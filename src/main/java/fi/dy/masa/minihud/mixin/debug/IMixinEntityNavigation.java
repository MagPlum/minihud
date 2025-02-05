package fi.dy.masa.minihud.mixin.debug;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import net.minecraft.entity.ai.pathing.EntityNavigation;

@Mixin(EntityNavigation.class)
public interface IMixinEntityNavigation
{
    @Accessor("nodeReachProximity")
    float minihud_getMaxDistanceToWaypoint();
}
