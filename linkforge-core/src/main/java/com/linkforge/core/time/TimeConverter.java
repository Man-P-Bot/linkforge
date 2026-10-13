package com.linkforge.core.time;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 时间的时区转换（docs/DECISIONS.md DR-027，**全项目唯一的转换入口**）。
 *
 * <h2>转换链（不得在各处自行实现）</h2>
 * <pre>
 *   OffsetDateTime（请求，保留调用方偏移）
 *        │ toUtc
 *        ▼
 *   LocalDateTime（UTC）  ──►  MySQL TIMESTAMP 列
 *        │ toOffset
 *        ▼
 *   OffsetDateTime（响应，按服务端时区渲染）
 * </pre>
 *
 * <h2>⚠️ 为什么持久层必须存 UTC 的 LocalDateTime</h2>
 * 连接串已固定 {@code serverTimezone=Asia/Shanghai}（DR-023），驱动会按该时区解释 {@code TIMESTAMP}。
 * 若应用层再存一个带偏移的对象，就会出现**偏移被应用一次、又被驱动解释一次**的双重换算，
 * 时间整体偏移数小时。存 UTC 的 {@code LocalDateTime} 是唯一不产生歧义的组合。
 *
 * <h2>⚠️ 为什么入/出用 OffsetDateTime 而不是 Instant</h2>
 * 请求带的是偏移（{@code +08:00}），{@code OffsetDateTime} 能保留调用方原始意图，
 * 也便于按服务端时区回渲染；{@code Instant} 会丢失偏移信息。
 *
 * <h2>null 语义</h2>
 * {@code null} 入 → {@code null} 出，**不抛异常**。理由：{@code expire_at} 为 NULL 表示
 * 「永久有效」（DATABASE §4.1），是合法业务状态而非错误。调用方因此不必在每处写 null 判断。
 *
 * <p>本类是**无状态纯函数工具**，构造器私有；需要注入的场景（如 Service 依赖它）请使用
 * {@link #INSTANCE}，这样单元测试可以替换实现而不影响调用方。
 */
public final class TimeConverter {

    /** 无状态单例，供构造器注入使用。 */
    public static final TimeConverter INSTANCE = new TimeConverter();

    private TimeConverter() {
    }

    /**
     * 请求时间 → 持久层时间：{@code OffsetDateTime} → **UTC 的 {@code LocalDateTime}**。
     *
     * @return {@code null} 入参返回 {@code null}
     */
    public LocalDateTime toUtc(OffsetDateTime source) {
        if (source == null) {
            return null;
        }
        return source.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /**
     * 持久层时间 → 响应时间：**UTC 的 {@code LocalDateTime}** → 按服务端时区渲染的 {@code OffsetDateTime}。
     *
     * @param displayZone 响应渲染时区（配置项，默认 {@code +08:00}）
     * @return {@code null} 入参返回 {@code null}
     */
    public OffsetDateTime toOffset(LocalDateTime utcLocal, ZoneId displayZone) {
        if (utcLocal == null) {
            return null;
        }
        // 先按 UTC 还原成带偏移的时刻，再换算到展示时区：同一瞬间，偏移不同。
        return utcLocal.atOffset(ZoneOffset.UTC).withOffsetSameInstant(displayZone.getRules()
                .getOffset(utcLocal.atOffset(ZoneOffset.UTC).toInstant()));
    }

    /**
     * 判定用的「现在」（UTC 的 {@code LocalDateTime}）。
     *
     * <p>⚠️ **D5 的过期判定不要用本方法之外的本地时间**：拿「UTC 的 {@code LocalDateTime}」直接与
     * {@code LocalDateTime.now()}（本地时间）比较，在非 UTC 部署环境下会得出错误结论
     * （把未过期的判成已过期）。要么用本方法，要么统一转 {@code Instant} 比较。
     *
     * <p>本方法刻意**不做成可注入的 Clock**：需要「可控时间」的场景（如过期判定的单测）
     * 应由调用方注入 {@code java.time.Clock}，而不是在这里藏一个静态时钟。
     */
    public LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
