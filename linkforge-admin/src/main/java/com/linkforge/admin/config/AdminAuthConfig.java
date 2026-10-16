package com.linkforge.admin.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 后台鉴权配置。
 *
 * <h2>⚠️ 为什么 Token 未配置时**启动即失败**（fail-fast）</h2>
 * 没配 {@code ADMIN_API_TOKEN} 的后台接口等于**不设防**——
 * 任何人都能删短链。若选择「启动成功、请求时再报错」，
 * 风险会一直潜伏到有人真的访问后台为止，而那时可能已经删了不该删的数据。
 *
 * <p>fail-fast 把「配置漏了」变成启动日志里最显眼的一行字。
 * ⚠️ <b>错误消息必须明确指出「ADMIN_API_TOKEN 未设置」</b>，
 * 而不是泛泛的「配置错误」——运维看日志要能立刻定位到缺哪个变量。
 */
@Configuration
public class AdminAuthConfig {

    private final String token;

    public AdminAuthConfig(@Value("${ADMIN_API_TOKEN:}") String token) {
        if (token == null || token.isBlank()) {
            // ⚠️ 消息里必须写明具体的环境变量名
            throw new IllegalStateException(
                    "ADMIN_API_TOKEN 未设置：后台接口 /admin/** 依赖它做鉴权。"
                            + "请设置环境变量 ADMIN_API_TOKEN 为一个足够长的随机串后重启。"
                            + "不做 fail-fast 的理由是：未鉴权的后台等于不设防，"
                            + "风险会潜伏到有人真的调用为止");
        }
        this.token = token;
    }

    @Bean
    public AdminToken adminToken() {
        return new AdminToken(token);
    }
}