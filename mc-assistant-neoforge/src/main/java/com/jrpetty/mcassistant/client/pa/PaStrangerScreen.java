package com.jrpetty.mcassistant.client.pa;

import com.jrpetty.mcassistant.client.UiStyle;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * [pa33b] Someone else's assistant, right-clicked: "Chat with Basil (Dev's assistant)". A line to type and a few quick
 * things to say. It can't give orders, open its pack or change anything: the words go to it as a stranger's, and it
 * answers in its bubble, in kind.
 */
public final class PaStrangerScreen extends Screen {

    private static final String[] QUICK = {"Hello", "Nice outfit", "You're useless", "Your owner's rubbish"};

    private final int id;
    private final String name, owner;
    private EditBox box;
    private int left, top, w = 300, h = 96;

    public PaStrangerScreen(CompoundTag data) {
        super(Component.literal("Chat with " + data.getString("name")));
        this.id = data.getInt("id");
        this.name = data.getString("name");
        this.owner = data.getString("owner");
    }

    @Override
    protected void init() {
        left = (width - w) / 2;
        top = height - h - 40;
        box = new EditBox(font, left + 8, top + 24, w - 70, 16, Component.literal("Say something to " + name));
        box.setMaxLength(120);
        box.setHint(Component.literal("Say something to " + name + "..."));
        addRenderableWidget(box);
        setInitialFocus(box);
        addRenderableWidget(Button.builder(Component.literal("Say"), b -> send(box.getValue())).bounds(left + w - 58, top + 23, 50, 18).build());
        int x = left + 8;
        for (String q : QUICK) {
            int bw = font.width(q) + 12;
            addRenderableWidget(Button.builder(Component.literal(q), b -> send(q)).bounds(x, top + 48, bw, 16).build());
            x += bw + 4;
        }
    }

    private void send(String text) {
        if (text == null || text.isBlank()) return;
        CompoundTag a = new CompoundTag();
        a.putInt("id", id);
        a.putString("text", text.trim());
        PaClient.send("stranger", a);
        onClose();                                    // the answer comes in its bubble
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && box.isFocused()) { send(box.getValue()); return true; }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        // no blur: the assistant it's talking to stays in view
        g.fill(left, top, left + w, top + h - 20, 0xE01F3A3D);
        g.fill(left, top, left + 2, top + h - 20, 0xFF3FA796);
        g.drawString(font, UiStyle.clip(font, "Chat with " + name + (owner.isEmpty() ? "" : " (" + owner + "'s assistant)"), w - 16), left + 8, top + 8, 0xFFE8F6F3, false);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        g.drawString(font, UiStyle.clip(font, "It answers in its bubble. Only " + (owner.isEmpty() ? "its owner" : owner) + " can give it orders.", w - 16),
            left + 8, top + 68, 0xFF9DB5B0, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
