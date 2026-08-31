# Cortex ↔ ChatGPT Cognitive Bridge V5

Status: implementation contract

## Goal
Make ChatGPT the cognitive adjudicator over Cortex state without coupling Cortex core logic to UI automation or an OpenAI API key.

Pipeline:

`Phone evidence → Cortex local evidence/state → compact cognitive packet → ChatGPT adjudication → validated decision contract → Cortex state/action layer`

## Hard boundary
Cortex owns truth, persistence, provenance, permissions and execution. ChatGPT owns semantic interpretation, linking, prioritisation and proposed next actions. No model response is executed directly.

## Transport adapters
The cognitive contract is transport-independent:

1. **CHATGPT_APP_BRIDGE** — experimental/manual bridge to the installed ChatGPT app. Cortex exports a compact packet and accepts the returned decision JSON. This is a prototype/fallback because Android UI automation is brittle.
2. **MCP_REMOTE** — preferred future path. Cortex exposes authenticated read/action tools through a bridge service reachable by ChatGPT.
3. Existing model providers may implement the same contract for testing/fallback.

Termux/Shizuku are execution/transport helpers, not the cognitive protocol.

## Cognitive packet
Cortex should not send isolated notifications one-by-one. It groups evidence into situations first and exports:

```json
{
  "schema_version": 5,
  "packet_id": "...",
  "generated_at": 0,
  "question": "What should Cortex do with this state?",
  "current_state": {
    "attention": [],
    "waiting": [],
    "decisions": [],
    "goals": [],
    "situations": []
  },
  "new_evidence": [],
  "allowed_decisions": [
    "IGNORE", "STORE", "LINK", "UPDATE_SITUATION", "SURFACE_NOW",
    "WATCH", "ASK_USER", "PROPOSE_ACTION"
  ]
}
```

Every evidence object gets an opaque local reference such as `E1`; derived situations use `S1`. Raw DB IDs do not need to leave Cortex.

## ChatGPT decision contract
ChatGPT returns only JSON:

```json
{
  "schema_version": 5,
  "summary": "...",
  "decisions": [{
    "type": "UPDATE_SITUATION",
    "target_ref": "S1",
    "evidence_refs": ["E1", "E2"],
    "confidence": 0.92,
    "reason": "new evidence changes the live state",
    "proposed_state": {},
    "next_action": null
  }]
}
```

Cortex validates references, types, required fields and execution permissions locally. Unknown references or unsupported decision types are rejected.

## Safety/execution rule
`PROPOSE_ACTION` is not execution. It enters the existing BrainActionStore/CortexActionDispatcher validation path. Missing private facts must be requested from the user rather than invented.

## Learning loop
Teacher/Student V4/V5 should compare:

`same cognitive packet → ChatGPT teacher decision → Cortex student decision → semantic/state/action diff`

Persist differences by category: missed link, stale state, wrong lifecycle, missed priority, false priority, missing action, unsafe action, hallucinated fact, weak explanation.

## Implementation order
1. CognitivePacketBuilder (read-only, deterministic, provenance-preserving).
2. CognitiveDecisionContract parser/validator.
3. CognitiveAdjudicationStore for packet/teacher/student/diff history.
4. ChatGPT App Bridge export/import prototype.
5. Teacher/Student V5 instrumentation against exactly the same packets.
6. MCP/remote transport only after the contract and adjudication tests are stable.

## Non-goal
Do not make Accessibility/Shizuku screen scraping the source of truth or let it directly mutate Cortex state. The transport can fail without corrupting the cognitive model.
