#!/bin/bash

script_dir=$(cd "$(dirname "$0")" > /dev/null || exit 1; pwd)
repo_root=$(git -C "$script_dir" rev-parse --show-toplevel 2>/dev/null)

if [[ -z "$repo_root" ]]; then
  repo_root="$script_dir"
  while [[ ! -f "$repo_root/VERSION" && "$repo_root" != "/" ]]; do
    repo_root=$(dirname "$repo_root")
  done
fi

if [[ ! -f "$repo_root/VERSION" ]]; then
  echo "Error: Could not locate the repository root from $script_dir" >&2
  exit 1
fi

cd "$repo_root" || exit 1

print_usage() {
  echo "Usage: test.sh [--dry-run] [--show] [test-types...] [additional-args...]"
  echo ""
  echo "Options:"
  echo "  --dry-run   Compile only (test-compile), do not run tests"
  echo "  --show      Print the final Maven command, do not execute anything"
  echo ""
  echo "Test Types:"
  echo "  fast        Run fast unit tests only"
  echo "  it          Run integration tests"
  echo "  e2e         Run end-to-end tests"
  echo "  cli         Run Rust Browser4 CLI tests from cli/browser4-cli"
  echo "  mock-site   Launch mock site from browser4-tests\browser4-rest-tests"
  echo "  rest        Run REST module tests"
  echo "  skills      Run skills-focused agentic tests"
  echo "  mcp         Run MCP-focused agentic tests"
  echo "  resume      Resume from the last failed module (-rf)"
  echo "  browser4    Run all Browser4 main tests (fast, rest, it, e2e)"
  echo "  b4          Alias for browser4"
  echo ""
  echo "Examples:"
  echo "  test.sh fast                       # Run fast unit tests"
  echo "  test.sh --dry-run fast             # Show the Maven command for fast tests"
  echo "  test.sh --dry-run it -pl browser4-core  # Show the Maven command with extra args"
  echo "  test.sh it                         # Run integration tests"
  echo "  test.sh e2e                        # Run end-to-end tests"
  echo "  test.sh cli                        # Run Browser4 CLI tests"
  echo "  test.sh cli -- --nocapture         # Pass extra cargo test args"
  echo "  test.sh mock-site -Dmock.site.port=18080"
  echo "  test.sh skills                     # Run skills-focused agentic tests"
  echo "  test.sh mcp                        # Run MCP-focused agentic tests"
  echo "  test.sh resume                     # Resume from the last failed module"
  echo "  test.sh browser4                   # Run all Browser4 main tests"
  echo "  test.sh b4                         # Alias for browser4"
  echo "  test.sh it -pl browser4-core       # Pass additional Maven args through"
  exit 1
}

exit_unknown_test_type() {
  local test_type=$1
  echo "Error: Unknown test type '$test_type'. Valid test types: fast, it, e2e, cli, mock-site, rest, skills, mcp, resume, browser4, b4 (aliases: mocksite, mocksiteboot)." >&2
  exit 1
}

run_maven_tests() {
  local -a test_types=("$@")
  local goal="test"
  [[ "$DRY_RUN" == "true" && "$SHOW" != "true" ]] && goal="test-compile"
  local -a mvn_test_args=("$goal" "-P=-examples")
  local -a modules=()
  local -a test_patterns=()
  local joined_modules=""
  local joined_patterns=""
  local has_fast=false
  local has_it=false
  local has_e2e=false
  local has_rest=false
  local has_skills=false
  local has_mcp=false

  echo "=========================================="
  echo "Running Maven tests: ${test_types[*]}"
  echo "=========================================="

  for type in "${test_types[@]}"; do
    case "$type" in
      fast) has_fast=true ;;
      it) has_it=true ;;
      e2e) has_e2e=true ;;
      rest) has_rest=true ;;
      skills) has_skills=true ;;
      mcp) has_mcp=true ;;
    esac
  done

  [[ "$has_it" == "true" ]] && mvn_test_args+=("-DrunITs=true")
  [[ "$has_e2e" == "true" ]] && mvn_test_args+=("-DrunE2ETests=true")
  [[ "$has_rest" == "true" ]] && mvn_test_args+=("-DrunRestTests=true")
  if [[ "$has_skills" == "true" || "$has_mcp" == "true" ]]; then
    modules+=("browser4-agentic")

    if [[ "$has_fast" == "false" && "$has_it" == "false" && "$has_e2e" == "false" && "$has_rest" == "false" ]]; then
      [[ "$has_skills" == "true" ]] && test_patterns+=("*Skill*")
      [[ "$has_mcp" == "true" ]] && test_patterns+=("*MCP*")

      if [[ ${#test_patterns[@]} -gt 0 ]]; then
        joined_patterns=$(IFS=, ; echo "${test_patterns[*]}")
        mvn_test_args+=("-Dtest=$joined_patterns" "-Dsurefire.failIfNoSpecifiedTests=false")
      fi
    fi
  fi

  if [[ "$has_fast" == "true" || "$has_rest" == "true" ]]; then
    modules=()
  fi

  if [[ ${#modules[@]} -gt 0 ]]; then
    joined_modules=$(IFS=, ; echo "${modules[*]}")
    mvn_test_args+=("-pl" "$joined_modules" "-am")
  fi

  mvn_test_args+=("${AdditionalMvnArgs[@]}")

  if [[ "$SHOW" == "true" ]]; then
    echo ""
    echo "=========================================="
    echo "[SHOW] Would execute:"
    echo "  ./mvnw ${mvn_test_args[*]}"
    echo "=========================================="
    return
  fi

  if [[ "$DRY_RUN" == "true" ]]; then
    echo ""
    echo "=========================================="
    echo "[DRY RUN] Executing:"
    echo "  ./mvnw ${mvn_test_args[*]}"
    echo "=========================================="
  fi

  ./mvnw "${mvn_test_args[@]}"
  local exit_code=$?
  if [[ $exit_code -ne 0 ]]; then
    echo ""
    echo "=========================================="
    echo "❌ Maven tests failed with exit code $exit_code"
    echo "=========================================="
    exit $exit_code
  fi

  echo ""
  echo "=========================================="
  echo "✅ Maven tests completed successfully"
  echo "=========================================="
}

run_browser4_cli_tests() {
  local browser4_cli_dir="$repo_root/cli/browser4-cli"

  echo "=========================================="
  echo "Running Browser4 CLI tests..."
  echo "=========================================="

  if [[ ! -d "$browser4_cli_dir" ]]; then
    echo "Error: Browser4 CLI directory not found at $browser4_cli_dir" >&2
    exit 1
  fi

  if ! command -v cargo >/dev/null 2>&1; then
    echo "Error: cargo is not installed or not in PATH" >&2
    exit 1
  fi

  pushd "$browser4_cli_dir" > /dev/null || exit 1
  echo "Working directory: $(pwd)"

  if [[ ! -f "$browser4_cli_dir/Cargo.toml" ]]; then
    echo "Error: Cargo.toml not found in $browser4_cli_dir" >&2
    popd > /dev/null || true
    exit 1
  fi

  if [[ "$SHOW" == "true" ]]; then
    echo ""
    echo "=========================================="
    echo "[SHOW] Would execute in $browser4_cli_dir:"
    echo "  cargo test ${AdditionalMvnArgs[*]}"
    echo "=========================================="
    popd > /dev/null || true
    return
  fi

  if [[ "$DRY_RUN" == "true" ]]; then
    echo ""
    echo "=========================================="
    echo "[DRY RUN] Executing in $browser4_cli_dir:"
    echo "  cargo test --no-run ${AdditionalMvnArgs[*]}"
    echo "=========================================="
  fi

  local -a cargo_args=("test")
  [[ "$DRY_RUN" == "true" ]] && cargo_args+=("--no-run")
  cargo_args+=("${AdditionalMvnArgs[@]}")
  cargo "${cargo_args[@]}"
  local exit_code=$?
  popd > /dev/null || true

  if [[ $exit_code -ne 0 ]]; then
    echo ""
    echo "=========================================="
    echo "❌ Browser4 CLI tests failed with exit code $exit_code"
    echo "=========================================="
    exit $exit_code
  fi

  echo ""
  echo "=========================================="
  echo "✅ Browser4 CLI tests completed successfully"
  echo "=========================================="
}

run_mocksiteboot() {
  local mocksite_module_dir="$repo_root/browser4-tests/browser4-rest-tests"
  local -a pass_through_args=()
  local -a mocksite_jvm_args=()
  local -a mvn_args=(
    "-DskipTests"
    "-P=-examples"
  )

  echo "=========================================="
  echo "Launching MockSiteBoot..."
  echo "=========================================="

  if [[ ! -d "$mocksite_module_dir" ]]; then
    echo "Error: Mock site module not found at $mocksite_module_dir" >&2
    exit 1
  fi

  for arg in "${AdditionalMvnArgs[@]}"; do
    if [[ "$arg" == -Dmock.site.* ]]; then
      mocksite_jvm_args+=("$arg")
    else
      pass_through_args+=("$arg")
    fi
  done

  mvn_args+=("${pass_through_args[@]}")
  if [[ ${#mocksite_jvm_args[@]} -gt 0 ]]; then
    has_jvm_args=false
    for arg in "${pass_through_args[@]}"; do
      if [[ "$arg" == -Dspring-boot.run.jvmArguments=* ]]; then
        has_jvm_args=true
        break
      fi
    done

    if [[ "$has_jvm_args" == "false" ]]; then
      local joined_jvm_args=""
      printf -v joined_jvm_args '%s ' "${mocksite_jvm_args[@]}"
      joined_jvm_args=${joined_jvm_args% }
      mvn_args+=("-Dspring-boot.run.jvmArguments=$joined_jvm_args")
    fi
  fi

  if [[ "$SHOW" == "true" ]]; then
    mvn_args+=("package" "spring-boot:run")
  elif [[ "$DRY_RUN" == "true" ]]; then
    mvn_args+=("compile")
  else
    mvn_args+=("package" "spring-boot:run")
  fi

  pushd "$mocksite_module_dir" > /dev/null || exit 1

  if [[ "$SHOW" == "true" ]]; then
    echo ""
    echo "=========================================="
    echo "[SHOW] Would execute in $mocksite_module_dir:"
    echo "  $repo_root/mvnw ${mvn_args[*]}"
    echo "=========================================="
    popd > /dev/null || true
    return
  fi

  if [[ "$DRY_RUN" == "true" ]]; then
    echo ""
    echo "=========================================="
    echo "[DRY RUN] Executing in $mocksite_module_dir:"
    echo "  $repo_root/mvnw ${mvn_args[*]}"
    echo "=========================================="
  fi

  "$repo_root/mvnw" "${mvn_args[@]}"
  local exit_code=$?
  popd > /dev/null || true
  if [[ $exit_code -ne 0 ]]; then
    echo ""
    echo "=========================================="
    echo "❌ MockSiteBoot failed with exit code $exit_code"
    echo "=========================================="
    exit $exit_code
  fi
}

# Read the parent POM's <modules> section to get the reactor build order.
# Modules are listed in the order they appear in <modules> (i.e. reactor order).
get_reactor_module_order() {
  local parent_pom="$repo_root/pom.xml"
  local -a order=()
  local in_modules=false

  while IFS= read -r line; do
    if [[ "$line" =~ \<modules\> ]]; then
      in_modules=true
      continue
    fi
    if [[ "$line" =~ \</modules\> ]]; then
      break
    fi
    if [[ "$in_modules" == "true" ]]; then
      if [[ "$line" =~ \<module\>(.+)\</module\> ]]; then
        order+=("${BASH_REMATCH[1]}")
      fi
    fi
  done < "$parent_pom"

  printf '%s\n' "${order[@]}"
}

# Find the artifactId for a module directory by reading its pom.xml.
# Skips the <parent> block to return the module's own artifactId.
get_artifact_id_for_dir() {
  local module_dir="$1"
  local pom="$module_dir/pom.xml"
  if [[ -f "$pom" ]]; then
    local in_parent=false
    while IFS= read -r line; do
      if [[ "$line" =~ \<parent\> ]]; then
        in_parent=true
      elif [[ "$line" =~ \</parent\> ]]; then
        in_parent=false
      elif [[ "$in_parent" == "false" && "$line" =~ \<artifactId\>(.+)\</artifactId\> ]]; then
        echo "${BASH_REMATCH[1]}"
        return
      fi
    done < "$pom"
  fi
}

run_resume_tests() {
  echo "=========================================="
  echo "Searching for failed modules to resume from..."
  echo "=========================================="

  # Collect all module directories that have failing test reports.
  local -a failed_module_dirs=()
  while IFS= read -r -d '' reports_dir; do
    if grep -rq '<failure\|<error' "$reports_dir"/*.xml 2>/dev/null; then
      # reports_dir is e.g. <repo>/browser4-core/target/surefire-reports
      local parent_dir
      parent_dir=$(dirname $(dirname "$reports_dir"))
      failed_module_dirs+=("$parent_dir")
    fi
  done < <(find "$repo_root" -path "*/target/surefire-reports" -type d -print0 2>/dev/null)

  if [[ ${#failed_module_dirs[@]} -eq 0 ]]; then
    echo "No previous test failures found to resume from."
    exit 0
  fi

  # Build a map: artifactId -> module directory
  declare -A artifact_to_dir
  for dir in "${failed_module_dirs[@]}"; do
    local aid
    aid=$(get_artifact_id_for_dir "$dir")
    if [[ -n "$aid" ]]; then
      artifact_to_dir["$aid"]="$dir"
    fi
  done

  # Walk the reactor order from the parent POM to find the first failed module.
  local resume_from=""
  local -a reactor_order
  mapfile -t reactor_order < <(get_reactor_module_order)

  for module_path in "${reactor_order[@]}"; do
    # module_path is a directory name (e.g. "browser4-core"). Resolve to full path.
    local module_pom="$repo_root/$module_path/pom.xml"
    if [[ -f "$module_pom" ]]; then
      local aid
      aid=$(get_artifact_id_for_dir "$repo_root/$module_path")
      if [[ -n "$aid" && -n "${artifact_to_dir["$aid"]}" ]]; then
        resume_from="$aid"
        break
      fi
    fi
  done

  if [[ -z "$resume_from" ]]; then
    echo "Could not match any failed module to the reactor order."
    exit 1
  fi

  echo "Resuming from module: $resume_from"
  echo ""

  local goal="test"
  [[ "$DRY_RUN" == "true" && "$SHOW" != "true" ]] && goal="test-compile"
  local -a mvn_test_args=("$goal" "-P=-examples" "-rf" ":$resume_from")
  mvn_test_args+=("${AdditionalMvnArgs[@]}")

  if [[ "$SHOW" == "true" ]]; then
    echo ""
    echo "=========================================="
    echo "[SHOW] Would execute:"
    echo "  ./mvnw ${mvn_test_args[*]}"
    echo "=========================================="
    return
  fi

  if [[ "$DRY_RUN" == "true" ]]; then
    echo ""
    echo "=========================================="
    echo "[DRY RUN] Executing:"
    echo "  ./mvnw ${mvn_test_args[*]}"
    echo "=========================================="
  fi

  ./mvnw "${mvn_test_args[@]}"
  local exit_code=$?
  if [[ $exit_code -ne 0 ]]; then
    echo ""
    echo "=========================================="
    echo "❌ Tests failed with exit code $exit_code"
    echo "=========================================="
    exit $exit_code
  fi

  echo ""
  echo "=========================================="
  echo "✅ Resume tests completed successfully"
  echo "=========================================="
}

KnownTestTypes=(fast it e2e cli browser4-cli mock-site mocksite mocksiteboot rest skills mcp resume browser4 b4)
TestTypes=()
MavenTests=()
CLITests=()
LaunchTargets=()
AdditionalMvnArgs=()
ParsingTestTypes=true
DRY_RUN=false
SHOW=false

if [[ $# -eq 0 ]]; then
  print_usage
fi

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|-help|--help)
      print_usage
      ;;
    --dry-run)
      DRY_RUN=true
      shift
      ;;
    --show)
      SHOW=true
      shift
      ;;
    fast|it|e2e|cli|browser4-cli|mock-site|mocksite|mocksiteboot|rest|skills|mcp|browser4|b4|resume)
      if [[ "$ParsingTestTypes" == "true" ]]; then
        TestTypes+=("$1")
      else
        AdditionalMvnArgs+=("$1")
      fi
      shift
      ;;
    *)
      if [[ "$ParsingTestTypes" == "true" && "$1" != -* ]]; then
        exit_unknown_test_type "$1"
      fi
      ParsingTestTypes=false
      AdditionalMvnArgs+=("$1")
      shift
      ;;
  esac
done

if [[ ${#TestTypes[@]} -eq 0 ]]; then
  TestTypes=(fast)
fi

# Handle 'resume' test type: find last failed module and resume with -rf
if [[ " ${TestTypes[*]} " == *" resume "* ]]; then
  if [[ ${#TestTypes[@]} -gt 1 ]]; then
    echo "Error: 'resume' must be the only test type. It resumes from the last failed module." >&2
    exit 1
  fi
  run_resume_tests
  exit 0
fi

for type in "${TestTypes[@]}"; do
  if [[ "$type" == "browser4" || "$type" == "b4" ]]; then
    MavenTests+=(fast it e2e rest)
  elif [[ "$type" == "cli" || "$type" == "browser4-cli" ]]; then
    CLITests+=("$type")
  elif [[ "$type" == "mock-site" || "$type" == "mocksite" || "$type" == "mocksiteboot" ]]; then
    LaunchTargets+=("mock-site")
  else
    MavenTests+=("$type")
  fi
done

UniqueMavenTests=()
for type in "${MavenTests[@]}"; do
  found=false
  for known in "${UniqueMavenTests[@]}"; do
    if [[ "$known" == "$type" ]]; then
      found=true
      break
    fi
  done

  if [[ "$found" == "false" ]]; then
    UniqueMavenTests+=("$type")
  fi
done
MavenTests=("${UniqueMavenTests[@]}")

UniqueCLITests=()
for type in "${CLITests[@]}"; do
  found=false
  for known in "${UniqueCLITests[@]}"; do
    if [[ "$known" == "$type" ]]; then
      found=true
      break
    fi
  done

  if [[ "$found" == "false" ]]; then
    UniqueCLITests+=("$type")
  fi
done
CLITests=("${UniqueCLITests[@]}")

UniqueLaunchTargets=()
for type in "${LaunchTargets[@]}"; do
  found=false
  for known in "${UniqueLaunchTargets[@]}"; do
    if [[ "$known" == "$type" ]]; then
      found=true
      break
    fi
  done

  if [[ "$found" == "false" ]]; then
    UniqueLaunchTargets+=("$type")
  fi
done
LaunchTargets=("${UniqueLaunchTargets[@]}")

if [[ ${#LaunchTargets[@]} -gt 0 && ( ${#MavenTests[@]} -gt 0 || ${#CLITests[@]} -gt 0 || ${#LaunchTargets[@]} -gt 1 ) ]]; then
  echo "Error: mock-site must be run by itself. Pass any Maven properties after it, for example: test.sh mock-site -Dmock.site.port=18080" >&2
  exit 1
fi

if [[ ${#MavenTests[@]} -gt 0 ]]; then
  run_maven_tests "${MavenTests[@]}"
fi

for test_type in "${CLITests[@]}"; do
  case "$test_type" in
    cli|browser4-cli)
      run_browser4_cli_tests
      ;;
  esac
done

for launch_target in "${LaunchTargets[@]}"; do
  case "$launch_target" in
    mock-site)
      run_mocksiteboot
      ;;
  esac
done

exit 0
