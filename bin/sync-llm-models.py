#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""sync-llm-models.py

同步 Browser4 的 LLM providers.json 默认模型，真值源是 cc-switch 的模型目录。

用法:
    python bin/sync-llm-models.py                               # 使用默认本地路径
    python bin/sync-llm-models.py --cc D:/codebase/web-apps/cc-switch
    python bin/sync-llm-models.py --cc https://github.com/xxx/cc-switch  # git clone
    python bin/sync-llm-models.py --dry-run                    # 只打印不动文件
    python bin/sync-llm-models.py --preview                     # 额外打印每个候选的 metadata

映射规则（Provider 目录 id → Browser4 providerName）见 PROVIDER_ALIAS。
cc-switch 中未被 Browser4 收录的 provider，脚本会打印 warning 但不报错。
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple

# ── 关键路径 ────────────────────────────────────────────────────────────────
REPO_ROOT = Path(__file__).resolve().parent.parent
BROWSER4_PROVIDERS = (
    REPO_ROOT
    / "browser4-core"
    / "browser4-resources"
    / "src"
    / "main"
    / "resources"
    / "config"
    / "llm"
    / "providers.json"
)

# Browser4 providerName → cc-switch 中的 providerId 前缀（piModelCatalog 的 key）。
# piModelCatalog 的 key 形如 "<provider>/<model>"，我们取 "/".join(key.split("/")[:-1])
# 得到 providerId 前缀，再用这里的映射表对齐。
PROVIDER_ALIAS: Dict[str, List[str]] = {
    "openrouter": ["openrouter"],  # piModelCatalog 里没有 openrouter，走 OpenAI 目录
    "deepseek": ["deepseek"],
    "zhipu": ["zai", "zhipu"],  # piModelCatalog key 用 "zai/glm-..."
    "moonshot": ["moonshotai"],
    "minimax": ["minimax"],
    "volcengine": ["volcengine"],
    "hunyuan": ["tencent"],  # "tencent/hy3"
    "qianfan": ["baidu", "qianfan"],  # 百度千帆；piModelCatalog 里没有独立 key
    "bailian": ["qwen"],
    "openai": ["openai"],
    "anthropic": ["anthropic"],
    "gemini": ["google"],
    "groq": ["groq"],  # piModelCatalog key 无前缀，需要在 preset 文件里查找
    "together": ["together", "togetherai"],
    "mistral": ["mistral"],
    "xai": ["xai"],
    "fireworks": ["fireworks"],
    "stepfun": ["stepfun"],
}

# piModelCatalog.ts 里没有明确前缀的 provider，或者需要手动锁定
# 生产稳定默认值（catalog 只是"review 过的模型名单"，不是"默认值"），
# 在下面集中覆盖。键必须是 Browser4 的 providerName。
MANUAL_OVERRIDES: Dict[str, str] = {
    # openrouter: 无独立 catalog 前缀，跟随 OpenAI 最新旗舰
    "openrouter": "openai/gpt-6.1-sol",
    # fireworks: 无 catalog 条目，GLM 是他们主推的中文模型
    "fireworks": "accounts/fireworks/models/glm-5p3",
    # qianfan: 百度千帆 catalog 无条目，目前 deepseek 系列在千帆上架
    "qianfan": "deepseek-v4-flash",
    # together: cc-switch 没有 together 前缀，用 OSS 主力
    "together": "Qwen/Qwen3.7-Max",
    # groq: cc-switch 没有 groq 前缀，Groq 主打开源 LLM
    "groq": "openai/gpt-oss-120b",
    # deepseek: catalog 字典序最大的是 deepseek-v4.1-flash-1017（内部代号），
    # deepseek-flash 才是公开 API 的稳定生产 ID
    "deepseek": "deepseek-flash",
    # volcengine: catalog 有 ark-code-latest 和 doubao-seed-2.1-pro，我们是 doubao API
    "volcengine": "doubao-seed-2-1-pro-260915",
    # hunyuan: tokenplan-auto 是腾讯自动路由，hy3 是主力生产版
    "hunyuan": "hy3",
    # bailian (qwen): catalog 字典序最大的是 qwen3-coder-plus（代码专用），
    # 通用主力是 qwen3.8-max
    "bailian": "qwen3.8-max",
    # 以下三个基库内置 provider 在 cc-switch piModelCatalog 里
    # 没有对应条目，用基库默认的合理值占位
    "perplexity": "sonar",
    "baichuan": "Baichuan4",
    "yi": "yi-large",
}


# ── cc-switch 发现 ─────────────────────────────────────────────────────────
DEFAULT_LOCAL_HINTS = [
    "D:/codebase/web-apps/cc-switch",
    "~/codebase/web-apps/cc-switch",
    "~/workspace/cc-switch",
    "./cc-switch",
]

CC_GITHUB_FALLBACK = "https://github.com/platonai/cc-switch.git"


def discover_cc_switch(explicit: Optional[str]) -> Tuple[Path, Optional[str]]:
    """返回 (目录, 来源说明)；找不到就抛异常。"""
    candidates: List[Path] = []
    if explicit:
        # 显式给的是路径还是 URL？
        if re.match(r"^https?://|^git@", explicit):
            return _clone_cc(explicit), f"git clone {explicit}"
        candidates.append(Path(explicit).expanduser())
    else:
        env = os.environ.get("CC_SWITCH_PATH")
        if env:
            candidates.append(Path(env).expanduser())
        for h in DEFAULT_LOCAL_HINTS:
            candidates.append(Path(h).expanduser())

    for c in candidates:
        marker = c / "src" / "config" / "piModelCatalog.ts"
        if marker.exists():
            return c, f"local {c}"

    # 没找到 → 下载
    print(f"[sync] 本地未找到 cc-switch，从 GitHub 下载 {CC_GITHUB_FALLBACK}", file=sys.stderr)
    return _clone_cc(CC_GITHUB_FALLBACK), f"github {CC_GITHUB_FALLBACK}"


def _clone_cc(url: str) -> Path:
    tmp = Path(tempfile.mkdtemp(prefix="cc-switch-"))
    try:
        subprocess.run(
            ["git", "clone", "--depth=1", url, str(tmp)],
            check=True,
            capture_output=True,
            text=True,
        )
        return tmp
    except Exception as exc:
        # git 不可用就尝试直接下载 raw 文件（GitHub 单文件）
        print(f"[sync] git clone 失败 ({exc})，尝试下载原始文件", file=sys.stderr)
        return _download_raw_cc_files(url)


def _download_raw_cc_files(repo_url: str) -> Path:
    """GitHub 单文件下载，跳过 git。"""
    repo = re.sub(r"\.git$", "", repo_url)
    repo = re.sub(r"^https?://github\.com/", "", repo)
    raw_base = f"https://raw.githubusercontent.com/{repo}/main"

    tmp = Path(tempfile.mkdtemp(prefix="cc-switch-raw-"))
    files = [
        "src/config/piModelCatalog.ts",
        "src/config/codexProviderPresets.ts",
        "src/config/claudeProviderPresets.ts",
        "src/config/geminiProviderPresets.ts",
    ]
    for rel in files:
        url = f"{raw_base}/{rel}"
        dst = tmp / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        try:
            with urllib.request.urlopen(url, timeout=20) as resp:
                dst.write_bytes(resp.read())
            print(f"  下载 {rel}", file=sys.stderr)
        except urllib.error.HTTPError as e:
            print(f"  跳过 {rel}: HTTP {e.code}", file=sys.stderr)
    return tmp


# ── 目录解析 ────────────────────────────────────────────────────────────────
def parse_pi_model_catalog(path: Path) -> List[Dict[str, Any]]:
    """从 piModelCatalog.ts 顶层 object literal 里抽取每个模型条目。

    条目形如:
        "openai/gpt-6.1-sol": {
            capabilities: { name, reasoning, input: [...], contextWindow, maxTokens }
        }
    """
    text = path.read_text(encoding="utf-8")
    entries: List[Dict[str, Any]] = []
    pattern = re.compile(r'"([^"]+/[^"]+)":\s*\{')
    for m in pattern.finditer(text):
        key = m.group(1)
        start = m.end() - 1  # 指向 {
        depth = 0
        i = start
        in_str = False
        while i < len(text):
            c = text[i]
            if in_str:
                if c == "\\" and i + 1 < len(text):
                    i += 2
                    continue
                if c == '"':
                    in_str = False
            else:
                if c == '"':
                    in_str = True
                elif c == "{":
                    depth += 1
                elif c == "}":
                    depth -= 1
                    if depth == 0:
                        break
            i += 1
        body = text[start : i + 1]
        entry: Dict[str, Any] = {"key": key}
        # piModelCatalog 所有元数据都在 capabilities 子对象里
        # 抽 reasoning（true/false）、contextWindow、maxTokens
        m_reason = re.search(r'reasoning\s*:\s*(true|false)', body)
        if m_reason:
            entry["reasoning"] = m_reason.group(1) == "true"
        m_ctx = re.search(r'contextWindow\s*:\s*(\d[\d_]*)', body)
        if m_ctx:
            entry["contextWindow"] = int(m_ctx.group(1).replace("_", ""))
        m_max = re.search(r'maxTokens\s*:\s*(\d[\d_]*)', body)
        if m_max:
            entry["maxTokens"] = int(m_max.group(1).replace("_", ""))
        # input 数组抽第一个（image / text）
        m_in = re.search(r'input\s*:\s*\[([^\]]*)\]', body)
        if m_in:
            entry["input_raw"] = m_in.group(1)
        entries.append(entry)
    return entries


def _version_tuple(name: str) -> Tuple[int, ...]:
    """从模型名里提取语义版本元组，用于版本比较。

    先剔除参数量后缀（-120b, -20b, -480b 等）、四位数字独立 token
    （-2603 日期代号、-2025 年份等）和 1M token 数，再把剩余数字按
    位置提取为 tuple。

    例: gpt-6.1-sol → (6, 1); o4-mini → (4); claude-sonnet-5.5 → (5, 5);
        mistral-large-4 → (4); mistral-small-2603 → (0,)
        gpt-oss-120b → (0,)  (OSS 模型过滤参数量后无版本号)
    """
    s = name.lower()
    # 剔除参数量后缀: -120b, -20b, -480b, -1.6t, -70b
    s = re.sub(r"-?\d+(?:\.\d+)?[bt]", "", s)
    # 剔除 1M / 2M / 500K 这类 token 数
    s = re.sub(r"-?\d+[mk]", "", s, flags=re.IGNORECASE)
    # 剔除四位独立数字 token (日期代号 / 年份), 前后是连字符 / 字符串边界,
    # 所以否定字符类只允许字母/数字/./_ 作为邻居 — 连字符不在里面 → 满足条件
    s = re.sub(r"(?<![A-Za-z0-9._])\d{4}(?![A-Za-z0-9._])", "", s)
    nums = [int(x) for x in re.findall(r"\d+", s)]
    return tuple(nums) if nums else (0,)


def _family_penalty(name: str) -> int:
    """模型系列偏好。主力模型通常是 sonnet / large / pro / flash / max / plus，
    opus/ultra/haiku/mini 属于旗舰或轻量变种，fable 和 o3/o4 推理族是实验。
    值越大惩罚越高（会被排到后面）。"""
    n = name.lower()
    # 旗舰 / 轻量 / 实验 → 罚 2
    if any(x in n for x in ("opus", "ultra", "haiku", "mini", "fable", "astra", "terra")):
        return 2
    # 推理模型族 (思考专用) → 罚 1 (主力通常不是推理模型)
    if n.startswith("o") or "reason" in n or "thinking" in n:
        return 1
    # o-series 带数字也归为推理: o1, o3, o4-mini
    m = re.match(r"^o\d", n)
    if m:
        return 1
    return 0


def pick_default_for_provider(
    entries: Iterable[Dict[str, Any]], provider_prefixes: List[str]
) -> Optional[str]:
    """从 piModelCatalog 中为给定 provider 选出最新一代主力模型。

    排序优先级 (从高到低):
    1. 避 -vision / -preview / -highspeed / -exp 等实验/测试后缀
    2. reasoning=false 优先（主力默认通常不是思考/推理模式）
    3. 版本号元组降序 (6.1 > 5.5 > 4)
    4. contextWindow 大的优先
    """
    candidates: List[Dict[str, Any]] = []
    for e in entries:
        key = e.get("key", "")
        prefix = key.split("/")[0] if "/" in key else ""
        if prefix in provider_prefixes:
            candidates.append(e)
    if not candidates:
        return None

    BAD_SUFFIXES = ("-vision", "-preview", "-highspeed", "-exp")

    def _model_name(e: Dict[str, Any]) -> str:
        return e["key"].split("/", 1)[1] if "/" in e["key"] else e["key"]

    def _suffix_penalty(name: str) -> int:
        return 1 if any(name.endswith(s) for s in BAD_SUFFIXES) else 0

    # 排序优先级 (最后一次 sort key 最优先):
    #   contextWindow  → version_tuple(reverse) → suffix_penalty → family_penalty → reasoning
    # reasoning 最优先: 主力默认一定不是推理模型, 即使 reasoning 版本号更高也要降后
    candidates.sort(key=lambda e: -(e.get("contextWindow") or 0))
    candidates.sort(key=lambda e: _version_tuple(_model_name(e)), reverse=True)
    candidates.sort(key=lambda e: _suffix_penalty(_model_name(e)))
    candidates.sort(key=lambda e: _family_penalty(_model_name(e)))
    candidates.sort(key=lambda e: 1 if e.get("reasoning") else 0)

    chosen = candidates[0]
    return _model_name(chosen)


def pick_catalog_entry_for_provider(
    entries: Iterable[Dict[str, Any]], provider_prefixes: List[str]
) -> Optional[Dict[str, Any]]:
    """与 pick_default_for_provider 同样的选择逻辑，但返回完整 catalog entry
    （含 reasoning / reasoningEffort / contextWindow 等元数据），用于
    给 providers.json 填充 reasoning 字段。

    可能返回 None（provider 在 catalog 里没有条目，靠 override）。
    """
    candidates: List[Dict[str, Any]] = []
    for e in entries:
        key = e.get("key", "")
        prefix = key.split("/")[0] if "/" in key else ""
        if prefix in provider_prefixes:
            candidates.append(e)
    if not candidates:
        return None

    BAD_SUFFIXES = ("-vision", "-preview", "-highspeed", "-exp")

    def _model_name(e: Dict[str, Any]) -> str:
        return e["key"].split("/", 1)[1] if "/" in e["key"] else e["key"]

    def _suffix_penalty(name: str) -> int:
        return 1 if any(name.endswith(s) for s in BAD_SUFFIXES) else 0

    candidates.sort(key=lambda e: -(e.get("contextWindow") or 0))
    candidates.sort(key=lambda e: _version_tuple(_model_name(e)), reverse=True)
    candidates.sort(key=lambda e: _suffix_penalty(_model_name(e)))
    candidates.sort(key=lambda e: _family_penalty(_model_name(e)))
    candidates.sort(key=lambda e: 1 if e.get("reasoning") else 0)

    return candidates[0]


# 协议层参数名映射。不同 provider 族用的思考参数名不同。
# key = Browser4 apiProtocol（OPENAI / ANTHROPIC / GEMINI），
# value = (请求体顶层字段名, 值的结构约定)
_REASONING_PARAM_NAME: Dict[str, str] = {
    "OPENAI": "reasoning_effort",       # low | medium | high（o 系列）
    "ANTHROPIC": "thinking",            # {type: "enabled", budget_tokens: N}
    "GEMINI": "thinking_level",         # NONE | LOW | MEDIUM | HIGH
}


def _family_reasoning_default(provider_name: str, model_name: str) -> Optional[str]:
    """override provider 在 catalog 没条目时的默认思考强度推断。

    按命名规律 + 提供商常规：
    - flash / mini / haiku → off（主力主力，默认不思考节省 token）
    - opus / ultra / o3 / r1 / fable → high（推理旗舰）
    - pro / plus / max / large / sonnet → medium（默认主力，适量思考）
    - 其他 → None（交给 API 自身默认）
    """
    m = model_name.lower()
    if any(x in m for x in ("mini", "haiku", "flash", "tiny", "nano")):
        return "off"
    if any(x in m for x in ("opus", "ultra", "o3", "o4", "deepseek-r", "fable", "oss-120b", "oss-20b", "p1", "reasoning")):
        return "high"
    if any(x in m for x in ("pro", "plus", "max", "large", "sonnet", "sol", "terra", "luna", "astra")):
        return "medium"
    # OpenRouter 默认主力也是 sol 系列, 给 medium
    return None


def fill_reasoning_fields(
    providers: List[Dict[str, Any]],
    entries: List[Dict[str, Any]],
    manual_overrides: Dict[str, str],
    provider_alias: Dict[str, List[str]],
) -> None:
    """原地给每个 provider entry 加 reasoning 相关字段。

    三个字段（全部可选，可以省略表示"跟随 API 默认"）：
    - defaultReasoningEffort: "off" | "low" | "medium" | "high" | null
    - supportsReasoning:      true / false（该默认模型是否原生支持 reasoning）
    - reasoningParamName:     协议层参数名（reasoning_effort / thinking / thinking_level）
    """
    for p in providers:
        name = p["providerName"]
        protocol = p.get("apiProtocol", "OPENAI")
        param_name = _REASONING_PARAM_NAME.get(protocol, "reasoning_effort")

        # 1) 先看 override（override 的 defaultModel 在 catalog 里找不到原 entry）
        new_model = manual_overrides.get(name) or p["defaultModel"]
        aliases = provider_alias.get(name)

        catalog_entry: Optional[Dict[str, Any]] = None
        if aliases:
            catalog_entry = pick_catalog_entry_for_provider(entries, aliases)

        supports: Optional[bool] = None
        effort: Optional[str] = None

        if catalog_entry:
            # catalog 有该模型族的元数据
            supports = catalog_entry.get("reasoning")
            effort_from_cat = catalog_entry.get("reasoningEffort")
            if isinstance(effort_from_cat, str):
                effort = effort_from_cat.lower()  # "LOW" → "low"
            elif supports is True:
                # reasoning=true 但没标 effort → 默认给 medium
                effort = "medium"
            else:
                # reasoning=false → off
                effort = "off"
        else:
            # override 模型：按命名推断
            inferred = _family_reasoning_default(name, new_model)
            effort = inferred
            supports = inferred != "off" and inferred is not None

        # 写字段（None 值不写出来，保持 JSON 整洁）
        if supports is not None:
            p["supportsReasoning"] = supports
        if effort is not None:
            p["defaultReasoningEffort"] = effort
        p["reasoningParamName"] = param_name


# ── 主流程 ─────────────────────────────────────────────────────────────────
def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--cc", help="cc-switch 本地路径或 git URL，否则从 $CC_SWITCH_PATH 或默认位置找")
    ap.add_argument("--providers", default=str(BROWSER4_PROVIDERS), help="Browser4 providers.json 路径")
    ap.add_argument("--dry-run", action="store_true", help="只打印差异，不写文件")
    ap.add_argument("--preview", action="store_true", help="打印每个候选的 metadata")
    args = ap.parse_args()

    cc_path, source = discover_cc_switch(args.cc)
    print(f"[sync] cc-switch 来源: {source}", file=sys.stderr)

    catalog = cc_path / "src" / "config" / "piModelCatalog.ts"
    if not catalog.exists():
        print(f"[sync] 致命: 找不到 {catalog}", file=sys.stderr)
        return 2

    entries = parse_pi_model_catalog(catalog)
    print(f"[sync] piModelCatalog 解析到 {len(entries)} 个模型", file=sys.stderr)

    providers_json = Path(args.providers)
    data = json.loads(providers_json.read_text(encoding="utf-8"))

    changed = 0
    for p in data["providers"]:
        name = p["providerName"]
        aliases = PROVIDER_ALIAS.get(name)
        override = MANUAL_OVERRIDES.get(name)

        if override:
            new_model = override
            source_kind = "override"
        elif aliases:
            new_model = pick_default_for_provider(entries, aliases)
            source_kind = "catalog"
        else:
            print(f"  ? {name}: 无别名映射，跳过", file=sys.stderr)
            continue

        if not new_model:
            print(f"  ? {name}: 无匹配（override={bool(override)}，aliases={aliases}），保留 {p['defaultModel']}", file=sys.stderr)
            continue

        old = p["defaultModel"]
        if old != new_model:
            marker = "[DIFF]"
            changed += 1
        else:
            marker = "[OK]"
        print(f"  {marker} {name}: {old} → {new_model}  ({source_kind})")
        p["defaultModel"] = new_model

    print(f"[sync] 共 {changed} 个 provider 的 defaultModel 需要更新")

    # 2) 填充 reasoning 字段（与 defaultModel 解耦：即使 defaultModel 没变，
    # 字段也可能是第一次加或因 catalog 条目变更而变）
    before_reasoning = json.dumps(data["providers"], sort_keys=True)
    fill_reasoning_fields(data["providers"], entries, MANUAL_OVERRIDES, PROVIDER_ALIAS)
    after_reasoning = json.dumps(data["providers"], sort_keys=True)
    reasoning_changed = before_reasoning != after_reasoning

    print(f"[sync] reasoning 字段: {'有变更' if reasoning_changed else '无变更'}")

    if changed == 0 and not reasoning_changed:
        print("[sync] 已经是最新，无需写入")
        return 0

    if args.dry_run:
        print("[sync] dry-run，不写文件")
        return 0

    providers_json.write_text(
        json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    print(f"[sync] 已写入 {providers_json}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
