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
  echo "Usage: test.sh [test-types...] [additional-args...]"
  echo ""
  echo "Test Types:"
  echo "  fast        Run fast unit tests only"
  echo "  it          Run integration tests"
  echo "  e2e         Run end-to-end tests"
  echo "  cli         Run Rust Browser4 CLI tests from cli/browser4-cli"
  echo "  mocksite    Launch MockSiteBoot from browser4-tests/browser4-rest-tests"
  echo "  rest        Run REST module tests"
  echo "  skills      Run skills-focused agentic tests"
  echo "  mcp         Run MCP-focused agentic tests"
  echo "  browser4    Run all Browser4 main tests (fast, rest, it, e2e)"
  echo "  b4          Alias for browser4"
  echo ""
  echo "Examples:"
  echo "  test.sh fast                       # Run fast unit tests"
  echo "  test.sh it                         # Run integration tests"
  echo "  test.sh e2e                        # Run end-to-end tests"
  echo "  test.sh cli                        # Run Browser4 CLI tests"
  echo "  test.sh cli -- --nocapture         # Pass extra cargo test args"
  echo "  test.sh mocksite -Dmock.site.port=18080"
  echo "  test.sh skills                     # Run skills-focused agentic tests"
  echo "  test.sh mcp                        # Run MCP-focused agentic tests"
  echo "  test.sh browser4                   # Run all Browser4 main tests"
  echo "  test.sh b4                         # Alias for browser4"
  echo "  test.sh it -pl browser4-core       # Pass additional Maven args through"
  exit 1
}

exit_unknown_test_type() {
  local test_type=$1
  echo "Error: Unknown test type '$test_type'. Valid test types: fast, it, e2e, cli, mocksite, rest, skills, mcp, browser4, b4." >&2
  exit 1
}

run_maven_tests() {
  local -a test_types=("$@")
  local -a mvn_test_args=("test" "-P=-examples")
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

  cargo test "${AdditionalMvnArgs[@]}"
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

  mvn_args+=("package" "spring-boot:run")

  pushd "$mocksite_module_dir" > /dev/null || exit 1
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

KnownTestTypes=(fast it e2e cli browser4-cli mocksite rest skills mcp browser4 b4)
TestTypes=()
MavenTests=()
CLITests=()
LaunchTargets=()
AdditionalMvnArgs=()
ParsingTestTypes=true

if [[ $# -eq 0 ]]; then
  print_usage
fi

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|-help|--help)
      print_usage
      ;;
    fast|it|e2e|cli|browser4-cli|mocksite|rest|skills|mcp|browser4|b4)
      if [[ "$ParsingTestTypes" == "true" ]]; then
        TestTypes+=("$1")
      else
        AdditionalMvnArgs+=("$1")
      fi
      ;;
    *)
      if [[ "$ParsingTestTypes" == "true" && "$1" != -* ]]; then
        exit_unknown_test_type "$1"
      fi
      ParsingTestTypes=false
      AdditionalMvnArgs+=("$1")
      ;;
  esac
  shift
done

if [[ ${#TestTypes[@]} -eq 0 ]]; then
  TestTypes=(fast)
fi

for type in "${TestTypes[@]}"; do
  if [[ "$type" == "browser4" || "$type" == "b4" ]]; then
    MavenTests+=(fast it e2e rest)
  elif [[ "$type" == "cli" || "$type" == "browser4-cli" ]]; then
    CLITests+=("$type")
  elif [[ "$type" == "mocksite" ]]; then
    LaunchTargets+=("$type")
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
  echo "Error: mocksite must be run by itself. Pass any Maven properties after it, for example: test.sh mocksite -Dmock.site.port=18080" >&2
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
    mocksite)
      run_mocksiteboot
      ;;
  esac
done

exit 0
