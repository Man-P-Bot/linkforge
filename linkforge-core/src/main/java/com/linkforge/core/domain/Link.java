package com.linkforge.core.domain;

import java.time.LocalDateTime;

/**
 * 短链领域对象，对应 {@code short_link} 表（docs/DATABASE.md §4.1）。
 *
 * <p>不可变对象：短码只读不可改（PRD §7.1），因此没有 setter。
 * 状态变更（删除）与统计落库由仓储层的显式方法表达，而不是靠 setter 改字段——
 * 这样「谁能改什么」在编译期就一目了然。
 *
 * <p>时间字段用 {@link LocalDateTime} 而不是 {@code Instant}：数据库列是 {@code TIMESTAMP}，
 * 且连接串强制数据库会话时区为 UTC（{@code connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true}，
 * 见 docs/DECISIONS.md DR-032），因此读回来的 {@code LocalDateTime} 本身就是 UTC，可直接使用。
 * 若会话时区是本地（如 {@code +08:00}），数据库自己生成的 {@code created_at} 就会是本地墙上时钟，
 * 被当 UTC 解释后会多出 8 小时——不要存带偏移的对象，否则会出现「偏移被应用一次、又被驱动解释一次」。
 * 注意 {@code created_at} 与 {@code updated_at} **由数据库生成**，插入时不需要赋值。
 */
public final class Link {

    /** 内部主键。插入前为 {@code null}，由数据库自增后回填。 */
    private final Long id;

    /** 短码：固定 7 位 Base62，只读不可改。自动生成与自定义共用同一唯一空间。 */
    private final String shortCode;

    /** 原始长链，最长 2048 字符。创建接口拒绝超长，不做截断。 */
    private final String targetUrl;

    /** 状态。删除只改这个字段，不做物理删除。 */
    private final LinkStatus status;

    /** 是否自定义短码。对外体现为 API 响应里的 {@code custom} 字段。 */
    private final boolean custom;

    /** 创建时间，由数据库生成。 */
    private final LocalDateTime createdAt;

    /** 最后变更时间，由数据库在更新时自动推进。用于回答「何时被删」。 */
    private final LocalDateTime updatedAt;

    /** 过期时间。{@code null} 表示永久有效。与 {@code status} 表达不同的事，不可合并。 */
    private final LocalDateTime expireAt;

    public Link(Long id,
                String shortCode,
                String targetUrl,
                LinkStatus status,
                boolean custom,
                LocalDateTime createdAt,
                LocalDateTime updatedAt,
                LocalDateTime expireAt) {
        this.id = id;
        this.shortCode = shortCode;
        this.targetUrl = targetUrl;
        this.status = status;
        this.custom = custom;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.expireAt = expireAt;
    }

    public Long id() {
        return id;
    }

    public String shortCode() {
        return shortCode;
    }

    public String targetUrl() {
        return targetUrl;
    }

    public LinkStatus status() {
        return status;
    }

    public boolean custom() {
        return custom;
    }

    public LocalDateTime createdAt() {
        return createdAt;
    }

    public LocalDateTime updatedAt() {
        return updatedAt;
    }

    public LocalDateTime expireAt() {
        return expireAt;
    }
}
