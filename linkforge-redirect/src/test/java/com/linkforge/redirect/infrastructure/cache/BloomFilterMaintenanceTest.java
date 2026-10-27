package com.linkforge.redirect.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.linkforge.core.repository.LinkRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 布隆维护编排层测试（DR-065）。
 *
 * <h2>为什么这些断言必须存在</h2>
 * 事故根因是<b>顺序错了</b>：重建的 {@code beginRebuild()}（开写穿窗口 + 删 :building）
 * 必须<b>早于数据库扫描</b>，否则扫描期间创建的短码既不在快照里、也没被写穿 → 404。
 *
 * <p>这个顺序用<b>代码审查是看不出来</b>的——两行调用换个位置，编译照样通过、
 * 单测照样全绿（因为没有任何测试断言过顺序）。所以这里用 Mockito 的
 * {@link InOrder} 把它<b>锁死</b>。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BloomFilterMaintenanceTest {

    private static final String CODE_A = "0Q1A2b3";
    private static final String CODE_B = "0Q1A2b4";

    @Mock
    private RedisBloomFilter bloomFilter;

    @Mock
    private LinkRepository linkRepository;

    private SimpleMeterRegistry registry;
    private BloomFilterMaintenance maintenance;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        maintenance = new BloomFilterMaintenance(bloomFilter, linkRepository, registry);
    }

    @Test
    @DisplayName("⭐⭐⭐ DR-065：必须**先开写穿窗口再扫描数据库**（顺序错了就 404）")
    void opensWriteThroughWindowBeforeScanning() {
        given(linkRepository.scanActiveWithId(anyLong(), anyInt()))
                .willReturn(List.of(new LinkRepository.IdShortCode(1L, CODE_A)))
                .willReturn(List.of());
        given(bloomFilter.rebuild(any())).willReturn(1L);

        maintenance.rebuild();

        // ⚠️ 核心断言：顺序 = 开窗口 → 扫描 → 写位切换。
        //    若扫描在前，扫描期间新建的短码会既不在快照里、也没被写穿 → 404。
        InOrder order = inOrder(bloomFilter, linkRepository);
        order.verify(bloomFilter).beginRebuild();
        order.verify(linkRepository).scanActiveWithId(anyLong(), anyInt());
        order.verify(bloomFilter).rebuild(any());
        order.verify(bloomFilter).endRebuild();
    }

    @Test
    @DisplayName("⭐⭐ DR-065：写位切换抛异常时也必须关窗口（否则写穿永远双写）")
    void closesWindowEvenWhenRebuildFails() {
        given(linkRepository.scanActiveWithId(anyLong(), anyInt())).willReturn(List.of());
        doThrow(new IllegalStateException("redis down"))
                .when(bloomFilter).rebuild(any());

        assertThatCode(() -> maintenance.rebuild()).isInstanceOf(IllegalStateException.class);

        verify(bloomFilter).endRebuild();
    }

    @Test
    @DisplayName("⭐⭐ DR-065：启动即重建（ApplicationReadyEvent）——重启后旧短码立刻可用")
    void rebuildsOnApplicationReady() {
        given(linkRepository.scanActiveWithId(anyLong(), anyInt()))
                .willReturn(List.of(new LinkRepository.IdShortCode(1L, CODE_A)))
                .willReturn(List.of());
        given(bloomFilter.rebuild(any())).willReturn(1L);

        maintenance.rebuildOnStartup();

        verify(bloomFilter).rebuild(any());
        assertThat(registry.get("shortlink.bloom.rebuild").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("⭐⭐ DR-065：启动重建失败**不阻止启动**，只记失败指标")
    void startupRebuildFailureDoesNotThrow() {
        doThrow(new IllegalStateException("db down"))
                .when(bloomFilter).beginRebuild();

        assertThatCode(() -> maintenance.rebuildOnStartup()).doesNotThrowAnyException();

        assertThat(registry.get("shortlink.bloom.rebuild.failed").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("shortlink.bloom.rebuild").counter().count()).isZero();
    }

    @Test
    @DisplayName("⭐ 定时重建失败**不能抛出去**（抛出去会让 Spring 取消后续调度）")
    void scheduledRebuildSwallowsException() {
        doThrow(new IllegalStateException("db down"))
                .when(bloomFilter).beginRebuild();

        assertThatCode(() -> maintenance.scheduledRebuild()).doesNotThrowAnyException();

        assertThat(registry.get("shortlink.bloom.rebuild.failed").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("⭐ 库中没有有效短码 → 跳过切换，不产生「重建完成」计数")
    void emptyDatabaseSkipsSwitch() {
        given(linkRepository.scanActiveWithId(anyLong(), anyInt())).willReturn(List.of());
        given(bloomFilter.rebuild(any())).willReturn(0L);

        long written = maintenance.rebuild();

        assertThat(written).isZero();
        assertThat(registry.get("shortlink.bloom.rebuild").counter().count()).isZero();
    }

    @Test
    @DisplayName("⭐ 重建进行中时并发重建直接跳过（不重复扫全库）")
    void concurrentRebuildIsSkipped() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        given(linkRepository.scanActiveWithId(anyLong(), anyInt())).willAnswer(inv -> {
            entered.countDown();
            release.await(2, TimeUnit.SECONDS);
            return List.of();
        });

        Thread first = new Thread(() -> maintenance.rebuild());
        first.start();
        assertThat(entered.await(2, TimeUnit.SECONDS)).as("第一次重建应进入扫描").isTrue();

        // 并发的第二次重建：必须被互斥挡住（返回 0，且不再扫描）
        long written = maintenance.rebuild();

        assertThat(written).isZero();
        release.countDown();
        first.join(2000);
        verify(linkRepository, org.mockito.Mockito.times(1)).scanActiveWithId(anyLong(), anyInt());
    }
}
