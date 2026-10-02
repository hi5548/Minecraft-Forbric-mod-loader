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
by the base's own default `reload`. It was neither renamed nor moved nor removed: the class is byte-identical to
vanilla's across the merge (the same `javap` output comes from `server-official.jar`). Neither the merge nor the
carrier has a hand in this one.

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
