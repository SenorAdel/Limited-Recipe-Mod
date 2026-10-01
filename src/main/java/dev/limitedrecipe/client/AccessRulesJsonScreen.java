package dev.limitedrecipe.client;

import dev.limitedrecipe.NetworkMessages;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

public final class AccessRulesJsonScreen extends Screen {
    private static final int MAX_JSON_CHARS = 16_000;
    private final Screen parent;
    private String initialJson;
    private String status = "Edit the server's saved recipe rules.";
    private MultiLineEditBox jsonBox;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;

    public AccessRulesJsonScreen(Screen parent, String json) {
        super(Component.literal("Edit Permissions JSON"));
        this.parent = parent;
        this.initialJson = json;
    }

    public void updateResult(String json, String message, boolean saved) {
        if (!json.isEmpty() && (saved || message.isEmpty())) {
            initialJson = json;
            if (jsonBox != null) {
                jsonBox.setValue(json);
            }
        }
        if (!message.isEmpty()) {
            status = message;
        } else if (saved) {
            status = "Saved and applied on the server.";
        }
    }

    public void refreshParentCatalog(String catalogJson) {
        if (parent instanceof AppliedRulesScreen applied) {
            applied.parentEditor().updateCatalog(catalogJson);
        } else if (parent instanceof RecipeAccessScreen editor) {
            editor.updateCatalog(catalogJson);
        }
    }

    @Override
    protected void init() {
        panelWidth = Math.min(820, Math.max(320, width - 16));
        panelHeight = Math.min(520, Math.max(290, height - 16));
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;
        jsonBox = addRenderableWidget(MultiLineEditBox.builder()
                .setX(panelX + 12)
                .setY(panelY + 60)
                .setPlaceholder(Component.literal("{ \"rules\": {} }"))
                .build(font, panelWidth - 24, panelHeight - 128, Component.literal("Permissions JSON")));
        jsonBox.setCharacterLimit(MAX_JSON_CHARS);
        jsonBox.setValue(initialJson);

        int y = panelY + panelHeight - 58;
        int buttonWidth = (panelWidth - 40) / 3;
        addRenderableWidget(Button.builder(Component.literal("Save and apply"), ignored -> save())
                .bounds(panelX + 12, y, buttonWidth, 22).build());
        addRenderableWidget(Button.builder(Component.literal("Reload from server"), ignored -> reload())
                .bounds(panelX + 20 + buttonWidth, y, buttonWidth, 22).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), ignored -> onClose())
                .bounds(panelX + 28 + buttonWidth * 2, y, buttonWidth, 22).build());
    }

    private void save() {
        String json = jsonBox.getValue();
        if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 30_000) {
            status = "JSON is too large to send; limit is 30,000 UTF-8 bytes.";
            return;
        }
        ClientPacketDistributor.sendToServer(new NetworkMessages.SaveAccessRulesJson(json));
        status = "Validating and saving on the server…";
    }

    private void reload() {
        ClientPacketDistributor.sendToServer(new NetworkMessages.AccessRulesJsonRequest());
        status = "Reloading from the server…";
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        extractTransparentBackground(graphics);
        ScreenChrome.begin(graphics, font, width, height, panelX, panelY, panelWidth, panelHeight,
                "RAW ACCESS RULES", "Root object: rules · craft, JEI, UUIDs, global disable, and enabled state",
                "SERVER DATA");
        ScreenChrome.card(graphics, panelX + 8, panelY + 51, panelWidth - 16, panelHeight - 122);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.text(font, Component.literal(status), panelX + 12, panelY + panelHeight - 24,
                ScreenChrome.WARN);
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
