package com.linkforge.redirect.application;

import com.linkforge.core.domain.Link;
import com.linkforge.core.domain.LinkStatus;
import com.linkforge.core.error.ErrorCode;
import com.linkforge.core.error.LinkForgeException;
import com.linkforge.core.infrastructure.cache.CacheEntry;
import com.linkforge.core.infrastructure.cache.CachedLink;
import com.linkforge.core.infrastructure.cache.RedirectCacheService;
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
    private final RedirectCacheService cache;
    private final Clock clock;

    public RedirectService(LinkRepository linkRepository,
                           LinkValidator linkValidator,
                           RedirectCacheService cache,
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

        // 2. 查缓存（两级）。返回的 CachedLink 携带 status 与 expireAt，判定能力与查库一致
        Optional<CacheEntry> cached = cache.get(rawCode);
        CachedLink link;
        if (cached.isPresent()) {
            CacheEntry entry = cached.get();
            if (entry instanceof CacheEntry.NegativeHit) {
                // 空值缓存命中：已确认不存在，直接 404，不回源（防穿透生效）
                throw new LinkForgeException(ErrorCode.LINK_NOT_FOUND,
                        "短码不存在: " + rawCode);
            }
            link = ((CacheEntry.Hit) entry).link();
        } else {
            // 3. 回源。仓储层不做状态过滤——已删除与已过期都要返回，由本层决定 404 还是 410
            Link found = linkRepository.findByShortCode(rawCode)
                    .orElseThrow(() -> {
                        // ⚠️ 只有「确认不存在」才写空值缓存。
                        // 若这里抛的是数据库异常（不是 orElseThrow 分支），异常会向上传播，
                        // 不会走到这里——所以绝不会把「查询失败」缓存成「不存在」。
                        cache.putEmpty(rawCode);
                        return new LinkForgeException(ErrorCode.LINK_NOT_FOUND,
                                "短码不存在: " + rawCode);
                    });
            link = new CachedLink(found.targetUrl(), found.status().name(), found.expireAt());
            // 4. 回填。⚠️ 无论该链接是否已失效都回填：404/410 的判定依赖 status 与 expireAt，
            //    缓存下来才能省掉下次回源；下次命中时本方法会重新走一遍判定。
            cache.put(rawCode, link);
        }

        // 5. 失效判定。与第 1 周完全相同的顺序：DELETED 先于 EXPIRED
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
}