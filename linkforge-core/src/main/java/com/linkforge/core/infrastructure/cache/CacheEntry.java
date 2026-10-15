package com.linkforge.core.infrastructure.cache;

/**
 * 缓存查询结果（docs/ARCHITECTURE.md §10）。
 *
 * <h2>为什么是三态而不是 {@code Optional<CachedLink>}</h2>
 * 防穿透需要缓存「该短码不存在」这个事实。此时如果用 {@code Optional} 表达，
 * 「已缓存不存在」与「本层未命中」在返回值上完全一样，调用方只能靠再查一次库来区分——
 * 空值缓存就白做了。
 *
 * <p>因此外层用 {@code Optional} 表示「本层有没有这个key」，内层用两个实现表示「有的话是什么」：
 *
 * <pre>
 *   Optional.empty()            → 本层未命中，需要回源
 *   Optional.of(Hit(link))      → 命中有效短链
 *   Optional.of(NegativeHit)    → 已缓存「不存在」，直接 404，不要回源
 * </pre>
 *
 * <p>⚠️ <b>最易写错的断言</b>：单测里「未命中必须返回 {@code Optional.empty()}，
 * 而不是 {@code NegativeHit}」——这两者语义相反，混淆会让防穿透逻辑反向生效。
 */
public sealed interface CacheEntry {

    /** 命中有效短链。 */
    record Hit(CachedLink link) implements CacheEntry {
    }

    /**
     * 已缓存「该短码不存在」（空值缓存，防穿透）。
     *
     * <p>不携带短码本身——调用方已经知道查的是谁。
     */
    record NegativeHit() implements CacheEntry {
    }
}