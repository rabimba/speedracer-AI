#!/usr/bin/env python3
"""HOST-CPU SYNTHETIC PROXY for the token-generation benchmark pipeline.

================================================================================
  THIS IS NOT GEMMA. NOT THE PIXEL. NOT GPU. NOT NPU.
  It runs a *synthetic* per-token workload on whatever host CPU executes it, to
  exercise and validate the SAME metric pipeline the real device benchmark uses
  (warmup pass -> per-token loop -> time-to-first-token -> median/p95 tok/s).
  Use it to prove the harness math and to produce a placeholder chart. Real
  GPU/CPU/NPU numbers come ONLY from run_device_benchmark.sh on a Pixel with a
  native-ready model.
================================================================================

The synthetic "model" does a fixed amount of float multiply-accumulate work per
token (a toy d x d mat-vec), with a fixed seed for determinism, so repeated runs
on the same machine are stable. Output JSON matches the accelerator-comparison
schema so render_benchmark_report.py can chart it.
"""
from __future__ import annotations

import argparse
import json
import os
import platform
import random
import statistics
import time

HERE = os.path.dirname(os.path.abspath(__file__))


def synth_token_work(dim: int, vec: list[float], mat: list[list[float]]) -> float:
    """One toy 'token': a dim x dim mat-vec. Returns a scalar to prevent elision."""
    acc = 0.0
    for row in mat:
        s = 0.0
        for j in range(dim):
            s += row[j] * vec[j]
        acc += s
    return acc


def run_once(dim: int, out_tokens: int, prompt_tokens: int) -> dict:
    rng = random.Random(17)
    vec = [rng.random() for _ in range(dim)]
    mat = [[rng.random() for _ in range(dim)] for _ in range(dim)]

    start = time.perf_counter()
    ttft_ms = 0.0
    for t in range(out_tokens):
        synth_token_work(dim, vec, mat)
        if t == 0:
            ttft_ms = (time.perf_counter() - start) * 1000.0
    latency_ms = (time.perf_counter() - start) * 1000.0
    tps = out_tokens / (latency_ms / 1000.0) if latency_ms > 0 else 0.0
    return {
        "outputTokens": out_tokens,
        "promptTokens": prompt_tokens,
        "latencyMs": round(latency_ms, 2),
        "ttftMs": round(ttft_ms, 2),
        "tokensPerSecond": round(tps, 2),
    }


def pct(values: list[float], q: float) -> float:
    if not values:
        return 0.0
    s = sorted(values)
    idx = min(len(s) - 1, max(0, int((len(s) - 1) * q)))
    return s[idx]


def main() -> int:
    ap = argparse.ArgumentParser(description="Host-CPU synthetic proxy for the token-gen pipeline.")
    ap.add_argument("--dim", type=int, default=256, help="toy hidden dim (per-token work size)")
    ap.add_argument("--tokens", type=int, default=64, help="output tokens per run")
    ap.add_argument("--prompt-tokens", type=int, default=48)
    ap.add_argument("--runs", type=int, default=5)
    ap.add_argument("--warmup", action="store_true", default=True)
    ap.add_argument("--out", default=os.path.join(HERE, "host-cpu-proxy-report.json"))
    args = ap.parse_args()

    if args.warmup:
        run_once(args.dim, max(8, args.tokens // 4), args.prompt_tokens)

    runs = [run_once(args.dim, args.tokens, args.prompt_tokens) for _ in range(args.runs)]
    for i, r in enumerate(runs):
        r["runIndex"] = i
    tps = [r["tokensPerSecond"] for r in runs]
    ttft = [r["ttftMs"] for r in runs]
    lat = [r["latencyMs"] for r in runs]

    report = {
        "deviceModel": f"HOST CPU PROXY — {platform.processor() or platform.machine()} ({platform.system()})",
        "generatedAtMs": int(time.time() * 1000),
        "modelPath": "synthetic://toy-matvec",
        "modelReadyForNativeAndroid": False,
        "note": (
            "SYNTHETIC HOST-CPU PROXY. NOT Gemma, NOT the Pixel, NOT GPU/NPU. "
            "Validates the warmup/TTFT/median/p95 token-rate pipeline only. "
            "Real GPU/CPU/NPU numbers require run_device_benchmark.sh on a Pixel."
        ),
        "accelerators": [
            {
                "accelerator": "HOST_CPU_SYNTHETIC",
                "backend": "PYTHON_TOY_MATVEC",
                "measurementType": "host_cpu_synthetic_proxy",
                "strategy": f"dim={args.dim}",
                "medianTokensPerSecond": round(statistics.median(tps), 2),
                "p95TokensPerSecond": round(pct(tps, 0.95), 2),
                "medianTtftMs": round(statistics.median(ttft), 2),
                "p95TtftMs": round(pct(ttft, 0.95), 2),
                "medianLatencyMs": round(statistics.median(lat), 2),
                "error": None,
                "runs": runs,
            }
        ],
    }
    with open(args.out, "w") as f:
        json.dump(report, f, indent=2)
    print(json.dumps(report, indent=2))
    print(f"\nWrote: {args.out}")
    print("Reminder: this is a HOST-CPU SYNTHETIC PROXY, not a device/model measurement.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
