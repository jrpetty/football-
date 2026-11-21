package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.AssistantConfig;
import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.net.FoundEasyPayload;
import com.jrpetty.mcassistant.village.FoundingPlan;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.ThemeData;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * [found23] Starting a town, made easy, start to finish and with no commands:
 * <ul>
 *   <li>a new player is given a Founding Kit once, and a player out in the wild with no town of their own is told now
 *       and then that they can start one (the U key);</li>
 *   <li>one founding screen, opened from the U page, a folk ("Start a village of my own"), a village board's header, the
 *       kit, a spawner or a charter right-clicked in the air, or a spawner's waiting board: where (here, or the best three
 *       spots near, found by SiteFinder, on a map, outlined on the ground), how big (a hamlet, a village, a town, a city,
 *       or any number), its name and look, and "Take me there";</li>
 *   <li>then the welcome card: what happens next, and buttons for the next steps.</li>
 * </ul>
 * Who may found, how many towns each, the kit and the travel are the server's to say (AssistantConfig, founding*).
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class FoundingEasy {

    private static final Logger LOG = LogUtils.getLogger();

    private FoundingEasy() {}

    /** The sizes offered at a click, with their plain names. */
    public static final int[] PRESETS = { 8, 25, 70, 250 };
    public static final String[] PRESET_NAMES = { "Hamlet", "Village", "Town", "City" };

    private static final String KIT_GIVEN = "mc_assistant_founding_kit_given";

    // ------------------------------------------------------------------ the server's rules (tests may override)

    @Nullable private static String whoForTests;
    @Nullable private static Integer limitForTests;
    @Nullable private static Boolean needsItemForTests;

    public static void rulesForTests(@Nullable String who, @Nullable Integer limit, @Nullable Boolean needsItem) {
        whoForTests = who;
        limitForTests = limit;
        needsItemForTests = needsItem;
    }

    static String who() {
        return whoForTests != null ? whoForTests : AssistantConfig.foundingWho();
    }

    static int limit() {
        return limitForTests != null ? limitForTests : AssistantConfig.foundingTownsPerPlayer();
    }

    static boolean needsItem() {
        return needsItemForTests != null ? needsItemForTests : AssistantConfig.foundingNeedsItem();
    }

    /** An operator, or the owner of a single-player world. */
    static boolean operator(ServerPlayer p) {
        return p.hasPermissions(2) || p.server.isSingleplayerOwner(p.getGameProfile());
    }

    /** Why this player may not found a town, or null if they may. */
    @Nullable
    public static String mayFound(ServerPlayer p) {
        if (operator(p)) return null;
        switch (who()) {
            case "operators" -> { return "On this server only operators may found towns."; }
            case "citizens" -> {
                boolean citizen = false;
                for (Villages.Village v : Villages.every()) if (Citizens.is(v.id(), p.getUUID())) { citizen = true; break; }
                if (!citizen) return "On this server only a town's citizens may found one. Become a citizen first: ask any folk \"May I live here?\"";
            }
            default -> { }
        }
        int most = limit();
        if (most > 0) {
            int mine = SiteFinder.townsOf(p.serverLevel(), p.getUUID());
            if (mine >= most) return "You have founded " + mine + (mine == 1 ? " town" : " towns") + ", the most this server lets one player found.";
        }
        return null;
    }

    // ------------------------------------------------------------------ what it costs

    static boolean isToken(ItemStack s) {
        return s.is(McAssistantMod.FOUNDING_KIT.get()) || s.is(McAssistantMod.FOLK_SPAWNER_ITEM.get()) || s.is(McAssistantMod.VILLAGE_CHARTER.get());
    }

    /** What founding from the screen will use up: a kit first, then a spawner, then a charter. Empty if nothing is needed or nothing is carried. */
    static ItemStack token(ServerPlayer p) {
        var inv = p.getInventory();
        for (var it : new net.minecraft.world.item.Item[]{ McAssistantMod.FOUNDING_KIT.get(), McAssistantMod.FOLK_SPAWNER_ITEM.get(), McAssistantMod.VILLAGE_CHARTER.get() }) {
            for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(it)) return inv.getItem(i);
        }
        return ItemStack.EMPTY;
    }

    static boolean free(ServerPlayer p) {
        return !needsItem() || p.getAbilities().instabuild;
    }

    /** What the screen says is missing, or empty. */
    static String need(ServerPlayer p) {
        if (free(p) || !token(p).isEmpty()) return "";
        return "You need a Founding Kit (2 bread, seeds, planks and a gold nugget) or a Village Folk Spawner (4 bread round a gold nugget).";
    }

    // ------------------------------------------------------------------ the screen

    /** Open the founding screen for a player, to found near where they stand. {@code from} says where it was opened from. */
    public static void open(ServerPlayer p, String from) {
        ServerLevel level = p.serverLevel();
        CompoundTag t = base(p, from);
        t.putString("mode", "site");
        SiteFinder.Result r = SiteFinder.find(level, p.blockPosition(), AssistantConfig.foundingSiteReach());
        write(t, r);
        t.putBoolean("travel", AssistantConfig.foundingTravel());
        t.putInt("travelReach", AssistantConfig.foundingTravelReach());
        send(p, FoundEasyPayload.OPEN, t);
        LOG.info("[MCA-FOUND] {} opened the founding screen ({}): {} spots, here {}", p.getGameProfile().getName(), from, r.picks().size(),
            r.picks().get(0).no().isEmpty() ? r.picks().get(0).score() + " " + r.picks().get(0).words() : r.picks().get(0).no());
    }

    /** A spawner's waiting board right-clicked: the same screen, its spot fixed at the board's. True when it was sent. */
    public static boolean offerBoard(ServerPlayer p, BlockPos board, int start) {
        ServerLevel level = p.serverLevel();
        Founding.Site s = Founding.siteNear(level, board, 64);
        if (s == null || !s.board.equals(board)) return false;
        CompoundTag t = base(p, "board");
        t.putString("mode", "board");
        t.putLong("board", board.asLong());
        t.putInt("start", start);
        t.putString("need", "");                                             // the spawner was spent setting the board up
        write(t, SiteFinder.find(level, s.heart, 0, true));
        send(p, FoundEasyPayload.OPEN, t);
        return true;
    }

    private static CompoundTag base(ServerPlayer p, String from) {
        CompoundTag t = new CompoundTag();
        t.putString("from", from);
        String no = mayFound(p);
        t.putString("no", no == null ? "" : no);
        t.putString("need", need(p));
        t.putInt("start", Math.min(Founding.most(), 25));
        t.putInt("most", Founding.most());
        t.putInt("worldFolk", Villages.worldHeadcount());
        t.putInt("worldCap", AssistantConfig.villageWorldCap());
        t.putString("name", suggestName());
        ListTag names = new ListTag();
        for (int i = 0; i < 8; i++) names.add(net.minecraft.nbt.StringTag.valueOf(suggestName()));
        t.put("names", names);
        ListTag themes = new ListTag();
        for (ThemeData d : Themes.offered()) {
            CompoundTag th = new CompoundTag();
            th.putString("id", d.id);
            th.putString("name", d.name());
            th.putString("about", d.about());
            themes.add(th);
        }
        t.put("themes", themes);
        t.putString("theme", Themes.defaultId());
        t.putIntArray("player", new int[]{ p.getBlockX(), p.getBlockY(), p.getBlockZ() });
        return t;
    }

    private static void write(CompoundTag t, SiteFinder.Result r) {
        ListTag picks = new ListTag();
        for (SiteFinder.Pick k : r.picks()) {
            CompoundTag c = new CompoundTag();
            c.putIntArray("at", new int[]{ k.at().getX(), k.at().getY(), k.at().getZ() });
            c.putInt("score", k.score());
            c.putString("words", k.words());
            c.putString("no", k.no());
            c.putBoolean("here", k.here());
            c.putInt("dist", k.distance());
            c.putString("bearing", k.bearing());
            picks.add(c);
        }
        t.put("picks", picks);
        CompoundTag m = new CompoundTag();
        m.putInt("x0", r.x0());
        m.putInt("z0", r.z0());
        m.putInt("size", r.size());
        m.putInt("step", SiteFinder.STEP);
        m.putByteArray("kinds", r.kinds());
        m.putByteArray("heights", r.heights());
        ListTag towns = new ListTag();
        for (SiteFinder.Mark k : r.towns()) {
            CompoundTag c = new CompoundTag();
            c.putInt("x", k.x());
            c.putInt("z", k.z());
            c.putString("name", k.name());
            towns.add(c);
        }
        m.put("towns", towns);
        t.put("map", m);
        t.putInt("bare", FoundingPlan.BARE);
        t.putInt("plaza", com.jrpetty.mcassistant.village.TownPlan.PLAZA);
    }

    /** A fresh name for a town, not one already in the world. */
    static String suggestName() {
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (Villages.Village v : Villages.every()) taken.add(Villages.name(v.id()));
        String n = Villages.name(UUID.randomUUID());
        for (int i = 0; i < 8 && taken.contains(n); i++) n = Villages.name(UUID.randomUUID());
        return n;
    }

    /** A name as typed, made safe: letters, digits, spaces, apostrophes, hyphens and stops, at most 24. */
    public static String cleanName(String raw) {
        if (raw == null) return "";
        StringBuilder b = new StringBuilder();
        for (char c : raw.trim().toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == ' ' || c == '\'' || c == '-' || c == '.') b.append(c);
            if (b.length() >= 24) break;
        }
        return b.toString().replaceAll(" +", " ").trim();
    }

    // ------------------------------------------------------------------ the screen's answers

    /** What the founding screen and the welcome card ask. */
    public static void act(ServerPlayer p, String action, CompoundTag a) {
        String said = switch (action) {
            case "open" -> { open(p, a.getString("from").isEmpty() ? "screen" : a.getString("from")); yield null; }
            case "found" -> found(p, a.getInt("x"), a.getInt("z"), a.getInt("count"), a.getString("name"), a.getString("theme"), a.getBoolean("travel")).message();
            case "board" -> board(p, BlockPos.of(a.getLong("board")), a.getInt("count"), a.getString("name"), a.getString("theme")).message();
            case "elder", "books", "favourite", "life" -> next(p, action, a);
            default -> null;
        };
        if (said != null && !said.isEmpty()) p.sendSystemMessage(Component.literal("<Village> " + said));
    }

    /** Found at a spot the screen offered (or any loaded spot within reach of the player). */
    public static Founding.Outcome found(ServerPlayer p, int x, int z, int count, String name, String theme, boolean travel) {
        ServerLevel level = p.serverLevel();
        String no = mayFound(p);
        if (no != null) return Founding.Outcome.no(no);
        int reach = AssistantConfig.foundingSiteReach() + 48;
        double dx = x - p.getX(), dz = z - p.getZ();
        if (dx * dx + dz * dz > (double) reach * reach) return Founding.Outcome.no("That spot is too far from you. Open the founding screen nearer to it.");
        if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) return Founding.Outcome.no("That ground is not loaded. Go a little nearer to it.");
        if (Villages.nearest(level, new BlockPos(x, p.getBlockY(), z), Villages.VILLAGE_RANGE * 2) != null) {
            return Founding.Outcome.no("There is a town too near that spot. Pick another, further out.");
        }
        ItemStack token = token(p);
        if (!free(p) && token.isEmpty()) return Founding.Outcome.no(need(p));
        BlockPos at = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
        double dist = Math.sqrt(dx * dx + dz * dz);
        float yaw = dist < 4 ? p.getYRot() : (float) Math.toDegrees(Math.atan2(-dx, dz));
        Founding.Outcome o = Founding.foundNow(level, at, count, yaw, p);
        if (!o.ok() || o.board() == null) return o;
        String n = cleanName(name);
        Founding.dress(level, o.board(), n, Themes.get(theme) != null ? theme : "");
        if (!free(p)) token.shrink(1);
        boolean carried = false;
        if (travel && AssistantConfig.foundingTravel() && dist > 12 && dist <= AssistantConfig.foundingTravelReach()) {
            carried = carry(level, p, at, Founding.allowed(count));
        }
        LOG.info("[MCA-FOUND] {} founds {} at {} with {} folk{}", p.getGameProfile().getName(), n.isEmpty() ? "a town" : n,
            at.toShortString(), Founding.allowed(count), carried ? " (carried there)" : "");
        welcome(p, at, Founding.allowed(count), n, carried);
        return o;
    }

    /** Confirmed at a spawner's waiting board. */
    public static Founding.Outcome board(ServerPlayer p, BlockPos board, int count, String name, String theme) {
        ServerLevel level = p.serverLevel();
        String no = mayFound(p);
        if (no != null) return Founding.Outcome.no(no);
        if (!level.isLoaded(board)) return Founding.Outcome.no("That board is too far away.");
        Founding.Site s = Founding.siteNear(level, board, 64);
        BlockPos heart = s == null ? board : s.heart;
        Founding.Outcome o = Founding.confirm(level, board, count, p);
        if (!o.ok()) return o;
        String n = cleanName(name);
        Founding.dress(level, board, n, Themes.get(theme) != null ? theme : "");
        welcome(p, heart, Founding.allowed(count), n, false);
        return o;
    }

    /** Over to the near side of the town's ground, outside what is levelled, falling softly if the ground moves. */
    private static boolean carry(ServerLevel level, ServerPlayer p, BlockPos heart, int folk) {
        double dx = p.getX() - heart.getX(), dz = p.getZ() - heart.getZ();
        double len = Math.max(1, Math.sqrt(dx * dx + dz * dz));
        int out = FoundingPlan.coreRadius(folk) + 10;
        int x = heart.getX() + (int) Math.round(dx / len * out), z = heart.getZ() + (int) Math.round(dz / len * out);
        level.getChunk(x >> 4, z >> 4);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        float face = (float) Math.toDegrees(Math.atan2(-(heart.getX() - x), heart.getZ() - z));
        p.teleportTo(level, x + 0.5, y, z + 0.5, face, 10.0F);
        p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 20 * 30, 0, false, false, true));
        return true;
    }

    /** The welcome card: what happens next, and the next steps. */
    private static void welcome(ServerPlayer p, BlockPos heart, int folk, String name, boolean carried) {
        CompoundTag t = new CompoundTag();
        t.putString("name", name.isEmpty() ? "Your village" : name);
        t.putInt("folk", folk);
        t.putIntArray("heart", new int[]{ heart.getX(), heart.getY(), heart.getZ() });
        int across = 2 * FoundingPlan.coreRadius(folk) + 1;
        t.putInt("across", across);
        int dist = (int) Math.sqrt(p.blockPosition().distSqr(new BlockPos(heart.getX(), p.getBlockY(), heart.getZ())));
        t.putInt("dist", dist);
        t.putString("bearing", Founding.bearing(p.blockPosition(), heart));
        t.putBoolean("carried", carried);
        send(p, FoundEasyPayload.WELCOME, t);
    }

    /** The welcome card's next steps, once the town is there. */
    private static String next(ServerPlayer p, String what, CompoundTag a) {
        ServerLevel level = p.serverLevel();
        int[] h = a.getIntArray("heart");
        if (h.length < 3) return null;
        BlockPos heart = new BlockPos(h[0], h[1], h[2]);
        Villages.Village v = Villages.nearest(level, heart, 64);
        if (v == null) {
            int pc = Founding.percentNear(level, heart, 64);
            return "Not founded yet: the ground is " + (pc < 0 ? "being got ready" : pc + "% level") + ". The folk come as soon as the heart of it is level; try again in a minute.";
        }
        if (what.equals("life")) {
            PlayerLife.open(p, "life", null);
            return null;
        }
        if (what.equals("books")) {
            com.jrpetty.mcassistant.net.AssistantNetwork.sendCityStats(p, level, v);
            return null;
        }
        VillageFolkEntity elder = null;
        UUID id = Villages.elder(v.id());
        if (id != null && level.getEntity(id) instanceof VillageFolkEntity e) elder = e;
        if (elder == null) {                                                   // nobody chosen yet: the first of them, nearest the heart
            double best = Double.MAX_VALUE;
            for (AssistantEntity f : Villages.folkOf(v.id())) {
                if (!(f instanceof VillageFolkEntity vf) || !vf.isAlive() || vf.level() != level) continue;
                double d = vf.distanceToSqr(v.centre().getX(), v.centre().getY(), v.centre().getZ());
                if (d < best) { best = d; elder = vf; }
            }
        }
        if (elder == null) return "Nobody has come yet. Give them a minute.";
        String who = elder.displayNameCap() + (id != null && id.equals(elder.getUUID()) ? ", the elder," : ", the first of the founders,");
        if (what.equals("favourite")) {
            Favourites.Added added = Favourites.add(p, elder);
            return switch (added) {
                case ADDED -> who + " is a favourite now: you hear how they get on.";
                case ALREADY -> who + " is a favourite already.";
                case FULL -> "Your favourites are full.";
            };
        }
        elder.addEffect(new MobEffectInstance(MobEffects.GLOWING, 20 * 30, 0, false, false));
        int d = (int) Math.sqrt(elder.distanceToSqr(p));
        return who + " is " + d + " blocks " + Founding.bearing(p.blockPosition(), elder.blockPosition()) + ", glowing so you can find them. Right-click them to talk.";
    }

    /** The town is founded: the name and look chosen on the screen are its own. */
    static void founded(UUID village, String name, String theme) {
        if (name != null && !name.isEmpty()) Ledger.note(village, "name", name);
        if (theme != null && !theme.isEmpty() && !theme.equals(Themes.idOf(village))) Themes.set(village, theme);
    }

    /** All the founders are there: the founder's notice. */
    static void finished(ServerLevel level, @Nullable UUID founder, UUID village) {
        if (founder == null) return;
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(founder);
        if (p == null) return;
        toast(p, Villages.name(village) + " is founded", Villages.headcount(village) + " folk on level ground. Press U for your life in town.");
    }

    /** The life page out in the wild: start a village of your own, with what it takes. */
    static void lifeRows(ServerPlayer p, LifePage page) {
        page.heading("Start a village");
        String no = mayFound(p);
        String need = need(p);
        String sub = no != null ? no : !need.isEmpty() ? need
            : "Pick a spot (here, or the best near), a size and a name. The folk come, level the ground and build it themselves.";
        page.row(new ItemStack(McAssistantMod.FOUNDING_KIT.get()), "Start a village of your own", sub, "")
            .button("Start a village", "found", null).primary();
    }

    // ------------------------------------------------------------------ the kit and the hints

    /** A player's first join: one Founding Kit, once (config foundingFreeKit). True if it was given now. */
    public static boolean welcomeNewPlayer(ServerPlayer p) {
        if (!AssistantConfig.foundingFreeKit()) return false;
        CompoundTag keep = p.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        if (keep.getBoolean(KIT_GIVEN)) return false;
        keep.putBoolean(KIT_GIVEN, true);
        p.getPersistentData().put(Player.PERSISTED_NBT_TAG, keep);
        ItemStack kit = new ItemStack(McAssistantMod.FOUNDING_KIT.get());
        if (!p.getInventory().add(kit)) p.drop(kit, false);
        p.sendSystemMessage(Component.literal("<Village> You have a Founding Kit: right-click it (or press U) to start a village of your own."));
        return true;
    }

    /** A player with no town of their own: founded none, a citizen of none, none being founded for them. */
    public static boolean hasOwn(ServerPlayer p) {
        UUID me = p.getUUID();
        for (Villages.Village v : Villages.every()) {
            if (me.equals(Advancements.founder(v.id())) || Citizens.is(v.id(), me)) return true;
        }
        return Founding.underWayFor(p.server, me) > 0;
    }

    /** Should this player be told they can start a town? Out in the open overworld, far from any town, with none of their own. */
    public static boolean wantsHint(ServerPlayer p) {
        ServerLevel level = p.serverLevel();
        if (!AssistantConfig.foundingHints() || level.dimensionType().hasCeiling() || p.isSpectator()) return false;
        if (Villages.nearest(level, p.blockPosition(), 256) != null || Founding.near(level, p.blockPosition(), 256)) return false;
        return !hasOwn(p);
    }

    /** When each player is next looked at for a hint, and how many they have had this session. */
    private static final Map<UUID, long[]> HINTS = new HashMap<>();
    private static final long FIRST = 200, AGAIN = 20 * 60 * 20;              // ten seconds after joining; then every twenty minutes
    private static final int MOST = 3;

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        welcomeNewPlayer(p);
        HINTS.put(p.getUUID(), new long[]{ p.server.getTickCount() + FIRST, 0 });
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        HINTS.remove(e.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post e) {
        var server = e.getServer();
        int now = server.getTickCount();
        if (now % 20 != 0 || HINTS.isEmpty()) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            long[] h = HINTS.get(p.getUUID());
            if (h == null || now < h[0] || h[1] >= MOST) continue;
            h[0] = now + AGAIN;
            if (!wantsHint(p)) continue;
            h[1]++;
            hint(p);
        }
    }

    /** The notice: you can start a village, and how. */
    public static void hint(ServerPlayer p) {
        boolean kit = !token(p).isEmpty();
        toast(p, "Start a village of your own", kit ? "Right-click your Founding Kit, or press U and choose Start a village."
            : "Press U and choose Start a village.");
    }

    static void toast(ServerPlayer p, String title, String text) {
        CompoundTag t = new CompoundTag();
        t.putString("title", title);
        t.putString("text", text);
        send(p, FoundEasyPayload.TOAST, t);
    }

    static void send(ServerPlayer p, int kind, CompoundTag t) {
        if (TEST_SINK != null) TEST_SINK.accept(kind, t);
        if (p.connection == null || !p.connection.hasChannel(FoundEasyPayload.TYPE)) return;   // a test's stand-in player, a client without the mod
        PacketDistributor.sendToPlayer(p, new FoundEasyPayload(kind, t));
    }

    /** Tests: what was sent to players, as it was sent. */
    @Nullable public static java.util.function.BiConsumer<Integer, CompoundTag> TEST_SINK;

    // ------------------------------------------------------------------ the spawner and the charter, in the air

    /** A spawner or a charter right-clicked at nothing: the founding screen (placing the one, or using the other on the ground, as ever). */
    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem e) {
        ItemStack held = e.getItemStack();
        if (!held.is(McAssistantMod.FOLK_SPAWNER_ITEM.get()) && !held.is(McAssistantMod.VILLAGE_CHARTER.get())) return;
        Entity who = e.getEntity();
        if (who.level().isClientSide) {
            e.setCancellationResult(InteractionResult.SUCCESS);
            e.setCanceled(true);
            return;
        }
        if (!(who instanceof ServerPlayer p)) return;
        if (Villages.nearest(p.serverLevel(), p.blockPosition(), Villages.VILLAGE_RANGE * 2) != null) return;   // in a town: they add a settler, on the ground
        open(p, held.is(McAssistantMod.VILLAGE_CHARTER.get()) ? "charter" : "spawner");
        e.setCancellationResult(InteractionResult.SUCCESS);
        e.setCanceled(true);
    }

    /** The usual party a spawner or charter founds with (kept for the spawner's board and the charter). */
    public static int party() {
        return VillageFolkSpawnerBlock.foundingParty();
    }
}
