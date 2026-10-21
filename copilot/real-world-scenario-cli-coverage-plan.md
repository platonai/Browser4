# real-world-scenarios CLI 命令覆盖缺口与测试计划

> 状态：**审查通过，Wave 1 #1–#6 已实施；剩余 89 个命令已逐条审查（§9）；#7–#19 与 Wave 4 待排期**
> 生成时间：2026-08（基于当前工作区 `Browser4-4.14-feat`）
> 范围：`browser4-tests/real-world-scenarios/` 下的全部测试场景 vs `cli/browser4-cli` 的全部 CLI 命令

## 0. 审查结论（2026-08）

| 议题 | 决定 |
|---|---|
| 覆盖判定口径 | **采纳 T1/T2/T3 三档**；storage 等"行为覆盖但没写命令名"的场景保留 T2，不强制改写 |
| `code-*`（23 个 hidden 命令） | **纳入覆盖**：写一个 `coding-toolkit` 场景（Wave 2 #18），在临时副本上跑。注：§9 查实它们 hidden 且除 help.rs 外零文档 → 预期该场景会报"不可发现" |
| `uninstall` / `upgrade` / `install` | `install` / `upgrade` 只在 production 模式场景中跑（Wave 2 #19）；`uninstall` **拆分处置（第二轮审查修正）**：`uninstall --dry-run` 纳入 Wave 3，真正执行的 `uninstall -y` 永久排除进白名单 |
| 实施节奏 | **先做 Wave 1 的 #1–#6 试水**，验证新场景写法与验收断言后再批量铺开 |

实施进度（本轮完成）：

| # | 场景 | 状态 |
|---|---|---|
| 1 | `tasks/mock-site/network-observability.md` | ✅ 已落地 |
| 2 | `tasks/mock-site/console-errors-and-cdp.md` | ✅ 已落地 |
| 3 | `tasks/mock-site/iframes-and-popups.md` | ✅ 已落地 |
| 4 | `tasks/mock-site/file-upload-and-download.md` | ✅ 已落地 |
| 5 | `tasks/mock-site/device-and-network-emulation.md` | ✅ 已落地 |
| 6 | `tasks/mock-site/element-assertions-and-focus.md` | ✅ 已落地 |
| 7–12 | Wave 1 余下场景 | ⏳ 待实施 |
| 13–19 | Wave 2 | ⏳ 待实施 |
| — | Wave 4 既有场景加固 + `coverage.ps1` 守护 | ⏳ 待实施 |

顺带修正的既有问题：`README.md` 的任务计数与表格（32 → 47 个任务文件、generic 7 → 10、browser4 10 → 18、mock-site 13 → 19，补上 `agent-status-result-tracking.md`、`bot-stealth-check-md.md`、`amazon-cell-phone-60-pages.md`、`qunar.md` 等缺失行）；`tasks/real-world/generic/qunar.md` 缺少 `# heading`（按 README 约定补上 `# qunar-flight-search`，否则场景名为空）。

---

## 1. 检查范围与命令基线

### 1.1 被测场景（审计基线 45 个 → 当前 51 个）

| 类别 | 目录 | 场景数 | 说明 |
|---|---|---|---|
| MockSite | `tasks/mock-site/`（含 `decision-tree/`） | 13 → **19** | 依赖本地 MockSite `localhost:18080`；新增 6 个见 §0 |
| Browser4 专属 | `tasks/real-world/browser4/` | 18 | X-SQL / crawl / agent / loop / attach / plugin 等 |
| 通用 | `tasks/real-world/generic/` | 10 | 任意浏览器 agent 都能跑的真实网站任务 |
| 工作流脚本 | `tasks/workflow/*.ps1` | 4 | `agent.ps1` / `attach.ps1` / `tab.ps1` / `experience.ps1`（`run-all.ps1` 是 runner，非场景） |
| **合计** | | 45 → **51** | |

> 附注：`README.md` 里原先写的 "32 task files" 已过期，本轮一并修正为 47 个 `.md`（含 workflow 脚本共 51 个场景）。

### 1.2 命令基线

`cli/browser4-cli/src/commands.rs::all_commands()` 注册的 **201** 个 `CommandDef`
（不含由 `ToolSpec.cliName` 动态生成、由 `/mcp/tools/specs` 发现的插件命令）。

### 1.3 覆盖判定方法

对 45 个场景文件的全文（含 fenced code、inline code、`__CLI__ <cmd>` 调用）与 201 个命令做双向匹配
（短横线形式 + 空格形式，如 `htmlsnapshot-get` / `htmlsnapshot get`），再逐条人工裁决。
判定分三档：

| 档 | 含义 | 判定依据 |
|---|---|---|
| **T1 显式覆盖**（60） | 场景里直接写出了命令名（含 `__CLI__` 调用） | 例如 `__CLI__ tab-list`、`` `htmlsnapshot get text` ``、`swarm query --sql @file` |
| **T2 行为覆盖**（26） | 场景明确要求该能力，但没写出命令名，agent 必须自己去发现 | 例如 storage 场景说 "Set a cookie named session_id … `--httpOnly`"，但没写 `cookie set` |
| **T3 未覆盖**（115） | 45 个场景里既没有命令名，也没有对应行为要求 | 本文档的缺口清单 |

自动化匹配脚本与中间产物（`extract-coverage.ps1`、`extract-code-coverage.ps1`、`classify.ps1`、`coverage2.csv`、`evidence.csv`、`t3-uncovered.csv`）当前只是本次审计的临时文件，**没有提交进仓库**；实施阶段应把解析+匹配逻辑正式收进 `scripts/coverage.ps1`（见第 6 节）以便 CI 复用。
匹配结果已对以下假阳性做过人工剔除：`key`（LLM **API key**）、`type`（`captureType` / "type of task"）、`cdp`（`attach --cdp` 参数、**CDP port** 文本）、`install`（`webminer install`）、`download`（插件工具 `image.download`）、`errors`（PowerShell `$ErrorActionPreference`）、`set`（"set cache duration" / "result set"）、`config`（"session config"）、`stop`（`loop --stop` / PowerShell `Stop`）、`session-default`（输出文案 "Using existing session DEFAULT"）、`is-visible`（"content is visible"）、`check`（"check the result"，但 `check` 命令本身由 form-filling 场景行为覆盖 → T2）。

---

## 2. 结论总览

审计基线（Wave 1 #1–#6 之前，45 个场景）：

```
201 CLI 命令
├── T1 显式覆盖  60  (29.9%)  ← 场景里点名了命令
├── T2 行为覆盖  26  (12.9%)  ← 要求了行为，命令名靠 agent 发现
└── T3 未覆盖   115  (57.2%)  ← 45 个场景一个都没碰
                       ├── 27 个在 CLI e2e 里也是 E2eCoverage::Excluded → 全链路零覆盖
                       └── 88 个只有 CLI e2e（mock/real）功能覆盖，真实场景层零覆盖
```

加入 Wave 1 #1–#6（6 个新场景、51 个场景）之后重新测量：

```
201 CLI 命令
├── T1 显式覆盖  87  (43.3%)   (+27)
├── T2 行为覆盖  25  (12.4%)   （check 从 T2 升为 T1）
└── T3 未覆盖    89  (44.3%)   (-26)
                       ├── 27 个仍是全链路零覆盖（含 uninstall / skills / skill / plugin / doctor / act …）
                       └── 62 个只有 CLI e2e 功能覆盖
```

**关键判断：真实场景层从覆盖 42.8% 提升到 55.7%，缺口形状不是"零散漏了几个"，而是整族缺席**：
`code-*`（23）、`config-*`（5）、`skills`+`skill-*`（10）、`plugin-*`（4）、`doctor*`+ 生命周期（11）、
鼠标键盘原语（8）、`crawl` 任务控制（5）、`scrape`/`webdb`（4）、profiler/vitals（4）、snapshot 生命周期（3）。

---

## 3. 已覆盖清单（供审查核对）

### 3.1 T1 显式覆盖（60）

| 类别 | 命令 |
|---|---|
| Agent (7) | `agent run/status/result/list`、`experience save/query/list/deep-learn` |
| Agent 提取 (2) | `extract`、`summarize` |
| Browsers (7) | `open`、`close`、`close all`、`attach`、`list`、`kill all`、`status` |
| Core (5) | `get`、`eval`、`snapshot`、`snapshot grep`、`loop` |
| Keyboard/Mouse (5) | `press`、`fill`、`click`、`hover`、`drag` |
| Navigation (5) | `goto`、`go back`、`go forward`、`reload`、`page-info` |
| Export (3) | `screenshot`、`pdf`、`resize` |
| Snapshot (8) | `htmlsnapshot`、`htmlsnapshot get`、`get all`、`query`、`export`、`inspect`、`summary`、`grep` |
| Swarm (7) | `crawl`、`crawl list`、`swarm create/submit/query/status/result` |
| Tabs (4) | `tab-list`、`tab-new`、`tab-select`、`tab-close` |
| WebMiner (3) | `webminer`、`webminer all`、`webminer install` |
| Core 交互 (3) | `dialog-accept`、`dialog-dismiss`、`generate-locator` |

### 3.2 T2 行为覆盖（26，命令名未出现在场景里）

`check`、`select`、`wait`、`scroll`、`mousewheel`、`dblclick`、
`state save`、`state load`、
`cookie list/get/set/delete/clear`、
`localstorage list/get/set/delete/clear`、
`sessionstorage list/get/set/delete/clear`、
`htmlsnapshot capture`、`swarm list`、`swarm close`。

> storage 15 个命令全部落在 T2：`storage-state-management.md` 把行为写得很完整（domain/path/httpOnly/secure/sameSite/expires 全覆盖），
> 但一个命令名都没写。这既是**好场景**（考发现能力），也是**覆盖风险**（agent 换个写法就测不到命令本身）。

---

## 4. 缺口清单（审计基线：115 个未覆盖命令）

> 本节表格是**审计基线**（Wave 1 #1–#6 之前）。网络族、DevTools 诊断族、frame/window、upload/download、
> `set`/`delete-data`、`is-*`/focus/scrollintoview/highlight/pushstate 共 26 个命令已由 #1–#6 覆盖；
> 当前剩余缺口见 §4.2。

| 类别 | 数量 | 未覆盖命令 |
|---|---|---|
| **Network** | 7 | `network requests`、`network request`、`network route`、`network unroute`、`har start`、`har stop`、`download` |
| **DevTools** | 9 | `console`、`errors`、`cdp`、`tool call`、`set`（geo/offline/headers/media/device）、`profiler start`、`profiler stop`、`vitals`、`web vitals` |
| **Core** | 14 | `batch`、`upload`、`uncheck`、`focus`、`highlight`、`scrollintoview`、`pushstate`、`is visible`、`is enabled`、`is checked`、`dialog-status`、`delete-data`、`snapshot list`、`snapshot clean` |
| **Mouse** | 3 | `mousemove`、`mousedown`、`mouseup` |
| **Keyboard** | 5 | `keyboard`、`key`、`keydown`、`keyup`、`type` |
| **Tabs** | 3 | `frames`、`frame`、`window new` |
| **Snapshot** | 3 | `diff-snapshot`、`htmlsnapshot algorithms`、`htmlsnapshot readability` |
| **Swarm（crawl 任务控制）** | 5 | `crawl status`、`crawl result`、`crawl cancel`、`crawl clear`、`crawl resume` |
| **Storage** | 2 | `webdb export`、`webdb normalize` |
| **Export** | 2 | `scrape`、`scrape formats` |
| **Browsers** | 8 | `doctor`、`doctor log`、`doctor metrics`、`doctor status`、`stop`、`session-default`、`profiles list`、`profile import` |
| **Install** | 3 | `install`、`uninstall`、`upgrade` |
| **Config** | 5 | `config`、`config list/get/set/delete` |
| **Skills** | 5 | `skills`、`skills list/get/path/unpack` |
| **Skill** | 5 | `skill list/info/install/uninstall/reload` |
| **Plugins** | 4 | `plugin list/info/install/remove` |
| **Code** | 23 | `code read/write/append/replace/delete/copy/move/list/stat/glob/grep/mkdir/diff/changes/shell/scaffold/validate/mvn/run/devtask/impact/workspace/javap` |
| **Agent** | 3 | `chat`、`chat result`、`agent cancel` |
| **WebMiner** | 5 | `webminer update/version/uninstall/run-example/views` |
| **Act** | 1 | `act` |

### 4.1 全链路零覆盖（27 个，同时是 `E2eCoverage::Excluded`）

```
uninstall
skills, skills list/get/path/unpack                (5)
skill list/info/install/uninstall/reload           (5)
plugin list/info/install/remove                    (4)
webminer update/version/uninstall/run-example/views (5)
batch, snapshot list, snapshot clean
doctor, doctor log, doctor metrics
act
```

这些命令**既没有功能 e2e，也没有真实场景**——CLI 的整个"自管理/发现"曲面（skills、plugins、doctor、webminer 运维）目前只靠单元测试兜底。
（注意 `doctor status` 与 `upgrade` 不属于此列：它们在 CLI e2e 里标为 `Tested`，只是真实场景层没覆盖。）

### 4.2 当前剩余缺口（89 个，Wave 1 #1–#6 之后）

| 类别 | 数量 | 未覆盖命令 |
|---|---|---|
| **Code** | 23 | `code read/write/append/replace/delete/copy/move/list/stat/glob/grep/mkdir/diff/changes/shell/scaffold/validate/mvn/run/devtask/impact/workspace/javap` |
| **Browsers** | 8 | `doctor`、`doctor log`、`doctor metrics`、`doctor status`、`stop`、`session-default`、`profiles list`、`profile import` |
| **Skill/Skills** | 10 | `skills`、`skills list/get/path/unpack`、`skill list/info/install/uninstall/reload` |
| **Keyboard** | 5 | `key`、`keyboard`、`keydown`、`keyup`、`type` |
| **Config** | 5 | `config`、`config list/get/set/delete` |
| **Swarm（crawl 任务控制）** | 5 | `crawl status/result/cancel/clear/resume` |
| **WebMiner** | 5 | `webminer update/version/uninstall/run-example/views` |
| **Plugins** | 4 | `plugin list/info/install/remove` |
| **DevTools** | 4 | `profiler start`、`profiler stop`、`vitals`、`web vitals` |
| **Mouse** | 3 | `mousemove`、`mousedown`、`mouseup` |
| **Agent** | 3 | `chat`、`chat result`、`agent cancel` |
| **Install** | 3 | `install`、`uninstall`、`upgrade` |
| **Snapshot** | 3 | `diff-snapshot`、`htmlsnapshot algorithms`、`htmlsnapshot readability` |
| **Core** | 3 | `batch`、`snapshot list`、`snapshot clean` |
| **Storage** | 2 | `webdb export`、`webdb normalize` |
| **Export** | 2 | `scrape`、`scrape formats` |
| **Act** | 1 | `act` |

> Wave 1 余下 6 个场景（#7–#12）预计再关闭约 20 个命令（键鼠原语 8、crawl 任务控制 5、
> snapshot 生命周期 3、config 5、skills+plugins 14、doctor 族 8 → 部分与 Wave 2 重叠）。

---

## 5. 测试计划：新增场景

设计原则：

1. **一个场景考一族命令**，用真实工作流串起来（不做 115 个微场景）；
2. **优先 MockSite + `static/b4` 现成 fixture**，保证确定性、可离线、可重复；现有 fixture 已足够，标题另注需要新增 fixture 的项；
3. 每个场景都必须写出 **Acceptance Criteria**（照 `decision-tree/*.md` 的样式），把"命令被调用"变成可判定的断言；
4. 场景名 / 目录遵循现有约定：`tasks/mock-site/` 或 `tasks/real-world/browser4/`，文件名即 `# heading`。

### Wave 1（P0）：高价值、可本地确定性复现、当前零覆盖

> **#1–#6 已实施**（文件已落地，见 §0 进度表与 §4.2 复测结果）；下表保留完整计划，作为 #7–#12 的实施依据与 #1–#6 的实施记录。

| # | 场景文件 | 覆盖命令 | 目标页面（已存在） | 关键断言 |
|---|---|---|---|---|
| 1 | `tasks/mock-site/network-observability.md` | `network requests/request/route/unroute`、`har start`、`har stop` | `b4/network-fixture.html`（自带两次 fetch）、`/json`·`/text`·`/csv` 端点（HAR 内容捕获）、`/ec/` 列表页（多子资源） | ① `network requests --type xhr,fetch` 列出 fixture 的两次 fetch（MockSite 不提供 `/api/*`，故两者都是 404；`--status 4xx` 过滤应命中两条）；② `network request <id>` 返回 headers/body；③ `network route "**/api/network-endpoint-ok.json" --body '{"routed":true}'` 后页面 `#results` 出现 mock body，`unroute` 后恢复 404；④ `network route … --abort` 使请求失败；⑤ `har start --content text` → 触发流量 → `har stop <path>` 落盘非空，`log.entries[]` 含目标 URL |
| 2 | `tasks/mock-site/console-errors-and-cdp.md` | `console`、`errors`、`cdp`、`tool call`、`dialog-status` | `b4/console-probe-fixture.html`、`assets/consolelog.html`、`assets/error.html`、`generated/interactive-1.html` | ① `console --min-level warn` 只回 warning/error；② 未捕获异常出现在 `errors`；③ `--clear` 后再读为空；④ `cdp Runtime.evaluate` 返回值与 `eval` 一致；⑤ `tool call <name>` 与等价命令结果一致；⑥ `dialog-status` 在未处理的 confirm 后报告 pending，`dialog-accept` 后清空 |
| 3 | `tasks/mock-site/iframes-and-popups.md` | `frames`、`frame`、`window new` | `b4/frame-switch.html`（pay/other/nested）、`b4/frame-nested.html`、`assets/popup/window-open.html` | ① `frames` 列出全部 iframe（name + url）并标记 active；② `frame "#pay-frame"` 后可操作 frame 内元素，切回 main 后主文档元素才可解析（负向断言）；③ 嵌套 frame 路径可切换；④ `window new <url>` 后 `tab-list` 能看到新目标并 `tab-select` 接管 |
| 4 | `tasks/mock-site/file-upload-and-download.md` | `upload`（单/多）、`download`、`wait --download` | `assets/input/fileupload.html`、`fileupload-multi.html`、`folderupload.html`、`b4/download-test.html`、`assets/download-blob.html` | ① 单文件/多文件上传后 `eval` 读 `input.files` 长度与名称；② 不存在的路径**大声失败**（非零退出 + 明确报错）；③ `download --dir` 指定的目录里出现文件，`wait --download` 在其完成时才返回 |
| 5 | `tasks/mock-site/device-and-network-emulation.md` | `set geo/offline/headers/media/device`、`delete-data` | `assets/geolocation.html`、`assets/mobile.html`、`assets/highdpi.html`、`b4/network-fixture.html` | ① `set geo --lat/--lon` 后页面 `navigator.geolocation` 返回设定值；② `set offline on` 后 `goto` 失败、`off` 后恢复；③ `set headers --json '{"X-B4-Test":"1"}'` 后用 `network request <id>` 读回，自定义头出现在请求头里；④ `set media --color-scheme dark` 改变 `matchMedia`；⑤ `set device --width/--height/--dpr/--mobile` 改变 `innerWidth`/`devicePixelRatio`，`--dpr 2` 能触发 `assets/highdpi.html` 的 1.5x 媒体查询；⑥ `delete-data` 后 cookie/storage 清空 |
| 6 | `tasks/mock-site/element-assertions-and-focus.md` | `is visible/enabled/checked`、`check`、`uncheck`、`focus`、`scrollintoview`、`highlight`、`pushstate` | `generated/form-filling.html`、`b4/mcp-tool-controller-form-fixture.html`、`assets/input/scrollable.html`、`assets/historyapi.html` | ① 断言命令在"应为真/应为假"两种情形下都有确定退出码；② `uncheck` 后 `is checked` 为假；③ `focus` 后 `document.activeElement` 正确；④ `scrollintoview` 后元素进入视口（用 `get box` 判定）；⑤ `highlight` 有可见覆盖层（截图佐证）；⑥ `pushstate` 改 URL 不触发导航 |
| 7 | `tasks/mock-site/keyboard-and-mouse-primitives.md` | `keyboard`、`key`、`keydown`、`keyup`、`type`、`mousemove`、`mousedown`、`mouseup`、`mousewheel`、`dblclick` | `b4/mcp-tool-controller-keyboard-fixture.html`、`b4/mcp-tool-controller-mouse-fixture.html`、`generated/interactive-5.html`、`assets/input/scrollable.html` | ① 修饰键组合（`keydown Shift` + `type abc`）产出大写；② `keydown`/`keyup` 在 fixture 状态里留下不同记录；③ `mousedown`→`mousemove`→`mouseup` 构成选择/拖拽而无 click；④ `mousewheel dy` 精确滚动可滚动容器；⑤ `dblclick` 使 fixture 计数器 +1 |
| 8 | `tasks/mock-site/snapshot-lifecycle.md` | `snapshot list`、`snapshot clean`、`diff-snapshot`、`htmlsnapshot capture`、`htmlsnapshot algorithms`、`htmlsnapshot readability` | `/ec/dp/B0E000001`、`generated/interactive-1.html` | ① `snapshot list -n` 列出刚生成的快照；② `snapshot clean --keep N --dry-run` 只报告不删除，实际执行后剩余 N 个；③ `diff-snapshot <before> <after>` 精确报告新增/移除元素；④ `htmlsnapshot capture` 显式落盘；⑤ `algorithms`/`readability` 输出结构可读且与 SKILL 文档一致 |
| 9 | `tasks/mock-site/crawl-job-control.md` | `crawl status/result/cancel/clear/resume` | `/generated/crawl/index.html`（depth 2–3，页数足够） | ① `--background` 返回任务 ID，`crawl status` 状态机流转（queued→running→done）；② `crawl cancel` 后状态终止、无残留进程；③ `crawl result` 返回结果负载；④ 中断后用 `crawl resume` 从 checkpoint 继续（对照 `docs/crawl-checkpoint-resume.md`）；⑤ `crawl clear` 清空任务表 |
| 10 | `tasks/mock-site/config-management.md` | `config`、`config list/get/set/delete` | 任意 MockSite 页面（无需浏览器） | ① `config set/get` 往返一致；② `config list` 展示有效值与来源；③ 未知键报错清晰；④ `config delete` 后回落默认值；⑤ 改动不破坏后续命令 |
| 11 | `tasks/mock-site/skills-and-plugins.md` | `skills`、`skills list/get/path/unpack`、`skill list/info/install/uninstall/reload`、`plugin list/info/install/remove` | 本地 `skills/browser4-cli`、`browser4-plugins/` | ① `skills path` 指向真实目录、`skills get` 打印 SKILL.md；② `skills unpack --dest` 落盘完整；③ `skill install`（临时目录）→`list`→`reload`→`uninstall` 闭环；④ `plugin list` 列出 5 个内置插件；`plugin info` 字段完整；⑤ 安装/移除本地插件目录后列表一致 |
| 12 | `tasks/real-world/browser4/runtime-diagnostics.md` | `doctor`、`doctor log/metrics/status`、`stop`、`status`、`session-default`、`profiles list`、`profile import` | 本地环境（无需外网） | ① `doctor` 每项检查给出 pass/fail/warn + 可执行建议；② `doctor log <name> grep ERROR -i` 命中；③ `stop` 后无残留进程（`status` 佐证）；④ `session-default` 切换后后续命令落在默认会话；⑤ `profiles list` 枚举 Chrome/Edge profile；⑥ `profile import --list-sources` 列出源，未开 `profileimport.allow.passwords` 时拒绝导入密码 |

### Wave 2（P1）：中价值

| # | 场景文件 | 覆盖命令 | 目标 / 断言要点 |
|---|---|---|---|
| 13 | `tasks/mock-site/profiler-and-vitals.md` | `profiler start/stop`、`vitals`、`web vitals` | `generated/interactive-dynamic.html`；profile 文件可解析（`.cpuprofile` nodes/samples 非空）；vitals 在无 CDN 时有降级提示 |
| 14 | `tasks/mock-site/scrape-and-webdb.md` | `scrape`、`scrape formats`、`webdb export`、`webdb normalize` | `/ec/dp/B0E000001`；`scrape` 无需会话即可返回文本/HTML；`scrape formats` 列出格式；`webdb export` 产出文件、`normalize` 幂等 |
| 15 | `tasks/mock-site/batch-mode.md` | `batch`（正向） | `generated/form-filling.html`；`batch` 一次提交多步、`--bail` 首错即停、`--json` 输出可解析、HTTP 往返次数下降（对照 amazon.md 目前只做"禁止使用"的负向覆盖） |
| 16 | `tasks/mock-site/act-adaptive-actions.md` | `act` | `generated/tta/act/act-demo.html`（现成 fixture）；隐藏实验命令，先确认是否需要公开 |
| 17 | `tasks/real-world/browser4/agent-chat-and-cancel.md` | `chat`、`chat result`、`agent cancel` | 需 LLM key；无 key 时记录 "environment-blocked"；`agent cancel` 对长任务生效并给出终态 |
| 18 | `tasks/real-world/browser4/coding-toolkit.md` | `code *`（23） | 仓库工作区 scratch 目录；`code write/read/append/replace/diff/changes/glob/grep/stat` 往返；`code shell`/`mvn`/`validate`/`scaffold` 限定在临时副本上跑（避免污染仓库） |
| 19 | `tasks/real-world/browser4/install-upgrade-lifecycle.md` | `install`、`upgrade` | **production 模式**（`run-task-production.ps1`）；已安装时 install 幂等、`upgrade --tag` 行为、镜像回退；`uninstall` 单列且默认不进 CI（破坏性） |

### Wave 3（P2）：需产品裁决

| 命令 | 建议 |
|---|---|
| `uninstall` | 执行路径 `uninstall -y` 保持 Excluded：破坏全局安装，仅保留 `tests-production` 层；在覆盖守护白名单里写明理由。**但 `uninstall --dry-run` 是非破坏路径，可覆盖**（修正 §8 第 3 条的粗判，见 §9.1 发现 8）。另注：`browser4-tests/tests-production/local-commands.ps1` 已覆盖其余 server-free 本地命令，此处不重复 |
| `cdp`、`tool call`、`act` | 低层"逃生舱"/实验命令：`cdp` 与 `tool call` 并入场景 #2；`act` 视是否转正决定 |
| `keyboard`、`key`、`type` | 与 `press`/`fill` 语义重叠的别名：并入场景 #7，不单列 |
| `window new` | 并入场景 #3（与 popup 同源） |
| `webminer update/version/run-example/views` | 依赖外部 jar/网络：以 `webminer install/run-example` 做"存在性 + 失败可读"断言。**`version` 已由 `tests-production/local-commands.ps1` 机械覆盖**（只断言"不启动后端" + banner，不断言退出码，因为"最新版本"查询受网络影响） |
| `webminer-uninstall` | ✅ **已裁决接受删除用户级 `~/.scent/webminer`**（§8 第 12 条）→ **纳入 Wave 3 真实场景覆盖**，不进白名单。场景需在临时/受控环境下先 `webminer install` 造出 `~/.scent/webminer`，再 `uninstall` 并断言目录被移除与提示可读；不得删除其他用户数据 |

### Wave 4：既有场景加固（T2 → T1）

| 文件 | 改动 | 理由 |
|---|---|---|
| `tasks/mock-site/storage-state-management.md` | 保留发现性质，但在验收区显式列出应被用到的命令（`cookie set/get/delete/clear/list`、`localstorage *`、`sessionstorage *`、`state save/load`） | 行为已覆盖 15 个命令，但一个命令名都没出现；补一行验收项即可让覆盖率可被机械校验 |
| `tasks/real-world/generic/visual-screenshot-controls.md` | 验收区点名 `scroll` / `mousewheel`、`wait` | 现在只有动词描述 |
| `tasks/mock-site/advanced-mouse-interaction.md` | 验收区点名 `dblclick`、`hover`、`drag`、`is visible` | 同上 |
| `tasks/mock-site/form-filling.md` | 验收区点名 `check` / `select` / `press` / `fill` / `wait` | 同上 |
| `tasks/real-world/browser4/*`（crawl/swarm 类） | 验收区点名 `crawl list`、`swarm list`、`swarm close` | 同上 |

---

## 6. 覆盖率守护机制（建议与场景一起落地）

CLI e2e 侧已有 `verify_e2e_command_coverage` 做"标记 ↔ 场景"自审计；真实场景层目前**完全没有**守护，
所以这次审计出的 115 个缺口随时会再次漂移。建议新增：

**`browser4-tests/real-world-scenarios/scripts/coverage.ps1`**

- 从 `cli/browser4-cli/src/commands.rs::all_commands()` 解析命令名（与 CLI e2e 的解析口径一致）；
- 扫描 `tasks/**/*.md` + `tasks/workflow/*.ps1`（短横线 + 空格两种形式）；
- 输出 `T1 / T2 / T3` 三档清单，`-List` 打印缺口，`-Fail` 在存在非白名单缺口时非零退出；
- 白名单 `scripts/coverage-exclusions.psd1`：每条写明命令 + 理由（destructive / hidden-experimental / needs-external-service），与 `docs-dev/cli-e2e-test-coverage-analysis.md` 的 Excluded 理由对齐；
- **按 §9 的审查结果**：Wave 1–3 全部落地后，白名单应只剩少数几条——至少 `webminer-uninstall`（若决定不接受删除用户级 `~/.scent/webminer`；理由 not-sandboxable）。`uninstall` 因为会覆盖 `--dry-run` 路径，不留在白名单里；`vitals`/`chat`/`act`/`install`/`upgrade` 属于"需要外部资源"，用**标签**而非白名单表达（缺外部资源时应记录 environment-blocked，而不是静默跳过）；
- 建议同时校验反向一致性：场景里写出的命令名必须存在于 registry（防 stale，例如把 `dialog status` 这类不存在的空格形式抓出来）；
- 接入方式（二选一，待审查决定）：
  - a) 作为 `common.tests.ps1` 同级的 Pester/自研断言，在 `bin/test.ps1` 的某个 target 里跑；
  - b) 仅作为手动审计脚本 + CI nightly 的只读检查。

**收益**：新增命令若没有场景，`coverage.ps1` 立刻报出来；反过来，场景里写了不存在的命令也会被抓到（防 stale）。

---

## 7. 验收标准与运行方式

每个新场景的 Definition of Done：

1. `pwsh ./bin/test.ps1 rws scenarios --list` 能看到新场景，`-Category` 过滤正确；
2. 单场景跑通：`pwsh ./bin/test.ps1 rws sc <name>`（MockSite 场景先 `pwsh ./bin/test.ps1 mock-site`）；
3. 场景内含 **Acceptance Criteria** 段落，且每条都能被 agent 以"命令 + 输出证据"回答；
4. agent 报告里能给出每条断言的实际输出（不是"看起来没问题"）；
5. `coverage.ps1` 中这些命令从 T3 消失（或进入白名单并有理由）；
6. `README.md` 的任务表与计数同步更新；
7. 场景自身不得污染仓库：临时文件写 `./target/` 或 `$env:TEMP`，结束时清理。

运行入口参考：

```powershell
pwsh ./bin/test.ps1 mock-site                      # 起 MockSite (18080)
pwsh ./bin/test.ps1 rws scenarios --list           # 列场景
pwsh ./bin/test.ps1 rws sc network-observability   # 跑单个新场景
pwsh ./bin/test.ps1 rws dir mock-site              # 跑整类
pwsh ./browser4-tests/real-world-scenarios/scripts/coverage.ps1 -List   # 覆盖审计
```

---

## 8. 风险、成本与待决问题

| # | 问题 | 状态 / 决定 |
|---|---|---|
| 1 | **判定口径**是否接受 T1/T2/T3 三档？storage 15 个命令目前记 T2 | ✅ **已裁决：接受三档**，storage 等保持 T2，不做强制改写 |
| 2 | **`code-*` 23 个命令**是否值得占用一个真实场景？ | ✅ **已裁决：纳入**，写 Wave 2 #18 `coding-toolkit` 场景（在临时副本上跑） |
| 3 | **破坏性命令**（`uninstall`、`upgrade`）是否纳入？ | ✅ **已裁决：`uninstall` 永久排除**（进守护白名单）；`install`/`upgrade` 只走 production 模式场景（#19）。⚠️ **本条已被 §9.1 发现 8 部分修正**：`uninstall --dry-run` 是非破坏路径，可覆盖；只有 `uninstall -y` 排除。见 §5 Wave 3 |
| 4 | **预算**：Wave 1 共 12 个新场景，每个 5–30 分钟 | ✅ **已裁决：先做 #1–#6 试水**（本轮完成），#7–#12 待 #1–#6 的实测反馈后再排期 |
| 5 | **是否需要新增 fixture** | 已核实：#1–#6 **未新增任何静态 fixture**，全部复用现成页面。两点限制已写进场景正文：① MockSite 不提供 `/api/network-endpoint-*.json` 路由，`network route` 的 mock 即"200 正常返回"的证据来源；② `download-test.html` 的 `/files/download-me.txt` 也是 CLI e2e FixtureServer 专属路由，故 #4 改用自包含的 `assets/download-blob.html`（Blob 下载，无需服务端路由） |
| 6 | **是否同步补 CLI e2e** | ⏳ 未决：本计划只覆盖 real-world 层。89 个命令中 27 个连 mock e2e 都没有，是否补 mock 场景需另行决策 |
| 7 | README 计数与表格已过期 | ✅ 本轮已修正（32 → 47 个 `.md`；generic 7 → 10；browser4 10 → 18；mock-site 13 → 19；补齐缺失行） |
| 8 | **覆盖守护脚本的接入方式**（§6 的 a/b 选项） | ✅ **已裁决（方案 a + 放宽路径过滤）**：`scripts/coverage.tests.ps1` 已被 `bin/test.ps1 ps` 自动发现，本轮同时给 `.github/workflows/ps1-tests.yml` 的**两处** `paths` 补上 `cli/browser4-cli/src/commands.rs` 与 `browser4-tests/real-world-scenarios/tasks/**`——否则"制造缺口的那个改动"自身不触发守护，只剩 nightly 兜底。CI 事实见 §10.2 |
| 9 | **`code-*` 的文档定位**（§9 发现 hidden + 除 help.rs 外零文档） | ✅ **已裁决（方案 b）**：明确为"agent 内部工具面"，不补 `references/coding.md`、不改 hidden，**不再要求真实场景覆盖**。理由与处置写在 `scripts/coverage-exclusions.psd1` 的 `code-*` 条目里；功能覆盖留在 CLI e2e 的 coding tool family mock 场景 |
| 10 | **`snapshot-list`/`snapshot-clean` 标记注释与派发不一致** | ✅ **已修复**（方案 A）：两个 `tool_name_fn` 由死元数据 `snapshot_list`/`snapshot_clean` 改为 `String::new()`，并把两条命令补进 `cli/browser4-cli/src/commands.rs` 的 `test_local_only_commands_have_empty_tool_name` 名单（单测已通过） |
| 11 | **14 个"不需要浏览器/后端"的本地命令**（§9 发现） | ✅ **已裁决：加 shell 断言做机械校验**，落地为 `browser4-tests/tests-production/local-commands.ps1`（新 `local` 类别，24 条断言，离线、无浏览器、无后端）。**同时修正了原清单**：实测 `diff-snapshot`/`profiles-list`/`batch` 会拉起后端，不属于本类；真正的 server-free 集合与逐项取舍见 §8.1 |
| 12 | **`webminer-uninstall` 是否接受删除用户级 `~/.scent/webminer`** | ✅ **已裁决：接受删除** → 纳入 Wave 3 的真实场景覆盖（见 §5），不再进白名单；`uninstall -y` 仍保持排除 |

### 8.1 §8 第 11 条：server-free 命令的实测清单（2026-10-10）

原判"14 个既不需要浏览器也不需要后端"的命令**清单有误**。用全局安装版 CLI 逐个实测（`browser4-cli 4.14.0-rc.8`），以输出里是否出现 `Starting Browser4 dev server` 为准：

| 命令 | 实测 | 结论 |
|---|---|---|
| `config list/get/set/delete`（+ hidden `config-list`） | 读写本地 `~/.browser4/workspaces/<ws>/config.json` | ✅ server-free |
| `skills list/get/path/unpack` | 读二进制内置 skill + 写目标目录 | ✅ server-free |
| `snapshot list`、`snapshot clean` | 读/删 `<cwd>/.browser4-cli/snapshot` | ✅ server-free（与 §10.1 的派发结论一致） |
| `webminer version` | 本地 jar + 可选"最新版本"查询 | ✅ server-free（退出码受网络影响，故只断言"不启动后端"+ banner） |
| `stop` | 停本 workspace 后端 | ✅ 不需要浏览器，但**已被 `stress-install.ps1` 覆盖**，不重复 |
| `install` / `upgrade` | 下载 runtime bundle | ⚠️ 无浏览器，但有网络重依赖；归 Wave 3 #19 |
| `diff-snapshot`、`profiles-list`、`batch` | **会打印 `Starting Browser4 dev server`** | ❌ 需要后端，应归 LLM 场景而非本类 |
| `session-default` | 缺参数时先报错，未验证 | ⚠️ 待 Wave 1 #12 场景确认 |

落地的断言口径（`local-commands.ps1`）：

1. **server-free 契约**——这些命令的输出不得出现任何后端启动 banner（标记串写死，换措辞会显式失败而不是静默通过）；
2. **行为**——`config` 全量 set→get→delete 往返（`try/finally` 还原原值）、`skills list/get/path`、`skills unpack <临时目录>`、`snapshot list/clean --dry-run`；
3. **空格形式契约**——hidden 子命令（`config-list`/`skills-list`/`skills-path`）必须拒绝 kebab 拼写、exit 2、并提示 `Use 'browser4-cli config list' instead`。这条同时是守护脚本 Check E 判定"场景里的命令 token 是否可用"的机械依据。

安全取舍：`snapshot clean` 只跑 `--dry-run`（否则删用户快照）；`skills unpack` 只写临时目录（否则覆盖用户 skills 目录）；`stop`/`install`/`upgrade` 不在本脚本内执行。

---

## 9. 逐条审查结论：剩余 89 个命令（第二轮审查）

对 T3 的 89 个命令逐个核对了五件事：**是否 hidden**、**CLI e2e 标记**、**派发方式**（MCP 工具 / REST / 纯本地）、**文档命中**（SKILL / references / help.rs / docs）、**外部前置条件**，并给出族级处置与前缀 fixture 核实结果。

### 9.1 本轮新增结论（10 条）

1. **29 / 89 是 `hidden: true`** —— 在 `help` 输出里根本看不到：23 个 `code-*` + `skills-list/get/path/unpack` + `config-list` + `act`。对这些命令，"可发现性"评价本身就是一个结论（预期报 finding），不是场景缺陷。
2. **23 个 `code-*` 只在 `help.rs` 里出现过**（自动生成的帮助文本），SKILL.md / references / docs **零提及**。整个 coding 工具箱对读文档的用户不可见。建议二选一：补一份 `references/coding.md`，或在文档里明确它属于"agent 内部工具面"。→ ✅ **已裁决（§8 第 9 条）：取后者**——保持 hidden、不补 `references/coding.md`，定位为 agent 内部工具面，理由写在 `scripts/coverage-exclusions.psd1` 的 `code-*` 条目；不再要求真实场景覆盖。
3. **`agent-cancel`、`webminer-run-example` 同样只在 help.rs 出现** —— 可见命令却没有面向用户的文档，属于文档缺口（不是覆盖缺口）。
4. **`web-vitals` 是 `vitals` 的完全重复**（同 `browser_evaluate` + 同 `VITALS_JS` 载荷）→ 不需要独立场景，1 条 AC 即可。
5. **`key` / `keyboard` 是 `press` 的别名**（同 `browser_press_key`）；`keydown` / `keyup` / `type`（`browser_press_sequentially`）才是独立实现，必须各写断言。
6. **40 / 89 是 CLI 本地派发**（无 MCP 工具名）。其中约 14 个**既不需要浏览器也不需要后端**：`config*`、`skills*`、`webminer version/uninstall`、`diff-snapshot`、`profiles-list`、`session-default`、`stop`、`install/upgrade`、`batch`（编译段）。建议：这些用 `browser4-tests/tests-production/` 的 shell 断言做机械校验（便宜、可进 CI），LLM 场景只负责"首次使用者能否发现它"。→ ✅ **已落地（§8 第 11 条）**，但**清单经实测修正**：`diff-snapshot`/`profiles-list`/`batch` 实测会拉起后端，不在本类；`stop`/`install`/`upgrade` 已由既有 production 测试覆盖或归 Wave 3。实际 server-free 集合、逐项实测表与断言口径见 **§8.1**；脚本为 `browser4-tests/tests-production/local-commands.ps1`（`run-tests.ps1 local`，24 条断言，离线）。
7. **`snapshot-list` / `snapshot-clean` 的元数据与实现不一致（已查明，见 §10.1）**：两者都的注释写 "filesystem-only, no backend"，实际也确实由本地 handler 处理（`handle_snapshot_list` / `handle_snapshot_clean`，不传 client），但 `tool_name_fn` 却返回了并不存在的后端工具名 `snapshot_list` / `snapshot_clean`。**结论：排除理由是对的，错的是那个 tool 名**（死元数据 + 单测名单漏了这两个命令）。
8. **`uninstall --dry-run` 存在** → 上一轮"`uninstall` 永久排除"的粗判需要修正：`--dry-run` 是非破坏路径，可以覆盖；只有真正执行的 `uninstall -y` 才排除。
9. **外部依赖清单**（决定这些命令能否进常规批次）：`vitals`/`web-vitals` → CDN；`webminer update/views` → 下载 `scent-miner.jar`，`webminer run-example` → 还需 7-Zip；`install`/`upgrade` → 下载 runtime bundle；`chat`/`act` → LLM key；`profile-import` → 本机 Chrome/Edge profile。
10. **每个族的 fixture 都已核实可用**，含此前未列出的两个：`htmlsnapshot-test/readability-article`（给 `htmlsnapshot readability` 用，MockSite 现成路由）与 `generated/tta/act/act-demo.html`（给 `act` 用，多页流程）。

### 9.2 逐命令结论（89 条，按族）

#### Code 工具箱（23）

**处置**：Wave 2 #18（coding-toolkit）：只读命令（read/list/stat/glob/grep/diff/changes/workspace/impact）在临时目录上往返；写命令（write/append/replace/delete/copy/move/mkdir）在临时副本上做；shell/mvn/validate/scaffold/run/devtask/javap 限定显式低风险调用（如 `code shell "echo hi"`）

**前置条件**：仓库工作区 + 后端；全部 hidden，SKILL/refs/docs 零提及 → 预期报“不可发现”

| 命令 | 类别 | hidden | CLI e2e 标记 | 派发 | SKILL/refs/help 文档命中 |
|---|---|---|---|---|---|
| `code-read` | Code | True | Tested | coding_read | SKILL=0 refs=0 help.rs=2 docs=0 |
| `code-write` | Code | True | Tested | coding_write | SKILL=0 refs=0 help.rs=2 docs=0 |
| `code-append` | Code | True | Tested | coding_append | SKILL=0 refs=0 help.rs=2 docs=0 |
| `code-replace` | Code | True | Tested | coding_replace | SKILL=0 refs=0 help.rs=2 docs=0 |
| `code-delete` | Code | True | Tested | coding_delete | SKILL=0 refs=0 help.rs=2 docs=0 |
| `code-copy` | Code | True | Tested | coding_copy | SKILL=0 refs=0 help.rs=2 docs=0 |
| `code-move` | Code | True | Tested | coding_move | SKILL=0 refs=0 help.rs=2 docs=0 |
| `code-list` | Code | True | Tested | coding_listDir | SKILL=0 refs=0 help.rs=2 docs=0 |
| `code-stat` | Code | True | Tested | coding_stat | SKILL=0 refs=0 help.rs=2 docs=0 |
| `code-glob` | Code | True | Tested | coding_glob | SKILL=0 refs=0 help.rs=2 docs=0 |
| `code-grep` | Code | True | Tested | coding_grep | SKILL=0 refs=0 help.rs=2 docs=0 |
| `code-mkdir` | Code | True | Tested | coding_mkdir | SKILL=0 refs=0 help.rs=2 docs=0 |
| `code-diff` | Code | True | Tested | coding_diff | SKILL=0 refs=0 help.rs=2 docs=0 |
| `code-changes` | Code | True | Tested | coding_changeSummary | SKILL=0 refs=0 help.rs=2 docs=0 |
| `code-shell` | Code | True | Tested | coding_shell | SKILL=0 refs=0 help.rs=2 docs=0 |
| `code-scaffold` | Code | True | Tested | coding_scaffoldToDir | SKILL=0 refs=0 help.rs=2 docs=0 |
| `code-validate` | Code | True | Tested | coding_validate | SKILL=0 refs=0 help.rs=2 docs=0 |
| `code-mvn` | Code | True | Tested | coding_mvnBuild | SKILL=0 refs=0 help.rs=2 docs=0 |
| `code-run` | Code | True | Tested | coding_runCode | SKILL=0 refs=0 help.rs=2 docs=0 |
| `code-devtask` | Code | True | Tested | coding_devTask | SKILL=0 refs=0 help.rs=2 docs=0 |
| `code-impact` | Code | True | Tested | coding_impact | SKILL=0 refs=0 help.rs=2 docs=0 |
| `code-workspace` | Code | True | Tested | coding_workspaceRoot | SKILL=0 refs=0 help.rs=2 docs=0 |
| `code-javap` | Code | True | Tested | coding_classInfo | SKILL=0 refs=0 help.rs=2 docs=0 |

#### Skill/Skills 管理（10）

**处置**：Wave 1 #11（同一场景）：`skills*` 为本地 skill 目录操作，`skill-*` 走后端注册表（MCP），一个场景内串联

**前置条件**：本地 `skills/browser4-cli` 目录 + 后端；4 个 `skills-*` 子命令 hidden

| 命令 | 类别 | hidden | CLI e2e 标记 | 派发 | SKILL/refs/help 文档命中 |
|---|---|---|---|---|---|
| `skills` | Skills | False | Excluded | <local> | SKILL=98 refs=78 help.rs=83 docs=21 |
| `skills-list` | Skills | True | Excluded | <local> | SKILL=3 refs=2 help.rs=7 docs=0 |
| `skills-get` | Skills | True | Excluded | <local> | SKILL=8 refs=15 help.rs=19 docs=0 |
| `skills-path` | Skills | True | Excluded | <local> | SKILL=5 refs=5 help.rs=14 docs=0 |
| `skills-unpack` | Skills | True | Excluded | <local> | SKILL=4 refs=8 help.rs=13 docs=0 |
| `skill-list` | Skill | False | Excluded | skill_list | SKILL=3 refs=6 help.rs=0 docs=0 |
| `skill-info` | Skill | False | Excluded | skill_info | SKILL=3 refs=6 help.rs=0 docs=0 |
| `skill-install` | Skill | False | Excluded | skill_install | SKILL=3 refs=9 help.rs=0 docs=0 |
| `skill-uninstall` | Skill | False | Excluded | skill_uninstall | SKILL=3 refs=6 help.rs=0 docs=0 |
| `skill-reload` | Skill | False | Excluded | skill_reload | SKILL=3 refs=5 help.rs=0 docs=0 |

#### Plugin 管理（4）

**处置**：Wave 1 #11（同一场景）：REST CRUD，用内置插件目录做 install/remove 往返

**前置条件**：后端；`browser4-plugins/` 内置插件；全部标记 Excluded

| 命令 | 类别 | hidden | CLI e2e 标记 | 派发 | SKILL/refs/help 文档命中 |
|---|---|---|---|---|---|
| `plugin-list` | Plugins | False | Excluded | <local> | SKILL=5 refs=0 help.rs=3 docs=0 |
| `plugin-info` | Plugins | False | Excluded | <local> | SKILL=2 refs=0 help.rs=2 docs=0 |
| `plugin-install` | Plugins | False | Excluded | <local> | SKILL=2 refs=0 help.rs=3 docs=1 |
| `plugin-remove` | Plugins | False | Excluded | <local> | SKILL=2 refs=0 help.rs=2 docs=0 |

#### Config 管理（5）

**处置**：Wave 1 #10：本地配置文件读写，无需浏览器

**前置条件**：无（可在无浏览器会话下跑）；`config-list` hidden

| 命令 | 类别 | hidden | CLI e2e 标记 | 派发 | SKILL/refs/help 文档命中 |
|---|---|---|---|---|---|
| `config` | Config | False | Tested | <local> | SKILL=36 refs=52 help.rs=8 docs=16 |
| `config-list` | Config | True | Tested | <local> | SKILL=1 refs=2 help.rs=2 docs=0 |
| `config-get` | Config | False | Tested | <local> | SKILL=3 refs=5 help.rs=2 docs=0 |
| `config-set` | Config | False | Tested | <local> | SKILL=8 refs=11 help.rs=2 docs=1 |
| `config-delete` | Config | False | Tested | <local> | SKILL=2 refs=2 help.rs=2 docs=0 |

#### Browsers：doctor 与运行时（8）

**处置**：Wave 1 #12：doctor 家族逐个跑并检查每项结论可执行；`stop` 验证无残留进程；`session-default` 验证默认会话切换；`profiles-list` 枚举本地 profile；`profile-import` 只做 `--list-sources` 与“未开 property 时拒绝导入密码”

**前置条件**：本地环境（doctor/stop 建议 production 模式）；`profile-import` 需本机 Chrome/Edge profile

| 命令 | 类别 | hidden | CLI e2e 标记 | 派发 | SKILL/refs/help 文档命中 |
|---|---|---|---|---|---|
| `doctor` | Browsers | False | Excluded | <local> | SKILL=38 refs=5 help.rs=6 docs=13 |
| `doctor-log` | Browsers | False | Excluded | <local> | SKILL=7 refs=0 help.rs=3 docs=0 |
| `doctor-metrics` | Browsers | False | Excluded | <local> | SKILL=7 refs=0 help.rs=3 docs=0 |
| `doctor-status` | Browsers | False | Tested | <local> | SKILL=8 refs=0 help.rs=3 docs=0 |
| `stop` | Browsers | False | Tested | <local> | SKILL=22 refs=25 help.rs=11 docs=17 |
| `session-default` | Browsers | False | Tested | <local> | SKILL=4 refs=1 help.rs=0 docs=0 |
| `profiles-list` | Browsers | False | Tested | <local> | SKILL=2 refs=0 help.rs=2 docs=0 |
| `profile-import` | Browsers | False | Tested | profile_import_list_sources | SKILL=3 refs=1 help.rs=0 docs=1 |

#### Keyboard（5）

**处置**：Wave 1 #7：`key`/`keyboard` 与 `press` 共用 `browser_press_key`（别名，各 1 条 AC 即可）；`keydown`/`keyup`/`type` 是独立 tool，必须各写断言

**前置条件**：浏览器 + `b4/mcp-tool-controller-keyboard-fixture.html`

| 命令 | 类别 | hidden | CLI e2e 标记 | 派发 | SKILL/refs/help 文档命中 |
|---|---|---|---|---|---|
| `key` | Keyboard | False | Tested | browser_press_key | SKILL=76 refs=52 help.rs=16 docs=133 |
| `keyboard` | Keyboard | False | Tested | browser_press_key | SKILL=5 refs=2 help.rs=6 docs=8 |
| `keydown` | Keyboard | False | Tested | browser_keydown | SKILL=6 refs=0 help.rs=1 docs=1 |
| `keyup` | Keyboard | False | Tested | browser_keyup | SKILL=6 refs=0 help.rs=1 docs=1 |
| `type` | Keyboard | False | Tested | browser_press_sequentially | SKILL=33 refs=53 help.rs=24 docs=286 |

#### Mouse 原语（3）

**处置**：Wave 1 #7：down→move→up 组合出“无 click 的拖选”，与 `drag`/`click` 区分开

**前置条件**：浏览器 + `b4/mcp-tool-controller-mouse-fixture.html`

| 命令 | 类别 | hidden | CLI e2e 标记 | 派发 | SKILL/refs/help 文档命中 |
|---|---|---|---|---|---|
| `mousemove` | Mouse | False | Tested | browser_mouse_move_xy | SKILL=7 refs=3 help.rs=1 docs=0 |
| `mousedown` | Mouse | False | Tested | browser_mouse_down | SKILL=6 refs=1 help.rs=1 docs=0 |
| `mouseup` | Mouse | False | Tested | browser_mouse_up | SKILL=6 refs=0 help.rs=1 docs=0 |

#### Crawl 任务控制（5）

**处置**：Wave 1 #9：background crawl → status/result → cancel → clear → resume（checkpoint）全链路

**前置条件**：后端 + MockSite `/generated/crawl/`；`crawl-resume` 需中断后留下的 checkpoint

| 命令 | 类别 | hidden | CLI e2e 标记 | 派发 | SKILL/refs/help 文档命中 |
|---|---|---|---|---|---|
| `crawl-status` | Swarm | False | Tested | <local> | SKILL=3 refs=7 help.rs=6 docs=4 |
| `crawl-result` | Swarm | False | Tested | <local> | SKILL=3 refs=7 help.rs=6 docs=1 |
| `crawl-cancel` | Swarm | False | Tested | <local> | SKILL=2 refs=3 help.rs=5 docs=1 |
| `crawl-clear` | Swarm | False | Tested | <local> | SKILL=4 refs=6 help.rs=8 docs=3 |
| `crawl-resume` | Swarm | False | Tested | <local> | SKILL=8 refs=16 help.rs=17 docs=9 |

#### Snapshot 生命周期（3）

**处置**：Wave 1 #8：先造 2~3 个快照再 list/dry-run/clean/diff。⚠️ `snapshot-list`/`snapshot-clean` 的 `Excluded` 注释写“filesystem-only, no backend”，但派发的却是后端 MCP 工具 `snapshot_list`/`snapshot_clean` —— 注释与派发不一致，场景落地前需确认权威存储位置（CLI state 目录 vs 后端）

**前置条件**：后端（至少一次 `snapshot`）；`diff-snapshot` 为纯本地

| 命令 | 类别 | hidden | CLI e2e 标记 | 派发 | SKILL/refs/help 文档命中 |
|---|---|---|---|---|---|
| `snapshot-list` | Core | False | Excluded | snapshot_list | SKILL=3 refs=6 help.rs=2 docs=0 |
| `snapshot-clean` | Core | False | Excluded | snapshot_clean | SKILL=3 refs=6 help.rs=2 docs=0 |
| `diff-snapshot` | Snapshot | False | Tested | <local> | SKILL=2 refs=0 help.rs=2 docs=0 |

#### Core：批量与其余（1）

**处置**：Wave 2 #15：正向覆盖 `batch`（多步提交、`--bail` 首错即停、`--json`、HTTP 往返下降）；目前只有 `amazon.md` 的“禁止使用”负向覆盖

**前置条件**：后端 + MockSite 表单页

| 命令 | 类别 | hidden | CLI e2e 标记 | 派发 | SKILL/refs/help 文档命中 |
|---|---|---|---|---|---|
| `batch` | Core | False | Excluded | <local> | SKILL=17 refs=31 help.rs=27 docs=16 |

#### Storage / Export：抓取与 WebDB（4）

**处置**：Wave 2 #14：`scrape`/`scrape-formats` 在 `/ec/dp/<id>` 上免会话运行；`webdb export` 用一次 crawl 产生 WebDB 后导出，`normalize` 验证幂等

**前置条件**：后端（+ MockSite）；`webdb` 需要 crawl 产物

| 命令 | 类别 | hidden | CLI e2e 标记 | 派发 | SKILL/refs/help 文档命中 |
|---|---|---|---|---|---|
| `scrape` | Export | False | Tested | page_scrape | SKILL=27 refs=52 help.rs=22 docs=9 |
| `scrape-formats` | Export | False | Tested | page_formats | SKILL=10 refs=9 help.rs=7 docs=0 |
| `webdb-export` | Storage | False | Tested | webdb_export | SKILL=4 refs=16 help.rs=2 docs=1 |
| `webdb-normalize` | Storage | False | Tested | webdb_normalize | SKILL=3 refs=9 help.rs=2 docs=0 |

#### DevTools：性能与指标（4）

**处置**：Wave 2 #13：profile 落盘后校验 `.cpuprofile` 结构；`vitals` 依赖 CDN，断言“拿到指标或给出可读降级提示”。⚠️ `web-vitals` 与 `vitals` 的 tool 与 JS 载荷完全相同（纯重复），只需 1 条 AC

**前置条件**：浏览器 + 外网（CDN）；`profiler-*` 需要在活动会话内成对使用

| 命令 | 类别 | hidden | CLI e2e 标记 | 派发 | SKILL/refs/help 文档命中 |
|---|---|---|---|---|---|
| `profiler-start` | DevTools | False | Tested | <local> | SKILL=2 refs=0 help.rs=2 docs=0 |
| `profiler-stop` | DevTools | False | Tested | <local> | SKILL=2 refs=0 help.rs=2 docs=0 |
| `vitals` | DevTools | False | Tested | browser_evaluate | SKILL=2 refs=1 help.rs=0 docs=0 |
| `web-vitals` | DevTools | False | Tested | browser_evaluate | SKILL=4 refs=0 help.rs=0 docs=0 |

#### Agent：对话与取消（3）

**处置**：Wave 2 #17：`chat` → `chat-result` 轮询；`agent-cancel` 对长任务生效并给终态；无 LLM key 时记录 environment-blocked

**前置条件**：LLM key + 后端；`agent-cancel` 需要足够长的任务才能观察取消。⚠️ `agent-cancel` 是可见命令但 SKILL/refs/docs 零文档（仅 help.rs）

| 命令 | 类别 | hidden | CLI e2e 标记 | 派发 | SKILL/refs/help 文档命中 |
|---|---|---|---|---|---|
| `chat` | Agent | False | Tested | <local> | SKILL=12 refs=0 help.rs=1 docs=4 |
| `chat-result` | Agent | False | Tested | <local> | SKILL=2 refs=0 help.rs=2 docs=0 |
| `agent-cancel` | Agent | False | Tested | <local> | SKILL=0 refs=0 help.rs=2 docs=0 |

#### Install 生命周期（2）

**处置**：Wave 2 #19（production 模式）：install 幂等、`upgrade --tag`、镜像回退；已安装态与未安装态各跑一次

**前置条件**：production 模式 + 网络（下载 runtime bundle）

| 命令 | 类别 | hidden | CLI e2e 标记 | 派发 | SKILL/refs/help 文档命中 |
|---|---|---|---|---|---|
| `install` | Install | False | Tested | <local> | SKILL=43 refs=16 help.rs=28 docs=74 |
| `upgrade` | Install | False | Tested | <local> | SKILL=15 refs=2 help.rs=14 docs=30 |

#### WebMiner 运维（5）

**处置**：Wave 3：`webminer-version` 只读 `~/.scent/webminer/version.txt`，未安装时给可读报错 → 可确定性覆盖；`webminer-uninstall` 的 `Excluded` 注释写明“targets real ~/.scent via WinAPI home — not redirectable in e2e”，若接受删除用户级目录则可在“install → version → uninstall”闭环里覆盖，否则列白名单（not sandboxable）；`webminer-update`/`views` 需 `scent-miner.jar`（GitHub/OSS 下载），`webminer-run-example` 额外依赖 7-Zip → 这两类只断言“存在性 + 失败可读”，不拉进常规批次

**前置条件**：`~/.scent/webminer/` 与网络；`uninstall` 需可接受删除该目录；`run-example` 需 7-Zip

| 命令 | 类别 | hidden | CLI e2e 标记 | 派发 | SKILL/refs/help 文档命中 |
|---|---|---|---|---|---|
| `webminer-version` | WebMiner | False | Excluded | <local> | SKILL=1 refs=1 help.rs=4 docs=0 |
| `webminer-uninstall` | WebMiner | False | Excluded | <local> | SKILL=1 refs=1 help.rs=4 docs=0 |
| `webminer-update` | WebMiner | False | Excluded | <local> | SKILL=1 refs=1 help.rs=4 docs=0 |
| `webminer-run-example` | WebMiner | False | Excluded | <local> | SKILL=0 refs=0 help.rs=2 docs=0 |
| `webminer-views` | WebMiner | False | Excluded | <local> | SKILL=1 refs=1 help.rs=5 docs=0 |

#### Act（实验）（1）

**处置**：Wave 2 #16：`generated/tta/act/act-demo.html` 现成多页 fixture；需 LLM key（POST `/api/act`）。hidden + Excluded，先确认是否转正再投入

**前置条件**：LLM key + 后端

| 命令 | 类别 | hidden | CLI e2e 标记 | 派发 | SKILL/refs/help 文档命中 |
|---|---|---|---|---|---|
| `act` | Act | True | Excluded | <local> | SKILL=10 refs=9 help.rs=13 docs=3 |

#### HTML snapshot 提取算法（2）

**处置**：Wave 1 #8（同一场景）：`algorithms` 列出可选算法；`readability` 在正文页上输出可读正文。MockSite 已有专用 fixture `htmlsnapshot-test/readability-article`

**前置条件**：后端 + 任意 HTML 页（readability 用正文页）

| 命令 | 类别 | hidden | CLI e2e 标记 | 派发 | SKILL/refs/help 文档命中 |
|---|---|---|---|---|---|
| `htmlsnapshot-algorithms` | Snapshot | False | Tested | html_snapshot_algorithms | SKILL=4 refs=3 help.rs=5 docs=2 |
| `htmlsnapshot-readability` | Snapshot | False | Tested | html_snapshot_readability | SKILL=9 refs=7 help.rs=8 docs=3 |

#### Install：卸载（1）

**处置**：**拆分处置**：`uninstall --dry-run` 是非破坏路径（列出将被删除的全局安装与 runtime data），纳入 Wave 3 覆盖；真正执行的 `uninstall -y` **永久排除**（会删除全局 CLI 安装与 runtime data，且用户级路径无法在 CI 内重定向），写入 `coverage.ps1` 白名单，理由 destructive

**前置条件**：`--dry-run`：任意环境（无需网络）；实删：不适用（排除）

| 命令 | 类别 | hidden | CLI e2e 标记 | 派发 | SKILL/refs/help 文档命中 |
|---|---|---|---|---|---|
| `uninstall` | Install | False | Excluded | <local> | SKILL=5 refs=3 help.rs=9 docs=26 |

<!-- rows: 89 -->

---

## 10. §8 第 8/10 条的查证细节

### 10.1 `snapshot-list` / `snapshot-clean`：注释 vs 派发（对应 §8 第 10 条）

**问题**：两个命令的 `e2e_coverage` 注释写 "filesystem-only, no backend"，但同一结构体里 `tool_name_fn` 却返回 `"snapshot_list"` / `"snapshot_clean"` —— 一眼看去像是"要么注释错、要么派发错"。

**代码证据链**（四条互相印证，结论是**注释正确、tool 名是死元数据**）：

| # | 证据 | 位置 |
|---|---|---|
| 1 | 派发是本地 handler，**没有传 client / base_url**（不可能走后端） | `main.rs:27997` → `handle_snapshot_list(&tool_params)?`；`main.rs:28000` → `handle_snapshot_clean(&tool_params)?` |
| 2 | handler 内部直接读本地目录 `snapshot::snapshot_dir()` | `main.rs:6862` / `main.rs:7008`；`snapshot.rs:9,16` → `SNAPSHOT_DIR = [".browser4-cli", "snapshot"]` |
| 3 | `should_ensure_server_running()` 明确把这两个命令列为**不需要服务器** | `main.rs:24578` 的排除列表含 `snapshot-list` / `snapshot-clean` |
| 4 | 显式 match 分支先于通用 MCP 路径（`_ => handle_tool_command(...)`），因此 `tool_name_fn` 的返回值**永远不会被派发使用**；唯一的通用消费点是 `--examples`（`resolve_command_tool` → `main.rs:21015/21051`） | `main.rs:21015`、`21051` |

**影响（按严重度）**：
1. 后端根本没有 `snapshot_list` / `snapshot_clean` 这两个工具（全仓 `.kt` 零命中）→ `snapshot-list --examples` 只能回一句"取不到示例"，而这两个命令其实完全可以在本地给出用法示例。
2. 审计/文档会持续误判这两个命令"需要后端"（本计划的自动化审计第一版就这么判的）。
3. 单测 `test_local_only_commands_have_empty_tool_name`（`commands.rs:5906`）本意就是守这条规则，但它的名单只有 `diff-snapshot`、`profiles-list`、`profiler-start` 三个，漏了这两个 → 规则形同虚设。

**建议修法**（1 行 + 1 行）：把两者的 `tool_name_fn` 改成 `|_| String::new()`，并把 `snapshot-list`/`snapshot-clean` 加进那个单测的名单。修完注释与实现就一致了。

**对场景 #8 的直接影响**：断言全部落在 CLI 侧文件系统，不需要后端——但要注意快照目录是 **CWD 相对**路径（`.browser4-cli/snapshot`），所以"在另一个目录执行 `snapshot list` 会报 no snapshot directory"本身就是一个值得记录的真实可用性问题（CLI 的其它状态文件走 state dir，快照却跟着 CWD 走）。

### 10.2 覆盖守护脚本的落点（对应 §8 第 8 条）

**已核实的 CI 事实**：

| 事实 | 位置 |
|---|---|
| `bin/test.ps1 ps` 会**递归发现并执行仓库内所有 `*.tests.ps1`**（非 Pester 风格则用 `pwsh -File` 跑；`common.tests.ps1` 就是这种，失败 `exit 1`） | `bin/test.ps1:3593` 起的 `ps` target |
| 因此新增 `scripts/coverage.tests.ps1` **无需任何接线**就会进这套测试 | 同上 |
| `ps1-tests.yml` 在**每个 PR**（以及 push main/*.x、nightly 03:23）执行 `./bin/test.ps1 ps`，号称"无 Java/Maven/Docker，~3 分钟" | `.github/workflows/ps1-tests.yml:88-90` |
| **但它的 `paths` 过滤只有** `**/*.ps1`、`**/*.psm1`、`**/*.psd1`、`skills/**/*.md`、workflow 自身 | `ps1-tests.yml:32-46` |
| ⇒ 新增/修改 CLI 命令（改 `cli/browser4-cli/src/commands.rs`）**不会**触发它；新增/修改场景任务（`browser4-tests/real-world-scenarios/tasks/**.md`）**也不会**触发它 | 结论 |
| `cargo test --bin browser4-cli --lib` 只在 **nightly** 跑；`ci.yml` 跑的是 `cargo test --test e2e`（BASIC 级） | `nightly.yml:347`、`ci.yml:299` |

**三个可落点与取舍**：

| 方案 | 触发时机 | 代价 | 风险 |
|---|---|---|---|
| **(a) PS 套件**：`scripts/coverage.tests.ps1` | PR（**需同时把 `cli/browser4-cli/src/commands.rs` 与 `browser4-tests/real-world-scenarios/tasks/**` 加进 `ps1-tests.yml` 的 `paths`**），否则只剩 nightly | 零接线；纯文本解析，~1s | 变成硬门禁：新增命令必须同时交一个场景或一条白名单理由 |
| **(b) 只读脚本**：`scripts/coverage.ps1 -List` | 手动 + 可选 nightly 步骤 | 最小 | 不会自动拦住漂移（本次 115 个缺口就是没人跑才累积出来的） |
| **(c) Rust 单测**：在 `commands.rs` 的 `#[cfg(test)]` 里读 `browser4-tests/real-world-scenarios/tasks/**/*.md` | nightly（PR 不跑 `--bin`） | 需要测试里解析仓库相对路径 | 让 CLI crate 单测依赖场景目录，层次上不如 (a) 干净 |

**建议**：选 **(a) + 放宽 `paths`**（两行 YAML）。理由：缺口是"改 Rust 文件"制造的，门禁必须挂在那个改动上；纯静态检查足够快，符合该 workflow 的定位；白名单机制给 hidden/内部/需外部资源的命令留了出口，不会把合法的"这个命令不打算做真实场景"堵死。若不想加硬门禁，就退到 (b) 并接受"nightly 才报、不阻塞"。

---

## 附录 A：命令 → 覆盖档位全表（机器可读）

- 完整 CSV（201 行，含 `Command / Category / CodeHits / ProseHits / 判定`）：由 `classify.ps1` 生成，可作为 `coverage.ps1` 的期望输出基线。
- T3 明细：审计基线 115 行见 §4，当前剩余 89 行见 §4.2。

## 附录 B：本次审计的复现步骤

```powershell
# 1) 解析 commands.rs 的命令注册表（201 条）
# 2) 抽取场景文件的 code 上下文（fenced / inline code / __CLI__ / browser4-cli 调用）
# 3) 双向匹配（kebab + spaced）→ coverage2.csv / evidence.csv
# 4) 人工剔除假阳性并分档 → classify.ps1 输出 T1/T2/T3
# 脚本副本位于本次会话的临时目录（不属于仓库）；实施 coverage.ps1 时把 1)-4) 固化进去
```

## 附录 C：Wave 1 #1–#6 的实施核对单

| 检查项 | 结果 |
|---|---|
| 6 个场景文件已落地 | ✅ `tasks/mock-site/{network-observability, console-errors-and-cdp, iframes-and-popups, file-upload-and-download, device-and-network-emulation, element-assertions-and-focus}.md` |
| 场景可被发现 / 分类正确 | ✅ `run-tests.ps1 -List -Category mock-site` → 19/19 选中，含全部 6 个新文件 |
| 命令解析正确（含空格形式） | ✅ 已逐个核对 `main.rs::rewrite_prefixed_command` / `preferred_spaced_command_form`：`network requests/request/route/unroute`、`network har start/stop`、`tool call`、`is visible/enabled/checked`、`window new`、`plugin list`、`snapshot list` 等有空格映射；**`tab-list`/`tab-new`/`tab-select`、`dialog-status`/`dialog-accept`/`dialog-dismiss`、`delete-data` 没有空格映射，必须写 kebab 形式**——场景文件已按此修正 |
| 复测覆盖增量 | ✅ T1 60 → 87；T3 115 → 89（26 个命令从 T3 移出，`check` 由 T2 升 T1） |
| 未新增静态 fixture | ✅ 全部复用 `static/b4/` 与 `static/assets/` 现成页面 |
| README 同步 | ✅ 计数与任务表已更新 |
| 断言取自可复现来源 | ✅ 期望串（如 `#pay-state` → `submitted:…`、`dialog-status` 报告 pending、`is visible` 对 `#hidden-target` 返回 false、HAR `log.entries[]` 结构、`network route` 后 `#results` 出现 `ok: 200 {"routed":true}`）均与 `cli/browser4-cli/tests/e2e/scenarios/browser.rs` 中已在跑的同类断言对齐，并按 MockSite 的路由差异做了修正说明 |
