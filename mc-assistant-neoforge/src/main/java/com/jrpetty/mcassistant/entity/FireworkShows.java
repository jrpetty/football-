package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [fireworks] The town's displays: the fireworks maker's rockets, out of the stores, up over the town at its festivals,
 * its weddings, its victories and its Remembrance Day (the rockets themselves are FireworksMaker's).
 *
 * <ul>
 * <li><b>When.</b> As the speeches end at a gathering (Assemblies: afterSpeech): a festival, Founding Day, a new age seen
 *     in, a hero honoured, a wedding (once the vows are said), the feast for a war won; and after Remembrance Day's
 *     minute's silence (Traditions). A milestone gets a salute of three (PlayerServices).</li>
 * <li><b>Only the stores' rockets.</b> Every rocket that goes up is a firework rocket taken out of the stores at the
 *     moment it is lit, the occasion's own design first (its palette: the couple's colours, the town's, white alone
 *     for Remembrance), else any display rocket put by. None put by, and there is no display: a quieter night, the
 *     bonfire's glow, and the gazette says so. Nothing is made from nothing here.</li>
 * <li><b>Where.</b> A launch spot on the square, a dozen blocks from where the town stands to watch, on open ground under
 *     the open sky: a rack of five places abreast. The maker and two helpers walk to it and stand back behind it.</li>
 * <li><b>The programme.</b> An opening volley, the middle a rocket or two at a time, and a finale, as the occasion asks:
 *     two together for a wedding (one for each of them), big volleys for a victory, and for Remembrance one at a time,
 *     white, slow. Each rocket goes straight up, from a place with nobody on it or over it, and bursts high over the
 *     town: never at folk.</li>
 * <li><b>No launches in a thunderstorm.</b> The crew waits for the thunder to pass; if it has not in two minutes the
 *     display is called off, and the rockets stay in the stores.</li>
 * <li><b>The town hears of it.</b> The chronicle, everybody near remembering it, the board while it is on, and the next
 *     morning's gazette reviews it ("A triumph", or "Over almost before it began").</li>
 * </ul>
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class FireworkShows {

    private FireworkShows() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** What a display is for: in a few words, and how many rockets it wants. */
    public enum Occasion {
        FESTIVAL("a festival", 9), FOUNDING("Founding Day", 12), CELEBRATION("a new age", 9), HONOUR("a hero's night", 9),
        WEDDING("a wedding", 9), VICTORY("a victory", 15), REMEMBRANCE("Remembrance Day", 6), SALUTE("a salute", 3);

        public final String words;
        public final int rockets;

        Occasion(String words, int rockets) {
            this.words = words;
            this.rockets = rockets;
        }
    }

    /** How long the crew has to get to the rack before the first volley goes up regardless (ticks). */
    static final long CREW_WAIT = 600L;
    /** How long a display waits out a thunderstorm before it is called off. */
    static final long STORM_WAIT = 2400L;
    /** How far the launch spot is looked for from where the town stands to watch. */
    static final int[] RINGS = { 11, 9, 13, 15, 7 };
    /** The rack: five places abreast, the middle first. */
    static final int[] RACK = { 0, -1, 1, -2, 2 };

    /** A display under way. */
    static final class Show {
        final UUID village;
        final Occasion occasion;
        final String words;
        final List<DyeColor> palette;
        final BlockPos focus, spot;
        final Direction side, back;
        final long day, began;
        final int[] programme;
        final long[] gaps;
        final List<UUID> crew = new ArrayList<>();
        @Nullable UUID maker;
        int next, fired, planned;
        long nextAt, storm = -1, blockedSince = -1;
        boolean stormed;
        /** The operator's stage display: it goes up on the moment, the crew or no (for the pictures). */
        boolean staged;
        /** What went up: the colours and the shapes, for the review. */
        final LinkedHashSet<DyeColor> colours = new LinkedHashSet<>();
        final LinkedHashSet<String> shapes = new LinkedHashSet<>();

        Show(UUID village, Occasion occasion, String words, List<DyeColor> palette, BlockPos focus, BlockPos spot, long day, long began,
             int[] programme, long[] gaps) {
            this.village = village;
            this.occasion = occasion;
            this.words = words;
            this.palette = palette;
            this.focus = focus;
            this.spot = spot;
            this.day = day;
            this.began = began;
            this.programme = programme;
            this.gaps = gaps;
            int dx = spot.getX() - focus.getX(), dz = spot.getZ() - focus.getZ();
            Direction away = Math.abs(dx) >= Math.abs(dz) ? (dx >= 0 ? Direction.EAST : Direction.WEST) : (dz >= 0 ? Direction.SOUTH : Direction.NORTH);
            this.back = away;
            this.side = away.getClockWise();
            for (int n : programme) planned += n;
        }
    }

    private static final Map<UUID, Show> SHOWS = new ConcurrentHashMap<>();
    /** The rockets each town's display put up, and on which day: {day, fired}. */
    private static final Map<UUID, long[]> FIRED = new ConcurrentHashMap<>();
    /** Each town's rockets in the stores, as last counted (FireworksMaker.tick): {display, the town's colours, elytra, white}. */
    private static final Map<UUID, int[]> STOCK = new ConcurrentHashMap<>();

    public static void resetForTests() {
        SHOWS.clear();
        FIRED.clear();
        STOCK.clear();
    }

    // ------------------------------------------------------------------ the programmes

    /** The programme an occasion asks for: the rockets in each volley, in order. */
    static int[] programme(Occasion o) {
        return switch (o) {
            case WEDDING -> new int[]{ 2, 1, 1, 1, 1, 3 };          // one for each of them, then the finale
            case FOUNDING -> new int[]{ 3, 1, 2, 1, 2, 3 };
            case VICTORY -> new int[]{ 3, 2, 2, 2, 2, 4 };          // big volleys, and a bigger finale
            case REMEMBRANCE -> new int[]{ 1, 1, 1, 1, 1, 1 };      // one at a time, slow
            case SALUTE -> new int[]{ 3 };
            default -> new int[]{ 3, 1, 1, 1, 3 };
        };
    }

    /** The pause after each volley (ticks): slow for Remembrance, a breath before the finale for the rest. */
    static long[] gaps(Occasion o, int volleys) {
        long[] g = new long[Math.max(0, volleys - 1)];
        for (int i = 0; i < g.length; i++) {
            g[i] = o == Occasion.REMEMBRANCE ? 100L : i == 0 ? 40L : i == g.length - 1 ? 45L : o == Occasion.VICTORY ? 25L : 30L;
        }
        return g;
    }

    /** A programme cut to the rockets there are: the opening and the finale kept, the middle a rocket at a time. */
    static int[] cut(int[] plan, int have) {
        int total = 0;
        for (int n : plan) total += n;
        if (have >= total) return plan;
        if (have <= 0) return new int[0];
        int opening = Math.min(plan[0], have), rest = have - opening;
        int finale = plan.length > 1 ? Math.min(plan[plan.length - 1], rest) : 0;
        int middle = rest - finale;
        int[] out = new int[1 + middle + (finale > 0 ? 1 : 0)];
        out[0] = opening;
        for (int i = 0; i < middle; i++) out[1 + i] = 1;
        if (finale > 0) out[out.length - 1] = finale;
        return out;
    }

    // ------------------------------------------------------------------ beginning

    /** The rockets a display of this palette may use, best first: its own palette's, then (but for white alone) any. */
    static Predicate<ItemStack> ours(List<DyeColor> palette) {
        return s -> FireworksMaker.fits(s, palette);
    }

    static int ready(ServerLevel level, UUID village, Occasion o, List<DyeColor> palette) {
        int own = Market.stock(level, village, ours(palette));
        if (o == Occasion.REMEMBRANCE) return own;
        return own + Market.stock(level, village, s -> FireworksMaker.display(s) && !FireworksMaker.fits(s, palette));
    }

    /** The palette a display of this occasion looks for: its design's, or the town's colours. */
    static List<DyeColor> paletteFor(ServerLevel level, UUID village, Occasion o) {
        FireworksMaker.Design d = FireworksMaker.design(level, village, o);
        return d != null ? d.palette() : FireworksMaker.townColours(village);
    }

    /**
     * A display, begun: if the stores hold rockets for it and there is a clear spot to launch from. Its crew (the maker
     * and two helpers) called to the rack, the first volley a moment after. False (and the gazette told, if the town
     * keeps a maker) when there is nothing to show.
     */
    public static boolean begin(ServerLevel level, Villages.Village v, Occasion o, BlockPos focus, String words) {
        return begin(level, v, o, focus, words, null);
    }

    static boolean begin(ServerLevel level, Villages.Village v, Occasion o, BlockPos focus, String words, @Nullable int[] programme) {
        UUID id = v.id();
        if (SHOWS.containsKey(id)) return false;
        long day = level.getDayTime() / 24000L;
        List<DyeColor> palette = o == Occasion.REMEMBRANCE ? List.of(DyeColor.WHITE) : paletteFor(level, id, o);
        int have = ready(level, id, o, palette);
        if (have <= 0) {
            quiet(v, o, words, day, "the stores had no rockets");
            return false;
        }
        BlockPos spot = launchSpot(level, focus);
        if (spot == null) {
            quiet(v, o, words, day, "there was nowhere clear to launch from");
            return false;
        }
        int[] plan = cut(programme != null ? programme : programme(o), have);
        if (plan.length == 0) return false;
        long now = level.getGameTime();
        Show s = new Show(id, o, words, palette, focus, spot, day, now, plan, gaps(o, plan.length));
        crew(level, v, s);
        s.nextAt = now + (TownJobs.instantNow() ? 0L : 60L);
        SHOWS.put(id, s);
        FIRED.put(id, new long[]{ day, 0 });
        VillageFolkEntity m = s.maker == null ? null : level.getEntity(s.maker) instanceof VillageFolkEntity f ? f : null;
        if (m != null) FolkTalk.speak(m, o == Occasion.REMEMBRANCE ? "For those we lost. Watch the sky."
            : FolkTalk.pick(level.getRandom(), "Stand back, everybody! Fireworks for " + words + "!", "Eyes to the sky, now!",
                "Ready at the rack. Here they come!"));
        Raids.tellNear(level, focus, 128, Component.literal(Villages.name(id) + ": fireworks for " + words + "!").withStyle(ChatFormatting.GOLD), true);
        LOG.info("[MCA-FIREWORKS] {}: a display for {} ({} rockets ready, {} planned) from {}", Villages.name(id), words, have, s.planned, spot);
        return true;
    }

    /** A display that could not be: into the gazette, for a town with a maker. */
    static void quiet(Villages.Village v, Occasion o, String words, long day, String why) {
        if (!FireworksMaker.opened(v.id())) return;
        Ledger.note(v.id(), "fw.review", day + "|No fireworks for " + words + ": " + why + ". A quieter night; the bonfire's glow had to do.");
    }

    /** The maker (if it is about and awake) and the two nearest grown hands to the spot, not the couple, nor the watch by night. */
    static void crew(ServerLevel level, Villages.Village v, Show s) {
        VillageFolkEntity maker = FireworksMaker.maker(v.id());
        if (maker != null && !maker.isSleeping() && maker.blockPosition().distSqr(s.spot) < 96 * 96) {
            s.maker = maker.getUUID();
            s.crew.add(maker.getUUID());
        }
        Gatherings.Wedding w = s.occasion == Occasion.WEDDING ? Gatherings.wedding(v.id()) : null;
        List<VillageFolkEntity> near = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.isSleeping() || f.isShowcase() || s.crew.contains(f.getUUID())) continue;
            if (w != null && (f.getUUID().equals(w.a()) || f.getUUID().equals(w.b()))) continue;
            if (f.stationTask() == StationTask.GUARD && level.isNight()) continue;
            if (f.blockPosition().distSqr(s.spot) > 48 * 48) continue;
            near.add(f);
        }
        near.sort(java.util.Comparator.comparingDouble(f -> f.blockPosition().distSqr(s.spot)));
        for (int i = 0; i < near.size() && s.crew.size() < 3; i++) s.crew.add(near.get(i).getUUID());
    }

    /**
     * Where the rockets go up from: open ground on the square a dozen blocks from where the town watches, under the
     * open sky, with room for the rack and nothing over it.
     */
    @Nullable
    static BlockPos launchSpot(ServerLevel level, BlockPos focus) {
        for (int r : RINGS) {
            for (int k = 0; k < 8; k++) {
                double a = (k * 45 + 22.5) * Math.PI / 180.0;
                int x = focus.getX() + (int) Math.round(Math.cos(a) * r), z = focus.getZ() + (int) Math.round(Math.sin(a) * r);
                if (!level.isLoaded(new BlockPos(x, focus.getY(), z))) continue;
                BlockPos g = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z);
                if (Math.abs(g.getY() - focus.getY()) > 6 || !open(level, g)) continue;
                int clear = 0;
                Direction side = Math.abs(x - focus.getX()) >= Math.abs(z - focus.getZ()) ? Direction.SOUTH : Direction.EAST;
                for (int off : RACK) if (open(level, g.relative(side, off))) clear++;
                if (clear >= 3) return g;
            }
        }
        return null;
    }

    /** A place on the rack: firm ground, air above it, and the sky over it. */
    static boolean open(ServerLevel level, BlockPos p) {
        net.minecraft.world.level.block.state.BlockState st = level.getBlockState(p);
        return (st.isAir() || st.canBeReplaced() && st.getFluidState().isEmpty()) && level.getBlockState(p.above()).isAir()
            && level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)
            && level.getFluidState(p.below()).isEmpty() && level.canSeeSky(p);
    }

    /**
     * Nobody on the place or over it: a rocket goes straight up out of the middle of it (a quarter of a block wide), so
     * one standing on it, or anywhere over it, would have it in the face. Never at folk. The crew stood two blocks back
     * behind the rack, and the crowd a dozen off, are clear of it; and its burst is nine blocks up and more, out of the
     * reach of its blast.
     */
    static boolean safe(ServerLevel level, BlockPos p) {
        if (!open(level, p)) return false;
        AABB column = new AABB(p).inflate(0.25, 0.0, 0.25).expandTowards(0.0, 32.0, 0.0);
        return level.getEntitiesOfClass(LivingEntity.class, column, LivingEntity::isAlive).isEmpty();
    }

    // ------------------------------------------------------------------ the occasions

    /**
     * [Assemblies] The speeches over (the vows said, the year read out, the age welcomed): the display, if the gathering
     * has one, and a festival counted toward the town's taking up fireworks.
     */
    static void afterSpeech(ServerLevel level, Assemblies.Assembly a) {
        UUID id = a.village;
        Villages.Village v = Villages.get(id);
        if (v == null) return;
        long day = level.getDayTime() / 24000L;
        Occasion o = switch (a.kind) {
            case CELEBRATION -> Occasion.CELEBRATION;
            case HONOUR -> Occasion.HONOUR;
            case FOUNDING -> Occasion.FOUNDING;
            case FESTIVAL -> Occasion.FESTIVAL;
            case WEDDING -> Occasion.WEDDING;
            case FEAST -> FireworksMaker.victoryDay(id) == day ? Occasion.VICTORY : null;
            default -> null;
        };
        if (o == null) return;
        if (o == Occasion.FESTIVAL || o == Occasion.FOUNDING || o == Occasion.CELEBRATION) FireworksMaker.festivalKept(id);
        String words = o == Occasion.VICTORY ? "the victory over " + FireworksMaker.victoryFoe(id) : Assemblies.describe(a);
        a.rockets = begin(level, v, o, a.focus, words) ? 1 : 99;
        if (o == Occasion.VICTORY) Ledger.forget(id, "fw.victory");
    }

    /** [Traditions] Remembrance Day's silence over: white rockets, one at a time, slow, for whoever the war cost. */
    static void remembrance(ServerLevel level, Villages.Village v, String toWhom) {
        BlockPos bell = Traditions.bell(level, v);
        begin(level, v, Occasion.REMEMBRANCE, bell != null ? bell : v.centre(), "Remembrance Day, for " + toWhom);
    }

    /**
     * [PlayerServices] A salute for a milestone: up to so many of the stores' display rockets straight up from the square,
     * now, and how many went. None put by: none go.
     */
    public static int salute(ServerLevel level, Villages.Village v, BlockPos at, int n) {
        if (Weather.stormy(level)) return 0;
        BlockPos spot = launchSpot(level, at);
        if (spot == null) return 0;
        int up = 0;
        Direction side = Direction.EAST;
        for (int i = 0, place = 0; i < n && place < RACK.length * 2; place++) {
            BlockPos p = spot.relative(side, RACK[place % RACK.length]);
            if (!safe(level, p)) continue;                                // never from where somebody stands
            ItemStack r = Crafts.takeOne(level, v, FireworksMaker::display);
            if (r.isEmpty()) break;
            i++;
            launch(level, p, r);
            Economy.tally(v.id(), null, r, 1, false);
            up++;
        }
        if (up > 0) FireworksMaker.tally(v.id(), 4, up);
        return up;
    }

    // ------------------------------------------------------------------ the display, step by step

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (SHOWS.isEmpty()) return;
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 5 != 2) return;
        com.jrpetty.mcassistant.Guard.run("the fireworks", () -> {
            for (Show s : new ArrayList<>(SHOWS.values())) {
                Villages.Village v = Villages.get(s.village);
                ServerLevel level = v == null ? null : server.getLevel(v.dim());
                if (v == null || level == null) {
                    SHOWS.remove(s.village);
                    continue;
                }
                step(level, v, s);
            }
        });
    }

    /** One look at a display: the storm waited out, the crew at the rack, the next volley when it is due. */
    static void step(ServerLevel level, Villages.Village v, Show s) {
        long now = level.getGameTime();
        if (!level.isLoaded(s.spot)) {
            if (now - s.began > STORM_WAIT) end(level, v, s, "the town went out of the world");
            return;
        }
        if (Weather.stormy(level)) {
            if (s.storm < 0) {
                s.storm = now;
                s.stormed = true;
                VillageFolkEntity m = s.maker == null ? null : level.getEntity(s.maker) instanceof VillageFolkEntity f ? f : null;
                if (m != null) FolkTalk.speak(m, FolkTalk.pick(level.getRandom(), "Not in this. Nobody lights a fuse under thunder.",
                    "We wait for the storm to pass. The rockets stay in the stores till it does."));
            } else if (now - s.storm > STORM_WAIT) {
                end(level, v, s, "the thunderstorm never passed");
            }
            return;
        }
        if (s.storm >= 0) {
            s.storm = -1;
            s.nextAt = Math.max(s.nextAt, now + 60L);
        }
        if (now < s.nextAt) return;
        if (!s.staged && !crewAt(level, s) && now - s.began < CREW_WAIT && !TownJobs.instantNow()) return;
        int up = volley(level, v, s, s.programme[s.next]);
        if (up < 0) {
            // Somebody on the rack, or over it: nothing goes up till they have moved off it.
            if (s.blockedSince < 0) s.blockedSince = now;
            if (now - s.blockedSince > CREW_WAIT) end(level, v, s, "the launch spot was never clear of folk");
            else s.nextAt = now + 20L;
            return;
        }
        s.blockedSince = -1;
        if (up == 0) {
            end(level, v, s, s.fired == 0 ? "no rocket could go up" : null);
            return;
        }
        s.next++;
        if (s.next >= s.programme.length) {
            end(level, v, s, null);
            return;
        }
        s.nextAt = now + s.gaps[s.next - 1];
    }

    /** Is one of the crew at the rack? */
    static boolean crewAt(ServerLevel level, Show s) {
        for (UUID u : s.crew) {
            if (level.getEntity(u) instanceof VillageFolkEntity f && f.blockPosition().distSqr(s.spot) <= 4.5 * 4.5) return true;
        }
        return s.crew.isEmpty();
    }

    /** A volley: so many rockets out of the stores, lit on the rack's free places. How many went up (-1: no place clear). */
    static int volley(ServerLevel level, Villages.Village v, Show s, int n) {
        int up = 0, clear = 0;
        for (int i = 0, place = 0; i < n && place < RACK.length * 2; place++) {
            BlockPos p = s.spot.relative(s.side, RACK[place % RACK.length]);
            if (!safe(level, p)) continue;
            clear++;
            ItemStack r = Crafts.takeOne(level, v, ours(s.palette));
            if (r.isEmpty() && s.occasion != Occasion.REMEMBRANCE) r = Crafts.takeOne(level, v, FireworksMaker::display);
            if (r.isEmpty()) break;
            launch(level, p, r);
            Economy.tally(s.village, null, r, 1, false);         // used up: the Production page's rockets in and out
            note(s, r);
            up++;
            i++;
        }
        if (clear == 0) return -1;                                          // never at folk: wait for the rack to clear
        if (up <= 0) return 0;
        s.fired += up;
        FIRED.put(s.village, new long[]{ s.day, s.fired });
        FireworksMaker.tally(s.village, 4, up);
        // The crew at the rack light them; the crowd looks up.
        RandomSource rand = level.getRandom();
        for (UUID u : s.crew) {
            if (level.getEntity(u) instanceof VillageFolkEntity f && f.blockPosition().distSqr(s.spot) <= 6 * 6) f.swing(InteractionHand.MAIN_HAND);
        }
        int voices = 0;
        for (AssistantEntity a : Villages.folkOf(s.village)) {
            if (voices >= 2 || !(a instanceof VillageFolkEntity f) || f.isSleeping() || s.crew.contains(f.getUUID())) continue;
            if (f.blockPosition().distSqr(s.focus) > 24 * 24 || rand.nextInt(4) != 0) continue;
            f.getLookControl().setLookAt(s.spot.getX() + 0.5, s.spot.getY() + 18.0, s.spot.getZ() + 0.5);
            FolkTalk.speak(f, s.occasion == Occasion.REMEMBRANCE ? FolkTalk.pick(rand, "...", "For them.", "Rest well.")
                : FolkTalk.pick(rand, "Ooooh!", "Look at that one!", "Aaaah!", "Lovely!", "Higher than the last!",
                    s.occasion == Occasion.WEDDING ? "To the happy couple!" : "To " + Villages.name(s.village) + "!"));
            voices++;
        }
        return up;
    }

    /** A rocket lit: the game's own firework rocket, straight up from the rack, out of the stores' stack. */
    static void launch(ServerLevel level, BlockPos p, ItemStack rocket) {
        FireworkRocketEntity e = new FireworkRocketEntity(level, p.getX() + 0.5, p.getY() + 0.15, p.getZ() + 0.5, rocket.copyWithCount(1));
        e.addTag("mca_fireworks");
        level.addFreshEntity(e);
        level.sendParticles(ParticleTypes.SMOKE, p.getX() + 0.5, p.getY() + 0.3, p.getZ() + 0.5, 4, 0.1, 0.1, 0.1, 0.01);
    }

    /** What a rocket that went up looked like, for the review. */
    static void note(Show s, ItemStack r) {
        s.colours.addAll(FireworksMaker.coloursOf(r));
        Fireworks fw = r.get(DataComponents.FIREWORKS);
        if (fw == null) return;
        for (FireworkExplosion e : fw.explosions()) {
            s.shapes.add(switch (e.shape()) {
                case LARGE_BALL -> "large balls";
                case STAR -> "stars";
                case BURST -> "bursts";
                case CREEPER -> "creeper faces";
                default -> "small balls";
            });
            if (e.hasTwinkle()) s.shapes.add(e.shape() == FireworkExplosion.Shape.LARGE_BALL ? "crackle" : "twinkle");
            if (e.hasTrail()) s.shapes.add("trails");
            if (!e.fadeColors().isEmpty()) s.shapes.add("fading");
        }
    }

    /** The display over: into the chronicle and the folk's memories, its review for the gazette, its crew let go. */
    static void end(ServerLevel level, Villages.Village v, Show s, @Nullable String why) {
        SHOWS.remove(s.village);
        long day = s.day;
        String review = review(s, why);
        Ledger.note(s.village, "fw.review", day + "|" + review);
        if (s.fired <= 0) return;
        FireworksMaker.tally(s.village, 3, 1);
        VillageFolkEntity m = s.maker == null ? null : level.getEntity(s.maker) instanceof VillageFolkEntity f ? f : null;
        String by = m != null ? m.displayNameCap() + ", the fireworks maker," : "the town";
        Villages.tell(s.village, day, by + " put on a display of " + s.fired + (s.fired == 1 ? " rocket" : " rockets") + " for " + s.words
            + (s.colours.isEmpty() ? "" : ", in " + FireworksMaker.colourWords(new ArrayList<>(s.colours)))
            + (s.stormed ? ", once the thunder had passed" : ""));
        int remembered = 0;
        for (AssistantEntity a : Villages.folkOf(s.village)) {
            if (remembered >= 40 || !(a instanceof VillageFolkEntity f) || f.isSleeping() || f.blockPosition().distSqr(s.focus) > 48 * 48) continue;
            f.persona().remember(day, s.crew.contains(f.getUUID()) ? "I set off the fireworks for " + s.words
                : "I watched the fireworks for " + s.words, s.occasion == Occasion.WEDDING || s.occasion == Occasion.VICTORY ? 4 : 3);
            remembered++;
        }
        if (m != null) FolkTalk.speak(m, s.occasion == Occasion.REMEMBRANCE ? "That's the last. We'll not forget them."
            : FolkTalk.pick(level.getRandom(), "That's the lot! Did you see the finale?", "And that's the show. Mind the sticks in the morning.",
                "Every one of them went up. Not bad, not bad at all."));
        LOG.info("[MCA-FIREWORKS] {}: the display for {} over: {} rockets{}", Villages.name(s.village), s.words, s.fired, why == null ? "" : " (" + why + ")");
    }

    /** The gazette's review: what went up, how, and its verdict. */
    static String review(Show s, @Nullable String why) {
        if (s.fired <= 0) return "No fireworks for " + s.words + ": " + (why == null ? "nothing went up" : why) + ". The rockets are still in the stores.";
        StringBuilder sb = new StringBuilder();
        sb.append("Fireworks for ").append(s.words).append(": ").append(s.fired).append(s.fired == 1 ? " rocket" : " rockets");
        if (!s.colours.isEmpty()) sb.append(" in ").append(FireworksMaker.colourWords(new ArrayList<>(s.colours)));
        if (!s.shapes.isEmpty()) sb.append(" (").append(String.join(", ", s.shapes)).append(")");
        int volleys = Math.min(s.next + 1, s.programme.length);
        if (s.occasion == Occasion.REMEMBRANCE) sb.append(", one at a time");
        else if (volleys > 1) sb.append(", an opening volley of ").append(s.programme[0]).append(" and a finale of ").append(s.programme[Math.min(volleys, s.programme.length) - 1]);
        if (s.stormed) sb.append(", held back by the thunder till it passed");
        sb.append(". ");
        if (s.occasion == Occasion.REMEMBRANCE) sb.append("Quiet, white and slow, as it should be.");
        else if (why != null) sb.append("Cut short: ").append(why).append('.');
        else if (s.fired >= 12) sb.append("A triumph.");
        else if (s.fired >= 8) sb.append("A fine show.");
        else if (s.fired >= 4) sb.append("Short, but sweet.");
        else sb.append("Over almost before it began.");
        return sb.toString();
    }

    // ------------------------------------------------------------------ the crew

    /** Is this folk one of a display's crew just now? */
    public static boolean crewing(VillageFolkEntity f) {
        UUID id = f.ownerId();
        Show s = id == null ? null : SHOWS.get(id);
        return s != null && s.crew.contains(f.getUUID());
    }

    /**
     * From the folk's tick (VillageFolkEntity.aiStep), before the gathering: one of the crew to its place behind the rack
     * (the maker in the middle, a helper either side), facing it and the sky. True while it is there for the display.
     */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        Show s = id == null ? null : SHOWS.get(id);
        if (s == null) return false;
        int i = s.crew.indexOf(f.getUUID());
        if (i < 0 || f.isSleeping() || f.getTarget() != null) return false;
        BlockPos stand = s.spot.relative(s.back, 2).relative(s.side, i == 0 ? 0 : i == 1 ? -2 : 2);
        f.hobbyNow = "setting off the fireworks for " + s.words;
        f.lastLeisureTick = f.tickCount;
        double dx = f.getX() - (stand.getX() + 0.5), dz = f.getZ() - (stand.getZ() + 0.5);
        // Right at its place, not short of it: short of it is on the rack, coming from the square.
        if (dx * dx + dz * dz > 0.8 * 0.8) {
            if (f.getNavigation().isDone() || f.tickCount % 40 == 0) f.walkTo(stand, 1.0D);
            return true;
        }
        f.getNavigation().stop();
        f.getLookControl().setLookAt(s.spot.getX() + 0.5, s.spot.getY() + (f.tickCount % 80 < 40 ? 1.0 : 12.0), s.spot.getZ() + 0.5);
        return true;
    }

    // ------------------------------------------------------------------ what the town sees

    /** Is a display on in this town now? */
    public static boolean on(UUID village) {
        return SHOWS.containsKey(village);
    }

    /** The rockets today's display put up so far. */
    public static int fired(UUID village, long day) {
        long[] f = FIRED.get(village);
        return f == null || f[0] != day ? 0 : (int) f[1];
    }

    /** Tests: the display under way, {fired, planned, volleys}, or null. */
    @Nullable
    public static int[] showForTests(UUID village) {
        Show s = SHOWS.get(village);
        return s == null ? null : new int[]{ s.fired, s.planned, s.programme.length };
    }

    /** Tests: the display's launch spot and its crew. */
    @Nullable
    public static BlockPos spotForTests(UUID village) {
        Show s = SHOWS.get(village);
        return s == null ? null : s.spot;
    }

    /** Tests: the next step of the town's display, now, whenever it was due (as the server's tick does it, when due). */
    public static void stepForTests(ServerLevel level, UUID village) {
        Show s = SHOWS.get(village);
        Villages.Village v = Villages.get(village);
        if (s == null || v == null) return;
        s.nextAt = Math.min(s.nextAt, level.getGameTime());
        step(level, v, s);
    }

    /** Tests: the display's rack, its five places in order. */
    public static List<BlockPos> rackForTests(UUID village) {
        Show s = SHOWS.get(village);
        List<BlockPos> out = new ArrayList<>();
        if (s != null) for (int off : RACK) out.add(s.spot.relative(s.side, off));
        return out;
    }

    /** Tests: the display's crew. */
    public static List<UUID> crewForTests(UUID village) {
        Show s = SHOWS.get(village);
        return s == null ? List.of() : List.copyOf(s.crew);
    }

    /** The rockets the stores hold, counted (FireworksMaker.tick): {display, the town's colours, elytra, white}. */
    static void countStock(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        List<DyeColor> town = FireworksMaker.townColours(id);
        int[] n = new int[4];
        for (BlockPos p : Villages.storeChests(level, id)) {
            if (!(level.getBlockEntity(p) instanceof net.minecraft.world.Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!s.is(Items.FIREWORK_ROCKET)) continue;
                if (FireworksMaker.elytra(s)) n[2] += s.getCount();
                else {
                    n[0] += s.getCount();
                    if (FireworksMaker.fits(s, town)) n[1] += s.getCount();
                    if (FireworksMaker.fits(s, List.of(DyeColor.WHITE))) n[3] += s.getCount();
                }
            }
        }
        STOCK.put(id, n);
    }

    /** The next occasion with a display, and when: "the wedding of Ash and Rowan tonight", "Founding Day in 3 days". */
    @Nullable
    static String nextOccasion(UUID village, long day) {
        Gatherings.Wedding w = Gatherings.wedding(village);
        if (w != null) return "the wedding of " + w.names() + (w.day() >= day ? " tomorrow evening" : " this evening");
        long won = FireworksMaker.victoryDay(village);
        if (won >= day) return "the victory over " + FireworksMaker.victoryFoe(village) + (won == day ? " tonight" : " on day " + (won + 1));
        long rem = FireworksMaker.remembranceIn(village, day);
        long founding = FoundingDay.next(village, day);
        Festivals.Feast feast = FireworksMaker.festivalWithin(village, day, 7);
        long feastIn = feast == null ? Long.MAX_VALUE : Festivals.next(village, day, feast) - day;
        long foundIn = founding < 0 ? Long.MAX_VALUE : founding - day;
        long remIn = rem < 0 ? Long.MAX_VALUE : rem;
        long best = Math.min(feastIn, Math.min(foundIn, remIn));
        if (best == Long.MAX_VALUE || best > 14) return null;
        String what = best == remIn ? "Remembrance Day" : best == foundIn ? "Founding Day" : feast.words;
        return what + (best == 0 ? " tonight" : best == 1 ? " tomorrow" : " in " + best + " days");
    }

    /** The board's line (VillageBoards): a display on now; else what is ready and the next occasion. Null for a town without fireworks. */
    @Nullable
    public static String boardLine(UUID village, long day) {
        Show s = SHOWS.get(village);
        if (s != null) return "RG|Fireworks now: " + s.words + ", " + s.fired + " of " + s.planned + " rockets up. Eyes to the sky!";
        if (!FireworksMaker.opened(village)) return null;
        if (FireworksMaker.hut(village) == null) return "RN|Fireworks: the town wants a powder hut, out at the edge of the town, for its maker.";
        int[] n = STOCK.getOrDefault(village, new int[4]);
        String next = nextOccasion(village, day);
        return "RN|Fireworks: " + (n[0] == 0 ? "no display rockets ready" : n[0] + " display rockets ready (" + n[1] + " in the town's colours)")
            + ", " + n[2] + " elytra rockets" + (next == null ? "" : "; next display: " + next) + ".";
    }

    /** Yesterday's display (or why there was none), for the gazette; null if nothing was planned. */
    @Nullable
    public static String gazette(UUID village, long day) {
        String n = Ledger.note(village, "fw.review");
        if (n == null || !n.contains("|")) return null;
        String[] p = n.split("\\|", 2);
        if (FireworksMaker.num(p[0], -1) != day - 1) return null;
        return "§lLast night's fireworks§r\n" + p[1];
    }

    /** What a folk says about fireworks, asked (FireworksMaker.talk). */
    static String talk(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        UUID id = v.id();
        if (!FireworksMaker.opened(id)) return "Fireworks? We've not the powder for them, nor anybody to make them. Maybe one day.";
        if (FireworksMaker.hut(id) == null) return "We're to have fireworks! The town wants a powder hut first, away from the houses.";
        countStock(level, v);
        int[] n = STOCK.getOrDefault(id, new int[4]);
        String next = nextOccasion(id, level.getDayTime() / 24000L);
        boolean me = f.stationTask() == FireworksMaker.TRADE;
        return (me ? "I've " : "There are ") + n[0] + " rockets ready for a display" + (n[1] > 0 ? ", " + n[1] + " of them in the town's colours" : "")
            + ", and " + n[2] + " for flying, at the shop. " + (next == null ? "No display planned just now." : "The next display: " + next + ".")
            + (me ? " Ask me for elytra rockets: flight one, two or three." : "");
    }

    // ------------------------------------------------------------------ the command and the stage

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("fireworks")
            .executes(FireworkShows::cmdStatus)
            .then(Commands.literal("now").requires(src -> src.hasPermission(2)).executes(ctx -> {
                Villages.Village v = here(ctx);
                if (v == null) return 0;
                ServerLevel level = ctx.getSource().getLevel();
                Ledger.Building b = FireworksMaker.hut(v.id());
                VillageFolkEntity m = FireworksMaker.maker(v.id());
                String said = b == null || m == null ? "no powder hut or no maker" : FireworksMaker.now(m, level, v, b);
                ctx.getSource().sendSuccess(() -> Component.literal("FIREWORKS made: " + said), false);
                return 1;
            }))
            .then(Commands.literal("show").requires(src -> src.hasPermission(2))
                .then(Commands.argument("occasion", StringArgumentType.word()).executes(ctx -> {
                    Villages.Village v = here(ctx);
                    if (v == null) return 0;
                    Occasion o;
                    try {
                        o = Occasion.valueOf(StringArgumentType.getString(ctx, "occasion").toUpperCase(java.util.Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        ctx.getSource().sendFailure(Component.literal("Occasions: festival, founding, celebration, honour, wedding, victory, remembrance"));
                        return 0;
                    }
                    boolean on = begin(ctx.getSource().getLevel(), v, o, v.centre(), o.words);
                    ctx.getSource().sendSuccess(() -> Component.literal(on ? "FIREWORKS a display for " + o.words + " begun"
                        : "FIREWORKS none: " + gazetteNow(v.id())), false);
                    return on ? 1 : 0;
                })))
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    List<String> out = stage(ctx.getSource().getLevel(), BlockPos.containing(ctx.getSource().getPosition()), here(ctx));
                    ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", out)), false);
                    return out.size();
                })
                .then(Commands.literal("show").executes(ctx -> {
                    List<String> out = stageShow(ctx.getSource().getLevel(), here(ctx));
                    ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", out)), false);
                    return out.size();
                })));
    }

    private static String gazetteNow(UUID village) {
        String n = Ledger.note(village, "fw.review");
        return n == null || !n.contains("|") ? "nothing to show" : n.split("\\|", 2)[1];
    }

    @Nullable
    private static Villages.Village here(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Villages.Village v = Villages.nearest(level, BlockPos.containing(ctx.getSource().getPosition()), Villages.VILLAGE_RANGE * 4);
        if (v == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        if (v == null) ctx.getSource().sendFailure(Component.literal("No village within reach."));
        return v;
    }

    private static int cmdStatus(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        List<String> lines = status(ctx.getSource().getLevel(), v);
        ctx.getSource().sendSuccess(() -> Component.literal("FIREWORKS " + Villages.name(v.id()) + "\n" + String.join("\n", lines)), false);
        return lines.size();
    }

    /** The trade and its displays, line by line (the command, the smoke stage's log). */
    public static List<String> status(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        List<String> out = new ArrayList<>();
        long day = level.getDayTime() / 24000L;
        out.add("opened: " + FireworksMaker.opened(id) + " (festivals kept " + FireworksMaker.festivalsKept(id) + ", needs " + FireworksMaker.FESTIVALS + ")");
        Ledger.Building b = FireworksMaker.hut(id);
        VillageFolkEntity m = FireworksMaker.maker(id);
        out.add("powder hut: " + (b == null ? "none" : b.anchor().toShortString() + ", gunpowder in it " + FireworksMaker.hutPowder(level, id)
            + "/" + FireworksMaker.HUT_MOST + ", cauldron " + (FireworksMaker.cauldronFull(level, id) ? "full" : "not full")));
        out.add("maker: " + (m == null ? "none" : m.displayNameCap() + " (level " + m.tradeLevel(FireworksMaker.TRADE) + ")"));
        countStock(level, v);
        int[] n = STOCK.getOrDefault(id, new int[4]);
        out.add("in the stores: " + n[0] + " display rockets (" + n[1] + " in the town's colours " + FireworksMaker.colourWords(FireworksMaker.townColours(id))
            + ", " + n[3] + " white), " + n[2] + " elytra rockets, " + Market.stock(level, id, s -> s.is(Items.GUNPOWDER)) + " gunpowder");
        FireworksMaker.Order o = FireworksMaker.next(level, v);
        out.add("next to make: " + (o == null ? "nothing" : o.design().words() + " for " + o.forWhat() + " (" + o.have() + "/" + o.want() + ")"));
        String next = nextOccasion(id, day);
        out.add("next display: " + (next == null ? "none planned" : next));
        long[] t = FireworksMaker.tally(id);
        out.add("tally: " + t[0] + " stars, " + t[1] + " display rockets, " + t[2] + " elytra rockets made; " + t[3] + " displays, "
            + t[4] + " rockets fired; " + t[5] + " gunpowder fetched from the watch's creepers");
        Show s = SHOWS.get(id);
        if (s != null) out.add("NOW: " + s.words + ", " + s.fired + "/" + s.planned + " up, from " + s.spot.toShortString());
        String review = gazetteNow(id);
        out.add("last review: " + review);
        return out;
    }

    /**
     * The stage for the pictures, where it is run: a powder hut put up on cleared ground (from a palette, for the
     * pictures, as the showcase's are) and on the town's books; the trade opened and a maker taken up; the stage's
     * makings given into the stores (gunpowder, paper, the town's dyes, gold nuggets, feathers, glowstone) and the maker's
     * rockets made from them there and then by the recipes; a fireworks maker in its own look at the bench. Returns the
     * views ("VIEW name x y z lookx looky lookz") and what was done.
     */
    static List<String> stage(ServerLevel level, BlockPos at, @Nullable Villages.Village v) {
        List<String> out = new ArrayList<>();
        if (v == null) {
            out.add("no village");
            return out;
        }
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        BlockPos ground = new BlockPos(at.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()), at.getZ());
        Direction facing = Direction.NORTH;                         // its door to the south, to the camera
        Ledger.Building b = FireworksMaker.hut(id);
        if (b == null) {
            Showcase.stage(level, ground.getX() - 7, ground.getX() + 7, ground.getZ() - 7, ground.getZ() + 9, ground.getY());
            BuildGoal.stamp(level, FireworksMaker.STRUCTURE, ground, facing, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(id, FireworksMaker.STRUCTURE, ground, facing);
            b = FireworksMaker.hut(id);
        }
        if (Ledger.note(id, "fw.open") == null || Ledger.note(id, "fw.open").isEmpty()) {
            Ledger.note(id, "fw.open", Long.toString(day));
            Villages.tell(id, day, Villages.name(id) + " took up fireworks (the stage)");
        }
        if (b == null) {
            out.add("no hut");
            return out;
        }
        // The stage's makings, given: gunpowder, paper, the town's dyes and white, gold nuggets, feathers, glowstone.
        List<ItemStack> given = new ArrayList<>(List.of(new ItemStack(Items.GUNPOWDER, 48), new ItemStack(Items.PAPER, 16),
            new ItemStack(Items.GOLD_NUGGET, 12), new ItemStack(Items.FEATHER, 8), new ItemStack(Items.GLOWSTONE_DUST, 12),
            new ItemStack(Items.WHITE_DYE, 12), new ItemStack(Items.YELLOW_DYE, 8), new ItemStack(Items.WATER_BUCKET)));
        for (DyeColor c : FireworksMaker.townColours(id)) given.add(new ItemStack(net.minecraft.world.item.DyeItem.byColor(c), 12));
        for (ItemStack g : given) Crafts.store(level, v, g);
        VillageFolkEntity m = FireworksMaker.maker(id);
        if (m == null) {
            FireworksMaker.appoint(level, v, day);
            m = FireworksMaker.maker(id);
        }
        FireworksMaker.fitOut(level, v, b, m);
        FireworksMaker.keepPowder(level, v, b);
        int made = 0;
        for (Occasion o : new Occasion[]{ Occasion.FOUNDING, Occasion.FOUNDING, Occasion.FESTIVAL, Occasion.FESTIVAL, Occasion.FOUNDING, Occasion.FESTIVAL }) {
            FireworksMaker.keepPowder(level, v, b);
            FireworksMaker.Design d = FireworksMaker.design(level, id, o);
            if (d == null) continue;
            FireworksMaker.Made r = FireworksMaker.make(level, v, b, d);
            made += r.rockets();
            if (r.rockets() > 0) {
                FireworksMaker.tally(id, 0, r.stars());
                FireworksMaker.tally(id, 1, r.rockets());
            }
        }
        FireworksMaker.keepPowder(level, v, b);
        FireworksMaker.Made e = FireworksMaker.make(level, v, b, FireworksMaker.elytraDesign(2));
        out.add("MADE " + made + " display rockets and " + e.rockets() + " elytra rockets, by the recipes, of the stage's makings");
        // A fireworks maker in its own look at the bench, for the close picture.
        FireworksMaker.Fit fit = FireworksMaker.fit(b);
        BlockPos stand = fit.bench().relative(fit.front());
        VillageFolkEntity show = McAssistantMod.VILLAGE_FOLK.get().create(level);
        if (show != null) {
            show.makeShowcase(StationTask.FIREWORKS);
            show.moveTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, fit.front().getOpposite().toYRot(), 0.0F);
            show.setYHeadRot(fit.front().getOpposite().toYRot());
            show.setYBodyRot(fit.front().getOpposite().toYRot());
            show.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.FIREWORK_ROCKET));
            show.rename("Fireworks maker");
            show.addTag("fireworks_stage");
            level.addFreshEntity(show);
        }
        BlockPos door = fit.door() != null ? fit.door() : b.anchor();
        BlockPos eye = door.relative(fit.front(), 9).relative(fit.front().getClockWise(), 4).above(3);
        out.add("VIEW hut " + eye.getX() + " " + eye.getY() + " " + eye.getZ() + " " + b.anchor().getX() + " " + (b.anchor().getY() + 1) + " " + b.anchor().getZ());
        BlockPos in = door.relative(fit.front().getOpposite(), 1);
        out.add("VIEW maker " + in.getX() + " " + in.getY() + " " + in.getZ() + " " + stand.getX() + " " + (stand.getY() + 1) + " " + stand.getZ());
        BlockPos sign = FireworksMaker.signAt(id);
        if (sign != null) {
            BlockPos se = sign.relative(fit.front(), 4).below(1);
            out.add("VIEW sign " + se.getX() + " " + se.getY() + " " + se.getZ() + " " + sign.getX() + " " + sign.getY() + " " + sign.getZ());
        }
        out.addAll(status(level, v));
        return out;
    }

    /**
     * The stage's display over the town's heart: the rockets the maker made for the stage, the first volley four seconds
     * on (the camera in place by then) and a volley of three every second and a half after it for nine seconds, so a
     * picture taken any time from five seconds to fourteen has bursts in it. Returns the view (from across the square,
     * the rack at the foot of the picture and the bursts over it) and what was begun.
     */
    static List<String> stageShow(ServerLevel level, @Nullable Villages.Village v) {
        List<String> out = new ArrayList<>();
        if (v == null) {
            out.add("no village");
            return out;
        }
        SHOWS.remove(v.id());
        BlockPos focus = v.centre();
        boolean on = begin(level, v, Occasion.FOUNDING, focus, "the stage's display", new int[]{ 3, 3, 3, 3, 3, 3 });
        Show s = SHOWS.get(v.id());
        if (!on || s == null) {
            out.add("no display: " + gazetteNow(v.id()));
            return out;
        }
        s.staged = true;
        s.nextAt = level.getGameTime() + 80L;
        for (int i = 0; i < s.gaps.length; i++) s.gaps[i] = 30L;
        // Flight-two rockets burst nine to twenty blocks up: from twenty back and five up, looking at thirteen up, the
        // rack is at the foot of the picture and every burst in it.
        BlockPos eye = s.spot.relative(s.back.getOpposite(), 20).above(5);
        out.add("VIEW show " + eye.getX() + " " + eye.getY() + " " + eye.getZ() + " " + s.spot.getX() + " " + (s.spot.getY() + 13) + " " + s.spot.getZ());
        BlockPos side = s.spot.relative(s.back.getOpposite(), 14).relative(s.side, 12).above(3);
        out.add("VIEW crowd " + side.getX() + " " + side.getY() + " " + side.getZ() + " " + s.spot.getX() + " " + (s.spot.getY() + 10) + " " + s.spot.getZ());
        out.add("BEGUN " + s.planned + " rockets from " + s.spot.toShortString());
        return out;
    }
}
