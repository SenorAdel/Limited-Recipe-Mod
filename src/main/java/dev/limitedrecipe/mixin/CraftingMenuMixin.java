package dev.limitedrecipe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.limitedrecipe.PermissionStore;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;

@Mixin(CraftingMenu.class)
public abstract class CraftingMenuMixin {
    @Inject(method = "quickMoveStack", at = @At("HEAD"), cancellable = true)
    private void limitedrecipe$denyRestrictedShiftCraft(Player player, int slotIndex,
            CallbackInfoReturnable<ItemStack> callbackInfo) {
        if (!(player instanceof ServerPlayer) || slotIndex != 0) {
            return;
        }

        AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
        Slot slot = menu.getSlot(slotIndex);
        if (!(slot.container instanceof ResultContainer resultContainer)) {
            return;
        }

        RecipeHolder<?> recipe = resultContainer.getRecipeUsed();
        if (recipe != null && !PermissionStore.isAllowed(player, recipe.id().identifier().toString())) {
            callbackInfo.setReturnValue(ItemStack.EMPTY);
        }
    }

    @Inject(method = "slotChangedCraftingGrid", at = @At("TAIL"))
    private static void limitedrecipe$enforceRecipeAllowlist(AbstractContainerMenu menu, ServerLevel level,
                                                               Player player, CraftingContainer container,
                                                               ResultContainer resultSlots,
                                                               RecipeHolder<CraftingRecipe> recipeHint,
                                                               CallbackInfo callbackInfo) {
        RecipeHolder<?> recipe = resultSlots.getRecipeUsed();
        if (recipe == null) {
            recipe = recipeHint;
        }
        if (recipe != null && !PermissionStore.isAllowed(player, recipe.id().identifier().toString())) {
            resultSlots.setItem(0, ItemStack.EMPTY);
            resultSlots.setRecipeUsed(null);
            menu.broadcastChanges();
        }
    }
}
