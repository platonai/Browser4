# 引导用户用「自己已登录的 GitHub 账号」点 Star —— 方案

> 状态：**已实现**（2026-11-11），见 §12 的实现记录
> 基线：2026-11-11，`platonai/Browser4` 1,180 ⭐ / 152 fork / 52 open issues
> 目标仓库固定为 `platonai/Browser4`（单一目标，不做多仓库）。

## 0. 一句话方案

新增 `browser4-cli star`：它只做三件事 —— **把用户带到仓库页（优先复用他已经登录的浏览器）、读出页面上的登录/Star 状态、然后停下来等人点**。

CLI 永不代点。转化靠「价值刚交付的那一刻提一句 + 一条真能一键到达的命令」，不靠打扰。

## 1. 为什么「代点」不能做（这是硬边界，不是可选优化）

- **合规**：GitHub Acceptable Use 明确禁止 inauthentic interactions / star manipulation。最坏结果不是「没效果」，是仓库或组织被处罚。
- **商业价值**：非真实用户产生的 star 不构成社交证明；被识破一次，信任成本远高于收益。
- **产品叙事**：Browser4 卖的是「AI 原生浏览器引擎」。用户一旦看到 CLI 会代替自己点 Star，就会合理怀疑它在别的站点也会自作主张地写操作。**人点，是产品可信度的一部分。**

落到代码约束：`star` 命令不发送 `POST /star`，不调用 `gh api -X PUT user/starred/*`，不做任何「批量/多账号」路径。

## 2. 三条用户路径

### P1 已 attach —— 最理想（用户自己的 Chrome 就是登录态）

`SessionKind::ExtensionAttached` / `CdpAttached`。

1. `tab-new https://github.com/platonai/Browser4` —— **只开新 tab，绝不用 `goto` 覆盖用户当前活动页**。
2. 一次 `eval` 读状态（脚本见 §5.2）。
3. 按结果分流：

| 页面状态 | CLI 行为 | 退出码 |
|---|---|---|
| `Starred` | `@login 已经 Star 过了，谢谢 🙂`，不再开 tab（幂等） | 0 |
| `LoggedIn && !starred` | 终端打印「请在浏览器里点右上角 ⭐ Star」，同时把按钮滚进视口 + 3 秒视觉脉冲（只注入样式，不碰 DOM 数据），轮询 ≤60s | 0 |
| `NotLoggedIn` | 导航到 `https://github.com/login?return_to=%2Fplatonai%2FBrowser4`，提示「登录好回来说一声」 | 0 |
| `Unknown`（DOM 变了 / 超时） | 降级为纯提示 + 链接，**不报错** | 0 |

### P2 未 attach

- `attach --list` 发现用户自己开着带调试口的 Chrome → 问一句 `Connect to your running Chrome? [y/N]`，同意才 `attach`（一次性，之后记在状态里）。
- 否则走已有的 `open_url_in_browser()`（`main.rs:3370`，Windows `cmd /c start` / macOS `open` / Linux `xdg-open`，并优先 Chrome/Edge）交给系统默认浏览器 → 「已打开，点完 Star 回来说一声，我再检查」。
- **不静默 attach**：接管用户真实浏览器必须是他点过头的。

### P3 无 GUI（SSH / 容器 / CI）

- 只打印 URL；无浏览器可启动时，再打印一句可复制的 `gh repo star platonai/Browser4`。
- **不实现 `--gh` 开关**（原提案里的可选路径，已否决）：一旦 CLI 真的执行写操作，就与「同意不做代点」自相矛盾，而这个用例只需要把命令递到用户手里 —— 他自己敲那一下，等价于手点，CLI 不承担任何写请求。可在 `star` 里加 `--gh` 的前提是重新讨论这条边界，不是顺手加个 flag。

## 3. 触达点矩阵

| 位置 | 时机 | 频次 | 实现点 |
|---|---|---|---|
| T0 `star` 命令 | 用户主动 | 无限（幂等） | 新增命令 |
| T1 首次「有产出」的成功命令后 | 刚拿到价值（`scrape` / `htmlsnapshot query` / `agent run` 结束） | **1 次** | `main.rs:35199`，与 `tips::show_tip` 同一处 |
| T2 `upgrade` 成功 | 升级即价值兑现 | 每个大版本 1 次 | `daemon.rs` 升级收尾 |
| T3 `doctor` 尾部 | 用户主动求助后 | 1 次 | `doctor` 输出末尾 |
| T4 `--show-tip` 轮换池 | 已经是 opt-in | 轮换 | `tips.rs` 加 1 条 |
| T5 README / README.zh / release notes / issue 模板 | 文档侧 | — | 指向 `browser4-cli star` |

**不做**：命令启动时、每条命令、错误信息里、`--json`/`--raw`/`--quiet` 模式、agent 对话上下文。

文案（CLI 保持英文，与现有输出一致）：

```
⭐ Finding Browser4 useful? Run `browser4-cli star` — it opens the repo in the browser
   you're already logged into and waits for your click. We never click Star for you.
```

## 4. 频控 / 退出 / 幂等

状态文件：`resolve_runtime_data_dir()/star-guide.json`（与 `update_check.rs` 的 state 同目录同风格）。

```json
{ "shows": 2, "last_shown_at": 1794404572, "starred_at": null, "dismissed": false }
```

- 上限 **3 次**，间隔 **≥7 天**；`starred` / `dismissed` 之后永久静默。
- 一票否决：`BROWSER4_NO_PROMO=1`；或 `browser4-cli star --dismiss`。
- 硬门禁（任一命中即不展示）：stdout 非 TTY、`--json` / `--raw` / `--quiet` / `--stdout`、命令来自 `agent run` / `chat` 内部、`CI=true`。
- 幂等：已 `Starred` 时 `star` 不再开 tab；重复运行输出一致。
- 失败静默：检测失败只降级，不改变退出码。仅「一个浏览器都打不开」时返回 2。

## 5. 技术实现

### 5.1 新文件 `cli/browser4-cli/src/star_guide.rs`（≈300 行）

```rust
pub enum StarState { Unknown, NotLoggedIn, LoggedIn { login: String, starred: bool } }

pub async fn cmd_star(client: &Client, base_url: &str, args: &Args) -> Result<i32, String>;
pub fn maybe_show_cta(command: &str, outcome_ok: bool);   // T1/T2/T3 共用入口
fn detection_js() -> &'static str;                        // §5.2
fn should_show_cta() -> bool;                             // TTY + 门禁 + 频控
fn read_star_state() -> StarGuideState;                   // 坏文件 → 默认值，不 panic
```

### 5.2 页面态检测（单次 eval，返回 JSON）

```js
(() => {
  const login = document.querySelector('meta[name="user-login"]')?.content || '';
  const unstar = !!document.querySelector('form[action$="/unstar"]');
  const star   = document.querySelector('form[action$="/star"]');
  const btn    = (unstar || star || document).querySelector?.('button') ?? null;
  const byText = [...document.querySelectorAll('button')]
    .find(b => /^(star|unstar|starred)$/i.test(b.textContent.trim()));
  const starred = unstar || (byText?.getAttribute('aria-pressed') === 'true');
  return JSON.stringify({ url: location.href, login, starred });
})()
```

多信号 fallback：`form[action$="/unstar"]` → `aria-pressed` → 按钮文案 → `Unknown`。
`Unknown` **绝不**当作「已 Star」，也绝不当作失败 —— 只是少给一句判断，多给一句「请你在页面上点一下」。

### 5.3 为什么优先 attach，而不是直接 `open`

`open` 走 `SEQUENTIAL` 隔离 profile → 全新 cookie jar → GitHub 必然未登录 → 用户得在自动化 profile 里现登，既容易被风控注意，心理成本也高（凭什么在编工具里登 GitHub）。
attach 复用真实浏览器，登录态天然存在。漏斗顺序因此固定为：**attach > 系统默认浏览器 > 提示手动**。

### 5.4 命令定义与改动面

| 文件 | 改动 |
|---|---|
| `star_guide.rs` | 新增（核心逻辑） |
| `commands.rs` | `CommandDef { name: "star", category: Category::Browsers, batch_supported: false, tool_name_fn: \|_\| String::new() }`；options：`--check`（重读已打开该仓库的 tab，reload 后判断）、`--no-open`（只打印，不碰浏览器）、`--wait <seconds>`、`--dismiss` |
| `main.rs` | 在 server gate **之前**分发 `star`；成功命令尾部调用 `star_guide::maybe_show_cta(command)` |
| `help.rs` / `tips.rs` | 帮助 Notes + 两条示例 + 轮换 tip |
| `skills/browser4-cli/SKILL.md` | **加一条 agent 规则：不得替用户执行 `star`**（它会打开真人浏览器并等人点） |
| `README.md` / `README.zh.md` / `cli/browser4-cli/README.md` | 一行 CTA + 命令说明 |

单字命令无子命令，`star` 保持裸 kebab，符合现有命名约定，不需要 `rewrite_prefixed_command()`。

测试钩子不是产品选项：`--repo` 只在提案里存在，实现改为环境变量 `BROWSER4_STAR_URL`（只接受 http(s)，其它值忽略），这样产品面永远只有一个目标仓库。

## 6. 测试

- **单元**（21 个，`star_guide::tests`）：`should_show_cta()` 真值矩阵（TTY/`--json`/`--quiet`/CI/`BROWSER4_NO_PROMO`/已 star/已 dismiss/次数上限/7 天间隔/时钟回拨）；检测结果解析（未登录、已登录未点、已点、别的页面、垃圾输入、JSON 引号包装）；`return_to` 编码；state 读写与坏文件容错；`--wait` 解析与上限；tab URL 匹配。
- **fixture**：`browser4-tests/pulsar-tests-common/src/main/resources/static/b4/star-fixture.html` —— 复刻页面上的三路信号（`meta[name=user-login]`、`form[action$="/star"|"/unstar"]`、`aria-pressed`），点击翻转；状态存 localStorage（模拟「账号级」而非「tab 级」）。用 `BROWSER4_STAR_URL=http://localhost:<port>/b4/star-fixture.html` 驱动，**永不访问真 github.com**。
- **e2e**：目前标记 `E2eCoverage::Excluded`（它会打开真人浏览器并等人点），fixture 与 URL 覆盖钩子已就位，后续加 `--scenario=test_e2e_star_*` 只需接上这两样。
- **已做的人工端到端验证**（headless Chrome 9333 + 本机 fixture server，走 `attach --cdp`）：开新 tab → 识别 `@fixture-user` 未 star → 等待 → 中途点按钮 → 轮询捕获 → 记 `starred_at` → 再跑幂等；`--check` reload 后正确处理「已 star / 取消 star」两种状态；`--dismiss` 生效。

## 7. 度量

- **北极星**：star 数（基线 1,180）。用只读、免鉴权的 `GET /repos/platonai/Browser4/stargazers?per_page=100` + `Accept: application/vnd.github.star+json` 拿每页的 `starred_at`，就能画时间序列，看发布日之后是否出现台阶。
- **判定**：发布后 4 周，star 增速 vs 前 4 周；同时观察 fork / issue 是否同向变化（排除「只是这次发布本身带来的普遍增长」）。
- **不做隐式遥测**。若确需漏斗数据，`star --report` 显式 opt-in，只上报 `{version, platform, outcome}`，默认关（**未实现**，等真的有这个需求再加）。
- 注意归因天花板：GitHub 只给 referring **sites**（域名），CLI 打开会记成无 referrer，别指望精确归因。

## 8. 风险与反模式

| 风险 | 应对 |
|---|---|
| GitHub DOM 改版 | 多信号 + `Unknown` 降级，永不报错 |
| attach 抢焦点 / 覆盖用户页面 | 只开新 tab，禁用 `goto`，提供 `--no-open` |
| 用户反感 | 上限 3 次、7 天间隔、`BROWSER4_NO_PROMO=1` |
| 被误指「AI 刷 star」 | 不发写请求、文档明说、SKILL.md 禁止 agent 代点 |
| 隐私 | `user-login` 只在本地用，不回传、不落日志 |

**反模式（明确不做）**：Star 换功能/换额度（易被判定 inauthentic）、在错误信息里推销、每条命令都提示、`--yes` 自动点、用 gh token 批量点、向 agent 的上下文里塞 CTA。

## 9. 分阶段落地

| 阶段 | 内容 | 状态 |
|---|---|---|
| P1 | `star` 命令 + 系统默认浏览器路径 + help/docs | ✅ |
| P2 | attach 感知 + 页面态检测 + state 文件 + fixture | ✅ |
| P3 | CTA（TTY 门禁 + 频控 + 退出开关） | ✅ |
| P4 | README / README.zh / CLI README / SKILL.md | ✅ |
| P5 | 真实 e2e 场景（`--scenario=test_e2e_star_*`） | ⬜ 待做，fixture 与 `BROWSER4_STAR_URL` 钩子已就位 |
| P6 | 发布 4 周后对比 star 增速 | ⬜ 需要时间 |

## 10. 验收标准

- [x] `browser4-cli star` 在 attach / 有 GUI / 无 GUI 三种环境下行为符合 §2（attach 与有 GUI 已实测；无 GUI 走纯提示）
- [x] 代码中不存在任何 star 写请求（`POST /star`、`gh api -X PUT user/starred`）—— 只调用 `browser_tabs` / `browser_evaluate` / `browser_navigate` 三个只读（相对 GitHub 而言）工具
- [x] `should_show_cta()` 门禁矩阵单测全绿；`BROWSER4_NO_PROMO=1` 全局静默
- [x] `cargo test --bin browser4-cli` 全绿（2,155 个），help 宽度/示例一致性 77 个测试全绿
- [x] `help.rs` / `tips.rs` / `SKILL.md` / `README(.zh)` / `cli/browser4-cli/README.md` 已更新
- [ ] 发布 4 周后 star 增速可与前 4 周对比

## 11. 已确认的决策

1. 「点赞」= **Star**，目标仓库只有 `platonai/Browser4`。
2. 「询问后一次性 attach 用户真实浏览器」：接受。实现上**只在没有会话时**才问 —— 已有会话时接管会顶掉用户正在用的浏览器，那比「无法校验点击」更糟。
3. `--gh`：**不做**。SSH 场景只把 `gh repo star platonai/Browser4` 递给用户自己敲（见 §2 P3）。
4. 同意不做「代点」：CLI 不发写请求，文档与 `SKILL.md` 都写明。

## 12. 实现记录

| 文件 | 内容 |
|---|---|
| `cli/browser4-cli/src/star_guide.rs`（新） | 三条路径、状态检测、`--wait` 轮询、CTA 门禁、`star-guide.json` 读写、21 个单测 |
| `cli/browser4-cli/src/main.rs` | `mod star_guide;`；`star` 在 server gate **之前**分发（打不开浏览器不该付一次冷启动）；成功命令尾部调用 `maybe_show_cta()` |
| `cli/browser4-cli/src/commands.rs` | `star` 的 `CommandDef`（`Category::Browsers`、`batch_supported: false`、`E2eCoverage::Excluded` 及原因） |
| `cli/browser4-cli/src/help.rs` | `star` 的三条 Notes + `CLI_EXAMPLES` 两条示例 |
| `cli/browser4-cli/src/tips.rs` | GENERAL 轮换里一条 `star` tip |
| `browser4-tests/.../static/b4/star-fixture.html`（新） | 页面 fixture，localStorage 模拟账号级 star 状态 |
| `skills/browser4-cli/SKILL.md` | 命令表新增 `star` 行，并写明「agent 不得代用户执行」 |
| `README.md` / `README.zh.md` / `cli/browser4-cli/README.md` | 一行 CTA / 命令说明 |

两处与提案不同的实现细节，都是端到端跑出来的：

1. **`--check` 会 reload 一个已经显示仓库的 tab**。只读 DOM 会读到「打开早于点击」的旧 tab，把已 star 误判成未 star；reload 之后页面才是账号的真实状态。它仍然不会新开 tab。
2. **`--check` 优先当前活动 tab**。`star` 每跑一次开一个 tab，两个仓库 tab 同时存在是正常的，挑第一个匹配会读到另一个 tab 的旧状态。
