package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.item.InventionItems;
import com.jrpetty.mcassistant.village.InventionTree;
import com.jrpetty.mcassistant.village.InventionTree.Invention;
import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [tech-tree] The player's and the operator's way into the town's inventions.
 * <ul>
 * <li><b>/village inventions</b>: the nearest town's tree in lines (what it knows, what it is at and how far, its scholars,
 *     the rate and why), and the world's firsts. {@code tree}: every invention there is, with what it needs and does.</li>
 * <li><b>suggest &lt;invention&gt;</b>: put one to the scholars; the leader weighs it at its next choice.</li>
 * <li><b>sponsor &lt;coins&gt;</b>: coin to the treasury for the work in hand, five coins a point of learning (half the
 *     invention's cost at most from players). <b>give</b>: the books, paper or ink in your hand to the scholars (into the
 *     stores; a book is two points of learning, a written one four, an enchanted one six). The same by right-clicking one
 *     of the academy's lecterns with them (or with coin).</li>
 * <li><b>pick &lt;invention&gt;</b>: a player who leads the town (or an operator) sets the scholars to it.</li>
 * <li>Operators: <b>now</b> (the work in hand finished), <b>grant &lt;invention&gt;</b>, <b>all</b> (everything of the
 *     town's age and before), <b>reset</b>, <b>morning</b> (the scholars' morning run now), <b>stage</b> (the pictures').</li>
 * </ul>
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class InventionCommands {

    private InventionCommands() {}

    static final SuggestionProvider<CommandSourceStack> IDS = (ctx, b) -> SharedSuggestionProvider.suggest(Inventions.allIds(), b);

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("inventions")
            .executes(ctx -> say(ctx, (level, v) -> {
                List<String> out = new ArrayList<>(Inventions.lines(level, v.id()));
                List<String> firsts = Inventions.firstsLines();
                if (!firsts.isEmpty()) out.add("First in the world: " + String.join("; ", firsts));
                return String.join("\n", out);
            }))
            .then(Commands.literal("tree").executes(ctx -> {
                List<String> out = new ArrayList<>();
                for (Invention i : Invention.values()) {
                    List<String> needs = new ArrayList<>();
                    for (Invention n : i.needs()) needs.add(n.id());
                    out.add(i.id() + " [" + i.branch.title + ", " + i.ageWords() + ", " + i.cost + " learning, " + i.days + "+ days"
                        + (needs.isEmpty() ? "" : ", needs " + String.join(" ", needs)) + "]: " + i.effect);
                }
                ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", out)), false);
                return out.size();
            }))
            .then(Commands.literal("suggest").then(Commands.argument("invention", StringArgumentType.word()).suggests(IDS)
                .executes(ctx -> withInvention(ctx, (level, v, i) -> Inventions.suggest(level, v, ctx.getSource().getTextName(), i)))))
            .then(Commands.literal("sponsor").then(Commands.argument("coins", IntegerArgumentType.integer(1, 10000))
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    return say(ctx, (level, v) -> Inventions.sponsor(level, v, p, IntegerArgumentType.getInteger(ctx, "coins")));
                })))
            .then(Commands.literal("give").executes(ctx -> {
                ServerPlayer p = ctx.getSource().getPlayerOrException();
                return say(ctx, (level, v) -> Inventions.donate(level, v, p, p.getMainHandItem()));
            }))
            .then(Commands.literal("pick").then(Commands.argument("invention", StringArgumentType.word()).suggests(IDS)
                .executes(ctx -> withInvention(ctx, (level, v, i) -> {
                    ServerPlayer p = ctx.getSource().getPlayer();
                    boolean leads = p != null && PlayerLeader.leads(v.id(), p.getUUID());
                    if (!leads && !ctx.getSource().hasPermission(2)) return "Only the town's leader chooses what its scholars take up. Try suggest.";
                    return Inventions.pick(level, v.id(), i, leads ? p.getGameProfile().getName() : "By decree");
                }))))
            .then(Commands.literal("now").requires(src -> src.hasPermission(2))
                .executes(ctx -> say(ctx, (level, v) -> Inventions.finishNow(level, v.id()))))
            .then(Commands.literal("grant").requires(src -> src.hasPermission(2))
                .then(Commands.argument("invention", StringArgumentType.word()).suggests(IDS)
                    .executes(ctx -> withInvention(ctx, (level, v, i) -> Inventions.grant(level, v.id(), i)))))
            .then(Commands.literal("all").requires(src -> src.hasPermission(2))
                .executes(ctx -> say(ctx, (level, v) -> Inventions.grantAll(level, v.id(), Villages.ageOf(v.id()).ordinal())
                    + " inventions granted to " + Villages.name(v.id()))))
            .then(Commands.literal("reset").requires(src -> src.hasPermission(2))
                .executes(ctx -> say(ctx, (level, v) -> {
                    Inventions.reset(v.id());
                    return Villages.name(v.id()) + "'s inventions forgotten (its scholars kept).";
                })))
            .then(Commands.literal("morning").requires(src -> src.hasPermission(2))
                .executes(ctx -> say(ctx, (level, v) -> {
                    Inventions.newMorningForTests(v.id());
                    Inventions.morning(level, v, level.getDayTime() / 24000L);
                    return String.join("\n", Inventions.lines(level, v.id()));
                })))
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2))
                .executes(ctx -> say(ctx, (level, v) -> String.join("\n", stage(level, v, BlockPos.containing(ctx.getSource().getPosition()))))));
    }

    interface TownAct { String run(ServerLevel level, Villages.Village v); }

    interface InventionAct { String run(ServerLevel level, Villages.Village v, Invention i); }

    static int say(CommandContext<CommandSourceStack> ctx, TownAct act) {
        ServerLevel level = ctx.getSource().getLevel();
        Villages.Village v = Villages.nearest(level, BlockPos.containing(ctx.getSource().getPosition()), 200);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village near enough."));
            return 0;
        }
        String out = act.run(level, v);
        ctx.getSource().sendSuccess(() -> Component.literal(out), false);
        return 1;
    }

    static int withInvention(CommandContext<CommandSourceStack> ctx, InventionAct act) {
        Invention i = InventionTree.byId(StringArgumentType.getString(ctx, "invention"));
        if (i == null) {
            ctx.getSource().sendFailure(Component.literal("No invention called that. /village inventions tree lists them."));
            return 0;
        }
        return say(ctx, (level, v) -> act.run(level, v, i));
    }

    // ------------------------------------------------------------------ the academy's lecterns

    /** The academy whose lectern this is, or null. */
    @Nullable
    static Villages.Village academyAt(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockState(pos).getBlock() instanceof LecternBlock)) return null;
        Villages.Village v = Villages.nearest(level, pos, Villages.VILLAGE_RANGE);
        if (v == null) return null;
        Ledger.Building b = Villages.builtStructure(v.id(), InventionWorks.ACADEMY);
        if (b == null) return null;
        return Scholars.lecterns(level, b).contains(pos) ? v : null;
    }

    /** One of the academy's lecterns, right-clicked with books, paper, ink or coin: given to the scholars. */
    @SubscribeEvent
    public static void onUseBlock(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getLevel() instanceof ServerLevel level) || !(e.getEntity() instanceof ServerPlayer p)) return;
        ItemStack held = p.getItemInHand(e.getHand());
        boolean coin = Market.isCoin(held);
        boolean book = held.is(Items.BOOK) || held.is(Items.ENCHANTED_BOOK) || held.is(Items.PAPER) || held.is(Items.INK_SAC);
        if (!coin && !book) return;
        Villages.Village v = academyAt(level, e.getPos());
        if (v == null) return;
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        if (e.getHand() != InteractionHand.MAIN_HAND) return;
        String said = coin ? Inventions.sponsor(level, v, p, p.isShiftKeyDown() ? held.getCount() : 1) : Inventions.donate(level, v, p, held);
        p.displayClientMessage(Component.literal(said).withStyle(ChatFormatting.GOLD), false);
    }

    // ------------------------------------------------------------------ the pictures' stage

    /**
     * The inventions set out for the pictures (smoke.py's tech_tree_stage): beside the nearest town, on ground levelled
     * for them, the academy, the clocktower, the watermill, the foundry and the aqueduct in a row (from a palette, as the
     * showcase's are); the town given what they need and a scholar at the academy's lectern, the work in hand half done;
     * and an armour stand in steel with a halberd, another with a pike and a longbow. Says where to look ("VIEW ...").
     */
    static List<String> stage(ServerLevel level, Villages.Village v, BlockPos at) {
        UUID id = v.id();
        int x = at.getX(), z = at.getZ();
        level.getChunk(x >> 4, z >> 4);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        Showcase.stage(level, x - 34, x + 34, z - 10, z + 16, y);
        long day = level.getDayTime() / 24000L;
        for (Invention i : new Invention[]{ Invention.HEAVY_PLOUGH, Invention.WINDMILL, Invention.TREADWHEEL_CRANE, Invention.CLOCKTOWER,
            Invention.AQUEDUCT, Invention.WATERMILL, Invention.PIKE, Invention.STEEL, Invention.LONGBOW, Invention.HALBERD }) {
            if (!Inventions.known(id, i)) Inventions.complete(level, id, i, day, "were granted");
        }
        List<String> out = new ArrayList<>();
        String[] row = { InventionWorks.ACADEMY, InventionWorks.CLOCKTOWER, InventionWorks.WATERMILL, InventionWorks.FOUNDRY, InventionWorks.AQUEDUCT };
        int[] dx = { -26, -13, 0, 13, 26 };
        BlockPos academy = null;
        for (int k = 0; k < row.length; k++) {
            BlockPos p = new BlockPos(x + dx[k], y, z);
            BuildGoal.stamp(level, row[k], p, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(id, row[k], p, Direction.NORTH);
            if (k == 0) academy = p;
            out.add("VIEW tech-" + (k + 1) + "-" + row[k] + " " + (p.getX() + 5) + " " + (y + 3) + " " + (p.getZ() + 12) + " " + p.getX() + " " + (y + 3) + " " + p.getZ());
        }
        // A scholar at the academy's lectern, the work in hand half done.
        Scholars.appoint(level, v, day);
        if (Scholars.appointed(id).isEmpty()) for (VillageFolkEntity f : Civics.grown(id)) { Scholars.appointForTests(id, f); break; }
        Invention now = Inventions.current(id);
        if (now == null) now = Inventions.choose(level, id, day);
        if (now != null) {
            Inventions.progressForTests(id, now.cost * 50);
            Inventions.paidForTests(id, true);
        }
        Ledger.Building ab = Villages.builtStructure(id, InventionWorks.ACADEMY);
        List<BlockPos> desks = ab == null ? List.of() : Scholars.lecterns(level, ab);
        for (UUID s : Scholars.appointed(id)) {
            if (desks.isEmpty() || !(level.getEntity(s) instanceof VillageFolkEntity f)) break;
            BlockPos d = desks.get(0).south();
            f.moveTo(d.getX() + 0.5, d.getY(), d.getZ() + 0.5, 180.0F, 0.0F);
            f.getNavigation().stop();
            out.add("SCHOLAR " + f.displayNameCap() + " at " + d.getX() + " " + d.getY() + " " + d.getZ());
            break;
        }
        // The new arms on stands before the foundry.
        BlockPos stands = new BlockPos(x + 13, y, z + 9);
        stand(level, stands.west(2), InventionItems.HALBERD.get().getDefaultInstance(), true);
        stand(level, stands, InventionItems.PIKE.get().getDefaultInstance(), false);
        stand(level, stands.east(2), InventionItems.LONGBOW.get().getDefaultInstance(), false);
        out.add("VIEW tech-6-arms " + stands.getX() + " " + (y + 2) + " " + (stands.getZ() + 5) + " " + stands.getX() + " " + (y + 1) + " " + stands.getZ());
        if (academy != null) {
            out.add("VIEW tech-7-scholar " + (academy.getX() + 1) + " " + (y + 2) + " " + (academy.getZ() + 2) + " " + academy.getX() + " " + (y + 1) + " " + (academy.getZ() - 2));
        }
        out.add("TECH " + x + " " + y + " " + z + " " + (now == null ? "nothing in hand" : now.title + " half done"));
        return out;
    }

    /** An armour stand with a weapon, in steel if {@code steel}. */
    static void stand(ServerLevel level, BlockPos at, ItemStack weapon, boolean steel) {
        ArmorStand a = new ArmorStand(level, at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        a.setYRot(180.0F);
        a.setItemSlot(EquipmentSlot.MAINHAND, weapon);
        if (steel) {
            a.setItemSlot(EquipmentSlot.HEAD, InventionItems.STEEL_HELMET.get().getDefaultInstance());
            a.setItemSlot(EquipmentSlot.CHEST, InventionItems.STEEL_CHESTPLATE.get().getDefaultInstance());
            a.setItemSlot(EquipmentSlot.LEGS, InventionItems.STEEL_LEGGINGS.get().getDefaultInstance());
            a.setItemSlot(EquipmentSlot.FEET, InventionItems.STEEL_BOOTS.get().getDefaultInstance());
        }
        level.addFreshEntity(a);
    }
}
