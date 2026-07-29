# WebMiner — Run the Scent ML Pipeline on Local HTML Files

WebMiner groups similar web pages together so you can browse patterns in your
data. Give it a folder of downloaded HTML files, and it produces an interactive
HTML report with clusters of related pages — plus Excel spreadsheets for further
analysis. Everything runs locally; no data leaves your machine.

## Quick Start

```bash
# One-shot: download the latest release and run
.\webminer.ps1 install
.\webminer.ps1 all /path/to/html/files
```

Or use the JAR directly:

```bash
java -jar scent-miner.jar all /path/to/html/files
```

## Managing Releases

The `webminer.ps1` launcher can self-install and self-update from GitHub Releases:

```bash
.\webminer.ps1 install              # Download and install the latest release
.\webminer.ps1 install v0.0.1       # Install a specific version
.\webminer.ps1 update               # Check for and install the latest release
.\webminer.ps1 version              # Show installed and latest available versions
.\webminer.ps1 uninstall            # Remove the installed release
```

Releases are installed to `~/.scent/webminer/` and checked against
`https://github.com/platonai/web-miner/releases`. SHA-256 checksums are
verified automatically on download.

When you run a pipeline command, `webminer.ps1` auto-detects the JAR:
1. Installed release at `~/.scent/webminer/lib/scent-miner.jar`
2. Bundled JAR in `lib/` next to or above the script

```bash
java -jar scent-miner.jar all /path/to/html/files
```

## Running the Example

The `run-example` command downloads a pre-uploaded test dataset of real web
pages, extracts it, and runs the full pipeline on it — no manual setup required
beyond Java 17 and 7-Zip:

```bash
.\webminer.ps1 run-example
.\webminer.ps1 run-example --k 8
```

The dataset is cached at `~/.scent/test-data/amazon.com/` so subsequent runs
skip the download. This is the quickest way to see WebMiner in action with
real-world HTML pages.

## Commands

| Command | Args | What it does |
|---------|------|-------------|
| `encode` | `<html-dir>` | Convert HTML files into structured data (CSV) |
| `cluster` | `<csv-path>` | Group similar pages together |
| `views` | `[<result-dir>]` | Build an interactive HTML report and Excel files |
| `all` | `<html-dir>` | Run the full pipeline: encode → cluster → views |

If you run `views` without a directory, it scans all completed clustering
projects and builds views for each one.

## Options

### encode / all

| Flag | Default | Purpose |
|------|---------|---------|
| `--max-files <n>` | `40` | Maximum number of HTML files to process |

### cluster

| Flag | Default | Purpose |
|------|---------|---------|
| `--k <n>` | auto-detected | Number of clusters |
| `--output <dir>` | auto-derived | Where to write results |

### all

| Flag | Default | Purpose |
|------|---------|---------|
| `--k <n>` | auto-detected | Number of clusters |
| `--max-files <n>` | `40` | Maximum number of HTML files to process |
| `--output <dir>` | `<html-dir>-ml-output` | Where to write results |
| `--resume [<project-id>]` | — | Pick up where a previous run left off. If no project ID is given, the most recent project is used. |

### Global

| Flag | Purpose |
|------|---------|
| `-am, --also-make` | Run all earlier pipeline stages first. The first argument becomes the HTML directory. |
| `--help, -h` | Print usage and exit |

## Examples

```bash
# Full pipeline (k auto-detected, up to 40 files)
java -jar scent-miner.jar all /data/amazon-pages

# Custom cluster count and file limit
java -jar scent-miner.jar all /data/amazon-pages --k 12 --max-files 50

# Resume an interrupted run (auto-detects latest project)
java -jar scent-miner.jar all /data/amazon-pages --resume

# Resume a specific project
java -jar scent-miner.jar all /data/amazon-pages --resume p20260717054158

# Encode only (limit to 20 files)
java -jar scent-miner.jar encode /data/amazon-pages --max-files 20

# Cluster an existing CSV (k auto-detected)
java -jar scent-miner.jar cluster /data/encoded.csv

# Cluster with a specific k
java -jar scent-miner.jar cluster /data/encoded.csv --k 8

# Run cluster with dependencies (encodes HTML first)
java -jar scent-miner.jar cluster /data/amazon-pages -am --k 12

# Build views with all dependencies
java -jar scent-miner.jar views /data/amazon-pages --also-make --max-files 50

# Build views from an existing clustering result
java -jar scent-miner.jar views /data/results/kmeans-result/p1723201624
```

## Output

The `all` pipeline writes clustering results to `<html-dir>-ml-output/` (or
wherever `--output` points):

```
<html-dir>-ml-output/
  └── kmeans-result/
      └── p<timestamp>/
          ├── clusteringInfo.txt
          ├── predictionAndMinimalFeatures/
          │   └── result.csv
          ├── predictionAndOriginalFeatures/
          │   └── result.csv
          └── predictionAndFinalFeatures/
              └── result.csv
```

The encoded CSV and the interactive views (HTML/XLSX) are stored in the system
ML data directory. To build views alongside the output, run:

```bash
java -jar scent-miner.jar views <html-dir>-ml-output/kmeans-result/p<timestamp>
```

This creates:

```
<html-dir>-ml-output/kmeans-result/p<timestamp>/
  └── predictionAndMinimalFeatures.views/
      ├── index.html    ← Open this in a browser
      ├── *.xlsx        ← Excel reports
      ├── *.json        ← Data files
      └── ...
```

Open `index.html` in a browser to explore the clustering results. The `.xlsx`
files can be opened in Excel for sorting, filtering, or further analysis.

## Tips

- **Let k auto-detect** — when you omit `--k`, WebMiner picks the best cluster
  count from the data. This usually works better than guessing a number.
- **Input files** — only `*.html` and `*.htm` files are processed. Other files
  in the directory are ignored.
- **Resume interrupted runs** — if a pipeline stops partway through, use
  `--resume` to continue from the last completed stage instead of starting over.
- **Offline only** — WebMiner works with pre-downloaded HTML files. Use a
  browser, wget, or a crawler to fetch pages first.
- **Run a single stage with dependencies** — use `-am` to auto-run all earlier
  stages. E.g., `cluster /html -am` encodes first, then clusters.
