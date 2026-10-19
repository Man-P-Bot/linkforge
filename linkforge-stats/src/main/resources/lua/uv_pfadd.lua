-- UV 去重：原子地 PFADD + 设置 TTL
--
-- ⚠️⚠️ **DR-054 回归点的 Lua 版**
--   Java 侧 opsForHyperLogLog().add(key, String... values) 是**变长参数**：
--     · 传 String[] -> 展开成多个值（正确）
--     · 传 List     -> 被当成**单个元素**序列化，
--                     PFADD 只记了一个 "[a, b, c]" 字符串，**UV 恒等于 1**
--   改用 Lua 后，ARGV 的形态由脚本自己决定，不存在这个歧义。
--
-- ⚠️ 为什么 unpack(ARGV, 2) 在本场景安全：
--   Lua 的栈上限约 8000 个值，而本脚本的批大小上限是 BATCH_SIZE=500
--   （一个 code 的 visitor 数不会超过批大小）。
--   ⚠️ 若将来 BATCH_SIZE 提高到数千，需要改用分片 PFADD，
--      否则会撞上 "too many results to unpack"。
--
-- ⚠️ 为什么在消费线程执行 0.5ms 的 Lua 完全无害（与 DR-056 对照）：
--   DR-056 的教训是「**主线程**同步 I/O 会拖垮 Tomcat 线程池」。
--   本脚本运行在独立的统计消费线程上，与跳转请求完全隔离——
--   详见 pv_incr_by.lua 顶部注释的同一段说明。
--
-- KEYS[1] = shortlink:uv:{code}:{yyyymmdd}
-- ARGV[1] = TTL 秒数
-- ARGV[2..] = visitor 标识列表

local key = KEYS[1]
local ttl = tonumber(ARGV[1])
local visitors = {}

for i = 2, #ARGV do
  visitors[#visitors + 1] = ARGV[i]
end

if #visitors > 0 then
  redis.call('PFADD', key, unpack(visitors))
  redis.call('EXPIRE', key, ttl)
  return redis.call('PFCOUNT', key)
end
return 0
