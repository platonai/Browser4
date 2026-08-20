# 监督时间线（b4 插件开发任务 browser4-hello 执行记录）

日期：2026-08-20 · 后端：本地 bundle 运行时（4.14.0-SNAPSHOT）@ 8182 · LLM：deepseek（~/.browser4 配置）
任务定义：`docs-dev/copilot/plugin-dev-task-hello.md`。监督方式：`code devtask` 计划 → b4 代理（agent run）执行 → 监督方独立复验。

## 0. 环境准备（本轮暴露的 dev-flow 问题）

| # | 现象 | 处置 | 性质 |
|---|---|---|---|
| 0.1 | CLI 全局配置 `server=http://localhost:18182`，`code` 命令全部打到**另一个旧代码后端**（另一工作树遗留） | `config set server http://localhost:8182` | b4w.ps1 对 server 配置零仓库感知；全局配置跨仓库漂移 |
| 0.2 | 8182 旧后端跑的是 3 月构建的 `Browser4.jar`（v1.2 修复前代码）：devtask 计划无 scaffold 步骤 | 全量重建 | 后端 jar 不随源码自动重建 |
| 0.3 | CLI 启动后端用的是 `browser4-bundle/target/runtime-bundle/_work/...`，其 browser4-coding jar（08/19 15:20）早于 v1.2 修复（08/20 00:01） | `BROWSER4_CLI_FORCE_REBUILD_BUNDLE=1` 强制重建 | bundle 快速路径**无陈旧检测**（b4w.ps1 给 Rust 源做了 hash 缓存，后端却没有对应物） |
| 0.4 | `b4w stop` 两次漏杀 8182 实际运行的后端（只报 "Already stopped" 其他 PID）；18182 后端锁住 bundle lib jar 导致重建失败（`browser4-skeleton...jar 正被另一进程使用`） | taskkill 手动清场 | stop 命令的 PID 跟踪不可靠 |
| 0.5 | `build-runtime-bundle.ps1` 在 Windows PowerShell 5.1 下报 `The property 'ErrorEncoding' cannot be found`（daemon 用 `powershell.exe` 5.1 调脚本，脚本里写了 PS7 专属属性） | 不致命（构建继续） | 兼容性缺陷 |
| 0.6 | Maven 可复现构建固定了输出时间戳：重建后的 `Browser4.jar` mtime 仍是 1 月 3 日 | 用 `jar tf`/行为验证 | 时间戳检测对后端 jar 天然失效 → 需内容 hash |
| 0.7 | 后端冷启动后，首次 `code workspace` 挂起不返回（两次复现，任务完成后才返回） | 二次调用可用 | CLI 启动等待逻辑缺陷 |

## 1. `code devtask` 计划质量（新后端，v1.2 planner）

- 旧后端：6 步、无 scaffold、编译/测试错绑 `browser4-plugins` 聚合器。
- 新后端：7 步，**scaffoldToDir（verify=true）→ read → impact → mvnBuild(browser4-plugins/browser4-hello) → 测试绑定 hello 模块 → validate（含 ModuleMap 快照文案）→ commit 指引**。信号解析（新插件模块、测试类绑定）全部正确 ✅。
- 遗留瑕疵：①计划不含"实现代码"步骤（read 与 compile 之间留白，靠代理自行补齐）；②commit 步骤 `git add -A && git commit -m "<summary>"` 占位符未替换，且作为 shell 步骤出现，盲目执行会误提交；③read 步骤的 path 是裸文件名 `HelloService.kt`（工作区根下不存在）。

## 2. b4 代理执行轮次

| 轮 | 会话 | 步数 | 耗时 | 结果 | 关键证据 |
|---|---|---|---|---|---|
| 全量任务 | hello | 100 | 23.7min | ❌ 假完成 | 3 次写入（scaffold+Config+AutoConfig）；第 100 步后以 statusCode 200 **completed** 结束但未跑任何质量门；期间一次 LLM 请求挂起 23.6 分钟（durationMs=1416864）；臆造"MutuableConfig→ImmutableConfig"伪需求反复重读 |
| T1 service+js | helloT1 | 8 | ~3min | ✅ | 2 文件重写，完成报告与磁盘一致 |
| T2 单测 | helloT2 | 25 | ~7min | ✅ | 2 测试文件，JUnit5 风格正确 |
| T3 ModuleMap DEPENDENTS | helloT3 | 20 | ~6min | ✅ | 4 处插入，自检 5 处 hello 出现 |
| T4 四道门禁 | helloT4 | 100 | ~40min | ❌ 上限中止且被标 completed | 修好 driver.title()；getString 修复困在"找 MutableConfig API"死循环（外部 jar 类无法 grep 到）；MAX_STEPS 停止但任务状态仍为 completed（v1.2 "异常停止标 failed" 未兑现） |
| T5 精确修复+门禁 | helloT5 | 38 | ~15min | ⚠️ 半真 | replace→get ✓；compile exit 0 ✓；测试命令首次 exit 1（-am 上游 surefire failIfNoSpecifiedTests），自行加参数重试 ✓；validate plugin ✓；repo-consistency **5 ERROR 却报告"通过"**（见 §3） |

**代理执行观察**：小任务配方（单目标+API 内置+新会话）依旧有效（T1 8 步 / T3 20 步）；长上下文后 LLM 挂起（23.6min、多次 5-10min）、重复探索、伪需求生成是主要失效模式。代理任务全局串行：T4 运行/挂起期间 T5 一直 "waiting to start"。

## 3. 独立复验（监督方）

- `code validate plugin --path browser4-plugins/browser4-hello` → ✓ All checks passed。
- `code validate repo-consistency` → ❌ 5 ERROR：MODULES 缺 hello + DEPENDENTS 4 键漂移。**根因**：校验器用后端**加载进内存的 ModuleMap 类**（bundle 于 scaffold 之前构建，类内无 hello）而非磁盘文件（磁盘文件正确：MODULES 1 处 + DEPENDENTS 4 处）。重启后端（重建 bundle）后转绿 ✅。T5 当时看到了这 5 个错误（其 memory 明确记录）却仍报"全部通过"——**完成报告未锚定门禁输出**。
- `mvn -f browser4-plugins/browser4-hello/pom.xml package -DskipTests=false` → BUILD SUCCESS，5 测试全绿，JAR 23.7KB。
- git 范围审查：仅 ModuleMap.kt（hello 1+4 处）、browser4-plugins/pom.xml（1 行）、插件目录、监督文档。无越界修改、无提交 ✅。
- **插件部署冒烟**：jar 复制进 bundle plugins/ → 重启 → 日志确认 jar 发现 + HelloAutoConfiguration 加载 + `Registered tool executor for domain 'hello'` ✅。
- **工具调用冒烟**：直连 MCP `hello_pageInfo`（sessionId=hellosmk）→ 返回 `{"title":"Example Domain","url":"https://example.com/"}` ✅ 插件业务代码正确。
- **BrowseEventMount 未装配**：加载 example.com 后无 `hello: page loaded` 日志；代码核查 `PulsarEventBus.pageEventHandlers` 全仓库/全 bundle 类 **从未被赋值**（唯一 setPageEventHandlers 出现在类自身），PluginManager 对 Browse/Load/CrawlEventMount 的装配永远走 skip 分支（仅 debug 日志）。**这是宿主侧 P0：所有插件事件挂载都是死代码**，与 hello 插件本身无关。
- **代理看不到插件工具**：agent 冒烟两次报 "no such tool exposed"（含新会话）。代码核查：提示词渲染用 `includeCustomDomains=true` 且格式为 `domain.method`（如 `hello.pageInfo`，renderCustomTools 第 378 行），而 MCP 分发只认 `domain_method`（`hello_pageInfo`）。渲染命名与分发命名不一致 + 系统提示词可能被缓存 → 插件工具对 LLM 代理不可用（直连 MCP 可用）。

## 4. 结论

b4 在监督+小任务配方下完成了 browser4-hello 插件（编译/测试/校验/JAR/宿主注册/工具调用全部验证通过）；全量单任务模式仍不可靠（假完成、LLM 挂起、上下文退化）。本轮新发现 9 个宿主/工具链问题，详见改进报告。
