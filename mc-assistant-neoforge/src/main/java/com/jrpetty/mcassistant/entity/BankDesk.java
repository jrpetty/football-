package com.jrpetty.mcassistant.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.UUID;

/**
 * [shops-homes] The bank's desk: your account (deposit, withdraw), your mortgage (repay it, or take one on the empty
 * house you stand in) and the statement, at the banker's desk. Every button is the bank's own business as it was
 * (Bank.handle): this is only its screen.
 */
final class BankDesk implements ServicePoints.Desk {

    static final BankDesk INSTANCE = new BankDesk();

    private BankDesk() {}

    @Override
    public String kind(ServiceRole role) {
        return "desk";
    }

    @Override
    public int fingerprint(ServicePoints.Visit visit) {
        ServerPlayer p = visit.player();
        return p == null ? 0 : Bank.balanceForTests(visit.village().id(), p.getUUID()) * 31 + Market.coinsHeld(p);
    }

    @Override
    public CompoundTag body(ServicePoints.Visit visit, String tab) {
        UUID id = visit.village().id();
        ServerPlayer p = visit.player();
        DeskRows d = new DeskRows(visit.level().registryAccess());
        if (!Bank.open(id) || p == null) {
            d.section("The bank").line("The bank isn't open for business yet.");
            return d.build();
        }
        int balance = Bank.balanceForTests(id, p.getUUID());
        int held = Market.coinsHeld(p);
        d.section("Your account").line(Bank.accountLine(id, p.getUUID()));
        DeskRows.amount(d.row("deposit", "Deposit", "Put coin in the vault", "You carry " + held + "; it earns a coin in a hundred a week.",
            new ItemStack(Items.GOLD_NUGGET), held <= 0 ? "You've no coin on you." : ""), 1, held, Math.min(10, held), 1);
        DeskRows.amount(d.row("withdraw", "Withdraw", "Take coin out", "In the bank: " + balance + ".",
            new ItemStack(Items.GOLD_INGOT), balance <= 0 ? "You've nothing in the bank." : ""), 1, balance, Math.min(10, balance), 1);
        Bank.Loan loan = Bank.playerLoan(id, p.getUUID());
        d.section("Your mortgage");
        if (loan != null) {
            d.line(Bank.loanLine(id, loan));
            DeskRows.amount(d.row("repay", "Repay", "Pay off some of it", loan.owed() + " owed, " + loan.weekly + " a week.",
                new ItemStack(Items.PAPER), held <= 0 ? "You've no coin on you." : ""), 1, Math.min(held, loan.owed()), Math.min(held, loan.weekly), 1);
        } else {
            d.line("Stand in an empty house of the town's and the bank will lend you four fifths of its price.");
            d.row("mortgage", "Ask", "A mortgage on this house", "A fifth down; the rest paid weekly from your account.",
                new ItemStack(Items.TRIPWIRE_HOOK), "");
        }
        d.section("The bank").line(Bank.line(id));
        return d.build();
    }

    @Override
    public ServicePoints.Result act(ServicePoints.Visit visit, String action, CompoundTag args) {
        ServerPlayer p = visit.player();
        UUID id = visit.village().id();
        if (p == null || !Bank.open(id)) return ServicePoints.Result.no("The bank isn't open for business yet.");
        int n = Math.max(0, args.getInt("amount"));
        String said = switch (action) {
            case "deposit" -> n <= 0 ? null : Bank.handle(visit.level(), id, p, "deposit " + n);
            case "withdraw" -> n <= 0 ? null : Bank.handle(visit.level(), id, p, "withdraw " + n);
            case "repay" -> n <= 0 ? null : Bank.handle(visit.level(), id, p, "repay " + n);
            case "mortgage" -> Bank.handle(visit.level(), id, p, "a mortgage on this house buy");
            default -> null;
        };
        if (said == null) return ServicePoints.Result.no("Choose how much first.");
        boolean moved = said.contains("Into the vault") || said.contains("Out of the vault") || said.startsWith("Paid") || said.contains("Paid off")
            || said.startsWith("It's yours");
        if (moved) ShopDesk.receipt(visit.level(), id, p.getUUID(), "Day " + visit.level().getDayTime() / 24000L + ": the bank: " + firstSentence(said));
        return moved ? ServicePoints.Result.done(said) : ServicePoints.Result.no(said);
    }

    private static String firstSentence(String s) {
        int dot = s.indexOf(". ");
        return dot > 0 ? s.substring(0, dot) : s;
    }
}
