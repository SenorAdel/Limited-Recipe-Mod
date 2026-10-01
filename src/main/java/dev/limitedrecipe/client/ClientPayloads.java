package dev.limitedrecipe.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import com.google.gson.JsonParser;

import dev.limitedrecipe.LimitedRecipeMod;
import dev.limitedrecipe.NetworkMessages;

@EventBusSubscriber(modid = LimitedRecipeMod.MOD_ID, value = Dist.CLIENT)
public final class ClientPayloads {
    private static String[] catalogChunks;
    private static int expectedChunkCount;
    private static int receivedChunkCount;
    private static String[] hiddenChunks;
    private static int expectedHiddenChunkCount;
    private static int receivedHiddenChunkCount;
    private static java.util.Set<String> hiddenItemIds = java.util.Set.of();
    private static java.util.Set<String> hiddenRecipeIds = java.util.Set.of();

    private ClientPayloads() {}

    @SubscribeEvent
    public static void register(RegisterClientPayloadHandlersEvent event) {
        event.register(NetworkMessages.RecipeCatalog.TYPE, ClientPayloads::receiveCatalog);
        event.register(NetworkMessages.HiddenRecipeList.TYPE, ClientPayloads::receiveHiddenRecipes);
        event.register(NetworkMessages.RecipeJsonData.TYPE, ClientPayloads::receiveRecipeJson);
        event.register(NetworkMessages.AccessRulesJsonData.TYPE, ClientPayloads::receiveAccessRulesJson);
    }

    @SubscribeEvent
    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        hiddenItemIds = java.util.Set.of();
        hiddenRecipeIds = java.util.Set.of();
        resetHiddenAssembly();
        refreshJeiIntegration();
    }

    private static void receiveHiddenRecipes(NetworkMessages.HiddenRecipeList payload,
                                              net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> {
            if (payload.chunkCount() < 1 || payload.chunkCount() > NetworkMessages.MAX_CATALOG_CHUNKS
                    || payload.chunkIndex() < 0 || payload.chunkIndex() >= payload.chunkCount()
                    || payload.json().length() > NetworkMessages.CATALOG_CHUNK_SIZE) {
                resetHiddenAssembly();
                return;
            }
            if (payload.chunkIndex() == 0) {
                hiddenChunks = new String[payload.chunkCount()];
                expectedHiddenChunkCount = payload.chunkCount();
                receivedHiddenChunkCount = 0;
            } else if (hiddenChunks == null || expectedHiddenChunkCount != payload.chunkCount()) {
                resetHiddenAssembly();
                return;
            }
            if (hiddenChunks[payload.chunkIndex()] == null) {
                hiddenChunks[payload.chunkIndex()] = payload.json();
                receivedHiddenChunkCount++;
            }
            if (receivedHiddenChunkCount != expectedHiddenChunkCount) {
                return;
            }

            StringBuilder json = new StringBuilder();
            for (String chunk : hiddenChunks) {
                json.append(chunk);
            }
            resetHiddenAssembly();
            try {
                var root = JsonParser.parseString(json.toString()).getAsJsonObject();
                java.util.Set<String> items = new java.util.LinkedHashSet<>();
                root.getAsJsonArray("items").forEach(value -> items.add(value.getAsString()));
                java.util.Set<String> recipes = new java.util.LinkedHashSet<>();
                if (root.has("recipes") && root.get("recipes").isJsonArray()) {
                    root.getAsJsonArray("recipes").forEach(value -> recipes.add(value.getAsString()));
                }
                hiddenItemIds = java.util.Set.copyOf(items);
                hiddenRecipeIds = java.util.Set.copyOf(recipes);
                refreshJeiIntegration();
            } catch (RuntimeException exception) {
                hiddenItemIds = java.util.Set.of();
                hiddenRecipeIds = java.util.Set.of();
                refreshJeiIntegration();
            }
        });
    }

    public static java.util.Set<String> hiddenItemIds() {
        return hiddenItemIds;
    }

    public static java.util.Set<String> hiddenRecipeIds() {
        return hiddenRecipeIds;
    }

    private static void receiveRecipeJson(NetworkMessages.RecipeJsonData payload,
                                          net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.screen instanceof RecipeSlotsScreen slots) {
                if (slots.recipeId().equals(payload.recipeId())) {
                    slots.receiveRecipeData(payload.json(), payload.hasOverride(), payload.message());
                }
                return;
            }
            if (!payload.message().isEmpty() || payload.json().isEmpty()) {
                if (minecraft.player != null) {
                    minecraft.player.sendSystemMessage(Component.literal(payload.message()));
                }
                return;
            }
            if (minecraft.screen instanceof RecipeAccessScreen parent) {
                minecraft.setScreen(new RecipeJsonScreen(parent, payload.recipeId(), payload.json(),
                        payload.hasOverride()));
            } else if (minecraft.screen instanceof AppliedRulesScreen parent) {
                minecraft.setScreen(new RecipeJsonScreen(parent, payload.recipeId(), payload.json(),
                        payload.hasOverride()));
            }
        });
    }

    private static void receiveAccessRulesJson(NetworkMessages.AccessRulesJsonData payload,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.screen instanceof AccessRulesJsonScreen screen) {
                screen.updateResult(payload.json(), payload.message(), payload.saved());
            } else if (minecraft.screen instanceof AppliedRulesScreen parent && !payload.json().isEmpty()) {
                minecraft.setScreen(new AccessRulesJsonScreen(parent, payload.json()));
            } else if (minecraft.player != null && !payload.message().isEmpty()) {
                minecraft.player.sendSystemMessage(Component.literal(payload.message()));
            }
        });
    }

    private static void refreshJeiIntegration() {
        if (!ModList.get().isLoaded("jei")) {
            return;
        }
        try {
            Class<?> integration = Class.forName("dev.limitedrecipe.client.JeiIntegration");
            integration.getMethod("refreshFromServer").invoke(null);
        } catch (ReflectiveOperationException exception) {
            // JEI is optional; do not interfere with joining a server if its API changes.
        }
    }

    private static void resetHiddenAssembly() {
        hiddenChunks = null;
        expectedHiddenChunkCount = 0;
        receivedHiddenChunkCount = 0;
    }

    private static void receiveCatalog(NetworkMessages.RecipeCatalog payload,
                                       net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> {
            if (payload.chunkCount() < 1 || payload.chunkCount() > NetworkMessages.MAX_CATALOG_CHUNKS
                    || payload.chunkIndex() < 0 || payload.chunkIndex() >= payload.chunkCount()
                    || payload.json().length() > NetworkMessages.CATALOG_CHUNK_SIZE) {
                resetCatalogAssembly();
                return;
            }

            if (payload.chunkIndex() == 0) {
                catalogChunks = new String[payload.chunkCount()];
                expectedChunkCount = payload.chunkCount();
                receivedChunkCount = 0;
            } else if (catalogChunks == null || expectedChunkCount != payload.chunkCount()) {
                resetCatalogAssembly();
                return;
            }

            if (catalogChunks[payload.chunkIndex()] == null) {
                catalogChunks[payload.chunkIndex()] = payload.json();
                receivedChunkCount++;
            }
            if (receivedChunkCount != expectedChunkCount) {
                return;
            }

            StringBuilder catalog = new StringBuilder(expectedChunkCount * NetworkMessages.CATALOG_CHUNK_SIZE);
            for (String chunk : catalogChunks) {
                catalog.append(chunk);
            }
            resetCatalogAssembly();

            try {
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft.screen instanceof RecipeAccessScreen screen) {
                    screen.updateCatalog(catalog.toString());
                } else if (minecraft.screen instanceof AppliedRulesScreen screen) {
                    screen.parentEditor().updateCatalog(catalog.toString());
                    screen.refreshFromServer();
                } else if (minecraft.screen instanceof AccessRulesJsonScreen screen) {
                    screen.refreshParentCatalog(catalog.toString());
                } else if (minecraft.screen instanceof RecipeJsonScreen screen) {
                    screen.refreshParentCatalog(catalog.toString());
                } else if (minecraft.screen instanceof RecipeSlotsScreen screen) {
                    screen.onRecipeReloaded(catalog.toString());
                } else {
                    minecraft.setScreen(new RecipeAccessScreen(catalog.toString()));
                }
            } catch (RuntimeException exception) {
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft.player != null) {
                    minecraft.player.sendSystemMessage(
                            Component.literal("Could not open the recipe access editor."));
                }
            }
        });
    }

    private static void resetCatalogAssembly() {
        catalogChunks = null;
        expectedChunkCount = 0;
        receivedChunkCount = 0;
    }
}
