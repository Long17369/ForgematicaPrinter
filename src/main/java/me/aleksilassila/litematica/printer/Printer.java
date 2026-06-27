package me.aleksilassila.litematica.printer;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.util.EntityUtils;
import fi.dy.masa.litematica.util.InventoryUtils;
import fi.dy.masa.litematica.util.PlacementHandler;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.util.EasyPlaceProtocol;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.config.Hotkeys;
import me.aleksilassila.litematica.printer.mixin.WorldUtilsInvoker;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerAbilities;
import net.minecraft.item.ItemStack;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

public class Printer {
    public static final Logger logger = LogManager.getLogger(PrinterReference.MOD_ID);
    @Nonnull
    public final ClientPlayerEntity player;

    public Printer(@Nonnull MinecraftClient client, @Nonnull ClientPlayerEntity player) {
        this.player = player;
    }

    public boolean onGameTick() {
        WorldSchematic worldSchematic = SchematicWorldHandler.getSchematicWorld();

        if (worldSchematic == null) {
            return false;
        }

        if (!Configs.PRINT_MODE.getBooleanValue() && !Hotkeys.PRINT.getKeybind().isPressed()) {
            return false;
        }

        PlayerAbilities abilities = player.getAbilities();
        if (!abilities.allowModifyWorld) {
            return false;
        }

        List<BlockPos> positions = getReachablePositions();
        for (BlockPos pos : positions) {
            // Skip positions that were recently placed (server sync delay protection)
            if (WorldUtils.easyPlaceIsPositionCached(pos)) {
                continue;
            }

            BlockState stateSchematic = worldSchematic.getBlockState(pos);
            BlockState stateClient = player.getWorld().getBlockState(pos);

            // Skip if air in schematic
            if (stateSchematic.isAir())
                continue;

            // If the target position already has the correct block type, skip placement.
            // State corrections (like rotation) should be handled by interact/use,
            // not by placing a new block which would end up in the wrong position.
            if (stateSchematic.getBlock() == stateClient.getBlock()) {
                continue;
            }

            // If the target position already has some other solid block, skip it.
            // Placing here would put the block on the wrong face of the existing block.
            if (!stateClient.isAir() && !stateClient.isReplaceable()) {
                continue;
            }

            // Get required item via Forgematica's MaterialCache
            ItemStack stack = MaterialCache.getInstance().getRequiredBuildItemForState(stateSchematic);
            if (stack.isEmpty()) {
                printDebug("No item found for {}", stateSchematic.getBlock().getName());
                continue;
            }

            // Switch to the required item
            InventoryUtils.schematicWorldPickBlock(stack, pos, worldSchematic, MinecraftClient.getInstance());

            // Check which hand has the item
            Hand hand = EntityUtils.getUsedHandForItem(player, stack);
            if (hand == null) {
                printDebug("Item {} not found in hand", stack.getItem().getName());
                continue;
            }

            // Calculate the side and hitPos from player's eye towards the target block.
            // The actual block orientation will be corrected by Forgematica's
            // MixinBlockItem.modifyPlacementState() during interactBlock.
            Vec3d eyePos = player.getEyePos();
            Vec3d targetCenter = Vec3d.ofCenter(pos);
            Vec3d diff = targetCenter.subtract(eyePos);
            Direction side = Direction.getFacing(diff.x, diff.y, diff.z);
            Vec3d hitPos = targetCenter.add(Vec3d.of(side.getVector()).multiply(-0.5));

            // Apply accurate placement protocol to hitPos
            EasyPlaceProtocol protocol = PlacementHandler.getEffectiveProtocolVersion();
            Direction adjustedSide = applyPlacementFacing(stateSchematic, side, stateClient);

            if (protocol == EasyPlaceProtocol.V3) {
                hitPos = WorldUtils.applyPlacementProtocolV3(pos, stateSchematic, hitPos);
            } else if (protocol == EasyPlaceProtocol.V2) {
                hitPos = WorldUtils.applyCarpetProtocolHitVec(pos, stateSchematic, hitPos);
            } else if (protocol == EasyPlaceProtocol.SLAB_ONLY) {
                hitPos = applySlabProtocol(pos, stateSchematic, hitPos);
            }
            // NONE: no protocol encoding, use raw hitPos

            BlockHitResult hitResult = new BlockHitResult(hitPos, adjustedSide, pos, false);
            MinecraftClient.getInstance().interactionManager.interactBlock(player, hand, hitResult);

            // Mark position as recently placed to prevent duplicate placement due to server latency
            WorldUtilsInvoker.invokeCacheEasyPlacePosition(pos);
            WorldUtils.setEasyPlaceLastPickBlockTime();

            printDebug("Placed {} at {}", stateSchematic.getBlock().getName(), pos);
            return true; // One placement per tick
        }

        return false;
    }

    /**
     * Applies the SLAB_ONLY protocol: adjusts hitVec Y coordinate for slabs and
     * stairs.
     * Replicates the logic from WorldUtils.applyBlockSlabProtocol() /
     * applySlabOrStairHitVecY().
     */
    private static Vec3d applySlabProtocol(BlockPos pos, BlockState state, Vec3d hitVecIn) {
        double newY = applySlabOrStairHitVecY(hitVecIn.y, pos, state);
        return newY != hitVecIn.y ? new Vec3d(hitVecIn.x, newY, hitVecIn.z) : hitVecIn;
    }

    /**
     * Adjusts Y coordinate for slab/stair placement.
     * Replicates the logic from WorldUtils.applySlabOrStairHitVecY().
     */
    private static double applySlabOrStairHitVecY(double origY, BlockPos pos, BlockState state) {
        double y = origY;

        if (state.contains(Properties.SLAB_TYPE)) {
            y = pos.getY();
            if (state.get(Properties.SLAB_TYPE) == SlabType.TOP) {
                y += 0.99;
            }
        } else if (state.contains(Properties.BLOCK_HALF)) {
            y = pos.getY();
            if (state.get(Properties.BLOCK_HALF) == BlockHalf.TOP) {
                y += 0.99;
            }
        }

        return y;
    }

    /**
     * Applies placement facing adjustments for slabs, stairs etc.
     * Replicates the logic from WorldUtils.applyPlacementFacing().
     */
    private static Direction applyPlacementFacing(BlockState stateSchematic, Direction side, BlockState stateClient) {
        Block blockSchematic = stateSchematic.getBlock();
        Block blockClient = stateClient.getBlock();

        if (blockSchematic instanceof SlabBlock) {
            if (stateSchematic.get(SlabBlock.TYPE) == SlabType.DOUBLE &&
                    blockClient instanceof SlabBlock &&
                    stateClient.get(SlabBlock.TYPE) != SlabType.DOUBLE) {
                if (stateClient.get(SlabBlock.TYPE) == SlabType.TOP) {
                    return Direction.DOWN;
                } else {
                    return Direction.UP;
                }
            } else {
                return Direction.NORTH;
            }
        } else if (stateSchematic.contains(Properties.BLOCK_HALF)) {
            side = stateSchematic.get(Properties.BLOCK_HALF) == BlockHalf.TOP ? Direction.DOWN : Direction.UP;
        }

        return side;
    }

    private List<BlockPos> getReachablePositions() {
        int maxReach = (int) Math.ceil(Configs.PRINTING_RANGE.getDoubleValue());
        double maxReachSquared = MathHelper.square(Configs.PRINTING_RANGE.getDoubleValue());

        ArrayList<BlockPos> positions = new ArrayList<>();

        for (int y = -maxReach; y < maxReach + 1; y++) {
            for (int x = -maxReach; x < maxReach + 1; x++) {
                for (int z = -maxReach; z < maxReach + 1; z++) {
                    BlockPos blockPos = player.getBlockPos().north(x).west(z).up(y);

                    if (!DataManager.getRenderLayerRange().isPositionWithinRange(blockPos)) {
                        continue;
                    }
                    if (this.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(blockPos)) > maxReachSquared) {
                        continue;
                    }

                    positions.add(blockPos);
                }
            }
        }

        return positions.stream()
                .filter(p -> {
                    Vec3d vec = Vec3d.ofCenter(p);
                    return this.player.getPos().squaredDistanceTo(vec) > 1
                            && this.player.getEyePos().squaredDistanceTo(vec) > 1;
                })
                .sorted((a, b) -> {
                    double aDistance = this.player.getPos().squaredDistanceTo(Vec3d.ofCenter(a));
                    double bDistance = this.player.getPos().squaredDistanceTo(Vec3d.ofCenter(b));
                    return Double.compare(aDistance, bDistance);
                }).toList();
    }

    public static void printDebug(String key, Object... args) {
        if (Configs.PRINT_DEBUG.getBooleanValue()) {
            logger.info(key, args);
        }
    }
}
