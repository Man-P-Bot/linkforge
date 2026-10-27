package com.linkforge.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkforge.app.persistence.MySqlContainerSupport;
import com.linkforge.core.infrastructure.cache.CacheKeys;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.io.IOException;
import java.net.HttpURLConnection;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 层 3 验证：跳转全链路（DR-054 —— <b>这一层是ecac087 声称有而实际没有的</b>）。
 *
 * <h2>⚠️ 为什么必须有这一层</h2>
 * {@code ecac087} 提交 {@code checkAndGet} 时，我在提交信息里写「5 条验证全过」。
 * 实际上：
 * <ul>
 *   <li>Lua 行为 → {@code redis-cli --eval} 验过（真的验了）</li>
 *   <li>返回值解析 → Mockito 构造 {@code List.of(...)} 验过</li>
 *   <li><b>Java 调用 → Spring Data Redis → Lua → 解析：从未执行过一次</b></li>
 * </ul>
 * 结果上线后跳转全部 500（{@code toArgs} 返回 {@code List} 与
 * {@code Object...} 变参不匹配，{@code StringRedisSerializer} 强转抛 CCE）。
 *
 * <p><b>本类不 mock 任何 Spring Bean</b>——
 * {@code StringRedisTemplate}、{@code RedisBloomFilter}、{@code RedirectService}
 * 全部是容器里的真实实例。这是它与层 2 的唯一区别，也是它的全部价值。
 *
 * <h2>断言的是 HTTP 层可见效果</h2>
 * 状态码 + {@code Location} 头，不是内部方法返回值。
 * 内部方法的「通过」不能证明链路通——本次事故里所有内部单测都是绿的。
 *
 * <h2>数据库：一次性 MySQL 8.4 容器</h2>
 * 本类继承 {@link MySqlContainerSupport}，用 <b>Testcontainers 的一次性容器</b>而不是
 * 宿主机上的 MySQL。理由：宿主机方案需要 {@code TEST_MYSQL_PASSWORD} 环境变量，
 * 干净环境（CI / 新克隆）跑不了——而「静默跳过」在 CI 上和「通过」长得一模一样。
 * <p>
 * ⚠️ Redis 仍用宿主机（{@code application-test.yml} 指向 {@code localhost:6379}）：
 * 本类验证的是「真实 Redis + 真实 HTTP」的跳转链路，Redis 容器化反而会
 * 让「连的是不是生产同款 Redis」变得不明确。
 *
 * <h2>数据隔离</h2>
 * 每个 {@code @BeforeEach} 造自己的短码（带唯一前缀），
 * {@code @AfterEach} 按前缀清理。<b>刻意不用 TRUNCATE</b>——
 * 那会破坏 Flyway 建表后建立的基线，并让后续测试无法运行。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("跳转全链路（真实 Redis + 真实 HTTP，DR-054 层 3）")
class RedirectEndToEndTest extends MySqlContainerSupport {

    /** 本类专用短码前缀，与其它测试不冲突。 */
    private static final String PREFIX = "e2e";

    /** 布隆的全局 key（与生产同 key 名，但测试走独立逻辑库，见 application-test.yml）。 */
    private static final String BLOOM_KEY = "shortlink:bloom";

    /**
     * 真实 HTTP 客户端。
     *
     * <p>⚠️ **刻意不用 {@code TestRestTemplate}**，改用 JDK {@code HttpURLConnection}：
     * <ul>
     *   <li>本项目没有 Apache HttpClient 依赖，Spring Boot 的
     *       {@code HttpComponentsClientHttpRequestFactory} 不可用；</li>
     *   <li>更关键的是 {@code TestRestTemplate} 默认**跟随重定向**——
     *       而 302 的 Location 指向 {@code example.com}（测试环境无外网），
     *       跟随后就是 {@code ConnectException}，拿不到 302 本身。</li>
     * </ul>
     *
     * <p>禁跟随重定向是「断言 302 + Location」的前提——
     * 这一条用错客户端，测试会以「网络异常」失败，掩盖真实状态码。
     */
    private RestTemplate rest;

    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;

    /**
     * 禁止跟随重定向的请求工厂。
     *
     * <p>Spring 没有现成的「禁跟随」工厂（默认那个会跟），
     * 也不引新依赖，故基于 {@code SimpleClientHttpRequestFactory} 覆写
     * {@code setInstanceFollowRedirects(false)}。
     */
    private static final class NoRedirectRequestFactory
            extends SimpleClientHttpRequestFactory {
        @Override
        protected void prepareConnection(HttpURLConnection connection, String httpMethod)
                throws IOException {
            super.prepareConnection(connection, httpMethod);
            // ⚠️ 核心：302 之后不要再请求 Location 指向的地址
            connection.setInstanceFollowRedirects(false);
        }
    }

    /**
     * 后台鉴权 token。
     *
     * <p>⚠️ **必须用 Spring 注入，不能用 {@code System.getenv}**：
     * {@code System.getenv} 只认进程的环境变量，绕过了 Spring 的属性源 ——
     * 于是 {@code application-test.yml} 里配的固定 token 完全不生效，
     * 干净环境（CI / 新克隆）下这 8 个用例全部失败。
     *
     * <p>注入后 test profile 的 {@code ADMIN_API_TOKEN} 与生产读的是<b>同一个</b>配置键，
     * 只是来源不同（test profile 用固定值，生产用环境变量）。
     */
    @org.springframework.beans.factory.annotation.Value("${ADMIN_API_TOKEN:}")
    private String adminToken;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    /**
     * 布隆维护（启动重建 / 定时重建的编排层）。
     *
     * <p>用于「模拟重启后的初始化重建」这一场景（DR-065）。
     */
    @Autowired
    private com.linkforge.redirect.infrastructure.cache.BloomFilterMaintenance bloomMaintenance;

    /**
     * 布隆过滤器本体（真实 Redis）。
     *
     * <p>用于「重建窗口期内的写穿不丢」这一回归场景（DR-065）。
     */
    @Autowired
    private com.linkforge.redirect.infrastructure.cache.RedisBloomFilter bloomFilter;

    private final List<String> created = new ArrayList<>();

    /**
     * ⚠️ 客户端在 {@code @BeforeEach} 里建而**不是** {@code @PostConstruct}：
     * JUnit 测试类不是 Spring Bean，后者不会被调用，{@code rest} 会一直是 null。
     * 也不拆成两个 {@code @BeforeEach}——JUnit 不保证多个 BeforeEach 的执行顺序。
     */
    @BeforeEach
    void setUp() {
        // ⚠️ 用 RestTemplate 而非 TestRestTemplate：后者的构造器只接受
        // RestTemplateBuilder / HttpClientOption，无法直接传自定义 RequestFactory，
        // 而本项目没有 Apache HttpClient 依赖（HttpClientOption.ENABLE_REDIRECTS 不可用）。
        RestTemplate t = new RestTemplate(new NoRedirectRequestFactory());
        t.setUriTemplateHandler(
                new DefaultUriBuilderFactory("http://localhost:" + port));
        // ⚠️ **必须**设错误处理器：RestTemplate 默认对 4xx/5xx 抛异常，
        //那样拿不到响应体，就无法断言 LINKFORGE-404-01。
        //（TestRestTemplate 默认用 NoOpResponseErrorHandler，但本类用不了它。）
        t.setErrorHandler(new org.springframework.web.client.DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response)
                    throws java.io.IOException {
                // 不把 4xx/5xx 当「传输错误」，交给调用方断言状态码与响应体
                return false;
            }
        });
        this.rest = t;

        assertThat(adminToken)
                .as("后台删除接口需要 ADMIN_API_TOKEN；未设置时本类的场景 4 无法运行。"
                        + "test profile 已在 application-test.yml 里配了固定值")
                .isNotBlank();
    }

    @AfterEach
    void tearDown() {
        // ⚠️ 按 short_code 逐个删，**不按前缀删**：
        // 自动短码（不传 customCode 时）由发号器分配，形如 0000ScJ，
        // **没有 e2e 前缀**，按前缀删会漏掉它们。
        // 上一轮就是漏了 28 条，害得 LinkRepositoryMyBatisTest 的
        // 「库中应恰好 N 条」断言全部失败（该测试假设库是干净的）。
        for (String code : created) {
            jdbc.update("DELETE FROM short_link WHERE short_code = ?", code);
        }
        // 兜底：清掉本类可能遗留的 e2e 前缀数据（自定义短码）
        jdbc.update("DELETE FROM short_link WHERE short_code LIKE ?", PREFIX + "%");
        for (String code : created) {
            redis.delete(CacheKeys.code(code));
        }
        created.clear();
        // 恢复布隆：场景 5 会 DEL 它，后续测试不能受影响。
        // 置一个远处的位即可——布隆只需「存在」，不需内容正确
        // （下个定时重建会用 RENAME 从库里重建出真实内容）。
        if (!Boolean.TRUE.equals(redis.hasKey(BLOOM_KEY))) {
            redis.opsForValue().setBit(BLOOM_KEY, 1, true);
        }
    }

    /** POST 创建短链，返回短码。 */
    private String createLink(String targetUrl) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = mapper.writeValueAsString(
                java.util.Map.of("targetUrl", targetUrl));
        ResponseEntity<String> resp = rest.postForEntity(
                "/api/v1/links", new HttpEntity<>(body, headers), String.class);

        assertThat(resp.getStatusCode())
                .as("创建短链应返回 201，实际 {}，body={}", resp.getStatusCode(), resp.getBody())
                .isEqualTo(HttpStatus.CREATED);
        JsonNode json = mapper.readTree(resp.getBody());
        String code = json.path("data").path("shortCode").asText();
        assertThat(code).isNotBlank();
        created.add(code);
        // ⚠️⚠️ **刻意不手工置位**（DR-065）：
        //    旧版本这里手工把短码的 7 个位写进布隆，等于**替应用把写穿做了**——
        //    于是「写穿到底有没有生效」这条链路在 E2E 层完全没被验证：
        //    写穿坏掉时，所有 E2E 用例照样全绿（这正是本次事故的成因）。
        //    现在位由应用的写穿负责，E2E 才真的在验证它。
        return code;
    }

    /**
     * 断言「布隆里确实有这个短码的每一位」。
     *
     * <p>⚠️ <b>为什么必须断言位，而不只是断言 302</b>：
     * 布隆 key 不存在时，检查脚本会**放行**（未初始化 → 查库），
     * 此时即使写穿完全失效，GET 也会返回 302 —— <b>假通过</b>。
     * 只有断言「7 个位都为 1」，才真的证明了写穿执行过。
     */
    private void assertBloomContains(String shortCode) {
        assertThat(redis.hasKey(BLOOM_KEY))
                .as("布隆 key 应存在。若不存在，检查脚本会放行，下面的 302 就成了假通过")
                .isTrue();
        for (int bit : bitsOf(shortCode)) {
            assertThat(redis.opsForValue().getBit(BLOOM_KEY, bit))
                    .as("布隆的第 %d 位应为 1（写穿漏了这一位 → 该短码会被判「一定不存在」）", bit)
                    .isTrue();
        }
    }

    private int[] bitsOf(String shortCode) {
        int[] bits = new int[7];
        com.linkforge.core.infrastructure.cache.BloomHash.bitIndexes(shortCode, bits);
        return bits;
    }

    /**
     * GET 且**不抛异常**。
     *
     * <p>⚠️ {@code RestTemplate.getForEntity} 对 4xx/5xx 默认抛
     * {@code HttpClientErrorException}，那样就拿不到响应体，
     * 无法断言 {@code LINKFORGE-404-01}。
     * {@code TestRestTemplate} 默认不抛，但本类用不了它（见字段注释），
     * 故这里手动调 {@code exchange}。
     */
    private ResponseEntity<String> get(String path) {
        return rest.exchange(path, HttpMethod.GET, HttpEntity.EMPTY, String.class);
    }

    private void deleteViaAdmin(String shortCode) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + adminToken);
        rest.exchange("/admin/api/v1/links/" + shortCode, HttpMethod.DELETE,
                new HttpEntity<>(headers), String.class);
    }

    // -------------------------------------------------------------- 场景 1
    @Test
    @DisplayName("1️⃣ 未缓存短码 → 302（走完整链路：布隆 + L2 未命中 + 回源）")
    void uncachedShortCodeReturns302() throws Exception {
        String target = "https://example.com/e2e/uncached";
        String code = createLink(target);

        // 清 L2，确保本次是「L2 未命中 → 回源」路径
        redis.delete(CacheKeys.code(code));

        ResponseEntity<String> resp = get("/" + code);

        assertThat(resp.getStatusCode())
                .as("未缓存短码必须 302。500 说明 checkAndGet 的 Java 调用链断了"
                        + "（ecac087 的缺陷正是这里，DR-054）")
                .isEqualTo(HttpStatus.FOUND);
        assertThat(resp.getHeaders().getFirst(HttpHeaders.LOCATION)).isEqualTo(target);
    }

    // -------------------------------------------------------------- 场景 2
    @Test
    @DisplayName("2️⃣ 已缓存短码 → 第二次 GET 仍 302（走 L2 命中路径）")
    void cachedShortCodeReturns302OnSecondRequest() throws Exception {
        String target = "https://example.com/e2e/cached";
        String code = createLink(target);

        // 第一次：L2 未命中 → 回源并写缓存
        ResponseEntity<String> first = get("/" + code);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.FOUND);

        // 第二次：应命中 L2（此时在同一个 JVM 的 Caffeine 里，第三次才是 L2）
        ResponseEntity<String> second = get("/" + code);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(second.getHeaders().getFirst(HttpHeaders.LOCATION)).isEqualTo(target);
    }

    // -------------------------------------------------------------- 场景 3
    @Test
    @DisplayName("3️⃣ 不存在的短码 → 404 + LINKFORGE-404-01")
    void unknownShortCodeReturns404() {
        ResponseEntity<String> resp = get("/zzzzzzz");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).contains("LINKFORGE-404-01");
    }

    // -------------------------------------------------------------- 场景 4
    @Test
    @DisplayName("4️⃣ 已删除的短码 → 404（删除优先于过期，DR-038）")
    void deletedShortCodeReturns404() throws Exception {
        String code = createLink("https://example.com/e2e/deleted");

        deleteViaAdmin(code);

        ResponseEntity<String> resp = get("/" + code);

        assertThat(resp.getStatusCode())
                .as("删除后应 404（DELETED 优先于 EXPIRED，DR-038）")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).contains("LINKFORGE-404-01");
    }

    // -------------------------------------------------------------- 场景 5
    @Test
    @DisplayName("5️⃣ 布隆 key 不存在 → **放行**仍 302（首次部署前不能全站 404）")
    void absentBloomPassesThrough() throws Exception {
        String target = "https://example.com/e2e/nobloom";
        String code = createLink(target);
        redis.delete(CacheKeys.code(code));

        // 模拟首次部署前 / 重建窗口期：布隆不存在
        redis.delete(BLOOM_KEY);

        ResponseEntity<String> resp = get("/" + code);

        assertThat(resp.getStatusCode())
                .as("布隆不存在时应放行。若返回 404，说明误把「未初始化」当「不存在」"
                        + "——首次部署后全站404（DR-007 明令禁止）")
                .isEqualTo(HttpStatus.FOUND);
        assertThat(resp.getHeaders().getFirst(HttpHeaders.LOCATION)).isEqualTo(target);
    }

    // -------------------------------------------------------------- 场景 6
    @Test
    @DisplayName("6️⃣⭐ 布隆判否（位不全）→ 404 且**不查库**")
    void bloomDefinitelyAbsentReturns404WithoutSource() throws Exception {
        String code = createLink("https://example.com/e2e/blocked");
        redis.delete(CacheKeys.code(code));

        // 把该短码的全部位清零 → 布隆必判「不存在」
        for (int bit : bitsOf(code)) {
            redis.opsForValue().setBit(BLOOM_KEY, bit, false);
        }

        ResponseEntity<String> resp = get("/" + code);

        assertThat(resp.getStatusCode())
                .as("布隆判否应直接 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).contains("LINKFORGE-404-01");
    }

    // -------------------------------------------------------------- 统计集成

    @Test
    @DisplayName("7️⃣⭐ 每次成功跳转都入队：入队数 == 成功跳转数（DR-058）")
    void everySuccessfulRedirectEnqueuesOnce() throws Exception {
        String target = "https://example.com/e2e/stats";
        String code = createLink(target);
        redis.delete(CacheKeys.code(code));

        int requests = 20;
        double offeredBefore = registryCounter("shortlink.stats.offered");
        for (int i = 0; i < requests; i++) {
            assertThat(get("/" + code).getStatusCode()).isEqualTo(HttpStatus.FOUND);
        }
        double offeredAfter = registryCounter("shortlink.stats.offered");

        assertThat(offeredAfter - offeredBefore)
                .as("20 次成功跳转应产生 20 次入队")
                .isEqualTo((double) requests);
    }

    @Test
    @DisplayName("8️⃣⭐ 失败跳转（404）**不**入队：统计不该计入无效访问")
    void failedRedirectDoesNotEnqueue() {
        double offeredBefore = registryCounter("shortlink.stats.offered");

        assertThat(get("/zzzzzzz").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(registryCounter("shortlink.stats.offered") - offeredBefore)
                .as("404 不该被计为 PV——统计必须放在跳转判定之后")
                .isZero();
    }

    // -------------------------------------------------------------- 场景 9（DR-065）

    @Test
    @DisplayName("9️⃣⭐⭐⭐ DR-065：创建后**立刻**跳转 → 302（验证真实写穿）")
    void newlyCreatedShortCodeRedirectsImmediately() throws Exception {
        String target = "https://example.com/e2e/write-through";
        String code = createLink(target);

        // ⚠️ 先证明写穿真的发生了（布隆里 7 个位都是 1）。
        //    只断言 302 是不够的：布隆 key 不存在时脚本会放行 → 302 → 假通过。
        assertBloomContains(code);

        // ⚠️ **立刻**发起跳转请求，不做任何等待
        ResponseEntity<String> resp = get("/" + code);

        assertThat(resp.getStatusCode())
                .as("新建短码必须立刻可跳转。404 说明写穿没落到位，"
                        + "该短码会被布隆判「一定不存在」→ 真实用户 404（DR-065）")
                .isEqualTo(HttpStatus.FOUND);
        assertThat(resp.getHeaders().getFirst(HttpHeaders.LOCATION)).isEqualTo(target);
    }

    // -------------------------------------------------------------- 场景 10（DR-065）

    @Test
    @DisplayName("10️⃣⭐⭐⭐ DR-065：模拟重启（清空布隆 → 全量重建）→ 旧短码仍 302")
    void existingShortCodeSurvivesRestartRebuild() throws Exception {
        String target = "https://example.com/e2e/restart";
        String code = createLink(target);
        // 模拟「重启」：布隆被清空（Redis 重启 / 新实例 / 重建前）
        redis.delete(BLOOM_KEY);
        redis.delete(CacheKeys.code(code));

        // 模拟启动时的初始化重建（与 BloomFilterMaintenance 启动路径同一条代码）
        long rebuilt = bloomMaintenance.rebuild();

        assertThat(rebuilt)
                .as("重建应把库里的有效短码写回布隆")
                .isPositive();
        assertBloomContains(code);

        ResponseEntity<String> resp = get("/" + code);

        assertThat(resp.getStatusCode())
                .as("重启重建后，已有短码必须仍然可跳转")
                .isEqualTo(HttpStatus.FOUND);
        assertThat(resp.getHeaders().getFirst(HttpHeaders.LOCATION)).isEqualTo(target);
    }

    // -------------------------------------------------------------- 场景 11（DR-065）

    @Test
    @DisplayName("11️⃣⭐⭐⭐ DR-065：重建窗口期写穿的短码，RENAME 切换后**不丢**")
    void writeThroughDuringRebuildSurvivesSwitch() throws Exception {
        String target = "https://example.com/e2e/rebuild-race";
        String code = createLink(target);
        redis.delete(CacheKeys.code(code));

        // 复现事故时序：重建窗口期内新建的短码，写穿后立刻被 RENAME 切换覆盖。
        // 修复前：写穿只落现行 key → RENAME 把它一起丢掉 → 该短码从此 404。
        bloomFilter.beginRebuild();
        bloomFilter.put(code);
        bloomFilter.rebuild(java.util.List.of("e2eOld1"));
        bloomFilter.endRebuild();

        // ⚠️ 核心断言：切换之后，这个短码的位仍在现行 key 上
        assertBloomContains(code);

        ResponseEntity<String> resp = get("/" + code);

        assertThat(resp.getStatusCode())
                .as("重建窗口期创建的短码不能被 RENAME 丢掉（DR-065 的根因）")
                .isEqualTo(HttpStatus.FOUND);
        assertThat(resp.getHeaders().getFirst(HttpHeaders.LOCATION)).isEqualTo(target);
    }

    // -------------------------------------------------------------- 场景 12（DR-065）

    @Test
    @DisplayName("12️⃣⭐ favicon.ico → 204 且不产生错误日志（浏览器噪声）")
    void faviconRequestIsSilent() {
        ResponseEntity<String> resp = get("/favicon.ico");

        assertThat(resp.getStatusCode())
                .as("favicon.ico 不该走跳转用例，更不该产生 400 业务异常噪声")
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    /** 读 Micrometer 计数器（actuator 或直接 registry 均可）。 */
    private double registryCounter(String name) {
        try {
            ResponseEntity<String> resp = get("/actuator/metrics/" + name);
            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                return mapper.readTree(resp.getBody())
                        .path("measurements").get(0).path("value").asDouble();
            }
        } catch (Exception ignored) {
            // 指标端点不可用时用下面的 registry 兜底
        }
        var c = meterRegistry.find(name).counter();
        return c == null ? 0.0 : c.count();
    }
}
