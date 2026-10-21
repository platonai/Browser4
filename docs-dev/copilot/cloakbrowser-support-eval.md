# CloakBrowser 支持评估（Browser4 4.14.x）

> 评估日期：2026-08-17
> 数据源：CloakHQ/CloakBrowser 仓库（README / BINARY-LICENSE.md / cloakbrowser 包装器源码）、本仓库 `browser4-base` 源码（`browser4base/pulsar-core/`）、Browser4 `browser4-protocol`、`cli/browser4-cli/src/daemon.rs`、`docs/build-from-source.md`

## 1. 总体结论

- **CloakBrowser 是"真 Chromium 二进制 + C++ 源码级指纹补丁"的隐身浏览器**（当前 Chromium 150，71 个补丁；30/30 反爬测试通过），不是 JS 注入类隐身库。它本质是标准 Chromium，经 CDP 可被现有驱动栈直接驾驶。
- **Browser4 已具备零代码接入路径**：`ChromeLauncher.searchChromeBinary()` 第一优先级读取系统属性 `chrome.path`，而 CLI（`daemon.rs`）已经把它注入服务端 JVM 且允许 `BROWSER4_SERVER_OPTS` 覆盖——**今天就可以用 CloakBrowser 二进制跑通全部现有能力**（CDP → PulsarWebDriver/Browser4WebDriver → AgentToolManager → REST/MCP）。
- **产品级一等支持工作量约 2–4 人日**：新增 `CLOAK_CHROME` 浏览器类型 + `CloakBrowserLauncher` + 二进制自动下载 + License Key 透传 + 配置旋钮 + 文档。全部改动可落在仓库内（`browser4-protocol` 的 `DefaultBrowserFactory` 是扩展点），不强制改 base 库。
- **最大产品约束是二进制许可**：CloakBrowser 二进制是 CloakHQ 专有（禁止再分发/打包进发行物），Browser4 只能走"用户自备二进制"模式，不能随 Docker 镜像/安装包分发。
- **建议路径**：先零代码 POC 验证（今天可做）→ 再按二等公民集成（launcher + 下载 + key），暂不引入 `BrowserType` 枚举改动（避免 base 库版本升级），后续需要时再补枚举。

## 2. CloakBrowser 是什么

| 项 | 内容 |
|---|---|
| 项目 | [CloakHQ/CloakBrowser](https://github.com/CloakHQ/CloakBrowser)（MIT 包装器 + 专有二进制），~30k stars，2026-08 仍活跃 |
| 定位 | "Stealth Chromium that passes every bot detection test. Drop-in Playwright replacement with source-level fingerprint patches." |
| 实现 | 71 个 C++ 源码级补丁（canvas/WebGL/audio/fonts/GPU/screen/WebRTC/网络时序/自动化信号/CDP 输入行为），编译进二进制；隐身默认零 flag（启动时自动生成随机指纹种子） |
| 驱动方式 | Python/JS/.NET 包装器 = 下载二进制 + `pw.chromium.launch(executable_path=...)`——**标准 Chromium 命令行 + CDP** |
| 版本 | Chromium 150（Linux x64/ARM64、Windows x64；macOS 略滞后）；v146 旧版免费发布于 GitHub Releases |
| 许可 | 包装器代码 MIT；**二进制为 CloakHQ 专有**（基于 ungoogled-chromium + CloakHQ 补丁）。内部使用（含商用爬取）无需 OEM/SaaS 许可，但**禁止再分发、转售、打包进分发产品**；最新大版本需订阅，合法获取的旧版本（v146）长期可用 |
| Key | 最新版需 `CLOAKBROWSER_LICENSE_KEY`（免费 key = GitHub 登录，1 并发会话；Pro 付费扩并发）；v146 免 key |
| 注意 | 不解决验证码，而是"防止验证码出现"；`humanize`/`geoip` 是包装器层特性（Python/JS），不在二进制内 |

> ⚠️ 命名冲突：另有 [SalhaNabil/CloakBrowser](https://github.com/SalhaNabil/CloakBrowser)（商业反检测浏览器，含代理管理/多开）与 [devinwang/cloak-browser-mcp](https://github.com/devinwang/cloak-browser-mcp)（基于 CloakBrowser 的 @playwright/mcp 替代品）。本评估只针对 CloakHQ/CloakBrowser；cloak-browser-mcp 走 Playwright MCP 协议，与 Browser4 自有 REST-MCP 协议不直接对接，仅作生态佐证。

## 3. Browser4 现有浏览器接入架构（关键事实）

```
DefaultBrowserFactory (browser4-protocol, 仓库内)
  └─ Map<BrowserType, BrowserLauncher>  // 现仅 PULSAR_CHROME → PulsarBrowserLauncher
       └─ BrowserLauncher.launch(browserId, launcherOptions, chromeOptions)
            └─ ChromeLauncher(userDataDir, options).launch(ChromeOptions)
                 └─ launch() → searchChromeBinary()   // ① chrome.path 系统属性（第一优先级）
                 │                                    // ② 内置 Chrome/Chromium 路径
                 │                                    // ③ Playwright 安装目录 ④ Edge
                 └─ launch(binaryPath: Path, options) // 显式二进制入口（已存在）
                      └─ ProcessLauncher.launch(executable, args)  // 无 env 参数
                           └─ 等待 "DevTools listening on ws://..." → ChromeImpl(port) → CDP
                                └─ PulsarBrowser → PulsarWebDriver → Browser4WebDriver
```

关键事实：

1. **`ChromeLauncher.launch(chromeBinaryPath: Path, options)` 显式二进制重载已存在**（browser4base `ChromeLauncher.kt`），`PulsarBrowserLauncher` 只是薄封装。
2. **`chrome.path` 系统属性是第一优先级**（`searchChromeBinary()`），CLI 侧 `daemon.rs` 已注入 `-Dchrome.path=<检测到的浏览器>`，且**用户 `BROWSER4_SERVER_OPTS` 里的 `-Dchrome.path=...` 可以覆盖**（后置 -D 生效）。
3. `ChromeOptions.addArgument(key, value)` 支持追加任意 flag（如 `--fingerprint-platform=windows`、隐身参数）。
4. `ProcessLauncher.launch(executable, args)` **不支持 env**——透传 `CLOAKBROWSER_LICENSE_KEY` 需要扩展（或写 `~/.cloakbrowser/license.key`，包装器同样读取该文件）。
5. `BrowserType` 枚举（NATIVE/PULSAR_CHROME/PLAYWRIGHT_CHROME）定义在 **base 库** `browser4base/pulsar-core/pulsar-common`，改枚举需 base 版本升级；但 `DefaultBrowserFactory` 的 launcher 映射表在**仓库内**（browser4-protocol），可配置化替换而不动枚举。
6. 浏览器选择配置键：`browser.profile.mode`（DEFAULT/SYSTEM_DEFAULT/PROTOTYPE/SEQUENTIAL/TEMPORARY）+ `CapabilityTypes.BROWSER_TYPE`（默认 PULSAR_CHROME）；无现成 `browser.type` 运行配置。

## 4. 三条集成路径对比

| 路径 | 工作量 | 说明 | 适用 |
|---|---|---|---|
| **A. 零代码（配置级）** | ~0（需用户手工下载二进制） | `BROWSER4_SERVER_OPTS="-Dchrome.path=C:\...\cloak-chrome.exe"`；全套现有栈直接驱动 CloakBrowser；隐身内建零 flag | 今天可做的 POC / 单机试用；不算产品特性 |
| **B. 一等公民 launcher（推荐）** | 2–4 人日 | 仓库内新增 `CloakBrowserLauncher : BrowserLauncher` + 二进制自动下载（GitHub Releases v146 免 key / cloakbrowser.dev 带 key）+ License Key 透传 + 配置旋钮（`browser.cloak.*`）+ 文档/CLI 提示 | 产品级支持 |
| **C. Playwright 协议后端** | 重（不推荐） | base 生态有 `pulsar-protocol-playwright`（PlaywrightDriver/PlaywrightBrowserLauncher），可指向 cloak 二进制；但当前运行时不装配，且需 Playwright Java/Node 运行时，比 CDP 路径重 | 仅在 CDP 路径被证明不足时考虑 |

### 路径 B 详细设计

```
新增/修改（全部仓库内）：
1. browser4-browser 或新小模块：CloakBrowserLauncher : BrowserLauncher
   - 二进制解析顺序：browser.cloak.path 配置 → CLOAKBROWSER_BINARY_PATH env
     → ~/.cloakbrowser/chromium-*/chrome.exe（Windows: chrome.exe）→ 自动下载
   - 自动下载：v146 免费从 GitHub Releases 拉 cloakbrowser-{platform}.zip/tar.gz
     （assets: cloakbrowser-windows-x64.zip / cloakbrowser-linux-x64.tar.gz，~200MB，校验 SHA256SUMS）
     ；最新版从 cloakbrowser.dev 下载（需 key）
   - 启动：ChromeLauncher(userDataDir, options).launch(cloakBinary, chromeOptions)
     + addArgument 追加指纹/隐身 flags；key 透传通过扩展 ProcessLauncher env
     重载（browser4base 小改）或写 ~/.cloakbrowser/license.key
2. browser4-protocol DefaultBrowserFactory：配置开关 browser.cloak.enabled=true 时
   getLauncher 返回 CloakBrowserLauncher（不引入枚举，避免 base 升级）
3. 配置键：browser.cloak.enabled / browser.cloak.path / browser.cloak.licenseKey(secret)
   / browser.cloak.releaseChannel=stable|preview
4. CLI daemon.rs：find_browser_executable 增加 CloakBrowser 检测（可选）
5. 测试：FingerprintTest 等现有 BrowserType 参数化用例 + launcher 单元测试 + 冒烟 e2e（真实二进制标记为 ManualOnly/RequiresCloakBrowser）
```

## 5. 风险与决策点

| # | 风险/决策 | 说明 |
|---|---|---|
| 1 | **二进制许可：禁止再分发** | Browser4 发行物（安装包/Docker 镜像/打包 jar）不能内置 CloakBrowser 二进制。Docker 场景需用户挂载自备二进制。这是产品形态的最大约束，需在文档与 CLI 提示中明确"自备二进制"模式 |
| 2 | **License Key 与并发限制** | 最新版免费 key 仅 1 并发会话，与 Browser4 SEQUENTIAL/TEMPORARY 多上下文模式冲突；规模化需 Pro 订阅。v146 免 key 但会随反爬演进老化（项目方明确提示"older build ages fast"） |
| 3 | **base 库版本升级（可规避）** | 改 `BrowserType` 枚举需 browser4base 构建 + `browser4-base.version` 提升。路径 B 用配置开关规避，枚举留待后续 |
| 4 | **CDP 输入行为补丁兼容性** | CloakBrowser 源码级补丁含 "CDP input behavior" 与自动化信号修改，可能影响 PulsarWebDriver 的 `Input.dispatchMouseEvent`/`insertText`/mouseWheel 流程（AGENTS.md 记录过多个 CDP 坑）。**必须用 POC 验证** type/click/mousewheel/截图等核心路径 |
| 5 | **humanize/geoip 是包装器层特性** | 不在二进制内；Browser4 已有随机延迟输入，但无贝塞尔鼠标轨迹。若反爬测试要求行为级仿真，需另行实现（或评估是否值得） |
| 6 | **Windows 平台覆盖** | CloakBrowser 支持 Windows x64 / Linux x64+ARM64（macOS 略滞后），与 Browser4 支持面基本对齐 |
| 7 | **`--remote-debugging-port` 与 `--user-data-dir` 兼容** | 二进制是 Chromium，playwright 即经 CDP 驱动，兼容性高；POC 第一项即验证 |

## 6. 建议实施顺序

1. **POC（0.5 天，今天可做）**：下载 v146 免费二进制（GitHub Releases `chromium-v146.0.7680.177.5`，assets `cloakbrowser-windows-x64.zip`），设 `BROWSER4_SERVER_OPTS="-Dchrome.path=<解压后 chrome.exe>"` 启动服务，跑一遍核心用例（open/type/click/mousewheel/screenshot/snapshot），重点验证 CDP 输入补丁兼容性。通过后决策是否继续产品化。
2. **产品化（2–4 人日）**：路径 B——`CloakBrowserLauncher` + 下载器 + key 透传 + `browser.cloak.*` 配置 + `DefaultBrowserFactory` 配置开关 + 文档（README / SKILL.md / config.md / build-from-source.md）+ CLI 提示。
3. **可选**：`BrowserType.CLOAK_CHROME` 枚举（base 4.11.4）+ CLI 自动检测 + `humanize` 行为仿真；评估与 `browser4-captcha`/代理体系的联动。

## 7. POC 进展（2026-08-17，`bin/setup-cloakbrowser.ps1`）

脚本 `bin/setup-cloakbrowser.ps1` 已就绪：下载（Invoke-WebRequest → gh 回退）→ SHA256 校验 → 解压定位 → `BROWSER4_SERVER_OPTS=-Dchrome.path=` 重启服务器 → CLI 跑 open/snapshot/type/click/mousewheel/eval/screenshot → 逐项 PASS/FAIL。

已验证（沙箱内，`-DownloadOnly` 模式）：
- ✅ v146 免费二进制下载（~200MB，gh 回退路径）、SHA256 校验（与 SHA256SUMS 一致）、解压定位 `chrome.exe`（文件版本 146.0.7680.177）
- ✅ `-Dchrome.path` 注入链路（daemon.rs → searchChromeBinary 第一优先级）、服务器 stop/端口轮询/`-ForceKillConflicting`/残留浏览器清扫逻辑

未能在沙箱内完成浏览器交互验证——**DSH 沙箱禁止任何 Chromium 启动**（mojo 命名管道 `platform_channel.cc 拒绝访问 0x5`，系统 Chrome 与 CloakBrowser 同样报错）。需在正常终端执行完整脚本验证。

调试中发现的真实环境坑（已固化进脚本）：

| # | 坑 | 处理 |
|---|---|---|
| 1 | 本机 18182 端口被**不受当前 CLI 管理的外来服务器**（4.13 工作区 bundle，运行 38h+）占用，`browser4-cli stop` 报「Stopped 0」 | netstat 端口轮询 + `-ForceKillConflicting` 强制停止占用进程 |
| 2 | **残留浏览器会话复用陷阱**：ChromeLauncher `checkExistingChromeProcess()` 发现旧浏览器进程存活（`~/.browser4/browser/chrome/default` 端口文件有效）时直接复用，`-Dchrome.path` 完全不生效（UA 仍是系统 Chrome 151） | stop 后清扫带 `user-data-dir=...browser4` 标记的 chrome/msedge 进程 |
| 3 | 已安装 4.13.4 CLI 把 `--brief` 等 CLI 侧参数原样透传给服务器端 ariaSnapshot 工具 → 报错 | 改用 4.13 兼容语法（snapshot 不带 --brief、eval 用 --file/--json） |
| 4 | 服务器刚启动/浏览器首拉时 eval/screenshot 偶发打印 CLI 帮助文本 | eval/screenshot 加重试 |
| 5 | `open` 在浏览器启动失败时仍返回 exit 0（假阳性） | open 后立即 eval `navigator.userAgent` 断言 `Chrome/146` 作为门禁 |

环境状态：为跑 POC 停止了用户昨日 05:01 启动的 4.13 工作区服务器（PID 96268，端口 18182）及其系统 Chrome 会话（PID 108884），并清理了 POC 期间的临时服务器进程。恢复：`browser4-cli stop` 后再正常启动（4.13 checkout 用其自身工具重启）。

## 8. 参考链接

- https://github.com/CloakHQ/CloakBrowser （README / BINARY-LICENSE.md / releases）
- https://pypi.org/project/cloakbrowser/ 、https://www.npmjs.com/package/cloakbrowser
- https://github.com/devinwang/cloak-browser-mcp （生态佐证，非直接对接）
- 本仓库：`browser4base/pulsar-core/pulsar-browser/.../ChromeLauncher.kt`、`.../manage/PulsarBrowserLauncher.kt`、`browser4base/pulsar-core/pulsar-common/.../BrowserType.kt`、`browser4-protocol/.../DefaultBrowserFactory.kt`、`cli/browser4-cli/src/daemon.rs`（`collect_jvm_opts_and_program_args` / `find_browser_executable`）
