# Pre-registration — 2026-10-08 `play-payload-dispatch` lane

*(Written before any arm was run. Criteria below are only ever "met" or "falsified"; the text is not edited
afterwards — corrections go in §Adjudication.)*

## 0. Question

The `2026-10-08-cannot-fire` report (§4) named **two** candidates for the defect its §3 measured: the merged
`ServerGamePacketListenerImpl.handleCustomPayload` is MinecraftForge's override, it POPs Forge's answer and returns
without ever calling `super`, and NeoForge's dispatcher lives on exactly that super — so a NeoForge mod's
serverbound PLAY payload reaches nobody (in singleplayer too). The kernel's existing fall-through
(`CommonNetworkInteropInjector.letNeoForgePayloadsThrough`, gate `-Dforbric.playPayloadFallThrough`, default OFF)
reaches the super and is then killed by a second defect: `fabric-networking-api-v1`'s `ServerCommonNetworkHandlerMixin`
injects at the HEAD of that shared super and throws `IllegalStateException: Unknown addon` for any addon that is not a
`ServerConfigurationNetworkAddon` — a PLAY listener's addon is a `ServerPlayNetworkAddon`.

This lane implements **candidate (b) — do not borrow the super**. The fall-through calls NeoForge's dispatch tail
(`net.neoforged.neoforge.network.registration.NetworkRegistry.handleModdedPayload`) directly. The safe population is
already pinned by the existing gate: `PayloadInterop.neoForgeWillHandle` admits only payload ids that have a
**registered handler** (`PAYLOAD_REGISTRATIONS`), and the five payloads the super body dispatches before its tail
(`MinecraftRegister/UnregisterPayload`, `CommonVersion/RegisterPayload` in `BUILTIN_PAYLOADS`) are codec-only entries
with no handler, so they can never be in that population. Fabric's invariant — that its shared handler is only
entered from a configuration listener — is therefore kept instead of broken, and `ServerPlayNetworkAddon` receive
(its own PLAY mixin, at the HEAD of the PLAY override) is untouched.

The lane also flips the fall-through **default to ON**: the only reason it shipped OFF was the disconnect, and (b) is
what removes it. `-Dforbric.playPayloadFallThrough=off` remains as the falsifier arm.

## 1. The shapes measured (byte for byte)

- Implementation commit: this lane's, on top of `4ecde53a`.
- Kernel under test: `kernel-playpay.jar` — the **published 0.3.7-beta kernel** (sha
  `48df3d2b90dfec3926fbfcee92dc6bd8a441760f27560fd7d168169d81e2fa97`, built from `4f4935b9`, JDK 25) with the
  **single** class `net/forbric/kernel/transform/CommonNetworkInteropInjector.class` (sha
  `6d3516a3bf73d88768b4afdbf7d350eeee5d9d20e13a344f46d9bfaa1ab4d585`) replaced by this lane's recompile.
  `git diff --stat 4f4935b9 HEAD -- forbric-kernel/src/main` is empty, so no other boot class differs by source;
  an entry-by-entry sha comparison of the two boot halves shows this is the ONLY class that differs at all.
  `kernel-playpay.jar` sha256 `97d894f5946829d46a163e179cc71508424bb8d4bf21ae2534acafa555d6780d`, 3290882 bytes,
  nested game side present.
- Baseline kernel (falsifier F2 only): `/private/tmp/rel037/artifacts/release-kernel.jar`, sha
  `48df3d2b90dfec3926fbfcee92dc6bd8a441760f27560fd7d168169d81e2fa97` — no fix.
- Merged base: `stubtable/stage/merged-base/patched-mc-merged-1.21.1.jar`, sha
  `46af05299bafd3140321b25246138def88dd4f938b9ae9451f976b57bd1c587f`.
- NeoForge runtime: `stubtable/stage/neoforge-runtime/neoforge-runtime.jar` (NeoForge 21.1.252), sha
  `3d862fd92a42efddca77ba3d97d7a53229b4386b9fa8b4bdf6f8ffa01d1d0494`.
- Corpus: `/private/tmp/rel037/corpus14` — the user's real 14 jars; subject `the-shooting-star-demo-1.3.1-neoforge.jar`
  (`8b972bce…`), closure = 13 jars. Per-jar sha list is `./mod-set.txt`.
- Instrument: the mod's own dev hook, `dev.aek.shootingstardemo.mc1211.devtest.DevAutoTest`, shipped OUTSIDE the
  artifact under test at `/private/tmp/cannotfire/probe/cannotfire-probe.jar`, sha
  `2538eb8c9f73f64f429b15f90d1b136b98f64aab13877a51e2ed28119b073d39` — the same instrument the `cannot-fire` lane
  used, driven by `run_with_probe.py` (w7/harness/sweep_client.py + the probe on `--libraryPath`).

## 2. The instrument's own two boundaries (stated up front)

1. **No input injection.** W7Harness has no mouse/keyboard injection, so "click to cast" cannot be scripted. The cast
   is driven through the mod's own extension point instead (`DevAutoTest` calls the exact method the click reaches,
   `ShootingStarDemoClient.onUse`, and polls `ClientSkills.remaining`, which only a server-sent `CooldownPayload`
   writes). This is unchanged from the `cannot-fire` lane and was accepted there.
2. **`-Dforbric.debug` is additive.** It only turns on `PayloadInterop.probe` output; it changes no decision.

## 3. Criteria (pre-committed)

Primary arm **FIX** = `kernel-playpay.jar`, no `-Dforbric.playPayloadFallThrough` (i.e. the new default), probe present.

- **R1 — cast accepted.** The probe prints `RESULT=CAST_ACCEPTED` (a non-zero `ClientSkills.remaining` ⇒ the server's
  NeoForge handler ran and `Casting.syncCooldowns` sent `CooldownPayload` back). A raw `RESULT=CAST_DROPPED` is a
  **falsification of R1**.
- **R2 — no kick.** The console has no `lost connection` of any kind AND the harness reaches its own
  `ClientSmoke] clean disconnect observed`; the row reads `run=PASS`, `exit=0`, `world=true`, `stopped=true`,
  `killed=false`.
- **R3 — "Unknown addon" = 0.** Zero occurrences of `Unknown addon` in the run console.
- **R4 — standard row green.** The `shooting_star_demo` row is **not worse** than the frozen 0.3.7 baseline: `run=PASS`,
  `exit=0`, `world=true`, `frames>=1`, `confirmed_required=0`, and `mod`/`catalog_failures`/`cause` equal to the
  baseline (`DEGRADED` / `['shooting_star_demo']` / `mod-degraded`, the documented `AbiLinkAudit` false positive on
  the jar's dead `mc1201` half). "Green" means exactly this shape; a *change* in any of those fields is a
  falsification.

Falsifiers:

- **F1 — repair off reproduces the original defect.** Same jar, `-Dforbric.playPayloadFallThrough=off`: the probe must
  read `RESULT=CAST_DROPPED` (no `CooldownPayload` ever arrives). Pre-committed: this is the *falsifier for the fix*,
  so a `CAST_ACCEPTED` here would mean the run was not actually measuring the rewrite.
- **F2 — the fix, not the gate, removes the disconnect.** Baseline kernel (`48df3d2b…`) + `-Dforbric.playPayloadFallThrough=on`:
  the console must show `IllegalStateException: Unknown addon` and the player being kicked. This is the second defect
  as the `cannot-fire` lane reproduced it; this lane re-runs it so the before/after differs by exactly one class.

Fabric:

- **F3 — fabric's own PLAY receive still works, or the loss is recorded.** With `-Dforbric.debug=true` on the FIX jar,
  look for `[Forbric/Net] addon ServerPlayNetworkAddon <- …` (fabric's PLAY addon actually handling a payload) and for
  fabric's PLAY mixin still applied to `ServerGamePacketListenerImpl`. If the corpus exercises no fabric PLAY payload,
  say so and record the structural evidence instead (the mixin is present and the fall-through runs only *after* it
  declines) — do not claim a functional reading that was not taken.

## 4. Offline shape check (before any game run; NOT a substitute for R1–R4)

`DispatchProbe` runs the shipped injector over the REAL merged `ServerGamePacketListenerImpl`:
`off` must come back byte-identical (12 insns, sha `27ccfef8…`, the same reading `cannot-fire/evidence/payload-path.txt`
recorded); `on` must emit `ForgeHooks.onCustomPayload` → `PayloadInterop.neoForgeWillHandle` → `invokestatic
NetworkRegistry.handleModdedPayload(ServerCommonPacketListener, ServerboundCustomPayloadPacket)V`, with **no**
`invokespecial ServerCommonPacketListenerImpl.handleCustomPayload`, and the descriptor must equal the one the merged
super body itself calls. A `BasicVerifier` pass on the rewritten method is required too.

---

## §Adjudication

*(appended after the arms; the criteria above are unchanged)*

### §Adjudication (appended after the arms; the criteria above are unchanged)

**Offline shape check (§4) — met.** `evidence/payload-path.txt`. `off` byte-identical (12 INSN, sha
`27ccfef8f2ce4673be34bb2d2ac535c235cfa8e9501732d3efe47f9e54692ae7` — the same value `cannot-fire` recorded), `on`
20 INSN with `ForgeHooks.onCustomPayload` → `PayloadInterop.neoForgeWillHandle` → `invokestatic
NetworkRegistry.handleModdedPayload(…ServerCommonPacketListener;…ServerboundCustomPayloadPacket;)V`, **no**
`invokespecial …handleCustomPayload`, descriptor equal to the merged super body's own call, `BasicVerifier` OK.

**R1 met** in two arms: `RESULT=CAST_ACCEPTED`, cooldown 1197 (FIX) / 1189 (FIX-DEBUG) ticks.
**R2 met**: `lost connection` = 0; `ClientSmoke] clean disconnect observed`; row `run=PASS exit=0 world=true
stopped=true killed=false`.
**R3 met**: `Unknown addon` = 0 in FIX, FIX-DEBUG and OFF.
**R4 met**: `evidence/row-compare.txt` — every judged field identical to the 0.3.7 gate14 row (`DEGRADED` /
`['shooting_star_demo']` / `mod-degraded` / `loaded=false` / `strict=false`); only `cpu_busy_pct`, `load_1m` and
`seconds` differ, all arms flagged `contended=true` under 300 %+ CPU.
**F1 met**: `=off` → `RESULT=CAST_DROPPED`, `remaining` = 0 throughout, 0 announcements of the rewrite.
**F2 met**: release kernel + `=on` → 3 × `Unknown addon`, `ForbricKernel lost connection: Internal Exception: …
Unknown addon`, `DisconnectedScreen`, row `STALL frames=0 killed=true` (the cannot-fire probe-ON arm, re-run so
FIX/OLD-ON differ by exactly one class).
**F3 met, as a functional reading**: `ServerPlayNetworkAddon <- shooting_star_demo:cast [CastSkillPayload]`
(declined by Fabric, then the gate fired: `neo owns shooting_star_demo:cast — handing the play payload to its
dispatcher`) and `ServerPlayNetworkAddon <- minecraft:register [MinecraftRegisterPayload]` (handled by Fabric).
Fabric's PLAY mixin is still woven and still calls `ServerPlayNetworkAddon.handle` in the runtime bytes. No loss to
record here.

**Two things the criteria did not cover, recorded:**

1. The super body's five pre-tail branches remain unreachable in PLAY — unchanged by this lane, impossible for the
   gated population (see the README §7), and a separate question the `cannot-fire` report already named.
2. `CommonNetworkInteropInjectorTest`'s real-bytecode assertions are skipped on this machine (the file pins the 26.2
   merged base; only 1.21.1 is staged). The equivalent check was run against the real 1.21.1 merged class by the
   offline probe, and the shape was seen again in the live arm's exported bytes.

**Machine load**: every arm ran at 300 %+ CPU across 8 cores (`contended=true`, load 6.0–9.2) — the same rule-3
condition the cannot-fire arms carried. No criterion in this lane is time-based.

**Correction to §1 (not a criterion):** the sentence "`git diff --stat 4f4935b9 HEAD -- forbric-kernel/src/main` is
empty" described the tree *before* this lane's commits. After them the same command names exactly this lane's one
file (`CommonNetworkInteropInjector.java`, 62+/22-) and nothing else — the same fact, stated on the other side of the
commit. The entry-by-entry sha comparison of the two boot halves was taken before the change and is what pins "the
only class that differs is this one".
