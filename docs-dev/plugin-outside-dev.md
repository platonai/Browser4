# Browser4 插件「项目外开发 + Maven Central 发布」方案

> 状态：方案定稿（2026-08-25 评审）
> 适用范围：插件在独立 GitHub 仓库中开发、构建、测试，通过 Maven Central 公开发布，用户装入自己的 Browser4 实例。
> 原则：开发期**不克隆 Browser4 源码**，只依赖 Maven Central 上的官方 PDK（`ai.platon.pulsar:browser4-pdk`）。

---

## 1. 目标与总体思路

插件以**独立 GitHub 仓库**承载（与 Browser4 主仓库完全解耦），开发期不克隆 Browser4 源码，只依赖 Maven Central 上的官方 PDK；构建产物为 **thin JAR**，通过 Maven Central 公开发布，用户拿到 JAR 后装入自己的 Browser4 实例（`plugins/` 目录或 REST 接口）。

### 技术基础（已在仓库源码核实）

| 组件 | 说明 | 位置 |
|---|---|---|
| `browser4-pdk` | 独立 parent POM：直接继承 Maven Central 上的 `ai.platon:pulsar-parent`，不依赖 Browser4 聚合 POM；提供 Kotlin + JVM 17 编译配置、`browser4-pdk-bom` 版本管理、Central 发布 profile（GPG + dokka + sources） | `browser4-pdk/pom.xml`（当前版本 `4.14.0-rc.1`） |
| `browser4-plugin-archetype` | 官方 Maven 脚手架 | `browser4-pdk/browser4-plugin-archetype/` |
| 宿主加载 | `PluginClasspathEnhancer` 启动时扫描 `plugins/` 目录 JAR → Spring Boot 自动发现 `AutoConfiguration.imports` → `PluginManager` 注册 | `browser4-boot/.../plugin/PluginClasspathEnhancer.kt` |
| REST 安装 | `POST /api/plugins/install`（multipart，`replace` 参数）、`GET /api/plugins`、`DELETE /api/plugins/{name}`；重启生效 | `browser4-rest/.../api/controller/PluginController.kt` |
| 版本兼容契约（4.14 起） | 宿主按清单 `sdkVersion` 与自身版本比对：SDK 比宿主新 → **拒绝加载**；比宿主旧 → 警告后加载。插件 `sdkVersion` 必须与目标宿主同大版本 | `PluginClasspathEnhancer.selectJars()` / `PluginCompatibility` |

---

## 2. 仓库与工程结构

单插件一仓库：`github.com/<org>/browser4-<feature>`；若多个插件共享基础设施，可改一仓库多 module（父 POM 继承 `browser4-pdk`）。

```
browser4-<feature>/
├── pom.xml                        # parent: ai.platon.pulsar:browser4-pdk:<版本>
├── README.md                      # 功能说明 + 安装指南（兼容的 Browser4 版本）
├── .github/workflows/
│   ├── ci.yml                     # push / PR：构建 + 单测 + JAR 校验
│   └── release.yml                # 打 tag：发布 Central + GitHub Release 附件
└── src/main/
    ├── kotlin/<package>/
    │   ├── <Feature>Plugin.kt                 # 可选：Browser4Plugin 生命周期
    │   ├── config/<Feature>AutoConfiguration.kt  # @AutoConfiguration + PluginMount（必选）
    │   ├── config/<Feature>Config.kt          # 配置数据类（可选）
    │   ├── integration/<Feature>BrowseEventHandler.kt   # 可选
    │   ├── integration/<Feature>LoadEventHandler.kt     # 可选
    │   ├── service/<Feature>Service.kt        # 业务逻辑（可选）
    │   └── tools/<Feature>ToolExecutor.kt     # LLM 工具（可选）
    └── resources/META-INF/
        ├── browser4-plugin.json               # name/version/sdkVersion/dependsOn/autoConfigurationClasses（必选）
        └── spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports  # 必选
```

### 依赖纪律（决定成败）

- 所有 `browser4-*`、Spring Boot、Kotlin 依赖一律 `<scope>provided</scope>`（宿主提供）；**只有真正的第三方库用 `compile`**（会被打进 JAR）。
- 禁用 `spring-boot-maven-plugin` 的 `repackage`，保持 thin JAR。
- 自动配置类必须 `@Lazy`；事件处理器体必须 try-catch；事件循环内禁止阻塞 I/O（用协程）。
- `@ConditionalOnProperty(name=["<feature>.enabled"], havingValue="true", matchIfMissing=true)`，默认启用。
- 清单 JSON 与 `Browser4Plugin.manifest` 保持同步，JSON 为准。

---

## 3. 开发流程（本地）

### 3.1 准备

- JDK 17+、Maven 3.9+
- 确认目标 PDK 版本已在 Maven Central：

  ```bash
  mvn dependency:get -Dartifact=ai.platon.pulsar:browser4-pdk:<版本>
  ```

  `<版本>` 与目标宿主同大版本（如宿主 4.14.x → PDK 4.14.x）。

### 3.2 脚手架

```bash
mvn archetype:generate \
  -DarchetypeGroupId=ai.platon.pulsar \
  -DarchetypeArtifactId=browser4-plugin-archetype \
  -DarchetypeVersion=<版本> -Dbrowser4-version=<版本> \
  -DgroupId=<com.example> -DartifactId=browser4-<feature> \
  -Dpackage=<...> -DpluginName="..." -DpluginDescription="..."
```

`-Dbrowser4-version` 写入生成的 `sdkVersion` 和 JAR 属性 `Browser4-Plugin-Version`，**必须与宿主一致**。

生成后立即：

1. 重命名类：`PluginAutoConfiguration` → `<Feature>AutoConfiguration`、`MyPlugin` → `<Feature>Plugin`、handler 同步重命名；
2. 更新 `browser4-plugin.json`（name/description/autoConfigurationClasses）与 `AutoConfiguration.imports` 的 FQN；
3. 删除不会用到的模板文件。

### 3.3 落地功能

按需求澄清选择 mount 点：

| 能力 | Mount 点 | 主钩子 |
|---|---|---|
| 页面加载后自动执行 RPA | `BrowseEventMount` | `onDocumentSteady`（最常用） |
| 导航前拦截资源/设请求头 | `BrowseEventMount` | `onWillNavigate` |
| 关闭前截图/取数 | `BrowseEventMount` | `onWillStopTab` |
| URL 规范化/加载中转换 | `LoadEventMount` | `onNormalize` / `onHTMLDocumentParsed` |
| 抓取管道 URL 过滤 | `CrawlEventMount` | `onWillLoad`（返回 null 拒绝） |
| 暴露 LLM 工具 | `ToolMount` | `getToolExecutors()` |
| 页面类别识别 | `PageSnifferMount` | `getPageSniffers()` |
| 启动/关闭生命周期 | `Browser4Plugin` | `onStartup()` / `onShutdown()` |

先 `mvn package` 验证骨架，再逐个 mount 点增量实现、增量测试。

### 3.4 测试

- JUnit5 + kotlin-test-junit5 + spring-boot-test（test 作用域）
- Service 用 `java.lang.reflect.Proxy` 轻量 mock（`WebPage`/`WebDriver`）
- handler 用 `runBlocking` 测协程逻辑
- Config 测默认值与 `fromConfig()` 属性读取

### 3.5 宿主验证（发布前必做）

```powershell
# 1. 校验 JAR 结构（thin JAR、清单、AutoConfiguration.imports）
bin/verify-plugin.ps1 target/browser4-<feature>-<版本>.jar

# 2. 装入真实 Browser4（默认端口 8182；以实际配置为准）
curl.exe -X POST http://localhost:8182/api/plugins/install `
  -F "file=@target/browser4-<feature>-<版本>.jar" -F "replace=true"

# 3. 重启后日志应出现：
#    PluginManager: Found X PluginMount bean(s)
#    PluginManager:   ✓ Configured browse event handlers
#    PluginManager: Found X Browser4Plugin bean(s)
#      - browser4-<feature> v<版本>

# 4. 确认注册
curl.exe http://localhost:8182/api/plugins
```

- 用 `browser4-cli` 跑真实页面冒烟，验证钩子行为与工具可用性。
- 注意：opt-in 插件（清单 `defaultEnabled: false`）需 `browser4.plugins.enable=<name>` 或 `browser4.plugins.enable-all` 才加载。

---

## 4. CI（GitHub Actions）

### 4.1 ci.yml（push / PR）

- JDK 17 + Maven：`mvn verify`（含单测）
- `verify-plugin` 脚本校验产物 JAR 结构

### 4.2 release.yml（打 tag `v<版本>` 触发）

1. `mvn deploy -Pdeploy` 发布 Maven Central（PDK deploy profile 自动生成 sources、dokka javadoc、GPG 签名）
2. 把 thin JAR 附到 GitHub Release（方便不熟悉 Maven 的用户直接下载）
3. 生成 changelog，注明兼容的 Browser4 版本

### 4.3 一次性发布准备

- Sonatype Central Portal 账号（用自有 groupId，如 `com.example` 或公司域名）
- GPG 密钥对（`--pinentry-mode loopback`）
- GitHub Actions secrets：`OSSRH_USERNAME`、`OSSRH_TOKEN`、`GPG_KEY`、`GPG_PASSPHRASE`
- `~/.m2/settings.xml` 模板 + secrets 注入

---

## 5. 版本策略与兼容性维护

- 插件自身用 SemVer；`sdkVersion`（= `browser4-version`）跟随宿主大版本。
- 宿主大版本升级（如 4.14 → 4.15）时：用新 PDK 重建并发布新版本插件；README 明示「支持 Browser4 4.x」。
- 使用 `ToolMount` / `PageSnifferMount` 时，清单 `dependsOn` 需含 `browser4-protocol`、`browser4-agentic`。
- 兼容性检查特征与排障：

| 症状 | 原因 | 对策 |
|---|---|---|
| `Skipping incompatible plugin ... blocked` | SDK 比宿主新 | 用与宿主匹配的 PDK 重编 |
| `ClassNotFoundException`（Browser4 API 类） | 依赖误用 `compile` 作用域 | 改回 `provided` |
| `BeanCreationException` | 缺 `@Lazy` / bean 循环依赖 | 加 `@Lazy`、检查构造参数 |
| `NoClassDefFoundError`（第三方库） | 第三方依赖未打进 JAR | 该依赖改 `compile` |
| 插件未加载（无注册日志） | 清单缺失/损坏、JAR 不在 plugins 目录 | 校验清单 JSON、`verify-plugin.ps1` |

---

## 6. 里程碑

| 阶段 | 内容 | 出口标准 |
|---|---|---|
| M0 需求澄清 | 功能、mount 点、外部依赖、配置项 | 功能清单 + mount 点选型 |
| M1 仓库初始化 | GitHub 仓库、README、license、archetype 脚手架、重命名、删废码 | `mvn package` 通过 |
| M2 实现与单测 | Config/Service/Handler/Tool 实现 + 单测 | `mvn verify` 绿 |
| M3 宿主验证 | verify-plugin + 装入真实 Browser4 + CLI 冒烟 | 注册日志出现、功能实测通过 |
| M4 发布 | CI 就绪、Central 账号/GPG 就绪、首个 tag | Central 可 `dependency:get`、Release 附件可下载 |
| M5 维护 | 安装指南、兼容矩阵、版本升级流程 | README 完整 |

---

## 7. 风险与对策

| 风险 | 对策 |
|---|---|
| 目标 PDK 版本尚未发布到 Central（如 rc） | 发布前置 `dependency:get` 校验；紧急时本地 `mvn -pl browser4-pdk install` 临时构建（仅内部验证，不进 CI） |
| 宿主升级导致插件被拒载 | 版本策略（第 5 节）：跟随宿主重建 + 兼容矩阵文档 |
| thin JAR 纪律被破坏（fat JAR / 作用域错） | CI 强制 `verify-plugin.ps1` + 代码评审清单 |
| Windows 下插件 JAR 被锁无法重装 | 宿主已有 classloader close 机制；冲突返回 409，用 `replace=true` 重试 |
| 端口混淆（8182 vs 部分文档的 8080） | 宿主验证命令显式用 8182，或以实际配置为准 |

---

## 8. 参考

- [Plugin Development Guide](../docs-dev/plugin-development.md) — 官方插件开发指南（外部开发流程、API 参考）
- [Plugin 开发技能](C:\Users\pereg\.dsh\skills\browser4-plugin) — 脚手架、mount 点、测试、部署全流程
- [PluginClasspathEnhancer.kt](../browser4-boot/src/main/kotlin/ai/platon/pulsar/boot/plugin/PluginClasspathEnhancer.kt) — 宿主加载与兼容性检查
- [PluginController.kt](../browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/controller/PluginController.kt) — 插件 REST API
- [browser4-pdk/pom.xml](../browser4-pdk/pom.xml) — PDK parent POM（Central 发布 profile）
- [browser4-plugin-archetype](../browser4-pdk/browser4-plugin-archetype/src/main/resources/archetype-resources/) — 脚手架模板
