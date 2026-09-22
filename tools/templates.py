#!/usr/bin/env python3
"""Turn masked bank-SMS samples into candidate rule-pack templates, gated by the corpus test.

Developer-side only. Nothing here runs on a phone; the app has no network. Input is the text a
user copied with Settings → "Copy 20 unreadable messages" (every digit already 0) and pasted
into an issue. Claude sees only that. Output is a candidate that the corpus test must pass
before a human reviews the diff. Never commits.

  ANTHROPIC_API_KEY=... tools/templates.py samples.txt          # propose → tools/out/candidate.json
  tools/templates.py --apply tools/out/candidate.json           # merge into the pack, run the corpus, revert on red
"""
import json, os, pathlib, subprocess, sys, urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
PACK = ROOT / "rulepacks/merchants.json"
CORPUS = ROOT / "fixtures/corpus.jsonl"
OUT = ROOT / "tools/out/candidate.json"
MODEL = "claude-fable-5-1"

PROMPT = """You write extraction templates for a bank-SMS parser. Below is the JSON schema of the
`templates` section of its rule pack (regex, Java dialect; `{CUR}` and `{NUM}` expand to the currency
and number patterns), three existing rules of each kind as examples, and masked sample messages
(every digit is 0). Reply with ONE JSON object and nothing else:
{"types":[...], "amount":[...], "merchant":[...], "card":[...], "dates":[...], "currencyAliases":{},
 "corpus":[{"source":"<bank>","text":"<sample verbatim>","expect":{"type":"purchase","amount":0.0,
 "currency":"EGP","merchant":"...","category":"?"} or null}]}
Rules: only add rules the samples need; prefer whole-text case-insensitive rules for Latin text;
an OTP, promo, balance notice or login alert must map to a reject or be left unmatched; never
guess a merchant that is not in the text; `expect` must be what your rules produce for that sample.

SCHEMA AND EXAMPLES
%s

SAMPLES
%s"""


def propose(samples: str) -> dict:
    t = json.load(PACK.open())["templates"]
    examples = {k: (t[k][:3] if isinstance(t[k], list) else t[k]) for k in ("currency", "number", "types", "amount", "merchant", "card", "dates")}
    body = json.dumps({
        "model": MODEL, "max_tokens": 4000,
        "messages": [{"role": "user", "content": PROMPT % (json.dumps(examples, ensure_ascii=False, indent=1), samples)}],
    }).encode()
    req = urllib.request.Request("https://api.anthropic.com/v1/messages", body, {
        "x-api-key": os.environ["ANTHROPIC_API_KEY"], "anthropic-version": "2023-06-01", "content-type": "application/json",
    })
    text = json.load(urllib.request.urlopen(req))["content"][0]["text"]
    return json.loads(text[text.index("{"): text.rindex("}") + 1])


def apply(cand: dict) -> bool:
    pack = json.load(PACK.open())
    t = pack["templates"]
    # Appended, never inserted: existing rules keep precedence (first hit wins), so a candidate can
    # only add behaviour for messages that were rejected before.
    for k in ("types", "amount", "merchant", "card", "dates"):
        t[k].extend(cand.get(k, []))
    t["currencyAliases"].update(cand.get("currencyAliases", {}))
    PACK.write_text(json.dumps(pack, ensure_ascii=False, indent=2) + "\n")
    rows = [json.loads(l) for l in CORPUS.open()]
    with CORPUS.open("a") as f:
        for i, r in enumerate(cand.get("corpus", []), start=len(rows)):
            f.write(json.dumps({"id": i, **r}, ensure_ascii=False) + "\n")
    subprocess.run(["sed", "-i", "", f"s/rows.size >= [0-9]*/rows.size >= {len(rows) + len(cand.get('corpus', []))}/",
                    str(ROOT / "shared/src/jvmTest/kotlin/app/tidybox/engine/CorpusTest.kt")], check=True)
    ok = subprocess.run(["./gradlew", "-q", ":shared:jvmTest"], cwd=ROOT).returncode == 0
    if not ok:
        subprocess.run(["git", "checkout", "--", str(PACK), str(CORPUS), "shared/src/jvmTest"], cwd=ROOT)
        print("corpus RED — candidate reverted; fix it by hand or re-run propose with more samples")
    else:
        print("corpus GREEN — review `git diff`, then commit")
    return ok


if __name__ == "__main__":
    if sys.argv[1] == "--apply":
        sys.exit(0 if apply(json.load(open(sys.argv[2]))) else 1)
    OUT.parent.mkdir(exist_ok=True)
    OUT.write_text(json.dumps(propose(pathlib.Path(sys.argv[1]).read_text()), ensure_ascii=False, indent=2))
    print(f"candidate → {OUT}\nnext: tools/templates.py --apply {OUT}")
