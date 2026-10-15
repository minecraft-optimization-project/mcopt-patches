package org.mcopt.patches.mixin.minecells;

import com.bawnorton.mixinsquared.TargetHandler;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.mcopt.patches.minecells.FallResetTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mine Cells 2.0.0: when something falls below Y 180 in the Ramparts, fall protection picks a checkpoint
 * to teleport it to by querying the height and block state at checkpoints up to ~1024 blocks away. That
 * synchronously loads those chunks on the server thread: a 4.27 s freeze on a live server, ~3-4 s per
 * trigger in a test world.
 * <p>
 * This patch resolves the target without blocking. If its chunk is not loaded yet, it requests an
 * asynchronous load, holds the entity where it is, and lets Mine Cells' handler (which re-runs every
 * 2 ticks while the entity is below the reset height) try again. After {@link #MAX_HOLD_TICKS} it falls
 * back to the original, blocking behavior.
 */
@Mixin(value = Entity.class, priority = 1500)
public abstract class EntityFallResetMixin {
    @Unique private static final String MINECELLS_MIXIN = "com.github.mim1q.minecells.mixin.entity.EntityFallResetMixin";
    @Unique private static final String GET_RESET_TO_POS = "Lnet/minecraft/class_1297;minecells$getResetToPos()Lnet/minecraft/class_2338;";
    @Unique private static final int MAX_HOLD_TICKS = 200;

    @Shadow public float fallDistance;
    @Shadow public abstract void setVelocity(Vec3d velocity);
    @Shadow public abstract void teleport(double x, double y, double z);

    @Unique private BlockPos mcopt$resetTarget;
    @Unique private Vec3d mcopt$holdPos;
    @Unique private int mcopt$holdTicks;

    @TargetHandler(mixin = MINECELLS_MIXIN, name = "minecells$injectTick")
    @Inject(method = "@MixinSquared:Handler", at = @At(value = "INVOKE", target = GET_RESET_TO_POS, remap = false), cancellable = true, remap = false)
    private void mcopt$resolveTargetWithoutBlocking(CallbackInfo originalCi, CallbackInfo ci) {
        var self = (Entity) (Object) this;
        var target = FallResetTargets.find(self, (ServerWorld) self.getWorld());

        if (target != FallResetTargets.PENDING) {
            mcopt$resetTarget = target;
            mcopt$holdPos = null;
            mcopt$holdTicks = 0;
            return; // Mine Cells teleports, using the target via mcopt$useResolvedTarget
        }
        if (mcopt$holdTicks >= MAX_HOLD_TICKS) {
            mcopt$holdPos = null;
            mcopt$holdTicks = 0;
            return; // give up waiting: original behavior, including its synchronous lookup
        }

        // Keep the entity in place while the target chunk loads; the handler runs every 2 ticks.
        if (mcopt$holdPos == null) mcopt$holdPos = self.getPos();
        mcopt$holdTicks += 2;
        teleport(mcopt$holdPos.x, mcopt$holdPos.y, mcopt$holdPos.z);
        setVelocity(Vec3d.ZERO);
        fallDistance = 0.0f;
        ci.cancel();
    }

    @TargetHandler(mixin = MINECELLS_MIXIN, name = "minecells$injectTick")
    @WrapOperation(method = "@MixinSquared:Handler", at = @At(value = "INVOKE", target = GET_RESET_TO_POS, remap = false), remap = false)
    private BlockPos mcopt$useResolvedTarget(Entity self, Operation<BlockPos> original) {
        var target = mcopt$resetTarget;
        mcopt$resetTarget = null;
        return target != null ? target : original.call(self);
    }
}
