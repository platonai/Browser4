# `help.rs` 文档核对清单（基于 2026-05-08 最新代码）

本次核对以**当前实现与运行时输出**为准，不再把 `SKILL.md` 视为唯一真值源。

## 核对范围

事实真值源：
- `D:\workspace\Browser4Team\submodules\Browser4\sdks\browser4-cli\src\commands.rs`
- `D:\workspace\Browser4Team\submodules\Browser4\sdks\browser4-cli\src\help.rs`
- `D:\workspace\Browser4Team\submodules\Browser4\sdks\browser4-cli\src\main.rs`

对照文档：
- `D:\workspace\Browser4Team\submodules\Browser4\sdks\skill\SKILL.md`
- `D:\workspace\Browser4Team\submodules\Browser4\sdks\browser4-cli\README.md`

实际观测命令：
- `cargo run --quiet -- help`
- `cargo run --quiet -- help press|type|upload|snapshot|tab-close|tab-select|console|extract|summarize|agent-run|co-create|co-submit|batch`
- `cargo test help -- --nocapture`

---

## 结论摘要

- `help.rs` 的**单命令帮助**基本跟 `commands.rs` 当前签名一致。
- `press` / `type` 已统一为“值在前，目标 ref 在后”的帮助语法，同时保留旧式参数顺序兼容。
- `upload` 当前真实契约仍是 `browser4-cli upload <ref> <file>`；这里不再是 `SKILL.md` 的旧冲突，而是 **`SKILL.md` 目前完全没有覆盖 `upload`**。
- `README.md` 已与 `press` / `type` / `upload` 当前契约对齐，但 `tab-close` / `tab-select` 仍写成 `index`，与代码里的 `tabId` 不一致。
- `help.rs` 的**全局帮助**仍然漏掉 `console`、`extract`、`summarize`、`agent-*`、`co-*`，因为 `CATEGORIES` 没有启用 `devtools` / `agent` / `collective`。
- `batch` 仍然是**单命令帮助可见、全局帮助隐藏**的状态；相应测试仍然失败。
- `help.rs` 仍会把描述和参数说明统一转小写，导致 `ArrowLeft` / `JavaScript` / `GUI` 等专有写法失真。

---

## 需要确认 / 选择的事项

### 1. `press` 的调用语法：当前实现已稳定
- 当前签名：`browser4-cli press <key> [ref]`
- `commands.rs` 中通过 `resolve_key_and_ref()` 兼容旧式 `press <ref> <key>`
- 运行时帮助与 `README.md` 已一致
- `SKILL.md` 示例当前也与新语法一致，例如：
  - `browser4-cli press Enter`
  - `browser4-cli press ArrowDown`

**当前状态：** 该项已基本统一，无需再作为文档冲突处理。

---

### 2. `type` 的调用语法：当前实现已稳定
- 当前签名：`browser4-cli type <text> [ref]`
- `commands.rs` 中通过 `resolve_text_and_ref()` 兼容旧式 `type <ref> <text>`
- 运行时帮助与 `README.md` 已一致
- `SKILL.md` 当前示例也使用新语法

**当前状态：** 该项已基本统一，无需再作为文档冲突处理。

---

### 3. `upload` 的用户文档覆盖仍不完整
- 当前实现 / 运行时帮助 / `README.md` 一致：`browser4-cli upload <ref> <file>`
- `commands.rs` 当前契约：文件输入元素 `ref` 必填，最终映射到 `{ "ref": ..., "paths": [file] }`
- `SKILL.md` 当前**没有 `upload` 示例，也没有命令条目**

**需要选择：**
- [ ] A. 继续保持当前 CLI 契约，并给 `SKILL.md` 补上 `upload <ref> <file>` 示例
- [ ] B. 如果产品希望支持省略 `ref`，则先定义“默认上传目标”的规则，再改实现与帮助

审核意见：暂时不提供该命令。

---

### 4. “每条命令之后都会提供 snapshot” 这句话仍然过强
- `SKILL.md` 当前表述仍是：`After each command, browser4-cli provides a snapshot of the current browser state.`
- `README.md` 已改成更准确的说法：`After each command that modifies browser state...`
- `main.rs` 中 `no_snapshot_commands()` 当前排除了：
  - `open`
  - `close`
  - `close-all`
  - `kill-all`
  - `list`
  - `help`
  - `eval`
  - `snapshot`
  - `screenshot`
  - `pdf`
  - `agent-run` / `agent-status` / `agent-result`
  - `co-create` / `co-submit` / `co-scrape` / `co-status` / `co-result`
- 特殊情况：`open <url>` 会在导航完成后手动打印一次 snapshot；`open`（无 URL）不会。

**需要选择：**
- [✔] A. 把 `SKILL.md` 改成与 `README.md` / `main.rs` 一致的“修改页面状态的命令通常会自动附带 snapshot”
- [ ] B. 如果产品要求“每条命令都自动附带 snapshot”，则应修改 `main.rs` 行为，而不是继续让文档背离实现

---

### 5. `tab-close` / `tab-select` 当前到底是“索引”还是 `tabId`？
- 当前 `commands.rs` / `help.rs` / 运行时帮助统一使用：
  - `browser4-cli tab-close [tabId]`
  - `browser4-cli tab-select <tabId>`
- `commands.rs` 单测也明确断言只使用 `tabId`，而不是 `index`
- `README.md` 仍写：
  - `tab-close [index]`
  - `tab-select <index>`
- `SKILL.md` 示例仍使用纯数字：
  - `browser4-cli tab-close 2`
  - `browser4-cli tab-select 0`

**风险：**
- 读 `README.md` / `SKILL.md` 的用户会自然理解成“按索引切换 tab”
- 读 CLI 运行时帮助的用户会理解成“传真实 tabId”

**需要选择：**
- [✔] A. 正式文档统一为 `tabId`，并把 `README.md` / `SKILL.md` 示例改成“先 `tab-list`，再传真实 id”
- [ ] B. 如果产品真想兼容“索引”，应在 CLI 契约层明确支持并把帮助文案改成 `index-or-tabId`

---

### 6. 全局帮助漏掉的命令范围比之前更大
- `commands.rs` 中以下命令都不是 hidden：
  - `console`
  - `extract`
  - `summarize`
  - `agent-run` / `agent-status` / `agent-result`
  - `co-create` / `co-submit` / `co-scrape` / `co-status` / `co-result`
- 但 `help.rs` 的 `CATEGORIES` 当前只启用了：
  - `core`
  - `navigation`
  - `keyboard`
  - `mouse`
  - `export`
  - `tabs`
  - `browsers`
- 因此，实际运行 `browser4-cli help` 时，上述命令**全部不会出现在全局帮助里**
- 但逐条运行 `browser4-cli help console|extract|summarize|agent-run|co-create|co-submit` 时，单命令帮助都能正常显示

**需要选择：**
- [ ] A. 如果这些命令属于正式对外能力，应把 `devtools` / `agent` / `collective` 分类加回 `CATEGORIES`
- [✔] B. 如果这些命令不希望在全局帮助中暴露，就应进一步统一策略：设为 hidden，或在 `README.md` / `SKILL.md` 明确说明“高级命令需显式查询帮助”

---

### 7. `batch` 仍处于“局部公开、全局隐藏、测试失败”的冲突状态
- `help.rs` 为 `batch` 提供了完整单命令帮助、Notes 和 Examples
- `commands.rs` 中 `batch.hidden = true`，所以它不会出现在 `browser4-cli help` 的全局列表里
- `README.md` 命令表和示例中又把 `batch` 当成公开能力来写
- 当前测试 `help::tests::test_generate_help_contains_commands` 仍断言全局帮助必须包含 `batch`

**已观测结果：**
- 运行 `cargo test help -- --nocapture` 仍失败
- 失败测试：`help::tests::test_generate_help_contains_commands`
- 失败原因：断言 `generate_help()` 输出里包含 `batch`，但实际全局帮助没有

**需要选择：**
- [ ] A. `batch` 应该公开显示：把 `hidden` 改回 `false`
- [✔] B. `batch` 应该继续隐藏：那就同步修正测试，并重新定义 README / CLI 帮助的公开层级

---

### 8. `help.rs` 的统一小写化仍然会破坏术语精度
- 当前 `help.rs` 会对：
  - 全局帮助中的描述
  - 单命令帮助中的参数说明
  - 单命令帮助中的选项说明
  执行 `to_lowercase()`
- 本次实际观察到的结果包括：
  - 全局帮助里：``ArrowLeft`` 被显示成 ``arrowleft``
  - `help press` 的参数说明里：`ArrowLeft` 被显示成 `arrowleft`
  - 全局帮助里：`Evaluate JavaScript...` 变成 `evaluate javascript...`
  - `help co-create` 里：`GUI, HEADLESS, SUPERVISED` 变成 `gui, headless, supervised`

**影响：**
- 与 `commands.rs` 里的原始描述不一致
- 与 `README.md` / `SKILL.md` 示例风格不一致
- 在键名、专有名词、枚举值上降低准确性

**需要选择：**
- [✔] A. 保留原始大小写，去掉 `to_lowercase()`
- [ ] B. 如果仍要统一风格，也应只处理普通句子，不要改动代码字面量 / 键名 / 枚举值 / 专有名词

---

## 当前更像“事实真值源”的位置

如果只看最新实现，当前更接近真实契约的是：
- 参数与命令签名：`src/commands.rs`
- 自动 snapshot 行为：`src/main.rs`
- 最终用户看到的帮助输出：`src/help.rs`

因此，**短期如果不改 CLI 行为，应该优先把 `SKILL.md` / `README.md` 回写到当前实现契约**；如果产品想保留更简化的人机交互，则应先补实现，再统一回写帮助文档。

审核意见：始终以`SKILL.md`为首要真值源；如果 `SKILL.md` 没有覆盖的命令，则以 `commands.rs` 的实现为真值源；如果 `commands.rs` 的实现与 `help.rs` 输出不一致，则以 `help.rs` 的输出为真值源。

---

## 本次核对时实际观察到的运行结果

### `browser4-cli help` 当前会显示
- `press <key> [ref]`
- `type <text> [ref]`
- `upload <ref> <file>`
- `tab-close [tabId]`
- `tab-select <tabId>`
- `Browser sessions` 分类

### `browser4-cli help` 当前不会显示
- `batch`
- `console`
- `extract`
- `summarize`
- `agent-run` / `agent-status` / `agent-result`
- `co-create` / `co-submit` / `co-scrape` / `co-status` / `co-result`

### 单命令帮助当前可正常显示
- `browser4-cli help press`
- `browser4-cli help type`
- `browser4-cli help upload`
- `browser4-cli help snapshot`
- `browser4-cli help tab-close`
- `browser4-cli help tab-select`
- `browser4-cli help console`
- `browser4-cli help extract`
- `browser4-cli help summarize`
- `browser4-cli help agent-run`
- `browser4-cli help co-create`
- `browser4-cli help co-submit`
- `browser4-cli help batch`

### `cargo test help -- --nocapture` 当前结果
- 仍然失败
- 失败测试：`help::tests::test_generate_help_contains_commands`
- 直接原因：测试断言全局帮助包含 `batch`，但实现没有输出 `batch`

