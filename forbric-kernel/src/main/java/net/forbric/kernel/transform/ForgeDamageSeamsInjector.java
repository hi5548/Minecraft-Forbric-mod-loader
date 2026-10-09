/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.ArrayList;
import java.util.List;

import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * MinecraftForge's Hurt, Damage and player-Attack events get their positions back in NeoForge's damage pipeline.
 *
 * <p>The merged {@code actuallyHurt} and the player hurt entry are NeoForge's: nothing calls
 * {@code ForgeHooks.onLivingHurt}, {@code onLivingDamage} or {@code onPlayerAttack}, and no NeoForge event sits where
 * the first two were, so a bridge has nothing to listen to. Tombstone's ghost immunity, its Voodoo Poppet and its
 * damage perks are exactly these listeners, and all of them did nothing. Three seams, each an {@code invokestatic}
 * into {@code KernelLivingDamage} with nothing else that another mod's injector could match:
 *
 * <ul>
 *   <li><b>Hurt</b>, in {@code actuallyHurt} right after the {@code isInvulnerableTo} check and before armour —
 *       MinecraftForge's position. True from the helper returns, as MinecraftForge's {@code amount <= 0} does; a
 *       listener that killed the entity returns too, so NeoForge's "killed during LivingDamageEvent.Pre" check
 *       cannot throw.</li>
 *   <li><b>Damage</b>, after the health damage is read back from the container (after armour, magic and
 *       absorption), rewriting the local the body goes on to write with — the local {@code setHealth} reads.</li>
 *   <li><b>player Attack</b>, at the head of the player hurt entry: {@code Player.hurtServer(ServerLevel,
 *       DamageSource, float)Z} on 26.2, {@code Player.hurt(DamageSource, float)Z} on 1.21.1 — which is where
 *       MinecraftForge 1.21.1 puts {@code onPlayerAttack} too. At the head and before difficulty scaling and the
 *       zero-damage return, so a snowball is still an attack. {@code Player.<clinit>} tells the attack forward the
 *       seam is in, so players are asked here and only here.</li>
 * </ul>
 *
 * <p>The two carriers differ in shape, and which one is present is read from the class, not assumed: 26.2 led the
 * damage pipeline with a {@code ServerLevel} ({@code actuallyHurt(ServerLevel,DamageSource,float)},
 * {@code hurtServer}), 1.21.1 does not ({@code actuallyHurt(DamageSource,float)}, {@code hurt}). The Hurt/Damage
 * proof is the same either way: the opening {@code isInvulnerableTo} guard, one {@code onLivingDamagePre}, and the
 * {@code getNewDamage()} the body hands to the local it applies.
 *
 * <p>Each seam is placed only when its proof holds and no MinecraftForge call is already there.
 * {@code -Dforbric.forgeDamageSeams=off} leaves both classes as merged (the attack forward then asks every entity).
 */
public final class ForgeDamageSeamsInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.forgeDamageSeams";
	static final String LIVING = "net/minecraft/world/entity/LivingEntity";
	static final String PLAYER = "net/minecraft/world/entity/player/Player";
	static final String CONTAINER = "net/neoforged/neoforge/common/damagesource/DamageContainer";
	static final String RUNTIME = "net/forbric/kernel/runtime/KernelLivingDamage";
	static final String SOURCE = "Lnet/minecraft/world/damagesource/DamageSource;";
	static final String HURT_DESC = "(Lnet/minecraft/server/level/ServerLevel;" + SOURCE + "F)V";
	static final String SERVER_DESC = "(Lnet/minecraft/server/level/ServerLevel;" + SOURCE + "F)Z";
	/**
	 * 1.21.1's shapes: the damage pipeline does not take a {@code ServerLevel}, so {@code actuallyHurt} is
	 * {@code (DamageSource,float)} and the player hook is {@code Player.hurt(DamageSource,float)Z} — the place
	 * MinecraftForge 1.21.1 itself calls {@code onPlayerAttack} from. Read from whichever shape the carrier has.
	 */
	static final String HURT_DESC_121 = "(" + SOURCE + "F)V";
	static final String HURT_BOOL_DESC = "(" + SOURCE + "F)Z";

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override public String name() { return "forbric-forge-damage-seams"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("MinecraftForge's Hurt, Damage and player-Attack events left undelivered "
				+ "with -D" + PROPERTY + "=off");
		String cost = "MinecraftForge's LivingHurtEvent and LivingDamageEvent never fire — damage perks, immunity and "
				+ "death-prevention in MinecraftForge mods do nothing";
		return AnchorSet.of(new AnchorSet.Anchor(LIVING.replace('/', '.'), AnchorSet.Severity.REQUIRED, cost),
				new AnchorSet.Anchor(PLAYER.replace('/', '.'), AnchorSet.Severity.REQUIRED, cost));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0) return bytes;
		boolean player = className.equals(PLAYER.replace('/', '.'));
		if (!player && !className.equals(LIVING.replace('/', '.'))) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		List<String> placed = repair(node);
		if (placed.isEmpty()) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info("[Forbric/Damage] %s: MinecraftForge's %s back in NeoForge's damage pipeline — nothing called "
				+ "them on the merged base", className, String.join(", ", placed));
		return writer.toByteArray();
	}

	/** Places every seam whose proof holds; the names placed. */
	static List<String> repair(ClassNode node) {
		List<String> placed = new ArrayList<>();
		MethodNode hurt = own(node, "actuallyHurt", HURT_DESC);
		if (hurt == null) hurt = own(node, "actuallyHurt", HURT_DESC_121);
		if (hurt != null && hurtSeams(hurt)) placed.add("Hurt and Damage in actuallyHurt");
		if (node.name.equals(PLAYER)) {
			MethodNode server = own(node, "hurtServer", SERVER_DESC);
			if (server == null) server = own(node, "hurt", HURT_BOOL_DESC);
			if (server != null && attackSeam(server) && noteSeam(node)) placed.add("player Attack in " + server.name);
		}
		return placed;
	}

	/** Hurt after the invulnerability check, Damage after the health damage is read back. */
	static boolean hurtSeams(MethodNode method) {
		if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0) return false;
		if (calls(method, "net/minecraftforge/common/ForgeHooks", "onLivingHurt") != 0
				|| calls(method, "net/minecraftforge/common/ForgeHooks", "onLivingDamage") != 0
				|| calls(method, RUNTIME, "hurt") != 0) return false;
		List<AbstractInsnNode> code = code(method);
		// `aload0 aload<1..n> invokevirtual isInvulnerableTo; ifne <end>`. 26.2 passes a ServerLevel and a
		// DamageSource (two loads); 1.21.1 passes only the DamageSource (one), so the guard is read from the call's
		// own descriptor. The last loaded argument is the DamageSource, and its slot is also the source parameter's
		// slot in this method — the loads are consecutive from slot 1, which the guard verifies.
		int guard = invulnerableGuard(code);
		if (guard < 0) return false;
		int sourceSlot = Type.getArgumentTypes(((MethodInsnNode) code.get(guard - 1)).desc).length;
		if (calls(method, "net/neoforged/neoforge/common/CommonHooks", "onLivingDamagePre") != 1) return false;
		// The health damage the body goes on to apply: the last `getNewDamage()` handed to a local. 26.2 has one
		// post-Pre read; 1.21.1 reads it twice (once after Pre, once after absorption has been taken out) and the
		// applied one is the later local, the one `setHealth` reads.
		AbstractInsnNode store = null;
		for (AbstractInsnNode insn : code) {
			if (insn instanceof VarInsnNode v && v.getOpcode() == Opcodes.FSTORE
					&& v.getPrevious() instanceof MethodInsnNode read && read.owner.equals(CONTAINER)
					&& read.name.equals("getNewDamage")) store = insn;
		}
		if (store == null) return false;
		int storeSlot = ((VarInsnNode) store).var;
		if (!loadedAfter(code, store, storeSlot)) return false;

		LabelNode end = new LabelNode(), go = new LabelNode();
		InsnList hurt = new InsnList();
		hurt.add(new VarInsnNode(Opcodes.ALOAD, 0));
		container(hurt);
		hurt.add(new VarInsnNode(Opcodes.ALOAD, sourceSlot));
		hurt.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "hurt",
				"(L" + LIVING + ";L" + CONTAINER + ";" + SOURCE + ")Z", false));
		hurt.add(new JumpInsnNode(Opcodes.IFNE, end));
		hurt.add(new VarInsnNode(Opcodes.ALOAD, 0));
		hurt.add(new FieldInsnNode(Opcodes.GETFIELD, LIVING, "dead", "Z"));
		hurt.add(new JumpInsnNode(Opcodes.IFEQ, go));
		hurt.add(end);
		hurt.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
		hurt.add(new InsnNode(Opcodes.RETURN));
		hurt.add(go);
		hurt.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
		method.instructions.insert(code.get(guard), hurt);

		InsnList damage = new InsnList();
		damage.add(new VarInsnNode(Opcodes.ALOAD, 0));
		container(damage);
		damage.add(new VarInsnNode(Opcodes.ALOAD, sourceSlot));
		damage.add(new VarInsnNode(Opcodes.FLOAD, storeSlot));
		damage.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "damage",
				"(L" + LIVING + ";L" + CONTAINER + ";" + SOURCE + "F)F", false));
		damage.add(new VarInsnNode(Opcodes.FSTORE, storeSlot));
		method.instructions.insert(store, damage);
		return true;
	}

	/**
	 * The index in {@code code} of the {@code ifne} that closes the opening {@code this.isInvulnerableTo(...)}
	 * guard, or {@code -1} when the shape has moved. The receiver and every argument must be pushed consecutively
	 * from slot 0, which is what makes the last one's slot the source parameter's slot.
	 */
	private static int invulnerableGuard(List<AbstractInsnNode> code) {
		for (int i = 1; i + 1 < code.size(); i++) {
			if (!(code.get(i) instanceof MethodInsnNode call) || !call.name.equals("isInvulnerableTo")) continue;
			int args = Type.getArgumentTypes(call.desc).length;
			if (i != 1 + args || !load(code.get(0), Opcodes.ALOAD, 0)) continue;
			boolean pushed = true;
			for (int a = 0; a < args; a++) {
				if (!load(code.get(1 + a), Opcodes.ALOAD, 1 + a)) {
					pushed = false;
					break;
				}
			}
			if (pushed && code.get(i + 1).getOpcode() == Opcodes.IFNE) return i + 1;
		}
		return -1;
	}

	/** Whether the local {@code store} wrote is read again later — the link to the health the body writes. */
	private static boolean loadedAfter(List<AbstractInsnNode> code, AbstractInsnNode store, int slot) {
		boolean seen = false;
		for (AbstractInsnNode insn : code) {
			if (insn == store) {
				seen = true;
				continue;
			}
			if (seen && load(insn, Opcodes.FLOAD, slot)) return true;
		}
		return false;
	}

	/** {@code if (!KernelLivingDamage.playerAttack(this, source, amount)) return false;} at the head. */
	static boolean attackSeam(MethodNode method) {
		if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0) return false;
		if (calls(method, "net/minecraftforge/common/ForgeHooks", "onPlayerAttack") != 0
				|| calls(method, RUNTIME, "playerAttack") != 0) return false;
		// Nothing jumps back to the first instruction: the seam's F_SAME frames are then relative to the entry frame.
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null && insn.getOpcode() < 0; insn = insn.getNext()) {
			if (insn instanceof FrameNode) return false;
		}
		if (code(method).isEmpty()) return false;
		// `hurtServer(ServerLevel,DamageSource,float)` on 26.2, `hurt(DamageSource,float)` on 1.21.1: the source and
		// amount are the last two arguments, so their slots follow from the descriptor (`this` is slot 0).
		Type[] args = Type.getArgumentTypes(method.desc);
		if (args.length < 2 || !args[args.length - 2].getDescriptor().equals(SOURCE)) return false;
		LabelNode go = new LabelNode();
		InsnList attack = new InsnList();
		attack.add(new VarInsnNode(Opcodes.ALOAD, 0));
		attack.add(new VarInsnNode(Opcodes.ALOAD, args.length - 1));
		attack.add(new VarInsnNode(Opcodes.FLOAD, args.length));
		attack.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "playerAttack", "(L" + LIVING + ";" + SOURCE + "F)Z", false));
		attack.add(new JumpInsnNode(Opcodes.IFNE, go));
		attack.add(new InsnNode(Opcodes.ICONST_0));
		attack.add(new InsnNode(Opcodes.IRETURN));
		attack.add(go);
		attack.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
		method.instructions.insert(attack);
		return true;
	}

	/** {@code KernelLivingDamage.notePlayerSeam()} first thing in {@code Player.<clinit>}. */
	static boolean noteSeam(ClassNode node) {
		MethodNode clinit = own(node, "<clinit>", "()V");
		if (clinit == null) return false;
		clinit.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "notePlayerSeam", "()V", false));
		return true;
	}

	/** {@code (DamageContainer) this.damageContainers.peek()} — NeoForge's own way of reading the hit's container. */
	private static void container(InsnList list) {
		list.add(new VarInsnNode(Opcodes.ALOAD, 0));
		list.add(new FieldInsnNode(Opcodes.GETFIELD, LIVING, "damageContainers", "Ljava/util/Stack;"));
		list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/Stack", "peek", "()Ljava/lang/Object;", false));
		list.add(new TypeInsnNode(Opcodes.CHECKCAST, CONTAINER));
	}

	private static boolean load(AbstractInsnNode insn, int opcode, int slot) {
		return insn instanceof VarInsnNode v && v.getOpcode() == opcode && v.var == slot;
	}

	private static List<AbstractInsnNode> code(MethodNode method) {
		List<AbstractInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) if (insn.getOpcode() >= 0) out.add(insn);
		return out;
	}

	private static int calls(MethodNode method, String owner, String name) {
		int n = 0;
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) n++;
		}
		return n;
	}

	private static MethodNode own(ClassNode node, String name, String desc) {
		for (MethodNode method : node.methods) if (method.name.equals(name) && method.desc.equals(desc)) return method;
		return null;
	}
}
