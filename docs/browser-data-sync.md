---
title: "浏览器数据同步机制"
description: "Browser4 的浏览器数据同步方案：Storage State 保存与恢复、Profile Mode 会话级管理、完整 Profile 复制，以及跨浏览器状态迁移的完整流程。"
---

# 浏览器数据同步机制

Browser4 提供了一套分层的浏览器数据同步方案，从细粒度的 Web 状态到完整浏览器 Profile 都有对应的机制，满足不同场景下对登录状态、Cookie、LocalStorage 等数据的持久化和迁移需求。

---

## 一、整体架构概览

Browser4 的数据同步分为三个层级，覆盖从认证状态恢复到完整浏览器环境迁移的不同需求：

| 层级 | 同步范围 | 推荐程度 | 适用场景 |
|---|---|---|---|
| **Web State**（Web 状态） | Cookies + localStorage | ✅ 官方推荐 | 恢复登录认证状态、自动化任务间共享会话 |
| **会话级 Profile** | 通过 Profile Mode 管理的浏览器 profile 目录 | ✅ 支持 | 命名会话持久化、批量任务隔离 |
| **完整 Profile 复制** | 历史、密码、扩展、IndexedDB 等 | ⚠️ 手动操作 | 需要完整浏览器环境迁移的特殊场景 |

三个层级可以独立使用，也可以组合使用——常见的模式是通过 `state save` / `state load` 迁移 Web 状态，同时使用命名会话（`-s <name>`）保持 Profile 持久化。

---

## 二、Storage State 管理（核心同步机制）

这是 Browser4 最核心、最推荐的浏览器数据同步方案，通过 `state save` / `state load` 命令实现浏览器存储状态的序列化与恢复。

### 2.1 保存 Storage State

```bash
# 保存到自动生成的文件（路径: .browser4-cli/snapshot/storage-state-{timestamp}.json）
browser4-cli state save

# 保存到指定文件路径（相对路径从当前工作目录解析）
browser4-cli state save my-auth-state.json
```

### 2.2 恢复 Storage State

```bash
# 从 JSON 文件恢复 storage state
browser4-cli state load my-auth-state.json

# Cookie 恢复后需要刷新或打开页面才会在下次 HTTP 请求中发送
browser4-cli open https://example.com
```

恢复后，Cookie 会立即写入浏览器的 Cookie Jar（可通过 `cookie list` 验证），localStorage 也会写入对应 origin。页面本身不会自动刷新——如果你需要恢复后的 Cookie 随请求发送，记得显式刷新或重新导航。

### 2.3 文件格式

保存的 JSON 文件包含两个顶层数组：

```json
{
  "cookies": [
    {
      "name": "session_id",
      "value": "abc123",
      "domain": "example.com",
      "path": "/",
      "expires": 1735689600,
      "httpOnly": true,
      "secure": true,
      "sameSite": "Lax"
    }
  ],
  "origins": [
    {
      "origin": "https://example.com",
      "localStorage": [
        { "name": "theme", "value": "dark" },
        { "name": "user_id", "value": "12345" }
      ]
    }
  ]
}
```

**Cookie 属性规范：**
- `name`、`value`、`domain`、`path`、`expires`、`httpOnly`、`secure`、`sameSite` 八个属性始终存在
- 浏览器未上报的属性值为 `null`（如 session Cookie 的 `expires: null`、未设置 SameSite 的 `sameSite: null`）
- 浏览器上报的其他属性（如 `partitionKey`）会原样保留

### 2.4 包含与排除

**包含：**
- 浏览器 Cookie Jar 中的所有 Cookie（通过 CDP `Network.getAllCookies` 获取）
- **当前活跃 origin** 的 localStorage

**排除（设计如此）：**
- `sessionStorage` —— 故意限定在浏览器会话范围内，不持久化
- 浏览历史、保存的密码、浏览器扩展、IndexedDB、HTTP 缓存、Service Worker、浏览器设置项

如需同步上述被排除的内容，请参考本章第五节「完整 Profile 复制」。

### 2.5 多 origin localStorage 收集

`state save` 只捕获当前活跃 origin 的 localStorage。如果需要收集多个 origin 的 localStorage：

```bash
# 方法 1: 分别保存后手动合并 JSON 的 origins 数组
browser4-cli open https://app.example.com
browser4-cli state save app.json
browser4-cli open https://api.example.com
browser4-cli state save api.json
# 手动合并两个 JSON 文件的 origins 数组

# 方法 2: 使用独立命令逐条导出
browser4-cli localstorage get theme          # 导出单条
browser4-cli cookie list --domain example.com # 按域名批量导出
```

---

## 三、独立的 Cookie / Storage 管理命令

除了整体的 `state save` / `state load`，Browser4 还提供了细粒度的独立命令来管理浏览器存储，适合需要精准控制的场景。

### 3.1 Cookie 管理

```bash
# 列出所有 Cookie（JSON 格式，按 domain → path → name 排序）
browser4-cli cookie list

# 按域名过滤
browser4-cli cookie list --domain example.com

# 按路径过滤
browser4-cli cookie list --path /api

# 获取指定 Cookie 的值
browser4-cli cookie get session_id

# 设置 Cookie（省略 --domain 时默认使用当前页面的 domain）
browser4-cli cookie set session abc123
browser4-cli cookie set session abc123 --domain example.com --path / \
  --httpOnly --secure --sameSite Lax --expires 1735689600

# 删除单个 Cookie
browser4-cli cookie delete session_id

# 清空所有 Cookie
browser4-cli cookie clear
```

### 3.2 LocalStorage 管理

```bash
browser4-cli localstorage list
browser4-cli localstorage get token
browser4-cli localstorage set theme dark
browser4-cli localstorage set user_settings '{"theme":"dark","language":"en"}'
browser4-cli localstorage delete token
browser4-cli localstorage clear
```

### 3.3 SessionStorage 管理

```bash
browser4-cli sessionstorage list
browser4-cli sessionstorage get form_data
browser4-cli sessionstorage set step 3
browser4-cli sessionstorage clear
```

> **注意：** Git Bash 用户在使用 `--path` 等需要 `/` 开头参数时，请注意 MSYS 路径转换可能导致的值污染（参见 `storage-state.md` 中的 Git Bash 备注）。

---

## 四、跨浏览器状态迁移

### 4.1 从系统浏览器迁移到 Browser4 管理的浏览器

将你日常使用的 Chrome/Edge 中已登录的状态迁移到 Browser4 启动的隔离浏览器中，是 Browser4 最常见的使用模式之一。

#### 推荐流程（官方支持）

```powershell
# 步骤 1: 连接到正在运行的系统浏览器
#    使用扩展方式无需配置调试端口，最便捷
browser4-cli attach --extension

# 步骤 2: 导出系统浏览器的认证状态（cookies + 当前 origin 的 localStorage）
browser4-cli state save system-auth.json

# 步骤 3: 断开连接（系统浏览器保持运行，标签页不受影响）
browser4-cli close

# 步骤 4: 启动 Browser4 管理的全新隔离浏览器
browser4-cli open --fresh

# 步骤 5: 导入状态，恢复登录
browser4-cli state load system-auth.json
browser4-cli goto https://example.com/dashboard
# → 无需重新登录！
```

如果系统浏览器已开启远程调试，也可以通过 CDP 方式连接：

```bash
# 通过 CDP endpoint 连接
browser4-cli attach --cdp chrome                       # 通过 channel 名自动发现
browser4-cli attach --cdp http://localhost:9222         # 指定 endpoint

# 后续流程相同：state save → close → open → state load
```

### 4.2 常见问题排查

| 症状 | 可能原因 | 解决方法 |
|---|---|---|
| Cookie 已恢复但登录依然失败 | `state load` 后页面未刷新，Cookie 未随请求发送 | `open` 或刷新页面 |
| `state load` 似乎没有效果 | 状态文件保存自不同的 profile 或 origin | 确认 JSON 中的 origins 与目标页面匹配 |
| 跨机器恢复失败 | Cookie 中的 `expires` 已过期 | 在原浏览器重新保存后再恢复 |

---

## 五、Profile Mode（会话级 Profile 管理）

Browser4 通过 `--profile-mode` 参数控制浏览器 Profile 的生命周期和隔离策略，这是会话级数据持久化的基础机制。

### 5.1 五种 Profile 模式

| 模式 | 行为 | 适用场景 |
|---|---|---|
| **DEFAULT** | 共享 Browser4 默认 profile，状态持久化 | 单任务、串行脚本、CI 流水线 |
| **SEQUENTIAL** | 从 PROTOTYPE 继承的轮流使用的 profile 池 | 批量任务、IP 轮换场景 |
| **TEMPORARY** | 一次性 profile，用完即弃 | 一次性干净抓取、避免状态污染 |
| **PROTOTYPE** | 管理的原型目录，SEQUENTIAL/TEMPORARY 从其继承 | 高级场景，需要定制基础环境 |
| **SYSTEM_DEFAULT** | 指向系统真实默认 profile | ⚠️ **已废弃**（Chrome ≥ 143 拒绝调试真实默认 profile） |

### 5.2 使用示例

```bash
# DEFAULT 模式（共享 Browser4 默认 profile）
browser4-cli open --profile-mode DEFAULT https://example.com

# TEMPORARY 模式（一次性干净环境）
browser4-cli open --profile-mode TEMPORARY https://example.com

# SEQUENTIAL 模式（轮流使用的 profile 池，配合代理轮换）
browser4-cli open --profile-mode SEQUENTIAL https://example.com
```

### 5.3 Profile 目录结构

Browser4 将所有浏览器 Profile 存储在用户数据目录下：

```
~/.browser4/
├── browser/chrome/prototype/google-chrome/     # PROTOTYPE 模式的原型目录
├── context/groups/named/
│   └── PULSAR_CHROME/
│       └── cx.<sessionUuid>/                    # 命名会话的专用 contextDir
├── context/groups/sequential/
│   └── PULSAR_CHROME/
│       └── cx.<N>/                              # SEQUENTIAL 模式轮流池
├── context/groups/temporary/
│   └── PULSAR_CHROME/
│       └── cx.<random>/                         # TEMPORARY 模式一次性目录
└── sessions/
    ├── DEFAULT.json                             # 默认会话状态
    ├── <name>.json                              # 命名会话状态文件
    └── SWARM.json                               # SWARM 会话状态
```

**BrowserId 与 Profile Mode 映射（`AbstractPulsarSession.browserIdFor`）：**

| Profile Mode | BrowserId 工厂方法 | Profile 来源 |
|---|---|---|
| DEFAULT | `BrowserId.createDefault()` | Browser4 共享默认 profile |
| SEQUENTIAL | `BrowserId.createNextSequential()` | 从轮流池中取下一个 |
| TEMPORARY | `BrowserId.createRandomTemp()` | 随机生成一次性目录 |
| PROTOTYPE | `BrowserId.createPrototype()` | 原型目录 |
| SYSTEM_DEFAULT | `BrowserId.createSystemDefault()` | 系统真实默认 profile（已废弃） |

### 5.4 命名会话与 Profile 持久化

使用 `-s <name>` 参数创建命名会话，可以让多个独立运行的 CLI 调用共享同一个浏览器 Profile。

**关键实现细节：** 命名会话默认的 Profile Mode 实际上是 **SEQUENTIAL**，但后端会通过设置 `contextDir` capability 将其固定指向专用目录 `cx.<sessionUuid>`，从而在 SEQUENTIAL 的 BrowserId 机制上实现了会话隔离。这是一种"复用轮流池的基础设施，但给每个命名会话一个固定 contextDir"的设计。

```bash
# 首次运行：创建命名会话并登录
browser4-cli -s work open --headed https://example.com
# ... 手动完成登录或 CAPTCHA ...
browser4-cli -s work cookie list                     # 验证登录状态

# 后续运行：同一会话名复用 Profile
browser4-cli -s work open https://example.com/dashboard
# → 登录状态保留，无需重新登录

# 关闭会话（Profile 目录保留）
browser4-cli -s work close
```

> **⚠️ 后端重启风险：** 命名会话的 Profile 绑定依赖于后端内存中的 displayName→sessionId 映射和 contextDir 计算。如果后端重启，映射可能丢失并重新生成不同的 sessionId，导致指向新的空 Profile 目录。实测结果为 REBOUND（会话 ID 变了，持久化 Cookie 丢失）。关键的认证状态建议同时通过 `state save` 持久化。

---

## 六、完整 Profile 复制（手动操作）

当需要同步浏览历史、保存的密码、浏览器扩展、IndexedDB 等完整浏览器数据时，需要手动复制浏览器 Profile 目录。这是一个手动操作流程，需要注意多个兼容性问题。

### 6.1 基本流程

```powershell
# 步骤 1: 完全关闭系统浏览器（包括后台进程）
taskkill /F /IM chrome.exe    # Windows 示例

# 步骤 2: 复制 Profile 目录（必须排除锁文件）
Copy-Item -Recurse "$env:LOCALAPPDATA\Google\Chrome\User Data\Default" `
  "D:\profiles\chrome-system-copy" `
  -Exclude "SingletonLock","SingletonSocket","SingletonCookie"

# 步骤 3a: 种子化 PROTOTYPE 目录（所有 SEQUENTIAL/TEMPORARY 会话会从中继承）
cp -r D:\profiles\chrome-system-copy ~/.browser4/browser/chrome/prototype/google-chrome/
browser4-cli open --profile-mode prototype

# 步骤 3b: 直接挂载到单次会话
browser4-cli open --profile D:\profiles\chrome-system-copy
```

### 6.2 使用 profile-import 插件（4.14+）

Browser4 4.14 及以上版本提供了 `browser4-profile-import` 插件，可自动化完整 Profile 发现与导入：

```bash
# 发现本机可用的浏览器 Profile
# 通过 plugin profile_import.list_sources 调用

# 导入指定 Profile（默认排除密码以保障安全）
# 通过 plugin profile_import.import 调用

# 导入完成后使用 open --profile <dir> 挂载
browser4-cli open --profile ~/.browser4/imports/<snapshot>/
```

### 6.3 注意事项

| 风险 | 说明 | 规避措施 |
|---|---|---|
| **DPAPI 加密**（Windows） | Cookie 和密码使用 Windows DPAPI 加密 | 同一 Windows 用户账户下复制才能正确解密，跨机器/跨用户无法生效 |
| **Chrome 版本不匹配** | 新版 Chrome 生成的 Profile 可能被旧版 Chromium 拒绝 | 保持源浏览器和目标浏览器版本接近 |
| **锁文件冲突** | `SingletonLock` / `SingletonSocket` / `SingletonCookie` 是进程相关的锁 | 复制时必须排除这三个文件 |
| **跨平台兼容性** | macOS Keychain / Linux Keyring 加密无法在 Windows 上解密 | 完整 Profile 复制不支持跨平台 |
| **安全风险** | 复制目录包含密码和 Cookie | 妥善保护复制目录，用完即删，密码默认排除 |

---

## 七、常见应用模式

### 模式 1：认证状态复用

最典型的同步场景——一次性登录后保存状态，后续任务直接恢复：

```bash
# 第一次运行：手动登录并保存状态
browser4-cli open --headed https://app.example.com/login
browser4-cli fill e1 "user@example.com"
browser4-cli fill e2 "password123"
browser4-cli click e3
browser4-cli state save auth.json

# 后续运行：恢复状态，跳过登录直接操作
browser4-cli open --fresh
browser4-cli state load auth.json
browser4-cli open https://app.example.com/dashboard
# → 已自动登录，可直接执行后续操作
```

### 模式 2：命名会话 + Storage State 双层保障

```bash
# 命名会话保持 Profile 持久化，state save 做额外备份
browser4-cli -s work open --headed https://example.com
# ... 登录 ...
browser4-cli state save work-auth.json

# 后端重启或新环境恢复
browser4-cli state load work-auth.json
browser4-cli -s work open https://example.com
```

### 模式 3：系统浏览器 → 隔离浏览器

将日常浏览器的认证状态迁移到 Browser4 管理的干净环境：

```powershell
browser4-cli attach --extension           # 连接日常 Chrome
browser4-cli state save daily-auth.json  # 导出认证状态
browser4-cli close
browser4-cli open --fresh --headless     # 启动隔离的 headless 浏览器
browser4-cli state load daily-auth.json  # 导入状态
browser4-cli goto https://example.com    # 已登录
```

### 模式 4：批量任务的 TEMPORARY Profile

```bash
# 批量抓取时使用 TEMPORARY Profile 避免状态累积
# 每个任务启动独立的一次性浏览器环境
browser4-cli -s task-1 open --profile-mode TEMPORARY https://example.com/a
browser4-cli -s task-2 open --profile-mode TEMPORARY https://example.com/b
```

---

## 八、安全注意事项

1. **不要提交认证状态文件** —— 将 `*.auth-state.json` 加入 `.gitignore`
2. **用完即删** —— 自动化完成后删除 state 文件和完整 Profile 复制目录
3. **环境变量传递敏感数据** —— 避免将 API Key、Token 等明文存入 state 文件
4. **完整 Profile 复制需额外保护** —— 包含密码和 Cookie，设置文件权限（chmod 600 / Windows ACL）
5. **不要用于敏感操作** —— 建议将 `state save` 文件视为等同于登录凭证对待

---

## 九、决策速查表

| 需求 | 推荐方案 | 关键命令/参数 |
|---|---|---|
| 恢复登录状态 | `state save` / `state load` | 保存后在新会话中加载 |
| 跨运行保持同一 Profile | 命名会话 `-s <name>` | CLI 参数，后端重启后可能需要 state save 兜底 |
| 系统浏览器认证迁移 | attach → state save → open → state load | 官方支持的完整迁移路径 |
| 一次性干净环境 | `--profile-mode TEMPORARY` | 用完即弃，无状态污染 |
| 需要 IP 轮换 | `--profile-mode SEQUENTIAL` + 代理配置 | Profile 池轮流使用 |
| 同步密码/历史/扩展 | 完整 Profile 复制（手动） | 需关闭源浏览器，排除锁文件 |
| 完整 Profile 自动化导入 | `browser4-profile-import` 插件 | 4.14+ 版本支持 |

---

## 十、相关文档

| 文档 | 路径 | 说明 |
|---|---|---|
| Storage Management | `skills/browser4-cli/references/storage-state.md` | state save/load 及独立 Cookie/Storage 命令完整参考 |
| Import Browser State | `skills/browser4-cli/references/browser-state-import.md` | 系统浏览器到 Browser4 的状态迁移指南 |
| Browser Modes | `skills/browser4-cli/references/browser-modes.md` | 会话模式、显示模式、Profile Mode 完整说明 |
| Attach 参考 | `skills/browser4-cli/references/attach.md` | attach --cdp / --extension 的端点发现与连接机制 |
| 配置参考 | `docs/config.md` | `browser.profile.mode` 等配置项的说明 |
| IP 代理配置 | `docs/ip-proxy.md` | SEQUENTIAL/TEMPORARY 模式下的代理轮换配置 |
