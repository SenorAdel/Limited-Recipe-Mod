package dev.limitedrecipe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.limitedrecipe.PermissionStore;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

@Mixin(AbstractFurnaceBlockEntity.class)
public abstract class AbstractFurnaceBlockEntityMixin {
    @Inject(method = "canTakeItemThroughFace", at = @At("HEAD"), cancellable = true)
    private void limitedrecipe$denyAutomatedRestrictedOutput(int slot, ItemStack stack, Direction direction,
            CallbackInfoReturnable<Boolean> callbackInfo) {
        if (slot != 2) {
            return;
        }

        Container container = (Container) (Object) this;
        RecipeHolder<?> recipe = PermissionStore.recipeUsedBy(container);
        if (recipe != null && PermissionStore.blocksAutomatedOutput(recipe.id().identifier().toString())) {
            callbackInfo.setReturnValue(false);
        }
    }
}
