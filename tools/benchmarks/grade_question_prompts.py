"""Grade QuestionPromptProbeTest rows: refusals, question echo, expected facts, reply language, route labels."""
import collections
import difflib
import json
import re
import statistics
import sys

REFUSAL = re.compile(r"\b(audio|áudio|recording|gravação|grabación|aufnahme|provide|forneça|proporciona|bereitstellen|"
                     r"cannot hear|can't hear|não consigo ouvir|no puedo escuchar)\b|音频|录音|提供", re.I)
STOP = {
    "en": {"the", "is", "a", "you", "it", "of", "to", "and", "are", "this", "i", "in"},
    "pt-BR": {"o", "a", "é", "de", "que", "não", "um", "uma", "você", "para", "do", "da", "os", "as", "em"},
    "de": {"der", "die", "das", "ist", "und", "ich", "nicht", "ein", "eine", "zu", "es", "sie", "du", "in"},
    "es": {"el", "la", "es", "de", "que", "no", "un", "una", "y", "en", "los", "las", "para"},
}
WANT = {"plain": "A", "time": "A", "web": "C", "action": "D", "ignore": "E"}


def reply_lang(text):
    if re.search(r"[一-鿿]", text):
        return "zh-CN"
    words = re.findall(r"[a-zäöüßáéíóúãõâêôç]+", text.lower())
    scores = {lang: sum(w in s for w in words) for lang, s in STOP.items()}
    best = max(scores, key=scores.get)
    return best if scores[best] else "?"


WORDS = {"answer": "A", "camera": "B", "search": "C", "phone": "D", "ignore": "E"}


def label(text):
    w = re.match(r"\s*\[(answer|camera|search|phone|ignore)\]", text, re.I)
    if w:
        return WORDS[w.group(1).lower()]
    m = re.search(r"⊲[^A-E⊳]{0,3}([A-E])[^⊳]{0,3}⊳", text)
    return m.group(1) if m else None


def echoes(row):
    ans = row["answer"]
    if re.match(r"\s*(Q:|A:)", ans) or "\nQ:" in ans:
        return True
    first = re.split(r"[\n.?!。？！]", re.sub(r"⊲.?⊳", "", ans).strip(), maxsplit=1)[0].lower()
    return difflib.SequenceMatcher(None, first, row["text"].lower().rstrip(".?!。？")).ratio() > 0.75


def main(paths):
    rows = [json.loads(l) for p in paths for l in open(p) if l.strip()]
    rows = [r for r in rows if r.get("kind") == "row"]
    by = collections.defaultdict(list)
    for r in rows:
        by[(r["backend"], r["variant"])].append(r)
    print(f"{'backend':7s} {'variant':10s} {'n':>3s} {'refuse':>6s} {'echo':>5s} {'lang ok':>7s} {'fact':>7s} {'route':>7s} {'ttft':>6s} {'done':>6s}")
    for (backend, variant), rs in sorted(by.items()):
        refuse = sum(bool(REFUSAL.search(r["answer"])) for r in rs)
        echo = sum(echoes(r) for r in rs)
        answered = [r for r in rs if r["class"] in ("plain", "time", "visual")]
        lang_ok = sum(reply_lang(re.sub(r"Q:.*\n", "", r["answer"])) == r["lang"] or (r["lang"] == "en" and reply_lang(r["answer"]) == "en")
                      for r in answered)
        facts = [r for r in rs if r.get("expect") and (r["class"] != "visual" or (r.get("photo") and "img" in variant))]
        fact = sum(any(e.lower() in r["answer"].lower() for e in r["expect"]) for r in facts)
        route = ""
        if variant.startswith("route"):
            want = lambda r: ("A" if "img" in variant else "B") if r["class"] == "visual" else WANT.get(r["class"])
            route = f"{sum(label(r['answer']) == want(r) for r in rs)}/{len(rs)}"
        print(f"{backend:7s} {variant:10s} {len(rs):3d} {refuse:6d} {echo:5d} {lang_ok:3d}/{len(answered):<3d} {fact:3d}/{len(facts):<3d} {route:>7s} "
              f"{statistics.median(r['ttftMs'] for r in rs):6.0f} {statistics.median(r['doneMs'] for r in rs):6.0f}")
    if "-v" in sys.argv:
        for r in rows:
            print(f"{r['backend']:4s} {r['variant']:10s} {r['id'][:22]:22s} {r['class']:7s} {r['answer'][:110]!r}")
    routes = [r for r in rows if r["variant"].startswith("route")]
    if routes:
        print("\nroute labels per class:")
        for (backend, variant) in sorted({(r["backend"], r["variant"]) for r in routes}):
            for cls in ("visual", "plain", "time", "web", "action", "ignore"):
                sub = [r for r in routes if r["backend"] == backend and r["variant"] == variant and r["class"] == cls]
                if sub:
                    print(f"  {backend} {variant:10s} {cls:7s} {dict(collections.Counter(label(r['answer']) for r in sub))}")


if __name__ == "__main__":
    main([a for a in sys.argv[1:] if a != "-v"])
