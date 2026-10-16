package com.linkforge.admin.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 后台鉴权配置测试（DR-005）。
 *
 * <p>方案场景 17：无 Token / 错 Token → 401，且**不执行删除**。
 * 鉴权本身的「不执行业务」由 {@code AdminController} 保证，
 * 本类验证 Token 值对象与 fail-fast 配置。
 */
class AdminAuthConfigTest {

    @Test
    @DisplayName("⭐⭐ ADMIN_API_TOKEN 未设置 → **启动即失败**，且消息点名变量")
    void missingTokenFailsFastWithExplicitMessage() {
        // ⚠️ 没配 Token 的后台等于不设防。启动成功会让风险潜伏到有人真的调用为止
        assertThatThrownBy(() -> new AdminAuthConfig(""))
                .isInstanceOf(IllegalStateException.class)
                // 消息必须写明具体变量名，运维看日志能立刻定位
                .hasMessageContaining("ADMIN_API_TOKEN")
                .hasMessageContaining("未设置");
    }

    @Test
    @DisplayName("⭐ null 也算未设置")
    void nullTokenFailsFast() {
        assertThatThrownBy(() -> new AdminAuthConfig(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_API_TOKEN");
    }

    @Test
    @DisplayName("⭐ 空白串也拒绝（trim 后为空）")
    void blankTokenFailsFast() {
        assertThatThrownBy(() -> new AdminAuthConfig("   "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_API_TOKEN");
    }

    @Test
    @DisplayName("⭐ 正常 Token → 构造成功")
    void validTokenAccepted() {
        assertThatCode(() -> new AdminAuthConfig("s3cret-token")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("⭐⭐ 场景 17：匹配的 Token → true；不匹配的 → false")
    void matchesOnlyExactToken() {
        AdminToken token = new AdminToken("s3cret-token");

        assertThat(token.matches("s3cret-token")).isTrue();
        assertThat(token.matches("s3cret-toke")).isFalse();   // 少一位
        assertThat(token.matches("s3cret-tokenn")).isFalse(); // 多一位
        assertThat(token.matches("S3CRET-TOKEN")).isFalse();  // 大小写不同
        assertThat(token.matches("")).isFalse();
        assertThat(token.matches(null)).isFalse();
    }

    @Test
    @DisplayName("⭐ toString 绝不泄露真实 Token")
    void toStringDoesNotLeak() {
        // Token 一旦进了日志就等于没设防
        assertThat(new AdminToken("super-secret-value").toString())
                .doesNotContain("super-secret-value")
                .contains("****");
    }
}