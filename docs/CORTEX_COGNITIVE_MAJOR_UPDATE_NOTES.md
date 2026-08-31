# Current stabilization notes

The previous device E2E proved capture and fallback safety but also exposed the real blocker: local cognitive shadow runs were taking roughly 45–60 seconds and the 4-second canary authority budget timed out into Legacy.

The current stabilization branch therefore focuses on measured local inference performance and scheduling rather than changing cognitive taxonomy or persistence semantics.

Implemented in the current 7.1 line:

- `LocalInferenceCoordinator` with AUTHORITATIVE > INTERACTIVE > LEGACY > SHADOW ordering.
- Shadow admission returns `SKIPPED_BUSY` instead of waiting behind authority/native work.
- Shadow quiet window increased to 15 seconds.
- Canary authority timeout increased to 12 seconds and split into queue/inference timeout reasons.
- `FastCognitivePromptBuilder` with bounded input context.
- `FastCognitiveResultParser` mapping the compact wire format into the existing `CognitiveResult` / `CognitiveItem` model.
- Local cognitive generation capped at 96 tokens.
- Local run telemetry includes queue/native/total timings, prompt size, generated tokens, cache hit and wire schema.
- Benchmark corpus covers ACTION, WAITING, EVENT, CONTENT, CONTEXT and Arabic/English code-switching.
- Fast DERIVE prompt example uses realistic canary-eligible confidence rather than a zero-confidence placeholder.

The next acceptance step is measurement on the S26 Ultra. No merge is permitted from this status.
