# Agent 权限模型重设计：从命令白名单到能力（capability）+ OS 级强制

> 状态：设计稿 v0.1（未实现）· 日期：2026-10-31
> 代码基线：本仓库当前检出 `4.15.x @ 3283822f03`
> 范围：内置智能体 `agent run` 的 `coding.*` 工具（文件、shell、runCode）、工具暴露与审批语义
> 不在本设计内：CLI 帮助文案与输出渲染、LLM 配置加载、`DevTaskPlanner` 的计划逻辑
> 前置文档：[agent-run-permission-hardening-plan.md](agent-run-permission-hardening-plan.md)（M0–M7 已实现的
> preset / 矩阵 / 结构化拒绝）、[coding-run-isolation-design.md](coding-run-isolation-design.md)（工作区与镜像生命周期）

## 1. 结论

现有权限体系的核心假设是「命令字符串可以被安全地翻译成能力」——这个假设不成立。字符串到实际效果的映射
在一般情况下不可判定（`python -c`、脚本文件、任意子进程），因此**字符串守卫只能作为 UX 层**，不能作为
安全边界。

重设计方案一句话：**保留 capability 矩阵、结构化拒绝码与审计计数，把判定收敛成一处，把强制下沉到操作
系统，把提示降级为 advisory。**

## 2. 现状：五处判定、四个已知绕过

### 2.1 判定点分布

| 判定 | 位置 | 覆盖 | 结构性缺口 |
|---|---|---|---|
| 文件工具路径 | `CodingAgentFileSystem.resolvePath`（L1378-1392） | `coding.*` 全部文件方法 | 解析 → 校验 → 打开，中间可被换 symlink（TOCTOU） |
| 参数凭据 | `CodingAgentShell.argumentPathViolation`（L885-916） | shell 参数里的 `~/.ssh` 等 | 只查凭据；`pathGuard=off` 可整体关闭 |
| 工作区边界 | `CodingAgentShell.workspaceBoundaryViolation`（L936-959） | shell 中字面绝对路径 | 启动器扩展名、toolchain 根目录两类例外 |
| 受保护目录 | `CodingAgentShell.protectedPathViolation`（L997-1007） | `.browser4`、`.github/workflows` | 仅字面量，脚本内构造的路径不可见 |
| 方法级矩阵 | `AgentAccessPolicy.gateOf`（L133-139） | `coding.*` 方法 | 只能表达 preset，不能表达 scope |

五个判定各自独立维护，每个"例外"都是对模型的一条新提示。这是守卫漂移的根因（`agent-run-permission-hardening-plan.md`
§P2-1 已经记录过同类问题）。

### 2.2 四个已被代码自证的绕过

| # | 绕过 | 证据 |
|---|---|---|
| B1 | 代码内容不解析：`python -c`、`node -e`、先写脚本再执行 | `CodingAgentShell.kt` L933-934 的 KDoc 原话："Inline scripts (`python -c "…"`) are not parsed … a script is free to compute whatever it likes"；`inlineScript` 只识别 `sh/bash/zsh/dash/pwsh/powershell/cmd`（L1461-1466），`python` 不在表内 |
| B2 | 带代码外壳的 token 静默跳过：`open(r'C:\x','w')` 含 `\` 被判疑似路径，`Path.of` 抛异常后 `getOrNull() ?: continue` 直接放过 | L942-943 |
| B3 | 启动器例外：workspace 外、扩展名为 `exe/ps1/py/sh/cmd/bat` 的 token 一律放行 | `isLauncherReference` L966-971，调用点 L953 |
| B4 | toolchain 根目录整片豁免：JDK、`~/.m2`、`~/.gradle`、`~/.cache`、`java.io.tmpdir`、`SystemRoot` | L947、L974-984 |

配套的两个结构性问题：

- **环境全量继承**：`processBuilder.environment()` 直接拿父进程环境（L1550-1555），凭据类变量对子进程完全可见。
- **无事后证据**：完成门禁比对的是 git 变更，workspace 之外的写入不出现在 `filesChanged`，等于不可观测。

### 2.3 一个不是权限但会污染权限的事实

`WorkspaceMirror` 把模块目录做成 junction / symlink，模型对模块的写入**实际落到用户真实检出**
（见 `coding-run-isolation-design.md` §1）。任何按"字符串前缀"判定的边界在这里都是错的——必须以
**realpath** 判定，否则 ACL、绑定挂载与判定结果三者不一致。

## 3. 威胁模型分叉（决定 80% 的设计）

| 模型 | 假设 | 需要的强制程度 |
|---|---|---|
| 可信模型（单机开发者） | 模型不会主动作恶，只会犯错或被页面内容带偏 | 现有字符串守卫 + 审批提示即可，加一条"越界写入审计"兜底 |
| 不可信模型（多租户 / 第三方任务 / 会执行抓取内容中的指令） | 模型可能被提示注入驱动 | **必须** OS 级隔离；字符串守卫无论怎么加固都不成立 |

本设计按**不可信模型**写。若产品只承诺可信模型，§8 可整体跳过，其余章节仍然成立。

## 4. 设计原则

1. **判定对象是效果（effect），不是命令。**
   `fs.read / fs.write / fs.exec / net.connect / proc.spawn / secret.read`，配 `scope`（路径、域名）与
   `limits`（时长、字节、调用次数）。工具静态声明所需 effects；未声明的工具 fail closed。
   这条 `AgentAccessPolicy.requiredPresetOf`（L236-241）已经做对，保留。
2. **守卫只允许存在一份。** 五个判定收敛到一个 `Authorizer`；工具实现里不再出现任何路径/命令判断。
3. **强制在唯一一层：进程边界。** 判定可以撒谎，ACL / 命名空间不会。
4. **字符串层降级为 advisory。** 保留它的价值（快失败、给模型可执行提示、省一次沙箱启动），但文档与代码
   注释中明确它不是安全声明。
5. **默认拒绝、显式授权、可撤销、留证据。**

## 5. 目标架构

```
任务声明 capability ──▶ Authorizer（唯一判定点） ──▶ 执行沙箱（OS 强制） ──▶ 审计/收敛
        ▲                                              │
        └────────────── 实写记录回灌 ────────────────────┘
```

- **L0 声明**：任务启动时给出一组 capability（默认由 preset 派生，可被任务文件或 CLI 收窄，不能自行放宽）。
- **L1 判定**：`Authorizer.decide(request) -> Decision`，纯函数，可单测、可 dry-run（`coding.explain`）。
- **L2 强制**：所有 `exec` 类调用进入沙箱；文件工具在同进程内按 realpath 判定，但以 L2 的 ACL 为最终一致口径。
- **L3 审计**：记录越界写入、网络目标、进程树、预算消耗；超预算即中止；记录回灌用于收敛 scope。

## 6. 能力模型

### 6.1 数据结构

```json
{
  "id": "cap-ws-write",
  "effects": ["fs.read", "fs.write", "fs.exec"],
  "scope": {
    "paths": { "read": ["${workspace}", "~/.m2", "${java_home}"],
               "write": ["${workspace}", "${tmp}"] },
    "hosts": []
  },
  "limits": { "durationSec": 1800, "writeBytes": 536870912, "execCalls": 200 },
  "source": "preset:full | task | operator",
  "expiresAt": "2026-10-31T23:59:59Z"
}
```

规则：

- `scope.paths` 一律在**规范化 realpath** 上匹配，支持 `**` 通配，但只用于收敛，不用于扩张。
- 任务只能**收窄**已有 capability（求交集），不能扩；扩展必须走 §11 审批。
- `source` 与 `expiresAt` 是审计字段，不参与匹配，但审批记录必须可追溯到它。

### 6.2 效果枚举是封闭的

不引入策略 DSL。新增效果必须改枚举并补测试，避免"配置即代码"式的绕过面。理由：DSL 最终会变成第二个
Tampermonkey——永远有没覆盖到的组合。

### 6.3 与现有 preset 的映射

| preset | 派生的 capability |
|---|---|
| `read` | `fs.read` @ workspace |
| `read-build` | 上者 + `fs.write` @ build outputs + `fs.exec` @ BUILD_COMMANDS（受限） |
| `write` | `fs.read`/`fs.write` @ workspace，无 `fs.exec` |
| `full` | 全部上游 + `fs.exec` 全量命令 + `net.connect`（按 `allowNetwork`） |

`autoApproveUpTo` 的语义改为"自动批准不超过该 preset 所需的 capability"，判定仍在 `Authorizer`，不再由
工具自己调 `presetAllows`。

## 7. 判定点（Authorizer）

```kotlin
data class Request(val tool: String, val effects: Set<Effect>, val targets: List<Target>)

sealed interface Decision {
    data object Allow : Decision
    data class AllowWithBudget(val cap: CapabilityId, val cost: Cost) : Decision
    data class Deny(val code: AuditCode, val effect: Effect, val target: String,
                    val needed: CapabilityRequest) : Decision
}
```

约束：

- `Deny` 必须携带 `needed`——让模型知道"要什么"而不是"被拒了"。当前拒绝信封已有 `hint`，把它升级成结构化
  的 `needed`，CLI 可以直接渲染成"需要批准 X"。
- `Authorizer` 不读 JVM 属性、不读环境变量（除配置注入点），输入即全部。这样才可 dry-run、可重放。
- 每个 `coding.*` 方法在注册表里带一行 `effects` 声明；注册时缺声明 → 启动期 fail（不是运行期）。

## 8. 强制层

| 平台 | 方案 | 覆盖任意子进程 | 成本 | 说明 |
|---|---|---|---|---|
| Linux | bwrap / nsjail / 容器（runc） | 是 | 低（启动 ~50–200ms） | workspace `rw` bind，其余 `ro`，`/tmp` tmpfs，未授权则断开 net namespace |
| Linux（轻量） | Landlock + seccomp（内核 ≥5.13） | 是 | 很低 | 无 root，按路径授予读写；对老内核不可用 |
| macOS | 容器（Docker/Colima）/ `sandbox-exec` 策略 | 是 | 中 | `sandbox-exec` 已 deprecated 但可用；生产建议容器 |
| Windows | AppContainer + capability SID | 是 | 中高 | 需为解释器准备包身份；ACL 是覆盖面关键 |
| Windows（务实） | 独立低权本地用户 + 文件系统 ACL + `CreateProcessWithToken` | 是 | 中 | 只授予 workspace 写、其余只读；不需要包身份，最容易落地 |
| Windows（兜底） | WSL2 容器里跑 exec | 是 | 中 | exec 可用，但与宿主机 Chrome/CDP 的交互需要额外通道 |

Windows 是这套设计的主要难点：没有 bwrap 等价物。"低权用户 + ACL"是唯一能在不引入包身份的前提下覆盖
**任意子进程**的手段，建议作为 v1 的默认落地方式，AppContainer 作为后续加固。

共性要求：

- 沙箱内**不继承**后端进程环境（见 §10）。
- 沙箱内 `cwd` 与 capability 的 workspace 根一致；所有 mount 按 realpath，junction/symlink 必须先展开。
- 沙箱启动失败必须是**硬失败**，不得回退到"无沙箱直跑"（否则一次配置错误就把边界静默关掉）。

## 9. 文件写与 TOCTOU

现在的 `resolvePath → 校验 → open` 三步之间存在竞态：模型可以先在 workspace 内放一个指向外部的链接，
校验通过后再换掉目标。修正方向：

1. 判定用 `toRealPath()`（跟随链接后的真实路径）而不是 `normalize()`；
2. 打开时用 `openat` + `O_NOFOLLOW`（Java 侧对应 `LinkOption.NOFOLLOW_LINKS` + 单次 `Files.newByteChannel`
   不拆分 stat/open）；
3. 最终以 L2 的 ACL 为准——即使 1、2 被绕过，OS 仍然拒绝。

## 10. 凭据与环境

- `processBuilder.environment()` 改为**白名单构造**：只透传 `PATH`、`HOME`、`JAVA_HOME`、语言运行时必需项，
  外加任务显式声明的变量。
- 长期密钥（`DEEPSEEK_API_KEY`、`OPENROUTER_API_KEY` 等）不进 exec 进程；需要时由 broker 签发短期 token，
  并在审计中记录用途与有效期。
- `coding.runCode` 与 `coding.shell` 共用同一套环境构造与同一套沙箱，不再各写一份（当前 `CodeRunner` 只做
  私有 cwd + 超时，见 `CodeRunner.kt` L14-24 的自述）。

## 11. 审批与升级

现在的拒绝信息里直接写"设 `-Dbrowser4.agent.shell.pathGuard=off`"——这是反模式：把闸门开关写在错误里，
等于训练模型去劝操作员关防护，而且 JVM flag 是进程级、全局生效、无法按任务撤销。

替换为 capability 申请：

```
模型: coding.requestAccess({ effect: "fs.write", target: "C:\\temp", reason: "...", ttl: 600 })
系统: 生成待批准项（不进模型上下文，进操作员面）
操作员: 批准 → 签发窄 scope capability → 审计记录 → 到期自动失效
```

要求：

- 申请、批准、失效三类事件都进审计流，可被 `coding.permissions` 与任务报告读取；
- 批准只能**收窄**不能放宽 preset 已拒绝的效果（与现有 `autoApproveUpTo` 的"护栏不可越"语义一致）；
- 无审批通道（无人值守）时，申请直接拒绝并给出"改用 workspace 内路径"的替代方案。

## 12. 审计与收敛

记录三类事实：

| 类别 | 内容 | 用途 |
|---|---|---|
| 越界写入 | 每次 realpath 落在 workspace 之外的写入（文件、大小、时间、发起进程） | 完成门禁的 `filesChanged` 之外的第二份证据 |
| 网络目标 | 域名/IP、发起命令、字节数 | 发现"抓取内容驱动外联" |
| 预算 | execCalls、writeBytes、duration | 超限即中止，而不是只靠 timeout |

收敛机制：连续 N 个任务中某 capability 从未使用 → 提示操作员收窄；频繁触发 `Deny` 的 target → 提示把该
路径写进默认 scope（或提示模型改用 workspace 内路径）。这是把"配错 ACL"的成本前移的手段。

## 13. 迁移路径

按"先不改变行为、再改变强制"的顺序，每步独立可回滚。

| 阶段 | 内容 | 验收 |
|---|---|---|
| M0 | 五个判定收敛进 `Authorizer`，行为等价；`coding.explain` 输出 dry-run 判定 | 现有 FS/Shell 单测全绿，同一批命令判定结果逐条一致 |
| M1 | 加 effect / 越界写入审计，只记录不拦截 | 跑一周真实任务，产出"实际需要的 scope 清单" |
| M2 | 容器（Linux/macOS）与低权用户 + ACL（Windows）落地，opt-in 开关 | 在沙箱内跑通构建 + 单测 + 代码工具全链路 |
| M3 | 默认开启沙箱；`pathGuard` / `allowExternalAccess` 等全局 JVM 开关降级为 advisory 并标记 deprecated | 沙箱启动失败 = 任务失败（硬失败） |
| M4 | 审批 API 取代 hint 式开闸；删除 B3/B4 两类例外 | 无 JVM flag 的分支上跑完整回归 |

M3 之前不得删除任何现有守卫——过渡期需要两者同时存在，以便对比判定差异。

## 14. 风险与代价

| 风险 | 说明 | 缓解 |
|---|---|---|
| Windows 复杂度 | ACL 配错会让 `~/.m2` 写失败，表现为构建莫名失败 | M1 的 scope 清单先行；沙箱启动自检（写探针） |
| 性能 | 容器启动 50–200ms/命令；FS 虚拟化影响大批量读写 | 每次任务复用一个沙箱实例，而不是每条命令一个 |
| 与 junction 冲突 | workspace 内 junction 指向真实检出，mount 与判定口径可能不一致 | 一律 realpath；镜像在沙箱外构建 |
| 调试成本 | 失败从"command not allowed"变成 OS 级 permission denied | 复用 `PermissionAudit` 码表，沙箱拒绝也要被翻译成结构化拒绝 |
| 开发者体验 | 工具链需要写 `~/.m2`、`~/.gradle` | scope 显式列只读/可写根，而不是整片豁免 |

## 15. 开放问题

1. Windows 默认方案选"低权用户 + ACL"还是"WSL2 容器"？前者与 Chrome/CDP 交互自然，后者隔离更强。
2. 沙箱内的 Chrome 由谁启动？后端驱动与沙箱内浏览器之间的 CDP 通道需要单独设计（否则 `b4.run` 直接失效）。
3. `autoApproveUpTo` 在无人值守场景是否保留？保留意味着"自动放宽"，与"默认拒绝"存在张力，需要明确适用边界。
4. `coding.requestAccess` 在 CLI 单机场景的落地形态（交互式确认 vs 预置策略文件）。

## 16. 与现有实现的取舍清单

保留：

- `AgentAccessPolicy` 的 preset 与 gate 矩阵（前提：改为派生 capability）；
- `PermissionAudit` 的结构化拒绝信封与码表；
- `protectedFiles` / `protectedDirs` / `sensitivePaths` 三份默认清单（它们表达的是"护栏"，与 capability 正交）；
- `autoApproveUpTo` 的"护栏不可越"语义。

删除或降级：

- `isLauncherReference` 的外部路径放行（B3）→ 改为 scope 显式列可执行的绝对路径；
- `toolchainRoots` 整片豁免（B4）→ 改为 scope 里的只读根；
- 拒绝文案中的 JVM 开关提示 → 改为结构化 `needed` + 审批入口；
- 各工具内部的路径判断 → 全部迁入 `Authorizer`。

新增：

- `effects` 声明与注册期校验（缺声明 fail closed）；
- `coding.explain`（dry-run 判定）与 `coding.requestAccess`；
- 越界写入 / 网络 / 预算三类审计记录。
