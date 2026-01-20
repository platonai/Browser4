# Browser4 OpenAPI 协议与 SDK 版本演化方案

## 目录

1. [概述](#概述)
2. [版本号命名规则](#版本号命名规则)
3. [API 版本策略](#api-版本策略)
4. [SDK 版本策略](#sdk-版本策略)
5. [向后兼容策略](#向后兼容策略)
6. [破坏性变更管理](#破坏性变更管理)
7. [弃用流程](#弃用流程)
8. [发布管理](#发布管理)
9. [迁移指南](#迁移指南)
10. [变更日志规范](#变更日志规范)

---

## 概述

本文档定义了 Browser4 OpenAPI 协议和 SDK 的版本演化策略，确保可预测的发布、清晰的向后兼容保证，以及用户的平滑迁移路径。

### 当前版本

| 组件 | 当前版本 | 状态 |
|-----|---------|------|
| Browser4 核心 | 4.5.0-SNAPSHOT | 开发中 |
| OpenAPI 规范 | 1.0.0 | 稳定 |
| Kotlin SDK | 4.5.0-SNAPSHOT | 开发中 |
| Python SDK | 0.1.0 | Beta |

### 版本理念

- **稳定性**：主版本提供长期稳定性和清晰的迁移路径
- **透明性**：所有变更都有明确的影响评估文档
- **可预测性**：用户可以通过语义化版本预期破坏性变更
- **向后兼容**：在主版本内尽可能保持兼容性

---

## 版本号命名规则

### 语义化版本

所有 Browser4 组件遵循[语义化版本 2.0.0](https://semver.org/) 规范（MAJOR.MINOR.PATCH）：

```
主版本号.次版本号.修订号[-预发布版本][+构建元数据]
```

**示例：**
- `1.0.0` - 稳定版本
- `1.1.0-alpha.1` - Alpha 预发布版本
- `1.1.0-beta.2` - Beta 预发布版本
- `1.1.0-rc.1` - 候选发布版本
- `1.1.0+20250120` - 构建元数据

### 版本号含义

#### 主版本号（破坏性变更）

在进行不兼容的 API 变更时递增：
- 移除端点或操作
- 以不兼容的方式更改请求/响应架构
- 移除必需的向后兼容性
- 更改身份验证机制
- 重大架构变更

**影响**：用户必须更新代码并查看迁移指南

#### 次版本号（新功能）

以向后兼容的方式添加功能时递增：
- 新端点或操作
- 新的可选请求参数
- 新的响应字段（非破坏性）
- 新能力或特性
- 性能改进

**影响**：用户可以在不修改代码的情况下升级（但可能想使用新功能）

#### 修订号（错误修复）

进行向后兼容的错误修复时递增：
- 错误修复
- 安全补丁
- 文档更正
- 小优化

**影响**：用户应立即升级（无需代码更改）

### 预发布标识符

- **alpha**：早期开发，API 可能会有重大变化
- **beta**：功能完整，API 基本稳定，测试阶段
- **rc**（候选发布）：稳定版本前的最终测试

---

## API 版本策略

### OpenAPI 规范版本

OpenAPI 规范版本**独立于** Browser4 核心版本，但遵循语义化版本。

#### 版本对齐策略

```
OpenAPI 规范 v1.x.x → Browser4 核心 v4.x.x
OpenAPI 规范 v2.x.x → Browser4 核心 v5.x.x（未来）
```

#### 基于 URL 的版本控制

主要 API 版本反映在 URL 路径中：

```
/v1/session/{sessionId}/url          # API v1.x
/v2/session/{sessionId}/url          # API v2.x（未来）
```

**当前实现：**
- 根端点（无版本前缀）映射到最新稳定 API（v1.x）
- 这为现有用户提供了平滑的过渡路径

#### 基于请求头的版本控制（可选）

对于高级场景，客户端可以请求特定版本：

```http
Accept: application/json; version=1.0
API-Version: 1.2
```

### API 演化阶段

每个 API 都经过定义的生命周期阶段：

| 阶段 | 描述 | 支持级别 |
|-----|------|---------|
| **实验性** | 早期开发，可能变更 | 无保证 |
| **Beta** | 功能完整，征求反馈 | 可能有破坏性变更 |
| **稳定** | 生产就绪 | 完全支持 |
| **已弃用** | 计划移除 | 仅维护 |
| **已移除** | 不再可用 | N/A |

**OpenAPI 中的阶段指示器：**

```yaml
/session/{sessionId}/experimental/feature:
  post:
    tags:
      - experimental
    x-lifecycle: experimental
    description: |
      ⚠️ 实验性：此端点可能会更改。
```

### 版本文档

每个 OpenAPI 规范版本包括：
- `info.version` 中的版本号
- 规范描述中的变更日志
- 端点的弃用通知
- 破坏性变更的迁移说明

---

## SDK 版本策略

### SDK 版本对齐

SDK 遵循**独立版本**，但保持与特定 API 版本的兼容性：

```
Kotlin SDK v4.5.x → OpenAPI v1.x（Browser4 v4.5.x）
Python SDK v0.2.x → OpenAPI v1.x（Browser4 v4.5.x）
```

### SDK 版本矩阵

| SDK 版本 | OpenAPI 版本 | Browser4 核心 | 最低服务器版本 |
|---------|-------------|--------------|--------------|
| Kotlin 4.5.x | 1.0.x | 4.5.x | 4.5.0 |
| Python 0.2.x | 1.0.x | 4.5.x | 4.5.0 |

### SDK 发布节奏

- **主要 SDK 版本**：与主要 API 版本变更对齐
- **次要 SDK 版本**：新功能、额外的 API 端点支持
- **修订 SDK 版本**：错误修复、性能改进

### SDK 版本规则

1. **主版本号**（X.0.0）：SDK 接口的破坏性 API 变更
2. **次版本号**（x.Y.0）：新功能、新 API 端点支持
3. **修订号**（x.y.Z）：错误修复，无 API 变更

### 功能对等

SDK 力求功能对等，但可能落后于服务器：

```kotlin
// SDK 指示支持的 API 功能
class Browser4Client {
    val supportedApiVersion = "1.0.0"
    val supportedFeatures = setOf("agent", "selectors", "events")
}
```

---

## 向后兼容策略

### 兼容性保证

在同一**主版本**内，我们保证：

✅ **兼容变更**（安全）：
- 添加新的可选参数
- 添加新端点
- 添加新响应字段
- 扩展枚举值（语义上安全）
- 放宽验证规则

❌ **不兼容变更**（破坏性）：
- 移除端点
- 移除请求参数
- 移除响应字段
- 更改字段类型
- 将可选参数变为必需
- 更改错误响应格式

### 版本支持策略

| 版本类型 | 支持期限 | 更新 |
|---------|---------|------|
| **当前主版本** | 直到下一主版本 | 所有更新 |
| **上一主版本** | 12 个月 | 仅关键修复 |
| **旧版本** | 尽力而为 | 仅安全补丁 |

**示例时间表：**

```
v1.0.0 发布：2025 年 1 月
v2.0.0 发布：2026 年 1 月
v1.x 支持至：2027 年 1 月（v2.0.0 后 12 个月）
v3.0.0 发布：2027 年 1 月
v2.x 支持至：2028 年 1 月
v1.x 支持结束
```

### API 稳定性级别

不同的 API 部分可能有不同的稳定性保证：

```yaml
paths:
  /session/{sessionId}/url:
    x-stability: stable
  
  /session/{sessionId}/agent/experimental:
    x-stability: experimental
    x-stability-notice: |
      此端点可能会在不通知的情况下更改。
      不建议在生产环境中使用。
```

---

## 破坏性变更管理

### 识别破坏性变更

在以下情况下，变更被视为**破坏性**：
1. 现有客户端代码停止工作
2. 现有功能行为发生变化
3. 可能发生数据丢失或损坏
4. 安全或身份验证模型变更

### 沟通破坏性变更

所有破坏性变更必须：
1. 在变更日志中用 BREAKING CHANGE 标记**记录**
2. 在发布前至少 3 个月**公告**
3. 在发布说明中**突出显示**
4. 用迁移示例**解释**

**变更日志条目格式：**

```markdown
## [2.0.0] - 2026-01-15

### 破坏性变更

- **[会话 API]** 移除已弃用的 `capabilities.legacy` 字段
  - **迁移**：使用 `capabilities.browserOptions` 代替
  - **影响**：使用 `capabilities.legacy` 的客户端必须更新
  - **示例**：
    ```diff
    - "capabilities": { "legacy": true }
    + "capabilities": { "browserOptions": { "headless": true } }
    ```
```

### 破坏性变更检查清单

在引入破坏性变更之前：

- [ ] 此变更是否绝对必要？
- [ ] 能否使其向后兼容？
- [ ] 是否有替代方法？
- [ ] 是否添加了弃用警告？
- [ ] 迁移路径是否清晰？
- [ ] 示例是否更新？
- [ ] 变更日志是否更新？

### 最小化破坏性变更

**策略：**
1. 尽可能使用**增量变更**
2. 在弃用字段旁引入新字段
3. 提供**适配器层**以实现平滑过渡
4. 使用**特性标志**进行渐进式推出

**示例 - 增量变更：**

```yaml
# 不移除旧字段
AgentRunRequest:
  properties:
    task:
      type: string
      deprecated: true
      description: 使用 'instruction' 代替
    instruction:
      type: string
      description: 任务指令（替换 'task'）
```

---

## 弃用流程

### 弃用时间表

完整的弃用周期至少跨越 **2 个次版本**：

1. **版本 N.x**：功能宣布弃用
2. **版本 N+1.x**：弃用警告激活
3. **版本 N+2.0**：功能移除

**最短时间表**：从弃用到移除 6 个月

### 弃用公告

**OpenAPI 规范：**

```yaml
paths:
  /session/{sessionId}/legacy/action:
    post:
      deprecated: true
      x-deprecation:
        since: "1.5.0"
        removal: "2.0.0"
        alternative: "/session/{sessionId}/agent/act"
      description: |
        ⚠️ 已弃用：此端点自 v1.5.0 起弃用
        并将在 v2.0.0 中移除。
        
        请使用 `/session/{sessionId}/agent/act` 代替。
```

**运行时警告：**

```http
HTTP/1.1 200 OK
Warning: 299 - "端点自 v1.5.0 起弃用，将在 v2.0.0 中移除。使用 /session/{sessionId}/agent/act"
X-Deprecated-Since: 1.5.0
X-Deprecated-Removal: 2.0.0
X-Deprecated-Alternative: /session/{sessionId}/agent/act
```

### 弃用指南

1. **不要在没有弃用的情况下移除**（alpha/beta 除外）
2. **始终提供替代方案**和迁移示例
3. **记录弃用警告**以帮助用户识别使用情况
4. **更新文档**以反映弃用状态
5. 在弃用期间**保持向后兼容**

### SDK 弃用

SDK 方法遵循相同的时间表：

```kotlin
@Deprecated(
    message = "使用 agentAct() 代替",
    replaceWith = ReplaceWith("agentAct(action)"),
    level = DeprecationLevel.WARNING
)
fun legacyAction(action: String): Result {
    logger.warn("legacyAction() 已弃用，使用 agentAct()")
    return agentAct(action)
}
```

---

## 发布管理

### 发布类型

| 类型 | 描述 | 频率 | 示例 |
|-----|------|-----|------|
| **主要** | 破坏性变更 | 每年 | 1.0.0 → 2.0.0 |
| **次要** | 新功能 | 每月 | 1.0.0 → 1.1.0 |
| **修订** | 错误修复 | 按需 | 1.0.0 → 1.0.1 |
| **热修复** | 关键安全 | 紧急 | 1.0.1 → 1.0.2 |

### 发布工作流

#### 1. 规划阶段
- 审查功能提案
- 评估破坏性变更
- 确定版本号
- 建立时间表

#### 2. 开发阶段
- 实现功能
- 编写测试
- 更新文档
- 维护变更日志

#### 3. 预发布阶段
```
1.5.0-alpha.1  → 内部测试
1.5.0-beta.1   → 公开测试
1.5.0-rc.1     → 候选发布
1.5.0          → 稳定版本
```

#### 4. 发布阶段
- 在 Git 中标记版本
- 构建制品
- 更新 OpenAPI 规范版本
- 将 SDK 发布到注册表
- 更新文档站点
- 公告发布

#### 5. 发布后阶段
- 监控问题
- 必要时准备热修复
- 收集反馈
- 规划下一次迭代

### 版本标签格式

```bash
# 核心和 API
v4.5.0              # Browser4 核心发布
api/v1.0.0          # OpenAPI 规范发布

# SDK
sdk/kotlin/v4.5.0   # Kotlin SDK 发布
sdk/python/v0.2.0   # Python SDK 发布
```

### 发布分支

```
main                  # 最新稳定
develop              # 集成分支
release/v1.1.0       # 发布准备
hotfix/v1.0.1        # 紧急修复
```

### 发布检查清单

**发布前：**
- [ ] 所有测试通过
- [ ] 文档已更新
- [ ] 变更日志完整
- [ ] 迁移指南就绪（如有破坏性变更）
- [ ] 版本号已更新
- [ ] 安全扫描通过

**发布：**
- [ ] Git 标签已创建
- [ ] 制品已构建和发布
- [ ] 文档站点已更新
- [ ] 发布说明已发布
- [ ] 公告已发送

**发布后：**
- [ ] 监控错误跟踪
- [ ] 响应反馈
- [ ] 更新路线图

---

## 迁移指南

### 迁移指南模板

对于每个主版本，提供全面的迁移指南：

```markdown
# 迁移指南：v1.x → v2.0

## 概述
- **工作量级别**：中等（典型集成需 2-4 小时）
- **破坏性变更**：5 个方面
- **已弃用功能**：3 个
- **新功能**：10 个

## 破坏性变更

### 1. 身份验证变更
**变更内容**：API 密钥现在需要 `Bearer` 前缀

**之前（v1.x）：**
```http
Authorization: sk-abc123
```

**之后（v2.0）：**
```http
Authorization: Bearer sk-abc123
```

**迁移步骤：**
1. 更新身份验证请求头格式
2. 使用新格式测试
3. 部署变更

### 2. 响应格式变更
...
```

### 迁移工具

提供工具来辅助迁移：

```bash
# CLI 迁移助手
browser4 migrate --from v1.5 --to v2.0 --check
browser4 migrate --from v1.5 --to v2.0 --apply

# SDK 迁移助手
kotlin {
    val migrator = ApiMigrator(from = "1.5.0", to = "2.0.0")
    migrator.analyzeCode("src/")
    migrator.suggestChanges()
}
```

### 版本兼容层

为平滑过渡提供兼容性适配器：

```kotlin
// v1.x 客户端的兼容层
@CompatibilityShim(targetVersion = "2.0.0")
class V1CompatibilityAdapter : RequestAdapter {
    override fun adapt(request: Request): Request {
        // 将 v1 请求转换为 v2 格式
        return request.transformAuthHeader()
                     .transformCapabilities()
    }
}
```

---

## 变更日志规范

### 变更日志格式

遵循 [Keep a Changelog](https://keepachangelog.com/) 格式：

```markdown
# 变更日志

Browser4 OpenAPI 和 SDK 的所有重要变更都将记录在此。

格式基于 [Keep a Changelog](https://keepachangelog.com/)，
本项目遵循[语义化版本](https://semver.org/)。

## [未发布]

### 新增
- 用于批量操作的新 `/session/{sessionId}/agent/batch` 端点

### 变更
- 改进验证失败的错误消息

### 弃用
- AgentRunRequest 中的 `task` 参数（使用 `instruction` 代替）

### 修复
- 修复长时间运行的 agent 操作中的超时处理

## [1.1.0] - 2025-02-15

### 新增
- 通过服务器发送事件（SSE）的事件流
- 批量元素操作
- 增强的选择器策略

### 变更
- 优化截图捕获性能
- 更新默认超时值

### 安全
- 添加速率限制以防止滥用
- 改进 API 密钥验证

## [1.0.0] - 2025-01-15

初始稳定版本。

### 新增
- 完整的 WebDriver 兼容 API
- 选择器优先操作
- AI 驱动的 agent 端点
- PulsarSession 集成
```

### 变更日志条目类别

使用这些标准类别：

- **新增**：新功能
- **变更**：现有功能的变更
- **弃用**：即将移除的功能
- **移除**：已移除的功能
- **修复**：错误修复
- **安全**：安全改进
- **破坏性**：破坏性变更（始终突出显示）

### 链接问题和 PR

```markdown
### 修复
- 修复会话清理竞态条件（[#123](link)）
- 解决 agent 操作中的内存泄漏（[#456](link)）

### 新增
- 新的批量操作端点（[#789](link)）
  实现批量处理的 RFC-001
```

### SDK 特定的变更日志

每个 SDK 维护自己的变更日志：

```
/sdks/kotlin-sdk/CHANGELOG.md
/sdks/python-sdk/CHANGELOG.md
/openapi/CHANGELOG.md
```

---

## 附录：版本历史

### OpenAPI 演化

| 版本 | 发布日期 | Browser4 版本 | 说明 |
|-----|---------|--------------|-----|
| 1.0.0 | 2025 年 1 月 | 4.5.0 | 初始稳定版本 |
| 1.1.0 | 2025 年 2 月 | 4.6.0 | 添加事件流 |
| 2.0.0 | 2026 年 1 月 | 5.0.0 | 主要修订（计划中）|

### SDK 演化

#### Kotlin SDK

| 版本 | 发布日期 | OpenAPI 版本 | 说明 |
|-----|---------|-------------|-----|
| 4.5.0 | 2025 年 1 月 | 1.0.0 | 初始版本 |
| 4.6.0 | 2025 年 2 月 | 1.1.0 | 事件流支持 |

#### Python SDK

| 版本 | 发布日期 | OpenAPI 版本 | 说明 |
|-----|---------|-------------|-----|
| 0.1.0 | 2025 年 1 月 | 1.0.0 | Beta 版本 |
| 0.2.0 | 2025 年 2 月 | 1.1.0 | 添加事件流 |
| 1.0.0 | 2025 年 3 月 | 1.1.0 | 稳定版本 |

---

## 参考资料

- [语义化版本 2.0.0](https://semver.org/lang/zh-CN/)
- [Keep a Changelog](https://keepachangelog.com/zh-CN/)
- [OpenAPI 规范](https://spec.openapis.org/)
- [API 演化最佳实践](https://opensource.zalando.com/restful-api-guidelines/)
- [W3C WebDriver 规范](https://w3c.github.io/webdriver/)

---

**文档版本**：1.0.0  
**最后更新**：2025-01-20  
**维护者**：Browser4 团队  
**问题**：在 https://github.com/platonai/browser4/issues 创建问题
