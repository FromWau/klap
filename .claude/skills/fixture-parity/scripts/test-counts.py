#!/usr/bin/env python3
"""Count tests, skips and failures from every JUnit XML report under a checkout, per module and in total, and name
each failing test. Gradle's quiet output prints no counts, and a report left from an earlier run counts as a result,
so delete build/test-results before the run you are counting.

usage: test-counts.py <checkout>
"""
import collections
import glob
import os
import sys
import xml.etree.ElementTree as ET

root = sys.argv[1]
per = collections.defaultdict(lambda: [0, 0, 0])
failures = []
for report in glob.glob(os.path.join(root, "**/build/test-results/**/TEST-*.xml"), recursive=True):
    rel = os.path.relpath(report, root)
    module = rel.split("/build/")[0] + ":" + rel.split("test-results/")[1].split("/")[0]
    suite = ET.parse(report).getroot()
    per[module][0] += int(suite.get("tests"))
    per[module][1] += int(suite.get("skipped"))
    per[module][2] += int(suite.get("failures")) + int(suite.get("errors"))
    for case in suite.iter("testcase"):
        bad = case.find("failure") if case.find("failure") is not None else case.find("error")
        if bad is not None:
            message = (bad.get("message") or "").splitlines()[0][:200] if bad.get("message") else ""
            failures.append(f"{module} {case.get('classname').rsplit('.', 1)[-1]}: {case.get('name')} :: {message}")
for module, (tests, skipped, failed) in sorted(per.items()):
    print(f"{module:45} {tests:5} tests {skipped:3} skipped {failed:3} failed")
totals = [sum(v[i] for v in per.values()) for i in range(3)]
print(f"{'total':45} {totals[0]:5} tests {totals[1]:3} skipped {totals[2]:3} failed")
for line in failures:
    print("FAILED", line)
