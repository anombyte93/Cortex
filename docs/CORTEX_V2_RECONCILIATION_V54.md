# Cortex V2 Reconciliation — v54

Branch: `fix/v2-reconciliation-v54`
Base: `migration/cognitive-brain-v2-step1-2`

## Non-negotiable invariants

- No PR merge is part of this reconciliation.
- Update in place only. Never uninstall Cortex or clear app data.
- Keep the installed Cortex signing identity unchanged.
- Cognitive database stays at `DB_VERSION = 7` and `REVISION = cognitive_004`.
- Preserve the V2 authority router, 5% guarded canary, validation provenance, shadow path, hard-noise gate and kill switches.
- Preserve the canonical truth path: Evidence -> derived canonical state -> Attention -> Now/Ask/Brief/Proactive surfaces.
- Groovy `app/build.gradle` remains canonical. Local experimental `app/build.gradle.kts` must not replace it wholesale.

## Reconciliation changes

- Release moves to `versionCode 54`, `versionName 2.0-v54` so it can update the validated v53 device build.
- Android automatic backup is disabled for private Cortex state. Explicit Cortex export/import remains available.
- Phone-only self review is no longer externally deep-link triggerable.
- Arabic OCR build asset is pinned to the known tessdata_fast 4.1.0 byte length and SHA-512 digest.
- The build no longer reconstructs the installed-app signer from tracked base64 material.
- The legacy encoded signing-material file is deleted from branch HEAD and future encoded keystore paths are ignored. Historical Git exposure still requires repository-history/visibility remediation.
- CI/repo audit now guards V2 schema/release/privacy/signing/build-file invariants.
- Backup export preserves the bounded streaming + consistent Vault snapshot implementation.
- Backup restore preserves streaming inspection, extraction limits, database-atomic restore and rollback cleanup.
- Approved semantic CardVariant/StatusDot palette is retained without reverting newer Now/presentation logic.

## Local-only UI preservation

The earlier Compose UI work was not committed to Git. Known local-only candidates included:

- `app/src/main/java/com/kareem/cortex/ui/`
- `app/build.gradle.kts`
- `gradlew`, `gradlew.bat`, and `gradle/` helper files

Do not `reset --hard`, blindly `stash`, or overwrite these files while reconciling them. Run `bash tools/preserve-v2-local-work.sh` in the existing device working tree first. It records tracked binary patches, untracked source/config files, repository state, and only the SHA-256 fingerprint of the local signer. It never copies signing key bytes.

After preservation, port only useful UI behavior/design into the canonical build. Do not replace canonical V2 cognitive files with stale UI-branch versions.

## Validation order

1. Repository audit and CI compile gate.
2. Build on the phone with the existing local Cortex signer.
3. Install with `pm install -r` via `/data/local/tmp` and verify `versionCode=54`.
4. Launch/crash-buffer check.
5. Extract `cortex.db` non-destructively and verify `PRAGMA user_version=7`, `integrity_check=ok`, and `schema_meta.cognitive_schema=cognitive_004`.
6. Verify visible Now/Inbox/Atlas/Ask/Capture behavior.
7. Re-run guarded V2 authority/canary provenance and real notification checks without changing the normal 5% production routing.

CI artifacts built without the local persistent signer are compile evidence only; they are not update-in-place installation artifacts.
