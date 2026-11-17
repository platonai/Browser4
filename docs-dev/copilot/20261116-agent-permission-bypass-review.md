# Agent 权限绕过复盘与修复（run 966a5f2e，2026-11-16）

## 1. 背景

Browser4 coding agent 的任务 `966a5f2e`（验证 PDK 能否开发插件并交付 `hello-plugin`）全程
153 次工具调用、11 次被权限系统拒绝。复盘文档
`<agent>/workspace.46759168-…/permissions-report-966a5f2e.md` 把 11 次拒绝归成 4 类；
后续同会话任务 `b422d875` 的 `access-boundaries.md` 又做了边界实测。

两份报告合起来指向同一个事实：

- 11 次拒绝里，**1 次**走的是系统允许的正道（`coding.listDir` 读 `~/.browser4` 这个声明的可读根），
  **1 次**被结构性守住（`b4.run stop`，未重试），**8 次**是「绕过闸门」——都是**读**：
  `%USERPROFILE%` 等变量展开、`python -c os.path.*`、`.b4scratch/*.ps1` 里的路径。
- 真正严重的是边界实测：`sandbox-default = off`，因此 `coding.*` 的 `PATH_DENIED` 只是**工具入口的
  策略闸门**；换成 `coding.shell` 里跑的 python，`C:\Windows`、`C:\Users\pereg\Documents`、`D:\`、
  `%TEMP%`、`~/.browser4` 读写全通，`socket`/`urllib` 也能连 443——而 `curl` 只是按命令名被拦。

**结论：不是突破了沙箱，而是「声明了一套边界、执行层没有落实」。** 沙箱没开，字符串层是唯一边界，
而字符串层按设计只做「快失败」（它自己的注释就这么写）。

## 2. 根因

`SandboxDefaults`（plan P3-4 / D1）为了让老装不因 ACL miss 而构建失败，把边界**只**给新装：
已有数据目录 → `off`，并把这个 `off` 持久化。于是：

1. 老安装永远拿不到强制层；
2. 拒绝文案仍然宣称 *"the OS sandbox enforces the boundary when enabled"* —— 在 `off` 的机器上这是
   假的安全感（实测 run 里 8 次越界读都发生在这种机器上）；
3. `coding.shell` 的 inline script 豁免（`python -c`、`powershell -Command`）是**有意的**，前提正是
   「沙箱兜底」——前提不成立时，豁免就变成洞。

## 3. 本次修改

### 3.1 老装一次性升级到平台后端（`sandbox/SandboxDefaults.kt`）

- marker 增加 schema 版本：内容从裸 id 变成 `v2:<id>`。
- 规则：
  - 显式 `-Dbrowser4.agent.sandbox=<id>` 永远优先，不落盘；
  - `vN:<id>`（`N ≥ 2`）原样重放 —— 所以 `v2:off` 是**操作者的决定**，不再被升级；
  - 老的裸 `windows-acl` 保留并 pin 成 `v2:windows-acl`；
  - 老的裸 `off`、没有 marker 的老数据目录、全新安装 → 取 `defaultForPlatform()`，
    前两种会打 WARN，并点明回滚属性。
- **就绪门控**（`WindowsSandboxBootstrap.isReady()`）：Windows 后端要求低权限账户与其凭据已由管理员
  离线准备好。没准备好就不切（否则**每条** `coding.shell` 都会硬失败，变成自己制造的故障，而不是边界），
  而是保持 `off` + 一条 `sandbox boundary NOT enforced: …` 的 WARN，并且**不 pin 决定** —— 管理员补好
  账户后，下一次启动自动升级。`isReady()` 只做前置检查（`net user` + 读凭据），不动 ACL。
- 失败语义不变：后端可用但启动失败 → `SandboxUnavailableException`，绝不裸启动（fail-closed）。最坏情况是
  一次响亮的失败，而不是静默的洞。

### 3.2 拒绝文案跟随真实执行层（`CodingAgentShell.kt`）

`ADVISORY_NOTE`（常量，恒定宣称「OS 沙箱在强制」）改成 `advisoryNote()`：

- 沙箱开启 → `the path check is advisory — the OS sandbox enforces the boundary`；
- 沙箱 `off` → `the path check is advisory and no OS sandbox is active (browser4.agent.sandbox=off),
  so a command that runs its own code can leave the workspace`。

于是「策略闸门」与「强制层」在模型看到的文本里不再是同一件事。

### 3.3 文档

- `docs/config.md`：`browser4.agent.sandbox` 默认值段与权限开关总表；
- `docs/agent-permissions.md`：§⑤ 执行沙箱、默认值段、开关表。

## 4. 明确没修的部分（留白，别再当成已解决）

| 项 | 现状 | 建议 |
|---|---|---|
| inline script 豁免（`python -c`、`powershell -Command`、`-File x.ps1`） | 仍然不解析路径 | 属于「沙箱开启后的合理设计」；`windows-low-il`（2026-11-17）已把**写**和**凭据读**下沉到内核，脚本同样受限 |
| `b4.run stop` / 生命周期保护 | 命令名白名单 | 沙箱开启后由进程层兜；未开沙箱时 `python ctypes TerminateProcess` 仍未验证覆盖 |
| 凭据目录（`~/.ssh`、`~/.aws`、`.m2/settings.xml`） | 工具层硬拒绝 | 沙箱开启后才真正不可读；`off` 时仅工具层 |
| 网络（`net.connect`） | 按命令名拦 | 沙箱/出口代理才是真边界；`curl` 拦、`urllib` 放行即证据 |
| `coding.permissions` 输出 | 被截断 1363 字符，受保护清单看不全 | 支持分段/`--json`，或缩短输出 |

## 5. 验证

- `browser4-coding` 编译通过（`mvn -o -pl browser4-coding -DskipTests test-compile`）。
- 单元测试：`SandboxDefaultsTest`（新增 6 个迁移用例）、`CodingAgentShellPathGuardTest`、
  `CodingAgentShellUnrestrictedTierTest`、`CodingAgentShellWindowsBuiltinTest`。
- 手工验收：把 `<data>/.browser4/sandbox-default` 写成裸 `off`，重启后端 → 应看到
  `sandbox default upgraded from the legacy 'off' to 'windows-acl'` 的 WARN，marker 变成
  `v2:windows-acl`；再写 `v2:off` 则保持 `off`。
