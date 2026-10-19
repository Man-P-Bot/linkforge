package com.linkforge.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * <h2>数据隔离</h2>
 * 每个 {@code @BeforeEach} 造自己的短码（带唯一前缀），
 * {@code @AfterEach} 按前缀清理。<b>刻意不用 TRUNCATE</b>——
 * 那会破坏 Flyway 建表后建立的基线，并让后续测试无法运行。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("跳转全链路（真实 Redis + 真实 HTTP，DR-054 层 3）")
class RedirectEndToEndTest {

    /** 本类专用短码前缀，与其它测试不冲突。 */
    private static final String PREFIX = "e2e";

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

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    private final List<String> created = new ArrayList<>();
    private String adminToken;

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

        adminToken = System.getenv("ADMIN_API_TOKEN");
        assertThat(adminToken)
                .as("后台删除接口需要 ADMIN_API_TOKEN；未设置时本类的场景 4 无法运行")
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
        if (!Boolean.TRUE.equals(redis.hasKey("shortlink:bloom"))) {
            redis.opsForValue().setBit("shortlink:bloom", 1, true);
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
        // ⚠️ 必须写**全部 k 个位**：只设第0 位的话，布隆查另外 6 位时会判否，
        // 于是刚创建的短码立刻 404——这是测试自己造的假故障。
        for (int bit : bitsOf(code)) {
            redis.opsForValue().setBit("shortlink:bloom", bit, true);
        }
        return code;
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
        redis.delete("shortlink:bloom");

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
            redis.opsForValue().setBit("shortlink:bloom", bit, false);
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
