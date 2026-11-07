package locatorfinder.mixin;

import net.minecraft.core.Vec3i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.world.waypoints.TrackedWaypoint$Vec3iWaypoint")
public interface Vec3iWaypointAccess {
	@Accessor("vector")
	Vec3i locatorfinder$vector();
}
