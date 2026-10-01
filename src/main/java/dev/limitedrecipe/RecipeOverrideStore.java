package dev.limitedrecipe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;

import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

public final class RecipeOverrideStore {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String PACK_ID = "file/limitedrecipe_overrides";
    private static final String PACK_METADATA = """
            {
              "pack": {
                "description": "Limited Recipe Access overrides",
                "min_format": [101, 1],
                "max_format": 101
              }
            }
            """;

    private RecipeOverrideStore() {}

    public static String currentRecipeJson(MinecraftServer server, String recipeId) {
        RecipeHolder<?> holder = findRecipe(server, recipeId);
        if (holder == null) {
            return null;
        }
        DataResult<JsonElement> encoded = Recipe.CODEC.encodeStart(
                RegistryOps.create(JsonOps.INSTANCE, server.registryAccess()), holder.value());
        return encoded.resultOrPartial(message -> LOGGER.warn("Could not encode recipe {}: {}", recipeId, message))
                .map(GSON::toJson)
                .orElse(null);
    }

    public static boolean hasOverride(MinecraftServer server, String recipeId) {
        Path override = overridePath(server, recipeId);
        return override != null && Files.isRegularFile(override);
    }

    public static EditResult saveOrRestore(MinecraftServer server, String recipeId, String json, boolean restore) {
        if (findRecipe(server, recipeId) == null) {
            return new EditResult(false, "That recipe is no longer loaded.");
        }

        Path override = overridePath(server, recipeId);
        if (override == null) {
            return new EditResult(false, "That recipe id contains an invalid path.");
        }
        try {
            if (restore) {
                if (!Files.deleteIfExists(override)) {
                    return new EditResult(false, "This recipe has no saved override to restore.");
                }
                return new EditResult(true, "Original recipe restored and reloaded.");
            }

            JsonElement source = JsonParser.parseString(json);
            DataResult<Recipe<?>> decoded = Recipe.CODEC.parse(
                    RegistryOps.create(JsonOps.INSTANCE, server.registryAccess()), source);
            if (decoded.resultOrPartial(message ->
                    LOGGER.warn("Invalid recipe override for {}: {}", recipeId, message)).isEmpty()) {
                return new EditResult(false, "The JSON must define a valid recipe supported by a loaded serializer.");
            }

            Path packRoot = packRoot(server);
            Files.createDirectories(override.getParent());
            Files.writeString(packRoot.resolve("pack.mcmeta"), PACK_METADATA);
            Files.writeString(override, GSON.toJson(source));
            return new EditResult(true, "Recipe saved and reloaded.");
        } catch (RuntimeException | IOException exception) {
            LOGGER.warn("Could not save recipe override for {}", recipeId, exception);
            return new EditResult(false, "Could not parse or save that recipe JSON.");
        }
    }

    public static CompletableFuture<Void> reloadWithOverrides(MinecraftServer server) {
        PackRepository repository = server.getPackRepository();
        repository.reload();
        if (!repository.isAvailable(PACK_ID)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("The Limited Recipe data pack was not discovered."));
        }
        Set<String> selected = new LinkedHashSet<>(repository.getSelectedIds());
        selected.add(PACK_ID);
        return server.reloadResources(selected);
    }

    private static RecipeHolder<?> findRecipe(MinecraftServer server, String recipeId) {
        Collection<RecipeHolder<?>> recipes = server.overworld().recipeAccess().getRecipes();
        return recipes.stream()
                .filter(holder -> holder.id().identifier().toString().equals(recipeId))
                .findFirst()
                .orElse(null);
    }

    private static Path overridePath(MinecraftServer server, String recipeId) {
        int separator = recipeId.indexOf(':');
        if (separator <= 0 || separator == recipeId.length() - 1 || recipeId.indexOf(':', separator + 1) >= 0) {
            return null;
        }
        String namespace = recipeId.substring(0, separator);
        String recipePath = recipeId.substring(separator + 1);
        if (!namespace.matches("[a-z0-9_.-]+")
                || !recipePath.matches("[a-z0-9/._-]+")
                || java.util.Arrays.stream(recipePath.split("/", -1)).anyMatch(part -> part.isEmpty()
                || part.equals(".") || part.equals(".."))) {
            return null;
        }
        Path packRoot = packRoot(server).toAbsolutePath().normalize();
        Path target = packRoot.resolve("data").resolve(namespace).resolve("recipe")
                .resolve(recipePath + ".json").normalize();
        return target.startsWith(packRoot) ? target : null;
    }

    private static Path packRoot(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("datapacks").resolve("limitedrecipe_overrides");
    }

    public record EditResult(boolean success, String message) {}
}
