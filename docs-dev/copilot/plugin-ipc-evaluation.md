# 插件 ↔ 主程序通讯升级为进程间通讯（IPC）的评估

> 状态：评估结论（2026 年，4.14 分支）
> 范围：Browser4 插件系统（`browser4-boot` + `browser4-pdk` + `browser4-plugins`）
> 结论速览：**不建议现在做全量 IPC**；建议分三步走（见文末路线）。

---

## 一、现状基线：现在的"通讯"到底是什么

当前插件通讯是**同 JVM 进程内直接方法调用**，没有任何进程边界：

| 通讯通道 | 机制 | 跨边界传递的数据 |
|---|---|---|
| 事件挂载（Load/Browse/CrawlEventMount） | 插件把回调注册进 `PulsarEventBus` 处理器链，主程序在加载/浏览/爬取阶段同步（或挂起）调用 | **活对象引用**：`UrlAware`、`WebPage`（**可变**，有 setter）、`FeaturedDocument`（DOM 树）、`WebDriver`（活 CDP 会话）、`PrivacyContext`、`PageDatum` |
| 工具挂载（ToolMount → CustomToolRegistry） | `AgentToolManager` 把插件注册的 `ToolExecutor` 当成本地工具调用 | `receiver` 参数**直接就是 `WebDriver` 实例**（captcha 插件 `driver.evaluateValue(js)` 注入 token） |
| 生命周期（Browser4Plugin） | `onStartup`/`onShutdown` 进程内回调 | — |
| 隐含通讯面（最大） | 插件是 Spring bean，可 `@Autowired` **任意主程序 bean**：PulsarContext、会话池、隐私上下文池、浏览器驱动池 | 全量主机内部 API |

关键事实：

- 插件 JAR 由 `PluginClasspathEnhancer` 在 Spring 启动前用 `URLClassLoader` 载入**同一进程**；安装/卸载需要**重启**才能生效（`PluginService` 明确如此）。
- 依赖隔离靠 "thin JAR + provided scope"（PDK 的核心设计决策）来规避，类加载冲突并未根治。
- 插件崩溃（OOM、死循环、`System.exit`）= **主程序崩溃**。
- 仓库已有进程边界先例：CLI↔后端是 MCP over HTTP；`MemoryExternalBridge` 已实现"拉起外部 MCP 子进程 + stdio 客户端传输"。MCP 的 server（HTTP/stdio）和 client（stdio）两端 SDK 都已在用。

### 关键代码位置

| 组件 | 位置 |
|---|---|
| 插件加载（URLClassLoader） | `browser4-boot/.../plugin/PluginClasspathEnhancer.kt` |
| 挂载点 wiring | `browser4-boot/.../plugin/PluginManager.kt` |
| JAR 管理（安装/卸载/重启生效） | `browser4-boot/.../plugin/PluginService.kt` |
| 挂载点接口（27 个事件钩子） | `browser4-core/browser4-skeleton/.../plugin/MountPoints.kt` |
| 事件处理器链签名（跨边界数据类型） | `browser4-core/browser4-skeleton/.../event/EventHandlers.kt` |
| 工具挂载 | `browser4-agentic/.../tools/ToolMount.kt`、`builtin/AbstractToolExecutor.kt` |
| 外部进程 MCP client 先例 | `browser4-agentic/.../memory/external/MemoryExternalBridge.kt`（`StdioClientTransport`） |
| 插件清单（10 个模块） | `browser4-plugins/`：captcha、images、markdown、media、pptx、seo、swarm、forms、headings、wordcount |

---

## 二、升级动机（IPC 能买到的）

1. **崩溃隔离**——插件 OOM/死循环/原生崩溃不再拖垮主程序（当前 captcha、media/FFmpeg 等直接在宿主进程里干活）
2. **热插拔**——装/卸/升级免重启（当前必须重启，PDK 工作流里 `curl install` 之后还要 restart）
3. **类加载彻底隔离**——摆脱 provided-scope 的脆弱平衡
4. **多语言**——插件不再强制 Kotlin/JVM
5. **安全**——不可信插件拿到最小权限，不能 `System.exit`、读宿主内存、越权操作 CDP
6. **资源隔离**——独立 CPU/内存限额

---

## 三、方案选项

### 方案 A：进程内"伪 IPC"（消息化契约 + 隔离类加载）

保持同进程，但：

1. 每插件独立 child-first ClassLoader（解决依赖冲突）
2. 热重载（新 ClassLoader + 重新 wiring 挂载点，Spring context 内可做）
3. 把插件契约从"活对象回调"抽象为**消息/DTO 接口**，先实现进程内 transport

- ✅ 成本最低、风险最小；解决"重启"和"依赖冲突"两大痛点；"消息化"这一步让未来换真 IPC 只是换 transport
- ❌ 不解决崩溃隔离、多语言、安全

### 方案 B：插件独立进程 + MCP/JSON-RPC 本地通道（推荐方向）

插件仍是 JVM 进程但独立运行；主程序作为 **MCP client** 拉起插件（直接复用 `MemoryExternalBridge` 的 `StdioClientTransport` 模式，产品自身的通讯标准就是 MCP，栈已齐）。传输可选 stdio / 本地 Unix socket / Windows 命名管道 / localhost HTTP。

- 工具 = 插件的 MCP tools；事件 = 主程序→插件推送（需扩展 JSON-RPC 通知或借 sampling 机制）
- **数据面是最大工程**：
  - `WebDriver` 活对象不可序列化 → 要么做远程 driver 代理（每个 `evaluateValue`/点击变成一次 RPC），要么把浏览器 tab 的 **CDP websocket 地址交给插件直连**（浏览器本身已按 CDP 暴露，PulsarWebDriver 只是包装）——后者省代理但引入会话生命周期、隐身指纹、并发访问治理问题
  - `WebPage`/`FeaturedDocument` 改传 DTO 快照（每页 MB 级 DOM → 序列化成本，10 万页/天目标 ≈ GB 级 IPC 流量）
- ✅ 崩溃隔离、热插拔、依赖隔离全解决；JVM 插件零语言迁移
- ❌ 工程量大；事件链语义要重新设计

### 方案 C：插件 = 远程 HTTP 服务（彻底解耦）

插件独立部署（甚至另一台机器），REST 注册/调用，事件走 webhook。语言无关、实现最直接（复用 REST 层）。

- ❌ 热路径事件（`onWillLoad`/`onNormalize` 每个 URL 都触发）跨 HTTP 的延迟和不可靠不可接受；filter 短路、`WebPage` 可变、同步链顺序全部需要异步化 → **语义大改**，相当于重写插件模型

### 方案 D：混合（务实首选）

- **热路径事件挂载留在进程内**（load/browse/crawl 阶段的 27 个钩子：延迟敏感、传活对象、同步语义）
- **工具执行器 + 重活外部能力**（打码服务、FFmpeg、Tika、批量下载）走 IPC 进程化——这是插件最独立、最受益于隔离的部分
- ✅ 以 1/3 的成本拿到大部分收益（崩溃隔离覆盖"重活"、热插拔、依赖隔离）

---

## 四、成本估算（人周）

| 项目 | 方案 A | 方案 B（首版） | 方案 C | 方案 D |
|---|---|---|---|---|
| 契约/协议设计（消息 schema、注册、版本协商） | 0.5 | 2–3 | 1–2 | 1–2 |
| Host 侧桥（工具桥 + 事件桥 + DTO 转换） | 1–2 | 2–3 | 2 | 1.5–2.5 |
| WebDriver 代理或 CDP 直连方案 | — | **2–3**（最大单项：6+ 插件直接操 driver） | 1–2 | 1–1.5 |
| 进程监督器（拉起/握手/心跳/重启/孤儿回收） | — | 1–2 | 0.5 | 1 |
| 内置插件迁移（**10 个模块**：captcha、images、media、markdown、pptx、seo、swarm、forms、headings、wordcount） | 0 | 5–12（复杂者 1–2 周/个） | 4–8 | 3–6 |
| 打包/启动器/文档/CI/跨进程测试 | 0.5 | 2–3 | 1–2 | 1–2 |
| **合计** | **2–4** | **14–26** | **10–16** | **8–13** |

**隐性成本（所有方案 B/C/D 共有）**：

- PDK（4.13/4.14 刚交付的 archetype、BOM、验证脚本、测试插件）部分作废或重做
- 协议版本化替代现有 `PluginCompatibility` SDK 版本检查
- 日志聚合与跨进程排障
- e2e 测试矩阵新增 flakiness（已有 Docker/Windows/Linux 矩阵）

---

## 五、风险清单

1. **热路径性能回归**——`onHTMLDocumentParsed` 每页传 MB 级 DOM；filter 钩子（URL 拒绝/改写）每 URL 触发；序列化 + 往返延迟（本地 IPC RTT 10–200µs 尚可，但 DTO 快照的拷贝与 GC 压力是实打实的）
2. **语义破坏**——filter 短路（`onWillLoad` 返回 null 拒绝 URL）、`WebPage` 可变性（插件改 page 影响后续流程）、链顺序与异常传播，跨进程全部需要重新定义契约，**每个插件重写**
3. **WebDriver 远程化**——captcha 插件的 js 注入/`evaluateValue` 每步变 RPC；CDP 直连方案引入会话泄漏、隐身指纹破坏（反检测是全项目红线，见 AGENTS.md）、并发访问问题
4. **进程生命周期**——孤儿进程、崩溃检测/重启策略、启动握手顺序（主程序必须等插件就绪）、关闭顺序；Windows 文件锁问题（现有 plugins 目录锁）转移到进程管理
5. **安全面扩大**——IPC 端点需要鉴权与消息限额（消息洪水 DoS）；但宿主内任意代码风险确实下降，是净收益
6. **生态时点风险（最大）**——目前 10 个插件**全部是第一方/内置**，PDK 刚发布、第三方生态未成形；高成本重构的收益对象尚不存在，而重构会拖慢 PDK 生态的建立

---

## 六、结论与建议

**不建议现在做全量 IPC。** 现状下"重启生效 + 类加载冲突 + 崩溃传染"三个痛点中，前两个可用 2–4 人周的低成本方案 A 缓解，第三个（崩溃隔离）当前的触发面主要是几个重活插件，可用方案 D 精准覆盖。全量 IPC（方案 B）是正确方向，但应作为生态成熟后的演进路径，而不是现在的一次性重写——尤其它会重创刚交付的 PDK 一致性，且 10 个第一方插件全部要迁移。

**建议路线：**

1. **现在（2–4 周）**：方案 A——插件契约消息化（把活对象回调抽象成 DTO/消息接口 + 虚拟 transport）+ 每插件隔离 ClassLoader + 热重载。这一步单独看就能解决重启与依赖冲突，更重要的是**让"升级为 IPC"变成换 transport 而非重写架构**，把未来成本从 14–26 人周降到 6–10 人周。
2. **第三方插件生态有苗头时**：方案 D 起步——工具执行器走 MCP-stdio 进程化（复用 `MemoryExternalBridge` 模式），事件挂载暂留进程内。
3. **安全/隔离成为硬需求**（如大规模运行不可信第三方插件）时：事件桥 IPC 化 + WebDriver 代理/CDP 直连，全量进程化。
