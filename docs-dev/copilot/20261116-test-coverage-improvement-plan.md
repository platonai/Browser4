# 测试覆盖率提升方案（4.16.x）

> 目的：把"提高测试覆盖率"从一句口号变成一份**可排期、可验收、防倒退**的清单。
> 本文只给方案，不含代码改动。
>
> 数据来源：Nightly Build [run 43806470273](https://github.com/platonai/Browser4/actions/runs/43806470273)
> （4.16.x @ `63e471dac9`，全绿，5112 个 JVM 测试 + 1007/2220 个 CLI 单测 + 245 个 CLI e2e 场景）。
> 覆盖率数字取自该 run 的 `test-reports-43806470273` artifact 中 13 份
> `*/target/site/jacoco/jacoco.csv`，按 `LINE_MISSED` / `LINE_COVERED` 聚合。
>
> 写作日期：2026-11-16 · 复现步骤见 §9

---

## 1. 现状

| 指标 | 实测 | 仓库目标（AGENTS.md "Definition of Done"） |
|---|---:|---|
| 全局行覆盖 | 65.8% | ≥70% |
| 全局指令覆盖 | 62.5% | — |
| 全局分支覆盖 | 52.0% | — |
| 参与统计的模块 | 13 个 | — |
| 实测类 | 1,673 个 | — |
| 未覆盖行合计 | 19,212 行 | — |

分模块（行覆盖为 `LINE_COVERED / (LINE_COVERED + LINE_MISSED)`）：

| 模块 | 指令% | 分支% | 行% | 执行行数 | 补到 80% 需再覆盖 |
|---|---:|---:|---:|---:|---:|
| `browser4-core/browser4-protocol` | 31.5 | 29.3 | **38.2** | 2,681 | +1,121 |
| `browser4-core/browser4-skeleton` | 45.2 | 37.9 | **46.7** | 7,831 | +2,611 |
| `browser4-core/browser4-common` | 54.0 | 46.2 | 46.2 | 299 | +101 |
| `browser4-core/browser4-browser` | 45.7 | 48.2 | **55.3** | 2,423 | +598 |
| `browser4-tests/pulsar-tests-common` | 65.8 | 48.2 | 63.8 | 780 | +126 |
| `browser4-boot` | 62.7 | 55.8 | 64.0 | 577 | +93 |
| `browser4-rest` | 63.0 | 51.1 | 66.2 | 9,536 | +1,313 |
| `browser4-agent-tools` | 63.0 | 56.7 | 69.1 | 3,047 | +334 |
| `browser4-agentic` | 68.7 | 55.8 | 72.7 | 20,062 | +1,457 |
| `browser4-coding` | 75.2 | 61.4 | 77.7 | 8,950 | +204 |
| `browser4-core/browser4-parse` | 90.5 | n/a | 93.3 | 15 | 达标 |
| `browser4-tests/browser4-rest-tests` | 0.0 | 0.0 | 0.0 | 17 | 测试模块，自身不计 |
| `browser4-tests/browser4-e2e-tests` | n/a | n/a | n/a | 0 | 无代码 |

> 说明：`browser4-runtime` / `browser4-apps/browser4-standalone`（bundle）天然 0 覆盖——
> 它们自己的 main 类只在别的模块的测试 JVM 里被执行，`docs/TESTING.md` 已记录这一点，
> 目标是**排除**而不是补测。

---

## 2. 为什么现在的数字不能直接当基线

这是本文最重要的一节。**不先做 §4 Phase 0，后面所有目标都会定错。**

### 2.1 覆盖率按模块各自结算，不合并跨模块执行数据

`quality-gate` profile 的 `prepare-agent` 把 agent 挂到每个模块自己的 surefire fork 上，
`report` 又绑在该模块的 `test` 阶段——所以在 `browser4-tests/browser4-rest-tests` 里
跑 E2E 时执行到的**被测模块字节码**，只落在测试模块自己的 `jacoco.exec` 里；被测模块的
报告在 reactor 更早的阶段就已经用它自己的 exec 生成完了。

实测证据（这些类显示 0%，但确实在被执行）：

| 类 | 模块报告里的未覆盖行 | 实际在哪被执行 |
|---|---:|---|
| `InteractiveBrowserEmulator` | 479（**0%**） | `browser4-tests/.../CaptureIgnoreDomFeaturesE2ETest` |
| `LoadComponent` | 308（**0%**） | 3 个 `browser4-rest-tests` E2E 类 |
| `BrowserEmulatorImplBase` | 178（**0%**） | e2e 导航路径 |

结论：`browser4-protocol` 的 38.2%、`browser4-skeleton` 的 46.7% **被系统性低估**。
先合并口径，再谈补测。

### 2.2 三个测量盲区

1. **6 个插件模块不计覆盖率**：`browser4-plugins/browser4-{media,images,pptx,markdown,swarm,profile-import}`
   的 parent 是 `browser4-pdk` 而不是根 POM，拿不到 `quality-gate` profile——测试照跑，
   但既不产数据也不受下限约束（`docs/TESTING.md` 已有记录）。
2. **Rust CLI 完全没有覆盖率数据**：仓内无 `cargo llvm-cov` / `tarpaulin` / `codecov` 任何接入，
   而 CLI 已有 3,227 个单测 + 245 个 e2e 场景。
3. **汇总报告从未产出**：根 `pom.xml` 的 `ci` profile 里已有 `jacoco:report-aggregate`
   （`verify` 阶段），但**没有任何 workflow 调用它**，所以"全仓一份报告"这件事一直是空的。

### 2.3 门禁基本空转

- PR 门禁（`.github/workflows/pr.yml`）跑 `-Pall-main-modules,quality-gate`，规则是
  `element=BUNDLE, counter=INSTRUCTION, COVEREDRATIO ≥ 0.20`——**每个模块都远超 0.20**，
  等于没有下限；
- nightly 用 `-Djacoco.check.skip=true`，纯观测；
- `E2E` / `E2ETest` 标注的测试只在 nightly 跑（`docs/TESTING.md`：5 个类 / 64 个方法 / 实测 ~9 min），
  PR 作者拿不到这部分反馈。

---

## 3. 未覆盖行的结构

### 3.1 两类缺口，两种策略

| 类型 | 类数 | 未覆盖行 | 占比 | 对症策略 |
|---|---:|---:|---:|---|
| **从未执行**（`LINE_COVERED = 0`） | 440 | 6,553 | 34% | 先判"是没测"还是"没算"（§2.1），再补第一个测试 |
| **部分覆盖**（有执行、分支/边界缺失） | 534 | 12,659 | 66% | 在有测试的类上补边界/异常/失败路径 |

从未执行按模块分布：

| 模块 | 从未执行类数 | 未覆盖行 |
|---|---:|---:|
| `browser4-core/browser4-skeleton` | 142 | 2,314 |
| `browser4-core/browser4-protocol` | 44 | 1,055 |
| `browser4-rest` | 73 | 951 |
| `browser4-agentic` | 75 | 835 |
| `browser4-coding` | 31 | 610 |
| `browser4-agent-tools` | 33 | 485 |
| `browser4-core/browser4-browser` | 33 | 193 |
| 其他（含 `pulsar-tests-common` / `browser4-boot`） | 9 | 110 |

### 3.2 头部集中：Top-50 类 = 55.7% 的未覆盖行

10,708 / 19,212 行集中在 50 个类里。**不需要全仓扫荡，盯住这几十个类就有大头。**

未覆盖行最多的 25 个类（`未覆盖/已覆盖` 行）：

| # | 模块 | 类 | 未覆盖 | 已覆盖 |
|---:|---|---|---:|---:|
| 1 | agentic | `agents.RobustBrowserAgent` | 873 | 70 |
| 2 | browser | `chrome.Browser4WebDriver` | 747 | 118 |
| 3 | agentic | `tools.builtin.BrowserTabToolExecutor` | 528 | 765 |
| 4 | protocol | `emulator.impl.InteractiveBrowserEmulator` | 479 | **0** |
| 5 | rest | `api.service.crawl.CrawlRoundRunner` | 430 | 8 |
| 6 | skeleton | `loop.impl.StreamingTaskRunner` | 401 | 96 |
| 7 | agentic | `tools.builtin.CodingToolExecutor` | 400 | 1167 |
| 8 | rest | `mcp.controller.MCPToolController` | 337 | 546 |
| 9 | rest | `api.service.crawl.CrawlService` | 315 | 540 |
| 10 | skeleton | `workflow.component.LoadComponent` | 308 | **0** |
| 11 | skeleton | `session.AbstractPulsarSession` | 283 | 96 |
| 12 | rest | `session.PulsarSessionManager` | 265 | 540 |
| 13 | skeleton | `skeleton.CoreMetrics` | 255 | **0** |
| 14 | coding | `coding.CodingAgentShell` | 232 | 776 |
| 15 | agentic | `inference.action.ContextToAction` | 220 | 51 |
| 16 | agentic | `tools.AgentToolManager` | 212 | 260 |
| 17 | rest | `api.controller.DoctorController` | 205 | **0** |
| 18 | agent-tools | `advanced.agent.StatefulAgentRunner` | 198 | 446 |
| 19 | coding | `coding.CodingAgentFileSystem` | 195 | 865 |
| 20 | agentic | `agents.RobustBrowserAgent.CliLoopTracer` | 192 | **0** |
| 21 | coding | `coding.VerificationRunner` | 187 | **0** |
| 22 | protocol | `emulator.impl.BrowserEmulatorImplBase` | 178 | **0** |
| 23 | agentic | `skills.SkillDefinitionLoader` | 171 | 350 |
| 24 | agent-tools | `advanced.crawl.StatefulPageVisitor` | 171 | **0** |
| 25 | agentic | `agents.BasicBrowserAgent` | 167 | 35 |

### 3.3 包级热点（未覆盖行最多的 10 个包）

| 模块 | 包 | 未覆盖 | 已覆盖 |
|---|---|---:|---:|
| coding | `ai.platon.pulsar.coding` | 1,576 | 6,638 |
| agentic | `ai.platon.pulsar.agentic.agents` | 1,316 | 804 |
| agentic | `ai.platon.pulsar.agentic.tools.builtin` | 1,135 | 3,191 |
| browser | `ai.platon.pulsar.chrome` | 952 | 458 |
| rest | `ai.platon.pulsar.rest.api.service.crawl` | 923 | 1,558 |
| protocol | `ai.platon.pulsar.protocol.browser.emulator.impl` | 870 | **50** |
| rest | `ai.platon.pulsar.rest.api.controller` | 593 | 979 |
| skeleton | `ai.platon.pulsar.loop.impl` | 566 | 159 |
| rest | `ai.platon.pulsar.rest.mcp.controller` | 550 | 1,272 |
| agentic | `ai.platon.pulsar.agentic.inference` | 463 | 369 |

---

## 4. 实施方案

### Phase 0 · 让数字可信（约 2~3 人日，最高优先级）

**动作**

1. 合并全仓 `jacoco.exec`：收集所有模块（含 `browser4-tests/*`）的 exec → `jacoco:merge`
   → `report-aggregate`，产出**一份**全仓报告；复用根 `pom.xml` 里 `ci` profile 已有的
   `report-aggregate` execution，而不是新造机制。
2. 明确排除与标注：测试支撑模块本身（`pulsar-tests-common`、`browser4-rest-tests`）、
   bundle、生成代码（`ToolDocGenerator` 产出）、纯常量 companion。
3. 把 per-class `jacoco.csv` 作为 artifact 留存，并在 step summary 输出
   "模块 × Top10 未覆盖类"，让排期有据可查。

**验收**：报告覆盖 13 + 6 个模块；`protocol` / `skeleton` / `browser` / `rest` 的百分比
出现明显上跳（预计 +8~15 个百分点，来自 §2.1 的跨模块执行）；`InteractiveBrowserEmulator`、
`LoadComponent` 不再是 0%。

**为什么排第一**：当前基线偏低是测量缺陷造成的，先补测等于替测量问题买单。

### Phase 1 · 门禁从"观赏"变"能防倒退"（约 3~4 人日）

**动作**

1. **用真实基线替换 0.20**：按模块设 floor（实测行覆盖 −1~2% 缓冲），并做 **ratchet**
   （只升不降，基线随合并 PR 更新）。
2. PR 门禁增加 **diff coverage（新增/改动行 ≥80%）**。这比全局 floor 有效得多：大模块的
   0.5% 波动会被稀释，而"改 200 行只测 3 行"会被直接抓住。
3. nightly 保持 observe-only，但增加**回归告警**：相对 `main` 基线下降 >0.5% 即报告（不判红）。
4. 让 E2E 覆盖率进入日常信号：二选一——
   (a) 抽一个 `E2E:Fast` 子集（几十个快场景）下沉到 PR 门禁；
   (b) 至少保证 Phase 0 的合并让 E2E 执行到的代码在 nightly 汇总里可见。

**验收**：人为提交一个"只改生产代码、不写测试"的 PR，diff coverage 规则必须变红；
一个模块覆盖率下降 1% 时 ratchet 必须变红。

### Phase 2 · 按 ROI 补测（4~6 周，分三档）

#### A 档｜纯单元、无浏览器、收益最高（≈2,000 行）

| 目标 | 包 / 类 | 未覆盖行 | 备注 |
|---|---|---:|---|
| 交互/导航状态机 | `protocol...emulator.impl`（`InteractiveBrowserEmulator` 479） | 870 | 5.4%，`waitForJavascriptInjected` / `waitForDocumentSettle` / 注入超时分支都可 mock CDP |
| 任务循环 | `skeleton.loop.impl`（`StreamingTaskRunner` 401） | 566 | 状态机 + 取消/超时 |
| REST 控制器（目标 ≥85%） | `rest.api.controller`（`DoctorController` 205，**0 测试**） | 593 | 控制器是 AGENTS.md 明确的 85% 目标区 |
| MCP 分发/别名/参数归一化 | `rest.mcp.controller`（`MCPToolController` 337） | 550 | 纯映射逻辑，表驱动测试性价比最高 |
| 纯函数解析 | `agentic.inference`（`ContextToAction` 220） | 463 | 无 LLM、无浏览器 |

共性：参数校验、错误分支、映射表——**表驱动就能覆盖**，且能在 PR 的 fast/unit 门禁里跑。

#### B 档｜需 fixture 页 + 真浏览器（≈2,100 行）

| 目标 | 类 | 未覆盖行 | 做法 |
|---|---|---:|---|
| 驱动层 | `chrome.Browser4WebDriver` | 747 | 复用 `pulsar-tests-common` 的 `static/b4/` fixture + e2e harness |
| 内置工具 | agentic `tools.builtin`（`BrowserTabToolExecutor` 528 / `CodingToolExecutor` 400） | 1,135 | 按 tool 的 `execute()` 参数与失败路径补 |
| 有状态访问 | `agent-tools...advanced.crawl`（`StatefulPageVisitor` 171，0%） | 352 | 同上 |

#### C 档｜明确**不**设行覆盖指标

| 目标 | 未覆盖行 | 替代方案 |
|---|---:|---|
| `agentic.agents`（`RobustBrowserAgent` 873 + `CliLoopTracer` 192 + `BasicBrowserAgent` 167） | 1,316 | LLM 驱动、已标 `RequiresAI`：用**录制回放 / 契约测试**（固定 prompt → 预期 tool-call 轨迹）替代行覆盖 |
| `coding.sandbox` 破坏性 shell 路径 | 418 | 集成测试 + 少量单测，不追行覆盖 |
| crawl 重型验收类 | — | 已按 `Heavy` 归到 nightly（`docs/TESTING.md`） |

### Phase 3 · 结构性补位（2~3 周，可并行）

1. **Rust CLI 接入 `cargo llvm-cov`**：先在 `nightly-cli.yml` 起步，先只要求
   `commands.rs` / `main.rs` 的参数映射出数字，再谈门槛。
2. **6 个插件模块纳入覆盖率**：在 `browser4-pdk` 里复制 `quality-gate` 的 agent/report/check，
   或在 CI 里显式对它们跑一次带 agent 的测试。
3. **覆盖率台账写进 `docs/TESTING.md`**：模块 → floor → 责任范围，附"新增逻辑必须带测试"的约定。
4. **测试密度约定**：`browser4-core/browser4-protocol` 现在是 32 个 main 文件 / 14 个测试文件
   （5,998 行源码），按"一个 main 文件至少一个测试类"补齐。

---

## 5. 里程碑与目标

| 节点 | 目标 | 判定方式 |
|---|---|---|
| M1（1 周） | 合并口径基线 + 汇总报告进 nightly | 报告含 13 + 6 模块；`protocol`/`skeleton` 数字纠正 |
| M2（2 周） | 模块 ratchet + PR diff coverage 上线 | 零测试 PR 必须变红 |
| M3（6 周） | 全局行 ≥70%；A 档包全部 ≥60%；Controllers ≥85% | 全仓汇总报告 |
| M4（10 周） | Core 四模块向 80% 推进（protocol / skeleton / browser / common）；Rust CLI 有行覆盖数据 | 同上 + `llvm-cov` 报告 |

阶段目标（对照 AGENTS.md：Global ≥70%、Core ≥80%、Utilities ≥90%、Controllers ≥85%）：

| 阶段 | 全局行 | protocol | skeleton | browser | common | rest |
|---|---:|---:|---:|---:|---:|---:|
| 现在（未校正） | 65.8 | 38.2 | 46.7 | 55.3 | 46.2 | 66.2 |
| M1 校正后 | 预计 70± | 预计 55± | 预计 60± | 预计 60± | 不变 | 预计 72± |
| M3 | ≥70 | ≥70 | ≥70 | ≥70 | ≥70 | Controllers ≥85 |
| M4 | ≥75 | ≥80 | ≥80 | ≥80 | ≥90 | ≥80 |

> M1 的"预计"值必须在 Phase 0 完成后重算——不要用现在的低估数字承诺。

---

## 6. 门禁规则设计（落地口径）

| 门禁 | 触发 | 规则 | 失败语义 |
|---|---|---|---|
| PR diff coverage | 每个 PR | 新增/改动行 ≥80% | 红（可豁免单文件并留 comment） |
| PR 模块 floor | 每个 PR | 按模块基线（ratchet） | 红 |
| nightly 汇总 | 每日 | observe-only + 相对 `main` 回归 >0.5% 告警 | 黄（不判红，进 summary） |
| 周报 | 每周 | Top-50 未覆盖类占比是否下降 | 人工 review |

**反作弊**（防止把覆盖率做上去但质量没上去）：

- 门槛只对**新增/改动行**用力，避免为了刷数字给旧代码补 `assertNotNull` 这类空测试；
- 覆盖率与"测试执行时长"同时记录，防止用重跑同一路径堆覆盖；
- flaky 清零是前提：nightly 现在 `--max-failures=0`，红就是真红，覆盖率才有意义。

---

## 7. 明确不做

- 不追 100%，不给 LLM agent 包（`agentic.agents`）设行覆盖门槛。
- 不把生成代码、data class、常量、bundle 模块算进分母。
- 不用"降低门禁"换绿——本次 nightly 就是被 0.20 的假下限放过去的。
- 不把 A 档工作推到 nightly 专用 tag 里：A 档必须能在 PR 的 fast/unit 门禁执行。

---

## 8. 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 合并 exec 重复计数 | 数字虚高 | 每模块先 merge 一次，再跨模块汇总；对比 per-module 报告交叉验证 |
| Kotlin 协程/inline 产生合成分支 | 分支覆盖被低估 | 开启 JaCoCo Kotlin 过滤，否则 52% 的分支目标永远显得不可达 |
| E2E fixture 抖动 | 趋势不可信 | 先清 flaky，再让覆盖率参与门禁（nightly `--max-failures=0` 已是基础） |
| 只盯全局百分比 | 大模块稀释小模块 | 模块级 floor + diff coverage 双轨 |
| 补测挤压夜间预算 | nightly 超时（当前 JVM 阶段 150 min 预算） | A 档进 PR，B 档用现有 E2E 类扩展，不新增重型类 |

---

## 9. 复现与核对方式

```bash
# 1）取最近一次 nightly 的测试产物（含每个模块的 jacoco.csv）
gh run list --workflow nightly.yml --limit 1 --json databaseId --jq '.[0].databaseId'
gh run download <run-id> -R platonai/Browser4 -n test-reports-<run-id> -D /tmp/b4cov

# 2）聚合（CSV 列序：GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,...,LINE_MISSED,LINE_COVERED,...）
#    按 LINE_MISSED 降序即为 §3.2 的表；按模块求和即为 §1 的表
```

核对点：

- §1 的模块表应与 nightly 日志里 `Coverage Summary (observe-only)` 的
  `TOTAL (N modules)` 行和逐模块行一致（当前 `TOTAL (13 modules) 62.5 52.0 65.8`）。
- §2.1 的三个"0% 但确实被执行"的类，可在 `browser4-rest-tests` 的 surefire 日志里找到
  对应用例（`CaptureIgnoreDomFeaturesE2ETest` 等）。

---

## 10. 附：优先级一句话版

> 先修**测量**（合并口径 + 补三个盲区），再修**门禁**（真地板 + diff coverage + ratchet），
> 然后按 **Top-50 类**按 A/B/C 三档补测；A 档进 PR、B 档走 e2e、C 档只做契约与录制回放。
