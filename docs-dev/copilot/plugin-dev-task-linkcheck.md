# 插件开发任务：browser4-linkcheck（由 b4 代理执行）

本任务用于回归验证 `./b4w.ps1 code` 工具族（HEAD b7ed815d85，反馈闭环 + 任务隔离版本）：
`code devtask` 计划质量、`code scaffold` 插件骨架、ModuleMap 自动同步（MODULES + DEPENDENTS）、
`code mvn` 编译、最小范围测试、`code validate` 语义校验与 repo 治理校验，
以及 b4 代理在监督下完成一次完整插件开发任务（scaffold → 实现 → 编译 → 测试 → 校验 → 打包）。

在 browser4-plugins 下开发一个新的 Browser4 插件 **browser4-linkcheck**。

**范围约束**：这是独立第三方风格的插件，只允许在 browser4-plugins/browser4-linkcheck 目录内创建/修改文件；
例外 1：scaffold 工具会自动把新模块注册进 browser4-plugins/pom.xml（属于正常行为，允许）；
例外 2：scaffold 工具会自动同步 browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/ModuleMap.kt
的 MODULES 列表（属于正常行为，允许；若未自动同步，则必须手动补上）。
例外 3：ModuleMap.kt 的 DEPENDENTS 反向边（browser4-agentic、browser4-core/browser4-protocol、
browser4-core/browser4-skeleton、browser4-pdk 四键）scaffold 不会自动补，必须手动补齐，
否则 `code validate repo-consistency` 会报 4 处漂移（参考 browser4-wordcount 的 4 处插入方式）。
不要修改任何其他文件（包括根 pom.xml、VERSION、browser4-rest 等）。

## 步骤

1. 用 code scaffold plugin 生成骨架（必须用 `--dir` 落地到磁盘，而不是只打印模板）：
   `code scaffold plugin --dir browser4-plugins/browser4-linkcheck --name browser4-linkcheck --domain linkcheck --package ai.platon.pulsar.linkcheck --method countLinks --desc "Count total, external and internal links on the current page"`
2. 实现 JS 资源 `src/main/resources/linkcheck/countLinks.js`：
   在页面上下文统计链接，返回 JSON 字符串 `{"total":N,"external":N,"internal":N}`：
   - total：所有 `a[href]` 的数量；
   - external：绝对 http/https 链接且 origin 与 `location.origin` 不同（跳过 `#`、`mailto:`、`tel:` 等非 http(s) 协议）；
   - internal：其余（相对链接、锚点、同 origin 绝对链接）。
3. Config：`LinkcheckConfig(enabled: Boolean = true, logLevel: String = "info")`，
   `fromConfig` 用 `conf.getBoolean("linkcheck.enabled", true)` 与
   `conf.get("linkcheck.logLevel", "info")`（注意：ImmutableConfig 没有 getString，用 `get(key, default)`）。
4. Service：`LinkcheckService` 提供：
   - `suspend fun countLinks(driver: WebDriver): LinkCountResult` —— 加载 countLinks.js 经
     `driver.evaluateValue(script)` 执行，把 JSON 字符串解析为 `LinkCountResult`；
     异常时打 WARN 并返回全 0；
   - `fun parseCounts(json: String): LinkCountResult` —— 纯函数，缺失字段或非法 JSON 视为 0；
   - `fun summarize(result: LinkCountResult): String` —— 纯函数，输出
     `"total=10 external=3 internal=7"` 格式。
   `data class LinkCountResult(total: Int, external: Int, internal: Int)`。
5. ToolExecutor：`LinkcheckToolExecutor(service)` 继承
   `ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor`，`domain = "linkcheck"`，
   `receiverClass = WebDriver::class`，暴露 `countLinks` 工具（ToolSpec 无参数，
   returnType = "LinkCountResult"，description 与任务一致）。
6. AutoConfiguration：`LinkcheckAutoConfiguration` 实现
   `ai.platon.pulsar.agentic.tools.ToolMount`，暴露 `linkcheck.countLinks`；
   bean 名 linkcheckConfig / linkcheckService / linkcheckToolExecutor；
   `@ConditionalOnProperty(name=["linkcheck.enabled"], havingValue="true", matchIfMissing=true)`，加 `@Lazy`。
7. 同步 browser4-plugin.json 的 autoConfigurationClasses 与
   AutoConfiguration.imports（若 scaffold 已生成正确内容，保持不变即可，删除无用桩文件）。
8. 单元测试（src/test/kotlin，JUnit5 + kotlin-test-junit5，测试方法 camelCase + @DisplayName）：
   - LinkcheckConfigTest：默认值（enabled=true、logLevel="info"）+ fromConfig 读取自定义值；
   - LinkcheckServiceTest：parseCounts 正常 JSON / 缺字段 / 空串；summarize 输出格式。
9. README.md：插件功能、构建命令（`mvn -f pom.xml package`）、JAR 结构校验与部署方式。

## 质量门槛

- `mvn -f browser4-plugins/browser4-linkcheck/pom.xml package` 必须成功（编译 + 测试全过）
- `code validate plugin --path browser4-plugins/browser4-linkcheck` 不得有 ERROR 级别问题
- `code validate repo-consistency` 不得新增 ERROR
- 不要执行 git add / git commit / git push

## 监督方备注（本任务针对 planner 的检查点）

- `code devtask` 计划必须包含 scaffold 步骤（任务文本含新模块路径 browser4-plugins/browser4-linkcheck）
- 计划中的编译/测试目标必须绑定 browser4-plugins/browser4-linkcheck，而不是聚合器 browser4-plugins
- 计划必须把 LinkcheckConfigTest、LinkcheckServiceTest 的 -Dtest 绑定到 browser4-plugins/browser4-linkcheck
- 计划不得包含 git commit 步骤
