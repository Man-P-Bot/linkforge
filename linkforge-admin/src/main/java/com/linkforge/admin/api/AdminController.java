package com.linkforge.admin.api;

import com.linkforge.admin.application.DeleteLinkService;
import com.linkforge.admin.config.AdminToken;
import com.linkforge.core.domain.ReservedCodes;
import com.linkforge.core.error.ErrorCode;
import com.linkforge.core.error.LinkForgeException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台接口（docs/API.md §7，DR-005）。
 *
 * <h2>路径前缀 {@code /admin/api/v1}</h2>
 * 与业务接口 {@code /api/v1} 完全分开，不共用前缀——
 * 这样限流 scope 可以区分（{@code admin} vs {@code query}），
 * 鉴权也可以只挂在 {@code /admin/**} 上。
 *
 * <p>⚠️ <b>admin 模块禁止依赖 redirect（DR-018）</b>，
 * 因此本模块<b>没有</b>{@code GlobalExceptionHandler}，
 * 异常由 app 层那个统一处理。错误响应结构与业务接口一致。
 */
@RestController
@RequestMapping("/admin/api/v1")
public class AdminController {

    private final DeleteLinkService deleteLinkService;
    private final AdminToken adminToken;
    private final ReservedCodes reservedCodes;
    private final ZoneId displayZone;

    public AdminController(DeleteLinkService deleteLinkService,
                           AdminToken adminToken,
                           ReservedCodes reservedCodes,
                           ZoneId displayZone) {
        this.deleteLinkService = deleteLinkService;
        this.adminToken = adminToken;
        this.reservedCodes = reservedCodes;
        this.displayZone = displayZone;
    }

    /**
     * 删除短链。
     *
     * <p>⚠️ <b>幂等：重复删除返回 200 + {@code alreadyDeleted: true}</b>（API.md §7.1）。
     * DELETE 的语义是「确保它不存在」，事后状态相同即为成功。
     *
     * @param authHeader {@code Authorization: Bearer <token>}
     */
    @DeleteMapping("/links/{shortCode}")
    public ResponseEntity<Map<String, Object>> deleteLink(
            @PathVariable String shortCode,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader) {

        // 1. 鉴权。⚠️ 在任何业务逻辑之前——未鉴权时**绝不能**碰库
        requireAuthorized(authHeader);

        // 2. 校验短码格式。理由与业务接口相同：非法字符不该消耗一次查询
        if (shortCode == null || shortCode.isBlank()) {
            throw unauthorized();
        }
        if (reservedCodes.contains(shortCode.toLowerCase(java.util.Locale.ROOT))) {
            // 保留字不可能是短码，泄露「哪些词是保留字」没有意义
            throw unauthorized();
        }
        if (!shortCode.matches("^[a-zA-Z0-9]{4,8}$")) {
            throw new LinkForgeException(ErrorCode.INVALID_PARAMETER,
                    "短码格式非法（4~8 位 Base62）: " + shortCode);
        }

        // 3. 删除
        DeleteLinkService.DeleteResult result = deleteLinkService.delete(shortCode);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", Map.of(
                "shortCode", shortCode,
                "deleted", result.deleted(),
                "alreadyDeleted", result.alreadyDeleted()));
        body.put("timestamp", OffsetDateTime.now(displayZone).toString());
        return ResponseEntity.ok(body);
    }

    /**
     * 鉴权（纵深防御的第二道）。
     *
     * <p>⚠️ **第一道是 {@link com.linkforge.admin.config.AdminAuthFilter}**，
     * 它在路由解析<b>之前</b>执行 —— 那才是修复「GET 后台接口返回 500」的关键（DR-064）。
     * 这里保留第二次检查，是为了在「过滤器被误配置 / 被绕过」时仍不失去防护。
     *
     * <p>两处调用<b>同一个</b> {@code matchesAuthorizationHeader}，
     * 判定口径不可能不一致。
     *
     * @param authHeader {@code Authorization: Bearer <token>}
     */
    private void requireAuthorized(String authHeader) {
        // ⚠️ 判定逻辑集中在 AdminToken.matchesAuthorizationHeader：
        //    null / 空串 / 缺 Bearer 前缀 / 乱码 / 长度不符 —— 全部得到确定的 false，
        //    **永不抛异常**（异常会穿透成 500，把调用方问题伪装成服务故障）。
        if (!adminToken.matchesAuthorizationHeader(authHeader)) {
            throw unauthorized();
        }
    }

    private LinkForgeException unauthorized() {
        return new LinkForgeException(ErrorCode.UNAUTHORIZED, "缺少或无效的后台 Token");
    }
}