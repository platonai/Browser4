# file-upload-and-download

Before running this scenario, ensure MockSite is running on localhost:18080 (`./bin/test.ps1 mock-site`).

This scenario covers file uploads and browser downloads: `upload` (single and multi-file), `download`
(download directory / behavior) and `wait --download`.

Fixtures:
- `http://localhost:18080/b4/mcp-tool-controller-interactive-fixture.html` — has `#file-input`
  (`type="file"`, `multiple`), a non-file input `#fill-target`, and mirrors the input state into
  `window.__browser4State` (`uploadCount`, `uploadName`) and `#state-log`.
- `http://localhost:18080/assets/download-blob.html` — an anchor labelled "Download" that builds a
  Blob and downloads it as `example.txt` containing `Hello world`. It needs no server route, so it
  works against MockSite.

Work in `./target/upload-download/` (absolute paths) and clean it up at the end.

## Steps

### 1. Prepare local files

1. Create two small local files with distinct contents, e.g. `alpha.txt` and `beta.txt`, in `./target/upload-download/`.
2. Open a fresh session and navigate to the interactive fixture.

### 2. Upload

3. Upload `alpha.txt` to `#file-input` and read the resulting input state.
4. Upload both files in one command and read the state again.
5. Attempt to upload into `#fill-target`, which is not a file input.
6. Attempt to upload a path that does not exist.

### 3. Download

7. Configure a download directory under `./target/upload-download/` before triggering the download.
8. Navigate to the blob download fixture, take a snapshot to obtain a ref for the "Download" link, and click it.
9. Wait for the download to complete and verify the landed file.

### 4. Cleanup

10. Remove the files created under `./target/upload-download/` and close the session.

## Acceptance Criteria

1. **AC1 — single upload:** `upload "#file-input" <abs-path-to-alpha.txt>` sets the input's files; `window.__browser4State.uploadName` becomes `alpha.txt` and `uploadCount` becomes `1`.
2. **AC2 — multi-file upload:** `upload "#file-input" <alpha> <beta>` uploads both paths separately (not one joined string): `uploadCount` becomes `2` and `uploadName` stays `alpha.txt`.
3. **AC3 — wrong target fails loudly:** `upload "#fill-target" <alpha>` exits non-zero with a message stating that only file inputs accept uploads — it must not report success.
4. **AC4 — missing file fails loudly:** `upload "#file-input" <nonexistent>` exits non-zero with a message saying the path is not found or not readable, **before** contacting the browser.
5. **AC5 — download directory is configurable:** `download --dir <abs-dir>` confirms the configuration, and the browser then writes downloads into that directory.
6. **AC6 — download completes:** after clicking the fixture's "Download" link, `wait --download --dir <abs-dir> --timeout 20000` returns when the download finishes, and `example.txt` exists in that directory with the content `Hello world`.
7. **AC7 — the wait is a real wait:** `wait --download` must not return before the file exists (no `.crdownload` leftover), and it must fail with a clear timeout message when no download is started.
8. **AC8 — reported paths are usable:** any path the CLI prints (download dir, uploaded file) is the real absolute path, and the files are actually there when checked from the shell.

## What to evaluate while doing this

- Are the download directory and its default (`./downloads`) documented where a first-time user looks?
- Does `wait --download` explain what it polls and what a timeout means?
- Is the upload path requirement (absolute path readable by the **backend** machine, not just the CLI) stated clearly in the help or skill reference?
