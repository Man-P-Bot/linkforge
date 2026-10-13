package com.linkforge.redirect.api.dto;

import java.time.OffsetDateTime;

/**
 * 创建短链响应（docs/API.md §5.3）。
 *
 * <h2>⚠️ 刻意不包含的字段</h2>
 * <ul>
 *   <li><b>数据库自增 {@code id}</b>：内部标识，泄露无益且会暴露数据量（API.md §5.3 明确要求）；</li>
 *   <li><b>发号器序号</b>：会暴露短码可枚举性（DR-011 已接受可枚举，但没必要主动公示）；</li>
 *   <li><b>{@code status}</b>：新建必为 ACTIVE，无信息量。</li>
 * </ul>
 *
 * <p>{@code createdAt} 也在这里返回：它由数据库生成（DATABASE §4.1），
 * 插入后需要回查才能拿到，因此该字段的存在意味着 Service 要在插入后再读一次或由 SQL 回填。
 * <p>本字段用 {@link OffsetDateTime} 输出（DR-027），由 Service 层经 {@code TimeConverter} 转换。
 */
public record CreateLinkResponse(
        String shortCode,
        String shortUrl,
        String targetUrl,
        boolean custom,
        OffsetDateTime expireAt,
        OffsetDateTime createdAt
) {
}
