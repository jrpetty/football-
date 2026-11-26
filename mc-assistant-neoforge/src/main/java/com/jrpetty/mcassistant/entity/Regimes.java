package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * [featcivic] What each form of government changes, concretely: who owns the workshops, who may open a business, own a
 * house, vote and speak; what the taxes, wages and prices are; how the laws are made; what a player can and cannot do
 * there; and what each form is good at and bad at. One table ({@link #rules}) the board's comparison page, the Town
 * Hall and a folk's talk all read, and the few hooks the rest of the mod asks (a business barred in a planned state, the
 * state's taxes, the general's strong watch, the government's flag in the square).
 *
 * <p>No form is put forward as the right one. Each has strengths a town of a certain character values and costs another
 * town would not bear: an elected democracy is steady and slow, a planned state equal and stiff, military rule safe and
 * cheerless, a lordship settled and unfair.
 */
public final class Regimes {

    private Regimes() {}

    /** One form's rules, for the board and the hall: words only, each a short plain line. */
    public record Rules(String ownership, String business, String homes, String vote, String speech, String market, String wages,
                        String taxes, String laws, String style, List<String> strengths, List<String> weaknesses, List<String> player) {}

    public static Rules rules(Government.Form f) {
        return switch (f) {
            case DEMOCRACY -> new Rules("Private: folk and players own their workshops and shops",
                "Anybody in good standing may open one", "Anybody in good standing may own a house",
                "Every grown folk and every citizen, every ten days, by secret ballot", "Free: parties, posters, speeches in the square",
                "An open market: prices set by trade", "Set by the market; the mayor may lean them", "Voted by the council each week",
                "Voted by the elected town council, by majority", "Slow, argued, steady: policy follows the winning party",
                List.of("Peaceful handover of power", "Unrest is let out at the polls", "Folk feel heard"),
                List.of("Slow to decide", "Policy swings with each election", "Campaigns take folk from work"),
                List.of("Vote at the ballot box (citizens)", "Stand for mayor (a citizen the town counts a friend)", "Open a business", "Own a house"));
            case REEVE -> new Rules("Private", "Anybody in good standing may open one", "Anybody in good standing (householders only, in a hierarchical town)",
                "Every grown folk elects the leader every ten days", "Free", "An open market", "Set by the market", "Voted by the council each week",
                "Decreed by the elected leader", "Quick and personal: one elected leader decides",
                List.of("Decisions come quickly", "Peaceful handover of power"),
                List.of("Much rests on one person", "No standing council to check the leader"),
                List.of("Vote at the ballot box (citizens)", "Stand for leader", "Open a business", "Own a house"));
            case COUNCIL -> new Rules("Private", "Anybody in good standing", "Anybody in good standing", "Only the eldest five, from among themselves",
                "Free, but the elders listen to their own", "An open market", "Set by the market", "Voted by the council",
                "Voted by the council of elders", "Cautious: orders kept for longer",
                List.of("Steady, experienced hands", "Few sudden changes"), List.of("The young have no say", "Slow to take up new ideas"),
                List.of("Stand before the elders (an honoured guest)", "Open a business", "Own a house"));
            case LORD -> new Rules("Private, with dues to the lord", "Anybody in good standing", "Anybody; the hunting is the lord's",
                "Nobody votes for the lord", "Guarded: speak ill of the lord at your peril", "An open market, with heavier dues",
                "Spread wide by rank", "The lord's word counts three times on the council", "Decreed by the lord",
                "Settled and personal: the line succeeds",
                List.of("No campaigns: everyone knows who rules", "Folk used to the dues grumble less"),
                List.of("No say for the folk", "A bad lord rules until deposed"),
                List.of("Marry into the ruling house", "Open a business", "Own a house"));
            case GUILD -> new Rules("Private: the masters' workshops", "Anybody in good standing; the masters have the say",
                "Anybody in good standing", "Only the masters of the trades", "Free among the masters", "An open market, the guild's prices",
                "Set by the market", "Voted by the council", "Each trade votes on its own matters", "Businesslike: the market first",
                List.of("Trade thrives", "Those who know a trade decide on it"), List.of("Apprentices and labourers have no vote", "Little for those outside the trades"),
                List.of("Stand before the masters (an honoured guest)", "Open a business", "Own a house"));
            case COMMUNE -> new Rules("Shared: the town's", "Anybody in good standing", "Nobody: the houses are the town's",
                "Everybody, on everything big", "Free: the assembly hears all", "An open market", "Everybody paid the same", "Voted by the council",
                "Every change put to the whole town", "Open and slow: everything goes to a vote",
                List.of("All are equal", "Nothing big is done without the town"), List.of("Votes on everything are slow", "The ambitious chafe at equal pay"),
                List.of("Vote at the ballot box (citizens)", "Stand for steward", "Open a business"));
            case CHAPLAIN -> new Rules("Private", "Anybody in good standing", "Anybody in good standing", "Nobody votes for the chaplain",
                "Guarded on matters of the chapel", "An open market", "Set by the market", "Voted by the council", "Decreed by the chaplain",
                "Devout: the day of rest kept strictly, feasts mid-week",
                List.of("A shared faith holds the town together", "Feasts and rest days"), List.of("The worldly chafe", "No say for the folk"),
                List.of("Open a business", "Own a house"));
            case PLANNED -> new Rules("The state's: the committee runs the workshops; no new private firm (old ones wind down)",
                "No private business: the state runs them all", "Nobody: the committee assigns the houses",
                "Nobody votes: the party congress appoints the chair", "Guarded: criticism goes through the committee",
                "No market: rations and state shops; quotas set each week", "Equal: one wage for every hand",
                "No market toll and no profits tax (there are no profits); a heavier share of wages", "Voted by the committee",
                "Planned: quotas, assigned jobs, equal pay",
                List.of("Nobody goes without: equal pay and rations", "Work is assigned, so nothing is left undone", "Little crime against property"),
                List.of("No private business at all", "Shortages when the plan is wrong", "No say for the folk; dissent builds quietly"),
                List.of("Work for the state: the jobs board", "You cannot open a private business here", "You cannot own a house here"));
            case JUNTA -> new Rules("Private, under watch", "Anybody, with the general's leave", "Anybody in good standing",
                "Nobody votes", "Restricted: no gatherings without leave", "An open market under the watch's eye",
                "Set by the market; the watch is paid first", "A standing levy for the watch, war or not",
                "Decreed by the general", "Orderly and strict: a curfew, a strong watch",
                List.of("Safe streets: a strong watch and a curfew", "Quick decisions in a crisis"),
                List.of("No say for the folk", "Curfews and checks wear folk down", "A coup can be undone the same way"),
                List.of("Open a business (under watch)", "Own a house", "No vote and no candidacy"));
        };
    }

    /** "the planned state", "military rule": a short name for lists. */
    public static String shortName(Government.Form f) {
        return switch (f) {
            case DEMOCRACY -> "Democracy";
            case REEVE -> "Elected leader";
            case COUNCIL -> "Council of elders";
            case LORD -> "Lordship";
            case GUILD -> "Guild republic";
            case COMMUNE -> "Commune";
            case CHAPLAIN -> "Chaplain's rule";
            case PLANNED -> "Planned state";
            case JUNTA -> "Military rule";
        };
    }

    /** The colour of a form's flag in the square (and its row on the board). */
    public static DyeColor colour(Government.Form f) {
        return switch (f) {
            case DEMOCRACY -> DyeColor.LIGHT_BLUE;
            case REEVE -> DyeColor.GREEN;
            case COUNCIL -> DyeColor.BROWN;
            case LORD -> DyeColor.PURPLE;
            case GUILD -> DyeColor.YELLOW;
            case COMMUNE -> DyeColor.LIME;
            case CHAPLAIN -> DyeColor.WHITE;
            case PLANNED -> DyeColor.ORANGE;
            case JUNTA -> DyeColor.GRAY;
        };
    }

    // ------------------------------------------------------------------ the hooks

    /** Why no private business may be opened in this town now (the charter, a folk's own venture), or null if it may. */
    @Nullable
    public static String businessBarred(@Nullable UUID village) {
        if (village == null || Identity.neutral() || Government.of(village) != Government.Form.PLANNED) return null;
        return "In a planned state you cannot open a private business: the committee owns every workshop in " + Villages.name(village)
            + ". You can work for the state instead (the jobs board), or wait for a reform.";
    }

    /** The state's taxes: a planned state takes no toll and no profits tax (no market, no profits); military rule keeps a levy. */
    public static int taxRate(@Nullable UUID village, TaxBook.Lever l, int r) {
        if (village == null || Identity.neutral()) return r;
        Government.Form f = Government.of(village);
        if (f == Government.Form.PLANNED && (l == TaxBook.Lever.TOLL || l == TaxBook.Lever.PROFITS || l == TaxBook.Lever.RATES)) return 0;
        if (f == Government.Form.JUNTA && l == TaxBook.Lever.LEVY) return Math.max(1, r);
        return r;
    }

    /** Can the council move this tax at all under this form? */
    public static boolean taxFixed(@Nullable UUID village, TaxBook.Lever l) {
        return taxRate(village, l, 2) != 2 || taxRate(village, l, 0) != 0;
    }

    /** The law-book a form wants, on top of the town's character (LawBook.desired). */
    static void laws(Government.Form g, int[] axes, int[] want) {
        int d = axes[Ethos.Axis.DOORS.ordinal()];
        switch (g) {
            case JUNTA -> {
                want[LawBook.Law.CURFEW.ordinal()] = 1;
                want[LawBook.Law.WEAPONS.ordinal()] = 1;
                want[LawBook.Law.CONSCRIPTION.ordinal()] = 1;
                if (d < 20) want[LawBook.Law.BORDERS.ordinal()] = 1;
                want[LawBook.Law.TITHE.ordinal()] = Math.max(want[LawBook.Law.TITHE.ordinal()], 2);
            }
            case PLANNED -> {
                want[LawBook.Law.HOUSES.ordinal()] = 2;
                want[LawBook.Law.TITHE.ordinal()] = 2;
                want[LawBook.Law.TARIFF.ordinal()] = 1;
                want[LawBook.Law.HUNTING.ordinal()] = 0;
                want[LawBook.Law.APPRENTICE.ordinal()] = Math.max(1, want[LawBook.Law.APPRENTICE.ordinal()]);
            }
            case DEMOCRACY -> {
                want[LawBook.Law.HOUSES.ordinal()] = 0;
                if (want[LawBook.Law.WEAPONS.ordinal()] == 1 && axes[Ethos.Axis.WAR.ordinal()] > -50) want[LawBook.Law.WEAPONS.ordinal()] = 0;
            }
            default -> { }
        }
    }

    /** More hands on a trade: the general's watch half again as strong; the plan's fields a tenth more. */
    public static double shareLean(@Nullable UUID village, StationTask t) {
        if (village == null || Identity.neutral()) return 1.0;
        Government.Form f = Government.of(village);
        if (f == Government.Form.JUNTA && t == StationTask.GUARD) return 1.5;
        if (f == Government.Form.PLANNED && t == StationTask.FARM) return 1.1;
        return 1.0;
    }

    /** What its form does to the town's spirits, for the new forms (Government.contentment). */
    static int contentment(UUID village, Government.Form f, List<String> good, List<String> bad) {
        int rank = Ethos.lean(village, Ethos.Axis.RANK), war = Ethos.lean(village, Ethos.Axis.WAR), doors = Ethos.lean(village, Ethos.Axis.DOORS),
            trade = Ethos.lean(village, Ethos.Axis.TRADE);
        switch (f) {
            case DEMOCRACY -> {
                if (doors >= 0) { good.add("our say at the ballot box"); return 2; }
                return 1;
            }
            case PLANNED -> {
                if (rank >= Ethos.POLE) { good.add("everyone paid alike"); return 2; }
                if (trade >= Ethos.POLE) { bad.add("no business of our own"); return -3; }
                bad.add("the committee decides everything");
                return -1;
            }
            case JUNTA -> {
                if (war >= Ethos.POLE) { good.add("a strong watch"); return 1; }
                bad.add(war <= -Ethos.POLE ? "soldiers on every corner" : "the general's curfew");
                return war <= -Ethos.POLE ? -4 : -2;
            }
            default -> { return 0; }
        }
    }

    // ------------------------------------------------------------------ who rules, where nobody votes

    /** The committee's choice of chair: an esteemed, egalitarian-minded, hard-working folk. */
    @Nullable
    static VillageFolkEntity chairChoice(UUID village, @Nullable UUID not) {
        VillageFolkEntity best = null;
        double top = Double.NEGATIVE_INFINITY;
        List<VillageFolkEntity> council = Council.members(village);
        for (VillageFolkEntity f : Identity.grown(village)) {
            if (f.getUUID().equals(not)) continue;
            double s = Ethos.folkLean(f)[Ethos.Axis.RANK.ordinal()] + (council.contains(f) ? 15 : 0)
                + (f.life().has(Social.Trait.HARDWORKING) ? 8 : 0) + Math.min(10, f.veteranLevel() / 2.0);
            if (s > top) { top = s; best = f; }
        }
        return best;
    }

    /** The senior officer: the guard of longest service, else the most martial-minded grown folk. */
    @Nullable
    static VillageFolkEntity generalChoice(UUID village, @Nullable UUID not) {
        VillageFolkEntity best = null;
        double top = Double.NEGATIVE_INFINITY;
        for (VillageFolkEntity f : Identity.grown(village)) {
            if (f.getUUID().equals(not)) continue;
            double s = (f.stationTask() == StationTask.GUARD ? 100 + f.veteranLevel() : 0) + Values.weight(f, Values.Value.SAFETY) * 0.5
                + (f.life().has(Social.Trait.GRUMPY) ? 4 : 0);
            if (s > top) { top = s; best = f; }
        }
        return best;
    }

    /** A planned state's committee: the chair and the four most taken with the plan (larder and homes for all). */
    static List<VillageFolkEntity> committee(@Nullable UUID village, List<VillageFolkEntity> all) {
        UUID chair = village == null ? null : Villages.elder(village);
        List<VillageFolkEntity> out = new ArrayList<>(all);
        out.sort(Comparator.comparingDouble((VillageFolkEntity f) -> -((f.getUUID().equals(chair) ? 1000 : 0)
            + Values.weight(f, Values.Value.FOOD) + Values.weight(f, Values.Value.HOMES) + 0.5 * Ethos.folkLean(f)[Ethos.Axis.RANK.ordinal()])));
        return new ArrayList<>(out.subList(0, Math.min(5, out.size())));
    }

    /** The general's officers: the watch (and the general); three at least, the most martial-minded making up the number. */
    static List<VillageFolkEntity> officers(@Nullable UUID village, List<VillageFolkEntity> all) {
        UUID general = village == null ? null : Villages.elder(village);
        List<VillageFolkEntity> out = new ArrayList<>(all);
        out.sort(Comparator.comparingDouble((VillageFolkEntity f) -> -((f.getUUID().equals(general) ? 1000 : 0)
            + (f.stationTask() == StationTask.GUARD ? 200 : 0) + Values.weight(f, Values.Value.SAFETY))));
        int n = 0;
        for (VillageFolkEntity f : out) if (f.stationTask() == StationTask.GUARD || f.getUUID().equals(general)) n++;
        return new ArrayList<>(out.subList(0, Math.min(Math.max(3, n), out.size())));
    }

    // ------------------------------------------------------------------ a change of government, in the square

    /**
     * The town has taken up another form (Government.set): its flag by the board comes down and the new one goes up, the
     * old order's posters are torn down, its election machinery is put away or set up, and the chronicle has it.
     */
    static void changed(ServerLevel level, Villages.Village v, Government.Form was, Government.Form to, long day) {
        UUID id = v.id();
        if (was == to) return;
        com.jrpetty.mcassistant.Guard.run("the government's flag", () -> raiseFlag(level, v, to, true));
        com.jrpetty.mcassistant.Guard.run("the old posters", () -> Polls.tearDownAll(level, v, "the old order's posters and notices"));
        Ledger.note(id, "gov.notices", "");
        Ledger.note(id, "gov.changed", day + "|" + was.name() + "|" + to.name());
        Factions.reset(id);
        Dissent.formChanged(level, v, was, to, day);
        if (to == Government.Form.PLANNED) {
            Villages.tell(id, day, Villages.name(id) + " is a planned state now: no new private business is chartered, the committee runs the "
                + "town's workshops, assigns the jobs and the houses, and pays every hand the same");
        } else if (was == Government.Form.PLANNED) {
            Villages.tell(id, day, "the committee's workshops are free to change hands again: anybody in good standing may open a business");
        }
        if (to == Government.Form.JUNTA) Villages.tell(id, day, "the general's curfew is read out in the square, and the watch is doubled");
        if (to == Government.Form.DEMOCRACY && Government.appointed(was)) {
            Villages.tell(id, day, "the town will elect a mayor and a council: the first election is called, and the parties are forming");
        }
        Market.assemblyNews(id, "Hear this: " + Villages.name(id) + " is " + to.words + " from today. " + capital(rules(to).style) + ".");
    }

    /** An unelected government's notices round the square, put up once a form (Politics, the morning). */
    static void notices(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        Government.Form f = Government.of(id);
        if (!Government.appointed(f)) return;
        String done = Ledger.note(id, "gov.notices");
        if (f.name().equals(done)) return;
        String leader = Villages.elderName(id);
        Identity.Rec r = Identity.known(id);
        String[] lines = switch (f) {
            case JUNTA -> new String[]{ "BY ORDER OF", leader.isEmpty() ? "THE GENERAL" : "General " + leader, "Curfew at dusk", "Keep the peace" };
            case PLANNED -> new String[]{ "THE PLAN", "Work for all", "Quotas by", "the hall door" };
            case LORD -> new String[]{ "BY COMMAND OF", r == null || r.house.isEmpty() ? "THE LORD" : r.house.replace("the House of ", "House "), "Dues on", "tax day" };
            default -> new String[]{ "THE CHAPEL", "Rest day", "kept strictly", leader };
        };
        if (Polls.placeNotices(level, v, lines, colour(f), 3) > 0) Ledger.note(id, "gov.notices", f.name());
    }

    /** The government's flag beside the board: the form's colour on a post. Returns where it stands, or null. */
    @Nullable
    public static BlockPos raiseFlag(ServerLevel level, Villages.Village v, Government.Form f, boolean tearOld) {
        UUID id = v.id();
        BlockPos old = parse(Ledger.note(id, "gov.flag"));
        if (old != null && level.isLoaded(old)) {
            BlockState s = level.getBlockState(old);
            if (s.getBlock() instanceof net.minecraft.world.level.block.BannerBlock) {
                if (tearOld) {
                    level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, s), old.getX() + 0.5, old.getY() + 1.0, old.getZ() + 0.5,
                        30, 0.3, 0.6, 0.3, 0.1);
                    level.playSound(null, old, SoundEvents.WOOL_BREAK, SoundSource.BLOCKS, 1.0F, 0.8F);
                }
                level.setBlock(old, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        BlockPos at = flagSpot(level, v);
        if (at == null) return null;
        Block banner = net.minecraft.world.level.block.BannerBlock.byColor(colour(f));
        level.setBlock(at, banner.defaultBlockState(), Block.UPDATE_ALL);
        level.playSound(null, at, SoundEvents.WOOL_PLACE, SoundSource.BLOCKS, 1.0F, 1.1F);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, at.getX() + 0.5, at.getY() + 1.5, at.getZ() + 0.5, 12, 0.4, 0.6, 0.4, 0.0);
        Ledger.note(id, "gov.flag", at.getX() + "," + at.getY() + "," + at.getZ());
        return at;
    }

    /** Beside the board (or the town's heart): open ground with air above, a block or two to the side. */
    @Nullable
    static BlockPos flagSpot(ServerLevel level, Villages.Village v) {
        BlockPos board = VillageBoards.lectern(v.id());
        BlockPos base = board != null ? board : v.centre();
        Direction face = board != null ? VillageBoards.facingOf(v.id()) : Direction.SOUTH;
        if (face == null) face = Direction.SOUTH;
        Direction side = face.getClockWise();
        for (int d : new int[]{ 2, -2, 3, -3, 4, -4 }) {
            BlockPos col = base.relative(side, d);
            BlockPos p = Polls.groundAt(level, col);
            if (p != null) return p;
        }
        return null;
    }

    @Nullable
    public static BlockPos parse(@Nullable String s) {
        if (s == null || s.isEmpty()) return null;
        String[] p = s.split(",");
        if (p.length < 3) return null;
        try {
            return new BlockPos(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String capital(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
