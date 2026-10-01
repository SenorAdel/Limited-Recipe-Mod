package dev.limitedrecipe;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.slf4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.RecipeCraftingHolder;
import net.minecraft.world.Container;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SingleItemRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

public final class PermissionStore {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, RecipeRule> RULES = new TreeMap<>();
    private static Path rulesFile;

    private PermissionStore() {}

    public static void onServerStarted(ServerStartedEvent event) {
        load(event.getServer());
    }

    private static void load(MinecraftServer server) {
        RULES.clear();
        rulesFile = server.getWorldPath(LevelResource.ROOT)
                .resolve("data")
                .resolve("limitedrecipe")
                .resolve("permissions.json");
        if (!Files.exists(rulesFile)) {
            return;
        }

        try {
            JsonObject root = JsonParser.parseString(Files.readString(rulesFile)).getAsJsonObject();
            JsonObject savedRules = root.getAsJsonObject("rules");
            if (savedRules != null) {
                for (Map.Entry<String, JsonElement> entry : savedRules.entrySet()) {
                    if (!entry.getValue().isJsonObject()) {
                        continue;
                    }
                    JsonObject value = entry.getValue().getAsJsonObject();
                    try {
                        AccessMode mode = AccessMode.parse(value.get("mode").getAsString());
                        Set<UUID> uuids = readUuids(value.get("uuids"), entry.getKey());
                        boolean hideInJei = value.has("hideInJei") && value.get("hideInJei").getAsBoolean();
                        AccessMode jeiMode = value.has("jeiMode")
                                ? AccessMode.parse(value.get("jeiMode").getAsString())
                                : (hideInJei && mode == AccessMode.WHITELIST ? AccessMode.WHITELIST : AccessMode.OPEN);
                        Set<UUID> jeiUuids = value.has("jeiUuids")
                                ? readUuids(value.get("jeiUuids"), entry.getKey())
                                : (jeiMode == AccessMode.WHITELIST ? uuids : Set.of());
                        boolean disabled = value.has("disabled") && value.get("disabled").getAsBoolean();
                        boolean enabled = !value.has("enabled") || value.get("enabled").getAsBoolean();
                        RULES.put(entry.getKey(), new RecipeRule(mode, uuids, jeiMode, jeiUuids,
                                hideInJei, disabled, enabled));
                    } catch (RuntimeException exception) {
                        LOGGER.warn("Ignoring invalid access rule for recipe {}", entry.getKey());
                    }
                }
            }

            // Migrate rules written by versions that stored a bare UUID allowlist.
            JsonObject legacyPermissions = root.getAsJsonObject("permissions");
            if (legacyPermissions != null) {
                for (Map.Entry<String, JsonElement> entry : legacyPermissions.entrySet()) {
                    if (RULES.containsKey(entry.getKey()) || !entry.getValue().isJsonArray()) {
                        continue;
                    }
                    Set<UUID> uuids = readUuids(entry.getValue(), entry.getKey());
                    if (!uuids.isEmpty()) {
                        RULES.put(entry.getKey(), new RecipeRule(AccessMode.WHITELIST, uuids,
                                AccessMode.OPEN, Set.of(), false, false, true));
                    }
                }
            }
        } catch (Exception exception) {
            LOGGER.error("Could not read Limited Recipe permissions from {}", rulesFile, exception);
        }
    }

    public static boolean isAllowed(Player player, String recipeId) {
        RecipeRule rule = RULES.get(recipeId);
        if (rule == null || !rule.enabled()) {
            return true;
        }
        boolean listed = rule.uuids().contains(player.getUUID());
        if (rule.mode() == AccessMode.WHITELIST) {
            // A craft whitelist is an explicit per-player exception to the global disable.
            return listed;
        }
        if (rule.disabled()) {
            return false;
        }
        return switch (rule.mode()) {
            case OPEN -> true;
            case WHITELIST -> listed;
            case BLACKLIST -> !listed;
        };
    }

    public static boolean isGloballyDisabled(String recipeId) {
        RecipeRule rule = RULES.get(recipeId);
        return rule != null && rule.enabled() && rule.disabled()
                && (rule.mode() != AccessMode.WHITELIST || rule.uuids().isEmpty());
    }

    public static boolean blocksAutomatedOutput(String recipeId) {
        RecipeRule rule = RULES.get(recipeId);
        return rule != null && rule.enabled() && (rule.disabled() || rule.mode() != AccessMode.OPEN);
    }

    public static RecipeHolder<?> recipeUsedBy(Container container) {
        return container instanceof RecipeCraftingHolder holder ? holder.getRecipeUsed() : null;
    }

    public static boolean supportsPlayerAccess(MinecraftServer server, String recipeId) {
        RecipeHolder<?> recipe = findRecipe(server, recipeId);
        return recipe != null && recipe.value().getType() != RecipeType.CAMPFIRE_COOKING;
    }

    public static boolean saveRule(MinecraftServer server, String recipeId, AccessMode mode,
                                   Collection<UUID> playerUuids, AccessMode jeiMode,
                                   Collection<UUID> jeiPlayerUuids, boolean hideInJei, boolean disabled) {
        if (rulesFile == null || mode == null || jeiMode == null || !isLoadedRecipe(server, recipeId)) {
            return false;
        }

        Map<String, RecipeRule> updatedRules = new TreeMap<>(RULES);
        RecipeRule previous = RULES.get(recipeId);
        boolean enabled = previous == null || previous.enabled();
        updatedRules.put(recipeId, new RecipeRule(mode, new LinkedHashSet<>(playerUuids), jeiMode,
                new LinkedHashSet<>(jeiPlayerUuids), hideInJei, disabled, enabled));

        return writeRules(updatedRules);
    }

    public static boolean deleteRule(String recipeId) {
        if (rulesFile == null || recipeId == null || recipeId.length() > 256 || !RULES.containsKey(recipeId)) {
            return false;
        }
        Map<String, RecipeRule> updatedRules = new TreeMap<>(RULES);
        updatedRules.remove(recipeId);
        return writeRules(updatedRules);
    }

    public static boolean setRuleEnabled(String recipeId, boolean enabled) {
        if (rulesFile == null || recipeId == null || recipeId.length() > 256) {
            return false;
        }
        RecipeRule current = RULES.get(recipeId);
        if (current == null) {
            return false;
        }
        Map<String, RecipeRule> updatedRules = new TreeMap<>(RULES);
        updatedRules.put(recipeId, new RecipeRule(current.mode(), current.uuids(), current.jeiMode(),
                current.jeiUuids(), current.hideInJei(), current.disabled(), enabled));
        return writeRules(updatedRules);
    }

    public static String accessRulesJson() {
        JsonObject root = new JsonObject();
        root.add("rules", rulesJsonObject(RULES));
        return GSON.toJson(root);
    }

    public static boolean replaceAccessRulesJson(String json) {
        if (rulesFile == null || json.length() > 65_536) {
            return false;
        }
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject savedRules = root.getAsJsonObject("rules");
            if (savedRules == null || savedRules.size() > 20_000) {
                return false;
            }
            Map<String, RecipeRule> parsedRules = new TreeMap<>();
            for (Map.Entry<String, JsonElement> entry : savedRules.entrySet()) {
                if (entry.getKey().isBlank() || entry.getKey().length() > 256
                        || !entry.getValue().isJsonObject()) {
                    return false;
                }
                JsonObject value = entry.getValue().getAsJsonObject();
                AccessMode mode = AccessMode.parse(value.get("mode").getAsString());
                JsonElement rawUuids = value.get("uuids");
                if (rawUuids == null || !rawUuids.isJsonArray() || rawUuids.getAsJsonArray().size() > 256) {
                    return false;
                }
                Set<UUID> uuids = new LinkedHashSet<>();
                for (JsonElement rawUuid : rawUuids.getAsJsonArray()) {
                    uuids.add(UUID.fromString(rawUuid.getAsString()));
                }
                boolean hideInJei = value.has("hideInJei") && value.get("hideInJei").getAsBoolean();
                AccessMode jeiMode = value.has("jeiMode")
                        ? AccessMode.parse(value.get("jeiMode").getAsString())
                        : (hideInJei && mode == AccessMode.WHITELIST ? AccessMode.WHITELIST : AccessMode.OPEN);
                JsonElement rawJeiUuids = value.get("jeiUuids");
                Set<UUID> jeiUuids;
                if (rawJeiUuids == null) {
                    jeiUuids = jeiMode == AccessMode.WHITELIST
                            ? new LinkedHashSet<>(uuids) : new LinkedHashSet<>();
                } else {
                    if (!rawJeiUuids.isJsonArray() || rawJeiUuids.getAsJsonArray().size() > 256) {
                        return false;
                    }
                    jeiUuids = new LinkedHashSet<>();
                    for (JsonElement rawUuid : rawJeiUuids.getAsJsonArray()) {
                        jeiUuids.add(UUID.fromString(rawUuid.getAsString()));
                    }
                }
                boolean disabled = value.has("disabled") && value.get("disabled").getAsBoolean();
                boolean enabled = !value.has("enabled") || value.get("enabled").getAsBoolean();
                parsedRules.put(entry.getKey(), new RecipeRule(mode, uuids, jeiMode, jeiUuids,
                        hideInJei, disabled, enabled));
            }
            return writeRules(parsedRules);
        } catch (RuntimeException exception) {
            LOGGER.warn("Rejected invalid Limited Recipe permissions JSON", exception);
            return false;
        }
    }

    private static boolean writeRules(Map<String, RecipeRule> updatedRules) {
        Path temporaryFile = null;
        try {
            Path parent = rulesFile.getParent();
            Files.createDirectories(parent);
            JsonObject root = new JsonObject();
            root.add("rules", rulesJsonObject(updatedRules));
            temporaryFile = Files.createTempFile(parent, "permissions-", ".tmp");
            Files.writeString(temporaryFile, GSON.toJson(root));
            try {
                Files.move(temporaryFile, rulesFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile, rulesFile, StandardCopyOption.REPLACE_EXISTING);
            }
            RULES.clear();
            RULES.putAll(updatedRules);
            return true;
        } catch (IOException exception) {
            LOGGER.error("Could not save Limited Recipe permissions to {}", rulesFile, exception);
            return false;
        } finally {
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException exception) {
                    LOGGER.warn("Could not remove temporary recipe permission file {}", temporaryFile, exception);
                }
            }
        }
    }

    private static JsonObject rulesJsonObject(Map<String, RecipeRule> source) {
        JsonObject savedRules = new JsonObject();
        source.forEach((id, rule) -> {
            JsonArray values = new JsonArray();
            rule.uuids().forEach(uuid -> values.add(uuid.toString()));
            JsonObject value = new JsonObject();
            value.addProperty("mode", rule.mode().configName());
            value.add("uuids", values);
            value.addProperty("jeiMode", rule.jeiMode().configName());
            JsonArray jeiValues = new JsonArray();
            rule.jeiUuids().forEach(uuid -> jeiValues.add(uuid.toString()));
            value.add("jeiUuids", jeiValues);
            value.addProperty("hideInJei", rule.hideInJei());
            value.addProperty("disabled", rule.disabled());
            value.addProperty("enabled", rule.enabled());
            savedRules.add(id, value);
        });
        return savedRules;
    }

    public static String catalogJson(MinecraftServer server) {
        JsonObject root = new JsonObject();
        JsonArray recipes = new JsonArray();
        allRecipes(server).stream()
                .map(holder -> holder.id().identifier().toString())
                .sorted()
                .forEach(recipes::add);
        root.add("recipes", recipes);

        JsonObject recipeTypes = new JsonObject();
        allRecipes(server).stream()
                .sorted((first, second) -> first.id().identifier().toString()
                        .compareTo(second.id().identifier().toString()))
                .forEach(holder -> {
                    var recipeTypeId = BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType());
                    recipeTypes.addProperty(holder.id().identifier().toString(), recipeTypeId.toString());
                });
        root.add("recipeTypes", recipeTypes);

        root.add("rules", rulesJsonObject(RULES));

        JsonArray players = new JsonArray();
        Map<UUID, String> knownPlayers = cachedPlayers(server);
        server.getPlayerList().getPlayers().forEach(player ->
                knownPlayers.put(player.getUUID(), player.getName().getString()));
        RULES.values().forEach(rule -> {
            rule.uuids().forEach(uuid -> knownPlayers.putIfAbsent(uuid, "Unknown player"));
            rule.jeiUuids().forEach(uuid -> knownPlayers.putIfAbsent(uuid, "Unknown player"));
        });
        knownPlayers.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(String.CASE_INSENSITIVE_ORDER))
                .forEach(entry -> {
                    JsonObject player = new JsonObject();
                    player.addProperty("name", entry.getValue());
                    player.addProperty("uuid", entry.getKey().toString());
                    players.add(player);
                });
        root.add("players", players);
        return GSON.toJson(root);
    }

    public static String hiddenItemsJson(MinecraftServer server, ServerPlayer player) {
        Collection<RecipeHolder<?>> allRecipes = allRecipes(server);
        Set<String> hidden = new java.util.TreeSet<>();
        Set<String> hiddenRecipes = new java.util.TreeSet<>();
        Set<String> jeiWhitelistOverrides = new LinkedHashSet<>();
        for (RecipeHolder<?> holder : allRecipes) {
            String id = holder.id().identifier().toString();
            RecipeRule rule = RULES.get(id);
            boolean recipeVisible = rule == null || !rule.enabled() || canSeeInJei(rule, player.getUUID());
            if (!recipeVisible) {
                hiddenRecipes.add(id);
            }
            for (ItemStack result : recipeOutputs(holder)) {
                if (result.isEmpty()) {
                    continue;
                }
                String itemId = BuiltInRegistries.ITEM.getKey(result.getItem()).toString();
                if (rule != null && rule.enabled() && !recipeVisible) {
                    hidden.add(itemId);
                }
                if (rule != null && rule.enabled() && rule.jeiMode() == AccessMode.WHITELIST
                        && rule.jeiUuids().contains(player.getUUID())) {
                    jeiWhitelistOverrides.add(itemId);
                }
            }
        }
        hidden.removeAll(jeiWhitelistOverrides);
        allRecipes.stream()
                .filter(holder -> recipeOutputs(holder).stream().filter(result -> !result.isEmpty())
                        .map(result -> BuiltInRegistries.ITEM.getKey(result.getItem()).toString())
                        .anyMatch(hidden::contains))
                .map(holder -> holder.id().identifier().toString())
                .forEach(hiddenRecipes::add);
        JsonArray items = new JsonArray();
        hidden.forEach(items::add);
        JsonArray recipes = new JsonArray();
        hiddenRecipes.forEach(recipes::add);
        JsonObject root = new JsonObject();
        root.add("items", items);
        root.add("recipes", recipes);
        return GSON.toJson(root);
    }

    private static boolean canSeeInJei(RecipeRule rule, UUID playerUuid) {
        boolean listed = rule.jeiUuids().contains(playerUuid);
        return switch (rule.jeiMode()) {
            case WHITELIST -> listed;
            case BLACKLIST -> !rule.hideInJei() && !listed;
            case OPEN -> !rule.hideInJei();
        };
    }

    private static Map<UUID, String> cachedPlayers(MinecraftServer server) {
        Map<UUID, String> players = new TreeMap<>();
        Path userCache = server.getServerDirectory().resolve("usercache.json");
        if (!Files.exists(userCache)) {
            return players;
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(userCache));
            if (!parsed.isJsonArray()) {
                return players;
            }
            for (JsonElement entry : parsed.getAsJsonArray()) {
                if (!entry.isJsonObject()) {
                    continue;
                }
                JsonObject profile = entry.getAsJsonObject();
                try {
                    UUID uuid = UUID.fromString(profile.get("uuid").getAsString());
                    String name = profile.get("name").getAsString();
                    players.put(uuid, name);
                } catch (RuntimeException ignored) {
                    // Skip incomplete or malformed cached profile entries.
                }
            }
        } catch (IOException exception) {
            LOGGER.warn("Could not read cached player profiles from {}", userCache, exception);
        }
        return players;
    }

    private static Set<UUID> readUuids(JsonElement values, String recipeId) {
        Set<UUID> uuids = new LinkedHashSet<>();
        if (values == null || !values.isJsonArray()) {
            return uuids;
        }
        for (JsonElement value : values.getAsJsonArray()) {
            try {
                uuids.add(UUID.fromString(value.getAsString()));
            } catch (IllegalArgumentException exception) {
                LOGGER.warn("Ignoring invalid player UUID in rule for {}", recipeId);
            }
        }
        return uuids;
    }

    private static boolean isLoadedRecipe(MinecraftServer server, String recipeId) {
        return findRecipe(server, recipeId) != null;
    }

    private static List<ItemStack> recipeOutputs(RecipeHolder<?> holder) {
        try {
            List<ItemStack> outputs = holder.value().display().stream()
                    .flatMap(display -> display.result().resolveForStacks(ContextMap.EMPTY).stream())
                    .limit(64)
                    .toList();
            if (!outputs.isEmpty()) {
                return outputs;
            }
            if (holder.value() instanceof CraftingRecipe recipe) {
                return List.of(recipe.assemble(CraftingInput.EMPTY));
            }
            if (holder.value() instanceof SingleItemRecipe recipe) {
                return List.of(recipe.assemble(new SingleRecipeInput(ItemStack.EMPTY)));
            }
        } catch (RuntimeException exception) {
            LOGGER.debug("Could not resolve JEI output display for recipe {}", holder.id(), exception);
        }
        return List.of();
    }

    private static RecipeHolder<?> findRecipe(MinecraftServer server, String recipeId) {
        return allRecipes(server).stream()
                .filter(holder -> holder.id().identifier().toString().equals(recipeId))
                .findFirst()
                .orElse(null);
    }

    private static Collection<RecipeHolder<?>> allRecipes(MinecraftServer server) {
        return server.overworld().recipeAccess().getRecipes();
    }

    private record RecipeRule(AccessMode mode, Set<UUID> uuids, AccessMode jeiMode, Set<UUID> jeiUuids,
                              boolean hideInJei, boolean disabled, boolean enabled) {
        private RecipeRule {
            uuids = Set.copyOf(uuids);
            jeiUuids = Set.copyOf(jeiUuids);
        }
    }
}
