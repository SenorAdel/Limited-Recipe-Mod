package dev.limitedrecipe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.limitedrecipe.PermissionStore;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;

@Mixin(Slot.class)
public abstract class SlotMixin {
    @Inject(method = "mayPickup", at = @At("HEAD"), cancellable = true)
    private void limitedrecipe$denyRestrictedResultPickup(Player player,
            CallbackInfoReturnable<Boolean> callbackInfo) {
        if (!(player instanceof ServerPlayer)) {
            return;
        }

        Slot slot = (Slot) (Object) this;
        var recipe = PermissionStore.recipeUsedBy(slot.container);
        if (recipe != null && !PermissionStore.isAllowed(player, recipe.id().identifier().toString())) {
            callbackInfo.setReturnValue(false);
        }
    }
}
