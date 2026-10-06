# Pre-registration — 1.21.1 carrier-stub table (the 32/33 movable rows + the renamed-delegate rule)

Written **before** the gate run. The readings below are predictions, not results; the report's §Adjudication is
filled in afterwards and must not be edited into agreement with the run.

## 0. The shape measured

- **Kernel**: the boot half built from `1f00ed55` / amended `4a0a5302` (`MixinStubRebind.delegation()` follows a
  renamed delegate; a constructor still forwards only through `this(...)`), game side injected from the 0.3.5
  runtime payload. sha256 of the frozen jar is recorded in `evidence/build-provenance.txt`.
  The table change is `carrier-stubs.txt`: 63 rows → 94 (34 added, 3 replaced, 2 columns corrected).
- **Set**: the user's real twelve NeoForge/Fabric mods (subject `modmenu-11.0.5.jar`; closure = fabric-api /
  JEI / Sodium / Lithium / FerriteCore / ModernFix / EntityCulling / ImmediatelyFast / AppleSkin / Cloth Config /
  Placeholder API), copied out of `versions/1.21.1-forbric/mods/`.
- **Instrument**: `W7Harness` client surface (`w7/harness/sweep_client.py`), quick-play into `W7Client`,
  `-Dforbric.compatibilityPolicy=strict`, `-Dforbric.mixinFit=default`, JDK 21, kernel pinned with `--kernel-jar`.

## 1. Pre-committed boot gate lines (same shape as the 0.3.5 release gate)

- `run=PASS`, `exit=0`, `world=true`, `frames>=1`, `stopped=true`, `killed=false`, `strict=TRUE`
- `compatibility_policy=strict`, `mixin_fit=default`
- `confirmed_required=0`, `loaded=true`, `na=false`, every closure entry `OK`, `catalog_failures` empty,
  0 crash reports
- `joined world via quick-play` >= 1
- `[Forbric/Seed] seeded NeoForge LoadingModList` >= 1

## 2. The new rows must actually fire (the point of the change)

The hunt (`agent://BugHunt4`) measured which corpus mixins anchor on a stub the old table did not cover. Two of
those anchors are in **this** twelve-mod set; ModernFix's is the sharp one:

- `[Forbric/Mixin] … now targets net.minecraft.client.resources.language.ClientLanguage.<init>` >= 1.
  The 0.3.5 release's `load-report.txt` for this set did not carry it; the shipped row
  `ClientLanguage#<init>(Ljava/util/Map;Z)V -> (Ljava/util/Map;ZLjava/util/Map;)V` was inert because the
  descriptor the row named does not exist on 1.21.1 (`forge=body` is wrong there — the row now reads
  `forge=body neo=stub` from the real Forge jar and the mod is NeoForge, so the move is Fabric-family only;
  see §4 for the falsifier if it does not fire).
- No `[Forbric/Mixin]` line may name a target whose class does not exist on 1.21.1, and no `now targets` line may
  name a descriptor absent from `patched-mc-merged-1.21.1.jar`.

## 3. No regression in the two fixed cycles

- **The three 26.2-descriptor rows must be gone from the console/`load-report.txt` as findings**: the strings
  `LevelLoadingScreen$Reason`, `resources/Identifier` and `LivingEntity` (in a `BucketItem.emptyContents`
  context) must not appear inside a `[Forbric/…]` finding or a "could not find"/"no such class" line.
- **Sodium cutout chain stays**: `now targets net.minecraft.client.renderer.texture.SpriteContents.<init>(…
  ForgeTextureMetadata;)V` exactly **2**; `applies only partially … SpriteContents.originalImage` = **0**.
- **Colour fix stays**: the four markers (`BlockColors … 2 site(s) re-keyed to getBlock`,
  `ItemColors … 1 site(s) re-keyed to getItem`, `BlockColorsMixin's @Shadow blockColors is an IdMapper`,
  `ItemColorsMixin's @Shadow itemColors is an IdMapper`) each >= 1.
- **Pixel sub-readings** (same sampler, same ROIs as `2026-10-04-sodium-cutout` / `2026-10-06-release-0.3.5`):
  grass-side `(1180,455,1240,725)` pure black `(0,0,0)` = **0.0000** and `dirt_brown` > 0.5; hotbar icon
  `grass_green` > 0.3 with `grey_tex` < 0.1.
  **Fixture caveat, stated up front**: the world fixture the previous cycles used (`/private/tmp/sodcut-fixture`)
  was deleted with the rest of `/private/tmp` before this run. This run uses the corpus fixture
  `w7/corpus/client-world/W7Client` (the 1.38 MB `r.-1.0.mca` one). If the ROIs land on a different scene, the
  comparison with the previous cycles is **indeterminate**; the absolute readings are still reported verbatim.

## 4. Falsifiers and retraction

- Any of §1 failing → that line is recorded as **falsified**, verbatim; the judgement is not rewritten.
- `confirmed_required>0` or any crash report → **do not land the table**; the change is withdrawn and said so.
- §2 falsified (no `now targets … ClientLanguage.<init>` line) → say so and treat the ModernFix anchor as
  uncovered, not as "the run was noisy".
