#!/usr/bin/env python3
"""
Link Checker for Documentation

This script checks all links in documentation files (Markdown, HTML, reStructuredText).
- Internal links: Validates that target files/anchors exist
- External links: Verifies connectivity with HEAD/GET requests
- Multi-threaded for performance
- CI-friendly with non-zero exit code on failures
"""

import argparse
import concurrent.futures
import os
import re
import sys
import time
import threading
from dataclasses import dataclass, field
from pathlib import Path
from typing import List, Set, Tuple, Optional
from urllib.parse import urlparse, urljoin, unquote

try:
    import requests
    from requests.adapters import HTTPAdapter
    from urllib3.util.retry import Retry
except ImportError:
    print("Error: 'requests' library is required. Install with: pip install requests", file=sys.stderr)
    sys.exit(1)


@dataclass
class LinkCheckResult:
    """Result of checking a single link"""
    url: str
    source_file: str
    line_number: int
    is_valid: bool
    error_message: str = ""
    link_type: str = "unknown"  # internal, external, anchor


@dataclass
class Statistics:
    """Statistics for link checking"""
    total_files: int = 0
    total_links: int = 0
    internal_links: int = 0
    external_links: int = 0
    anchor_links: int = 0
    valid_links: int = 0
    broken_links: int = 0
    skipped_links: int = 0
    errors: List[LinkCheckResult] = field(default_factory=list)


class LinkChecker:
    """Main link checker class"""

    # File extensions to check
    SUPPORTED_EXTENSIONS = {'.md', '.markdown', '.html', '.htm', '.rst', '.txt'}

    # Link patterns for different file types
    MARKDOWN_LINK_PATTERN = re.compile(
        r'\[([^\]]*?)\]\(([^)]+?)\)'  # [text](url)
    )
    MARKDOWN_AUTOLINK_PATTERN = re.compile(
        r'<(https?://[^>]+)>'  # <http://url>
    )
    MARKDOWN_BARE_URL_PATTERN = re.compile(
        r'(?:^|\s)(https?://\S+)'  # bare URLs at start or after whitespace
    )
    HTML_LINK_PATTERN = re.compile(
        r'(?:href|src)=["\']([^"\']+?)["\']',  # href="url" or src="url"
        re.IGNORECASE
    )
    RST_LINK_PATTERN = re.compile(
        r'`[^`]+<([^>]+)>`_|'  # `text <url>`_
        r'\.\. _[^:]+:\s*(\S+)'  # .. _name: url
    )

    def __init__(self, root_dir: Path, exclude_patterns: List[str] = None,
                 max_workers: int = 10, timeout: int = 10, skip_external: bool = False,
                 check_localhost: bool = False):
        self.root_dir = root_dir.resolve()
        self.exclude_patterns = exclude_patterns or []
        self.max_workers = max_workers
        self.timeout = timeout
        self.skip_external = skip_external
        self.check_localhost = check_localhost
        self.stats = Statistics()
        self.checked_external_urls: Set[str] = set()
        self.external_urls_lock = threading.Lock()  # Thread safety for checked URLs

        # Configure requests session with retries
        self.session = requests.Session()
        retry_strategy = Retry(
            total=3,
            backoff_factor=0.5,
            status_forcelist=[429, 500, 502, 503, 504],
        )
        adapter = HTTPAdapter(max_retries=retry_strategy)
        self.session.mount("http://", adapter)
        self.session.mount("https://", adapter)
        self.session.headers.update({
            'User-Agent': 'Mozilla/5.0 (compatible; LinkChecker/1.0)'
        })

    def should_skip_file(self, file_path: Path) -> bool:
        """Check if file should be skipped based on exclude patterns"""
        rel_path = str(file_path.relative_to(self.root_dir))
        for pattern in self.exclude_patterns:
            if pattern in rel_path:
                return True
        return False

    def find_documentation_files(self, search_dirs: List[Path]) -> List[Path]:
        """Find all documentation files in the given directories"""
        doc_files = []
        for search_path in search_dirs:
            if not search_path.exists():
                print(f"Warning: Path does not exist: {search_path}", file=sys.stderr)
                continue

            # If it's a file, add it directly if it's a supported type
            if search_path.is_file():
                if search_path.suffix in self.SUPPORTED_EXTENSIONS:
                    if not self.should_skip_file(search_path):
                        doc_files.append(search_path)
            # If it's a directory, search recursively
            elif search_path.is_dir():
                for file_path in search_path.rglob('*'):
                    if file_path.is_file() and file_path.suffix in self.SUPPORTED_EXTENSIONS:
                        if not self.should_skip_file(file_path):
                            doc_files.append(file_path)

        return sorted(doc_files)

    def extract_links_from_file(self, file_path: Path) -> List[Tuple[str, int]]:
        """Extract all links from a file with line numbers"""
        links = []
        try:
            with open(file_path, 'r', encoding='utf-8', errors='ignore') as f:
                content = f.read()
                lines = content.split('\n')

                # Determine file type and use appropriate patterns
                if file_path.suffix in {'.md', '.markdown'}:
                    patterns = [
                        self.MARKDOWN_LINK_PATTERN,
                        self.MARKDOWN_AUTOLINK_PATTERN,
                        self.MARKDOWN_BARE_URL_PATTERN
                    ]
                elif file_path.suffix in {'.html', '.htm'}:
                    patterns = [self.HTML_LINK_PATTERN]
                elif file_path.suffix == '.rst':
                    patterns = [self.RST_LINK_PATTERN]
                else:
                    patterns = [self.MARKDOWN_LINK_PATTERN]  # Default to markdown

                for line_num, line in enumerate(lines, 1):
                    for pattern in patterns:
                        for match in pattern.finditer(line):
                            # For markdown links [text](url), we want group 2 (the URL)
                            # For other patterns, get the first non-None group
                            url = None
                            if pattern == self.MARKDOWN_LINK_PATTERN:
                                # Group 2 is the URL in [text](url)
                                url = match.group(2)
                            else:
                                # Get first non-None group
                                for group in match.groups():
                                    if group:
                                        url = group.strip()
                                        break

                            if url:
                                url = url.strip()
                                # Skip certain URLs
                                if self._should_skip_url(url):
                                    continue
                                links.append((url, line_num))

        except Exception as e:
            print(f"Warning: Failed to read {file_path}: {e}", file=sys.stderr)

        return links

    def _should_skip_url(self, url: str) -> bool:
        """Check if URL should be skipped"""
        # Skip email links
        if url.startswith('mailto:'):
            return True
        # Skip javascript and data URIs
        if url.startswith(('javascript:', 'data:', 'tel:', 'ftp:')):
            return True
        # Skip empty or just anchor
        if not url or url == '#':
            return True
        # Skip template variables
        if '${' in url or '{{' in url:
            return True
        return False

    def _is_localhost_url(self, url: str) -> bool:
        """Check whether a URL points to localhost/loopback."""
        parsed = urlparse(url)
        host = (parsed.hostname or "").strip().lower().rstrip(".")
        return host in {"localhost", "127.0.0.1", "::1"}

    def check_internal_link(self, url: str, source_file: Path) -> Tuple[bool, str]:
        """Check if an internal link is valid"""
        # Remove query parameters and fragments for file checking
        url_parts = url.split('#', 1)
        file_part = url_parts[0].split('?', 1)[0]
        anchor = url_parts[1] if len(url_parts) > 1 else None

        if not file_part:  # Just an anchor reference
            return True, ""

        # Resolve the path relative to the source file
        source_dir = source_file.parent

        # Handle absolute paths from root
        if file_part.startswith('/'):
            target_path = self.root_dir / file_part.lstrip('/')
        else:
            target_path = (source_dir / file_part).resolve()

        # Check if target exists
        if target_path.exists():
            if target_path.is_file():
                return True, ""
            elif target_path.is_dir():
                # Check for index files
                for index_file in ['index.html', 'index.md', 'README.md']:
                    if (target_path / index_file).exists():
                        return True, ""
                return False, f"Directory exists but no index file found: {target_path}"
        else:
            # Try with common extensions if no extension provided
            if not target_path.suffix:
                for ext in ['.md', '.html', '.rst']:
                    if target_path.with_suffix(ext).exists():
                        return True, ""

            return False, f"File not found: {target_path}"

        return True, ""

    def check_external_link(self, url: str) -> Tuple[bool, str]:
        """Check if an external link is accessible"""
        # Skip if we've already checked this URL (thread-safe check)
        with self.external_urls_lock:
            if url in self.checked_external_urls:
                return True, ""
            self.checked_external_urls.add(url)

        try:
            # Try HEAD request first (faster)
            response = self.session.head(url, timeout=self.timeout, allow_redirects=True)
            if response.status_code < 400:
                return True, ""

            # If HEAD fails, try GET (some servers don't support HEAD)
            response = self.session.get(url, timeout=self.timeout, stream=True, allow_redirects=True)
            # Close immediately to avoid downloading content
            response.close()

            if response.status_code < 400:
                return True, ""
            else:
                return False, f"HTTP {response.status_code}"

        except requests.exceptions.Timeout:
            return False, "Timeout"
        except requests.exceptions.TooManyRedirects:
            return False, "Too many redirects"
        except requests.exceptions.RequestException as e:
            return False, f"Request failed: {str(e)}"
        except Exception as e:
            return False, f"Unexpected error: {str(e)}"

    def check_link(self, url: str, source_file: Path, line_number: int) -> LinkCheckResult:
        """Check a single link"""
        parsed = urlparse(url)

        # Determine link type
        if parsed.scheme in ('http', 'https'):
            link_type = 'external'
            if self.skip_external:
                self.stats.skipped_links += 1
                return LinkCheckResult(
                    url=url,
                    source_file=str(source_file.relative_to(self.root_dir)),
                    line_number=line_number,
                    is_valid=True,  # Treat as valid when skipped
                    error_message="Skipped (external)",
                    link_type=link_type
                )
            if not self.check_localhost and self._is_localhost_url(url):
                self.stats.skipped_links += 1
                return LinkCheckResult(
                    url=url,
                    source_file=str(source_file.relative_to(self.root_dir)),
                    line_number=line_number,
                    is_valid=True,
                    error_message="Skipped (localhost)",
                    link_type=link_type
                )
            is_valid, error = self.check_external_link(url)
            self.stats.external_links += 1
        elif parsed.scheme in ('', 'file'):
            # Internal link
            link_type = 'internal'
            is_valid, error = self.check_internal_link(url, source_file)
            self.stats.internal_links += 1
        else:
            # Unknown scheme
            link_type = 'unknown'
            is_valid = True
            error = f"Unsupported scheme: {parsed.scheme}"
            self.stats.skipped_links += 1

        if is_valid:
            self.stats.valid_links += 1
        else:
            self.stats.broken_links += 1

        return LinkCheckResult(
            url=url,
            source_file=str(source_file.relative_to(self.root_dir)),
            line_number=line_number,
            is_valid=is_valid,
            error_message=error,
            link_type=link_type
        )

    def check_file(self, file_path: Path) -> List[LinkCheckResult]:
        """Check all links in a single file"""

        print (f"🔍 Checking file: {file_path.relative_to(self.root_dir)}")

        results = []
        links = self.extract_links_from_file(file_path)

        for url, line_number in links:
            self.stats.total_links += 1
            result = self.check_link(url, file_path, line_number)
            if not result.is_valid:
                results.append(result)
                self.stats.errors.append(result)

        return results

    def run(self, search_dirs: List[Path]) -> Statistics:
        """Run link checker on all files"""
        print(f"🔍 Scanning for documentation files in {len(search_dirs)} path{'s' if len(search_dirs) != 1 else ''}...")

        doc_files = self.find_documentation_files(search_dirs)
        self.stats.total_files = len(doc_files)

        if not doc_files:
            print("⚠️  No documentation files found!")
            return self.stats

        print(f"📄 Found {len(doc_files)} documentation file(s)")
        print(f"🔗 Checking links with {self.max_workers} worker(s)...")

        start_time = time.time()

        # Process files in parallel
        with concurrent.futures.ThreadPoolExecutor(max_workers=self.max_workers) as executor:
            futures = {executor.submit(self.check_file, f): f for f in doc_files}

            for future in concurrent.futures.as_completed(futures):
                file_path = futures[future]
                try:
                    future.result()
                except Exception as e:
                    print(f"❌ Error processing {file_path}: {e}", file=sys.stderr)

        elapsed = time.time() - start_time
        print(f"\n⏱️  Completed in {elapsed:.2f} seconds")

        return self.stats

    def print_report(self):
        """Print detailed report"""
        print("\n" + "="*80)
        print("📊 LINK CHECK REPORT")
        print("="*80)

        print(f"\n📁 Files checked:     {self.stats.total_files}")
        print(f"🔗 Total links:       {self.stats.total_links}")
        print(f"   ├─ Internal:       {self.stats.internal_links}")
        print(f"   ├─ External:       {self.stats.external_links}")
        print(f"   └─ Skipped:        {self.stats.skipped_links}")

        print(f"\n✅ Valid links:       {self.stats.valid_links}")
        print(f"❌ Broken links:      {self.stats.broken_links}")

        if self.stats.errors:
            print(f"\n{'='*80}")
            print(f"💔 BROKEN LINKS ({len(self.stats.errors)}):")
            print(f"{'='*80}\n")

            for error in self.stats.errors:
                print(f"📄 {error.source_file}:{error.line_number}")
                print(f"   🔗 {error.url}")
                print(f"   ❌ {error.error_message} ({error.link_type})")
                print()

        print("="*80)


def main():
    parser = argparse.ArgumentParser(
        description='Check links in documentation files',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Check all docs
  %(prog)s

  # Check specific directories
  %(prog)s docs README.md

  # Skip external links (faster)
  %(prog)s --skip-external

  # Use more workers
  %(prog)s --workers 20

  # Exclude patterns
  %(prog)s --exclude node_modules --exclude .git

  # Check localhost URLs too
  %(prog)s --check-localhost
        """
    )

    parser.add_argument(
        'paths',
        nargs='*',
        default=['docs', 'README.md', 'README.zh.md'],
        help='Directories or files to check (default: docs README.md README.zh.md)'
    )

    parser.add_argument(
        '--exclude',
        action='append',
        default=[],
        help='Patterns to exclude (can be used multiple times)'
    )

    parser.add_argument(
        '--workers',
        type=int,
        default=10,
        help='Number of worker threads (default: 10)'
    )

    parser.add_argument(
        '--timeout',
        type=int,
        default=10,
        help='Timeout for external link checks in seconds (default: 10)'
    )

    parser.add_argument(
        '--skip-external',
        action='store_true',
        help='Skip checking external links (faster)'
    )

    parser.add_argument(
        '--check-localhost',
        action='store_true',
        help='Check localhost/loopback URLs (default: skipped)'
    )

    parser.add_argument(
        '--root',
        type=Path,
        default=None,
        help='Root directory of the project (default: auto-detect)'
    )

    args = parser.parse_args()

    # Determine root directory
    if args.root:
        root_dir = args.root
    else:
        # Try to find git root or use current directory
        root_dir = Path.cwd()
        git_dir = root_dir / '.git'
        if git_dir.exists():
            pass  # Already at root
        else:
            # Try to find .git in parent directories
            current = root_dir
            while current.parent != current:
                if (current / '.git').exists():
                    root_dir = current
                    break
                current = current.parent

    # Ensure UTF-8 output on Windows (avoids GBK UnicodeEncodeError for emoji)
    if sys.platform == 'win32':
        for stream in (sys.stdout, sys.stderr):
            try:
                stream.reconfigure(encoding='utf-8', errors='replace')
            except Exception:
                pass

    print(f"📂 Root directory: {root_dir}")

    # Convert paths to Path objects
    search_paths = []
    for path_str in args.paths:
        path = Path(path_str)
        if not path.is_absolute():
            path = root_dir / path
        search_paths.append(path)

    # Add default exclude patterns
    default_excludes = ['.git', 'node_modules', '__pycache__', '.mvn', 'target']
    exclude_patterns = default_excludes + args.exclude

    # Create checker and run
    checker = LinkChecker(
        root_dir=root_dir,
        exclude_patterns=exclude_patterns,
        max_workers=args.workers,
        timeout=args.timeout,
        skip_external=args.skip_external,
        check_localhost=args.check_localhost
    )

    try:
        stats = checker.run(search_paths)
        checker.print_report()

        # Exit with error code if broken links found
        if stats.broken_links > 0:
            sys.exit(1)
        else:
            print("\n✨ All links are valid!")
            sys.exit(0)

    except KeyboardInterrupt:
        print("\n\n⚠️  Interrupted by user", file=sys.stderr)
        sys.exit(130)
    except Exception as e:
        print(f"\n❌ Fatal error: {e}", file=sys.stderr)
        import traceback
        traceback.print_exc()
        sys.exit(1)


if __name__ == '__main__':
    main()
