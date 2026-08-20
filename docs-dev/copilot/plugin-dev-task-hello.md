# 插件开发任务：browser4-hello（由 b4 代理执行）

本任务用于回归验证 `./b4w.ps1 code` 工具族（v1.2 修复后的新构建后端）：
`code devtask` 计划质量、`code scaffold` 插件骨架、ModuleMap 自动同步、
`code mvn` 编译、最小范围测试、`code validate` 语义校验与 repo 治理校验。

在 browser4-plugins 下开发一个新的 Browser4 插件 browser4-hello。

**范围约束**：这是独立第三方风格的插件，只允许在 browser4-plugins/browser4-hello 目录内创建/修改文件；
例外 1：scaffold 工具会自动把新模块注册进 browser4-plugins/pom.xml（属于正常行为，允许）；
例外 2：scaffold 工具会自动同步 browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/ModuleMap.kt
的 MODULES 列表（属于正常行为，允许；若未自动同步，则必须手动补上，ModuleMapDriftE2ETest 会校验 pom 与快照一致）。
不要修改任何其他文件（包括根 pom.xml、VERSION、browser4-rest 等）。

## 步骤

1. 用 code scaffold plugin 生成骨架：
   `code scaffold plugin --dir browser4-plugins/browser4-hello --name browser4-hello --domain hello --package ai.platon.pulsar.hello --method pageInfo --desc "Get the current page title and URL"`
2. 重命名类：HelloConfig、HelloAutoConfiguration、HelloService、HelloToolExecutor，
   同步更新 browser4-plugin.json 的 autoConfigurationClasses 与 AutoConfiguration.imports（删除无用桩文件）。
3. 实现 BrowseEventMount：onDocumentSteady 钩子中用 logger.info 记录页面标题与 URL（HelloAutoConfiguration 同时实现 BrowseEventMount 与 ToolMount）；
   handler 体内必须 try-catch 包裹，不做阻塞 I/O。
4. 实现 ToolMount：暴露 hello.pageInfo 工具，通过 WebDriver.evaluateValue 执行 JS 返回 title、url 两个字段，
   title 缺失时返回空字符串；JS 资源放 src/main/resources/hello/pageInfo.js，由 Service 加载。
5. Config：hello.enabled 默认 true、hello.logLevel 默认 "info"；
   @ConditionalOnProperty(name=["hello.enabled"], havingValue="true", matchIfMissing=true)，auto-configuration 类加 @Lazy。
6. Service：HelloService 提供 pageInfo(driver) 与纯函数 greet(name)（空名返回 "Hello, Browser4!"）。
7. 单元测试（src/test/kotlin）：HelloConfigTest（默认值与 fromConfig 读取）、HelloServiceTest（greet 空名/普通名、pageInfo 空 title 处理）；
   JUnit5 + kotlin-test-junit5，测试方法 camelCase + @DisplayName。
8. README.md：插件功能、构建命令（mvn package -f pom.xml）、JAR 结构校验与部署方式。

## 质量门槛

- `mvn -f browser4-plugins/browser4-hello/pom.xml package` 必须成功（编译 + 测试全过）
- `code validate plugin --path browser4-plugins/browser4-hello` 不得有 ERROR 级别问题
- `code validate repo-consistency` 不得新增 ERROR
- 不要执行 git add / git commit / git push

## 监督方备注（本任务针对 planner 的检查点）

- `code devtask` 计划必须包含 scaffold 步骤（任务文本含新模块路径 browser4-plugins/browser4-hello）
- 计划中的编译/测试目标必须绑定 browser4-plugins/browser4-hello，而不是聚合器 browser4-plugins
- 计划必须把 HelloServiceTest、HelloConfigTest 的 -Dtest 绑定到 browser4-plugins/browser4-hello
