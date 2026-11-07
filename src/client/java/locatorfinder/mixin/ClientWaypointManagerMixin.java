package locatorfinder.mixin;

import locatorfinder.LocatorTracker;
import net.minecraft.client.waypoints.ClientWaypointManager;
import net.minecraft.world.waypoints.TrackedWaypoint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sees every waypoint packet as it is applied. Hooks the same data the locator bar draws. */
@Mixin(ClientWaypointManager.class)
public abstract class ClientWaypointManagerMixin {
	@Inject(method = "trackWaypoint(Lnet/minecraft/world/waypoints/TrackedWaypoint;)V", at = @At("HEAD"))
	private void locatorfinder$onTrack(TrackedWaypoint waypoint, CallbackInfo ci) {
		LocatorTracker.onWaypoint(waypoint);
	}

	@Inject(method = "updateWaypoint(Lnet/minecraft/world/waypoints/TrackedWaypoint;)V", at = @At("HEAD"))
	private void locatorfinder$onUpdate(TrackedWaypoint waypoint, CallbackInfo ci) {
		LocatorTracker.onWaypoint(waypoint);
	}

	@Inject(method = "untrackWaypoint(Lnet/minecraft/world/waypoints/TrackedWaypoint;)V", at = @At("HEAD"))
	private void locatorfinder$onUntrack(TrackedWaypoint waypoint, CallbackInfo ci) {
		LocatorTracker.onRemoved(waypoint);
	}
}
