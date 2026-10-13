package com.linkforge.app;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * LinkForge 应用入口。
 *
 * <h2>⚠️ 「组件扫描」与「MyBatis Mapper 扫描」是两件独立的事</h2>
 * {@code scanBasePackages = "com.linkforge"} 只影响 **Spring 的组件扫描**（{@code @Component}
 * {@code @Service} {@code @Configuration} 等），**不会**让 MyBatis 发现 Mapper 接口。
 *
 * <p>MyBatis 的自动扫描走的是 Spring Boot 的「自动配置包」（{@code AutoConfigurationPackages}），
 * 那个值来自 {@code @SpringBootApplication} **所在类的包**，即 {@code com.linkforge.app}——
 * 与 {@code scanBasePackages} 无关。因此 {@code com.linkforge.core.infrastructure.persistence}
 * 下的 Mapper **永远不会被自动扫描到**，启动时会报：
 * <pre>
 *   No MyBatis mapper was found in '[com.linkforge.app]' package.
 *   ... required a bean of type
 *   'com.linkforge.core.infrastructure.persistence.IdSegmentMapper' that could not be found.
 * </pre>
 *
 * <p>所以必须显式声明 {@link MapperScan}。这正是本项目「Mapper 放在 core、
 * 启动类放在 app」（docs/DECISIONS.md DR-021）这一布局必须付出的代价：
 * 包路径不同源，就必须显式指定扫描范围，不能指望默认值。
 */
@SpringBootApplication(scanBasePackages = "com.linkforge")
@MapperScan("com.linkforge.core.infrastructure.persistence")
public class LinkForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(LinkForgeApplication.class, args);
    }
}
