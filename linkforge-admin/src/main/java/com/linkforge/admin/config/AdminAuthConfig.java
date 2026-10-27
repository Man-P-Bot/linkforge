package com.linkforge.admin.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

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

    /**
     * 注册后台鉴权过滤器。
     *
     * <h2>⚠️ 为什么用 {@link FilterRegistrationBean} 而不是 {@code @Component}</h2>
     * {@code @Component} 声明的 {@code Filter} 会被容器注册到<b>所有</b>路径上，
     * 于是过滤器自己必须判断「这个路径要不要鉴权」——
     * 而那正是最容易出错的判断（漏一个前缀 = 漏一个接口的防护）。
     *
     * <p>用 {@code FilterRegistrationBean} 把 <b>URL 模式</b>交给容器：
     * 只有 {@code /admin/*} 会进入过滤器，其余路径连方法都不会被调用。
     * 「哪些路径受保护」由容器保证，不依赖运行期判断。
     *
     * <h2>⚠️ 为什么顺序要显式指定</h2>
     * {@code Ordered.HIGHEST_PRECEDENCE + 10}：让鉴权<b>尽可能早</b>执行 ——
     * 越早拒绝，越少资源被消耗在「注定要 401 的请求」上。
     */
    @Bean
    public FilterRegistrationBean<AdminAuthFilter> adminAuthFilterRegistration(AdminToken adminToken,
                                                                                ZoneId displayZone,
                                                                                ObjectMapper objectMapper) {
        FilterRegistrationBean<AdminAuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new AdminAuthFilter(adminToken, displayZone, objectMapper));
        registration.addUrlPatterns("/admin/*");
        registration.setName("adminAuthFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}