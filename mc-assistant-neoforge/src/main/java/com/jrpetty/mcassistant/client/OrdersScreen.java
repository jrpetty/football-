package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.AssistantActions;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.net.OrderPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;

/**
 * One specialist's orders, on one page: who it is, how it's doing, what it's
 * working towards, and every instruction you can give it. Opened by looking at
 * a bot and pressing the orders key.
 *
 * Laid out in labelled groups — the job, its patch, handling, duty — so it
 * reads as a control panel rather than a wall of identical buttons. The whole
 * panel stays under 240px tall so it fits at GUI scale 4 on a 1080p screen,
 * which is what auto-scale picks there.
 */
public class OrdersScreen extends Screen {

    private static final int W = 288, H = 236;
    private static final int PAD = 10;

    // Every vertical position on the page, in one place, so a change to one
    // row can't quietly land on top of another. (It did: the career lines were
    // drawn at exactly the duty row's Y, and the buttons covered them.)
    private static final int Y_NAME      = 8;
    private static final int Y_BAR       = 22;
    private static final int Y_CAREER    = 34;
    private static final int Y_PERKS     = 44;
    private static final int Y_SEC_JOB   = 58;
    private static final int Y_JOB       = 68;
    private static final int Y_SEC_PATCH = 90;
    private static final int Y_PATCH     = 100;
    private static final int Y_SEC_HAND  = 122;
    private static final int Y_HAND1     = 132;
    private static final int Y_HAND2     = 154;
    private static final int Y_SEC_DUTY  = 176;
    private static final int Y_DUTY      = 186;
    private static final int Y_FOOTER    = 208;

    private final AssistantEntity bot;
    private int left, top;
    @Nullable private Button jobButton, shiftButton;
    @Nullable private Button depthSurface, depthIron, depthDiamond, dropOff, escortBtn;
    @Nullable private Button claimBedBtn, stanceBtn;
    private int shownStance;
    @Nullable private Button quarryBtn;
    private int shownJob = -1;

    public OrdersScreen(AssistantEntity bot) {
        super(Component.literal("Orders"));
        this.bot = bot;
    }

    @Override
    protected void init() {
        this.left = (this.width - W) / 2;
        this.top = (this.height - H) / 2;
        int x = left + PAD;
        int inner = W - PAD * 2;
        int h = 18, gap = 4;
        int w4 = (inner - gap * 3) / 4;

        // ---- the job itself: arrows either side of a work/pause toggle ----
        int y = top + Y_JOB;
        add(x, y, 18, h, "‹", AssistantActions.JOB_PREV, "Previous job");
        jobButton = add(x + 22, y, inner - 44, h, jobTitle(), AssistantActions.WORK,
            "Click to start or pause this job");
        add(x + inner - 18, y, 18, h, "›", AssistantActions.JOB_NEXT, "Next job");
        shownJob = bot.clientJobOrdinal();

        // ---- its ground: claim it, see it, or manage everything from the
        // plot book. Resizing moved to where it is visual — drag on the map,
        // or click more blocks with the wand.
        y = top + Y_PATCH;
        int w3 = (inner - gap * 2) / 3;
        add(x, y, w3, h, "Set Area", AssistantActions.ZONE_HERE,
            "Claim a patch centred where you're standing");
        add(x + (w3 + gap), y, w3, h, "Show", AssistantActions.ZONE_SHOW,
            "Outline the patch in particles");
        this.addRenderableWidget(Button.builder(Component.literal("Plot Book \u203a"), b -> {
                this.onClose();
                PlotsClient.requestOpen();
            })
            .bounds(x + 2 * (w3 + gap), y, inner - 2 * (w3 + gap), h)
            .tooltip(Tooltip.create(Component.literal(
                "Every plot and the whole crew on one page — assign, trade, shift, copy, undo")))
            .build());

        // ---- handling ----
        y = top + Y_HAND1;
        add(x, y, w4, h, "Follow", AssistantActions.FOLLOW, "Come with me (stops work)");
        add(x + (w4 + gap), y, w4, h, "Stay", AssistantActions.STAY, "Wait here (stops work)");
        add(x + 2 * (w4 + gap), y, w4, h, "Come", AssistantActions.COME, "Walk to me now");
        add(x + 3 * (w4 + gap), y, w4, h, "Stop", AssistantActions.STOP, "Drop whatever it's doing");

        // ---- duty, bed, and the job-specific control ----
        y = top + Y_DUTY;
        shiftButton = add(x, y, w4 * 2 + gap, h, dutyLabel(), AssistantActions.CYCLE_SHIFT,
            "When this one works. Off duty it sleeps in its bed.");
        // Claim Bed retired: they claim their own beds at dusk, and have
        // since b81 — a button for a thing that happens by itself is noise.
        claimBedBtn = add(x + 2 * (w4 + gap), y, w4, h, "Stash", AssistantActions.STASH,
            "Empty its pack into the chests");
        // The guard's version of that slot: how it fights.
        shownStance = BotInfo.of(bot).stance();
        stanceBtn = this.addRenderableWidget(Button.builder(
                Component.literal(stanceWord()), b -> {
                    sendOrder(bot, AssistantActions.STANCE_NEXT);
                    shownStance = (shownStance + 1) % AssistantEntity.Stance.values().length;
                    b.setMessage(Component.literal(stanceWord()));
                })
            .bounds(x + 2 * (w4 + gap), y, w4, h)
            .tooltip(Tooltip.create(Component.literal(
                "How it fights. Auto reads the fight: bow at range, blade in close. "
                + "Ranged keeps the bow up and its distance. Melee keeps the blade, "
                + "and leaves creepers alone.")))
            .build());
        // How deep, and how it gets there — the whole mining plan in the one
        // slot a miner gets. Labels are terse because a quarter of a slot IS
        // fifteen pixels; the tooltips carry the meaning.
        int wp = (w4 - 6) / 4;
        int dx0 = x + 3 * (w4 + gap);
        depthSurface = add(dx0, y, wp, h, "S", AssistantActions.DEPTH_SURFACE,
            "Depth: surface — work the ground as marked, no deep shaft");
        depthIron = add(dx0 + wp + 2, y, wp, h, "16", AssistantActions.DEPTH_IRON,
            "Depth: iron country — down to Y16");
        depthDiamond = add(dx0 + 2 * (wp + 2), y, wp, h, "D", AssistantActions.DEPTH_DIAMOND,
            "Depth: diamond country — down to Y-54");
        quarryBtn = this.addRenderableWidget(Button.builder(
                Component.literal(BotInfo.of(bot).quarry() ? "▤" : "▬"), b -> {
                    sendOrder(bot, AssistantActions.QUARRY_TOGGLE);
                    boolean on = b.getMessage().getString().equals("▬");
                    b.setMessage(Component.literal(on ? "▤" : "▬"));
                })
            .bounds(dx0 + 3 * (wp + 2), y, w4 - 3 * (wp + 2), h)
            .tooltip(Tooltip.create(Component.literal(
                "Quarry: cut a level, drop four blocks, cut the next — terraced "
                + "all the way down to the depth above. Off, it cuts one gallery and stops.")))
            .build());
        dropOff = add(x + 3 * (w4 + gap), y, w4, h, "Drop-off", AssistantActions.SET_DROPOFF,
            "Deliver loads to where you're standing");
        escortBtn = add(x + 3 * (w4 + gap), y, w4, h, "Escort ›", AssistantActions.ESCORT_NEXT,
            "Shadow a crewmate instead of ground — click again for the next one, "
            + "and past the last name to go back to the beat");

        // ---- footer: five now, so the chatter toggle gets a home ----
        y = top + Y_FOOTER;
        int w5 = (inner - gap * 4) / 5;
        add(x, y, w5, h, "Pack", AssistantActions.OPEN_PACK, "Open its inventory and gear");
        this.addRenderableWidget(Button.builder(Component.literal("Record"),
                b -> this.minecraft.setScreen(new RecordScreen(bot)))
            .bounds(x + (w5 + gap), y, w5, h)
            .tooltip(Tooltip.create(Component.literal(
                "Everything it has done in its career, counted")))
            .build());
        this.addRenderableWidget(Button.builder(Component.literal("Name"),
                b -> this.minecraft.setScreen(new NameScreen(bot)))
            .bounds(x + 2 * (w5 + gap), y, w5, h)
            .tooltip(Tooltip.create(Component.literal("Give this one a name — pick, don't type")))
            .build());
        boolean quietNow = BotInfo.of(bot).quiet();
        this.addRenderableWidget(Button.builder(
                Component.literal(quietNow ? "Chatty" : "Quiet"), b -> {
                    OrdersScreen.sendOrder(bot, AssistantActions.QUIET_TOGGLE);
                    boolean q = b.getMessage().getString().equals("Quiet");
                    b.setMessage(Component.literal(q ? "Chatty" : "Quiet"));
                })
            .bounds(x + 3 * (w5 + gap), y, w5, h)
            .tooltip(Tooltip.create(Component.literal(
                "Mute the routine chatter — needs, deaths and paydays still come through")))
            .build());
        this.addRenderableWidget(Button.builder(Component.literal("Close"), b -> this.onClose())
            .bounds(x + 4 * (w5 + gap), y, inner - 4 * (w5 + gap), h).build());

        refreshJobButtons();
    }

    private Button add(int x, int y, int w, int h, String label, int action, String tip) {
        Button b = Button.builder(Component.literal(label), btn -> sendOrder(bot, action))
            .bounds(x, y, w, h)
            .tooltip(Tooltip.create(Component.literal(tip)))
            .build();
        return this.addRenderableWidget(b);
    }

    /** Fire an order at a bot. Safe with null — the key may find nobody. */
    public static void sendOrder(@Nullable AssistantEntity bot, int action) {
        if (bot == null) return;
        PacketDistributor.sendToServer(new OrderPayload(bot.getId(), action));
    }

    private String jobTitle() {
        AssistantEntity.StationTask job =
            AssistantEntity.StationTask.byOrdinal(bot.clientJobOrdinal());
        return job == AssistantEntity.StationTask.NONE ? "Pick a job" : job.title;
    }

    private String stanceWord() {
        return AssistantEntity.Stance.byOrdinal(shownStance).label + " \u203a";
    }

    private String dutyLabel() {
        return "Works " + bot.clientShift().label;
    }

    /** Only ever show the control that means something for this job. */
    private void refreshJobButtons() {
        if (depthSurface == null || depthIron == null || depthDiamond == null
            || dropOff == null) return;
        AssistantEntity.StationTask job =
            AssistantEntity.StationTask.byOrdinal(bot.clientJobOrdinal());
        boolean miner = job == AssistantEntity.StationTask.MINE;
        boolean hauler = job == AssistantEntity.StationTask.HAUL;
        depthSurface.visible = miner;
        depthIron.visible = miner;
        depthDiamond.visible = miner;
        if (quarryBtn != null) quarryBtn.visible = miner;
        dropOff.visible = false;   // the hauler's route is two wand-linked chests now
        boolean guarding = job == AssistantEntity.StationTask.GUARD;
        if (escortBtn != null) escortBtn.visible = guarding;
        if (stanceBtn != null) stanceBtn.visible = guarding;
        if (claimBedBtn != null) claimBedBtn.visible = !guarding;
    }

    @Override
    public void tick() {
        super.tick();
        if (!bot.isAlive()) {
            this.onClose();
            return;
        }
        if (shiftButton != null) shiftButton.setMessage(Component.literal(dutyLabel()));
        if (bot.clientJobOrdinal() != shownJob) {
            shownJob = bot.clientJobOrdinal();
            if (jobButton != null) jobButton.setMessage(Component.literal(jobTitle()));
            refreshJobButtons();
        }
    }

    /**
     * ALL of this screen's own painting happens here, layered between the
     * background and the widgets, because since 1.21 the vanilla render loop
     * runs the menu BLUR SHADER inside renderBackground — and super.render()
     * calls renderBackground itself. The old pattern (paint the panel, then
     * call super.render) ran the blur a SECOND time over everything already
     * drawn: every panel and every line of text got gaussian-smeared, and the
     * vanilla buttons were drawn crisp on top afterwards. That is the blur
     * that survived four palette overhauls — it was never contrast.
     */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        Ui.panel(g, left, top, W, H, 30);

        int x = left + PAD;
        int inner = W - PAD * 2;
        int lvl = bot.clientLevel();

        // Header: who, what level, and the one status line that matters.
        String name = bot.clientName();
        Ui.chip(g, x, top + Y_NAME, Ui.job(bot.clientJobOrdinal()));
        g.drawString(this.font, name, x + 10, top + Y_NAME, Ui.INK, false);
        if (lvl >= 1) {
            g.drawString(this.font, "✦" + lvl, x + 10 + this.font.width(name) + 6, top + Y_NAME,
                Ui.ACCENT, false);
        }
        String status = Ui.clip(this.font, bot.clientStatus(), inner - 120);
        int sw = this.font.width(status) + 8;
        Ui.pill(g, this.font, status, left + W - PAD - sw, top + Y_NAME, Ui.statusColour(status));

        // Level progress, so "how close is my veteran" is answerable at a glance.
        if (lvl < 50) {
            Ui.bar(g, x, top + Y_BAR, inner - 46, 4, Ui.levelProgress(lvl, bot.clientLifetimeXp()),
                Ui.ACCENT);
            Ui.right(g, this.font, "lv " + (lvl + 1), left + W - PAD, top + Y_BAR - 2, Ui.FAINT);
        }

        // Its patch, its service and what it has earned — high on the page,
        // where there's room, rather than underneath the buttons.
        BotInfo info = BotInfo.of(bot);
        String career = bot.clientZone() + "  ·  " + info.daysServed() + "d served"
            + (info.hasBranch() ? "  ·  " + info.branch() : "")
            + (info.topDeed().isEmpty() ? "" : "  ·  " + info.topDeed());
        String dietPct = info.diet() + "%";
        g.drawString(this.font, Ui.clip(this.font, career,
            inner - this.font.width(dietPct) - 8), x, top + Y_CAREER, Ui.MUTED, false);
        g.drawString(this.font, Ui.clip(this.font, perkLine(lvl), inner), x, top + Y_PERKS,
            lvl >= 10 ? Ui.GOOD : Ui.FAINT, false);
        // Diet sits with the status pill, not with the perks: it is a condition
        // you can fix right now, not something the bot has earned.
        Ui.right(g, this.font, dietPct, left + W - PAD, top + Y_CAREER,
            info.dietColour());

        // Section headings.
        Ui.section(g, this.font, "Job", x, top + Y_SEC_JOB, inner);
        Ui.section(g, this.font, "Ground", x, top + Y_SEC_PATCH, inner);
        Ui.section(g, this.font, "Orders", x, top + Y_SEC_HAND, inner);
        Ui.section(g, this.font, "Duty", x, top + Y_SEC_DUTY, inner);

    }

    /** What it has earned, and what it earns next. */
    private String perkLine(int lvl) {
        String have = lvl >= 35 ? "+2♥ +30% work +20% speed"
            : lvl >= 30 ? "+2♥ +20% work +20% speed"
            : lvl >= 20 ? "+2♥ +20% work"
            : lvl >= 10 ? "+10% work"
            : "No bonuses yet";
        String next = lvl < 10 ? "next lv10: +10% work"
            : lvl < 20 ? "next lv20: +2♥, +20% work"
            : lvl < 30 ? "next lv30: +20% speed"
            : lvl < 35 ? "next lv35: +30% work"
            : "fully trained";
        return have + "  ·  " + next;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
