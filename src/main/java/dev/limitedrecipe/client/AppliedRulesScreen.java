package dev.limitedrecipe.client;

import java.util.ArrayList;
import java.util.List;

import dev.limitedrecipe.NetworkMessages;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

public final class AppliedRulesScreen extends Screen {
    private final RecipeAccessScreen parent;
    private final List<Button> ruleButtons = new ArrayList<>();
    private final List<RuleEntry> visibleRules = new ArrayList<>();
    private List<RuleEntry> rules = List.of();
    private RuleEntry selected;
    private int page;
    private int pageSize;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int listWidth;
    private int detailX;
    private int detailWidth;
    private Button recipeJsonButton;
    private Button editRuleButton;
    private Button deleteRuleButton;
    private Button ruleEnabledButton;
    private boolean confirmingDelete;
    private String status = "";

    public AppliedRulesScreen(RecipeAccessScreen parent) {
        super(Component.literal("Applied Recipe Rules"));
        this.parent = parent;
    }

    public RecipeAccessScreen parentEditor() {
        return parent;
    }

    @Override
    protected void init() {
        rules = parent.appliedRules();
        if (selected == null || rules.stream().noneMatch(rule -> rule.recipeId().equals(selected.recipeId()))) {
            selected = rules.isEmpty() ? null : rules.getFirst();
        } else {
            selected = rules.stream().filter(rule -> rule.recipeId().equals(selected.recipeId())).findFirst()
                    .orElse(null);
        }
        ruleButtons.clear();
        visibleRules.clear();
        panelWidth = Math.min(900, Math.max(380, width - 20));
        panelHeight = Math.min(520, Math.max(320, height - 16));
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;
        listWidth = Math.max(145, (panelWidth - 56) * 2 / 5);
        detailX = panelX + 28 + listWidth;
        detailWidth = panelX + panelWidth - 14 - detailX;
        pageSize = Math.max(1, Math.min(14, (panelHeight - 162) / 21));

        for (int row = 0; row < pageSize; row++) {
            final int index = row;
            ruleButtons.add(addRenderableWidget(Button.builder(Component.empty(), ignored -> {
                if (index < visibleRules.size()) {
                    selected = visibleRules.get(index);
                    confirmingDelete = false;
                    status = "";
                    refreshRuleButtons();
                    refreshActionButtons();
                }
            }).bounds(panelX + 14, panelY + 74 + row * 21, listWidth, 19).build()));
        }
        int pagerY = panelY + panelHeight - 74;
        addRenderableWidget(Button.builder(Component.literal("Prev"), ignored -> {
            page = Math.max(0, page - 1);
            refreshRuleButtons();
        }).bounds(panelX + 14, pagerY, 64, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Next"), ignored -> {
            page++;
            refreshRuleButtons();
        }).bounds(panelX + 14 + listWidth - 64, pagerY, 64, 20).build());

        int actionX = detailX;
        int actionWidth = (detailWidth - 6) / 2;
        int rowOneY = panelY + panelHeight - 100;
        int rowTwoY = panelY + panelHeight - 72;
        int rowThreeY = panelY + panelHeight - 44;
        editRuleButton = addRenderableWidget(Button.builder(Component.literal("Edit rule"), ignored -> editRule())
                .bounds(actionX, rowOneY, actionWidth, 22).build());
        ruleEnabledButton = addRenderableWidget(Button.builder(Component.empty(), ignored -> toggleRuleEnabled())
                .bounds(actionX + actionWidth + 6, rowOneY, actionWidth, 22).build());
        deleteRuleButton = addRenderableWidget(Button.builder(Component.literal("Delete rule"),
                ignored -> deleteRule())
                .bounds(actionX, rowTwoY, actionWidth, 22).build());
        recipeJsonButton = addRenderableWidget(Button.builder(Component.literal("Recipe JSON"),
                ignored -> requestRecipeJson())
                .bounds(actionX + actionWidth + 6, rowTwoY, actionWidth, 22).build());
        addRenderableWidget(Button.builder(Component.literal("Rules JSON"), ignored -> requestAccessJson())
                .bounds(actionX, rowThreeY, actionWidth, 22).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), ignored -> onClose())
                .bounds(actionX + actionWidth + 6, rowThreeY, actionWidth, 22).build());
        refreshRuleButtons();
        refreshActionButtons();
    }

    private void refreshRuleButtons() {
        int maxPage = Math.max(0, (rules.size() - 1) / pageSize);
        page = Math.min(page, maxPage);
        visibleRules.clear();
        int maxChars = Math.max(18, (listWidth - 10) / 6);
        for (int row = 0; row < ruleButtons.size(); row++) {
            Button button = ruleButtons.get(row);
            int ruleIndex = page * pageSize + row;
            if (ruleIndex < rules.size()) {
                RuleEntry rule = rules.get(ruleIndex);
                visibleRules.add(rule);
                String prefix = rule == selected ? "> " : "";
                String suffix = rule.enabled() ? " [on]" : " [off]";
                button.setMessage(Component.literal(shorten(prefix + rule.recipeId() + suffix, maxChars)));
                button.active = true;
            } else {
                button.setMessage(Component.empty());
                button.active = false;
            }
        }
    }

    private void refreshActionButtons() {
        if (recipeJsonButton != null) {
            recipeJsonButton.active = selected != null;
        }
        if (editRuleButton != null) {
            editRuleButton.active = selected != null && parent.hasRecipe(selected.recipeId());
        }
        if (ruleEnabledButton != null) {
            ruleEnabledButton.active = selected != null && !status.startsWith("Turning rule");
            ruleEnabledButton.setMessage(Component.literal(selected == null ? "Rule state"
                    : selected.enabled() ? "Turn rule off" : "Turn rule on"));
        }
        if (deleteRuleButton != null) {
            deleteRuleButton.active = selected != null && !status.startsWith("Deleting");
            deleteRuleButton.setMessage(Component.literal(confirmingDelete ? "Confirm?" : "Delete rule"));
        }
    }

    private void editRule() {
        if (selected != null && parent.openRecipeForEditing(selected.recipeId())) {
            minecraft.setScreen(parent);
        } else {
            status = "This recipe is no longer loaded. You can still remove this stale rule.";
        }
    }

    private void deleteRule() {
        if (selected == null) {
            return;
        }
        if (!confirmingDelete) {
            confirmingDelete = true;
            status = "Click Confirm? to permanently remove this saved rule.";
            refreshActionButtons();
            return;
        }
        confirmingDelete = false;
        status = "Deleting rule on the server…";
        refreshActionButtons();
        ClientPacketDistributor.sendToServer(new NetworkMessages.DeleteRecipeRule(selected.recipeId()));
    }

    private void toggleRuleEnabled() {
        if (selected == null) {
            return;
        }
        boolean enabled = !selected.enabled();
        status = enabled ? "Turning rule on…" : "Turning rule off…";
        refreshActionButtons();
        ClientPacketDistributor.sendToServer(
                new NetworkMessages.SetRecipeRuleEnabled(selected.recipeId(), enabled));
    }

    private void requestRecipeJson() {
        if (selected != null) {
            ClientPacketDistributor.sendToServer(new NetworkMessages.RecipeJsonRequest(selected.recipeId()));
        }
    }

    private void requestAccessJson() {
        ClientPacketDistributor.sendToServer(new NetworkMessages.AccessRulesJsonRequest());
    }

    public void refreshFromServer() {
        String oldSelection = selected == null ? null : selected.recipeId();
        rules = parent.appliedRules();
        selected = oldSelection == null ? null : rules.stream()
                .filter(rule -> rule.recipeId().equals(oldSelection)).findFirst().orElse(null);
        if (selected == null && !rules.isEmpty()) {
            selected = rules.getFirst();
            page = 0;
        }
        confirmingDelete = false;
        status = "";
        refreshRuleButtons();
        refreshActionButtons();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        extractTransparentBackground(graphics);
        ScreenChrome.begin(graphics, font, width, height, panelX, panelY, panelWidth, panelHeight,
                "APPLIED RULES", "Inspect policy, edit saved settings, or restore defaults",
                rules.size() + " SAVED");
        ScreenChrome.card(graphics, panelX + 8, panelY + 52, listWidth + 12, panelHeight - 170);
        ScreenChrome.card(graphics, detailX - 8, panelY + 52, detailWidth + 16, panelHeight - 170);
        ScreenChrome.section(graphics, font, "Rule library", panelX + 14, panelY + 55,
                listWidth, rules.size() + " total");
        ScreenChrome.section(graphics, font, "Policy details", detailX, panelY + 55,
                detailWidth, selected == null ? "No selection" : selected.enabled() ? "Active" : "Paused");

        if (selected == null) {
            graphics.text(font, Component.literal("No saved recipe rules yet."), detailX, panelY + 82,
                    ScreenChrome.TEXT);
            graphics.text(font, Component.literal("Select Permissions JSON to view or edit the raw file."),
                    detailX, panelY + 102, ScreenChrome.MUTED);
        } else {
            ScreenChrome.chip(graphics, font, selected.enabled() ? "ON" : "OFF", detailX,
                    panelY + 75, selected.enabled() ? ScreenChrome.GOOD : ScreenChrome.WARN);
            int y = panelY + 99;
            int maxChars = Math.max(22, detailWidth / 6);
            String craftPolicy = !selected.enabled() ? "Anyone; rule is off"
                    : selected.craftMode().equals("Whitelist")
                    ? "Whitelist players may craft, even if globally disabled"
                    : selected.disabled() ? "No one; globally disabled"
                    : selected.craftMode().equals("Blacklist")
                    ? "Everyone except listed UUIDs may craft" : "Anyone may craft";
            y = drawLines(graphics, y, maxChars, "Who can craft: " + craftPolicy);
            String jeiPolicy = !selected.enabled() ? "Anyone; rule is off"
                    : selected.jeiMode().equals("Whitelist")
                    ? "Whitelist players may see it; others cannot"
                    : selected.hideInJei() ? "Hidden from everyone"
                    : selected.jeiMode().equals("Blacklist")
                    ? "Everyone except listed UUIDs may see it" : "Anyone may see it";
            if (panelHeight < 400) {
                y = drawLines(graphics, y, maxChars, shorten("Recipe: " + selected.recipeId(), maxChars));
                y = drawLines(graphics, y, maxChars, shorten("Type: " + selected.recipeType(), maxChars));
                y = drawLines(graphics, y, maxChars, shorten("Craft: " + craftPolicy, maxChars));
                y = drawLines(graphics, y, maxChars, shorten("JEI: " + jeiPolicy, maxChars));
                drawLines(graphics, y, maxChars, "Selected players — craft: " + selected.craftPlayers().size()
                        + " · JEI: " + selected.jeiPlayers().size());
            } else {
                y = drawLines(graphics, y, maxChars, "Recipe: " + selected.recipeId());
                y = drawLines(graphics, y, maxChars, "Type: " + selected.recipeType());
                y = drawLines(graphics, y, maxChars, "Who can craft: " + craftPolicy);
                y = drawLines(graphics, y, maxChars, "Who can see in JEI: " + jeiPolicy);
                y = drawLines(graphics, y, maxChars,
                        "Craft UUIDs (" + selected.craftPlayers().size() + "): ");
                int maxPlayerLines = Math.max(0, Math.min(4, (panelHeight - 360) / 14));
                for (String player : selected.craftPlayers().stream().limit(maxPlayerLines).toList()) {
                    y = drawLines(graphics, y, maxChars, "  " + player);
                }
                if (selected.craftPlayers().size() > maxPlayerLines) {
                    y = drawLines(graphics, y, maxChars, "  … and "
                            + (selected.craftPlayers().size() - maxPlayerLines) + " more");
                }
                y = drawLines(graphics, y, maxChars, "JEI UUIDs (" + selected.jeiPlayers().size() + "): ");
                for (String player : selected.jeiPlayers().stream().limit(maxPlayerLines).toList()) {
                    y = drawLines(graphics, y, maxChars, "  " + player);
                }
                if (selected.jeiPlayers().size() > maxPlayerLines) {
                    drawLines(graphics, y, maxChars, "  … and "
                            + (selected.jeiPlayers().size() - maxPlayerLines) + " more");
                }
            }
        }
        if (!status.isEmpty()) {
            graphics.text(font, Component.literal(shorten(status, Math.max(24, detailWidth / 6))), detailX,
                    panelY + panelHeight - 114, ScreenChrome.WARN);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        ScreenChrome.selected(graphics, font, selectedButton(), ScreenChrome.ACCENT_DARK,
                ScreenChrome.ACCENT, true);
    }

    private Button selectedButton() {
        for (int i = 0; i < visibleRules.size(); i++) {
            if (visibleRules.get(i) == selected || (selected != null && visibleRules.get(i).recipeId()
                    .equals(selected.recipeId()))) {
                return ruleButtons.get(i);
            }
        }
        return null;
    }

    private int drawLines(GuiGraphicsExtractor graphics, int y, int maxChars, String value) {
        for (int start = 0; start < value.length(); start += maxChars) {
            graphics.text(font, Component.literal(value.substring(start, Math.min(value.length(), start + maxChars))),
                    detailX, y, 0xFFFFFFFF);
            y += 13;
        }
        return y;
    }

    private static String shorten(String value, int maxChars) {
        return value.length() <= maxChars ? value : value.substring(0, maxChars - 1) + "…";
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    public record RuleEntry(String recipeId, String recipeType, String craftMode, List<String> craftPlayers,
                            String jeiMode, List<String> jeiPlayers, boolean hideInJei, boolean disabled,
                            boolean enabled) {
        public RuleEntry {
            craftPlayers = List.copyOf(craftPlayers);
            jeiPlayers = List.copyOf(jeiPlayers);
        }
    }
}
