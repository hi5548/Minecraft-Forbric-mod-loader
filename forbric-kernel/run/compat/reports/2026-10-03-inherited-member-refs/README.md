# `builders-enhancements`' `NoSuchMethodError` — a game member reached through another mod's class (2026-10-03)

Scope: the `builders-enhancements` `mod=FAILED` row's underlying defect, assigned by Main after
[`read-mod-failed-rows.md`](read-mod-failed-rows.md) classified it as "real failure, kernel-owned cause". Fixed here,
with the red-then-green test on the real spine and the real mod jar, and a `REMAP_VERSION` bump because the same input
jar now remaps to different bytes.

Inputs: the subject `builders-delight-2.2.0.1.21.1.jar` (`w7/corpus/mods/`), the remap fixtures
(`p0/mc-1.21.1/.forbric/mappings/{intermediary-1.21.1.jar,client-1.21.1.txt}`), the merged base
`patched-mc-merged-1.21.1.jar`.

## 1. The defect: one member, two owners, only one resolved

Console (arm 11, verbatim):

```
[main/ERROR]: [Forbric/Fabric] main entrypoint of bd failed
java.lang.NoSuchMethodError: 'net.minecraft.world.level.block.state.BlockBehaviour$Properties
    net.fabricmc.fabric.api.object.builder.v1.block.FabricBlockSettings.method_9630(net.mi…
    at forbric/com.zrollus.bd.block.ModBlocks.<clinit>(ModBlocks.java:238)
    at forbric/com.zrollus.bd.BuildersDelight.onInitialize(BuildersDelight.java:42)
```

The raw jar's constant pool carries the SAME member under both owners, and the remap resolved exactly one of them:

```
#505 = NameAndType  method_9630:(Lnet/minecraft/class_4970;)Lnet/minecraft/class_4970$class_2251;
#506 = Methodref    net/minecraft/class_4970$class_2251.method_9630:(…)                     ← game owner: REMAPPED
#967 = Methodref    net/fabricmc/fabric/api/object/builder/v1/block/FabricBlockSettings.method_9630:(…)
                                                                                            ← subclass owner: SURVIVED
ModBlocks.<clinit>:
   2293: invokestatic #967   // FabricBlockSettings.method_9630(Lnet/minecraft/class_4970;)L…class_2251;
   …four call sites in <clinit> and six in the class
```

`class_4970$class_2251` is `BlockBehaviour$Properties` and `method_9630` is its **`ofFullCopy(BlockBehaviour)`**
(the merged base: `public static BlockBehaviour$Properties ofFullCopy(BlockBehaviour)`; the earlier notes in
`complete-enumeration.md` and `read-mod-failed-rows.md` said `copyOf`, which this fixes — the spine resolves the name
and that is what it returns). `FabricBlockSettings` is fabric-api's subclass, and at runtime
`invokestatic FabricBlockSettings.ofFullCopy` resolves through the superclass, so the *name* is the whole problem.

## 2. Why the engine cannot do it — measured, not assumed

tiny-remapper resolves an inherited member by walking the classpath from the call's owner up to the class declaring
it. The game is on that classpath (`FabricGuestRemapper` converts it to the source namespace for exactly this), so a
game owner resolves. **Another mod's class is not on it** — a guest jar is neither input nor classpath for the jar
being remapped — so the walk stops at a class the mappings do not know.

Two things were measured before choosing the fix, because both the obvious alternatives are wrong:

* **An extra mapping does not reach it.** `InheritedMemberRefs` was first written as an extra `IMappingProvider` entry
  for `(FabricBlockSettings, method_9630, desc) → ofFullCopy` — the shape `MixinShadowMembers` uses for mixin shadows.
  The provider was verifiably composed (a probe asserted it was not the identity) and **the reference still came out
  intermediary**: tiny-remapper keys member mappings by the classes it has READ, and that owner is read by neither the
  input nor the classpath.
* **The declaring jar on the classpath works but is the wrong shape of change.** With
  `fabric-object-builder-api-v1` (which declares `FabricBlockSettings`) *and* the converted merged base on the
  classpath, the engine renamed the member itself — and then left the next one of the same family
  (`FabricDataOutput.method_45971`) because that owner's jar was not there. Putting every guest jar on every other
  guest's classpath is an N² change to the stage, and it still needs the hierarchy to stay inside the jars given.

## 3. The fix: the same post-pass convention the other two namespaces use

`net.forbric.kernel.mapping.InheritedMemberRefs.translate(jar, spine)`, called from `FabricGuestRemapper` right after
`MixinNames.translate` and before `AccessWidenerRemapper` — the same convention as those two: a namespace the engine
cannot reach is a name the kernel resolves itself with `ForbricMappings.mapMemberName`, whose javadoc documents
exactly this case ("a subclass that overrides a method the obfuscated jar already spells with its superclass's own
name … an owner-scoped lookup returns nothing where the name alone is unambiguous").

A reference is renamed only when all three hold:

1. its name is still an intermediary spelling (`method_\d+` / `field_\d+`);
2. the owner-scoped lookup returns nothing — which IS the shape being repaired: an owner the spine knows would have
   been renamed by the engine, so a name still standing through a known owner is left alone;
3. the name alone resolves in the spine (intermediary names a member once across the game).

The owner is never rewritten — it is another mod's class and has no mapping. `InvokeDynamic` bootstrap handles and
`LDC` handles are covered with the method/field references. Classes whose bytes contain no `method_`/`field_` are
skipped by a byte scan before parsing, and the jar is not rewritten at all when nothing changed.

`REMAP_VERSION` → **`1.21.1-9-inherited-member-refs`**: the same input jar now produces different output, so every
warm cache must re-derive rather than serve bytes from the previous stage — the same reason `1.21.1-8` exists (and
the same trap W7Harness measured twice: a fix that never runs because nothing asked the cache to re-derive).

## 4. Evidence

**Real jar, the whole stage, before and after** (the probe runs `FabricGuestRemapper.remapAll` on
`builders-delight-2.2.0.1.21.1.jar` with the real mapping data and counts the member references whose owner the jar
does not define and whose name is still intermediary — the invariant `FabricGuestRemapperTest` states):

```
=== in  builders-delight-2.2.0.1.21.1.jar          leftovers=37   (class_1269.field_5811, class_1657.method_5998, …)
[Forbric/Mapping] builders-delight-…jar: 4 class(es) carried a game member reached through another mod's class,
                  renamed by name — a remap engine cannot walk to a declaring class it is not given
=== out builders-delight-2.2.0.1.21.1-….jar        leftovers=[]
```

Before the pass the same output carried `FabricBlockSettings.method_9630` six times (and, on the all-guests-on-the-
classpath probe, `FabricDataOutput.method_45971` beside it — one example of the family, not the whole of it).

**In-tree, real spine, red then green** — `InheritedMemberRefsTest`, 3 tests:

* the red premise is asserted first: the jar as written leaves `FabricBlockSettings.method_9630` as a survivor;
* the pass renames it, the OWNER stays (`FabricBlockSettings#ofFullCopy`), the survivors go to none, and a second
  pass changes nothing (idempotent);
* a name the spine cannot resolve is left exactly as written, and a reference through a KNOWN owner is left to the
  engine — the boundary of the repair, pinned.

Run with the real mapping data (`MC_DIR=<mc dir with .forbric/mappings>`): `3 tests, 0 skipped, 0 failed`. With the
pass short-circuited to `return 0`, the rename test fails (`3 tests, 1 failed`) — the test measures the fix, not the
fixture. Without the mapping fixtures: `5 tests, 5 skipped, 0 failed`, so a bare checkout stays green.

`net.forbric.kernel.mapping.*` with the fixtures staged: 26 tests, 3 skipped, 1 failed — the failure is the
pre-existing `ForbricCacheTest.resolveAndIsCached` (`@TempDir` path assertion, recorded as an existing row by
`6875f090`), unrelated; `FabricGuestRemapperTest` itself passes with the new pass composed into the stage.

## 5. Cost

One byte scan over each guest class, one parse of the classes that still contain an intermediary token, and one jar
rewrite only when a name changed (4 classes of 37 in the measured jar). The stage is cached per boot, so this is paid
once per cache directory. No new classpath, no new engine configuration, no per-jar variation.

## 6. What this does not cover

* **`cobblemon_skills_api`** stays `mod=FAILED` and is **not ours**: `NoClassDefFoundError:
  net/puffish/skillsmod/api/reward/Reward`, its `fabric.mod.json` declaring only fabricloader/minecraft/fabric, and no
  puffish jar in the corpus. Recorded so nobody re-opens it — see [`read-mod-failed-rows.md`](read-mod-failed-rows.md)
  §3.2.
* The `registry-load` blocker (an empty `minecraft:painting_variant` at world load, every fabric subject) is a
  different defect class and a different owner.
* The `mod` field's two definitions are now distinguished in W7Harness's rows (`status_source: console:entrypoint-threw`
  plus `catalog_mods_rows`), so this row's FAILED is checkable as the console fallback rather than the catalogue's.
