package me.aleksilassila.litematica.printer.mixin;

import fi.dy.masa.litematica.util.WorldUtils;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Invoker mixin to access the private cacheEasyPlacePosition method in WorldUtils.
 * Used by Printer to prevent duplicate placements due to server latency.
 */
@Mixin(WorldUtils.class)
public interface WorldUtilsInvoker {
    @Invoker("cacheEasyPlacePosition")
    static void invokeCacheEasyPlacePosition(BlockPos pos) {
        throw new AssertionError("Untransformed @Invoker");
    }
}
