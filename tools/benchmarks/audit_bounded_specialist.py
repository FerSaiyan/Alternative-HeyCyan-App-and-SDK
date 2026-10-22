#!/usr/bin/env python3
"""Audit host menus, episode paths, model-view parity and token loss masks."""
import argparse
import hashlib
import json
import sys
from collections import Counter
from pathlib import Path

from bounded_specialist_contract import fingerprint, resolve_choice
from make_bounded_specialist_flat import resolve_flat_choice


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ("data", "needle-source", "laya-source", "laya-base", "out"):
        p.add_argument("--"+name,type=Path,required=True)
    args = p.parse_args()
    sys.path.insert(0,str(args.needle_source.resolve()))
    sys.path.insert(0,str(args.laya_source.resolve()))
    from needle.model.finetune import _encode, render_example
    from needle.model.tokenizer import get_tokenizer
    from laya.common import build_sequence, render_options
    from transformers import AutoTokenizer
    needle = get_tokenizer()
    laya = AutoTokenizer.from_pretrained(args.laya_base/"tokenizer",local_files_only=True)
    manifest = json.loads((args.data/"manifest.json").read_text())
    groups = {}
    report = {"contract":manifest["contract"],"view":manifest["view"],"datasets":{},"calibrationEvaluated":False,"sealedEvaluated":False,"needleCap":768,"layaCap":512,"limits":manifest["limits"]}
    for split in ("train","development","calibration","sealed"):
        def load(kind):
            name = f"{split}.{kind}.jsonl"
            content = (args.data/name).read_bytes()
            assert hashlib.sha256(content).hexdigest()==manifest["files"][name]["sha256"]
            rows = [json.loads(s) for s in content.splitlines()]
            assert len(rows)==manifest["files"][name]["rows"]
            return rows
        canonical,l,n,episodes = [load(kind) for kind in ("canonical","laya","needle","episodes")]
        eps = {ep["episodeId"]:ep for ep in episodes}
        max_l,max_n = 0,0
        offered, selected = Counter(), Counter()
        for c,lr,nr in zip(canonical,l,n):
            assert c["caseId"]==lr["caseId"]==nr["caseId"]
            assert c["turn"]["query"]==lr["state"]==nr["query"]
            assert lr["expectedCalls"]==nr["answers"]
            assert set(lr["questions"]["next_action"]["criteria"])=={tool["name"] for tool in nr["tools"]}==set(c["turn"]["options"])
            assert groups.setdefault(c["groupId"],split)==split
            offered.update(b["key"] for b in c["turn"]["options"].values())
            selected[c["turn"]["options"][c["expectedChoice"]]["key"]] += 1
            state = eps[c["episodeId"]]["snapshot"]
            resolve = resolve_flat_choice if manifest["view"]=="flat_state_matched" else resolve_choice
            assert resolve(c["turn"],c["expectedChoice"],state).get("effect")==c["expectedEffect"]
            prompt,target = render_example(nr)
            size = len(needle.encode(prompt))+len(needle.encode(target))+2
            assert size<=768,(c["caseId"],size,"Needle clipping")
            _,mask = _encode(needle,nr,768)
            assert sum(mask)==len(needle.encode(target))+1,"Target truncated/loss mask invalid"
            max_n=max(max_n,size)
            q=lr["questions"]["next_action"]
            question={"t":"choice","ins":q["instructions"],"crit":q["criteria"]}
            assert all(len(laya.encode(" "+o,add_special_tokens=False))<=48 for o in render_options(question))
            ids,markers=build_sequence(laya,lr["state"],question,512,256)
            status=laya.encode(lr["state"],add_special_tokens=False)
            assert len(markers)==len(q["criteria"]) and ids[-len(status)-1:-1]==status
            max_l=max(max_l,len(ids))
        for ep in episodes:
            path=[]
            rows=[r for r in canonical if r["episodeId"]==ep["episodeId"]]
            assert rows and rows[-1]["expectedEffect"]==ep["expectedEffect"]
            if manifest["view"]!="flat_state_matched":
                for row in rows:
                    assert row["turn"]["path"]==path,"Gold path skipped a hierarchy branch"
                    bound=resolve_choice(row["turn"],row["expectedChoice"],ep["snapshot"])
                    path=bound.get("nextPath",path)
        unsupported_labels = sorted(set(offered)-set(selected))
        if split=="train":
            assert not unsupported_labels,("Offered choices with no positive training examples",unsupported_labels)
        report["datasets"][split]={"episodes":len(eps),"turns":len(canonical),"maxLayaTokens":max_l,"maxNeedleTokens":max_n,"requestedBranchRecall":sum(c["requestedBranchRecall"] for c in canonical)/len(canonical),"eligibleGoldRecall":1.0,"choiceCoverage":dict(selected),"offeredButNeverGold":unsupported_labels}
    args.out.parent.mkdir(parents=True,exist_ok=True)
    args.out.write_text(json.dumps(report,indent=2)+"\n")
    print(json.dumps(report,indent=2))


if __name__ == "__main__":
    main()
