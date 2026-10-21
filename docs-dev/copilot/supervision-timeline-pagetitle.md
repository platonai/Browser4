# 监督时间线（b4 插件开发任务执行记录）

日期：2026-08-19 · 后端：新构建 Browser4.jar（4.14.0-SNAPSHOT）@ 8182 · LLM：deepseek（~/.browser4 配置）

## 环境准备
- 旧 runtime bundle（18182）为过期代码（glob bug 未修复等），弃用；全量 `mvn install`（2min 成功）后启动新后端 8182。
- CLI 指向 8182 用 `--server`；b4 agent 会话用 `-s <name>` 隔离。

## 任务（code devtask 版）
- `code devtask "<插件任务>"` 输出 6 步计划：read(browser4-plugin.js 误提取) / impact / mvnBuild(browser4-plugins/browser4-seo) / test(-Dtest=PageTitleConfigTest,PageTitleServiceTest 指向 seo 模块) / validate repo-consistency / commit。
- `--verify`：mvn compile browser4-seo -am exit 0 ✓；repo-consistency 通过（1 warning：browser4-pageinfo 未注册到根 pom——既有问题）。

## code 工具冒烟结果（新后端）
| 命令 | 结果 |
|---|---|
| code workspace / list / stat / grep / read / write / append / replace / diff / changes / delete / validate | 全部正常 |
| code glob "browser4-plugins/*/pom.xml" | ❌ Illegal char <*>（indexOfLast bug） |
| code glob "dir/**/*" | ⚠ 漏根级文件（pom.xml/README 不出现） |
| code list --depth 10/20 | ⚠ 静默截断到 5 层，深层 kotlin 文件不显示 |

## b4 agent 执行轮次
| 轮 | 会话 | 结果 | 根因 |
|---|---|---|---|
| 1 | default | ❌ 16 步，零写入 | 探索低效（listDir/glob 缺陷）；cli.run(powershell) 误用（browser4-cli 无该命令，只出帮助）；随后 LLM 连续 5 次无工具调用 → noop.stop(limit=5) |
| 2 | pagetitle2 | ❌ 52 步被我强杀 | listDir/glob 缺陷导致“文件清单不确定”死循环；任务文本引导它走 CLI scaffold 而非 coding.scaffoldToDir |
| 3 | pagetitle3 | ❌ 12 步取消 | `doResolve.cancelled reason=Timed out waiting for 30000 ms`——浏览器 DOM 快照超时取消整个 run；另有 DeepSeek 拒绝 image_url（unknown variant）重试 3 次 |
| 4 | pagetitle4 (about:blank 预热) | ❌ 75 步被我强杀 | 无 DOM 超时（预热有效），但模型反复重读同一批文件，并开始乱调 scraping skill；noop 间歇出现 |
| 5 | pagetitle5 (about:blank 预热) | ❌ 61 步 noop 中止 | 开始写入（4 个 Kotlin 类、manifest 同步）；随后进入"写新→删旧"陷阱：先写新内容到旧文件名，再删除旧文件 = 自毁工作；恢复期乱调用 scraping skill |
| 6 | pagetitle6 (about:blank 预热) | ❌ 24 步挂起 | 纯编辑任务（禁重命名/删除）有效：17 步开始写入；但 13K 字符 LLM 请求挂起 7 分钟（API 无响应） |
| 7 | pagetitle7 | ❌ 61 步 noop 中止 | 任务内置 BrowseEventMount 签名 → 10 步即写入；4 文件+tests 完成；导入包错（browser.* vs skeleton.*）编译失败，修复后重跑 mvn 时 5 连 noop 中止 |
| 8 | pagetitle8 | ✅ 20 步完成 | 小任务（修 1 行 + mvn + validate + README）：driver.title() 修复，mvn package 通过（5 测试），两项 validate 无 ERROR |
| 9 | pagetitle9 | ✅ 7 步完成 | 补实现 getPageInfo.js（scaffold 桩漏实现核心功能——validate 全过但功能缺失，监督发现） |

## 最终结果
- 插件 browser4-pagetitle 由 b4 完成：13 文件（11 main + 2 test）、mvn package 通过（5 测试全过，独立重跑验证 EXIT 0）、validate plugin 全过、repo-consistency 无新增 ERROR、jar 已产出（25.8KB）。
- 未完成项：ModuleMap.kt 未同步（repo-consistency 不检查 ModuleMap，只有 ModuleMapDriftE2ETest 会抓）；PagetitleConfigTest 缺 fromConfig 用例（只有默认值用例）。
- 范围约束遵守：git 只动了 browser4-plugins/pom.xml（scaffold 自动注册）+ 插件目录；无 git add/commit。

## 关键缺陷清单（供改进报告）
1. glob：`indexOfLast` 取最后一个非通配段 → 通配符进 Path.resolve → InvalidPathException
2. glob：`dir/**/*` 不匹配根级文件（Java glob 语义）→ 代理困惑
3. listDir：`maxDepth.coerceIn(1,5)` 静默截断，无提示 → 深层文件不可见
4. DevTaskPlanner：FILE_PATTERN 扩展名交替顺序问题（json 被截为 js）；新模块无法推断；测试类指向错误模块
5. agent 循环浏览器导向：纯编码任务每步做页面观察（DOM 快照），超时即取消 run；无“编码模式”
6. DeepSeek 拒绝 image_url：已有降级逻辑（isImageNotSupportedError）但 CachedBrowserChatModel 层先重试 3 次失败（错误格式未匹配）
7. noop.stop(limit=5)：LLM 连续文本响应即中止，即使 taskComplete=false 也不重试；长工具执行期间也计数
8. scaffoldToDir 自动注册 aggregator pom 模块 → 需要手动同步 ModuleMap（工具不自动更新 ModuleMap；repo-consistency 也不检查 → 流程缺口，只有 ModuleMapDriftE2ETest 会抓）
9. agent run 无 --wait 传参（CLI 拒绝未知选项，handle_agent_run 的 wait 参数不可达）
10. 代理自毁模式："写新内容到旧文件名 + 删除旧文件" = 数据丢失（第 5 轮）；恢复期不用 coding.revert/changeSummary 审计
11. validate plugin 全过 ≠ 功能完整：getPageInfo.js 是 scaffold 桩（只返回 url），校验器只查文件存在性不查内容语义——人工监督审查才发现
12. 长任务上下文膨胀 → 模型反复重读同一文件、乱调无关 skill（scraping）、text-only 响应增多
13. 有效干预手段：任务文本内置 API 签名（省去 API 研读）、禁 listDir/glob、禁删文件、任务切小——都显著提升了完成率

## 备注
- `code changes` 曾显示 server-hold5.log/server-hold7.log 的删除记录（非本会话操作）——快照追踪的杂音。
