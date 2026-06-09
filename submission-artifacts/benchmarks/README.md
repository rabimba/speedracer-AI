# Accelerator Benchmarks — GPU vs CPU vs NPU

Three tools, one honest story.

| File | What it does | Where it runs |
| :--- | :--- | :--- |
| `run_device_benchmark.sh` | Runs `AcceleratorComparisonInstrumentedTest` on a connected Pixel, pulls the report, renders it. **Source of real numbers.** | Your machine + Pixel |
| `render_benchmark_report.py` | Turns any report JSON into a Markdown table + SVG chart. Honest about PENDING / STATUS-ONLY lanes. | Anywhere (stdlib only) |
| `host_cpu_methodology_proxy.py` | A **synthetic host-CPU** workload that exercises the same warmup→TTFT→median/p95 pipeline. Proves the harness math. **Not Gemma, not GPU/NPU.** | Anywhere (stdlib only) |

## Current measured state (the honest version)

`accelerator-comparison-report.json` was measured on a **Google Pixel 10 (API 36)**
on **2026-06-08** (`modelReadyForNativeAndroid: true`), all lanes via the official
LiteRT-LM benchmark API, each lane isolated in its own instrumentation process:

| Lane | Runtime | Median tok/s | TTFT |
| :--- | :--- | ---: | ---: |
| GPU | LiteRT-LM | 10.3 | 1108 ms |
| CPU | LiteRT-LM | 6.9 | 8545 ms |
| **NPU** | **LiteRT-LM · Tensor G5** | **14.3** | **424 ms** |

**The NPU wins on both throughput (~2× CPU) and — the metric that matters for a
real-time coach — time-to-first-token (424 ms vs 1.1 s GPU / 8.5 s CPU).** The NPU lane
runs `gemma-4-E2B-it_Google_Tensor_G5.litertlm` through the packaged
`libLiteRtDispatch_GoogleTensor.so` dispatch runtime.

> **How it got here.** This lane started the day PENDING: the original `.litertlm` was a
> multimodal export rejected over `tf_lite_audio_adapter`. A **text-only re-export**
> made it native-ready; the Tensor-G5 artifact + dispatch library unblocked the NPU.
> The harness reported PENDING honestly until the model actually loaded — it never
> faked a number.

> Runner notes: the on-device instrumented test package is `com.trustableai.koru.debug.test`
> (`TEST_PKG`); the script `installDebug` + `installDebugAndroidTest`, stages/pushes the
> model, runs **one accelerator per process** (a native NPU crash can abort the process),
> then `merge_accelerator_reports.py` combines the per-lane reports.

## Quick start

```bash
# 1) Methodology proxy (runs anywhere; SYNTHETIC, not device numbers)
python3 host_cpu_methodology_proxy.py --runs 5
python3 render_benchmark_report.py host-cpu-proxy-report.json

# 2) Real device run (needs a Pixel + native-ready model)
./run_device_benchmark.sh 3
```
