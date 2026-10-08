package dev.aek.shootingstardemo.mc1211.devtest;

import java.lang.reflect.Method;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import dev.aek.shootingstardemo.mc1211.client.magic.ClientSkills;
import dev.aek.shootingstardemo.mc1211.magic.MagicItem;
import dev.aek.shootingstardemo.mc1211.magic.Skill;
import dev.aek.shootingstardemo.mc1211.registry.ModItems;
import dev.aek.shootingstardemo.mc1211.registry.ModSkills;

/**
 * cannot-fire lane instrument. The demo's own dev hook: {@code ShootingStarDemoClient.init} reflectively calls
 * {@code <pkg>.client.DevAutoTest.register()} and swallows ClassNotFoundException, so this class — shipped on the
 * library path, OUTSIDE the artifact under test — is the mod's own extension point and nothing else.
 *
 * It reproduces the click's cast without a mouse: give the Stellar Remote to the integrated server's player, then
 * call the private {@code ShootingStarDemoClient.onUse(Player, MagicItem)} — the exact method {@code MagicItem.use}
 * (right-click) invokes, and the only place a {@code CastSkillPayload} is ever built. The mod's own client state is
 * then polled: {@code ClientSkills.remaining(skill)} is written only by {@code ClientSkills.apply(CooldownPayload)},
 * which the server sends from {@code Casting.syncCooldowns} immediately after it ACCEPTS a cast. A non-zero reading
 * therefore means the packet reached the server's handler; a permanently empty array means it did not.
 */
public final class DevAutoTest {
	private static int ticks;
	private static boolean armed;
	private static boolean fired;
	private static int firedAt = -1;
	private static int lastRemaining = -1;
	private static boolean reported;

	private DevAutoTest() {
	}

	public static void register() {
		try {
			System.out.println("[CANNOTFIRE/PROBE] DevAutoTest.register() called — probe is live");
			NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, (Consumer<ClientTickEvent.Post>) DevAutoTest::tick);
		} catch (Throwable t) {
			// The caller wraps a ReflectiveOperationException into "The dev auto test could not start" and fails the
			// mod's construction, so this method must never throw.
			System.out.println("[CANNOTFIRE/PROBE] register() could not subscribe: " + t);
		}
	}

	private static void tick(ClientTickEvent.Post event) {
		try {
			ticks++;
			Minecraft mc = Minecraft.getInstance();
			LocalPlayer player = mc.player;
			if (player == null || mc.level == null) return;

			if (!armed) {
				armed = true;
				Object server = mc.getSingleplayerServer();
				if (server instanceof net.minecraft.server.MinecraftServer integrated) {
					integrated.execute(() -> {
						for (var sp : integrated.getPlayerList().getPlayers()) {
							sp.getInventory().setItem(0, new ItemStack(ModItems.STELLAR_REMOTE));
							sp.getInventory().selected = 0;
							System.out.println("[CANNOTFIRE/PROBE] gave the Stellar Remote to "
									+ sp.getStringUUID() + " (server side, hotbar slot 0)");
						}
					});
				} else {
					System.out.println("[CANNOTFIRE/PROBE] no integrated server — cannot arm");
				}
				return;
			}

			if (!fired) {
				if (ticks < 40) return;
				MagicItem item = MagicItem.held(player);
				if (item == null) {
					if (ticks % 40 == 0) System.out.println("[CANNOTFIRE/PROBE] tick " + ticks + ": not holding the remote yet");
					return;
				}
				fired = true;
				firedAt = ticks;
				Method onUse = Class.forName("dev.aek.shootingstardemo.mc1211.client.ShootingStarDemoClient")
						.getDeclaredMethod("onUse", net.minecraft.world.entity.player.Player.class, MagicItem.class);
				onUse.setAccessible(true);
				onUse.invoke(null, player, item);
				Skill skill = ModSkills.STELLAR_REMOTE.skills.get(0);
				System.out.println("[CANNOTFIRE/PROBE] tick " + ticks + ": called ShootingStarDemoClient.onUse — the cast the click makes"
						+ " (selected skill '" + skill.title() + "', ClientSkills.remaining before = " + ClientSkills.remaining(skill) + ")");
				return;
			}

			if (firedAt < 0 || ticks - firedAt < 12) return;
			Skill skill = ModSkills.STELLAR_REMOTE.skills.get(0);
			int remaining = ClientSkills.remaining(skill);
			if (remaining != lastRemaining) {
				lastRemaining = remaining;
				System.out.println("[CANNOTFIRE/PROBE] tick+" + (ticks - firedAt) + ": ClientSkills.remaining(" + skill.title() + ")=" + remaining);
			}
			if (remaining > 0 && !reported) {
				reported = true;
				System.out.println("[CANNOTFIRE/PROBE] RESULT=CAST_ACCEPTED — the server took the cast; cooldown " + remaining
						+ " ticks came back over the mod's own payload");
			}
			if (ticks - firedAt == 150 && remaining == 0) {
				System.out.println("[CANNOTFIRE/PROBE] RESULT=CAST_DROPPED — 150 ticks after onUse, no CooldownPayload ever arrived");
			}
		} catch (Throwable t) {
			System.out.println("[CANNOTFIRE/PROBE] tick failed: " + t);
		}
	}
}
