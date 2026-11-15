# `browser4-cli star` 的 GitHub CLI 直连路径（2026-11-16）

> 需求（项目 owner）：「优化 `browser4-cli star`，允许 gh 已登录用户直接用 API 加星。」
> 前置设计见 [github-star-guidance-proposal.md](github-star-guidance-proposal.md)（原方案明确不做
> `--gh`，本次改判，边界见 §3）。

## 1. 要解决的问题

原 `star` 只有一条「人在浏览器里点」的路径：attach 用户浏览器 → 开新 tab → 轮询点击。在没有 GUI 的
机器（SSH / 容器 / CI）、或者用户本来就用 `gh` 管 GitHub 的场景下，这条路径要么走不到，要么比
`gh repo star` 还麻烦。而 `gh` 已经持有用户的凭据，`PUT /user/starred/{owner}/{repo}` 这一个请求就
能把事情办完。

## 2. 行为

| 命令 | `gh` 已登录 | 行为 | 退出码 |
|---|---|---|---|
| `star` | 是，终端是 TTY | 问一次 `Star … as @login through the GitHub CLI? [Y/n]`，回车即写；`n` 则回落浏览器路径 | 0 |
| `star` | 是，非 TTY（管道 / agent） | **不写**，打印一行「`… star --gh` 可走 API」，继续浏览器路径 | 0 |
| `star --gh` | 是 | 不问，直接 `PUT` | 0 |
| `star --gh` | 否 / 缺失 / 写失败 | 报错 + 下一步（`gh auth login` / 安装地址 / `gh` 的 stderr） | 2 |
| `star --no-gh` | 任意 | 完全不碰 CLI，只走浏览器路径 | 0 |
| `star --check` | 是 | 只 `GET`：204 → 已 star，404 → 未 star；不写、不开 tab | 0 |
| `star --check --gh` | 否 | 报错，而不是悄悄回落到页面 | 2 |
| `star --no-open` | 任意 | 只打印，不碰浏览器也不碰 API | 0 |
| 任意 | `--gh` + `--no-gh` 或 `--gh` + `--no-open` | 用法错误 | 2 |

CTA（`maybe_show_cta`）永远不写；`BROWSER4_STAR_URL` fixture 覆盖时 `gh_covers_url()` 为 false，
API 路径整体跳过 —— 测试永远不会碰到真实账号。

## 3. 边界（为什么这样做仍然是「真实 star」）

1. **只有用户自己的命令能写**：CTA、`--check`、`--no-open`、非交互且没有 `--gh` 的运行，全部零写入。
   交互式运行还要再回答一次 `[Y/n]`。
2. **`--gh` 是显式授权，不是默认值**：脚本 / agent 必须自己写出来；SKILL.md 明说 agent 不得代用户
   传这个 flag。
3. **单仓库、单次、幂等**：目标写死在 `platonai/Browser4`；`PUT` 本身幂等，已 star 时先读到的 204
   会直接终止，不重复写。批量 / 多账号路径不存在。
4. **不碰凭据**：CLI 不读 `~/.config/gh/hosts.yml`、不读 token；`gh` 自己解析 `GH_TOKEN` /
   `GITHUB_TOKEN` / keyring。

## 4. 实现

`cli/browser4-cli/src/star_guide.rs` 新增一节 "GitHub CLI (gh) — star through the REST API"：

| 项 | 说明 |
|---|---|
| `GhStatus` | `Missing` / `Unavailable { detail }` / `LoggedIn { login }` |
| `gh_status()` | `gh api user`：一次请求同时证明登录态并拿到 `login`（不解析 `gh auth status` 的本地文本） |
| `gh_star_state()` | `gh api /user/starred/platonai/Browser4`：204 = `Starred`，404 = `NotStarred`，其它 = `Unknown` |
| `gh_star()` | `gh api --method PUT /user/starred/platonai/Browser4`；幂等，无需读改写 |
| `decide_gh_action()` | 纯函数真值表：`no_gh` → Skip；已登录 + `--gh` → Star；已登录 + TTY → Ask；否则 Skip |
| `run_gh()` | spawn + 20 s 硬超时（`try_wait` 轮询，超时 kill），Windows `CREATE_NO_WINDOW`，stdout/stderr 全量捕获 |
| `gh_covers_url()` | 只对真实仓库 URL 生效，fixture 覆盖一律 false |
| `gh_page_state()` / `star_via_gh()` | `--check` 与写入两条流程；`--gh` 时失败即报错，否则回落页面路径 |

关键取舍：

- **`GET` 的 404 才代表「未 star」**。`gh api` 对 404 的退出码与网络/凭据错误一样都是非零，因此
  `classify_starred()` 只看 stderr 里的 `404` / `not found`；其它一律 `Unknown`，绝不把临时故障说成
  「你还没 star」。
- **先读后写**，但 `Unknown` 时不放弃写：`PUT` 幂等且权威，读失败不该让命令彻底失败。
- **超时**：`gh` 自己不做 HTTP 超时，网络黑洞会让命令挂死；20 s 后 kill 并按失败处理。
- **`record_and_report()` 增加 `via_gh`**：文案不再声称「你点了 Star」，JSON 增加
  `"method": "gh-api" | "browser"`，便于脚本区分。

## 5. 验证

- **单测**（`cargo test --bin browser4-cli star_guide`，29 个）：`gh_covers_url` 只覆盖真仓库、
  `log in` JSON 解析（含空 login / 非对象输入）、404 与网络错误的分类矩阵、`decide_gh_action` 真值表、
  `--gh`/`--no-gh` 解析与冲突、`first_line()` 截断。
- **e2e**（`cargo test --test e2e -- --scenario=test_e2e_mock_star_gh_api`）：桩 `gh`
  （Windows `.cmd` / Unix `sh`，通过 `BROWSER4_STAR_GH` 注入）验证：`star --gh` 先 `GET` 再 `PUT`、
  `star-guide.json` 记录 `starred_at`、第二次运行不再写、`--check` 把过期记录改回未 star、
  `--gh` 遇到缺失 `gh` 返回 2 + `USAGE_ERROR`。全程不碰真实账号，也不需要后端。
- **真实环境（只读）**：`gh` 2.86.0，账号 `platonai` 已 star 过该仓库，
  `browser4-cli star --check --json` 输出
  `{"method":"gh-api","page":"starred","login":"platonai"}` —— 不打开浏览器、不写任何东西。

## 6. 未做 / 后续

- 不支持 `GITHUB_TOKEN` 直连（不走 `gh`）：凭据来源应当唯一，`gh` 已经覆盖 env / keyring。
- 不做 unstar、不做批量、不做多仓库。
- 交互式确认目前只有 `[Y/n]`；若后续要「记住选择」，应当写进 `star-guide.json` 的显式字段，
  不能默认成 yes 让脚本继承。
