package com.linkforge.stats.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * {@link RedisDistributedLock} 的行为契约。
 *
 * <h2>⚠️ 本类锁的是两条「不加锁就一定会出事」的红线</h2>
 * <ol>
 *   <li><b>SET 必须是「加锁 + 设过期」的一条命令</b>。拆成 SETNX + EXPIRE 时，
 *       两条之间崩溃会留下一个<b>永不过期</b>的锁——只能人工删 key 才能恢复。</li>
 *   <li><b>释放必须校验 value</b>。不校验会误删他人的锁，导致多实例同时落库，
 *       而 PV 是累加语义，重复落库会让数据<b>虚高且不可回滚</b>。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedisDistributedLockTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;

    @BeforeEach
    void setUp() {
        // ⚠️ 必须 stub opsForValue()：它返回 null 时链式调用直接 NPE，
        //    报错点会落在测试方法里而不是「没抢到锁」的语义上，很难定位。
        when(redis.opsForValue()).thenReturn(valueOps);
    }

    private RedisDistributedLock newLock() {
        return new RedisDistributedLock(redis);
    }

    // ==================================================== 加锁

    @Test
    @DisplayName("🔒️ 加锁只有一条 setIfAbsent(key, value, ttl) —— 不拆成 SETNX + EXPIRE")
    void tryLockUsesSingleAtomicSetIfAbsent() {
        when(valueOps.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true);
        RedisDistributedLock lock = newLock();

        assertThat(lock.tryLock("k", Duration.ofSeconds(60))).isTrue();

        // ⚠️ 核心断言：只有一次调用，且带 TTL。
        // 若代码退化成「setIfAbsent(key,value) + expire(key,ttl)」两条，
        // 两条命令之间的崩溃窗口会留下永久锁——这正是本条红线要防的。
        verify(valueOps, times(1)).setIfAbsent(eq("k"), any(String.class),
                eq(Duration.ofSeconds(60)));
        verify(redis, never()).expire(any(String.class), any(Long.class), any(TimeUnit.class));
    }

    @Test
    @DisplayName("🔒️b Redis 返回 null / false 都算「没抢到」，绝不当作抢到")
    void tryLockTreatsNullAsFailure() {
        when(valueOps.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(null);
        assertThat(newLock().tryLock("k", Duration.ofSeconds(60)))
                .as("⚠️ Boolean 拆箱 null 会 NPE；必须按 false 处理")
                .isFalse();
    }

    @Test
    @DisplayName("🔒️c Redis 不可用时返回 false，**不抛异常**（抛异常会让整轮 flush 崩掉）")
    void tryLockSwallowsRedisFailure() {
        when(valueOps.setIfAbsent(any(), any(), any(Duration.class)))
                .thenThrow(new RuntimeException("连接超时"));
        assertThat(newLock().tryLock("k", Duration.ofSeconds(60))).isFalse();
    }

    @Test
    @DisplayName("🔒️d 每个实例的 value 都不同（否则释放锁的校验形同虚设）")
    void instanceIdIsUniquePerInstance() {
        assertThat(newLock().instanceId())
                .as("⚠️ 用随机 UUID：固定值（如主机名）会让 A 能删掉 B 的锁")
                .isNotEqualTo(newLock().instanceId());
    }

    // ==================================================== 释放

    @Test
    @DisplayName("🔓️ 释放走 Lua 且 value 是本实例 ID（不是直接 DEL）")
    void unlockUsesLuaWithOwnInstanceId() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L);
        RedisDistributedLock lock = newLock();

        assertThat(lock.unlock("k")).isTrue();

        var script = org.mockito.ArgumentCaptor.forClass(RedisScript.class);
        var keys = org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        var argv = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(redis).execute(script.capture(), keys.capture(), argv.capture());

        assertThat(script.getValue().getScriptAsString())
                .as("⚠️ 释放脚本必须比对 value。直接 DEL 会误删他人的锁")
                .contains("get", "del");
        assertThat(keys.getValue()).containsExactly("k");
        assertThat(argv.getValue())
                .as("ARGV[1] 必须是本实例 ID")
                .containsExactly(lock.instanceId());
    }

    @Test
    @DisplayName("🔓️b Lua 返回 0（锁已不属于本实例）时 unlock 返回 false，**不报错**")
    void unlockReturnsFalseWhenValueMismatch() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(0L);
        assertThat(newLock().unlock("k")).isFalse();
    }

    @Test
    @DisplayName("🔓️c 释放时 Redis 挂掉返回 false，绝不让异常打断 finally 的其它清理")
    void unlockSwallowsRedisFailure() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RuntimeException("连接断开"));
        assertThat(newLock().unlock("k")).isFalse();
    }

    // ==================================================== 二次校验

    @Test
    @DisplayName("⏱️⭐⭐⭐ holdsLock：锁过期被他人接管后返回 false")
    void holdsLockFalseWhenTakenOverByOther() {
        // ⚠️ 模拟「A 加锁后业务跑了 90 秒，锁过期，B 已接管」
        when(valueOps.get("k")).thenReturn("someone-else-uuid");
        assertThat(newLock().holdsLock("k")).isFalse();
    }

    @Test
    @DisplayName("⏱️b holdsLock 在 Redis 不可用时返回 false（保守：无法确认 == 已丢）")
    void holdsLockFalseWhenRedisDown() {
        // ⚠️ 若这里返回 true，flush 会以为锁还在而继续落库 -> 与新持有者并发写
        when(valueOps.get(any(String.class))).thenThrow(new RuntimeException("连接断开"));
        assertThat(newLock().holdsLock("k")).isFalse();
    }

    @Test
    @DisplayName("⏱️c runWithLock：锁被接管时**不执行 action**（防并发落库导致 PV 重复累加）")
    void runWithLockSkipsActionWhenLockLost() {
        when(valueOps.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true);
        when(valueOps.get("k")).thenReturn("other-instance");
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L);
        AtomicBoolean ran = new AtomicBoolean(false);

        boolean result = newLock().runWithLock("k", Duration.ofSeconds(60), () -> ran.set(true));

        assertThat(result).isFalse();
        assertThat(ran.get())
                .as("⚠️ 锁已不属于本实例，必须放弃本轮。PV 重复累加不可回滚")
                .isFalse();
    }

    @Test
    @DisplayName("⏱️d runWithLock：抢不到锁时 action 不执行，且**不调 unlock**")
    void runWithLockDoesNothingWhenLockUnavailable() {
        when(valueOps.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(false);

        AtomicBoolean ran = new AtomicBoolean(false);
        boolean result = newLock().runWithLock("k", Duration.ofSeconds(60), () -> ran.set(true));

        assertThat(result).isFalse();
        assertThat(ran.get()).isFalse();
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    @DisplayName("⏱️e runWithLock：正常路径执行 action 且释放锁；action 抛异常也**一定释放**")
    void runWithLockAlwaysUnlocks() {
        when(valueOps.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L);
        RedisDistributedLock lock = newLock();
        when(valueOps.get("k")).thenReturn(lock.instanceId());

        AtomicBoolean ran = new AtomicBoolean(false);
        assertThat(lock.runWithLock("k", Duration.ofSeconds(60), () -> ran.set(true))).isTrue();
        assertThat(ran.get()).isTrue();

        // action 抛异常时，finally 仍必须释放
        try {
            lock.runWithLock("k", Duration.ofSeconds(60), () -> {
                throw new IllegalStateException("业务异常");
            });
        } catch (IllegalStateException expected) {
            // 预期：异常原样传播（调用方自己负责 catch-all）
        }
        verify(redis, times(2)).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }
}
