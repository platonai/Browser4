# Gradle 迁移评估报告

[English Version](../gradle-migration-evaluation.md)

## 1. 项目概述

Browser4 是一个大型多模块 Maven 项目，主要使用 Kotlin 编写（兼容 Java），包含以下主要特点：

- **36+ 个 pom.xml 文件**：大型多模块结构
- **语言**：Kotlin 优先，兼容 Java
- **框架**：Spring Boot、Kotlin Coroutines、JMH 基准测试
- **构建工具**：Maven 3.6+ (Maven Wrapper)
- **CI/CD**：GitHub Actions
- **发布**：Maven Central (Sonatype)

---

## 2. Gradle 的潜在优势

### 2.1 构建速度提升 ✅

| 特性 | Gradle | Maven | 影响 |
|------|--------|-------|------|
| 增量构建 | 原生支持 | 有限 | ⚡ 大幅提升日常开发效率 |
| 构建缓存 | 本地+远程 | 无 | ⚡ 重复构建速度提升 2-5 倍 |
| 并行执行 | 默认开启 | 需手动 | ⚡ 多模块项目显著受益 |
| 守护进程 | 常驻内存 | 每次启动 | ⚡ 启动时间减少 50%+ |

**预估效果**：对于 Browser4 这样的 36+ 模块项目，增量构建可能从 **3-5 分钟** 缩短到 **30 秒以内**。

### 2.2 Kotlin 优先支持 ✅

| 特性 | Gradle | Maven | 说明 |
|------|--------|-------|------|
| Kotlin DSL | 原生支持 | 不支持 | 构建脚本也用 Kotlin 编写 |
| Kotlin 编译 | JetBrains 官方维护 | 社区插件 | 更快的更新和更好的支持 |
| IDE 集成 | IntelliJ 深度集成 | 一般 | Kotlin 项目推荐 Gradle |

### 2.3 灵活性和可扩展性 ✅

- **自定义任务**：比 Maven 的插件机制更灵活
- **依赖约束**：更强大的依赖版本管理
- **Convention Plugins**：复用构建逻辑更方便

---

## 3. 迁移的挑战与风险

### 3.1 迁移成本 ⚠️ **高**

| 工作项 | 预估工时 | 复杂度 |
|--------|----------|--------|
| 36+ 个 pom.xml 转换为 build.gradle.kts | 40-60 小时 | 高 |
| 测试所有模块构建正确性 | 20-30 小时 | 中 |
| CI/CD 迁移 (GitHub Actions) | 8-16 小时 | 中 |
| Maven Central 发布配置迁移 | 8-16 小时 | 高 |
| 团队培训和文档更新 | 8-16 小时 | 中 |
| **总计** | **84-138 小时** | - |

### 3.2 关键配置迁移复杂度

#### 高复杂度 🔴
- **Dokka 文档生成**：Maven 插件配置复杂，需要完全重写
- **Maven Central 发布**：GPG 签名、校验和、staging 配置
- **JaCoCo 聚合报告**：多模块聚合配置需要调整
- **Spring Boot 插件**：需要适配 Gradle 版本

#### 中等复杂度 🟡
- **Kotlin + Java 混合编译**：Gradle 原生支持更好
- **依赖管理**：BOM 导入方式不同
- **Profile 迁移**：Gradle 使用不同的机制

#### 低复杂度 🟢
- **基础依赖声明**
- **资源处理**
- **测试配置**

### 3.3 潜在风险

1. **构建行为差异**：Maven 和 Gradle 的依赖解析策略不同，可能出现依赖冲突
2. **插件兼容性**：部分 Maven 插件无直接 Gradle 替代品
3. **CI/CD 缓存失效**：需要重新调优 GitHub Actions 缓存策略
4. **学习曲线**：团队需要学习 Gradle 和 Kotlin DSL

---

## 4. 当前 Maven 配置的优势

### 4.1 现有投资 ✅

- **成熟稳定**：当前 Maven 配置经过充分测试
- **CI/CD 完善**：GitHub Actions 工作流已完全配置
- **发布流程**：Maven Central 发布配置已就绪
- **团队熟悉**：开发者无需额外学习

### 4.2 Maven 的改进空间

即使不迁移 Gradle，也可以优化当前 Maven 构建：

```bash
# 并行构建（已可用）
./mvnw -T 1C install

# 增量编译（Kotlin 插件支持）
# 已在项目中配置

# 本地缓存（Maven 3.9+）
# 可通过 .mvn/maven.config 启用
```

---

## 5. 成本效益分析

### 5.1 迁移成本

| 成本项 | 估算 |
|--------|------|
| 开发工时 | 84-138 小时 (约 2-3 周全职) |
| 测试和验证 | 20-40 小时 |
| 潜在回归修复 | 16-32 小时 |
| 文档和培训 | 8-16 小时 |
| **总计** | **128-226 小时** |

### 5.2 预期收益

| 收益项 | 估算节省 |
|--------|----------|
| 每次增量构建 | 2-4 分钟 |
| 每天构建次数 | ~20 次 |
| **每天节省** | **40-80 分钟/开发者** |

### 5.3 投资回报周期

假设 3 名全职开发者：
- 日节省：3 × 60 分钟 = 180 分钟 = 3 小时
- 迁移成本：~177 小时（中位数）
- **回报周期**：约 **60 个工作日**（3 个月）

---

## 6. 建议

### 6.1 短期建议：**不推荐迁移** 🟡

**理由**：
1. 当前 Maven 配置成熟稳定，已满足项目需求
2. 迁移成本高，风险不可忽视
3. 投资回报周期较长（约 3 个月）
4. 对于已稳定运行的项目，迁移的边际收益递减

### 6.2 长期考虑

如果以下条件满足，可以重新评估：

1. **新项目/重大重构**：如果计划大规模重构，可顺带迁移
2. **构建时间成为瓶颈**：如果日常开发效率严重受影响
3. **Kotlin 生态需求**：需要使用 Gradle 独有的 Kotlin 特性
4. **团队扩张**：新成员更熟悉 Gradle

### 6.3 替代优化方案

无需迁移 Gradle，可以通过以下方式优化当前 Maven 构建：

```bash
# 1. 启用并行构建
echo "-T 1C" >> .mvn/maven.config

# 2. 跳过非必要模块
./mvnw -pl pulsar-core -am install

# 3. 使用 Maven 守护进程 (mvnd)
# https://github.com/apache/maven-mvnd
```

---

## 7. 结论

| 维度 | 评分 | 说明 |
|------|------|------|
| 迁移可行性 | 🟢 高 | 技术上完全可行 |
| 迁移成本 | 🔴 高 | 128-226 小时工作量 |
| 预期收益 | 🟡 中 | 构建速度提升 2-5 倍 |
| 风险 | 🟡 中 | 可能出现构建行为差异 |
| **综合建议** | **暂不迁移** | 当前 Maven 配置满足需求 |

**最终建议**：保持当前 Maven 构建系统，优化现有配置。仅在以下情况下考虑迁移：
- 计划大规模项目重构
- 构建时间成为严重瓶颈
- 团队对 Gradle 有强烈偏好

---

## 附录 A：Gradle 迁移示例（仅供参考）

### A.1 根项目 build.gradle.kts 骨架

```kotlin
plugins {
    kotlin("jvm") version "2.2.20" apply false
    kotlin("plugin.spring") version "2.2.20" apply false
    id("org.springframework.boot") version "3.3.8" apply false
    id("io.spring.dependency-management") version "1.1.4" apply false
}

allprojects {
    group = "ai.platon.pulsar"
    version = "4.2.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    
    dependencies {
        implementation(kotlin("stdlib"))
        implementation(kotlin("reflect"))
        testImplementation(kotlin("test-junit5"))
    }
}
```

### A.2 子模块 build.gradle.kts 示例

```kotlin
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":pulsar-core:pulsar-skeleton"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
```

---

*文档生成日期：2026-01-09*
*版本：1.0*
