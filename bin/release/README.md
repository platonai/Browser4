# Release Browser4

- make sure all tests pass
- merge current changes to master branch
- run release-tag-add.ps1 or release-tag-add.sh to add a new git tag
- wait for CI to build and publish to GitHub releases
- run next-minor.ps1 or next-minor.sh to bump version for next development cycle

# Release browser4-cli (npm)

1. Ensure all tests pass locally:
   ```bash
   cd sdks/browser4-cli
   npm ci && npm run build && npm test
   ```
2. Update `CHANGELOG.md` in `sdks/browser4-cli/` with the new version section.
3. Commit any outstanding changes and merge to master/main.
4. Run the release tagging script — it updates `package.json`, commits the bump,
   creates an annotated tag, and pushes it:
   ```bash
   # Linux / macOS
   ./bin/release/release-cli.sh 0.2.0

   # Windows PowerShell
   .\bin\release\release-cli.ps1 0.2.0
   ```
5. The pushed tag (`cli-v<version>`) triggers the **publish-cli** GitHub Actions
   workflow, which:
   - Lints, tests, and builds the CLI on ubuntu-latest
   - Smoke-tests the built artefact on Ubuntu, macOS, and Windows (Node 18 & 20)
   - Publishes `@platonai/browser4-cli@<version>` to the npm registry
   - Creates a GitHub Release at
     `https://github.com/platonai/Browser4/releases/tag/cli-v<version>`

For pre-releases (beta / RC), append a pre-release suffix:
```bash
./bin/release/release-cli.sh 0.2.0-beta.1
./bin/release/release-cli.sh 0.2.0-rc.1
```
Pre-release versions are published with the corresponding npm dist-tag
(`beta`, `rc`) instead of `latest`.
