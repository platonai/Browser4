@{
    # ═══════════════════════════════════════════════════════════════
    # Maintenance Thresholds Configuration
    # ═══════════════════════════════════════════════════════════════
    #
    # All numeric thresholds live here. Override via environment variables:
    #   $env:MAINTENANCE_<Section>_<Key> = "<value>"
    #
    # Execution mode (ci|nightly|dev) controls failure behavior:
    #   ci      → strict: any failure is exit code 1
    #   nightly → relaxed: collect failures, report at end
    #   dev     → warn only: never exit non-zero
    # ═══════════════════════════════════════════════════════════════

    # ── Code Coverage Thresholds (from AGENTS.md) ──
    Coverage = @{
        Global      = 0.70    # Overall project ≥ 70%
        Core        = 0.80    # browser4-core ≥ 80%
        Utilities   = 0.90    # Utility classes ≥ 90%
        Controllers = 0.85    # REST controllers ≥ 85%
        FailBelow   = $true   # Fail when below threshold
    }

    # ── Qodana Quality Gate (mirrors qodana.yaml) ──
    Qodana = @{
        MaxAnySeverity      = 15   # Maximum problems of any severity
        MaxCriticalSeverity = 5    # Maximum critical problems
        ExcludePatterns     = @('**/target/**', '**/generated/**')
    }

    # ── Documentation Thresholds ──
    Documentation = @{
        MaxBrokenInternalLinks  = 0     # Zero tolerance for broken internal links
        MaxBrokenExternalLinks  = 0     # Zero tolerance for broken external links
        ReadmeStalenessThreshold = 40   # Score >= 40 means README is stale
        SkillMaxDescriptionChars = 200  # SKILL.md description max 200 chars
        SkillMinWords            = 50   # SKILL.md minimum word count
        BilingualMinAlignment    = 0.80 # README.md ↔ README.zh.md section alignment ≥ 80%
    }

    # ── Test Health Thresholds ──
    TestHealth = @{
        MaxFastDurationSeconds = 300    # Fast tests must complete < 5 min
        MaxSlowDurationSeconds = 1800   # Slow tests must complete < 30 min
        MinPassRate            = 0.95   # Minimum test pass rate 95%
        AllowKnownFlakes       = $true  # Skip known flaky tests
        KnownFlakePatterns     = @(
            '*Flaky*',
            '*Unstable*',
            '*Timeout*'
        )
    }

    # ── Dependency Thresholds ──
    Dependencies = @{
        MaxCriticalVulnerabilities = 0    # Zero critical CVEs acceptable
        MaxHighVulnerabilities     = 2    # Max 2 high-severity CVEs (planned fixes)
        BannedLicenses             = @(
            'GPL-2.0-only',
            'GPL-3.0-only',
            'AGPL-1.0-only',
            'AGPL-3.0-only',
            'SSPL-1.0'
        )
        ApprovedLicenses           = @(
            'Apache-2.0',
            'MIT',
            'BSD-2-Clause',
            'BSD-3-Clause',
            'ISC',
            'MPL-2.0',
            'LGPL-2.1-only',
            'LGPL-3.0-only',
            'Unlicense',
            'CC0-1.0'
        )
    }

    # ── Log and Disk Thresholds ──
    LogHealth = @{
        MaxTotalMB             = 500   # Max total log directory size in MB
        MaxFileMB              = 50    # Max single log file size in MB
        RetentionDays          = 14    # Log retention period in days
        TempFileMaxAgeDays     = 7     # Max age of temp files before cleanup
        BuildArtifactMaxAgeDays = 3    # Max age of build artifacts before cleanup
    }

    # ── Performance Thresholds ──
    Performance = @{
        MaxCompilationMinutes  = 15    # Maven compilation timeout
        MaxTestMinutes         = 35    # Test suite timeout
        MaxDockerBuildMinutes  = 25    # Docker build timeout
        MaxHealthCheckSeconds  = 300   # Docker health check timeout
    }

    # ── Deprecated API Thresholds ──
    CodeQuality = @{
        MaxDeprecatedUsages     = 50   # Maximum deprecated API usages
        MaxDeadCodeWarnings     = 100  # Maximum dead code / unused import warnings
        MaxCompilerWarnings     = 200  # Maximum total compiler warnings
    }

    # ── AI Quality Thresholds ──
    AIQuality = @{
        MinClarityScore          = 7    # AI instruction clarity (1-10)
        MinActionabilityScore    = 6    # AI instruction actionability (1-10)
        MaxAmbiguityWords        = 3    # Max "maybe"/"could"/"might" per SKILL.md
        RequireUseWhenPattern    = $true # Description must contain "Use when..."
        RequireErrorHandling     = $true # Must document error cases
    }
}
