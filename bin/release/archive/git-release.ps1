#!/usr/bin/env pwsh

$repoRoot = (git rev-parse --show-toplevel 2>$null)
Set-Location $repoRoot

$gitExe = "git" # Assuming git is in the system PATH

# Get version information
$SNAPSHOT_VERSION = Get-Content "$repoRoot\VERSION" -TotalCount 1
$VERSION =$SNAPSHOT_VERSION -replace "-SNAPSHOT", ""
$LAST_COMMIT_ID = &$gitExe log --format="%H" -n 1
$BRANCH = &$gitExe branch --show-current
$TAG = "v$VERSION"

# Replace SNAPSHOT version with the release version in readme files
function Replace-Version-In-ReadmeFiles {
  Write-Host "Replacing SNAPSHOT version with the release version in all markdown files"

  # Find all .md files recursively in the project directory
  $mdFiles = Get-ChildItem -Path "$repoRoot" -Recurse -Filter *.md

  # Replace SNAPSHOT version with the release version in each file
  $mdFiles | ForEach-Object {
    $filePath = $_.FullName
    (Get-Content $filePath) -replace $SNAPSHOT_VERSION, $VERSION | Set-Content $filePath
    Write-Host "Updated version in: $filePath"

    # Replace tags in all README files, for example, v0.0.1 -> v$VERSION
    (Get-Content $filePath) -replace 'v\d+\.\d+\.\d+', "v$VERSION" | Set-Content $filePath
    Write-Host "Updated version in: $filePath"
  }

  # Stage, commit, and push the changes
  Push-ReadmeFiles-Changes

  Write-Host "Version replacement completed and changes pushed to the repository."
}

function Restore-WorkingBranch {
  Write-Host "Ready to restore"
  $confirm = Read-Host -Prompt "Are you sure to continue? [Y/n]"
  if ($confirm -eq 'Y' -or $confirm -eq 'y' -or $confirm -eq '') {
    & $gitExe restore .
  } else {
    Write-Host "Bye."
    exit 0
  }
}

function Pull-Changes {
  Write-Host "Ready to pull"
  $confirm = Read-Host -Prompt "Are you sure to continue? [Y/n]"
  if ($confirm -eq 'Y' -or $confirm -eq 'y' -or $confirm -eq '') {
    & $gitExe pull
  } else {
    Write-Host "Bye."
    exit 0
  }
}

function Push-ReadmeFiles-Changes {
  Write-Host "Ready to push to $BRANCH"
  $confirm = Read-Host -Prompt "Are you sure to continue? [Y/n]"
  if ($confirm -eq 'Y' -or $confirm -eq 'y' -or $confirm -eq '') {

    & $gitExe add '**/*.md'
    & $gitExe commit -m "Replace SNAPSHOT version with the release version in all markdown files"
    & $gitExe push

  } else {
    Write-Host "Do not push"
  }
}

function Run-Tests {
  Write-Host "Running tests..."
  ./mvnw test -pl browser4-tests
  if ($LASTEXITCODE -ne 0) {
    Write-Host "Tests failed. Aborting release."
    exit 1
  }
}

function Add-Tag {
  Write-Host "Switching to master branch before tagging..."
  & $gitExe checkout master
  if ($LASTEXITCODE -ne 0) {
    Write-Host "Failed to checkout master branch. Aborting tag."
    exit 1
  }

  # Update commit id
  $LAST_COMMIT_ID = &$gitExe log --format="%H" -n 1
  if ($LASTEXITCODE -ne 0) {
    Write-Host "Failed to get commit id. Aborting tag."
    exit 1
  }

  Write-Host "Ready to add tag $TAG on$LAST_COMMIT_ID"
  $confirm = Read-Host -Prompt "Are you sure to continue? [Y/n]"
  if ($confirm -eq 'Y' -or $confirm -eq 'y' -or $confirm -eq '') {
    & $gitExe tag "$TAG" "$LAST_COMMIT_ID"
    Push-WithTags
  } else {
    Write-Host "Do not add tag."
  }
}

function Push-WithTags {
  Write-Host "Ready to push with tags to $BRANCH"
  $confirm = Read-Host -Prompt "Are you sure to continue? [Y/n]"
  if ($confirm -eq 'Y' -or $confirm -eq 'y' -or $confirm -eq '') {
    & $gitExe push --tags
  } else {
    Write-Host "Do not push with tags"
  }
}

function Merge-ToMainBranch {
  Write-Host "Ready to merge to main branch"
  $confirm = Read-Host -Prompt "Are you sure to continue? [Y/n]"
  if ($confirm -eq 'Y' -or $confirm -eq 'y' -or $confirm -eq '') {
    & $gitExe checkout main
    if ($LASTEXITCODE -ne 0) {
      & $gitExe checkout master
    }
    & $gitExe pull
    & $gitExe merge "$BRANCH"

    # 检查合并是否成功
    if ($LASTEXITCODE -ne 0) {
      Write-Host "Merge failed. Aborting merge."
      exit 1
    } else {
      Write-Host "Merge successful."
    }

    Push-ToMainBranch
  } else {
    Write-Host "Do not merge to main branch."
  }
}

function Push-ToMainBranch {
  Write-Host "Ready to push to main branch"
  $confirm = Read-Host -Prompt "Are you sure to continue? [Y/n]"
  if ($confirm -eq 'Y' -or $confirm -eq 'y' -or $confirm -eq '') {
    & $gitExe push
  } else {
    Write-Host "Bye."
    exit 0
  }
}

function Checkout-WorkingBranch {
  Write-Host "Ready to checkout working branch $BRANCH"
  $confirm = Read-Host -Prompt "Are you sure to continue? [Y/n]"
  if ($confirm -eq 'Y' -or $confirm -eq 'y' -or $confirm -eq '') {
    & $gitExe checkout "$BRANCH"
  } else {
    Write-Host "Remain on main branch"
  }
}

# Call functions
Restore-WorkingBranch
Pull-Changes
Replace-Version-In-ReadmeFiles
Merge-ToMainBranch
Run-Tests
Add-Tag
Checkout-WorkingBranch
