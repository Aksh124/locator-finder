package locatorfinder;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import java.util.List;
import java.util.UUID;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.waypoints.ClientWaypointManager;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.waypoints.TrackedWaypoint;
import net.minecraft.world.waypoints.Waypoint;

public final class LocatorFinderClient implements ClientModInitializer {
	private static final int MAX_HUD_LINES = 10;
	private static boolean hudEnabled = true;

	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(LocatorTracker::tick);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> registerCommands(dispatcher));
		HudElementRegistry.attachElementBefore(
			VanillaHudElements.CHAT,
			Identifier.fromNamespaceAndPath("locatorfinder", "targets"),
			(graphics, tickCounter) -> drawHud(graphics));

		if (Boolean.getBoolean("locatorfinder.selftest")) {
			selfTest();
		}
	}

	private static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(literal("lf")
			.executes(context -> {
				List<String> lines = LocatorTracker.describeAll(Minecraft.getInstance());
				if (lines.isEmpty()) {
					context.getSource().sendFeedback(Component.literal("The server isn't showing anyone on the locator bar."));
				}
				lines.forEach(line -> context.getSource().sendFeedback(Component.literal(line)));
				return Command.SINGLE_SUCCESS;
			})
			.then(literal("hud").executes(context -> {
				hudEnabled = !hudEnabled;
				context.getSource().sendFeedback(Component.literal("Locator Finder HUD " + (hudEnabled ? "on" : "off")));
				return Command.SINGLE_SUCCESS;
			}))
			.then(literal("clear").executes(context -> {
				LocatorTracker.clear();
				context.getSource().sendFeedback(Component.literal("Cleared all tracked positions."));
				return Command.SINGLE_SUCCESS;
			})));
	}

	private static void drawHud(GuiGraphicsExtractor graphics) {
		Minecraft mc = Minecraft.getInstance();
		if (!hudEnabled || mc.gui.hud.isHidden()) {
			return;
		}

		List<String> lines = LocatorTracker.describeAll(mc);
		int lineHeight = mc.font.lineHeight + 1;
		int right = graphics.guiWidth() - 4;
		int y = 4;
		for (String line : lines.subList(0, Math.min(lines.size(), MAX_HUD_LINES))) {
			int left = right - mc.font.width(line);
			graphics.fill(left - 2, y - 1, right + 2, y + lineHeight - 1, 0x80000000);
			graphics.text(mc.font, line, left, y, 0xFFFFFFFF);
			y += lineHeight;
		}
	}

	/**
	 * Dev-only startup check (-Dlocatorfinder.selftest=true). Waypoint classes otherwise load only
	 * after joining a world, so a broken mixin would stay hidden until then.
	 */
	private static void selfTest() {
		ClientWaypointManager manager = new ClientWaypointManager();
		Waypoint.Icon icon = new Waypoint.Icon();

		manager.trackWaypoint(TrackedWaypoint.setAzimuth(UUID.randomUUID(), icon, 1.25f));
		check(LocatorTracker.lastReading, LocatorTracker.Kind.BEARING);
		if (LocatorTracker.lastReading.azimuth() != 1.25f) {
			throw new IllegalStateException("wrong azimuth: " + LocatorTracker.lastReading);
		}

		manager.trackWaypoint(TrackedWaypoint.setChunk(UUID.randomUUID(), icon, new ChunkPos(3, -2)));
		check(LocatorTracker.lastReading, LocatorTracker.Kind.CHUNK);
		if (LocatorTracker.lastReading.x() != 56 || LocatorTracker.lastReading.z() != -24) {
			throw new IllegalStateException("wrong chunk centre: " + LocatorTracker.lastReading);
		}

		manager.trackWaypoint(TrackedWaypoint.setPosition(UUID.randomUUID(), icon, new Vec3i(10, 64, -20)));
		check(LocatorTracker.lastReading, LocatorTracker.Kind.EXACT);
		if (LocatorTracker.lastReading.y() != 64) {
			throw new IllegalStateException("wrong position: " + LocatorTracker.lastReading);
		}

		System.out.println("[locatorfinder] self test passed");
	}

	private static void check(LocatorTracker.Reading reading, LocatorTracker.Kind expected) {
		if (reading == null || reading.kind() != expected) {
			throw new IllegalStateException("expected " + expected + " but read " + reading);
		}
	}
}
