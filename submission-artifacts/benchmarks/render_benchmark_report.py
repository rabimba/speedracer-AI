#!/usr/bin/env python3
"""Render an accelerator comparison report (GPU / CPU / NPU) to Markdown + SVG.

This reads the JSON produced by ``AcceleratorComparisonInstrumentedTest`` (schema
with a top-level ``accelerators`` array) or by ``LlmBenchmarkRunner.writeReport``
(``strategies`` array) and emits:

  * a Markdown table  (``<stem>.md``)
  * a self-contained SVG bar chart of median tokens/sec  (``<stem>.svg``)

It is deliberately honest: lanes that did not measure (model not native-ready, or
NPU reported as status-only) are rendered as PENDING / UNAVAILABLE rather than as a
zero that could be mistaken for "slow". No numbers are invented — everything comes
straight from the input JSON.

Usage:
    python3 render_benchmark_report.py [report.json] [--out DIR]
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_REPORT = os.path.join(HERE, "accelerator-comparison-report.json")


def load_lanes(report: dict) -> list[dict]:
    """Normalize either schema into a flat list of lane dicts."""
    lanes = report.get("accelerators") or report.get("strategies") or []
    out = []
    for lane in lanes:
        name = lane.get("accelerator") or lane.get("backend") or lane.get("strategy") or "?"
        out.append(
            {
                "name": str(name).upper(),
                "backend": lane.get("backend", ""),
                "measurement": lane.get("measurementType", ""),
                "median_tps": lane.get("medianTokensPerSecond"),
                "p95_tps": lane.get("p95TokensPerSecond"),
                "median_ttft_ms": lane.get("medianTtftMs"),
                "median_latency_ms": lane.get("medianLatencyMs"),
                "state": lane.get("state"),
                "detail": lane.get("detail"),
                "error": lane.get("error"),
            }
        )
    return out


def lane_status(lane: dict) -> str:
    if lane["measurement"] == "status_only" or lane["state"]:
        return f"STATUS-ONLY ({lane.get('state') or 'n/a'})"
    if lane["error"]:
        return "PENDING (not measured)"
    if lane["median_tps"] in (None, 0, 0.0):
        return "PENDING (no tokens generated)"
    return "MEASURED"


def fmt(value, suffix="") -> str:
    if value is None:
        return "—"
    if isinstance(value, float):
        return f"{value:.1f}{suffix}"
    return f"{value}{suffix}"


def to_markdown(report: dict, lanes: list[dict]) -> str:
    lines = []
    lines.append("# Accelerator Comparison — GPU vs CPU vs NPU\n")
    lines.append(f"- **Device:** {report.get('deviceModel', 'unknown')}")
    gen = report.get("generatedAtMs")
    if gen:
        ts = datetime.fromtimestamp(gen / 1000, tz=timezone.utc).isoformat()
        lines.append(f"- **Generated:** {ts}")
    lines.append(f"- **Model:** `{report.get('modelPath', 'n/a')}`")
    if report.get("npuModelPath"):
        lines.append(f"- **NPU model:** `{report['npuModelPath']}`")
    if "modelReadyForNativeAndroid" in report:
        lines.append(f"- **Model native-ready:** {report['modelReadyForNativeAndroid']}")
    if report.get("note"):
        lines.append(f"- **Note:** {report['note']}")
    lines.append("")
    lines.append("| Lane | Backend | Status | Median tok/s | p95 tok/s | Median TTFT | Median latency |")
    lines.append("| :--- | :--- | :--- | ---: | ---: | ---: | ---: |")
    for lane in lanes:
        lines.append(
            "| {name} | {backend} | {status} | {mtps} | {ptps} | {ttft} | {lat} |".format(
                name=lane["name"],
                backend=lane["backend"] or "—",
                status=lane_status(lane),
                mtps=fmt(lane["median_tps"]),
                ptps=fmt(lane["p95_tps"]),
                ttft=fmt(lane["median_ttft_ms"], " ms"),
                lat=fmt(lane["median_latency_ms"], " ms"),
            )
        )
    lines.append("")
    for lane in lanes:
        if lane["error"] or lane["detail"]:
            lines.append(f"- **{lane['name']}** — {lane.get('detail') or ''} {('· ' + lane['error']) if lane['error'] else ''}".strip())
    lines.append("")
    return "\n".join(lines)


def to_svg(report: dict, lanes: list[dict]) -> str:
    """Pure-Python SVG bar chart of median tok/s, honest about pending lanes."""
    W, H = 760, 420
    pad_l, pad_b, pad_t = 70, 70, 90
    plot_w = W - pad_l - 30
    plot_h = H - pad_b - pad_t
    measured = [l["median_tps"] for l in lanes if isinstance(l["median_tps"], (int, float)) and l["median_tps"]]
    vmax = max(measured) if measured else 1.0
    colors = {"GPU": "#2563eb", "CPU": "#0ea5e9", "NPU": "#7c3aed"}
    n = max(len(lanes), 1)
    slot = plot_w / n
    bar_w = min(120, slot * 0.5)

    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" font-family="-apple-system,Segoe UI,Roboto,sans-serif">',
        f'<rect width="{W}" height="{H}" rx="14" fill="#0b1020"/>',
        f'<text x="{W/2}" y="34" fill="#e8edff" font-size="20" font-weight="700" text-anchor="middle">On-device Token Generation — GPU vs CPU vs NPU</text>',
        f'<text x="{W/2}" y="58" fill="#9fb0d6" font-size="13" text-anchor="middle">{report.get("deviceModel","unknown")} · median tokens/sec</text>',
    ]
    # baseline
    base_y = pad_t + plot_h
    parts.append(f'<line x1="{pad_l}" y1="{base_y}" x2="{W-30}" y2="{base_y}" stroke="#2a3556" stroke-width="1.5"/>')

    for i, lane in enumerate(lanes):
        cx = pad_l + slot * i + slot / 2
        x = cx - bar_w / 2
        color = colors.get(lane["name"], "#64748b")
        v = lane["median_tps"] if isinstance(lane["median_tps"], (int, float)) else None
        if v and v > 0:
            bh = max(4, plot_h * (v / vmax))
            y = base_y - bh
            parts.append(f'<rect x="{x:.1f}" y="{y:.1f}" width="{bar_w:.1f}" height="{bh:.1f}" rx="6" fill="{color}"/>')
            parts.append(f'<text x="{cx:.1f}" y="{y-8:.1f}" fill="#e8edff" font-size="14" font-weight="700" text-anchor="middle">{v:.1f}</text>')
        else:
            # pending / unavailable lane: hatched outline + label
            bh = plot_h * 0.55
            y = base_y - bh
            parts.append(
                f'<rect x="{x:.1f}" y="{y:.1f}" width="{bar_w:.1f}" height="{bh:.1f}" rx="6" '
                f'fill="none" stroke="{color}" stroke-width="2" stroke-dasharray="6 5" opacity="0.7"/>'
            )
            label = "STATUS-ONLY" if (lane["measurement"] == "status_only" or lane["state"]) else "PENDING"
            parts.append(f'<text x="{cx:.1f}" y="{(y+bh/2):.1f}" fill="{color}" font-size="13" font-weight="700" text-anchor="middle" transform="rotate(-90 {cx:.1f} {(y+bh/2):.1f})">{label}</text>')
        parts.append(f'<text x="{cx:.1f}" y="{base_y+24:.1f}" fill="#cdd8f5" font-size="14" font-weight="600" text-anchor="middle">{lane["name"]}</text>')
        sub = lane["backend"] or ""
        parts.append(f'<text x="{cx:.1f}" y="{base_y+42:.1f}" fill="#7e8db5" font-size="11" text-anchor="middle">{sub}</text>')

    if not measured:
        parts.append(f'<text x="{W/2}" y="{pad_t+plot_h/2}" fill="#ffb454" font-size="13" text-anchor="middle">No measured lanes yet — run run_device_benchmark.sh on a Pixel with a native-ready model.</text>')
    parts.append("</svg>")
    return "\n".join(parts)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("report", nargs="?", default=DEFAULT_REPORT, help="path to report JSON")
    ap.add_argument("--out", default=None, help="output directory (default: alongside report)")
    args = ap.parse_args()

    if not os.path.exists(args.report):
        print(f"error: report not found: {args.report}", file=sys.stderr)
        return 2
    with open(args.report) as f:
        report = json.load(f)
    lanes = load_lanes(report)
    out_dir = args.out or os.path.dirname(os.path.abspath(args.report))
    os.makedirs(out_dir, exist_ok=True)
    stem = os.path.splitext(os.path.basename(args.report))[0]
    md_path = os.path.join(out_dir, f"{stem}.md")
    svg_path = os.path.join(out_dir, f"{stem}.svg")

    with open(md_path, "w") as f:
        f.write(to_markdown(report, lanes))
    with open(svg_path, "w") as f:
        f.write(to_svg(report, lanes))

    print(to_markdown(report, lanes))
    print(f"\nWrote: {md_path}\nWrote: {svg_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
