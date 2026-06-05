# Release Browser4

- make sure all tests pass
- run release-tag-add.ps1 to add a new git tag
- wait for CI to build and publish to GitHub releases
- run bump-version-patch.ps1 to bump version for next bug fix cycle
