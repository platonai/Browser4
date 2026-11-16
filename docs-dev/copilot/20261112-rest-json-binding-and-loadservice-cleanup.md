# REST JSON 绑定修复 + `LoadService` 死重载清理（2026-11-12）

> 来源：`20261112-act-removal.md` §4.1 里"与 act 下线无关的两条既有缺陷"
> 范围：全部改动留在工作区，未 commit

## 0. 结论

1. **所有"带必填构造参数"的 Kotlin `@RequestBody` DTO 在此前都会 500**。根因不是 `PromptRequest` 本身，而是 HTTP 层实际运行在 **Jackson 3**（`tools.jackson`）上，而 Jackson 3 的 Kotlin 模块根本不在依赖里；`browser4-rest`/`browser4-bundle` 里那三条 `spring-boot-starter-jackson` 排除是**无效的**（`spring-boot-starter-websocket` 又把它带回来了），注释里"Jackson 3.x kotlin 模块还不可用"也已不成立。
   修复：补 `tools.jackson.module:jackson-module-kotlin`（版本由 Spring Boot BOM 管理），删掉无效排除，注释改成事实。
2. **`LoadService.loadDocument(CommandRequest, PageEventHandlers)` 是死代码**。`CommandRequest` 只是 `PageVisitRequest` 的 typealias，同一份"加载页面 + 执行 `onPageReadyActions`"逻辑在 agent-tools 的 `ScrapeService.loadDocument(...)` 里由 `StatefulPageVisitor` 调用；REST 这份副本没有任何调用方。删除该重载与随之无用的 import。

## 1. 根因链（问题 1）

```
spring-boot 4.0.6（HTTP 默认 Jackson 3）
  browser4-rest → spring-boot-starter-websocket
                        └→ spring-boot-starter-webmvc
                              └→ spring-boot-starter-jackson
                                    └→ spring-boot-jackson
                                          └→ tools.jackson.core:jackson-databind:3.1.2   ← 实际在 classpath
```

- `browser4-rest/pom.xml` 只在 `spring-boot-starter-web` / `spring-boot-starter-webflux` 上排除了 `spring-boot-starter-jackson`，websocket 这条路径没排除 → Jackson 3 依然进包（打包产物 `lib/` 里同时有 `jackson-databind-2.21.2` 与 `jackson-databind-3.1.2`，`spring-boot-starter-jackson-4.0.6` 也在）。
- Spring Boot 4 的 `spring.jackson.find-and-add-modules` 默认 `true`，`AbstractMapperBuilderCustomizer` 会调用 `MapperBuilder.findAndAddModules()` 走 ServiceLoader 注册模块。
- classpath 上只有 **Jackson 2** 的 `jackson-module-kotlin`；Jackson 3 的 `JsonMapper` 没有 Kotlin 支持 → 必填构造参数的 Kotlin DTO 反序列化报
  `Cannot construct instance of … (no Creators, like default constructor, exist)`。
- **为什么一直没被发现**：`MCPToolCallRequest` 这类 DTO 的所有参数都有默认值（Kotlin 会生成无参构造），Jackson 不靠 Kotlin 模块也能构造；只有 `PromptRequest.url`、`PageVisitRequest.url` 这种**必填**参数才炸。而 CLI 全走 MCP（`/mcp/call-tool`），REST 的 JSON DTO 入口没有 HTTP 层测试（现有 controller 测试都是直接 `new Controller(mock)` 调方法，绕过了消息转换）。

## 2. 改动

| 文件 | 改动 |
|---|---|
| `browser4-rest/pom.xml` | 删 2 处无效的 `spring-boot-starter-jackson` 排除；新增 `tools.jackson.module:jackson-module-kotlin`（**无版本**，由 Boot BOM 的 `tools.jackson:jackson-bom` 管理）；用一段注释写清"两套 Jackson 并存、各需各自 Kotlin 模块、不要再靠排除 starter 想回到 Jackson 2" |
| `browser4-apps/browser4-bundle/pom.xml` | 删无效排除 + 注释说明 Jackson 3 的来源与 Kotlin 模块 |
| `browser4-apps/browser4-standalone/pom.xml` | 同上 |
| `browser4-rest/.../api/service/LoadService.kt` | 删 `loadDocument(CommandRequest, PageEventHandlers)`（含被注释的 `onDidInteract` 残块）；删 `CommandRequest` / `PageEventHandlers` import；类 KDoc 指向 `StatefulPageVisitor` → `ScrapeService.loadDocument` 这一真实入口 |
| `browser4-rest/src/test/.../api/entities/KotlinDtoJsonBindingTest.kt` | **新增** 3 个用例：Boot 自动配置的 `JsonMapper` 注册了 Jackson 3 Kotlin 模块；`PromptRequest`（必填 url）能绑定；`PageVisitRequest`（含页面动作）能绑定 |
| `browser4-rest/.../agent/tool/CommandToolExecutor.kt` | `command.run` 的 `tokenBudget` 描述与 CLI / `docs/mcp-tools.md` 的 cache-miss 口径对齐（见下方"顺带修掉的既有红测"） |
| `docs/mcp-tools.json` | 用 `-DregenerateToolDocs=true` 重新生成，只变 `tokenBudget` 那一行 |

### 为什么不是"把 Jackson 3 排干净，回到 Jackson 2"

Spring Boot 4 只提供 Jackson 3 的自动配置（本地仓库里没有 `spring-boot-jackson2` 模块）。把 Jackson 3 排除掉会让 MVC 失去默认 JSON converter，风险远大于收益。当前是两套并存：

- **HTTP 层**：Jackson 3（Boot 4 默认）+ `tools.jackson.module:jackson-module-kotlin`
- **应用内序列化**：Jackson 2（`pulsarObjectMapper()`）+ `com.fasterxml.jackson.module:jackson-module-kotlin`

两个 Kotlin 模块都不能少。

### 顺带修掉的既有红测：`ToolDocGeneratorTest`

跑 `mvn -o -pl browser4-rest test` 时暴露了第三个既有问题（与上面两项无关，但在同一个模块里挡着绿灯）：

- `docs/mcp-tools.md`（提交于 `a53b1c15f9` "report prompt-cache tokens and budget cache misses"）里 `command.run` 的 `tokenBudget` 描述是**新版**："**Cache-miss** token budget … prompt-cache reads do not count…"；
- `CommandToolExecutor.kt` 的 `ToolSpec.Arg` 与 `docs/mcp-tools.json` 里仍是**旧版**："Token budget for THIS task … 0 means unlimited…"；
- 于是 `ToolDocGeneratorTest.committedReferenceMatchesRegistry` 在 HEAD 上就是红的（先报 `.md` stale，修好后又报 `.json` stale）。

判断依据：CLI 的 `--token-budget` 选项（`commands.rs:4258`）与 `ContextToAction.kt:401` 的 cache-miss 记账都已经是"只计未命中的 prompt + output"，所以**旧文案才是过期的那一份**。处置：把 `CommandToolExecutor.kt` 的 Arg 描述改成与 `.md`/CLI 一致，再 `-DregenerateToolDocs=true` 只刷新 `docs/mcp-tools.json` 那一行（`.md` 未变，正好证明源文案现在与它一致）。

结果：`browser4-rest` 全模块 **893 tests / 0 failures**（此前 1 failure）。

## 3. 验证

| 检查 | 结果 |
|---|---|
| `mvn -o -pl browser4-rest -am test -Dtest=KotlinDtoJsonBindingTest` | **3 passed / 0 failed** |
| `mvn -o -pl browser4-rest test -Dtest=KotlinDtoJsonBindingTest,CommandControllerTest` | **19 passed / 0 failed** |
| `mvn -o -pl browser4-rest test`（全模块） | **893 passed / 0 failed**（修复前 1 failure：`ToolDocGeneratorTest`） |
| 重建 bundle（`BROWSER4_CLI_FORCE_REBUILD_BUNDLE=1`） | BUILD OK，`lib/` 里出现 `jackson-module-kotlin-3.1.2.jar`（与 `-2.21.2` 并存），其余 jar 集合不变 |
| `POST /api/conversations/about` `{"url":"https://example.com","prompt":"say hi","args":"-refresh"}` | **200**（修复前：**500**，0.1s 即失败） |
| 同上 + `"actions":["scroll to the bottom of the page"]` | **200**，35.1s —— 回调里跑完一次 agent tool loop |
| `POST /api/commands/json` `{"url":"https://example.com","onPageReadyActions":["click the \"Learn more\" link"],"async":false}` | **200**，273s；agent 日志 `resolveDone success=true durationMs=269734`，`history.jsonl` summary = "Clicked the \"Learn more\" link on http…" |
| Rust e2e `--group open --group scrape --group status --group list`（`--max-failures 0`） | 选中 27、`BASIC` 排除 6，**21 passed / 0 failed**，78.8s |
| 回归：`POST /api/act` / `browser4-cli act` | **404** / **exit 2 + 迁移提示**（act 下线未受影响） |

## 4. 观察（本轮未改）

1. **回调里的动作现在是一次完整 tool loop**：`POST /api/commands/json` 带 `onPageReadyActions` 的这一次实测耗时 **273s**（`maxSteps=100`，`gpt`-类模型往返多）。旧的 `act()` 只要一次 LLM 往返。若这个字段被用于批量抓取，应改成"一次 run 处理整个动作列表"或给它加超时/并发上限——否则一个慢模型就能把 crawl 卡住。
2. `ToolCallResult.isSuccess` 仍然取反（`Models.kt`，无生产调用方），属于遗留清理项。
3. `LoadService` 的 `url.contains(":8182/")` 内部地址拦截仍只覆盖历史端口；当前默认端口是 18182，`:8282` 工作区端口没有覆盖（本轮未动，属既有逻辑）。

## 5. 发布说明

- 修复：REST 的 JSON 请求体（`PromptRequest`、`PageVisitRequest`/`CommandRequest` 等带必填字段的 Kotlin DTO）此前一律 500，现已正常绑定。
- 移除：`LoadService.loadDocument(CommandRequest, PageEventHandlers)`（无调用方）；页面动作请走 `POST /api/commands/json`（`StatefulPageVisitor` → `ScrapeService.loadDocument`）。
