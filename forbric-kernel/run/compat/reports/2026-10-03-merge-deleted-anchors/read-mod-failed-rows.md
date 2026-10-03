# Read 4 — the `mod` field and the two `mod=FAILED` rows (builders-enhancements, cobblemon_skills_api)

Scope: the field the per-subject rows publish as `mod` (OK / DEGRADED / FAILED / ABSENT), what writes it, what
FAILED means in the kernel, whether it should gate — and then the two subjects that carried FAILED at the widest
depth. Verified on this machine against the producers and against the two real jars
(`w7/corpus/mods/builders-delight-2.2.0.1.21.1.jar`, `w7/corpus/mods/cobblemon_pufferfish_api_fabric-0.9.1-beta.jar`).

## 1. The producer, and the two different things called `mod=FAILED`

The published field is the harness's, not the kernel's. `run/compat/mac/per-mod.py`:

```
10:  mod:  the tested jar's own status in compatibility-report.json: OK | DEGRADED | FAILED | ABSENT (kernel never listed it)
64:  def mod_status(jar, report):
65:      rows = [m for m in report.get('mods', []) if m.get('jar') == jar]
66:      own = {m['modId'] for m in rows}
67:      rows += [m for m in report.get('mods', []) if m.get('bundledBy') in own and m not in rows]
68:      if not rows: return 'ABSENT', []
69:      worst = 'FAILED' if any(m['status'] == 'FAILED' for m in rows) else …
```

So `mod` is the WORST `status` among the subject's own `mods[]` rows plus anything bundled by them, and `ABSENT`
means the kernel never listed the jar at all. `mods[].status` is the kernel's `ModCatalog` status.

`ModCatalog` (`src/main/java/net/forbric/api/ModCatalog.java`) is where FAILED is defined, and it is narrower than
the word suggests:

* **Never invent a row** — an id the catalogue does not have is dropped without a word.
* **FAILED is sticky and outranks DEGRADED** — "a mod whose constructor threw and which then also missed a setup
  phase is still, first and last, a mod that did not finish loading".
* `CompatibilityFindings.observeInitializationFailures` documents the two audited production sources: "withdrawn
  `@Mod` construction, and Fabric main/client/server entrypoint failure. Partial setup and optional feature losses
  use DEGRADED and are deliberately not promoted here."

Concretely, the only sites that raise FAILED are:

| site | reason string |
|---|---|
| `KernelFabricEcosystem.java:422` | `its preLaunch entrypoint threw` |
| `KernelFabricEcosystem.java:733` | `its <main\|client\|server> entrypoint threw` |
| `KernelModLoader.java:1253` (via `constructorThrew`, `:475`) | `its @Mod constructor threw (…)`, mod withdrawn |

and each becomes a **CONFIRMED, required `initialization:<phase>` finding** (`CompatibilityFindings.java:52-70`,
sources `KernelModLoader @Mod construction` / `KernelFabricEcosystem … entrypoint`). That is how FAILED gates: it
is a required loss in `confirmedRequired`, and it is separately carried to the published acceptance as
`catalogFailures` when a FAILED row's reason has no matching `initialization:` finding — marked
`"classification":"UNCLASSIFIED"` (`CompatibilityFindings.java:171-190`), which `push-and-run.py:398` and
`soak-run.py:69` fail the run on ("no unclassified FAILED mod"), and which `per-mod.py:109-110` requires empty for
`strict`.

## 2. In arm 11 neither subject had a kernel FAILED row at all

W7Harness's arm-11 artifacts (`reports/2026-10-03-cluster1-verdicts-11`, kernel `4897a130`) have
**`mods[]` empty and `catalogFailures: []` for both subjects**, and those rows carry
`status_source: console`. So the `mod=FAILED` there is the harness's own console fallback — "the kernel logged that
this mod's entrypoint threw" — which is a **coarser predicate** than `ModCatalog`'s FAILED, and, read through
`per-mod.py`, a subject with no `mods[]` rows yields **`ABSENT`**, not FAILED. Two consequences worth keeping:

* The label is right about the *event* (a real exception from the mod's own entrypoint) but must not be quoted as
  the catalogue's FAILED. `status_source` has to travel with the number, and the aggregate column should say
  `console` where it is the fallback. W7Harness has recorded the empty-1.21.1-catalogue gap separately.
* Because the catalogue was empty, nothing in these two rows reached `confirmedRequired` through the
  `initialization:` channel; the subjects were already gated by `world=false` / `loaded=false` (the
  `registry-load` blocker at that depth).

## 3. The two subjects, classified

Both are **real failures of the subject's own code, not over-reports** — the earlier `SUSPECTED` family was the
kernel asserting a *mixin* loss; here the mod's own frames are on the stack and its own entrypoint threw. But they
are two different classes of cause, and only one of them is the kernel's to fix.

### 3.1 `builders-enhancements` — real failure, **kernel-owned cause** (inherited-member remap gap)

Console (arm 11, verbatim):

```
[main/ERROR]: [Forbric/Fabric] main entrypoint of bd failed
java.lang.NoSuchMethodError: 'net.minecraft.world.level.block.state.BlockBehaviour$Properties
    net.fabricmc.fabric.api.object.builder.v1.block.FabricBlockSettings.method_9630(net.mi…
    at forbric/com.zrollus.bd.block.ModBlocks.<clinit>(ModBlocks.java:238)
    at forbric/com.zrollus.bd.BuildersDelight.onInitialize(BuildersDelight.java:42)
```

Verified against the raw jar (`builders-delight-2.2.0.1.21.1.jar`), and the asymmetry is the point — one member,
two owners, only one resolved:

```
#505 = NameAndType  method_9630:(Lnet/minecraft/class_4970;)Lnet/minecraft/class_4970$class_2251;
#506 = Methodref    net/minecraft/class_4970$class_2251.method_9630:(…)      ← the game owner: REMAPPED
#967 = Methodref    net/fabricmc/fabric/api/object/builder/v1/block/FabricBlockSettings.method_9630:(…)   ← SURVIVED
ModBlocks.<clinit>:
   2293: invokestatic #967   // FabricBlockSettings.method_9630(Lnet/minecraft/class_4970;)L…class_2251;
   2315: invokestatic #967   …   (six call sites in all, four of them in <clinit>)
```

`class_4970$class_2251` is `BlockBehaviour$Properties` and `method_9630` is its **`ofFullCopy(BlockBehaviour)`** (the
merged base's own signature; this note first said `copyOf`, which the spine's answer corrects); the mod reaches that
inherited member through fabric-api's `FabricBlockSettings` subclass as the call's owner, and the guest-jar remap
resolved the game-owner form while leaving the subclass-owner form intermediary. This is the same family as
`b1eb468f` one layer over (`AccessWidenerRemapper`: `method_18377` registered on `class_1297` but written on
`class_1309`, resolved by falling back to `spine.mapMemberName(name)`).

**LANDED** — and the fix had to be measured into place, so the two candidates named here are BOTH wrong as written:
an extra `IMappingProvider` entry for the subclass owner is never consulted (tiny-remapper keys member mappings by the
classes it has READ — verified: the provider was composed and the reference still came out intermediary), and putting
the declaring jar on the classpath works for that reference but not for the family (the next one,
`FabricDataOutput.method_45971`, then needs its own jar). What landed is the post-pass convention the other two
namespaces already use: `InheritedMemberRefs.translate(jar, spine)` right after `MixinNames.translate`, renaming only
references that are still intermediary, that no owner-scoped lookup resolves, and that the name table can answer —
plus `REMAP_VERSION` → `1.21.1-9-inherited-member-refs`. See
[`../2026-10-03-inherited-member-refs/README.md`](../2026-10-03-inherited-member-refs/README.md).

### 3.2 `cobblemon_skills_api` — real failure, **subject-side** (an undeclared dependency)

Console (arm 11, verbatim):

```
[main/ERROR]: [Forbric/Fabric] main entrypoint of cobblemon_puffish_skills_api failed
java.lang.NoClassDefFoundError: net/puffish/skillsmod/api/reward/Reward
    at …ForbricClassLoader.define(ForbricClassLoader.java:392)
```

Verified against the real jar: id `cobblemon_puffish_skills_api`, `main` entrypoint
`com.cobblemon_puffish_skills.api.CobblemonSkillsBridgeFabric`, and its `fabric.mod.json` declares only
`fabricloader`, `minecraft`, `fabric` — **the dependency it actually needs is not declared**, the class is not in
its own jar, and no `puffish`/`skills` jar exists anywhere in the corpus (`w7/corpus/mods/`). So this is a
packaging/closure limit of the subject itself (the same family as miguelfaction's missing `migueleconomy`), not a
kernel defect — and the enumeration's earlier note said "cobblemon core", which is wrong: the missing class is from
**Pufferfish's Skills**, a different mod, which the subject's own name announces.

## 4. Answers to the two questions asked

* **Real mod-side failure or over-report?** Neither row is an over-report. Both are the mod's own entrypoint
  throwing, with its own frames. But only one of them is a *kernel* defect underneath:
  `builders-enhancements` is a kernel remap gap surfacing as a subject-side throw; `cobblemon_skills_api` is
  entirely subject-side (an undeclared, absent dependency).
* **Should it gate?** As the kernel defines it, yes: a FAILED mod is a mod that did not finish loading, and it is
  published as a CONFIRMED required `initialization:` finding — the same gate as any other required loss. What must
  **not** be trusted is the harness's console fallback being read as the catalogue's FAILED: at arm 11 the
  catalogue had no rows for either subject, so the honest per-row statement is "the kernel logged this mod's
  entrypoint throwing" (`status_source: console`), and a subject with no `mods[]` rows is `ABSENT` to
  `per-mod.py`. Reconciling the two tools' definitions of this field is the harness work this read hands back.

## 5. Follow-ups this leaves

1. **Harness**: quote `status_source` with `mod`, and state the console-fallback predicate in the row rather than
   in prose next to it (W7Harness's own caveat, now recorded here so it is not re-derived).
2. **Kernel, mapping lane**: the subclass-owner inherited-member remap gap, with the two candidate mechanisms
   above. It is the only kernel-owned cause found in these two rows.
3. **Corpus**: `cobblemon_skills_api` cannot load without Pufferfish's Skills and does not declare it; if the
   corpus keeps it, it should be annotated as a subject-side closure limit rather than counted as a kernel loss.
