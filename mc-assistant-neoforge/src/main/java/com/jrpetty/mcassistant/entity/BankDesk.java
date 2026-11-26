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
        Bank.Loan l = loan;
        d.section("Your mortgage");
        if (loan != null) {
            d.line(Bank.loanLine(id, loan));
            DeskRows.amount(d.row("repay", "Repay", "Pay off some of it", loan.owed() + " owed, " + loan.weekly + " a week.",
                new ItemStack(Items.PAPER), held <= 0 ? "You've no coin on you." : ""), 1, Math.min(held, loan.owed()), Math.min(held, loan.weekly), 1);
        } else {
            d.line("Choose a house, your deposit and the term: the rate follows how much you put down and your credit score.");   // [wpc] Mortgages
        }
        d.row("mortgages", "Open", l == null ? "Get a mortgage" : "Your mortgage: overpay, remortgage, port", "The deposit, the rate, the term, what you can afford",
            new ItemStack(Items.TRIPWIRE_HOOK), "");
        d.row("debts", "Open", "Your debts and credit score", Debts.status(id, p.getUUID(), visit.level().getDayTime() / 24000L), new ItemStack(Items.PAPER), "");
        d.row("will", "Open", "Your will", Wills.has(id, p.getUUID()) ? "Made and witnessed: change it here" : "Not made yet: say who gets what you own", new ItemStack(Items.WRITABLE_BOOK), "");
        // [player15] A business of your own (the bank lends what the charter is short of), and the town's capital market.
        d.section("A business of your own");
        d.row("startbiz", "Start", "Start a business", "The charter is " + Enterprise.CHARTER_FEE + " coins; the bank lends you what you're short.",
            new ItemStack(Items.WRITABLE_BOOK), "");
        d.section("Savings, loans and bonds").line(FolkCredit.playerLine(p, id));
        DeskRows.amount(d.row("club_in", "Save", "Into your savings", "Interest every week.", new ItemStack(Items.GOLD_NUGGET), held <= 0 ? "You've no coin on you." : ""),
            1, Math.max(1, held), Math.max(1, Math.min(10, held)), 1);
        DeskRows.amount(d.row("club_out", "Draw", "Out of your savings", "", new ItemStack(Items.GOLD_INGOT), ""), 1, 1000, 10, 1);
        DeskRows.amount(d.row("borrow", "Borrow", "A loan", "Repaid weekly, with interest.", new ItemStack(Items.PAPER), ""), 1, 500, 10, 1);
        DeskRows.amount(d.row("club_repay", "Repay", "Pay a loan down", "", new ItemStack(Items.PAPER), held <= 0 ? "You've no coin on you." : ""), 1, Math.max(1, held), Math.max(1, Math.min(10, held)), 1);
        DeskRows.amount(d.row("bonds", "Buy", "The town's bonds", TownBonds.line(id), new ItemStack(Items.MAP), held <= 0 ? "You've no coin on you." : ""), 1, Math.max(1, held), Math.max(1, Math.min(10, held)), 1);
        d.row("bonds_collect", "Collect", "Your bonds' interest and rents", TownBonds.playerLine(p, id), new ItemStack(Items.EMERALD), "");
        d.row("capital", "Open", "Land titles and shares", "Buy the title to a plot, or shares in a firm", new ItemStack(Items.FILLED_MAP), "");
        d.section("The bank").line(Bank.line(id));
        if (visit.point() != null) {
            d.row("use", "Read", "The bank's ledger", "Open on its desk for anybody to read (crouch and click it to go straight to it).",
                new ItemStack(Items.WRITTEN_BOOK), "");
        }
        return d.build();
    }

    @Override
    public ServicePoints.Result act(ServicePoints.Visit visit, String action, CompoundTag args) {
        ServerPlayer p = visit.player();
        UUID id = visit.village().id();
        if (action.equals("use")) return ServicePoints.useItYourself(visit);
        // [player15] the business and capital rows
        if (p != null && action.equals("startbiz")) {
            p.server.execute(() -> PlayerLife.open(p, "start", LifePage.args("loan", true)));   // after the counter's own answer
            return ServicePoints.Result.said("Here's how to start one.");
        }
        if (p != null && (action.equals("mortgages") || action.equals("debts") || action.equals("will") || action.equals("mortgage"))) {   // [wpc] Mortgages, Debts, Wills
            String page = action.equals("debts") ? "debts" : action.equals("will") ? "will" : "mortgage";
            p.server.execute(() -> PlayerLife.open(p, page, null));
            return ServicePoints.Result.said(page.equals("mortgage") ? "The mortgage desk." : page.equals("debts") ? "Your ledger." : "Your will.");
        }
        if (p != null && action.equals("capital")) {
            p.server.execute(() -> PlayerLife.open(p, "all", LifePage.args("tab", "capital")));
            return ServicePoints.Result.said("The capital market.");
        }
        if (p != null && (action.startsWith("club_") || action.equals("borrow") || action.startsWith("bonds"))) {
            int m = Math.max(0, args.getInt("amount"));
            String said = switch (action) {
                case "club_in" -> FolkCredit.playerDeposits(visit.level(), p, id, m);
                case "club_out" -> FolkCredit.playerWithdraws(visit.level(), p, id, m);
                case "club_repay" -> FolkCredit.playerRepays(visit.level(), p, id, m);
                case "borrow" -> FolkCredit.playerBorrows(visit.level(), p, id, m);
                case "bonds" -> TownBonds.playerBuys(visit.level(), p, id, m);
                default -> TownBonds.playerCollects(p, id);
            };
            return ServicePoints.Result.done(said);
        }
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
