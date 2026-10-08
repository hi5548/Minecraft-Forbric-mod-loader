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

package net.forbric.kernel.boot;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;

/**
 * MinecraftForge's side for the thread it is asked about, on a base whose threads NeoForge built.
 *
 * <p>{@code net.minecraftforge.fml.util.thread.EffectiveSide.get()} answers from the thread's {@link ThreadGroup}:
 * a {@code net.minecraftforge.fml.util.thread.SidedThreadGroup} carries the side, and <em>anything else is
 * CLIENT</em>. Genuine MinecraftForge builds its server thread and its network event loops inside its own SERVER
 * group; NeoForge builds both inside NeoForge's. The byte merge kept NeoForge's half of both patches — its own
 * conflict ledger lists {@code net/minecraft/server/MinecraftServer#spin … (forge hook lost)} — so on the merged
 * base every server-side thread is in NeoForge's group and Forge's {@code EffectiveSide.get()} answers CLIENT on
 * it. Forge's other group site, the login thread in {@code ServerLoginPacketListenerImpl}, survived; the base is
 * internally inconsistent, which is what makes this a merge repair rather than either ecosystem's behaviour.
 *
 * <p>That is not cosmetic. {@code ForgeHooks.onCustomPayload} — which the merged
 * {@code ServerGamePacketListenerImpl.handleCustomPayload} still calls for every serverbound play payload —
 * compares {@code EffectiveSide.get()} with the connection's direction and disconnects on a mismatch, so the
 * first custom payload a mod sent upward ended the connection with
 * "Illegal packet received, terminating connection".
 *
 * <p>The threads are not moved into Forge's group: NeoForge keeps them in its own for its own
 * {@code EffectiveSide}, and only one of the two can own a thread group. The losing reader is served instead —
 * this answers with the Forge group that carries the side the thread is really on — and the repaired
 * {@code EffectiveSide.get()} calls it (see
 * {@code ForbricMergedBaseCompatTransformer#letMinecraftForgeReadTheMergedThreadGroups}).
 *
 * <p>Boot-side, and answering in {@code Object}: the caller is transformed game bytecode, and no Forge type may be
 * named here (the boot half is also the half this machine can rebuild without the staged game jars).
 */
public final class ForgeSidedThreads {
	private static volatile ClassLoader resolvedFor;
	private static volatile Object[] cachedSides;

	private ForgeSidedThreads() {
	}

	/**
	 * The Forge {@code SidedThreadGroup} that carries {@code Thread.currentThread()}'s side: Forge's SERVER group
	 * when the thread is in NeoForge's SERVER group — where the merged base builds the server thread and the
	 * connection event loops — and Forge's CLIENT group otherwise (every other thread, including the two
	 * ecosystems' client threads, answered CLIENT before this too).
	 *
	 * <p>{@code caller} is the class that asked (the repaired {@code EffectiveSide}), used only to resolve Forge's
	 * and NeoForge's classes through the loader that owns them. Never null once Forge's {@code EffectiveSide}
	 * exists, which is the only caller.
	 */
	public static Object groupFor(Class<?> caller) {
		Object[] sides = sides(caller != null ? caller.getClassLoader() : null);
		ThreadGroup group = Thread.currentThread().getThreadGroup();
		return group != null && group == sides[2] ? sides[1] : sides[0];
	}

	/** {@code {forge CLIENT, forge SERVER, NeoForge SERVER}} for {@code loader}, resolved once per loader. */
	private static Object[] sides(ClassLoader loader) {
		Object[] cached = cachedSides;
		if (cached != null && resolvedFor == loader) return cached;
		synchronized (ForgeSidedThreads.class) {
			if (cachedSides != null && resolvedFor == loader) return cachedSides;
			ClassLoader effective = loader != null ? loader : ClassLoader.getSystemClassLoader();
			Object client = staticField(effective, ForeignType.SIDED_THREAD_GROUPS.binary(Ecosystem.FORGE), "CLIENT");
			Object server = staticField(effective, ForeignType.SIDED_THREAD_GROUPS.binary(Ecosystem.FORGE), "SERVER");
			Object neo = staticField(effective, ForeignType.SIDED_THREAD_GROUPS.binary(Ecosystem.NEOFORGE), "SERVER");
			Object[] resolved = {client, server, neo};
			resolvedFor = loader;
			cachedSides = resolved;
			return resolved;
		}
	}

	private static Object staticField(ClassLoader loader, String owner, String name) {
		try {
			return Class.forName(owner, false, loader).getField(name).get(null);
		} catch (Throwable absent) {
			// A carrier without the class, or without the field, is a debug fact and not a failure: the only
			// caller cannot run without Forge's EffectiveSide, and a NeoForge-less base is a legitimate instance.
			return null;
		}
	}
}
