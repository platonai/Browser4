# Testcontainers 评估快速参考

> **TL;DR**: 推荐在开发者本地测试和 SDK 测试中使用 Testcontainers，不建议全面替换现有架构。

## 一句话总结

**在本地开发和 SDK 测试中引入 Testcontainers 可以显著提升开发体验，但不应替换 CI/CD 中已优化的 Docker Compose 方案。**

---

## 快速决策表

| 使用场景 | 推荐度 | 理由 |
|---------|-------|------|
| 🏠 开发者本地集成测试 | ⭐⭐⭐⭐⭐ | 自动管理 MongoDB，零配置 |
| 📦 SDK 契约测试 | ⭐⭐⭐⭐⭐ | 独立环境，可靠性高 |
| 🔄 数据库迁移测试 | ⭐⭐⭐⭐ | 多版本支持 |
| 🤖 CI/CD 集成测试 | ⭐ | 现有方案已优化 |
| 🌐 浏览器自动化测试 | 不推荐 | Browser4 已有优化方案 |
| 🎯 E2E 测试 | 不推荐 | MockSiteLauncher 更轻量 |

---

## 核心收益

### ✅ 主要收益
1. **简化开发环境** — 无需手动 `docker compose up`
2. **提升测试隔离性** — 每个测试类独立容器
3. **支持多版本测试** — 轻松测试 MongoDB 4.4/5.0/6.0/7.0
4. **一致的开发体验** — 所有开发者环境相同

### ⚠️ 主要成本
1. **测试执行变慢** — 容器启动需要 5-10 秒
2. **Docker 环境依赖** — 所有开发者必须安装 Docker
3. **新增依赖管理** — 需要维护 Testcontainers 版本
4. **学习成本** — 团队需要了解 Testcontainers API

---

## 使用示例

### 基础用法

```kotlin
@SpringBootTest
@Testcontainers
@Tag("Integration")
@Tag("RequiresDocker")
class MyIntegrationTest : AbstractMongoDBTestcontainerTest() {
    
    @Autowired
    lateinit var session: PulsarSession
    
    @Test
    fun testWithMongoDB() {
        val page = session.load("https://example.com")
        assertNotNull(page.persistentId)
    }
}
```

### 执行命令

```bash
# 默认：跳过 Testcontainers 测试（快速反馈）
mvn test

# 运行集成测试（包含 Testcontainers）
mvn test -DrunITs=true

# 运行特定测试
mvn test -Dtest=MyIntegrationTest
```

---

## 实施路线图

### 第 1 阶段：试点（1-2 周）
- [ ] 选择 2-3 个集成测试试点
- [ ] 添加 Testcontainers 依赖到 BOM
- [ ] 创建 `AbstractMongoDBTestcontainerTest` 基类
- [ ] 对比执行时间和稳定性

### 第 2 阶段：扩展（2-3 周）
- [ ] 扩展到 SDK 测试模块
- [ ] 提供 Browser4 容器测试环境
- [ ] 收集开发者反馈

### 第 3 阶段：文档化（1 周）
- [ ] 更新 `TESTING.md` 添加 Testcontainers 指南
- [ ] 创建最佳实践示例
- [ ] 团队培训

---

## 关键配置

### Maven 依赖（BOM）

```xml
<!-- pulsar-bom/pom.xml -->
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-bom</artifactId>
            <version>1.20.4</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

### 测试模块依赖

```xml
<!-- pulsar-it-tests/pom.xml -->
<dependencies>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>testcontainers</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>mongodb</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>junit-jupiter</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

---

## 性能优化

### 启用容器复用

```kotlin
companion object {
    @Container
    @JvmStatic
    val mongodb = MongoDBContainer("mongo:7.0")
        .withReuse(true) // ← 关键优化
}
```

**效果：**
- 10 个测试类：从 ~100 秒降至 ~20 秒
- 50 个测试类：从 ~500 秒降至 ~30 秒

---

## 与现有架构对比

| 维度 | Docker Compose | Testcontainers | 推荐 |
|------|---------------|----------------|------|
| 开发体验 | 手动启动 | 自动启动 | Testcontainers |
| 测试隔离 | 共享实例 | 独立容器 | Testcontainers |
| CI/CD | 已优化 | 需迁移 | Docker Compose |
| 执行速度 | 快 | 慢 5-10s | Docker Compose |
| 配置复杂度 | 中 | 低 | Testcontainers |

---

## 常见问题

### Q: 是否应该全面替换现有测试？
**A:** 否。保留 CI/CD 中的 Docker Compose，在开发者本地和 SDK 测试中使用 Testcontainers。

### Q: 会不会让测试变得很慢？
**A:** 单次启动增加 5-10 秒，但通过容器复用可以显著优化。对比手动启动 Docker Compose，总体开发效率更高。

### Q: 所有开发者都必须安装 Docker 吗？
**A:** 是的。但现代开发环境中 Docker 已是标配，且 Browser4 本身就需要 Docker。

### Q: 如何在 CI/CD 中使用？
**A:** 可以使用，但当前 GitHub Actions 的 Docker Compose 方案已优化，建议保留。

### Q: 是否支持 Windows 开发者？
**A:** 支持。需要安装 Docker Desktop，但要注意商业许可问题。

---

## 决策建议

### ✅ 立即行动
1. 在 `pulsar-it-tests` 中试点 2-3 个测试
2. 添加 Testcontainers 依赖到 `pulsar-bom`
3. 创建 `AbstractMongoDBTestcontainerTest` 基类

### ⚠️ 谨慎评估
1. 对比试点测试的执行时间
2. 收集开发者反馈
3. 评估是否扩展到更多测试

### ❌ 不要做
1. 不要全面替换现有测试基础设施
2. 不要在浏览器自动化中使用 Testcontainers
3. 不要强制所有测试使用 Testcontainers

---

## 相关文档

- 📄 **完整评估报告**
  - 中文版：`docs-dev/testcontainers-evaluation.md`
  - 英文版：`docs-dev/testcontainers-evaluation.en.md`

- 💻 **概念验证代码**
  - POC 目录：`docs-dev/testcontainers-poc/`
  - 示例测试：`WebPagePersistenceIT.kt`

- 📚 **测试体系文档**
  - `TESTING.md` — Browser4 测试分类体系
  - `pulsar-tests-common/README.md` — MockSiteLauncher
  - `docs-dev/maven-failsafe-plugin-evaluation.md` — Failsafe 评估

---

## 联系方式

有问题？
- 查看完整评估报告：`docs-dev/testcontainers-evaluation.md`
- 查看 POC 代码：`docs-dev/testcontainers-poc/`
- 咨询团队技术负责人

---

*快速参考版本: v1.0*  
*更新日期: 2026-02-14*
