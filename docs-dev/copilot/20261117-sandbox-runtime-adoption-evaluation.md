# 引入 sandbox-runtime（ASRT）的可行性评估

日期：2026-11-17
范围：`D:\codebase\sandbox-runtime`（`@anthropic-ai/sandbox-runtime` v0.0.83，commit `0bbebed`，2026-11-12）
对照方：`Browser4` 分支 `4.16.x`，`browser4-coding/.../coding/sandbox/` 与 `docs/agent-permissions-redesign.md`
性质：**评估（不做实现）**。第 10 节列出本轮实际跑过的命令；没有做的事也在那里写明。

---

## 0. 结论先行

1. **值得引入，但它是"第四个后端"，不是现有 Windows 后端的替代品。** ASRT 补上的正好是本项目自己的 P3 欠账：
   Linux/macOS 内核边界（今天为 **0**）、`net=off` + 逐域批准（今天四条轴里唯一没兑现的）、
   凭据遮蔽、以及"代理即边界"的成套实现。它**不**补的：exec 白名单、AppContainer 式读限、
   每任务工作区语义、Windows 的 env 透传 —— 这些仍归本项目。
2. **形态上选"常驻 sidecar（broker）+ `ExecutionSandbox` 新后端"，不要"每命令起一个 `srt`"。**
   ASRT 的生命周期是 `initialize()` 一次 → 多次 `wrapWithSandboxArgv()` → `reset()`，
   而本项目的接缝是 `prepare()` 每次进程启动调一次。两个粒度必须显式对齐（第 6.2 节），
   否则每条命令都要付一次 ACL 打标 + WFP 自检 + 代理握手，且逐域批准无法累积。
3. **三件事必须先拍板，否则不要动**：(a) 产品是否接受 Node ≥22.12（或自建 bun 单文件）这一运行时依赖；
   (b) Windows 是否接受"机器级一次性管理员安装 + `srt-sandbox` 账户 + WFP 过滤器"，
   它的 loopback 模式是**全机器唯一**，与同机 Claude Code 等 ASRT 用户互斥；
   (c) `b4.run` 的回环需求用"`localhost:<port>` 走代理"还是"安装期 `--allow-loopback`"（后者会削弱围栏）。
4. 建议路径：**P0 探针（Linux/macOS 先通）→ P1 新增 `asrt` 后端接 Linux/macOS → P2 Windows 的 coding 走 `asrt`
   （`low-il` 的读写语义 + 网络轴）→ P3 逐域批准/违规审计收口**。`windows-low-il` 保留为"免安装默认"，
   `windows-appcontainer` 继续管 browse，默认值不自动迁移。

---

## 1. 评估方法

- 读代码：ASRT 的 `src/sandbox/*`（约 31k 行 / 50 文件）与 `vendor/srt-win-src`（Rust）、README（1342 行）、
  `docs/srt-proxy.md`；本项目 `ExecutionSandbox` / `IsolationProfile` / `CodingComponents` / 各 spawn 点、
  `docs/agent-permissions-redesign.md`、`docs-dev/copilot/20261117-agent-sandbox-progress.md`、`docs/config.md`。
- 本机实测：见第 10 节（构建、`srt --version`、`checkWindowsSandboxStatus`、`cargo build srt-win`）。
- **未做**：`srt windows-install`（管理员/UAC + 机器级账户与 WFP 过滤器，评估阶段不应执行）、
  真实沙箱内跑命令、Linux/macOS 实机（本机为 Windows）。

---

## 2. sandbox-runtime 是什么（事实）

### 2.1 定位与生命周期

- 一个把**任意进程**包进内核边界的外壳：CLI `srt`、库 `SandboxManager`、以及可单独跑的网络代理 `srt-proxy`。
- 生命周期（`src/sandbox/sandbox-manager.ts`）：
  `initialize(config, askCallback)` → 打 ACL / 起 HTTP+SOCKS5 代理 / 校验围栏 →
  多次 `wrapWithSandboxArgv(cmd, binShell, customConfig, …, {commandId})` →
  `updateConfig()`（**只有网络列表与 sigv4 能热更新，文件规则不能**）→ `reset()`。
  多进程并发时通过 per-user 会话库做引用计数与崩溃恢复。
- 官方定位是 **Beta research preview**（README 顶部），"API 与配置格式可能变化" → 必须 pin 版本。

### 2.2 四平台的强制机制

| 平台 | 文件 | 网络 | 需要的机器条件 |
|---|---|---|---|
| macOS | `sandbox-exec` + 动态 SBPL profile（`file-read*`/`file-write*`） | 只允许连 localhost 代理端口，其余 `network-outbound` 拒绝 | 无（`sandbox-exec` 已弃用但仍在） |
| Linux | bubblewrap：`--ro-bind / /`、`--bind`、`--tmpfs`、`--die-with-parent` + `apply-seccomp` 辅助（拦 unix socket / 挂载 / 嵌套 userns） | `--unshare-net` + unix socket 上桥接的 HTTP/SOCKS5 代理 | `bwrap`≥0.4（0.5 用于非普通文件 bind）、`socat`、`ripgrep`；Ubuntu 24.04 需关 `apparmor_restrict_unprivileged_userns` |
| Windows | 独立本地账户 `srt-sandbox` + 写受限令牌 + 增量 ACL ACE | 机器级 WFP `ALE_AUTH_CONNECT` 过滤器按账户 SID 拦，只放行代理端口段（默认 60080–60089） | **一次性管理员安装**（建账户+组+WFP），依赖 **seclogon**；`--allow-loopback` 全机器唯一 |
| Windows（`mxc`，实验） | MS MXC BaseContainer（`@microsoft/mxc-sdk`，可选 peer dep） | 容器内只到宿主代理 | Node 24；MSYS2 程序起不来；容器外连不进去；不支持 `tlsTerminate` |

### 2.3 策略轴与配置

`SandboxRuntimeConfig`（`src/sandbox/sandbox-config.ts:1189`）只有四块：

- `filesystem`：读是"**先 deny 再 allow 回来**"（`denyRead` + `allowRead`，默认放开），写是"**只 allow**"
  （`allowWrite` + `denyWrite`，默认全禁）；内置保护 `.git/hooks`、shell rc、`.mcp.json` 等。
- `network`：`allowedDomains` / `deniedDomains`（支持 `:port` 与 `*.` 通配）/ `strictAllowlist` /
  `deniedResolvedAddresses`（默认禁 loopback、link-local、云元数据地址）/ `allowLocalBinding` /
  `tlsTerminate`（MITM，可见并改写请求体）/ `parentProxy`。
- `credentials`：文件与 env 的 `deny` | `mask`（哨兵替换）；Windows/macOS 上 `mask` 退化成 `deny`。
- 会话级 `ignoreViolations`、`ripgrep`、`seccomp`、`windows`（`backend`、`sandboxUser`、`proxyPortRange`、`srtWin.path`）。

**没有 exec 轴**：ASRT 不管"能运行什么程序"，那是 OS 的事。本项目的 `ExecPolicy` 仍只能留在字符串层。

### 2.4 网络的三种用法

1. **进程内代理**（默认）：`initialize()` 起 HTTP + SOCKS5，靠 OS 围栏保证"只能走代理"。
2. **`srt proxy` + 外部 decider**（`src/proxy-cli.ts`、`docs/srt-proxy.md`）：代理只做裁决执行，
   每个请求的 verdict 由宿主进程通过继承的 fd 给（4 字节长度前缀 + JSON 帧，hello/req/verdict/body，
   单帧 1 MiB / body 32 MiB）。**这正好是 Kotlin 侧做逐域批准、审计、凭据决策的接口。**
3. **自带代理**：`httpProxyPort` 指向外部代理（但那样 ASRT 不替你裁决）。

凭据注入、AWS SigV4 重签、`set-cookie` 剥离都在 1/2 里提供。

### 2.5 JVM 侧

`vendor/java-proxy-agent-src`（一个 `-javaagent`）把 `HTTP(S)_PROXY` 翻译成 `http(s).proxyHost/Port` +
`Authenticator`，由 `JAVA_TOOL_OPTIONS` 注入 —— **只在 Linux/macOS 注入**（`linux-sandbox-utils.ts:3752`、
`macos-sandbox-utils.ts:1544`），Windows 路径没有。这对本项目是硬伤，见第 4.3 节。

### 2.6 依赖、许可、成熟度

- 许可 Apache-2.0（与本仓库一致）；依赖 `commander`、`zod`、`node-forge`、`@pondwader/socks5-server`。
- 运行时：Node ≥22.12（`package.json` `engines`）；`srt-proxy` 与 seccomp 辅助可用 bun 编译成单文件，
  但 `srt` 主 CLI 官方只以 `dist/cli.js` 形式发布（`cli.ts` 里明确为 `bun build --compile` 留了静态 import）。
- 体量与测试：`src` 50 文件 / 31k 行，`test` 133 文件 / 72k 行；CI 覆盖 linux/macos/windows/docker。
- Windows 是一次性管理员安装 + 机器级状态（账户、WFP 子层、`%ProgramData%\sandbox-runtime` CA、per-user 会话库），
  升级时要求**重跑 `windows-install`**（`SETUP_VERSION` 2→3），旧版本会拒绝启动。

---

## 3. 本项目已有什么（事实）

### 3.1 接缝

`ExecutionSandbox`（`ExecutionSandbox.kt:55`）：

```kotlin
fun prepare(spec: ProcessSpec, workspace: Path, allowNetwork: Boolean,
            extraWritableRoots: List<Path> = emptyList()): ProcessSpec
val enforcesNetwork: Boolean; fun isReady(): Boolean; val autoSelectable: Boolean
val executesFileOps: Boolean
```

`prepare()` 返回的 spec 由 `ProcessLauncher.start()` 唯一启动点消费（token 启动 / `ProcessBuilder`）。
已被接入的通道：`coding.shell`、`coding.runCode`、`mvnBuild`/`verify`、技能脚本、`b4.run`、
`coding.write/append/replace/...`（P3-6 每操作一个 `powershell -File` 子进程）。
仍在后端 JVM 内（按设计）：`coding.read/glob/grep`、git/worktree/merge、LSP、ripgrep。

### 3.2 四个后端与能力矩阵

| 后端 | 读 | 写 | 网络 | 账户/管理员 | 默认 |
|---|---|---|---|---|---|
| `off` | 用户 | 用户 | 用户 | — | 老装兜底 |
| `windows-low-il` | 用户 | 工作区+缓存（Low IL） | ❌ | 都不需要 | coding 链首选 |
| `windows-appcontainer` | 仅工作区 | 仅工作区 | ✅ 无能力=无出网 | 都不需要 | browse 链首选但 **opt-in** |
| `windows-acl` | 账户可读树 | 工作区+镜像 | ❌ | 需管理员建账户 | 安装默认（历史） |

`IsolationProfile.backendChain()`：Windows = `appcontainer|low-il → windows-acl`；
Linux = `landlock → bwrap`；macOS = `seatbelt`（后两者 `of()` 直接抛"known but not implemented"）。

### 3.3 未收口清单（摘 `20261117-agent-sandbox-progress.md` §9）

1. 容器内跑 `browser4-cli`（browse 默认化的前置）；
2. coding 的 `net=off`（"与 #2 同一套代理，或 WFP 规则"）；
3. `git`/worktree/merge、LSP、ripgrep 仍在后端；
4. browse 默认启用容器；
5. `windows-acl` 的文件写下沉未验证；
6. 每次文件变更一次 `powershell` 启动（0.3–0.5 s）。

**第 2 项与 Linux/macOS 整块，正是 ASRT 的现成答案。**

### 3.4 已经踩过的坑（会再次踩到）

- "两种引号规则不能叠加"（progress §7.3）：launcher 按 C 规则加引号、`cmd /c` 再解析一次。
- 容器 SID 不能授予工作区的共享祖先；`icacls /grant` 需要目标上的 WRITE_DAC。
- `SandboxEnvironment.WHITELISTED`（`ExecutionSandbox.kt:396`）把子进程环境裁到工具链变量 —— 任何后端
  都不能假设"调用方的环境会到子进程"。
- `ReadablePaths.ALWAYS_REFUSED` = `~/.ssh`、`~/.aws`、`~/.gnupg`、`~/.config/gcloud`。

---

## 4. 能力对照：ASRT 对上本项目的四条轴

### 4.1 能补齐的

| 本项目 | ASRT 对应 | 评价 |
|---|---|---|
| Linux/macOS 内核边界（今天 0） | bwrap+seccomp / seatbelt | **最大收益**，且是本项目自己计划里"P2-1 bwrap 后端"的成品实现 |
| `net=off`（D3） | `allowedDomains: []` + `strictAllowlist` + OS 围栏 | 第一次能在 coding 模式真正兑现 |
| `NetPolicy.Allow(hosts)` / 逐域批准 | `allowedDomains` / `registerCommandNetworkLists` / ask callback / `srt proxy` decider | 与 `AgentAccessPolicy` 的批准流天然对接 |
| `ALWAYS_REFUSED` 凭据 | `credentials.files: [{mode: deny}]`（Win/macOS 上 mask 退化成 deny，行为一致） | 一处配置即可，跨平台一致 |
| `SandboxDenials` / `PermissionAudit` | 违规存储 + `annotateStderrWithSandboxFailures` + per-command `commandId` | 可以把"OS 拒绝"翻译成审计事件的现成素材 |
| `windows-acl` 的账户+ACL 模型 | `srt-sandbox` + 写受限令牌 + ACL | 同一思路但更完整（多了 WFP、CA 托管、持久授权、状态库） |
| Job Object 进程树 | Windows `srt-win` 内部 job；POSIX `--die-with-parent` | 等价 |

### 4.2 补不齐的（必须留在本项目）

- **exec 白名单**：ASRT 没有这个概念，`ExecPolicy` 仍只是 advisory。
- **browse 的"读仅工作区"**：ASRT Windows 安装期默认给沙箱账户 profile 读授权（凭据除外），与
  `ReadScope.WorkspaceOnly` 方向相反。仍可用 ASRT 表达：会话里 `denyRead: [~, 其他盘根]` +
  `allowRead: [workspace, mirrors, add-dirs]`（显式 DENY 优先于继承的 ALLOW，语义成立），
  代价是每次会话要对整棵 profile 打 deny ACE、对大目录 + glob 展开要花时间，且机器级 grant 这个事实不会消失
  （同机其它 ASRT 使用者各按自己的会话配置）。更省的选择：browse 继续用 AppContainer。
- **镜像 roots / junction / worktree**：`WorkspaceMirror` 派生的真实路径必须显式进 `allowWrite`，
  ASRT 不会自己发现。本项目已有 `MirrorRoots.derive()`，可以直接喂。
- **每任务工作区语义**：见 6.2。
- **Windows 的子进程环境**：见 4.3。

### 4.3 三处会立刻咬人的差异（评估重点）

1. **Windows 上 ASRT 不给子进程透传环境。**
   `wrapCommandWithSandboxWindows`（`windows-sandbox-utils.ts:4370`）的两跳启动只把
   `PATH`/`PATHEXT`/`NODE_USE_ENV_PROXY`、代理变量、凭据哨兵、`GIT_CONFIG_*` 通过 `--env` 覆盖进去，
   注释明确："The broker does NOT enumerate its own env"。
   → 本项目的 `ProcessSpec.environment`（`JAVA_HOME`、`M2_HOME`、`GRADLE_USER_HOME`、`BROWSER4_*`
   session pinning）**到不了 Windows 沙箱子进程**。对策：在工作区内写一个 wrapper 脚本
   （`.b4scratch/sandbox-env.cmd` / `.ps1`）再 `cmd /c <wrapper>`，只有路径过引号层；或绕开 Node 层直接调
   `srt-win exec --env`（形态 D）。
2. **Windows 上 JVM 不认识代理**（4.2 节的 javaagent 只在 POSIX 注入）。
   `mvn` / `gradle` 走代理会 407 或直连被 WFP 拦。而 `mvnBuild`/`verify` 是本项目 coding 模式的第一条命令。
   对策：复用 ASRT 的 `vendor/java-proxy-agent-src`（Apache-2.0）自建一个 `-javaagent`，同样经 wrapper 脚本
   以 `JAVA_TOOL_OPTIONS` 注入；或改用 Maven `settings.xml` 的 proxy 段（可带凭据）。
3. **Schannel 客户端在 Windows 沙箱里跑不通**（README "The child token and kept write grants"）：
   `curl.exe`、PowerShell `Invoke-WebRequest/RestMethod`、.NET `HttpClient`、`dotnet restore`、`winget`、
   `git`（schannel 后端）、`cargo`。ASRT 给 git 配了 openssl 后端，但 `.NET`/PowerShell 无解。
   → 本项目在 Windows 上的技能脚本、`verify` 里若有 PowerShell 下载，会直接坏掉。

补充：Linux 上 seccomp 层**禁止嵌套 userns**，Chromium/Electron 自己的沙箱、rootless podman、嵌套 bwrap 都会 `EPERM`。
本项目在 Linux 上跑浏览器（`--no-sandbox` 的 headless Chrome）不受影响，但"在沙箱里跑 docker/podman"的用法没有。

---

## 5. 四种接入形态

| 形态 | 接线点 | 优点 | 代价 | 结论 |
|---|---|---|---|---|
| **A. 每命令 `srt` 包装** | `AsrtSandbox.prepare()` 返回 `command = [srt, "-s", cfg, "--", *argv]` | 代码量最小；四平台一次覆盖；fail-closed 现成 | 每条命令一次 `initialize()`：ACL 打标 + WFP 自检 + 起代理；逐域批准无法累积；argv 被重新拼成 shell 字符串（引号第三层）；Windows 需机器级安装 | **只作 P0 基线**，不作产品形态 |
| **B. 常驻 broker（库 API）** | 新增 `AsrtBroker` 子进程（Node 或 bun 单文件），JSON-lines/stdio；`prepare()` 向 broker 要 `{argv, env}` | 与 ASRT 生命周期一致；一次 `initialize` 服务多命令；`updateConfig` 可热加域；`commandId` 归因；`spawnSandboxed`（mxc）也能用 | 多一个运行时与一个受监管子进程；broker 崩溃/重启要有语义 | **推荐**（配合 6.2 的会话映射） |
| **C. 只用 `srt proxy`** | Kotlin 起 `srt-proxy`，自己做 decider；子进程靠代理变量 | 网络轴单独落地，不动文件/启动路径；策略、审计、批准留在 Kotlin | 没有 OS 围栏时"代理即边界"是**建议性**的（进程可以绕过代理变量）；Windows 仍要 WFP 安装才成立 | **推荐作为 B 的网络子模块**（decider 换掉 ASRT 的 ask callback） |
| **D. 只链 `srt-win`（Rust）** | 把 `vendor/srt-win-src` 链进一个 `b4-sandbox.exe`（`SRT_WIN_DISPATCH_ARG1` 就是为此设计的） | 产品不引入 Node；Rust 是本仓库一等公民 | 要自己重写网络半边（HTTP CONNECT + SOCKS5 + TLS MITM + 域匹配 + 凭据注入），即 ASRT 最难的部分；且只有 Windows | 仅当"绝不引入 Node"是硬约束时考虑 |

**为什么 A 不能直接当产品**：ASRT 的成本集中在 `initialize()`（ACL 打标、WFP 围栏自检要起真连接、
代理监听、CA），而本项目的子进程粒度很细 —— `SandboxedFileOps` 每次文件变更就起一个进程（现在 0.3–0.5 s），
若每次再叠一个 `srt`，P3-6 的代价会翻几倍，且 Windows 的 `kept write grants`（`allowWrite` 根上的持久 ACE，
只有 `srt-win acl forget` 才清）每次启动都要重建/校验一遍。

---

## 6. 推荐方案详设（B + C）

### 6.1 组件

```
browser4 后端 (JVM)
  ├─ AsrtSandbox : ExecutionSandbox         ← 新后端 id "asrt"
  │    └─ AsrtSessions                      ← (workspace, extraRoots, policy) → 会话
  │          └─ AsrtBroker (Node/bun 子进程) ← 持有 SandboxManager
  │                ├─ initialize(config)     ← ACL / bwrap profile / 代理
  │                ├─ wrapWithSandboxArgv(...) ← 每次子进程启动
  │                └─ updateConfig(...)      ← 逐域批准
  └─ 子进程（coding.shell / runCode / skill / b4.run / fileOps）
```

- 形态 C 的 `srt-proxy` 只在需要"Kotlin 说了算"的网络裁决时启用：`AsrtBroker` 把 ASRT 的 ask callback
  经 IPC 转给 Kotlin 的 `AgentAccessPolicy`/审计；若代理与沙箱同进程（B），直接用 ask callback 即可，
  不必上 decider 协议。
- broker = 一个我们自己的 ~300 行 `.mjs`（或 bun 编译的单文件），依赖 pin 死的
  `@anthropic-ai/sandbox-runtime`，代价是把它放进 runtime bundle（本项目已有 bundle 下载/解压机制
  `cli/browser4-cli/src/daemon.rs`，`browser4-cli install` 是天然落点）。

### 6.2 会话 ↔ 任务映射（关键设计点）

- `prepare()` 每命令一次，ASRT 会话按任务一次。
- **Windows**：`filesystem` 是**会话级**的（`srt-win exec` 只接受 per-exec deny，per-exec allow 会抛，
  `sandbox-manager.ts:3161`）→ **一个任务工作区一个会话**，按 `(workspace, extraRoots)` 复用（与
  `WindowsAclSandbox.entries` 同构），空闲 TTL + 引用计数回收；任务结束 `reset()` 并
  `srt-win acl forget` 掉不再需要的持久授权。
- **Linux/macOS**：`wrapWithSandbox` 接受 per-command `filesystem` 覆盖 → 一个 broker 可以服务多个工作区，
  但仍建议按任务分会话，理由是可观测性与"代理/凭据配置随任务变"。
- 生命周期失败语义沿用现有契约：`prepare()` 抛 `SandboxUnavailableException`，**绝不裸启动**；
  broker 死亡 → 所有在跑的会话立即视为不可用（子进程由 ASRT 的 job/`--die-with-parent` 收尾）。

### 6.3 `IsolationProfile` → `SandboxRuntimeConfig` 映射

| 本项目 | ASRT |
|---|---|
| `ReadScope.CurrentUser` | `denyRead = ALWAYS_REFUSED`（默认读放开）；`credentials.files` 同步一份 |
| `ReadScope.WorkspaceOnly` | `denyRead = [~, 其他盘根]`，`allowRead = [workspace, mirrors, add-dirs, toolchain roots]`（显式 DENY 盖住继承的 ALLOW，安装期 profile grant 不必撤，但会话要对整棵 profile 打 deny） |
| `WriteScope.Workspace` | `allowWrite = [workspace, mirrors, .b4scratch]`，`denyWrite = []` |
| `WriteScope.WorkspacePlus(caches)` | 上面 + `defaultCaches()`（`~/.m2`、`~/.gradle`、`~/.cache`、`%TEMP%`） |
| `WriteScope.CurrentUser`（`--unrestricted`） | `filesystem.disabled: true`（注意：network/credentials 仍生效，与今天的 `off`
  不等价 —— 需要在 `coding.permissions` 里换文案） |
| `NetPolicy.Off` | `allowedDomains = []`、`deniedDomains = ["*"]`、`strictAllowlist = true`；`b4.run` 例外见 6.5 |
| `NetPolicy.BrowserOnly` | 同上（browser 在 backend，不在沙箱内） |
| `NetPolicy.Allow(hosts)` | `allowedDomains = hosts + 代理/loopback 例外`，`strictAllowlist = true`（把"提示抑制"变成"策略执法"） |
| `ALWAYS_REFUSED` | `credentials.files = [{path, mode: "deny"}]` |
| `SandboxEnvironment.WHITELISTED` | POSIX：把裁剪后的 env 交给 broker 进程即可继承；**Windows：见 4.3-1 的 wrapper** |
| `ExecPolicy` | 无对应（保留字符串层，文案不得声称是边界） |
| 违规 | `SandboxViolationStore` → `PermissionAudit.CODE_SANDBOX_DENIED` |

### 6.4 Windows 落地要点

- **模式归位**：WFP 围栏按 `srt-sandbox` 账户 SID 生效，所以"只想补网络"也必须让进程跑在 ASRT 账户下 ——
  文件边界会一并归 ASRT。结论：coding 用 `asrt`（读=profile grant 给到的用户树、写=allowWrite、网=allowlist，
  正是 `ReadScope.CurrentUser` + `WriteScope.WorkspacePlus` 的内核表达）；browse 继续用
  `windows-appcontainer`（不要 admin、不要账户、无能力即无网）；`windows-low-il` 留作"不装任何东西"的默认。
- **安装**：`srt windows-install` 必须是显式的、由用户/管理员触发的步骤（`browser4-cli doctor` 检查 +
  打印一条命令），默认不自动执行；`--skip-profile-grant` 与 browse 模式的组合要写进文档。
- **不要和 `browser4-agent` 账户并存**：ASRT 拒绝接管不是它建的账户（名字必须是 `srt-sandbox` 或安装时指定）。
  两条账户体系并存只会制造两套 ACL。建议：**长期收敛到 ASRT 的账户模型**，`windows-acl` 保留为 legacy。
- **loopback 模式全机器唯一**：本机若已按"关 loopback"装过，本项目就不能要求"开" —— 反之亦然，
  报错是 `wfp_loopback_mismatch`（`initialize()` 直接拒绝，`srt-win exec` 退出 19）。这是多产品共存机器上的真实冲突。
- **seclogon 必须是 running**（两跳启动用 `CreateProcessWithLogonW`）；本项目现有 token 启动
  （`LogonUserW` + `CreateProcessAsUserW`）**不需要** seclogon，所以这是引入 ASRT 后新增的机器前提。
- **不碰 `mxc`**：Node 24 + 实验性 + MSYS2 起不来 + 外连不进来（CDP 反连不可行）+ 不支持 `tlsTerminate`。

### 6.5 `b4.run` 的回环通道

`browser4-cli` 必须能连 `127.0.0.1:18182`。Windows 围栏默认**不许直连 loopback**（只放行代理端口段）。
两个选项：

- **推荐**：`allowedDomains` 加 `localhost:18182`（ASRT 对 loopback 名字有专门放行：allow 一个 loopback 名字
  就是"我要连本机"），CLI 通过代理连后端；要求 `browser4-cli`（reqwest + rustls，`Cargo.toml:24`）读 `http_proxy`
  —— reqwest 默认读环境变量，但需要在 e2e 里实证一次；`no_proxy` 在 loopback 关闭时不会被设置（代码里 Windows 显式删掉了）。
- 备选：安装期 `--allow-loopback`。代价是**任何**本地服务对所有沙箱进程可见（含本地转发器），
  且把机器钉在"开"的模式上。

`BROWSER4_CLI_STATE_DIR` 已有（P3-4），继续指向 `<workspace>/.b4scratch/cli-state`。

### 6.6 `executesFileOps` 通道

ASRT 下"文件写下沉"仍按 P3-6 的形态：broker 在会话里 `wrapWithSandboxArgv` 一个
`powershell -File <b4scratch>\fs-op.ps1`（所有变更先写暂存文件，命令行只传路径）。
不同点：这次**不需要每操作一个 session**，只要一个 broker 会话；`SandboxFileOps` 接口不变。
若 ASRT 的会话启动成本在 Windows 上偏高，可保留现后端给 fileOps（`executesFileOps=false` + 诚实标注）。

### 6.7 状态、文案与审计

- `SandboxStatus.effectiveId` 只要新 id 能解析即可 —— 注意它读的是 `ExecutionSandboxes.configuredId()` +
  `of()`，所以新 id 必须进 `of()` 的 `when`（只加进 `KNOWN_IDS` 会落进"known but not implemented"抛错分支）；
- `AsrtSandbox.enforcesNetwork = true` → `isolationSummary()` 第一次在 coding 模式打出 "network: enforced"（Linux/macOS/Windows 均成立）；
- 违规行（`<sandbox_violations>` 等价物）→ `SandboxDenials.envelope()` + `PermissionAudit`，
  沿用 `commandId` 归因；
- `coding.permissions` 增加一行："ASRT 版本 / 会话 id / loopback 模式 / 需要管理员安装的那台机器是否就绪"。

### 6.8 开关与默认值

- 新增 id：`asrt`（机制按平台选择）；`bwrap`、`seatbelt` 视作 `asrt` 的别名 —— 注意它们现在在
  `KNOWN_IDS` 里走的是"known but not implemented"的**抛错分支**，要改成委托到 `asrt`，否则和今天一样用不了。
- `IsolationProfile.backendChain()`：Linux/macOS 各改为 `["asrt"]`（`bwrap`/`seatbelt` 只留作操作者显式 id）；
  `landlock` 从链上撤掉（ASRT 不用 Landlock）。Windows 暂不改（保持 `low-il` / `appcontainer` 默认），
  `asrt` 只能显式选。
- `SandboxDefaults`：**不自动迁移**任何已有安装；Linux/macOS 的新装在 `checkDependencies` 通过时才可默认 `asrt`。

### 6.9 失败语义

| 情况 | 行为 |
|---|---|
| `checkDependencies` 失败（缺 bwrap/socat/rg、缺 srt-win、未安装、loopback 模式不匹配） | `prepare()` 抛 `SandboxUnavailableException`，文案带"要跑哪一条命令" |
| broker 启动失败/超时 | 同上；绝不回退到 `ProcessBuilder` |
| 会话被 ASRT 判为不可用（`initialize` 拒绝） | 该任务降级为 `off` 并记 `code=UNENFORCED` 审计事件 + `WARN`，不再声称有边界 |
| 子进程被 OS 拒绝 | 同现有 `SandboxDenials` 翻译路径 |

---

## 7. 风险清单

| # | 风险 | 影响 | 缓解 | 阻断？ |
|---|---|---|---|---|
| R1 | 多一个 Node ≥22.12 运行时（或自建 bun 单文件，需 Bun ≥1.4 才有 `tlsTerminate`；本机 bun 1.3.14） | 产品依赖面 + 体积 | 单文件 broker 进 runtime bundle；开发期用系统 Node | 否（需拍板） |
| R2 | Windows 机器级安装（管理员、本地账户、WFP、CA、状态库），升级要重跑 | 企业部署与 CI 成本；与同机其他 ASRT 用户互斥（loopback 模式） | 只作显式 opt-in；`doctor` 检查；文档写清共存语义 | 否（需拍板） |
| R3 | Windows 子进程拿不到调用方 env | `JAVA_HOME`/`M2_HOME`/`BROWSER4_*` 丢失，构建与 session pinning 坏 | wrapper 脚本 / 直接 `srt-win exec --env` / `coding.permissions` 标注 | 是（须先解） |
| R4 | Windows 上 JVM 不认代理 | `mvn`/`gradle` 取不到依赖 | 自建 javaagent（复用 Apache-2.0 源码）/ Maven settings.xml proxy | 是（须先解） |
| R5 | Windows Schannel 客户端断链 | PowerShell 下载、`.NET`、`dotnet restore`、`cargo` 失效 | 文档列清单；技能脚本改用 curl/openssl 侧；必要时保留 `low-il` 给这些任务 | 部分 |
| R6 | 引号第三层（argv → 命令字符串 → `cmd /s`） | 与 §3.4 同一类 bug 复发 | 优先"路径 + wrapper 脚本"，不拼内联命令；e2e 用带空格/引号的用例钉住 | 否 |
| R7 | 会话粒度错配（每命令 `initialize`） | 性能、ACL 堆积、批准不累积 | 6.2 的会话映射；P0 就测出启动成本 | 否 |
| R8 | ASRT 是 beta，配置格式会变 | 升级破坏 | pin 版本 + 一处配置生成器 + 契约测试 | 否 |
| R9 | browse 的"读仅工作区"在 ASRT 上要靠"整棵 profile 打 deny 再 allow 回来"，大目录 + glob 展开有成本 | browse 边界更难说清、会话变慢 | browse 继续用 AppContainer；ASRT 只做 browse 的网络轴 | 否 |
| R10 | Linux 禁嵌套 userns | 沙箱里不能跑 Chromium 自带沙箱/rootless podman/嵌套 bwrap | 文档；必要时 `allowNestedUserNamespaces`（会削弱写拒绝） | 否 |
| R11 | Docker/CI 内运行（本项目夜间 CI 起容器） | bwrap 需要 userns/能力 | `enableWeakerNestedSandbox` 或给容器加 cap；夜间门禁只跑依赖检查 | 否 |
| R12 | 代理 token 出现在 Windows runner 命令行 | 同机同用户可读；本地代理可被借用 | 单用户开发机可接受；共享机文档警告 | 否 |
| R13 | Windows 的 per-command deny 与 write-root 走命令行（`CreateProcessW` 上限 32 767 字符，ASRT 在 ~30 000 处抛 `argv_too_long`） | `--add-dir` 多、镜像 roots 多、路径长时会撞上，且是硬失败 | 会话级 `allowWrite` 收敛 + 每命令只放必要的 deny；错误文案要能指向"减少 grant" | 否 |

---

## 8. 分期与验收

| 期 | 内容 | 验收 |
|---|---|---|
| **P0 探针**（3–5 人日） | 在 Linux/macOS 上跑通 `srt` 包装 `python -c` 三态（读/写/网）；量 `initialize`、wrap、代理的延迟；写一个 broker 原型（stdio JSON）；确认 bundle 里放什么 | `docs/agent-permissions-redesign.md` §4 的三条不变式在 Linux/macOS 上由脚本自动判定；`coding.verify` 出结构化 verdict；给出启动成本数字 |
| **P1 Linux/macOS 后端**（5–8 人日） | `AsrtSandbox` + `AsrtSessions` + 配置生成器 + `SandboxStatus`/`coding.permissions` 接线 + 测试（含引号与空格路径） | `browser4-coding` 既有 882 例不回归；新增 asrt 后端单测；三平台矩阵脚本绿 |
| **P2 Windows 的 coding 后端**（5–8 人日） | `asrt-win` 作为 coding 的"带网络的 strong 后端"：进程必须跑在 `srt-sandbox` 账户下（WFP 按 SID 拦），所以文件边界也归 ASRT（`allowWrite` = workspace+镜像+缓存，读 = profile grant 给到的用户树）；解 R3/R4/R5。`windows-low-il` 保留为免安装默认，`windows-appcontainer` 继续管 browse | `net=off` 不变式在 Windows 成立；`mvn` 在沙箱内能装依赖；`b4.run` 能连后端；`low-il` 回归不变 |
| **P3 收口**（3–5 人日） | 逐域批准（ask callback 或 `srt proxy` decider）、违规→审计、`doctor`、文档、默认值评估 | 审计里能看到"拒绝的域/理由"；文档与 `docs/config.md` 同步；默认值决策记录 |

总投入量级：**单人 3–4 周**（不含 Windows 机器级安装的验证与用户沟通成本）。

---

## 9. 需要拍板的决策

| # | 决策 | 建议 |
|---|---|---|
| D-a | 运行时：要求 Node ≥22.12 / 自建 bun 单文件 / 不用 ASRT | 自建单文件进 bundle（dev 用系统 Node） |
| D-b | 生命周期：每命令 `srt` vs 常驻 broker | 常驻 broker（P0 先量成本再定） |
| D-c | Windows 安装归属：ASRT 的 `srt-sandbox` vs 本项目 `browser4-agent` | 长期收敛到 ASRT；`windows-acl` 降级为 legacy，不并存 |
| D-d | `b4.run` 回环：代理允许 `localhost:<port>` vs 安装期 `--allow-loopback` | 前者（保住围栏） |
| D-e | Windows 默认值 | 本次不改：`low-il`/`appcontainer` 继续默认，`asrt` 显式启用 |
| D-f | `--unrestricted` 的语义 | `filesystem.disabled` 但网络/凭据仍生效（比今天更紧），文案必须改 |
| D-g | 是否保留自研 Linux/macOS 后端计划 | 撤掉（ASRT 就是一个 bwrap/seatbelt 实现），只保留 `asrt` |

---

## 10. 本机实测记录（Windows 11，2026-11-17）

| 步骤 | 结果 |
|---|---|
| `npm ci` → `npm install --include=dev --ignore-scripts` | 首次 `npm ci` 在 `prepare` 阶段因 `husky` 失败（本机 `NODE_ENV=production` 让 dev 依赖被跳过，husky 不在，失败信息还指向 lockfile 之外的路径）；显式 `--include=dev` 后 305 包，`--ignore-scripts` 可跳过 git hook 安装 |
| `npx tsc` | 成功，产出 `dist/` |
| `node dist/cli.js --version` | `0.0.83`，耗时 **约 0.25 s** |
| `VENDORED_SRT_WIN_EXE` | 源码检出里**不存在**（`vendor/srt-win/x64/srt-win.exe` 是 npm 包打包产物），`checkWindowsSandboxStatus()` / `checkWindowsDependenciesAsync()` 直接抛 "path … does not exist" |
| `cargo build --release --locked`（`vendor/srt-win-src`） | **未在本轮跑完**：仓库自带 `rust-toolchain.toml` 钉 1.98.1，rustup 先要下载/修复该工具链（本机 PATH 上是 1.96），评估窗口内没等到编译阶段。代码层面主要成本是 `windows` crate（30+ features）与 `rusqlite` bundled（要编 C）。复现时先 `rustup toolchain install 1.98.1` |
| `srt windows-install` | **未执行**：管理员/UAC + 机器级账户与 WFP 过滤器，评估阶段不做 |
| 环境 | Node 24.19 / npm 11.17 / bun 1.3.14（< 1.4，`tlsTerminate` 需升级）/ cargo 1.96 / PATH 上 JDK 17（项目要 25） |

> 未实测的部分（真实沙箱内行为、Windows 安装后表现、Linux/macOS）全部来自代码与 README，
> 实施 P0 时必须用探针补上，不能拿本文当证据。

---

## 11. 参考

- ASRT：`src/index.ts`（导出面）、`src/cli.ts`（`--settings` / `-c` / `--control-fd`）、
  `src/sandbox/sandbox-manager.ts`（`initialize`/`wrapWithSandboxArgv`/`updateConfig`/`checkDependencies`）、
  `src/sandbox/sandbox-config.ts`（schema）、`src/sandbox/sandbox-schemas.ts`（轴语义）、
  `src/sandbox/windows-sandbox-utils.ts:4370`（Windows 启动与环境覆盖）、
  `src/proxy-cli.ts` + `docs/srt-proxy.md`（decider 协议）、`vendor/srt-win-src/src/lib.rs`（`run_from_args`）、
  README §Platform Support（676）、§Windows (alpha)（740）、§MXC（1000）、§Local servers（1032）、
  §Known limitations（1054）、§TLS Termination（1124）。
- 本项目：`browser4-coding/.../sandbox/ExecutionSandbox.kt`、`IsolationProfile.kt`、`SandboxStatus.kt`、
  `CodingComponents.kt:433`、`docs/agent-permissions-redesign.md` §2/§13/§16、
  `docs-dev/copilot/20261117-agent-sandbox-progress.md` §4/§5/§9、`docs/config.md`（`browser4.agent.sandbox`）。
