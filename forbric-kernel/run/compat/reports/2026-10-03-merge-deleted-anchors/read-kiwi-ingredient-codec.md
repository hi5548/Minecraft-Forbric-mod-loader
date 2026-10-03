# Read 3 — kiwi's two `Ingredient` codec captures (the `lychee$assignCodec` singletons)

Scope: the two singleton `mixin-injector` losses Main named — `kiwi Ingredient_ItemValueMixin#lychee$assignCodec`
and `Ingredient_TagValueMixin#lychee$assignCodec` (`cr=8` on kiwi against the universal 6 at the widest depth).
Classified against the guest's real (remapped) annotation and the two patched bases plus the merged base, then the
re-anchor was landed and re-proved end-to-end through Mixin's own applicator. Read-only against the game; no JVM
beyond the transform.

Guest: `/tmp/w7-remap-cache-final/Kiwi-1.21.1-Fabric-15.8.8-8da520a0f1559288.jar` →
`snownee/kiwi/mixin/codec/Ingredient_{Item,Tag}ValueMixin.class`.
Bases: `patched-mc-forge-1.21.1.jar`, `patched-mc-neoforge-1.21.1.jar`, `patched-mc-merged-1.21.1.jar`
(`p0/mc-1.21.1/.forbric-build/`).

## 1. Correction to the enumeration first

[`complete-enumeration.md`](complete-enumeration.md) lists these as `@ModifyExpressionValue` on
`RecordCodecBuilder.create`. `javap -v` of the remapped guest says **`@ModifyArg`**, with `remap=false`:

```
org.spongepowered.asm.mixin.injection.ModifyArg(
  method=["Lnet/minecraft/world/item/crafting/Ingredient$ItemValue;<clinit>()V"]
  at=@org.spongepowered.asm.mixin.injection.At(
    value="INVOKE"
    remap=false
    target="Lcom/mojang/serialization/codecs/RecordCodecBuilder;create(Ljava/util/function/Function;)Lcom/mojang/serialization/Codec;"))
```

The handler is `private static Function<Instance<ItemValue>, ? extends App<Mu<ItemValue>, ItemValue>>
lychee$assignCodec(Function<…> builder)` — one parameter, no `CallbackInfo`, no sugar, no `@Group`. The `lychee$`
prefix is the mod family's own naming (snownee's Kiwi/Lychee), **not** a delegation: the handler is a plain
`@ModifyArg`. Kind matters here, because it decides which retarget rules can even apply.

## 2. The anchor did not move and the signature did not change — the CALLEE was substituted

`Ingredient$ItemValue.<clinit>` (and `…$TagValue.<clinit>`), same shape in both:

| jar | `<clinit>` |
|---|---|
| `patched-mc-forge-1.21.1.jar` | `RecordCodecBuilder.create(Function)Codec` → `putstatic CODEC` |
| `patched-mc-neoforge-1.21.1.jar` | `RecordCodecBuilder.mapCodec(Function)MapCodec` → `putstatic MAP_CODEC`; `MAP_CODEC.codec()` → `putstatic CODEC` |
| merged (NeoForge won) | NeoForge's, unchanged |

Kiwi anchors the MinecraftForge shape (`create`), so **every Fabric mod compiled against that shape misses here**
and every NeoForge mod already anchors the right call. Not an anchor deletion and not a handler-ABI change: the
handler still receives a `Function` argument; the callee's *return type* is what differs.

**Why `mapCodec` is the same operation, from the library itself.** In datafixerupper 8.0.16 (the version the merged
base runs, `libraries/com/mojang/datafixerupper/8.0.16/`), `create` is *defined as* `mapCodec(...).codec()`:

```
public static <O> Codec<O> create(Function<Instance<O>, ? extends App<Mu<O>, O>> f);
      0: aload_0   1: invokestatic RecordCodecBuilder.instance()
      4: invokeinterface Function.apply   9: checkcast App
     12: invokestatic RecordCodecBuilder.build   15: invokevirtual MapCodec.codec   18: areturn
public static <O> MapCodec<O> mapCodec(Function<…> f);
      0: aload_0   1: invokestatic RecordCodecBuilder.instance()
      4: invokeinterface Function.apply   9: checkcast App
     12: invokestatic RecordCodecBuilder.build   15: areturn
```

Same argument, same `instance()`, same `apply`, same `build`; the only difference is the trailing `.codec()` — which
the merged `<clinit>` performs itself. So the abandoned argument is the very value the handler is written for, and
the merged body computes exactly what the Forge shape computed.

**And kiwi's handler says the same thing.** Its body calls `mapCodec` itself:

```
private static Function<…> lychee$assignCodec(Function<…> builder);
      0: aload_0
      1: invokestatic RecordCodecBuilder.mapCodec(Function)MapCodec
      4: putstatic snownee/kiwi/util/codec/IngredientCodecs.ITEM_VALUE_MAP_CODEC
      7: aload_0
      8: areturn
```

It stashes the MapCodec it builds from that builder function and returns the builder untouched, so the target's own
codec construction proceeds unchanged.

## 3. What the miss costs: not a decorative injection

`Ingredient_ValueMixin` — kiwi's third codec mixin — **attaches** (`@Inject` at `Ingredient$Value.<clinit>` RETURN)
and builds kiwi's value codec from the two captures:

```
IngredientCodecs.VALUE_MAP_CODEC = IngredientCodecs.xor(ITEM_VALUE_MAP_CODEC, TAG_VALUE_MAP_CODEC);
```

`xor` is `new XorMapCodec(first, second)`, and `XorMapCodec.decode` dereferences `this.first` with no null check. So
with both captures lost, kiwi's ingredient dispatch decodes through `new XorMapCodec(null, null)`: `IngredientMixin`
(which also attaches) wires `VALUE_MAP_CODEC` into the `fabric:type` dispatch as the else-branch, and
`SizedIngredient` uses `NON_EMPTY_MAP_CODEC`. [INFERENCE] the first datapack ingredient that reaches that path
fails with an NPE inside `XorMapCodec`, i.e. the loss is a latent crash on the ingredient path every recipe uses,
not a silently absent feature. That is why neither "leave it" nor a stand-down is an acceptable answer for this row:
a stand-down removes the same two handlers and leaves the nulls exactly as they are.

## 4. Why the general retarget machinery cannot take it, and what could

* `MergedBaseCalleeSwaps.Swap` + `MixinRetarget`'s R2 already covers `@ModifyArg` — but only for a swap whose
  **descriptor is identical**, because the kinds R2 moves are shaped by the callee. `create` and `mapCodec` differ
  in their return type, so no `Swap` row can hold this pair.
* `MergedBaseCalleeSwaps.Substitution` + R6 deliberately allows **only `@Inject`** to follow: *"its handler sees
  neither the call's arguments nor its result, only the point"*. Here the handler sees the argument — so following
  it is a claim about the argument, which R6's contract does not make.
* Listing this pair as a `Substitution` row would also be false to that table's premise: NeoForge did not exchange
  one call in an otherwise identical body. The merged `<clinit>` has three extra instructions and a new field
  (`MAP_CODEC`), so the census test `everySubstitutionIsOneCallInAnOtherwiseUnchangedBody` would fail it, correctly.
* What remains is what `MergedBaseCalleeSwaps`' own javadoc says is the only sound form of this argument —
  **per mixin, not per call site** — i.e. a reviewed, name-gated adapter, which is the shape every other
  single-guest anchor repair in the kernel already has.

The general rule that *could* be built from this (an argument-bound injector may follow a substitution whose
argument is carried through, gated by a row that says so) was considered and **not** shipped here: it would need
its own census premise and its own equal-depth A/B, and R6's restriction is a deliberate decision. This row's
evidence is recorded above so that rule can be written in one pass when someone wants it.

## 5. Landed: `KiwiIngredientCodecAnchors`

`net.forbric.kernel.mixin.KiwiIngredientCodecAnchors`, wired in `ForbricMixinService.getClassNode` with the other
per-guest anchor adapters. For the two kiwi mixins only (name gate first — no other mixin reads the merged base),
it rewrites the one `@At.target` from the `create` member to the `mapCodec` member, and **abstains** unless:

1. the handler is `lychee$assignCodec` with the pinned descriptor and exactly one `@ModifyArg`,
2. its one `@At` is `INVOKE` on exactly the `create` member, and
3. the merged `<clinit>` makes `create` **zero** times and `mapCodec` **exactly once** (uniqueness, so the
   argument's producer is unambiguous).

A base where Forge's shape won, a newer kiwi that already anchors `mapCodec`, or a reshaped method leaves the
annotation as compiled — and when the class IS kiwi's but the proof is what failed, it says so at WARN instead of
declining silently. `-Dforbric.kiwiIngredientCodec=off` restores the compiled selector exactly.

**Behaviour change.** Before: kiwi's `ITEM_VALUE_MAP_CODEC`/`TAG_VALUE_MAP_CODEC` stay null; `VALUE_MAP_CODEC` is
`XorMapCodec(null, null)`; both injectors are required CONFIRMED losses and `kiwi` is gated at `cr=8`. After: both
handlers attach, the captures hold the real MapCodecs the merged `<clinit>` builds, and both rows resolve.

## 6. Evidence

Real bytes, the shipped adapter, through Mixin's own applicator (nothing else on the classpath):

```
$ java … repro.Repro4 <merged> <kiwi>            # baseline
=== Ingredient$ItemValue merged modify$zza000$lychee$assignCodec(Function)Function; calls=0 in []
=== Ingredient$TagValue  merged modify$zzb001$lychee$assignCodec(Function)Function; calls=0 in []

$ java … repro.Repro4 <merged> <kiwi> reanchor   # the adapter on
[Forbric/Kiwi] Ingredient_ItemValueMixin's ingredient codec capture now follows
               Ingredient$ItemValue.<clinit> onto RecordCodecBuilder.mapCodec — …
[Forbric/Kiwi] Ingredient_TagValueMixin's ingredient codec capture now follows
               Ingredient$TagValue.<clinit> onto RecordCodecBuilder.mapCodec — …
=== Ingredient$ItemValue merged modify$zza000$lychee$assignCodec(Function)Function; calls=1 in [<clinit>]
=== Ingredient$TagValue  merged modify$zzb001$lychee$assignCodec(Function)Function; calls=1 in [<clinit>]
```

`calls=1 in [<clinit>]` is exactly the criterion `FinalMixinApplications` uses for ATTACHED (a reference to the
merged handler from another method of the defined class), so both rows resolve on the next arm.

Negative control — the same run against `patched-mc-forge-1.21.1.jar`, where the Forge shape survives:
`[Forbric/Kiwi] … does not carry the shape this re-anchor is proven for (1 create, 0 mapCodec call(s)), so … keeps
the selector it compiled` — and the injectors attach anyway, because kiwi's own selector is right there. The
adapter neither fires nor interferes when the guest was compiled for the base in front of it.

Blast radius, measured over the whole remapped corpus (194 guest jars in `/tmp/w7-remap-cache-final`): kiwi is the
only jar in it whose classes name `RecordCodecBuilder` together with `Ingredient$ItemValue`/`$TagValue` — these two
handlers, one mod, one config.

In-tree: `KiwiIngredientCodecAnchorsTest` (6 tests, green) — the proven shape rewrites and is idempotent; a base
that still makes `create` is left alone; both calls or two `mapCodec`s are left alone; an unreadable base is left
alone; the switch restores the compiled selector; another mixin's anchor is answered without reading the base at
all. `net.forbric.kernel.mixin.*`: 516 tests, 191 skipped, 1 failed — the pre-existing `LootSupersessionProofTest`
fixture NPE (staged merged base absent), unrelated.

## 7. Cost

Two annotation rewrites for two mixins, name-gated, once per boot: no other mixin consults the merged base for it.
The adapter parses one class from the merged base per matching mixin (two per boot) and abstains otherwise. The
knob is `-Dforbric.kiwiIngredientCodec=off`.

## 8. Prediction for the next arm

Arm 11 (kernel `4897a130`) already cleared the six universal ids, and its rows put `kiwi` at **`cr=2/req=21`** —
those 2 are exactly these two rows, so on top of arm 11 this change should read **`kiwi 0/19`**, with every other
subject byte-for-byte unchanged (balm `3/23`, cobblemon-auto-battle `1/25`). Measured against the pre-batch row
(`8/31`), the marginal effect is still the same two rows; the `cr 8 → 6` phrasing above is relative to numbers the
batch has since moved, so read this paragraph, not that one.

Two caveats worth carrying with the number: (a) `calls=1` was measured through Mixin's applicator alone, so the
arm is the first place it is checked with the kernel's whole pipeline around it; (b) the fabric side's blocker is now
`registry-load` — an empty `minecraft:painting_variant` killing every subject at world load — so kiwi's rows may go
to 0 while the subject still does not load. If `cr` does not move, the place to look is whether the merged base's
`Ingredient$ItemValue.<clinit>` differs from the one measured here; the adapter's WARN names that condition.
