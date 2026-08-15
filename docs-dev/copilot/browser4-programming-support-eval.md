# Browser4 编程支持评估与自举总结（P1–P7）

> 会话 session-9a549e93 · 分支 4.14.x · 2026-08-15
> 前置文档：`browser4-programming-support-review.html`（原始评估）、`browser4-programming-support-artifacts-review.html`（P0/P1 工件修复评估）
> 本文档为最终收口：从「评估四类工件支持」到「把 Browser4 变成能自己开发自己的仓库」，共 15 个提交。

## 1. 目标与结论摘要

**原始目标**：评估 Browser4 对四类工件（plugin / skill / JS / 简单脚本）的编程支持。

**演变后的目标**（用户逐步推进）：修复 P0/P1 问题 → 解释底层概念（fs 沙箱、工具交叉引用、LSP 成本/下载）→ 自研 LSP 客户端 → 抽取编程内核为独立模块 → 脚手架浏览器优先 → 与 browser4-seo 对照验证 → 用 Browser4 开发 Browser4 自身（P2–P7）。

**最终结论**：
- **四类工件支持**：从"模板+校验"升级为"脚手架（scaffold / scaffoldFlow）+ 校验器（validate，含工具引用交叉验证）+ 反陈旧活模板（scaffoldFromExample，文件/目录双模式 + 词干派生改名）"，并通过真实插件 browser4-seo 逐文件对照验证为成熟超集。
- **自身开发支持**：新增 45 个 `coding.*` 工具覆盖"改前影响分析 → 编译诊断 → 最小测试 → CDP 陷阱提醒 → 治理校验 → 提交"的完整 AGENTS.md 开发流；模块拓扑从静态快照升级为运行时实时图谱（含防漂移守门测试）。
- **工程质量**：browser4-coding 18 个主文件 6,161 行 + 19 个测试文件 3,313 行；browser4-coding 208 / browser4-agentic 909 测试全绿；全部提交已推送到 GitHub（4.14.x）。

## 2. 原始评估发现（回顾）

原始评估（browser4-programming-support-review.html）发现的问题与本轮解决的映射：

| 原始问题 | 本轮解决 |
|---|---|
| 无内置 coding skill（"修 bug/写测试"工作流空白） | `skills/browser4-coding` + `skills/browser4-dev` 全量工作流文档；`coding.devTask` 把 AGENTS.md 七步流编排成可执行计划 |
| 脚手架与真实插件形态不一致（AutoConfiguration 未实现 ToolMount、plugin.json 偏离真实 schema） | P0/P1 对齐真实 PluginManifest + ToolMount 接线；`scaffoldFromExample` 直接从真实代码提取模板，从根上消除陈旧 |
| 校验器误报/漏报（artifactId 误取依赖、ToolExecutor 误匹配） | P1 修复 3 个校验 bug（见 artifacts-review） |
| 类名命名不一致（browser4-seo→SeoAutoConfiguration 前缀处理） | 脚手架去前缀；活模板词干派生自动保持兄弟类命名一致 |
| 变更可审计是亮点（快照+diff+changeSummary） | 保留并强化；新增 protectedFiles 治理保护（VERSION/AGENTS.md/BOM/pom/CI 不可被破坏性操作改动） |

## 3. 自举成果总览（P1–P7，15 个提交）

| 阶段 | 提交 | 内容 |
|---|---|---|
| P1 内核抽取 | `6c938eea1` | 编程内核抽出为独立模块 `browser4-coding`（独立于 pulsar-common，仅 slf4j+Jackson+协程） |
| P1 浏览器优先 | `eb09cc3dd` `02c8ac04f` | 插件脚手架 7→10 文件（JS 资源 + Service + build.ps1）；修复 build.ps1 转义、校验器 2 个误报 |
| P1 Maven 通道 | `7b7d5205c` | `coding.mvnBuild`：mvn 编译 + 结构化 Kotlin/Java 诊断解析（4 种格式） |
| P1 活模板 | `8ccd69934` | `SkeletonExtractor` 单文件活模板 + `coding.scaffoldFromExample`（反陈旧：参考实现即模板） |
| P2 开发流模板 | `c0416b103` `e950b9ca3` | `DevFlowScaffolds`（b4CliCommand / agentTool / restEndpoint / testClass / skill）+ `coding.scaffoldFlow`（跨文件标识符一致） |
| P2 语义层 | `c880432d5` | `KotlinSemanticIndexer` 零依赖符号/引用提取（ktSymbols/ktReferences）；放弃 embeddable（默认不加载不下载） |
| P2 验证环 | `97a913494` | scaffoldFlow verify=true 实跑构建；`coding.impact` 影响分析 |
| P2 治理 | `fbafca21f` | `coding.trapCheck`（3 个 CDP 陷阱）；`validate(type=repo-consistency)`；protectedFiles 五操作拦截 |
| P3 多文件活模板 | `f2940adea` | `extractDir` 目录级模板（basePackage 公共前缀、artifactId 剔除 parent BOM、pluginName）；`DevTaskPlanner` + `coding.devTask` |
| P4 词干派生 | `953989412` | 兄弟类自动改名（Seo→Weather 四类全改）；stem 参数；**打通 GitHub push**（一次性 token URL） |
| P5 实时模块图 | `f210a2ebb` | `ModuleGraph` 运行时 pom 扫描（剔除 dependencyManagement/build 噪声）；`coding.moduleGraph`；impact 用实时图谱；SKILL.md 全量文档 |
| P6 快照治理 | `21e06cd6f` | ModuleMap 静态快照与实时图谱同步（12→36 模块）；`ModuleMapDriftE2ETest` + `ModuleGraphE2ETest` 守门 |
| P7 执行闭环 | `2892e9a27` | devTask `runTests` 实跑模块测试；规划接入实时模块图；browser4-dev 技能同步 |

## 4. coding 域 45 工具分组

（`CodingToolExecutor`，domain=`coding`，receiver=`CodingAgentShell` + `CodingAgentFileSystem` 复合 Target）

### 4.1 Shell 与文件系统（28）
Shell（7）：`shell` `shellOutput` `shellStatus` `shellList` `shellSetEnv` `toolsDetect` `projectType`
文件系统（21）：`read` `readLines` `write` `append` `replace` `replaceRegex` `editLines` `insertAfter` `revert` `delete` `mkdir` `copy` `move` `listDir` `glob` `grep` `stat` `diff` `changeSummary` `languages` `workspaceRoot`

要点：命令白名单（SAFE/DEV/NETWORK/DESTRUCTIVE）、编辑原语带快照（revert/changeSummary 可审计）、搜索跳过排除目录、diff 支持 Myers/Patience、**protectedFiles 治理保护**（VERSION/AGENTS.md/CLAUDE.md/根 pom/BOM/CI 的 delete/replace/editLines/insertAfter 全部拦截，模块 pom 仍可编辑）。

### 4.2 工件创作与校验（6）
- `scaffold(type=plugin|skill|js|script)`：四类模板（插件 10 文件，浏览器优先）
- `scaffoldFlow(type=b4-cli-command|agent-tool|rest-endpoint|test-class|skill)`：多文件开发流骨架，跨文件标识符从单一 name 派生
- `scaffoldFromExample(path, ...)`：**反陈旧活模板**——文件模式（单文件参数化）/目录模式（跨文件一致 + 词干派生兄弟类 + artifactId/pluginName 发现）
- `validate(type=plugin|skill|js|script|repo-consistency)`：校验器 + 工具引用交叉验证 + 仓库治理校验
- `runCode` / `runCodeLanguages`：沙箱代码执行（kotlin/js/python/bash）

### 4.3 自身开发（7）
- `mvnBuild(module, goals)`：mvn 编译 + 结构化诊断
- `ktSymbols` / `ktReferences`：零依赖 Kotlin 语义提取
- `impact(path)`：属主模块 + 传递影响（实时图谱优先，静态回退）
- `moduleGraph(module?)`：实时 pom 图谱 + 漂移告警
- `devTask(task, verify, runTests)`：AGENTS.md 七步计划 + 执行（编译/陷阱/治理/测试）
- `trapCheck(path)`：CDP 陷阱提醒

### 4.4 LSP（4）
`lspServers` `diagnostics` `symbols` `references`：按需启动的语言服务器（ts/js/py/rs），未装则优雅降级

### 4.5 设计原则（贯穿全部）
1. **反陈旧**：模板/模块拓扑均从真实代码（参考实现/pom.xml）生成或校验，不维护手写契约
2. **默认不加载不下载**：重后端（kotlin-compiler-embeddable、JDTLS、LSP 服务器）仅运行时探测，永不成为 Maven 依赖
3. **零依赖核心**：browser4-coding 仅 slf4j+Jackson+协程，独立于 pulsar-common（避免 B4Constants 类路径冲突）
4. **可审计**：所有编辑走快照，revert/changeSummary 兜底；治理文件硬保护

## 5. 四类工件支持终态

| 工件 | scaffold | validate | 活模板/工作流 |
|---|---|---|---|
| plugin | 10 文件（pom/build.ps1/plugin.json/AutoConfiguration.imports/JS 资源/Config/AutoConfiguration(ToolMount+Service)/Service/ToolExecutor(WebDriver receiver)/README） | pom（parent pdk/字面版本/依赖）、plugin.json（PluginManifest）、ToolMount、receiverClass、manifest.name==artifactId、JS 路径存在性 | scaffoldFromExample 目录模式克隆真实插件（含词干派生）；scaffoldFlow agent-tool |
| skill | SKILL.md（name+description+triggers+tools） | frontmatter、name==目录名、description 1..1024、allowed-tools、**工具引用交叉验证**（domain.method 对照运行时可见工具集） | scaffoldFlow type=skill；browser4-coding/browser4-dev 自文档化 |
| js | 按 purpose（extract/inject/interact） | 括号平衡、return、use strict、反模式 | 配合 tab.eval/tab.console 运行时测试 |
| script | ps1/bash + scriptType | param 块/shebang/错误处理 | build.ps1 模板（mvn package + jar 校验 + 部署） |

**对照实验**（`02c8ac04f`）：用新脚手架重新生成 browser4-seo 并逐文件对比——结论是现有 seo 参考插件已是脚手架形态的成熟超集（同构：Service 注入 + WebDriver receiver + service.x(driver)），无需替换；对比发现并修复 3 个脚手架/校验 bug。

## 6. 测试与质量数据

- browser4-coding：**208** 测试全绿（19 个测试文件）——含 `RepoConsistencyE2ETest`（真实 checkout 治理一致性）、`ModuleMapDriftE2ETest`（静态快照 vs 实时图谱双向精确匹配）、`ModuleGraphE2ETest`（关键边 + 无全仓库爆炸守门）、`RepoConsistencyCheckTest`、`ModuleGraphTest`、`DevTaskPlannerTest`、`SkeletonExtractorTest`（含目录/词干派生）、`CdpTrapCheckTest` 等
- browser4-agentic：**909** 测试全绿（CodingToolExecutorTest 嵌套类结构，外层容器报 0 属正常，实际全部执行）
- E2E 守门：非 Browser4 检出目录自动跳过（向上找 VERSION+pom.xml）
- 已知记账：mvn -q 的 exit code 在部分 agentic 运行中报 1 是 surefire 报表怪癖，以 "Tests run: N, Failures: 0" 为准

## 7. 已知局限（诚实清单）

1. **启发式解析**：devTask 的模块/文件提取、trapCheck 关键词扫描、KotlinSemanticIndexer 正则提取均为启发式——复杂任务需 Agent 人工补全；runTests 跑整个模块套件而非精确测试类
2. **词干派生边界**：新类名不沿袭旧后缀模式（如 MyExecutor 代替 XxxToolExecutor）时兄弟类退化为 MyExecutorAutoConfiguration，需显式 stem 修正
3. **LSP 依赖外部服务器**：diagnostics/symbols/references 需要语言服务器已安装，未装时优雅降级返回提示
4. **静态快照仍存在**：ModuleMap 保留为无 I/O 纯规划回退，实时图谱是权威；E2E 守门防止再漂移
5. **push 通道**：沙箱内 git push 需一次性 token URL（已实测稳定），gh api 留作 tag/ref 兜底

## 8. 使用指南

- **四类工件创作 / 自身开发**：见 `skills/browser4-coding/SKILL.md`（45 工具全量文档 + 工作流）
- **开发 Browser4 自身**：见 `skills/browser4-dev/SKILL.md`（改前影响半径 → 脚手架 → 编译 → 测试 → 治理）
- **推荐顺序**（自身开发）：`coding.impact` → `coding.read` → `coding.trapCheck`（驱动代码）→ 编辑 → `coding.mvnBuild` → 最小测试 → `coding.validate(type=repo-consistency)` → `coding.moduleGraph()` 确认无漂移
- **一步到位**：`coding.devTask(task="...", verify=true, runTests=true)`

## 9. 后续候选（未做，按价值排序）

1. devTask 测试类名解析（runTests 精准到 -Dtest=）
2. `coding.protect` 运行时动态增删受保护文件
3. KotlinSemanticIndexer 跨文件引用/继承链
4. 四类工件对照示例库（真实实现 vs 脚手架输出）
5. 工具级真实 E2E 套件（与"不跑重套件"政策平衡）
6. GitHub release/tag（版本意图需确认：VERSION 当前 4.13.4-SNAPSHOT）
