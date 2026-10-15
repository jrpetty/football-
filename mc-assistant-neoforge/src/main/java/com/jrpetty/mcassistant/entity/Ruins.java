package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [war-after] Ruins: what is left of a town that is no more.
 *
 * <p><b>How a town comes to ruin.</b> Its last folk dead (VillageFolkEntity.remove), given up after a raid with nobody
 * left (JobMarket), standing empty three days with nobody on its roll, or taken and sacked with too few left to keep it
 * (Campaigns.taken, {@link #sack}). Whatever the way, the moment the town is let go (Villages.forget) its ruin is written
 * into the world's books (AfterWarBooks): its old name ("the ruins of Ashford"), where it stood, the day and how it fell,
 * its buildings and their drawings, its store chests and its board.
 *
 * <p><b>Nothing left pointing at it.</b> The towns at war with it are at peace (there is nobody to fight), a vassal of it
 * is free and its own bond forgotten, the pacts and alliances with it torn up, the prisoners it held walk home, its folk
 * held elsewhere are offered a place there (Prisoners.townGone), a ransom on its way to it comes home, its records of
 * players closed (Outlaws). Its trade deals drop themselves when next run, its envoys' hosts send them home (both
 * already look for a town that is gone). Every town that knew it hears of its fall, and its ruin goes into their atlas.
 *
 * <p><b>Decay.</b> A day after it fell its buildings begin to go, a few blocks a second while anybody is near enough for
 * its ground to be awake, up to forty-eight blocks for each day since, and never more than a third of any drawing: stone
 * cracks and grows moss, cobble mosses over, rotten timber and loose brick fall out, the glass goes, the doors and the
 * lamps, the roof comes in (the fallen pieces lying on the floor below), vines climb the walls and cobwebs hang in the
 * corners. Only what the town built is touched, a block of its own drawing that is as the drawing has it: never anything a
 * player put there, never a chest or anything else that holds things.
 *
 * <p><b>Loot.</b> Its store chests are left as they were, with what the town really had in them: anybody may take it
 * (the law of a town that is gone binds nobody). Each day of the ruin some of the food in them spoils, the meat and fish
 * to rotten flesh and the bread and the like to nothing. A town sacked first lost a share of its stores to the victors,
 * carried home in their packs ({@link #sack}).
 *
 * <p><b>Seen.</b> Its board reads "The ruins of Ashford", how and when it fell, and what is left; a player coming among
 * the ruins is told where they stand; scouts that pass it put it in their atlas, and folk of the towns round about tell
 * of it. The War page lists the ruins its town knows.
 *
 * <p><b>Rising again.</b> A growing town's colonists choose a ruin within reach before open ground (Colonies), and a
 * player may set a Village Folk Spawner down in one: either way the new town takes the old name ("Ashford rises
 * again") and the old buildings onto its own register, and its builders put them right out of the stores (Restore).
 */
public final class Ruins {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private Ruins() {}

    /** Blocks a ruin decays for each day since it fell (none on the day itself); a share of any drawing at most. */
    static final int PER_DAY = 48;
    static final double MOST_SHARE = 1.0 / 3.0;
    /** Blocks a second, at most, for one ruin. */
    static final int PER_PASS = 6;
    /** A taken town with this few grown folk left is not kept: its folk scatter, and it falls to ruin. */
    static final int RUIN_FOLK = 6;
    /** A town standing empty, nobody on its roll, this many days: abandoned. */
    static final int ABANDONED_DAYS = 3;
    /** Within this of a ruin's heart, a new town takes it up. */
    static final int RESETTLE = 48;
    /** How far a mother town's colonists will go to take up a ruin. */
    static final int COLONY_NEAR = 120, COLONY_FAR = 420;

    /** Decay and the colonists' choice, on in a game; stood down between the tests till one stands them up. */
    private static volatile boolean live = true;
    /** Why a town about to be let go is let go (sacked, abandoned), by town; "died out" if none was given. */
    private static final Map<UUID, String[]> WHY = new ConcurrentHashMap<>();
    /** Each ruin's buildings as drawn (worked out once a run): the cells that may decay. */
    private static final Map<UUID, List<BuildGoal.Placement>> PLANS = new ConcurrentHashMap<>();
    /** The players among the ruins just now (each told once a visit). */
    private static final Map<UUID, UUID> AMONG = new ConcurrentHashMap<>();
    /** What each of the victors carries home from a sack: by fighter, the items and counts. */
    private static final Map<UUID, List<ItemStack>> PLUNDER = new ConcurrentHashMap<>();

    static void resetForTests() {
        live = false;
        WHY.clear();
        PLANS.clear();
        AMONG.clear();
        PLUNDER.clear();
        for (String k : new ArrayList<>(AfterWarBooks.ruins().getAllKeys())) AfterWarBooks.ruins().remove(k);
    }

    public static void liveForTests(boolean on) {
        live = on;
    }

    // ------------------------------------------------------------------ the register

    /** A ruin as the books keep it. */
    public record Ruin(UUID id, String name, BlockPos centre, ResourceKey<Level> dim, long day, String how, @Nullable UUID by,
                       List<Ledger.Building> buildings, List<String> designs, List<BlockPos> chests, @Nullable BlockPos board,
                       int decayed, @Nullable UUID resettled, int searched) {

        public String title() {
            return "the ruins of " + name;
        }

        public boolean standing() {
            return resettled == null;
        }
    }

    @Nullable
    public static Ruin of(@Nullable UUID id) {
        if (id == null) return null;
        CompoundTag t = AfterWarBooks.ruins().getCompound(id.toString());
        return t.isEmpty() ? null : read(id, t);
    }

    /** Every ruin still standing (not taken up again). */
    public static List<Ruin> all() {
        List<Ruin> out = new ArrayList<>();
        CompoundTag all = AfterWarBooks.ruins();
        for (String k : all.getAllKeys()) {
            try {
                Ruin r = read(UUID.fromString(k), all.getCompound(k));
                if (r.standing()) out.add(r);
            } catch (IllegalArgumentException ignored) { }
        }
        return out;
    }

    static Ruin read(UUID id, CompoundTag t) {
        List<Ledger.Building> bs = new ArrayList<>();
        List<String> designs = new ArrayList<>();
        ListTag l = t.getList("buildings", Tag.TAG_COMPOUND);
        for (int i = 0; i < l.size(); i++) {
            CompoundTag b = l.getCompound(i);
            bs.add(new Ledger.Building(b.getString("s"), BlockPos.of(b.getLong("at")), Direction.from2DDataValue(b.getInt("f"))));
            designs.add(b.getString("d"));
        }
        List<BlockPos> chests = new ArrayList<>();
        for (long c : t.getLongArray("chests")) chests.add(BlockPos.of(c));
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(t.getString("dim").isEmpty() ? "minecraft:overworld" : t.getString("dim")));
        return new Ruin(id, t.getString("name"), BlockPos.of(t.getLong("centre")), dim, t.getLong("day"), t.getString("how"),
            t.hasUUID("by") ? t.getUUID("by") : null, bs, designs, chests, t.contains("board") ? BlockPos.of(t.getLong("board")) : null,
            t.getInt("decayed"), t.hasUUID("resettled") ? t.getUUID("resettled") : null, t.getInt("searched"));
    }

    // ------------------------------------------------------------------ the fall

    /** Why a town about to be let go is let go: "sacked by Bramley", "abandoned" (else it "died out"). */
    static void why(UUID town, String how, @Nullable UUID by) {
        WHY.put(town, new String[]{ how, by == null ? "" : by.toString() });
    }

    /**
     * [Villages.forget] The town is being let go: its ruin written, while its register still stands to be read, and
     * nothing left pointing at it. A camp that never raised a building leaves no ruin (but is still cleaned up after).
     */
    public static void fall(UUID id) {
        String[] why = WHY.remove(id);
        Villages.Village v = Villages.get(id);
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (v == null || server == null) return;
        ServerLevel level = server.getLevel(v.dim());
        if (level == null) return;
        com.jrpetty.mcassistant.Guard.run("a town's fall", () -> fall(level, v, why == null ? "died out" : why[0],
            why == null || why[1].isEmpty() ? null : UUID.fromString(why[1])));
    }

    static void fall(ServerLevel level, Villages.Village v, String how, @Nullable UUID by) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        String name = Villages.name(id);
        cleanUp(level, id, day, name);
        List<Ledger.Building> built = Ledger.buildings(id);
        if (built.isEmpty() || of(id) != null) return;
        CompoundTag t = new CompoundTag();
        t.putString("name", name);
        t.putLong("centre", v.centre().asLong());
        t.putString("dim", v.dim().location().toString());
        t.putLong("day", day);
        t.putString("how", how);
        if (by != null) t.putUUID("by", by);
        ListTag bs = new ListTag();
        for (Ledger.Building b : built) {
            CompoundTag one = new CompoundTag();
            one.putString("s", b.structure());
            one.putLong("at", b.anchor().asLong());
            one.putInt("f", b.facing().get2DDataValue());
            String design;
            try { design = TownBlocks.designOf(id, b); } catch (RuntimeException e) { design = b.structure(); }
            one.putString("d", design);
            bs.add(one);
        }
        t.put("buildings", bs);
        List<BlockPos> chests = Villages.storeChests(level, id);
        long[] cs = new long[chests.size()];
        for (int i = 0; i < cs.length; i++) cs[i] = chests.get(i).asLong();
        t.put("chests", new LongArrayTag(cs));
        BlockPos board = VillageBoards.boardOf(id);
        if (board != null) t.putLong("board", board.asLong());
        AfterWarBooks.ruins().put(id.toString(), t);
        AfterWarBooks.changed();
        // Word of it goes round the towns that knew it, and into their atlas.
        for (Villages.Village o : Villages.every()) {
            if (o.id().equals(id) || !Ledger.knowEachOther(o.id(), id)) continue;
            Villages.tell(o.id(), day, "word came that " + name + " " + how + "; its ruins stand " + Guide.direction(o.centre(), v.centre()) + " of us");
            Scouts.record(o.id(), new Scouts.Find(Scouts.Kind.RUIN, "the ruins of " + name, v.centre(), day, "word of its fall"));
        }
        Raids.tellNear(level, v.centre(), 200, Component.literal(name + " " + how + ". Its ruins stand at " + v.centre().getX() + ", " + v.centre().getZ() + ".")
            .withStyle(ChatFormatting.DARK_GRAY), false);
        level.playSound(null, v.centre(), SoundEvents.BELL_RESONATE, SoundSource.AMBIENT, 1.0F, 0.5F);
        LOG.info("[MCA-RUIN] {} {} on day {}: {} buildings, {} store chests", name, how, day, built.size(), chests.size());
    }

    /** Nothing left pointing at a town that is gone (see the class's note). */
    static void cleanUp(ServerLevel level, UUID dead, long day, String name) {
        for (Villages.Village o : Villages.every()) {
            UUID id = o.id();
            if (id.equals(dead)) continue;
            if (Wars.atWar(id, dead)) {
                Wars.end(id, dead);
                Ledger.forget(id, "wp.calling/" + dead);
                Ledger.forget(id, "wp.early/" + dead);
                WarBooks.course(id, dead, day, "the war ended: " + name + " is no more");
                WarBooks.close(id, dead);
                Villages.tell(id, day, "the war with " + name + " is over: " + name + " is no more");
                WarAndPeace.onPeace(level, id);
            }
            if (dead.equals(Vassals.overlord(id))) Vassals.free(level, id, day, "its overlord, " + name + ", being no more");
            Ledger.forget(id, Vassals.HAS + dead);
            Ledger.forget(id, "wp.pledge/" + dead);
            if (Envoys.pact(id, dead) || Envoys.allied(id, dead)) {
                Ledger.note(id, "pact/" + dead, "");
                Ledger.note(id, "ally/" + dead, "");
            }
            Ledger.forget(id, Prisoners.RANSOM + dead);
        }
        Prisoners.townGone(level, dead);
        for (Spies.Captive c : Spies.captives(dead)) Spies.release(level, c.who(), "when " + name + " fell");
        Outlaws.townGone(dead);
    }

    // ------------------------------------------------------------------ the sack (Campaigns.taken)

    /**
     * A town taken at its gate is sacked: the victors carry off a third of its stores in their packs (into their own
     * stores when they are home, {@link #unload}); and a town with too few left to keep it is given up, its folk gone to
     * a neighbour that will take them, or carried off by the victors, and it falls to ruin. Returns the words for it.
     */
    static String sack(ServerLevel level, Campaigns.Campaign c) {
        Villages.Village v = c.town(1), w = c.town(0);
        Armies.Army army = c.army(0);
        if (v == null || w == null || army == null) return "";
        List<VillageFolkEntity> carriers = new ArrayList<>();
        for (Armies.Fighter f : army.fighters) if (!f.down() && level.getEntity(f.id) instanceof VillageFolkEntity e && e.isAlive()) carriers.add(e);
        return sack(level, v, w, carriers);
    }

    /** The sack of {@code v} by {@code w}, its stores carried off by these. */
    static String sack(ServerLevel level, Villages.Village v, Villages.Village w, List<VillageFolkEntity> carriers) {
        long day = level.getDayTime() / 24000L;
        int took = 0;
        if (!carriers.isEmpty()) {
            int k = 0;
            for (BlockPos p : Villages.storeChests(level, v.id())) {
                if (!(level.getBlockEntity(p) instanceof Container box)) continue;
                for (int i = 0; i < box.getContainerSize(); i++) {
                    ItemStack s = box.getItem(i);
                    if (s.isEmpty() || i % 3 != 0) continue;                          // a third of what there is, slot by slot
                    VillageFolkEntity e = carriers.get(k++ % carriers.size());
                    ItemStack load = s.copy();
                    ItemStack left = e.insertItem(load);
                    int carried = s.getCount() - left.getCount();
                    if (carried <= 0) continue;
                    s.shrink(carried);
                    took += carried;
                    PLUNDER.computeIfAbsent(e.getUUID(), x -> new ArrayList<>()).add(load.copyWithCount(carried));
                }
                box.setChanged();
            }
        }
        String vn = Villages.name(v.id()), wn = Villages.name(w.id());
        if (took > 0) {
            Villages.tell(w.id(), day, "our army sacked " + vn + " and carried off " + took + " goods from its stores");
            Villages.tell(v.id(), day, wn + "'s army sacked the town and carried off " + took + " goods from the stores");
        }
        int grown = 0;
        for (AssistantEntity a : Villages.folkOf(v.id())) if (!a.isBaby() && !Spies.held((VillageFolkEntity) a)) grown++;
        if (grown > RUIN_FOLK || Vassals.isVassal(v.id())) return took > 0 ? took + " goods carried off" : "";
        why(v.id(), "was sacked by " + wn + " and left in ruins", w.id());
        scatter(level, v, w, day);
        if (Villages.get(v.id()) == null) return (took > 0 ? took + " goods carried off; " : "") + "the town left in ruins";
        if (Villages.folkOf(v.id()).isEmpty() && Villages.recordedPopulation(v.id()) <= 0) {
            com.jrpetty.mcassistant.ChunkLoad.setLoaded(level, v.id(), v.centre(), com.jrpetty.mcassistant.VillageSpawner.MAX_LOADED_RADIUS, false);
            Villages.forget(v.id());
            return (took > 0 ? took + " goods carried off; " : "") + "the town left in ruins";
        }
        WHY.remove(v.id());                                             // (some stayed: it lives on, sacked)
        return took > 0 ? took + " goods carried off" : "";
    }

    /**
     * The folk of a sacked town with too few to keep it: each household to the neighbour that will take it in (as the
     * homeless after a raid go: JobSeekers.refuge), and those nobody will take carried off by the victors as prisoners,
     * offered a place there as soon as the town is gone.
     */
    static void scatter(ServerLevel level, Villages.Village v, Villages.Village w, long day) {
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(v.id())) if (a instanceof VillageFolkEntity f && !Spies.held(f) && !f.isShowcase()) folk.add(f);
        java.util.Set<UUID> placed = new java.util.HashSet<>();
        for (VillageFolkEntity f : folk) {
            if (f.isBaby() || placed.contains(f.getUUID())) continue;
            List<VillageFolkEntity> h = new ArrayList<>();
            for (VillageFolkEntity m : JobSeekers.household(f)) if (placed.add(m.getUUID())) h.add(m);
            if (h.isEmpty()) continue;
            Villages.Village to = JobMarket.refuge(level, v, h.size());
            if (to != null && !to.id().equals(w.id())) {
                JobSeekers.refuge(level, h, v, to, "homeless after the sack of " + Villages.name(v.id()));
                Villages.tell(v.id(), day, h.get(0).displayNameCap() + (h.size() > 1 ? " and " + (h.size() - 1) + " more" : "") + " fled the ruins for " + Villages.name(to.id()));
                continue;
            }
            for (VillageFolkEntity m : h) {
                Spies.takeInBattle(w.id(), m.getUUID(), m.displayNameCap(), v.id(), day);
                Prisoners.taken(level, m.getUUID(), m.displayNameCap(), v.id(), w.id(), "NONE", Prisoners.worth("NONE", false), "the sack of " + Villages.name(v.id()), day);
            }
        }
        for (VillageFolkEntity f : folk) {                                       // a child left alone goes with the victors' care
            if (placed.contains(f.getUUID())) continue;
            Spies.takeInBattle(w.id(), f.getUUID(), f.displayNameCap(), v.id(), day);
            Prisoners.taken(level, f.getUUID(), f.displayNameCap(), v.id(), w.id(), "NONE", Prisoners.worth("NONE", false), "the sack of " + Villages.name(v.id()), day);
        }
        // Those carried off are on the victors' roll from now: the town they came from is about to be gone.
        for (Prisoners.Pow p : Prisoners.ofTown(v.id())) {
            VillageFolkEntity f = Civics.find(level, p.who);
            if (f != null && p.holder.equals(w.id())) Prisoners.offer(level, w, p, f, day, true);
        }
    }

    /** [Campaigns.standDown] The victors home with what they carried off: into their own stores. */
    static void unload(ServerLevel level, Armies.Army a) {
        List<UUID> who = new ArrayList<>();
        for (Armies.Fighter f : a.fighters) who.add(f.id);
        unload(level, Villages.get(a.town), who);
    }

    static void unload(ServerLevel level, @Nullable Villages.Village v, java.util.Collection<UUID> who) {
        if (v == null || PLUNDER.isEmpty()) return;
        for (UUID id : who) {
            List<ItemStack> carried = PLUNDER.remove(id);
            if (carried == null || !(level.getEntity(id) instanceof VillageFolkEntity e)) continue;
            for (ItemStack want : carried) {
                int n = e.removeMatching(s -> ItemStack.isSameItemSameComponents(s, want), want.getCount());
                if (n > 0) Crafts.store(level, v, want.copyWithCount(n));
            }
        }
    }

    // ------------------------------------------------------------------ abandoned (AfterWar, daily)

    /** A town standing empty with nobody on its roll, for days: let go, and it falls to ruin. */
    static void abandoned(ServerLevel level, Villages.Village v, long day) {
        if (!live) return;
        UUID id = v.id();
        if (!Villages.folkOf(id).isEmpty() || Villages.recordedPopulation(id) > 0 || Founding.siteNear(level, v.centre(), 64) != null) {
            Ledger.forget(id, "ruin.empty");
            return;
        }
        String since = Ledger.note(id, "ruin.empty");
        if (since == null || since.isEmpty()) {
            Ledger.note(id, "ruin.empty", Long.toString(day));
            return;
        }
        if (day - WarBooks.num(since, day) < ABANDONED_DAYS) return;
        why(id, "was abandoned, its last folk gone", null);
        com.jrpetty.mcassistant.ChunkLoad.setLoaded(level, id, v.centre(), com.jrpetty.mcassistant.VillageSpawner.MAX_LOADED_RADIUS, false);
        Villages.forget(id);
    }

    // ------------------------------------------------------------------ decay (AfterWar, every second)

    /** A second's decay of every ruin of this world whose ground is awake. */
    static void decay(ServerLevel level, Random rng) {
        if (!live) return;
        long day = level.getDayTime() / 24000L;
        for (Ruin r : all()) {
            if (!r.dim().equals(level.dimension()) || !level.isLoaded(r.centre())) continue;
            int owed = (int) Math.min(Integer.MAX_VALUE, (day - r.day()) * PER_DAY) - r.decayed();
            if (owed <= 0) continue;
            List<BuildGoal.Placement> plan = plan(r);
            int most = (int) (plan.size() * MOST_SHARE);
            if (r.decayed() >= most || plan.isEmpty()) continue;
            int done = 0;
            for (int tries = 0; tries < PER_PASS * 4 && done < Math.min(PER_PASS, owed); tries++) {
                BuildGoal.Placement p = plan.get(rng.nextInt(plan.size()));
                done += decayOne(level, p, rng);
            }
            if (done > 0) bump(r.id(), "decayed", done);
        }
    }

    /** The cells of a ruin's drawings that may decay (not its chests, its storehouse, anything that holds things). */
    static List<BuildGoal.Placement> plan(Ruin r) {
        return PLANS.computeIfAbsent(r.id(), k -> {
            List<BuildGoal.Placement> out = new ArrayList<>();
            for (int i = 0; i < r.buildings().size(); i++) {
                Ledger.Building b = r.buildings().get(i);
                String design = i < r.designs().size() && !r.designs().get(i).isEmpty() ? r.designs().get(i) : b.structure();
                try {
                    for (BuildGoal.Placement p : BuildGoal.plan(design, b.anchor(), b.facing(), 13)) {
                        switch (p.part()) {
                            case BLOCK, WINDOW, DOOR, TORCH, LANTERN, BED, FENCE, GATE -> out.add(p);
                            default -> { }
                        }
                    }
                } catch (RuntimeException e) {
                    LOG.debug("[MCA-RUIN] no plan for {}: {}", design, e.toString());
                }
            }
            return out;
        });
    }

    /** One block of a drawing gone to ruin, if it is as the town built it. Returns 1 if anything changed. */
    static int decayOne(ServerLevel level, BuildGoal.Placement p, Random rng) {
        BlockPos at = p.pos();
        if (!level.isLoaded(at)) return 0;
        BlockState st = level.getBlockState(at);
        if (st.isAir() || st.hasBlockEntity() || !Restore.serves(st, p)) return 0;      // only what the town built, as it built it
        BlockState to = null;
        switch (p.part()) {
            case WINDOW, TORCH, LANTERN, BED, GATE -> to = Blocks.AIR.defaultBlockState();
            case DOOR -> {
                if (st.getBlock() instanceof DoorBlock && st.getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER) at = at.below();
                to = Blocks.AIR.defaultBlockState();
            }
            case FENCE -> { if (rng.nextInt(3) == 0) to = Blocks.AIR.defaultBlockState(); }
            default -> to = weathered(st, p, rng);
        }
        if (to == null) return 0;
        boolean roof = p.style() == Blueprints.Style.ROOF_STAIR || p.style() == Blueprints.Style.ROOF_SLAB || p.style() == Blueprints.Style.ROOF_STAIR_TOP
            || p.style() == Blueprints.Style.ROOF_SLAB_TOP || p.style() == Blueprints.Style.ROOF_BLOCK;
        level.setBlock(at, to, Block.UPDATE_ALL);
        if (to.isAir() && roof && rng.nextBoolean()) fallen(level, at, st);
        if (!to.isAir() && rng.nextInt(3) == 0) vine(level, at);
        if (roof && rng.nextInt(3) == 0) cobweb(level, at);
        return 1;
    }

    /** Stone cracks and mosses over, cobble mosses, rotten timber and loose brick fall out; the roof comes in. */
    @Nullable
    static BlockState weathered(BlockState st, BuildGoal.Placement p, Random rng) {
        Block b = st.getBlock();
        if (b == Blocks.STONE_BRICKS) return (rng.nextBoolean() ? Blocks.CRACKED_STONE_BRICKS : Blocks.MOSSY_STONE_BRICKS).defaultBlockState();
        if (b == Blocks.COBBLESTONE) return Blocks.MOSSY_COBBLESTONE.defaultBlockState();
        if (b == Blocks.STONE_BRICK_STAIRS) return Blocks.MOSSY_STONE_BRICK_STAIRS.withPropertiesOf(st);
        if (b == Blocks.STONE_BRICK_SLAB) return Blocks.MOSSY_STONE_BRICK_SLAB.withPropertiesOf(st);
        if (b == Blocks.STONE_BRICK_WALL) return Blocks.MOSSY_STONE_BRICK_WALL.withPropertiesOf(st);
        if (b == Blocks.COBBLESTONE_STAIRS) return Blocks.MOSSY_COBBLESTONE_STAIRS.withPropertiesOf(st);
        if (b == Blocks.COBBLESTONE_SLAB) return Blocks.MOSSY_COBBLESTONE_SLAB.withPropertiesOf(st);
        if (b == Blocks.COBBLESTONE_WALL) return Blocks.MOSSY_COBBLESTONE_WALL.withPropertiesOf(st);
        if (b == Blocks.DEEPSLATE_BRICKS) return Blocks.CRACKED_DEEPSLATE_BRICKS.defaultBlockState();
        if (b == Blocks.DEEPSLATE_TILES) return Blocks.CRACKED_DEEPSLATE_TILES.defaultBlockState();
        if (b == Blocks.POLISHED_BLACKSTONE_BRICKS) return Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
        if (b == Blocks.NETHER_BRICKS) return Blocks.CRACKED_NETHER_BRICKS.defaultBlockState();
        boolean roof = p.style() == Blueprints.Style.ROOF_STAIR || p.style() == Blueprints.Style.ROOF_SLAB || p.style() == Blueprints.Style.ROOF_STAIR_TOP
            || p.style() == Blueprints.Style.ROOF_SLAB_TOP || p.style() == Blueprints.Style.ROOF_BLOCK;
        if (roof) return rng.nextInt(3) == 0 ? Blocks.AIR.defaultBlockState() : null;
        if (p.style() == Blueprints.Style.FOUNDATION || p.style() == Blueprints.Style.FLOOR || p.style() == Blueprints.Style.SOIL) return null;   // the floor stays
        if (st.is(BlockTags.PLANKS) || st.is(BlockTags.LOGS)) return rng.nextInt(3) == 0 ? Blocks.AIR.defaultBlockState() : null;
        return rng.nextInt(5) == 0 ? Blocks.AIR.defaultBlockState() : null;
    }

    /** A piece of the fallen roof, lying on the floor below where it came down. */
    static void fallen(ServerLevel level, BlockPos from, BlockState piece) {
        BlockPos p = from.below();
        for (int i = 0; i < 8 && level.getBlockState(p).isAir(); i++) p = p.below();
        BlockPos rest = p.above();
        if (rest.equals(from) || !level.getBlockState(rest).isAir() || !level.getBlockState(p).isFaceSturdy(level, p, Direction.UP)) return;
        level.setBlock(rest, piece, Block.UPDATE_ALL);
    }

    /** A vine up the outside of a wall that has weathered. */
    static void vine(ServerLevel level, BlockPos wall) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos out = wall.relative(d);
            if (!level.getBlockState(out).isAir() || !level.getBlockState(wall).isFaceSturdy(level, wall, d)) continue;
            level.setBlock(out, Blocks.VINE.defaultBlockState().setValue(VineBlock.getPropertyForFace(d.getOpposite()), true), Block.UPDATE_ALL);
            return;
        }
    }

    /** A cobweb in the corner under what is left of the roof. */
    static void cobweb(ServerLevel level, BlockPos roof) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos under = roof.relative(d).below();
            if (level.getBlockState(under).isAir() && !level.getBlockState(under.above()).isAir() && level.getBlockState(under.below()).isAir()) {
                level.setBlock(under, Blocks.COBWEB.defaultBlockState(), Block.UPDATE_ALL);
                return;
            }
        }
    }

    static void bump(UUID id, String key, int by) {
        CompoundTag t = AfterWarBooks.ruins().getCompound(id.toString());
        if (t.isEmpty()) return;
        t.putInt(key, t.getInt(key) + by);
        AfterWarBooks.changed();
    }

    // ------------------------------------------------------------------ what is left: spoiling, searching (AfterWar)

    /** A day of the ruin: some of the food in its stores spoils, meat and fish to rotten flesh, the rest to nothing. */
    static void spoil(ServerLevel level, Random rng) {
        long day = level.getDayTime() / 24000L;
        for (Ruin r : all()) {
            if (!r.dim().equals(level.dimension())) continue;
            CompoundTag t = AfterWarBooks.ruins().getCompound(r.id().toString());
            if (t.getLong("spoiled") >= day || !level.isLoaded(r.centre())) continue;
            int days = (int) Math.max(1, day - Math.max(r.day(), t.getLong("spoiled")));
            t.putLong("spoiled", day);
            for (BlockPos c : r.chests()) {
                if (!level.isLoaded(c) || !(level.getBlockEntity(c) instanceof Container box)) continue;
                for (int i = 0; i < box.getContainerSize(); i++) {
                    ItemStack s = box.getItem(i);
                    if (s.isEmpty() || s.get(net.minecraft.core.component.DataComponents.FOOD) == null || s.is(Items.ROTTEN_FLESH)) continue;
                    if (s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE) || s.is(Items.HONEY_BOTTLE)) continue;   // these keep
                    int gone = Math.min(s.getCount(), Math.max(1, s.getCount() * Math.min(days, 5) / 10));
                    boolean flesh = s.is(Items.BEEF) || s.is(Items.PORKCHOP) || s.is(Items.CHICKEN) || s.is(Items.MUTTON) || s.is(Items.RABBIT)
                        || s.is(Items.COD) || s.is(Items.SALMON) || s.is(Items.COOKED_BEEF) || s.is(Items.COOKED_PORKCHOP) || s.is(Items.COOKED_CHICKEN)
                        || s.is(Items.COOKED_MUTTON) || s.is(Items.COOKED_RABBIT) || s.is(Items.COOKED_COD) || s.is(Items.COOKED_SALMON);
                    if (!flesh) {
                        s.shrink(gone);
                    } else if (gone >= s.getCount()) {
                        box.setItem(i, new ItemStack(Items.ROTTEN_FLESH, gone));
                    } else {
                        // Part of it gone off: the rotten flesh in the first room there is (with no room, it keeps a while yet).
                        for (int j = 0; j < box.getContainerSize(); j++) {
                            if (!box.getItem(j).isEmpty()) continue;
                            box.setItem(j, new ItemStack(Items.ROTTEN_FLESH, gone));
                            s.shrink(gone);
                            break;
                        }
                    }
                }
                box.setChanged();
            }
            AfterWarBooks.changed();
        }
    }

    /** A player among the ruins (AfterWar, every second): told where it stands, once a visit, to a bell's last note. */
    static void visitors(ServerLevel level) {
        List<Ruin> ruins = all();
        if (ruins.isEmpty()) return;
        for (ServerPlayer p : level.players()) {
            Ruin near = null;
            for (Ruin r : ruins) {
                if (r.dim().equals(level.dimension()) && p.blockPosition().distSqr(r.centre()) < 40 * 40) { near = r; break; }
            }
            UUID was = AMONG.get(p.getUUID());
            if (near == null) {
                if (was != null) AMONG.remove(p.getUUID());
                continue;
            }
            if (near.id().equals(was)) continue;
            AMONG.put(p.getUUID(), near.id());
            p.sendSystemMessage(Component.literal("The ruins of " + near.name() + ". It " + near.how() + " on day " + near.day()
                + ". Its stores lie open to whoever finds them.").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            level.playSound(null, p.blockPosition(), SoundEvents.AMBIENT_CAVE.value(), SoundSource.AMBIENT, 0.6F, 0.8F);
        }
    }

    /** A ruin's store chest opened by a player: its search counted (for the board), and a word on what is left. */
    static void searched(ServerLevel level, ServerPlayer p, BlockPos at) {
        for (Ruin r : all()) {
            if (!r.dim().equals(level.dimension()) || !r.chests().contains(at)) continue;
            bump(r.id(), "searched", 1);
            p.displayClientMessage(Component.literal("What is left of " + r.name() + "'s stores.").withStyle(ChatFormatting.GRAY), true);
            return;
        }
    }

    // ------------------------------------------------------------------ seen: the board, the scouts, the talk, the books

    /** [VillageBoards.compose] The board of a town that is gone: its ruin's lines, or null if it left none. */
    @Nullable
    public static List<String> board(ServerLevel level, @Nullable UUID id) {
        Ruin r = of(id);
        if (r == null) return null;
        List<String> out = new ArrayList<>();
        out.add("TH|The ruins of " + r.name());
        out.add("SM|It " + r.how() + " on day " + r.day() + ".  ·  Day " + (level.getDayTime() / 24000L + 1));
        out.add("LH|What is left");
        int n = r.buildings().size();
        List<BuildGoal.Placement> plan = plan(r);
        int share = plan.isEmpty() ? 0 : (int) Math.round(100.0 * r.decayed() / Math.max(1, plan.size()));
        out.add("LW|" + n + (n == 1 ? " building, " : " buildings, ") + (share < 5 ? "standing as they were left" : share < 15 ? "beginning to fall in"
            : share < 25 ? "falling in" : "mostly fallen in") + ".");
        out.add("LM|Its stores lie open: " + r.chests().size() + (r.chests().size() == 1 ? " chest" : " chests")
            + (r.searched() > 0 ? ", searched " + r.searched() + (r.searched() == 1 ? " time" : " times") : ", untouched") + ".");
        if (r.resettled() != null) out.add("LG|Taken up again: " + Villages.name(r.resettled()) + ".");
        else out.add("LN|A town may rise here again: settlers, or a Village Folk Spawner set down among the ruins, take it up under its old name.");
        out.add("RH|Its last news");
        List<com.jrpetty.mcassistant.village.Chronicle.Entry> e = com.jrpetty.mcassistant.village.Chronicle.of(r.id());
        for (int i = Math.max(0, e.size() - 4); i < e.size(); i++) out.add("RM|Day " + e.get(i).day() + ": " + e.get(i).text() + ".");
        return out;
    }

    /** [Scouts.survey] A ruin within sight of a scout goes into its atlas. */
    static void scout(VillageFolkEntity f, Scouts.Expedition e, BlockPos here, long day, String by) {
        for (Ruin r : all()) {
            if (!r.dim().equals(f.level().dimension()) || Scouts.flat(here, r.centre()) > 96 * 96) continue;
            if (Scouts.find(f, e, new Scouts.Find(Scouts.Kind.RUIN, "the ruins of " + r.name(), r.centre(), day, by))) {
                FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "The ruins of " + r.name() + "... I'd heard, but to see it.", "That was " + r.name() + ", once."));
            }
        }
    }

    /** The War page: the ruins this town knows of, nearest first. */
    static List<String> reportLines(ServerLevel level, UUID id) {
        Villages.Village v = Villages.get(id);
        List<String> out = new ArrayList<>();
        if (v == null) return out;
        List<Ruin> rs = new ArrayList<>(all());
        rs.removeIf(r -> !r.dim().equals(v.dim()) || r.centre().distSqr(v.centre()) > 800.0 * 800.0);
        rs.sort(java.util.Comparator.comparingDouble(r -> r.centre().distSqr(v.centre())));
        for (Ruin r : rs) {
            if (out.size() >= 6) break;
            out.add("the ruins of " + r.name() + ", " + (int) Math.sqrt(r.centre().distSqr(v.centre())) + " blocks " + Guide.direction(v.centre(), r.centre())
                + ": it " + r.how() + " on day " + r.day());
        }
        return out;
    }

    /** What folk say of the ruins round about (FolkTalk gossip). */
    static List<String> gossip(VillageFolkEntity f) {
        List<String> out = new ArrayList<>();
        UUID home = f.ownerId();
        Villages.Village v = home == null ? null : Villages.get(home);
        if (v == null) return out;
        for (Ruin r : all()) {
            if (!r.dim().equals(v.dim()) || r.centre().distSqr(v.centre()) > 600.0 * 600.0) continue;
            String dir = Guide.direction(v.centre(), r.centre());
            out.add(FolkTalk.pick(f.getRandom(), "You know the ruins " + dir + " of here? That was " + r.name() + ". It " + r.how() + ". Nobody goes there now.",
                "They say there's still food in the stores at " + r.name() + ", what hasn't rotted. I wouldn't go. Not after what happened.",
                "My gran used to trade at " + r.name() + ". Look at it now: moss on every wall.",
                "If anybody ever builds at " + r.name() + " again, I hope they keep the name."));
            if (out.size() >= 2) break;
        }
        return out;
    }

    // ------------------------------------------------------------------ rising again

    /** [Colonies.spot] A ruin within a colony's reach, before open ground: its heart, or null. */
    @Nullable
    public static BlockPos colonySite(ServerLevel level, Villages.Village mother) {
        if (!live) return null;
        Ruin best = null;
        double bd = Double.MAX_VALUE;
        for (Ruin r : all()) {
            if (!r.dim().equals(level.dimension())) continue;
            double d = r.centre().distSqr(mother.centre());
            if (d < (double) COLONY_NEAR * COLONY_NEAR || d > (double) COLONY_FAR * COLONY_FAR) continue;
            if (Villages.nearest(level, r.centre(), Villages.VILLAGE_RANGE * 2) != null) continue;
            if (d < bd) { bd = d; best = r; }
        }
        return best == null ? null : best.centre();
    }

    /** Is this spot among a standing ruin (the colonists' ground is not moved off it)? */
    public static boolean isSite(BlockPos at) {
        for (Ruin r : all()) if (r.centre().distSqr(at) < (double) RESETTLE * RESETTLE) return true;
        return false;
    }

    /**
     * [Villages.found] A town founded among a ruin takes it up: the old name ("Ashford rises again"), the old buildings
     * onto its register for its builders to put right (Restore), the old stores' leftovers its own.
     */
    public static void founded(ServerLevel level, Villages.Village v) {
        Ruin best = null;
        for (Ruin r : all()) {
            if (r.dim().equals(v.dim()) && r.centre().distSqr(v.centre()) < (double) RESETTLE * RESETTLE) { best = r; break; }
        }
        if (best == null) return;
        long day = level.getDayTime() / 24000L;
        Ledger.note(v.id(), "name", best.name());
        Ledger.note(v.id(), "ruin.of", best.id().toString());
        int took = 0;
        for (Ledger.Building b : best.buildings()) {
            if (b.structure().equals("colony")) continue;
            Ledger.built(v.id(), b.structure(), b.anchor(), b.facing());
            Villages.noteProject(v.id(), b.structure(), level.getGameTime());
            took++;
        }
        CompoundTag t = AfterWarBooks.ruins().getCompound(best.id().toString());
        t.putUUID("resettled", v.id());
        t.putLong("resettledDay", day);
        AfterWarBooks.changed();
        PLANS.remove(best.id());
        Villages.tell(v.id(), day, best.name() + " rises again: settlers took up its ruins, " + took + " old buildings to put right, on day " + day);
        for (Villages.Village o : Villages.every()) {
            if (o.id().equals(v.id()) || !Ledger.knowEachOther(o.id(), best.id())) continue;
            Villages.tell(o.id(), day, "word came that " + best.name() + " rises again: settlers have taken up its ruins");
        }
        Raids.tellNear(level, v.centre(), 200, Component.literal(best.name() + " rises again!").withStyle(ChatFormatting.GOLD), false);
        LOG.info("[MCA-RUIN] {} rises again at {}: {} old buildings taken up", best.name(), v.centre().toShortString(), took);
    }

    // ------------------------------------------------------------------ admin

    /** [admin] A town made a ruin now: its folk scattered as after a sack (or carried off by nobody: they go where they can). */
    public static String ruinNow(ServerLevel level, Villages.Village v) {
        long day = level.getDayTime() / 24000L;
        String name = Villages.name(v.id());
        why(v.id(), "was given up by decree", null);              // (the last folk sent away lets it go as it goes)
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(v.id())) if (a instanceof VillageFolkEntity f && !f.isShowcase()) folk.add(f);
        java.util.Set<UUID> placed = new java.util.HashSet<>();
        int moved = 0, gone = 0;
        for (VillageFolkEntity f : folk) {
            if (placed.contains(f.getUUID())) continue;
            List<VillageFolkEntity> h = new ArrayList<>();
            for (VillageFolkEntity m : JobSeekers.household(f)) if (placed.add(m.getUUID())) h.add(m);
            if (h.isEmpty()) continue;
            Villages.Village to = JobMarket.refuge(level, v, h.size());
            if (to != null) {
                JobSeekers.refuge(level, h, v, to, "homeless when " + name + " was given up");
                moved += h.size();
            } else {
                for (VillageFolkEntity m : h) { m.discard(); gone++; }
            }
        }
        com.jrpetty.mcassistant.ChunkLoad.setLoaded(level, v.id(), v.centre(), com.jrpetty.mcassistant.VillageSpawner.MAX_LOADED_RADIUS, false);
        if (Villages.get(v.id()) != null) {
            why(v.id(), "was given up by decree", null);
            Villages.forget(v.id());
        }
        return name + " is a ruin now (" + moved + " folk went to neighbours, " + gone + " were sent away)";
    }

    /** [admin] A ruin's decay brought on by so many days (it falls in as it would have). Blocks changed. */
    public static int decayNow(ServerLevel level, UUID id, int days) {
        Ruin r = of(id);
        if (r == null) return 0;
        CompoundTag t = AfterWarBooks.ruins().getCompound(id.toString());
        t.putLong("day", r.day() - days);
        AfterWarBooks.changed();
        boolean was = live;
        live = true;
        Random rng = new Random(id.getLeastSignificantBits() ^ level.getGameTime());
        int before = r.decayed();
        for (int i = 0; i < days * PER_DAY / PER_PASS + 1; i++) decay(level, rng);
        live = was;
        Ruin now = of(id);
        return now == null ? 0 : now.decayed() - before;
    }

    /** The nearest standing ruin to a spot, within so far, or null. */
    @Nullable
    public static Ruin nearest(ServerLevel level, BlockPos at, int within) {
        Ruin best = null;
        double bd = (double) within * within;
        for (Ruin r : all()) {
            if (!r.dim().equals(level.dimension())) continue;
            double d = r.centre().distSqr(at);
            if (d < bd) { bd = d; best = r; }
        }
        return best;
    }

    // ------------------------------------------------------------------ tests

    public static void fallForTests(ServerLevel level, Villages.Village v, String how) {
        why(v.id(), how, null);
        Villages.forget(v.id());
    }

    public static void spoilForTests(ServerLevel level) {
        spoil(level, new Random(1));
    }

    public static String sackForTests(ServerLevel level, Villages.Village v, Villages.Village w, List<VillageFolkEntity> carriers) {
        return sack(level, v, w, carriers);
    }

    public static void unloadForTests(ServerLevel level, Villages.Village v, List<VillageFolkEntity> carriers) {
        List<UUID> who = new ArrayList<>();
        for (VillageFolkEntity f : carriers) who.add(f.getUUID());
        unload(level, v, who);
    }

    public static void abandonedForTests(ServerLevel level, Villages.Village v, long day) {
        abandoned(level, v, day);
    }
}
