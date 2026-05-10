#!/usr/bin/env pwsh

# 1) 清理旧的 jpackage 输出（否则 jpackage 会报“目标目录已存在”）
if (Test-Path "browser4\browser4-agents\target\jpackage") {
    Remove-Item -Recurse -Force "browser4\browser4-agents\target\jpackage"
}

# 2) 重新打 app-image
.\mvnw.cmd -pl browser4\browser4-agents -am -Pwin-jpackage -D"skipTests" package

# 3) 运行（双击也行）
& "browser4\browser4-agents\target\jpackage\app-image\Browser4\Browser4.exe"
