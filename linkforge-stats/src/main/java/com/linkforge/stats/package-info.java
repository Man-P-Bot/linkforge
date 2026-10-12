/**
 * LinkForge 统计模块。
 *
 * <p>职责：StatsService、定时落库、HyperLogLog UV 估算。
 * <p>依赖约束：只依赖 core，禁止依赖 redirect 与 admin（见 DR-018）。
 */
package com.linkforge.stats;
