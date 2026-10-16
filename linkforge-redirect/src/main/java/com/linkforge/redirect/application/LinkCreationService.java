package com.linkforge.redirect.application;

import com.linkforge.core.domain.IdSpace;
import com.linkforge.core.domain.Link;
import com.linkforge.core.domain.LinkStatus;
import com.linkforge.core.domain.ShortLinkBaseUrl;
import com.linkforge.core.error.ErrorCode;
import com.linkforge.core.error.LinkForgeException;
import com.linkforge.core.repository.LinkRepository;
import com.linkforge.core.repository.ShortCodeGenerator;
import com.linkforge.core.repository.ShortCodeGenerator.ShortCodeGenerationException;
import com.linkforge.core.time.TimeConverter;
import com.linkforge.redirect.api.dto.CreateLinkRequest;
import com.linkforge.redirect.api.dto.CreateLinkResponse;
import com.linkforge.redirect.infrastructure.cache.BloomFilterMaintenance;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 创建短链用例（docs/API.md §5）。
 *
 * <h2>职责边界</h2>
 * <ul>
 *   <li>校验交 {@link LinkValidator}（业务规则）、注解（字段格式）；</li>
 *   <li>{@code shortUrl} 拼接在**本层**（不在 Controller）：{@link ShortLinkBaseUrl} 是配置，
 *       启动时校验（DR-026），Controller 保持零业务逻辑；</li>
 *   <li>时区转换在**本层边界**（DR-027）：请求进 {@code toUtc}、响应出 {@code toOffset}。
 *       仓储只认 UTC 的 {@link LocalDateTime}，不做任何转换。</li>
 * </ul>
 *
 * <h2>冲突重试为什么在 Service 而不是 Repository</h2>
 * <ol>
 *   <li>重试需要**重新发号**（拿新短码再插一次），而发号器是 Service 的协作对象，
 *       Repository 不该知道发号器的存在；</li>
 *   <li>重试是**用例级策略**（重试几次、超限如何报错），属应用层；</li>
 *   <li>放 Repository 会让「一次 insert」隐式变成「可能插入多次」，违反最小惊讶原则。</li>
 * </ol>
 *
 * <h2>⚠️ 指标 {@code shortlink_id_conflict_total} 只在自动短码冲突时累加</h2>
 * 自定义短码冲突是**正常的用户输入冲突**（返回 409），若也累加该指标，
 * 指标会被日常使用污染，从而**失去「Redis 号段回退」的告警意义**（DR-022）。
 */
@Service
public class LinkCreationService {

    private static final Logger log = LoggerFactory.getLogger(LinkCreationService.class);

    /**
     * 自动短码插入冲突的重试上限（含首次尝试）。
     *
     * <p>理论上**不应发生**：发号器保证序号唯一 ⇒ 短码唯一。一旦发生说明发号器或号段有问题，
     * 因此重试只是「尽量把请求救回来」，同时必须留下指标与日志。
     */
    private static final int MAX_ATTEMPTS = 4;

    private final LinkRepository linkRepository;
    private final ShortCodeGenerator shortCodeGenerator;
    private final LinkValidator linkValidator;
    private final ShortLinkBaseUrl baseUrl;
    private final TimeConverter timeConverter;
    private final ZoneId displayZone;
    private final BloomFilterMaintenance bloomMaintenance;
    private final Counter idConflictCounter;

    public LinkCreationService(LinkRepository linkRepository,
                               ShortCodeGenerator shortCodeGenerator,
                               LinkValidator linkValidator,
                               ShortLinkBaseUrl baseUrl,
                               TimeConverter timeConverter,
                               ZoneId displayZone,
                               BloomFilterMaintenance bloomMaintenance,
                               MeterRegistry meterRegistry) {
        this.linkRepository = linkRepository;
        this.shortCodeGenerator = shortCodeGenerator;
        this.linkValidator = linkValidator;
        this.baseUrl = baseUrl;
        this.timeConverter = timeConverter;
        this.displayZone = displayZone;
        this.bloomMaintenance = bloomMaintenance;
        this.idConflictCounter = Counter.builder("shortlink_id_conflict_total")
                .description("自动生成短码插入时命中唯一索引的次数。理论上应恒为 0；"
                        + "突增说明发号器号段可能回退（docs/DECISIONS.md DR-022）")
                .register(meterRegistry);
    }

    /**
     * 创建短链。
     *
     * @throws LinkForgeException 校验失败或发号失败（由 GlobalExceptionHandler 翻译为 HTTP）
     */
    @Transactional
    public CreateLinkResponse create(CreateLinkRequest request) {
        // 1. 业务规则校验（顺序有意：先校验再发号，避免为空请求消耗序号）
        linkValidator.validateTargetUrl(request.targetUrl());
        linkValidator.validateCustomCode(request.customCode());
        linkValidator.validateExpireAt(request.expireAt());

        boolean custom = request.customCode() != null && !request.customCode().isBlank();
        LocalDateTime expireAtUtc = timeConverter.toUtc(request.expireAt());

        // 2. 自定义短码：冲突**不重试**，直接 409（PRD US-2：不静默覆盖）
        if (custom) {
            Link link = insertCustom(request.customCode(), request.targetUrl(), expireAtUtc);
            // ⚠️ 与自动短码一样挂到提交后（见 step 4 的说明）
            registerBloomWriteThrough(link.shortCode());
            return toResponse(link);
        }

        // 3. 自动短码：唯一索引冲突时重新发号重试
        Link link = insertGenerated(request.targetUrl(), expireAtUtc);
        // 4. 写穿布隆（ARCHITECTURE.md §259）。⚠️ 必须在**事务提交之后**——
        //    布隆写入不属于数据库事务；若在事务内写，事务回滚后布隆里会留下
        //    一个「存在」但库里没有的短码。它不会导致 404（误判存在只多查一次库），
        //    但会永久占着布隆的位，直到下次重建。
        //    实现见 registerBloomWriteThrough。
        registerBloomWriteThrough(link.shortCode());
        return toResponse(link);
    }

    /**
     * 把布隆写穿挂到<b>事务提交后</b>。
     *
     * <p>⚠️ 没有事务时（理论上不会，{@code @Transactional} 保证有）立即执行。
     */
    private void registerBloomWriteThrough(String shortCode) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            bloomMaintenance.writeThrough(shortCode);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                bloomMaintenance.writeThrough(shortCode);
            }
        });
    }

    private Link insertCustom(String customCode, String targetUrl, LocalDateTime expireAtUtc) {
        Link link = new Link(null, customCode, targetUrl, LinkStatus.ACTIVE, true, null, null, expireAtUtc);
        try {
            linkRepository.insert(link);
        } catch (DuplicateKeyException e) {
            // 自定义短码冲突是正常业务结果，**不累加冲突指标**（见类注释）
            throw new LinkForgeException(ErrorCode.SHORT_CODE_CONFLICT,
                    "customCode 已被占用: " + customCode, e);
        }
        return reload(link.shortCode());
    }

    private Link insertGenerated(String targetUrl, LocalDateTime expireAtUtc) {
        LinkForgeException lastFailure = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String shortCode;
            try {
                shortCode = shortCodeGenerator.nextShortCode();
            } catch (ShortCodeGenerationException e) {
                // 发号器彻底不可用（Redis 与数据库号段都失败）→ 503
                throw new LinkForgeException(ErrorCode.DEPENDENCY_UNAVAILABLE,
                        "短码发号失败: " + e.getMessage(), e);
            }

            if (shortCode.length() != IdSpace.SHORT_CODE_LENGTH) {
                // 防御性断言：发号器返回定长短码是契约
                throw new LinkForgeException(ErrorCode.DEPENDENCY_UNAVAILABLE,
                        "发号器返回了非定长短码: " + shortCode);
            }

            Link link = new Link(null, shortCode, targetUrl, LinkStatus.ACTIVE, false, null, null, expireAtUtc);
            try {
                linkRepository.insert(link);
                return reload(shortCode);
            } catch (DuplicateKeyException e) {
                // ⚠️ 理论上不应发生（序号唯一 ⇒ 短码唯一）。累加指标以便**立即发现号段回退**（DR-022）
                idConflictCounter.increment();
                log.error("短码插入冲突（第 {}/{} 次）：shortCode={}。"
                                + "该情况理论上不应发生，请检查发号器号段是否回退（DR-022）",
                        attempt, MAX_ATTEMPTS, shortCode, e);
                lastFailure = new LinkForgeException(ErrorCode.DEPENDENCY_UNAVAILABLE,
                        "短码冲突重试已达上限", e);
            }
        }

        throw lastFailure;
    }

    /**
     * 插入后回查，拿到数据库生成的 {@code created_at} / {@code updated_at}（DATABASE §4.1）。
     *
     * <p>这两个字段由数据库默认值生成，`useGeneratedKeys` 只回填主键，因此必须回查一次。
     * 回查同时也是「插入确实落库」的验证。
     */
    private Link reload(String shortCode) {
        return linkRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new LinkForgeException(ErrorCode.DEPENDENCY_UNAVAILABLE,
                        "插入后回查不到记录: " + shortCode));
    }

    private CreateLinkResponse toResponse(Link link) {
        return new CreateLinkResponse(
                link.shortCode(),
                baseUrl.toShortUrl(link.shortCode()),
                link.targetUrl(),
                link.custom(),
                timeConverter.toOffset(link.expireAt(), displayZone),
                timeConverter.toOffset(link.createdAt(), displayZone));
    }
}
