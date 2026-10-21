# CDP attach：从"没有页面目标"到浏览器级 WebSocket（4.13.x）

日期：2026-10-01 ｜ 分支：`4.13.x` ｜ 关联：platonai/Browser4#611 ｜ 基库：`pulsar-bom 4.11.23`

相关：[attach-cdp-probes/README.md](attach-cdp-probes/README.md)（探针脚本与"证据规则"）｜
[base-release-4.11.23.md](base-release-4.11.23.md)（基库发布流程与本轮发布阻塞）

目标：把"`attach --cdp` 连不上"这一类问题从"猜 Chrome 版本行为"变成**可复现的判据 + 可执行的排查脚本**，
并记录本轮真正踩到的坑（Windows 进程命令行解析、外部身份 key、提交拆分与 CI 发布）。

---

## 结论摘要

1. **HTTP 404 不能证明 WebSocket 端点不可用。** WebSocket 端点对**非升级的普通 GET** 本来就返回 404；
   判断"能不能附加"必须做一次**真实的 WS 升级**再调 `Target.getTargets`。这正是 #611 里最容易误判的一步
   （报告者据此以为 Chrome 153"没有可附加端点"，实际是"没有 HTTP 发现"）。
2. Chrome 有两种远程调试形态，端点拓扑不同（§2）：命令行 `--remote-debugging-port=N` 的**legacy 模式**同时提供
   `/json*` 与浏览器级 WS；`chrome://inspect/#remote-debugging` 开关的**内置模式**只提供浏览器级 WS，
   `/json*` 全部 404，端口与 socket 路径写在 profile 的 `DevToolsActivePort` 里。
3. 只读探针脚本 `docs-dev/copilot/attach-cdp-probes/probe-cdp-endpoints.ps1` 是这条链路的**唯一可信取证工具**：
   枚举候选 → HTTP 探测 → 真实 WS 升级 → `Target.getTargets`，退出码直接给出"可附加 / 无目标 / 无候选"。
4. 修完之后两种模式都能附加：`--cdp ws://host:port/devtools/browser/<uuid>` 直连该 socket；
   `--cdp chrome` 会从 `DevToolsActivePort` 第二行拿到 socket URL；页面仍走 `ws://host:port/devtools/page/<id>`。
5. 真机验证（Windows 11 + Chrome 154）：attach → `Attached to Google Chrome 154.0.8037.58 at ws://…` →
   `goto https://example.org` → `page-info` 报 `https://example.org/`；无页面目标时依旧响亮失败。
6. 本轮另有 4 类"非 CDP"的坑被踩到并修掉：Windows 命令行里的 `--user-data-dir` 解析、外部身份 key 的
   文件名字符合法性、**测试桩在 Windows 上继承非阻塞模式**、以及 `Select-Object -Last N` 包装会起后台进程的
   CLI 会永久挂住（§5）。发布侧的坑见 `base-release-4.11.23.md`。

---

## 1. 判据：先分清"没有 HTTP 发现"和"不可附加"

| 观察 | 能推出什么 | 不能推出什么 |
|---|---|---|
| `GET /json/version` 200 | 有 CDP HTTP 发现面（legacy 模式） | 是否有页面目标 |
| `GET /json` 返回 `[]` | 该端口没有页面目标 | 该浏览器没有标签页（可能只是连错了端口） |
| `GET /json/*`、`/devtools/browser/<uuid>` 全 404 | 该端口不提供 HTTP 发现 | WebSocket 不可用（**必须做 WS 升级**） |
| WS 升级成功 + `Target.getTargets` 有 `page` | 可附加 | — |
| WS 升级成功 + `Target.getTargets` 为 0 | 端点活着但**没有页面**，或连到了错误的实例 | — |

命令行的最小取证（Windows PowerShell，只读）：

```powershell
pwsh ./docs-dev/copilot/attach-cdp-probes/probe-cdp-endpoints.ps1                       # 全部候选
pwsh ./docs-dev/copilot/attach-cdp-probes/probe-cdp-endpoints.ps1 -Port 9222 -BrowserPath '/devtools/browser/<uuid>'
```

退出码：`0` 至少一个页面目标可经浏览器级 WS 到达；`1` 没有任何候选；`2` 有候选但都没有页面目标。
脚本只发发现类请求与 `Target.getTargets`，不导航、不附加、不改动浏览器。

---

## 2. 两种远程调试形态的端点拓扑

| | legacy（`--remote-debugging-port=N`） | 内置（`chrome://inspect/#remote-debugging`） |
|---|---|---|
| `/json/version`、`/json` | 200，正常列出 page | **全部 404** |
| 浏览器级 WS | `ws://host:port/devtools/browser/<uuid>`（`/json/version` 里给） | 同一形态，但只能从 `DevToolsActivePort` 得到 |
| 页面级 WS | `ws://host:port/devtools/page/<targetId>` | **同样可用**（这是内置模式仍能被 `chrome://inspect` 打开的原因） |
| 端口来源 | 命令行 / 监听端口 | `DevToolsActivePort` 第一行 |
| 页面发现 | `GET /json` | 浏览器级 WS 上的 `Target.getTargets` |

`DevToolsActivePort` 的两个要点：

1. **位置与格式**：位于 **user-data-dir 根目录**（不是 `Default/`），
   第一行端口、第二行 `/devtools/browser/<uuid>`。实测样例：
   ```
   4887
   /devtools/browser/8b91cacf-d8aa-4fa3-8b45-687c7de7af8a
   ```
2. **可能是陈旧的**：浏览器退出后文件不删。本机实测存在一个 2025-03-11 的陈旧文件，端口早已不监听。
   因此读到端口只是"候选"，**必须探测存活**后才能使用。

拿到 socket URL 后的手工验证（`ClientWebSocket` / `wscat` 均可）：

```jsonc
{"id":1,"method":"Target.getTargets"}      // 期望返回 result.targetInfos，含 type=page
```

---

## 3. 候选解析：为什么不能"先到先得"

旧实现的逻辑是"找到一个能应答 `/json/version` 的端口就用它"，于是：

- 一个**只应答但不含页面目标**的端口（例如内置模式旁路的 HTTP 面）会被选中，随后被后端以
  "reachable but has no page targets" 拒绝——用户看到的却是"请打开一个标签页"，而标签页明明开着；
- 找到之后**不回退**，其他候选（正确的 socket、其他实例）根本没机会。

现在的四层候选与判定（`cli/browser4-cli/src/daemon.rs`）：

1. `<user-data-dir>/DevToolsActivePort`（端口 + 浏览器 socket 路径）+ 命令行 `--remote-debugging-port=N`；
2. 该浏览器进程**其他监听端口**（Windows；这是随机端口浏览器当年唯一能被发现的路子）；
3. 通道默认端口（chrome=9222）；
4. 9222–9333 并发扫描。

每个候选都按 **page target 感知**打分：`/json` 有 page → HTTP 可附加；没有 HTTP 发现但 **知道 socket 路径** →
按浏览器级 WS 可附加；两者都不满足才继续找下一个。**顺序即优先级**（阶段内先到先得），
跨传输等价（HTTP 与 WS 都算"能承载页面"）。

> 由此带来的行为变化：若内置模式浏览器的 `DevToolsActivePort` 排在前面，`attach --cdp chrome` 会附加到它
> （而不是像从前那样跳到别的、有页面目标的实例）。附加后 CLI 会打印真实浏览器身份与当前页面 URL，用于自检。

---

## 4. 实现要点（两侧）

CLI（`main.rs`）：

- 浏览器级 `ws://…/devtools/browser/…` **原样透传**；页面级 `ws://…/devtools/page/<id>` 仍降级为 host:port，
  页面在那边经 `/json` 发现（保持既有语义）；
- `resolve_cdp_endpoint` 入口先 trim（否则带空格的参数会掉进"通道名"分支报 Unknown browser channel）。

后端（`PulsarSessionManager.createAttachedSession`）：

- 识别浏览器级 socket → 走 `createWebSocketAttachedSession`：用 `PulsarBrowser(browserWebSocketUrl = …)`
  连接，以 `Browser.getVersion` 取身份、`Target.getTargets` 取页面目标；
- 构造/连接失败统一包成"命名端点 + 真实原因 + retry attach"，不留裸平台异常；失败时不注册会话；
- 成功分支与 HTTP 路径语义对齐：`CDP_ATTACHED`（非自有，绝不被静默替换）、幂等重连、绑定既有页面 tab。

驱动层（基库 `pulsar-browser`）：`WebSocketChromeImpl` 只用浏览器 socket + 合成页面 socket，
不触碰任何 `/json*`。

---

## 5. 四个真实踩坑（都可复用）

### 5.1 `--user-data-dir` 解析：Windows 的两种畸形形态

实测到的原始命令行：

```
--user-data-dir=C:\Users\me\AppData\Local\Google\Chrome\User Data" /prefetch:4     ← 带空格 + 多余引号
--user-data-dir="C:\Users\me\AppData\Local\Google\Chrome\User Data"               ← 渲染进程的正常形态
--user-data-dir=C:\Users\me\.browser4\...\PULSAR_CHROME /prefetch:4               ← 无空格 + 单横线 token
```

只取"第一个空白分隔 token"会把路径截断成不存在的目录；反过来把 `/prefetch:4` 吞进路径也一样糟。
现在的做法：先用**多余引号 / 下一个 `--flag`** 划出窗口，再在窗口内**按 token 前缀取最长的、真实存在的目录**
（`user_data_dir_value()`）。选"最长"而不是"第一个命中"是被实机数据逼出来的：
`C:\…\Microsoft\Edge\User` **确实存在**，会抢走更长的 `…\Edge\User Data`。

### 5.2 进程名要精确匹配

`$_.Name -like '*msedge*'` 会命中 `msedgewebview2.exe`（WebView2 宿主），把应用的
`…\EBWebView` 目录当成浏览器 profile 扫。改用 `-ieq 'chrome.exe' / 'msedge.exe'` 后噪声消失。
另外同一 profile 会有多个 chrome 子进程（browser/renderer/crashpad），候选目录要去重。

### 5.3 测试桩在 Windows 上会"继承非阻塞"

`TcpListener::set_nonblocking(true)` 的 accept 循环里，Windows 上 **accept 出来的 socket 继承非阻塞模式**，
于是 `read()` 立刻 `WouldBlock`，桩偶尔不回响应 → 测试随机失败（linux 不复现）。
桩里必须显式 `stream.set_nonblocking(false)`（本仓库的 `CdpHttpStub` 已如此）。

### 5.4 包装"会启动后台进程的 CLI"时不要用管道聚合

`& ./b4w.ps1 open … | Select-Object -Last 12` 会**永久挂住**：CLI 拉起的后端继承了 stdout 句柄，
管道永远等不到 EOF。正确姿势是把每次调用重定向进文件、脚本结束后再读：

```powershell
& ./b4w.ps1 -s demo attach --cdp $ws *> "$env:TEMP\attach.out"
Get-Content "$env:TEMP\attach.out" -Tail 20
```

---

## 6. 外部身份必须是"合法文件名"

基库的 `PulsarBrowser(browserWebSocketUrl = …)` 会用 `BrowserId.external("attach.ws.<host>:<port>")` 生成
**外部身份**，而该 key 会变成虚拟上下文目录名 `cx.ext.<key>` —— **`:` 在 Windows 路径里非法**：

```
BrowserId.external("attach.ws.127.0.0.1:9222")
  -> java.nio.file.InvalidPathException: Illegal char <:> at index 26: cx.ext.attach.ws.127.0.0.1:9222
```

Linux 上 `:` 合法，于是这个 bug 只在 Windows 炸（"本地 Linux 通过、Windows 一片红"的经典形态）。
修复（已随 4.11.23 发布）：`WebSocketChromeImpl.externalKeyOf()` 用点号连接并按需压平 IPv6（`[::1]` → `--1`），
`ProfilePaths` 在**所有平台**统一校验外部 key（ASCII 字母/数字/`_`/`-`/`.`、≤64、禁尾点、拒绝非 ASCII 以免
macOS NFD 别名），非法 key 立刻抛可操作异常而不是在路径解析里晚失败。

> 教训：**任何会进文件名的标识符**都要在目标平台上验证，别只测本机 OS。同类隐患：`sessionId`、profile key、
> 以及任何"用 URL 拼目录名"的代码。

---

## 7. 验证方法（本轮实际用到的）

1. **纯函数 + 夹具**：候选排序、`DevToolsActivePort` 解析、`--user-data-dir` 解析、失败文案分支都做成纯函数，
   用临时目录/合成结构体做单测（不依赖本机浏览器）。
2. **可控 HTTP 桩**：`CdpHttpStub` 提供 `/json/version`、`/json`、可注入状态码与 `webSocketDebuggerUrl`，
   用来验证"有页面 / 无页面 / WS-only"三条分支（注意 §5.3）。
3. **实机烟测**：`#[ignore]` 的 `resolve_channel_live_smoke` / `unpacked_extension_roots_live_smoke`
   直接打印本机真实解析结果（`cargo test --bin browser4-cli -- --ignored --nocapture <name>`）——
   本轮就是靠它发现 §5.1 与 §5.2 的。
4. **真机 e2e**：`open --headless https://example.com` → 读该 profile 的 `DevToolsActivePort` →
   `attach --cdp ws://…` → `goto https://example.org` → `page-info` 断言 URL 变化。
   注意 dev 模式下后端来自 `browser4-apps/browser4-bundle/target/runtime-bundle`（源码变更时 CLI 会重建），
   手工重建用 `browser4-apps/browser4-bundle/build-runtime-bundle.ps1`；跑完 `./b4w.ps1 stop` 收尾。
5. **对"发布物"而不是"本地等价物"复验**：基库发布后把 `browser4-base.version` 切到正式版，
   重跑单测 + e2e（本轮做了两遍：SNAPSHOT 一次、Central 正式版一次）。

---

## 8. 排查清单（照做即可）

```powershell
# 1) 机器上有哪些 CDP 候选？谁有页面目标？谁能经浏览器级 WS 到达？
pwsh ./docs-dev/copilot/attach-cdp-probes/probe-cdp-endpoints.ps1

# 2) 通道名会解析到哪里（含真实浏览器身份）
cargo test --bin browser4-cli -- --ignored --nocapture resolve_channel_live_smoke

# 3) 显式指定 socket URL 附加，并确认它驱动的页面
browser4-cli -s dbg attach --cdp "ws://127.0.0.1:<port>/devtools/browser/<uuid>"
browser4-cli -s dbg page-info
```

失败时的读法：`is not usable over its browser-level WebSocket: <原因>` → socket 陈旧/浏览器已退，重读
`DevToolsActivePort`；`is reachable but lists no page target` → 该实例确实没有标签页（或连错实例）；
`Found a running … serves no CDP HTTP discovery endpoint` → 只找到 WS-only 但**没能拿到 socket 路径**，
显式传 `--cdp ws://…`。

---

## 9. 未决项

状态截至 2026-10-02：三条都不阻塞 4.13.24 的发布，前两条在 issue 上跟踪、第三条只差"真机矩阵"。

- **非 Windows 的监听端口枚举**（P2，未做）→ 已开 issue **platonai/Browser4#615**：目前只有 Windows 会枚举进程的其他监听端口；
  Linux/macOS 仍依赖 `DevToolsActivePort`（`--user-data-dir` 可从 `ps -e -o args=` 解析，故 Browser4 托管浏览器已覆盖）。
  剩余缺口是"默认 profile 或不常见安装根 + 随机端口"。实现思路：Linux 走 `/proc/<pid>/fd` → socket inode →
  比对 `/proc/net/tcp{,6}`（`ss -ltnp` 回退）；macOS 走 `lsof -nP -a -p <pid> -iTCP -sTCP:LISTEN`，
  配套纯解析函数单测。issue 里还列了验收标准（删掉 `DevToolsActivePort` 后仍能靠端口扫描解析、e2e 覆盖 Linux/macOS 腿、
  文档去掉 Windows-only 说明）。
- **内置模式端口拓扑的最后确认**（等外部输入）→ `DevToolsActivePort` 报 9222，而某随机端口（如 51343）也应答
  `/json/version` 却 `Target.getTargets` 为空。已在 #611 请报告者用探针脚本回贴输出确认二者关系
  （本机无法复现内置模式）。**截至 2026-10-02 尚无回复**：#611 上只有我方回帖（2026-10-01T15:32Z），
  issue 仍 OPEN。不影响已发布的修复——两种形态都已按"浏览器级 WS 优先"处理，这条只关乎 §2 表格最后一格的实测证据。
- **"哪些 Chrome 版本仍保留 legacy `/json` 发现"的对照表**（#611 建议 3，未做）：本轮只验证了**形态差异**
  （命令行 legacy 两种格式、内置形态 404 全部 `/json*`）与报告者的 Chrome 153 观测，**没有**版本边界数据，
  因此 `attach.md` / `browser-modes.md` 刻意只写形态、不写版本号。要落地需要：一台能切换 Chrome 版本的机器 +
  §8 的探针脚本，逐版本记录 `/json/version`、`/json`、浏览器级 WS 的 `Target.getTargets` 三项；
  结论落到 `skills/browser4-cli/references/`（`browser-modes.md` 已 498 行，注意 M6 的 500 行上限，
  放不下就单开一个 references 文件并在 `SKILL.md` 里链过去）。在此之前，文档对版本的态度是"不猜"。
