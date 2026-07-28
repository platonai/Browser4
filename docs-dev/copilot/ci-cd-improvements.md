# Browser4 CI/CD 流程改进建议

> 评估基于 `.github/workflows/` 下的 8 个 workflow 与 `.github/actions/` 下的核心 composite action（`setup-environment`、`maven-build`、`run-tests`、`docker-build`、`health-check`、`cleanup-resources`、`start-application`、`verify-dependencies`、`create-release-notes`）的实际内容。

## 现状速览

| Workflow | 触发 | 职责 |
|---|---|---|
| `ci.yml` | push `v*.*.*-ci.*` / dispatch | 快速单测 + Docker 构建 + CLI E2E + PowerShell 校验 |
| `nightly.yml` | cron `0 0 * * *` / dispatch | 全量 Slow + Integration（排除 E2E） |
| `release.yml` | push `v*.*.*[-rc/-alpha/-beta/-dry_run]` / dispatch | JAR + Docker + 7 CLI 二进制 + 3 运行时 bundle + npm + GH Release |
| `release-cli.yml` | push `v*.*.*-cli*` / dispatch | CLI 独立发布路径（npm + 最新 Release 附件） |
| `cross-platform-smoke.yml` | path 变更 / dispatch | 跨平台运行时 bundle 冒烟测试 |
| `test-runtime-bundle.yml` | push `v*.*.*-rb*` / dispatch | 单平台 bundle 构建验证 |
| `maintenance.yml` | cron `0 6 * * *` / dispatch | 每日维护检查编排（状态文件回写） |
| `sync-to-oss.yml` | release published / dispatch | 资产同步到阿里云 OSS + latest 软链 |

**做得好的部分（应保留）**：composite action 复用度高；release 的 `prepare` 前置校验 + dry-run/alpha 模式；Docker push 与 npm publish 均有 3 次退避重试；`actions/attest-build-provenance` 提供构建溯源；JAR `Start-Class` 校验与产物数量/体积校验；OSS latest 软链；跨平台 matrix；`run-tests` 通过 `excludedGroups` 默认排除重测试，保证 `mvn test` 永远快。

## P0 — 必须修（影响最大）

### 1. 缺少 pull_request 触发门禁
所有 workflow 只在 **tag push / nightly cron / dispatch** 触发，**没有任何 `pull_request` 触发器**。PR 合并前完全没有自动构建/测试验证，坏代码直接进入主分支，只能靠 nightly 事后发现。

**建议**：新增 `pr.yml`（或在 `ci.yml` 增加 `pull_request` 事件），仅跑 `all-main-modules` 的快速单测（`excludedGroups` 沿用 ci 配置）+ Maven 编译，作为合并必过检查。可用 `paths-ignore` 排除纯文档改动。

### 2. ci.yml 步骤顺序导致快速反馈失效
`ci.yml` 中 `Run Tests`（快速单测）位于流程**末尾**，前面是 Maven Build → Docker Build → 启动容器 → Health Check → CLI E2E（15min）→ PowerShell 校验。即便一个 Fast 单测失败，也要先烧掉 20+ 分钟 Docker/E2E 才暴露。

**建议**：把 `Run Tests`（及 PowerShell 校验这类秒级检查）移到 `Maven Build` 之后、`Docker Build` 之前。快速失败应先于昂贵阶段。E2E 仍保留在容器起来之后。

### 3. 覆盖率只有上报、没有门槛
`run-tests` 上传 `**/target/site/jacoco/**`，但 pom 的 JaCoCo `check` goal 未启用 `haltOnFailure`，Qodana 的 `testCoverageThresholds` 整段被注释。覆盖率可静默回退，与 `docs/TESTING.md`“测试是给调度系统的契约”理念脱节。

**建议**：在 `ci` profile 启用 `jacoco:check`（`haltOnFailure=true`），从总 0.5 / 增量 0.7 起步逐步收紧；或在 `run-tests` 增加 `coverage_gate` 输入。这步与测试体系评估中的“强制 @Tag”是同一枚硬币的两面。

## P1 — 应尽快做

### 4. 冻结并迁移遗留 @Tag 词汇
`excludedGroups` 同时维护新词（`Slow/Heavy/Integration/E2E/Requires*`）和遗留词（`E2ETest/HeavyTest/SkippableLowerLevelTest/TestInfraCheck/OptionalTest`）。注释写明“逐步迁移”但未完成，正是 TESTING.md 自己列为反模式的“Tag 语义模糊”。

**建议**：设定截止日期，把遗留 tag 全量替换为新词，再从 `excludedGroups` 删除；同测试体系的 enforcer 规则联动。

### 5. 第三方 action 仅 pin 到 major
`actions/checkout@v5`、`actions/setup-java@v5`、`actions/upload-artifact@v6`、`actions/download-artifact@v7`、`actions/cache@v5` 都只 pin 大版本。而项目内部对 `dtolnay/rust-toolchain`、`softprops/action-gh-release`、`actions/attest-build-provenance`、`goto-bus-stop/setup-zig` 等已 pin 到 commit SHA——做法不一致。

**建议**：对所有第三方 action 统一 pin 到 commit SHA（或至少 `@v5` + 已知 good SHA），降低供应链篡改风险。

### 6. 安全扫描只做咨询、不阻断
`release.yml` 的 Docker Scout / Trivy 扫描是 `continue-on-error: true`，从不阻断发布；且无 PR 上的 `dependency-review`。

**建议**：对 `critical`/`high` CVE 设阻断（至少 release 阻断，PR 上告警）；增加 `actions/dependency-review-action` 到 PR 门禁。

### 7. Docker 每次从零构建
`ci.yml`/`nightly.yml` 在 Docker build 前执行 `docker system prune -af`，且 `docker-build` 未使用 buildx 缓存，每次全量重建镜像。

**建议**：改用 `docker/build-push-action` + `cache-from/to=type=gha`，复用 GitHub Actions 缓存，显著缩短 20–25min 的镜像构建。

## P2 — 锦上添花

### 8. 启用 Surefire 并行
`run-tests` 已内置 `parallel_tests` 开关（`-Dsurefire.parallel=methods -DthreadCount=4`），但 ci/nightly 均未传入。35–60min 的测试窗口有压缩空间。

**建议**：验证与 pom 的 `forkCount=1 reuseForks=true` 兼容后开启；注意单 JVM 复用下的测试隔离。

### 9. 测试报告解析脆弱
`run-tests` 用 grep 正则从 Surefire XML 抽 `failures="[1-9]` 统计，边界易错且不产出 PR 注解。

**建议**：改用 `dorny/test-reporter` 直接发布 PR 测试注解与趋势，替代手写解析。

### 10. E2E 缺重试，flaky 易误杀 release
`release.yml` 的 `cargo test --test e2e --level=EXTENDED` 单次执行，无重试；`RequiresAI` 类测试在缺 `OPENROUTER_API_KEY` 时仅告警仍照跑。

**建议**：引入 `--retries` 与 `--failed` 重跑；对需密钥的测试明确 `if` 跳过而非告警后执行。

### 11. ci.yml 缺 concurrency group
`ci.yml` 无 `concurrency`，同一 `-ci` tag 并发推送可能重入。release 已用 `cancel-in-progress: false`，ci 可加按 `ref` 的并发组。

### 12. 统一 Node 版本管理
`FORCE_JAVASCRIPT_ACTIONS_TO_NODE24` 这类环境变量是 workaround。建议 pin 到受支持 action 版本，移除该 hack。

## 快速落地清单（按 ROI）

1. 加 `pr.yml`：快速单测 + 编译，作为合并门禁。
2. 重排 `ci.yml`：单测/PowerShell 校验移到 Docker build 之前。
3. 启用 `jacoco:check` + `haltOnFailure`，设初始阈值。
4. 第三方 action 全部 pin SHA。
5. Docker build 改用 buildx + GHA cache。
6. 安全扫描对 critical/high 设阻断 + PR dependency-review。
7. 遗留 @Tag 词汇冻结并迁移。

> 注：上述第 1、2、3 项与测试体系评估（@Tag 强制、覆盖率门槛）强相关，建议合并成一个“质量门禁”改进 PR 一次性落地。

---

## 已落地（2026-07-06）

按“快速落地清单”第 1、2、3 项 + 遗留 tag 安全清理，已实施，文件均通过 YAML/XML 语法校验：

### ✅ 1. 新增 `pr.yml` — PR 合并门禁
- `on: pull_request`（opened/synchronize/reopened）→ `main`/`master`。
- 单 job `pr-gate`：`setup-environment` → `maven-build`（编译，skipTests）→ **PowerShell 脚本解析校验** → **快速单测（含覆盖率门禁）** → 状态检查。
- `permissions: contents: read`；`concurrency` 按 `ref` 取消旧运行（`cancel-in-progress: true`）。
- 快速单测用 `mvn test -Pall-main-modules,quality-gate`，排除所有非 Fast tag（含仍在使用的遗留 `E2ETest/HeavyTest/IntegrationTest/TestInfraCheck`），`run_pulsar_tests: false` 跳过重型 IT 模块，PR 控制在 ~25min 内。
- 效果：PR 合并前即验证编译 + 单测 + 覆盖率，坏代码不再直入主干。

### ✅ 2. 重排 `ci.yml` — 快速反馈提前
- 将 `Validate PowerShell test scripts` + `Run Tests` + `Check Test Status` 三步从流程**末尾**移到 `Maven Build` 之后、`Setup Rust Toolchain`/`Docker Build` 之前。
- 效果：release tag 上一个 Fast 单测失败现在几分钟内暴露，不必先烧 20+ 分钟 Docker/E2E。
- 同步清理 `excluded_groups`：移除无人使用的 `SkippableLowerLevelTest`、`OptionalTest`。

### ✅ 3. 启用 JaCoCo 覆盖率硬门槛（`quality-gate` profile）
- 修正原 `ci` profile 的死配置：`report-aggregate` 绑 `verify` 且无 `prepare-agent`，run-tests 只跑 `mvn test`，覆盖率从未真正采集。
- 新增 `quality-gate` profile：`prepare-agent`（initialize）+ `report`（test）+ `check`（test, `haltOnFailure=true`），规则 `BUNDLE/INSTRUCTION/COVEREDRATIO` 最小 **0.20** 起步。
- Surefire `argLine` 改为 `${jacocoArgLine} -XX:+EnableDynamicAgentLoading`，root 增加空默认 `<jacocoArgLine>`，使 jacoco agent 能注入且不影响普通构建。
- 该 profile 仅在 `pr.yml` 激活（`-Pquality-gate`），不触碰 `ci.yml`/`nightly.yml` 既有 release 流程。
- ⚠️ **调优提示**：0.20 是保守起步地板，待覆盖率数据确认后逐步提到 0.5 → 0.7；若首个 PR 因零测试模块或缺口报错，可临时下调 `<minimum>` 或对该模块传 `-Djacoco.skip=true`。

### ✅ 4. 遗留 @Tag 词汇安全清理（partial）
- 经全仓排查，`E2ETest`/`HeavyTest`/`TestInfraCheck`/`IntegrationTest` 仍被 ~20+ 测试使用，**不能**从 `excludedGroups` 移除（否则重型/E2E 会混进快速套件）。
- 仅移除了**无人使用**的 `SkippableLowerLevelTest`、`OptionalTest`（pom 默认 `excludedGroups` + ci.yml 同步清理），并更新注释说明迁移状态。
- 完整迁移（把源 `@Tag` 改到新分类法）属更大改动，列为后续 P1 任务，需配合测试体系评估的“强制 @Tag”enforcer 一起做。

### 未做（仍在清单、本次未动）
- 第 4 项（第三方 action pin SHA）、第 5 项（Docker buildx + GHA cache）、第 6 项（安全扫描阻断 + dependency-review）、第 7 项完整遗留 tag 迁移——均为 P1/P2，风险与改动面更大，建议单独 PR。
