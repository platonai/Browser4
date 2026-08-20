# Browser4 差异化优势、竞品对比与产品文案

> 分析日期：2026-07-14
> 数据源：`README.md`（778 行全量）、`AGENTS.md`、`CLAUDE.md`、`docs/` 与 `skills/browser4-cli/` 目录结构
> 竞品信息基于公开资料与常识，发布前请核对竞品最新版本特性；性能数字为 README 的 "designed for" 设计目标，未见公开 benchmark。

## 1. 差异化优势清单（20 条，六组）

### A. 抽取与智能（5）
1. **X-SQL + 零 Token 确定性抽取** — `htmlsnapshot query --sql @query.sql` 对存储页面快照做确定性抽取，规模化时成本趋近于零。
2. **WebMiner / scent-miner ML 聚类** — `java -jar scent-miner.jar all <dir>` 一键跑完 encode（HTML→特征向量）→ cluster（SMILE KMeans，K 自动检测）→ views（交互式 HTML 报告 + Excel），不消耗 LLM token；免费层 <1,000 页单机、商业层 Spark 分布式。**本仓库自带 `skills/scent-miner/SKILL.md` 技能**：launcher 自安装/自更新（SHA-256 校验）、`run-example` 内置演示语料，agent 可零人工跑通整条管线。
3. **混合智能四层可降级** — LLM 抽取、ML 聚类、X-SQL、经验库任选一层，LLM 是可选项而非必需品。
4. **渐进式经验库** — `experience save/query/deep-learn` 按 URL/域名沉淀选择器、反爬陷阱与提示，跨会话、跨智能体复用。
5. **双快照模型 + WPSI 压缩摘要** — `snapshot`（交互 a11y refs）与 `htmlsnapshot`（抽取 DOM）两套互补视图；`htmlsnapshot summary` 产出 token 高效的 Web Page Summary Index；快照落盘可 `grep/list/clean` 复用。

### B. 性能与规模（4）
6. **CDP 原生协程引擎** — 自研 PulsarWebDriver 直封 CDP，设计目标单机日处理 10万–20万 复杂页面。
7. **swarm 并行采集 + URL 池优先级调度** — `--priority/--deadline/--expires/--refresh` 语义，状态面板可见排队/实时/延迟计数。
8. **batch 一步多命令 + loop 定时任务** — 一步内多命令原子执行；loop 支持 `--keep-state` 定时监控。
9. **异步任务模型** — agent/swarm/crawl 返回 task ID 轮询，长任务不阻塞 CLI。

### C. 接口与双受众（3）
10. **MCP-over-HTTP + Rust CLI + REST 三层接口** — 同一引擎同时服务人类与智能体。
11. **人机同一引擎双界面** — headed 交互（`open --headed`、`--boxes`）与 headless 智能体模式（默认、`--no-snapshot`、`--interact-level` 调优）共享一套语义，AI 踩坑时人可接管排障。
12. **智能体自助安装** — 一行 npm 全局装 + `irm`/`curl` 引导脚本，agent 读 SKILL 后自行安装；runtime 的 install/upgrade/uninstall/daemon 自管理。

### D. 状态与运维（4）
13. **有状态会话全家桶** — cookie/localStorage/sessionStorage 全控制 + `state-save/state-load` + profile 模式。
14. **人类登录态复用** — `attach` 接入已登录浏览器（CDP/扩展），导入系统浏览器状态，绕过登录墙与验证码。
15. **生产级可观测性** — `doctor`（诊断/修复/metrics/日志 grep）、聚合状态面板（`/status` 自动刷新）、页面截图墙（`/pages.html` 异步截图）。
16. **插件 JAR + 运行时技能热装** — 服务端插件热插拔、SKILL 目录安装/重载、SDK 兼容性检查。

### E. 生态与自举（2）
17. **47 工具编程智能体内核（browser4-coding）** — 沙箱 shell/fs、脚手架、校验、零依赖 Kotlin 分析、LSP 降级；agent 可为 Browser4 造插件，甚至开发 Browser4 自身；内核只依赖 SLF4J+Jackson+协程，可移植给非 agent 宿主。
18. **内置 coworker 文件队列 + webdb 网页数据库** — 任务文件按 `0draft→1ready→2working→3complete` 状态流转的人机协同；`webdb export/normalize` 把网页当数据资产管理，衔接 WebMiner 流水线。

### F. 市场与合规（2）
19. **Apache 2.0 自托管、数据本地、多 LLM 无锁定** — DeepSeek/OpenRouter/Volcengine/Qwen/OpenAI 可换，抽取与聚类本地跑，代理轮换（`PROXY_ROTATION_URL`）内置。
20. **中国市场全链路** — Gitee 镜像、阿里云 OSS 分发、README.zh 中文文档、Bilibili 视频、国内 LLM 直连。

### 逃生舱与细节（隐含于各组，不单列）
- 任意 `cdp <method>` 直通、`eval`（file/stdin/base64/await）、细粒度键鼠（keydown/mousewheel/drag）、`generate-locator`（ref 自动生成最优 CSS）、随机化输入延迟与 `trapCheck`（CDP 坑编码为工具）。

## 2. 竞品对比表

| 维度 | **Browser4** | Playwright MCP（微软） | Browser Use | Scrapy |
|---|---|---|---|---|
| **定位** | AI-native 浏览器引擎：人机共用 | 给 LLM 提供浏览器操作工具 | LLM 驱动的浏览器智能体 | HTTP 级爬虫框架 |
| **协议/入口** | MCP-over-HTTP + Rust CLI + REST | MCP（stdio/HTTP） | Python 库 + API | Python 框架 |
| **安装上手** | ✅ 一行 npm 装 + agent 读 SKILL 自助安装，runtime 自管理（install/upgrade/doctor） | ⚠️ npm/pip 安装，配置依赖宿主 MCP 客户端 | ⚠️ pip 安装，需配 LLM key | ✅ pip 安装即用 |
| **浏览器驱动** | CDP 原生（自研 PulsarWebDriver，协程安全） | Playwright（CDP/WebKit/Firefox） | Playwright（CDP） | 默认无浏览器（可拼 Splash/Playwright） |
| **确定性抽取** | ✅✅ X-SQL 查存页快照，零 token；双快照模型；WPSI 压缩摘要；webdb 网页数据库 | ❌ 只有 a11y 快照文本，交给 LLM | ❌ DOM 片段喂给 LLM | ✅ XPath/CSS + Item Pipeline（HTTP 层） |
| **AI 抽取** | ✅ 可选：extract/summarize/chat，LLM 供应商可换 | ❌ 无内置（靠宿主 LLM） | ✅ 核心即 LLM（每步烧 token） | ❌ 无 |
| **混合智能降级** | ✅ LLM/ML/X-SQL/经验库四层可选，scent-miner（WebMiner）ML 本地出报表，仓库自带 scent-miner 技能 | ❌ | ❌ | ❌ |
| **经验记忆** | ✅ experience save/query/deep-learn 复用选择器与反爬经验 | ❌ | 部分（近期有 memory 能力） | ❌ |
| **登录态/身份复用** | ✅✅ attach 已有浏览器、扩展导入、state-save/load、profile 模式、cookie/local/session storage 全控制 | ⚠️ 有持久 context，但无机器人共享的导入路径 | ⚠️ 浏览器 profile 复用 | ❌ 无浏览器身份概念 |
| **规模化** | ✅ swarm 并行 + URL 池优先级/期限调度 + batch/loop 定时；宣称单机日 10万–20万 复杂页（设计目标） | ⚠️ 单实例单浏览器，横向靠自建 | ⚠️ 多智能体并行，LLM 成本线性放大 | ✅✅ HTTP 级极快（纯 HTML），JS 重站点无力 |
| **低级控制逃生舱** | ✅ 任意 cdp 命令、eval（file/stdin/await）、细粒度键鼠、generate-locator、--no-snapshot/--interact-level 调优 | ⚠️ 依赖 Playwright API 覆盖 | ⚠️ 高层抽象为主 | ❌ 无浏览器层 |
| **可观测性/运维** | ✅✅ headed 模式、/status 聚合面板、页面截图墙、doctor 诊断/metrics/日志 grep、异步任务 ID 轮询 | ⚠️ headed 可看，无运维面板 | ⚠️ 记录/回放逐步完善 | ❌ |
| **可扩展性** | ✅ 插件 JAR + 运行时技能热装 + 浏览器扩展 + coworker 文件队列协同 | ⚠️ 依赖 Playwright 生态 | ⚠️ 靠 Python 生态 | ✅ 中间件丰富 |
| **智能体"自举"** | ✅ 47 个 coding.* 工具：沙箱 shell/fs、脚手架、校验、LSP、开发 Browser4 自身；内核可移植给非 agent 宿主 | ❌ | ❌ | ❌ |
| **中国市场** | ✅ Gitee 镜像、阿里云 OSS 分发、中文文档、Bilibili 视频、国内 LLM 直连 | ⚠️ 国内可用但生态靠社区 | ⚠️ 海外云服务为主 | ⚠️ 社区中文资料较全 |
| **合规/自托管** | ✅ Apache 2.0，抽取/聚类本地跑，数据不出域，代理轮换 | ✅ 开源（MIT 核心） | ✅ 开源但云服务为主力商业模式 | ✅ BSD |

**一句话结论**

- 让 LLM 操纵浏览器 → Playwright MCP / Browser Use 生态更成熟；
- 大规模、低成本、确定性抽取 + 人机共用登录态 + 可运维 → Browser4 的 X-SQL + swarm + attach/state 组合独有；
- 纯 HTTP 极速爬取、无 JS 渲染需求 → Scrapy 依旧犀利，但无浏览器/无 AI/无 MCP，与 Browser4 战场不同。

## 3. 产品页文案

### Hero

> # Browser4
> **An AI-native browser engine — agents drive real browsers, X-SQL extracts data with zero tokens, and swarm scales to 100k+ pages a day.**
>
> 副标语：一个引擎，人机共驾真实浏览器；一条 MCP 指令，从点击、抽取到蜂群采集全打通。
>
> 安装：`npm install -g browser4-cli` —— 让 AI 读完 SKILL 自己装好并开工。

### 三大支柱卖点

1. **Zero-Token Extraction** — 确定性 X-SQL 直查页面快照，WPSI 压缩摘要省上下文，HTML 目录经 `scent-miner`（WebMiner）本地聚类成交互式报表（encode→cluster→views，一条命令）。LLM 只在需要时出场，规模化账单趋近于零。
2. **Hybrid Intelligence, Growing Memory** — LLM/ML/X-SQL/经验库四层可降级；每跑一个站点，选择器与反爬经验自动沉淀（`experience save/query`），越用越懂网页。
3. **Enterprise-Scale, Human-Operable** — CDP 原生协程引擎 + swarm 优先级调度，设计目标单机日行十万页；headed 可视化、`/status` 面板、doctor 诊断，AI 踩坑时人随时接管。

### 对工程师的补充卖点（信任区）

- **人机共用登录态**：`attach` 接入你已登录的浏览器，AI 直接复用 cookie/localStorage，绕过登录墙与验证码。
- **永远有底牌**：任意 `cdp` 命令、`eval` 直执行、细粒度键鼠控制——再怪的页面也有逃生舱。
- **能自我开发**：47 个 `coding.*` 工具，AI 可以为 Browser4 写插件，甚至开发 Browser4 自身。
- **国内友好**：Gitee 镜像、阿里云 OSS 分发、DeepSeek/Qwen 直连、中文文档，开箱即用。

### CTA

> `npm install -g browser4-cli` —— 一行命令，让你的智能体接管真实浏览器；或直接让 AI 读 https://browser4.io/SKILL.md 自助安装。

## 4. 发布前注意事项

- [ ] 性能数字（10万–20万页/天）为设计目标，对外使用前补 benchmark 或保留 "designed for" 措辞。
- [ ] 竞品列特性随时间变化，发布前核对 Playwright MCP / Browser Use / Scrapy 最新版本。
- [ ] 对比表若用于官网，建议增加版本日期与"信息截止"标注。
- [ ] scent-miner（WebMiner）已知问题（来自 coworker 实测记录，v0.0.7）：`all` 的 views 输出落在应用临时目录而非文档所述 `<html-dir>-ml-output` 树（结束时打印绝对路径可缓解）；launcher 曾有吞 stdout 问题（已修复）；需 JDK 17+；免费层 <1,000 页、`--max-files` 默认 40。对外宣传前确认这些点已修复或在文案中规避。
