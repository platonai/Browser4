# 评估：从 Chrome / Edge / Safari 导入浏览器数据（书签、历史、密码、Cookie、扩展）

> 评估日期：2026-08-25
> 范围：逐源浏览器（Chrome / Edge / Safari）× 逐数据类型（书签、历史、密码、Cookie、扩展）的导入可行性与落地路径。
> 关联文档：`docs-dev/copilot/browser-state-import-eval.md`（2026-08-18，登录态复制评估）、`skills/browser4-cli/references/browser-state-import.md`（现有实操指南）。

## 1. 结论（TL;DR）

| 数据 | Chrome → Browser4 | Edge → Browser4 | Safari → Browser4 | 首选通道 |
|---|---|---|---|---|
| 书签 | ✅ 容易 | ✅ 容易 | 🟡 中（需解析 plist） | Chrome 原生导入器 / 文件直写 |
| 历史 | ✅ 容易 | ✅ 容易 | 🟡 中（需解析 History.db） | Chrome 原生导入器 / SQLite 直写 |
| 密码 | 🟡 中 | 🟡 中 | 🔴 难（Keychain） | 原生导入 / CSV 中转；Safari 需 `security` CLI |
| Cookie | ✅ 已有通道 | ✅ 已有通道 | 🟡 中（需解析 binarycookies） | CDP（`state-save`/`state-load`）；Safari 需新增解析器 |
| 扩展 | 🟡 中 | 🟡 中（商店不同） | 🔴 不可直接迁移 | profile 目录复制 / 商店重装 / CDP `Extensions.loadUnpacked` |

- **Cookie 是唯一有现成官方通道的数据**：`attach` → `state-save` → `state-load`（CDP `Network.getAllCookies`/`setCookies`），覆盖 Chrome/Edge 运行中的实例；Safari 没有 CDP，需要自己解析 `Cookies.binarycookies`（未加密）再走同一恢复通道。
- **书签/历史/密码最省力的路是让 Chrome 自己导入**：Chrome 的原生导入器（"导入书签和设置"）支持 Edge（Windows/macOS/Linux）和 Safari（仅 macOS），含书签、历史、密码；**但不含 Cookie、不含扩展**，且是 UI 流程，自动化有摩擦。
- **Windows 上最大的坑是 app-bound encryption**：Chrome ≥127（2024）对 Cookie（后扩及密码）改用 app-bound 加密，密钥绑定 Chrome 自身进程身份，"复制 profile 目录就能带走 cookie/密码"的同机捷径在新版 Chrome 上已不可靠——这是 2026 年与旧评估最大的事实差异。
- **Safari 整体最特殊**：无 CDP、无远程调试协议，密码在系统 Keychain，扩展是签名 app 格式（.appex / Web Extension），CRX 完全不能装。Safari 迁移只能靠"解析文件 + Chrome 原生导入 + 手动重装扩展"组合。

## 2. 源浏览器数据事实表

### 2.1 存储位置（默认路径）

| 数据 | Chrome | Edge | Safari（macOS） |
|---|---|---|---|
| Profile 根 | Win: `%LOCALAPPDATA%\Google\Chrome\User Data\Default`<br>macOS: `~/Library/Application Support/Google/Chrome/Default`<br>Linux: `~/.config/google-chrome/Default` | 同 Chrome 布局：`%LOCALAPPDATA%\Microsoft\Edge\User Data\Default` / `~/Library/Application Support/Microsoft Edge/Default` / `~/.config/microsoft-edge/Default` | 无独立 profile 目录，数据分散（见下） |
| 书签 | `Bookmarks`（JSON） | `Bookmarks`（JSON，同构） | `~/Library/Safari/Bookmarks.plist`（+ `.lockfile`） |
| 历史 | `History`（SQLite：urls/visits/…） | `History`（SQLite，同构） | `~/Library/Safari/History.db`（SQLite，Safari 13+；旧版 History.plist） |
| 密码 | `Login Data`（SQLite，`password_value` 加密） | `Login Data`（SQLite，加密方式同 Chromium 体系） | 系统 login Keychain（`~/Library/Keychains/login.keychain-db`，service 如 "Safari AutoFill"）；无独立数据库文件 |
| Cookie | `Network/Cookies`（SQLite，`encrypted_value`） | `Network/Cookies`（同构） | `~/Library/Containers/com.apple.Safari/Data/Library/Cookies/Cookies.binarycookies`（旧版 `~/Library/Cookies/`，**二进制但未加密**） |
| 扩展 | `Extensions/`（按扩展 ID 分目录）+ `Preferences`/`Local State` 中的启用状态 | `Extensions/`（同构，但商店是 Edge Add-ons） | 三类：legacy `.safariextz`（<Safari 12）、App Extension `.appex`、Safari Web Extension（WebExtension API，随宿主 App 分发）；在 `~/Library/Containers/com.apple.Safari/Data/Library/Safari/Extensions` 等目录 |

### 2.2 加密模型（决定"离线复制"是否可行）

| 平台 | Chrome / Edge | 对复制的影响 |
|---|---|---|
| Windows | 早期：AES-256-GCM（"v10"），密钥存 `Local State` 并由 **DPAPI** 保护 → 同机同用户可解。**Chrome ≥127（2024 起）Cookie/密码改用 app-bound encryption**：密钥绑定 Chrome 二进制身份（经 COM 提权服务），普通用户态进程无法独立解密，需要绕过工具或让 Chrome 自己读 | 🔴 新版 Chrome/Edge 的 cookie/密码**离线复制到另一 profile 可能解密失败**；需按实际版本实测 |
| macOS | Chrome/Edge：密钥存 Keychain（"Chrome Safe Storage"/"Microsoft Edge Safe Storage"），cookie/密码均加密 | 🟡 同机同用户可解（Keychain 会话解锁后） |
| Linux | 密码密钥存 kwallet / gnome-keyring（libsecret）；cookie 历史上多为明文 | 🟡 同机可解；部分发行版新版本开始加密 cookie |
| Safari | Cookie 二进制未加密；密码在 Keychain（需用户解锁/授权）；书签/历史明文 | 🟡 Cookie 好办；密码必须走 Keychain |

**推论**：跨浏览器复制 profile 文件（Chrome→Edge 或反之）在 app-bound 时代基本不可行（各自密钥体系 + 版本差异）；Chrome→Chrome 同机同版本复制 cookie/密码也需验证 app-bound 是否允许（建议实测，预期受限）。

## 3. 导入通道盘点（Browser4 视角）

Browser4 的目标侧始终是**受管 Chromium profile**（`~/.browser4/browser/...`，由 CDP 驱动），所以所有"导入"最终都要落到：CDP 运行时状态写入、受管 profile 文件写入、或让 Chrome 自己执行导入。

| 通道 | 机制 | 覆盖数据 | 现状 |
|---|---|---|---|
| A. CDP 运行时 | `Network.setCookies`（恢复）、`Storage.setLocalStorage`、CDP `Extensions.loadUnpacked`（Chrome ≥126 可用） | Cookie（+ localStorage）；扩展（unpacked 加载） | Cookie 已有 `state-save`/`state-load` 官方通道；扩展域未接入 |
| B. Chrome 原生导入器 | `chrome://settings/importData`（"导入书签和设置"），支持 Edge/Firefox + macOS 的 Safari；也支持 HTML 书签 / CSV 密码文件 | 书签、历史、密码（**无 cookie、无扩展**） | 未自动化；需 UI 驱动或人工 |
| C. 文件直写 | 直接写目标 profile 的 `Bookmarks` JSON / `History` SQLite / `Login Data` SQLite（需实现对应加密写入）/ `Secure Preferences` | 书签、历史、密码（难度递增） | 无；书签/历史格式简单，密码需实现 Chrome 加密格式 |
| D. Profile 目录复制 | 整目录复制 → `open --profile` 或 PROTOTYPE 模式挂载 | 几乎全部（含 IndexedDB、扩展） | 已有手工流程（见 browser-state-import.md）；受 2.2 加密模型限制 |
| E. 商店/CRX 重装 | 按扩展 ID 从 Web Store / Edge Add-ons 重装；CRX 侧载（`Extensions.loadUnpacked` 或 `--load-extension`） | 扩展 | 无命令支持 |

## 4. 逐数据类型评估

### 4.1 书签 —— 推荐：通道 B（原生导入），备选 C（JSON 直写）
- **Chrome/Edge 源**：源 `Bookmarks` 就是 JSON（嵌套 `children` 结构），直写目标 `Bookmarks` 文件零加密成本；但 Chrome 内存中保有书签，热写会被覆盖——必须在目标浏览器**关闭时写**或导入后重启。原生导入器（B）无此问题，且对 Edge/Safari 源都是官方支持的格式转换。
- **Safari 源**：`Bookmarks.plist` 是二进制/XML plist，需解析（结构：`Children` 递归 + `WebBookmarkType`/`WebBookmarkTitle`/`URLString` 字段）。可解析后转成 Chrome JSON 直写，或转 Netscape HTML 文件再走 Chrome 文件导入。
- 工作量：低（JSON/plist 解析 + 文件写入；注意目标浏览器关闭时序）。

### 4.2 历史 —— 推荐：通道 B（原生导入），备选 C（SQLite 直写）
- Chrome/Edge 的 `History` 是标准 SQLite（`urls`、`visits`、`visit_source` 等表），直读直写都简单；Safari 的 `History.db` 也是 SQLite（`history_items`/`history_visits`/`history_events`），表结构不同需映射。
- 直写约束同书签：目标浏览器必须关闭（SQLite 锁 + 内存覆盖）。Chrome 原生导入器对 Edge/Safari 都支持历史。
- 工作量：低～中（SQLite 写入 + 目标关闭时序；Safari 需表映射）。

### 4.3 密码 —— 推荐：通道 B（原生导入）/ CSV 中转；Safari 走 Keychain
- **Chrome/Edge 源**：`Login Data` 的 `password_value` 是加密 BLOB（Windows app-bound/DPAPI；macOS Keychain；Linux keyring）。直写目标 `Login Data` 必须**用目标 Chrome 自己的加密体系重新加密**（实现 AES-GCM v10 + 读 `Local State` 密钥，或调用系统 keyring），工作量和维护成本最高。
- **最实用路径**：CSV 中转——源侧解密（同机同用户可做）导出 `url,username,password` CSV，目标侧走 Chrome 密码导入（支持 CSV，UI 操作或将来自动化）。Chrome 原生导入器对 Edge（密码）和 Safari（macOS，经 Keychain）都支持，可免去自己解密。
- **Safari 源**：密码在 login Keychain，可用 `security find-internet-password` 系列命令枚举导出（会触发用户授权/解锁），无独立数据库文件。
- 工作量：中（CSV 中转 + 源侧解密逻辑）；**不建议**直写 Login Data。

### 4.4 Cookie —— 推荐：通道 A（CDP，已有）；Safari 需新增解析器
- **Chrome/Edge 源（运行中）**：`attach --extension/--cdp` → `state-save` 导出全部 cookie（`Network.getAllCookies`）→ `open --fresh` → `state-load`（`Network.setCookies`）。**官方通道，已文档化**，与源浏览器加密完全无关（浏览器自己解密），是最优解。
- **Chrome/Edge 源（离线）**：从 `Network/Cookies` 直读需处理 app-bound/DPAPI，且 cookie 有 `creation_utc`/`last_access_utc` 等列需要填，不如 attach 通道。
- **Safari 源**：`Cookies.binarycookies` 是公开的二进制格式（未加密，已有现成解析库如 macCookies / BinaryCookieReader / Python kooky），解析出 (domain, path, name, value, expires, secure, httponly) 后转成 `state-load` 的 JSON 格式即可复用现有恢复通道。Safari 17+ 有 cookie 存储调整的报告，需按目标 macOS 版本实测路径。
- 工作量：低（Chrome/Edge 零开发）；中（Safari 解析器 + 版本兼容）。

### 4.5 扩展 —— 推荐：通道 E（重装/CRX 加载）+ D（Chrome→Chrome 复制）
- **Chrome → Chrome**：复制 `Extensions/` 目录 + `Preferences`/`Secure Preferences` 中的扩展状态条目，同版本族可靠；也可从 Web Store 按 ID 重装（需要网络与商店可达）。
- **Chrome ↔ Edge**：CRX 格式同源，`Extensions/` 目录大体可复制，但依赖商店来源与"允许来自其他商店的扩展"开关；更稳的是各自商店重装。
- **自动化落点**：Chrome ≥126 提供 CDP `Extensions.loadUnpacked`（替代废弃的 `--load-extension`），可把 unpacked 扩展目录加载进受管会话；Browser4 尚未接入该 CDP 域，需要新增。
- **Safari**：格式完全不兼容（签名 `.appex` / Web Extension 随 App 分发，CRX 不可装）。WebExtension API 类扩展可人工在 Safari 商店/开发者处重装；这是唯一无法自动化的数据类型。
- 工作量：中（Chrome/Edge）；高/不可行（Safari）。

## 5. 逐源浏览器评估

### Chrome → Browser4（最容易）
同源同构。书签/历史/密码可走原生导入器或文件直写；cookie 走 attach+state-save（已有）；扩展可 profile 复制或商店重装。唯一注意：**app-bound 加密后，Windows 上的离线密码/cookie 提取与复制路径受限**，优先走运行时通道。

### Edge → Browser4（次之）
数据结构与 Chrome 完全同构（Chromium 同源），书签/历史文件格式一致。推荐：
1. cookie：attach Edge + state-save（已有通道，Edge 也支持扩展中继/CDP）。
2. 书签/历史/密码：Chrome 原生导入器直接支持 Edge（三平台）。
3. 扩展：Edge Add-ons 与 Web Store 不同源，按 ID 重装或"允许其他商店扩展"后复制。
4. 不推荐跨浏览器复制整个 profile（密钥体系 + app-bound + 版本差异）。

### Safari → Browser4（最难，需新增解析层）
无 CDP/远程调试；每类数据一种格式，且密码在 Keychain：
1. 书签/历史：解析 `Bookmarks.plist` / `History.db` → 转 Chrome JSON/SQLite 直写，或转 Netscape HTML 走 Chrome 文件导入。
2. 密码：`security find-internet-password` 导出（用户授权）→ CSV → Chrome CSV 导入；或直接用 Chrome 原生导入器（macOS 支持从 Safari 导密码）。
3. Cookie：解析 `Cookies.binarycookies`（未加密）→ `state-load` JSON。
4. 扩展：人工重装，无法自动化。
macOS 上其实有一条"免费"路径：**Chrome 原生导入器在 macOS 上同时覆盖 Safari 的书签/历史/密码**，先跑它，只剩 cookie 需要自研解析。

## 6. 风险与边界清单

1. **app-bound encryption（Windows，Chrome ≥127）**：2026 年离线复制 cookie/密码的最大变数。Chrome 的 cookie（后扩及密码）由绑定 Chrome 身份的加密服务保护；同机同用户的"复制 profile"捷径不再保证可用。所有依赖"直读 Cookies/Login Data 文件"的方案都需按目标版本实测；社区已有绕过研究（如 viachq/Chrome-App-Bound-Encryption-Decryption、CyberArk C4 分析），但作为产品功能不应依赖绕过手段。**结论：Windows 上 cookie/密码一律优先走运行时通道（attach/CDP），不做文件级提取。**
2. **锁文件**：profile 目录复制必须排除 `SingletonLock/SingletonSocket/SingletonCookie`，且源浏览器需完全关闭。
3. **DPAPI/Keychain 同机同用户限制**：跨机器/跨用户复制必然失败。
4. **版本差异**：源/目标 Chromium 版本差距过大时 profile 会被拒（尤其 Login Data/Cookies 的 schema 与加密版本）。
5. **Chrome 原生导入器是 UI 流程**：无公共命令行/API（`--import-from-*` 类开关属内部迁移流程，行为不稳定，不建议依赖）；自动化需驱动 `chrome://settings/importData` WebUI，脆但可行。
6. **书签/历史直写要求目标浏览器关闭**：Chrome 内存态会覆盖磁盘文件，热写无效。
7. **Safari 版本漂移**：cookie 路径与格式（Safari 15 容器化、17+ 的调整报告）、History.db schema 随版本变化，需测试矩阵。
8. **安全合规**：导入涉及明文密码/会话令牌，产物（JSON/CSV）需等同密钥对待（gitignore、用后删除）。

## 7. 产品化建议（如要做 `profile-import`）

建议按"数据难度"排序分阶段实现，全部落在一个命令下：

```
browser4-cli profile-import --source chrome|edge|safari --data bookmarks,history,passwords,cookies,extensions
```

| 阶段 | 数据 | 实现要点 | 风险 |
|---|---|---|---|
| P1（低风险，已有基础） | cookies | Chrome/Edge：attach+state-save/load 封装成一条命令；Safari：binarycookies 解析器 → 同一恢复 JSON | 低 |
| P2（中） | bookmarks / history | Chrome/Edge：源文件解析 → 目标文件直写（要求目标浏览器关闭，或写入后重启）；Safari：plist / History.db 解析 + 表映射；或统一转 Netscape HTML/CSV 走 Chrome 文件导入 | 中（关闭时序、表映射） |
| P3（中高） | passwords | 源侧解密导出 CSV（同机同用户）→ 目标 CSV 导入；Safari 走 `security` CLI；**Windows 需评估 app-bound 影响，必要时只支持"Chrome 原生导入器路径"** | 高（加密体系） |
| P4（高/受限） | extensions | Chrome/Edge：按 ID 从商店重装，或 `Extensions.loadUnpacked` 加载 unpacked 源；Safari：仅人工指引 | 中高（商店策略） |

测试矩阵：Windows / macOS / Linux × Chrome / Edge / Safari × 各数据类型 × 新旧版本（重点覆盖 Chrome ≥127 app-bound、Safari 15/17/18）。建议先做一轮真实环境验证（尤其：app-bound 下 attach+state-save 是否完整拿到 cookie；Safari 各版本 cookie 路径），再定 P2 细节。

## 8. 参考实现：agent-browser 的迁移能力盘点（2026-08-25 实地核查）

`D:\codebase\browser-automation\agent-browser`（Rust CLI + Chrome CDP）的迁移能力，可作为 Browser4 产品化的对照：

| 能力 | 现状 | 证据 |
|---|---|---|
| **Chrome 系整份 profile 迁移**：`--profile <name>` | ✅ 自动发现（读 `Local State` 的 `profile.info_cache`）→ 整目录复制到临时目录（只排除 Cache/Code Cache/GPUCache/Service Worker/blob_storage/File System/GCM Store/optimization_guide/ShaderCache/component_crx_cache）→ 作为 `--user-data-dir` 启动；**书签/历史/密码/Cookie/扩展/IndexedDB 全部随复制带走**；原 profile 只读快照，用后删除 | `cli/src/native/cdp/chrome.rs`：`list_chrome_profiles` / `resolve_chrome_profile` / `copy_chrome_profile`（changelog #1131） |
| 支持源浏览器 | Chrome / Chrome Canary / Chromium / Brave；**无 Edge、无 Safari** | `get_chrome_user_data_dirs()`（三平台硬编码列表） |
| **登录态导入**：`--auto-connect state save` → `--state <file>` / `state load` | ✅ 连接运行中的 Chrome（`--remote-debugging-port`）导出 cookies + **跨 origin localStorage**（记录访问过的 origin，用临时 target 逐个收集）；AES-256-GCM 可选加密、过期清理、restore 校验 | `cli/src/native/state.rs`（temp-target 收集多 origin）、`actions.rs::handle_state_save` |
| **Cookie 文件导入**：`cookies set --curl <file>` | ✅ 自动识别 JSON / cURL dump / Cookie header 三种格式，可 `--domain`/`--url` 限定 | `commands.rs`（changelog #1257） |
| 扩展 | 🟡 仅启动时 `--extension <path>` 加载 unpacked（`--load-extension`）；不能从源浏览器复制已装扩展 | `cdp/chrome.rs` build args |
| 书签/历史/密码逐项导入 | ❌ 无专项命令，只能随整份 profile 复制 | 全库无对应逻辑 |
| Safari | ❌ 无任何导入；仅 WebDriver（safaridriver）驱动 Safari 本身做自动化 | `cli/src/native/webdriver/safari.rs` |

**对 Browser4 的启示**：

1. agent-browser 把"复制整个 profile 目录"这条手工路完整产品化了（发现→复制→排除缓存→清理→只读快照），证实了该方案在 Chrome 系可行且值得做成命令（对应我们的 D 通道 + P2/P3 阶段）。
2. 它的整份复制同样不做文件级解密——让 Chrome 用自己二进制启动复制出的 profile，天然绕开 DPAPI/app-bound 的离线解密问题（与本评估"运行时优先"的结论一致）。但注意：**app-bound 是否允许"复制出的 profile 目录"被 Chrome 解密，agent-browser 未做显式处理，Windows 新版 Chrome 上需实测**；其文档也要求 Windows 上先完全关闭源 Chrome。
3. 它的登录态导入比 Browser4 `state-save` 更强：跨 origin localStorage（Browser4 目前只导当前活动页 origin，见 browser-state-import.md）。Browser4 若做 `profile-import`，建议参考 state.rs 的多 origin 收集方式。
4. 空白地带相同：Edge/Safari 源、逐项选择导入、扩展迁移，三边都没做——仍是差异化机会。

> 落地设计见 `docs-dev/copilot/profile-import-design.md`（2026-08-25）。

## 9. 参考

- 仓库现状：`BrowserProfileGenerator.kt`（PROTOTYPE/SEQUENTIAL/TEMPORARY 继承）、`PrivacyContext.kt`（原型目录）、`PulsarSettings.kt`（SYSTEM_DEFAULT 已废弃，issue #162）、`skills/browser4-cli/references/browser-state-import.md`、`attach.md`、`storage-state.md`
- Chrome 导入书签和设置（支持 Edge/Firefox/Safari(macOS)、HTML/CSV）：https://support.google.com/chrome/answer/96816
- Chrome app-bound 加密研究：https://github.com/viachq/Chrome-App-Bound-Encryption-Decryption 、https://www.cyberark.com/resources/threat-research-blog/c4-bomb-blowing-up-chromes-appbound-cookie-encryption
- CDP `Extensions.loadUnpacked`（Chrome ≥126 替代 `--load-extension`）：https://github.com/mozilla/web-ext/issues/3388
- Safari 取证存储（History.db、Cookies.binarycookies）：https://www.foxtonforensics.com/blog/post/analysing-safari-browser-history 、https://github.com/kawakatz/macCookies
- Edge 目录/策略（UserDataDir）：https://learn.microsoft.com/deployedge/microsoft-edge-policies/userdatadir
- Safari 无法安装 Chrome 扩展（CRX 不兼容）：https://www.php.cn/faq/1575373.html
