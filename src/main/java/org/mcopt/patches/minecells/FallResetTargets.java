package org.mcopt.patches.minecells;

import com.github.mim1q.minecells.dimension.MineCellsDimension;
import com.github.mim1q.minecells.structure.grid.GridBasedStructureUtils;
import com.github.mim1q.minecells.structure.grid.SpecialPointIds;
import com.github.mim1q.minecells.util.MathUtils;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;

/**
 * Picks the Ramparts fall-reset target like Mine Cells does (nearest valid checkpoint "behind" the
 * entity, else the dimension entrance), but never waits for a chunk on the server thread. If the target's
 * chunk is not ready yet, it requests an asynchronous load and reports {@link #PENDING}.
 * <p>
 * Note that {@code World#isChunkLoaded}/{@code World#getTopY} are not safe for this: isChunkLoaded is true
 * as soon as a chunk's ticket level reaches FULL, even while it is still loading, and the following
 * getChunk then blocks until it is done. {@code ServerChunkManager#getWorldChunk} only returns chunks
 * that are already fully loaded.
 */
public final class FallResetTargets {
    /** Sentinel: a chunk load was requested, try again on a later tick. */
    public static final BlockPos PENDING = new BlockPos(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);

    // Expires on its own; radius 1 brings the center chunk to FULL so it can be queried and teleported into.
    private static final ChunkTicketType<ChunkPos> TICKET =
        ChunkTicketType.create("mcopt_fall_reset", Comparator.comparingLong(ChunkPos::toLong), 100);

    private FallResetTargets() {}

    public static BlockPos find(Entity entity, ServerWorld world) {
        var dimension = MineCellsDimension.of(world);
        var cell = MathUtils.getClosestMultiplePosition(entity.getBlockPos(), 1024);

        List<BlockPos> candidates = GridBasedStructureUtils.getSpecialPoints(world, cell, dimension.baseGenerator).stream()
            .filter(it -> it.id().equals(SpecialPointIds.CHECKPOINT))
            .map(it -> new BlockPos(it.offset().add(cell)))
            .filter(it -> it.getZ() < entity.getZ())
            .sorted(Comparator.comparingDouble(it -> MathHelper.square(it.getX() - entity.getX()) + MathHelper.square(it.getZ() - entity.getZ())))
            .toList();

        // Nearest valid checkpoint among the ones that are already loaded.
        for (var candidate : candidates) {
            var chunk = loadedChunk(world, candidate);
            if (chunk == null) continue;
            var top = candidate.withY(chunk.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING, candidate.getX() & 15, candidate.getZ() & 15) + 1);
            var floor = top.down();
            if (!world.isOutOfHeightLimit(floor) && chunk.getBlockState(floor).isSideSolidFullSquare(chunk, floor, Direction.UP)) {
                return top;
            }
        }
        // Otherwise load the nearest unloaded one in the background and check again once it is there.
        for (var candidate : candidates) {
            if (loadedChunk(world, candidate) == null) {
                requestLoad(world, candidate);
                return PENDING;
            }
        }

        var entrance = BlockPos.ofFloored(dimension.getNonOverworldTeleportPosition(entity.getBlockPos(), world, SpecialPointIds.ENTRANCE).getLeft());
        if (loadedChunk(world, entrance) == null) {
            requestLoad(world, entrance);
            return PENDING;
        }
        return entrance;
    }

    private static @Nullable WorldChunk loadedChunk(ServerWorld world, BlockPos pos) {
        return world.getChunkManager().getWorldChunk(ChunkSectionPos.getSectionCoord(pos.getX()), ChunkSectionPos.getSectionCoord(pos.getZ()));
    }

    private static void requestLoad(ServerWorld world, BlockPos pos) {
        var chunk = new ChunkPos(pos);
        world.getChunkManager().addTicket(TICKET, chunk, 1, chunk);
    }
}
