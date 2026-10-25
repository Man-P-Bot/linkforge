package com.linkforge.redirect.infrastructure.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Meter 数量监控（第 5 周，长稳排查用）。
 *
 * <h2>⚠️ 为什么需要这个 Gauge</h2>
 * DR-057 怀疑「Micrometer 动态 Tag 导致 Metaspace 增长」，
 * 但要证实或证伪这个怀疑，**必须能观测到 meter 数量的变化**。
 *
 * <p>没有这个指标时，排查只能靠间接推断：
 * 「堆涨了 → 是不是 meter 多了？」—— 无法区分「meter 多了」与
 * 「业务对象多了」。有了它，判读变成一行：
 *
 * <pre>
 *   meter 数线性增长 + 堆涨   → 动态 tag（高基数）问题
 *   meter 数平稳     + 堆涨   → 业务对象滞留（另找原因）
 *   meter 数线性增长 + Metaspace 涨 → 说明动态 tag 的值里含类名/URL（才会触发类加载）
 *   meter 数平稳     + Metaspace 涨 → **类加载问题**（与 Micrometer 无关）
 * </pre>
 *
 * <h2>⚠️ 与 Metaspace 的关系：<b>它是堆问题，不是元空间问题</b></h2>
 * Meter 是**普通堆对象**。动态 tag 导致的是<b>堆</b>增长，
 * <b>不是</b> Metaspace 增长。
 *
 * <p>Metaspace 存的是<b>类元数据</b>，只有<b>加载新类</b>才会涨
 * （类一旦加载就永不卸载，除非类加载器被回收）。
 * 所以：
 * <ul>
 *   <li>要查 Metaspace → 看 {@code jvm_classes_loaded_classes}（已加载类数），</li>
 *   <li>要查堆       → 看本 Gauge 与 {@code jvm_memory_used_bytes{area="heap"}}。</li>
 * </ul>
 *
 * <h2>⚠️ 为什么用 {@code registry.getMeters().size()} 而不做缓存</h2>
 * {@code getMeters()} 返回的是<b>不可变快照</b>，每次调用会新建一个 List。
 * 把它做成 Gauge 意味着<b>每次 Prometheus 抓取都会遍历一遍所有 meter</b> ——
 * 这正是「不要开 path tag」那条建议的同一个理由（遍历成本随 meter 数线性增长）。
 *
 * <p>但 meter 数量在本项目是<b>固定的几十个</b>（所有 tag 值都是编译期常量），
 * 遍历成本可忽略。而<b>可观测性</b>的价值远大于这点开销 ——
 * 一旦将来有人误加了动态 tag，这个 Gauge 会**立刻**报警，
 * 而不是等到 Prometheus 因为 series 爆炸而 OOM 才发现。
 *
 * <p>⚠️ <b>不做缓存</b>是刻意的：缓存会掩盖「meter 数在涨」这个信号本身。
 */
@Component
public class MeterCountGauge {

    /**
     * Meter 总数 Gauge。
     *
     * <p>⚠️ <b>用弱引用绑定 registry</b>：Micrometer 的
     * {@code Gauge.builder(name, obj, valueFunction)} 对 obj 用弱引用。
     * 这里 obj 是 Spring 容器持有的 {@link MeterRegistry}（单例，final 语义），
     * 天然强可达，不会变 NaN。
     */
    public MeterCountGauge(MeterRegistry registry) {
        Gauge.builder("shortlink.meter.count", registry,
                        r -> r.getMeters().size())
                .description("MeterRegistry 中的 meter 总数。"
                        + "⚠️ 它线性增长 = 出现了高基数 tag（如 http.server.requests 的 path tag），"
                        + "会导致堆增长与 Prometheus series 爆炸")
                // ⚠️ **不设 baseUnit**：Micrometer 会把 baseUnit 追加到 Prometheus 的指标名后面，
                //   `shortlink.meter.count` + baseUnit("meters") -> `shortlink_meter_count_meters`。
                //   那个 `_meters` 后缀既冗余又容易让人查错（写成 shortlink_meter_count 查不到）。
                //   单位信息在 description 里已经说清楚了。
                .register(registry);
    }
}
