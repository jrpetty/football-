package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [featbuild] Parks and monuments: statue gardens by the square to the town's most notable folk, and the good they do the
 * folk round them.
 *
 * <p><b>Who.</b> A folk's standing in its town is reckoned from what the rest of the town thinks of it (the others' liking
 * for it, Social), its years at its trade (its level), what it has done (its deeds, every block mined and crop brought
 * in), and its offices (the elder; a warden). The most notable who has no statue yet, once it stands well above the
 * rest (a score of forty), is the town's next to be honoured.
 *
 * <p><b>The garden.</b> A Stone Age town of sixteen with somebody to honour puts a statue garden on the builders' list
 * (Villages, with the amenities), on a lot facing the square (TownPlan: a civic spot, never a home's): a plinth of dressed
 * stone in the middle, flower beds round it, benches facing it, lamp posts at the ways in and two ponds in stone basins
 * at the back (blueprints/garden.txt). More of them as the town grows and prospers: one more for every sixteen folk,
 * and one more for a treasury of three hundred, four at most. Its keepers tend it as they tend the park (Park: the trees
 * at its corners, its paths, its flowers and lamps; its ground made level first by ParkGround), and the town's folk
 * spend their free time in it as in the park: a sit on a bench looking at the statue, a stroll, a chat.
 *
 * <p><b>The statue.</b> Once the garden stands, the town's mason raises the statue on its plinth: the honoured folk in
 * stone (an armour stand dressed in stone-grey, the tools of its trade in its hand, facing the street), and a plaque on the
 * plinth's front with its name, its trade and what it is known for. Out of the stores: an armour stand (or three planks
 * and a smooth stone slab for one) and a block of stone to carve; the plaque a sign. The chronicle says who and why.
 *
 * <p><b>The ponds.</b> Filled from the stores' water (a bucket, given back empty) only where the water will stay: a floor
 * under it and stone or water on every side. A rim stone knocked out empties the pond before a drop can run (on the
 * break, and at every look), and its keepers put the stone back before it is filled again. A lily pad on each.
 *
 * <p><b>The good of it.</b> A folk standing within sixteen blocks of a statue, or living within twenty-four of one, is the
 * happier (three); a folk whose own statue it is, or its kin's, prouder still (five). A home by a garden is "by the park"
 * as a home by the park is (Quarters).
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Monuments {

    private Monuments() {}

    static final String STATUES = "monuments.statues";
    /** The score a folk must have to be honoured. */
    static final int NOTABLE = 40;
    static final int NEAR = 16, HOME_NEAR = 24;
    static final int MOOD = 3, KIN_MOOD = 5;
    /** The ponds' water cells in a garden's drawing: {across, toward the back}. */
    static final int[][] POND = { { -3, 4 }, { -2, 4 }, { 2, 4 }, { 3, 4 } };
    static final int STONE_GREY = 0x8E8E86;

    private static volatile boolean standDown;

    public static void standDownForTests(boolean down) {
        standDown = down;
    }

    public static void resetForTests() {
        LOOKED.clear();
    }

    // ------------------------------------------------------------------ who is notable

    /** A folk's standing in its town: what the rest think of it, its years at its trade, its deeds, its offices. */
    public static int score(VillageFolkEntity f, List<VillageFolkEntity> town) {
        if (f.isBaby()) return 0;
        int liking = 0, n = 0;
        for (VillageFolkEntity o : town) {
            if (o == f || o.isBaby()) continue;
            liking += o.life().affinity(f.getUUID());
            n++;
        }
        int s = n == 0 ? 0 : Math.max(-20, Math.min(30, liking / n));
        s += f.veteranLevel() * 2;
        int deeds = 0;
        for (AssistantEntity.Deed d : AssistantEntity.Deed.values()) deeds += f.deedCount(d);
        s += Math.min(40, deeds / 40);
        if (f.isElder()) s += 20;
        return s;
    }

    /** What a folk is known for, in a few words: its biggest tally of deeds. */
    static String knownFor(VillageFolkEntity f) {
        AssistantEntity.Deed best = null;
        int most = 0;
        for (AssistantEntity.Deed d : AssistantEntity.Deed.values()) {
            int c = f.deedCount(d);
            if (c > most) { most = c; best = d; }
        }
        if (best == null || most < 10) return f.isElder() ? "the town's elder" : "a life of good work";
        return String.format(java.util.Locale.ROOT, "%,d %s", most, best.label);
    }

    static String trade(VillageFolkEntity f) {
        String t = f.stationTask() == AssistantEntity.StationTask.NONE ? "townsfolk" : f.stationTask().name().toLowerCase(java.util.Locale.ROOT);
        String rank = f.veteranLevel() >= 25 ? "master " : f.veteranLevel() >= 10 ? "" : "young ";
        return rank + t;
    }

    /** The town's folk, grown, best first, with their scores; those already honoured left out. */
    static List<Object[]> candidates(UUID town) {
        List<VillageFolkEntity> all = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(town)) if (a instanceof VillageFolkEntity f && f.isAlive()) all.add(f);
        List<String> honoured = new ArrayList<>();
        for (String[] st : statues(town)) honoured.add(st[1]);
        List<Object[]> out = new ArrayList<>();
        for (VillageFolkEntity f : all) {
            if (f.isBaby() || honoured.contains(f.getStringUUID())) continue;
            out.add(new Object[]{ f, score(f, all) });
        }
        out.sort((a, b) -> Integer.compare((Integer) b[1], (Integer) a[1]));
        return out;
    }

    @Nullable
    static VillageFolkEntity next(UUID town) {
        List<Object[]> c = candidates(town);
        if (c.isEmpty() || (Integer) c.get(0)[1] < NOTABLE) return null;
        return (VillageFolkEntity) c.get(0)[0];
    }

    // ------------------------------------------------------------------ the gardens wanted

    static List<Ledger.Building> gardens(UUID town) {
        List<Ledger.Building> out = new ArrayList<>();
        for (Ledger.Building b : Ledger.buildings(town)) if (b.structure().equals(Park.GARDEN)) out.add(b);
        return out;
    }

    /** How many gardens a town of this size and wealth keeps. */
    public static int gardensFor(int folk, int coins) {
        if (folk < 16) return 0;
        return Math.min(4, (folk - 16) / 16 + 1 + (coins >= 300 ? 1 : 0));
    }

    /** Does the town want another statue garden now: in stone, somebody to honour, fewer than its size and wealth keep? */
    public static boolean wanted(UUID town, int folk) {
        if (standDown) return false;
        if (Villages.ageOf(town).ordinal() < Villages.Age.STONE.ordinal()) return false;
        int have = gardens(town).size();
        if (have >= gardensFor(folk, Ledger.coins(town))) return false;
        // Every garden has its statue (or one is on its way), and somebody waits to be honoured.
        for (Ledger.Building g : gardens(town)) if (statueAt(town, g.anchor()) == null) return false;
        return next(town) != null;
    }

    public static String why(UUID town) {
        VillageFolkEntity f = next(town);
        return "a statue garden by the square" + (f == null ? "" : ", to raise a statue to " + f.displayNameCap() + " (" + knownFor(f) + ")")
            + ": benches, flower beds and ponds, somewhere to sit of an evening";
    }

    // ------------------------------------------------------------------ the statues kept

    /** The statues: {garden anchor, folk uuid, name, day, plaque}. */
    static List<String[]> statues(UUID town) {
        List<String[]> out = new ArrayList<>();
        String s = Ledger.note(town, STATUES);
        if (s == null || s.isEmpty()) return out;
        for (String part : s.split("\n")) {
            String[] p = part.split(";", -1);
            if (p.length >= 5) out.add(p);
        }
        return out;
    }

    @Nullable
    static String[] statueAt(UUID town, BlockPos anchor) {
        for (String[] s : statues(town)) if (s[0].equals(Long.toString(anchor.asLong()))) return s;
        return null;
    }

    static void record(UUID town, BlockPos anchor, VillageFolkEntity f, long day, String plaque) {
        List<String> parts = new ArrayList<>();
        for (String[] s : statues(town)) parts.add(String.join(";", s));
        parts.add(anchor.asLong() + ";" + f.getStringUUID() + ";" + f.displayNameCap().replace(';', ' ') + ";" + day + ";" + plaque.replace(';', ' ').replace('\n', ' '));
        Ledger.note(town, STATUES, String.join("\n", parts));
    }

    // ------------------------------------------------------------------ the round (every second, Raids)

    private static final Map<UUID, Long> LOOKED = new ConcurrentHashMap<>();

    static void tick(ServerLevel level, Villages.Village v) {
        if (standDown) return;
        UUID id = v.id();
        long now = level.getGameTime();
        Long last = LOOKED.get(id);
        if (last != null && now - last < 100L && now >= last) return;
        LOOKED.put(id, now);
        for (Ledger.Building g : gardens(id)) {
            if (!Land.areaLoaded(level, g.anchor(), 7)) continue;
            ponds(level, v, g, false);
            if (statueAt(id, g.anchor()) == null) {
                VillageFolkEntity f = next(id);
                if (f != null) raise(level, v, g, f, false);
            } else {
                keepStatue(level, v, g);
            }
        }
    }

    /** The plinth's top (where the statue stands), the plaque's place and the way the statue faces. */
    static BlockPos plinthTop(Ledger.Building g) {
        return g.anchor().above(2);
    }

    /** Raise the statue to this folk on this garden's plinth (out of the stores unless {@code free}). */
    static boolean raise(ServerLevel level, Villages.Village v, Ledger.Building g, VillageFolkEntity f, boolean free) {
        UUID id = v.id();
        BlockPos top = plinthTop(g);
        if (!level.getBlockState(top.below()).isSolid() || !level.getBlockState(top).isAir() || !level.getBlockState(top.above()).isAir()) return false;
        if (!free) {
            boolean stand = Crafts.take(level, v, s -> s.is(Items.ARMOR_STAND), 1);
            if (!stand) {
                if (!Masonry.can(level, v, Items.SMOOTH_STONE_SLAB, 1) || !Crafts.usePlanks(level, v, 3)) return false;
                if (!Masonry.take(level, v, Items.SMOOTH_STONE_SLAB, 1)) {
                    Crafts.store(level, v, new ItemStack(Items.OAK_PLANKS, 3));
                    return false;
                }
            }
            // A block of stone to carve the likeness from: the yard's first, then the stores'.
            if (!StoneYard.forBuild(level, v, Items.COBBLESTONE, 1, "the statue garden") && !Crafts.take(level, v, s -> s.is(Items.COBBLESTONE) || s.is(Items.STONE), 1)) {
                Crafts.store(level, v, new ItemStack(Items.ARMOR_STAND));
                return false;
            }
        }
        Direction front = g.facing().getOpposite();
        ArmorStand st = statue(level, top, front, f);
        if (st == null) return false;
        long day = level.getDayTime() / 24000L;
        String known = knownFor(f);
        String plaque = f.displayNameCap() + " · " + trade(f) + " · " + known;
        // The plaque, on the plinth's front.
        BlockPos at = g.anchor().above().relative(front);
        if ((level.getBlockState(at).isAir() || level.getBlockState(at).canBeReplaced()) && (free || Crafts.sign(level, v))) {
            level.setBlock(at, Blocks.DARK_OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, front), 3);
            if (level.getBlockEntity(at) instanceof SignBlockEntity sign) {
                TownLife.write(sign, new String[]{ f.displayNameCap(), cap(trade(f)), clip(known, 15), "day " + (day + 1) });
            }
        }
        record(id, g.anchor(), f, day, plaque);
        Villages.tell(id, day, "a statue of " + f.displayNameCap() + " was raised in the statue garden (" + known + ")");
        f.persona().remember(day, "the town raised a statue to me", 9);
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "A statue? Of me? I don't know what to say.", "Well. I never thought I'd see my own face in stone.",
            "They've made my nose too big. I love it."));
        FeatHints.hint(level, id, g.anchor(), "parks", "New: a statue of " + f.displayNameCap() + " stands in the statue garden. Folk near it are happier; the board's Parks page has them all.");
        return true;
    }

    /** The likeness: an armour stand dressed in stone-grey, the tools of its trade in hand, fixed to its plinth. */
    @Nullable
    static ArmorStand statue(ServerLevel level, BlockPos top, Direction front, VillageFolkEntity f) {
        ArmorStand st = EntityType.ARMOR_STAND.create(level);
        if (st == null) return null;
        st.moveTo(top.getX() + 0.5, top.getY(), top.getZ() + 0.5, front.toYRot(), 0.0F);
        st.setNoGravity(true);
        st.setInvulnerable(true);
        st.setShowArms(true);
        st.setNoBasePlate(true);
        st.setItemSlot(EquipmentSlot.HEAD, grey(Items.LEATHER_HELMET));
        st.setItemSlot(EquipmentSlot.CHEST, grey(Items.LEATHER_CHESTPLATE));
        st.setItemSlot(EquipmentSlot.LEGS, grey(Items.LEATHER_LEGGINGS));
        st.setItemSlot(EquipmentSlot.FEET, grey(Items.LEATHER_BOOTS));
        ItemStack tool = tool(f);
        if (!tool.isEmpty()) st.setItemSlot(EquipmentSlot.MAINHAND, tool);
        CompoundTag t = st.saveWithoutId(new CompoundTag());
        t.putInt("DisabledSlots", 4144959);
        st.load(t);
        st.addTag("mca_statue");
        st.addTag("mca_folkstatue");
        st.addTag("mca_folk_" + f.getStringUUID());
        st.setCustomName(net.minecraft.network.chat.Component.literal(f.displayNameCap()));
        st.setCustomNameVisible(false);
        return level.addFreshEntity(st) ? st : null;
    }

    private static ItemStack grey(net.minecraft.world.item.Item piece) {
        ItemStack s = new ItemStack(piece);
        s.set(DataComponents.DYED_COLOR, new DyedItemColor(STONE_GREY, false));
        return s;
    }

    /** What the statue holds: the tool of its trade. */
    static ItemStack tool(VillageFolkEntity f) {
        return new ItemStack(switch (f.stationTask()) {
            case FARM -> Items.IRON_HOE;
            case WOOD -> Items.IRON_AXE;
            case MINE -> Items.IRON_PICKAXE;
            case GUARD -> Items.IRON_SWORD;
            case FISH -> Items.FISHING_ROD;
            case RANCH -> Items.SHEARS;
            case SMITH -> Items.IRON_INGOT;
            case HUNT -> Items.BOW;
            case COOK -> Items.BREAD;
            case SHOP, BANK -> Items.EMERALD;
            case SCOUT, CARTOGRAPHER -> Items.COMPASS;
            case HEAL -> Items.GLISTERING_MELON_SLICE;
            default -> Items.BOOK;
        });
    }

    /** A statue knocked off (it is fixed, but a command or a creeper may): put back, out of nothing (the stone stands). */
    static void keepStatue(ServerLevel level, Villages.Village v, Ledger.Building g) {
        BlockPos top = plinthTop(g);
        List<ArmorStand> there = level.getEntitiesOfClass(ArmorStand.class, new AABB(top).inflate(1.5), a -> a.getTags().contains("mca_folkstatue"));
        if (!there.isEmpty()) return;
        String[] s = statueAt(v.id(), g.anchor());
        if (s == null) return;
        try {
            if (level.getEntity(UUID.fromString(s[1])) instanceof VillageFolkEntity f) statue(level, top, g.facing().getOpposite(), f);
        } catch (IllegalArgumentException ignored) { }
    }

    // ------------------------------------------------------------------ the ponds

    /** The ponds' water cells for a garden. */
    static List<BlockPos> pond(Ledger.Building g) {
        List<BlockPos> out = new ArrayList<>();
        Direction back = g.facing(), right = back.getClockWise();
        for (int[] c : POND) out.add(g.anchor().relative(right, c[0]).relative(back, c[1]));
        return out;
    }

    /** Would water here stay: a solid floor under it, and on every side stone or more of the pond? */
    static boolean holds(ServerLevel level, BlockPos p, List<BlockPos> pond) {
        if (!level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)) return false;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = p.relative(d);
            if (pond.contains(n)) continue;
            if (!level.getBlockState(n).isFaceSturdy(level, n, d.getOpposite())) return false;
        }
        return true;
    }

    /**
     * The ponds seen to: emptied at once if a cell would not hold (before a drop runs anywhere); else, by day, filled a
     * cell at a time from the stores' water and given a lily pad. Returns cells filled (negative: emptied).
     */
    static int ponds(ServerLevel level, Villages.Village v, Ledger.Building g, boolean free) {
        List<BlockPos> pond = pond(g);
        // Each basin on its own: a leak empties its own basin.
        int changed = 0;
        for (List<BlockPos> basin : List.of(pond.subList(0, 2), pond.subList(2, 4))) {
            boolean sound = true;
            for (BlockPos p : basin) if (!holds(level, p, basin)) sound = false;
            if (!sound) {
                for (BlockPos p : basin) changed -= drain(level, p);
                continue;
            }
            for (BlockPos p : basin) {
                BlockState st = level.getBlockState(p);
                if (!level.getFluidState(p).isEmpty()) {
                    if (level.getBlockState(p.above()).isAir() && (free || level.getRandom().nextInt(4) == 0) && p.equals(basin.get(0))) {
                        level.setBlock(p.above(), Blocks.LILY_PAD.defaultBlockState(), 3);
                    }
                    continue;
                }
                if (!st.isAir()) continue;
                if (!free && !bucket(level, v)) return changed;
                level.setBlock(p, Blocks.WATER.defaultBlockState(), 3);
                changed++;
                if (!free) return changed;                                 // a bucket a look
            }
        }
        return changed;
    }

    private static int drain(ServerLevel level, BlockPos p) {
        int n = 0;
        if (level.getBlockState(p.above()).is(Blocks.LILY_PAD)) level.setBlock(p.above(), Blocks.AIR.defaultBlockState(), 3);
        if (!level.getFluidState(p).isEmpty() && level.getBlockState(p).getBlock() instanceof LiquidBlock) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
            n++;
        }
        return n;
    }

    /** A bucket of water from the stores (given back empty), or a full one filled from the town's well. */
    private static boolean bucket(ServerLevel level, Villages.Village v) {
        if (Crafts.take(level, v, s -> s.is(Items.WATER_BUCKET), 1)) {
            Crafts.store(level, v, new ItemStack(Items.BUCKET));
            return true;
        }
        // An empty bucket of the stores', filled at the well (the town has one from its first days).
        return Market.stock(level, v.id(), s -> s.is(Items.BUCKET)) > 0 && Villages.hasBuilt(v.id(), "well");
    }

    /** A rim stone of a pond broken: the pond emptied first, so not a drop runs out of the gap. */
    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        BlockPos pos = e.getPos();
        Villages.Village v = Villages.nearest(level, pos, Villages.VILLAGE_RANGE);
        if (v == null) return;
        for (Ledger.Building g : gardens(v.id())) {
            if (Math.abs(pos.getX() - g.anchor().getX()) > 6 || Math.abs(pos.getZ() - g.anchor().getZ()) > 6) continue;
            List<BlockPos> pond = pond(g);
            for (BlockPos p : pond) {
                if (pos.equals(p.below()) || pos.distManhattan(p) == 1 && pos.getY() == p.getY()) {
                    for (BlockPos q : pond) drain(level, q);
                    return;
                }
            }
        }
    }

    // ------------------------------------------------------------------ the good of it (VillageFolkEntity.refreshMood)

    private record Seen(long tick, int bonus, String why) {}
    private static final Map<UUID, Seen> SEEN = new ConcurrentHashMap<>();

    /** Near a statue (standing within sixteen, or living within twenty-four), the happier; prouder for one's own or kin's. */
    public static int mood(VillageFolkEntity f, long day, int m, List<Object[]> why) {
        UUID town = f.ownerId();
        if (town == null || f.isBaby()) return m;
        Seen s = SEEN.get(f.getUUID());
        if (s == null || f.tickCount - s.tick > 200 || f.tickCount < s.tick) {
            s = look(f, town);
            SEEN.put(f.getUUID(), s);
        }
        if (s.bonus > 0) {
            m += s.bonus;
            why.add(new Object[]{ s.why, s.bonus });
        }
        return m;
    }

    private static Seen look(VillageFolkEntity f, UUID town) {
        List<String[]> st = statues(town);
        if (st.isEmpty()) return new Seen(f.tickCount, 0, "");
        BlockPos at = f.blockPosition(), home = f.bedPos();
        boolean near = false, kin = false;
        for (String[] s : st) {
            BlockPos g = BlockPos.of(Long.parseLong(s[0]));
            boolean close = g.closerThan(at, NEAR) || home != null && g.closerThan(home, HOME_NEAR);
            if (s[1].equals(f.getStringUUID()) || kinOf(f, s[1])) kin = true;
            if (close) near = true;
        }
        if (kin) return new Seen(f.tickCount, KIN_MOOD, "kinstatue");
        return near ? new Seen(f.tickCount, MOOD, "statue") : new Seen(f.tickCount, 0, "");
    }

    private static boolean kinOf(VillageFolkEntity f, String other) {
        try {
            UUID o = UUID.fromString(other);
            if (o.equals(f.life().partner())) return true;
            return f.level() instanceof ServerLevel sl && sl.getEntity(o) instanceof VillageFolkEntity k && Courtship.kin(f, k);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** A folk's words for these reasons (FolkTalk.reason). */
    public static String moodWords(VillageFolkEntity f, String why) {
        return why.equals("kinstatue")
            ? FolkTalk.pick(f.getRandom(), "There's a statue of one of ours in the garden. I go and look at it most days.", "They put us in stone. Me, in stone!")
            : FolkTalk.pick(f.getRandom(), "I sat by the statue in the garden a while. It does you good, remembering.", "Lovely, the statue garden. The ponds, the flowers.");
    }

    // ------------------------------------------------------------------ the board

    public static CompoundTag report(ServerLevel level, Villages.Village v) {
        CompoundTag c = new CompoundTag();
        UUID id = v.id();
        int folk = Villages.headcount(id);
        c.putInt("folk", folk);
        c.putInt("coins", Ledger.coins(id));
        c.putInt("gardensFor", gardensFor(folk, Ledger.coins(id)));
        c.putBoolean("stone", Villages.ageOf(id).ordinal() >= Villages.Age.STONE.ordinal());
        ListTag greens = new ListTag();
        for (Ledger.Building b : Park.parks(id)) {
            CompoundTag g = new CompoundTag();
            boolean garden = b.structure().equals(Park.GARDEN);
            g.putString("kind", garden ? "Statue garden" : "Park");
            g.putIntArray("at", new int[]{ b.anchor().getX(), b.anchor().getY(), b.anchor().getZ() });
            int dx = b.anchor().getX() - v.centre().getX(), dz = b.anchor().getZ() - v.centre().getZ();
            g.putInt("dist", (int) Math.round(Math.sqrt(dx * (double) dx + dz * (double) dz)));
            int there = 0;
            for (AssistantEntity a : Villages.folkOf(id)) {
                if (a instanceof VillageFolkEntity f && Park.there(f) && f.blockPosition().closerThan(b.anchor(), 9)) there++;
            }
            g.putInt("there", there);
            if (garden) {
                String[] s = statueAt(id, b.anchor());
                g.putString("statue", s == null ? "" : s[2]);
                g.putString("plaque", s == null ? "" : s[4]);
                int water = 0;
                for (BlockPos p : pond(b)) if (level.isLoaded(p) && !level.getFluidState(p).isEmpty()) water++;
                g.putInt("water", water);
            } else {
                g.putString("status", Park.status(level, v));
            }
            greens.add(g);
        }
        c.put("greens", greens);
        ListTag next = new ListTag();
        int k = 0;
        for (Object[] o : candidates(id)) {
            if (k++ >= 5) break;
            VillageFolkEntity f = (VillageFolkEntity) o[0];
            CompoundTag n = new CompoundTag();
            n.putString("name", f.displayNameCap());
            n.putInt("score", (Integer) o[1]);
            n.putString("known", knownFor(f));
            n.putString("trade", trade(f));
            next.add(n);
        }
        c.put("next", next);
        c.putInt("notable", NOTABLE);
        c.putBoolean("wanted", wanted(id, folk));
        c.putBoolean("onList", Villages.sitesOf(id).containsKey(Park.GARDEN));
        int happy = 0;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f)) continue;
            Seen s = SEEN.get(f.getUUID());
            if (s != null && s.bonus > 0) happy++;
        }
        c.putInt("happier", happy);
        return c;
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String clip(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + ".";
    }

    // ------------------------------------------------------------------ the tests

    public static boolean raiseForTests(ServerLevel level, Villages.Village v, Ledger.Building g, VillageFolkEntity f) {
        return raise(level, v, g, f, true);
    }

    public static int pondsForTests(ServerLevel level, Villages.Village v, Ledger.Building g) {
        return ponds(level, v, g, true);
    }

    public static List<BlockPos> pondForTests(Ledger.Building g) {
        return pond(g);
    }

    public static List<Ledger.Building> gardensForTests(UUID town) {
        return gardens(town);
    }

    public static void forgetMoodForTests() {
        SEEN.clear();
    }
}
