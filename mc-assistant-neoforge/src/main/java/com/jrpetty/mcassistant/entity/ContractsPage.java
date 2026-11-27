package com.jrpetty.mcassistant.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [wpb] The Contracts page ("contracts"): your contracts with other players and what each waits on (sign, deliver, confirm),
 * and a form for a new one: goods for coin (the kind in your hand) or a service for coin, the other party, the price, the
 * deadline and the penalty. Escrow is held at the hall; everything is said in plain words before it moves.
 */
public final class ContractsPage implements PlayerLife.Page {

    @Override
    public LifePage build(ServerPlayer p, PlayerLife.Ctx c) {
        ServerLevel level = p.serverLevel();
        Villages.Village v = PlayerLife.here(p);
        LifePage page = new LifePage("contracts", "Contracts", v == null ? "No town near" : "Signed at " + Villages.name(v.id()) + "'s hall, escrow held by the town",
            p.registryAccess());
        if (v == null) return page;
        if (c.tab.isEmpty()) c.tab = "mine";
        page.tab("mine", "Your contracts", c.tab.equals("mine")).tab("new", "New contract", c.tab.equals("new"));
        if (!Earnings.data(p).getBoolean("wpb/hint/contracts")) {
            page.box("First time here", List.of("A contract between two players: what each gives, by when, and a penalty. Coin goes into escrow at the hall "
                + "when you sign, and comes out only when the goods are handed in or both agree the work is done."), 4);
            page.buttons().button("Got it", "hint", null);
        }
        if (c.tab.equals("new")) newTab(level, p, v, page);
        else mineTab(level, p, v, page);
        return page;
    }

    void mineTab(ServerLevel level, ServerPlayer p, Villages.Village v, LifePage page) {
        List<Contracts.Deal> mine = Contracts.of(v.id(), p.getUUID());
        if (mine.isEmpty()) {
            page.text("No contracts yet. Make one under New contract.", 4);
            return;
        }
        long day = level.getDayTime() / 24000L;
        for (Contracts.Deal d : mine) {
            boolean seller = d.seller.equals(p.getUUID());
            String other = seller ? d.buyerName : d.sellerName;
            String state = switch (d.state) {
                case "DRAFT" -> seller ? "Waiting for " + other + " to sign" : "Waiting for you to sign";
                case "SIGNED" -> d.kind.equals("service") ? (d.sellerDone || d.buyerDone ? "One side says it's done" : "Signed: the work to be done") : seller ? "Signed: deliver by day " + d.deadline : "Signed: " + other + " delivers by day " + d.deadline;
                case "DONE" -> "Done and paid";
                case "DISPUTED" -> "Disputed: awaiting the town's hearing; escrow held";
                case "REFUSED" -> "Refused";
                default -> "Lapsed: the price went back, with the penalty";
            };
            String sub = (seller ? "You deliver " : other + " delivers ") + d.what() + " for " + d.price + "c, by day " + d.deadline + ", penalty " + d.penalty + "c. "
                + state + (d.state.equals("SIGNED") || d.state.equals("DRAFT") ? " (" + Math.max(0, d.deadline - day) + " days left)." : ".");
            LifePage.Row row = page.row(new ItemStack(Items.WRITABLE_BOOK), "Contract " + d.id + " with " + other, sub, Contracts.escrowedOf(d) > 0 ? Contracts.escrowedOf(d) + "c held" : "");
            if (d.state.equals("DRAFT") && !seller) {
                boolean sure = PlayerLife.ctx(p).args.getInt("sureSign") == d.id;
                row.button(sure ? "Confirm: sign (" + (d.price + d.penalty) + "c to escrow)" : "Sign (" + (d.price + d.penalty) + "c to escrow)", "sign", LifePage.args("id", d.id, "sure", sure), Market.coinsHeld(p) >= d.price + d.penalty,
                    "You need " + (d.price + d.penalty) + "c to sign").primary().button("Refuse", "refuse", LifePage.args("id", d.id));
            }
            if (d.state.equals("SIGNED") && seller && d.kind.equals("goods")) row.button("Hand in the goods", "deliver", LifePage.args("id", d.id)).primary();
            if (d.state.equals("SIGNED") && d.kind.equals("service")) {
                boolean said = seller ? d.sellerDone : d.buyerDone;
                row.button("It's done", "done", LifePage.args("id", d.id), !said, "You've said so: waiting for the other side").button("Dispute", "dispute", LifePage.args("id", d.id));
            }
        }
    }

    void newTab(ServerLevel level, ServerPlayer p, Villages.Village v, LifePage page) {
        List<ServerPlayer> others = new ArrayList<>();
        for (ServerPlayer o : level.getServer().getPlayerList().getPlayers()) {
            Villages.Village there = PlayerLife.here(o);
            if (o != p && there != null && there.id().equals(v.id())) others.add(o);
        }
        if (others.isEmpty()) {
            page.text("Nobody else is in town to make a contract with just now.", 4);
            return;
        }
        String[] ids = new String[others.size()], names = new String[others.size()];
        for (int i = 0; i < others.size(); i++) { ids[i] = others.get(i).getUUID().toString(); names[i] = others.get(i).getGameProfile().getName(); }
        page.choice("who", "With", ids, names, ids[0]);
        page.choice("kind", "You give", new String[]{ "goods", "service" }, new String[]{ "Goods (the kind in your hand)", "A service (say what below)" }, "goods");
        ItemStack hand = p.getMainHandItem();
        page.text(hand.isEmpty() ? "For goods: hold the kind you'll deliver in your hand." : "Goods: " + Goods.name(hand) + ", the kind in your hand.", hand.isEmpty() ? 2 : 4);
        page.choice("qty", "How many", new String[]{ "8", "16", "32", "64" }, new String[]{ "8", "16", "32", "64" }, "16");
        page.field("service", "The service", "", 60);
        page.choice("price", "They pay", new String[]{ "5", "10", "20", "50", "100" }, new String[]{ "5c", "10c", "20c", "50c", "100c" }, "10");
        page.choice("days", "By", new String[]{ "1", "3", "7" }, new String[]{ "Tomorrow", "In 3 days", "In a week" }, "3");
        page.choice("pen", "Penalty", new String[]{ "0", "2", "5", "10" }, new String[]{ "None", "2c", "5c", "10c" }, "2");
        page.text("Proposing puts your penalty in escrow. They sign by putting the price and their own penalty in escrow. "
            + "Hand the goods in here (or both agree the work is done) and it all pays out; miss the day and they have the price back and your penalty.", 4);
        page.buttons().button("Propose", "propose", null).primary();
    }

    @Override
    public String act(ServerPlayer p, PlayerLife.Ctx c, String action, CompoundTag args) {
        ServerLevel level = p.serverLevel();
        Villages.Village v = PlayerLife.here(p);
        if (v == null) return "!No town here.";
        CompoundTag f = args.getCompound("f");
        if (action.equals("hint")) { Earnings.data(p).putBoolean("wpb/hint/contracts", true); return ""; }
        if (action.equals("propose")) {
            ServerPlayer other;
            try { other = level.getServer().getPlayerList().getPlayer(UUID.fromString(f.getString("who"))); } catch (IllegalArgumentException e) { other = null; }
            if (other == null) return "!They've gone: pick somebody in town.";
            int price = ShopPage.parse(f.getString("price"), 10), days = ShopPage.parse(f.getString("days"), 3), pen = ShopPage.parse(f.getString("pen"), 2);
            String said = f.getString("kind").equals("service")
                ? (f.getString("service").isBlank() ? "!Say what the service is." : Contracts.propose(level, p, v.id(), other, "service", "", f.getString("service").trim(), 1, price, days, pen))
                : Contracts.proposeGoods(level, p, v.id(), other, ShopPage.parse(f.getString("qty"), 16), price, days, pen);
            if (!said.startsWith("!")) c.tab = "mine";
            return said;
        }
        Contracts.Deal d = Contracts.get(args.getInt("id"));
        if (d == null) return "!No such contract.";
        return switch (action) {
            case "sign" -> {
                if (d.price + d.penalty > 20 && !args.getBoolean("sure")) { c.args.putInt("sureSign", d.id); yield "!Press again to put " + (d.price + d.penalty) + "c in escrow and sign."; }
                c.args.remove("sureSign");
                yield Contracts.sign(level, p, d);
            }
            case "refuse" -> Contracts.refuse(level, p, d);
            case "deliver" -> Contracts.deliver(level, p, d);
            case "done" -> Contracts.done(level, p, d);
            case "dispute" -> Contracts.dispute(level, p, d);
            default -> null;
        };
    }
}
