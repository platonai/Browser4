# script 工件对照

## 真实实现：仓库内脚本族

- `cli/browser4-cli/` 下安装/卸载/升级脚本（daemon 相关，含平台分支 `$IsWindows/$IsLinux/$IsMacOS`）
- `bin/test.ps1`（测试范围选择）、`b4w.ps1`（CLI 包装，自动按需构建）

## 脚手架输出：generated/build.ps1

由 `coding.scaffold(type="script", name="build", scriptType="build", shell="ps1")` 生成：

```powershell
<#
.SYNOPSIS
    build — build script
.DESCRIPTION
    Build script for build.
.PARAMETER Verbose
    ...
#>
[CmdletBinding()]
param()

# TODO: implement the build steps
```

`scriptType` 决定语义：`build` / `deploy` / `run`；`shell` 决定形态：`ps1` / `bash`。

## 校验

- `coding.validate(type="script", path=...)` — param 块、shebang、错误处理（`$ErrorActionPreference` 等）
- 语法检查：`coding.shell(command="powershell -File <script>")` 或 `bash -n <script>`

## 插件 build.ps1 参考

插件脚手架自带的 `build.ps1`（P1 轮次加入）承担更重的职责：`mvn package` + `jar tf` 校验
manifest/imports/JS/classes 完整性 + `-DeployDir` 拷贝或 `-RestInstall` 部署——见插件脚手架 10 文件输出。
