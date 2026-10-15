package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.Guard;
import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [trade-world] The roadside inn: halfway along a long road between two towns (three hundred blocks or more, a path at
 * least, the towns at peace), the richer of the two puts up an inn by the road, the same timber inn a town keeps inside its
 * gate (inn.txt), set back off the road with its door to it.
 *
 * <ul>
 * <li><b>Built.</b> Its timber, stone and glass are paid for at once out of the building town's stores (Masonry.buildIn:
 *     whatever the stores can pay for all together), and a hand of the town's road crew lays it a dozen blocks a visit;
 *     its beds (out of the stores, or three wool and three planks each) and its casks after. The chronicles of both towns
 *     tell it. A town that cannot pay for it waits and asks again a few days on.</li>
 * <li><b>Kept.</b> An innkeeper takes the lease: a visitor of no village (Visitors.Kind.INNKEEPER), who lives at the bar.
 *     The larder (the casks behind the bar) is stocked out of the building town's stores, and kept stocked by its road crew. The
 *     keeper's takings are its own till; half is the town's rent, handed to the next caravan of the town to lodge there,
 *     and carried home in its purse to the treasury.</li>
 * <li><b>Travellers.</b> A caravan's driver, an envoy, or a household on the road to a new home that is near the inn after
 *     dusk takes a room out of its purse, sleeps there and goes on in the morning (as at a town's inn).</li>
 * <li><b>You.</b> Right-click the sign by the door: a room for the night, three coins, and the bed is yours to sleep in, and
 *     your bed to wake at after (unlike a town's inn, a roadside inn is meant for the night on the road); sneak and
 *     right-click for a meal out of the larder, two coins. Ask the keeper about it too.</li>
 * <li><b>War.</b> While the two towns are at war the inn is shut: no rooms, no meals, the sign says why. Its keeper killed,
 *     it is shut till another takes the lease (two days on).</li>
 * </ul>
 * All of it is in the building town's notes, so a restart keeps it.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Roadside {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private Roadside() {}

    /** The inn's own drawing, set out by the road. */
    static final String DRAWING = "inn";
    /** A road this long has an inn halfway along it. */
    public static final int LONG_ROAD = 300;
    /** What the building town wants: its age, its size, its treasury. */
    static final int FROM_FOLK = 12, TREASURY = 40;
    /** A room, a meal. */
    public static final int ROOM = Inn.ROOM, MEAL = 2;
    /** Blocks laid a visit. */
    static final int CELLS = 12;
    /** The larder is topped up below so much food, by so much. */
    static final int LARDER_LOW = 6, LARDER_LOAD = 8;
    static final String NOTE = "tw.inn.";

    /** A roadside inn: the road it is on (by the other town), where it stands and which way, how far on it is, its keeper. */
    static final class House {
        UUID owner, other;
        BlockPos anchor = BlockPos.ZERO;
        Direction back = Direction.NORTH;
        /** "paying" (looking to pay), "building", "open". */
        String state = "building";
        String look = "";
        @Nullable UUID keeper;
        long opened = -1, keeperGone = -1;
        int guests, rooms, meals, takings, rent;

        Ledger.Building building() {
            return new Ledger.Building(DRAWING, anchor, back);
        }

        String encode() {
            return anchor.getX() + "," + anchor.getY() + "," + anchor.getZ() + "," + back.get2DDataValue() + "," + state + "," + opened + ","
                + (keeper == null ? "-" : keeper.toString()) + "," + keeperGone + "," + guests + "," + rooms + "," + meals + "," + takings + ","
                + rent + "," + look.replace(',', '&');
        }

        @Nullable
        static House decode(UUID owner, UUID other, @Nullable String s) {
            if (s == null || s.isEmpty()) return null;
            String[] p = s.split(",", 14);
            if (p.length < 14) return null;
            try {
                House h = new House();
                h.owner = owner;
                h.other = other;
                h.anchor = new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                h.back = Direction.from2DDataValue(Integer.parseInt(p[3]));
                h.state = p[4];
                h.opened = Long.parseLong(p[5]);
                h.keeper = p[6].equals("-") ? null : UUID.fromString(p[6]);
                h.keeperGone = Long.parseLong(p[7]);
                h.guests = Integer.parseInt(p[8]);
                h.rooms = Integer.parseInt(p[9]);
                h.meals = Integer.parseInt(p[10]);
                h.takings = Integer.parseInt(p[11]);
                h.rent = Integer.parseInt(p[12]);
                h.look = p[13].replace('&', ',');
                return h;
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /** The inns about, by road key (read from the building towns' notes as their towns are looked at). */
    private static final Map<String, House> INNS = new ConcurrentHashMap<>();
    /** A folk lodging at a roadside inn tonight: the road's key, the bed's head, the night it came. */
    private record Lodger(String inn, BlockPos bed, long night) {}
    private static final Map<UUID, Lodger> LODGERS = new ConcurrentHashMap<>();
    private static final Map<UUID, double[]> WALKS = new ConcurrentHashMap<>();
    /** A player's room tonight: the bed's head, and the morning it is given up; by player. */
    private record Booking(String inn, BlockPos bed, long until) {}
    private static final Map<UUID, Booking> BOOKED = new ConcurrentHashMap<>();

    static void resetForTests() {
        INNS.clear();
        LODGERS.clear();
        WALKS.clear();
        BOOKED.clear();
    }

    static void save(House h) {
        Ledger.note(h.owner, NOTE + h.other, h.encode());
        INNS.put(RoadWorks.key(h.owner, h.other), h);
    }

    /** The inn on the road between these two, if either has built (or is building) one. */
    @Nullable
    static House of(UUID x, UUID y) {
        House h = INNS.get(RoadWorks.key(x, y));
        if (h != null) return h;
        h = House.decode(x, y, Ledger.note(x, NOTE + y));
        if (h == null) h = House.decode(y, x, Ledger.note(y, NOTE + x));
        if (h != null) INNS.put(RoadWorks.key(x, y), h);
        return h;
    }

    /** Is the inn open: built, kept, and the towns at peace? */
    static boolean open(@Nullable House h) {
        return h != null && "open".equals(h.state) && h.keeper != null && !Wars.atWar(h.owner, h.other);
    }

    // ------------------------------------------------------------------ the town's visit

    /** [TownLife.tick, every ten seconds] Its roadside inns planned, paid for, built, kept and stocked. */
    public static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        for (RoadWorks.Road r : RoadWorks.roads(id)) {
            UUID o = r.other(id);
            House h = of(id, o);
            if (h == null) {
                if (level.getGameTime() % 2400L < 200L) consider(level, v, r);
                continue;
            }
            if (!h.owner.equals(id)) continue;
            switch (h.state) {
                case "paying" -> pay(level, v, r, h);
                case "building" -> build(level, v, r, h);
                default -> keep(level, v, r, h);
            }
        }
    }

    /** Should this town put an inn on this road? Long, used, at peace, and this the town to pay for it. */
    static void consider(ServerLevel level, Villages.Village v, RoadWorks.Road r) {
        UUID id = v.id(), o = r.other(id);
        if (r.length() < LONG_ROAD || RoadWorks.grade(id, o) < 1 || Wars.atWar(id, o)) return;
        if (Villages.ageOf(id).ordinal() < Villages.Age.STONE.ordinal() || Villages.headcount(id) < FROM_FOLK) return;
        if (Ledger.coins(id) < TREASURY) return;
        // The richer of the two builds it (a tie: the first of the pair), so the two never both set about it.
        int mine = Ledger.coins(id), theirs = Ledger.coins(o);
        if (theirs > mine || theirs == mine && !id.equals(r.a())) return;
        BlockPos[] site = site(level, r);
        if (site == null) return;
        House h = new House();
        h.owner = id;
        h.other = o;
        h.anchor = site[0];
        h.back = Direction.getNearest(site[1].getX(), 0, site[1].getZ());
        h.state = "paying";
        save(h);
        pay(level, v, r, h);
    }

    /**
     * Where the inn goes: by the road's middle (or a little either way), set back eight blocks off it on either side, its
     * back to the open country and its door to the road, on dry level ground away from any town. {anchor, back as a
     * vector}, or null.
     */
    @Nullable
    static BlockPos[] site(ServerLevel level, RoadWorks.Road r) {
        int mid = r.mid();
        boolean alongX = r.alongX();
        int[] half = BuildGoal.footprint(DRAWING);
        int off = Math.max(half[0], half[1]) + 4;
        for (int d : new int[]{ 0, 6, -6, 12, -12, 18, -18, 24, -24 }) {
            int i = mid + d;
            if (i < 8 || i >= r.length() - 8) continue;
            int[] p = r.line().get(i);
            for (int side : new int[]{ -1, 1 }) {
                int x = alongX ? p[0] : p[0] + side * off, z = alongX ? p[1] + side * off : p[1];
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                BlockPos anchor = Divers.standable(level, x, z);
                if (anchor == null) continue;
                boolean ok = true;
                int reach = Math.max(half[0], half[1]) + 1;
                for (int dx = -reach; dx <= reach && ok; dx++) {
                    for (int dz = -reach; dz <= reach && ok; dz++) {
                        BlockPos f = Divers.standable(level, x + dx, z + dz);
                        if (f == null || Math.abs(f.getY() - anchor.getY()) > 1) ok = false;
                    }
                }
                if (!ok || VanillaVillages.meets(level, x - reach, z - reach, x + reach, z + reach, VanillaVillages.MARGIN)) continue;
                boolean town = false;
                for (Villages.Village t : Villages.every()) {
                    int tr = Villages.townReach(t.id()) + reach;
                    if (t.dim().equals(level.dimension()) && Math.abs(x - t.centre().getX()) <= tr && Math.abs(z - t.centre().getZ()) <= tr) town = true;
                }
                if (town) continue;
                BlockPos back = alongX ? new BlockPos(0, 0, side) : new BlockPos(side, 0, 0);
                return new BlockPos[]{ anchor, back };
            }
        }
        return null;
    }

    /** The inn paid for out of the town's stores, all at once; or it waits, and asks again in a while. */
    static void pay(ServerLevel level, Villages.Village v, RoadWorks.Road r, House h) {
        if (!level.isLoaded(h.anchor)) return;
        if (level.getGameTime() % 2400L >= 200L && !h.look.isEmpty()) return;
        List<BuildGoal.Placement> cells = new ArrayList<>(BuildGoal.plan(DRAWING, h.anchor, h.back, 13));
        cells.removeIf(p -> p.part() == BuildGoal.Part.CLEAR);
        Masonry.Look look = Masonry.buildIn(level, v, Grow.palette(v.id(), Villages.ageOf(v.id())), cells, true);
        long day = level.getDayTime() / 24000L;
        if (look == null) {
            if (h.look.isEmpty()) {
                h.look = "waiting";
                save(h);
                Villages.tell(v.id(), day, "the town would put up an inn on the road to " + Villages.name(h.other)
                    + ", but the stores cannot run to its timber and stone yet");
            }
            return;
        }
        h.look = look.encode();
        h.state = "building";
        save(h);
        Villages.tell(v.id(), day, "the town is putting up an inn halfway along the road to " + Villages.name(h.other)
            + ", for the travellers between the two towns");
        Villages.tell(h.other, day, Villages.name(v.id()) + " is putting up an inn halfway along the road to us");
        LOG.info("[MCA-ROADS] {}: a roadside inn on the road to {} at {}", Villages.name(v.id()), Villages.name(h.other), h.anchor.toShortString());
    }

    /** A dozen blocks of the inn a visit, from the footing up, a hand of the road crew there; then its beds and casks. */
    static int build(ServerLevel level, Villages.Village v, RoadWorks.Road r, House h) {
        if (!level.isLoaded(h.anchor) || !Land.areaLoaded(level, h.anchor, 8)) return 0;
        Masonry.Look look = Masonry.Look.decode(h.look);
        if (look == null) {
            h.state = "paying";
            save(h);
            return 0;
        }
        var paint = look.painter(level);
        List<BuildGoal.Placement> plan = BuildGoal.plan(DRAWING, h.anchor, h.back, 13);
        List<BuildGoal.Placement> todo = new ArrayList<>();
        for (BuildGoal.Placement p : plan) {
            if (p.part() == BuildGoal.Part.CLEAR) {
                BlockState st = level.getBlockState(p.pos());
                if (!st.isAir() && st.getFluidState().isEmpty()) todo.add(p);
                continue;
            }
            BlockState want = paint.apply(p);
            if (want == null) continue;
            if (!level.getBlockState(p.pos()).is(want.getBlock())) todo.add(p);
        }
        String works = "road/inn/" + h.other;
        if (!todo.isEmpty()) {
            if (!TownJobs.atWork(level, v, works, h.anchor, "putting up the inn on the road to " + Villages.name(h.other),
                    AssistantEntity.StationTask.HAUL)) return 0;
            todo.sort(java.util.Comparator.comparingInt(p -> p.pos().getY()));
            Set<BlockPos> now = new java.util.HashSet<>();
            for (BuildGoal.Placement p : todo) {
                if (now.size() >= CELLS) break;
                BlockState st = level.getBlockState(p.pos());
                if (p.part() == BuildGoal.Part.CLEAR) {
                    if (RoadWorks.soft(st)) Crafts.giveBack(level, v, RoadWorks.dugAs(st), 1);
                    level.setBlock(p.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    continue;
                }
                if (RoadWorks.soft(st)) Crafts.giveBack(level, v, RoadWorks.dugAs(st), 1);   // the ground dug for the footing
                now.add(p.pos());
            }
            if (now.isEmpty()) return 0;
            BuildGoal.clearWildPlants(level, DRAWING, h.anchor, h.back, 13);
            return BuildGoal.stampOnly(level, DRAWING, h.anchor, h.back, 13, paint, p -> now.contains(p.pos()));
        }
        // The walls up: its beds and casks out of the stores, then open.
        if (!furnish(level, v, h, plan)) return 0;
        h.state = "open";
        h.opened = level.getDayTime() / 24000L;
        save(h);
        stock(level, v, h, 16);
        sign(level, h);
        VillageFolkEntity k = newKeeper(level, v, h);
        long day = level.getDayTime() / 24000L;
        String keeper = k == null ? "" : ", " + k.displayNameCap() + " its keeper";
        Villages.tell(v.id(), day, "the inn on the road to " + Villages.name(h.other) + " opened its doors" + keeper);
        Villages.tell(h.other, day, "the inn on the road to " + Villages.name(v.id()) + " opened its doors" + keeper);
        return 0;
    }

    /** Its beds (one put by, or three wool and three planks each) and its casks (or seven planks), out of the stores. */
    static boolean furnish(ServerLevel level, Villages.Village v, House h, List<BuildGoal.Placement> plan) {
        boolean anyBed = false;
        for (BuildGoal.Placement p : plan) {
            if (p.part() == BuildGoal.Part.BED) {
                if (level.getBlockState(p.pos()).getBlock() instanceof BedBlock) {
                    anyBed = true;
                    continue;
                }
                boolean paid = TownWork.take(level, v, s -> s.is(ItemTags.BEDS), 1)
                    || Market.stock(level, v.id(), s -> s.is(ItemTags.WOOL)) >= 3 && Crafts.planks(level, v, 3)
                        && TownWork.take(level, v, s -> s.is(ItemTags.WOOL), 3) && Crafts.usePlanks(level, v, 3);
                if (!paid) continue;
                BuildGoal.stampOnly(level, DRAWING, h.anchor, h.back, 13, q -> Blocks.RED_BED.defaultBlockState(), q -> q.pos().equals(p.pos()));
                anyBed = true;
            } else if (p.part() == BuildGoal.Part.BARREL && level.getBlockState(p.pos()).isAir()) {
                if (TownWork.take(level, v, s -> s.is(Items.BARREL), 1) || Crafts.usePlanks(level, v, 7)) {
                    level.setBlock(p.pos(), Blocks.BARREL.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        return anyBed;
    }

    /** The inn's sign: by the door, as a town's inn has it; what it says kept true (open, shut by war, no keeper). */
    static void sign(ServerLevel level, House h) {
        BlockPos at = Inn.signAt(h.building());
        if (!level.isLoaded(at)) return;
        BlockState st = level.getBlockState(at);
        if (!(st.getBlock() instanceof WallSignBlock)) {
            if (!st.isAir() || !level.getBlockState(at.relative(h.back)).isSolid()) return;
            level.setBlock(at, Blocks.SPRUCE_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, h.back.getOpposite()), Block.UPDATE_ALL);
        }
        if (level.getBlockEntity(at) instanceof SignBlockEntity sign) {
            String state = Wars.atWar(h.owner, h.other) ? "Shut: war" : h.keeper == null ? "Shut: no keeper" : "Room " + ROOM + "c";
            TownLife.write(sign, new String[]{ "Roadside Inn", clip(Villages.name(h.owner)), state,
                open(h) ? "Sneak: meal " + MEAL + "c" : "" });
        }
    }

    private static String clip(String s) {
        return s.length() > 15 ? s.substring(0, 15) : s;
    }

    /** Kept: its keeper (a new one two days after the last was lost), its sign, its larder stocked from the town's stores. */
    static void keep(ServerLevel level, Villages.Village v, RoadWorks.Road r, House h) {
        if (!level.isLoaded(h.anchor)) return;
        long day = level.getDayTime() / 24000L;
        VillageFolkEntity k = keeper(level, h);
        if (k == null && h.keeper != null && level.isLoaded(Inn.signAt(h.building()))) {
            // Its keeper not about: gone (killed, or lost). The lease stands empty a couple of days.
            if (h.keeperGone < 0) {
                h.keeperGone = day;
                save(h);
            } else if (day - h.keeperGone >= 2) {
                h.keeper = null;
                save(h);
            }
        }
        if (h.keeper == null && (h.keeperGone < 0 || day - h.keeperGone >= 2) && !Wars.atWar(h.owner, h.other)) newKeeper(level, v, h);
        if (k != null && h.keeperGone >= 0) {
            h.keeperGone = -1;
            save(h);
        }
        if (k != null) ArmyInns.till(h, k);                            // [realm-polish] what an army left on the bar for it
        sign(level, h);
        if (open(h) && larder(level, h) < LARDER_LOW) {
            if (TownJobs.atWork(level, v, "road/inn/" + h.other, h.anchor, "taking food out to the roadside inn", AssistantEntity.StationTask.HAUL)) {
                stock(level, v, h, LARDER_LOAD);
            }
        }
    }

    /** The inn's larder: the first of the casks behind its bar (or a chest, if the drawing has one). */
    @Nullable
    static Container larderBox(ServerLevel level, House h) {
        for (BuildGoal.Placement p : BuildGoal.plan(DRAWING, h.anchor, h.back, 13)) {
            if ((p.part() == BuildGoal.Part.CHEST || p.part() == BuildGoal.Part.BARREL) && level.getBlockEntity(p.pos()) instanceof Container c) return c;
        }
        return null;
    }

    static boolean food(ItemStack s) {
        return !s.isEmpty() && s.get(DataComponents.FOOD) != null && !s.is(Items.ROTTEN_FLESH) && !s.is(Items.SPIDER_EYE)
            && !s.is(Items.POISONOUS_POTATO);
    }

    /** How much food is in the larder. */
    static int larder(ServerLevel level, House h) {
        Container c = larderBox(level, h);
        if (c == null) return 0;
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (food(c.getItem(i))) n += c.getItem(i).getCount();
        return n;
    }

    /** So much food out of the town's stores into the larder (carried out by its road crew). */
    static int stock(ServerLevel level, Villages.Village v, House h, int n) {
        Container c = larderBox(level, h);
        if (c == null) return 0;
        int moved = 0;
        for (ItemStack s : Caravans.takeOut(level, v, Roadside::food, n)) {
            ItemStack left = s;
            for (int i = 0; i < c.getContainerSize() && !left.isEmpty(); i++) {
                ItemStack in = c.getItem(i);
                if (in.isEmpty()) {
                    c.setItem(i, left);
                    moved += left.getCount();
                    left = ItemStack.EMPTY;
                } else if (ItemStack.isSameItemSameComponents(in, left) && in.getCount() < in.getMaxStackSize()) {
                    int k = Math.min(left.getCount(), in.getMaxStackSize() - in.getCount());
                    in.grow(k);
                    left.shrink(k);
                    moved += k;
                }
            }
            if (!left.isEmpty()) Market.intoStores(level, v.id(), left);
        }
        c.setChanged();
        return moved;
    }

    // ------------------------------------------------------------------ the keeper

    @Nullable
    static VillageFolkEntity keeper(ServerLevel level, House h) {
        return h.keeper != null && level.getEntity(h.keeper) instanceof VillageFolkEntity f && f.isAlive() ? f : null;
    }

    /** An innkeeper takes the lease: comes along the road and sets up behind the bar. */
    @Nullable
    static VillageFolkEntity newKeeper(ServerLevel level, Villages.Village v, House h) {
        BlockPos door = h.anchor.relative(h.back.getOpposite(), 2);
        if (!level.isLoaded(door)) return null;
        VillageFolkEntity f = McAssistantMod.VILLAGE_FOLK.get().create(level);
        if (f == null) return null;
        RandomSource r = level.getRandom();
        long day = level.getDayTime() / 24000L;
        f.moveTo(h.anchor.getX() + 0.5, h.anchor.getY(), h.anchor.getZ() + 0.5, r.nextFloat() * 360.0F, 0.0F);
        String name = Names.freshFor(v.id(), r);
        f.rename(name + " the innkeeper");
        f.addTag(Visitors.TAG);
        f.setPersistenceRequired();
        f.life().roll(r, null, null);
        f.persona().roll(r, f.life(), AssistantEntity.StationTask.NONE, day, "the keeper of the inn on the road");
        Visitors.Visit vis = new Visitors.Visit();
        vis.kind = Visitors.Kind.INNKEEPER;
        vis.town = h.owner;
        vis.edge = door.immutable();
        vis.name = name;
        vis.arrived = day;
        vis.leave = Long.MAX_VALUE / 2;
        vis.stage = "stay";
        vis.told = true;
        vis.wares = h.other.toString();
        vis.seen = level.getGameTime();
        Visitors.save(f, vis);
        level.addFreshEntity(f);
        h.keeper = f.getUUID();
        h.keeperGone = -1;
        save(h);
        FolkTalk.speak(f, "Rooms and a hot meal for anybody on the road!");
        LOG.info("[MCA-ROADS] {} keeps the inn on the road between {} and {}", name, Villages.name(h.owner), Villages.name(h.other));
        return f;
    }

    /** The inn this keeper keeps. */
    @Nullable
    static House keptBy(Visitors.Visit vis) {
        try {
            return of(vis.town, UUID.fromString(vis.wares));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** [Visitors] Its day: behind the bar. It leaves only if the inn is gone. */
    static boolean stay(ServerLevel level, Villages.Village town, VillageFolkEntity f, Visitors.Visit vis, long day, long t) {
        House h = keptBy(vis);
        if (h == null || !f.getUUID().equals(h.keeper)) return true;
        if (f.isSleeping()) f.stopSleeping();
        Visitors.walk(f, level, h.anchor, 2.0, 0.6D, vis.walk);
        long now = level.getGameTime();
        if (now - vis.lastSaid > 1600L && f.getRandom().nextInt(4) == 0) {
            vis.lastSaid = now;
            FolkTalk.speak(f, Wars.atWar(h.owner, h.other) ? "Shut, I'm afraid. Not with a war on the road."
                : FolkTalk.pick(f.getRandom(), "A room, traveller? Three coins and a dry bed.", "There's stew on, if you're hungry."));
        }
        return false;
    }

    static String doing(VillageFolkEntity f, Visitors.Visit vis) {
        House h = keptBy(vis);
        return h == null ? "Keeping an inn" : "Keeping the inn on the road between " + Villages.name(h.owner) + " and " + Villages.name(h.other);
    }

    static String card(VillageFolkEntity f, Visitors.Visit vis) {
        House h = keptBy(vis);
        StringBuilder sb = new StringBuilder("Visitor|The keeper of a roadside inn; of no village, so not one of a town's people");
        if (h != null) {
            sb.append("\nInn|on the road between ").append(Villages.name(h.owner)).append(" and ").append(Villages.name(h.other))
                .append(", built by ").append(Villages.name(h.owner));
            sb.append("\nPrices|a room ").append(ROOM).append(" coins, a meal ").append(MEAL).append(" (the sign by the door)");
            sb.append("\nTakings|").append(h.takings).append(" coins from ").append(h.guests).append(" guests and ").append(h.meals)
                .append(" meals; ").append(h.rent).append(" sent home as rent");
            if (Wars.atWar(h.owner, h.other)) sb.append("\nShut|while the two towns are at war");
            for (String a : ArmyInns.bookLines(h)) sb.append("\nArmy|").append(a);       // [realm-polish] the armies it has had
        }
        if (f.persona().rolled()) sb.append("\nNature|").append(f.life().traitsLabel());
        return sb.toString();
    }

    @Nullable
    static String talk(VillageFolkEntity f, Player p, TalkTopic topic, Visitors.Visit vis) {
        House h = keptBy(vis);
        RandomSource r = f.getRandom();
        return switch (topic) {
            case GIFT, BYE, SAY -> null;
            case OPEN -> FolkTalk.pick(r, "Welcome, traveller! A room's " + ROOM + " coins, a meal " + MEAL + ".",
                "Come in out of the road. The fire's lit.");
            case TRADE -> h == null ? "We're not open." : (p instanceof ServerPlayer sp ? room(sp.serverLevel(), h, sp) : "");
            case MEAL -> h == null ? "We're not open." : (p instanceof ServerPlayer sp ? meal(sp.serverLevel(), h, sp) : "");
            case ABOUT, DREAMS, HOBBY -> h == null ? "I keep an inn." : "I keep this inn for " + Villages.name(h.owner)
                + ": they built it, I run it, and half of what I take goes home to them with their caravans.";
            case VILLAGE, GOSSIP, MEMORY -> "Folk come through from " + (h == null ? "both ends" : Villages.name(h.owner) + " and "
                + Villages.name(h.other)) + ". You hear things: ask me about a room and I'll tell you more.";
            default -> "I only keep the inn, friend. Ask in town.";
        };
    }

    // ------------------------------------------------------------------ a player at the inn

    /** A room for the night: three coins, a bed of yours till morning, and where you wake after. What the player is told. */
    static String room(ServerLevel level, House h, ServerPlayer p) {
        if (!open(h)) return Wars.atWar(h.owner, h.other) ? "We're shut while the war's on the road." : "We're shut: there's no keeper.";
        Booking have = BOOKED.get(p.getUUID());
        long dt = level.getDayTime();
        if (have != null && have.inn().equals(RoadWorks.key(h.owner, h.other)) && dt < have.until()) {
            return "You've a room already: the bed's yours till morning.";
        }
        BlockPos bed = freeBed(level, h);
        if (bed == null) return "Every bed's taken tonight, I'm sorry.";
        if (Market.coinsHeld(p) < ROOM) return "A room's " + ROOM + " coins, and you've not got them on you.";
        Market.payOut(p, ROOM);
        VillageFolkEntity k = keeper(level, h);
        if (k != null) k.earn(ROOM);
        h.rooms++;
        h.takings += ROOM;
        save(h);
        BOOKED.put(p.getUUID(), new Booking(RoadWorks.key(h.owner, h.other), bed, Inn.until(dt)));
        p.setRespawnPosition(level.dimension(), bed, p.getYRot(), true, false);
        level.playSound(null, bed, SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 0.8F, 1.0F);
        return "Three coins, thank you. The bed's yours till morning, and you'll wake here if the road goes ill for you.";
    }

    /** A meal out of the larder: two coins. What the player is told. */
    static String meal(ServerLevel level, House h, ServerPlayer p) {
        if (!open(h)) return "We're shut, I'm afraid.";
        Container c = larderBox(level, h);
        int slot = -1;
        if (c != null) for (int i = 0; i < c.getContainerSize() && slot < 0; i++) if (food(c.getItem(i))) slot = i;
        if (slot < 0) return "The larder's bare till the next cart comes out from " + Villages.name(h.owner) + ".";
        if (Market.coinsHeld(p) < MEAL) return "A meal's " + MEAL + " coins.";
        Market.payOut(p, MEAL);
        ItemStack dish = c.getItem(slot).split(1);
        c.setChanged();
        String served = ShipCalls.words(dish.getItem());
        if (!p.getInventory().add(dish)) p.drop(dish, false);
        VillageFolkEntity k = keeper(level, h);
        if (k != null) k.earn(MEAL);
        h.meals++;
        h.takings += MEAL;
        save(h);
        return "There you are: " + served + ", hot from the fire.";
    }

    @Nullable
    static BlockPos freeBed(ServerLevel level, House h) {
        long dt = level.getDayTime();
        for (BlockPos head : Inn.beds(level, h.building())) {
            boolean taken = false;
            for (Booking b : BOOKED.values()) if (b.bed().equals(head) && dt < b.until()) taken = true;
            for (Lodger l : LODGERS.values()) if (l.bed().equals(head)) taken = true;
            if (taken || level.getBlockState(head).getValue(BedBlock.OCCUPIED) || ArmyInns.roomTaken(head)) continue;   // [realm-polish] an officer's
            return head;
        }
        return null;
    }

    /** [realm-polish] Is this bed of an inn free tonight: no player's room, no traveller's, no officer's, nobody in it? */
    static boolean bedFree(ServerLevel level, BlockPos head) {
        long dt = level.getDayTime();
        for (Booking b : BOOKED.values()) if (b.bed().equals(head) && dt < b.until()) return false;
        for (Lodger l : LODGERS.values()) if (l.bed().equals(head)) return false;
        return level.getBlockState(head).getBlock() instanceof BedBlock && !level.getBlockState(head).getValue(BedBlock.OCCUPIED)
            && !ArmyInns.roomTaken(head);
    }

    /** The inn whose sign (or bed) this is, or null. */
    @Nullable
    static House at(ServerLevel level, BlockPos pos, boolean sign) {
        for (House h : INNS.values()) {
            if (Math.abs(h.anchor.getX() - pos.getX()) > 8 || Math.abs(h.anchor.getZ() - pos.getZ()) > 8) continue;
            if (sign ? Inn.signAt(h.building()).equals(pos) : Inn.beds(level, h.building()).contains(pos)) return h;
        }
        return null;
    }

    @SubscribeEvent
    public static void onUseBlock(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getLevel() instanceof ServerLevel level) || e.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(level.getBlockState(e.getPos()).getBlock() instanceof WallSignBlock) || INNS.isEmpty()) return;
        House h = at(level, e.getPos(), true);
        if (h == null || !(e.getEntity() instanceof ServerPlayer p)) return;
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        Guard.run("the roadside inn", () -> {
            VillageFolkEntity k = keeper(level, h);
            String said = p.isShiftKeyDown() ? meal(level, h, p) : room(level, h, p);
            p.sendSystemMessage(Component.literal((k == null ? "" : k.displayNameCap() + ": ") + "\"" + said + "\"").withStyle(ChatFormatting.YELLOW));
        });
    }

    /** Nobody sleeps in a roadside inn's bed without a room. */
    @SubscribeEvent
    public static void onSleep(CanPlayerSleepEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level) || INNS.isEmpty()) return;
        BlockPos head = Inn.head(level, e.getPos());
        if (head == null) return;
        House h = at(level, head, false);
        if (h == null || e.getEntity().isCreative()) return;
        Booking b = BOOKED.get(e.getEntity().getUUID());
        if (b != null && b.bed().equals(head) && level.getDayTime() < b.until()) return;
        e.setProblem(Player.BedSleepingProblem.OTHER_PROBLEM);
        e.getEntity().sendSystemMessage(Component.literal("That's one of the inn's beds: right-click the sign by the door for a room, "
            + ROOM + " coins a night.").withStyle(ChatFormatting.YELLOW));
    }

    // ------------------------------------------------------------------ travellers

    /**
     * [VillageFolkEntity.aiStep, after the town inns'] A traveller near a roadside inn after dusk takes a room out of its
     * purse, sleeps there, and goes on at dawn. A caravan of the building town's takes the town's rent home with it.
     */
    public static boolean lodging(VillageFolkEntity f, ServerLevel level) {
        Lodger l = LODGERS.get(f.getUUID());
        long dt = level.getDayTime(), t = dt % 24000L, day = dt / 24000L;
        if (l == null) {
            if (f.tickCount % 40 != 23 || f.isBaby() || INNS.isEmpty() || !Inn.travelling(f)) return false;
            if (t < 13000L || t >= 23000L || f.getTarget() != null) return false;
            return takeARoom(level, f) != null;
        }
        boolean morning = day > l.night() && t < 13000L || t >= 23500L;
        if (morning || f.getTarget() != null || !(level.getBlockState(l.bed()).getBlock() instanceof BedBlock)) {
            if (f.isSleeping()) f.stopSleeping();
            LODGERS.remove(f.getUUID());
            WALKS.remove(f.getUUID());
            if (f.trip() != null) f.trip().gainedTick = f.tickCount;
            if (morning) f.brain("on its way again after a night at the inn on the road");
            return false;
        }
        if (f.isSleeping()) return true;
        double d = f.blockPosition().distSqr(l.bed());
        if (d > 4.0) {
            double[] w = WALKS.computeIfAbsent(f.getUUID(), k -> new double[]{ Double.MAX_VALUE, f.tickCount, -1000 });
            if (d < w[0] - 1.0) { w[0] = d; w[1] = f.tickCount; }
            if (f.tickCount - w[1] > 600) {
                BlockPos by = Trades.floorSpot(level, l.bed(), 2);
                if (by != null) f.moveTo(by.getX() + 0.5, by.getY(), by.getZ() + 0.5, f.getYRot(), 0.0F);
                w[1] = f.tickCount;
            }
            if (f.getNavigation().isDone() || f.tickCount - w[2] > 60) {
                f.walkTo(l.bed(), 0.9D);
                w[2] = f.tickCount;
            }
            f.hobbyNow = "on its way to its room at the inn on the road";
            return true;
        }
        f.getNavigation().stop();
        f.startSleeping(l.bed());
        f.hobbyNow = "asleep at the inn on the road";
        return true;
    }

    /** A room at the nearest open roadside inn within reach, paid out of its purse. Returns the bed, or null. */
    @Nullable
    static BlockPos takeARoom(ServerLevel level, VillageFolkEntity f) {
        House near = null;
        double best = 48.0 * 48.0;
        for (House h : INNS.values()) {
            if (!open(h)) continue;
            double d = f.blockPosition().distSqr(h.anchor);
            if (d < best) {
                best = d;
                near = h;
            }
        }
        if (near == null || f.purse() < ROOM) return null;
        BlockPos bed = freeBed(level, near);
        if (bed == null || !f.spend(ROOM)) return null;
        VillageFolkEntity k = keeper(level, near);
        if (k != null) k.earn(ROOM);
        near.guests++;
        near.takings += ROOM;
        // A caravan of the building town's: the town's rent, half of the takings owed, goes home in its purse.
        if (k != null && f.trip() != null && near.owner.equals(f.ownerId())) {
            int owed = Math.max(0, near.takings / 2 - near.rent);
            int paid = Math.min(owed, k.purse());
            if (paid > 0 && k.spend(paid)) {
                f.trip().purse += paid;
                near.rent += paid;
                Villages.tell(near.owner, level.getDayTime() / 24000L, "the keeper of the inn on the road to " + Villages.name(near.other)
                    + " sent " + Visitors.coins(paid) + " of rent home with " + f.displayNameCap());
            }
        }
        save(near);
        long day = level.getDayTime() / 24000L;
        LODGERS.put(f.getUUID(), new Lodger(RoadWorks.key(near.owner, near.other), bed, day));
        f.getNavigation().stop();
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "A room for the night, please.", "Just the place. My feet are done in."));
        f.persona().remember(day, "spent the night at the inn on the road between " + Villages.name(near.owner) + " and " + Villages.name(near.other), 1);
        return bed;
    }

    // ------------------------------------------------------------------ the books

    /** The Roads page's line for the inn on this road, or null. */
    @Nullable
    static String line(ServerLevel level, RoadWorks.Road r) {
        House h = of(r.a(), r.b());
        if (h == null) {
            return r.length() >= LONG_ROAD ? "A long road: an inn halfway along it once it is a path and a town of twelve with "
                + TREASURY + " coins will build it." : null;
        }
        return switch (h.state) {
            case "paying" -> "The inn halfway: " + Villages.name(h.owner) + " waits on the timber and stone for it.";
            case "building" -> "The inn halfway: being put up by " + Villages.name(h.owner) + "'s road crew.";
            default -> "The inn halfway (" + Villages.name(h.owner) + "'s): " + (open(h) ? "open" : Wars.atWar(h.owner, h.other)
                ? "shut while the war lasts" : "shut, no keeper") + "; " + h.guests + " travellers, " + h.rooms + " rooms let to players, "
                + h.meals + " meals, " + h.takings + " coins taken, " + h.rent + " sent home as rent." + ArmyInns.booksLine(h);   // [realm-polish]
        };
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the inn on this road, as "state anchor keeper". */
    public static String stateForTests(UUID x, UUID y) {
        House h = of(x, y);
        return h == null ? "" : h.state + " " + h.anchor.toShortString() + " " + (h.keeper == null ? "-" : "kept");
    }

    /** Tests: the inn's place and its door's way. */
    @Nullable
    public static BlockPos anchorForTests(UUID x, UUID y) {
        House h = of(x, y);
        return h == null ? null : h.anchor;
    }

    /** Tests: the town looks at its roads for an inn to put up now (as it does once in a while). */
    public static void considerForTests(ServerLevel level, Villages.Village v) {
        for (RoadWorks.Road r : RoadWorks.roads(v.id())) if (of(v.id(), r.other(v.id())) == null) consider(level, v, r);
    }

    /** Tests: the town's inns seen to now (planned, paid, built a visit, kept). */
    public static void tickForTests(ServerLevel level, Villages.Village v) {
        tick(level, v);
    }

    /** Tests: the inn's sign, for a player to click. */
    @Nullable
    public static BlockPos signForTests(UUID x, UUID y) {
        House h = of(x, y);
        return h == null ? null : Inn.signAt(h.building());
    }

    /** Tests: a player asks for a room, or a meal. What the keeper said. */
    public static String roomForTests(ServerPlayer p, UUID x, UUID y, boolean meal) {
        House h = of(x, y);
        if (h == null) return "";
        return meal ? meal(p.serverLevel(), h, p) : room(p.serverLevel(), h, p);
    }

    /** Tests: a folk takes a room as a traveller would. Its bed, or null. */
    @Nullable
    public static BlockPos lodgeForTests(ServerLevel level, VillageFolkEntity f) {
        return takeARoom(level, f);
    }

    /** Tests: food in the larder. */
    public static int larderForTests(ServerLevel level, UUID x, UUID y) {
        House h = of(x, y);
        return h == null ? -1 : larder(level, h);
    }
}
