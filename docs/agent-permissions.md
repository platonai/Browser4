# Agent 权限体系（实现视角）

> 范围：内置智能体（`agent run` / MCP 直调）的 `coding.*`、`skill.*` 工具，以及它们背后的文件系统、Shell、进程执行边界。
> 视角：**内部实现**——对象模型、强制链、护栏、审批机制、审计与防漂移。
> 面向操作的用法（预设怎么选、拒绝码怎么读、`/approve` 怎么用）见
> [skills/browser4-cli/references/agent-access.md](../skills/browser4-cli/references/agent-access.md)；
> 历史设计与 M0–M7 的实现记录见
> [docs-dev/copilot/agent-run-permission-hardening-plan.md](../docs-dev/copilot/agent-run-permission-hardening-plan.md)。
> 最后核对：2026-11-15（4.16-feat 分支）。符号名稳定，行号会漂移，定位请以类/方法名为准。

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
`--add-dir` 目录，以及**只读**的可读根（`ReadablePaths`）；工具链根（JDK、`.m2`、`.gradle`、`.cache`、
`tmp`、OS 目录）只给 shell。作用域由 `scopeManifest` 单一派生，manifest 与 grant 不会漂移。
Authorizer 按 `(root, 可读根, add-dir)` 缓存，LRU 上限 64（每个任务一个新工作区，不设上限会随进程增长）。

### ⑤ 执行沙箱与进程层

`ExecutionSandboxes`（`-Dbrowser4.agent.sandbox`）是**强制层**，字符串层只是「快失败」。
沙箱无法启动就抛 `SandboxUnavailableException`，绝不静默降级为裸启动；沙箱内的 OS 拒绝会被翻译成
`code=SANDBOX_DENIED`（原始 OS 证据只进日志）。进程环境由 `SandboxEnvironment` 重建：只继承白名单变量
（PATH / JAVA_HOME / maven / gradle / 临时目录等），长生命周期密钥不会被继承；显式声明的密钥仍然放行，
但记一条 `ENV_PASSTHROUGH` 事件。

后端**按模式**选择（P1.5）：`--coding` 走 coding 链（`windows-low-il` → `windows-acl`），即使安装默认是
`off` 也会拿到真实的写强制；browse 不会落到 coding 后端（完整性级别不管读），在 AppContainer 落地前保持
安装默认。`coding.permissions` 会打印后端 id、模式的四条轴，以及"网络是否被强制"。

**文件写操作也在强制层内**（P3-6）：`coding.write`/`append`/`replace`/`editLines`/`delete`/`mkdir`/
`copy`/`move`/`revert` 的实际改动由**沙箱内的子进程**执行（`SandboxedFileOps`，后端声明
`executesFileOps` 即可；`windows-low-il` 与 `windows-appcontainer` 都是如此）。后端过去是 Medium 进程，
所以这条通道曾是最宽的一条——字符串层一旦有洞，写就出去了；现在补上的是内核判定：路径先打完包，
再由 Low/容器子进程搬到目标位置，跨出工作区的写入被 OS 拒绝，工具返回结构化错误。命令行的引号规则不能
叠加，因此载荷走**工作区内的暂存文件**（`.b4scratch/fs-bridge/payload-*.bin`），只把路径作为普通 argv
传给 `-File` 脚本；`replace`/`move` 的语义保持原子（`ReplaceFile`/同卷 rename，跨卷退化为复制+删除）。

读仍然在后端进程里，这是设计而非缺口：coding 的读目标就是"继承当前用户"，而 browse 的读范围就是工作区
（容器读得到的正是后端读得到的）。`coding.permissions` 按后端把这些"在/不在边界内"的通道写清楚。

沙箱为 `off` 时，字符串层的拒绝文案会明确写出「没有 OS 沙箱在运行」，不再暗示身后存在强制层
（2026-11-16：`ADVISORY_NOTE` 从常量改成按 `ExecutionSandboxes.configuredId()` 生成的动态文案）。

---

## 5. 护栏（rails）：批准也跨不过去

| 护栏 | 内容 | 实现 |
|---|---|---|
| 工作区边界 | `allowExternalAccess=false`（默认）时，写与 shell 越界即拒 | `Authorizer` + fs/shell |
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

- **护栏在「批准时」检查**（`AccessApprovals.guardrailViolation`）：模型可以随便申请，操作员的「同意」才必须守规矩。
  跨 `denyTools`、受保护路径、凭证路径、工作区边界、命令白名单的申请直接转成 `denied`，REST 回 **409**
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
日志按 `tool:code` 每种形态只记一次（拒绝与事件分开计数），重试循环不会变成日志风暴。

**自省工具**（都在 `READ` 门，被拒之后也还能用）：

- `coding.permissions`——完整权限报告：预设、可读根、add-dir、能否构建/执行/联网、受保护清单、
  生效的两个边界 grant、审计计数、审批队列；
- `coding.explain(path, method?)`——**干跑**边界判定，返回 `Authorizer` 会怎么判，不执行；
- `coding.requestAccess`——申请能力。

**其他出口**：`GET api/agent/{taskId}/access`（`state` 为 `idle`/`pending`，附 `report` 文本）、
CLI `/access`、`FailureCategory` 把结构化拒绝归类为「权限问题」而不是普通失败、`ToolOutcome` 把拒绝渲染为 fail。

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
| `SandboxFileOpsTest` | 每个文件变更都交给边界；边界拒绝 → 工具错误且不落盘；边界无法解析 → 同样不落盘（无进程内回退）；真实 Low-IL / 容器子进程区内通、区外拒；生产接线（`CodingComponents.fs`）端到端 |

改权限相关代码时，**先跑这三个**：矩阵覆盖、决策基线、报告一致性。它们失败通常意味着「改动比你以为的大」。

---

## 10. 容易混淆的相邻概念

- **`IsolationGate`** 不是权限门：它决定隔离 worktree 的变更能否合并回主树（要求改动过的模块有通过的
  `coding.verify`）。
- **finish gate** 也不是权限门：它校验完成报告里声称的 gate 是否真跑过、文件是否真改过。
- **`allowDestructive`** 不是只读开关：它只关 `delete`/`copy`/`move`，`write`/`append`/`replace` 照旧；
  要只读请用 `--access read`。该属性已标记为 deprecated，建议迁移到 `access`。
- **`read-build`** 不是沙箱：构建必然执行仓库自己的代码（Maven 插件、npm scripts），它防的是误操作与外泄，
  不防恶意代码——那要用容器或虚拟机。

---

## 11. 关键文件索引

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
| `browser4-coding/.../CodingAgentShell.kt`、`CodingAgentFileSystem.kt` | 命令分层、路径守卫、受保护/敏感清单 |
| `browser4-coding/.../sandbox/ExecutionSandbox.kt`、`SandboxDefaults.kt` | 沙箱后端、环境白名单、默认策略 |
| `browser4-agentic/.../tools/builtin/CodingToolExecutor.kt` | 方法门、`permissions` / `explain` / `requestAccess` |
| `browser4-agentic/.../tools/AgentToolManager.kt` | `applyAccessPolicy` / `resetAccessPolicy` / 组件重建 |
| `browser4-agentic/.../agents/RobustBrowserAgent.kt` | `buildCliEngineToolSet` 的工具列表裁剪 |
| `browser4-agentic/.../agents/CliAgentPrompt.kt` | 提示词里的 `### Permissions` 块 |
| `browser4-agent-tools/.../StatefulAgentRunner.kt` | 任务策略推导优先级、`--unrestricted` 审计 |
| `browser4-rest/.../api/controller/AgentAccessController.kt` | REST 审批接口（无 CORS） |
| `cli/browser4-cli/src/agent_interactive.rs` | `/access`、`/approve`、`/deny`、`/add-dir`、`/dirs` |

---

## 12. 一句话总结

**任务拿到一份声明式策略（preset + 护栏 + 增补作用域），每次调用都过低门槛的方法门与高精度的目标级边界判定，
被拒时拿到可执行的 `hint` 而非散文；操作员可以按目标粒度、带 TTL 地临时放宽，但放宽永远跨不过护栏，
底层还有 OS 沙箱兜底；全过程可计数、可干跑、可 JSON 基线回归。**
