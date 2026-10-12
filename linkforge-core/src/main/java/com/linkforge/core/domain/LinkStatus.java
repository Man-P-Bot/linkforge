package com.linkforge.core.domain;

/**
 * 短链状态（对应 {@code short_link.status} 的 {@code TINYINT} 取值）。
 *
 * <p>为什么单独定义枚举而不是散落 {@code 0}/{@code 1} 魔法值：失效判定优先级是写死的
 * （docs/DATABASE.md §4.2）——**先看 {@code status}，DELETED 直接 404，不看 {@code expire_at}**。
 * 一旦某处手写 {@code == 1} 而另一处写成 {@code == 0} 取反，就会让「已删除且已过期」的短链
 * 在不同路径上返回不同状态码（一处 404、一处 410），对外行为不再确定。
 *
 * <p>⚠️ 数据库存的是 {@code TINYINT}，不是字符串。枚举与 {@code TINYINT} 的转换需要 MyBatis
 * TypeHandler（默认的 {@code EnumTypeHandler} 按 {@code name()} 存字符串，与本表结构不匹配）。
 * 该 TypeHandler 属于数据访问实现，随 Mapper 落地时一并提供。
 */
public enum LinkStatus {

    /** 正常有效，且未过期。 */
    ACTIVE(0),

    /** 已主动删除。删除是更强的语义，优先级高于过期。 */
    DELETED(1);

    private final int code;

    LinkStatus(int code) {
        this.code = code;
    }

    /** 数据库中的取值。 */
    public int code() {
        return code;
    }

    /**
     * 由数据库取值反解枚举。
     *
     * @throws IllegalArgumentException 取值不在已知范围内——宁可启动即失败，也不要静默当成 ACTIVE
     *                                  （静默降级会让已删除的短链重新可跳转）
     */
    public static LinkStatus fromCode(int code) {
        for (LinkStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的短链状态取值: " + code);
    }
}
