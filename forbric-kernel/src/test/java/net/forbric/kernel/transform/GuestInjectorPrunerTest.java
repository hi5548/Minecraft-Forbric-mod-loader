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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.kernel.TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.forbric.kernel.mixin.MixinFit;

/**
 * Pins the pruner against the REAL {@code ModelManagerMixin} out of the staged fabric-api jar and the REAL merged
 * {@code ModelManager}, because the whole point is a measured shape: exactly two injectors cannot bind, and the
 * other eight can.
 */
class GuestInjectorPrunerTest {
	private static final Path MERGED_BASE =
			Path.of(System.getenv().getOrDefault("FORBRIC_OLD", System.getProperty("user.dir") + "/../forbric-loader"), "run", "merged-base",
					"patched-mc-merged-26.2.jar").normalize();
	private static final Path CLIENT_MODS =
			Path.of(System.getProperty("user.dir"), "run", "client-kernel", "mods").normalize();
	private static final String MIXIN_ENTRY =
			"net/fabricmc/fabric/mixin/client/model/loading/ModelManagerMixin.class";
	private static final String MODULE = "fabric-model-loading-api-v1";

	/** The ten injectors the real mixin carries, by name; the first two are the pair that cannot fit. */
	private static final Set<String> PRUNED = Set.of("cancelVanillaDeserialize", "actuallyDeserializeModel");
	private static final Set<String> SURVIVING_INJECTORS = Set.of("onHeadReload", "resetEventDispatcherFuture",
			"hookModels", "hookBlockStateModels", "hookModelCollect", "hookModelBaking", "resolveExtraModels",
			"onReturnUpload");

	@AfterEach
	void reset() {
		System.clearProperty(GuestInjectorPruner.PROPERTY);
		System.clearProperty(GuestInjectorPruner.FABRIC_TOOLTIP_BRIDGE);
		System.clearProperty("forbric.neoTooltipAppenders");
		System.clearProperty(ModelFormatFunnelInjector.PROPERTY);
		net.forbric.api.CompatibilityFindings.reset();
	}

	private static final String ITEM_STACK_ENTRY = "net/fabricmc/fabric/mixin/item/ItemStackMixin.class";
	private static final Set<String> TOOLTIP_INJECTORS = Set.of("preAppendComponentTooltip", "preShouldDisplay",
			"preAttributeModifiers", "postTooltipsAdvanced", "postTooltipsNonAdvanced");

	private static byte[] realItemStackMixin() throws Exception {
		Path fabricApi = fabricApiJar();
		assumeTrue(fabricApi != null, "fabric-api jar absent from run/client-kernel/mods");
		byte[] bytes = readFromNestedJar(fabricApi, "fabric-item-api-v1", ITEM_STACK_ENTRY);
		assumeTrue(bytes != null, "ItemStackMixin absent from the nested fabric-item-api-v1 module");
		return bytes;
	}

	/**
	 * fabric-item-api's five tooltip injectors go together — the kernel draws Fabric's providers from NeoForge's
	 * appenders — and nothing is reported for them; the custom-damage hook and the shared helper stay.
	 */
	@Test
	void fabricItemApisTooltipInjectorsGoTogetherAndNothingIsReported() throws Exception {
		net.forbric.api.CompatibilityFindings.reset();
		byte[] original = realItemStackMixin();
		ClassNode before = read(original);
		for (String name : TOOLTIP_INJECTORS) assertNotNull(method(before, name), "premise: the real mixin carries " + name);
		byte[] pruned = new GuestInjectorPruner().transform(GuestInjectorPruner.ITEM_STACK_MIXIN, original, null);
		assertNotSame(original, pruned);
		ClassNode after = read(pruned);
		for (String name : TOOLTIP_INJECTORS) assertEquals(null, method(after, name), name + " must be pruned");
		assertNotNull(method(after, "hookDamage"), "the custom damage handler hook stays");
		assertTrue(isInjector(method(after, "hookDamage")));
		assertNotNull(method(after, "preAppendTooltip"), "the unique helper stays (nothing calls it now)");
		assertEquals(before.methods.size() - TOOLTIP_INJECTORS.size(), after.methods.size());
		assertTrue(GuestInjectorPruner.fabricTooltipInjectorsPruned());
		assertTrue(net.forbric.api.CompatibilityFindings.all().isEmpty(), "the bridge does their job: "
				+ net.forbric.api.CompatibilityFindings.all());
		assertSame(pruned, new GuestInjectorPruner().transform(GuestInjectorPruner.ITEM_STACK_MIXIN, pruned, null),
				"a second pass changes nothing");
	}

	@Test
	void fabricItemApisTooltipInjectorsStayWhileTheBridgeIsOff() throws Exception {
		byte[] original = realItemStackMixin();
		for (String off : List.of(GuestInjectorPruner.FABRIC_TOOLTIP_BRIDGE, "forbric.neoTooltipAppenders")) {
			System.setProperty(off, "off");
			assertSame(original, new GuestInjectorPruner().transform(GuestInjectorPruner.ITEM_STACK_MIXIN, original, null), off);
			System.clearProperty(off);
		}
	}

	/** {@code addDetailsToTooltip} is also the prefix of NeoForge's two renamed bodies; the match is the whole selector. */
	@Test
	void aTooltipInjectorAlreadyMovedOrWithoutItsSharedIndexIsNotGuessed() throws Exception {
		ClassNode moved = read(realItemStackMixin());
		MethodNode first = method(moved, "preShouldDisplay");
		for (AnnotationNode a : first.visibleAnnotations) {
			if (!GuestInjectorPruner.INJECTOR_DESCS.contains(a.desc)) continue;
			for (int i = 0; i + 1 < a.values.size(); i += 2) {
				if ("method".equals(a.values.get(i))) a.values.set(i + 1, List.of("addDetailsToTooltipComponents"));
			}
		}
		byte[] drifted = write(moved);
		assertSame(drifted, new GuestInjectorPruner().transform(GuestInjectorPruner.ITEM_STACK_MIXIN, drifted, null));

		ClassNode unshared = read(realItemStackMixin());
		MethodNode nonAdvanced = method(unshared, "postTooltipsNonAdvanced");
		nonAdvanced.invisibleParameterAnnotations = null;
		nonAdvanced.visibleParameterAnnotations = null;
		byte[] drifted2 = write(unshared);
		assertSame(drifted2, new GuestInjectorPruner().transform(GuestInjectorPruner.ITEM_STACK_MIXIN, drifted2, null));
	}

	/**
	 * The pair's job — dispatching {@code fabric:type} — is the model-format funnel's inside NeoForge's own
	 * deserializer, so removing them loses nothing and nothing is reported. Traveler's Backpack's backpacks are
	 * {@code fabric:type} models; a finding here would have marked a working mod as degraded.
	 */
	@Test
	void whileTheModelFormatFunnelIsOnThePrunedPairReportsNothing() throws Exception {
		net.forbric.api.CompatibilityFindings.reset();
		byte[] pruned = new GuestInjectorPruner().transform(GuestInjectorPruner.MODEL_MANAGER_MIXIN, realMixin(), null);
		for (String gone : PRUNED) assertEquals(null, method(read(pruned), gone), gone + " is still pruned");
		assertTrue(net.forbric.api.CompatibilityFindings.all().isEmpty(), "the funnel does their job: "
				+ net.forbric.api.CompatibilityFindings.all());
	}

	/**
	 * With the funnel off the pruned pair never runs, and a log line was all that said so. Each is a confirmed
	 * finding on the owning config, naming the residual loss — and not a continue-or-quit question, since the
	 * kernel ships this trim.
	 */
	@Test
	void eachPrunedInjectorIsAConfirmedFindingThatAsksNothing() throws Exception {
		net.forbric.api.CompatibilityFindings.reset();
		System.setProperty(ModelFormatFunnelInjector.PROPERTY, "off");
		new GuestInjectorPruner().transform(GuestInjectorPruner.MODEL_MANAGER_MIXIN, realMixin(), null);

		String config = GuestInjectorPruner.CONFIGS.get(GuestInjectorPruner.MODEL_MANAGER_MIXIN);
		assertTrue(net.forbric.kernel.mixin.MergedBaseMixinCompat.SUPPRESSED_UNLESS_PRUNED.contains(config + ":ModelManagerMixin"),
				"the config named here is the one the whole-mixin pin names");
		var findings = net.forbric.api.CompatibilityFindings.all();
		for (String gone : PRUNED) {
			var finding = findings.stream().filter(f -> f.id().startsWith("mixin-injector:" + config + ":"
					+ GuestInjectorPruner.MODEL_MANAGER_MIXIN + "#" + gone + "(")).findFirst()
					.orElseThrow(() -> new AssertionError("no finding for pruned " + gone + ": " + findings));
			assertEquals(net.forbric.api.CompatibilityFinding.Confidence.CONFIRMED, finding.confidence());
			assertFalse(finding.required());
			assertTrue(finding.detail().contains("fabric:type"), finding.detail());
		}
		assertEquals(PRUNED.size(), findings.size(), "nothing else is reported: " + findings);
		assertTrue(net.forbric.api.CompatibilityFindings.confirmedRequired().isEmpty());
	}

	@Test
	void prunesExactlyTheTwoDeserializerInjectorsAndKeepsTheRest() throws Exception {
		byte[] original = realMixin();
		byte[] pruned = new GuestInjectorPruner().transform(GuestInjectorPruner.MODEL_MANAGER_MIXIN, original, null);
		assertNotSame(original, pruned, "the real mixin must be edited");

		ClassNode before = read(original);
		ClassNode after = read(pruned);
		for (String gone : PRUNED) {
			assertNotNull(method(before, gone), "premise: the real mixin carries " + gone);
			assertEquals(null, method(after, gone), gone + " must be pruned");
		}
		for (String kept : SURVIVING_INJECTORS) {
			MethodNode m = method(after, kept);
			assertNotNull(m, kept + " must survive");
			assertTrue(isInjector(m), kept + " must keep its injector annotation");
		}
		assertEquals(before.methods.size() - PRUNED.size(), after.methods.size(),
				"exactly the two are removed, nothing else — lambdas and helpers included");

		// The tell of the half-applied state: nothing left in the mixin may name the two call sites.
		for (MethodNode m : after.methods) {
			for (String target : atTargets(m)) {
				assertFalse(target.contains("Pair;of"), m.name + " still targets Pair.of: " + target);
				assertFalse(target.contains("fromStream"), m.name + " still targets fromStream: " + target);
			}
		}
	}

	/**
	 * The premise and the payoff in one place: on the original bytes MixinFit reads PARTIAL with {@code fromStream}
	 * among the misses; on the pruned bytes it reads FIT. If the merged base ever grows {@code fromStream} back,
	 * this is the test that says the pruner has become unnecessary.
	 */
	@Test
	void thePrunedMixinFitsTheMergedModelManagerWhereTheOriginalWasPartial() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		byte[] original = realMixin();
		Function<String, byte[]> resolver = mergedBaseResolver();

		MixinFit.Result was = MixinFit.evaluate(original, resolver);
		assertEquals(MixinFit.Verdict.PARTIAL, was.verdict(), "premise: " + was.unresolved());
		assertTrue(was.unresolved().stream().anyMatch(u -> u.contains("fromStream")),
				"premise: the miss is the @Redirect on fromStream: " + was.unresolved());

		byte[] pruned = new GuestInjectorPruner().transform(GuestInjectorPruner.MODEL_MANAGER_MIXIN, original, null);
		MixinFit.Result now = MixinFit.evaluate(pruned, resolver);
		// Pruning removes the fromStream miss. What remains is resolveExtraModels: Mixin binds its bare
		// "discoverModelDependencies" to the carrier's three-argument stub declared first, and resolve() is in the
		// four-argument body — MixinStubRebind's to move, for a Fabric mod, at load time. resolve() is
		// ModelDiscovery's, and the report names it so, in full.
		assertEquals(java.util.List.of("@At(INVOKE) net.minecraft.client.resources.model.ModelDiscovery.resolve in "
				+ "ModelManager.discoverModelDependencies"),
				now.unresolved(), "after pruning, only the stub-bound anchor remains");
		ClassNode node = net.forbric.kernel.mixin.MixinFit.parse(pruned);
		net.forbric.kernel.mixin.MixinStubRebindAccess.fabric(node.name);
		ClassNode target = new ClassNode();
		new ClassReader(resolver.apply("net/minecraft/client/resources/model/ModelManager.class")).accept(target, 0);
		assertEquals(1, net.forbric.kernel.mixin.MixinStubRebind.adapt(node, name -> target));
		org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
		node.accept(writer);
		MixinFit.Result rebound = MixinFit.evaluate(writer.toByteArray(), resolver);
		assertEquals(MixinFit.Verdict.FIT, rebound.verdict(), "after the rebind: " + rebound.unresolved());
		net.forbric.kernel.mixin.MixinStubRebindAccess.forget();
	}

	@Test
	void everyRemainingMethodStillVerifies() throws Exception {
		byte[] pruned = new GuestInjectorPruner().transform(GuestInjectorPruner.MODEL_MANAGER_MIXIN, realMixin(), null);
		ClassNode after = read(pruned);
		for (MethodNode m : after.methods) {
			if (m.instructions.size() == 0) continue;
			new Analyzer<>(new BasicVerifier()).analyze(after.name, m);
		}
	}

	/** Half the pair gone is the exact state this class exists to avoid, so any drift stands the whole edit down. */
	@Test
	void bothOrNothing_aRenamedRedirectLeavesTheMixinUntouched() throws Exception {
		ClassNode node = read(realMixin());
		method(node, "cancelVanillaDeserialize").name = "cancelVanillaDeserializeRenamed";
		byte[] drifted = write(node);

		assertSame(drifted, new GuestInjectorPruner().transform(GuestInjectorPruner.MODEL_MANAGER_MIXIN, drifted, null));
	}

	/** And the same when the method exists but no longer injects into the lambda the pruner is reasoning about. */
	@Test
	void bothOrNothing_aRetargetedInjectorLeavesTheMixinUntouched() throws Exception {
		ClassNode node = read(realMixin());
		MethodNode arg = method(node, "actuallyDeserializeModel");
		for (AnnotationNode a : arg.visibleAnnotations) {
			if (!GuestInjectorPruner.INJECTOR_DESCS.contains(a.desc)) continue;
			for (int i = 0; i + 1 < a.values.size(); i += 2) {
				if ("method".equals(a.values.get(i))) a.values.set(i + 1, List.of("somewhereElse"));
			}
		}
		byte[] drifted = write(node);

		assertSame(drifted, new GuestInjectorPruner().transform(GuestInjectorPruner.MODEL_MANAGER_MIXIN, drifted, null));
	}

	/**
	 * The third entry (fabric-lifecycle-events-v1's {@code WorldChunkMixin}). Its standalone {@code @Redirect} on
	 * the {@code Map.remove} in {@code LevelChunk.getBlockEntity} cannot attach — the merged method runs both
	 * {@code Map.remove}s before {@code createBlockEntity}, so the disambiguating slice is empty — and the static
	 * preflight does not model that, so only the post-application audit reports it. Pruning turns it into a
	 * confirmed finding that asks nothing, and the loss is named. Synthetic bytes: the measured shape, no fixture.
	 */
	@Test
	void theWorldChunkRedirectIsPrunedIntoAConfirmedFindingThatAsksNothing() throws Exception {
		net.forbric.api.CompatibilityFindings.reset();
		String redirectDesc = "(Ljava/util/Map;Ljava/lang/Object;)Ljava/lang/Object;";
		byte[] original = worldChunkMixin("Lnet/minecraft/world/level/chunk/LevelChunk;getBlockEntity"
				+ "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/chunk/LevelChunk$EntityCreationType;)"
				+ "Lnet/minecraft/world/level/block/entity/BlockEntity;");
		ClassNode before = read(original);
		assertNotNull(methodByDesc(before, "onRemoveBlockEntity", redirectDesc), "premise: the redirect exists");
		assertEquals(4, before.methods.size(), "premise: the redirect plus the three handlers");

		byte[] pruned = new GuestInjectorPruner().transform(GuestInjectorPruner.WORLD_CHUNK_MIXIN, original, null);
		assertNotSame(original, pruned, "the redirect must be pruned");
		ClassNode after = read(pruned);
		assertEquals(null, methodByDesc(after, "onRemoveBlockEntity", redirectDesc), "the standalone redirect goes");
		assertNotNull(methodByDesc(after, "onRemoveBlockEntity",
				"(Lnet/minecraft/world/level/block/entity/BlockEntity;"
						+ "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;"
						+ "Lnet/minecraft/world/level/block/entity/BlockEntity;)V"),
				"the @Inject handler that happens to share the name stays");
		assertNotNull(methodByDesc(after, "onLoadBlockEntity",
				"(Lnet/minecraft/world/level/block/entity/BlockEntity;"
						+ "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;"
						+ "Lnet/minecraft/world/level/block/entity/BlockEntity;)V"),
				"the Load handler stays");

		String config = GuestInjectorPruner.CONFIGS.get(GuestInjectorPruner.WORLD_CHUNK_MIXIN);
		var findings = net.forbric.api.CompatibilityFindings.all();
		var finding = findings.stream().filter(f -> f.id().startsWith("mixin-injector:" + config + ":"
				+ GuestInjectorPruner.WORLD_CHUNK_MIXIN + "#onRemoveBlockEntity" + redirectDesc)).findFirst()
				.orElseThrow(() -> new AssertionError("no finding for the pruned redirect: " + findings));
		assertEquals(net.forbric.api.CompatibilityFinding.Confidence.CONFIRMED, finding.confidence());
		assertFalse(finding.required(), "a pruned injector is a loss the kernel ships, not a continue-or-quit");
		assertTrue(finding.detail().contains("BLOCK_ENTITY_UNLOAD"), finding.detail());
		assertTrue(net.forbric.api.CompatibilityFindings.confirmedRequired().isEmpty());

		assertSame(pruned, new GuestInjectorPruner().transform(GuestInjectorPruner.WORLD_CHUNK_MIXIN, pruned, null),
				"a second pass changes nothing");
	}

	/** The same standalone redirect, but no longer injecting into the method the entry reasons about: stand down. */
	@Test
	void aMovedWorldChunkSelectorLeavesTheMixinUntouched() throws Exception {
		byte[] drifted = worldChunkMixin("Lnet/minecraft/world/level/chunk/LevelChunk;createBlockEntity");
		assertSame(drifted, new GuestInjectorPruner().transform(GuestInjectorPruner.WORLD_CHUNK_MIXIN, drifted, null));
	}

	/**
	 * The fourth entry (fabric-item-api-v1's {@code BrewingStandBlockEntityMixin}). The merged {@code doBrew}
	 * moved the crafting-remainder query from {@code Item} to {@code ItemStack} and constructs no {@code ItemStack},
	 * so only {@code captureItemStack} (the {@code ItemStack.shrink} inject) can bind. Synthetic bytes.
	 */
	@Test
	void theBrewingStandPairIsPrunedAndItsWorkingInjectorStays() throws Exception {
		net.forbric.api.CompatibilityFindings.reset();
		byte[] original = brewingStandMixin();
		ClassNode before = read(original);
		assertNotNull(methodByDesc(before, "hasStackRecipeRemainder", "(Lnet/minecraft/world/item/Item;)Z"));
		assertEquals(3, before.methods.size(), "premise: one working injector and the two that cannot bind");

		byte[] pruned = new GuestInjectorPruner().transform(GuestInjectorPruner.BREWING_STAND_MIXIN, original, null);
		assertNotSame(original, pruned);
		ClassNode after = read(pruned);
		assertEquals(null, methodByDesc(after, "hasStackRecipeRemainder", "(Lnet/minecraft/world/item/Item;)Z"));
		assertEquals(null, methodByDesc(after, "createStackRecipeRemainder",
				"(Lnet/minecraft/world/level/ItemLike;"
						+ "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)"
						+ "Lnet/minecraft/world/item/ItemStack;"));
		assertNotNull(methodByDesc(after, "captureItemStack",
				"(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/NonNullList;"
						+ "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;"
						+ "Lnet/minecraft/world/item/ItemStack;)V"), "the ItemStack.shrink inject stays");

		String config = GuestInjectorPruner.CONFIGS.get(GuestInjectorPruner.BREWING_STAND_MIXIN);
		var findings = net.forbric.api.CompatibilityFindings.all();
		for (String gone : List.of("hasStackRecipeRemainder", "createStackRecipeRemainder")) {
			var finding = findings.stream().filter(f -> f.id().startsWith("mixin-injector:" + config + ":"
					+ GuestInjectorPruner.BREWING_STAND_MIXIN + "#" + gone + "(")).findFirst()
					.orElseThrow(() -> new AssertionError("no finding for pruned " + gone + ": " + findings));
			assertEquals(net.forbric.api.CompatibilityFinding.Confidence.CONFIRMED, finding.confidence(), gone);
			assertFalse(finding.required(), gone);
			assertTrue(finding.detail().contains("crafting-remainder"), finding.detail());
		}
		assertTrue(net.forbric.api.CompatibilityFindings.confirmedRequired().isEmpty());
		assertSame(pruned, new GuestInjectorPruner().transform(GuestInjectorPruner.BREWING_STAND_MIXIN, pruned, null),
				"a second pass changes nothing");
	}

	/**
	 * The anvil and ingredient single-injector stand-downs: the dead injector is pruned, its working sibling stays,
	 * and the loss is recorded (CONFIRMED, required=false) with the cost named. Synthetic bytes.
	 */
	@Test
	void theAnvilAndIngredientDeadInjectorsArePrunedAndTheirSiblingsStay() throws Exception {
		net.forbric.api.CompatibilityFindings.reset();
		checkPrunedDeadInjector(oneDeadInjector(GuestInjectorPruner.ANVIL_HANDLER_MIXIN, "net.minecraft.world.inventory.AnvilMenu",
				"callAllowEnchantingEvent", "(Lnet/minecraft/world/item/enchantment/Enchantment;"
						+ "Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/Holder;)Z",
				"Lorg/spongepowered/asm/mixin/injection/Redirect;",
				"Lnet/minecraft/world/inventory/AnvilMenu;createResult()V", "INVOKE",
				"Lnet/minecraft/world/item/enchantment/Enchantment;canEnchant(Lnet/minecraft/world/item/ItemStack;)Z", "keepMe"),
				GuestInjectorPruner.ANVIL_HANDLER_MIXIN, "callAllowEnchantingEvent",
				"(Lnet/minecraft/world/item/enchantment/Enchantment;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/Holder;)Z",
				"keepMe", "AllowEnchanting");

		net.forbric.api.CompatibilityFindings.reset();
		checkPrunedDeadInjector(oneDeadInjector(GuestInjectorPruner.INGREDIENT_MIXIN, "net.minecraft.world.item.crafting.Ingredient",
				"useCustomIngredientPacketCodec",
				"(Lnet/minecraft/network/codec/StreamCodec;)Lnet/minecraft/network/codec/StreamCodec;",
				"Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;",
				"Lnet/minecraft/world/item/crafting/Ingredient;<clinit>()V", "INVOKE",
				"Lnet/minecraft/network/codec/StreamCodec;map(Ljava/util/function/Function;Ljava/util/function/Function;)"
						+ "Lnet/minecraft/network/codec/StreamCodec;",
				"injectCodec"), GuestInjectorPruner.INGREDIENT_MIXIN, "useCustomIngredientPacketCodec",
				"(Lnet/minecraft/network/codec/StreamCodec;)Lnet/minecraft/network/codec/StreamCodec;",
				"injectCodec", "custom Ingredient packet codec");
	}

	private static void checkPrunedDeadInjector(byte[] original, String mixin, String dead, String deadDesc,
			String survivor, String detailNeedle) {
		byte[] pruned = new GuestInjectorPruner().transform(mixin, original, null);
		assertNotSame(original, pruned, dead + " must be pruned");
		ClassNode after = read(pruned);
		assertEquals(null, methodByDesc(after, dead, deadDesc), dead + " goes");
		assertNotNull(method(after, survivor), survivor + " (the working sibling) stays");
		String config = GuestInjectorPruner.CONFIGS.get(mixin);
		var finding = net.forbric.api.CompatibilityFindings.all().stream()
				.filter(f -> f.id().startsWith("mixin-injector:" + config + ":" + mixin + "#" + dead + "(")).findFirst()
				.orElseThrow(() -> new AssertionError("no finding for pruned " + dead));
		assertEquals(net.forbric.api.CompatibilityFinding.Confidence.CONFIRMED, finding.confidence());
		assertFalse(finding.required(), dead + " is a shipped loss, not a continue-or-quit");
		assertTrue(finding.detail().contains(detailNeedle), finding.detail());
		assertTrue(net.forbric.api.CompatibilityFindings.confirmedRequired().isEmpty());
	}

	private static byte[] oneDeadInjector(String mixinClass, String target, String deadName, String deadDesc,
			String annotation, String selector, String atValue, String atTarget, String survivor) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, mixinClass, null, "java/lang/Object", null);
		org.objectweb.asm.AnnotationVisitor mixin = writer.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", true);
		org.objectweb.asm.AnnotationVisitor targets = mixin.visitArray("targets");
		targets.visit(null, target);
		targets.visitEnd();
		mixin.visitEnd();
		injector(writer, deadName, deadDesc, annotation, selector, atValue, atTarget);
		injector(writer, survivor, "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V",
				"Lorg/spongepowered/asm/mixin/injection/Inject;", selector, "TAIL", atTarget);
		writer.visitEnd();
		return writer.toByteArray();
	}

	@Test
	void aSecondPassChangesNothingFurther() throws Exception {
		GuestInjectorPruner pruner = new GuestInjectorPruner();
		byte[] once = pruner.transform(GuestInjectorPruner.MODEL_MANAGER_MIXIN, realMixin(), null);
		assertSame(once, pruner.transform(GuestInjectorPruner.MODEL_MANAGER_MIXIN, once, null));
		assertEquals(2, pruner.prunedInjectors(), "the count is of injectors removed, not of passes");
	}

	@Test
	void anUnrelatedClassPassesThroughByIdentity() throws Exception {
		byte[] mixin = realMixin();
		assertSame(mixin, new GuestInjectorPruner().transform("net.fabricmc.fabric.mixin.client.model.loading.Other",
				mixin, null));
	}

	@Test
	void switchedOffItStandsDownAndTheCompatListPinsTheWholeMixin() throws Exception {
		System.setProperty(GuestInjectorPruner.PROPERTY, "off");
		byte[] mixin = realMixin();
		assertSame(mixin, new GuestInjectorPruner().transform(GuestInjectorPruner.MODEL_MANAGER_MIXIN, mixin, null));
		assertFalse(GuestInjectorPruner.enabled());
	}

	// ---------------------------------------------------------------------------------------------------------------

	private static byte[] realMixin() throws Exception {
		Path fabricApi = fabricApiJar();
		assumeTrue(fabricApi != null, "fabric-api jar absent from run/client-kernel/mods");
		byte[] bytes = readFromNestedJar(fabricApi, MODULE, MIXIN_ENTRY);
		assumeTrue(bytes != null, "ModelManagerMixin absent from the nested " + MODULE + " module");
		return bytes;
	}

	private static Function<String, byte[]> mergedBaseResolver() {
		return name -> {
			try {
				return readFromJar(MERGED_BASE, name);
			} catch (Exception e) {
				return null;
			}
		};
	}

	private static ClassNode read(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] write(ClassNode node) {
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static MethodNode method(ClassNode node, String name) {
		for (MethodNode m : node.methods) if (m.name.equals(name)) return m;
		return null;
	}

	private static MethodNode methodByDesc(ClassNode node, String name, String desc) {
		for (MethodNode m : node.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m;
		return null;
	}

	/**
	 * The measured {@code WorldChunkMixin} shape as synthetic bytes: a standalone {@code @Redirect} on
	 * {@code Map.remove} behind a {@code @Slice(from=LevelChunk.createBlockEntity)}, the {@code Load} handler, the
	 * same-named {@code @Inject} unload handler, and an unrelated method. {@code redirectSelector} is a parameter so
	 * a test can move it and watch the edit stand down.
	 */
	private static byte[] worldChunkMixin(String redirectSelector) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, GuestInjectorPruner.WORLD_CHUNK_MIXIN, null, "java/lang/Object", null);

		org.objectweb.asm.AnnotationVisitor mixin = writer.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", true);
		org.objectweb.asm.AnnotationVisitor targets = mixin.visitArray("targets");
		targets.visit(null, "net.minecraft.world.level.chunk.LevelChunk");
		targets.visitEnd();
		mixin.visitEnd();

		org.objectweb.asm.MethodVisitor redirect = writer.visitMethod(Opcodes.ACC_PRIVATE, "onRemoveBlockEntity",
				"(Ljava/util/Map;Ljava/lang/Object;)Ljava/lang/Object;", null, null);
		org.objectweb.asm.AnnotationVisitor r = redirect.visitAnnotation(
				"Lorg/spongepowered/asm/mixin/injection/Redirect;", true);
		org.objectweb.asm.AnnotationVisitor rm = r.visitArray("method");
		rm.visit(null, redirectSelector);
		rm.visitEnd();
		org.objectweb.asm.AnnotationVisitor ra = r.visitArray("at");
		org.objectweb.asm.AnnotationVisitor at = ra.visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/At;");
		at.visit("value", "INVOKE");
		at.visit("target", "Ljava/util/Map;remove(Ljava/lang/Object;)Ljava/lang/Object;");
		at.visitEnd();
		ra.visitEnd();
		org.objectweb.asm.AnnotationVisitor slice = r.visitAnnotation("slice",
				"Lorg/spongepowered/asm/mixin/injection/Slice;");
		org.objectweb.asm.AnnotationVisitor from = slice.visitAnnotation("from",
				"Lorg/spongepowered/asm/mixin/injection/At;");
		from.visit("value", "INVOKE");
		from.visit("target", "Lnet/minecraft/world/level/chunk/LevelChunk;createBlockEntity"
				+ "(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;");
		from.visitEnd();
		slice.visitEnd();
		r.visitEnd();
		redirect.visitCode();
		redirect.visitInsn(Opcodes.ACONST_NULL);
		redirect.visitInsn(Opcodes.ARETURN);
		redirect.visitMaxs(1, 3);
		redirect.visitEnd();

		handler(writer, "onLoadBlockEntity",
				"Lnet/minecraft/world/level/chunk/LevelChunk;setBlockEntity(Lnet/minecraft/world/level/block/entity/BlockEntity;)V");
		handler(writer, "onRemoveBlockEntity",
				"Lnet/minecraft/world/level/chunk/LevelChunk;removeBlockEntity(Lnet/minecraft/core/BlockPos;)V");

		org.objectweb.asm.MethodVisitor unrelated = writer.visitMethod(Opcodes.ACC_PRIVATE, "unrelated", "()V", null, null);
		unrelated.visitCode();
		unrelated.visitInsn(Opcodes.RETURN);
		unrelated.visitMaxs(0, 1);
		unrelated.visitEnd();

		writer.visitEnd();
		return writer.toByteArray();
	}

	private static void handler(ClassWriter writer, String name, String selector) {
		org.objectweb.asm.MethodVisitor m = writer.visitMethod(Opcodes.ACC_PRIVATE, name,
				"(Lnet/minecraft/world/level/block/entity/BlockEntity;"
						+ "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;"
						+ "Lnet/minecraft/world/level/block/entity/BlockEntity;)V", null, null);
		org.objectweb.asm.AnnotationVisitor inject = m.visitAnnotation(
				"Lorg/spongepowered/asm/mixin/injection/Inject;", true);
		org.objectweb.asm.AnnotationVisitor methods = inject.visitArray("method");
		methods.visit(null, selector);
		methods.visitEnd();
		org.objectweb.asm.AnnotationVisitor ats = inject.visitArray("at");
		org.objectweb.asm.AnnotationVisitor at = ats.visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/At;");
		at.visit("value", "RETURN");
		at.visitEnd();
		ats.visitEnd();
		inject.visitEnd();
		m.visitCode();
		m.visitInsn(Opcodes.RETURN);
		m.visitMaxs(0, 4);
		m.visitEnd();
	}

	/** The measured {@code BrewingStandBlockEntityMixin} shape: the working shrink inject plus the two that cannot bind. */
	private static byte[] brewingStandMixin() {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, GuestInjectorPruner.BREWING_STAND_MIXIN, null, "java/lang/Object", null);

		org.objectweb.asm.AnnotationVisitor mixin = writer.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", true);
		org.objectweb.asm.AnnotationVisitor targets = mixin.visitArray("targets");
		targets.visit(null, "net.minecraft.world.level.block.entity.BrewingStandBlockEntity");
		targets.visitEnd();
		mixin.visitEnd();

		String doBrew = "Lnet/minecraft/world/level/block/entity/BrewingStandBlockEntity;doBrew"
				+ "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
				+ "Lnet/minecraft/core/NonNullList;)V";
		injector(writer, "captureItemStack",
				"(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/NonNullList;"
						+ "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;"
						+ "Lnet/minecraft/world/item/ItemStack;)V",
				"Lorg/spongepowered/asm/mixin/injection/Inject;", doBrew, "INVOKE",
				"Lnet/minecraft/world/item/ItemStack;shrink(I)V");
		injector(writer, "hasStackRecipeRemainder", "(Lnet/minecraft/world/item/Item;)Z",
				"Lorg/spongepowered/asm/mixin/injection/Redirect;", doBrew, "INVOKE",
				"Lnet/minecraft/world/item/Item;hasCraftingRemainingItem()Z");
		injector(writer, "createStackRecipeRemainder",
				"(Lnet/minecraft/world/level/ItemLike;"
						+ "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)"
						+ "Lnet/minecraft/world/item/ItemStack;",
				"Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;", doBrew, "NEW",
				"(Lnet/minecraft/class_1935;)Lnet/minecraft/class_1799;");

		writer.visitEnd();
		return writer.toByteArray();
	}

	/** One injector method: a single {@code @At(value, target)} and a body matching the return type. */
	private static void injector(ClassWriter writer, String name, String desc, String annotation, String selector,
			String atValue, String atTarget) {
		org.objectweb.asm.MethodVisitor m = writer.visitMethod(Opcodes.ACC_PRIVATE, name, desc, null, null);
		org.objectweb.asm.AnnotationVisitor a = m.visitAnnotation(annotation, true);
		org.objectweb.asm.AnnotationVisitor methods = a.visitArray("method");
		methods.visit(null, selector);
		methods.visitEnd();
		org.objectweb.asm.AnnotationVisitor ats = a.visitArray("at");
		org.objectweb.asm.AnnotationVisitor at = ats.visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/At;");
		at.visit("value", atValue);
		at.visit("target", atTarget);
		at.visitEnd();
		ats.visitEnd();
		a.visitEnd();
		m.visitCode();
		String ret = desc.substring(desc.lastIndexOf(')') + 1);
		switch (ret) {
			case "V" -> m.visitInsn(Opcodes.RETURN);
			case "Z" -> { m.visitInsn(Opcodes.ICONST_0); m.visitInsn(Opcodes.IRETURN); }
			default -> { m.visitInsn(Opcodes.ACONST_NULL); m.visitInsn(Opcodes.ARETURN); }
		}
		m.visitMaxs(1, desc.startsWith("(") ? 1 + desc.substring(1, desc.indexOf(')')).length() : 1);
		m.visitEnd();
	}

	private static boolean isInjector(MethodNode m) {
		if (m.visibleAnnotations == null) return false;
		for (AnnotationNode a : m.visibleAnnotations) {
			if (GuestInjectorPruner.INJECTOR_DESCS.contains(a.desc)) return true;
		}
		return false;
	}

	/** Every {@code @At(target=…)} string reachable from the method's injector annotations. */
	private static List<String> atTargets(MethodNode m) {
		List<String> out = new ArrayList<>();
		if (m.visibleAnnotations == null) return out;
		for (AnnotationNode a : m.visibleAnnotations) collectTargets(a, out);
		return out;
	}

	private static void collectTargets(AnnotationNode a, List<String> out) {
		if (a.values == null) return;
		for (int i = 0; i + 1 < a.values.size(); i += 2) {
			Object v = a.values.get(i + 1);
			if ("target".equals(a.values.get(i)) && v instanceof String s) out.add(s);
			if (v instanceof AnnotationNode nested) collectTargets(nested, out);
			if (v instanceof List<?> list) {
				for (Object o : list) if (o instanceof AnnotationNode nested) collectTargets(nested, out);
			}
		}
	}

	private static Path fabricApiJar() throws Exception {
		if (Files.isDirectory(CLIENT_MODS)) try (var files = Files.list(CLIENT_MODS)) {
			Path found = files.filter(p -> p.getFileName().toString().startsWith("fabric-api-")).findFirst().orElse(null);
			if (found != null) return found;
		}
		Path pinned = TestFixtures.fabricApi();
		return Files.isRegularFile(pinned) ? pinned : null;
	}

	private static byte[] readFromJar(Path jar, String entry) throws Exception {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry found = zip.getEntry(entry);
			if (found == null) return null;
			try (InputStream in = zip.getInputStream(found)) {
				return in.readAllBytes();
			}
		}
	}

	private static byte[] readFromNestedJar(Path outer, String modulePrefix, String entry) throws Exception {
		try (ZipFile zip = new ZipFile(outer.toFile())) {
			for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
				ZipEntry nested = e.nextElement();
				if (!nested.getName().startsWith("META-INF/jars/" + modulePrefix)) continue;
				Path tmp = Files.createTempFile("forbric-nested", ".jar");
				try (InputStream in = zip.getInputStream(nested)) {
					Files.write(tmp, in.readAllBytes());
				}
				try {
					byte[] bytes = readFromJar(tmp, entry);
					if (bytes != null) return bytes;
				} finally {
					Files.deleteIfExists(tmp);
				}
			}
		}
		return null;
	}

	static { assertTrue(Opcodes.ASM9 > 0); }
}
