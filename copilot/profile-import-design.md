# profile-import 落地设计：从 Chrome / Edge / Safari 导入浏览器个人数据

> 设计日期：2026-08-25
> **实现状态（2026-08-25）**：M0-M3 已落地——
> - `open --profile` 后端管线已修复（`PulsarSettings.profilePath` + `AbstractPulsarSession.createBoundDriver` 自定义 BrowserId，含单元测试）；
> - `browser4-plugins/browser4-profile-import` 插件已实现：`profile_import.list_sources` / `profile_import.import`（Chrome/Edge 整份复制 + data 裁剪 + 密码默认拒绝；Safari 书签 plist → Chrome JSON、binarycookies → cookies JSON），**支持 `--into temp|prototype|default` 落点**，30 个单元测试全绿，全链路 Maven 构建通过；
> - CLI `browser4-cli profile-import` 命令已实现（`--list-sources` / `--source` / `--profile` / `--data` / `--into`，无 session 直连后端 MCP 工具），4 个 commands.rs 单测 + 1 个 mock e2e 场景（`test_e2e_mock_profile_import_command`）全绿；Rust CLI 1179 单测无回归；
> - ModuleMap 快照已同步；修复了 DevTaskPlannerTest 中引用不存在模块（browser4-headings）的预存失败。
> 未做：Safari History.db/Keychain 转换（M4 实机矩阵）、扩展跨浏览器迁移、e2e 实机场景。
> **CLI 插件化评估（2026-08-25）**：CLI 支持"不改代码、导入插件即发现"——`browser4-cli plugin <domain> [method]`（spaced 规范形式，与 swarm/agent/profiles 同风格）经 `rewrite_prefixed_command` 通用分支（`"plugin" => "plugin-<sub>"`，main.rs:18353）重写为 `plugin-<domain>`，走 `handle_dynamic_plugin_command` 从 `/mcp/tools` 动态发现并调用（已 e2e 实证 `plugin profile_import import --source chrome`；2 个 rewrite 单测 + help.rs 概览已同步为 spaced 说明）。硬编码 `CommandDef`（如 `profile-import`）仍提供参数校验/帮助/美化输出的"一等命令"体验。
>
> **插件声明 CLI 命令（2026-08-25 已实现）**：`ToolSpec.cliName`（spaced 形式，如 `"profile import"`）允许插件**不改 CLI 代码**声明一等命令——后端新增 `GET /mcp/tools/specs` 汇总带 cliName 的 spec；CLI 启动时（首词非内置命令才探测）拉取并在 normalize 前匹配 spaced 名，`handle_declared_cli_command` 按 spec.arguments 做 `--key value` 解析后调用（`profile_import` 插件已声明 `profile import` / `profile sources`）。验证：2 个 serde/匹配单测 + e2e 场景第 7 步 `profile import --source chrome --data cookies` 全绿；AGENTS.md spaced 规则第 6 条已固化。
>
> **命令来源辨识（2026-08-25）**：`plugin commands` 列出全部插件声明命令（标注来源域）；`help` / `help <prefix>` 中插件声明命令带 `[plugin]` 徽标（`render_declared_help_section`，help 路径拉 specs，失败静默），原生命令无徽标——来源一目了然。`plugin <domain>` 仍可列任意域工具。
> 前置评估：`docs-dev/copilot/browser-data-import-eval.md`（§8 参考实现 agent-browser 已实地核查）
> 目标：把"导入书签、历史、密码、Cookie、扩展"做成 Browser4 的一等命令，覆盖 Chrome / Edge / Safari 三源。

## 1. 设计目标与范围

### 1.1 目标

```
browser4-cli profile-import --source chrome|edge|safari [--profile <name|dir>] \
    [--data bookmarks,history,passwords,cookies,extensions] [--into prototype|default|temp]
```

一次命令完成：发现源浏览器 profile → 按需复制/解析 → 转换进 Browser4 受管 profile（原型目录或自定义目录）→ 可直接 `open` 使用。

### 1.2 范围决策（对照评估 §8 差距点）

| 差距点 | 本设计 |
|---|---|
| Edge/Safari 源不支持 | ✅ 三源都做；Chrome/Edge 走"整份复制 + 文件直写"，Safari 走"解析层 + 转换" |
| 逐项选择导入 | ✅ `--data` 白名单；全量复制默认开启但可裁剪 |
| 扩展迁移 | 🟡 Chrome→Chrome 复制；Chrome↔Edge 商店重装指引；Safari 仅人工指引（不可自动化） |
| 登录态跨 origin | ✅ 顺带修复：`state-save` 升级为跨 origin（对齐 agent-browser state.rs 做法，见 §7.3） |

### 1.3 非目标（明确不做）

- 不做文件级解密绕过（app-bound/DPAPI/Keychain 的离线破解）。Windows 新版本 Chrome 的密码/cookie 一律走"运行时通道或整份复制让 Chrome 自己解密"，与评估结论一致。
- 不做 `SYSTEM_DEFAULT` 复活（Chrome ≥143 已废弃）。
- 不做浏览器间 UI 自动化导入（chrome://settings/importData 驱动），避免脆弱的 WebUI 自动化。

## 2. 命令面设计

### 2.1 CLI（`cli/browser4-cli/src/commands.rs` 新增 `CommandDef`）

```
profile-import
  --source <chrome|edge|safari>        必填；源浏览器
  --profile <name|dir>                 源 profile：Chrome/Edge 支持显示名或目录名（同 agent-browser 三级匹配），
                                       默认 "Default"；Safari 忽略
  --data <bookmarks,history,passwords,cookies,extensions>  默认全量
  --into <prototype|default|temp>      导入落点：prototype=种子原型目录（默认，SEQUENTIAL/TEMPORARY 自动继承）；
                                       default=直接作为 DEFAULT 模式 profile；temp=临时目录
  --dry-run                            只做发现与清单，不复制
  --list-sources                       列出本机可导入的浏览器与 profile（等价 profiles-list 的扩展）
```

- `batch_supported: false`、`no_snapshot_commands()` 内登记（导入命令不改页面状态）。
- 输出：导入摘要（各数据类型条数、落点路径、跳过项与原因），`--json` 支持。

### 2.2 后端 MCP 工具

| 工具 | 别名 | 内部名 | 说明 |
|---|---|---|---|
| `browser_profile_import` | MCPToolController `FRONTEND_TOOL_NAME_ALIASES` | `profile_import` | 后端执行导入（复制/解析/转换），返回 `{importDir, summary}` |
| `browser_profile_list` | 同上 | `profile_list` | 后端发现本机源浏览器 profile（三源统一） |

（REST 路径 `POST /api/profile/import`、`GET /api/profile/sources` 按需提供，对齐 swarm/crawl 的 REST 模式；若 CLI 侧实现为主，则仅 MCP 别名 + REST 为可选项，见 §3 架构决策。）

## 3. 总体架构与模块落点

### 3.1 架构决策：核心逻辑放后端（Kotlin），CLI 只做参数与展示

理由：
1. 复制/解析产物（含密码、cookie）不应走 MCP/HTTP 文本通道两次；后端直接写 `~/.browser4/imports/`，CLI 只拿路径。
2. 后端已有 SQLite/JSON 依赖（pulsar 体系），Java 生态有现成 plist/binarycookies 解析库可引入（`com.googlecode.plist`、自研 binarycookies 解析 ~200 行）；Rust 侧反而要新引入 rusqlite + plist crate。
3. 导入结果需要与 session/launch 联动（§7 的 profilePath 管线修复），后端改一处即可。
4. `profiles-list` 是 CLI 侧纯文件扫描的先例，但那只读 `~/.browser4` 自家目录；导入要跨平台发现系统浏览器目录 + 解析多种格式，复杂度更高，放后端更合适。

### 3.2 新增/修改文件清单

**新增（browser4-tools 模块，符合"操作工具与启动辅助"定位）**

```
browser4-tools/src/main/kotlin/ai/platon/pulsar/tools/profile/
  BrowserProfileImporter.kt        # 门面：编排发现→复制→转换→落点
  SourceBrowserDetector.kt         # 三平台发现 Chrome/Edge/Safari 数据目录（镜像 agent-browser get_chrome_user_data_dirs，+Edge/Safari）
  ChromeProfileReader.kt           # 读 Local State profile.info_cache；三级匹配解析 profile 名（端口自 agent-browser chrome.rs）
  ProfileCopier.kt                 # 递归复制 + 排除表 + 锁文件跳过 + Windows 运行中源检测（SingletonLock 存在→报错提示关浏览器）
  converters/BookmarkConverter.kt  # Chrome JSON ↔ Safari plist → 目标 Bookmarks JSON / Netscape HTML
  converters/HistoryConverter.kt   # Chrome/Edge History SQLite 直读 → 目标 History SQLite 写入（目标浏览器关闭时）
  converters/PasswordConverter.kt  # 源 Login Data 解密导出 CSV（同机同用户；Windows app-bound 检测→报错降级）
  converters/CookieConverter.kt    # Safari Cookies.binarycookies 解析 → state-load JSON（Chrome/Edge 走现有 CDP 通道）
  SafariKeychain.kt                # security find-internet-password 枚举导出（macOS）
```

**修改（管线修复，见 §7）**

| 文件 | 改动 |
|---|---|
| `browser4-core/browser4-skeleton/.../PulsarSettings.kt` | `parse()` 增加 `profilePath` 字段并传入 overrideConfiguration |
| `browser4-core/browser4-skeleton/.../session/AbstractPulsarSession.kt` | `createBoundDriver()`：sessionConfig 有 profilePath 时用 `BrowserId(BrowserProfile(path))` 构造自定义上下文启动（对齐 `BrowserPrivacyContext.kt:61` 的构造方式），替代默认 BrowserId |
| `browser4-rest/.../MCPToolController.kt` | 两个前端别名 + dispatch |
| `browser4-rest/.../PulsarSessionManager.kt` | `createManagedSession` 透传 profilePath 进 capabilities（已透传，无需改）；session 信息接口暴露 importDir |
| `browser4-core/browser4-common/.../B4Constants.kt` | `PROFILE_PATH_CAPABILITY = "profilePath"`、`BROWSER_IMPORT_DIR = "browser.import.dir"` 等常量 |
| `cli/browser4-cli/src/commands.rs` | `CommandDef` + 参数映射 |
| `cli/browser4-cli/src/main.rs` | dispatch、`no_snapshot_commands()` 登记、`--list-sources` 本地快速路径 |
| `cli/browser4-cli/src/help.rs` / `tips.rs` | 帮助与提示 |

### 3.3 数据目录布局

```
~/.browser4/imports/
  chrome-Default-20260825/          # 每次导入一个快照目录
    profile/                        # 整份复制（Chrome/Edge）或转换产物（Safari）
    meta.json                       # {source, profile, data, counts, warnings, createdAt}
  prototype/                        # --into prototype 时：复制到 ~/.browser4/browser/chrome/prototype/google-chrome/
```

- 快照目录用后即删（临时挂载）或保留（`--into default` 长期复用），密码类文件权限收紧（Unix 700 / Windows icacls，对齐 agent-browser security 文档做法）。

## 4. 核心流程

```
profile-import --source chrome --profile "Work" --data bookmarks,history,cookies
  ├─ 1. SourceBrowserDetector 定位 User Data 目录（Windows/macOS/Linux × chrome|edge 共 6 组路径）
  ├─ 2. ChromeProfileReader 读 Local State → 解析 "Work" → Default/Profile N（三级匹配，歧义报错）
  ├─ 3. 运行中检测：SingletonLock 存在 → 报错"请先完全关闭源浏览器"（Windows 必查；macOS/Linux 同）
  ├─ 4. ProfileCopier 复制（排除表 + 锁文件跳过）→ ~/.browser4/imports/<snapshot>/profile/
  ├─ 5. 按 --data 裁剪：只保留所需文件/执行转换（书签 JSON 直用；History 表裁剪；Login Data 跳过或 CSV）
  ├─ 6. 写入 meta.json；--into prototype → 复制进原型目录
  └─ 7. 输出摘要 + 落点路径 → 用户 `open --profile <path>` / `open --profile-mode prototype`
```

## 5. 逐数据管道

| 数据 | Chrome 源 | Edge 源 | Safari 源 | 目标写入 | 关键约束 |
|---|---|---|---|---|---|
| 书签 | 直用 `Bookmarks` JSON | 同左（同构） | `Bookmarks.plist` 解析 → Chrome JSON | 目标 profile `Bookmarks` | 目标浏览器关闭时写；Safari plist 结构（`Children` 递归 + `WebBookmarkType`）映射 |
| 历史 | `History` SQLite 直读 | 同左 | `History.db` 表映射（history_items/visits/events） | 目标 `History` SQLite（`urls`/`visits`/`visit_source`） | 目标浏览器关闭；时间列 UTC 微秒格式 |
| 密码 | `Login Data` 解密→CSV（DPAPI/Keychain；**app-bound 检测**） | 同左（DPAPI） | Keychain `security` CLI → CSV | Chrome CSV 导入指引 or 目标 Login Data 加密写入（P3 不做，指引用户） | Windows app-bound 存在时拒绝离线导出并提示走"整份复制"或 attach |
| Cookie | attach + `state-save`（已有） | 同左 | `Cookies.binarycookies` 解析（未加密）→ state JSON | `state-load` 通道（已有） | Safari 解析器新增；路径随 macOS 版本变化需探测 |
| 扩展 | `Extensions/` + Preferences 复制 | 复制 + 商店重装指引 | 不可迁移 | 目标 `Extensions/` | Chrome→Chrome 可靠；跨浏览器需"允许其他商店扩展"开关 |

## 6. 前置依赖修复：profilePath 能力管线（现状缺口）

**已核实的问题**：CLI `open --profile <path>` 把 `profilePath` 放进 open_session capabilities（`main.rs build_open_session_capabilities`），但后端 `PulsarSettings.parse()`（PulsarSettings.kt:79-96）只解析 `profileMode/headed/...`，**`profilePath` 被静默丢弃**，浏览器启动时不会挂载该目录。参考文档 browser-state-import.md 声称 `open --profile` 可用，需先实测确认（可能从未生效或依赖外部库的隐式读取）。

**修复方案**：
1. `PulsarSettings` 增加 `profilePath: String?`，`parse()` 读取，`overrideConfigurationInternal()` 写入系统属性 `browser.profile.path`（新常量）。
2. `AbstractPulsarSession.createBoundDriver()`（AbstractPulsarSession.kt:229）：`sessionConfig["browser.profile.path"]` 非空时，构造 `BrowserId(BrowserProfile(path), fingerprint)` 调 `browserManager.launch(browserId, BrowserSettings(sessionConfig))`——构造方式对齐 `BrowserPrivacyContext.kt:61`，不触碰外部 pulsar 库。
3. 单元测试：`PulsarSettingsTest` 解析 profilePath；`AbstractPulsarSessionTest` 验证自定义 BrowserId 路径（mock browserManager）。

**验证方法**：`open --profile <dir>` 后 `snapshot grep "user-data-dir"`（沿用 attach 工作流的回归检查手法）确认命令行携带目标路径。

## 7. 配置项

| 键 | 默认 | 说明 |
|---|---|---|
| `browser.import.dir` | `~/.browser4/imports/` | 导入快照根目录 |
| `browser.import.exclude` | 缓存类目录表（对齐 agent-browser `PROFILE_COPY_EXCLUDE_DIRS`） | 复制排除项，可追加 |
| `browser.import.allow-passwords` | `false` | 允许导出/导入密码（默认关，安全默认） |
| `browser.profile.path` | — | 会话级自定义 profile 路径（§6 新增，也可手工设置） |

## 8. 测试计划（对齐仓库测试规范）

1. **单元（Rust）**：`commands.rs` 参数解析（--source/--data/--into 校验、非法组合报错）、`--list-sources` 输出。
2. **单元（Kotlin）**：`ChromeProfileReaderTest`（Local State 缺失/畸形/多 profile 歧义，端口自 agent-browser 测试集）、`ProfileCopierTest`（排除表、锁文件跳过、源缺失）、`BookmarkConverterTest`（Chrome JSON ↔ plist 样例）、`HistoryConverterTest`（SQLite 表映射）、`CookieConverterTest`（binarycookies 样例解析）、`SafariKeychainTest`（mock security CLI 输出）。
3. **REST IT**（`browser4-rest-tests`）：`MCPToolController` 别名映射 → `browser_profile_import` 返回 importDir；`PulsarSessionManagerTest` profilePath 管线。
4. **E2E（真实浏览器）**：fixture 构造最小 Chrome profile（Bookmarks/History/Cookies SQLite）→ `profile-import --dry-run` → `open --profile` → 快照断言书签/历史/cookie 生效（`requires_browser4: true`，`--scenario=test_e2e_profile_import_*`）；Windows 上先跑 app-bound 验证用例。
5. **Safari**：`ManualOnly` 标记的 macOS 实机用例（plist/History.db/binarycookies/Keychain 版本矩阵：Safari 15/17/18）。

## 9. 文档更新清单（按仓库 Documentation Update Rule）

- `README.md` / `README.zh.md`：新命令小节
- `skills/browser4-cli/SKILL.md`：命令速查
- `skills/browser4-cli/references/browser-state-import.md`：升级为"导入个人数据"总指南（保留 state 章节，新增 profile-import 章节）
- `skills/browser4-cli/references/storage-state.md`：跨 origin 变更说明
- `cli/browser4-cli/README.md`、`src/help.rs`、`src/tips.rs`
- `docs/config.md`：新配置项
- `docs-dev/copilot/browser-data-import-eval.md`：标记设计落点

## 10. 里程碑与工作量（对照评估 §7 分阶段）

| 里程碑 | 内容 | 预估 | 风险 |
|---|---|---|---|
| M0 验证 | ① `open --profile` 后端管线实测（§6）② Windows Chrome ≥127 app-bound 下"整份复制→同二进制启动"是否可解密 cookie ③ Safari 各版本 cookie 路径 | 1-2 天 | 高（决定 P2 方案取舍） |
| M1 | §6 管线修复 + `profilePath` 全链路；`profile-import --source chrome --into temp --dry-run`（发现/清单） | 2-3 天 | 低 |
| M2 | 整份复制（书签/历史/cookie/扩展随复制）+ `--data` 裁剪 + `--into prototype/default` | 3-5 天 | 中（排除表、锁检测、Windows 关闭检测） |
| M3 | Edge 源（路径表 + 同构复用） | 1-2 天 | 低 |
| M4 | Safari 解析层（plist/History.db/binarycookies/Keychain→CSV）+ macOS 实机矩阵 | 5-8 天 | 高（版本漂移） |
| M5 | 文档 + e2e 场景 + 安全收尾（权限、清理、meta.json） | 2-3 天 | 低 |

## 11. 风险与开放问题

1. **`open --profile` 现状待实测**：后端可能从未真正消费 profilePath（§6），若确认是 bug，M1 顺带修复（收益：`--profile` 和 `profile-import` 一起落地）。
2. **app-bound（Windows Chrome ≥127）**：整份复制后由 Chrome 自身启动解密，理论可行但未验证；M0 必须有结论。若失败：cookie 降级走 attach+state-save 通道，密码仅 CSV 指引。
3. **Safari 版本矩阵**：cookie 路径（Safari 15 容器化、17+ 调整报告）、History.db schema 漂移；M4 用探测式路径查找 + 宽容解析。
4. **安全**：密码默认不导出（`browser.import.allow-passwords=false`）；快照目录权限收紧；meta.json 不落明文凭据。
5. **扩展跨浏览器**：Chrome↔Edge 的 Preferences 状态条目与商店策略差异，M2 只保证 Chrome→Chrome，跨浏览器给指引不保证。

## 12. 结论

- 复刻 agent-browser 已验证的"整份 profile 复制"路径（M1-M2 即达其 `--profile <name>` 同等能力），
- 用差异化补齐其空白：Edge/Safari 源（M3-M4）、逐项 `--data` 裁剪、`--into` 落点管理；
- 密码/cookie 严守"运行时优先、不做离线破解"的安全边界；
- 先做 M0 三个验证再开工，避免在 app-bound/`open --profile` 两个未证实前提上投入。
