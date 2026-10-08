# Pre-registration — the client's datapack-registry declaration runs before the NeoForge bus exists, so `neoforge:biome_modifier` is never declared

Written **before** either W7Harness run. The reading below is a prediction, not a result; §Adjudication is filled
in afterwards and must not be edited into agreement with the run.

## 0. What is being measured

**The context (the user's own log, `/Applications/.minecraft/versions/1.21.1-forbric/logs/latest.log`,
2026-10-08, kernel `forbric-kernel-0.3.6-beta.jar`, the one run that instance has ever made with the two
Shooting Star jars installed).** Four anchors, in log order:

- `998: [10:34:56] [Forbric/Lifecycle] posted datapack-registry declaration to 0 bus(es) — 0 declared ([]), 25 total`
- `999: [Forbric/Worldgen] declared forge:biome_modifier and forge:structure_modifier on NeoForge's
  datapack-registry list (unsynced, as ForgeMod declares them) — MinecraftForge's serializer registries hold -1
  biome and -1 structure modifier serializer(s)`
- `1134: [10:34:59] [Forbric/Lifecycle] datapack-registry declaration waits for the Fabric main and client
  entrypoints in Minecraft.<init> — its initialisers run Fabric mod code, which must not run before those mains`
- `1608: java.lang.IllegalStateException: Missing registry: ResourceKey[minecraft:root / neoforge:biome_modifier]`
  — from `ServerLifecycleHooks.runModifiers(ServerLifecycleHooks.java:162)` under
  `KernelNeoWorldgen.applyBiomeAndStructureModifiers(KernelNeoWorldgen.java:299)`, preceded by
  `[Forbric/Worldgen] NeoForge's biome/structure modifiers did not apply — -1 biome and -1 structure modifier(s)
  were loaded and none of them changed the world. The server start is unaffected`.

Plus the `MISSING` block the context names (the vanilla "version differences that were not resolved" list —
`shooting_star_demo (version 1.3.1-demo -> MISSING)`, `shooting_star_addition (version 1.1.0 -> MISSING)`, and the
NeoForge-side mods), at 1540/1576/2868/2999; and the first fatal throwable in that log, line 1491,
`net.minecraft.server.ChainedJsonException: Invalid shaders/core/halley_sky.json` (out of scope here).

**The defect.** On a client the Fabric client-entrypoint hook is wired into `Minecraft.<init>` AHEAD of the call
that drives `KernelLifecycle.driveNativeRegistration`, so the ordering in the log is: client entrypoints 10:34:55
(line 921), the **deferred declaration 10:34:56 (line 998)**, the mod-loading window and the NeoForge baseline
construction 10:34:57 (lines 1018/1026), step 3a 10:34:59 (line 1134). The deferred call therefore reaches
`registerDataPackRegistries` with **no NeoForge bus in existence** (`baselineBus == null`); it posts the
`DataPackRegistryEvent.NewRegistry` to nobody, declares nothing, and spends the process-wide one-shot
`DATAPACK_REGISTRIES_DECLARED`. `neoforge:biome_modifier` and `neoforge:structure_modifier` are declared by
**NeoForge's own baseline listener** (`NeoForgeMod.lambda$new$71`, verified in the staged
`neoforge-runtime.jar`: `dataPackRegistry(NeoForgeRegistries$Keys.BIOME_MODIFIERS, BiomeModifier.DIRECT_CODEC)` and
the structure twin) and by nothing else, so both are absent for the session. `ServerLifecycleHooks.runModifiers`'
first instruction is `registryOrThrow(neoforge:biome_modifier)`, so it throws before reading the first modifier and
**every** biome/structure modifier is skipped — NeoForge's own and MinecraftForge's, which ride the same pass
through `ForgeWorldModifierInjector`. Every mod's own declared datapack registry goes with them.

**The control.** Genuine NeoForge on the sibling instance `/Applications/.minecraft/versions/1.21.1-NeoForge` has
nine mods shipping `data/<ns>/neoforge/biome_modifier/*.json` (create, create_new_age, createnuclear,
farmersdelight, mekanism, mekanismadditions, powah, zeta, balm) and **zero** `Missing registry:
… neoforge:biome_modifier` lines anywhere in that instance, across 1340 log/config/datapack files. NeoForge logs no
success line for the registry, so absence **is** the working signal there.

**The fix (this lane, boot half only).** Two edits, no new class, and no new declaration code:
`DatapackRegistryDeclaration.waitsForFabric(side, fabricActive, mainsInConstructor, mainsAlreadyRan)` returns false
once the Fabric mains have already run (its only reason to wait is "later than the mains", and by step 3a they have
run), and `KernelLifecycle.registerDataPackRegistries` checks `baselineBus` by field read **before** the once-guard
and returns without spending it when no NeoForge bus exists yet. The baseline-bus post at
`KernelLifecycle.registerDataPackRegistries` is left exactly where convention puts it — nothing new declares
`neoforge:biome_modifier`.

**Frozen arms.** `before` = the published kernel the user ran,
`/Applications/.minecraft/libraries/net/forbric/forbric-kernel/0.3.6-beta/forbric-kernel-0.3.6-beta.jar`, sha256
`16bcd60242c54890fda728e294ad41935619ff4b499f359f539d6e44dae45b74` (its `forbric-kernel/src` is identical to this
tree's HEAD — `git diff --stat bf738f8b a6572f82 -- forbric-kernel/src` is empty). `after` =
`/private/tmp/shooting-lane/kernel-fix.jar`, sha256
`1543b40fd3dc8fd0f01126b29f931f6c182738e787a4d3c3f26781bc0f0dd846`: the boot half built from this tree
(`./gradlew --offline -q -Pforbric.stagedRoot=<empty> jar`, no stale `X N.class` debris — 734 entries, 0
dup-shaped) with `META-INF/jars/forbric-kernel-runtime.jar` taken **byte for byte** from the published jar, because
`energy-4.1.0-named.jar` is absent on this box and the game side cannot be rebuilt here. The two arms differ only
in the boot half, and only in those two edits.

**Set.** The user's real 14 mods, copied byte for byte from
`/Applications/.minecraft/versions/1.21.1-forbric/mods/` (sha256 in `mod-set.txt`), subject
`the-shooting-star-demo-1.3.1-neoforge.jar` (neoforge) with the other 13 as its closure — i.e. the exact scenario
the context describes. Corpus at `/private/tmp/shooting-lane/corpus` (`manifest.json` row added by this lane; the
12 mods and the `W7Client` fixture are the frozen W7 set).

**Instrument (fixed before the runs).** `w7/harness/sweep_client.py` (client, quick-play into `W7Client`,
`-Dforbric.compatibilityPolicy=strict`), `W7_JAVA` pinned to JDK 21.0.7, one subject, `--only
the-shooting-star-demo`, stage `实验/forbric/stubtable/stage`, MC root `实验/forbric/stubtable/mc`, shared warm
remap cache `/private/tmp/shooting-lane/remap`, boot timeout 900 / stall 300 (only the timeouts widened, no
criterion changed). Nothing in the kernel tree is rebuilt between the two runs; each arm pins its own
`--kernel-jar`.

## Pre-committed reading

**Prediction: the client declares `neoforge:biome_modifier`, and the modifier pass runs instead of throwing.**

Ranked, falsifiable, in the order the bytes would name them:

1. **The `before` arm reproduces the context.** In its `console.log`:
   `posted datapack-registry declaration to 0 bus(es) — 0 declared` present; `NeoForge's biome/structure modifiers
   did not apply — -1 biome and -1 structure modifier(s)` present; `Missing registry: ResourceKey[minecraft:root /
   neoforge:biome_modifier]` present. This is the failure this lane claims to have reproduced.
2. **The `after` arm does not.** The `Missing registry: … neoforge:biome_modifier` line is **absent**, the
   `did not apply — -1 biome and -1 structure modifier(s)` line is **absent**, and instead the console carries
   `applied NeoForge's 0 biome modifier(s) and 0 structure modifier(s)` — 0 because no mod in this 14-mod set ships
   a biome or structure modifier (verified: neither Shooting Star jar carries any `biome_modifier`/`structure_modifier`
   data and neither class references `BiomeModifier`).
3. **The declaration ran, and reached the bus.** The `after` console carries
   `posted datapack-registry declaration to N bus(es) — M declared (…)` with **N ≥ 1** and the declared list naming
   `ResourceKey[minecraft:root / neoforge:biome_modifier]` and `ResourceKey[minecraft:root / neoforge:structure_modifier]`
   — i.e. the baseline bus was reached and NeoForge's own listener declared them. It also carries the new
   `datapack-registry declaration asked for before any NeoForge bus exists` line, because the client-entrypoint
   hook still asks first; that call must NOT be the one that declares.
4. **Nothing else moved.** Both arms: `run=PASS`, `world=true`, `frames>=1`, `joined world via quick-play` present,
   `confirmed_required=0`, `catalog_failures=[]`, 0 crash-reports; the 13 dependencies read `OK`; and the
   `[Forbric/Seed] seeded NeoForge LoadingModList with N mod(s)` line is present in both. The other lines the
   context names — the `halley_sky` shader failure, the `Unable to find registry with key
   forge:biome_modifier_serializers for mod "forge"` suppression, the `Illegal packet received` disconnect — are
   **expected unchanged**; they are not this lane's.

**Falsifier.** If (2) or (3) fails — the exception is still there, or the declared list does not name
`neoforge:biome_modifier` — the fix is falsified for this arm and this report must say so verbatim, name the
surviving call path from the console, and state the fallback (declare NeoForge's two registries explicitly in the
kernel's own second `NewRegistry` event, the shape `declareMinecraftForgeModifierRegistries` already uses for
MinecraftForge's two, and record that the merged base's own listener was not reached). If (1) fails — the `before`
arm does not reproduce — the report must say the failure was not reproduced on this box and give the arm's verbatim
lines instead.

## What these runs do NOT cover (declared before they run)

- **No mod in this set ships a modifier to apply.** "applied N biome modifier(s)" with N=0 establishes that the
  registry exists and is empty; it does not establish that a populated modifier still transforms a biome. That half
  is the m25 gate's, which is server-side and already green.
- **The world-exit itself is not this lane.** The disconnect in the context is `net.minecraftforge.common.ForgeHooks`
  `onCustomPayload` → "Illegal packet received, terminating connection" (Forge's side check on a custom payload),
  and the throwable that ends the log is Forge's `ObjectHolderRegistry.applyObjectHolders` on `handleServerStopped`.
  Neither is caused by a missing datapack registry, and no arm here is expected to change either.
- **No second control arm beyond the frozen published kernel**, and no in-world behaviour, pixel reading or
  gameplay. The `1.21.1-NeoForge` instance is a read-only control, cited not re-run.

## Adjudication

Filled in after the runs. The prediction above is unchanged.

**All four pre-committed items hold; the falsifier did not fire.** Three arms were run, not two — and one
operational deviation was needed — both recorded below, neither of which changed a criterion.

| # | committed | read |
|---|---|---|
| 1 | `before` reproduces | **held.** `posted datapack-registry declaration to 0 bus(es) — 0 declared ([]), 25 total`; `NeoForge's biome/structure modifiers did not apply — -1 biome and -1 structure modifier(s)`; `java.lang.IllegalStateException: Missing registry: ResourceKey[minecraft:root / neoforge:biome_modifier]`. Present in **both** the arm that is the literal published `0.3.6-beta` jar (`16bcd602…`) and the same-environment rebuild of HEAD (`389c889b…`). |
| 2 | `after` does not | **held.** `Missing registry: … neoforge:biome_modifier` = **0**; `did not apply — -1 biome and -1 structure modifier(s)` = **0**; instead `applied NeoForge's 0 biome modifier(s) and 0 structure modifier(s)` = 1. |
| 3 | the declaration ran, and reached the bus | **held.** `posted datapack-registry declaration to 19 bus(es) — 2 declared`, and the declared list names `ResourceKey[minecraft:root / neoforge:biome_modifier]` and `ResourceKey[minecraft:root / neoforge:structure_modifier]`, both `requiredNonEmpty=false`. The new line `asked for before any NeoForge bus exists …` appears once, on the earlier (client-entrypoint) call — i.e. the call that used to spend the one-shot now declines it and the post-window call declares. |
| 4 | nothing else moved | **held.** All three arms: `run=PASS`, `exit=0`, `stopped=true`, `killed=false`, `world=true`, `frames=1`, `confirmed_required=0`, 13/13 deps `OK`, 0 crash-reports, 32 findings rows, same subject status (`mod=DEGRADED`, `catalog_failures=["shooting_star_demo"]`), `[Forbric/Seed] seeded NeoForge LoadingModList` present. The lines the context names that are *not* this lane's are unchanged in both arms: the `halley_sky` shader failure (see below) and the `forge:biome_modifier_serializers` suppression (not triggered in this mod set either arm). |

### What the runs establish, exactly

The client now declares NeoForge's own two datapack registries, because the `DataPackRegistryEvent.NewRegistry`
reaches the NeoForge **baseline bus** — so `NeoForgeMod`'s own listener declares them, and no kernel-side
re-declaration was needed. `ServerLifecycleHooks.runModifiers` no longer throws at its first instruction, so the
pass runs and reports counts instead of a `-1`. The counts are 0 because **no mod in this 14-mod set ships a
`biome_modifier` or `structure_modifier` file** — so "the pipeline runs" is established, not "a populated modifier
transforms a biome" (a declared gap, as pre-registered). The 12-mod set's earlier client runs (`stubtable/run*`, and
the user's own `latest.log`) are where the same one line was `-1`/`Missing registry`, so this is the first client on
this box whose whole biome/structure modifier pass executes.

### Correction owed to the record: "the arms differ only in those two edits"

The pre-registration predicted a two-arm comparison on the frozen published kernel. That arm's *behaviour* is exactly
the reproduction claimed, but it is **not** byte-reproducible from this tree: 121 of its 727 boot-half classes differ
from a local rebuild, purely in synthetic lambda numbering (`…$0` vs `…$4` inside a class) — a different compiler
environment, not different sources (`git diff --stat bf738f8b a6572f82 -- forbric-kernel/src` is empty). So a third
arm was added: `before` = **the same-environment rebuild of HEAD** (`./gradlew --offline -q
-Pforbric.stagedRoot=<empty> jar` in this tree, both edited files checked out, then restored, plus the published
runtime half byte for byte). Reported pair `before` → `after`:
`389c889beddd2f0927f76ebece6a43f85eb7e554e8450ecbd6b253c5004ab4eb` →
`ec81fdcdb87f928bb4db2f7956f46971bfd22ccdb078893e2b7a4c57d8d3f795`, entry sets equal (735 each), and exactly
**three** boot-half entries differ: `DatapackRegistryDeclaration.class`, `KernelLifecycle.class`,
`KernelLifecycle$ReopenedRegistries.class` — all inside `diff.patch`. That is the isolation; the pre-registration's
sentence "the two arms differ only in the boot half, and only in those two edits" is true of *that* pair and not of
the published arm, which is why the published arm is reported as context and the A/B is taken on the pair.

### Two operational deviations (no criterion changed)

1. **A superseded `after` reading.** The first `after` run used a jar assembled before zip entry timestamps were
   normalised (kernel sha `1543b40f…`). Its console markers are identical to the reported `after`'s; it was
   re-measured on a content-reproducible jar. Both readings are on disk (`after-out`, `arm-after`).
2. **The harness's window-hiding agent was inert for the reported arms.** While this lane ran, a sibling added
   `-javaagent:…/hide-window-agent.jar` to the harness's single client command (`sweep_client.py::command()`), and the
   jar on disk at ~11:09 injected a bare `RETURN` into `org.lwjgl.glfw.GLFW.glfwShowWindow` ahead of its entry frame,
   producing `java.lang.VerifyError: Expecting a stack map frame … GLFW.glfwShowWindow(J)V @1: getstatic` and
   `FAIL world=False 13s cause=verify` — **identically in both arms**, which names the agent and not the kernel (its
   author subsequently confirmed the 11:10 `visitFrame` variant failed the same way and that the repair is
   `ClassWriter.COMPUTE_FRAMES`). The two arms were re-run with `W7_SHOW_WINDOW=1`, which makes the agent's
   `transform` return null; that is the reading above, and it is stated as a reading: both consoles carry
   `[W7/HideWindow] agent installed; forbric.hideWindow=false` and **neither** carries the
   `[W7/HideWindow] patched org/lwjgl/glfw/GLFW…` line, i.e. the agent loaded and rewrote nothing.
   Nothing in this lane's criteria can be affected by it: every criterion is a line-presence claim in `console.log`
   plus the row's `world`/`frames`, and the agent rewrites only `org.lwjgl.glfw.GLFW`. The report says this in full
   because "the instrumentation sits outside the artifact under test" is a standing rule in this campaign.

### One thing the runs surfaced that the prediction did not mention

The `halley_sky` shader failure (`net.minecraft.server.ChainedJsonException: Invalid shaders/core/halley_sky.json`)
is present in every arm, unchanged — and a sibling lane's standalone CGL probe (no Minecraft in the loop) shows it is
**mod-side**: macOS's GLSL driver predeclares `noise3` as `vec3`, and the Addition's `float noise3(vec3 p)` at
`halley_sky.fsh:60` is a redeclaration with a different return type. Not a merge defect, not this lane's, and left
alone — recorded so nobody reads its presence in the `after` arm as a regression.
