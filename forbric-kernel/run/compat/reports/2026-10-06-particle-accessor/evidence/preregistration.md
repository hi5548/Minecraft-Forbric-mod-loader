# Pre-registration — the fabric-particles-v1 interface-mixin rejection (`ParticleEngine is not an interface`)

Written **before** the W7Harness run. The reading below is a prediction, not a result; §Adjudication in the report
is filled in afterwards and must not be edited into agreement with the run.

## 0. What is being measured

**The defect (carried in the 2026-10-06-bughunt-create report as §4, "recorded shape and cost, not fixed").**
The kernel's merged-base repair rewrote fabric-api's `ParticleManagerAccessor.getFactories()` from an `@Accessor`
into a default method over `ParticleEngine.forbric$providerFactories(ParticleEngine)`, because the merged base's
`ParticleEngine` has ONE `providers` field and it is `Map<ResourceLocation, ParticleProvider<?>>` — the
`Int2ObjectMap` field fabric-api's refmap names does not exist. A real default method makes Mixin classify the
interface mixin as `Variant.INTERFACE` (`MixinInfo.getVariant` answers INTERFACE as soon as one method is neither an
`@Accessor`/`@Invoker` nor synthetic; the shipped form's three `@Accessor`s answer ACCESSOR), and the INTERFACE
variant's `SubType.validateTarget` demands an interface target — `ParticleEngine` is a class — so
`MixinConfig.prepareMixins` throws and the whole `fabric-particles-v1.client.mixins.json` is dropped.

**The fix (this lane).** `ACC_SYNTHETIC` on the rewritten default method, one flag, in
`ForbricMergedBaseCompatTransformer.readTheFactoriesThroughThatBridge`. It is the classification's own "not a real
interface method" signal: the classifier skips synthetic methods, so the mixin goes back to ACCESSOR, and Mixin's
interface preprocessor merges a PUBLIC synthetic default like any other method (only a private synthetic is set
aside). This is the preferred repair — the mixin keeps applying, rather than being pinned/suppressed with the
accessor's function recorded as lost.

**Frozen arm.** Kernel `kernel-fix.jar` sha256 `ffdb6979f805da85d4af1d464872549413d47bc3ddfc350a8b37055a358a5de3`:
boot half `./gradlew --offline -q -Pforbric.stagedRoot=<empty run/> jar` from this tree (main tip `de13d8ee`), with
`META-INF/jars/forbric-kernel-runtime.jar` taken byte-for-byte from the published `0.3.5-beta` jar (the runtime half
is untouched by this lane; `/Volumes/ORICO` unmounted and `energy-4.1.0-named.jar` absent, so the game side cannot
be rebuilt here — same assembly as `2026-10-06-bughunt-create`).

*(Operational note, added after the run and NOT a criterion change: the first build of that jar carried pre-existing
stale `X N.class` / `X 3.*` duplicates from this working tree's build outputs, which the release capture forbids. The
debris was deleted, the boot half rebuilt, and the run re-measured; both readings are in `gate-reading.txt`. The
prediction below is untouched and the criteria are identical.)*

**The "before".** Kernel `a56bf626…` (= published `0.3.5-beta`), same repair, same boot-half code path. Its reading
on this defect is already in the tree and is quoted, not re-measured:
`2026-10-06-bughunt-create/evidence/console-markers.txt` —
`[Mixin/mixin] @Mixin target type mismatch: net.minecraft.client.particle.ParticleEngine is not an interface in
org.spongepowered.asm.mixin.transformer.MixinInfo$SubType$Interface@…`, plus the same
`InvalidMixinException` on the next line. Create is irrelevant to it (the report's §4 states the causal chain is
independent of Create and the line is present in both arms).

**Set.** The user's real 12 mods, copied byte-for-byte from `/Applications/.minecraft/versions/1.21.1-forbric/mods/`
(sha256 in `evidence/mod-set.txt`), subject `modmenu-11.0.5.jar` (fabric, `kind=popular`), the other 11 as its
closure.

**Instrument (fixed before the run).** `w7/harness/sweep_client.py` (client, quick-play into `W7Client`,
`-Dforbric.compatibilityPolicy=strict`, `-Dforbric.mixinFit=default`), `W7_JAVA` pinned to JDK 21.0.7, one subject,
`--kernel-jar /tmp/particle-lane/kernel-fix.jar`, stage / mc root / corpus / warm remap cache under
`/private/tmp/particle-lane/`, boot timeout 600 / stall 300 (only the timeouts widened, no criterion changed).

## Pre-committed reading

**Prediction: the rejection is gone and the 12 mods boot `strict` exactly as on `a56bf626`.**

Ranked, falsifiable, in the order the bytes would name them:

1. **Boot gate.** `run=PASS`, `exit=0`, `world=true`, `frames>=1`, `stopped=true`, `killed=false`, `strict=TRUE`,
   `compatibility_policy=strict`, `mixin_fit=default`, `confirmed_required=0`, `loaded=true`, `na=false`; the 11
   dependencies all `OK`; `catalog_failures` empty; 0 crash-reports; `joined world via quick-play` ≥ 1.
2. **The rejection is absent.** `grep -c "@Mixin target type mismatch"` = **0** and
   `grep -c "ParticleEngine is not an interface"` = **0** in the run's `console.log`. (On the same 12-mod set this was
   the released kernel's only mixin ERROR of this family.)
3. **The repair ran, in its new shape.** `grep -c "SYNTHETIC default method over ParticleEngine"` ≥ **1** — the
   marker the fixed transformer logs, i.e. the bytes that booted are the fixed bytes.
4. **Nothing else moved.** The `[Forbric/Seed] seeded NeoForge LoadingModList with 64 mod(s)` line is still present;
   the load-report aggregate sentence is still the one `0.3.5-beta` shipped (`N mod(s) 有一部分没有跑起来`, no
   `FAILED` row for these 12); no new mixin ERROR for any other config.

**Falsifier.** If (2) fails — the line is still there — the fix is falsified for this arm and the report must say so
verbatim and name the fallback (pin the config in `SUPPRESSED_MIXINS`, carry the accessor in the kernel's
adapter/pin convention, state the loss). If (1) fails, or a NEW error appears for
`fabric-particles-v1.client.mixins.json` (`InvalidAccessorException`, `InvalidInterfaceMixinException`,
`InvalidMixinException` on the accessor, or a crash in particle registration), that error IS the adjudication: the
synthetic-default shape is rejected downstream and the fallback is what the bytes name.

## What this run does NOT cover (declared before it runs)

- **The accessor's function is not exercised.** No mod in these 12 calls `ParticleFactoryRegistry` / the accessor.
  The run proves the mixin now APPLIES; it does not prove a custom particle factory registers. The classification
  proof is the unit probe (`build/test-results`), not the boot.
- No in-world particle behaviour, no pixel reading, no second arm: literally one W7Harness run, as asked.

## Adjudication

Filled in after the single run. The prediction above is unchanged.

**All four pre-committed items hold; the falsifier did not fire.**

*(Two readings were taken: the first on a jar whose boot half carried pre-existing stale duplicate entries from this
working tree's build outputs — see the operational note in §0 and `build-provenance.txt`. Its reading was
`run=PASS world=true frames=1 strict=TRUE` with the same marker counts (0 / 0 / 2). It is superseded on artifact
hygiene only; the reported reading is the re-measure on the clean jar, `ffdb6979…`. No criterion changed.)*

| # | committed | read |
|---|---|---|
| 1 | boot gate | `run=PASS exit=0 world=true frames=1 stopped=true killed=false strict=TRUE`, `confirmed_required=0`, `catalog_failures=[]`, `loaded=true`, `na=false`, 11 deps `OK`, 0 crash-reports, `joined world via quick-play` = 1 — **held** |
| 2 | rejection absent | `grep -c "@Mixin target type mismatch"` = **0**; `grep -c "ParticleEngine is not an interface"` = **0** — **held** |
| 3 | repair marker | `grep -c "SYNTHETIC default method over ParticleEngine"` = **2** (main thread + Render thread), i.e. the fixed bytes booted — **held** |
| 4 | nothing else moved | `seeded NeoForge LoadingModList with 64 mod(s)` present; the fabric-particles-v1 entry in `load-report.txt` is **byte-identical** to the 0.3.5-beta release gate's (only the pre-existing `BlockDustParticleMixin` suppression; the accessor is not named) — **held** |

**What the run establishes, exactly.** The console no longer carries the rejection, the mixin is *kept* rather than
suppressed (`[Forbric/Mixin] guest accessor mixin fabric-particles-v1 …:ParticleManagerAccessor cannot bind — … (kept;
…)` moved from the pre-flight's general path to its pure-accessor path), no `InvalidAccessorException` /
`InvalidInterfaceMixinException` appears, and the boot is `strict`. That `ParticleManagerAccessor` was actually
APPLIED on the merged `ParticleEngine` is [INFERENCE] from the absence of the exception plus the fact that Mixin had
already accepted the classification (`evidence/probe-output.txt`) — this run has no observer that reads
`ParticleEngine.factories`, because none of these 12 mods registers a particle factory. Recorded as a declared gap,
not as a claim.

**One thing the run surfaced that the prediction did not mention** (recorded, not a criterion): the kernel's
pure-accessor pre-flight prints `cannot bind — @Accessor field ParticleEngine.textureAtlas:…:…` for the two remaining
sprite accessors, 56 times across the fabric modules. It is a **pre-existing false positive** in `MixinFit`'s
accessor anchor parser (it feeds the remapped `name:desc` member-selector string straight into a bare-name field
lookup), the fields demonstrably exist, and Mixin parses that same string with its target-selector parser. Full
argument in `evidence/byte-evidence.txt` §6. Not touched by this lane.
