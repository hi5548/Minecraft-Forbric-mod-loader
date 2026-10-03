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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.mixin.MixinFit;
import net.forbric.kernel.util.ForbricLog;

/**
 * Rewrites a GUEST MIXIN's {@code @Inject}/{@code @Redirect}/MixinExtras selector when it names a synthetic lambda
 * the byte-merge renumbered, so the selector points at the member the merged base actually declares.
 *
 * <p>A guest mixin's selector reaches this chain already in the runtime namespace, carrying the name the OFFICIAL
 * mappings gave the lambda (e.g. {@code Lnet/minecraft/core/RegistrySynchronization;lambda$ownedNetworkableRegistries$4
 * (Lnet/minecraft/core/RegistryAccess$RegistryEntry;)Z}). The merged base is compiled, not remapped, so its own
 * javac renumbered that lambda — it declares {@code lambda$ownedNetworkableRegistries$5} with the very same
 * descriptor. Mixin then resolves nothing, the injector is a required loss, and every subject owning the mixin
 * stops STRICT on a shape that is present under a different number.
 *
 * <p>The rule is deliberately narrow. A selector whose name is {@code lambda$<enclosing>$<n>} is resolved against
 * the class it already names, by <em>enclosing name plus descriptor</em>, and the selector is rewritten only when
 * that picks out <em>exactly one</em> method. A descriptor that names no member is not this class's business — it
 * is a different function, and {@link net.forbric.kernel.mixin.MergedBaseMixinCompat#SUPPRESSED_MIXINS} already
 * pins that mixin whole. Neither does a bare selector without a descriptor get a guess, nor one whose owner is not
 * a class this kernel can read; both stand down. Only the selector string changes: no instruction, frame or local
 * is touched.
 *
 * <p>Measured on the real corpus: {@code fabric-registry-sync-v0}'s {@code SerializableRegistriesMixin} carries two
 * such selectors ({@code $4→$5} and {@code $3→$4}), while {@code fabric-item-api-v1}'s {@code EnchantmentHelperMixin}
 * wants {@code lambda$getAvailableEnchantmentResults$41(ItemStack,Z,Holder)Z} where the merged base's {@code $41} is
 * an unrelated {@code (int,List,Holder)V} accumulator — same name and number, different function — and is refused
 * here, leaving that mixin to the documented stand-down.
 *
 * <p>Guest mixin classes reach this transformer the same way they reach {@link GuestInjectorPruner}: through
 * {@code ForbricClassLoader.getPreMixinClassBytes}, which is what {@code MixinFit} and Mixin itself read, so the
 * rewritten selector is the only one anyone judges or applies. Because it decides per mixin, and which mixins carry
 * a renumbered selector is not a fixed list, it declares {@link AnchorSet#scanned(String)}.
 *
 * <p>{@code -Dforbric.lambdaSelectorRetarget=off} restores the previous behaviour exactly: every renumbered
 * selector is left as written and reads as a required loss again.
 */
public final class LambdaSelectorRetarget implements ClassTransformer {
	public static final String PROPERTY = "forbric.lambdaSelectorRetarget";

	private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";

	/** javac's synthetic lambda: {@code lambda$<enclosing>$<counter>}. */
	private static final Pattern LAMBDA = Pattern.compile("^lambda\\$(.+)\\$(\\d+)$");

	/** Reads a class's bytes by internal name; {@code null} when this kernel has no such class. */
	private final Function<String, byte[]> mergedBase;

	public LambdaSelectorRetarget(Function<String, byte[]> mergedBase) {
		this.mergedBase = mergedBase;
	}

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override
	public String name() {
		return "forbric:lambda-selector-retarget";
	}

	@Override
	public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("renumbered lambda selectors left as written with -D" + PROPERTY + "=off");
		return AnchorSet.scanned("which guest mixins carry a renumbered lambda selector is not a fixed list; each is "
				+ "resolved against the class its selector already names, so no class can be declared here");
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0 || !enabled()) return classBytes;
		// Cheap reject: only a mixin can carry an injector selector, and a non-mixin class is most of the traffic.
		if (!isMixin(classBytes)) return classBytes;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		int rewritten = 0;
		for (MethodNode method : node.methods) {
			for (AnnotationNode annotation : allAnnotations(method)) {
				if (!GuestInjectorPruner.INJECTOR_DESCS.contains(annotation.desc)) continue;
				rewritten += retarget(annotation, node);
			}
		}
		if (rewritten == 0) return classBytes;
		ForbricLog.info("[Forbric/LambdaSelectorRetarget] rewrote %d lambda selector(s) in %s to the member the "
				+ "merged base declares", rewritten, className);

		// Only annotation values changed: no instruction, frame or local was touched, so nothing needs recomputing.
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** Rewrites every {@code method} selector of one injector annotation; returns how many changed. */
	@SuppressWarnings("unchecked")
	private int retarget(AnnotationNode annotation, ClassNode mixin) {
		if (annotation.values == null) return 0;
		int count = 0;
		for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
			if (!"method".equals(annotation.values.get(i))) continue;
			Object value = annotation.values.get(i + 1);
			if (value instanceof String selector) {
				String fixed = retargeted(selector, mixin);
				if (fixed != null) { annotation.values.set(i + 1, fixed); count++; }
			} else if (value instanceof List<?> selectors) {
				// ASM hands annotation arrays over as a mutable ArrayList; copy so an immutable fixture list is safe too.
				List<Object> replaced = new ArrayList<>((List<Object>) selectors);
				boolean changed = false;
				for (int j = 0; j < replaced.size(); j++) {
					if (!(replaced.get(j) instanceof String selector)) continue;
					String fixed = retargeted(selector, mixin);
					if (fixed != null) { replaced.set(j, fixed); changed = true; count++; }
				}
				if (changed) annotation.values.set(i + 1, replaced);
			}
		}
		return count;
	}

	/** The rewritten selector, or {@code null} when this selector is not a renumbering this class may make. */
	private String retargeted(String raw, ClassNode mixin) {
		Selector selector = parse(raw);
		if (selector == null || selector.name() == null || selector.desc() == null) return null;
		Matcher lambda = LAMBDA.matcher(selector.name());
		if (!lambda.matches()) return null;
		String enclosing = lambda.group(1);

		List<String> owners = selector.owner() != null ? List.of(selector.owner()) : MixinFit.mixinTargets(mixin);
		String replacement = null;
		for (String owner : owners) {
			byte[] bytes = mergedBase.apply(owner);
			if (bytes == null) return null;
			List<String> candidates = candidates(bytes, enclosing, selector.desc());
			// More than one means the NUMBER cannot be trusted on its own — but the merge keeps a dead duplicate of a
			// lambda beside the body it actually wired into the invokedynamic, and that reference settles it. Measured
			// on the merged 1.21.1 LivingEntity: lambda$stopSleeping$9(BlockPos)V has two bodies ($11, $12) and only
			// $12 is the Optional.ifPresent handle from stopSleeping; lambda$checkBedExists$7(BlockPos)Boolean has
			// two ($9, $10) and only $10 is referenced. A class that references both — or neither — still declines.
			if (candidates.size() > 1) candidates = liveMembers(bytes, candidates, selector.desc());
			if (candidates.size() != 1) return null;
			String candidate = candidates.getFirst();
			if (replacement != null && !replacement.equals(candidate)) return null;
			replacement = candidate;
		}
		// Nothing to say, or already the member the base declares: leave the bytes identical.
		if (replacement == null || replacement.equals(selector.name())) return null;
		int at = raw.indexOf(selector.name());
		if (at < 0) return null;
		return raw.substring(0, at) + replacement + raw.substring(at + selector.name().length());
	}

	/** Every method of {@code ownerBytes} named {@code lambda$<enclosing>$<n>} with {@code desc}. */
	private static List<String> candidates(byte[] ownerBytes, String enclosing, String desc) {
		ClassNode owner = new ClassNode();
		new ClassReader(ownerBytes).accept(owner, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		String prefix = "lambda$" + enclosing + "$";
		List<String> found = new ArrayList<>(1);
		for (MethodNode method : owner.methods) {
			if (!method.name.startsWith(prefix) || !desc.equals(method.desc)) continue;
			String counter = method.name.substring(prefix.length());
			if (counter.isEmpty() || !counter.chars().allMatch(Character::isDigit)) continue;
			found.add(method.name);
		}
		return found;
	}

	/**
	 * Of {@code candidates}, the ones this class itself references through a method handle — a lambda body wired into
	 * an {@code invokedynamic} (LambdaMetafactory) or held as an {@code ldc} constant. A merge that kept a dead
	 * duplicate of the body leaves exactly one referenced; both referenced (or none) is not a decision this may make.
	 */
	private static List<String> liveMembers(byte[] ownerBytes, List<String> candidates, String desc) {
		ClassNode owner = new ClassNode();
		new ClassReader(ownerBytes).accept(owner, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		Set<String> wanted = new HashSet<>(candidates);
		Set<String> live = new LinkedHashSet<>();
		for (MethodNode method : owner.methods) {
			for (AbstractInsnNode instruction : method.instructions.toArray()) {
				if (instruction instanceof InvokeDynamicInsnNode dynamic) {
					for (Object argument : dynamic.bsmArgs) {
						if (argument instanceof Handle handle) record(live, wanted, handle, owner.name, desc);
					}
				} else if (instruction instanceof LdcInsnNode constant && constant.cst instanceof Handle handle) {
					record(live, wanted, handle, owner.name, desc);
				}
			}
		}
		return new ArrayList<>(live);
	}

	private static void record(Set<String> live, Set<String> wanted, Handle handle, String owner, String desc) {
		if (handle.getOwner().equals(owner) && handle.getDesc().equals(desc) && wanted.contains(handle.getName())) {
			live.add(handle.getName());
		}
	}

	/** Whether {@code classBytes} carries a {@code @Mixin}, the one class annotation every mixin has. */
	private static boolean isMixin(byte[] classBytes) {
		boolean[] mixin = {false};
		new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
				if (MIXIN_DESC.equals(desc)) mixin[0] = true;
				return null;
			}
		}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return mixin[0];
	}

	private static List<AnnotationNode> allAnnotations(MethodNode method) {
		List<AnnotationNode> out = new ArrayList<>();
		if (method.visibleAnnotations != null) out.addAll(method.visibleAnnotations);
		if (method.invisibleAnnotations != null) out.addAll(method.invisibleAnnotations);
		return out;
	}

	private record Selector(String owner, String name, String desc) { }

	/**
	 * Mixin's member selector ({@code name}, {@code name(desc)ret}, {@code Lowner;name(desc)ret}, {@code name:desc},
	 * quantifier suffixes), the shapes the census already models. Null for patterns, dynamic selectors and anything
	 * without a name; the owner is a binary internal name.
	 */
	static Selector parse(String raw) {
		if (raw == null) return null;
		String text = raw.trim();
		if (text.isEmpty() || text.startsWith("/") || text.startsWith("@") || text.contains("->")) return null;
		String owner = null;
		int dot = text.lastIndexOf('.');
		int semicolon = text.indexOf(';');
		if (dot > -1) {
			owner = text.substring(0, dot).replace('.', '/');
			text = text.substring(dot + 1);
		} else if (semicolon > -1 && text.startsWith("L")) {
			owner = text.substring(1, semicolon);
			text = text.substring(semicolon + 1);
		}
		String name;
		String desc = null;
		int paren = text.indexOf('(');
		if (paren > -1) {
			name = text.substring(0, paren);
			desc = text.substring(paren);
			int close = desc.indexOf(')');
			if (close < 0 || close == desc.length() - 1) return null;
		} else {
			int colon = text.indexOf(':');
			if (colon > -1) {
				name = text.substring(0, colon);
				desc = text.substring(colon + 1);
				if (desc.isEmpty()) return null;
			} else {
				name = text.replaceFirst("(\\*|\\+|\\{[0-9,]*\\})$", "");
			}
		}
		if (name.isEmpty() || name.contains("/")) return null;
		return new Selector(owner, name, desc);
	}
}
