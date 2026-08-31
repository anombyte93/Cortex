# Cortex PR stack — unique-value / supersession audit

Card B (`t_b5d55843`), branch `wt/supersession-audit`, worktree
`/home/anombyte/atlas/work/cortex-pr-stack-20260831/cortex/.worktrees/t_b5d55843`.
Read-only against upstream `KAN1409/Cortex`. No push, no PR touched, no branch deleted.
Date of measurement: 2026-08-31.

---

## 0. Refs actually measured

Every SHA in the commission resolves locally. `main` moved to
`c8c49ccc71c6b367dfecaff76351279f256d5c2d` ("Apply approved Cortex hierarchy to People Projects",
2026-08-27 19:03 +0300).

| PR | head | branch | base |
|----|------|--------|------|
| #8  | `8baa00a6ea76234b6dc3a1264541d621daaf7133` | `v1.0.1-mixed-language-voice` | `main` |
| #64 | `c38b2e217dfdc760ac776cbf77b322f4a02bded1` | `cleanup/repo-consolidation` | `main` |
| #65 | `9816a7ef0d3e6d96a6947a4a222396be8c505a50` | `architecture/pulse-memory-worlds-think-v1` | #64 |
| #66 | `32870cd69a0c30ceb0bc134cd91c8eaebd00823f` | `migration/cognitive-brain-v2-step1-2` | #64 |
| #67 | `4cfb45368c18d57db6bb097b9058dbadb6257f7b` | `integration/cortex-relay-signal-v2` | #65 |
| #68 | `9f7e18d90e23461c160af1c14031dd370f989389` | `integration/cognitive-relay-v2` | #66 |
| #69 | `3d54230869dbcc6419e727c952bd9254c74cad66` | `integration/cognitive-relay-v2-v54` | #66 |

Merge-base facts (`git merge-base`), which govern every comparison below:

```
main..#8   = 00eb05677ccbde591e05c2c713bbf95c52f70e56   (2026-08-21; #8 is 6 days stale of main)
main..#64  = c8c49ccc  (main IS the merge base — #64 strictly follows current main)
#64..#65   = c38b2e21  (#64 is an ancestor of #65)
#64..#66   = c38b2e21  (#64 is an ancestor of #66)
#65..#67   = 9816a7ef  (#65 is an ancestor of #67)
#66..#68   = d2523e6b  (#66 head is NOT an ancestor of #68)
#66..#69   = 0dd8453a  (#66 head is NOT an ancestor of #69)
#68..#69   = d2523e6b
```

**Topology correction worth recording.** #68 and #69 are *not* stacked on the #66 head; each was
cut from a mid-branch commit of `migration/cognitive-brain-v2-step1-2` and never re-based
forward. #68 branched at `d2523e6b` ("fix(cognitive): ground minimal fast results in input text");
#69 branched 13 commits later at `0dd8453a` ("feat(cognitive): make V2 the primary authority
path"). That, not any content disagreement, is the direct cause of their CONFLICTING status — the
#66 base moved on underneath them. GitHub's "base: migration/…" is a branch-name pointer and
misleads here.

Gate baseline used throughout: `bash scripts/cortex-repo-audit.sh` run at each head in a detached
checkout of this worktree.

| head | audit exit | files scanned | failures |
|------|-----------|---------------|----------|
| `main` c8c49ccc | **2 (FAIL)** | 223 | 6 |
| #64 c38b2e21 | 0 | 260 | 0 |
| #65 9816a7ef | 0 | 373 | 0 |
| #66 32870cd6 | 0 | 318 | 0 |
| #67 4cfb4536 | 0 | 379 | 0 |
| #68 9f7e18d9 | 0 | 319 | 0 |
| #69 3d542308 | 0 | 329 | 0 |
| `build/v2-brain-primary-v54` effd492f | 0 | 318 | 0 |
| #8 8baa00a6 | n/a — `scripts/cortex-repo-audit.sh` does not exist at that head | — | — |

The `main` result is not a defect on main; it is the **negative control for the whole exercise**.
The audit script is versioned with the tree, and main's own copy
(`d5feba02…`) still asserts the *previous* warm-velvet palette (`RED = Color.rgb(255,72,62)`,
`VIOLET = YELLOW`, …) while main's `CortexUi.java` has already moved to the approved AMOLED lime
system. So main fails its own six palette assertions. This proves the audit gate can and does go
RED, and that a green audit at #64/#66/#69 is a real signal rather than a check that cannot fail.
It is also a genuine finding for the maintainer: **`main` is currently red against its own audit
script**, and #64 is the change that fixes it (commit `c078ec70` "Replace stale UI-specific audit
with structural repo gate").

---

## 1. Per-PR unique-value table

| PR | verdict | unique value not present in the canonical line (#64 → #66 → #69) or main |
|----|---------|--------------------------------------------------------------------------|
| **#68** | **SUPERSEDED — fully contained in #69. Nothing lost.** | Zero. Its 17-file payload is byte-identical to #69's for every file both carry; #69 adds one further file. |
| **#67** | **SUPERSEDED — its Relay payload is byte-identical in #69. Nothing Relay-specific lost.** | Zero, in Relay terms. Its remaining delta is #65's architecture, audited separately below. |
| **#65** | **SUPERSEDED ARCHITECTURE — archive as research. One behavioural item is genuinely unique.** | The trusted-connector enrichment policy (`RawSignalStore.evaluateTrustedEnrichment` + its 5-case regression) exists nowhere else. See §4. |
| **#8** | **ARCHIVE — historical. Two items are genuinely unique but obsolete-by-design.** | A PHP cloud-ASR backend, a WorkManager retry worker for failed transcription, and two JVM unit tests. All are attached to an ASR architecture the product has since left. See §5. |

---

## 2. #68 — superseded by #69, proven byte-identical

Method: compared the two heads' *trees* rather than their diffs, because both are diffs against
different bases and a raw diff would have been misleading.

`git diff --name-status 9f7e18d9 3d542308` returns 28 paths. Every one of them is a **#66-line**
file (`CognitiveAdjudicatorV2`, `RawSignalStore`, `FastCognitiveResultParser`,
`CognitiveDecisionApplier`, `LegacyCognitiveFallback`, `V2FailureReason`, the primary-authority
tests, …) — i.e. the 13 cognitive commits between `d2523e6b` and `0dd8453a` that #69 sits on top of
and #68 does not. **Not one Relay file appears in that list.**

Per-file object-hash comparison of the Relay payload:

```
CortexRelayBridgeV2.java           68=0f451be083  69=0f451be083   SAME
CortexLocalBusProtocolV2.java      68=55dcce00ba  69=55dcce00ba   SAME
CortexLocalBusService.java         68=a997f917b1  69=a997f917b1   SAME
CortexRelayV2DiagnosticsActivity   68=bde1b3a239  69=bde1b3a239   SAME
CortexLocalBusV2RegressionTest     68=2ebc514ba6  69=2ebc514ba6   SAME
```

The only Relay-adjacent differences between the two heads are:

- `app/build.gradle` — `versionCode 51 → 54`, `versionName '1.0.0-v51-cognitive-relay-v2-candidate'
  → '2.0.1-cognitive-relay-v2-candidate'` (#69 is the later, correctly-versioned candidate);
- `scripts/build-sign-install-cognitive-relay-v2-candidate.sh` — the same version strings, plus
  `chmod +x`;
- #69 adds `scripts/build-local-snapshot-cognitive-relay-v2-v54.sh` (257 lines) which #68 lacks.

**Classification of every #68 unique hunk: already-present in #69, at identical content.**
Nothing to port. Disposition: close as superseded.

---

## 3. #67 — Relay payload superseded by #69; its remaining delta is #65

#67 is `#65 + 15 Relay commits`. Splitting those two concerns:

**3a. The Relay half is byte-identical to #69** (same object-hash table as §2 — `CortexRelayBridgeV2`,
`CortexLocalBusProtocolV2`, `CortexLocalBusService`, `CortexRelayV2DiagnosticsActivity`,
`CortexLocalBusV2RegressionTest`, and `docs/CORTEX_RELAY_SIGNAL_V2_ACCEPTANCE.md` at `d9d292572d`
in both). `git diff 4cfb4536 3d542308` restricted to the Relay file set returns changes in only
two files, and both are #65-vs-#66 architecture differences leaking through, not Relay differences:

- `AndroidManifest.xml` — #67 registers `DeepBrainActivity`, `DeepBrainImportActivity`,
  `RuntimePipelineDiagnosticsActivity`, `DeepQwenSettingsActivity` (all #65-only classes); #69
  registers `CognitiveShadowActivity` (#66-only). The `CortexRelayV2DiagnosticsActivity`
  registration is present and identical in both.
- `AdvancedSettingsActivity.java` — same story: the "Relay V2 bridge" row exists in both; the rows
  that differ are the #65-only diagnostics screens vs the #66-only Cognitive V2 Shadow screen.

**3b. Its installer is superseded, not lost.** #67 ships
`scripts/build-sign-install-relay-v2-candidate.sh`; #69 ships
`scripts/build-sign-install-cognitive-relay-v2-candidate.sh`. Diffing them directly (git sees no
rename because the bases differ) gives 9 changed lines: version 51→54, the APK filename, the
success banner, and two comment edits. Every safety property is preserved verbatim in #69 —
`EXPECTED_CERT_SHA256=e869f439…`, `bash scripts/cortex-repo-audit.sh` before build, no-uninstall
`pm install -r`, post-install signer verification, and the explicit refusal to store keystore
passwords in-repo. #69's version additionally documents that it is deliberately build-only and
must not run `connectedDebugAndroidTest`.

**3c. One item is uniquely #67's and is worth flagging, though not porting.** #67's `build-apk.yml`
triggers on `'architecture/**'`; #69's triggers on `'integration/**'`. Neither is wrong; they
follow their branch families. #69's is the correct one for the canonical line.

**Classification: all #67 Relay hunks already-present in #69 at identical content; all remaining
hunks are #65's, dispositioned in §4.** Disposition: close as superseded by #69, with the caveat
that closing #67 must not be read as closing #65 — see §4.

---

## 4. #65 — superseded architecture, with ONE genuinely unique behaviour

This is the largest PR in the stack (+11653) and it warrants the specificity the commission asked
for. #65 and #66 are **siblings**, both branching from #64 (`git merge-base 9816a7ef 32870cd6 =
c38b2e21`). They are competing successors, not a sequence.

### 4a. What #65 contains that #66 does not (file-level)

`comm` of the two trees gives **97 paths present in #65 and absent from #66**. They fall into four
groups:

1. **The V4 domain — 40 main classes + 22 instrumentation tests.** `CognitiveStoreV4`,
   `CognitiveSchemaV4`, `CognitiveMemoryProjectionV4`, `CognitiveMemoryBackfillV4` (+ scheduler and
   worker), `CognitiveWorldResolverV4`, `CognitiveWorldProjectionV4`, `CognitiveWorldCandidate*V4`,
   `CognitiveWorldProposalQualityV4`, `CognitiveSituationEngineV4`, `CognitivePulseProjectionV4`,
   `CognitiveIdentityV4`, `CognitiveGroundingV4`, `CognitiveRetentionV4`, `CognitiveNowPolicyV4`,
   `CognitiveReasoningOrchestratorV4`/`ProviderV4`/`WorkerV4`/`RunStoreV4`,
   `CognitiveDeepBrain*V4` (apply, packet builder, protocol, reconciler, store),
   `CognitiveAutoReasoning*V4`, `CognitiveBridgeStatusV4`, `CognitiveRealtimeProjectionV4`,
   `CognitiveIntentionalRealtimeV4`, `NotificationIdentityHintsV4`,
   `GeminiCognitiveReasoningProviderV4`, plus 6 `docs/COGNITIVE_*_V4.md` architecture documents.
   **Not one of these symbols is referenced anywhere in #66** (`git grep -l CognitiveStoreV4 …
   32870cd6 -- app/src` → empty). This is the "Pulse / Memory / Worlds / Think" architecture; #66
   pursues the Cognitive Brain V2 line instead. Setting #65 aside sets *this entire layer* aside.

2. **The Deep Qwen / brain-router layer.** `CortexBrainRouter`, `DeepQwenBrain`, `DeepQwenConfig`,
   `DeepQwenSettingsActivity`, `LocalBrainConfig`, `LocalBrainRuntimePolicy`, `BrainRequest`,
   `BrainCompletion`, `PriorityEngine`, `SignalFamilyClassifier` (a distinct implementation from
   #66's), plus `DeepBrainActivity` / `DeepBrainImportActivity` (the ChatGPT hand-off screens) and
   `RuntimePipelineDiagnosticsActivity` / `CortexIntensiveDiagnosticExporter` /
   `CortexRuntimeDiagnosticV1`. #66 keeps a single `LocalQwenBrain` + `LocalInferenceCoordinator`
   and drops the optional self-hosted-vLLM fallback entirely.

3. **Local Bus V1 + connector ingest — already carried forward, so NOT lost.**
   `CortexLocalBusService`, `CortexLocalBusProtocolV1`, `CortexLocalBusStoreV1`,
   `CortexConnectorIngestV1`, `CortexConnectorRegistryV1`, `CortexLocalBusV1RegressionTest`,
   `docs/CORTEX_LOCAL_BUS_V1.md`. These are absent from #66 but **present in #69**, ported by
   `d85da7a2` "integration: port Relay V2 bridge onto current cognitive head". #69's copies are
   evolved rather than identical (`CortexLocalBusService` +233/-… vs #65's; `CortexConnectorIngestV1`
   +98). So the connector substrate survives #65's archival via the Relay line.

4. **`app/cortex-debug.keystore.b64` — the security item.** See §4c.

### 4b. Same-name, different-semantics files (the trap the commission warned about)

`git merge-tree` of #66 against #65 produces **25 conflicts**, 11 of them `add/add` — meaning both
branches independently created a file of the same name with different content:
`CognitiveAdjudicatorV2`, `CognitiveResult`, `CognitiveResultParser`, `CognitiveResultValidator`,
`CognitiveItem`, `CognitiveInput`, `CognitiveKind`, `CognitiveDisposition`, `CognitivePromptBuilder`,
`CognitiveFeatureFlags`, `CortexBrain`, `BrainException`, `LocalQwenBrain`, `SignalFamilyClassifier`.
File-name equality here is *not* content equality; the two branches cannot be reconciled by
"whichever has the file".

Spot-check of the semantics: #65's `CognitiveResult` (1244 bytes) defensively copies and clamps
inside the constructor and carries a `toJson()`; #66's (812 bytes) is a plain immutable carrier
with `hasDerivedItems()`, and moves clamping/validation into a separate
`CognitiveResultValidator` that also enforces `MAX_ITEMS=5`, forces `ACTION ⇒ requiresUserAction`,
`WAITING ⇒ requiresFollowUp`, and **clears the items array for `IGNORE`/`CONTEXT` so a model
cannot smuggle durable intelligence through a non-durable disposition**. #66's split is the
stronger contract. #65's `CognitiveKind` carries a doc comment #66 dropped; the enum constants are
identical.

**Classification: superseded.** The #66 line's validator, authority router, failure-reason taxonomy
(`V2FailureReason`) and explicit `LegacyCognitiveFallback` are a strictly better-specified version
of the same responsibility.

### 4c. Signing material — #65 (and #67/#68/#69) still carry the real keystore

This is the most consequential single finding in the audit and it is not in the commission's
expectations list.

`app/cortex-debug.keystore.b64` is **3556 bytes of real PKCS#12 base64** (`MIIKZgIBAzCCChAGCSqG…`)
and is **present** at: `main` c8c49ccc, #64 c38b2e21, #65 9816a7ef, #67 4cfb4536, #68 9f7e18d9,
**#69 3d542308**. It is **absent** only at #66 32870cd6, where commits `836e5a45`
("security(v2): remove signing material from branch head") and `c2137e5e` ("security(v2): delete
tombstoned signing artifact from branch") removed it and `.gitignore` gained `*keystore*.b64`.

#66's audit script is the only one in the stack that checks for it (lines 95–111: `forbid_text` on
the build script, plus a scan of `git ls-files` for `*keystore*.b64` requiring the file be a ≤1024-byte
tombstone reading `REMOVED_FROM_SOURCE_CONTROL`). Neither `main`'s audit nor #64's
(`565096756740907ff941c0969075382fe2681bc4`, shared verbatim by #65/#67/#68/#69) contains any
keystore assertion — which is exactly why those heads pass their own audit while still carrying the
key.

I verified the merge outcome rather than assuming it: `git merge-tree --write-tree` of #66 against
each of #65, #68 and #69 produces a tree in which `app/cortex-debug.keystore.b64` is **absent** —
git resolves #66's deletion against an unmodified file cleanly, so merging any of them into the #66
line does not resurrect the key.

**Consequence to record for Card C and for the maintainer:** a rebase of #69 onto #66 must be
verified to preserve #66's deletion, and #69's audit script must be taken from #66 (the version
that has the keystore assertions), not carried over from #69's own base. The key remaining on
`main` and in git history is a separate, larger remediation that no PR in this stack addresses;
#66's own audit says so explicitly ("legacy signing-material path is tombstoned at HEAD;
Git-history remediation is still required").

### 4d. THE ONE GENUINELY UNIQUE BEHAVIOUR IN #65 — flagged loudly

`RawSignalStore.evaluateTrustedEnrichment(...)` exists **only** in #65 (4 references). It is absent
from `main`, #64, #66 and #69. It encodes the policy that when a trusted Relay connector delivers a
*richer revision* of a notification Cortex already captured from the shallow Android preview, that
richer text is re-adjudicated rather than deterministically promoted — with a package/metadata
allowlist so an arbitrary app cannot claim conversation semantics.

Its regression, `TrustedConnectorEnrichmentV1RegressionTest.java`, pins five behaviours:

1. a richer WhatsApp deadline request crosses the durable boundary as `ACTION`, importance ≥ 60;
2. a bare acknowledgement (`حاضر`) against an older request does **not** recreate the historical
   request as a new action — it lands `REVIEW` with `candidateKind=ACTION`;
3. a richer Gmail security alert can promote (`MEMORY`, durable) even when the preview missed it;
4. `com.shopping.promo` saying "Please send this offer to a friend" is **refused** conversation
   rules — `CONTEXT`, non-durable;
5. an unknown package **with** explicit `notification_kind=message` metadata is allowed —
   `ACTION`, durable.

Case 2 and case 4 are the interesting ones: they are anti-regressions against false-positive action
creation, and they are exactly the kind of behaviour that is expensive to rediscover.

**PR #68 deleted this test explicitly** — commit `9f7e18d9` "integration: drop architecture-only
trusted enrichment regression from cognitive branch", −46 lines, and `3b27c519` rewired
`CortexLocalBusV1RegressionTest` to stop depending on the helper. That was correct *mechanically*
(the helper does not exist on the #66 line, so the test could not compile there) but it means the
**policy was dropped silently along with the test**, and #69 inherits that drop.

What replaces it on the canonical line: #69's `CortexConnectorIngestV1` routes connector traffic
through `RawSignalStore.capture(context, db, signal)` and then
`NotificationEnrichmentEngine.enrich(...)`, i.e. the normal cognitive authority path. That is a
defensible design — the V2 adjudicator decides, rather than a deterministic promotion rule — and it
is arguably *safer*. But **the five behaviours above are no longer pinned by any test anywhere in
the stack.**

**Verdict: genuinely unique, and worth preserving as a test-level obligation, but NOT portable as
code.** I did not port it, and that is a deliberate decision I want reviewed:

- `evaluateTrustedEnrichment` depends on `MasterRelevanceFilter.evaluateThread`,
  `AdaptiveRelevanceLearning.adapt`, `SignalThreadStore.recentContext` and a `promote(...)`
  signature carrying a `policyVersion` argument. #66's `RawSignalStore` is a different file with a
  different `promote(...)` and no `TIER0_POLICY`/`CONNECTOR_POLICY` constants. Porting the method
  means porting a slice of #65's relevance stack into the #66 authority model — a redesign, not a
  cherry-pick, and it would put a deterministic promotion path *back* underneath `V2_PRIMARY`,
  which is the exact class of bypass §6 says must not exist.
- Porting it under this card would also have been out of scope: this card is an analysis card, and
  the change belongs on the Relay/cognitive line that Card C owns.

**Recommended follow-up (not done here):** a small card against the #66/#69 line that re-expresses
cases 1–5 as assertions on the `CortexConnectorIngestV1` → `RawSignalStore.capture` →
`CognitiveAdjudicatorV2` path, so the anti-false-positive behaviour is pinned again without
reviving the deterministic promotion route. Until that exists, closing #65 does lose something
real, and the branch must be preserved.

---

## 5. #8 — historical mixed-language voice research

#8's merge-base with main is `00eb0567` (2026-08-21, "Merge pull request #7"). It is 6 days behind
and 128 commits of its own. `scripts/cortex-repo-audit.sh` does not exist at that head, so the
current gate cannot be run against it at all.

`git merge-tree` main × #8 gives **7 conflicts** (`build-apk.yml`, `app/build.gradle`,
`AndroidManifest.xml`, `AnalysisQueue`, `AudioAnalyzer`, `BrainActivity`, `MainActivity`) and the
merged tree would carry **12 GitHub Actions workflows**, which #64's audit gate
(`exactly one GitHub Actions workflow`) rejects outright. #8 is unmergeable against the current
architecture by construction, not by accident.

### 5a. Already-present / superseded

The ASR line moved on. On `main` the transcription stack is
`AudioAnalyzer` → `GeminiAudioTranscriber` (primary) → `GroqAudioTranscriber` (fallback), with
`CohereAudioTranscriber`, `AsrSettingsActivity`, `PrivacyPolicy.canUseCloud(ctx,"audio")` gating,
an OOM guard on inline Gemini audio (`MAX_SAFE_INLINE_BYTES`), coverage/acceptability checks and
provider-provenance JSON. #8's `AudioAnalyzer` is the *opposite* design: it deletes all of that
and routes everything to a single self-hosted PHP endpoint (`CloudAudioTranscriber` +
`backend/transcribe.php`). Superseded, and superseded by something with more safety machinery.

`SystemAudioTranscriber.java` is **identical on main and #66** (`0adf27c6b7cbb…` at both) and is
**deleted by #8**. #8's whole local/whisper.cpp track — `LocalAsrModelStore`,
`CodeSwitchCandidateSelector`, `WavSpeechChunker`, the prompted-Whisper AAR build, the 8 model
conversion/benchmark workflows, `tools/whisper-cortex/patch_whisper_android.py` — exists on
neither main nor the canonical line, and #8's own final commit ("Cortex Prime v1.0.22 —
redirect-safe cloud ASR, no local model") **deletes it from #8 itself**. The author abandoned that
approach inside the PR. Archive.

### 5b. Genuinely unique, safe to archive

Three items exist nowhere else and are not represented in the current line:

1. **`backend/transcribe.php`** — a multi-provider server-side ASR broker (OpenAI
   `gpt-transcribe`, Google STT v2, Azure Speech) with a `ProviderFailure(retryable)` taxonomy.
   Configuration is env-var only (`getenv('OPENAI_API_KEY')` etc.) — **I checked and there are no
   literal credentials committed**. It is a whole deployment surface the product does not have and,
   on current evidence, does not want (main talks to providers directly from the device under
   `PrivacyPolicy`). Archive.
2. **`CloudTranscriptionRetryWorker`** — a WorkManager `OneTimeWorkRequest` with
   `NetworkType.CONNECTED` and exponential backoff from 30 s, re-driving a specific failed audio
   item. Main *marks* items `markFailedRetryable(...)` in `AnalysisQueue` but has no
   network-constrained worker that re-drives them on reconnect. **This is a real capability gap on
   main**, small and self-contained. It is the one piece of #8 I would call portable in principle.
   I did not port it: it is ASR-domain work with no owner in this stack, `AnalysisQueue` differs
   substantially between the two heads, and porting it under an analysis card would be unreviewed
   scope creep. Recommend a separate card if the gap matters.
3. **`VoiceExporter`, `BackupRestorer` + `RestoreActivity`, `ChatGptDebugReview` +
   `DebugReviewActivity`** — export-original-audio, a signing-migration restore launcher, and a
   share-to-ChatGPT voice debug bridge. The restore launcher was for the v1.0.9 stable-signing
   migration and is spent. Main has `BackupExporter`/`BackupImporter`. Archive.
4. **Two JVM unit tests** (`SystemAudioTranscriberTest`, `CodeSwitchPipelineTest`) — the only
   `src/test` JVM tests anywhere in the stack; the canonical line uses `androidTest` exclusively.
   They pin `mergeForTest`, `CodeSwitchCandidateSelector.mergeEnglishSpan/mergeTail/choose`,
   `WavSpeechChunker.detectRanges` and `LocalAsrModelStore.isGgmlHeader` — **every symbol they test
   is absent from main and from the canonical line**, and #8's own head commit deletes both files.
   Nothing to preserve: the behaviour under test no longer exists.

**Answer to the commission's precondition ("archive/close only after confirming any still-useful
ASR tests/behaviour are represented in the current ASR/cognitive line"):** confirmed for the tests
— they test deleted code. Not confirmed for the retry worker, which is a genuine (small) gap.
Recommend closing #8 with that gap explicitly recorded rather than silently absorbed.

---

## 6. #64 revalidation

**Is it a clean baseline against current `main`?** Yes. `git merge-base main #64 = c8c49ccc` — main
*is* the merge base, so #64 contains all of current main and loses nothing newer. GitHub reports it
MERGEABLE and that is consistent with the local topology.

**What it does.** 108 files, +3853/−1388. It removes 13 obsolete build-trigger marker files, the
second workflow (`android-build.yml`), a tracked `downloads/Cortex-latest-debug.apk`, and 5 stale
docs; adds the Attention/AI-adjudication layer, the ChatGPT Cognitive Bridge V5 packet contract,
`CompactTodayActivity` as the single launcher surface, and 7 instrumentation tests; and replaces
the audit script with a structural gate.

**Is the red check genuinely stale? — CONFIRMED, and with a sharper reason than "infra flake".**
`gh api repos/KAN1409/Cortex/actions/runs/33140254640`:

```
run_started_at 2026-08-28T03:54:26Z   updated_at 2026-08-28T03:54:30Z
status completed   conclusion failure   event pull_request
path .github/workflows/build-apk.yml   head_sha c38b2e21…   head_branch cleanup/repo-consolidation
```

`…/jobs` returns `total_count = 1`: job `validate-and-build`, started 03:54:27Z, completed
03:54:29Z, `conclusion: failure`, **`steps: []` — zero steps recorded**. A run that fails in two
seconds having executed no step never reached `Checkout`, let alone the audit or the build. It is a
scheduling/startup failure, not a code result. Logs are expired so the runner-side message is
unrecoverable — that is the one thing here I cannot show directly.

Locally at #64's exact head, `bash scripts/cortex-repo-audit.sh` returns **exit 0**, 260 files
scanned, 0 failures, 1 warning (8 TODO/FIXME hits). The control is meaningful because the same
script at `main` returns **exit 2 with 6 failures** — the gate demonstrably fires.

**Not proven:** I did not run the Gradle build at #64 (out of this card's scope; Card A owns the
toolchain). So "#64's audit passes" is proven; "#64's CI would go green end-to-end" is not — the
build step remains unexercised. A re-run of the workflow on that head is the cheap way to settle it
and requires write access this card does not have.

---

## 7. #66 revalidation — the two called-out items

### 7a. `RawSignalStore.capture(this, db, signal)` — PRESERVED, and the fallback path is real but bounded

`NotificationCaptureService.java:65` at #66 reads exactly:

```java
long signalId=RawSignalStore.capture(this,db,signal);
```

so the production notification path passes a live `Context`. `RawSignalStore` declares **two**
overloads (lines 16–17), both delegating to `captureInternal(context, db, signal)`:

```java
public static long capture(VaultDb db, MasterRelevanceFilter.Signal signal){ return captureInternal(null, db, signal); }
public static long capture(Context context, VaultDb db, MasterRelevanceFilter.Signal signal){ return captureInternal(context, db, signal); }
```

The null-context overload has exactly **one caller in the whole tree**, and it is a test:
`CognitiveShadowModeTest.java:102`, which asserts that shadow mode records nothing
(`COUNT(*) FROM model_runs WHERE role='cognitive_shadow'` = 0). The context-carrying overload's
callers are `NotificationCaptureService` (production) and `CognitivePrimaryAuthorityTest:74`.

The compatibility path is genuine but explicitly bounded, in `CognitiveAuthorityRouter.routeInternal`:

```java
// Null-context compatibility and the existing kill switch both collapse safely to Legacy.
if(context==null||!CognitiveFeatureFlags.authorityCanaryEnabled(context)){
    return new Decision(Route.LEGACY,RoutingReason.CANARY_DISABLED,bucket);
}
```

**So the risk the commission named is real in shape** — a null context *does* silently route to
Legacy, and `RoutingReason.CANARY_DISABLED` does not distinguish "kill switch pulled" from "no
context". But no production call site can reach it: the only null-context caller is a shadow-mode
test. Additionally the audit script at #66 now *requires* `CognitiveAuthorityRouter.java` and
`CognitiveFeatureFlags.java` to exist, and requires
`DEFAULT_AUTHORITY_MODE = CognitiveAuthorityMode.V2_PRIMARY` and `DEFAULT_CANARY_PERCENT = 5`.

**Verdict: PRESERVED, with a recorded residual.** Two cheap hardenings I recommend but did not
apply (they belong to the #66/#69 line, not to an analysis card): give the null-context branch its
own `RoutingReason` (e.g. `NO_CONTEXT`) so the telemetry can tell the two collapses apart, and add
an audit assertion that `RawSignalStore.capture(` in `NotificationCaptureService` is the
three-argument form — which would make this exact regression impossible to reintroduce silently.

### 7b. The `build/v2-brain-primary-v54` CI bypass — real, and provably unnecessary

At #66, `.github/workflows/build-apk.yml`, step "Repository audit":

```yaml
run: |
  if [ "${GITHUB_REF_NAME}" = "build/v2-brain-primary-v54" ]; then
    grep -Eq 'DEFAULT_AUTHORITY_MODE[[:space:]]*=[[:space:]]*CognitiveAuthorityMode\.V2_PRIMARY' app/src/main/java/com/kareem/cortex/CognitiveFeatureFlags.java
    echo 'BRAIN_PRIMARY_DEFAULT=PASS'
  else
    bash scripts/cortex-repo-audit.sh
  fi
```

Characterisation confirmed exactly as stated: on that one branch the entire structural audit —
single-workflow, no tracked APKs, no build-trigger markers, the keystore/`*.b64` assertions of
§4c, the single-launcher/single-`ACTION_SEND` invariants, the parallel-attention-architecture
absence checks — is skipped, and replaced by a single grep for one constant. It prints
`BRAIN_PRIMARY_DEFAULT=PASS` regardless, since the `grep -q` exit code is the step's result. It is
branch-name-scoped, so anyone who can push that branch name gets a build that never ran the audit.

**Exact consequence of removing it — measured, not reasoned.** The bypass exists to keep
`build/v2-brain-primary-v54` green. That branch is `effd492faf72db573f0b3065057fca6120bfcee1`, one
commit ahead of #66 ("perf(cognitive): persist native prompt/decode timing", a 1-line change to
`CognitiveDecisionApplier.java`). I checked it out and ran the full audit:

```
EXIT=0
Files scanned: 318  Warnings: 1  Failures: 0
CORTEX_REPO_AUDIT=PASS
```

**The branch passes the full audit unaided.** Removing the bypass — replacing the whole `run:`
block with `bash scripts/cortex-repo-audit.sh` — costs nothing at the current head. The V2_PRIMARY
assertion is not lost either: the audit script at #66 already contains
`require_text "$FLAGS" 'DEFAULT_AUTHORITY_MODE[[:space:]]*=[[:space:]]*CognitiveAuthorityMode\.V2_PRIMARY'`
plus `DEFAULT_CANARY_PERCENT = 5`. So the bypass's stated purpose is **strictly a subset of what
the unbypassed script already enforces**. It is pure attack surface with zero remaining benefit.

Note that #69's workflow has already dropped the bypass (its audit step is the bare
`run: bash scripts/cortex-repo-audit.sh`) — so the canonical line's own successor agrees. The
bypass must not be carried forward in any rebase.

---

## 8. Drafted supersession comments (NOT POSTED)

> ### #68 — proposed comment
> Superseded by #69. Verified locally: every Relay file in this PR is byte-identical to #69's
> (`CortexRelayBridgeV2` `0f451be0`, `CortexLocalBusProtocolV2` `55dcce00`, `CortexLocalBusService`
> `a997f917`, `CortexRelayV2DiagnosticsActivity` `bde1b3a2`, `CortexLocalBusV2RegressionTest`
> `2ebc514b` — identical object hashes at both heads). The only differences are the version bump
> (51 → 54, `2.0.1-cognitive-relay-v2-candidate`) and #69's additional
> `scripts/build-local-snapshot-cognitive-relay-v2-v54.sh`. Nothing in this branch is lost by
> closing it. For the record, this PR is not actually stacked on the current `migration/…` head —
> it was cut at `d2523e6b`, 13 cognitive commits behind, which is why it shows CONFLICTING.
> Branch `integration/cognitive-relay-v2` is preserved.
>
> One thing to carry forward deliberately: commit `9f7e18d9` here deleted
> `TrustedConnectorEnrichmentV1RegressionTest`. That was mechanically necessary, but it dropped the
> only test pinning the trusted-connector enrichment behaviour. See the note on #65.

> ### #67 — proposed comment
> Superseded by #69 for the Relay work. The Relay payload is byte-identical between this branch and
> #69 (same five object hashes as above, plus `docs/CORTEX_RELAY_SIGNAL_V2_ACCEPTANCE.md` at
> `d9d29257` in both). `scripts/build-sign-install-relay-v2-candidate.sh` is carried forward as
> `scripts/build-sign-install-cognitive-relay-v2-candidate.sh`, preserving every safety property
> verbatim: the pinned `EXPECTED_CERT_SHA256=e869f439…`, the pre-build `cortex-repo-audit.sh` run,
> update-in-place `pm install -r` with no uninstall, post-install signer verification, and no
> keystore passwords in-repo.
>
> Important: this PR is based on #65 (`architecture/pulse-memory-worlds-think-v1`), not on the
> canonical #66 line. Closing it as Relay-superseded says nothing about #65's architecture, which
> is dispositioned separately. Branch `integration/cortex-relay-signal-v2` is preserved.

> ### #65 — proposed comment
> Proposed disposition: **archive as historical research; do not merge**, superseded by the
> Cognitive Brain V2 line (#66 → #69). #65 and #66 are siblings off #64, not a sequence, so they
> are competing successors and only one can be the canonical cognitive architecture.
>
> Being specific about what is being set aside, since this is +11653 across 144 files: the entire
> V4 domain (40 main classes + 22 instrumentation tests + 6 architecture docs — Worlds, Memory
> projection/backfill, Situations, Pulse projection, Deep Brain reconciliation, autonomous Gemini
> reasoning), and the Deep Qwen / brain-router layer (`CortexBrainRouter`, `DeepQwenBrain`,
> optional self-hosted vLLM fallback, runtime pipeline diagnostics). No symbol from either group is
> referenced anywhere in #66.
>
> Preserved rather than lost: the Local Bus V1 / connector-ingest substrate
> (`CortexLocalBusService`, `CortexLocalBusProtocolV1`, `CortexLocalBusStoreV1`,
> `CortexConnectorIngestV1`, `CortexConnectorRegistryV1`, `CortexLocalBusV1RegressionTest`,
> `docs/CORTEX_LOCAL_BUS_V1.md`) was ported onto the cognitive head by #69's `d85da7a2`.
>
> **One item genuinely does not survive**, and should not be closed without a decision:
> `RawSignalStore.evaluateTrustedEnrichment` and its
> `TrustedConnectorEnrichmentV1RegressionTest` (deleted by #68's `9f7e18d9`). Those five cases pin
> real anti-false-positive behaviour — notably that a bare acknowledgement must not recreate a
> historical request as a new action, and that an arbitrary app cannot claim conversation semantics
> just because its text says "please send". #69 routes connector traffic through the normal V2
> authority path instead, which is defensible and arguably safer, but nothing currently pins those
> five behaviours. Recommend a follow-up that re-expresses them against
> `CortexConnectorIngestV1` → `RawSignalStore.capture` → `CognitiveAdjudicatorV2` before this branch
> is closed. Branch `architecture/pulse-memory-worlds-think-v1` is preserved regardless.

> ### #8 — proposed comment
> Proposed disposition: **archive as historical mixed-language voice research.** Its merge base is
> `00eb0567` (2026-08-21) and it predates the repo consolidation; `scripts/cortex-repo-audit.sh`
> does not exist at this head, and a trial merge into current `main` yields 7 conflicts and a tree
> with 12 workflow files, which the current audit gate (`exactly one GitHub Actions workflow`)
> rejects.
>
> The ASR architecture has moved on: `main` runs `AudioAnalyzer` → Gemini primary → Groq fallback,
> under `PrivacyPolicy.canUseCloud`, with an inline-audio OOM guard and coverage/acceptability
> checks. This PR replaces all of that with a single self-hosted PHP broker
> (`backend/transcribe.php`, env-var configured — no credentials are committed, I checked).
> The branch's own whisper.cpp/local-model track was already abandoned inside the PR by its final
> commit ("no local model"), which also deletes both JVM unit tests; every symbol those tests
> exercise (`CodeSwitchCandidateSelector`, `WavSpeechChunker`, `LocalAsrModelStore`,
> `SystemAudioTranscriber.mergeForTest`) is absent from `main` and from the canonical line, so
> there is no test behaviour to preserve.
>
> One capability here is genuinely absent from `main` and worth recording before closing:
> `CloudTranscriptionRetryWorker`, a network-constrained WorkManager retry with exponential backoff
> for audio items that failed to reach cloud ASR. `main` marks such items
> `markFailedRetryable(...)` but has no worker that re-drives them on reconnect. Suggest opening a
> small standalone issue for that gap rather than keeping this PR open for it.
> Branch `v1.0.1-mixed-language-voice` is preserved.

---

## 9. Anything ported

**Nothing was ported.** Two candidates were identified and both were consciously declined, with
reasons stated in §4d and §5b: #65's `evaluateTrustedEnrichment` is a redesign rather than a
cherry-pick and would reintroduce a deterministic promotion path beneath `V2_PRIMARY`; #8's
`CloudTranscriptionRetryWorker` is ASR-domain scope with no owner in this stack. Both are written
up as recommended follow-up cards. This card's only commit is this report.

---

## 10. Explicit list of what I could NOT prove

1. **The runner-side cause of run `33140254640`.** Logs are expired. I proved the run executed zero
   steps in two seconds and that the code passes its audit locally; I cannot show the runner's own
   error text. "Stale infra failure" is the only hypothesis consistent with zero steps, but it is
   an inference from shape, not a read of the message.
2. **That #64/#66/#69 would go green end-to-end in CI.** I proved `cortex-repo-audit.sh` exits 0 at
   each head. I did **not** run `gradle :app:assembleDebug :app:compileDebugAndroidTestJavaWithJavac`
   at any head — no `gradle` on PATH, no `gradlew` in the repo, and `compileSdk 35` is not installed
   on this box. The build step of every claim here is unexercised. Card A owns that toolchain.
3. **That #65's V4 layer contains no behaviour the product will later want.** I proved no symbol
   from it is referenced by #66, and I read the contracts of the conflicting same-name classes. I
   did not exhaustively read 40 classes and 11.6k lines for latent product value. "Superseded
   architecture" is a structural verdict; if the maintainer wants a specific V4 behaviour, it must
   be looked for by name.
4. **That the five trusted-enrichment behaviours are or are not preserved by #69's path.** I proved
   the *code* is gone and no test pins them. I could not run instrumentation tests, so I cannot say
   whether `CognitiveAdjudicatorV2` happens to produce the same five outcomes. That requires a
   device or emulator run.
5. **Whether the committed keystore is live or long-since rotated.** I proved the 3556-byte PKCS#12
   base64 is present at `main` and at #64/#65/#67/#68/#69 and absent only at #66, and that merging
   any of them into #66 does not resurrect it. I deliberately did **not** decode, extract or
   fingerprint the key material, so I cannot say whether it still matches the
   `e869f439…` signer the installers pin. Treat it as live until someone with authority says
   otherwise.
6. **GitHub's own mergeability states.** I recomputed conflicts locally with `git merge-tree` and my
   results agree with the commission's table, but I did not re-query the API's `mergeable` field —
   it is a cached, asynchronously-computed value and not independent evidence anyway.
7. **Anything about #70.** Out of scope for this card; Card A owns it.

---

## 11. Headline findings, in order of consequence

1. **`main` is currently RED against its own repo audit** (6 palette failures, exit 2). #64 is the
   fix. This doubles as the negative control proving the gate can fail.
2. **The real signing keystore is committed at `main` and at every stack head except #66.** #66 is
   the only branch that removes it and the only one whose audit script checks for it. Merging into
   the #66 line does not resurrect it (verified via `merge-tree`), but the `main`/history exposure
   is untouched by anything in this stack.
3. **#68 and #67 hold zero unique Relay value** — byte-identical payloads to #69. Clean closes.
4. **#65 holds exactly one genuinely unique, still-unpinned behaviour** (trusted-connector
   enrichment, 5 regression cases) which #68 deleted en route to #69. Closing #65 without a
   follow-up loses it.
5. **The `build/v2-brain-primary-v54` CI bypass is unnecessary today** — that branch passes the full
   audit unaided (exit 0, 318 files, 0 failures), and the audit already asserts the one constant the
   bypass greps for. Remove it; #69 already has.
6. **#68/#69 were never rebased onto the #66 head** — they were cut mid-branch. Their CONFLICTING
   status is base drift, not disagreement. That is Card C's starting point.
7. **#8 is unmergeable by construction** (12 workflows in the merged tree vs the gate's limit of 1)
   and its own final commit deleted the local-ASR work its tests covered. One small real gap
   (`CloudTranscriptionRetryWorker`) should be recorded as an issue before closing.
