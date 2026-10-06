# 1.21.1 carrier-stub table — the 33 rows that were uncovered, the renamed-delegate rule, and the test that pins it

**One line.** `carrier-stubs.txt` was the census of a *different* Minecraft generation: on the shipped
`patched-mc-merged-1.21.1` base it covered 10 rows correctly, named classes the base does not have in 20 more, left
**33 rows where a Forge-family mod's own carrier ran that selector on a body** uncovered — the class of gap that
produced the Sodium-cutout and colour bugs — and could not see a stub whose delegate the carrier had **renamed**.
The table is now the 1.21.1 census (95 rows, `derive()`-equal to what the re-pointed `CarrierStubCensusTest`
asserts), `delegation()` follows a renamed delegate, and the census test runs against 1.21.1 fixtures instead of
skipping.

**Commits** — the working tree's source is frozen at `13e4ff3f`:

| commit | what |
|---|---|
| `4a0a5302` | `MixinStubRebind.delegation()` follows a RENAMED delegate; a constructor still forwards only through `this(...)` |
| `72fe8d7e` | `carrier-stubs.txt` 63 → 94: the 32 movable rows + `RegistryData` + `BucketItem`, the three 26.2-descriptor rows replaced, two columns corrected |
| `7115772d` | report + first gate readings |
| `232f8de3` | full re-derive to 95 rows (drop 49 proven-dead rows, add the 50 Fabric-only ones), `CarrierStubCensusTest` re-pointed, `isStubOverBody` asks the base |
| `13e4ff3f` | census test's fixture resolution (staged `run/` first, `<mc>/.forbric-build` fallback), one pinned row corrected |

**Measured on the user's real twelve mods** (two arms, same corpus/world/ROIs, one run each):

| | control `0.3.5-beta` `a56bf626` | fix (95-row table) `0654d448` |
|---|---|---|
| gate | `run=PASS exit=0 world=true frames=1 stopped=true killed=false strict=TRUE confirmed_required=0 loaded=true na=false catalog_failures=[] mod=OK`, 0 crash reports | identical |
| `now targets` lines | 2 (both Sodium `SpriteContents.<init>`) | **6** — the same 2 plus 4 handlers on rows this lane added |
| `load-report.txt` | 147 lines | **byte-identical** |
| pixel pure black | 0.0000 × 3 ROIs | 0.0000 × 3 ROIs |

---

## 1. What changed

### 1.1 `carrier-stubs.txt`: 63 → 95 rows

Derived with the *fixed* `delegation()` against `patched-mc-merged-1.21.1` (`fbd531b0…`) + `client-official`
(`c92f9b08…`) + `patched-mc-forge-1.21.1` (`b67f13c3…`) + `patched-mc-neoforge-1.21.1` (`0e1a2e52…`): **95 derived
rows**, and the shipped table is now exactly those 95. 49 rows that were not stubs on this base at all (20 name an
absent class, 20 a missing stub signature, 9 a non-delegation) were dropped; 84 rows were added across the two
landings.

The 33 rows a Forge-family mod's own carrier ran on code (the point of the lane) — 28 `forge=body`, 4 `neo=body`
and `RegistryDataLoader$RegistryData#<init>(…;Z)V -> (…;Z,Consumer)V forge=descriptor-body`:

```
ClientPacketListener#startWaitingForNewLevel      CommonListenerCookie#<init> (client, server)
ParticleEngine#render                            BlockElement#<init>
RenderChunkRegion#<init>                         RenderRegionCache#createRegion
SectionRenderDispatcher$RenderSection$RebuildTask#<init>
HumanoidArmorLayer#renderArmorPiece              ClientLanguage#<init>
SimpleBakedModel$Builder#build                   ShapedRecipeBuilder#<init>
ShapelessRecipeBuilder#<init>                    SimpleCookingRecipeBuilder#<init>
EntityTypeTagsProvider#<init>                    FluidTagsProvider#<init>
GameEventTagsProvider#<init>                     RegistryDataLoader$RegistryData#<init>
DiscardedQueryAnswerPayload#<init> (neo=body)    DiscardedQueryPayload#<init> (neo=body)
ServerAdvancementManager#<init> (neo=body)       RecipeManager#<init> (neo=body)
Pack#<init>                                      DamageScaling#<init>
DeathMessageType#<init>                          MobEffect$AttributeTemplate#<init>
MobEffectInstance$Details#<init>                 RangedCrossbowAttackGoal#<init>
Boat$Type#<init>                                 FeatureFlag#<init>
FeatureFlagRegistry$Builder#create               FeatureFlagSet#<init>
LootDataType#<init>                              BucketItem#emptyContents
```

The three **26.2-descriptor rows** the hunt named (P1b):

| row | 26.2 spelling | 1.21.1 |
|---|---|---|
| `ClientPacketListener#startWaitingForNewLevel` | `…screens/LevelLoadingScreen$Reason` | `…screens/ReceivingLevelScreen$Reason` |
| `FeatureFlagRegistry$Builder#create` | `net/minecraft/resources/Identifier` | `net/minecraft/resources/ResourceLocation` |
| `BucketItem#emptyContents` | `…entity/LivingEntity` | `…entity/player/Player` |

And two **columns corrected** — a wrong-move removal: `SessionSearchTrees#creativeNameSearch()` / `#creativeTagSearch()`
read `forge=body`; on the real Forge jar `creativeNameSearch()` forwards to the *renamed* `getSearchTree(Key)`, so
Forge's own class keeps the stub and a Forge mod must not be moved there. They now read `forge=stub`.

### 1.2 `MixinStubRebind.delegation()`: a renamed delegate

`delegation()` required the delegate to share the stub's **name**. NeoForge's `Player` keeps vanilla's
`getDestroySpeed(BlockState)` as a stub over the widened, renamed `getDigSpeed(BlockState, BlockPos)`, so the row
naming that overload never fired and `Shape.of` read NeoForge's own class as `body` when it too forwards. The rule
is now: same owner, same static-ness, a different descriptor, every stub parameter reaching the delegate unchanged
(or nothing moves).

A **constructor** keeps the old rule and must forward through `this(...)`. Measured while deriving: relaxing this
too far made `DetectorRailBlock.<init>(Properties)` — `super(properties); registerDefaultState();`, vanilla's
constructor with `setDefaultState` extracted — read as a stub, i.e. an injector would have been moved onto a
helper the block also reaches from other paths.

### 1.3 `isStubOverBody` asks the base, not the row

Its "does the delegate exist here" guard used to look for a method with the **stub's** name and the row's delegate
descriptor, which is wrong for a renamed delegate (`renderWater` forwards to `renderFluid`). It now runs
`delegation(target, method)` and compares the descriptor the call actually reaches.

### 1.4 `CarrierStubCensusTest` re-pointed at 1.21.1, and it runs

The old revision hardcoded `../forbric-loader/run/merged-base/patched-mc-merged-26.2.jar` and
`versions/26.2/26.2.jar` and skipped when they were absent — so on the 1.21.1 port the "table must equal what the
base says" assertion never ran and the table stayed the 26.2 census. It now resolves the version-aware paths
(`forbric.mcVersion`, the staged `run/` the build hands the suite, `<mc>/.forbric-build` otherwise) and fixes
vanilla to the named `client-official.jar` (a launcher jar is obfuscated; a census read off it would find nothing).
It asserts **set equality in both directions**, so a derived row missing from the table fails it — and
`TestFixtures.requireFiles` makes a missing fixture a failure, not a skip, under
`FORBRIC_COMPAT_FIXTURES_REQUIRED=1`. A second test pins the five `Shape` outcomes by name at rows that exist on
1.21.1.

---

## 2. Evidence

| file | content |
|---|---|
| `evidence/probe-per-row.txt` | `RowProbe`: for **every** row, the merged base declares the stub, the shipped `delegation()` describes exactly the row's delegate, vanilla declares the stub and not the delegate, and each `forge=`/`neo=` column equals `Shape.of` on that carrier's own patched class — `rows=95 pass=95 fail=0` |
| `evidence/census-check.txt` | `Census2 check`: `DERIVED_ROWS 95 SHIPPED_ROWS 95 BOTH 95`, 0 absent, 0 stale, 0 column mismatches |
| `evidence/derived-1.21.1.txt` | the 95 derived rows |
| `evidence/unit-tests.txt` | `CarrierStubCensusTest` 2/2 passed **with fixtures loaded**; `MixinStubRebindTest` 34 tests, 28 skipped (gone fixtures), 0 failed |
| `evidence/diff.patch` | the source diff (rule + table + test) |
| `evidence/build-provenance.txt` | the frozen jar's sha and how it was assembled |
| `evidence/console-markers.txt`, `evidence/results-*.jsonl`, `evidence/load-report-*.txt` | the two arms |
| `evidence/pixel-readings.txt`, `evidence/frame-*.png` | the two arms' screenshots and ROIs |
| `evidence/preregistration.md` | the reading written before the runs |
| `evidence/Census2.java`, `evidence/RowProbe.java`, `evidence/sampler.py` | the tools |

Byte evidence for the three decisions that were not mechanical:

```
merged  Player.getDestroySpeed(BlockState)F   invokevirtual getDigSpeed(BlockState,BlockPos)F      (renamed)
forge   Player.getDestroySpeed(BlockState)F   invokevirtual getDestroySpeed(State,BlockPos)F      (same name)
neo     Player.getDestroySpeed(BlockState)F   invokevirtual getDigSpeed(State,BlockPos)F          (renamed)
merged  DetectorRailBlock.<init>(Properties)V super(BaseRailBlock); this.registerDefaultState()   (not a stub)
forge   SessionSearchTrees.creativeNameSearch()F  invokevirtual getSearchTree(Key)                (stub, not body)
merged  RegistryDataLoader$RegistryData.<init>(k,c,z)V  this(k,c,z,indy Consumer)                 (stub)
```

---

## 3. Adjudication (the pre-registered reading is `evidence/preregistration.md`)

Two arms, same corpus, same world fixture, same ROIs: control = the released `0.3.5-beta` (`a56bf626…`), fix =
`0654d448…` (the 95-row table, from `13e4ff3f`). One run each, no re-run.

### §1 gate — met, verbatim

```
run=PASS exit=0  world=true  frames=1  stopped=true  killed=false  strict=TRUE
compatibility_policy=strict  mixin_fit=default
confirmed_required=0  loaded=true  na=false  catalog_failures=[]  mod=OK
[Forbric/ClientSmoke] joined world via quick-play: W7Client
[Forbric/Seed] seeded NeoForge LoadingModList with 64 mod(s)
crash-reports: 0
```
All 11 closure entries `OK`; `seconds=32` (`cpu_busy_pct=394`, `contended=true` — the harness flags the row
timing-suspect, rule 3; no gate line is timing-dependent). The control arm reads the same on every one of them.

### §2 "the new rows must fire" — the *named* marker FALSIFIED; four other added rows measured firing

Pre-registered: `now targets … ClientLanguage.<init>` >= 1. **Measured 0.** The prediction was wrong on its own
terms — ModernFix is NeoForge and that row is `neo=stub`, so it cannot move a NeoForge mod. Recorded as falsified,
not rewritten.

What the run does show is the intent, and more than the intermediate arm did:

```
fix     : 6 lines
  2  sodium   SpriteContentsMixin            now targets SpriteContents.<init>(…ForgeTextureMetadata;)V   (pre-existing row)
  2  fabric   GameOptionsMixin (keybinding, resource loader)  now targets Options.load(Z)V                (row added here)
  1  fabric   indigo BlockModelRendererMixin hookRender$forbricshim  now targets ModelBlockRenderer.tesselateBlock(…ModelData;RenderType)V  (added here)
  1  fabric   ArmorFeatureRendererMixin      renderArmor$forbricshim now targets HumanoidArmorLayer.renderArmorPiece(…HumanoidModel;FFFFFF)V (added here)
control : 2 lines — the SpriteContents pair only
```
None of `Options#load(`, `ModelBlockRenderer#tesselateBlock(`, `HumanoidArmorLayer#renderArmorPiece(` headed a row
in the 0.3.5 table. So the landing closes four mixin anchors in the user's real twelve-mod set, three of them on
rows this lane pinned.

### §3 the two fixed cycles — no regression

- `now targets … SpriteContents.<init>(…ForgeTextureMetadata)V` = **2** in both arms;
  `applies only partially … SpriteContents.originalImage` = **0** in both.
- The four colour markers each >= 1 in the fix arm (2 / 2 / 1 / 1), same as the gate.
- `load-report.txt` **byte-identical** between the arms (147 lines, `diff` empty), including the unchanged
  `这一次启动,9 个 mod 有一部分没有跑起来。` aggregate and every finding line.
- No `[Forbric/…]` finding names a 26.2 class (`LevelLoadingScreen` = 0, `resources/Identifier` = 0).
- Pixel sub-readings: pure black `(0,0,0)` = **0.0000** in all three ROIs in **both** arms.
  **Indeterminate against the 2026-10-04/10-06 readings**, exactly as the preregistration §3 warned:
  `/private/tmp/sodcut-fixture` was deleted with the rest of `/private/tmp`; on the corpus fixture these ROIs frame
  grass tops (`grass_green` 0.78–0.98), not the grass side of the earlier cycles. The hotbar-icon criterion is
  indeterminate for the same reason and is not claimed.

### §4 verdict

`confirmed_required=0` and 0 crash reports in both arms, table == census (`CarrierStubCensusTest` 2/2 with
fixtures loaded) → the landing stands. One pre-registered reading was falsified and is reported as falsified.

## 4. Follow-up

- `MixinStubRebindTest`'s 26.2-pinned tests (`fusionsSpriteCaptureMovesToTheBodyMinecraftForgeRan`,
  `aNeoForgeModMovesOffAStubOnlyMinecraftForgeHas`, the torrential `FuelValues` pair) still name
  `../forbric-loader/run/merged-base/patched-mc-merged-26.2.jar` and `build/compat-inputs/sweep90/mods`; both are
  gone on this box, so they skip. They should be re-pointed at 1.21.1 rows (`Options.load`,
  `HumanoidArmorLayer.renderArmorPiece`) the way the census test now is.
- The whole suite could not be run here (no staged game jars / Team Reborn Energy fixture —
  `verifyRebornEnergy`); only the two classes above are reachable.
- The pixel comparison against the 2026-10-04/10-06 cycles needs the old world fixture, which no longer exists.
