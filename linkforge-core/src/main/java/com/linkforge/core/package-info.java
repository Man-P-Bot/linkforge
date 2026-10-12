/**
 * LinkForge 共享抽象层。
 *
 * <p>职责：Link 实体、LinkRepository 接口、ShortCodeGenerator、CacheService 接口、共享持久化访问（MyBatis Mapper）。
 * <p>约束：可以装持久化访问，但禁止装 Web/缓存/限流/布隆的实现（见 DR-021）。
 */
package com.linkforge.core;
