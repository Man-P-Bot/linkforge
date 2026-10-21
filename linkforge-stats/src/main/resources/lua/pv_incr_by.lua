-- PV 计数：原子地 INCRBY + 设置 TTL + 登记 dirty set
--
-- ⚠️ 为什么需要这个脚本（阶段 2 的 F4 修补）：
--   原来用两条命令 redis.opsForValue().increment() + redis.expire()。
--   它们**不是原子的**——若进程恰好在两条命令之间崩溃，
--   计数写成功但 TTL 没设上，该 key 会**永久驻留**。
--   而 PV key 是「每天每短码一个」，几个月后 Redis 内存会累积到不可控。
--
-- ⚠️⚠️ 为什么本脚本还要 SADD dirty set（阶段 3 补齐的关键一环）：
--   阶段 3 的定时落库面临一个选择：**怎么找到「今天有流量」的短码**？
--     选项 A：SCAN shortlink:pv:* 全量扫 key
--             ✗ SCAN 是游标遍历不是快照，扫描期间的新 key 可能被漏；
--             ✗ 无法区分「今天有量」与「35 天内有量」，每分钟扫全量是纯浪费。
--     选项 B：**谁产生数据谁登记**（本脚本的做法）
--             ✓ flush 只需 SPOP dirty set，处理量与真实流量成正比；
--             ✓ 登记与计数在同一个 Lua 里，**不增加任何往返**。
--   代价是每次 PV 写入多一次 SADD（O(1)，同一批内重复 SADD 是幂等的）。
--   这个代价远小于「每分钟 SCAN 全量 key」的持续开销。
--
--   ⚠️ 只由 PV 通道登记，UV 不登记：PV 是「每次访问必有」的通道，
--     用它登记已足够表达「这个短码今天有流量」；
--     且 flush 时会对登记过的 code **顺带读一次 UV**，UV 不会漏。
--
-- ⚠️ 为什么在消费线程执行 0.5ms 的 Lua 完全无害（与 DR-056 对照）：
--   DR-056 的教训是「**主线程**同步 I/O 会拖垮 Tomcat 线程池」。
--   本脚本运行在**独立的统计消费线程**上，与跳转请求完全隔离：
--     · 跳转线程只做 offer（纯内存操作，单次 < 1µs）
--     · 消费线程慢，只会让队列积压，最终表现为 dropped 指标上升
--     · 而 dropped 是**可观测且可接受**的（统计可以丢，跳转不能慢）
--   若把这段 Lua 挪到主线程，就会重演限流 63% 吞吐损失的问题。
--
-- KEYS[1] = shortlink:pv:{code}:{yyyymmdd}
-- KEYS[2] = shortlink:stats:dirty:{yyyymmdd}
-- KEYS[3] = {code}（作为 dirty set 的 member）
-- ARGV[1] = 增量（批内该 code 的 PV 条数）
-- ARGV[2] = TTL 秒数

local key = KEYS[1]
local dirtyKey = KEYS[2]
local code = KEYS[3]
local ttl = tonumber(ARGV[2])

local newValue = redis.call('INCRBY', key, ARGV[1])
redis.call('EXPIRE', key, ttl)

-- 登记 dirty：flush 会 SPOP 这个集合来找待落库的短码
redis.call('SADD', dirtyKey, code)
-- ⚠️ dirty set 也必须有 TTL：否则「有流量但映射不到 link_id」的短码
--   （例如短链被物理删除）会永久驻留，白吃内存。
redis.call('EXPIRE', dirtyKey, ttl)

return newValue
