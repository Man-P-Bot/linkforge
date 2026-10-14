package com.linkforge.redirect.application;

import com.linkforge.core.domain.Link;
import com.linkforge.core.domain.LinkState;
import com.linkforge.core.error.ErrorCode;
import com.linkforge.core.error.LinkForgeException;
import com.linkforge.core.repository.LinkRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;

/**
 * 跳转用例（docs/API.md §4，PRD US-3 / US-4）。
 *
 * <h2>D5 的形态：查库 → 判定 → 跳转，不带缓存</h2>
 * 第 1 周的跳转<b>每次都直查 MySQL</b>。这不是「忘了做缓存」，而是<b>刻意保留的性能基线</b>：
 * PRD A-3 要求用「无缓存版本」作为分母，先量出无缓存的 P99，才谈得上缓存带来了多少收益。
 * 若一上来就带缓存，压测测到的是「缓存 + DB」的合成值，基线就永远补不回来了。
 *
 * <p>⚠️ <b>缓存接口刻意不在 D5 定义</b>（第 2 周与 {@code RedisRedirectCache} 一起落地）：
 * 为猜不准的需求提前建抽象往往要返工——需求没稳定就抽接口，接口签名会跟着需求反复改，
 * 改的成本比「等需求确定后再抽一次」更高。本类直接依赖 {@link LinkRepository}，
 * 第 2 周引入缓存时改这一处依赖即可，属局部改动而非架构改动（KISS）。
 *
 * <h2>失效判定优先级（写死，不可调换）</h2>
 * <pre>
 *   查不到            → 404 LINKFORGE-404-01
 *   status = DELETED  → 404 LINKFORGE-404-01   ← 先判，不看 expire_at
 *   expire_at &lt;= now → 410 LINKFORGE-410-01
 *   否则              → 302
 * </pre>
 * 判定逻辑放在 core 的 {@link LinkState#resolve(Link, java.time.Instant)} 而不是散在本类里：
 * 查询接口（第 4 周 admin）必须给出<b>完全一致</b>的状态码（API.md §6.3），
 * 规则只有一份才可能一致。
 *
 * <p>⚠️ <b>时间比较全程用 {@code Instant}</b>：{@code expire_at} 读回来是 UTC 的
 * {@code LocalDateTime}，必须显式按 UTC 还原成瞬间再与 {@link Clock#instant()} 比。
 * 直接与 {@code LocalDateTime.now()}（本地时间）比，在非 UTC 部署环境下会把未过期的判成已过期。
 */
@Service
public class RedirectService {

    private final LinkRepository linkRepository;
    private final LinkValidator linkValidator;
    private final Clock clock;

    public RedirectService(LinkRepository linkRepository, LinkValidator linkValidator, Clock clock) {
        this.linkRepository = linkRepository;
        this.linkValidator = linkValidator;
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
        // 1. 格式校验先行：非法字符不应该消耗一次数据库查询
        linkValidator.validateForRedirect(rawCode);

        // 2. 查库。仓储层不做状态过滤——已删除与已过期都要返回，由本层决定 404 还是 410
        Link link = linkRepository.findByShortCode(rawCode)
                .orElseThrow(() -> new LinkForgeException(ErrorCode.LINK_NOT_FOUND,
                        "短码不存在: " + rawCode));

        // 3. 失效判定。顺序即优先级：DELETED 先于 EXPIRED
        LinkState state = LinkState.resolve(link, clock.instant());
        switch (state) {
            case DELETED -> throw new LinkForgeException(ErrorCode.LINK_NOT_FOUND,
                    "短码已删除: " + rawCode);
            case EXPIRED -> throw new LinkForgeException(ErrorCode.LINK_EXPIRED,
                    "短码已过期: " + rawCode);
            case ACTIVE -> {
                // 落到这里说明可跳转
            }
        }

        return link.targetUrl();
    }
}
