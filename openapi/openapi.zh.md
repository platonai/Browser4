# Browser4 WebDriver-Compatible API（openapi.yaml 解读与实现映射）

> 目标：让 `openapi/openapi.md` 成为 **spec 与代码之间的可维护索引**：读得懂、找得到、可持续更新。
>
> 权威来源：
> - **Spec**：`openapi/openapi.yaml`
> - **实现**：`pulsar-rest/src/main/kotlin/ai/platon/pulsar/rest/openapi/controller/*`
>
> 适用范围：本文聚焦 WebDriver-Compatible API（以 `/session...` 为根路径）。仓库内其它 REST 面（如 `/api/*`）不在本文详细展开（见附录）。

---

## 1. OpenAPI 概览（从 `openapi.yaml` 提取）

- OpenAPI：`3.1.0`
- Title：**Browser4 WebDriver-Compatible API**
- Server（默认开发地址）：`http://localhost:8182`
- 风格：大量响应采用 WebDriver 兼容包装：
  - 成功：`{"value": ...}` 或 `{"value": null}`
  - 失败：`ErrorResponse.value.error` / `ErrorResponse.value.message`

### 1.1 Tags（能力分组）
`openapi.yaml` 将接口按能力分为 9 组（tags）：

- `session`：会话生命周期（create/get/delete）
- `navigation`：导航与 URL 信息（url/documentUri/baseUri）
- `selectors`：扩展：selector-first 交互（exists/waitFor/click/fill/press/outerHtml/screenshot/element(s)）
- `element`：WebDriver 标准 element-by-id（find element(s)/click/sendKeys/attribute/text）
- `script`：执行 JavaScript（sync/async）
- `control`：delay/pause/stop
- `events`：事件配置、订阅、查询
- `agent`：AI agent（run/observe/act/extract/summarize/clearHistory）
- `pulsar`：PulsarSession 能力（normalize/open/load/submit）

---

## 2. 端点总览（按 tag）

> 提示：这里给出“骨架索引”，细节（请求/响应 schema、状态码）以 `openapi.yaml` 为准。

### 2.1 session
| Method | Path | operationId |
|---|---|---|
| POST | `/session` | `createSession` |
| GET | `/session/{sessionId}` | `getSession` |
| DELETE | `/session/{sessionId}` | `deleteSession` |

### 2.2 navigation
| Method | Path | operationId |
|---|---|---|
| POST | `/session/{sessionId}/url` | `navigateTo` |
| GET | `/session/{sessionId}/url` | `getCurrentUrl` |
| GET | `/session/{sessionId}/documentUri` | `getDocumentUri` |
| GET | `/session/{sessionId}/baseUri` | `getBaseUri` |

### 2.3 selectors（selector-first 扩展）
| Method | Path | operationId |
|---|---|---|
| POST | `/session/{sessionId}/selectors/exists` | `selectorExists` |
| POST | `/session/{sessionId}/selectors/waitFor` | `waitForSelector` |
| POST | `/session/{sessionId}/selectors/element` | `findElementBySelector` |
| POST | `/session/{sessionId}/selectors/elements` | `findElementsBySelector` |
| POST | `/session/{sessionId}/selectors/click` | `clickBySelector` |
| POST | `/session/{sessionId}/selectors/fill` | `fillBySelector` |
| POST | `/session/{sessionId}/selectors/press` | `pressBySelector` |
| POST | `/session/{sessionId}/selectors/outerHtml` | `getOuterHtmlBySelector` |
| POST | `/session/{sessionId}/selectors/screenshot` | `screenshotBySelector` |

### 2.4 element（标准 WebDriver element）
| Method | Path | operationId |
|---|---|---|
| POST | `/session/{sessionId}/element` | `findElement` |
| POST | `/session/{sessionId}/elements` | `findElements` |
| POST | `/session/{sessionId}/element/{elementId}/click` | `clickElement` |
| POST | `/session/{sessionId}/element/{elementId}/value` | `sendKeysToElement` |
| GET | `/session/{sessionId}/element/{elementId}/attribute/{name}` | `getElementAttribute` |
| GET | `/session/{sessionId}/element/{elementId}/text` | `getElementText` |

### 2.5 script
| Method | Path | operationId |
|---|---|---|
| POST | `/session/{sessionId}/execute/sync` | `executeSync` |
| POST | `/session/{sessionId}/execute/async` | `executeAsync` |

### 2.6 control
| Method | Path | operationId |
|---|---|---|
| POST | `/session/{sessionId}/control/delay` | `delay` |
| POST | `/session/{sessionId}/control/pause` | `pause` |
| POST | `/session/{sessionId}/control/stop` | `stop` |

### 2.7 events
| Method | Path | operationId |
|---|---|---|
| POST | `/session/{sessionId}/event-configs` | `createEventConfig` |
| GET | `/session/{sessionId}/event-configs` | `getEventConfigs` |
| GET | `/session/{sessionId}/events` | `getEvents` |
| POST | `/session/{sessionId}/events/subscribe` | `subscribeToEvents` |

### 2.8 agent
| Method | Path | operationId |
|---|---|---|
| POST | `/session/{sessionId}/agent/run` | `run` |
| POST | `/session/{sessionId}/agent/observe` | `observe` |
| POST | `/session/{sessionId}/agent/act` | `act` |
| POST | `/session/{sessionId}/agent/extract` | `extract` |
| POST | `/session/{sessionId}/agent/summarize` | `summarize` |
| POST | `/session/{sessionId}/agent/clearHistory` | `clearHistory` |

### 2.9 pulsar
| Method | Path | operationId |
|---|---|---|
| POST | `/session/{sessionId}/normalize` | `normalize` |
| POST | `/session/{sessionId}/open` | `open` |
| POST | `/session/{sessionId}/load` | `load` |
| POST | `/session/{sessionId}/submit` | `submit` |

---

## 3. Spec → Controller 对照（代码映射）

WebDriver-Compatible API 的实现基本集中在：

- `pulsar-rest/src/main/kotlin/ai/platon/pulsar/rest/openapi/controller/`

按 tag 对应 Controller 文件：

| Tag | Controller |
|---|---|
| session | `SessionController.kt` |
| navigation | `NavigationController.kt` |
| selectors | `SelectorController.kt` |
| element | `ElementController.kt` |
| script | `ScriptController.kt` |
| control | `ControlController.kt` |
| events | `EventsController.kt` |
| agent | `AgentController.kt` |
| pulsar | `PulsarSessionController.kt` |

关键依赖（用于区分 real/mock）：

- real 会话与真实能力入口：`pulsar-rest/.../openapi/service/SessionManager.kt`（存在时 controllers 会启用 real 分支）
- mock/演示存储：`pulsar-rest/.../openapi/store/InMemoryStore.kt`

---

## 4. 实现覆盖矩阵（real / mock）

> 口径：
> - **real**：controller 通过 `SessionManager` 获取 session，并调用 `session.pulsarSession.*` 或 `session.pulsarSession.getOrCreateBoundDriver()`/`session.agent.*` 完成真实动作。
> - **mock**：仅操作 `InMemoryStore` 中的会话/元素/事件，返回演示数据。
>
> 注意：当前实现允许 real 和 mock 并存（同一 endpoint 在不同运行配置下走不同分支）。

| Tag | Endpoint（代表性） | Real | Mock | 备注 |
|---|---|---:|---:|---|
| session | `/session` `/session/{id}` | ✅ | ✅ | `SessionController`：根据 `SessionManager` 是否注入切换 real/mock |
| navigation | `/session/{id}/url` | ✅ | ✅ | real 模式会 `pulsarSession.load(url)`，但 `GET url/documentUri/baseUri` 当前主要返回“存储的 url” |
| selectors | `/selectors/exists` `/waitFor` `/click` `/fill` `/press` `/outerHtml` `/screenshot` | ✅ | ✅ | real 模式通过 bound driver 执行；mock 模式多为演示（如 exists 固定 true、screenshot 返回 mock base64） |
| selectors | `/selectors/element(s)` | ❌ | ✅ | 当前查找 element(s) 仍基于 store 生成 elementId（real 模式未对齐为“从 driver 实际 find”） |
| element | `/element/{elementId}/*` | ✅(部分) | ✅ | real 模式通过 store 中 elementId → selector，再用 driver 操作；elementId 本身仍来自 store |
| script | `/execute/sync` `/execute/async` | ✅ | ✅ | real 通过 driver.evaluate；mock 固定返回 null |
| control | `/control/*` | ✅(部分) | ✅ | `/control/delay` 会休眠请求的时长（最大 30 秒防止资源耗尽）；`/control/pause` 和 `/control/stop` 更新 `SessionManager` 中的会话状态，但尚未直接暂停/停止 driver 级操作。 |
| events | `/event-configs` `/events` `/events/subscribe` | ❌ | ✅ | 仅 mock（内存事件体系），不是浏览器真实事件流 |
| agent | `/agent/*` | ✅ | ✅ | real 调用 `session.agent.*`；mock 返回演示响应 |
| pulsar | `/normalize` `/open` `/load` `/submit` | ✅ | ✅ | real 调用 `pulsarSession.*`；mock 返回演示 WebPageResult |

---

## 5. 已知语义差异与注意事项（spec vs 实现）

### 5.1 navigation 的“当前 URL”语义
- `POST /url`（real）会触发加载：`pulsarSession.load(request.url)`，并将 url 写入 `SessionManager`。
- `GET /url` / `GET /documentUri` / `GET /baseUri`（real）目前主要基于 **SessionManager/会话对象中存储的 url**。
  - 这与“从真实浏览器读取当前地址/文档地址”的 WebDriver 语义并不完全等价。

### 5.2 selectors / element 的 elementId 语义
- `elementId` 当前更像“服务端 session store 的句柄”。
- real 模式下，element 的 click/fill/text/attribute 等，会把 elementId 反查成 selector，再通过 driver 执行。
  - 这意味着 elementId 的生命周期/有效性由 store 决定，并非浏览器端原生引用。

### 5.3 control / events 现状

- `control` 端点现已有部分真实实现：
    - `/control/delay`：会休眠请求的时长（最大 30 秒，防止资源耗尽）。
    - `/control/pause` 和 `/control/stop`：更新 `SessionManager` 中的会话状态，但尚未直接暂停/停止 driver 级操作。
- `events` 当前没有 real 分支：主要用于演示与接口占位（内存事件体系）。
- 若要完全对齐 WebDriver/浏览器事件流，需要引入 driver 侧能力与更明确的状态机/订阅模型。

---

## 6. 维护建议（让 spec vs 实现可持续对齐）

1. **以 `openapi.yaml` 为唯一 spec 来源**：新增/修改端点时先改 yaml，再补 controller 和本文的映射/矩阵。
2. **明确 demo-only 等级**：建议在 controller 或 docs 中统一标注（例如 `@Deprecated("demo-only")`/README 标识），避免误用。
3. **优先补齐真实实现（按使用价值）**：
   - P0：`selectors/element(s)` 的 real find 对齐（从 driver 实际查找并返回稳定 elementId 策略）
   - P1：`events` 的 real 语义设计（浏览器事件流集成，若对外承诺）
   - P1：进一步对齐 navigation 的“当前 URL/documentUri”获取方式
   - P2：扩展 `control/pause` 和 `control/stop` 以暂停/停止 driver 级操作
4. **增加最小契约测试（MockMvc/WebTestClient）**：至少覆盖
   - `POST /session` → 返回 sessionId
   - 404 错误体结构符合 `ErrorResponse`
   - `POST /session/{id}/load` 或 `POST /session/{id}/url` 的基本成功路径

---

## 7. 快速验证（Windows / PowerShell / Maven Wrapper）

> 说明：仓库是多模块 Maven，请在项目根目录使用 `mvnw.cmd`。

```powershell
# 1) 快速构建（跳过测试）
.\mvnw.cmd -q -DskipTests package

# 2) 仅验证 REST 模块测试（会级联构建依赖模块）
.\mvnw.cmd -pl pulsar-rest -am test -D"surefire.failIfNoSpecifiedTests=false"
```

---

## 附录 A：其它 REST surfaces

仓库中还存在非 `/session...` 的 REST Controller（例如 `/api/*` 命令、对话、抽取等）。这些端点是否纳入 OpenAPI（以及是否与本文同一契约面）建议另起文档说明，避免与 WebDriver-Compatible API 混淆。

---

## 其他资源

- **[OpenAPI 规范](./openapi.yaml)** - 完整 API 规范
- **[变更日志](./CHANGELOG.md)** - 版本历史和发布说明
- **[版本演化方案](../docs/api-version-evolution.zh.md)** - 版本策略和规则
- **[REST API 示例](../docs/rest-api-examples.md)** - 实用示例

---

_最后更新：2025-01-20 | 文档版本：1.0.0_
