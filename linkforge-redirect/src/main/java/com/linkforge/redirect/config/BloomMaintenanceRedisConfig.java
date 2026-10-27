package com.linkforge.redirect.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 布隆重建专用的 Redis 连接（DR-065）。
 *
 * <h2>⚠️ 为什么必须单独一个连接工厂</h2>
 * 生产配置的 {@code spring.data.redis.timeout} 是 <b>50ms</b>（DR-011）——
 * 那是<b>跳转命令</b>的执行预算：宁可超时降级查库，也不能让请求挂在 Redis 上。
 *
 * <p>但布隆全量重建是**批量写**：一次 pipeline 要写几千个短码 × 7 个位，
 * 远超 50ms。实测（2 万条短码）：用 50ms 的模板做 pipeline → {@code Redis command timed out}
 * → 重建失败 → 布隆停留在旧版本 → <b>新短码继续 404</b>。
 *
 * <p>把全局超时调大是错的方向：那会把跳转路径的延迟保证一起放松掉，
 * 而跳转路径才是真正在意 50ms 的那个。
 *
 * <p>所以：<b>热路径用 50ms 的模板，批量维护用自己的模板</b>。
 * 两者指向同一个 Redis（同一 host/port/db），只是超时预算不同。
 *
 * <h2>⚠️ 单实例连接</h2>
 * 这个连接工厂只用于低频的重建任务，不需要连接池规模，
 * 默认值即可；它不会影响跳转路径的连接池指标。
 */
@Configuration
public class BloomMaintenanceRedisConfig {

    /**
     * 重建专用模板的超时（默认 5 秒）。
     *
     * <p>⚠️ 与 {@code spring.data.redis.timeout}（50ms）**故意不同**：
     * 那个是跳转命令的延迟预算，这个是批量任务的执行预算。
     */
    @Value("${linkforge.bloom.maintenance-timeout:5s}")
    private Duration maintenanceTimeout;

    /**
     * 布隆重建专用的 {@link StringRedisTemplate}。
     *
     * <p>⚠️ 连接参数（host/port/密码/db/SSL）全部取自 {@link RedisProperties}，
     * 即与应用其它 Redis 连接<b>同源</b>——
     * 手工复制一份配置是「两处配置迟早漂移」的经典来源（DR-014 的同款教训）。
     */
    @Bean
    public StringRedisTemplate bloomMaintenanceRedisTemplate(RedisProperties properties) {
        RedisStandaloneConfiguration standalone =
                new RedisStandaloneConfiguration(properties.getHost(), properties.getPort());
        standalone.setDatabase(properties.getDatabase());
        if (properties.getUsername() != null && !properties.getUsername().isBlank()) {
            // Redis 6+ 的 ACL 用户名
            standalone.setUsername(properties.getUsername());
        }
        if (properties.getPassword() != null && !properties.getPassword().isBlank()) {
            standalone.setPassword(properties.getPassword());
        }
        LettuceConnectionFactory factory = new LettuceConnectionFactory(standalone);
        if (properties.getClientName() != null && !properties.getClientName().isBlank()) {
            factory.setClientName(properties.getClientName());
        }
        if (properties.getSsl() != null && properties.getSsl().isEnabled()) {
            factory.setUseSsl(true);
        }
        // ⚠️ 这一行是本配置类的全部意义所在：给批量写一个独立的超时预算
        //    （单位是毫秒；跳转路径的模板是 50ms，这里是 5s）
        factory.setTimeout(maintenanceTimeout.toMillis());
        factory.afterPropertiesSet();
        return new StringRedisTemplate(factory);
    }
}
