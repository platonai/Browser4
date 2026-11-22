# Agent 权限体系（实现视角）

> 范围：内置智能体（`agent run` / MCP 直调）的 `coding.*`、`skill.*` 工具，以及它们背后的文件系统、Shell、进程执行边界。
> 视角：**内部实现**——对象模型、强制链、护栏、审批机制、审计与防漂移。
> 面向操作的用法（预设怎么选、拒绝码怎么读、`/approve` 怎么用）见
> [skills/browser4-cli/references/agent-access.md](../skills/browser4-cli/references/agent-access.md)；
> 历史设计与 M0–M7 的实现记录见
> [docs-dev/copilot/agent-run-permission-hardening-plan.md](../docs-dev/copilot/agent-run-permission-hardening-plan.md)。
> **当前 as-built 总览**（模式 × 四轴 × 五层 × 四后端、通道覆盖矩阵、已知缺口）见
> [docs-dev/copilot/20261117-agent-run-permissions-report.md](../docs-dev/copilot/20261117-agent-run-permissions-report.md)；
> 未强制/未接线/已拍板"不强制"的逐条登记（G1–G8，含根因、危害、复测与收口代价）见
> [docs-dev/copilot/20261117-agent-permission-known-gaps.md](../docs-dev/copilot/20261117-agent-permission-known-gaps.md)；
> 沙箱后端的设计与实验见
> [docs/agent-permissions-redesign.md](agent-permissions-redesign.md)。
> 最后核对：2026-11-18（新增 §10 故障排查；§10.8 第 1、2 条已实现；复核 `windows-low-il` / `windows-appcontainer`、文件写下沉、`b4.run` 产品通道）。符号名稳定，行号会漂移，定位请以类/方法名为准。

---

## 1. 定位

权限体系的主体是**任务（task / run）**，不是用户或角色：这里没有 RBAC，只有 **per-task capability**。
核心实现不在 `browser4-agentic`，而在 **`browser4-coding`**；agent 层只负责装配、门禁调用和提示词披露。

| 角色 | 实现 | 职责 |
|---|---|---|
| 唯一事实源 | `AgentAccessPolicy` | 一个任务的 preset + 各条护栏 + 生效中的授权 |
| 唯一决策点 | `Authorizer` | 纯函数：`Request → Allow / AllowWithBudget / Deny` |
| 唯一拒绝语汇 | `PermissionAudit` | `PERMISSION_DENIED \| code=…` 结构化拒绝、计数、单次日志 |
| 唯一放宽通道 | `AccessApprovals` | 待批队列 + `OPERATOR` capability |
| 唯一装配点 | `CodingComponents` | 按策略构造 fs / shell / sandbox，避免三处手搓漂移 |

四条原则贯穿全篇：

1. **单一事实源**——MCP、CLI、REST、内部构建都从同一个 policy 派生，`CodingComponents` 是唯一构造点；
2. **失败关闭（fail closed）**——未分类的 `domain.method` 需要 `full`；未知 sandbox id 直接启动失败；判定不出目标即拒绝；
3. **纵深防御**——注册表裁剪 → 方法门 → 组件投影 → 边界 Authorizer → OS 沙箱，上层被绕过时下层仍挡；
4. **拒绝是数据不是散文**——带 `code` / `hint` / `needed`，可计数、可分类、可自诊断。

拒绝**不会**让任务失败：一次被拒的步骤只是该步骤失败，终态仍由 `system.taskComplete` 决定
（`PermissionAudit` 的 KDoc 与加固计划 §8 决策 1 同口径）。

---

## 2. 对象模型

| 概念 | 位置 | 说明 |
|---|---|---|
| `AccessPreset` | `AgentAccessPolicy.kt` | `full` / `write` / `read-build` / `read`，从宽到窄；`parse` 接受 `read_build` 大小写 |
| `ToolGate` | `AgentAccessPolicy.kt` | `READ` / `BUILD` / `MUTATE` / `EXEC`，每个方法必须落进唯一一档 |
| `AgentAccessPolicy` | `AgentAccessPolicy.kt` | preset + `unrestricted`、`allowExternalAccess`、`allowNetwork`、`allowDestructive`、`denyTools`、protected/sensitive 清单、`additionalDirs`、`operatorGrants` |
| `Effect` | `Capability.kt` | 闭集：`fs.read` `fs.write` `fs.exec` `net.connect` `proc.spawn` `secret.read` |
| `Target` | `Capability.kt` | `PathTarget` / `HostTarget` / `CommandTarget`，各自规范化：real path、去尾点小写、去 `.exe/.cmd/.bat/.com/.ps1` 后缀 |
| `Scope` | `Capability.kt` | 读写路径根、hosts、commands、launcher 豁免、`canonicalize` 开关 |
| `Capability` | `Capability.kt` | 效果集 + 作用域 + `Limits` + 来源（`PRESET` / `TASK` / `OPERATOR`）+ `expiresAt` |
| `Limits` / `Cost` | `Capability.kt` | 三个维度（时长 / 写入字节 / 执行次数）；`null` ≠ `0` |
| `Authorizer` / `Decision` | `Authorizer.kt` | 判定引擎；`Deny` 必带 `CapabilityRequest`，让调用方能告诉模型「该申请什么」 |
| `PermissionAudit` | `PermissionAudit.kt` | 拒绝/事件计数与格式化 |
| `AccessApprovals` | `AccessApprovals.kt` | 待批队列、护栏检查、capability 签发、惰性过期 |

`Effect` 是**故意封闭**的枚举：要加效果就得改枚举和它的测试，不存在运行时用字符串拼组合的姿势。

---

## 3. 四个预设的能力矩阵

| 能力 | `read` | `read-build` | `write` | `full` |
|---|---|---|---|---|
| 读工作区 | ✓ | ✓ | ✓ | ✓ |
| 项目构建 `mvnBuild` / `verify` / `validate` | ✗ | ✓ | ✓ | ✓ |
| 改工作区文件 | ✗ | ✗ | ✓ | ✓ |
| shell / `runCode` | ✗ | ✗ | ✗ | ✓ |

两个容易误解的点：

- **`read-build` 会写 `target/`**：`allowsFileMutation()` 为 false，但构建入口走的是**独立的 build-only shell**
  （`CodingComponents.buildShell`，`buildOnly = true`），所以「不许改源码」与「可以跑构建」并存；
- **`--add-dir` 是作用域不是权力**：额外目录进入读写边界、也进入沙箱 ACL，但 `--access read` 下它仍然只读。

推导规则（`requiredPresetOf` / `presetAllows`）：

| 门 | 需要的最低预设 | 谁放行 |
|---|---|---|
| `READ` | `read` | 所有预设 |
| `BUILD` | `read-build` | `read-build` / `write` / `full` |
| `MUTATE` | `write` | `write` / `full` |
| `EXEC`，以及**未分类**的方法 | `full` | `full` |

未分类方法按 `full` 处理，即新增工具默认在受限预设里**不可达**——失败关闭而不是静默放宽。

不受预设门控的域（`UNGATED_DOMAINS`）：`tab`、`browser`、`agent`、`b4`、`system`、`memory`、`batch`、`driverEx`、`experience`。
预设约束的是**工作区权力**（文件、shell、代码执行）与能执行脚本的 `skill` 面，浏览类工具在任何预设下都可用，
否则 `--access read` 连页面都打不开。`skill.*` 单独分级：列举/查看/激活/读文档是 `READ`，
`run`/`runScript`/`install`/`uninstall`/`reload` 是 `EXEC`。

`denyTools` 支持 `*`、`domain.*`、`domain.method`、裸 `method`，并且**先于**「免门域」判断生效，
所以连 `b4.run` 这类免门方法也能单独封掉。目前没有 CLI 开关，只在策略对象与 `deny` 无人值守模式中使用。

---

## 4. 一次工具调用的五层决策链

```
① 工具列表裁剪   buildCliEngineToolSet ── 被拒方法从注册表消失（exposeTools 也取不回）
② 方法门         CodingToolExecutor.callFunctionOn → policy.denial() → ToolAccessDeniedException
③ 组件投影       CodingComponents.fs / shell → readOnly / allowExecution / commandAllowedProvider
                （+ fs 的 mutationOpsProvider：写操作交给沙箱内的子进程执行）
④ 边界授权       Authorizer：CAP_FS_BOUNDARY（canonical）/ CAP_SHELL_BOUNDARY（lexical）
⑤ 执行沙箱       ExecutionSandbox + 环境白名单 + 运行时否决
```

### ① 工具列表裁剪

`RobustBrowserAgent.buildCliEngineToolSet` 在构造 registry **之前**过滤：
`policy.allowsTool(domain, method)` 为假的方法不进入初始集合，也不进入披露池——`system.exposeTools`
是同一个列表的第二个视图，因此无法把被拒方法复活。`system.*` 豁免，
否则 `denyTools=["*"]` 会让任务连 `system.taskComplete` 都没有，只能靠散文收尾（finish gate 会拒）。

### ② 方法门

`CodingToolExecutor.callFunctionOn` 对每个调用先跑一次 `policy.denial(domain, method)`；
被拒时抛 `ToolAccessDeniedException`（携带格式化后的 `PERMISSION_DENIED` 文本）。
`mutationDenial()` 处理「读形态合法、写形态不合法」的方法（例如 `coding.protect` 不带 `path` 是列举保护项）。
`denial()` 的 `hint` 会点名**需要的效果 id**（不是预设 id），因为 `coding.requestAccess` 要的是效果。

### ③ 组件投影

`CodingComponents.fs` / `.shell` 把策略字段**投影**成组件级开关：`readOnly`、`allowExecution`、
`commandAllowedProvider`、`writeAllowedProvider`（参数级，用于 operator 授权）。这样即便有人直接持有
fs / shell 实例，也无法重开工具门已经关掉的东西。

### ④ 边界授权

`CodingComponents.boundaryAuthorizer(root, additionalDirs)` 为同一个工作区构造**一个** Authorizer，
上面挂两个 grant：

| grant | 作用域语义 | 用途 |
|---|---|---|
| `cap-fs-boundary` | canonical（`toRealPath`，跟随 junction/symlink） | 文件工具 |
| `cap-shell-boundary` | lexical（按字面）+ 工具链根 + launcher 豁免 | shell |

两个 grant 都覆盖 workspace、镜像模块根（`MirrorRoots`）、源检出（`ScopeKind.SOURCE`）、
`--add-dir` 目录、其他任务的工作区（`ScopeKind.TASK_WORKSPACE`，见下），以及**只读**的可读根
（`ReadablePaths`）；工具链根（JDK、`.m2`、`.gradle`、`.cache`、
`tmp`、OS 目录）只给 shell。作用域由 `scopeManifest` 单一派生，manifest 与 grant 不会漂移。
Authorizer 按 `(root, 可读根, add-dir, 其他任务工作区)` 缓存，LRU 上限 64（每个任务一个新工作区，不设上限会随进程增长）。

**其他任务的工作区**由 `TaskWorkspaces` 从布局推导，只认
`<app data>/agent/<yyyy>/<MM>/<dd>/<agent-uuid>/workspace[.<task-id>]` 这一种形状，不看名字：
`workspace.archive`（中断任务的归档交付物）、`<agent data>/agent/skills`（可执行 skill 的部署点）、
隔离 worktree（`<repo>/.browser4/worktrees/<task-id>`，未合并改动的唯一副本）**不在**其中；
后者要用 `--add-dir` 显式交接。开关是 `browser4.agent.allowTaskWorkspaceWrites`（默认 `true`），
窗口 `browser4.agent.taskWorkspaceWriteDays`（默认 7 天，钳制 1..400）、上限 64 个根。
两个后果要在心里备着：① `filesChanged` 从此只证明「本次 run 写过」，不再证明「由本次 run 产出」；
② 集合在策略生效时解析，**之后**启动的任务不在其中（用 `--add-dir` 补）。
沙箱只拿到每个工作区的**日目录**（`TaskWorkspaces.sandboxRoots`）：一天一个 label/ACE，而不是一个任务一个，
字符串层仍是精确列表。

### ⑤ 执行沙箱与进程层

`ExecutionSandboxes`（`-Dbrowser4.agent.sandbox`）是**强制层**，字符串层只是「快失败」。
沙箱无法启动就抛 `SandboxUnavailableException`，绝不静默降级为裸启动；沙箱内的 OS 拒绝会被翻译成
`code=SANDBOX_DENIED`（原始 OS 证据只进日志）。进程环境由 `SandboxEnvironment` 重建：只继承白名单变量
（PATH / JAVA_HOME / maven / gradle / 临时目录等），长生命周期密钥不会被继承；显式声明的密钥仍然放行，
但记一条 `ENV_PASSTHROUGH` 事件。

后端**按模式**选择（P1.5）：`--coding` 走 coding 链（`windows-low-il` → `windows-acl`），即使安装默认是
`off` 也会拿到真实的写强制；browse 不会落到 coding 后端（完整性级别不管读），在 AppContainer 落地前保持
安装默认。`coding.permissions` 会打印后端 id、模式的四条轴，以及"网络是否被强制"。

**授权在任务结束时撤销**：三个 Windows 后端共用安全身份（一个 Low 令牌、一个 `browser4-agent` 账户、或过去的一个
容器 SID），所以"某个任务拿到的工作区"如果不能收回，就是**下一个任务**的常驻写权限。`ExecutionSandbox.release(workspace)`
在任务收尾（`AgentToolManager.endTask` 的 finally，正常结束 / 失败 / 取消都走这里）执行：low-il 把该任务打过标的根恢复成
Medium 并删掉打标标记，`windows-acl` 用 `icacls /remove:g` 收回账户授权并释放凭据，appcontainer 收回该任务的 ACE 并删除
**任务专属**容器 profile。AppContainer 的身份是**按工作区**的（`browser4-agent-<path 摘要>`），因此两个任务不再共享一个
SID；low-il / windows-acl 仍共享身份，撤销只把窗口限制在任务生命周期内，任务**并发**运行时彼此可写这一点没有消失
（2026-11-20 审查 S2）。

**文件写操作也在强制层内**（P3-6）：`coding.write`/`append`/`replace`/`editLines`/`delete`/`mkdir`/
`copy`/`move`/`revert` 的实际改动由**沙箱内的子进程**执行（`SandboxedFileOps`，后端声明
`executesFileOps` 即可；`windows-low-il` 与 `windows-appcontainer` 都是如此）。后端过去是 Medium 进程，
所以这条通道曾是最宽的一条——字符串层一旦有洞，写就出去了；现在补上的是内核判定：路径先打完包，
再由 Low/容器子进程搬到目标位置，跨出工作区的写入被 OS 拒绝，工具返回结构化错误。命令行的引号规则不能
叠加，因此载荷走**工作区内的暂存文件**（`.b4scratch/fs-bridge/payload-*.bin`），只把路径作为普通 argv
传给 `-File` 脚本；`replace`/`move` 的语义保持原子（`ReplaceFile`/同卷 rename，跨卷退化为复制+删除）。

读仍然在后端进程里，这是设计而非缺口：coding 的读目标就是"继承当前用户"，而 browse 的读范围就是工作区
（容器读得到的正是后端读得到的）。`coding.permissions` 按后端把这些"在/不在边界内"的通道写清楚。

**`b4.run` 是产品通道，按后端能力决定是否入界**（2026-11-17 拍板）：`browser4-cli` 装在操作者 profile 里、
通过 `127.0.0.1` 访问后端，与 git/worktree/LSP 同类。`ExecutionSandbox.executesProductCommands` 声明
本后端能否托管它——容器为 `false`（profile 不可达，且无能力容器连 loopback 都被拒；实测连 DNS 都不通），
所以 browse+容器模式下 `b4.run` 保留历史的后端进程启动，`coding.permissions` 把它列进 NOT covered
（`b4.run (this backend cannot host browser4-cli)`）。这不是静默回退：能力是声明式的，报告由它生成；
也没为了它给容器开 `privateNetworkClientServer`（那会放开主机本地所有服务）。

沙箱为 `off` 时，字符串层的拒绝文案会明确写出「没有 OS 沙箱在运行」，不再暗示身后存在强制层
（2026-11-16：`ADVISORY_NOTE` 从常量改成按 `ExecutionSandboxes.configuredId()` 生成的动态文案）。

---

## 5. 护栏（rails）：批准也跨不过去

| 护栏 | 内容 | 实现 |
|---|---|---|
| 工作区边界 | `allowExternalAccess=false`（默认）时，写与 shell 越界即拒；其他任务的工作区是**默认在界内**的例外，但只有布局合法的 `workspace[.<task-id>]` 才算 | `Authorizer` + fs/shell + `TaskWorkspaces` |
| 受保护文件 | `VERSION`、`AGENTS.md`、`CLAUDE.md`、根 `pom.xml`、`browser4-dependencies/pom.xml`、`ci.yml`、`b4w.*`、`bin/build*`、`bin/recover*`、`mvnw*` | `DEFAULT_PROTECTED_FILES` |
| 受保护目录 | `.browser4`（快照 / 检查点 / worktree）、`.github/workflows`、`.mvn` | `DEFAULT_PROTECTED_DIRS` |
| 敏感路径 | `~/.ssh`、`~/.aws`、`~/.gnupg`、`~/.config/gcloud`、`~/.browser4` | `DEFAULT_SENSITIVE_PATHS` |
| 绝对拒绝 | `ReadablePaths.ALWAYS_REFUSED`：即使加可读根、即使 `--unrestricted` 也不放行 | `SensitivePaths.isRefused` 先判它 |
| 命令分级 | `SAFE` / `DEV` / `BUILD` / `NETWORK` / `DESTRUCTIVE` / `MUTATING` + `BLOCKED_PATTERNS` + 内联脚本递归校验 | `CodingAgentShell` |
| 运行时自毁 | `taskkill` 后端、`b4w build/stop`、`bin/build.ps1`、`mklink`/`ln`/`mount` 等 | `handOffVetoViolation` |
| CLI 逃逸 | `b4.run` 的 lifecycle / `skill install` / `tool call` 被拒；preset ≠ `full` 时 `b4.run` 的直调 MCP 命令被拒 | `b4CliLifecycleRefusal`、`b4CliPolicyRefusal` |

判定顺序很重要（`SensitivePaths`）：**先**看 OS 凭证库（永不放宽）→ 再看是否命中敏感根 → 命中时还要看
是否落在可读根内（`~/.browser4` 同时在两张表上，可读根的放宽正是让 agent 能读自己的日志与数据）。
两个纯/带放宽的 API 特意分开：`isSensitive` 是纯成员判定（`TaskInputs` 用它，绝不能继承放宽），
`isRefused` 才是工具实际调用的那个。

---

## 6. 审批：非阻塞的能力申请

`coding.requestAccess(effect, target, reason?, ttlSeconds?)` 归在 `READ` 门——**任何预设都能调**；
它立即返回 pending 的 `requestId`，不阻塞 agent 循环；同一 `effect + 种类 + target` 去重，
避免重试刷屏；已批准且授权仍有效时也返回同一条，授权过期后才允许重新申请。

四条通道汇聚成同一个 `OPERATOR` capability：

| 通道 | 入口 | `AccessSource` |
|---|---|---|
| REST | `GET api/agent/{taskId}/access`、`POST …/{requestId}/approve`、`…/deny?reason=` | `rest` |
| CLI | `agent run -i` 内的 `/access`、`/approve`、`/deny`；非 TTY 结束时打印待批摘要 | `cli` |
| 策略文件 | `<workspace>/.browser4/access-policy.json`，任务启动时预批准 | `file` |
| 无人值守默认 | `browser4.agent.access.unattended` | `default` |

关键设计：

- **护栏在「申请」与「批准」两处都检查**（`AccessApprovals.guardrailViolation`）：
  申请一个**永远不可能被批准**的目标（凭证库 `~/.ssh`、受保护文件、工作区外且禁止外访的路径、白名单外的程序）
  会**立即**转成 `denied` 并带上原因码（`ACCESS_DENIED`），模型不必等一个不存在的操作员；
  批准时再查一次，因为策略可能在「申请」与「决定」之间变化（策略文件条目尤其如此）。
  跨 `denyTools`、受保护路径、凭证路径、工作区边界、命令白名单的申请 REST 回 **409**
  （客户端据此区分「还没决定」与「不可能被决定」）。
- **授权是目标级作用域**（`scopeFor`）：批准一个路径只覆盖那个路径，批准 `echo hi` 只覆盖程序名 `echo`；
  `AgentAccessPolicy.operatorAllows` 复用同一个 `Authorizer` 做匹配，不另写一套路径比较。
- **方法门只做效果级判定**：它看不到调用的目标，所以 `operatorCoversRequirement` 只回答「有没有覆盖该效果的
  grant」；真正的目标级收紧在会读取作用域的层（fs / shell）。
- **TTL + 惰性过期**：`expiresAt` 到点后在下一次判定时丢弃并记一次 `ACCESS_EXPIRED`，无需后台定时器。
- **每任务一个队列**：`AgentToolManager.applyAccessPolicy` 新建 `AccessApprovals`，并把
  `policy.copy(operatorGrants = { approvals.activeCapabilities() })` 挂到策略上——批准在下一次工具调用即生效，
  不需要重建组件。任务结束 `resetAccessPolicy()` 只留一份只读快照给终态查询；共享的 companion agent
  不会继承上一个任务的授权。
- **策略文件在受保护目录里**：`.browser4` 不可写，任务**无法改写自己的权限**；文件解析刻意宽容
  （缺文件、坏 JSON、坏条目都只是跳过并告警），但护栏一条都不宽容。

REST 控制器**故意不开 CORS**：批准等于授予写文件/执行命令的能力，agent 访问的页面绝不能跨源读到或答这个队列。

---

## 7. 任务级策略的推导优先级

```
显式 access=  →  serverDefault.withTaskOverrides(preset)
无人值守      →  unattendedDefault            （任务没指名 preset 且无 TTY）
其余          →  serverDefault
      ↓
     withAdditionalDirs(--add-dir)            // 只扩作用域
      ↓
     withUnrestricted(--unrestricted)         // 最后一句话，pass-through
      ↓
     applyAccessPolicy(taskPolicy, taskId)    // 建队列 + 载策略文件 + 重建 fs/shell/target
```

实现位置：`StatefulAgentRunner`（推导）→ `AgentToolManager.applyAccessPolicy`（落地）。
推导顺序有个硬约束：**工作区重绑定必须在 applyAccessPolicy 之前**，否则读的是上一个工作区里的
`access-policy.json`，重建的组件也指向旧根。

| 配置 | 键 / 开关 | 默认 |
|---|---|---|
| 服务端预设 | `-Dbrowser4.agent.access` | `full`（外部访问关、破坏性操作开、网络关） |
| 无人值守默认 | `-Dbrowser4.agent.access.unattended` | `full` + 允许网络；`deny` 用 `denyTools=coding.*,skill.*` 表达 |
| 任务预设 | `agent run --access <preset>` | 继承服务端 |
| 额外目录 | `agent run --add-dir`、REPL `/add-dir`、`/dirs [clear]` | 无 |
| 其他任务工作区 | `-Dbrowser4.agent.allowTaskWorkspaceWrites`、窗口 `-Dbrowser4.agent.taskWorkspaceWriteDays` | **开**（写其他任务的 `workspace[.<task-id>]`；`workspace.archive`、`agent/skills`、隔离 worktree 不在内） |
| 全权 | `agent run --unrestricted` | 关 |
| 可读根 | `-Dbrowser4.agent.readPaths` | Browser4 数据根、`~/.browser4`、CLI 工作区、源检出 |
| OS 沙箱 | `-Dbrowser4.agent.sandbox` | Windows：默认 `windows-acl`（新装与老装一次性升级）；`windows-low-il` 是**免账户免管理员**的 coding 推荐后端（写与凭据读强制、网络不强制）；其它平台 `off`（待 P2）；库/测试 `off` |
| 额外受保护路径 | `-Dbrowser4.agent.protected.extra` | 无 |

`--unrestricted` 是 pass-through：preset 置 `full`、清空 `denyTools` 与 protected/sensitive 清单、
允许外部访问/网络/破坏性操作、`Authorizer.unrestricted()` 直通、shell 的命令分层也解除。
**两件事不解除**：OS 凭证库（`ALWAYS_REFUSED`），以及会摧毁运行时自身的命令（`handOffVetoViolation`）——
「用我的权限」不等于「把我的密钥交给模型」。

沙箱默认值由 `SandboxDefaults.apply` 在应用入口（不是库默认）决定：显式属性永远优先；全新安装取平台后端
（Windows 目前是 `windows-acl`，Linux/macOS 待 P2）；**老装一次性升级到平台后端**——它的历史 `off` 只是
「边界还不存在时的自动默认」，从来不是操作者的决定，而 `off` 时字符串层就是唯一边界（2026-11-16 对
run `966a5f2e` 的复盘：`python -c`、`powershell -File`、`%VAR%` 展开都走出了工作区，而拒绝文案还在宣称
「OS 沙箱在强制」）。

升级**以就绪为条件**（`WindowsSandboxBootstrap.isReady()`：账户 `net user` 可用 + 凭据可读，只检查、
不改 ACL）。未就绪则保持 `off` 并打出 `sandbox boundary NOT enforced: …`，且**不 pin 决定**，管理员补好
账户后下一次启动自动升级——因为把不可用的后端打开会让每条 `coding.shell` 硬失败，那不是边界而是故障。

决定写进 `<data>/.browser4/sandbox-default`，格式 `v2:<id>`：`v2:off` 是真正的操作者决定，后续启动不再
升级；显式 `-Dbrowser4.agent.sandbox=off` 是随时可用的回滚。

---

## 8. 可观测性

**结构化拒绝**（`PermissionAudit.format`）：

```
PERMISSION_DENIED | code=TOOL_DENIED | tool=coding.shell | detail=… | hint=… | needed=fs.exec
```

主要 code：

| 分组 | code |
|---|---|
| 方法/预设 | `TOOL_DENIED`、`READ_ONLY`、`EXEC_DISABLED` |
| 路径 | `PATH_OUTSIDE_WORKSPACE`、`PATH_DENIED`、`SENSITIVE_PATH`、`PROTECTED_PATH` |
| 命令 | `COMMAND_NOT_ALLOWED`、`COMMAND_PATH_DENIED` |
| 预算/沙箱 | `BUDGET_EXCEEDED`、`SANDBOX_DENIED` |
| 审批 | `ACCESS_REQUESTED`、`ACCESS_APPROVED`、`ACCESS_DENIED`、`ACCESS_EXPIRED` |
| 事实事件（非拒绝） | `OUT_OF_SCOPE_WRITE`、`NETWORK_TARGET`、`SCOPE_UNCERTAIN`、`ENV_PASSTHROUGH` |

拒绝与「事实事件」共用一张计数表但语义相反：拒绝证明某事**没有**发生，事件证明它**发生了**。
日志按 `tool:code` 每种形态只记一次（拒绝与事件分开计数），重试循环不会变成日志风暴；
沙箱层的 `sandbox denied: sandbox=… tool=… command=… evidence=…` 是**每次**拒绝一条，用于按次归因
（`permission-e2e` 的日志断言读它，而不是读按形态去重的 `coding access denied` 行）。

**传输层信封**：`PermissionAudit` 按契约「返回、不抛」，所以拒绝在工具结果是**字符串**。这意味着
`isError`/退出码曾经都表示成功（`tool call --fail-on-error` 对拒绝也退出 0，issue `20261120-0145`）。
现在 REST 分派层识别**行首**的 `PERMISSION_DENIED | code=…`（容忍 `coding.fs` 的 `Error: ` 前缀），把信封
标成 `isError=true` + `errorCode=PERMISSION_DENIED`，具体原因进 `structuredContent.permissionCode`
（`PATH_DENIED` / `PROTECTED_PATH` / `SANDBOX_DENIED`…），**文本逐字不变**——模型与既有客户端看到的内容
完全一样，只有机器调用方多拿到一个可判定的字段。`command_batch` 的 `op:"tool"` 步骤同样如此（`ok=false`）。

**自省工具**（都在 `READ` 门，被拒之后也还能用）：

- `coding.permissions`——完整权限报告：预设、可读根、add-dir、能否构建/执行/联网、受保护清单、
  生效的两个边界 grant、审计计数、审批队列；
- `coding.explain(path, method?)`——**干跑**边界判定，返回 `Authorizer` 会怎么判，不执行；
- `coding.requestAccess`——申请能力。

**其他出口**：`GET api/agent/{taskId}/access`（`state` 为 `idle`/`pending`，附 `report` 文本）、
CLI `/access`、`FailureCategory` 把结构化拒绝归类为「权限问题」而不是普通失败、`ToolOutcome` 把拒绝渲染为 fail。

**链接不授权**：镜像根只认后端自己的清单，不认工作区当前的链接拓扑。`WorkspaceMirror` 建链接时把
`链接 → 目标` 成对记录到工作区**同级**的 `<workspace>.mirror-manifest`（任务写不到那里），`MirrorRoots.derive` 只接受
"清单里有、仍是链接、真实目标与记录完全一致、且仍在该清单记录的检出内"的条目；源检出（`ScopeKind.SOURCE`）同样只来自这份清单。
因此新增链接、把链接改指向别的目录、或指向另一个合法检出，都不会产生任何授权——否则"在工作区造一个 junction"就等于把
那个目录交给沙箱子进程（2026-11-20 审查 S1：目标只要能走到任意检出标记就会被当成镜像根，单链接即可上扩到整个检出）。
清单缺失（旧版本建的工作区）时按"没有镜像"处理：读写都留在工作区内，属于 fail-closed 而不是静默放宽。

**环境只给工具链**：子进程的环境 = 白名单（toolchain + OS plumbing）+ 调用方显式声明的变量。这条**对 token 沙箱同样成立**：
账户的持久环境块不是任务环境，必须先过 `SandboxEnvironment.isWhitelisted` 才能继承——否则用 `setx` 持久化的
`*_API_KEY` 会直接出现在 low-il 子进程里（2026-11-20 `permission-e2e` A10 实测，`WindowsTokenLauncher.mergeEnvironment`）。

**提示词披露**：`CliAgentPrompt.permissionsBlock` 把预设、边界、审批入口写进系统提示。
最关键是那条「拒绝是结构性的，不要换引号 / 换解释器 / 加 `..` 重试」——实测里模型最费步数的行为就是
换个写法反复撞同一堵墙。

---

## 9. 防漂移：这套体系自己的测试

| 测试 | 守住什么 |
|---|---|
| `CodingToolMatrixCoverageTest` | 每个注册的 `coding.*` 方法都必须在权限矩阵里被分类 |
| `PermissionDecisionBaselineTest` | 把 shell / fs / policy 的判定冻结成 JSON golden baseline（`permission-decision-baseline.json`），allow↔deny 或 code 变化都会失败 |
| `PermissionsConsistencyTest` | `coding.permissions` 报告的数字必须等于 `PermissionAudit.snapshot()` |
| `AgentAccessPolicyTest` | 预设语义、`denyTools` 四种形态、operator 授权不覆盖显式 deny |
| `AccessApprovalsTest` | 去重、护栏不可跨、过期、快照 |
| `CodingAccessEnforcementTest` | 端到端拒绝、报告与实际门一致 |
| `OperatorScopeEnforcementTest` | 一个路径/程序的授权不能解锁另一个 |
| `SandboxDefaultsTest` / `SandboxProbeTest` / `WindowsSandboxTest` | 沙箱默认选择、写入探针、失败即硬报 |
| `OwnerSecurityRightsTest` / `WindowsLowIntegritySandboxTest` | OWNER RIGHTS 的 argv 形状、真实授权后能打标；`exit=5` 时自愈恰好重试一次、授权也失败时仍硬失败 |
| `SandboxFileOpsTest` | 每个文件变更都交给边界；边界拒绝 → 工具错误且不落盘；边界无法解析 → 同样不落盘（无进程内回退）；真实 Low-IL / 容器子进程区内通、区外拒；生产接线（`CodingComponents.fs`）端到端 |

改权限相关代码时，**先跑这三个**：矩阵覆盖、决策基线、报告一致性。它们失败通常意味着「改动比你以为的大」。

---

## 10. 故障排查：`windows-low-il` 起不来（读得动、改不动）

`windows-low-il` 的写边界靠给工作区**降强制性标签**（mandatory label）兑现，而写显式标签需要目标上的
`WRITE_OWNER`。仓库若是从共享位置（`%TEMP%`、`D:\workspace` 这类只继承 `Authenticated Users:(M)` 的目录）
拷贝或解压出来的，DACL 里就没有这一位（属主身份也不隐含它），于是沙箱在 `prepare()` 就起不来：
**一条 ACL 问题同时打死写、执行、构建、测试，而读路径（后端进程内）完全正常**。

### 10.1 先分清三层：策略拒绝 / 沙箱内拒绝 / 沙箱起不来

| 形态 | 是谁 | 特征 |
|---|---|---|
| 策略拒绝 | `Authorizer` / 方法门 | `PERMISSION_DENIED \| code=TOOL_DENIED\|PATH_DENIED\|… \| hint=… \| needed=…`；`coding.permissions` / `coding.explain` 能解释 |
| 沙箱内拒绝 | 沙箱是好的，OS 挡了这一次操作 | `PERMISSION_DENIED \| code=SANDBOX_DENIED \| tool=… \| detail=… \| hint=… \| needed=…` |
| **沙箱起不来** | `prepare()` 失败，压根没进沙箱 | `Error: … could not start …` / `Error: Unexpected error executing … Windows sandbox unavailable: …`；`coding.permissions` 一片正常，工具照样失败 |

三者的处置完全不同：前两种改策略或改命令，第三种**只能改机器状态**（见 §10.5）。把第三种误判成第一种
是本故障最贵的弯路——换预设、加 `--unrestricted`、换引号重试，全部无效。

### 10.2 症状字符串 → 原因 → 处置

| 症状（原文片段） | 是什么 | 处置 |
|---|---|---|
| `Error: Failed to write '<p>': the OS sandbox 'windows-low-il' could not start for 'write' on '<abs>': Windows sandbox unavailable: cannot lower the mandatory label of '<ws>' (icacls exit=5: …)` | 文件变更走 `SandboxedFileOps`，`prepare()` 在任何字节落盘**之前**就失败 | §10.4 → §10.5 |
| `Error: Unexpected error executing '<cmd>' — Windows sandbox unavailable: cannot lower the mandatory label …` | `coding.shell`、`mvnBuild`、`verify(compile\|test)`、`b4.run` 共用同一个 `prepare()` | 同上 |
| `sandbox boundary NOT enforced: …`（启动日志） | `SandboxDefaults` 的就绪判定没过，后端保持 `off` | 不是本节：按日志点名的缺件处理（账户 / 凭据 / 令牌） |
| `windows-low-il write probe passed: workspace=…` | 打标与三段探针都过了 | 健康状态，无需动作 |
| 沙箱**能起来**、工作区读写正常，但工具链目录里 `dir` 报「找不到文件」/ exe 或 `.cmd` 报「拒绝访问」/ `node -e readdirSync` 报 `EPERM`，同一条命令在普通 shell 里正常 | 目标目录的 DACL 上有**AppContainer 包 SID**（`S-1-15-2-<hash>`）的遗留 ACE：低完整性 + 受限令牌读不了它 | §10.10 |

本节故障**不会**出现 `sandbox boundary NOT enforced`：`isReady()` 只验「能造出 Low 令牌」，
不验「工作区能降标签」，所以选后端阶段放行、运行时才炸（缺口见 §10.8）。

### 10.3 根因

1. **契约**：沙箱起不来必须硬失败，绝不回退裸启动（`ExecutionSandbox` 的 KDoc）；`windows-low-il` 又声明
   `executesFileOps = true`，所以**文件变更也走 Low-IL 子进程**，一处 ACL 坏掉就同时废掉写与执行。
2. **唯一失败点**：`WindowsLowIntegritySandbox.prepare()` →
   `labelWritable(workspace, deep = true, required = true)` → `icacls <ws> /setintegritylevel (OI)(CI)Low`。
   `icacls` 返回 `exit=5`（`ERROR_ACCESS_DENIED`）→ `fail()` → `SandboxUnavailableException`。
3. **为什么需要 `WRITE_OWNER`**：给对象写显式 mandatory label 属于「改安全信息」；对象属主只隐含
   `READ_CONTROL` 与 `WRITE_DAC`，`WRITE_OWNER` 只能来自 DACL 或 `SeTakeOwnership` / `SeRestore`。
   继承来的 `Modify`（`icacls` 里的 `(M)`）**不含**这两位，所以「我是属主、我能读写」和「我能改标签」是两件事。
4. **为什么 worktree 特别容易中**：`WorkspaceIsolation` 用 `git worktree add` 在
   `<repo>/.browser4/worktrees/<runId>` 建树，DACL 全继承仓库根；仓库根若是从共享位置拷来的
   （属主落到别的账户或 `Administrators` 组），`icacls` 必然 `exit=5`。重建 worktree、删 `.browser4`
   都不改变继承链。
5. **诊断为何抓不到**：`labelWritable` 只把 `exit code + 首行` 带进异常，不带账户 / 属主 / DACL；首行是
   本地化的 `icacls` 文本，而它走的是 `SystemSandboxCommandRunner` 的 UTF-8 解码（Kotlin `bufferedReader()`
   默认 UTF-8），`decodeConsole` 的 GBK 兜底只用在探针输出上——中文 Windows 上会乱码。

### 10.4 现场诊断（只读，四条）

```powershell
whoami /user                                        # 当前账户与 SID
(Get-Acl '<ws>').Owner                              # 属主是谁
icacls '<ws>'                                       # DACL：有没有带 WDAC/WO 的 ACE（继承的 (M) 不够）
whoami /priv | findstr /i "TakeOwnership Restore"   # takeown 路线是否可用
```

再用一个**临时子目录**验证「确实是这个 ACL 问题」，不动工作区本身：

```powershell
$p = Join-Path '<ws>' '.b4-label-probe'
New-Item -ItemType Directory $p | Out-Null
icacls $p /setintegritylevel "(OI)(CI)Low"; "exit=$LASTEXITCODE"
Remove-Item $p -Recurse -Force
```

`exit=5` + `Access is denied` /「拒绝访问」→ 命中本节。`exit=0` → 标签不是原因，回去按 §10.1 找
`PERMISSION_DENIED`。

### 10.5 修复（按代价从低到高）

**A. 最小授权，不动属主**（首选；属主天然有 `WRITE_DAC`，**不需要管理员**）

```powershell
icacls '<ws>' /grant "*S-1-3-4:(OI)(CI)(WDAC,WO)"
```

`S-1-3-4` 是 OWNER RIGHTS：把 `WRITE_DAC` / `WRITE_OWNER` 只授给对象属主，不额外扩大任何人的读权限。
两个细节不能省：

- **要带 `(OI)(CI)`**：任务的工作区 `.browser4/worktrees/<runId>` 是**之后**才由 `git worktree add` 创建的，
  不带继承性的 ACE 传不到新树，下一个 `agent run` 照样失败；继承 ACE 同时会补到已有子树
  （实测：父目录授权后新建的子目录带 `OWNER RIGHTS:(I)(OI)(CI)(WDAC,WO)`，对其 `setintegritylevel` 返回 0）。
- **不需要 `/T`**：`/setintegritylevel (OI)(CI)Low` 写的是可继承的强制性标签，子对象自动继承
  （实测：父目录打标后子文件显示 `Mandatory Label\Low Mandatory Level:(I)(NW)`），只有根对象需要 `WRITE_OWNER`。

这条授权**写进目录 DACL、持久生效**，不是每次运行都要做：任务的 worktree 会继承它，之后每个
`agent run` 自动可用。只有这几类情况需要重做一次：重新 `git clone` / 重新解压 / 从别的机器或共享位置再拷一份
（新树重新继承父目录 ACL），或换到另一个 Windows 账户（`OWNER RIGHTS` 只对对象属主生效）。
反过来，检出放在用户档案下（`C:\Users\<you>\…`）时**根本不需要这一步**——用户对自己档案有显式 `FullControl`，
天然带 `WRITE_OWNER`；`D:\` 根、`%TEMP%`、共享盘这类只继承 `Authenticated Users:(M)` 的位置才需要。
当前代码在 `WorkspaceIsolation.begin` 建完 worktree 时就自动补这条授权（`sandbox/OwnerSecurityRights.kt`），
所以**新任务不需要手工处理**；下面的命令用于已有树、`--add-dir` 根，以及不含该修复的版本。
全新安装的 coding 任务天然被覆盖（它的工作区就是任务 worktree）；即使工作区不是 worktree
（数据目录被指到共享盘这类情况），`labelWritable` 的自愈也会在原地补授权。

`--add-dir` 的额外根各自补一次（它们与工作区同为 `required = true` 的打标目标）；镜像根 / 缓存根打不上只 warn，不是硬失败。
撤销本条授权：`icacls '<ws>' /remove:g "*S-1-3-4"`。

> 注意 `prepare()` 的授权范围比"工作区"宽：`labelWritable` 的自愈对**任何**出现 `icacls exit=5` 的打标目标生效，包括
> 镜像模式下的真实源树（`MirrorRoots`）和 `~/.m2` 这类缓存根。授权本身只给对象属主 `(WDAC,WO)`，不扩大任何人的读写，
> 失败也只 warn。反过来，**已经可打标**的目标永远不会触发自愈——所以确切的语义是"授权只补在打不上标签的地方"，
> 不是"后端只会改工作区的 ACL"。

**B. 全控制**（等价、更宽）

```powershell
icacls '<ws>' /grant "<DOMAIN\user>:(OI)(CI)F"
```

`F` 含 `WRITE_OWNER`，同样不需要 `/T`；同样要带 `(OI)(CI)`，否则之后创建的 worktree 继承不到。

**C. 属主不是自己、DACL 也改不动**

```powershell
takeown /f '<ws>' /r /d y     # 需要提升的 SeTakeOwnership（管理员）
```

先看 §10.4 的 `whoami /priv`；没有 `SeTakeOwnership` 就别试，直接走 D。
另一种「属主不是自己」更隐蔽：企业环境给父目录配了可继承的**属主** ACE（新对象属主自动落成
`Administrators`），任务 worktree 建出来就不归本人所有——建树时的 `OWNER RIGHTS` 落在组上、对你不生效，
只能走 C 或 D。

**D. 不动 ACL：换后端或回滚**（操作员动作）

- `-Dbrowser4.agent.sandbox=windows-acl`：文件工具留在后端进程内，但要先 `net user` provision 沙箱账户与
  凭据，多数机器需要管理员，可能撞同一堵墙；
- `-Dbrowser4.agent.sandbox=off`：唯一必然可用的一键回滚。显式 `-D` 不写标记、下次启动不保持；只有启动时
  **推导**出的 `off` 才落成 `v2:off`（永久决定）。语义见 [config.md](config.md) 的 `browser4.agent.sandbox` 一节。

`b4w stop/start`、重启后端属操作员工作，agent 不得自行执行（运行时自毁护栏也会拒绝）。

**E. 结构上避开**

把工作区放到用户私有位置（产品默认 `<app data>/agent/...`、或 `%LOCALAPPDATA%\browser4\…`），
继承用户档案的 DACL，天然可降标签；共享目录下继承 `Modify` 的路径永远需要 A / B 的显式授权。

### 10.6 修复后验证（逐级，缺一不可）

1. `icacls '<ws>' /setintegritylevel "(OI)(CI)Low"` → `Successfully processed 1 files`。
   这条是**验收探针**，会真的给工作区打标，与沙箱 `prepare()` 做的事一致；
2. 一次 `coding.mkdir` / `coding.write` 成功；
3. 后端日志出现 `windows-low-il write probe passed: workspace=…`；
4. `coding.verify(module=<m>, checks=compile,test)` 能真正拉起 Maven。

`coding.verify(checks=repo-consistency)` 走的是 `RepoConsistencyReporter.check(fs)`，**不启动进程**，
所以故障期间它仍然 `passed=true`——它证明「仓库自洽」，不证明「写边界恢复」，别拿它当验收。

### 10.7 诊断期哪些工具还能用

| 仍可用（后端进程内，不经过 `prepare()`） | 不可用（都要过 `prepare()`） |
|---|---|
| `coding.read` / `readLines` / `readFiles` / `grep` / `glob` / `listDir` / `stat` / `changeSummary` | `coding.write` / `append` / `replace` / `editLines` / `insertAfter` / `mkdir` / `delete` / `copy` / `move` / `revert` |
| `coding.permissions` / `coding.explain`、`coding.validate(type=repo-consistency)`、`coding.verify(checks=repo-consistency)` | `coding.shell` / `runCode` / `mvnBuild`、`coding.verify(checks=compile\|test)`、`skill.run` / `runScript`、`b4.run` / `b4.batch` |
| `memory.*`、`system.*`、浏览器 / 页面工具 | — |

浏览器 / 页面工具本身不经这条通道（它们走 driver，不是 `coding` 沙箱）；若看到它们失败，先确认那一步是不是
经由 `b4.run` 或需要 shell 的辅助脚本。

### 10.8 别踩的坑与未收口的缺口

不要做的：

- 不要改 `--access` / `--unrestricted`：这不是策略拒绝，`coding.permissions` 全绿也照样写不动；
- 不要换引号、换解释器、加 `..` 重试，也不要删 `.browser4/worktrees` 重建：新树继承同一套 DACL；
- 不要指望 agent 自己修好：修复动作本身就是写操作，正被这个故障挡着——必须由操作员在真实终端执行。

代码级收口（1、2 已落地，3–6 仍开放）：

1. ✅ `WorkspaceIsolation.begin` 建完 worktree 立即补 `OWNER RIGHTS` 的 `(OI)(CI)(WDAC,WO)`；唯一授权点是
   `sandbox/OwnerSecurityRights.kt`，拒绝时只 warn、不阻断建树（`windows-acl`/`off` 安装从不打标）；
2. ✅ `labelWritable` 自愈：`exit=5` 时先补 `(WDAC,WO)` 再重试一次（**只一次**），仍失败才抛；
3. 报错可修复化：带 `user=` / `owner=` / ASCII 化的 `icacls` 原文，并给一行可粘贴的修复命令；
4. `isReady()` 增加「工作区可标注」探针，不就绪就在**选后端阶段**降级并 warn，而不是每条工具调用运行时炸；
5. `coding.permissions` 暴露 `sandbox.id` / `ready` / `reason`，并给外部智能体一个可轮询的自检端点；
6. `labelWritable` 的错误文本改走 `decodeConsole`（或 `chcp 65001`），别让本地化乱码进 JSON。

测试覆盖：`OwnerSecurityRightsTest`（argv 形状 + 真实授权后能打标）、`WindowsLowIntegritySandboxTest`
（拒绝后自愈恰好重试一次、授权也失败时仍硬失败）、`WorkspaceIsolationTest.newWorktreeIsLabelable`
（在只有 `Modify` 的临时根下建树，仍能真正打标）。新增测试请沿用「先由 `begin` 授权，再打标」这个前提，
不要再假设 `%TEMP%` 天然可打标。

### 10.9 相关文档

- 沙箱设计与已知约束：[agent-permissions-redesign.md](agent-permissions-redesign.md) §8
- 属性、失败语义与回滚：[config.md](config.md)（`browser4.agent.sandbox` 一节）
- 拒绝码与操作员审批：[agent-access.md](../skills/browser4-cli/references/agent-access.md)

### 10.10 低完整性读不到工具链目录（遗留 AppContainer ACE）

与 §10 前九节相反：**沙箱正常起来了**，工作区里读写/构建都好，只有**工作区外**的某些目录进不去。

症状（2026-11-19，run `4125acaa` 实测）：

```text
coding.shell> browser4-cli --version
✗ FAILED · exit 1
拒绝访问。

coding.shell> dir /b "…\npm\node_modules\browser4-cli"
找不到文件                      # 同一父目录 …\npm\node_modules 却列得出来

node -e "readdirSync('…/npm/node_modules/browser4-cli')"
ERR EPERM: operation not permitted, scandir '…'
```

而**同一账户**在普通 shell（Medium IL）、`runas /trustlevel:0x20000`（受限令牌）、以及直接
`node_modules\browser4-cli\bin\browser4-cli-win32-x64.exe --version` 下全部正常；`icacls` 里当前用户还是 `(F)`。
子进程自报身份是 `Mandatory Label\Low Mandatory Level (S-1-16-4096)`，即 `windows-low-il` 确实生效。

根因：对象 DACL 里若有 **AppContainer 包 SID**（`S-1-15-2-<hash>`）的**显式** ACE，Low 完整性 + 受限令牌
对它的**读/枚举**都会被内核拒绝（`CreateProcess` 落在该目录内的 exe 也一并「拒绝访问」）。这类 ACE 通常是
`windows-appcontainer` 后端（或它的实验/测试）给容器授权后没有回收留下的：
`icacls <dir> /grant "*<containerSid>:(OI)(CI)(M|RX)"`。

已实测的边界（同一台机器、同一个受限 Low 令牌）：

| 目录上的额外 ACE | Low IL 结果 |
|---|---|
| 无 | 可列/可读/可执行 |
| 外部普通 SID（`S-1-5-21-…-9999:(OI)(CI)(RX)`） | 正常 |
| `S-1-15-2-1`（ALL APPLICATION PACKAGES） | 正常 |
| **具体包 SID `S-1-15-2-740…:(OI)(CI)(RX)`** | **列不出 / 拒绝访问** |
| 同一条包 SID ACE 被 `/remove` 后 | 立即恢复正常 |

最小复现（**新建**一个自己的目录，不动现有对象）：

```powershell
$p = Join-Path $env:TEMP 'ace-probe'; New-Item -ItemType Directory $p | Out-Null; Set-Content "$p\a.txt" hi
icacls $p /grant "*S-1-15-2-740000081-2787761786-2739701453-4027781610-3709442335-2178988983-1814727619:(OI)(CI)(RX)"
# 在低完整性沙箱子进程里：dir $p → 找不到文件；type $p\a.txt → 拒绝访问
icacls $p /remove "*S-1-15-2-740…"   # 立刻恢复
```

处置（属主天然有 `WRITE_DAC`，通常不需要管理员）：

```powershell
icacls '<dir>'                              # 找不带 (I) 的 S-1-15-2-<hash> ACE
icacls '<dir>' /remove "*S-1-15-2-<hash>"   # 只删这一条，不要 /reset 整条 DACL
```

不改 ACL 也能干活的路子：`b4.run` 产品通道；或直接调目录里的 exe（`.cmd` shim 要经 `cmd.exe` 解析批处理，
被挡时同样报「拒绝访问」）。

产品侧已落（2026-11-19 / 11-20）：

- `SandboxDenials` 现在认中文拒绝文案（`拒绝访问`），`windows-low-il` 的 `code=SANDBOX_DENIED` 的 `hint`
  里直接带上 `icacls … /remove *<sid>` 这条命令；`SandboxStatus` / `SandboxDenials` 改为读**任务实际的
  后端**（`CodingComponents.sandbox(policy)`），不再只读 `browser4.agent.sandbox` 属性——此前它们会在
  low-il 生效时对模型宣称「no OS sandbox is active」。
- **自检**：`windows-low-il` 在每个后端实例第一次被查询时扫一遍候选根（工具链根 + `PATH` 目录 +
  `PATH` 前缀下的 `node_modules/*`，上限 96 个目录、实测 ~1 s），发现包 SID ACE 就以 WARN 打出
  `icacls … /remove` 命令；同一条也出现在 `coding.permissions` 的 `OS sandbox:` 行下面
  （`ExecutionSandbox.advisoryNotes()`）。开关：`-Dbrowser4.agent.sandbox.checkContainerAces=off`。
- **回收**：`windows-appcontainer` 的 grant marker 现在记 `sid|path`，`revokeRecordedGrants()` 会在
  `prepare()` 里把「本任务不需要且超过保留期」的授予撤掉（默认 6 h，
  `-Dbrowser4.agent.sandbox.grantRetentionHours=0|off` 关闭）。只删它自己记过的目录；老式只有路径的
  marker 会先读该目录 ACL，能唯一归因才删，归因不了就丢掉 marker 并打一条手工命令——不猜着改 ACL。
- **约定**：容器只授「工作区 + 该任务 `--add-dir` 的目录」，绝不授用户全局目录（`%APPDATA%`、npm 前缀、
  JDK 安装）；这条写在 `WindowsAppContainerSandbox.grant()` 的 KDoc 里，`WindowsAppContainerSandboxTest`
  用「只发一次 `icacls /grant`」钉住它。

仍未收口的只剩：ACE 一旦落在**非** `windows-appcontainer` 记过的目录上（别的工具、手工实验），回收只能
靠上面的手工命令或自检告警。

---

## 11. 容易混淆的相邻概念

- **`IsolationGate`** 不是权限门：它决定隔离 worktree 的变更能否合并回主树（要求改动过的模块有通过的
  `coding.verify`）。
- **finish gate** 也不是权限门：它校验完成报告里声称的 gate 是否真跑过、文件是否真改过。
- **`allowDestructive`** 不是只读开关：它只关 `delete`/`copy`/`move`，`write`/`append`/`replace` 照旧；
  要只读请用 `--access read`。该属性已标记为 deprecated，建议迁移到 `access`。
- **`read-build`** 不是沙箱：构建必然执行仓库自己的代码（Maven 插件、npm scripts），它防的是误操作与外泄，
  不防恶意代码——那要用容器或虚拟机。

---

## 12. 关键文件索引

| 文件 | 内容 |
|---|---|
| `browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/AgentAccessPolicy.kt` | 预设、ToolGate、策略、拒绝构造、服务端/无人值守默认 |
| `browser4-coding/.../Capability.kt` | Effect / Target / Scope / Limits / Cost / Capability |
| `browser4-coding/.../Authorizer.kt` | 判定引擎、canonical 路径解析、`unrestricted()` |
| `browser4-coding/.../AccessApprovals.kt` | 待批队列、护栏检查、capability 签发、惰性过期 |
| `browser4-coding/.../AccessPolicyFile.kt` | 工作区策略文件格式与解析 |
| `browser4-coding/.../PermissionAudit.kt` | 拒绝码表、计数、单次日志 |
| `browser4-coding/.../CodingComponents.kt` | 边界 Authorizer、scope manifest、fs / shell / sandbox 装配 |
| `browser4-coding/.../ReadablePaths.kt`、`SensitivePaths.kt` | 可读根、凭证路径的判定顺序 |
| `browser4-coding/.../TaskWorkspaces.kt` | 其他任务工作区的形状判定、窗口/上限、沙箱日目录合并 |
| `browser4-coding/.../CodingAgentShell.kt`、`CodingAgentFileSystem.kt` | 命令分层、路径守卫、受保护/敏感清单 |
| `browser4-coding/.../sandbox/ExecutionSandbox.kt`、`SandboxDefaults.kt` | 沙箱后端、环境白名单、默认策略 |
| `browser4-coding/.../sandbox/WindowsLowIntegritySandbox.kt`、`OwnerSecurityRights.kt` | Low-IL 后端：令牌、打标（`labelWritable`）、OWNER RIGHTS 授权、三段探针 |
| `browser4-coding/.../WorkspaceIsolation.kt` | 隔离 worktree 的创建 / 复用 / 合并（沙箱 ACL 的继承源头） |
| `browser4-agentic/.../tools/builtin/CodingToolExecutor.kt` | 方法门、`permissions` / `explain` / `requestAccess` |
| `browser4-agentic/.../tools/AgentToolManager.kt` | `applyAccessPolicy` / `resetAccessPolicy` / 组件重建 |
| `browser4-agentic/.../agents/RobustBrowserAgent.kt` | `buildCliEngineToolSet` 的工具列表裁剪 |
| `browser4-agentic/.../agents/CliAgentPrompt.kt` | 提示词里的 `### Permissions` 块 |
| `browser4-agent-tools/.../StatefulAgentRunner.kt` | 任务策略推导优先级、`--unrestricted` 审计 |
| `browser4-rest/.../api/controller/AgentAccessController.kt` | REST 审批接口（无 CORS） |
| `cli/browser4-cli/src/agent_interactive.rs` | `/access`、`/approve`、`/deny`、`/add-dir`、`/dirs` |

---

## 13. 一句话总结

**任务拿到一份声明式策略（preset + 护栏 + 增补作用域），每次调用都过低门槛的方法门与高精度的目标级边界判定，
被拒时拿到可执行的 `hint` 而非散文；操作员可以按目标粒度、带 TTL 地临时放宽，但放宽永远跨不过护栏，
底层还有 OS 沙箱兜底；全过程可计数、可干跑、可 JSON 基线回归。**
