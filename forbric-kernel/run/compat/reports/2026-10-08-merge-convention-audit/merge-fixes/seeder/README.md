# S3 — `seedForge52LoadingModList`: a populated backfill for Forge 52's `FMLLoader.loadingModList`

Lane: **seeder** (`forbric-kernel`, `net.forbric.kernel.boot.PassiveSeeder`).
Audit: 2026-10-08 merge-convention audit, row **S3** (slice D — `D-state.md`).
Fix template: **S5**, the three states of `net.forbric.api.ForgeLoadingList`.

## Verdict

**The field can be, and now is, honestly populated on 1.21.1.** Before this change
`seedForge52LoadingModList` seeded `LoadingModList.of(List.of(), List.of(), null)` — an EMPTY list frozen into the
static field before Main, with no backfill (it is a no-op once set). It now seeds the SAME Forge-family mod set the
MinecraftForge `LoadingModList` is built from, and reads it back through the door a mod uses.

- offline probe: `PassiveSeederForge52LoadingModListTest` — 4 cases, green (real Forge 52 bytecode).
- client run (user's 12-mod set, Sodium included): **`STRICT PASS`, `world=True`, `frames=1`,
  `confirmed_required=0`, 63 s, no crash reports**, kernel sha `25d1a1aa…`. Console:
  `[Forbric/Seed] seeded Forge 52's FMLLoader.loadingModList with 9 Forge-family mod(s) and read them back through
  LoadingModList.getMods() … [immediatelyfast, appleskin, cloth_config, entityculling, ferritecore, jei, lithium,
  modernfix, sodium]`.

**But read §"Who actually reads it" before treating this as a player-visible fix.** No mod in this corpus reads the
MinecraftForge list; the reader is Forge's own `ModLoader` constructor. It CAN be populated, and it is — the honest
outcome is correctness for readers, not a visible change on today's corpus.

## What a populated list must contain on 1.21.1

`javap` of the staged/installed `forge-runtime.jar` (Forge 52.1.16). There is **no** `LoadingModListImpl` and **no**
`ModSorter$State` on this base — the 26.2 lazy-holder machinery the kernel carries is inert here, and the field is
the whole seam.

```
net.minecraftforge.fml.loading.LoadingModList
  private static LoadingModList INSTANCE;                                  // non-final; written by of()
  private final List<ModFileInfo> modFiles;                                // files.map(ModFile::getModFileInfo)
  private final List<ModInfo>     sortedList;                              // the mods argument, as-is
  private final Map<String,ModFileInfo> fileById;                          // flatMap(ModFileInfo::getMods) -> ModInfo
  private final List<EarlyLoadingException> preLoadErrors;                 // new ArrayList (+ the error argument)
  private List<IModFile> brokenFiles;                                      // NULL until setBrokenFiles()
  public static LoadingModList of(List<ModFile>, List<ModInfo>, EarlyLoadingException);  // -> new + INSTANCE
  public static LoadingModList get();                                      // INSTANCE
net.minecraftforge.fml.loading.FMLLoader
  private static LoadingModList loadingModList;                            // the field this seeder writes
  public  static LoadingModList getLoadingModList();
```

So a populated list needs: `List<ModFile>` whose every element answers `getModFileInfo()` (non-null) and
`List<ModInfo>` whose elements answer `getModId()`/`getOwningFile()` (the `fileById` fold), plus `brokenFiles`
**set to a list** — the constructor never assigns it, and Forge's reader streams it:

```
net.minecraftforge.fml.ModLoader$LazyInit.INSTANCE  ->  new ModLoader()
   loader.loadingModList = FMLLoader.getLoadingModList();     // NPE when the field is null (the pre-Main failure)
   loader.loadingExceptions = getErrors().flatMap(...)
   loader.loadingWarnings   = getBrokenFiles().stream().map(lambda$new$0)   // NPE when brokenFiles is null
   getModFiles().stream().filter(ModFileInfo::missingLicense).map(lambda$new$2) -> loadingExceptions
```

The kernel already had a builder for exactly this shape: **`PassiveSeeder.buildForgeLoadingLists`** (each `ModFile`
gets `modFileInfo`, `jarVersion`, `fileProperties`, `loaders`, `accessTransformers`, a real scan-data index, a
`ForgeSecureJarStandIn` and `type=MOD`; each `ModInfo` is a record with `owningFile`/`modId`/`version`/`displayName`/
`config`/`modProperties`). It was used for the `ForgeLoadingList` holder path but had never been fed to
`FMLLoader.loadingModList` on 1.21.1 — the seeder passed `List.of()` instead. The fix reuses it.

## The three-state template, applied

`ForgeLoadingList` distinguishes populated / genuinely-empty / not-published. Here:

| state | behaviour |
|---|---|
| Forge-family mods exist | build with `buildForgeLoadingLists(arbitratedForgeFamilyMods(modsDir))`, `of(...)`, set, read back |
| no Forge-family mods | an EMPTY list — that is the answer, logged as such |
| discovery could not answer | **nothing is written**: the field stays null and the first Forge read is a loud NPE, never a silent "zero mods" frozen for the run |
| `-Dforbric.seedLoadingModList=off` | the pre-fix empty list, with a warning (parity with the NeoForge / `publishForgeLoadingList` switch) |

Source of the mods: `arbitratedForgeFamilyMods(<gameDir>/mods)` — the same deterministic answer the NeoForge seeder
and `publishForgeLoadingList` use. **Fabric mods are deliberately not added** (this list is what Forge's handshake
would announce; presence must not become "this client runs those"), and neither are jar-in-jar nested mods.

The read-back is its own check (`readBackForge52ModCount`), not a log line taken on faith: `LoadingModList.get()`
must answer with the seeded object, then `getMods()` on that instance must report the same count. The first cut
invoked the **instance** method `getMods()` with a null receiver and threw on every boot; the client run's console
caught it (`unreadable even though seeding succeeded`) before it shipped, and the read path now lives in a
package-private helper the probe drives directly.

## Who actually reads it (the honest limit)

A byte scan of every jar under `w7/` (dedup by realpath) and the user's `mods/` finds **zero** references to
`net/minecraftforge/fml/loading/LoadingModList` or `net/minecraftforge/fml/ModList` outside `forge-runtime.jar` and
the kernel itself. Sodium — the "e.g." in the brief — reads the **NeoForge** `LoadingModList` (the already-fixed twin,
S1/S2); ImmediatelyFast, ferritecore, lithium and modernfix do too.

So the in-corpus reader of the MinecraftForge list is Forge's own `ModLoader.<init>` during Bootstrap, which runs on
**every** boot. A malformed populated list would throw there, pre-Main, and there would be no world — so the run
reaching the world is the read succeeding, and the new INFO line is the read-back.

Consequences recorded rather than hidden:

- The player-visible effect in today's corpus is **nil**: no mod probes the MinecraftForge list. The audit's
  "Forge handshake announces `mods=[]`" belongs to row **S4** (traditional-Forge `ModList` is still seeded empty by
  `seedForgeLoadingModList`); this row only makes `FMLLoader.loadingModList` true for its readers. The populated
  lists here are exactly the `ModFile`/`ModInfo` data S4 would need — a natural, one-line follow-up, not done here.
- `buildForgeLoadingLists` gives each seeded `ModFileInfo` `license=""`, so `ModFileInfo.missingLicense()` is true and
  `ModLoader.<init>` stores N inert `ModLoadingException("fml.modloading.missinglicense")` entries in
  `loadingExceptions`. No Forbric path reads that list (`loadMods`/`gatherAndInitializeMods`/`finishMods` are replaced
  by the kernel and never called), so it is inert — but it is a real state change and is noted as the cost.
- Sampling: one subject, one run; a reading about this kernel on this rig, not a distribution.

## Pre-registration variance (fixed before the run, checked after)

Pre-committed `N == 10`; the reading is `N == 9`. Cause: I counted `placeholder-api-2.4.2+1.21.jar` as Forge-family;
it is a Fabric jar (`fabric.mod.json` only). The 9 are the direct Forge-family mods; nested jar-in-jar and Fabric
mods are excluded by design. The other pre-committed criteria (id list contains `sodium` and `lithium`; no
"could not discover" line; no read/write divergence; `run=PASS world=True frames=1 strict=TRUE confirmed_required=0`;
joined/ready/clean-disconnect) all held.

## Evidence (this directory)

- `preregistration.md` — the reading, written before the run.
- `evidence/console-markers.txt` — the seed lines + world markers, from the run console.
- `evidence/console.txt`, `evidence/results.jsonl`, `evidence/frozen-kernel-sha256.txt` — the full run console, the
  harness row, and the sha of the exact jar that ran.
- `run/` — the harness's own output tree: `frozen/forbric-kernel-client.jar` (the exact bytes that ran),
  `frozen-kernel-sha256.txt`, `per-mod/results.jsonl`. The large per-mod rundir (world copy, logs) was removed after
  the run; its console is preserved verbatim in `evidence/console.txt`.

## Changes

- `src/main/java/net/forbric/kernel/boot/PassiveSeeder.java` — `+121/-18`: `seedForge52LoadingModList` rewritten to
  populate (two overloads: discover-from-dir, install-for-known-mods), `readBackForge52ModCount` added; caller in
  `seedForgeFmlLoader` now passes `<gameDir>/mods`.
- `src/test/java/net/forbric/kernel/boot/PassiveSeederForge52LoadingModListTest.java` — new offline probe (4 cases).
- Seeder test suite (`PassiveSeeder*`), real Forge 52 bytecode: green (33 tests, 1 skipped — the skip needs the
  player's Unlit Campfire pack, unrelated). Full suite at this revision: 5 failures, all pre-existing/environmental
  and none in the seeder or loader area (`ForeignTypeTest` inline-family census, `KernelRuntimeClassesTest` needs the
  excluded game-side compile, `CompatProtocolTest` missing a report-doc line, `FinalMixinApplicationsTest` missing a
  Fabric API fixture, `MergedBaseAnonymousDriftTest` needs the real named vanilla jar).
