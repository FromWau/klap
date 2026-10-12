#!/usr/bin/env python3
"""Prove a fixture's targets: strip every TDD-target @Ignore, run the module's tests, restore the files, and report
which tests failed. Every target should fail and nothing else should; a failure outside a *TargetsTest class is a
broken test, and a target that passes needs no @Ignore.

usage: unignore-check.py <repo> <tool> [--records]
  --records also strips the "Will not do" records, to see that each still fails for its stated reason.
"""
import glob
import os
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET


def strip_ignores(text, records):
    out, i, count = [], 0, 0
    while True:
        j = text.find("@Ignore(", i)
        if j < 0:
            out.append(text[i:])
            return "".join(out), count
        k, depth, quoted = j + len("@Ignore("), 1, False
        while depth:
            c = text[k]
            if c == '"' and text[k - 1] != "\\":
                quoted = not quoted
            elif not quoted and c == "(":
                depth += 1
            elif not quoted and c == ")":
                depth -= 1
            k += 1
        annotation = text[j:k]
        if "TDD target" in annotation or (records and "Will not do" in annotation):
            # The annotation's own line goes; its indent, kept in text[i:j], now leads the next line's `@Test`.
            out.append(text[i:j])
            count += 1
            while k < len(text) and text[k] in " \n":
                k += 1
        else:
            out.append(text[i:k])
        i = k


def main():
    repo, tool = sys.argv[1], sys.argv[2]
    records = "--records" in sys.argv
    module = os.path.join(repo, "example", tool)
    sources = glob.glob(os.path.join(module, "src/test/**/*.kt"), recursive=True)
    backup = tempfile.mkdtemp(prefix=f"unignore-{tool}-")
    stripped = 0
    for path in sources:
        shutil.copy2(path, os.path.join(backup, path.replace("/", "%")))
        text = open(path).read()
        new, count = strip_ignores(text, records)
        stripped += count
        if count:
            open(path, "w").write(new)
    shutil.rmtree(os.path.join(module, "build/test-results"), ignore_errors=True)
    try:
        subprocess.run(
            ["./gradlew", "--offline", f":example:{tool}:test", "--continue", "-q"],
            cwd=repo, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
    finally:
        for path in sources:
            shutil.copy2(os.path.join(backup, path.replace("/", "%")), path)
        shutil.rmtree(backup)
    total, failed = 0, []
    for report in glob.glob(os.path.join(module, "build/test-results/test/TEST-*.xml")):
        for case in ET.parse(report).getroot().iter("testcase"):
            total += 1
            if case.find("failure") is not None or case.find("error") is not None:
                failed.append((case.get("classname").rsplit(".", 1)[-1], case.get("name")))
    print(f"stripped {stripped} @Ignore, ran {total} tests, {len(failed)} failed; files restored")
    for cls, name in sorted(failed):
        marker = "" if cls.endswith("TargetsTest") else "   <- NOT a target class"
        print(f"  {cls}: {name}{marker}")
    if total == 0:
        print("no test results: the module did not compile; run the gradle task by hand to see why")


if __name__ == "__main__":
    main()
