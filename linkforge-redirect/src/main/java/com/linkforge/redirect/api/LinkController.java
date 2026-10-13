package com.linkforge.redirect.api;

import com.linkforge.redirect.api.dto.CreateLinkRequest;
import com.linkforge.redirect.api.dto.CreateLinkResponse;
import com.linkforge.redirect.application.LinkCreationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 创建短链接口（docs/API.md §5）。
 *
 * <p><b>零业务逻辑</b>：只做协议转换 —— 参数绑定、{@code @Valid} 触发字段级校验、
 * 调用用例、返回状态码。校验规则、发号、拼接、事务都在
 * {@link LinkCreationService}（ARCHITECTURE §4 的分层约束）。
 *
 * <p>返回 <b>201 Created</b>（API.md §5.3 定稿）。
 * <b>刻意不加 {@code Location} 头</b>（DR-028）：指向 {@code shortUrl} 语义混乱
 * （那是给最终用户点击的跳转入口，不是本 API 的资源端点），而指向
 * {@code /api/v1/links/{code}} 的资源端点 D5 才存在——指向一个尚不存在的 URL 比不返回更糟。
 * 响应体里已有 {@code shortUrl}，客户端所需信息完整。
 */
@RestController
@RequestMapping("/api/v1/links")
public class LinkController {

    private final LinkCreationService linkCreationService;

    public LinkController(LinkCreationService linkCreationService) {
        this.linkCreationService = linkCreationService;
    }

    @PostMapping
    public ResponseEntity<CreateLinkResponse> create(@Valid @RequestBody CreateLinkRequest request) {
        CreateLinkResponse response = linkCreationService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
