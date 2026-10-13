package com.linkforge.redirect.config;

import com.linkforge.core.domain.ReservedCodes;
import com.linkforge.core.domain.ShortLinkBaseUrl;
import com.linkforge.core.time.TimeConverter;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * redirect 模块的基础设施装配：配置值对象与时间源。
 *
 * <h2>⚠️ 这里的 Bean 都在启动期校验配置（DR-014 / DR-026 / DR-027）</h2>
 * {@link ShortLinkBaseUrl#parse} 在**构造 Bean 时**就会对非法配置抛异常，
 * 于是「BASE_URL 为空/纯斜杠/无 scheme」会让**应用启动失败**，而不是等第一次创建短链才 500。
 * 这是刻意的：配置错误暴露得越早，排查成本越低。
 *
 * <p>{@link ReservedCodes} 同理——黑名单为空会让防线失效，必须在启动时发现。
 */
@Configuration
public class RedirectConfig {

    /** 响应时间的渲染时区（存储永远是 UTC，DR-027）。默认 +08:00。 */
    @Value("${linkforge.time.display-zone:+08:00}")
    private String displayZoneId;

    /**
     * 对外基地址，用于拼 {@code shortUrl}。
     *
     * <p>无默认值：缺失即启动失败（DR-014）。若给默认值，生产忘记设置就会返回
     * {@code http://localhost:8080/...} 这种**对外不可用的短链**，错误发生在用户点击时，排查成本极高。
     */
    @Bean
    public ShortLinkBaseUrl shortLinkBaseUrl(@Value("${SHORTLINK_BASE_URL}") String raw) {
        return ShortLinkBaseUrl.parse(raw);
    }

    /** 保留字黑名单（配置化，新增无需改代码）。 */
    @Bean
    public ReservedCodes reservedCodes(@Value("${RESERVED_CODES:" + ReservedCodes.DEFAULT_CODES + "}") String raw) {
        return ReservedCodes.parse(raw);
    }

    /**
     * 时间源。
     *
     * <p>抽成 Bean 而不是各处调用 {@code OffsetDateTime.now()}：过期判定与
     * {@code expireAt} 的「将来性」检查都依赖当前时间，而**时间必须可注入**才能写单测（PRD §7.3）。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** 展示时区，供 {@code TimeConverter.toOffset} 与错误体时间戳使用。 */
    @Bean
    public ZoneId displayZone() {
        return ZoneId.of(displayZoneId);
    }

    /** 唯一的时间转换入口（DR-027）。 */
    @Bean
    public TimeConverter timeConverter() {
        return TimeConverter.INSTANCE;
    }
}
