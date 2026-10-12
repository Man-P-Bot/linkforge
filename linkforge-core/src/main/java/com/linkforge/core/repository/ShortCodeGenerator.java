package com.linkforge.core.repository;

/**
 * 短码发号器（docs/DECISIONS.md DR-011）。
 *
 * <p>语义：每次调用返回一个**从未发出过的**、定长 7 位的 Base62 短码。
 * 唯一性由「序号单调唯一」保证，而不是由随机碰撞概率保证——这是本方案相对
 * 「随机生成 + 冲突重试」的核心优势。
 *
 * <p>实现有两个，共用 {@link com.linkforge.core.domain.IdSpace} 定义的同一号空间：
 * <ul>
 *   <li>主路径：Redis {@code INCR}（区间 {@code [100000, 1_000_000_000)}）</li>
 *   <li>降级路径：数据库号段（区间 {@code [1_000_000_000, 62^7)}）</li>
 * </ul>
 * 两区间不重叠，因此降级期间不会发出与主路径重复的号，也不需要跨存储同步。
 */
@FunctionalInterface
public interface ShortCodeGenerator {

    /**
     * 分配一个短码。
     *
     * @return 定长 7 位的 Base62 短码
     * @throws ShortCodeGenerationException 发号失败（如主路径与降级路径均不可用）
     */
    String nextShortCode();

    /**
     * 发号失败。刻意用受检语义明确的运行时异常，便于上层区分
     * 「发号器不可用」（应返回 503）与「参数错误」（应返回 400）。
     */
    class ShortCodeGenerationException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public ShortCodeGenerationException(String message, Throwable cause) {
            super(message, cause);
        }

        public ShortCodeGenerationException(String message) {
            super(message);
        }
    }
}
