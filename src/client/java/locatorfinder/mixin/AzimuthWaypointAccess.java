package locatorfinder.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The waypoint subclasses are package-private, so they are reached through accessor interfaces. */
@Mixin(targets = "net.minecraft.world.waypoints.TrackedWaypoint$AzimuthWaypoint")
public interface AzimuthWaypointAccess {
	@Accessor("angle")
	float locatorfinder$angle();
}
