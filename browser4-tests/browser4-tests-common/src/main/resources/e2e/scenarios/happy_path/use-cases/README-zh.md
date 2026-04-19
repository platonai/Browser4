# Browser4 代理测试用例（中文站点）

本目录用于 **Browser4 agent** 的端到端（E2E）测试用例。每个 `.txt` 文件代表一个可被测试运行器自动发现并执行的用例。

## 编写要求（重要）

1. **限定中文网站**：用例中访问的网站应以中文内容为主（不限地区：大陆/港澳台/海外中文站点均可）。
2. **简单用例避免登录**：`Simple / Level 1` 用例尽量使用“无需账号即可完成”的流程，避免登录、验证码、短信校验、二次验证等不稳定步骤。
3. **可重复、可验证**：步骤应尽量确定性（deterministic），输出应有明确的落地文件或可核对结果（例如保存为 markdown/JSON）。

## 目录结构

- 每个 `.txt` 文件为一个独立用例
- 文件名建议使用两位数字递增前缀，便于排序与选择运行，例如：`01-xxx.txt`、`15-xxx.txt`

## 用例文件格式

每个用例文件由两部分组成：

- **注释行**：以 `#` 开头，用于描述与元数据
- **任务内容**：给 agent 执行的操作步骤（建议使用编号列表）

### 推荐注释头

- `# Use Case N: <用例名称>`
- `# Level: Simple | Complex | Enterprise`
- `# Type: Single-site | Multi-site | deterministic | agentic ...`
- `# Description: <一句话目的>`

### 示例（中文站点、无需登录）

```text
# Use Case 01: 新闻站点热榜提取（单站点）
# Level: Simple
# Type: Single-site, deterministic
# Description: 打开中文新闻站点，提取热榜标题并输出 markdown

1. go to https://news.ycombinator.com/ (示例：请替换为中文网站)
2. ...
```

> 提示：上述示例仅展示格式。实际用例请使用中文网站，并让步骤在“未登录”状态下可完成。

## 用例级别（Levels）

- **Simple（Level 1）**：单站点、确定性流程，优先避免登录
- **Complex（Level 2）**：需要一定推理/循环/聚合/跨站点对比的任务
- **Enterprise（Level 3）**：长链路可审计流程，可能涉及企业 SSO、权限控制与合规要求

## 运行测试

这些用例通常由仓库脚本执行（脚本路径以仓库为准）：

- 运行全部用例：`bin/tests/test-cases/run-e2e-agents.sh`
- 按编号选择用例：`bin/tests/test-cases/run-e2e-agents.sh -t "01,02,03"`
- 输出更详细日志：`bin/tests/test-cases/run-e2e-agents.sh --verbose`

> Windows 上如果你在 PowerShell 里运行 `.sh` 脚本，请确保已安装可用的 bash 环境（如 Git Bash/WSL）。

## 新增用例

1. 在本目录新建 `.txt` 用例文件，使用递增编号前缀（例如 `15-new-use-case.txt`）
2. 添加注释头（名称 / Level / Type / Description）
3. 编写可执行的步骤列表（尽量短、清晰、可在未登录状态下完成）

测试运行器会自动发现并执行新增用例。

## 参考资料

- 本仓库的 agent 用例设计参考：`docs-dev/agentic/use-cases.md`
