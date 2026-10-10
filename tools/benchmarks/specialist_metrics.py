"""Exact, ordered, unmasked pilot metrics; no deployment confidence gate."""
from collections import defaultdict
import json


def exact_calls(actual, expected):
    return isinstance(actual, list) and json.dumps(actual, sort_keys=True, separators=(",", ":")) == json.dumps(expected, sort_keys=True, separators=(",", ":"))


def rebind_calls(calls, binding):
    """Map known opaque IDs only; an undeclared original name must remain wrong."""
    if not isinstance(calls, list):
        return calls
    return [dict(c, name=binding.get(c.get("name"), "UNDECLARED:"+str(c.get("name")))) if isinstance(c, dict) else c for c in calls]


def needle_proposals(envelope, include_suppressed=True):
    """Score proposals, not a validator turning wrong actions into empty calls.

    The bounded contract expects zero or one call, so any merged multi-call
    list is wrong regardless of ordering lost by the native suppression split.
    """
    if not isinstance(envelope, dict):
        return None
    if envelope.get("success") is False or envelope.get("error_code") not in (None, "", 0) or envelope.get("error") not in (None, ""):
        return None
    calls = envelope.get("function_calls")
    suppressed = envelope.get("suppressed_calls") or []
    if not isinstance(calls, list) or not isinstance(suppressed, list):
        return None
    return calls + suppressed if include_suppressed else calls


def summarize(rows):
    stages = defaultdict(list)
    for row in rows:
        stages[row["stage"]].append(row)
    positive = [r for r in rows if r["expected"]]
    negative = [r for r in rows if not r["expected"]]
    emitted = [r for r in rows if isinstance(r["calls"], list) and r["calls"]]
    correct = lambda r: exact_calls(r["calls"], r["expected"])
    return {
        "cases": len(rows), "correct": sum(map(correct, rows)),
        "exactCallAccuracy": sum(map(correct, rows)) / max(1, len(rows)),
        "positiveCases": len(positive), "negativeCases": len(negative),
        "positiveExactAccuracy": sum(map(correct, positive)) / max(1, len(positive)),
        "negativeFalseActionRate": sum(isinstance(r["calls"], list) and bool(r["calls"]) for r in negative) / max(1, len(negative)),
        "negativeInvalidRate": sum(r["calls"] is None for r in negative) / max(1, len(negative)),
        "rawEmittedCallPrecision": sum(map(correct, emitted)) / max(1, len(emitted)),
        "emittedRows": len(emitted), "invalidRows": sum(r["calls"] is None for r in rows),
        "rawClassifierNegativeActionRate": sum(r.get("selected", "DEFER") != "DEFER" for r in negative) / max(1, len(negative)) if any("selected" in r for r in rows) else None,
        "stages": {key: {"cases": len(items), "correct": sum(map(correct, items)), "accuracy": sum(map(correct, items))/len(items)} for key, items in stages.items()},
        "limits": "Uncalibrated offline development calls, not execution permission, final-test accuracy, recorded-ASR accuracy or phone completion."
    }
