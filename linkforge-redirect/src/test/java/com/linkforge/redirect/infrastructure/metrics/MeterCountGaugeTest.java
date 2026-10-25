package com.linkforge.redirect.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link MeterCountGauge} 的行为契约（第 5 周）。
 *
 * <h2>⚠️ 本类锁的是「长稳排查能不能用」这条底线</h2>
 * 没有这个 Gauge，DR-057 的怀疑（Metaspace 增长）就只能靠间接推断。
 * 有了它，「meter 数是否线性增长」是一个**可直接读出的数字**。
 */
class MeterCountGaugeTest {

    @Test
    @DisplayName("📊⭐⭐ 暴露 shortlink.meter.count，且数值等于 registry 里的 meter 数")
    void exposesMeterCount() {
        MeterRegistry registry = new SimpleMeterRegistry();
        new MeterCountGauge(registry);

        // 先确认 Gauge 自身已注册（它自己就是一个 meter）
        double before = registry.get("shortlink.meter.count").gauge().value();

        // 再加几个 meter，看数值是否跟着涨
        registry.counter("test.counter.a").increment();
        registry.counter("test.counter.b").increment();
        registry.counter("test.counter.c").increment();

        double after = registry.get("shortlink.meter.count").gauge().value();

        assertThat(after)
                .as("⚠️ 核心断言：Gauge 必须反映**当前**的 meter 数，"
                        + "否则长稳排查时它就是个死指标")
                .isEqualTo(before + 3);
    }

    @Test
    @DisplayName("📊b Gauge 不会因为弱引用变成 NaN")
    void gaugeIsNotNaN() {
        MeterRegistry registry = new SimpleMeterRegistry();
        new MeterCountGauge(registry);

        // ⚠️ Micrometer 的 Gauge.builder 对被观测对象用弱引用。
        //    若 registry 只被弱引用持有，这里会变 NaN。
        //    本类绑定的 registry 是 Spring 容器的单例（强可达），所以安全。
        assertThat(registry.get("shortlink.meter.count").gauge().value())
                .as("⚠️ NaN 说明 registry 被回收了 —— "
                        + "生产上表现为「指标变 NaN」，排查时极易误判")
                .isNotNaN();
    }

    @Test
    @DisplayName("📊c meter 数在**相同 tag 组合**下不增长（幂等）")
    void meterCountIsStableForSameTags() {
        MeterRegistry registry = new SimpleMeterRegistry();
        new MeterCountGauge(registry);

        double before = registry.get("shortlink.meter.count").gauge().value();

        // 同一个名字 + 同一个 tag 组合，重复取 —— Micrometer 复用同一个 meter
        for (int i = 0; i < 100; i++) {
            registry.counter("test.counter.same", "tag", "value").increment();
        }

        assertThat(registry.get("shortlink.meter.count").gauge().value())
                .as("⚠️ 相同 tag 组合必须复用 meter —— 这是「tag 值必须是编译期常量」"
                        + "这条约定的可观测证据")
                .isEqualTo(before + 1);
    }

    @Test
    @DisplayName("📊d 不同 tag 组合会**新增** meter（高基数问题的机制）")
    void differentTagsCreateNewMeters() {
        MeterRegistry registry = new SimpleMeterRegistry();
        new MeterCountGauge(registry);

        double before = registry.get("shortlink.meter.count").gauge().value();

        // ⚠️ 模拟「误开 path tag」的后果：每个短码一个 tag 值
        for (int i = 0; i < 50; i++) {
            registry.counter("http.server.requests", "path", "/code" + i).increment();
        }

        assertThat(registry.get("shortlink.meter.count").gauge().value())
                .as("⚠️ 这就是「动态 tag → meter 数线性增长 → 堆涨 + Prometheus 爆炸」的机制。"
                        + "本 Gauge 的存在意义就是让它**可见**")
                .isEqualTo(before + 50);
    }
}
