# cobblemon Auto-Battle's `IncompatibleClassChangeError` — vanilla `final`, and the widener that un-finals it (2026-10-03)

Scope: the `boot-incompatible-class-change-error` that has stayed under `009-cobblemon-auto-battle__fabric`
through arms 5–8 (`reports/2026-10-03-cluster1-verdicts-{5,6,7,8}`). Question asked and answered here: **where does
the `final` come from — the merged base, a Forge/NeoForge patch, or the game itself?** Read-only except for the one
remap fallback it lands. No game JVM, no sweep.

## The error

Arm 5, `console.log:619`:

```
java.lang.IncompatibleClassChangeError: class com.cobblemon.mod.common.entity.pokemon.PokemonEntity overrides
final method net.minecraft.world.entity.LivingEntity.canBreatheUnderwater()Z
    at java.base/java.lang.ClassLoader.defineClass1(Native Method)
    at net.forbric.kernel.classloading.ForbricClassLoader.define(ForbricClassLoader.java:392)
```

Arms 6–8 name the same class on the next conflict in declaration order,
`...LivingEntity.getDimensions(Lnet/minecraft/world/entity/Pose;)Lnet/minecraft/world/entity/EntityDimensions;`.
That progression is part of the evidence, not noise: the widener began to *reach* the first method once the merge
namespace was fixed (`90ee13ef`), so the JVM stopped on the second.

## 1. The flag is vanilla 1.21.1's — not the merge's, not either patch's

`versions/1.21.1/1.21.1.jar` is the launcher's own client, intact: its SHA-1 is
`30c73b1c5da787909b2f73340419fdf13b9def88`, byte-for-byte the `downloads.client.sha1` in the sibling
`1.21.1.json`. In the obfuscated jar `LivingEntity` is `btn` and `canBreatheUnderwater` is `dW`
(`client-mojmaps.tsrg:69482/69618`):

```
$ javap -v -p -cp versions/1.21.1/1.21.1.jar btn | grep -A3 'boolean dW()'
  public final boolean dW();
    descriptor: ()Z
    flags: (0x0011) ACC_PUBLIC, ACC_FINAL
    Code:
         0: aload_0
         1: invokevirtual #651   // Method am:()Lbsx;          // getType() → EntityType
         4: getstatic     #656   // Field awi.m:Lawu;           // EntityTypeTags.CAN_BREATHE_UNDER_WATER
         7: invokevirtual #661   // Method bsx.a:(Lawu;)Z
        10: ireturn
```

1.21.1 made water-breathing data-driven (`getType().is(EntityTypeTags.CAN_BREATHE_UNDER_WATER)`) and sealed the
method, which is why no subclass anywhere in the game overrides it — the intermediary file carries exactly one
`canBreatheUnderwater` row, on `class_1309`. `getDimensions(Pose)` is the same shape one class down: `Entity`
declares it plainly, `LivingEntity` overrides it **final**.

Both patched jars and the merged base carry it unchanged:

| jar | `canBreatheUnderwater()Z` flags | body |
|---|---|---|
| `patched-mc-forge-1.21.1.jar` | `(0x0011) ACC_PUBLIC, ACC_FINAL` | vanilla's |
| `patched-mc-neoforge-1.21.1.jar` | `(0x0011) ACC_PUBLIC, ACC_FINAL` | vanilla's |
| `patched-mc-merged-1.21.1.jar` | `(0x0011) ACC_PUBLIC, ACC_FINAL` | vanilla's |

```
$ javap -v -p -cp <each of the three> net.minecraft.world.entity.LivingEntity | grep -A2 canBreatheUnderwater
  public final boolean canBreatheUnderwater();
    flags: (0x0011) ACC_PUBLIC, ACC_FINAL
```

**Verdict: neither the merge (`MergedBaseTool`/`PatchedMcBuilder`) nor a Forge/NeoForge patch sets this flag. It is
the host's own `final`, correct for vanilla 1.21.1, and both platforms preserve it.** The merge's own conflict row —
`merge-conflicts-1.21.1.txt:624`, `LivingEntity#canBreatheUnderwater()Z (forge hook lost)` — is bookkeeping for a
Forge-side divergence the merge resolved by keeping NeoForge's body; measured, that body is vanilla's in all three
jars, and the flag is untouched either way.

## 2. The mod overrides it legitimately — through its own access widener

`Cobblemon-fabric-1.8.1+1.21.1.jar` ships `cobblemon-common.accesswidener` (fabric.mod.json: `"accessWidener"`):

```
accessWidener	v2	intermediary
transitive-extendable	method	net/minecraft/class_1309	method_6094	()Z
transitive-extendable	method	net/minecraft/class_1309	method_18377	(Lnet/minecraft/class_4050;)Lnet/minecraft/class_4048;
```

In Fabric's format `extendable method` is *precisely* "clear ACC_FINAL so this may be overridden"; on Fabric Loader
the two lines are what make Cobblemon's overrides legal, and no real Fabric player meets this error.
`PokemonEntity` declares both, in Cobblemon's own bytecode:

```
$ javap -p -cp <Cobblemon jar> com.cobblemon.mod.common.entity.pokemon.PokemonEntity | grep -E 'method_6094|method_18377'
  public boolean method_6094();
$ javap -p -cp <the remapped jar> com.cobblemon.mod.common.entity.pokemon.PokemonEntity | grep -E 'canBreatheUnderwater|getDimensions'
  public boolean canBreatheUnderwater();      // getBehaviour().getMoving().getSwim().getCanBreatheUnderwater()
  public net.minecraft.world.entity.EntityDimensions getDimensions(Pose);
```

The remap of the bytecode is right (`method_6094` → `canBreatheUnderwater`); it is the **widener** entry that has to
follow. In the intermediary file `method_6094` is spelled on `class_1309` itself, but `method_18377` is declared on
`class_1297` (`Entity`):

```
$ awk 'NR>=30986 && NR<31369' mappings.tiny | grep -E '\tdW\t'      # class_1309 block
	m	()Z	dW	method_6094
$ awk '/^c\t/ {cls=$3} /method_18377$/ {print cls}' mappings.tiny
class=net/minecraft/class_1297
```

A subclass that overrides an inherited method keeps the superclass's own intermediary name and gets no row of its
own — the exact gap `ForbricMappings#mapMemberName` was written for (it is how `MixinNames` resolves a refmap key
written against the subclass).

## 3. The defect: the owner-scoped lookup dropped the second entry

`AccessWidenerRemapper.remap` translated each directive's member through
`spine.mapMethod(INTERMEDIARY, NAMED, owner, name, desc)` — an **owner-scoped** lookup. Against the audited arm's
remap cache (`/tmp/w7-remap-cache-5870/Cobblemon-fabric-…jar`, `accessWidener v2 official` after the header fix):

```
transitive-extendable	method	net/minecraft/world/entity/LivingEntity	canBreatheUnderwater	()Z          # translated
transitive-extendable	method	net/minecraft/world/entity/LivingEntity	method_18377	(Lnet/…/Pose;)Lnet/…/EntityDimensions;   # NOT
```

`method_18377` has no row under `class_1309`, so the lookup returned the name unchanged; the merged base declares no
member by that name; the `extendable` directive therefore met nothing; `LivingEntity.getDimensions` kept ACC_FINAL;
`PokemonEntity`'s declaration of it is an override of a final method; the JVM refuses the definition. The class
tweaker's own console line — `AccessCensus` records the unmatched member — is the only place the miss was visible.
Fix (following the repo's existing convention, not a second one): on a miss, fall back to
`spine.mapMemberName(name)`, as `MixinNames:473` and `MixinShadowMembers:196/214` already do.

`canBreatheUnderwater` translated all along; it failed first only while the whole file was being *skipped* for its
namespace (before `90ee13ef` fixed the merge namespace, and `75717e9d`/`cce20b5c` the suffix predicate). Once the
file applied, the inherited entry was the blocker.

## 4. What was landed

Landed as *合并基底⑲：access widener 的成员名按成员表回退——修 cobblemon 的 final 覆写 ICCE*:

* `forbric-kernel/src/main/java/net/forbric/kernel/access/AccessWidenerRemapper.java` — the `members` lambda falls
  back to `spine.mapMemberName(name)` when the owner-scoped lookup misses, with the measured Cobblemon shape in the
  comment and the class javadoc.
* `forbric-kernel/src/main/java/net/forbric/kernel/mapping/FabricGuestRemapper.java` — `REMAP_VERSION` →
  `1.21.1-8-accesswidener-inherited-member`: the widener text this stage writes changed for the same input jar, so a
  warm cache would otherwise keep the untranslated `method_18377` and the fix would be latent on exactly the
  machines that have a cache (same rule as `6875f090` and `cce20b5c`).
* `forbric-kernel/src/test/java/net/forbric/kernel/mapping/AccessWidenerRemapperTest.java` — two tests, on the real
  1.21.1 mappings and the real merged bytes.

## Test evidence

`AccessWidenerRemapperTest` (5 tests), focused run:

```
$ cd forbric-kernel && ./gradlew test --tests 'net.forbric.kernel.mapping.AccessWidenerRemapperTest' --offline \
    -Pforbric.stagedRoot=… -Pforbric.mcLibraries=… -Pforbric.fabricApi=… -Pforbric.rebornEnergy=…
test: 5 tests, 0 skipped, 0 failed
```

Both new tests **fail before the fix** (reverted production file only):

```
AccessWidenerRemapperTest > theMergedBasesFinalMembersLoseTheirFinalFlag() FAILED  (line 166: the getDimensions flag)
AccessWidenerRemapperTest > anInheritedMemberNamedOnTheSubclassIsRewritten() FAILED (line 137: the getDimensions text)
test: 5 tests, 0 skipped, 2 failed
```

* `anInheritedMemberNamedOnTheSubclassIsRewritten` — `remap()` on a real jar: `class_1309 method_18377` becomes
  `net/minecraft/world/entity/LivingEntity getDimensions (Lnet/minecraft/world/entity/Pose;)Lnet/minecraft/world/entity/EntityDimensions;`.
* `theMergedBasesFinalMembersLoseTheirFinalFlag` — end to end on the real merged jar: the remapped widener is merged
  by `ClassTweakerTransformer`, which is then run over the real
  `net/minecraft/world/entity/LivingEntity.class`; both `canBreatheUnderwater()Z` and `getDimensions(Pose)` come out
  with `ACC_FINAL` cleared. That is the exact condition the JVM checks before it will define `PokemonEntity`.

Wider focused run, `access.* + mapping.*`:

```
test: 42 tests, 3 skipped, 1 failed
```

The one failure is `ForbricCacheTest.resolveAndIsCached` (`AssertionFailedError` on a `@TempDir` path assertion) —
pre-existing and unrelated (it exercises only `ForbricCache`, which this change does not touch; the same failure is
recorded in `6875f090`'s message as the known `ForbricCacheTest` row). No new failure.

## Expected effect, for W7Harness

`cobblemon-auto-battle` should leave `boot-incompatible-class-change-error` and reach the post-application audit
like every other fabric subject; `cr` 0 → its next `compat-required-loss` set (its `req` is 22, of which the
mixin-injector part was never reached because the class never defined). **Needs the `REMAP_VERSION` bump's cache
invalidation**: without it the arm reuses `Cobblemon-fabric-…` with `method_18377` still intermediary and the row
does not move — which is the same trap `cce20b5c` was landed for.
