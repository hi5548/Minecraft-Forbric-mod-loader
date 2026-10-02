# Two fabric boot-fails at the same point in the load (`2026-10-03-fabric-bootfails`)

Scope: the two subjects of the 2026-10-03 fabric sweep that never reached `Preparing level`, each its own defect
class, both kernel-owned and both repaired here — one commit each, with the red-then-green test on the real subject
data. The other eight subjects boot (see W7Harness's `2026-10-03-fabric-5ids/`).

Inputs: the per-subject consoles (`w7/reports/2026-10-03-fabric-full-at-world/per-mod/run/{002-chipped,007-betterrailwaysystem}__fabric/console.log`),
the frozen merged base `patched-mc-merged-1.21.1.jar` (sha256 249bdb2a…, the kernel sha the rows ran on), the
carriers (`forge-runtime.jar` / `neoforge-runtime.jar`), the pristine `forge-1.21.1-52.1.16-universal.jar` from the
build's own download cache, the real mapping data (`p0/mc-1.21.1/.forbric/mappings/`), and the subject jars
(`w7/corpus/mods/`).

---

## 1. `betterrailwaysystem` — `AbstractMethodError` on the reload listener it implements

### 1.1 The console, verbatim

```
[06:09:06] [main/WARN]: Failed to load datapacks, can't proceed with server load. You can either fix your datapacks or reset to vanilla with --safeMode
java.util.concurrent.ExecutionException: java.lang.AbstractMethodError: Method org/dcstudio/config/BetterRailwaySystemDataReloadListener.onResourceManagerReload(Lnet/minecraft/server/packs/resources/ResourceManager;)V is abstract
	at java.base/java.util.concurrent.CompletableFuture.wrapInExecutionException(CompletableFuture.java:345) ~[?:?]
	…
	at forbric/net.minecraft.server.Main.main(Main.java:258) [patched-mc-merged-1.21.1.jar:?]
Caused by: java.lang.AbstractMethodError: Method org/dcstudio/config/BetterRailwaySystemDataReloadListener.onResourceManagerReload(Lnet/minecraft/server/packs/resources/ResourceManager;)V is abstract
	at forbric/org.dcstudio.config.BetterRailwaySystemDataReloadListener.onResourceManagerReload(BetterRailwaySystemDataReloadListener.java) ~[BetterRailwaySystem-0.1.0-mc1.21.1-c358b083da864932.jar:?]
	at forbric/net.minecraft.server.packs.resources.ResourceManagerReloadListener.lambda$reload$0(ResourceManagerReloadListener.java:15) ~[patched-mc-merged-1.21.1.jar:?]
```

### 1.2 The base is not at fault — the interface method is exactly where vanilla has it

```
$ javap -p net/minecraft/server/packs/resources/ResourceManagerReloadListener.class     # the merged base
public interface net.minecraft.server.packs.resources.ResourceManagerReloadListener extends …PreparableReloadListener {
  public default java.util.concurrent.CompletableFuture<java.lang.Void> reload(…);
  public abstract void onResourceManagerReload(net.minecraft.server.packs.resources.ResourceManager);
  private void lambda$reload$0(…);   // invokeinterface onResourceManagerReload:(…ResourceManager;)V
}
```

`onResourceManagerReload(ResourceManager)` is declared, abstract, the same descriptor the mod implemented, and reached
by the base's own default `reload`. It was neither renamed nor moved nor removed, and this is not the mod's
`ResourceManagerReloadListener` failing to find the base's: the merged class is NeoForge's recompile of the interface
(the merged file is byte-identical to `patched-mc-neoforge-1.21.1.jar`'s, sha256 `d38a6b4b…`), and the disassembly —
the declared method, its descriptor, the `invokeinterface` in `lambda$reload$0` — is identical in vanilla's own
`server-official.jar`, both patched jars and the merged base. Neither the merge nor a carrier has a hand in this one.

### 1.3 What the remap actually emitted

The subject jar in `mods/` is in the **intermediary** namespace (`getFabricId()` returns
`net/minecraft/class_2960`, the listener's parameter is `net.minecraft.class_3300`), and the kernel remaps it to the
merged base's Mojmap namespace (`FabricGuestRemapper`, `1.21.1-11`). The remapped class — the one the game loaded —
came out with its descriptor named and its NAME not:

```
$ javap -p /tmp/w7-remap-cache-8827/BetterRailwaySystem-0.1.0-mc1.21.1-c358b083da864932.jar   # com/…/ReloadListener
public final class org.dcstudio.config.BetterRailwaySystemDataReloadListener
    implements net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener {
  public net.minecraft.resources.ResourceLocation getFabricId();          ← descriptor remapped
  public void method_14491(net.minecraft.server.packs.resources.ResourceManager);   ← name NOT remapped
}
```

Measured over the whole remapped jar: **exactly one** class still carries an intermediary token, and it is this one
(`method_14491`). Every other member the mod declares or references resolves.

### 1.4 Why the engine could not resolve it

tiny-remapper names a **declaration** by walking the classpath from the class it is read in up to the class that
declares the member. The class read is the mod's own (not in the mappings), so the walk goes to the supertypes: the
classpath holds the game (converted to the source namespace for exactly this reason — `FabricGuestRemapper`), and
**not the Fabric API** the interface belongs to. `SimpleSynchronousResourceReloadListener` is neither input nor
classpath, so the walk stops one class short of `ResourceManagerReloadListener`:

```
$ javap -p fabric-resource-loader-v0-….jar   # net/fabricmc/fabric/api/resource/SimpleSynchronousResourceReloadListener
public interface net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener
    extends net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener,
            net.minecraft.server.packs.resources.ResourceManagerReloadListener {}   ← the declaring class, one hop away
```

The class then satisfies nothing, and the JVM is right to say so: a concrete class that does not implement an
interface method is legal at class-load time and fails at the first `invokeinterface`.

### 1.5 The fix: the name table, in the pass that already exists for this family

`net.forbric.kernel.mapping.InheritedMemberDecls` — the declaration half of `InheritedMemberRefs`, called from
`FabricGuestRemapper.remapAll` right after it, with `REMAP_VERSION` → `1.21.1-11-inherited-member-decls` (a
declaration that gained its runtime name changes the class file's method table, so a warm cache must re-derive).
Both use the same `ForbricMappings.mapMemberName` fallback whose javadoc documents this exact case: *"intermediary
names a member once across the whole game … an owner-scoped lookup returns nothing where the name alone is
unambiguous"*. Here the name is unambiguous because the mapping file carries it exactly once, on the class that
declares it:

```
intermediary-1.21.1.jar!mappings/mappings.tiny:20969   m (Laue;)V a method_14491      ← class auf = net/minecraft/class_4013
client-1.21.1.txt:61012                                net.minecraft.server.packs.resources.ResourceManagerReloadListener -> auf:
client-1.21.1.txt:61015                                    void onResourceManagerReload(…ResourceManager) -> a
```

A declaration is renamed only when all of these hold, and the boundary is the reason this cannot reach a mod's own
member: the name is still spelled `method_\d+`; the owner-scoped lookup does not resolve it (a declaration the spine
knows under its own owner is one the engine already renamed); the name alone resolves in the table; the method is
`public` (a game member a class implements is public by Java's own rule); and the class carries no `@Mixin` (inside
a mixin the declared name is the binding name its refmap and annotation strings carry, which `MixinNames` owns). No
owner and no descriptor is touched, so the body, the flags and the call sites stay exactly where they were — which is
what "the behaviour can be preserved" means here: the listener's four reloads and its log line are what already ran;
only the name that made the JVM refuse to reach them changed.

### 1.6 Evidence

`InheritedMemberDeclsTest`, on the real mapping spike (`MC_DIR` staged), 4 tests, 0 failed:

* the red premise is asserted first — the fixture's declaration is exactly `method_14491`, the spelling the remap
  leaves (`premise: the declaration is exactly what the remap leaves when the classpath stops at a Fabric API
  interface`);
* the pass renames it to `onResourceManagerReload`, and a second pass changes nothing (idempotent);
* a `private method_14491` is left alone, a name the spine cannot resolve is left alone, and a `@Mixin` class's
  declaration is left alone — the three boundaries, each pinned.

### 1.7 Cost

One byte scan per guest class, one parse of the classes that still contain `method_`, and one jar rewrite only when
a name changed (1 class in the measured subject). No new classpath, no new engine configuration, no per-jar
variation; the stage is cached per boot.

### 1.8 What this does not cover

* A declaration that satisfies a game member **from inside a `@Mixin` class** without `@Shadow`/`@Overwrite` is left
  alone by this pass (the class-level stand-down above). No such subject was measured; closing it means deciding how
  a mixin's own implementation member relates to its refmap, which is `MixinNames`' question, not this pass's.
* The deeper alternative — putting the guest ecosystem's own jars on the remap classpath so the engine walks the
  whole hierarchy — is the shape `InheritedMemberRefs` already declined for the reference half, and it remains an
  N² change to the stage that still only reaches the hierarchy it is given.

---

## 2. `chipped` — `StackOverflowError` before `Preparing level`, on a self-dependency MinecraftForge ships

### 2.1 The console, verbatim

```
[06:06:42] [main/WARN]: Failed to load datapacks, can't proceed with server load. You can either fix your datapacks or reset to vanilla with --safeMode
java.util.concurrent.ExecutionException: java.lang.StackOverflowError
	at java.base/java.util.concurrent.CompletableFuture.wrapInExecutionException(CompletableFuture.java:345) ~[?:?]
	…
	at forbric/net.minecraft.server.Main.main(Main.java:258) [patched-mc-merged-1.21.1.jar:?]
Caused by: java.lang.StackOverflowError
	at forbric/net.minecraft.resources.ResourceLocation.equals(ResourceLocation.java:150) ~[patched-mc-merged-1.21.1.jar:?]
	at java.base/java.util.HashMap.getNode(HashMap.java:579) ~[?:?]
	at java.base/java.util.HashMap.get(HashMap.java:565) ~[?:?]
	at forbric/com.google.common.collect.AbstractMapBasedMultimap.get(AbstractMapBasedMultimap.java:291) ~[guava-32.1.2-jre.jar:?]
	at forbric/net.minecraft.util.DependencySorter.isCyclic(DependencySorter.java:40) ~[patched-mc-merged-1.21.1.jar:?]
	at forbric/net.minecraft.util.DependencySorter.lambda$isCyclic$1(DependencySorter.java:44) ~[patched-mc-merged-1.21.1.jar:?]
	at java.base/java.util.stream.MatchOps$1MatchSink.accept(MatchOps.java:90) ~[?:?]
	… (the anyMatch pipeline) …
	at forbric/net.minecraft.util.DependencySorter.isCyclic(DependencySorter.java:44) ~[patched-mc-merged-1.21.1.jar:?]
	at forbric/net.minecraft.util.DependencySorter.lambda$isCyclic$1(DependencySorter.java:44) ~[patched-mc-merged-1.21.1.jar:?]
	… the same ten frames to the end of the log …
```

### 2.2 The recursion is by design; the merge did not write it

The frames repeat `DependencySorter.isCyclic` ⇄ `DependencySorter.lambda$isCyclic$1` — the DFS calling itself through
its own lambda. That is vanilla's code, untouched, and the disassembly of both methods is identical in all four jars
of this generation. The class FILES are not the same bytes and are not claimed to be: `server-official.jar` and
`patched-mc-neoforge-1.21.1.jar` carry the identical file (sha256 `11bb8f29…`), while `patched-mc-forge-1.21.1.jar`
and the merged base are recompiles of the same code (sha256 `f8c51d77…` / `a36db36b…`), so only constant-pool
numbering differs — the table quotes the instructions, which are the same:

| jar | `isCyclic(Multimap,Object,Object)` + `lambda$isCyclic$1` |
|---|---|
| `server-official.jar` (vanilla, Mojmap) | `aload_2; Multimap.get; astore_3; contains(from); ifeq 20; iconst_1; ireturn; stream; anyMatch(lambda → isCyclic(mm, from, elem))` |
| `patched-mc-forge-1.21.1.jar` | same, only constant-pool indices differ |
| `patched-mc-neoforge-1.21.1.jar` | same |
| `patched-mc-merged-1.21.1.jar` | same |

So this is **not** a merge artefact and **not** a "self-calling lifted lambda": `isCyclic` is a recursive DFS with no
visited set, which is correct on the acyclic graph it is written for. What the merge changed is the DATA it is handed.

### 2.3 The hole the data walks through

```
private static <K> void addDependencyIfNotCyclic(Multimap<K,K> mm, K from, K to) { if (!isCyclic(mm, from, to)) mm.put(from, to); }
private static <K> boolean isCyclic(Multimap<K,K> mm, K from, K to) {
    Collection<K> collection = mm.get(to);              // ← what `to` already depends on
    if (collection.contains(from)) return true;          // ← line 40: a cycle of length ≥ 2 is found here
    return collection.stream().anyMatch(k -> isCyclic(mm, from, k));   // ← line 44
}
```

The guard is sound for every cycle of length 2 or more and blind to `from == to`: `isCyclic(x, x)` asks whether `x`'s
closure contains `x`, which it does not **yet**, so `x → x` is recorded. Every later query that walks into `x` then
re-enters at that self-edge forever (`HashMap.get` → `AbstractMapBasedMultimap.get` → `ResourceLocation.equals` is
where the deepest frame happened to be when the stack ran out). Since guarded insertion is the only way edges enter
this multimap, a self-edge is the one insertion that can leave it cyclic — which is why the fix is to refuse it.

### 2.4 The self-dependency is real Forge data, and the merged base is where it meets its caller

MinecraftForge's own data pack, in the pristine `forge-1.21.1-52.1.16-universal.jar` (and its `-srg` twin — this is
not the carrier build's doing):

```
data/forge/tags/item/feathers.json      ["minecraft:feather", {"id":"#forge:feathers","required":false}, "minecraft:feather"]
data/forge/tags/item/mushrooms.json     ["minecraft:brown_mushroom", "minecraft:red_mushroom",
                                         {"id":"#forge:mushrooms","required":false}, "minecraft:brown_mushroom", "minecraft:red_mushroom"]
data/forge/tags/item/nether_stars.json  ["minecraft:nether_star", {"id":"#forge:nether_stars","required":false}, "minecraft:nether_star"]
```

NeoForge's convention tags point straight back at them (`neoforge-runtime.jar`):

```
data/c/tags/item/mushrooms.json         ["minecraft:brown_mushroom", "minecraft:red_mushroom", {"id":"#forge:mushrooms","required":false}]
data/c/tags/item/feathers.json          ["minecraft:feather", {"id":"#forge:feathers","required":false}]
data/c/tags/item/nether_stars.json      ["minecraft:nether_star", {"id":"#forge:nether_stars","required":false}]
```

On either native loader only one of those two packs is ever installed, so this combination has never cost anyone a
boot: on NeoForge alone no pack defines a `forge:` tag and the query finds an empty closure; on MinecraftForge alone
nothing declares a `c:` tag that walks into `forge:`. The merged base serves both runtime carriers at once, and the
console says so:

```
[Forbric/DataPacks] served 2 datapack(s) to the server PackRepository — 2 loader carrier(s) (the c: convention tags live only here) …
    : [forbric/carrier/1-forge-runtime-interop, forbric/carrier/2-neoforge-runtime]
```

### 2.5 Why nine subjects boot and `chipped` does not

Both the self-edge and the query that walks into it happen inside the sorter's own walk over its key `HashMap`, so
whether the recursion is reached is a function of that table's capacity — i.e. of how many tags the pack set
declares. The item registry carries 604 tag ids in nine of the ten subjects and 881 in `chipped`'s run (its own 277
item tags on top); `HashMap` resizes from 1024 to 2048 between those, and the two keys change order:

| pack set | item tags | table | bucket `forge:mushrooms` | bucket `c:mushrooms` | walked | outcome |
|---|---:|---:|---:|---:|---|---|
| nine subjects | 604 | 1024 | 607 | 372 | `c:` first | the query runs before the self-edge exists — no crash |
| `chipped` (881) | 881 | 2048 | 607 | 1396 | `forge:` first | the self-edge is recorded, then `c:mushrooms` walks into it — `StackOverflowError` |

That model predicts the observed outcome for all ten subjects exactly (only `chipped`'s run has the
`StackOverflowError`). So **the subject that fails is the one whose tag COUNT changes the table size**; chipped
declares no self-reference of its own, and the data combination that breaks it is the merge's.

### 2.6 The fix

`ForbricMergedBaseCompatTransformer`, new repair `readASelfDependencyAsACycle`: one guard at the head of
`DependencySorter.isCyclic`, `java.util.Objects.equals(from, to)` → jump to the `return true` the method already
has. A dependency an entry declares on itself **is** a cycle, which is the guard's own semantics, and it is the only
insertion that could escape — so the multimap is acyclic by induction and the walk terminates on any pack set,
rather than on the pack sets that happen to order themselves helpfully. Idempotent (a second pass finds its own
`Objects.equals` and stands down), one fixed target, declared REQUIRED in the claim ledger with the cost of its
silence.

Nothing else moves. The multimap feeds the cycle guard and the sort order only, and
`visitDependenciesAndElement` adds a key to its `visited` set before walking that key's edges — so a self-edge was
already a no-op there and the resulting order is unchanged. Tag CONTENTS are built from `TagLoader`'s own lookup,
never from this multimap, so a `required:true` self-reference keeps behaving exactly as it did when the lookup called
it unbuildable.

### 2.7 Evidence

`MergedBaseSelfDependencyTest`, 6 tests, 0 failed, on the real merged base's own `DependencySorter` (defined by a
loader whose parent holds Guava, so the bytes under test are the bytes the game links against) and the real Guava
multimap:

* red, order-free and from the two real private methods: `addDependencyIfNotCyclic(edges, forge:mushrooms, forge:mushrooms)`
  records the self-edge, and `isCyclic(edges, c:mushrooms, forge:mushrooms)` then throws `StackOverflowError`;
* green: the repaired bytes refuse the self-edge (`edges.get(SELF)` is empty) and the same query returns `false`;
* the guard still finds a dependency of length two (`forge:mushrooms → c:mushrooms` makes the reverse query `true`),
  so a repair that answered "cyclic" too eagerly cannot pass;
* the failing operation itself completes on the measured tag shape and yields `[forge:mushrooms, c:mushrooms]` —
  a tag is built after the tags it includes;
* a second pass changes nothing; a class outside the sorter is returned untouched.

### 2.8 Cost

One class-name test per transformed class and, for the one class that matches, a scan of one method's instructions
and 3 new instructions. No new classpath entry, no runtime helper, no per-pack cost.

### 2.9 What this does not cover

* A **genuine** cycle of length ≥ 2 in a mod's tag data is still (correctly) refused by the existing guard, and a
  pathological diamond in the dependency graph is still walked without memoisation — vanilla's shape, unchanged.
* The other data half of this defect class — a pack whose `forge:`/`c:` tags reference a tag no other pack defines —
  is not touched here, because on the merged base the tag IS defined and the reference is what the two carriers
  mean together.
* The claim is a `fixed` anchor on `net/minecraft/util/DependencySorter`, so on a base where that class or its
  `isCyclic` is gone or reshaped the repair stands down and the ledger reports the miss rather than guessing. Only
  the 1.21.1 staged base has been measured; `MergedBaseRepairClaimsStagedTest` reads whichever merged base is staged
  (26.2 in this checkout's default `run/`), so the first 26.2 run should be read for that claim's verdict.

---

## Handoff

Slice for W7Harness: rebuild the kernel from the second commit and re-run **only** the two subjects whose boot-fails
this covers — `chipped` and `betterrailwaysystem` — with a fresh remap cache directory (the `REMAP_VERSION` moved to
`1.21.1-11-inherited-member-decls`, so a warm cache must not be reused). Focused tests:
`net.forbric.kernel.mapping.InheritedMemberDeclsTest` and
`net.forbric.kernel.transform.MergedBaseSelfDependencyTest`. Expected: chipped reaches the world instead of
`StackOverflowError` before `Preparing level`, and betterrailwaysystem reaches the world instead of dying on
`onResourceManagerReload` being abstract, the latter's listener log line `Reloaded BetterRailwaySystem data-driven
definitions` present at least once.

