# Cortex Cognitive Major Update — Acceptance Gate

This branch is not production-ready merely because it compiles or falls back safely.

## Definition of Done

A real phone signal must complete this path on the target device:

`notification -> Cortex capture -> V2 authority -> Qwen3-1.7B -> valid fast cognitive result -> accepted before timeout -> persisted cognitive state -> no Legacy mutation for the same signal`

After the controlled authority test, the canary percentage must be restored to the normal 5% value.

## Required gates

- Valid fast JSON / cognitive contract: >= 99%.
- No classification regression across ACTION, WAITING, EVENT, CONTENT and CONTEXT cases, including mixed Arabic/English.
- Authority queue P95: < 500 ms.
- Authority total P50: <= 8 s.
- Authority total P95: <= 12 s.
- Authority timeouts: 0/10 benchmark cases.
- Shadow blocks authority: 0.
- Generated tokens P50: <= 70.
- Generated tokens max: <= 96.
- Non-empty `<think>` reasoning output: 0.
- Runtime references to the old Qwen3-4B identity: 0, except explicit migration cleanup / historical documentation.
- Real controlled E2E: V2 model run `complete`, no Legacy fallback.
- Final normal runtime: authority canary enabled at 5%.

## Protected semantics

This stabilization work must not change:

- RawSignalStore authority semantics.
- CognitiveStore persistence semantics.
- DB schema.
- Canary percentage default.
- PrimeBriefStore / Pulse / Deep Brain semantics.

No merge is allowed until all applicable gates above are green and device evidence is captured.
