package com.linkforge.redirect.api;

import com.linkforge.core.error.ErrorCode;
import com.linkforge.core.error.LinkForgeException;
import com.linkforge.redirect.application.RedirectService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 跳转接口 {@code GET /{code}}（docs/API.md §4，PRD US-3 / US-4）。
 *
 * <p><b>零业务逻辑</b>：只做「取路径变量 → 调用例 → 拼响应头」。
 * 格式校验、查库、失效判定都在 {@link RedirectService} 与 {@code LinkValidator}。
 *
 * <h2>为什么用 302 而不是 301</h2>
 * 301 会被浏览器<b>永久缓存</b>，之后即使链接被删除或改版，用户浏览器仍会直接跳转，
 * 既让「删除」这个能力对外失效，也让 PV/UV 统计严重失真（PRD D-3）。
 * 302 每次都回源，是「链接可被随时撤销」这一产品承诺的技术前提。
 *
 * <h2>为什么显式写 {@code Cache-Control: no-store}</h2>
 * 不写不违法，但<b>不写就等于把决定权交给中间层</b>：CDN、反向代理、浏览器扩展都可能缓存 302。
 * PRD D-3 的初衷是「避免缓存导致统计失真」，因此这个头必须由本接口显式声明，
 * 而不是依赖「默认不缓存」。
 *
 * <h2>为什么成功响应无 body</h2>
 * 跳转只需把浏览器送走，返回内容没有意义，还会占用带宽（API.md §3.1）。
 * <b>失败响应才有 body</b>——契约一致性优先，且失败请求占比低，不构成性能负担。
 *
 * <h2>为什么尾斜杠要单独映射</h2>
 * Spring 6 默认<b>不</b>匹配尾斜杠（{@code setMatchOptionalTrailingSeparator(false)}），
 * 因此 {@code /abc1234/} 不会落到 {@link #redirect(String)} 上——它会变成
 * 「找不到处理器」的 404，body 不是统一错误体，等于契约违规。
 * 这里显式注册 {@code /{code}/} 并抛 400，保证契约里写明的行为真的发生（API.md §4.1）。
 */
@RestController
public class RedirectController {

    private final RedirectService redirectService;

    public RedirectController(RedirectService redirectService) {
        this.redirectService = redirectService;
    }

    /**
     * 正常跳转：302 + {@code Location} + {@code Cache-Control: no-store}。
     *
     * <p>⚠️ {@code Location} 是<b>目标地址</b>（{@code targetUrl}），
     * <b>不是</b> {@code shortUrl}——后者是「本服务自己的短链地址」，
     * 填进去会让浏览器跳回自己，形成一次多余往返甚至循环。
     */
    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(@PathVariable("code") String code) {
        String targetUrl = redirectService.resolveTargetUrl(code);
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, targetUrl)
                .cacheControl(CacheControl.noStore())
                .build();
    }

    /**
     * 尾斜杠一律 400，<b>不做重定向纠正</b>（API.md §4.1）。
     *
     * <p>自动纠正会让一个短码对应多个可访问 URL，破坏一一对应关系，
     * 也会让缓存与统计出现双份记录。明确拒绝比静默纠正更容易排查。
     */
    @GetMapping("/{code}/")
    public ResponseEntity<Void> rejectTrailingSlash(@PathVariable("code") String code) {
        throw new LinkForgeException(ErrorCode.INVALID_PARAMETER,
                "短码路径不允许尾斜杠: \"/" + code + "/\"");
    }
}
