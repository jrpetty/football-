package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.Guard;
import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [transport] The town's ways of getting about, together: the railways (Railways: the lines laid; RailCarts: the
 * carts on them and who rides them), the ferry (Ferries) and the stone bridge that replaces it (Bridges). This is
 * where they meet the rest of the town: the server's tick, the folk's own tick (riding, waiting at a platform or a
 * landing), the boats and carts a folk may sit in (Aboard), the town's books (the Transport page), the board, the
 * card, and /village transport.
 */
public final class Transport {

    private Transport() {}

    public static void resetForTests() {
        Railways.resetForTests();
        RailCarts.resetForTests();
        Ferries.resetForTests();
        Bridges.resetForTests();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        Guard.run("transport", () -> {
            for (ServerLevel level : event.getServer().getAllLevels()) {
                RailCarts.tick(level);
                Ferries.tick(level);
                if (level.getGameTime() % 20 != 7) continue;
                for (Villages.Village v : Villages.every()) {
                    if (!v.dim().equals(level.dimension()) || !level.isLoaded(v.centre())) continue;
                    Railways.tick(level, v);
                    RailCarts.work(level, v);
                    Ferries.tick(level, v);
                }
            }
        });
    }

    // ------------------------------------------------------------------ the folk

    /** Sat in a cart or the ferry on a ride of its own (or rowing it): the rest of its day waits (VillageFolkEntity.aiStep). */
    public static boolean aboard(VillageFolkEntity f) {
        return RailCarts.aboard(f) || Ferries.aboard(f);
    }

    /** From the folk's tick: a ride, a crossing, or the ferryman's day at the ferry. True while one holds it. */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        return RailCarts.hold(f, level) || Ferries.hold(f, level);
    }

    /** On a ride, a crossing or at the ferry: its own work and its idle thoughts wait (VillageFolkEntity.calledAway). */
    public static boolean busy(VillageFolkEntity f) {
        return RailCarts.busy(f) || Ferries.busy(f);
    }

    /** May it sit in this cart or boat (Aboard)? The ferryman in its ferry, a rider in its cart, a passenger in the ferry. */
    public static boolean mayBoard(VillageFolkEntity f, Entity vehicle) {
        return RailCarts.mayBoard(f, vehicle) || Ferries.mayBoard(f, vehicle);
    }

    /** What it is doing on its way about, for its card; null if nothing. */
    @Nullable
    public static String doing(VillageFolkEntity f) {
        String r = RailCarts.doing(f);
        return r != null ? r : Ferries.passing(f);
    }

    // ------------------------------------------------------------------ the books

    /**
     * The Transport page of the town's books (client/TransportPage): each line (its state, how far laid, what it waits
     * on, its figures, and its way as points for the map), the carts, the ferry and the bridge, and the town's heart and
     * mine for the map.
     */
    public static CompoundTag report(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        CompoundTag out = new CompoundTag();
        out.putIntArray("heart", new int[]{ v.centre().getX(), v.centre().getZ() });
        BlockPos mine = TownMine.siteOf(id);
        if (mine != null) out.putIntArray("mine", new int[]{ mine.getX(), mine.getZ() });
        out.putString("age", Villages.ageOf(id).label);
        ListTag lines = new ListTag();
        for (Railways.Line l : Railways.lines(id).values()) {
            CompoundTag t = new CompoundTag();
            RailCarts.Stats s = RailCarts.statsOf(id, l);
            t.putString("name", Railways.cap(l.name()));
            t.putString("state", l.state().words);
            t.putInt("length", l.length());
            t.putInt("laid", l.laid);
            t.putInt("own", l.own());
            t.putString("waiting", l.waiting);
            t.putLong("opened", l.openedDay);
            t.putInt("carts", s.carts);
            t.putInt("goods", s.goods);
            t.putInt("riders", s.riders);
            t.putInt("mended", s.mended);
            int powered = 0, torches = 0;
            for (char c : l.kinds) {
                if (c != Railways.PLAIN) powered++;
                if (c == Railways.BOOST) torches++;
            }
            t.putInt("powered", powered);
            t.putInt("torches", torches);
            // Its way for the map: the turns and the ends (a straight run needs no more).
            List<Integer> pts = new ArrayList<>();
            for (int i = 0; i < l.length(); i++) {
                BlockPos r = l.rail(i);
                boolean keep = i == 0 || i == l.length() - 1;
                if (!keep) {
                    BlockPos a = l.rail(i - 1), b = l.rail(i + 1);
                    keep = (r.getX() - a.getX()) != (b.getX() - r.getX()) || (r.getZ() - a.getZ()) != (b.getZ() - r.getZ());
                }
                if (keep) {
                    pts.add(r.getX());
                    pts.add(r.getZ());
                }
            }
            t.put("way", new IntArrayTag(pts.stream().mapToInt(Integer::intValue).toArray()));
            BlockPos laidTo = l.rail(Math.max(0, Math.min(l.length() - 1, l.laid - 1)));
            t.putIntArray("head", new int[]{ laidTo.getX(), laidTo.getZ() });
            lines.add(t);
        }
        out.put("lines", lines);
        out.put("carts", strings(RailCarts.report(level, id)));
        Ferries.Crossing c = Ferries.crossing(id);
        if (c != null) {
            CompoundTag f = new CompoundTag();
            f.putString("to", c.toWhat);
            f.putString("state", c.state().words);
            f.putInt("width", c.width());
            f.putInt("crossings", c.crossings);
            f.putInt("fares", c.fares);
            f.putInt("free", c.free);
            f.putInt("players", c.players);
            f.putBoolean("weather", Ferries.badWeather(level));
            VillageFolkEntity man = Ferries.ferrymanOf(level, c);
            f.putString("ferryman", man == null ? "" : man.displayNameCap());
            f.putIntArray("a", new int[]{ c.bankA().getX(), c.bankA().getZ() });
            f.putIntArray("b", new int[]{ c.bankB().getX(), c.bankB().getZ() });
            f.putString("bridge", c.bridge().name());
            String b = Bridges.report(level, id);
            f.putString("bridge_words", b == null ? "" : b);
            if (c.bridgeA != null && c.bridgeB != null) {
                f.putIntArray("ba", new int[]{ c.bridgeA.getX(), c.bridgeA.getZ() });
                f.putIntArray("bb", new int[]{ c.bridgeB.getX(), c.bridgeB.getZ() });
            }
            out.put("ferry", f);
        }
        out.put("lines_words", strings(page(level, v)));
        return out;
    }

    private static ListTag strings(List<String> list) {
        ListTag t = new ListTag();
        for (String s : list) t.add(StringTag.valueOf(s));
        return t;
    }

    /** The town's ways about in plain lines: /village transport, and the page's text. */
    public static List<String> page(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>(Railways.report(level, v.id()));
        out.addAll(RailCarts.report(level, v.id()));
        out.addAll(Ferries.report(level, v.id()));
        String b = Bridges.report(level, v.id());
        if (b != null) out.add(b);
        if (out.isEmpty()) {
            out.add(Villages.ageOf(v.id()).ordinal() < Villages.Age.IRON.ordinal()
                ? "No railway: the town lays rails in the Iron Age, once its mine is far out and it has the iron."
                : TownMine.siteOf(v.id()) == null ? "No railway: the town has no mine yet." : "No railway yet: the mine is near enough to walk to, or the iron is short.");
            out.add("No ferry: no water lies between the town and its fields, its mine or its neighbours.");
        }
        return out;
    }

    /** The board's line, or null with nothing to say. */
    @Nullable
    public static String boardLine(ServerLevel level, UUID village) {
        List<String> parts = new ArrayList<>();
        for (Railways.Line l : Railways.lines(village).values()) {
            RailCarts.Stats s = RailCarts.statsOf(village, l);
            if (l.state() == Railways.State.OPEN) {
                parts.add(Railways.cap(l.name()) + " is open" + (s.carts > 0 ? " (" + s.carts + (s.carts == 1 ? " cart" : " carts") + " of ore in)" : ""));
            } else {
                parts.add(Railways.cap(l.name()) + " is being laid, " + (l.laid * 100 / Math.max(1, l.own())) + "%");
            }
        }
        Ferries.Crossing c = Ferries.crossing(village);
        if (c != null && c.state() == Ferries.State.RUNNING) {
            parts.add("the ferry to " + c.toWhat + " runs (" + c.crossings + " crossings, a coin each)"
                + (c.bridge() == Bridges.Stage.BUILDING ? "; a stone bridge is going up beside it" : ""));
        } else if (c != null && c.state() == Ferries.State.RETIRED) {
            parts.add("the stone bridge carries everybody over the water");
        }
        return parts.isEmpty() ? null : "Getting about: " + String.join("; ", parts) + ".";
    }

    // ------------------------------------------------------------------ /village transport

    /**
     * /village transport: the lines, the carts, the ferry and the bridge. {@code books} opens the town's books at the
     * Transport page. Operators: {@code now} works the town's lines and ferry now (a visit, out of the stores as ever);
     * {@code stage} sets out, beside where it is run, a short line to a mine with its stations laid for nothing and a
     * river with a ferry across it, for the pictures; {@code stage bridge} builds that ferry's stone bridge.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("transport")
            .executes(Transport::cmdPage)
            .then(Commands.literal("books").executes(Transport::cmdBooks))
            .then(Commands.literal("now").requires(src -> src.hasPermission(2)).executes(ctx -> {
                Villages.Village v = here(ctx);
                if (v == null) return 0;
                ServerLevel level = ctx.getSource().getLevel();
                Railways.work(level, v);
                RailCarts.workForTests(level, v);
                Ferries.work(level, v);
                return cmdPage(ctx);
            }))
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    List<String> out = stage(ctx.getSource().getLevel(), BlockPos.containing(ctx.getSource().getPosition()), here(ctx));
                    ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", out)), false);
                    return out.size();
                })
                .then(Commands.literal("bridge").executes(ctx -> {
                    Villages.Village v = here(ctx);
                    if (v == null) return 0;
                    Bridges.buildFreeForStage(ctx.getSource().getLevel(), v);
                    String b = Bridges.report(ctx.getSource().getLevel(), v.id());
                    ctx.getSource().sendSuccess(() -> Component.literal("BRIDGE " + (b == null ? "none" : b)), false);
                    return 1;
                })));
    }

    @Nullable
    private static Villages.Village here(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Villages.Village v = Villages.nearest(level, BlockPos.containing(ctx.getSource().getPosition()), Villages.VILLAGE_RANGE * 4);
        if (v == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        if (v == null) ctx.getSource().sendFailure(Component.literal("No village within reach."));
        return v;
    }

    private static int cmdPage(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        List<String> lines = page(ctx.getSource().getLevel(), v);
        String text = "TRANSPORT " + Villages.name(v.id()) + "\n" + String.join("\n", lines);
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return lines.size();
    }

    private static int cmdBooks(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) return cmdPage(ctx);
        CompoundTag books = Annals.snapshot(ctx.getSource().getLevel(), v);
        books.putString("page", "Transport");
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.jrpetty.mcassistant.net.CityStatsPayload(books));
        return 1;
    }

    // ------------------------------------------------------------------ the pictures' stage

    /**
     * The stage for the pictures, beside where it is run: a mine site set ninety-odd blocks out from the town (if it has
     * none) and the line to it planned and laid for nothing, its stations, its carts; and a river cut across the ground
     * twenty blocks along (east) of where it is run, eight wide, with the town's ferry across it (landings, the bell,
     * the boat and a ferryman). Returns the views to photograph ("VIEW name x y z lookx looky lookz") and what was done.
     */
    static List<String> stage(ServerLevel level, BlockPos at, @Nullable Villages.Village v) {
        List<String> out = new ArrayList<>();
        if (v == null) {
            out.add("no village");
            return out;
        }
        UUID id = v.id();
        BlockPos heart = v.centre();
        // The line.
        if (TownMine.siteOf(id) == null) {
            int out_ = Villages.townReach(id) + 70;
            Ledger.note(id, "mine.site", (heart.getX() + out_) + "," + heart.getZ());
        }
        Railways.Line l = Railways.mineLine(id);
        if (l == null) {
            Railways.planAnywayForTests(true);
            try {
                l = Railways.planMine(level, v, level.getDayTime() / 24000L);
            } finally {
                Railways.planAnywayForTests(false);
            }
        }
        if (l == null) {
            out.add("LINE not planned yet (the ground is loading, or no way): run again in a few seconds");
        } else {
            if (l.state() != Railways.State.OPEN) Railways.layFreeForStage(level, v, l);
            for (RailCarts.Role r : RailCarts.Role.values()) {
                if (RailCarts.cart(id, l.key(), r) != null) continue;
                net.minecraft.world.entity.vehicle.AbstractMinecart cart = net.minecraft.world.entity.vehicle.AbstractMinecart.createMinecart(level,
                    l.rail(r == RailCarts.Role.ORE ? 1 : 3).getX() + 0.5, l.rail(1).getY() + 0.0625, l.rail(r == RailCarts.Role.ORE ? 1 : 3).getZ() + 0.5,
                    r == RailCarts.Role.ORE ? net.minecraft.world.entity.vehicle.AbstractMinecart.Type.CHEST
                        : net.minecraft.world.entity.vehicle.AbstractMinecart.Type.RIDEABLE,
                    net.minecraft.world.item.ItemStack.EMPTY, null);
                cart.addTag("transport_stage");
                if (level.addFreshEntity(cart)) RailCarts.adoptForTests(id, l, cart, r, 0);
            }
            BlockPos s0 = l.rail(3), mid = l.rail(l.length() / 2), s1 = l.rail(l.length() - 4);
            Direction side = l.outAt(3).getCounterClockWise();
            BlockPos eye0 = s0.relative(side, 7).above(5);
            out.add("VIEW station " + eye0.getX() + " " + eye0.getY() + " " + eye0.getZ() + " " + s0.getX() + " " + s0.getY() + " " + s0.getZ());
            BlockPos eye1 = mid.relative(side, 10).above(9);
            out.add("VIEW line " + eye1.getX() + " " + eye1.getY() + " " + eye1.getZ() + " " + mid.getX() + " " + mid.getY() + " " + mid.getZ());
            BlockPos eye2 = s1.relative(l.outAt(l.length() - 5).getCounterClockWise(), 7).above(5);
            out.add("VIEW mine-station " + eye2.getX() + " " + eye2.getY() + " " + eye2.getZ() + " " + s1.getX() + " " + s1.getY() + " " + s1.getZ());
            out.add("LINE " + l.length() + " rails, " + l.state().words);
        }
        // The river and its ferry.
        Ferries.Crossing c = Ferries.crossing(id);
        if (c == null) {
            int x0 = at.getX() + 20, z0 = at.getZ() - 14;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x0, at.getZ()) - 1;
            for (int dx = 0; dx < 8; dx++) {
                for (int dz = 0; dz < 28; dz++) {
                    for (int dy = -2; dy <= 4; dy++) {
                        BlockPos p = new BlockPos(x0 + dx, y + dy, z0 + dz);
                        level.setBlock(p, dy <= 0 ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
                    }
                    level.setBlock(new BlockPos(x0 + dx, y - 3, z0 + dz), Blocks.DIRT.defaultBlockState(), 2);
                }
            }
            BlockPos a = new BlockPos(x0 - 1, y, at.getZ()), b = new BlockPos(x0 + 8, y, at.getZ());
            c = Ferries.setForTests(id, a, b, Direction.EAST, y, "the far bank");
        }
        if (c.state() != Ferries.State.RUNNING && c.state() != Ferries.State.RETIRED) Ferries.buildFreeForStage(level, v, c);
        if (Ferries.ferrymanOf(level, c) == null) {
            for (AssistantEntity a : Villages.folkOf(id)) {
                if (a instanceof VillageFolkEntity f && !f.isBaby() && f.stationTask() != AssistantEntity.StationTask.GUARD) {
                    Ferries.appointForTests(level, v, f);
                    BlockPos s = c.stand(0);
                    f.moveTo(s.getX() + 0.5, s.getY(), s.getZ() + 0.5, 0.0F, 0.0F);
                    break;
                }
            }
        }
        BlockPos mid = BlockPos.containing(c.middle());
        BlockPos eye = mid.relative(c.way().getClockWise(), 12).above(6);
        out.add("VIEW ferry " + eye.getX() + " " + eye.getY() + " " + eye.getZ() + " " + mid.getX() + " " + mid.getY() + " " + mid.getZ());
        BlockPos bridgeEye = mid.relative(c.way().getClockWise(), -14).above(7);
        BlockPos bridgeAt = mid.relative(c.way().getClockWise(), 5);
        out.add("VIEW bridge " + bridgeEye.getX() + " " + bridgeEye.getY() + " " + bridgeEye.getZ() + " " + bridgeAt.getX() + " " + bridgeAt.getY()
            + " " + bridgeAt.getZ());
        out.add("FERRY " + c.state().words + ", " + c.width() + " wide at " + c.where());
        return out;
    }
}
