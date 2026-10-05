package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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
 * <p>While the village is young its houses are given: the village builds them and hands them to
 * the households that need them most, families first. Once it is getting rich (the Stone Age past,
 * a treasury of its own, sixteen folk) a new house is sold: a household with the coin buys it
 * outright, one without rents it from the village at a coin or two a day out of its wages, and buys
 * it when it can; the builders put up houses for sale as the town grows, and a manor for a
 * household rich enough to want one. A household that grows rich moves up, selling its old house
 * back to the village.
 *
 * <p>A player can buy a house too (`/village house buy`), live in it, and let it out
 * (`/village house let`): a household with nowhere to live moves in and pays the player's rent,
 * which the player collects (`/village house rent`).
 */
public final class Homes {

    private Homes() {}

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
        Tenure tenure = Tenure.GIVEN;
        final List<UUID> members = new ArrayList<>();
        @Nullable UUID landlord;
        String landlordName = "";
        int price;
        int rent;
        long since = -1;
        int owed;
        boolean toLet;

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
    private static final Map<UUID, Long> MORNING = new ConcurrentHashMap<>();
    /** Folk carrying their things from one house to another. */
    private static final Map<UUID, Move> MOVES = new ConcurrentHashMap<>();

    record Move(@Nullable BlockPos from, BlockPos to, boolean whole, long started, boolean[] fetched) {}

    @Nullable private static Boolean SALE_FOR_TESTS;

    public static void resetForTests() {
        SALE_FOR_TESTS = null;
        HOMES.clear();
        TICKED.clear();
        MORNING.clear();
        MOVES.clear();
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
            + "|" + h.landlordName.replace("|", "") + "|" + h.price + "|" + h.rent + "|" + h.since + "|" + h.owed + "|" + (h.toLet ? "1" : "0"));
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
        // A bed for every child.
        for (Home h : homes.values()) childBeds(level, v, h, day);
        // Keepsakes into the household's chest, for those at home.
        morning(level, v, day);
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
        hall.tenure = Tenure.GIVEN;
        hall.price = 0;
        hall.rent = 0;
        hall.since = day;
        moveIn(level, v, hall, household, day, true);
        if (old != null && old != hall && old.members.isEmpty() && old.tenure != Tenure.PLAYER) {
            if (old.tenure == Tenure.OWNED && old.price > 0) f.earn(Ledger.takeCoins(id, old.price / 2));
            old.tenure = Tenure.GIVEN;
            old.price = 0;
            old.rent = 0;
            old.owed = 0;
            save(id, old);
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
            // A manor is kept for a household that buys it.
            if (h.structure.equals("manor") && !canBuy(level, village, h, household)) continue;
            if (beds < bestBeds) { best = h; bestBeds = beds; }
        }
        return best;
    }

    /** Is the village selling its houses now (rather than giving them)? Once it is getting rich. */
    public static boolean forSale(UUID village) {
        if (SALE_FOR_TESTS != null) return SALE_FOR_TESTS;
        return Villages.ageOf(village).ordinal() >= Villages.Age.STONE.ordinal()
            && Ledger.coins(village) >= 120 && Villages.headcount(village) >= 16;
    }

    /** What a house sells for: by its size and the age of the town. */
    static int price(UUID village, Home h) {
        int base = h.structure.equals("manor") ? 120 : Ledger.grown(village, h.anchor) ? 55 : 35;
        return (int) Math.round(base * (1.0 + 0.25 * Villages.ageOf(village).ordinal()));
    }

    /** A day's rent: a coin for a small house, more for a big one. Less under a leader elected for homes. */
    static int rent(UUID village, Home h) {
        int r = Math.max(1, price(village, h) / 30);
        if (Elections.mandate(village) == Values.Value.HOMES) r = Math.max(1, r / 2);
        return r;
    }

    static int purses(List<VillageFolkEntity> household) {
        int n = 0;
        for (VillageFolkEntity f : household) if (!f.isBaby()) n += f.purse();
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

    /** A household takes a house: given, bought, rented from the village, or let from a player. */
    static void settle(ServerLevel level, Villages.Village v, Home h, List<VillageFolkEntity> household, long day) {
        UUID id = v.id();
        String names = names(household);
        String where = address(id, v, h);
        String how;
        if (h.tenure == Tenure.PLAYER) {
            h.rent = Math.max(1, h.rent);
            how = "rented " + where + " from " + h.landlordName + " at " + h.rent + coins(h.rent) + " a day";
        } else if (!forSale(id) || generous(id) && purses(household) < price(id, h)) {
            h.tenure = Tenure.GIVEN;
            h.price = 0;
            how = "were given " + where + " by the village";
        } else if (canBuy(level, id, h, household) && pay(household, price(id, h))) {
            h.tenure = Tenure.OWNED;
            h.price = price(id, h);
            Ledger.addCoins(id, h.price);
            Economy.spent(id, 0);
            how = "bought " + where + " for " + h.price + coins(h.price);
        } else {
            h.tenure = Tenure.RENTED;
            h.price = price(id, h);
            h.rent = rent(id, h);
            how = "rented " + where + " from the village at " + h.rent + coins(h.rent) + " a day";
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
            f.persona().remember(day, (left.isEmpty() ? "we " : "I moved out and ") + how.replaceFirst("^were ", "were ") + " on day " + day, 6);
        }
        VillageFolkEntity first = household.get(0);
        if (!left.isEmpty()) {
            FolkTalk.speak(first, FolkTalk.pick(level.getRandom(), "A place of my own at last!", "My own front door!", "Off I go — I'll visit, I promise."));
        } else {
            FolkTalk.speak(first, FolkTalk.pick(level.getRandom(), "Home!", "A roof of our own!", "This one's ours."));
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
            if (leave.tenure != Tenure.PLAYER) { leave.tenure = Tenure.GIVEN; leave.price = 0; leave.rent = 0; leave.owed = 0; }
            save(id, leave);
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
        if (h.members.isEmpty() || !level.isLoaded(h.anchor) || Ledger.raising(id, h.anchor)) return false;
        List<VillageFolkEntity> members = loadedMembers(id, h);
        List<BlockPos> beds = bedsIn(level, id, h);
        int children = (int) members.stream().filter(VillageFolkEntity::isBaby).count();
        if (children == 0 || beds.size() >= members.size()) return false;
        // A bed: out of the stores (the tailor's), bought with the parents' coin; or the village's gift if
        // the parents have none and the leader is a generous one.
        if (Market.stock(level, id, s -> s.is(ItemTags.BEDS)) == 0) return false;
        Market.Good g = Market.goodFor(new ItemStack(Items.WHITE_BED));
        int price = g == null ? 4 : Math.max(1, Market.sellPrice(g, Market.stock(level, id, g.what()), false));
        boolean paid = pay(members, price);
        if (!paid && !generous(id)) return false;
        BlockPos spot = bedSpot(level, id, h, beds);
        if (spot == null) {
            if (paid) members.stream().filter(m -> !m.isBaby()).max(Comparator.comparingInt(VillageFolkEntity::purse)).ifPresent(m -> m.earn(price));
            return false;
        }
        ItemStack bed = Crafts.takeOne(level, v, s -> s.is(ItemTags.BEDS));
        if (bed.isEmpty() || !(Block.byItem(bed.getItem()) instanceof BedBlock bb)) {
            if (paid) members.stream().filter(m -> !m.isBaby()).findFirst().ifPresent(m -> m.earn(price));
            if (!bed.isEmpty()) Crafts.store(level, v, bed);
            return false;
        }
        if (paid) Ledger.addCoins(id, price);
        Direction lie = spotLie(level, spot);
        BlockState st = bb.defaultBlockState().setValue(BedBlock.FACING, lie);
        level.setBlock(spot, st.setValue(BedBlock.PART, BedPart.FOOT), 3);
        level.setBlock(spot.relative(lie), st.setValue(BedBlock.PART, BedPart.HEAD), 3);
        String extra = Ledger.note(id, "homebeds/" + h.anchor.asLong());
        Ledger.note(id, "homebeds/" + h.anchor.asLong(), (extra == null || extra.isEmpty() ? "" : extra + ",") + spot.relative(lie).asLong());
        VillageFolkEntity parent = members.stream().filter(m -> !m.isBaby()).findFirst().orElse(null);
        VillageFolkEntity child = members.stream().filter(VillageFolkEntity::isBaby)
            .max(Comparator.comparingLong(VillageFolkEntity::bornDay)).orElse(null);
        Villages.tell(id, day, (parent == null ? "the village" : parent.displayNameCap()) + (paid ? " bought a bed at the shop for " : " was given a bed for ")
            + (child == null ? "a child" : child.displayNameCap()) + " (" + price + coins(price) + ")");
        if (parent != null) parent.persona().remember(day, "I bought a bed for " + (child == null ? "the little one" : child.displayNameCap()), 4);
        return true;
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
        if (!level.getBlockState(p).isAir() || !level.getBlockState(p.above()).isAir()) return false;
        if (!level.getBlockState(p.below()).isSolidRender(level, p.below())) return false;
        if (level.canSeeSky(p)) return false;
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
        for (int i = 0; i < c.getContainerSize() && !s.isEmpty(); i++) {
            ItemStack in = c.getItem(i);
            if (in.isEmpty() || !ItemStack.isSameItemSameComponents(in, s)) continue;
            int room = Math.min(c.getMaxStackSize(), in.getMaxStackSize()) - in.getCount();
            if (room <= 0) continue;
            int k = Math.min(room, s.getCount());
            in.grow(k);
            s.shrink(k);
        }
        for (int i = 0; i < c.getContainerSize() && !s.isEmpty(); i++) {
            if (!c.getItem(i).isEmpty()) continue;
            c.setItem(i, s.copy());
            s = ItemStack.EMPTY;
        }
        return s;
    }

    static boolean ownedBy(ItemStack s, VillageFolkEntity f) {
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        return d != null && f.getStringUUID().equals(d.copyTag().getString(KEEP));
    }

    // ------------------------------------------------------------------ mornings: rent, buying, moving up

    /** Once a day: rent paid, renters with the coin buy, the rich move up. */
    static void morning(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        Long done = MORNING.get(id);
        long t = level.getDayTime() % 24000L;
        if (done != null && done == day || t < 1000L || t > 8000L) return;
        MORNING.put(id, day);
        for (Home h : homes(id).values()) {
            if (h.members.isEmpty()) continue;
            List<VillageFolkEntity> household = loadedMembers(id, h);
            if (household.isEmpty()) continue;
            switch (h.tenure) {
                case RENTED -> {
                    if (pay(household, h.rent)) Ledger.addCoins(id, h.rent);
                    else h.owed += h.rent;
                    // Saved enough: they buy it.
                    if (forSale(id) && purses(household) >= h.price + 10 && pay(household, h.price)) {
                        Ledger.addCoins(id, h.price);
                        h.tenure = Tenure.OWNED;
                        h.owed = 0;
                        Villages.tell(id, day, names(household) + " bought " + address(id, v, h) + ", the house they rented, for " + h.price + coins(h.price));
                    }
                    save(id, h);
                }
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
                default -> { }
            }
        }
    }

    /** A household that owns its house and has grown rich buys a manor that stands empty, and sells its house back. */
    static void moveUp(ServerLevel level, Villages.Village v, Home h, List<VillageFolkEntity> household, long day) {
        UUID id = v.id();
        if (h.structure.equals("manor")) return;
        for (Home m : homes(id).values()) {
            if (!m.structure.equals("manor") || !m.vacant() || m.tenure == Tenure.PLAYER) continue;
            int price = price(id, m), back = h.price / 2;
            if (purses(household) + back < price + 20) return;
            for (VillageFolkEntity f : household) if (!f.isBaby()) { f.earn(Ledger.takeCoins(id, back)); break; }
            if (!pay(household, price)) return;
            Ledger.addCoins(id, price);
            m.tenure = Tenure.OWNED;
            m.price = price;
            m.since = day;
            moveIn(level, v, m, household, day, true);
            h.members.clear();
            h.tenure = Tenure.GIVEN;
            h.price = 0;
            save(id, h);
            Villages.tell(id, day, names(household) + " moved up to the manor at " + address(id, v, m) + ", bought for " + price + coins(price));
            return;
        }
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

    /** Does the village give houses to those who can't pay (a generous leader, or one elected for homes)? */
    public static boolean generous(UUID village) {
        if (Elections.mandate(village) == Values.Value.HOMES) return true;
        VillageFolkEntity elder = Orders.elderOf(village);
        return elder != null && elder.life().has(Social.Trait.GENEROUS);
    }

    /** For the status: "14 households: 11 housed (6 given, 3 owned, 2 rented), 3 waiting; 1 empty (house 50c)". */
    public static String line(ServerLevel level, UUID village) {
        enrol(village);
        int given = 0, owned = 0, rented = 0, let = 0, empty = 0;
        StringBuilder sale = new StringBuilder();
        for (Home h : homes(village).values()) {
            if (seat(h)) continue;
            if (h.members.isEmpty()) {
                if (h.tenure == Tenure.PLAYER && !h.toLet) { let++; continue; }
                empty++;
                if (sale.length() < 60) sale.append(sale.length() == 0 ? "" : ", ").append(h.structure).append(' ').append(price(village, h)).append('c');
                continue;
            }
            switch (h.tenure) {
                case GIVEN -> given++;
                case OWNED -> owned++;
                case RENTED -> rented++;
                case PLAYER -> let++;
            }
        }
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) if (a instanceof VillageFolkEntity f && !f.isShowcase()) folk.add(f);
        int waiting = waiting(village, folk, level.getDayTime() / 24000L).size();
        return (given + owned + rented) + " households housed (" + given + " given, " + owned + " owned, " + rented + " rented)"
            + (let > 0 ? ", " + let + " players'" : "") + ", " + waiting + " waiting; " + empty + " empty"
            + (sale.length() > 0 ? " (" + (forSale(village) ? "for sale: " : "") + sale + ")" : "")
            + "; houses are " + (forSale(village) ? "sold" : "given");
    }

    /** "I live at No. 4, Elm Street, with Tansy and the children — the village gave it us." */
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
            case OWNED -> "it's ours: we paid " + h.price + coins(h.price) + " for it";
            case RENTED -> "we rent it from the village at " + h.rent + coins(h.rent) + " a day";
            case PLAYER -> "we rent it from " + h.landlordName;
        };
        return "I live at " + address(village, v, h) + with + " — " + terms + ".";
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
            if (!h.members.isEmpty() || h.tenure == Tenure.PLAYER || v == null) continue;
            sale.add(address(village, v, h) + " at " + price(village, h) + coins(price(village, h)));
            if (sale.size() == 3) break;
        }
        if (sale.isEmpty()) return "Every house is taken. The builders put up another when the village can spare the stone"
            + (forSale(village) ? "; it sells them, so have coin ready." : ".");
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
                    : "empty, " + (forSale(id) ? "for sale at " + price(id, h) + coins(price(id, h)) : "to be given to the next household"));
            } else {
                List<VillageFolkEntity> m = loadedMembers(id, h);
                sb.append(m.isEmpty() ? h.members.size() + " folk" : names(m)).append(", ").append(h.tenure.word)
                    .append(h.tenure == Tenure.RENTED || h.tenure == Tenure.PLAYER ? " at " + h.rent + coins(h.rent) + " a day" : "");
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

    /** A folk gone for good (dead, or moved away): off its house's books. */
    public static void left(UUID village, UUID folk) {
        Home h = homeOf(village, folk);
        if (h == null) return;
        h.members.remove(folk);
        if (h.members.isEmpty() && h.tenure != Tenure.PLAYER) { h.tenure = Tenure.GIVEN; h.price = 0; h.rent = 0; h.owed = 0; }
        save(village, h);
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

    /** Tests: the houses for sale (true), given (false), or as the village's wealth has it (null). */
    public static void saleForTests(@Nullable Boolean on) {
        SALE_FOR_TESTS = on;
    }

    /** Tests: this morning's rent and buying, now. */
    public static void morningForTests(ServerLevel level, Villages.Village v) {
        MORNING.remove(v.id());
        morning(level, v, level.getDayTime() / 24000L);
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
