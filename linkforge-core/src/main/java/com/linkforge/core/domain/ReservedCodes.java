package com.linkforge.core.domain;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 保留字黑名单（docs/API.md §5.2、docs/DECISIONS.md DR-025）。
 *
 * <h2>它防的是什么</h2>
 * 短码走根路径 {@code GET /{code}}，因此短码取值空间与系统路径**共享命名空间**。
 * 若不拦，一个叫 {@code api} 的自定义短码会**吃掉 {@code /api/v1/links}**。
 * 黑名单是这条防线的第一道（第二道是启动时的路径前缀断言，见 ARCHITECTURE §6.4）。
 *
 * <h2>⚠️ 为什么比较大小写不敏感（DR-025）</h2>
 * 短码**值**是大小写敏感的（{@code Abc123} ≠ {@code abc123}），但保留字是**路径级防线**：
 * HTTP 路径的大小写归一化行为在不同实现/容器/代理下并不一致。
 * 若只拦小写 {@code api}，则 {@code Api} 在某些部署下**仍会撞上 {@code /api}**——
 * 防线的有效性会依赖「部署环境恰好不做归一化」，这是不可接受的假设。
 * 因此采用**更保守**的比较：加载时统一转小写，校验时也转小写。
 *
 * <p>本类是**不可变值对象**，配置在启动时解析一次。
 */
public final class ReservedCodes {

    /** 默认黑名单，来自 `docs/API.md` §5.2（配置项 {@code RESERVED_CODES} 可覆盖）。 */
    public static final String DEFAULT_CODES = "api,admin,actuator,health,favicon.ico,static,error";

    private final Set<String> normalized;

    private ReservedCodes(Set<String> normalized) {
        this.normalized = Collections.unmodifiableSet(normalized);
    }

    /**
     * 解析逗号分隔的配置。
     *
     * @param raw 形如 {@code api,admin,actuator}，允许空白与空项；{@code null}/空白则用
     *            {@link #DEFAULT_CODES}
     */
    public static ReservedCodes parse(String raw) {
        String source = (raw == null || raw.isBlank()) ? DEFAULT_CODES : raw;
        Set<String> codes = new LinkedHashSet<>();
        for (String part : source.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                // 统一小写存储：比较时也小写，从而大小写不敏感（DR-025）
                codes.add(trimmed.toLowerCase(Locale.ROOT));
            }
        }
        if (codes.isEmpty()) {
            throw new IllegalArgumentException("保留字黑名单不能为空，配置值: \"" + raw + "\"");
        }
        return new ReservedCodes(codes);
    }

    /**
     * 判断候选短码是否为保留字（**大小写不敏感**）。
     *
     * @param candidate 候选短码；{@code null} 视为不是保留字（是否非法由格式校验负责）
     */
    public boolean contains(String candidate) {
        if (candidate == null) {
            return false;
        }
        return normalized.contains(candidate.toLowerCase(Locale.ROOT));
    }

    /** 归一化后的黑名单（小写），供启动断言与日志使用。 */
    public Set<String> values() {
        return normalized;
    }

    @Override
    public String toString() {
        return String.join(",", normalized);
    }
}
