#!/usr/bin/env python3
"""
Scan JUnit XML test-result artifacts downloaded from CI and produce a
markdown summary of error and failure patterns.

Usage:
    python3 summarize-error-logs.py <artifacts-dir> <output-dir>

<artifacts-dir> must contain one sub-directory per CI run (named by run ID).
Each sub-directory is the unpacked content of the 'gradle-reports' artifact,
which typically has the structure:
    <run-id>/build/test-results/<task>/TEST-*.xml
"""

import os
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from datetime import date


def parse_junit_xml(filepath, run_id):
    """Return a list of failure/error records from a JUnit XML file."""
    records = []
    try:
        tree = ET.parse(filepath)
        root = tree.getroot()
        if root.tag == "testsuites":
            suites = list(root.iter("testsuite"))
        elif root.tag == "testsuite":
            suites = [root]
        else:
            return records

        for suite in suites:
            suite_name = suite.get("name", "")
            for testcase in suite.findall("testcase"):
                classname = testcase.get("classname", suite_name)
                testname = testcase.get("name", "")
                for kind in ("failure", "error"):
                    node = testcase.find(kind)
                    if node is not None:
                        raw = (node.get("message") or node.text or "").strip()
                        records.append(
                            {
                                "run_id": run_id,
                                "classname": classname,
                                "testname": testname,
                                "kind": kind,
                                "message": raw[:300],
                            }
                        )
    except ET.ParseError:
        pass
    return records


def fingerprint(record):
    """Stable key used to group similar failures across runs."""
    return f"{record['classname']}#{record['testname']}"


def run_id_sort_key(run_id):
    """Numeric sort key for run IDs (GitHub run IDs are integers)."""
    return int(run_id) if run_id.isdigit() else 0


def collect_records(artifacts_dir):
    """Walk artifacts_dir and return (run_dirs, all_records)."""
    if not os.path.isdir(artifacts_dir):
        return [], []

    run_dir_names = sorted(
        [d for d in os.listdir(artifacts_dir) if os.path.isdir(os.path.join(artifacts_dir, d))],
        key=run_id_sort_key,
    )

    all_records = []
    for run_dir_name in run_dir_names:
        run_path = os.path.join(artifacts_dir, run_dir_name)
        for dirpath, _, filenames in os.walk(run_path):
            for filename in filenames:
                if filename.endswith(".xml") and filename.startswith("TEST-"):
                    filepath = os.path.join(dirpath, filename)
                    records = parse_junit_xml(filepath, run_dir_name)
                    all_records.extend(records)

    return run_dir_names, all_records


def build_report(run_dirs, all_records):
    """Return the full markdown report as a string."""
    today = date.today().isoformat()

    lines = [
        "# Error Log Review",
        "",
        f"Review date: {today}",
        f"Analyzed CI runs: {len(run_dirs)}",
        "",
    ]

    if run_dirs:
        lines += [
            f"Run IDs reviewed: {', '.join(run_dirs)}",
            "",
        ]

    groups = defaultdict(list)
    for r in all_records:
        groups[fingerprint(r)].append(r)

    sorted_groups = sorted(
        groups.items(),
        key=lambda item: (
            -len(item[1]),
            -max(run_id_sort_key(r["run_id"]) for r in item[1]),
        ),
    )

    if not sorted_groups:
        lines += [
            "## Result",
            "",
            "No test failures or errors found across the reviewed CI runs. ✅",
            "",
            "All analyzed runs completed without recorded test failures.",
        ]
    else:
        lines += [
            f"Distinct failure patterns: **{len(sorted_groups)}**",
            f"Total failure occurrences: **{len(all_records)}**",
            "",
            "## Top Failures by Occurrence Count",
            "",
        ]

        for fp, records in sorted_groups[:20]:
            count = len(records)
            most_recent_run = max(records, key=lambda r: run_id_sort_key(r["run_id"]))["run_id"]
            kinds = sorted(set(r["kind"] for r in records))
            sample_msg = records[-1]["message"] or "(no message)"
            sample_display = sample_msg[:200] + ("…" if len(sample_msg) > 200 else "")

            lines += [
                f"### `{fp}`",
                "",
                f"| Field | Value |",
                f"|---|---|",
                f"| Type | {' / '.join(kinds)} |",
                f"| Occurrences | {count} |",
                f"| Most recent run ID | {most_recent_run} |",
                f"| Sample message | `{sample_display}` |",
                "",
            ]

    lines += [
        "---",
        "",
        "## How to Interpret and Act on Findings",
        "",
        "| Signal | Suggested action |",
        "|---|---|",
        "| High occurrence count | Likely a persistent or flaky test — investigate root cause or file a tracking issue. |",
        "| High run ID (recent) | May indicate a regression; check the commits between the last-green and failing run. |",
        "| `error` kind | The test threw an unexpected exception — look for infrastructure or setup problems. |",
        "| `failure` kind | An assertion failed — review the test expectation vs. actual behavior. |",
        "",
        "**Investigate a specific run:**",
        "",
        "```bash",
        "gh run view <run-id> --repo bertramkranz/PersonalAgent",
        "gh run download <run-id> --name gradle-reports",
        "```",
        "",
        "**Flaky tests** should be tracked in the issue tracker and either fixed or annotated with a known-issue label.",
        "",
        "_This report is auto-generated by [`.github/workflows/error-log-review.yml`](../../.github/workflows/error-log-review.yml)._",
    ]

    return "\n".join(lines) + "\n"


def main():
    if len(sys.argv) != 3:
        print(f"Usage: {sys.argv[0]} <artifacts-dir> <output-dir>", file=sys.stderr)
        sys.exit(2)

    artifacts_dir = sys.argv[1]
    output_dir = sys.argv[2]

    run_dirs, all_records = collect_records(artifacts_dir)
    report = build_report(run_dirs, all_records)

    os.makedirs(output_dir, exist_ok=True)
    report_path = os.path.join(output_dir, "README.md")
    with open(report_path, "w", encoding="utf-8") as fh:
        fh.write(report)

    groups = defaultdict(list)
    for r in all_records:
        groups[f"{r['classname']}#{r['testname']}"].append(r)

    print(f"Report written to {report_path}")
    print(
        f"Analyzed {len(run_dirs)} run(s) — "
        f"{len(all_records)} failure record(s) across {len(groups)} distinct pattern(s)."
    )


if __name__ == "__main__":
    main()
