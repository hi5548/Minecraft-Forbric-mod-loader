package net.forbric.kernel.mixin;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/**
 * The repair {@link FabricResourcePackProfileMixinAdapter} makes, asserted on the real module's bytes.
 *
 * <p>Why this test exists at all: an unset parent predicate made {@code fabric_isHidden()} answer <em>true</em> for
 * every pack, and the only filter that reads it is the first statement of
 * {@code ModResourcePackUtil.refreshAutoEnabledPacks} — whose re-add loop is nested inside the list it just emptied.
 * The pack selection therefore came back empty with no exception and no line above DEBUG, and every datapack
 * registry was loaded from nothing; only the two that declare {@code requiredNonEmpty} reported it. A test that only
 * asserted "the adapter ran" would have passed on the broken behaviour too, so this asserts the shape BEFORE the
 * edit (which is what made the unset field read as hidden) and the emitted program AFTER it.
 */
class FabricResourcePackProfileMixinAdapterTest {
	private static final String MODULE="fabric-resource-loader-v0";
	private static final String CLASS="net/fabricmc/fabric/mixin/resource/loader/ResourcePackProfileMixin";

	private ClassNode mixin() throws Exception {
		// The helper assumes the staged fabric-api fixture is present; called from the test body, never a lambda,
		// so JUnit's abort unwinds instead of being wrapped.
		return StagedFabricMixinFixture.mixin(MODULE,CLASS);
	}

	private static String sequence(MethodNode m) {
		StringBuilder s=new StringBuilder();
		for(org.objectweb.asm.tree.AbstractInsnNode i=m.instructions.getFirst();i!=null;i=i.getNext())
			s.append(i.getClass().getSimpleName()).append('[').append(i.getOpcode()).append("] ");
		return s.toString();
	}

	private static MethodNode accessor(ClassNode mixin) {
		return mixin.methods.stream().filter(m->m.name.equals("fabric_isHidden")).findFirst().orElseThrow();
	}

	/** The shape the adapter's edit needs, and the one that made the bug: one field read, no null test. */
	@Test void theUneditedAccessorReadsAnUnsetFieldAsHidden() throws Exception {
		MethodNode before=accessor(mixin());
		assertNotEquals(0,FabricResourcePackProfileMixinAdapter.predicateField(before),
				"the accessor this adapter repairs must still be an identity test on its parent-predicate field");
		assertNotEquals(Opcodes.IFNULL,before.instructions.getFirst().getNext().getNext().getOpcode(),
				"red before the edit: nothing guards the field read, so a JVM-default null field reads as hidden");
	}

	@Test void theEditGuardsTheReadAndStillReportsAHiddenPack() throws Exception {
		ClassNode mixin=mixin();
		assertEquals(1,FabricResourcePackProfileMixinAdapter.adapt(mixin,null));
		MethodNode after=accessor(mixin);
		InsnList code=after.instructions;
		AbstractInsnNode i=code.getFirst();
		assertEquals(Opcodes.ALOAD,i.getOpcode());
		assertTrue(i.getNext() instanceof FieldInsnNode,"the guard reads the same field the accessor tests");
		FieldInsnNode guarded=(FieldInsnNode)i.getNext();
		assertTrue(i.getNext().getNext() instanceof JumpInsnNode jump&&jump.getOpcode()==Opcodes.IFNULL,
				"and branches away when it is unset");
		JumpInsnNode guard=(JumpInsnNode)i.getNext().getNext();
		assertEquals(Opcodes.ICONST_0,guard.label.getNext().getOpcode(),
				"the null branch is the existing false arm, so an unset predicate answers 'not hidden'");
		assertEquals(guarded.name,FabricResourcePackProfileMixinAdapter.predicateField(after).name,
				"and the identity test below it is untouched, so a SET predicate still decides");
		new Analyzer<>(new BasicVerifier()).analyze(mixin.name,after);
	}

	@Test void aSecondPassLeavesTheEditAlone() throws Exception {
		ClassNode mixin=mixin();
		assertEquals(1,FabricResourcePackProfileMixinAdapter.adapt(mixin,null));
		assertEquals(0,FabricResourcePackProfileMixinAdapter.adapt(mixin,null),"idempotent: the guard is its own mark");
	}

	/**
	 * The runtime oracle for this fix is the fabric slice (`Registry loading errors` = 0, then a `loaded` row); what
	 * these tests can prove without the game is that the edited program is the shape the javadoc describes and that
	 * ASM's analyser accepts it. An earlier version of this file also DEFINED a hand-emitted probe class and executed
	 * the edited accessor; it was dropped because the probe's own bytecode (not the edit) verified inconsistently
	 * under a version-49 verifier, and a test whose failures can come from its own harness proves nothing about the
	 * repair. The edit itself is three instructions in front of the existing test, asserted below.
	 */
	@Test void anotherClassIsUntouched() throws Exception {
		ClassNode other=mixin();
		other.name="net/fabricmc/fabric/mixin/resource/loader/SomeOtherMixin";
		assertEquals(0,FabricResourcePackProfileMixinAdapter.adapt(other,null));
	}

	/** The mixin's shape, synthesized: field + identity-test accessor, under the name the adapter accepts. */
	private static final String PROBE="net/fabricmc/fabric/mixin/resource/loader/ResourcePackProfileMixin";
	private static final String PREDICATE="Ljava/util/function/Predicate;";

	private static ClassNode shape() {
		ClassNode c=new ClassNode();
		// V1_5: the probe needs no stack maps, so the class-version-49 verifier infers them and the executed test
		// proves the EDITED instructions rather than the harness's frame emission.
		c.version=Opcodes.V1_5;c.access=Opcodes.ACC_PUBLIC;c.superName="java/lang/Object";c.name=PROBE;
		c.fields.add(new FieldNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC,"DEFAULT",PREDICATE,null,null));
		c.fields.add(new FieldNode(Opcodes.ACC_PRIVATE,"parentsPredicate",PREDICATE,null,null));
		MethodNode ctor=new MethodNode(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);
		ctor.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));
		ctor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false));
		ctor.instructions.add(new InsnNode(Opcodes.RETURN));
		ctor.maxStack=1;ctor.maxLocals=1;
		MethodNode accessor=new MethodNode(Opcodes.ACC_PUBLIC,"fabric_isHidden","()Z",null,null);
		LabelNode isDefault=new LabelNode(),end=new LabelNode();
		accessor.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));
		accessor.instructions.add(new FieldInsnNode(Opcodes.GETFIELD,PROBE,"parentsPredicate",PREDICATE));
		accessor.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC,PROBE,"DEFAULT",PREDICATE));
		accessor.instructions.add(new JumpInsnNode(Opcodes.IF_ACMPEQ,isDefault));
		accessor.instructions.add(new InsnNode(Opcodes.ICONST_1));
		accessor.instructions.add(new JumpInsnNode(Opcodes.GOTO,end));
		accessor.instructions.add(isDefault);
		accessor.instructions.add(new InsnNode(Opcodes.ICONST_0));
		accessor.instructions.add(end);
		accessor.instructions.add(new InsnNode(Opcodes.IRETURN));
		accessor.maxStack=2;accessor.maxLocals=1;
		c.methods.add(ctor);c.methods.add(accessor);
		return c;
	}

	/**
	 * The edit itself, verified on the shape above when the fixture is not staged: the guard is a null test in front
	 * of the identity test, it branches to the EXISTING false arm, and ASM's analyser accepts the result. Red before
	 * the edit by construction — the unedited accessor's second instruction is the field read, not a jump.
	 */
	@Test void theGuardIsInFrontOfTheIdentityTestAndTheResultVerifies() throws Exception {
		ClassNode unedited=shape();
		MethodNode before=unedited.methods.stream().filter(m->m.name.equals("fabric_isHidden")).findFirst().orElseThrow();
		assertEquals(Opcodes.GETFIELD,before.instructions.getFirst().getNext().getOpcode(),
				"red before the edit: nothing tests the field for null, so an unset predicate reads as hidden");
		ClassNode edited=shape();
		assertEquals(1,FabricResourcePackProfileMixinAdapter.adapt(edited,null));
		MethodNode after=edited.methods.stream().filter(m->m.name.equals("fabric_isHidden")).findFirst().orElseThrow();
		assertEquals(Opcodes.ALOAD,after.instructions.getFirst().getOpcode());
		assertTrue(after.instructions.getFirst().getNext() instanceof FieldInsnNode,"sequence: "+sequence(after));
		assertTrue(after.instructions.getFirst().getNext().getNext() instanceof JumpInsnNode jump
				&&jump.getOpcode()==Opcodes.IFNULL);
		JumpInsnNode guard=(JumpInsnNode)after.instructions.getFirst().getNext().getNext();
		assertEquals(Opcodes.ICONST_0,guard.label.getNext().getOpcode(),"the null branch is the false arm");
		new Analyzer<>(new BasicVerifier()).analyze(edited.name,after);
		assertEquals(0,FabricResourcePackProfileMixinAdapter.adapt(edited,null),"idempotent on the shape too");
	}

	private static Class<?> define(ClassNode c) {
		// Version 49 and COMPUTE_MAXS only: the class-version-49 verifier infers frames, so this test proves the
		// EDITED instructions rather than its own frame emission.
		org.objectweb.asm.ClassWriter writer=new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
		c.accept(writer);
		byte[] bytes=writer.toByteArray();
		return new ClassLoader(){ Class<?> define(byte[] b){return defineClass(null,b,0,b.length);} }.define(bytes);
	}

	/**
	 * The repair EXECUTED, on the shape above: an instance whose predicate was never set reads "hidden" before the
	 * edit — the state that made `refreshAutoEnabledPacks` remove every selected pack — and "not hidden" after it,
	 * while a predicate that WAS set still decides. `shape()`'s constructor deliberately does not assign the field,
	 * which is what an initialiser that never reached the class leaves behind.
	 */
	@Test void anUnsetPredicateStopsReadingAsHiddenWhenExecuted() throws Exception {
		java.util.function.Predicate<Set<String>> def=s->true, other=s->true;

		Class<?> unedited=define(shape());
		java.lang.reflect.Field rawDefault=unedited.getDeclaredField("DEFAULT");
		rawDefault.setAccessible(true);
		rawDefault.set(null,def);   // the constant exists; only the INSTANCE field is unset, which is the state in question
		Object rawInstance=unedited.getConstructor().newInstance();
		assertEquals(Boolean.TRUE,unedited.getMethod("fabric_isHidden").invoke(rawInstance),
				"red before the edit: an unset predicate read as hidden, and removeIf then emptied the selection");

		ClassNode edited=shape();
		assertEquals(1,FabricResourcePackProfileMixinAdapter.adapt(edited,null));
		Class<?> k=define(edited);
		java.lang.reflect.Field declared=k.getDeclaredField("DEFAULT");
		declared.setAccessible(true);declared.set(null,def);
		java.lang.reflect.Field field=k.getDeclaredField("parentsPredicate");
		field.setAccessible(true);
		Object instance=k.getConstructor().newInstance();
		java.lang.reflect.Method hidden=k.getMethod("fabric_isHidden");
		assertEquals(Boolean.FALSE,hidden.invoke(instance),"unset is not hidden any more");
		field.set(instance,def);
		assertEquals(Boolean.FALSE,hidden.invoke(instance),"the default predicate is still not hidden");
		field.set(instance,other);
		assertEquals(Boolean.TRUE,hidden.invoke(instance),"a predicate that WAS set still reports hidden");
	}
}
