package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * [links12] The region round a town: the towns it knows, the roads and railways between them and how far along each is,
 * how each town stands with it (allies, friendly, neutral, rivals, hostile), and the league table of the region's towns
 * by standing; with the region's news (roads surveyed and opened, railways opened, pacts, alliances, rivalries, embargoes).
 * The Region page of the town's books draws it (RegionPage), and {@code /village region} says it in the chat.
 *
 * <p><b>Standing</b> is what a town counts for among its neighbours: three for every folk, its renown, ten for every age
 * it has come through, a coin in twenty-five of its treasury, five for every town it is joined to by a made road or a
 * railway (its reach grows with its links), and a point for every four coins of its weekly trade with its neighbours.
 */
public final class Region {

    private Region() {}

    /** How far round a town its region reaches. */
    public static final int REACH = 1500;
    /** The region's news kept in each town's notes: so many lines. */
    static final String LOG = "rg.log";
    static final int KEEP = 16;

    // ------------------------------------------------------------------ the news

    /** News that concerns two towns: both chronicles, and both towns' region news. */
    public static void event(UUID a, UUID b, long day, String text) {
        Villages.tell(a, day, text);
        Villages.tell(b, day, text);
        remember(a, day, text);
        remember(b, day, text);
    }

    static void remember(UUID town, long day, String text) {
        List<String> lines = news(town);
        lines.add(0, "Day " + (day + 1) + ": " + text);
        while (lines.size() > KEEP) lines.remove(lines.size() - 1);
        Ledger.note(town, LOG, String.join("\n", lines));
    }

    /** The region's news as this town heard it, newest first. */
    public static List<String> news(UUID town) {
        String s = Ledger.note(town, LOG);
        List<String> out = new ArrayList<>();
        if (s != null && !s.isEmpty()) for (String l : s.split("\n")) if (!l.isEmpty()) out.add(l);
        return out;
    }

    /** [RoadWorks.work, Roads] The road between two towns made so far end to end. */
    public static void roadOpened(UUID a, UUID b, int grade, long day) {
        String words = grade <= 1 ? "the road between " + Villages.name(a) + " and " + Villages.name(b) + " is open end to end"
            : "the road between " + Villages.name(a) + " and " + Villages.name(b) + " is " + RoadWorks.GRADES[Math.min(grade, 3)] + " end to end";
        remember(a, day, words);
        remember(b, day, words);
    }

    /** [Railways] A railway between two towns opened. */
    public static void railOpened(UUID a, UUID b, long day) {
        String words = "the railway between " + Villages.name(a) + " and " + Villages.name(b) + " is open";
        remember(a, day, words);
        remember(b, day, words);
    }

    // ------------------------------------------------------------------ the towns and the table

    /** The towns of this town's region (itself first): the same world, within reach. */
    public static List<Villages.Village> towns(Villages.Village v) {
        List<Villages.Village> out = new ArrayList<>();
        out.add(v);
        for (Villages.Village o : Villages.every()) {
            if (o.id().equals(v.id()) || !o.dim().equals(v.dim())) continue;
            double dx = o.centre().getX() - v.centre().getX(), dz = o.centre().getZ() - v.centre().getZ();
            if (dx * dx + dz * dz <= (double) REACH * REACH) out.add(o);
        }
        return out;
    }

    /** The towns this one is joined to by a made road (a path or better) or a railway. */
    public static int links(UUID town) {
        int n = 0;
        for (RoadWorks.Road r : RoadWorks.roads(town)) {
            UUID o = r.other(town);
            if (RoadWorks.grade(town, o) >= 1 || Railways.linkOpen(town, o)) n++;
        }
        return n;
    }

    /** The weekly run of a town's trade with all its neighbours. */
    static double trade(UUID town) {
        double t = 0;
        for (Villages.Village o : Diplomacy.neighboursOf(town)) t += Rivalry.tradeRun(town, o.id());
        return t;
    }

    /** One line of the league table. */
    public record Row(UUID town, int standing, int folk, int renown, int links, int trade) {}

    public static Row row(UUID town) {
        int folk = Villages.headcount(town), renown = Villages.renown(town), links = links(town);
        int trade = (int) Math.round(trade(town));
        int standing = 3 * folk + renown + 10 * Villages.ageOf(town).ordinal() + Ledger.coins(town) / 25 + 5 * links + trade / 4;
        return new Row(town, standing, folk, renown, links, trade);
    }

    /** The region's towns by standing, best first. */
    public static List<Row> table(Villages.Village v) {
        List<Row> out = new ArrayList<>();
        for (Villages.Village o : towns(v)) out.add(row(o.id()));
        out.sort(Comparator.comparingInt(Row::standing).reversed().thenComparing(r -> Villages.name(r.town())));
        return out;
    }

    // ------------------------------------------------------------------ the page

    /** The Region page's figures: the towns, the routes between them, the league table and the news. */
    public static CompoundTag report(ServerLevel level, Villages.Village v) {
        CompoundTag out = new CompoundTag();
        List<Villages.Village> towns = towns(v);
        ListTag ts = new ListTag();
        for (Villages.Village o : towns) {
            CompoundTag t = new CompoundTag();
            t.putString("name", Villages.name(o.id()));
            t.putInt("x", o.centre().getX());
            t.putInt("z", o.centre().getZ());
            t.putInt("folk", Villages.headcount(o.id()));
            t.putString("age", Villages.ageOf(o.id()).label);
            if (!o.id().equals(v.id())) {
                Rivalry.Stance s = Rivalry.stance(v.id(), o.id());
                t.putString("stance", s.words);
                t.putInt("colour", s.colour);
                t.putInt("rel", Ledger.relation(v.id(), o.id()));
                t.putInt("rivalry", Rivalry.score(v.id(), o.id()));
                t.putString("why", Rivalry.why(v.id(), o.id()));
                t.putBoolean("embargo", Rivalry.embargoed(v.id(), o.id()));
                t.putBoolean("pact", Envoys.pact(v.id(), o.id()));
            } else {
                t.putBoolean("me", true);
            }
            ts.add(t);
        }
        out.put("towns", ts);
        ListTag rs = new ListTag();
        List<String> done = new ArrayList<>();
        for (int i = 0; i < towns.size(); i++) {
            for (RoadWorks.Road r : RoadWorks.roads(towns.get(i).id())) {
                String k = RoadWorks.key(r.a(), r.b());
                if (done.contains(k)) continue;
                done.add(k);
                int ia = indexOf(towns, r.a()), ib = indexOf(towns, r.b());
                if (ia < 0 || ib < 0) continue;
                Routes.Info info = Routes.between(r.a(), r.b());
                if (info == null) continue;
                CompoundTag t = new CompoundTag();
                t.putInt("a", ia);
                t.putInt("b", ib);
                List<int[]> line = r.line();
                int every = Math.max(1, line.size() / 48);
                int[] pts = new int[2 * ((line.size() + every - 1) / every + 1)];
                int n = 0;
                for (int s = 0; s < line.size(); s += every) { pts[n++] = line.get(s)[0]; pts[n++] = line.get(s)[1]; }
                pts[n++] = line.get(line.size() - 1)[0];
                pts[n++] = line.get(line.size() - 1)[1];
                t.putIntArray("pts", java.util.Arrays.copyOf(pts, n));
                int[] ha = RoadWorks.halfState(r.a(), r.b()), hb = RoadWorks.halfState(r.b(), r.a());
                t.putIntArray("half", new int[]{ ha[0], ha[1], r.half(r.a()), hb[0], hb[1], r.half(r.b()) });
                t.putInt("grade", info.grade());
                Railways.Line rail = Railways.linkTo(r.a(), r.b());
                t.putInt("rail", info.rail() ? 2 : rail != null ? 1 : 0);
                if (rail != null) {
                    int[] rp = new int[2 * Math.min(48, rail.length())];
                    int step = Math.max(1, rail.length() / 48), m = 0;
                    for (int s = 0; s < rail.length() && m + 1 < rp.length; s += step) { rp[m++] = rail.rail(s).getX(); rp[m++] = rail.rail(s).getZ(); }
                    t.putIntArray("railPts", java.util.Arrays.copyOf(rp, m));
                }
                t.putBoolean("embargo", info.embargo());
                t.putInt("length", info.length());
                t.putInt("bridges", info.bridges());
                t.putInt("toll", info.toll());
                t.putInt("money", info.roadMoney());
                t.putLong("ticks", info.travelTicks());
                rs.add(t);
            }
        }
        out.put("routes", rs);
        ListTag lg = new ListTag();
        for (Row row : table(v)) {
            CompoundTag t = new CompoundTag();
            t.putString("name", Villages.name(row.town()));
            t.putInt("standing", row.standing());
            t.putInt("folk", row.folk());
            t.putInt("renown", row.renown());
            t.putInt("links", row.links());
            t.putInt("trade", row.trade());
            t.putBoolean("me", row.town().equals(v.id()));
            lg.add(t);
        }
        out.put("league", lg);
        ListTag ns = new ListTag();
        for (String s : news(v.id())) ns.add(StringTag.valueOf(s));
        out.put("news", ns);
        out.putString("name", Villages.name(v.id()));
        return out;
    }

    private static int indexOf(List<Villages.Village> towns, UUID id) {
        for (int i = 0; i < towns.size(); i++) if (towns.get(i).id().equals(id)) return i;
        return -1;
    }

    /** "3 hours", "40 minutes": a travel time in the game's own hours (a day is twenty-four). */
    public static String time(long ticks) {
        double hours = ticks / 1000.0;
        if (hours >= 1.5) return Math.round(hours) + " hours";
        int minutes = (int) Math.max(1, Math.round(hours * 60));
        return minutes + " minutes";
    }

    /** The region in words, for the chat. */
    public static List<String> lines(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        List<Villages.Village> towns = towns(v);
        out.add("Towns known: " + (towns.size() - 1) + " within " + REACH + " blocks.");
        for (Villages.Village o : towns) {
            if (o.id().equals(v.id())) continue;
            String rel = Rivalry.stance(v.id(), o.id()).words;
            int score = Rivalry.score(v.id(), o.id());
            String extra = (Rivalry.embargoed(v.id(), o.id()) ? ", under embargo" : "") + (Envoys.pact(v.id(), o.id()) ? ", trade pact" : "")
                + (score > 0 ? ", rivalry " + score + (Rivalry.why(v.id(), o.id()).isEmpty() ? "" : " (" + Rivalry.why(v.id(), o.id()) + ")") : "");
            out.add("  " + Villages.name(o.id()) + ": " + rel + " (relation " + Ledger.relation(v.id(), o.id()) + extra + ")");
        }
        for (Routes.Info i : Routes.from(v.id())) {
            UUID o = i.a().equals(v.id()) ? i.b() : i.a();
            int[] mine = RoadWorks.halfState(v.id(), o);
            out.add("Road to " + Villages.name(o) + ": " + i.length() + " blocks, " + i.gradeWords()
                + (i.bridges() > 0 ? ", bridging " + i.bridges() + " of water" : "") + (i.rail() ? ", railway open" : Railways.linkTo(v.id(), o) != null ? ", railway being laid" : "")
                + "; going takes " + time(i.travelTicks()) + ", a load's road money " + i.roadMoney() + (i.toll() > 0 ? " (toll " + i.toll() + ")" : "")
                + (i.embargo() ? "; EMBARGO" : "") + (mine[1] > 0 ? "; our gang " + mine[1] + " steps on toward the next grade" : ""));
        }
        out.add("League table:");
        int n = 1;
        for (Row r : table(v)) {
            out.add("  " + n++ + ". " + Villages.name(r.town()) + " " + r.standing() + " (folk " + r.folk() + ", renown " + r.renown()
                + ", links " + r.links() + ", trade " + r.trade() + ")" + (r.town().equals(v.id()) ? " <" : ""));
        }
        List<String> news = news(v.id());
        if (!news.isEmpty()) {
            out.add("News:");
            for (int i = 0; i < Math.min(6, news.size()); i++) out.add("  " + news.get(i));
        }
        return out;
    }

    // ------------------------------------------------------------------ the command

    /** /village region: the region in the chat; {@code books} opens the town's books at the Region page. */
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("region")
            .executes(Region::cmdPage)
            .then(Commands.literal("books").executes(Region::cmdBooks))
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2)).executes(ctx -> {
                List<String> out = RegionStage.stage(ctx.getSource().getLevel(), BlockPos.containing(ctx.getSource().getPosition()));
                ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", out)), false);
                return out.size();
            }).then(Commands.literal("rail").executes(ctx -> {
                List<String> out = RegionStage.stageRail(ctx.getSource().getLevel(), here(ctx));
                ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", out)), false);
                return out.size();
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
        List<String> lines = lines(ctx.getSource().getLevel(), v);
        String text = "REGION round " + Villages.name(v.id()) + "\n" + String.join("\n", lines);
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return lines.size();
    }

    private static int cmdBooks(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) return cmdPage(ctx);
        CompoundTag books = Annals.snapshot(ctx.getSource().getLevel(), v);
        books.putString("page", "Region");
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.jrpetty.mcassistant.net.CityStatsPayload(books));
        return 1;
    }
}
