package dev.limitedrecipe.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Shared visual language for the recipe-management screens. */
final class ScreenChrome {
    static final int INK = 0xFF0C1118;
    static final int PANEL = 0xF0161D27;
    static final int CARD = 0xFF1B2430;
    static final int CARD_DARK = 0xFF121923;
    static final int BORDER = 0xFF354456;
    static final int ACCENT = 0xFF69C9C1;
    static final int ACCENT_DARK = 0xFF275B5B;
    static final int TEXT = 0xFFF2F5F8;
    static final int MUTED = 0xFFAFBDCC;
    static final int GOOD = 0xFF8BD5A3;
    static final int WARN = 0xFFFFCA78;
    static final int DANGER = 0xFFFF8C82;

    private ScreenChrome() {}

    static void begin(GuiGraphicsExtractor graphics, net.minecraft.client.gui.Font font,
                      int screenWidth, int screenHeight, int panelX, int panelY,
                      int panelWidth, int panelHeight, String title, String subtitle, String badge) {
        graphics.fill(0, 0, screenWidth, screenHeight, 0x55070B10);
        graphics.fill(panelX + 4, panelY + 5, panelX + panelWidth + 4, panelY + panelHeight + 5,
                0x90000000);
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, PANEL);
        graphics.fill(panelX + 1, panelY + 1, panelX + panelWidth - 1, panelY + 48, 0xFF1C2632);
        graphics.outline(panelX, panelY, panelWidth, panelHeight, BORDER);
        graphics.fill(panelX + 1, panelY + 1, panelX + 4, panelY + panelHeight - 1, ACCENT);
        int textWidth = panelWidth - 28;
        if (badge != null && !badge.isBlank()) {
            int badgeTextWidth = Math.min(font.width(badge), Math.max(20, panelWidth / 3 - 24));
            String badgeLabel = fit(badge, badgeTextWidth, font);
            int badgeWidth = font.width(badgeLabel) + 16;
            int badgeX = panelX + panelWidth - badgeWidth - 12;
            graphics.fill(badgeX, panelY + 12, badgeX + badgeWidth, panelY + 32, CARD_DARK);
            graphics.outline(badgeX, panelY + 12, badgeWidth, 20, BORDER);
            graphics.text(font, Component.literal(badgeLabel), badgeX + 8, panelY + 18, ACCENT);
            textWidth = Math.max(12, badgeX - panelX - 22);
        }
        graphics.text(font, Component.literal(fit(title, textWidth, font)), panelX + 14, panelY + 10, TEXT);
        graphics.text(font, Component.literal(fit(subtitle, textWidth, font)), panelX + 14,
                panelY + 29, MUTED);
    }

    static void card(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        graphics.fill(x, y, x + width, y + height, CARD_DARK);
        graphics.outline(x, y, width, height, BORDER);
    }

    static void section(GuiGraphicsExtractor graphics, net.minecraft.client.gui.Font font,
                        String label, int x, int y, int width, String detail) {
        String visibleLabel = fit(label.toUpperCase(java.util.Locale.ROOT), Math.max(12, width / 2), font);
        graphics.text(font, Component.literal(visibleLabel), x, y, ACCENT);
        if (detail != null && !detail.isBlank()) {
            int maxDetailWidth = Math.max(12, width - font.width(visibleLabel) - 8);
            String visibleDetail = fit(detail, maxDetailWidth, font);
            int detailWidth = font.width(visibleDetail);
            graphics.text(font, Component.literal(visibleDetail), x + width - detailWidth, y, MUTED);
        }
        graphics.fill(x, y + 12, x + width, y + 13, BORDER);
    }

    static void chip(GuiGraphicsExtractor graphics, net.minecraft.client.gui.Font font,
                     String label, int x, int y, int color) {
        int width = font.width(label) + 12;
        graphics.fill(x, y, x + width, y + 16, 0xAA101720);
        graphics.outline(x, y, width, 16, color);
        graphics.text(font, Component.literal(label), x + 6, y + 4, color);
    }

    static void selected(GuiGraphicsExtractor graphics, net.minecraft.client.gui.Font font,
                         Button button, int fillColor, int outlineColor, boolean centered) {
        if (button == null || !button.visible || !button.active) {
            return;
        }
        int x = button.getX();
        int y = button.getY();
        int width = button.getWidth();
        int height = button.getHeight();
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, fillColor);
        graphics.outline(x, y, width, height, outlineColor);
        Component label = button.getMessage();
        int textX = centered ? x + Math.max(4, (width - font.width(label)) / 2) : x + 6;
        graphics.text(font, label, textX, y + (height - 9) / 2, TEXT);
    }

    private static String fit(String text, int maxWidth, net.minecraft.client.gui.Font font) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        String shortened = text;
        while (!shortened.isEmpty() && font.width(shortened + "…") > maxWidth) {
            shortened = shortened.substring(0, shortened.length() - 1);
        }
        return shortened.isEmpty() ? "…" : shortened + "…";
    }
}
