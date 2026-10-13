package com.linkforge.redirect.infrastructure.id;

import com.linkforge.core.domain.IdSpace;
import com.linkforge.core.infrastructure.persistence.IdSegmentMapper;
import com.linkforge.core.repository.ShortCodeGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 短码发号器的装配（docs/DECISIONS.md DR-011 / DR-022）。
 *
 * <p>装配发生在 {@code redirect} 模块：接口在 {@code core}，实现属跳转链路的基础设施。
 * stats / admin 第 4 周才可能需要发号，届时再评估是否上移，**现在不预移**。
 */
@Configuration
public class ShortCodeGeneratorConfig {

    private static final Logger log = LoggerFactory.getLogger(ShortCodeGeneratorConfig.class);

    /**
     * 号段步长。最终取值由第 1 周 D7 压测决定（docs/DATABASE.md 待确认项 D-4）。
     * 环境变量：{@code SHORTLINK_ID_SEGMENT_STEP}。
     */
    @Value("${linkforge.id.segment-step:" + IdSpace.DEFAULT_SEGMENT_STEP + "}")
    private int segmentStep;

    /**
     * 两个发号器的引用，供启动初始化使用。
     *
     * <p>⚠️ 为什么用字段而不是 {@code @EventListener} 的方法参数：
     * **{@code @EventListener} 的方法参数是「要监听的事件类型」，只能有一个**。
     * 写成 {@code initializeIdSpaces(SegmentShortCodeGenerator, RedisShortCodeGenerator)}
     * 会被 Spring 当成「监听两种事件」并直接启动失败：
     * <pre>
     *   Maximum one parameter is allowed for event listener method
     * </pre>
     * 需要初始化的 Bean 必须通过注入获得，不能塞进监听方法的参数里。
     */
    private SegmentShortCodeGenerator segmentGeneratorBean;
    private RedisShortCodeGenerator redisGeneratorBean;

    @Bean
    public IdSegmentAllocator idSegmentAllocator(IdSegmentMapper idSegmentMapper) {
        return new IdSegmentAllocator(idSegmentMapper, IdSegmentAllocator.BIZ_TAG, segmentStep);
    }

    @Bean
    public SegmentShortCodeGenerator segmentShortCodeGenerator(IdSegmentAllocator allocator) {
        this.segmentGeneratorBean = new SegmentShortCodeGenerator(allocator, segmentStep);
        return this.segmentGeneratorBean;
    }

    @Bean
    public RedisShortCodeGenerator redisShortCodeGenerator(StringRedisTemplate redisTemplate) {
        this.redisGeneratorBean = new RedisShortCodeGenerator(redisTemplate);
        return this.redisGeneratorBean;
    }

    /**
     * 对外暴露的发号器：Redis 主路径 + 号段降级。
     *
     * <p>注入的是这个 Bean，而不是两个具体实现——上层不需要知道当前走哪条路径。
     */
    @Bean
    public ShortCodeGenerator shortCodeGenerator(RedisShortCodeGenerator primary,
                                                 SegmentShortCodeGenerator fallback) {
        return new FallbackShortCodeGenerator(primary, fallback);
    }

    /**
     * 启动后初始化，让失败暴露在启动阶段而不是第一次创建短链时。
     *
     * <p><b>本方法必须无参数</b>（见 {@link #segmentGeneratorBean} 的说明），
     * 要操作的对象通过字段引用取得。
     *
     * <p>顺序有意为之：**先初始化降级路径（号段）再初始化 Redis**。
     * 号段是兜底，必须保证它在 Redis 出问题时一定可用；
     * 而 Redis 初始化失败**不应阻止应用启动**——跳转链路在没有 Redis 时本就要能降级运行
     * （docs/ARCHITECTURE.md §12 降级矩阵）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initializeIdSpaces() {
        segmentGeneratorBean.ensureInitialized();
        try {
            redisGeneratorBean.ensureInitialized();
        } catch (RuntimeException e) {
            // ⚠️ 只记警告，不阻止启动：Redis 不可用时发号会自动降级到号段（DR-022），
            // 跳转链路也本就要能在无 Redis 时降级运行（docs/ARCHITECTURE.md §12）。
            // 绝不能在这里吞掉异常而不留痕迹——那会让「为什么一直在走号段」无从排查。
            log.warn("Redis 发号 key 初始化失败，发号将在首次调用时降级到数据库号段。原因: {}", e.getMessage(), e);
        }
    }
}
