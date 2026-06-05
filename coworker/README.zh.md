# AI 协作助手（AI Coworker）

AI 协作助手是一个代理（agent），可协助你在指定仓库中完成各种任务。你只需创建任务文件，协作助手会处理这些文件、执行任务，并可将更改提交回仓库。

## 使用方法

启动助理 -> 批量起草任务 -> 复制到执行目录 [ -> 查看结果 - > 审查 ] -> 移动到批准目录 -> 自动提交推送

1. 运行 `coworker-scheduler.ps1` 以启动定时自动化
2. 在 `0draft` 下起草任务（或者任何地方）
3. 将已完成草稿的任务复制到 `1created` 目录以执行
4. 执行后，您可以在 `3_1complete` 中找到结果，在 300logs 中找到详细日志
5. 如有需要，复核结果
6. 将任务文件从 `3_1complete` 移动到 `5approved` 以便触发 git 推送

## 工作流程

任务文件会在 `coworker/tasks/` 目录下的编号文件夹中流转：

| 阶段   | 文件夹         | 说明                     |
|--------|----------------|--------------------------|
| 草稿   | `0draft`     | 在此处创建和编辑任务文件 |
| 队列   | `1created`     | 准备执行时移入此文件夹   |
| 规划   | `200plan`      | 代理规划阶段（自动管理） |
| 执行   | `2working`     | 代理正在执行任务         |
| 完成   | `3_1complete`    | 执行结束，可审查更改     |
| 审查   | `4review`      | 可选的人工审查阶段       |
| 已批准 | `5approved`    | 已批准任务，等待提交推送 |
| 已推送 | `6git-pushed`  | 已成功提交并推送         |
| 归档   | `700archive`   | 已归档的已完成任务       |

## 快速开始

1. **草稿** — 在 `coworker/tasks/0draft/` 创建任务文件。
2. **队列** — 准备好后将其移至 `coworker/tasks/1created/`。
3. **执行** — 运行协作助手脚本处理任务：
   - Windows: `.\coworker\scripts\coworker.ps1`
   - Python: `python .\coworker\scripts\coworker.py`
   - Linux/macOS: `./coworker/scripts/coworker.sh`
   - Linux/macOS (Python): `python3 ./coworker/scripts/coworker.py`
4. **审查** — 任务执行后会进入 `3_1complete`，可审查更改。
5. **批准** — 将任务移至 `5approved`，定时任务会自动提交并推送。

## 前置条件

需安装并认证 GitHub CLI（`gh`）。

安装方法详见：https://github.com/cli/cli#installation

## 标签（Tags）

你可以在任务文件中使用标签，提供额外上下文或控制行为。

支持的标签：

- `#auto-approve` — 任务完成后自动移至 `5approved`，无需人工审查，适用于低风险、可信任务。

## 提及（Mentions）

> **实验性功能**

在任务文件中提及 `@coworker`，可通知代理处理该任务。

## 与 Git 同步

任务批准后，可使用 git-sync 脚本将更改推送到仓库。

**Windows (PowerShell)：**

```powershell
.\coworker\scripts\workers\git-sync.ps1
```


**Linux/macOS (Bash)：**

```bash
./coworker/scripts/workers/git-sync.sh
```

## 统一调度器（PowerShell）

如果你希望只配置一个 Windows Task Scheduler 触发器，请使用统一调度器。它会按配置分别启动各个 PowerShell 子进程，保存 stdout/stderr 日志，并持续把任务状态写入 `logs/scheduled-tasks.status.json`。

任务定义位于 `coworker/scripts/coworker-scheduler.config.psd1`。每个任务都可以独立启用或禁用，并单独设置 `IntervalSeconds`、脚本路径、参数、可选的 `DependsOn` 依赖顺序，以及可选的 `PendingPaths` 输入队列。配置 `PendingPaths` 后，调度器会先检查这些文件/目录中是否真的有待处理内容，只有存在工作项时才会启动新的 PowerShell 子进程。

**Windows (PowerShell)：**

```powershell
.\coworker\scripts\coworker-scheduler.ps1
.\coworker\scripts\coworker-scheduler.ps1 -Once
```

默认调度任务：

- `coworker` — 在任务源监控之后处理排队中的 coworker 任务
- `draft-refinement` — 处理草稿润色队列
- `process-task-source` — 启用后轮询配置的任务源并分发新任务

统一调度器会调用 `coworker/scripts/deprecated/` 中保留的旧版一次性实现。更清晰的 PowerShell 入口分别是 `coworker/scripts/process-coworker-queue.ps1`、`coworker/scripts/process-draft-refinement-queue.ps1` 和 `coworker/scripts/process-task-source.ps1`；旧的 `run_*_periodically.ps1` 名称仍保留为兼容包装器，并会先输出弃用警告再转发。

## 旧版队列处理脚本

如果你需要直接执行一次性或循环式处理，请使用这些更清晰的旧版队列处理脚本：

- `coworker/scripts/process-coworker-queue.ps1`
- `coworker/scripts/process-draft-refinement-queue.ps1`
- `coworker/scripts/process-task-source.ps1`

统一调度器实际调用的实现位于：

- `coworker/scripts/deprecated/process-coworker-queue.ps1`
- `coworker/scripts/deprecated/process-draft-refinement-queue.ps1`
- `coworker/scripts/deprecated/process-task-source.ps1`

为了兼容旧流程，以下旧名称仍然可用，但会提示弃用：

- `coworker/scripts/run_coworker_periodically.ps1`
- `coworker/scripts/run_draft_refinement_periodically.ps1`

如果是定时自动化，请优先使用 `coworker-scheduler.ps1`。


**Windows (PowerShell)：**

```powershell
.\coworker\scripts\process-coworker-queue.ps1
.\coworker\scripts\process-coworker-queue.ps1 -Once
```

## 草稿润色

草稿润色使用 `coworker/tasks/0draft/refine/` 下的专用流程：

- `1ready` — 等待润色的草稿
- `2working` — 正在润色的草稿
- `3done` — 已完成润色、等待审阅的草稿

你可以润色单个文件，也可以传入一个文件夹批量处理；传入文件夹时会逐个文件执行。

**Windows (PowerShell)：**

```powershell
.\coworker\scripts\workers\refine-drafts.ps1
.\coworker\scripts\workers\refine-drafts.ps1 -Path .\coworker\tasks\0draft\refine\1ready
.\coworker\scripts\coworker-scheduler.ps1
```

**Linux/macOS (Bash)：**

```bash
./coworker/scripts/workers/refine-drafts.sh
./coworker/scripts/workers/refine-drafts.sh ./coworker/tasks/0draft/refine/1ready
pwsh ./coworker/scripts/process-draft-refinement-queue.ps1 -Once
```


