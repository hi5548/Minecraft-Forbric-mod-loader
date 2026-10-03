/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.mixin;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Vanilla anonymous classes ({@code Outer$N}) whose NAME the merge kept but whose CLASS it did not.
 *
 * <p>javac numbers anonymous classes per outer class in source order. A NeoForge patch that adds an anonymous
 * class before vanilla's renumbers every later one, and the merge keeps one class per name — so
 * {@code ByteBufCodecs$22} is a different codec from vanilla's, whose body now lives at {@code $24}. A mixin
 * targeting {@code ByteBufCodecs$22} applies cleanly, every anchor resolves, and its injections bind to
 * unrelated code. Nothing else can see that.
 *
 * <p>Three buckets, derived from the game jar the merge was built from and the staged merged base by
 * {@code MergedBaseAnonymousDriftTest}. The identity of an anonymous class is its superclass plus its
 * non-{@code <init>} method set, and a patch may ADD methods to it (NeoForge gives {@code MappedRegistry$2}
 * data-map accessors, and {@code ByteBufCodecs$27} two holderset helpers on 1.21.1): {@code $N} still holds
 * vanilla's class while every vanilla method is there. Only a vanilla method that is GONE from {@code $N} says
 * otherwise:
 * <ul>
 *   <li>{@code RELOCATED}: vanilla's method set exists at another {@code $M} of the same outer class — the
 *       candidates are listed, several when the body is duplicated or a patch re-parented one.</li>
 *   <li>{@code RESHAPED}: vanilla's method set exists nowhere in the outer class any more (a real method lost,
 *       or the superclass changed).</li>
 *   <li>{@code CAPTURE_ONLY}: same methods, only the constructor descriptor or the captured {@code val$}
 *       fields differ. Pinned for the record, never flagged — chat_heads targets {@code ChatComponent$1} this way
 *       and its anchors are exactly where vanilla put them.</li>
 * </ul>
 * {@code MixinFit} adds a SOFT unresolved anchor for a {@code @Mixin} target in the first two sets: listed in
 * the reason, PARTIAL at most, never UNFIT — so nothing that works today is dropped, and the adapter names the
 * mod and where vanilla's body went.
 *
 * <h2>One census per base, chosen by shape</h2>
 *
 * <p>The buckets are a census OF A PARTICULAR BASE, and a base has no version in it that a class name can be
 * read from — the numbers differ, the names do not. So the census is selected by asking the base the one
 * question that does separate them (present in 26.2, absent from 1.21.1). Keeping both is the point: the 26.2
 * constants were derived from 26.2 and are still the answer for a 26.2 base; applying the 1.21.1 census to 26.2
 * (or the reverse, which is the defect this replaced) would move a mixin by a number that means something else.
 */
public final class MergedBaseAnonymousDrift {
	/**
	 * One base's anonymous-class census.
	 *
	 * @param relocated   vanilla {@code $N} whose method set lives at another {@code $M} of the same outer
	 * @param reshaped    vanilla {@code $N} whose method set exists nowhere in the outer any more
	 * @param captureOnly same methods, different constructor or {@code val$} captures — never flagged
	 */
	public record Census(Map<String, List<String>> relocated, Set<String> reshaped, Set<String> captureOnly) {
		/** Whether {@code internalName} is a renumbered or reshaped anonymous class. */
		public boolean drifted(String internalName) {
			return relocated.containsKey(internalName) || reshaped.contains(internalName);
		}

		/** One sentence saying what {@code internalName} is on this base, for the mixin log. */
		public String describe(String internalName) {
			List<String> candidates = relocated.get(internalName);
			if (candidates != null) return "vanilla's body now lives at " + String.join(" or ", candidates);
			return reshaped.contains(internalName) ? "the class was reshaped by the merge" : "";
		}
	}

	/**
	 * The census of the 26.2 base. Kept verbatim: it is what a 26.2 build must be judged against, and its
	 * candidate rule (exact method-set equality) is what produced it. Several entries name more than one home
	 * there, which is why {@link MixinAnonymousRetarget} declines them on 26.2.
	 */
	public static final Census VANILLA_26_2 = new Census(
			Map.ofEntries(
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$12", List.of("net/minecraft/network/codec/ByteBufCodecs$11", "net/minecraft/network/codec/ByteBufCodecs$19")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$13", List.of("net/minecraft/network/codec/ByteBufCodecs$12")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$14", List.of("net/minecraft/network/codec/ByteBufCodecs$20")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$15", List.of("net/minecraft/network/codec/ByteBufCodecs$13", "net/minecraft/network/codec/ByteBufCodecs$21", "net/minecraft/network/codec/ByteBufCodecs$25")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$16", List.of("net/minecraft/network/codec/ByteBufCodecs$22")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$17", List.of("net/minecraft/network/codec/ByteBufCodecs$23")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$18", List.of("net/minecraft/network/codec/ByteBufCodecs$24")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$19", List.of("net/minecraft/network/codec/ByteBufCodecs$13", "net/minecraft/network/codec/ByteBufCodecs$21", "net/minecraft/network/codec/ByteBufCodecs$25")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$20", List.of("net/minecraft/network/codec/ByteBufCodecs$14")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$21", List.of("net/minecraft/network/codec/ByteBufCodecs$15")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$22", List.of("net/minecraft/network/codec/ByteBufCodecs$16", "net/minecraft/network/codec/ByteBufCodecs$18", "net/minecraft/network/codec/ByteBufCodecs$4", "net/minecraft/network/codec/ByteBufCodecs$5", "net/minecraft/network/codec/ByteBufCodecs$6")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$23", List.of("net/minecraft/network/codec/ByteBufCodecs$13", "net/minecraft/network/codec/ByteBufCodecs$21", "net/minecraft/network/codec/ByteBufCodecs$25")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$24", List.of("net/minecraft/network/codec/ByteBufCodecs$26")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$25", List.of("net/minecraft/network/codec/ByteBufCodecs$27")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$26", List.of("net/minecraft/network/codec/ByteBufCodecs$28")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$27", List.of("net/minecraft/network/codec/ByteBufCodecs$29", "net/minecraft/network/codec/ByteBufCodecs$30")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$28", List.of("net/minecraft/network/codec/ByteBufCodecs$29", "net/minecraft/network/codec/ByteBufCodecs$30")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$29", List.of("net/minecraft/network/codec/ByteBufCodecs$31")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$30", List.of("net/minecraft/network/codec/ByteBufCodecs$32")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$32", List.of("net/minecraft/network/codec/ByteBufCodecs$17")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$33", List.of("net/minecraft/network/codec/ByteBufCodecs$16", "net/minecraft/network/codec/ByteBufCodecs$18", "net/minecraft/network/codec/ByteBufCodecs$4", "net/minecraft/network/codec/ByteBufCodecs$5", "net/minecraft/network/codec/ByteBufCodecs$6")),
			Map.entry("net/minecraft/server/commands/FunctionCommand$1", List.of("net/minecraft/server/commands/FunctionCommand$2", "net/minecraft/server/commands/FunctionCommand$3", "net/minecraft/server/commands/FunctionCommand$4", "net/minecraft/server/commands/FunctionCommand$5")),
			Map.entry("net/minecraft/server/commands/FunctionCommand$5", List.of("net/minecraft/server/commands/FunctionCommand$1")),
			Map.entry("net/minecraft/util/BoundedFloatFunction$2", List.of("net/minecraft/util/BoundedFloatFunction$1"))),
			Set.of(
			"net/minecraft/data/recipes/RecipeProvider$Runner$1",
			"net/minecraft/network/codec/ByteBufCodecs$31"),
			Set.of(
			"net/minecraft/client/gui/components/ChatComponent$1",
			"net/minecraft/data/tags/TagAppender$1",
			"net/minecraft/locale/Language$1",
			"net/minecraft/network/codec/ByteBufCodecs$11",
			"net/minecraft/resources/RegistryDataLoader$1",
			"net/minecraft/server/commands/FunctionCommand$3",
			"net/minecraft/server/network/ServerLoginPacketListenerImpl$1",
			"net/minecraft/util/BoundedFloatFunction$1",
			"net/minecraft/world/item/Item$TooltipContext$2",
			"net/minecraft/world/item/ItemStack$1",
			"net/minecraft/world/item/ItemStack$2",
			"net/minecraft/world/level/block/entity/SpawnerBlockEntity$1"));

	public static final Census VANILLA_1_21_1 = new Census(
			Map.ofEntries(
			Map.entry("net/minecraft/Util$7",
			List.of("net/minecraft/Util$3", "net/minecraft/Util$4", "net/minecraft/Util$5", "net/minecraft/Util$6")),
			Map.entry("net/minecraft/Util$8",
			List.of("net/minecraft/Util$3", "net/minecraft/Util$4", "net/minecraft/Util$5", "net/minecraft/Util$6")),
			Map.entry("net/minecraft/Util$9", List.of("net/minecraft/Util$7")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$11",
			List.of("net/minecraft/network/codec/ByteBufCodecs$10", "net/minecraft/network/codec/ByteBufCodecs$16")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$12", List.of("net/minecraft/network/codec/ByteBufCodecs$17")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$13", List.of("net/minecraft/network/codec/ByteBufCodecs$18")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$14", List.of("net/minecraft/network/codec/ByteBufCodecs$19")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$15",
			List.of("net/minecraft/network/codec/ByteBufCodecs$11", "net/minecraft/network/codec/ByteBufCodecs$20")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$16", List.of("net/minecraft/network/codec/ByteBufCodecs$12")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$17", List.of("net/minecraft/network/codec/ByteBufCodecs$13")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$18",
			List.of("net/minecraft/network/codec/ByteBufCodecs$11", "net/minecraft/network/codec/ByteBufCodecs$20")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$19", List.of("net/minecraft/network/codec/ByteBufCodecs$21")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$20", List.of("net/minecraft/network/codec/ByteBufCodecs$22")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$21", List.of("net/minecraft/network/codec/ByteBufCodecs$23")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$22", List.of("net/minecraft/network/codec/ByteBufCodecs$24")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$23", List.of("net/minecraft/network/codec/ByteBufCodecs$25")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$24", List.of("net/minecraft/network/codec/ByteBufCodecs$26")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$25", List.of("net/minecraft/network/codec/ByteBufCodecs$27")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$26", List.of("net/minecraft/network/codec/ByteBufCodecs$14")),
			Map.entry("net/minecraft/network/codec/ByteBufCodecs$27", List.of("net/minecraft/network/codec/ByteBufCodecs$15"))),
			Set.of(
			"net/minecraft/data/recipes/RecipeProvider$1",
			"net/minecraft/server/network/ServerGamePacketListenerImpl$1"),
			Set.of(
			"net/minecraft/Util$5",
			"net/minecraft/Util$6",
			"net/minecraft/client/Options$2",
			"net/minecraft/client/Options$3",
			"net/minecraft/client/Options$4",
			"net/minecraft/client/gui/screens/ConnectScreen$1",
			"net/minecraft/client/main/Main$1",
			"net/minecraft/client/multiplayer/ServerStatusPinger$1",
			"net/minecraft/client/multiplayer/ServerStatusPinger$2",
			"net/minecraft/client/multiplayer/resolver/AddressCheck$1",
			"net/minecraft/client/renderer/ShaderInstance$1",
			"net/minecraft/client/renderer/texture/TextureAtlasSprite$1",
			"net/minecraft/commands/Commands$1",
			"net/minecraft/commands/Commands$1$1",
			"net/minecraft/core/HolderLookup$RegistryLookup$1",
			"net/minecraft/core/RegistrySetBuilder$1",
			"net/minecraft/core/RegistrySetBuilder$2",
			"net/minecraft/core/RegistrySetBuilder$3",
			"net/minecraft/core/component/DataComponentMap$2",
			"net/minecraft/core/component/DataComponentMap$3",
			"net/minecraft/data/HashCache$1",
			"net/minecraft/locale/Language$1",
			"net/minecraft/network/Connection$1",
			"net/minecraft/network/chat/ComponentSerialization$1",
			"net/minecraft/network/codec/ByteBufCodecs$10",
			"net/minecraft/network/protocol/BundlerInfo$1",
			"net/minecraft/network/protocol/common/custom/CustomPacketPayload$1",
			"net/minecraft/resources/RegistryDataLoader$1",
			"net/minecraft/server/Bootstrap$1",
			"net/minecraft/server/Main$1",
			"net/minecraft/server/MinecraftServer$1",
			"net/minecraft/server/MinecraftServer$TimeProfiler$1",
			"net/minecraft/server/ReloadableServerResources$ConfigurableRegistryLookup$1",
			"net/minecraft/server/gui/MinecraftServerGui$1",
			"net/minecraft/server/network/ServerLoginPacketListenerImpl$1",
			"net/minecraft/server/packs/repository/BuiltInPackSource$1",
			"net/minecraft/tags/TagLoader$1",
			"net/minecraft/world/entity/EntityType$1",
			"net/minecraft/world/entity/ai/Brain$1",
			"net/minecraft/world/entity/player/Player$2",
			"net/minecraft/world/entity/vehicle/ContainerEntity$1",
			"net/minecraft/world/inventory/GrindstoneMenu$4",
			"net/minecraft/world/item/Item$TooltipContext$2",
			"net/minecraft/world/item/Item$TooltipContext$3",
			"net/minecraft/world/item/ItemStack$3",
			"net/minecraft/world/item/crafting/RecipeManager$1",
			"net/minecraft/world/item/crafting/RecipeType$1",
			"net/minecraft/world/level/Level$1",
			"net/minecraft/world/level/block/ChestBlock$2$1",
			"net/minecraft/world/level/block/ChestBlock$3",
			"net/minecraft/world/level/block/entity/BlockEntity$1",
			"net/minecraft/world/level/block/entity/SpawnerBlockEntity$1",
			"net/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess$1",
			"net/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess$2",
			"net/minecraft/world/level/storage/loot/providers/nbt/ContextNbtProvider$2"));
	/**
	 * The class that tells the two censuses apart: 26.2 has an anonymous {@code ByteBufCodecs} past {@code $28}
	 * and 1.21.1's stop there. One presence test on a class the census itself names, so the two stay in step.
	 */
	public static final String ONLY_26_2 = "net/minecraft/network/codec/ByteBufCodecs$33";

	/** Both censuses, so a name read back OUT of a verdict reason can be attributed without guessing the base. */
	static final List<Census> CENSUSES = List.of(VANILLA_26_2, VANILLA_1_21_1);

	private MergedBaseAnonymousDrift() {
	}

	/**
	 * The census for the base {@code present} answers for — the caller's own resolver, so the choice is made
	 * against the bytes the mixin will be judged on and cannot be made from a version constant that has drifted.
	 */
	public static Census forBase(Predicate<String> present) {
		return present.test(ONLY_26_2) ? VANILLA_26_2 : VANILLA_1_21_1;
	}

	/**
	 * What the census that knows {@code internalName} says about it, or {@code ""}. For a name read back out of a
	 * verdict reason, where which base produced the reason is no longer at hand.
	 */
	public static String describeAny(String internalName) {
		for (Census census : CENSUSES) if (census.drifted(internalName)) return census.describe(internalName);
		return "";
	}
}
