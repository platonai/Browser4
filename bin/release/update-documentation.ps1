#!/usr/bin/env pwsh

# 🔍 查找包含 VERSION 文件的第一个父目录
$ScriptPath = $MyInvocation.MyCommand.Path
$APP_HOME = Split-Path -Parent (Resolve-Path $ScriptPath)
while (!(Test-Path "$APP_HOME/VERSION") -and ($APP_HOME -ne "/")) {
    $APP_HOME = Split-Path -Parent $APP_HOME
}
Set-Location $APP_HOME

Write-Host "🔄 Updating Browser4 documentation..."
Write-Host "📅 Current Date: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss UTC')"
Write-Host "👤 User: $env:USERNAME"

# 检查 VERSION 文件是否存在
if (!(Test-Path "$APP_HOME/VERSION")) {
    Write-Host "❌ Error: VERSION file not found in $APP_HOME"
    exit 1
}

$SNAPSHOT_VERSION = (Get-Content "$APP_HOME/VERSION" -First 1).Trim()
$VERSION = $SNAPSHOT_VERSION -replace '-SNAPSHOT', ''
$PREFIX = ($VERSION -split '\.')[0..1] -join '.'

Write-Host "📦 Version Info:"
Write-Host "   Snapshot: $SNAPSHOT_VERSION"
Write-Host "   Release:  $VERSION"
Write-Host "   Prefix:   $PREFIX"

# 包含版本号的文件
$VERSION_AWARE_FILES = @(
    "$APP_HOME/README.md",
    "$APP_HOME/README.zh.md"
)

Write-Host "🔍 Processing files..."
$UPDATED_FILES = @()
$RELATIVE_FILES = @()

foreach ($F in $VERSION_AWARE_FILES) {
    if (Test-Path $F) {
        Write-Host "  📄 Processing: $(Split-Path $F -Leaf)"
        # 备份原文件
        Copy-Item $F "$F.backup"

        # 替换 SNAPSHOT 版本（精确匹配）
        (Get-Content $F) -replace "\b$SNAPSHOT_VERSION\b", $VERSION |
            Set-Content $F

        # 查找同前缀但不同补丁的旧版本号
        $OLD_VERSIONS = Select-String -Path $F -Pattern "v?$PREFIX\.[0-9]+" -AllMatches | ForEach-Object {
            $_.Matches.Value
        } | Sort-Object -Unique

        foreach ($OLD_VERSION in $OLD_VERSIONS) {
            if (($OLD_VERSION -ne $VERSION) -and ($OLD_VERSION -ne "v$VERSION")) {
                Write-Host "    🔄 Replacing $OLD_VERSION → v$VERSION"
                (Get-Content $F) -replace "\b$OLD_VERSION\b", "v$VERSION" |
                    Set-Content $F
            }
        }

        # 检查文件是否被修改
        if (-not (Compare-Object (Get-Content $F) (Get-Content "$F.backup") -SyncWindow 0)) {
            # 没有变化
        } else {
            $UPDATED_FILES += $F
            # 计算相对路径用于git add命令
            $RELATIVE_PATH = (Resolve-Path -Relative $F)
            $RELATIVE_FILES += $RELATIVE_PATH
        }
        # 删除备份
        Remove-Item "$F.backup"
    } else {
        Write-Host "  ⚠️  File not found: $F"
    }
}

if ($UPDATED_FILES.Count -eq 0) {
    Write-Host "ℹ️  No files were updated."
    exit 0
}

Write-Host "✅ Documentation updated with version v$VERSION"
Write-Host "📝 Modified files:"
foreach ($file in $UPDATED_FILES) {
    Write-Host "   - $(Split-Path $file -Leaf)"
}

# 针对Windows和Unix环境准备不同格式的文件路径
$GIT_FILES = @()
foreach ($file in $UPDATED_FILES) {
    $relativePath = $file.Replace("$APP_HOME\", "").Replace("$APP_HOME/", "")
    # 统一使用正斜杠，这在Windows和Unix环境都有效
    $gitPath = $relativePath.Replace("\", "/")
    $GIT_FILES += $gitPath
}

Write-Host ""
Write-Host "🔍 Please review the changes before committing:"
Write-Host "   git diff"
Write-Host ""
Write-Host "📤 To commit and push changes:"
Write-Host "   git add $($GIT_FILES -join ' ')"
Write-Host "   git commit -m 'docs: update documentation for version v$VERSION'"
Write-Host "   git push origin master"

# 正确执行git add命令，每个文件单独添加以避免路径问题
foreach ($file in $GIT_FILES) {
    git add $file
}
git commit -m "docs: update documentation for version v$VERSION"
git push
