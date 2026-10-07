# Pre-registration — can the user add Create (机械动力) to their 12-mod set?

Written **before** the gate run. The reading below is a prediction, not a result; §Adjudication in the
report is filled in afterwards and must not be edited into agreement with the run.

## Question

The user's real client install is 12 NeoForge/Fabric mods (`versions/1.21.1-forbric/mods/`). Can
**Create 6.0.10 for 1.21.1 (NeoForge)** be added to that same set and still boot `strict` on the frozen
kernel `a56bf626…`?

## The frozen set (13 jars, sha256)

| # | file | sha256 |
|---|---|---|
| 1 | `modmenu-11.0.5.jar` (subject in the 0.3.5 gate) | `afa55fe4e7c48560126dbe654fa9647f141bc6ffd3ce35525b65dd65d88251b5` |
| 2 | `fabric-api-0.116.17+1.21.1.jar` | `79ac44b40780acbd884b34c50be1e39af682847e5f5cb3b1fddeeaa768dce800` |
| 3 | `jei-1.21.1-neoforge-19.57.0.450.jar` | `238b20c943bb7c1e4ed387b2d1bc463c656f44ab7c388da1eee148f54d383b52` |
| 4 | `sodium-neoforge-0.8.13+mc1.21.1.jar` | `575d187b15328d7bddd8a9b1bee64333c22c14735f5780ec49eac0cd5503c08d` |
| 5 | `lithium-neoforge-0.15.4+mc1.21.1.jar` | `488f33216030c1cb0baa33e59de3d657d08d5795a6c2080c8c8bbbc133fc8757` |
| 6 | `ferritecore-7.0.3-neoforge.jar` | `d87ea28262715ebff45b8a82d493e6b468e7a4521bc021df5d88302196d030a8` |
| 7 | `modernfix-neoforge-5.27.24+mc1.21.1.jar` | `e6e9446890f0feb3aab3f6e73ae18cb17575c370f232179fef7baa30e61538fe` |
| 8 | `entityculling-neoforge-1.11.2-mc1.21.1.jar` | `3983b981da4a0583c7e58184a8631a23e56cc3b81f4cb53491ca600382879b82` |
| 9 | `ImmediatelyFast-NeoForge-1.6.14+1.21.1.jar` | `15bf9bd6d8e3ae8ad35c5a4d90680424b534d9762d698c0a466965afcab5dd78` |
| 10 | `appleskin-neoforge-mc1.21-3.0.9.jar` | `38b48dd6231341c9f964ce6e42c57ec866c6cc8f72bec938390a59bafb3922df` |
| 11 | `cloth-config-15.0.140-neoforge.jar` | `65e722e0d98431a07c45f8bdd8d529a217cc8c175fde1740248bd5c1b4f3c0d4` |
| 12 | `placeholder-api-2.4.2+1.21.jar` | `c0187ee299527ac7a3e0b1e83601dcdc0de631e286fe41e6be1a75958ea8e443` |
| **+** | **`create-1.21.1-6.0.10.jar`** (new) | `ef87fe5709f1ba1f5b8bb20a2925b5afb4669e178fd6d8bf10c167759eefe37a` |

Create's own required deps are **bundled inside its jar** as JiJ (`META-INF/jarjar/`:
`flywheel-neoforge-1.21.1-1.0.6.jar`, `ponder-neoforge-1.0.82+mc1.21.1.jar`,
`Registrate-MC1.21-1.3.0+67.jar`); `jar_ids` reads them as provided by the same jar, so the closure adds
no extra file. Create declares `flywheel` and `ponder` as `required` and NeoForge `[21.1.219,)`; the
carrier is NeoForge 21.1.252, so the version range is satisfied.

## Instrument (fixed before the run)

- Surface: `w7/harness/sweep_client.py` (client, quick-play into `W7Client`, `strict`),
  `-Dforbric.compatibilityPolicy=strict` (the harness default), `W7_JAVA` pinned to JDK 21.0.7.
- Kernel: frozen copy of `forbric-kernel/build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar`,
  sha256 `a56bf626e983b1dda81cf1b15f5c7f485eaec9c13cb099f1d96ef1d136a6e721`
  (= the published `0.3.5-beta` artifact; not rebuilt, not replaced).
- Stage: `/private/tmp/create-bughunt/stage` (`patched-mc-merged-1.21.1.jar`,
  `forge-runtime-interop.jar`, `neoforge-runtime.jar`, all symlinked from
  `/Applications/.minecraft/.forbric-build/out/`). MC root overlay `/private/tmp/create-bughunt/mc`
  (symlinks to the installed `libraries/ assets/ .forbric/mappings/` + `versions/1.21.1`).
- Corpus: `/private/tmp/create-bughunt/scratch/corpus` — subject `create-1.21.1-6.0.10.jar`
  (`kind: popular`), the other 12 jars `kind: dependency`, `closure[create]` = those 12 (peers, not
  declared deps of Create).
- World fixture: `w7/corpus/client-world/W7Client`.

## Pre-committed reading

**Prediction: NOT loadable today — the run will not be `strict`.**

- `strict=false`, and I expect `world=false`; `run ∈ {FAIL, CRASH, TIMEOUT, STALL}`; `cause` a
  `boot-*` or a named escaping Throwable.
- Ranked, falsifiable blocker hypotheses (in the order I expect the bytes to name them):

  1. **Guest mixin application on the merged base.** Create's `create.mixins.json` (required, plugin
     `CreateMixinPlugin`, priority 1000) plus Flywheel's configs target a lot of game internals
     (`BuiltInRegistriesMixin`, `MinecraftServer`/`Player`/`BlockItem` injectors, ~30 accessors). Under
     `strict` a single un-attachable injector becomes a whole-mixin loss, so I expect at least one
     `…applies only partially … missing: @At(…)` line and a non-zero `confirmed_required`.
  2. **Method-level NeoForge ABI.** All 258 distinct `net.neoforged.neoforge.*` classes Create
     references are present in the staged runtime jars (checked statically), so the next-likely failure
     is a *present class with an absent method* → `NoSuchMethodError`/`AbstractMethodError`/
     `IncompatibleClassChangeError` in Create's registration path.
  3. **JiJ nested bundles not materialized** → `flywheel`/`ponder`/`registrate` read as missing →
     dependency/`Mod Loading has failed`.

- **Falsifier.** If the row reads `run=PASS, world=true, frames>=1, confirmed_required=0,
  strict=TRUE, exit=0` then Create is compatible with the set today and hypotheses 1–3 are dead.

The report's §Adjudication records which hypothesis the bytes actually named (or that the falsifier
fired), with the run reading quoted verbatim.
