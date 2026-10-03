# Cobblemon's custom registries: two kernel-side walls, both proven on real bytes (2026-10-03)

Target: the asymmetry that keeps the fabric bucket under the bar — the real Fabric reference loads
**43** `Registered the cobblemon:* registry` lines from `Cobblemon-fabric-1.8.1+1.21.1.jar`, Forbric loaded
**0** and never reached the world. Both causes are kernel-side and both are fixed here:

1. **`EntityDataSerializers.registerSerializer` is a NeoForge caller-identity guard** that hard-refuses every
   caller but the class itself, so Cobblemon's Fabric main entrypoint aborts in `preInitialize` — that one abort
   is the single cause of 0/43 registry lines and all 91 datapack parse failures behind it.
2. **Kotlin `@Metadata`'s string table is a namespace** the bytecode remapper never touches, so `kotlin-reflect`
   resolves types by their *intermediary* names against a *named* runtime — the next wall, reached only once (1)
   was fixed.

Fixes: `1023cbc8` (1), `c2b9d90a` (2). Judging boot: kernel `30ee1859…` (= tree at `c2b9d90a`), Cobblemon +
fabric-api only, JDK 21, `--offline`, cold remap cache.

## 0. Where the asymmetry is (both sides, verbatim)

Reference (`/tmp/w7-fabric-ref`, real Fabric Loader, same jar, same JDK 21):

```
[12:31:51] [main/INFO]: Registered the cobblemon:molang registry
…
[12:31:51] [main/INFO]: Registered the cobblemon:technical_machines registry
[12:31:59] [Server thread/INFO]: Done (1.065s)! For help, type "help"
```

Forbric before this work (`reports/2026-10-03-cobblemon-forbric`… `console.log`, kernel `5aaad474`):

```
[12:52:19] [main/ERROR]: Modded EntityDataSerializers must be registered to NeoForgeRegistries.ENTITY_DATA_SERIALIZERS instead to prevent ID mismatches between client and server!
[12:52:19] [main/ERROR]: [Forbric/Fabric] main entrypoint of cobblemon failed
java.lang.UnsupportedOperationException: Modded EntityDataSerializers must be registered to NeoForgeRegistries.ENTITY_DATA_SERIALIZERS instead to prevent ID mismatches between client and server!
	at forbric/net.minecraft.network.syncher.EntityDataSerializers.registerSerializer(EntityDataSerializers.java:136) ~[patched-mc-merged-1.21.1.jar:?]
	at forbric/com.cobblemon.mod.fabric.CobblemonFabric.registerEntityDataSerializers(CobblemonFabric.kt:247)
	at forbric/com.cobblemon.mod.common.Cobblemon.preInitialize(Cobblemon.java:223)
```

`grep -c 'Registered the cobblemon'` = **0**; `grep -c 'Failed to parse cobblemon:'` = **91**
(86 `worldgen/processor_list` + 4 `placed_feature` + 1 `configured_feature`), every `processor_list` leaf cause
the same key:

```
Caused by: java.lang.IllegalStateException: Unknown registry key in ResourceKey[minecraft:root / minecraft:worldgen/structure_processor]: cobblemon:height_range
```

`worldgen/processor_list` is a **vanilla** registry, and the same jar parses it without complaint on the
reference — so the parsing context differs, not the bytes. The "unbound value" is a consequence: Cobblemon's
`STRUCTURE_PROCESSOR` entries never register because its entrypoint never gets past line 223.

## 1. Wall one: the merged base's caller-identity guard (bytes)

`patched-mc-merged-1.21.1.jar` → `net/minecraft/network/syncher/EntityDataSerializers`, `javap -c`:

```
  public static void registerSerializer(net.minecraft.network.syncher.EntityDataSerializer<?>);
    Code:
       0: getstatic     #119  // Field STACK_WALKER
       3: invokevirtual #123  // StackWalker.getCallerClass()
       6: ldc           #2    // class net/minecraft/network/syncher/EntityDataSerializers
       8: invokevirtual #127  // Object.equals
      11: ifne          34                        // caller IS the class → vanilla path
      14: getstatic     #129  // LOGGER
      17: ldc           #131  // String Modded EntityDataSerializers must be registered to NeoForgeRegistries…
      19: invokeinterface #137 // Logger.error(String)
      24: new           #139  // UnsupportedOperationException
      27: dup
      28: ldc           #131
      30: invokespecial #142  // <init>(String)
      33: athrow
      34: getstatic     #144  // Field SERIALIZERS:CrudeIncrementalIntIdentityHashBiMap
      37: aload_0
      38: invokevirtual #150  // add(Object)I
      41: sipush        256
      44: if_icmplt     57
      …
      57: return
```

Only the class's own `<clinit>` satisfies the test; every mod caller is refused. **The kernel had zero handling
of it** (`grep -rn registerSerializer src/main/java` was empty), which is why a Fabric-ecosystem guest — compiled
against the vanilla overload and unable to name a NeoForge registry — hits it as an unhandled cross-ecosystem
surface while `MergedBaseCompat` already translates several of the same shape.

The guard's destination is right and its audience is wrong, so the fix **routes rather than removes**:
`ForbricMergedBaseCompatTransformer.routeForeignEntityDataSerializersToNeoForge` replaces the refuse block
(offsets 14–33) with

```
aload_0
invokestatic net/forbric/kernel/runtime/KernelEntityDataSerializers.register (Lnet/minecraft/network/syncher/EntityDataSerializer;)V
return
```

and leaves the `ifne` and the vanilla path it guards byte-for-byte intact, so `EntityDataSerializers.<clinit>`
still registers vanilla's defaults through `SERIALIZERS.add`. The replacement is straight-line and
empty-to-empty; the two compiler frames (at 34, the `ifne` target, and at 57) sit outside the edited range, so
no frame is recomputed.

### Where the id comes from (the whole risk, settled before editing)

The vanilla overload carries **no name**, and NeoForge's registry derives the wire id from **insertion order**
(`CommonHooks.getSerializerId` returns `NeoForgeRegistries.ENTITY_DATA_SERIALIZERS.getId(value) + 256` for
anything the vanilla map does not hold; `CommonHooks.getSerializer(id)` is the mirror). So the id is only
deterministic if the **name** is. `KernelEntityDataSerializers` derives the name from the serializer's own class
— `forbric:guest_serializer/<lower-cased binary name>` — not from a counter, so the same mod set produces the
same name (and therefore the same id) on both sides whatever order the guests run in. Registration is idempotent
by identity, so a guest registering twice is a no-op.

Measured on the boot — all eight of Cobblemon's serializers, each named and id-assigned:

```
[Forbric/DataSerializers] com.cobblemon.mod.common.api.net.serializers.Vec3DataSerializer registered as forbric:guest_serializer/com.cobblemon…vec3dataserializer in NeoForge's synced registry (wire id 256) …
… (8 lines, wire ids 256–263) …
```

### Cost recorded

- A guest's serializer now lives in NeoForge's **synced** id space (wire id 256 + registry id), so a stock
  Fabric/vanilla client cannot resolve it; a Forbric client gets it from registry sync by name. This is the same
  trade every cross-ecosystem registration on this base makes, and it is what removes the mismatch the guard was
  guarding against.
- The guard no longer refuses **any** caller; it translates every external one into the registry it named. A
  caller that deliberately wanted the vanilla map's id space can no longer get it — but no ecosystem wanted that,
  and NeoForge's own guard called it a defect.
- The name is synthetic (`forbric:…`) because the Fabric jar ships no NeoForge name for these entries; deriving
  the owning mod id would need the container, and the property that matters is determinism, not prettiness.

The rejected alternative, for the record: **stand the guard down** and let the guest use vanilla's
`CrudeIncrementalIntIdentityHashBiMap`. That map is capped at 256 and is not synced, so one extra serializer on
the client shifts every id after it — the exact failure the guard exists to prevent.

## 2. Wall two: Kotlin `@Metadata`'s string table (bytes)

With wall one fixed, the entrypoint got one line further and died at `Cobblemon.loadConfig` →
`SpeciesAdditions.<clinit>` (run `2026-10-03-cobblemon-registries-guardonly`):

```
java.lang.ExceptionInInitializerError: null
	at forbric/com.cobblemon.mod.common.pokemon.SpeciesAdditions.<clinit>(SpeciesAdditions.java:33)
	at forbric/com.cobblemon.mod.common.Cobblemon.preInitialize(Cobblemon.java:236)
Caused by: java.lang.ClassNotFoundException: net.minecraft.class_2960 (game-side, but not found in any kernel-owned jar)
	at net.forbric.kernel.classloading.ForbricClassLoader.defineGameClass(ForbricClassLoader.java:306)
	at kotlin.reflect.jvm.internal.KDeclarationContainerImpl.parseType(KDeclarationContainerImpl.kt:296)
	at kotlin.reflect.jvm.internal.KDeclarationContainerImpl.parseJvmDescriptor(KDeclarationContainerImpl.kt:289)
	at kotlin.reflect.jvm.internal.KPropertyImplKt.computeCallerForAccessor(KPropertyImpl.kt:263)
```

`kotlin-reflect` reaches the name through `JvmProtoBuf$JvmMethodSignature.getDesc():I` — an **index** into the
metadata string table (`d2`) — and the same class's `d2` still spells it intermediary, because a bytecode
remapper does not touch annotations. Real bytes, `Cobblemon-fabric-1.8.1+1.21.1.jar` →
`com/cobblemon/mod/common/pokemon/SpeciesAdditions.class`, `javap -v`:

```
d2=["…","Lnet/minecraft/class_2960;","data","","reload","(Ljava/util/Map;)V","Lnet/minecraft/class_3222;",…,
    "()Lnet/minecraft/class_2960;","getId","()Lnet/minecraft/class_2960;",…]
```

On real Fabric the metadata's namespace and the game's runtime namespace are *both* intermediary, so the gap is
invisible. Forbric's merged base runs **named** (Mojmap), so the name resolves to nothing.

Fix (`KotlinMetadataRemapper`, a remap-stage post-pass like `MixinNames`/`InheritedMemberRefs`/
`InheritedMemberDecls`): rewrite the `d2` table — descriptors (`L…;`, `[…`, `(…)…`) through ASM's
`Remapper.mapDesc` so array components are covered, bare internal names through
`spine.mapClass(INTERMEDIARY → NAMED)`, everything else (member names, Kotlin's own types) untouched. `d1` is
left alone on purpose: it is protobuf whose type entries reference `d2` **by array index**, so replacing entries
in place changes names without moving a single reference. `REMAP_VERSION` → `1.21.1-12-kotlin-metadata`, because
the same input jar now yields different class bytes and a warm cache would keep serving the intermediary table.

Measured on the boot (`[Forbric/Mapping] … had game names in their Kotlin @Metadata rewritten`):
Cobblemon **3167** classes, kotlin-stdlib 15, kotlinx-datetime 6, kotlinx-serialization-core 4,
kotlinx-coroutines-core 4, plus five smaller jars.

## 3. The proof boot

`w7/harness/sweep.py` (corpus = Cobblemon + fabric-api only, `cobblemon` promoted to a bootable row), kernel
frozen at `30ee1859072a10638aa11a974fe03fd6c9c74a822ed89b9a1c1afe3e128cc707` (= the tree at `c2b9d90a`),
`W7_JAVA` = JDK 21.0.7, `--jvm=-Dforbric.compatibilityPolicy=continue`, cold remap cache:

```
STRICT PASS     mod=OK         346s Cobblemon-fabric-1.8.1+1.21.1.jar  cause=None
```

Both acceptance lines, quoted from
`w7/reports/2026-10-03-cobblemon-registries/per-mod/run/000-cobblemon__fabric/console.log`:

```
[13:30:40] [main/INFO]: Registered the cobblemon:molang registry
…
[13:30:40] [main/INFO]: Registered the cobblemon:technical_machines registry
```

```
[13:30:55] [Server thread/INFO]: Done (4.842s)! For help, type "help"
```

`grep -c 'Registered the cobblemon:'` = **43** (identical set to the reference's 43);
`world/level.dat` exists → `world=true`; `Failed to parse cobblemon:` = **0**; `entrypoint of cobblemon failed` =
**0**; `confirmedRequired` = 0. The row is `{"run": "PASS", "world": true, "mod": "OK", "strict": true}`.

## 4. Tests (focused, staged-root flags only)

`MergedBaseEntityDataSerializersTest` (4 tests, real bytes) — on the **real merged base**: the guard's
`new UnsupportedOperationException` is replaced by the kernel registration; the vanilla `SERIALIZERS.add` path and
both branches survive; the frame count is unchanged; `BasicVerifier` accepts the result; a second pass is
idempotent; a neighbouring class is untouched. Plus the **real Cobblemon jar**: its entrypoint makes exactly
**eight** calls to the vanilla overload (intermediary `class_2943.method_12720`), the calls this fix serves.

`KotlinMetadataRemapperTest` (3 tests, real bytes) — on the **real** `SpeciesAdditions.class`: the `d2` table's
`Lnet/minecraft/class_2960;` / `()Lnet/minecraft/class_2960;` become named while member names survive; a plain
Java class answers `null` and is byte-identical; the jar pass changes exactly one entry and is idempotent.

Red→green was demonstrated both ways: with the guard repair's dispatch line commented out,
`theGuardsRefuseBlockBecomesAKernelRegistration` fails at `assertNotSame` (the class comes back unedited), and the
metadata test fails at the first `d2` assertion before the pass exists.

Focused runs (no project-wide suite):

```
cd forbric-kernel && ./gradlew test --tests 'net.forbric.kernel.transform.MergedBaseEntityDataSerializersTest' \
  --tests 'net.forbric.kernel.mapping.KotlinMetadataRemapperTest' --tests 'net.forbric.kernel.transform.TransformerAnchorCensusTest' \
  --offline -Pforbric.stagedRoot=…/p0/run -Pforbric.mcLibraries=…/p0/mc-1.21.1/libraries \
  -Pforbric.fabricApi=…/p0/fixtures/fabric-api-0.116.17+1.21.1-named.jar -Pforbric.rebornEnergy=…/p0/fixtures/energy-4.1.0-named.jar
test: 0 failed
```

The whole transform package (`--tests 'net.forbric.kernel.transform.*'`) reads 769 tests / 0 failed, which
includes the census test that pins `REPAIRS` ↔ `claims()` ↔ dispatch order for the new repair.

## 5. What moves next

`cobblecoop`, `cobblespawnregions` and `Cobblemon-Auto-Battle` were expected to move with this; the first two are
`registry-load` rows, so their consoles should now show the same 43 lines. Not measured here.
