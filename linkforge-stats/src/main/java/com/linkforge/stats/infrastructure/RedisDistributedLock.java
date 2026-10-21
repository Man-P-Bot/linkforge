package com.linkforge.stats.infrastructure;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis 分布式锁：单实例互斥的定时任务护栏（阶段 3 的 Q4）。
 *
 * <h2>为什么需要它</h2>
 * 本项目按「无状态多实例」设计——同一个镜像会同时跑多个容器。
 * 而 {@code @Scheduled} 是<b>每个 JVM 各自触发</b>的：
 * 3 个实例 = 每分钟 3 次 flush。若不加锁，同一批增量会被多个实例同时落库：
 * <ul>
 *   <li>PV 是<b>累加</b>语义 → 同一份 delta 被加 3 次，PV 虚高 3 倍</li>
 *   <li>UV 是<b>覆盖</b>语义 → 重复执行无害，但 PV 的错已经不可逆</li>
 * </ul>
 *
 * <h2>为什么不用 {@code @SchedulerLock}（ShedLock）</h2>
 * ShedLock 需要额外依赖 + 一张锁表 + 一套配置。本场景的锁语义极简
 * （互斥 + 自动过期 + 防误删），用 Redis 原生命令实现只有 30 行，
 * 且锁与业务数据在同一个 Redis 里，运维排查时「看锁」和「看数据」是同一条命令。
 *
 * <h2>⚠️ 加锁：{@code SET key value NX EX ttl}</h2>
 * 必须是<b>一条命令</b>，不能拆成「SETNX」+「EXPIRE」：
 * <pre>
 *   1) SETNX lock instanceId   -&gt; 成功
 *   2) &lt;进程崩溃&gt;                      -&gt; 锁没有 TTL，永久存在
 *   3) 其他实例从此再也拿不到锁
 * </pre>
 * 这个窗口只有几毫秒，但它<b>一旦发生就是永久性的</b>：
 * 锁不会自己过期，只能人工删 key。
 * {@code SET ... NX EX} 由 Redis 保证两个动作的原子性。
 *
 * <h2>⚠️ 释放：必须用 Lua 校验 value，不能直接 DEL</h2>
 * 朴素写法会踩一个非常隐蔽的故障：
 * <pre>
 *   实例 A: SET lock A EX 60  -&gt; 成功
 *   实例 A: flush 跑了 70 秒（锁已过期）
 *   实例 B: SET lock B EX 60  -&gt; 成功（B 现在持有锁）
 *   实例 A: DEL lock          -&gt; ❌ 把 B 的锁删了！
 *   实例 C: SET lock C EX 60  -&gt; 成功（A、B、C 三者同时在 flush）
 * </pre>
 * 释放时校验 value 是自己，才说明「这个锁确实是我加的、也确实过期没人接管」：
 * <pre>
 *   if redis.call("get", KEYS[1]) == ARGV[1] then
 *       return redis.call("del", KEYS[1])
 *   else
 *       return 0
 *   end
 * </pre>
 * 校验与删除之间也不能有间隙，所以必须用 Lua（Redis 单线程执行脚本，脚本内不会被打断）。
 *
 * <h2>⚠️ TTL 定 60 秒的依据</h2>
 * 单轮 flush 的最坏耗时：{@code 重试 1s + 4s + 16s = 21s} × PV/UV 两路，
 * 加上 Redis/MySQL 往返，正常应在数秒内完成。60 秒留了约 2~3 倍余量：
 * <ul>
 *   <li>TTL <b>不能短于</b>单轮最坏耗时，否则会出现「A 超时仍在跑、B 已进入」的双跑。
 *       双跑本身不致命（取走即删让重复执行天然幂等），但会浪费一轮数据库写入。</li>
 *   <li>TTL <b>不能太长</b>：实例崩溃后锁要等 TTL 过期才能被接管，
 *       TTL=1 小时意味着崩溃后统计停摆 1 小时。</li>
 * </ul>
 * 本类不实现「自动续期」（watchdog）：flush 是短任务，续期反而引入了
 * 「续期线程与业务线程的竞态」这一类更难的 bug。
 */
@Component
public class RedisDistributedLock {

    /**
     * 释放锁的 Lua：<b>只有 value 匹配才删</b>。
     *
     * <p>⚠️ 用 {@code ==} 而不是 {@code equals}：Lua 会把
     * {@code GET} 的返回值转成 <b>boolean</b> 再比较，
     * 对字符串而言 {@code ==} 就是值比较（与 Java 的 {@code equals} 等价），
     * 但如果误写成 Java 的 {@code .equals} 语义会引入类型转换问题。
     */
    private static final String RELEASE_SCRIPT = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            else
                return 0
            end
            """;

    private final StringRedisTemplate redis;

    /**
     * 本实例的唯一标识，写入锁的 value。
     *
     * <p>⚠️ <b>必须每次启动都不同</b>：若所有实例共用一个固定值（如主机名），
     * 释放锁的校验就形同虚设——A 可以删掉 B 的锁。用随机 UUID 是最省事的做法。
     */
    private final String instanceId = UUID.randomUUID().toString();

    private DefaultRedisScript<Long> releaseScript;

    public RedisDistributedLock(StringRedisTemplate redis) {
        this.redis = redis;
        this.releaseScript = luaScript(RELEASE_SCRIPT);
    }

    /**
     * 从内存字符串构造 {@link DefaultRedisScript}。
     *
     * <p>⚠️ 这里不用 {@code setLocation(ClassPathResource)}：
     * Spring Data Redis 3.5 <b>没有 {@code ResourceScriptSource}</b> 这个类，
     * 只 {@code setLocation} 会让 {@code getScriptSource()} 保持 null，
     * 执行时报「script source must not be null」。
     * {@code setScriptText} 同时避开这个问题，且不需要额外依赖
     * （与 {@code AsyncStatsQueue} 里踩过的同一个坑）。
     */
    private static DefaultRedisScript<Long> luaScript(String text) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(text);
        script.setResultType(Long.class);
        return script;
    }

    /**
     * 尝试加锁。
     *
     * <p>⚠️ <b>本方法不抛异常</b>：Redis 不可达时返回 {@code false}（视为没抢到锁），
     * 而不是把异常抛给调用方。理由与限流降级一致——
     * <b>抢不到锁只是「这轮不落库」，而抛异常会让整轮 flush 崩掉</b>。
     *
     * @param lockKey 锁的 Redis key
     * @param ttl     锁的自动过期时间
     * @return true = 抢到锁；false = 锁被他人持有 或 Redis 不可用
     */
    public boolean tryLock(String lockKey, Duration ttl) {
        try {
            // ⚠️ SET key value NX EX ttl —— 一条命令保证「加锁 + 设过期」的原子性。
            //    返回 Boolean（可能为 null：Redis 正常返回时不会是 null，
            //    但连接异常时 Lettuce 可能给出 null），统一按「false」处理。
            Boolean ok = redis.opsForValue()
                    .setIfAbsent(lockKey, instanceId, ttl);
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            // ⚠️ Redis 挂了不是「可以绕过锁」的理由：
            //    绕过意味着所有实例同时落库，PV 直接虚高。
            //    返回 false 让本轮 flush 跳过，下一分钟再试。
            return false;
        }
    }

    /**
     * 释放锁（<b>Lua 校验 value</b>，不误删他人的锁）。
     *
     * <p>⚠️ <b>本方法同样不抛异常</b>：释放失败最坏后果是「锁等 TTL 自然过期」，
     * 远小于「释放时抛异常打断 finally 里的其他清理」。
     *
     * @return true = 确实删掉了（value 匹配）；false = 锁已不属于本实例 或 Redis 不可用
     */
    public boolean unlock(String lockKey) {
        try {
            Long deleted = redis.execute(releaseScript,
                    Collections.singletonList(lockKey),
                    instanceId);
            return deleted != null && deleted > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 在锁的持有者校验下执行一段逻辑（模板方法）。
     *
     * <p>⚠️ <b>执行前会再校验一次 value</b>：这是防「超时实例继续破坏」的兜底。
     * 场景——本实例加锁后业务跑了 90 秒（超过 60 秒 TTL），锁已被别人接管并持有，
     * 此时本实例若继续落库就会与新持有者并发写。
     * 校验失败就放弃本轮：<b>宁可这一批数据多等一分钟，也不能重复累加 PV</b>。
     *
     * <p>⚠️ {@code action} 抛出的异常会原样传播给调用方，
     * 但 {@link #unlock} <b>一定会在 finally 里执行</b>。
     */
    public boolean runWithLock(String lockKey, Duration ttl, Runnable action) {
        if (!tryLock(lockKey, ttl)) {
            return false;
        }
        try {
            // ⚠️ 二次校验：见本方法 javadoc。锁过期后我们已不是持有者。
            if (!holdsLock(lockKey)) {
                return false;
            }
            action.run();
            return true;
        } finally {
            unlock(lockKey);
        }
    }

    /**
     * 当前是否仍持有该锁（比对 value）。
     *
     * <p>⚠️ Redis 不可达时返回 {@code false}（保守）：
     * 「无法确认自己还持有锁」与「锁丢了」必须做同样的处置——放弃本轮。
     * 若返回 true 而实际锁已丢，就会出现双跑。
     */
    public boolean holdsLock(String lockKey) {
        try {
            String holder = redis.opsForValue().get(lockKey);
            return instanceId.equals(holder);
        } catch (Exception e) {
            return false;
        }
    }

    /** 本实例标识，仅用于日志与测试断言。 */
    public String instanceId() {
        return instanceId;
    }

    /** 仅供测试替换脚本用（生产不走这条路）。 */
    RedisDistributedLock withReleaseScript(DefaultRedisScript<Long> script) {
        this.releaseScript = script;
        return this;
    }
}
