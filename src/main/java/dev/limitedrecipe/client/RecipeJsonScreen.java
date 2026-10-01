package dev.limitedrecipe.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import dev.limitedrecipe.NetworkMessages;

public final class RecipeJsonScreen extends Screen {
    private static final int MAX_PANEL_WIDTH = 760;
    private static final int MAX_PANEL_HEIGHT = 500;

    private final Screen parent;
    private final String recipeId;
    private final String initialJson;
    private final boolean hasOverride;
    private MultiLineEditBox jsonBox;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;

    public RecipeJsonScreen(Screen parent, String recipeId, String json, boolean hasOverride) {
        super(Component.literal("Edit Recipe JSON"));
        this.parent = parent;
        this.recipeId = recipeId;
        this.initialJson = json;
        this.hasOverride = hasOverride;
    }

    public void refreshParentCatalog(String catalogJson) {
        if (parent instanceof RecipeAccessScreen editor) {
            editor.updateCatalog(catalogJson);
        } else if (parent instanceof AppliedRulesScreen applied) {
            applied.parentEditor().updateCatalog(catalogJson);
        } else if (parent instanceof RecipeSlotsScreen slots) {
            slots.onRecipeReloaded(catalogJson);
        }
    }

    @Override
    protected void init() {
        panelWidth = Math.min(MAX_PANEL_WIDTH, Math.max(320, width - 16));
        panelHeight = Math.min(MAX_PANEL_HEIGHT, Math.max(280, height - 16));
        panelX = (width - panelWidth) / 2;
        panelY = Math.max(0, (height - panelHeight) / 2);

        jsonBox = addRenderableWidget(MultiLineEditBox.builder()
                .setX(panelX + 12)
                .setY(panelY + 58)
                .setPlaceholder(Component.literal("Recipe JSON"))
                .build(font, panelWidth - 24, panelHeight - 114, Component.literal("Recipe JSON")));
        jsonBox.setCharacterLimit(8_000);
        jsonBox.setValue(initialJson);

        int buttonY = panelY + panelHeight - 42;
        int buttonWidth = (panelWidth - 40) / 3;
        addRenderableWidget(Button.builder(Component.literal("Save and reload"), ignored -> save(false))
                .bounds(panelX + 12, buttonY, buttonWidth, 22).build());
        Button restore = addRenderableWidget(Button.builder(Component.literal("Restore original"), ignored -> save(true))
                .bounds(panelX + 12 + buttonWidth + 8, buttonY, buttonWidth, 22).build());
        restore.active = hasOverride;
        addRenderableWidget(Button.builder(Component.literal("Back"), ignored -> onClose())
                .bounds(panelX + 12 + (buttonWidth + 8) * 2, buttonY, buttonWidth, 22).build());
    }

    private void save(boolean restore) {
        ClientPacketDistributor.sendToServer(new NetworkMessages.SaveRecipeJson(
                recipeId, restore ? "" : jsonBox.getValue(), restore));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        extractTransparentBackground(graphics);
        ScreenChrome.begin(graphics, font, width, height, panelX, panelY, panelWidth, panelHeight,
                "RAW RECIPE JSON", recipeId, hasOverride ? "WORLD OVERRIDE" : "DEFAULT RECIPE");
        ScreenChrome.card(graphics, panelX + 8, panelY + 51, panelWidth - 16, panelHeight - 102);
        graphics.text(font, Component.literal("Edits are validated and reloaded by the server."),
                panelX + 14, panelY + panelHeight - 54, ScreenChrome.MUTED);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
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
