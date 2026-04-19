#!/usr/bin/env pwsh

Write-Host "Configuring Git to use proxy"

git config --global http.proxy http://127.0.0.1:10808
git config --global https.proxy http://127.0.0.1:10808

#git config --global --unset-all http.proxy
#git config --global --unset-all https.proxy
