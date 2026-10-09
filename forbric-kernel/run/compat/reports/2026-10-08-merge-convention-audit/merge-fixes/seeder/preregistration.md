# S3 pre-registration — `seedForge52LoadingModList` backfill (written BEFORE the run)

Audit row S3 (2026-10-08 merge-convention audit, slice D): `PassiveSeeder.seedForge52LoadingModList` froze an EMPTY
`FMLLoader.loadingModList` before Main with no proven backfill — the silent twin of the `LoadingModList` NPE that
killed startup. Fix: populate the field from the same `arbitratedForgeFamilyMods` answer the MinecraftForge
`LoadingModList` is built from, with `ForgeLoadingList`'s three states (populated / genuinely-empty / unknown-is-loud).

## Artifact under test

- kernel jar: `forbric-kernel/build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar`
- sha256: `25d1a1aab233cce0ce5a694741cee6b40c76ebf723e292c90bc5855f3c5aab5e` (`frozen-kernel-sha256.txt` is the harness's own copy of this)
- built with my working tree's `PassiveSeeder.java` + `PassiveSeederForge52LoadingModListTest.java`.

## Rig (fixed before the run; not a variable)

- `sweep_client.py --corpus corpus-user12 --only modmenu`, `--stage .stage-scratch/stage --mc .stage-scratch/mc`,
  `--remap-cache` absolute, `--jvm=-Dforbric.compatibilityPolicy=continue`, window hiding on (default),
  `W7_JAVA=jdk-21.0.7`. Same rig FixMergeRepairs used; W7Harness confirmed 0 client JVMs live.
- The subject's closure is the user's 12-mod set (Sodium, JEI, ModernFix, Lithium, ferritecore, ImmediatelyFast,
  cloth-config, appleskin, entityculling, placeholder-api, fabric-api, modmenu). Ten of those are Forge-family.

## Pre-committed reading (what PASS/FAIL means, fixed now)

**Primary (the fix's own signal), in the run console:**

1. `[Forbric/Seed] seeded Forge 52's FMLLoader.loadingModList with N Forge-family mod(s) and read them back through
   LoadingModList.getMods()` with **N == 10** and the id list containing `sodium` and `lithium`.
2. No `[Forbric/Seed] could not discover the Forge-family mods to satisfy Forge 52's FMLLoader.loadingModList`
   (that line means discovery failed and the field was left NULL — the loud "unknown" state).
3. No `[Forbric/Seed] Forge 52's LoadingModList.getMods() reads back ... but the kernel built ...` (write/read
   divergence).

**Secondary (the read must be real, not just logged).** Every Forbric boot reads this field during Bootstrap:
`ForgeEventFactory.<clinit> → ModLoader.get() → new ModLoader() → FMLLoader.getLoadingModList()`, whose constructor
then streams `getErrors()`, `getBrokenFiles()`, `getModFiles()` and filters them by `ModFileInfo.missingLicense()`.
So a run that BOOTS proves Forge's own reader consumed the populated list (a malformed ModFile/ModInfo would throw
there, pre-Main, and there is no world). Pre-committed:

4. `run=PASS`, `world=true`, `frames=1`, `strict=TRUE`, `confirmed_required=0`, `crash-reports: 0`.
5. `ClientSmoke] joined world via quick-play: W7Client`, `client-ready after 200 world tick(s)`,
   `requesting clean disconnect after 220 world tick(s)`.

## Honest limits (fixed now, before seeing the run)

- **No corpus mod reads the MinecraftForge `LoadingModList`/`ModList`.** A byte scan of every jar under `w7/` (dedup
  by realpath) and the user's `mods/` finds zero references to `net/minecraftforge/fml/loading/LoadingModList` or
  `net/minecraftforge/fml/ModList` outside the runtime and the kernel itself. Sodium (the "e.g." in the brief) reads
  the *NeoForge* `LoadingModList` — the already-fixed twin (S1/S2). So the "a Forge-family mod that reads it" arm
  cannot be a corpus mod; the reader this change serves is Forge's own `ModLoader` constructor during Bootstrap,
  which runs on every boot (criterion 4 is the proof of the read).
- **Sampling:** one subject, one run. A single PASS is a reading about this kernel on this rig, not a distribution.

## If it fails

- Boot dies pre-Main with an exception out of `ModLoader.<init>` / `LoadingModList` → the populated list is malformed
  (a `ModFile` without `modFileInfo`, a `ModInfo` the `fileById` map rejects, a null `brokenFiles`): record the stack,
  the fix is wrong.
- N == 0 or the "could not discover" line → the backfill did not run; record why.
