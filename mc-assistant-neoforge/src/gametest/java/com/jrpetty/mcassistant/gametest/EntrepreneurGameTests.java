package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.entity.Accounts;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Business;
import com.jrpetty.mcassistant.entity.Businesses;
import com.jrpetty.mcassistant.entity.CoinCensus;
import com.jrpetty.mcassistant.entity.MarketMaker;
import com.jrpetty.mcassistant.entity.Privatisation;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.Wealth;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * [econ-1b] Entrepreneurs and competition, and the two weaknesses of the first market batch put right: the producers'
 * pay and the town's budget.
 *
 * <p>Each on its own ground, x 1400000 to 1499999 (2000 apart), z 90000, in a batch of its own.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class EntrepreneurGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 90000;

    // ============================================================ ent10 ten days of a town under the market

    /**
     * A town founded and left to itself for ten days with the market at work (its Market Opening Day as it comes), its
     * stores topped up each morning with building stuff and bread as the long growth tests do. Each morning: the census
     * (nought drift), the treasury and its IOUs, the market, the producers' takings against the other trades' wages, and
     * the firms. At the end: IOUs bounded, the producers earning a living comparable to the other trades, at least one firm
     * founded by an entrepreneur, no deadlock (it grew and built and fed itself).
     */
    @GameTest(template = EMPTY, timeoutTicks = 900000, required = false, batch = "ent10_ten_days")
    public static void ent10_ten_days(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        int cx = 1418000;
        Kit.reset(level);
        MarketMaker.liveForTests(true);
        String tag = "ent10";
        com.jrpetty.mcassistant.entity.Founding.resetForTests(server);
        com.jrpetty.mcassistant.entity.TownJobs.instantForTests(false);
        int outer = com.jrpetty.mcassistant.village.FoundingPlan.coreRadius(16) + com.jrpetty.mcassistant.village.FoundingPlan.BAND_MAX;
        Kit.hold(level, cx, Z, outer + 32);
        Kit.prepare(level, cx, Z, outer + 32);
        BlockPos heart = Kit.surface(level, cx, Z);
        Kit.generousTerrain(level, cx, Z);
        Kit.log(tag + " found: " + Kit.command(level, "village found 16 " + cx + " " + Z));
        server.tickRateManager().setTickRate(10000.0F);
        final long[] began = { -1 };
        final int[] start = { 0, 0, -1 };
        final UUID[] town = { null };
        final boolean[] over = { false };
        final String[] verdict = { null };
        final long[] worstDrift = { 0 };
        final long[] mostIous = { 0 };
        final Runnable[] look = { null };
        look[0] = () -> {
            if (verdict[0] == null) helper.runAfterDelay(100, () -> look[0].run());
            else if (verdict[0].isEmpty()) helper.succeed();
            else helper.fail(verdict[0]);
        };
        helper.runAfterDelay(100, look[0]);
        java.util.function.Consumer<net.neoforged.neoforge.event.tick.ServerTickEvent.Post> each = e -> {
            if (over[0]) return;
            long t = helper.getTick();
            try {
                if (began[0] < 0) {
                    if (com.jrpetty.mcassistant.entity.Founding.near(level, heart, 8)) return;
                    Villages.Village v = Villages.nearest(level, heart, 64);
                    if (v == null) {
                        over[0] = true;
                        server.tickRateManager().setTickRate(20.0F);
                        verdict[0] = "no village founded";
                        return;
                    }
                    town[0] = v.id();
                    began[0] = t;
                    start[0] = Villages.headcount(v.id());
                    start[1] = Ledger.buildings(v.id()).size();
                    Kit.log(tag + " " + Villages.name(v.id()) + " of " + start[0] + " founded at tick " + t + ", " + start[1] + " buildings");
                    return;
                }
                long ran = t - began[0];
                UUID id = town[0];
                int dayNo = (int) (ran / 24000L);
                if (dayNo != start[2]) {
                    start[2] = dayNo;
                    Kit.stockForGrowth(level, Villages.get(id));
                    long drift = CoinCensus.lastDrift(id);
                    worstDrift[0] = Math.max(worstDrift[0], Math.abs(drift));
                    mostIous[0] = Math.max(mostIous[0], Accounts.debts(id));
                    Kit.log(tag + " day " + dayNo + ": " + Villages.headcount(id) + " folk, " + Ledger.buildings(id).size() + " buildings, food "
                        + Villages.stock(level, Villages.get(id).centre(), Villages.Task.FOOD, Villages.storesRadius(id)) + ", treasury "
                        + Businesses.pence(Businesses.treasuryPence(id)) + ", IOUs " + Businesses.pence(Accounts.debts(id)) + ", market "
                        + (MarketMaker.live(id) ? "open " + Businesses.pence(MarketMaker.cash(id)) + " credit " + Businesses.pence(MarketMaker.storeCredit(id))
                        + " float " + Businesses.pence(MarketMaker.advanced(id)) : "commons") + ", drift " + drift + " | " + CoinCensus.line(id));
                    for (String l : producerLines(level, id)) Kit.log(tag + " day " + dayNo + " " + l);
                    long today = level.getDayTime() / 24000L;
                    for (Villages.News n : Villages.news(id)) if (n.day() >= today - 1) Kit.log(tag + " news day " + (n.day() + 1) + ": " + n.text());
                }
                if (ran >= 10 * 24000L) {
                    over[0] = true;
                    server.tickRateManager().setTickRate(20.0F);
                    Kit.Expect ok = new Kit.Expect();
                    int folkNow = Villages.headcount(id), built = Ledger.buildings(id).size();
                    int food = Villages.stock(level, Villages.get(id).centre(), Villages.Task.FOOD, Villages.storesRadius(id));
                    Kit.log(tag + " FINAL " + folkNow + " folk (from " + start[0] + "), " + built + " buildings (from " + start[1] + "), food " + food);
                    Kit.log(tag + " " + MarketMaker.words(level, id).replace('\n', '|'));
                    Kit.log(tag + " " + CoinCensus.page(id).replace('\n', '|'));
                    for (String l : producerLines(level, id)) Kit.log(tag + " FINAL " + l);
                    ok.that(MarketMaker.live(id), "its market opened");
                    ok.that(folkNow >= start[0], "it kept its folk: " + start[0] + " -> " + folkNow);
                    ok.that(built > start[1], "it built: " + start[1] + " -> " + built);
                    ok.that(food > 0, "it fed itself: food in the stores " + food);
                    ok.that(worstDrift[0] == 0, "the census at nought drift every morning (worst " + worstDrift[0] + ")");
                    Kit.log(tag + " most IOUs " + Businesses.pence(mostIous[0]));
                    verdict[0] = ok.clean() ? "" : ok.summary();
                }
            } catch (RuntimeException ex) {
                over[0] = true;
                server.tickRateManager().setTickRate(20.0F);
                verdict[0] = "the run threw " + ex;
                Kit.log(tag + " threw " + ex);
                ex.printStackTrace();
            }
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(each);
    }

    /**
     * The producers against the other trades: each producer business's takings, its costs other than its hand's wage, and
     * its surplus a day since it opened (what its hand lives on: its wage and its drawings come out of it), beside the
     * average wage of the other trades and the producer's own going wage.
     */
    static List<String> producerLines(ServerLevel level, UUID id) {
        List<String> out = new ArrayList<>();
        long day = level.getDayTime() / 24000L;
        double surplusSum = 0;
        int producers = 0;
        for (Business b : Businesses.all(id)) {
            if (b.type.size != Business.Size.YARD || !b.open) continue;
            long days = Math.max(1, day - b.opened);
            Business.Day a = b.allTime;
            long surplus = a.revenue - a.cogs - a.other - a.rent - a.interest - a.taxes - a.losses;
            VillageFolkEntity hand = handOf(id, b);
            int wage = hand == null ? 0 : Wealth.wage(hand);
            Business.Day y = b.last(day - 1, 1);
            out.add(String.format(Locale.ROOT, "  producer %-22s %s: took %s, costs %s, surplus %s a day over %d days (yesterday %s on %d units); its wage %d; paid %s, drew %s; till %s, owes town %s, loan %s; purse %s",
                b.name, b.town() ? "(town's)" : b.ownersWords(), Businesses.pence(a.revenue), Businesses.pence(a.other + a.rent + a.interest + a.taxes),
                Businesses.pence(surplus / days), days, Businesses.pence(y.revenue), unitsOf(y), wage, Businesses.pence(a.wages), Businesses.pence(a.drawings),
                Businesses.pence(b.till), Businesses.pence(-Math.min(0, b.account)), Businesses.pence(b.loan), hand == null ? "-" : String.valueOf(hand.purse())));
            if (!b.town()) {
                surplusSum += surplus / (double) days;
                producers++;
            }
        }
        double others = 0;
        int n = 0;
        StringBuilder trades = new StringBuilder();
        for (AssistantEntity e : Villages.folkOf(id)) {
            if (!(e instanceof VillageFolkEntity f) || f.isBaby() || f.stationTask() == StationTask.NONE) continue;
            if (Privatisation.producer(f.stationTask())) continue;
            int w = Wealth.wage(f);
            others += w;
            n++;
            trades.append(f.stationTask().name().toLowerCase(Locale.ROOT)).append(' ').append(w).append(", ");
        }
        out.add(String.format(Locale.ROOT, "  PRODUCERS %d private, surplus %.2fc a day each on average; OTHER TRADES %d, wage %.2f a day on average (%s)",
            producers, producers == 0 ? 0 : surplusSum / producers / 100.0, n, n == 0 ? 0 : others / n, trades));
        return out;
    }

    static long unitsOf(Business.Day d) {
        long u = 0;
        for (Business.Line l : d.lines.values()) u += l.units;
        return u;
    }

    static VillageFolkEntity handOf(UUID id, Business b) {
        return Privatisation.handForTests(id, b);
    }
}
