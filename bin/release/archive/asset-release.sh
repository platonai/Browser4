#!/bin/bash

# Default parameters
REMOTE_USER="vincent"
REMOTE_HOST="platonai.cn"
REMOTE_PATH="~/platonic.fun/repo/ai/platon/pulsar/"

# Parse command line arguments
while [[ $# -gt 0 ]]; do
  case $1 in
    --remote-user=*)
      REMOTE_USER="${1#*=}"
      shift
      ;;
    --remote-host=*)
      REMOTE_HOST="${1#*=}"
      shift
      ;;
    --remote-path=*)
      REMOTE_PATH="${1#*=}"
      shift
      ;;
    *)
      echo "Unknown parameter: $1"
      exit 1
      ;;
  esac
done

repoRoot=$(git rev-parse --show-toplevel 2>null)
cd "$repoRoot" || exit 1

VERSION=$(head -n 1 "$repoRoot/VERSION" | sed 's/-SNAPSHOT//')

# If browser4-app/browser4-agents/target/Browser4.jar exists, copy it to remote
PULSAR_RPA_PATH="$repoRoot/browser4-app/browser4-agents/target/Browser4.jar"
if [[ -f "$PULSAR_RPA_PATH" ]]; then
  DESTINATION_PATH="${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_PATH}Browser4-${VERSION}.jar"

  echo "Copying $PULSAR_RPA_PATH to $DESTINATION_PATH..."

  # Check if SSH connection works
  if ! ssh "${REMOTE_USER}@${REMOTE_HOST}" 'echo Connection test' > /dev/null; then
    echo "Error: SSH connection test failed" >&2
    exit 1
  fi

  # Ensure destination directory exists
  if ! ssh "${REMOTE_USER}@${REMOTE_HOST}" "mkdir -p ${REMOTE_PATH}"; then
    echo "Error: Failed to create remote directory" >&2
    exit 1
  fi

  # Copy the file
  if ! scp "$PULSAR_RPA_PATH" "$DESTINATION_PATH"; then
    echo "Error: SCP command failed" >&2
    exit 1
  fi

  echo -e "\e[32mFile copied successfully to $DESTINATION_PATH\e[0m"

  # Create a symbolic link to the latest version
  if ! ssh "${REMOTE_USER}@${REMOTE_HOST}" "cd ${REMOTE_PATH} && ln -sf Browser4-${VERSION}.jar Browser4.jar"; then
    echo "Error: Failed to create symbolic link" >&2
    exit 1
  fi
  echo -e "\e[32mSymbolic link created successfully\e[0m"

  # List the files in the remote directory
  # e.g. ssh vincent@platonai.cn ls -l ~/platonic.fun/repo/ai/platon/pulsar/
  echo -e "\e[32mFiles in $REMOTE_PATH:\e[0m"
  if ! ssh "${REMOTE_USER}@${REMOTE_HOST}" "ls -l ${REMOTE_PATH}"; then
    echo "Error: Failed to list files in remote directory" >&2
    exit 1
  fi

  echo "Done."
  echo "Assets are available at: https://static.platonai.cn/repo/ai/platon/pulsar/"
else
  echo -e "\e[33mWarning: $PULSAR_RPA_PATH does not exist\e[0m" >&2
  exit 1
fi
