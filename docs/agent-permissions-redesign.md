# agent run 权限体系（重构设计 v1）

状态：设计稿（2026-11-17）。目标是把今天"预设 + 字符串层 + 可选 OS 沙箱"三件套，收敛成
**两个模式 × 三条轴 × 一个内核执行层**的模型，并且让第 3 条（OS 层）成为唯一的强制点。

---

## 0. 今天的问题

| # | 现状 | 后果 |
|---|---|---|
| 1 | `--coding` 只是"任务类型"标记，实际权限由 `--access <preset>`（`full/write/read-build/read`）单独决定 | "browse vs coding" 与"能做什么"没有绑定关系，同一份权限可以出现在任一模式下 |
| 2 | 目标分散在 4 个布尔上：`allowExternalAccess` / `allowDestructive` / `allowNetwork` / `unrestricted` | 读、写、网三个轴互相耦合，无法表达"读放开、写收紧"（coding 需要的正是这个组合） |
| 3 | 字符串层（path guard、命令白名单）承担了"边界"的叙事，但它是**快失败**；真正的边界是 OS 沙箱，而 Windows 后端要管理员开账户，实践中常是 `off` | run `966a5f2e`：`python -c` / `-File x.ps1` / `%VAR%` 展开走出工作区，拒绝文案却宣称"OS 沙箱在强制" |
| 4 | 网络只有"命令名白名单"（拦 `curl`、放 `python urllib`） | 等于没有网络边界 |
| 5 | `b4.run stop` 等生命周期保护只靠命令名 | 子进程层面的 kill / rebuild 无保护 |

---

## 1. 新模型

### 1.1 两个模式（Mode）

`browse`（默认）与 `coding`（`--coding`）。模式决定**目标**，不直接决定细节：

| 目标 | browse | coding |
|---|---|---|
| 用途 | 围绕 browser4 命令与浏览器目标操作 | 围绕程序开发；可开发任意项目，含 Browser4 自开发 |
| 读文件 | **仅工作目录**（+ 显式只读授权根） | **继承当前用户**（凭据库除外，见 D1） |
| 写文件 | 仅工作目录 | 工作目录 + 构建缓存白名单（见 D2） |
| 执行 | 允许写脚本；解释器/命令按 allowlist | 任意代码执行（这是 coding 的前提） |
| 网络 | shell/脚本 **off**；浏览器进程 on | 默认 off + 逐域申请（见 D3） |
| 生命周期 | 禁 stop/kill/rebuild 本 backend（内核级） | 同左 |

### 1.2 三条轴（IsolationProfile）

模式编译成一份可执行的内核规则描述，`ExecutionSandbox` 只消费它：

```kotlin
data class IsolationProfile(
    val mode: Mode,                                   // BROWSE / CODING
    val read: ReadScope,                              // WORKSPACE_ONLY / CURRENT_USER(deny=[凭据库])
    val write: WriteScope,                            // WORKSPACE / WORKSPACE_PLUS(caches)
    val net: NetPolicy,                               // OFF / BROWSER_ONLY / ALLOW(hosts)
    val exec: ExecPolicy,                             // BROWSER4_ONLY / ALLOWLIST / ANY
    val lifecycle: LifecyclePolicy,                   // PROTECT_BACKEND（恒开）
    val extraReadRoots: List<Path>,                   // --add-dir（只读）
)
```

规则（编译期常量，不可由模型改写）：

- **browse** = `READ WORKSPACE_ONLY` + `WRITE WORKSPACE` + `NET BROWSER_ONLY` + `EXEC ALLOWLIST`
- **coding** = `READ CURRENT_USER − ALWAYS_REFUSED` + `WRITE WORKSPACE_PLUS(caches)` + `NET OFF(可申请)` + `EXEC ANY`
- `--unrestricted` = 显式 `profile = NONE`（`NET ALLOW(any)`、`READ CURRENT_USER`、`WRITE CURRENT_USER`），
  逐任务 opt-in、审计、且**凭据库与 lifecycle 两条护栏不被解除**（保持今天的语义）。

### 1.3 只有内核层是边界

三层职责从此固定，不再互相冒充：

1. **策略层**（preset/mode）：决定"要不要请求"，产生结构化拒绝与 hint。**advisory**。
2. **字符串层**（path guard、命令白名单）：**只做快速失败和可读的提示**，永不作为安全声明。
3. **内核层**（sandbox）：唯一强制点。必须对**子孙进程**生效（令牌/IL/LSM 规则都会被继承，
   Job Object 覆盖整棵树），这样"browser4 写的程序"同样受限。

`coding.permissions` 与所有拒绝文案必须打印**生效的内核后端**；后端不可用 → 模式降级 + `WARN` +
`code=UNENFORCED` 审计事件（今天 `SandboxDefaults` 的就绪门控思路，扩展到每个模式）。

---

## 2. OS 层怎么落地

核心洞察：两个模式需要的内核能力**不一样**，这正好对应两种 Windows 机制。

| 模式目标 | 机制 | 为什么是它 | 需要管理员吗 |
|---|---|---|---|
| coding：读=用户、写=工作区 | **Low/Untrusted 完整性级别令牌** | 强制策略只拦"向上写"（NO_WRITE_UP），读不受影响 → 天然满足"读继承、写收紧"；IL 被子孙进程继承 | 不需要 |
| browse：读=仅工作区、写=仅工作区 | **受限令牌 + restricting SID**（"沙箱 SID"） | 访问检查两趟都要 allow：restricted 列表放 `{沙箱 SID, Users, Everyone}`，profile 里没有 `Users` ACE → 直接拒；工作区/工具链交给沙箱 SID | 不需要（自己目录有 WRITE_DAC） |
| browse（隔离优先的备选） | **AppContainer** | 额外得到"无网络"（不给 `internetClient`），但兼容性成本最高（命名对象/管道/安装路径授权） | 不需要 |
| 生命周期（两模式） | **Job Object**（`KILL_ON_JOB_CLOSE` + 对 backend 进程保护句柄） | 命令名白名单挡不住 `python TerminateProcess`，进程层才行 | 不需要 |
| 网络 | Windows：WFP 过滤（需管理员，一次性）或"仅 loopback 代理 + 出口白名单"；Linux：`unshare-net` + 代理 | 只有网络层能表达 `BROWSER_ONLY` / `ALLOW(hosts)` | 视方案 |

具体配方（coding / Low IL，无需账户、无需管理员）：

1. 父进程用 `CreateRestrictedToken(maxPrivileges=DISABLE_MAX_PRIVILEGE)` 造令牌，再用
   `SetTokenInformation(TokenIntegrityLevel, Low)` 降级；`CreateProcessAsUserW` 启动子进程。
2. 工作区子树打 **Low 完整性标签**（对象属主可改；若机器策略要求 `SeRelabelPrivilege`，退化为 §2 的
   restricted-token 方案并在 `coding.permissions` 里说明）。
3. 构建缓存根（`~/.m2`、`~/.gradle`、`~/.npm`、`~/.cargo`、`Temp`）同样打 Low 标签：
   Medium 进程写 Low 对象是"向下写"，允许 → 你自己的 `mvn` 不受影响；agent 也能写。
4. 读：Medium 对象对 Low IL 主体**可读** → 继承用户权限；`ALWAYS_REFUSED` 凭据库用显式 deny ACE
   对 Low IL SID 生效（`~/.ssh` 等）。
5. 网络：IL 不管网络；`NET OFF` 用 WFP 规则（管理员一次性）或进程级代理。**若两者都不可用**，
   诚实降级：`NET UNENFORCED` 审计 + 提示词明说。

Linux/macOS：`landlock`（读写规则、无需特权、跨 `exec` 继承）是 browse/coding 两模式的统一答案；
`bwrap --unshare-net` 管网络与挂载；macOS 用 `sandbox-exec`(seatbelt) profile，已废弃但仍可用，
否则按"无强制 + 显著告警"处理。

---

## 3. 与现有代码的映射（迁移不推倒重来）

| 今天 | 新模型 |
|---|---|
| `AccessPreset.FULL/WRITE/READ_BUILD/READ` | 保留为**模式内的细粒度**（browse 默认 `write`，coding 默认 `full`）；新增 `--coding/--browse` 选择 `Mode` |
| `allowExternalAccess` / `allowDestructive` / `allowNetwork` / `unrestricted` | 收敛为 `IsolationProfile`；旧属性做兼容映射并打 deprecated 日志 |
| `ExecutionSandbox.prepare(spec, workspace, allowNetwork, extraWritableRoots)` | 改为 `prepare(spec, profile)`；现有 `windows-acl` 变成 backend 之一 |
| `WindowsAclSandbox`（账户令牌 + icacls） | 保留（隔离最强、要管理员）；新增 `WindowsLowIntegritySandbox`（coding 默认）与 `WindowsRestrictedTokenSandbox`（browse 默认） |
| 字符串层 `workspaceBoundaryViolation(...)` | 保留，但所有文案统一走 `SandboxStatus`（已实现），且不再有"边界"叙事 |
| `SandboxDefaults` 的全局 `v2:<id>` | 升级为"每模式一个后端 + 每模式就绪探测"；`v2` marker 保留一次迁移语义 |
| `b4.run stop` 命令名白名单 | 保留为快失败；真正保护下沉到 Job Object + 保护句柄 |

---

## 4. 三条不变式（写成 e2e 探针，任何后端都必须过）

1. **browse**：工作区外 `open(r"C:\Users\<u>\.ssh\id_rsa")`、`Paths.get("D:/").toFile().list()` 必失败；
   工作区内写脚本、执行 `b4` 成功。
2. **coding**：工作区外**读**成功（`~/.m2`、`D:/`、`~/Documents`），**写**失败（含 `~/.ssh`、`~/Documents`、
   `C:/Windows/Temp/...`）；写 `workspace` 与 `~/.m2` 成功。
3. **网络**：browse 的 shell 里 `socket.create_connection(("example.com",443))` 失败、浏览器导航成功；
   coding 的 shell 默认失败，`--net-allow example.com` 后仅该 host 成功。
   三条都用 `python -c`（而不是 curl）验证——命令名白名单不是边界。

---

## 5. 需要拍板的决策

| # | 问题 | 建议 |
|---|---|---|
| D1 | coding 的"读继承用户"是否包括凭据库（`~/.ssh`、`.aws`、`.m2/settings.xml`）？ | **已定的答案：不含** —— 用 Medium 强制性标签 + `NO_READ_UP`：Low 子进程读不到，Medium 的你自己照常读 |
| D2 | 构建缓存白名单给多大？ | `~/.m2`、`~/.gradle`、`~/.cache`、`%TEMP%`（取自 `toolchainEntries()`），根目录打标、不做深遍历 |
| D3 | coding 的网络默认？ | **已定的答案：默认 off + 逐域申请**（`--net-allow`）；`windows-low-il` 管不了 socket，必须由 AppContainer/过滤器兑现，未兑现时如实上报 |
| D4 | browse 的 `--add-dir` 是否允许？ | **只读**允许，写仍限工作区 |
| D5 | Windows browse 走 restricted-token 还是 AppContainer？ | **已定的答案：隔离优先 → AppContainer**（另外它天然给出"无网络"）；restricted-token 降为备选 |

---

## 6. 分期

- **P0**（小，1 天）：`Mode` 进 policy；`browse`/`coding` 各出一份 `IsolationProfile`；
  `coding.permissions` 与提示词只说生效层（`SandboxStatus` 已就位）。**目标：任何路径都不再宣称未兑现的边界。**
- **P1**（核心，2–4 天）：`WindowsLowIntegritySandbox` → coding 的 OS 强制（无需账户/管理员）；
  三条不变式的 Windows 探针。**目标 2 达成。**
- **P2**（2–4 天）：`WindowsRestrictedTokenSandbox` → browse 的读写双限。**目标 1 达成。**
- **P3**：Job Object 生命周期 + 网络强制（WFP 或代理）+ Linux landlock + macOS seatbelt。

验收用 `coding.verify` 的结构化 verdict，外加独立探针脚本（三条不变式 × 三平台矩阵）。

---

## 7. 已落地的契约（P0，2026-11-17）

模型已经在代码里，并且是**被测试钉住的**，但还没有接到执行路径上（不改变任何现有行为）：

| 代码 | 作用 |
|---|---|
| `ai.platon.pulsar.coding.AgentMode` | `BROWSE` / `CODING`，与 `--coding` 标记绑定 |
| `IsolationProfile` | 三条轴 + exec + `--add-dir`；`forMode(...)` 就是 §1.2 的矩阵 |
| `ReadScope` / `WriteScope` / `NetPolicy` / `ExecPolicy` | 封闭集合，后端不能"差不多对" |
| `IsolationProfile.defaultCaches()` | 从 `CodingComponents.toolchainEntries()` 取 `maven/gradle/cache/tmp`，JDK 与 OS 根永不在内 |
| `IsolationProfile.backendChain(mode)` | Windows：browse→`windows-restricted-token`、coding→`windows-low-il`，`windows-acl` 仅兜底；Linux→`landlock`/`bwrap`；macOS→`seatbelt`；未知平台→空（必须报 `UNENFORCED`） |
| `IsolationProfile.unrestricted()` | `--unrestricted` 也有显式 profile，便于审计与展示 |
| `IsolationProfileTest` | 8 条断言：browse 最小面、coding 读放开写收紧、凭据库仍拒、caches 不含 JDK/OS、`--add-dir` 只加读、`--unrestricted` 显式、后端选择、`describe()` 覆盖每一条轴 |

P1 的接线点：`AgentAccessPolicy` 增加 `mode: AgentMode`（由 `--coding`/`--browse` 填充）→
`CodingComponents` 用 `IsolationProfile.forMode(...)` 构造 `ProcessSpec` 的 profile 字段 →
`ExecutionSandboxes` 按 `backendChain(mode)` 选后端。

---

## 8. P1 已实现：`windows-low-il`（coding，免账户免管理员）

代码：`sandbox/WindowsLowIntegritySandbox.kt`；测试：`WindowsLowIntegritySandboxTest`（5 例，含真实子进程）。

| 环节 | 做法 |
|---|---|
| 令牌 | `OpenProcessToken` → `CreateRestrictedToken(DISABLE_MAX_PRIVILEGE)` → `SetTokenInformation(TokenIntegrityLevel=Low)`；`CreateProcessAsUserW` 接受它（受限令牌是调用者令牌的派生物，不需要 `SeAssignPrimaryToken`），**后端自身令牌不动** |
| 写边界 | 工作区/`--add-dir`/任务 temp 用 `icacls /setintegritylevel (OI)(CI)Low` 深打标；构建缓存只打根（避免深遍历 `~/.m2`，新文件按创建者 IL 继承）。Medium 进程"向下写"法律 → 你自己的 shell 不受影响 |
| 凭据（D1） | `~/.ssh` 等加 **Medium + `NO_READ_UP`** 强制性标签（`InitializeAcl` + `AddMandatoryAce` + `SetNamedSecurityInfoW`）：Low 子进程读不到，Medium 的你照常读 |
| 网络 | **不强制**（`enforcesNetwork = false`）。`NetPolicy.Off` 必须由 AppContainer 或主机防火墙兑现；未兑现就上报，不假装 |
| 自检 | 每个工作区首次使用跑三段探针（工作区内写必须成功、工作区外写必须失败、凭据目录必须列不出）；不通过就 `SandboxUnavailableException`，绝不裸启动 |
| 默认 | 仍是 `windows-acl`。`windows-low-il` 是**按模式**的后端：在 `AgentMode` 接线落地前把它设成全局默认，会把 browse 任务的 `b4` CLI（要写 `~/.browser4` 这类 Medium 对象）一起关进去。所以今天用 `-Dbrowser4.agent.sandbox=windows-low-il` 显式开启（推荐的 coding-only 配置），P1.5 随模式接线成为 coding 默认 |

实测（本机，普通用户、非管理员）：Low 子进程在工作区内写成功、在 `~` 下写被拒、列 `~/.ssh` 被拒；探针脚本用 PowerShell `-File` 传路径，避免 `cmd /c` 自带引号与 launcher 的 C 运行库引号规则打架（这个坑真踩了：`echo x> "C:\a b\f"` 报"文件名、目录名或卷标语法不正确"）。

已知约束：`icacls /setintegritylevel` 需要目标上的 **WRITE_OWNER**。产品的工作区在 `<app data>/agent/...`（用户私有）没问题；`%TEMP%` 这类继承自共享目录的位置不行 —— 后端现在对此直接报错（"cannot lower the mandatory label … needs WRITE_OWNER"），而不是含糊地失败在探针上。

---

## 9. P1.5 已实现：模式接线（`--coding` → profile → backend）

| 环节 | 做法 |
|---|---|
| 模式来源 | `AgentAccessPolicy.mode`（默认 `BROWSE`）；`StatefulAgentRunner` 在 `applyAccessPolicy` 前用既有的 `codingMode` 覆写为 `CODING`/`BROWSE` —— 任务类型从此是**权限输入**，不只是提示词提示 |
| 后端选择 | `CodingComponents.sandbox(policy)` → `ExecutionSandboxes.resolveForMode(mode)`：操作者显式 `-Dbrowser4.agent.sandbox` 优先；否则按 `backendChain(mode)` 取**第一个 ready** 的后端；都不 ready 时回落到安装默认（含 `off`，由 `SandboxStatus` 如实上报） |
| 就绪判定 | `ExecutionSandbox.isReady()`：`windows-low-il` = 能造出 Low 令牌；`windows-acl` = 账户与凭据齐备；未实现的后端在链里被跳过而不是致命 |
| 可观测 | `coding.permissions` 新增一行：`OS sandbox: <id> — mode=… read=… write=… net=… exec=…; network: enforced/NOT enforced`。目标与强制层从此在同一行里区分 |
| 打标成本 | 工作区/镜像根深打标；**缓存根也深打标但只做一次**——`~/.browser4/sandbox/low-il/<sha256>.label` 记录已完成，重启不再重走（构建会重写 `~/.m2` 里已有文件，浅打标不够） |
| 镜像/工作树 | 与账户后端一致：`MirrorRoots.derive(workspace)` 一并打标，模块 junction 的写入才合法 |

**已知缺口（P3）**：linked worktree 的 `git` 元数据在 `<main>/.git/worktrees/<task>`（工作区之外且不是镜像根），沙箱内的 `git` 写操作会被拒；产品自己的 patch/merge 在沙箱外执行，所以主流程不受影响，但需要 `git` 写命令的任务要等 P3 把 git 目录纳入可写根。

**实测**：本机 66+35 例定向测试通过，其中 `windows-low-il` 的 5 例含真实子进程（工作区内写成功、工作区外写被拒、注入的凭据目录列不出、Medium 进程仍可读）。默认后端未改（仍是 `windows-acl`）；coding 任务在本机走 `resolveForMode(CODING)` 时会选中就绪的 `windows-low-il`。

---

## 10. P3 第一步：生命周期与"边界覆盖范围"的诚实披露

### 10.1 `stop`/`kill` 的结构性保护已由内核给出（新增实测）

过去"agent 不许杀 backend"只靠命令名白名单（`processKillViolation`），`python ctypes TerminateProcess` 这类写法在理论上是漏的。Low 子进程 + Medium 后端之后，这条**由强制完整性接管**：终止更高完整性级别的进程属于"向上写"，内核直接拒。

新测试 `WindowsLowIntegritySandboxTest.childCannotTerminateAMediumProcess`：起一个 Medium 的受害者进程，让 Low 子进程 `taskkill /PID <pid> /F` → 退出码非 0、受害者仍存活。也就是说，**coding 模式下这条护栏不再依赖字符串匹配**。

> 注意：这只在沙箱开启时成立。`off`/`--unrestricted` 时仍只有命令名白名单，Job Object（`KILL_ON_JOB_CLOSE`）留给 P3 的清理与 `off` 场景。

### 10.2 边界覆盖范围写进 `coding.permissions`

沙箱只包住走 `ProcessLauncher` 的通道。`describePermissions` 现在明说哪些通道在边界内、哪些只在策略层
（下面是最初的形态；P3-3/P3-4/P3-6 之后 `b4.run`、技能脚本与文件写操作已经进入边界，现文案见 §16）：

```
OS boundary (windows-low-il) covers: coding.shell, coding.runCode, coding.mvnBuild/verify.
NOT covered (backend process, policy layer only): coding.write/read/glob, b4.run,
skill scripts, git/worktree/merge, LSP/ripgrep.
```

沙箱为 `off` 时改为：`OS boundary: none — every channel … is governed by the policy layer only.`

### 10.3 剩余收口清单（按危害排序）

| # | 通道 | 现状 | 收口方式 |
|---|---|---|---|
| 1 | `coding.write/read/glob`（后端自身） | ~~Medium，策略层~~ → **写操作已下沉**（§16） | 把写操作下沉到沙箱子进程执行，或后端整体降级 |
| 2 | `b4.run` → `CliProcessManager` | Medium 子进程 | 需要 CLI 的 state 目录落在沙箱可写根内（`~/.browser4` 的 Medium 状态文件目前不可写），属设计项 |
| 3 | ~~`SkillScriptToolExecutor`（python）~~ | **已接入接缝**（2026-11-17，见 §11） | 沙箱解析为 `off` 时保留历史 `ProcessBuilder` 路径 |
| 4 | git/worktree/merge、LSP、ripgrep | 后端 | 产品流程本身；`git` 的 linked-worktree 元数据在工作区外（见 §9 已知缺口） |
| 5 | 网络 | `windows-low-il` 不强制 | AppContainer（browse）或 WFP/代理（coding） |
| 6 | ~~进程清理 / `off` 场景的生命周期~~ | **已实现** Job Object + `KILL_ON_JOB_CLOSE`（沙箱内进程树），见 §11 | `off` 时仍在 JVM 内，无 job |

---

## 11. P3 第二步：进程树生命周期 + 技能脚本入界

### 11.1 Job Object（`KILL_ON_JOB_CLOSE`）

`WindowsJob`（`sandbox/WindowsJob.kt`）为每个 `windows-low-il` 实例创建**一个** job，
`WindowsTokenLauncher.startWithToken(..., job)` 在 `CreateProcessAsUserW` 成功后把子进程加入该 job。
效果：backend 退出/崩溃 → 子进程整棵树随之终止；任务起的守护进程不再活过任务。

- 加入 job 是**尽力而为**（已被禁止嵌套的 job 成员会拒绝），失败只记日志、不影响命令执行。
- `CloseHandle` 触发终止，`close()` 幂等。
- 测试 `WindowsJobTest`（3 例）：关闭 job 后成员进程确实死亡；重复 close 无副作用且关闭后不再收新成员；对已退出 pid 的 attach 返回 `false` 而不是抛异常。

> 与 §10.1 的分工：**Low 令牌**负责"子进程不能杀 Medium 的 backend"（越权方向），**Job** 负责"backend 走了，子进程也走"（清理方向）。

### 11.2 技能脚本走同一条接缝

`SkillScriptToolExecutor.startScript(command, root)`：

- 解析任务沙箱（`sandbox()`，由 `AgentToolManager` 用 `CodingComponents.sandbox(accessPolicy)` 注入）与任务工作区；
- 有沙箱且不是 `off` → 构造 `ProcessSpec`（cwd = skill 根、环境 = `SandboxEnvironment.build(env, sessionEnv)`）→ `prepare(spec, workspace, allowNetwork=false)` → `ProcessLauncher.start(...)`；
- 没有沙箱/工作区 → 保留历史 `ProcessBuilder`（继承环境、无边界），并 debug 记账；
- 沙箱化时打 info 日志（`skill script runs inside the OS boundary: backend=…`），便于事后核对。

`sessionEnv` 传入的是 `AgentToolManager.cliSessionEnv()`，这样脚本里调用 `browser4-cli` 仍能命中当前 backend/session（否则会被环境白名单剥掉）。

### 11.3 现在还剩什么

| # | 通道 | 收口方式 |
|---|---|---|
| 1 | `coding.write/read/glob`（后端自身，Medium） | **写操作已下沉**（§16）；`coding.read/glob/grep` 仍在后端，按设计 |
| 2 | ~~`b4.run` → `CliProcessManager`（Medium 子进程）~~ | **已接入接缝**（2026-11-17，见 §12） |
| 4 | git/worktree/merge、LSP、ripgrep | 产品流程本身；linked-worktree 的 git 元数据见 §9 |
| 5 | 网络 | AppContainer（browse）或 WFP/代理（coding） |
---

## 12. P3 第三步：`b4.run` 进入沙箱

`b4.run` 曾是最宽的 agent 通道：它在 backend 的自身完整性级别起 `browser4-cli`，沙箱里的任务照样能借它写到任意位置。

### 12.1 做法（`CliProcessManager`）

| 环节 | 实现 |
|---|---|
| 边界 | 新增 `sandbox` / `workspace` 供应商（`AgentToolManager` 用 `CodingComponents.sandbox(accessPolicy)` 与 `workspaceRoot` 注入）；有边界且不是 `off` 时，`ProcessLauncher.start(prepare(...))` |
| CLI 状态 | CLI 需要写 `~/.browser4`（Medium，沙箱内不可写）。启动时显式设 `BROWSER4_CLI_STATE_DIR=<workspace>/.b4scratch/cli-state`——CLI 本来就有这个开关，且它在 `PermissionDenied` 时也会回退到 CWD 下的 `./.browser4-cli-state`，所以这是同一条既有机制的显式写法 |
| stdin | 令牌启动把 stdin 接成管道；无 payload 时启动后立即 `close()`，让子进程马上看到 EOF（否则会重演 2026-10-29 那个"读 stdin 卡到超时"） |
| 回退 | 无边界/无工作区 → 保留历史 `ProcessBuilder`；有边界时 `prepare` 失败会抛错，绝不裸启动 |
| 进程树 | 与 shell 一致：子进程进入同一个 Job Object（§11.1），backend 退出时一起清理 |

### 12.2 验证

`CliProcessManagerSandboxTest`（3 例）：状态目录落在工作区、请求的工作目录优先且 session pinning 变量保留、legacy builder 的目录被尊重。既有 `CliProcessManagerTest` 14 例无回归。

### 12.3 现在还剩什么

| # | 通道 | 收口方式 |
|---|---|---|
| 1 | `coding.write/read/glob`（后端自身，Medium） | **写操作已下沉**（§16）；读仍在后端 |
| 3 | git/worktree/merge、LSP、ripgrep | 产品流程本身；linked-worktree 的 git 元数据在工作区外（§9） |
| 4 | 网络 `net=off` | AppContainer（browse）或 WFP/代理（coding） |
| 5 | browse 读+写双限 | **`windows-appcontainer`** —— 下一个大头 |
---

## 13. P3 第四步：`windows-appcontainer`（browse 的读+写双限）

Integrity level 只收紧**写**，而 browse 模式要的是"工作目录之外的文件一律访问不到"——包括读。这只能用容器做。

### 13.1 机制

| 环节 | 实现 |
|---|---|
| 容器身份 | `CreateAppContainerProfile("browser4.agent.browse", …, capabilities=null, 0)`；已存在（`HRESULT 0x800700B7`）则 `DeriveAppContainerSidFromAppContainerName`。**每用户、不需要管理员** |
| 授权 | `icacls <path> /grant *S-1-15-2-…:(OI)(CI)(M)`：只有工作区与 `--add-dir` 目录被授予；用户 profile、`D:\`、`~/.ssh` 全部不在访问集内 |
| 启动 | `CreateProcessW` + `STARTUPINFOEX`：`InitializeProcThreadAttributeList` → `UpdateProcThreadAttribute(PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES)` → `EXTENDED_STARTUPINFO_PRESENT`。管线程/环境/命令行的机器码复用 token 路径，两个后端行为一致 |
| 网络 | **不声明任何 capability**（没有 `internetClient`）→ 容器**无法出网**。浏览器在后端进程里、不在容器内，这正好是 `NetPolicy.BrowserOnly` |
| 家目录 | 容器的 `USERPROFILE`/`APPDATA`/`LOCALAPPDATA`/`TEMP` 指到 `<workspace>/.b4scratch`，普通的工具"想写个 cache"不会因为真实 profile 不可读而失败 |
| 进程树 | 同一个 `WindowsJob`（§11.1） |
| 自检 | 每工作区首次：区内写必须成功、`%SystemRoot%\Temp` 下写必须失败、真实 profile（含 `.ssh`）必须列不出；不通过 → `SandboxUnavailableException` |

### 13.2 实测（`WindowsAppContainerSandboxTest`，6 例）

- 真实容器里 `cmd.exe` 能跑（区内写落盘）；
- `%SystemRoot%\Temp` 下的写被拒；
- 真实 `%USERPROFILE%\.ssh` 列不出；
- `enforcesNetwork = true`（唯一能兑现 `net=off` 的后端）；
- 未就绪/探针失败 → 硬失败，不裸启动。

### 13.3 为什么默认仍是 opt-in

容器读不到用户 profile，而 **`browser4-cli` 本身住在 profile 里**（npm 全局安装 / `~/.browser4` 状态）。把 browse 默认切成容器，会先打断 browse 任务赖以生存的 `b4.run`。所以：

- `ExecutionSandbox.autoSelectable`：`resolveForMode(chain)` 会**跳过**不可自动选择的后端；
- 打开方式：`-Dbrowser4.agent.sandbox.browse=on`（让 browse 链选它）或 `-Dbrowser4.agent.sandbox=windows-appcontainer`（直接指定）；
- 后续把它变成默认的前提：给容器读授权 `browser4-cli` 的安装目录 + runtime，或用容器外的 loopback 代理转发 `b4.run`。

### 13.4 踩坑记录

- `HRESULT_FROM_WIN32(ERROR_ALREADY_EXISTS)` 是 `0x800700B7`（= `-2147024713`）。我一开始手算成 `-2147024777`，把"配置已存在"这种正常情况变成了硬失败；现在常量直接写成 `0x800700B7.toInt()` 并注明不要手算。
- 探针里的凭证检查不能再依赖 `%USERPROFILE%`（已被重定向到工作区），改为由探针注入 `B4_SANDBOX_PROBE_HOME` 指向真实 profile；含空格的路径会让 `dir` 失败，这只会让检查**更严**，而不会放宽。
- `icacls /grant` 同样需要目标上的 **WRITE_DAC**；共享目录继承来的权限会失败，后端对此直接报错（与 low-il 的 `WRITE_OWNER` 要求同理）。
---

## 14. 第五步：容器"读 CLI 目录"的实验与一个必须记住的坑

目标是让 browse 默认能开容器。前提是容器内能跑 `browser4-cli`，而 CLI 与 runtime 都在用户 profile 下。实验做了两件事，结论都要保留：

### 14.1 已证实的坑：容器 SID 不能授予任务工作区的**共享祖先**

给 `~/.browser4` 加一条 AppContainer SID 的 ACE（`(OI)(CI)(RX)`，容器需要读 runtime/CLI）之后，**其下 Low-IL 沙箱工作区立刻全部失败**：Low 子进程对刚刚由 backend 写好的探针脚本报 `Access is denied` / "文件不存在"，而 Medium 的 backend 一切正常。移除这条 ACE 后立刻恢复（83 例沙箱测试全绿）。

规则：**容器授权只落在任务工作区自身**（以及你能完全控制的安装目录），永远不要落在会托管其它沙箱工作区的共享祖先上。文档、代码与测试都按这条来（`grant()` 的 required 语义 + 测试断言"只有工作区被授权"）。

### 14.2 还没解决：祖先目录的 traverse 授权

即便给 `%APPDATA%\browser4` 授予 `(OI)(CI)(RX)`，容器仍然读不到——因为访问需要**每一级祖先**都允许 traverse，而 `C:\Users`、`C:\Users\<user>` 归系统所有，普通用户没有 WRITE_DAC，改不了。所以"容器内直接跑 CLI"这条路要么：

1. 在容器外做一个 **loopback 代理** 转发 `b4.run`（容器只需访问 127.0.0.1 的代理端口，但容器默认无网络能力，需要给它 `privateNetworkClientServer`）；
2. 把 CLI 及其 runtime **复制进工作区**（工作区已授权，且容器可执行其中文件）；
3. 由管理员**预置** traverse ACE（部署成本高，不做默认）。

在那之前，browse 的 AppContainer 仍保持 opt-in（`-Dbrowser4.agent.sandbox.browse=on`），默认不启用以免打断 `b4.run`。

### 14.3 探针经验

Low-IL 的探针用**工作区内的 PowerShell 脚本**（`-File <script> -Mode write -Path <path>`），路径作为普通 argv 传递；`cmd /c "echo x> y"` 这种带重定向的载荷在 Low 令牌下曾整片失败（当时 ACL 已被 14.1 的授权污染，未单独证伪），因此保留已验证的脚本方案，并在 KDoc 里写明两种引号规则叠加的问题。

---

## 15. 进展快照（执行侧）

做了什么、代码清单、测试明细、机器状态、开关与回滚、未收口清单，见
[`docs-dev/copilot/20261117-agent-sandbox-progress.md`](../docs-dev/copilot/20261117-agent-sandbox-progress.md)。
起点是 run `966a5f2e` 的复盘：[`docs-dev/copilot/20261116-agent-permission-bypass-review.md`](../docs-dev/copilot/20261116-agent-permission-bypass-review.md)。

---

## 16. 第六步：文件写操作下沉到沙箱子进程（P3-6）

`coding.write/append/replace/editLines/delete/mkdir/copy/move/revert` 曾是**后端进程自己**做的
（Medium 完整性、拥有者身份），策略层与字符串层是唯一挡在前面的东西。§10.3 把它列为第一条待收口：
这是 agent 手里最宽的一条通道，一次路径判定漏洞就等于一次无约束写入。

### 16.1 机制：写操作交给边界内的子进程

| 环节 | 实现 |
|---|---|
| 接缝 | `SandboxFileOps`（`write`/`mkdirs`/`delete`/`copy`/`move`，失败抛 `IOException`）；`CodingAgentFileSystem` 新增 `mutationOpsProvider`，**所有**变更操作在真正落盘前问它 |
| 装配 | `CodingComponents.fs` 注入 `{ fileOps(policy, root) }` → `CodingComponents.fileOps` → 本任务解析出的 `ExecutionSandbox` → `SandboxedFileOps.forSandbox(...)`；`--unrestricted` 与"不会执行文件操作的后端"返回 `null`（保留历史进程内路径） |
| 后端能力 | `ExecutionSandbox.executesFileOps`（默认 `false`）：`windows-low-il` 与 `windows-appcontainer` 为 `true`；`windows-acl` 保持 `false`（它的账户 provisioning 从未围绕这条通道设计，`coding.permissions` 如实标注） |
| 执行 | 每次操作启动一个 `powershell.exe -File <workspace>/.b4scratch/fs-bridge/b4-fs.ps1 -Op … -Target …` 子进程，经 `sandbox.prepare` + `ProcessLauncher` 启动 —— 与 `coding.shell` 完全同一条启动接缝，所以边界不是第二份实现 |
| 载荷 | 内容先由后端写成工作区内的暂存文件（`.b4scratch/fs-bridge/payload-*.bin`），子进程用 `ReplaceFile`/同卷 rename 换入目标位置；跨卷退化为复制+删除。命令行上只有路径（约 32KB 上限、以及字节编码问题都由此避开） |
| 原子性 | 保持 `writeAtomic` 的语义：读者只看到旧文件或新文件；暂存文件在成功/失败后都清掉 |
| 记账 | 后端自己的簿记（`.browser4/coding-snapshots/` 快照、任务重置）**不进**边界：路径由代码选择而非模型选择，为它付一次子进程启动是纯损耗 |

### 16.2 为什么是"一键一进程"而不是常驻助手

常驻助手能省下每次约 0.4s 的启动开销，但会引入单飞锁、挂起检测、重启、孤儿清理这一整套生命周期，
而它们的失败模式恰好是本轮重构要消灭的"静默"那一类（一次卡住 = 工具调用永远不返回）。
当前形态的代价是**可预测的**：一次变更 = 一次 `powershell -File`（实测约 0.3–0.5s，
`SandboxFileOpsTest` 的 8 次操作 ~2.5s），相对一轮 LLM 推理或一次构建可以忽略；如果实测发现它主导了任务时长，
再换成批量协议，接口不用动。

### 16.3 引号规则（第三次踩同一个坑）

launcher 用 C 运行时规则给 argv 加引号，`cmd /c`/`-Command` 再自己解析一遍，带引号/空格的载荷活不过两层
（§7.3、§14.3 已记录过两次）。这里的做法：脚本以 `-File` 方式启动，路径作为**普通 argv** 传入，内容根本不上命令行。
另外 `-Recursive` 用 `[string]` 而不是 `[switch]`，避免 `-Recursive true` 里的 `true` 被当成位置参数。

### 16.4 验证（`SandboxFileOpsTest`，7 例；AppContainer 4 例）

| 断言 | 类型 |
|---|---|
| 每个变更方法都恰好把改动交给边界一次（write/mkdir/append/replace/copy/move/delete） | 接线，跨平台 |
| 边界拒绝 → 工具返回 `Error: …Access is denied.` 且目标文件不存在 | 失败关闭 |
| `mutationOpsProvider` 抛异常 → 报错且不落盘（**没有**进程内回退） | 失败关闭 |
| 快照簿记仍在进程内（边界只收到 1 次 write） | 记账边界 |
| 只有声明 `executesFileOps` 的后端才拿到这条通道（low-il/appcontainer 是，acl/off 不是） | 契约 |
| 真实 Low-IL 子进程：区内写/建目录/复制/移动/删除全部成功，CRLF 与非 ASCII 字节完全一致；**区外写被 OS 拒绝**并抛 `IOException` | 真实边界 |
| 真实 AppContainer 子进程：`powershell.exe` 能起、区内写落盘、profile 下的写被拒 | 真实边界 |
| 生产接线端到端：`AgentAccessPolicy(mode=CODING)` → `CodingComponents.fs` → 真实后端 → `coding.write` 成功，工作区外写被拒 | 接线（真实后端） |

### 16.5 现在还剩什么

| # | 通道 | 状态 |
|---|---|---|
| 1 | `coding.read/glob/grep` | 留在后端，**按设计**：coding 的读目标就是继承用户；browse 的读范围就是工作区，容器读得到的正是后端读得到的 |
| 2 | 容器内跑 `browser4-cli`（`b4.run`） | 未解决，见 §14.2（loopback 代理 / 复制进工作区 / 管理员预置 traverse） |
| 3 | coding 的网络 `net=off` | 未兑现，同上（代理或 WFP） |
| 4 | git/worktree/merge、LSP、ripgrep | 产品流程本身，不入界 |
| 5 | browse 默认启用容器 | 依赖 #2 |

**一个已知的"策略比 OS 宽"点**：`coding.copy`/`coding.read` 的**源**可以在工作区之外（可读根 `ReadablePaths`）。
`windows-low-il` 下没问题（读继承用户）；`windows-appcontainer` 下源在工作区外时子进程读不到，
于是复制/读取会以 OS 拒绝失败——这与容器"profile 不可读"的定位一致，`coding.permissions` 也一直这么写，
但它是**策略层允许、强制层拒绝**的方向，遇到时应当理解为边界在说话，而不是工具坏了。
