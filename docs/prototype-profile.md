---
title: "PROTOTYPE Profile 机制"
description: "Browser4 PROTOTYPE profile 的完整生命周期：新用户首次启动时 Chrome 自动创建、SEQUENTIAL/TEMPORARY 如何从 PROTOTYPE 继承、如何主动丰富 PROTOTYPE 注入扩展与登录状态。"
---

# PROTOTYPE Profile 机制

PROTOTYPE 是 Browser4 管理的浏览器 profile 模板目录，SEQUENTIAL（轮流池）和 TEMPORARY（临时池）模式的所有 profile 都从它继承。理解 PROTOTYPE 的创建、维护和继承机制，是正确使用 Profile Mode 的关键。

---

## 一、目录结构

PROTOTYPE 目录遵循平台和浏览器类型的约定（定义见 `PrivacyContext.Companion`）：

```
~/.browser4/
├── browser/
│   └── chrome/
│       ├── prototype/                          ← PROTOTYPE_CONTEXT_DIR（父目录）
│       │   ├── google-chrome/                  ← PROTOTYPE_DATA_DIR（真正的 --user-data-dir）
│       │   ├── chrome-launch-arguments.txt
│       │   └── chrome-launch-output.log
│       └── ...
└── context/
    └── groups/
        ├── default/                            ← SEQUENTIAL 池
        │   └── PULSAR_CHROME/
        │       ├── cx.001/PULSAR_CHROME/        ← SEQUENTIAL 从 PROTOTYPE 复制
        │       ├── cx.002/PULSAR_CHROME/
        │       └── ...
        └── temporary/                          ← TEMPORARY 池
            └── PULSAR_CHROME/
                └── cx.<random>/PULSAR_CHROME/   ← 同样从 PROTOTYPE 复制
```

**关键常量（`PrivacyContext.Companion`）：**

| 常量 | 值 | 含义 |
|---|---|---|
| `PROTOTYPE_DATA_DIR` | `AppPaths.CHROME_DATA_DIR_PROTOTYPE` | Chrome 的实际 user-data-dir |
| `PROTOTYPE_CONTEXT_DIR` | `PROTOTYPE_DATA_DIR.parent` | 父目录，chrome-launch-arguments.txt 在这里 |
| `DEFAULT_CONTEXT_DIR` | `AppPaths.CONTEXT_DEFAULT_DIR` | DEFAULT 模式的独立目录（**不继承 PROTOTYPE**） |

---

## 二、新用户首次启动：PROTOTYPE 从哪来

**核心答案：Chrome 自己创建，Browser4 不做任何 seed 或初始化。**

### 首次启动的完整流程

```
browser4-cli open https://example.com
       │
       ▼
PulsarSessionManager.normalizeCapabilities()
       │  → profileMode 决策（默认 SEQUENTIAL）
       ▼
AbstractPulsarSession.browserIdFor(mode)
       │  → BrowserId.createNextSequential()   // 选 cx.001 / cx.002 / ...
       ▼
BrowserProfileGeneratorFactory.getBrowserProfile(mode)
       │  → SequentialBrowserProfileGenerator
       ▼
SequentialBrowserProfileGenerator.invoke(fingerprint)
       │  → BrowserFiles.computeNextSequentialContextDir(group, fingerprint, maxProfiles)
       │     （外部库 pulsar-common）
       │     1. 创建 cx.N 目录
       │     2. 如果 PROTOTYPE 目录存在 → 把它的内容复制到 cx.N/PULSAR_CHROME
       │     3. 如果 PROTOTYPE 目录不存在 → cx.N 保持为空目录
       ▼
DefaultBrowserFactory.launch(browserId)
       │  Browser4Extensions.applyTo()   // EXTENSIONS_INHERIT 策略
       │  Browser4UserAgent.applyTo()    // 伪装 --user-agent
       ▼
PulsarBrowserLauncher.launch(browserId)
       │  chrome.exe --headless --user-data-dir=<cx.N/PULSAR_CHROME>
       ▼
Chrome 启动，发现 user-data-dir 为空
       │
       ▼
Chrome 自动初始化完整 profile：
  ├── Default/
  │   ├── Extensions/      ← 自动安装默认扩展
  │   ├── Cache/
  │   ├── Code Cache/
  │   ├── DIPS/
  │   ├── ... 40+ 子目录
  ├── Local State
  ├── Variations
  └── BrowserMetrics-*.pma
```

### 如果用户使用 PROTOTYPE 模式首次启动

```bash
browser4-cli open --headed --profile-mode PROTOTYPE https://example.com
```

- `BrowserProfileGeneratorFactory.getBrowserProfile(PROTOTYPE)` → `PrototypeBrowserProfileGenerator`
- 它调用 `BrowserProfile.createDefault(fingerprint.browserType)`，指向固定路径 `PROTOTYPE_DATA_DIR`
- Chrome 首次启动时 GUI 模式，自动在这个路径下创建完整 profile
- 手动安装的扩展、浏览器偏好、登录状态都会写入 `google-chrome/Default/`

### PROTOTYPE 与 DEFAULT 的关键区别

| 维度 | PROTOTYPE | DEFAULT |
|---|---|---|
| 路径 | `~/.browser4/browser/chrome/prototype/google-chrome/` | 独立固定目录 |
| 由谁创建 | Chrome 首次启动 | Chrome 首次启动 |
| 被谁继承 | SEQUENTIAL、TEMPORARY | **不被任何池继承** |
| 用途 | 模板，可被用户丰富 | Browser4 的长期共享默认 profile |
| 是否可手动扩展 | ✅ 是（装扩展、登录账号） | ⚠️ 可以但不会被其他模式继承 |

> **重要：** DEFAULT 和 PROTOTYPE 是两个独立概念。DEFAULT 是 Browser4 自己的共享默认 profile，不继承 PROTOTYPE。要让 SEQUENTIAL/TEMPORARY 池继承你手动注入的扩展和登录状态，必须丰富的是 **PROTOTYPE**，不是 DEFAULT。

---

## 三、SEQUENTIAL 和 TEMPORARY 如何从 PROTOTYPE 继承

### 核心机制（`BrowserFiles.computeNextSequentialContextDir`）

这个方法在外部库 **pulsar-common** 中实现，负责：

1. **轮流选择目录**：维护 `cx.001`、`cx.002`、...、`cx.N` 的固定数量的目录池
2. **从 PROTOTYPE 复制内容**：每当需要一个新的或已被回收的 context dir 时，把 PROTOTYPE 目录的完整内容复制进去
3. **Chrome 只做增量写入**：复制完成后 Chrome 启动，在复制过来的 profile 上做增量修改（新增 Cookie、更新 LocalStorage 等）

### 继承发生的时机

| 时机 | 行为 |
|---|---|
| PROTOTYPE 首次创建前 | SEQUENTIAL 的 cx.N 是空目录，Chrome 在里面自己初始化（完全干净） |
| PROTOTYPE 创建后 | 后续创建的 cx.N 会从 PROTOTYPE 复制，继承扩展、偏好、... |
| cx.N 被回收后复用 | 每次复用都重新从 PROTOTYPE 复制，**恢复干净状态** |

### 实测对比

在一台真实 Browser4 用户的机器上观察：

| 位置 | Extensions 数量 | Extensions ID |
|---|---|---|
| PROTOTYPE `google-chrome/Default/Extensions/` | 2 | `lanhpcaapacdmofkjpkahckmlkljcdhh` + `ncennffkjdiamlpmcbajkmaiiiddgioo` |
| SEQUENTIAL cx.001 `PULSAR_CHROME/Default/Extensions/` | 1 | `ncennffkjdiamlpmcbajkmaiiiddgioo` |

**差异原因：** SEQUENTIAL 模式默认 `--headless` 启动。headless Chrome 对扩展的处理和 GUI 模式不同——headless 不会加载那些依赖 DOM/UI 的扩展（如 `lanhpcaapacdmofkjpkahckmlkljcdhh`，可能是 K 文档扩展）。复制操作是完整的，但 headless Chrome 运行时不会把所有已安装扩展都加载到 `Default/Extensions/` 下。

---

## 四、如何主动丰富 PROTOTYPE

PROTOTYPE 不只是 Chrome 自动填充的，用户可以通过以下方式注入自己的扩展、登录状态和浏览器偏好。

### 方法 1：在 PROTOTYPE 模式下手动配置（推荐）

最简单直接的方式——用 PROTOTYPE + headed 模式启动浏览器，然后像日常用 Chrome 一样安装扩展、登录账号、设置偏好：

```bash
# GUI 模式启动 PROTOTYPE profile
browser4-cli open --headed --profile-mode PROTOTYPE https://chrome.google.com/webstore

# 手动操作：
#   1. 安装需要的扩展（AdGuard、脚本管理器、验证码辅助等）
#   2. 登录目标网站（GitHub、Google、...）
#   3. 调整浏览器偏好（语言、主题、启动页等）

# 关闭后，PROTOTYPE 已被丰富，后续 SEQUENTIAL/TEMPORARY 会继承
browser4-cli close
```

**注意事项：**
- `--profile-mode PROTOTYPE` 是全局 profile 模式切换，**不要**同时指定 `SEQUENTIAL` 或 `TEMPORARY`
- 扩展会写入 `google-chrome/Default/Extensions/`，会被复制到后续池
- Cookie 和 LocalStorage 也会被复制，但建议关键登录状态同时用 `state save` 备份

### 方法 2：手动复制系统浏览器 profile

从系统浏览器（Chrome/Edge）复制完整 profile 到 PROTOTYPE 目录：

```powershell
# 步骤 1: 完全关闭系统浏览器（包括后台进程）
taskkill /F /IM chrome.exe    # Windows 示例

# 步骤 2: 复制 Default 目录到 PROTOTYPE
Copy-Item -Recurse "$env:LOCALAPPDATA\Google\Chrome\User Data\Default" `
  "$env:USERPROFILE\.browser4\browser\chrome\prototype\google-chrome\" `
  -Exclude "SingletonLock","SingletonSocket","SingletonCookie"
```

**安全注意：**

| 风险 | 说明 |
|---|---|
| **DPAPI 加密**（Windows） | Cookie 和密码使用 Windows 用户密钥加密。同一 Windows 用户账户下复制才能正确解密；跨机器、跨用户无效 |
| **Chrome 版本不匹配** | 新版 Chrome 的 profile 可能被旧版 Chromium 拒绝。保持源浏览器和目标浏览器版本接近 |
| **锁文件冲突** | `SingletonLock`、`SingletonSocket`、`SingletonCookie` 必须排除，否则 Chrome 无法启动 |
| **跨平台不支持** | macOS Keychain / Linux Keyring 加密无法在 Windows 上解密 |

### 方法 3：使用 profile-import 插件（4.14+）

Browser4 4.14 及以上版本提供 `browser4-profile-import` 插件，可以自动化完整 profile 发现与导入：

```bash
# 发现本机可用的浏览器 profile
browser4-cli plugin profile_import.list_sources

# 导入指定 profile（默认排除密码以保障安全）
browser4-cli plugin profile_import.import

# 导入完成后，后续 SEQUENTIAL/TEMPORARY 模式会自动继承
```

---

## 五、完整继承决策链

从 `open` 命令到 Chrome 启动，PROTOTYPE 参与的每一步：

```
browser4-cli open [--profile-mode SEQUENTIAL]
       │
       ▼
① Session Manager：normalizeCapabilities()
       │  决定最终 profileMode（见决策树）
       ▼
② Session：browserIdFor(mode)
       │  PROTOTYPE  → BrowserId.createPrototype()
       │  SEQUENTIAL → BrowserId.createNextSequential()
       │  TEMPORARY  → BrowserId.createRandomTemp()
       ▼
③ Generator Factory：getBrowserProfile(mode, conf)
       │  PROTOTYPE  → PrototypeBrowserProfileGenerator
       │  SEQUENTIAL → SequentialBrowserProfileGenerator
       │  TEMPORARY  → RandomBrowserProfileGenerator
       ▼
④ Generator.invoke(fingerprint)
       │  PROTOTYPE  → BrowserProfile.createDefault()
       │  SEQUENTIAL → BrowserFiles.computeNextSequentialContextDir()  ← 从 PROTOTYPE 复制
       │  TEMPORARY  → BrowserFiles.computeRandomTmpContextDir()      ← 从 PROTOTYPE 复制
       ▼
⑤ Browser Factory：DefaultBrowserFactory.launch()
       │  Browser4Extensions.applyTo()  ← EXTENSIONS_INHERIT 策略
       │  Browser4UserAgent.applyTo()   ← 伪装 user-agent
       ▼
⑥ Chrome 启动
       │  chrome.exe --headless --user-data-dir=<context dir>
       ▼
PROTOTYPE 已 seed 的内容 → Chrome 增量写入（Cookie、LocalStorage、history 等）
```

---

## 六、Profile Mode 继承关系速查

| Mode | 谁创建 profile | 创建时是否继承 PROTOTYPE | Chrome 自己做了什么 | 典型用途 |
|---|---|---|---|---|
| **PROTOTYPE** | Chrome 首次启动 | N/A（它自己就是模板） | 完全初始化，GUI 模式下安装扩展 | 丰富模板：装扩展、登录、设偏好 |
| **DEFAULT** | Chrome 首次启动 | ❌ 不继承 | 独立初始化 | 单任务、串行脚本、CI |
| **SEQUENTIAL** | pulsar-common 复制 | ✅ 如果 PROTOTYPE 存在 | 增量修改 | 批量任务、IP 轮换 |
| **TEMPORARY** | pulsar-common 复制 | ✅ 如果 PROTOTYPE 存在 | 增量修改 | 一次性干净抓取 |
| **SYSTEM_DEFAULT** | 指向系统浏览器 | N/A | 指向系统真实 profile | ⚠️ **已废弃** |

> **关键洞察：** DEFAULT 和 PROTOTYPE 是两条平行的线。要让批量任务继承你手动配置的扩展和登录状态，丰富的是 **PROTOTYPE**，不是 DEFAULT。SEQUENTIAL 和 TEMPORARY 只继承 PROTOTYPE。

---

## 七、常见问题

### Q1: 为什么我的 SEQUENTIAL profile 里没有 PROTOTYPE 的扩展？

可能有两个原因：

1. **PROTOTYPE 目录是在第一次 SEQUENTIAL 启动之后才创建的**——已存在的 cx.N 不会回溯复制，新创建的才会。强制回收重建：
   ```bash
   # 手动删除 SEQUENTIAL 池，下次启动会从 PROTOTYPE 重新 seed
   Remove-Item -Recurse "$env:TEMP\browser4-pereg\context\groups\default\PULSAR_CHROME\cx.*"
   ```

2. **headless Chrome 不加载某些扩展**——SEQUENTIAL 默认 `--headless`，如果扩展依赖 DOM/UI，headless 不会把它加载到 `Default/Extensions/` 下。试试：
   ```bash
   # HEADED 模式下 SEQUENTIAL 也能完整继承扩展
   browser4-cli open --headed --profile-mode SEQUENTIAL
   ```

### Q2: SEQUENTIAL 池被回收复用后，继承关系还在吗？

在。每次复用被回收的 cx.N 时，`computeNextSequentialContextDir` 会重新从 PROTOTYPE 复制一份内容进去——**恢复干净状态**。这意味着：
- PROTOTYPE 里的扩展会被恢复 ✅
- PROTOTYPE 里的登录状态会被恢复 ✅
- 上一次运行累积的 Cookie 会被清除 ✅

这是 SEQUENTIAL 模式实现"每次都是干净但有基础配置的 profile"的关键。

### Q3: SEQUENTIAL 和 TEMPORARY 的继承有什么区别？

两者都从 PROTOTYPE 继承，但 **TEMPORARY 用完即弃**，SEQUENTIAL 在池里轮流复用。继承内容完全相同，区别在于用完后的处理：

| 对比 | SEQUENTIAL | TEMPORARY |
|---|---|---|
| 创建 | 从 PROTOTYPE 复制 | 从 PROTOTYPE 复制 |
| 用完后 | 归还池，下次复用前**重新从 PROTOTYPE 复制** | 直接删除 |
| 最大数量 | `BROWSER_CONTEXT_NUMBER`（默认 2） | 无上限 |
| 典型场景 | IP 轮换、批量任务 | 一次性抓取 |

### Q4: 可以用环境变量覆盖 PROTOTYPE 路径吗？

目前不可以，PROTOTYPE 路径在 `AppPaths.CHROME_DATA_DIR_PROTOTYPE` 中硬编码。如果需要指向其他 profile 目录，使用 `--profile <path>` 参数（它走 `PROFILE_PATH` 的 LaunchProfileSource，绕过所有 profile mode 逻辑）。

---

## 八、相关文档

| 文档 | 说明 |
|---|---|
| [browser-data-sync.md](browser-data-sync.md) | Storage State 保存/恢复、完整 Profile 复制的完整指南 |
| [config.md](config.md) | `browser.profile.mode` 等配置项 |
| [browser-modes.md](browser-modes.md) | 会话模式、显示模式、Profile Mode 完整说明 |
| [storage-state.md](../skills/browser4-cli/references/storage-state.md) | State save/load 命令参考 |
| [browser-state-import.md](../skills/browser4-cli/references/browser-state-import.md) | 系统浏览器状态迁移指南 |
