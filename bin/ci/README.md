# CI/CD Scripts 🚀

This directory contains automation scripts for Continuous Integration and Continuous Deployment workflows.

## 📋 Scripts Overview

### 🔄 Local CI/CD Scripts

#### `ci-local.ps1`
**Continuous Integration Monitor**

Automatically monitors Git repository for changes and triggers builds when updates are detected.

**Features:**
- ⏱️ Configurable polling interval (default: 60 seconds)
- 🔍 Auto-detects repository root using VERSION file
- 🔨 Triggers build script on new commits
- 📊 Real-time status logging

**Usage:**
```powershell
# Default 60-second interval
./ci-local.ps1

# Custom interval (e.g., 30 seconds)
./ci-local.ps1 -intervalSeconds 30
```

#### `ci-docker-local.ps1`
**Docker-based Local CI**

Runs CI/CD pipeline in Docker containers for consistent environment testing.

### 🏷️ Tag Management Scripts

#### `ci-tag-add.ps1`
**GitHub Actions Trigger**

Creates CI tags to trigger GitHub Actions workflows automatically.

**Features:**
- 🎯 Smart version detection from VERSION file
- 🔢 Auto-incrementing CI tag numbers (e.g., `v3.0.10-ci.1`, `v3.0.10-ci.2`)
- 🌐 Configurable remote repository
- ✅ Duplicate tag prevention

**Usage:**
```powershell
# Add CI tag to origin
./ci-tag-add.ps1

# Add CI tag to specific remote
./ci-tag-add.ps1 -remote upstream
```

**Example Output:**
- Version from VERSION file: `3.0.10-SNAPSHOT`
- Generated tag: `v3.0.10-ci.1`
- Triggers: GitHub Actions CI/CD pipeline

#### `ci-tags-rm.ps1`
**Tag Cleanup**

Removes CI tags to keep repository clean and organized.

**Features:**
- 🧹 Removes all CI tags matching pattern `v*-ci.*`
- 🌐 Works with multiple remotes
- ⚠️ Safety confirmation prompts

**Usage:**
```powershell
# Remove all CI tags
./ci-tags-rm.ps1
```

## 📚 Additional Resources

### Documentation Files
- `ci.md` - Comprehensive CI/CD and GitHub Actions guide
- `ci-local.md` - Detailed local CI setup instructions

### Quick Start Guide

1. **Set up local CI monitoring:**
   ```powershell
   cd bin/ci
   ./ci-local.ps1
   ```

2. **Trigger GitHub Actions CI:**
   ```powershell
   ./ci-tag-add.ps1
   ```

3. **Clean up after testing:**
   ```powershell
   ./ci-tags-rm.ps1
   ```

## 🛠️ Prerequisites

- **PowerShell** (for .ps1 scripts)
- **Bash** (for .sh scripts)
- **Git** repository with remote configured
- **VERSION** file in project root
- **GitHub repository** with Actions enabled (for tag triggers)

## 🔧 Configuration

All scripts automatically detect the project root by locating the `VERSION` file. No manual configuration required for basic usage.

## 📖 How It Works

1. **Local CI**: Monitors repository changes and builds automatically
2. **Tag Management**: Creates versioned tags that trigger cloud CI/CD
3. **Docker Integration**: Provides containerized testing environment
4. **GitHub Actions**: Automated testing, building, and deployment

## 🚨 Important Notes

- CI tags are temporary and should be cleaned up after testing
- Local CI scripts will run indefinitely until manually stopped (Ctrl+C)
- Always test changes locally before triggering cloud CI/CD
- Ensure your GitHub repository has proper Actions workflows configured
