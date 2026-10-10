#!/usr/bin/env python3
"""Replays the journaled / gold decision requests against a Clef-flash SystemOne endpoint on a laptop and writes the
answers next to them, for DecisionEvalTest (docs/plans/decision-chunking.md §9). Nothing here runs on a phone.

    python tools/decide/replay_clef.py engine/src/test/resources/decide/boundaries.jsonl answers.jsonl \
        --url http://localhost:30000/v1/systemone --model Cloudflare/clef-flash

Input lines carry `request: {model, state, questions: [...]}` (a list with ids, the phone's hashable form); the Clef
API keys questions by id, so each request is converted before posting. Output lines are `{"id", "answers": {...}}` in
the API's answer shapes (a noul answer is a bare probability).
"""
import argparse
import json
import sys
import urllib.request


def to_clef(request, model):
    questions = {}
    for q in request["questions"]:
        body = {"type": q["type"], "instructions": q.get("instructions", "")}
        if "criteria" in q:
            body["criteria"] = q["criteria"]
        questions[q["id"]] = body
    return {"model": model, "state": request["state"], "questions": questions}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("dst")
    ap.add_argument("--url", default="http://localhost:30000/v1/systemone")
    ap.add_argument("--model", default="Cloudflare/clef-flash")
    a = ap.parse_args()
    n = 0
    with open(a.src, encoding="utf-8") as f, open(a.dst, "w", encoding="utf-8", newline="\n") as out:
        for line in f:
            line = line.strip()
            if not line:
                continue
            case = json.loads(line)
            body = json.dumps(to_clef(case["request"], a.model)).encode("utf-8")
            req = urllib.request.Request(a.url, data=body, headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=120) as r:
                res = json.loads(r.read().decode("utf-8"))
            out.write(json.dumps({"id": case["id"], "model": res.get("model"), "answers": res["answers"], "usage": res.get("usage")}, ensure_ascii=False) + "\n")
            n += 1
    print("%d requests answered by %s" % (n, a.model), file=sys.stderr)


if __name__ == "__main__":
    main()
