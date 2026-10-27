#!/usr/bin/env python3
"""Summarise the Gradle unit-test XML results and fail when nothing ran.

Usage: summarize-unit-tests.py <results-dir>
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET


def main() -> int:
    results_dir = sys.argv[1] if len(sys.argv) > 1 else "app/build/test-results/testFdroidDebugUnitTest"
    files = sorted(glob.glob(os.path.join(results_dir, "*.xml")))
    total = failures = errors = skipped = 0
    for path in files:
        root = ET.parse(path).getroot()
        tests = int(root.get("tests", 0))
        failed = int(root.get("failures", 0))
        errored = int(root.get("errors", 0))
        skipped_count = int(root.get("skipped", 0))
        total += tests
        failures += failed
        errors += errored
        skipped += skipped_count
        print("%-52s tests=%d failures=%d errors=%d skipped=%d" % (root.get("name"), tests, failed, errored, skipped_count))
    print("TOTAL tests=%d failures=%d errors=%d skipped=%d" % (total, failures, errors, skipped))
    # Emitted as a workflow annotation so the executed count is readable from the API/UI.
    print("::notice::unit tests executed: %d (failures=%d errors=%d skipped=%d)" % (total, failures, errors, skipped))
    if total == 0:
        print("no unit tests were executed")
        return 1
    if failures or errors:
        print("unit test failures detected")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
