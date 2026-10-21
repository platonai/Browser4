# 评估：如何复制系统浏览器的状态到 Browser4 管理的浏览器

> 评估日期：2026-08-18
> 状态：已落档。实操指南见 `skills/browser4-cli/references/browser-state-import.md`。

## 1. 结论

Browser4 已有一条官方支持的“复制登录态”路径：**附加到系统浏览器 → `state-save` 导出 cookies + localStorage → 在 Browser4 管理的会话里 `state-load` 恢复**，无需写代码。要复制更完整的“整份 profile”（历史、密码、扩展、IndexedDB），目前只能手工复制 profile 目录并用 `--profile` 或 PROTOTYPE 模式挂载，且存在硬性限制。`SYSTEM_DEFAULT` 模式（直接用系统默认 profile）在 Chrome 143+ 已被官方废弃，不可依赖。

## 2. 现状盘点（代码证据）

Browser4 有五种 profile 模式（`browser4-core/browser4-skeleton/.../browser/privacy/BrowserProfileGeneratorFactory.kt`）：

1. **`SYSTEM_DEFAULT`**：直接让 Browser4 启动的 Chrome 指向系统默认 profile。但 `PulsarSettings.kt` 的 KDoc 明确写着 “No longer supported by Chrome since v143”（issue #162）——Chrome 已不允许对真实默认 profile 开远程调试，且系统浏览器运行中 profile 是锁定的。**对现代 Chrome 已失效**，Edge/旧版 Chromium 才可能用。
2. **`attach --extension` / `attach --cdp`**：不复制状态，而是通过 Chrome 扩展中继或 CDP 直连**正在运行的系统浏览器**，保留全部现有状态（cookies、登录、扩展）。`chrome-extension/src/protocolHandlers.ts` 允许 `chrome.debugger.sendCommand` 透传任意 CDP 命令；`skills/browser4-cli/references/attach.md` 明确把 `state-save` 列为 attach 后的推荐步骤。
3. **`state-save` / `state-load`**：把当前会话的 cookies + localStorage 导出/恢复为 JSON（`cli/browser4-cli/src/main.rs` 的 `handle_state_save` / `handle_state_load`；格式见 `skills/browser4-cli/references/storage-state.md`）。恢复端有专门修复：`Browser4WebDriver.loadStorageState` 会逐 origin 导航、等文档提交后再写 localStorage。
4. **`PROTOTYPE` 模式**：Browser4 维护受管原型 profile（如 `~/.browser4/browser/chrome/prototype/google-chrome/`），SEQUENTIAL/TEMPORARY 上下文从原型复制/继承（`BrowserProfileGenerator.kt` 的 `RandomBrowserProfileGenerator` KDoc）。原型可充当“系统状态中转站”。
5. **`open --profile <path>`**：CLI 允许把任意 profile 目录直接挂给 Browser4 会话（`cli/browser4-cli/src/commands.rs` 的 `open` 命令定义）。

## 3. 方案对比

| 方案 | 覆盖的状态 | 是否“复制到受管浏览器” | 现状 | 主要限制 |
|---|---|---|---|---|
| A. attach（扩展/CDP）+ `state-save` → `state-load` | cookies + localStorage（登录态核心） | 是 | **可用，官方文档化** | 不含历史/密码/扩展/IndexedDB/sessionStorage；localStorage 只导出当前 origin |
| B. `attach --extension/--cdp` 直接用 | 系统浏览器全部状态 | 否（直接控制原浏览器） | 可用 | 依赖原浏览器持续运行；chrome:// 页面会掉线 |
| C. `SYSTEM_DEFAULT` 模式 | 系统默认 profile | 否（共享） | **已废弃** | Chrome ≥143 不支持 |
| D. 复制 profile 目录 → PROTOTYPE 或 `open --profile` | 几乎全部（历史/密码/扩展/DB） | 是 | 手工操作，无命令支持 | 需先完全关闭 Chrome；Windows 下密码/cookie 用 DPAPI 加密（同机同用户可解）；Chrome 版本差异可能不兼容；复制时不能带 SingletonLock |

## 4. 推荐流程（今天可用）

```powershell
browser4-cli attach --extension
browser4-cli state-save system-auth.json
browser4-cli close
browser4-cli open --fresh
browser4-cli state-load system-auth.json
browser4-cli goto https://example.com/dashboard
```

补充：

- 系统浏览器开了远程调试时也可用 `attach --cdp chrome` 或 `attach --cdp http://localhost:9222`。
- `state-save` 只导出**当前活动页** origin 的 localStorage（`BrowserTabToolExecutor.kt` 的 tool spec 写明）。多 origin 需逐站保存后合并 JSON，或用 `cookie-list`/`localstorage-get` 选择性复制。
- 想长期复用登录态，可让恢复后的 Browser4 会话保持 DEFAULT profile，之后 `open` 自动复用。

## 5. 边界与坑

- `state-save` 不含：sessionStorage（设计如此）、历史、密码、扩展、IndexedDB、缓存、Service Worker。
- `SYSTEM_DEFAULT` 在 Chrome ≥ 143 失效；文档已标注 Deprecated 并给出替代路径。
- 整份 profile 复制：源浏览器必须完全关闭；跳过 `SingletonLock/SingletonSocket/SingletonCookie`；Windows DPAPI 使 profile 仅在同机同用户可用；源/目标 Chrome 版本差异可能导致 profile 被拒。

## 6. 产品化建议（可选）

若要“一键导入”，可做 `browser4-cli profile-import --source system --mode state|full`：

- `--mode state`：内部即 attach → state-save → open → state-load，改动小、风险低。
- `--mode full`：需要处理 profile 目录复制（源浏览器关闭检测、锁文件、DPAPI/版本兼容检查），改动与风险显著更高，需要 Windows/多版本 Chrome 测试矩阵。

建议先按方案 A 做真实验证（重点确认扩展会话里 `state-save` 能取到完整 cookies），再决定是否实现 `profile-import`。
