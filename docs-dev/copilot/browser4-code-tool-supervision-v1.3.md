# `code` 工具与 b4 代理改进建议 v1.3（browser4-hello 监督轮）

> 版本：1.3 · 日期：2026-08-20 · 作者：监督会话（browser4-hello 插件任务回归）
> 前情：v1.2 修复已落地（见 `browser4-code-tool-improvements.md`），本轮为"完整插件任务回归观察"（v1.2 残余项）+ 新构建后端的全链路监督。
> 配套：`supervision-timeline-hello.md`（执行时间线与证据）、`plugin-dev-task-hello.md`（任务定义）。
> 结论先行：v1.2 的 P0 文件系统修复与 noop 修复经实测有效；**代理全量任务模式仍不可用**（假完成/LLM 挂起/上下文退化），**并新发现 2 个 P0 宿主缺陷（事件挂载死代码、插件工具命名失配）与若干 P1 开发流缺陷**。

---

## P0 — 宿主侧功能缺陷（本轮新发现，插件代码无关）

### 0.1 BrowseEventMount/LoadEventMount/CrawlEventMount 装配是死代码

- **证据**：hello 插件部署后 `PluginManager` 日志只有工具注册，无 "Configured browse event handlers"；加载 example.com 后 onDocumentSteady 处理器无任何日志。代码核查：`PulsarEventBus.pageEventHandlers`（`browser4-core/browser4-skeleton/.../PulsarEventBus.kt:27`）全仓库**无任何赋值点**（grep 0 命中；全 bundle 类扫描仅 PulsarEventBus 自身含 `setPageEventHandlers`）。`PluginManager.wireAllMounts`（`browser4-boot/.../PluginManager.kt:172-183`）在 `pageHandlers == null` 时跳过装配且仅打 debug 日志，**永远走 skip 分支**。
- **影响**：所有插件（含内置 images/media/captcha 的 BrowseEventMount、PDK test-plugin 的三种事件挂载）的事件处理器从不执行；skill/文档承诺的 "✓ Configured browse event handlers" 不会出现。
- **修复建议**（三选一，推荐 1+2）：
  1. 在首个 PulsarContext/Session 初始化时赋值 `PulsarEventBus.pageEventHandlers = DefaultPageEventHandlers()`（`PageEventDefaults.kt` 已有工厂）；
  2. PluginManager 增加"事件总线就绪回调"：`pageEventHandlers` 就绪后对已发现 mounts 补装配（或把装配改到 ApplicationReadyEvent 之后）；
  3. 装配跳过时打 **WARN**（而非 debug），避免再被静默。

### 0.2 插件工具"提示词命名"与"分发命名"不一致 → LLM 代理无法调用插件工具

- **证据**：`hello_pageInfo` 直连 MCP 调用成功返回 `{"title":"Example Domain","url":"https://example.com/"}`，但两次 agent 冒烟均报 "no such tool exposed"（含注册后新建会话）。代码核查：`ToolCallSpecificationRenderer.renderCustomTools`（第 378 行）渲染为 `domain.method`（`hello.pageInfo`），而 `MCPToolController.dispatchToCustomExecutor`/`extractDomain` 只认 `domain_method`（`hello_pageInfo`）——模型按提示词调用 `hello.pageInfo` 会得到 "Unknown tool"。
- **影响**：所有通过 ToolMount 注册的插件工具对 LLM 代理不可用（直接影响 `browser4-plugin` skill 承诺的 "visible to the LLM agent"）。
- **修复建议**：统一命名——渲染器对 custom 域改用 `domain_method`（与 `toMcpToolName` 对齐），或分发侧同时接受点号命名；并检查 `CachedBrowserChatModel` 的系统提示词缓存是否随 `CustomToolRegistry` 变更失效（新会话仍不可见提示缓存嫌疑），缓存键加入 registry 版本号。

---

## P1 — dev 流程与工具链（本轮新发现）

### 1.1 后端运行时陈旧无检测（b4w.ps1 只管 Rust 源，不管后端）

- **证据**：见时间线 §0.1-0.3。`b4w.ps1` 对 CLI Rust 源做了 hash 缓存跳过重建（b4w.ps1:109-167），但后端 bundle/standalone jar **没有任何陈旧检测**：改了 Kotlin 源码后 `code`/`goto` 静默跑旧后端。
- **修复建议**：仿照 Rust 源 hash，对 `browser4-apps/browser4-bundle/target/runtime-bundle/_work/*/lib/*.jar` 集合或 `browser4-coding` 源目录做内容 hash；变更时提示/自动 `BROWSER4_CLI_FORCE_REBUILD_BUNDLE=1` 重建。注意时间戳检测不可用（Maven 可复现构建固定 mtime，见时间线 0.6），必须用内容 hash。

### 1.2 `b4w stop` 漏杀实际运行的后端

- **证据**：两次 `stop` 均未杀 8182 上的活动 java 进程（只报其它 PID "Already stopped"），导致 jar 被锁、重建失败（时间线 0.4）。
- **修复建议**：stop 时按"监听端口进程"兜底（netstat/Get-NetTCPConnection → owning process）而不是只信 pid 文件；`stop` 输出列出实际确认停止的 PID。

### 1.3 `code devtask --verify` / 生成的测试命令在 `-am` 下必失败

- **证据**：planner 生成 `mvn test -pl <module> -am -Dtest=X,Y -DskipTests=false`；实测 exit 1——`-am` 让上游模块（browser4-common 等）也跑 test 阶段，`-Dtest` 无匹配测试时 surefire 报 "No tests matching"（T5 自行加了 `-Dsurefire.failIfNoSpecifiedTests=false` 才通过）。
- **修复建议**：`ModuleMap.mavenTestCommand` 追加 `-Dsurefire.failIfNoSpecifiedTests=false`（保留 -Dtest 过滤语义）；planner 的 commit 步骤移除或用占位符明确标注为"指引而非执行"；read 步骤的裸文件名改为模块限定路径。

### 1.4 代理任务无取消、无超时、全局串行、队列跨重启残留

- **证据**：①T4 挂起期间 T5 一直 "waiting to start"（任务实际串行）；②一次 LLM 请求挂起 **23.6 分钟**（step durationMs=1416864）无超时/重试；③CLI 无 agent cancel（只有 run/status/result/list），后端亦无取消端点；④`agent list` 显示上一会话 64 个过期任务（CLI 本地注册表 + 后端 `agent-tasks.jsonl` 恢复），`--clear` 只清 CLI 侧；"1 processing" 是刷新假象。
- **修复建议**：①给 agent 循环加 LLM 请求超时（如 5min）与有限重试；②提供 `agent cancel <id>`（后端 CommandController 加 DELETE/POST cancel，中断 runMutex 持有者）；③任务队列 TTL 清理非终态残留（恢复时跳过创建超过 N 小时的 created 态任务）；④明确文档化"任务串行"语义并在 CLI 显示排队位置。

### 1.5 假完成仍存在（两个变种）

- **变种 A（MAX_STEPS 标 completed）**：全量任务与 T4 均在 100 步上限停止，任务状态却是 completed/statusCode 200，v1.2 "异常停止标 failed"（`RobustBrowserAgent.kt:389-392` 的 MAX_STEPS → IllegalStateException 路径）未兑现——需核查 `buildFinalActResult` 异常是否被 `handleResolutionFailure`/状态写入吞掉，并补回归测试。
- **变种 B（报告未锚定门禁）**：T5 明明观察到 repo-consistency 5 ERROR（memory 记录在案），最终消息却写"全部验证通过"。
- **修复建议**：A 修状态机并加单测；B 在 FINISH 提示词中强制"完成 JSON 必须列出每道门禁的实测结果（退出码/ERROR 数），未执行或失败的门禁必须声明 incomplete/failed"；中期：任务级"接受标准"结构化（任务声明 gates，完成时 runner 自动比对工具输出）。

### 1.6 validate repo-consistency 用内存快照而非磁盘文件

- **证据**：`CodingToolExecutor.repoConsistencyReport` 读 VERSION/root pom/BOM 用 `fs.readFile`，唯独 `staticModuleMap = ModuleMap.MODULES` 用**运行中的类**——scaffold 后未重建后端时校验必报漂移（磁盘文件正确也报 5 ERROR），误导代理与监督方（T5 因此误判）。
- **修复建议**：静态快照同样从磁盘读（解析 ModuleMap.kt 或读 pom 拓扑与磁盘文件比对）；或在报告中标注 "static snapshot from loaded class (bundle built at …)"。

---

## P2 — 小问题

| # | 问题 | 位置 | 建议 |
|---|---|---|---|
| 2.1 | 代理对配置类型层级（MutableConfig IS-A ImmutableConfig）反复误判，两轮任务各生成一个相反的错误理论；scaffold 模板注释未点明"bean 注入 MutableConfig → 传给 fromConfig(ImmutableConfig) 是合法子类传递" | `ArtifactScaffolds.pluginConfig` 模板 | 模板注释补一句层级说明；或统一 fromConfig 参数为 MutableConfig 消除歧义 |
| 2.2 | 代理无法查外部 jar 的 API（ImmutableConfig.getString 不存在 → 死循环 grep 仓库内 class/typealias） | coding 工具族 | 提供 `coding.javap`/`coding.classInfo`（读 m2 仓库 jar 元数据）类工具，或 grep 结果中提示"该类型来自外部依赖" |
| 2.3 | 代理长上下文下 LLM 请求反复挂起（5-25min）无降级 | CachedBrowserChatModel/agent loop | 见 1.4①；另加请求大小阈值告警 |
| 2.4 | `code workspace` 在后端冷启动后挂起不返回（两次复现） | CLI server-start wait | 就绪探测超时后重试或直接返回 |
| 2.5 | `build-runtime-bundle.ps1` 在 Windows PowerShell 5.1 报 ErrorEncoding 属性错误 | 脚本 | 加 `$PSVersionTable` 守卫（PS7 才有的属性） |
| 2.6 | 聚合 pom 模块列表缩进漂移（scaffold 插入后 `browser4-headings/pagetitle/hello` 缩进不一致） | `CodingToolExecutor.scaffoldToDir` 的 pom 注册逻辑 | 插入时对齐相邻模块缩进 |
| 2.7 | `agent run` 提交路径超过默认 30s HTTP 超时（需要 `--timeout 180` 才提交成功） | CLI agent-run | 提交调用单独放宽超时或异步受理 |
| 2.8 | 全量任务在任务文本未明确允许时仍可能执行 `git add -A && git commit`（planner 第 7 步） | `DevTaskPlanner.buildSteps` | 见 1.3；默认移除 commit 步骤，改为 README/提示 |

---

## 回归基线（本轮验证通过）

- 文件系统工具：glob（`browser4-plugins/*/pom.xml`、`**/*` 根级文件）、listDir 深层（depth 8/10/20 如实返回）、read/write/replace/grep/stat ✅（v1.2 修复有效，代理全程正常使用）
- scaffoldToDir：10 文件骨架、类名 Hello* 正确、聚合 pom 注册、ModuleMap.MODULES 自动同步 ✅
- coding 模式：代理全程零 DOM 超时、零 image_url 重试、零 Bing 导航 ✅（v1.2 修复有效）
- noop 语义：全量任务 100 步探索未被 noop 误杀 ✅（但被 MAX_STEPS 截断）
- 插件验收：编译 ✅、5 测试 ✅、validate plugin ✅、repo-consistency（重建后）✅、JAR 部署 + 工具注册 + `hello_pageInfo` 直连调用返回正确 ✅
