package locatorfinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class TriangulatorTest {
	/** The vanilla server's calculation in WaypointTransmitter.EntityAzimuthConnection. */
	private static float serverAzimuth(double observerX, double observerZ, double targetX, double targetZ) {
		double dx = observerX - targetX;
		double dz = observerZ - targetZ;
		// Vec3.rotateClockwise90 maps (x, z) to (-z, x), then Mth.atan2(z, x).
		return (float) Math.atan2(dx, -dz);
	}

	@Test
	void azimuthMatchesTheYawThatFacesTheTarget() {
		// Minecraft yaw 0 faces south (+Z) and yaw 90 faces west (-X).
		assertEquals(0, Math.toDegrees(serverAzimuth(0, 0, 0, 1000)), 1e-3);
		assertEquals(90, Math.toDegrees(serverAzimuth(0, 0, -1000, 0)), 1e-3);
		assertEquals(-90, Math.toDegrees(serverAzimuth(0, 0, 1000, 0)), 1e-3);
		assertEquals(180, Math.abs(Math.toDegrees(serverAzimuth(0, 0, 0, -1000))), 1e-3);
	}

	@Test
	void twoBearingsFindAStationaryTarget() {
		Triangulator t = new Triangulator();
		t.add(0, 0, serverAzimuth(0, 0, 3000, -2000), 0);
		t.add(500, 0, serverAzimuth(500, 0, 3000, -2000), 1000);

		Triangulator.Fix fix = t.solve(1000);
		assertNotNull(fix);
		assertEquals(3000, fix.x(), 1);
		assertEquals(-2000, fix.z(), 1);
	}

	@Test
	void walkingSidewaysWithPositionLagGetsClose() {
		double targetX = -4200, targetZ = 7300;
		double distance = Math.hypot(targetX, targetZ);
		double sideX = targetZ / distance, sideZ = -targetX / distance;

		Random random = new Random(42);
		Triangulator t = new Triangulator();
		Triangulator.Fix fix = null;
		for (int i = 0; i <= 80; i++) {
			double x = sideX * i * 5, z = sideZ * i * 5;
			float azimuth = serverAzimuth(x, z, targetX, targetZ);
			// The position we record is up to a couple of blocks off from what the server used.
			t.add(x + random.nextGaussian(), z + random.nextGaussian(), azimuth, i * 500L);
			fix = t.solve(i * 500L);
		}

		assertNotNull(fix);
		double error = Math.hypot(fix.x() - targetX, fix.z() - targetZ);
		assertTrue(error < 60, "error was " + error);
		assertTrue(error <= fix.errorRadius(), "error " + error + " outside radius " + fix.errorRadius());
	}

	@Test
	void walkingStraightAtTheTargetGivesNoFix() {
		Triangulator t = new Triangulator();
		for (int i = 0; i < 20; i++) {
			double z = i * 10;
			t.add(0, z, serverAzimuth(0, z, 0, 5000), i * 100L);
		}
		assertNull(t.solve(2000));
	}

	@Test
	void singleBearingGivesNoFix() {
		Triangulator t = new Triangulator();
		t.add(0, 0, 1f, 0);
		assertNull(t.solve(0));
	}

	@Test
	void reacquiresATargetThatTeleported() {
		Triangulator t = new Triangulator();
		for (int i = 0; i < 20; i++) {
			t.add(i * 20, 0, serverAzimuth(i * 20, 0, 1000, 3000), i * 500L);
		}
		for (int i = 20; i < 40; i++) {
			t.add(i * 20, 0, serverAzimuth(i * 20, 0, -2500, 1500), i * 500L);
		}

		Triangulator.Fix fix = t.solve(20_000);
		assertNotNull(fix);
		assertEquals(-2500, fix.x(), 5);
		assertEquals(1500, fix.z(), 5);
		assertTrue(fix.samples() <= 21, "kept stale bearings: " + fix.samples());
	}

	@Test
	void forgetsOldBearings() {
		Triangulator t = new Triangulator();
		t.add(0, 0, serverAzimuth(0, 0, 1000, 1000), 0);
		t.add(500, 0, serverAzimuth(500, 0, 1000, 1000), 1000);
		assertNull(t.solve(200_000));
		assertEquals(0, t.size());
	}
}
