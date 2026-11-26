package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.List;

/**
 * [wpb] Operators only, for the camera and the screenshots (never a player's way in: players use the pages):
 * {@code /village wpbdemo <step> <player>} sets a scene up at once in the nearest town: "adverts" posts a few openings with
 * folk applicants; "hire" takes the player on as a farm hand on commission; "shift" starts its shift; "handin" puts marked
 * and unmarked wheat in its pack; "farm" registers the farmland it stands on as its farm with a crop of marked wheat;
 * "firm" charters a bakery owned by the player with a hand and a week's trade.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class WorkDemo {

    private WorkDemo() {}

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("village").then(Commands.literal("wpbdemo").requires(s -> s.hasPermission(2))
            .then(Commands.argument("step", StringArgumentType.word()).then(Commands.argument("who", EntityArgument.player()).executes(c -> {
                ServerPlayer p = EntityArgument.getPlayer(c, "who");
                String said = step(p, StringArgumentType.getString(c, "step"));
                c.getSource().sendSuccess(() -> Component.literal("WPBDEMO " + said), false);
                return 1;
            })))));
    }

    /** [wpb] The deputy's week, the report, the Cores. */
    static String demo2(ServerLevel level, ServerPlayer p, Villages.Village v, String step) {
        List<Business> mine = MyBusinesses.mine(v.id(), p.getUUID());
        Business b = mine.isEmpty() ? null : mine.get(0);
        long day = Businesses.today(level);
        net.minecraft.core.BlockPos ahead = p.blockPosition().relative(p.getDirection(), 3);
        switch (step) {
            case "deputy" -> {
                if (b == null) return "no firm";
                while (Enterprise.hands(v.id(), b).size() < 3 && Enterprise.hireForTests(level, v, b) != null) { }
                List<VillageFolkEntity> hs = Enterprise.hands(v.id(), b);
                if (hs.isEmpty()) return "no hands";
                CompoundTag r = Enterprise.rec(v.id(), b.id);
                String said = Deputy.appoint(level, v.id(), b, hs.get(0), "Shop manager", 10);
                for (int i = 6; i >= 1; i--) Deputy.day(level, v, b, r, day - i, java.util.Map.of(), true);
                return said;
            }
            case "awayreport" -> {
                if (b == null) return "no firm";
                CompoundTag r = Enterprise.rec(v.id(), b.id);
                r.putLong("left/" + p.getUUID(), day - 6);
                return "reports " + Deputy.loginReport(level, p);
            }
            case "core" -> {
                if (b == null) return "no firm";
                b.at = ahead;
                CoreSites.ensure(level, p);
                CoreSites.Site s = CoreSites.site(v.id(), "firm:" + b.id);
                return s == null ? "no spot" : "core at " + s.at().toShortString();
            }
            case "folkcore" -> {
                for (Business x : Enterprise.open(v.id())) {
                    if (x.ownedBy(p.getUUID())) continue;
                    x.at = ahead;
                    CoreSites.ensure(level, p);
                    CoreSites.Site s = CoreSites.site(v.id(), "firm:" + x.id);
                    if (s != null) return "folk core " + x.name + " at " + s.at().toShortString();
                }
                return "none";
            }
            case "offerin" -> {
                if (b == null) return "no firm";
                for (AssistantEntity a : Villages.folkOf(v.id())) {
                    if (!(a instanceof VillageFolkEntity f) || f.isBaby() || b.ownedBy(f.getUUID())) continue;
                    f.earn(80);
                    BusinessOffers.Offer o = BusinessOffers.folkOffer(level, v.id(), b, f, 60);
                    if (o != null) return "offer from " + f.displayNameCap();
                }
                return "none";
            }
            default -> { return "?"; }
        }
    }

    static String step(ServerPlayer p, String step) {
        ServerLevel level = p.serverLevel();
        Villages.Village v = PlayerLife.here(p);
        if (v == null) return "no town";
        long day = level.getDayTime() / 24000L;
        switch (step) {
            case "adverts" -> {
                String[][] ads = { { "town:FARM", "The town's fields", "FARM" }, { "town:MINE", "The town's mine", "MINE" }, { "town:FISH", "The harbour", "FISH" },
                    { "town:HAUL", "The storehouse", "HAUL" }, { "town:SMITH", "The smithy", "SMITH" }, { "town:SHOP", "The general store", "SHOP" } };
                int n = 0;
                for (String[] a : ads) {
                    JobCards.Card card = JobCards.of(a[2]);
                    int id = PlayerJobs.openingForTests(v.id(), a[0], a[1], a[2], PayTerms.goingWage(level, v.id(), card, 0), day);
                    int k = 0;
                    for (AssistantEntity f : Villages.folkOf(v.id())) {
                        if (f instanceof VillageFolkEntity vf && !vf.isBaby() && k < 1 + n % 3) { PlayerJobs.rivalForTests(v.id(), id, vf, 2 + k, day); k++; }
                    }
                    n++;
                }
                return n + " adverts posted";
            }
            case "hire" -> {
                WorkLife.hireForTests(level, p, v.id(), "town:FARM", "The town's fields", "FARM", PayTerms.goingWage(level, v.id(), JobCards.of("FARM"), 0), true);
                return "hired: " + PlayerJobs.line(p);
            }
            case "shift" -> {
                String s = Shifts.take(p, v.id(), PlayerJobs.job(p).getString("role"), true);
                return s.isEmpty() ? "shift on" : s;
            }
            case "handin" -> {
                ItemStack mine = new ItemStack(Items.WHEAT, 24);
                Provenance.tag(mine, Provenance.FIELD, WorkLife.employerKey(p), p, "the town's fields");
                p.getInventory().add(mine);
                p.getInventory().add(new ItemStack(Items.WHEAT, 9));
                return "wheat in the pack";
            }
            case "farm" -> {
                BlockPos at = p.blockPosition();
                for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
                    level.setBlock(at.offset(dx, -1, dz), Blocks.FARMLAND.defaultBlockState(), 3);
                    level.setBlock(at.offset(dx, 0, dz), ((CropBlock) Blocks.WHEAT).getStateForAge(dx == 0 && dz == 0 ? 0 : 7), 3);
                }
                Earnings.giveCoins(p, 20);
                String r = PlayerFarms.register(level, p, v, "farm", p.getGameProfile().getName() + "'s Farm", true);
                for (PlayerFarms.Farm f : PlayerFarms.ofOwner(v.id(), p.getUUID())) {
                    ItemStack grown = new ItemStack(Items.WHEAT, 40);
                    Provenance.tag(grown, Provenance.OWN, "farm:" + f.id, p, f.name);
                    p.getInventory().add(grown);
                }
                return r;
            }
            case "firm" -> {
                Business b = Enterprise.foundForTests(level, v, "baker", null);
                if (b == null) return "no firm";
                b.owners.clear();
                b.owners.add(new Business.Stake(Business.Holder.PLAYER, p.getUUID(), p.getGameProfile().getName(), Business.WHOLE));
                b.till += 4200;
                Enterprise.hireForTests(level, v, b);
                long today = Businesses.today(level);
                for (int i = 0; i < 9; i++) {
                    Business.Day d = b.today(today - i);
                    d.revenue += 1800 + 120 * (i % 4);
                    d.cogs += 700;
                    d.wages += 500;
                    d.rent += 60;
                    d.trans += 9 + i % 3;
                }
                Businesses.dirty();
                return "firm " + b.name;
            }
            case "deputy", "awayreport", "core", "folkcore", "offerin" -> { return demo2(level, p, v, step); }   // [wpb] the deputy and the Core, for the camera
            case "openaway" -> { PlayerLife.open(p, "firm", LifePage.args("tab", "away")); return "opened"; }
            case "openhistory" -> { PlayerLife.open(p, "firm", LifePage.args("tab", "history")); return "opened"; }
            case "openoffers" -> { PlayerLife.open(p, "firm", LifePage.args("tab", "offers")); return "opened"; }
            case "openprices" -> { PlayerLife.open(p, "firm", LifePage.args("tab", "prices")); return "opened"; }
            case "opencore" -> {
                CoreSites.Site best = null;
                for (CoreSites.Site x : CoreSites.of().sites.values()) if (best == null || x.at().distSqr(p.blockPosition()) < best.at().distSqr(p.blockPosition())) best = x;
                if (best == null) return "no core";
                CoreSites.open(p, best.at());
                return "opened " + best.key;
            }
            case "treasury" -> { com.jrpetty.mcassistant.village.Ledger.addCoins(v.id(), 500); return "500 coins to the treasury"; }
            case "goto" -> {                                                // ten blocks short of the workplace, facing it
                net.minecraft.nbt.CompoundTag wp = WorkWay.workplace(level, p, PlayerJobs.job(p));
                if (wp == null) return "no workplace";
                int[] a = wp.getIntArray("at");
                double dx = p.getX() - a[0], dz = p.getZ() - a[2], d = Math.max(1, Math.sqrt(dx * dx + dz * dz));
                double x = a[0] + dx / d * 12, z = a[2] + dz / d * 12;
                int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) x, (int) z);
                float yaw = (float) Math.toDegrees(Math.atan2(-(a[0] - x), a[2] - z));
                p.teleportTo(level, x, y, z, yaw, 15);
                return "at " + (int) x + " " + y + " " + (int) z;
            }
            case "openadverts" -> { PlayerLife.open(p, "jobs", LifePage.args("tab", "adverts")); return "opened"; }
            case "opencard" -> {
                List<String> keys = JobAdverts.advertKeysForTests(level, v, p);
                PlayerLife.open(p, "jobs", LifePage.args("tab", "adverts", "view", "advert", "ad", keys.isEmpty() ? "" : keys.get(0)));
                return "opened " + (keys.isEmpty() ? "nothing" : keys.get(0));
            }
            case "openjob" -> { PlayerLife.open(p, "jobs", LifePage.args("tab", "job")); return "opened"; }
            case "openweek" -> { PlayerLife.open(p, "jobs", LifePage.args("tab", "week")); return "opened"; }
            case "openapps" -> { PlayerLife.open(p, "jobs", LifePage.args("tab", "apps")); return "opened"; }
            case "openhandin" -> { PlayerLife.open(p, "jobs", LifePage.args("tab", "handin")); return "opened"; }
            case "openfirm" -> { PlayerLife.open(p, "firm", LifePage.args("tab", "summary")); return "opened"; }
            case "openfirmhands" -> { PlayerLife.open(p, "firm", LifePage.args("tab", "hands")); return "opened"; }
            case "rota" -> {                                                  // [rota] a firm's staff on a rota, for the camera
                Business b = null;
                for (Business x : Enterprise.open(v.id())) if (x.ownedBy(p.getUUID())) b = x;
                if (b == null) return "no firm";
                for (int i = 0; i < 2; i++) Enterprise.hireForTests(level, v, b);
                return Rota.autoFill(level, v.id(), b, p.getGameProfile().getName());
            }
            case "openfirmrota" -> { PlayerLife.open(p, "firm", LifePage.args("tab", "rota")); return "opened"; }
            case "openfirmmore" -> { PlayerLife.open(p, "firm", LifePage.args("tab", "more")); return "opened"; }
            case "openfarm" -> { PlayerLife.open(p, "farm", LifePage.args("tab", "overview")); return "opened"; }
            case "openfarmsell" -> { PlayerLife.open(p, "farm", LifePage.args("tab", "sell")); return "opened"; }
            case "apply" -> {
                List<String> keys = JobAdverts.advertKeysForTests(level, v, p);
                return keys.isEmpty() ? "none" : JobAdverts.applyForTests(level, p, keys.get(0), true);
            }
            case "hints" -> {
                for (String k : new String[]{ "adverts", "job", "apps", "week", "handin", "farm" }) Earnings.data(p).putBoolean("wpb/hint/" + k, true);
                return "hints dismissed";
            }
            default -> { return "steps: adverts, hire, shift, handin, farm, firm, apply, hints, open..."; }
        }
    }
}
