#!/usr/bin/env python3
"""Parse connected-test JUnit XML into a JSON result map and optionally diff it against a baseline.

Usage:
  parse_results.py --xml-dir <dir> --out <results.json> [--baseline baseline/test-results.json --diff-md <diff.md>]
"""
import argparse
import glob
import json
import os
import sys
import xml.etree.ElementTree as ET


def parse(xml_dir):
    results = {}
    devices = set()
    for path in sorted(glob.glob(os.path.join(xml_dir, "**", "*.xml"), recursive=True)):
        root = ET.parse(path).getroot()
        suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
        for suite in suites:
            for prop in suite.iter("property"):
                if prop.get("name") == "device":
                    devices.add(prop.get("value"))
            for case in suite.findall("testcase"):
                name = f"{case.get('classname')}#{case.get('name')}"
                failure = case.find("failure")
                if failure is None:
                    failure = case.find("error")
                if failure is not None:
                    msg = (failure.get("message") or failure.text or "").strip().splitlines()
                    results[name] = {"status": "failed", "time": float(case.get("time") or 0),
                                     "message": msg[0][:300] if msg else ""}
                elif case.find("skipped") is not None:
                    results[name] = {"status": "skipped", "time": float(case.get("time") or 0)}
                else:
                    results[name] = {"status": "passed", "time": float(case.get("time") or 0)}
    return results, sorted(devices)


def summary(results):
    s = {"total": len(results), "passed": 0, "failed": 0, "skipped": 0}
    for r in results.values():
        s[r["status"]] += 1
    return s


def diff(baseline, current):
    rows = []
    regressions = []
    for name in sorted(set(baseline) | set(current)):
        b = baseline.get(name, {}).get("status", "absent")
        c = current.get(name, {}).get("status", "absent")
        known = baseline.get(name, {}).get("known_failure")
        if b == "passed" and c != "passed":
            verdict = "REGRESSION"
            regressions.append(name)
        elif b == "absent":
            verdict = "new"
        elif b != "passed" and c == "passed":
            verdict = "fixed"
        elif b == c:
            verdict = "same" + (" (known failure)" if known else "")
        else:
            verdict = "changed"
        rows.append((name, b, c, verdict))
    return rows, regressions


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--xml-dir", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--baseline")
    ap.add_argument("--diff-md")
    args = ap.parse_args()

    results, devices = parse(args.xml_dir)
    if not results:
        print(f"no test cases found under {args.xml_dir}", file=sys.stderr)
        return 2
    doc = {"devices": devices, "summary": summary(results), "tests": results}
    with open(args.out, "w") as f:
        json.dump(doc, f, indent=2, sort_keys=True)
    print(json.dumps(doc["summary"]), devices)

    if args.baseline:
        with open(args.baseline) as f:
            base = json.load(f)["tests"]
        rows, regressions = diff(base, results)
        lines = ["| Test | Baseline | Current | Verdict |", "|---|---|---|---|"]
        lines += [f"| `{n}` | {b} | {c} | {v} |" for n, b, c, v in rows]
        bs, cs = summary(base), summary(results)
        header = (f"Baseline: {bs['passed']}/{bs['total']} passed, {bs['failed']} failed, {bs['skipped']} skipped. "
                  f"Current: {cs['passed']}/{cs['total']} passed, {cs['failed']} failed, {cs['skipped']} skipped. "
                  f"Regressions: {len(regressions)}.\n\n")
        if args.diff_md:
            with open(args.diff_md, "w") as f:
                f.write(header + "\n".join(lines) + "\n")
        print(header.strip())
        for r in regressions:
            print("REGRESSION:", r)
        return 1 if regressions else 0
    return 0


if __name__ == "__main__":
    sys.exit(main())
