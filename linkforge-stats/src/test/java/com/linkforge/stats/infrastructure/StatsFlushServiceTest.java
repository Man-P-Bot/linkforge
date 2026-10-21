package com.linkforge.stats.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.linkforge.core.repository.LinkRepository;
import com.linkforge.core.repository.StatsRepository;
import com.linkforge.core.repository.StatsRepository.PvRow;
import com.linkforge.core.repository.StatsRepository.UvRow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.HyperLogLogOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * {@link StatsFlushService} 的行为契约（阶段 3）。
 *
 * <h2>⚠️ 本类锁的是六条「不加就一定会出事」的红线</h2>
 * <ol>
 *   <li><b>@Scheduled 抛异常 = 调度永久失效</b>（Spring 不会重建 Trigger）</li>
 *   <li><b>SPOP 取走即删</b>，不能 SMEMBERS + SREM（会删掉期间新增的短码）</li>
 *   <li><b>GETDEL 原子取删</b>，不能先 GET 再 DEL（崩溃即重复落库，PV 翻倍）</li>
 *   <li><b>PV 与 UV 分开落库</b>（合并后重试会重复累加已成功的 PV）</li>
 *   <li><b>DLQ 靶向重试</b>：target=pv 只补 PV，绝不在一次重试里同时更新两者</li>
 *   <li><b>queue.isRunning() 守卫</b>（消费线程已死时不做它下游的事）</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StatsFlushServiceTest {

    private SimpleMeterRegistry registry;

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private SetOperations<String, String> setOps;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private ListOperations<String, String> listOps;
    @Mock
    private HyperLogLogOperations<String, String> hllOps;
    @Mock
    private RedisDistributedLock lock;
    @Mock
    private LinkRepository links;
    @Mock
    private StatsRepository stats;
    @Mock
    private AsyncStatsQueue queue;

    /** 退避设为 0，让重试路径的测试瞬间完成。 */
    private StatsFlushService newService() {
        return new StatsFlushService(registry, redis, lock, links, stats, queue,
                500, 20, 2, 60, "0,0,0");
    }

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        when(redis.opsForSet()).thenReturn(setOps);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForList()).thenReturn(listOps);
        when(redis.opsForHyperLogLog()).thenReturn(hllOps);
        // 默认：抢到锁且仍持有
        when(lock.tryLock(anyString(), any(Duration.class))).thenReturn(true);
        when(lock.holdsLock(anyString())).thenReturn(true);
        // 默认：消费线程在运行
        when(queue.isRunning()).thenReturn(true);
        // 默认：dirty set 空
        when(setOps.pop(anyString(), anyLong())).thenReturn(List.of());
        when(listOps.range(anyString(), anyLong(), anyLong())).thenReturn(List.of());
        // 默认：短码映射
        when(links.resolveIdsByShortCodes(any())).thenReturn(Map.of());
        // 默认：PV / UV 都读不到
        when(valueOps.getAndDelete(anyString())).thenReturn(null);
        when(hllOps.size(anyString())).thenReturn(0L);
    }

    private double counter(String name, String... tagPairs) {
        var search = registry.find(name);
        for (int i = 0; i + 1 < tagPairs.length; i += 2) {
            search = search.tag(tagPairs[i], tagPairs[i + 1]);
        }
        var c = search.counter();
        return c == null ? 0.0 : c.count();
    }

    /** 让 dirty set 在第一次 SPOP 时返回给定短码，之后返回空。 */
    private void dirtyOnce(String... codes) {
        when(setOps.pop(anyString(), anyLong()))
                .thenReturn(List.of(codes))
                .thenReturn(List.of());
    }

    // ============================================ 红线 1：@Scheduled 不许抛异常

    @Test
    @DisplayName("🛡️⭐⭐⭐ flush() 遇到任何异常都不外抛（外抛会让 Spring 永久取消该调度）")
    void flushNeverThrows() {
        doThrow(new RuntimeException("Redis 炸了")).when(setOps)
                .pop(anyString(), anyLong());

        // ⚠️ 关键：这里不 assertThatThrownBy，而是「能正常返回」就是通过
        newService().flush();

        assertThat(counter("shortlink.stats.flush.date.failed"))
                .as("⚠️ 单日失败被隔离时**不能**记成整轮 failed——"
                        + "前者说明调度存活、只是某个日期有问题；后者说明调度崩了。"
                        + "混为一谈会让告警无法定位")
                .isEqualTo(2.0);
        assertThat(counter("shortlink.stats.flush.runs", "result", "failed"))
                .as("隔离成功的失败不算整轮失败").isZero();
    }

    @Test
    @DisplayName("🛡️b 抛 Error（非 Exception）同样被吞 —— 用 catch Exception 会留下定时炸弹")
    void flushSwallowsErrors() {
        doThrow(new NoSuchMethodError("NoClassDefFoundError 的近亲")).when(setOps)
                .pop(anyString(), anyLong());

        newService().flush();

        // ⚠️ Error 逃出 doFlush 的 catch(Exception)，一路到 flush() 的
        //    catch(Throwable) 才被接住 —— 这正是必须用 Throwable 的原因
        assertThat(counter("shortlink.stats.flush.runs", "result", "failed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("🛡️c 单日失败**不阻断**其它日期（昨天的数据不因今天出错而一起滞留）")
    void oneDateFailureDoesNotBlockOthers() {
        // 今天第一次 pop 抛异常，昨天正常返回空
        when(setOps.pop(anyString(), anyLong()))
                .thenThrow(new RuntimeException("今天的 dirty set 读不了"))
                .thenReturn(List.of());

        newService().flush();

        assertThat(counter("shortlink.stats.flush.date.failed")).isEqualTo(1.0);
        assertThat(counter("shortlink.stats.flush.runs", "result", "ok"))
                .as("整轮依然算成功").isEqualTo(1.0);
    }

    // ============================================ 红线 6：运行状态守卫

    @Test
    @DisplayName("⏸️⭐⭐ 消费线程未运行时整轮跳过，且**不碰 Redis、不抢锁**")
    void skipsWhenQueueNotRunning() {
        when(queue.isRunning()).thenReturn(false);

        newService().flush();

        assertThat(counter("shortlink.stats.flush.runs", "result", "skipped_stopped")).isEqualTo(1.0);
        verify(lock, never()).tryLock(anyString(), any(Duration.class));
        verifyNoInteractions(stats);
        verifyNoInteractions(links);
    }

    // ============================================ 分布式锁

    @Test
    @DisplayName("🔒️ 抢不到锁就整轮跳过（多实例下正常，不算错误）")
    void skipsWhenLockUnavailable() {
        when(lock.tryLock(anyString(), any(Duration.class))).thenReturn(false);

        newService().flush();

        assertThat(counter("shortlink.stats.flush.runs", "result", "skipped_lock")).isEqualTo(1.0);
        verifyNoInteractions(stats);
    }

    @Test
    @DisplayName("🔒️b 执行前二次校验失败也放弃本轮（防并发落库导致 PV 重复累加）")
    void skipsWhenLockLostBeforeExecution() {
        when(lock.holdsLock(anyString())).thenReturn(false);

        newService().flush();

        assertThat(counter("shortlink.stats.flush.runs", "result", "skipped_lock")).isEqualTo(1.0);
        verifyNoInteractions(stats);
    }

    @Test
    @DisplayName("🔒️c 锁用固定常量 shortlink:stats:flush:lock，且**总是被释放**")
    void lockKeyIsConstantAndAlwaysReleased() {
        newService().flush();
        verify(lock).tryLock(eq("shortlink:stats:flush:lock"), eq(Duration.ofSeconds(60)));
        verify(lock).unlock("shortlink:stats:flush:lock");
    }

    @Test
    @DisplayName("🔒️d 即使业务抛异常也释放锁（finally）")
    void lockReleasedEvenOnFailure() {
        doThrow(new RuntimeException("boom")).when(setOps).pop(anyString(), anyLong());

        newService().flush();

        verify(lock).unlock("shortlink:stats:flush:lock");
    }

    // ============================================ 红线 2：SPOP 而非 SMEMBERS + SREM

    @Test
    @DisplayName("📤⭐⭐⭐ 取短码只用 SPOP key count，**绝不出现 SMEMBERS / SREM**")
    void usesSpopAndNeverSmembers() {
        dirtyOnce("0Q1A2b3");
        when(links.resolveIdsByShortCodes(any())).thenReturn(Map.of("0Q1A2b3", 1L));
        when(valueOps.getAndDelete(anyString())).thenReturn("5");

        newService().flush();

        verify(setOps, times(2)).pop(eq("shortlink:stats:dirty:" + today()), eq(500L));
        // ⚠️ 旧路径必须彻底消失：SMEMBERS 读全量 + SREM 清理会在两者之间
        //    把「新登记进来的短码」一起删掉，那批数据既不会被本轮处理，
        //    也不会被下一轮处理——静默烂在 Redis 里直到 TTL 过期。
        verify(setOps, never()).members(anyString());
        verify(setOps, never()).remove(anyString(), any(Object[].class));
    }

    // ============================================ 红线 3：GETDEL

    @Test
    @DisplayName("📥⭐⭐⭐ PV 取数用 getAndDelete（GETDEL），**绝不先 GET 再单独 DEL**")
    void usesGetAndDelete() {
        dirtyOnce("0Q1A2b3");
        when(links.resolveIdsByShortCodes(any())).thenReturn(Map.of("0Q1A2b3", 7L));
        when(valueOps.getAndDelete(anyString())).thenReturn("42");

        newService().flush();

        verify(valueOps).getAndDelete("shortlink:pv:0Q1A2b3:" + today());
        // ⚠️ 旧路径必须彻底消失：先 GET 再 DEL 之间崩溃 -> 同一份增量落两次库
        verify(redis, never()).delete(anyString());
    }

    @Test
    @DisplayName("📥b PV key 不存在（返回 null）时不落那一行，且不报错")
    void missingPvKeyIsSkipped() {
        dirtyOnce("0Q1A2b3");
        when(links.resolveIdsByShortCodes(any())).thenReturn(Map.of("0Q1A2b3", 7L));
        when(valueOps.getAndDelete(anyString())).thenReturn(null);

        newService().flush();

        verify(stats, never()).accumulatePv(any());
    }

    @Test
    @DisplayName("📥c PV 值为 0 时不落库（避免创建全是 0 的空行）")
    void zeroPvIsSkipped() {
        dirtyOnce("0Q1A2b3");
        when(links.resolveIdsByShortCodes(any())).thenReturn(Map.of("0Q1A2b3", 7L));
        when(valueOps.getAndDelete(anyString())).thenReturn("0");

        newService().flush();

        verify(stats, never()).accumulatePv(any());
    }

    // ============================================ 红线 4：PV / UV 分开

    @Test
    @DisplayName("✂️⭐⭐⭐ PV 与 UV 分两次调用（合并后重试会重复累加已成功的 PV）")
    void pvAndUvAreWrittenSeparately() {
        dirtyOnce("0Q1A2b3");
        when(links.resolveIdsByShortCodes(any())).thenReturn(Map.of("0Q1A2b3", 7L));
        when(valueOps.getAndDelete(anyString())).thenReturn("3");
        when(hllOps.size(anyString())).thenReturn(11L);

        newService().flush();

        // ⚠️ 两路独立调用，而不是一个「同时更新两者」的方法
        verify(stats).accumulatePv(any());
        verify(stats).mergeUv(any());

        var pvRows = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(stats).accumulatePv(pvRows.capture());
        assertThat(pvRows.getValue()).hasSize(1);
        assertThat(pvRows.getValue().iterator().next())
                .isEqualTo(new PvRow(7L, LocalDate.now(), 3L));

        var uvRows = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(stats).mergeUv(uvRows.capture());
        assertThat(uvRows.getValue().iterator().next())
                .isEqualTo(new UvRow(7L, LocalDate.now(), 11L));
    }

    @Test
    @DisplayName("✂️b UV 值为 0 时不调 mergeUv（当天没有 visitor）")
    void zeroUvIsSkipped() {
        dirtyOnce("0Q1A2b3");
        when(links.resolveIdsByShortCodes(any())).thenReturn(Map.of("0Q1A2b3", 7L));
        when(valueOps.getAndDelete(anyString())).thenReturn("3");
        when(hllOps.size(anyString())).thenReturn(0L);

        newService().flush();

        verify(stats).accumulatePv(any());
        verify(stats, never()).mergeUv(any());
    }

    @Test
    @DisplayName("✂️c PV 失败**不影响** UV 落库（两路互不牵连）")
    void pvFailureDoesNotBlockUv() {
        dirtyOnce("0Q1A2b3");
        when(links.resolveIdsByShortCodes(any())).thenReturn(Map.of("0Q1A2b3", 7L));
        when(valueOps.getAndDelete(anyString())).thenReturn("3");
        when(hllOps.size(anyString())).thenReturn(11L);
        doThrow(new RuntimeException("PV 写入失败")).when(stats).accumulatePv(any());

        newService().flush();

        verify(stats).mergeUv(any());
    }

    // ============================================ 重试与 DLQ

    @Test
    @DisplayName("🔁⭐⭐ PV 首次失败 + 重试成功 -> 不写 DLQ")
    void retriesThenSucceedsWithoutDlq() {
        dirtyOnce("0Q1A2b3");
        when(links.resolveIdsByShortCodes(any())).thenReturn(Map.of("0Q1A2b3", 7L));
        when(valueOps.getAndDelete(anyString())).thenReturn("3");
        // ⚠️ accumulatePv 返回 int，不能用 doNothing()（那是 void 方法专用的）
        doThrow(new RuntimeException("第一次失败")).doReturn(1).when(stats)
                .accumulatePv(any());

        newService().flush();

        verify(stats, times(2)).accumulatePv(any());
        verify(listOps, never()).rightPushAll(anyString(), anyList());
        assertThat(counter("shortlink.stats.flush.rows", "result", "ok")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("🔁b 重试耗尽（首次 + 3 次 = 4 次调用）后写 DLQ，payload 带 target/stage=mysql")
    void retriesThreeTimesThenWritesDlq() {
        dirtyOnce("0Q1A2b3");
        when(links.resolveIdsByShortCodes(any())).thenReturn(Map.of("0Q1A2b3", 7L));
        when(valueOps.getAndDelete(anyString())).thenReturn("3");
        doThrow(new RuntimeException("一直失败")).when(stats).accumulatePv(any());

        newService().flush();

        // ⚠️ 3 次重试 = 共 4 次调用（1 次首发 + 3 次重试）
        verify(stats, times(4)).accumulatePv(any());

        var payloads = ArgumentCaptor.forClass(List.class);
        verify(listOps).rightPushAll(eq("shortlink:stats:dl:" + today()), payloads.capture());
        assertThat(payloads.getValue())
                .as("payload 必须带 stage=mysql（Q5）与 channel=pv，否则重放方向会错")
                .hasSize(1);
        assertThat((String) payloads.getValue().get(0))
                .isEqualTo("v1|mysql|pv|7|" + LocalDate.now() + "|3");
        assertThat(counter("shortlink.stats.flush.rows", "result", "failed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("🔁c UV 重试耗尽写 DLQ 时 channel=uv（**不是** pv）")
    void uvDlqPayloadCarriesUvChannel() {
        dirtyOnce("0Q1A2b3");
        when(links.resolveIdsByShortCodes(any())).thenReturn(Map.of("0Q1A2b3", 7L));
        when(hllOps.size(anyString())).thenReturn(9L);
        doThrow(new RuntimeException("UV 一直失败")).when(stats).mergeUv(any());

        newService().flush();

        var payloads = ArgumentCaptor.forClass(List.class);
        verify(listOps).rightPushAll(eq("shortlink:stats:dl:" + today()), payloads.capture());
        assertThat((String) payloads.getValue().get(0))
                .isEqualTo("v1|mysql|uv|7|" + LocalDate.now() + "|9");
    }

    @Test
    @DisplayName("🔁d DLQ 写入也失败时计 dlq.lost{target=pv, stage=mysql}（光记日志不够）")
    void countsLostWhenDlqWriteAlsoFails() {
        dirtyOnce("0Q1A2b3");
        when(links.resolveIdsByShortCodes(any())).thenReturn(Map.of("0Q1A2b3", 7L));
        when(valueOps.getAndDelete(anyString())).thenReturn("3");
        doThrow(new RuntimeException("MySQL 失败")).when(stats).accumulatePv(any());
        doThrow(new RuntimeException("Redis 也失败")).when(listOps)
                .rightPushAll(anyString(), anyList());

        newService().flush();

        assertThat(counter("shortlink.stats.dlq.lost", "target", "pv", "stage", "mysql"))
                .as("运维不翻日志就看不到数据在丢，必须计指标").isEqualTo(1.0);
    }

    // ============================================ 红线 5：DLQ 靶向重试

    @Test
    @DisplayName("🎯⭐⭐⭐ DLQ 里的 target=pv 只调 accumulatePv，**绝不调 mergeUv**")
    void dlqReplayTargetsPvOnly() {
        when(listOps.range(eq("shortlink:stats:dl:" + today()), eq(0L), eq(499L)))
                .thenReturn(List.of("v1|mysql|pv|7|" + LocalDate.now() + "|3"));

        newService().flush();

        verify(stats).accumulatePv(any());
        // ⚠️ PV 死信重放绝不能碰 UV：合并会让已成功的 UV 用错语义
        verify(stats, never()).mergeUv(any());
        verify(listOps).remove(eq("shortlink:stats:dl:" + today()), eq(1L),
                eq("v1|mysql|pv|7|" + LocalDate.now() + "|3"));
    }

    @Test
    @DisplayName("🎯b DLQ 里的 target=uv 只调 mergeUv，**绝不调 accumulatePv**")
    void dlqReplayTargetsUvOnly() {
        when(listOps.range(eq("shortlink:stats:dl:" + today()), eq(0L), eq(499L)))
                .thenReturn(List.of("v1|mysql|uv|7|" + LocalDate.now() + "|9"));

        newService().flush();

        verify(stats).mergeUv(any());
        // ⚠️ UV 死信重放绝不能碰 PV：PV 已成功，重放会把它再累加一遍且不可回滚
        verify(stats, never()).accumulatePv(any());
    }

    @Test
    @DisplayName("🎯c DLQ 重放成功才删条目；失败保留在队列等下一轮（**不是一次性丢弃队列**）")
    void dlqEntryRemovedOnlyOnSuccess() {
        String payload = "v1|mysql|pv|7|" + LocalDate.now() + "|3";
        when(listOps.range(eq("shortlink:stats:dl:" + today()), eq(0L), eq(499L)))
                .thenReturn(List.of(payload));
        doThrow(new RuntimeException("还是失败")).when(stats).accumulatePv(any());

        newService().flush();

        verify(listOps, never()).remove(anyString(), anyLong(), anyString());
        assertThat(counter("shortlink.stats.dlq.retry", "result", "failed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("🎯d DLQ 混合 pv 与 uv 时，**各自只补自己那一路**")
    void dlqMixedTargetsStaySeparate() {
        when(listOps.range(eq("shortlink:stats:dl:" + today()), eq(0L), eq(499L)))
                .thenReturn(List.of(
                        "v1|mysql|pv|7|" + LocalDate.now() + "|3",
                        "v1|mysql|uv|8|" + LocalDate.now() + "|9"));

        newService().flush();

        var pvRows = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(stats).accumulatePv(pvRows.capture());
        assertThat(pvRows.getValue())
                .as("PV 批次里绝不能混入 UV 行").hasSize(1)
                .allMatch(r -> ((PvRow) r).linkId() == 7L);

        var uvRows = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(stats).mergeUv(uvRows.capture());
        assertThat(uvRows.getValue())
                .as("UV 批次里绝不能混入 PV 行").hasSize(1)
                .allMatch(r -> ((UvRow) r).linkId() == 8L);
    }

    @Test
    @DisplayName("🎯e 无法识别的 DLQ 条目被计入 unparsed，**且不删**（留待人工排查）")
    void unparsedDlqEntryIsCountedAndKept() {
        when(listOps.range(eq("shortlink:stats:dl:" + today()), eq(0L), eq(499L)))
                .thenReturn(List.of("这不是我认识的格式"));

        newService().flush();

        assertThat(counter("shortlink.stats.dlq.unparsed")).isEqualTo(1.0);
        verify(listOps, never()).remove(anyString(), anyLong(), anyString());
        verifyNoInteractions(stats);
    }

    @Test
    @DisplayName("🎯f DLQ 头部全是垃圾条目时整轮放弃（防每分钟空转）")
    void givesUpWhenDlqMostlyGarbage() {
        when(listOps.range(eq("shortlink:stats:dl:" + today()), eq(0L), eq(499L)))
                .thenReturn(java.util.stream.IntStream.range(0, 12)
                        .mapToObj(i -> "garbage-" + i).toList());

        newService().flush();

        verify(stats, never()).accumulatePv(any());
        assertThat(counter("shortlink.stats.dlq.unparsed")).isEqualTo(12.0);
    }

    // ============================================ 短码映射

    @Test
    @DisplayName("🔗 查不到 link_id 的短码被计入 unmapped（静默丢弃是无声缺陷）")
    void unmappedCodeIsCounted() {
        dirtyOnce("ghost01", "0Q1A2b3");
        when(links.resolveIdsByShortCodes(any())).thenReturn(Map.of("0Q1A2b3", 7L));
        when(valueOps.getAndDelete(anyString())).thenReturn("3");

        newService().flush();

        assertThat(counter("shortlink.stats.flush.unmapped")).isEqualTo(1.0);
        verify(stats).accumulatePv(any());
    }

    @Test
    @DisplayName("🔗b 映射查询失败时把短码**放回 dirty set**（否则 PV 滞留到 TTL 过期）")
    void restoresDirtyWhenMappingFails() {
        dirtyOnce("0Q1A2b3");
        doThrow(new RuntimeException("MySQL 查不到")).when(links).resolveIdsByShortCodes(any());

        newService().flush();

        verify(setOps).add(eq("shortlink:stats:dirty:" + today()), any(String[].class));
        // ⚠️ 关键：此时还没有 GETDEL 任何 PV，放回是安全的
        verify(valueOps, never()).getAndDelete(anyString());
    }

    // ============================================ 回看窗口与批次上限

    @Test
    @DisplayName("📅⭐⭐ 回看 2 天：今天的与昨天的 dirty set 都被处理（昨天的数据不会永久滞留）")
    void looksBackTwoDays() {
        newService().flush();

        verify(setOps).pop(eq("shortlink:stats:dirty:" + today()), eq(500L));
        verify(setOps).pop(eq("shortlink:stats:dirty:" + fmt(LocalDate.now().minusDays(1))),
                eq(500L));
    }

    @Test
    @DisplayName("📅b 单轮最多 maxBatchesPerRun 批（无上限会长期占着全局锁）")
    void respectsMaxBatchesPerRun() {
        when(setOps.pop(anyString(), anyLong())).thenReturn(List.of("c1"));

        newService().flush();

        // 回看 2 天 × 每天上限 20 批 = 40 次
        verify(setOps, times(40)).pop(anyString(), anyLong());
    }

    // ============================================ 配置校验

    @Test
    @DisplayName("⚙️ 退避表必须恰好 3 段（启动期暴露，不等运行期才发现）")
    void rejectsWrongBackoffLength() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new StatsFlushService(registry, redis, lock, links, stats, queue,
                        500, 20, 2, 60, "1000,4000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("3 段");

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new StatsFlushService(registry, redis, lock, links, stats, queue,
                        500, 20, 2, 60, "1000,4000,16000,64000"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("⚙️b 退避为 0 时立即重试（测试用，不 sleep）")
    void zeroBackoffDoesNotSleep() {
        StatsFlushService svc = new StatsFlushService(registry, redis, lock, links, stats, queue,
                500, 20, 2, 60, "0,0,0");

        assertThat(svc.retryBackoffMillis()).containsExactly(0L, 0L, 0L);
    }

    @Test
    @DisplayName("⚙️c 默认退避是 1s / 4s / 16s（Q5 硬性要求）")
    void defaultBackoffIsAsSpecified() {
        StatsFlushService svc = new StatsFlushService(registry, redis, lock, links, stats, queue,
                500, 20, 2, 60, "1000,4000,16000");

        assertThat(svc.retryBackoffMillis()).containsExactly(1000L, 4000L, 16000L);
    }

    private static String fmt(LocalDate d) {
        return d.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
    }

    private static String today() {
        return fmt(LocalDate.now());
    }
}
