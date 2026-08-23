# WebMiner — Convert Detail Web Pages into Interactive Views

WebMiner groups similar web pages together and produces an interactive HTML
report with clusters of related pages — plus Excel spreadsheets for further
analysis. Give it a folder of downloaded HTML files, and it handles the rest.
Everything runs locally; no data leaves your machine.

## Using from the Browser4 CLI

WebMiner is a first-class Browser4 citizen: the `browser4-cli webminer`
command installs, updates, and runs the tool natively (no PowerShell needed —
the CLI locates a Java 17+ installation and launches `scent-miner.jar`
directly). The JAR and its release metadata are installed to the same
locations the launcher script uses (`~/.scent/webminer/`), so both entry
points share one installation.

```bash
browser4-cli webminer install            # Download and install the latest release
browser4-cli webminer update             # Check for and install the latest release
browser4-cli webminer version            # Show installed and latest available versions
browser4-cli webminer uninstall          # Remove the installed release
browser4-cli webminer run-example        # Sample dataset + full pipeline (needs 7-Zip)
browser4-cli webminer all <html-dir>     # Full pipeline (encode → cluster → views)
browser4-cli webminer views <result-dir> # Rebuild views from an existing run
```

- `webminer all <dir>` accepts the pipeline options directly
  (`--max-files <n>`, `--output <dir>`, `--resume [<project-id>]`).
- Any other command is forwarded verbatim to `scent-miner.jar`, e.g.
  `browser4-cli webminer encode <dir>`.
- Runs started through the CLI set `-Dapp.name=webminer`, so the views
  task-output root is `%TEMP%\webminer-pereg\ml\tasks\...` (see [Output](#output)).

## Installing WebMiner

The `webminer.ps1` launcher can self-install and self-update from GitHub Releases:

```bash
.\webminer.ps1 install              # Download and install the latest release
.\webminer.ps1 update               # Check for and install the latest release
.\webminer.ps1 version              # Show installed and latest available versions
.\webminer.ps1 uninstall            # Remove the installed release
```

`browser4-cli webminer install/update/version/uninstall` are the
cross-platform equivalents (they do not require PowerShell).

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
| `--output <dir>` | `<html-dir>-ml-output` | Where to write the clustered results (CSV + clustering info; the views stage uses the app temp root — see [Output](#output)) |
| `--resume [<project-id>]` | — | Pick up where a previous run left off. If no project ID is given, the most recent project is used. |

### Building Views from an Existing Run

If clustering has already completed and you just need to (re)build the views:

```bash
java -jar scent-miner.jar views <html-dir>-ml-output/kmeans-result/p<timestamp>
```

## Output

`all` produces two kinds of artifacts in **two different places**:

1. **Clustered results** — written to `<html-dir>-ml-output/kmeans-result/p<timestamp>/`
   (or wherever `--output` points): one `result.csv` per feature view
   (`predictionAnd{Final,Minimal,Original}Features/result.csv`) plus
   `clusteringInfo.txt`.
2. **Views** (interactive HTML report + Excel + JSON) — the `views` stage of
   `all` writes them to the application's **temp task-output root**, NOT under
   `<html-dir>-ml-output`:
   `%TEMP%\<app>-pereg\ml\tasks\unsupervised\result\p<timestamp>\predictionAndMinimalFeatures.views\`
   on Windows (the `<app>` prefix follows `-Dapp.name`: `pulsar` for a direct
   `java -jar` run, `webminer` when launched through `webminer.ps1`).  The end
   of the run prints the resolved absolute views path.

So after `java -jar scent-miner.jar all ./html-pages/` the clustered results
look like:

```
html-pages-ml-output/
  └── kmeans-result/
      └── p<timestamp>/
          ├── predictionAndFinalFeatures/result.csv
          ├── predictionAndMinimalFeatures/result.csv
          ├── predictionAndOriginalFeatures/result.csv
          └── clusteringInfo.txt
```

and the views (`index.html`, `*.xlsx`, `*.json`) live in the temp
task-output directory printed by the run.

To place the views **beside the clustered results** (e.g. to archive them with
the project), rebuild them from the result directory:

```bash
java -jar scent-miner.jar views <html-dir>-ml-output/kmeans-result/p<timestamp>
```

This writes `predictionAndMinimalFeatures.views/` inside the given result
directory.  Open the generated `index.html` in a browser to explore the
clustering results. The `.xlsx` files can be opened in Excel for sorting,
filtering, or further analysis.

## Tips

- **Input files** — only `*.html` and `*.htm` files are processed. Other files
  in the directory are ignored.
- **Resume interrupted runs** — if a pipeline stops partway through, use
  `--resume` to continue from the last completed stage instead of starting over.
- **Offline only** — WebMiner works with pre-downloaded HTML files. Use a
  browser, wget, or a crawler to fetch pages first.
- **Java 17** is required. Make sure `java` is on your PATH.
