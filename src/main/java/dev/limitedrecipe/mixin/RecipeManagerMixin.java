package dev.limitedrecipe.mixin;

import java.util.Optional;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.limitedrecipe.PermissionStore;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

@Mixin(RecipeManager.class)
public abstract class RecipeManagerMixin {
    @Inject(method = "getRecipeFor(Lnet/minecraft/world/item/crafting/RecipeType;Lnet/minecraft/world/item/crafting/RecipeInput;Lnet/minecraft/world/level/Level;)Ljava/util/Optional;",
            at = @At("RETURN"), cancellable = true)
    private <I extends RecipeInput, T extends Recipe<I>> void limitedrecipe$filterDisabledRecipe(
            RecipeType<T> type, I input, Level level,
            CallbackInfoReturnable<Optional<RecipeHolder<T>>> callbackInfo) {
        Optional<RecipeHolder<T>> recipe = callbackInfo.getReturnValue();
        if (recipe.isPresent() && PermissionStore.isGloballyDisabled(
                recipe.get().id().identifier().toString())) {
            callbackInfo.setReturnValue(Optional.empty());
        }
    }

    @Inject(method = "getRecipeFor(Lnet/minecraft/world/item/crafting/RecipeType;Lnet/minecraft/world/item/crafting/RecipeInput;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/crafting/RecipeHolder;)Ljava/util/Optional;",
            at = @At("RETURN"), cancellable = true)
    private <I extends RecipeInput, T extends Recipe<I>> void limitedrecipe$filterDisabledCachedRecipe(
            RecipeType<T> type, I input, Level level, RecipeHolder<T> recipeHint,
            CallbackInfoReturnable<Optional<RecipeHolder<T>>> callbackInfo) {
        Optional<RecipeHolder<T>> recipe = callbackInfo.getReturnValue();
        if (recipe.isPresent() && PermissionStore.isGloballyDisabled(
                recipe.get().id().identifier().toString())) {
            callbackInfo.setReturnValue(Optional.empty());
        }
    }
}
