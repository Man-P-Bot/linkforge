package com.linkforge.core.domain;

/**
 * Base62 编解码（docs/DECISIONS.md DR-011）。
 *
 * <h2>为什么自己写表而不用 {@code Long.toString(n, 62)}</h2>
 * JDK 的 {@code toString(long, int)} 在基数为 62 时使用 {@code 0-9a-zA-Z} 顺序
 * （先小写后大写，取自 {@code Character.forDigit} 的内部表）。
 * 本项目的编码表是 {@code 0-9A-Za-z}（先大写后小写）。
 * 两者对同一序号会产出**不同的字符串**，例如序号 10：
 * <ul>
 *   <li>本实现的表：{@code "A"}</li>
 *   <li>{@code Long.toString(10, 62)}：{@code "a"}</li>
 * </ul>
 * 若用 JDK 的实现，单元测试的期望值、缓存里已写入的短码、压测脚本里的示例 URL
 * 会与实际发出的短码不一致，且这种不一致**只在序号 ≥ 10 时才暴露**，极难排查。
 * 因此编码表显式写死在本类中。
 *
 * <p>编码表字符顺序（索引 → 字符）：
 * <pre>
 *   0..9    -> '0'..'9'
 *   10..35  -> 'A'..'Z'
 *   36..61  -> 'a'..'z'
 * </pre>
 *
 * <p><b>刻意不排除易混淆字符</b>（不排除 0/O、1/l/I）：短码靠复制粘贴与点击传播，
 * 不靠人工手抄；而排除字符会让基数不再是 62，7 位空间 {@code 62^7} 的容量核算
 * （PRD §7.1 的 3.52 万亿）随之失效，位数也不再固定。
 */
public final class Base62 {

    /** 编码表：索引即数值，字符即该位的表示。顺序为 0-9, A-Z, a-z。 */
    private static final char[] ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    /** 反查表：字符 → 数值，用于解码与测试的双射性校验。 */
    private static final int[] LOOKUP = new int[128];

    static {
        // 初始化为 -1，表示「不是合法 Base62 字符」
        for (int i = 0; i < LOOKUP.length; i++) {
            LOOKUP[i] = -1;
        }
        for (int i = 0; i < ALPHABET.length; i++) {
            LOOKUP[ALPHABET[i]] = i;
        }
    }

    private Base62() {
    }

    /**
     * 把非负序号编码为 Base62 字符串（**不补零**，最短表示）。
     *
     * @throws IllegalArgumentException 序号为负或超出 {@link IdSpace#MAX_SEQUENCE}
     */
    public static String encode(long sequence) {
        IdSpace.requireInRange(sequence);
        if (sequence == 0) {
            return "0";
        }
        // 最长 7 位（62^7 - 1 编码为 "zzzzzzz"），留一点余量即可
        char[] buffer = new char[IdSpace.SHORT_CODE_LENGTH + 1];
        int position = buffer.length;
        long remaining = sequence;
        while (remaining > 0) {
            int digit = (int) (remaining % IdSpace.BASE);
            buffer[--position] = ALPHABET[digit];
            remaining /= IdSpace.BASE;
        }
        return new String(buffer, position, buffer.length - position);
    }

    /**
     * 把序号编码为**定长**短码：左侧补 {@code '0'} 到 {@link IdSpace#SHORT_CODE_LENGTH} 位。
     *
     * <p>补零字符用 {@code '0'} 而不是另设填充符：{@code '0'} 本身就是 Base62 表里数值 0 的字符，
     * 语义自然，且解码时无需特殊处理。
     *
     * @throws IllegalStateException 编码结果已超过定长（理论上不可达，因为 {@link IdSpace#requireInRange}
     *         已挡住越界序号）——一旦触发说明发号器或常量被改坏，属严重问题，必须显式失败并告警
     */
    public static String encodePadded(long sequence) {
        String encoded = encode(sequence);
        if (encoded.length() > IdSpace.SHORT_CODE_LENGTH) {
            throw new IllegalStateException(
                    "序号 " + sequence + " 编码为 " + encoded.length() + " 位，超过定长 "
                            + IdSpace.SHORT_CODE_LENGTH + " 位；发号区间常量可能已被改坏");
        }
        StringBuilder padded = new StringBuilder(IdSpace.SHORT_CODE_LENGTH);
        for (int i = encoded.length(); i < IdSpace.SHORT_CODE_LENGTH; i++) {
            padded.append('0');
        }
        padded.append(encoded);
        return padded.toString();
    }

    /**
     * 解码 Base62 字符串为序号。
     *
     * @throws IllegalArgumentException 含非法字符，或数值超出 {@link IdSpace#MAX_SEQUENCE}
     */
    public static long decode(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("待解码的 Base62 字符串不能为空");
        }
        long result = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            int digit = c < LOOKUP.length ? LOOKUP[c] : -1;
            if (digit < 0) {
                throw new IllegalArgumentException("非法 Base62 字符: '" + c + "'（位置 " + i + "）");
            }
            result = Math.addExact(Math.multiplyExact(result, IdSpace.BASE), digit);
        }
        IdSpace.requireInRange(result);
        return result;
    }

    /** 判断字符串是否只由合法 Base62 字符组成（不做长度与范围校验）。 */
    public static boolean isBase62(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= LOOKUP.length || LOOKUP[c] < 0) {
                return false;
            }
        }
        return true;
    }

    /** 编码表副本，仅供测试与文档使用。 */
    public static char[] alphabet() {
        return ALPHABET.clone();
    }
}
