# -*- coding: utf-8 -*-
"""Strip Amazon browsing-session ids embedded in ref URLs before publishing artifacts."""
import io
import json
import os
import re

OUT = r"D:\workspace\Browser4\Browser4-4.15-feat\output\amazon-microwaves-2026-10-26"
PAT = re.compile(r"/\d{3}-\d{7}-\d{7}(?=[?/]|$)")

targets = ["bs_list.json", "bs_list_raw.txt"]
for name in targets:
    p = os.path.join(OUT, name)
    if not os.path.exists(p):
        continue
    c = io.open(p, encoding="utf-8").read()
    n = len(PAT.findall(c))
    c2 = PAT.sub("", c)
    io.open(p, "w", encoding="utf-8", newline="").write(c2)
    print("%-20s removed %d session ids" % (name, n))

# integrity: bs_list.json still parses and keeps 30 items
with io.open(os.path.join(OUT, "bs_list.json"), encoding="utf-8") as f:
    bs = json.load(f)
assert len(bs["items"]) == 30, len(bs["items"])
print("bs_list.json ok:", len(bs["items"]), "items; sample href:", bs["items"][0]["href"][:100])
