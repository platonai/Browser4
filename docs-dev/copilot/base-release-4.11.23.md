# 基库发布：4.11.23 全流程与本轮踩到的点

日期：2026-10-01 ｜ 仓库：`platonai/Browser4base`（`D:\workspace\Browser4\browser4base`）｜ 产物：`pulsar-bom 4.11.23`

目标：把"基库怎么发、发之前要验什么、发失败怎么查、发布物什么时候可拉"写成可复用的清单，
并记录本轮真实遇到的阻塞（跨模块的旧断言没跟着行为变更走）。

---

## 结论摘要

1. 发布**只需要推一个 tag**：`.github/workflows/release.yml` 由 `v*.*.*`（排除 `v*.*.*-*`，允许 `-rc.*`）触发；
   本地没有 Central/GPG 凭据（`~/.m2/settings.xml` 无 server），**不要**尝试本地 `mvn deploy`。
2. 第一次推 `v4.11.23` **失败在 Run Tests 步骤**，唯一失败点是
   `ai.platon.pulsar.skeleton.common.urls.CombinedScopedUrlNormalizerTest
   .testNormalizeWithSpecialCharactersInUrlNotSupported`（`CombinedScopedUrlNormalizerTest.kt:131`，
   `expected: <true> but was: <false>`）：上一提交 `244d6f47f` **有意**把
   `http://example.com/!@#$%^&*()` 从"拒绝（NIL）"改成"规范化并丢弃 fragment"，并更新了
   `URLUtilsTest`，**但漏掉了另一个模块里钉住旧行为的断言**。
   → 教训：行为变更提交必须全仓搜索"钉住旧行为"的断言，尤其是跨模块的（`pulsar-skeleton` 与
   `pulsar-common` 分属不同模块、不同测试根）。
3. 修好后把 tag **移到**含修复的提交再推（`deploy` 只在 tests 通过后才跑，所以第一次没有产生任何发布；
   也没有留下 GitHub Release 需要清理）。
4. 第二次运行 5 个 job 全绿：`ci-build` / `deploy` / `sync-main` / `bump-version` / `release-summary`。
5. **Central 发布是异步的**：`central-publishing-maven-plugin` + `autoPublish=true`，`mvn deploy` 只是上传
   部署包（bundle）；repo1 出现 4.11.23 实测比 workflow 完成晚约 **17 分钟**。要用轮询确认，别把
   "workflow success" 当成"已经可拉"。
6. 发布后 `bump-version` job 自动把 `main` 提到 `4.11.24-SNAPSHOT` 并推回（本次为 `660815c99`）。

---

## 1. 发布流程（release.yml 实际做什么）

| job | 行为 |
|---|---|
| `ci-build` | checkout → 构建（`skip_tests` 默认只在 workflow_dispatch 下为 true；**推 tag 时测试会跑**：`-P tests-integration,tests-e2e -DrunITs=true`，排除组 `Slow,SlowTest,Flaky,Manual,ManualOnly`，超时 35 min）→ 生成 release notes → 建 GitHub Release → 更新文档 |
| `deploy` | 校验 secrets（`CENTRAL_USERNAME/PASSWORD`、`GPG_PRIVATE_KEY/PASSPHRASE`）→ 在**工作区内**把 `VERSION` 与所有 `pom.xml` 的 `x.y.z-SNAPSHOT` 替换成 `x.y.z` → `./mvnw deploy -P deploy,release -DskipTests` |
| `sync-main` | `git push origin refs/tags/<tag>:refs/heads/main --force`（把 main 硬重置到 tag） |
| `bump-version` | 计算下一个 patch 快照（`4.11.23` → `4.11.24-SNAPSHOT`），改 `VERSION` + 全部 pom 并推 main |
| `release-summary` | 汇总表格 |

> **tag 类型**：`sync-main` 推的是 `refs/tags/<tag>`。**轻量 tag** 指向 commit，强推到分支没问题；
> **annotated tag** 指向 tag 对象，把它推到 `refs/heads/main` 会被拒（该 job 有 `continue-on-error: true`，
> 只会告警）。本仓历史 tag（如 `v4.11.22`）都是轻量的 —— 用 `git tag <name>`（不加 `-a`）。
> 注：这一点本轮未实测到失败（首次运行在 tests 就红了，`sync-main` 根本没跑），按既有风格统一成轻量 tag 规避。

查看运行：

```powershell
gh run list --repo platonai/Browser4base --limit 5
gh run view <run-id> --repo platonai/Browser4base --json status,conclusion,jobs
gh run view <run-id> --log-failed --repo platonai/Browser4base   # 关键：失败步骤的完整日志
```

（匿名 `Invoke-WebRequest` 拉 `actions/jobs/<id>/logs` 会 403；`gh` 已认证，直接用它。）

---

## 2. 发布前本地该验什么（本轮实际做法）

1. **全量安装到本地仓库**（Browser4 要用到整套基库，不只是 `pulsar-browser`）：
   ```powershell
   cd D:\workspace\Browser4\browser4base
   .\mvnw.cmd -D"skipTests" install      # 25 个模块，本轮约 3 分钟
   ```
2. **跑与改动相关的测试类**（跨模块别漏）：
   ```powershell
   .\mvnw.cmd -pl pulsar-core/pulsar-browser -am -D"test=ProfilePathsTest,WebSocketChromeImplTest,PulsarBrowserIdTest" `
       -D"failIfNoTests=false" -D"surefire.failIfNoSpecifiedTests=false" `
       -D"surefire.excludedGroups=Slow,Heavy,E2E,SDK,ManualOnly" test
   .\mvnw.cmd -pl pulsar-core/pulsar-skeleton -am -D"test=CombinedScopedUrlNormalizerTest" ... test
   .\mvnw.cmd -pl pulsar-core/pulsar-core-tests/pulsar-common-tests -am -D"test=URLUtilsTest" ... test
   ```
   注意 `-Dtest=` 只在**包含该测试类的模块**生效；`URLUtilsTest` 不在 `pulsar-common` 模块里，
   而在 `pulsar-core/pulsar-core-tests/pulsar-common-tests`（用 `git show --name-only <commit>` 定位最快）。
3. **`-surefire.excludedGroups=` 覆盖**：默认排除 `Integration`，本地要跑集成/带浏览器用例时给一个更短的排除列表。
4. 若改动涉及"跨模块的既有断言"，**推 tag 之前**先在本地把 release 的测试开关近似跑一遍
   （至少把本仓所有引用被改行为的测试类找出来），避免像本轮那样在 CI 里才发现。

---

## 3. Browser4 侧升级（基库发布后）

1. 两份 pom 同步：根 `pom.xml` 与 `browser4-dependencies/pom.xml` 的 `<browser4-base.version>`。
   **先在 SNAPSHOT 上联调，发布后再切正式版**（本轮：`4.11.22` → `4.11.23-SNAPSHOT` → `4.11.23`）。
2. 验证 Central 可拉（发布具有延迟）：
   ```powershell
   Invoke-WebRequest -Uri 'https://repo1.maven.org/maven2/ai/platon/pulsar/pulsar-bom/4.11.23/pulsar-bom-4.11.23.pom' -Method Head
   ```
3. dev 模式后端来自 `browser4-apps/browser4-bundle/target/runtime-bundle`，
   **基库版本变化不会自动触发重建**（陈旧判定看的是仓库自身源码）：手工
   `pwsh browser4-apps/browser4-bundle/build-runtime-bundle.ps1`，然后确认 bundle 里的
   `pulsar-browser-<version>.jar` 是新的。
4. 收尾：`./b4w.ps1 stop` 关掉 dev 后端，避免后续排查误连旧进程。

---

## 4. "本地装一个打过补丁的版本"这种做法要当心

本轮为了在基库发布前验证 Browser4，曾把打过补丁的源码**以旧版本号**装进本地 `.m2`
（`versions:set -DnewVersion=4.11.22` → `install` → 还原 pom）。可行但**必须**：

- 只用 `git checkout -- '*.xml'` 之类**精准还原**，不要 `git checkout -- .`（会连带回滚源码改动）；
- 明确它只影响本机解析（同名版本被本地 jar 顶掉），**事后删掉** `~/.m2/.../<artifact>/<version>/`
  让 Maven 重新拉官方件 —— 否则后面所有构建都在用"看起来像正式版、其实是本地构建"的产物；
- 记录在案（本轮在最终汇报里说明了这一点）。

更干净的替代：临时把 Browser4 指到基库的 SNAPSHOT 版本号（前提是整套 SNAPSHOT 都已 install）。

---

## 5. 排查速查

| 症状 | 第一个动作 |
|---|---|
| workflow 红在 Run Tests | `gh run view <id> --log-failed`；失败测试名会出现在 `[ERROR] Tests run:` 行 |
| `deploy` skipped | 说明 `ci-build` 未成功；先修 tests（deploy 依赖 `needs: ci-build`） |
| Central 404（刚发布完） | 正常，等 5–30 分钟再确认（异步 autoPublish） |
| `main` 没被同步到 tag | 看 `sync-main` 是否告警；确认 tag 是轻量 tag |
| 本地 `mvn deploy` 失败 | 本地无凭据，改为推 tag 让 CI 发 |

---

## 6. 分提交与验证的工程细节（本轮踩到，可复用）

1. **用 `git apply --cached --unidiff-zero` 做 hunk 级拆分不安全。**
   本轮把 `main.rs` 的"扩展 ID"与"WebSocket"两组 hunk 拆到不同提交时，零上下文补丁被 git 的模糊定位放错位置：
   新函数签名被插到旧函数体**之后**，产生了一个 `error: expected item, found keyword 'for'` 的**不可编译中间提交**。
   安全做法：
   - 优先**按文件切分**，并用**提交顺序**满足依赖（被依赖的文件先提交，例如 `daemon.rs` 的
     `pub(crate)` 助手 → 使用它的 `main.rs`）；文档与脚本单独成提交；
   - 必须拆同一文件时用交互式 `git add -p` 并**逐个提交编译验证**，不要靠脚本拼补丁。
2. **验证"某个提交可编译"的正确姿势**（工作树里还留着后续改动时）：
   ```powershell
   git stash push --keep-index --quiet -m verify   # 工作树回到索引（=该提交）状态
   cargo test --bin browser4-cli --no-run          # 或 cargo check
   git stash pop --quiet
   ```
   本轮就是靠它抓到上面那个坏提交 —— 如果只在最后跑一次全量测试，坏提交会留在历史里。
3. **坏提交要从历史里摘掉**：`git reset --mixed HEAD~1`（保留工作树，索引回到上一提交），
   再按正确顺序重做；**推之前**确认 `git log` 里没有它（本地坏提交从未推送过）。
4. **提交信息写"验证过什么"**：本轮每个功能提交都带一行
   `Verified: cargo test … (N passed); mvn -Dtest=… (N passed); real-machine e2e …`，
   评审者不必反问"这跑过没有"。
5. **`cfg!` 的两个分支在任何平台都会被类型检查 —— 平台专用实现与其 stub 的签名必须同步。**
   本轮把 `windows_process_list` 的参数从 `&str` 改成 `&[&str]`，忘了改非 Windows 的 stub，
   于是 Windows 本地全绿、**Linux 编译直接 `E0308`**（`error[E0308] ... expected &str, found &[&str]`），
   `Cross-Platform Smoke Test` 和依赖 CLI 二进制的 PowerShell 生产测试一起变红（`b4w --help` 退出码 1）。
   把关办法：
   - CI 的 `Cross-Platform Smoke Test` 是真正的守门人（本轮就是它抓到的）；
   - 本地可以试 `rustup target add x86_64-unknown-linux-gnu && cargo check --target x86_64-unknown-linux-gnu`，
     但本机实测会被依赖 `aws-lc-sys` 的构建脚本挡住（需要 Linux 侧 C 工具链），所以**别把"本地 Windows 绿"当成"CI 绿"**；
   - 改平台专用函数的签名时，顺手 grep 一遍 `cfg(not(target_os = "windows"))` 的桩：签名不一致就是这类错误的唯一来源。
6. **文档有硬性行数上限，而且由 CI 门禁把关（M1-M8 / M6）。**
   `bin/skill-doc-lint.ps1` 在 `PowerShell Tests` 工作流里跑，规则 M6：`SKILL.md`、decision 文档、procedure 文档
   **≤ 500 物理行**（catalog 不设上限）。本轮给 `skills/browser4-cli/references/browser-modes.md` 补了 WebSocket
   段落，把它从 ~490 推到 **508 行**，门禁直接失败：
   ```
   checked 37 files, 1 issue(s) [M6=1]
     browser4-cli/references/browser-modes.md  [M6] decision doc is 508 lines (cap 500)
   ```
   做法：**改 `skills/**/*.md` 后先本地跑 `pwsh bin/skill-doc-lint.ps1`**（输出 `checked N files, 0 issue(s)` 才算干净，
   它会检查行数、模板章节、链接、emoji 等）。触发是路径过滤的：`skills/**/*.md` 的改动会拉起 `PowerShell Tests`，
   而只改 `cli/**` 的 Rust 提交不会 —— 所以"没触发"不等于"没门禁"，必要时 `gh workflow run ps1-tests.yml --ref <branch>` 手动补跑。
7. **发版前先把分支跑绿。** 本轮顺序是：先修 Linux 编译（`bb0e954`）+ 文档行数（`bb2f064`），
   等 `Cross-Platform Smoke Test` 与 `PowerShell Tests` 都 success，再 `node bin/version.mjs bump patch` → 打 tag。
   另外 `bump patch` **只更新 VERSION 与 Maven pom**，CLI 文件要再跑一次 `node bin/version.mjs cli sync`
   （否则 `version.mjs check` 报 `cli/Cargo.toml: … expected …`），全部一致后才 `git tag -a vX.Y.Z` 推送。


