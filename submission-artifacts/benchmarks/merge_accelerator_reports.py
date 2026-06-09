#!/usr/bin/env python3
"""Merge per-accelerator benchmark reports into one artifact."""
from __future__ import annotations

import argparse
import json
import os
import time

EXPECTED = ("GPU", "CPU", "NPU")


def failed_lane(lane: str, message: str) -> dict:
    return {
        "accelerator": lane,
        "backend": "LITERT_LM",
        "strategy": f"{lane.lower()}_litertlm_benchmark",
        "modelPath": None,
        "measurementType": "token_generation",
        "medianTokensPerSecond": 0.0,
        "p95TokensPerSecond": 0.0,
        "medianPrefillTokensPerSecond": 0.0,
        "medianTtftMs": 0,
        "p95TtftMs": 0,
        "medianLatencyMs": 0,
        "state": "ERROR",
        "detail": None,
        "error": message,
        "runs": [],
    }


def load_report(path: str) -> dict | None:
    if not os.path.exists(path):
        return None
    with open(path) as handle:
        return json.load(handle)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--partials-dir", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    reports: dict[str, dict] = {}
    first_report = None
    for lane in EXPECTED:
        report = load_report(
            os.path.join(args.partials_dir, f"accelerator-comparison-report-{lane.lower()}.json")
        )
        if report and not first_report:
            first_report = report
        if report:
            reports[lane] = report

    merged = {
        "deviceModel": (first_report or {}).get("deviceModel", "unknown"),
        "generatedAtMs": int(time.time() * 1000),
        "modelPath": (first_report or {}).get(
            "modelPath",
            "/data/local/tmp/koru/models/gemma-4-e2b-it/gemma-4-E2B-it.litertlm",
        ),
        "npuModelPath": (first_report or {}).get(
            "npuModelPath",
            "/data/local/tmp/koru/models/gemma-4-e2b-it/gemma-4-E2B-it_Google_Tensor_G5.litertlm",
        ),
        "modelReadyForNativeAndroid": False,
        "note": (
            "GPU, CPU, and NPU use the official LiteRT-LM benchmark API. "
            "Each lane is isolated in a separate instrumentation process so native crashes are recorded per lane."
        ),
        "accelerators": [],
    }

    for lane in EXPECTED:
        report = reports.get(lane)
        if report:
            merged["accelerators"].extend(report.get("accelerators", []))
        else:
            merged["accelerators"].append(
                failed_lane(
                    lane,
                    "Instrumentation process did not produce a lane report; check logcat/tombstone for the native crash.",
                )
            )

    merged["modelReadyForNativeAndroid"] = any(
        lane.get("error") in (None, False)
        and isinstance(lane.get("medianTokensPerSecond"), (int, float))
        and lane.get("medianTokensPerSecond", 0) > 0
        for lane in merged["accelerators"]
    )

    os.makedirs(os.path.dirname(os.path.abspath(args.output)), exist_ok=True)
    with open(args.output, "w") as handle:
        json.dump(merged, handle, indent=2)
        handle.write("\n")
    print(f"Wrote merged report: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
