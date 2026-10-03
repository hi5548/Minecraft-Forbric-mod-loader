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

package net.forbric.kernel.transform;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Removes named injector methods from a GUEST MIXIN class before Mixin reads it, so that a mixin whose other
 * injectors fit the merged base can apply instead of being pinned whole.
 *
 * <p>Three entries. balm's {@code FabricCropBlockMixin} (the two {@code getGrowthSpeed*} handlers, which anchor on
 * a {@code BlockState.is(Block)} call the merged {@code getGrowthSpeed(BlockState, BlockGetter, BlockPos)} does not
 * make — one unbound REQUIRED injector aborted the whole mixin, taking its two fitting {@code randomTick} handlers
 * with it), fabric-object-builder-api-v1's trade-offer factory ({@code disableVanillaCheck}, whose target exists
 * nowhere on the merged base, while its {@code create} sibling binds), and fabric-model-loading-api-v1's
 * {@code ModelManagerMixin}. Each entry's annotation quotes its module's own spelling where one still applies; the
 * {@code selectorPrefix} is the selector THIS transformer will find, i.e. the one the name layer leaves behind.
 *
 * <p>NeoForge won the byte-merge
 * of {@code ModelManager.lambda$loadBlockModels$2} and replaced vanilla's {@code CuboidModel.fromStream(Reader)}
 * there with its own {@code UnbakedModelParser.parse(Reader)} — the dispatch point for NeoForge {@code "loader"}
 * model formats. Fabric's {@code @Redirect cancelVanillaDeserialize} targets {@code fromStream}, so it cannot
 * bind; its sibling {@code @ModifyArg actuallyDeserializeModel} at {@code Pair.of} DOES bind and hands an
 * already-consumed {@code Reader} to Fabric's deserializer registry. Every one of the 4666 block models then dies
 * on {@code JsonParseException: JSON data was null or empty} and the whole world renders as the missingno
 * checkerboard — the measured PARTIAL that made {@link net.forbric.kernel.mixin.MergedBaseMixinCompat} pin the
 * mixin. Pinning it cost every {@code ModelLoadingPlugin}: block-state resolvers, extra models and per-model
 * modifiers registered by Fabric mods were never called.
 *
 * <p>The two deserializer injectors are the ONLY ones that cannot fit. The other eight — plugin preparation at
 * reload HEAD, the on-load model and block-state modifiers, the thread-local dispatcher around collect and bake,
 * extra-model resolution and the post-upload capture — anchor on instructions the merged {@code ModelManager}
 * still has. Removing the pair from the mixin's bytes lets Mixin apply the rest as written, while NeoForge's
 * parser keeps the call site, so NeoForge {@code "loader"} models keep working too. What the pair did —
 * dispatch Fabric's {@code fabric:type} custom model formats ({@code UnbakedModelDeserializer}) — is done by
 * {@link ModelFormatFunnelInjector} inside NeoForge's own deserializer, so nothing is recorded for them while it is
 * on. With it off, each removed injector is a confirmed finding naming that loss: Traveler's Backpack's backpacks
 * are {@code fabric:type} models, and without the funnel every one of them fails to bake.
 *
 * <p>NeoForge's substitution at that site is a census-pinned row of
 * {@link net.forbric.kernel.mixin.MergedBaseCalleeSwaps#SUBSTITUTED}. MixinRetarget moves an {@code @Inject} along it
 * (fusion's capture of the model id before the parse), because such a handler sees only the point; it never moves
 * this pair, whose handlers are the call and its argument, and {@code parse} is not {@code fromStream}.
 *
 * <p>Guest mixin classes reach the transform chain through {@code ForbricClassLoader.getPreMixinClassBytes},
 * which is also what {@link net.forbric.kernel.mixin.MixinFit} and Mixin itself read, so the pruned bytes are
 * the only bytes anyone judges or applies. Both methods must be present, each carrying an injector annotation
 * whose {@code method} list names {@code lambda$loadBlockModels$2}; a fabric-api that reshapes either leaves the
 * class untouched, with a warning, and the whole mixin then reads PARTIAL as it did before this class existed.
 *
 * <p>{@code -Dforbric.guestInjectorPruner=off} restores the previous behaviour EXACTLY: the pruner stands down, and
 * for an entry whose un-pruned state is a HALF-APPLICATION — {@code ModelManagerMixin} — {@code
 * MergedBaseMixinCompat.SUPPRESSED_UNLESS_PRUNED} puts the whole-mixin pin back instead. The other two entries need
 * no pin, because standing down reproduces the pre-pruner state by itself: the trade factory's redirect soft-skips
 * (its fitting sibling still applies) and balm's mixin aborts with {@code InvalidInjectionException}.
 *
 * <p>The second entry is fabric-item-api-v1's {@code ItemStackMixin}. Its five tooltip injectors thread one
 * {@code @Share("index")} through vanilla's {@code addDetailsToTooltip}, which NeoForge turned into a dispatcher over
 * its own appender lists: three bound in a renamed body nothing calls, one drew every Fabric line at once above the
 * item id in advanced tooltips, one bound nowhere. The kernel draws Fabric's component tooltips from NeoForge's
 * appenders instead ({@code KernelNeoTooltips}), so the five go — only while that bridge is on, or they would draw
 * the same lines twice — and {@code hookDamage} (custom damage handlers) applies as written. Nothing is recorded for
 * them: the bridge does their job, and {@code FabricApiModuleLossAudit} names a mod's use of the registry when it
 * is off.
 *
 * <p>The third entry is fabric-lifecycle-events-v1's {@code WorldChunkMixin}. Its {@code onRemoveBlockEntity(Map,
 * Object)} {@code @Redirect} fires {@code ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD} from the {@code Map.remove}
 * in {@code LevelChunk.getBlockEntity}, disambiguated by a {@code @Slice(from=LevelChunk.createBlockEntity)}. The
 * merged method runs the two {@code Map.remove}s (+30 {@code blockEntities}, +47 {@code pendingBlockEntities})
 * BEFORE {@code createBlockEntity} (+92), so the slice is empty and the redirect binds nowhere — yet the static
 * preflight reads the mixin FIT (it does not model an empty slice), so it is the post-application audit that reports
 * the miss, as a CONFIRMED required {@code mixin-injector} loss on every subject carrying the module. Pruning it
 * records the same loss as a finding that asks nothing
 * ({@code ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD} for the eviction at +30), and keeps the mixin's other three
 * handlers — the Load handler and two {@code setRemoved}-based unload handlers — applying as written. It is not
 * hidden debt: the finding names the cost. (Retargeting it instead needs a transform that rewrites the {@code
 * @Slice}/ordinal, which none of the current transformers does; see the measured caution below before building one.)
 *
 * <p>The fourth entry is fabric-item-api-v1's {@code BrewingStandBlockEntityMixin}. Its {@code captureItemStack}
 * still binds (the merged {@code doBrew} calls {@code ItemStack.shrink}), but its other two injectors cannot:
 * {@code hasStackRecipeRemainder} {@code @Redirect}s {@code Item.hasCraftingRemainingItem()} while the merged
 * {@code doBrew} calls {@code ItemStack.hasCraftingRemainingItem()} (the owner moved; the handler's {@code Item}
 * parameter no longer matches), and {@code createStackRecipeRemainder} {@code @WrapOperation}s the
 * {@code new ItemStack(ItemLike)} construction which the merged {@code doBrew} never performs (it calls
 * {@code ItemStack.getCraftingRemainingItem()}). Both are required losses that halt a STRICT launch for a feature
 * that cannot work; pruning them records each as a finding that asks nothing, with the cost named, and leaves the
 * working {@code captureItemStack} in place.
 *
 * <p>Two more single-injector stand-downs of the same kind, each with a working sibling left in place:
 * fabric-item-api-v1's {@code AnvilScreenHandlerMixin#callAllowEnchantingEvent} ({@code @Redirect} on
 * {@code Enchantment.canEnchant} in {@code AnvilMenu.createResult}; the merged createResult checks
 * {@code ItemStack.supportsEnchantment} instead) and fabric-recipe-api-v1's
 * {@code IngredientMixin#useCustomIngredientPacketCodec} ({@code @ModifyExpressionValue} on
 * {@code StreamCodec.map} in {@code Ingredient.<clinit>}; the merged {@code <clinit>} builds the codec through
 * {@code Either.map}). Both anchors are gone and neither handler fits the replacement, so both are pruned with the
 * loss named.
 *
 * <p>And fabric-content-registries-v0's {@code AbstractFurnaceBlockEntityMixin}: its two fuel-map {@code @Redirect}s
 * in {@code isFuel}/{@code getBurnDuration} cannot attach either, because the merged base routes both call sites
 * through {@code ForgeHooks.getBurnTime} (an {@code int}), and a handler that returns the fuel {@code Map} cannot
 * match that call — so it cannot be retargeted, only stood down, with the loss recorded. The mixin's
 * {@code fuelTimeMapHook} and its other anchors stay.
 *
 * <p>Three more from the same family, all member moves or a call site the merge moved, each with a surviving
 * anchor: fabric-item-api-v1's {@code EnchantRandomlyLootFunctionMixin#callAllowEnchantingEvent} ({@code canEnchant}
 * → {@code ItemStack.supportsEnchantment}, like the anvil one) and {@code RecipeMixin#hasStackRemainder} /
 * {@code #replaceGetRecipeRemainder} ({@code Item.hasCraftingRemainingItem}/{@code getCraftingRemainingItem} →
 * the {@code ItemStack} pair, like the brewing stand); and fabric-events-interaction-v0's
 * {@code ServerPlayerInteractionManagerMixin}, whose {@code onBlockBroken} anchor {@code Block.destroy} is no
 * longer called in {@code ServerPlayerGameMode.destroyBlock}, and whose {@code breakBlock} anchor IS present but
 * the {@code @Inject} captures locals the merged LVT no longer has ("incompatible changes at opcode 89").
 *
 * <p>Two more of the same kind, from the 10-subject slice that first reached the world. fabric-object-builder-v1's
 * {@code TradeOffersTypeAwareBuyForOneEmeraldFactoryMixin#disableVanillaCheck} {@code @At(INVOKE)}s
 * {@code DefaultedRegistry.stream} inside {@code VillagerTrades$EmeraldsForVillagerTypeItem.<init>}, and the merged
 * class has neither: its constructor takes the item map as a PARAMETER and assigns four fields, and no
 * {@code DefaultedRegistry.stream} call exists in the class or in {@code VillagerTrades} (the trade is built from a
 * literal map), so a handler returning a widened {@code Stream} cannot be retargeted either. It was a CONFIRMED
 * required loss on 8 of the 10 subjects. Its sibling {@code @At(NEW)} anchor needed no pruning: the name layer
 * translates {@code Lnet/minecraft/village/TradeOffer;} into {@code MerchantOffer} now (see
 * {@link net.forbric.kernel.mapping.MixinNames}).
 *
 * <p>And balm's own {@code FabricCropBlockMixin}, whose failure mode is the loud one. Its two {@code getGrowthSpeed*}
 * handlers anchor on {@code BlockState.is(Block)} inside a method the merged base declares as
 * {@code getGrowthSpeed(BlockState, BlockGetter, BlockPos)} and whose body no longer calls {@code BlockState.is} at
 * all — NeoForge rewrote the farmland test as {@code canSustainPlant}/{@code isFertile}/{@code getBlock()}. One
 * unbound required injector aborts the WHOLE mixin, so the two {@code randomTick} pre/post grow handlers — whose
 * {@code ServerLevel.setBlock} anchor IS present in the merged {@code randomTick} — reported no attachment, and the
 * mixin itself was a CONFIRMED required loss. Pruning the two unfit handlers lets the mixin apply and both events
 * bind; the cost is that a {@code CustomFarmBlock}'s {@code isFertile} no longer changes a crop's growth speed.
 * Those two handlers are SUSPECTED rather than CONFIRMED in the report and are named here so a later reader does not
 * re-open them as unexplained: they are the CAUSE of the three counted balm rows.
 *
 * <p><b>A lambda-selector retarget is NOT local — measured 2026-10-03, reverted.</b> A transformer that rewrote a
 * guest mixin's selectors onto the lambda the merged base declares cleared {@code SerializableRegistriesMixin}'s
 * finding, and the same subject then reported eleven more CONFIRMED {@code mixin-injector} losses plus a balm
 * {@code InvalidInjectionException}. The rewrite itself touched ONE class of 3807 in the remapped tree (only that
 * mixin, only its two selectors), so it cannot have edited the failing mixins. What moves is the PIPELINE:
 * clearing the mixin's required finding let the launch pass the STRICT compatibility gate that had halted it
 * before Mixin applied anything, and post-application audit verdicts ("no attachment in the actual defined class")
 * that are unreachable at gate depth became visible. Whether those eleven are a REGRESSION or a failure that was
 * always there and merely hidden behind the early stop is UNRESOLVED: the {@code -Dforbric.lambdaSelectorRetarget=off}
 * isolation arm reproduced the parent's gate stop (`stopped=2`, no mixin application), so it compared two
 * different boot depths and could not settle it. {@code confirmedRequired} is depth-sensitive — a retarget must be
 * judged with {@code -Dforbric.compatibilityPolicy=continue} on BOTH arms, or the 1&rarr;12 delta is a boot-depth
 * artefact, not an injection regression. Understanding why those eleven injectors report unattached once the boot
 * goes deep enough is the real next question; the retarget is orthogonal to it.
 */
public final class GuestInjectorPruner implements ClassTransformer {
	public static final String PROPERTY = "forbric.guestInjectorPruner";

	static final String MODEL_MANAGER_MIXIN = "net.fabricmc.fabric.mixin.client.model.loading.ModelManagerMixin";
	static final String MODEL_LAMBDA = "lambda$loadBlockModels$2";
	static final String ITEM_STACK_MIXIN = "net.fabricmc.fabric.mixin.item.ItemStackMixin";
	static final String WORLD_CHUNK_MIXIN = "net.fabricmc.fabric.mixin.event.lifecycle.server.WorldChunkMixin";
	static final String BREWING_STAND_MIXIN = "net.fabricmc.fabric.mixin.item.BrewingStandBlockEntityMixin";
	static final String ANVIL_HANDLER_MIXIN = "net.fabricmc.fabric.mixin.item.AnvilScreenHandlerMixin";
	static final String INGREDIENT_MIXIN = "net.fabricmc.fabric.mixin.recipe.ingredient.IngredientMixin";
	static final String FURNACE_CONTENT_MIXIN =
			"net.fabricmc.fabric.mixin.content.registry.AbstractFurnaceBlockEntityMixin";
	static final String ENCHANT_RANDOMLY_MIXIN = "net.fabricmc.fabric.mixin.item.EnchantRandomlyLootFunctionMixin";
	static final String RECIPE_MIXIN = "net.fabricmc.fabric.mixin.item.RecipeMixin";
	static final String PLAYER_INTERACTION_MIXIN =
			"net.fabricmc.fabric.mixin.event.interaction.ServerPlayerInteractionManagerMixin";
	static final String TRADE_OFFERS_MIXIN =
			"net.fabricmc.fabric.mixin.object.builder.TradeOffersTypeAwareBuyForOneEmeraldFactoryMixin";
	static final String BALM_CROP_MIXIN = "net.blay09.mods.balm.mixin.FabricCropBlockMixin";
	static final String SHADOWGUARD_FIRE_MIXIN = "org.krripe.shadowguard.mixin.FireBlockMixin";
	static final String ARCHITECTURY_GAMEMODE_MIXIN =
			"dev.architectury.mixin.fabric.MixinServerPlayerGameMode";
	static final String ARCHITECTURY_PHANTOM_MIXIN = "dev.architectury.mixin.fabric.MixinPhantomSpawner";

	/**
	 * One table plus the entries that would push {@code Map.of} past its ten-pair limit. Java's {@code Map.of}
	 * takes at most ten pairs, and these seven tables describe eleven and twelve mixins; the alternative — hoisting
	 * every existing pair into a {@code Map.ofEntries} — rewrites lines whose content nobody is changing, which is
	 * exactly the kind of churn a diff of this file should not carry.
	 */
	private static <K, V> Map<K, V> with(Map<K, V> base, Map<K, V> extra) {
		Map<K, V> all = new java.util.LinkedHashMap<>(base);
		all.putAll(extra);
		return Map.copyOf(all);
	}
	private static final String SHARED_INDEX = "Lcom/llamalad7/mixinextras/sugar/ref/LocalIntRef;";

	/**
	 * One injector method to remove, and the target-method selector its annotation must carry: a prefix, or with
	 * {@code exact} the whole selector — {@code addDetailsToTooltip} is also the prefix of the two renamed bodies.
	 */
	record Prune(String name, String desc, String selectorPrefix, boolean exact) {
		Prune(String name, String desc, String selectorPrefix) {
			this(name, desc, selectorPrefix, false);
		}

		String key() {
			return name + desc;
		}
	}

	static final Map<String, List<Prune>> EXTRA_TABLE = Map.ofEntries(
			// The prefix is the LIVE selector, not the module's own `"<init>"`: the name layer translates a member
			// selector through the module's refmap before this transformer sees the class (the refmap maps
			// `"<init>"` to `L…EmeraldsForVillagerTypeItem;<init>(IIILjava/util/Map;)V`), so a prefix written the way
			// the MOD wrote it matches nothing here and the pruner declines — measured in a real boot, where this
			// entry's guard read `no longer injects into <init>` while the class the loader had was translated.
			Map.entry(TRADE_OFFERS_MIXIN, List.of(new Prune("disableVanillaCheck",
					"(Lnet/minecraft/core/DefaultedRegistry;)Ljava/util/stream/Stream;",
					"Lnet/minecraft/world/entity/npc/VillagerTrades$EmeraldsForVillagerTypeItem;<init>"))),
			// The merge WIDENED this method's signature, which no prefix can paper over: the module's handler was
			// compiled against FireBlock.checkBurnOut(Level, BlockPos, int, RandomSource, int) and the merged
			// (NeoForge) one takes a Direction before the callback. Mixin's own words, verbatim:
			//   InvalidInjectionException: Invalid descriptor …->@Inject::shadowguard$protectFireTarget(…)V!
			//   Expected (…,I,Lnet/minecraft/core/Direction;,Lorg/…/callback/CallbackInfo;)V but found (…,I,Lorg/…CallbackInfo;)V
			// One handler that cannot bind aborts the WHOLE mixin, which is why its two siblings reported "no
			// attachment" too and why the mixin-level finding exists at all. Removing this one handler lets the
			// other two apply: $protectFireTick is @Inject(method = "tick", at = HEAD) and $protectFirePlacement is
			// the @Redirect on ServerLevel.setBlock inside the same tick, both of which the merged base still has.
			// Cost: ShadowGuard's fire-TARGET protection (the checkBurnOut veto) does not run on this subject.
			Map.entry(SHADOWGUARD_FIRE_MIXIN, List.of(new Prune("shadowguard$protectFireTarget",
					"(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;ILnet/minecraft/util/RandomSource;"
							+ "ILorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V",
					"checkBurnOut"))),
			// Both architectury handlers ANCHOR fine — what fails is the LOCAL CAPTURE. Mixin says so itself:
			//   Injection warning: LVT in …ServerPlayerGameMode::destroyBlock(…)Z has incompatible changes at opcode 39
			//                      in callback architectury.mixins.json:MixinServerPlayerGameMode …->@Inject::onBreak
			// The merge changed the locals at that point (NeoForge's fire/break patches insert their own), so the
			// @Inject.locals capture cannot be satisfied and the injection is skipped. The kernel already softens the
			// capture (CAPTURE_FAILHARD → CAPTURE_FAILSOFT) so the game survives, and the census counts the skipped
			// handler as a required loss. A capture is not a selector: there is nothing to retarget, and rewriting the
			// handler's own signature is the module's code, not ours. Removing the handler keeps the rest of the mixin.
			// Cost: architectury's block-break event (onBreak) does not fire on subjects whose closure carries it.
			Map.entry(ARCHITECTURY_GAMEMODE_MIXIN, List.of(new Prune("onBreak",
					"(Lnet/minecraft/core/BlockPos;"
							+ "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;"
							+ "Lnet/minecraft/world/level/block/entity/BlockEntity;"
							+ "Lnet/minecraft/world/level/block/state/BlockState;)V",
					"Lnet/minecraft/server/level/ServerPlayerGameMode;destroyBlock"))),
			// Same cause as the entry above, different handler: LVT …PhantomSpawner::tick(…)I has incompatible
			// changes at opcode 267. Cost: architectury's phantom-spawn event (checkPhantomSpawn) does not fire.
			Map.entry(ARCHITECTURY_PHANTOM_MIXIN, List.of(new Prune("checkPhantomSpawn",
					"(Lnet/minecraft/server/level/ServerLevel;ZZ"
							+ "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;"
							+ "Lnet/minecraft/util/RandomSource;ILjava/util/Iterator;"
							+ "Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/core/BlockPos;"
							+ "Lnet/minecraft/world/DifficultyInstance;Lnet/minecraft/stats/ServerStatsCounter;II"
							+ "Lnet/minecraft/core/BlockPos;"
							+ "Lnet/minecraft/world/level/block/state/BlockState;"
							+ "Lnet/minecraft/world/level/material/FluidState;"
							+ "Lnet/minecraft/world/entity/SpawnGroupData;II"
							+ "Lnet/minecraft/world/entity/monster/Phantom;)V",
					"Lnet/minecraft/world/level/levelgen/PhantomSpawner;tick"))),
			Map.entry(BALM_CROP_MIXIN, List.of(
					new Prune("getGrowthSpeed",
							"(FLnet/minecraft/world/level/block/Block;Lnet/minecraft/world/level/BlockGetter;"
									+ "Lnet/minecraft/core/BlockPos;Lcom/llamalad7/mixinextras/sugar/ref/LocalRef;)F",
							"getGrowthSpeed"),
					new Prune("getGrowthSpeedCaptureLocals",
							"(Lnet/minecraft/world/level/block/state/BlockState;"
									+ "Lcom/llamalad7/mixinextras/sugar/ref/LocalRef;)"
									+ "Lnet/minecraft/world/level/block/state/BlockState;",
							"getGrowthSpeed"))));

	static final Map<String, List<Prune>> TABLE = with(Map.of(MODEL_MANAGER_MIXIN, List.of(
			new Prune("cancelVanillaDeserialize",
					"(Ljava/io/Reader;)Lnet/minecraft/client/resources/model/cuboid/CuboidModel;", MODEL_LAMBDA),
			new Prune("actuallyDeserializeModel",
					"(Ljava/lang/Object;Ljava/io/Reader;)Ljava/lang/Object;", MODEL_LAMBDA)),
			ITEM_STACK_MIXIN, List.of(
				new Prune("preAppendComponentTooltip", "(Lnet/minecraft/core/component/DataComponentType;Lnet/minecraft/world/item/Item$TooltipContext;"
						+ "Lnet/minecraft/world/item/component/TooltipDisplay;Lnet/minecraft/world/item/TooltipFlag;Ljava/util/function/Consumer;"
						+ SHARED_INDEX + ")Lnet/minecraft/core/component/DataComponentType;", "addDetailsToTooltip", true),
				new Prune("preShouldDisplay", "(Lnet/minecraft/core/component/DataComponentType;Lnet/minecraft/world/item/Item$TooltipContext;"
						+ "Lnet/minecraft/world/item/component/TooltipDisplay;Lnet/minecraft/world/item/TooltipFlag;Ljava/util/function/Consumer;"
						+ SHARED_INDEX + ")Lnet/minecraft/core/component/DataComponentType;", "addDetailsToTooltip", true),
				new Prune("preAttributeModifiers", "(Lnet/minecraft/world/item/Item$TooltipContext;Lnet/minecraft/world/item/component/TooltipDisplay;"
						+ "Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/TooltipFlag;Ljava/util/function/Consumer;"
						+ "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;" + SHARED_INDEX + ")V", "addDetailsToTooltip", true),
				new Prune("postTooltipsAdvanced", "(Lnet/minecraft/world/item/Item$TooltipContext;Lnet/minecraft/world/item/component/TooltipDisplay;"
						+ "Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/TooltipFlag;Ljava/util/function/Consumer;"
						+ "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;" + SHARED_INDEX + ")V", "addDetailsToTooltip", true),
				new Prune("postTooltipsNonAdvanced", "(ZLnet/minecraft/world/item/Item$TooltipContext;Lnet/minecraft/world/item/component/TooltipDisplay;"
						+ "Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/TooltipFlag;Ljava/util/function/Consumer;"
						+ SHARED_INDEX + ")Z", "addDetailsToTooltip", true)),
			WORLD_CHUNK_MIXIN, List.of(new Prune("onRemoveBlockEntity",
					"(Ljava/util/Map;Ljava/lang/Object;)Ljava/lang/Object;",
					"Lnet/minecraft/world/level/chunk/LevelChunk;getBlockEntity")),
			BREWING_STAND_MIXIN, List.of(
					new Prune("hasStackRecipeRemainder", "(Lnet/minecraft/world/item/Item;)Z",
							"Lnet/minecraft/world/level/block/entity/BrewingStandBlockEntity;doBrew"),
					new Prune("createStackRecipeRemainder",
							"(Lnet/minecraft/world/level/ItemLike;"
									+ "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)"
									+ "Lnet/minecraft/world/item/ItemStack;",
							"Lnet/minecraft/world/level/block/entity/BrewingStandBlockEntity;doBrew")),
			ANVIL_HANDLER_MIXIN, List.of(new Prune("callAllowEnchantingEvent",
					"(Lnet/minecraft/world/item/enchantment/Enchantment;Lnet/minecraft/world/item/ItemStack;"
							+ "Lnet/minecraft/core/Holder;)Z",
					"Lnet/minecraft/world/inventory/AnvilMenu;createResult")),
			INGREDIENT_MIXIN, List.of(new Prune("useCustomIngredientPacketCodec",
					"(Lnet/minecraft/network/codec/StreamCodec;)Lnet/minecraft/network/codec/StreamCodec;",
					"Lnet/minecraft/world/item/crafting/Ingredient;<clinit>")),
			FURNACE_CONTENT_MIXIN, List.of(
					new Prune("canUseAsFuelRedirect", "()Ljava/util/Map;",
							"Lnet/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity;isFuel"),
					new Prune("getFuelTimeRedirect", "()Ljava/util/Map;",
							"Lnet/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity;getBurnDuration")),
			ENCHANT_RANDOMLY_MIXIN, List.of(new Prune("callAllowEnchantingEvent",
					"(Lnet/minecraft/world/item/enchantment/Enchantment;Lnet/minecraft/world/item/ItemStack;Z"
							+ "Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/Holder;)Z",
					"lambda$run$")),
			RECIPE_MIXIN, List.of(
					new Prune("hasStackRemainder", "(Lnet/minecraft/world/item/Item;)Z",
							"Lnet/minecraft/world/item/crafting/Recipe;getRemainingItems"),
					new Prune("replaceGetRecipeRemainder", "(Lnet/minecraft/world/item/Item;)"
							+ "Lnet/minecraft/world/item/Item;", "Lnet/minecraft/world/item/crafting/Recipe;getRemainingItems")),
			PLAYER_INTERACTION_MIXIN, List.of(
					new Prune("breakBlock", "(Lnet/minecraft/core/BlockPos;"
							+ "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;"
							+ "Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/level/block/Block;"
							+ "Lnet/minecraft/world/level/block/state/BlockState;)V",
							"Lnet/minecraft/server/level/ServerPlayerGameMode;destroyBlock"),
					new Prune("onBlockBroken", "(Lnet/minecraft/core/BlockPos;"
							+ "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;"
							+ "Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/level/block/Block;"
							+ "Lnet/minecraft/world/level/block/state/BlockState;Z)V",
							"Lnet/minecraft/server/level/ServerPlayerGameMode;destroyBlock"))), EXTRA_TABLE);

	/** The two entries the config table cannot hold; see the class docs for their evidence and cost. */
	private static final Map<String, String> EXTRA_CONFIGS = Map.ofEntries(
			Map.entry(TRADE_OFFERS_MIXIN, "fabric-object-builder-v1.mixins.json"),
			Map.entry(BALM_CROP_MIXIN, "balm.fabric.mixins.json"),
			Map.entry(SHADOWGUARD_FIRE_MIXIN, "shadowguard.mixins.json"),
			Map.entry(ARCHITECTURY_GAMEMODE_MIXIN, "architectury.mixins.json"),
			Map.entry(ARCHITECTURY_PHANTOM_MIXIN, "architectury.mixins.json"));

	/** The mixin config each entry is declared in, which names the owning mod on the finding. */
	static final Map<String, String> CONFIGS = with(Map.of(MODEL_MANAGER_MIXIN, "fabric-model-loading-api-v1.mixins.json",
			ITEM_STACK_MIXIN, "fabric-item-api-v1.mixins.json",
			WORLD_CHUNK_MIXIN, "fabric-lifecycle-events-v1.mixins.json",
			BREWING_STAND_MIXIN, "fabric-item-api-v1.mixins.json",
			ANVIL_HANDLER_MIXIN, "fabric-item-api-v1.mixins.json",
			INGREDIENT_MIXIN, "fabric-recipe-api-v1.mixins.json",
			FURNACE_CONTENT_MIXIN, "fabric-content-registries-v0.mixins.json",
			ENCHANT_RANDOMLY_MIXIN, "fabric-item-api-v1.mixins.json",
			RECIPE_MIXIN, "fabric-item-api-v1.mixins.json",
			PLAYER_INTERACTION_MIXIN, "fabric-events-interaction-v0.mixins.json"), EXTRA_CONFIGS);

	/** Whether an entry applies on this boot, beyond the pruner's own switch. */
	private static final Map<String, BooleanSupplier> EXTRA_ACTIVE = Map.ofEntries(
			Map.entry(TRADE_OFFERS_MIXIN, () -> true),
			Map.entry(BALM_CROP_MIXIN, () -> true),
			Map.entry(SHADOWGUARD_FIRE_MIXIN, () -> true),
			Map.entry(ARCHITECTURY_GAMEMODE_MIXIN, () -> true),
			Map.entry(ARCHITECTURY_PHANTOM_MIXIN, () -> true));

	static final Map<String, BooleanSupplier> ACTIVE = with(Map.of(MODEL_MANAGER_MIXIN, () -> true,
			ITEM_STACK_MIXIN, GuestInjectorPruner::fabricTooltipBridgeOn,
			WORLD_CHUNK_MIXIN, () -> true,
			BREWING_STAND_MIXIN, () -> true,
			ANVIL_HANDLER_MIXIN, () -> true,
			INGREDIENT_MIXIN, () -> true,
			FURNACE_CONTENT_MIXIN, () -> true,
			ENCHANT_RANDOMLY_MIXIN, () -> true,
			RECIPE_MIXIN, () -> true,
			PLAYER_INTERACTION_MIXIN, () -> true), EXTRA_ACTIVE);

	/** What is lost when an entry's class loads and is not pruned. */
	private static final Map<String, String> EXTRA_COSTS = Map.ofEntries(
			Map.entry(TRADE_OFFERS_MIXIN, "the injector stays in the mixin, cannot attach (the merged "
					+ "EmeraldsForVillagerTypeItem.<init> only assigns four fields — the caller builds the map, and no "
					+ "DefaultedRegistry.stream call exists anywhere in the merged base) and is reported as a required "
					+ "CONFIRMED loss, so a STRICT launch halts on it; the feature is gone either way, since the trade it "
					+ "would widen is no longer built from a registry iteration"),
			Map.entry(SHADOWGUARD_FIRE_MIXIN, "the handler stays and aborts the WHOLE mixin "
					+ "(InvalidInjectionException: its parameter list is one Direction short of the merged "
					+ "checkBurnOut), taking $protectFireTick and $protectFirePlacement with it although both of their "
					+ "anchors are present — a STRICT launch then halts on three findings instead of one; the feature "
					+ "is gone either way, since the merged checkBurnOut takes a Direction this handler cannot accept"),
			Map.entry(ARCHITECTURY_GAMEMODE_MIXIN, "the handler stays, its @Inject.locals capture still cannot be "
					+ "satisfied on the merged body, and the census counts the skipped injection as a required CONFIRMED "
					+ "loss, so a STRICT launch halts on it; the injection is skipped either way — the kernel softened "
					+ "the capture to FAILSOFT so the game survives — so architectury's block-break event does not fire "
					+ "either way"),
			Map.entry(ARCHITECTURY_PHANTOM_MIXIN, "the handler stays, its @Inject.locals capture still cannot be "
					+ "satisfied on the merged body, and the census counts the skipped injection as a required CONFIRMED "
					+ "loss, so a STRICT launch halts on it; the injection is skipped either way, so architectury's "
					+ "phantom-spawn event does not fire either way"),
			Map.entry(BALM_CROP_MIXIN, "the two injectors stay in the mixin and abort it (InvalidInjectionException), "
					+ "taking the two randomTick handlers with them — both of THEIR anchors are present in the merged "
					+ "randomTick, so the abort, not the anchor, is what makes them required losses, and the mixin-level "
					+ "failure is reported on its own; the feature is gone either way, since the merged getGrowthSpeed "
					+ "consults NeoForge's own canSustainPlant/isFertile and never the mod's CustomFarmBlock"));

	static final Map<String, String> COSTS = with(Map.of(MODEL_MANAGER_MIXIN,
			"the whole mixin stays pinned, so every Fabric ModelLoadingPlugin -- block-state resolvers, extra "
					+ "models, model modifiers -- is registered and never called",
			ITEM_STACK_MIXIN, "fabric-item-api's tooltip injectors stay where the retarget put them, so the kernel's "
					+ "tooltip bridge stands down and a Fabric mod's component tooltips are missing from normal tooltips",
			WORLD_CHUNK_MIXIN, "the redirect stays in the mixin, cannot attach (its @Slice matches nothing in the "
					+ "merged order) and is reported as a required CONFIRMED loss, so a STRICT launch halts on it",
			BREWING_STAND_MIXIN, "the two injectors stay in the mixin, cannot attach (the merged doBrew calls "
					+ "ItemStack.hasCraftingRemainingItem and constructs no ItemStack) and are reported as required "
					+ "losses, so a STRICT launch halts on them",
			ANVIL_HANDLER_MIXIN, "the redirect stays in the mixin, cannot attach (the merged createResult no longer "
					+ "calls Enchantment.canEnchant) and is reported as a required loss, so a STRICT launch halts on it",
			INGREDIENT_MIXIN, "the value modifier stays in the mixin, cannot attach (the merged <clinit> builds the codec "
					+ "with Either.map, not StreamCodec.map) and is reported as a required loss, so a STRICT launch halts on it",
			FURNACE_CONTENT_MIXIN, "the two redirects stay in the mixin, cannot attach (the merged isFuel/getBurnDuration "
					+ "call ForgeHooks.getBurnTime, not getFuel) and are reported as required CONFIRMED losses, so a "
					+ "STRICT launch halts on them",
			ENCHANT_RANDOMLY_MIXIN, "the redirect stays in the mixin, cannot attach (the merged function calls "
					+ "ItemStack.supportsEnchantment, not Enchantment.canEnchant) and is reported as a required loss, "
					+ "so a STRICT launch halts on it",
			RECIPE_MIXIN, "the two redirects stay in the mixin, cannot attach (the merged Recipe.getRemainingItems "
					+ "calls ItemStack.hasCraftingRemainingItem/getCraftingRemainingItem, not the Item overloads) and are "
					+ "reported as required losses, so a STRICT launch halts on them",
			PLAYER_INTERACTION_MIXIN, "breakBlock is lost to the local-variable table and onBlockBroken to a removed "
					+ "Block.destroy call site; both are reported as required losses, so a STRICT launch halts on them"),
			EXTRA_COSTS);

	/** Why an entry's injectors cannot stay, for the log line. */
	private static final Map<String, String> EXTRA_REASONS = Map.ofEntries(
			Map.entry(TRADE_OFFERS_MIXIN, "the merged EmeraldsForVillagerTypeItem.<init>(int,int,int,Map) takes its map "
					+ "as a PARAMETER and assigns four fields; vanilla's registry iteration is gone from the class "
					+ "entirely — VillagerTrades builds that map without iterating BuiltInRegistries.VILLAGER_TYPE at "
					+ "all — so the @At(INVOKE) DefaultedRegistry.stream anchor has no call to bind to, and a handler "
					+ "returning a widened Stream cannot fit any surviving call"),
			Map.entry(SHADOWGUARD_FIRE_MIXIN, "the merge WIDENED FireBlock.checkBurnOut: Mixin's own words are "
					+ "\"Expected (…,I,Lnet/minecraft/core/Direction;,Lorg/…CallBackInfo;)V but found (…,I,Lorg/…CallBackInfo;)V\" "
					+ "— a handler's parameter list is the module's code shape, not a selector the name layer could "
					+ "translate, so nothing here is retargetable"),
			Map.entry(ARCHITECTURY_GAMEMODE_MIXIN, "the anchor binds — what fails is the LOCAL CAPTURE: "
					+ "\"Injection warning: LVT in …ServerPlayerGameMode::destroyBlock(…)Z has incompatible changes at opcode "
					+ "39\", because NeoForge's break path inserts locals there; a capture is not a selector and the "
					+ "handler's signature is the module's own code"),
			Map.entry(ARCHITECTURY_PHANTOM_MIXIN, "the anchor binds — what fails is the LOCAL CAPTURE: "
					+ "\"Injection warning: LVT in …PhantomSpawner::tick(…)I has incompatible changes at opcode 267\"; "
					+ "a capture is not a selector and the handler's signature is the module's own code"),
			Map.entry(BALM_CROP_MIXIN, "the merged (NeoForge) CropBlock.getGrowthSpeed takes a BlockState, not vanilla's "
					+ "Block, and its body no longer calls BlockState.is(Block) at all — NeoForge rewrote the farmland "
					+ "test as canSustainPlant/isFertile/getBlock() — so both anchors (ordinals 0 and 1) are gone; one "
					+ "unbound required injector aborts the WHOLE mixin, which is why the two randomTick handlers report "
					+ "no attachment although their ServerLevel.setBlock anchor is present in the merged randomTick"));

	static final Map<String, String> REASONS = with(Map.of(MODEL_MANAGER_MIXIN,
			"NeoForge replaced CuboidModel.fromStream with UnbakedModelParser.parse at that site, so fabric's @Redirect "
					+ "could not bind while its @ModifyArg did and re-read a consumed Reader (every block model missingno)",
			ITEM_STACK_MIXIN, "NeoForge's ItemStack draws tooltips from its appender lists, where the kernel draws "
					+ "Fabric's component tooltip providers now; these would have drawn them a second time, or nowhere",
			WORLD_CHUNK_MIXIN, "the redirect watches the Map.remove inside LevelChunk.getBlockEntity behind a "
					+ "@Slice(from=LevelChunk.createBlockEntity), and the merged method calls BOTH Map.remove sites "
					+ "(+30 blockEntities, +47 pendingBlockEntities) BEFORE createBlockEntity (+92), so the slice is empty",
			BREWING_STAND_MIXIN, "the merged doBrew calls ItemStack.hasCraftingRemainingItem()/getCraftingRemainingItem() "
					+ "(the owner moved Item->ItemStack, and the handler's Item parameter no longer matches) and never "
					+ "constructs an ItemStack through the (ItemLike)ItemStack ctor the @WrapOperation wraps",
			ANVIL_HANDLER_MIXIN, "the merged AnvilMenu.createResult no longer calls Enchantment.canEnchant(ItemStack); "
					+ "NeoForge moved the check to ItemStack.supportsEnchantment(Holder), a different owner and parameter, "
					+ "so the redirect's handler cannot bind",
			INGREDIENT_MIXIN, "the merged Ingredient.<clinit> builds the packet codec through Either.map, not the "
					+ "StreamCodec.map the @ModifyExpressionValue selects; the owner and return type both differ, so the "
					+ "modifier binds nowhere",
			FURNACE_CONTENT_MIXIN, "the merged isFuel (+2) and getBurnDuration (+19) call ForgeHooks.getBurnTime, not "
					+ "getFuel; a @Redirect handler returning the fuel Map cannot match a call returning an int, so the "
					+ "two cannot be retargeted onto the ForgeHooks call site",
			ENCHANT_RANDOMLY_MIXIN, "the merged EnchantRandomlyFunction.lambda$run$4 no longer calls "
					+ "Enchantment.canEnchant(ItemStack); NeoForge moved the check to ItemStack.supportsEnchantment(Holder)",
			RECIPE_MIXIN, "the merged Recipe.getRemainingItems calls ItemStack.hasCraftingRemainingItem() and "
					+ "getCraftingRemainingItem() (the owner moved Item->ItemStack, so the Item-parameter handlers cannot match)",
			PLAYER_INTERACTION_MIXIN, "onBlockBroken's @At(INVOKE) Block.destroy is no longer made inside "
					+ "ServerPlayerGameMode.destroyBlock, and breakBlock's @Inject captures locals its LVT no longer has "
					+ "(incompatible changes at opcode 89); the anchor is present but the capture is not"),
			EXTRA_REASONS);

	/** What happens to an entry's mixin when a reshaped fabric-api leaves it untouched. */
	private static final Map<String, String> EXTRA_DRIFT = Map.ofEntries(
			Map.entry(TRADE_OFFERS_MIXIN, "the injector soft-skips with Mixin's own warning, exactly as it did before "
					+ "this entry, and the mixin's three other anchors still bind"),
			Map.entry(SHADOWGUARD_FIRE_MIXIN, "shadowguard's whole fire mixin aborts (InvalidInjectionException) and "
					+ "its two other handlers never attach — exactly the state before this entry"),
			Map.entry(ARCHITECTURY_GAMEMODE_MIXIN, "the injection is skipped with Mixin's own warning, exactly as it did "
					+ "before this entry; this mixin has no other handler"),
			Map.entry(ARCHITECTURY_PHANTOM_MIXIN, "the injection is skipped with Mixin's own warning, exactly as it did "
					+ "before this entry; this mixin has no other handler"),
			Map.entry(BALM_CROP_MIXIN, "balm's whole crop mixin aborts (InvalidInjectionException) and its two randomTick "
					+ "handlers never attach — exactly the state before this entry"));

	static final Map<String, String> DRIFT = with(Map.of(MODEL_MANAGER_MIXIN, "it will read PARTIAL and apply half — the state that made every block "
					+ "model missingno",
			ITEM_STACK_MIXIN, "it is retargeted as before and the kernel's tooltip bridge stands down; Fabric component "
					+ "tooltip providers show only above the item id in advanced tooltips",
			WORLD_CHUNK_MIXIN, "the redirect soft-skips with Mixin's own warning, exactly as it did before this entry",
			BREWING_STAND_MIXIN, "the two injectors soft-skip with Mixin's own warnings, exactly as they did before "
					+ "this entry, and only captureItemStack binds",
			ANVIL_HANDLER_MIXIN, "the redirect soft-skips with Mixin's own warning, exactly as it did before this entry",
			INGREDIENT_MIXIN, "the value modifier soft-skips with Mixin's own warning, exactly as it did before this entry",
			FURNACE_CONTENT_MIXIN, "the two redirects soft-skip with Mixin's own warnings, exactly as they did before "
					+ "this entry, and the mixin's other three anchors still bind",
			ENCHANT_RANDOMLY_MIXIN, "the redirect soft-skips with Mixin's own warning, exactly as it did before this entry",
			RECIPE_MIXIN, "the two redirects soft-skip with Mixin's own warnings, exactly as they did before this entry",
			PLAYER_INTERACTION_MIXIN, "both soft-skip with Mixin's own warnings, exactly as they did before this entry"),
			EXTRA_DRIFT);

	/** The finding a removed injector records, or none when a kernel repair does its job. */
	private static final Map<String, String> EXTRA_LOSSES = Map.ofEntries(
			Map.entry(TRADE_OFFERS_MIXIN, "the kernel removed this injector: Fabric's type-aware emerald trade no longer "
					+ "substitutes a modded VillagerType for the vanilla one (the merged EmeraldsForVillagerTypeItem is "
					+ "built from a caller-supplied map, with no registry iteration left to widen); the mixin's @At(NEW) "
					+ "construction anchor and its method anchors still bind"),
			Map.entry(SHADOWGUARD_FIRE_MIXIN, "the kernel removed this injector: ShadowGuard's fire-TARGET protection "
					+ "(the checkBurnOut veto) is not installed — the merged checkBurnOut takes a Direction the handler "
					+ "cannot accept; the mixin's tick @Inject(HEAD) and its ServerLevel.setBlock @Redirect still apply"),
			Map.entry(ARCHITECTURY_GAMEMODE_MIXIN, "the kernel removed this injector: architectury's block-break event "
					+ "no longer fires on subjects whose closure carries architectury — its @Inject.locals capture cannot "
					+ "be satisfied on the merged destroyBlock; this mixin has no other handler"),
			Map.entry(ARCHITECTURY_PHANTOM_MIXIN, "the kernel removed this injector: architectury's phantom-spawn event "
					+ "no longer fires — its @Inject.locals capture cannot be satisfied on the merged PhantomSpawner.tick; "
					+ "this mixin has no other handler"),
			Map.entry(BALM_CROP_MIXIN, "the kernel removed these injectors: a balm CustomFarmBlock's "
					+ "canSustainPlant/isFertile no longer changes a crop's growth speed (the merged "
					+ "CropBlock.getGrowthSpeed consults NeoForge's own canSustainPlant/isFertile); the mixin's two "
					+ "randomTick handlers (the pre/post grow events) still bind"));

	static final Map<String, String> LOSSES = with(Map.of(MODEL_MANAGER_MIXIN,
			"the kernel removed this injector: NeoForge's UnbakedModelParser now reads block models at its call site, so "
					+ "Fabric's fabric:type custom model formats (UnbakedModelDeserializer) are not consulted — the "
					+ "kernel's own dispatch of them is off (-D" + ModelFormatFunnelInjector.PROPERTY + "=off)",
			WORLD_CHUNK_MIXIN, "the kernel removed this injector: ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD no longer "
					+ "fires when LevelChunk.getBlockEntity evicts a removed block entity (the blockEntities.remove at "
					+ "+30); the mixin's Load handler and its two setRemoved-based Unload handlers still apply",
			BREWING_STAND_MIXIN, "the kernel removed this injector: fabric-item-api's crafting-remainder substitution no "
					+ "longer applies to the brewing stand (doBrew calls ItemStack.hasCraftingRemainingItem and "
					+ "getCraftingRemainingItem directly); the mixin's captureItemStack (the ItemStack.shrink inject) "
					+ "still binds",
			ANVIL_HANDLER_MIXIN, "the kernel removed this injector: fabric-item-api's AllowEnchanting event no longer "
					+ "fires from AnvilMenu.createResult (NeoForge checks ItemStack.supportsEnchantment instead); the "
					+ "mixin's other anchor still binds",
			INGREDIENT_MIXIN, "the kernel removed this injector: fabric-recipe-api's custom Ingredient packet codec no "
					+ "longer replaces the built-in one (the merged <clinit> uses Either.map); the mixin's injectCodec "
					+ "still binds",
			FURNACE_CONTENT_MIXIN, "the kernel removed this injector: fabric-content-registries-v0's furnace fuel map is no "
					+ "longer consulted by isFuel/getBurnDuration (the merged base routes both through "
					+ "ForgeHooks.getBurnTime); the mixin's fuelTimeMapHook and its other anchors still bind",
			ENCHANT_RANDOMLY_MIXIN, "the kernel removed this injector: fabric-item-api's AllowEnchanting event no longer "
					+ "fires from EnchantRandomlyFunction (the merged base checks ItemStack.supportsEnchantment)",
			RECIPE_MIXIN, "the kernel removed these injectors: fabric-item-api's crafting-remainder substitution no longer "
					+ "applies in Recipe.getRemainingItems (the merged base calls ItemStack's own methods)",
			PLAYER_INTERACTION_MIXIN, "the kernel removed these injectors: fabric-events-interaction's "
					+ "PlayerBlockBreakEvents BEFORE/AFTER no longer fire from ServerPlayerGameMode.destroyBlock"),
			EXTRA_LOSSES);

	/**
	 * The finding an entry's removed injectors record on this boot, or null when something does their job:
	 * the model pair's {@code fabric:type} dispatch is {@link ModelFormatFunnelInjector}'s while it is on.
	 */
	static String lossOf(String mixin) {
		if (MODEL_MANAGER_MIXIN.equals(mixin) && ModelFormatFunnelInjector.enabled()) return null;
		return LOSSES.get(mixin);
	}

	private static volatile boolean fabricTooltipsPruned;

	/**
	 * fabric-item-api's tooltip injectors go only while the kernel draws Fabric's providers from NeoForge's appenders:
	 * NeoForge's appenders built, and the bridge on.
	 */
	public static boolean fabricTooltipBridgeOn() {
		return !"off".equalsIgnoreCase(System.getProperty("forbric.neoTooltipAppenders", "on"))
				&& !"off".equalsIgnoreCase(System.getProperty(FABRIC_TOOLTIP_BRIDGE, "on"));
	}

	/** {@code -Dforbric.fabricTooltipBridge=off} leaves fabric-item-api's tooltip injectors where they were. */
	public static final String FABRIC_TOOLTIP_BRIDGE = "forbric.fabricTooltipBridge";

	/** Whether fabric-item-api's five tooltip injectors were removed on this boot — the bridge draws only then. */
	public static boolean fabricTooltipInjectorsPruned() {
		return fabricTooltipsPruned;
	}

	/** Every annotation that makes a mixin method an injector: Mixin's own and MixinExtras'. */
	static final Set<String> INJECTOR_DESCS = Set.of(
			"Lorg/spongepowered/asm/mixin/injection/Inject;",
			"Lorg/spongepowered/asm/mixin/injection/Redirect;",
			"Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
			"Lorg/spongepowered/asm/mixin/injection/ModifyArgs;",
			"Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
			"Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
			"Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;",
			"Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;",
			"Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
			"Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;",
			"Lcom/llamalad7/mixinextras/injector/WrapWithCondition;",
			"Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;");

	private int pruned;

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override
	public String name() {
		return "forbric:guest-injector-pruner";
	}

	@Override
	public AnchorSet anchors() {
		return AnchorSet.of(declaredAnchors(TABLE.keySet(), ACTIVE, COSTS).toArray(new AnchorSet.Anchor[0]));
	}

	/**
	 * Every trim entry active on this boot, as the REQUIRED anchor the audit declares for it.
	 *
	 * <p>This runs inside {@code TransformChain.register}, before a single subject is considered, and it once ran
	 * the whole game into the ground: adding a trim to {@link #TABLE} without its row in {@link #COSTS} made
	 * {@link AnchorSet.Anchor} refuse the anchor, and every boot died with
	 * {@code an anchor without a cost cannot be reported usefully} — a table defect whose blast radius was the
	 * launch. The cost lookup therefore has a loud fallback rather than a null ({@link #costOf}), and the table is
	 * kept complete by {@link #entriesWithoutTheirCost} plus the unit test that reads it. Parameterised so that
	 * test can hand back the same table with one row removed — the pre-fix state — without shipping the gap again.
	 */
	static List<AnchorSet.Anchor> declaredAnchors(Iterable<String> mixins, Map<String, BooleanSupplier> active,
			Map<String, String> costs) {
		List<AnchorSet.Anchor> anchors = new ArrayList<>();
		for (String mixin : mixins) {
			if (!active.get(mixin).getAsBoolean()) continue;
			anchors.add(new AnchorSet.Anchor(mixin, AnchorSet.Severity.REQUIRED, costOf(mixin, costs)));
		}
		return anchors;
	}

	/**
	 * The cost text for a trimmed entry, or a loud placeholder when the table has none.
	 *
	 * <p>Never returns null or blank, because the caller is a boot path and a missing row is this table's defect,
	 * not the game's: it must degrade to a report, not an exception. The placeholder names the mixin so the log
	 * line and the ledger entry both say which row to add.
	 */
	static String costOf(String mixin, Map<String, String> costs) {
		String cost = costs.get(mixin);
		if (cost != null && !cost.isBlank()) return cost;
		ForbricLog.warn("[Forbric/GuestInjectorPruner] %s is a trim entry with NO cost row — its injectors are "
				+ "removed and nothing says what that costs. That is a defect in this table, not a game condition; "
				+ "the boot proceeds with the cost unknown. Add its COSTS entry.", mixin);
		return "cost unknown — " + mixin + " was trimmed with no COSTS row in GuestInjectorPruner; "
				+ "the injector(s) are gone and what that costs is not recorded";
	}

	/** The defect {@link #declaredAnchors} guards: a {@link #TABLE} key with no usable {@link #COSTS} text. */
	static List<String> entriesWithoutTheirCost() {
		List<String> missing = new ArrayList<>();
		for (String mixin : TABLE.keySet()) {
			String cost = COSTS.get(mixin);
			if (cost == null || cost.isBlank()) missing.add(mixin);
		}
		return missing;
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		List<Prune> prunes = TABLE.get(className);
		if (prunes == null || !enabled() || !ACTIVE.get(className).getAsBoolean()) return classBytes;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		if (node.methods == null) return classBytes;

		// Both-or-nothing: the pair only makes sense together. Half of it gone is exactly the half-applied state
		// this class exists to avoid, so any drift in either stands the whole edit down.
		List<MethodNode> victims = new ArrayList<>();
		for (Prune prune : prunes) {
			MethodNode found = null;
			for (MethodNode m : node.methods) {
				if (prune.name().equals(m.name) && prune.desc().equals(m.desc)) { found = m; break; }
			}
			if (found == null) {
				// Absent on a second pass is what idempotence looks like; absent on the first is drift.
				if (alreadyPruned(node, prunes)) {
					if (ITEM_STACK_MIXIN.equals(className)) fabricTooltipsPruned = true;
					return classBytes;
				}
				ForbricLog.warn("[Forbric/GuestInjectorPruner] %s has no %s%s — fabric-api reshaped the mixin, leaving "
						+ "it untouched (%s)", className, prune.name(), prune.desc(), DRIFT.get(className));
				return classBytes;
			}
			if (!(prune.exact() ? isInjectorExactlyInto(found, prune.selectorPrefix()) : isInjectorInto(found, prune.selectorPrefix()))) {
				ForbricLog.warn("[Forbric/GuestInjectorPruner] %s.%s no longer injects into %s — fabric-api reshaped "
						+ "the mixin, leaving it untouched (%s)", className, prune.name(), prune.selectorPrefix(), DRIFT.get(className));
				return classBytes;
			}
			victims.add(found);
		}

		node.methods.removeAll(victims);
		pruned += victims.size();
		if (ITEM_STACK_MIXIN.equals(className)) fabricTooltipsPruned = true;
		// Removed, so never run: a confirmed finding for each where nothing does its job, naming what is not
		// covered. The log line below is not the report.
		String loss = lossOf(className);
		for (MethodNode victim : loss == null ? List.<MethodNode>of() : victims) {
			net.forbric.kernel.mixin.MixinCompatibility.recordRemovedInjector(CONFIGS.get(className), className,
					victim.name, victim.desc, loss,
					List.of("kernel pruned " + victim.name + victim.desc + " from " + className,
							"target selector " + prunes.get(0).selectorPrefix(), "source=GuestInjectorPruner"));
		}
		ForbricLog.info("[Forbric/GuestInjectorPruner] pruned %d injector(s) from %s — %s; the other %d injector(s) "
				+ "apply as written", victims.size(), className, REASONS.get(className), countInjectors(node));

		// Only whole methods were removed: no instruction, frame or local changed, so nothing needs recomputing.
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** Whether {@code m} carries an injector annotation whose {@code method} list has a selector starting with {@code prefix}. */
	static boolean isInjectorInto(MethodNode m, String prefix) {
		for (AnnotationNode a : allAnnotations(m)) {
			if (!INJECTOR_DESCS.contains(a.desc)) continue;
			List<Object> values = a.values;
			if (values == null) continue;
			for (int i = 0; i + 1 < values.size(); i += 2) {
				if (!"method".equals(values.get(i))) continue;
				Object v = values.get(i + 1);
				if (v instanceof List<?> list) {
					for (Object s : list) if (s instanceof String str && str.startsWith(prefix)) return true;
				} else if (v instanceof String str && str.startsWith(prefix)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Whether {@code m} carries an injector annotation whose {@code method} list is exactly {@code selector}, and a
	 * {@code @Share} parameter — the shape of fabric-item-api's five, which only move together.
	 */
	static boolean isInjectorExactlyInto(MethodNode m, String selector) {
		boolean shared = false;
		for (List<AnnotationNode> parameter : m.invisibleParameterAnnotations == null ? new List[0] : m.invisibleParameterAnnotations) {
			if (parameter != null) for (AnnotationNode a : parameter) shared |= "Lcom/llamalad7/mixinextras/sugar/Share;".equals(a.desc);
		}
		for (List<AnnotationNode> parameter : m.visibleParameterAnnotations == null ? new List[0] : m.visibleParameterAnnotations) {
			if (parameter != null) for (AnnotationNode a : parameter) shared |= "Lcom/llamalad7/mixinextras/sugar/Share;".equals(a.desc);
		}
		if (!shared) return false;
		for (AnnotationNode a : allAnnotations(m)) {
			if (!INJECTOR_DESCS.contains(a.desc) || a.values == null) continue;
			for (int i = 0; i + 1 < a.values.size(); i += 2) {
				if (!"method".equals(a.values.get(i))) continue;
				Object v = a.values.get(i + 1);
				if (v instanceof List<?> list) return list.size() == 1 && selector.equals(list.get(0));
				return selector.equals(v);
			}
		}
		return false;
	}

	private static List<AnnotationNode> allAnnotations(MethodNode m) {
		List<AnnotationNode> out = new ArrayList<>();
		if (m.visibleAnnotations != null) out.addAll(m.visibleAnnotations);
		if (m.invisibleAnnotations != null) out.addAll(m.invisibleAnnotations);
		return out;
	}

	private static boolean alreadyPruned(ClassNode node, List<Prune> prunes) {
		for (Prune p : prunes) {
			for (MethodNode m : node.methods) {
				if (p.name().equals(m.name) && p.desc().equals(m.desc)) return false;
			}
		}
		return true;
	}

	private static int countInjectors(ClassNode node) {
		int n = 0;
		for (MethodNode m : node.methods) {
			for (AnnotationNode a : allAnnotations(m)) {
				if (INJECTOR_DESCS.contains(a.desc)) { n++; break; }
			}
		}
		return n;
	}

	/** How many injector methods were removed, for the boot summary. */
	public int prunedInjectors() {
		return pruned;
	}
}
