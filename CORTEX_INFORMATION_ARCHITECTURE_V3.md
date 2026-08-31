# Cortex Information Architecture V3

## North star
Cortex is a cognitive operating surface, not a collection of feature pages.

The user-facing mental model is fixed to:

**Now → Inbox → Library → Ask**

Capture is a global action, not a destination.

## Cognitive authority

Cortex has one truth path for attention and open loops:

```text
Raw evidence
→ MasterRelevanceFilter / thread adjudication
→ review when uncertain
→ derived_items (durable cognitive state)
→ CandidateConsolidator
→ AttentionEngine + AttentionLearning
→ PrimeBriefStore
→ Now / Ask / proactive digest / daily-weekly brief
```

`derived_items` is the durable authority for ACTION, WAITING, DECISION, ALERT and CHANGE state. An open loop is a lifecycle state of canonical derived intelligence; it is not a second database or a second product model.

The legacy `actions` table may remain only where old capture/detail compatibility still requires it. It must never be consulted to answer “what needs attention?”, build Today, compose a current brief, or produce proactive attention notifications.

Do not reintroduce parallel stores such as `open_loops`, `attention_feed`, or `attention_actions`. Model output may enrich or adjudicate meaning, but it never becomes a parallel attention authority.

## Primary surfaces

### Now
Purpose: what deserves attention now.
Only grounded, non-empty groups may appear:
- Needs you now
- Waiting on
- Decisions to move
- Changed recently
- Worth knowing
- Needs review, when user judgement is required

Recent voice/capture context may be available below the attention state, but raw evidence, screenshots, missed calls, or generic memories must not surface merely because they are recent.

A clear horizon means the **attention state is empty**. Recent context by itself must not prevent Cortex from saying that nothing currently deserves attention.

### Inbox
Purpose: intentional intake.
Anything deliberately sent to Cortex appears here first. Save immediately; enrich asynchronously.
States are user-facing and explicit: Saved / Thinking / Ready / Preserved.

### Library
Purpose: durable organized knowledge.
The four primary object entrances are:
- People
- Projects
- Situations
- Memory

Do not duplicate these entrances with a second tab system. Search spans the whole Library.

### Ask
Purpose: natural-language access to Cortex intelligence and actions.
The normal UI does not expose provider/source routing controls. Cortex selects the route automatically and discloses provenance after the answer.

Operational questions such as “what needs my attention?”, “what am I waiting on?” and recent-decision queries must read the same `PrimeBriefStore` state as Now rather than reconstructing state from legacy tables or semantic similarity.

## Detail pattern
People, Projects, and Situations converge on one detail mental model:
1. Overview
2. Needs attention
3. Timeline
4. Connections
5. Evidence

Evidence is collapsed by default. It explains Cortex; it is not the product surface.

## Navigation rules
- Important information must be reachable within two taps from the primary navigation.
- No user-facing screen may require knowing which ingestion source created an item.
- No duplicate navigation layers for the same concept.
- Technical/test surfaces never appear in the primary navigation.

## Keep / merge / hide policy

### Keep as primary
- CompactTodayActivity → Now
- InboxActivity → Inbox
- ProposalPeopleProjectsActivity → Library
- ProposalAskCortexActivity → Ask

### Keep as secondary
- SettingsActivity
- VaultActivity as Memory/raw archive fallback
- ReviewQueueActivity when judgement is required
- PromptLibraryActivity and CorrectionLearningActivity under Settings

### Hide behind Advanced
- AttentionEvaluationActivity
- RelevanceEvaluationActivity
- CapabilityMatrixActivity
- EnvironmentActivity
- CortexStatusActivity
- CortexAuditActivity
- ExternalModelCheckActivity
- VisualIntelligenceActivity
- OcrTestActivity
- self-review/test surfaces

### Legacy implementation surfaces
Satin/Proposal/base activities may remain only when current primary surfaces still inherit utility behavior or when an old intent requires a compatibility redirect. They must not create parallel user-facing navigation, independent Today state, or a competing cognitive model.

## Product test
A new user should be able to answer these without explanation:
- What needs me now?
- Where do I put something?
- Where do I find a person/project/situation/memory?
- Where do I ask Cortex?

The answer to “what needs me now?” must be materially consistent whether it is viewed in Now, asked in Ask, delivered proactively, or included in a current brief.
