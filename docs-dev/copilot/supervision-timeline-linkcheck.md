# 监督时间线（b4 插件开发任务 browser4-linkcheck 执行记录）

日期：2026-08-21 · 后端：HEAD b7ed815d85 的 runtime bundle @ 8183（监督会话自起，绑定本工作树）· LLM：deepseek-v4-pro（~/.browser4 配置）
任务定义：`docs-dev/copilot/plugin-dev-task-linkcheck.md`。监督方式：`code devtask` 计划 → b4 代理（agent run）执行 → 监督方独立复验。

## 0. 环境准备（本轮发现 2 个环境级问题）

0.1 8182 运行中的后端工作区绑定在兄弟工作树 D:\workspace\Browser4\Browser4-4.14（code workspace 返回该树，HEAD 同为 b7ed815d85），code 工具全部读写错误工作树。处置：复用同 HEAD 的 bundle，用 -Dbrowser4.agent.workspace=feat 与 --server.port=8183 自起后端，CLI 全部 --server http://localhost:8183。

0.2 直接调用 08/19 构建的陈旧 browser4-cli.exe 时，code devtask --verify 30s 客户端超时（600s coding 超时修复不在二进制内）。重建 CLI（1m20s）后消失 → 属陈旧产物而非 HEAD 缺陷；提示 b4w.ps1 的自动重建不可绕过。

0.3 8183 后端 8088 MCP-over-HTTP 端口与 8182 后端冲突，日志 WARN 降级（REST 路径不受影响）。记录为 P2。

## 1. code devtask 计划质量（HEAD）

- 6 步计划：scaffold 步骤存在、无 commit 步骤、repo-consistency 收尾。
- P1 缺陷（回归）：任务文本按任务文档要求提到 DEPENDENTS 四键（browser4-agentic、browser4-core/browser4-protocol、browser4-core/browser4-skeleton、browser4-pdk），inferModules 把它们收进 modules；maxByOrNull 同深度取先 → mvnBuild 与测试绑定到 browser4-core/browser4-protocol 而非新插件模块。
- --verify：编译 browser4-core/browser4-protocol（存在且可编译）→ Build succeeded 假阳性（插件尚不存在）。v1.4 问题 1.1 的残余形态在 HEAD 复现。
- 步骤 2/3 路径错误：coding.read 与 coding.impact 使用 src/main/resources/linkcheck/countLinks.js（按工作区根解析），应为 browser4-plugins/browser4-linkcheck/src/...。v1.3 的裸文件名修复只覆盖无斜杠文件名。

## 2. b4 代理执行轮次

| 轮 | 会话 | 步数 | 耗时 | 结果 | 关键证据 |
|---|---|---|---|---|---|
| 全量任务 | linkcheck1 | 5 | 3m36s | 失败（NOOP_LIMIT） | 每步首个 LLM 请求崩溃（Unknown exception: text cannot be null or blank）；工具循环 12 次跑满溢出，溢出成果被丢弃、整步记为成功无动作；textOnly.stall(5) 熔断。--noop-limit 8 无效（不作用于 textOnlyStallLimit） |
| T1b scaffold+JS+Service | linkcheckT1b | 2 | 约6min | 成功 | 调参后（toolLoop=40）收敛；countLinks.js 与 LinkcheckService 按规格重写；mvn compile exit 0 |
| T2 Config+Executor+AutoConfig | linkcheckT2 | 3 | 约13min | 成功 | 三文件符合规格；manifest 核对正确；compile exit 0 |
| T3 单测 | linkcheckT3 | 2 | 约8min | 成功 | 2+5=7 用例全过；camelCase+@DisplayName；MutableConfig 构造 fromConfig 输入 |
| T4 DEPENDENTS+README+门禁 | linkcheckT4 | 2 | 约20min 后取消 | 工作完成未收敛 | ModuleMap 四键 + README 已写 + JAR 已产出；step2 工具循环 40 次跑满（单步 1,252,044 输入 token）不收敛；门禁由监督方独立执行 |

小任务配方依然有效：T1b/T2/T3 均 2-3 步完成且产物质量高；全量任务与多文件+门禁任务触发循环溢出/上下文膨胀（与 v1.3/v1.4 结论一致）。

## 3. 独立复验（监督方）

- mvnw.cmd -f browser4-plugins/browser4-linkcheck/pom.xml package → BUILD SUCCESS，Tests run: 7，Failures: 0，Errors: 0；JAR 23.6KB。
- code validate plugin --path browser4-plugins/browser4-linkcheck → All checks passed。
- code validate repo-consistency → All checks passed（DEPENDENTS 补齐后无漂移）。
- git 范围审查：仅 ModuleMap.kt（MODULES 1 + DEPENDENTS 4 键）、browser4-plugins/pom.xml（1 行）、插件目录、监督文档；无越界修改、无提交。
- 部署冒烟：JAR 复制到 plugins/ → 重启 → 日志 Registered tool executor for domain linkcheck。
- 工具调用冒烟：goto https://example.com → plugin-linkcheck countLinks → LinkCountResult(total=1, external=1, internal=0)（分类正确）。

## 4. 结论

调参（toolLoop.maxIterations=40、textOnlyStallLimit=8、noop.limit=8）+ 小任务拆分下，b4 正确高效完成插件开发（编译/测试/双重校验/打包/部署/工具调用全部验证通过）。HEAD 存在 2 个 P0 代理回路缺陷与 DevTaskPlanner 模块绑定回归，详见 browser4-code-tool-supervision-v1.5.md。8183 后端仍运行中（带调参）；插件 JAR 留在 D:\workspace\Browser4\Browser4-4.14-feat\plugins\。
