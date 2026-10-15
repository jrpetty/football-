package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.BattleModel;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * [war] A battle between two armies, organised, in front of whoever is watching; on paper with nobody near.
 *
 * <p><b>In full</b> (a player within view of the field): the lines <b>form</b> (Battlefields.GAP apart, each
 * facing the other: the shields in front, the archers behind, the reserve and the Captain with the banner at
 * the back, the medic behind them; a hidden reserve out on the flank behind its cover, a rise's archers on the
 * rise) to the drums. The two Captains meet in the middle for the <b>parley</b>: the last terms offered and
 * refused, and they go back. Each Captain's <b>speech</b> to its lines, a beat of silence, the horn and the battle
 * cry. A bold Captain may offer single combat: the two <b>champions</b> fight between the lines till one yields.
 * The <b>archers' duel</b>: "Archers, loose!", volleys arcing together, the shields up to take them. The
 * <b>advance</b>: "Shields up! Advance!", the line coming on at a walk (a shield wall and a rise's archers wait
 * for them; a feigned retreat gives ground and turns), then "Charge!". The <b>clash</b>: each in the line at its
 * man, sergeant at sergeant where they can, the archers still loosing over the line; the <b>flank move</b> (the
 * hidden reserve or the reserve sent round, "Reserve, round their left!"); the <b>crisis</b> (the banner falls
 * when its bearer yields, the Captain wounded, "Hold the line!"); the <b>break</b> (an ordered retreat, facing
 * them, step by step; a rout, everybody for home; a surrender, on their knees); a short <b>pursuit</b>, and the
 * call to let them go. Then the <b>aftermath</b>: each medic binds its own wounded, the winner leads the loser's
 * wounded away captive (on leads, if it brought them), and the fallen banner is taken as a trophy. Spirit is the
 * model's (BattleModel): losses, the Captain down, the banner lost, the flank turned, being outnumbered. A bar
 * at the top of the screen shows every player near the field both sides' strength and spirit, and the orders
 * come up over the hotbar.
 *
 * <p><b>On paper</b>: the same sides, by the same model, round by round (BattleModel.resolve), in an instant,
 * with the same outcomes: who yielded, who is taken, who (rarely) fell.
 *
 * <p>Either way the battle is written up (record): in both towns' chronicles and the war's books, its name and
 * story, the war's score and weariness, a cairn and a sign where the lines met, the veterans' memories and
 * their honours (a medal for the brave, out of the stores, and a name: "Brannoc the Bold").
 *
 * <p>The conductor works twice a second while a battle is fought in full (EVERY ticks): no more than both
 * armies' fighters looked over (a few dozen), never a look round the world.
 */
@EventBusSubscriber(modid = com.jrpetty.mcassistant.McAssistantMod.MODID)
public final class Battles {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private Battles() {}

    /** The conductor's beat, in ticks. */
    static final int EVERY = 10;
    /** The longest a battle is fought before the side with the less heart in it draws off. */
    static final long LONGEST = 6000L;
    /** [war-polish] How far off the field a battle's outcome is told (blocks). */
    static final int ANNOUNCE = 400;
    /** How long the lines take to form, at most; the parley; the speeches; the champions; between volleys. */
    static final long FORM = 600L, PARLEY = 400L, SPEECH = 200L, CHAMPIONS = 800L, VOLLEY = 60L, PURSUIT = 200L, AFTERMATH = 600L;
    /** Volleys in the archers' duel before the lines come on. */
    static final int VOLLEYS = 4;
    /**
     * [war-bows] The crossbow company's volleys, a rank at a time (the counter-march): one every three seconds while a
     * rank stands spanned. The front rank stands this far before the line, the reloading rank kneels this far, and once
     * the lines are this close the company falls back behind the shields.
     */
    static final long BOLT_EVERY = 60L;
    static final double BOLT_FRONT = 4.2, BOLT_BACK = 2.6, BOLT_RETIRE = 10.0;
    /** [war-bows] The crossbow volleys remembered, by side (who loosed each), for the books' tests: the last few. */
    static final int BOLT_LOG = 8;
    /** Captives taken in one battle, at most: the rest are let go on their word. */
    static final int CAPTIVES_MOST = 8;
    /** Bursts of the clash's sparks a beat, at most. */
    static final int SPARKS = 6;
    /** [war-polish] Drawn up before the horn: the most a mark strays off its slot (blocks), and the share of a fighter's own step forward or back. */
    static final double STILL_JITTER = 0.12, STILL_STEP = 0.4;
    /** [war-polish] Before the horn a fighter glances aside now and then: for a beat of so many ticks in every so many, so far (degrees). */
    static final long IDLE_BEAT = 60L, IDLE_EVERY = 5L;
    static final float IDLE_TURN = 15.0F;
    /** [war-polish] Back from the line: the archers at least, the Captain and the standard at least, and the gap between each group (blocks). */
    static final double ARCHERS_BACK = 4.0, COMMAND_BACK = 10.0, GROUP_GAP = 3.0;
    /** [war-polish] The charge staged for the pictures: the lines this far apart, running in so far a beat till this close. */
    static final double CHARGE_GAP = 14.0, CHARGE_STEP = 1.0, CHARGE_STOP = 4.0, CHARGE_SPEED = 1.25;

    /** Tests: the chance of a death, whatever the config says (null: as it says). */
    @Nullable static Integer deathsForTests;
    /** Tests: the parley's surrender, offered and then taken (true) or refused (false) whatever the odds; null as the model has it. */
    @Nullable static Boolean parleyForTests;

    static void resetForTests() {
        deathsForTests = null;
        parleyForTests = null;
    }

    /** Tests: a surrender at the parley offered and taken (true), offered and refused (false), or as the model has it (null). */
    public static void parleyForTests(@Nullable Boolean taken) {
        parleyForTests = taken;
    }

    /** Tests: deaths at this chance in a hundred (null: the config's). */
    public static void deathsForTests(@Nullable Integer percent) {
        deathsForTests = percent;
    }

    // ------------------------------------------------------------------ the battle

    /** One battle: the two armies, the ground, its phase, the spirit of each side, and how it ended. */
    public static final class Battle {
        final Campaigns.Campaign c;
        final Armies.Army[] side = new Armies.Army[2];
        final BlockPos centre;
        final double ax, az;
        final boolean assault, detailed;
        BattleModel.Phase phase = BattleModel.Phase.FORM;
        long phaseAt, began;
        /** How far forward of where it drew up each side's line has come (blocks). */
        final double[] forward = new double[2];
        final int[] yieldedSeen = new int[2], down = new int[2], volleys = new int[2];
        final boolean[] captainDown = new boolean[2], bannerLost = new boolean[2], flanked = new boolean[2], reserveSent = new boolean[2];
        final boolean[] fellBack = new boolean[2];
        final long[] rallied = { -1000, -1000 };
        @Nullable UUID[] champion = new UUID[2];
        boolean championsDone;
        int winner = -1;
        BattleModel.Outcome outcome;
        boolean over;
        @Nullable BattleModel.Result paper;
        final List<String> story = new ArrayList<>();
        final ServerBossEvent[] bars = new ServerBossEvent[2];
        /** The banners fallen on the field: the item lying there, by side. */
        final UUID[] fallenBanner = new UUID[2];
        final ItemStack[] fallenCloth = { ItemStack.EMPTY, ItemStack.EMPTY };
        final List<String> captives = new ArrayList<>();
        long lastVolley;
        int gateHits;
        boolean gateDown;
        /** How far the lines have drifted off their marks (0 dressed, 1 ragged): a sergeant's shout straightens them. */
        double ragged = 0.6;
        /** Settled without a blow (a surrender taken at the parley), or one side gone behind its walls rather than fight. */
        boolean settled, withdrew;
        /** [war-polish] Staged at the charge: the lines run in till they all but meet, and are held there. */
        boolean charging;
        /** [war-bows] The arrows each side has loosed on this field (a third gathered again by the side that holds it). */
        final int[] spent = new int[2];
        /** [war-bows] When each side's crossbow company last loosed a rank. */
        final long[] lastBolt = { -1000, -1000 };
        /** [war-bows] Who loosed each of the last few crossbow volleys, by side (the counter-march, rank by rank). */
        final List<List<UUID>> bolted0 = new ArrayList<>(), bolted1 = new ArrayList<>();
        /** [war-bows] The arrows gathered off the field once it is held: -1 not yet. */
        int gathered = -1;
        /** [war-bows] The defenders' places on the town wall in an assault (CastleWall's posts, nearest the gate first), found once. */
        @Nullable List<Watch.Post> wall;

        Battle(Campaigns.Campaign c, BlockPos centre, double ax, double az, boolean assault, boolean detailed) {
            this.c = c;
            this.centre = centre;
            this.ax = ax;
            this.az = az;
            this.assault = assault;
            this.detailed = detailed;
            side[0] = c.armies[0];
            side[1] = c.armies[1];
        }

        public boolean over() { return over; }
        public int winner() { return winner; }
        @Nullable public BattleModel.Outcome outcome() { return outcome; }
        public boolean detailed() { return detailed; }
        public BattleModel.Phase phase() { return phase; }
        public List<String> story() { return story; }
        @Nullable public BattleModel.Result result() { return paper; }
        public List<String> captives() { return captives; }
        public int down(int i) { return down[i]; }
        /** [war-bows] The arrows side i has loosed; who loosed each of its last crossbow volleys; the arrows gathered (-1 not yet). */
        public int spent(int i) { return spent[i]; }
        public List<List<UUID>> bolted(int i) { return i == 0 ? bolted0 : bolted1; }
        public int gathered() { return gathered; }
        /** [war-bows] Tests: the field left now, its spent arrows gathered by the side that holds it. */
        public void gatherForTests() { gather(); }

        String name(int i) {
            return Villages.name(side[i].town);
        }

        /** Side i's origin: where its line draws up (and how far it has come on), and the way it faces. */
        double[] origin(int i) {
            double back = Battlefields.GAP / 2.0 - forward[i];
            double sx = i == 0 ? -1 : 1;
            return new double[]{ centre.getX() + 0.5 + ax * sx * back, centre.getZ() + 0.5 + az * sx * back, -sx * ax, -sx * az };
        }

        /** The conductor's beat. */
        void tick(ServerLevel level) {
            if (over || !detailed) return;
            try {
                beat(level);
            } catch (RuntimeException e) {
                com.jrpetty.mcassistant.Guard.struck("a battle's beat", c.name, e);
                onPaper(level);
            }
        }

        void beat(ServerLevel level) {
            long now = level.getGameTime();
            if (side[1] == null || side[0] == null) { onPaper(level); return; }
            long in = now - phaseAt;
            if (now % 20 < EVERY) {
                spirit(level, now);
                bars(level);
            }
            if (phase.ordinal() <= BattleModel.Phase.ADVANCE.ordinal()) life(level, now);
            if (phase.ordinal() < BattleModel.Phase.PURSUIT.ordinal()) for (int i = 0; i < 2; i++) crossbows(level, i, now);   // [war-bows]
            switch (phase) {
                case FORM -> form(level, in);
                case PARLEY -> parley(level, in);
                case SPEECH -> speech(level, in);
                case CHAMPIONS -> champions(level, in);
                case VOLLEYS -> archery(level, now, in);
                case ADVANCE -> advance(level, in);
                case CLASH -> clash(level, now, in);
                case PURSUIT -> pursuit(level, in);
                case AFTERMATH -> aftermath(level, in);
                default -> { }
            }
            if (!over && hold == null && phase.ordinal() < BattleModel.Phase.PURSUIT.ordinal() && now - began > (Campaigns.hurried() ? LONGEST / 2 : LONGEST)) {
                // Neither side has given way by evening: the one with less heart in it draws off.
                int loser = side[0].morale < side[1].morale ? 0 : side[1].morale < side[0].morale ? 1 : -1;
                if (loser < 0) {
                    winner = -1;
                    outcome = BattleModel.Outcome.DRAWN;
                    story.add("Neither side would give way, and both drew off.");
                    to(level, BattleModel.Phase.AFTERMATH);
                } else breakSide(level, loser, BattleModel.Outcome.RETREAT);
            }
        }

        void to(ServerLevel level, BattleModel.Phase p) {
            if (hold != null && phase == hold) return;                 // staged for the pictures: it stays here
            phase = p;
            phaseAt = level.getGameTime();
        }

        /** Staged for the pictures (Campaigns.stage): the battle stays at this phase till it is let go. */
        @Nullable BattleModel.Phase hold;

        /** [war-polish] Held at its phase for the pictures: what would move it on (and say so) waits. */
        boolean held() {
            return hold != null && phase == hold;
        }

        /** [war-polish] Drawn up and waiting for the horn: the ranks stand true and still. */
        boolean still() {
            return phase.ordinal() < BattleModel.Phase.VOLLEYS.ordinal();
        }

        /**
         * Pictures: the battle set straight at a phase and held there: the lines formed (lineup), the Captains in the
         * middle (parley), the volleys (volley), the lines locked (clash), one side running (rout), the medics and the
         * captives (aftermath). The fighters' places worked out at once, for the stage to set them down in.
         */
        void stagePhase(ServerLevel level, String what) {
            hold = null;                                           // [war-polish] staged on from wherever it was held
            charging = false;
            switch (what) {
                case "parley" -> stageAt(level, BattleModel.Phase.PARLEY);
                case "horn" -> {                                   // [war-polish] the beat of silence over: the horn and the battle cry
                    stageAt(level, BattleModel.Phase.SPEECH);
                    sound(level);
                }
                case "volley" -> {
                    stageAt(level, BattleModel.Phase.VOLLEYS);
                    shields(true);
                }
                case "charge" -> {                                 // [war-polish] the lines coming on at a run
                    forward[0] = forward[1] = (Battlefields.GAP - CHARGE_GAP) / 2.0;
                    stageAt(level, BattleModel.Phase.ADVANCE);
                    charging = true;
                    shields(false);
                    for (int i = 0; i < 2; i++) {
                        order(level, i, "Charge!");
                        WarHorn.signal(level, side[i], at(origin(i)), WarHorn.Signal.CHARGE);
                        Armies.cry(level, at(origin(i)), false);
                    }
                }
                case "clash" -> {
                    forward[0] = forward[1] = (Battlefields.GAP - 3) / 2.0;
                    stageAt(level, BattleModel.Phase.CLASH);
                }
                case "rout" -> {
                    forward[0] = forward[1] = (Battlefields.GAP - 3) / 2.0;
                    phase = BattleModel.Phase.CLASH;
                    for (int i = 0; i < 2; i++) array(level, side[i], origin(i), 0, this, i);
                    breakSide(level, 1, BattleModel.Outcome.ROUT);     // once: a battle staged on keeps the outcome it had
                    hold = BattleModel.Phase.PURSUIT;
                }
                case "aftermath" -> {
                    forward[0] = forward[1] = (Battlefields.GAP - 3) / 2.0;
                    if (outcome == null) breakSide(level, 1, BattleModel.Outcome.RETREAT);   // [war-polish] its one outcome
                    for (int i = 0; i < 2; i++) {
                        int n = 0;
                        for (Armies.Fighter f : side[i].fighters) {
                            if (n >= (i == winner ? 2 : 4) || f.rank != Armies.Rank.NONE || f.role == BattleModel.Role.MEDIC) continue;
                            if (level.getEntity(f.id) instanceof VillageFolkEntity e) Armies.yieldNow(e, f, null);
                            f.placed = true;
                            double[] o = origin(i);
                            f.place(level, o[0] + o[2] * 2 + -o[3] * (n * 2 - 3), o[1] + o[3] * 2 + o[2] * (n * 2 - 3), Armies.yawTo(o[2], o[3]));
                            n++;
                        }
                    }
                    stageAt(level, BattleModel.Phase.AFTERMATH);
                }
                default -> stageAt(level, BattleModel.Phase.FORM);
            }
            for (int i = 0; i < 2; i++) array(level, side[i], origin(i), 0, this, i);
            if (phase == BattleModel.Phase.PARLEY) parley(level, soon(PARLEY) / 4);
        }

        /** Set straight to this phase (the pictures), and held there. */
        void stageAt(ServerLevel level, BattleModel.Phase p) {
            hold = null;
            phase = p;
            phaseAt = level.getGameTime() - EVERY;
            hold = p;
        }

        // -------------------------------------------------------------- a player's horn

        /**
         * [WarHorn.blown] A player who commands side i sounds a call: the charge (from the volleys or the advance, straight
         * to the clash), the rally (heart, once in a while), the retreat in good order, or the lines dressed. What was
         * done, or null for a call that orders nothing now.
         */
        @Nullable
        String horn(ServerLevel level, int i, WarHorn.Signal s) {
            switch (s) {
                case CHARGE -> {
                    if (phase != BattleModel.Phase.VOLLEYS && phase != BattleModel.Phase.ADVANCE && phase != BattleModel.Phase.FORM) return null;
                    order(level, i, "Charge!");
                    shields(false);
                    to(level, BattleModel.Phase.CLASH);
                    return "charge!";
                }
                case RALLY -> {
                    long now = level.getGameTime();
                    if (now - rallied[i] < 300) return null;
                    rallied[i] = now;
                    side[i].morale = Math.min(100, side[i].morale + BattleModel.RALLY);
                    order(level, i, "To the banner! Rally!");
                    return "rallied";
                }
                case RETREAT -> {
                    if (phase.ordinal() >= BattleModel.Phase.PURSUIT.ordinal()) return null;
                    breakSide(level, i, BattleModel.Outcome.RETREAT);
                    return "falls back in good order";
                }
                case FORM -> {
                    ragged = 0.2;
                    order(level, i, "Dress the line!");
                    return "the lines dressed";
                }
                default -> {
                    return null;
                }
            }
        }

        // -------------------------------------------------------------- the lines alive

        /**
         * The lines before the clash are folk, not pieces: the ranks drift and a sergeant dresses them, one with its
         * nerves gone steps back and is pulled into line, shields are banged and a word passed to a neighbour, and
         * between the lines the taunts and songs fly (heard in the chat of anybody near). A beat of it a side, at most.
         */
        void life(ServerLevel level, long now) {
            ragged = Math.min(1.0, ragged + 0.012);
            for (int i = 0; i < 2; i++) {
                Armies.Army a = side[i];
                for (Armies.Fighter f : a.fighters) {
                    if (f.nerves <= 0) continue;
                    if (--f.nerves == 1) say(level, a.officer(Armies.Rank.SERGEANT), FolkTalk.pick(level.getRandom(), "You! Back in line!",
                        "Steady, lad — back where you belong.", "Nobody steps back. Nobody!"));
                }
                if (ragged > 0.85 && i == (int) (now / 20 % 2)) {
                    say(level, a.officer(Armies.Rank.SERGEANT), FolkTalk.pick(level.getRandom(), "Dress the line! Straighten up!", "Close up there — shoulder to shoulder!"));
                    if (i == 1) ragged = 0.2;
                }
                if (a.fighters.isEmpty() || level.getRandom().nextInt(4) != 0) continue;
                Armies.Fighter f = a.fighters.get(level.getRandom().nextInt(a.fighters.size()));
                if (f.down() || f.rank != Armies.Rank.NONE || !(level.getEntity(f.id) instanceof VillageFolkEntity e)) continue;
                switch (level.getRandom().nextInt(4)) {
                    case 0 -> {
                        if (e.getOffhandItem().getItem() instanceof ShieldItem) {
                            e.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                            level.playSound(null, e.blockPosition(), SoundEvents.SHIELD_BLOCK, SoundSource.NEUTRAL, 0.5F, 0.8F + level.getRandom().nextFloat() * 0.3F);
                        }
                    }
                    case 1 -> Armies.shout(e, FolkTalk.pick(e.getRandom(), "Stay by me.", "Look at the size of that one.", "My hands won't stop shaking.",
                        "Just like the drill. Just like the drill.", "Mind my back, I'll mind yours."));
                    case 2 -> { if (!f.veteran && f.power <= 8 && phase != BattleModel.Phase.CLASH) { f.nerves = 4; Armies.shout(e, "I— I can't…"); } }
                    default -> { if (phase == BattleModel.Phase.FORM || phase == BattleModel.Phase.PARLEY) taunt(level, i, e); }
                }
            }
        }

        /** A taunt, a jest or a song across the field, in the speaker's own town's words; heard in the chat of anybody near. */
        void taunt(ServerLevel level, int i, VillageFolkEntity e) {
            String us = name(i), them = name(1 - i);
            String motto = Heraldry.motto(side[i].town);
            String line = FolkTalk.pick(e.getRandom(),
                "Go home to your mothers, " + them + "!", them + "? I've seen fiercer chickens!", "Your banner'll look grand on our hall wall!",
                "We'll be home for supper. Will you?", "Is that a shield or a door you pulled off its hinges?", "Oi! Your Captain's helmet's on backwards!",
                "♪ Oh " + us + " stands, and " + us + " stays, through the longest of days ♪", "♪ Shields up, lads, and steady the line ♪",
                motto == null || motto.isEmpty() ? us + "! " + us + "!" : motto + "!");
            line = TownSpeech.local(e, line);
            Armies.shout(e, line);
            Raids.tellNear(level, e.blockPosition(), 48, Component.literal("[" + us + "'s line] " + line).withStyle(ChatFormatting.GRAY), false);
        }

        // -------------------------------------------------------------- the phases

        void form(ServerLevel level, long in) {
            if (in == EVERY) for (int i = 0; i < 2; i++) WarHorn.signal(level, side[i], at(origin(i)), WarHorn.Signal.FORM);
            for (int i = 0; i < 2; i++) array(level, side[i], i == 1 && assault && !gateDown ? behindTheGate() : origin(i), 0, this, i);
            if (in % 40 == 0) for (int i = 0; i < 2; i++) Armies.drum(level, at(origin(i)), in % 80 == 0);
            if (assault && !gateDown) {
                batter(level, in);
                return;
            }
            boolean ready = formed(level, 0) && formed(level, 1);
            if (ready || in >= soon(FORM)) {
                if (assault) { to(level, BattleModel.Phase.SPEECH); return; }
                to(level, BattleModel.Phase.PARLEY);
            }
        }

        /** Where the defenders stand while their gate holds: inside it, facing it. */
        double[] behindTheGate() {
            if (c.gate == null) return origin(1);
            BlockPos in = c.gate.relative(c.gateOut.getOpposite(), 5);
            return new double[]{ in.getX() + 0.5, in.getZ() + 0.5, c.gateOut.getStepX(), c.gateOut.getStepZ() };
        }

        /** The ram at the gate: a blow every two seconds, the gate down at the tenth. */
        void batter(ServerLevel level, long in) {
            if (c.gate == null) { gateDown = true; return; }
            if (in % 40 != 0) return;
            if (gateHits == 0) order(level, 0, "Bring up the ram! At the gate!");
            gateHits++;
            BlockPos g = c.gate;
            level.playSound(null, g, SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR, SoundSource.BLOCKS, 3.0F, 0.6F);
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.OAK_PLANKS.defaultBlockState()), g.getX() + 0.5, g.getY() + 1.0,
                g.getZ() + 0.5, 12, 0.4, 0.6, 0.4, 0.1);
            for (Armies.Fighter f : side[0].fighters) {
                if (f.role == BattleModel.Role.LINE && f.rank == Armies.Rank.NONE && level.getEntity(f.id) instanceof VillageFolkEntity e
                    && e.distanceToSqr(g.getX(), g.getY(), g.getZ()) < 64) e.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            }
            if (gateHits >= InventionArms.gateBlows(side[0].town, 10) || c.ram == null && gateHits >= 4) {   // [tech-tree] the iron-shod ram, the powder
                breakGate(level, c);
                gateDown = true;
                story.add("The ram broke in " + name(1) + "'s gate.");
                order(level, 1, "They're through the gate! Form up before it!");
                Raids.tellNear(level, centre, 200, Component.literal("[War] The gate of " + name(1) + " is down!").withStyle(ChatFormatting.RED), false);
            }
        }

        boolean formed(ServerLevel level, int i) {
            int seen = 0, in = 0;
            for (Armies.Fighter f : side[i].fighters) {
                if (f.down() || !(level.getEntity(f.id) instanceof VillageFolkEntity e)) continue;
                seen++;
                double dx = e.getX() - f.tx, dz = e.getZ() - f.tz;
                if (dx * dx + dz * dz < 2.5) in++;
            }
            return seen == 0 || in * 5 >= seen * 4;
        }

        void parley(ServerLevel level, long in) {
            Armies.Fighter[] caps = { side[0].officer(Armies.Rank.CAPTAIN), side[1].officer(Armies.Rank.CAPTAIN) };
            for (int i = 0; i < 2; i++) {
                array(level, side[i], origin(i), 0, this, i);
                Armies.Fighter cap = caps[i];
                if (cap == null || cap.down()) continue;
                double s = i == 0 ? -2.0 : 2.0;
                if (in < soon(PARLEY) - 120 || hold == BattleModel.Phase.PARLEY) cap.place(level, centre.getX() + 0.5 + ax * s, centre.getZ() + 0.5 + az * s, Armies.yawTo(-ax * Math.signum(s), -az * Math.signum(s)));
            }
            long half = soon(PARLEY) / 2 / EVERY * EVERY;
            if (in == half) {
                say(level, caps[0], terms(0));
                story.add("The Captains met between the lines; " + name(1) + " would not hear " + name(0) + "'s terms.");
            }
            if (in == half + 4 * EVERY) {
                int outcome = hold == null ? parleyTerms(level) : 0;     // [war-polish] staged: no surrender on a stage
                if (outcome == 0) say(level, caps[1], refusal());
                if (settled || withdrew) return;
            }
            if (in >= Math.max(soon(PARLEY), half + 6 * EVERY)) to(level, BattleModel.Phase.SPEECH);
        }

        void speech(ServerLevel level, long in) {
            for (int i = 0; i < 2; i++) array(level, side[i], origin(i), 0, this, i);
            if (in == EVERY) for (int i = 0; i < 2; i++) say(level, side[i].officer(Armies.Rank.CAPTAIN), rousing(i));
            if (in == Math.min(80, soon(SPEECH) - 2 * EVERY)) for (int i = 0; i < 2; i++) say(level, side[i].officer(Armies.Rank.CAPTAIN), FolkTalk.pick(level.getRandom(),
                "Mind your sergeants. Keep your places. Follow the banner!", "Shields together. Nobody runs. Nobody!", "For " + name(i) + "!"));
            if (in >= soon(SPEECH) && !held()) {
                sound(level);                                      // the beat of silence, then the horn and the battle cry
                story.add(name(0) + " " + side[0].tactic.words + "; " + name(1) + " " + side[1].tactic.words + ".");
                Raids.tellNear(level, centre, 240, Component.literal("[War] " + capital(c.name) + " is joined: " + story.get(story.size() - 1))
                    .withStyle(ChatFormatting.GOLD), false);
                to(level, assault ? BattleModel.Phase.ADVANCE : offerChampions(level) ? BattleModel.Phase.CHAMPIONS : BattleModel.Phase.VOLLEYS);
            }
        }

        /** The horn and each side's battle cry. */
        void sound(ServerLevel level) {
            for (int i = 0; i < 2; i++) {
                WarHorn.signal(level, side[i], at(origin(i)), WarHorn.Signal.BEGIN);
                Armies.cry(level, at(origin(i)), false);
                cheer(level, i, false);
            }
        }

        boolean offerChampions(ServerLevel level) {
            Random rng = new Random(Campaigns.seed(side[0].town, c.day + 5));
            int offer = BattleModel.offersChampion(side[0].boldness, rng) ? 0 : BattleModel.offersChampion(side[1].boldness, rng) ? 1 : -1;
            if (offer < 0 || !BattleModel.acceptsChampion(side[1 - offer].boldness)) return false;
            for (int i = 0; i < 2; i++) {
                Armies.Fighter best = null;
                for (Armies.Fighter f : side[i].fighters) {
                    if (f.down() || f.role != BattleModel.Role.LINE || f.rank == Armies.Rank.BEARER) continue;
                    if (best == null || f.power > best.power) best = f;
                }
                if (best == null) return false;
                champion[i] = best.id;
            }
            say(level, side[offer].officer(Armies.Rank.CAPTAIN), "Let it be settled man to man first! Send out your best!");
            say(level, side[1 - offer].officer(Armies.Rank.CAPTAIN), "Done. " + nameOf(champion[1 - offer]) + ", show them.");
            return true;
        }

        void champions(ServerLevel level, long in) {
            for (int i = 0; i < 2; i++) array(level, side[i], origin(i), 0, this, i);
            Armies.Fighter x = Armies.fighter(champion[0]), y = Armies.fighter(champion[1]);
            if (x == null || y == null) { to(level, BattleModel.Phase.VOLLEYS); return; }
            if (!x.down() && !y.down()) {
                x.order = Armies.Order.ENGAGE;
                x.foe = y.id;
                y.order = Armies.Order.ENGAGE;
                y.foe = x.id;
                if (in < soon(CHAMPIONS)) return;
            }
            int lost = x.down() ? 0 : y.down() ? 1 : -1;
            if (lost >= 0) {
                Armies.Army w = side[1 - lost];
                side[lost].morale -= BattleModel.CHAMPION_LOST;
                w.morale = Math.min(100, w.morale + BattleModel.CHAMPION_WON);
                Armies.Fighter won = lost == 0 ? y : x;
                won.downed += 2;                                         // a champion's victory is a brave man's
                story.add("Their champions met between the lines, and " + won.name + " of " + name(1 - lost) + " won.");
                cheer(level, 1 - lost, true);
            }
            for (Armies.Fighter f : new Armies.Fighter[]{ x, y }) if (!f.down()) { f.order = Armies.Order.PLACE; f.foe = null; }
            championsDone = true;
            to(level, BattleModel.Phase.VOLLEYS);
        }

        void archery(ServerLevel level, long now, long in) {
            for (int i = 0; i < 2; i++) array(level, side[i], origin(i), 0, this, i);
            shields(true);
            boolean any = side[0].count(BattleModel.Role.ARCHER) + side[1].count(BattleModel.Role.ARCHER) > 0;
            if (any && now - lastVolley >= VOLLEY - Armies.BOW_DRAW && now - lastVolley < VOLLEY) {
                for (int i = 0; i < 2; i++) if (volleys[i] < VOLLEYS || hold == BattleModel.Phase.VOLLEYS) drawBows(level, i);   // [war-bows] "Draw!"
            }
            if (any && now - lastVolley >= VOLLEY) {
                lastVolley = now;
                for (int i = 0; i < 2; i++) {
                    if (hold == BattleModel.Phase.VOLLEYS && volleys[i] >= VOLLEYS) volleys[i] = 0;   // staged: the volleys keep coming
                    if (volleys[i] < VOLLEYS) volley(level, i, false);
                }
            }
            if (!held() && (!any || volleys[0] >= VOLLEYS && volleys[1] >= VOLLEYS || in > VOLLEY * (VOLLEYS + 2))) {
                to(level, BattleModel.Phase.ADVANCE);
                for (int i = 0; i < 2; i++) if (!side[i].tactic.holds() || bothHold()) order(level, i, "Shields up! Advance!");
            }
        }

        boolean bothHold() {
            return side[0].tactic.holds() && side[1].tactic.holds();
        }

        /** Does side i come on (or wait for them)? Both waiting, the bolder comes on. */
        boolean comesOn(int i) {
            if (!side[i].tactic.holds()) return true;
            return bothHold() && side[i].boldness >= side[1 - i].boldness && (side[i].boldness > side[1 - i].boldness || i == 0);
        }

        void advance(ServerLevel level, long in) {
            if (charging) {                                        // [war-polish] staged: running in, held as they all but meet
                if (gap() > CHARGE_STOP) for (int i = 0; i < 2; i++) forward[i] += CHARGE_STEP / 2;
                for (int i = 0; i < 2; i++) array(level, side[i], origin(i), 0, this, i);
                return;
            }
            shields(true);
            for (int i = 0; i < 2; i++) if (comesOn(i)) forward[i] += 0.5;     // a steady walk, a block a second
            // A feigned retreat: as they come on, the line gives ground, then turns on them.
            for (int i = 0; i < 2; i++) {
                if (side[i].tactic == BattleModel.Tactic.FEIGNED_RETREAT && !fellBack[i] && gap() < 12) {
                    fellBack[i] = true;
                    forward[i] -= 6;
                    order(level, i, "Fall back! Fall back!");
                }
            }
            for (int i = 0; i < 2; i++) array(level, side[i], origin(i), 0, this, i);
            if (in % 60 == 0 && side[0].count(BattleModel.Role.ARCHER) + side[1].count(BattleModel.Role.ARCHER) > 0) {
                for (int i = 0; i < 2; i++) volley(level, i, false);
            } else if (in % 60 == 60 - Armies.BOW_DRAW) for (int i = 0; i < 2; i++) drawBows(level, i);   // [war-bows]
            if (!held() && (gap() <= 5 || in > 900)) {
                for (int i = 0; i < 2; i++) {
                    if (fellBack[i]) {
                        order(level, i, "Now! Turn and at them!");
                        side[1 - i].morale -= BattleModel.DRAWN_ON;
                        story.add(name(i) + " gave ground, drew " + name(1 - i) + " on, and turned on it.");
                    } else order(level, i, "Charge!");
                    WarHorn.signal(level, side[i], at(origin(i)), WarHorn.Signal.CHARGE);
                    Armies.cry(level, at(origin(i)), false);
                }
                shields(false);
                to(level, BattleModel.Phase.CLASH);
            }
        }

        double gap() {
            return Battlefields.GAP - forward[0] - forward[1];
        }

        void clash(ServerLevel level, long now, long in) {
            for (int i = 0; i < 2; i++) array(level, side[i], origin(i), 0, this, i);
            for (int i = 0; i < 2; i++) {
                Armies.Army us = side[i], them = side[1 - i];
                // The reserve: round the flank once the lines are locked, or into a gap when the line thins.
                boolean thin = us.count(BattleModel.Role.LINE) * 4 < lineSize(us) * 3;
                if (!reserveSent[i] && us.count(BattleModel.Role.RESERVE) + us.count(BattleModel.Role.RIDER) > 0 && (us.tactic.flanks() && in >= 200 || thin || in >= 600)) {
                    reserveSent[i] = true;
                    boolean left = level.getRandom().nextBoolean();
                    order(level, i, us.tactic == BattleModel.Tactic.CAVALRY ? "Riders! Round their " + (left ? "left" : "right") + " — now!"
                        : us.tactic.flanks() ? "Reserve, round their " + (left ? "left" : "right") + "!" : "Reserve, into the gap!");
                    if (us.tactic.flanks()) story.add(name(i) + " sent its reserve round " + name(1 - i) + "'s flank.");
                }
                engage(level, i, them);
                if (!flanked[1 - i] && reserveSent[i] && us.tactic.flanks() && reserveAtFlank(level, i)) {
                    flanked[1 - i] = true;
                    them.morale -= BattleModel.FLANKED;
                    order(level, 1 - i, "They're on our flank! Turn, turn!");
                }
            }
            if (now - lastVolley >= VOLLEY + 20) {
                lastVolley = now;
                for (int i = 0; i < 2; i++) volley(level, i, true);
            } else if (now - lastVolley >= VOLLEY) for (int i = 0; i < 2; i++) drawBows(level, i);   // [war-bows]
            for (int i = 0; i < 2; i++) tend(level, i, 10.0);        // the medic pulls back those down near its own lines
            sparks(level);
            if (held()) return;                                    // [war-polish] staged: the lines stay locked; nobody breaks on a stage
            for (int i = 0; i < 2; i++) {
                BattleModel.Outcome o = BattleModel.breaks(model(i), model(1 - i));
                if (o != null) {
                    breakSide(level, i, o);
                    return;
                }
            }
        }

        int lineSize(Armies.Army a) {
            int n = 0;
            for (Armies.Fighter f : a.fighters) if (f.role == BattleModel.Role.LINE) n++;
            return Math.max(1, n);
        }

        /** Each of side i's line (and its reserve once sent) at its man: the nearest of theirs on its feet; sergeant at sergeant. */
        void engage(ServerLevel level, int i, Armies.Army them) {
            List<Armies.Fighter> foes = new ArrayList<>();
            for (Armies.Fighter f : them.fighters) if (!f.down() && f.role != BattleModel.Role.MEDIC) foes.add(f);
            for (Armies.Fighter f : side[i].fighters) {
                if (f.down() || f.role == BattleModel.Role.MEDIC || f.role == BattleModel.Role.ARCHER || f.rank == Armies.Rank.BEARER) continue;
                if (f.role == BattleModel.Role.RESERVE && !reserveSent[i]) continue;
                if (f.role == BattleModel.Role.RIDER && side[i].tactic == BattleModel.Tactic.CAVALRY && !reserveSent[i]) continue;
                if (f.rank == Armies.Rank.CAPTAIN && side[i].count(BattleModel.Role.LINE) > 2) continue;
                Armies.Fighter cur = f.foe == null ? null : Armies.fighter(f.foe);
                if (cur != null && !cur.down() && level.getGameTime() % 100 != f.stagger * 10) continue;
                Entity me = level.getEntity(f.id);
                if (me == null) continue;
                Armies.Fighter best = null;
                double bd = 30 * 30;
                for (Armies.Fighter o : foes) {
                    Entity e = level.getEntity(o.id);
                    if (e == null) continue;
                    double d = e.distanceToSqr(me);
                    if (f.rank == Armies.Rank.SERGEANT && o.rank == Armies.Rank.SERGEANT) d *= 0.4;   // sergeant seeks sergeant
                    if (e instanceof LivingEntity l) d *= 0.6 + 0.4 * l.getHealth() / Math.max(1.0F, l.getMaxHealth());   // and the wounded, to finish
                    if (d < bd) { bd = d; best = o; }
                }
                if (best == null) continue;
                f.order = Armies.Order.ENGAGE;
                f.foe = best.id;
                f.speed = 1.25;
            }
        }

        boolean reserveAtFlank(ServerLevel level, int i) {
            double[] o = origin(1 - i);
            for (Armies.Fighter f : side[i].fighters) {
                if (f.role != BattleModel.Role.RESERVE && f.role != BattleModel.Role.RIDER || f.down() || !(level.getEntity(f.id) instanceof VillageFolkEntity e)) continue;
                double dx = e.getX() - o[0], dz = e.getZ() - o[1];
                if (dx * dx + dz * dz < 12 * 12) return true;
            }
            return false;
        }

        /** The sparks and the din of the clash: a few bursts a beat where pairs are at it. */
        void sparks(ServerLevel level) {
            int n = 0;
            for (Armies.Fighter f : side[0].fighters) {
                if (n >= SPARKS) break;
                if (f.order != Armies.Order.ENGAGE || f.foe == null || level.getRandom().nextInt(3) != 0) continue;
                Entity a = level.getEntity(f.id), b = level.getEntity(f.foe);
                if (a == null || b == null || a.distanceToSqr(b) > 9) continue;
                double x = (a.getX() + b.getX()) / 2, y = a.getY() + 1.2, z = (a.getZ() + b.getZ()) / 2;
                level.sendParticles(level.getRandom().nextBoolean() ? ParticleTypes.SWEEP_ATTACK : ParticleTypes.CRIT, x, y, z, 3, 0.3, 0.2, 0.3, 0.05);
                level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, level.getBlockState(a.blockPosition().below())), x, a.getY() + 0.1, z, 4,
                    0.4, 0.05, 0.4, 0.05);
                if (n == 0) level.playSound(null, x, y, z, level.getRandom().nextBoolean() ? SoundEvents.PLAYER_ATTACK_SWEEP : SoundEvents.SHIELD_BLOCK,
                    SoundSource.NEUTRAL, 1.2F, 0.9F + level.getRandom().nextFloat() * 0.2F);
                n++;
            }
        }

        /** A side breaks: in good order, in a rout, or on its knees; the other cheers; the pursuit. */
        void breakSide(ServerLevel level, int loser, BattleModel.Outcome o) {
            if (outcome != null) return;                           // [war-polish] one battle, one outcome
            winner = 1 - loser;
            outcome = o;
            Armies.Army l = side[loser];
            double[] home = origin(loser);
            double hx = -home[2], hz = -home[3];
            switch (o) {
                case RETREAT -> {
                    order(level, loser, "Fall back! In good order — fall back!");
                    forward[loser] -= 20;
                    for (Armies.Fighter f : l.fighters) if (f.order == Armies.Order.ENGAGE) { f.order = Armies.Order.PLACE; f.foe = null; f.speed = 0.6; }
                }
                case ROUT -> {
                    order(level, loser, "Run! Run for it!");
                    Random r = new Random(level.getGameTime());
                    for (Armies.Fighter f : l.fighters) {
                        if (f.down()) continue;
                        f.order = Armies.Order.ROUT;
                        f.foe = null;
                        f.speed = 1.3;
                        double across = (r.nextDouble() - 0.5) * 30;
                        f.place(level, home[0] + hx * (30 + r.nextInt(12)) - hz * across, home[1] + hz * (30 + r.nextInt(12)) + hx * across,
                            Armies.yawTo(hx, hz));
                    }
                }
                case SURRENDER -> {
                    order(level, loser, "Enough! We yield — we yield!");
                    for (Armies.Fighter f : l.fighters) {
                        if (f.down() || f.role == BattleModel.Role.MEDIC) continue;
                        if (level.getEntity(f.id) instanceof VillageFolkEntity e) Armies.yieldNow(e, f, null);
                        else f.order = Armies.Order.YIELDED;
                    }
                }
                default -> { }
            }
            for (Armies.Fighter f : side[winner].fighters) if (f.order == Armies.Order.ENGAGE) { f.order = Armies.Order.PLACE; f.foe = null; }
            cheer(level, winner, true);
            WarHorn.signal(level, side[winner], at(origin(winner)), WarHorn.Signal.VICTORY);
            WarHorn.signal(level, side[loser], at(origin(loser)), WarHorn.Signal.RETREAT);
            story.add(name(loser) + " " + o.words + "; " + name(winner) + " held the field.");
            Raids.tellNear(level, centre, ANNOUNCE, Component.literal("[War] " + capital(c.name) + ": " + story.get(story.size() - 1)).withStyle(ChatFormatting.GOLD), false);
            to(level, BattleModel.Phase.PURSUIT);
        }

        void pursuit(ServerLevel level, long in) {
            if (winner < 0) { to(level, BattleModel.Phase.AFTERMATH); return; }
            if (outcome == BattleModel.Outcome.RETREAT) array(level, side[1 - winner], origin(1 - winner), 0, this, 1 - winner);
            if (in < PURSUIT && outcome == BattleModel.Outcome.ROUT) {
                engage(level, winner, side[1 - winner]);
                for (Armies.Fighter f : side[winner].fighters) {
                    Armies.Fighter foe = f.foe == null ? null : Armies.fighter(f.foe);
                    if (foe != null && foe.order != Armies.Order.ROUT) { f.order = Armies.Order.PLACE; f.foe = null; }
                }
                return;
            }
            if (in < PURSUIT || held()) return;
            order(level, winner, "Hold! Let them go! Back to the banner!");
            for (Armies.Fighter f : side[winner].fighters) if (f.order == Armies.Order.ENGAGE) { f.order = Armies.Order.PLACE; f.foe = null; }
            to(level, BattleModel.Phase.AFTERMATH);
        }

        /** The medics at their work, the captives taken, the fallen banner taken up; then the battle is over. */
        void aftermath(ServerLevel level, long in) {
            for (int i = 0; i < 2; i++) if (i == winner || winner < 0) array(level, side[i], origin(i), 0, this, i);
            for (int i = 0; i < 2; i++) tend(level, i, 40.0);
            if (in == EVERY) takeCaptives(level);
            if (in < soon(AFTERMATH) || held()) return;             // [war-polish] staged: the field stays as it is
            for (int i = 0; i < 2; i++) bannerHome(level, i);
            gather();
            over = true;
            close();
        }

        /**
         * [war-bows] The spent arrows gathered off the field by the side that holds it: a third of all loosed there, both
         * sides' (the rest broken, lost or trodden in), into its supply to go home to its stores. Never more than were
         * loosed; once.
         */
        void gather() {
            if (gathered >= 0) return;
            gathered = 0;
            if (winner < 0 || side[winner] == null) return;
            gathered = BattleModel.recovered(spent[0] + spent[1]);
            if (gathered <= 0) return;
            side[winner].supply.merge(Items.ARROW, gathered, Integer::sum);
            story.add(name(winner) + " gathered " + gathered + " arrows off the field.");
        }

        /**
         * Side i's medic to its nearest wounded within {@code reach} of its own lines (in the clash, only those it can
         * get to without walking into the fight), and the wound bound (a bandage out of its pack): out of the fight, but
         * on its feet and carried home with its own rather than taken.
         */
        void tend(ServerLevel level, int i, double reach) {
            Armies.Army a = side[i];
            if (a.medic == null || !(level.getEntity(a.medic) instanceof VillageFolkEntity medic)) return;
            Armies.Fighter m = Armies.fighter(a.medic);
            if (m == null) return;
            VillageFolkEntity best = null;
            Armies.Fighter bestF = null;
            double bd = 40 * 40;
            double[] o = origin(i);
            for (Armies.Fighter f : a.fighters) {
                if (f.order != Armies.Order.YIELDED || f.tended || f.dead || !(level.getEntity(f.id) instanceof VillageFolkEntity e)) continue;
                double ox = e.getX() - (o[0] - o[2] * 6), oz = e.getZ() - (o[1] - o[3] * 6);
                if (ox * ox + oz * oz > reach * reach) continue;
                double d = e.distanceToSqr(medic);
                if (d < bd) { bd = d; best = e; bestF = f; }
            }
            if (best == null) return;
            m.place(level, best.getX(), best.getZ(), m.yaw);
            m.speed = 1.0;
            if (bd > 4) return;
            boolean bound = medic.removeMatching(Kitchen.BANDAGE, 1) == 1;
            best.heal(bound ? 8.0F : 2.0F);
            medic.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            Armies.shout(medic, FolkTalk.pick(medic.getRandom(), "Hold still — I've got you.", "Easy now. You'll mend.", "There. On your feet, slowly."));
            bestF.tended = true;                                    // out of the fight, on its feet, back with the medic
            bestF.place(level, o[0] - o[2] * 14 + o[3] * (bestF.stagger - 5) * 0.8, o[1] - o[3] * 14 - o[2] * (bestF.stagger - 5) * 0.8,
                Armies.yawTo(o[2], o[3]));
            bestF.speed = 0.6;
        }

        /** The winner leads off the loser's wounded left on the field: captives, on leads where it brought them. */
        void takeCaptives(ServerLevel level) {
            if (winner < 0) return;
            Armies.Army w = side[winner], l = side[1 - winner];
            List<Armies.Fighter> captors = new ArrayList<>();
            for (Armies.Fighter f : w.fighters) if (!f.down() && f.role == BattleModel.Role.LINE) captors.add(f);
            long day = level.getDayTime() / 24000L;
            int n = 0;
            for (Armies.Fighter f : l.fighters) {
                if (f.order != Armies.Order.YIELDED || f.dead || f.tended) continue;     // the medic's are carried off with their own
                if (n >= CAPTIVES_MOST || captors.isEmpty()) break;
                Armies.Fighter by = captors.remove(0);
                capture(level, f, by, w, day);
                n++;
            }
        }

        /** The fallen banner: back in its bearer's hand if its side held the field, else carried off by the winner as a trophy. */
        void bannerHome(ServerLevel level, int i) {
            if (fallenBanner[i] == null) return;
            Entity e = level.getEntity(fallenBanner[i]);
            if (e != null) e.discard();
            ItemStack cloth = fallenCloth[i];
            fallenBanner[i] = null;
            fallenCloth[i] = ItemStack.EMPTY;
            if (cloth.isEmpty()) return;
            if (winner == 1 - i) {
                cloth.set(net.minecraft.core.component.DataComponents.ITEM_NAME, Component.literal("Banner of " + name(i) + ", taken at " + capital(c.name)));
                Villages.Village v = Villages.get(side[1 - i].town);
                if (v != null) TownWork.give(level, v, Arms.unlend(cloth));
                story.add(name(1 - i) + " carried off " + name(i) + "'s banner.");
                return;
            }
            Armies.Fighter bearer = side[i].bearer == null ? null : Armies.fighter(side[i].bearer);
            if (bearer != null && level.getEntity(bearer.id) instanceof VillageFolkEntity b && b.getMainHandItem().isEmpty()) {
                b.setItemSlot(EquipmentSlot.MAINHAND, cloth);
            } else {
                Villages.Village v = Villages.get(side[i].town);
                if (v != null) TownWork.give(level, v, Arms.unlend(cloth));
            }
        }

        // -------------------------------------------------------------- spirit, orders, the bar

        BattleModel.Side model(int i) {
            Armies.Army a = side[i];
            BattleModel.Side s = new BattleModel.Side(name(i), a.size(), a.power(), a.count(BattleModel.Role.ARCHER), a.morale, a.tactic);
            s.yielded = a.size() - a.standing() - a.behind;
            s.captainDown = captainDown[i];
            s.formation = a.formation;
            InventionArms.tally(a, s, null);                                   // [tech-tree] its pikes, hammers, longbows, arbalests
            return s;
        }

        /** Once a second: each side's spirit, from what befell it since. */
        void spirit(ServerLevel level, long now) {
            for (int i = 0; i < 2; i++) {
                Armies.Army a = side[i];
                int newly = down[i] - yieldedSeen[i];
                yieldedSeen[i] = down[i];
                int shake = BattleModel.lossShock(newly, a.size());
                if (phase == BattleModel.Phase.CLASH) shake += BattleModel.outnumbered(a.standing() + a.behind, side[1 - i].standing() + side[1 - i].behind);
                a.morale = Math.max(0, Math.min(100, a.morale - shake));
                Armies.Fighter cap = a.officer(Armies.Rank.CAPTAIN);
                if (cap != null && !cap.down() && a.morale < BattleModel.WAVER && a.morale >= BattleModel.BREAK && now - rallied[i] > 300) {
                    rallied[i] = now;
                    a.morale += BattleModel.RALLY;
                    WarHorn.signal(level, a, at(origin(i)), WarHorn.Signal.RALLY);
                    order(level, i, FolkTalk.pick(level.getRandom(), "Hold the line! Hold!", "Stand fast, " + name(i) + "! Stand!", "To me! To the banner!"));
                }
            }
        }

        /** The bar at the top of the screen, for every player near the field: each side's name, strength and spirit. */
        void bars(ServerLevel level) {
            for (int i = 0; i < 2; i++) {
                Armies.Army a = side[i];
                if (bars[i] == null) {
                    WarDress.Tint[] t = WarDress.tints(c);             // [war-polish] the bar in the army's own colour
                    bars[i] = new ServerBossEvent(Component.empty(), WarDress.bar(t[i], i == 1 && bars[0] != null ? bars[0].getColor() : null),
                        BossEvent.BossBarOverlay.NOTCHED_10);
                }
                bars[i].setName(Component.literal(name(i) + " — " + (a.standing() + a.behind) + " standing, " + Campaigns.spirit(a)
                    + (captainDown[i] ? ", Captain down" : "") + (bannerLost[i] ? ", banner lost" : "")));
                bars[i].setProgress(Math.max(0.0F, Math.min(1.0F, a.morale / 100.0F)));
                double r = com.jrpetty.mcassistant.AssistantConfig.battleView();
                for (ServerPlayer p : level.players()) {
                    boolean near = p.distanceToSqr(centre.getX(), p.getY(), centre.getZ()) <= r * r;
                    if (near && !bars[i].getPlayers().contains(p)) bars[i].addPlayer(p);
                    else if (!near && bars[i].getPlayers().contains(p)) bars[i].removePlayer(p);
                }
            }
        }

        void close() {
            for (ServerBossEvent b : bars) if (b != null) b.removeAllPlayers();
        }

        /** The battle given up (peace made in the middle of it): the bars down, the banners back. */
        void abandon(ServerLevel level) {
            for (int i = 0; i < 2; i++) if (side[i] != null) bannerHome(level, i);
            close();
            over = true;
        }

        /** An order: shouted by the Captain (else a sergeant), and over the hotbar of everybody near the field. */
        void order(ServerLevel level, int i, String words) {
            Armies.Army a = side[i];
            if (a == null) return;
            Armies.Fighter by = a.officer(Armies.Rank.CAPTAIN);
            if (by == null || by.down()) by = a.officer(Armies.Rank.SERGEANT);
            if (by != null && level.getEntity(by.id) instanceof VillageFolkEntity e) Armies.shout(e, words);
            Raids.tellNear(level, centre, (int) com.jrpetty.mcassistant.AssistantConfig.battleView(),
                Component.literal(name(i) + ": " + words).withStyle(i == 0 ? ChatFormatting.AQUA : ChatFormatting.RED), true);
        }

        void say(ServerLevel level, @Nullable Armies.Fighter f, String words) {
            if (f != null && !f.down() && level.getEntity(f.id) instanceof VillageFolkEntity e) Armies.shout(e, words);
        }

        void cheer(ServerLevel level, int i, boolean cheer) {
            int n = 0;
            for (Armies.Fighter f : side[i].fighters) {
                if (f.down() || n >= 5 || !(level.getEntity(f.id) instanceof VillageFolkEntity e)) continue;
                if (level.getRandom().nextInt(2) != 0) continue;
                e.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                Armies.shout(e, cheer ? FolkTalk.pick(e.getRandom(), "Hurrah!", "We've done it!", name(i) + "! " + name(i) + "!")
                    : FolkTalk.pick(e.getRandom(), "For " + name(i) + "!", "Come on, then!", "Stand together!"));
                n++;
            }
            Armies.cry(level, at(origin(i)), cheer);
        }

        void shields(boolean up) {
            for (int i = 0; i < 2; i++) for (Armies.Fighter f : side[i].fighters) f.shieldUp = up && f.role == BattleModel.Role.LINE && !f.down();
        }

        /** [war-bows] Side i's bowmen draw for the coming volley (their bows bent, the pose that says a volley is coming). */
        void drawBows(ServerLevel level, int i) {
            for (Armies.Fighter f : side[i].fighters) {
                if (f.role == BattleModel.Role.ARCHER && !f.crossbow && !f.down() && level.getEntity(f.id) instanceof VillageFolkEntity e) Armies.draw(e);
            }
        }

        /**
         * [war-bows] Side i's crossbow company, a beat: every crossbow spanning counts down to loaded (one whose quiver is
         * empty draws its blade and joins the reserve); then, every few seconds while it may shoot, the spanned front rank
         * looses together at the enemy's line, flat and hard ("Crossbows, loose!"), and the ranks change places (the
         * counter-march): it falls back a step to kneel and reload ("Reload!") while the rank behind steps up spanned. On a
         * town wall in an assault every spanned crossbow looses from the parapet.
         */
        void crossbows(ServerLevel level, int i, long now) {
            Armies.Army us = side[i];
            if (us == null) return;
            boolean onWall = onWall(i);
            List<Armies.Fighter> ready = new ArrayList<>();
            int company = 0, front = 0;
            for (Armies.Fighter f : us.fighters) if (f.crossbow() && !f.down() && f.file == 0) front++;
            if (front == 0 || front == us.missiles(true)) {
                // One rank only (the books do not keep the ranks over a restart, or the other rank fell): told off afresh by turns.
                int k = 0;
                for (Armies.Fighter f : us.fighters) if (f.crossbow() && !f.down()) f.file = k++ % 2;
            }
            for (Armies.Fighter f : us.fighters) {
                if (!f.crossbow() || f.down() || !(level.getEntity(f.id) instanceof VillageFolkEntity e)) continue;
                company++;
                Armies.arm(e, f);                                      // its crossbow in its hand to span
                if (!Armies.spanning(e, f, EVERY)) {
                    Armies.outOfArrows(e, f);
                    continue;
                }
                if (f.reload <= 0 && net.minecraft.world.item.CrossbowItem.isCharged(e.getMainHandItem()) && (onWall || f.file == 0)) ready.add(f);
            }
            if (company == 0 || ready.isEmpty() || now - lastBolt[i] < BOLT_EVERY || !boltsFly(i, onWall)) return;
            List<LivingEntity> marks = new ArrayList<>();
            for (Armies.Fighter f : side[1 - i].fighters) {
                if (f.down() || f.role == BattleModel.Role.MEDIC) continue;
                if (!onWall && f.role != BattleModel.Role.LINE) continue;              // at their line: flat shots hit what is in front
                if (level.getEntity(f.id) instanceof LivingEntity l) marks.add(l);
            }
            if (marks.isEmpty()) return;
            List<UUID> who = new ArrayList<>();
            for (Armies.Fighter f : ready) {
                if (!(level.getEntity(f.id) instanceof VillageFolkEntity e)) continue;
                LivingEntity m = nearest(e, marks);
                if (m != null && Armies.bolt(level, e, m)) {
                    who.add(f.id);
                    f.loosed++;
                    spent[i]++;
                    Armies.span(e, f);                                     // straight to spanning the next
                }
            }
            if (who.isEmpty()) return;
            lastBolt[i] = now;
            volleys[i]++;
            List<List<UUID>> log = bolted(i);
            log.add(who);
            if (log.size() > BOLT_LOG) log.remove(0);
            order(level, i, onWall ? "From the wall — crossbows, loose!" : "Crossbows, loose!");
            if (!onWall) {
                for (Armies.Fighter f : us.fighters) if (f.crossbow()) f.file = 1 - f.file;    // the counter-march
                say(level, Armies.fighter(who.get(0)), "Reload!");
            }
        }

        /** [war-bows] May side i's crossbows shoot now: from a wall in an assault; in the field, in the volleys and on the advance till the lines close. */
        boolean boltsFly(int i, boolean onWall) {
            if (onWall) return phase.ordinal() >= BattleModel.Phase.FORM.ordinal() && phase.ordinal() <= BattleModel.Phase.CLASH.ordinal();
            return phase == BattleModel.Phase.VOLLEYS || phase == BattleModel.Phase.ADVANCE && !charging && gap() > BOLT_RETIRE;
        }

        /** [war-bows] Side i's crossbows stand on its town's wall: the defenders of an assault, the gate still shut, a wall to stand on. */
        boolean onWall(int i) {
            return i == 1 && assault && !gateDown && wall != null && !wall.isEmpty();
        }

        @Nullable
        static LivingEntity nearest(Entity from, List<LivingEntity> marks) {
            LivingEntity best = null;
            double bd = Double.MAX_VALUE;
            for (LivingEntity l : marks) {
                double d = l.distanceToSqr(from);
                if (d < bd) { bd = d; best = l; }
            }
            return best;
        }

        /** A volley from side i's archers: each at one of theirs (the line, or over it at the back once the lines meet). */
        void volley(ServerLevel level, int i, boolean over) {
            List<Entity> marks = new ArrayList<>();
            for (Armies.Fighter f : side[1 - i].fighters) {
                if (f.down() || f.role == BattleModel.Role.MEDIC) continue;
                if (over == (f.role == BattleModel.Role.LINE)) continue;
                Entity e = level.getEntity(f.id);
                if (e != null) marks.add(e);
            }
            if (marks.isEmpty()) return;
            int shot = 0;
            for (Armies.Fighter f : side[i].fighters) {
                if (f.role != BattleModel.Role.ARCHER || f.crossbow || f.down() || !(level.getEntity(f.id) instanceof VillageFolkEntity archer)) continue;
                Armies.arm(archer, f);                                 // [war-bows] its bow in its hand to loose
                Entity m = marks.get(level.getRandom().nextInt(marks.size()));
                if (Armies.loose(level, archer, m.getX(), m.getY(), m.getZ(), 5.0F)) {
                    shot++;
                    f.loosed++;
                    spent[i]++;
                }
                if (archer.countMatching(s -> s.is(Items.ARROW)) == 0) Armies.outOfArrows(archer, f);   // [war-bows] blades, and into the reserve
            }
            if (shot == 0) return;
            volleys[i]++;
            if (volleys[i] == 1 || level.getRandom().nextInt(3) == 0) order(level, i, over ? "Over their heads — loose!" : "Archers, loose!");
            level.playSound(null, at(origin(i)), SoundEvents.ARROW_SHOOT, SoundSource.NEUTRAL, 2.0F, 0.8F);
        }

        // -------------------------------------------------------------- the parley's last word

        /**
         * At the parley: does the side much the weaker (under BattleModel.HOPELESS, allies counted) offer to yield,
         * by its Captain's boldness and its town's pride; and does the other take it, by its temper, what it holds
         * against them (blood, a broken treaty) and its ambition? Taken: peace there and then on the stronger's
         * terms, the armies home, and the mercy remembered by every town. Refused: the battle (or, for a defender with
         * a wall to go to, the walls: a siege), and the refusal remembered too. 0 nobody offered; 1 taken; 2 refused.
         */
        int parleyTerms(ServerLevel level) {
            int weak = side[0].power() <= side[1].power() ? 0 : 1, strong = 1 - weak;
            double odds = side[weak].power() / (double) Math.max(1, side[strong].power());
            UUID w = side[weak].town, s = side[strong].town;
            long day = level.getDayTime() / 24000L;
            Random rng = new Random(Campaigns.seed(w, day * 13 + 5));
            if (parleyForTests == null && !BattleModel.offersSurrender(odds, side[weak].boldness, WarAndPeace.great(w), rng)) return 0;
            Armies.Fighter capW = side[weak].officer(Armies.Rank.CAPTAIN), capS = side[strong].officer(Armies.Rank.CAPTAIN);
            say(level, capW, "We'll not throw lives away for nothing. Name your terms.");
            boolean vengeful = WarAndPeace.brokenFaith(s, w, day);
            for (WarBooks.Grievance g : WarBooks.wrongs(s, w)) if (g.kind() == WarAndPeace.Wrong.BLOOD) vengeful = true;
            WarBooks.Book book = WarBooks.book(s, w);
            boolean ambitious = book != null && book.aggressor && (book.goal == WarAndPeace.Goal.SUBJUGATE || WarAndPeace.great(s));
            boolean kindly = Envoys.temper(s).kindly();
            if (parleyForTests != null ? parleyForTests : BattleModel.acceptsSurrender(side[strong].boldness, kindly, vengeful, ambitious, rng)) {
                say(level, capS, "Then lay down your arms and go home. The terms will be kept.");
                story.add(name(weak) + " yielded at the parley, and " + name(strong) + " took its surrender: not a blow struck.");
                settled = true;
                winner = strong;
                outcome = BattleModel.Outcome.SURRENDER;
                Campaigns.yieldedAtParley(level, c, s, w, c.ground.name());
                over = true;
                close();
                return 1;
            }
            say(level, capS, vengeful ? "No. You'll answer for what you did. Form up!" : "Too late for that. Form up, and fight.");
            story.add(name(weak) + " offered to yield at the parley; " + name(strong) + " would not have it.");
            Campaigns.refusedAtParley(level, c, s, w);
            if (weak == 1 && !assault && Campaigns.walled(w)) {
                withdrew = true;
                winner = strong;
                outcome = BattleModel.Outcome.RETREAT;
                story.add(name(weak) + " drew back behind its walls rather than fight in the open.");
                over = true;
                close();
            }
            return 2;
        }

        // -------------------------------------------------------------- on paper

        /**
         * The battle reckoned on paper (nobody watching, or the battle in full gone wrong): the model run from where
         * the two sides stand, and its result laid on the fighters: who yielded, who (rarely) fell, who is taken.
         */
        void onPaper(ServerLevel level) {
            if (over) return;
            close();
            if (side[0] == null || side[1] == null) {
                winner = side[0] != null ? 0 : 1;
                outcome = BattleModel.Outcome.RETREAT;
                over = true;
                return;
            }
            if (!assault && parleyTerms(level) != 0 && (settled || withdrew)) return;
            Random rng = new Random(Campaigns.seed(side[0].town, c.day * 31 + level.getGameTime()));
            BattleModel.Side x = model(0), y = model(1);
            int was0 = x.yielded, was1 = y.yielded;
            if (assault) y.morale = Math.min(100, y.morale + InventionArms.gateHeart(side[0].town, 10));   // behind its own gate; [tech-tree] less against engines
            BattleModel.Result r = BattleModel.resolve(x, y, side[0].boldness, side[1].boldness, best(0), best(1), Campaigns.deathPercent(), rng);
            paper = r;
            winner = r.winner();
            outcome = r.outcome();
            story.addAll(r.story());
            long day = level.getDayTime() / 24000L;
            lay(level, 0, r.yieldedA() - was0, r.deadA(), x.captainDown);
            lay(level, 1, r.yieldedB() - was1, r.deadB(), y.captainDown);
            side[0].morale = x.morale;
            side[1].morale = y.morale;
            if (winner >= 0) {
                Armies.Army w = side[winner], l = side[1 - winner];
                List<Armies.Fighter> captors = new ArrayList<>();
                for (Armies.Fighter f : w.fighters) if (!f.down() && f.role != BattleModel.Role.MEDIC) captors.add(f);
                int n = 0, k = 0;
                for (Armies.Fighter f : l.fighters) {
                    if (f.order != Armies.Order.YIELDED || f.dead) continue;
                    boolean taken = outcome != BattleModel.Outcome.RETREAT || k++ % 2 == 0;   // a retreat carries half its wounded off
                    if (!taken || n >= CAPTIVES_MOST) continue;
                    capture(level, f, captors.isEmpty() ? null : captors.get(n % captors.size()), w, day);
                    n++;
                }
            }
            // The rest of the wounded bound and on their feet, to march home with their own.
            for (Armies.Army a : side) for (Armies.Fighter f : a.fighters) if (f.order == Armies.Order.YIELDED && !f.dead) f.order = Armies.Order.PLACE;
            over = true;
        }

        /** The worth of side i's best in the line (its champion, if it comes to that). */
        int best(int i) {
            int b = 0;
            for (Armies.Fighter f : side[i].fighters) if (!f.down() && f.role == BattleModel.Role.LINE) b = Math.max(b, f.power);
            return b;
        }

        /** So many more of side i beaten down on paper (the line first, the Captain if it fell), so many of them dead. */
        void lay(ServerLevel level, int i, int n, int dead, boolean captainDown) {
            List<Armies.Fighter> order = new ArrayList<>();
            for (Armies.Fighter f : side[i].fighters) if (!f.down() && f.role != BattleModel.Role.MEDIC) order.add(f);
            order.sort(Comparator.comparingInt(f -> f.rank == Armies.Rank.CAPTAIN ? (captainDown ? -1 : 9)
                : f.role == BattleModel.Role.LINE ? 1 : f.role == BattleModel.Role.RESERVE ? 2 : f.role == BattleModel.Role.ARCHER ? 3 : 4));
            for (int k = 0; k < Math.min(n, order.size()); k++) {
                Armies.Fighter f = order.get(k);
                f.order = Armies.Order.YIELDED;
                down[i]++;
                Entity e = level.getEntity(f.id);
                if (k < dead) {
                    f.dead = true;
                    if (e instanceof VillageFolkEntity v) dies(v, f);
                } else if (e instanceof VillageFolkEntity v && v.getHealth() > Armies.YIELD_HEALTH) {
                    v.setHealth(Armies.YIELD_HEALTH);
                }
            }
        }

        String terms(int i) {
            WarBooks.Book b = WarBooks.book(side[i].town, side[1 - i].town);
            String want = b == null || b.goalText.isEmpty() ? "your word you'll trouble us no more" : WarAndPeace.words(b.goalText);
            return "Last terms: " + want + ". Yield now, and nobody need bleed today.";
        }

        String refusal() {
            return FolkTalk.pick(net.minecraft.util.RandomSource.create(c.day), name(1) + " yields nothing to " + name(0) + ". Go home, or come and take it.",
                "You'll have nothing from us but what's on the end of a blade.", "No. We'll see whose banner is standing tonight.");
        }

        String rousing(int i) {
            boolean attacking = i == 0;
            String n = name(i);
            return attacking ? n + "! They would not give us what is owed. Today we take it — follow the banner!"
                : "They've come for what's ours, " + n + ". They'll not have it. Shields together!";
        }
    }

    // ------------------------------------------------------------------ opening a battle

    /**
     * A battle opened on this ground: in full if anybody watches it (the stragglers called up), else reckoned on
     * paper there and then.
     */
    static Battle open(ServerLevel level, Campaigns.Campaign c, BlockPos centre, double ax, double az, boolean assault) {
        boolean watched = Campaigns.watched(level, centre) && c.armies[0] != null && c.armies[1] != null;
        Battle b = new Battle(c, centre, ax, az, assault, watched);
        b.began = b.phaseAt = level.getGameTime();
        b.ragged = 0.6 - 0.35 * Math.min(drilled(c.armies[0]), drilled(c.armies[1]));   // a drilled army draws up the straighter
        if (!watched) {
            b.onPaper(level);
            return b;
        }
        for (int i = 0; i < 2; i++) {
            Campaigns.pull(level, c, c.armies[i], centre);
            for (Armies.Fighter f : c.armies[i].fighters) {
                if (f.order == Armies.Order.ROUT) f.order = Armies.Order.PLACE;
                if (level.getEntity(f.id) instanceof VillageFolkEntity e) Armies.arm(e, f);
            }
        }
        Raids.tellNear(level, centre, 320, Component.literal("[War] The armies of " + Villages.name(c.a) + " and " + Villages.name(c.b)
            + " are forming up for battle on " + c.ground.name() + ".").withStyle(ChatFormatting.GOLD), false);
        LOG.info("[MCA-WAR] {} fought in full at {}", c.name, centre.toShortString());
        return b;
    }

    /**
     * How drilled an army is, 0 to 1: its veterans, and its militia's turns at the training yard (ten turns, as good
     * as a battle). A drilled army forms faster and straighter.
     */
    static double drilled(@Nullable Armies.Army a) {
        if (a == null || a.fighters.isEmpty()) return 0;
        java.util.Map<UUID, Militia.Member> m = Militia.members(a.town);
        double sum = 0;
        for (Armies.Fighter f : a.fighters) {
            Militia.Member mm = m.get(f.id);
            sum += f.veteran ? 1.0 : mm == null ? 0.5 : Math.min(1.0, mm.drills() / 10.0);   // the watch drills daily
        }
        return sum / a.fighters.size();
    }

    /**
     * Pictures: a battle opened in full whoever is watching (Campaigns.stage), its fighters armed for the field.
     */
    static Battle openStaged(ServerLevel level, Campaigns.Campaign c, BlockPos centre, double ax, double az) {
        Battle b = new Battle(c, centre, ax, az, false, true);
        b.began = b.phaseAt = level.getGameTime();
        b.ragged = 0.35;
        for (int i = 0; i < 2; i++) {
            for (Armies.Fighter f : c.armies[i].fighters) if (level.getEntity(f.id) instanceof VillageFolkEntity e) Armies.arm(e, f);
        }
        return b;
    }

    /** [war-polish] Tests: side i of the campaign's battle drawn up in this formation, its marks set now. */
    public static void formForTests(ServerLevel level, Campaigns.Campaign c, int i, BattleModel.Formation f) {
        Battle b = c.battle;
        if (b == null || b.side[i] == null) return;
        b.side[i].formation = f;
        array(level, b.side[i], b.origin(i), 0, b, i);
    }

    /** [war-polish] Tests: where side i's line draws up and the way it faces, {x, z, fx, fz}. */
    public static double[] originForTests(Campaigns.Campaign c, int i) {
        return c.battle == null ? new double[]{ 0, 0, 1, 0 } : c.battle.origin(i);
    }

    /** [war-polish] Tests: the war's course as written in this town's books, every entry (not the deduplicated reading). */
    public static List<String> courseWrittenForTests(UUID us, UUID them) {
        return WarBooks.list(us, "wp.course/" + them);
    }

    /** Wait so long, or less for the tests. */
    static long soon(long t) {
        return Campaigns.soonBattle(t);
    }

    // ------------------------------------------------------------------ formation

    /** The column's order: the banner and the Captain first, the line, the archers, the reserve, the medic, the captives. */
    static List<Armies.Fighter> columnOrder(Armies.Army a) {
        List<Armies.Fighter> out = new ArrayList<>(a.fighters);
        out.removeIf(f -> f.dead);
        out.sort(Comparator.comparingInt(Battles::columnRank));
        return out;
    }

    static int columnRank(Armies.Fighter f) {
        if (f.order == Armies.Order.CAPTIVE) return 9;
        if (f.rank == Armies.Rank.BEARER) return 0;
        if (f.rank == Armies.Rank.CAPTAIN) return 1;
        return switch (f.role) {
            case LINE -> f.rank == Armies.Rank.SERGEANT ? 2 : 3;
            case ARCHER -> 4;
            case RIDER -> 5;
            case RESERVE -> 6;
            case MEDIC -> 8;
        };
    }

    /** The muster's array on the square (Campaigns): as for battle, at {@code origin} {x, z, fx, fz}. */
    static void array(ServerLevel level, Armies.Army a, double[] origin, double forward) {
        array(level, a, origin, forward, null, 0);
    }

    /**
     * Every fighter of the army its place in its lines, at {@code origin} {x, z, fx, fz} (where the front rank's
     * middle stands, and the way it faces). [war-polish] Ranks and files, front to back: the line in its formation (on
     * parade one rank, two past a dozen, a block and a half apart, the sergeants at its ends; in the field the shields
     * in the front rank, the rest behind them); the archers behind it (four at least; on the wings, in front, inside
     * the ring or on the rise as the formation and the tactic have it); the Captain with the standard behind the
     * centre (ten at least); the reserve behind them (out on the flank behind its cover for a hidden one); the medic
     * last. Those engaged, down, routed or taken keep to what they are doing.
     */
    static void array(ServerLevel level, Armies.Army a, double[] origin, double forward, @Nullable Battle b, int side) {
        double fx = origin[2], fz = origin[3], rx = -fz, rz = fx;
        double ox = origin[0] + fx * forward, oz = origin[1] + fz * forward;
        float yaw = Armies.yawTo(fx, fz);
        List<Armies.Fighter> line = new ArrayList<>(), archers = new ArrayList<>(), reserve = new ArrayList<>(), riders = new ArrayList<>();
        List<Armies.Fighter> bolts = new ArrayList<>();                    // [war-bows] the crossbow company
        Armies.Fighter captain = null, bearer = null, medic = null;
        for (Armies.Fighter f : a.fighters) {
            if (f.down() || f.order == Armies.Order.ENGAGE || f.order == Armies.Order.ROUT) continue;
            if (f.rank == Armies.Rank.CAPTAIN) { captain = f; continue; }
            if (f.rank == Armies.Rank.BEARER) { bearer = f; continue; }
            switch (f.role) {
                case MEDIC -> medic = f;
                case ARCHER -> (f.crossbow ? bolts : archers).add(f);
                case RESERVE -> reserve.add(f);
                case RIDER -> riders.add(f);
                default -> line.add(f);
            }
            f.speed = b != null && b.phase == BattleModel.Phase.ADVANCE ? (b.charging ? CHARGE_SPEED : 0.55) : 0.75;
        }
        double back = b == null ? rank(level, paradeOrder(line), ox, oz, fx, fz, 0, 1.5, 12, yaw)     // on parade: straight ranks
            : shape(level, lineOrder(level, line, a.formation), a.formation, ox, oz, fx, fz, yaw, b, side);
        // [war-bows] The crossbows: in two ranks before the line while they shoot, the front spanned and the rear kneeling to
        // reload; on the town wall's parapet in an assault; once the lines close (and on parade), behind the shields with the bows.
        if (!crossbowsForward(level, bolts, b, side, ox, oz, fx, fz, yaw)) archers.addAll(bolts);
        BlockPos rise = b == null || a.tactic != BattleModel.Tactic.HILL_ARCHERS ? null : side == 0 ? b.c.ground.riseA() : b.c.ground.riseB();
        int ranks = a.formation.ranksFor(line.size()), per = (line.size() + ranks - 1) / Math.max(1, ranks);
        double half = Math.max(2, (per - 1) * a.formation.spacing / 2);
        BattleModel.Formation.Archers where = b == null ? BattleModel.Formation.Archers.BEHIND : a.formation.archers;
        if (where == BattleModel.Formation.Archers.SCREEN && b.phase.ordinal() >= BattleModel.Phase.ADVANCE.ordinal()) where = BattleModel.Formation.Archers.BEHIND;
        if (rise != null) rank(level, archers, rise.getX() + 0.5, rise.getZ() + 0.5, fx, fz, 0, 1.5, 10, yaw);
        else if (where == BattleModel.Formation.Archers.WINGS && archers.size() >= 2) {
            // On either wing, a little forward and angled in, so the bows cross in front of the line.
            int n = archers.size() / 2;
            for (int w = 0; w < 2; w++) {
                double sign = w == 0 ? 1 : -1, ax = fx * Math.cos(0.35) - sign * rx * Math.sin(0.35), az = fz * Math.cos(0.35) - sign * rz * Math.sin(0.35);
                List<Armies.Fighter> wing = w == 0 ? archers.subList(0, n) : archers.subList(n, archers.size());
                rank(level, wing, ox + rx * sign * (half + 3), oz + rz * sign * (half + 3), ax, az, 1, 1.8, 8, Armies.yawTo(ax, az));
            }
        } else if (where == BattleModel.Formation.Archers.SCREEN) rank(level, archers, ox, oz, fx, fz, 6, 3.0, 14, yaw);
        else if (where == BattleModel.Formation.Archers.INSIDE) rank(level, archers, ox - fx * 3, oz - fz * 3, fx, fz, 0, 1.2, 4, yaw);
        else back = rank(level, archers, ox, oz, fx, fz, Math.min(-ARCHERS_BACK, back - GROUP_GAP), 2.0, 14, yaw);
        // The Captain and the standard behind the centre, the reserve behind them, the medic last.
        double command = Math.min(-COMMAND_BACK, back - GROUP_GAP);
        if (captain != null && (b == null || b.phase != BattleModel.Phase.PARLEY)) captain.place(level, ox + fx * command, oz + fz * command, yaw);
        if (bearer != null) bearer.place(level, ox + fx * command + rx * 1.5, oz + fz * command + rz * 1.5, yaw);
        boolean sent = b != null && b.reserveSent[side];
        // The riders on the right wing, a little back; sent, round the far end of their line.
        double wingR = Math.max(8, line.size() * 0.75 + 5);
        if (sent && b.phase == BattleModel.Phase.CLASH && a.tactic == BattleModel.Tactic.CAVALRY) {
            double[] them = b.origin(1 - side);
            rank(level, riders, them[0] + rx * wingR, them[1] + rz * wingR, -rx, -rz, 0, 2.5, 6, Armies.yawTo(-rx, -rz));
            for (Armies.Fighter f : riders) f.speed = 1.3;
        } else rank(level, riders, ox + rx * wingR, oz + rz * wingR, fx, fz, -2, 2.5, 6, yaw);
        double last = command;
        if (sent && b.phase == BattleModel.Phase.CLASH) {
            // Round the flank: out past the end of their line, then in at it.
            double[] them = b.origin(1 - side);
            double wing = Math.max(8, line.size() * 0.75 + 5);
            rank(level, reserve, them[0] + rx * wing, them[1] + rz * wing, -rx, -rz, 0, 1.5, 8, Armies.yawTo(-rx, -rz));
            for (Armies.Fighter f : reserve) f.speed = 1.2;
        } else if (a.tactic == BattleModel.Tactic.HIDDEN_RESERVE && b != null) {
            BlockPos cover = side == 0 ? b.c.ground.coverA() : b.c.ground.coverB();
            double wing = Math.max(10, line.size() * 0.75 + 8);
            if (cover != null) rank(level, reserve, cover.getX() + 0.5, cover.getZ() + 0.5, fx, fz, 0, 1.5, 8, yaw);
            else rank(level, reserve, ox + rx * wing, oz + rz * wing, fx, fz, -6, 1.5, 8, yaw);
        } else if (!reserve.isEmpty()) last = rank(level, reserve, ox, oz, fx, fz, command - GROUP_GAP, 1.6, 12, yaw);
        if (medic != null && (b == null || b.phase.ordinal() < BattleModel.Phase.CLASH.ordinal())) {
            medic.place(level, ox + fx * (last - GROUP_GAP), oz + fz * (last - GROUP_GAP), yaw);
        }
    }

    /**
     * [war-bows] The crossbow company placed for shooting, if it shoots from where it stands: on the town wall's posts
     * (nearest the gate first) for the defenders of an assault; else in the field from the forming up till the lines
     * close, in two ranks before the line, the rank to shoot next in front and the reloading rank a step behind it,
     * kneeling, its files half a file over so the ranks pass through each other as they change places. False when it
     * stands behind the line with the bows.
     */
    static boolean crossbowsForward(ServerLevel level, List<Armies.Fighter> bolts, @Nullable Battle b, int side, double ox, double oz,
                                    double fx, double fz, float yaw) {
        if (bolts.isEmpty() || b == null) return false;
        if (b.assault && side == 1 && !b.gateDown) {
            if (b.wall == null) b.wall = wallPosts(level, b);
            if (!b.wall.isEmpty()) {
                for (int k = 0; k < bolts.size(); k++) {
                    Watch.Post p = b.wall.get(k % b.wall.size());
                    double spread = (k / b.wall.size()) * 0.8;               // more than posts: shoulder to shoulder along the walkway
                    Armies.Fighter x = bolts.get(k);
                    x.place(level, p.stand().getX() + 0.5 + p.out().getStepZ() * spread, p.stand().getZ() + 0.5 + p.out().getStepX() * spread,
                        Armies.yawTo(p.out().getStepX(), p.out().getStepZ()));
                    x.ty = p.stand().getY();
                }
                return true;
            }
        }
        if (b.phase.ordinal() > BattleModel.Phase.ADVANCE.ordinal() || b.charging || b.phase == BattleModel.Phase.ADVANCE && b.gap() <= BOLT_RETIRE) return false;
        List<Armies.Fighter> front = new ArrayList<>(), back = new ArrayList<>();
        for (Armies.Fighter x : bolts) (x.file == 0 ? front : back).add(x);
        double rx = -fz, rz = fx;
        rank(level, front, ox, oz, fx, fz, BOLT_FRONT, 2.0, 14, yaw);
        rank(level, back, ox + rx * 1.0, oz + rz * 1.0, fx, fz, BOLT_BACK, 2.0, 14, yaw);
        return true;
    }

    /** [war-bows] The defending town's wall posts, nearest the battle's centre (its gate) first. */
    static List<Watch.Post> wallPosts(ServerLevel level, Battle b) {
        Armies.Army d = b.side[1];
        List<Watch.Post> out = d == null ? new ArrayList<>() : new ArrayList<>(CastleWall.posts(level, d.town));
        out.sort(Comparator.comparingDouble(p -> p.stand().distSqr(b.centre)));
        return out;
    }

    /** On parade: the sergeants at the ends of the line, the rest between. */
    static List<Armies.Fighter> paradeOrder(List<Armies.Fighter> line) {
        List<Armies.Fighter> sorted = new ArrayList<>(line);
        sorted.sort(Comparator.comparingInt(f -> f.rank == Armies.Rank.SERGEANT ? 0 : 1));
        List<Armies.Fighter> ordered = new ArrayList<>();
        for (int k = 0; k < sorted.size(); k++) {
            if (k % 2 == 0) ordered.add(sorted.get(k));
            else ordered.add(0, sorted.get(k));
        }
        return ordered;
    }

    /**
     * [war-polish] The line in the order its formation fills it: the front rank first, the shields in it and a sergeant
     * at each end, the rest behind; a wedge's best at its point; a ring as it stands.
     */
    static List<Armies.Fighter> lineOrder(ServerLevel level, List<Armies.Fighter> line, BattleModel.Formation f) {
        List<Armies.Fighter> out = new ArrayList<>(line);
        if (f.bend == BattleModel.Formation.Bend.WEDGE) {
            out.sort((x, y) -> Integer.compare(y.power, x.power));
            return out;
        }
        if (f.bend == BattleModel.Formation.Bend.RING || out.isEmpty()) return out;
        out.sort(Comparator.comparingInt(x -> shielded(level, x) ? 0 : 1));
        int ranks = f.ranksFor(out.size()), per = Math.min(out.size(), (out.size() + ranks - 1) / ranks);
        List<Armies.Fighter> front = new ArrayList<>(out.subList(0, per)), ends = new ArrayList<>();
        for (Armies.Fighter x : front) if (x.rank == Armies.Rank.SERGEANT && ends.size() < 2) ends.add(x);
        front.removeAll(ends);
        if (!ends.isEmpty()) front.add(0, ends.get(0));
        if (ends.size() > 1) front.add(ends.get(1));
        front.addAll(out.subList(per, out.size()));
        return front;
    }

    static boolean shielded(ServerLevel level, Armies.Fighter x) {
        return level.getEntity(x.id) instanceof LivingEntity e && e.getOffhandItem().getItem() instanceof ShieldItem;
    }

    /**
     * The line drawn up in its formation's shape (BattleModel.Formation.slot): ranks so deep and so far apart, bent
     * into a wedge, a ring, a crescent or an echelon. Not a grid: each keeps a little way off its mark (less for a
     * veteran, and less again just after its sergeant has dressed the line), the brave a step forward and the green a
     * step back, the ends of the line a moment behind the middle on the advance, and one with the nerves gone a step
     * back till it is pulled into line. [war-polish] Before the horn the ranks stand true and still (a tenth of a
     * block off at most), each now and then glancing aside. How far back its rearmost slot lies (blocks, negative).
     */
    static double shape(ServerLevel level, List<Armies.Fighter> fs, BattleModel.Formation f, double ox, double oz, double fx, double fz, float yaw,
                        Battle b, int side) {
        int n = fs.size();
        if (n == 0) return 0;
        double rx = -fz, rz = fx, radius = f.ringRadius(n), back = 0;
        boolean still = b.still();
        long now = level.getGameTime();
        for (int k = 0; k < n; k++) {
            Armies.Fighter x = fs.get(k);
            double[] slot = f.slot(k, n);
            double across = slot[0], depth = slot[1];
            float face = yaw;
            if (f.bend == BattleModel.Formation.Bend.RING) {
                double cx = across / radius, cz = (depth + radius) / radius;
                face = Armies.yawTo(fx * cz + rx * cx, fz * cz + rz * cx);
            }
            back = Math.min(back, depth);
            depth += human(x, b, side, across);
            double rag = b.ragged * (x.veteran ? 0.12 : 0.4);
            if (still) {
                rag = Math.min(rag, STILL_JITTER);
                face += idle(x, now);
            }
            double jx = ((x.id.getLeastSignificantBits() & 15) / 15.0 - 0.5) * 2 * rag, jz = (((x.id.getLeastSignificantBits() >> 4) & 15) / 15.0 - 0.5) * 2 * rag;
            x.place(level, ox + fx * depth + rx * across + jx, oz + fz * depth + rz * across + jz, face);
        }
        return back;
    }

    /** A fighter's own step off its formation's mark, forward or back: brave or green, its nerves, the order reaching it late. */
    static double human(Armies.Fighter x, Battle b, int side, double across) {
        double d = x.veteran || x.power >= 12 ? 0.35 : x.power <= 7 ? -0.35 : 0.0;
        if (b.still()) d *= STILL_STEP;                            // [war-polish] drawn up: the ranks stand true
        if (x.nerves > 0) d -= 1.8;
        if (b.phase == BattleModel.Phase.ADVANCE) d -= Math.min(b.forward[side], Math.abs(across) * 0.07);
        return d;
    }

    /** [war-polish] Before the horn, now and then (a beat in every few), a fighter glances aside, so a still line is not a statue. */
    static float idle(Armies.Fighter x, long now) {
        if ((now / IDLE_BEAT + x.stagger) % IDLE_EVERY != 0) return 0.0F;
        return x.stagger % 2 == 0 ? IDLE_TURN : -IDLE_TURN;
    }

    /** A rank (two past {@code per}) {@code spacing} apart across, {@code depth} in front of (or behind) the origin; where its last row stands. */
    static double rank(ServerLevel level, List<Armies.Fighter> fs, double ox, double oz, double fx, double fz, double depth, double spacing, int per, float yaw) {
        double rx = -fz, rz = fx, last = depth;
        for (int k = 0; k < fs.size(); k++) {
            int row = k / per, inRow = Math.min(per, fs.size() - row * per), col = k % per;
            double across = (col - (inRow - 1) / 2.0) * spacing + (row % 2) * spacing / 2;
            double d = depth - row * 1.6;
            fs.get(k).place(level, ox + fx * d + rx * across, oz + fz * d + rz * across, yaw);
            last = d;
        }
        return last;                                               // [war-polish] where its last rank stands, for those behind it
    }

    static BlockPos at(double[] origin) {
        return BlockPos.containing(origin[0], 70, origin[1]);
    }

    // ------------------------------------------------------------------ the camp

    /** In camp: round the fire (sitting, half of them), two sentries out in front. */
    static void camp(ServerLevel level, Armies.Army a, BlockPos at, double fx, double fz, @Nullable Battlefields.Camp camp) {
        List<Armies.Fighter> all = columnOrder(a);
        int n = 0;
        for (Armies.Fighter f : all) {
            if (f.down() && f.order != Armies.Order.YIELDED) continue;
            if (n < 2 && f.role == BattleModel.Role.LINE && f.rank == Armies.Rank.NONE) {
                f.place(level, at.getX() + 0.5 + fx * 12 + -fz * (n == 0 ? 6 : -6), at.getZ() + 0.5 + fz * 12 + fx * (n == 0 ? 6 : -6), Armies.yawTo(fx, fz));
                n++;
                continue;
            }
            double ang = Math.PI * 2 * n / Math.max(1, all.size()) + 0.2;
            double r = 3.0 + (n % 2);
            f.place(level, at.getX() + 0.5 + Math.cos(ang) * r, at.getZ() + 0.5 + Math.sin(ang) * r, Armies.yawTo(-Math.cos(ang), -Math.sin(ang)));
            f.sit = n % 2 == 0;
            n++;
        }
    }

    /** The sentries' beat: the first two of the line walk across the camp's front and back. */
    static void sentries(ServerLevel level, Armies.Army a, long now) {
        int n = 0;
        for (Armies.Fighter f : columnOrder(a)) {
            if (n >= 2) break;
            if (f.down() || f.role != BattleModel.Role.LINE || f.rank != Armies.Rank.NONE) continue;
            double flip = (now / 300) % 2 == 0 ? 1 : -1;
            double fx = -Math.sin(Math.toRadians(f.yaw)), fz = Math.cos(Math.toRadians(f.yaw));
            f.place(level, f.tx + -fz * 6 * flip, f.tz + fx * 6 * flip, f.yaw);
            n++;
        }
    }

    // ------------------------------------------------------------------ yielding and falling (from Armies)

    /** A fighter beaten down: counted for its side's spirit; the Captain down is a blow of its own. */
    static void yielded(ServerLevel level, Armies.Fighter x, @Nullable Entity by) {
        Battle b = battleOf(x);
        if (b == null) return;
        int i = b.side[0] == x.army ? 0 : 1;
        b.down[i]++;
        if (x.rank == Armies.Rank.CAPTAIN && !b.captainDown[i]) {
            b.captainDown[i] = true;
            x.army.morale -= BattleModel.CAPTAIN_DOWN;
            b.story.add(name(x) + "'s Captain, " + x.name + ", was struck down.");
            b.order(level, i, "The Captain's down! Hold — hold!");
        }
    }

    /** The banner falls with its bearer: on the ground where it fell, for its side to take up again or the enemy to carry off. */
    static void bannerFalls(ServerLevel level, VillageFolkEntity f, Armies.Fighter x) {
        ItemStack held = f.getMainHandItem();
        Battle b = battleOf(x);
        if (!(held.getItem() instanceof BannerItem) || b == null) return;
        int i = b.side[0] == x.army ? 0 : 1;
        f.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        ItemEntity drop = new ItemEntity(level, f.getX(), f.getY() + 0.5, f.getZ(), held.copy());
        drop.setNeverPickUp();
        drop.setUnlimitedLifetime();
        level.addFreshEntity(drop);
        b.fallenBanner[i] = drop.getUUID();
        b.fallenCloth[i] = held.copy();
        if (!b.bannerLost[i]) {
            b.bannerLost[i] = true;
            x.army.morale -= BattleModel.BANNER_LOST;
            b.story.add(name(x) + "'s banner went down.");
            b.order(level, i, "The banner! The banner's down!");
        }
    }

    /** Is this fighter in a battle staged for the pictures (nobody dies on a stage)? */
    static boolean staged(Armies.Fighter x) {
        Battle b = battleOf(x);
        return b != null && b.hold != null;
    }

    @Nullable
    static Battle battleOf(Armies.Fighter x) {
        if (x.army == null) return null;
        Campaigns.Campaign c = Campaigns.ofTown(x.army.town);
        return c == null ? null : c.battle;
    }

    static String name(Armies.Fighter x) {
        return x.army == null ? "" : Villages.name(x.army.town);
    }

    static String nameOf(@Nullable UUID id) {
        Armies.Fighter f = id == null ? null : Armies.fighter(id);
        return f == null ? "our champion" : f.name;
    }

    /** One of an army killed (the rare blow, or anything else that killed it in the field): out of the ranks, mourned. */
    @SubscribeEvent
    public static void onDeath(LivingDeathEvent e) {
        if (!Armies.any() || !(e.getEntity() instanceof VillageFolkEntity f) || !(f.level() instanceof ServerLevel level)) return;
        Armies.Fighter x = Armies.fighter(f.getUUID());
        if (x == null || x.dead) return;
        x.dead = true;
        Battle b = battleOf(x);
        if (b != null && !x.down()) {
            int i = b.side[0] == x.army ? 0 : 1;
            b.down[i]++;
        }
        if (x.army != null && !WarAndPeace.awayOnGarrison(f)) {
            long day = level.getDayTime() / 24000L;
            WarAndPeace.fallen(x.army.town, x.army.enemy, x.what(), day);
            Villages.tell(x.army.town, day, x.what() + " fell" + (b != null ? " at " + b.c.name : " in the war with " + Villages.name(x.army.enemy)));
        }
    }

    /** A fighter the paper says fell, killed now its ground is awake (once). */
    static void dies(VillageFolkEntity f, Armies.Fighter x) {
        if (!f.isAlive()) return;
        x.dead = false;                                           // so the death is counted once, by onDeath
        f.hurt(f.level().damageSources().generic(), Float.MAX_VALUE);
        x.dead = true;
    }

    // ------------------------------------------------------------------ captives

    /** Taken: held by the winner's town (Spies), led on a lead by its captor if the army carried leads. */
    static void capture(ServerLevel level, Armies.Fighter f, @Nullable Armies.Fighter by, Armies.Army w, long day) {
        f.order = Armies.Order.CAPTIVE;
        f.foe = by == null ? null : by.id;
        Spies.takeInBattle(w.town, f.id, f.name, f.town, day);
        Battle b = battleOf(f);
        if (b != null) b.captives.add(f.name);
        if (level.getEntity(f.id) instanceof VillageFolkEntity e) {
            e.setPose(Pose.STANDING);
            if (by != null && level.getEntity(by.id) instanceof VillageFolkEntity captor && w.use(Items.LEAD, 1)) e.setLeashedTo(captor, true);
            Armies.shout(e, FolkTalk.pick(e.getRandom(), "All right. I'll come.", "You've won the day. I'll not run.", "Mind the lead — I'm coming."));
        }
    }

    // ------------------------------------------------------------------ written up

    /**
     * The battle written into the books: both towns' chronicles and the war's course, the war's score and
     * weariness, the day of it (the armies rest two days after), the cairn on the field, the veterans and the brave.
     */
    static void record(ServerLevel level, Campaigns.Campaign c, Battle b) {
        long day = level.getDayTime() / 24000L;
        if (b.side[0] == null || b.side[1] == null || b.settled) return;          // a surrender taken at the parley is the treaty's
        String[] names = { b.name(0), b.name(1) };
        if (b.withdrew) {
            for (int i = 0; i < 2; i++) WarBooks.course(b.side[i].town, b.side[1 - i].town, day, capital(c.name) + ": " + String.join(" ", b.story));
            return;
        }
        String summary = String.join(" ", b.story);
        int w = b.winner;
        for (int i = 0; i < 2; i++) {
            UUID us = b.side[i].town, them = b.side[1 - i].town;
            String how = w < 0 ? "neither side won the field" : w == i ? "we held the field" : "we " + b.outcome.words.replace("its ", "our ");
            Villages.tell(us, day, capital(c.name) + ": " + how + " against " + names[1 - i] + " (" + b.down[i] + " of ours down, " + b.down[1 - i]
                + " of theirs" + (b.captives.isEmpty() ? "" : "; captives: " + String.join(", ", b.captives)) + ")");
            WarBooks.course(us, them, day, capital(c.name) + ": " + summary);
            WarBooks.push(us, "wp.battles/" + them, day + "|" + WarBooks.clean(capital(c.name)) + "|" + (w < 0 ? "drawn" : w == i ? "won" : "lost") + "|"
                + b.down[i] + "|" + b.down[1 - i] + "|" + (b.outcome == null ? "" : b.outcome.name()), 10);
            Ledger.note(us, "wp.battleday/" + them, Long.toString(day));
            int weary = WarAndPeace.weariness(us);
            WarAndPeace.weariness(us, Math.max(0, Math.min(100, weary + (w < 0 ? 4 : w == i ? -3 : 10 + Math.min(10, b.down[i] * 2)))));
        }
        if (w >= 0) Campaigns.bump(b.side[w].town, b.side[1 - w].town, 1);
        Earnings.battleFought(level, b.side[0].town, b.side[1].town, w, b.centre);        // [earning] the sellswords paid
        // Each side has seen the other's army for itself: its reckoning of them is what it saw, not rumour.
        for (int i = 0; i < 2; i++) Intel.truth(level, b.side[i].town, b.side[1 - i].town, day, "seen on the field at " + c.name);
        veterans(level, c, b, day);
        if (!b.assault && w >= 0 && c.ground.known()) {
            Villages.Village wv = Villages.get(b.side[w].town);
            if (wv != null) Battlefields.cairn(level, wv, c.ground, new String[]{ "The Battle of", c.ground.name(), "day " + day, names[0] + " v " + names[1] });
            if (b.detailed) Battlefields.trample(level, b.side[w].town, c.ground, day, new Random(Campaigns.seed(b.side[w].town, day)));
        }
        if (!b.detailed || w < 0) {                            // [war-polish] a battle fought in full told its outcome as it broke: once
            Raids.tellNear(level, b.centre, ANNOUNCE, Component.literal("[War] " + capital(c.name) + ": " + (w < 0 ? "drawn" : names[w] + " held the field; "
                + names[1 - w] + " " + b.outcome.words) + ".").withStyle(ChatFormatting.GOLD), false);
        }
        LOG.info("[MCA-WAR] {} ({}): {}", c.name, b.detailed ? "in full" : "on paper", summary);
    }

    /** Everybody who fought a veteran the more (and remembers it); the brave honoured with a medal and a name. */
    static void veterans(ServerLevel level, Campaigns.Campaign c, Battle b, long day) {
        for (int i = 0; i < 2; i++) {
            Armies.Army a = b.side[i];
            for (Armies.Fighter f : a.fighters) {
                if (f.dead || f.order == Armies.Order.CAPTIVE || f.role == BattleModel.Role.MEDIC) continue;
                int battles = (int) WarBooks.num(Ledger.note(f.town, "war.vet/" + f.id), 0) + 1;
                Ledger.note(f.town, "war.vet/" + f.id, Integer.toString(battles));
                if (level.getEntity(f.id) instanceof VillageFolkEntity e) {
                    e.persona().remember(day, "I fought at " + c.name + (b.winner == i ? ", and we held the field" : b.winner < 0 ? "" : ", and we lost it"), 8);
                    if (f.downed >= 2 && b.winner >= 0) honour(level, e, f, c, day);
                }
            }
        }
    }

    /** The bravest: a medal out of the stores (a nugget of gold, else of iron, struck for the battle) and a name the town gives it. */
    static void honour(ServerLevel level, VillageFolkEntity e, Armies.Fighter f, Campaigns.Campaign c, long day) {
        Villages.Village v = Villages.get(f.town);
        if (v == null || Ledger.note(f.town, "war.nick/" + f.id) != null) return;
        String nick = FolkTalk.pick(e.getRandom(), "the Bold", "the Steadfast", "the Lion", "Ironhand", "the Unbowed");
        Ledger.note(f.town, "war.nick/" + f.id, nick);
        boolean gold = TownWork.take(level, v, s -> s.is(Items.GOLD_NUGGET), 1);
        if (gold || TownWork.take(level, v, s -> s.is(Items.IRON_NUGGET), 1)) {
            ItemStack medal = new ItemStack(gold ? Items.GOLD_NUGGET : Items.IRON_NUGGET);
            medal.set(net.minecraft.core.component.DataComponents.ITEM_NAME, Component.literal("Medal of " + capital(c.name)));
            ItemStack left = e.insertGiven(medal);
            if (!left.isEmpty()) TownWork.give(level, v, left);
        }
        Villages.tell(f.town, day, f.name + " was honoured for " + c.name + " (" + f.downed + " of the enemy beaten down), and is called " + f.name + " " + nick);
        e.persona().remember(day, "the town calls me " + f.name + " " + nick + ", after " + c.name, 9);
    }

    /** Home: the captives handed to the barracks, the lent banner and the armoury's shields back, the wounded to their beds. */
    static void homecoming(ServerLevel level, Campaigns.Campaign c, Armies.Army a) {
        Villages.Village v = Villages.get(a.town);
        for (Armies.Fighter f : a.fighters) {
            if (!(level.getEntity(f.id) instanceof VillageFolkEntity e)) continue;
            if (f.dead) { dies(e, f); continue; }
            if (e.isLeashed()) e.dropLeash(true, false);
            if (e.isPassenger()) e.stopRiding();                   // the horse left at the square, for the rancher to bring in
            if (v == null || !f.town.equals(a.town)) continue;
            ItemStack held = e.getMainHandItem();
            if (held.getItem() instanceof BannerItem && Arms.lent(held)) {
                e.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                TownWork.give(level, v, Arms.unlend(held.copy()));
            }
            ItemStack off = e.getOffhandItem();
            if (off.getItem() instanceof ShieldItem && WatchKit.issued(off) && e.stationTask() != AssistantEntity.StationTask.GUARD) {
                e.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
                WarWorks.returnArms(level, v, off.copy());
            }
        }
        long day = level.getDayTime() / 24000L;
        if (v != null) Villages.tell(a.town, day, "the army came home from " + c.name + " and stood down");
    }

    /** The gate's doors broken in (the town rehangs them out of its stores at the peace: Watch.keep). */
    static void breakGate(ServerLevel level, Campaigns.Campaign c) {
        Villages.Village v = c.town(1);
        if (v == null || c.gate == null) return;
        List<BlockPos> doors = new ArrayList<>();
        for (CastleWall.Gate g : CastleWall.gates(level, v.id())) if (g.gateway().distSqr(c.gate) < 9) doors.addAll(g.doors());
        for (Watch.Gate g : Watch.gates(level, v.id())) if (!g.doors().isEmpty() && g.doors().get(0).distSqr(c.gate) < 9) doors.addAll(g.doors());
        if (doors.isEmpty()) doors.add(c.gate);
        for (BlockPos p : doors) {
            if (!level.isLoaded(p)) continue;
            net.minecraft.world.level.block.state.BlockState st = level.getBlockState(p);
            if (st.isAir() || !(st.getBlock() instanceof net.minecraft.world.level.block.DoorBlock || st.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock
                || st.is(net.minecraft.tags.BlockTags.PLANKS))) continue;
            level.destroyBlock(p, false);
        }
        level.playSound(null, c.gate, SoundEvents.ZOMBIE_BREAK_WOODEN_DOOR, SoundSource.BLOCKS, 4.0F, 0.7F);
    }

    // ------------------------------------------------------------------ words

    /** The Captain's words on the square at the muster. */
    static String muster(Armies.Army a, Campaigns.Campaign c) {
        String n = Villages.name(a.town), e = Villages.name(a.enemy);
        return switch (a.tactic) {
            case SHIELD_WALL -> n + "! We'll lock shields and let " + e + " break itself on us.";
            case HILL_ARCHERS -> "Archers, you'll have the high ground. The rest of you, keep them off it!";
            case FEIGNED_RETREAT -> "When I call the fall-back, you fall back — and when I call the turn, you turn. Not before!";
            case HIDDEN_RESERVE -> "The reserve goes where I send it, and nobody sees it till it's on their flank.";
            case CAVALRY -> "Riders on the wings. When the lines meet, round their flank you go.";
            default -> n + "! We march on " + e + ". Keep your places, follow the banner, and we'll be home by the week's end.";
        };
    }

    static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
