package com.linkforge.core.domain;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 短链在**跳转视角**下的有效性状态（docs/DATABASE.md §4.2、docs/API.md §4.4）。
 *
 * <h2>为什么与 {@link LinkStatus} 分开而不是复用</h2>
 * {@link LinkStatus} 是**数据库列的取值**（{@code TINYINT}，只有 {@code ACTIVE}/{@code DELETED}），
 * 表达「这条记录被删了没有」；而跳转要回答的是「现在点它会得到什么」——
 * 后者多了一个 {@code EXPIRED}，它<b>并不是数据库里的某个状态</b>，
 * 而是「{@code status = ACTIVE} 且 {@code expire_at <= now}」这条规则的<b>推导结果</b>。
 * 若把 {@code EXPIRED} 塞进 {@link LinkStatus}，就会出现「库里有第三种状态」的错觉，
 * 诱导后续代码写出 {@code if (status == EXPIRED) ...} 这种永远不成立的分支。
 *
 * <h2>⚠️ 判定优先级是写死的，不可调换</h2>
 * <pre>
 *   status = DELETED        → DELETED（先判，<b>不看 expire_at</b>）
 *   status = ACTIVE 且已过期 → EXPIRED
 *   status = ACTIVE 且未过期 → ACTIVE
 * </pre>
 * 「已删除且已过期」必须是 {@link #DELETED}（对外 404 而非 410）：
 * 删除是<b>更强的语义</b>——调用方拼错短码与链接被删都表现为「查不到」，
 * 而 410 的价值恰恰在于告诉调用方「这个码曾经有效」，一条被主动删掉的链接不再具备这个信息。
 * 反过来先判过期会让被删除的过期链接返回 410，对外宣称「曾经有效」但其实已被撤销。
 */
public enum LinkState {

    /** 可跳转。 */
    ACTIVE,

    /** 已删除：对外表现为 404（{@code LINKFORGE-404-01}）。 */
    DELETED,

    /** 已过期：对外表现为 410（{@code LINKFORGE-410-01}）。 */
    EXPIRED;

    /**
     * 推导跳转视角的状态。
     *
     * <p>⚠️ {@code expireAt} 是<b>UTC 的 {@code LocalDateTime}</b>（DR-032），
     * 必须显式按 UTC 还原成瞬间再与 {@code now} 比较。
     * 直接用 {@code LocalDateTime.compareTo} 或与 {@code LocalDateTime.now()} 比都会错 8 小时。
     *
     * @param link 已从仓储读出的记录
     * @param now  当前时刻（由调用方注入，保证可测试）
     */
    public static LinkState resolve(Link link, Instant now) {
        if (link.status() == LinkStatus.DELETED) {
            return DELETED;
        }
        return isExpired(link.expireAt(), now) ? EXPIRED : ACTIVE;
    }

    /**
     * 过期边界判定：{@code expireAt <= now} 即为已过期。
     *
     * <p>边界取<b>等于即过期</b>（{@code <=}）而非严格小于：
     * {@code expire_at} 精度到秒（PRD §7.3），{@code expire_at == now} 意味着
     * 「在这一秒内已不再有效」，若用 {@code <} 会让这一秒的失效延迟到下一秒才生效，
     * 使「过期时间」这个对外承诺不精确。同样的口径与创建接口的
     * 「{@code expireAt} 必须<b>严格</b>将来」互补：创建要求严格大于，过期判定含等号。
     *
     * @param expireAt UTC 的过期时间，{@code null} 表示永久有效
     * @param now      当前时刻
     */
    public static boolean isExpired(LocalDateTime expireAt, Instant now) {
        if (expireAt == null) {
            return false;
        }
        return !expireAt.toInstant(ZoneOffset.UTC).isAfter(now);
    }
}
