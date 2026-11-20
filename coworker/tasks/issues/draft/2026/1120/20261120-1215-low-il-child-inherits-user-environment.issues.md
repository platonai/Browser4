# Issues: `windows-low-il` 子进程继承用户**持久**环境，把 API key 交给沙箱（permission-e2e A10 实测）

> **Source:** 2026-11-20 12:08 本机实测（live backend `windows-low-il`，rebuild 后，`permission-e2e` A10）
> **Date:** 20261120-1215 · **Mode:** dev
> **状态:** **已修复 2026-11-20 12:12**
> **代码基线:** `4.16.x` @ `6df6fd520e`
> **报告人:** 本会话 agent（实现 `permission-e2e` A10 时发现）

---

## 0. 结论摘要

| 编号 | 问题 | 是产品缺陷吗 | 置信度 | 建议决策 |
|---|---|---|---|---|
| I1 | `WindowsTokenLauncher.mergedEnvironment` 以 **token 环境块**（`CreateEnvironmentBlock`，即该账户的持久环境）为基底，只对"声明变量"做白名单 | 是（凭据泄漏） | 高 | ACCEPT |
| I2 | 结果：`coding.shell` 执行 `cmd /c echo %DEEPSEEK_API_KEY%` 打印出**真实密钥**（`sk-…`），而进程内路径（`SandboxEnvironment.build`）会把它剥掉 | 是（与文档契约相反） | 高 | ACCEPT |
| I3 | 影响面：任何用 `setx`/系统属性持久化的 `*_API_KEY`、token、密码都会进入 low-il 子进程；`ENV_PASSTHROUGH` 审计事件也不会记（因为没有显式传入） | 是 | 高 | ACCEPT |

**一句话**：沙箱把"这个账户的环境"当成了"这个任务的环境"，于是同机上任何持久化密钥都成了 agent 可读的环境变量。

---

## 1. 复现（修复前）

```powershell
# backend: -Dbrowser4.agent.sandbox=windows-low-il
browser4-cli tool call coding_shell --json '{"command":"cmd /c echo INHERITED=[%DEEPSEEK_API_KEY%]"}'
# 实测输出（2026-11-20 12:08）：
#   ✓ devshell-11 · SUCCESS · exit 0
#   INHERITED=[sk-bc54…]        ← 真实密钥
```

同一台机器上，启动 CLI 的 shell **没有** `DEEPSEEK_API_KEY`（`Get-ChildItem env:` 为 0 条），后端是从
`application-private.properties` 读取密钥的——说明泄漏源不是启动环境，而是**账户的持久环境块**。

---

## 2. 根因

`browser4-coding/.../sandbox/WindowsTokenLauncher.kt`：

```kotlin
private fun mergedEnvironment(token: Pointer, declared: Map<String, String>): Map<String, String> {
    if (Win32.userenv.CreateEnvironmentBlock(block, token, false)) {
        return mergeEnvironment(readEnvironmentBlock(pointer), declared)   // ← 整个 token 环境作为基底
    }
    return declared
}

internal fun mergeEnvironment(tokenEnvironment: Map<String, String>, declared: Map<String, String>): Map<String, String> {
    val merged = LinkedHashMap(tokenEnvironment)     // ← 所有变量都进来，只对 TOKEN_OWNED 做特例
    ...
}
```

`TOKEN_OWNED_VARIABLES` 只描述"声明值不该覆盖 token 值"（`TEMP`/`USERPROFILE`/`PATH`…），并不是白名单。
因此 low-il 子进程拿到的是**用户完整环境 + 声明变量**，与 `SandboxEnvironment.build`（非沙箱路径）的
白名单契约不一致：那条路径只放行 toolchain/OS 变量，并对显式传入的 secret 记 `ENV_PASSTHROUGH`。

---

## 3. 修复

`mergeEnvironment` 先用白名单过滤 token 环境块，再叠加声明值：

```kotlin
for ((name, value) in tokenEnvironment) {
    if (name.uppercase() in TOKEN_OWNED_VARIABLES || SandboxEnvironment.isWhitelisted(name)) {
        merged[name] = value
    }
}
```

- 保留：账户身份与 OS plumbing（`USERPROFILE`/`TEMP`/`APPDATA`…）、toolchain（`PATH`/`JAVA_HOME`/`M2_HOME`…）；
- 丢弃：其余一切（`*_API_KEY`、token、`AWS_PROFILE` 之类）；
- 声明变量（`coding.shell_set_env` 等）仍然生效，secret 形状的仍记 `ENV_PASSTHROUGH`（既有行为）。

---

## 4. 测试

| 测试 | 守住什么 |
|---|---|
| `WindowsTokenLaunchTest.mergeStripsUserSecrets`（新） | token 环境里的 `DEEPSEEK_API_KEY` / `OPENAI_API_KEY` / 任意未列入白名单的变量**不会**进入子进程；`USERPROFILE`/`TEMP`/`JAVA_HOME`/`PATH` 仍然保留 |
| `WindowsTokenLaunchTest.mergeKeepsTokenIdentity`（既有） | token 拥有的变量不被声明值覆盖 |
| `permission-e2e` `A10-secret-not-inherited` | 端到端：`INHERITED=[%DEEPSEEK_API_KEY%]`（未定义即保留字面量）、显式 set-env 可见 |

修复后的端到端实测（2026-11-20 12:09，重建后端）：
`A05/A06/A10/A17` 全 **PASS**，`A10` 的继承值回到字面量 `%DEEPSEEK_API_KEY%`。

---

## 5. 关联

- 设计/实测：`docs-dev/copilot/20261120-permission-sandbox-e2e-plan.md` §12.2
- 同批：`20261120-1200`（junction 目标成为可写根）、`20261120-0145`、`20261120-0146`、`20261120-0156`
- 契约文档：`docs/agent-permissions.md` §8（环境白名单 / `ENV_PASSTHROUGH`）
