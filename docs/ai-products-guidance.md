# AI 编程产品指导文件 / AI Programming Products Guidance Files

本文档列出了为各主要 AI 编程产品提供的项目指导文件。

This document lists the guidance files provided for major AI programming products.

## 支持的 AI 编程产品 / Supported AI Programming Products

### 1. GitHub Copilot

**配置文件 / Configuration File:** `.github/copilot-instructions.md`

GitHub Copilot 是 GitHub 官方提供的 AI 编程助手，集成在 VSCode、JetBrains IDEs 等多个编辑器中。

GitHub Copilot is the official AI programming assistant from GitHub, integrated into VSCode, JetBrains IDEs, and more.

**使用方式 / Usage:**
- GitHub Copilot 会自动读取 `.github/copilot-instructions.md` 文件
- Copilot automatically reads the `.github/copilot-instructions.md` file

### 2. Cursor

**配置文件 / Configuration File:** `.cursorrules`

Cursor 是一个 AI-first 的代码编辑器，基于 VSCode，深度集成了 AI 功能。

Cursor is an AI-first code editor based on VSCode with deep AI integration.

**使用方式 / Usage:**
- Cursor 会自动读取项目根目录的 `.cursorrules` 文件
- Cursor automatically reads the `.cursorrules` file in the project root

**官网 / Website:** https://cursor.sh/

### 3. Windsurf

**配置文件 / Configuration File:** `.windsurfrules`

Windsurf 是 Codeium 推出的 AI 编程编辑器，提供强大的代码理解和生成能力。

Windsurf is an AI programming editor from Codeium, offering powerful code understanding and generation.

**使用方式 / Usage:**
- Windsurf 会自动读取项目根目录的 `.windsurfrules` 文件
- Windsurf automatically reads the `.windsurfrules` file in the project root

**官网 / Website:** https://codeium.com/windsurf

### 4. Cline (formerly Claude Dev)

**配置文件 / Configuration File:** `.github/cline-instructions.md`

Cline 是一个 VSCode 扩展，支持在编辑器内进行 AI 辅助开发，可以执行复杂的多步骤任务。

Cline is a VSCode extension that supports AI-assisted development within the editor, capable of executing complex multi-step tasks.

**使用方式 / Usage:**
- 在 VSCode 中安装 Cline 扩展
- Cline 可以访问 `.github/cline-instructions.md` 获取项目指导
- Install the Cline extension in VSCode
- Cline can access `.github/cline-instructions.md` for project guidance

**VSCode 扩展 / VSCode Extension:** `saoudrizwan.claude-dev`

### 5. Aider

**配置文件 / Configuration File:** `.aider.conf.yml`

Aider 是一个命令行 AI 编程工具，支持与多种 LLM 集成，专注于代码编辑和重构。

Aider is a command-line AI programming tool that integrates with various LLMs, focusing on code editing and refactoring.

**使用方式 / Usage:**
```bash
# 安装 Aider / Install Aider
pip install aider-chat

# 在项目根目录运行 / Run in project root
aider

# Aider 会自动读取 .aider.conf.yml 配置
# Aider automatically reads the .aider.conf.yml configuration
```

**官网 / Website:** https://aider.chat/

## 文件内容说明 / File Content Description

所有指导文件都包含以下核心内容：

All guidance files contain the following core content:

1. **快速开始** - 构建和测试命令 / **Quick Start** - Build and test commands
2. **项目概览** - 架构和模块说明 / **Project Overview** - Architecture and module description
3. **开发规范** - 代码风格和最佳实践 / **Development Standards** - Code style and best practices
4. **测试策略** - 测试组织和覆盖率要求 / **Testing Strategy** - Test organization and coverage requirements
5. **常见问题** - 故障排查和解决方案 / **Troubleshooting** - Problem diagnosis and solutions

## 使用建议 / Usage Recommendations

1. **选择合适的工具** - 根据你的工作流选择最适合的 AI 编程产品
   **Choose the Right Tool** - Select the most suitable AI programming product for your workflow

2. **遵循指导** - AI 会根据这些文件中的指导进行代码生成和修改
   **Follow the Guidelines** - AI will generate and modify code based on the guidance in these files

3. **保持更新** - 当项目约定或架构变化时，及时更新相应的指导文件
   **Keep Updated** - Update the guidance files when project conventions or architecture changes

4. **反馈改进** - 如果发现指导文件需要改进，请提交 PR
   **Feedback for Improvement** - Submit a PR if you find the guidance files need improvement

## 详细文档 / Detailed Documentation

更多详细信息请参考：

For more detailed information, please refer to:

- **完整 AI 编程指南** / **Complete AI Programming Guide:** `README-AI.md`
- **快速参考指南** / **Quick Reference Guide:** `devdocs/copilot/README-nano.md`
- **项目概念** / **Project Concepts:** `docs/concepts.md`
- **高级指南** / **Advanced Guide:** `docs/advanced-guides.md`

## 版本历史 / Version History

- **v2025-10-14**: 初始版本，支持 GitHub Copilot, Cursor, Windsurf, Cline, Aider
- **v2025-10-14**: Initial version, supporting GitHub Copilot, Cursor, Windsurf, Cline, Aider
