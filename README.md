# Cortex

Cortex is a personal cognitive intelligence system for Android. It captures evidence from intentional input and selected phone context, decides what deserves durable memory, connects related context, reasons over the user's data, surfaces attention-worthy open loops, and prepares user-approved actions.

## Core pipeline

```text
Capture / Perceive
→ Understand
→ Connect
→ Remember
→ Reason
→ Prioritize
→ Suggest
→ Execute with approval
→ Learn
```

Raw device signals are not durable memory by default. Notifications, accessibility context, screenshots, audio and other evidence first pass through relevance, threading, uncertainty and privacy gates before promotion.

## Current architecture

- `knowledge_items` preserves durable captured memory and attachments.
- `raw_signals` and `signal_threads` hold bounded evidence and communication episodes.
- `derived_items` stores cognitive state such as actions, waiting items, decisions, insights and reviews.
- `source_links` preserves provenance between signals, threads, memory, entities and derived intelligence.
- `AttentionEngine` ranks already-relevant candidates for current attention.
- `BrainRouter` provides local, external and combined reasoning routes with local privacy filtering.
- `ResultProposalEngine` and `CortexActionDispatcher` convert useful results into structured, approval-first next actions.
- Local semantic retrieval, OCR, ASR, feedback learning, diagnostics and backup/restore are integrated into the same application.

See `CORTEX_INFORMATION_ARCHITECTURE_V3.md` and `docs/DATA_MODEL.md` for additional design notes.

## Build and validation

The repository has one GitHub Actions workflow: `.github/workflows/build-apk.yml`.

CI runs the repository audit, compiles the debug APK, and compiles instrumented Android test sources. APK binaries are build artifacts only and are not committed to the repository.

Local/Termux builds should use:

```bash
bash termux-build-cortex.sh
```

The current Android version is defined only in `app/build.gradle`; do not hard-code release version names in CI artifact logic or documentation.

## Repository rules

Generated APKs, temporary build-trigger files, benchmark markers, transient audit reports and branch-specific planning artifacts do not belong in source control. Historical migrations and compatibility code may remain when they are required to preserve existing Cortex data or runtime behavior.
