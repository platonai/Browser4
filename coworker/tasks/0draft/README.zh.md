# Coworker Task Drafting Area

在此处起草您的任务。本区域用于尚未准备就绪的任务草稿，不供执行。

1. 运行 `coworker-scheduler.ps1` 以启动定时自动化
2. 在 `0draft` 下起草任务（或者任何地方）
3. 将已完成草稿的任务复制到 `1created` 目录以执行
4. 执行后，您可以在 `3_1complete` 中找到结果，在 300logs 中找到详细日志
5. 如有需要，复核结果
6. 将任务文件从 `3_1complete` 移动到 `5approved` 以便触发 git 推送

任何提示语都可以用来描述一项任务，但为求清晰明了，建议使用以下模板：

```markdown
# 任务标题

## 问题描述
详细描述需要解决的问题或任务，尽量具体明确。

## 解决方案

简要说明你计划采用的解决思路，包括步骤、方法或任何具体的技术方案。

## 参考资料

列出有助于理解和解决任务的相关资源、文档或参考链接。
```

## 示例

- [简单示例 - refine-coworker-readme.md.md](../6git-pushed/2026/0228/refine-coworker-readme.md.md)
- [进阶示例 - implement-daily-memory-batching.md](../6git-pushed/2026/0228/implement-daily-memory-batching.md)

## 参考资料

- [coworker.ps1](../../scripts/coworker.ps1)
- [coworker.sh](../../scripts/coworker.sh)
- [coworker-scheduler.ps1](../../scripts/coworker-scheduler.ps1)
- [coworker-scheduler.config.psd1](../../scripts/coworker-scheduler.config.psd1)
