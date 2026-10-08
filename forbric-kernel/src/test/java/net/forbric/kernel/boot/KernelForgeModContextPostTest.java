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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

/**
 * The traditional-Forge bus carries TWO {@code post} overloads, and the resolver must take the one-argument one
 * by its parameter TYPE rather than by name.
 *
 * <p>EventBus 6's {@code IEventBus} declares {@code post(Event)} beside
 * {@code post(Event, IEventBusInvokeDispatcher)}, and {@code Class.getMethods()} yields them in an order the JVM
 * does not specify. Resolving by name alone returned the two-argument one here, so
 * {@code invoke(bus, event)} threw {@code IllegalArgumentException: wrong number of arguments: 1 expected: 2}
 * for every handle and {@code NewRegistryEvent} never reached a single {@code DeferredRegister} — which is how
 * every Forge custom registry (forge:fluid_type, forge:condition_codecs, the modifier serializers, …) came to
 * be absent, surfacing only later as MinecraftForge's "Failed to apply some object holders" at world exit.
 */
class KernelForgeModContextPostTest {

	@Test
	void resolvesTheOneArgumentPostWhenTheBusAlsoDeclaresATwoArgumentOne() throws Exception {
		assertEquals(2, countPostsNamed("post", EventBusShaped.class),
				"the fixture must carry both overloads or this asserts nothing about the choice");

		Method post = KernelForgeModContext.eventBusPost(EventBusShaped.class, Event.class);

		assertEquals(1, post.getParameterCount(),
				"invoke(bus, event) supplies exactly one argument after the receiver, so a two-argument post is "
						+ "the wrong number of arguments at call time");
		assertEquals(Event.class, post.getParameterTypes()[0]);
		assertEquals(boolean.class, post.getReturnType());
	}

	@Test
	void theChosenPostIsInvocableWithABusAndAnEvent() throws Exception {
		EventBusShaped bus = new EventBusShaped();
		Object handled = KernelForgeModContext.eventBusPost(EventBusShaped.class, Event.class)
				.invoke(bus, new Event("forge:fluid_type"));

		assertEquals(Boolean.TRUE, handled);
		assertEquals("forge:fluid_type", bus.last);
	}

	@Test
	void aBusWithoutThatPostFailsLoudlyAndNamesTheShape() {
		IllegalStateException missing = assertThrows(IllegalStateException.class,
				() -> KernelForgeModContext.eventBusPost(NoPostAtAll.class, Event.class));

		assertTrue(missing.getMessage().contains("post"), missing.getMessage());
		assertTrue(missing.getMessage().contains(Event.class.getName()),
				"the message names the parameter type it looked for: " + missing.getMessage());
	}

	private static int countPostsNamed(String name, Class<?> owner) {
		int found = 0;
		for (Method method : owner.getMethods()) {
			if (method.getName().equals(name)) found++;
		}
		return found;
	}

	/** The shape that matters: the same first parameter type on a one- and a two-argument overload. */
	public static final class EventBusShaped {
		private String last;

		public boolean post(Event event) {
			last = event.name;
			return true;
		}

		public boolean post(Event event, Object dispatcher) {
			return false;
		}
	}

	public static final class NoPostAtAll {
	}

	public static final class Event {
		private final String name;

		Event(String name) {
			this.name = name;
		}
	}
}
