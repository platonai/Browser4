# E2E / E2ETest 标记的 10 个类：稳定性评估与接入建议

> 评估时间：2026-09（4.14.x）。触发原因：`docs/TESTING.md` 的覆盖盘点发现这 10 个类 / 82 个方法
> **在任何 workflow 都不执行** —— nightly / ci / pr 都排除 `E2E` 与 `E2ETest` 两个 tag，
> 而没有任何 workflow 传 `-DrunE2ETests=true`。本文回答一个问题：**能不能把它们接回门禁，接回哪里。**

## 1. 分类盘点

| # | 类 | 方法 | 现有 tag | 实际依赖 | 实测（本地，2 次采样） | 结论 |
|---|---|---|---|---|---|---|
| 1 | `Browser4MCPServerE2ETest`（browser4-agentic） | 14 | `E2ETest`,`mcp` | **无**：纯 mockk（mock `ToolExecutor`）+ 内存 MCP server，不起 Spring、不开浏览器 | **2.8 s / 0 失败** | **tag 名不副实**：应改标 `Unit`+`Fast`，让它在 PR 门禁里跑（见 §3.1） |
| 2 | `HtmlSnapshotScenariosE2ETest`（rest-tests） | 33 | `E2ETest` | Spring Boot 自启 + mock 电商站 + 真实 Chrome（会话/driver 切换/双世界捕获） | **481 s / 0 失败**，第二次 **388 s / 0 失败** | 稳定但**极重**（单类 6.5–8 分钟），只适合 nightly/按需 job |
| 3 | `StorageStateCookiePathE2ETest`（rest-tests） | 4 | `E2ETest` | 同上（cookie path 语义，Chrome `Network.setCookies`） | **41.3 s / 0 失败**，第二次 **41.4 s / 0 失败** | 稳定，可进 nightly |
| 4 | `SwarmControllerE2ETest`（rest-tests） | 5 | `E2ETest` | 同上 + swarm 会话 | **2.9 s / 0 失败**，第二次 **2.6 s / 0 失败** | 稳定，可进 nightly |
| 5 | `CommandControllerE2ETest`（rest-tests） | 4 | `E2ETest`,`ManualOnly`,`Slow` | Spring + Chrome，命令通道 | — | `ManualOnly`：属人工触发，保持现状 |
| 6 | `Browser4WebDriverE2ETest`（browser4-browser） | 2 | `E2E`,`ManualOnly`,`RequiresBrowser` | 真实 Chrome，GUI 语义 | — | `ManualOnly`，保持现状 |
| 7 | `AgentE2ETest`（browser4-e2e-tests） | 2 | `E2ETest`,`ManualOnly`,`Slow` | 类级 `@Disabled("ManualOnly")` | 恒跳过 | 名义存在；建议注释里写清手动运行方式 |
| 8 | `SkillInstallE2ETest`（browser4-e2e-tests） | 2 | `E2ETest`,`ManualOnly`,`Slow` | 类级 `@Disabled("ManualOnly")` | 恒跳过 | 同上 |
| 9 | `SkillRegistrationAndInvocationE2ETest`（browser4-e2e-tests） | 1 | `E2ETest`,`RequiresAI`,`skills` | 需要真实 LLM 密钥 | — | `RequiresAI`：nightly 也排除，保持现状 |
| 10 | `MCPToolControllerE2ETest`（rest-tests） | 18 | `E2ETest`,`RequiresAI` | MCP 工具契约，部分路径需要真实 LLM | — | 保持现状；其中不依赖 AI 的子集可另行拆分（未做） |

## 2. 结论：**不要**在 nightly 里放开 `E2E,E2ETest` 排除

理由（全部可量化）：

1. **放开也只会多跑 4 个类**：10 个类里有 6 个被 `ManualOnly`（3 个）、`RequiresAI`（2 个）或类级
   `@Disabled`（2 个，与 ManualOnly 重叠）挡住，共 26 个方法无论如何都不会执行。
   真正会因放开 tag 而开始执行的是 **#1–#4：14 + 33 + 4 + 5 = 56 个方法**。
2. **其中 3 个类是"Spring + 真实 Chrome"的重测试**，`HtmlSnapshotScenariosE2ETest` 单类约 **8 分钟**。
   直接塞进 nightly 的 Maven 阶段（75 分钟预算，已含 Slow/TestInfra 与 JaCoCo 开销）会明显抬高
   超时风险，而超时会让整个 reactor 被 kill（那正是 nightly 反复踩过的坑）。
3. **收益与成本不匹配**：这 3 个类覆盖的路径（会话生命周期、storage state、htmlsnapshot 抓取）
   在 CLI e2e 的 204 个场景里已从用户侧覆盖；JVM 侧再加 8 分钟换来的边际信息很少。
4. **稳定性样本不足**：本地 2 次采样都通过，但都在 Windows + 本机 Chrome 上；CI 是 Linux + Docker
   后端 + 无头 Chrome，历史经验（nightly-cli.yml 的注释）说明同一套 e2e 在不同平台差异很大。
   **未在 CI 配置上验证过。**

## 3. 建议处置（按性价比排序）

### 3.1 `Browser4MCPServerE2ETest`：改标 `Unit` + `Fast`（收益最大、风险最低）
它是 mockk 驱动的 MCP 协议测试，不起 Spring、不开浏览器、2.8 s 跑完 14 个方法
—— 现在的 `E2ETest` tag 只是历史命名，导致它被所有门禁排除。
改标后它立刻回到 PR 门禁（14 个方法从"永不执行"变成"每次 PR 执行"）。

### 3.2 `HtmlSnapshotScenarios` / `StorageStateCookiePath` / `SwarmController`：按需 job，不要并入主阶段
保持 `E2ETest` tag，但接入方式应为**独立 job**（`timeout-minutes: 40`，`-Dtest=<三个类>`，
`-Dsurefire.excludedGroups=ManualOnly,RequiresAI`），先跑 N 轮观察，再决定是否并入 nightly 主阶段。
不建议直接放开 tag 排除。

### 3.3 其余 6 个类：保持现状，但把"人工触发方式"写进注释
`@Disabled("ManualOnly")` 的两个类（#7、#8）目前连"怎么手动跑"都没写；补一行命令即可，
避免它们长期当死代码。

### 3.4 把"为什么现在不放开"留档
即本文 §2 的量化依据，避免下一轮又有人提议"直接放开 E2E/E2ETest"。

## 4. 采样证据

| 类 | Run 1（本地，2026-09-24） | Run 2（同日，第二轮） |
|---|---|---|
| `HtmlSnapshotScenariosE2ETest` | 33 例 / 0 失败 / 481.3 s | 33 例 / 0 失败 / 388.2 s |
| `StorageStateCookiePathE2ETest` | 4 例 / 0 失败 / 41.3 s | 4 例 / 0 失败 / 41.4 s |
| `SwarmControllerE2ETest` | 5 例 / 0 失败 / 2.9 s | 5 例 / 0 失败 / 2.6 s |
| `Browser4MCPServerE2ETest` | — | 14 例 / 0 失败 / 2.8 s |

两轮合计 **56 例 / 0 失败**（`BUILD SUCCESS`）。`HtmlSnapshotScenariosE2ETest` 的两次时长差 93 s
（481 → 388）说明它的耗时对机器负载敏感，这也是它不适合塞进有硬预算的主阶段的另一个理由。

运行方式（两轮相同，Windows + 本机 Chrome，无 Docker 后端）：

```powershell
.\mvnw.cmd -B -Pall-main-modules,all-test-modules -DrunITs=true `
  "-Dsurefire.excludedGroups=ManualOnly,RequiresAI" `
  "-Dtest=HtmlSnapshotScenariosE2ETest,SwarmControllerE2ETest,StorageStateCookiePathE2ETest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## 5. 还没做

* **未在 CI（Linux + Docker + headless Chrome）上验证**：两轮采样都是本地 Windows。
  CI 上的时长通常更长（本地 481 s 的类在 CI 上可能 8–12 分钟），这是 §3.2 建议"先按需 job 观察"的原因。
* **`MCPToolControllerE2ETest`（18 个方法）未拆分**：其中一部分只验证 MCP 契约、不需要 LLM，
  理论上可以拆出来进 PR 门禁，但需要逐方法核对。
* **`browser4-tests/browser4-e2e-tests` 整个模块**（#7–#9，5 个方法）除一个 RequiresAI 外全部
  `@Disabled` 或 `ManualOnly`，当前等价于死模块；要么补上手动运行说明，要么评估删除。
