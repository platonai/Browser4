# Browser4 四类工件对照示例库

> 真实实现 vs 脚手架输出 —— 反陈旧：所有"脚手架输出"均由仓库内的生成器实时产出（见 `generated/`），
> 重新生成命令见各分类页；若与当前脚手架不一致，说明生成器已演进，以重新生成的输出为准。

| 工件 | 真实实现（参考） | 脚手架输出（generated/） | 生成方式 |
|---|---|---|---|
| plugin | `browser4-plugins/browser4-seo/`（成熟参考插件） | `plugin-weather/`（活模板改名克隆） | `scaffoldFromExample(path=browser4-plugins/browser4-seo, className=WeatherToolExecutor, ...)` |
| skill | `skills/browser4-coding/SKILL.md`（真实技能） | `extract-prices-SKILL.md` | `scaffold(type=skill, ...)` |
| js | 插件内 JS 资源（`browser4-seo/src/main/resources/<domain>/extractMeta.js`） | `extract-prices.js` | `scaffold(type=js, name=extract-prices, purpose=extract)` |
| script | `cli/browser4-cli/...` 部署脚本族 | `build.ps1` | `scaffold(type=script, name=build, scriptType=build, shell=ps1)` |

- [plugin.md](plugin.md) — 插件：真实 seo 插件 vs 活模板克隆
- [skill.md](skill.md) — 技能：SKILL.md 契约结构
- [js.md](js.md) — 浏览器 JS：脚手架形态与运行方式
- [script.md](script.md) — 脚本：build.ps1 脚手架与校验

## 使用方式

1. 生成：用对应 `coding.scaffold` / `coding.scaffoldFlow` / `coding.scaffoldFromExample` 命令产出新工件
2. 对照：与 `generated/` 或仓库内真实实现比对形态是否一致（脚手架演进的信号）
3. 校验：`coding.validate(type=plugin|skill|js|script, path=...)`
4. 落地：`coding.write` 写入仓库，按需 `coding.mvnBuild` / `tab.eval` 验证
