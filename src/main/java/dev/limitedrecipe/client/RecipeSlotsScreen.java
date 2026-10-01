package dev.limitedrecipe.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import dev.limitedrecipe.NetworkMessages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/** Visual editor for common item-based recipe serializers. */
public final class RecipeSlotsScreen extends Screen {
    private static final int SLOT_SIZE = 48;
    private static final int SLOT_STEP = 54;

    private final RecipeAccessScreen parent;
    private final String recipeId;
    private final List<Button> inputButtons = new ArrayList<>();
    private final List<int[]> inputPositions = new ArrayList<>();
    private Layout layout;
    private JsonObject sourceRecipe;
    private String recipeType = "";
    private String inputField;
    private final List<String> multiInputFields = new ArrayList<>();
    private final List<JsonElement> inputValues = new ArrayList<>();
    private final List<Item> inputItems = new ArrayList<>();
    private Item outputItem;
    private int outputCount = 1;
    private boolean hasOverride;
    private String status = "Loading recipe slots…";
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int centerX;
    private int inputStartX;
    private int inputStartY;
    private Button outputButton;
    private Button saveButton;
    private Button jsonButton;
    private boolean slotsDirty;

    RecipeSlotsScreen(RecipeAccessScreen parent, String recipeId) {
        super(Component.literal("Edit Recipe Slots"));
        this.parent = parent;
        this.recipeId = recipeId;
    }

    void requestRecipeJson() {
        ClientPacketDistributor.sendToServer(new NetworkMessages.RecipeJsonRequest(recipeId));
    }

    String recipeId() {
        return recipeId;
    }

    void receiveRecipeData(String json, boolean overrideExists, String message) {
        if (!message.isEmpty() || json.isEmpty()) {
            status = message.isEmpty() ? "The server returned no recipe data." : message;
            return;
        }
        try {
            sourceRecipe = JsonParser.parseString(json).getAsJsonObject();
            recipeType = sourceRecipe.has("type") ? sourceRecipe.get("type").getAsString() : "recipe";
            hasOverride = overrideExists;
            readRecipeSlots();
            status = "Choose an input or output slot to change it.";
            if (outputButton != null) {
                outputButton.active = true;
            }
            if (saveButton != null) {
                saveButton.active = layout != Layout.UNSUPPORTED;
            }
            buildInputButtons();
            refreshSlotButtons();
        } catch (RuntimeException exception) {
            layout = Layout.UNSUPPORTED;
            status = "Could not read this recipe's slots. Use the JSON editor instead.";
            if (saveButton != null) {
                saveButton.active = false;
            }
        }
    }

    void onRecipeReloaded(String catalogJson) {
        parent.updateCatalog(catalogJson);
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    protected void init() {
        panelWidth = Math.min(760, Math.max(320, width - 16));
        panelHeight = Math.min(520, Math.max(340, height - 16));
        panelX = (width - panelWidth) / 2;
        panelY = Math.max(0, (height - panelHeight) / 2);
        centerX = panelX + panelWidth / 2;
        inputStartX = Math.max(panelX + 26, centerX - 164);
        inputStartY = panelY + 112;

        outputButton = addRenderableWidget(Button.builder(Component.empty(), ignored -> chooseOutput())
                .bounds(centerX + 92, panelY + 154, SLOT_SIZE, SLOT_SIZE)
                .tooltip(Tooltip.create(Component.literal("Recipe output — click to select an item")))
                .build());
        outputButton.active = sourceRecipe != null;

        int buttonY = panelY + panelHeight - 38;
        int buttonWidth = (panelWidth - 36) / 3;
        saveButton = addRenderableWidget(Button.builder(Component.literal("Save and reload"), ignored -> save())
                .bounds(panelX + 12, buttonY, buttonWidth, 22).build());
        saveButton.active = layout != null && layout != Layout.UNSUPPORTED;
        jsonButton = addRenderableWidget(Button.builder(Component.literal("Raw JSON"), ignored -> openJsonEditor())
                .bounds(panelX + 18 + buttonWidth, buttonY, buttonWidth, 22).build());
        jsonButton.active = sourceRecipe != null;
        addRenderableWidget(Button.builder(Component.literal("Back"), ignored -> onClose())
                .bounds(panelX + 24 + buttonWidth * 2, buttonY, buttonWidth, 22).build());

        buildInputButtons();
        refreshSlotButtons();
    }

    private void readRecipeSlots() {
        inputValues.clear();
        inputItems.clear();
        multiInputFields.clear();
        inputField = null;

        if (sourceRecipe.has("pattern") && sourceRecipe.has("key")
                && sourceRecipe.get("key").isJsonObject()) {
            JsonArray pattern = sourceRecipe.getAsJsonArray("pattern");
            boolean fitsGrid = pattern.size() <= 3 && pattern.asList().stream()
                    .allMatch(row -> row.getAsString().length() <= 3);
            if (!fitsGrid) {
                layout = Layout.UNSUPPORTED;
                status = "This shaped recipe is larger than a 3×3 grid. Use Raw JSON.";
            } else {
                layout = Layout.SHAPED;
            }
            if (layout == Layout.UNSUPPORTED) {
                readOutput();
                return;
            }
            JsonObject key = sourceRecipe.getAsJsonObject("key");
            int rows = Math.min(3, pattern.size());
            int columns = rows == 0 ? 0 : Math.min(3, pattern.get(0).getAsString().length());
            int rowOffset = Math.max(0, (3 - rows) / 2);
            int columnOffset = Math.max(0, (3 - columns) / 2);
            for (int i = 0; i < 9; i++) {
                inputValues.add(null);
                inputItems.add(null);
            }
            for (int row = 0; row < rows; row++) {
                String line = pattern.get(row).getAsString();
                for (int column = 0; column < Math.min(3, line.length()); column++) {
                    char symbol = line.charAt(column);
                    if (symbol == ' ') {
                        continue;
                    }
                    JsonElement ingredient = key.get(String.valueOf(symbol));
                    if (ingredient != null) {
                        setDecodedInput((row + rowOffset) * 3 + column + columnOffset, ingredient);
                    }
                }
            }
        } else if (recipeType.endsWith(":crafting_shapeless") && sourceRecipe.has("ingredients")
                && sourceRecipe.get("ingredients").isJsonArray()
                && sourceRecipe.getAsJsonArray("ingredients").size() <= 9) {
            layout = Layout.SHAPELESS;
            for (int i = 0; i < 9; i++) {
                inputValues.add(null);
                inputItems.add(null);
            }
            JsonArray ingredients = sourceRecipe.getAsJsonArray("ingredients");
            for (int i = 0; i < Math.min(9, ingredients.size()); i++) {
                setDecodedInput(i, ingredients.get(i));
            }
        } else if (recipeType.endsWith(":smithing_transform") && sourceRecipe.has("template") && sourceRecipe.has("base")
                && sourceRecipe.has("addition")) {
            layout = Layout.SMITHING;
            multiInputFields.addAll(List.of("template", "base", "addition"));
            for (String field : multiInputFields) {
                JsonElement raw = sourceRecipe.get(field);
                inputValues.add(raw == null ? null : raw.deepCopy());
                inputItems.add(raw == null ? null : itemFromIngredient(raw));
            }
        } else if (isSingleInputType(recipeType) && sourceRecipe.has("ingredient")) {
            layout = Layout.SINGLE;
            inputField = "ingredient";
            JsonElement raw = sourceRecipe.get(inputField);
            inputValues.add(raw.deepCopy());
            inputItems.add(itemFromIngredient(raw));
        } else {
            layout = Layout.UNSUPPORTED;
            status = "This recipe type has no recognized item-slot layout. Use Raw JSON.";
        }

        readOutput();
    }

    private void readOutput() {
        JsonElement result = sourceRecipe.get("result");
        if (result != null) {
            ItemStackTemplate stack = ItemStackTemplate.CODEC.parse(registryOps(), result).result().orElse(null);
            if (stack != null) {
                outputItem = stack.item().value();
                outputCount = stack.count();
            }
        }
    }

    private static boolean isSingleInputType(String type) {
        return type.endsWith(":smelting") || type.endsWith(":blasting") || type.endsWith(":smoking")
                || type.endsWith(":campfire_cooking") || type.endsWith(":stonecutting");
    }

    private void setDecodedInput(int index, JsonElement ingredient) {
        inputValues.set(index, ingredient.deepCopy());
        inputItems.set(index, itemFromIngredient(ingredient));
    }

    @SuppressWarnings("deprecation")
    private Item itemFromIngredient(JsonElement json) {
        return Ingredient.CODEC.parse(registryOps(), json).result()
                .flatMap(ingredient -> ingredient.items().findFirst())
                .map(Holder::value)
                .orElse(null);
    }

    private RegistryOps<JsonElement> registryOps() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            throw new IllegalStateException("A world must be loaded to edit recipe slots.");
        }
        return RegistryOps.create(JsonOps.INSTANCE, minecraft.level.registryAccess());
    }

    private void buildInputButtons() {
        inputButtons.clear();
        inputPositions.clear();
        if (layout == null || layout == Layout.UNSUPPORTED) {
            return;
        }
        for (int index = 0; index < inputValues.size(); index++) {
            int x;
            int y;
            if (layout == Layout.SHAPED || layout == Layout.SHAPELESS) {
                x = inputStartX + (index % 3) * SLOT_STEP;
                y = inputStartY + (index / 3) * SLOT_STEP;
            } else if (layout == Layout.SMITHING) {
                x = inputStartX + index * SLOT_STEP;
                y = panelY + 154;
            } else {
                x = centerX - 70;
                y = panelY + 154;
            }
            inputPositions.add(new int[] {x, y});
            final int slotIndex = index;
            Button button = addRenderableWidget(Button.builder(Component.empty(), ignored -> chooseInput(slotIndex))
                    .bounds(x, y, SLOT_SIZE, SLOT_SIZE).build());
            inputButtons.add(button);
        }
    }

    private void refreshSlotButtons() {
        for (int i = 0; i < inputButtons.size(); i++) {
            JsonElement raw = inputValues.get(i);
            Item item = inputItems.get(i);
            inputButtons.get(i).setMessage(Component.literal(raw == null ? "+" : item == null ? "?" : ""));
            inputButtons.get(i).setTooltip(Tooltip.create(Component.literal(raw == null
                    ? "Empty slot — click to choose an item"
                    : item == null ? "Ingredient could not be displayed — click to replace it"
                    : BuiltInRegistries.ITEM.getKey(item) + " — click to change")));
        }
        if (outputButton != null) {
            outputButton.active = sourceRecipe != null;
            outputButton.setMessage(Component.literal(outputItem == null ? "+" : ""));
            outputButton.setTooltip(Tooltip.create(Component.literal(outputItem == null
                    ? "Choose an output item"
                    : BuiltInRegistries.ITEM.getKey(outputItem) + " ×" + outputCount + " — click to change")));
        }
        if (jsonButton != null) {
            jsonButton.active = sourceRecipe != null;
        }
    }

    private void chooseInput(int index) {
        Item current = inputItems.get(index);
        boolean canClear = layout == Layout.SHAPED || layout == Layout.SHAPELESS;
        minecraft.setScreen(new ItemPickerScreen(this, current, canClear, chosen -> {
            try {
                if (chosen == null) {
                    inputValues.set(index, null);
                    inputItems.set(index, null);
                } else {
                    inputValues.set(index, Ingredient.CODEC.encodeStart(registryOps(), Ingredient.of(chosen))
                            .result().orElseThrow(() -> new IllegalStateException("Could not encode that ingredient.")));
                    inputItems.set(index, chosen);
                }
                status = "Slot changed. Save and reload to apply it.";
                slotsDirty = true;
                refreshSlotButtons();
            } catch (RuntimeException exception) {
                status = "Could not encode that item as a recipe ingredient.";
            }
        }));
    }

    private void chooseOutput() {
        minecraft.setScreen(new ItemPickerScreen(this, outputItem, false, chosen -> {
            if (chosen == null) {
                return;
            }
            outputItem = chosen;
            status = "Output changed. Save and reload to apply it.";
            slotsDirty = true;
            refreshSlotButtons();
        }));
    }

    private void save() {
        if (sourceRecipe == null || layout == null || layout == Layout.UNSUPPORTED || outputItem == null) {
            status = "This recipe needs readable input and output slots before it can be saved here.";
            return;
        }
        try {
            JsonObject edited = buildEditedRecipe();
            ClientPacketDistributor.sendToServer(new NetworkMessages.SaveRecipeJson(
                    recipeId, edited.toString(), false));
            status = "Saving recipe and reloading the server…";
        } catch (RuntimeException exception) {
            status = exception.getMessage() == null ? "Could not build a valid recipe." : exception.getMessage();
        }
    }

    private JsonObject buildEditedRecipe() {
        JsonObject edited = sourceRecipe.deepCopy();
        switch (layout) {
            case SHAPED -> writeShapedInputs(edited);
            case SHAPELESS -> writeShapelessInputs(edited);
            case SINGLE -> writeSingleInput(edited);
            case SMITHING -> writeSmithingInputs(edited);
            case UNSUPPORTED -> throw new IllegalStateException("Unsupported recipe layout.");
        }
        ItemStackTemplate output = new ItemStackTemplate(outputItem, outputCount);
        JsonElement encodedOutput = ItemStackTemplate.CODEC.encodeStart(registryOps(), output).result()
                .orElseThrow(() -> new IllegalStateException("Could not encode the output item."));
        edited.add("result", encodedOutput);
        return edited;
    }

    private void writeShapedInputs(JsonObject edited) {
        JsonObject key = new JsonObject();
        JsonArray pattern = new JsonArray();
        Map<String, Character> symbols = new LinkedHashMap<>();
        int ingredientCount = 0;
        for (int row = 0; row < 3; row++) {
            StringBuilder line = new StringBuilder(3);
            for (int column = 0; column < 3; column++) {
                JsonElement ingredient = inputValues.get(row * 3 + column);
                if (ingredient == null) {
                    line.append(' ');
                    continue;
                }
                String identity = ingredient.toString();
                Character symbol = symbols.get(identity);
                if (symbol == null) {
                    if (symbols.size() >= 26) {
                        throw new IllegalStateException("Too many unique ingredients for a 3×3 recipe.");
                    }
                    symbol = (char) ('A' + symbols.size());
                    symbols.put(identity, symbol);
                    key.add(String.valueOf(symbol), ingredient.deepCopy());
                }
                line.append(symbol);
                ingredientCount++;
            }
            pattern.add(line.toString());
        }
        if (ingredientCount == 0) {
            throw new IllegalStateException("A shaped recipe needs at least one ingredient.");
        }
        edited.add("pattern", pattern);
        edited.add("key", key);
    }

    private void writeShapelessInputs(JsonObject edited) {
        JsonArray ingredients = new JsonArray();
        inputValues.stream().filter(value -> value != null).forEach(value -> ingredients.add(value.deepCopy()));
        if (ingredients.isEmpty()) {
            throw new IllegalStateException("A shapeless recipe needs at least one ingredient.");
        }
        edited.add("ingredients", ingredients);
    }

    private void writeSingleInput(JsonObject edited) {
        JsonElement ingredient = inputValues.getFirst();
        if (ingredient == null) {
            throw new IllegalStateException("This recipe needs an input item.");
        }
        edited.add(inputField, ingredient.deepCopy());
    }

    private void writeSmithingInputs(JsonObject edited) {
        for (int i = 0; i < multiInputFields.size(); i++) {
            JsonElement ingredient = inputValues.get(i);
            if (ingredient == null) {
                throw new IllegalStateException("Smithing recipes need all three inputs.");
            }
            edited.add(multiInputFields.get(i), ingredient.deepCopy());
        }
    }

    private void openJsonEditor() {
        if (sourceRecipe != null) {
            String json = sourceRecipe.toString();
            if (layout != null && layout != Layout.UNSUPPORTED && outputItem != null) {
                try {
                    json = buildEditedRecipe().toString();
                } catch (RuntimeException exception) {
                    status = exception.getMessage() == null ? "Could not build recipe JSON." : exception.getMessage();
                    return;
                }
            }
            minecraft.setScreen(new RecipeJsonScreen(this, recipeId, json, hasOverride));
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        extractTransparentBackground(graphics);
        String typeBadge = recipeType.contains(":") ? recipeType.substring(recipeType.indexOf(':') + 1) : recipeType;
        ScreenChrome.begin(graphics, font, width, height, panelX, panelY, panelWidth, panelHeight,
                "RECIPE SLOT STUDIO", recipeId, typeBadge);
        int gridWidth = SLOT_STEP * 2 + SLOT_SIZE;
        int gridHeight = SLOT_STEP * 2 + SLOT_SIZE;
        if (layout == Layout.SHAPED || layout == Layout.SHAPELESS) {
            ScreenChrome.card(graphics, inputStartX - 14, panelY + 76, gridWidth + 28, gridHeight + 38);
        } else if (layout == Layout.SMITHING) {
            ScreenChrome.card(graphics, inputStartX - 14, panelY + 116, gridWidth + 28, 92);
        } else if (layout == Layout.SINGLE) {
            ScreenChrome.card(graphics, centerX - 96, panelY + 116, 92, 92);
        }
        ScreenChrome.card(graphics, centerX + 80, panelY + 116, 72, 92);
        if (layout == Layout.SHAPED || layout == Layout.SHAPELESS) {
            graphics.text(font, Component.literal(layout == Layout.SHAPED
                    ? "CRAFTING GRID  ·  click a slot to edit"
                    : "INGREDIENTS  ·  click a slot to edit"),
                    inputStartX, panelY + 86, ScreenChrome.ACCENT);
        } else if (layout == Layout.SMITHING) {
            graphics.text(font, Component.literal("TEMPLATE        BASE        ADDITION"), inputStartX,
                    panelY + 132, ScreenChrome.MUTED);
        } else if (layout == Layout.SINGLE) {
            graphics.text(font, Component.literal("INPUT"), centerX - 70, panelY + 132, ScreenChrome.MUTED);
        }
        graphics.fill(centerX + 18, panelY + 176, centerX + 70, panelY + 177, ScreenChrome.BORDER);
        graphics.text(font, Component.literal("→"), centerX + 40, panelY + 166, ScreenChrome.ACCENT);
        graphics.fill(centerX + 70, panelY + 176, centerX + 78, panelY + 177, ScreenChrome.BORDER);
        graphics.text(font, Component.literal("OUTPUT"), centerX + 91, panelY + 132, ScreenChrome.ACCENT);
        graphics.text(font, Component.literal("Edit output"), centerX + 88, panelY + 210,
                ScreenChrome.MUTED);
        if (layout == null) {
            graphics.text(font, Component.literal("Waiting for recipe data from the server…"), panelX + 20,
                    panelY + 88, ScreenChrome.MUTED);
        } else if (layout == Layout.UNSUPPORTED) {
            graphics.textWithWordWrap(font, Component.literal(status), panelX + 20, panelY + 82,
                    panelWidth - 40, ScreenChrome.WARN);
        }
        graphics.textWithWordWrap(font, Component.literal(status), panelX + 12,
                panelY + panelHeight - 58, panelWidth - 24,
                slotsDirty ? ScreenChrome.WARN : ScreenChrome.MUTED);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (slotsDirty) {
            ScreenChrome.selected(graphics, font, saveButton, 0xAA275B5B, ScreenChrome.ACCENT, true);
        }
        for (int i = 0; i < inputButtons.size(); i++) {
            Item item = inputItems.get(i);
            if (item != null) {
                int[] position = inputPositions.get(i);
                ItemStack stack = new ItemStack(item);
                graphics.item(stack, position[0] + (SLOT_SIZE - 16) / 2,
                        position[1] + (SLOT_SIZE - 16) / 2);
                graphics.itemDecorations(font, stack, position[0] + (SLOT_SIZE - 16) / 2,
                        position[1] + (SLOT_SIZE - 16) / 2);
            }
        }
        if (outputItem != null && outputButton != null) {
            ItemStack stack = new ItemStack(outputItem, outputCount);
            int x = outputButton.getX() + (SLOT_SIZE - 16) / 2;
            int y = outputButton.getY() + (SLOT_SIZE - 16) / 2;
            graphics.item(stack, x, y);
            graphics.itemDecorations(font, stack, x, y);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private enum Layout {
        SHAPED,
        SHAPELESS,
        SINGLE,
        SMITHING,
        UNSUPPORTED
    }

    private static final class ItemPickerScreen extends Screen {
        private final RecipeSlotsScreen parent;
        private final Consumer<Item> onSelect;
        private final boolean canClear;
        private final List<ItemChoice> items;
        private final List<Button> itemButtons = new ArrayList<>();
        private final List<ItemChoice> visibleItems = new ArrayList<>();
        private Item current;
        private String query = "";
        private int page;
        private int pageSize;
        private int panelX;
        private int panelY;
        private int panelWidth;
        private int panelHeight;
        private int columns;
        private int tileWidth;
        private int filteredItemCount;
        private EditBox searchBox;
        private Button prevButton;
        private Button nextButton;

        private ItemPickerScreen(RecipeSlotsScreen parent, Item current, boolean canClear,
                                 Consumer<Item> onSelect) {
            super(Component.literal("Choose Recipe Item"));
            this.parent = parent;
            this.current = current;
            this.canClear = canClear;
            this.onSelect = onSelect;
            this.items = BuiltInRegistries.ITEM.stream()
                    .filter(item -> item != Items.AIR)
                    .map(item -> new ItemChoice(BuiltInRegistries.ITEM.getKey(item).toString(), item))
                    .sorted(Comparator.comparing(ItemChoice::id))
                    .toList();
        }

        @Override
        protected void init() {
            panelWidth = Math.min(700, Math.max(300, width - 16));
            panelHeight = Math.min(660, Math.max(340, height - 12));
            panelX = (width - panelWidth) / 2;
            panelY = (height - panelHeight) / 2;
            columns = panelWidth >= 540 ? 3 : panelWidth >= 360 ? 2 : 1;
            tileWidth = (panelWidth - 32 - (columns - 1) * 6) / columns;
            int footerY = panelY + panelHeight - 48;
            int rows = Math.max(1, Math.min(6, (footerY - panelY - 114) / 46));
            pageSize = columns * rows;
            itemButtons.clear();
            visibleItems.clear();

            searchBox = addRenderableWidget(new EditBox(font, panelX + 12, panelY + 55,
                    panelWidth - 24, 20, Component.literal("Search item")));
            searchBox.setMaxLength(100);
            searchBox.setResponder(value -> {
                query = value.toLowerCase(java.util.Locale.ROOT);
                page = 0;
                refreshItemButtons();
            });
            searchBox.setValue(query);

            Button clear = addRenderableWidget(Button.builder(Component.literal("Clear this slot"), ignored -> choose(null))
                    .bounds(panelX + 12, panelY + 81, panelWidth - 24, 18).build());
            clear.visible = canClear;

            for (int i = 0; i < pageSize; i++) {
                final int index = i;
                itemButtons.add(addRenderableWidget(Button.builder(Component.empty(), ignored -> {
                    if (index < visibleItems.size()) {
                        choose(visibleItems.get(index).item());
                    }
                }).bounds(panelX + 12 + (i % columns) * (tileWidth + 6),
                        panelY + 106 + (i / columns) * 46, tileWidth, 42).build()));
            }
            prevButton = addRenderableWidget(Button.builder(Component.literal("Prev"), ignored -> {
                page = Math.max(0, page - 1);
                refreshItemButtons();
            }).bounds(panelX + 12, footerY, 72, 20).build());
            nextButton = addRenderableWidget(Button.builder(Component.literal("Next"), ignored -> {
                page++;
                refreshItemButtons();
            }).bounds(panelX + panelWidth - 84, footerY, 72, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Back"), ignored -> onClose())
                    .bounds(panelX + (panelWidth - 72) / 2, footerY, 72, 20).build());
            refreshItemButtons();
        }

        private void refreshItemButtons() {
            if (itemButtons.isEmpty()) {
                return;
            }
            List<ItemChoice> filtered = items.stream().filter(choice -> choice.id().toLowerCase(java.util.Locale.ROOT)
                            .contains(query)
                            || choice.item().getName(new ItemStack(choice.item())).getString()
                            .toLowerCase(java.util.Locale.ROOT).contains(query))
                    .toList();
            filteredItemCount = filtered.size();
            int maxPage = Math.max(0, (filtered.size() - 1) / pageSize);
            page = Math.min(page, maxPage);
            if (prevButton != null) {
                prevButton.active = page > 0;
            }
            if (nextButton != null) {
                nextButton.active = page < maxPage;
            }
            visibleItems.clear();
            for (int i = 0; i < itemButtons.size(); i++) {
                int itemIndex = page * pageSize + i;
                Button button = itemButtons.get(i);
                if (itemIndex < filtered.size()) {
                    ItemChoice choice = filtered.get(itemIndex);
                    visibleItems.add(choice);
                    String name = choice.item().getName(new ItemStack(choice.item())).getString();
                    button.setMessage(Component.empty());
                    button.setTooltip(Tooltip.create(Component.literal(choice.id())));
                    button.active = true;
                } else {
                    button.setMessage(Component.empty());
                    button.active = false;
                }
            }
        }

        private void choose(Item item) {
            onSelect.accept(item);
            minecraft.setScreen(parent);
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            extractTransparentBackground(graphics);
            String subtitle = current == null ? "Search by item name, namespace, or registry ID"
                    : "Replacing " + BuiltInRegistries.ITEM.getKey(current);
            ScreenChrome.begin(graphics, font, width, height, panelX, panelY, panelWidth, panelHeight,
                    "ITEM CATALOG", subtitle, items.size() + " ITEMS");
            int footerY = panelY + panelHeight - 48;
            ScreenChrome.card(graphics, panelX + 8, panelY + 51, panelWidth - 16,
                    footerY - panelY - 42);
            graphics.text(font, Component.literal("Type to filter the item registry. Hover an item for its full ID."),
                    panelX + 14, panelY + 39, ScreenChrome.MUTED);
            super.extractRenderState(graphics, mouseX, mouseY, partialTick);
            if (filteredItemCount == 0) {
                graphics.text(font, Component.literal("No items match. Try a shorter name or namespace."),
                        panelX + 20, panelY + 128, ScreenChrome.MUTED);
            }
            for (int i = 0; i < visibleItems.size() && i < itemButtons.size(); i++) {
                Button button = itemButtons.get(i);
                ItemChoice choice = visibleItems.get(i);
                String name = choice.item().getName(new ItemStack(choice.item())).getString();
                int textX = button.getX() + 30;
                int available = Math.max(8, (button.getWidth() - 38) / 6);
                if (choice.item() == current) {
                    graphics.fill(button.getX() + 1, button.getY() + 1,
                            button.getX() + button.getWidth() - 1, button.getY() + button.getHeight() - 1,
                            ScreenChrome.ACCENT_DARK);
                    graphics.outline(button.getX(), button.getY(), button.getWidth(), button.getHeight(),
                            ScreenChrome.ACCENT);
                }
                graphics.item(new ItemStack(choice.item()), button.getX() + 7, button.getY() + 13);
                graphics.text(font, Component.literal(shorten(name, available)), textX, button.getY() + 9,
                        ScreenChrome.TEXT);
                graphics.text(font, Component.literal(shorten(choice.id(), available)), textX, button.getY() + 23,
                        ScreenChrome.MUTED);
            }
        }

        private static String shorten(String text, int maxChars) {
            return text.length() <= maxChars ? text : text.substring(0, Math.max(1, maxChars - 1)) + "…";
        }

        @Override
        public void onClose() {
            minecraft.setScreen(parent);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }

    private record ItemChoice(String id, Item item) {}
}
