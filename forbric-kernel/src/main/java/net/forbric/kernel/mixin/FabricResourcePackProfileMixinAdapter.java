/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.function.Function;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import net.forbric.kernel.util.ForbricLog;

/**
 * Makes fabric-resource-loader's "is this pack hidden?" answer safe when its parent-predicate field was never set.
 *
 * <h2>What it costs when it is not</h2>
 *
 * <p>{@code ResourcePackProfileMixin} declares {@code @Unique Predicate parentsPredicate}, initialised — in the
 * mixin's own no-argument constructor — to a private static {@code DEFAULT_PARENT_PREDICATE} whose lambda always
 * answers true. {@link #MIXIN}'s accessor is an identity test against that constant:
 *
 * <pre>
 *   fabric_isHidden() == (parentsPredicate != DEFAULT_PARENT_PREDICATE)
 * </pre>
 *
 * <p>So an instance whose field was never assigned reports {@code true}: "this pack is hidden, enable it only when
 * its parents are". That is the safe reading ONLY as long as something re-adds such packs, and the re-add lives in
 * {@code ModResourcePackUtil.refreshAutoEnabledPacks} — whose loop is nested INSIDE a loop over the list it has just
 * filtered:
 *
 * <pre>
 *   enabled.removeIf(p -&gt; ((FabricResourcePackProfile) p).fabric_isHidden());   // every pack, if the field is unset
 *   for (ListIterator&lt;Pack&gt; it = enabled.listIterator(); it.hasNext();) {      // zero iterations then
 *       ... for each available pack that is hidden and whose parents are enabled: it.add(pack);
 *   }
 * </pre>
 *
 * <p>An emptied list therefore re-adds nothing — and nothing throws: {@code fabric_parentsEnabled} is only reached
 * from inside that loop, so a null predicate never gets called. The selection ends up EMPTY, silently, at INFO
 * level. That list is what {@code PackRepository.rebuildSelected} is about to hand to
 * {@code WorldLoader.PackConfig.createResourceManager()}, so the world-load {@code MultiPackResourceManager} gets
 * no packs at all, every datapack registry is loaded from nothing, and only the registries that declare
 * {@code requiredNonEmpty} report it: {@code minecraft:wolf_variant} and {@code minecraft:painting_variant}.
 *
 * <h2>Why the field can be unset here</h2>
 *
 * <p>The initialiser is only ever applied by merging the mixin's own constructor — there is no {@code Initialiser}
 * implementation on this class, so if that no-argument constructor does not reach the merged {@code Pack} class
 * (its constructors are the merge's, and NeoForge re-typed them), the field keeps its JVM default of {@code null}
 * and the accessor above answers {@code true} for every pack. The byte shape is asserted in
 * {@code FabricResourcePackProfileMixinAdapterTest}, not assumed.
 *
 * <h2>The edit</h2>
 *
 * <p>Null is not "hidden": the field is unset exactly when nobody has marked the pack. The accessor gets the one
 * missing case in front of its identity test —
 *
 * <pre>
 *   parentsPredicate == null || parentsPredicate == DEFAULT_PARENT_PREDICATE  -&gt; false
 * </pre>
 *
 * <p>— two instructions and a jump to the existing {@code false} arm, straight-line code that leaves one
 * {@code boolean} where one was, so every existing stack-map frame still holds. Nothing else about the mixin
 * changes: a pack whose predicate WAS set still reports hidden, and {@code fabric_parentsEnabled} still answers from
 * it. {@code -Dforbric.fabricResourcePackProfile=off} leaves the mixin exactly as it came.
 */
public final class FabricResourcePackProfileMixinAdapter {
	public static final String PROPERTY="forbric.fabricResourcePackProfile";
	private static final String MIXIN="net/fabricmc/fabric/mixin/resource/loader/ResourcePackProfileMixin";
	private static final String ACCESSOR="fabric_isHidden";
	private static final String ACCESSOR_DESC="()Z";
	private static final String FIELD_DESC="Ljava/util/function/Predicate;";
	private FabricResourcePackProfileMixinAdapter() { }
	public static boolean enabled(){return !"off".equalsIgnoreCase(System.getProperty(PROPERTY,"on"));}

	/** The field the accessor tests, or null when this class does not have the shape the edit needs. */
	static FieldInsnNode predicateField(MethodNode accessor){
		if(accessor==null||!ACCESSOR_DESC.equals(accessor.desc)||accessor.instructions==null)return null;
		FieldInsnNode field=null;int reads=0;
		for(AbstractInsnNode insn=accessor.instructions.getFirst();insn!=null;insn=insn.getNext()){
			if(insn instanceof FieldInsnNode f&&f.getOpcode()==Opcodes.GETFIELD){
				if(!FIELD_DESC.equals(f.desc))return null;
				field=f;reads++;
			}
		}
		return reads==1?field:null;
	}

	public static int adapt(ClassNode mixin,Function<String,ClassNode> targets){
		if(!enabled()||mixin==null||!MIXIN.equals(mixin.name)||mixin.methods==null)return 0;
		MethodNode accessor=mixin.methods.stream().filter(m->m.name.equals(ACCESSOR)&&m.desc.equals(ACCESSOR_DESC)).findFirst().orElse(null);
		FieldInsnNode field=predicateField(accessor);
		if(field==null)return 0;
		// Already edited? Then the accessor opens with ALOAD 0 / GETFIELD / IFNULL.
		AbstractInsnNode head=accessor.instructions.getFirst();
		if(head==null)return 0;
		AbstractInsnNode second=head.getNext();
		AbstractInsnNode third=second==null?null:second.getNext();
		if(head.getOpcode()==Opcodes.ALOAD&&second instanceof FieldInsnNode&&third instanceof JumpInsnNode
				&&third.getOpcode()==Opcodes.IFNULL)return 0;
		// The false arm: the instruction the existing IF_ACMPEQ branches to.
		JumpInsnNode identity=null;
		for(AbstractInsnNode insn=head;insn!=null;insn=insn.getNext())
			if(insn instanceof JumpInsnNode j&&j.getOpcode()==Opcodes.IF_ACMPEQ){identity=j;break;}
		if(identity==null||!(identity.label.getNext() instanceof InsnNode falseArm)||falseArm.getOpcode()!=Opcodes.ICONST_0)return 0;
		LabelNode unhidden=new LabelNode();
		accessor.instructions.insertBefore(falseArm,unhidden);
		InsnList guard=new InsnList();
		guard.add(new VarInsnNode(Opcodes.ALOAD,0));
		guard.add(new FieldInsnNode(Opcodes.GETFIELD,field.owner,field.name,field.desc));
		guard.add(new JumpInsnNode(Opcodes.IFNULL,unhidden));
		// BEFORE the head, not after it: ASM's two-argument insert places the list after the given node, and a guard
		// landing after the first ALOAD leaves that reference on the stack — the method then verifies inconsistently
		// and, worse, reads the field before the null test it was supposed to precede.
		accessor.instructions.insertBefore(head,guard);
		accessor.maxStack=Math.max(accessor.maxStack,2);
		ForbricLog.info("[Forbric/ResourceLoader] %s.fabric_isHidden answers false for a pack whose parent predicate "
				+ "was never set — with the field at its JVM default every pack read as hidden, and pack selection came "
				+ "back empty, which empties every datapack registry (only requiredNonEmpty ones report it)",
				mixin.name.substring(mixin.name.lastIndexOf('/')+1));
		return 1;
	}
}
