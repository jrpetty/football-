package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [civic-land] Knighthood: a player the town has to thank, knighted at the hall.
 *
 * <p><b>Service.</b> What a player has done for a town is counted, and shown on the Knights page of its books with a
 * bar to a hundred: twelve for each quest done for it, twenty for each campaign fought in its army (eight as a sword
 * for hire), a point or two for each gift its folk were glad of (three a day at most), a point for each day a citizen
 * in good standing (two as an honoured guest or better), two for taxes paid in full; twenty-five off for each
 * offence against its laws, thirty for a muster not answered.
 *
 * <p><b>The knighting.</b> A citizen in good standing (a friend of the town at least, its fines paid) whose service
 * reaches a hundred is chosen by the council to be knighted. Told in chat, it is to come to the hall at dusk; the bell
 * rings, the town gathers before the hall, the leader reads what it has done, bids it kneel, and dubs it Sir, Dame or
 * Knight (the player's choice of address: "Call me Dame", or /village knight style), with cheers and the town's
 * rockets. A player who does not come in three evenings (away, or not on) is knighted in its absence and told when it
 * next comes on. With the title come: the town's banner with a knight's border of gold, out of the stores (handed
 * over by any folk if it was away); a grant of land at the town's edge, where the builders raise its manor as a public
 * work of the town's stores (KnightManors); a retinue of the town's watch (Retinue); and a seat on the council: its
 * vote counts in the council's vote on the taxes and on what is built next ("My vote on the taxes: lower", the Knights
 * page, or /village knight vote).
 *
 * <p><b>Duties.</b> At war, a knight is called to muster with the town's army, and expected to join it (/village war
 * join); a muster not answered costs it service, and two cost it the title. It pays its knight's fee each tax day
 * with the taxes; four weeks unpaid and the title goes. A knight tried and found guilty under the town's laws,
 * banished, or barred by the watch is stripped of it, and so is one the outlaws' reckoning names (strip, the public
 * hook). A town that dies takes its knighthoods with it. Folk greet a knight by its title; its name in chat and in
 * the list of players carries it; its manor stands, its own, whatever comes.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Knights {

    private Knights() {}

    /** How a knight is addressed: chosen by the player, "Knight" until it chooses. */
    public enum Style {
        KNIGHT("Knight"), SIR("Sir"), DAME("Dame");

        public final String word;

        Style(String word) {
            this.word = word;
        }

        @Nullable
        public static Style named(String s) {
            if (s == null) return null;
            try { return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT)); } catch (IllegalArgumentException e) { return null; }
        }
    }

    /** The service a knighthood wants. */
    public static final int SERVICE = 100;
    static final int QUEST = 12, BATTLE = 20, HIRED_BATTLE = 8, GIFT = 1, LOVED_GIFT = 2, GIFTS_A_DAY = 3, DAY = 1, HONOURED_DAY = 2, TAXES = 2,
        CRIME = 25, SHIRK = 30;
    /** Evenings a player chosen for knighthood may miss before it is knighted in its absence. */
    static final int EVENINGS = 3;
    /** How near the hall a player must be for its knighting to begin. */
    static final double NEAR = 24.0;
    /** Weeks a knight's fee may go unpaid. */
    static final int FEE_WEEKS = 4;

    static void resetForTests() {
    }

    // ------------------------------------------------------------------ the record

    static CompoundTag knights(UUID town) {
        return CivicLand.sub(CivicLand.town(town), "knights");
    }

    @Nullable
    static CompoundTag knight(UUID town, UUID player) {
        if (!CivicLand.known(town)) return null;
        CompoundTag all = knights(town);
        return all.contains(player.toString()) ? all.getCompound(player.toString()) : null;
    }

    static CompoundTag serviceTag(UUID town, UUID player) {
        return CivicLand.sub(CivicLand.sub(CivicLand.town(town), "service"), player.toString());
    }

    static CompoundTag elect(UUID town) {
        return CivicLand.sub(CivicLand.town(town), "elect");
    }

    /** Is this player a knight of this town? (The outlaws' and the borders' hook.) */
    public static boolean isKnight(@Nullable UUID town, UUID player) {
        return town != null && knight(town, player) != null;
    }

    /** Is this player a knight of any town? */
    public static boolean isKnightAnywhere(UUID player) {
        return !CivicLand.list(CivicLand.player(player), "knightOf").isEmpty();
    }

    /** The towns this player is a knight of. */
    public static List<UUID> townsOf(UUID player) {
        List<UUID> out = new ArrayList<>();
        for (String s : CivicLand.read(CivicLand.list(CivicLand.player(player), "knightOf"))) {
            UUID u = CivicLand.uuid(s);
            if (u != null) out.add(u);
        }
        return out;
    }

    public static Style style(UUID player) {
        Style s = Style.named(CivicLand.player(player).getString("style"));
        return s == null ? Style.KNIGHT : s;
    }

    /** "Sir Ada": the name with its title, for a knight of any town; the name alone else. */
    public static String styled(UUID player, String name) {
        return isKnightAnywhere(player) ? style(player).word + " " + name : name;
    }

    // ------------------------------------------------------------------ service

    /** The service a player has done a town, all told (nought at the least). */
    public static int service(@Nullable UUID town, UUID player) {
        if (town == null || !CivicLand.known(town)) return 0;
        CompoundTag s = serviceTag(town, player);
        int n = QUEST * s.getInt("q") + BATTLE * s.getInt("b") + HIRED_BATTLE * s.getInt("h") + s.getInt("g") + s.getInt("d")
            + TAXES * s.getInt("t") - CRIME * s.getInt("c") - SHIRK * s.getInt("s");
        return Math.max(0, n);
    }

    static void served(UUID town, UUID player, String name, String key, int n) {
        CompoundTag s = serviceTag(town, player);
        s.putInt(key, s.getInt(key) + n);
        if (name != null && !name.isEmpty()) s.putString("name", name);
        CivicLand.changed();
    }

    /** A quest done for the town (QuestRun.finish). */
    public static void quest(ServerLevel level, @Nullable UUID town, Player p) {
        if (town != null) served(town, p.getUUID(), p.getName().getString(), "q", 1);
    }

    /** A campaign joined in the town's army (Campaigns.join): a volunteer's, or a sword for hire's; a knight's muster answered. */
    public static void battle(ServerLevel level, UUID town, Player p, boolean volunteer) {
        served(town, p.getUUID(), p.getName().getString(), volunteer ? "b" : "h", 1);
        CompoundTag k = knight(town, p.getUUID());
        if (k != null && k.getLong("musterFor") > 0) {
            k.putBoolean("answered", true);
            CivicLand.changed();
        }
    }

    /** A gift a folk was glad of (FolkTalk.gift): a point, two for a loved one, three a day at most in a town. */
    public static void gift(VillageFolkEntity f, Player p, int delta) {
        UUID town = f.ownerId();
        if (town == null || delta < 5) return;
        CompoundTag s = serviceTag(town, p.getUUID());
        long day = f.level().getDayTime() / 24000L;
        if (s.getLong("giftDay") != day + 1) {
            s.putLong("giftDay", day + 1);
            s.putInt("giftsToday", 0);
        }
        if (s.getInt("giftsToday") >= GIFTS_A_DAY) return;
        s.putInt("giftsToday", s.getInt("giftsToday") + 1);
        served(town, p.getUUID(), p.getName().getString(), "g", delta >= 12 ? LOVED_GIFT : GIFT);
    }

    /** Taxes paid in full (TaxBook.pay). */
    static void taxPaid(UUID town, UUID player) {
        served(town, player, "", "t", 1);
    }

    /**
     * An offence against the town's laws (Laws.offence, PlayerLaw.handle): service lost; and for a knight found guilty,
     * banished or barred, the title.
     */
    public static void offence(ServerLevel level, UUID town, Player p, boolean severe, String what) {
        served(town, p.getUUID(), p.getName().getString(), "c", 1);
        if (severe && isKnight(town, p.getUUID())) strip(level, town, p.getUUID(), "for " + what);
        CompoundTag e = elect(town);
        if (e.contains(p.getUUID().toString())) {
            e.remove(p.getUUID().toString());
            CivicLand.changed();
            Villages.tell(town, CivicLand.day(level), "the council thought better of knighting " + p.getName().getString() + ", after " + what);
        }
    }

    // ------------------------------------------------------------------ the round of the town

    /** Every five seconds (CivicLand): the morning's look, the evening's knighting, the musters, the manors. */
    static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long day = CivicLand.day(level), t = CivicLand.time(level);
        CompoundTag tag = CivicLand.town(id);
        if (t >= 1200 && tag.getLong("knightsLooked") != day + 1) {
            tag.putLong("knightsLooked", day + 1);
            CivicLand.changed();
            morning(level, v, day);
        }
        evening(level, v, day, t);
        muster(level, v, day);
        KnightManors.tick(level, v);
    }

    /** The morning: a day's good standing counted for every citizen; whoever has served enough chosen for knighthood. */
    static void morning(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        for (Map.Entry<UUID, String> c : Ledger.citizens(id).entrySet()) {
            UUID p = c.getKey();
            Standing.View view = Standing.of(id, p, level.getGameTime());
            if (!view.title().atLeast(Standing.Title.FRIEND) || Laws.owes(id, p) > 0) continue;
            served(id, p, c.getValue(), "d", view.title().atLeast(Standing.Title.HONOURED) ? HONOURED_DAY : DAY);
        }
        // A banner the stores could not run to at the knighting: tried again each morning.
        for (String k : new ArrayList<>(knights(id).getAllKeys())) {
            UUID p = CivicLand.uuid(k);
            if (p != null && !knights(id).getCompound(k).getBoolean("banner")) banner(level, v, p);
        }
        if (!CivicLand.live()) return;
        CompoundTag all = CivicLand.sub(CivicLand.town(id), "service");
        for (String k : all.getAllKeys()) {
            UUID p = CivicLand.uuid(k);
            if (p == null || isKnight(id, p) || elect(id).contains(k)) continue;
            if (eligible(level, id, p) != null) continue;
            choose(level, v, p, all.getCompound(k).getString("name"), day);
        }
    }

    /** Why this player may not be knighted here yet, or null if it may. */
    @Nullable
    static String eligible(ServerLevel level, UUID town, UUID player) {
        int s = service(town, player);
        if (s < SERVICE) return "your service to the town stands at " + s + " of the " + SERVICE + " a knighthood wants";
        if (!Citizens.is(town, player)) return "only a citizen of the town is knighted by it";
        Standing.View view = Standing.of(town, player, level.getGameTime());
        if (!view.title().atLeast(Standing.Title.FRIEND)) return "the town must count you a friend first";
        if (Laws.owes(town, player) > 0) return "you owe the town a fine";
        if (Laws.banished(town, player, CivicLand.day(level))) return "you are banished";
        CompoundTag former = CivicLand.sub(CivicLand.town(town), "former");
        if (former.contains(player.toString()) && CivicLand.day(level) + 1 - former.getCompound(player.toString()).getLong("day") < 28) {
            return "you were stripped of your knighthood less than four weeks ago";
        }
        return null;
    }

    /** Chosen: the council resolves to knight it, and it is bidden to the hall at dusk. */
    static void choose(ServerLevel level, Villages.Village v, UUID player, String name, long day) {
        UUID id = v.id();
        CompoundTag e = CivicLand.sub(elect(id), player.toString());
        e.putString("name", name);
        e.putLong("since", day + 1);
        e.putInt("missed", 0);
        CivicLand.changed();
        Villages.tell(id, day, "the council resolved to knight " + name + " for their service to the town");
        Market.assemblyNews(id, "The council has resolved to knight " + name + ". The ceremony is at the hall, at dusk, when they come.");
        CivicLand.Spot at = CivicLand.beforeTheHall(level, v);
        CivicLand.tell(level.getServer(), player, Villages.name(id) + " means to knight you for your service! Come to "
            + (Villages.builtStructure(id, "hall") != null ? "the meeting hall" : "the board") + " (" + at.focus().getX() + ", " + at.focus().getZ()
            + ") at dusk. Choose how you are to be called: /village knight style sir|dame|knight.", ChatFormatting.GOLD);
    }

    /** The evening: a chosen player at the hall is knighted; one away three evenings running is knighted in its absence. */
    static void evening(ServerLevel level, Villages.Village v, long day, long t) {
        UUID id = v.id();
        CompoundTag all = elect(id);
        if (all.isEmpty()) return;
        for (String k : new ArrayList<>(all.getAllKeys())) {
            UUID p = CivicLand.uuid(k);
            if (p == null) { all.remove(k); continue; }
            CompoundTag e = all.getCompound(k);
            ServerPlayer sp = level.getServer().getPlayerList().getPlayer(p);
            if (t >= 11000 && t < 13300 && e.getLong("told") != day + 1 && sp != null) {
                e.putLong("told", day + 1);
                CivicLand.Spot at = CivicLand.beforeTheHall(level, v);
                sp.sendSystemMessage(Component.literal("Dusk falls on " + Villages.name(id) + ": the town waits at "
                    + (Villages.builtStructure(id, "hall") != null ? "the meeting hall" : "the board") + " (" + at.focus().getX() + ", "
                    + at.focus().getZ() + ") to knight you.").withStyle(ChatFormatting.GOLD));
            }
            if (t >= 11500 && t < 13300 && sp != null && sp.level() == level && Assemblies.underWay(id) == null
                    && sp.blockPosition().closerThan(CivicLand.beforeTheHall(level, v).focus(), NEAR)) {
                ceremony(level, v, p, e.getString("name"));
                return;
            }
            if (t >= 13300 && e.getLong("missedOn") != day + 1 && e.getLong("began") != day + 1) {   // (not the evening it came)
                e.putLong("missedOn", day + 1);
                e.putInt("missed", e.getInt("missed") + 1);
                CivicLand.changed();
                if (e.getInt("missed") >= EVENINGS) dub(level, v, p, e.getString("name"), true);
            }
        }
    }

    /** The knighting, before the hall: the town gathers, the leader dubs. Whether it began. */
    static boolean ceremony(ServerLevel level, Villages.Village v, UUID player, String name) {
        CivicLand.Spot at = CivicLand.beforeTheHall(level, v);
        Assemblies.Assembly a = new Assemblies.Assembly(v.id(), Assemblies.Kind.KNIGHTING, player + "|" + name, CivicLand.day(level),
            at.focus(), at.audience(), Assemblies.Layout.ARC);
        if (!Assemblies.beginNow(level, a)) return false;
        CompoundTag e = elect(v.id());
        if (e.contains(player.toString())) e.getCompound(player.toString()).putLong("began", CivicLand.day(level) + 1);
        CivicLand.changed();
        return true;
    }

    /** The words (Assemblies.script): what it did, kneel, the dubbing (and with it everything the title brings), the cheers. */
    static void script(ServerLevel level, Assemblies.Assembly a, List<Assemblies.Line> s, RandomSource r) {
        String[] q = a.subject.split("\\|", 2);
        UUID player = CivicLand.uuid(q[0]);
        if (player == null) return;
        String name = q.length > 1 ? q[1] : "our friend";
        UUID id = a.village;
        Villages.Village v = Villages.get(id);
        if (v == null) return;
        String town = Villages.name(id);
        CompoundTag sv = serviceTag(id, player);
        List<String> deeds = new ArrayList<>();
        if (sv.getInt("q") > 0) deeds.add(sv.getInt("q") + (sv.getInt("q") == 1 ? " quest" : " quests") + " done for us");
        if (sv.getInt("b") + sv.getInt("h") > 0) deeds.add((sv.getInt("b") + sv.getInt("h") == 1 ? "a campaign" : sv.getInt("b") + sv.getInt("h") + " campaigns")
            + " in our army");
        if (sv.getInt("g") > 0) deeds.add("gifts to many of us");
        if (sv.getInt("d") > 0) deeds.add(sv.getInt("d") + " days a good citizen");
        s.add(new Assemblies.Line(null, "We are here for " + name + ", who has served " + town + " well: " + (deeds.isEmpty() ? "in more ways than I can say"
            : CivicLand.names(deeds)) + ".", '?', null));
        s.add(new Assemblies.Line(null, name + ", kneel before the town.", '~', () -> {
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(player);
            if (p != null) p.displayClientMessage(Component.literal("Kneel (sneak) before " + town + "'s " + Government.title(id) + ".")
                .withStyle(ChatFormatting.GOLD), true);
        }));
        s.add(new Assemblies.Line(null, "By the will of the council and the people of " + town + ", I dub thee —", '~', () ->
            level.playSound(null, a.focus, SoundEvents.ANVIL_LAND, SoundSource.NEUTRAL, 0.5F, 1.6F)));
        s.add(new Assemblies.Line(null, style(player).word + " " + name + ", Knight of " + town + "!", '!', () -> {
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(player);
            boolean knelt = p != null && p.isShiftKeyDown();
            dub(level, v, player, name, false);
            if (p != null) {
                level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, p.getX(), p.getY() + 1.0, p.getZ(), 40, 0.5, 0.8, 0.5, 0.3);
                level.playSound(null, p.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0F, 0.8F);
                if (knelt) for (AssistantEntity x : Villages.folkOf(id)) {
                    if (x instanceof VillageFolkEntity f) f.persona().feelFor(player, name, 2);
                }
            }
        }));
        s.add(new Assemblies.Line(null, "Rise. The land at the town's edge is yours, and our builders will raise your manor on it.", '!', null));
        if (Retinue.spare(id) > 0) s.add(new Assemblies.Line(null, "Men of our watch will ride at your side when you call them.", '!', null));
        s.add(new Assemblies.Line(null, "Three cheers for " + style(player).word + " " + name + "! Hip hip —", '!', null));
    }

    /** It is over (Assemblies.close): those who were there remember it. */
    static void closed(ServerLevel level, Assemblies.Assembly a) {
        String[] q = a.subject.split("\\|", 2);
        String name = q.length > 1 ? q[1] : "a friend of the town";
        long day = CivicLand.day(level);
        for (UUID u : a.seated.keySet()) {
            VillageFolkEntity f = CivicLand.find(level, u);
            if (f != null) f.persona().remember(day, "I saw " + name + " knighted before the hall", 4);
        }
    }

    /**
     * Knighted: the title, the land, the manor wanted, the retinue named, the banner made, the seat on the council; into
     * the chronicle and the morning's news. What is said of it.
     */
    public static String dub(ServerLevel level, Villages.Village v, UUID player, String name, boolean absent) {
        UUID id = v.id();
        if (isKnight(id, player)) return name + " is a knight of " + Villages.name(id) + " already.";
        long day = CivicLand.day(level);
        CompoundTag k = CivicLand.sub(knights(id), player.toString());
        k.putString("name", name);
        k.putLong("since", day + 1);
        k.putBoolean("absent", absent);
        elect(id).remove(player.toString());
        ListTag of = CivicLand.list(CivicLand.player(player), "knightOf");
        if (!CivicLand.read(of).contains(id.toString())) of.add(StringTag.valueOf(id.toString()));
        CivicLand.sub(CivicLand.town(id), "former").remove(player.toString());
        CivicLand.changed();
        String title = style(player).word + " " + name;
        Villages.tell(id, day, title + " was knighted " + (absent ? "in their absence" : "before the hall") + " for their service to the town");
        Market.assemblyNews(id, "Hail " + title + ", Knight of " + Villages.name(id) + "!");
        for (AssistantEntity x : Villages.folkOf(id)) {
            if (x instanceof VillageFolkEntity f) f.persona().feelFor(player, name, 4);
        }
        Standing.stir(id, player);
        String land = KnightManors.grant(level, v, player);
        List<String> men = Retinue.assign(level, v, player);
        boolean banner = banner(level, v, player);
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(player);
        if (p != null) refreshName(p);
        String words = "You are " + title + ", Knight of " + Villages.name(id) + "! " + land
            + (men.isEmpty() ? " The watch can spare no men for your retinue just yet." : " Your retinue: " + CivicLand.names(men) + " (say \"Retinue, to me!\").")
            + (banner ? " Your banner is woven." : " Your banner will be woven when the stores run to it.")
            + " You have a vote on the council: /village knight vote lower|keep|raise.";
        CivicLand.tell(level.getServer(), player, words, ChatFormatting.GOLD);
        return title + " knighted in " + Villages.name(id) + (absent ? " (in their absence)" : "") + ".";
    }

    /**
     * Stripped of the title (crimes, treachery, a muster shirked, the fee unpaid; the outlaws' reckoning calls it too):
     * the retinue sent home, the vote gone, the manor forfeit to the town, into the chronicle. What is said.
     */
    public static String strip(ServerLevel level, UUID town, UUID player, String why) {
        CompoundTag k = knight(town, player);
        if (k == null) return "They are no knight of " + Villages.name(town) + ".";
        String name = k.getString("name");
        String title = style(player).word + " " + name;
        long day = CivicLand.day(level);
        Retinue.release(level, town, player, "stripped");
        KnightManors.forfeit(level, town, player);
        knights(town).remove(player.toString());
        ListTag of = CivicLand.list(CivicLand.player(player), "knightOf");
        for (int i = of.size() - 1; i >= 0; i--) if (of.getString(i).equals(town.toString())) of.remove(i);
        CompoundTag f = CivicLand.sub(CivicLand.sub(CivicLand.town(town), "former"), player.toString());
        f.putString("name", name);
        f.putString("why", why);
        f.putLong("day", day + 1);
        CivicLand.sub(CivicLand.town(town), "pref").remove(player.toString());
        CivicLand.changed();
        Villages.tell(town, day, title + " was stripped of their knighthood " + why);
        Market.assemblyNews(town, name + " is a knight of this town no longer: stripped " + why + ".");
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(player);
        if (p != null) refreshName(p);
        CivicLand.tell(level.getServer(), player, Villages.name(town) + " has stripped you of your knighthood " + why + ".", ChatFormatting.RED);
        return name + " stripped of their knighthood of " + Villages.name(town) + ".";
    }

    /** The town is gone (CivicLand.townGone): its knighthoods lapse, kept in each one's own record as of a fallen town. */
    static void townGone(UUID town) {
        if (!CivicLand.known(town)) return;
        String name = Villages.name(town);
        for (String k : new ArrayList<>(knights(town).getAllKeys())) {
            UUID p = CivicLand.uuid(k);
            if (p == null) continue;
            ListTag of = CivicLand.list(CivicLand.player(p), "knightOf");
            for (int i = of.size() - 1; i >= 0; i--) if (of.getString(i).equals(town.toString())) of.remove(i);
            CivicLand.list(CivicLand.player(p), "fallen").add(StringTag.valueOf("Knight of " + name + ", a town that is no more"));
            Retinue.forget(p);
            net.minecraft.server.MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                ServerPlayer sp = server.getPlayerList().getPlayer(p);
                if (sp != null) refreshName(sp);
                CivicLand.tell(server, p, name + " is no more: your knighthood of it is a memory now. Your manor stands, yours still.", ChatFormatting.GRAY);
            }
        }
        CivicLand.changed();
    }

    /**
     * The knight's banner: the town's arms (a plain banner of the field, for a town with none drawn yet) within a gold
     * border, its own mark, woven of the stores' banner and dyes (Heraldry's way: the tailor's, at its bench). Into the
     * knight's hands if it is near; else kept for the next folk it talks to. Whether it was made.
     */
    static boolean banner(ServerLevel level, Villages.Village v, UUID player) {
        CompoundTag k = knight(v.id(), player);
        if (k == null || k.getBoolean("banner")) return k != null;
        ItemStack b = make(level, v, player, k.getString("name"), true);
        if (b.isEmpty()) return false;
        k.putBoolean("banner", true);
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(player);
        if (p != null && p.level() == level && p.blockPosition().closerThan(v.centre(), 96)) {
            if (!p.getInventory().add(b)) p.drop(b, false);
        } else {
            k.put("keep", b.save(level.registryAccess()));
        }
        CivicLand.changed();
        return true;
    }

    /** The knight's arms as a banner, made out of the stores (or only drawn, with {@code take} false). Empty if the stores fall short. */
    static ItemStack make(ServerLevel level, Villages.Village v, UUID player, String name, boolean take) {
        UUID id = v.id();
        Heraldry.Design town = Heraldry.design(id);
        DyeColor field = town == null ? DyeColor.WHITE : town.field();
        List<Heraldry.Layer> layers = new ArrayList<>(town == null ? List.of() : town.layers());
        boolean bordered = false;
        for (Heraldry.Layer l : layers) if (l.charge() == Heraldry.Charge.BORDER || l.charge() == Heraldry.Charge.SCALLOPED) bordered = true;
        layers.add(new Heraldry.Layer(bordered ? Heraldry.Charge.CHIEF : Heraldry.Charge.BORDER, Heraldry.contrast(DyeColor.YELLOW, field)));
        Heraldry.Design mine = new Heraldry.Design(field, layers);
        if (take) {
            Bench.Plan plan = Heraldry.plan(level, v, mine);
            if (!plan.ok() || !Bench.take(level, v, plan, Heraldry.tailor(id))) return ItemStack.EMPTY;
        }
        ItemStack s = Heraldry.item(level, id, mine);
        s.set(DataComponents.ITEM_NAME, Component.literal("Banner of " + style(player).word + " " + name + " of " + Villages.name(id)));
        return s;
    }

    /** At the start of a talk (FolkTalk): the knight's banner the town has been keeping for it. Empty if nothing. */
    public static String handOver(VillageFolkEntity f, Player p) {
        UUID id = f.ownerId();
        if (id == null) return "";
        CompoundTag k = knight(id, p.getUUID());
        if (k == null || !k.contains("keep") || !(f.level() instanceof ServerLevel level)) return "";
        ItemStack b = ItemStack.parseOptional(level.registryAccess(), k.getCompound("keep"));
        k.remove("keep");
        CivicLand.changed();
        if (b.isEmpty()) return "";
        if (!p.getInventory().add(b)) p.drop(b, false);
        return "Here — your banner, " + style(p.getUUID()).word + " " + p.getName().getString() + ". The tailor wove it while you were away.";
    }

    // ------------------------------------------------------------------ duties

    /** At war, with the town's army in the field: each knight called to the muster, once; one that never came, answerable for it. */
    static void muster(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        CompoundTag all = knights(id);
        if (all.isEmpty()) return;
        Campaigns.Campaign c = Campaigns.ofTown(id);
        for (String k : new ArrayList<>(all.getAllKeys())) {
            CompoundTag kn = all.getCompound(k);
            UUID p = CivicLand.uuid(k);
            if (p == null) continue;
            if (c != null && kn.getLong("musterFor") == 0) {
                UUID foe = c.attacker().equals(id) ? c.defender() : c.attacker();
                kn.putLong("musterFor", day + 1);
                kn.putBoolean("answered", false);
                CivicLand.changed();
                CivicLand.tell(level.getServer(), p, Villages.name(id) + " calls its knights to muster: its army takes the field against "
                    + Villages.name(foe) + ". Join it: /village war join (in the town).", ChatFormatting.RED);
                Villages.tell(id, day, style(p).word + " " + kn.getString("name") + " was called to the muster");
            } else if (c == null && kn.getLong("musterFor") > 0) {
                boolean came = kn.getBoolean("answered");
                kn.remove("musterFor");
                kn.remove("answered");
                CivicLand.changed();
                if (came) continue;
                served(id, p, kn.getString("name"), "s", 1);
                kn.putInt("shirked", kn.getInt("shirked") + 1);
                Villages.tell(id, day, style(p).word + " " + kn.getString("name") + " did not answer the town's muster");
                if (kn.getInt("shirked") >= 2) strip(level, id, p, "for failing the muster twice");
                else CivicLand.tell(level.getServer(), p, "You did not answer " + Villages.name(id) + "'s muster. Fail it again and you will lose your knighthood.",
                    ChatFormatting.RED);
            }
        }
    }

    /** The fee unpaid (TaxBook.players, each tax day): four weeks behind, and the title goes. */
    static void feeArrears(ServerLevel level, UUID town, UUID player, int weeks, int fee) {
        if (weeks >= FEE_WEEKS && fee > 0 && isKnight(town, player)) strip(level, town, player, "for leaving the knight's fee unpaid");
    }

    // ------------------------------------------------------------------ the seat on the council

    /** A player's say in the council's vote on the taxes: its name, its choice (-1 lower, 0 none, +1 raise), its weight. */
    record Say(UUID player, String name, int choice, int weight) {}

    /** Everybody with a player's say on this town's council: its knights, a player given a seat, a player who leads. */
    static List<Say> says(UUID town) {
        List<Say> out = new ArrayList<>();
        CompoundTag pref = CivicLand.sub(CivicLand.town(town), "pref");
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        for (String k : knights(town).getAllKeys()) {
            UUID p = CivicLand.uuid(k);
            if (p == null) continue;
            seen.add(p);
            out.add(new Say(p, style(p).word + " " + knights(town).getCompound(k).getString("name"), pref.getInt(k), 1));
        }
        String seat = Ledger.note(town, "civic.seat");
        if (seat != null && !seat.isEmpty()) {
            String[] q = seat.split("~");
            UUID p = q.length >= 2 ? CivicLand.uuid(q[0]) : null;
            if (p != null && seen.add(p)) out.add(new Say(p, q[1], pref.getInt(p.toString()), 1));
        }
        UUID leader = PlayerLeader.leaderId(town);
        if (leader != null && seen.add(leader)) {
            String name = PlayerLeader.leaderName(town);
            out.add(new Say(leader, name.isEmpty() ? "the leader" : name, pref.getInt(leader.toString()),
                Government.of(town) == Government.Form.LORD ? 3 : 2));
        } else if (leader != null) {
            for (int i = 0; i < out.size(); i++) if (out.get(i).player().equals(leader)) out.set(i, new Say(leader, out.get(i).name(), out.get(i).choice(), 2));
        }
        return out;
    }

    /** Has this player a say on the council (a knight, a seated player, the player who leads)? */
    static boolean hasSay(UUID town, UUID player) {
        for (Say s : says(town)) if (s.player().equals(player)) return true;
        return false;
    }

    /** A player with a say casts it on the taxes: lower, keep, raise. What is said. */
    public static String vote(UUID town, UUID player, int choice) {
        if (!hasSay(town, player)) return "You have no seat on " + Villages.name(town) + "'s council. A knight of the town has one.";
        CivicLand.sub(CivicLand.town(town), "pref").putInt(player.toString(), Integer.signum(choice));
        CivicLand.changed();
        long day = 0;
        net.minecraft.server.MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server != null) day = server.overworld().getDayTime() / 24000L;
        String when = "the council sits on the taxes on day " + TaxBook.nextSitting(day);
        return switch (Integer.signum(choice)) {
            case -1 -> "Your vote is down for lowering the taxes: " + when + ".";
            case 1 -> "Your vote is down for raising the taxes: " + when + ".";
            default -> "Your vote is down to leave the taxes be: you'll vote against any change, " + when + ".";
        };
    }

    /** The council's vote on what is built next (Council.vote): a knight's proposal carries a councillor's vote. */
    public static void councilVotes(UUID village, Map<UUID, String> proposals, Map<String, Integer> tally) {
        for (Map.Entry<UUID, String> p : proposals.entrySet()) {
            if (isKnight(village, p.getKey()) && tally.containsKey(p.getValue())) tally.merge(p.getValue(), 1, Integer::sum);
        }
    }

    // ------------------------------------------------------------------ what folk say, and what a player says

    /** A wave for a knight (Greetings.hello): its title, and a bow; null for anybody else. */
    @Nullable
    public static String hello(VillageFolkEntity f, Player p, RandomSource r) {
        UUID id = f.ownerId();
        if (id == null || f.isBaby() && !isKnight(id, p.getUUID())) return null;
        String title = style(p.getUUID()).word + " " + p.getName().getString();
        if (isKnight(id, p.getUUID())) {
            f.setXRot(20.0F);                                                // a nod of the head, a little bow
            if (f.isBaby()) return FolkTalk.pick(r, "It's " + title + "! Are you going to fight a dragon?", title + "! Can I hold your sword?");
            if (TaxUnrest.revolt(id) && TaxUnrest.aggrieved(id, f)) return FolkTalk.pick(r, title + ". Tell the council what we're saying, will you?",
                "Good day, " + title + ". You sit on the council — do something about these taxes.");
            return FolkTalk.pick(r, "Good day, " + title + "!", "Your servant, " + title + ".", title + "! An honour.", "God keep you, " + title + ".");
        }
        if (isKnightAnywhere(p.getUUID())) return FolkTalk.pick(r, "Welcome, " + title + ".", "Good day to you, " + title + ".");
        return null;
    }

    /** About the knighthood, said to any folk: its answer, or null when the words are about something else. */
    @Nullable
    static String talk(VillageFolkEntity f, Player p, String low) {
        UUID id = f.ownerId();
        if (id == null || !(f.level() instanceof ServerLevel level)) return null;
        if (low.startsWith("call me ")) {
            Style s = low.contains("dame") ? Style.DAME : low.contains("sir") ? Style.SIR : low.contains("knight") ? Style.KNIGHT : null;
            if (s == null) return null;
            return setStyle(p, s);
        }
        if (low.contains("retinue")) {
            if (!(p instanceof ServerPlayer sp)) return null;
            if (low.contains("stand down") || low.contains("dismiss") || low.contains("go home")) return Retinue.dismiss(level, id, sp);
            if (low.contains("release")) return Retinue.release(level, id, p.getUUID(), "released");
            if (low.contains("more") || low.contains("hire")) return Retinue.hire(level, id, sp);
            return Retinue.call(level, id, sp);
        }
        if (low.contains("vote") && low.contains("tax")) {
            int choice = low.contains("lower") || low.contains("down") ? -1 : low.contains("raise") || low.contains("up") || low.contains("higher") ? 1 : 0;
            return vote(id, p.getUUID(), choice);
        }
        if (low.contains("grander manor") || low.contains("grand manor") || low.contains("bigger manor")) return KnightManors.grand(level, id, p);
        if (low.contains("my manor") || low.contains("my land")) return KnightManors.where(level, id, p.getUUID());
        if (low.contains("knight") || low.contains("my service")) return status(level, id, p);
        return null;
    }

    /** "Call me Dame": how the player is addressed as a knight, everywhere. */
    public static String setStyle(Player p, Style s) {
        CivicLand.player(p.getUUID()).putString("style", s.name());
        CivicLand.changed();
        if (p instanceof ServerPlayer sp) refreshName(sp);
        return isKnightAnywhere(p.getUUID()) ? "As you wish, " + s.word + " " + p.getName().getString() + "."
            : "We'll remember: " + s.word + " " + p.getName().getString() + ", when the day comes.";
    }

    /** "What's my standing in the town's service?": the service, what it is short of, and what a knight has. */
    static String status(ServerLevel level, UUID town, Player p) {
        String name = Villages.name(town);
        CompoundTag k = knight(town, p.getUUID());
        int s = service(town, p.getUUID());
        if (k != null) {
            String manor = KnightManors.where(level, town, p.getUUID());
            return "You're " + style(p.getUUID()).word + " " + p.getName().getString() + ", Knight of " + name + ", since day " + (k.getLong("since") - 1) + ". "
                + manor + " " + Retinue.line(level, town, p.getUUID()) + " Your service: " + s + ".";
        }
        if (elect(town).contains(p.getUUID().toString())) return "The council means to knight you! Come to the hall at dusk.";
        String why = eligible(level, town, p.getUUID());
        return why == null ? "You've served " + name + " well: the council will take it up in the morning."
            : "Your service to " + name + " stands at " + s + " of " + SERVICE + ". " + CivicLand.cap(why) + ". Quests, its battles, gifts and good years as a citizen all count.";
    }

    // ------------------------------------------------------------------ the name

    static void refreshName(ServerPlayer p) {
        p.refreshDisplayName();
        p.refreshTabListName();
    }

    @SubscribeEvent
    public static void onNameFormat(PlayerEvent.NameFormat e) {
        if (e.getEntity().level().isClientSide || !isKnightAnywhere(e.getEntity().getUUID())) return;
        e.setDisplayname(Component.literal(style(e.getEntity().getUUID()).word + " ").withStyle(ChatFormatting.GOLD).append(e.getDisplayname()));
    }

    @SubscribeEvent
    public static void onTabListName(PlayerEvent.TabListNameFormat e) {
        if (e.getEntity().level().isClientSide || !isKnightAnywhere(e.getEntity().getUUID())) return;
        e.setDisplayName(Component.literal(style(e.getEntity().getUUID()).word + " ").withStyle(ChatFormatting.GOLD)
            .append(e.getEntity().getName()));
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        com.jrpetty.mcassistant.Guard.run("a knight's welcome", () -> {
            CivicLand.deliver(p);
            if (isKnightAnywhere(p.getUUID())) refreshName(p);
        });
    }

    // ------------------------------------------------------------------ the books' page

    /** The town's knights, the one chosen, the ones stripped: the Knights page (the town's side). */
    static CompoundTag report(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        CompoundTag out = new CompoundTag();
        ListTag list = new ListTag();
        CompoundTag pref = CivicLand.sub(CivicLand.town(id), "pref");
        for (String k : knights(id).getAllKeys()) {
            UUID p = CivicLand.uuid(k);
            if (p == null) continue;
            CompoundTag kn = knights(id).getCompound(k);
            CompoundTag r = new CompoundTag();
            r.putString("name", style(p).word + " " + kn.getString("name"));
            r.putLong("since", kn.getLong("since") - 1);
            r.putString("manor", KnightManors.state(id, p));
            r.putInt("retinue", Retinue.size(id, p));
            int c = pref.getInt(k);
            r.putString("vote", c < 0 ? "lower" : c > 0 ? "raise" : "keep");
            r.putInt("service", service(id, p));
            list.add(r);
        }
        out.put("knights", list);
        List<String> chosen = new ArrayList<>();
        for (String k : elect(id).getAllKeys()) chosen.add(elect(id).getCompound(k).getString("name"));
        out.put("chosen", CivicLand.strings(chosen));
        List<String> former = new ArrayList<>();
        CompoundTag f = CivicLand.sub(CivicLand.town(id), "former");
        for (String k : f.getAllKeys()) former.add(f.getCompound(k).getString("name") + ", stripped " + f.getCompound(k).getString("why"));
        out.put("former", CivicLand.strings(former));
        out.putInt("spare", Retinue.spare(id));
        return out;
    }

    /** The viewer's own: its service and what it is made of, its knighthood, its retinue, its vote, what it owes. */
    static CompoundTag yours(ServerLevel level, Villages.Village v, ServerPlayer p) {
        UUID id = v.id();
        CompoundTag out = new CompoundTag();
        CompoundTag s = CivicLand.known(id) ? serviceTag(id, p.getUUID()) : new CompoundTag();
        out.putInt("service", service(id, p.getUUID()));
        out.putInt("needed", SERVICE);
        out.putInt("quests", s.getInt("q"));
        out.putInt("battles", s.getInt("b") + s.getInt("h"));
        out.putInt("gifts", s.getInt("g"));
        out.putInt("days", s.getInt("d"));
        out.putInt("taxes", s.getInt("t"));
        out.putInt("crimes", s.getInt("c"));
        out.putInt("shirks", s.getInt("s"));
        out.putString("style", style(p.getUUID()).word);
        CompoundTag k = knight(id, p.getUUID());
        out.putBoolean("knight", k != null);
        out.putBoolean("chosen", CivicLand.known(id) && elect(id).contains(p.getUUID().toString()));
        String why = eligible(level, id, p.getUUID());
        out.putString("why", why == null ? "" : why);
        if (k != null) {
            out.putLong("since", k.getLong("since") - 1);
            out.putString("manor", KnightManors.where(level, id, p.getUUID()));
            out.put("retinue", CivicLand.strings(Retinue.lines(level, id, p.getUUID())));
            int c = CivicLand.sub(CivicLand.town(id), "pref").getInt(p.getUUID().toString());
            out.putString("vote", c < 0 ? "lower" : c > 0 ? "raise" : "keep");
            out.putBoolean("muster", k.getLong("musterFor") > 0 && !k.getBoolean("answered"));
        }
        out.putBoolean("say", CivicLand.known(id) && hasSay(id, p.getUUID()));
        out.putInt("owed", TaxBook.owedBy(id, p.getUUID()));
        out.putBoolean("citizen", Citizens.is(id, p.getUUID()));
        List<String> fallen = CivicLand.read(CivicLand.list(CivicLand.player(p.getUUID()), "fallen"));
        out.put("fallen", CivicLand.strings(fallen));
        return out;
    }

    // ------------------------------------------------------------------ an operator's hand, and the tests

    /**
     * Knighted now (an operator): at the hall, with the town gathered, if the player is by it and nothing else is under
     * way; else at once. A player not yet a citizen is made one first. What is said.
     */
    public static String dubNow(ServerLevel level, Villages.Village v, ServerPlayer p) {
        UUID id = v.id();
        String name = p.getName().getString();
        if (isKnight(id, p.getUUID())) return name + " is a knight of " + Villages.name(id) + " already.";
        if (!Citizens.is(id, p.getUUID())) Ledger.addCitizen(id, p.getUUID(), name);
        CompoundTag e = CivicLand.sub(elect(id), p.getUUID().toString());
        e.putString("name", name);
        e.putLong("since", CivicLand.day(level) + 1);
        CivicLand.changed();
        if (p.level() == level && p.blockPosition().closerThan(CivicLand.beforeTheHall(level, v).focus(), NEAR) && ceremony(level, v, p.getUUID(), name)) {
            return "The town gathers before the hall to knight " + name + ".";
        }
        return dub(level, v, p.getUUID(), name, false);
    }

    /** Tests: service of this kind added outright ("q", "b", "h", "g", "d", "t", "c", "s"). */
    public static void serveForTests(UUID town, UUID player, String name, String key, int n) {
        served(town, player, name, key, n);
    }

    /** Tests: the morning's look now (good standing counted, the deserving chosen). */
    public static void morningForTests(ServerLevel level, Villages.Village v) {
        morning(level, v, CivicLand.day(level));
    }

    /** Tests: is this player chosen and waiting to be knighted? */
    public static boolean chosenForTests(UUID town, UUID player) {
        return CivicLand.known(town) && elect(town).contains(player.toString());
    }

    /** Tests: the knighting begun now (the player must be by the hall). */
    public static boolean ceremonyForTests(ServerLevel level, Villages.Village v, UUID player, String name) {
        return ceremony(level, v, player, name);
    }

    /** Tests: the evening's look at this hour. */
    public static void eveningForTests(ServerLevel level, Villages.Village v) {
        evening(level, v, CivicLand.day(level), CivicLand.time(level));
    }

    /** Tests: the muster's look now. */
    public static void musterForTests(ServerLevel level, Villages.Village v) {
        muster(level, v, CivicLand.day(level));
    }

    /** Tests: whether the knight's banner is kept for it, waiting to be handed over. */
    public static boolean bannerKeptForTests(UUID town, UUID player) {
        CompoundTag k = knight(town, player);
        return k != null && k.contains("keep");
    }
}
