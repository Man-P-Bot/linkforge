local counter = 1
local threads = {}
local codes = {}
CODE_LIST = os.getenv("CODE_LIST")
local f = io.open(CODE_LIST, "r")
for line in f:lines() do table.insert(codes, line) end
f:close()

function setup(thread)
  thread:set("tid", counter)
  table.insert(threads, thread)
  counter = counter + 1
end

function init(args)
  tid = tid
end

function delay() return 0 end

function request()
  local c = codes[(counter % #codes) + 1]
  counter = counter + 1
  return wrk.format("GET", "/" .. c)
end

function response(status, headers, body)
  if status ~= 302 then
    non302 = (non302 or 0) + 1
  end
end

function done(summary, latency, requests)
  io.write("\n--- 完整百分位 ---\n")
  io.write(string.format("P50  = %.2f ms\n", latency:percentile(50.0)))
  io.write(string.format("P75  = %.2f ms\n", latency:percentile(75.0)))
  io.write(string.format("P90  = %.2f ms\n", latency:percentile(90.0)))
  io.write(string.format("P95  = %.2f ms\n", latency:percentile(95.0)))
  io.write(string.format("P99  = %.2f ms\n", latency:percentile(99.0)))
  io.write(string.format("非302 = %d\n", non302 or 0))
end
