# Review fixes — the ten findings of `agent://ClientReview` (2026-10-04)

Scope: the ten findings of the independent correctness review, fixed in priority order on branch
`review-fixes` off `0c0a2eea`, one commit per decision. P1–P3 player-visible first; P4/P5 the
remaining two review findings; P6/P7 the per-launch false alarms; P8–P10 hygiene. Then the client
run with the user's real 12-mod set, reading registered before the run.

Work tree: a pristine `git worktree` at `/tmp/w7-reviewfix-wt` (`review-fixes`), so the shared
checkout and the peer lanes were never disturbed. Every "broken/not broken" claim below is grounded
in `javap` of the real staged base
(`p0/stage-1.21.1/merged-base/patched-mc-merged-1.21.1.jar`, `forge-runtime-interop.jar`) or the
real guest jars, and each fix was run over those real bytes offline before landing.

| # | Finding | Verdict | Commit |
|---|---|---|---|
| P1 | reflective id lookups ask only the newer generation's names | fixed (two-generation adapter) | `9a829fa1` |
| P2 | overlay-condition veto anchored on a method absent on 1.21.1 | fixed (re-anchor, generation-probed claim) | `524b7cab` |
| P3 | `FlowerPotRepairInjector` fully inert | fixed (all three sub-edits re-derived) | `9aa7f16f` |
| P4 | `RegistryAliasParityInjector` keys on the newer `Identifier` | fixed (`ResourceLocation` + newer) | `9ab4602f` |
| P5 | FRAPI evidence check misses Sodium's real entry point | fixed (entry point recognised) | `7d922d74` |
| P6 | `restoreDoublePrecisionToTheRandomSources` false-positive anchors | fixed (HEDGE) | `fc3c1cf9` |
| P7 | `forbric-dragon-parts` EnderDragonPart false-positive anchor | fixed (HEDGE) | `45590507` |
| P8 | stale `--mc 26.2` and other current-version docs | fixed (doc sweep, selective) | `33ece56e` |
| P9 | `buildForbricJars` forwards only 3 staged properties | fixed (whole `forbric.*` family) | `950d264b` |
| P10 | `bundleForbric` guard matches by substring | fixed (exact coordinate) | `0ddf6a91` |

---

## P1 — the player log's `NoSuchMethodException: …ResourceKey.identifier()` — `9a829fa1`

`javap -p patched-mc-merged-1.21.1.jar`:

```
net.minecraft.resources.ResourceKey        -> public ResourceLocation location()   (no identifier())
net.minecraft.resources.ResourceLocation   -> exists
net.minecraft.resources.Identifier         -> Error: class not found
```

Every reflective site asked the newer generation's spelling, absent here: `getMethod("identifier")`
threw on every call (the WARN the player saw) and `Class.forName("…Identifier")` threw
`ClassNotFoundException`, which `finishSnapshotApplication` catches and answers with the ERROR
"could not apply the server's ids … they keep their local ids" — so fabric-registry-sync id
remapping over Forge-wrapped registries silently kept local ids. Sites: `KernelForgeWrapperSync`
(`stageIdMapping`, `apply`, `registryName`), `KernelRegistryAliases.resolveKey`, `KernelLifecycle`
registration order, `KernelWrapperEntryEvents.resolve` (whose callback descriptor is built from the
resolved return type, so the wrong name also mis-typed it).

Fix: new `util/IdentifierNames` resolves the accessor `location()` then `identifier()`, and the
identifier class `ResourceLocation` then `Identifier`; the newer path is kept. Each call site is
documented with what its failure cost. Tests: `KernelRegistryAliasesTest` gains a key whose only
accessor is `location()` (red on the old `getMethod("identifier")`, green now) and a new
`IdentifierNamesTest` over both names and their preference.

## P2 — re-anchor the overlay-condition veto — `524b7cab`

`javap -p 'OverlayMetadataSection$OverlayEntry'` lists only `isApplicable`, the record accessors and
`lambda$static$0` — no `listCodecForPackType`. The funnel moved to the section:

```
javap -c OverlayMetadataSection -> lambda$static$0 (run from <clinit> via RecordCodecBuilder.create),
  offset 4: invokestatic net/neoforged/neoforge/common/conditions/ConditionalOps
              .decodeListWithElementConditions:(Lcom/mojang/serialization/Codec;)Lcom/mojang/serialization/Codec;
```

The repair now accepts both carriers — the 26.2 entry method (scoped to that method) and the 1.21.1
section (the single conditional decoder anywhere in the class) — and wraps whichever it finds; both
generations' code paths are kept. The claim's anchor is probed from the same bytes the repair will
see (`ByteScan` for `listCodecForPackType` in `OverlayEntry`): the running base's carrier is
REQUIRED and the other generation is not a Miss. Test: a synthetic 1.21.1 section (a `<clinit>` that
feeds the conditional decoder once) — red before, green now, idempotent.

## P3 — the flower-pot repair was inert on 1.21.1 — `9aa7f16f`

```
useItemOn(…) -> returns ItemInteractionResult          (injector asked for InteractionResult)
addPlant(net.minecraft.resources.ResourceLocation, Supplier)   (injector asked for Identifier)
(Block,Properties) ctor: aload_0/…/invokespecial <init>(Supplier,Supplier,Properties)/…/return;
  NO putfield potted — `potted = null` moved into the supplier ctor (offset 5-7 there)
isEmpty() is `potted == Blocks.AIR`, and Blocks.flowerPot(Block) passes the plant (AIR for the
  empty pot), so `potted = arg1` is right for both
```

Fix: `useItemOn`/`addPlant` accept both generations' descriptors; `storePlant` re-derives against
the delegating body — when the 2-arg constructor has no `putfield potted`, insert `this.potted =
arg1` right after its single `invokespecial <init>`, then the same `POTTED_BY_CONTENT.put`. Offline
probe on the real class: changed; the injected store's predecessor is `ALOAD 1`; `useItemOn` calls
`KernelFlowerPots.fullPotFor`; `BasicVerifier` passes; idempotent. New
`FlowerPotRepairInjectorTest` reproduces the 1.21.1 shape synthetically (red before, green now).

## P4 — alias parity keys on the wrong id type — `9ab4602f`

`javap -p forge-runtime-interop.jar`: `NamespacedWrapper`
`get/getOptional/containsKey/getHolder(ResourceLocation)`; `NamespacedDefaultedWrapper` a single
`get(ResourceLocation)`. The injector looked only for `Identifier`, so the whole id-keyed list was
skipped and the defaulted subclass got ZERO edits (the log's
`forbric-registry-alias-parity -> NamespacedDefaultedWrapper` Miss). Fix: match either generation's
argument type. Probe over the real classes: `NamespacedWrapper` hooks 4 → 8;
`NamespacedDefaultedWrapper` hooks 0 → 1. Test fixture corrected to the real `ResourceLocation`
name (red before, green now), with the newer `Identifier` spelling still covered.

## P5 — the `contains_renderer` false negative — `7d922d74`

The user's 12-mod console:

```
[Forbric/Fabric] sodium declares fabric-renderer-api-v1:contains_renderer, but the build that
  loaded never registers a Fabric renderer — not forwarding it, so Indigo takes the slot …
java.lang.UnsupportedOperationException: A second rendering plug-in attempted to register.
  at net.fabricmc.fabric.impl.renderer.RendererAccessImpl.registerRenderer(:33)
  at net.caffeinemc.mods.sodium.neoforge.SodiumForgeMod.<init>(:23)
```

`javap` of the real `sodium-neoforge-0.8.13` nested mod jar:

```
getstatic net/fabricmc/fabric/api/renderer/v1/RendererAccess.INSTANCE
getstatic net/caffeinemc/mods/sodium/client/render/frapi/SodiumRenderer.INSTANCE
invokeinterface …/api/renderer/v1/RendererAccess.registerRenderer(…/renderer/v1/Renderer;)V
```

The scanner looked only for `api/client/renderer/v1/Renderer.register` and
`impl/client/renderer/RendererManager.registerRenderer` — neither exists this generation. Fix:
recognise both generations' entry points (`RendererAccess`/`RendererAccessImpl`/`RendererManager`
with `registerRenderer`, and the `Renderer.register` direct form) and broaden the class-name needles.
Probe: `registersRenderer(<the user's real sodium jar>)` = true; `FrapiRendererEvidenceTest` gains
the new cases (5 tests, 0 failed), 26.2 cases kept.

Measured second effect, one wall later: with `contains_renderer` now forwarded, Sodium's NeoForge
side reaches and satisfies `ConfigLoaderForge` — `Sodium's config could not be found`, which killed
the same set on `main`, occurs 0 times on this batch and the boot proceeds to a world.

## P6 / P7 — the two per-launch false alarms — `fc3c1cf9`, `45590507`

P6: `restoreDoublePrecisionToTheRandomSources` matches the float form of the 2⁻⁵³ scaling; the
1.21.1 base is already double (`XoroshiroRandomSource.nextDouble` = `l2d/ldc2_w/dmul/dreturn`,
`BitRandomSource.nextDouble` the same), so it cannot fire and the two REQUIRED anchors were Misses
(the player log's BitRandomSource ERROR). P7: `EnderDragonPart` already extends
`net.neoforged.neoforge.entity.PartEntity<…>`, so `rebasePart` returns 0 by design.

Both target anchors become HEDGE, documented; the repair still fires the moment a carrier
reintroduces the shape (P6 fires on the 26.2 fixtures, where both are hits). No other anchor changes
severity. Tests pin the severities: P6 both HEDGE; P7 PART HEDGE and DRAGON/HITBOXES REQUIRED.

## P8 / P9 / P10 — hygiene — `33ece56e`, `950d264b`, `0ddf6a91`

P8: `Main`'s usage javadoc said `--mc 26.2 (default 26.2)` while the code reads and prints
`Pins.MINECRAFT` (1.21.1); same stale current-version examples in `Installer`/`ForgeArtifacts`, and
`JdkLocator`'s comment/message named 26.2. Fixed; the 26.2 references that are history or
generation contrasts (Pins' retarget note, BuildStamp, MergedBaseTool, NfrtRunner, ArtifactBuilder,
the test fixtures) were deliberately NOT swept — they are the record, not drift.

P9: `buildForbricJars` forwarded `stagedRoot/fabricApi/rebornEnergy` only, while the kernel also
reads `mcVersion`/`fabricApiVersion`/`mcLibraries`/`requireTransfer`; now it forwards every
`forbric.*` property the caller supplied.

P10: `bundleForbric`'s guard was `coordinate.contains('forbric-kernel')`; now the exact
`net.forbric:forbric-kernel:` coordinate.

---

## Verification

- Kernel compile clean; installer compiles and its Gradle tasks parse.
- Full kernel suite on the rebased branch: **2945 tests, 983 skipped, 31 failed** — and the exact
  same 31 failures (12 classes, all staged-fixture-absent NPEs / `assume`-mismatches) reproduce on
  `main` `58c2e0e6` itself (**2933 tests, 983 skipped, 31 failed**). The branch adds 12 tests, all
  passing; no regression.
- Per-fix red→green tests as listed above; a throwaway offline probe ran every transform over the
  real 1.21.1 bytes (removed after use).

## Client run — handed to `W7Harness`, reading registered before the run

Request: build from `/tmp/w7-reviewfix-wt/forbric-kernel` (HEAD `e11e4fcb`, the rebased batch on
top of main) and run the user's real 12-mod set (Mod Menu subject, the other eleven closure).
Pre-committed reading, registered before the run: `confirmed_required: 0`; `world=true` and
`joined world via quick-play: 1`; the P1 console lines absent (`NoSuchMethodException` with
`ResourceKey.identifier()`; `could not apply the server's ids` / `they keep their local ids`); plus
the two P6/P7 `[Forbric/Anchor] … made no edit` lines absent.

The wall that blocked the previous run of this exact set — `Minecraft.<init>` dying on
`NoClassDefFoundError: Could not initialize class net.neoforged.neoforge.resource.ResourcePackLoader`
— is fixed on `main` (`0c0a2eea`, tip `58c2e0e6`), and the batch was rebased onto main so the run's
worktree carries it. That is why this run can be expected to reach a world where the last one could
not.

**Result** (`W7Harness`, `reports/2026-10-04-reviewfix-usermods/`, kernel sha
`451c6780f682df26bfc9b7fbc5eb26d17c25f7973219b2cfe3cceda945d1025f` from `e11e4fcb`, the ten rebased
fixes on main):

```
run=PASS  exit=0  world=true  frames=1  stopped=true  killed=false  strict=TRUE
confirmed_required=0  seconds=102  java=jdk-21  joined world via quick-play: 1
```

All four registered reads met:

1. `confirmed_required: 0` — enumeration empty.
2. `world=true` and `joined world via quick-play: 1` — the first world-depth pass of this full set
   on any build today.
3. Both P1 lines absent: **0** occurrences of `ResourceKey.identifier` anywhere
   (`NoSuchMethodException` appears twice, on unrelated sites); `could not apply the server's ids`
   **0** and `they keep their local ids` **0**.
4. Kernel sha recorded above.

The two P6/P7 HEDGEs are confirmed gone against `main`'s own row on the same set: `BitRandomSource`
"made no edit" 1 → 0, `forbric-dragon-parts`/EnderDragonPart 1 → 0.

**Second effect of P5, recorded here rather than as a separate finding:** the last run of this set on
`main` died inside Sodium's `ConfigLoaderForge` — `Sodium's config could not be found`. On this batch
that line occurs **0** times and the boot reaches a world: the same evidence-check fix, seen one wall
later. The batch therefore not only passes the wall `main` fixed (ResourcePackLoader); it clears a
wall `main` still had.
