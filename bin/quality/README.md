# Link Checker and Fixer

A Python script to check and automatically fix links in documentation files.

## Features

- ✅ **Multi-format support**: Markdown, HTML, reStructuredText, and plain text
- 🔗 **Internal link validation**: Checks if referenced files exist (supports relative and absolute paths)
- 🌐 **External link validation**: Verifies connectivity using HEAD requests (fallback to GET)
- 🔧 **Auto-fix mode**: Automatically repairs broken internal links using fuzzy matching
- 💾 **Safe backups**: Creates .bak files before modifying documents
- ⚡ **Multi-threaded**: Fast parallel checking with configurable worker count
- 📊 **Detailed reporting**: Shows broken links with file locations, line numbers, and suggested fixes
- 🚀 **CI-friendly**: Returns non-zero exit code when broken links are found

## Requirements

- Python 3.7+
- `requests` library

Install requirements:
```bash
pip3 install requests
```

**Note**: The shell/PowerShell wrapper scripts will attempt to install the `requests` library automatically if it's not found and pip is available. This is for convenience and uses the well-known, safe `requests` library from PyPI.

## Usage

### Basic Usage

Check default documentation directories:
```bash
./bin/quality/check-links.sh
```

Or on Windows:
```powershell
.\bin\quality\check-links.ps1
```

### Advanced Usage

Check specific directories or files:
```bash
./bin/quality/check-links.sh docs README.md docs-dev
```

Skip external links (faster for local development):
```bash
./bin/quality/check-links.sh --skip-external
```

**Auto-fix broken internal links:**
```bash
./bin/quality/check-links.sh --fix
```

The --fix flag will:
1. Build a file cache for fast lookups
2. Find similar files using fuzzy matching
3. Calculate correct relative paths
4. Create .bak backups before changes
5. Update broken links automatically

Use more workers for faster checking:
```bash
./bin/quality/check-links.sh --workers 20
```

Exclude specific patterns:
```bash
./bin/quality/check-links.sh --exclude node_modules --exclude target
```

### Python Script Direct Usage

You can also run the Python script directly:
```bash
python3 bin/quality/fix-links.py --help
```

## Options

- `paths`: Directories or files to check (default: `docs README.md README.zh.md`)
- `--exclude PATTERN`: Patterns to exclude (can be used multiple times)
- `--workers N`: Number of worker threads (default: 10)
- `--timeout N`: Timeout for external links in seconds (default: 10)
- `--skip-external`: Skip checking external links
- `--fix`: Automatically fix broken internal links (creates .bak backups)
- `--check-localhost`: Check localhost/loopback URLs (default: skipped)
- `--root PATH`: Root directory of the project (default: auto-detect)

## Examples

```bash
# Check all documentation
./bin/quality/check-links.sh

# Quick check (skip external links)
./bin/quality/check-links.sh --skip-external

# Auto-fix broken internal links
./bin/quality/check-links.sh --skip-external --fix

# Check specific directories
./bin/quality/check-links.sh docs docs-dev

# Exclude build artifacts and use 20 workers
./bin/quality/check-links.sh --exclude target --exclude build --workers 20
```

## Auto-Fix Mode

The `--fix` flag enables automatic correction of broken internal links. This is useful for:
- Fixing links after files are moved or renamed
- Correcting typos in file paths
- Updating references after directory restructuring

### How it works:

1. **Detection**: Identifies broken internal links during normal checking
2. **Fuzzy Matching**: Searches for similar file paths using intelligent matching (60-70% similarity)
3. **Path Calculation**: Computes correct relative paths from source to target
4. **Safe Update**: Creates `.bak` backup files before making any changes
5. **Verification**: Only replaces exact URL matches in markdown/HTML links

### Example Output:

```
🔨 Building file cache for auto-fix mode...
🔧 Applying fixes to 3 file(s)...
   ✓ Fixed: ../devdocs/test-guide.md → ../docs-dev/copilot/test-guide.md
   ✓ Fixed: /pulsar-skeleton/WebDriver.kt → ../../pulsar-core/pulsar-skeleton/.../WebDriver.kt
   📝 Updated: docs/test-strategy.md

✅ Fixed 2 broken link(s)!
   💾 Backup files created with .bak extension
```

### What Gets Fixed:

- ✅ Wrong directory names (e.g., `devdocs` → `docs-dev`)
- ✅ Missing path prefixes for absolute paths
- ✅ Incorrect relative paths between files
- ✅ Files moved to different module directories
- ✅ Case-sensitive path issues

### What Doesn't Get Fixed:

- ❌ External URLs (HTTP/HTTPS links)
- ❌ Links where no similar file exists
- ❌ Anchor-only references (#section)
- ❌ Special URI schemes (mailto:, javascript:, etc.)

### Tips for Using --fix:

1. **Always review changes**: Check `.bak` files to verify fixes are correct
2. **Run without --fix first**: Preview suggested fixes before applying them
3. **Use with --skip-external**: Faster for local testing
4. **Commit separately**: Keep link fixes in separate commits from content changes

## Output

The script provides a detailed report including:

- Total files checked
- Total links found (internal/external/skipped)
- Valid and broken link counts
- List of broken links with:
  - Source file and line number
  - Link URL
  - Error message
  - Link type (internal/external)

### Example Output

```
🔍 Scanning for documentation files in 1 directory...
📄 Found 45 documentation file(s)
🔗 Checking links with 10 worker(s)...

⏱️  Completed in 3.42 seconds

================================================================================
📊 LINK CHECK REPORT
================================================================================

📁 Files checked:     45
🔗 Total links:       234
   ├─ Internal:       189
   ├─ External:       42
   └─ Skipped:        3

✅ Valid links:       230
❌ Broken links:      4

================================================================================
💔 BROKEN LINKS (4):
================================================================================

📄 docs/concepts.md:42
   🔗 /docs/missing-file.md
   ❌ File not found: /path/to/Browser4/docs/missing-file.md (internal)
   💡 Suggested fix: /docs/existing-file.md

📄 README.md:156
   🔗 https://example.com/dead-link
   ❌ HTTP 404 (external)

================================================================================
```

Note: When `--fix` is not used, suggested fixes are shown with "💡 Suggested fix" but not applied.

## CI Integration

Add to your CI pipeline:

### GitHub Actions

```yaml
- name: Check documentation links
  run: |
    pip3 install requests
    ./bin/quality/check-links.sh
```

### GitLab CI

```yaml
check-links:
  script:
    - pip3 install requests
    - ./bin/quality/check-links.sh
```

The script will exit with code 1 if any broken links are found, causing the CI job to fail.

## How It Works

1. **File Discovery**: Recursively finds all documentation files (`.md`, `.html`, `.rst`, `.txt`)
2. **Link Extraction**: Uses regex patterns to extract links from different file formats
3. **Link Classification**: Determines if link is internal, external, or should be skipped
4. **Validation**:
   - **Internal links**: Checks if referenced files exist on the filesystem
   - **External links**: Sends HEAD request first (faster), falls back to GET if needed
5. **Parallel Processing**: Uses thread pool for concurrent link checking
6. **Reporting**: Generates detailed report with statistics and error listings

## Link Types

### Internal Links

- Relative paths: `./docs/guide.md`, `../README.md`
- Absolute paths: `/docs/guide.md`
- With anchors: `docs/guide.md#section`
- Directory links: `docs/` (checks for index files)

### External Links

- HTTP/HTTPS URLs: `https://example.com`
- Markdown style: `[text](https://example.com)`
- HTML style: `<a href="https://example.com">`
- Bare URLs: `https://example.com`

### Skipped Links

- Email: `mailto:user@example.com`
- JavaScript: `javascript:void(0)`
- Data URIs: `data:image/png;base64,...`
- Telephone: `tel:+1234567890`
- Template variables: `${VAR}`, `{{var}}`
- Empty anchors: `#`

## Troubleshooting

### "requests library is required"

Install the requests library:
```bash
pip3 install requests
```

### Timeout errors for external links

Increase the timeout:
```bash
./bin/quality/check-links.sh --timeout 30
```

### Too many false positives for external links

Some websites block automated requests. You can:
1. Skip external links: `--skip-external`
2. Check them manually
3. Add patterns to exclude specific domains

### Script is slow

- Increase workers: `--workers 20`
- Skip external links: `--skip-external`
- Check specific directories only

## Contributing

To improve the link checker:

1. Add support for new file formats by updating patterns in `LinkChecker` class
2. Improve link extraction regex for edge cases
3. Add caching for frequently checked external URLs
4. Add configuration file support for project-specific settings

## License

This script is part of the Browser4 project and follows the same license.
