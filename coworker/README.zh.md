# AI 协作助手（AI Coworker）

AI 协作助手是一个代理（agent），可协助你在指定仓库中完成各种任务。你只需创建任务文件，协作助手会处理这些文件、执行任务，并可将更改提交回仓库。

## 使用方法

1. 运行 `start.ps1` 以启动定时自动化
2. 在 `main/0draft` 下起草任务（或者任何地方）
3. 将已完成草稿的任务复制到 `main/1ready` 目录以执行
4. 执行后，您可以在 `main/3done` 中找到结果，在 `~\.browser4-coworker\tasks\300logs` 中找到详细日志
5. 如有需要，复核结果
6. 将任务文件从 `main/3done` 移动到 `main/5approved` 以便触发 git 推送

## 工作流程

任务文件会在 `coworker/tasks/` 目录下的编号文件夹中流转。完整的管道文档（包括状态机图、转换规则和目录映射）请参见 `coworker/tasks/README.md`。

### 主任务管道

| 阶段   | 文件夹         | 说明                     |
|--------|----------------|--------------------------|
| 草稿   | `main/0draft`       | 在此处创建和编辑任务文件 |
| 队列   | `main/1ready`     | 准备执行时移入此文件夹   |
| 执行   | `main/2working`     | 代理正在执行任务         |
| 完成   | `main/3done`  | 执行结束，可审查更改（按日期归档：`YYYY/MMDD/<文件>`） |
| 审查   | `main/4review`      | 可选的人工审查阶段       |
| 已批准 | `main/5approved`    | 已批准任务，等待提交推送（按日期归档：`YYYY/MMDD/<文件>`） |
| 已推送 | `main/6git-pushed`  | 已成功提交并推送（按日期归档：`YYYY/MMDD/<文件>`） |

带有 `#auto-approve` 标签的任务会跳过 `3done`，直接从 `2working` 进入 `5approved`。

`3done`、`5approved` 和 `6git-pushed` 阶段中的任务由 `organize-task-files.ps1` 按日期进行归档。

### GitHub Issues 管道

| 阶段   | 文件夹 | 说明 |
|--------|--------|------|
| 草稿   | `200issues/draft/refine/0ready` | 待提取和润色的 issue 描述草稿 |
| 处理中 | `200issues/draft/refine/1working` | 代理正在提取和润色 issue |
| 完成   | `200issues/draft/refine/2done` | 提取完成，已暂存 |
| 错误   | `200issues/draft/refine/0error` | 达到最大重试次数后提取失败 |
| 待创建 | `200issues/github/commit/ready` | 已润色的 issue 文件等待通过 `gh` CLI 创建 |
| 已创建 | `200issues/github/commit/done` | issue 已在 GitHub 上成功创建 |
| 失败   | `200issues/github/commit/failed` | `gh` CLI 返回错误 — 需人工处理 |
| 草稿（人工） | `200issues/github/commit/draft` | 原始草稿等待人工审批 |

### 草稿润色管道（`main/0draft` 的子管道）

| 阶段   | 文件夹 | 说明 |
|--------|--------|------|
| 就绪   | `main/0draft/refine/1ready` | 等待润色的草稿 |
| 进行中 | `main/0draft/refine/2working` | 正在润色的草稿 |
| 完成   | `main/0draft/refine/3done` | 已完成润色、等待审阅的草稿 |
| 错误   | `main/0draft/refine/0error` | 达到最大重试次数后润色失败 |

## 快速开始

1. **草稿** — 在 `coworker/tasks/main/0draft/` 创建任务文件。
2. **队列** — 准备好后将其移至 `coworker/tasks/main/1ready/`。
3. **执行** — 运行调度器或直接运行工作脚本：
   ```powershell
   .\coworker\scripts\coworker-scheduler.ps1
   # 或单次执行：
   .\coworker\scripts\coworker.ps1
   ```
4. **审查** — 任务执行后会进入 `main/3done`，可审查更改。
5. **批准** — 将任务移至 `main/5approved`，定时任务会自动提交并推送。

## 任务管理器 GUI

`coworker/gui/` 中提供了一个基于 Web 的任务管道管理 GUI。它为浏览、创建、移动和删除各管道阶段中的任务提供了可视化界面 — 无需使用命令行。

```bash
cd coworker/gui
npm install
npm start -- --tasks-root ../tasks/
```

然后打开 **http://127.0.0.1:8090**。该 GUI 提供 REST API（`/api/stats`、`/api/tasks`、`/api/move`），默认仅绑定到 localhost。完整的 API 参考和 CLI 选项请参见 `coworker/gui/README.md`。

## 前置条件

需安装并认证 GitHub CLI（`gh`）。

安装方法详见：https://github.com/cli/cli#installation

## 配置

Coworker 将控制数据保存在当前仓库中，但任务执行可以针对不同的仓库。在 `coworker/scripts/config.psd1` 中配置两者：

- `Paths.WorkspaceRoot` 将 `coworker/tasks`、日志和记忆文件的根目录保持在此仓库中。
- `Paths.TargetRepositoryRoot` 设置任务模式 `gh copilot` 执行的目标仓库。如果省略，任务执行将回退到 `WorkspaceRoot` 以保持向后兼容。

## 标签（Tags）

你可以在任务文件中使用标签，提供额外上下文或控制行为。

支持的标签：

- `#auto-approve` — 任务完成后自动移至 `main/5approved` 而非 `main/3done`，无需人工审查。适用于可信、低风险的任务。

## 提及（Mentions）

> **实验性功能**

在任务文件中提及 `@coworker`，可通知代理处理该任务。

## 与 Git 同步

任务批准后，可使用 git-sync 脚本将更改推送到仓库。

```powershell
.\coworker\scripts\workers\git-sync.ps1
```

## 统一调度器（PowerShell）

统一调度器使用一个触发器管理所有定期协作任务。调度器按配置为各个任务启动独立的 PowerShell 子进程，保留控制台转储日志，持续写入任务状态到 `logs/scheduled-tasks.status.json`，并使用文件系统事件对队列变化做出反应，无需轮询任务文件夹。

任务定义位于 `coworker/scripts/coworker-scheduler.config.psd1`。每个任务都可以独立启用或禁用，并单独设置 `IntervalSeconds`、脚本路径、参数、可选的 `DependsOn` 依赖顺序，以及可选的 `PendingPaths` 输入队列。配置 `PendingPaths` 后，调度器会监控这些文件/目录，只有存在待处理工作项时才会启动新的子进程。

```powershell
.\coworker\scripts\coworker-scheduler.ps1        # 持续运行模式
.\coworker\scripts\coworker-scheduler.ps1 -Once  # 一次性模式：运行所有到期任务后退出
```

默认调度任务：

| 任务 | 工作脚本 | 触发路径 |
|------|---------|---------|
| `coworker` | `coworker.ps1` | `main/1ready` 或 `main/5approved` |
| `draft-refinement` | `workers/refine-drafts.ps1` | `main/0draft/refine/1ready` |
| `commit-github-issues` | `workers/commit-github-issues.ps1` | `200issues/github/commit/ready`（默认禁用） |
| `refine-github-issues` | `workers/refine-github-issues.ps1` | `200issues/draft/refine/0ready` |
| `fetch-github-issues` | `workers/fetch-github-issues.ps1` | （始终运行，每 10 分钟） |
| `triage-github-issues` | `workers/triage-github-issues.ps1` | `main/0draft/issues/github`（每 30 分钟） |
| `organize-task-files` | `workers/organize-task-files.ps1` | （始终运行，每 5 分钟） |
| `update-readmes` | `workers/update-readmes.ps1` | （始终运行，每 1 小时） |

## 队列处理脚本

在调度器之外直接执行一次性处理：

```powershell
.\coworker\scripts\process-coworker-queue.ps1
.\coworker\scripts\process-coworker-queue.ps1 -Once
.\coworker\scripts\process-draft-refinement-queue.ps1 -Once
.\coworker\scripts\process-task-source.ps1 -Once
```

如需定时自动化，请优先使用 `coworker-scheduler.ps1`。

## 草稿润色

草稿润色使用 `coworker/tasks/main/0draft/refine/` 下的专用流程：

- `1ready` — 等待润色的草稿
- `2working` — 正在润色的草稿
- `3done` — 已完成润色、等待审阅的草稿

你可以润色单个文件，也可以传入一个文件夹批量处理；传入文件夹时会逐个文件执行。

```powershell
.\coworker\scripts\workers\refine-drafts.ps1 -Path .\coworker\tasks\main\0draft\refine\1ready
```

## GitHub Issues 管道

Coworker 可以从自然语言的草稿文件中提取、润色并创建 GitHub issues。这是一个两阶段管道：

1. **润色** (`refine-github-issues.ps1`)：扫描 `200issues/draft/refine/0ready` 中的草稿文件，调用代理提取各个独立的 issue，将每个 issue 格式化为结构化的 Markdown 文件，写入 `200issues/github/commit/ready`。

2. **创建** (`commit-github-issues.ps1`)：扫描 `200issues/github/commit/ready` 中已格式化的 issue 文件，通过 `gh issue create` 在 GitHub 上创建它们。

Issue 文件格式：
```markdown
# Issue 标题

Issue 正文内容。

Labels: bug, enhancement
Assignees: 用户名
Repo: owner/repo
```

- `Labels`、`Assignees`、`Repo` 字段为可选。
- 在草稿最后 5 行中使用 `#auto-approve`，可将原始草稿也作为 issue 发布（例如作为父级/epic issue）。

```powershell
# 润色 issue 草稿
.\coworker\scripts\workers\refine-github-issues.ps1

# 在 GitHub 上创建 issue
.\coworker\scripts\workers\commit-github-issues.ps1
```

## 任务来源接入

Coworker 可以从外部来源接入任务并加入执行队列：

- **获取** (`fetch-github-issues.ps1`)：轮询已配置仓库的 GitHub 未关闭 issues，自动认领未分配的 issue，并将每个 issue 保存为 `main/0draft/issues/github/` 下的 Markdown 文件。
- **分诊** (`triage-github-issues.ps1`)：扫描已获取的 issues，自动将低风险、高相关度的 issues 移入 `main/1ready` 等待 AI 执行。

两个 worker 在调度器中均默认启用。


