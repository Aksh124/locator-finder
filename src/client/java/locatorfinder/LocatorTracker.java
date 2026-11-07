package locatorfinder;

import com.mojang.datafixers.util.Either;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import locatorfinder.mixin.AzimuthWaypointAccess;
import locatorfinder.mixin.ChunkWaypointAccess;
import locatorfinder.mixin.Vec3iWaypointAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.waypoints.TrackedWaypoint;

/** Turns waypoint updates into a position for each player the server reports. All on the client thread. */
public final class LocatorTracker {
	public enum Kind { EXACT, CHUNK, BEARING }

	/** What one waypoint update says. Y is only meaningful for EXACT, azimuth only for BEARING. */
	public record Reading(Kind kind, double x, double y, double z, float azimuth) {}

	private record PastPosition(long timeMs, double x, double z) {}

	private static final class Target {
		final Triangulator triangulator = new Triangulator();
		String name;
		Reading reading;
		Triangulator.Fix fix;
		long lastUpdateMs;
		boolean lost;
	}

	private static final long FORGET_LOST_AFTER_MS = 5 * 60_000;
	private static final long POSITION_HISTORY_MS = 3_000;
	/** The server recalculates on its 50ms tick, so a bearing is on average half a tick late. */
	private static final long SERVER_TICK_DELAY_MS = 25;

	private static final Map<Either<UUID, String>, Target> TARGETS = new HashMap<>();
	private static final ArrayDeque<PastPosition> HISTORY = new ArrayDeque<>();
	private static ClientLevel lastLevel;
	static Reading lastReading;

	private LocatorTracker() {}

	public static void onWaypoint(TrackedWaypoint waypoint) {
		Reading reading = read(waypoint);
		lastReading = reading;
		Minecraft mc = Minecraft.getInstance();
		if (reading == null || mc == null || mc.player == null) {
			return;
		}

		long now = System.currentTimeMillis();
		Target target = TARGETS.computeIfAbsent(waypoint.id(), id -> new Target());
		target.name = displayName(mc, waypoint.id());
		target.reading = reading;
		target.lastUpdateMs = now;
		target.lost = false;

		if (reading.kind() == Kind.BEARING) {
			PastPosition from = positionServerUsed(mc, now);
			target.triangulator.add(from.x(), from.z(), reading.azimuth(), now);
			target.fix = target.triangulator.solve(now);
		}
	}

	public static void onRemoved(TrackedWaypoint waypoint) {
		Target target = TARGETS.get(waypoint.id());
		if (target != null) {
			target.lost = true;
		}
	}

	private static Reading read(TrackedWaypoint waypoint) {
		if (waypoint instanceof Vec3iWaypointAccess access) {
			Vec3i v = access.locatorfinder$vector();
			return new Reading(Kind.EXACT, v.getX(), v.getY(), v.getZ(), 0);
		}
		if (waypoint instanceof ChunkWaypointAccess access) {
			ChunkPos chunk = access.locatorfinder$chunkPos();
			return new Reading(Kind.CHUNK, chunk.getMiddleBlockX(), 0, chunk.getMiddleBlockZ(), 0);
		}
		if (waypoint instanceof AzimuthWaypointAccess access) {
			return new Reading(Kind.BEARING, 0, 0, 0, access.locatorfinder$angle());
		}
		return null;
	}

	private static String displayName(Minecraft mc, Either<UUID, String> id) {
		return id.map(uuid -> {
			ClientPacketListener connection = mc.getConnection();
			PlayerInfo info = connection == null ? null : connection.getPlayerInfo(uuid);
			return info != null ? info.getProfile().name() : "unknown " + uuid.toString().substring(0, 8);
		}, text -> text);
	}

	/** Runs at the end of each client tick. */
	public static void tick(Minecraft mc) {
		if (mc.level != lastLevel) {
			lastLevel = mc.level;
			clear();
		}
		LocalPlayer player = mc.player;
		if (player == null) {
			return;
		}

		long now = System.currentTimeMillis();
		HISTORY.addLast(new PastPosition(now, player.getX(), player.getZ()));
		while (now - HISTORY.peekFirst().timeMs() > POSITION_HISTORY_MS) {
			HISTORY.removeFirst();
		}
		TARGETS.values().removeIf(t -> t.lost && now - t.lastUpdateMs > FORGET_LOST_AFTER_MS);
	}

	/**
	 * A bearing arriving now was worked out from where the server last thought we were, which is
	 * about one round trip old. Moving fast without this shifts every bearing line sideways.
	 */
	private static PastPosition positionServerUsed(Minecraft mc, long now) {
		LocalPlayer player = mc.player;
		PlayerInfo self = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(player.getUUID());
		long rewindTo = now - SERVER_TICK_DELAY_MS - (self == null ? 0 : self.getLatency());

		PastPosition best = new PastPosition(now, player.getX(), player.getZ());
		for (PastPosition past : HISTORY) {
			if (Math.abs(past.timeMs() - rewindTo) < Math.abs(best.timeMs() - rewindTo)) {
				best = past;
			}
		}
		return best;
	}

	public static void clear() {
		TARGETS.clear();
		HISTORY.clear();
	}

	/** One line per target, nearest first. */
	public static List<String> describeAll(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null) {
			return List.of();
		}
		record Line(double distance, String text) {}

		List<Line> lines = new ArrayList<>();
		for (Target target : TARGETS.values()) {
			double distance = estimatedDistance(target, player);
			lines.add(new Line(Double.isNaN(distance) ? Double.MAX_VALUE : distance, describe(target, player, distance)));
		}
		lines.sort(Comparator.comparingDouble(Line::distance));
		return lines.stream().map(Line::text).toList();
	}

	private static boolean hasUsableFix(Target target, LocalPlayer player) {
		Triangulator.Fix fix = target.fix;
		if (fix == null) {
			return false;
		}
		double distance = Math.hypot(fix.x() - player.getX(), fix.z() - player.getZ());
		return fix.errorRadius() <= Math.max(32, distance * 0.2);
	}

	private static double estimatedDistance(Target target, LocalPlayer player) {
		Reading r = target.reading;
		if (r.kind() != Kind.BEARING) {
			return Math.hypot(r.x() - player.getX(), r.z() - player.getZ());
		}
		if (hasUsableFix(target, player)) {
			return Math.hypot(target.fix.x() - player.getX(), target.fix.z() - player.getZ());
		}
		return Double.NaN;
	}

	private static String describe(Target target, LocalPlayer player, double distance) {
		Reading r = target.reading;
		String suffix = target.lost ? " [last known]" : "";
		String away = Double.isNaN(distance) ? "" : String.format(Locale.ROOT, " %.0fm", distance);
		return switch (r.kind()) {
			case EXACT -> String.format(Locale.ROOT, "%s: %.0f %.0f %.0f (exact)%s%s", target.name, r.x(), r.y(), r.z(), away, suffix);
			case CHUNK -> String.format(Locale.ROOT, "%s: %.0f ~ %.0f (±8)%s%s", target.name, r.x(), r.z(), away, suffix);
			case BEARING -> hasUsableFix(target, player)
				? String.format(Locale.ROOT, "%s: %.0f ~ %.0f (±%.0f)%s%s", target.name, target.fix.x(), target.fix.z(), target.fix.errorRadius(), away, suffix)
				: String.format(Locale.ROOT, "%s: to the %s, move sideways%s", target.name, compass(r.azimuth()), suffix);
		};
	}

	/** Azimuths are yaws: 0 is south, a quarter turn is west. */
	private static String compass(float azimuth) {
		String[] names = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
		int index = (int) Math.round(Math.toDegrees(azimuth) / 45.0);
		return names[Math.floorMod(index, names.length)];
	}
}
