# `agent run` 已知缺口登记册

日期：2026-11-17（含本轮实测：容器网络、worktree 里的 git 写）
性质：**缺口登记册（gap register）**，是 as-built 报告的配套文档 ——
[`20261117-agent-run-permissions-report.md`](20261117-agent-run-permissions-report.md) §13 的那张表，逐条展开成
"是什么 / 为什么 / 危害 / 现在怎么披露 / 怎么复测 / 怎么收口 / 该不该现在做"。

跟踪入口：[#647](https://github.com/platonai/Browser4/issues/647)（登记册的排期/讨论 issue，与本文件同口径同步）。

维护约定：ID（`G1`…）**稳定不复用**；关闭一条就把状态改成 `CLOSED (日期, 提交)` 并补上守护它的测试；
新增缺口用 §7 的模板。缺口不是 bug 列表——**B 类是决定**，它们出现在这里是因为"未强制"这件事必须可查。

---

## 0. 分类与字段

| 类 | 含义 | 谁来拍板 |
|---|---|---|
| **A. 该强制而未强制** | 意图写了，内核层没兑现 | 工程（技术/权限约束） |
| **B. 产品决定不强制** | 明知且如实披露，用"点名"换"围栏不被削弱" | 产品/安全 |
| **C. 未接线 / 成本** | 能做没做，或做了太贵 | 工程（排期） |

每条缺口包含：**现状 → 根因 → 危害（谁能利用、影响哪条默认路径）→ 系统怎么披露 → 证据（可复测）→ 收口路径与代价 → 建议时机**。

---

## 1. 总表

| ID | 类别 | 一句话现状 | 风险 | 收口代价 | 状态 |
|---|---|---|---|---|---|
| G1 | A | coding 的网络 `net=off` 未强制 | **高**（默认路径上可外传/下载） | 高（WFP，需管理员） | OPEN |
| G2 | A | 非 Windows 平台无内核边界（`landlock`/`bwrap`/`seatbelt` 均未实现） | **高**（那两个平台只剩字符串层） | 中（landlock 无特权，CI 可测） | OPEN |
| G3 | B | 容器内 `b4.run` 在后端执行，不在边界内 | 中（已披露，命令分级仍在） | 高（要放开主机 loopback） | OPEN（已拍板） |
| G4 | B | `coding.read/glob/grep` 在后端 | 低（方向安全，体验不一致） | 低（下沉读，或修报告） | OPEN（部分设计） |
| G5 | B | git/worktree/LSP/ripgrep 在后端；沙箱内 `git add`/`commit` 被拒 | 中（coding 体验，主流程不受影响） | 低（提示 + 产品 git 通道） | OPEN（已由测试钉住） |
| G6 | C | `windows-acl` 的文件写未下沉 | 低（该后端需管理员，实践少见） | 中（要一台已 provisioning 的机器） | OPEN |
| G7 | C | 每次文件变更 ~0.4s（一进程一次操作） | 低（只影响时长） | 中（常驻助手 = 生命周期风险） | OPEN |
| G8 | C | browse 未默认启用容器：容器内无解释器/工具链 | 中（browse 目标含"允许写脚本"） | 高（部署层决定） | OPEN |
| G9 | C | 容器 grant 只记录不回收；残留的包 SID ACE 让 `windows-low-il` 读不到该目录 | 中（整棵工具链目录被挡，报错是裸「拒绝访问。」） | 低（按 marker 撤销 + 启动自检） | OPEN（2026-11-19 已手工处置一次） |

---

## 2. A 类：该强制而未强制

### G1 — coding 的网络 `net=off`（唯一没兑现的轴）

- **现状**：`windows-low-il` 的 `enforcesNetwork = false`。`coding.shell` 里
  `python -c "import socket; socket.create_connection(('example.com', 443))"` 能出去；
  `--net-allow <host>` / `net.connect` 批准只是策略层，"逐域"没有兑现机制。
- **根因**：完整性级别只决定**对象访问**（读写），不决定 socket。Windows 上真正能拦的只有
  WFP 过滤器（管理员 + 机器级，按进程/路径匹配）或"仅 loopback 代理"（要求被约束进程自愿走代理 ⇒ 不是边界）。
- **危害**：coding 任务可任意下载/外传；决策 D3（默认 off + 逐域申请）此刻是**承诺**。默认路径（`--coding`）命中。
- **系统怎么披露**：`coding.permissions` 的 isolation 行：
  `network: NOT enforced by this backend (it cannot refuse a socket)`；提示词说明生效层。
- **证据 / 复测**：一个 coding 任务里跑
  `coding.shell(command="python -c \"import socket;socket.create_connection(('example.com',443));print('open')\"")`
  → 打印 `open` 即缺口仍在；同时 `coding.permissions` 必须写 NOT enforced（**不能只说成功**）。
- **收口路径**：WFP 规则集（安装期一次性管理员；每后端按进程路径/IL 匹配；`net=off` 默认拒绝、批准域加白）。
  代价：管理员、机器级影响、必须自带"卸载/回滚"与审计；**本机无管理员，无法验证**。
- **建议时机**：G2 之后单独一轮，先做"探测 + 报告"（把 WFP 状态与规则集纳入 `coding.permissions`），再做强制。

### G2 — 非 Windows 平台没有内核边界（结构性）

- **现状**：`IsolationProfile.backendChain` 在 Linux 返回 `landlock`/`bwrap`、macOS 返回 `seatbelt`，
  但三者**都没有实现** → `ExecutionSandboxes.of(id)` 抛 `SandboxUnavailableException`
  （"known but not implemented"）→ 就绪探测为 false → 被跳过 → 落回 `off`。
- **根因**：P1–P3 的实现集中在 Windows（`windows-low-il` / `windows-appcontainer`）；
  Linux/macOS 一直是"设计里有、代码里没有"。
- **危害**：那两台平台上整个强制层 = 字符串层，run `966a5f2e` 的教训原样存在。
- **系统怎么披露**：`OS boundary: none`（因为 id 确实是 `off`）——不冒充。
- **证据 / 复测**：Linux/macOS 上 `coding.permissions` → `OS boundary: none`；
  `ExecutionSandboxes.of("landlock")` 抛异常（`ExecutionSandboxesModeTest` 已覆盖"已知但未实现"）。
- **收口路径**：**landlock**（LSM，无特权、跨 `exec` 继承、可表达读写规则）——最接近 Low-IL 的统一答案；
  `bwrap --unshare-net` 补网络。CI 的 Linux 容器可直接实测 ⇒ 这是"最便宜的大洞"。
- **建议时机**：**下一个大项**（比 G1 便宜且可验证）。

---

## 3. B 类：产品决定不强制（已披露）

### G3 — 容器内 `b4.run` 不在边界内（2026-11-17 拍板）

- **现状**：browse+容器模式下 `b4.run` 保留历史启动（后端进程执行）；
  `coding.permissions` 写 `b4.run (this backend cannot host browser4-cli)`。
- **根因（实测）**：`browser4-cli` 装在操作者 profile（容器读不到），并通过 `127.0.0.1` 访问后端
  （容器连宿主 loopback 都被拒：curl exit 7/28，DNS 也不通 exit 6）。把 CLI 复制进工作区只解决"能执行"，
  不解决"能通信"；给容器 `privateNetworkClientServer` 会放开**主机本地任何服务**。
- **危害**：这条通道回到策略层（命令分级/白名单/审计仍在）。browse+容器是 opt-in，默认不命中。
- **系统怎么披露**：报告里显式出现在 NOT covered；设计 §14.2.1/§14.2.2 记录测量与决定。
- **证据 / 复测**：`WindowsAppContainerSandboxTest.realContainerNetworkAndStagedBinary`
  （宿主先验证 URL 可达；容器内先验证 `curl.exe --version` 能跑；再断言 loopback/internet 被拒、暂存 exe 能跑）。
- **收口路径（不建议）**：能力 + loopback 豁免（多半要管理员）⇒ 用围栏换 CLI。
- **建议时机**：不主动收口；若未来要"容器内完整 CLI"，重新开一轮设计。

### G4 — `coding.read/glob/grep` 留在后端

- **现状**：读由后端 JVM（Medium）执行；`coding.permissions` 里 `coding.read/glob/grep` 恒在 NOT covered。
- **根因（设计）**：coding 的读**目标**就是"继承当前用户"；browse 的读**范围**就是工作区
  （容器读得到的东西，后端也读得到），所以下沉读买不到额外边界。
- **唯一的真实不一致**：browse+容器模式下**可读根**（`~/.browser4` 数据、源检出）对 `coding.read` 可读，
  对容器里的脚本不可读 —— 策略比 OS 宽。方向安全（读不到不构成越权），体验是"工具能读、脚本读不到"。
- **危害**：低（无越权方向）；可能让模型误判"这个路径可用"。
- **系统怎么披露**：可读根在报告里列出，但**没有**标注"容器模式下脚本读不到"。
- **收口路径**：二选一 —— (a) 给 `SandboxFileOps` 加 `read/list`，让 browse 的读也下沉；
  (b) 在 browse+容器模式的报告里把容器读不到的可读根标出来（更便宜、更诚实）。
- **建议时机**：顺手做 (b)，(a) 等真的有人被绊到。

### G5 — git / worktree / LSP / ripgrep 在后端（沙箱内 git 写会被拒）

- **现状**：产品流程本身（patch/merge、language server、ripgrep）在后端执行。
  **沙箱内**：`--coding` 的隔离 worktree 里 `git status` 正常，但 `git add`/`git commit` 失败：
  `fatal: Unable to create '…/repo/.git/worktrees/<name>/index.lock': Permission denied`（exit 128），
  因为 worktree 的 `.git` 是**文件**，元数据在 `<main>/.git/worktrees/<name>`（工作区之外）。
- **根因（有意）**：把 `.git` 标 Low 等于让沙箱子进程能写 `.git/hooks/…`；之后**产品自己的**（Medium）
  `git commit`/`merge` 会执行这些 hook ⇒ 从沙箱到操作者的提权路径。所以宁可让沙箱内 git 写失败。
- **危害**：coding 任务里 agent 不能自己提交；**但产品 patch/merge 在沙箱外执行，主流程不受影响**。
  现在的报错对模型是纯噪声（`Permission denied` 没解释"这是边界"）。
- **系统怎么披露**：拒绝被翻译为 `PERMISSION_DENIED | code=SANDBOX_DENIED`，但没有"git 元数据在工作区外"的说明。
- **证据 / 复测**：`WorktreeGitBoundaryTest`（browser4-coding）——断言 `.git` 是指针文件、元数据在工作区外、
  沙箱内 `status` 成功、`add` 被拒且原因含 `index.lock`、**操作者侧** `add` 仍成功。
- **收口路径**：给 agent 一个**产品级通道**（如 `coding.gitCommit`：后端执行 + 子命令白名单 + 审计），
  而不是放宽 ACL；同时把提示改成"git 元数据在工作区外，由产品合并，或走 git 通道"。
- **建议时机**：下一轮的**低成本高收益**项（纯提示 + 一个白名单工具）。

---

## 4. C 类：未接线 / 成本

### G6 — `windows-acl` 的文件写没有下沉

- **现状**：`WindowsAclSandbox.executesFileOps = false` ⇒ `coding.write` 等仍在后端执行；
  报告写 `coding.write/… NOT covered`。
- **根因**：账户后端的 provisioning 从未围绕"逐操作子进程"设计——账户能否读工作区里的暂存载荷、
  跨卷 `copy`、`.m2` 只读边界下的写语义，都需要重新验证。
- **危害**：低。该后端要管理员离线建账户，实践少见；但它仍是**安装默认**（`windows-acl`），
  所以一旦某个管理员装好了账户，那条最宽的通道仍只剩策略层。
- **系统怎么披露**：`coding.permissions` 的 covered 列表里没有文件写。
- **证据 / 复测**：在已 provisioning 的机器上 `-Dbrowser4.agent.sandbox=windows-acl` +
  `coding.permissions` → 文件写不在 covered；`coding.write` 仍由后端执行。
- **收口路径**：在这样一台机器上跑通 `SandboxedFileOps`（含探针三段）→ `executesFileOps = true` + 测试。
- **建议时机**：有机器可用时；否则保持现状（已如实标注）。

### G7 — 每次文件变更 ~0.4s

- **现状**：一进程一次操作（`powershell -File`）；实测 8 次操作 ~2.5s（含首次打标）。
- **根因**：设计取舍（见设计 §16.2）——常驻助手能省启动，但引入单飞锁、挂起检测、重启、孤儿清理，
  失败模式正是本轮要消灭的"静默"那一类。
- **危害**：只影响时长：一个写/replace 100 次的任务多约 40s；不改变任何语义。
- **系统怎么披露**：不需要（无安全含义）；文档里有测量值。
- **证据 / 复测**：`SandboxFileOpsTest` 的耗时（sandbox 包报告里 `SandboxFileOpsTest` 时间），
  或 `Measure-Command { powershell.exe -NoProfile -File b4-fs.ps1 -Op mkdir -Target … }`。
- **收口路径**：常驻助手（长度前缀/JSON-lines 协议）或批量提交；`SandboxFileOps` 接口不变。
- **建议时机**：等真实任务耗时数据证明它占主导（比如一次运行 100+ 变更）。

### G8 — browse 未默认启用容器：容器内没有解释器/工具链

- **现状**：容器仍 opt-in（`-Dbrowser4.agent.sandbox.browse=on`）。
- **根因（实测）**：容器内 `python`/`node`/`java` 都不可执行；本机 Python/JDK 在 `D:` 卷，
  而容器连 `D:\` 都看不到（卷根没有容器 traverse）；profile 按设计不可读。
  而 browse 模式的目标包含"允许写脚本"。
- **危害**：中。browse 默认化会先打断"写脚本"这类任务；`b4.run` 已经不是障碍（见 G3）。
- **系统怎么披露**：容器模式属于 opt-in，`config.md` 说明它读不到 profile。
- **证据 / 复测**：容器内 `if exist D:\`（看不到）、`python --version`（不可执行）；
  正式探针见 `WindowsAppContainerSandboxTest`（网络/暂存二进制部分）。
- **收口路径**：部署层决定——把共享工具链装到 `ALL APPLICATION PACKAGES` 可读的位置，
  或给那些目录预置 ACE（需管理员/WRITE_DAC）；否则接受"容器模式不给脚本解释器"。
- **建议时机**：与 G2 一起考虑（都属于"边界之外的可达性"问题）。

### G9 — 容器 grant 只记录不回收：残留的包 SID ACE 会让 `windows-low-il` 读不到该目录

- **现状**：`WindowsAppContainerSandbox.grant()` 每次授权都在 data 目录旁边写一个 marker
  （`grantedRoots` + `<digest>.grant`），但**没有撤销通道**；容器实验/测试跑完后，
  目标目录上的 `icacls … /grant "*<containerSid>:(OI)(CI)(M|RX)"` 会一直留着。
- **根因（实测，2026-11-19）**：对象 DACL 里只要有一条**具体包 SID**（`S-1-15-2-<hash>`，
  不是 `S-1-15-2-1`）的显式 ACE，**低完整性 + 受限令牌**（`windows-low-il` 的子进程）对该对象的
  **读/枚举**就会被内核拒绝：`dir` 报「找不到文件」、目录内 exe/`.cmd` 报「拒绝访问」、
  `node -e readdirSync` 报 `EPERM`；同一账户在普通 shell、`runas /trustlevel` 下都正常。
  实测矩阵与最小复现见 [`docs/agent-permissions.md` §10.10](../../docs/agent-permissions.md)。
- **危害**：中。一个遗留 ACE 会让**整棵工具链目录**对 coding 沙箱不可读，而报错是裸的
  「拒绝访问。」——2026-11-19 那次 `agent run` 为此浪费了约 25 步（run `4125acaa`）。
- **系统怎么披露**：`code=SANDBOX_DENIED` 的 hint 现在点名这条 ACE 与 `icacls … /remove *<sid>`
  （`SandboxDenials.LOW_IL_HINT`）；症状/处置写进 `docs/agent-permissions.md` §10.10。
- **证据 / 复测**：三阶段对照（无 ACE → 可列；加包 SID ACE → 列不出；`/remove` → 恢复）3/3 稳定；
  测试见 `SandboxDenialsTest`（localized denial + hint）与 `SandboxStatusTest`。
- **收口路径**：① 用 marker 实现 `revoke`（`icacls <dir> /remove "*<sid>"`），
  在容器后端停止/任务结束时回收；② 启动自检：low-il 就绪探针顺带扫一遍工作区与工具链根的
  DACL，发现包 SID ACE 就**在选后端阶段**警告并给修复命令；③ 明确"绝不给用户全局目录授权"。
- **建议时机**：先做 ③（约定）与 ②（告警），再做 ①（回收）。

---

## 5. 不是缺口（避免误读）

| 现象 | 为什么不是缺口 |
|---|---|
| coding 模式下 `coding.read` 能读 `~/.m2`、`D:\` | 就是 `ReadScope.CURRENT_USER` 的目标（决策 D1） |
| `--unrestricted` 下受保护/敏感 rails 消失 | 显式 opt-in + 审计；**凭据库**与**杀/重建后端的命令**两条仍在 |
| `b4.run` 的 lifecycle / `skill install` / `tool call` 被拒 | 产品通道的固定护栏 |
| `windows-appcontainer` 默认不启用 | 待 G8 解决；`autoSelectable=false` 是显式声明 |
| 沙箱内 `git add` 失败（G5 的现象另一半） | 这是**有意的安全边界**，见 G5 根因 |

---

## 6. 症状 → 缺口（自查）

| 你看到的 | 是哪个 |
|---|---|
| shell 里 `python -c "socket…"` 能联网；`--net-allow` 像没生效 | G1 |
| 非 Windows 上 `OS boundary: none` | G2（诚实，不是 bug） |
| 容器模式下 `b4.run` 可用，但报告说它 NOT covered | G3 |
| `coding.read` 能读某路径，容器里脚本读不到 | G4 |
| 沙箱内 `git add` → `Permission denied … index.lock` | G5 |
| 账户后端上 `coding.write` 不在边界内 | G6 |
| 一个任务里写文件明显变慢 | G7 |
| 容器里 `python/node/java` not found | G8 |

---

## 7. 新增一条缺口的模板

```markdown
### G<n> — <一句话>

- **现状**：<代码/行为事实，含后端 id 与开关>
- **根因**：<技术约束 or 产品决定（注明拍板日期）>
- **危害**：<谁能利用 / 命中哪条默认路径 / 评级 高/中/低>
- **系统怎么披露**：<coding.permissions 行、审计码、文档位置>
- **证据 / 复测**：<测试名 或 可复制的命令>
- **收口路径与代价**：<方案 + 代价，含"为什么还没做">
- **建议时机**：<排序依据>
```

---

## 8. 建议收口顺序

1. **G2 landlock**（A 类、无特权、CI 的 Linux 容器里可实测，一次覆盖两个平台）
2. **G5 的提示 + 产品 git 通道**（便宜，直接改善 coding 体验，且**避免** hook 提权）
3. **G4 的诚实修正**（browse+容器报告里标注读不到的可读根）
4. **G1 WFP**（要管理员，单独一轮：先探测/报告，再强制）
5. **G6**（有 provisioning 的机器时）
6. **G7 常驻助手**（等真实耗时数据）

G3、G8 不建议在没有新产品决策前动：前者要用围栏换 CLI，后者是部署层问题。
