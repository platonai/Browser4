# Dockerfile.fast - 快速构建指南

## 📝 概述

`Dockerfile.fast` 是一个优化的 Dockerfile，用于跳过 Maven 构建阶段，直接使用预先构建好的 JAR 文件来创建 Docker 镜像。这种方式可以显著提高构建速度。

**适用场景：**
- 本地已经构建了 JAR 文件
- 需要快速构建 Docker 镜像进行测试
- CI/CD 流程中已经有独立的构建步骤

## 🚀 使用方法

### 第一步：构建 JAR 文件

首先，使用 Maven 构建项目并生成 JAR 文件：

```bash
# Linux/macOS
./mvnw clean package -DskipTests

# Windows (cmd)
mvnw.cmd clean package -DskipTests

# Windows (PowerShell)
.\mvnw.cmd clean package -DskipTests
```

构建完成后，JAR 文件将位于 `browser4-app/browser4-agents/target/Browser4.jar`

### 第二步：构建 Docker 镜像

使用 `Dockerfile.fast` 构建 Docker 镜像：

```bash
docker build -f Dockerfile.fast -t browser4:latest .
```

## ⚡ 性能对比

| 构建方式 | 构建时间 | 说明 |
|---------|---------|------|
| `Dockerfile` | ~10-15 分钟 | 从源代码完整构建 |
| `Dockerfile.fast` | ~2-3 分钟 | 使用预构建 JAR |

## 📋 技术细节

`Dockerfile.fast` 的关键特点：

1. **单阶段构建**：不包含 Maven 构建阶段，只有运行时阶段
2. **直接复制 JAR**：使用 `COPY browser4-app/browser4-agents/target/Browser4.jar app.jar`
3. **相同的运行时环境**：与原始 Dockerfile 使用相同的基础镜像和配置

## 🔧 运行镜像

构建完成后，可以使用以下命令运行容器：

```bash
docker run -d -p 8182:8182 \
  -e OPENROUTER_API_KEY=${OPENROUTER_API_KEY} \
  -e PROXY_ROTATION_URL=${PROXY_ROTATION_URL} \
  browser4:latest
```

## ⚠️ 注意事项

1. **必须先构建 JAR**：确保在构建 Docker 镜像之前已经生成了 `Browser4.jar` 文件
2. **文件路径**：JAR 文件必须位于 `browser4-app/browser4-agents/target/Browser4.jar`
3. **版本一致性**：确保使用的 JAR 文件版本与预期一致

## 🔍 故障排查

### 问题：Docker 构建失败，提示找不到 JAR 文件

**解决方案：**
```bash
# 检查 JAR 文件是否存在
ls -lh browser4-app/browser4-agents/target/Browser4.jar

# 如果不存在，先构建 JAR
./mvnw clean package -DskipTests
```

### 问题：镜像构建成功但无法启动

**解决方案：**
检查环境变量是否正确设置，特别是 `OPENROUTER_API_KEY`

## 📚 相关文档

- [Docker 开发指南](../docker/README.md)
- [主 Dockerfile](../Dockerfile)
- [项目构建说明](../README.md)
