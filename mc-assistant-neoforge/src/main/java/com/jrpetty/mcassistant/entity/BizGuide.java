package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * [biz25] The way from "I want a shop" to the first folk customer paying at its counter, said on every screen it passes:
 * <ul>
 * <li><b>The first steps, ticked.</b> Premises with a counter; named and registered; everything a shop needs; wares on its
 *     shelves that folk here have a use for; open; the first sale. The shop's screen and Your life in town show them, the
 *     next one first with a button to where it is done, so the player always knows what to do next.</li>
 * <li><b>A counter to be had.</b> Where the town has no shop selling Shop Counters, its carpenters make one to order at
 *     the market's price of its makings and a little for the work, the coin into the treasury, booked as goods bought.</li>
 * <li><b>A word on the way in.</b> The first time a player comes into a town, a line says the U key is their life there:
 *     a job, a shop of their own, a business.</li>
 * <li><b>What sells here.</b> What the town's folk want now (ItemUses), for the shop's Stock tab and the first steps.</li>
 * </ul>
 */
public final class BizGuide {

    private BizGuide() {}

    /** One of a shop's first steps: done or not, what it is, what to do about it, and the button that does it. */
    public record Step(String id, boolean done, String label, String hint, String button, String action, @Nullable CompoundTag args) {
        public String line() {
            return (done ? "✔ " : "✘ ") + label;
        }
    }

    /** A shop's first steps, in order ({@code s} null: the player keeps none yet in this town). */
    public static List<Step> steps(ServerLevel level, @Nullable Villages.Village v, @Nullable PlayerShops.Shop s) {
        List<Step> out = new ArrayList<>();
        boolean has = s != null;
        out.add(new Step("premises", has, "Premises with a Shop Counter",
            "A room of your own with a door onto a street and a Shop Counter in it; or have the town build you a shop; or buy a building that's for sale.",
            "Get premises", "start", LifePage.args("kind", "shop")));
        boolean registered = has && s.setUp && s.licensed;
        out.add(new Step("register", registered, "Named and registered",
            has ? "Give it a name and hours on its Setup tab and register it (a licence of " + PlayerLife.coins(PlayerShops.licence(s.village)) + ")." : "",
            "Setup", "tab:setup", null));
        boolean ready = has && ShopNeeds.ready(PlayerShops.needs(level, s));
        out.add(new Step("needs", ready, "Everything a shop needs",
            has && !ready ? "It still " + ShopNeeds.missing(s.needs) + ": the Setup tab says where each thing is to be had." : "",
            "What it needs", "tab:setup", null));
        int wares = 0, wanted = 0;
        List<String> idle = new ArrayList<>();
        if (has) {
            Map<String, ItemStack> w = PlayerShops.wares(level, s, new LinkedHashMap<>(), false);
            wares = w.size();
            for (Map.Entry<String, ItemStack> e : w.entrySet()) {
                Integer price = s.prices.get(e.getKey());
                if (price != null && price == 0) continue;                  // kept back
                if (ItemUses.judge(level, s.village, e.getValue()).yes()) wanted++;
                else if (idle.size() < 3) idle.add(PlayerStalls.nameOf(e.getValue()).toLowerCase(Locale.ROOT));
            }
        }
        String stockHint = !has ? "" : wares == 0 ? "Put what you'd sell in a chest inside (not one marked back stock). " + wantedWords(level, s.village, 4)
            : wanted == 0 ? "Nobody here has a use for what's on its shelves (" + String.join(", ", idle) + ") just now. " + wantedWords(level, s.village, 4)
            : wanted + " of its " + wares + (wares == 1 ? " ware has" : " wares have") + " buyers in town; each sells at the going price unless you set one.";
        out.add(new Step("stock", wanted > 0, "Wares on its shelves that folk here want", stockHint, "Stock & prices", "tab:stock", null));
        PlayerShops.State st = has ? PlayerShops.state(level, s) : PlayerShops.State.UNSET;
        boolean open = st == PlayerShops.State.OPEN;
        out.add(new Step("open", open || has && s.soldAll > 0, "Open",
            !has ? "" : st == PlayerShops.State.SHUT ? "It opens at " + PlayerShops.clock(s.opens) + " (its hours are on the Setup tab)."
                : st == PlayerShops.State.OPEN ? "" : "It opens once the steps above are done.",
            "Its hours", "tab:setup", null));
        out.add(new Step("sale", has && s.soldAll > 0, "Its first sale to a folk",
            has ? "Folk come in its hours when it has something they have a use for: a few a day at first, more as its name grows. A keeper minds it while you're away." : "",
            "Take on a keeper", "tab:keeper", null));
        return out;
    }

    /** The first step not done yet, or null when the shop is up and selling. */
    @Nullable
    public static Step next(List<Step> steps) {
        for (Step s : steps) if (!s.done()) return s;
        return null;
    }

    /** "Folk here want: candles (3 folk), leather tunics (1)." from what the town's folk have a use for now. */
    static String wantedWords(ServerLevel level, UUID village, int most) {
        List<String> out = new ArrayList<>();
        try {
            for (ItemUses.TownNeed n : ItemUses.townNeeds(level, village)) {
                if (out.size() >= most) break;
                String name = PlayerStalls.nameOf(n.sample()).toLowerCase(Locale.ROOT);
                if (out.stream().anyMatch(x -> x.startsWith(name + " "))) continue;
                out.add(name + " (" + n.folk() + " folk, up to " + PlayerLife.coins(n.mostEach()) + " each)");
            }
        } catch (RuntimeException e) {
            return "";
        }
        return out.isEmpty() ? "" : "Folk here want: " + String.join(", ", out) + ".";
    }

    // ------------------------------------------------------------------ a counter, made to order

    /** What the town's carpenters ask for a Shop Counter: its makings at the market's price and a fifth for the work. */
    public static int counterPrice(ServerLevel level, UUID village) {
        double makings;
        try {
            makings = CostBook.atMarket(level, village, new ItemStack(McAssistantMod.SHOP_COUNTER_ITEM.get()));
        } catch (RuntimeException e) {
            makings = 0;
        }
        return Math.max(3, (int) Math.ceil(makings * 1.2));
    }

    /** Does the player carry a Shop Counter (or a sign)? */
    public static boolean carriesCounter(Player p) {
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack x = p.getInventory().getItem(i);
            if (x.is(McAssistantMod.SHOP_COUNTER_ITEM.get()) || x.is(McAssistantMod.SHOP_SIGN_ITEM.get())) return true;
        }
        return false;
    }

    /** Buy a Shop Counter of the town's carpenters: the coin into the treasury, the counter into the pack. "!" refused. */
    public static String buyCounter(ServerPlayer p) {
        Villages.Village v = PlayerLife.here(p);
        if (v == null) return "!A town's carpenters make shop counters: go to one.";
        ServerLevel level = p.serverLevel();
        int price = counterPrice(level, v.id());
        int have = Market.coinsHeld(p);
        if (have < price) return "!A Shop Counter is " + PlayerLife.coins(price) + " and you have " + have + ". Sell something, take a shift, or craft one.";
        Market.payOut(p, price);
        com.jrpetty.mcassistant.village.Ledger.addCoins(v.id(), price);
        CoinCensus.playerIn(v.id(), price, p, PlayerFinance.Cat.PURCHASES, "a shop counter from the town's carpenters");
        Economy.sold(v.id(), price);
        ItemStack counter = new ItemStack(McAssistantMod.SHOP_COUNTER_ITEM.get());
        if (!p.getInventory().add(counter)) p.drop(counter, false);
        return "A Shop Counter for " + PlayerLife.coins(price) + ", in your pack. Set it down inside your building: its screen opens and says what else it needs.";
    }

    // ------------------------------------------------------------------ the word on the way in

    private static final String TOLD = "mca_biz25_told";

    /** The first time a player comes into this town: a line on the U key, unless it keeps a shop or a business here already. */
    public static void firstVisit(ServerPlayer p, UUID village) {
        CompoundTag keep = p.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        CompoundTag told = keep.getCompound(TOLD);
        String k = village.toString();
        if (told.getBoolean(k)) return;
        told.putBoolean(k, true);
        keep.put(TOLD, told);
        p.getPersistentData().put(Player.PERSISTED_NBT_TAG, keep);
        if (PlayerShops.of(village, p.getUUID()) != null || !MyBusinesses.mine(village, p.getUUID()).isEmpty()) return;
        p.sendSystemMessage(Component.literal("Press U for your life in " + Villages.name(village)
            + ": find work, open a shop of your own or start a business. The page says what to do next.").withStyle(ChatFormatting.AQUA));
    }

    // ------------------------------------------------------------------ a building sold

    /**
     * A building on the register sold (PropertyMarket.accept): if the seller's shop stands in it, the shop is the seller's
     * no longer: its till paid out to the seller, its keeper let go, its sign taken down and its record closed, as Close
     * the shop does. What is in its chests went with the building or came back to the seller, as the listing said. The
     * new owner makes it a shop of its own with a click on the counter. Returns a word to add to what the seller is told.
     */
    static String buildingSold(ServerLevel level, UUID village, CompoundTag t, ServerPlayer seller) {
        PlayerShops.Shop s = PlayerShops.of(village, seller.getUUID());
        if (s == null || !inside(t, s.anchor)) return "";
        int till = s.till;
        PlayerShops.close(level, seller, s);
        return " Your shop there is closed" + (till > 0 ? ", its till (" + PlayerLife.coins(till) + ") into your pack" : "")
            + "; the new owner makes it a shop of its own at the counter.";
    }

    /** Is this spot within a register entry's footprint (its anchor, turned by its facing, a storey's height or so)? */
    static boolean inside(CompoundTag t, net.minecraft.core.BlockPos at) {
        net.minecraft.core.BlockPos a = net.minecraft.core.BlockPos.of(t.getLong("anchor"));
        net.minecraft.core.Direction f = net.minecraft.core.Direction.from2DDataValue(t.getInt("facing"));
        boolean turned = f.getAxis() == net.minecraft.core.Direction.Axis.X;
        int bx = turned ? t.getInt("hd") : t.getInt("hw"), bz = turned ? t.getInt("hw") : t.getInt("hd");
        return Math.abs(at.getX() - a.getX()) <= bx && Math.abs(at.getZ() - a.getZ()) <= bz && Math.abs(at.getY() - a.getY()) <= 12;
    }

    // ------------------------------------------------------------------ the keeper's sums

    /** "Its till took about 6 coins a day this week; a keeper asks 3": what a keeper costs against what the shop takes. */
    static String keeperSums(PlayerShops.Shop s, int wage) {
        int days = 0, coin = 0;
        for (int i = 0; i < PlayerShops.WEEK; i++) {
            coin += s.weekCoin[i];
            if (s.weekCoin[i] > 0 || i == 0) days = i + 1;
        }
        int perDay = days == 0 ? 0 : Math.round(coin / (float) Math.max(1, days));
        if (coin == 0) return "It hasn't taken anything yet: a keeper's wage (" + PlayerLife.coins(wage) + " a day) comes out of the till whether it sells or not.";
        return "Its till took about " + PlayerLife.coins(perDay) + " a day lately; a keeper asks " + PlayerLife.coins(wage) + " a day"
            + (perDay > wage ? ", so it pays for itself." : perDay == wage ? ", about what it takes." : ": more than it takes, so stock what sells first.");
    }
}
