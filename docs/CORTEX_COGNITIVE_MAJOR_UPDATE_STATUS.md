# Cortex Cognitive Major Update — Status

Branch under reconciliation: `fix/v2-reconciliation-v54`
Canonical cognitive base: `migration/cognitive-brain-v2-step1-2`

## Green

- Qwen3-1.7B model replacement is implemented.
- Typed Cognitive V2 contract exists.
- Local Cognitive Brain harness exists.
- Canonical cognitive schema is V7 / `cognitive_004`.
- Shadow mode exists.
- Shadow evaluation gate exists.
- Guarded authority canary exists.
- Real-device router/canary instrumentation previously passed 8/8.
- Qwen3-1.7B was verified on device and returned `CORTEX_LOCAL_OK`.
- Fast authoritative classification uses the compact cognitive wire with a 24-token generation ceiling.
- Grounded Ask local refinement is a separate path with a 96-token generation ceiling.
- Fast parser compiles after checked-JSON exception hardening.
- Authority quiet window is 3 seconds; shadow quiet window is 15 seconds.
- Canary authority budget is 12 seconds with queue vs inference timeout telemetry.
- Local inference coordinator protects authority from interactive/legacy/shadow admission.
- Debug validation override proved out-of-5% V2 routing provenance on a real device, including routing reason and bucket persistence.
- Disabling the override returned the same out-of-5% thread immediately to normal hash Legacy behavior; the normal 5% production routing rule remained intact.
- The canonical V2 branch CI repository audit and compile gate were green before v54 reconciliation.

## V54 reconciliation in progress

- Update-in-place release identity is reconciled at versionCode 54 above the validated v53 device build.
- Automatic Android backup is disabled for private Cortex state while explicit Cortex backup/export remains available.
- External triggering of the phone-only self review is disabled.
- Arabic OCR asset download is integrity pinned.
- The build no longer reconstructs the installed-app signer from tracked base64 material; the encoded keystore file is deleted from branch HEAD and ignored going forward.
- Historical repository exposure of that signer still needs repository-history/visibility remediation.
- Bounded consistent backup export and database-atomic restore improvements are preserved.
- Approved semantic UI palette is preserved without reverting newer canonical attention/presentation logic.
- Local-only experimental Compose UI work must be snapshotted and selectively reconciled; it must not overwrite the canonical Groovy build or cognitive V2 files wholesale.

## Red until new proof

- Final v54 CI audit + APK/instrumented-source compile on the latest reconciliation HEAD.
- v54 install over the current device build with the unchanged installed-app signer.
- v54 startup/crash-buffer proof.
- Post-v54 database audit: `user_version=7`, `integrity_check=ok`, `cognitive_schema=cognitive_004`.
- Visible Now / Inbox / Atlas / Ask / Capture regression pass after local-only UI reconciliation.
- 10-case S26 latency benchmark after the final v54 fast path.
- Authority queue P95 < 500 ms.
- Authority total P50 <= 8 s / P95 <= 12 s.
- 0/10 authority timeouts.
- Valid fast contract >= 99% under benchmark/device load.
- Classification acceptance on ACTION / WAITING / EVENT / CONTENT / CONTEXT and mixed Arabic-English.
- Real production notification completing through V2 authority before timeout.
- Persisted V2 cognitive state with no Legacy fallback/mutation for the same production signal.
- Final DB audit after restoring/confirming the normal 5% canary.
- Repository visibility/history remediation for the previously exposed signer material.

This file is deliberately conservative: code presence, compilation, and device proof are tracked separately and nothing is marked green by implication.
