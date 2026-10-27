package com.linkforge.redirect.infrastructure.cache;

import com.linkforge.core.infrastructure.cache.BloomFilter;
import com.linkforge.core.infrastructure.cache.BloomHash;
import com.linkforge.core.infrastructure.cache.CacheKeys;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis 布隆过滤器（docs/ARCHITECTURE.md §7.1，DR-007）。
 *
 * <h2>规格</h2>
 * n=1000万 / p=0.01 / m=95,850,584 位 ≈ 11.43 MiB≈ 12 MB / k=7。
 * 推导见 ARCHITECTURE.md §7.1「规格推导」。
 *
 * <h2>⚠️ 为什么用 Lua 而不是 pipelining（ARCHITECTURE.md §147 要求）</h2>
 * pipelining 是「一次网络发送、多次响应」，客户端仍要等两次响应才能决定下一步；
 * Lua 在 Redis 服务端执行完再返回，才能真正做到「按布隆结果分支」，
 * 把「布隆 + 缓存查询」压成<b>一次网络往返</b>。
 *
 * <p>⚠️ <b>bit 下标在 Java 侧算好再传进 Lua</b>，不在 Lua 里算。
 * Lua 里算哈希需要 {@code redis.sha1hex} 这类命令，每个短码要多几次调用，
 * 反而更慢；Java 侧算好还能复用。
 *
 * <h2>⚠️⚠️ 重建窗口期：写穿必须同时落到现行 key 与 {@code :building}（DR-065）</h2>
 * 双缓冲只解决了「重建期间现行 key 不被删」，<b>没解决「重建期间的写穿被 RENAME 吃掉」</b>：
 * <pre>
 *   T0  重建开始（扫描库）
 *   T1  新短码 X 提交 → 写穿把 X 的位写进**现行 key**
 *   T2  重建扫描结束（快照里没有 X——它在 T1 之后才落库）
 *   T3  RENAME :building → 现行 key     ← X 的位随旧 key 一起被丢弃
 *   T4  访问 X → 布隆判「一定不存在」 → **404**
 * </pre>
 * 实测事故：短码 {@code 0000VF1} 在重建窗口期内创建，重建完成后一直 404，
 * 直到下一次全量重建才自愈（docs/DECISIONS.md DR-065）。
 *
 * <p><b>修法</b>：重建期间（{@link #beginRebuild()} ~ {@link #endRebuild()}）的写穿
 * <b>同时写现行 key 与 {@code :building}</b>，于是 RENAME 切换后 X 依然在位。
 *
 * <p>⚠️ <b>两个动作的先后顺序不能颠倒</b>：
 * <ol>
 *   <li>{@link #beginRebuild()} 必须<b>早于数据库扫描</b>开启窗口，
 *       否则「窗口期外创建的短码」既不在快照里、也没被写穿 → 404；</li>
 *   <li>{@code :building} 的删除也必须<b>早于数据库扫描</b>，
 *       否则扫描期间写穿到 {@code :building} 的位会被随后的 DEL 抹掉 → 404。</li>
 * </ol>
 * 两者都由 {@code beginRebuild()} 一次完成，调用方（{@code BloomFilterMaintenance}）
 * 只要保证「先 beginRebuild，再扫描」即可。
 *
 * <p>⚠️ 窗口标志是<b>进程内</b>的（{@link AtomicBoolean}）：本项目是单实例部署
 * （docs/ARCHITECTURE.md §12），多实例时需要把标志放进 Redis。
 */
@Component
public class RedisBloomFilter implements BloomFilter {

    private static final Logger log = LoggerFactory.getLogger(RedisBloomFilter.class);

    /**
     * 布隆检查 + 缓存查询合并脚本。
     *
     * <p>返回：
     * <ul>
     *   <li>{@code {0, nil}} —— 一定不存在（布隆判否），<b>调用方应直接 404</b></li>
     *   <li>{@code {1, value}} —— 可能存在且缓存有值</li>
     *   <li>{@code {1, false}} —— 可能存在但缓存无值，需回源</li>
     * </ul>
     *
     * <p>⚠️ 布隆 key 不存在时（首次部署前或重建期间）返回 {@code {1, false}}——
     * <b>放行</b>。若返回「不存在」，第一次部署后所有请求都会404。
     */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> CHECK_AND_GET = new DefaultRedisScript<>("""
            local bloomExists = redis.call('EXISTS', KEYS[1])
            if bloomExists == 0 then
              -- 布隆未初始化：放行，让它继续查缓存/回源。
              -- 首次部署或重建窗口期都走这里，绝不能返回「不存在」。
              return {1, false}
            end

            -- ARGV 全部是 bit 下标，**没有占位符**。
            -- 曾用 'for i = 2' 配合一个占位元素，结果只检查了 k-1 = 6 个位：
            -- 少查一位不会崩溃，Lua 也不报错——它只是静默地少判一位。
            -- ⚠️ 真实危害是**k 与设计规格不符**（文档 7、代码 6），
            --    不是误判率恶化：n=1000万/p=0.01 时 k=7 是 1.0039%、k=6 是 1.0143%，
            --    差异仅 0.01 个百分点。详见 DR-054。
            for i = 1, #ARGV do
              if redis.call('GETBIT', KEYS[1], ARGV[i]) == 0 then
                return {0, false}
              end
            end

            -- 7 位全为 1：可能存在，查 L2
            local cached = redis.call('GET', KEYS[2])
            if cached then
              return {1, cached}
            end
            return {1, false}
            """, List.class);

    private final StringRedisTemplate redis;

    /**
     * 重建专用的模板（更长的超时预算）。
     *
     * <p>⚠️ 跳转路径的 Redis 超时是 50ms（DR-011），那是**跳转命令**的延迟预算；
     * 而重建是批量写（几千个短码 × 7 个位），用同一个模板必然超时。
     * 见 {@link com.linkforge.redirect.config.BloomMaintenanceRedisConfig}。
     */
    private final StringRedisTemplate maintenanceRedis;

    private final Counter blockedCounter;
    private final Counter putFailedCounter;

    /**
     * 重建窗口期标志（DR-065）。
     *
     * <p>为 {@code true} 时，{@link #put(String)} 会把短码<b>同时</b>写进现行 key
     * 与 {@code :building}，保证 RENAME 原子切换后不会丢。
     */
    private final AtomicBoolean rebuilding = new AtomicBoolean(false);

    /**
     * 重建时单批 pipeline 的短码数。
     *
     * <p>⚠️ 为什么必须批量：逐个短码 7 次 SETBIT 往返时，
     * 实测 <b>2 万条短码要 28 秒</b>（每条 7 次 RTT）。重建窗口长达半分钟，
     * 期间新建的短码全都暴露在「写穿可能被 RENAME 吃掉」的风险里（DR-065）——
     * <b>重建越慢，风险窗口越长</b>。批量 pipeline 后同样的 2 万条约 1 秒。
     *
     * <p>⚠️ 取 500 而不是更大：单批的**全部回复**必须在超时预算内返回，
     * 批太大反而会撞上超时（实测 2000 条/批会超时）。500 条 = 3500 个命令，
     * 在 5 秒的维护预算下绰绰有余。
     */
    private static final int PIPELINE_BATCH_CODES = 500;

    public RedisBloomFilter(StringRedisTemplate redis, MeterRegistry meterRegistry) {
        this(redis, redis, meterRegistry);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RedisBloomFilter(StringRedisTemplate redis,
                            @org.springframework.beans.factory.annotation.Qualifier(
                                    "bloomMaintenanceRedisTemplate") StringRedisTemplate maintenanceRedis,
                            MeterRegistry meterRegistry) {
        this.redis = redis;
        this.maintenanceRedis = maintenanceRedis;
        this.blockedCounter = Counter.builder("shortlink.bloom.blocked")
                .description("被布隆过滤器挡掉（判定为一定不存在）的请求数")
                .register(meterRegistry);
        this.putFailedCounter = Counter.builder("shortlink.bloom.put.failed")
                .description("布隆写穿失败次数。失败不阻塞创建，"
                        + "后果是该新短码下次会被布隆挡掉（404）直到下次重建自愈")
                .register(meterRegistry);
    }

    @Override
    public boolean mightContain(String shortCode) {
        try {
            if (!Boolean.TRUE.equals(redis.hasKey(BloomHash.PREFIX))) {
                // 未初始化 → 放行。首次部署前若判「不存在」，所有请求都会 404
                return true;
            }
            int[] bits = new int[BloomHash.HASH_COUNT];
            BloomHash.bitIndexes(shortCode, bits);
            for (int bit : bits) {
                if (!Boolean.TRUE.equals(redis.opsForValue().getBit(BloomHash.PREFIX, bit))) {
                    // 任一位为 0 → 一定不存在。这是唯一可以放心 404 的情况
                    blockedCounter.increment();
                    return false;
                }
            }
            return true;
        } catch (DataAccessException e) {
            // ⚠️ 布隆不可用 → 放行。降级查库，服务可用（ARCHITECTURE.md §384）
            log.warn("布隆检查失败，跳过（降级查库）: code={} {}", shortCode, e.getMessage());
            return true;
        }
    }

    @Override
    public boolean isReady() {
        try {
            return redis.hasKey(BloomHash.PREFIX);
        } catch (DataAccessException e) {
            return false;
        }
    }

    @Override
    public void put(String shortCode) {
        try {
            int[] bits = new int[BloomHash.HASH_COUNT];
            BloomHash.bitIndexes(shortCode, bits);
            setBits(BloomHash.PREFIX, bits);
            if (rebuilding.get()) {
                // ⚠️⚠️ 重建窗口期（DR-065）：**必须同时**写到 :building。
                //    只写现行 key 的话，RENAME 原子切换会把这次写穿一起丢掉，
                //    新建的短码从此 404，直到下一次全量重建才自愈。
                setBits(buildingKey(), bits);
            }
        } catch (DataAccessException e) {
            // ⚠️ 失败只记日志 + 计数，**绝不抛出**（ARCHITECTURE.md §259）
            // 布隆是优化不是正确性来源，写失败就回滚创建会让它变成单点依赖
            putFailedCounter.increment();
            log.warn("布隆写穿失败（不阻塞创建，重建后自愈）: code={} {}", shortCode, e.getMessage());
        } catch (RuntimeException e) {
            // ⚠️ 同 checkAndGet 的教训（DR-054）：只 catch DataAccessException 会让
            //    非 DataAccess 的 RuntimeException（序列化/类型转换类）穿透到调用方。
            //    布隆是**保护措施**，它自己出错不该让创建接口挂掉。
            putFailedCounter.increment();
            log.warn("布隆写穿异常（不阻塞创建，重建后自愈）: code={} {}", shortCode, e.toString());
        }
    }

    /**
     * 写 k 个位（逐位 SETBIT）。
     *
     * <p>⚠️ 单条写穿刻意<b>不</b>做 pipeline：写穿在创建接口的关键路径上，
     * 但创建是低频操作（QPS 个位数），7 次 RTT 的绝对开销可以忽略；
     * 而 pipeline 的收益在<b>重建</b>路径上（那里一次要写几十万位），
     * 见 {@link #writeBitsBatched(String, List)}。
     */
    private void setBits(String key, int[] bits) {
        for (int bit : bits) {
            redis.opsForValue().setBit(key, bit, true);
        }
    }

    /**
     * 布隆 + 缓存的合并查询（DR-007 要求的一次往返）。
     *
     * @return {@code BloomCheckResult}
     */
    public BloomCheckResult checkAndGet(String shortCode) {
        int[] bits = new int[BloomHash.HASH_COUNT];
        BloomHash.bitIndexes(shortCode, bits);

        List<Object> raw;
        try {
            raw = redis.execute(CHECK_AND_GET, List.of(BloomHash.PREFIX, CacheKeys.code(shortCode)),
                    toArgs(bits));
        } catch (RuntimeException e) {
            // ⚠️ 捕获 RuntimeException 而非 DataAccessException：
            // 参数序列化/类型转换类异常（如 ClassCastException）不是 DataAccessException，
            // 只 catch DataAccessException 会让降级兜底失效，异常穿透到 Controller → 500。
            // 布隆是**保护措施**，它自己出错不该让跳转挂掉（DR-054）。
            log.warn("布隆合并查询失败，降级为回源: code={} {}", shortCode, e.toString(), e);
            return BloomCheckResult.source();
        }

        // ⚠️ 形态校验先行：Lua 某个分支不带第二项、或是完全未知的返回类型时，
        // 直接 r.get(0)/r.get(1) 会抛 IndexOutOfBounds / ClassCastException。
        // 布隆是**保护措施**，它自己出问题不该让跳转挂掉——降级为「需回源」（DR-054）。
        if (raw == null || raw.isEmpty() || !(raw.get(0) instanceof Number flagNum)) {
            log.warn("布隆合并查询返回形态异常，降级为回源: raw={} code={}",
                    raw == null ? "null" : raw.getClass().getName(), shortCode);
            return BloomCheckResult.source();
        }
        List<?> r = raw;
        int verdict = flagNum.intValue();
        // ⚠️ 元素 1 可能缺失（脚本某分支没带第二项），不能直接 r.get(1)
        Object value = r.size() > 1 ? r.get(1) : null;
        if (verdict == 0) {
            blockedCounter.increment();
            return BloomCheckResult.absent();
        }
        Object cached = value;
        if (cached instanceof byte[] bytes) {
            cached = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        if (cached instanceof String s) {
            return BloomCheckResult.cached(s);
        }
        // ⚠️ 走到这里说明：verdict==1 且第二项既不是 String/byte[] 也不是 null。
        //    null 是 Lua 的正常「无值」（{1, false} / {1, nil}），**不是异常**；
        //    其它类型（实测遇到过 ArrayList）才是真异常，必须留 WARN——
        //    否则「静默降级」会让人以为布在工作，而它其实在持续返回错误结果。
        if (cached != null) {
            log.warn("布隆合并查询返回形态异常（第二项类型未知），降级为回源: "
                            + "type={} code={}",
                    cached.getClass().getName(), shortCode);
        }
        return BloomCheckResult.source();
    }

    /**
     * 双缓冲重建（DR-007）。
     *
     * <p>⚠️ <b>核心约束：任何时候都不删除现行 key</b>。
     * 流程是「写 :building → 填完 → RENAME 原子切换」。
     * 重建期间请求读的是现行 key，完全不受影响；
     * 若先 DEL 现行key，从 DEL 到 RENAME 之间所有真实短码都会被判「不存在」→ 404。
     *
     * <h2>⚠️ 调用顺序（DR-065）</h2>
     * <pre>
     *   bloomFilter.beginRebuild();   // ① 删 :building + 打开写穿窗口（必须早于扫描）
     *   List<String> all = 扫描数据库();  // ② 扫描
     *   bloomFilter.rebuild(all);      // ③ 写位 + RENAME 原子切换
     *   bloomFilter.endRebuild();     // ④ 关窗口 + 清理残留
     * </pre>
     * ①②的顺序颠倒会重新引入「写穿被 RENAME 吃掉」的 404 缺陷，详见类注释。
     *
     * @param allShortCodes 全部有效短码（调用方负责分批扫描）
     * @return 实际写入的短码数；{@code 0} 表示「库中没有有效短码，已跳过切换」
     */
    public long rebuild(Iterable<String> allShortCodes) {
        if (!rebuilding.get()) {
            // ⚠️ 没开窗口就重建 = 扫描期间新建的短码既不在快照里、也没被写穿 → 404。
            //    这里只是提醒，真正保证顺序的是调用方的 beginRebuild() 在扫描之前。
            log.warn("布隆重建未处于写穿窗口期（beginRebuild 未先于数据库扫描调用），"
                    + "扫描期间新建的短码可能在切换后丢失");
        }
        List<String> all = new ArrayList<>();
        allShortCodes.forEach(all::add);
        if (all.isEmpty()) {
            // ⚠️⚠️ **绝不创建「空布隆」**：空布隆的 key 是存在的，
            //    而检查脚本的逻辑是「key 不存在 → 放行；key 存在 → 逐位查」。
            //    于是空布隆会把**每一个**请求都判成「一定不存在」→ 全站 404。
            //    保持 key 不存在（放行）才是安全的那一侧。
            log.warn("布隆重建跳过：库中没有有效短码。不创建空布隆"
                    + "（空布隆会把所有请求判为「一定不存在」→ 全站 404）");
            return 0;
        }
        writeBitsBatched(buildingKey(), all);
        // ⚠️ RENAME 是原子的：切换瞬间不存在「两个都不对」的状态
        maintenanceRedis.rename(buildingKey(), BloomHash.PREFIX);
        log.info("布隆重建完成：shortlink:bloom 由 :building 原子切换而来");
        return all.size();
    }

    /**
     * 打开重建窗口：删除 {@code :building} 并让写穿同时落到现行 key 与 {@code :building}。
     *
     * <p>⚠️ <b>必须在数据库扫描之前调用</b>（DR-065）：
     * <ul>
     *   <li>窗口在扫描<b>之前</b>打开 → 扫描期间创建的短码会被写穿到 {@code :building}，
     *       RENAME 后依然在位；</li>
     *   <li>{@code :building} 在扫描<b>之前</b>删除 → 扫描期间的写穿不会被随后的 DEL 抹掉。</li>
     * </ul>
     * 顺序反了就会重新引入「写穿被 RENAME 吃掉」的 404 缺陷。
     */
    public void beginRebuild() {
        // ⚠️ 先开窗口再删 :building：反过来的话，「开窗口到删 key 之间」的写穿
        //    会把位写进即将被删的 :building，白写一次（不影响正确性，但会让人困惑）。
        rebuilding.set(true);
        maintenanceRedis.delete(buildingKey());
    }

    /**
     * 关闭重建窗口，并清理 RENAME 后可能残留的 {@code :building}。
     *
     * <p>⚠️ 残留清理是安全的：RENAME 之后 {@code :building} 里只剩「切换后又被写穿」的位，
     * 而那些位<b>同时</b>也写进了现行 key，删掉不影响正确性。
     */
    public void endRebuild() {
        rebuilding.set(false);
        try {
            maintenanceRedis.delete(buildingKey());
        } catch (RuntimeException e) {
            // 清理失败无所谓：下次 beginRebuild() 会再删一次
            log.warn("清理布隆 :building 残留失败（不影响正确性）: {}", e.getMessage());
        }
    }

    /**
     * 批量写位（pipeline）。
     *
     * <p>⚠️ <b>为什么必须批量</b>：逐条 SETBIT 是 7 次 RTT/短码，
     * 实测 2 万条短码要 28 秒；pipeline 后同样的量约 1 秒。
     * 重建窗口越短，「窗口期内新建的短码丢失」的概率越低（DR-065）。
     *
     * <p>⚠️ key 的序列化在这里手工做（UTF-8 字节）而不是走模板的 keySerializer：
     * {@code RedisCallback} 拿到的是<b>底层连接</b>，不经过模板的序列化链。
     * {@code StringRedisSerializer} 产出的正是 UTF-8 字节，两者等价。
     */
    private void writeBitsBatched(String key, List<String> codes) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        for (int from = 0; from < codes.size(); from += PIPELINE_BATCH_CODES) {
            int to = Math.min(from + PIPELINE_BATCH_CODES, codes.size());
            List<String> chunk = codes.subList(from, to);
            maintenanceRedis.executePipelined((RedisCallback<Object>) connection -> {
                for (String code : chunk) {
                    int[] bits = new int[BloomHash.HASH_COUNT];
                    BloomHash.bitIndexes(code, bits);
                    for (int bit : bits) {
                        connection.stringCommands().setBit(keyBytes, bit, true);
                    }
                }
                return null;
            });
        }
    }

    /** 重建用的缓冲 key。 */
    private static String buildingKey() {
        return BloomHash.PREFIX + BloomHash.BUILDING_SUFFIX;
    }

    @Override
    public long expectedInsertions() {
        return BloomHash.EXPECTED_INSERTIONS;
    }

    @Override
    public long bitSize() {
        return BloomHash.BIT_SIZE;
    }

    @Override
    public int hashFunctions() {
        return BloomHash.HASH_COUNT;
    }

    /**
     * 把bit 下标转成 {@code redis.execute} 需要的 {@code Object[]}。
     *
     * <h2>⚠️ 为什么必须返回 {@code Object[]} 而不是 {@code List<String>}</h2>
     * {@code RedisTemplate.execute(script, keys, Object... args)} 是**变参**。
     * 传一个 {@code List} 时，Java 会把它当成**单个元素**包成 {@code Object[]{list}}，
     * 而 Spring 的 {@code DefaultScriptExecutor.keysAndArgs} 会对每个元素调
     * {@code StringRedisSerializer.serialize}——它内部直接 {@code (String) value} 强转，
     * 不做类型检查，于是抛 {@code ClassCastException}。
     *
     * <p>⚠️ 更隐蔽的是：{@code catch (DataAccessException)} 抓不住它
     * （CCE 是 {@code RuntimeException}），降级兜底失效，异常直接穿透到 Controller
     * → 跳转全 500（DR-054）。
     *
     * <p>⚠️ <b>不要加占位元素</b>：曾用「首元素放 BIT_SIZE、Lua 从 i=2 开始」，
     * 结果只检查 k-1 = 6 个位。Lua 不报错，只是静默地少判一位。
     * ⚠️ 危害是**k 与规格不符**，不是误判率恶化——实测两者仅差 0.01 个百分点
     * （n=1000 万时 k=7 是 1.0039%、k=6 是 1.0143%）。
     * 我曾在这里写「误判率从 1% 升到 1.3%」，那是用常识替代计算的错误结论（DR-054）。
     *
     * @param bits bit 下标；{@code null} 或空数组返回空 {@code Object[]}
     * @return 每个元素都是 {@code String} 的数组，可安全交给 {@code execute}
     */
    private static Object[] toArgs(int[] bits) {
        if (bits == null || bits.length == 0) {
            return new Object[0];
        }
        Object[] args = new Object[bits.length];
        for (int i = 0; i < bits.length; i++) {
            args[i] = String.valueOf(bits[i]);
        }
        return args;
    }

    /**
     * 布隆 + 缓存合并查询的结果。
     *
     * @param definitelyAbsent 布隆判「一定不存在」，可直接 404
     * @param cachedValue 缓存值（可能为 null，表示需要回源）
     */
    public record BloomCheckResult(boolean definitelyAbsent, String cachedValue) {
        public static BloomCheckResult absent() {
            return new BloomCheckResult(true, null);
        }

        public static BloomCheckResult cached(String value) {
            return new BloomCheckResult(false, value);
        }

        public static BloomCheckResult source() {
            return new BloomCheckResult(false, null);
        }

        /** 布隆说「可能存在」且缓存无值 -> 需要回源。 */
        public boolean requiresSource() {
            return !definitelyAbsent && cachedValue == null;
        }
    }
}