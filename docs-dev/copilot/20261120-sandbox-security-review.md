# Sandbox 机制安全审查（2026-11-20）

> 范围：`browser4-coding` 的执行沙箱后端（`off` / `windows-acl` / `windows-low-il` / `windows-appcontainer`）、
> `SandboxedFileOps` 文件写下沉、`CodingComponents` 边界装配，以及它们的策略层入口（`coding.shell` / `coding.runCode` /
> `coding.write|delete|copy|move`）。
> 基线：`4.16.x` 工作树（`git status` 显示 sandbox 目录无改动，本次审查**未修改任何源码**）。
> 视角：安全审查（沙箱逃逸、权限边界、跨任务隔离），不是通用代码评审。

---

## 1. 方法与证据来源

| 步骤 | 内容 |
|---|---|
| 静态 | 通读 `sandbox/` 全部 13 个实现文件、`MirrorRoots`、`CodingComponents`，以及 shell / runCode / fileOps 三条调用链 |
| 历史对照 | 复核前一轮审查记录的 4 条问题（junction 逃逸、TEMP/TMP 覆盖、授权回收、探针误删），逐条回到当前代码确认状态 |
| 动态 | `mvn test -pl browser4-coding -Dtest=CodingAgentFileSystemMirrorBoundaryTest,WindowsLowIntegritySandboxTest,WindowsAppContainerSandboxTest,WindowsTokenLaunchTest,SandboxProbeTest,SandboxFileOpsTest` |

动态结果（surefire 报告时间 13:41，为本次运行）：6 个测试类共 **63 项，62 通过，1 跳过**（`WindowsTokenLaunchTest`
中一处平台相关 skip）。真实 Windows 令牌、真实 AppContainer、真实 junction 的用例均实际执行，不是 mock。

**局限**：未做两个真实任务并发运行的压力验证，跨任务隔离结论来自代码路径推导（见 S2）。

---

## 2. 结论

| # | 类别 | 问题 | 等级 | 置信度 | 状态 |
|---|---|---|---|---|---|
| S1 | path_traversal | 伪造镜像 junction 可把任意「带仓库标记」的目录变成可写根，并可上扩到整个检出 | 高 | 高 | **已修复**（清单化授权，§7.1） |
| S2 | idor | 三个 Windows 后端均未实现任务间工作区隔离，同一安全身份跨任务复用 | 中 | 高 | **已修复（部分）**：AppContainer 改为按任务身份；low-il / windows-acl 改为任务结束即撤销，并发窗口仍在（§7.2） |

上一轮记录的 4 条问题，当前代码中 **3 条已修复、1 条已明显收窄**（见 §4）。

---

## 3. 问题详情

### S1 — 伪造镜像 junction 仍可将区外检出变成可写根

**攻击路径**

1. 任务在**自己的工作区内**创建 junction（合法动作，策略层只禁用 `mklink`）：
   `powershell -Command "New-Item -ItemType Junction -Path <ws>\x -Target C:\other-checkout"`。
   内联脚本体**不做层级判定**（[CodingAgentShell.kt](file:///d:/workspace/Browser4/Browser4-4.16/browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/CodingAgentShell.kt#L2045-L2054)），
   因此这条命令绕过 [FORBIDDEN_COMMANDS](file:///d:/workspace/Browser4/Browser4-4.16/browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/CodingAgentShell.kt#L579-L580) 里的 `mklink` 禁令。
2. `MirrorRoots.derive` 见到「解析到工作区外」的链接，调用 `isCheckoutLinkedModule`，后者只要求目标**或其 4 层祖先**存在
   `pom.xml` / `package.json` / `.git` 等标记（[MirrorRoots.kt](file:///d:/workspace/Browser4/Browser4-4.16/browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/MirrorRoots.kt#L83-L90)、
   [MirrorRoots.kt](file:///d:/workspace/Browser4/Browser4-4.16/browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/MirrorRoots.kt#L117-L127)）。
3. 命中后该目标被写成 `MIRROR` 读写条目（[CodingComponents.kt](file:///d:/workspace/Browser4/Browser4-4.16/browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/CodingComponents.kt#L177-L185)），
   于是**策略层**放行写入，**内核层**同步放宽：`windows-low-il` 递归下调其完整性标签
   （[WindowsLowIntegritySandbox.kt](file:///d:/workspace/Browser4/Browser4-4.16/browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/sandbox/WindowsLowIntegritySandbox.kt#L183-L194)），
   `windows-acl` 授予该账户 `(OI)(CI)M`，`windows-appcontainer` 授予容器 SID `(OI)(CI)(M)`。

**升级面**：`sourceRoot` 取所有镜像目标的公共祖先，并沿 `pom.xml` 向上继续走（[MirrorRoots.kt](file:///d:/workspace/Browser4/Browser4-4.16/browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/MirrorRoots.kt#L146-L158)）。
只要伪造**一个**链接，公共祖先就是该目标自身，再向上即可得到整个检出根——也就是说单链接即可把授权面从一个目录扩大到整仓
（含 `.git`、`bin/`、`cli/`）。这比上一轮 issue 记录的「必须有仓库标记」更宽：标记只证明「这是一个检出的某处」，不证明
「这是本任务应当使用的检出」。

**复现要点**（与 `permission-e2e` A17 同构，只需把目标换成一个无关但合法的检出）：
在 `<ws>` 下建 junction 指向另一个本机仓库或其子模块 → 触发一次 `coding.shell`（仅触发 `prepare`）→ `icacls <目标>`
出现 `Mandatory Label\Low`，随后经 junction 写入成功。当前 `arbitraryLinkTargetIsNotAGrant`
（[CodingAgentFileSystemMirrorBoundaryTest.kt](file:///d:/workspace/Browser4/Browser4-4.16/browser4-coding/src/test/kotlin/ai/platon/pulsar/coding/CodingAgentFileSystemMirrorBoundaryTest.kt#L190-L211)）
用的是**无标记**普通目录，因此不覆盖此路径。

**建议**

1. 授权来源从「扫描出来的链接目标」改为**清单**：由 `WorkspaceMirror` 创建镜像时落盘（链接路径 + 目标 + 源检出 + 任务 ID），
   `MirrorRoots` 只信这份清单；清单本身放在任务不可写的后端目录，避免被伪造。
2. 若暂不引入清单，至少要绑定「同一源检出」：镜像目标的检出根必须等于 `sourceRoot` 推导出的那一个，而不是任意检出。
3. 把「创建链接」当成独立的 shell 效果拒绝（`mklink` 已被禁，但 `powershell`/`python` 内联体不受层级判定），或明确记录
   「OS 层是最终兜底」——但现在 OS 层同样被 S1 收买，兜底不成立。
4. 补一条系统性反向测试：junction 指向**另一个合法检出**时，策略层拒绝且目标目录标签保持不变。

### S2 — 三个 Windows 后端都没有任务间工作区隔离

**事实**

| 后端 | 共享身份 | 授权范围 | 回收 |
|---|---|---|---|
| `windows-low-il` | 单个 Low 令牌，进程内单例（[WindowsLowIntegritySandbox.kt](file:///d:/workspace/Browser4/Browser4-4.16/browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/sandbox/WindowsLowIntegritySandbox.kt#L160-L169)） | 每个任务的工作区**都被**降为 Low | 不回收（标签是持久副作用） |
| `windows-acl` | 同名账户 `browser4-agent`（[WindowsSandboxBootstrap.kt](file:///d:/workspace/Browser4/Browser4-4.16/browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/sandbox/WindowsSandboxBootstrap.kt#L228-L251)） | 每个任务的工作区都 `grant (OI)(CI)M`（[WindowsSandboxBootstrap.kt](file:///d:/workspace/Browser4/Browser4-4.16/browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/sandbox/WindowsSandboxBootstrap.kt#L302-L327)） | 不回收 |
| `windows-appcontainer` | 单个容器 SID，进程内单例（[WindowsAppContainerSandbox.kt](file:///d:/workspace/Browser4/Browser4-4.16/browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/sandbox/WindowsAppContainerSandbox.kt#L124-L133)） | 每个任务的工作区都 `grant` 给**同一个** SID | 按 6h 保留期回收，且**每个后端进程只清理一次** |

**结果**：Low 可以写 Low，所以任务 A 的子进程可直接写入任务 B 的工作区（若路径可知）；AppContainer 下同一 SID 对所有已授权
工作区有读写权；`windows-acl` 下同一账户在所有任务工作区都有 Modify。任务 B 结束后其目录仍是 Low、仍带 ACL，A 可在任意
时间后写入。进程树清理（`WindowsJob`）只约束「后端退出」，不约束「另一个任务」。

**为什么算问题**：产品的隔离承诺是 per-task（`docs/agent-permissions.md`、`--shared-workspace` 的存在都以此为前提），
而内核层把「工作区」实现成了「本轮所有工作区的并集」。上一轮把它记为「授权回收生命周期」问题，当前代码把**回收时机**收紧了
（[WindowsAppContainerSandbox.kt](file:///d:/workspace/Browser4/Browser4-4.16/browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/sandbox/WindowsAppContainerSandbox.kt#L163-L174)），
但**身份共享**这一根因未变，`windows-low-il` 与 `windows-acl` 完全没有回收。

**建议**

1. 每任务独立安全身份：AppContainer 用任务级容器名（`browser4-agent-<taskId>`），或为每个任务生成 restricting SID 并只用它
   授权该任务的工作区；`windows-low-il` 至少给工作区加一条「拒绝其他任务标志」的 ACL——更现实的是按任务加一个专属
   Low IL SID 条目。
2. 任务结束（含异常）时撤销该任务工作区的标签/ACL，并把「撤销」做成可观测的（`coding.permissions` 报告回收结果）。
3. 补测试：并发两个工作区，A 的 `coding.shell` 写 B 必须被内核拒绝；任务结束后 B 的目录不得残留可写标签。

---

## 4. 上一轮问题的复核状态

| 项 | 结论 | 证据 |
|---|---|---|
| TEMP/TMP 在真实启动时被令牌环境覆盖，指向系统全局临时目录 | **已修复**：`prepare` 写入工作区内 `.b4scratch/tmp`，启动时以 `declaredWins = {"TEMP","TMP"}` 让声明值胜出 | [WindowsLowIntegritySandbox.kt](file:///d:/workspace/Browser4/Browser4-4.16/browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/sandbox/WindowsLowIntegritySandbox.kt#L240-L262)；`WindowsTokenLaunchTest`（14 项）含该断言，`realChildIsConfined` 真实读回 `%TEMP%` |
| AppContainer 长任务授权可能被其他任务撤销 | **已修复**：清理改为每个后端进程仅一次，且 `keep` 包含本任务全部根 | [WindowsAppContainerSandbox.kt](file:///d:/workspace/Browser4/Browser4-4.16/browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/sandbox/WindowsAppContainerSandbox.kt#L163-L174)；`laterPrepareDoesNotRevokeLiveGrants` |
| 探针会删除工作区/用户目录下固定名称文件 | **已修复**：探针文件名带 UUID，仅删除自己创建的文件 | [WindowsLowIntegritySandbox.kt](file:///d:/workspace/Browser4/Browser4-4.16/browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/sandbox/WindowsLowIntegritySandbox.kt#L488-L522)、[WindowsAppContainerSandbox.kt](file:///d:/workspace/Browser4/Browser4-4.16/browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/sandbox/WindowsAppContainerSandbox.kt#L449-L488) |
| 工作区 junction 使区外目录被降标签并可写 | **已收窄，未关闭**：任意普通目录不再被接受，但「另一个合法检出」仍被接受 → S1 | [MirrorRoots.kt](file:///d:/workspace/Browser4/Browser4-4.16/browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/MirrorRoots.kt#L100-L127) |

另外确认以下设计是**显式声明**而非静默降级，不作为缺陷记录：`windows-low-il` 不强制网络
（`enforcesNetwork = false`）；AppContainer 不能承载 `b4.run`（`executesProductCommands = false`）；`windows-acl` 的文件写
留在后端进程（`executesFileOps = false`）。三者都在 `coding.permissions` 中如实报告。

---

## 5. 审查中确认有效的护栏

- **失败关闭**：后端不可用一律抛 `SandboxUnavailableException`（[ExecutionSandbox.kt](file:///d:/workspace/Browser4/Browser4-4.16/browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/sandbox/ExecutionSandbox.kt#L49-L57)、
  [ExecutionSandbox.kt](file:///d:/workspace/Browser4/Browser4-4.16/browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/sandbox/ExecutionSandbox.kt#L388-L405)），未知 id 在域构造期即失败。
- **写探测**：每个工作区首次 `prepare` 执行「内部可写 / 外部拒绝 / 凭据不可列」三段探测，不匹配即硬失败。
- **`.b4scratch` 链接防护**：`ScratchBoundary` 逐级比较真实路径，阻止 `.b4scratch` 或 `fs-bridge` 被 junction 重定向
  （[ExecutionSandbox.kt](file:///d:/workspace/Browser4/Browser4-4.16/browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/sandbox/ExecutionSandbox.kt#L498-L534)）。
- **环境白名单**：子进程只继承工具链与 OS 基础变量，密钥类变量不会隐式传递（[ExecutionSandbox.kt](file:///d:/workspace/Browser4/Browser4-4.16/browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/sandbox/ExecutionSandbox.kt#L417-L483)）。
- **文件写下沉**：`coding.write|delete|copy|move` 在支持的后端下进入沙箱子进程，失败即抛错而非回退到进程内写
  （[SandboxFileOps.kt](file:///d:/workspace/Browser4/Browser4-4.16/browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/sandbox/SandboxFileOps.kt#L186-L204)）。
- **进程树生命周期**：`WindowsJob` 以 `KILL_ON_JOB_CLOSE` 绑定后端，避免遗留 `mvn`/`python` 孤儿子进程。

---

## 6. 优先级建议

1. **P0 — 已完成** — S1：授权只信清单 + 「指向另一检出」的反向测试（§7.1）。
2. **P1 — 已完成（部分）** — S2：AppContainer 改为任务级身份；三个后端都在任务结束时撤销授权（§7.2）。
3. **P2 — 待做** — 把「创建链接」纳入 shell 效果判定，或明确写入威胁模型「链接创建不受策略层约束」（现在链接即便能造出来也不再产生授权，优先级下降）。
4. **P3 — 待做** — `permission-e2e` 增加 A17c：junction 指向另一检出（而非普通目录），断言 `code=SANDBOX_DENIED` 且目标标签不变。

---

## 7. 修复记录（2026-11-20）

### 7.1 S1 — 镜像授权改为清单驱动

**改法**

- `WorkspaceMirror.ensure` 建完链接后，把本次**实际成立**的 `链接 → 目标` 成对写进
  `MirrorRoots.writeManifest`：目标必须仍是链接、且仍解析到它镜像的那个模块，否则不记录（第二次 `ensure`
  不会把已存在的链接漏掉）。清单落在**工作区同级**的 `<workspace>.mirror-manifest`，任务不可写。
- `MirrorRoots.derive` 不再遍历工作区，只按清单逐条校验：链接存在、真实路径 ≠ 自身（仍是链接）、真实路径
  **等于记录的目标**、目标仍在清单记录的检出内、且仍是检出模块。任何一条不满足都不授权。
- `MirrorRoots.sourceRoot` 直接取清单里的检出（不再用"镜像公共祖先"推断），同样要求至少一个链接仍然成立，
  且目标确实是检出根。
- 旧版本建的工作区没有清单 → 按"无镜像"处理（fail-closed），镜像写入会被拒绝并给出明确错误，而不是静默放宽。

**效果**：新增链接、把链接改指向别的目录、或指向另一个合法检出，都不再产生策略层或内核层授权；
一发 junction 上扩到整个检出的路径消失。

**测试**

- `CodingAgentFileSystemMirrorBoundaryTest`：新增 `rePointedLinkToAnotherCheckoutIsNotAGrant`（指向另一检出的
  镜像链接既无 `derive` 也无 `sourceRoot`，写入被拒且目标无文件）、`manifestLivesOutsideTheWorkspace`；
  原有 11 个用例改为先写清单，深度限制用例改为"清单里过深的条目不被记录"。
- `WorkspaceMirrorTest.mirrorsModulesAndRootFiles`：断言 `ensure` 写出的清单恰好授予三个已链接模块，且源检出可写。
- `CodingAgentShellWorkspaceBoundaryTest.mirroredCheckoutWorkingDirAllowed`：补清单后，镜像检出任可作为工作目录。

### 7.2 S2 — 任务级身份与任务结束撤销

**改法**

- `ExecutionSandbox` 新增 `release(workspace)`（默认空实现，契约：尽力而为、幂等、只回收本任务拿到的授权）。
- `ExecutionSandboxes.bindTask` 记录"哪个后端实例 prepared 了哪个工作区"，`releaseTask` 在任务收尾时精确派发。
- `AgentToolManager.endTask`（`finally`，正常 / 失败 / 取消）调用 `ExecutionSandboxes.releaseTask(root)`，
  失败只记日志，不影响任务结论。
- `windows-low-il`：`prepare` 记录本任务的打标根；`release` 用 `icacls /setintegritylevel (OI)(CI)Medium`
  逐个恢复并删除打标标记（`taskRoots` 只含本任务，别的任务不受影响）。
- `windows-acl`：`release` 用 `icacls /remove:g <account>` 收回工作区 / 镜像根 / `--add-dir` 的授权，并释放缓存凭据。
- `windows-appcontainer`：容器身份改为**按工作区**（`browser4-agent-<path 摘要>`，同一工作区稳定、不同工作区不同 SID）；
  `release` 收回该任务的 ACE（`icacls /remove *SID`）并删除任务 profile（`DeleteAppContainerProfile`，可注入以便测试）；
  `isReady` 仍用安装级 profile 做能力探测。

**仍未关闭**：`windows-low-il` 与 `windows-acl` 的令牌 / 账户仍由安装共享，两个任务**同时在跑**时彼此可写；
撤销只是把窗口压到任务生命周期内。彻底隔离需要每任务独立安全身份（独立账户或 restricting SID），
本轮没有做——需要评估读范围回归（restricting SID 会让"仅属主 ACE"的文件读不到）。

**测试**

- `ExecutionSandboxTest.releaseTaskDispatchesOnce`：从未 prepared 的工作区是空操作；绑定的后端只被调用一次。
- `WindowsLowIntegritySandboxTest.releaseRestoresTheMediumLabel`：`release` 发出 `(OI)(CI)Medium` 恢复，
  第二次调用不再发命令。
- `WindowsAppContainerSandboxTest.eachWorkspaceGetsItsOwnContainer`：不同工作区得到不同容器名，同一工作区稳定。
- `WindowsAppContainerSandboxTest.releaseDropsTheTaskIdentity`：`release` 用 `*SID` 收回授权、删除对应 profile，且幂等。

### 7.3 验证

- `mvn -pl browser4-coding -Dtest=...` 跑 `coding` 边界与 `sandbox` 包共 **359 项**：**0 失败、2 跳过**
  （平台相关 skip）。
- 其中 `CodingAgentFileSystemLinkGuardTest.refusesToDeleteJunction` 在改动前就是失败的：`resolvePath` 先按
  **真实路径**判定，junction 指向工作区外时返回 `PATH_DENIED`，链接守卫的专用提示永远走不到。已在同一次修复里
  修掉：`delete` 先用**字面路径**（`lexicalPath`，不跟随链接）做链接守卫，工作区内的链接一律以
  "Refusing to delete a link or junction …" 拒绝，目标树不受影响；边界判定仍在其后执行。
- `mvn -pl browser4-coding -DskipTests install` + `mvn -pl browser4-agentic -DskipTests compile`：通过。
- 未运行 e2e（`permission-e2e` A17c 仍是 §6 的 P3）。
