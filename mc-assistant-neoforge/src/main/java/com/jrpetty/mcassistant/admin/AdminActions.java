package com.jrpetty.mcassistant.admin;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AdminTools;
import com.jrpetty.mcassistant.entity.AdminTools.Done;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.CityTree;
import com.jrpetty.mcassistant.entity.FolkSkills;
import com.jrpetty.mcassistant.entity.TownAudit;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.village.AdminSpec;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * [admin] What each admin action (AdminSpec) does, bound to the service (entity/AdminTools), and what it would do
 * (for a dry run and a confirm). Small actions run at once; big ones start a job (AdminJobs) whose slices are bounded
 * (a few folk, one building, one day a tick) and whose end writes the "By decree" line with what really happened.
 */
public final class AdminActions {

    private AdminActions() {}

    /** Folk raised a tick by an admin job: a founding party's pace, so five hundred take about six seconds. */
    static final int FOLK_PER_TICK = 4;
    /** Folk worked through a tick (knacks, kit). */
    static final int FOLK_WORK_PER_TICK = 10;
    /** Ticks between the days of a time-warp: a day every half second. */
    static final int WARP_TICKS = 10;
    /** At most this many buildings put up by one "build all" (the town's list grows as it builds). */
    static final int MOST_BUILDS = 40;
    /** The least headcount each age is sized for, when an age is set with "fill". */
    static final int[] AGE_FOLK = { 5, 15, 30, 50, 70 };
    /** How long a founding is waited for before its age is set anyway (ticks: three minutes). */
    static final int FOUNDING_WAIT = 3600;

    private static final Random RNG = new Random();

    // ------------------------------------------------------------------ previews

    /** What the action would do to this town (null: the world), changing nothing. */
    static String preview(AdminRunner.Ctx c, @Nullable Villages.Village v) {
        String town = v == null ? "the world" : Villages.name(v.id());
        var a = c.args();
        return switch (c.action().id()) {
            case "people.add", "people.trade", "people.children", "people.elders" -> {
                int n = a.num(c.action().id().equals("people.trade") ? 1 : 0, 1);
                yield "add " + n + " folk to " + town + " (" + Villages.headcount(v.id()) + " -> " + (Villages.headcount(v.id()) + n)
                    + "); beds for " + Villages.housing(v.id()) + ", so about " + Math.max(0, Villages.headcount(v.id()) + n - Villages.housing(v.id()))
                    + " camp beds and the houses for them queued" + entityNote(c, n);
            }
            case "people.couples" -> "add " + 2 * a.num(0, 1) + " folk to " + town + " as " + a.num(0, 1) + " pledged couples";
            case "people.charter" -> "bring " + town + " from " + Villages.headcount(v.id()) + " to " + charter(c) + " folk";
            case "people.remove" -> "send " + Math.min(a.num(0, 1), AdminTools.folk(v.id()).size()) + " of " + town + "'s " + Villages.headcount(v.id())
                + " folk away (the ones with least to keep them)";
            case "people.kill" -> "kill " + AdminRunner.folk(c, v).size() + " folk of " + town + " (graves, mourning)";
            case "economy.empty" -> "empty " + Villages.storeChests(c.level(), v.id()).size() + " store chest(s) of " + town + " ("
                + AdminTools.storesCount(c.level(), v) + " items)";
            case "economy.coin" -> "treasury of " + town + ": " + Ledger.coins(v.id()) + " -> " + Math.max(0, Ledger.coins(v.id()) + a.num(0, 0));
            case "ages.set", "ages.next" -> {
                Villages.Age to = c.action().id().equals("ages.next") ? AdminTools.next(Villages.ageOf(v.id())) : AdminTools.age(a.word(0, "wood"));
                yield to == null ? town + " is in the last age already" : town + ": " + Villages.ageOf(v.id()).label + " -> " + to.label + ", "
                    + Ledger.buildings(v.id()).size() + " buildings to renovate (" + a.word(1, "queued") + "), the age's stock into the stores, tools brought up";
            }
            case "build.all" -> town + " would build: " + String.join(", ", Villages.projectsWanted(v.id()));
            case "build.finish" -> town + " would finish: " + (AdminTools.underWay(v.id()).isEmpty() ? "nothing under way" : String.join(", ", AdminTools.underWay(v.id())));
            case "build.renovate" -> "renovate " + Ledger.buildings(v.id()).size() + " buildings of " + town;
            case "restore" -> "put back " + town + "'s saved data from " + a.word(0, "?") + " (age, treasury, notes; not blocks or folk)";
            case "world.skip" -> "skip " + a.num(0, 1) + " day(s): every town's morning round run for each";
            default -> c.action().path() + " on " + town + (a.positional().isEmpty() ? "" : " with " + String.join(" ", a.positional()));
        };
    }

    private static String entityNote(AdminRunner.Ctx c, int adding) {
        int n = 0;
        for (Entity ignored : c.level().getAllEntities()) n++;
        return n + adding > AdminConfig.entityWarning() ? "; WARNING the world holds " + n + " entities already" : "";
    }

    private static int charter(AdminRunner.Ctx c) {
        return c.args().num(0, com.jrpetty.mcassistant.AssistantConfig.villageCharterFolk());
    }

    // ------------------------------------------------------------------ running

    /** Run the action on this town (null: once, for the world). */
    static Done run(AdminRunner.Ctx c, @Nullable Villages.Village v) {
        ServerLevel level = v == null ? c.level() : levelOf(c, v);
        var a = c.args();
        return switch (c.action().id()) {
            // ---- town
            case "inspect.town" -> {
                AdminProbe.watch();
                yield Done.said(String.join("\n", AdminTools.inspect(level, v)));
            }
            case "inspect.folk" -> {
                List<VillageFolkEntity> fs = AdminRunner.folk(c, v);
                if (fs.isEmpty()) yield Done.no("No such folk.");
                yield Done.said(String.join("\n", AdminTools.inspect(fs.get(0))));
            }
            case "inspect.building" -> Done.said(String.join("\n", AdminTools.inspectBuilding(v.id(), c.pos())));
            case "snapshot" -> AdminSnapshots.take(c.server(), v);
            case "restore" -> AdminSnapshots.restore(c.server(), level, v, a.word(0, ""));
            case "preset.save" -> AdminPresets.save(c.server(), level, v, a.word(0, "preset"));
            case "preset.apply" -> AdminPresets.apply(c, v, a.word(0, ""));
            case "preset.list" -> Done.said("Presets: " + String.join(", ", AdminPresets.names(c.server())));
            // ---- ages and research
            case "ages.set" -> setAge(c, v, AdminTools.age(a.word(0, "wood")), a.word(1, "queued"), a.word(2, "nofill").equals("fill"));
            case "ages.next" -> {
                Villages.Age to = AdminTools.next(Villages.ageOf(v.id()));
                yield to == null ? Done.no(Villages.name(v.id()) + " is in the last age.") : setAge(c, v, to, "queued", false);
            }
            case "ages.needs" -> {
                List<String> n = AdminTools.needs(level, v);
                yield Done.said(Villages.name(v.id()) + " (" + Villages.ageOf(v.id()).label + ") needs: " + (n.isEmpty() ? "nothing; it comes of age at the next look" : String.join("; ", n)));
            }
            case "ages.meet" -> meet(c, v);
            case "research.unlock" -> {
                CityTree.Civic civic = CityTree.byKey(a.word(0, ""));
                if (civic == null) yield Done.no("No civic called " + a.word(0, "") + ".");
                String said = CityTree.grant(level, v.id(), civic, level.getDayTime() / 24000L);
                yield CityTree.has(v.id(), civic) ? Done.ok(said, civic.title + " was completed") : Done.no(said);
            }
            case "research.all" -> {
                int n = 0;
                for (int round = 0; round < CityTree.Civic.values().length; round++) {
                    List<CityTree.Civic> open = CityTree.openNow(v.id());
                    if (open.isEmpty()) break;
                    for (CityTree.Civic civic : open) {
                        if (!CityTree.open(v.id(), civic)) continue;               // its rival just chosen
                        CityTree.grant(level, v.id(), civic, level.getDayTime() / 24000L);
                        if (CityTree.has(v.id(), civic)) n++;
                    }
                }
                yield n == 0 ? Done.said("Nothing left " + Villages.name(v.id()) + " can study.")
                    : Done.ok(n + " civics completed in " + Villages.name(v.id()), n + " civics were completed");
            }
            case "research.knacks" -> forFolk(c, v, "knacks", f -> {
                int n = 0;
                for (FolkSkills.Knack k : FolkSkills.Knack.values()) if (FolkSkills.open(f, k) && FolkSkills.grant(level, f, k)) n++;
                return n > 0;
            });
            // ---- people
            case "people.add" -> addFolk(c, v, a.num(0, 10), Mix.SPREAD, null);
            case "people.trade" -> {
                StationTask t = AdminTools.trade(a.word(0, ""));
                yield t == null ? Done.no("No trade called " + a.word(0, "") + ". One of " + String.join(", ", AdminTools.tradeWords()))
                    : addFolk(c, v, a.num(1, 1), Mix.ADULTS, t);
            }
            case "people.children" -> addFolk(c, v, a.num(0, 3), Mix.CHILDREN, null);
            case "people.elders" -> addFolk(c, v, a.num(0, 3), Mix.ELDERS, null);
            case "people.couples" -> addFolk(c, v, 2 * a.num(0, 2), Mix.COUPLES, null);
            case "people.charter" -> {
                int want = charter(c), have = Villages.headcount(v.id());
                yield want <= have ? Done.said(Villages.name(v.id()) + " has " + have + " already (people remove to shrink it).")
                    : addFolk(c, v, Math.min(AdminSpec.MOST_FOLK, want - have), Mix.SPREAD, null);
            }
            case "people.remove" -> {
                int n = 0, want = a.num(0, 1);
                while (n < want && AdminTools.sendAway(level, v)) n++;
                yield n == 0 ? Done.no("Nobody could be sent away.") : Done.ok(n + " folk sent away from " + Villages.name(v.id()), n + " folk were sent away");
            }
            case "people.kill" -> {
                List<VillageFolkEntity> fs = AdminRunner.folk(c, v);
                for (VillageFolkEntity f : fs) f.kill();
                yield fs.isEmpty() ? Done.no("No such folk.") : Done.ok(fs.size() + " folk of " + Villages.name(v.id()) + " killed", fs.size() + " folk were put to death");
            }
            case "people.heal" -> each(c, v, "healed", AdminTools::heal);
            case "people.needs" -> each(c, v, "fed, rested and cheered", AdminTools::fillNeeds);
            case "people.skill" -> each(c, v, "set to level " + a.num(0, 10) + " at their trade", f -> AdminTools.skill(f, a.num(0, 10)));
            case "people.grow" -> {
                int n = 0;
                for (VillageFolkEntity f : AdminRunner.folk(c, v)) if (AdminTools.growUp(f)) n++;
                yield n == 0 ? Done.said("No children grown up.") : Done.ok(n + " children grown up in " + Villages.name(v.id()), n + " children came of age");
            }
            case "people.marry" -> {
                List<VillageFolkEntity> s = AdminTools.singles(v.id());
                int n = 0;
                for (int i = 0; i + 1 < s.size() && n < a.num(0, 1); i += 2, n++) AdminTools.pledge(level, v.id(), s.get(i), s.get(i + 1));
                yield n == 0 ? Done.no(Villages.name(v.id()) + " has no two singles.") : Done.ok(n + " couple(s) pledged; the town holds the weddings", n + " couples were pledged");
            }
            case "people.births" -> {
                int n = 0;
                for (VillageFolkEntity[] p : AdminTools.couples(level, v.id())) {
                    if (n >= a.num(0, 1)) break;
                    if (p[0].raiseChildWith(p[1]) != null) n++;
                }
                yield n == 0 ? Done.no(Villages.name(v.id()) + " has no couple to have a child.") : Done.ok(n + " child(ren) born", n + " children were born");
            }
            case "people.leader" -> {
                List<VillageFolkEntity> fs = AdminRunner.folk(c, v);
                if (fs.isEmpty() || !c.args().folkGiven()) yield Done.no("Name the new leader with folk:<name>.");
                VillageFolkEntity f = fs.get(0);
                Villages.electElder(v.id(), f, level.getDayTime() / 24000L);
                yield Done.ok(f.displayNameCap() + " leads " + Villages.name(v.id()), f.displayNameCap() + " was made leader");
            }
            case "people.election" -> AdminTools.event(level, v, "election", "", RNG);
            // ---- army and war
            case "army.kit" -> {
                List<VillageFolkEntity> g = AdminTools.guards(v.id());
                if (g.isEmpty()) yield Done.no(Villages.name(v.id()) + " has no watch (army raise first).");
                String tier = a.word(0, "iron");
                AdminTools.Kit k = AdminTools.kit(level, v, g, tier, !a.word(1, "stores").equals("decree"));
                yield Done.ok(k.fighters() + " of the watch armed in " + tier + ": " + k.words(), "the watch was armed in " + tier);
            }
            case "army.raise" -> raiseArmy(c, v, a.num(0, 12), a.word(1, "iron"));
            case "army.war" -> {
                Villages.Village o = a.word(0, "").equalsIgnoreCase("rival") ? AdminTools.foeOf(v) : AdminRunner.town(a.word(0, ""));
                yield o == null ? Done.no("No town called " + a.word(0, "") + ".") : AdminTools.war(level, v, o);
            }
            case "army.phase" -> AdminTools.phase(level, v, a.word(0, "lineup").toLowerCase(Locale.ROOT));
            case "army.ranged" -> AdminTools.ranged(v, a.word(0, "mix"));
            case "army.vassal" -> {
                Villages.Village o = AdminRunner.town(a.word(0, ""));
                yield o == null ? Done.no("No town called " + a.word(0, "") + ".") : AdminTools.vassal(level, v, o);
            }
            case "army.free" -> AdminTools.free(level, v);
            case "army.raid" -> AdminTools.raid(level, v, a.num(0, 6));
            case "army.peace" -> AdminTools.peace(level, v);
            // ---- economy
            case "economy.coin" -> {
                int n = a.num(0, 0);
                if (n >= 0) Ledger.addCoins(v.id(), n);
                else n = -Ledger.takeCoins(v.id(), -n);
                yield Done.ok("Treasury of " + Villages.name(v.id()) + ": " + (n >= 0 ? "+" : "") + n + " = " + Ledger.coins(v.id()),
                    (n >= 0 ? n + " coin was put into" : -n + " coin was taken from") + " the treasury");
            }
            case "economy.pay" -> pay(c, v, a.word(0, ""), a.num(1, 64));
            case "economy.fill" -> {
                String what = a.word(0, "age");
                int n = AdminTools.fill(level, v, AdminTools.fillList(v.id(), what));
                yield Done.ok(n + " goods into the stores of " + Villages.name(v.id()) + " (" + what + ")", "the stores were filled (" + what + ")");
            }
            case "economy.give" -> {
                ResourceLocation id = ResourceLocation.tryParse(a.word(0, "").contains(":") ? a.word(0, "") : "minecraft:" + a.word(0, ""));
                Item it = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
                if (it == null || new ItemStack(it).isEmpty()) yield Done.no("No item called " + a.word(0, "") + ".");
                int n = AdminTools.fill(level, v, Map.of(it, a.num(1, 64)));
                yield Done.ok(n + " " + id.getPath() + " into the stores of " + Villages.name(v.id()), n + " " + id.getPath() + " were put in the stores");
            }
            case "economy.empty" -> {
                int n = AdminTools.emptyStores(level, v);
                yield Done.ok(n + " items taken out of the stores of " + Villages.name(v.id()), "the stores were emptied");
            }
            case "economy.price" -> {
                ResourceLocation id = ResourceLocation.tryParse(a.word(0, "").contains(":") ? a.word(0, "") : "minecraft:" + a.word(0, ""));
                Item it = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
                yield it == null ? Done.no("No item called " + a.word(0, "") + ".") : AdminTools.price(level, v, it, a.num(1, 100));
            }
            case "economy.wagon" -> AdminTools.wagon(level, v);
            case "economy.market" -> AdminTools.marketDay(level, v);
            case "economy.till" -> AdminTools.restock(level, v);
            // ---- build
            case "build.finish" -> {
                List<String> under = AdminTools.underWay(v.id());
                if (under.isEmpty()) yield Done.said("Nothing under way in " + Villages.name(v.id()) + ".");
                List<String> done = new ArrayList<>();
                for (String p : under) {
                    Done d = AdminTools.buildProject(level, v, p, a.word(0, "free").equals("paid"));
                    done.add(d.text());
                }
                yield Done.ok(String.join("; ", done), "the buildings under way were finished");
            }
            case "build.all" -> buildAll(c, v, a.word(0, "free").equals("paid"));
            case "build.named" -> AdminTools.buildProject(level, v, a.word(0, ""), a.word(1, "free").equals("paid"));
            case "build.walls" -> AdminTools.walls(level, v);
            case "build.renovate" -> renovateAll(c, v);
            case "build.unstick" -> AdminTools.unstick(level, v);
            case "build.place" -> place(c, v, a.word(0, "house"));
            // ---- events
            case "events.now" -> AdminTools.event(level, v, a.word(0, "festival").toLowerCase(Locale.ROOT), a.word(1, ""), RNG);
            // ---- world
            case "world.skip" -> skip(c, a.num(0, 1));
            case "world.season" -> AdminTools.season(level, v, a.word(0, "spring"));
            case "world.weather" -> {
                String w = a.word(0, "clear");
                for (ServerLevel l : c.server().getAllLevels()) {
                    switch (w) {
                        case "rain" -> l.setWeatherParameters(0, 6000, true, false);
                        case "thunder" -> l.setWeatherParameters(0, 6000, true, true);
                        default -> l.setWeatherParameters(6000, 0, false, false);
                    }
                }
                yield Done.ok("The weather set to " + w, null);
            }
            case "world.time" -> {
                long t = switch (a.word(0, "noon")) { case "dawn" -> 0L; case "dusk" -> 12000L; case "midnight" -> 18000L; default -> 6000L; };
                long day = c.server().overworld().getDayTime() / 24000L;
                for (ServerLevel l : c.server().getAllLevels()) l.setDayTime(day * 24000L + t);
                yield Done.ok("The time set to " + a.word(0, "noon"), null);
            }
            case "world.found" -> {
                BlockPos at = c.pos();
                if (a.word(2, "here").equals("open")) {                            // [field] the nearest open grassland or savanna
                    BlockPos open = AdminTools.openSite(c.level(), at, 0, OPEN_SEARCH);
                    if (open == null) yield Done.no("No open, level land clear of other towns within " + OPEN_SEARCH + " blocks.");
                    at = surface(c.level(), open);
                }
                yield found(c, at, a.num(0, 20), AdminTools.age(a.word(1, "wood")), null);
            }
            case "world.rival" -> {
                // [field] So far off, on open ground clear of every town (east first, else round about); due east failing that.
                int d = a.num(0, 260);
                BlockPos open = AdminTools.openSite(c.level(), v.centre(), d, d + RIVAL_SLACK);
                BlockPos at = surface(c.level(), open != null ? open : v.centre().offset(d, 0, 0));
                yield found(c, at, a.num(1, 20), Villages.ageOf(v.id()), v.id());
            }
            case "world.tp" -> teleport(c, a.rest(0));
            case "scenario" -> AdminScripts.scenario(c, a.word(0, ""));
            case "run" -> AdminScripts.run(c, a.word(0, ""));
            case "scripts" -> Done.said("Scripts in <world>/mcassistant/admin-scripts: " + String.join(", ", AdminScripts.names(c.server())));
            // ---- debug
            case "debug.overlay" -> AdminOverlay.show(c, v, a.word(0, "lots"));
            case "debug.audit" -> {
                TownAudit.Report r = TownAudit.check(level, v);
                yield Done.said(r.summary() + "\n" + String.join("\n", r.lines(12)));
            }
            case "debug.cost" -> {
                AdminProbe.watch();
                StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "SERVER %.1f ms a tick (%.0f%% of the 50 ms budget at normal speed)",
                    c.server().getCurrentSmoothedTickTime(), c.server().getCurrentSmoothedTickTime() * 2));
                for (Villages.Village t : Villages.every()) {
                    double ms = AdminProbe.msPerSecond(t.id());
                    sb.append("\nTOWN ").append(Villages.name(t.id())).append(' ').append(Villages.headcount(t.id())).append(" folk, ")
                        .append(ms < 0 ? "measuring" : String.format(Locale.ROOT, "%.2f ms a second", ms));
                }
                yield Done.said(sb.toString());
            }
            default -> Done.no("Not bound: " + c.action().id());
        };
    }

    static ServerLevel levelOf(AdminRunner.Ctx c, Villages.Village v) {
        ServerLevel l = c.server().getLevel(v.dim());
        return l == null ? c.level() : l;
    }

    // ------------------------------------------------------------------ the bigger ones

    private static Done setAge(AdminRunner.Ctx c, Villages.Village v, @Nullable Villages.Age to, String renovate, boolean fill) {
        if (to == null) return Done.no("No such age.");
        ServerLevel level = levelOf(c, v);
        Done d = AdminTools.setAge(level, v, to);
        List<String> more = new ArrayList<>();
        if (renovate.equals("instant")) {
            Done r = renovateAll(c, v);
            more.add(r.text());
        } else if (renovate.equals("queued")) {
            more.add("the builders renovate to the age as their work allows");
        }
        if (fill && Villages.headcount(v.id()) < AGE_FOLK[to.ordinal()]) {
            more.add(addFolk(c, v, AGE_FOLK[to.ordinal()] - Villages.headcount(v.id()), Mix.SPREAD, null).text());
        }
        return new Done(true, d.text() + (more.isEmpty() ? "" : "; " + String.join("; ", more)), d.decree());
    }

    enum Mix { SPREAD, ADULTS, CHILDREN, ELDERS, COUPLES }

    /** One child in this many of a spread, one elder in this many. */
    private static final int CHILD_EVERY = 7, ELDER_EVERY = 10, COUPLE_EVERY = 3;

    /**
     * Folk added over ticks ({@link #FOLK_PER_TICK} a tick): a spread of ages and the town's own pick of trades (or the one
     * given), couples pledged among the new grown folk, camp beds for those with none, the houses left to the builders.
     */
    static Done addFolk(AdminRunner.Ctx c, Villages.Village v, int n, Mix mix, @Nullable StationTask trade) {
        ServerLevel level = levelOf(c, v);
        int count = Math.min(AdminSpec.MOST_FOLK, n);
        List<VillageFolkEntity> grown = new ArrayList<>();
        int[] made = new int[3];
        List<VillageFolkEntity[]> parents = mix == Mix.CHILDREN ? AdminTools.couples(level, v.id()) : List.of();
        String label = "adding " + count + " folk to " + Villages.name(v.id());
        AdminJobs.Job job = AdminJobs.units(label, c.src(), count, FOLK_PER_TICK, i -> {
            AdminTools.Kind kind = switch (mix) {
                case CHILDREN -> AdminTools.Kind.CHILD;
                case ELDERS -> AdminTools.Kind.ELDER;
                case SPREAD -> i % CHILD_EVERY == CHILD_EVERY - 1 ? AdminTools.Kind.CHILD : i % ELDER_EVERY == ELDER_EVERY - 1 ? AdminTools.Kind.ELDER : AdminTools.Kind.ADULT;
                default -> AdminTools.Kind.ADULT;
            };
            VillageFolkEntity f = null;
            if (kind == AdminTools.Kind.CHILD && !parents.isEmpty()) {
                VillageFolkEntity[] p = parents.get(i % parents.size());
                if (p[0].isAlive() && p[1].isAlive()) f = p[0].raiseChildWith(p[1]);
            }
            if (f == null) f = AdminTools.raise(level, v, kind, trade, RNG);
            if (f == null) return false;
            made[kind.ordinal()]++;
            if (kind != AdminTools.Kind.CHILD) grown.add(f);
            return true;
        }, j -> {
            int pairs = 0;
            for (int i = 0; i + 1 < grown.size(); i += 2) {
                if (mix != Mix.COUPLES && (i / 2) % COUPLE_EVERY != 0) continue;
                if (grown.get(i).life().partner() != null || grown.get(i + 1).life().partner() != null) continue;
                AdminTools.pledge(level, v.id(), grown.get(i), grown.get(i + 1));
                pairs++;
            }
            int short_ = Math.max(0, Villages.headcount(v.id()) - Villages.housing(v.id()));
            int beds = AdminTools.beds(level, v, Math.min(short_, made[0] + made[1] + made[2]));
            int total = made[0] + made[1] + made[2];
            String said = total + " folk added to " + Villages.name(v.id()) + " (" + made[0] + " grown, " + made[1] + " children, " + made[2] + " elders), "
                + pairs + " couple(s) pledged, " + beds + " camp beds; " + Villages.name(v.id()) + " now " + Villages.headcount(v.id())
                + " strong, its builders queue houses for the rest";
            AdminRunner.decree(level, v, total + " folk were brought to the town");
            jobOutcome(j, said);
        });
        return start(job, label + " (" + FOLK_PER_TICK + " a tick)" + entityNote(c, count));
    }

    /** The job just started, as a result line the runner reads ("JOB a3f1 ..."). */
    private static Done start(AdminJobs.Job job, String what) {
        String[] why = { "" };
        if (AdminJobs.start(job, why) == null) return Done.no(why[0]);
        return Done.said("JOB " + job.id() + " " + what + "; /village admin cancel " + job.id() + " to stop it");
    }

    /** A job's last word: what really happened, for its owner and the log. */
    private static void jobOutcome(AdminJobs.Job j, String said) {
        j.outcome(said);
    }

    static Done raiseArmy(AdminRunner.Ctx c, Villages.Village v, int n, String tier) {
        ServerLevel level = levelOf(c, v);
        int[] raised = new int[1];
        List<VillageFolkEntity> army = new ArrayList<>();
        String label = "raising " + n + " of the watch in " + Villages.name(v.id());
        AdminJobs.Job job = AdminJobs.units(label, c.src(), n, FOLK_PER_TICK, i -> {
            VillageFolkEntity f = AdminTools.enlist(v);
            if (f == null) {
                f = AdminTools.raise(level, v, AdminTools.Kind.ADULT, StationTask.GUARD, RNG);
                if (f == null) return false;
                raised[0]++;
            }
            army.add(f);
            return true;
        }, j -> {
            AdminTools.Kit k = AdminTools.kit(level, v, army, tier, true);
            String said = army.size() + " put on the watch of " + Villages.name(v.id()) + " (" + raised[0] + " raised for it), armed in " + tier + ": "
                + k.words();
            AdminRunner.decree(level, v, army.size() + " were called to the watch and armed in " + tier);
            jobOutcome(j, said);
        });
        return start(job, label);
    }

    private static Done meet(AdminRunner.Ctx c, Villages.Village v) {
        ServerLevel level = levelOf(c, v);
        List<String> said = new ArrayList<>();
        List<String> builds = AdminTools.meetGoods(level, v, said);
        if (builds.isEmpty()) return Done.ok(Villages.name(v.id()) + ": " + said.get(0) + "; no buildings wanted", "the next age's goods were laid in");
        List<String> built = new ArrayList<>();
        AdminJobs.Job job = AdminJobs.units("putting up " + builds.size() + " building(s) in " + Villages.name(v.id()), c.src(), builds.size(), 1, i -> {
            Done d = AdminTools.buildProject(level, v, builds.get(i), false);
            if (d.ok()) built.add(builds.get(i));
            return true;
        }, j -> {
            AdminRunner.decree(level, v, "the next age's needs were met (" + String.join(", ", built) + ")");
            jobOutcome(j, "built " + String.join(", ", built) + " in " + Villages.name(v.id()));
        });
        return start(job, said.get(0) + "; building " + String.join(", ", builds));
    }

    private static Done buildAll(AdminRunner.Ctx c, Villages.Village v, boolean paid) {
        ServerLevel level = levelOf(c, v);
        List<String> built = new ArrayList<>();
        String[] last = { "" };
        AdminJobs.Job job = AdminJobs.units("building everything " + Villages.name(v.id()) + " wants", c.src(), MOST_BUILDS, 1, i -> {
            String p = AdminTools.nextProject(v.id());
            if (p == null || p.equals(last[0])) return false;           // nothing left, or the same one would not go up
            last[0] = p;
            if (AdminTools.buildProject(level, v, p, paid).ok()) built.add(p);
            return true;
        }, j -> {
            if (!built.isEmpty()) AdminRunner.decree(level, v, built.size() + " buildings were put up");
            jobOutcome(j, built.size() + " built in " + Villages.name(v.id()) + ": " + String.join(", ", built));
        });
        return start(job, "building " + Villages.name(v.id()) + "'s list, one a tick" + (paid ? ", out of the stores" : ""));
    }

    private static Done renovateAll(AdminRunner.Ctx c, Villages.Village v) {
        ServerLevel level = levelOf(c, v);
        List<Ledger.Building> all = new ArrayList<>(Ledger.buildings(v.id()));
        if (all.isEmpty()) return Done.said(Villages.name(v.id()) + " has no buildings to renovate.");
        int[] blocks = new int[1];
        AdminJobs.Job job = AdminJobs.units("renovating " + all.size() + " buildings of " + Villages.name(v.id()), c.src(), all.size(), 1, i -> {
            blocks[0] += AdminTools.renovate(level, v, all.get(i));
            return true;
        }, j -> {
            AdminRunner.decree(level, v, all.size() + " buildings were renovated to " + Villages.ageOf(v.id()).label);
            jobOutcome(j, all.size() + " buildings renovated in " + Villages.name(v.id()) + " (" + blocks[0] + " blocks)");
        });
        return start(job, "renovating " + all.size() + " buildings, one a tick");
    }

    private static Done skip(AdminRunner.Ctx c, int days) {
        long from = c.server().overworld().getDayTime();
        long tod = from % 24000L;
        AdminJobs.Job job = new AdminJobs.Job("skipping " + days + " day(s)", c.src(), days) {
            int wait;

            @Override
            protected boolean tick(net.minecraft.server.MinecraftServer server) {
                if (++wait < WARP_TICKS) return false;
                wait = 0;
                AdminTools.warpDay(server);
                done++;
                if (done < total) return false;
                long day = server.overworld().getDayTime() / 24000L;
                for (ServerLevel l : server.getAllLevels()) l.setDayTime(day * 24000L + tod);   // back to the hour it was
                for (Villages.Village v : Villages.every()) {
                    ServerLevel l = server.getLevel(v.dim());
                    if (l != null) AdminRunner.decree(l, v, days + " day(s) were skipped");
                }
                outcome("skipped " + days + " day(s): now day " + day);
                return true;
            }
        };
        return start(job, "skipping " + days + " day(s), a day every " + WARP_TICKS + " ticks");
    }

    /** [field] How far an open site is looked for: round the admin for a new town, past the distance asked for a rival. */
    static final int OPEN_SEARCH = 1024, RIVAL_SLACK = 96;

    private static BlockPos surface(ServerLevel level, BlockPos p) {
        level.getChunk(p.getX() >> 4, p.getZ() >> 4);
        return new BlockPos(p.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ()), p.getZ());
    }

    /** A town founded here with so many folk at an age, as a job; {@code rivalOf}, paired with that town as its rival once it stands. */
    private static Done found(AdminRunner.Ctx c, BlockPos at, int folk, @Nullable Villages.Age age, @Nullable UUID rivalOf) {
        ServerLevel level = c.level();
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) level.getChunk((at.getX() >> 4) + dx, (at.getZ() >> 4) + dz);
        if (Villages.nearest(level, at, Villages.VILLAGE_RANGE * 2) != null) return Done.no("There is a town near there already.");
        var o = com.jrpetty.mcassistant.entity.Founding.foundNow(level, at, folk, 0F, c.src().getPlayer());
        if (!o.ok()) return Done.no(o.message());
        AdminJobs.Job job = new AdminJobs.Job("founding a town of " + folk + " at " + at.getX() + " " + at.getZ(), c.src(), folk) {
            int ticks;

            @Override
            protected boolean tick(net.minecraft.server.MinecraftServer server) {
                if (++ticks % 20 != 0) return false;
                Villages.Village v = Villages.nearest(level, at, Villages.VILLAGE_RANGE * 2);
                done = v == null ? 0 : Villages.headcount(v.id());
                if (v == null || (done < folk * 9 / 10 && ticks < FOUNDING_WAIT)) return ticks >= FOUNDING_WAIT * 2;
                AdminRunner.newest = v.id();
                if (rivalOf != null) AdminTools.pairRivals(rivalOf, v.id());
                if (age != null && age != Villages.Age.WOOD) AdminTools.setAge(level, v, age);
                AdminRunner.decree(level, v, "the town was founded with " + done + " folk" + (age == null ? "" : " in " + age.label));
                outcome("founded " + Villages.name(v.id()) + " with " + done + " folk" + (age == null ? "" : ", " + age.label));
                return true;
            }
        };
        return start(job, "founding at " + at.toShortString() + ": " + o.message());
    }

    private static Done pay(AdminRunner.Ctx c, Villages.Village v, String name, int n) {
        ServerPlayer p = c.server().getPlayerList().getPlayerByName(name);
        if (p == null) return Done.no("No player called " + name + " is online.");
        int fromTreasury = Ledger.takeCoins(v.id(), n);
        int left = n;
        while (left > 0) {
            int k = Math.min(64, left);
            ItemStack coins = new ItemStack(McAssistantMod.VILLAGE_COIN.get(), k);
            if (!p.getInventory().add(coins)) p.drop(coins, false);
            left -= k;
        }
        return Done.ok(p.getGameProfile().getName() + " paid " + n + " coin of " + Villages.name(v.id()) + " (" + fromTreasury + " from the treasury, "
            + (n - fromTreasury) + " minted by decree)", p.getGameProfile().getName() + " was paid " + n + " coin");
    }

    private static Done place(AdminRunner.Ctx c, Villages.Village v, String structure) {
        ServerPlayer p = c.src().getPlayer();
        if (p == null) return Done.no("Only a player can place where it looks.");
        HitResult hit = p.pick(64.0, 1.0F, false);
        if (!(hit instanceof BlockHitResult b) || hit.getType() != HitResult.Type.BLOCK) return Done.no("Look at the ground where it should stand.");
        BlockPos anchor = b.getBlockPos().above();
        return AdminTools.place(levelOf(c, v), v, structure, anchor, p.getDirection().getOpposite());
    }

    private static Done teleport(AdminRunner.Ctx c, String where) {
        ServerPlayer p = c.src().getPlayer();
        if (p == null) return Done.no("Only a player can be teleported.");
        Villages.Village t = AdminRunner.town(where);
        BlockPos to = null;
        String what = where;
        if (t != null) {
            to = t.centre();
            what = Villages.name(t.id());
        } else if (where.startsWith("building ")) {
            Villages.Village v = AdminRunner.nearest(c);
            String s = where.substring(9).trim();
            if (v != null) for (Ledger.Building b : Ledger.buildings(v.id())) if (b.structure().equals(s)) { to = b.anchor(); break; }
        } else {
            for (Villages.Village v : Villages.every()) {
                for (VillageFolkEntity f : AdminTools.folk(v.id())) {
                    if (f.displayNameCap().equalsIgnoreCase(where)) { to = f.blockPosition(); what = f.displayNameCap(); break; }
                }
                if (to != null) break;
            }
        }
        if (to == null) return Done.no("Nowhere called " + where + " (a town, a folk, or \"building <structure>\").");
        ServerLevel l = t != null ? levelOf(c, t) : c.level();
        int y = l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, to.getX(), to.getZ());
        p.teleportTo(l, to.getX() + 0.5, Math.max(y, to.getY()) + 1, to.getZ() + 0.5, p.getYRot(), p.getXRot());
        return Done.said("Teleported to " + what + " at " + to.toShortString());
    }

    // ------------------------------------------------------------------ folk, one by one

    private static Done each(AdminRunner.Ctx c, Villages.Village v, String what, java.util.function.Consumer<VillageFolkEntity> act) {
        List<VillageFolkEntity> fs = AdminRunner.folk(c, v);
        for (VillageFolkEntity f : fs) act.accept(f);
        return fs.isEmpty() ? Done.no("No such folk.") : Done.ok(fs.size() + " folk of " + Villages.name(v.id()) + " " + what, fs.size() + " folk were " + what);
    }

    private static Done forFolk(AdminRunner.Ctx c, Villages.Village v, String what, java.util.function.Predicate<VillageFolkEntity> act) {
        List<VillageFolkEntity> fs = AdminRunner.folk(c, v);
        if (fs.isEmpty()) return Done.no("No such folk.");
        ServerLevel level = levelOf(c, v);
        int[] n = new int[1];
        AdminJobs.Job job = AdminJobs.units(what + " for " + fs.size() + " folk of " + Villages.name(v.id()), c.src(), fs.size(), FOLK_WORK_PER_TICK, i -> {
            if (fs.get(i).isAlive() && act.test(fs.get(i))) n[0]++;
            return true;
        }, j -> {
            AdminRunner.decree(level, v, n[0] + " folk were given " + what);
            jobOutcome(j, n[0] + " of " + fs.size() + " folk given " + what);
        });
        return start(job, what + " for " + fs.size() + " folk");
    }

    /** Tests: a town's folk with this trade (or grown, or children). */
    static List<VillageFolkEntity> ofTrade(UUID village, String trade) {
        List<VillageFolkEntity> out = new ArrayList<>();
        StationTask t = AdminTools.trade(trade);
        for (VillageFolkEntity f : AdminTools.folk(village)) if (t != null && f.stationTask() == t) out.add(f);
        return out;
    }
}
