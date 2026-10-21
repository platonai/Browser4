# skill 工件对照

## 契约（SkillDefinitionLoader）

SKILL.md 必须满足：
- frontmatter：`name`（kebab-case，== 目录名）、`description`（1–1024 字符）、`allowed-tools`
- 正文：when-to-use 触发条件 + 工作流步骤

## 真实实现：skills/browser4-coding/SKILL.md

```yaml
---
name: browser4-coding
description: "Create and validate Browser4 plugins, skills, JS scripts, and shell scripts; ..."
allowed-tools: coding.scaffold coding.validate coding.write ...
---
```

（46 工具全量文档，见 `skills/browser4-coding/SKILL.md`；自身开发流见 `skills/browser4-dev/SKILL.md`。）

## 脚手架输出：generated/extract-prices-SKILL.md

由 `coding.scaffold(type="skill", name="extract-prices", description="Extract product prices from a page",
triggers="When the user asks to extract prices,...", tools="coding.read,tab.eval")` 生成。

对比要点：
- `name: extract-prices` 必须与目录 `skills/extract-prices/` 一致（loader 硬要求）
- `allowed-tools` 由 `tools` 参数推导
- 正文含 When to Use + 工作流骨架，供 Agent 补全细节

## 交叉验证

`coding.validate(type="skill", path=...)` 会额外做**工具引用交叉验证**：正文里的 `domain.method(` 调用
逐一对照运行时可见工具集（硬编码域 + 插件注册域），已知域未知方法 = ERROR、未知域 = WARNING。
