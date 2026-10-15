package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.Guard;
import com.jrpetty.mcassistant.block.MilestoneBlock;
import com.jrpetty.mcassistant.block.MilestoneBlockEntity;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.item.WorkItems;
import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * [trade-world] Where the harbours, the roads and the folk on the move meet the rest of the game: the town's visit
 * (TownLife), what a folk says about them (FolkTalk: the ships, the roads, "won't you stay?"), the talk screen's flags and
 * the card's lines, and the commands: {@code /village harbour}, {@code /village highways}, {@code /village migration}
 * (each with {@code stage} for the pictures and its operators' "now").
 */
public final class TradeWorld {

    private TradeWorld() {}

    /** [TownLife.tick, every ten seconds] The harbour and its ships, the roads and their inns, the folk thinking of going. */
    public static void tick(ServerLevel level, Villages.Village v) {
        Guard.run("harbours", () -> Harbours.tick(level, v));
        Guard.run("roadworks", () -> RoadWorks.tick(level, v));
        Guard.run("roadside", () -> Roadside.tick(level, v));
        Guard.run("migration", () -> Migration.tick(level, v));
    }

    public static void resetForTests() {
        Harbours.resetForTests();
        RoadWorks.resetForTests();
        Migration.resetForTests();
    }

    // ------------------------------------------------------------------ talk

    /** [FolkTalk.answer] The ships and the harbour, the roads, and a folk thinking of leaving talked round. Null otherwise. */
    @Nullable
    public static String talk(VillageFolkEntity f, Player p, TalkTopic topic, String text, boolean typed) {
        if (Visitors.is(f) || f.isBaby() || f.ownerId() == null || !(f.level() instanceof ServerLevel level)) return null;
        if (topic == TalkTopic.GIFT) {
            Migration.gifted(f, p);                                     // counts toward keeping it; the gift itself goes on as ever
            return null;
        }
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) return null;
        if (Migration.thinking(f) && (lower.contains("won't you stay") || lower.contains("wont you stay") || lower.contains("please stay")
                || lower.contains("don't go") || lower.contains("dont go") || lower.contains("stay with us"))) {
            return Migration.persuade(level, f, p);
        }
        if (lower.matches(".*\\bships?\\b.*") || lower.contains("harbour") || lower.contains("harbor") || lower.contains("lighthouse")) {
            return harbourTalk(level, f);
        }
        if (lower.matches(".*\\broads\\b.*") || lower.contains("road gang") || lower.contains("milestone") || lower.contains("roadside inn")) {
            return roadTalk(level, f);
        }
        return null;
    }

    /** What a folk says of the harbour: the harbourmaster from its books, anybody else what it has heard and who to ask. */
    static String harbourTalk(ServerLevel level, VillageFolkEntity f) {
        UUID id = f.ownerId();
        Villages.Village v = Villages.get(id);
        Harbours.Shore s = Harbours.shore(id);
        if (v == null || s == null) {
            String why = id == null ? "" : Harbours.noShore(id);
            return "A harbour? Not here" + (why.isEmpty() ? "." : ": " + why + ".");
        }
        if (!Harbours.ready(id)) return "We'll have a harbour once the office stands and the pier is out. Then the ships will come.";
        ShipCalls.Ship ship = ShipCalls.ship(id);
        long day = level.getDayTime() / 24000L;
        boolean master = Harbours.isMaster(f);
        StringBuilder sb = new StringBuilder();
        if (ship != null) {
            ForeignShips.Land land = ship.land();
            sb.append(ship.name).append(" of ").append(land == null ? "over the sea" : land.name()).append(ship.docked()
                ? " is in harbour" + (ship.leave <= day ? " and sails this morning" : ", sailing in " + (ship.leave - day) + (ship.leave - day == 1 ? " day" : " days"))
                : "in".equals(ship.stage) ? " is coming in now" : " is sailing away").append(". ");
            if (master && ship.docked()) sb.append("Talk to its captain on the quay if you want to trade. It paid ").append(ship.dues).append(" coins in dues.");
        } else {
            long next = Harbours.parse(Ledger.note(id, ShipCalls.NEXT));
            ForeignShips.Land l = ShipCalls.nextLand(id);
            sb.append(next < 0 ? "No ship's due that I know of." : "The next ship's due " + (next <= day ? "today" : next == day + 1 ? "tomorrow"
                : "in " + (next - day) + " days") + (l == null ? "" : ", from " + l.name()) + ".");
            if (!Wars.enemies(id).isEmpty()) sb.append(" Not while we're at war, mind: no merchant sails into a war.");
        }
        if (master) {
            if (Villages.builtStructure(id, Harbours.LIGHT) != null) {
                sb.append(Harbours.litLately(id, day) ? " I keep the light lit: the ships come oftener for it." : " The light's been dark: there's no coal to burn.");
            }
        } else {
            VillageFolkEntity m = Harbours.holder(level, id);
            if (m != null) sb.append(" Ask ").append(m.displayNameCap()).append(", the harbourmaster.");
        }
        return sb.toString().trim();
    }

    /** What a folk says of the roads: the best of them, and the gang's work. */
    static String roadTalk(ServerLevel level, VillageFolkEntity f) {
        UUID id = f.ownerId();
        Villages.Village v = Villages.get(id);
        if (v == null) return null;
        List<RoadWorks.Road> all = RoadWorks.roads(id);
        if (all.isEmpty()) return "Roads? We've no neighbours near enough to need one.";
        StringBuilder sb = new StringBuilder();
        RoadWorks.Road busiest = null;
        int traffic = -1;
        for (RoadWorks.Road r : all) {
            int t = RoadWorks.half(id, r.other(id)).traffic;
            if (t > traffic) {
                traffic = t;
                busiest = r;
            }
        }
        UUID o = busiest.other(id);
        sb.append("The road to ").append(Villages.name(o)).append(" is ").append(RoadWorks.GRADES[RoadWorks.grade(id, o)])
            .append(", ").append(RoadWorks.leagues(busiest.length())).append(" of it. ");
        RoadWorks.Road working = RoadWorks.workingOn(v);
        if (working != null) {
            UUID w = working.other(id);
            sb.append("The gang's out on the road to ").append(Villages.name(w)).append(", making it ")
                .append(RoadWorks.GRADES[RoadWorks.half(id, w).grade + 1]).append(".");
        } else {
            sb.append("Nobody's working on the roads just now.");
        }
        return sb.toString();
    }

    /** [VillageBoards] The board's line: the ship in harbour (or the next due), and how many think of leaving. Null if none. */
    @Nullable
    public static String boardLine(ServerLevel level, UUID id) {
        List<String> parts = new ArrayList<>();
        ShipCalls.Ship ship = Harbours.ready(id) ? ShipCalls.ship(id) : null;
        long day = level.getDayTime() / 24000L;
        if (ship != null && ship.docked()) {
            ForeignShips.Land l = ship.land();
            parts.add(ship.name + " of " + (l == null ? "over the sea" : l.name()) + " is in harbour: trade with its captain on the quay");
        } else if (Harbours.ready(id)) {
            long next = Harbours.parse(Ledger.note(id, ShipCalls.NEXT));
            if (next >= day) parts.add("the next ship is due " + (next == day ? "today" : next == day + 1 ? "tomorrow" : "in " + (next - day) + " days"));
        }
        int thinking = 0;
        for (String k : Ledger.notes(id).keySet()) if (k.startsWith(Migration.THOUGHT)) thinking++;
        if (thinking > 0) parts.add(thinking == 1 ? "one of us is thinking of leaving" : thinking + " of us are thinking of leaving");
        return parts.isEmpty() ? null : FolkTalk.cap(String.join("; ", parts)) + ".";
    }

    /** [FolkTalk.talkContext] " leaving" (thinking of leaving), " harbourmaster", " harbour" (its town's harbour is open). */
    public static String contextFlags(VillageFolkEntity f) {
        if (f.ownerId() == null || Visitors.is(f)) return "";
        StringBuilder sb = new StringBuilder();
        if (Migration.thinking(f)) sb.append(" leaving");
        if (Harbours.isMaster(f)) sb.append(" harbourmaster");
        if (Harbours.ready(f.ownerId())) sb.append(" harbour");
        return sb.toString();
    }

    /** [FolkTalk.card] The harbourmaster's line, or "". */
    public static String postLine(VillageFolkEntity f) {
        if (f.ownerId() == null || !Harbours.isMaster(f)) return "";
        return "the harbourmaster: sees the ships in, keeps the light, takes a quarter of the dues; ask it when the next ship is due";
    }

    // ------------------------------------------------------------------ commands

    @Nullable
    private static Villages.Village here(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Villages.Village v = Villages.nearest(level, BlockPos.containing(ctx.getSource().getPosition()), Villages.VILLAGE_RANGE * 4);
        if (v == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        if (v == null) ctx.getSource().sendFailure(Component.literal("No village within reach."));
        return v;
    }

    private static int say(CommandContext<CommandSourceStack> ctx, String head, List<String> lines) {
        StringBuilder sb = new StringBuilder(head);
        for (String l : lines) {
            sb.append('\n');
            int bar = l.indexOf('|');
            sb.append(bar == 1 ? (l.charAt(0) == 'H' ? "== " + l.substring(2) + " ==" : l.charAt(0) == 'W' ? "! " + l.substring(2) : l.substring(2)) : l);
        }
        String text = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return Math.max(1, lines.size());
    }

    private static int books(CommandContext<CommandSourceStack> ctx, String page) {
        Villages.Village v = here(ctx);
        if (v == null || !(ctx.getSource().getEntity() instanceof ServerPlayer p)) return 0;
        CompoundTag books = Annals.snapshot(ctx.getSource().getLevel(), v);
        books.putString("page", page);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.jrpetty.mcassistant.net.CityStatsPayload(books));
        return 1;
    }

    /**
     * /village harbour: the town's harbour, its light and its ships. {@code books} opens the Harbour page. Operators:
     * {@code survey} (look for the shore now), {@code ship [land]} (a ship sighted now), {@code stage} (for the pictures).
     */
    public static LiteralArgumentBuilder<CommandSourceStack> harbourCommand() {
        return Commands.literal("harbour")
            .executes(ctx -> {
                Villages.Village v = here(ctx);
                return v == null ? 0 : say(ctx, "HARBOUR " + Villages.name(v.id()), Harbours.lines(ctx.getSource().getLevel(), v));
            })
            .then(Commands.literal("books").executes(ctx -> books(ctx, "Harbour")))
            .then(Commands.literal("survey").requires(src -> src.hasPermission(2)).executes(ctx -> {
                Villages.Village v = here(ctx);
                if (v == null) return 0;
                Harbours.Shore s = Harbours.surveyForTests(ctx.getSource().getLevel(), v);
                ctx.getSource().sendSuccess(() -> Component.literal(s == null ? "No harbour: " + Harbours.noShore(v.id())
                    : "SHORE " + s.water().toShortString() + " out " + s.out().getName() + "; office " + s.office().toShortString()
                    + "; light " + (s.light() == null ? "none" : s.light().toShortString())), false);
                return 1;
            }))
            .then(Commands.literal("ship").requires(src -> src.hasPermission(2))
                .executes(ctx -> ship(ctx, null))
                .then(Commands.argument("land", StringArgumentType.word()).executes(ctx -> ship(ctx, StringArgumentType.getString(ctx, "land")))))
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2)).executes(ctx -> {
                Villages.Village v = here(ctx);
                if (v == null) return 0;
                List<String> out = harbourStage(ctx.getSource().getLevel(), v, BlockPos.containing(ctx.getSource().getPosition()));
                ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", out)), false);
                return out.size();
            }));
    }

    private static int ship(CommandContext<CommandSourceStack> ctx, @Nullable String land) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        String name = ShipCalls.sightNow(ctx.getSource().getLevel(), v, land == null ? null : land.toLowerCase(Locale.ROOT));
        ctx.getSource().sendSuccess(() -> Component.literal(name == null ? "No harbour." : "SHIP " + name + " sighted"), false);
        return name == null ? 0 : 1;
    }

    /** /village highways: the town's roads, their grades and inns, and the comings and goings. Operators: now, stage. */
    public static LiteralArgumentBuilder<CommandSourceStack> highwaysCommand() {
        return Commands.literal("highways")
            .executes(ctx -> {
                Villages.Village v = here(ctx);
                return v == null ? 0 : say(ctx, "ROADS " + Villages.name(v.id()), RoadWorks.lines(ctx.getSource().getLevel(), v));
            })
            .then(Commands.literal("books").executes(ctx -> books(ctx, "Roads")))
            .then(Commands.literal("now").requires(src -> src.hasPermission(2)).executes(ctx -> {
                Villages.Village v = here(ctx);
                return v == null ? 0 : say(ctx, "ROADS NOW " + Villages.name(v.id()), RoadWorks.upgradeNow(ctx.getSource().getLevel(), v));
            }))
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2)).executes(ctx -> {
                Villages.Village v = here(ctx);
                if (v == null) return 0;
                List<String> out = roadStage(ctx.getSource().getLevel(), v, BlockPos.containing(ctx.getSource().getPosition()));
                ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", out)), false);
                return out.size();
            }));
    }

    /** /village migration: who is thinking of going, and the comings and goings. Operators: now [n], think, stage. */
    public static LiteralArgumentBuilder<CommandSourceStack> migrationCommand() {
        return Commands.literal("migration")
            .executes(ctx -> {
                Villages.Village v = here(ctx);
                return v == null ? 0 : say(ctx, "MIGRATION " + Villages.name(v.id()), Migration.lines(ctx.getSource().getLevel(), v));
            })
            .then(Commands.literal("now").requires(src -> src.hasPermission(2))
                .executes(ctx -> migrate(ctx, 1))
                .then(Commands.argument("n", IntegerArgumentType.integer(1, 50)).executes(ctx -> migrate(ctx, IntegerArgumentType.getInteger(ctx, "n")))))
            .then(Commands.literal("think").requires(src -> src.hasPermission(2)).executes(ctx -> {
                Villages.Village v = here(ctx);
                if (v == null) return 0;
                List<String> out = migrationStage(ctx.getSource().getLevel(), v);
                ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", out)), false);
                return out.size();
            }))
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2)).executes(ctx -> {
                Villages.Village v = here(ctx);
                if (v == null) return 0;
                List<String> out = migrationStage(ctx.getSource().getLevel(), v);
                ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", out)), false);
                return out.size();
            }));
    }

    private static int migrate(CommandContext<CommandSourceStack> ctx, int n) {
        Villages.Village v = here(ctx);
        return v == null ? 0 : say(ctx, "MIGRATE " + Villages.name(v.id()), Migration.migrateNow(ctx.getSource().getLevel(), v, n));
    }

    // ------------------------------------------------------------------ the pictures' stages

    /**
     * The harbour's stage, beside where it is run: a bay cut out of the ground to the east (three deep, sixty-odd long),
     * the town's harbour found on its bank, the office and the light set down for nothing, the pier laid for nothing, the
     * light lit, and a ship of Qadira brought in and moored with its people ashore. Views: the harbour from over the water,
     * the ship at the pier, the light. Returns "VIEW name x y z lookx looky lookz" lines and what was done.
     */
    static List<String> harbourStage(ServerLevel level, Villages.Village v, BlockPos at) {
        List<String> out = new ArrayList<>();
        int y = Visitors.surface(level, at).getY();
        int x0 = at.getX() + 12, z0 = at.getZ() - 20;
        // The bay: water three deep from x0 east for seventy, forty across; the bank west of it, levelled.
        for (int x = x0 - 18; x <= x0 + 70; x++) {
            for (int z = z0 - 4; z <= z0 + 44; z++) {
                boolean wet = x >= x0;
                for (int dy = -4; dy <= 12; dy++) {
                    BlockPos p = new BlockPos(x, y + dy, z);
                    BlockState want = dy < -3 ? Blocks.SAND.defaultBlockState() : wet ? (dy < 0 ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState())
                        : dy < -1 ? Blocks.DIRT.defaultBlockState() : dy == -1 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState();
                    if (wet && dy == -4) want = Blocks.SAND.defaultBlockState();
                    if (!level.getBlockState(p).equals(want)) level.setBlock(p, want, Block.UPDATE_CLIENTS | 16);
                }
            }
        }
        Harbours.Shore s = Harbours.surveyForTests(level, v);
        if (s == null) {
            String[] why = new String[1];
            s = Harbours.fit(level, v.id(), x0, z0 + 20, Direction.EAST, y - 1, why);
            if (s == null) {
                out.add("no harbour on the stage's bay: " + why[0]);
                return out;
            }
            Ledger.note(v.id(), Harbours.SHORE, s.encode());
            Harbours.resetShore(v.id());
        }
        Harbours.officeForTests(level, v);
        Harbours.lightForTests(level, v);
        for (Harbours.Piece p : Harbours.plan(s)) {
            if (!Harbours.missing(level, s, p)) continue;
            BlockState st = switch (p.kind()) {
                case POST, DECK -> Blocks.STONE_BRICKS.defaultBlockState();
                case BOLLARD -> Blocks.COBBLESTONE_WALL.defaultBlockState();
                case LAMP_POST -> Blocks.SPRUCE_FENCE.defaultBlockState();
                case CASK -> Blocks.BARREL.defaultBlockState();
            };
            if (p.kind() == Harbours.Kind.POST) {
                for (BlockPos q = p.pos(); q.getY() > y - 6 && !level.getBlockState(q).isSolid(); q = q.below()) level.setBlock(q, st, Block.UPDATE_ALL);
            } else if (p.kind() == Harbours.Kind.CASK) {
                level.setBlock(Visitors.surface(level, p.pos()), st, Block.UPDATE_ALL);
            } else {
                level.setBlock(p.pos(), st, Block.UPDATE_ALL);
                if (p.kind() == Harbours.Kind.LAMP_POST) level.setBlock(p.pos().above(), Blocks.LANTERN.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        Ledger.note(v.id(), Harbours.PIER_NOTE, "done");
        BlockPos beacon = Harbours.beaconForTests(v.id());
        if (beacon != null && level.getBlockState(beacon).getBlock() instanceof CampfireBlock) {
            level.setBlock(beacon, level.getBlockState(beacon).setValue(CampfireBlock.LIT, true), Block.UPDATE_ALL);
        }
        ShipCalls.sightNow(level, v, "qadira");
        ShipCalls.Ship ship = ShipCalls.ship(v.id());
        if (ship != null) {
            ship.pos = ShipHull.BERTH;
            ShipHull.move(level, s, ship.flag(), false, 0, true, ShipHull.BERTH, true);
            ship.drawn = true;
            ShipCalls.moored(level, v, s, ship);
        }
        BlockPos quay = s.onDeck(1, 0), mid = s.at(ShipHull.BERTH + 5, ShipHull.MIDDLE, 4);
        BlockPos over = s.at(28, -14, 12);
        out.add("VIEW harbour " + over.getX() + " " + over.getY() + " " + over.getZ() + " " + quay.getX() + " " + quay.getY() + " " + quay.getZ());
        BlockPos near = s.at(1, -5, 3);
        out.add("VIEW ship " + near.getX() + " " + near.getY() + " " + near.getZ() + " " + mid.getX() + " " + mid.getY() + " " + mid.getZ());
        if (s.light() != null) {
            BlockPos lv = s.at(6, -14, 8);
            out.add("VIEW light " + lv.getX() + " " + lv.getY() + " " + lv.getZ() + " " + s.light().getX() + " " + (s.light().getY() + 10) + " " + s.light().getZ());
        }
        out.add("harbour staged: " + String.join(" / ", Harbours.lines(level, v)));
        return out;
    }

    /**
     * The roads' stage, beside where it is run (to the south): forty blocks of road, the grades side by side as a road is
     * made over the years (a path, then gravel, then stone with kerbs and lamp posts), a milestone lettered for the town, a
     * signpost, and the roadside inn by it, all set down for nothing. Views: along the road, the milestone, the inn.
     */
    static List<String> roadStage(ServerLevel level, Villages.Village v, BlockPos at) {
        List<String> out = new ArrayList<>();
        int y = Visitors.surface(level, at).getY() - 1;
        int x0 = at.getX() - 20, z0 = at.getZ() + 14;
        for (int x = x0 - 2; x <= x0 + 42; x++) {
            for (int z = z0 - 16; z <= z0 + 6; z++) {
                for (int dy = -2; dy <= 10; dy++) {
                    BlockPos p = new BlockPos(x, y + dy, z);
                    BlockState want = dy < 0 ? Blocks.DIRT.defaultBlockState() : dy == 0 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState();
                    if (!level.getBlockState(p).equals(want)) level.setBlock(p, want, Block.UPDATE_CLIENTS | 16);
                }
            }
        }
        for (int i = 0; i <= 40; i++) {
            int x = x0 + i;
            Block top = i < 13 ? Blocks.DIRT_PATH : i < 26 ? Blocks.GRAVEL : Blocks.COBBLESTONE;
            for (int c = -1; c <= 1; c++) level.setBlock(new BlockPos(x, y, z0 + c), top.defaultBlockState(), Block.UPDATE_ALL);
            if (i >= 26) {
                for (int c : new int[]{ -2, 2 }) {
                    level.setBlock(new BlockPos(x, y + 1, z0 + c), Blocks.STONE_BRICK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM), Block.UPDATE_ALL);
                }
                if ((i - 26) % 12 == 6) {
                    BlockPos post = new BlockPos(x, y + 1, z0 + 3);
                    level.setBlock(post, Blocks.SPRUCE_FENCE.defaultBlockState(), Block.UPDATE_ALL);
                    level.setBlock(post.above(), Blocks.SPRUCE_FENCE.defaultBlockState(), Block.UPDATE_ALL);
                    level.setBlock(post.above(2), Blocks.LANTERN.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        BlockPos stone = new BlockPos(x0 + 27, y + 1, z0 - 3);
        level.setBlock(stone, WorkItems.MILESTONE.get().defaultBlockState().setValue(MilestoneBlock.FACING, Direction.SOUTH), Block.UPDATE_ALL);
        if (level.getBlockEntity(stone) instanceof MilestoneBlockEntity be) {
            be.setWays(List.of(new MilestoneBlockEntity.Way(Villages.name(v.id()), v.id(), v.centre().getX(), v.centre().getZ(),
                Integer.signum(v.centre().getX() - stone.getX()), Integer.signum(v.centre().getZ() - stone.getZ()),
                (int) Math.sqrt(v.centre().distSqr(stone)))));
        }
        // The roadside inn, set back off the road with its door to it.
        BlockPos inn = new BlockPos(x0 + 14, y + 1, z0 - 10);
        BuildGoal.stamp(level, Roadside.DRAWING, inn, Direction.NORTH, 13, com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        BlockPos sign = Inn.signAt(new Ledger.Building(Roadside.DRAWING, inn, Direction.NORTH));
        if (level.getBlockState(sign).isAir()) {
            level.setBlock(sign, Blocks.SPRUCE_WALL_SIGN.defaultBlockState().setValue(net.minecraft.world.level.block.WallSignBlock.FACING, Direction.SOUTH), Block.UPDATE_ALL);
        }
        if (level.getBlockEntity(sign) instanceof net.minecraft.world.level.block.entity.SignBlockEntity se) {
            TownLife.write(se, new String[]{ "Roadside Inn", Villages.name(v.id()).length() > 15 ? Villages.name(v.id()).substring(0, 15) : Villages.name(v.id()),
                "Room " + Roadside.ROOM + "c", "Sneak: meal " + Roadside.MEAL + "c" });
        }
        out.add("VIEW road " + (x0 - 6) + " " + (y + 5) + " " + (z0 + 4) + " " + (x0 + 30) + " " + y + " " + z0);
        out.add("VIEW milestone " + (stone.getX() - 3) + " " + (y + 3) + " " + (stone.getZ() + 4) + " " + stone.getX() + " " + stone.getY() + " " + stone.getZ());
        out.add("VIEW inn " + (inn.getX() + 4) + " " + (y + 6) + " " + (z0 + 6) + " " + inn.getX() + " " + (y + 2) + " " + inn.getZ());
        out.add("roads staged: " + String.join(" / ", RoadWorks.lines(level, v)));
        return out;
    }

    /** Migration's stage: the folk with most reason to go starts to think of it now (it says so); its card and the lines. */
    static List<String> migrationStage(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        VillageFolkEntity best = null;
        int bestNet = Integer.MIN_VALUE;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || !Migration.free(level, f) || Migration.thinking(f)) continue;
            int[] pt = Migration.pushAndTiesForTests(level, f);
            if (pt[0] - pt[1] > bestNet) {
                bestNet = pt[0] - pt[1];
                best = f;
            }
        }
        if (best == null) {
            out.add("nobody free to think of leaving");
            return out;
        }
        UUID to = Migration.thinkNowForTests(level, best);
        out.add(to == null ? best.displayNameCap() + " has nowhere better to go" : best.displayNameCap() + " thinks of leaving for " + Villages.name(to));
        BlockPos b = best.blockPosition();
        out.add("VIEW folk " + (b.getX() + 3) + " " + (b.getY() + 2) + " " + (b.getZ() + 3) + " " + b.getX() + " " + (b.getY() + 1) + " " + b.getZ());
        out.addAll(Migration.lines(level, v));
        return out;
    }
}
