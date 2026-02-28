#!/usr/bin/env bash

# ============================================================================
# Coworker Task Runner - Bash Shell Version
# ============================================================================
# Purpose:
#   Automatically processes task files in the 'created' directory
#   and executes them using the Copilot tool. Task files are moved through
#   a workflow: created -> working -> finished, with execution logs recorded.
#
# Task File Format (optional structured format):
#   Title: <task title>
#   Description: <task description>
#   Prompt: <task prompt content>
#
#   Mentions:
#   If a line starts with @coworker, the rest of the line is executed as a command.
#
#   If not in structured format, the entire file content is treated as the prompt.
#
# Usage:
#   bash coworker.sh [TaskFile]
#   ./coworker.sh [TaskFile]
# ============================================================================

# Configuration
COPILOT_NAME_TIMEOUT_SECONDS=60
COPILOT_RUN_TIMEOUT_SECONDS=6000

# Handle optional TaskFile argument
taskFile="$1"

# This allows the script to be run from any location within the project
AppHome="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repoRoot="$AppHome"
while [[ ! -f "$repoRoot/ROOT.md" ]] && [[ "$repoRoot" != "/" ]]; do
    repoRoot="$(dirname "$repoRoot")"
done

# If ROOT.md not found, fallback to script directory parents
if [[ ! -f "$repoRoot/ROOT.md" ]]; then
    # Fallback logic similar to PS1
    repoRoot="$AppHome/../.."
fi

cd "$repoRoot" || exit 1

# Define directory paths for task management workflow
baseDir="$repoRoot/coworker/tasks"
prepareDir="$baseDir/0prepare"
createdDir="$baseDir/1created"        # Input directory for new tasks
workingDir="$baseDir/2working"        # Processing directory for current tasks
finishedDir="$baseDir/3complete"      # Output directory for completed tasks
reviewDir="$baseDir/4review"
approvedDir="$baseDir/5approved"
pushedDir="$baseDir/6git-pushed"
logsDir="$baseDir/300logs"              # Directory for script and execution logs
scriptsDir="$repoRoot/coworker/scripts"

# Ensure all required directories exist
mkdir -p "$prepareDir"
mkdir -p "$createdDir"
mkdir -p "$workingDir"
mkdir -p "$finishedDir"
mkdir -p "$reviewDir"
mkdir -p "$approvedDir"
mkdir -p "$pushedDir"
mkdir -p "$logsDir"

# Handle specified TaskFile
if [[ -n "$taskFile" ]]; then
    if [[ -f "$taskFile" ]]; then
        # Resolve full path
        if command -v realpath >/dev/null 2>&1; then
             fullTaskPath=$(realpath "$taskFile")
        else
             fullTaskPath="$taskFile"
        fi

        fileName=$(basename "$fullTaskPath")
        destPath="$createdDir/$fileName"
        mv "$fullTaskPath" "$destPath"
        echo "Moved specified task file to: $destPath"
    else
        echo "Error: Specified task file not found: $taskFile" >&2
        exit 1
    fi
fi

# Initialize script-level logging
# Main log file for all script output
currentYear=$(date +%Y)
currentMonth=$(date +%m)
currentDay=$(date +%d)
currentTime=$(date +%H%M%S)
logsSubDir="$logsDir/$currentYear/$currentMonth/$currentDay"
mkdir -p "$logsSubDir"

scriptLogPath="$logsSubDir/${currentTime}-coworker.log"
scriptStartTime=$(date '+%Y-%m-%d %H:%M:%S')

# ============================================================================
# Logging Functions
# ============================================================================

# Function: Write message to console and main script log file
# Usage: log_message "message text" [LEVEL]
# Levels: INFO (default), WARN, ERROR
log_message() {
    local message="$1"
    local level="${2:-INFO}"
    local timestamp=$(date '+%Y-%m-%d %H:%M:%S')
    local logEntry="[$timestamp] [$level] $message"

    # Write to console
    case "$level" in
        WARN)
            echo -e "\033[33m$logEntry\033[0m"  # Yellow for warnings
            ;;
        ERROR)
            echo -e "\033[31m$logEntry\033[0m" >&2  # Red for errors
            ;;
        *)
            echo "$logEntry"
            ;;
    esac

    # Append to script log file
    echo "$logEntry" >> "$scriptLogPath"
}

# Function: Write message only to log file (for verbose output)
# Usage: log_verbose "debug message"
log_verbose() {
    local message="$1"
    local timestamp=$(date '+%Y-%m-%d %H:%M:%S')
    local logEntry="[$timestamp] [DEBUG] $message"

    # Append to script log file only (not console)
    echo "$logEntry" >> "$scriptLogPath"
}

# Function: Get unique path to avoid collisions
# Usage: resolve_unique_path "directory" "basename" "extension"
resolve_unique_path() {
    local dir="$1"
    local base="$2"
    local ext="$3"

    local candidateName="${base}${ext}"
    local candidatePath="$dir/$candidateName"

    if [[ ! -e "$candidatePath" ]]; then
        echo "$candidatePath"
        return
    fi

    local counter=2
    while true; do
        local nextName="${base}.${counter}${ext}"
        local nextPath="$dir/$nextName"
        if [[ ! -e "$nextPath" ]]; then
            echo "$nextPath"
            return
        fi
        ((counter++))
    done
}

# Function: Generate task name using Copilot
get_task_basename() {
    local title="$1"
    local description="$2"
    local prompt="$3"
    local fallback="$4"

    # Truncate prompt for naming context
    local promptSample="${prompt:0:600}"

    # Escape quotes for the prompt content
    # Bash variables inside double quotes don't need escaping for the content itself
    # unless we are evaluating the string or passing it to something that parses it again.
    # However, if the user explicitly mentioned escaping issues, let's revert to standard variable usage
    # but ensure we quote the variable when using it.
    
    local namingPrompt="Create a short, descriptive task name in kebab-case (3-6 words max). Output only the name.
Title: $title
Description: $description
Prompt: $promptSample"

    # Run gh copilot with timeout
    # Using timeout command if available, otherwise just run
    local rawName=""
    if command -v timeout >/dev/null 2>&1; then
        rawName=$(timeout "$COPILOT_NAME_TIMEOUT_SECONDS" gh copilot -p "$namingPrompt" --allow-all-tools --allow-all-paths 2>/dev/null | head -n 1)
        exitCode=$?
        if [[ $exitCode -eq 124 ]]; then # Timeout exit code
             return 1 # Fail triggers fallback
        fi
    else
        rawName=$(gh copilot -p "$namingPrompt" --allow-all-tools --allow-all-paths 2>/dev/null | head -n 1)
    fi

    if [[ -z "$rawName" ]]; then
        echo "$fallback"
        return
    fi

    # Normalize name
    # 1. Trim whitespace
    # 2. Replace whitespace with dashes
    # 3. Keep only alphanumeric, dot, underscore, dash
    # 4. Collapse multiple dashes
    # 5. Trim leading/trailing separators
    # 6. Truncate to 60 chars

    local normalized=$(echo "$rawName" | tr -d '\r' | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
    normalized=$(echo "$normalized" | sed 's/[[:space:]]\+/-/g')
    normalized=$(echo "$normalized" | sed 's/[^A-Za-z0-9._-]/-/g')
    normalized=$(echo "$normalized" | sed 's/-\+/-/g')
    normalized=$(echo "$normalized" | sed -e 's/^[-._]\+//' -e 's/[-._]\+$//')
    normalized="${normalized:0:60}"
    normalized=$(echo "$normalized" | sed -e 's/^[-._]\+//' -e 's/[-._]\+$//')

    if [[ -z "$normalized" ]]; then
        echo "$fallback"
    else
        echo "$normalized"
    fi
}

# Log script startup
log_message "===========================================================================" INFO
log_message "Coworker Task Runner - Bash Shell Version" INFO
log_message "Started at: $scriptStartTime" INFO
log_message "Script Log: $scriptLogPath" INFO
log_message "==========================================================================" INFO

# 1. Process 0prepare
shopt -s nullglob
prepare_files=("$prepareDir"/*)
shopt -u nullglob
for file in "${prepare_files[@]}"; do
    [[ -f "$file" ]] || continue
    log_message "[PREPARE] Task: $(basename "$file")" INFO
done

# 2. Process 3complete (newly added to show pending reviews)
if [[ -d "$finishedDir" ]]; then
    # Use find to locate files modified in the last 24 hours
    if command -v find >/dev/null 2>&1; then
        find "$finishedDir" -type f -mtime -1 -print0 | while IFS= read -r -d '' file; do
            log_message "[COMPLETE] Task waiting for review: $(basename "$file")" INFO
        done
    fi
fi

# 3. Process 4review
shopt -s nullglob
review_files=("$reviewDir"/*)
shopt -u nullglob
for file in "${review_files[@]}"; do
    [[ -f "$file" ]] || continue
    log_message "[REVIEW] Task: $(basename "$file")" INFO
done

# 4. Process 5approved
# Find files recursively in approvedDir
if [[ -d "$approvedDir" ]]; then
    # Use find to get all files recursively and process them in a loop
    # This avoids mapfile compatibility issues and array handling complexities
    found_files=0

    # Check if there are any files first to avoid running logic if empty
    if [[ -n $(find "$approvedDir" -type f -print -quit) ]]; then
        # Move files to pushed directory
        currentYear=$(date +%Y)
        currentDate=$(date +%m%d)
        pushedSubDir="$pushedDir/$currentYear/$currentDate"
        mkdir -p "$pushedSubDir"

        while IFS= read -r -d '' file; do
             [[ -f "$file" ]] || continue
             fileName=$(basename "$file")
             if [[ "$fileName" == *.* ]]; then
                fileExt=".${fileName##*.}"
                baseName="${fileName%.*}"
             else
                fileExt=""
                baseName="$fileName"
             fi

             pushedPath=$(resolve_unique_path "$pushedSubDir" "$baseName" "$fileExt")
             mv "$file" "$pushedPath"
             log_message "Task moved to pushed: $pushedPath" INFO
             found_files=1
        done < <(find "$approvedDir" -type f -print0)

        if [[ $found_files -eq 1 ]]; then
            # Call commit script
            commitScript="$scriptsDir/git-sync.sh"
            if [[ -f "$commitScript" ]]; then
                log_message "Executing commit script for approved tasks..." INFO
                bash "$commitScript"
                gitExitCode=$?
                if [[ $gitExitCode -eq 0 ]]; then
                    log_message "Git sync executed successfully." INFO
                else
                    log_message "Git sync failed with exit code $gitExitCode." ERROR
                fi
            else
                log_message "Commit script not found at $commitScript" WARN
            fi
        fi
    fi
fi

# 5. Process 6git-pushed (last 2 days)
# Use find to locate files modified in the last 2 days
# -mtime -2 means modified less than 2 days ago
if command -v find >/dev/null 2>&1; then
    find "$pushedDir" -type f -mtime -2 -print0 | while IFS= read -r -d '' file; do
        log_message "[PUSHED] Task: $(basename "$file")" INFO
    done
fi

# Process each file in the created directory
# Using nullglob to handle empty directory case safely
shopt -s nullglob
files=("$createdDir"/*)
shopt -u nullglob

for file in "${files[@]}"; do
    # Skip if it's a directory
    [[ -f "$file" ]] || continue

    log_message "Processing $(basename "$file")..." INFO

    # 1. Determine the descriptive name based on content
    renameScript="$scriptsDir/rename.sh"
    descriptiveName=""

    # Read content
    content=$(cat "$file")
    fileName=$(basename "$file")

    # Extract extension and basename
    if [[ "$fileName" == *.* ]]; then
        fileExt=".${fileName##*.}"
        baseName="${fileName%.*}"
    else
        fileExt=""
        baseName="$fileName"
    fi

    safeTitle=$(echo "$baseName" | sed 's/[\/\\*?:"<>|]/_/g')
    if [[ -z "$safeTitle" ]]; then safeTitle="task"; fi

    chmod +x "$renameScript" 2>/dev/null

    if [[ -f "$renameScript" && -x "$renameScript" ]]; then
        # Execute rename.sh script
        generatedName=$("$renameScript" "$file")
        if [[ -n "$generatedName" && "$generatedName" != "Error"* ]]; then
            descriptiveName="$generatedName"
        fi
    else
        # Fallback to internal function
        descriptiveName=$(get_task_basename "$safeTitle" "Task from $fileName" "$content" "$safeTitle")
    fi

    if [[ -z "$descriptiveName" ]]; then
         descriptiveName="$safeTitle"
    fi

    # 2. Rename in place (in created dir) then Move to working directory

    currentPath="$file"

    if [[ "$descriptiveName" != "$baseName" ]]; then
        renamedPath=$(resolve_unique_path "$createdDir" "$descriptiveName" "$fileExt")

        # If resolve_unique_path returned a path with a counter, update descriptiveName
        # resolve_unique_path returns full path. extraction needed if we want to update descriptiveName variable correctly for next steps
        renamedName=$(basename "$renamedPath")
        if [[ "$renamedName" == *.* ]]; then
             renamedBase="${renamedName%.*}"
        else
             renamedBase="$renamedName"
        fi

        mv "$file" "$renamedPath"
        log_message "Renamed in created: $fileName -> $renamedName" INFO

        currentPath="$renamedPath"
        fileName="$renamedName"
        baseName="$renamedBase"
        descriptiveName="$renamedBase"
    fi

    # 3. Move to working directory
    workingPath=$(resolve_unique_path "$workingDir" "$baseName" "$fileExt")
    workingBaseName=$(basename "$workingPath")
    # remove extension from workingBaseName for log naming
    if [[ "$workingBaseName" == *.* ]]; then
        workingBaseNameNoExt="${workingBaseName%.*}"
    else
        workingBaseNameNoExt="$workingBaseName"
    fi

    mv "$currentPath" "$workingPath"
    log_message "Moved to working: $workingPath" INFO

    # 4. Parse content for execution
    title="$descriptiveName"
    description="Task from $fileName"
    prompt="$content"

    # Check for @coworker mention
    # If found, use the mention content as the prompt
    mention_line=$(echo "$content" | grep "^@coworker" | head -n 1)
    if [[ -n "$mention_line" ]]; then
        # Extract everything after @coworker
        prompt=$(echo "$mention_line" | sed 's/^@coworker[[:space:]]*//')
        log_message "Found @coworker mention: $prompt" INFO
    else
        # Try to parse structured content (simple regex approach)
        # Using perl for multiline regex support which is more robust than bash regex
        if command -v perl >/dev/null 2>&1; then
            parsed_title=$(perl -0777 -ne 'print $1 if /Title:\s*(.*?)(\r\n|\n)/s' "$workingPath")
            parsed_desc=$(perl -0777 -ne 'print $1 if /Description:\s*(.*?)(\r\n|\n)/s' "$workingPath")
            parsed_prompt=$(perl -0777 -ne 'print $1 if /Prompt:\s*(.*)$/s' "$workingPath")

            if [[ -n "$parsed_title" ]]; then title=$(echo "$parsed_title" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//'); fi
            if [[ -n "$parsed_desc" ]]; then description=$(echo "$parsed_desc" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//'); fi
            if [[ -n "$parsed_prompt" ]]; then prompt=$(echo "$parsed_prompt" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//'); fi
        fi
    fi

    # Define log file paths
    # workingBaseNameNoExt was calculated earlier

    currentYear=$(date +%Y)
    currentMonth=$(date +%m)
    currentDay=$(date +%d)
    currentTime=$(date +%H%M%S)
    logsSubDir="$logsDir/$currentYear/$currentMonth/$currentDay"
    mkdir -p "$logsSubDir"

    taskLogPath="$logsSubDir/${currentTime}-${workingBaseNameNoExt}.task.log"
    copilotLogPath="$logsSubDir/${currentTime}-${workingBaseNameNoExt}.copilot.log"

    log_verbose "Task log will be written to: $taskLogPath"

    # Change working directory to repository root
    pushd "$repoRoot" > /dev/null

    log_message "Executing Copilot for task: $workingBaseNameNoExt" INFO
    log_verbose "Prompt length: ${#prompt} characters"

    # Record task execution details to task log
    {
        echo "Task: $title"
        echo "Description: $description"
        echo "Original File: $fileName"
        echo "Started: $(date '+%Y-%m-%d %H:%M:%S')"
        echo "Prompt:"
        echo "$prompt"
        echo "---"
        echo "Copilot Execution Output:"
    } > "$taskLogPath"

    # Define paths for temporary output and error logs
    stdOutLog="${copilotLogPath}.stdout"
    stdErrLog="${copilotLogPath}.stderr"

    log_message "=== Starting Copilot execution ===" INFO

    # Start Copilot in background to allow monitoring
    # We use a subshell to redirect outputs

    (
        gh copilot -p "$prompt" --allow-all-tools --allow-all-paths > "$stdOutLog" 2> "$stdErrLog"
    ) &
    copilotPid=$!

    startTime=$(date +%s)
    lastOutputLineCount=0

    # Monitor loop
    while kill -0 "$copilotPid" 2>/dev/null; do
        sleep 0.5

        # Check and display new stdout lines
        if [[ -f "$stdOutLog" ]]; then
            # Read new lines. We use wc -l to get line count
            currentLineCount=$(wc -l < "$stdOutLog")

            # If we have more lines than before
            if [[ "$currentLineCount" -gt "$lastOutputLineCount" ]]; then
                # Print lines from lastOutputLineCount+1 to currentLineCount
                tail -n +"$((lastOutputLineCount + 1))" "$stdOutLog"
                lastOutputLineCount=$currentLineCount
            fi
        fi

        # Check timeout
        nowEpoch=$(date +%s)
        elapsed=$((nowEpoch - startTime))

        if [[ "$elapsed" -gt "$COPILOT_RUN_TIMEOUT_SECONDS" ]]; then
            kill -9 "$copilotPid" 2>/dev/null
            log_message "Copilot timed out after ${COPILOT_RUN_TIMEOUT_SECONDS}s" WARN
            echo "[TIMEOUT] Copilot execution exceeded ${COPILOT_RUN_TIMEOUT_SECONDS}s timeout"
            break
        fi
    done

    # Wait for process to fully exit and get exit code
    wait "$copilotPid" 2>/dev/null
    exitCode=$?

    # Final output capture
    if [[ -f "$stdOutLog" ]]; then
        currentLineCount=$(wc -l < "$stdOutLog")
        if [[ "$currentLineCount" -gt "$lastOutputLineCount" ]]; then
            tail -n +"$((lastOutputLineCount + 1))" "$stdOutLog"
        fi
    fi

    # Capture stderr output
    if [[ -f "$stdErrLog" && -s "$stdErrLog" ]]; then
        echo -e "\n[STDERR OUTPUT]"
        # Yellow color for stderr
        while IFS= read -r line; do
            echo -e "\033[33m$line\033[0m"
        done < "$stdErrLog"
    fi

    # Combine logs
    if [[ -f "$stdOutLog" ]]; then cat "$stdOutLog" >> "$copilotLogPath"; fi
    if [[ -f "$stdErrLog" && -s "$stdErrLog" ]]; then
        {
            echo -e "\r\n=== COPILOT STDERR ===\r\n"
            cat "$stdErrLog"
        } >> "$copilotLogPath"
    fi

    # Cleanup temps
    rm -f "$stdOutLog" "$stdErrLog"

    log_message "Copilot execution finished with exit code $exitCode" INFO
    log_message "=== Copilot execution completed ===" INFO
    log_verbose "Copilot external tool log: $copilotLogPath"

    # Append result to task log
    {
        echo ""
        echo "Copilot Exit Code: $exitCode"
        echo "Copilot Log: $copilotLogPath"
    } >> "$taskLogPath"

    if [[ $exitCode -ne 0 ]]; then
        log_message "Warning: Copilot exited with non-zero code. Check log: $copilotLogPath" WARN
    fi

    popd > /dev/null

    # Move completed task from working directory to finished or approved directory
    # Check for #auto-approve tag in content
    currentYear=$(date +%Y)
    currentDate=$(date +%m%d)

    if echo "$content" | grep -q '#auto-approve'; then
        destDir="$approvedDir/$currentYear/$currentDate"
        destLabel="approved (auto-approve)"
    else
        destDir="$finishedDir/$currentYear/$currentDate"
        destLabel="finished"
    fi

    mkdir -p "$destDir"

    destPath=$(resolve_unique_path "$destDir" "$workingBaseNameNoExt" "$fileExt")

    mv "$workingPath" "$destPath"
    log_message "Task moved to ${destLabel}: $destPath" INFO

    log_message "---" INFO

done

# Log script completion
scriptEndTime=$(date '+%Y-%m-%d %H:%M:%S')
log_message "===========================================================================" INFO
log_message "All tasks completed" INFO
log_message "Ended at: $scriptEndTime" INFO
log_message "Script Log: $scriptLogPath" INFO
log_message "==========================================================================" INFO
