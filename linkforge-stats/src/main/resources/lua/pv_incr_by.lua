-- PV 计数：原子地 INCRBY + 设置 TTL
--
-- ⚠️ 为什么需要这个脚本（阶段 2 的 F4 修补）：
--   原来用两条命令 redis.opsForValue().increment() + redis.expire()。
--   它们**不是原子的**——若进程恰好在两条命令之间崩溃，
--   计数写成功但 TTL 没设上，该 key 会**永久驻留**。
--   而 PV key 是「每天每短码一个」，几个月后 Redis 内存会累积到不可控。
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
-- ARGV[1] = 增量（批内该 code 的 PV 条数）
-- ARGV[2] = TTL 秒数

local key = KEYS[1]
local newValue = redis.call('INCRBY', key, ARGV[1])
redis.call('EXPIRE', key, tonumber(ARGV[2]))
return newValue
