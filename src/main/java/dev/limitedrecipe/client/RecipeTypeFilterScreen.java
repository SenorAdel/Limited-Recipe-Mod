package dev.limitedrecipe.client;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class RecipeTypeFilterScreen extends Screen {
    private final RecipeAccessScreen parent;
    private final List<String> types;
    private final Set<String> selected;
    private final List<Button> typeButtons = new ArrayList<>();
    private final List<String> visibleTypes = new ArrayList<>();
    private int page;
    private int pageSize;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private Button prevButton;
    private Button nextButton;
    private Button confirmButton;

    public RecipeTypeFilterScreen(RecipeAccessScreen parent, List<String> types, Set<String> selected) {
        super(Component.literal("Recipe Type Filter"));
        this.parent = parent;
        this.types = List.copyOf(types);
        this.selected = new LinkedHashSet<>(selected);
    }

    @Override
    protected void init() {
        typeButtons.clear();
        visibleTypes.clear();
        panelWidth = Math.min(560, Math.max(320, width - 24));
        panelHeight = Math.min(430, Math.max(270, height - 24));
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;
        Button close = addRenderableWidget(Button.builder(Component.literal("X"), ignored -> applyAndClose())
                .bounds(panelX + panelWidth - 34, panelY + 10, 22, 20).build());
        close.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.literal("Apply selected recipe types and close")));

        pageSize = Math.max(1, Math.min(12, (panelHeight - 154) / 22));
        for (int index = 0; index < pageSize; index++) {
            final int row = index;
            typeButtons.add(addRenderableWidget(Button.builder(Component.empty(), ignored -> {
                if (row < visibleTypes.size()) {
                    String type = visibleTypes.get(row);
                    if (!selected.add(type)) {
                        selected.remove(type);
                    }
                    refreshTypeButtons();
                }
            }).bounds(panelX + 14, panelY + 74 + index * 22, panelWidth - 28, 20).build()));
        }

        int actionY = panelY + panelHeight - 42;
        int buttonGap = 6;
        int buttonWidth = (panelWidth - 28 - buttonGap * 2) / 3;
        addRenderableWidget(Button.builder(Component.literal("Select all"), ignored -> {
            selected.addAll(types);
            refreshTypeButtons();
        }).bounds(panelX + 14, actionY, buttonWidth, 22).build());
        addRenderableWidget(Button.builder(Component.literal("Select none"), ignored -> {
            selected.clear();
            refreshTypeButtons();
        }).bounds(panelX + 14 + buttonWidth + buttonGap, actionY, buttonWidth, 22).build());
        confirmButton = addRenderableWidget(Button.builder(Component.literal("Confirm"), ignored -> applyAndClose())
                .bounds(panelX + 14 + (buttonWidth + buttonGap) * 2, actionY, buttonWidth, 22).build());
        confirmButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.literal("Apply this recipe-type filter")));
        int pagerY = actionY - 30;
        prevButton = addRenderableWidget(Button.builder(Component.literal("Prev"), ignored -> {
            page = Math.max(0, page - 1);
            refreshTypeButtons();
        }).bounds(panelX + 14, pagerY, 72, 20).build());
        nextButton = addRenderableWidget(Button.builder(Component.literal("Next"), ignored -> {
            page++;
            refreshTypeButtons();
        }).bounds(panelX + panelWidth - 86, pagerY, 72, 20).build());
        refreshTypeButtons();
    }

    private void refreshTypeButtons() {
        int maxPage = Math.max(0, (types.size() - 1) / pageSize);
        page = Math.min(page, maxPage);
        if (prevButton != null) {
            prevButton.active = page > 0;
            nextButton.active = page < maxPage;
        }
        visibleTypes.clear();
        for (int index = 0; index < typeButtons.size(); index++) {
            Button button = typeButtons.get(index);
            int typeIndex = page * pageSize + index;
            if (typeIndex < types.size()) {
                String type = types.get(typeIndex);
                visibleTypes.add(type);
                button.setMessage(typeLabel(type, selected.contains(type)));
                button.active = true;
            } else {
                button.setMessage(Component.empty());
                button.active = false;
            }
        }
    }

    private static String displayName(String type) {
        int separator = type.indexOf(':');
        String namespace = separator < 0 ? "minecraft" : type.substring(0, separator);
        String path = separator < 0 ? type : type.substring(separator + 1);
        String words = path.replace('_', ' ');
        if (!words.isEmpty()) {
            words = words.substring(0, 1).toUpperCase(Locale.ROOT) + words.substring(1);
        }
        return words + " (" + namespace + ")";
    }

    private static Component typeLabel(String type, boolean checked) {
        int separator = type.indexOf(':');
        String namespace = separator < 0 ? "minecraft" : type.substring(0, separator);
        String path = separator < 0 ? type : type.substring(separator + 1);
        String category = path.replace('_', ' ');
        if (!category.isEmpty()) {
            category = category.substring(0, 1).toUpperCase(Locale.ROOT) + category.substring(1);
        }
        return Component.literal(checked ? "[x] " : "[ ] ")
                .append(Component.literal(category).withStyle(style -> style.withColor(
                        RecipeAccessScreen.recipeTypeColor(type))))
                .append(Component.literal(" (" + namespace + ")").withStyle(style -> style.withColor(
                        ScreenChrome.MUTED)));
    }

    private void applyAndClose() {
        parent.applyTypeFilter(selected);
        minecraft.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        extractTransparentBackground(graphics);
        ScreenChrome.begin(graphics, font, width, height, panelX, panelY, panelWidth, panelHeight,
                "RECIPE TYPE FILTER", "Choose categories · " + selected.size() + " of " + types.size()
                        + " enabled", null);
        ScreenChrome.card(graphics, panelX + 8, panelY + 52, panelWidth - 16, panelHeight - 134);
        ScreenChrome.section(graphics, font, "Available categories", panelX + 14, panelY + 55,
                panelWidth - 28, "click to toggle");
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        for (int index = 0; index < visibleTypes.size(); index++) {
            if (selected.contains(visibleTypes.get(index))) {
                ScreenChrome.selected(graphics, font, typeButtons.get(index), ScreenChrome.ACCENT_DARK,
                        ScreenChrome.ACCENT, true);
            }
        }
        ScreenChrome.selected(graphics, font, confirmButton, ScreenChrome.ACCENT_DARK,
                ScreenChrome.ACCENT, true);
        String pageLabel = "Page " + (page + 1) + " / "
                + Math.max(1, (types.size() + pageSize - 1) / pageSize);
        graphics.text(font, Component.literal(pageLabel),
                panelX + (panelWidth - font.width(pageLabel)) / 2, panelY + panelHeight - 66,
                ScreenChrome.MUTED);
    }

    @Override
    public void onClose() {
        applyAndClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
