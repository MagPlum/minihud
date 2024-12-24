package fi.dy.masa.minihud.mixin;

import java.util.List;
import com.llamalad7.mixinextras.sugar.Local;
import org.jetbrains.annotations.Nullable;

import net.minecraft.block.BeehiveBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import fi.dy.masa.minihud.config.Configs;
import fi.dy.masa.minihud.util.MiscUtils;

@Mixin(ItemStack.class)
public abstract class MixinItemStack
{
    @Shadow
    public abstract Item getItem();

    @Inject(method = "getTooltip",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/item/ItemStack;appendTooltip(Lnet/minecraft/component/ComponentType;Lnet/minecraft/item/Item$TooltipContext;Ljava/util/function/Consumer;Lnet/minecraft/item/tooltip/TooltipType;)V",
                     ordinal = 0))
    private void onGetTooltipComponents(Item.TooltipContext context, @Nullable PlayerEntity player, TooltipType type, CallbackInfoReturnable<List<Text>> cir,
                                        @Local List<Text> list)
    {
        if (Configs.Generic.BUNDLE_TOOLTIPS.getBooleanValue() &&
            this.getItem() instanceof BundleItem)
        {
            MiscUtils.addBundleTooltip((ItemStack) (Object) this, list);
        }
    }

    @Inject(method = "getTooltip", at = @At("RETURN"))
    private void onGetTooltip(Item.TooltipContext context, @Nullable PlayerEntity player, TooltipType type, CallbackInfoReturnable<List<Text>> cir)
    {
        List<Text> list = cir.getReturnValue();

        if (Configs.Generic.AXOLOTL_TOOLTIPS.getBooleanValue() &&
            this.getItem() == Items.AXOLOTL_BUCKET)
        {
            MiscUtils.addAxolotlTooltip((ItemStack) (Object) this, list);
            return;
        }

        if (Configs.Generic.BEE_TOOLTIPS.getBooleanValue() &&
            this.getItem() instanceof BlockItem &&
            ((BlockItem) this.getItem()).getBlock() instanceof BeehiveBlock)
        {
            MiscUtils.addBeeTooltip((ItemStack) (Object) this, list);
        }

        if (Configs.Generic.HONEY_TOOLTIPS.getBooleanValue() &&
            this.getItem() instanceof BlockItem &&
            ((BlockItem) this.getItem()).getBlock() instanceof BeehiveBlock)
        {
            MiscUtils.addHoneyTooltip((ItemStack) (Object) this, list);
        }
    }
}
