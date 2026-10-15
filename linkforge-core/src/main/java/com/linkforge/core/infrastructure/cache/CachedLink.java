package com.linkforge.core.infrastructure.cache;

import java.time.LocalDateTime;

/**
 * 跳转缓存条目（缓存层的最小数据单元）。
 *
 * <h2>⚠️ 为什么不直接缓存 {@link com.linkforge.core.domain.Link}</h2>
 * 三个理由：
 * <ol>
 *   <li><b>字段冗余</b>：{@code id} / {@code createdAt} / {@code updatedAt} / {@code custom}
 *       对跳转判定全部无用。{@code targetUrl} 最长 2048 字节，
 *       把它和一堆用不到的字段一起序列化进 Redis，是纯浪费内存与网络。</li>
 *   <li><b>缓存值不该跟着领域对象演进</b>：{@code Link} 加字段时，
 *       缓存格式会静默变化，滚动发布期间新旧实例写出两种格式。</li>
 *   <li><b>可序列化性</b>：{@code Link} 是 {@code final class} 无默认构造，
 *       Jackson 反序列化需要额外配置；record 天然适配。</li>
 * </ol>
 *
 * <p>缓存需要的是「跳转判定所必需的最小事实」，恰好是这个 record 的全部内容。
 *
 * @param targetUrl 目标地址，原样保存（不做 URL 归一化，DATABASE.md §4.1）
 * @param status    状态；缓存里必须带上，否则无法区分 404（已删除）与 302
 * @param expireAt  过期时间，{@code null} 表示永久有效。必须带，
 *                  否则每次命中缓存都得回库判断是否过期，缓存就白做了
 */
public record CachedLink(String targetUrl, String status, LocalDateTime expireAt) {
}