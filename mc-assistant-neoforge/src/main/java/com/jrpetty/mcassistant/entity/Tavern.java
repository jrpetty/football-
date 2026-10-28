package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.Guard;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * The tavern, of an evening.
 * <ul>
 * <li>Off-work folk drop in two evenings in five, stand about the tables and the fire, and
 *     talk — and somebody always tells a story: something that really happened, out of the
 *     village's history ("Remember the night the raiders came at the north gate?").</li>
 * <li>Once there are a few in, the music starts: a tune on the note blocks in the corner,
 *     the bass on the blocks themselves and the melody over it.</li>
 * <li>A player can <b>buy a round</b>: right-click the board on the bar ("Buy a round, a
 *     coin a head"). Everybody in the tavern raises a glass to you and thinks the better of
 *     you, and the village remembers it.</li>
 * <li>What is drunk is the café's: the cook's cider, juices, honey tea and cocoa, out of the
 *     stores, a bottle a head (the bottles go back). A folk in for the evening with a few coins
 *     put by buys itself one. With none in the stores, the round is drunk in water, and the
 *     tavern's books (Stockroom) count the drink it had not got, so the cook makes more.</li>
 * <li>[roles-c] <b>The barkeep</b> (Hospitality.Role.BARKEEP) pours a round when it is behind the bar, and keeps a coin
 *     in four of it; the rest goes in the till (the treasury), and the tavern keeps its own count of its takings and
 *     rounds. Ask it for a pint (its talk option) and it pours you one of the café's drinks out of the stores at the
 *     town's price. Bring your empty bottles back to it: a coin for every three, out of the till, and the bottles go
 *     back in the stores for the cook to fill again.</li>
 * </ul>
 */
public final class Tavern {

    private Tavern() {}

    private static final Map<BlockPos, Integer> BEAT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> STORY = new ConcurrentHashMap<>();
    private static final Set<String> TOASTED = ConcurrentHashMap.newKeySet();
    /** The evening each folk last bought itself a drink at the bar. */
    private static final Map<UUID, Long> DRANK = new ConcurrentHashMap<>();

    /** [roles-c] The barkeep keeps a coin in this many of a round it pours. */
    static final int ROUND_SHARE = 4;
    /** [roles-c] Empty bottles brought back to the bar are paid a coin for every this many. */
    static final int BOTTLE_DEPOSIT = 3;
    /** [roles-c] How near the tavern's middle the barkeep must be to be behind its bar. */
    static final double BAR_REACH = 12.0;

    public static void resetForTests() {
        BEAT.clear();
        STORY.clear();
        TOASTED.clear();
        DRANK.clear();
    }

    /** The village's tavern, or null. */
    @Nullable
    public static Ledger.Building of(@Nullable UUID village) {
        if (village == null) return null;
        for (Ledger.Building b : Ledger.buildings(village)) if (b.structure().equals("tavern")) return b;
        return null;
    }

    /** Is this folk in for the evening (two evenings in five)? */
    static boolean goingTonight(VillageFolkEntity f, long day) {
        return Math.floorMod(day * 7 + f.getUUID().hashCode(), 5) < 2 + Ethos.tavernNights(f.ownerId());   // [identity] worldly or devout
    }

    /** An off-work folk's evening at the tavern. True while it is going or there. */
    public static boolean evening(VillageFolkEntity f, long t) {
        UUID village = f.ownerId();
        Ledger.Building tav = of(village);
        if (tav == null || f.isBaby() || !(f.level() instanceof ServerLevel level) || !level.isLoaded(tav.anchor())) return false;
        if (!LawBook.tavernOpen(village, t)) return false;            // [identity] shut at nightfall by law, or by the curfew
        long day = level.getDayTime() / 24000L;
        if (!goingTonight(f, day) && !Habits.regular(f) && !Bard.playing(level, village)   // [individual] a regular: every evening
                && !Buskers.tavernTonight(level, village)) return false;   // [batchG] a bard in: the whole town comes; [arms] or its busker
        Direction back = tav.facing(), right = back.getClockWise();
        int h = f.getUUID().hashCode();
        BlockPos spot = tav.anchor().relative(right, Math.floorMod(h, 4)).relative(back, Math.floorMod(h >> 3, 5) - 2);
        if (!RestDay.arrive(f, spot)) return true;
        f.hobbyNow = "at the tavern";
        f.lastLeisureTick = f.tickCount;
        BlockPos hearth = tav.anchor().relative(back, 4);
        f.getLookControl().setLookAt(hearth.getX() + 0.5, hearth.getY() + 1.0, hearth.getZ() + 0.5);
        tell(f, level, village);
        Villages.Village v = Villages.get(village);
        if (v != null && f.purse() >= 3 && DRANK.getOrDefault(f.getUUID(), -1L) != day && !LawBook.dry(v.id())) {
            ServiceQueue.Turn turn = ServiceQueue.turn(f, level, ServiceRole.TAVERN, "a drink");   // [shops-homes] served at the bar, in turn
            if (turn == ServiceQueue.Turn.BUSY) return true;
            if (turn == ServiceQueue.Turn.CLOSED) DRANK.put(f.getUUID(), day);
        }
        if (v != null) drink(f, level, v, day);
        return true;
    }

    // ------------------------------------------------------------------ the bar

    /**
     * A drink at the bar, once an evening, for a folk with a few coins put by: one of the café's drinks
     * out of the stores, paid into the treasury and drunk there and then. With none in the stores, the
     * tavern's books count the drink it had not got (Stockroom), and the cook makes more.
     */
    static void drink(VillageFolkEntity f, ServerLevel level, Villages.Village v, long day) {
        if (f.purse() < 3 || DRANK.getOrDefault(f.getUUID(), -1L) == day) return;
        if (LawBook.dry(v.id())) return;                               // [identity] a dry town: nothing sold at the bar
        DRANK.put(f.getUUID(), day);
        if (DRANK.size() > 4096) DRANK.clear();
        if (TradeGoods.stout(level, v, f, day)) return;              // [player-civic] the master brewer's stout, half the evenings
        if (Kitchen.atTheBar(level, v, f, day)) return;             // [kitchen] the brewer's mead, or in the autumn its cider
        net.minecraft.world.item.ItemStack d = pour(level, v, f.getRandom());
        if (d.isEmpty()) {
            Cafe.Drink usual = Cafe.DRINKS.get(Math.floorMod(f.getUUID().hashCode(), Cafe.DRINKS.size()));
            Stockroom.missed(level, v.id(), Stockroom.Seller.TAVERN, Cafe.drink(usual));
            return;
        }
        int price = Stockroom.asked(level, v.id(), d, 1, 1);
        if (!f.spend(price)) {
            Crafts.store(level, v, d);
            return;
        }
        Ledger.addCoins(v.id(), price);
        Economy.spentInTown(v.id(), price);
        Stockroom.sold(level, v.id(), Stockroom.Seller.TAVERN, d, 1, price);
        book(v.id(), "tavern.takings", price);                         // [roles-c] the tavern's own count
        drunk(level, v, f, d);
    }

    /** One of the café's drinks out of the stores, any of them, or nothing if there are none. */
    static net.minecraft.world.item.ItemStack pour(ServerLevel level, Villages.Village v, RandomSource r) {
        List<net.minecraft.world.item.ItemStack> drinks = Cafe.drinksInStores(level, v.id());
        if (drinks.isEmpty()) return net.minecraft.world.item.ItemStack.EMPTY;
        net.minecraft.world.item.ItemStack pick = drinks.get(r.nextInt(drinks.size()));
        if (!TownWork.take(level, v, s -> net.minecraft.world.item.ItemStack.isSameItemSameComponents(s, pick), 1)) {
            return net.minecraft.world.item.ItemStack.EMPTY;
        }
        return pick.copyWithCount(1);
    }

    /** Drunk there and then: it does its little good, and the bottle goes back to the stores. */
    private static void drunk(ServerLevel level, Villages.Village v, VillageFolkEntity f, net.minecraft.world.item.ItemStack d) {
        Cafe.Drink kind = Cafe.drinkFor(Cafe.drinkOf(d) == null ? "" : Cafe.drinkOf(d));
        if (kind != null) f.addEffect(new MobEffectInstance(kind.effect(), kind.ticks(), 0));
        Crafts.store(level, v, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GLASS_BOTTLE));
        f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
    }

    /** Somebody tells a story, every so often: a thing out of the village's history. */
    static void tell(VillageFolkEntity f, ServerLevel level, UUID village) {
        long now = level.getGameTime();
        if (now - STORY.getOrDefault(village, -100000L) < 260L) return;
        RandomSource r = f.getRandom();
        if (r.nextInt(3) != 0) return;
        STORY.put(village, now);
        List<Chronicle.Entry> past = Chronicle.of(village);
        if (past.isEmpty()) return;
        Chronicle.Entry e = past.get(r.nextInt(past.size()));
        long ago = level.getDayTime() / 24000L - e.day();
        String when = ago <= 1 ? "just yesterday" : ago <= 7 ? ago + " days back" : "back on day " + e.day();
        FolkTalk.speak(f, FolkTalk.pick(r, "Remember when " + e.text() + "? That was " + when + ".",
            "I'll never forget it: " + e.text() + ", " + when + ".", "Did I ever tell you how " + e.text() + "?"));
        // And somebody answers.
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity o && o != f && o.distanceToSqr(f) < 36.0 && !o.isSleeping()) {
                o.sayLater(FolkTalk.pick(r, "Aye, I remember!", "Those were the days.", "You tell it better every time.",
                    "Ha! I was there!", "Not this one again..."), 50);
                break;
            }
        }
    }

    // ------------------------------------------------------------------ the music

    /** A jig in G, a beat to a note (-1 a rest): note-block pitches, 0 the lowest F sharp. */
    static final int[] JIG = { 13, 15, 17, 18, 17, 15, 13, -1, 13, 15, 13, 10, 8, 10, 13, -1,
        18, 20, 22, 20, 18, 17, 15, -1, 13, 15, 17, 15, 13, 10, 13, -1 };
    /** And a slow air for late in the evening. */
    static final int[] AIR = { 6, -1, 10, 13, -1, 11, 10, -1, 8, -1, 6, 8, 10, -1, -1, -1,
        13, -1, 15, 13, -1, 11, 10, -1, 8, 10, 11, 10, 6, -1, -1, -1 };

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 5 != 2) return;
        Guard.run("tavern", () -> {
            for (ServerLevel level : event.getServer().getAllLevels()) {
                for (Villages.Village v : Villages.every()) {
                    if (v.dim().equals(level.dimension())) play(level, v);
                }
            }
        });
    }

    /** A beat of music, if there is company enough in the tavern of an evening. */
    static void play(ServerLevel level, Villages.Village v) {
        long t = level.getDayTime() % 24000L;
        if (t < 12500L || t > 17000L) return;
        if (Music.playing(v.id())) return;                         // [batchD] the band has the tavern tonight (Music)
        if (Buskers.atTheTavern(v.id())) return;                   // [arms] and its busker before the band
        Ledger.Building tav = of(v.id());
        if (tav == null || !level.isLoaded(tav.anchor())) return;
        if (company(level, tav) < 2) return;
        int beat = BEAT.merge(tav.anchor(), 1, Integer::sum);
        int[] tune = t < 15000L ? JIG : AIR;
        int note = tune[Math.floorMod(beat, tune.length)];
        if (note < 0) return;
        List<BlockPos> blocks = noteBlocks(tav);
        float pitch = (float) Math.pow(2.0, (note - 12) / 12.0);
        BlockPos at = blocks.isEmpty() ? tav.anchor() : blocks.get(0);
        level.playSound(null, at, t < 15000L ? SoundEvents.NOTE_BLOCK_FLUTE.value() : SoundEvents.NOTE_BLOCK_HARP.value(),
            SoundSource.RECORDS, 1.2F, pitch);
        level.sendParticles(ParticleTypes.NOTE, at.getX() + 0.5, at.getY() + 1.2, at.getZ() + 0.5, 0, note / 24.0, 0.0, 0.0, 1.0);
        // The bass, every other beat, on the second block itself.
        if (blocks.size() > 1 && beat % 2 == 0) {
            BlockPos bass = blocks.get(1);
            BlockState s = level.getBlockState(bass);
            if (s.getBlock() instanceof NoteBlock) {
                level.setBlock(bass, s.setValue(NoteBlock.NOTE, Math.floorMod(note - 12, 25)), 2);
                level.blockEvent(bass, s.getBlock(), 0, 0);
            }
        }
    }

    /** Folk in the tavern now. */
    static int company(ServerLevel level, Ledger.Building tav) {
        return level.getEntitiesOfClass(VillageFolkEntity.class, new AABB(tav.anchor()).inflate(5, 3, 5),
            f -> f.isAlive() && !f.isSleeping()).size();
    }

    private static final Map<Ledger.Building, List<BlockPos>> NOTES = new ConcurrentHashMap<>();

    static List<BlockPos> noteBlocks(Ledger.Building tav) {
        return NOTES.computeIfAbsent(tav, k -> {
            List<BlockPos> out = new ArrayList<>();
            for (BuildGoal.Placement p : BuildGoal.plan(k.structure(), k.anchor(), k.facing(), 13)) {
                if (p.part() == BuildGoal.Part.NOTE_BLOCK) out.add(p.pos());
            }
            return List.copyOf(out);
        });
    }

    // ------------------------------------------------------------------ a round

    /** The board on the bar that says a round may be bought, put up if it is missing, for nothing
     *  (the showcase and the tests). */
    public static void board(ServerLevel level, Ledger.Building tav) {
        board(level, null, tav, true);
    }

    /** The board on the bar, put up if it is missing: a sign out of the village's stores (or two
     *  planks), unless {@code free}; no board till there is one. */
    static void board(ServerLevel level, @javax.annotation.Nullable Villages.Village v, Ledger.Building tav, boolean free) {
        Direction back = tav.facing(), right = back.getClockWise();
        // The bar runs down the left of the room; the board hangs on its front, facing the tables.
        BlockPos barrel = tav.anchor().relative(right, -2);
        if (!level.getBlockState(barrel).is(Blocks.BARREL)) return;
        BlockPos at = barrel.relative(right);
        BlockState s = level.getBlockState(at);
        if (!(s.getBlock() instanceof WallSignBlock)) {
            if (!s.isAir()) return;
            if (!free && (v == null || !Crafts.sign(level, v))) return;
            level.setBlock(at, Blocks.SPRUCE_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, right), 3);
        }
        if (level.getBlockEntity(at) instanceof SignBlockEntity sign) {
            TownLife.write(sign, new String[]{ "Buy a round", "a coin a head", "", "(right-click)" });
        }
    }

    @SubscribeEvent
    public static void onUseBlock(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        BlockPos pos = e.getPos();
        if (!(level.getBlockState(pos).getBlock() instanceof WallSignBlock)) return;
        if (!(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) return;
        if (!"Buy a round".equals(sign.getFrontText().getMessage(0, false).getString())) return;
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        if (e.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        Villages.Village v = Villages.nearest(level, pos, 96);
        if (v == null) return;
        e.getEntity().displayClientMessage(Component.literal(round(level, v, e.getEntity())), true);
    }

    /** A player buys a round for everybody in the tavern. Returns what to tell them. */
    public static String round(ServerLevel level, Villages.Village v, Player p) {
        Ledger.Building tav = of(v.id());
        if (tav == null) return "There's no tavern here.";
        List<VillageFolkEntity> in = level.getEntitiesOfClass(VillageFolkEntity.class, new AABB(tav.anchor()).inflate(5, 3, 5),
            f -> f.isAlive() && !f.isSleeping() && !f.isBaby() && v.id().equals(f.ownerId()));
        if (in.isEmpty()) return "Nobody's in to drink it.";
        int price = in.size();
        int coins = Market.coinsHeld(p);
        if (coins < price) return "A round for " + in.size() + " is " + price + " coins. You have " + coins + ".";
        Market.payOut(p, price);
        // [roles-c] The barkeep behind the bar pours it, and keeps its share; the rest to the till.
        VillageFolkEntity barkeep = barkeep(level, v.id(), tav);
        int share = barkeep == null ? 0 : price / ROUND_SHARE;
        if (share > 0) barkeep.earn(share);
        till(v.id(), price - share);
        book(v.id(), "tavern.rounds", 1);
        long day = level.getDayTime() / 24000L;
        String name = p.getName().getString();
        int poured = 0;
        for (VillageFolkEntity f : in) {
            boolean first = TOASTED.add(f.getUUID() + "/" + p.getUUID() + "/" + day);
            if (first) {
                f.persona().feelFor(p.getUUID(), name, 4);
                f.persona().remember(day, name + " bought a round at the tavern", 3);
            }
            // A bottle of the café's out of the stores, a head; with none, the toast is drunk in water.
            net.minecraft.world.item.ItemStack d = pour(level, v, f.getRandom());
            if (!d.isEmpty()) {
                drunk(level, v, f, d);
                Stockroom.sold(level, v.id(), Stockroom.Seller.TAVERN, d, 1, 1);
                poured++;
            } else {
                Stockroom.missed(level, v.id(), Stockroom.Seller.TAVERN,
                    Cafe.drink(Cafe.DRINKS.get(Math.floorMod(f.getUUID().hashCode(), Cafe.DRINKS.size()))));
            }
            f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            f.sayLater(FolkTalk.pick(f.getRandom(), "To " + name + "!", "Cheers, " + name + "!", "Good health!",
                "A gentleman and a scholar!", "Well, I never — thank you!"), 10 + f.getRandom().nextInt(40));
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, f.getX(), f.getY() + 2.0, f.getZ(), 4, 0.3, 0.2, 0.3, 0.0);
        }
        if (TOASTED.size() > 4096) TOASTED.clear();
        if (barkeep != null) {                                          // [roles-c] behind the bar, pouring
            barkeep.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            FolkTalk.speak(barkeep, FolkTalk.pick(barkeep.getRandom(), "A round on " + name + "! Glasses up!",
                "One each, coming up. Courtesy of " + name + ".", "Drinks all round! Thank " + name + "."));
            barkeep.persona().remember(day, "poured a round " + name + " bought", 1);
        }
        level.playSound(null, tav.anchor(), SoundEvents.GENERIC_DRINK, SoundSource.NEUTRAL, 1.0F, 1.0F);
        Villages.tell(v.id(), day, name + " bought a round at the tavern");
        Standing.stir(v.id(), p.getUUID());
        return "You bought a round for " + in.size() + " (" + price + (price == 1 ? " coin" : " coins") + ")"
            + (barkeep != null ? ", poured by " + barkeep.displayNameCap() : "") + ". Cheers!"
            + (poured == 0 ? " (Water all round: there's nothing from the café in the stores.)"
                : poured < in.size() ? " (" + poured + " of the café's drinks, and water for the rest.)" : "");
    }

    // ------------------------------------------------------------------ [roles-c] the barkeep and the till

    /** The barkeep behind the tavern's bar now (awake, in the tavern), or null: the bar serves itself. */
    @Nullable
    static VillageFolkEntity barkeep(ServerLevel level, UUID village, Ledger.Building tav) {
        for (VillageFolkEntity f : Hospitality.in(village, Hospitality.Role.BARKEEP)) {
            if (f.level() == level && !f.isSleeping() && f.distanceToSqr(tav.anchor().getX() + 0.5, tav.anchor().getY(), tav.anchor().getZ() + 0.5)
                    <= BAR_REACH * BAR_REACH) return f;
        }
        return null;
    }

    /** The coin into the till: the treasury, the day's books, and the tavern's own count. */
    static void till(UUID village, int coins) {
        if (coins <= 0) return;
        Ledger.addCoins(village, coins);
        Economy.sold(village, coins);
        book(village, "tavern.takings", coins);
    }

    /** One of the tavern's own counts, so much more. */
    static void book(UUID village, String key, int n) {
        Ledger.note(village, key, Integer.toString(count(village, key) + n));
    }

    /** One of the tavern's own counts (its takings, its rounds, the bottles back). */
    static int count(UUID village, String key) {
        String s = Ledger.note(village, key);
        try {
            return s == null || s.isEmpty() ? 0 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * A drink for a player at the bar: one of the café's drinks out of the stores (the one named, else any), at the
     * town's price into the till, booked as the tavern's sale. Returns what to tell the player, and whether it went
     * through. The bar's desk (TavernDesk) and the barkeep's talk both pour this way.
     */
    static Inn.Let pint(ServerLevel level, Villages.Village v, Player p, @Nullable String lineId, @Nullable VillageFolkEntity barkeep) {
        UUID village = v.id();
        if (LawBook.dry(village)) return new Inn.Let(barkeep, "Nothing's poured here: the town's dry by law.", false);
        ItemStack one = ItemStack.EMPTY;
        List<ItemStack> drinks = Cafe.drinksInStores(level, village);
        for (ItemStack s : drinks) if (lineId == null || Goods.lineId(s).equals(lineId)) { one = s.copyWithCount(1); break; }
        if (one.isEmpty()) {
            Cafe.Drink usual = Cafe.DRINKS.get(Math.floorMod(p.getUUID().hashCode(), Cafe.DRINKS.size()));
            Stockroom.missed(level, village, Stockroom.Seller.TAVERN, Cafe.drink(usual));
            return new Inn.Let(barkeep, drinks.isEmpty() ? "The casks are dry: there's nothing from the café in the stores. Ask the cook."
                : "That's all gone just now.", false);
        }
        if (!(p instanceof net.minecraft.server.level.ServerPlayer sp)) return new Inn.Let(barkeep, "That's all gone just now.", false);
        ShopDesk.Quote q = ShopDesk.quote(level, village, sp, List.of(one), List.of(1));
        if (Market.coinsHeld(p) < q.total()) return new Inn.Let(barkeep, Goods.name(one) + " is " + q.total() + ". You've " + Market.coinsHeld(p) + ".", false);
        ItemStack want = one;
        if (!TownWork.take(level, v, s -> ItemStack.isSameItemSameComponents(s, want), 1)) return new Inn.Let(barkeep, "That's all gone just now.", false);
        Market.payOut(p, q.total());
        till(village, q.total());
        Stockroom.sold(level, village, Stockroom.Seller.TAVERN, one, 1, q.total());
        if (!p.getInventory().add(one.copy())) p.drop(one.copy(), false);
        if (barkeep != null) barkeep.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        return new Inn.Let(barkeep, Goods.name(one) + ", " + q.total() + (q.total() == 1 ? " coin" : " coins") + ". Good health!"
            + " Bring the bottle back when you're done: a coin for every " + BOTTLE_DEPOSIT + ".", true);
    }

    /**
     * Empty bottles brought back to the bar: a coin for every three, out of the till, and the bottles into the stores
     * for the cook to fill again. An odd one or two are left with the player till it has three. Nothing is paid that
     * the till has not got.
     */
    static Inn.Let bottlesBack(ServerLevel level, Villages.Village v, Player p, @Nullable VillageFolkEntity barkeep) {
        Predicate<ItemStack> empty = s -> s.is(Items.GLASS_BOTTLE) && s.getComponentsPatch().isEmpty();
        int held = 0;
        for (ItemStack s : p.getInventory().items) if (empty.test(s)) held += s.getCount();
        if (held == 0) return new Inn.Let(barkeep, "You've no empty bottles on you. Bring them back here: a coin for every " + BOTTLE_DEPOSIT + ".", false);
        int coins = held / BOTTLE_DEPOSIT;
        if (coins == 0) return new Inn.Let(barkeep, "That's " + held + ". Bring me " + BOTTLE_DEPOSIT + " and that's a coin.", false);
        int paid = Ledger.takeCoins(v.id(), coins);
        if (paid == 0) return new Inn.Let(barkeep, "The till's empty, I'm sorry. Keep them till it isn't.", false);
        int bottles = paid * BOTTLE_DEPOSIT, left = bottles;
        for (ItemStack s : p.getInventory().items) {
            if (left <= 0) break;
            if (!empty.test(s)) continue;
            int k = Math.min(left, s.getCount());
            s.shrink(k);
            left -= k;
        }
        p.getInventory().setChanged();
        Crafts.store(level, v, new ItemStack(Items.GLASS_BOTTLE, bottles));
        Dealings.giveCoins(p, paid);
        book(v.id(), "tavern.bottles", bottles);
        if (barkeep != null) barkeep.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        return new Inn.Let(barkeep, bottles + " bottles back: " + paid + (paid == 1 ? " coin" : " coins") + " for you. The cook'll fill them again."
            + (held > bottles ? " (Keep the other " + (held - bottles) + " till you've three.)" : ""), true);
    }

    /** [roles-c] The tavern's books in a line (/village hospitality, the barkeep's card). */
    static String booksLine(UUID village) {
        return count(village, "tavern.takings") + " coins taken, " + count(village, "tavern.rounds") + " rounds poured, "
            + count(village, "tavern.bottles") + " bottles brought back";
    }

    /** [roles-c] The barkeep's card: behind the bar, and the tavern's books. */
    @Nullable
    static String cardPart(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null || Hospitality.role(f) != Hospitality.Role.BARKEEP || of(village) == null) return null;
        return "keeps the tavern's bar: " + booksLine(village);
    }

    /** For the status line. */
    static Component line(String s) { return Component.literal(s).withStyle(ChatFormatting.GOLD); }
}
