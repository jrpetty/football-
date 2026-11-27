package com.jrpetty.mcassistant.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * [screens13] A vanilla button in the house look (UiStyle.button): the same widget, so its keys, focus, narration,
 * tooltip and active state all work as before, only drawn as the mod's tile with its label centred and clipped. A label
 * cut short shows in full as its tooltip when it has none of its own. Made with {@code Button.builder(...).build(UiButton::new)}.
 * A primary button ({@link #primary}) carries the accent spine, for the one thing a screen is for.
 */
public class UiButton extends Button {

    private boolean primary;
    private String clippedFor = "";
    private boolean ownTip;

    public UiButton(Button.Builder builder) {
        super(builder);
    }

    /** The screen's main action: drawn chosen (the accent spine), so the eye goes to it first. */
    public UiButton primary() {
        this.primary = true;
        return this;
    }

    /** Wrap {@code Button.builder(...)} as a primary button. */
    public static UiButton primary(Button.Builder builder) {
        return ((UiButton) builder.build(UiButton::new)).primary();
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Font font = Minecraft.getInstance().font;
        boolean hot = isHoveredOrFocused();
        UiStyle.State s = !this.active ? UiStyle.State.DISABLED : hot ? UiStyle.State.HOVER : primary ? UiStyle.State.SELECTED : UiStyle.State.NORMAL;
        String label = getMessage().getString();
        boolean clipped = UiStyle.button(g, font, label, getX(), getY(), getWidth(), getHeight(), s);
        if (primary && hot && this.active) g.fill(getX() + 1, getY() + 1, getX() + 3, getY() + getHeight() - 1, UiStyle.ACCENT_FILL);
        if (clipped && (ownTip || getTooltip() == null) && !label.equals(clippedFor)) {
            clippedFor = label;
            ownTip = true;
            setTooltip(Tooltip.create(Component.literal(label)));
        } else if (!clipped && ownTip) {
            clippedFor = "";
            ownTip = false;
            setTooltip(null);
        }
    }
}
