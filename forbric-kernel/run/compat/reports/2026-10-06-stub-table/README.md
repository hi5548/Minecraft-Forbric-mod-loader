# 1.21.1 carrier-stub table — the 32/33 rows that were uncovered, and the renamed-delegate rule

**One line.** `carrier-stubs.txt` was the census of a *different* Minecraft generation: on the shipped
`patched-mc-merged-1.21.1` base it covered 10 rows correctly, named classes the base does not have in 20 more,
and left **33 rows where a Forge-family mod's own carrier ran that selector on a body** uncovered — the class of
gap that produced the Sodium-cutout and colour bugs. The table is now the 1.21.1 census for its live surface, and
`MixinStubRebind.delegation()` follows a **renamed** delegate, which is what NeoForge's `Player.getDestroySpeed`
row needed and what exposed two wrong `forge=` columns on existing rows.

**Kernel**: boot half from `72fe8d7e`, game side injected from the 0.3.5 runtime payload; sha256 and the full
assembly are in `evidence/build-provenance.txt` (the frozen jar the gate ran is
`1f9a19977f912dd588499c2bfce12a99cc335e965a664d7396d0be900bc7999f`).

**Ingested data** (all byte-level, re-derivable with `evidence/` scripts described in §5):
`patched-mc-merged-1.21.1` (`fbd531b0…`), `client-official` (`c92f9b08…`), `patched-mc-forge-1.21.1`
(`b67f13c3…`), `patched-mc-neoforge-1.21.1` (`0e1a2e52…`).

---

## 1. What changed

### 1.1 `carrier-stubs.txt`: 63 → 94 rows

Derived with the *shipped* `delegation()` after the rule fix (§1.2): **95 derived rows**, of which **45 match the
table after this change** and 50 are Fabric-only rows left for the follow-up (§4). The 49 shipped rows that are
not stubs on this base at all (20 name an absent class, 20 a missing stub signature, 9 a non-delegation) are also
left for the follow-up — this pass does not delete rows it cannot prove; it stops *adding* the wrong ones.

**Added (34 rows)** — the 33 the hunt's `missing_movable` set implies (28 `forge=body`, 4 `neo=body`, plus
`RegistryDataLoader$RegistryData#<init>(ResourceKey,Codec,Z)V -> (…Z,Consumer)V forge=descriptor-body`, a
descriptor-selector move) and `BucketItem#emptyContents(Player,…)Z`, whose shipped row spelled a 26.2 param:

- `ClientPacketListener#startWaitingForNewLevel`
- `CommonListenerCookie#<init>` (client), `CommonListenerCookie#<init>` (server)
- `ParticleEngine#render`, `BlockElement#<init>`, `RenderChunkRegion#<init>`, `RenderRegionCache#createRegion`,
  `SectionRenderDispatcher$RenderSection$RebuildTask#<init>`
- `HumanoidArmorLayer#renderArmorPiece`, `ClientLanguage#<init>`, `SimpleBakedModel$Builder#build`
- `ShapedRecipeBuilder#<init>`, `ShapelessRecipeBuilder#<init>`, `SimpleCookingRecipeBuilder#<init>`
- `EntityTypeTagsProvider#<init>`, `FluidTagsProvider#<init>`, `GameEventTagsProvider#<init>`
- `DiscardedQueryAnswerPayload#<init>`, `DiscardedQueryPayload#<init>` (both `neo=body`)
- `ServerAdvancementManager#<init>`, `RecipeManager#<init>` (both `neo=body`)
- `Pack#<init>`, `DamageScaling#<init>`, `DeathMessageType#<init>`, `MobEffect$AttributeTemplate#<init>`,
  `MobEffectInstance$Details#<init>`, `RangedCrossbowAttackGoal#<init>`, `Boat$Type#<init>`, `FeatureFlag#<init>`,
  `FeatureFlagRegistry$Builder#create`, `FeatureFlagSet#<init>`, `LootDataType#<init>`

**Replaced (3 rows whose descriptor spells a 26.2 class)** — the P1b rows:

| row | 26.2 spelling | 1.21.1 |
|---|---|---|
| `ClientPacketListener#startWaitingForNewLevel` | `…screens/LevelLoadingScreen$Reason` | `…screens/ReceivingLevelScreen$Reason` |
| `FeatureFlagRegistry$Builder#create` | `net/minecraft/resources/Identifier` | `net/minecraft/resources/ResourceLocation` |
| `BucketItem#emptyContents` | `…entity/LivingEntity` | `…entity/player/Player` |

**Columns corrected (2 rows)** — a consequence of §1.2, and a **wrong-move removal**:
`SessionSearchTrees#creativeNameSearch()` and `#creativeTagSearch()` read `forge=body`; on the real Forge jar
`creativeNameSearch()` forwards to the *renamed* `getSearchTree(Key)`, i.e. Forge's own class keeps the stub, so a
Forge mod must not be moved there. They now read `forge=stub`.

### 1.2 `MixinStubRebind.delegation()`: a renamed delegate

`delegation()` required the delegate to share the stub's **name**. NeoForge's `Player` keeps vanilla's
`getDestroySpeed(BlockState)` as a stub over the widened, **renamed** `getDigSpeed(BlockState, BlockPos)`, so the
row naming that overload never fired and `Shape.of` read NeoForge's own class as `body` when it too forwards.
The rule is now: same owner, same static-ness, a different descriptor, every stub parameter reaching the
delegate unchanged (or nothing moves).

A **constructor** keeps the old rule and must forward through `this(...)`. Measured while deriving: relaxing this
too far made `DetectorRailBlock.<init>(Properties)` — `super(properties); registerDefaultState();`, vanilla's
constructor with `setDefaultState` extracted — read as a stub, i.e. an injector would have been moved onto a
helper the block also reaches from other paths. Byte evidence in §3.

Renamed rows this exposes that are *not* added (they move Fabric mods only, out of this pass's scope):
`ScreenEffectRenderer.renderWater(Minecraft,PoseStack)V -> renderFluid(…,ResourceLocation)V` and
`Bee.jumpInLiquid(TagKey)V -> jumpInLiquidInternal()V`, both pure extractions whose delegate vanilla lacks.

---

## 2. Evidence — one probe per row

`evidence/probe-per-row.txt` (`RowProbe`) re-reads the four staged jars and, for **every** row of the shipped
table, asserts: the merged base declares the stub; the shipped `delegation()` describes exactly the row's
delegate descriptor; vanilla declares the stub and **not** the delegate; and each `forge=`/`neo=` column equals
`Shape.of` on that carrier's own patched class.

```
PROBE rows=94 pass=45 fail=49
```

- **All 34 added rows PASS** (checked by set difference: 0 added rows without `PASS`).
- The 2 column-corrected rows PASS.
- The 49 `FAIL` rows are the pre-existing stale ones (20 absent class, 20 missing stub, 9 not a delegation); they
  are exactly the rows the follow-up removes. Their failures are the reason this pass did not just rewrite the
  whole file from the derivation.

`evidence/census-check.txt` is the derivation diff (`Census2 check`): `DERIVED_ROWS 95 SHIPPED_ROWS 94 BOTH 45`,
`ABSENT_BODY 0` (every row that can move a Forge-family mod is now present).

## 3. Byte evidence for the three decisions that were not mechanical

```
merged  Player.getDestroySpeed(BlockState)F   invokevirtual getDigSpeed(BlockState,BlockPos)F      (renamed)
forge   Player.getDestroySpeed(BlockState)F   invokevirtual getDestroySpeed(BlockState,BlockPos)F  (same name)
neo     Player.getDestroySpeed(BlockState)F   invokevirtual getDigSpeed(BlockState,BlockPos)F      (renamed)
merged  DetectorRailBlock.<init>(Properties)V super(BaseRailBlock); this.registerDefaultState()    (not a stub)
forge   SessionSearchTrees.creativeNameSearch()F  invokevirtual getSearchTree(Key)                 (stub, not body)
merged  RegistryDataLoader$RegistryData.<init>(k,c,z)V  this(k,c,z,indy Consumer)                  (stub)
```

---

## 4. Follow-up (not in this landing)

- `CarrierStubCensusTest` still points at `../forbric-loader/run/merged-base/patched-mc-merged-26.2.jar` and
  `versions/26.2/26.2.jar`, so it skips here and cannot catch a drift back to another generation. Re-pointing it
  at the 1.21.1 fixtures — and making it derive the renamed rows too — is the next step, and this table is already
  what it would then assert for the live surface.
- 50 derived rows that move Fabric mods only are not in the table yet.
- 49 shipped rows are dead on this base (20 absent classes, 20 missing stubs, 9 non-delegations).

## 5. Reproducing the derivation

`Census2.java` / `RowProbe.java` (package `net.forbric.kernel.mixin`, compiled against the kernel jar + ASM
9.10.1) and the derived output are in `evidence/`: `derived-1.21.1.txt`. Jar paths and sha256s are in
`evidence/build-provenance.txt`.

---

## 6. Adjudication (filled in after the run; the pre-registered readings are in `evidence/preregistration.md`)

Two arms, **same corpus, same world fixture, same ROIs**: control = the released `0.3.5-beta` artifact
(`a56bf626…`), fix = this lane (`1f9a1997…`). Readings quoted verbatim from `evidence/results-*.jsonl`.

### §1 gate — met, verbatim, one run, no re-run

```
run=PASS  exit=0  world=true  frames=1  stopped=true  killed=false  strict=TRUE
compatibility_policy=strict  mixin_fit=default
confirmed_required=0  loaded=true  na=false  catalog_failures=[]  mod=OK
[Forbric/ClientSmoke] joined world via quick-play: W7Client
[Forbric/Seed] seeded NeoForge LoadingModList with 64 mod(s)
crash-reports: 0
```
`dep_status`: all 11 closure entries `OK`. `seconds=181`, `cpu_busy_pct=223`, `contended=true` — the harness
itself flags the row timing-suspect (rule 3) because the box is busy; the gate lines are not timing-dependent.
The control arm reads the same on every one of those lines (`seconds=31`; its remap cache was by then warm).

### §2 "the new rows must fire" — the *named* marker is FALSIFIED, the mechanism is proven by another added row

Pre-registered: `now targets … ClientLanguage.<init>` >= 1. **Measured: 0.** The prediction was wrong on its own
terms — ModernFix is NeoForge and the ClientLanguage row is `neo=stub` (NeoForge's own `ClientLanguage.<init>`
does not forward), so the row cannot move a NeoForge mod. Recorded as falsified, not rewritten.

What did happen is the intent: an added row does fire on the user's real set.

```
fix     : 3 lines
          2  … SpriteContents.<init>(…ForgeTextureMetadata;)V        (the pre-existing row, unchanged)
          1  net.fabricmc.fabric.mixin.client.rendering.ArmorFeatureRendererMixin:
             renderArmor$forbricshim now targets
             net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer.renderArmorPiece(…HumanoidModel;FFFFFF)V
control : 2 lines — the SpriteContents pair only
```
`HumanoidArmorLayer#renderArmorPiece(…HumanoidModel)V -> (…HumanoidModel;FFFFFF)V` is one of the 34 rows added by
this lane (the old table had no `renderArmorPiece` row at all). The move is in a `fabric-api` module, so the
Fabric rule moves it along the row regardless of column; it is the first measured case on this twelve-mod set of a
newly-pinned row changing the kernel's behaviour.

### §3 the two fixed cycles — no regression

- `now targets … SpriteContents.<init>(…ForgeTextureMetadata)V` = **2** in both arms;
  `applies only partially … SpriteContents.originalImage` = **0** in both.
- The four colour markers each >= 1: `2 site(s) re-keyed to getBlock` = 2, `1 site(s) re-keyed to getItem` = 2,
  `@Shadow blockColors is an IdMapper` = 1, `@Shadow itemColors is an IdMapper` = 1 — same as the 0.3.5 gate.
- `load-report.txt` is **byte-identical** between the two arms (147 lines, `diff` empty), including the unchanged
  `这一次启动,9 个 mod 有一部分没有跑起来。` aggregate and every finding line.
- No `[Forbric/…]` finding names a 26.2 class: `LevelLoadingScreen` = 0, `resources/Identifier` = 0 (the single
  `LevelLoadingScreen` in the console is a `[Forbric/ClientSmoke] screen change` progress line).
- Pixel sub-readings: pure black `(0,0,0)` = **0.0000** in all three ROIs in **both** arms (so no black
  regression, fix vs control, on the same scene). **Indeterminate against the 2026-10-04/10-06 readings**, exactly
  as the preregistration §3 warned: `/private/tmp/sodcut-fixture` was deleted with the rest of `/private/tmp`, and
  on the corpus fixture these ROIs frame grass tops (`grass_green` 0.78–0.98), not the grass side of the earlier
  cycles (`dirt_brown` 0.0707 control / 0.0812 fix, versus 0.7856 then). The hotbar-icon criterion is
  indeterminate for the same reason and is not claimed.

### §4 verdict

`confirmed_required=0` and 0 crash reports in both arms → the landing stands. One reading was falsified (§2's
named row) and is reported as falsified; the substance of §2 is carried by the `renderArmorPiece` line above.

### What this run does *not* cover

- The control/fix pair differs only by the table + the `delegation()` name rule; a row whose move is only visible
  in a Forge-family mod on a `forge=body`/`neo=body` row is not exercised by this twelve-mod set (no such anchor
  in it), so those 32 rows have byte evidence (`evidence/probe-per-row.txt`) but no in-game witness here.
- The pixel comparison against the earlier cycles is indeterminate (§3).

