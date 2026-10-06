"""Turns k6 --summary-export files into a Markdown table (for the CI run summary and README).

Usage: python3 load-test/summarize.py <results-dir> <machine.txt>
Prints the Markdown table, appends it to $GITHUB_STEP_SUMMARY when running in GitHub Actions,
and prints one ::notice annotation per scenario so the numbers show on the run page.
"""
import json
import os
import pathlib
import sys

results = pathlib.Path(sys.argv[1])
machine = dict(
    line.split("=", 1) for line in pathlib.Path(sys.argv[2]).read_text().splitlines() if "=" in line
)
exit_codes = dict(
    line.split("=", 1) for line in (results / "exit-codes").read_text().splitlines() if "=" in line
)


def metric(data, name):
    return data["metrics"].get(name, {})


def ms(value):
    return "–" if value is None else f"{value:.1f} ms"


def pct(value):
    return "–" if value is None else f"{value * 100:.2f}%"


rows = []
for scenario in ("steady", "spike", "failover"):
    path = results / f"{scenario}.json"
    if not path.exists():
        continue
    data = json.loads(path.read_text())
    reqs = metric(data, "http_reqs")
    duration = metric(data, "http_req_duration")
    failed = metric(data, "http_req_failed")
    checks = metric(data, "checks")
    gateway = {}
    gateway_file = results / f"gateway-metrics-{scenario}.json"
    if gateway_file.exists():
        gateway = json.loads(gateway_file.read_text())
    codes = gateway.get("statusCodes", {})
    passed = exit_codes.get(f"{scenario}_exit") == "0"
    rows.append({
        "scenario": scenario,
        "requests": int(reqs.get("count", 0)),
        "rate": reqs.get("rate", 0),
        "p50": duration.get("med"),
        "p95": duration.get("p(95)"),
        "p99": duration.get("p(99)"),
        "failed": failed.get("value"),
        "checks": checks.get("value"),
        "codes": ", ".join(f"{c}: {n:,}" for c, n in sorted(codes.items())),
        "thresholds": "pass" if passed else "FAIL",
        "circuit": " / ".join(
            f"{e['backendId']} {e['from']}->{e['to']}" for e in reversed(gateway.get("circuitEvents", []))
        ) or "none",
    })

out = [
    "## Load test results",
    "",
    f"Machine: GitHub-hosted runner, {machine.get('cpu', '?')}, {machine.get('cores', '?')} vCPU, "
    f"{machine.get('memory', '?')} RAM. {machine.get('java', '')}. {machine.get('k6', '')}. "
    "k6 and the gateway (with its 3 embedded backends) share the same machine.",
    "",
    "| Scenario | Requests | Throughput | p50 | p95 | p99 | Failed | Checks passed | Status codes (gateway, incl. warm-up) | Thresholds |",
    "|---|---|---|---|---|---|---|---|---|---|",
]
for r in rows:
    out.append(
        f"| {r['scenario']} | {r['requests']:,} | {r['rate']:.0f} req/s | {ms(r['p50'])} | {ms(r['p95'])} | "
        f"{ms(r['p99'])} | {pct(r['failed'])} | {pct(r['checks'])} | {r['codes']} | {r['thresholds']} |"
    )
markdown = "\n".join(out)
print(markdown)
summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
if summary_path:
    with open(summary_path, "a") as fh:
        fh.write(markdown + "\n")

if rows and any(r["circuit"] != "none" for r in rows):
    out_circuit = ["", "Circuit breaker transitions recorded by the gateway during each run:", ""]
    out_circuit += [f"- **{r['scenario']}**: {r['circuit']}" for r in rows]
    extra = "\n".join(out_circuit)
    print(extra)
    if summary_path:
        with open(summary_path, "a") as fh:
            fh.write(extra + "\n")

print(f"::notice title=machine::{machine}")
for r in rows:
    print(
        f"::notice title=k6 {r['scenario']}::circuit [{r['circuit']}] {r['requests']} requests, {r['rate']:.0f} req/s, "
        f"p50 {ms(r['p50'])}, p95 {ms(r['p95'])}, p99 {ms(r['p99'])}, failed {pct(r['failed'])}, "
        f"checks {pct(r['checks'])}, codes [{r['codes']}], thresholds {r['thresholds']}"
    )
