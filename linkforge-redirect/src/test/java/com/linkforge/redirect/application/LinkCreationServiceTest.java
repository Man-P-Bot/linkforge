package com.linkforge.redirect.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.linkforge.core.domain.Link;
import com.linkforge.core.domain.LinkStatus;
import com.linkforge.core.domain.ReservedCodes;
import com.linkforge.core.domain.ShortLinkBaseUrl;
import com.linkforge.core.error.ErrorCode;
import com.linkforge.core.error.LinkForgeException;
import com.linkforge.core.repository.LinkRepository;
import com.linkforge.redirect.infrastructure.cache.BloomFilterMaintenance;
import com.linkforge.core.repository.ShortCodeGenerator;
import com.linkforge.core.repository.ShortCodeGenerator.ShortCodeGenerationException;
import com.linkforge.core.time.TimeConverter;
import com.linkforge.redirect.api.dto.CreateLinkRequest;
import com.linkforge.redirect.api.dto.CreateLinkResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/**
 * 创建短链用例测试（docs/PRD.md US-1/US-2/US-7、docs/DECISIONS.md DR-022）。
 *
 * <p>用**手写的假实现**而不是 mock 框架的深桩：本用例需要精确控制
 * 「第几次 insert 抛冲突」，手写实现让这个序列显式可见。
 * 发号器也用手写假实现——需要断言「冲突后确实换了新短码」。
 */
class LinkCreationServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-01-15T02:30:00Z");
    private static final ZoneId DISPLAY = ZoneId.of("+08:00");

    /** 假发号器：按给定顺序发放短码，并记录被调用次数。 */
    private static final class FakeGenerator implements ShortCodeGenerator {
        private final List<String> codes;
        private int index;
        private int calls;

        FakeGenerator(String... codes) {
            this.codes = new ArrayList<>(List.of(codes));
        }

        @Override
        public String nextShortCode() {
            calls++;
            if (index >= codes.size()) {
                throw new ShortCodeGenerationException("假发号器已耗尽");
            }
            return codes.get(index++);
        }

        int calls() {
            return calls;
        }
    }

    /** 假仓储：可指定「第 N 次 insert 抛 DuplicateKeyException」，并记录插入内容。 */
    private static final class FakeRepository implements LinkRepository {
        private final List<Link> inserted = new ArrayList<>();
        private final List<Integer> failOnCallNumbers = new ArrayList<>();
        private int insertCalls;

        void failOnInsertCall(int callNumber) {
            failOnCallNumbers.add(callNumber);
        }

        @Override
        public java.util.Optional<Link> findByShortCode(String shortCode) {
            return inserted.stream().filter(l -> l.shortCode().equals(shortCode)).findFirst();
        }

        @Override
        public java.util.Optional<Link> findById(long id) {
            return inserted.stream().filter(l -> l.id() != null && l.id() == id).findFirst();
        }

        @Override
        public void insert(Link link) {
            insertCalls++;
            if (failOnCallNumbers.contains(insertCalls)) {
                throw new DuplicateKeyException("模拟短码唯一索引冲突: " + link.shortCode());
            }
            // 模拟数据库生成 created_at / updated_at 后的回查结果
            inserted.add(new Link(1L, link.shortCode(), link.targetUrl(), link.status(), link.custom(),
                    LocalDateTime.of(2026, 1, 15, 2, 30, 0),
                    LocalDateTime.of(2026, 1, 15, 2, 30, 0),
                    link.expireAt()));
        }

        @Override
        public int markDeleted(long id) {
            return 0;
        }

        @Override
        public List<String> scanActiveShortCodes(long afterId, int limit) {
            return List.of();
        }

        @Override
        public List<com.linkforge.core.repository.LinkRepository.IdShortCode> scanActiveWithId(
                long afterId, int limit) {
            return List.of();
        }

        @Override
        public java.util.Map<String, Long> resolveIdsByShortCodes(
                java.util.Collection<String> shortCodes) {
            // 本测试只覆盖建链流程，不涉及统计落库
            return java.util.Map.of();
        }

        @Override
        public List<com.linkforge.core.repository.LinkRepository.ExpiredLink> scanExpired(
                int limit, java.time.Instant now, long afterId) {
            // 本测试只覆盖建链流程，不涉及过期清理
            return List.of();
        }


        int insertCalls() {
            return insertCalls;
        }

        List<Link> inserted() {
            return inserted;
        }
    }

    private FakeRepository repository;
    private MeterRegistry meterRegistry;
    private LinkValidator validator;

    @BeforeEach
    void setUp() {
        repository = new FakeRepository();
        meterRegistry = new SimpleMeterRegistry();
        validator = new LinkValidator(ReservedCodes.parse(ReservedCodes.DEFAULT_CODES),
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    private LinkCreationService service(ShortCodeGenerator generator, String baseUrl) {
        return new LinkCreationService(repository, generator, validator,
                ShortLinkBaseUrl.parse(baseUrl), TimeConverter.INSTANCE, DISPLAY,
                noopBloomMaintenance(), meterRegistry);
    }

    /**
     * 布隆维护的空实现。
     *
     * <p>本类测的是「发号 / 唯一索引冲突 / 校验」，
     * 布隆写穿有独立的 {@code RedisBloomFilterTest}。
     */
    /**
     * 布隆维护的空实现。
     *
     * <p>⚠️ 刻意做成<b>方法</b>而不是字段：{@code meterRegistry} 在 {@code @BeforeEach}
     * 里才赋值，写成字段会在初始化时传进null——
     * 而 {@code BloomFilterMaintenance} 的构造器会立刻注册计数器，直接 NPE。
     * <p>本类测的是「发号/ 唯一索引冲突 / 校验」，
     * 布隆写穿有独立的 {@code RedisBloomFilterTest}。
     */
    private BloomFilterMaintenance noopBloomMaintenance() {
        return new BloomFilterMaintenance(null, repository, meterRegistry) {
            @Override
            public void writeThrough(String shortCode) {
                // 本类不关心布隆
            }
        };
    }

    private double conflictCount() {
        // ⚠️ 指标名不带 _total：Micrometer 写裸名，Prometheus 导出时自动补后缀。
        // 手工加 _total 会变成 _total_total（DR-058阶段 1）。
        var counter = meterRegistry.find("shortlink.id.conflict").counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Nested
    @DisplayName("成功路径")
    class HappyPath {

        @Test
        @DisplayName("自动短码：返回 7 位短码 + 正确的 shortUrl，custom=false")
        void createsWithGeneratedCode() {
            LinkCreationService service = service(new FakeGenerator("0000Q0u"), "http://localhost:8080");

            CreateLinkResponse response = service.create(
                    new CreateLinkRequest("https://example.com/very/long/path", null, null));

            assertThat(response.shortCode()).isEqualTo("0000Q0u");
            assertThat(response.shortUrl()).isEqualTo("http://localhost:8080/0000Q0u");
            assertThat(response.targetUrl()).isEqualTo("https://example.com/very/long/path");
            assertThat(response.custom()).isFalse();
            assertThat(response.expireAt()).isNull();
        }

        @Test
        @DisplayName("自定义短码：custom=true，且不调用发号器")
        void createsWithCustomCode() {
            FakeGenerator generator = new FakeGenerator();
            LinkCreationService service = service(generator, "http://localhost:8080");

            CreateLinkResponse response = service.create(
                    new CreateLinkRequest("https://example.com/promo", "promo26", null));

            assertThat(response.shortCode()).isEqualTo("promo26");
            assertThat(response.custom()).isTrue();
            assertThat(generator.calls()).isZero();
        }

        @Test
        @DisplayName("⭐ 同一长链连续创建两次得到不同短码（PRD US-7：不做幂等）")
        void sameTargetUrlProducesDifferentCodes() {
            LinkCreationService service = service(new FakeGenerator("0000Q0u", "0000Q0v"),
                    "http://localhost:8080");

            CreateLinkResponse first = service.create(
                    new CreateLinkRequest("https://example.com/same", null, null));
            CreateLinkResponse second = service.create(
                    new CreateLinkRequest("https://example.com/same", null, null));

            assertThat(first.shortCode()).isEqualTo("0000Q0u");
            assertThat(second.shortCode()).isEqualTo("0000Q0v");
            assertThat(first.shortCode()).isNotEqualTo(second.shortCode());
        }

        @Test
        @DisplayName("⭐ BASE_URL 末尾带斜杠同样产出无 // 的 shortUrl")
        void normalizesBaseUrlTrailingSlash() {
            LinkCreationService service = service(new FakeGenerator("0000Q0u"), "http://localhost:8080/");

            CreateLinkResponse response = service.create(
                    new CreateLinkRequest("https://example.com/x", null, null));

            assertThat(response.shortUrl()).isEqualTo("http://localhost:8080/0000Q0u");
            assertThat(response.shortUrl()).doesNotContain("8080//");
        }

        @Test
        @DisplayName("expireAt 往返正确：+08:00 进、+08:00 出，且与输入同一瞬间")
        void expireAtRoundTrip() {
            LinkCreationService service = service(new FakeGenerator("0000Q0u"), "http://localhost:8080");
            OffsetDateTime expireAt = OffsetDateTime.parse("2026-12-31T23:59:59+08:00");

            CreateLinkResponse response = service.create(
                    new CreateLinkRequest("https://example.com/x", null, expireAt));

            assertThat(response.expireAt()).isNotNull();
            assertThat(response.expireAt().toInstant()).isEqualTo(expireAt.toInstant());
            // 存储层拿到的是 UTC 的 LocalDateTime
            assertThat(repository.inserted().get(0).expireAt())
                    .isEqualTo(LocalDateTime.of(2026, 12, 31, 15, 59, 59));
        }

        @Test
        @DisplayName("createdAt 来自数据库回查（不是由应用生成）")
        void createdAtComesFromDatabase() {
            LinkCreationService service = service(new FakeGenerator("0000Q0u"), "http://localhost:8080");

            CreateLinkResponse response = service.create(
                    new CreateLinkRequest("https://example.com/x", null, null));

            assertThat(response.createdAt()).isEqualTo(OffsetDateTime.parse("2026-01-15T10:30:00+08:00"));
        }
    }

    @Nested
    @DisplayName("自动短码冲突重试")
    class GeneratedCodeConflict {

        @Test
        @DisplayName("第一次冲突后重发号并成功，短码与第一次不同")
        void retriesWithNewCode() {
            FakeGenerator generator = new FakeGenerator("0000Q0u", "0000Q0v");
            repository.failOnInsertCall(1);
            LinkCreationService service = service(generator, "http://localhost:8080");

            CreateLinkResponse response = service.create(
                    new CreateLinkRequest("https://example.com/x", null, null));

            assertThat(response.shortCode()).isEqualTo("0000Q0v");
            assertThat(generator.calls()).isEqualTo(2);
            assertThat(repository.insertCalls()).isEqualTo(2);
        }

        @Test
        @DisplayName("⭐ 冲突累加 shortlink.id.conflict 指标（用于发现号段回退，DR-022）")
        void incrementsConflictMetric() {
            FakeGenerator generator = new FakeGenerator("0000Q0u", "0000Q0v");
            repository.failOnInsertCall(1);
            LinkCreationService service = service(generator, "http://localhost:8080");

            service.create(new CreateLinkRequest("https://example.com/x", null, null));

            assertThat(conflictCount()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("连续 4 次冲突 → 503-01，且指标累加 4 次")
        void givesUpAfterMaxAttempts() {
            FakeGenerator generator = new FakeGenerator("a000001", "a000002", "a000003", "a000004", "a000005");
            repository.failOnInsertCall(1);
            repository.failOnInsertCall(2);
            repository.failOnInsertCall(3);
            repository.failOnInsertCall(4);
            LinkCreationService service = service(generator, "http://localhost:8080");

            assertThatThrownBy(() -> service.create(
                    new CreateLinkRequest("https://example.com/x", null, null)))
                    .isInstanceOf(LinkForgeException.class)
                    .extracting(e -> ((LinkForgeException) e).errorCode())
                    .isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE);

            assertThat(conflictCount()).isEqualTo(4.0);
            assertThat(generator.calls()).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("自定义短码冲突：不重试、不累加指标")
    class CustomCodeConflict {

        @Test
        @DisplayName("⭐ 冲突直接 → 409-01，且不调用发号器、不重试")
        void conflictReturns409WithoutRetry() {
            FakeGenerator generator = new FakeGenerator("0000Q0u");
            repository.failOnInsertCall(1);
            LinkCreationService service = service(generator, "http://localhost:8080");

            assertThatThrownBy(() -> service.create(
                    new CreateLinkRequest("https://example.com/x", "promo26", null)))
                    .isInstanceOf(LinkForgeException.class)
                    .extracting(e -> ((LinkForgeException) e).errorCode())
                    .isEqualTo(ErrorCode.SHORT_CODE_CONFLICT);

            assertThat(repository.insertCalls()).isEqualTo(1);
            assertThat(generator.calls()).isZero();
        }

        @Test
        @DisplayName("⭐ 自定义冲突不累加冲突指标（否则指标被日常输入污染，失去告警意义）")
        void doesNotIncrementConflictMetric() {
            repository.failOnInsertCall(1);
            LinkCreationService service = service(new FakeGenerator(), "http://localhost:8080");

            assertThatThrownBy(() -> service.create(
                    new CreateLinkRequest("https://example.com/x", "promo26", null)))
                    .isInstanceOf(LinkForgeException.class);

            assertThat(conflictCount()).isZero();
        }

        @Test
        @DisplayName("⭐ 并发场景：一个成功、一个 409，都不是 500")
        void concurrentSameCustomCodeYields201And409() {
            // 第一次 insert 成功、第二次抛唯一索引冲突 —— 模拟真正并发下的竞争结果
            repository.failOnInsertCall(2);
            LinkCreationService service = service(new FakeGenerator(), "http://localhost:8080");
            CreateLinkRequest request = new CreateLinkRequest("https://example.com/x", "promo26", null);

            // 第一个请求：成功
            CreateLinkResponse response = service.create(request);
            assertThat(response.shortCode()).isEqualTo("promo26");

            // 第二个请求：同一自定义短码 → 409（而不是 500）
            assertThatThrownBy(() -> service.create(request))
                    .isInstanceOf(LinkForgeException.class)
                    .extracting(e -> ((LinkForgeException) e).errorCode())
                    .isEqualTo(ErrorCode.SHORT_CODE_CONFLICT);
        }
    }

    @Nested
    @DisplayName("发号器不可用")
    class GeneratorFailure {

        @Test
        @DisplayName("发号器抛异常（Redis 与号段都挂）→ 503-01")
        void generatorFailureMapsTo503() {
            LinkCreationService service = service(new FakeGenerator(), "http://localhost:8080");

            assertThatThrownBy(() -> service.create(
                    new CreateLinkRequest("https://example.com/x", null, null)))
                    .isInstanceOf(LinkForgeException.class)
                    .extracting(e -> ((LinkForgeException) e).errorCode())
                    .isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE);

            // 发号失败不应产生任何插入尝试
            assertThat(repository.insertCalls()).isZero();
        }

        @Test
        @DisplayName("发号器返回非定长短码 → 503-01（防御性断言）")
        void nonStandardLengthCodeRejected() {
            LinkCreationService service = service(new FakeGenerator("abc"), "http://localhost:8080");

            assertThatThrownBy(() -> service.create(
                    new CreateLinkRequest("https://example.com/x", null, null)))
                    .isInstanceOf(LinkForgeException.class)
                    .extracting(e -> ((LinkForgeException) e).errorCode())
                    .isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    @Nested
    @DisplayName("校验失败时不产生副作用")
    class ValidationFailure {

        @Test
        @DisplayName("协议非法时：不发号、不插入")
        void protocolRejectedBeforeAllocation() {
            FakeGenerator generator = new FakeGenerator("0000Q0u");
            LinkCreationService service = service(generator, "http://localhost:8080");

            assertThatThrownBy(() -> service.create(
                    new CreateLinkRequest("javascript:alert(1)", null, null)))
                    .extracting(e -> ((LinkForgeException) e).errorCode())
                    .isEqualTo(ErrorCode.UNSUPPORTED_PROTOCOL);

            assertThat(generator.calls()).isZero();
            assertThat(repository.insertCalls()).isZero();
        }

        @Test
        @DisplayName("保留字短码时：不发号、不插入")
        void reservedCodeRejectedBeforeAllocation() {
            FakeGenerator generator = new FakeGenerator("0000Q0u");
            LinkCreationService service = service(generator, "http://localhost:8080");

            assertThatThrownBy(() -> service.create(
                    new CreateLinkRequest("https://example.com/x", "API", null)))
                    .extracting(e -> ((LinkForgeException) e).errorCode())
                    .isEqualTo(ErrorCode.RESERVED_CUSTOM_CODE);

            assertThat(repository.insertCalls()).isZero();
        }
    }

    /**
     * 指标命名必须是 Prometheus 兼容的（DR-058 阶段 1）。
     *
     * <p>⚠️ <b>本测试只能验证「代码侧没写 _total」这一半</b>：
     * {@code _total} 后缀是 Prometheus registry 在 scrape 时补的，
     * 而 {@code micrometer-registry-prometheus} <b>当前不在依赖树里</b>
     * （actuator starter 不传递它，需显式引入）。
     * 因此「导出后是否真的是单后缀」需在第 5 周接 Prometheus 时
     * 用 {@code GET /actuator/prometheus} 验证。
     *
     * <p>能在这里断言的：<b>Micrometer 层的裸名里绝不能已含 {@code _total}</b>，
     * 否则 Prometheus 再补一次就成了 {@code _total_total}。
     */
    @Test
    @DisplayName("⭐⭐ Micrometer 层裸名不含 _total（否则 Prometheus 会补成 _total_total）")
    void idConflictMetricNameIsPrometheusCompliant() {
        // ⚠️ 用 SimpleMeterRegistry 是**正确的**：本测试不验证 Prometheus 后缀，
        //    只验证「我们交给 Micrometer 的名字里没有 _total」。
        //    早期版本误用 PrometheusMeterRegistry 断言，
        //    而那个类根本不在依赖树里 -> 编译失败。
        io.micrometer.core.instrument.Counter counter =
                io.micrometer.core.instrument.Counter.builder("shortlink.id.conflict")
                        .register(meterRegistry);

        var meter = meterRegistry.find("shortlink.id.conflict").counter();
        assertThat(meter)
                .as("裸名必须能被 find 到，说明名字里没有多余后缀")
                .isNotNull();
        assertThat(meterRegistry.getMeters())
                .as("注册的指标名不应含 _total（Micrometer 惯例：写裸名，"
                        + "Prometheus 在 scrape 时补）")
                .allSatisfy(m -> assertThat(m.getId().getName())
                        .doesNotContain("_total"));
    }
}
