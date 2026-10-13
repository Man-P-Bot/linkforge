# LinkForge 接口契约（API）

> 上游依据：`docs/PRD.md`（US-1 ~ US-7、A-7/A-8）、`docs/ARCHITECTURE.md`（保留路径、限流、降级）、`docs/DATABASE.md`（失效判定优先级）。
> 决策来源：`docs/DECISIONS.md`（DR-001 ~ DR-013）。
> **错误码体系（A-4）在本文档定稿，第 1 周前生效。**

---

## 1. 契约原则与上游约束

| 来源 | 约束 | 对接口的要求 |
|---|---|---|
| PRD D-1 | 跳转走根路径 | `GET /{code}`，与 `/api`、`/admin` 共享命名空间 → 保留字黑名单 |
| PRD D-4 | 跳转链路不得依赖 admin | 跳转接口与 admin 接口在代码与限流上完全隔离 |
| PRD A-7 | `/admin/**` 缺 Token 一律拒绝 | 后台接口统一鉴权，无例外 |
| PRD A-8 | 非 http/https 目标一律拒绝 | 创建接口的强制校验，不可配置关闭 |
| DATABASE §4.2 | 失效判定优先级：先 `status`，DELETED 直接 404 | **接口层必须原样映射**：404 与 410 不可混用 |
| PRD §7.4 | UV 是 HyperLogLog 估算值（约 0.81% 误差） | 统计接口**必须在响应中标注**为估算值 |
| ARCHITECTURE §6.4 | 保留字黑名单需启动时断言 | 接口层校验 + 启动断言双保险 |

**契约原则**：

1. **错误体格式在所有接口间完全一致**——唯一例外是 429（见 §3.4）。
2. **不泄露内部信息**：不返回数据库自增 `id`、不返回发号器序号、不返回异常栈。
3. **状态码语义唯一**：一个状态码只表达一种含义（尤其 404 与 410）。
4. **契约即验收依据**：本文档的示例可直接作为第 1 周起的验收脚本。

---

## 2. 接口总览

| # | 方法 | 路径 | 鉴权 | 限流 | 幂等 | 对应 PRD |
|---|---|---|---|---|---|---|
| 1 | GET | `/{code}` | ❌ 公开 | 按 IP，**20 次/秒** | ✅ | US-3、US-4 |
| 2 | POST | `/api/v1/links` | ❌ 公开 | 按 IP，**10 次/分钟** | ❌ **不幂等**（D-8） | US-1、US-2、US-7 |
| 3 | GET | `/api/v1/links/{code}` | ❌ 公开 | 按 IP，**60 次/分钟** | ✅ | US-1 |
| 4 | DELETE | `/admin/api/v1/links/{code}` | ✅ Token | 按 Token/IP，**30 次/分钟** | ✅ **幂等** | US-6、A-7 |
| 5 | GET | `/admin/api/v1/links/{code}/stats` | ✅ Token | 按 Token/IP，**30 次/分钟** | ✅ | US-5、A-7 |

**限流阈值说明**：创建接口按 IP **每分钟 10 次**（远比跳转严格）——创建是写路径且可被滥用（批量灌短链），而跳转是正常业务流量。查询接口比跳转严格、比创建宽松。所有阈值**配置化**，不硬编码。

**鉴权说明（DR-013）**：接口 3 不鉴权，因为短链本身是公开跳转的——目标 URL 通过一次 302 即可获得，给查询加鉴权属于**安全剧场**；但查询没有跳转的业务价值，因此**用更严的限流替代鉴权**控制成本。

---

## 3. 通用约定

### 3.1 编码与请求头

- `Content-Type: application/json; charset=utf-8`（所有带 body 的请求与响应）。
- **跳转成功（302）无 body**（PRD：跳转只需把浏览器送走，不返回内容）。

### 3.2 时间格式（DR-013、DR-027）

| 项 | 约定 |
|---|---|
| 传输格式 | **ISO-8601 带时区**，格式 `yyyy-MM-dd'T'HH:mm:ssXXX` |
| 示例 | `2026-01-15T10:30:00+08:00` |
| Java 类型（入/出） | **`OffsetDateTime`** —— 保留偏移量，不做隐式时区转换 |
| 数据库存储 | **UTC**，列类型 `TIMESTAMP` |
| Java 类型（持久层） | **`LocalDateTime`（UTC）** —— 与 `short_link.expire_at` / `created_at` 列对应 |
| 精度 | 秒（与 PRD §7.3「过期时间精确到秒」一致） |

**转换链（DR-027，D4 定稿，D5 过期判定直接复用）**：
```
请求 JSON "2026-01-15T10:30:00+08:00"
   │  Jackson 反序列化
   ▼
OffsetDateTime（保留 +08:00 偏移）
   │  写库：withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime()
   ▼
LocalDateTime（UTC）  ──►  MySQL TIMESTAMP 列
   │  读库：LocalDateTime（UTC）.atOffset(ZoneOffset.UTC)
   ▼
OffsetDateTime（UTC）
   │  响应：withOffsetSameInstant(服务端时区, 默认 +08:00)
   ▼
输出 JSON "2026-01-15T10:30:00+08:00"
```

- 请求中的时间必须带时区偏移；**不带时区的时间一律视为非法参数**（返回 400，`LINKFORGE-400-01`）——接受「无时区时间」会把时区歧义转嫁给服务端。
- 响应中的时间统一按服务端配置的时区渲染（默认 `+08:00`）。
- ⚠️ **连接串已固定 `serverTimezone=Asia/Shanghai`**（见 DR-023）：驱动按该时区解释 `TIMESTAMP`，因此应用层必须**存 UTC 的 `LocalDateTime`**、不要存带偏移的对象，否则会出现「偏移被应用一次、又被驱动解释一次」的双重换算。
- ⚠️ **过期判定（D5）必须在 `Instant`/`OffsetDateTime` 语义下比较**，不要拿「UTC 的 `LocalDateTime`」直接与本地 `LocalDateTime.now()` 比 —— 那样在非 UTC 部署环境下会得到错误结论。要么统一转 `Instant` 比较，要么统一用注入的 UTC 时间源。

### 3.3 统一响应结构

**成功（有 body 的接口）**：

```
{
  "data": { ... },
  "timestamp": "2026-01-15T10:30:00+08:00"
}
```

**失败（统一错误体）**：

```
{
  "code": "LINKFORGE-400-01",
  "message": "targetUrl must use http or https scheme",
  "timestamp": "2026-01-15T10:30:00+08:00",
  "details": { "field": "targetUrl", "rejectedValue": "javascript:alert(1)" }
}
```

- `message` 为**面向调用方的英文短句**，不含内部类名、SQL、栈信息。
- `details` 可选，仅在参数校验类错误中出现，用于指出具体字段。
- `timestamp` 为服务端生成时间（ISO-8601，见 §3.2）。

### 3.4 ⚠️ 429 的唯一例外：极简 body（DR-013）

429 是**唯一**不遵循 §3.3 统一结构的状态码。它发生在大流量滥用场景，此时不应再消耗序列化与带宽成本：

- **429 响应体只含 `code`、`message`、`retryAfter` 三个字段**，**不带 `details`**，**不带嵌套结构**。
- **必须带 `Retry-After` 响应头，单位为秒**。

```
HTTP/1.1 429 Too Many Requests
Retry-After: 42
Content-Type: application/json; charset=utf-8

{ "code": "LINKFORGE-429-01", "message": "rate limit exceeded", "retryAfter": 42 }
```

- `Retry-After` 头与 body 中的 `retryAfter` **必须为同一数值**，避免调用方看到两个不同的等待时间。
- 理由：429 的处置动作只有一个——等一会儿再来；任何额外字段都是无用负担。

### 3.5 CORS

- **v1 不开启 CORS**。
- 理由：v1 无浏览器前端（后台管理不提供 Web 界面，只有接口），跨域来源无从约束，开启 CORS 等于放宽一个不存在需求的边界。
- **v2 再做**（届时需要先明确允许的来源白名单，禁止使用 `*` 配合凭证）。

### 3.6 幂等性总述

| 接口 | 幂等 | 说明 |
|---|---|---|
| 创建 | ❌ | **同一 `targetUrl` 重复创建，每次返回新短码**，不做幂等复用（PRD D-8、US-7） |
| 删除 | ✅ | 重复删除已删除的短码返回 **200 + `alreadyDeleted` 标记**，不报错（§7.1） |
| 查询 | ✅ | 天然幂等 |

### 3.7 分页

- **v1 不做分页**：接口 5 的返回以「日期范围 + 来源聚合」为界，结果集受日期上限约束（§7.2），不存在无界返回。

---

## 4. 跳转接口 `GET /{code}`

**用途**：把访问者送到目标地址（PRD US-3）。

### 4.1 路径参数校验（补充 8）

| 情况 | 处理 |
|---|---|
| `{code}` 为 Base62 之外的字符（含中文、`%`、`.`、`_`、`-`、空格） | **直接返回 400**，不进入查找逻辑 |
| `{code}` 长度不是 7（默认短码长度） | 视为「不存在」，返回 404（长度不符不构成「参数非法」，而是「一定查不到」） |
| **尾斜杠**（如 `/abc1234/`） | **返回 400**，**不做重定向、不做自动纠正** |

- **尾斜杠为什么返回 400 而不是重定向**：自动重定向会让「一个短码对应多个可访问 URL」，破坏短码与链接的一一对应，也会让缓存与统计出现双份记录。明确拒绝比静默纠正更容易排查。
- **非 Base62 字符为什么返回 400 而不是 404**：这是**请求格式错误**，不是「查不到」；区分两者能让调用方立刻发现自己的拼接 bug。

### 4.2 成功响应

```
HTTP/1.1 302 Found
Location: http://localhost:8080/abc1234   ← 见 §4.3，此处应为目标地址
```

**⚠️ `Location` 头必须是绝对 URL**（补充 1）：

- 由环境变量 **`SHORTLINK_BASE_URL`** 拼接生成，**不得依赖请求的 `Host` 头**。
- 理由：依赖 `Host` 会引入 Host 头投毒风险，且反向代理场景下 `Host` 与实际对外域名可能不一致，会产生错误的跳转地址。
- 开发环境取值：**`http://localhost:8080`**。
- **配置项**：`SHORTLINK_BASE_URL`（无默认值，**必填**，缺失时应用启动失败——静默使用错误 base URL 会产生对外可见的错误链接）。

### 4.3 响应头设计

| 头 | 值 | 理由 |
|---|---|---|
| `Location` | 目标绝对 URL | 302 的语义要求 |
| `Cache-Control` | **`no-store`** | 与 PRD D-3「用 302 而非 301 以避免浏览器缓存、保证统计不失真」的初衷保持一致：若不显式禁止缓存，中间层仍可能缓存跳转结果 |
| `Retry-After` | 仅 429 携带 | §3.4 |

### 4.4 失败响应

| 情况 | 状态码 | 错误码 | body |
|---|---|---|---|
| 短码格式非法 / 尾斜杠 | 400 | `LINKFORGE-400-01` | 完整错误体 |
| 短码不存在（含从未创建、已删除、布隆判不存在） | **404** | `LINKFORGE-404-01` | 完整错误体 |
| 短码已过期 | **410** | `LINKFORGE-410-01` | 完整错误体 |
| 触发限流 | **429** | `LINKFORGE-429-01` | **极简体**（§3.4）+ `Retry-After` |
| 依赖不可用（MySQL 挂且缓存未命中） | 503 | `LINKFORGE-503-01` | 完整错误体 |

- **404 与 410 的区分是硬要求**（PRD US-4）：`status = DELETED` → 404（**优先判定，不看 `expire_at`**）；`expire_at` 已到 → 410（DATABASE §4.2）。
- **跳转失败返回完整 JSON**（用户确认）：契约一致性优先；失败请求占比低，不构成性能负担，且便于压测统计失败路径。

### 4.5 日志约定

- 跳转**不记录每次请求的 INFO 日志**（ARCHITECTURE §14.2）——高 QPS 下会拖垮磁盘。
- 仅 WARN/ERROR：降级事件、缓存删除失败、依赖超时。

---

## 5. 创建接口 `POST /api/v1/links`

**用途**：长链换短码，可自定义短码、可指定过期时间（PRD US-1、US-2）。

### 5.1 请求体

| 字段 | 必填 | 约束 |
|---|---|---|
| `targetUrl` | ✅ | 仅 `http`/`https`；长度 **≤ 2048**，**超长直接拒绝，不截断** |
| `customCode` | ❌ | 正则 **`^[a-zA-Z0-9]{4,8}$`**，**大小写敏感** |
| `expireAt` | ❌ | ISO-8601 带时区；不传 ⇒ 永久有效；必须是**将来**时间 |

- **`targetUrl` 为什么拒绝超长而不是截断**：截断会产生指向错误地址的短链，比拒绝请求危险得多（PRD：接口拒绝超长，DATABASE §4.1）。
- **`expireAt` 必须是将来时间**：允许创建「一出生就过期」的短链没有业务意义，且会让调用方误以为创建失败。

### 5.2 自定义短码校验（补充 2）

| 规则 | 说明 |
|---|---|
| 格式 | `^[a-zA-Z0-9]{4,8}$`（**4~8 位**，比自动生成的 7 位更宽松，便于用户取有意义的名字） |
| 大小写 | **敏感**（`Abc123` 与 `abc123` 是两个不同短码） |
| 保留字黑名单 | **应用层校验**，命中返回 400（`LINKFORGE-400-03`）。黑名单：`api, admin, actuator, health, favicon.ico, static, error`。⚠️ **比较大小写不敏感**（DR-025） |
| 已占用 | 返回 **409**，**不静默覆盖** |
| 与自动生成共用空间 | 自动生成的 7 位短码与自定义短码在同一唯一空间内判重（DATABASE §4.1） |

- 保留字黑名单**同时**在启动时做断言（校验黑名单覆盖所有已注册系统路径前缀），避免 `/{code}` 吃掉 `/api/v1/links`（ARCHITECTURE §6.4）。
- ⚠️ **大小写不敏感看似与「短码大小写敏感」矛盾，实为有意**（DR-025）：短码值大小写敏感（`Abc123` ≠ `abc123`），但**保留字保护的是一条路径而不是一个值**。HTTP 路径在不同实现/容器/代理下的大小写归一化行为并不一致，若只拦小写 `api`，`Api` 在某些部署下仍会撞上 `/api`——防线的有效性会依赖「部署环境恰好不归一化」，这是不可接受的假设。因此黑名单加载时统一转小写存入 `Set`，校验时把 `customCode` 转小写后比较。

### 5.3 成功响应 `201 Created`

```
{
  "data": {
    "shortCode": "abc1234",
    "shortUrl": "http://localhost:8080/abc1234",
    "targetUrl": "https://example.com/very/long/path",
    "custom": false,
    "expireAt": null,
    "createdAt": "2026-01-15T10:30:00+08:00"
  },
  "timestamp": "2026-01-15T10:30:00+08:00"
}
```

- `shortUrl` 由 `SHORTLINK_BASE_URL` 拼接（§4.3 同一配置项）。
- **明确不返回**：数据库自增 `id`（内部标识，泄露无益且会暴露数据量）、发号器序号（会暴露短码可枚举性）、`status`（新建必为 ACTIVE，无信息量）。

### 5.4 失败响应

| 情况 | 状态码 | 错误码 |
|---|---|---|
| `targetUrl` 缺失 / 超长 / 协议非 http(s) | 400 | `LINKFORGE-400-01` |
| `customCode` 格式不符（正则不匹配） | 400 | `LINKFORGE-400-02` |
| `customCode` 命中保留字黑名单 | 400 | `LINKFORGE-400-03` |
| `expireAt` 格式非法或不是将来时间 | 400 | `LINKFORGE-400-01` |
| `customCode` 已被占用 | **409** | `LINKFORGE-409-01` |
| 发号器不可用（Redis 与数据库号段同时失败） | 503 | `LINKFORGE-503-01` |
| 触发限流（每分钟 10 次） | 429 | `LINKFORGE-429-01` + `Retry-After` |

### 5.5 明确不做的行为

- **不做幂等**：同一 `targetUrl` 重复提交 ⇒ 每次返回**新的** `shortCode`（PRD US-7、D-8）。
- **不做 URL 归一化**：不剥离 `utm_*` 参数、不统一大小写、不去末尾斜杠（DATABASE §4.1）。
- **不做连通性校验**：不主动请求目标 URL 验证其可达（会引入 SSRF 风险与被测站点压力）。

---

## 6. 查询接口 `GET /api/v1/links/{code}`

**用途**：查看某短码的元信息（PRD US-1）。

### 6.1 鉴权

- **不鉴权**（DR-013），但限流 **60 次/分钟**（比跳转严格）。
- **只返回非敏感元信息**，**不返回统计**（统计属后台，见 §7.2）。

### 6.2 成功响应 `200 OK`

```
{
  "data": {
    "shortCode": "abc1234",
    "shortUrl": "http://localhost:8080/abc1234",
    "targetUrl": "https://example.com/very/long/path",
    "custom": false,
    "status": "ACTIVE",
    "expireAt": null,
    "createdAt": "2026-01-15T10:30:00+08:00",
    "updatedAt": "2026-01-15T10:30:00+08:00"
  },
  "timestamp": "2026-01-15T10:30:00+08:00"
}
```

- 此接口返回 `status`（与创建接口不同）：查询的用途之一就是确认链接当前是否有效。

### 6.3 失败响应

| 情况 | 状态码 | 错误码 |
|---|---|---|
| 短码格式非法 / 尾斜杠 | 400 | `LINKFORGE-400-01` |
| 短码不存在或已删除 | 404 | `LINKFORGE-404-01` |
| 短码已过期 | 410 | `LINKFORGE-410-01` |
| 限流 | 429 | `LINKFORGE-429-01` |

- **失效优先级与跳转接口完全一致**（先 `status` → 404，再 `expire_at` → 410），不允许两个接口给出不同状态码。

---

## 7. 后台接口 `/admin/api/v1/**`

### 7.1 删除短链 `DELETE /admin/api/v1/links/{code}`

**用途**：让短码立即停止生效（PRD US-6）。

**行为与响应（DR-013）**：

| 情况 | 状态码 | 响应 |
|---|---|---|
| 成功删除 | **200** | `{"data": {"shortCode": "abc1234", "alreadyDeleted": false}, ...}` |
| 删除一个**已删除**的短码 | **200** | `{"data": {"shortCode": "abc1234", "alreadyDeleted": true}, ...}` |
| 短码**从未存在** | **404** | `LINKFORGE-404-01` |
| 短码**已过期** | 200 | 视为可删除，返回 `alreadyDeleted: false` |
| 缺 Token / Token 错误 | **401** | `LINKFORGE-401-01` |

- **为什么区分「已删除」(200) 与「不存在」(404)**：两者语义不同——「已经删过了」是重复执行同一意图（幂等成功），而「这个码从来没存在过」通常是调用方**拼错了短码**。都返回 200 会让拼写错误无法被发现。
- **幂等性**：重复调用删除返回 200，不报错（PRD US-6 验收）。
- **`alreadyDeleted` 放在 `data` 里而不是用不同状态码**：状态码保持「200 = 请求意图已达成」的单一含义，把**信息性差异**放在 body 中，避免调用方需要区分两个成功状态码。

**删除的内部语义**（与 DATABASE §7.2、ARCHITECTURE §5.3 一致）：

1. 事务内改 `status = DELETED`；
2. 提交后按 **Cache Aside 删缓存**；删除失败**重试一次 + 记指标**，**不抛回请求**；
3. 布隆过滤器**不移除**该短码（布隆不支持删除），删除后该码会多一次回源查库。

### 7.2 查询统计 `GET /admin/api/v1/links/{code}/stats`

**用途**：看某短链的 PV/UV/来源分布（PRD US-5）。

**请求参数**：

| 参数 | 必填 | 约束 |
|---|---|---|
| `from` | ❌ | 起始日期（含），格式 `yyyy-MM-dd`；**默认 = 今天 − 6 天** |
| `to` | ❌ | 结束日期（含），格式 `yyyy-MM-dd`；**默认 = 今天** |

**日期范围约束（补充 5）**：

- **默认最近 7 天**（`from` 与 `to` 均缺省时）。
- **最大 90 天**（与统计保留期一致）。
- **超出 90 天返回 400**（`LINKFORGE-400-01`），**不做静默截断**——静默截断会让调用方以为自己拿到了完整数据。
- `from > to` 返回 400。

**成功响应 `200 OK`**：

```
{
  "data": {
    "shortCode": "abc1234",
    "from": "2026-01-09",
    "to": "2026-01-15",
    "uvEstimated": true,
    "uvNote": "UV is estimated by Redis HyperLogLog, error rate ~0.81%",
    "daily": [
      { "date": "2026-01-15", "pv": 1240, "uv": 318, "referers": [
          { "domain": "direct", "pv": 620 },
          { "domain": "weibo.com", "pv": 410 },
          { "domain": "example.org", "pv": 210 }
      ]}
    ],
    "totals": { "pv": 8610, "uv": 1902 }
  },
  "timestamp": "2026-01-15T10:30:00+08:00"
}
```

- **`uvEstimated` 与 `uvNote` 是强制字段**：UV 来自 HyperLogLog，误差率约 **0.81%**、内存约 **12KB/key**（PRD §7.4）。**必须让调用方知道这是估算值**，否则会被当作精确人数使用。
- `referers` 中 **`'direct'` 表示无 Referer 的直接访问**（DATABASE §4.3：空 Referer 存 `'direct'` 而非 NULL）。
- `totals` 为范围内合计；`uv` 合计同样是估算值（按天估算值相加，**不是**区间去重结果——区间内同一访客会被重复计入不同天）。

**失败响应**：

| 情况 | 状态码 | 错误码 |
|---|---|---|
| 日期格式非法 / 范围超 90 天 / `from > to` | 400 | `LINKFORGE-400-01` |
| 短码不存在或已删除 | 404 | `LINKFORGE-404-01` |
| 缺 Token / Token 错误 | 401 | `LINKFORGE-401-01` |
| 限流 | 429 | `LINKFORGE-429-01` |

> ⚠️ **区间 UV 的口径必须写清楚**：HyperLogLog 是按「短码 + 日期」分 key 的（DATABASE §6.2），因此**无法跨天做精确区间去重**。区间 UV 合计是「各天估算值之和」，会比真实区间去重值**偏大**。这是设计的已知边界，必须写进文档与面试口径，不能假装它是精确区间 UV。

### 7.3 鉴权约定（PRD A-7）

- 后台接口**统一要求** `Authorization: Bearer <token>`。
- Token 由**环境变量**注入（`ADMIN_API_TOKEN`），**禁止提交进仓库**。
- **缺失、格式错误、值不匹配一律返回 401**，无例外、无匿名放行。
- 后台**独立限流**（独立 Key 前缀、阈值更严），避免后台被刷影响跳转（ARCHITECTURE §9）。

---

## 8. 错误码表（A-4，本文档核心交付物）

**格式：`LINKFORGE-<HTTP状态码(3位)>-<两位序号>`**，序号从 `01` 起。

```
LINKFORGE-400-01
   │       │   └── 该状态码下的第 1 个错误
   │       └────── HTTP 状态码（3 位，与响应的 HTTP 状态**必然一致**）
   └────────────── 固定前缀
```

**为什么用连字符分隔而不是把状态码与序号连写**：连写形式（如 `LINKFORGE-4001`）无法一眼区分
「400 段的第 1 个」与「401 段的第 0 个」，且 `LINKFORGE-4410`（410 段）这类编号需要读者做心算。
分隔后**状态码段与响应的 HTTP 状态直接对应**，调用方与排查者都不需要对照表。
⚠️ 这个格式**覆盖 4xx 与 5xx**（`400`~`599`），5xx 用 `5xx` 段，不再限制在 `4xxx`。

| 错误码 | HTTP | 含义 | 触发条件 |
|---|---|---|---|
| `LINKFORGE-400-01` | 400 | 参数错误 | `targetUrl` 缺失/超长/协议非 http(s)；时间格式非法或非将来时间；日期范围非法；短码路径字符非法或带尾斜杠 |
| `LINKFORGE-400-02` | 400 | 自定义短码格式非法 | 不匹配 `^[a-zA-Z0-9]{4,8}$` |
| `LINKFORGE-400-03` | 400 | 自定义短码命中保留字 | 值为 `api`/`admin`/`actuator`/`health`/`favicon.ico`/`static`/`error`（**大小写不敏感**，见 §5.2） |
| `LINKFORGE-401-01` | 401 | 未鉴权或 Token 无效 | 后台接口缺 `Authorization` 头、格式错误、Token 不匹配 |
| `LINKFORGE-404-01` | 404 | 短码不存在 | 从未创建 / **已删除（`status = DELETED`）** / 布隆判不存在 |
| `LINKFORGE-409-01` | 409 | 短码冲突 | 自定义短码已被占用（**不静默覆盖**） |
| `LINKFORGE-410-01` | 410 | 短码已过期 | `status = ACTIVE` 但 `expire_at <= now` |
| `LINKFORGE-429-01` | 429 | 触发限流 | 超过该接口的 IP/Token 阈值；**响应含 `Retry-After`** |
| `LINKFORGE-503-01` | 503 | 依赖不可用 | MySQL 不可用且缓存未命中；发号器（Redis 与数据库号段）均不可用 |

**错误码规则（写死，面向未来避免编号混乱；权威记录见 `docs/DECISIONS.md` DR-024）**：

1. **编号规则**：`LINKFORGE-` + **HTTP 状态码（3 位）** + `-` + **两位序号**（`01`~`99`，从 `01` 起）。
2. **状态码段与 HTTP 响应状态必然一致**：错误码里的状态码**就是**响应的 HTTP 状态，
   不允许出现「码里写 400、实际返回 422」这类不一致。这条由 `ErrorCode` 枚举同时持有
   `code` 与 `httpStatus` 两个字段来保证（见 D4 设计）。
3. **4xx 用 `4xx` 段、5xx 用 `5xx` 段**，全格式覆盖 `400`~`599`。
4. **序号按「同一状态码内的新增顺序」递增**，不复用、不回收。
5. **错误码一旦发布不得复用或改义**；废弃只在表中标记 `已废弃`，**不删除行**。
6. **新增错误码只允许追加**，且必须同步更新本节与 §9 映射表。
7. ⚠️ **本格式于 D4 前修订**：初版曾用连写形式（`LINKFORGE-4001`、`LINKFORGE-4410`、`LINKFORGE-5003`），
   存在「无法一眼看出 HTTP 状态」与「4xxx 段容纳不下 5xx」两个问题。
   **当前项目尚未发布任何接口**，因此这是一次**兼容性无损的格式修订**，不涉及契约破坏。

---

## 9. 状态码与错误码映射

| HTTP | 语义（唯一含义） | 错误码 | 为什么是它 |
|---|---|---|---|
| 200 | 意图已达成 | 无错误码（成功） | 幂等重试不应表现为失败：删除已删除的短码也走 200 |
| 201 | 资源已创建 | 无错误码（成功） | 创建是新资源产生，语义上比 200 更准确 |
| 302 | 临时重定向 | 无错误码（成功） | 不用 301：避免浏览器永久缓存导致统计失真（PRD D-3） |
| 400 | **请求格式或参数错误** | `LINKFORGE-400-01` / `-02` / `-03` | 调用方可以自己修的问题 |
| 401 | **未鉴权** | `LINKFORGE-401-01` | 后台接口唯一入口守卫 |
| 404 | **资源不存在** | `LINKFORGE-404-01` | 含「已删除」，因为删除后对外**就是不存在** |
| 409 | **状态冲突** | `LINKFORGE-409-01` | 短码被占用，需要换一个码 |
| 410 | **曾经存在但已失效** | `LINKFORGE-410-01` | **与 404 必须区分**（PRD US-4）：410 告诉调用方「这个码曾经有效」，对排查过期配置有直接价值 |
| 429 | **限流** | `LINKFORGE-429-01` | 唯一携带 `Retry-After`、唯一使用极简 body |
| 503 | 依赖不可用 | `LINKFORGE-503-01` | 服务端问题，重试可能成功 |

**阅读方式**：错误码的中间段**就是**该响应必须使用的 HTTP 状态（规则 2）。
上表第 3 列因此只需要写序号段就能验证一致性——若某处实现成「返回 200 却带 `LINKFORGE-400-01`」，即为契约违规。

**为什么 404 与 410 必须区分**（面试口径）：两者对调用方的含义完全不同——404 表示「你搞错了短码」，410 表示「短码没错，但已过期或失效」。合并成一个码，调用方就无法区分「链接被删了」和「链接写错了」，运营排查成本显著上升。

---

## 10. 版本策略与兼容性

- 业务接口统一挂 **`/api/v1`**；后台统一挂 **`/admin/api/v1`**。
- **跳转接口不参与版本化**：`GET /{code}` 是最终用户的公开入口，**永远不带版本号**——短链一旦分享出去就不可改，给它加版本号会让已分享的链接全部失效。这是本项目版本策略中最重要的例外。
- **破坏性变更**（需要升到 `v2`）：字段删除、字段语义改变、必填项新增、错误码改义、状态码语义改变。
- **非破坏性变更**（`v1` 内允许）：新增可选请求字段、新增响应字段、新增错误码、放宽校验限制。
- 收紧校验限制（如短码正则变严）属于**破坏性变更**，因为已存在的合法调用会开始失败。

---

## 11. 配置项清单

| 环境变量 | 必填 | 开发环境取值 | 说明 |
|---|---|---|---|
| `SHORTLINK_BASE_URL` | ✅ | `http://localhost:8080` | **拼 `Location` 与 `shortUrl`**；缺失时启动失败 |
| `ADMIN_API_TOKEN` | ✅ | 本地自定义（**不进仓库**） | 后台鉴权 Token |
| `RATE_LIMIT_REDIRECT_PER_SEC` | ❌ | `20` | 跳转按 IP 限流 |
| `RATE_LIMIT_CREATE_PER_MIN` | ❌ | `10` | 创建按 IP 限流（比跳转严格） |
| `RATE_LIMIT_QUERY_PER_MIN` | ❌ | `60` | 查询按 IP 限流 |
| `RATE_LIMIT_ADMIN_PER_MIN` | ❌ | `30` | 后台按 Token/IP 限流 |
| `RESERVED_CODES` | ❌ | `api,admin,actuator,health,favicon.ico,static,error` | 保留字黑名单（配置化，新增无需改代码） |
| `CACHE_TTL_SECONDS` | ❌ | `3600` | 跳转缓存基础 TTL（叠加 0~5 分钟抖动） |
| `CACHE_NULL_TTL_SECONDS` | ❌ | `30` | 空值缓存 TTL |
| `STATS_MAX_RANGE_DAYS` | ❌ | `90` | 统计查询最大范围（与保留期一致） |
| `STATS_DEFAULT_RANGE_DAYS` | ❌ | `7` | 统计查询默认范围 |

- **`SHORTLINK_BASE_URL` 为什么必填**：默认值看似方便，但一旦在生产忘记设置，就会给用户返回 `http://localhost:8080/...` 这种**对外不可用的短链**——错误发生在用户点击时，排查成本极高。**启动即失败**是更安全的选择。
- **启动期校验（DR-026）**：除「缺失即失败」外，**空字符串 / 全空白 / 纯斜杠 / 无 scheme / scheme 非 http(s)** 一律**启动失败**，不允许带病启动。
- **末尾斜杠归一化（DR-026）**：`http://localhost:8080/` 与 `http://localhost:8080` 必须产出同一结果，拼接后**不得出现 `//`**。
- **CORS 相关配置：v1 不提供**（§3.5）。

---

## 12. 完整调用序列（第 1 周验收脚本基础）

```bash
# 1. 创建短链（期望 201 + 7 位 shortCode）
curl -s -X POST http://localhost:8080/api/v1/links \
  -H "Content-Type: application/json" \
  -d '{"targetUrl":"https://example.com/very/long/path"}'

# 2. 自定义短码创建（期望 201）
curl -s -X POST http://localhost:8080/api/v1/links \
  -H "Content-Type: application/json" \
  -d '{"targetUrl":"https://example.com/promo","customCode":"promo2026","expireAt":"2026-12-31T23:59:59+08:00"}'

# 3. 短码已被占用（期望 409 + LINKFORGE-409-01）
curl -s -X POST http://localhost:8080/api/v1/links \
  -H "Content-Type: application/json" \
  -d '{"targetUrl":"https://example.com/other","customCode":"promo2026"}'

# 4. 协议非法（期望 400 + LINKFORGE-400-01）
curl -s -X POST http://localhost:8080/api/v1/links \
  -H "Content-Type: application/json" \
  -d '{"targetUrl":"javascript:alert(1)"}'

# 5. 跳转（期望 302，Location 指向目标，Cache-Control: no-store）
curl -s -I http://localhost:8080/abc1234

# 6. 不存在（期望 404 + LINKFORGE-404-01）
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/zzzzzzz

# 7. 格式非法（期望 400 + LINKFORGE-400-01）
curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:8080/abc_123"

# 8. 尾斜杠（期望 400，不做重定向）
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/abc1234/

# 9. 查询元信息（期望 200）
curl -s http://localhost:8080/api/v1/links/abc1234

# 10. 后台无 Token（期望 401）
curl -s -o /dev/null -w "%{http_code}\n" \
  -X DELETE http://localhost:8080/admin/api/v1/links/abc1234

# 11. 后台删除（期望 200 + alreadyDeleted:false）
curl -s -X DELETE http://localhost:8080/admin/api/v1/links/abc1234 \
  -H "Authorization: Bearer $ADMIN_API_TOKEN"

# 12. 重复删除（期望 200 + alreadyDeleted:true）
curl -s -X DELETE http://localhost:8080/admin/api/v1/links/abc1234 \
  -H "Authorization: Bearer $ADMIN_API_TOKEN"

# 13. 删除后跳转（期望 404）
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/abc1234

# 14. 查统计（期望 200，含 uvEstimated:true）
curl -s "http://localhost:8080/admin/api/v1/links/promo2026/stats?from=2026-01-09&to=2026-01-15" \
  -H "Authorization: Bearer $ADMIN_API_TOKEN"

# 15. 统计范围超 90 天（期望 400 + LINKFORGE-400-01）
curl -s -o /dev/null -w "%{http_code}\n" \
  "http://localhost:8080/admin/api/v1/links/promo2026/stats?from=2020-01-01&to=2026-01-15" \
  -H "Authorization: Bearer $ADMIN_API_TOKEN"

# 16. 限流（期望出现 429 + Retry-After）
for i in $(seq 1 30); do curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/abc1234; done
```

---

## 13. 待确认问题清单

| 编号 | 问题 | 归属 | 备注 |
|---|---|---|---|
| P-1 | 创建接口的 IP 限流阈值在真实压测下的取值（当前 10 次/分钟为经验值） | 第 5 周 | 需与压测场景平衡，避免压测本身触发 429 |
| P-2 | 区间 UV 是否需要「跨天去重」能力（当前为各天估算值之和，偏大） | v2 | 见 §7.2 ⚠️；需要跨天 HLL 合并才能实现 |
| P-3 | 后台是否需要「列出全部短链」的接口 | v1 不做 | PRD 无此需求；一旦加就需要分页（§3.7） |
| P-4 | CORS 的具体来源白名单（v2） | v2 | §3.5；禁止 `*` 配合凭证 |
| P-5 | `Retry-After` 的取值来源（固定窗口剩余时间 vs 令牌桶预估等待） | 第 3 周 | 与限流算法实现绑定（DR-002） |
