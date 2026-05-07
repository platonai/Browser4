# `help.rs` 文档核对清单

基准文档：`D:\workspace\Browser4Team\submodules\Browser4\sdks\skill\SKILL.md`

对照文件：
- `D:\workspace\Browser4Team\submodules\Browser4\sdks\browser4-cli\src\help.rs`
- `D:\workspace\Browser4Team\submodules\Browser4\sdks\browser4-cli\src\commands.rs`
- `D:\workspace\Browser4Team\submodules\Browser4\sdks\browser4-cli\src\main.rs`
- 运行时输出：`cargo run --quiet -- help`、`cargo run --quiet -- help press|type|upload|snapshot`

结论摘要：
- `help.rs` 的**单命令帮助**大体跟 `commands.rs` 一致。
- 但以 `SKILL.md` 为基准时，当前文档集合存在多处**契约不一致 / 需要选定单一真值源**的问题。
- 另外，`help.rs` 自身也有几处**全局帮助缺项**与**文案精度**问题。

---

## 需要确认/选择的事项

### 1. `press` 的调用语法以哪个为准？
- 已统一为：`browser4-cli press <key> [ref]`
- `SKILL.md` / `README.md` / `commands.rs` / 运行时帮助现应保持一致
- CLI 同时兼容旧式 `press <ref> <key>`（当首参明显像 selector/ref 时自动归一化）

---

### 2. `type` 的调用语法以哪个为准？
- 已统一为：`browser4-cli type <text> [ref]`
- `SKILL.md` / `README.md` / `commands.rs` / 运行时帮助现应保持一致
- CLI 同时兼容旧式 `type <ref> <text>`（当首参明显像 selector/ref 且第二参不像 ref 时自动归一化）

---

### 3. `upload` 的调用语法以哪个为准？
- `SKILL.md` 示例：`browser4-cli upload ./document.pdf`
- 当前 `help.rs` / 实现：`browser4-cli upload <ref> <file>`
- `commands.rs` 当前契约：文件输入元素 `ref` 必填

**需要选择：**
- [ ] A. 保持当前 CLI 契约，更新 `SKILL.md` 为 `browser4-cli upload <ref> ./document.pdf`
- [ ] B. 如果产品真的希望支持省略 `ref`，则需要先定义“默认上传目标”的规则，再改 CLI 和帮助文档

---

### 4. “每条命令之后都会提供 snapshot” 这句话是否仍然成立？
- `SKILL.md` 当前表述：`After each command, browser4-cli provides a snapshot of the current browser state.`
- `main.rs` 中 `no_snapshot_commands()` 明确排除了以下命令的自动快照：
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
- 特殊情况：`open <url>` 会在导航完成后手动打印一次 snapshot，但 `open`（无 URL）不会。

**需要选择：**
- [ ] A. 把 `SKILL.md` 改成“多数会改变页面状态的浏览器交互命令会自动附带 snapshot，若需稳定产物请显式运行 `snapshot`”
- [ ] B. 如果产品要求“每条命令都自动附带 snapshot”，则需要修改 `main.rs` 行为，而不是只改文档

---

### 5. `tab-close 2` / `tab-select 0` 里的数字，表示“索引”还是“tabId”？
- `SKILL.md` 示例使用数字：
  - `browser4-cli tab-close 2`
  - `browser4-cli tab-select 0`
- 当前 `help.rs` / `commands.rs` 参数名都叫 `tabId`
- 后端兼容层会把旧参数 `index` / `id` 归一化到 `tabId`
- E2E 测试是从 `tab-list` 输出里提取真实 `id`，再调用 `tab-select` / `tab-close`

**风险：**
- 读 `SKILL.md` 的用户会自然理解成“按索引切换 tab”
- 读 `help.rs` 的用户会自然理解成“传真实 tabId”

**需要选择：**
- [ ] A. 正式文档统一为 `tabId`，并把 `SKILL.md` 示例换成“先 `tab-list` 再传 id”
- [ ] B. 正式支持“索引”语义，并在 CLI help 中把参数名改为更明确的 `index-or-tabId` 或提供单独选项

---

### 6. 全局帮助是否应该展示 `console` / `agent-*` / `co-*` 命令？
- `commands.rs` 里这些命令都不是 hidden
- 但 `help.rs` 的 `CATEGORIES` 没有启用：
  - `devtools`
  - `agent`
  - `collective`
- 实际运行 `browser4-cli help` 时，这些公共命令**不会出现在全局帮助里**

**需要选择：**
- [ ] A. 如果这些命令是正式对外能力，应把对应分类加回 `CATEGORIES`
- [ ] B. 如果不希望在全局帮助中暴露，就应该进一步统一策略（例如设为 hidden，或在 `SKILL.md` 明确分层）

---

### 7. `batch` 是否应该出现在全局帮助里？
- `help.rs` 为 `batch` 提供了单命令帮助和示例
- `commands.rs` 中 `batch.hidden = true`，因此不会出现在 `browser4-cli help` 全局列表里
- 但当前单测 `help::tests::test_generate_help_contains_commands` 仍然断言全局帮助里必须包含 `batch`

**已观测到的结果：**
- 运行 `cargo test help -- --nocapture` 时，`help::tests::test_generate_help_contains_commands` 失败
- 失败原因正是：全局帮助输出中不包含 `batch`

**需要选择：**
- [ ] A. `batch` 应该公开显示：把 `hidden` 改回 `false`
- [ ] B. `batch` 应该继续隐藏：那就同步修正测试，避免测试与实现打架

---

### 8. `help.rs` 是否应该保留原始大小写，而不是统一转小写？
当前 `help.rs` 会对全局列表中的描述、以及参数/选项说明执行 `to_lowercase()`，导致如下问题：
- `ArrowLeft` 在帮助里变成 `arrowleft`
- `JavaScript` 变成 `javascript`
- `Enter` 相关文案会变成 `enter`

这会让帮助文本：
- 与源码中的原始说明不一致
- 与 `SKILL.md` 示例风格不一致
- 在键名/专有名词上降低准确性

**需要选择：**
- [ ] A. 保留原始大小写，去掉 `to_lowercase()`
- [ ] B. 如果确实要统一风格，也应只对普通句子做风格化，避免改动代码字面量 / 键名 / 专有名词

---

## 当前更像“事实真值源”的位置
如果只看当前实现，下面这些文件更接近真实契约：
- 参数与命令签名：`src/commands.rs`
- 自动 snapshot 行为：`src/main.rs`
- 最终用户看到的帮助输出：`src/help.rs`

因此，**如果短期不改 CLI 行为，建议优先把 `SKILL.md` 回写到当前实现契约**；如果要保留 `SKILL.md` 里的更简洁交互，则应先补实现，再回写帮助。

---

## 本次核对时实际观察到的运行结果
- `browser4-cli help` 现应显示：
  - `press <key> [ref]`
  - `type <text> [ref]`
  - `upload <ref> <file>`
  - 不显示 `console` / `agent-*` / `co-*`
- `browser4-cli help snapshot` 当前显示：
  - 只有显式 `snapshot` 命令的说明
  - 没有“每条命令都会自动附带 snapshot”之类保证
- `cargo test help -- --nocapture` 当前失败：
  - `help::tests::test_generate_help_contains_commands`
  - 原因：断言全局帮助包含 `batch`，但实现没有输出 `batch`

