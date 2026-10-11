#!/usr/bin/env python3
"""Builds the held-out gold sets of the decision-model evaluation harness (docs/plans/decision-chunking.md §9).

    python tools/decide/make_gold.py <repo root>

Writes engine/src/test/resources/decide/boundaries.jsonl and verdicts.jsonl. Every line is
`{"id", "request": {model, state, questions: [...]}, "gold": {questionId: bool}}` in the shape the phone journals and
Clef-flash answers (see replay_clef.py). Deterministic: the same inputs give the same files.

- boundaries: windows of sentences from the prose of docs/spec.md, status.md, gates.md and seams.md (tables and code
  skipped; list items count as paragraphs). A boundary after sentence i is gold-true when sentence i + 1 opens a new
  `#` section (a topic change by construction), gold-false when both sentences sit in the same paragraph; boundaries
  between paragraphs of one section are left unlabelled (ambiguous).
- verdicts: the invoice fixtures (node/src/test/resources/fixtures/invoice_NN.txt). The true output holds the
  vendor, invoice number and total read from the text; the false outputs swap the total or the vendor with another
  invoice's, or break the schema (total as text).
"""
import json
import os
import re
import sys

root = sys.argv[1] if len(sys.argv) > 1 else "."
out_dir = os.path.join(root, "engine", "src", "test", "resources", "decide")
os.makedirs(out_dir, exist_ok=True)

SENT = re.compile(r"(?<=[.!?])\s+(?=[A-Z0-9\"'(\[])")


def sentences(block):
    block = re.sub(r"\s+", " ", block).strip()
    return [s.strip() for s in SENT.split(block) if re.search(r"[A-Za-z0-9]", s)]


def spec_sentences(path):
    """[(sentence, section index, paragraph index)] over the prose paragraphs of the spec (tables, code and lists skipped)."""
    out = []
    section = -1
    para = 0
    in_code = False
    for raw in open(path, encoding="utf-8").read().split("\n\n"):
        block = raw.strip()
        if not block:
            continue
        if block.startswith("```"):
            in_code = not in_code if block.count("```") % 2 else in_code
            continue
        if in_code:
            continue
        if block.startswith("#"):
            section += 1
            continue
        if block.startswith("|") or block.startswith("<!--"):
            continue
        # a bullet or numbered list: every item is a paragraph of its section (the amendments are written this way)
        items = re.split(r"\n(?=\s*(?:[-*]|\d+\.)\s+)", block) if re.match(r"^\s*(?:[-*]|\d+\.)\s+", block) else [block]
        for item in items:
            item = re.sub(r"^\s*(?:[-*]|\d+\.)\s+", "", item)
            item = re.sub(r"\*\*|`|\[([^\]]*)\]\([^)]*\)", lambda m: m.group(1) or "", item)
            ss = sentences(item)
            if not ss:
                continue
            para += 1
            out += [(s, section, para) for s in ss]
    return out


def boundaries(paths, window=12):
    rows = []
    for p in paths:
        rows += spec_sentences(p)
    lines = []
    i = 0
    n = len(rows)
    while i + window <= n:
        idx = list(range(i, i + window))
        gold = {}
        for k in idx[:-1]:
            (s0, sec0, p0), (s1, sec1, p1) = rows[k], rows[k + 1]
            if sec1 != sec0:
                gold["b%d" % k] = True
            elif p1 == p0:
                gold["b%d" % k] = False
        if True in gold.values() and False in gold.values():
            state = "\n".join("s%d: %s" % (k, rows[k][0]) for k in idx)
            questions = [{"type": "noul", "id": "b%d" % k, "instructions": "The topic changes between sentence s%d and sentence s%d." % (k, k + 1), "group": "w%d" % i}
                         for k in idx[:-1]]
            lines.append({"id": "docs-%d" % i, "request": {"model": "hivecore-decide", "state": state, "questions": questions}, "gold": gold})
        i += window // 3
    return lines


def invoice(path):
    """(text, fields) or None when the text has no readable total (the fixture set has one such scan)."""
    text = open(path, encoding="utf-8").read()
    vendor = text.strip().split("\n")[0].strip()
    no = re.search(r"Invoice No:\s*(\S+)", text)
    total = re.search(r"Total:\s*(?:₹|Rs\.?|INR)?\s*([\d,]+(?:\.\d+)?)", text)
    if not no or not total:
        return None
    return text, {"vendor": vendor, "invoiceNo": no.group(1), "total": float(total.group(1).replace(",", ""))}


def verdicts(fixtures):
    files = sorted(f for f in os.listdir(fixtures) if re.match(r"invoice_\d+\.txt$", f))
    invs = [i for i in (invoice(os.path.join(fixtures, f)) for f in files) if i]
    schema = {"type": "object", "properties": {"vendor": {"type": "string"}, "invoiceNo": {"type": "string"}, "total": {"type": "number"}}, "required": ["vendor", "invoiceNo", "total"]}
    lines = []
    for i, (text, good) in enumerate(invs):
        other = invs[(i + 1) % len(invs)][1]
        cases = [
            ("ok", good, True),
            ("total", dict(good, total=other["total"]), False),
            ("vendor", dict(good, vendor=other["vendor"]), False),
            ("schema", dict(good, total="n/a"), False),
        ]
        for tag, out, gold in cases:
            state = "Input:\n%s\nOutput:\n%s\nSchema:\n%s" % (text.strip(), json.dumps(out, sort_keys=True, separators=(",", ":")), json.dumps(schema, sort_keys=True, separators=(",", ":")))
            qid = "v%d.%s" % (i, tag)
            lines.append({"id": "invoice-%02d-%s" % (i + 1, tag), "schemaValid": tag != "schema",
                          "request": {"model": "hivecore-decide", "state": state, "questions": [{"type": "noul", "id": qid, "instructions": "This output is consistent with its input and with the required schema."}]},
                          "gold": {qid: gold}})
    return lines


def write(name, lines):
    with open(os.path.join(out_dir, name), "w", encoding="utf-8", newline="\n") as f:
        for l in lines:
            f.write(json.dumps(l, ensure_ascii=False, sort_keys=True) + "\n")
    print("%s: %d cases" % (name, len(lines)))


write("boundaries.jsonl", boundaries([os.path.join(root, "docs", f) for f in ("spec.md", "status.md", "gates.md", "seams.md")]))
write("verdicts.jsonl", verdicts(os.path.join(root, "node", "src", "test", "resources", "fixtures")))
