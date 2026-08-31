# Cortex v54 V2 Primary Device Acceptance

Validated on a real Android device on 2026-08-29 using the locally signed update-in-place APK from `build/v2-brain-primary-v54`.

## Installed build

- Package: `com.kareem.cortex`
- versionCode: `54`
- versionName: `2.0-v54`
- Cognitive schema DB version: `7`
- Cognitive schema revision: `cognitive_004`
- Default authority mode in source: `V2_PRIMARY`
- Local model runtime state: `ready`

The update installed successfully with `pm install -r`, launched successfully, retained app data, and produced no crash-buffer entry during the clean-start check.

## Exact single-signal provenance proof

Controlled notification signal:

- raw signal id: `8718`
- thread id: `352`
- disposition: `EVENT`
- cognitive state: `DERIVED`
- cognitive version: `cognitive_v2_primary_001`
- final reason: `V2 primary accepted local result at confidence 0.82 via PRIMARY`

Authoritative local model run:

- model run id: `452`
- role: `cognitive_authority`
- provider: `local`
- model: `Qwen3-1.7B Q4_K_M`
- route: `cognitive_v2_primary`
- state: `complete`
- confidence: `0.82`
- queue wait: `0 ms`
- native total: `8078 ms`
- total: `8080 ms`
- generated tokens: `9`
- wire schema: `fast_cognitive_001`
- `output_json.signal_id = 8718`

Relevant provenance links:

- `model_run 452 --authoritative_evaluated--> raw_signal 8718`
- `raw_signal 8718 --supports--> derived 376`
- `model_run 452 --generated--> derived 376`
- `raw_signal 8718 --member_of--> thread 352`

Legacy model evaluations for signal 8718: `0`.

## Ten-case primary authority gate

A ten-signal controlled notification benchmark covered English, Arabic, and mixed Arabic/English inputs.

| Case | Signal | Disposition | Model run | Total ms |
| --- | ---: | --- | ---: | ---: |
| 01 | 8719 | ACTION | 453 | 5311 |
| 02 | 8720 | WAITING | 454 | 4524 |
| 03 | 8721 | EVENT | 455 | 7141 |
| 04 | 8722 | CONTENT | 456 | 4755 |
| 05 | 8723 | CONTENT | 457 | 6723 |
| 06 | 8724 | ACTION | 458 | 4867 |
| 07 | 8725 | CONTENT | 459 | 4929 |
| 08 | 8726 | ACTION | 460 | 7807 |
| 09 | 8727 | CONTENT | 461 | 7871 |
| 10 | 8728 | EVENT | 462 | 8119 |

Every case had:

- cognitive state `DERIVED`
- cognitive version `cognitive_v2_primary_001`
- route `cognitive_v2_primary`
- model run state `complete`
- confidence `0.82`
- queue wait `0 ms`
- wire schema `fast_cognitive_001`

Aggregate gate results:

- captured: `10/10`
- primary complete: `10/10`
- legacy model evaluations: `0`
- authoritative provenance: `10/10`
- fast cognitive contract: `10/10`
- P50 total latency: `5311 ms`
- P95 total latency: `8119 ms`
- P95 queue latency: `0 ms`
- database integrity: `ok`

Acceptance thresholds:

- P50 <= 8000 ms: PASS
- P95 <= 12000 ms: PASS
- queue P95 < 500 ms: PASS

Final device gate:

`CORTEX_V54_PRIMARY_10_CASE_GATE_PASS`

## Conclusion

For meaningful signals in this validated build, local Qwen V2 is the primary cognitive authority. The deterministic hard-noise gate remains ahead of the model, and Legacy remains a fallback/safety path rather than a competing authority path. The real-device benchmark observed no Legacy model evaluation for the ten primary test signals.
