package com.linkforge.redirect.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 本地缓存（Caffeine）配置。
 *
 * <h2>expireAfterWrite = 5 分钟：由业务需求决定，不由 Redis TTL 倒推</h2>
 * 本地缓存<b>无法精确失效</b>——多实例部署时每个实例各有一份，
 * 一个实例删了 Redis key，其它实例的本地缓存毫不知情。
 * 因此**最大不一致窗口 = Caffeine 的 TTL**，这个数字由业务能接受多久的
 * 「删除短链后仍可跳转」决定，而不是由 Redis TTL 是 1 小时倒推成什么值。
 *
 * <p>业务已确认可接受 <b>5 分钟</b>的窗口，因此定为 5 分钟。
 *
 * <p>⚠️ <b>记录一个刻意的取舍</b>：5 分钟恰好也接近 Redis TTL 的 1/12，
 * 但那只是巧合，<b>不要用「≤ Redis TTL/12」当推导依据</b>——
 * 若哪天 Redis TTL 改成 30 分钟，不应该跟着把本地缓存改成 2.5 分钟。
 * 业务窗口不变，本地 TTL 就不该变。
 *
 * <h2>maximumSize = 10000：数据量依赖，不是通用常数</h2>
 * 10000 是<b>第 1 周实测数据量（1 万条短码）</b>决定的：
 * 足以覆盖压测语料的全部热数据，又只占几 MB。
 * <b>生产环境应按热点数据比例重新评估</b>，通常取「活跃短码数 × 0.1~0.3」——
 * 短链访问是长尾分布，少数码贡献绝大多数流量，全量缓存是浪费。
 * 换数据量时这个值必须一起改，否则命中率会静默偏离预期。
 *
 * <h2>⚠️ 本地缓存只存 Hit，绝不存 NegativeHit</h2>
 * 空值 TTL 只有 30 秒，而本地 TTL 是 5 分钟。若把「不存在」也缓存到本地，
 * 一个<em>刚被创建</em>的短码会持续 404 长达 5 分钟——
 * 缓存了一个比它本身存在时间还长的错误结论。
 * 约束由 {@link TwoLevelRedirectCache} 在代码里保证，并有单测兜底。
 *
 * <h2>为什么不用 {@code CaffeineCacheMetrics.monitor}</h2>
 * 它生成的指标名是 {@code caffeine.*} 系列（按缓存名派生），
 * 与本项目已定的三个指标名对不上。三个指标必须<b>同名同源</b>，
 * 否则压测时要在两套命名里换算，第 5 周 Prometheus 也要加转换规则——
 * 指标口径从第一天就该统一。绑定见 {@link TwoLevelRedirectCache}。
 */
@Configuration
public class CaffeineConfig {

    /** 本地缓存 TTL：业务可接受的不一致窗口（见类注释）。 */
    public static final Duration LOCAL_TTL = Duration.ofMinutes(5);

    /**
     * 本地缓存条目上限。
     *
     * <p>⚠️ 数据量依赖：当前按第 1 周的 1 万条压测语料设定。生产环境按
     * 「活跃短码数 × 0.1~0.3」重新评估，不要照搬。
     */
    public static final long LOCAL_MAX_SIZE = 10_000L;

    @Bean
    public Cache<String, com.linkforge.core.infrastructure.cache.CacheEntry.Hit> localLinkCache() {
        // ⚠️ recordStats 必须开：不 recordStats 就没有命中率数据，
        // 而命中率是判断缓存是否有效的唯一客观依据。
        // ⚠️ 只缓存 CacheEntry.Hit：NegativeHit 绝不能进本地缓存（见类注释）。
        return Caffeine.newBuilder()
                .maximumSize(LOCAL_MAX_SIZE)
                .expireAfterWrite(LOCAL_TTL)
                .recordStats()
                .build();
    }
}