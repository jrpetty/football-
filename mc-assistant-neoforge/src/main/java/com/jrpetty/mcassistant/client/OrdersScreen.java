package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.AssistantActions;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.net.OrderPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;

/**
 * The Orders screen: everything you can tell one specialist to do, on one page,
 * opened by looking at it and pressing the orders key. Reads its live state
 * straight off the entity (job, patch and status are synched), so it stays
 * honest while it's open.
 */
public class OrdersScreen extends Screen {

    private static final int W = 256, H = 208;

    private static final int PANEL      = 0xE8181A14;
    private static final int PANEL_EDGE = 0xFF3A4029;
    private static final int HEADER     = 0xFF232A1B;
    private static final int INK        = 0xFFEAEDE0;
    private static final int MUTED      = 0xFF9AA087;
    private static final int GOOD       = 0xFF78C78C;
    private static final int WARN       = 0xFFE0A83A;
    private static final int BAD        = 0xFFDE6C51;

    private final AssistantEntity bot;
    private int left, top;
    @Nullable private Button jobButton;
    @Nullable private Button depthDown, depthUp, dropOff;
    private int shownJob = -1;

    public OrdersScreen(AssistantEntity bot) {
        super(Component.literal("Orders"));
        this.bot = bot;
    }

    @Override
    protected void init() {
        this.left = (this.width - W) / 2;
        this.top = (this.height - H) / 2;
        int x = left + 12, y = top + 46;
        int w3 = (W - 24 - 8) / 3;   // three across
        int w4 = (W - 24 - 12) / 4;  // four across
        int h = 20, gap = 4;

        // --- the job itself ---
        add(x, y, 22, h, "<", AssistantActions.JOB_PREV);
        jobButton = add(x + 26, y, W - 24 - 52, h, jobTitle(), AssistantActions.WORK);
        add(x + W - 24 - 22, y, 22, h, ">", AssistantActions.JOB_NEXT);
        shownJob = bot.clientJobOrdinal();

        // --- where it works ---
        y += h + gap;
        add(x, y, w4, h, "Set Area", AssistantActions.ZONE_HERE);
        add(x + (w4 + gap), y, w4, h, "Area -", AssistantActions.ZONE_SHRINK);
        add(x + 2 * (w4 + gap), y, w4, h, "Area +", AssistantActions.ZONE_GROW);
        add(x + 3 * (w4 + gap), y, w4, h, "Show", AssistantActions.ZONE_SHOW);

        // --- handling ---
        y += h + gap;
        add(x, y, w4, h, "Follow", AssistantActions.FOLLOW);
        add(x + (w4 + gap), y, w4, h, "Stay", AssistantActions.STAY);
        add(x + 2 * (w4 + gap), y, w4, h, "Come", AssistantActions.COME);
        add(x + 3 * (w4 + gap), y, w4, h, "Guard", AssistantActions.GUARD);

        // --- items and errands ---
        y += h + gap;
        add(x, y, w4, h, "Stash", AssistantActions.STASH);
        add(x + (w4 + gap), y, w4, h, "Go Home", AssistantActions.GO_HOME);
        add(x + 2 * (w4 + gap), y, w4, h, "Stop", AssistantActions.STOP);
        add(x + 3 * (w4 + gap), y, w4, h, "Pack", AssistantActions.OPEN_PACK);

        // --- job-specific, shown only when it means something ---
        y += h + gap;
        depthDown = add(x, y, w4, h, "Deeper", AssistantActions.DEPTH_DOWN);
        depthUp = add(x + (w4 + gap), y, w4, h, "Shallower", AssistantActions.DEPTH_UP);
        dropOff = add(x, y, w3, h, "Set Drop-off", AssistantActions.SET_DROPOFF);

        // --- reports ---
        y += h + gap;
        add(x, y, w3, h, "Report", AssistantActions.REPORT);
        add(x + (w3 + gap), y, w3, h, "Whole Crew", AssistantActions.ROSTER);
        this.addRenderableWidget(Button.builder(Component.literal("Close"),
            b -> this.onClose()).bounds(x + 2 * (w3 + gap), y, w3, h).build());

        refreshJobButtons();
    }

    private Button add(int x, int y, int w, int h, String label, int action) {
        return this.addRenderableWidget(Button.builder(Component.literal(label),
            b -> sendOrder(bot, action)).bounds(x, y, w, h).build());
    }

    /** Fire an order at a bot. Safe to call with null (the key may find nobody). */
    public static void sendOrder(@Nullable AssistantEntity bot, int action) {
        if (bot == null) return;
        PacketDistributor.sendToServer(new OrderPayload(bot.getId(), action));
    }

    private String jobTitle() {
        AssistantEntity.StationTask job =
            AssistantEntity.StationTask.byOrdinal(bot.clientJobOrdinal());
        return job == AssistantEntity.StationTask.NONE
            ? "Pick a job" : job.title + "  (work / pause)";
    }

    private void refreshJobButtons() {
        if (depthDown == null || depthUp == null || dropOff == null) return;
        AssistantEntity.StationTask job =
            AssistantEntity.StationTask.byOrdinal(bot.clientJobOrdinal());
        boolean miner = job == AssistantEntity.StationTask.MINE;
        boolean hauler = job == AssistantEntity.StationTask.HAUL;
        depthDown.visible = miner;
        depthUp.visible = miner;
        dropOff.visible = hauler;
    }

    @Override
    public void tick() {
        super.tick();
        if (!bot.isAlive()) {
            this.onClose();
            return;
        }
        if (bot.clientJobOrdinal() != shownJob) {
            shownJob = bot.clientJobOrdinal();
            if (jobButton != null) jobButton.setMessage(Component.literal(jobTitle()));
            refreshJobButtons();
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g, mouseX, mouseY, partialTick);

        g.fill(left, top, left + W, top + H, PANEL);
        g.fill(left, top, left + W, top + 30, HEADER);
        g.renderOutline(left, top, W, H, PANEL_EDGE);

        // Who am I talking to, and how are they doing?
        String name = (bot.veteranLevel() >= 1 ? "✦" + bot.veteranLevel() + " " : "")
            + bot.displayNameCap();
        g.drawString(this.font, name, left + 12, top + 8, INK, false);

        String job = AssistantEntity.StationTask.byOrdinal(bot.clientJobOrdinal()).title;
        g.drawString(this.font, job, left + W - 12 - this.font.width(job), top + 8, MUTED, false);

        String status = bot.clientStatus();
        int colour = status.startsWith("Working") ? GOOD
            : status.startsWith("Needs") || status.startsWith("Out of") ? BAD : WARN;
        g.drawString(this.font, clip(status, W - 24), left + 12, top + 20, colour, false);

        // Where it works, along the bottom.
        g.drawString(this.font, clip(bot.clientZone(), W - 24),
            left + 12, top + H - 14, MUTED, false);

        super.render(g, mouseX, mouseY, partialTick);
    }

    private String clip(String text, int max) {
        if (this.font.width(text) <= max) return text;
        String cut = text;
        while (cut.length() > 3 && this.font.width(cut + "...") > max) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "...";
    }

    @Override
    public boolean isPauseScreen() {
        return false; // the world keeps running while you give orders
    }
}
