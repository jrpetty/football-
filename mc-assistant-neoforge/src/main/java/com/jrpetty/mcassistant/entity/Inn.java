package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.Guard;
import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerSetSpawnEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The inn [batchE]: a long timber house by the road into town with two rooms of two beds each at the back
 * and a common room with a bar at the front (inn.txt), built from the Iron Age, or in a town of thirty, on a
 * lot by the avenue where it comes in through the gate, once there is somebody to keep it. It is run by an
 * innkeeper: the café's cook, else the shopkeeper, who lets its rooms at three coins a night and puts the
 * coin in the town's till (the treasury).
 * <ul>
 * <li><b>Travellers.</b> A caravan's driver, an envoy, or a folk on the road to a new home who is in a town
 *     with an inn between dusk and dawn takes a room there for the night, out of its own purse: it walks to
 *     its bed, sleeps till morning, and goes on its way. With no coin, or no bed free, it goes on as before.</li>
 * <li><b>Players.</b> Right-click the inn's sign by its door, or the innkeeper with village coins in your
 *     hand, to take a room for the night: the innkeeper gives you one of the beds, and that bed is yours to
 *     sleep in until the next morning. It does not become your home: your respawn point stays where it was.
 *     The inn's beds are for paying guests: without a room, nobody sleeps in one.</li>
 * </ul>
 * The town's own folk never take an inn bed for their own (VillageFolkEntity.bedOnOffer), and the inn's beds
 * are not counted among the homes' (Villages.bedsMadeUp).
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Inn {

    private Inn() {}

    /** A town of this many has an inn whatever its age; in the Iron Age, a town of the second number. */
    static final int FROM_FOLK = 30, FROM_FOLK_IRON = 16;
    /** A night's room. */
    static final int ROOM = 3;

    /** A folk lodging at an inn tonight: the town, the bed's head, the night it came. */
    private record Lodger(UUID village, BlockPos bed, long night, int since) {}

    private static final Map<UUID, Lodger> LODGERS = new ConcurrentHashMap<>();
    /** The progress a lodger's walk to its bed is making: the nearest it has come, and when. */
    private static final Map<UUID, double[]> WALKS = new ConcurrentHashMap<>();

    static void resetForTests() {
        LODGERS.clear();
        WALKS.clear();
    }

    @Nullable
    static Ledger.Building inn(UUID village) {
        return TownLook.building(village, TownLook.INN);
    }

    /** The inn's sign: on the front wall beside the door, facing the street. */
    static BlockPos signAt(Ledger.Building b) {
        return TownLook.cell(b, -1, 1, -5);
    }

    /** The heads of the inn's beds, as they stand now. */
    static List<BlockPos> beds(ServerLevel level, Ledger.Building b) {
        List<BlockPos> out = new ArrayList<>();
        if (!level.isLoaded(b.anchor())) return out;
        for (BuildGoal.Placement p : BuildGoal.plan(b.structure(), b.anchor(), b.facing(), 13)) {
            if (p.part() != BuildGoal.Part.BED) continue;
            BlockPos head = head(level, p.pos());
            if (head != null) out.add(head);
        }
        return out;
    }

    /** The head of the bed at this block (either half), or null if there is no bed. */
    @Nullable
    static BlockPos head(ServerLevel level, BlockPos p) {
        BlockState st = level.getBlockState(p);
        if (!(st.getBlock() instanceof BedBlock)) return null;
        return st.getValue(BedBlock.PART) == BedPart.HEAD ? p.immutable() : p.relative(st.getValue(BedBlock.FACING)).immutable();
    }

    /** The town whose inn this bed (either half) is in, or null. */
    @Nullable
    static Villages.Village innOfBed(ServerLevel level, BlockPos p) {
        BlockPos head = head(level, p);
        if (head == null) return null;
        for (Villages.Village v : Villages.every()) {
            if (!v.dim().equals(level.dimension())) continue;
            Ledger.Building b = inn(v.id());
            if (b == null || b.anchor().distManhattan(head) > 16) continue;
            if (beds(level, b).contains(head)) return v;
        }
        return null;
    }

    /** Is this bed one of an inn's (for the town's folk: not theirs to sleep in)? Cheap: by the inn's footprint. */
    public static boolean isInnBed(UUID village, BlockPos p) {
        Ledger.Building b = inn(village);
        if (b == null) return false;
        return Math.abs(p.getX() - b.anchor().getX()) <= 5 && Math.abs(p.getZ() - b.anchor().getZ()) <= 5
            && p.getY() >= b.anchor().getY() - 1 && p.getY() <= b.anchor().getY() + 3;
    }

    /**
     * The innkeeper: the café's cook (its first, by name), else the shopkeeper. Null with neither, and then the
     * inn lets no rooms.
     */
    @Nullable
    static VillageFolkEntity keeper(UUID village) {
        VillageFolkEntity cook = null, shop = null;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || !f.isAlive()) continue;
            if (f.stationTask() == AssistantEntity.StationTask.COOK && (cook == null || f.displayNameCap().compareTo(cook.displayNameCap()) < 0)) cook = f;
            if (f.stationTask() == AssistantEntity.StationTask.SHOP && (shop == null || f.displayNameCap().compareTo(shop.displayNameCap()) < 0)) shop = f;
        }
        return cook != null ? cook : shop;
    }

    // ------------------------------------------------------------------ the night's bookings

    /** The morning a room let today is given up (the next day's first hour). */
    static long until(long dayTime) {
        return (dayTime / 24000L + 1) * 24000L + 1000L;
    }

    private static String bookingKey(BlockPos head) {
        return "inn/" + head.asLong();
    }

    /** The player a bed is let to now, or null. Kept in the town's books (Ledger), so a restart keeps the room. */
    @Nullable
    static UUID bookedBy(ServerLevel level, UUID village, BlockPos head) {
        String s = Ledger.note(village, bookingKey(head));
        if (s == null || s.isEmpty()) return null;
        String[] p = s.split("\\|");
        try {
            if (p.length < 2 || level.getDayTime() >= Long.parseLong(p[1])) return null;
            return UUID.fromString(p[0]);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Is a lodger of the folk's in this bed tonight? */
    static boolean lodgerIn(BlockPos head) {
        for (Lodger l : LODGERS.values()) if (l.bed().equals(head)) return true;
        return false;
    }

    /** A bed of the inn's nobody has tonight, or null. */
    @Nullable
    static BlockPos freeBed(ServerLevel level, UUID village, Ledger.Building b) {
        for (BlockPos head : beds(level, b)) {
            if (bookedBy(level, village, head) != null || lodgerIn(head)) continue;
            if (level.getBlockState(head).getValue(BedBlock.OCCUPIED)) continue;
            return head;
        }
        return null;
    }

    /** The bed this player has at this town's inn tonight, or null. */
    @Nullable
    static BlockPos roomOf(ServerLevel level, UUID village, UUID player) {
        Ledger.Building b = inn(village);
        if (b == null) return null;
        for (BlockPos head : beds(level, b)) if (player.equals(bookedBy(level, village, head))) return head;
        return null;
    }

    /** The coin into the till: the treasury, the day's books, and the inn's own count. */
    static void till(UUID village, int coins, boolean player) {
        Ledger.addCoins(village, coins);
        Economy.sold(village, coins);
        Ledger.note(village, "inn.takings", Integer.toString(count(village, "inn.takings") + coins));
        Ledger.note(village, "inn.guests", Integer.toString(count(village, "inn.guests") + 1));
        if (player) Ledger.note(village, "inn.players", Integer.toString(count(village, "inn.players") + 1));
    }

    static int count(UUID village, String key) {
        String s = Ledger.note(village, key);
        try {
            return s == null || s.isEmpty() ? 0 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Where in the inn a bed is, in words: "the room on the left, by the wall". */
    static String where(Ledger.Building b, BlockPos head) {
        Direction right = b.facing().getClockWise();
        int across = (head.getX() - b.anchor().getX()) * right.getStepX() + (head.getZ() - b.anchor().getZ()) * right.getStepZ();
        String room = across < 0 ? "the room on the left" : "the room on the right";
        return room + (Math.abs(across) >= 3 ? ", the bed by the wall" : ", the bed by the partition");
    }

    /**
     * A player takes a room for the night: the innkeeper's, three coins (less if the player has talked the
     * town down today: Dealings), into the till. Returns what the player is told.
     */
    public static String rent(ServerLevel level, Villages.Village v, Player p) {
        UUID id = v.id();
        Ledger.Building b = inn(id);
        if (b == null) return Villages.name(id) + " has no inn yet.";
        VillageFolkEntity keeper = keeper(id);
        if (keeper == null) return "The inn's shut: there's nobody to keep it. (It wants a cook or a shopkeeper in the town.)";
        long day = level.getDayTime() / 24000L;
        Standing.Title title = Standing.of(id, p.getUUID(), level.getGameTime()).title();
        if (title == Standing.Title.OUTCAST || Laws.banished(id, p.getUUID(), day)) return keeper.displayNameCap() + ": \"No room. Not for you.\"";
        BlockPos have = roomOf(level, id, p.getUUID());
        if (have != null) return keeper.displayNameCap() + ": \"You've a room already: " + where(b, have) + ". Sleep well.\"";
        BlockPos bed = freeBed(level, id, b);
        if (bed == null) return keeper.displayNameCap() + ": \"Sorry, we're full tonight.\"";
        int price = Dealings.haggled(id, p.getUUID(), day, ROOM);
        int coins = Market.coinsHeld(p);
        if (coins < price) return keeper.displayNameCap() + ": \"A room's " + price + " coins a night. You've " + coins + ".\"";
        Market.payOut(p, price);
        till(id, price, true);
        Ledger.note(id, bookingKey(bed), p.getUUID() + "|" + until(level.getDayTime()));
        level.playSound(null, bed, SoundEvents.WOOL_PLACE, SoundSource.BLOCKS, 0.6F, 1.0F);
        String said = FolkTalk.pick(keeper.getRandom(), "There you are: ", "Here's your key: ", "Right you are: ")
            + where(b, bed) + ". " + price + " coins, thank you. Breakfast's at the café.";
        if (keeper.distanceToSqr(p) < 32.0 * 32.0) FolkTalk.speak(keeper, said);
        keeper.persona().remember(day, "let a room at the inn to " + p.getName().getString(), 1);
        if (count(id, "inn.players") == 1) Villages.tell(id, day, p.getName().getString() + " took the first room at the inn");
        return keeper.displayNameCap() + ": \"" + said + "\" (Your bed until morning; it does not change where you wake.)";
    }

    // ------------------------------------------------------------------ the town's part

    /** One visit: the inn's sign up (and kept to the truth), and last night's rooms given up. */
    static boolean tick(ServerLevel level, Villages.Village v) {
        // A lodger that never woke here (it was lost on the road, or its ground let go) gives its bed up by mid-morning.
        long dt = level.getDayTime();
        LODGERS.entrySet().removeIf(e -> e.getValue().village().equals(v.id()) && dt / 24000L > e.getValue().night()
            && dt % 24000L >= 2000L && dt % 24000L < 13000L);
        Ledger.Building b = inn(v.id());
        if (b == null || !Land.areaLoaded(level, b.anchor(), 6)) return false;
        BlockPos at = signAt(b);
        VillageFolkEntity keeper = keeper(v.id());
        BlockState st = level.getBlockState(at);
        if (!(st.getBlock() instanceof WallSignBlock)) {
            if (keeper == null || !st.isAir() || !level.getBlockState(at.relative(b.facing())).isSolid()) return false;
            if (!TownLook.canSign(level, v, 1)) return false;
            if (!TownJobs.atWork(level, v, "signs", at, "putting up the inn's sign")) return false;
            if (!TownLook.sign(level, v)) return false;
            level.setBlock(at, Blocks.SPRUCE_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, b.facing().getOpposite()), 3);
            Villages.tell(v.id(), level.getDayTime() / 24000L, "the inn opened its doors, " + keeper.displayNameCap() + " its keeper");
        }
        if (level.getBlockEntity(at) instanceof SignBlockEntity sign) {
            String name = Villages.name(v.id());
            TownLife.write(sign, new String[]{ "The Inn", name.length() > 15 ? name.substring(0, 15) : name,
                keeper == null ? "Closed" : "Rooms " + ROOM + " coins", keeper == null ? "" : "Click for a room" });
        }
        return false;
    }

    // ------------------------------------------------------------------ travellers

    /** Is this folk on the road (a caravan, an envoy, a household moving towns)? */
    static boolean travelling(VillageFolkEntity f) {
        return f.trip() != null || JobSeekers.travelling(f)
            || Interviews.visiting(f);                              // [interviews] come from another town for an interview
    }

    /** Is the folk lodging at an inn just now (its own night routine waits: VillageFolkEntity.calledAway)? */
    public static boolean lodged(VillageFolkEntity f) {
        return LODGERS.containsKey(f.getUUID());
    }

    /**
     * From the folk's tick (VillageFolkEntity.aiStep, ahead of the road): a traveller in a town with an inn
     * after dusk takes a room, walks to its bed and sleeps there till morning. True while it is lodging.
     */
    public static boolean lodging(VillageFolkEntity f, ServerLevel level) {
        Lodger l = LODGERS.get(f.getUUID());
        long dt = level.getDayTime(), t = dt % 24000L, day = dt / 24000L;
        if (l == null) {
            if (f.tickCount % 40 != 17 || f.isBaby() || !travelling(f)) return false;
            if (t < 13000L || t >= 23000L || f.getTarget() != null) return false;
            return takeARoom(level, f) != null;
        }
        boolean morning = day > l.night() && t < 13000L || t >= 23500L;
        if (morning || f.getTarget() != null || !(level.getBlockState(l.bed()).getBlock() instanceof BedBlock)) {
            if (f.isSleeping()) f.stopSleeping();
            LODGERS.remove(f.getUUID());
            WALKS.remove(f.getUUID());
            if (f.trip() != null) f.trip().gainedTick = f.tickCount;   // the night's stop is no hold-up on the road
            if (morning) f.brain("on its way again after a night at the inn in " + Villages.name(l.village()));
            return false;
        }
        if (f.isSleeping()) return true;
        double d = f.blockPosition().distSqr(l.bed());
        if (d > 4.0) {
            double[] w = WALKS.computeIfAbsent(f.getUUID(), k -> new double[]{ Double.MAX_VALUE, f.tickCount, -1000 });
            if (d < w[0] - 1.0) { w[0] = d; w[1] = f.tickCount; }
            if (f.tickCount - w[1] > 600) {                                        // no nearer in half a minute: set down by the bed
                BlockPos by = Trades.floorSpot(level, l.bed(), 2);
                if (by != null) f.moveTo(by.getX() + 0.5, by.getY(), by.getZ() + 0.5, f.getYRot(), 0.0F);
                w[1] = f.tickCount;
            }
            if (f.getNavigation().isDone() || f.tickCount - w[2] > 60) {
                f.walkTo(l.bed(), 0.9D);
                w[2] = f.tickCount;
            }
            f.hobbyNow = "on its way to its room at the inn";
            return true;
        }
        f.getNavigation().stop();
        f.startSleeping(l.bed());
        f.hobbyNow = "asleep at the inn";
        return true;
    }

    /** A room taken by a traveller in the town it is in, paid out of its purse. Returns the bed, or null. */
    @Nullable
    static BlockPos takeARoom(ServerLevel level, VillageFolkEntity f) {
        Villages.Village v = Villages.nearest(level, f.blockPosition(), Villages.VILLAGE_RANGE);
        if (v == null || v.id().equals(f.ownerId())) return null;
        int far = Math.max(Math.abs(f.getBlockX() - v.centre().getX()), Math.abs(f.getBlockZ() - v.centre().getZ()));
        if (far > Villages.townReach(v.id()) + 8) return null;
        Ledger.Building b = inn(v.id());
        if (b == null || keeper(v.id()) == null) return null;
        BlockPos bed = freeBed(level, v.id(), b);
        if (bed == null || f.purse() < ROOM || !f.spend(ROOM)) return null;
        till(v.id(), ROOM, false);
        long day = level.getDayTime() / 24000L;
        LODGERS.put(f.getUUID(), new Lodger(v.id(), bed, day, f.tickCount));
        f.getNavigation().stop();
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "A room for the night, please.", "That's far enough for today.",
            "I'll go on in the morning."));
        f.persona().remember(day, "spent the night at the inn in " + Villages.name(v.id()), 1);
        if (count(v.id(), "inn.guests") == 1) {
            Villages.tell(v.id(), day, f.displayNameCap() + " of " + (f.ownerId() == null ? "the road" : Villages.name(f.ownerId()))
                + " was the inn's first guest");
        }
        return bed;
    }

    // ------------------------------------------------------------------ a player at the inn

    /** The inn's sign right-clicked: a room. */
    @SubscribeEvent
    public static void onUseBlock(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getLevel() instanceof ServerLevel level) || e.getHand() != InteractionHand.MAIN_HAND) return;
        BlockPos pos = e.getPos();
        if (!(level.getBlockState(pos).getBlock() instanceof WallSignBlock)) return;
        Villages.Village v = Villages.nearest(level, pos, Villages.VILLAGE_RANGE * 2);
        if (v == null) return;
        Ledger.Building b = inn(v.id());
        if (b == null || !signAt(b).equals(pos)) return;
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        Player p = e.getEntity();
        Guard.run("the inn", () -> p.sendSystemMessage(Component.literal(rent(level, v, p)).withStyle(ChatFormatting.YELLOW)));
    }

    /** The innkeeper right-clicked with village coins in hand: a room. (Without coins in hand, a chat as ever.) */
    @SubscribeEvent
    public static void onUseFolk(PlayerInteractEvent.EntityInteract e) {
        if (!(e.getLevel() instanceof ServerLevel level) || e.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(e.getTarget() instanceof VillageFolkEntity f) || f.ownerId() == null) return;
        Player p = e.getEntity();
        if (!Market.isCoin(p.getMainHandItem()) || p.isShiftKeyDown()) return;
        Villages.Village v = Villages.get(f.ownerId());
        if (v == null || inn(v.id()) == null || keeper(v.id()) != f) return;
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        Guard.run("the inn", () -> p.sendSystemMessage(Component.literal(rent(level, v, p)).withStyle(ChatFormatting.YELLOW)));
    }

    /** Nobody sleeps in an inn bed without a room: a player whose room it is, yes. */
    @SubscribeEvent
    public static void onSleep(CanPlayerSleepEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        Villages.Village v = innOfBed(level, e.getPos());
        if (v == null) return;
        BlockPos head = head(level, e.getPos());
        if (head == null) return;
        UUID who = bookedBy(level, v.id(), head);
        if (e.getEntity().getUUID().equals(who)) return;
        if (e.getEntity().isCreative()) return;
        e.setProblem(Player.BedSleepingProblem.OTHER_PROBLEM);
        e.getEntity().sendSystemMessage(Component.literal(who != null ? "That bed's let to somebody else tonight."
            : "That's one of the inn's beds: right-click the inn's sign (or the innkeeper with coins in hand) for a room, "
            + ROOM + " coins a night.").withStyle(ChatFormatting.YELLOW));
    }

    /** A night at the inn is not a home: a player's respawn point is not moved to an inn bed. */
    @SubscribeEvent
    public static void onSetSpawn(PlayerSetSpawnEvent e) {
        if (e.isForced() || e.getNewSpawn() == null || !(e.getEntity().level() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(e.getSpawnLevel())) return;
        if (innOfBed(level, e.getNewSpawn()) != null) e.setCanceled(true);
    }

    // ------------------------------------------------------------------ where a player sees it

    @Nullable
    static String boardPart(ServerLevel level, UUID village) {
        Ledger.Building b = inn(village);
        if (b == null || !level.isLoaded(b.anchor())) return null;
        VillageFolkEntity keeper = keeper(village);
        if (keeper == null) return "the inn (shut: no keeper)";
        List<BlockPos> beds = beds(level, b);
        int taken = 0;
        for (BlockPos head : beds) if (bookedBy(level, village, head) != null || lodgerIn(head)) taken++;
        return "the inn: " + taken + " of " + beds.size() + " beds taken tonight at " + ROOM + " coins, " + keeper.displayNameCap() + " keeping it";
    }

    @Nullable
    static String cardPart(VillageFolkEntity f) {
        Lodger l = LODGERS.get(f.getUUID());
        if (l != null) return "lodging at the inn in " + Villages.name(l.village()) + " tonight";
        UUID village = f.ownerId();
        if (village == null || inn(village) == null || keeper(village) != f) return null;
        return "keeps the inn: " + count(village, "inn.guests") + " guests so far, " + count(village, "inn.takings") + " coins in the till";
    }

    static String line(ServerLevel level, Villages.Village v) {
        String part = boardPart(level, v.id());
        return part == null ? "none yet" : part + "; " + count(v.id(), "inn.guests") + " guests in all, " + count(v.id(), "inn.takings") + " coins taken";
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the inn's beds (their heads). */
    public static List<BlockPos> bedsForTests(ServerLevel level, UUID village) {
        Ledger.Building b = inn(village);
        return b == null ? List.of() : beds(level, b);
    }

    /** Tests: the bed a player has at the inn tonight, or null. */
    @Nullable
    public static BlockPos roomForTests(ServerLevel level, UUID village, UUID player) {
        return roomOf(level, village, player);
    }

    /** Tests: this folk takes a room as a traveller would (whether or not it is on the road). Returns its bed, or null. */
    @Nullable
    public static BlockPos lodgeForTests(ServerLevel level, VillageFolkEntity f) {
        return takeARoom(level, f);
    }

    /** Tests: the inn's sign, and the innkeeper (or null). */
    public static Object[] signAndKeeperForTests(UUID village) {
        Ledger.Building b = inn(village);
        return new Object[]{ b == null ? null : signAt(b), keeper(village) };
    }

    /** Tests: may this player sleep in this inn bed now? (What CanPlayerSleepEvent decides.) */
    public static boolean maySleepForTests(ServerLevel level, BlockPos bed, UUID player) {
        Villages.Village v = innOfBed(level, bed);
        if (v == null) return true;
        BlockPos head = head(level, bed);
        return head != null && player.equals(bookedBy(level, v.id(), head));
    }

    /** Tests: the inn's round (its sign). */
    public static void tickForTests(ServerLevel level, Villages.Village v) {
        tick(level, v);
    }
}
