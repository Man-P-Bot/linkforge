package com.linkforge.app.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 把 {@code docs/DECISIONS.md} DR-018 的模块依赖规则变成**可执行的测试**。
 *
 * <p>规则：
 * <pre>
 *   redirect → admin   ❌ 禁止
 *   admin    → redirect ❌ 禁止（隔离必须双向）
 *   stats    → redirect / admin ❌ 禁止
 * </pre>
 *
 * <p>为什么还需要这个测试：Maven 模块依赖已经能从编译期拦住「直接依赖」，
 * 但拦不住两件事：
 * <ol>
 *   <li>有人为了图省事，在 pom 里加一条 {@code redirect -> admin} 的依赖；</li>
 *   <li>模块被合并或重构后，包与模块不再一一对应，隔离名存实亡。</li>
 * </ol>
 * 这个测试的作用是让违规**在 CI/构建阶段就失败**，而不是等到代码评审。
 *
 * <p>注意：本测试在 {@code linkforge-app} 中，因为只有它的 classpath 同时包含全部模块。
 */
class ModuleBoundaryArchTest {

    /**
     * 只导入各模块的**生产代码**，排除测试类与 Spring Boot 打包产物。
     * 包名与模块一一对应：{@code com.linkforge.core} 即 {@code linkforge-core}。
     */
    private static JavaClasses productionClasses;

    @BeforeAll
    static void importClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.linkforge");
    }

    @Test
    void redirect_must_not_depend_on_admin() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.linkforge.redirect..")
                .should().dependOnClassesThat().resideInAPackage("com.linkforge.admin..")
                .because("跳转链路必须与后台物理隔离（docs/DECISIONS.md DR-009 / DR-018）");

        rule.check(productionClasses);
    }

    @Test
    void admin_must_not_depend_on_redirect() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.linkforge.admin..")
                .should().dependOnClassesThat().resideInAPackage("com.linkforge.redirect..")
                .because("隔离必须双向：admin 不得把跳转链路的缓存/限流实现拉进后台"
                        + "（docs/DECISIONS.md DR-018）");

        rule.check(productionClasses);
    }

    @Test
    void stats_must_not_depend_on_redirect_or_admin() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.linkforge.stats..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("com.linkforge.redirect..", "com.linkforge.admin..")
                .because("stats 只允许依赖 core（docs/DECISIONS.md DR-018）");

        rule.check(productionClasses);
    }
}
