package com.linkforge.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * LinkForge 应用入口。
 *
 * <p>包扫描根为 {@code com.linkforge}，因此各模块（core / redirect / stats / admin）的组件
 * 只要位于该包路径下即可被发现，无需在每个模块里额外声明配置类。
 */
@SpringBootApplication(scanBasePackages = "com.linkforge")
public class LinkForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(LinkForgeApplication.class, args);
    }
}
