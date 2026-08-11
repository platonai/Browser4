# WebMiner — Convert Detail Web Pages into Interactive Views

WebMiner groups similar web pages together and produces an interactive HTML
report with clusters of related pages — plus Excel spreadsheets for further
analysis. Give it a folder of downloaded HTML files, and it handles the rest.
Everything runs locally; no data leaves your machine.

## Installing WebMiner

The `webminer.ps1` launcher can self-install and self-update from GitHub Releases:

```bash
.\webminer.ps1 install              # Download and install the latest release
.\webminer.ps1 update               # Check for and install the latest release
.\webminer.ps1 version              # Show installed and latest available versions
.\webminer.ps1 uninstall            # Remove the installed release
```

Releases are installed to `~/.scent/webminer/` and checked against
`https://github.com/platonai/web-miner/releases`. SHA-256 checksums are
verified automatically on download.

You can also use the JAR directly if it's already available:

```bash
java -jar scent-miner.jar <command> <args>
```

## Converting Pages to Views

### Running the Example

The `run-example` command downloads a pre-uploaded test dataset of real web
pages, extracts it, and runs the full pipeline — no manual setup required
beyond Java 17 and 7-Zip:

```bash
.\webminer.ps1 run-example
```

The dataset is cached at `~/.scent/test-data/amazon.com/` so subsequent runs
skip the download.

### Running on Your Own Pages

```bash
# Full pipeline (one-shot)
.\webminer.ps1 all /path/to/html/files

# Or with the JAR directly
java -jar scent-miner.jar all /path/to/html/files
```

The cluster count is always auto-detected from the data — this produces better
results than guessing a number.

### Options

| Flag | Default | Purpose |
|------|---------|---------|
| `--max-files <n>` | `40` | Maximum number of HTML files to process |
| `--output <dir>` | `<html-dir>-ml-output` | Where to write results |
| `--resume [<project-id>]` | — | Pick up where a previous run left off. If no project ID is given, the most recent project is used. |

### Building Views from an Existing Run

If clustering has already completed and you just need to (re)build the views:

```bash
java -jar scent-miner.jar views <html-dir>-ml-output/kmeans-result/p<timestamp>
```

## Output

The pipeline writes results to `<html-dir>-ml-output/` (or wherever `--output`
points). The views live in a `predictionAndMinimalFeatures.views/` directory
inside the timestamped result folder:

```
<html-dir>-ml-output/
  └── kmeans-result/
      └── p<timestamp>/
          └── predictionAndMinimalFeatures.views/
              ├── index.html    ← Open this in a browser
              ├── *.xlsx        ← Excel reports
              ├── *.json        ← Data files
              └── ...
```

Open `index.html` in a browser to explore the clustering results. The `.xlsx`
files can be opened in Excel for sorting, filtering, or further analysis.

## Tips

- **Input files** — only `*.html` and `*.htm` files are processed. Other files
  in the directory are ignored.
- **Resume interrupted runs** — if a pipeline stops partway through, use
  `--resume` to continue from the last completed stage instead of starting over.
- **Offline only** — WebMiner works with pre-downloaded HTML files. Use a
  browser, wget, or a crawler to fetch pages first.
- **Java 17** is required. Make sure `java` is on your PATH.
