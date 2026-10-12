/**
 * LinkForge 跳转链路模块。
 *
 * <p>职责：短链跳转、缓存读写、布隆过滤、限流。
 * <p>依赖约束：只依赖 core 与 stats，禁止依赖 admin（见 DR-018）。
 */
package com.linkforge.redirect;
