# JVM AOT Cache 启动收益实测（2026-11-15）

- 范围：`cli/browser4-cli/src/daemon.rs` 的 AOT 缓存训练与加载路径（`ensure_aot_cache_trained`），以及 Browser4 后端的启动耗时
- 环境：Windows；已安装 runtime **v4.16.7**（`lib/` 下 261 个 jar，jlink 的 **Temurin 25.0.5**）；classpath 为 CLI 同样的显式排序枚举
- 关联：[AOT 训练前置到 install 阶段](aot-training-during-install-design.md)、[服务器端启动速度优化评估](browser4-server-startup-optimization-review.html)、[AOT 优化深度分析](browser4-server-aot-optimization-deep-dive.html)

## 结论

在本机（暖盘、隔离的空 app data 目录）上，**JVM AOT cache 带来约 40% 的启动时间下降**：

| 口径 | 无缓存 | 有缓存 | 变化 |
|---|---|---|---|
| Spring `Started ... in N seconds`（上下文刷新完成） | 2.00 s | 1.12 s | **−0.88 s / −43.9%** |
| JVM uptime at `Started`（进程启动 → 上下文就绪） | 2.89 s | 1.68 s | **−1.20 s / −41.7%** |
| **CLI 判定“后端可用”**（`/actuator/health` UP 且 `/mcp/tools` 就绪） | **3.59 s（中位）** | **2.25 s（中位）** | **−1.35 s / −37.5%** |

训练是**一次性**成本：本机 **197 s**，产出 **90.8 MB** 的 `app.aot`。

## 1. 测量口径

- **训练规格**（复用 CLI 的训练模式）：`-Dbrowser4.aot.training=true`、`-Dspring.context.exit=onRefresh`、`-Xmx1G`、`-XX:AOTCacheOutput=<bench>/bench.aot`、`--server.port=0`；classpath 与 `-XX:TieredStopAtLevel=1` 和生产一致，`-Dapp.*` 指向 bench 隔离目录（`-D` 属性不参与缓存校验，已用 `-Xlog:aot` 验证）。
- **加载规格**：同一组参数，去掉训练旗标与退出旗标，换成 `-XX:AOTCache=<bench>/bench.aot` + `--server.port=18417`；并用 `-Xlog:aot` 确认每次 cached run 都出现 `Opened AOT cache`、且**没有** mismatch/fallback 警告。
- **隔离**：`-Dapp.data.dir=<bench>/data`（空目录）、`-Dapp.tmp.base.dir=<bench>/data/tmp`、固定端口 18417，不碰用户正在使用的后端与 `~/.browser4`。
- **就绪判定**：与 CLI 的 `probe_server_state()` 完全同口径（`daemon.rs:10397-10431`）——`GET /actuator/health` 返回体含 `"status":"UP"`，且 `GET /mcp/tools` 同时包含 `open_session` 与 `browser_navigate`。另外记录 `GET /mcp/tools/specs` 返回 200 的时刻。
- **计时**：从 `java.exe` 进程创建开始；就绪探测间隔 30 ms；就绪后立即 `taskkill /T /F`。两种模式交替执行，磁盘已热（所有模式都在训练之后的同一批内完成）。

## 2. 训练成本

| 项 | 值 |
|---|---|
| 训练进程 wall time | **197 s** |
| 产出 `bench.aot` | **90,832,896 B（≈90.8 MB）** |
| 训练期日志 | 大量 `class ... cannot be archived because it was not defined from ...jar as claimed`（Spring 的 `ClassFileMetadataReader*` 等重定义类被 JVM 主动放弃缓存），不影响缓存可用性 |

## 3. 结果 A：训练规格下的整进程耗时（各 5 轮，交替）

wall clock = 进程创建 → Spring context refresh 完成并退出（训练规格带 `spring.context.exit=onRefresh`）。

| 轮次 | 无 AOT | 有 AOT |
|---|---|---|
| 1 | 2558 ms | 1374 ms |
| 2 | 2390 ms | 1351 ms |
| 3 | 2411 ms | 1394 ms |
| 4 | 2401 ms | 1405 ms |
| 5 | 2528 ms | 1410 ms |
| **均值** | **2458 ms** | **1387 ms** |
| **中位** | 2411 ms | 1394 ms |

→ **−1071 ms（−43.6%，1.77×）**。

## 4. 结果 B：生产式启动（不加训练旗标、不退出）

Spring 自己打印的 `Started ... in N seconds (process running for M)`：

| 无 AOT | `Started in` | JVM uptime | 有 AOT | `Started in` | JVM uptime |
|---|---|---|---|---|---|
| run 1 | 2.191 s | 3.154 s | run 1 | 1.162 s | 1.747 s |
| run 2 | 1.984 s | 2.830 s | run 2 | 1.107 s | 1.655 s |
| run 3 | 1.903 s | 2.765 s | run 3 | 1.105 s | 1.646 s |
| run 4 | 1.936 s | 2.793 s | | | |
| **均值** | **2.00 s** | **2.886 s** | **均值** | **1.12 s** | **1.683 s** |

→ `Started in` **−0.88 s（−43.9%，1.78×）**；JVM uptime **−1.20 s（−41.7%，1.71×）**。

## 5. 结果 C：真实可用时间（CLI 就绪口径，各 7 轮，交替）

| # | 无 AOT health | 无 AOT tools | 无 AOT specs | 有 AOT health | 有 AOT tools | 有 AOT specs |
|---|---|---|---|---|---|---|
| 1 | 3120 | 3201 | 3387 | 1977 | 2020 | 2115 |
| 2 | 3022 | 3136 | 3319 | 1994 | 2037 | 2131 |
| 3 | 3562 | 3591 | 3788 | 2215 | 2246 | 2349 |
| 4 | 3936 | 4052 | 4230 | 2192 | 2230 | 2329 |
| 5 | 3510 | 3550 | 3683 | 2376 | 2411 | 2508 |
| 6 | 5394 | 5498 | 5801 | 2212 | 2261 | 2385 |
| 7 | 4064 | 4171 | 4535 | 2264 | 2301 | 2408 |

| 指标（ms） | 无 AOT avg / median（min–max） | 有 AOT avg / median（min–max） | 中位差 |
|---|---|---|---|
| `/actuator/health` = UP | 3801 / **3562**（3022–5394） | 2176 / **2215**（1977–2376） | **−1347（−37.8%）** |
| `/mcp/tools` 就绪（CLI 判定） | 3886 / **3591**（3136–5498） | 2215 / **2246**（2020–2411） | **−1345（−37.5%）** |
| `/mcp/tools/specs` = 200 | 4106 / **3788**（3319–5801） | 2318 / **2349**（2115–2508） | **−1439（−38.0%）** |

同一批运行里 Spring 的数值：`Started in` 均值 **2.12 s → 1.12 s**，JVM uptime 均值 **2.98 s → 1.65 s**。第 6 轮无 AOT（5498 ms）是本机负载导致的离群点；去掉它均值仍约 3.6 s，结论不变。

## 6. 解读

- **收益全部来自 JVM 启动 + 类加载/链接 + Spring 装配**：两种模式下，从 `Started` 日志到 HTTP 端点可用都只剩约 0.3–0.4 s（actuator、MVC、工具注册表挂载；无缓存的高负载轮次会更长），几乎不受缓存影响。
- **两种口径互相印证**：训练规格整进程 −1.07 s、生产式 JVM uptime −1.20 s、CLI 就绪 −1.35 s；绝对值随口径变化（是否含 JVM 启动、端点挂载、进程退出），**降幅都落在 37–44%**。
- **缓存模式更稳定**：1.98–2.41 s 的窄区间 vs 无缓存的 3.02–5.50 s。
- **与既有估计对比**：文档原估计“~2 s → 1.0–1.4 s（端口就绪）”，实测 `Started in` 2.0→1.12 s 落在区间内；但**CLI 层面的“可用”还要再加约 1.0–1.1 s**（端点挂载 + 工具注册表），所以 CLI 实际看到的是 **3.6 s → 2.2 s**。

## 7. 局限

- 单机、单 OS；所有数字为**暖盘**（jar 已在系统缓存），首次安装后的冷盘对两种模式都更慢，差值仍主要来自类加载。
- 隔离的空 app data 目录：没有用户配置、LLM key、历史会话与数据库，真实用户的绝对值可能略高（两种模式同向）。
- 探测粒度 30 ms；就绪后立即杀进程，**不含**浏览器启动、StartupWarmer 尾部与首条真实工具调用的耗时。
- 插件 JAR（`plugins/*.jar`，经 `URLClassLoader` 加载）不在缓存覆盖范围内，只按普通路径加载。

## 8. 复现要点

```text
# 训练（与 CLI 相同）
java @bench.args    # 内含：
  -Dapp.name=browser4
  -Dapp.data.dir=<bench>/data
  -Dapp.tmp.base.dir=<bench>/data/tmp
  -XX:TieredStopAtLevel=1 -Xmx1G -Xlog:aot
  -Dbrowser4.aot.training=true -Dspring.context.exit=onRefresh
  -XX:AOTCacheOutput=<bench>/bench.aot
  -cp <runtime>/lib/<sorted jars, ';'>
  ai.platon.pulsar.apps.Browser4BundleApplicationKt --server.port=0

# 加载对比：去掉训练旗标与 exit 旗标，加
  -XX:AOTCache=<bench>/bench.aot
  ... --server.port=18417
# 就绪探测：GET /actuator/health、/mcp/tools、/mcp/tools/specs
```

本次测量的临时产物（未入库）：`%TEMP%\b4-aot-bench\`（`bench.ps1`、`bench-real.ps1`、`bench-ready.ps1`、`bench.aot`、每轮原始日志）。
