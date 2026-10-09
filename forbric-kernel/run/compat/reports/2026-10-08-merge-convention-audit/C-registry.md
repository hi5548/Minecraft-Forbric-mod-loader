# Slice C — registry keys & sync hooks (read-only census)

Scope: the merged base's registries (`register`-vs-`get` key shapes) and every class the merge took
from the *wrong* ecosystem among the registry-sync / registry-hook surfaces. Bytes only; no builds,
no client runs.

## 0. Method and base

Three staged jars under `/Applications/.minecraft/.forbric-build/out/` (the installed
`libraries/net/forbric/patched-mc-merged/1.21.1/*.jar` is byte-identical, checked):

```
46af05299bafd3140321b25246138def88dd4f938b9ae9451f976b57bd1c587f  patched-mc-merged-1.21.1.jar
8663eba8568acd6d18a66430e8abfc11c5c93f48969b76299a92bb36eeb42533  patched-mc-forge-1.21.1.jar
0e1a2e52e489b0b9000c84e1f30f70204ac9c494ea38d80cc9628838ef0a9032  patched-mc-neoforge-1.21.1.jar
```

Per-class byte compare over all 8k classes: `forge != neo` for **8268** classes; of those merged kept
**forge** in **117**, **neo** in **6992**, and spliced both in **1159**. Constant-pool scan for
`net/minecraftforge/registries/` hit **14** merged classes (complete list below); `net/neoforged/`
hit 2878. Every finding below is a `javap -p -c` reading of those exact class files.

Class sizes (bytes) are the evidence of *which* side won (merged == one side ⇒ that side's body):

| class | forge | neo | merged | winner |
|---|---:|---:|---:|---|
| `core.RegistrySynchronization` | 11653 | 11544 | **11653** | **FORGE** |
| `core.RegistrySynchronization$PackedRegistryEntry` | 4226 | 4234 | **4226** | **FORGE** |
| `core.RegistrySetBuilder$UniversalLookup` | 3128 | 4174 | **4174** | NEO |
| `client.color.block.BlockColors` | 11002 | 10575 | 10854 | spliced |
| `client.color.item.ItemColors` | 8865 | 8442 | 8709 | spliced |
| `client.renderer.ItemBlockRenderTypes` | 23573 | 22436 | 23726 | spliced |
| `core.MappedRegistry` | 25340 | 29397 | 31094 | spliced |
| `core.registries.BuiltInRegistries` | 39838 | 39716 | 39954 | spliced |
| `resources.RegistryDataLoader` | 31691 | 29586 | 31927 | spliced |
| `resources.HolderSetCodec` | 16747 | 16212 | 18860 | spliced |
| `world.item.ItemDisplayContext` | 7365 | 6428 | 9294 | spliced |
| `data.tags.TagsProvider` | 19852 | 19266 | 24194 | spliced |
| `world.entity.player.Inventory` | 19349 | 19440 | **19349** | **FORGE** |
| `world.entity.npc.Villager` | 51432 | 52338 | 54498 | spliced |
| `world.item.BucketItem` | 16291 | 14927 | 21475 | spliced |
| `world.level.block.Block` | 47420 | 42669 | 57641 | spliced |
| `world.level.block.FlowerPotBlock` | 13764 | 13747 | 23776 | spliced |

---

## A. `register`-vs-`get` key shapes

The merged `Registry` interface itself is shape-consistent — it offers `register(…, ResourceLocation, T)`,
`register(…, ResourceKey<V>, T)`, `get(ResourceLocation)`, `get(ResourceKey)`, `getHolder(int|ResourceLocation|ResourceKey)`
(identical in all three jars). Every mismatch is a *caller* that keys a map by one shape and reads it by the other.

### A1 — `BlockColors.getColor` keys by Forge `Holder`, `register` stores raw `Block` — REPAIRED
`net/minecraft/client/color/block/BlockColors` (spliced, 10854). Field `blockColors` is an `IdentityHashMap`
(`<init>` `new IdentityHashMap` #33). `getColor` (`javap -c`, offsets 1–11):
```
1: getfield  #36   // Field blockColors:Ljava/util/Map;
4: getstatic #240  // Field net/minecraftforge/registries/ForgeRegistries.BLOCKS:IForgeRegistry
8: invokevirtual #246 // BlockState.getBlock:()Lnet/minecraft/world/level/block/Block;
11: invokeinterface #252 // IForgeRegistry.getDelegateOrThrow(Object):Holder$Reference
```
`register(BlockColor, Block…)` stores raw `Block`. A `Holder$Reference` never equals a raw `Block` under an
identity map, so every lookup missed and fell back to `MapColor`. **Player-visible** (grass/water/leaves/lava lose
tint). **Repaired** — `ForbricMergedBaseCompatTransformer.restoreTheRawColourKeysAndRebindTheColourMixins` →
`stripTheForgeRegistryKey(…,"blockColors",2,BlockState,"getBlock")` (transformer line 1741), plus the fabric
`BlockColorsMixin` rebind. Owner: `ForbricMergedBaseCompatTransformer` (colour repair). Confidence: high (bytes).

### A2 — `ItemColors.getColor` — same shape — REPAIRED
`net/minecraft/client/color/item/ItemColors` (spliced, 8709). `getColor(ItemStack,int)` offsets 4–11:
`getstatic ForgeRegistries.ITEMS:IForgeRegistry` → `getDelegateOrThrow(Item)`. `register(...)` stores raw `Item`.
Repaired by the same method (`"itemColors",1,ItemStack,"getItem"`, line 1742). Player-visible, high.

### A3 — `ItemBlockRenderTypes.BLOCK_RENDER_TYPES` — Forge overload writes a Holder key, Neo reader reads a raw Block — **NOT REPAIRED**
`net/minecraft/client/renderer/ItemBlockRenderTypes` (spliced, 23726). Field (declared key shape):
```
private static final java.util.Map<net.minecraft.world.level.block.Block, net.neoforged.neoforge.client.ChunkRenderTypeSet> BLOCK_RENDER_TYPES;
```
NeoForge writer — raw key (offsets 3–8): `getstatic BLOCK_RENDER_TYPES; aload_0; aload_1; Map.put`.
Forge writer — **Holder key** (`setRenderLayer(Block, net.minecraftforge.client.ChunkRenderTypeSet)`, offsets 3–21):
```
3: getstatic  #168  // Field BLOCK_RENDER_TYPES:Map
6: getstatic  #1308 // Field net/minecraftforge/registries/ForgeRegistries.BLOCKS:IForgeRegistry
10: invokeinterface #151 // IForgeRegistry.getDelegateOrThrow(Object):Holder$Reference
16: invokeinterface #207 // Map.put(Object,Object)
```
Reader — raw key (`getRenderLayers(BlockState)`, offsets 28–40):
```
28: getstatic #168 // Field BLOCK_RENDER_TYPES:Map
31: aload_1        // the raw Block from BlockState.getBlock()
32: invokeinterface #69 // Map.get(Object):Object
```
`Holder.Reference` does not equal the raw `Block`, so **any render layer a Forge mod registers through Forge's
`setRenderLayer(Block, ChunkRenderTypeSet)` is never read back** — the block renders with the default layer.
Not in the kernel's repair list (`grep BLOCK_RENDER_TYPES src/main` = none; `REPAIRS` in the transformer has no
render-layer-block entry). **Player-visible** (opaque instead of cutout/translucent). Confidence: **high on the byte
mismatch** (medium that a corpus Forge mod exercises that overload). Owner lane: `ForbricMergedBaseCompatTransformer`
— the same raw-key repair as the colour maps.

### A4 — `ItemBlockRenderTypes.FLUID_RENDER_TYPES` left null — REPAIRED
Field `Map<Holder$Reference<Fluid>, RenderType>`; Forge's `<clinit>` initialiser was lost (Neo's `<clinit>` won),
readers/writer/filler survived → `getRenderLayer` NPE. Both writer `setRenderLayer(Fluid,…)` (offsets 20–33) and
reader `getRenderLayer(FluidState)` (offsets 0–10) use `ForgeRegistries.FLUIDS.getDelegateOrThrow(...)`, i.e. a
*consistent* Holder shape — no mismatch here, only the unwritten static. Repaired by
`ItemBlockRenderTypesFluidMapRepair` (registered `KernelBoot:609`). Player-visible (crash), high.

### A5 — `MappedRegistry.KNOWN` unwritten — REPAIRED (registry-adjacent)
`net/minecraft/core/MappedRegistry` (spliced, 31094) kept Forge's `private static final Set<ResourceLocation> KNOWN`
plus `getKnownRegistries()` / `markKnown()` (`getstatic KNOWN` at two sites) but its `<clinit>` is Neo's
(`LOGGER` only) — no `putstatic KNOWN`, so the field is null. Repaired by
`ForbricMergedBaseCompatTransformer.addMissingForgeKnownRegistriesInitializer` (claim at line 122:
"the first registry registration NPEs … the server never reaches the main menu"). Player-visible (boot), high.

### A6 — shapes that agree (checked, no finding)
- `BucketItem.<init>`: `fluidSupplier = ForgeRegistries.FLUIDS.getDelegateOrThrow(fluid)` (a `Supplier` field, no map).
- `FlowerPotBlock.useItemOn` offsets 21–57: `fullPots.getOrDefault(ForgeRegistries.BLOCKS.getKey(block), ForgeRegistries.BLOCKS.getDelegateOrThrow(Blocks.AIR))` — map declared `Map<ResourceLocation,Supplier<Block>>`, **key shape `ResourceLocation` on both sides**; the default is a `Holder.Reference` used as a `Supplier`.
- `Block.asItem()` offsets 15–33: `ForgeRegistries.ITEMS.getDelegateOrThrow(this.item).get()` (no map).
- `Villager` offsets 0–10 and `Inventory.lambda$add$0` offsets 0–12: `…IForgeRegistry.getKey(obj) : ResourceLocation` — same shape as vanilla.

---

## B. Registry-sync / registry-hook classes the merge took from the wrong ecosystem

### B1 — `net.minecraft.core.RegistrySynchronization` is MinecraftForge's, whole — LIVE
merged `11653` bytes == forge `11653`; NeoForge's `11544` lost. `<clinit>`:
```
0: invokedynamic #387 // InvokeDynamic #9:get:()Supplier
5: invokestatic  #393 // Method net/minecraftforge/registries/DataPackRegistriesHooks.grabNetworkableRegistries:(Supplier)Set
8: putstatic     #183 // Field NETWORKABLE_REGISTRIES:Set
```
NeoForge's `<clinit>` (discarded) derives the same set directly from the loader:
`RegistryDataLoader.SYNCHRONIZED_REGISTRIES.stream().map(key).collect(toUnmodifiableSet())`. The merge also left
Forge's lambda numbering (`$0…$5`, incl. `lambda$static$0()`), while Neo's is `$0…$4` — the same offset the kernel's
`LambdaSelectorRetarget` documents for `fabric-registry-sync`'s `$4`.

The two hook classes on the classpath are **not interchangeable**:
```
Forge  net/minecraftforge/registries/DataPackRegistriesHooks
   grabNetworkableRegistries(Supplier<Set<ResourceKey>>): Set      // StackWalker guard: caller must be net.minecraft.core.RegistrySynchronization
Neo    net/neoforged/neoforge/registries/DataPackRegistriesHooks
   grabNetworkableRegistries(List<RegistryData<?>>): List          // guard: caller must be RegistryDataLoader
```
`RegistrySynchronization$PackedRegistryEntry` is likewise Forge's (4226 vs 4234). Both runtimes are on the launch
line (`1.21.1-forbric.json` `--runtimeJar forge-runtime.jar:neoforge-runtime.jar`), so the Forge call *resolves* —
but the sync set is now assembled through **Forge's** hook while the base's own `RegistryDataLoader.<clinit>` (B2)
calls **NeoForge's**. Nothing repairs it (`grep NETWORKABLE_REGISTRIES/RegistrySynchronization src/main` → only the
lambda-renumber comment at `KernelBoot:889`). **Player-visible** (which dynamic registries a server announces /
clients receive); the delta is a cross-ecosystem set difference, not a crash. Confidence: **high** that the merge
kept the wrong class; **medium** on today's functional size of the delta. Owner lane: kernel registry-sync
transforms (`RegistrySyncParityInjector` / `DatapackRegistryDeclaration`), or fix the merge to keep Neo's class.

### B2 — `net.minecraft.resources.RegistryDataLoader` — Forge driver + Neo loader — REPAIRED
`spliced 31927`. `loadContentsFromNetwork` (offsets 38–54) is Forge's: `getstatic
net/minecraftforge/common/crafting/conditions/ICondition$IContext.KEY` → `ConditionCodec.wrap(Decoder)`;
`loadElementFromResource` is Neo's: `NeoForgeExtraCodecs.decodeOnly` + `ConditionalOps.createConditionalCodec`
(offsets 1–4); `<clinit>` offset 675 calls **NeoForge's** `DataPackRegistriesHooks.grabNetworkableRegistries(List)`.
The double wrap/unwrap is the documented client disconnect; repaired by `RegistryNetworkSyncDecoderRepair`
(`KernelBoot:642`). Player-visible (client level never created) — repaired, high.

### B3 — `net.minecraft.core.registries.BuiltInRegistries` → Forge `GameData.getWrapper` — REPAIRED
`spliced 39954`; `internalRegister` offset 16: `invokestatic net/minecraftforge/registries/GameData.getWrapper(ResourceKey,WritableRegistry)WritableRegistry`.
Neutered by `RegistryHookRedirector` (`KernelBoot:956`), so builtin registries stay plain `MappedRegistry`.
Player-visible (world-join "Tags not bound") — repaired, high.

### B4 — `RegistrySetBuilder` — NeoForge's registry-set machinery kept — expected
`RegistrySetBuilder$UniversalLookup` merged `4174` == neo `4174` (Forge's `3128` lost); `$BuildState`,
`$EmptyTagLookupWrapper` likewise Neo/then spliced. The one Forge method here, `RegistrySetBuilder#wrapContextLookup`,
is listed FORGE-uncalled in `uncalled-methods.txt:91`. No defect.

### B5 — `net.minecraft.world.item.ItemDisplayContext` — Forge `DISPLAY_CONTEXTS` path present but dead
`spliced 9294`. The class implements NeoForge's `IExtensibleEnum` and declares NeoForge's
`getExtensionInfo()`/`create(ResourceLocation,…)`, **and** keeps Forge's `lambda$static$1(int)` (reads
`ForgeRegistries.DISPLAY_CONTEXTS` via `IForgeRegistryInternal.getValue`) and `lambda$static$2(…IForgeRegistryInternal,
RegistryManager,…)` (Forge's add-callback), plus the field
`public static final IForgeRegistry$AddCallback<ItemDisplayContext> ADD_CALLBACK;` which **no `putstatic` assigns**
(null). `uncalled-methods.txt:168-169` lists both Forge lambdas as FORGE-uncalled in the merged game, and the kernel
drives the NeoForge path for every mod via `NeoEnumExtensions`/`RuntimeEnumExtender`. **Player-visible** only for a
Forge mod that registers through the Forge `DISPLAY_CONTEXTS` registry — likely none in the corpus. Confidence:
high on the bytes, low on live impact. Owner: `NeoEnumExtensions` / `ForbricMergedBaseCompatTransformer`.

### B6 — `net.minecraft.resources.HolderSetCodec` — both holder-set-type registries in one class
`spliced 18860`. `<init>` (offsets 38–65) builds `forgeDispatchCodec` from
`getstatic net/neoforged/neoforge/registries/NeoForgeRegistries.HOLDER_SET_TYPES` `byNameCodec()` `dispatch(...)`,
while the class still carries Forge's `lambda$new$4(…net.minecraftforge.registries.holdersets.HolderSetType)` →
`holdersets/HolderSetType.makeCodec`, a no-arg `lambda$new$3()` returning
`ForgeRegistries.HOLDER_SET_TYPES.getCodec()`, and `lambda$new$3(…neo…holdersets.HolderSetType)` (Neo's). The merge
report records the decision verbatim:
```
merge-conflicts.txt:453  net/minecraft/resources/HolderSetCodec#<init>(…)V (forge hook lost)
merge-conflicts.txt:454  net/minecraft/resources/HolderSetCodec#encode(…) (forge hook lost)
merge-conflicts.txt:1454 net/minecraft/resources/HolderSetCodec#<init>(…) DECLINED constructor or class initializer
merge-conflicts.txt:1455 net/minecraft/resources/HolderSetCodec#encode(…) DECLINED both sides must add an entry prefix
```
`uncalled-methods.txt:126-127` marks the Forge holder-set lambdas dead, so today a custom Forge holder-set type
does not decode on the merged base (NeoForge's registry is the live one). **Player-visible** for a Forge mod that
ships a custom holder-set type. Confidence: high on the bytes, medium on live impact. Owner:
`ForbricMergedBaseCompatTransformer` or the merge.

### B7 — `net.minecraft.data.tags.TagsProvider` — Forge `RegistryManager` in an error lambda
`spliced 24194`; `lambda$run$3()` (offsets 0–35) reads
`net/minecraftforge/registries/RegistryManager.ACTIVE.getRegistry(ResourceKey)`. Key shape is `ResourceKey` — the
message path only, no registry read. `uncalled-methods.txt:113-116` marks `lambda$run$3..6` FORGE-uncalled.
Non-functional. Confidence: high, impact negligible.

### B8 — other Forge-registry references, no shape defect
`Villager`, `Inventory` (FORGE-won body: `lambda$add$0` uses `ForgeRegistries.ITEMS.getKey`), `BucketItem`,
`Block`, `FlowerPotBlock`, `Holder` (`tags/IReverseTag`) reference Forge registries but use shapes that agree (A6),
or their Forge methods are in `uncalled-methods.txt`. `Inventory` is byte-identical to Forge's while NeoForge's
differs (`19349` vs `19440`) but the differing code is the `add()` error-message lambda — no player-visible effect.

---

## C. Owner lanes (per finding)

| # | finding | repaired? | owner lane |
|---|---|---|---|
| A1/A2 | colour maps raw-vs-Holder | yes | `ForbricMergedBaseCompatTransformer` (colour repair) |
| A3 | `ItemBlockRenderTypes.BLOCK_RENDER_TYPES` Forge Holder key | **no** | `ForbricMergedBaseCompatTransformer` / new render-layer repair |
| A4 | `FLUID_RENDER_TYPES` null static | yes | `ItemBlockRenderTypesFluidMapRepair` |
| A5 | `MappedRegistry.KNOWN` null static | yes | `addMissingForgeKnownRegistriesInitializer` |
| B1 | `RegistrySynchronization` is Forge's | **no** | kernel registry-sync (`RegistrySyncParityInjector` / merge) |
| B2 | `RegistryDataLoader` Forge driver | yes | `RegistryNetworkSyncDecoderRepair` |
| B3 | `BuiltInRegistries` → `GameData.getWrapper` | yes | `RegistryHookRedirector` |
| B5 | `ItemDisplayContext` Forge display-context path | dead | `NeoEnumExtensions` |
| B6 | `HolderSetCodec` Forge holder-set types | dead | `ForbricMergedBaseCompatTransformer` / merge |

Net: among the registry-sync / registry-hook surfaces the merge actually *chose* Forge's body for, only
`RegistrySynchronization` (+ its `PackedRegistryEntry`) is live and unrepaired; and the one live unrepaired
key-shape mismatch is `ItemBlockRenderTypes.BLOCK_RENDER_TYPES`. Everything else the merge mis-picked is either
already repaired or listed as dead in `uncalled-methods.txt`.
