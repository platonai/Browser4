# browser4-cli Quick Reference (distilled)

> 常驻迷你版：嵌入 CLI 引擎系统提示词，压缩后仍可用。完整细节以
> `system.skillDoc("SKILL.md")` 与 `system.skillDoc("<topic>.md")` 为准。
> 本文档被压缩后，需要细节时重新加载 SKILL.md。

## Core Loop

```
1. OPEN        browser4-cli open --headless <url>   # headless 是 AI 代理默认
2. SNAPSHOT    browser4-cli snapshot -v 0           # 读页面、拿 ref（e5/e12…）
3. INTERACT    browser4-cli click <ref> | fill <ref> "<value>" | press Enter
4. RE-SNAPSHOT browser4-cli snapshot -v 0 --auto-diff   # 验证变化
5. EXTRACT     browser4-cli htmlsnapshot get text "<css>" | query --sql @f.sql
```

**Headless 默认**：除非用户明确要求可见窗口（"show me the browser"/"open visibly"），
一律 `--headless`。`goto` 继承 `open` 设定的显示模式。

## Copy-Paste Template

```bash
browser4-cli open --headless "https://example.com"
browser4-cli snapshot -v 0 --stdout        # 读页面；记录 refs
browser4-cli fill <ref> "<value>"
browser4-cli press Enter
browser4-cli wait --load networkidle
browser4-cli snapshot -v 0 --auto-diff --stdout   # 验证变化
browser4-cli htmlsnapshot get text "article" --all
```

`--stdout` 直接打印；默认写文件。交互后 CLI 自动快照；`--no-snapshot` 跳过省一次往返。

## Key Commands

| 目的 | 命令 |
|---|---|
| 导航/会话 | `open --headless <url>` / `goto <url>` / `close` / `reload`；多会话用 `-s <name>` |
| 读页面+拿 ref | `snapshot -v 0`（当前屏）/ `-v all`（整页）/ `-i`（仅交互元素）/ `snapshot grep <pattern>` |
| 交互 | `click` / `dblclick` / `hover` / `drag` / `fill` / `type` / `press` / `select` / `check` / `focus` / `key` |
| 提取 | `htmlsnapshot get text\|attr\|html "<css>" [--all]`；`query --sql @f.sql`（X-SQL 多字段）；`eval --json`（实时 DOM）；`extract`（自然语言，需 LLM key） |
| 结构化批量 | `crawl <url> --depth N --sql @f.sql`；`swarm create/query`（并行）；`loop`（定时重复） |
| 状态 | `state-save` / `state-load` / `cookie-*`；`attach`（连现有 Chrome） |
| 标签页 | `tab-list` / `tab-new <url>` / `tab-select <index>`（切后必须重新 snapshot） |
| 诊断 | `status` / `doctor`；`errors`（页面 JS 报错）；`vitals`（性能） |

## snapshot vs htmlsnapshot（关键决策）

| | `snapshot` | `htmlsnapshot` |
|---|---|---|
| 内容 | 无障碍树（AXTree）：角色/名称/ref | 原始 HTML DOM：全文 |
| 用途 | **交互**——拿 ref 去 click/fill | **提取**——读文本/数据/属性 |
| 判定 | "我要点按钮/找输入框" | "我要读文章/提取价格" |

`htmlsnapshot`（capture）**必须先执行一次**，`get/get all/inspect/grep/export` 才能用；
`query` 例外——独立重新抓取，不需要先 capture。JS 更新内容 → capture 后再提取；
`eval --json` 读实时 DOM。**每次导航/交互后重新 capture**，否则快照过期。

## Refs：一次性句柄

refs 是临时句柄：任何交互（click/fill/type/press/select/check/hover/drag）、
任何导航（goto/reload/切标签）后都可能失效。**安全循环 = 交互 → 重新快照 → 用新 refs**。
不要把 refs 存到跨导航使用。`generate-locator <ref>` 可生成抗变的 CSS 选择器。

## Critical Warnings

- **选择器会过期**：站点改版即失效。提取前先 `htmlsnapshot inspect` / `summary` 发现选择器，
  场景文档是模式不是配方。
- **Shell 引号（Windows）**：复杂 JS/SQL 用 `--sql @file.sql`、`--sql-stdin`、`eval --file`/`--stdin`/`--base64`；
  PowerShell 里 `@file` 路径要加引号（`--sql "@q.sql"`）。不要内联双引号 CSS 选择器。
- **分页输出**：`snapshot -v 0` 按屏读；`snapshot grep` 定位；`get html`/`grep` 默认 2K 行分页
  （`--page N` 翻页）。**不要 cat 快照文件**（可超 256KB）。
- **eval --ref 必须是箭头函数**：`element => element.textContent`；写成 `element.textContent`
  会返回 null——最常见错误。
- **对话框**：点击触发 `alert/confirm/prompt` 时 click 会超时；单独 `dialog-accept` /
  `dialog-dismiss`（或 `click --auto-dismiss-dialogs <ref>`）。
- **后台环境**：JVM 写日志被沙箱拒绝时，`open/goto` 会启动超时——设
  `BROWSER4_RUNTIME_DIR` / `BROWSER4_CLI_STATE_DIR` 到可写目录。
- **会话复用**：`--headless/--headed` 只在新建会话时生效；已运行会话忽略并告警。

## Extraction Decision Tree

```
要提取数据？
├─ 要先交互？ → snapshot + refs → 交互 → 重新 capture → 提取
├─ 静态页单字段 → htmlsnapshot get text "<sel>"
├─ 静态页多字段关联（标题+价格+URL）→ query 用 DOM_LOAD_AND_SELECT(@url,'.card')
├─ 动态/复杂 JS → eval --json
├─ 自然语言 → extract（需 LLM key）
└─ 大量页面 → crawl / swarm --sql
```

X-SQL 要点：CSS 选择器用**单引号**（`'h2'`）；`@url` 不引号；FROM 恒为
`DOM_LOAD_AND_SELECT(@url, '...')`；无 JOIN/CTE/子查询；先 `inspect` 发现选择器再写 SQL。

## 上下文纪律

- 优先 `snapshot -v 0 --stdout` 与定向 `htmlsnapshot get`，避免整页倾倒；
- 同一页反复查看时使用引用/差异（系统自动折叠）；需要强制重取时传 refresh 参数；
- 需要完整语法/参考时调用 `system.skillDoc("SKILL.md")` / `system.skillDoc("<topic>.md")`
  （snapshot、htmlsnapshot、x-sql、crawl、swarm、agent…）；
- 若 SKILL 文档内容已被压缩（checkpoint 中标注），重新用 `system.skillDoc` 加载。
