package dev.limitedrecipe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.limitedrecipe.PermissionStore;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.crafting.RecipeHolder;

@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin {
    @Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
    private void limitedrecipe$denyRestrictedOutputClick(int slotIndex, int buttonNum,
            ContainerInput input, Player player, CallbackInfo callbackInfo) {
        if (!(player instanceof ServerPlayer) || slotIndex < 0) {
            return;
        }

        AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
        Slot slot;
        try {
            slot = menu.getSlot(slotIndex);
        } catch (IndexOutOfBoundsException ignored) {
            return;
        }
        RecipeHolder<?> recipe = PermissionStore.recipeUsedBy(slot.container);
        if (recipe != null && !PermissionStore.isAllowed(player, recipe.id().identifier().toString())) {
            callbackInfo.cancel();
        }
    }
}
