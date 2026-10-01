package dev.limitedrecipe;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.Collection;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.Permissions;
import net.neoforged.neoforge.network.PacketDistributor;

public final class Networking {
    private static final int MAX_RECIPE_JSON_CHARS = 8_000;
    private static final int MAX_ACCESS_RULES_JSON_CHARS = 16_000;
    private static final int MAX_ACCESS_RULES_JSON_BYTES = 30_000;

    private Networking() {}

    public static void openEditor(ServerPlayer player) {
        String catalog = PermissionStore.catalogJson(player.level().getServer());
        int chunkCount = (catalog.length() + NetworkMessages.CATALOG_CHUNK_SIZE - 1)
                / NetworkMessages.CATALOG_CHUNK_SIZE;
        if (chunkCount > NetworkMessages.MAX_CATALOG_CHUNKS) {
            player.sendSystemMessage(Component.literal("Recipe catalog is too large to open safely."));
            return;
        }

        for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
            int start = chunkIndex * NetworkMessages.CATALOG_CHUNK_SIZE;
            int end = Math.min(catalog.length(), start + NetworkMessages.CATALOG_CHUNK_SIZE);
            PacketDistributor.sendToPlayer(player,
                    new NetworkMessages.RecipeCatalog(chunkIndex, chunkCount, catalog.substring(start, end)));
        }
    }

    public static void onPlayerLoggedIn(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            syncHiddenRecipes(player);
        }
    }

    public static void syncHiddenRecipes(ServerPlayer player) {
        sendHiddenRecipeList(player, PermissionStore.hiddenItemsJson(player.level().getServer(), player));
    }

    private static void sendHiddenRecipeList(ServerPlayer player, String hiddenRecipes) {
        int chunkCount = Math.max(1, (hiddenRecipes.length() + NetworkMessages.CATALOG_CHUNK_SIZE - 1)
                / NetworkMessages.CATALOG_CHUNK_SIZE);
        if (chunkCount > NetworkMessages.MAX_CATALOG_CHUNKS) {
            return;
        }
        for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
            int start = chunkIndex * NetworkMessages.CATALOG_CHUNK_SIZE;
            int end = Math.min(hiddenRecipes.length(), start + NetworkMessages.CATALOG_CHUNK_SIZE);
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                    new NetworkMessages.HiddenRecipeList(chunkIndex, chunkCount,
                            hiddenRecipes.substring(start, end)));
        }
    }

    private static void syncHiddenRecipesToAll(MinecraftServer server) {
        Collection<ServerPlayer> players = server.getPlayerList().getPlayers();
        players.forEach(player -> syncHiddenRecipes(player));
    }

    public static void saveRule(NetworkMessages.SaveRecipeRule payload,
                                net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> saveRuleOnServer(payload, context));
    }

    public static void deleteRule(NetworkMessages.DeleteRecipeRule payload,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || !isEditor(player)) {
                return;
            }
            if (payload.recipeId().length() > 256
                    || !PermissionStore.deleteRule(payload.recipeId())) {
                player.sendSystemMessage(Component.literal("Could not delete that saved recipe rule."));
                openEditor(player);
                return;
            }
            player.sendSystemMessage(Component.literal("Recipe rule deleted: " + payload.recipeId()));
            MinecraftServer server = player.level().getServer();
            syncHiddenRecipesToAll(server);
            openEditor(player);
        });
    }

    public static void setRecipeRuleEnabled(NetworkMessages.SetRecipeRuleEnabled payload,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || !isEditor(player)) {
                return;
            }
            if (payload.recipeId().length() > 256
                    || !PermissionStore.setRuleEnabled(payload.recipeId(), payload.enabled())) {
                player.sendSystemMessage(Component.literal("Could not change that recipe rule's on/off state."));
                openEditor(player);
                return;
            }
            String state = payload.enabled() ? "on" : "off";
            player.sendSystemMessage(Component.literal("Recipe rule turned " + state + ": " + payload.recipeId()));
            MinecraftServer server = player.level().getServer();
            syncHiddenRecipesToAll(server);
            openEditor(player);
        });
    }

    private static void saveRuleOnServer(NetworkMessages.SaveRecipeRule payload,
                                         net.neoforged.neoforge.network.handling.IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        if (!player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
            player.sendSystemMessage(Component.literal("You do not have permission to edit recipe access."));
            return;
        }
        if (payload.recipeId().length() > 256 || payload.mode().length() > 16
                || payload.jeiMode().length() > 16
                || payload.playerUuids().size() > 256 || payload.jeiPlayerUuids().size() > 256
                || payload.playerUuids().stream().anyMatch(value -> value.length() != 36)
                || payload.jeiPlayerUuids().stream().anyMatch(value -> value.length() != 36)) {
            return;
        }

        AccessMode mode;
        AccessMode jeiMode;
        try {
            mode = AccessMode.valueOf(payload.mode().toUpperCase(Locale.ROOT));
            jeiMode = AccessMode.valueOf(payload.jeiMode().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            player.sendSystemMessage(Component.literal("That recipe rule has an invalid craft or JEI mode."));
            return;
        }

        List<UUID> parsed = new ArrayList<>();
        List<UUID> parsedJei = new ArrayList<>();
        try {
            for (String value : payload.playerUuids()) {
                parsed.add(UUID.fromString(value));
            }
            for (String value : payload.jeiPlayerUuids()) {
                parsedJei.add(UUID.fromString(value));
            }
        } catch (IllegalArgumentException exception) {
            player.sendSystemMessage(Component.literal("That recipe rule contains an invalid UUID."));
            return;
        }

        if ((mode != AccessMode.OPEN || jeiMode != AccessMode.OPEN)
                && !PermissionStore.supportsPlayerAccess(player.level().getServer(), payload.recipeId())) {
            player.sendSystemMessage(Component.literal(
                    "This recipe type has no player-owned result slot; use global disable instead."));
            openEditor(player);
            return;
        }

        boolean saved = PermissionStore.saveRule(player.level().getServer(), payload.recipeId(),
                mode, new LinkedHashSet<>(parsed), jeiMode, new LinkedHashSet<>(parsedJei),
                payload.hideInJei(), payload.disabled());
        if (!saved) {
            player.sendSystemMessage(Component.literal("Could not save this recipe rule."));
        } else {
            syncHiddenRecipesToAll(player.level().getServer());
        }
        openEditor(player);
    }

    public static void requestRecipeJson(NetworkMessages.RecipeJsonRequest payload,
                                         net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> requestRecipeJsonOnServer(payload, context));
    }

    private static void requestRecipeJsonOnServer(NetworkMessages.RecipeJsonRequest payload,
                                                  net.neoforged.neoforge.network.handling.IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !isEditor(player)) {
            return;
        }
        if (payload.recipeId().length() > 256) {
            return;
        }
        String json = RecipeOverrideStore.currentRecipeJson(player.level().getServer(), payload.recipeId());
        String message = json == null ? "Could not read that recipe." : "";
        if (json != null && json.length() > MAX_RECIPE_JSON_CHARS) {
            json = "";
            message = "This recipe JSON is too large for the editor.";
        }
        PacketDistributor.sendToPlayer(player, new NetworkMessages.RecipeJsonData(payload.recipeId(),
                json == null ? "" : json,
                RecipeOverrideStore.hasOverride(player.level().getServer(), payload.recipeId()), message));
    }

    public static void saveRecipeJson(NetworkMessages.SaveRecipeJson payload,
                                      net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> saveRecipeJsonOnServer(payload, context));
    }

    public static void requestAccessRulesJson(NetworkMessages.AccessRulesJsonRequest payload,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || !isEditor(player)) {
                return;
            }
            sendAccessRulesJson(player, false, "");
        });
    }

    public static void saveAccessRulesJson(NetworkMessages.SaveAccessRulesJson payload,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || !isEditor(player)) {
                return;
            }
            String message;
            boolean saved = false;
            if (payload.json().length() > MAX_ACCESS_RULES_JSON_CHARS
                    || payload.json().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    > MAX_ACCESS_RULES_JSON_BYTES) {
                message = "Permissions JSON exceeds the editor size limit.";
            } else if (!PermissionStore.replaceAccessRulesJson(payload.json())) {
                message = "Invalid permissions JSON or the file could not be saved. Check the rules schema.";
            } else {
                message = "Permissions saved and applied on the server.";
                saved = true;
                syncHiddenRecipesToAll(player.level().getServer());
            }
            sendAccessRulesJson(player, saved, message);
            if (saved) {
                openEditor(player);
            }
        });
    }

    private static void sendAccessRulesJson(ServerPlayer player, boolean saved, String message) {
        String json = PermissionStore.accessRulesJson();
        if (json.length() > MAX_ACCESS_RULES_JSON_CHARS
                || json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_ACCESS_RULES_JSON_BYTES) {
            json = "";
            message = "Permissions JSON is too large for the in-game editor (16,000 character limit).";
            saved = false;
        }
        PacketDistributor.sendToPlayer(player, new NetworkMessages.AccessRulesJsonData(json, message, saved));
    }

    private static void saveRecipeJsonOnServer(NetworkMessages.SaveRecipeJson payload,
                                               net.neoforged.neoforge.network.handling.IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !isEditor(player)) {
            return;
        }
        if (payload.recipeId().length() > 256 || payload.json().length() > MAX_RECIPE_JSON_CHARS) {
            player.sendSystemMessage(Component.literal("Recipe JSON exceeds the editor's size limit."));
            return;
        }
        MinecraftServer server = player.level().getServer();
        RecipeOverrideStore.EditResult result = RecipeOverrideStore.saveOrRestore(
                server, payload.recipeId(), payload.json(), payload.restore());
        if (!result.success()) {
            player.sendSystemMessage(Component.literal(result.message()));
            return;
        }

        RecipeOverrideStore.reloadWithOverrides(server).whenComplete((ignored, exception) -> server.execute(() -> {
            if (exception != null) {
                player.sendSystemMessage(Component.literal("Recipe reload failed; check the server log."));
                return;
            }
            player.sendSystemMessage(Component.literal(result.message()));
            syncHiddenRecipesToAll(server);
            openEditor(player);
        }));
    }

    private static boolean isEditor(ServerPlayer player) {
        if (player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
            return true;
        }
        player.sendSystemMessage(Component.literal("You do not have permission to edit recipes."));
        return false;
    }
}
