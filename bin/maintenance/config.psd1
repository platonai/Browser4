@{
    # ═══════════════════════════════════════════════════════════════
    # Maintenance Scheduler Configuration
    # ═══════════════════════════════════════════════════════════════
    #
    # Defines all maintenance tasks, their intervals, and scheduling
    # behavior. The orchestrator reads this config and manages
    # execution.
    #
    # Task fields:
    #   Name            — Unique task identifier
    #   Description     — Human-readable description
    #   Enabled         — Whether this task is active
    #   IntervalSeconds — How often to run (seconds)
    #   ScriptPath      — Path to the check script (relative to repo root)
    #   Arguments       — Array of arguments to pass to the script
    #   DependsOn       — Array of task Names that must pass first
    # ==================================================================
    # Frequency guide:
    #   300    = 5 minutes (per-commit / CI)
    #   3600   = 1 hour
    #   86400  = 24 hours (nightly)
    #   604800 = 7 days (weekly)
    # ═══════════════════════════════════════════════════════════════

    Scheduler = @{
        TickSeconds          = 10
        PowerShellExecutable = 'pwsh'
        WorkingDirectory     = '../..'
        LogDirectory         = 'bin/maintenance/logs'
        StatusFile           = 'bin/maintenance/state/maintenance-state.json'
    }

    Tasks = @(
        # ═══════════════════════════════════════════════════════════
        # CI-Level Checks (fast, per-commit safety)
        # ═══════════════════════════════════════════════════════════
        @{
            Name            = 'check-compilation'
            Description     = 'Verify Maven + Cargo compilation'
            Enabled         = $true
            IntervalSeconds = 300
            ScriptPath      = 'bin/maintenance/checks/check-compilation.ps1'
            Arguments       = @()
        }
        @{
            Name            = 'check-fast-tests'
            Description     = 'Run fast JUnit unit tests'
            Enabled         = $true
            IntervalSeconds = 600
            DependsOn       = @('check-compilation')
            ScriptPath      = 'bin/maintenance/checks/check-fast-tests.ps1'
            Arguments       = @()
        }
        @{
            Name            = 'check-rust-cli'
            Description     = 'Run cargo test + cargo clippy'
            Enabled         = $true
            IntervalSeconds = 3600
            ScriptPath      = 'bin/maintenance/checks/check-rust-cli.ps1'
            Arguments       = @()
        }
        @{
            Name            = 'check-doc-links-internal'
            Description     = 'Validate internal documentation links'
            Enabled         = $true
            IntervalSeconds = 3600
            ScriptPath      = 'bin/maintenance/checks/check-doc-links-internal.ps1'
            Arguments       = @()
        }
        @{
            Name            = 'check-skill-frontmatter'
            Description     = 'Validate SKILL.md YAML frontmatter'
            Enabled         = $true
            IntervalSeconds = 3600
            ScriptPath      = 'bin/maintenance/checks/check-skill-frontmatter.ps1'
            Arguments       = @()
        }
        @{
            Name            = 'check-version-consistency'
            Description     = 'Verify version alignment across files'
            Enabled         = $true
            IntervalSeconds = 3600
            ScriptPath      = 'bin/maintenance/checks/check-version-consistency.ps1'
            Arguments       = @()
        }
        @{
            Name            = 'check-ps1-syntax'
            Description     = 'Parse all PS1 scripts for syntax errors'
            Enabled         = $true
            IntervalSeconds = 3600
            ScriptPath      = 'bin/maintenance/checks/check-ps1-syntax.ps1'
            Arguments       = @()
        }
        @{
            Name            = 'check-dockerfile'
            Description     = 'Verify Docker images build cleanly'
            Enabled         = $true
            IntervalSeconds = 3600
            ScriptPath      = 'bin/maintenance/checks/check-dockerfile.ps1'
            Arguments       = @()
        }

        # ═══════════════════════════════════════════════════════════
        # Nightly Checks (thorough, runs daily)
        # ═══════════════════════════════════════════════════════════
        @{
            Name            = 'check-coverage'
            Description     = 'Verify code coverage against thresholds'
            Enabled         = $true
            IntervalSeconds = 86400
            DependsOn       = @('check-compilation')
            ScriptPath      = 'bin/maintenance/checks/check-coverage.ps1'
            Arguments       = @()
        }
        @{
            Name            = 'check-test-tags'
            Description     = 'Audit JUnit test tag taxonomy'
            Enabled         = $true
            IntervalSeconds = 86400
            ScriptPath      = 'bin/maintenance/checks/check-test-tags.ps1'
            Arguments       = @()
        }
        @{
            Name            = 'check-skill-structure'
            Description     = 'Validate SKILL.md section structure'
            Enabled         = $true
            IntervalSeconds = 86400
            ScriptPath      = 'bin/maintenance/checks/check-skill-structure.ps1'
            Arguments       = @()
        }
        @{
            Name            = 'check-dependency-vulns'
            Description     = 'Scan dependencies for CVE vulnerabilities'
            Enabled         = $true
            IntervalSeconds = 86400
            DependsOn       = @('check-compilation')
            ScriptPath      = 'bin/maintenance/checks/check-dependency-vulns.ps1'
            Arguments       = @()
        }
        @{
            Name            = 'check-maven-deps'
            Description     = 'Verify Maven dependency tree convergence'
            Enabled         = $true
            IntervalSeconds = 86400
            ScriptPath      = 'bin/maintenance/checks/check-maven-deps.ps1'
            Arguments       = @()
        }
        @{
            Name            = 'check-cargo-audit'
            Description     = 'Run cargo audit for Rust vulns'
            Enabled         = $true
            IntervalSeconds = 86400
            ScriptPath      = 'bin/maintenance/checks/check-cargo-audit.ps1'
            Arguments       = @()
        }
        @{
            Name            = 'check-doc-links-external'
            Description     = 'Validate external URLs in documentation'
            Enabled         = $true
            IntervalSeconds = 86400
            ScriptPath      = 'bin/maintenance/checks/check-doc-links-external.ps1'
            Arguments       = @()
        }
        @{
            Name            = 'check-bilingual-readme'
            Description     = 'Check README.md ↔ README.zh.md consistency'
            Enabled         = $true
            IntervalSeconds = 86400
            ScriptPath      = 'bin/maintenance/checks/check-bilingual-readme.ps1'
            Arguments       = @()
        }
        @{
            Name            = 'check-log-sizes'
            Description     = 'Audit log directory sizes'
            Enabled         = $true
            IntervalSeconds = 86400
            ScriptPath      = 'bin/maintenance/checks/check-log-sizes.ps1'
            Arguments       = @()
        }

        # ═══════════════════════════════════════════════════════════
        # Weekly Checks (deep analysis, cleanup)
        # ═══════════════════════════════════════════════════════════
        @{
            Name            = 'check-deprecated-apis'
            Description     = 'Detect deprecated API usage'
            Enabled         = $true
            IntervalSeconds = 604800
            ScriptPath      = 'bin/maintenance/checks/check-deprecated-apis.ps1'
            Arguments       = @()
        }
        @{
            Name            = 'check-dead-code'
            Description     = 'Detect dead code and unused imports'
            Enabled         = $true
            IntervalSeconds = 604800
            ScriptPath      = 'bin/maintenance/checks/check-dead-code.ps1'
            Arguments       = @()
        }
        @{
            Name            = 'check-skill-ai-quality'
            Description     = 'Assess SKILL.md AI instruction quality'
            Enabled         = $true
            IntervalSeconds = 604800
            ScriptPath      = 'bin/maintenance/checks/check-skill-ai-quality.ps1'
            Arguments       = @()
        }
        @{
            Name            = 'check-license-compliance'
            Description     = 'Verify dependency license compatibility'
            Enabled         = $true
            IntervalSeconds = 604800
            ScriptPath      = 'bin/maintenance/checks/check-license-compliance.ps1'
            Arguments       = @()
        }
        @{
            Name            = 'clean-build-artifacts'
            Description     = 'Remove stale build artifacts'
            Enabled         = $true
            IntervalSeconds = 604800
            ScriptPath      = 'bin/maintenance/checks/clean-build-artifacts.ps1'
            Arguments       = @()
        }
        @{
            Name            = 'clean-temp-files'
            Description     = 'Remove stale temp and lock files'
            Enabled         = $true
            IntervalSeconds = 604800
            ScriptPath      = 'bin/maintenance/checks/clean-temp-files.ps1'
            Arguments       = @()
        }
    )
}
