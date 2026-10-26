# LinkForge 第 5 周设计文档（DESIGN-W5）

> 第 5 周主题：**监控、部署与终极压测**。
> 本文件记录第 5 周的设计决策与实测数据，其中 §2 是 8 小时长稳测试的完整报告。
>
> 相关文档：
> - [ARCHITECTURE.md §17](ARCHITECTURE.md) —— 监控栈的组件与拓扑
> - [PERFORMANCE.md §11](PERFORMANCE.md) —— 第 5 周压测的预注册框架与口径
> - [DECISIONS.md DR-061](DECISIONS.md) —— 推翻「Metaspace 泄漏」伪 bug 的正式决策
> - [PITFALLS.md](PITFALLS.md) —— 4 个 Docker 部署静默故障的防呆清单
> - [TROUBLESHOOTING.md](TROUBLESHOOTING.md) —— 20+ 条排障记录

---

## 1. 第 5 周的三条主线

| 主线 | 目标 | 状态 |
|---|---|---|
| **监控与部署** | `docker compose up -d` 一键起全套（App + MySQL + Redis + Prometheus + Grafana），凭据不落明文 | ✅ 完成 |
| **长稳排查** | 验证 DR-057 记录的「19 小时 Metaspace 涨到 74MB」是不是泄漏 | ✅ 完成（**证伪**） |
| **终极压测** | 与第 1/2/3 周**同口径**的最终数据 + 阶梯加压找拐点 | ✅ 完成（组 A / 组 C，组 B 有意不做） |

---

## 2. 8 小时长稳测试报告

### 2.1 测试目的与设计

**要回答的问题**：DR-057 记录「容器跑 19 小时后 Metaspace 涨到 74MB，
G1 频繁 evacuation，吞吐掉 50%+」，并怀疑是 **Micrometer 动态 Tag** 导致。

⚠️ **这个怀疑在第 5 周开始时被判定为「因果链不成立」**（理由见 §2.5），
但**不能只靠推理否定一个观察到的现象**——必须用数据说话。

**测试设计**：

| 项 | 值 | 为什么 |
|---|---|---|
| 时长 | 8 小时（15:09:30 → 23:09:30，采样至 23:16） | 覆盖一个完整工作日周期 |
| 流量 | `wrk -t2 -c10 -d8h` + `wrk-redirect.lua`（轮换 1 万条短码） | 持续有真实流量，但**不烤机器** |
| 语料 | 10,000 条真实短码 | 用 `wrk-redirect.lua` 而非裸 URL —— 否则 100% L1 命中、数据库完全不参与，测的不是真实负载 |
| 采样 | 每 30 分钟（17 个点）+ 每 60 秒（看护脚本） | 30 分钟看趋势，60 秒抓跳变 |
| 观测 | Prometheus（Micrometer）+ `jcmd`（每 2 小时） | 三条曲线缺一不可 |

**三条曲线**：

| 曲线 | 作用 |
|---|---|
| `jvm_memory_used_bytes{area="nonheap",id="Metaspace"}` | 怀疑对象 |
| `jvm_classes_loaded_classes` | ⭐ **最直接的证据**：已加载类数（单调递增） |
| `shortlink.meter.count` | 对照组：**动态 tag 涨的是堆，不是 Metaspace** |

### 2.2 关键节点数据

| 节点 | 时刻 | Metaspace | Classes | Meters | GC（young） | Full GC |
|---|---|---|---|---|---|---|
| **基线**（重启后） | 15:09 | **72.47 MB** | 14,946 | 197 | — | — |
| **2 小时** | 17:09 | **73.78 MB** | 14,995 | 199 | — | — |
| **4 小时** | 19:09 | **74.01 MB** | 14,996 | 199 | — | — |
| **8 小时** | 23:09 | **74.02 MB** | 14,996 | 199 | **14,388** | **0** |

**累计增长：+1.55 MB / 8 小时**（起点 72.47MB → 终点 74.02MB）。

⚠️ 终点 74.02MB 与 DR-057 记录的「19 小时后 74MB」**几乎完全一致** ——
这强烈提示 DR-057 记录的就是**本应用的正常稳态值**，而不是泄漏。

### 2.3 增量趋势：**已完全走平**

| 区间 | Metaspace 增量 | Classes 增量 | 速率 |
|---|---|---|---|
| 0 ~ 1h | **+1,090.0 KB** | +46 | 1.06 MB/h |
| 1 ~ 2h | +238.1 KB | +3 | 0.23 MB/h |
| 2 ~ 4h | +158.2 KB | +1 | 0.08 MB/h |
| 4 ~ 6h | +93.6 KB | +0 | 0.05 MB/h |
| **6 ~ 8h** | **+5.8 KB** | **+0** | **0.00 MB/h** |

逐 30 分钟的增量序列（KB）：

```
+863.2  +226.9  +112.9  +125.2  +82.4  +32.6  +39.4  +3.7
+35.8   +11.4   +30.8   +15.6   +4.2   +0.0   +1.6
```

**结论：增量单调收敛到 0。最后 2 小时合计只涨了 5.8 KB（约为起始基线的 0.008%）。**

⚠️ **这不是「线性增长」**。真正的泄漏是**恒定速率**的增长，
而这里是**单调递减并趋于零** —— 典型的「首次执行路径加载类 + 之后收敛」形态。

### 2.4 GC 与流量真实性

| 项 | 值 | 说明 |
|---|---|---|
| **GC 总次数** | **14,388** | 全部是 `G1 Evacuation Pause`（young GC），约 30 次/秒 |
| **Full GC** | **0** | `sum(jvm_gc_pause_seconds_count{action=~".*major.*"})` 返回**空** |
| **Metadata GC** | **1** | `cause="Metadata GC Threshold"` —— JVM 自己检测到元数据增长并回收了一次 |
| **PV 总数** | **369,536,164** | 8 小时 3.7 亿次跳转 |
| **平均 QPS** | **≈ 12,566** | 与压测档位 `-c10` 的预期一致 |
| **采集链路** | 1,861 次抓取 | `/actuator/prometheus` 每 15s 一次，8 小时不间断 |

⭐ **`cause="Metadata GC Threshold"` 恰好出现 1 次，是最有说服力的一条证据**：
JVM **自己**检测到元数据增长，触发了一次元空间专用 GC；
那次之后 Metaspace 的增量就完全走平了。
**说明 JVM 自身的元空间管理机制接管了这件事** —— 不是应用层泄漏。

### 2.5 ⚠️ 三处「我自己犯的错」——必须记录

本次排查过程中，**我的采样脚本犯了三个错误**，每一个都差点导致错误结论：

#### ① 「GC 恒为 1」——**差了 4 个数量级**

采样用 `extract_value` 取 Prometheus 返回的**第一条** series。
而 `jvm_gc_pause_seconds_count` 有**多条** series（每个 GC cause 一条）：

| cause | count |
|---|---|
| `G1 Evacuation Pause` | **14,387** |
| `Metadata GC Threshold` | 1 |

`r[0]`（按 label 排序第一条）恰好是 **`Metadata GC Threshold`**（count=1）。
于是日志里「8 小时只 GC 了 1 次」——**完全错误**。

> **教训**：**取多 series 查询的第一条，等于取一个与语义无关的值。**
> Prometheus 的 series 顺序由 label 排序决定，**与「哪个更重要」无关**。
> 判据：**要总数就用 `sum()`，要分类就逐条列出，不要用「第一条」。**

#### ② 「PV 累计 1,805」——**根本不是 PV**

采样用 `http_server_requests_seconds_count{uri=~"/.*"}` 的第一条 series，
而 `r[0]` 是 **`/actuator/prometheus`**（Prometheus 每 15s 抓一次）。
30 分钟 120 次 —— **正好是抓取频率**，所以数字看起来「很合理」，
但它与业务 PV 毫无关系。

真正的 PV 在 `uri=~"/{code}"`：**369,536,164**。

> **教训**：**「数字看起来合理」是最危险的验证方式。**
> 120 次/30 分钟与抓取频率完全吻合，说明它**自洽**，但自洽不等于正确。
> 判据：**指标必须显式限定维度**（`uri=~"/{code}"`），不能靠「取第一条」。

#### ③ 「Metaspace 增长 ≡ 累计加载了新类」——**推论过强，被自己的数据推翻**

我最初写下「`unloaded_total = 0` ⇒ Metaspace 增长 ≡ 累计加载了新类」，
理由是类一旦加载就永不卸载。

运行 1 小时后的实测**不支持**它：

```
[16:10:29] Metaspace=77168928 (+0.089MB)  Classes=+0  Unloaded=0
```

**Metaspace 涨了 93KB，而 `LoadedClassCount` 一点没涨。**
根因是 JDK 15+ 的 **hidden class（lambda 隐藏类）**——
它们**计入 Metaspace，但不计入 `LoadedClassCount`**。

修正后的表述（两层）：

| 层 | 结论 |
|---|---|
| ① | `unloaded_total` 恒为 0 → 类加载器从未被回收 → **已加载类数单调递增、不会回落** |
| ② | Metaspace 增长 = 已加载类 + **hidden class（lambda）** + 其它元数据 |

**但最终 8 小时数据进一步修正了这个判断**：
总增量 1.55MB / 50 类 = **31.7 KB/类**，**高于**普通类的 ~19KB/类基准
→ 说明**增量主体确实是类加载**，hidden class 的贡献很小
（后期那几 KB 才是）。

> **教训**：**「我修正过一次」不等于「修正到位了」。**
> 第二次修正（最终数据）推翻了第一次修正（hidden class 是主要嫌疑）的一部分。
> 每一层修正都要有数据支撑，不能停在「听起来合理」。

### 2.6 ⚠️ 未完成的部分（诚实记录）

| 项 | 状态 | 原因 |
|---|---|---|
| `jcmd VM.classloader_stats` / `GC.class_histogram` | ❌ **未执行** | 应用镜像是 **JRE**（`eclipse-temurin:17-jre-jammy`），**不含 JDK 工具**；宿主机的 jcmd（Windows）也无法 attach 到容器 JVM（PID namespace 不同 + 宿主是 Windows） |
| 跨容器 attach 方案 | ⚠️ **已设计但未实测** | compose 新增了 `jdk-tools`（`diagnose` profile，`nsenter` 进 app 的 mount namespace），但**实测直接 jcmd 会报** `Unable to open socket file /tmp/.java_pid1`（sidecar 有自己的 /tmp）；而 `nsenter` 路径需要重建 app 才会生效 → 会打断长稳曲线，**故意没做** |
| 具体类名分布 | ❌ 未获取 | 同上 |

⚠️ **但曲线证据已经足够**：8 小时的连续量数据（Metaspace / Classes / Meters）
比 jcmd 的一次性快照**更适合**回答「是否线性增长」这个问题。

### 2.7 长稳结论

> ### ✅ 判定：**不是泄漏**。DR-057 的「Metaspace 泄漏」是**伪 bug**。
>
> 1. **8 小时只涨 1.55 MB**，且**增量单调收敛到 0**（最后 2 小时 5.8 KB）。
>    泄漏是**恒定速率**增长，不是**单调递减**。
> 2. **已加载类数从 t=120m 起完全平稳**（14,995 / 14,996）——
>    没有持续加载新类。
> 3. **Meter 数从 t=30m 起恒为 199**（7.5 小时零增长）——
>    **动态 Tag 没有增长**，「Micrometer 动态 Tag 导致 Metaspace 泄漏」被直接证伪。
> 4. **零 Full GC**，14,388 次全是 young GC。
> 5. **JVM 自己触发了 1 次 `Metadata GC Threshold`** ——
>    说明元空间管理机制正常工作，不是应用层失控。
> 6. 终点 **74.02MB** 与 DR-057 记录的「19 小时后 74MB」**几乎一致** ——
>    那个数字是**稳态基线**，不是泄漏水位。

---

## 3. 监控栈（第 5 周阶段 1）

### 3.1 拓扑

```
                    ┌──────────────── linkforge_default 网络 ────────────────┐
  wrk（压测）  ──────┼──→ app:8080 ──→ redis:6379                            │
                    │      │                                                 │
  prometheus ───────┼──────┴──→ app:8080/actuator/prometheus                │
                    │                                                        │
  grafana ──────────┼──→ prometheus:9090                                    │
                    └────────────────────────────────────────────────────────┘
```

| 组件 | 版本 | 凭据 |
|---|---|---|
| Prometheus | 3.5.0 | Basic Auth（bcrypt 哈希进 git，明文在 secret） |
| Grafana | 12.1.0 | `GF_SECURITY_ADMIN_PASSWORD__FILE` + secret 挂载 |

⚠️ 全部**声明式**（provisioning 文件），不点 UI —— 手工配的东西只存在 Grafana 的数据库里。

⚠️ **`http.server.requests` 的 `path` tag 必须保持关闭**：
`uri` 是 URI 模板（`/{code}`），基数固定；
`path` 是实际路径（`/0000Q0u`）→ 每个短码一个新 tag → meter 数线性增长 → **堆涨 + Prometheus series 爆炸**。
本项目的短码**每条短链唯一**，`path` 的基数 = 短码总数 = **无界**。

### 3.2 核心看板（5 个指标）

| # | 指标 | 作用 |
|---|---|---|
| 1 | 跳转 QPS | 一切的总入口；突降 = 挂了或被限流 |
| 2 | P99 延迟 | 与第 2/3 周直接可比 |
| 3 | L1 缓存命中率 | 第 2 周的核心成果，掉了说明缓存被绕过 |
| 4 | 限流拒绝数 | 压测时必须为 0；生产非 0 = 有人在刷 |
| 5 | 错误率（5xx / 4xx 分开） | 5xx 是硬故障；4xx 含正常的 404/410，必须分开看 |

附加：JVM 内存（Metaspace/Heap/Code Cache）、已加载类数 + GC 次数、**meter 数量**（长稳排查）。

---

## 4. 终极压测（第 5 周阶段 4，✅ 已完成）

> ✅ **实测结果见 `docs/PERFORMANCE.md` §11.4（组 A）/ §11.6（组 C）与 §13（收尾结论）**。
> 组 B（调优版）**有意未跑**，理由见 `docs/PERFORMANCE.md` §11.5。

### 4.1 三个防线

1. **停掉 Prometheus/Grafana** —— `/actuator/prometheus` 每次被访问都要
   **遍历并序列化全部 meter**，8 万 QPS 下是显著 CPU 开销。
   ⚠️ 这是前三周**不存在**的污染源。
2. **重启 app** —— 清 Metaspace 与 GC 状态（DR-057 的教训）。
3. **等布隆全量重建完成** —— 否则前几千个请求全 miss，拉高 P99。

### 4.2 组别与判据

| 组 | 配置 | 可比对象 |
|---|---|---|
| **A：同配置基线** | JVM 参数与第 3 周**一字不差** | 第 1/2/3 周 |
| **B：调优版** | 改了 JVM 参数 / 代码 | 仅组 A |
| **C：阶梯加压** | 固定 `-c50`，只加 `-t` | 仅自身（找拐点） |

**组 A 的判据（预注册，跑完不改）**：

```
QPS ≥ 25,000 且 P99 ≤ 15ms 且零错误  -> ✅ 通过
QPS 20,000~25,000                    -> ⚠️ 基本通过（需归因）
QPS < 20,000                         -> ❌ 不通过
任何非 302 / Socket 错误             -> ❌ 不通过（错误优先于性能）
```

⚠️ **本机物理上限约 3.2 万 QPS**（18 核 − 宿主固有 3 核 − 压测端 1.5 核，
每请求 417 µs CPU），所以预期区间是 **25,000 ~ 32,000**。

> **本阶段的目标是「验证加入监控后性能无明显衰减」，而非突破物理极限。**

---

## 5. 本周产出

| 类别 | 内容 |
|---|---|
| 部署 | `docker compose up -d` 一键起 5 个容器；3 个一键脚本（`setup-local.sh` / `gen-prometheus-hash.sh` / `reset-grafana-datasource.sh`） |
| 监控 | Prometheus + Grafana 全声明式；`shortlink.meter.count` Gauge |
| 文档 | 本文件、`PITFALLS.md`（防呆清单）、`RESUME-DRAFT.md`（简历素材） |
| 决策 | DR-060（过期清理）、**DR-061（推翻 Metaspace 泄漏伪 bug）** |
| 排障 | `TROUBLESHOOTING.md` 新增 T-015 ~ T-026（12 条）；第 6 周收尾再补 T-027 / T-028 |
| 工具 | `final-bench.sh`（含自动判定）、`staircase-bench.sh`、`longrun-{load,collect,watch}.sh` |
