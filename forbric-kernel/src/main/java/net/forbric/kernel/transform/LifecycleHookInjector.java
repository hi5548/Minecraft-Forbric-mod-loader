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

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.MergedBaseCalleeSwaps;
import net.forbric.kernel.mixin.MixinRetarget;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.ForbricLog;

/**
 * Redirects the genuine-loader lifecycle trigger the merged base's byte-merge left woven into a vanilla entry
 * point ({@code Main.main}) to the kernel's own native lifecycle hook — so the kernel, not FancyModLoader / FML,
 * owns the lifecycle.
 *
 * <p><b>The merged-base facts.</b> NeoForge won the byte-merge of both entries:
 * <ul>
 *   <li><b>Server</b> — {@code net.minecraft.server.Main.main} calls
 *       {@code net.neoforged.neoforge.server.loading.ServerModLoader.load(Z)V} between {@code Bootstrap.bootStrap()}
 *       and {@code new DedicatedServerSettings(...)}. PORT(1.21.1): the call in that generation is the no-arg
 *       {@code load()V} (NeoForge 21.1 — the same shape MinecraftForge 52 uses); {@link #SERVER_TRIGGERS} carries
 *       both descriptor arms, so either base redirects to the same kernel hook.</li>
 *   <li><b>Client</b> — {@code net.minecraft.client.main.Main.main} calls
 *       {@code net.neoforged.neoforge.client.loading.ClientModLoader.begin()V} at bc 814, after
 *       {@code Bootstrap.validate()} (and after {@code BackgroundWaiter.runAndTick} ran the bootstrap lambda that
 *       froze the registries), and before {@code new Minecraft} → {@code Minecraft.<init>} → the
 *       {@code ClientHooks.initClientHooks} that fires the client mod-bus events (reload listeners, renderers) the
 *       kernel must have populated the {@code ModList} for. This is the exact client analogue of the server's
 *       {@code ServerModLoader.load} site — a plain owner+name swap (no args).</li>
 * </ul>
 * Invoking either would run the genuine mod-loading lifecycle (discovery, module layer, {@code LoadingModList},
 * RegisterEvent dispatch) — the sovereign machinery the kernel replaces.
 *
 * <p><b>The redirect.</b> Each such {@code invokestatic} is retargeted (owner + name) to the matching
 * {@code net.forbric.kernel.boot.KernelLifecycle} hook, keeping the descriptor so the argument on the stack is
 * preserved. The kernel drives its native registration there, at exactly the point the genuine loader would have.
 * The other client mod-loader calls ({@code ClientModLoader.finish/completeModLoading/setupModResourcePacks}) are
 * stubbed separately (see {@code KernelClientLaunch}'s {@code MethodBodyNeuter} targets).
 *
 * <p><b>Fail-loud.</b> If the targeted entry is transformed but no known trigger is found (the merged base moved
 * the call), {@link #missedRequiredExcision()} reports it so the kernel refuses to boot rather than silently
 * firing a genuine lifecycle or leaving the entry un-hooked.
 *
 * <p><b>Fabric's own marker.</b> On the server, the redirected call is followed by
 * {@code Hooks.startServer(null, null)} — the call Fabric Loader patches into {@code Main.main} and mods anchor on
 * as "every mod has initialised" (owo freezes its channels right after it). It goes after the kernel's window, not at
 * Fabric's literal spot after {@code Bootstrap.validate}, because only here is that post-condition true in the
 * kernel; see {@code net.fabricmc.loader.impl.game.minecraft.Hooks}. {@code -Dforbric.fabricHooks=off} leaves it out.
 *
 * <p><b>The client's first failure.</b> The client's {@code Main.main} opens with three steps in handlers of their
 * own -- {@code SharedConstants.tryDetectVersion()}, {@code new OptionParser()} and {@code parser.parse(args)} --
 * each of which calls {@code logEarlyException}, which prints the throwable to stderr, and then exits (status 249,
 * 252, 251). Nothing leaves {@code main}, so {@code CompatibilityLaunchBoundary} never sees it, and a launcher shows
 * {@code latest.log}, not stderr. {@code logEarlyException} therefore hands the throwable to {@code KernelLifecycle.onEarlyStartupFailure}
 * first; the print and the exit are vanilla's and stay. The dedicated server needs nothing: its
 * {@code tryDetectVersion} is outside any handler, and what it throws leaves {@code main} for the boundary.
 */
public final class LifecycleHookInjector implements ClassTransformer {

	/** Merged-base dedicated-server entry. */
	public static final String SERVER_MAIN = "net.minecraft.server.Main";
	/** Merged-base client entry. */
	public static final String CLIENT_MAIN = "net.minecraft.client.main.Main";

	private static final String KERNEL_HOOK_OWNER = "net/forbric/kernel/boot/KernelLifecycle";
	/**
	 * PORT(1.21.1): the game-side bridge the 1.21.1 client redirect lands on, because the trigger's descriptor
	 * (three game types) has to be preserved and {@code KernelLifecycle} is boot-side.
	 */
	static final String CLIENT_LIFECYCLE_BRIDGE = "net/forbric/kernel/runtime/KernelClientLifecycle";

	/** The client {@code Main}'s handler for its first three steps: {@code private static (Throwable)V}. */
	static final String EARLY_FAILURE = "logEarlyException";
	static final String EARLY_FAILURE_DESC = "(Ljava/lang/Throwable;)V";
	/** The kernel hook {@link #EARLY_FAILURE} calls first, with the same descriptor. */
	static final String EARLY_FAILURE_HOOK = "onEarlyStartupFailure";

	/** Fabric Loader's hook class, whose calls mods anchor on. Shipped by the kernel, parent-loaded. */
	static final String FABRIC_HOOKS = "net/fabricmc/loader/impl/game/minecraft/Hooks";
	/** {@code Hooks.startServer} / {@code Hooks.startClient}: {@code (File runDir, Object gameInstance)}. */
	static final String FABRIC_HOOK_DESC = "(Ljava/io/File;Ljava/lang/Object;)V";
	/** {@code -Dforbric.fabricHooks=off}: emit neither Fabric hook call, exactly the bytecode from before they existed. */
	public static final String FABRIC_HOOKS_SWITCH = "forbric.fabricHooks";

	/** Whether the entries carry Fabric's {@code Hooks.startServer}/{@code startClient} calls. Read per transform. */
	static boolean fabricHooksEnabled() {
		return !"off".equalsIgnoreCase(System.getProperty(FABRIC_HOOKS_SWITCH, "on"));
	}

	/**
	 * A genuine mod-loading trigger the byte-merge can leave in an entry, and the kernel hook it redirects to.
	 *
	 * <p>{@code popSlots} = how many argument slots to POP before the (no-arg) kernel hook, when the trigger's
	 * descriptor carries arguments the hook does not want. When {@code popSlots == 0} the descriptor is kept and it
	 * is a plain owner+name swap (the argument stays on the stack for a matching hook signature).
	 */
	/**
	 * @param owner     the class holding the genuine loader call, in the family {@code ecosystem} belongs to
	 * @param ecosystem the family whose jar makes that call — also the family a guest was compiled against when its
	 *                  anchor names {@code owner}, which is how {@link MergedBaseCalleeSwaps#substitution} filters
	 */
	private record Trigger(String owner, String name, String desc, String hookName, int popSlots, Ecosystem ecosystem) {}

	// Server triggers, both families. One descriptor arm matches a given merged base, so a base that carries both
	// families' calls still triggers exactly once (the caller also marks the method after the first redirect).
	//
	// PORT(1.21.1): NeoForge 26.2 widened this entry to load(Z)V, but NeoForge 21.1 — the one the 1.21.1 merged
	// base carries — still has the original no-arg load()V, the same shape MinecraftForge 52 uses. Measured on the
	// 1.21.1 merged base: Main.main's only loader call is `invokestatic
	// net/neoforged/neoforge/server/loading/ServerModLoader.load:()V` at bc 453, between Bootstrap.bootStrap() and
	// new DedicatedServerSettings(...). Without this arm the anchor reads as "gone" on 1.21.1 and the kernel
	// (correctly) refuses to boot rather than let the genuine lifecycle run. popSlots 0: no-arg trigger, no-arg hook.
	private static final Trigger[] SERVER_TRIGGERS = {
			new Trigger(ForeignType.SERVER_MOD_LOADER.internal(Ecosystem.NEOFORGE), "load", "(Z)V", "onServerModLoading", 0, Ecosystem.NEOFORGE),
			new Trigger(ForeignType.SERVER_MOD_LOADER.internal(Ecosystem.NEOFORGE), "load", "()V", "onServerModLoadingNoArg", 0, Ecosystem.NEOFORGE),
			new Trigger(ForeignType.SERVER_MOD_LOADER.internal(Ecosystem.FORGE), "load", "()V", "onServerModLoadingNoArg", 0, Ecosystem.FORGE),
	};

	// Client trigger: ClientModLoader.begin()V in net.minecraft.client.main.Main.main — the EXACT client analogue of
	// the server's ServerModLoader.load redirect in the dedicated-server Main.main. bc 814, after Bootstrap.validate
	// (811) and after BackgroundWaiter.runAndTick (796) has already run the bootstrap lambda (registries frozen), and
	// BEFORE `new Minecraft` (1613) → Minecraft.<init> → ClientHooks.initClientHooks (which fires the client mod-bus
	// events onClientModLoading must have populated the ModList for). No-arg swap (popSlots 0). Class name differs by
	// family (Neo/Forge).
	//
	// (History: an earlier M5 attempt redirected setupModResourcePacks(PackRepository) in Minecraft.<init> instead,
	// with begin() neutered — that HANGS before Minecraft.<init>, because neutering begin() defers onClientModLoading
	// to a point never reached. Redirecting begin() here mirrors the proven server path and reaches the window.)
	private static final String BEGIN = "begin";
	private static final Trigger[] CLIENT_TRIGGERS = {
			new Trigger(ForeignType.CLIENT_MOD_LOADER.internal(Ecosystem.NEOFORGE), BEGIN, "()V", "onClientModLoading", 0, Ecosystem.NEOFORGE),
			new Trigger(ForeignType.CLIENT_MOD_LOADER.internal(Ecosystem.FORGE), BEGIN, "()V", "onClientModLoading", 0, Ecosystem.FORGE),
	};

	// PORT(1.21.1): the client's genuine-loader entry MOVED. 26.2 wove ClientModLoader.begin()V into
	// net.minecraft.client.main.Main.main; on 1.21.1 both families' begin takes the three live objects —
	// begin(Minecraft, PackRepository, ReloadableResourceManager) — and is called from Minecraft.<init> before the
	// first resource reload. Measured on the 1.21.1 merged base: Main.main calls no ClientModLoader at all, and
	// Minecraft.<init> calls net/neoforged/neoforge/client/loading/ClientModLoader.begin at bc 945. The hook keeps
	// the descriptor (popSlots 0), so it takes the three arguments and can serve the pack repository itself.
	public static final String CLIENT_INIT = "net.minecraft.client.Minecraft";
	private static final String CLIENT_BEGIN_DESC = "(Lnet/minecraft/client/Minecraft;"
			+ "Lnet/minecraft/server/packs/repository/PackRepository;"
			+ "Lnet/minecraft/server/packs/resources/ReloadableResourceManager;)V";
	private static final Trigger[] CLIENT_INIT_TRIGGERS = {
			new Trigger(ForeignType.CLIENT_MOD_LOADER.internal(Ecosystem.NEOFORGE), BEGIN, CLIENT_BEGIN_DESC,
					"onClientModLoadingWithPacks", 0, Ecosystem.NEOFORGE),
			new Trigger(ForeignType.CLIENT_MOD_LOADER.internal(Ecosystem.FORGE), BEGIN, CLIENT_BEGIN_DESC,
					"onClientModLoadingWithPacks", 0, Ecosystem.FORGE),
	};

	/** No trigger arms: a reporting-only registration (see {@link #forClientEarlyFailures()}). */
	private static final Trigger[] NO_TRIGGERS = {};

	// The class + method carrying the trigger to rewrite. Server: Main.main. Client: Main.main (client entry), where
	// ClientModLoader.begin() is woven — the exact analogue of the server's ServerModLoader.load site.
	private final String transformClass;
	private final String transformMethod;
	private final Trigger[] triggers;
	/**
	 * The class the redirect targets. The boot-side {@code KernelLifecycle} answers no-argument calls; a trigger
	 * whose descriptor must be preserved onto a game-typed hook (the 1.21.1 client) points at the game-side bridge
	 * instead, because a boot class cannot declare {@code Minecraft} in a signature.
	 */
	private final String hookOwner;
	/**
	 * Whether a redirected trigger is followed by Fabric's {@code Hooks.startServer}. Server only: the client's
	 * equivalent, {@code Hooks.startClient}, belongs in {@code Minecraft.<init>} and is emitted there by
	 * {@code ClientEntrypointHookInjector}.
	 */
	private final boolean fabricServerHook;
	/** Whether {@link #EARLY_FAILURE} reports to the kernel first. Client only; see the class comment. */
	private final boolean reportsEarlyFailures;

	private volatile boolean transformedRequiredEntry;
	private volatile boolean redirectedAtRequiredEntry;

	private LifecycleHookInjector(String transformClass, String transformMethod, Trigger[] triggers,
			boolean fabricServerHook, boolean reportsEarlyFailures, String hookOwner) {
		this.transformClass = transformClass;
		this.transformMethod = transformMethod;
		this.triggers = triggers;
		this.hookOwner = hookOwner;
		this.fabricServerHook = fabricServerHook;
		this.reportsEarlyFailures = reportsEarlyFailures;
	}

	/** The injector for the dedicated-server entry ({@code Main.main}). */
	public static LifecycleHookInjector forServer() {
		return new LifecycleHookInjector(SERVER_MAIN, "main", SERVER_TRIGGERS, true, false, KERNEL_HOOK_OWNER);
	}

	/** The injector for the client ({@code net.minecraft.client.main.Main.main}). */
	public static LifecycleHookInjector forClient() {
		return new LifecycleHookInjector(CLIENT_MAIN, "main", CLIENT_TRIGGERS, false, true, KERNEL_HOOK_OWNER);
	}

	/**
	 * The client lifecycle injector for 1.21.1, whose trigger is the three-argument {@code begin} called from
	 * {@code Minecraft.<init>} rather than {@code Main.main}. The trigger keeps its descriptor, so the redirect
	 * lands on the game-side bridge (see {@link #CLIENT_LIFECYCLE_BRIDGE}). The missed-excision gate then judges the
	 * class that actually carries the caller.
	 */
	public static LifecycleHookInjector forClientMinecraftInit() {
		return new LifecycleHookInjector(CLIENT_INIT, "<init>", CLIENT_INIT_TRIGGERS, false, false,
				CLIENT_LIFECYCLE_BRIDGE);
	}

	/**
	 * The client entry with NO trigger arms: patch {@code Main.logEarlyException} so a failure in Main's first three
	 * steps reaches the kernel's log, but expect no redirect. Used alongside {@link #forClientMinecraftInit()} on
	 * 1.21.1, where the lifecycle trigger is not in this class (26.2's {@link #forClient()} covers both).
	 */
	public static LifecycleHookInjector forClientEarlyFailures() {
		return new LifecycleHookInjector(CLIENT_MAIN, "main", NO_TRIGGERS, false, true, KERNEL_HOOK_OWNER);
	}

	/**
	 * Whether {@code Main.main} itself carries a genuine client lifecycle trigger — i.e. whether this base is the
	 * 26.2 shape. Probes the merged-base bytes rather than a version string; a base the resolver cannot read keeps
	 * the 26.2 reading, which is the one whose gate is strongest.
	 */
	public static boolean clientEntryCarriesTheTrigger(java.util.function.Function<String, byte[]> classes) {
		byte[] bytes = classes.apply(CLIENT_MAIN.replace('.', '/') + ".class");
		if (bytes == null) return true;
		String pool = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
		for (Trigger t : CLIENT_TRIGGERS) {
			if (pool.contains(t.owner())) return true;
		}
		return false;
	}

	/** Backwards-compatible default: the server entry (existing callers/tests). */
	public LifecycleHookInjector() {
		this(SERVER_MAIN, "main", SERVER_TRIGGERS, true, false, KERNEL_HOOK_OWNER);
	}

	/**
	 * The {@link MergedBaseCalleeSwaps#SUBSTITUTED} row for one redirect: what the guest was compiled against, and
	 * what this pass put at the same instruction instead. Public so a test — or a headless probe — can assert the
	 * row without a boot, and so the two readers and the writer share one construction.
	 */
	public static MergedBaseCalleeSwaps.Substitution substitutionRow(String hostClass, org.objectweb.asm.tree.MethodNode host,
			String owner, String name, String desc, Ecosystem ecosystem, String hookOwner, String hookName,
			String hookDesc) {
		// EVERY ecosystem, deliberately — not the family whose arm fired. A carrier substitution is family-scoped
		// because a mod compiled against another family's jar never had that call to begin with, so its anchor
		// missing is what it would do natively. This swap is the KERNEL's: it replaced the call in the base for
		// everyone, so every guest anchored on it was anchored on a call that is now gone, whatever family compiled
		// it. Filtering by family left Sinytra Connector behind — a Fabric-ecosystem mod that anchors on NeoForge's
		// loader class precisely because that is what it exists to interact with — and the census kept reporting
		// `1/2 anchors resolve, missing: @At(INVOKE) …ServerModLoader.load in Main.main` on a boot where the swap had
		// been published. The family of the arm is still named in the text because it is the provenance of the row.
		// host.name + host.desc, never the bare name: the lookup this row is read by keys on the method as
		// MixinRetarget asks it — `name + descriptor` — so a bare name matches nothing and the row is silently inert.
		// Measured on a real boot: the pass published `Main.main` and the reader asked for
		// `main([Ljava/lang/String;)V`, so `substitution(...)` answered row=none on a NEOFORGE guest while the redirect
		// for that very call was logged twice. Taking the node rather than two strings is what makes that impossible.
		// hostClass arrives as the transform chain names classes — DOTTED (`net.minecraft.server.Main`) — while every
		// reader of this table passes an ASM internal name (`net/minecraft/server/Main`), and `covers` compares the
		// target with equals. Storing the dotted form made every row unfindable: on a real boot the row sat in the list
		// with target, method, member and ecosystems all matching the ask, and `substitution(...)` still answered
		// `row=none` — the one component that differed was this separator. Normalised here, at the boundary where the
		// row is built, because the table's contract is internal names and this is the only producer that broke it.
		return new MergedBaseCalleeSwaps.Substitution(hostClass.replace('.', '/'), host.name + host.desc, "L" + owner + ";" + name + desc,
				"L" + hookOwner + ";" + hookName + hookDesc,
				java.util.Set.of(Ecosystem.FABRIC, Ecosystem.FORGE, Ecosystem.NEOFORGE),
				"the kernel owns the lifecycle: this pass replaced " + owner.replace('/', '.') + "." + name
						+ " with its own hook at the same instruction, so the point BEFORE the hook is still the point "
						+ "before the mod-loading window every guest anchored on that call meant (the "
						+ ecosystem + " arm is the one that fired here)");
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (!transformClass.equals(className)) return classBytes;
		transformedRequiredEntry = true;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		int redirected = 0;
		boolean fabricHooks = fabricServerHook && fabricHooksEnabled();
		for (MethodNode m : node.methods) {
			if (!m.name.equals(transformMethod)) continue;
			// One marker per method, after the first trigger. SERVER_TRIGGERS keeps both load forms, and a base
			// carrying both would otherwise call Hooks.startServer twice -- owo's @Group(max = 1) then fails with
			// "expected 1 but 2", losing the freeze the marker is there for. Fabric's own Main calls it once.
			boolean marked = false;
			for (var insn : m.instructions.toArray()) {
				if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC) continue;
				Trigger t = matchTrigger(call);
				if (t == null) continue;

				if (t.popSlots() == 0) {
					// Same descriptor: keep the argument on the stack, just retarget owner+name.
					call.owner = hookOwner;
					call.name = t.hookName();
				} else {
					// The trigger has arguments the no-arg hook does not want: POP them, then call the hook. (Every
					// arg the client trigger carries is a single-slot object reference, so one POP per slot.)
					for (int i = 0; i < t.popSlots(); i++) {
						m.instructions.insertBefore(call, new InsnNode(Opcodes.POP));
					}
					call.owner = hookOwner;
					call.name = t.hookName();
					call.desc = "()V";
				}

				redirected++;
				// Publish the swap. A guest anchored on the call this pass just replaced is not reporting a merge
				// loss — it is reporting ours — so the anchor-retarget moves it onto the hook instead of leaving it on
				// a call that is no longer there (Sinytra Connector's boot.ServerMainMixin#earlyInit, whose
				// `@At(INVOKE) ServerModLoader.load in Main.main` read as missing on every subject while the console
				// line above was naming that very call as ours).
				MergedBaseCalleeSwaps.Substitution published = substitutionRow(transformClass, m, t.owner(),
						t.name(), t.desc(), t.ecosystem(), hookOwner, call.name, call.desc);
				MergedBaseCalleeSwaps.kernelSubstituted(published);
				// The other half of the pair `-Dforbric.mixinRetarget.diagnose=on` prints at the reader: the reader's
				// line says which key it asked for and whether a row answered; this one says which key was published
				// and WHEN relative to that ask. A row published after the ask cannot answer it, and the two lines
				// together separate that from a key that simply differs.
				if ("on".equalsIgnoreCase(System.getProperty(MixinRetarget.DIAGNOSE_PROPERTY, "off"))) {
					ForbricLog.info("[Forbric/Lifecycle] published a kernel substitution row for %s.%s (member %s) "
							+ "— kernel rows published so far: %d", published.target(), published.method(),
							published.member(), MergedBaseCalleeSwaps.kernelRowCount());
				}
				ForbricLog.info("[Forbric/Lifecycle] redirected genuine loader trigger %s.%s to %s.%s from %s.%s "
						+ "— kernel owns the lifecycle", t.owner(), t.name(), hookOwner, t.hookName(),
						transformClass, transformMethod);

				if (fabricHooks && !marked) {
					marked = true;
					// Right after the window, on the same path: the NeoForge base only loads mods when the launch
					// is not --initSettings, and neither may the marker claim they were. Fabric passes (null, null).
					InsnList marker = new InsnList();
					marker.add(new InsnNode(Opcodes.ACONST_NULL));
					marker.add(new InsnNode(Opcodes.ACONST_NULL));
					marker.add(new MethodInsnNode(Opcodes.INVOKESTATIC, FABRIC_HOOKS, "startServer", FABRIC_HOOK_DESC,
							false));
					m.instructions.insert(call, marker);
					// Two references on top of whatever the void call left, which is nothing at this statement
					// boundary; the frames are untouched (no branch in, no branch out, stack balanced).
					m.maxStack += 2;
					ForbricLog.info("[Forbric/Fabric] emitted Fabric Loader's Hooks.startServer after the server "
							+ "mod-loading window — mods anchored on it (owo's freeze) see every mod initialised, "
							+ "as on Fabric (-D%s=off to go back)", FABRIC_HOOKS_SWITCH);
				}
			}
		}

		if (reportsEarlyFailures) reportEarlyFailures(node);

		if (redirected == 0) {
			// A reporting-only registration (NO_TRIGGERS) expects no redirect and says nothing about it.
			if (triggers.length > 0) {
				StringBuilder seen = new StringBuilder();
				for (MethodNode m : node.methods) {
					if (!m.name.equals(transformMethod)) continue;
					for (var insn : m.instructions.toArray()) {
						if (insn instanceof MethodInsnNode call && call.owner.contains("ClientModLoader")) {
							if (seen.length() > 0) seen.append(", ");
							seen.append(call.owner).append('.').append(call.name).append(call.desc);
						}
					}
				}
				ForbricLog.error("[Forbric/Lifecycle] %s.%s contained NO known genuine-loader trigger — the merged "
						+ "base's entry shape changed; refusing to boot on an un-hooked lifecycle. Loader calls in "
						+ "that method: [%s]", transformClass, transformMethod,
						seen.length() == 0 ? "none" : seen);
			}
			return classBytes;
		}
		redirectedAtRequiredEntry = true;

		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		byte[] out = writer.toByteArray();

		return out;
	}

	/**
	 * Has {@code logEarlyException} pass its throwable to the kernel before printing it: {@code aload_0; invokestatic}
	 * at the head. One slot deep, which the method's own {@code aload_0; invokevirtual printStackTrace} already needs,
	 * and no branch moves. A base without the method keeps vanilla's stderr-only handler and loses nothing else.
	 */
	private static void reportEarlyFailures(ClassNode node) {
		for (MethodNode m : node.methods) {
			if (!EARLY_FAILURE.equals(m.name) || !EARLY_FAILURE_DESC.equals(m.desc)
					|| (m.access & Opcodes.ACC_STATIC) == 0 || m.instructions.size() == 0) {
				continue;
			}
			InsnList report = new InsnList();
			report.add(new VarInsnNode(Opcodes.ALOAD, 0));
			report.add(new MethodInsnNode(Opcodes.INVOKESTATIC, KERNEL_HOOK_OWNER, EARLY_FAILURE_HOOK,
					EARLY_FAILURE_DESC, false));
			m.instructions.insert(report);
			m.maxStack = Math.max(m.maxStack, 1);
			return;
		}
		ForbricLog.debug("[Forbric/Lifecycle] %s has no static %s%s; a failure in its first steps stays on "
				+ "stderr only", node.name, EARLY_FAILURE, EARLY_FAILURE_DESC);
	}

	private Trigger matchTrigger(MethodInsnNode call) {
		for (Trigger t : triggers) {
			if (t.owner().equals(call.owner) && t.name().equals(call.name) && t.desc().equals(call.desc)) return t;
		}
		return null;
	}

	/**
	 * True iff the required entry was loaded+transformed but its genuine-loader lifecycle trigger was NOT
	 * redirected (moved/renamed). The kernel checks this after the game class loads and aborts.
	 */
	public boolean missedRequiredExcision() {
		return transformedRequiredEntry && !redirectedAtRequiredEntry;
	}

	/** True once the targeted entry has been transformed (whether or not a trigger was found). */
	public boolean transformedServerEntry() {
		return transformedRequiredEntry;
	}

	@Override
	public String name() {
		return "forbric:lifecycle-hook-injector";
	}

	@Override
	public AnchorSet anchors() {
		// The one FATAL in the tree, and not a new policy: KernelBoot already refuses to boot when this seam is
		// missed. Declaring it puts the same fact in the books so the summary and the build-time audit can see it
		// too.
		//
		// PORT(1.21.1): the client registers TWO injectors — the lifecycle one (Main.main on 26.2,
		// Minecraft.<init> on 1.21.1) and a reporting-only one on Main.main that expects no redirect. Only the
		// trigger-carrying one declares an anchor; a NO_TRIGGERS registration is not a seam that can be missed.
		if (triggers.length == 0) {
			return AnchorSet.scanned("reports early client failures only; carries no lifecycle trigger");
		}
		return AnchorSet.of(new AnchorSet.Anchor(transformClass, AnchorSet.Severity.FATAL,
				"the genuine loader's own mod-loading lifecycle would run alongside the kernel's, which is the "
						+ "one thing this architecture cannot survive"));
	}
}
