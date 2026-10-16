package com.linkforge.redirect.application;

import com.linkforge.core.domain.Link;
import com.linkforge.core.domain.LinkStatus;
import com.linkforge.core.error.ErrorCode;
import com.linkforge.core.error.LinkForgeException;
import com.linkforge.core.infrastructure.cache.CacheEntry;
import com.linkforge.core.infrastructure.cache.CachedLink;
import com.linkforge.redirect.infrastructure.cache.TwoLevelRedirectCache;
import com.linkforge.core.repository.LinkRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 跳转用例（docs/API.md §4，PRD US-3 / US-4）。
 *
 * <h2>第 2 周的形态：Caffeine → Redis → MySQL，两级缓存</h2>
 * 第 1 周每次直查 MySQL，那是<b>刻意保留的无缓存基线</b>（PRD A-3）：
 * 只有先量出无缓存的 P99，才谈得上缓存带来多少收益。
 * 第 2 周在同口径下接入缓存，两次数据并列记录在 {@code docs/PERFORMANCE.md}。
 *
 * <h2>失效判定优先级（写死，不可调换）</h2>
 * <pre>
 *   两级缓存都没命中→ 回源查库
 *   查不到            → 404 LINKFORGE-404-01
 *   status = DELETED  → 404 LINKFORGE-404-01   ← 先判，不看 expire_at
 *   expire_at &lt;= now → 410 LINKFORGE-410-01
 *   否则              → 302
 * </pre>
 * 判定逻辑放在 core 的 {@link com.linkforge.core.domain.LinkState#resolve}
 * 而不是散在本类里：查询接口（第 4 周 admin）必须给出<b>完全一致</b>的状态码
 * （API.md §6.3），规则只有一份才可能一致。
 *
 * <p>⚠️ <b>缓存不能绕过状态判定</b>：缓存里存的是 {@link CachedLink}（含 status 与 expireAt），
 * 命中后<b>仍然要走一遍同样的判定</b>。如果命中缓存就直接返回 302，
 * 那么「先跳转、后删除」的链接在最长 5 分钟内仍会跳到目标——
 * 而 DELETE 语义要求删除后立即 404（API.md §7.1）。判定放在缓存层之前，
 * 才与第 1 周的行为完全一致。
 *
 * <p>⚠️ <b>时间比较全程用 {@code Instant}</b>：{@code expire_at} 是 UTC 的
 * {@code LocalDateTime}，必须显式按 UTC 还原成瞬间再与 {@link Clock#instant()} 比。
 *
 * <p>⚠️ <b>缓存的 status 与 expireAt 从哪来</b>：回源时从 {@link Link} 转换。
 * 注意存的是<b>数据库读出的原值</b>，不是在 Java 侧重新计算的时间——
 * 重新计算会把「已过期的链接」缓存成一个看似新鲜的数据。
 */
@Service
public class RedirectService {

    private final LinkRepository linkRepository;
    private final LinkValidator linkValidator;
    private final TwoLevelRedirectCache cache;
    private final Clock clock;

    public RedirectService(LinkRepository linkRepository,
                           LinkValidator linkValidator,
                           TwoLevelRedirectCache cache,
                           Clock clock) {
        this.linkRepository = linkRepository;
        this.linkValidator = linkValidator;
        this.cache = cache;
        this.clock = clock;
    }

    /**
     * 解析短码对应的目标地址。
     *
     * <p><b>不记 INFO 日志</b>（docs/ARCHITECTURE.md §14.2、API.md §4.5）：
     * 跳转是全站 QPS 最高的路径，每次请求写一行 INFO 会把磁盘打满，
     * 且真正需要留痕的失败路径已经由 {@code GlobalExceptionHandler} 记 WARN/ERROR。
     *
     * @param rawCode 路径中的原始短码（未解码判断前不做任何清洗）
     * @return 目标地址（原样返回，<b>不做 URL 归一化</b>，DATABASE §4.1）
     * @throws LinkForgeException 格式非法（400）、不存在或已删除（404）、已过期（410）
     */
    public String resolveTargetUrl(String rawCode) {
        // 1. 格式校验先行：非法字符不应该消耗一次缓存查询，更不该消耗数据库查询
        linkValidator.validateForRedirect(rawCode);

        // 2. 缓存查询 + 布隆前置，**一次 Redis 往返完成**（DR-052）。
        //    顺序：L1（0 次 Redis）→ 1 次 Lua（布隆 k 次 GETBIT + L2 GET 合并）→ 单飞回源
        //
        //    ⚠️ 不要再单独调 bloomFilter.mightContain：那是「EXISTS + 7 次 GETBIT」
        //    =8 次独立往返，8 万 QPS 下等于每秒 64 万次 Redis 往返。
        //    合并脚本在 RedisBloomFilter.checkAndGet 里，DR-052 有完整背景。
        TwoLevelRedirectCache.BloomAwareResult result =
                cache.getOrLoadWithBloom(rawCode, this::loadFromSource);

        if (result.definitelyAbsent()) {
            // 布隆判「一定不存在」：直接 404，不查缓存也不查库（防穿透）
            throw new LinkForgeException(ErrorCode.LINK_NOT_FOUND,
                    "短码不存在: " + rawCode);
        }
        if (result.hit() == null) {
            // 空值缓存命中 或 回源确认不存在：直接 404，不重复回源
            throw new LinkForgeException(ErrorCode.LINK_NOT_FOUND,
                    "短码不存在: " + rawCode);
        }
        CachedLink link = result.hit().link();

        // 4. 失效判定。与第 1 周完全相同的顺序：DELETED 先于 EXPIRED
        LinkStatus status = LinkStatus.valueOf(link.status());
        if (status == LinkStatus.DELETED) {
            throw new LinkForgeException(ErrorCode.LINK_NOT_FOUND, "短码已删除: " + rawCode);
        }
        LocalDateTime expireAt = link.expireAt();
        if (expireAt != null && !expireAt.toInstant(ZoneOffset.UTC).isAfter(clock.instant())) {
            throw new LinkForgeException(ErrorCode.LINK_EXPIRED, "短码已过期: " + rawCode);
        }

        return link.targetUrl();
    }

    /**
     * 回源查库（单飞的 loader）。
     *
     * <p>⚠️ <b>三条约束</b>：
     * <ol>
     *   <li><b>只能是查库</b>——100 个并发请求会同步等待它完成，耗时操作会卡住本地缓存；</li>
     *   <li><b>异常直接向上抛</b>，不转成 {@code null}——返回 null 的语义是
     *       「确认不存在」，而数据库异常绝不能被当成「不存在」缓存（DR-048）；</li>
     *   <li><b>无论链接是否已失效都要回填</b>——404/410 的判定依赖 status 与 expireAt，
     *       缓存下来才能省掉下次回源；命中时第 4 步会重新走一遍判定。</li>
     * </ol>
     *
     * @return 查到的链接；{@code null} 表示「确认不存在」
     */
    private CacheEntry.Hit loadFromSource(String rawCode) {
        // 仓储层不做状态过滤——已删除与已过期都要返回，由调用方决定 404 还是 410
        Link found = linkRepository.findByShortCode(rawCode).orElse(null);
        if (found == null) {
            return null;
        }
        CachedLink cached = new CachedLink(
                found.targetUrl(), found.status().name(), found.expireAt());
        // 回填 Redis（L2）。⚠️ 本地层由 Caffeine 的 loader 自动写入
        cache.putToRedisOnly(rawCode, cached);
        return new CacheEntry.Hit(cached);
    }
}