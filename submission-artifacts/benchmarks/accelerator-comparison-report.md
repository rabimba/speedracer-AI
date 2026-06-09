# Accelerator Comparison — GPU vs CPU vs NPU

- **Device:** Google Pixel 10 (API 36)
- **Generated:** 2026-06-08T22:24:25.345000+00:00
- **Model:** `/data/local/tmp/koru/models/gemma-4-e2b-it/gemma-4-E2B-it.litertlm`
- **NPU model:** `/data/local/tmp/koru/models/gemma-4-e2b-it/gemma-4-E2B-it_Google_Tensor_G5.litertlm`
- **Model native-ready:** True
- **Note:** GPU, CPU, and NPU use the official LiteRT-LM benchmark API. Each lane is isolated in a separate instrumentation process so native crashes are recorded per lane.

| Lane | Backend | Status | Median tok/s | p95 tok/s | Median TTFT | Median latency |
| :--- | :--- | :--- | ---: | ---: | ---: | ---: |
| GPU | LITERT_LM | MEASURED | 10.3 | 10.3 | 1108 ms | 42085 ms |
| CPU | LITERT_LM | MEASURED | 6.9 | 6.9 | 8545 ms | 28660 ms |
| NPU | LITERT_LM | MEASURED | 14.3 | 14.3 | 424 ms | 14128 ms |

