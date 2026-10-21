# 插件开发任务：browser4-pagetitle（由 b4 代理执行）

在 browser4-plugins 下开发一个新的 Browser4 插件 browser4-pagetitle。

**范围约束**：这是独立第三方风格的插件，只允许在 browser4-plugins/browser4-pagetitle 目录内创建/修改文件；
例外 1：scaffold 工具会自动把新模块注册进 browser4-plugins/pom.xml（属于正常行为，允许）；
例外 2：若 browser4-plugins/pom.xml 发生了模块注册，则必须同步在 browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/ModuleMap.kt
的 MODULES 列表中加入 "browser4-plugins/browser4-pagetitle"（ModuleMapDriftE2ETest 会校验 pom 与快照一致）。
不要修改任何其他文件（包括根 pom.xml、VERSION、browser4-rest 等）。

## 步骤

1. 用 code scaffold plugin 生成骨架：
   `code scaffold plugin --dir browser4-plugins/browser4-pagetitle --name browser4-pagetitle --domain pagetitle --package ai.platon.pulsar.pagetitle --method getPageInfo --desc "Get the current page title, URL and meta description"`
2. 重命名类：PageTitleConfig、PageTitleAutoConfiguration、PageTitleService、PageTitleToolExecutor，
   同步更新 browser4-plugin.json 的 autoConfigurationClasses 与 AutoConfiguration.imports（删除无用桩文件）。
3. 实现 BrowseEventMount：onDocumentSteady 钩子中用 logger.info 记录页面标题与 URL；handler 体内必须 try-catch 包裹。
4. 实现 ToolMount：暴露 pagetitle.getPageInfo 工具，通过 WebDriver.evaluateValue 执行 JS 返回 title、url、description 三个字段，
   description 缺失时返回空字符串；JS 资源放 src/main/resources/pagetitle/getPageInfo.js，由 Service 加载。
5. Config：pagetitle.enabled 默认 true、pagetitle.maxLength 默认 200；
   @ConditionalOnProperty(name=["pagetitle.enabled"], havingValue="true", matchIfMissing=true)，auto-configuration 类加 @Lazy。
6. Service：PageTitleService 提供 getPageInfo(driver) 与纯函数 summarize(info, maxLength)（空串处理与按 maxLength 截断）。
7. 单元测试（src/test/kotlin）：PageTitleConfigTest（默认值与 fromConfig 读取）、PageTitleServiceTest（summarize 空串/短文本/超长截断）；
   JUnit5 + kotlin-test-junit5，测试方法 camelCase + @DisplayName。
8. README.md：插件功能、构建命令（mvn package -f pom.xml）、JAR 结构校验与部署方式。

## 质量门槛

- `mvn -f browser4-plugins/browser4-pagetitle/pom.xml package` 必须成功（编译 + 测试全过）
- `code validate plugin --path browser4-plugins/browser4-pagetitle` 不得有 ERROR 级别问题
- `code validate repo-consistency` 不得新增 ERROR
- 不要执行 git add / git commit / git push
