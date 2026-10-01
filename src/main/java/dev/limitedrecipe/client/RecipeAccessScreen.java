package dev.limitedrecipe.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.limitedrecipe.AccessMode;
import dev.limitedrecipe.NetworkMessages;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

public final class RecipeAccessScreen extends Screen {
    private static final int PANEL_WIDTH = 520;
    private static final int PANEL_HEIGHT = 330;

    private final List<Button> recipeButtons = new ArrayList<>();
    private final List<Button> craftModeButtons = new ArrayList<>();
    private final List<Button> jeiModeButtons = new ArrayList<>();
    private final List<Button> playerButtons = new ArrayList<>();
    private final List<AbstractWidget> accessWidgets = new ArrayList<>();
    private final List<String> visibleRecipeIds = new ArrayList<>();
    private final List<String> visiblePlayerUuids = new ArrayList<>();
    private final Map<String, RuleDraft> rules = new LinkedHashMap<>();
    private final Map<String, RuleDraft> savedRules = new LinkedHashMap<>();
    private final LinkedHashSet<String> selectedRecipeTypes = new LinkedHashSet<>();
    private List<String> recipes = List.of();
    private Map<String, String> recipeTypes = Map.of();
    private List<PlayerEntry> players = List.of();
    private String selectedRecipe;
    private String filter = "";
    private String playerFilter = "";
    private String status = "Choose a recipe and its access mode.";
    private int recipePage;
    private int playerPage;
    private int panelX;
    private int panelY;
    private int panelWidth = PANEL_WIDTH;
    private int panelHeight = PANEL_HEIGHT;
    private int leftColumnWidth;
    private int rightColumnWidth;
    private int rightColumnX;
    private int recipePageSize = 9;
    private int playerPageSize = 6;
    private EditBox searchBox;
    private EditBox playerSearchBox;
    private Button disabledButton;
    private Button hideInJeiButton;
    private Button playerScopeButton;
    private Button saveRuleButton;
    private Button typeFilterButton;
    private Button appliedRulesButton;
    private boolean recipeSearchEmpty;
    private boolean editingJeiPlayers;

    public RecipeAccessScreen(String catalogJson) {
        super(Component.literal("Recipe Access Manager"));
        updateCatalog(catalogJson);
    }

    public void updateCatalog(String catalogJson) {
        Set<String> oldTypes = availableRecipeTypes();
        boolean hadAllTypesSelected = !selectedRecipeTypes.isEmpty() && selectedRecipeTypes.containsAll(oldTypes);
        JsonObject root = JsonParser.parseString(catalogJson).getAsJsonObject();
        List<String> loadedRecipes = new ArrayList<>();
        root.getAsJsonArray("recipes").forEach(value -> loadedRecipes.add(value.getAsString()));
        recipes = List.copyOf(loadedRecipes);

        Map<String, String> loadedRecipeTypes = new LinkedHashMap<>();
        JsonObject typeEntries = root.getAsJsonObject("recipeTypes");
        if (typeEntries != null) {
            typeEntries.entrySet().forEach(entry -> {
                try {
                    loadedRecipeTypes.put(entry.getKey(), entry.getValue().getAsString());
                } catch (RuntimeException ignored) {
                    // Older or malformed catalog entries have no recipe type label.
                }
            });
        }
        recipeTypes = Map.copyOf(loadedRecipeTypes);
        Set<String> newTypes = availableRecipeTypes();
        if (selectedRecipeTypes.isEmpty() && oldTypes.isEmpty()) {
            selectedRecipeTypes.addAll(newTypes);
        } else {
            selectedRecipeTypes.retainAll(newTypes);
            if (hadAllTypesSelected) {
                selectedRecipeTypes.addAll(newTypes);
            }
        }

        List<PlayerEntry> loadedPlayers = new ArrayList<>();
        if (root.has("players") && root.get("players").isJsonArray()) {
            root.getAsJsonArray("players").forEach(value -> {
                if (value.isJsonObject()) {
                    try {
                        JsonObject player = value.getAsJsonObject();
                        String name = player.get("name").getAsString();
                        String uuid = UUID.fromString(player.get("uuid").getAsString()).toString();
                        loadedPlayers.add(new PlayerEntry(name, uuid));
                    } catch (RuntimeException ignored) {
                        // Ignore malformed player-cache entries.
                    }
                }
            });
        }
        players = loadedPlayers.stream().distinct()
                .sorted((first, second) -> first.name().compareToIgnoreCase(second.name())).toList();

        rules.clear();
        savedRules.clear();
        JsonObject loadedRules = root.getAsJsonObject("rules");
        if (loadedRules != null) {
            for (Map.Entry<String, JsonElement> entry : loadedRules.entrySet()) {
                if (!entry.getValue().isJsonObject()) {
                    continue;
                }
                try {
                    JsonObject value = entry.getValue().getAsJsonObject();
                    AccessMode mode = AccessMode.valueOf(value.get("mode").getAsString().toUpperCase(Locale.ROOT));
                    RuleDraft draft = new RuleDraft(mode);
                    draft.hideInJei = value.has("hideInJei") && value.get("hideInJei").getAsBoolean();
                    draft.jeiMode = value.has("jeiMode")
                            ? AccessMode.valueOf(value.get("jeiMode").getAsString().toUpperCase(Locale.ROOT))
                            : (draft.hideInJei && mode == AccessMode.WHITELIST ? AccessMode.WHITELIST : AccessMode.OPEN);
                    draft.disabled = value.has("disabled") && value.get("disabled").getAsBoolean();
                    draft.enabled = !value.has("enabled") || value.get("enabled").getAsBoolean();
                    JsonElement uuids = value.get("uuids");
                    if (uuids != null && uuids.isJsonArray()) {
                        uuids.getAsJsonArray().forEach(uuid -> draft.uuids.add(uuid.getAsString()));
                    }
                    JsonElement jeiUuids = value.get("jeiUuids");
                    if (jeiUuids != null && jeiUuids.isJsonArray()) {
                        jeiUuids.getAsJsonArray().forEach(uuid -> draft.jeiUuids.add(uuid.getAsString()));
                    } else if (draft.jeiMode == AccessMode.WHITELIST) {
                        draft.jeiUuids.addAll(draft.uuids);
                    }
                    rules.put(entry.getKey(), draft);
                    savedRules.put(entry.getKey(), copyDraft(draft));
                } catch (RuntimeException ignored) {
                    // Ignore malformed entries; the server remains authoritative.
                }
            }
        }

        // Read old catalog responses as whitelists for compatibility during migration.
        JsonObject oldPermissions = root.getAsJsonObject("permissions");
        if (oldPermissions != null) {
            for (Map.Entry<String, JsonElement> entry : oldPermissions.entrySet()) {
                if (rules.containsKey(entry.getKey()) || !entry.getValue().isJsonArray()) {
                    continue;
                }
                RuleDraft draft = new RuleDraft(AccessMode.WHITELIST);
                entry.getValue().getAsJsonArray().forEach(uuid -> draft.uuids.add(uuid.getAsString()));
                if (!draft.uuids.isEmpty()) {
                    rules.put(entry.getKey(), draft);
                    savedRules.put(entry.getKey(), copyDraft(draft));
                }
            }
        }
        if (selectedRecipe != null && !recipes.contains(selectedRecipe)) {
            selectedRecipe = null;
        }
        status = "Choose an access mode and check players for this recipe.";
        refreshRecipeButtons();
        refreshModeButtons();
        refreshPlayerButtons();
        refreshAccessVisibility();
        refreshTypeFilterButton();
        refreshAppliedRulesButton();
    }

    @Override
    protected void init() {
        recipeButtons.clear();
        craftModeButtons.clear();
        jeiModeButtons.clear();
        playerButtons.clear();
        accessWidgets.clear();
        visibleRecipeIds.clear();
        visiblePlayerUuids.clear();
        panelWidth = Math.min(860, Math.max(320, width - 16));
        panelHeight = Math.min(480, Math.max(PANEL_HEIGHT, height - 8));
        panelX = (width - panelWidth) / 2;
        panelY = Math.max(0, (height - panelHeight) / 2);
        leftColumnWidth = (panelWidth - 44) / 2;
        rightColumnWidth = panelWidth - 44 - leftColumnWidth;
        rightColumnX = panelX + 12 + leftColumnWidth + 20;
        recipePageSize = Math.max(1, Math.min(15, (panelHeight - 190) / 19));
        playerPageSize = Math.max(1, Math.min(9, (panelHeight - 315) / 17));

        int typeButtonWidth = Math.min(86, leftColumnWidth / 4);
        searchBox = addRenderableWidget(new EditBox(font, panelX + 12, panelY + 65,
                leftColumnWidth - typeButtonWidth - 4, 20,
                Component.literal("Search recipe ID or type")));
        searchBox.setMaxLength(120);
        searchBox.setResponder(value -> {
            filter = value.toLowerCase(Locale.ROOT);
            recipePage = 0;
            refreshRecipeButtons();
        });
        typeFilterButton = addRenderableWidget(Button.builder(Component.literal("Types"),
                ignored -> openTypeFilter())
                .bounds(panelX + 12 + leftColumnWidth - typeButtonWidth, panelY + 65, typeButtonWidth, 20).build());

        appliedRulesButton = addRenderableWidget(Button.builder(Component.empty(),
                ignored -> minecraft.setScreen(new AppliedRulesScreen(this)))
                .bounds(panelX + panelWidth - 154, panelY + 9, 142, 22).build());

        playerSearchBox = addAccessWidget(new EditBox(font, rightColumnX, panelY + 65, rightColumnWidth, 20,
                Component.literal("Find player by name or UUID")));
        playerSearchBox.setMaxLength(64);
        playerSearchBox.setResponder(value -> {
            playerFilter = value.toLowerCase(Locale.ROOT);
            playerPage = 0;
            refreshPlayerButtons();
        });

        for (AccessMode mode : AccessMode.values()) {
            String label = modeLabel(mode);
            int modeWidth = (rightColumnWidth - 8) / 3;
            Button button = addAccessWidget(Button.builder(Component.literal(label), ignored -> selectMode(mode, false))
                    .bounds(rightColumnX + craftModeButtons.size() * (modeWidth + 4), panelY + 110,
                            modeWidth, 20).build());
            craftModeButtons.add(button);
            Button jeiButton = addAccessWidget(Button.builder(Component.literal(label), ignored -> selectMode(mode, true))
                    .bounds(rightColumnX + jeiModeButtons.size() * (modeWidth + 4), panelY + 154,
                            modeWidth, 20).build());
            jeiModeButtons.add(jeiButton);
        }

        hideInJeiButton = addAccessWidget(Button.builder(Component.empty(), ignored -> toggleHideInJei())
                .bounds(rightColumnX, panelY + 200, rightColumnWidth, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "Hides this recipe and its output for everyone, except players on the JEI whitelist.")))
                .build());
        disabledButton = addAccessWidget(Button.builder(Component.empty(), ignored -> toggleDisabled())
                .bounds(rightColumnX, panelY + 178, rightColumnWidth, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "Blocks crafting for everyone, except players on the craft whitelist.")))
                .build());
        playerScopeButton = addAccessWidget(Button.builder(Component.empty(), ignored -> togglePlayerScope())
                .bounds(rightColumnX, panelY + 224, rightColumnWidth, 20).build());

        for (int i = 0; i < recipePageSize; i++) {
            final int index = i;
            Button button = addRenderableWidget(Button.builder(Component.empty(), ignored -> {
                if (index < visibleRecipeIds.size()) {
                    selectedRecipe = visibleRecipeIds.get(index);
                    playerPage = 0;
                    status = supportsPlayerAccess(selectedRecipe)
                            ? "Set access and check players, then save this recipe."
                            : "Campfire output has no player owner; UUID access rules do not apply.";
                    refreshRecipeButtons();
                    refreshModeButtons();
                    refreshPlayerButtons();
                    refreshAccessVisibility();
                }
            }).bounds(panelX + 12, panelY + 91 + i * 19, leftColumnWidth, 18).build());
            recipeButtons.add(button);
        }

        int recipePagerY = panelY + 96 + recipePageSize * 19;
        int recipePagerEdgeWidth = (leftColumnWidth - 6) / 4;
        addRenderableWidget(Button.builder(Component.literal("Prev"), ignored -> {
            recipePage = Math.max(0, recipePage - 1);
            refreshRecipeButtons();
        }).bounds(panelX + 12, recipePagerY, recipePagerEdgeWidth, 20).build());
        addAccessWidget(Button.builder(Component.literal(leftColumnWidth < 220 ? "Slots" : "Edit slots"),
                        ignored -> openSlotEditor())
                .bounds(panelX + 12 + recipePagerEdgeWidth + 2, recipePagerY, recipePagerEdgeWidth, 20)
                .tooltip(Tooltip.create(Component.literal("Visually change recipe input and output items.")))
                .build());
        addAccessWidget(Button.builder(Component.literal("JSON"), ignored -> requestRecipeJson())
                .bounds(panelX + 12 + (recipePagerEdgeWidth + 2) * 2, recipePagerY, recipePagerEdgeWidth, 20)
                .tooltip(Tooltip.create(Component.literal("Edit the recipe's raw JSON.")))
                .build());
        addRenderableWidget(Button.builder(Component.literal("Next"), ignored -> {
            recipePage++;
            refreshRecipeButtons();
        }).bounds(panelX + 12 + (recipePagerEdgeWidth + 2) * 3, recipePagerY,
                recipePagerEdgeWidth, 20).build());

        for (int i = 0; i < playerPageSize; i++) {
            final int index = i;
            Button button = addAccessWidget(Button.builder(Component.empty(), ignored -> {
                if (index < visiblePlayerUuids.size()) {
                    togglePlayer(visiblePlayerUuids.get(index));
                }
            }).bounds(rightColumnX, panelY + 248 + i * 17, rightColumnWidth, 16).build());
            playerButtons.add(button);
        }

        int playerPagerY = panelY + 251 + playerPageSize * 17;
        addAccessWidget(Button.builder(Component.literal("Prev"), ignored -> {
            playerPage = Math.max(0, playerPage - 1);
            refreshPlayerButtons();
        }).bounds(rightColumnX, playerPagerY, 58, 20).build());
        addAccessWidget(Button.builder(Component.literal("Next"), ignored -> {
            playerPage++;
            refreshPlayerButtons();
        }).bounds(rightColumnX + rightColumnWidth - 58, playerPagerY, 58, 20).build());

        int saveY = panelY + panelHeight - 38;
        int saveButtonWidth = (rightColumnWidth - 12) / 2;
        saveRuleButton = addAccessWidget(Button.builder(Component.literal("Save recipe"), ignored -> saveRule())
                .bounds(rightColumnX, saveY, saveButtonWidth, 22).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), ignored -> onClose())
                .bounds(rightColumnX + saveButtonWidth + 12, saveY, saveButtonWidth, 22).build());

        refreshRecipeButtons();
        refreshModeButtons();
        refreshPlayerButtons();
        refreshAccessVisibility();
        refreshTypeFilterButton();
        refreshAppliedRulesButton();
    }

    private <T extends AbstractWidget> T addAccessWidget(T widget) {
        accessWidgets.add(widget);
        return addRenderableWidget(widget);
    }

    private void refreshRecipeButtons() {
        List<String> filtered = recipes.stream()
                .filter(id -> selectedRecipeTypes.contains(recipeTypes.getOrDefault(id, "unknown")))
                .filter(id -> id.toLowerCase(Locale.ROOT).contains(filter)
                        || recipeTypes.getOrDefault(id, "").toLowerCase(Locale.ROOT).contains(filter))
                .toList();
        recipeSearchEmpty = filtered.isEmpty();
        int maxPage = Math.max(0, (filtered.size() - 1) / recipePageSize);
        recipePage = Math.min(recipePage, maxPage);
        visibleRecipeIds.clear();
        for (int i = 0; i < recipeButtons.size(); i++) {
            int recipeIndex = recipePage * recipePageSize + i;
            Button button = recipeButtons.get(i);
            if (recipeIndex < filtered.size()) {
                String id = filtered.get(recipeIndex);
                visibleRecipeIds.add(id);
                button.setMessage(recipeButtonLabel(id));
                button.active = true;
            } else {
                button.setMessage(Component.empty());
                button.active = false;
            }
        }
    }

    private void refreshModeButtons() {
        RuleDraft draft = selectedRule();
        for (int i = 0; i < craftModeButtons.size(); i++) {
            AccessMode mode = AccessMode.values()[i];
            craftModeButtons.get(i).setMessage(Component.literal(
                    (draft != null && draft.mode == mode ? "[x] " : "[ ] ") + modeLabel(mode)));
            craftModeButtons.get(i).active = selectedRecipe != null
                    && (supportsPlayerAccess(selectedRecipe) || mode == AccessMode.OPEN);
            jeiModeButtons.get(i).setMessage(Component.literal(
                    (draft != null && draft.jeiMode == mode ? "[x] " : "[ ] ") + modeLabel(mode)));
            jeiModeButtons.get(i).active = selectedRecipe != null
                    && (supportsPlayerAccess(selectedRecipe) || mode == AccessMode.OPEN);
        }
        if (hideInJeiButton != null) {
            hideInJeiButton.setMessage(Component.literal(
                    (draft != null && draft.hideInJei ? "[x] " : "[ ] ") + "Hide output from JEI"));
            hideInJeiButton.active = selectedRecipe != null;
        }
        if (disabledButton != null) {
            disabledButton.setMessage(Component.literal(
                    (draft != null && draft.disabled ? "[x] " : "[ ] ") + "Disable recipe for everyone"));
            disabledButton.active = selectedRecipe != null;
        }
        if (playerScopeButton != null) {
            playerScopeButton.setMessage(Component.literal(editingJeiPlayers
                    ? "Editing JEI player list — click to edit Craft list"
                    : "Editing Craft player list — click to edit JEI list"));
            playerScopeButton.active = selectedRecipe != null;
        }
    }

    private void refreshAccessVisibility() {
        boolean selected = selectedRecipe != null;
        accessWidgets.forEach(widget -> widget.visible = selected);
    }

    private void refreshTypeFilterButton() {
        if (typeFilterButton != null) {
            typeFilterButton.setMessage(Component.literal("Types (" + selectedRecipeTypes.size()
                    + "/" + availableRecipeTypes().size() + ")"));
        }
    }

    private void refreshAppliedRulesButton() {
        if (appliedRulesButton != null) {
            appliedRulesButton.setMessage(Component.literal("Applied rules (" + savedRules.size() + ")"));
        }
    }

    private Set<String> availableRecipeTypes() {
        LinkedHashSet<String> available = new LinkedHashSet<>();
        recipes.stream().map(id -> recipeTypes.getOrDefault(id, "unknown")).sorted()
                .forEach(available::add);
        return available;
    }

    private void openTypeFilter() {
        minecraft.setScreen(new RecipeTypeFilterScreen(this, List.copyOf(availableRecipeTypes()),
                selectedRecipeTypes));
    }

    void applyTypeFilter(Set<String> types) {
        selectedRecipeTypes.clear();
        selectedRecipeTypes.addAll(types);
        recipePage = 0;
        refreshRecipeButtons();
        refreshTypeFilterButton();
    }

    boolean openRecipeForEditing(String recipeId) {
        if (!recipes.contains(recipeId)) {
            return false;
        }
        selectedRecipe = recipeId;
        selectedRecipeTypes.add(recipeTypes.getOrDefault(recipeId, "unknown"));
        filter = "";
        playerFilter = "";
        if (searchBox != null) {
            searchBox.setValue("");
        }
        if (playerSearchBox != null) {
            playerSearchBox.setValue("");
        }
        List<String> filtered = recipes.stream()
                .filter(id -> selectedRecipeTypes.contains(recipeTypes.getOrDefault(id, "unknown")))
                .toList();
        int recipeIndex = filtered.indexOf(recipeId);
        recipePage = recipeIndex < 0 ? 0 : recipeIndex / recipePageSize;
        playerPage = 0;
        status = "Editing saved rule. Save recipe to apply any changes.";
        refreshRecipeButtons();
        refreshModeButtons();
        refreshPlayerButtons();
        refreshAccessVisibility();
        refreshTypeFilterButton();
        return true;
    }

    boolean hasRecipe(String recipeId) {
        return recipes.contains(recipeId);
    }

    List<AppliedRulesScreen.RuleEntry> appliedRules() {
        Map<String, String> namesByUuid = new LinkedHashMap<>();
        players.forEach(player -> namesByUuid.put(player.uuid(), player.name()));
        return savedRules.entrySet().stream().map(entry -> {
            RuleDraft draft = entry.getValue();
            List<String> craftPlayerNames = draft.uuids.stream().sorted()
                    .map(uuid -> namesByUuid.getOrDefault(uuid, "Unknown player") + " — " + uuid)
                    .toList();
            List<String> jeiPlayerNames = draft.jeiUuids.stream().sorted()
                    .map(uuid -> namesByUuid.getOrDefault(uuid, "Unknown player") + " — " + uuid)
                    .toList();
            return new AppliedRulesScreen.RuleEntry(entry.getKey(),
                    recipeTypes.getOrDefault(entry.getKey(), "unknown"), modeLabel(draft.mode), craftPlayerNames,
                    modeLabel(draft.jeiMode), jeiPlayerNames, draft.hideInJei, draft.disabled, draft.enabled);
        }).toList();
    }

    private static RuleDraft copyDraft(RuleDraft source) {
        RuleDraft copy = new RuleDraft(source.mode);
        copy.jeiMode = source.jeiMode;
        copy.hideInJei = source.hideInJei;
        copy.disabled = source.disabled;
        copy.enabled = source.enabled;
        copy.uuids.addAll(source.uuids);
        copy.jeiUuids.addAll(source.jeiUuids);
        return copy;
    }

    private void refreshPlayerButtons() {
        visiblePlayerUuids.clear();
        RuleDraft draft = selectedRule();
        List<PlayerEntry> filtered = players.stream()
                .filter(player -> player.name().toLowerCase(Locale.ROOT).contains(playerFilter)
                        || player.uuid().contains(playerFilter))
                .toList();
        int maxPage = Math.max(0, (filtered.size() - 1) / playerPageSize);
        playerPage = Math.min(playerPage, maxPage);
        for (int i = 0; i < playerButtons.size(); i++) {
            int playerIndex = playerPage * playerPageSize + i;
            Button button = playerButtons.get(i);
            if (selectedRecipe != null && playerIndex < filtered.size()) {
                PlayerEntry player = filtered.get(playerIndex);
                visiblePlayerUuids.add(player.uuid());
                boolean checked = draft != null && playerList(draft).contains(player.uuid());
                String display = player.name() + " — " + player.uuid().substring(0, 8);
                int maxLabelChars = Math.max(22, (rightColumnWidth - 10) / 6);
                button.setMessage(Component.literal((checked ? "[x] " : "[ ] ") + shorten(display, maxLabelChars)));
                button.active = selectedRecipe != null && supportsPlayerAccess(selectedRecipe);
            } else {
                button.setMessage(Component.empty());
                button.active = false;
            }
        }
    }

    private void selectMode(AccessMode mode, boolean jei) {
        RuleDraft draft = selectedRule();
        if (draft == null) {
            status = "Select a recipe first.";
            return;
        }
        if (!supportsPlayerAccess(selectedRecipe) && mode != AccessMode.OPEN) {
            status = "Campfire output has no player owner; UUID access rules do not apply.";
            return;
        }
        if (jei) {
            draft.jeiMode = mode;
            editingJeiPlayers = true;
        } else {
            draft.mode = mode;
            editingJeiPlayers = false;
        }
        status = modeDescription(mode, jei) + " Save this recipe to apply it.";
        refreshModeButtons();
        refreshPlayerButtons();
    }

    private void toggleHideInJei() {
        if (selectedRecipe == null) {
            status = "Select a recipe first.";
            return;
        }
        RuleDraft draft = selectedRule();
        draft.hideInJei = !draft.hideInJei;
        status = "JEI visibility changed. Save this recipe to apply the change.";
        refreshModeButtons();
    }

    private void toggleDisabled() {
        if (selectedRecipe == null) {
            status = "Select a recipe first.";
            return;
        }
        RuleDraft draft = selectedRule();
        draft.disabled = !draft.disabled;
        status = "Global recipe availability changed. Save this recipe to apply the change.";
        refreshModeButtons();
    }

    private void togglePlayer(String uuid) {
        if (selectedRecipe == null) {
            status = "Select a recipe first.";
            return;
        }
        if (!supportsPlayerAccess(selectedRecipe)) {
            status = "Campfire output has no player owner; UUID access rules do not apply.";
            return;
        }
        RuleDraft draft = selectedRule();
        LinkedHashSet<String> selectedPlayers = playerList(draft);
        if (selectedPlayers.contains(uuid)) {
            selectedPlayers.remove(uuid);
        } else if (selectedPlayers.size() < 256) {
            selectedPlayers.add(uuid);
        } else {
            status = "A recipe can have up to 256 selected players.";
            return;
        }
        status = "Player selection changed. Save this recipe to apply the change.";
        refreshPlayerButtons();
    }

    private void saveRule() {
        if (selectedRecipe == null) {
            status = "Select a recipe first.";
            return;
        }
        RuleDraft draft = selectedRule();
        List<String> uuids = draft.uuids.stream().sorted().toList();
        List<String> jeiUuids = draft.jeiUuids.stream().sorted().toList();
        ClientPacketDistributor.sendToServer(new NetworkMessages.SaveRecipeRule(
                selectedRecipe, draft.mode.configName(), uuids, draft.jeiMode.configName(), jeiUuids,
                draft.hideInJei, draft.disabled));
        status = "Saving recipe access rule…";
    }

    private void requestRecipeJson() {
        if (selectedRecipe == null) {
            status = "Select a recipe first.";
            return;
        }
        ClientPacketDistributor.sendToServer(new NetworkMessages.RecipeJsonRequest(selectedRecipe));
        status = "Loading the selected recipe definition…";
    }

    private void openSlotEditor() {
        if (selectedRecipe == null) {
            status = "Select a recipe first.";
            return;
        }
        RecipeSlotsScreen editor = new RecipeSlotsScreen(this, selectedRecipe);
        minecraft.setScreen(editor);
        editor.requestRecipeJson();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        extractTransparentBackground(graphics);
        ScreenChrome.begin(graphics, font, width, height, panelX, panelY, panelWidth, panelHeight,
                "RECIPE ACCESS WORKBENCH", "Control crafting, JEI visibility, and player-specific access", null);
        int contentY = panelY + 53;
        int contentHeight = panelHeight - 105;
        ScreenChrome.card(graphics, panelX + 8, contentY, leftColumnWidth + 8, contentHeight);
        ScreenChrome.card(graphics, rightColumnX - 8, contentY, rightColumnWidth + 16, contentHeight);
        ScreenChrome.section(graphics, font, "Recipe library", panelX + 12, panelY + 52,
                leftColumnWidth, recipes.size() + " loaded");
        ScreenChrome.section(graphics, font, "Access policy", rightColumnX, panelY + 52,
                rightColumnWidth, selectedRuleDirty() ? "UNSAVED CHANGES"
                        : selectedRecipe == null ? savedRules.size() + " saved rules" : "Per-recipe controls");
        ScreenChrome.section(graphics, font, "Craft access", rightColumnX, panelY + 92,
                rightColumnWidth, null);
        ScreenChrome.section(graphics, font, "JEI visibility", rightColumnX, panelY + 136,
                rightColumnWidth, null);
        if (recipeSearchEmpty) {
            graphics.text(font, Component.literal("No matches. Adjust search or type filters."),
                    panelX + 18, panelY + 106, ScreenChrome.MUTED);
        }
        int footerY = panelHeight - 58;
        int footerMaxChars = Math.max(24, (rightColumnWidth - 8) / 6);
        if (selectedRecipe != null) {
            graphics.text(font, Component.literal(recipeLabel(selectedRecipe)), rightColumnX,
                    panelY + footerY, ScreenChrome.TEXT);
        } else {
            graphics.text(font, Component.literal("Your recipe settings live here."), rightColumnX + 14,
                    panelY + 180, ScreenChrome.TEXT);
            graphics.text(font, Component.literal("Select a recipe to configure craft and JEI access."),
                    rightColumnX + 14, panelY + 197, ScreenChrome.MUTED);
            graphics.text(font, Component.literal("Saved rules can be reviewed from the top-right."),
                    rightColumnX + 14, panelY + 214, ScreenChrome.MUTED);
        }
        RuleDraft draft = selectedRule();
        if (draft != null) {
            String access = accessSummary(draft);
            graphics.text(font, Component.literal(shorten(access, footerMaxChars)), rightColumnX,
                    panelY + panelHeight - 48, ScreenChrome.GOOD);
        }
        graphics.text(font, Component.literal(shorten(status, Math.max(35, (panelWidth - 24) / 6))),
                panelX + 12, panelY + panelHeight - 15, ScreenChrome.MUTED);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        ScreenChrome.selected(graphics, font, selectedRecipeButton(), ScreenChrome.ACCENT_DARK,
                ScreenChrome.ACCENT, false);
        RuleDraft currentDraft = selectedRule();
        if (currentDraft != null) {
            for (int i = 0; i < craftModeButtons.size(); i++) {
                if (AccessMode.values()[i] == currentDraft.mode) {
                    ScreenChrome.selected(graphics, font, craftModeButtons.get(i), ScreenChrome.ACCENT_DARK,
                            ScreenChrome.ACCENT, true);
                }
                if (AccessMode.values()[i] == currentDraft.jeiMode) {
                    ScreenChrome.selected(graphics, font, jeiModeButtons.get(i), ScreenChrome.ACCENT_DARK,
                            ScreenChrome.ACCENT, true);
                }
            }
            if (currentDraft.disabled) {
                ScreenChrome.selected(graphics, font, disabledButton, 0xFF6B3736, ScreenChrome.DANGER, true);
            }
            if (currentDraft.hideInJei) {
                ScreenChrome.selected(graphics, font, hideInJeiButton, 0xFF6B3736, ScreenChrome.DANGER, true);
            }
            LinkedHashSet<String> checkedPlayers = playerList(currentDraft);
            for (int i = 0; i < playerButtons.size() && i < visiblePlayerUuids.size(); i++) {
                if (checkedPlayers.contains(visiblePlayerUuids.get(i))) {
                    ScreenChrome.selected(graphics, font, playerButtons.get(i), 0xFF275B5B,
                            ScreenChrome.ACCENT, false);
                }
            }
        }
    }

    private boolean selectedRuleDirty() {
        if (selectedRecipe == null) {
            return false;
        }
        RuleDraft current = rules.get(selectedRecipe);
        if (current == null) {
            return false;
        }
        RuleDraft saved = savedRules.get(selectedRecipe);
        if (saved == null) {
            return current.mode != AccessMode.OPEN || current.jeiMode != AccessMode.OPEN || current.disabled
                    || current.hideInJei || !current.uuids.isEmpty() || !current.jeiUuids.isEmpty();
        }
        return current.mode != saved.mode || current.jeiMode != saved.jeiMode || current.disabled != saved.disabled
                || current.hideInJei != saved.hideInJei || !current.uuids.equals(saved.uuids)
                || !current.jeiUuids.equals(saved.jeiUuids);
    }

    private Button selectedRecipeButton() {
        if (selectedRecipe == null) {
            return null;
        }
        for (int index = 0; index < visibleRecipeIds.size(); index++) {
            if (visibleRecipeIds.get(index).equals(selectedRecipe)) {
                return recipeButtons.get(index);
            }
        }
        return null;
    }

    private RuleDraft selectedRule() {
        return selectedRecipe == null ? null : rules.computeIfAbsent(selectedRecipe,
                ignored -> new RuleDraft(AccessMode.OPEN));
    }

    private static String modeLabel(AccessMode mode) {
        return switch (mode) {
            case OPEN -> "Anyone";
            case WHITELIST -> "Whitelist";
            case BLACKLIST -> "Blacklist";
        };
    }

    private static String modeDescription(AccessMode mode, boolean jei) {
        if (jei) {
            return switch (mode) {
                case OPEN -> "Anyone may see it unless globally hidden.";
                case WHITELIST -> "Only selected players may see it; this overrides global JEI hiding.";
                case BLACKLIST -> "Selected players cannot see it.";
            };
        }
        return switch (mode) {
            case OPEN -> "Anyone can craft; listed UUIDs are ignored.";
            case WHITELIST -> "Only listed UUIDs can craft, even when globally disabled.";
            case BLACKLIST -> "Listed UUIDs are blocked; everyone else can craft.";
        };
    }

    private static String accessSummary(RuleDraft draft) {
        String craft = draft.disabled && draft.mode != AccessMode.WHITELIST ? "Craft: globally disabled"
                : "Craft: " + draft.mode.name().toLowerCase(Locale.ROOT) + " (" + draft.uuids.size() + ")";
        String jei = draft.hideInJei && draft.jeiMode != AccessMode.WHITELIST ? "JEI: globally hidden"
                : "JEI: " + draft.jeiMode.name().toLowerCase(Locale.ROOT) + " (" + draft.jeiUuids.size() + ")";
        return craft + " | " + jei;
    }

    private void togglePlayerScope() {
        editingJeiPlayers = !editingJeiPlayers;
        refreshModeButtons();
        refreshPlayerButtons();
    }

    private LinkedHashSet<String> playerList(RuleDraft draft) {
        return editingJeiPlayers ? draft.jeiUuids : draft.uuids;
    }

    private String recipeLabel(String recipeId) {
        String type = recipeTypes.get(recipeId);
        if (type == null || type.isBlank()) {
            return shorten(recipeId, Math.max(24, (leftColumnWidth - 10) / 6));
        }
        if (type.startsWith("minecraft:")) {
            type = type.substring("minecraft:".length());
        }
        return shorten(recipeId + " [" + type + "]", Math.max(24, (leftColumnWidth - 10) / 6));
    }

    private Component recipeButtonLabel(String recipeId) {
        String type = recipeTypes.getOrDefault(recipeId, "unknown");
        String tag = type.startsWith("minecraft:") ? type.substring("minecraft:".length()) : type;
        String prefix = recipeId.equals(selectedRecipe) ? "> " : "";
        int maxChars = Math.max(24, (leftColumnWidth - 10) / 6);
        String shortId = shorten(recipeId, Math.max(8, maxChars - tag.length() - prefix.length() - 4));
        return Component.literal(prefix + shortId + " [")
                .append(Component.literal(tag).withStyle(style -> style.withColor(recipeTypeColor(type))))
                .append(Component.literal("]"));
    }

    static int recipeTypeColor(String type) {
        String normalized = type.toLowerCase(Locale.ROOT);
        if (normalized.contains("crafting")) return 0xFF66D4C4;
        if (normalized.contains("smelting")) return 0xFFFFB65C;
        if (normalized.contains("blasting")) return 0xFFFF8068;
        if (normalized.contains("smoking")) return 0xFFB9D7D0;
        if (normalized.contains("campfire")) return 0xFFFF9162;
        if (normalized.contains("stonecutting")) return 0xFF9BD37D;
        if (normalized.contains("smithing")) return 0xFFB7A0FF;
        return 0xFF80B4F1;
    }

    private boolean supportsPlayerAccess(String recipeId) {
        return !"minecraft:campfire_cooking".equals(recipeTypes.get(recipeId));
    }

    private static String shorten(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars - 1) + "…";
    }

    @Override
    public void onClose() {
        minecraft.setScreen(null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static final class RuleDraft {
        private AccessMode mode;
        private AccessMode jeiMode = AccessMode.OPEN;
        private boolean hideInJei;
        private boolean disabled;
        private boolean enabled = true;
        private final LinkedHashSet<String> uuids = new LinkedHashSet<>();
        private final LinkedHashSet<String> jeiUuids = new LinkedHashSet<>();

        private RuleDraft(AccessMode mode) {
            this.mode = mode;
        }
    }

    private record PlayerEntry(String name, String uuid) {}
}
