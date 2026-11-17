# `agent run` 权限体系（as-built 报告）

日期：2026-11-17（含当天落地的 P3-6 文件写下沉与 `b4.run` 产品通道决策）
性质：**现状报告**，不是设计稿。设计正文见 [`docs/agent-permissions-redesign.md`](../../docs/agent-permissions-redesign.md)，
面向使用者的规则见 [`docs/agent-permissions.md`](../../docs/agent-permissions.md)，执行侧流水账见
[`20261117-agent-sandbox-progress.md`](20261117-agent-sandbox-progress.md)。

一句话：**2 个模式 × 4 条轴 × 5 层决策 × 4 个 OS 后端**。字符串层只负责"快失败 + 可读提示"，
内核层是唯一强制点；内核兜不住的地方必须在报告里点名（`coding.permissions`）。

---

## 1. 入口：CLI 开关 → 策略对象

| 开关 | 写入策略 | 生效位置 |
|---|---|---|
| `--coding` | `AgentMode.CODING`（默认 `BROWSE`） | `IsolationProfile` → 后端链；是权限输入，不只是提示词提示 |
| `--access full\|write\|read-build\|read` | `AccessPreset`（`read_build` 等写法也接受） | 方法门 + 组件投影（`readOnly` / `allowExecution`） |
| `--unrestricted` | pass-through 策略（full + 外部访问 + 网络 + 破坏性 + 去掉受保护/敏感 rails + 解除命令白名单） | 仍在的两条护栏：**OS 凭据库**、**会杀掉/重建后端的命令**；逐任务 opt-in、审计、被 `--resume` 继承 |
| `--add-dir <dir>` | `additionalDirs` | 进边界作用域（读+写+shell cwd），**预设仍决定能不能写**——授权是 scope 不是 power |
| `--resume <id>` | 继承 preset / add-dir / unrestricted | 任务级 |
| `-i` 会话 | `/access`、`/add-dir`、`/approve`、`/deny` 在会话内追加 | 会话级 |
| 不给 `--access` | 无人值守 → `unattendedDefault`（`full` + `allowNetwork=true`；`browser4.agent.access.unattended=deny` → `read` + 拒 `coding.*`/`skill.*`）；`-i` 观察会话（TTY）→ 服务端默认 `browser4.agent.access`（默认 `full`） | 任务结束恢复服务端默认 |

代码：`StatefulAgentRunner`（flags → 策略，含审计）、`AgentAccessPolicy`（唯一事实源）、`CodingComponents`（唯一装配点）。

---

## 2. 五层决策链

```
① 工具注册表裁剪   buildCliEngineToolSet：被拒方法从注册表消失，exposeTools 也取不回
② 方法门           CodingToolExecutor.callFunctionOn → policy.denial() → ToolAccessDeniedException
③ 组件投影         CodingComponents.fs / shell：readOnly / allowExecution / commandAllowedProvider
                  / writeAllowedProvider / mutationOpsProvider（文件写交给沙箱子进程）
④ 边界授权         Authorizer：cap-fs-boundary（canonical，跟随 junction）
                              cap-shell-boundary（lexical + 工具链根 + launcher 豁免）
⑤ 内核强制         ExecutionSandbox：令牌 / 容器 / Job Object；审计 + 结构化拒绝
```

每层都能**独立拒绝**：上层被绕过时下层仍挡；③ 让"直接持有 fs/shell 实例"也无法重开 ② 关掉的东西。

---

## 3. 预设矩阵（任务级 `--access`）

| 能力 | `full` | `write` | `read-build` | `read` |
|---|---|---|---|---|
| 读工作区 / 可读根 | ✓ | ✓ | ✓ | ✓ |
| 写 / 改 / 删 / 复制 / 移动 | ✓ | ✓ | ✗ | ✗ |
| 构建（`mvnBuild`/`verify`/`validate`） | ✓ | ✓ | ✓ | ✗ |
| `coding.shell` / `runCode` | ✓ | ✗ | ✗ | ✗ |
| 网络类 shell 命令 | 需 `allowNetwork` 或 `net.connect` 授权（默认拒） | 同 | 同 | 同 |
| 未分类的新方法 | 按 `full`-only **失败关闭**（`ToolGate` 必须恰属一类） | — | — | — |

`read-build` 能构建但模型面无 shell：构建入口走**独立 build-only shell**（`CodingComponents.buildShell`），
所以"只读自检"不需要给模型开执行权。

---

## 4. 模式 × 四条轴（`IsolationProfile`）

| 轴 | `browse`（默认） | `coding`（`--coding`） |
|---|---|---|
| 读 | 仅工作区（+ `--add-dir`、只读可读根） | **继承当前用户**，凭据库除外（决策 D1） |
| 写 | 仅工作区 | 工作区 + 构建缓存白名单（D2：`~/.m2`、`~/.gradle`、`~/.cache`、任务 temp） |
| 网络 | shell/脚本 off，浏览器 on | 默认 off + 逐域申请（D3，**未兑现**，见 §13） |
| 执行 | 允许写脚本，命令按 allowlist | 任意代码（coding 的前提） |
| 生命周期 | 禁杀/重建本 backend | 同左 |

后端链（`IsolationProfile.backendChain`）：
`coding → windows-low-il → windows-acl`；`browse → windows-appcontainer → windows-acl`；
Linux → `landlock`/`bwrap`，macOS → `seatbelt`（**均未实现**，选到即失败而不是降级）。
**操作者显式 `-Dbrowser4.agent.sandbox=<id>` 永远优先**；链上"未就绪/不可自动选择/未实现"的后端被跳过，跳过不致命。

---

## 5. OS 后端能力矩阵

| 后端 | 写边界 | 读边界 | 网络 | 凭据读 | 文件写下沉 | 托管 CLI（`b4.run`） | 需要账户/管理员 | 状态 |
|---|---|---|---|---|---|---|---|---|
| `windows-low-il`（coding 首选） | Low IL：工作区+缓存 | 用户可读（读上允许） | ❌ 不强制 | `NO_READ_UP` 拒 | ✅ | ✅ | 都不需要 | 已验证 |
| `windows-appcontainer`（browse，opt-in） | 容器 SID 只授工作区 | **profile / 非系统卷不可达** | ✅ 无能力=无出网 | profile 不可达 | ✅ | ❌（声明式，见 §6） | 都不需要 | 已验证 |
| `windows-acl`（安装默认，历史行为） | 账户 ACL 矩阵 | 账户可读的树 | ❌ 不强制 | 靠 ACL | ❌（策略层） | ✅ | 需要管理员预建账户 | 就绪门控 |
| `off` | 当前用户 | 当前用户 | 当前用户 | 仅策略层 | ❌ | ✅（无边界） | — | 老装 / 回滚 |

三个**能力声明**（报告由它们生成覆盖范围，不靠人手写）：
`enforcesNetwork` / `executesFileOps` / `executesProductCommands`（后者：能否在边界内托管产品子进程 CLI）。
`WindowsJob`（`KILL_ON_JOB_CLOSE`）覆盖所有沙箱后端的进程树：backend 退出即整树终止。

---

## 6. 通道覆盖矩阵（谁在边界内）

| 通道 | 执行身份 | 在边界内？ |
|---|---|---|
| `coding.shell` / `runCode` / `mvnBuild`·`verify` | 沙箱子进程 | ✅ |
| 技能脚本 `skill.runScript` | 沙箱子进程 | ✅ |
| `b4.run` → `browser4-cli` | 沙箱子进程（CLI 状态目录内迁） | ✅（`low-il`/`acl`）；❌ 容器——保留后端进程启动，报告点名 |
| 文件写：`write`/`append`/`replace`/`editLines`/`delete`/`mkdir`/`copy`/`move`/`revert` | **每次变更一个沙箱子进程**（载荷走工作区暂存文件 + `ReplaceFile`） | ✅（`low-il`/容器）；`acl` 仍策略层 |
| `coding.read/glob/grep` | 后端 JVM | ❌ 仅策略层（**设计**：coding 读=继承用户；browse 读=工作区） |
| git / worktree / merge、LSP、ripgrep | 后端 JVM | ❌ 产品流程本身 |
| 网络（coding） | — | ❌ `net=off` 未兑现 |

> `b4.run` 的依据：`browser4-cli` 装在 profile、通过 `127.0.0.1` 访问后端，与 git/LSP 同属"产品控制面"。
> 实测（2026-11-17）：容器连 loopback 都被拒（curl exit 7/28），DNS 也不通；工作区里暂存的 exe 能跑。
> 所以"复制进工作区"只解决可执行性，不解决连通性；给容器 `privateNetworkClientServer` 会放开主机本地所有服务。
> 结论写进设计 §14.2.1/§14.2.2。

---

## 7. 护栏（批准也跨不过去）

- 工作区边界（`allowExternalAccess=false` 时越界即拒，写与 shell 都判）
- 受保护文件：`VERSION`、`AGENTS.md`、`CLAUDE.md`、根 `pom.xml`、`browser4-dependencies/pom.xml`、`ci.yml`、`b4w.*`、`bin/build*`、`bin/recover*`、`mvnw*`
- 受保护目录：`.browser4`（快照/检查点/worktree）、`.github/workflows`、`.mvn`
- 敏感路径：`~/.ssh`、`~/.aws`、`~/.gnupg`、`~/.config/gcloud`、`~/.browser4`
- 绝对拒绝：`ReadablePaths.ALWAYS_REFUSED`（加可读根、甚至 `--unrestricted` 也不放）
- 命令分级 + `BLOCKED_PATTERNS` + 内联脚本递归校验；运行时自毁（杀后端 / `b4w build` / `mklink`·`ln`·`mount`）
- CLI 逃逸：`b4.run` 的 lifecycle、`skill install`、`tool call`；`preset ≠ full` 时 `b4.run` 的直调 MCP 命令被拒

---

## 8. 审批：非阻塞的能力申请

1. 模型调 `coding.requestAccess(effect, target, reason?, ttlSeconds?)`（归 **READ 门**，任何预设都能调）
2. 立刻返回 pending 的 `requestId`，**不阻塞** agent 循环；同一 `effect + 种类 + target` 去重
3. 操作者经 REST 或 `-i` 决定：
   - `GET api/agent/{taskId}/access`
   - `POST api/agent/{taskId}/access/{requestId}/approve`
   - `POST api/agent/{taskId}/access/{requestId}/deny`
   - CLI `-i`：`/access [task-id]`、`/approve <request-id>`、`/deny`
4. 批准签发**目标级** capability（一个路径的批准不解锁另一个），带 TTL、惰性过期；下一次调用即生效（`operatorGrants` 随每次判定读取）
5. 审批**不能**跨过护栏；申请/批准/拒绝/过期都留审计事件
6. 策略与审批队列**按任务归属**：`applyAccessPolicy(policy, taskId)` 会重建队列，任务结束即失效；
   `-i` 会话里的 `--unrestricted` 这类 flag 作用于会话的每个任务，但每个任务仍拿到自己的策略实例

---

## 9. 任务生命周期与策略边界

| 阶段 | 发生什么 |
|---|---|
| 任务开始 | `--coding` 决定隔离 worktree 或镜像；`StatefulAgentRunner` 推导 `basePolicy`（显式 preset > 无人值守默认 > 服务端默认），叠加 `--add-dir`，最后叠加 `--unrestricted`（都写审计） |
| 绑定工作区 | `AgentToolManager.rebindWorkspace` 重建 shell / fs / coding target，边界 Authorizer 与沙箱**按新工作区**重新解析 |
| 应用策略 | `applyAccessPolicy(taskPolicy)`：策略文件 + 审批队列 + 组件重建；提示词与 `coding.permissions` 从这一刻起读新策略 |
| 运行 | 每次工具调用过 ①–⑤；沙箱实例按 id 复用（JVM 内），工作区级准备（打标/授权/探针）**每工作区一次** |
| 任务结束 | `finally` 恢复服务端默认并重置策略，避免共享 companion agent 继承上一个任务的授权 |
| 恢复 | `--resume` 继承 preset / add-dir / unrestricted；checkpoint 必须同机同一 backend |

---

## 10. 可观测性

- **`coding.permissions`**：预设、工作区、隔离形态、`isolationSummary`（后端 + 四条轴 + 网络是否强制）、
  **按能力生成**的 covered / NOT covered 通道、可读根、`--add-dir`、受保护项、被拒方法清单
- **`coding.explain(path, method?)`**：干跑 `Authorizer` 的判定，不执行
- **系统提示词**：预设能力 + 边界 + "拒绝是结构性的，不要换引号/换解释器/加 `..` 重试" +
  生效 OS 层（**按模式解析后端**，不读裸属性；文件写在边界内时也这么说）
- **拒绝形态**：`PERMISSION_DENIED | code=… | tool=… | detail=… | hint=… | needed=…`；
  内核拒绝翻译成 `code=SANDBOX_DENIED`（原始 OS 证据只进日志）
- **审计码**：`TOOL_DENIED`、`READ_ONLY`、`PATH_OUTSIDE_WORKSPACE`、`PATH_DENIED`、`SENSITIVE_PATH`、
  `PROTECTED_PATH`、`COMMAND_NOT_ALLOWED`、`COMMAND_PATH_DENIED`、`EXEC_DISABLED`、`BUDGET_EXCEEDED`、
  `OUT_OF_SCOPE_WRITE`、`NETWORK_TARGET`、`SCOPE_UNCERTAIN`、`SANDBOX_DENIED`、`ENV_PASSTHROUGH`、
  `ACCESS_REQUESTED`/`ACCESS_APPROVED`/`ACCESS_DENIED`/`ACCESS_EXPIRED`
- **`GET api/agent/{taskId}/access`**：pending 清单 + 报告文本；`FailureCategory` 把结构化拒绝归类为权限问题；
  `ToolOutcome` 把拒绝渲染为 fail（不当作成功）

---

## 11. 失败语义（fail closed，三处）

1. 未知 / 未实现的 backend id → **启动即失败**（不是第一次调用）
2. 后端起不来（缺账户/凭据、ACL 失败、标签失败、探针失败、工作区无 `WRITE_OWNER`）→
   `SandboxUnavailableException`，**绝不裸启动**
3. 文件写桥起不来 → 工具返回 `Error: Failed to write …`，**不落盘、不回退进程内写**

---

## 12. 开关与回滚

```
-Dbrowser4.agent.sandbox=windows-low-il|windows-acl|windows-appcontainer|off   # 每后端
-Dbrowser4.agent.sandbox.browse=on                                             # 让 browse 链选容器（opt-in）
-Dbrowser4.agent.sandbox.account=browser4-agent                                # 账户名（windows-acl）
-Dbrowser4.agent.access=full|write|read-build|read                             # 服务端默认预设
-Dbrowser4.agent.access.unattended=deny|<preset>                               # 无人值守默认
```

决策落盘：`<data>/.browser4/sandbox-default`（`v2:<id>`）。`v2:off` 是操作者决定，不再自动升级；
显式属性永远优先且不落盘；`off` 是一键回滚。

---

## 13. 已知缺口

逐条的根因、危害、披露方式、可复测证据与收口代价见 **缺口登记册**
[`20261117-agent-permission-known-gaps.md`](20261117-agent-permission-known-gaps.md)（ID 稳定：G1–G8）。
速查：

| 缺口 | 现状 | 下一步 |
|---|---|---|
| G1 coding 网络 `net=off` | **未强制**，报告写明 "NOT enforced by this backend" | WFP（需管理员）或代理；本机不可验证 |
| G2 非 Windows 无内核边界 | `landlock`/`bwrap`/`seatbelt` 均未实现 → 实际是 `off` | landlock 后端（无特权，CI 可测） |
| G3 容器内 `b4.run` | 不在边界内（**已拍板**：产品通道，报告点名） | 不开 `privateNetworkClientServer`（会放开主机本地所有服务） |
| G4 `coding.read/glob/grep` | 策略层 | 设计如此；browse+容器下可读根与容器读不到不一致，可在报告里标注 |
| G5 git/worktree、LSP、ripgrep | 策略层；沙箱内 `git add`/`commit` 被拒（`index.lock`），已由 `WorktreeGitBoundaryTest` 钉住 | 产品级 git 通道 + 可读提示，**不要**放宽 ACL（hook 提权） |
| G6 `windows-acl` 的文件写下沉 | 未接 | 需一台已 provisioning 的机器验证 |
| G7 每次文件变更 ~0.4s | 一进程一次操作 | 若主导任务时长，换常驻/批量协议；接口不变 |
| G8 browse 默认启用容器 | 未默认 | 容器内**没有解释器/工具链**（部署层决定） |

---

## 14. 证据（2026-11-17 跑过的）

- `browser4-coding` 全量 **891 例全绿**（`sandbox` 包 103 例），含**真实** Low-IL 子进程与**真实** AppContainer：
  区内写成功、区外写被 OS 拒、凭据目录列不出、容器够不到 profile/loopback/外网、容器内 `powershell` 能起
- `browser4-agentic` 权限/CLI 定向 **469 例全绿**（`coding.permissions` 一致性、提示词文案、`b4.run` 沙箱化）
- `PermissionDecisionBaselineTest`：shell/fs/policy 判定冻结成 JSON golden baseline（allow↔deny 或 code 变化即失败）
- `CodingToolMatrixCoverageTest`：每个注册的 `coding.*` 方法都必须在权限矩阵里分类
- 全 reactor 29 模块 `compile` 通过

---

## 15. 代码索引

见 [`docs/agent-permissions.md`](../../docs/agent-permissions.md) §11「关键文件索引」。本报告新增的两条通道实现在
`browser4-coding/.../sandbox/SandboxFileOps.kt`（文件写下沉）与
`browser4-coding/.../CodingComponents.kt#b4RunSandbox`（`b4.run` 产品通道决策）。
