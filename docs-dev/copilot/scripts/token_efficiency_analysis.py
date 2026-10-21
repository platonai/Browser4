#!/usr/bin/env python3
"""Analyze token efficiency of the Browser4 coding module.

Measures:
1. Static prompt cost — tool specs rendered in KOTLIN (default prompt format,
   signatures only) and JSON (signatures + descriptions) formats.
2. Per-call output cost — worst-case caps derived from code constants, and
   "typical" costs sampled from real repo files.
3. Token estimation uses the same heuristic as TokenEstimator.kt (±25%).
"""
import os
import re
import statistics

ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
EXECUTOR = os.path.join(ROOT, "browser4-agentic", "src", "main", "kotlin", "ai", "platon",
                        "pulsar", "agentic", "tools", "builtin", "CodingToolExecutor.kt")
HARDCODED_SPEC = os.path.join(ROOT, "browser4-agentic", "src", "main", "kotlin", "ai", "platon",
                              "pulsar", "agentic", "tools", "specs", "ToolSpecification.kt")

# ---------------- Token estimation (mirror of TokenEstimator.kt) ----------------
CHUNK = re.compile(r"[A-Z]+[a-z]*|[a-z]+|[0-9]+|[\u3400-\u4dbf\u4e00-\u9fff]|\S")
CHUNK_FULL = re.compile(r"[A-Z]+[a-z]*|[a-z]+|[0-9]+|[\u3400-\u4dbf\u4e00-\u9fff]|[^\s]")

def estimate_tokens(text: str) -> int:
    if not text:
        return 0
    tokens = 0
    non_ws = 0
    for m in CHUNK_FULL.finditer(text):
        s = m.group(0)
        c = s[0]
        non_ws += len(s)
        if c.isdigit():
            tokens += (len(s) + 2) // 3
        elif '\u3400' <= c <= '\u9fff':
            tokens += 1
        elif c.isalpha():
            tokens += (len(s) + 4) // 5
        else:
            tokens += 1
    tokens += (len(text) - non_ws + 7) // 8
    return tokens

# ---------------- Parse toolSpec entries from CodingToolExecutor.kt ----------------
def parse_tool_specs(path):
    src = open(path, encoding="utf-8").read()
    specs = []
    # find each toolSpec["x"] = ToolSpec( ... ) block with balanced parens
    for m in re.finditer(r'toolSpec\["(\w+)"\]\s*=\s*ToolSpec\(', src):
        method = m.group(1)
        depth = 1
        i = m.end()
        while depth > 0 and i < len(src):
            if src[i] == '(':
                depth += 1
            elif src[i] == ')':
                depth -= 1
            i += 1
        block = src[m.end():i - 1]
        # args
        args = []
        for am in re.finditer(r'ToolSpec\.Arg\("(\w+)",\s*"([^"]+)"(?:,\s*"((?:[^"\\]|\\.)*)")?\)', block):
            name, typ, default = am.group(1), am.group(2), am.group(3)
            if default is not None:
                default = default.replace('\\"', '"').replace("\\$", "$")
            args.append((name, typ, default))
        # returnType
        rt = re.search(r'returnType\s*=\s*"([^"]*)"', block)
        return_type = rt.group(1) if rt else "Unit"
        # description: string literals joined by + (runs to the end of the block)
        desc_m = re.search(r'description\s*=\s*(.+)', block, re.S)
        description = ""
        if desc_m:
            parts = re.findall(r'"((?:[^"\\]|\\.)*)"', desc_m.group(1))
            description = "".join(p.replace('\\"', '"').replace("\\$", "$").replace("\\n", "\n") for p in parts)
        specs.append({"method": method, "args": args, "returnType": return_type,
                      "description": description, "descChars": len(description)})
    return specs

def render_kotlin(specs, domain="coding"):
    lines = []
    for s in sorted(specs, key=lambda x: x["method"]):
        args = ", ".join(f"{n}: {t}" + (f" = {d}" if d is not None else "") for n, t, d in s["args"])
        rt = s["returnType"]
        ret = f": {rt}" if rt and rt != "Unit" else ""
        lines.append(f"{domain}.{s['method']}({args}){ret}")
    return "\n".join(lines)

def render_json(specs, domain="coding"):
    # mirrors ToolCallSpecificationRenderer.buildJsonToolObject (compact equivalence is enough for sizing)
    parts = []
    for s in sorted(specs, key=lambda x: x["method"]):
        params = ", ".join(
            '{"name": "%s", "type": "%s"%s}' % (n, t, (', "default": "%s"' % d) if d is not None else "")
            for n, t, d in s["args"])
        entry = ('{"domain": "%s", "method": "%s", "parameters": [%s], "returns": "%s"'
                 % (domain, s["method"], params, s["returnType"]))
        if s["description"]:
            entry += ', "description": "%s"' % s["description"].replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")
        entry += "}"
        parts.append(entry)
    return "{\n  \"tools\": [\n    " + ",\n    ".join(parts) + "\n  ]\n}"

# ---------------- Repo file-size sampling (typical coding.read cost) ----------------
def sample_repo_files():
    sizes = []
    for base, dirs, files in os.walk(ROOT):
        dirs[:] = [d for d in dirs if d not in
                   ("target", "node_modules", ".git", ".workbuddy", "build", "out", "dist")]
        for f in files:
            if f.endswith((".kt", ".rs", ".md", ".js", ".ts", ".py")):
                p = os.path.join(base, f)
                try:
                    sizes.append((os.path.getsize(p), p))
                except OSError:
                    pass
    sizes.sort()
    return sizes

def pct(values, p):
    if not values:
        return 0
    k = max(0, min(len(values) - 1, int(round(p / 100 * (len(values) - 1)))))
    return values[k]

def fmt(n):
    return f"{n:,}"

def main():
    specs = parse_tool_specs(EXECUTOR)
    print(f"coding domain: {len(specs)} tools\n")

    # --- static prompt cost ---
    kotlin_block = render_kotlin(specs)
    json_block = render_json(specs)
    k_tokens = estimate_tokens(kotlin_block)
    j_tokens = estimate_tokens(json_block)

    hc_src = open(HARDCODED_SPEC, encoding="utf-8").read()
    hc_m = re.search(r'const val TOOL_CALL_SPECIFICATION = """\n(.*?)\n    """', hc_src, re.S)
    hc_block = hc_m.group(1) if hc_m else ""
    hc_tokens = estimate_tokens(hc_block)

    print("=== Static prompt cost (tool definitions injected every turn) ===")
    print(f"hardcoded builtin spec (tab/browser/fs/agent/system): {fmt(len(hc_block))} chars ≈ {fmt(hc_tokens)} tokens")
    print(f"coding domain, KOTLIN render (default; signatures only): {fmt(len(kotlin_block))} chars ≈ {fmt(k_tokens)} tokens")
    print(f"coding domain, JSON render (signatures + descriptions):  {fmt(len(json_block))} chars ≈ {fmt(j_tokens)} tokens")
    print()

    print("=== Top-10 most expensive tool definitions ===")
    rows = []
    for s in specs:
        sig = f"coding.{s['method']}(" + ", ".join(f"{n}: {t}" + (f" = {d}" if d is not None else "") for n, t, d in s["args"]) + ")"
        rows.append((estimate_tokens(sig), estimate_tokens(s["description"]), s["method"], s["descChars"]))
    rows.sort(key=lambda r: -(r[0] + r[1]))
    print(f"{'method':<22} {'sig-tok':>7} {'desc-tok':>8} {'desc-chars':>10}")
    for st, dt, name, dc in rows[:10]:
        print(f"{name:<22} {st:>7} {dt:>8} {fmt(dc):>10}")
    print()

    # --- per-call output caps from code constants ---
    print("=== Per-call output caps (from code constants) ===")
    caps = [
        ("read", "MAX_READ_SIZE_BYTES = 5 MB — NO truncation", 5 * 1024 * 1024),
        ("readLines", "unbounded (whole file when endLine=-1)", 5 * 1024 * 1024),
        ("shell", "stdout 200K + stderr 200K chars (MAX_OUTPUT_CHARS)", 400_000),
        ("grep", "200 results x ~200 chars + path prefixes", 200 * 260),
        ("glob", "200 file paths listed", 200 * 80),
        ("listDir", "unbounded at maxDepth>=2 on big trees", 200 * 80),
        ("diff", "unified diff of a 5 MB file", 5 * 1024 * 1024),
        ("changeSummary", "one line per changed file", 100 * 120),
        ("scaffoldFromExample (dir)", "concatenated multi-file skeleton set", 400_000),
        ("devTask(verify)", "plan + compile diagnostics + consistency", 50_000),
    ]
    print(f"{'tool':<28} {'cap-chars':>10} {'cap-tokens≈':>12}  note")
    for name, note, chars in caps:
        print(f"{name:<28} {fmt(chars):>10} {fmt(estimate_tokens('a' * chars) if chars < 200000 else int(chars/3.5)):>12}  {note}")
    print()

    # --- typical read cost from real repo files ---
    sizes = sample_repo_files()
    vals = [s for s, _ in sizes]
    kt_vals = [s for s, p in sizes if p.endswith(".kt")]
    print(f"=== Typical coding.read cost (repo has {fmt(len(vals))} text files) ===")
    for label, arr in [("all text files", vals), (".kt files only", kt_vals)]:
        if not arr:
            continue
        med = statistics.median(arr)
        print(f"{label}: median {fmt(int(med))} B ≈ {fmt(int(med/3.5))} tok | "
              f"p90 {fmt(pct(arr, 90))} B ≈ {fmt(int(pct(arr, 90)/3.5))} tok | "
              f"max {fmt(arr[-1])} B ≈ {fmt(int(arr[-1]/3.5))} tok")
    biggest = sizes[-5:]
    print("largest files:")
    for s, p in biggest:
        print(f"  {fmt(s)} B ≈ {fmt(int(s/3.5))} tok  {os.path.relpath(p, ROOT)}")
    print()

    # --- description share ---
    total_desc = sum(s["descChars"] for s in specs)
    print("=== Description volume (only paid in JSON/native tool-calling path) ===")
    print(f"total description chars: {fmt(total_desc)} ≈ {fmt(int(total_desc/3.5))} tokens across {len(specs)} tools")
    long_descs = sorted(specs, key=lambda s: -s["descChars"])[:5]
    for s in long_descs:
        print(f"  {s['method']:<22} {fmt(s['descChars'])} chars")

if __name__ == "__main__":
    main()
