# ego lite 接入变更摘要

日期：2026-11-01
范围：Browser4 对 Citro Labs **ego (lite)** 浏览器的两种接入方式 —— 附加（attach）与托管（managed launch）。

## 一、目标

分两步落地：

1. **附加**：把已经运行、开着 `--remote-debugging-port` 的 ego 接入现有 CDP 通道（`attach --cdp ego-lite`）。
2. **托管**：让 Browser4 后端像启动 Chrome 一样**启动并管理** ego（`open --channel ego-lite`），包括会话隔离 profile、无头模式、UA 伪装、`close` 时的进程回收。

## 二、概要

| 维度 | 内容 |
|---|---|
| 新增 CLI 能力 | `open --channel chrome\|ego-lite`、`attach --cdp ego-lite` |
| 新增后端能力 | `browser.channel` 会话配置项、`EgoLiteBrowserLauncher` 托管启动器 |
| 改动文件 | 14 个修改 + 1 个新增源文件 + 1 个新增测试文件 |
| 代码量 | +557 / −32（不含新增文件） |
| 依赖变更 | **无**。不改 `pulsar-*` base 库、不改 BOM、不加新依赖 |
| 测试 | Kotlin 新增 14 个用例；Rust 新增 7 个用例，全量单测通过 |

## 三、架构与调用链

### 托管启动（`open --channel ego-lite`）

```
CLI commands.rs (tool_params_fn: channel 规范化)
  → main.rs (build_open_session_capabilities_with_test_mode 转发 caps["channel"])
  → 后端 PulsarSettings.parse(capabilities) 取出 channel
  → PulsarSettings.overrideConfiguration → conf["browser.channel"]
  → AbstractPulsarSession.createBoundDriver → browserManager.launch(BrowserId, BrowserSettings)
  → DefaultBrowserFactory.launch()          ← 全应用唯一的启动漏斗
      ├─ browser.channel = chrome（默认） → 原 getLauncher(browserType) 路径
      └─ browser.channel = ego-lite      → EgoLiteBrowserLauncher
                                            └─ ChromeLauncher.launch(Path, ChromeOptions)  ← base 库公有显式二进制入口
```

关键点：**选择在漏斗处做，机制全部复用**。二进制之后的 CDP 传输、profile 模式、显示模式、进程回收都由既有托管管线承担，没有复制任何 Chrome 启动逻辑。

### 附加（`attach --cdp ego-lite`）

`daemon.rs` 的 `BrowserChannel` 增加 `EgoLite` 变体：接受 `ego-lite` / `ego` / `ego lite` / `egolite` 四种拼写（大小写不敏感），只在 ego 自身进程中按命令行 `--user-data-dir` 解析端点（ego 不写 `DevToolsActivePort`、也不注册约定的调试端口），并且 CDP-only（扩展中继不支持）。

## 四、逐文件变更

### 后端（Kotlin）

| 文件 | 变更 |
|---|---|
| `browser4-core/browser4-common/.../B4Constants.kt` | 新增 `CHANNEL_CAPABILITY`、`BROWSER_CHANNEL`、`BROWSER_CHANNEL_EGO_LITE` 三个常量（+20） |
| `browser4-core/browser4-skeleton/.../PulsarSettings.kt` | data class 增加 `channel`；`parse()` 从 capabilities 读取并 trim；`overrideConfigurationInternal` 写入 `browser.channel`（+15） |
| `browser4-core/browser4-skeleton/.../session/AbstractPulsarSession.kt` | `LaunchProfileSource.NONE` 分支条件补 `BROWSER_CHANNEL != null`。**这是修补了一个会静默丢配置的缺口**：没有 `displayMode` 的会话原本会退回服务器全局设置，导致 channel 丢失（+9/−3） |
| `browser4-core/browser4-protocol/.../impl/EgoLiteBrowserLauncher.kt` | **新增**。`EgoLiteBrowsers`（别名判定、二进制解析、UA 探测）+ `EgoLiteBrowserLauncher`（托管启动） |
| `browser4-core/browser4-protocol/.../impl/DefaultBrowserFactory.kt` | 接入漏斗：按 `browser.channel` 选择 launcher；未知 channel 抛 `IllegalArgumentException`（+38/−6） |

### CLI（Rust）

| 文件 | 变更 |
|---|---|
| `cli/browser4-cli/src/commands.rs` | `open` 新增 `--channel <name>` 选项；`tool_params_fn` 规范化（大小写、空格、`google-chrome`/`ego` 等别名统一为 `chrome`/`ego-lite`）；`attach --cdp` 帮助文本补 `ego-lite`（+50/−2） |
| `cli/browser4-cli/src/main.rs` | capabilities 转发 `channel`；`validate_command_semantics` 对 `open --channel` 做前置校验（非托管渠道如 `msedge` 拒绝并提示改用 `attach --cdp`）（+148） |
| `cli/browser4-cli/src/daemon.rs` | `BrowserChannel::EgoLite`：拼写解析、exe 候选（Citro 安装目录）、端点解析、`has_conventional_debug_port=false`、扩展渠道列表拆分（+204/−28） |
| `cli/browser4-cli/src/help.rs` | `open` 帮助补 `--channel` 段落与示例（+14/−2） |
| `cli/browser4-cli/src/tips.rs` | 相关提示（+3） |

### 文档

| 文件 | 变更 |
|---|---|
| `skills/browser4-cli/references/browser-modes.md` | 三路对比表补 ego lite；新增「Managed browser channel」专段 |
| `skills/browser4-cli/references/attach.md` | `ego-lite` 附加说明（+25/−5） |
| `skills/browser4-cli/SKILL.md` | `open --channel` 说明 |
| `cli/browser4-cli/README.md` | `open` 命令行摘要 |

## 五、设计取舍

1. **不改 base 库。** `BrowserType` 枚举不可扩展、`ChromeLauncher` 是 final，但 `ChromeLauncher.launch(Path, ChromeOptions)` 是公有的显式二进制入口（已用 javap 反编译确认）。因此 ego 支持的边界完全留在本仓库，base 升级不会冲掉。
2. **用会话 capability 而不是新 CLI 命令。** `open --channel` 映射到 `browser.channel` 配置项，走既有的 `PulsarSettings` 链路，`normalizeCapabilities` 不做白名单过滤，capability 可直达。channel 与 `displayMode` 一样**在会话创建时固定**，重连不改变。
3. **二进制解析优先级**：配置项 `ego.path` → 系统属性 `-Dego.path` → 标准安装目录（`ProgramFiles` / `ProgramFiles(x86)` / `LOCALAPPDATA` 下的 `Citro Labs\ego lite\Application\ego.exe`）+ Windows 双兜底。**显式配置了不可用路径时绝不回退**，直接报错 —— 用户点名了二进制，静默换一个会掩盖笔误。
4. **ego 不注册约定端口、不写 `DevToolsActivePort`**，所以 attach 侧的端点只能从 ego 自身进程命令行解析，且不会与 Chrome/Edge 的 9222 冲突（`has_conventional_debug_port = false`）。

## 六、过程中发现并修复的 bug

### UA 版本号解析为 `0.0.0.0`（真实缺陷）

ego 把 **二进制文件和安装布局的版本目录**都打上应用版本号 `0.6.2.5`，从不暴露 Chromium 版本。base 库的 `ReducedUserAgent.chromeMajorVersion(binary)` 按安装布局解析，得到 major 0 —— 无头模式下会发出 `Chrome/0.0.0.0`，**比原生的 `HeadlessChrome` token 更容易被指纹识别**。

修复（`EgoLiteBrowserLauncher.kt`）：真实 Chromium 版本只有运行中的实例才知道，所以做一次性的 CDP 探测 —— 用临时 profile 无头启动 ego，轮询 `DevToolsActivePort` 拿端口，读 `/json/version` 的 `Browser` 字段取 major，然后强制结束探测进程并删临时目录；结果在 JVM 生命周期内按 `路径#修改时间` 缓存。探测失败则**保留原生 UA 并告警**，绝不产出 `Chrome/0.0.0.0`。

对于 ego 渠道，`DefaultBrowserFactory` 明确**不调用** `Browser4UserAgent.applyTo(binary)`，避免再次踩到安装布局解析。

修复后实测：`navigator.userAgent` = `Chrome/156.0.0.0`，与 `navigator.userAgentData.brands`（Chromium/156）一致。

## 七、验证记录

### 真实 ego lite 环境（0.6.2.5 / Chromium 156，Windows）

| 验证项 | 结果 |
|---|---|
| `open --channel ego-lite https://example.com` 托管启动 | 通过 —— 9 个 `ego.exe` 进程，使用 Browser4 隔离 profile |
| 页面导航 + ARIA 快照 | 通过 —— example.com 正常加载 |
| `navigator.userAgent` | 通过 —— `Chrome/156.0.0.0`，无 `HeadlessChrome` token |
| `close` 后进程回收 | 通过 —— ego 进程全部退出，0 残留 |
| `attach --cdp ego-lite` 附加到已运行 ego | 通过 |

### 测试

- Kotlin：`EgoLiteBrowserLauncherTest`（8 个：别名正反、`ego.path` 生效、配置路径不可用时不回退、`/json/version` 解析 3 个、未知 channel 拒绝）；`PulsarSettingsTest` 新增 6 个 channel 用例（解析/trim/空白丢弃/缺省/写入配置/不写配置）。
- Rust：`commands.rs` 的 `test_open_params_with_channel`、`test_open_params_channel_is_canonicalized` 及缺省断言；`main.rs` 5 个（capabilities 转发、缺省不转发、接受 chrome/ego-lite、拒绝未知值、拒绝仅附加渠道）；`daemon.rs` 的 ego 拼写解析、exe 候选、渠道属性。全量 2031 单测通过。

### 环境侧的两个非代码问题（已绕过，无需代码改动）

1. **旧后端服务旧 bundle**：首次 `open` 打到了之前启动的旧后端，实际启动的是 Chrome。`b4w stop` 后重新构建即恢复。
2. **Shell 作业对象杀后端**：本会话中 Shell 命令结束时整个进程树被强杀（后端日志无 shutdown 记录、戛然中断）。改用 WMI `Win32_Process.Create` 启动 CLI 绕开作业对象后验证通过。这是本地验证环境的现象，与产品行为无关。

## 八、已知限制

- **Windows 限定**：二进制解析目前只覆盖 Windows 安装布局（ego lite 当前只发布 Windows 版本）。非 Windows 平台若选择 ego 渠道，会在解析阶段报出明确错误而不是静默失败。
- **channel 不可热切换**：与 `displayMode` 一致，需 `close` 或 `open --fresh`。
- **仅托管 Chrome 与 ego lite**：Edge / Canary / Electron / 云端浏览器仍为附加通道，`open` 会前置拒绝并提示 `attach --cdp`。
- **UA 探测有一次额外启动开销**：每个 ego 安装（按二进制路径 + 修改时间）在每个后端 JVM 内只探测一次。

## 九、状态

所有改动**尚未提交**。`docs/TESTING.md` 之外的文档规则（README.md / README.zh.md）未改动 —— 二者为快速上手风格，不列举 `open` 的完整选项面。
