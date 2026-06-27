package me.aleksilassila.litematica.printer.mixin;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import fi.dy.masa.litematica.util.PlacementHandler;
import fi.dy.masa.litematica.util.PlacementHandler.UseContext;

/**
 * Always applies the accurate placement protocol to fix block orientation,
 * regardless of whether Forgematica's Easy Place Mode is enabled.
 * This is essential for Printer's automatic placement to produce correct
 * block facing for slabs, stairs, and directional blocks.
 * <p>
 * Priority 990 runs after Forgematica's MixinBlockItem (priority 980),
 * so if Forgematica already handled it, we skip. If not, we handle it.
 */
@Mixin(value = BlockItem.class, priority = 990)
public abstract class MixinBlockItemPlacement extends Item {

    private MixinBlockItemPlacement(Settings builder) {
        super(builder);
    }

    @Shadow
    protected abstract BlockState getPlacementState(ItemPlacementContext context);

    @Shadow
    protected abstract boolean canPlace(ItemPlacementContext context, BlockState state);

    @Shadow
    public abstract Block getBlock();

    @Inject(method = "getPlacementState", at = @At("HEAD"), cancellable = true)
    private void printer_fixPlacementState(ItemPlacementContext ctx, CallbackInfoReturnable<BlockState> cir) {
        // Skip if Forgematica's own MixinBlockItem already handled it
        if (fi.dy.masa.litematica.config.Configs.Generic.EASY_PLACE_MODE.getBooleanValue() &&
                fi.dy.masa.litematica.config.Configs.Generic.EASY_PLACE_SP_HANDLING.getBooleanValue()) {
            return;
        }

        BlockState stateOrig = this.getBlock().getPlacementState(ctx);

        if (stateOrig != null && this.canPlace(ctx, stateOrig)) {
            UseContext context = UseContext.from(ctx, ctx.getHand());
            cir.setReturnValue(PlacementHandler.applyPlacementProtocolToPlacementState(stateOrig, context));
        }
    }
}
