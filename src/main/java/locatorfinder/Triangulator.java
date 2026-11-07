package locatorfinder;

import java.util.ArrayDeque;

/**
 * Estimates a target's X/Z position from bearings taken at different places.
 *
 * <p>A bearing is the azimuth the server sends for a player more than 332 blocks away. It equals
 * the yaw from the observer to the target, so the direction to the target is (-sin a, cos a) and
 * the target lies on the line through the observer with that direction. Each bearing gives one
 * line, and the estimate is the point closest to all of them (linear least squares).
 *
 * <p>Pure Java with no Minecraft types, so it can be unit tested without the game.
 */
public final class Triangulator {
	/** @param errorRadius estimated error in blocks, roughly a 95% radius */
	public record Fix(double x, double z, double errorRadius, int samples) {}

	private record Bearing(double x, double z, double sin, double cos, long timeMs) {}

	private static final int MAX_SAMPLES = 128;
	/** Older bearings are dropped, since the target has probably moved since then. */
	private static final long MAX_AGE_MS = 90_000;
	/** A line further than this from the estimate means the target moved between bearings. */
	private static final double MAX_RESIDUAL = 16.0;
	/** Assumed error in our own position when a bearing was taken, in blocks. */
	private static final double POSITION_SIGMA = 1.5;
	/** Below this the lines are effectively parallel (about 0.2 degrees apart). */
	private static final double MIN_DETERMINANT = 1e-5;
	private static final double WORLD_LIMIT = 30_000_000.0;

	private final ArrayDeque<Bearing> bearings = new ArrayDeque<>();

	public void add(double x, double z, double azimuth, long timeMs) {
		bearings.addLast(new Bearing(x, z, Math.sin(azimuth), Math.cos(azimuth), timeMs));
		if (bearings.size() > MAX_SAMPLES) {
			bearings.removeFirst();
		}
	}

	public void clear() {
		bearings.clear();
	}

	public int size() {
		return bearings.size();
	}

	/**
	 * Returns the best estimate, or null if the bearings cannot give one yet. Discards the oldest
	 * bearings until the rest agree, so a target that moved is re-acquired instead of averaged.
	 */
	public Fix solve(long nowMs) {
		while (!bearings.isEmpty() && nowMs - bearings.peekFirst().timeMs() > MAX_AGE_MS) {
			bearings.removeFirst();
		}

		while (bearings.size() >= 2) {
			double a = 0, b = 0, d = 0, bx = 0, bz = 0;
			for (Bearing s : bearings) {
				double along = s.cos() * s.x() + s.sin() * s.z();
				a += s.cos() * s.cos();
				b += s.cos() * s.sin();
				d += s.sin() * s.sin();
				bx += s.cos() * along;
				bz += s.sin() * along;
			}

			double det = a * d - b * b;
			if (det < MIN_DETERMINANT) {
				return null;
			}
			double x = (d * bx - b * bz) / det;
			double z = (a * bz - b * bx) / det;

			double squaredResiduals = 0;
			boolean consistent = Math.abs(x) <= WORLD_LIMIT && Math.abs(z) <= WORLD_LIMIT;
			for (Bearing s : bearings) {
				double dx = x - s.x();
				double dz = z - s.z();
				double residual = s.cos() * dx + s.sin() * dz;
				double ahead = -s.sin() * dx + s.cos() * dz;
				squaredResiduals += residual * residual;
				if (Math.abs(residual) > MAX_RESIDUAL || ahead <= 0) {
					consistent = false;
				}
			}

			if (consistent) {
				int n = bearings.size();
				double variance = Math.max(POSITION_SIGMA * POSITION_SIGMA, n > 2 ? squaredResiduals / (n - 2) : 0);
				double trace = a + d;
				double largestEigenvalue = (trace + Math.sqrt(Math.max(0, trace * trace - 4 * det))) / (2 * det);
				return new Fix(x, z, 2 * Math.sqrt(variance * largestEigenvalue), n);
			}
			bearings.removeFirst();
		}
		return null;
	}
}
