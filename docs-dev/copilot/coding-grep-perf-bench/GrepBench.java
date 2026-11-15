// Benchmark harness for the `coding_grep` performance investigation.
//
// It is a faithful Java replica of
// browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/CodingAgentFileSystem.kt
// `grep()` (same walk, same exclusion set, same 5 MB cap, same 500-match cap,
// same `Files.readAllLines` + per-line `matcher.find()`), plus the candidate
// optimisations measured in coding-grep-performance-2026-11-15.md.
//
// Requires JDK 21+ (single-file source launch). Run it from a scratch directory:
//
//   javac -d out GrepBench.java
//   java -cp out GrepBench <root> '<pattern>' <mode> <reps>
//
// Modes:
//   walk      traversal + filters only (no file opens) — the floor cost
//   current   the shipped implementation
//   stream    BufferedReader + walk attributes instead of a second stat()
//   literal   whole-file byte search for a plain literal (no decode)
//   parallel  candidate collection + a fixed thread pool over files
//
// Env:
//   GREP_CAP      match cap (default 500, like the shipped code)
//   GREP_THREADS  pool size (default: available processors)
//   GREP_MS       wall-clock budget for `stream` (0 = unlimited)
//
// Caution: on a cold Windows tree the first scan is 5-26x slower than the warm
// one (Defender's on-access scan is charged per file open). Compare like with
// like, and report both numbers.

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

public class GrepBench {

    /** CodingAgentFileSystem.DEFAULT_SEARCH_EXCLUDED_DIRS. */
    static final Set<String> EXCLUDED = Set.of(
            ".git", ".svn", ".hg",
            "node_modules", "bower_components",
            "target", "build", "dist", "out", "bin",
            ".gradle", ".idea", ".vscode", ".claude", ".mvn",
            "__pycache__", ".venv", "venv", ".tox",
            ".next", ".nuxt", ".cache", ".parcel-cache");

    /** CodingAgentFileSystem.BINARY_EXTENSIONS. */
    static final Set<String> BINARY = Set.of(
            "png", "jpg", "jpeg", "gif", "bmp", "ico", "webp", "svgz",
            "mp3", "mp4", "avi", "mov", "mkv", "webm", "wav", "flac",
            "zip", "tar", "gz", "bz2", "xz", "7z", "rar",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "class", "jar", "war", "ear", "exe", "dll", "so", "dylib",
            "bin", "dat", "db", "sqlite", "sqlite3",
            "ttf", "otf", "woff", "woff2", "eot",
            "wasm", "o", "a", "lib", "obj");

    static final long MAX_SIZE = 5L * 1024 * 1024;
    static final int CAP = Integer.parseInt(System.getenv().getOrDefault("GREP_CAP", "500"));
    static final int THREADS = Integer.parseInt(
            System.getenv().getOrDefault("GREP_THREADS", String.valueOf(Runtime.getRuntime().availableProcessors())));
    static final long BUDGET_MS = Long.parseLong(System.getenv().getOrDefault("GREP_MS", "0"));

    static final AtomicLong filesVisited = new AtomicLong();
    static final AtomicLong candidates = new AtomicLong();
    static final AtomicLong filesRead = new AtomicLong();
    static final AtomicLong bytesRead = new AtomicLong();
    static final AtomicLong linesScanned = new AtomicLong();
    static final AtomicLong decodeFailures = new AtomicLong();

    public static void main(String[] argv) throws Exception {
        if (argv.length >= 2 && argv[0].equals("gen")) {
            generate(Path.of(argv[1]), Integer.parseInt(argv[2]), Integer.parseInt(argv[3]), Integer.parseInt(argv[4]));
            return;
        }
        Path root = Path.of(argv[0]).toAbsolutePath().normalize();
        String pattern = argv[1];
        String mode = argv[2];
        int reps = argv.length > 3 ? Integer.parseInt(argv[3]) : 2;
        Pattern regex = Pattern.compile(pattern);
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:*");

        reset();
        long t0 = System.nanoTime();
        long sink = run(root, pattern, regex, matcher, mode);
        System.out.printf("first(cold) %-9s = %6d ms | files=%d cands=%d read=%d MB=%.1f res=%d%n",
                mode, (System.nanoTime() - t0) / 1_000_000,
                filesVisited.get(), candidates.get(), filesRead.get(), bytesRead.get() / 1048576.0, sink);

        long best = Long.MAX_VALUE;
        for (int i = 0; i < reps; i++) {
            reset();
            t0 = System.nanoTime();
            sink = run(root, pattern, regex, matcher, mode);
            best = Math.min(best, (System.nanoTime() - t0) / 1_000_000);
        }
        System.out.printf("%-9s best=%6d ms | files=%d cands=%d read=%d decodable=%d MB=%.1f lines=%d res=%d threads=%d cap=%d%n",
                mode, best, filesVisited.get(), candidates.get(), filesRead.get(),
                filesRead.get() - decodeFailures.get(), bytesRead.get() / 1048576.0,
                linesScanned.get(), sink, THREADS, CAP);
    }

    static void reset() {
        filesVisited.set(0);
        candidates.set(0);
        filesRead.set(0);
        bytesRead.set(0);
        linesScanned.set(0);
        decodeFailures.set(0);
    }

    static long run(Path root, String pattern, Pattern regex, PathMatcher matcher, String mode) throws Exception {
        switch (mode) {
            case "walk": return walkOnly(root, matcher);
            case "current": return current(root, regex, matcher);
            case "stream": return streamed(root, regex, matcher);
            case "literal": return literal(root, pattern, matcher);
            case "parallel": return parallel(root, regex, matcher);
            default: throw new IllegalArgumentException("unknown mode: " + mode);
        }
    }

    static FileVisitResult enter(Path root, Path dir) {
        if (!dir.equals(root) && EXCLUDED.contains(dir.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
        return FileVisitResult.CONTINUE;
    }

    static String ext(Path p) {
        String n = p.getFileName().toString();
        int i = n.lastIndexOf('.');
        return i < 0 ? "" : n.substring(i + 1).toLowerCase();
    }

    static long walkOnly(Path root, PathMatcher matcher) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) { return enter(root, dir); }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                filesVisited.incrementAndGet();
                if (BINARY.contains(ext(file))) return FileVisitResult.CONTINUE;
                if (attrs.size() > MAX_SIZE) return FileVisitResult.CONTINUE;
                if (!matcher.matches(file.getFileName())) return FileVisitResult.CONTINUE;
                candidates.incrementAndGet();
                bytesRead.addAndGet(attrs.size());
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException exc) { return FileVisitResult.SKIP_SUBTREE; }
        });
        return bytesRead.get();
    }

    /** The shipped implementation: Files.size + Files.readAllLines + per-line find(). */
    static long current(Path root, Pattern regex, PathMatcher matcher) throws IOException {
        List<String> results = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) { return enter(root, dir); }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                filesVisited.incrementAndGet();
                if (results.size() >= CAP) return FileVisitResult.TERMINATE;
                if (BINARY.contains(ext(file))) return FileVisitResult.CONTINUE;
                try {
                    if (Files.size(file) > MAX_SIZE) return FileVisitResult.CONTINUE;
                } catch (IOException e) {
                    return FileVisitResult.CONTINUE;
                }
                if (!matcher.matches(file.getFileName())) return FileVisitResult.CONTINUE;
                candidates.incrementAndGet();
                try {
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    filesRead.incrementAndGet();
                    bytesRead.addAndGet(attrs.size());
                    linesScanned.addAndGet(lines.size());
                    for (String line : lines) {
                        if (regex.matcher(line).find()) {
                            results.add(line);
                            if (results.size() >= CAP) break;
                        }
                    }
                } catch (Exception ignored) {
                    decodeFailures.incrementAndGet();
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException exc) { return FileVisitResult.SKIP_SUBTREE; }
        });
        return results.size();
    }

    /** Candidate 1: streamed read + walk attributes (no second stat). */
    static long streamed(Path root, Pattern regex, PathMatcher matcher) throws IOException {
        List<String> results = new ArrayList<>();
        long deadline = BUDGET_MS == 0 ? Long.MAX_VALUE : System.nanoTime() + BUDGET_MS * 1_000_000;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) { return enter(root, dir); }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                filesVisited.incrementAndGet();
                if (results.size() >= CAP || System.nanoTime() > deadline) return FileVisitResult.TERMINATE;
                if (BINARY.contains(ext(file))) return FileVisitResult.CONTINUE;
                if (attrs.size() > MAX_SIZE) return FileVisitResult.CONTINUE;
                if (!matcher.matches(file.getFileName())) return FileVisitResult.CONTINUE;
                candidates.incrementAndGet();
                try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    filesRead.incrementAndGet();
                    bytesRead.addAndGet(attrs.size());
                    String line;
                    while ((line = r.readLine()) != null) {
                        linesScanned.incrementAndGet();
                        if (regex.matcher(line).find()) {
                            results.add(line);
                            if (results.size() >= CAP) break;
                        }
                    }
                } catch (Exception ignored) {
                    decodeFailures.incrementAndGet();
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException exc) { return FileVisitResult.SKIP_SUBTREE; }
        });
        return results.size();
    }

    /** Candidate 2: plain-literal fast path — search raw bytes, decode nothing. */
    static long literal(Path root, String needle, PathMatcher matcher) throws IOException {
        byte[] nb = needle.getBytes(StandardCharsets.UTF_8);
        List<String> results = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) { return enter(root, dir); }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                filesVisited.incrementAndGet();
                if (results.size() >= CAP) return FileVisitResult.TERMINATE;
                if (BINARY.contains(ext(file))) return FileVisitResult.CONTINUE;
                if (attrs.size() > MAX_SIZE) return FileVisitResult.CONTINUE;
                if (!matcher.matches(file.getFileName())) return FileVisitResult.CONTINUE;
                candidates.incrementAndGet();
                try {
                    byte[] data = Files.readAllBytes(file);
                    filesRead.incrementAndGet();
                    bytesRead.addAndGet(data.length);
                    if (indexOf(data, nb, 0) >= 0) results.add(file.toString());
                } catch (Exception ignored) {
                    decodeFailures.incrementAndGet();
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException exc) { return FileVisitResult.SKIP_SUBTREE; }
        });
        return results.size();
    }

    /** Candidate 3: walk once, then scan candidates in a fixed pool. */
    static long parallel(Path root, Pattern regex, PathMatcher matcher) throws Exception {
        List<Path> files = Collections.synchronizedList(new ArrayList<>());
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) { return enter(root, dir); }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                filesVisited.incrementAndGet();
                if (BINARY.contains(ext(file))) return FileVisitResult.CONTINUE;
                if (attrs.size() > MAX_SIZE) return FileVisitResult.CONTINUE;
                if (!matcher.matches(file.getFileName())) return FileVisitResult.CONTINUE;
                candidates.incrementAndGet();
                bytesRead.addAndGet(attrs.size());
                files.add(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException exc) { return FileVisitResult.SKIP_SUBTREE; }
        });
        AtomicInteger matches = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Callable<Void>> tasks = new ArrayList<>(files.size());
        for (Path file : files) {
            tasks.add(() -> {
                if (matches.get() >= CAP) return null;
                try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    filesRead.incrementAndGet();
                    String line;
                    while ((line = r.readLine()) != null) {
                        linesScanned.incrementAndGet();
                        if (regex.matcher(line).find() && matches.addAndGet(1) >= CAP) break;
                    }
                } catch (Exception ignored) {
                    decodeFailures.incrementAndGet();
                }
                return null;
            });
        }
        for (Future<Void> f : pool.invokeAll(tasks)) f.get();
        pool.shutdown();
        return matches.get();
    }

    static int indexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for (int i = from; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    /** `gen <root> <dirs> <filesPerDir> <linesPerFile>` — synthetic tree for scaling runs. */
    static void generate(Path root, int dirs, int perDir, int lines) throws IOException {
        Files.createDirectories(root);
        StringBuilder sb = new StringBuilder();
        for (int d = 0; d < dirs; d++) {
            Path dir = root.resolve("pkg" + d);
            Files.createDirectories(dir);
            for (int f = 0; f < perDir; f++) {
                sb.setLength(0);
                for (int l = 0; l < lines; l++) {
                    sb.append("    // line ").append(l)
                            .append(" of module ").append(d).append(" file ").append(f)
                            .append(" with some padding text here\n");
                }
                if (d == dirs - 1 && f == perDir - 1) sb.append("RARE_MARKER_XYZ here\n");
                Files.writeString(dir.resolve("File" + f + ".kt"), sb, StandardCharsets.UTF_8);
            }
        }
        System.out.println("generated " + (dirs * perDir) + " files under " + root);
    }
}
