package com.linkforge.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkforge.app.persistence.MySqlContainerSupport;
import com.linkforge.core.infrastructure.cache.CacheKeys;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 后台鉴权端到端测试（DR-064）—— **层 3：真实 HTTP + 真实过滤器链**。
 *
 * <h2>⚠️ 本类针对的是本地验收实测到的真实缺陷</h2>
 * <pre>
 *   GET /admin/api/v1/links/0000VF0   （Authorization 头是错的）
 *   → 500 {"code":"LINKFORGE-500-01","message":"internal server error"}
 * </pre>
 *
 * <h2>根因不是「Token 比对写错」，而是「鉴权的位置」</h2>
 * <ol>
 *   <li>{@code /admin/api/v1/links/{code}} 只定义了 {@code DELETE}；</li>
 *   <li>Spring 在<b>路由解析阶段</b>就抛了 {@code HttpRequestMethodNotSupportedException}；</li>
 *   <li>鉴权写在 Controller 方法体内，<b>根本没被执行到</b>；</li>
 *   <li>该异常无 handler 映射 → 掉进兜底 → <b>500</b>。</li>
 * </ol>
 *
 * <h2>为什么必须做端到端（而不能只测过滤器）</h2>
 * 过滤器的单元测试证明「判定逻辑对」，
 * 但它<b>证明不了</b>「过滤器真的在路由解析之前执行」——
 * 那需要真实 Servlet 容器里的真实链路。
 * ⚠️ 本项目历史上就栽在「只验组件、没验链路」上（DR-054）：
 * {@code ecac087} 的 5 条组件级验证全过，上线后跳转全部 500。
 *
 * <h2>断言的是 HTTP 层可见效果</h2>
 * 状态码 + 错误体里的 {@code code} 字段，不看内部方法返回值。
 *
 * <h2>数据库：一次性 MySQL 8.4 容器</h2>
 * 继承 {@link MySqlContainerSupport}，理由同 {@link RedirectEndToEndTest}：
 * 宿主机方案在干净环境跑不了，而「静默跳过」在 CI 上和「通过」长得一模一样。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("后台鉴权（真实 HTTP + 真实过滤器链，DR-064）")
class AdminAuthEndToEndTest extends MySqlContainerSupport {

    /** 本类专用短码前缀。 */
    private static final String PREFIX = "adm";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 后台 token。
     *
     * <p>⚠️ **必须用 Spring 注入，不能用 {@code System.getenv}**：
     * {@code System.getenv} 绕过了 Spring 的属性源，
     * 于是 {@code application-test.yml} 里配的固定 token 完全不生效，
     * 干净环境（CI / 新克隆）下本类全部失败（DR-054 场景 4 的同款坑）。
     */
    @org.springframework.beans.factory.annotation.Value("${ADMIN_API_TOKEN:}")
    private String adminToken;

    private HttpClient http;

    private String baseUrl;

    private final List<String> created = new ArrayList<>();

    /**
     * ⚠️ **刻意用 JDK 的 {@link HttpClient}，不用 {@code RestTemplate}**。
     *
     * <p>本类第一次写成 RestTemplate 时，**DELETE 的 401 响应体读不出来**
     * （{@code getBody()} 返回 null，而响应头里明明有 {@code Content-Length: 117}）——
     * 排查后发现是 {@code SimpleClientHttpRequestFactory} 底层
     * {@code HttpURLConnection} 在 401 响应上的鉴权处理路径把错误流吃掉了。
     *
     * <p>⚠️ 也就是说：<b>用错 HTTP 客户端，断言会失败在「读不到 body」上，
     * 而那与服务端行为无关</b> —— 很容易被误判成「服务端的 bug」，
     * 从而去改本来正确的服务端代码。
     * 这正是 DR-054「验证两层 ≠ 验证链路」的同款陷阱，只是这次发生在**客户端**。
     *
     * <p>{@link HttpClient} 的三个好处：① 对任意方法、任意状态码都能稳定读到 body；
     * ② 默认**不跟随重定向**（{@code Redirect.NEVER}），断言 302 + Location 的前提天然成立；
     * ③ JDK 自带，不引新依赖（与本项目「不引 Apache HttpClient」的选择一致）。
     */
    @BeforeEach
    void setUp() {
        this.http = HttpClient.newHttpClient();
        this.baseUrl = "http://localhost:" + port;

        assertThat(adminToken)
                .as("后台鉴权测试需要 ADMIN_API_TOKEN；未设置时本类无法运行。"
                        + "test profile 已在 application-test.yml 里配了固定值")
                .isNotBlank();
    }

    @AfterEach
    void tearDown() {
        for (String code : created) {
            jdbc.update("DELETE FROM short_link WHERE short_code = ?", code);
            redis.delete(CacheKeys.code(code));
        }
        jdbc.update("DELETE FROM short_link WHERE short_code LIKE ?", PREFIX + "%");
        created.clear();
    }

    // ==================================================================
    // 场景 1：Token 缺失 → 401
    // ==================================================================

    @Test
    @DisplayName("⭐⭐⭐ Token 缺失 → 401 + LINKFORGE-401-01（不是 500）")
    void missingTokenReturns401() throws Exception {
        String code = createLink();

        HttpResponse<String> resp = call("DELETE", "/admin/api/v1/links/" + code, null);

        assertThat(resp.statusCode())
                .as("Token 缺失必须 401。实际 %s，body=%s", resp.statusCode(), resp.body())
                .isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(errorCode(resp)).isEqualTo("LINKFORGE-401-01");
        // ⚠️ 关键：短链必须**还在** —— 鉴权失败绝不能碰数据
        assertThat(linkStillExists(code)).isTrue();
    }

    // ==================================================================
    // 场景 2：Token 格式错误（没有 Bearer 前缀）→ 401
    // ==================================================================

    @Test
    @DisplayName("⭐⭐⭐ Token 格式错误（没有 Bearer 前缀）→ 401")
    void malformedTokenReturns401() throws Exception {
        String code = createLink();

        HttpResponse<String> resp = call("DELETE", "/admin/api/v1/links/" + code, adminToken);

        assertThat(resp.statusCode())
                .as("缺 Bearer 前缀必须 401。实际 %s，body=%s", resp.statusCode(), resp.body())
                .isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(errorCode(resp)).isEqualTo("LINKFORGE-401-01");
        assertThat(linkStillExists(code)).isTrue();
    }

    // ==================================================================
    // 场景 3：Token 内容不匹配（乱码）→ 401
    // ==================================================================

    @Test
    @DisplayName("⭐⭐⭐ Token 内容不匹配（乱码 / 中文）→ 401")
    void mismatchedTokenReturns401() throws Exception {
        String code = createLink();

        // ⚠️ 同上：非 ASCII 头值由单元测试覆盖，这里只用 ASCII 变体
        for (String garbage : List.of(
                "Bearer wrong-token",
                "Bearer ???",
                "Bearer ",
                "Bearer " + adminToken + "x")) {
            HttpResponse<String> resp = call("DELETE", "/admin/api/v1/links/" + code, garbage);

            assertThat(resp.statusCode())
                    .as("Token「%s」必须 401。实际 %s，body=%s", garbage, resp.statusCode(), resp.body())
                    .isEqualTo(HttpStatus.UNAUTHORIZED.value());
            assertThat(errorCode(resp)).isEqualTo("LINKFORGE-401-01");
        }
        assertThat(linkStillExists(code)).isTrue();
    }

    // ==================================================================
    // 场景 4：⭐ 原始缺陷 —— GET（方法不被支持）+ 错误 Token → 401 而非 500
    // ==================================================================

    @Test
    @DisplayName("⭐⭐⭐⭐ 原始缺陷：GET 后台接口 + 错误 Token → 401（修复前是 500）")
    void getWithInvalidTokenReturns401Not500() throws Exception {
        String code = createLink();

        // ⚠️ 只用 ASCII 乱码：JDK 的 HttpClient 会**拒绝非 ASCII 的请求头值**
        // （"invalid header value"）—— 那是客户端的限制，不是服务端的。
        // 非 ASCII 头的服务端行为由 AdminAuthFilterTest（直接构造 request）覆盖。
        for (String garbage : List.of("Bearer garbage-token", "Bearer wrong-token", adminToken)) {
            HttpResponse<String> resp = call("GET", "/admin/api/v1/links/" + code, garbage);

            assertThat(resp.statusCode())
                    .as("GET + 错误 Token「%s」必须 401。修复前是 500。实际 %s，body=%s",
                            garbage, resp.statusCode(), resp.body())
                    .isEqualTo(HttpStatus.UNAUTHORIZED.value());
            assertThat(errorCode(resp)).isEqualTo("LINKFORGE-401-01");
        }
    }

    @Test
    @DisplayName("⭐⭐⭐⭐ GET 后台接口 + **无** Token → 401（修复前是 500）")
    void getWithoutTokenReturns401Not500() throws Exception {
        String code = createLink();

        HttpResponse<String> resp = call("GET", "/admin/api/v1/links/" + code, null);

        assertThat(resp.statusCode())
                .as("GET 无 Token 必须 401。修复前是 500。实际 %s，body=%s", resp.statusCode(), resp.body())
                .isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(errorCode(resp)).isEqualTo("LINKFORGE-401-01");
    }

    // ==================================================================
    // 场景 5：Token 正确但方法不对 → 405（不是 500）
    // ==================================================================

    @Test
    @DisplayName("⭐⭐⭐ Token 正确但方法不对（GET）→ 405 + LINKFORGE-405-01（不是 500）")
    void validTokenWithWrongMethodReturns405() throws Exception {
        String code = createLink();

        HttpResponse<String> resp = call("GET", "/admin/api/v1/links/" + code, "Bearer " + adminToken);

        assertThat(resp.statusCode())
                .as("方法不对必须 405。实际 %s，body=%s", resp.statusCode(), resp.body())
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED.value());
        assertThat(errorCode(resp)).isEqualTo("LINKFORGE-405-01");
        // ⚠️ RFC 7231：405 必须带 Allow 头
        assertThat(resp.headers().firstValue("Allow")).contains("DELETE");
    }

    // ==================================================================
    // 场景 6：正确 Token → 放行并真的删除
    // ==================================================================

    @Test
    @DisplayName("⭐⭐ 正确 Token → 放行，删除成功")
    void validTokenDeletesLink() throws Exception {
        String code = createLink();
        assertThat(linkStillExists(code)).isTrue();

        HttpResponse<String> resp = call("DELETE", "/admin/api/v1/links/" + code, "Bearer " + adminToken);

        assertThat(resp.statusCode())
                .as("正确 Token 必须放行。实际 %s，body=%s", resp.statusCode(), resp.body())
                .isEqualTo(HttpStatus.OK.value());
        // ⚠️ 软删除：status 变 1，记录仍在
        Integer status = jdbc.queryForObject(
                "SELECT status FROM short_link WHERE short_code = ?", Integer.class, code);
        assertThat(status).isEqualTo(1);
    }

    // ==================================================================
    // 场景 7：业务接口不受影响（过滤器只管 /admin/*）
    // ==================================================================

    @Test
    @DisplayName("⭐ 过滤器只管 /admin/*：业务接口不带 Token 仍正常")
    void businessApiIsNotAffected() throws Exception {
        String code = createLink();

        HttpResponse<String> resp = call("GET", "/" + code, null);

        assertThat(resp.statusCode())
                .as("跳转接口不带 Token 必须照常 302")
                .isEqualTo(HttpStatus.FOUND.value());
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /** 发一个不带 body 的请求（GET / DELETE）。 */
    private HttpResponse<String> call(String method, String path, String rawAuthHeader) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path));
        if (rawAuthHeader != null) {
            builder.header("Authorization", rawAuthHeader);
        }
        return http.send(builder.method(method, HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String errorCode(HttpResponse<String> resp) throws Exception {
        assertThat(resp.body())
                .as("响应体不能为空（状态码 %s）", resp.statusCode())
                .isNotBlank();
        JsonNode json = mapper.readTree(resp.body());
        return json.path("code").asText();
    }

    /** 短码是否仍然有效（status=0）。 */
    private boolean linkStillExists(String code) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM short_link WHERE short_code = ? AND status = 0", Integer.class, code);
        return count != null && count > 0;
    }

    /**
     * 造一条待删除的短链，**直接写库**（不走创建接口）。
     *
     * <h2>⚠️ 为什么不用 {@code POST /api/v1/links}</h2>
     * 创建接口有 **10 次/分钟** 的 IP 限流（{@code RATE_LIMIT_CREATE_PER_MIN}）。
     * 本类有 8 个用例，若每个都调一次创建接口，
     * 再加上同一次构建里另一个 E2E 类（{@code RedirectEndToEndTest}）的创建请求，
     * **总量会超过 10/分钟** → 后跑的类全部拿到 **429**。
     *
     * <p>⚠️ 这是一个**测试之间的隐性耦合**：限流按 IP 计，
     * 而所有测试都来自 {@code 127.0.0.1}，于是**配额是共享的**。
     * 本类的被测对象是**鉴权**，不是创建 —— 所以直接写库，
     * 把创建接口的配额留给需要它的测试。
     *
     * <p>（创建 → 跳转这条链路由 {@code RedirectEndToEndTest} 覆盖。）
     */
    private String createLink() {
        String customCode = PREFIX + java.util.UUID.randomUUID().toString()
                .replace("-", "").substring(0, 4);
        jdbc.update("INSERT INTO short_link (short_code, target_url, status, is_custom, expire_at) "
                        + "VALUES (?, ?, 0, 1, NULL)",
                customCode, "https://example.com/" + customCode);
        created.add(customCode);
        // ⚠️ 必须写全部 7 个位：只设第 0 位的话布隆查另外 6 位会判否，
        // 于是刚创建的短码立刻 404 —— 那是测试自己造的假故障。
        int[] bits = new int[7];
        com.linkforge.core.infrastructure.cache.BloomHash.bitIndexes(customCode, bits);
        for (int bit : bits) {
            redis.opsForValue().setBit("shortlink:bloom", bit, true);
        }
        return customCode;
    }
}
