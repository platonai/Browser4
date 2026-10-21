# plugin 工件对照

## 真实实现：browser4-seo（成熟参考插件）

`browser4-plugins/browser4-seo/` —— 脚手架形态的成熟超集（ToolExecutor 注入 Service + WebDriver receiver + service.x(driver) 完全同构；双工具 + 真 JS 脚本 + config 注入更丰富）。

关键文件：
- `pom.xml` — parent = `browser4-pdk`（`<relativePath>../../browser4-pdk/pom.xml</relativePath>`），artifactId `browser4-seo`
- `src/main/kotlin/ai/platon/pulsar/seo/tools/SeoToolExecutor.kt` — `open class SeoToolExecutor : AbstractToolExecutor()`，`override val domain = "seo"`，`toolSpec["extractMeta"]`
- `src/main/kotlin/ai/platon/pulsar/seo/config/SeoAutoConfiguration.kt` — `@AutoConfiguration` + `ToolMount`（`getToolExecutors()` 注册工具）
- `src/main/resources/META-INF/browser4-plugin.json` — PluginManifest（name/description/dependsOn/autoConfigurationClasses）

## 脚手架输出 A：scaffoldFlow（agent-tool）

```text
coding.scaffoldFlow(type="agent-tool", name="browser4-weather", domain="weather",
     basePackage="ai.platon.pulsar.weather", toolMethod="fetchWeather", description="...")
  → 2 文件：WeatherToolExecutor.kt + WeatherAutoConfiguration.kt
```

## 脚手架输出 B：活模板克隆（generated/plugin-weather/）

由 `coding.scaffoldFromExample(path="browser4-plugins/browser4-seo", className="WeatherToolExecutor",
basePackage="ai.platon.pulsar.weather", domain="weather", artifactId="browser4-weather")` 生成，
发现参数：`basePackage=ai.platon.pulsar.seo, domain=seo, toolMethod=extractMeta,
artifactId=browser4-seo, className=SeoToolExecutor, stem=Seo`。

词干派生：一个 `className` 改名同时派生全部兄弟类 —— `SeoToolExecutor→WeatherToolExecutor`、
`SeoAutoConfiguration→WeatherAutoConfiguration`、`SeoService→WeatherService`、`SeoConfig→WeatherConfig`。

对比 `generated/plugin-weather/src/main/kotlin/.../tools/SeoToolExecutor.kt` 与真实
`SeoToolExecutor.kt`：包名、类名、domain、引用全部一致改名，结构逐字保留。

### 已知边界（诚实说明）
- **文件路径不参与参数化**：`generated/plugin-weather/` 的目录仍叫 `seo/`（内容已改，路径未改）——
  落地时需把 `seo/` 目录改名为 `weather/`（或用 scaffoldFlow agent-tool 生成带路径的新插件）。
- 若参考实现引入新约定（如新注解），重新生成即可同步，无需维护手写模板。
