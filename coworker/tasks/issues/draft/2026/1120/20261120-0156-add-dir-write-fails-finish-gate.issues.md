# Issues: `--add-dir` 写到授权目录后任务必失败——finish gate 用 git 校验 `filesChanged`

> **Source:** 2026-11-20 01:52 本机实测（permission-e2e `B05a-add-dir-grant`，两次复现，live backend 4.16.9-SNAPSHOT，`browser4.agent.sandbox=off`）
> **Date:** 20261120-0156 · **Mode:** dev
> **状态:** **已修复 2026-11-20 12:00**（I1/I2 关闭；B05a 已加回 `taskCompleted: true`）
> **代码基线:** `4.16.x` @ `6df6fd520e`
> **报告人:** 本会话 agent（实现 `browser4-tests/permission-e2e` 时发现）

---

## 0.1 修复（2026-11-20 12:00）

`FinishEvidence` 新增 `grantedRoots`（`agentToolManager.accessPolicy.additionalDirs`，取自本任务真正的策略）：

1. **rule 4**：`filesChanged` 里落在授权根下的路径由**授权本身**背书，不再要求 git 或 mutation trace 能证明；
2. **rule 7**：同一类写入同时被视为 **material**，所以"漏报授权目录里的产出"仍会被要求补全
   （`FinishReportRepair` 从 mutation trace 补齐或返修一轮）——这堵住了 §2 担心的"奖励隐瞒"反激励。

对前缀相同的兄弟目录做了 component-wise 比较（`C:\granted` 不覆盖 `C:\granted-other`），
回归测试在 `FinishReportGateTest.grantedRootFileIsBackedByTheGrant` / `grantedRootPrefixIsNotTheRoot`。

---

## 0. 结论摘要

| 编号 | 问题 | 是产品缺陷吗 | 置信度 | 建议决策 |
|---|---|---|---|---|
| I1 | 任务用 `--add-dir` 授权的外部目录写入成功后，`system.taskComplete` 被 finish gate 拒绝：`filesChanged lists 1 file(s) git does not show as changed` | 是（授权功能与完成门槛冲突） | 高 | ACCEPT |
| I2 | 该拒绝把任务标成 `failed`（`errorCode=TOOL_ERROR`，`resumable=true`），但代理已完成要求的工作 | 是（终态语义） | 高 | ACCEPT |
| I3 | 复现不需要任何沙箱后端：`--access full` + `--add-dir` 即可 | 影响面 | 高 | 记录 |

**一句话**：`--add-dir` 明确承诺"这个文件夹可读可写可当工作目录"，但 finish gate 只认 `git status` 能看到的改动，于是**授权目录里的成功写入反而让任务失败**——功能自己打自己。

---

## 1. 复现

```powershell
$granted = "$env:TEMP\b4-grant-probe"      # 仓库之外、git 之外
New-Item -ItemType Directory -Path $granted -Force | Out-Null
browser4-cli agent run "Use the coding file write tool to write ok to $granted\grant.txt, then complete the task." --coding --add-dir $granted --follow --ndjson
```

实测（2026-11-20 01:52，任务 `8930c246-3719-468d-b5f1-5150799051c3`）：

```
tool.result  coding_write       ok=True   "✓ Wrote 3 chars to …\grant.txt (3 bytes)"
tool.result  system_taskComplete ok=True
task.failed
```

`agent result … --json` 给出的原因：

```
statusCode 417
message: 🛑 Invalid state: step 0 - Finish report rejected:
         filesChanged lists 1 file(s) git does not show as changed:
         D:/Users/pereg/AppData/Local/Temp/b4-perm-e2e/…/granted/grant.txt
errorCode: TOOL_ERROR, resumable: true
isolatedWorkspace: …/worktrees/8930c246-…
merge: refused
```

注意 `filesChanged` 里正是**授权目录**的那个文件——`git status` 当然看不到它（它在仓库之外）。

---

## 2. 根因方向

finish gate（`docs/AGENTS.md` 的 Definition of Done / `browser4.agent.finish.*`）要求报告里列的每个 `filesChanged` 都能被 git 证明是改动过的（`unlistedFiles` 的对称检查）。`--add-dir` 允许的目录位于检出之外，git 永远无法证明，于是：

- 代理诚实地上报了它写的文件 → 被拒；
- 代理若**隐瞒**这个文件 → 通过（但报告不完整）。

这是一个"奖励隐瞒"的激励，比单纯的误报更糟。

**建议**：finish gate 对 `policy.additionalDirs`（以及任何 `ReadablePaths.roots()` 之外的授权根）下的路径跳过 git 校验，改为"由运行记录（mutation trace）证明"——`filesChanged` 的校验本来就有 run-mutation 的来源可依（AGENTS.md 描述了 `filesChanged` 由 mutation trace 补齐的路径）。或者至少把这类路径算作"无法由 git 证明但允许"，并从拒绝列表里移出。

---

## 3. 影响

- `--add-dir` 的主要用途就是"把工作区外的语料/子项目给它"，写入是该功能的显式承诺（`agent run --add-dir <dir>` 的 help 写着 read/write/shell）；
- 任何以该目录为产出的任务（把结果写到用户的 data 目录、写一份报告到指定位置）都会稳定失败；
- `resumable=true` 会诱导代理/用户重试，但重试同样失败（非幂等因素除外）；
- 与 I3 无关：**不需要沙箱**，默认 `full` 预设即可复现。

---

## 4. 测试

- `permission-e2e` 的 `B05a-add-dir-grant` 现在只断言"写入成功"（`toolResult: write ok` + canary 存在），并在 `why` 里记下这个缺陷；等修复后应把 `taskCompleted: true` 加回去——这一步反过来就是回归测试。
- `B05b-add-dir-scope-reset` 已证明授权是任务级的（下一个任务同路径 → `code=PATH_DENIED`），所以缺陷只在"有授权时的终态判定"，不在授权本身。

---

## 5. 关联

- 同批发现的另两条：
  [`20261120-0145-tool-call-reports-permission-denied-as-success`](20261120-0145-tool-call-reports-permission-denied-as-success.issues.md) ·
  [`20261120-0146-shell-redirect-target-not-path-guarded`](20261120-0146-shell-redirect-target-not-path-guarded.issues.md)
- 设计文档：`docs-dev/copilot/20261120-permission-sandbox-e2e-plan.md` §9（首次实测）
