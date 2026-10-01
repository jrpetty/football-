package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * The quest board on the meeting hall: what the village wants done, with a reward in coin
 * from its treasury for whoever does it.
 * <ul>
 * <li><b>Wanted</b>: what the village is short of for its next building or its next age
 *     ("40 iron for the smeltery", "48 timber for a new house"), and what its trades need
 *     (wool for the tailor, lapis for the enchanter, nether wart for the brewer).</li>
 * <li><b>Clear</b>: monsters near where the village works ("clear the spiders from the east
 *     mine", "the zombies from the north fields").</li>
 * </ul>
 * Right-click a posting to take it on (it shows your name), right-click it again to hand in
 * what you have brought: it goes into the village's stores. A cleared posting is claimed the
 * same way. Postings come and go: a new one goes up most mornings, and one nobody takes, or
 * nobody finishes, comes down after four days.
 */
public final class Quests {

    private Quests() {}

    /** The most postings a board holds. */
    public static final int MOST = 5;
    static final int LASTS_DAYS = 4;

    public static final class Posting {
        public final int id;
        public final String kind;            // "bring" or "clear"
        public final String item;            // Errands.matcher key, for "bring"
        public final int count;
        public final String purpose;         // "the smeltery"
        public final String mob;             // "spiders", "zombies", "skeletons", "monsters", for "clear"
        @Nullable public final BlockPos place;
        public final String placeName;       // "the east mine"
        public final int reward;
        public final long posted;
        @Nullable public UUID takenBy;
        public String takenName = "";
        public long takenDay = -1;
        public int done;

        Posting(int id, String kind, String item, int count, String purpose, String mob, @Nullable BlockPos place,
                String placeName, int reward, long posted) {
            this.id = id;
            this.kind = kind;
            this.item = item;
            this.count = count;
            this.purpose = purpose;
            this.mob = mob;
            this.place = place;
            this.placeName = placeName;
            this.reward = reward;
            this.posted = posted;
        }

        public boolean finished() { return done >= count; }

        /** "40 iron for the smeltery", "clear 5 spiders from the east mine". */
        public String words() {
            return kind.equals("bring") ? Errands.words(item, count).replace(" (ingots or raw)", "") + " for " + purpose
                : "clear " + count + " " + mob + " from " + placeName;
        }
    }

    private static final Map<UUID, List<Posting>> BOARD = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LOOKED = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_NEW = new ConcurrentHashMap<>();
    private static int nextId = 1;

    public static void resetForTests() {
        BOARD.clear();
        LOOKED.clear();
        LAST_NEW.clear();
    }

    public static List<Posting> postings(UUID village) {
        return BOARD.computeIfAbsent(village, k -> new ArrayList<>());
    }

    // ------------------------------------------------------------------ the day's postings

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 200 != 97) return;
        com.jrpetty.mcassistant.Guard.run("quests", () -> {
            for (ServerLevel level : event.getServer().getAllLevels()) {
                for (Villages.Village v : Villages.every()) {
                    if (v.dim().equals(level.dimension()) && level.isLoaded(v.centre())) look(level, v);
                }
            }
        });
    }

    /** See to a village's board: old postings down, a new one up (one a morning), the signs written. */
    public static void look(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime(), day = level.getDayTime() / 24000L;
        if (now - LOOKED.getOrDefault(id, -100000L) < 600L) return;
        LOOKED.put(id, now);
        Services.overdue(level, id, day);
        List<Posting> board = postings(id);
        boolean changed = board.removeIf(p -> p.takenBy == null ? day - p.posted > LASTS_DAYS : day - p.takenDay > LASTS_DAYS);
        if (board.size() < MOST && LAST_NEW.getOrDefault(id, -1L) < day && Villages.headcount(id) >= 4) {
            Posting fresh = post(level, v, day);
            if (fresh != null) {
                board.add(fresh);
                LAST_NEW.put(id, day);
                changed = true;
            }
        }
        if (changed || now % 2400L < 600L) paint(level, v);
    }

    /** Something the village wants done now, that is not on the board already. Null if nothing. */
    @Nullable
    public static Posting post(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        List<Posting> board = postings(id);
        Set<String> already = new HashSet<>();
        for (Posting p : board) already.add(p.kind + ":" + (p.kind.equals("bring") ? p.item : p.placeName));
        List<Posting> options = new ArrayList<>();
        // What it is short of.
        for (Villages.Need n : Villages.needs(level, id)) {
            String item = Errands.supplyItem(n.task());
            if (item == null || already.contains("bring:" + item)) continue;
            int count = Math.min(64, Errands.supplyCount(item, n.amount()) * 2);
            options.add(bring(item, count, purpose(n.task(), id), day));
            break;
        }
        // What its trades want.
        String[][] trades = {
            { "TAILOR", "white_wool", "16", "the tailor's loom" },
            { "ENCHANT", "lapis_lazuli", "16", "the enchanter's table" },
            { "BREW", "nether_wart", "8", "the brewery" },
            { "BEEKEEP", "flowers", "12", "the hives" },
            { "SMITH", "iron", "24", "the smithy" },
            { "COOK", "sugar", "16", "the café's cakes" },
        };
        for (String[] t : trades) {
            if (already.contains("bring:" + t[1])) continue;
            boolean has = false;
            for (AssistantEntity a : Villages.folkOf(id)) if (a.stationTask().name().equals(t[0])) { has = true; break; }
            if (has) options.add(bring(t[1], Integer.parseInt(t[2]), t[3], day));
        }
        // Monsters near where the village works.
        Posting hunt = hunt(level, v, day, already);
        if (hunt != null) options.add(0, hunt);
        if (options.isEmpty()) return null;
        return options.get(level.getRandom().nextInt(Math.min(2, options.size())));
    }

    private static Posting bring(String item, int count, String purpose, long day) {
        ItemStack one = sample(item);
        Market.Good g = Market.goodFor(one);
        double each = g == null ? 0.5 : g.value();
        int reward = (int) Math.max(3, Math.min(64, Math.round(each * count * 1.3)));
        return new Posting(nextId++, "bring", item, count, purpose, "", null, "", reward, day);
    }

    static ItemStack sample(String item) {
        return switch (item) {
            case "iron" -> new ItemStack(Items.IRON_INGOT);
            case "coal" -> new ItemStack(Items.COAL);
            case "logs" -> new ItemStack(Items.OAK_LOG);
            case "stone" -> new ItemStack(Items.COBBLESTONE);
            case "food" -> new ItemStack(Items.BREAD);
            case "flowers" -> new ItemStack(Items.POPPY);
            default -> {
                net.minecraft.world.item.Item it = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .get(net.minecraft.resources.ResourceLocation.withDefaultNamespace(item));
                yield new ItemStack(it);
            }
        };
    }

    /** What the village wants a thing for, in words. */
    static String purpose(Villages.Task task, UUID village) {
        String next = Villages.nextProject(village);
        String building = next == null ? "the builders" : Villages.spoken(next);
        return switch (task) {
            case IRON -> Villages.hasBuilt(village, "smeltery") ? "the smeltery" : "the watch's armour";
            case COAL -> "the furnaces";
            case FOOD -> "the larder";
            case DIAMOND -> "the Diamond Age";
            case OBSIDIAN -> "the gateway";
            default -> building;
        };
    }

    /** Monsters about a mine, a field or a wood the village works: a posting to clear them. */
    @Nullable
    static Posting hunt(ServerLevel level, Villages.Village v, long day, Set<String> already) {
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            AssistantEntity.StationTask t = a.stationTask();
            String what = switch (t) {
                case MINE -> "mine";
                case FARM -> "fields";
                case WOOD -> "woods";
                default -> null;
            };
            if (what == null || a.workZone() == null) continue;
            BlockPos at = a.workZone().center();
            String name = "the " + compass(v.centre(), at) + what;
            if (already.contains("clear:" + name) || !level.isLoaded(at)) continue;
            List<Monster> there = level.getEntitiesOfClass(Monster.class, new AABB(at).inflate(32, 16, 32), LivingEntity::isAlive);
            if (there.isEmpty()) continue;
            Map<String, Integer> kinds = new java.util.HashMap<>();
            for (Monster m : there) kinds.merge(kind(m), 1, Integer::sum);
            String mob = "monsters";
            int most = 0;
            for (Map.Entry<String, Integer> k : kinds.entrySet()) if (k.getValue() > most) { most = k.getValue(); mob = k.getKey(); }
            int count = Math.max(3, Math.min(8, most + 2));
            return new Posting(nextId++, "clear", "", count, "", mob, at.immutable(), name, 3 * count, day);
        }
        return null;
    }

    static String kind(LivingEntity m) {
        if (m instanceof net.minecraft.world.entity.monster.Spider) return "spiders";
        if (m instanceof net.minecraft.world.entity.monster.Zombie) return "zombies";
        if (m instanceof net.minecraft.world.entity.monster.AbstractSkeleton) return "skeletons";
        if (m instanceof net.minecraft.world.entity.monster.Creeper) return "creepers";
        return "monsters";
    }

    static String compass(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        if (dx * dx + dz * dz < 12 * 12) return "";
        String ns = Math.abs(dz) * 2 < Math.abs(dx) ? "" : dz < 0 ? "north" : "south";
        String ew = Math.abs(dx) * 2 < Math.abs(dz) ? "" : dx < 0 ? "west" : "east";
        return ns + ew + " ";
    }

    // ------------------------------------------------------------------ the board itself

    /** The board's place: the meeting hall's front wall, or (until there is a hall) the storehouse's. */
    @Nullable
    static Ledger.Building hall(UUID village) {
        Ledger.Building store = null;
        for (Ledger.Building b : Ledger.buildings(village)) {
            if (b.structure().equals("hall")) return b;
            if (b.structure().equals("storage") && store == null) store = b;
        }
        return store;
    }

    /** Where the postings hang: on the front wall either side of the door, nearest first. */
    public static List<BlockPos> spots(ServerLevel level, Ledger.Building b) {
        List<BuildGoal.Placement> plan = BuildGoal.plan(b.structure(), b.anchor(), b.facing(), 13);
        Set<BlockPos> planned = new HashSet<>(), solid = new HashSet<>();
        List<BlockPos> doors = new ArrayList<>();
        for (BuildGoal.Placement p : plan) {
            if (p.part() != BuildGoal.Part.CLEAR) planned.add(p.pos());
            if (p.part() == BuildGoal.Part.BLOCK) solid.add(p.pos());
            if (p.part() == BuildGoal.Part.DOOR) doors.add(p.pos());
        }
        Direction front = b.facing().getOpposite();
        int y = b.anchor().getY() + 1;
        // The front door (the one furthest forward), and the wall it is in.
        BlockPos door = null;
        int layer = Integer.MIN_VALUE;
        for (BlockPos d : doors) {
            int l = dot(d.subtract(b.anchor()), front);
            if (l > layer || (l == layer && door != null && d.getY() < door.getY())) { layer = l; door = d; }
        }
        if (door == null) for (BlockPos w : solid) layer = Math.max(layer, dot(w.subtract(b.anchor()), front));
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos w : solid) {
            if (w.getY() != y || dot(w.subtract(b.anchor()), front) != layer) continue;
            BlockPos at = w.relative(front);
            if (planned.contains(at)) continue;                           // a porch in front of it
            if (door != null && Math.abs(w.getX() - door.getX()) + Math.abs(w.getZ() - door.getZ()) < 2) continue;
            out.add(at);
        }
        final BlockPos d0 = door == null ? b.anchor() : door;
        out.sort(java.util.Comparator.<BlockPos>comparingInt(p -> (int) p.distSqr(d0)).thenComparingLong(BlockPos::asLong));
        return out.size() > MOST ? new ArrayList<>(out.subList(0, MOST)) : out;
    }

    private static int dot(net.minecraft.core.Vec3i v, Direction d) {
        return v.getX() * d.getStepX() + v.getZ() * d.getStepZ();
    }

    /** Write the postings onto the board. */
    public static void paint(ServerLevel level, Villages.Village v) {
        Ledger.Building b = hall(v.id());
        if (b == null || !level.isLoaded(b.anchor())) return;
        paintOn(level, b, postings(v.id()));
    }

    /** Hang these postings on this building's board. Returns the signs written. */
    public static List<BlockPos> paintOn(ServerLevel level, Ledger.Building b, List<Posting> board) {
        List<BlockPos> spots = spots(level, b);
        List<BlockPos> written = new ArrayList<>();
        Direction front = b.facing().getOpposite();
        for (int i = 0; i < spots.size(); i++) {
            BlockPos at = spots.get(i);
            BlockState s = level.getBlockState(at);
            Posting p = i < board.size() ? board.get(i) : null;
            if (!(s.getBlock() instanceof WallSignBlock)) {
                if (!s.isAir() || p == null) continue;
                BlockState sign = Blocks.BIRCH_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, front);
                if (!sign.canSurvive(level, at)) continue;
                level.setBlock(at, sign, 3);
            }
            if (!(level.getBlockEntity(at) instanceof SignBlockEntity sign)) continue;
            sign.getPersistentData().putBoolean("mca_quest", true);
            TownLife.write(sign, p == null ? new String[]{ "QUEST BOARD", "", "nothing posted", "today" } : lines(p));
            written.add(at);
        }
        return written;
    }

    /** Postings to show in pictures and tests. */
    public static List<Posting> samples(long day) {
        List<Posting> out = new ArrayList<>();
        out.add(new Posting(nextId++, "bring", "iron", 40, "the smeltery", "", null, "", 31, day));
        out.add(new Posting(nextId++, "clear", "", 5, "", "spiders", BlockPos.ZERO, "the east mine", 15, day));
        out.add(new Posting(nextId++, "bring", "white_wool", 16, "the tailor's loom", "", null, "", 6, day));
        Posting taken = new Posting(nextId++, "bring", "logs", 48, "a new house", "", null, "", 12, day);
        taken.takenBy = new UUID(0L, 1L);
        taken.takenName = "Steve";
        out.add(taken);
        return out;
    }

    static String[] lines(Posting p) {
        String first = p.kind.equals("bring") ? "WANTED" : "CLEAR";
        String what, where;
        if (p.kind.equals("bring")) {
            what = Errands.words(p.item, p.count).replace(" (ingots or raw)", "");
            where = "for " + p.purpose.replaceFirst("^the ", "");
        } else {
            what = p.count + " " + p.mob;
            where = "from " + p.placeName.replaceFirst("^the ", "");
        }
        String last = p.takenBy == null ? p.reward + " coins" : p.finished() ? "DONE - claim!" : "taken: " + p.takenName;
        return new String[]{ first, cut(what), cut(where), cut(last) };
    }

    private static String cut(String s) {
        return s.length() <= 15 ? s : s.substring(0, 15);
    }

    // ------------------------------------------------------------------ taking one on, handing in

    @SubscribeEvent
    public static void onUseBlock(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getLevel() instanceof ServerLevel level) || e.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(level.getBlockState(e.getPos()).getBlock() instanceof WallSignBlock)) return;
        if (!(level.getBlockEntity(e.getPos()) instanceof SignBlockEntity sign) || !sign.getPersistentData().getBoolean("mca_quest")) return;
        Villages.Village v = Villages.nearest(level, e.getPos(), Villages.VILLAGE_RANGE);
        if (v == null) return;
        Ledger.Building b = hall(v.id());
        if (b == null) return;
        int i = spots(level, b).indexOf(e.getPos());
        List<Posting> board = postings(v.id());
        e.setCanceled(true);
        if (i < 0 || i >= board.size()) {
            e.getEntity().displayClientMessage(Component.literal("Nothing posted there today."), true);
            return;
        }
        String said = use(level, v, e.getEntity(), board.get(i));
        e.getEntity().sendSystemMessage(Component.literal(said).withStyle(ChatFormatting.YELLOW));
        paint(level, v);
    }

    /** A player at the board: take the posting on, hand in toward it, or claim it. Returns what to tell it. */
    public static String use(ServerLevel level, Villages.Village v, Player p, Posting q) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        Standing.Title title = Standing.of(id, p.getUUID(), level.getGameTime()).title();
        if (title == Standing.Title.OUTCAST || Laws.banished(id, p.getUUID(), day)) return "The village wants nothing from you.";
        if (q.takenBy == null) {
            for (Posting o : postings(id)) {
                if (p.getUUID().equals(o.takenBy) && !o.finished()) return "Finish what you took on first: " + o.words() + ".";
            }
            q.takenBy = p.getUUID();
            q.takenName = p.getName().getString();
            q.takenDay = day;
            return "You took on the posting: " + q.words() + ". The reward is " + q.reward + " coins. "
                + (q.kind.equals("bring") ? "Bring it here to the board." : "Come back to the board when it's done.");
        }
        if (!p.getUUID().equals(q.takenBy)) return q.takenName + " has taken that one on.";
        if (q.kind.equals("bring") && !q.finished()) {
            int moved = handIn(level, id, p, q);
            if (moved == 0) return "You've brought " + q.done + " of " + q.count + " so far. You've none on you.";
            if (!q.finished()) return "That's " + q.done + " of " + q.count + ". Bring the rest when you can.";
        }
        if (!q.finished()) return "So far " + q.done + " of " + q.count + " " + q.mob + ". Keep at it!";
        return claim(level, v, p, q);
    }

    /** What the player carries toward a "bring" posting, out of its pack and into the stores. */
    static int handIn(ServerLevel level, UUID village, Player p, Posting q) {
        Predicate<ItemStack> want = Errands.matcher(q.item);
        int moved = 0;
        for (int i = 0; i < p.getInventory().getContainerSize() && q.done < q.count; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.isEmpty() || !want.test(s)) continue;
            int n = Math.min(q.count - q.done, s.getCount());
            ItemStack given = s.split(n);
            ItemStack left = Market.intoStores(level, village, given);
            if (!left.isEmpty()) {
                // The stores are full: the hall takes it anyway (it goes in a pile by the board).
                net.minecraft.world.entity.item.ItemEntity drop = new net.minecraft.world.entity.item.ItemEntity(level,
                    p.getX(), p.getY(), p.getZ(), left);
                level.addFreshEntity(drop);
            }
            q.done += n;
            moved += n;
        }
        p.getInventory().setChanged();
        return moved;
    }

    /** Done: the reward, from the treasury (with a little from the elder's own purse if it is short). */
    static String claim(ServerLevel level, Villages.Village v, Player p, Posting q) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        int fromTreasury = Ledger.takeCoins(id, q.reward);
        int paid = Math.max(fromTreasury, Math.min(q.reward, fromTreasury + Math.max(2, q.reward / 2)));
        ItemStack coins = new ItemStack(McAssistantMod.VILLAGE_COIN.get(), paid);
        if (!p.getInventory().add(coins)) p.drop(coins, false);
        p.giveExperiencePoints(5 + q.count / 2);
        String name = p.getName().getString();
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (a instanceof VillageFolkEntity f) f.persona().feelFor(p.getUUID(), name, 3);
        }
        Standing.stir(id, p.getUUID());
        Villages.tell(id, day, name + " answered the quest board: " + q.words());
        postings(id).remove(q);
        return "Done: " + q.words() + ". The village pays you " + paid + " coins" + (paid < q.reward ? " (all it could find)" : "")
            + ", with its thanks.";
    }

    // ------------------------------------------------------------------ clearing

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level) || !(event.getEntity() instanceof Enemy)) return;
        if (!(event.getSource().getEntity() instanceof Player p)) return;
        killed(level, p, event.getEntity());
    }

    /** A monster a player killed: does it count toward a posting it took on? */
    public static void killed(ServerLevel level, Player p, LivingEntity dead) {
        String k = kind(dead);
        for (Map.Entry<UUID, List<Posting>> e : BOARD.entrySet()) {
            for (Posting q : e.getValue()) {
                if (!q.kind.equals("clear") || !p.getUUID().equals(q.takenBy) || q.finished() || q.place == null) continue;
                if (!q.mob.equals("monsters") && !q.mob.equals(k)) continue;
                if (dead.blockPosition().distSqr(q.place) > 40 * 40) continue;
                q.done++;
                if (q.finished()) {
                    p.sendSystemMessage(Component.literal("That's " + q.words().replaceFirst("^clear ", "") + " cleared. Claim your reward at the quest board.")
                        .withStyle(ChatFormatting.YELLOW));
                }
            }
        }
    }

    // ------------------------------------------------------------------ talk

    /** "What's on the quest board?" */
    public static String talk(VillageFolkEntity f, Player p) {
        UUID village = f.ownerId();
        if (village == null) return "A quest board? We've not even a village.";
        List<Posting> board = postings(village);
        Ledger.Building b = hall(village);
        String where = b == null ? "We've nowhere to post them yet." : b.structure().equals("hall")
            ? "The board's on the front of the meeting hall." : "The board's on the front of the storehouse, till we've a hall.";
        if (board.isEmpty()) return "Nothing on the board today. " + where + " Check back in the morning.";
        StringBuilder sb = new StringBuilder("On the board: ");
        List<String> items = new ArrayList<>();
        for (Posting q : board) {
            items.add(q.words() + " (" + q.reward + " coins" + (q.takenBy == null ? "" : ", " + q.takenName + "'s") + ")");
        }
        sb.append(String.join("; ", items)).append(". ").append(where).append(" Right-click a posting to take it on.");
        return sb.toString();
    }
}
