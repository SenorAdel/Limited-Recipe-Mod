package dev.limitedrecipe.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.types.IRecipeType;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/** Optional JEI integration, only discovered and loaded when JEI is installed. */
@JeiPlugin
public final class JeiIntegration implements IModPlugin {
    private static IJeiRuntime runtime;
    private static Set<String> hiddenItemIds = Set.of();
    private static Set<String> hiddenRecipeIds = Set.of();
    private static final Map<String, List<ItemStack>> removedStacks = new HashMap<>();
    private static final Map<String, HiddenRecipe> hiddenRecipes = new HashMap<>();

    @Override
    public Identifier getPluginUid() {
        return Identifier.fromNamespaceAndPath("limitedrecipe", "jei");
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
        reconcileOnClientThread();
    }

    @Override
    public void onRuntimeUnavailable() {
        runtime = null;
        removedStacks.clear();
        hiddenRecipes.clear();
    }

    public static void refreshFromServer() {
        hiddenItemIds = ClientPayloads.hiddenItemIds();
        hiddenRecipeIds = ClientPayloads.hiddenRecipeIds();
        reconcileOnClientThread();
    }

    private static void reconcileOnClientThread() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(JeiIntegration::reconcileOnClientThread);
            return;
        }
        IJeiRuntime activeRuntime = runtime;
        if (activeRuntime == null) {
            return;
        }

        Map<String, HiddenRecipe> recipesToHide = recipesToHide(activeRuntime);
        for (String key : new HashSet<>(hiddenRecipes.keySet())) {
            if (!recipesToHide.containsKey(key)) {
                unhideRecipe(activeRuntime, hiddenRecipes.remove(key));
            }
        }
        recipesToHide.forEach((key, recipe) -> {
            if (!hiddenRecipes.containsKey(key) && hideRecipe(activeRuntime, recipe)) {
                hiddenRecipes.put(key, recipe);
            }
        });

        var ingredientManager = activeRuntime.getIngredientManager();
        for (String itemId : new ArrayList<>(removedStacks.keySet())) {
            if (!hiddenItemIds.contains(itemId)) {
                List<ItemStack> restore = removedStacks.remove(itemId);
                if (!restore.isEmpty()) {
                    ingredientManager.addIngredientsAtRuntime(VanillaTypes.ITEM_STACK, restore);
                }
            }
        }

        Collection<ItemStack> visibleIngredients =
                ingredientManager.getAllIngredients(VanillaTypes.ITEM_STACK);
        for (String itemId : hiddenItemIds) {
            if (removedStacks.containsKey(itemId)) {
                continue;
            }
            List<ItemStack> matches = visibleIngredients.stream()
                    .filter(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId))
                    .map(ItemStack::copy)
                    .toList();
            if (!matches.isEmpty()) {
                ingredientManager.removeIngredientsAtRuntime(VanillaTypes.ITEM_STACK, matches);
                removedStacks.put(itemId, matches);
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Map<String, HiddenRecipe> recipesToHide(IJeiRuntime activeRuntime) {
        Map<String, HiddenRecipe> found = new HashMap<>();
        if (hiddenRecipeIds.isEmpty()) {
            return found;
        }
        IRecipeManager recipeManager = activeRuntime.getRecipeManager();
        for (Object rawCategory : recipeManager.createRecipeCategoryLookup().includeHidden().get().toList()) {
            IRecipeCategory category = (IRecipeCategory) rawCategory;
            IRecipeType type = category.getRecipeType();
            try {
                for (Object recipe : recipeManager.createRecipeLookup(type).includeHidden().get().toList()) {
                    Identifier recipeId = category.getIdentifier(recipe);
                    if (recipeId != null && hiddenRecipeIds.contains(recipeId.toString())) {
                        String key = type.getUid() + "|" + recipeId;
                        found.putIfAbsent(key, new HiddenRecipe(type, recipe));
                    }
                }
            } catch (RuntimeException ignored) {
                // A third-party JEI category should not break item hiding for other categories.
            }
        }
        return found;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean hideRecipe(IJeiRuntime activeRuntime, HiddenRecipe hiddenRecipe) {
        try {
            ((IRecipeManager) activeRuntime.getRecipeManager())
                    .hideRecipes((IRecipeType) hiddenRecipe.type(), List.of(hiddenRecipe.recipe()));
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void unhideRecipe(IJeiRuntime activeRuntime, HiddenRecipe hiddenRecipe) {
        if (hiddenRecipe != null) {
            try {
                ((IRecipeManager) activeRuntime.getRecipeManager())
                        .unhideRecipes((IRecipeType) hiddenRecipe.type(), List.of(hiddenRecipe.recipe()));
            } catch (RuntimeException ignored) {
                // Ignore recipes removed by a JEI reload while restoring other hidden entries.
            }
        }
    }

    private record HiddenRecipe(IRecipeType<?> type, Object recipe) {}
}
