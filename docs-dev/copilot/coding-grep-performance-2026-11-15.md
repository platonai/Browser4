# `coding_grep` 性能调查（2026-11-15）

调查对象：`coding.grep`（MCP 名 `coding_grep`），实现
`browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/CodingAgentFileSystem.kt:1224-1291`，
派发点 `browser4-agentic/.../tools/builtin/CodingToolExecutor.kt:1207-1215`。

复现工具（本次新增）：[`coding-grep-perf-bench/GrepBench.java`](coding-grep-perf-bench/GrepBench.java)，
逐行复刻了现有实现（同遍历、同排除表、同 5 MB 上限、同 500 命中上限、同
`Files.readAllLines` + 逐行 `matcher.find()`），并附带各优化候选模式。

## 一、结论（TL;DR）

`coding.grep` 每次调用都是「整棵树 × 逐文件 open × 逐行 regex × 单线程」的全量扫描，
没有索引、没有缓存、没有时间预算、没有取消检查。它的成本几乎完全由**打开的文件个数**决定，
而不是字节数——这正是它在真实工作区上不可用的原因。

1. **热缓存（数据已在系统缓存里）**：约 140 MB/s 单线程；本仓 3,951 文件 / 77 MB = **0.34-0.48 s**。
2. **首次触碰（冷树，Defender 实时扫描介入）**：每个文件 open 要 **3-24 ms**，与字节数无关：

   | 树 | 文件数 | 大小 | 现有实现（单线程，首次） | 每文件 |
   |---|---|---|---|---|
   | `Browser4-4.15` | 4,898 | 86 MB | **15.8 s** | 3.2 ms |
   | `Browser4-4.17-feat` | 5,753 | 82 MB | **45.2 s** | 7.9 ms |
   | 本仓（闲置后首跑） | 3,951 | 77 MB | **92.0 s** | 23.8 ms |
   | `Browser4Team` | 8,447 | 146 MB | **162.1 s** | 19.2 ms |

   同样的树再跑一次：**0.34-0.94 s**（热）。也就是说冷/热差 **16×-270×**，
   而 agent 的典型场景（新建工作区、`git checkout` 后、build 后）恰好全是冷树。
3. **多 checkout 工作区**（`D:\workspace\Browser4`，198,166 文件 / 3.4 GB）：
   仅遍历（只取属性）= 7.1 s；现有实现跑 **20 分 26 秒只读完 2.4 GB（≈2 MB/s，CPU 仅 46 s）**，
   推算单次调用约 **29 分钟**。
4. **失败模式不是「慢」，而是「超时 + 重试 + 挂住」**：
   - 直接 CLI 调用 `browser4-cli code grep`：`cli/browser4-cli/src/http.rs:100-108`
     的 `is_coding_tool()` 只列了 `mvnBuild/devTask/runCode/verify`（600 s），
     所以 `coding_grep` 走 **默认 30 s** 预算（`http.rs:8`）→ 冷树必然超时。
   - 超时后模型通常换个写法重发，**服务端不会停**：`grep()` 里的
     `withContext(Dispatchers.IO) { Files.walkFileTree(...) }` 没有 `ensureActive()`，
     客户端断开不会中断扫描，`AgentConfig.resolveTimeoutMs` 默认 **24 h**，也没有别的上限。
5. **可量化的优化空间（实测）**：
   - 并行扫描：热树 **3.3-6.1×**（本仓 340 ms → 104 ms）；冷树按每文件成本折算 **21×**
     （`Browser4Team` 单线程 19.2 ms/文件 → `archive` 20 线程 0.89 ms/文件；两棵树不同，
     但每文件首触成本可比，正好把 20 线程的摊薄效果量出来）。
   - 流式读取（`BufferedReader` 替代 `readAllLines`）+ 用 walk 自带属性替代第二次 `stat`：**+15-25%**。
   - 纯字面量走字节级预筛（不解码）：**+15-30%**。
   - 命中上限 500 → 100：常见 token 再省一半（本仓 `class ` 命中 500 条用 47 ms/337 文件，100 条用 24 ms/124 文件）。
   - 加 10 s 硬预算：把最坏情况从「无界」变成「有界 + 显式 partial」。
6. 另外发现 5 处会**静默漏掉结果**的正确性缺陷（见 §四），它们会让模型反复换写法重试，
   进一步放大「慢」的观感——**P1 已全部修复**（2026-11-16）。
7. **P0/P1/P2 均已落地**（见 §五）：
   - P0（2026-11-15）：10 s 硬预算 + 最多 8 线程并行 + `limit`（默认 100）+ `ensureActive()` 可取消 + 字面量字节预筛；
   - P1（2026-11-16）：非 UTF-8/UTF-16 回退解码、排除表重做（含 `bin` 语义修正）、`filePattern` 路径语义、命中窗口；
   - P2（2026-11-16）：`rg` 收窄快路径（探测窗口内走得完就不起进程，失败一律回退，输出逐字节一致）。
   被截断时结果里带 `partial:` / `match cap` 脚注，不再把「没扫完」伪装成「没有」。
8. **另修掉一个更早发生的首调用瓶颈**：`MirrorRoots.derive` 过去递归遍历整个 workspace 并对每个条目
   `toRealPath()`，在大工作区上让**第一次** `coding.grep` 先花 226 s 做边界检查；现已按深度/条目数有界，
   226,370 ms → **234 ms**（§五末）。

## 二、成本结构（为什么是「按文件计费」）

| 阶段 | 本仓 3,951 文件 / 77 MB | 合成树 25,000 文件 / 184 MB |
|---|---|---|
| 仅遍历 + 过滤（不开文件） | 26-45 ms | 30-45 ms |
| 现有实现（`readAllLines` + 逐行 regex） | 340-480 ms | 1,247-1,310 ms |
| 流式读取 + walk 属性 | 341 ms | 929-1,053 ms |
| 字面量字节预筛 | – | 870-894 ms |
| 并行（20 线程） | **98-104 ms** | **205-277 ms** |

- 遍历本身很便宜（25,000 文件 30 ms，元数据访问），**贵的是把每个文件都打开并解码**。
- 热态下 CPU 是瓶颈（单线程 ≈2-4 M 行/s，≈150-220 MB/s；`readAllLines` 会产生整文件的行对象：
  184 MB 树实测堆占用 156 MB，流式 104 MB）。
- 冷态下延迟是瓶颈（每次 open 3-24 ms，线程能把它摊薄——同一棵树 `archive` 33,520 文件 / 385 MB
  并行首次只花 **30.0 s**，每文件 0.89 ms，而单线程同规模要 ~10 分钟）。
- 结论：**减少文件个数**（排除表/`filePattern`/命中上限）与**并行**收益同阶；
  逐字节优化（更快的解码/正则）只占个位数百分比。

新实现在同一棵树上的实测（真实 Kotlin 代码，本仓 3,951 文件 / 77 MB）：

| 场景 | 结果 |
|---|---|
| 无预算全量扫描（热，无命中） | **109-144 ms**（旧实现 340-480 ms，≈3×） |
| 纯字面量（命中 100 条即停） | 61-81 ms |
| `filePattern = "browser4-coding/**/*.kt"`（命中 100 条） | 56 ms |
| 首次（页缓存已被 Maven 构建挤掉） | 388-419 ms（边界检查已有界，见 §五末；旧实现单此一项 5.7 s） |
| `budgetMillis = 10` | 18 ms 返回，带 `partial:` 脚注 |
| 大树 198k 文件 / 3.4 GB，60 s 预算 | 60 s 内返回（`rg` 收窄；冷态 `rg` 单遍扫描 37 s），不再是无界 20+ 分钟 |

（合成树上的 6× 是上限，本仓文件更小、也更接近 CPU 边界，所以只有 ~3×。）

## 三、结果预算错配（做的时候做满，给的时候只给 ~3%）

- `grep()` 收集到 **500** 条命中才停（`maxResults = 500`）。
- 输出最多打印 **200** 条（`CodingAgentFileSystem.kt:1284-1285`），其余静默丢弃（只有「N more」）。
- 到模型手里只剩 **1,600 字符**（`ToolOutcome.kt:81-94` `BODY_BUDGETS["grep"] = 1_600`，
  `boundedBody` 头 2/3 + 标记 + 尾 1/3）。按 `rel:line: ` + 200 字符正文估算，
  **约 6 行**可见 ≈ 收集量的 **3%**。
- 也就是说：为了 6 行结果，扫完整棵树。同时 `line.trim().take(200)`
  （`CodingAgentFileSystem.kt:1265`）意味着**命中若在第 200 字符之后，输出里根本看不到命中**——
  模型会把它当成误报，然后换工具重查（P1 已改为命中窗口，见 §五）。

## 四、顺带发现的正确性缺陷（都会伪装成「慢」，均已在 P1 修复）

1. **非 UTF-8 文件被整文件静默跳过**：`Files.readAllLines` 用严格 UTF-8 解码，
   抛 `MalformedInputException` 后被 `catch (_: Exception) {}` 吞掉。
   实测：GBK 编码的 `Gbk.java` 内含 `NEEDLE_MARKER` → 返回 `No matches`；
   同目录 UTF-8 文件的 marker 正常命中。Windows 上 GBK/UTF-16 源码很常见，
   模型会认为「文件里没有」并改用 `coding_shell` / `b4_run` 绕路。
   （按同样规则扫本仓，解码失败的文件在 6-9 个之间——恰好都是 `.doc/.odt/.pfx/.ogv/.webm/.xlsx`
   这类真二进制，影响为 0，但那是运气：Windows 上 GBK/UTF-16 源码会整文件消失。）
2. **`bin` 被整目录排除**（`DEFAULT_SEARCH_EXCLUDED_DIRS`，`CodingAgentFileSystem.kt:219-226`）：
   本仓 `bin/` 有 **120 个脚本**（`bin/test.ps1`、`bin/release/*`、`bin/build/*`），
   `grep`/`glob`/`listDir` 全都看不见。AGENTS.md 里指路的脚本正好在那儿。
3. **`.browser4`、`.b4scratch` 没有被排除**：生产环境开启 `persistSnapshots` 后，
   `.browser4/coding-snapshots/` 里是改动过的源文件副本，会被一起扫描并产生
   同名不同路径的重复命中；本机多 checkout 树里有 32 个 `.browser4`、12 个 `.b4scratch`。
4. **`filePattern` 只匹配 basename**（`pathMatcher.matches(file.fileName)`，`:1258`）：
   文档写「glob 模式」，但 `filePattern="src/**/*.kt"` 永远匹配不到 → 静默返回 `No matches`。
5. 排除目录名区分大小写（Windows 上 `Target/`、`Build/` 不会被跳过）。

## 五、修复方案与落地状态

### P0 — 已落地（2026-11-15）

实现在 `CodingAgentFileSystem.grep()`，签名变为
`grep(pattern, path = ".", filePattern = "*", ignoreCase = false, maxResults = 100, budgetMillis = 10_000)`：

1. **硬预算**：`DEFAULT_GREP_BUDGET_MS = 10_000`（`budgetMillis <= 0` 关闭，否则夹到 1 ms..`MAX_GREP_BUDGET_MS = 60_000`）。
   遍历期（`preVisitDirectory`/`visitFile`）与并行期（每文件）都检查 deadline，到点返回已完成部分，并打印：
   `partial: scanned N of M candidate file(s) in X ms (budget 10000 ms exhausted) — this result is INCOMPLETE; narrow path/filePattern …`。
   超预算且无命中时首行改写为 `No matches for 'p' in the N of M candidate file(s) scanned so far`，
   避免模型把它读成「工作区里没有」。
2. **并行扫描**：遍历只收候选（属性已在手，不额外 stat），再由 `min(8, cores, candidates/32)` 的固定池（守护线程 `b4-grep-<n>`）
   分连续切片扫描；共享命中计数器到上限即停；合并后按（路径，行号）排序，输出与调度无关。
3. **整文件读取 + 编码感知解码**：用 `visitFile` 的 `attrs.size()` 取代第二次 `Files.size()`；跳过 0 字节文件；
   每文件一次 `readAllBytes`（≤5 MB 上限），按检测到的编码解码后逐行匹配（合成树上字节预筛版 870 ms 已快于流式 929 ms）。
4. **命中上限与输出对齐**：新增 `limit`（默认 `DEFAULT_GREP_LIMIT = 100`，夹到 1..`MAX_GREP_RESULTS = 1000`），
   `CodingToolExecutor` 的 tool spec 与 CLI `code grep --limit` 都已暴露；`ToolOutcome.BODY_BUDGETS["grep"]` 1,600 → 4,000。
   命中上限截断时打印 `... (stopped at the N match cap — narrow path/filePattern or raise limit)`。
5. **可取消**：遍历与切片循环里调用 `callContext.ensureActive()`，CLI 超时/任务取消会真的停下扫描（之前会一直读完）。
6. **纯字面量快路径**：pattern 无正则元字符时先做字节级搜索（`indexOfBytes`），不命中就不解码。

### P1 — 已落地（2026-11-16，正确性）

7. **解码回退**：先严格 UTF-8 尝试；失败则用替换字符重新解码（不再整文件丢弃），
   结果尾部打印 `note: N file(s) were not valid UTF-8; their text was still searched after replacement`。
   GBK 源码里的 ASCII 符号/路径照样能被搜到。带 BOM 的 UTF-16（LE/BE）按 UTF-16 解码
   ——它们的字节里 NUL 交错，字面量预筛看不到 needle，所以这两种编码会跳过预筛。
   读失败（权限/占用）单独计为 `note: N candidate file(s) could not be read`。
8. **排除表**：加入 `vendor`、`deps`、`Pods`、`site-packages`、`obj`、`coverage`、`DerivedData`、
   `.terraform`、`.svelte-kit`、`.turbo`、`.yarn`、`.pnpm-store`、`.pytest_cache`、`.mypy_cache`、
   `.ruff_cache`、`.b4scratch`、`.browser4`；`bazel-*` 前缀也排除。
   **去掉裸 `bin`**：只有「构建产物形态」的 `bin` 才跳过（同级有 `obj/`，或子目录是
   `Debug/Release/x64/x86/classes`），仓库级放脚本的 `bin/` 重新可搜。
   Windows 上目录名比较改为大小写不敏感。
9. **`filePattern` 语义**：含分隔符时按相对 `path` 的路径匹配（`src/**/*.kt` 现在有效），
   否则仍按文件名匹配（`*.kt` 保持向后兼容）。
10. **命中窗口**：长行（压缩 JS、单行 JSON）里命中在第 200 字符之后时，输出改为
   `…<命中前 60 字符><命中>…`，不再给出「没有命中的命中行」。

配套单测（`browser4-coding`，`CodingAgentFileSystemEditsTest` 47 例全通过）：命中上限、超预算 partial、
排序、字面量/正则两条路径、GBK 文件仍可命中 + `note`、UTF-16 BOM、脚本 `bin/` 可搜 / `bin/Release` 跳过、
依赖与 scratch 目录排除、`filePattern` 路径语义、长行命中窗口。
`browser4-agentic` 的 `CodingToolExecutorTest` 同步到新参数并新增 `limit` 映射用例；
`browser4-coding` 全模块测试、`ToolOutcomeTest`、`cargo test --bin browser4-cli` 均通过。

### P2 — 已落地（2026-11-16）：ripgrep 收窄快路径

新增 `RipgrepSupport`（`browser4-coding/.../RipgrepSupport.kt`）。设计要点是**只借 ripgrep 做「哪些文件可能命中」**：

- `rg --files-with-matches --null --text --no-ignore --hidden --max-filesize 5M --threads 4`
  + 排除目录 glob + `(?m)<pattern>`；命中文件列表回到 Kotlin 侧，仍由原来的匹配器产出
  行号/命中窗口/非 UTF-8 解码/排序/`partial:` 脚注 —— 两个引擎的输出**逐字节一致**
  （`realRipgrepAgreesWithBuiltInScan` 用真 `rg` 断言）。
- **安全方言门限** `isSupportedPattern`：lookaround/内联 flag、反向引用、`\p{…}`、`\uXXXX`、
  `\A`/`\z`/`\Z`、`\Q…\E`、POSIX 类一律不委托（这些构造两个引擎要么不接受、要么语义不同，
  而「ripgrep 漏掉的文件」永远不会到达我们的匹配器）。`(?m)` 前缀是必需的：Kotlin 侧逐行匹配，
  不加它 `^`/`$` 只在文件边界命中。
- **探测窗口** `GREP_PROBE_MS = 1_200`：先自己走一遍树，走得完（小工作区）就根本不起进程；
  走不完才交给 ripgrep。实测本仓（3,950 文件 / 77 MB）：内置 107-141 ms，强制 rg 138-142 ms，
  所以小树上默认路径不付进程启动费。
- **失败一律回退**：未安装/被 `BROWSER4_CODING_RIPGREP=off` 关闭/退出码非 0-1/无法启动 →
  继续用内置扫描（必要时用剩余预算重走一遍），结果语义不变。
- 大树实测：`rg` 扫 `D:\workspace\Browser4`（198,166 文件 / 3.4 GB，冷）**37 s**（exit 0，列出 4 个文件），
  同一棵树内置扫描 >20 分钟仍未读完（§一-3）。
- 可观测：`partial:` 文案会注明 `ripgrep candidate scan stopped early`；`BROWSER4_RG_PATH` 可指定二进制，
  `-Dbrowser4.coding.grep.probeMs` 可调探测窗口（测试用它强制走 rg 分支）。

### 附带修复：MirrorRoots 的首调用全树遍历（2026-11-16）

查 P2 时发现一个**更早发生**的瓶颈：大树上的第一次 `coding.grep` 会先花几分钟做边界检查。

- 现象：`D:\workspace\Browser4`（198k 文件）上，新实例的**第一次** `resolvePath` 耗时 **226 s**；
  拆开看 `MirrorRoots.sourceRoot` **186 s**、`MirrorRoots.derive` **103 s**；`toRealPath` 本身 0 ms。
- 原因：`MirrorRoots.derive` 递归遍历整个 workspace，并对**每个条目（含文件）**调 `toRealPath()`
  来识别 junction；它由「懒构建的边界 Authorizer」在第一次解析路径时触发，
  所以 `grep/read/listDir/...` 的首个调用先付这笔钱。
- 修复：`MAX_DERIVE_DEPTH = 3`（镜像链接只可能出现在 `<ws>/<module>` 或 `<ws>/<group>/<module>`）
  + `MAX_DERIVE_ENTRIES = 2_000` + 跳过非目录条目（文件不可能是镜像根），成本从「文件数」变回「目录数」。
  单测覆盖：嵌套模块镜像仍被发现；超出深度契约的链接被忽略。
- 效果（同树同实例）：首次 `resolvePath` **226,370 ms → 234 ms**（第二次 0 ms）；
  64 文件小仓的首次 `grep`（含边界预热）**5,656 ms → 388 ms**，第二次 120 ms。


## 六、复现

```powershell
# 编译复刻基准
$jdk = "$env:JAVA_HOME\bin"
$bench = "docs-dev\copilot\coding-grep-perf-bench"
New-Item -ItemType Directory -Force -Path "$env:TEMP\grepbench" | Out-Null
& "$jdk\javac.exe" -d "$env:TEMP\grepbench" "$bench\GrepBench.java"

# 现有实现 vs 候选（先跑 first(cold)，再看 best=热态）
& "$jdk\java.exe" -cp "$env:TEMP\grepbench" GrepBench <树根> 'zzzzNO_MATCH_zzzz' current 2
& "$jdk\java.exe" -cp "$env:TEMP\grepbench" GrepBench <树根> 'zzzzNO_MATCH_zzzz' stream  2
& "$jdk\java.exe" -cp "$env:TEMP\grepbench" GrepBench <树根> 'zzzzNO_MATCH_zzzz' parallel 2
& "$jdk\java.exe" -cp "$env:TEMP\grepbench" GrepBench <树根> 'zzzzNO_MATCH_zzzz' literal  2
# 命中上限效果
$env:GREP_CAP='100'; & "$jdk\java.exe" -cp "$env:TEMP\grepbench" GrepBench <树根> 'class ' current 2
# 合成大树（2 万文件 / ~184 MB）
& "$jdk\java.exe" "$bench\GrepBench.java" gen "$env:TEMP\grep-synth" 100 200 120
```

注意：冷态数字依赖是否被 AV/系统缓存碰过，**必须同时报告 first(cold) 与 best(热)**；
本机为 Windows + Defender 实时/按访问保护开启（`Get-MpComputerStatus`）。

## 七、环境

- Windows，20 逻辑核，WD SN740 NVMe SSD（D: NTFS，Developer volume + AV filter）。
- JDK 25（GraalVM 25.0.3），Defender `RealTimeProtectionEnabled / OnAccessProtectionEnabled = true`。
- 冷态数字因此偏保守；热态数字与 AV 无关，可跨机器横向比较。
