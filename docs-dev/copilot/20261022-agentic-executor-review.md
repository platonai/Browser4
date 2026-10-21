# browser4-agentic Agent 执行器代码评审报告

- 日期：2026-10-22
- 范围：`browser4-agentic` 子模块，29 个核心文件（执行器基类与内置执行器、Agent 主类、推理/状态/重试/检查点、CLI 解析、限流与缓存）
- 评审方式：静态代码评审，三路并行通读后交叉汇总
- 问题总数：**131 项**（高 20+ / 中 60+ / 低 40+）

---

## 一、致命：协程取消语义被破坏（6 处）

取消/超时无法真正停止工作，是分布最广、危害最大的一类问题。

1. **RobustBrowserAgent.kt L435/L469/L489** — `agentScope.coroutineContext.minusKey(Job)` 摘掉父子关系后再 `withContext`，`close()` 的 `agentJob.cancel()` **无法取消任何正在运行的任务**。L568 注释 "this will propagate cancellation to all child jobs" 不成立，长 LLM 调用期间 close 无效。
2. **AgentToolManager.kt L660-670** — `onDidNavigate` 的 `catch (e: Exception)` 吞掉 `delay` 抛出的 `CancellationException`，降级为 debug 日志。
3. **BatchExecutor.kt L190** — 并行 `awaitAll` 路径中 `catch (e: Exception)` 吞取消，其余 step 被取消后仍继续跑完。串行路径同理。
4. **AbstractToolExecutor.kt L165** — 基类 `callFunctionOn` 用 `catch (Exception)` 兜底，把取消转成普通 `TcEvaluate` 错误。**影响所有子类**。BrowserTabToolExecutor L943-950 的 `bestEffort` 明确重抛了 `CancellationException`，说明项目已知此约定，基类却违反了。
5. **CodingToolExecutor.kt L1555** — `runCatching { scanModuleGraph(fs) }` 捕获 `Throwable`，吞掉取消。L1783 同类（风险较低）。
6. **RetryStrategy.kt L82** — `catch (e: Exception)` 虽当前 `shouldRetry` 对取消返回 false 会重抛，但 `onError` 回调对取消事件误触发，扩展规则时极易吞掉取消。应先 `if (e is CancellationException) throw e`。

相关误判（2 处）：

- **AgentStateManager.kt L509-548** — 快照 `withTimeout(30s)` 抛的 `TimeoutCancellationException` 是 `CancellationException` 子类，既没降级为 `BrowserUseState.DUMMY`（与同函数降级策略矛盾），还会被上层误判为用户取消而取消整个任务。
- **RetryStrategy.kt L106-130** — `classifyError` 不识别 `TimeoutCancellationException`，协程超时落入 else 被误分类为 "💥 Unexpected error"（语义上应归 `TimeoutError`）。

---

## 二、资源泄漏（6 处）

7. **BasicBrowserAgent.kt L150-166 + L782** — **每个 agent 实例向全局 EventBus 注册两个捕获 `this` 的 lambda，而 `close()` 是空方法，从不注销**。agent 无法被 GC，监听器无限累积，已关闭的 agent 仍响应事件。
8. **CheckpointManager.kt L143/L178** — `Files.list()` 返回的目录 Stream 从不关闭（缺 `.use{}`），每次调用泄漏一个目录句柄。
9. **CodingToolExecutor.kt L99-106** — LSP 管理器双重检查锁绑死**第一次调用时**的 workspaceRoot，后续不同 workspace 复用错误的 LSP；executor 未实现 Closeable，语言服务器子进程随 executor 丢弃而泄漏。
10. **B4CliToolExecutor.kt L47-48/L211-214** — `jobScope`/`jobRegistry` 仅显式 `closeJobs()` 才释放；executor 未实现 Closeable/AutoCloseable，也无注册钩子保证调用，遗忘即泄漏协程作用域与所有后台 CLI 子进程。
11. **RobustBrowserAgent.kt L1243** — `MultiSinkMessageWriter`（CliLoopTracer）在 close 路径中无 flush/close，长进程下文件句柄/缓冲泄漏。
12. **AgentStateManager.kt L74+L406 / L72** — `_processTrace` KDoc 声称 "Limited to 200 entries to prevent memory leaks"，**实际无任何裁剪逻辑**；`_contexts` 声称 "Cleaned periodically to max 100 entries"，同样无清理。文档与实现双重脱节，长 session 内存无界增长。

---

## 三、并发正确性（9 处）

13. **ToolRateLimiter.kt L127-134** — **check/consume 两阶段无全局锁**：两个并发线程可同时通过 check 再都 consume，令牌透支，限流并发下失效；`coerceAtLeast(0.0)` 把负值抹平，问题被掩盖而非暴露。
14. **AgentToolManager.kt L52/L134** — `_customTargets`（mutableMapOf）与 `_concreteExecutors`（lazy+MutableMap）可从 REST 请求线程并发 register/unregister/contains，无同步，CME/更新丢失风险。
15. **RobustBrowserAgent.kt L291-296 / L306** — CustomToolRegistry `if (get(...) == null) register(...)` check-then-act 非原子，两个 agent 并发首次访问 `agentMemory` 时重复注册。
16. **RobustBrowserAgent.kt L1118** — `roundToolRecords +=` 普通 mutableListOf，并行工具执行时 `onToolResult` 回调并发触发会 CME/丢记录。
17. **AgentStateManager.kt L406 / L331** — `addTrace` 无同步，而同文件 `addToHistory` 用 `synchronized`，**同类内同步策略不一致**；`updateAgentState` 在锁外读 `stateHistory.states.lastOrNull()`。
18. **AgentStateManager.kt L358-372** — `synchronized` 锁内执行两次磁盘写（`writeHistory`），磁盘抖动放大为全局停顿。应把 I/O 移出临界区。
19. **AgenticContext.kt L43/L52/L76** — `getOrCreateSession` 竞态可创建多个 session；多 session 时返回"任意第一个"（依赖 map 迭代序），语义不确定；所有 createSession 变体的 `sessions[it.id] = it` 注册步骤无同步。
20. **TokenBudget.kt L29-30** — `isExceeded` 检查与 `add` 之间 check-then-act 竞态，并发 LLM 调用可双双通过检查后超额，"hard cap" 并发下不成立；KDoc 声称超限抛 `TokenBudgetExceededException`，类自身从不抛，文档未指明真实抛点。
21. **BrowserTabToolExecutor.kt L220-221** — 读延迟门控 `lastActionAtMillis`/`lastActionAffectsDom` 是 **executor 实例级**而非 per-tab：tab A 的 click 让 tab B 的 ariaSnapshot 被迫延迟 50ms，并发多 tab 跨会话污染。

其他并发项：AbstractToolExecutor L46 `toolSpec` 用非线程安全 mutableMapOf 且无安全发布；BrowserToolExecutor L292-293 `frontDriver` 活性检查与 `destroyDriver` 之间 TOCTOU。

---

## 四、安全/注入风险（4 处）

22. **CheckpointManager.kt L103/L124/L179** — `sessionId`/`checkpointId` 未净化直接拼文件名（`"checkpoint-$sessionId-$checkpointId.json"`），含 `../` 可路径穿越逃出 checkpointDir；`load` 可读任意 JSON，`deleteAll` 前缀过滤同样基于未净化输入。public 方法属安全隐患。
23. **CodingToolExecutor.kt L2063** — `classInfo` 用 `Class.forName(className)` 触发目标类 `<clinit>`，**被 prompt 注入的模型可触发 classpath 任意类的静态副作用**。应 `Class.forName(name, false, loader)`。
24. **CodingToolExecutor.kt L151** — tokenStats 记录完整工具入参（`input = tc.arguments.toString()`），`coding.write` 的 content（整份文件、可能含密钥）随 tokenStats 报告回传模型，**敏感数据外泄通道**。应截断或对 content 类参数脱敏。
25. **B4CliToolExecutor.kt L56-59** — 嵌套 agent 防护正则 `^\s*(agent\s+run|agent-run|act)\s` 要求子命令后必有空白：`b4.run(args="act")`（恰好无后续参数）不匹配，**防护被绕过**，与注释"在任何子进程启动前拒绝"矛盾。应改 `(\s|$)`。

---

## 五、输入解析静默错误（11 处）

26. **AgenticCliRunner.kt L245-250** — **Windows 反斜杠转义 bug**：非引号 token 中 `\` 把下一字符直接转义，`goto C:\Users\foo` 解析成 `C:Usersfoo`，路径被静默损坏。CLI 高频输入。
27. **AgenticCliRunner.kt L225-236** — 引号内不支持转义（`"a\"b"` 解析错误）；**L233 空引号 `""` 被丢弃**（`isNotEmpty()` 检查），`type ""`（清空输入框）无法表达。
28. **AgenticCliRunner.kt L194** — `--key value` 前瞻只检查下一 token 是否 `--` 开头，`--verbose snapshot` 这类"flag 后跟位置参数"会把位置参数吞成 flag 值。
29. **AgenticCliRunner.kt L529-531 / L712 / L725 / L802** — `parseNumber` 静默归零：`mousemove abc def` → (0,0) 错误坐标静默生效；`resize` 无参会改成 0×0。违反 no-silent-failures。
30. **AgenticCliRunner.kt L1157-1161** — 自定义 `Any?.toBoolean()` 用 `toBooleanStrictOrNull ?: false`，与标准库 ignoreCase 语义冲突：**browser_handle_dialog（L970）的 `"True"`/`"TRUE"` 会把 accept 静默变 dismiss**（安全相关分支）。
31. **BrowserTabToolExecutor.kt L1716/L1802/L1843/L1894/L2548** — `args["submit"] == true` / `args["autoDismissDialogs"] == true` / `(normalizedArgs["awaitPromise"] as? Boolean) ?: false` 只认 Boolean：MCP/CLI 传字符串 `"true"` 时 submit/auto-dismiss/awaitPromise **静默失效**无提示；同文件 L1673 却用 `paramBool`，风格不一致导致行为不一致。
32. **BrowserToolExecutor.kt L307** — `parseTabIndex` 中 `is Number -> raw.toInt()`：`index=1.9` 静默截断为 1，可能切换/关闭错误标签页。非整值应报错。
33. **BrowserTabToolExecutor.kt L2206/L2250/L2324/L2555/L2739** — 数值参数解析失败静默回退默认值：`scrollBy(pixels="abc")` 按 200.0 执行、`delay(millis="abc")` 按 1000ms、`waitTimeout` 非法值按 30s。与类内其他 fail-loud 校验风格相悖。
34. **AbstractToolExecutor.kt L216-233 / L264 / L201** — `paramInt`/`paramLong` 对 Double `3.0` 直接抛错（`"3.0".toIntOrNull()` 为 null，应先 `is Number -> v.toInt()`）；`paramStringList` 静默丢弃非 String 元素；`paramString` 对 List/Map 静默 toString 成 `"[a, b]"` 垃圾值。
35. **AgentToolManager.kt L739/L766/L789 / L713-717** — `putIfAbsent` 在命名/位置参数冲突时静默丢位置参数；spec 为 null 时 `"0"`,`"1"` 数字键垃圾参数原样下发，下游无法解析。
36. **BatchToolExecutor.kt L110 / L218** — `bypassCache` 解析逻辑混乱：`cache: "yes"`、`cache: 0` 都变 bypass=true；stepsArg JSON 解析失败返回原字符串，随后抛 "A batch requires a 'steps' array"，真实原因（JSON 语法错误）被消息误导。

其他解析项：AgenticCliRunner L1067-1071 snakeToCamel 丢失显式 null 值（下游无法区分"未传"与"传了 null"）；L1078-1093 `selector` 已存在时 `ref` 值被静默丢弃；AgenticSession/L1917-1923 `as? List<String>` 类型擦除，非 String 元素延迟到 driver 内部 ClassCastException。

---

## 六、逻辑错误 / 契约矛盾（14 处）

37. **AgentToolCallLoop.kt L263-272** — **overflow 压缩恢复后未重置 `pageViewDeduper`**：正常压缩路径 L210-216 明确注释"压缩重写了历史，deduper 索引可能失效"并 reset，overflow 路径 `compactForOverflow` 后 `continue` 却漏了，下一次 `decorate()` 用陈旧索引把工具结果引用到错误消息。
38. **AgentStateManager.kt L601/L494/L445** — `writeHistory`/`truncateLastHistoryLine`/`writeProcessTrace` 用 `_activeContext?.sessionId` 而非 state 自身的 sessionId：**多 session 交错时 A 的历史/trace 写进 B 的目录**，回滚截断也截错文件；`writeAgentState` 用参数是对的，同类行为不一致。
39. **AgentStateManager.kt L299-301** — 失败路径 `requireNotNull(observeElement)`/`requireNotNull(toolCall)`：动作执行失败时两者很可能为 null，抛 IAE **掩盖原始失败异常**，状态记录中断，与 L311-317 注释声称的"转发 action 级异常以记录失败状态"直接冲突。
40. **CheckpointManager.kt L68** — `AgentStateSnapshot.from` 用 `success = description?.contains("FAIL") != true`：子串匹配且大小写敏感——"did not fail"/"FAILOVER" 误判失败，"failed"/"错误" 误判成功；`exception` 字段明明携带真实成败信号却不用。
41. **BrowserToolExecutor.kt L142** — `newTab` 的 `?: "about:blank"` 是**死代码**：`paramString` required=true 先抛异常，永远执行不到；而 L52 ToolSpec 明确声明默认 "about:blank"，宣传契约（可省略 url）与实际行为（必填）矛盾。
42. **RobustBrowserAgent.kt L942** — 文本即完成判定过松：`toolExecutions.get() > 0 && textOnlyStreak >= 1`，模型历史上跑过任意一个工具后再说一句纯文本就当作最终报告，中途说明性文字触发 false completion（上方注释还残留"两次纯文本"旧描述，与代码不符）。
43. **RobustBrowserAgent.kt L1973** — finish-gate strict 模式校验失败抛 IllegalStateException → 经 doRunCliAgentLoop catch → 上层 retry，**一次报告格式问题让整个任务重跑烧 LLM token**，而非仅拒绝该次完成声明让模型当场修正。
44. **RobustBrowserAgent.kt L1088** — `maxIterations.coerceAtLeast(40)` 静默覆盖用户配置：想收紧预算到 10 会被抬回 40。
45. **RobustBrowserAgent.kt L2055** — `extractVerdictJson` 用 `lastIndexOf('}')` 贪婪截取，吞掉 verdict 之后 summary 里的任何 `}`，提取结果非合法 JSON 导致"引用的 verdict 与记录不符"误判。
46. **RobustBrowserAgent.kt L2017** — finishWorkspaceRoot 依赖 `user.dir`：REST 服务进程工作目录与 agent 任务目标仓库无关，git 交叉检查可能查错仓库，得出错误 filesChanged 判定。
47. **RetryStrategy.kt L115 vs L42-43** — `shouldRetry` 对 IOException 先 lowercase 再匹配，`classifyError` 却 `contains("connection")` 大小写敏感："Connection reset by peer"（大写 C）在重试判定可重试、在分类落入通用 TransientError，**同一异常两个入口结果不一致**。
48. **AgentToolCallLoop.kt L342** — `maxIterations <= 0` 时 `for (0 until maxIterations)` 零次执行，`response!!` 抛 NPE 而非返回有意义的 OVERFLOW 错误；构造无 `require(maxIterations > 0)` 防护。
49. **BasicBrowserAgent.kt L186** — `_lastRunSessionId` 依赖 `stateManager.contexts.getOrNull(contextCountBefore)`，共享 contexts 在 run 期间被 trim 即失效；RobustBrowserAgent L429-438 注释明说已改用 `result.context.sessionId` 修复同样问题，**基类仍保留 bug，父子类行为不一致**。
50. **BasicBrowserAgent.kt L246+L764 / L273** — 嵌套超时归因错误：外层 `withTimeout(actTimeoutMs)` 套内层 `withTimeout(llmInferenceTimeoutMs)`，内层 LLM 超时被外层 catch 记录为 "act TIMEOUT"，真实原因被掩盖；L273 状态时序问题直接 `require(...)` 崩溃而非业务级失败结果。

其他逻辑项：AgenticCliRunner L107 `execute(commandName, args)` 未经过 `normalizeCommandName`，与 `execute(command)` 解析路径不一致；L668 goto 无 URL 静默导航 about:blank；L286 `click` 等命令的位置参数在 COMMAND_DEFS / COMMAND_RESOLVERS / when 分支三处重复定义，漂移风险高；`AgenticCliResult.ignored` 把"执行失败"与"命令被忽略"混入同一 `success=false`；BatchToolExecutor L155 失败路径的 `cache.put(..., success=false)` 是误导性死调用（put 内部 `!success return`）；L197-201 `isReadOnly` 把"可并行"与"可缓存"两个正交策略强行合取；L187-192 工具名大小写敏感无归一化；CodingToolExecutor L1359/L1393/L1504 用 `"Error:"` 字符串哨兵表示失败，内容恰以 `Error:` 开头的合法文件被误判；L1257-1263 scaffoldFlow 中 name 不以 `browser4-` 开头时 module 推断与 pluginDir 规则不一致，插件校验被静默跳过；FileSystemToolExecutor L124-130 spec 键 `listFiles` 与 method `listFilesInfo` 不一致，对外宣传的 `fs.listFilesInfo` 报 Unsupported；WebDriverExToolExecutor L43-46 CSS 选择器按逗号拼接/拆分，`[data-x="a,b"]`、`:not(a, b)` 合法选择器被拆坏无报错；SystemToolExecutor L45/L159-199 `deletedFiles` 参数被声明且列入 allowed 但从不读取，模型提交的删除清单静默丢弃。

---

## 七、SystemToolExecutor 确定 bug

51. **SystemToolExecutor.kt L183/L187/L195** — `args["domain"]!! as String`：`{"domain": null}` → NPE、`{"domain": 123}` → ClassCastException；`validateArgs` 只查 containsKey 不查值非空/类型，绕过统一 `IllegalArgumentException` 错误契约且拿不到 `help`。应改用 `paramString`。
52. **SystemToolExecutor.kt** — 见上 #50 末段（deletedFiles 从不读取）。

---

## 八、崩溃一致性 / 边界值

53. **CheckpointManager.kt L102-113** — `save` 直接 `mapper.writeValue(file...)` 非原子写：写一半崩溃留下截断损坏 JSON，后续 `load`/`listCheckpoints` 直接抛异常。应先写临时文件再 `Files.move(..., ATOMIC_MOVE)`；且 "latest" 文件第二次独立写入，两次写之间崩溃导致主文件与 latest 不一致。
54. **CheckpointManager.kt L147** — `listCheckpoints` 对单个损坏文件无 try-catch，任一损坏 checkpoint 让整个列表操作（及依赖它的 `pruneOldCheckpoints`）整体失败。
55. **ToolRateLimiter.kt L181** — `permitsPerSecond=0` 除零：`parseOverrides` 只拒绝 `< 0.0` 允许 `=0`，`missing / 0.0 = Infinity`，`ceil(...).toLong()` 溢出为 Long.MAX_VALUE，retryAfterMs 变天文数字返回客户端。L186 桶初始容量与后续 limit 漂移（overrides 改 limit 后旧桶不重建）；L119 sessionId 含 `:` 时键空间混淆。
56. **TokenBudget.kt L72** — `consumedTotal * 5 >= limit * 4` Long 乘法溢出：limit 接近 `Long.MAX_VALUE/4` 时溢出变负数，警告逻辑永久失效。应改除法形式。L47 运行时覆盖值无校验，负数/0 被静默解释为"无限制"，预算在运维失误时被意外禁用且无告警。
57. **B4CliToolExecutor.kt L173** — `timeout * 1000` Long 溢出：`wait(timeoutSeconds=Long.MAX_VALUE)` 溢出为负毫秒，await 行为未定义（spec 声称 max 600s 但代码未强制）。
58. **AgentToolCallLoop.kt L189-191** — token 累加用 `Int`：budget 调高 + maxIterations 调高时累计可超 `Int.MAX_VALUE`，溢出变负数，下游统计全错。应 Long。
59. **AgentStateManager.kt L493-507** — `truncateLastHistoryLine` 每次回滚 `readAllLines`+全量重写 O(N)，读写用平台默认 charset 可能损坏其他编码字符；追加时进程崩溃留半行会删错行。
60. **ToolResultCache.kt L124-131 / L197-205 / L115 / L71 / L186 / L165** — invalidateSession 全表 O(n) 扫描（点击/导航等高频写操作都触发）；evictIfCrowded 在 ConcurrentHashMap 弱一致迭代上排序，并发 put 期间可能漏删，map 长期略超 maxEntries 与注释 "bounded" 承诺不符，且每次超限 O(n log n)；read 失败（success=false）不淘汰旧缓存——read 失败往往意味着页面已变，继续发旧答案是陈旧数据风险；get 命中路径重复计算 specVersion SHA-256；Collection 顺序敏感（`[a,b]` 与 `[b,a]` 不同键）；键无转义（sessionId 含 `|` 时与 invalidateSession 前缀串扰）。

---

## 九、复制粘贴 / 死代码 / 文档脱节（选录）

61. **AgenticSession.kt L82** — `BasicAgenticSession` 的 logger 用 `getLogger(GenericAgenticSession::class)`，复制粘贴残留，日志全部归错类名，排障误导。
62. **BasicToolCallExecutor.kt L24** — KDoc 示例写 `ToolCallExecutor()`，实际类名 `BasicToolCallExecutor`，复制示例无法编译。
63. **AgenticContext.kt L36** — `initConfiguration` 为 public 可变单例 `MutableConfig(true)`，被所有后续 session 共享 `toVolatileConfig()`，任何持有 context 引用者可改后续所有 session 的初始配置。
64. **AgenticSession.kt L55-61/L90-96/L112-118** — createCompanionAgent 三处完全重复；`@Synchronized` 方法体内执行 `getOrCreateBoundDriver()`（秒级 IO）阻塞所有竞争者；`@Synchronized` + `by lazy` 双重保护冗余；L57 `if (isActive)` 时序依赖，失败仅 warn 后继续。
65. **AgentToolManager.kt L108-132** — domainAlias 不一致：有 "Tab"/"WebDriver"/"FileSystem" 大小写变体，却没有 "Browser"/"FS"/"Shell"/"System" 变体——`Browser.listTabs` 落入 else 抛 UnsupportedOperationException 而 `Tab.click` 正常，行为无规律。L272 `help()` 对 map 做 O(n) `values.firstOrNull` 查找而非直接索引；L299 `getAllToolSpecs()` associate 静默覆盖同 domain；L378 closeTab 兜底 `listDrivers().firstOrNull()`（本文件 L501-503 注释自己承认迭代序不稳定）可能关闭任意 tab；L385 等抛 IllegalArgumentException 与 `@Throws(UnsupportedOperationException)` 声明不符；L442 对 `!requiresReceiver` 传无意义 `Any()` 契约脆弱；L55 `driver` getter 有副作用（每次访问可能启动浏览器）；L678/L690 导航等待失败语义不一致（waitForSelector 超时逃逸 vs readyState 失败仅 warn；错误路径还白等 1 秒）；L698 `parts.isEmpty()` 死代码（`"".split(".")` 返回 `[""]`）。
66. **BasicBrowserAgent.kt L147 / L180 / L689** — 构造函数即 IO（`Files.createDirectories` 失败直接抛）；失败后紧循环无退避 hammer LLM/浏览器；L689 空 finally 死代码。
67. **RobustBrowserAgent.kt L304 / L584-587 / L866-867** — lazy 中 `runBlocking { bridge.awaitConnected(...) }`（单线程 dispatcher 内首次访问有死锁风险，lazy 做网络等待是反模式）；close() 中 `driver.close()` 抛异常时 `session.unbindDriver(driver)` 被同一 runCatching 跳过，session 持有死 driver 引用；L866-867 缩进错位 + 隐式触发 agentToolManager 整个 lazy 初始化（含 workspace mirror provision），副作用隐藏深。
68. **AgentToolExecutor.kt L44** — 生产代码 `requireLLMConfigured` 直接调用测试设施 `TestChatModelFactory.isEnabled()`，主代码路径对测试 mock 框架编译期依赖，测试开关可静默改变生产行为。L21 `associateByTo` 重复 method 名静默覆盖。
69. **AgentMessage.kt L10-12 / L18 / L38-41** — `Role` 枚举死代码且全大写与实际小写字符串角色不匹配；内部 `MutableList` 作为构造参数公开，绕过校验逻辑；对不可变 data class 做逐字段无意义拷贝。
70. **StructuredAgentLogger.kt L40 / L74** — `putAll(additionalData)` 在上下文字段之后执行，同名键静默覆盖真实 sessionId/step/event；结构化模式下 error 输出"前缀+message+内嵌 JSON 字符串"不是纯 JSON（info 是纯 JSON，行为不一致），机器无法按行解析。
71. **RetryStrategy.kt L88 vs L92 / L56** — `onRetry` 回调传 0-based `attempt`，日志打印 1-based `attempt + 1`，消费方按日志口径理解会错位；L56 注释声称 jitter 使延迟"monotonic w.r.t attempt"，实际 `(0..30).random()` 完全可能 attempt+1 延迟更短，注释与行为不符。
72. **SupportTypes.kt L36 / L34-39** — `PermanentError` 是 sealed 层级中唯一 `open class`，无注释说明扩展点；`ResourceExhaustedError` 不可重试（429/配额临时用尽通常应延迟重试），与 RetryStrategy 处理瞬态错误的意图不协调。
73. **BrowserToolExecutor.kt L130 / L163** — `switchTab` 依赖硬编码 `delay(200)` 做时序同步，快慢机器行为不一致（注释已承认）；L163 冗余 `as? AbstractWebDriver` 无效防御。
74. **BrowserTabToolExecutor.kt L762-765 / L2342** — selector+expression+functionDeclaration 三者同给时 expression 被静默忽略；`pdf()` 若返回 ByteArray 会被基类包装成 `{description="[B@6d4b2c"}` 不可读哈希串，应显式 base64。
75. **BatchExecutor.kt L215 / L182 / L266** — `private fun ToolErrorCodeMapper(...)` 函数名大写形似类名且从未被调用（死代码）；concurrency=100 静默截断为 MAX_CONCURRENCY=4 无日志；tool 字段对非字符串值 toString 出垃圾仍通过 blank 检查。
76. **AgenticContext.kt L113-115/L193-195** — 两处重复的注释掉的 System.err 死代码。

---

## 十、优先修复建议（Top 8）

| # | 问题 | 位置 | 影响面 |
|---|------|------|--------|
| 1 | `catch (Exception)` 吞 CancellationException | AbstractToolExecutor L165（基类） | 所有工具执行器 |
| 2 | `minusKey(Job)` 使 close() 取消失效 | RobustBrowserAgent L435/L469/L489 | 所有 run/act/extract |
| 3 | EventBus 监听器不注销 + 空 close() | BasicBrowserAgent L150/L782 | 每实例泄漏，无法 GC |
| 4 | check/consume 竞态限流失效 | ToolRateLimiter L127-134 | 并发下限流护栏失效 |
| 5 | overflow 路径漏 reset pageViewDeduper | AgentToolCallLoop L263-272 | 上下文引用污染 |
| 6 | 嵌套 agent 防护正则可绕过 | B4CliToolExecutor L56-59 | 安全护栏失效 |
| 7 | Windows 路径反斜杠转义 bug | AgenticCliRunner L245-250 | Windows 高频输入损坏 |
| 8 | 跨 session 写错目录 | AgentStateManager L601/L494/L445 | 多 session 数据错乱 |

### 修复模式建议

- **取消语义**：全库统一模式 `catch (e: Exception) { if (e is CancellationException) throw e; ... }`，基类 AbstractToolExecutor 先行；`runCatching` 在 suspend 上下文中需同样处理。
- **资源生命周期**：所有持有子进程/协程作用域/监听器/文件句柄的 executor 与 agent 实现 `Closeable`，并在注册处挂关闭钩子；EventBus 注册必须配对注销。
- **并发**：限流器 check+consume 合并为原子操作（或加锁）；所有 registry/manager 的可变 Map/List 换 ConcurrentHashMap/CopyOnWriteArrayList 或显式同步；文档声称的容量上限要么实现要么改文档。
- **参数解析**：统一走 `paramBool`/`paramInt`/`paramString`，禁止 `args["x"] == true` 裸比较；数值非法值 fail-loud 而非静默回退默认；CLI 解析器补引号内转义与 Windows 路径测试。
- **安全**：文件路径拼接统一净化（拒绝 `..`、路径分隔符）；`Class.forName` 用三参形式不触发 clinit；tokenStats 对 content 类大参数脱敏截断。
