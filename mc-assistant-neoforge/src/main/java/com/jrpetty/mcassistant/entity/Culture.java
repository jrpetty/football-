package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Culture and identity [batchD]: what makes a town its own besides its trades and its walls.
 * <ul>
 * <li><b>Its banner and its motto</b> (Heraldry): a banner designed once for the town, from its land,
 *     its name and the nature of whoever led it at the founding, woven out of the stores and hung on the
 *     hall, the gates, the market and the theatre; a copy at the shop for a citizen; the motto carved
 *     over the hall's door.</li>
 * <li><b>Its customs</b> (Traditions): its great days kept every year on their anniversary — a minute's
 *     silence at the bell, lanterns on the square, a toast at the tavern — and cried by the crier.</li>
 * <li><b>The theatre</b> (Theatre): an open stage, and a play out of the town's own chronicle on the
 *     evening of the day of rest.</li>
 * <li><b>The band and the choir</b> (Music): the folk who love music, at the tavern on the day of rest,
 *     at weddings and feasts, and in the chapel at the morning service.</li>
 * <li><b>Paintings</b> (Paintings): pictures painted of an evening, sold at the shop, hung in the
 *     hall, the museum and the tavern.</li>
 * <li><b>Plaques</b> (Plaques): signs put up where the town was founded, at its first house, where a
 *     hero fell and where the record harvest came in.</li>
 * </ul>
 *
 * <p>This class holds them together: the town's look at all of it every second (the server's tick, as
 * the town bell's is), the folk's own part (VillageFolkEntity.aiStep: a folk at the silence, in the choir,
 * on the stage or a bench at the theatre, in the band, at the toast, at its easel), and what the player
 * reads of it on the board, the folk's card and the Culture page of the town's books.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Culture {

    private Culture() {}

    /** What a folk is about, of the town's culture. */
    enum Role { SILENCE, CHOIR, ACTOR, AUDIENCE, BAND, TOAST, PAINTER, BUSK, LISTEN,   // [arms] a busker, a passer-by stopped to hear it
        RITE }                                                                    // [culture2] the faith's rites (Beliefs)

    /** A folk held by the town's culture: as what, till when (its last look and a little), and its seat if it sat. */
    static final class Held {
        Role role;
        long until;
        @Nullable BlockPos seat;
        float yaw;
        boolean seated;
        String doing = "";

        Held(Role role) { this.role = role; }
    }

    private static final Map<UUID, Held> HELD = new ConcurrentHashMap<>();

    /** Everything kept in memory forgotten (Villages.resetForTests: the tests share one JVM). */
    public static void resetForTests() {
        HELD.clear();
        Heraldry.resetForTests();
        Traditions.resetForTests();
        Theatre.resetForTests();
        Music.resetForTests();
        Paintings.resetForTests();
        Plaques.resetForTests();
        Buskers.resetForTests();                                         // [arms]
        TownWays.resetForTests();                                        // [culture2] the town's own ways
    }

    // ------------------------------------------------------------------ the town's part

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        int tick = event.getServer().getTickCount();
        if (tick % 5 != 1) return;
        com.jrpetty.mcassistant.Guard.run("the town's culture", () -> {
            for (ServerLevel level : event.getServer().getAllLevels()) {
                for (Villages.Village v : Villages.every()) {
                    if (!v.dim().equals(level.dimension()) || !level.isLoaded(v.centre())) continue;
                    Music.beat(level, v);                                  // the band and the choir, a beat at a time
                    if (tick % 20 == 1) tick(level, v);
                }
            }
        });
    }

    /** The town's look at its culture, once a second. */
    static void tick(ServerLevel level, Villages.Village v) {
        Heraldry.tick(level, v);
        Traditions.tick(level, v);
        Theatre.tick(level, v);
        Music.tick(level, v);
        Paintings.tick(level, v);
        Plaques.tick(level, v);
        Arms.tick(level, v);                                             // [arms] the arms on the road, the watch, the tabards
        Buskers.tick(level, v);                                          // [arms] the street's musicians
    }

    /** The shop's sign for the town's banner, right-clicked: a copy for a citizen (Heraldry). */
    @SubscribeEvent
    public static void onUseBlock(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        BlockPos pos = e.getPos();
        if (!(level.getBlockState(pos).getBlock() instanceof WallSignBlock)) return;
        if (!(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) return;
        if (!Heraldry.SHOP_SIGN.equals(sign.getFrontText().getMessage(0, false).getString())) return;
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        if (e.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        Villages.Village v = Villages.nearest(level, pos, 96);
        if (v == null) return;
        e.getEntity().displayClientMessage(Component.literal(Heraldry.sell(level, v, e.getEntity())), true);
    }

    // ------------------------------------------------------------------ the folk's part

    /**
     * Every few ticks for each folk (VillageFolkEntity.aiStep): the silence on a great day's anniversary, the
     * choir at the morning service, the play at the theatre (on the stage or on a bench), the band, the
     * toast at the tavern, a picture at the easel. True while one of them has it; its own day waits.
     */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        if (v == null || f.isShowcase() || f.isBaby() || !awake(f, level)) {
            release(f);
            return false;
        }
        // The silence stops everybody where it is, on its way home from the dusk bell or not.
        if (Traditions.silence(f, level, v)) return true;
        if (!free(f, level)) {
            release(f);
            return false;
        }
        boolean held = Music.choir(f, level, v) || Theatre.hold(f, level, v)
            || Music.band(f, level, v) || Traditions.toast(f, level, v) || Paintings.easel(f, level, v)
            || Buskers.hold(f, level, v)                                 // [arms] busking, or stopped to listen
            || Beliefs.hold(f, level, v);                                // [culture2] the morning's rite, the Stars' vigil
        if (!held) release(f);
        return held;
    }

    /** Is this folk about the town's culture just now (its work, its evening and its bed wait)? Every tick: a seat kept. */
    public static boolean busy(VillageFolkEntity f) {
        Held h = HELD.get(f.getUUID());
        if (h == null) return false;
        if (f.level().getGameTime() > h.until || !f.isAlive()) {
            release(f);
            return false;
        }
        if (h.seated && h.seat != null) {
            Vec3 at = new Vec3(h.seat.getX() + 0.5, h.seat.getY() + 0.5, h.seat.getZ() + 0.5);
            if (f.position().distanceToSqr(at) > 1.0) {
                h.seated = false;
                if (f.getPose() == Pose.SITTING) f.setPose(Pose.STANDING);
            } else {
                if (f.getPose() != Pose.SITTING) f.setPose(Pose.SITTING);
                f.setYBodyRot(h.yaw);
                f.setYHeadRot(h.yaw);
            }
        }
        return true;
    }

    /** Sat on a bench at the theatre (Park, which stands up anybody sat down that it did not sit, leaves it be). */
    public static boolean seated(VillageFolkEntity f) {
        Held h = HELD.get(f.getUUID());
        return h != null && h.seated;
    }

    /** Tests: what this folk is about, of the town's culture ("SILENCE", "BAND"...), or null. */
    @Nullable
    public static String roleForTests(VillageFolkEntity f) {
        Role r = role(f);
        return r == null ? null : r.name();
    }

    /** Tests: this folk's pastime (its persona rolled first, if it has not been). */
    public static void hobbyForTests(VillageFolkEntity f, Persona.Hobby h) {
        Persona p = f.persona();
        if (!p.rolled()) p.roll(f.getRandom(), f.life(), f.stationTask(), f.level().getDayTime() / 24000L, "a founder of the village");
        p.hobby = h;
    }

    /** Free to be about any of it: awake, at home in its town, nothing pressing in hand, and not the watch on duty. */
    static boolean free(VillageFolkEntity f, ServerLevel level) {
        if (!awake(f, level)) return false;
        if (TownJobs.busy(f) || School.teaching(f)) return false;
        return !TownCalendar.busy(f);
    }

    /** Awake and at home in its town, nothing pressing (a fight, a fire, a storm, the bell, the road), and not the watch on duty. */
    static boolean awake(VillageFolkEntity f, ServerLevel level) {
        if (!f.isAlive() || f.isSleeping() || f.isHired() || f.getTarget() != null) return false;
        if (f.trip() != null || f.expedition() != null || Nether.away(f) || Drover.busy(f)) return false;
        if (FireBrigade.onIt(f) || Weather.sheltering(f)) return false;
        if (Raids.underAlarm(f.ownerId())) return false;
        return f.stationTask() != AssistantEntity.StationTask.GUARD || !level.isNight() && !f.onWatch();
    }

    /** Taken (or kept) for this, a few seconds from now; returns its hold. */
    static Held mark(VillageFolkEntity f, Role role, String doing) {
        Held h = HELD.get(f.getUUID());
        if (h == null || h.role != role) {
            if (h != null) release(f);
            h = new Held(role);
            HELD.put(f.getUUID(), h);
            f.clearQueue();
            f.getNavigation().stop();
            f.hobbySpot = null;                                    // (a musician's own spot at the well is not tonight's)
        }
        h.until = f.level().getGameTime() + 12;
        h.doing = doing;
        f.hobbyNow = doing;
        f.lastLeisureTick = f.tickCount;
        return h;
    }

    /** Let go: up off its seat, and back to its own day. */
    static void release(VillageFolkEntity f) {
        Held h = HELD.remove(f.getUUID());
        if (h != null && h.seated && f.getPose() == Pose.SITTING) f.setPose(Pose.STANDING);
    }

    @Nullable
    static Role role(VillageFolkEntity f) {
        Held h = HELD.get(f.getUUID());
        return h == null ? null : h.role;
    }

    /** Down on this seat (a stair the right way up), facing the way it looks. */
    static void sit(VillageFolkEntity f, Held h, BlockPos seat, float yaw) {
        f.getNavigation().stop();
        f.moveTo(seat.getX() + 0.5, seat.getY() + 0.5, seat.getZ() + 0.5, yaw, 0.0F);
        f.setYHeadRot(yaw);
        f.setYBodyRot(yaw);
        f.setPose(Pose.SITTING);
        h.seat = seat.immutable();
        h.yaw = yaw;
        h.seated = true;
    }

    /** To a spot: true once there (within so far across, a step up or down). */
    static boolean arrive(VillageFolkEntity f, BlockPos spot, double within, double speed) {
        double dx = f.getX() - (spot.getX() + 0.5), dz = f.getZ() - (spot.getZ() + 0.5);
        if (dx * dx + dz * dz <= within * within && Math.abs(f.getY() - spot.getY()) <= 1.5) {
            f.getNavigation().stop();
            return true;
        }
        if (f.getNavigation().isDone() || f.tickCount - f.hobbyTick > 60) {
            f.walkTo(spot, speed);
            f.hobbyTick = f.tickCount;
        }
        return false;
    }

    /** A thing in its other hand for show (a note block, a brush): only while it is at it; Leisure puts it away after. */
    static void prop(VillageFolkEntity f, Item item) {
        ItemStack off = f.getItemBySlot(EquipmentSlot.OFFHAND);
        if (!off.isEmpty()) return;
        ItemStack prop = new ItemStack(item);
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("mca_prop", true);                          // the tag Leisure.isProp knows: never dropped, put away after
        prop.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        f.setItemSlot(EquipmentSlot.OFFHAND, prop);
        f.propInHand = true;
    }

    // ------------------------------------------------------------------ the town's places

    /** A building of the town's, by its kind, or null. */
    @Nullable
    static Ledger.Building building(UUID village, String structure) {
        for (Ledger.Building b : Ledger.buildings(village)) if (b.structure().equals(structure)) return b;
        return null;
    }

    /** The town's hall: the leader's hall once it stands, else the meeting hall; null before either. */
    @Nullable
    static Ledger.Building hall(UUID village) {
        Ledger.Building b = building(village, "townhall");
        return b != null ? b : building(village, "hall");
    }

    /** A cell of a building, in its drawing's terms: across (right is +), up, and toward the back. */
    static BlockPos at(Ledger.Building b, int dx, int h, int dz) {
        Direction back = b.facing();
        return b.anchor().relative(back.getClockWise(), dx).relative(back, dz).above(h);
    }

    /** A place on a wall: the cell in front of it, and the way it faces (out of the wall). */
    record Spot(BlockPos at, Direction facing) {}

    /**
     * Places on the outside of a building's front, at this height over its ground, in these columns: walking
     * in from the street to the first solid face, the cell in front of it. Not over or beside a door, and
     * (for a banner, which hangs down a block) with room under it. A banner or a sign already there is still
     * the place.
     */
    static List<Spot> front(ServerLevel level, Ledger.Building b, int h, int[] columns, boolean hangs) {
        List<Spot> out = new ArrayList<>();
        Direction face = b.facing().getOpposite();
        int depth = Blueprints.has(b.structure()) ? Blueprints.fullHalf(b.structure())[1] : 5;
        for (int dx : columns) {
            for (int dz = -depth - 2; dz <= 0; dz++) {
                BlockPos w = at(b, dx, h, dz);
                if (!level.isLoaded(w)) break;
                BlockState ws = level.getBlockState(w);
                if (ws.isAir() || ws.canBeReplaced() || ws.getBlock() instanceof WallBannerBlock || ws.getBlock() instanceof WallSignBlock) continue;
                if (!ws.isFaceSturdy(level, w, face) || ws.getBlock() instanceof DoorBlock) break;
                BlockPos s = w.relative(face);
                BlockState ss = level.getBlockState(s);
                boolean ours = ss.getBlock() instanceof WallBannerBlock || ss.getBlock() instanceof WallSignBlock;
                if (!ss.isAir() && !ours) break;
                if (hangs && !level.getBlockState(s.below()).isAir() && !ours) break;
                boolean door = false;
                for (int k = 1; k <= 2; k++) if (level.getBlockState(w.below(k)).getBlock() instanceof DoorBlock) door = true;
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    if (level.getBlockState(s.relative(d)).getBlock() instanceof DoorBlock) door = true;
                }
                if (!door) out.add(new Spot(s.immutable(), face));
                break;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ where the player sees it

    /** The folk's card: its part in the town's culture, or null. */
    @Nullable
    public static String cardLine(VillageFolkEntity f) {
        List<String> bits = new ArrayList<>();
        Held h = HELD.get(f.getUUID());
        if (h != null && !h.doing.isEmpty()) bits.add("now " + h.doing);
        String band = Music.cardLine(f);
        if (band != null) bits.add(band);
        String stage = Theatre.cardLine(f);
        if (stage != null) bits.add(stage);
        String art = Paintings.cardLine(f);
        if (art != null) bits.add(art);
        String busk = Buskers.cardLine(f);                               // [arms] the street's musicians
        if (busk != null) bits.add(busk);
        String arms = Arms.cardLine(f);                                  // [arms] the town's arms it bears
        if (arms != null) bits.add(arms);
        return bits.isEmpty() ? null : String.join("; ", bits);
    }

    /** The board's lines (in its foot): the banner and the motto, the customs, the theatre tonight. */
    public static List<String> board(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        String arms = Heraldry.boardLine(village);
        if (arms != null) out.add("FN|" + arms);
        String drawn = Arms.boardLine(village);                          // [arms] the arms drawn in the board's header
        if (drawn != null) out.add(drawn);
        String customs = Traditions.boardLine(village, level.getDayTime() / 24000L);
        if (customs != null) out.add("FN|" + customs);
        String stage = Theatre.boardLine(level, village);
        if (stage != null) out.add("FG|" + stage);
        String ways = TownWays.boardLine(village);                       // [culture2] its style, its dish, its feast, its faith
        if (ways != null) out.add(ways);
        return out;
    }

    /** The town's books, the Culture page (CulturePage draws it). */
    public static CompoundTag report(ServerLevel level, Villages.Village v) {
        CompoundTag out = new CompoundTag();
        out.put("banner", Heraldry.report(level, v));
        out.put("customs", Traditions.report(level, v));
        out.put("theatre", Theatre.report(level, v));
        out.put("music", Music.report(level, v));
        out.put("paintings", Paintings.report(level, v));
        out.put("plaques", Plaques.report(level, v));
        out.put("arms", Arms.report(level, v));                          // [arms]
        out.put("buskers", Buskers.report(level, v));                    // [arms]
        out.put("ways", TownWays.report(level, v));                      // [culture2] its table, tongue, building, feast and faith
        return out;
    }

    /** /village culture: all of it in chat. */
    public static List<String> status(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        UUID id = v.id();
        out.add("Culture of " + Villages.name(id) + ":");
        CompoundTag r = report(level, v);
        CompoundTag banner = r.getCompound("banner");
        out.add("Banner: " + banner.getString("blazon") + " — hung " + banner.getInt("hung") + " of " + banner.getInt("places")
            + (banner.getString("short").isEmpty() ? "" : "; short of " + banner.getString("short")));
        out.add("Motto: " + banner.getString("motto") + (banner.getBoolean("carved") ? " (carved over the hall's door)" : ""));
        for (String s : strings(r.getCompound("customs"), "lines")) out.add("Custom: " + s);
        for (String s : strings(r.getCompound("theatre"), "lines")) out.add("Theatre: " + s);
        for (String s : strings(r.getCompound("music"), "lines")) out.add("Music: " + s);
        for (String s : strings(r.getCompound("paintings"), "lines")) out.add("Paintings: " + s);
        for (String s : strings(r.getCompound("plaques"), "lines")) out.add("Plaque: " + s);
        for (String s : strings(r.getCompound("arms"), "lines")) out.add("Arms: " + s);          // [arms]
        for (String s : strings(r.getCompound("buskers"), "lines")) out.add("Busker: " + s);     // [arms]
        return out;
    }

    static ListTag strings(List<String> list) {
        ListTag t = new ListTag();
        for (String s : list) t.add(StringTag.valueOf(s));
        return t;
    }

    static List<String> strings(CompoundTag tag, String key) {
        List<String> out = new ArrayList<>();
        ListTag l = tag.getList(key, net.minecraft.nbt.Tag.TAG_STRING);
        for (int i = 0; i < l.size(); i++) out.add(l.getString(i));
        return out;
    }

    /** A note of the town's, as rows of fields (tab between fields, a new line between rows). */
    static List<String[]> rows(UUID village, String key) {
        String n = Ledger.note(village, key);
        List<String[]> out = new ArrayList<>();
        if (n == null || n.isEmpty()) return out;
        for (String row : n.split("\n")) if (!row.isEmpty()) out.add(row.split("\t", -1));
        return out;
    }

    static void rows(UUID village, String key, List<String[]> rows) {
        StringBuilder sb = new StringBuilder();
        for (String[] r : rows) {
            if (sb.length() > 0) sb.append('\n');
            for (int i = 0; i < r.length; i++) {
                if (i > 0) sb.append('\t');
                sb.append(r[i] == null ? "" : r[i].replace('\t', ' ').replace('\n', ' '));
            }
        }
        Ledger.note(village, key, sb.toString());
    }

    static long num(String s, long otherwise) {
        try {
            return Long.parseLong(s.trim());
        } catch (RuntimeException e) {
            return otherwise;
        }
    }

    static String capital(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Words fitted to a sign's lines (as wide as a line holds), at most four. */
    static String[] signLines(String text) {
        String[] out = { "", "", "", "" };
        String[] words = text.split(" ");
        int line = 0;
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            String next = sb.length() == 0 ? w : sb + " " + w;
            if (Archive.px(next) > SIGN_PX && sb.length() > 0) {
                if (line >= 3) break;
                out[line++] = sb.toString();
                sb.setLength(0);
                sb.append(w);
            } else {
                sb.setLength(0);
                sb.append(next);
            }
        }
        if (sb.length() > 0 && line <= 3) out[line] = sb.toString();
        return out;
    }

    /** As wide as a line of a sign will hold, in the font's pixels (as the museum's labels). */
    static final int SIGN_PX = 88;
}
