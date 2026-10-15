-- wrk 跳转压测脚本（第 1 周性能基线口径，DR-003 / DR-019）
--
-- 用法（宿主机执行，压测容器与被测应用在同一 compose 网络）：
--   MSYS_NO_PATHCONV=1 docker run --rm --network linkforge_default \
--     -v "D:/path/to/repo/scripts:/scripts:ro" \
--     -v "D:/path/to/repo/benchmark/results:/codes:ro" \
--     -e CODE_LIST=/codes/codes.txt \
--     williamyeh/wrk -t4 -c100 -d30s --latency -s /scripts/wrk-redirect.lua http://app:8080
--
-- ⚠️ MSYS_NO_PATHCONV=1：Git Bash 会把 /scripts/... 改写成 Windows 路径，
--    容器里当然找不到。MSYS 的路径转换必须关掉。
--
-- ⚠️ URL 用**服务名 app** 而不是 localhost：容器内的 localhost 指向压测容器自身，
--    这是最容易犯的错（DR-003）。
--
-- ⚠️ 短码从 CODE_LIST 指定的文件读入（由 bench-create.sh 造数后生成）。
--    为什么不随机生成短码：随机会大量命中 404/400，测的就不是「跳转主路径」
--    而是「错误处理路径」，基线会失真。

local counter = 0
local codes = {}

-- 从 CODE_LIST 环境变量指定的文件加载短码
local path = os.getenv("CODE_LIST") or "/codes/codes.txt"
local f = assert(io.open(path, "r"), "cannot open code list: " .. path)
for line in f:lines() do
    line = line:gsub("%s+$", "")
    if line ~= "" then
        codes[#codes + 1] = line
    end
end
f:close()

assert(#codes > 0, "code list is empty: " .. path)

-- 统计非 302 响应数。基线阶段应恒为 0；
-- 若不为 0，说明压测流量没打在有效短码上，基线不可用。
--
-- ⚠️ wrk 的线程各有独立 Lua 状态：setup/init/request/response 跑在**工作线程**里，
--    done 跑在**主线程**里。主线程的全局变量与工作线程的不是同一份。
--    因此这里不用全局累加，而是每个线程各自计数（init 里置 0），
--    再由 done 通过 thread:get 汇总——thread 才是跨状态传递数据的正确通道。
local thread = nil

function setup(t)
    thread = t
    t:set("counter", 0)
    t:set("non_302", 0)
end

function init(args)
    counter = 0
    non_302 = 0
end

function request()
    counter = counter + 1
    local code = codes[(counter % #codes) + 1]
    return wrk.format("GET", "/" .. code)
end

function response(status, headers, body)
    if status ~= 302 then
        non_302 = non_302 + 1
    end
end

function done(summary, latency, requests)
    -- 汇总各工作线程的非 302 计数
    -- ⚠️ summary.threads 在某些 wrk 版本里不存在（只在 --threads 显式指定时给出），
    --    同样会 panic：'for' limit must be a number。
    --    拿不到线程数就退化为「只读主线程的那份」，宁可少算也不能让脚本崩。
    local total_non_302 = 0
    local nthreads = summary.threads or 1
    for i = 1, nthreads do
        total_non_302 = total_non_302 + (thread:get("non_302") or 0)
    end

    -- ⚠️ summary.errors 各字段可能为 nil（没有该类错误时）。
    --    直接算术会 panic：bad argument #2 to 'format' (number expected, got nil)。
    --    这个坑在「一切正常」时才会出现——恰恰是最需要脚本能跑完的时候。
    local e = summary.errors or {}
    local connect = e.connect or 0
    local read    = e.read or 0
    local write   = e.write or 0
    local timeout = e.timeout or 0
    local staterr = e.status or 0

    io.write("\n--- LinkForge 跳转压测附加统计 ---\n")
    io.write(string.format("短码总数      : %d\n", #codes))
    io.write(string.format("总请求数      : %d\n", summary.requests))
    io.write(string.format("非 302 响应数 : %d\n", total_non_302))
    io.write(string.format("Socket 错误   : connect=%d read=%d write=%d timeout=%d\n",
        connect, read, write, timeout))
    if staterr > 0 then
        io.write(string.format("状态码错误    : %d\n", staterr))
    end
    -- wrk 的 latency 单位是微秒，转成毫秒
    io.write(string.format("P50 / P90 / P99: %.2f / %.2f / %.2f (ms)\n",
        latency:percentile(50) / 1000.0,
        latency:percentile(90) / 1000.0,
        latency:percentile(99) / 1000.0))
    io.write("--------------------------------------\n")
end