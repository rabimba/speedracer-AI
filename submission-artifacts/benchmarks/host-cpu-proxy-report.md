# Accelerator Comparison — GPU vs CPU vs NPU

- **Device:** HOST CPU PROXY — aarch64 (Linux)
- **Generated:** 2026-06-08T17:42:21.047000+00:00
- **Model:** `synthetic://toy-matvec`
- **Model native-ready:** False
- **Note:** SYNTHETIC HOST-CPU PROXY. NOT Gemma, NOT the Pixel, NOT GPU/NPU. Validates the warmup/TTFT/median/p95 token-rate pipeline only. Real GPU/CPU/NPU numbers require run_device_benchmark.sh on a Pixel.

| Lane | Backend | Status | Median tok/s | p95 tok/s | Median TTFT | Median latency |
| :--- | :--- | :--- | ---: | ---: | ---: | ---: |
| HOST_CPU_SYNTHETIC | PYTHON_TOY_MATVEC | MEASURED | 1534.1 | 1537.8 | 0.7 ms | 31.3 ms |

