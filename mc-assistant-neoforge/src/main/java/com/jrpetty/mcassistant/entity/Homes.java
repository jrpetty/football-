package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Homes: who lives where, and on what terms.
 *
 * <p>A household is a folk, its partner and their children. Each household has a house of its own
 * (a house or a manor the village built), and sleeps there: the two of them side by side, the
 * children's beds on the other side of the room. A child born into a full house gets a bed of its
 * own, bought at the shop out of its parents' purses and set up across from theirs. A child grown
 * up stays at home until there is a house for it, then moves out into a place of its own; two folk
 * who marry move in together, into the better of their two houses, and the other goes back to the
 * village. A family that moves carries its belongings with it: what is in the old house's chest
 * goes into the new one's, and its keepsakes (presents, things it bought for itself) are never
 * banked in the village stores.
 *
 * <p>The village gives no houses away: it lets them. A household moves in as the village's tenant,
 * families first, rich or poor, and pays its rent on payday out of its wages, into the treasury: half
 * a field hand's day for a house, a field hand's day for a two-storey one, two for a manor (so the
 * rents rise with the place's wages). Nobody is put out for want of it: rent it
 * cannot pay goes on the slate and is paid back when it can, and the village writes off more than a
 * week's of it; a house where nobody earns pays none, and a generous leader lets off whatever a
 * tenant is short. The leader's hall is the one free roof: it goes with the office.
 *
 * <p>The founders are the exception. The folk who started the village live rent-free in the houses it
 * builds them, until they can afford the rent: from the first payday a household of founders has a
 * week's rent in hand over what it lives on, it pays like everybody else, and never goes back to free.
 *
 * <p>Some want a house of their own and some never do. A Homemaker does, and a Traditionalist and a
 * Provider like to own the roof over their heads; a Free Spirit would rather rent and keep its coin
 * and its freedom; a Merchant buys when the price is a good deal (no more than forty days' rent). A
 * couple settling down, children, and the middle years pull toward buying; youth, and old age, away.
 * A household that wants to own puts its pay by on payday (a third of it, or all its purses hold
 * over a dozen coins a head, whichever is more), and when what it has put by covers the house's
 * price it buys it from the village: no more rent. One that changes its mind has its savings back.
 * An owner sells its house back at half what it paid when it leaves it (to marry into the other
 * house, for the leader's hall, or up to a manor once it has grown rich).
 *
 * <p>A player can buy a house too (`/village house buy`), live in it, and let it out
 * (`/village house let`): a household with nowhere to live moves in and pays the player's rent,
 * which the player collects (`/village house rent`).
 */
public final class Homes {

    private Homes() {}

    /**
     * On what terms a household lives where it does. GIVEN is the leader's hall (it goes with the
     * office), and houses given before the village let them, which it lets to their households on
     * the next payday.
     */
    public enum Tenure {
        GIVEN("given by the village"), OWNED("owned"), RENTED("rented from the village"), PLAYER("a player's");

        public final String word;

        Tenure(String word) {
            this.word = word;
        }
    }

    /** A house the village built to live in: who lives there, and on what terms. */
    static final class Home {
        final BlockPos anchor;
        final String structure;
        /** An empty house is the village's, to let. */
        Tenure tenure = Tenure.RENTED;
        final List<UUID> members = new ArrayList<>();
        @Nullable UUID landlord;
        String landlordName = "";
        int price;
        int rent;
        long since = -1;
        int owed;
        boolean toLet;
        /** What the household has put by out of its wages toward buying it (tenants that want to own). */
        int saved;

        Home(BlockPos anchor, String structure) {
            this.anchor = anchor;
            this.structure = structure;
        }

        boolean vacant() {
            return members.isEmpty() && (tenure != Tenure.PLAYER || toLet);
        }
    }

    private static final Map<UUID, Map<Long, Home>> HOMES = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> TICKED = new ConcurrentHashMap<>();
    /** Folk carrying their things from one house to another. */
    private static final Map<UUID, Move> MOVES = new ConcurrentHashMap<>();

    record Move(@Nullable BlockPos from, BlockPos to, boolean whole, long started, boolean[] fetched) {}

    /** A child's bed bought at the shop, on its way home with a parent: where it goes, how it lies, for whom. */
    record BedErrand(UUID village, BlockPos house, BlockPos shop, BlockPos spot, Direction lie, BlockState bed, long started,
                     boolean[] collected, String child) {}

    /** Parents fetching a child's bed from the shop (by the parent). */
    private static final Map<UUID, BedErrand> BED_ERRANDS = new ConcurrentHashMap<>();

    @Nullable private static Boolean SALE_FOR_TESTS;

    public static void resetForTests() {
        SALE_FOR_TESTS = null;
        HOMES.clear();
        TICKED.clear();
        MOVES.clear();
        BED_ERRANDS.clear();
    }

    /** What the village builds for living in. */
    public static boolean isHome(String structure) {
        return structure.equals("house") || structure.equals("manor") || structure.equals("townhall");
    }

    /** The leader's hall: the home that goes with leading the village, never given, sold or let. */
    static boolean seat(Home h) {
        return h.structure.equals("townhall");
    }

    // ------------------------------------------------------------------ the books

    static Map<Long, Home> homes(UUID village) {
        return HOMES.computeIfAbsent(village, id -> {
            Map<Long, Home> out = new LinkedHashMap<>();
            Map<String, Ledger.Building> built = new HashMap<>();
            for (Ledger.Building b : Ledger.buildings(id)) built.put(Long.toString(b.anchor().asLong()), b);
            for (Map.Entry<String, String> e : Ledger.notes(id).entrySet()) {
                if (!e.getKey().startsWith("home/") || e.getValue().isEmpty()) continue;
                String key = e.getKey().substring(5);
                Ledger.Building b = built.get(key);
                if (b == null) continue;
                Home h = decode(b, e.getValue());
                if (h != null) out.put(b.anchor().asLong(), h);
            }
            return new ConcurrentHashMap<>(out);
        });
    }

    @Nullable
    private static Home decode(Ledger.Building b, String s) {
        try {
            String[] p = s.split("\\|", -1);
            Home h = new Home(b.anchor(), b.structure());
            h.tenure = Tenure.valueOf(p[0]);
            if (!p[1].isEmpty()) for (String m : p[1].split(",")) h.members.add(UUID.fromString(m));
            h.landlord = p[2].isEmpty() ? null : UUID.fromString(p[2]);
            h.landlordName = p[3];
            h.price = Integer.parseInt(p[4]);
            h.rent = Integer.parseInt(p[5]);
            h.since = Long.parseLong(p[6]);
            h.owed = Integer.parseInt(p[7]);
            h.toLet = "1".equals(p[8]);
            h.saved = p.length > 9 ? Integer.parseInt(p[9]) : 0;        // put by toward buying it (none in older books)
            return h;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static void save(UUID village, Home h) {
        StringBuilder m = new StringBuilder();
        for (UUID u : h.members) {
            if (m.length() > 0) m.append(',');
            m.append(u);
        }
        Ledger.note(village, "home/" + h.anchor.asLong(), h.tenure.name() + "|" + m + "|" + (h.landlord == null ? "" : h.landlord)
            + "|" + h.landlordName.replace("|", "") + "|" + h.price + "|" + h.rent + "|" + h.since + "|" + h.owed + "|" + (h.toLet ? "1" : "0")
            + "|" + h.saved);
    }

    /** Every house built for living in is on the books, empty until somebody moves in. */
    static void enrol(UUID village) {
        Map<Long, Home> homes = homes(village);
        for (Ledger.Building b : Ledger.buildings(village)) {
            if (!isHome(b.structure()) || homes.containsKey(b.anchor().asLong())) continue;
            Home h = new Home(b.anchor(), b.structure());
            homes.put(b.anchor().asLong(), h);
            save(village, h);
        }
    }

    @Nullable
    static Ledger.Building building(UUID village, BlockPos anchor) {
        for (Ledger.Building b : Ledger.buildings(village)) if (b.anchor().equals(anchor)) return b;
        return null;
    }

    @Nullable
    static Home homeOf(UUID village, UUID folk) {
        for (Home h : homes(village).values()) if (h.members.contains(folk)) return h;
        return null;
    }

    /** Where this folk lives (the house's anchor), or null. */
    @Nullable
    public static BlockPos homeOf(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null) return null;
        Home h = homeOf(village, f.getUUID());
        return h == null ? null : h.anchor;
    }

    /** The house that stands here, if any (its footprint, a little round it). */
    @Nullable
    static Home homeAt(UUID village, BlockPos p) {
        for (Home h : homes(village).values()) {
            int[] half = BuildGoal.footprint(drawing(village, h));
            int r = Math.max(half[0], half[1]) + 1;
            if (Math.abs(p.getX() - h.anchor.getX()) <= r && Math.abs(p.getZ() - h.anchor.getZ()) <= r
                    && p.getY() >= h.anchor.getY() - 2 && p.getY() <= h.anchor.getY() + 14) return h;
        }
        return null;
    }

    /**
     * Is this spot inside one of the village's homes? Its chest is the household's, not the stores',
     * empty or not: an empty house's chest taken for the stores was lost to them the day a family
     * moved in, and whatever the village had put in it with it.
     */
    public static boolean inAHome(UUID village, BlockPos p) {
        return homeAt(village, p) != null;
    }

    /** The plan of the house as it stands: grown houses have their second storey. */
    static String drawing(UUID village, Home h) {
        if (h.structure.equals("house")) return Ledger.grown(village, h.anchor) ? "house2" : "house";
        return h.structure;
    }

    // ------------------------------------------------------------------ beds

    /** The heads of the beds made up in a house now, its own (from its plan) and the ones bought for it. */
    static List<BlockPos> bedsIn(ServerLevel level, UUID village, Home h) {
        List<BlockPos> out = new ArrayList<>();
        Ledger.Building b = building(village, h.anchor);
        if (b == null || !level.isLoaded(h.anchor)) return out;
        for (BuildGoal.Placement p : BuildGoal.plan(drawing(village, h), b.anchor(), b.facing(), 13)) {
            if (p.part() != BuildGoal.Part.BED) continue;
            Direction lie = p.way() == com.jrpetty.mcassistant.entity.goal.Blueprints.Way.UP ? b.facing()
                : com.jrpetty.mcassistant.entity.goal.Blueprints.world(p.way(), b.facing());
            BlockPos head = p.pos().relative(lie);
            if (isBedHead(level, head) && !out.contains(head)) out.add(head);
        }
        String extra = Ledger.note(village, "homebeds/" + h.anchor.asLong());
        if (extra != null && !extra.isEmpty()) {
            for (String s : extra.split(",")) {
                try {
                    BlockPos head = BlockPos.of(Long.parseLong(s));
                    if (isBedHead(level, head) && !out.contains(head)) out.add(head);
                } catch (NumberFormatException ignored) { }
            }
        }
        out.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        return out;
    }

    static boolean isBedHead(ServerLevel level, BlockPos p) {
        BlockState st = level.getBlockState(p);
        return st.getBlock() instanceof BedBlock && st.getValue(BedBlock.PART) == BedPart.HEAD;
    }

    /**
     * Which bed in its house is this folk's: the grown-ups take a pair side by side, the children
     * the beds on the other side of the room, in the order they were born. Null if it has no house,
     * or the house has no bed left for it.
     */
    @Nullable
    public static BlockPos bedFor(ServerLevel level, VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null) return null;
        Home h = homeOf(village, f.getUUID());
        if (h == null) return null;
        List<BlockPos> beds = bedsIn(level, village, h);
        if (beds.isEmpty()) return null;
        // The beds in groups that stand together (a pair side by side).
        List<List<BlockPos>> groups = groups(beds);
        groups.sort((a, b) -> Integer.compare(b.size(), a.size()));
        List<BlockPos> adultsFirst = new ArrayList<>();
        for (List<BlockPos> g : groups) adultsFirst.addAll(g);
        List<BlockPos> childrenFirst = new ArrayList<>();
        for (int i = groups.size() - 1; i >= 0; i--) childrenFirst.addAll(groups.get(i));
        if (groups.size() > 1) {                                   // the children's side: not the grown-ups' pair
            childrenFirst.removeAll(groups.get(0));
            childrenFirst.addAll(groups.get(0));
        }
        List<VillageFolkEntity> adults = new ArrayList<>(), children = new ArrayList<>();
        for (VillageFolkEntity m : loadedMembers(village, h)) (m.isBaby() ? children : adults).add(m);
        adults.sort(Comparator.comparing(VillageFolkEntity::getUUID));
        children.sort(Comparator.comparingLong(VillageFolkEntity::bornDay).thenComparing(VillageFolkEntity::getUUID));
        java.util.Set<BlockPos> taken = new java.util.HashSet<>();
        for (VillageFolkEntity a : adults) {
            BlockPos bed = first(adultsFirst, taken);
            if (bed == null) return null;
            taken.add(bed);
            if (a == f) return bed;
        }
        for (VillageFolkEntity c : children) {
            BlockPos bed = first(childrenFirst, taken);
            if (bed == null) return null;
            taken.add(bed);
            if (c == f) return bed;
        }
        return null;
    }

    @Nullable
    private static BlockPos first(List<BlockPos> order, java.util.Set<BlockPos> taken) {
        for (BlockPos b : order) if (!taken.contains(b)) return b;
        return null;
    }

    static List<List<BlockPos>> groups(List<BlockPos> beds) {
        List<List<BlockPos>> out = new ArrayList<>();
        for (BlockPos b : beds) {
            List<BlockPos> join = null;
            for (List<BlockPos> g : out) {
                for (BlockPos o : g) if (o.getY() == b.getY() && o.distSqr(b) <= 2.0) { join = g; break; }
                if (join != null) break;
            }
            if (join == null) { join = new ArrayList<>(); out.add(join); }
            join.add(b);
        }
        return out;
    }

    /** Is this bed in somebody else's home (not to be claimed by this folk)? */
    public static boolean someoneElses(UUID village, BlockPos bed, VillageFolkEntity f) {
        Home h = homeAt(village, bed);
        if (h == null) return false;
        if (h.tenure == Tenure.PLAYER && !h.toLet) return true;
        return !h.members.isEmpty() && !h.members.contains(f.getUUID());
    }

    // ------------------------------------------------------------------ the households

    /** Every so often for each village (TownLife): homes for those without, couples together, grown children out, beds for the children. */
    public static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime();
        Long last = TICKED.get(id);
        if (last != null && now - last < 600L && now >= last) return;
        TICKED.put(id, now);
        long day = level.getDayTime() / 24000L;
        enrol(id);
        Villages.bedsMadeUp(level, id);                // the room the village has, counted afresh (Villages.housing)
        Map<Long, Home> homes = homes(id);
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (a instanceof VillageFolkEntity f && !f.isShowcase() && !f.isHired()) folk.add(f);
        }
        // Couples under one roof.
        for (VillageFolkEntity f : folk) {
            if (f.isBaby()) continue;
            VillageFolkEntity p = partner(f, folk);
            if (p == null) continue;
            Home mine = homeOf(id, f.getUUID()), theirs = homeOf(id, p.getUUID());
            if (mine != null && mine == theirs) continue;
            if (mine == null && theirs == null) continue;
            together(level, v, f, p, mine, theirs, day);
        }
        // Children live with their parents.
        for (VillageFolkEntity f : folk) {
            if (!f.isBaby() || homeOf(id, f.getUUID()) != null) continue;
            Home parents = parentsHome(id, f, folk);
            if (parents != null) moveIn(level, v, parents, List.of(f), day, false);
        }
        // The leader's household in the leader's hall.
        leaderMovesIn(level, v, folk, day);
        // Those with nowhere of their own: couples and families first, then those who have waited longest.
        List<List<VillageFolkEntity>> waiting = waiting(id, folk, day);
        for (List<VillageFolkEntity> household : waiting) {
            Home h = vacancyFor(level, id, household);
            if (h == null) break;
            settle(level, v, h, household, day);
        }
        // The builders' list: a house for whoever still waits, with none standing empty.
        Villages.HOUSE_WANTED.put(id, wantsAHouse(level, id));
        // A bed for every child (and any fetched from the shop that never came: delivered).
        for (BedErrand e : List.copyOf(BED_ERRANDS.values())) {
            if (!e.village().equals(id) || level.getGameTime() - e.started() < 6000L) continue;
            Home eh = homes.get(e.house().asLong());
            if (eh != null) deliver(level, v, eh, e, null);
            else BED_ERRANDS.values().remove(e);
        }
        for (Home h : homes.values()) childBeds(level, v, h, day);
        // The rent, the saving and the buying are payday's (Market.tick, after the wages: payday).
    }

    /**
     * Whoever leads the village lives in the leader's hall, with its partner and their children: the
     * household before it moves out (back on the list for a house of its own), and the new one moves
     * in, its old house going back to the village (bought back at half what they paid, if it was theirs).
     */
    static void leaderMovesIn(ServerLevel level, Villages.Village v, List<VillageFolkEntity> folk, long day) {
        UUID id = v.id();
        Home hall = null;
        for (Home h : homes(id).values()) if (seat(h)) { hall = h; break; }
        if (hall == null || Ledger.raising(id, hall.anchor) || !level.isLoaded(hall.anchor)) return;
        UUID leader = Villages.elder(id);
        VillageFolkEntity f = leader == null ? null : loaded(id, leader);
        if (f == null || f.isBaby() || f.isHired() || hall.members.contains(leader)) return;
        if (!hall.members.isEmpty()) {
            List<UUID> out = new ArrayList<>(hall.members);
            hall.members.clear();
            save(id, hall);
            List<String> names = new ArrayList<>();
            for (UUID m : out) {
                VillageFolkEntity o = loaded(id, m);
                if (o == null) continue;
                o.forgetBed();
                if (!o.isBaby()) names.add(o.displayNameCap());
            }
            if (!names.isEmpty()) Villages.tell(id, day, String.join(" and ", names) + " moved out of the leader's hall");
        }
        List<VillageFolkEntity> household = new ArrayList<>();
        household.add(f);
        VillageFolkEntity p = partner(f, folk);
        if (p != null) household.add(p);
        for (VillageFolkEntity c : folk) {
            if (c.isBaby() && (childOf(c, f) || p != null && childOf(c, p))) household.add(c);
        }
        Home old = homeOf(id, leader);
        hall.tenure = Tenure.GIVEN;                    // the one free roof: it goes with the office
        hall.price = 0;
        hall.rent = 0;
        hall.owed = 0;
        hall.since = day;
        moveIn(level, v, hall, household, day, true);
        if (old != null && old != hall && old.members.isEmpty() && old.tenure != Tenure.PLAYER) {
            if (old.tenure == Tenure.OWNED && old.price > 0) f.earn(Ledger.takeCoins(id, old.price / 2));
            vacate(id, old, f);                        // and what they had put by toward buying it, back to them
        }
        Villages.tell(id, day, names(household) + " moved into the leader's hall");
        f.persona().remember(day, "we moved into the leader's hall", 7);
        FolkTalk.speak(f, FolkTalk.pick(level.getRandom(), "The leader's hall! I'll try to deserve it.",
            "A roof the whole village built. We'll keep it well.", "Our new home — and the village's business under it."));
    }

    @Nullable
    static VillageFolkEntity partner(VillageFolkEntity f, List<VillageFolkEntity> folk) {
        UUID p = f.life().partner();
        if (p == null) return null;
        for (VillageFolkEntity o : folk) if (o.getUUID().equals(p) && !o.isBaby()) return o;
        return null;
    }

    /** Is this one a child (or a grown child) of that one? By the names its parents were given. */
    static boolean childOf(VillageFolkEntity child, VillageFolkEntity parent) {
        if (child.parentIds().contains(parent.getUUID())) return true;
        String parents = child.life().parents();
        return !parents.isEmpty() && parents.contains(parent.displayNameCap());
    }

    @Nullable
    static Home parentsHome(UUID village, VillageFolkEntity f, List<VillageFolkEntity> folk) {
        for (VillageFolkEntity o : folk) {
            if (o == f || o.isBaby() || !childOf(f, o)) continue;
            Home h = homeOf(village, o.getUUID());
            if (h != null) return h;
        }
        return null;
    }

    /**
     * Households wanting a home of their own, neediest first: grown folk with no house (and their
     * partners and children with them), then grown children still at their parents' a day after
     * they came of age.
     */
    static List<List<VillageFolkEntity>> waiting(UUID village, List<VillageFolkEntity> folk, long day) {
        List<List<VillageFolkEntity>> out = new ArrayList<>();
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        for (VillageFolkEntity f : folk) {
            if (f.isBaby() || seen.contains(f.getUUID())) continue;
            Home h = homeOf(village, f.getUUID());
            boolean homeless = h == null;
            boolean grownAtHome = false;
            if (h != null && f.life().partner() == null) {
                // At its parents' still: a grown child of somebody in the house.
                for (UUID m : h.members) {
                    VillageFolkEntity o = loaded(village, m);
                    if (o != null && o != f && !o.isBaby() && childOf(f, o)) { grownAtHome = true; break; }
                }
                if (grownAtHome && day - Math.max(0, f.bornDay() + VillageFolkEntity.GROW_DAYS) < 1) grownAtHome = false;
            }
            if (!homeless && !grownAtHome) continue;
            List<VillageFolkEntity> household = new ArrayList<>();
            household.add(f);
            seen.add(f.getUUID());
            VillageFolkEntity p = partner(f, folk);
            if (p != null && homeOf(village, p.getUUID()) == null && !seen.contains(p.getUUID())) {
                household.add(p);
                seen.add(p.getUUID());
            }
            if (homeless) {
                for (VillageFolkEntity c : folk) {
                    if (c.isBaby() && !seen.contains(c.getUUID()) && homeOf(village, c.getUUID()) == null
                            && (childOf(c, f) || p != null && childOf(c, p))) {
                        household.add(c);
                        seen.add(c.getUUID());
                    }
                }
            }
            out.add(household);
        }
        out.sort((a, b) -> {
            int ka = (int) a.stream().filter(VillageFolkEntity::isBaby).count(), kb = (int) b.stream().filter(VillageFolkEntity::isBaby).count();
            if (ka != kb) return Integer.compare(kb, ka);
            if (a.size() != b.size()) return Integer.compare(b.size(), a.size());
            boolean ha = homeOf(village, a.get(0).getUUID()) == null, hb = homeOf(village, b.get(0).getUUID()) == null;
            return Boolean.compare(hb, ha);                        // the homeless before the grown children at home
        });
        return out;
    }

    /** The empty house that suits this household best: room for its grown folk, the smallest that does. */
    @Nullable
    static Home vacancyFor(ServerLevel level, UUID village, List<VillageFolkEntity> household) {
        int adults = (int) household.stream().filter(f -> !f.isBaby()).count();
        Home best = null;
        int bestBeds = Integer.MAX_VALUE;
        for (Home h : homes(village).values()) {
            if (!h.vacant() || seat(h) || Ledger.raising(village, h.anchor)) continue;
            int beds = bedsIn(level, village, h).size();
            if (beds < Math.max(1, adults)) continue;
            // A manor is kept for a household that could buy it (it rents it first, like any, and buys
            // it on payday if it wants to own).
            if (h.structure.equals("manor") && !canBuy(level, village, h, household)) continue;
            if (beds < bestBeds) { best = h; bestBeds = beds; }
        }
        return best;
    }

    /**
     * Is the town rich enough to sell outright (the Stone Age past, 120 coins in the treasury,
     * sixteen folk)? Then it keeps an empty manor for a household that could buy it, and builds one
     * for an owner grown rich enough to move up. Its houses it lets, young or rich, and sells to the
     * tenants who save for them.
     */
    public static boolean forSale(UUID village) {
        if (SALE_FOR_TESTS != null) return SALE_FOR_TESTS;
        return Villages.ageOf(village).ordinal() >= Villages.Age.STONE.ordinal()
            && Ledger.coins(village) >= 120 && Villages.headcount(village) >= 16;
    }

    /** What a house sells for: by its size and the age of the town (a tenth less under the town's Home Loans: CityTree). */
    static int price(UUID village, Home h) {
        int base = h.structure.equals("manor") ? 120 : Ledger.grown(village, h.anchor) ? 55 : 35;
        return (int) Math.round(base * (1.0 + 0.25 * Villages.ageOf(village).ordinal()) * CityTree.pricePercent(village) / 100.0
            * Decor.pricePercent(village, h.anchor) / 100.0);                // a furnished house is worth more (Decor)
    }

    /** How big a house is, for its rent: a house 1, a two-storey house 2, a manor 4. */
    static int size(UUID village, Home h) {
        return h.structure.equals("manor") ? 4 : Ledger.grown(village, h.anchor) ? 2 : 1;
    }

    /**
     * A day's rent, scaled to the wages: half a field hand's day for a house, a field hand's day for
     * a two-storey house, two for a manor (rounded up). A field hand gets a coin in a hamlet, two in
     * a village or a town, three in a city, so a house is a coin a day until the place is a city. Half
     * that under a leader elected for homes. Once the town has Cheap Homes (CityTree), a fifth off:
     * a rent of three coins or more is four-fifths of itself, rounded; a rent of one or two coins
     * is too small to cut, and is let off one payday in five instead (tenants).
     */
    static int rent(UUID village, Home h) {
        return CityTree.rent(village, baseRent(village, h));
    }

    /** The rent before the town's Cheap Homes. */
    static int baseRent(UUID village, Home h) {
        int hand = Wealth.tradeWage(AssistantEntity.StationTask.FARM, village);
        int r = Math.max(1, (size(village, h) * hand + 1) / 2);
        if (Elections.mandate(village) == Values.Value.HOMES) r = Math.max(1, r / 2);
        return r;
    }

    static int purses(List<VillageFolkEntity> household) {
        int n = 0;
        for (VillageFolkEntity f : household) if (!f.isBaby()) n += f.purse();
        return n;
    }

    static List<VillageFolkEntity> grown(List<VillageFolkEntity> household) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (VillageFolkEntity f : household) if (!f.isBaby()) out.add(f);
        return out;
    }

    /** How many of the household earn a wage (have a trade). */
    static int earners(List<VillageFolkEntity> household) {
        int n = 0;
        for (VillageFolkEntity f : household) if (!f.isBaby() && f.stationTask() != AssistantEntity.StationTask.NONE) n++;
        return n;
    }

    static boolean canBuy(ServerLevel level, UUID village, Home h, List<VillageFolkEntity> household) {
        return forSale(village) && purses(household) >= price(village, h);
    }

    /** Take a sum out of a household's purses, the grown folk with the most first. */
    static boolean pay(List<VillageFolkEntity> household, int sum) {
        if (purses(household) < sum) return false;
        List<VillageFolkEntity> payers = new ArrayList<>(household);
        payers.removeIf(VillageFolkEntity::isBaby);
        payers.sort((a, b) -> Integer.compare(b.purse(), a.purse()));
        int left = sum;
        for (VillageFolkEntity f : payers) {
            int k = Math.min(left, f.purse());
            if (k > 0 && f.spend(k)) left -= k;
            if (left <= 0) break;
        }
        return left <= 0;
    }

    /**
     * A household takes a house: the village's, as its tenant (rich or poor: it never gives them, and
     * one that wants a house of its own saves up and buys it), or a player's, let to it.
     */
    static void settle(ServerLevel level, Villages.Village v, Home h, List<VillageFolkEntity> household, long day) {
        UUID id = v.id();
        String names = names(household);
        String where = address(id, v, h);
        String how;
        if (h.tenure == Tenure.PLAYER) {
            h.rent = Math.max(1, h.rent);
            how = "rented " + where + " from " + h.landlordName + " at " + h.rent + coins(h.rent) + " a day";
        } else {
            h.tenure = Tenure.RENTED;
            h.price = price(id, h);
            h.rent = rent(id, h);
            h.owed = 0;
            if (h.saved > 0) Ledger.addCoins(id, h.saved);   // savings left behind in an empty house: nobody's now
            h.saved = 0;
            how = rentFree(household)
                ? "moved into " + where + ", rent-free: founders of the village, until they can afford the " + h.rent + coins(h.rent) + " a day"
                : "rented " + where + " from the village at " + h.rent + coins(h.rent) + " a day";
        }
        // Anyone it is leaving behind (a grown child moving out) goes off their old house's books.
        List<Home> left = new ArrayList<>();
        for (VillageFolkEntity f : household) {
            Home was = homeOf(id, f.getUUID());
            if (was != null && was != h) { was.members.remove(f.getUUID()); save(id, was); left.add(was); }
        }
        h.since = day;
        moveIn(level, v, h, household, day, true);
        Villages.tell(id, day, names + " " + how);
        for (VillageFolkEntity f : household) {
            if (f.isBaby()) continue;
            f.persona().remember(day, (left.isEmpty() ? "we " : "I moved out and ") + how + " on day " + day, 6);
        }
        VillageFolkEntity first = household.get(0);
        boolean saving = h.tenure == Tenure.RENTED && wish(id, h, household).yes();
        if (h.tenure == Tenure.RENTED && rentFree(household)) {
            FolkTalk.speak(first, FolkTalk.pick(level.getRandom(), "Our own roof, and not a coin to pay till we can. That's founders' luck.",
                "Free till we can pay our way, they say. We'll not be long.", "A house for nothing, for now. We built this place, after all."));
            return;
        }
        if (!left.isEmpty()) {
            FolkTalk.speak(first, saving
                ? FolkTalk.pick(level.getRandom(), "A place of my own at last! Rented for now — I'll save up and buy it.", "My own front door! Well, the village's. For now.")
                : FolkTalk.pick(level.getRandom(), "A place of my own at last!", "My own front door!", "Off I go — I'll visit, I promise."));
        } else {
            FolkTalk.speak(first, saving
                ? FolkTalk.pick(level.getRandom(), "Home! We'll save up and make it ours.", "Rented for now — ours one day.")
                : FolkTalk.pick(level.getRandom(), "Home!", "A roof over our heads!", "This'll do us nicely."));
        }
    }

    /** These folk live here now: on the books, their beds in it, and their things carried over. */
    static void moveIn(ServerLevel level, Villages.Village v, Home h, List<VillageFolkEntity> who, long day, boolean carry) {
        UUID id = v.id();
        BlockPos to = chestOf(level, id, h);
        for (VillageFolkEntity f : who) {
            Home was = homeOf(id, f.getUUID());
            if (was == h) continue;
            if (was != null) { was.members.remove(f.getUUID()); save(id, was); }
            h.members.add(f.getUUID());
            f.forgetBed();
            BlockPos from = was == null ? null : chestOf(level, id, was);
            if (carry && !f.isBaby() && to != null) MOVES.put(f.getUUID(), new Move(from, to, was != null && was.members.isEmpty(),
                level.getGameTime(), new boolean[]{ from == null }));
        }
        save(id, h);
    }

    /** Two folk who are together live together: in the better of their houses; the other goes back to the village. */
    static void together(ServerLevel level, Villages.Village v, VillageFolkEntity a, VillageFolkEntity b, @Nullable Home ha, @Nullable Home hb, long day) {
        UUID id = v.id();
        Home keep, leave;
        if (ha == null) { keep = hb; leave = null; }
        else if (hb == null) { keep = ha; leave = null; }
        else {
            // The leader's hall over anything: it goes with the office.
            int sa = (seat(ha) ? 1000 : 0) + (ha.tenure == Tenure.OWNED ? 100 : 0) + bedsIn(level, id, ha).size();
            int sb = (seat(hb) ? 1000 : 0) + (hb.tenure == Tenure.OWNED ? 100 : 0) + bedsIn(level, id, hb).size();
            keep = sa >= sb ? ha : hb;
            leave = keep == ha ? hb : ha;
        }
        VillageFolkEntity mover = keep == ha ? b : a;
        List<VillageFolkEntity> moving = new ArrayList<>();
        moving.add(mover);
        // Its children come with it.
        if (leave != null) {
            for (UUID m : new ArrayList<>(leave.members)) {
                VillageFolkEntity c = loaded(id, m);
                if (c != null && c.isBaby() && childOf(c, mover)) moving.add(c);
            }
        }
        boolean emptied = leave != null && leave.members.size() <= moving.size();
        moveIn(level, v, keep, moving, day, true);
        if (leave != null && leave.members.isEmpty()) {
            // Back to the village: bought back at half what was paid, if it was theirs.
            if (leave.tenure == Tenure.OWNED && leave.price > 0) {
                int back = Ledger.takeCoins(id, leave.price / 2);
                mover.earn(back);
                Villages.tell(id, day, mover.displayNameCap() + " sold " + address(id, v, leave) + " back to the village for " + back + coins(back));
            }
            vacate(id, leave, mover);                  // what it had put by toward buying it goes with it
        }
        if (emptied || leave == null) {
            Villages.tell(id, day, mover.displayNameCap() + " moved in with " + (mover == a ? b : a).displayNameCap() + " at " + address(id, v, keep));
        }
    }

    /** A household too big for its beds buys a bed at the shop for each child that has none, and sets it up across from the parents'. */
    static void childBeds(ServerLevel level, Villages.Village v, Home h, long day) {
        // One bed at a time, as many as the children want (twins want two), while the shop has them.
        for (int i = 0; i < 4; i++) if (!childBed(level, v, h, day)) return;
    }

    /** One bed bought and set up for a child that has none; false if none was wanted, or none could be had. */
    static boolean childBed(ServerLevel level, Villages.Village v, Home h, long day) {
        UUID id = v.id();
        if (h.members.isEmpty() || !level.isLoaded(h.anchor) || Ledger.raising(id, h.anchor)) return why(h, "empty, unloaded or going up");
        List<VillageFolkEntity> members = loadedMembers(id, h);
        List<BlockPos> beds = bedsIn(level, id, h);
        int children = (int) members.stream().filter(VillageFolkEntity::isBaby).count();
        if (children == 0 || beds.size() >= members.size()) return why(h, "none wanted: " + beds.size() + " beds for " + members.size());
        for (BedErrand e : BED_ERRANDS.values()) if (e.house().equals(h.anchor)) return why(h, "a bed on its way from the shop");
        // A bed: out of the stores (the tailor's), bought with the parents' coin; or the village's gift if
        // the parents have none and the leader is a generous one.
        if (Market.stock(level, id, s -> s.is(ItemTags.BEDS)) == 0) return why(h, "no bed in the stores");
        Market.Good g = Market.goodFor(new ItemStack(Items.WHITE_BED));
        int price = g == null ? 4 : Math.max(1, Market.sellPrice(g, Market.stock(level, id, g.what()), false));
        boolean paid = pay(members, price);
        if (!paid && !generous(id)) return why(h, "the parents cannot pay " + price);
        BlockPos spot = bedSpot(level, id, h, beds);
        if (spot == null) {
            if (paid) members.stream().filter(m -> !m.isBaby()).max(Comparator.comparingInt(VillageFolkEntity::purse)).ifPresent(m -> m.earn(price));
            return why(h, "no room for it in the house");
        }
        ItemStack bed = Crafts.takeOne(level, v, s -> s.is(ItemTags.BEDS));
        if (bed.isEmpty() || !(Block.byItem(bed.getItem()) instanceof BedBlock bb)) {
            if (paid) members.stream().filter(m -> !m.isBaby()).findFirst().ifPresent(m -> m.earn(price));
            if (!bed.isEmpty()) Crafts.store(level, v, bed);
            return why(h, "the bed could not be taken from the stores");
        }
        if (paid) Ledger.addCoins(id, price);
        Direction lie = spotLie(level, spot);
        VillageFolkEntity parent0 = members.stream().filter(m -> !m.isBaby() && !BED_ERRANDS.containsKey(m.getUUID())).findFirst().orElse(null);
        VillageFolkEntity child0 = members.stream().filter(VillageFolkEntity::isBaby)
            .max(Comparator.comparingLong(VillageFolkEntity::bornDay)).orElse(null);
        BlockPos shop = Villages.builtAt(id, "shop");
        if (shop != null && Cafe.open(id, "shop") && parent0 != null && !instantBeds) {
            // Bought at the shop: a parent goes for it and carries it home (moving), and sets it up.
            BED_ERRANDS.put(parent0.getUUID(), new BedErrand(id, h.anchor, shop, spot, lie, bb.defaultBlockState().setValue(BedBlock.FACING, lie),
                level.getGameTime(), new boolean[]{ false }, child0 == null ? "the little one" : child0.displayNameCap()));
            Villages.tell(id, day, parent0.displayNameCap() + " bought a bed at the shop for " + (child0 == null ? "a child" : child0.displayNameCap())
                + " (" + price + coins(price) + ")");
            parent0.persona().remember(day, "I bought a bed for " + (child0 == null ? "the little one" : child0.displayNameCap()), 4);
            why(h, "bought one at the shop");
            return true;
        }
        layBed(level, v, h, spot, lie, bb.defaultBlockState());
        VillageFolkEntity parent = members.stream().filter(m -> !m.isBaby()).findFirst().orElse(null);
        VillageFolkEntity child = members.stream().filter(VillageFolkEntity::isBaby)
            .max(Comparator.comparingLong(VillageFolkEntity::bornDay)).orElse(null);
        Villages.tell(id, day, (parent == null ? "the village" : parent.displayNameCap()) + (paid ? " bought a bed at the shop for " : " was given a bed for ")
            + (child == null ? "a child" : child.displayNameCap()) + " (" + price + coins(price) + ")");
        if (parent != null) parent.persona().remember(day, "I bought a bed for " + (child == null ? "the little one" : child.displayNameCap()), 4);
        why(h, "bought one");
        return true;
    }

    private static final Map<Long, String> WHY = new ConcurrentHashMap<>();

    private static boolean why(Home h, String reason) {
        WHY.put(h.anchor.asLong(), reason);
        return false;
    }

    /** Tests: what became of the last look for a child's bed in this house. */
    public static String whyForTests(BlockPos anchor) {
        return WHY.getOrDefault(anchor.asLong(), "not looked");
    }

    /** Tests: a child's bed set up there and then, not fetched from the shop. */
    static boolean instantBeds;

    public static void instantBedsForTests(boolean on) {
        instantBeds = on;
    }

    /** A child's bed set down in its house: any rug there rolled back into the stores, and the bed on the house's books. */
    static void layBed(ServerLevel level, Villages.Village v, Home h, BlockPos spot, Direction lie, BlockState bed) {
        for (BlockPos rug : List.of(spot, spot.relative(lie))) {
            BlockState was = level.getBlockState(rug);
            if (was.is(BlockTags.WOOL_CARPETS)) Crafts.store(level, v, new ItemStack(was.getBlock().asItem()));   // rolled up, back to the stores
        }
        BlockState st = bed.setValue(BedBlock.FACING, lie);
        level.setBlock(spot, st.setValue(BedBlock.PART, BedPart.FOOT), 3);
        level.setBlock(spot.relative(lie), st.setValue(BedBlock.PART, BedPart.HEAD), 3);
        String extra = Ledger.note(v.id(), "homebeds/" + h.anchor.asLong());
        Ledger.note(v.id(), "homebeds/" + h.anchor.asLong(), (extra == null || extra.isEmpty() ? "" : extra + ",") + spot.relative(lie).asLong());
    }

    /**
     * A parent with a child's bed to fetch: to the shop for it, then home with it, and set it up across
     * the room from its own. Returns whether it is about it. Too long about it (a shut door, a long
     * way round), the shop's boy brings it round and it is set up all the same.
     */
    static boolean bedErrand(VillageFolkEntity f, ServerLevel level) {
        BedErrand e = BED_ERRANDS.get(f.getUUID());
        if (e == null) return false;
        Villages.Village v = Villages.get(e.village());
        Home h = v == null ? null : homes(e.village()).get(e.house().asLong());
        long now = level.getGameTime();
        if (v == null || h == null) { BED_ERRANDS.remove(f.getUUID()); return false; }
        if (now - e.started() > 4800L) {
            deliver(level, v, h, e, null);
            return false;
        }
        BlockPos target = e.collected()[0] ? e.spot() : e.shop();
        if (!level.isLoaded(target)) return false;
        if (f.blockPosition().distSqr(target) > (e.collected()[0] ? 2.5 * 2.5 : 4.0 * 4.0)) {
            if (f.getNavigation().isDone() || (now - e.started()) % 80L == 0) f.walkTo(target, 0.9D);
            f.hobbyNow = e.collected()[0] ? "carrying a bed home for " + e.child() : "off to the shop for a bed for " + e.child();
            f.lastLeisureTick = f.tickCount;
            return true;
        }
        f.getNavigation().stop();
        f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        if (!e.collected()[0]) {
            e.collected()[0] = true;
            FolkTalk.speak(f, FolkTalk.pick(level.getRandom(), "One bed, please — for " + e.child() + ".",
                "A bed for " + e.child() + ". The good one."));
            return true;
        }
        deliver(level, v, h, e, f);
        return true;
    }

    /** The bed set up (where it was meant to go, or wherever there is room now). */
    private static void deliver(ServerLevel level, Villages.Village v, Home h, BedErrand e, @Nullable VillageFolkEntity f) {
        BED_ERRANDS.values().remove(e);
        BlockPos spot = e.spot();
        Direction lie = e.lie();
        if (!clear(level, spot) || !clear(level, spot.relative(lie))) {
            spot = bedSpot(level, v.id(), h, bedsIn(level, v.id(), h));
            if (spot == null) {                                                // no room after all: back to the stores
                Crafts.store(level, v, new ItemStack(e.bed().getBlock().asItem()));
                return;
            }
            lie = spotLie(level, spot);
        }
        layBed(level, v, h, spot, lie, e.bed());
        if (f != null) FolkTalk.speak(f, FolkTalk.pick(level.getRandom(), "There — a bed of your own, " + e.child() + ".",
            "All made up. Sleep well tonight, " + e.child() + "."));
    }

    /**
     * Where a bought bed goes: on the ground floor inside the house, two clear cells on a sound floor,
     * not across a door, as far from the grown-ups' beds as the room allows.
     */
    @Nullable
    static BlockPos bedSpot(ServerLevel level, UUID village, Home h, List<BlockPos> beds) {
        int[] half = BuildGoal.footprint(drawing(village, h));
        double cx = 0, cz = 0;
        for (BlockPos b : beds) { cx += b.getX(); cz += b.getZ(); }
        if (!beds.isEmpty()) { cx /= beds.size(); cz /= beds.size(); } else { cx = h.anchor.getX(); cz = h.anchor.getZ(); }
        BlockPos best = null;
        double far = -1;
        int y = h.anchor.getY();
        for (int dx = -half[0] + 1; dx <= half[0] - 1; dx++) {
            for (int dz = -half[1] + 1; dz <= half[1] - 1; dz++) {
                BlockPos p = new BlockPos(h.anchor.getX() + dx, y, h.anchor.getZ() + dz);
                Direction lie = spotLie(level, p);
                if (!clear(level, p) || !clear(level, p.relative(lie))) continue;
                double d = (p.getX() - cx) * (p.getX() - cx) + (p.getZ() - cz) * (p.getZ() - cz);
                if (d > far) { far = d; best = p; }
            }
        }
        return best;
    }

    /** A cell a bed may stand in: air, on something solid, under a roof, and not by a door or a ladder. */
    static boolean clear(ServerLevel level, BlockPos p) {
        // A rug may be rolled back for it (Interiors); anything else stands in the way.
        BlockState here = level.getBlockState(p);
        if (!here.isAir() && !here.is(BlockTags.WOOL_CARPETS) || !level.getBlockState(p.above()).isAir()) return false;
        if (!level.getBlockState(p.below()).isSolidRender(level, p.below())) return false;
        // Under a roof: something built over it (the sky's light inside a new house can take a while to go).
        if (level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, p.getX(), p.getZ()) <= p.getY() + 1) return false;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockState n = level.getBlockState(p.relative(d));
            if (n.getBlock() instanceof net.minecraft.world.level.block.DoorBlock || n.is(Blocks.LADDER)) return false;
        }
        return true;
    }

    /** Which way a bed at this cell lies: its head toward the nearer wall, with a cell for the head before it. */
    static Direction spotLie(ServerLevel level, BlockPos p) {
        Direction best = Direction.NORTH;
        int nearest = Integer.MAX_VALUE;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            for (int k = 1; k <= 6; k++) {
                if (!level.getBlockState(p.relative(d, k)).isAir()) {
                    if (k >= 2 && k < nearest) { nearest = k; best = d; }
                    break;
                }
            }
        }
        return best;
    }

    /** The household's chest: the house's own chest as it stands now. */
    @Nullable
    static BlockPos chestOf(ServerLevel level, UUID village, Home h) {
        Ledger.Building b = building(village, h.anchor);
        if (b == null || !level.isLoaded(h.anchor)) return null;
        for (BuildGoal.Placement p : BuildGoal.plan(drawing(village, h), b.anchor(), b.facing(), 13)) {
            if (p.part() == BuildGoal.Part.CHEST && level.getBlockState(p.pos()).getBlock() instanceof net.minecraft.world.level.block.ChestBlock) return p.pos();
        }
        return null;
    }

    // ------------------------------------------------------------------ belongings

    private static final String KEEP = "mca_keepsake";

    /** Mark a thing as this folk's own (a present, something it bought for itself): never banked in the stores. */
    public static void keepsake(ItemStack s, VillageFolkEntity owner) {
        if (s.isEmpty()) return;
        CompoundTag t = s.has(DataComponents.CUSTOM_DATA) ? s.get(DataComponents.CUSTOM_DATA).copyTag() : new CompoundTag();
        t.putString(KEEP, owner.getStringUUID());
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
    }

    public static boolean isKeepsake(ItemStack s) {
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        return d != null && d.contains(KEEP);
    }

    /**
     * Every few ticks for each folk (VillageFolkEntity): moving house, its things from the old chest
     * to the new; or, off work, its keepsakes home to its chest. True while it is about it.
     */
    public static boolean moving(VillageFolkEntity f, ServerLevel level) {
        UUID village = f.ownerId();
        if (village == null || f.isBaby() || f.isSleeping() || f.getTarget() != null) return false;
        if (bedErrand(f, level)) return true;
        Move m = MOVES.get(f.getUUID());
        long now = level.getGameTime();
        if (m == null) {
            // Off work in the evening, with something of its own to put away.
            long t = level.getDayTime() % 24000L;
            if (t < 11500L || t > 13000L || f.tickCount % 200 != 0 || f.onShift()) return false;
            boolean carrying = false;
            for (ItemStack s : f.getInventoryItems()) if (isKeepsake(s)) { carrying = true; break; }
            if (!carrying) return false;
            Home h = homeOf(village, f.getUUID());
            BlockPos chest = h == null ? null : chestOf(level, village, h);
            if (chest == null) return false;
            m = new Move(null, chest, false, now, new boolean[]{ true });
            MOVES.put(f.getUUID(), m);
        }
        if (now - m.started() > 4800L) { MOVES.remove(f.getUUID()); return false; }
        BlockPos target = m.fetched()[0] ? m.to() : m.from();
        if (target == null || !level.isLoaded(target)) { MOVES.remove(f.getUUID()); return false; }
        if (f.blockPosition().distSqr(target) > 2.5 * 2.5) {
            if (f.getNavigation().isDone() || (now - m.started()) % 80L == 0) f.walkTo(target, 0.9D);
            f.hobbyNow = m.from() != null ? "moving house" : "putting its things away";
            f.lastLeisureTick = f.tickCount;
            return true;
        }
        f.getNavigation().stop();
        if (!(level.getBlockEntity(target) instanceof net.minecraft.world.Container c)) { MOVES.remove(f.getUUID()); return false; }
        f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        if (!m.fetched()[0]) {
            // The old chest: its things (all of it if the household is leaving the house, else its own).
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty()) continue;
                if (!m.whole() && !ownedBy(s, f)) continue;
                ItemStack carried = s.copy();
                keepsake(carried, f);
                ItemStack left = f.insertItem(carried);
                c.setItem(i, left.isEmpty() ? ItemStack.EMPTY : s.copyWithCount(left.getCount()));
            }
            c.setChanged();
            m.fetched()[0] = true;
            return true;
        }
        // The new chest: everything of its own goes in.
        List<ItemStack> pack = f.getInventoryItems();
        for (int i = 0; i < pack.size(); i++) {
            ItemStack s = pack.get(i);
            if (s.isEmpty() || !isKeepsake(s)) continue;
            ItemStack left = insertInto(c, s.copy());
            pack.set(i, left);
        }
        c.setChanged();
        MOVES.remove(f.getUUID());
        if (m.from() != null) FolkTalk.speak(f, FolkTalk.pick(level.getRandom(), "There — all moved in.", "That's the last of my things.", "Home sweet home."));
        return true;
    }

    /** Put a stack into a chest, onto stacks of its own kind first, then into empty slots. Returns what would not fit. */
    static ItemStack insertInto(net.minecraft.world.Container c, ItemStack s) {
        return Stacking.insert(c, s);
    }

    static boolean ownedBy(ItemStack s, VillageFolkEntity f) {
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        return d != null && f.getStringUUID().equals(d.copyTag().getString(KEEP));
    }

    // ------------------------------------------------------------------ payday: rent, saving up, buying, moving up

    /** A Merchant counts a house a good deal when its price is no more than this many days' rent. */
    static final int GOOD_DEAL_DAYS = 40;
    /** What a saving household keeps in each grown folk's purse to live on; the rest it puts by. */
    static final int LIVE_ON = 12;
    /** Rent owed past this many days' worth, the village writes off. */
    static final int WRITE_OFF_DAYS = 7;

    /**
     * Once a day, on payday, after the wages (Market.tick): the village's tenants pay their rent
     * into the treasury (and what they owe, when they can), those that want a house of their own put
     * part of their pay by toward its price and buy it once they have it, a player's tenants pay the
     * player, and owners grown rich move up to a manor. A house given before the village let its
     * houses is let to its household from now on.
     */
    static void payday(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        enrol(id);
        for (Home h : homes(id).values()) {
            if (h.members.isEmpty() || seat(h)) continue;
            List<VillageFolkEntity> household = loadedMembers(id, h);
            if (household.isEmpty()) continue;
            switch (h.tenure) {
                case GIVEN -> letInstead(v, h, household, day);
                case RENTED -> tenants(level, v, h, household, day);
                case PLAYER -> {
                    if (pay(household, h.rent)) {
                        String key = "rentdue/" + h.landlord;
                        int due = parse(Ledger.note(id, key)) + h.rent;
                        Ledger.note(id, key, Integer.toString(due));
                    } else {
                        h.owed += h.rent;
                        save(id, h);
                    }
                }
                case OWNED -> moveUp(level, v, h, household, day);
            }
        }
    }

    /** A house the village gave, before it let them: its household rents it now, from the next payday. */
    static void letInstead(Villages.Village v, Home h, List<VillageFolkEntity> household, long day) {
        UUID id = v.id();
        h.tenure = Tenure.RENTED;
        h.price = price(id, h);
        h.rent = rent(id, h);
        h.owed = 0;
        save(id, h);
        Villages.tell(id, day, "the village gives no houses now: " + names(household) + " rent " + address(id, v, h) + " from it at "
            + h.rent + coins(h.rent) + " a day");
    }

    /**
     * A tenant's payday. The rent, and anything owed, out of the household's purses (then out of
     * what it has put by); what it can't pay goes on the slate. Hardship: a house where nobody earns
     * pays no rent, a generous leader lets off whatever a tenant is short, and the village writes off
     * more than a week's rent owed: nobody is put out of a house for want of it. Then, if it wants a
     * house of its own, it puts by toward the price, and buys the house once it has it.
     */
    static void tenants(ServerLevel level, Villages.Village v, Home h, List<VillageFolkEntity> household, long day) {
        UUID id = v.id();
        h.rent = rent(id, h);                              // the going rent, as the place's wages go
        h.price = price(id, h);
        boolean hard = earners(household) == 0;
        boolean free = rentFree(household);
        if (free && affords(household, h)) {
            startPaying(level, v, h, household, day);
            free = false;
        }
        boolean letOff = CityTree.rentFreeToday(id, baseRent(id, h), day);   // Cheap Homes: a small rent, one payday in five
        int due = (hard || free || letOff ? 0 : h.rent) + h.owed;
        int paid = take(household, h, due);
        if (paid > 0) {
            Ledger.addCoins(id, paid);
            Economy.rent(id, paid);
        }
        h.owed = due - paid;
        if (h.owed > 0 && (hard || generous(id))) {
            h.owed = 0;                                    // let off
        } else if (h.owed > WRITE_OFF_DAYS * Math.max(1, h.rent)) {
            Villages.tell(id, day, "the village wrote off the " + h.owed + coins(h.owed) + " of rent " + names(household) + " owed on "
                + address(id, v, h));
            h.owed = 0;
        }
        Wish w = wish(id, h, household);
        if (!w.yes() && h.saved > 0 && w.score() <= 0) giveBack(household, h);     // changed its mind: its savings back
        if (w.yes() && h.owed == 0) {
            int was = h.saved;
            putBy(h, household, day);
            // The town's Housing Fund (CityTree): the treasury adds a coin for every ten put by.
            h.saved += CityTree.housingFund(id, h.saved - was, h.price - h.saved);
        }
        if (w.yes() && h.price > 0 && h.saved >= h.price) {
            buy(level, v, h, household, day);
            return;
        }
        save(id, h);
    }

    /** Rent a founder's house is free of, until it is afforded: a week of it in hand over what each grown folk lives on. */
    static final int AFFORD_DAYS = 7;

    /** Is this a household of founders still living rent-free (any of its grown folk)? */
    static boolean rentFree(List<VillageFolkEntity> household) {
        for (VillageFolkEntity f : household) if (!f.isBaby() && f.rentFree()) return true;
        return false;
    }

    /** Can a household afford its rent: somebody in it earning, and a week's rent in hand (its purses and
     *  what it has put by) over the dozen coins a head it lives on? */
    static boolean affords(List<VillageFolkEntity> household, Home h) {
        if (earners(household) == 0) return false;
        return purses(household) + h.saved >= AFFORD_DAYS * Math.max(1, h.rent) + LIVE_ON * grown(household).size();
    }

    /** A household of founders can afford its rent now: it pays from today, like everybody else, for good. */
    static void startPaying(ServerLevel level, Villages.Village v, Home h, List<VillageFolkEntity> household, long day) {
        UUID id = v.id();
        for (VillageFolkEntity f : household) f.rentFree(false);
        Villages.tell(id, day, names(household) + " can afford their rent now: from today they pay " + h.rent + coins(h.rent)
            + " a day for " + address(id, v, h));
        for (VillageFolkEntity f : grown(household)) {
            f.persona().remember(day, "we started paying rent on day " + day + ": we can afford it now", 4);
        }
        List<VillageFolkEntity> grown = grown(household);
        FolkTalk.speak(grown.isEmpty() ? household.get(0) : grown.get(0), FolkTalk.pick(level.getRandom(), "We can pay our way now. Rent from today — fair's fair.",
            "No more living on the village's kindness: we pay rent now.", "Rent day, our first. Feels like we've made it."));
    }

    /** Take a sum from a household: out of its grown folk's purses, the fullest first, then out of what it has put by. Returns what it could. */
    static int take(List<VillageFolkEntity> household, Home h, int sum) {
        if (sum <= 0) return 0;
        int got = 0;
        List<VillageFolkEntity> payers = grown(household);
        payers.sort((a, b) -> Integer.compare(b.purse(), a.purse()));
        for (VillageFolkEntity f : payers) {
            int k = Math.min(sum - got, f.purse());
            if (k > 0 && f.spend(k)) got += k;
            if (got >= sum) return got;
        }
        int k = Math.min(sum - got, h.saved);
        if (k > 0) {
            h.saved -= k;
            got += k;
        }
        return got;
    }

    /**
     * Saving for the house: each grown folk puts by a third of what it was paid this morning, or all
     * its purse holds over a dozen coins, whichever is more, until the price is put by.
     */
    static void putBy(Home h, List<VillageFolkEntity> household, long day) {
        int need = h.price - h.saved;
        for (VillageFolkEntity f : grown(household)) {
            if (need <= 0) return;
            int third = Market.paidOn(f.getUUID(), day) / 3;
            int put = Math.min(need, Math.min(f.purse(), Math.max(third, f.purse() - LIVE_ON)));
            if (put > 0 && f.spend(put)) {
                h.saved += put;
                need -= put;
            }
        }
    }

    /** What a household had put by, back into its grown folk's purses, shared out. */
    static void giveBack(List<VillageFolkEntity> household, Home h) {
        List<VillageFolkEntity> grown = grown(household);
        if (h.saved <= 0 || grown.isEmpty()) return;
        int each = h.saved / grown.size(), odd = h.saved % grown.size();
        for (int i = 0; i < grown.size(); i++) grown.get(i).earn(each + (i < odd ? 1 : 0));
        h.saved = 0;
    }

    /** Saved up: the household buys the house it rents from the village, for its price, into the treasury. No more rent. */
    static void buy(ServerLevel level, Villages.Village v, Home h, List<VillageFolkEntity> household, long day) {
        UUID id = v.id();
        int price = h.price;
        h.saved -= price;
        Ledger.addCoins(id, price);
        Economy.houseSold(id, price);
        giveBack(household, h);                            // the change
        h.tenure = Tenure.OWNED;
        h.rent = 0;
        h.owed = 0;
        save(id, h);
        String where = address(id, v, h);
        Villages.tell(id, day, names(household) + " bought " + where + ", the house they rented, for " + price + coins(price)
            + " put by out of their wages");
        List<VillageFolkEntity> grown = grown(household);
        for (VillageFolkEntity f : grown) f.persona().remember(day, "we bought " + where + " for " + price + " coins, saved up out of our wages", 7);
        if (!grown.isEmpty()) FolkTalk.speak(grown.get(0), FolkTalk.pick(level.getRandom(), "It's ours now — every brick of it!",
            "Paid for! No more rent for us.", "Our own house at last. Saved for every coin of it."));
    }

    /** A household that owns its house and has grown rich buys a manor that stands empty, and sells its house back. */
    static void moveUp(ServerLevel level, Villages.Village v, Home h, List<VillageFolkEntity> household, long day) {
        UUID id = v.id();
        if (h.structure.equals("manor")) return;
        for (Home m : homes(id).values()) {
            if (!m.structure.equals("manor") || !m.vacant() || m.tenure == Tenure.PLAYER) continue;
            int price = price(id, m), back = h.price / 2;
            if (purses(household) + back < price + 20) return;
            VillageFolkEntity seller = null;
            for (VillageFolkEntity f : household) if (!f.isBaby()) { seller = f; f.earn(Ledger.takeCoins(id, back)); break; }
            if (!pay(household, price)) return;
            Ledger.addCoins(id, price);
            Economy.houseSold(id, price);
            if (m.saved > 0) Ledger.addCoins(id, m.saved);   // savings left behind in an empty house: nobody's now
            m.tenure = Tenure.OWNED;
            m.price = price;
            m.rent = 0;
            m.owed = 0;
            m.saved = 0;
            m.since = day;
            moveIn(level, v, m, household, day, true);
            h.members.clear();
            vacate(id, h, seller);
            Villages.tell(id, day, names(household) + " moved up to the manor at " + address(id, v, m) + ", bought for " + price + coins(price));
            return;
        }
    }

    /**
     * A house its household has left, back on the village's books, to let: what they had put by toward
     * buying it goes back to them (or, with nobody to take it, into the treasury).
     */
    static void vacate(UUID village, Home h, @Nullable VillageFolkEntity heir) {
        if (h.saved > 0) {
            if (heir != null) heir.earn(h.saved);
            else Ledger.addCoins(village, h.saved);
            h.saved = 0;
        }
        if (h.tenure != Tenure.PLAYER) {
            h.tenure = Tenure.RENTED;
            h.price = 0;
            h.rent = 0;
            h.owed = 0;
        }
        save(village, h);
    }

    // ------------------------------------------------------------------ who wants a house of its own

    /**
     * Whether a household wants a house of its own, how strongly (2 or more: it saves for one), why
     * (for the books: "a Free Spirit would rather rent and stay free") and the same in its own words
     * (for the talk card: "we'd rather keep our coin and our freedom").
     */
    public record Wish(boolean yes, double score, String why, String mine) {}

    /**
     * Does this household want to own the house it lives in? By what its grown folk care about most: a
     * Homemaker does (+3), a Traditionalist and a Provider like to (+2), a Guardian a little (+1), a
     * Visionary isn't fussed (0), a Free Spirit would rather rent (-3), and a Merchant buys when it is
     * a good deal (+2: the price no more than forty days' rent; else -1), averaged over the grown folk.
     * A couple settling down adds one, each child one (two at most); the middle years (thirty to
     * sixty) add one, youth (under twenty-four) and old age (sixty on) take one away. Two or more and
     * it saves; once it has started, it keeps on while it still leans that way at all.
     */
    static Wish wish(UUID village, Home h, List<VillageFolkEntity> household) {
        List<VillageFolkEntity> grown = grown(household);
        if (grown.isEmpty()) return new Wish(false, 0, "nobody grown in the house to buy it", "there's nobody grown here to buy it");
        int kids = household.size() - grown.size();
        int rent = Math.max(1, rent(village, h)), price = price(village, h);
        int days = (price + rent - 1) / rent;
        boolean deal = days <= GOOD_DEAL_DAYS;
        String we = household.size() > 1 ? "we" : "I", us = household.size() > 1 ? "us" : "me", our = household.size() > 1 ? "our" : "my";
        List<String> pro = new ArrayList<>(), con = new ArrayList<>(), proMine = new ArrayList<>(), conMine = new ArrayList<>();
        java.util.Set<Values.Value> seen = java.util.EnumSet.noneOf(Values.Value.class);
        int nature = 0, years = 0;
        for (VillageFolkEntity f : grown) {
            Values.Value top = Values.top(f);
            years += f.ageYears();
            nature += switch (top) {
                case HOMES -> 3;
                case TRADITION, FOOD -> 2;
                case SAFETY -> 1;
                case PROGRESS -> 0;
                case WEALTH -> deal ? 2 : -1;
                case LEISURE -> -3;
            };
            if (!seen.add(top)) continue;
            switch (top) {
                case HOMES -> { pro.add("a Homemaker wants a house of its own"); proMine.add(we + " want a place of " + our + " own"); }
                case TRADITION -> { pro.add("a Traditionalist likes to own the roof over its head"); proMine.add("owning your house is the proper way"); }
                case FOOD -> { pro.add("a Provider likes to own what keeps its family"); proMine.add("a family wants a roof of its own"); }
                case SAFETY -> { pro.add("a Guardian likes a roof nobody can take away"); proMine.add("nobody can put " + us + " out of " + our + " own house"); }
                case PROGRESS -> { con.add("a Visionary minds the next age more than bricks"); conMine.add("there's more to think about than bricks"); }
                case WEALTH -> {
                    (deal ? pro : con).add("a Merchant: at " + price + coins(price) + " it is " + days + " days' rent, "
                        + (deal ? "a good deal" : "not a good deal yet"));
                    (deal ? proMine : conMine).add("at " + price + coins(price) + " it's " + days + " days' rent — "
                        + (deal ? "a good deal" : "not worth it yet"));
                }
                case LEISURE -> { con.add("a Free Spirit would rather rent and stay free"); conMine.add(we + "'d rather keep " + our + " coin and " + our + " freedom"); }
            }
        }
        double score = nature / (double) grown.size();
        if (grown.size() >= 2) { score += 1; pro.add("a couple settling down"); proMine.add("we're settling down"); }
        if (kids > 0) {
            score += Math.min(2, kids);
            pro.add(kids == 1 ? "a child to raise" : kids + " children to raise");
            proMine.add(kids == 1 ? "there's the little one to think of" : "there are the children to think of");
        }
        int age = years / grown.size();
        if (age < 24) { score -= 1; con.add("young yet (" + age + ")"); conMine.add(we + "'re young yet"); }
        else if (age >= VillageFolkEntity.OLD_AT) { score -= 1; con.add("late in life to start saving (" + age + ")"); conMine.add("it's late in life to start saving"); }
        else if (age >= 30) { score += 1; pro.add("settled, at " + age); proMine.add(we + "'re settled here"); }
        boolean yes = score >= 2 || h.saved > 0 && score > 0;
        List<String> why = yes ? pro : con, mine = yes ? proMine : conMine;
        String said = why.isEmpty() ? (yes ? "it wants a place of its own" : "nothing draws it to buying")
            : String.join("; ", why.subList(0, Math.min(3, why.size())));
        String own = mine.isEmpty() ? (yes ? we + " want a place of " + our + " own" : "it suits " + us) : mine.get(0);
        return new Wish(yes, score, said, own);
    }

    /** Is this folk's household saving to buy its house? {put by, price}, or null. (Wealth.talk) */
    @Nullable
    public static int[] savingFor(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null || f.isBaby()) return null;
        Home h = homeOf(village, f.getUUID());
        if (h == null || h.tenure != Tenure.RENTED || seat(h)) return null;
        if (h.saved <= 0 && !wish(village, h, loadedMembers(village, h)).yes()) return null;
        return new int[]{ h.saved, price(village, h) };
    }

    /** This folk's share of what its household has put by toward its house (Wealth.worth). */
    /** Its share of the house its household owns, at what the house was bought for (its price when
     *  bought before prices were kept): nothing for a tenant, the leader's hall or a player's house. */
    public static int ownedShare(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null || f.isBaby()) return 0;
        Home h = homeOf(village, f.getUUID());
        if (h == null || h.tenure != Tenure.OWNED || seat(h)) return 0;
        int value = h.price > 0 ? h.price : price(village, h);
        int grown = 0;
        for (UUID m : h.members) {
            VillageFolkEntity o = loaded(village, m);
            if (o != null && !o.isBaby()) grown++;
        }
        return value / Math.max(1, grown);
    }

    /** The houses a player owns in a village: {how many, what they are worth at their price}. */
    public static int[] playerHouses(UUID village, UUID player) {
        int n = 0, worth = 0;
        for (Home h : homes(village).values()) {
            if (h.tenure != Tenure.PLAYER || !player.equals(h.landlord)) continue;
            n++;
            worth += h.price > 0 ? h.price : price(village, h);
        }
        return new int[]{ n, worth };
    }

    public static int savedShare(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null || f.isBaby()) return 0;
        Home h = homeOf(village, f.getUUID());
        if (h == null || h.saved <= 0) return 0;
        int grown = 0;
        for (UUID m : h.members) {
            VillageFolkEntity o = loaded(village, m);
            if (o != null && !o.isBaby()) grown++;
        }
        return h.saved / Math.max(1, grown);
    }

    /** What all the village's households have put by toward their houses (Economy: the village's worth). */
    public static int savedTotal(UUID village) {
        int n = 0;
        for (Home h : homes(village).values()) n += Math.max(0, h.saved);
        return n;
    }

    // ------------------------------------------------------------------ a helping hand toward a house (FolkSkills)

    /**
     * What a grant toward a folk's house came to: so much put by toward the house its household
     * rents (and means to buy), so much into its purse, which house (or null) and its price.
     */
    public record Grant(int towardHouse, int toPurse, @Nullable BlockPos house, int price) {}

    /**
     * The price of the house this folk lives in: what it sells for now, or what its household paid
     * if it owns it. A folk with no house of its own (or in the leader's hall, which is never sold)
     * gets the price of a plain house in its village as it stands. (FolkSkills: Nest Egg.)
     */
    public static int priceFor(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null) return 35;                     // a plain house in a new village
        Home h = homeOf(village, f.getUUID());
        if (h == null || seat(h)) return price(village, new Home(BlockPos.ZERO, "house"));
        if (h.tenure == Tenure.OWNED && h.price > 0) return h.price;
        return price(village, h);
    }

    /** Does this folk's household rent its house from the village and want to buy it (or has it started saving)? */
    public static boolean rentsAndWantsToOwn(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null || f.isBaby()) return false;
        Home h = homeOf(village, f.getUUID());
        if (h == null || seat(h) || h.tenure != Tenure.RENTED) return false;
        return h.saved > 0 || wish(village, h, loadedMembers(village, h)).yes();
    }

    /** Does this folk's household own the house it lives in? */
    public static boolean ownsItsHouse(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null) return false;
        Home h = homeOf(village, f.getUUID());
        return h != null && h.tenure == Tenure.OWNED;
    }

    /**
     * Coin given toward a folk's house (FolkSkills: Nest Egg). If its household rents its house from
     * the village and wants to buy it, the coin goes into what it has put by toward the price, never
     * past the price (it still buys on payday, as it always does, once what it has put by covers it);
     * whatever is over, and all of it for a folk that owns its house, has none, or would rather rent,
     * goes into its own purse. The caller has already taken the coin from wherever it came from.
     */
    public static Grant grantTowardHouse(ServerLevel level, VillageFolkEntity f, int coins) {
        if (coins <= 0) return new Grant(0, 0, homeOf(f), priceFor(f));
        UUID village = f.ownerId();
        Home h = village == null ? null : homeOf(village, f.getUUID());
        int toward = 0, price = priceFor(f);
        if (h != null && rentsAndWantsToOwn(f)) {
            h.price = price(village, h);
            price = h.price;
            toward = Math.max(0, Math.min(coins, h.price - h.saved));
            h.saved += toward;
            save(village, h);
        }
        int rest = coins - toward;
        if (rest > 0) f.earn(rest);
        return new Grant(toward, rest, h == null ? null : h.anchor, price);
    }

    // ------------------------------------------------------------------ builders, the leader, talk

    /** Is there a household waiting for a house, and no house standing empty for it? (Villages.projectsWanted) */
    public static boolean wantsAHouse(ServerLevel level, UUID village) {
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) if (a instanceof VillageFolkEntity f && !f.isShowcase()) folk.add(f);
        List<List<VillageFolkEntity>> waiting = waiting(village, folk, level.getDayTime() / 24000L);
        if (waiting.isEmpty()) return false;
        for (Home h : homes(village).values()) if (h.vacant() && !h.structure.equals("manor") && !seat(h)) return false;
        return true;
    }

    /** Is there a household rich enough to buy a manor, and none standing empty? */
    public static boolean wantsAManor(UUID village) {
        if (!forSale(village) || Villages.ageOf(village).ordinal() < Villages.Age.IRON.ordinal()) return false;
        for (Home h : homes(village).values()) if (h.structure.equals("manor") && h.vacant()) return false;
        Home probe = new Home(BlockPos.ZERO, "manor");
        int price = price(village, probe);
        for (Home h : homes(village).values()) {
            if (h.tenure != Tenure.OWNED || h.structure.equals("manor") || seat(h)) continue;
            int purse = 0;
            for (UUID m : h.members) {
                VillageFolkEntity f = loaded(village, m);
                if (f != null && !f.isBaby()) purse += f.purse();
            }
            if (purse + h.price / 2 >= price + 20) return true;
        }
        return false;
    }

    /** Does the village let off what a household can't pay, the rent or a child's bed (a generous leader, or one elected for homes)? */
    public static boolean generous(UUID village) {
        if (Elections.mandate(village) == Values.Value.HOMES) return true;
        VillageFolkEntity elder = Orders.elderOf(village);
        return elder != null && elder.life().has(Social.Trait.GENEROUS);
    }

    /**
     * For the status: "11 households housed (2 owned, 9 rented, 3 of them saving to buy), 1 waiting;
     * 1 empty (to let: house 1c a day); rent 9 coins yesterday; houses are let, and sold to the
     * tenants who save for them".
     */
    public static String line(ServerLevel level, UUID village) {
        int[] c = counts(level, village);
        StringBuilder let = new StringBuilder();
        for (Home h : homes(village).values()) {
            if (seat(h) || !h.members.isEmpty() || h.tenure == Tenure.PLAYER || let.length() >= 60) continue;
            int r = rent(village, h);
            let.append(let.length() == 0 ? "" : ", ").append(h.structure).append(' ').append(r).append("c a day");
        }
        return c[0] + " households housed (" + (c[2] > 0 ? c[2] + " given, " : "") + c[3] + " owned, " + c[4] + " rented"
            + (c[8] > 0 ? ", " + c[8] + " of them saving to buy" : "") + (c[13] > 0 ? ", " + c[13] + " founders' rent-free" : "") + ")"
            + (c[5] > 0 ? ", " + c[5] + " players'" : "") + ", " + c[1] + " waiting; " + c[6] + " empty"
            + (let.length() > 0 ? " (to let: " + let + ")" : "")
            + "; rent " + c[9] + coins(c[9]) + " yesterday" + (c[11] > 0 ? ", " + c[11] + " owed" : "")
            + "; houses are let, and sold to the tenants who save for them";
    }

    /** For the board: "9 rented, 2 owned, 3 saving to buy; rent 9 yesterday", or "" with nobody housed. */
    public static String brief(ServerLevel level, UUID village) {
        int[] c = counts(level, village);
        if (c[0] == 0) return "";
        return c[4] + " rented" + (c[13] > 0 ? " (" + c[13] + " rent-free)" : "") + ", " + c[3] + " owned"
            + (c[8] > 0 ? ", " + c[8] + " saving to buy" : "") + "; rent " + c[9] + " yesterday";
    }

    /**
     * For the town's books (Annals): {housed, waiting, given, owned, rented, players', empty, for sale (1/0),
     * saving (tenant households putting by to buy), rent collected yesterday, coin put by toward houses,
     * rent owed, coin from houses sold yesterday, founders' households still rent-free}. The first eight are as
     * they always were.
     */
    public static int[] counts(ServerLevel level, UUID village) {
        enrol(village);
        int given = 0, owned = 0, rented = 0, let = 0, empty = 0, saving = 0, saved = 0, owed = 0, free = 0;
        for (Home h : homes(village).values()) {
            saved += Math.max(0, h.saved);
            if (seat(h)) continue;
            if (h.members.isEmpty()) {
                if (h.tenure == Tenure.PLAYER && !h.toLet) let++;
                else empty++;
                continue;
            }
            owed += Math.max(0, h.owed);
            switch (h.tenure) {
                case GIVEN -> given++;
                case OWNED -> owned++;
                case RENTED -> {
                    rented++;
                    List<VillageFolkEntity> household = loadedMembers(village, h);
                    if (rentFree(household)) free++;
                    if (h.saved > 0 || wish(village, h, household).yes()) saving++;
                }
                case PLAYER -> let++;
            }
        }
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) if (a instanceof VillageFolkEntity f && !f.isShowcase()) folk.add(f);
        int waiting = waiting(village, folk, level.getDayTime() / 24000L).size();
        return new int[]{ given + owned + rented, waiting, given, owned, rented, let, empty, forSale(village) ? 1 : 0,
            saving, Economy.rentYesterday(village), saved, owed, Economy.housesSoldYesterday(village), free };
    }

    /**
     * The town's books' Homes page (Annals): the figures (as counts, by name), and a row for every
     * household: where it lives (address, kind), who (household, folk, grown), on what terms (tenure,
     * terms, rent a day, owed, a note on the rent), what it has put by toward the price and the price,
     * whether it wants a house of its own and why, its standing (renting, saving, owns, the leader's,
     * a player's tenant) and the day it moved in.
     */
    public static CompoundTag report(ServerLevel level, UUID village) {
        CompoundTag out = new CompoundTag();
        int[] c = counts(level, village);
        String[] names = { "housed", "waiting", "given", "owned", "rented", "players", "empty", "for_sale",
            "saving", "rent_yesterday", "saved", "owed", "sales_yesterday", "rent_free" };
        for (int i = 0; i < names.length; i++) out.putInt(names[i], c[i]);
        out.putInt("hand_wage", Wealth.tradeWage(AssistantEntity.StationTask.FARM, village));
        out.putString("line", line(level, village));
        Villages.Village v = Villages.get(village);
        net.minecraft.nbt.ListTag rows = new net.minecraft.nbt.ListTag();
        for (Home h : homes(village).values()) {
            if (h.members.isEmpty()) continue;
            List<VillageFolkEntity> household = loadedMembers(village, h);
            List<VillageFolkEntity> grown = grown(household);
            CompoundTag r = new CompoundTag();
            r.putLong("anchor", h.anchor.asLong());                // the building's, as on the Buildings page
            r.putString("address", v == null ? "" : address(village, v, h));
            r.putString("kind", seat(h) ? "the leader's hall" : h.structure.equals("manor") ? "manor"
                : Ledger.grown(village, h.anchor) ? "two-storey house" : "house");
            r.putString("household", household.isEmpty() ? h.members.size() + " folk" : names(household));
            r.putInt("folk", h.members.size());
            r.putInt("grown", grown.size());
            r.putString("tenure", seat(h) ? "the leader's" : h.tenure.name().toLowerCase(java.util.Locale.ROOT));
            r.putString("terms", seat(h) ? "goes with leading the village" : h.tenure == Tenure.PLAYER ? "rented from " + h.landlordName : h.tenure.word);
            boolean founders = h.tenure == Tenure.RENTED && rentFree(household);
            boolean paysRent = !seat(h) && !founders && (h.tenure == Tenure.RENTED || h.tenure == Tenure.PLAYER);
            r.putInt("rent", paysRent ? h.rent : 0);
            r.putInt("rent_due", h.tenure == Tenure.RENTED && !seat(h) ? h.rent : 0);
            r.putBoolean("rent_free", founders);
            r.putInt("owed", h.owed);
            r.putInt("saved", h.saved);
            r.putInt("price", seat(h) || h.tenure == Tenure.PLAYER ? 0 : h.tenure == Tenure.OWNED ? h.price : price(village, h));
            boolean wants;
            String why, status, rentNote = "";
            if (seat(h)) {
                wants = false;
                why = "the leader's hall goes with the office: never sold";
                status = "the leader's";
            } else if (h.tenure == Tenure.OWNED) {
                wants = true;
                why = h.price > 0 ? "bought it for " + h.price + coins(h.price) : "owns it";
                status = "owns";
            } else if (h.tenure == Tenure.PLAYER) {
                Wish w = wish(village, h, household);
                wants = w.yes();
                why = "a player's house, not the village's to sell; " + w.why();
                status = "a player's tenant";
            } else {
                Wish w = wish(village, h, household);
                wants = w.yes();
                why = w.why();
                status = founders ? "rent-free" : wants ? "saving" : "renting";
                if (founders) {
                    int need = AFFORD_DAYS * Math.max(1, h.rent) + LIVE_ON * grown.size();
                    rentNote = "founders: free until they can afford " + h.rent + coins(h.rent) + " a day (" + (purses(household) + h.saved)
                        + " of " + need + coins(need) + " in hand)";
                } else if (h.tenure == Tenure.RENTED && !household.isEmpty() && earners(household) == 0) rentNote = "waived: nobody in the house earns";
                else if (h.owed > 0) rentNote = "owes " + h.owed + coins(h.owed);
                else if (h.tenure == Tenure.RENTED && generous(village)) rentNote = "a generous leader lets off what a tenant is short";
            }
            r.putBoolean("wants", wants);
            r.putString("why", why);
            r.putString("status", status);
            r.putString("rent_note", rentNote);
            r.putLong("since", h.since);
            rows.add(r);
        }
        out.put("rows", rows);
        return out;
    }

    /** "I live at No. 4, Elm Street, with Tansy and the children — we rent it from the village at a coin a day, and we're saving to buy it." */
    public static String talk(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null) return "";
        Home h = homeOf(village, f.getUUID());
        Villages.Village v = Villages.get(village);
        if (h == null || v == null) return "I've no house of my own yet. I'm waiting for one.";
        List<String> others = new ArrayList<>();
        int kids = 0;
        for (UUID m : h.members) {
            if (m.equals(f.getUUID())) continue;
            VillageFolkEntity o = loaded(village, m);
            if (o == null) continue;
            if (o.isBaby()) kids++;
            else others.add(o.displayNameCap());
        }
        String with = others.isEmpty() && kids == 0 ? "" : " with " + String.join(" and ", others)
            + (kids == 0 ? "" : (others.isEmpty() ? "" : " and ") + (kids == 1 ? "our child" : "the children"));
        String terms = seat(h) ? "it goes with leading the village" : switch (h.tenure) {
            case GIVEN -> "the village gave it us";
            case OWNED -> h.price > 0 ? "it's ours: we paid " + h.price + coins(h.price) + " for it, saved up" : "it's ours";
            case RENTED -> tenancy(village, h);
            case PLAYER -> "we rent it from " + h.landlordName + " at " + h.rent + coins(h.rent) + " a day";
        };
        return "I live at " + address(village, v, h) + with + " — " + terms + ".";
    }

    /** "we rent it from the village at 1 coin a day; we're saving to buy it: 34 of 44 coins put by". */
    static String tenancy(UUID village, Home h) {
        List<VillageFolkEntity> household = loadedMembers(village, h);
        boolean many = household.size() > 1;
        String we = many ? "we" : "I", us = many ? "us" : "me";
        StringBuilder sb = new StringBuilder(we + " rent it from the village at " + h.rent + coins(h.rent) + " a day");
        if (rentFree(household)) {
            sb = new StringBuilder(we + " live in it rent-free — " + (many ? "we're" : "I'm") + " one of the founders — till "
                + we + " can afford the " + h.rent + coins(h.rent) + " a day");
        } else if (!household.isEmpty() && earners(household) == 0) sb.append(", though the village lets ").append(us).append(" off while nobody here earns");
        else if (h.owed > 0) sb.append(", and ").append(we).append(" owe ").append(h.owed).append(coins(h.owed)).append(" of it — ").append(we).append("'ll make it up");
        Wish w = wish(village, h, household);
        int price = price(village, h);
        if (w.yes()) sb.append("; ").append(many ? "we're" : "I'm").append(" saving to buy it: ").append(h.saved).append(" of ")
            .append(price).append(coins(price)).append(" put by (").append(w.mine()).append(")");
        else sb.append("; renting suits ").append(us).append(" (").append(w.mine()).append(")");
        return sb.toString();
    }

    // ------------------------------------------------------------------ players' houses

    /** Talk: "buy this house", "let my house for 3", "my rent", or what is for sale. */
    public static String ask(VillageFolkEntity f, Player p, String text) {
        if (!(f.level() instanceof ServerLevel level)) return "";
        String t = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT);
        if (t.contains("buy")) return playerBuys(level, p);
        if (t.contains("let") || t.contains("rent out")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(t);
            return playerLets(level, p, m.find() ? Integer.parseInt(m.group(1)) : 3);
        }
        if (t.contains("rent") || t.contains("tenant")) return playerCollects(level, p);
        UUID village = f.ownerId();
        if (village == null) return "I'm not from round here.";
        enrol(village);
        List<String> sale = new ArrayList<>();
        Villages.Village v = Villages.get(village);
        for (Home h : homes(village).values()) {
            if (!h.members.isEmpty() || h.tenure == Tenure.PLAYER || seat(h) || v == null) continue;
            sale.add(address(village, v, h) + " at " + price(village, h) + coins(price(village, h)));
            if (sale.size() == 3) break;
        }
        if (sale.isEmpty()) return "Every house is taken. The builders put up another when the village can spare the stone; "
            + "it lets them to us, and sells them to whoever saves up the price.";
        return "Empty just now: " + String.join("; ", sale) + ". Stand in the one you like and say \"buy this house\". "
            + "You can live in it, or let it to a household for coin.";
    }

    /** /village house buy: the empty house the player stands in (or nearest), bought with its coin. */
    public static String playerBuys(ServerLevel level, Player p) {
        Villages.Village v = Villages.nearest(level, p.blockPosition(), Villages.VILLAGE_RANGE);
        if (v == null) return "There's no village here.";
        UUID id = v.id();
        enrol(id);
        Home h = homeAt(id, p.blockPosition());
        if (h == null || !h.members.isEmpty() || h.tenure == Tenure.PLAYER || seat(h)) {
            h = null;
            double best = Double.MAX_VALUE;
            for (Home o : homes(id).values()) {
                if (!o.members.isEmpty() || o.tenure == Tenure.PLAYER || seat(o)) continue;
                double d = o.anchor.distSqr(p.blockPosition());
                if (d < best && d < 24 * 24) { best = d; h = o; }
            }
        }
        if (h == null) return "There's no empty house near you for sale. Ask again when the builders have put one up.";
        Standing.Title title = Standing.of(id, p.getUUID(), level.getGameTime()).title();
        boolean citizen = Ledger.citizens(id).containsKey(p.getUUID());
        if (!citizen && !title.atLeast(Standing.Title.FRIEND)) return Villages.name(id) + " sells its houses to its friends. Be one first.";
        int price = (int) Math.round(price(id, h) * (citizen ? 1.0 : 1.25));
        int held = Market.coinsHeld(p);
        if (held < price) return address(id, v, h) + " is " + price + coins(price) + ". You have " + held + ".";
        Market.payOut(p, price);
        Ledger.addCoins(id, price);
        h.tenure = Tenure.PLAYER;
        h.landlord = p.getUUID();
        h.landlordName = p.getName().getString();
        h.price = price;
        h.rent = 0;
        h.toLet = false;
        h.since = level.getDayTime() / 24000L;
        save(id, h);
        ItemStack key = new ItemStack(Items.TRIPWIRE_HOOK);
        key.set(DataComponents.CUSTOM_NAME, Component.literal("Key to " + address(id, v, h)));
        if (!p.getInventory().add(key)) p.drop(key, false);
        Villages.tell(id, h.since, p.getName().getString() + " bought " + address(id, v, h) + " for " + price + coins(price));
        return "It's yours: " + address(id, v, h) + ", for " + price + coins(price) + ". Sleep in its bed to make it your home, or let it out with /village house let <rent a day>.";
    }

    /** /village house let N: the player's house let out at N coins a day (0: taken off the market and the tenants given notice). */
    public static String playerLets(ServerLevel level, Player p, int rent) {
        Home h = playersHouse(level, p);
        if (h == null) return "You don't own a house here.";
        Villages.Village v = Villages.nearest(level, h.anchor, Villages.VILLAGE_RANGE);
        if (v == null) return "That house has no village round it.";
        UUID id = v.id();
        if (rent <= 0) {
            h.toLet = false;
            List<UUID> out = new ArrayList<>(h.members);
            h.members.clear();
            save(id, h);
            for (UUID m : out) {
                VillageFolkEntity f = loaded(id, m);
                if (f != null) f.forgetBed();
            }
            return out.isEmpty() ? "It's yours alone again." : "Your tenants have moved out. It's yours alone again.";
        }
        h.toLet = true;
        h.rent = Math.min(20, rent);
        save(id, h);
        return address(id, v, h) + " is to let at " + h.rent + coins(h.rent) + " a day. The next household without a home will take it.";
    }

    /** /village house rent: the rent the player's tenants have paid, in coin. */
    public static String playerCollects(ServerLevel level, Player p) {
        Villages.Village v = Villages.nearest(level, p.blockPosition(), Villages.VILLAGE_RANGE);
        if (v == null) return "There's no village here.";
        String key = "rentdue/" + p.getUUID();
        int due = parse(Ledger.note(v.id(), key));
        if (due <= 0) return "No rent waiting for you in " + Villages.name(v.id()) + ".";
        Ledger.note(v.id(), key, "0");
        ItemStack coins = new ItemStack(com.jrpetty.mcassistant.McAssistantMod.VILLAGE_COIN.get(), due);
        if (!p.getInventory().add(coins)) p.drop(coins, false);
        return "Your rent: " + due + coins(due) + ".";
    }

    /** /village house: the houses of the village, who lives in them, and what is for sale. */
    public static List<String> list(ServerLevel level, Player p) {
        List<String> out = new ArrayList<>();
        Villages.Village v = Villages.nearest(level, p.blockPosition(), Villages.VILLAGE_RANGE);
        if (v == null) { out.add("There's no village here."); return out; }
        UUID id = v.id();
        enrol(id);
        out.add(Villages.name(id) + ": " + line(level, id));
        for (Home h : homes(id).values()) {
            StringBuilder sb = new StringBuilder(address(id, v, h)).append(" (").append(seat(h) ? "the leader's hall" : h.structure.equals("manor") ? "manor"
                : Ledger.grown(id, h.anchor) ? "two-storey house" : "house")
                .append(", ").append(bedsIn(level, id, h).size()).append(" beds): ");
            if (h.members.isEmpty()) {
                sb.append(h.tenure == Tenure.PLAYER ? h.landlordName + "'s" + (h.toLet ? ", to let at " + h.rent + coins(h.rent) + " a day" : "")
                    : seat(h) ? "empty, kept for the leader" : "empty, to let at " + rent(id, h) + coins(rent(id, h)) + " a day (yours for "
                        + price(id, h) + coins(price(id, h)) + ")");
            } else {
                List<VillageFolkEntity> m = loadedMembers(id, h);
                sb.append(m.isEmpty() ? h.members.size() + " folk" : names(m)).append(", ").append(seat(h) ? "with the office" : h.tenure.word)
                    .append(h.tenure == Tenure.RENTED || h.tenure == Tenure.PLAYER ? " at " + h.rent + coins(h.rent) + " a day" : "")
                    .append(h.tenure == Tenure.OWNED && h.price > 0 ? ", bought for " + h.price + coins(h.price) : "");
                if (h.tenure == Tenure.RENTED && !seat(h)) {
                    if (h.owed > 0) sb.append(", owes ").append(h.owed);
                    if (h.saved > 0 || wish(id, h, m).yes()) sb.append(", saving to buy it: ").append(h.saved).append(" of ").append(price(id, h)).append(" put by");
                }
            }
            out.add(sb.toString());
        }
        return out;
    }

    @Nullable
    static Home playersHouse(ServerLevel level, Player p) {
        Villages.Village v = Villages.nearest(level, p.blockPosition(), Villages.VILLAGE_RANGE);
        if (v == null) return null;
        Home at = homeAt(v.id(), p.blockPosition());
        if (at != null && p.getUUID().equals(at.landlord)) return at;
        for (Home h : homes(v.id()).values()) if (p.getUUID().equals(h.landlord)) return h;
        return null;
    }

    // ------------------------------------------------------------------ departures

    /**
     * A folk gone for good (dead, or moved away): off its house's books. The last one out takes what
     * the household had put by toward buying it, if it is moving away; if it died, that goes to the
     * village.
     */
    public static void left(UUID village, UUID folk) {
        Home h = homeOf(village, folk);
        if (h == null) return;
        VillageFolkEntity f = loaded(village, folk);
        h.members.remove(folk);
        if (h.members.isEmpty()) vacate(village, h, f != null && f.isAlive() ? f : null);
        else save(village, h);
        MOVES.remove(folk);
    }

    // ------------------------------------------------------------------ helpers

    static List<VillageFolkEntity> loadedMembers(UUID village, Home h) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (UUID m : h.members) {
            VillageFolkEntity f = loaded(village, m);
            if (f != null) out.add(f);
        }
        return out;
    }

    @Nullable
    static VillageFolkEntity loaded(UUID village, UUID who) {
        for (AssistantEntity a : Villages.folkOf(village)) if (a instanceof VillageFolkEntity f && f.getUUID().equals(who)) return f;
        return null;
    }

    static String address(UUID village, Villages.Village v, Home h) {
        if (seat(h)) return "the leader's hall";
        Ledger.Building b = building(village, h.anchor);
        String[] a = b == null ? null : TownLife.address(village, v.centre(), b);
        return a == null ? "the house at " + h.anchor.getX() + ", " + h.anchor.getZ() : a[0] + ", " + a[1];
    }

    static String names(List<VillageFolkEntity> household) {
        List<String> grown = new ArrayList<>();
        int kids = 0;
        for (VillageFolkEntity f : household) {
            if (f.isBaby()) kids++;
            else grown.add(f.displayNameCap());
        }
        String s = String.join(" and ", grown);
        if (kids > 0) s += (s.isEmpty() ? "" : " and ") + (kids == 1 ? "their child" : "their " + kids + " children");
        return s;
    }

    static String coins(int n) {
        return n == 1 ? " coin" : " coins";
    }

    static int parse(@Nullable String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ tests

    /** Tests: who lives in the house at this anchor. */
    public static List<UUID> membersForTests(UUID village, BlockPos anchor) {
        Home h = homes(village).get(anchor.asLong());
        return h == null ? List.of() : List.copyOf(h.members);
    }

    /** Tests: the tenure of the house at this anchor, or null. */
    @Nullable
    public static String tenureForTests(UUID village, BlockPos anchor) {
        Home h = homes(village).get(anchor.asLong());
        return h == null ? null : h.tenure.name();
    }

    /** Tests: run the households now. */
    public static void tickForTests(ServerLevel level, Villages.Village v) {
        TICKED.remove(v.id());
        tick(level, v);
    }

    /** Tests: the town selling outright (manors) (true), not (false), or as the village's wealth has it (null). */
    public static void saleForTests(@Nullable Boolean on) {
        SALE_FOR_TESTS = on;
    }

    /** Tests: the homes' payday (rent, saving up, buying, moving up), now. */
    public static void paydayForTests(ServerLevel level, Villages.Village v) {
        payday(level, v, level.getDayTime() / 24000L);
    }

    /** Tests: the same, by its old name (the morning's rent). */
    public static void morningForTests(ServerLevel level, Villages.Village v) {
        paydayForTests(level, v);
    }

    /** Tests: {rent a day, owed, put by, price} of the house at this anchor, or null. */
    @Nullable
    public static int[] termsForTests(UUID village, BlockPos anchor) {
        Home h = homes(village).get(anchor.asLong());
        return h == null ? null : new int[]{ h.rent, h.owed, h.saved, h.tenure == Tenure.OWNED ? h.price : price(village, h) };
    }

    /** Tests: the rent the village would ask for a house of this kind ("house", "manor") in this village now. */
    public static int rentForTests(UUID village, String structure) {
        return rent(village, new Home(BlockPos.ZERO, structure));
    }

    /** Tests: what the village would sell a house of this kind for now. */
    public static int priceForTests(UUID village, String structure) {
        return price(village, new Home(BlockPos.ZERO, structure));
    }

    /** Tests: something into the village's stores. */
    public static void storeForTests(ServerLevel level, Villages.Village v, ItemStack s) {
        Crafts.store(level, v, s);
    }

    /** Tests: where this folk's things are on their way to, if it is moving house. */
    @Nullable
    public static BlockPos movingToForTests(UUID folk) {
        Move m = MOVES.get(folk);
        return m == null ? null : m.to();
    }

    /** Tests: the bed heads in the house at this anchor. */
    public static List<BlockPos> bedsForTests(ServerLevel level, UUID village, BlockPos anchor) {
        Home h = homes(village).get(anchor.asLong());
        return h == null ? List.of() : bedsIn(level, village, h);
    }
}
