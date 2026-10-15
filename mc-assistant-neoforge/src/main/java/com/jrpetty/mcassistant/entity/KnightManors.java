package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [civic-land] A knight's land and its manor.
 *
 * <p><b>The grant.</b> At its knighting a knight is granted a plot at the town's edge: out past its last buildings
 * (and its wall), on dry ground level enough to build on, clear of every building and of another knight's land, its
 * door to the town. The chronicle names the side of the town it lies on.
 *
 * <p><b>The manor.</b> The town's builders raise it there as a public work, out of the town's stores, when its turn
 * comes after the town's own needs (and before them, once it has waited four days): a modest, handsome hall-house of
 * two storeys on a stone footing (the "knightmanor" drawing), its great room below with a long carpet, a hearth-stove,
 * a table, bookshelves, chests and casks, the knight's chamber above with a bed for it and one for a guest. Before it
 * is begun, the knight may pay the town sixty coins for a grander one: a knight's hall of two full storeys, a great
 * room with its long table and hearth below and the knight's chambers above ("knighthall"), built the same way. When
 * it stands, the knight's banner (its arms, out of the stores) is hung beside the door, the town opens it as it opens
 * any building, and the key is handed over; nobody of the town sleeps in it or takes from its chests. Stripped of the
 * title, the knight forfeits it to the town.
 */
public final class KnightManors {

    private KnightManors() {}

    /** The modest manor's drawing. */
    public static final String STRUCTURE = "knightmanor";
    /** The grander one, paid for by the knight: a knight's hall of two full storeys. */
    public static final String GRAND = "knighthall";
    /** What the grander manor costs the knight, into the treasury. */
    public static final int GRAND_PRICE = 60;
    /** Days a manor waits behind the town's own work before it goes to the head of the list. */
    static final int PATIENCE = 4;

    // ------------------------------------------------------------------ the grant

    /** The land granted at the town's edge: into the knight's record and the chronicle. What is said of it. */
    static String grant(ServerLevel level, Villages.Village v, UUID knight) {
        CompoundTag k = Knights.knight(v.id(), knight);
        if (k == null) return "";
        if (k.contains("plot")) return "Your land is at " + at(k) + ".";
        Villages.Site site = find(level, v, STRUCTURE);
        long day = CivicLand.day(level);
        if (site == null) {
            k.putString("manor", "nolot");
            CivicLand.changed();
            return "The land is to be granted when the town finds ground for it.";
        }
        put(k, site);
        k.putString("manor", "wanted");
        k.putString("kind", STRUCTURE);
        k.putLong("granted", day + 1);
        CivicLand.changed();
        String side = side(v.centre(), site.anchor());
        Villages.tell(v.id(), day, "the town granted " + Knights.style(knight).word + " " + k.getString("name") + " the land at its " + side + " edge");
        return "The town grants you its land at the " + side + " edge (" + site.anchor().getX() + ", " + site.anchor().getZ()
            + "), where its builders will raise your manor.";
    }

    static void put(CompoundTag k, Villages.Site site) {
        k.putLong("plot", site.anchor().asLong());
        k.putString("facing", site.facing().getName());
    }

    static String at(CompoundTag k) {
        BlockPos p = BlockPos.of(k.getLong("plot"));
        return p.getX() + ", " + p.getZ();
    }

    /** "east", "north-west": where a spot lies from the heart. */
    static String side(BlockPos centre, BlockPos at) {
        double ang = Math.toDegrees(Math.atan2(at.getZ() - centre.getZ(), at.getX() - centre.getX()));
        String[] names = { "east", "south-east", "south", "south-west", "west", "north-west", "north", "north-east" };
        return names[Math.floorMod((int) Math.round(ang / 45.0), 8)];
    }

    /**
     * Ground at the town's edge for a manor: rings out past its furthest building (and its wall), looked round in
     * sixteen directions; dry, level enough, clear of buildings, other knights' land and other towns, the door to the town.
     */
    @Nullable
    static Villages.Site find(ServerLevel level, Villages.Village v, String structure) {
        UUID id = v.id();
        BlockPos c = v.centre();
        int[] half = Blueprints.has(structure) ? Blueprints.fullHalf(structure) : new int[]{ 5, 6 };
        int mine = Math.max(half[0], half[1]);
        int reach = 16;
        List<Ledger.Building> built = Ledger.buildings(id);
        for (Ledger.Building b : built) {
            if (b.structure().equals("fortify")) { reach = Math.max(reach, Watch.R + 4); continue; }
            int[] h = BuildGoal.footprint(b.structure());
            double d = Math.sqrt(b.anchor().distSqr(new BlockPos(c.getX(), b.anchor().getY(), c.getZ())));
            reach = Math.max(reach, (int) Math.ceil(d) + Math.max(h[0], h[1]));
        }
        List<BlockPos> plots = new ArrayList<>();
        for (String key : Knights.knights(id).getAllKeys()) {
            CompoundTag k = Knights.knights(id).getCompound(key);
            if (k.contains("plot")) plots.add(BlockPos.of(k.getLong("plot")));
        }
        int salt = Math.floorMod(id.hashCode() + plots.size() * 5, 16);
        for (int ring = reach + mine + 4; ring <= reach + mine + 40; ring += 6) {
            for (int i = 0; i < 16; i++) {
                double ang = Math.PI * 2 * ((i * 7 + salt) % 16) / 16.0;
                int x = c.getX() + (int) Math.round(Math.cos(ang) * ring), z = c.getZ() + (int) Math.round(Math.sin(ang) * ring);
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                // Its back to the wilds, its door to the town.
                Direction back = Math.abs(Math.cos(ang)) >= Math.abs(Math.sin(ang)) ? (Math.cos(ang) > 0 ? Direction.EAST : Direction.WEST)
                    : (Math.sin(ang) > 0 ? Direction.SOUTH : Direction.NORTH);
                int hx = back.getAxis() == Direction.Axis.X ? half[1] : half[0], hz = back.getAxis() == Direction.Axis.X ? half[0] : half[1];
                BlockPos ground = level(level, x, z, hx, hz);
                if (ground == null) continue;
                boolean clear = true;
                for (Ledger.Building b : built) {
                    if (b.structure().equals("fortify")) continue;
                    int[] h = BuildGoal.footprint(b.structure());
                    if (Math.abs(b.anchor().getX() - x) <= h[0] + hx + 4 && Math.abs(b.anchor().getZ() - z) <= h[1] + hz + 4) { clear = false; break; }
                }
                for (BlockPos p : plots) if (Math.abs(p.getX() - x) <= 2 * mine + 6 && Math.abs(p.getZ() - z) <= 2 * mine + 6) clear = false;
                if (!clear) continue;
                Villages.Village other = Villages.nearest(level, ground, Villages.VILLAGE_RANGE);
                if (other != null && !other.id().equals(id)) continue;
                if (VanillaVillages.meets(level, x - hx, z - hz, x + hx, z + hz, VanillaVillages.MARGIN)) continue;
                return new Villages.Site(ground, back, 0);
            }
        }
        return null;
    }

    /** Level, dry, buildable ground here for a footprint so many each way, or null: its floor height. */
    @Nullable
    static BlockPos level(ServerLevel level, int x, int z, int hx, int hz) {
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int dx : new int[]{ -hx, 0, hx }) {
            for (int dz : new int[]{ -hz, 0, hz }) {
                if (!level.hasChunk((x + dx) >> 4, (z + dz) >> 4)) return null;
                int h = BuildGoal.groundTop(level, x + dx, z + dz);
                BlockState top = level.getBlockState(new BlockPos(x + dx, h - 1, z + dz));
                if (!top.getFluidState().isEmpty() || !top.isSolid()) return null;
                lo = Math.min(lo, h);
                hi = Math.max(hi, h);
            }
        }
        if (hi - lo > 3) return null;
        BlockPos at = new BlockPos(x, hi, z);
        int blocked = 0;
        for (int dx = -hx; dx <= hx; dx++) {
            for (int dz = -hz; dz <= hz; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    BlockPos p = at.offset(dx, dy, dz);
                    BlockState st = level.getBlockState(p);
                    if (BuildGoal.soft(st) || st.is(net.minecraft.tags.BlockTags.LEAVES)
                        || st.is(net.minecraft.tags.BlockTags.LOGS) && BuildGoal.isTreeLog(level, p)) continue;
                    blocked++;
                }
            }
        }
        return blocked > 12 ? null : at;
    }

    // ------------------------------------------------------------------ the building

    /** The manors the town is to build (Villages.projectsWanted): after its own, or first once one has waited long enough. */
    public static List<String> wanted(UUID village, List<String> out) {
        if (!CivicLand.known(village)) return out;
        String first = null;
        long oldest = Long.MAX_VALUE;
        for (String key : Knights.knights(village).getAllKeys()) {
            CompoundTag k = Knights.knights(village).getCompound(key);
            if (!"wanted".equals(k.getString("manor")) || !k.contains("plot")) continue;
            if (k.getLong("granted") < oldest) { oldest = k.getLong("granted"); first = k.getString("kind").isEmpty() ? STRUCTURE : k.getString("kind"); }
        }
        if (first == null || out.contains(first)) return out;
        List<String> all = new ArrayList<>(out);
        net.minecraft.server.MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        long day = server == null ? 0 : server.overworld().getDayTime() / 24000L;
        if (day + 1 - oldest >= PATIENCE) all.add(Math.min(all.size(), 1), first);
        else all.add(first);
        return all;
    }

    /** The plot of the knight whose manor of this drawing is to go up next (Villages.siteFor), or null. */
    @Nullable
    public static Villages.Site site(ServerLevel level, UUID village, String structure) {
        if (!CivicLand.known(village)) return null;
        CompoundTag best = null;
        for (String key : Knights.knights(village).getAllKeys()) {
            CompoundTag k = Knights.knights(village).getCompound(key);
            String kind = k.getString("kind").isEmpty() ? STRUCTURE : k.getString("kind");
            if (!"wanted".equals(k.getString("manor")) || !k.contains("plot") || !kind.equals(structure)) continue;
            if (best == null || k.getLong("granted") < best.getLong("granted")) best = k;
        }
        if (best == null) return null;
        Direction facing = Direction.byName(best.getString("facing"));
        return new Villages.Site(BlockPos.of(best.getLong("plot")), facing == null ? Direction.NORTH : facing, 0);
    }

    /** Is this a manor the town builds for a knight now (Villages.siteFor)? */
    public static boolean isKnights(UUID village, String structure) {
        return (STRUCTURE.equals(structure) || GRAND.equals(structure)) && site(null, village, structure) != null;
    }

    /** Raised (Villages.noteProject): the knight's own now; the key and the news to it. */
    public static void raised(UUID village, String structure, @Nullable BlockPos anchor, long gameTime) {
        if (anchor == null || !CivicLand.known(village)) return;
        for (String key : Knights.knights(village).getAllKeys()) {
            CompoundTag k = Knights.knights(village).getCompound(key);
            if (!k.contains("plot") || k.getLong("plot") != anchor.asLong()) continue;
            UUID knight = CivicLand.uuid(key);
            if (knight == null) continue;
            k.putString("manor", "built");
            k.putLong("builtDay", gameTime / 24000L + 1);
            CivicLand.changed();
            long day = gameTime / 24000L;
            String title = Knights.style(knight).word + " " + k.getString("name");
            Villages.tell(village, day, title + "'s manor went up at the town's edge");
            net.minecraft.server.MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                CivicLand.tell(server, knight, "Your manor in " + Villages.name(village) + " is built, at " + anchor.getX() + ", " + anchor.getZ()
                    + ". Its key waits for you with any folk of the town.", ChatFormatting.GOLD);
            }
            return;
        }
    }

    /** Every five seconds (Knights.tick): a manor's land found at last; the knight's banner hung beside a built manor's door. */
    static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long day = CivicLand.day(level);
        for (String key : new ArrayList<>(Knights.knights(id).getAllKeys())) {
            CompoundTag k = Knights.knights(id).getCompound(key);
            UUID knight = CivicLand.uuid(key);
            if (knight == null) continue;
            if ("nolot".equals(k.getString("manor")) && k.getLong("lotLook") != day + 1) {
                k.putLong("lotLook", day + 1);
                grant(level, v, knight);
                CivicLand.changed();
            }
            if ("built".equals(k.getString("manor")) && !k.getBoolean("hung") && k.getLong("bannerLook") != day + 1
                    && level.isLoaded(BlockPos.of(k.getLong("plot")))) {
                k.putLong("bannerLook", day + 1);
                hangBanner(level, v, knight, k);
                CivicLand.changed();
            }
        }
    }

    /** The knight's arms beside its door, out of the stores (Knights.make). */
    static boolean hangBanner(ServerLevel level, Villages.Village v, UUID knight, CompoundTag k) {
        Ledger.Building b = building(v.id(), k);
        if (b == null) return false;
        BlockPos door = TownLife.fittings(b).door();
        if (door == null) return false;
        Direction out = b.facing().getOpposite();
        Direction along = out.getClockWise();
        for (int side : new int[]{ 1, -1 }) {
            BlockPos at = door.relative(along, side * 2).above(1).relative(out, 1);
            if (!level.getBlockState(at).isAir() || !level.getBlockState(at.relative(out.getOpposite())).isSolid()) continue;
            ItemStack banner = Knights.make(level, v, knight, k.getString("name"), true);
            if (banner.isEmpty()) return false;
            if (Decor.hangBanner(level, at, out, banner)) {
                k.putBoolean("hung", true);
                return true;
            }
            ItemStack left = Market.intoStores(level, v.id(), banner);
            if (!left.isEmpty()) level.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(level, at.getX(), at.getY(), at.getZ(), left));
            return false;
        }
        return false;
    }

    @Nullable
    static Ledger.Building building(UUID village, CompoundTag k) {
        if (!k.contains("plot")) return null;
        long plot = k.getLong("plot");
        for (Ledger.Building b : Ledger.buildings(village)) {
            if (b.anchor().asLong() == plot && (b.structure().equals(STRUCTURE) || b.structure().equals(GRAND))) return b;
        }
        return null;
    }

    /** A knight's land, for a map or a border (the war room's map, the borders): where, whose, and whether the manor stands. */
    public record Land(BlockPos at, UUID knight, String name, boolean built) {}

    /** Every knight's land in this town. */
    public static List<Land> lands(UUID village) {
        List<Land> out = new ArrayList<>();
        if (!CivicLand.known(village)) return out;
        for (String key : Knights.knights(village).getAllKeys()) {
            CompoundTag k = Knights.knights(village).getCompound(key);
            UUID p = CivicLand.uuid(key);
            if (p == null || !k.contains("plot")) continue;
            out.add(new Land(BlockPos.of(k.getLong("plot")), p, Knights.style(p).word + " " + k.getString("name"), "built".equals(k.getString("manor"))));
        }
        return out;
    }

    /** Is this spot inside a knight's manor (Villages.inAGuestHouse: its chests are its own, not the town's stores)? */
    public static boolean inAManor(UUID village, BlockPos pos) {
        if (!CivicLand.known(village)) return false;
        for (String key : Knights.knights(village).getAllKeys()) {
            CompoundTag k = Knights.knights(village).getCompound(key);
            if (!k.contains("plot")) continue;
            BlockPos p = BlockPos.of(k.getLong("plot"));
            int r = GRAND.equals(k.getString("kind")) ? 7 : 6;
            if (Math.abs(pos.getX() - p.getX()) <= r && Math.abs(pos.getZ() - p.getZ()) <= r && Math.abs(pos.getY() - p.getY()) <= 14) return true;
        }
        return false;
    }

    /** Stripped (Knights.strip): the manor forfeit to the town, its land the town's again. */
    static void forfeit(ServerLevel level, UUID village, UUID knight) {
        CompoundTag k = Knights.knight(village, knight);
        if (k == null || !k.contains("plot")) return;
        if ("built".equals(k.getString("manor"))) {
            CompoundTag f = CivicLand.sub(CivicLand.sub(CivicLand.town(village), "forfeit"), knight.toString());
            f.putLong("plot", k.getLong("plot"));
            f.putString("kind", k.getString("kind"));
            Villages.tell(village, CivicLand.day(level), k.getString("name") + "'s manor was forfeit to the town");
        }
        CivicLand.changed();
    }

    /** "I'll pay for a grander manor": sixty coins into the treasury, and the town's builders raise a knight's hall instead. */
    static String grand(ServerLevel level, UUID village, Player p) {
        CompoundTag k = Knights.knight(village, p.getUUID());
        if (k == null) return "A manor is a knight's. You're no knight of " + Villages.name(village) + ".";
        if (!"wanted".equals(k.getString("manor"))) return "built".equals(k.getString("manor")) ? "Your manor stands already."
            : "Your land isn't granted yet: the town is still finding ground for it.";
        if (GRAND.equals(k.getString("kind"))) return "Your grander manor is on the builders' list already.";
        if (Villages.sitesOf(village).containsKey(STRUCTURE)) return "The builders have begun your manor already: it's too late to change it.";
        if (Market.coinsHeld(p) < GRAND_PRICE) return "A grander manor costs " + GRAND_PRICE + " coins, for the extra stone and timber. You've "
            + Market.coinsHeld(p) + ".";
        Market.payOut(p, GRAND_PRICE);
        Ledger.addCoins(village, GRAND_PRICE);
        Economy.takings(village, GRAND_PRICE);
        // A larger footprint: the plot looked at again, the grant moved if it will not hold the bigger house.
        Villages.Village v = Villages.get(village);
        if (v != null) {
            Villages.Site bigger = find(level, v, GRAND);
            if (bigger != null) put(k, bigger);
        }
        k.putString("kind", GRAND);
        CivicLand.changed();
        Villages.tell(village, CivicLand.day(level), Knights.style(p.getUUID()).word + " " + k.getString("name") + " paid for a grander manor");
        return "Done: " + GRAND_PRICE + " coins to the treasury, and the builders will raise you a knight's hall worthy of the name, at " + at(k) + ".";
    }

    /** Why the town builds it (Villages.whyBuild): "Sir Ada's manor, on the land the town granted them for their service". */
    public static String why(UUID village, String structure) {
        if (CivicLand.known(village)) {
            for (String key : Knights.knights(village).getAllKeys()) {
                CompoundTag k = Knights.knights(village).getCompound(key);
                String kind = k.getString("kind").isEmpty() ? STRUCTURE : k.getString("kind");
                UUID knight = CivicLand.uuid(key);
                if (knight != null && "wanted".equals(k.getString("manor")) && kind.equals(structure)) {
                    return Knights.style(knight).word + " " + k.getString("name") + "'s " + (GRAND.equals(structure) ? "hall" : "manor")
                        + ", on the land the town granted them for their service";
                }
            }
        }
        return "a manor for a knight of the town";
    }

    /** Where the knight's manor is, and how it stands, in a line. */
    static String where(ServerLevel level, UUID village, UUID knight) {
        CompoundTag k = Knights.knight(village, knight);
        if (k == null) return "You have no land here.";
        String state = k.getString("manor");
        return switch (state) {
            case "built" -> "Your manor stands at " + at(k) + ".";
            case "wanted" -> "Your land is at " + at(k) + "; the builders will raise your " + (GRAND.equals(k.getString("kind")) ? "hall" : "manor")
                + " there" + (Villages.sitesOf(village).containsKey(k.getString("kind").isEmpty() ? STRUCTURE : k.getString("kind")) ? " (under way now)." : " when its turn comes.");
            case "nolot" -> "The town is still finding ground for your land.";
            default -> "You have no land here.";
        };
    }

    /** "built", "wanted", "no land yet", for the page. */
    static String state(UUID village, UUID knight) {
        CompoundTag k = Knights.knight(village, knight);
        if (k == null) return "";
        return switch (k.getString("manor")) {
            case "built" -> "manor at " + at(k);
            case "wanted" -> "land at " + at(k) + ", manor to build";
            case "nolot" -> "land to find";
            default -> "";
        };
    }

    /** The key to its manor, at the start of a talk (FolkTalk, with Knights.handOver). Empty if none is waiting. */
    public static String handOver(VillageFolkEntity f, Player p) {
        UUID id = f.ownerId();
        if (id == null) return "";
        CompoundTag k = Knights.knight(id, p.getUUID());
        if (k == null || !"built".equals(k.getString("manor")) || k.getBoolean("keyGiven")) return "";
        ItemStack key = new ItemStack(Items.TRIPWIRE_HOOK);
        key.set(DataComponents.CUSTOM_NAME, Component.literal("Key to your manor in " + Villages.name(id)));
        key.set(DataComponents.LORE, new ItemLore(List.of(
            Component.literal("It stands at " + at(k) + ".").withStyle(ChatFormatting.GRAY),
            Component.literal("Granted by the town to " + Knights.style(p.getUUID()).word + " " + p.getName().getString() + ".").withStyle(ChatFormatting.GRAY))));
        if (!p.getInventory().add(key)) p.drop(key, false);
        k.putBoolean("keyGiven", true);
        CivicLand.changed();
        return "The key to your manor, " + Knights.style(p.getUUID()).word + ". The builders did you proud.";
    }

    // ------------------------------------------------------------------ an operator's hand, and the tests

    /** The land granted now (an operator): looked for afresh if the manor is not yet begun. What is said. */
    public static String grantNow(ServerLevel level, Villages.Village v, UUID knight) {
        CompoundTag k = Knights.knight(v.id(), knight);
        if (k == null) return "They are no knight of " + Villages.name(v.id()) + ".";
        if ("built".equals(k.getString("manor"))) return "The manor stands already, at " + at(k) + ".";
        String kind = k.getString("kind").isEmpty() ? STRUCTURE : k.getString("kind");
        if (Villages.sitesOf(v.id()).containsKey(kind)) return "The builders are at work on it already, at " + at(k) + ".";
        k.remove("plot");
        CivicLand.changed();
        return grant(level, v, knight);
    }

    /** The manor raised now (an operator; the tests): out of the stores ({@code paid}) or by decree. What is said. */
    public static String buildNow(ServerLevel level, Villages.Village v, UUID knight, boolean paid) {
        CompoundTag k = Knights.knight(v.id(), knight);
        if (k == null) return "They are no knight of " + Villages.name(v.id()) + ".";
        if (!k.contains("plot")) grant(level, v, knight);
        if (!k.contains("plot")) return "No ground for the manor yet.";
        if ("built".equals(k.getString("manor"))) return "The manor stands already.";
        String kind = k.getString("kind").isEmpty() ? STRUCTURE : k.getString("kind");
        AdminTools.Done d = AdminTools.buildProject(level, v, kind, paid);
        return d.text();
    }

    /** Tests: the knight's plot, or null. */
    @Nullable
    public static BlockPos plotForTests(UUID village, UUID knight) {
        CompoundTag k = Knights.knight(village, knight);
        return k == null || !k.contains("plot") ? null : BlockPos.of(k.getLong("plot"));
    }

    /** Tests: the manor's state ("wanted", "built"...). */
    public static String manorForTests(UUID village, UUID knight) {
        CompoundTag k = Knights.knight(village, knight);
        return k == null ? "" : k.getString("manor");
    }

    /** Tests: the banner beside its door is up. */
    public static boolean bannerHungForTests(UUID village, UUID knight) {
        CompoundTag k = Knights.knight(village, knight);
        return k != null && k.getBoolean("hung");
    }

    /** Tests: hang it now. */
    public static boolean hangForTests(ServerLevel level, Villages.Village v, UUID knight) {
        CompoundTag k = Knights.knight(v.id(), knight);
        return k != null && hangBanner(level, v, knight, k);
    }
}
