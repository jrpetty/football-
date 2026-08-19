package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.BowAttackGoal;
import com.jrpetty.mcassistant.entity.goal.BreedGoal;
import com.jrpetty.mcassistant.entity.goal.BridgeGoal;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.entity.goal.CleanupGoal;
import com.jrpetty.mcassistant.entity.goal.ClearGoal;
import com.jrpetty.mcassistant.entity.goal.CraftGoal;
import com.jrpetty.mcassistant.entity.goal.CreeperDodgeGoal;
import com.jrpetty.mcassistant.entity.goal.DepositGoal;
import com.jrpetty.mcassistant.entity.goal.FarmGoal;
import com.jrpetty.mcassistant.entity.goal.FishGoal;
import com.jrpetty.mcassistant.entity.goal.FollowOwnerGoal;
import com.jrpetty.mcassistant.entity.goal.GatherGoal;
import com.jrpetty.mcassistant.entity.goal.GiveGoal;
import com.jrpetty.mcassistant.entity.goal.HerdGoal;
import com.jrpetty.mcassistant.entity.goal.HuntGoal;
import com.jrpetty.mcassistant.entity.goal.MineGoal;
import com.jrpetty.mcassistant.entity.goal.PatrolGoal;
import com.jrpetty.mcassistant.entity.goal.RecoverGoal;
import com.jrpetty.mcassistant.entity.goal.RetreatGoal;
import com.jrpetty.mcassistant.entity.goal.ShearGoal;
import com.jrpetty.mcassistant.entity.goal.BoatGoal;
import com.jrpetty.mcassistant.entity.goal.EnchantGoal;
import com.jrpetty.mcassistant.entity.goal.EscapeGoal;
import com.jrpetty.mcassistant.entity.goal.ExploreGoal;
import com.jrpetty.mcassistant.entity.goal.DiagnosticsGoal;
import com.jrpetty.mcassistant.entity.goal.NetherGoal;
import com.jrpetty.mcassistant.entity.goal.NightShelterGoal;
import com.jrpetty.mcassistant.entity.goal.SmeltGoal;
import com.jrpetty.mcassistant.entity.goal.SortGoal;
import com.jrpetty.mcassistant.entity.goal.TorchAreaGoal;
import com.jrpetty.mcassistant.entity.goal.TravelGoal;
import com.jrpetty.mcassistant.entity.goal.WithdrawGoal;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The companion. Player-parity on purpose: it has normal mob health, walks
 * everywhere, eats real food to heal, uses the right tool for the job (and
 * wears tools out like a player), and carries loot in a real 27-slot
 * inventory that it deposits into real chests.
 *
 * Modes are standing orders: FOLLOW trails the owner, GUARD holds but fights,
 * STAY parks. Work (gather/deposit/craft/withdraw/farm/build) runs through a
 * sequential job queue. It can hold standing supply orders ("keep the chest
 * stocked with 64 logs"), and with autonomy on it picks role-based work by
 * itself when idle — the seed of the self-running town.
 */
public class AssistantEntity extends PathfinderMob implements RangedAttackMob {

    public enum Mode { STAY, FOLLOW, GUARD }

    /** A full-time specialty the bot is pinned to at a fixed spot: FARM keeps a
     *  plot planted/watered/harvested; WOOD fells trees and replants every stump;
     *  RANCH breeds/shears/culls the herd; GUARD holds the area and keeps it lit;
     *  SMELT keeps the furnaces fed from the input chest; HAUL runs cargo from
     *  the post's chest home. Set by "farm here", "chop trees here", "ranch
     *  here", "guard here", "run the smeltery", "haul here". */
    public enum StationTask {
        NONE("none", "Unassigned"),
        FARM("farming", "Farmer"),
        WOOD("forestry", "Lumberjack"),
        MINE("mining", "Miner"),
        RANCH("ranching", "Rancher"),
        GUARD("guard duty", "Guard"),
        SMELT("smeltery", "Smelter"),
        FISH("fishing", "Fisher"),
        STORE("storekeeping", "Storekeeper"),
        HAUL("hauling", "Hauler");

        public final String label;   // lower-case, for sentences
        public final String title;   // display name, for the management screen
        StationTask(String label, String title) { this.label = label; this.title = title; }

        public static StationTask byOrdinal(int i) {
            StationTask[] all = values();
            return all[Math.floorMod(i, all.length)];
        }
    }

    public enum Role {
        NONE, MINER, LUMBERJACK, FARMER, BUILDER;

        @Nullable
        public static Role fromWord(String w) {
            return switch (w) {
                case "miner", "mining" -> MINER;
                case "lumberjack", "logger", "woodcutter" -> LUMBERJACK;
                case "farmer", "farming" -> FARMER;
                case "builder", "building" -> BUILDER;
                default -> null;
            };
        }
    }

    /** A supply contract: keep ~amount of kind in the nearest chest, forever. */
    public record StandingOrder(GatherGoal.Kind kind, int amount) {}

    /** A recurring chore the assistant does to itself on a timer — "tend the
     *  farm every 20 minutes". Each kind maps to a normal job; the schedule
     *  just re-queues it whenever it's due and the assistant is idle. */
    public enum RoutineKind {
        FARM("tend the farm"),
        DEPOSIT("stash surplus in a chest"),
        SORT("sort the storage"),
        CLEANUP("pick up loose items"),
        HUNT("top up the food supply"),
        LIGHT("light up the area");

        public final String label;
        RoutineKind(String label) { this.label = label; }

        @Nullable
        public static RoutineKind fromWord(String w) {
            return switch (w) {
                case "farm", "farming", "crops", "field", "harvest" -> FARM;
                case "deposit", "stash", "store", "unload", "dropoff" -> DEPOSIT;
                case "sort", "organize", "organise", "tidy" -> SORT;
                case "cleanup", "clean", "pickup" -> CLEANUP;
                case "hunt", "hunting", "food" -> HUNT;
                case "light", "torch", "lighting", "lights" -> LIGHT;
                default -> null;
            };
        }

        public Job toJob() {
            return switch (this) {
                case FARM -> Job.farm();
                case DEPOSIT -> Job.deposit();
                case SORT -> Job.sort();
                case CLEANUP -> Job.cleanup();
                case HUNT -> Job.hunt(null, 4);
                case LIGHT -> Job.torchArea(8);
            };
        }
    }

    /** One scheduled chore: run `kind` every `interval` ticks. `nextTick` is the
     *  entity-age at which it next fires (re-seeded on load, since age resets). */
    public static final class Routine {
        public final RoutineKind kind;
        public int interval;
        public int nextTick;
        public Routine(RoutineKind kind, int interval, int nextTick) {
            this.kind = kind;
            this.interval = interval;
            this.nextTick = nextTick;
        }
    }

    public static final int INVENTORY_SIZE = 27;
    /** How many specialists one player may run at once — config/crew.maxPerPlayer. */
    public static int maxPerOwner() { return com.jrpetty.mcassistant.AssistantConfig.maxCrew(); }

    /** Build stamp — say "version" to hear it. Bumped whenever features land, so
     *  you can tell at a glance whether the loaded jar is the current one. */
    public static final String BUILD_TAG =
        "2026-07-b97 · NAMING GROUND WORKS THE WAY IT READS: the book's Name button said it cycled the pool, but it always grabbed the FIRST free name — and since renaming North Farm frees North Farm, pressing it just swapped the same two names back and forth forever while the other ten sat unreachable. It now walks the pool from wherever the plot's name stands, wrapping round, so every name gets its turn · AND THE POOL DOUBLED: twenty-four ground names now — The Hollow, Millbrook, Long Acre, Cinder Row, High Meadow and friends — so a full book of twelve plots still leaves twelve names of headroom to cycle through instead of none · FREIGHT GREW UP: RELAYS — one hauler's delivery chest can be another's pickup, and the cargo crosses the base leg by leg, each leg one bot; link the route and the hauler says out loud whose relay it joins, and warns you if two routes would just feed each other the same cargo forever · AND RAILS: if a rail line connects a route's two chests and there's a chest minecart in stock, the route runs ON the line — the cart is set on the rails at the pickup, loaded straight from the chest, towed along the track behind the hauler and emptied into the delivery chest at the far end. Twenty-seven slots a trip instead of a packful. The rail is an upgrade and never a dependency: no rails, no cart, or a line that jams, and the hauler walks the route exactly as before — a stuck line is flagged for five minutes and retried, not obeyed · FULL STORES SHOW ON THE MAP: any plot whose chests are nearly out of slots gets a little flagged chest drawn over it from above, so the bottleneck is visible from the map before the crew stalls on it — and the book's ledger line says how full the picked plot's stores run · ALL FREE, ONE CLICK: a button on the book puts every free hand nearby onto the picked plot at once — new ground gets crewed in one press instead of eight · AND UNDO: the book remembers how the last-ordered bot stood — plot, trade, the lot — and one press puts it back exactly there. Pressed twice it swaps straight back, because the restore takes its own snapshot first: undo is its own redo · b95's condition survey also stopped counting animals with a constructor 1.21 does not have — the ranch check builds its box from plain numbers now · THE BOOK READS LIKE A LEDGER NOW: every plot row shows what the ground banked TODAY — a per-day number against each name, so the farm that is quietly underperforming stops hiding among the ones that are fine · AND EVERY PLOT HAS A CONDITION: dark corners where things can spawn, farmland gone dry, a pen with no breeding pair left — rolled into one percentage, shown for the picked plot and flagged on its row with an amber or red spine the moment it slips below eighty, which is BEFORE the output drops rather than after · BLUEPRINTS: press Copy on a working plot and its whole shape goes in your pocket — footprint, dig depth, trade, shift, and where every chest stands relative to the centre — then stand somewhere new and press Stamp: the ground is staked, named and typed in one click, and sparks trace the outline and hang columns where the chests belong, so you can see what to build before you build it. The blueprint survives death the same way the book does · THE PLOT CARRIES THE SHIFT: days, nights or round the clock is a property of the GROUND now, cycled from its own button in the book, and everyone assigned inherits it on the spot — a night quarry stays a night quarry no matter who you click onto it · IRRIGATION IS PLANNED, NOT PLUGGED: a farmer with a water bucket used to pour it into the first dry hole it saw; it now surveys the dry ground and picks the spot whose four-block reach hydrates the MOST of it, so each bucket buys the biggest circle of wet farmland it can · AND THE MAP DRAG SHOWS IN THE WORLD: while you drag a plot on the map, the live rectangle sparks along its perimeter out in the world itself, so you size ground against the actual field instead of squinting at a minimap · OFF THE PLOT MEANS STANDING BY, NOT DEMOTED: clicking a worker off a plot used to ride the zone-clearing path, whose side effect wipes the TRADE — a farmer toggled off became an Unassigned drifter running the survival brain, wandering off to chop wood. Off the plot now means exactly what the bot says: it keeps its trade, drops its queue, and stands where it is until your next word — and clicking it onto ground re-arms it in the same breath, so the toggle round-trips cleanly · THE HAULER IS A ROUTE NOW: two chests, linked with the wand in order — click the PICKUP first, the DELIVERY second — and it ferries everything between them. No zone, no home point, no minimum distance, no Drop-off button: if both ends stand, the route runs. It loads the pickup clean, walks the full load over (a part-load goes too once it has sat a minute, so a trickle still crosses the base), unloads into THE delivery chest rather than whichever chest is nearest, and walks back. Click a linked chest again to unlink it and re-route · AND CREWING A PLOT IS NOT A CLASH: the overlap warning fired on the IDENTICAL zone, which is exactly what the plot book creates on purpose — every assignment made the crew complain about its own crewmates. The same zone is a crew; only ground that PARTIALLY overlaps different ground gets the warning now · a plot-assigned hauler also no longer ping-pongs between its route and its zone: the route outranks the ground · THE PLOT BOOK CLOSES ITS LOOPS: a plot has a TRADE now — press Trade on the book and the ground becomes farmer ground or miner ground; everyone standing on it retrains on the spot, and anyone you click onto it afterwards takes the trade with the ground, because assigning someone to North Farm means farming it. Bare ground works the other way: workers keep their own trades and the plot learns its type from the first one who has one — so the dead end where a fresh hire on fresh ground stood doing nothing forever is closed from both sides · EVERY WAY OF MARKING GROUND FEEDS ONE BOOK: Set Area, Bigger, Smaller and Save Patch now stake like the wand and the map already did, keeping the plot's name when the ground is the same ground — there is no longer any zone the book cannot see · EMPTY PLOTS SHOW ON THE MAP: staked ground with nobody on it used to exist nowhere a player could look; it draws as a quiet slate outline with its name until someone works it · AND THE BOOK SURVIVES YOUR DEATH: player data is only copied through a respawn under one specific subtree, and the book lived outside it — one death quietly erased every plot and saved patch you had, and had done since saved patches shipped. It is copied across now · THE PLOT BOOK: ground is a first-class thing now. Stake a plot — with the wand, with a drag on the map, or with one button right where you stand — and it goes into your plot book NAMED, automatically, from the name pool: North Farm, The Quarry, West Mine. Press B anywhere and the book opens: every plot on the left with its size and how many hands are on it, your whole crew on the right — pick a plot, click a hand, and they are on it; click again and they are off. Works for every trade: bare ground takes workers as whatever they already are, ground saved with a trade hands the trade to anyone who has none. Stake, name-cycle, forget, all from the same four buttons, and the screen always shows what the server believes because the list IS the server's reply — never a stale client copy. Re-marking or widening the same field keeps its name, because it is still the same field · THE QUARRY SINKS A LADDER SHAFT: a quarry with ladders in stock now drops ONE vertical column through the middle of its patch and cuts every gallery off it like spokes, lining each cell with a rung the instant it opens so the shaft is never an unlit hole to fall down. The way home stops being a thousand-block switchback back through every level and becomes a straight climb — minutes to seconds, which is the difference between a deep quarry being worth running and being a novelty. Run two finds run one's shaft, rides it to the foot, and carries on down instead of sinking a second beside it · THE CLIMB IS HAND-DRIVEN ON PURPOSE: vanilla ground pathfinding reasons in steps and falls and has no vertical climb node at all, so asking it to route up a ladder is asking it to fail — the bot walks to the column, which is a normal walk on the level it is already on, and the vertical part is driven tick by tick the same way the dig loop is. If it is not rising after five seconds, or cannot reach the column at all, it abandons the shaft and walks the breadcrumb trail instead: the ladders are an upgrade that can never become a dependency, and no ladders at all simply means the b88 staircase quarry · AND THE ANTI-HOP CAP HAD TO LEARN ABOUT LADDERS: climbing IS holding jump, so the one-jump-a-second gate from b72 would have let a bot rise a block, slide back, and rise again — reading exactly like being stuck at the bottom of its own shaft. Climbing is exempt now, the way fluids already were · THE QUARRY CANNOT SPIN OR STRAND: b87 shipped a level-drop that trusted the ground it was standing on. A gallery that ended AT the patch edge dropped a level, turned, found it could not descend either, fell back to cutting — which instantly re-ended at the same edge — and dropped another level. Two ticks a level, nothing dug: a Y64-to-Y-54 quarry would rip through all twenty-nine levels in about a second and announce itself finished having mined not one block. Now a level can only be dropped after the gallery actually CUT something, only when there is somewhere in-zone to descend to (checked before committing, not discovered afterwards), and never more than thirty-two times — so the descent strictly decreases and the run provably ends · THE DESCENT LOOKS ALL FOUR WAYS: it used to try two directions before calling itself boxed in, so a corner could refuse a descent that was one more turn away · IT AIMS BACK INTO THE PATCH: a gallery ends at the far edge by definition, which is the worst place to start digging downward, so the next level heads for the middle · AND IT CLIMBS OUT: finishing at the bottom of a terraced pit used to hand the pathfinder a bot inside four-block walls and ask it to reach a chest at the surface. It now walks home along its own workings, and a waypoint it cannot reach is skipped for the next one up rather than stranding it. The trail is a sliding window too — it used to STOP recording at four hundred steps, which meant the newest steps were the missing ones and the walk home began from a waypoint several levels above the bot: precisely the one it could not reach · A SIGN ON A CHEST MAKES IT YOURS: hang one blank sign on any chest — side or lid, it does not have to say a word — and every assistant stops seeing it. Nothing taken, nothing stashed, never counted toward a checklist, no hauler routing to it. It works because every chest question in the whole mod passes through one lookup, so the mark is the entire feature: no typing, no menu, no per-bot setting · A TOOL IS REPLACED BEFORE IT SNAPS, NOT AFTER: when the last hoe or pickaxe within reach is down to its final fifteen percent and there is no fresh one in pack or chest, the replacement is made THEN — while the bot can still work — and at the best tier the stores can pay for, so a crew with iron coming in quietly climbs off wooden kit as it wears through it, which since the tool now sets the pace is a real speed-up · AND THE MINER CUTS A QUARRY: press the fourth button in its depth slot and it stops cutting one lonely gallery — it cuts a level, drops four blocks, turns a quarter, cuts the next, and terraces its way down to whatever depth you set with the three buttons beside it. It breadcrumbs every floor as it goes, so a full pack still walks home along its own workings · THE BLUR IS DEAD, AND IT WAS NEVER CONTRAST: since 1.21 the game runs a gaussian BLUR SHADER inside every menu's background pass — and the mod's screens painted their panels first, then handed over to the vanilla loop, which ran that background pass a SECOND time. Everything the mod had already drawn — every panel, every line of text — was literally gaussian-smeared each frame, and the vanilla buttons were drawn crisp on top afterwards. That is why the buttons always looked sharp while everything around them looked soft, why the pack screen (a different code path that only blurs once) never had the problem, and why four palette overhauls could not fix it: the text was smeared AFTER being drawn, at any contrast. Every screen is rebuilt so all of its painting happens inside the one and only background pass — blur the world once, then panel, then text, then buttons, all pixel-crisp · THE TOOL FINALLY SETS THE PACE: a wooden tool works at six seconds an action, stone at five and a quarter, iron at four and a half, gold at four, diamond at three and three quarters, netherite at three — and every bonus a specialist earns (levels, branch, crewmates, quirk, dinner) pulls against THAT base, so a fed veteran on wood is still slower than a recruit handed netherite. The old code fed the tool's dig speed into a three-second floor that every tier from wood up already beat, so a netherite pick worked at exactly the pace of a wooden one — the tool you paid diamonds for changed nothing. Shears, rods and free hands keep the old base: there is no better tool to buy them · TWO FROM THE FIELD: EXPANDING A FINISHED PATCH WORKS NOW — a bot that had worked out its ground kept standing in the done corner after you widened it, for three stacked reasons: changing a zone never cleared the old nothing-left-here verdicts (the idle back-off, the dead-end memory), so the fresh ground started life pre-judged; the roam-the-patch walk only fired on zones wider than radius 16, while every local search only sees about 16 blocks from where the bot STANDS — so a zone in the gap, or a bot standing off-centre of any zone, found nothing and never moved either. A changed zone now resets every verdict and kicks the brain the same tick, and an idle roamer strolls to a random spot of its patch every fifteen seconds regardless of size, so the far corner always gets looked at · AND ONE AXE, NOT THE RACK — the restock used one predicate for a trade's whole kit and scooped up to sixteen of anything it matched, which is how a lumberjack emptied the chest of all five axes in one visit. Tools and stock are separate restocks now: the tool tops up to exactly ONE and only when it has none, the consumables still take a proper handful — and exactly one tool counts as working kit at stash time, so a bot already sitting on five axes hands four back at its next deposit instead of hoarding the rack · EIGHT MORE HANDS-OFF: OUT OF KIT, IT CRAFTS ONE — the planner reads the game's own recipe book, so a farmer with planks in the chest makes its own hoe instead of standing there asking, wood and iron pulled from the stores, once a minute at most · A FULL CHEST SENDS THE LOAD NEXT DOOR — the deposit walks to the next linked chest with room and takes the loading pause again, instead of writing the whole station off for five minutes · ESCORT DUTY — a guard cycles through the crew from its orders sheet and shadows the one you pick, fighting whatever comes near its ward and never getting dragged back to the patch · SHIELD UP — a guard given a shield raises it while a threat closes or shoots and drops it to swing, with vanilla's own blocking rules doing the damage math · RALLY TO THE BREACH — one guard engaging is every guard's business: mates on the same patch converge on the threat, each keeping its own rules about creepers · HOMECOMING — a hand that hasn't seen you in a game-day looks up, waves, and says so when you walk back in · QUIET MODE — one button mutes a bot's routine chatter for good, keeping the lines that matter: needs, deaths, paydays · AND THE MAP TELLS YOU WHEN TO VISIT THE FARM — a farm patch warms toward harvest-gold as its crops ripen, measured by the same survey the farmer already runs · NINE MORE, AND THE OPERATION RUNS ON ITS OWN KITCHEN: a smelter with no ore to run COOKS — the rancher's raw drops become the full-pace meals the diet system wants, banked for the supply chain to hand to whoever is hungry; the pen feeds the whole operation with nobody lifting a finger · eggs are swept the moment they exist, because an egg despawns in five minutes and general litter waits for three drops to pile up · A MINER WITH A FULL PACK WALKS BACK UP ITS OWN STAIRS — every step of the descent is remembered and retraced, instead of handing the pathfinder a bot at Y-50 and letting it wander whatever caves the dig had breached · ARMOR IN THE LINKED CHEST IS WORN: the same rule tools follow, one piece per kit check, the old piece left in the chest in trade, with the equip sound so you hear it happen · DRAW PLOTS ON THE MAP: drag a rectangle on open ground and click a chip to hand it over, or start the drag on an existing patch to resize it in place — the server clamps the size and never trusts the client past a footprint · RIGHT-CLICK GROUND TO NAME IT: North Farm, The Quarry — a preset list, no typing, shared by everyone working the same patch, written on the map and in every status line · OPERATION TOTALS: one page summing everything the whole crew has ever produced with a per-day rate, because 4,000 wheat is a career but 400 a day is a farm that feeds a server · CHESTS LABEL THEMSELVES: a bot with item frames hangs one on each linked chest showing what it mostly holds, and keeps the sample truthful as the contents drift · AND THE WORK IS AUDIBLE NOW: the rhythmic hit of a pick on stone and a hoe on dirt in time with the swing, the thunk of every torch, sapling, filler block and water bucket, the pop of drops entering the pack, the crunch of a meal, the clink of armor going on — a worked farm finally SOUNDS worked · TWELVE UPGRADES TO WHAT WAS ALREADY THERE — THE FARM: the field mix is the STOCK mix now, half wheat and half carrots in the chest comes out a field of half and half, because whichever seed is furthest behind its share goes in the ground next · melons and pumpkins: the seeds plant a STEM, the stem lives forever, and only the fruit is ever cut — plant those rows once · sugar cane: cut above the root and the root regrows it, no replanting, ever · bonemeal in the chest reaches the farmer and gets spent one application per quiet moment on something still growing — never on grass, which vanilla answers with a lawn of flowers · THE MINE: every exposed ore face is dug before the gallery advances, including ore in the walls of caves it merely WALKS through, which the old scan never saw because it only checked blocks it broke · lava or water in a wall or ahead gets walled off with the cobble it is already carrying and the tunnel carries ON PAST — a run used to abandon everything at the first drip, now it only stops when the filler runs out · and the dig floor jumps straight to the stratum: three buttons on the orders sheet, Surface, Y16 for iron, Y-54 for diamond, instead of eight blocks per click · THE CREW: skulls on the map where anyone died in the last day, gone after that, so you can see the danger spot while you re-zone · the crew list sorts three ways now — name, trade, or problems first, and it remembers which you picked · an off-shift bot with no bed claims the nearest free one around home BY ITSELF at dusk, and it can no longer take a bed a crewmate already owns or one someone is asleep in — which the button could · level 30 is a CHOICE now, made once on the record sheet: Swift is the old +20% speed, Tough is +4 armor, Porter banks and hauls half a pack more per trip — two veterans on one crew finally come out different · and payday is spoken: one quiet line naming the ingot it took, so metal leaving the chest is never a mystery · CHECKED ONE BY ONE, THREE CAUGHT: the bed-time stagger I shipped in b79 ANDed two tick clocks that can never agree for some bots — those bots would simply never have walked to bed again; it rolls a die per opportunity now, which cannot starve anyone. The off-duty stroll had the same disease and got the same cure. A failed restock was wiping the remembered POND along with the chest list, putting a stalled fisher back on the full 6,875-block water scan every checklist — the exact cost the chunk-map fix removed; retries now drop only the chest list. A hauler whose linked chest is its PICKUP chest would have delivered its cargo straight back into it, forever — delivery runs ignore the standing link. And the idle self-sort re-announced a sorted storeroom every five minutes; it only runs after something NEW was banked · THE SCREENS GOT THE PACK SCREEN\u2019S CONSTRUCTION: every panel now has the vanilla bevel — light where the light falls, shadow opposite — an engraved rule under the header band, section headings with a green tick and an engraved hairline, progress bars recessed into the panel with a glossy top edge on the fill, and the map sunk into the panel like a slot with N marked on it. Every trade\u2019s colour is now ONE palette shared by the map, the crew list and a new chip beside the name on the orders and record sheets — you find the farmer in a list the way you find it in a field. Career tallies draw on a soft full-width track so the biggest number owns its row · EIGHTEEN WAYS THEY WORK SMARTER: LINK A CHEST DIRECTLY — click a chest with the wand while a bot is bound and that chest is THE chest: restocks open it first, output banks there, and it survives a reload · CHESTS IN A SENSIBLE ORDER — the hand-linked chest, then the one that answered last time, then the nearest to where the bot is stood, instead of a fixed scan order that walked it past three nearer chests · RESTOCK BEFORE THE STALL — kit trending toward empty gets topped up in an idle window, not at zero with the field half-planted · BORROWING — chests empty but a crewmate ten feet away carrying a stack of seeds? It takes half, says thanks, and keeps working · FAILED PATHS ARE REMEMBERED — a spot no path reaches is blacklisted for a minute ACROSS runs, so the brain stops re-picking the same dead end the moment a goal restarts · FARMERS SPIRAL OUTWARD — target scans work ring by ring from the feet and stop at the first hit, instead of reading thousands of blocks to find the crop two steps away · SAPLINGS GET ROOM — a lumberjack skips replanting within two blocks of a standing sapling, so the forest comes back as trees rather than a choked thicket · TORCHES WHERE THE DARK IS — miners and lighting runs place a torch only where BLOCK light is zero, which is the only place anything can spawn, so a stack lights four times the ground · BATCHED DEPOSITS — a stash run waits for a fuller load; the ten-minute trickle timer and a full pack still force a trip, so nothing is ever stranded · QUIET WHEN UNWATCHED — with no player within 64 blocks the nametag stops rebuilding, the checklist re-checks a third as often and the idle brain looks for its next job a third as often; the jobs already queued run at full speed, so the work itself is untouched · CHEST LIST CACHED HONESTLY — the store index is reused for ten seconds instead of one and a half, drops itself the moment any chest in it vanishes, and a failed restock always looks fresh, so a just-placed chest is still seen within seconds · BED RUSH FIXED — a whole crew clocking off used to fire every pathfind on the same tick; path starts are now staggered across a two-second window per bot · OFF DUTY IS NOT A STATUE — a parked bot takes a short, cheap stroll around home now and then instead of running the full idle brain for nothing · A BELL WHEN ONE NEWLY NEEDS SOMETHING — you hear the stall from across the base instead of noticing a nametag glyph an hour later · MID-TRANSFER CLICKS — right-clicking a bot with its hands full of chest move gets a visible swing instead of a screen over a half-finished transfer · IDLE SELF-SORT — a stationed bot with genuinely nothing to do and two chests in reach straightens its own stores, rarely · AND THE SORT GATE — the ambient sort only queues when the chests are actually within the sorter's reach, so it never announces it cannot find them · THEY GO TO THE CHEST FOR SEEDS NOW, AND IT WAS THE TWO-SECOND TRANSFER THAT STOPPED THEM: a restock pulled three things in a row — rations, charges, then the kit — and each one was paced. The rations scoop took its items and started the two-second clock, and the redstone and the KIT scoops behind it both bailed out on that clock and returned nothing. The seeds were always last in the queue and were never once reached. So a farmer stood beside a chest full of seeds, restocking food it already had, for ever. The whole restock is one paced trip now, rations top UP to three rather than taking three every time, and the kit is never starved behind them · AND IT NO LONGER SITS BEHIND THE COOL-OFF: running out of seeds made the goal report a dry run, which earned a forty-second cool-off, which is exactly the window in which the restock lived — so the one thing that would have fixed it was the one thing it could not do. Restocking is not discretionary work; it runs from the tick regardless of the cool-off, the job queue or the station brain, and a dry run straight after a restock no longer counts as a tapped-out patch · AND THE JOBS ASK FOR THEMSELVES: before a farmer gives up a run for want of seeds, a miner for want of a pickaxe, a smelter for want of fuel, a rancher for want of feed, a lumberjack for want of an axe, a fisher for want of a rod — each one goes to the chests on its patch first, and only calls the run over if the chest has nothing either · THE SCREENS READ LIKE MINECRAFT NOW: I kept making the text brighter on a near-black panel, and it kept being hard to read, because contrast was never the problem — it already measured 17:1 against that panel, three times a vanilla button label. The problem was the panel. Every assistant screen now uses vanilla's own convention, dark ink on light grey, the same as the pack screen — the one screen nobody has ever called unreadable. Every ink in the set was measured against every band it can land on and the worst case is 4.3:1, where a vanilla button's label is 3.4:1 — so nothing the mod draws is quieter than the game's own text. The status badge sits on a raised light chip instead of the recessed grey, and the map's player cross went from white to ink, because white on light grey is a cross you cannot find · FARMERS FILL THE HOLES FIRST: a bare square is growth that is not happening, so a farmer with seeds plants the gaps in its field before taking the harvest — and the one line that can destroy a plant now checks the crop is ripe itself rather than trusting whatever routed it there · THEY WORK LIKE PEOPLE NOW: three seconds between actions for a raw recruit instead of a fraction of one, and that number is what levels, branch, crewmates, quirk and dinner all pull against — a fed veteran working alongside its crew gets down to about a second and a half, one on scraps takes ten · LEVELS ARE A PROCESS: the XP curve is two and a half times longer, so level 20 is 10,000 xp rather than 4,000 · CHESTS AT THE EDGE OF A PLOT WERE INVISIBLE: the search was a flat 12 blocks from the patch CENTRE, and a default 25x25 patch has corners 17 blocks out — so a farmer stood beside a chest full of seeds insisting it had none, for every job. The range now covers the whole marked plot · PACK SCREEN LAYOUT: the food readout I added in b61 was drawn at exactly the Set Area button row, so the bar and its text ran straight through the buttons. It now shares the status line with the bar as a hairline above the inventory, and the whole screen is mapped element by element so nothing else lands on anything · THE HOPPING — IT WAS NEVER MY JUMP CALLS: vanilla MoveControl jumps by itself whenever the next path node is above step height, so a bot handed a node it cannot reach bounces at the engine's own pace and rate-limiting the mod's jump() calls could never have touched it. setJumping is now overridden — the one point every jump from every source must pass through — capped at one per second, fluids exempt · THE HOPPING, AT THE SOURCE: four separate places called jump(), each able to fire every few ticks, which is why fixing them one at a time did not work twice. They all go through one gate now with a hard two-second floor, so a bot physically cannot bounce. The escape goal also sits at priority 0 and could hold it for ever on a fence it could have walked around — it now times out after ten seconds and backs off · THE HOPPING, PROPERLY: bounding the jumps was not enough — giving up reset the counter, the brain re-picked the same unreachable target a tick later, and the bot hopped twice more, for ever. Now: at most one hop every three seconds and only at something a hop clears; giving up blocks any new walking target for ten seconds; and the jump flag is forced off whenever there is no path at all · KIT CHECK: every ten seconds an idle specialist confirms it is holding the tool of its trade and equips it if not — a hoe for the farmer, shears for the rancher, a rod for the fisher — so nobody starts its next run a swing behind holding a stack of wheat · MINERS STAY ON THEIR PATCH: containment only ever checked X and Z, so anything built above or below the plot was fair game and vein-chasing wandered off the plan entirely — every dig is now gated on the footprint AND the depth you set · SMELTERS RUN THE WHOLE BANK: ten furnaces and 200 ore is 20 apiece, walked in rotation to keep them all lit, and fuel goes in longest-burning first · FARMERS LEAVE THE WATER ALONE: vanilla canBeReplaced() returns true for water, so every free-space test on a farm read a pond as open ground — a farmer would till its own bank and plant wheat into the source block, destroying the water hydrating its field. Fluids are never free space now · READABLE SCREENS: every line the mod draws itself had its drop shadow switched off, which is why the vanilla buttons looked crisp and everything above them did not — that is now on everywhere. The panel is fully opaque instead of 94%, so a night-time forest no longer shows through it, and the dimmest text went from 3.6:1 contrast to 7.3:1 · NO MORE HOPPING ON THE SPOT: a bot that could not reach where it was going hopped once a second forever. It now hops only at something a hop would clear, only twice, then drops the path and picks a different job · GUARDS WALK: the patrol deadlocked on the first corner it could not path to; it now abandons a leg it cannot finish and takes the next corner · FARMERS: a run plants up to 64 rather than 9, and nobody tills ground that has no water within four blocks, because dry farmland reverts to dirt · PLACE MARKER ACTUALLY DOES SOMETHING: it used to refuse to work until you renamed it in an anvil, which is typing, and the named waypoints it made were only read by the chat commands and the patrol goal — both switched off. Right-click a spot and it is now the nearest assistant home: where it sleeps, where Go Home sends it, where a hauler delivers · FARMERS PLANT AGAIN: a farmer would not sow a field that already had a single seedling in it — the check asked whether there were NO crops before planting, so one wheat anywhere in the plot stopped it sowing the other fifty empty squares. It now asks whether there is ROOM, which is a different question, and answers all three farm questions in one pass instead of three · THE WAND WORKS ON BOTS AGAIN: right-clicking an assistant with the Work Zone Marker opened its pack instead of assigning it, because the entity gets first refusal on a right-click and was consuming it before the wand ever saw it. Binding a bot and putting one on a marked plot have both been dead since the wand shipped · YOU GET WHAT YOU FEED THEM: a specialist works at the pace of its last meal. Rotten flesh is 30%, carrots and melon around 55, bread and baked potato 85, a cooked steak or porkchop 100 — and it eats the best thing it has, so what it runs on is decided by what you stock. Right-click one and the bar under its gear shows the pace and what bought it · SPAWNER RECIPE: the centre of the ring is a plain DIAMOND now, not a diamond block — hiring already costs diamonds from the fourth assistant on, so charging a whole block for the spawner as well was charging twice. Eight rotten flesh round one diamond · REQUEST BOARD: a specialist that cannot supply itself posts what it is short of, and anyone already carrying that takes it over on their next run — an answered request outranks the standing supply chains, because a posted need is somebody who has actually stopped working. The crew screen shows who is asking for what · GOOD GROUND: a miner remembers where the veins were and a lumberjack where it replanted, and both go back rather than wandering off in a straight line away from the ore they just found · THE BEAT: a guard with a patch now walks its perimeter corner to corner instead of standing in the middle — it meets things at the boundary, which is what a boundary is for · TRAITS: every hire turns up with a quirk of its own — quick, sturdy, cave-shy, a night owl, or thrifty — rolled from its own id so it is settled the moment you meet it and never rerolls · SHIFT HANDOVER: a specialist clocking off passes its load to whoever is coming on to the same plot, keeping its own tools and rations · TOOL SENSE: among tools that dig a block at much the same rate the enchantment now decides — Fortune on ore for the extra yield, Silk Touch on the few blocks that shatter without it, and never Silk Touch on ore, which costs a miner drops · TEAMWORK: hands that have worked the same ground for weeks get up to 10% quicker at it · MILESTONES: a nametag earns a chevron at a thousand of whatever its trade is judged on, three at twenty thousand · SUPPLY CHAINS: a load now goes where it is wanted rather than into whichever chest is nearest — ore and fuel to the smelter, wheat and carrots to the rancher, spare food to whoever has an empty larder and is about to stall. Three chains, each obvious, no general routing to surprise you · WAGES: a specialist draws a wage in metal on top of its rations, and a wage is SPENT, not carried — hand one a diamond and it is gone, so you cannot kill it to get the diamond back. Iron covers a day, gold two, a diamond four · HIRING COST: the first three are free, then each hire wants one more diamond than the last · LEDGER: what the crew has cost you, on the crew screen and per bot on its record · DEATH MEMORY: a bot that died somewhere keeps a wider margin there next life · NAMES: every hire arrives with a name of its own instead of ten of them called assistant, and the Name button gives you a page of unused names to click — no anvil, no typing · PLOTS FIRST: mark a field with the wand before you own anyone, then right-click each assistant to put them on it. One field, marked once, worked by as many hands as you like, and widening it afterwards moves everyone already on it · SERVER COST: a requirement check used to probe every one of 6,875 positions in a zone, up to seven times a scan — about 48,000 block-entity lookups every three seconds per specialist. It now reads the block-entity map the chunk already keeps, which is at most nine maps of a handful of entries each. Same chests, same answers · LINKED CHESTS: everything a job needs counts as held whether it is in the pack or in a chest in the zone, and the bot fetches it itself — stock the chest once and it stops asking you · Hatted trades (farmer, miner, rancher, guard, fisher) had the whole head covered by the hat layer and no face at all — the overlay is now a crown and a brow band, so they look like people in hats · SAVED PATCHES: set a field up once, press Save Patch, and every future hire drops straight onto it — same trade, same ground, same shift. Put a second bot on the same patch and they crew it together · LAYOUT FIX: the orders sheet was drawing its career and perk lines underneath the duty buttons, so nobody ever saw them · JOB UNIFORMS: every trade wears its own kit — straw hat and wheat gold for the farmer, denim and a hard hat for the miner, hi-vis for the hauler — so you can read a crew of ten across a field · WORKING ICON: the tool of its trade floats over its head while it works, and turns into a barrier the moment it's stuck · MAP SCREEN: every patch and every specialist from above, click one to open its orders · WORK RECORD: the full career tally on its own page · CONFIG FILE: every number is yours now — upkeep rates, crew size, patch sizes, the XP curve, chunk loading — in config/mc_assistant-common.toml · LOYALTY: a specialist earns a permanent heart per week of service, up to four, so an old hand outlives a fresh hire · BRANCHES: at level 20 a specialist picks a branch (irrigation, husbandry, prospecting, forestry, sentinel, porterage) that deepens the job it already does · DEATH LOG: it tells you what killed it and where, and remembers when revived · danger sense · useful idling · work record · beds + shifts";

    // Player-parity reach: same as a survival player's default
    // block_interaction_range (4.5) and entity_interaction_range (3.0).
    public static final double BLOCK_REACH = 4.5;
    public static final double ENTITY_REACH = 3.0;

    // Owner UUID -> (lowercase name -> live assistant). One owner can run a
    // whole crew; commands route by name or to the nearest one.
    private static final Map<UUID, ConcurrentHashMap<String, AssistantEntity>> BY_OWNER = new ConcurrentHashMap<>();

    // What this bot is currently filed under, so the tick can tell at a glance
    // whether the registry needs touching at all.
    @Nullable private UUID registeredOwner;
    @Nullable private String registeredName;

    @Nullable
    public static AssistantEntity byOwner(UUID ownerId) {
        Map<String, AssistantEntity> m = BY_OWNER.get(ownerId);
        if (m == null) return null;
        for (AssistantEntity a : m.values()) {
            if (a.isAlive()) return a;
        }
        return null;
    }

    @Nullable
    public static AssistantEntity byName(UUID ownerId, String name) {
        Map<String, AssistantEntity> m = BY_OWNER.get(ownerId);
        if (m == null) return null;
        AssistantEntity a = m.get(name.toLowerCase());
        return (a != null && a.isAlive()) ? a : null;
    }

    public static List<AssistantEntity> allFor(UUID ownerId) {
        Map<String, AssistantEntity> m = BY_OWNER.get(ownerId);
        if (m == null) return List.of();
        List<AssistantEntity> out = new ArrayList<>();
        for (AssistantEntity a : m.values()) {
            if (a.isAlive()) out.add(a);
        }
        return out;
    }

    public static List<String> namesFor(UUID ownerId) {
        List<String> out = new ArrayList<>();
        for (AssistantEntity a : allFor(ownerId)) out.add(a.getAssistantName());
        return out;
    }

    @Nullable private UUID ownerId;
    private Mode mode = Mode.FOLLOW;
    private Role role = Role.NONE;
    private int lastTownRoleTick = -2000; // debounce town role reassignment
    private net.minecraft.world.phys.Vec3 lastPathPos = net.minecraft.world.phys.Vec3.ZERO; // stuck detector
    private int stuckStreak; // consecutive ~1s windows spent going nowhere while pathing
    private int quartermasterCd; // cooldown between handing the owner supplies
    private int distressCd;      // cooldown between calls for crew backup
    private String assistantName = "assistant";
    private boolean autonomous;
    private final NonNullList<ItemStack> inventory = NonNullList.withSize(INVENTORY_SIZE, ItemStack.EMPTY);

    // The work queue — jobs run in order (finish one, start the next).
    private final java.util.ArrayDeque<Job> jobs = new java.util.ArrayDeque<>();
    private int taskGen;

    // Standing supply orders, checked when idle.
    private final List<StandingOrder> standingOrders = new ArrayList<>();

    // Scheduled chores that re-fire on a timer ("tend the farm every 20 min").
    private final List<Routine> routines = new ArrayList<>();

    // Which chest held what, learned from every chest it touches.
    private final Map<Long, Set<String>> chestMemory = new ConcurrentHashMap<>();

    // Health / survival state.
    private int lastDamageTick = -1000;
    private int lastShownHealth = -1;
    private int lastShownLevel = -1;   // nametag refresh trigger for level-ups
    private char lastShownGlyph = ' '; // nametag refresh trigger for job status
    private int eatCooldown;
    private boolean retreating;
    private int idleBackoffUntil;
    private float exploreHeading;      // scouting direction, kept so it spirals out rather than pacing
    private boolean idleKick;          // run the idle brain immediately once autonomy is switched on
    @Nullable private BlockPos stationPos;              // where its full-time post is (persisted)
    private StationTask stationTask = StationTask.NONE; // what it does there (persisted)
    private int stationWarnTick = -9999;                // cooldown on "I need a chest here" nags
    private int stationTorchTick = -99999;              // a guard post re-lights its ground rarely
    private int stationBreedTick = -99999;              // pace breeding to the animals' love cooldown
    private int stationSortTick = -99999;               // a storeroom only needs tidying now and then
    private int depositBlockedTick = -99999;            // last time the output chest was full
    private int lastStashTick = -99999;                 // last successful stash, to bank trickling output
    @Nullable private BlockPos mobileLoadCenter;        // hauler's traveling chunk window (persisted)
    @Nullable private WorkZone workZone;                // the patch it's assigned to (persisted)
    private int upkeepFoodTick;                         // work-ticks banked toward the next meal
    private int upkeepChargeTick;                       // work-ticks banked toward the next core charge
    private boolean upkeepStalled;                      // out of rations/charge -> not working
    private int readyCheckTick = -9999;                 // throttle for the requirements scan
    private java.util.List<String> missingEssentials = java.util.List.of();

    // --- Client-visible job state, so the management screen can show the job,
    //     its work zone, and what's still missing without any custom packets. ---
    private static final net.minecraft.network.syncher.EntityDataAccessor<Integer> DATA_JOB =
        net.minecraft.network.syncher.SynchedEntityData.defineId(
            AssistantEntity.class, net.minecraft.network.syncher.EntityDataSerializers.INT);
    private static final net.minecraft.network.syncher.EntityDataAccessor<String> DATA_STATUS =
        net.minecraft.network.syncher.SynchedEntityData.defineId(
            AssistantEntity.class, net.minecraft.network.syncher.EntityDataSerializers.STRING);
    private static final net.minecraft.network.syncher.EntityDataAccessor<String> DATA_ZONE =
        net.minecraft.network.syncher.SynchedEntityData.defineId(
            AssistantEntity.class, net.minecraft.network.syncher.EntityDataSerializers.STRING);

    @Override
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_JOB, 0);
        builder.define(DATA_NAME, "");
        builder.define(DATA_LEVEL, 0);
        builder.define(DATA_SHIFT, 0);
        builder.define(DATA_XP, 0);
        builder.define(DATA_EXTRA, "");
        builder.define(DATA_STATUS, "");
        builder.define(DATA_ZONE, "");
    }

    private static final net.minecraft.network.syncher.EntityDataAccessor<String> DATA_NAME =
        net.minecraft.network.syncher.SynchedEntityData.defineId(
            AssistantEntity.class, net.minecraft.network.syncher.EntityDataSerializers.STRING);

    private static final net.minecraft.network.syncher.EntityDataAccessor<Integer> DATA_LEVEL =
        net.minecraft.network.syncher.SynchedEntityData.defineId(
            AssistantEntity.class, net.minecraft.network.syncher.EntityDataSerializers.INT);
    private static final net.minecraft.network.syncher.EntityDataAccessor<Integer> DATA_SHIFT =
        net.minecraft.network.syncher.SynchedEntityData.defineId(
            AssistantEntity.class, net.minecraft.network.syncher.EntityDataSerializers.INT);
    private static final net.minecraft.network.syncher.EntityDataAccessor<Integer> DATA_XP =
        net.minecraft.network.syncher.SynchedEntityData.defineId(
            AssistantEntity.class, net.minecraft.network.syncher.EntityDataSerializers.INT);

    private static final net.minecraft.network.syncher.EntityDataAccessor<String> DATA_EXTRA =
        net.minecraft.network.syncher.SynchedEntityData.defineId(
            AssistantEntity.class, net.minecraft.network.syncher.EntityDataSerializers.STRING);

    /** "branch|daysServed|topDeed" — the career line the screens show. */
    public String clientExtra() { return this.entityData.get(DATA_EXTRA); }

    public int clientLevel() { return this.entityData.get(DATA_LEVEL); }
    public int clientLifetimeXp() { return this.entityData.get(DATA_XP); }
    public Shift clientShift() { return Shift.values()[Math.floorMod(this.entityData.get(DATA_SHIFT), Shift.values().length)]; }

    public String clientName() {
        String n = this.entityData.get(DATA_NAME);
        return n.isEmpty() ? "assistant" : n;
    }

    public int clientJobOrdinal() { return this.entityData.get(DATA_JOB); }
    public String clientStatus() { return this.entityData.get(DATA_STATUS); }
    public String clientZone() { return this.entityData.get(DATA_ZONE); }

    /** Re-check the job's requirements (scans the zone) and publish the result. */
    private void refreshJobState() {
        missingEssentials = JobSpec.missing(this);
        publishJobState();
    }

    /** Publish the cached job/zone/status to watching clients. Scans nothing —
     *  safe to call while the entity is still loading in. */
    private void publishJobState() {
        this.entityData.set(DATA_JOB, stationTask.ordinal());
        this.entityData.set(DATA_NAME, displayNameCap());
        this.entityData.set(DATA_LEVEL, veteranLevel());
        this.entityData.set(DATA_SHIFT, shift.ordinal());
        this.entityData.set(DATA_XP, lifetimeXp);
        // The career line and the deed table only change when the bot actually
        // does something, so stamp a cheap signature and rebuild the string on a
        // miss. Publishing runs several times a minute per bot; the strings it
        // produces are nearly always byte-identical to the ones already sent.
        long stamp = deedStamp * 31L + branch.ordinal() * 7L + daysServed()
            + wagesPaid * 1009L + (wageDue() / 200L)
            + trait.ordinal() * 65537L + teamworkPercent() * 131L + dietPercent * 7919L
            + perk30.ordinal() * 524287L + patchName.hashCode() * 8191L
            + (quiet ? 4093L : 0L) + (quarry ? 2039L : 0L) + ripePercent * 131071L
            + (deathSite == null ? 0
               : deathSite.asLong() ^ ((level().getGameTime() - deathGameTime) / 1200L));
        if (stamp != lastExtraStamp || lastExtraZone != zoneStamp()) {
            java.util.List<String> top = workRecord(1);
            this.entityData.set(DATA_EXTRA, branch.label + "|" + daysServed() + "|"
                + (top.isEmpty() ? "" : top.get(0)) + "|" + deedCsv() + "|" + zoneCsv()
                + "|" + ironPaid + "," + goldPaid + "," + diamondPaid + "," + wageDue()
                // blurb LAST: it contains commas of its own, so it has to be
                // the field the reader stops splitting at.
                + "|" + trait.label + "," + teamworkPercent() + "," + trait.blurb
                + "|" + dietPercent + "," + lastMeal
                + "|" + deathField() + "|" + perk30.ordinal() + "|" + patchName
                + "|" + (quiet ? 1 : 0) + "," + ripePercent + "," + (quarry ? 1 : 0));
            lastExtraStamp = stamp;
            lastExtraZone = zoneStamp();
        }
        this.entityData.set(DATA_ZONE, workZone == null ? "No work zone set"
            : (patchName.isEmpty() ? "" : patchName + " — ") + workZone.describe()
              + (stationTask == StationTask.MINE ? "  depth Y" + workZone.depth() : ""));
        String status;
        if (stationTask == StationTask.NONE) {
            status = "Pick a job, then mark a zone";
        } else if (!missingEssentials.isEmpty()) {
            status = "Needs " + String.join(", ", missingEssentials);
        } else if (workZone == null && stationPos == null) {
            status = "Needs a work zone";
        } else if (upkeepStalled) {
            status = "Out of upkeep — food + redstone";
        } else {
            status = "Working";
        }
        this.entityData.set(DATA_STATUS, status);
    }

    public java.util.List<String> missingEssentials() { return missingEssentials; }

    /** DepositGoal calls this when it reached a chest and could not fit anything
     *  in. Backs the deposit rung off so productive work resumes meanwhile. */
    /** DepositGoal reports a successful stash so the "lingering output" timer resets. */
    public void noteStashed() { lastStashTick = tickCount; }

    public void noteDepositBlocked() {
        depositBlockedTick = tickCount;
        say("My output chest is full — I'll keep working, but it needs emptying.");
    }

    @Nullable public WorkZone workZone() { return workZone; }

    /** Assign (or clear) the patch this bot works. The zone doubles as its post,
     *  so the station machinery keeps it there and keeps the ground loaded. */
    public void setWorkZone(@Nullable WorkZone zone) {
        this.workZone = zone;
        forgetChestIndex();   // new ground, new stores
        // A changed patch is a fresh brief: drop every "nothing left here"
        // verdict reached on the OLD footprint. Without this, expanding a
        // finished lumberjack's range left it standing in its worked-out
        // corner, backed off and remembering dead ends, insisting it was done.
        idleBackoffUntil = 0;
        idleKick = true;
        unreachableUntil.clear();
        lastTopUpFound = true;
        if (zone != null) {
            setStation(zone.center(), stationTask);
        } else if (stationPos != null) {
            setStation(null, StationTask.NONE);
        }
        refreshJobState();
        warnIfZoneClashes();
    }

    /** Two specialists working the same ground will trip over each other —
     *  harvesting each other's crops, felling the same trees. Say so once, when
     *  the patch is handed over, rather than leaving it to be discovered. */
    private void warnIfZoneClashes() {
        if (workZone == null || ownerId == null || stationTask == StationTask.NONE) return;
        for (AssistantEntity other : allFor(ownerId)) {
            if (other == this || other.level() != level()) continue;
            WorkZone theirs = other.workZone();
            if (theirs == null || other.stationTask() == StationTask.NONE) continue;
            // The SAME zone is a crew, not a clash — putting three farmers on
            // North Farm is the plot book's whole point. Only ground that
            // partially overlaps different ground trips over itself.
            if (workZone.equals(theirs) || !workZone.overlaps(theirs)) continue;
            say("Careful — my patch overlaps " + other.displayNameCap() + "'s ("
                + other.stationTask().label + "). We'll get in each other's way; "
                + "mark one of us somewhere else.");
            return;
        }
    }

    /**
     * Set the specialisation. If no zone has been marked, the bot claims a
     * sensible patch around where it's standing — so picking a job is all you
     * have to do to get someone working. The Zone Marker is then a refinement
     * ("actually, work THAT field"), not a hoop to jump through first.
     */
    public void setJob(StationTask task) {
        StationTask old = stationTask;
        if (task == StationTask.NONE) {
            setStation(null, StationTask.NONE);
        } else {
            if (workZone == null) {
                BlockPos here = feetPos();
                int r = defaultZoneRadius();
                workZone = WorkZone.of(here.offset(-r, -4, -r), here.offset(r, 4, r), WorkZone.DEFAULT_DEPTH);
            }
            setStation(workZone.center(), task);
        }
        if (old != task) {
            upkeepStalled = false;
            clearQueue();
        }
        if (firstServedDay < 0 && task != StationTask.NONE) {
            firstServedDay = level().getDayTime() / 24000L;
        }
        refreshJobState();
        // Hiring a second bot next to the first is the usual way patches end up
        // on top of each other, since an unzoned job claims the ground it stands on.
        if (task != StationTask.NONE) warnIfZoneClashes();
    }

    private static int defaultZoneRadius() { return com.jrpetty.mcassistant.AssistantConfig.defaultZoneRadius(); }

    /** Irrigation and prospecting reach further out across the patch. */
    public int branchWorkRadiusBonus() {
        return branch == Branch.IRRIGATION || branch == Branch.PROSPECTOR ? 6 : 0;
    }

    /** A porter holds more before it has to break off and stash. */
    public int branchPackBonus() {
        return branch == Branch.PORTER ? 16 : 0;
    }

    /** Husbandry and forestry get through their cycles faster. */
    public int branchCooldownPercent() {
        return branch == Branch.HUSBANDRY || branch == Branch.FORESTER ? 60 : 100;
    }

    /** Inside the assigned patch? Without a zone every position counts, so an
     *  unzoned specialist behaves the way stations did before zones existed. */
    public boolean inZone(BlockPos pos) {
        return workZone == null || workZone.contains(pos);
    }

    /** Inside the patch's footprint, ignoring height — what a digging job needs. */
    public boolean inZoneColumn(BlockPos pos) {
        return workZone == null || workZone.containsColumn(pos);
    }

    // --- places worth going back to ------------------------------------------
    // A vein rarely comes alone and a felled grove regrows. Remembering where
    // the good ground was turns a miner that wanders off in a straight line
    // into one that works a seam out, and gives a lumberjack somewhere to
    // return to once the saplings it planted have come up.

    private final java.util.List<Long> richSpots = new java.util.ArrayList<>();
    private static final int SPOT_MEMORY = 12;

    /** Remember this as ground that paid off. */
    public void noteRichSpot(BlockPos pos) {
        long key = pos.asLong();
        // Anything within a few blocks of a spot we already know is the same
        // find, not a new one — otherwise one seam fills the whole memory.
        for (long known : richSpots) {
            if (BlockPos.of(known).distSqr(pos) < 64.0) return;
        }
        richSpots.add(0, key);
        while (richSpots.size() > SPOT_MEMORY) richSpots.remove(richSpots.size() - 1);
    }

    /** The nearest remembered spot inside the patch, or null. Skips anywhere it
     *  is already standing, so it does not keep re-walking to its own feet. */
    @Nullable
    public BlockPos richSpotToRevisit() {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (long key : richSpots) {
            BlockPos pos = BlockPos.of(key);
            if (!inZoneColumn(pos)) continue;
            double d = pos.distSqr(blockPosition());
            if (d < 100.0 || d > 96.0 * 96.0 || d >= bestDist) continue;
            bestDist = d;
            best = pos;
        }
        return best;
    }

    /** Forget a spot that has been worked out. */
    public void forgetRichSpot(BlockPos pos) {
        richSpots.removeIf(k -> BlockPos.of(k).distSqr(pos) < 64.0);
    }

    private int driftTick = -1000;   // pace the roam so idling reads as patrol
    private int revisitTick = -1000;
    private int beatCorner;          // which corner of the patch it is heading for
    private int beatLegStart = -1000; // when the current leg was started
    private int beatTick = -1000;

    /**
     * A guard's patrol: the four corners of its own plot, in order, pausing at
     * each. The route needs no marking of its own — you already drew it when
     * you marked the patch, and a beat that follows the boundary is what the
     * boundary is for.
     */
    private boolean walkTheBeat() {
        if (workZone == null || movementBlocked()) return false;

        if (!getNavigation().isDone()) {
            // Abandon a leg we plainly cannot finish. Without this, a corner in
            // a wall or across water held a guard forever: the path stayed "in
            // progress", so this method never ran again to choose a different
            // corner, and the stuck-detector jumped the bot on the spot once a
            // second. That was the guard that stood still and hopped.
            if (tickCount - beatLegStart < 140) return false;
            getNavigation().stop();
        } else if (tickCount - beatTick < 60) {
            return false;   // a short breather at each corner, not a ten-second stand
        }

        BlockPos min = workZone.min(), max = workZone.max();
        BlockPos[] corners = {
            new BlockPos(min.getX() + 1, min.getY(), min.getZ() + 1),
            new BlockPos(max.getX() - 1, min.getY(), min.getZ() + 1),
            new BlockPos(max.getX() - 1, min.getY(), max.getZ() - 1),
            new BlockPos(min.getX() + 1, min.getY(), max.getZ() - 1),
        };
        // Take the first corner we can actually get a path to, rather than
        // committing to one and hoping. A patch with a cliff or a lake in it
        // still gets walked, just not through the impossible bit.
        for (int i = 0; i < corners.length; i++) {
            BlockPos target = corners[Math.floorMod(beatCorner + i, corners.length)];
            // Aim at the surface, not the zone's floor — a patch marked from a
            // hilltop has a low min Y and would point underground.
            int y = level().getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                target.getX(), target.getZ());
            if (getNavigation().moveTo(target.getX() + 0.5, y, target.getZ() + 0.5, 1.0D)) {
                beatCorner = Math.floorMod(beatCorner + i + 1, corners.length);
                beatTick = tickCount;
                beatLegStart = tickCount;
                return true;
            }
        }
        beatTick = tickCount;   // nowhere reachable right now — try again shortly
        return false;
    }

    private int handoverTick = -1000;

    /**
     * Pass the pack to the crewmate coming on shift on this same plot. Only the
     * output — its tools and its rations stay with it, or the relief would
     * arrive holding two hoes and the one going to bed would starve.
     */
    private void handOver() {
        if (workZone == null || ownerId == null || countItems() == 0) return;
        if (tickCount - handoverTick < 600) return;
        for (AssistantEntity relief : allFor(ownerId)) {
            if (relief == this || !relief.isAlive()) continue;
            if (!workZone.equals(relief.workZone())) continue;
            if (!relief.onShift() || relief.stationTask() == StationTask.NONE) continue;
            if (relief.distanceToSqr(this) > 24.0 * 24.0) continue;

            handoverTick = tickCount;
            int passed = 0;
            for (int i = 0; i < inventory.size(); i++) {
                ItemStack st = inventory.get(i);
                if (st.isEmpty() || isKit(st)) continue;
                ItemStack left = relief.insertItem(st);
                passed += st.getCount() - left.getCount();
                inventory.set(i, left);
            }
            if (passed > 0) {
                say("Clocking off — handing " + passed + " over to "
                    + relief.displayNameCap() + ".");
            }
            return;
        }
    }

    /** Things a specialist keeps through a handover: its tools and its upkeep. */
    private boolean isKit(ItemStack s) {
        return s.get(DataComponents.FOOD) != null || s.is(Items.REDSTONE)
            || s.isDamageableItem() || s.is(Items.TORCH) || isWorkingStock(s);
    }

    /** Draw the patch's outline for a player, so "where does it work?" is
     *  answerable at any time rather than only while holding a marker. */
    public void showZoneTo(Player player) {
        if (workZone == null || !(level() instanceof net.minecraft.server.level.ServerLevel sl)) return;
        for (int i = 0; i < 3; i++) { // a few passes so it reads as a solid border
            com.jrpetty.mcassistant.item.ZoneMarkerItem.outline(sl, workZone,
                net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER);
        }
    }

    /**
     * Running cost. A working specialist is hungry work: it eats a ration every
     * ~2.5 min of labour and burns a redstone "core charge" every ~10 min,
     * taking both from its own pack or the chests in its zone. Run dry and it
     * downs tools and says so — a crew is an ongoing commitment, not free labour.
     */
    private boolean payUpkeep() {
        if (stationTask == StationTask.NONE) return true;
        boolean ok = true;
        // Measured against the world clock, NOT the number of times this runs —
        // the work brain only ticks every ~10s, so counting calls would stretch
        // "every 2.5 minutes" into most of a day.
        if (upkeepFoodTick == 0) upkeepFoodTick = tickCount;
        if (upkeepChargeTick == 0) upkeepChargeTick = tickCount;
        if (com.jrpetty.mcassistant.AssistantConfig.upkeepEnabled()
            && tickCount - upkeepFoodTick
                >= com.jrpetty.mcassistant.AssistantConfig.foodInterval() * traitUpkeepPercent() / 100) {             // ~2.5 minutes of work
            if (eatBestFood()) {
                upkeepFoodTick = tickCount;
            } else {
                ok = false;
                if (tickCount - stationWarnTick > 2400) {
                    stationWarnTick = tickCount;
                    say("I'm out of rations — put food in my chest and I'll get back to work.");
                }
            }
        }
        if (com.jrpetty.mcassistant.AssistantConfig.upkeepEnabled()
            && tickCount - upkeepChargeTick
                >= com.jrpetty.mcassistant.AssistantConfig.chargeInterval() * traitUpkeepPercent() / 100) {          // ~10 minutes of work
            if (consumeUpkeep(s -> s.is(Items.REDSTONE))) {
                upkeepChargeTick = tickCount;
            } else {
                ok = false;
                if (tickCount - stationWarnTick > 2400) {
                    stationWarnTick = tickCount;
                    say("My core needs a charge — I need redstone to keep running.");
                }
            }
        }
        // Wages. Food and redstone are consumed like fuel; a wage is different —
        // the metal is SPENT. Hand a specialist a diamond and it is gone: you
        // cannot kill it to get the diamond back, because it was never carried.
        // That is what makes paying one a decision rather than a loan.
        if (com.jrpetty.mcassistant.AssistantConfig.upkeepEnabled()
            && com.jrpetty.mcassistant.AssistantConfig.wagesEnabled()) {
            if (wagePaidUntil == 0) wagePaidUntil = tickCount
                + com.jrpetty.mcassistant.AssistantConfig.wageInterval();
            if (tickCount >= wagePaidUntil && !drawWage()) {
                if (tickCount - lastWarnTick > 2400) {
                    lastWarnTick = tickCount;
                    say("I'm owed a wage — an iron ingot, or gold, or a diamond. "
                        + "Leave it in my chest and I'll take it from there.");
                }
                ok = false;
            }
        }
        upkeepStalled = !ok;
        return ok;
    }

    /** Restock the kit this job needs from the chests at the station — the
     *  tools that wear out, and the torches that get used up. Returns true if
     *  anything was actually pulled. */
    private boolean resupplyFromChests() {
        // The TOOL of the trade and the stock it burns through are different
        // restocks: one hoe is a kit, five hoes is a raid. The old single
        // predicate scooped up to 16 of anything it matched — which is how a
        // lumberjack emptied the chest of all five axes in one visit.
        java.util.function.Predicate<ItemStack> tool = tradeTool();
        java.util.function.Predicate<ItemStack> restock = switch (stationTask) {
            // Saplings are the lumberjack's whole regrowth mechanism, so they
            // are kit, not output: without them a zone is felled once and never
            // grows back. Bonemeal likewise for the farmer — optional, but
            // stocked bonemeal should reach the pack. Breeding food for the
            // rancher: kitShortInHand counts a rancher with no feed as short.
            case WOOD -> s -> s.is(ItemTags.SAPLINGS);
            case FARM -> s -> FARM_SEEDS.test(s) || s.is(Items.BONE_MEAL);
            case MINE -> s -> s.is(Items.TORCH) || s.is(Items.LADDER);
            case GUARD -> s -> s.is(Items.TORCH);
            case RANCH -> BREEDING_FOOD;
            case SMELT -> s -> s.is(Items.COAL) || s.is(Items.CHARCOAL);
            case FISH, STORE, HAUL, NONE -> null;
        };
        // ONE restock, one pace. This used to be three separate paced scoops in
        // a row, and only the first of them could ever run: the food scoop took
        // its items, started the two-second transfer, and the redstone and KIT
        // scoops behind it both bailed out on `!transferReady()` and returned
        // zero. So a farmer stood next to a chest full of seeds and never got
        // any — every restock spent its whole budget on food it already had,
        // and the seeds were always next in the queue and never reached.
        //
        // The pace is checked once, here, for the trip as a whole; the scoops
        // themselves run unpaced and the clock starts after the last of them.
        if (!transferReady()) return false;
        // Top UP to a couple of meals and a few charges, rather than taking that
        // many every single time — a bot that empties the larder into its own
        // pack has moved the problem rather than solved it, and the chest is
        // four blocks away when it wants more.
        int wantFood = 3 - countCarried(s -> s.get(DataComponents.FOOD) != null);
        int wantCharge = 4 - countCarried(s -> s.is(Items.REDSTONE));
        boolean got = false;
        if (wantFood > 0) {
            got |= scoopFromChests(s -> s.get(DataComponents.FOOD) != null,
                wantFood, chestRange(), false) > 0;
        }
        if (wantCharge > 0) {
            got |= scoopFromChests(s -> s.is(Items.REDSTONE), wantCharge, chestRange(), false) > 0;
        }
        // The kit itself goes LAST but is never starved by the two above,
        // because none of them start the transfer clock. Tools top up to
        // exactly ONE; the consumables take a proper handful.
        if (tool != null) {
            int wantTool = 1 - countCarried(tool);
            if (wantTool > 0) got |= scoopFromChests(tool, wantTool, chestRange(), false) > 0;
        }
        if (restock != null) got |= scoopFromChests(restock, 16, chestRange(), false) > 0;
        if (got) {
            beginTransfer();
            restockedTick = tickCount;
        }
        return got;
    }

    private int restockedTick = -1000;

    /**
     * Go and get what this job runs on, from the chests linked to the patch.
     *
     * <p>Public and called straight from the tick, because the restock used to
     * live inside the station brain and the station brain sits behind the idle
     * back-off — so the moment a farmer ran out of seeds it reported a dry run,
     * earned a forty-second cool-off, and spent that cool-off unable to do the
     * one thing that would have fixed it. Running out of stock is the opposite
     * of "this area is tapped out"; it is precisely when a bot should be at the
     * chest.
     */
    public boolean topUpKit() {
        if (stationTask == StationTask.NONE) return false;
        if (!transferReady()) return false;      // stamp nothing; try again shortly
        // A stocked chest should answer quickly; an empty one should not be
        // rescanned every second. So the wait depends on how the last trip went,
        // which also keeps a goal that has JUST discovered it is blocked from
        // being held off by the tick's own cadence.
        if (tickCount - kitTopUpTick < (lastTopUpFound ? 20 : 100)) return false;
        kitTopUpTick = tickCount;
        if (!kitShortInHand()) { lastTopUpFound = false; return false; }
        // The last trip found nothing: the player may have JUST set a chest
        // down to fix that, and the store index is allowed to be seconds stale.
        // A retry after a failure always looks at the chests fresh.
        if (!lastTopUpFound) dropChestIndex();
        lastTopUpFound = resupplyFromChests();
        // Chests empty? A crewmate carrying spares is a store too.
        if (!lastTopUpFound) lastTopUpFound = borrowFromCrew();
        return lastTopUpFound;
    }

    private boolean lastTopUpFound = true;   // optimistic: try promptly the first time

    // ------------------------- how it works, quietly -------------------------

    /** Places this one could not path to, remembered for a minute ACROSS goal
     *  runs. Each goal already keeps its own blacklist, but those clear every
     *  time the goal restarts — which is exactly when the brain re-picks the
     *  same unreachable block and walks into the same wall again. */
    private final java.util.Map<Long, Integer> unreachableUntil = new java.util.HashMap<>();

    public void noteUnreachable(BlockPos pos) {
        if (unreachableUntil.size() > 128) {
            unreachableUntil.values().removeIf(t -> t < tickCount);
            if (unreachableUntil.size() > 128) unreachableUntil.clear();
        }
        unreachableUntil.put(pos.asLong(), tickCount + 1200);
    }

    public boolean isUnreachable(BlockPos pos) {
        Integer until = unreachableUntil.get(pos.asLong());
        return until != null && until > tickCount;
    }

    /** What this one's ground is CALLED — "North Farm", "The Quarry" — shown
     *  on the map, in its status line, and shared by everyone working the same
     *  patch. Cycled by right-clicking the patch on the map: a preset list,
     *  because nothing in this mod is ever typed. */
    private String patchName = "";

    public String patchName() { return patchName; }

    /** Put this one on a plot: the ground and its name in one move. Rides on
     *  setWorkZone, so every stale nothing-left-here verdict clears with it.
     *  Re-arms autonomy too — a bot stood down by leavePlot() must start
     *  working the moment it is clicked back onto ground. */
    public void assignPlot(WorkZone zone, String name) {
        snapshotOrder();
        this.patchName = name;
        setWorkZone(zone);
        setAutonomous(true);
        publishJobState();
    }

    /**
     * Take this one off its plot: stand down IN PLACE, keeping the trade.
     *
     * <p>This used to ride setWorkZone(null), whose else-branch clears the
     * station AND the task — so clicking a farmer off a plot silently turned
     * it into an Unassigned drifter running the survival brain. "Off the plot,
     * waiting on your word" now means exactly that: trade kept for the next
     * assignment, queue cleared, autonomy off, standing until told.
     */
    public void leavePlot() {
        snapshotOrder();
        StationTask trade = stationTask;
        this.patchName = "";
        setPreset(null);
        setWorkZone(null);              // clears ground + post (and the task)
        if (trade != StationTask.NONE) {
            stationTask = trade;        // the trade is the bot's, not the ground's
        }
        clearQueue();
        setAutonomous(false);
        setMode(Mode.STAY);
        publishJobState();
    }

    // ---- one order of memory: the book's Undo ----
    @Nullable private WorkZone prevOrderZone;
    private String prevOrderPatch = "";
    private StationTask prevOrderTrade = StationTask.NONE;
    private boolean prevOrderKnown = false;
    private int orderSnapTick = Integer.MIN_VALUE;

    /** Remember how this one stood before an order changes it. One snapshot
     *  per order, not per sub-step: applyGround retrains AND assigns in the
     *  same tick, and the memory must hold the state before the FIRST step,
     *  not something halfway through. */
    public void snapshotOrder() {
        if (tickCount == orderSnapTick) return;
        orderSnapTick = tickCount;
        prevOrderZone = workZone;
        prevOrderPatch = patchName;
        prevOrderTrade = stationTask;
        prevOrderKnown = true;
    }

    /** Put this one back the way the last order found it: previous plot,
     *  previous trade. The restore snapshots the current state first, so
     *  Undo pressed twice swaps straight back — undo is its own redo. */
    public boolean undoOrder() {
        if (!prevOrderKnown) return false;
        WorkZone zone = prevOrderZone;
        String patch = prevOrderPatch == null ? "" : prevOrderPatch;
        StationTask trade = prevOrderTrade;
        snapshotOrder();
        if (zone != null) {
            if (trade != stationTask) {
                if (trade == StationTask.NONE) setStation(null, StationTask.NONE);
                else setStation(zone.center(), trade);
            }
            assignPlot(zone, patch);
            say(patch.isEmpty() ? "Back on my old ground, as before."
                : "Back on " + patch + ", as before.");
        } else {
            leavePlot();
            if (stationTask != trade) {
                if (trade == StationTask.NONE) setStation(null, StationTask.NONE);
                else stationTask = trade;
                publishJobState();
            }
            say("Standing down — back where I was before that order.");
        }
        return true;
    }

    public void cyclePatchName() {
        if (workZone == null) {
            say("Mark my patch first, then we can put a name on it.");
            return;
        }
        int idx = Names.PATCHES.indexOf(patchName);
        String next = idx + 1 >= Names.PATCHES.size() ? "" : Names.PATCHES.get(idx + 1);
        java.util.List<AssistantEntity> mates = ownerId != null ? allFor(ownerId)
            : java.util.List.of(this);
        for (AssistantEntity mate : mates) {
            if (!mate.isAlive() || !workZone.equals(mate.workZone())) continue;
            mate.patchName = next;
            mate.publishJobState();
        }
        this.patchName = next;
        publishJobState();
        say(next.isEmpty() ? "This patch goes back to being just ground."
            : "This ground is " + next + " now.");
    }

    /** The chest the player linked to this bot by hand (wand on a chest), and
     *  the chest that last actually answered a restock. Both are tried before
     *  the rest of the stores, so a restock usually opens one chest, not five. */
    @Nullable private BlockPos preferredChest;
    @Nullable private BlockPos lastGoodChest;

    /** The hauler's delivery end. Pickup is the ordinary linked chest; this
     *  is where every load goes. Both set by the wand, in order. */
    @Nullable private BlockPos deliveryChest;

    @Nullable public BlockPos deliveryChest() { return deliveryChest; }

    @Nullable public BlockPos preferredChest() { return preferredChest; }

    /** The chest cart running this hauler's rail route, if the line has one.
     *  Transient on purpose: after a reload the cart is re-adopted from the
     *  rails by position rather than trusted from a saved id. */
    private int routeCartId = -1;
    private int railBrokenUntil = -1;   // tickCount gate after a stuck tow
    private int cartProgressTick = 0;   // last tick the cart was keeping up

    /**
     * A wand-click on a chest, routed by trade. For a HAULER the clicks build
     * the route: first chest is the pickup, second is the delivery, and
     * clicking either again clears it for re-linking. Everyone else keeps the
     * single linked-chest behaviour.
     */
    public void linkChestSmart(BlockPos pos) {
        if (stationTask != StationTask.HAUL) {
            linkChest(pos);
            say("Linked to this chest — I draw my kit from it first and bank my output here.");
            return;
        }
        BlockPos pickup = preferredChest;
        if (pos.equals(pickup)) {
            preferredChest = null;
            say("Pickup unlinked — click a chest to set a new one.");
        } else if (pos.equals(deliveryChest)) {
            deliveryChest = null;
            say("Delivery unlinked — click a chest to set a new one.");
        } else if (pickup == null) {
            preferredChest = pos.immutable();
            setStation(pos, StationTask.HAUL);   // the pickup IS the post
            say("Pickup chest set. Now click the chest I should deliver to.");
            noteRelays();
        } else {
            deliveryChest = pos.immutable();
            say("Delivery set — I'll run everything from the pickup over to here.");
            noteRelays();
        }
        forgetChestIndex();
    }

    public void linkChest(BlockPos pos) {
        preferredChest = pos.immutable();
        lastGoodChest = pos.immutable();
        forgetChestIndex();
    }

    /** What this route means beside the owner's other haulers. One hauler's
     *  delivery being another's pickup is a RELAY — multi-leg freight, each
     *  leg one bot — and worth saying out loud so it reads as designed, not
     *  coincidence. Two routes feeding each OTHER is cargo circling forever,
     *  and worth a warning before it starts. */
    private void noteRelays() {
        if (ownerId == null) return;
        for (AssistantEntity mate : allFor(ownerId)) {
            if (mate == this || !mate.isAlive() || mate.stationTask() != StationTask.HAUL) continue;
            boolean feedThem = deliveryChest != null && deliveryChest.equals(mate.preferredChest());
            boolean fedByThem = preferredChest != null && preferredChest.equals(mate.deliveryChest());
            if (feedThem && fedByThem) {
                say("Careful — " + mate.getAssistantName()
                    + " and I would just pass the same cargo back and forth forever.");
            } else if (feedThem) {
                say("That makes a relay: I hand my loads to " + mate.getAssistantName()
                    + "'s pickup, and they carry it on from there.");
            } else if (fedByThem) {
                say("That makes a relay: " + mate.getAssistantName()
                    + " delivers into my pickup — I take it from there.");
            }
        }
    }

    /** The nearest linked chest that still has an empty slot, excluding the
     *  one that just proved full — so a full chest sends the deposit next door
     *  instead of writing the whole station off for five minutes. */
    @Nullable
    public BlockPos nextChestWithRoom(BlockPos exclude) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (ZoneChests.Found f : linkedChests()) {
            if (!f.stillThere() || f.pos().equals(exclude)) continue;
            Container c = f.container();
            boolean room = false;
            for (int i = 0; i < c.getContainerSize(); i++) {
                if (c.getItem(i).isEmpty()) { room = true; break; }
            }
            if (!room) continue;
            double d = f.pos().distSqr(blockPosition());
            if (d < bestDist) { bestDist = d; best = f.pos(); }
        }
        return best;
    }

    /** The linked chest, if it still exists, is loaded, and is near enough to
     *  be worth a walk. Null means "no standing instruction — pick sensibly". */
    @Nullable public BlockPos usablePreferredChest() {
        if (preferredChest == null || !level().isLoaded(preferredChest)) return null;
        if (preferredChest.distSqr(blockPosition()) > 160.0 * 160.0) return null;
        return level().getBlockEntity(preferredChest) instanceof Container ? preferredChest : null;
    }

    /** Is anyone actually around to see this bot? Checked at most every five
     *  seconds. When nobody is, the cosmetic work — nametag rebuilds, frequent
     *  checklist refreshes, how often the idle brain looks for its NEXT job —
     *  runs at a fraction of the cadence. The goals already queued run at full
     *  speed regardless: the work itself is never throttled, only the thinking
     *  about work nobody is watching. */
    private boolean watchedCache = true;
    private int watchedCheckTick = -1000;

    public boolean isWatched() {
        if (tickCount - watchedCheckTick >= 100) {
            watchedCheckTick = tickCount;
            watchedCache = level().hasNearbyAlivePlayer(getX(), getY(), getZ(), 64.0);
        }
        return watchedCache;
    }

    /** Spread simultaneous path starts across the crew: ten bots all deciding
     *  to walk to bed on the same tick is ten full pathfinds in one tick.
     *
     *  <p>Deliberately RANDOM, not tick-modular: the callers of this only run
     *  on the idle brain's own cadence, and two modular conditions ANDed can
     *  be permanently false for some entity ids — a bot that never goes to
     *  bed. A one-in-three roll per opportunity can never starve anyone; it
     *  just decoheres the crew within a couple of decide cycles. */
    private boolean staggerBeat() {
        return random.nextInt(3) == 0;
    }

    /** Running LOW is not the same as running OUT: a farmer down to its last
     *  few seeds still plants, but its next quiet moment should be spent at the
     *  chest, not the moment after the last seed goes in the ground. */
    private boolean kitLowInHand() {
        return switch (stationTask) {
            case FARM -> countCarried(FARM_SEEDS) < 8;
            case WOOD -> countCarried(s -> s.is(ItemTags.SAPLINGS)) < 4;
            case MINE -> countCarried(s -> s.is(Items.TORCH)) < 16;
            case GUARD -> countCarried(s -> s.is(Items.TORCH)) < 6;
            case RANCH -> countCarried(BREEDING_FOOD) < 6;
            case SMELT -> countCarried(s -> s.is(Items.COAL) || s.is(Items.CHARCOAL)) < 8;
            default -> false;
        };
    }

    /** The idle-window restock: top up BEFORE the stall, when there is nothing
     *  better to do anyway. topUpKit() handles actually being out. */
    private void topUpKitAhead() {
        if (stationTask == StationTask.NONE || !transferReady()) return;
        if (kitShortInHand() || !kitLowInHand()) return;
        resupplyFromChests();
    }

    /** What this trade burns through — the stuff a crewmate can hand over
     *  without giving up its own tools. */
    @Nullable private java.util.function.Predicate<ItemStack> kitConsumables() {
        return switch (stationTask) {
            case FARM -> FARM_SEEDS;
            case WOOD -> s -> s.is(ItemTags.SAPLINGS);
            case MINE -> s -> s.is(Items.TORCH) || s.is(Items.LADDER);
            case GUARD -> s -> s.is(Items.TORCH);
            case RANCH -> BREEDING_FOOD;
            case SMELT -> s -> s.is(Items.COAL) || s.is(Items.CHARCOAL);
            default -> null;
        };
    }

    /** The chests are empty — is a crewmate nearby carrying spares? Takes at
     *  most half of what the mate holds and only from one holding plenty, so
     *  lending never turns one shortage into two. Consumables only: nobody
     *  hands over their own hoe. */
    private boolean borrowFromCrew() {
        java.util.function.Predicate<ItemStack> what = kitConsumables();
        if (what == null || ownerId == null) return false;
        if (countCarried(what) > 0) return false;     // borrow only when actually dry
        for (AssistantEntity mate : allFor(ownerId)) {
            if (mate == this || !mate.isAlive()) continue;
            if (mate.distanceToSqr(this) > 12.0 * 12.0) continue;
            if (mate.countCarried(what) < 12) continue;   // never beggar a mate
            var inv = mate.inventory;
            int moved = 0;
            for (int i = 0; i < inv.size() && moved < 8; i++) {
                ItemStack stack = inv.get(i);
                if (stack.isEmpty() || !what.test(stack)) continue;
                int spare = Math.min(stack.getCount() / 2, 8 - moved);
                if (spare <= 0) continue;
                ItemStack left = insertItem(stack.copyWithCount(spare));
                int taken = spare - left.getCount();
                if (taken <= 0) break;
                stack.shrink(taken);
                if (stack.isEmpty()) inv.set(i, ItemStack.EMPTY);
                moved += taken;
            }
            if (moved > 0) {
                beginTransfer();
                mate.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                sayRoutine("Borrowed some from " + mate.displayNameCap() + " — thanks.");
                return true;
            }
        }
        return false;
    }

    // An audible nudge the moment one newly needs something, so you hear the
    // stall from across the base instead of noticing the nametag an hour later.
    private boolean alertWas;
    private int lastChimeTick = -100000;

    /**
     * The quirk a specialist turns up with. Rolled from its own id at hire, so
     * it is settled the moment you meet it and never rerolls; small enough that
     * no trait makes a bot a mistake to hire, big enough that you notice which
     * one you got.
     */
    public enum Trait {
        NONE("steady", "nothing remarkable"),
        QUICK("quick", "works 8% faster"),
        STURDY("sturdy", "one extra heart"),
        CAVE_SHY("cave-shy", "10% faster above ground, 15% slower below Y40"),
        NIGHT_OWL("night owl", "10% faster after dark, 5% slower by day"),
        THRIFTY("thrifty", "eats and charges a quarter less often");

        public final String label, blurb;
        Trait(String label, String blurb) { this.label = label; this.blurb = blurb; }
    }

    private Trait trait = Trait.NONE;

    /**
     * The level-30 bonus, as a CHOICE. It used to be a flat +20% move speed;
     * now a veteran picks its edge once, permanently, from the record sheet —
     * so two level-30 hands on the same crew end up different specialists.
     */
    public enum Perk {
        NONE("undecided", ""),
        SWIFT("Swift", "+20% move speed"),
        TOUGH("Tough", "+4 armor"),
        PORTER("Porter", "carries half a pack more per trip");

        public final String label, blurb;
        Perk(String label, String blurb) { this.label = label; this.blurb = blurb; }
    }

    private Perk perk30 = Perk.NONE;

    /** A guard on escort shadows a CREWMATE instead of ground. Cycled from the
     *  orders screen through the crew and back to "nobody" — no picking UI,
     *  the same pattern as every other choice in this mod. */
    @Nullable private java.util.UUID escortId;

    /** Quarry mode: instead of one gallery and done, cut a gallery, drop four
     *  blocks, cut the next, and keep going down to the depth you set. */
    private boolean quarry;

    public boolean quarry() { return quarry; }

    public void toggleQuarry() {
        if (stationTask != StationTask.MINE) {
            say("Only a miner works a quarry.");
            return;
        }
        quarry = !quarry;
        publishJobState();
        say(quarry
            ? "Quarry it is — I'll cut a level, drop four blocks, and keep going down to Y"
              + (workZone != null ? workZone.depth() : -54) + "."
            : "Back to single galleries.");
    }
    private long ownerSeenGameTime;   // homecoming: last game-time the owner stood near
    private boolean quiet;            // routine chatter off; needs, deaths, paydays stay

    public void cycleEscort() {
        if (stationTask != StationTask.GUARD) {
            say("I'd need to be working as a guard to walk escort.");
            return;
        }
        java.util.List<AssistantEntity> mates = new java.util.ArrayList<>();
        if (ownerId != null) {
            for (AssistantEntity mate : allFor(ownerId)) {
                if (mate != this && mate.isAlive()) mates.add(mate);
            }
        }
        if (mates.isEmpty()) {
            say("Nobody to escort — it's just me out here.");
            return;
        }
        mates.sort((a, b) -> a.displayNameCap().compareToIgnoreCase(b.displayNameCap()));
        int idx = -1;
        for (int i = 0; i < mates.size(); i++) {
            if (mates.get(i).getUUID().equals(escortId)) { idx = i; break; }
        }
        AssistantEntity next = idx + 1 >= mates.size() ? null : mates.get(idx + 1);
        escortId = next == null ? null : next.getUUID();
        say(next == null ? "Back on my own beat."
            : "On escort — I'll stay at " + next.displayNameCap() + "'s shoulder.");
    }

    @Nullable private AssistantEntity escortWard() {
        if (escortId == null || ownerId == null) return null;
        for (AssistantEntity mate : allFor(ownerId)) {
            if (mate.isAlive() && mate.getUUID().equals(escortId)) return mate;
        }
        return null;
    }

    /** One guard engaging is every guard's business: mates on the same patch
     *  converge on the threat instead of finishing their own beats first. Each
     *  keeps its own rules — no creepers without a bow, no rally from across
     *  the map. */
    private void rallyGuards(Monster m) {
        if (ownerId == null || workZone == null) return;
        for (AssistantEntity mate : allFor(ownerId)) {
            if (mate == this || !mate.isAlive()) continue;
            if (mate.stationTask() != StationTask.GUARD) continue;
            if (!workZone.equals(mate.workZone())) continue;
            if (mate.getTarget() != null || mate.shouldDisengage()) continue;
            if (mate.distanceToSqr(m) > 48.0 * 48.0) continue;
            if (m instanceof Creeper && !(mate.hasBow() && mate.hasArrows())) continue;
            mate.setTarget(m);
        }
    }

    public Perk perk30() { return perk30; }

    /** Settle the level-30 perk. Once, and only at 30. */
    public void choosePerk(Perk pick) {
        if (veteranLevel() < 30) {
            say("That choice comes at level 30 — I'm at " + veteranLevel() + ".");
            return;
        }
        if (perk30 != Perk.NONE) {
            say("I settled that long ago — I'm " + perk30.label + ".");
            return;
        }
        perk30 = pick;
        applyLevelPerks();
        publishJobState();
        say("Then I'm " + pick.label + " from here on — " + pick.blurb + ".");
    }

    /** The Porter perk: bank a fuller load, carry a bigger cargo. */
    public int perkPackBonus() {
        return perk30 == Perk.PORTER && veteranLevel() >= 30 ? 24 : 0;
    }

    public Trait trait() { return trait; }

    /** Settle this one's quirk. Derived from its id so it is stable across a
     *  reload and identical on any machine that asks. */
    private void rollTrait() {
        if (trait != Trait.NONE) return;
        Trait[] options = Trait.values();
        int pick = Math.floorMod(getUUID().hashCode(), options.length - 1) + 1;
        trait = options[pick];
    }

    /** Work-speed swing from the trait, as a percentage bonus. */
    private int traitWorkPercent() {
        return switch (trait) {
            case QUICK -> 8;
            case CAVE_SHY -> blockPosition().getY() < 40 ? -15 : 10;
            case NIGHT_OWL -> level().isNight() ? 10 : -5;
            default -> 0;
        };
    }

    /** How much longer this one goes between meals and charges. */
    public int traitUpkeepPercent() {
        return trait == Trait.THRIFTY ? 125 : 100;
    }

    // ----------------------------- sound of work -----------------------------
    // destroyBlock plays its own break sound, so harvesting and digging already
    // sound right at the END of a job. These fill in the rest: the rhythmic
    // hit while working, the placement thunk, the pickup pop — the difference
    // between a farm that is worked and a farm where things silently change.

    private int lastPopTick;

    /** The soft pop of something entering the pack. Rate-limited: a swept pile
     *  of forty drops is one workman picking things up, not a slot machine. */
    public void popSound() {
        if (tickCount - lastPopTick < 10) return;
        lastPopTick = tickCount;
        playSound(net.minecraft.sounds.SoundEvents.ITEM_PICKUP, 0.25F,
            0.9F + random.nextFloat() * 0.4F);
    }

    /** The hit-sound of the block actually being worked, quiet, in time with
     *  the swing — a pick on stone sounds like a pick on stone. */
    public void workHit(BlockPos pos) {
        BlockState st = level().getBlockState(pos);
        if (st.isAir()) return;
        var type = st.getSoundType();
        level().playSound(null, pos, type.getHitSound(), net.minecraft.sounds.SoundSource.BLOCKS,
            (type.getVolume() + 1.0F) / 8.0F, type.getPitch() * 0.6F);
    }

    /** The placement sound of whatever was just set down at pos. */
    public void placeSound(BlockPos pos) {
        BlockState st = level().getBlockState(pos);
        if (st.isAir()) return;
        var type = st.getSoundType();
        level().playSound(null, pos, type.getPlaceSound(), net.minecraft.sounds.SoundSource.BLOCKS,
            (type.getVolume() + 1.0F) / 2.0F, type.getPitch() * 0.8F);
    }

    private int transferBusyUntil;

    /** Moving goods in or out of a block takes a moment, the way it would for a
     *  player at a chest. Instant transfers made a bot empty a double chest in
     *  a single tick, which reads as a glitch rather than as work being done. */
    public boolean transferReady() { return tickCount >= transferBusyUntil; }

    /** Call when a bot actually moves something. Two seconds, and it swings for
     *  it, so you can see the work happening. */
    public void beginTransfer() {
        transferBusyUntil = tickCount + 40;
        swing(net.minecraft.world.InteractionHand.MAIN_HAND);
    }

    private int kitCheckTick = -1000;

    /**
     * Every ten seconds an idle specialist checks it is actually holding the
     * tool of its trade, and equips it if not.
     *
     * <p>The goals equip what they need at the moment they use it, which covers
     * a bot mid-job. This covers the rest of the time — the bot standing at its
     * post between runs holding a stack of wheat because that is what it last
     * picked up, then starting its next job one swing behind. Only when idle,
     * so it never fights a goal that has deliberately put something else in
     * hand for the block in front of it.
     */
    private void tendKit() {
        if (stationTask == StationTask.NONE) return;
        if (getTarget() != null || !jobs.isEmpty() || retreating) return;
        if (tickCount - kitCheckTick < 200) return;
        kitCheckTick = tickCount;
        armorFromChests();
        switch (stationTask) {
            case FARM  -> equipToolNamed("_hoe");
            case WOOD  -> equipToolNamed("_axe");
            case MINE  -> equipToolNamed("_pickaxe");
            case GUARD -> equipToolNamed("_sword");
            case RANCH -> equipToolNamed("shears");
            case FISH  -> equipToolNamed("fishing_rod");
            case SMELT, STORE, HAUL, NONE -> { }   // hands free is correct for these
        }
    }

    private int kitTopUpTick = -1000;
    private int shoulderTicks;       // time spent working the same ground as a mate
    private int bondCheckTick = -1000;

    /**
     * Hands that have worked the same ground for a long time get quicker at it.
     * Small and slow on purpose: a full bond is a couple of in-game weeks of
     * shared work, and it is worth 10%, so it rewards a settled crew without
     * making a lone specialist feel wrong.
     */
    public int teamworkPercent() {
        return shoulderTicks >= 200000 ? 10 : shoulderTicks >= 60000 ? 5 : 0;
    }

    /** Is someone else of this crew working this same patch right now? */
    private void tickTeamwork() {
        if (workZone == null || stationTask == StationTask.NONE || ownerId == null) return;
        if (tickCount - bondCheckTick < 200) return;
        int elapsed = Math.min(400, tickCount - bondCheckTick);
        bondCheckTick = tickCount;
        for (AssistantEntity mate : allFor(ownerId)) {
            if (mate == this || !mate.isAlive()) continue;
            if (workZone.equals(mate.workZone()) && mate.stationTask() != StationTask.NONE) {
                int was = teamworkPercent();
                shoulderTicks = Math.min(200000, shoulderTicks + elapsed);
                if (teamworkPercent() > was) {
                    say("We've got the rhythm of this place now — "
                        + teamworkPercent() + "% quicker working it together.");
                }
                return;
            }
        }
    }

    /** Is this specialist short of the kit it works with, in its own hands?
     *  Reads only the pack — no world access — so it is safe to ask often. */
    private boolean kitShortInHand() {
        // Only count rations and charges as kit when upkeep is switched on. With
        // it off there is no redstone anywhere in the world, so this returned
        // true for ever and the restock re-scanned the chests every couple of
        // seconds for something that was never coming.
        if (com.jrpetty.mcassistant.AssistantConfig.upkeepEnabled()
            && (countCarried(s -> s.get(DataComponents.FOOD) != null) == 0
                || countCarried(s -> s.is(Items.REDSTONE)) == 0)) {
            return true;
        }
        return switch (stationTask) {
            case FARM -> countCarried(s -> isToolNamed(s, "_hoe")) == 0
                || countCarried(FARM_SEEDS) == 0;
            case WOOD -> countCarried(s -> isToolNamed(s, "_axe")) == 0
                || countCarried(s -> s.is(ItemTags.SAPLINGS)) == 0;
            case MINE -> countCarried(s -> isToolNamed(s, "_pickaxe")) == 0
                || countCarried(s -> s.is(Items.TORCH)) < 8;
            case RANCH -> countCarried(s -> s.is(Items.SHEARS)) == 0
                || countCarried(BREEDING_FOOD) < 2;
            case GUARD -> countCarried(s -> isToolNamed(s, "_sword")) == 0;
            case FISH -> countCarried(s -> s.is(Items.FISHING_ROD)) == 0;
            case SMELT -> countCarried(s -> s.is(Items.COAL) || s.is(Items.CHARCOAL)) == 0;
            case STORE, HAUL, NONE -> false;
        };
    }

    private static boolean isToolNamed(ItemStack s, String suffix) {
        return !s.isEmpty() && net.minecraft.core.registries.BuiltInRegistries.ITEM
            .getKey(s.getItem()).getPath().endsWith(suffix);
    }

    /** Spend one upkeep item from the pack, else from a chest in the zone.
     *  Working stock is spared first: a farmer eating its own seed potatoes is
     *  how a field quietly shrinks to nothing over a few hours. Only if there is
     *  genuinely nothing else does it dip into the stock, since going hungry
     *  stops the job outright. */
    private boolean consumeUpkeep(java.util.function.Predicate<ItemStack> what) {
        java.util.function.Predicate<ItemStack> spare = s -> what.test(s) && !isWorkingStock(s);
        if (removeMatching(spare, 1) == 1) return true;
        if (scoopFromChests(what, 8, chestRange(), false) > 0 && removeMatching(spare, 1) == 1) return true;
        return removeMatching(what, 1) == 1;
    }

    // ------------------------------ what it runs on --------------------------
    // A specialist works at the pace of its last meal. Rotten flesh keeps it
    // alive and not much more; bread and vegetables are honest food; a cooked
    // steak is what you feed someone you want a full day's work out of. This is
    // what makes the difference between clearing out your zombie drops and
    // actually provisioning a crew.

    /** How hard this food lets a specialist work, as a percentage of full pace. */
    public static int foodQuality(ItemStack food) {
        FoodProperties fp = food.get(DataComponents.FOOD);
        if (fp == null) return 0;                     // not food at all
        // Anything that would poison a person is scraps, whatever its numbers.
        for (FoodProperties.PossibleEffect e : fp.effects()) {
            if (e.effect().getEffect().value().getCategory()
                    == net.minecraft.world.effect.MobEffectCategory.HARMFUL) {
                return 30;   // rotten flesh, spider eye, pufferfish
            }
        }
        // A proper cooked meal — the top of the scale, by name, because that is
        // the thing a player recognises as "good food".
        if (food.is(Items.COOKED_BEEF) || food.is(Items.COOKED_PORKCHOP)
            || food.is(Items.COOKED_MUTTON) || food.is(Items.COOKED_CHICKEN)
            || food.is(Items.COOKED_RABBIT) || food.is(Items.COOKED_COD)
            || food.is(Items.COOKED_SALMON) || food.is(Items.RABBIT_STEW)
            || food.is(Items.GOLDEN_CARROT) || food.is(Items.GOLDEN_APPLE)
            || food.is(Items.ENCHANTED_GOLDEN_APPLE)) {
            return 100;
        }
        // Everything else scales on how filling it is, so modded food lands
        // somewhere sensible without being listed here.
        int n = fp.nutrition();
        if (n >= 8) return 95;
        if (n >= 6) return 85;   // bread, baked potato, cooked-ish
        if (n >= 4) return 70;   // raw meat, apple, beetroot soup
        if (n >= 2) return 55;   // carrot, melon, cookie
        return 45;
    }

    /** A word for the pace, for the screens. */
    public static String foodLabel(int quality) {
        if (quality >= 100) return "well fed";
        if (quality >= 85) return "fed";
        if (quality >= 70) return "getting by";
        if (quality >= 45) return "poorly fed";
        return "living on scraps";
    }

    private int dietPercent = 100;         // pace set by the last meal
    private String lastMeal = "";

    public int dietPercent() { return dietPercent; }
    public String lastMeal() { return lastMeal; }

    /**
     * Eat the best thing on hand, and let it set the pace until the next meal.
     * Best-first on purpose: a bot holding both steak and rotten flesh should
     * eat the steak, so what it runs on is decided by what you stock, not by
     * which slot the stack happened to land in.
     */
    private boolean eatBestFood() {
        java.util.function.Predicate<ItemStack> isFood =
            s -> s.get(DataComponents.FOOD) != null;
        int bestSlot = -1, bestScore = -1;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.get(i);
            if (s.isEmpty() || !isFood.test(s) || isWorkingStock(s)) continue;
            int score = foodQuality(s);
            if (score > bestScore) { bestScore = score; bestSlot = i; }
        }
        if (bestSlot < 0) {
            // Nothing in the pack — pull from the stores and look again.
            if (scoopFromChests(isFood, 2, chestRange(), false) <= 0) {
                return removeMatching(isFood, 1) == 1;   // last resort: seed stock
            }
            for (int i = 0; i < inventory.size(); i++) {
                ItemStack s = inventory.get(i);
                if (s.isEmpty() || !isFood.test(s) || isWorkingStock(s)) continue;
                int score = foodQuality(s);
                if (score > bestScore) { bestScore = score; bestSlot = i; }
            }
            if (bestSlot < 0) return false;
        }
        ItemStack meal = inventory.get(bestSlot);
        int was = dietPercent;
        dietPercent = bestScore;
        lastMeal = meal.getHoverName().getString();
        // The meal's own sound, asked of the entity — the one path that has
        // kept the same shape across versions while SoundEvents fields turned
        // into Holders one release at a time.
        playSound(this.getEatingSound(meal), 0.6F, 0.9F + random.nextFloat() * 0.2F);
        swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        meal.shrink(1);
        if (meal.isEmpty()) inventory.set(bestSlot, ItemStack.EMPTY);
        if (was != dietPercent) {
            sayRoutine(dietPercent >= 100
                ? "That's proper food — I can give you a full day on that."
                : dietPercent <= 30
                    ? "Scraps again. I'll work, but don't expect much."
                    : "That'll keep me going.");
        }
        return true;
    }

    private int wagePaidUntil;      // work-tick the current wage runs out on
    private int wagesPaid;          // how many have been drawn, for the ledger
    private int ironPaid, goldPaid, diamondPaid;   // what they were paid in

    /**
     * Take one wage in metal and destroy it. Better metal buys a longer stretch,
     * so a diamond is worth handing over rather than just expensive: iron covers
     * one interval, gold two, a diamond four.
     */
    private boolean drawWage() {
        int interval = com.jrpetty.mcassistant.AssistantConfig.wageInterval();
        if (spend(s -> s.is(Items.DIAMOND))) {
            wagePaidUntil = tickCount + interval * 4;
            diamondPaid++;
            wagesPaid++;
            say("Payday — took a diamond; I'm settled for a good while.");
            return true;
        }
        if (spend(s -> s.is(Items.GOLD_INGOT))) {
            wagePaidUntil = tickCount + interval * 2;
            goldPaid++;
            wagesPaid++;
            say("Payday — took a gold ingot; I'm settled for two days.");
            return true;
        }
        if (spend(s -> s.is(Items.IRON_INGOT))) {
            wagePaidUntil = tickCount + interval;
            ironPaid++;
            wagesPaid++;
            say("Payday — took an iron ingot; settled for the day.");
            return true;
        }
        return false;
    }

    /** Destroy one matching item from the pack, or from the linked chests.
     *  Nothing is stored — this is the difference between a wage and a supply. */
    private boolean spend(java.util.function.Predicate<ItemStack> what) {
        if (removeMatching(what, 1) == 1) return true;
        for (ZoneChests.Found f : linkedChests()) {
            if (!f.stillThere()) continue;
            Container c = f.container();
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack st = c.getItem(i);
                if (st.isEmpty() || !what.test(st)) continue;
                st.shrink(1);
                if (st.isEmpty()) c.setItem(i, ItemStack.EMPTY);
                c.setChanged();
                return true;
            }
        }
        return false;
    }

    /** What this one has cost you so far: iron, gold, diamonds. For the ledger. */
    public int[] wageLedger() {
        return new int[]{ ironPaid, goldPaid, diamondPaid, wagesPaid };
    }

    /** How long until its next payday, in ticks (negative when overdue). */
    public int wageDue() {
        return wagePaidUntil - tickCount;
    }

    /** Items this job needs to keep in order to keep producing. */
    private boolean isWorkingStock(ItemStack s) {
        return switch (stationTask) {
            case FARM -> s.is(Items.CARROT) || s.is(Items.POTATO)
                || s.is(Items.WHEAT_SEEDS) || s.is(Items.BEETROOT_SEEDS);
            case RANCH -> BREEDING_FOOD.test(s);
            default -> false;
        };
    }
    /** Chests a specialist can use, matching the range its checklist accepts
     *  one at — otherwise a chest could pass the requirement check and still
     *  be out of reach, leaving the bot silently unable to work. */
    private static final int CHEST_RANGE = 12;
    private static final int STATION_RADIUS = 16;       // how far it works from the post

    // --- the chests linked to this specialist -------------------------------
    // Its stores: what it checks before asking the player for anything, and
    // what it helps itself from. Re-read at most a few times a second, and only
    // ever from the same origin its checklist uses, so a requirement scan and
    // the resupply that follows it share one look at the world instead of
    // walking the same 6,875 positions half a dozen times over.
    @Nullable private java.util.List<ZoneChests.Found> chestIndex;
    private int chestIndexTick = -1000;
    @Nullable private BlockPos chestIndexOrigin;
    private int chestIndexRange;

    /** The chests this specialist is linked to, at the range its checklist
     *  accepts one at. Cached for less than the interval anything re-checks on,
     *  so nothing is ever answered from a look older than its own cadence. */
    /**
     * How far from the middle of its patch a specialist looks for its chests.
     *
     * <p>This was a flat 12 blocks, measured from the patch CENTRE — but the
     * default patch is 25x25, whose corners are 17 blocks from the middle. A
     * chest anywhere but the middle of a plot was therefore invisible, and a
     * farmer would stand beside a chest full of seeds insisting it had none.
     * The range now covers the whole marked plot with a margin.
     */
    public int chestRange() {
        return workZone == null ? CHEST_RANGE
            : Math.max(CHEST_RANGE, Math.min(48, workZone.radius() + 6));
    }

    public java.util.List<ZoneChests.Found> linkedChests() {
        BlockPos origin = chestSearchOrigin();
        if (chestIndex != null && chestIndexOrigin != null && chestIndexOrigin.equals(origin)
            && chestIndexRange == chestRange()
            && tickCount - chestIndexTick < 200 && tickCount >= chestIndexTick) {
            // Reuse for ten seconds, not one and a half — but only while every
            // chest in it still exists. Contents are read live either way; the
            // only thing this list can get wrong is a chest appearing or going,
            // so a broken chest forces a fresh look and a new one is seen on
            // the next scan (a failed restock drops the cache immediately).
            boolean intact = true;
            for (ZoneChests.Found f : chestIndex) {
                if (!f.stillThere()) { intact = false; break; }
            }
            if (intact) return chestIndex;
        }
        chestIndex = ZoneChests.around(level(), origin, chestRange(), 5);
        chestIndexOrigin = origin;
        chestIndexRange = chestRange();
        chestIndexTick = tickCount;
        return chestIndex;
    }

    /** Drop the cached look at the stores — when the patch moves. Contents
     *  are read live through the block entities, so taking from a chest needs
     *  no invalidation; only chests appearing or going away do. */
    public void forgetChestIndex() {
        chestIndex = null;
        knownWater = null;
    }

    /** Drop only the chest list, keeping the remembered water. For retries
     *  after a failed restock: a new CHEST may have appeared, but wiping the
     *  pond along with it put a stalled fisher back on the full-box water
     *  scan every checklist — the exact cost the chunk-map lookup removed. */
    private void dropChestIndex() {
        chestIndex = null;
    }

    @Nullable private BlockPos knownWater;

    /** Water in the patch, for a fisher. Checks the last place it found some
     *  first: a pond does not move, and the alternative is reading every block
     *  state in a 25x11x25 box each time the checklist is re-run. */
    public boolean waterInZone() {
        BlockPos origin = chestSearchOrigin();
        // Both halves matter: still water, and still inside the box the
        // checklist asks about. Checking only the block would keep answering
        // "yes" from a pond the patch has since been moved away from.
        if (knownWater != null
            && Math.abs(knownWater.getX() - origin.getX()) <= chestRange()
            && Math.abs(knownWater.getZ() - origin.getZ()) <= chestRange()
            && Math.abs(knownWater.getY() - origin.getY()) <= 5
            && level().getBlockState(knownWater).is(Blocks.WATER)) {
            return true;
        }
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-chestRange(), -5, -chestRange()),
                origin.offset(chestRange(), 5, chestRange()))) {
            if (level().getBlockState(pos).is(Blocks.WATER)) {
                knownWater = pos.immutable();
                return true;
            }
        }
        knownWater = null;
        return false;
    }
    private int lastWarnTick = -1000;
    private boolean nightHome;        // "head home at night" toggle
    @Nullable private BlockPos bedPos;  // the bed this one sleeps in (persisted)
    private Shift shift = Shift.DAY;    // when this specialist is on duty

    /** When a specialist works. Off shift it goes to its bed and sleeps, which
     *  is also how it stops standing in the open at night getting killed. */
    public enum Shift {
        DAY("days"), NIGHT("nights"), ALWAYS("day and night");
        public final String label;
        Shift(String label) { this.label = label; }
        public Shift next() { return values()[(ordinal() + 1) % values().length]; }
    }

    public Shift shift() { return shift; }

    public void setShift(Shift s) {
        this.shift = s;
        refreshJobState();
    }

    /** The saved patch this one is working, if it was put on one. Two bots on
     *  the same preset are crewing the same ground together. */
    @Nullable private String presetName;

    @Nullable public String preset() { return presetName; }

    public void setPreset(@Nullable String name) { this.presetName = name; }

    /** Is this specialist on duty right now? */
    public boolean onShift() {
        return switch (shift) {
            case ALWAYS -> true;
            case DAY -> !level().isNight();
            case NIGHT -> level().isNight();
        };
    }

    @Nullable public BlockPos bedPos() { return bedPos; }

    /** Claim the nearest free bed to `near` — how a player assigns one. */
    public boolean claimBedNear(BlockPos near) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                near.offset(-12, -5, -12), near.offset(12, 5, 12))) {
            BlockState bedState = level().getBlockState(pos);
            if (!(bedState.getBlock() instanceof net.minecraft.world.level.block.BedBlock)) continue;
            // A bed is two blocks — count each once, by its head — and a bed
            // someone is IN, or that a crewmate already calls its own, is not
            // on offer. Auto-claiming used to be the button's job; now that it
            // happens by itself at dusk it must never take a mate's.
            if (bedState.getValue(net.minecraft.world.level.block.BedBlock.PART)
                    != net.minecraft.world.level.block.state.properties.BedPart.HEAD) continue;
            if (bedState.getValue(net.minecraft.world.level.block.BedBlock.OCCUPIED)) continue;
            if (claimedByCrewmate(pos)) continue;
            double d = pos.distSqr(near);
            if (d < bestDist) { bestDist = d; best = pos.immutable(); }
        }
        if (best == null) return false;
        this.bedPos = best;
        refreshJobState();
        return true;
    }

    private boolean claimedByCrewmate(BlockPos pos) {
        if (ownerId == null) return false;
        for (AssistantEntity mate : allFor(ownerId)) {
            if (mate == this || !mate.isAlive()) continue;
            BlockPos theirs = mate.bedPos();
            if (theirs != null && theirs.distSqr(pos) <= 2.0) return true;
        }
        return false;
    }

    /**
     * Off-shift behaviour: go to bed and sleep there. Returns true when it has
     * taken charge of the bot, so the work brain leaves it alone. Without this a
     * specialist simply stood in the open all night and got killed.
     */
    private boolean restIfOffShift() {
        boolean resting = !onShift();
        if (!resting) {
            if (isSleeping()) stopSleeping();
            return false;
        }
        // Clocking off. Hand the load to whoever is taking over this ground, so
        // a day/night pair on one plot is a shift with a handover rather than
        // two bots that happen to share a field.
        handOver();
        if (getTarget() != null || retreating) {
            if (isSleeping()) stopSleeping(); // defend yourself first
            return false;
        }
        BlockPos bed = bedPos;
        if (bed != null && !(level().getBlockState(bed).getBlock()
                instanceof net.minecraft.world.level.block.BedBlock)) {
            bed = null;                 // someone mined it
            bedPos = null;
        }
        // No bed of its own? Claim the nearest free one around home or the
        // patch, once a night, instead of standing bedless until the player
        // notices and presses the button.
        if (bed == null && tickCount - bedClaimTick > 6000) {
            bedClaimTick = tickCount;
            BlockPos base = homePos != null ? homePos
                : workZone != null ? workZone.center() : blockPosition();
            if (claimBedNear(base)) {
                bed = bedPos;
                sayRoutine("Claimed the spare bed for myself.");
            }
        }
        if (bed == null) {
            // No bed assigned: at least go home rather than stand in a field.
            // Path starts are staggered — a whole crew clocking off at dusk
            // used to fire every pathfind on the same tick.
            if (homePos != null && homePos.distSqr(blockPosition()) > 9.0) {
                if (getNavigation().isDone() && staggerBeat()) {
                    getNavigation().moveTo(homePos.getX() + 0.5, homePos.getY(), homePos.getZ() + 0.5, 1.0D);
                }
                return true;
            }
            // Off duty is not a statue: a small, cheap stroll around home now
            // and then — one short path a while, tethered to a few blocks, in
            // place of the full idle brain it used to keep running for nothing.
            if (homePos != null && getNavigation().isDone() && random.nextInt(20) == 0) {
                int dx = random.nextInt(9) - 4, dz = random.nextInt(9) - 4;
                getNavigation().moveTo(homePos.getX() + dx + 0.5, homePos.getY(),
                    homePos.getZ() + dz + 0.5, 0.8D);
            }
            return homePos != null; // parked at home, off duty
        }
        if (bed.distSqr(blockPosition()) > 4.0) {
            if (isSleeping()) stopSleeping();
            if (getNavigation().isDone() && staggerBeat()) {
                getNavigation().moveTo(bed.getX() + 0.5, bed.getY(), bed.getZ() + 0.5, 1.0D);
            }
            return true;
        }
        if (!isSleeping()) {
            getNavigation().stop();
            startSleeping(bed);
        }
        return true;
    }
    private boolean wentHomeTonight;  // one trip per night
    private int bedClaimTick = -99999; // one bed hunt per night, not per tick
    private boolean parkedForNight;   // the night routine parked it (un-park at dawn)
    private int baseStage;            // how far it has built up its home base (0=just home+chest)

    // Experience points, earned fairly from its OWN kills, ore, and smelting —
    // spent (with lapis) at an enchanting table. Not vanilla XP levels; a plain
    // pool so mobs can play by the enchant-costs-effort rule.
    private int xp;
    private int lifetimeXp;            // never decreases — drives the veteran level

    // Nether expedition state. Persisted in NBT because a dimension change
    // swaps the entity instance (and drops the transient job queue), so the
    // resurrected nether/overworld entity resumes from these.
    private boolean expeditionActive;
    private int expeditionPhase;                 // 1 = gathering in nether, 2 = back
    private String expeditionTarget = "glowstone";
    private int expeditionRemaining;
    @Nullable private BlockPos expeditionReturn; // overworld spot to come back to

    @Nullable private BlockPos homePos;

    // Named waypoints: "remember this spot as the mine" -> "go to the mine".
    private final Map<String, BlockPos> waypoints = new ConcurrentHashMap<>();

    public AssistantEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        this.setCanPickUpLoot(false); // we manage pickups into our own inventory
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0D)
            .add(Attributes.MOVEMENT_SPEED, 0.32D)
            .add(Attributes.ATTACK_DAMAGE, 4.0D)
            // Step up full blocks instead of catching on 1-block ledges (the
            // single biggest pathing improvement over vanilla mobs), a long
            // path budget (follow range feeds the pathfinder's node limit), and
            // a bit more reach for the follow/travel goals.
            .add(Attributes.STEP_HEIGHT, 1.0D)
            .add(Attributes.FOLLOW_RANGE, 64.0D);
    }

    /** A ground navigator that floats over water, opens/passes doors, and
     *  searches a bigger area — so it stops giving up on real terrain. */
    @Override
    protected net.minecraft.world.entity.ai.navigation.PathNavigation createNavigation(Level level) {
        GroundPathNavigation nav = new GroundPathNavigation(this, level);
        nav.setCanOpenDoors(true);
        nav.setCanPassDoors(true);
        nav.setCanFloat(true); // don't treat water as a wall — float across it
        // Most assistant deaths were stupid ones: walking into lava, strolling
        // off a ledge, wading into a fire. Make the pathfinder refuse those
        // routes outright rather than costing them slightly.
        this.setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.LAVA, -1.0F);
        this.setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.DAMAGE_FIRE, -1.0F);
        this.setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.DANGER_FIRE, -1.0F);
        this.setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.DANGER_OTHER, -1.0F);
        this.setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.DAMAGE_OTHER, -1.0F);
        this.setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.WATER_BORDER, 8.0F);
        return nav;
    }

    /**
     * The deaths that made assistants look daft: standing in fire, wading into
     * lava, drowning in their own irrigation, and stepping off a drop that was
     * never on the path. The pathfinder handles routes; this handles the moment.
     */
    private void avoidStupidDeaths() {
        if (level().isClientSide) return;

        // On fire or in lava: get out, and stop whatever we were walking to.
        if (this.isInLava() || (this.isOnFire() && this.getRemainingFireTicks() > 20)) {
            BlockPos safe = nearestSafeGround(6);
            if (safe != null) {
                getNavigation().moveTo(safe.getX() + 0.5, safe.getY(), safe.getZ() + 0.5, 1.4D);
            }
            return;
        }
        // Running out of air: head for the surface instead of quietly drowning.
        if (this.isEyeInFluid(net.minecraft.tags.FluidTags.WATER) && this.getAirSupply() < 120) {
            BlockPos surface = surfaceAbove();
            if (surface != null) {
                getNavigation().moveTo(surface.getX() + 0.5, surface.getY(), surface.getZ() + 0.5, 1.3D);
            }
            return;
        }
        // About to walk off something that will hurt: stop and re-route.
        if (!onGround() || getNavigation().isDone()) return;
        BlockPos ahead = blockPosition().relative(getDirection());
        if (dropBelow(ahead) > 4 || level().getBlockState(ahead.below()).is(Blocks.LAVA)) {
            getNavigation().stop();
            setDeltaMovement(getDeltaMovement().multiply(0.2, 1.0, 0.2));
        }
    }

    /** Is there something directly ahead that a one-block hop would clear?
     *  Jumping at open air, at a wall, or at a three-block cliff achieves
     *  nothing and just looks like a malfunction. */
    private int lastJumpTick = -1000;
    private int moveBlockedUntil;

    /**
     * The ONLY way anything in this mod jumps.
     *
     * <p>There were four separate places calling jump() — the unstuck routine,
     * two in the escape goal, one in the pillar-up — each individually
     * reasonable and each capable of firing every few ticks. Between them a bot
     * could hop several times a second indefinitely, and chasing them one at a
     * time did not work twice. So they all come through here, and here there is
     * a hard floor of two seconds between hops and a requirement to actually be
     * on the ground. Whatever the logic above decides, a bot physically cannot
     * bounce any more.
     *
     * @return true if it actually jumped
     */
    public boolean tryHop() {
        if (!onGround() || tickCount - lastJumpTick < 40) return false;
        getJumpControl().jump();
        return true;
    }

    /**
     * The real gate on jumping, and the only one that can work.
     *
     * <p>Three attempts at this bug rate-limited the mod's own calls to jump().
     * All three were beside the point: the jumps were not coming from the mod.
     * Vanilla's MoveControl calls getJumpControl().jump() directly whenever the
     * next path node sits higher than the mob's step height and is close by, so
     * a bot repeatedly handed a node it cannot reach bounces on the spot at the
     * engine's own pace — one jump every ten ticks, for ever, no matter what
     * this mod does or does not ask for.
     *
     * <p>Every jump from every source — MoveControl, JumpControl, our own goals
     * — passes through setJumping(), because that flag is what aiStep reads
     * before calling jumpFromGround(). So this is the single place a cap
     * actually holds, and it holds against code I do not control.
     *
     * <p>One second between jumps: enough to still climb a ledge on a route,
     * far too slow to read as bouncing. Fluids are exempt because swimming up
     * IS this flag, and a capped one would drown.
     */
    @Override
    public void setJumping(boolean jumping) {
        // A ladder is climbed by HOLDING jump — vanilla applies the upward
        // motion in handleOnClimbable while the jump flag is set. The anti-hop
        // cap below would therefore have let a bot rise one block a second and
        // slide back down between tries, which reads as being stuck at the
        // bottom of its own shaft. Climbing is exempt, exactly as fluids are.
        if (jumping && !level().isClientSide && !isInWater() && !isInLava()
            && !onClimbable()) {
            if (tickCount - lastJumpTick < 20) {
                super.setJumping(false);
                return;
            }
            lastJumpTick = tickCount;
        }
        super.setJumping(jumping);
    }

    /** True while this bot has given up on getting somewhere and is deliberately
     *  standing still. Anything that hands out a new walking target checks it,
     *  or the give-up is undone on the very next decision. */
    public boolean movementBlocked() {
        return tickCount < moveBlockedUntil;
    }

    private boolean jumpableObstacleAhead() {
        BlockPos ahead = blockPosition().relative(getDirection());
        if (level().getBlockState(ahead).isAir()) return false;      // nothing in the way
        return level().getBlockState(ahead.above()).isAir()
            && level().getBlockState(ahead.above(2)).isAir();        // room to land and stand
    }

    /** How far it is down from a block before something solid catches you. */
    private int dropBelow(BlockPos pos) {
        int drop = 0;
        BlockPos.MutableBlockPos cur = pos.mutable().move(net.minecraft.core.Direction.DOWN);
        while (drop < 24 && level().getBlockState(cur).isAir()) {
            drop++;
            cur.move(net.minecraft.core.Direction.DOWN);
        }
        return drop;
    }

    @Nullable
    private BlockPos nearestSafeGround(int radius) {
        BlockPos feet = blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                feet.offset(-radius, -2, -radius), feet.offset(radius, 2, radius))) {
            if (!level().getBlockState(pos).isAir()) continue;
            if (level().getBlockState(pos.below()).isAir()) continue;
            if (level().getBlockState(pos.below()).is(Blocks.LAVA)) continue;
            if (level().getBlockState(pos).is(Blocks.FIRE)) continue;
            if (!level().getFluidState(pos).isEmpty()) continue;
            double d = pos.distSqr(feet);
            if (d < bestDist) { bestDist = d; best = pos.immutable(); }
        }
        return best;
    }

    @Nullable
    private BlockPos surfaceAbove() {
        BlockPos.MutableBlockPos cur = blockPosition().mutable();
        for (int i = 0; i < 24; i++) {
            cur.move(net.minecraft.core.Direction.UP);
            if (level().getFluidState(cur).isEmpty() && level().getBlockState(cur).isAir()) {
                return cur.immutable();
            }
        }
        return null;
    }

    /** True once it's been going nowhere for ~4s while actively trying to path —
     *  a signal it's genuinely wedged, so EscapeGoal can dig/pillar it free. */
    public boolean isBadlyStuck() {
        return stuckStreak >= 4;
    }

    private int firstSlot(java.util.function.Predicate<ItemStack> p) {
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.get(i);
            if (!s.isEmpty() && p.test(s)) return i;
        }
        return -1;
    }

    private boolean hasHarmfulEffect() {
        for (var e : getActiveEffects()) {
            if (e.getEffect().value().getCategory() == net.minecraft.world.effect.MobEffectCategory.HARMFUL) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private BlockPos findNearestWater(int radius) {
        BlockPos feet = blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                feet.offset(-radius, -3, -radius), feet.offset(radius, 3, radius))) {
            if (level().getFluidState(pos).is(net.minecraft.world.level.material.Fluids.WATER)) {
                double d = pos.distSqr(feet);
                if (d < bestDist) { bestDist = d; best = pos.immutable(); }
            }
        }
        return best;
    }

    private boolean noTorchNear(int radius) {
        BlockPos feet = feetPos();
        for (BlockPos pos : BlockPos.betweenClosed(
                feet.offset(-radius, -2, -radius), feet.offset(radius, 2, radius))) {
            BlockState st = level().getBlockState(pos);
            if (st.is(Blocks.TORCH) || st.is(Blocks.WALL_TORCH)) return false;
        }
        return true;
    }

    private void placeTorchNearby() {
        BlockPos feet = feetPos();
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos p = feet.relative(dir);
            if (level().getBlockState(p).canBeReplaced()
                && level().getBlockState(p.below()).isSolid()) {
                if (removeMatching(s -> s.is(Items.TORCH), 1) == 1) {
                    level().setBlockAndUpdate(p, Blocks.TORCH.defaultBlockState());
                }
                return;
            }
        }
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new EscapeGoal(this)); // dig/pillar out when buried or boxed in
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(0, new CreeperDodgeGoal(this));
        this.goalSelector.addGoal(1, new RetreatGoal(this));
        this.goalSelector.addGoal(1, new NightShelterGoal(this)); // seals in at night; yields to retreat
        this.goalSelector.addGoal(1, new OpenDoorGoal(this, true));
        this.goalSelector.addGoal(2, new GatherGoal(this));
        this.goalSelector.addGoal(2, new DepositGoal(this));
        this.goalSelector.addGoal(2, new CraftGoal(this));
        this.goalSelector.addGoal(2, new WithdrawGoal(this));
        this.goalSelector.addGoal(2, new FarmGoal(this));
        this.goalSelector.addGoal(2, new BuildGoal(this));
        this.goalSelector.addGoal(2, new SmeltGoal(this));
        this.goalSelector.addGoal(2, new MineGoal(this));
        this.goalSelector.addGoal(2, new ShearGoal(this));
        this.goalSelector.addGoal(2, new GiveGoal(this));
        this.goalSelector.addGoal(2, new TravelGoal(this));
        this.goalSelector.addGoal(2, new PatrolGoal(this));
        this.goalSelector.addGoal(2, new ClearGoal(this));
        this.goalSelector.addGoal(2, new TorchAreaGoal(this));
        this.goalSelector.addGoal(2, new BridgeGoal(this));
        this.goalSelector.addGoal(2, new BreedGoal(this));
        this.goalSelector.addGoal(2, new HerdGoal(this));
        this.goalSelector.addGoal(2, new FishGoal(this));
        this.goalSelector.addGoal(2, new CleanupGoal(this));
        this.goalSelector.addGoal(2, new RecoverGoal(this));
        this.goalSelector.addGoal(2, new SortGoal(this));
        this.goalSelector.addGoal(2, new EnchantGoal(this));
        this.goalSelector.addGoal(2, new NetherGoal(this)); // retreat (prio 1) still preempts
        this.goalSelector.addGoal(2, new BoatGoal(this));
        this.goalSelector.addGoal(2, new DiagnosticsGoal(this)); // "run diagnostics" self-test
        this.goalSelector.addGoal(2, new ExploreGoal(this)); // relocate to fresh terrain when the area's dry
        this.goalSelector.addGoal(2, new HuntGoal(this)); // flagless coordinator
        this.goalSelector.addGoal(3, new BowAttackGoal(this));
        this.goalSelector.addGoal(4, new MeleeAttackGoal(this, 1.25D, true));
        this.goalSelector.addGoal(5, new FollowOwnerGoal(this, 1.2D, 4.0F, 32.0F));
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(
            this, Monster.class, 10, true, false, this::shouldAutoAttack));
    }

    private boolean shouldAutoAttack(LivingEntity target) {
        if (retreating) return false;
        // Threat-aware: don't pick a fight we should be fleeing (low HP with no
        // way to heal, or outnumbered and under-armored). RetreatGoal takes over.
        if (shouldDisengage()) return false;
        // Creepers are melee suicide — but with a bow and arrows we can take
        // them from range instead of just ignoring them.
        if (target instanceof Creeper && !(hasBow() && hasArrows())) return false;
        if (this.mode == Mode.STAY) return false;
        double toMe = target.distanceToSqr(this);
        if (toMe <= 8.0 * 8.0) return true;
        if (this.mode == Mode.GUARD) {
            Player owner = this.getOwnerPlayer();
            return owner != null && target.distanceToSqr(owner) <= 12.0 * 12.0;
        }
        return false;
    }

    // ------------------------------- ownership -------------------------------

    public void setOwner(@Nullable Player player) {
        this.ownerId = player == null ? null : player.getUUID();
        if (this.ownerId != null) {
            rollTrait();
            applyLevelPerks();   // a sturdy one starts with its extra heart
        }
    }

    @Nullable
    public UUID getOwnerId() {
        return ownerId;
    }

    public boolean isOwner(Player player) {
        return ownerId != null && ownerId.equals(player.getUUID());
    }

    @Nullable
    public Player getOwnerPlayer() {
        if (ownerId == null) return null;
        return this.level().getPlayerByUUID(ownerId);
    }

    // ------------------------------ name & role ------------------------------

    public String getAssistantName() {
        return assistantName;
    }

    public String displayNameCap() {
        return assistantName.isEmpty() ? "Assistant"
            : Character.toUpperCase(assistantName.charAt(0)) + assistantName.substring(1);
    }

    public void rename(String newName) {
        // Names stay simple enough to be a lookup key, but a tag reading
        // "Old Bill" should give Old_bill rather than Oldbill.
        String clean = newName.toLowerCase().trim()
            .replaceAll("\\s+", "_")
            .replaceAll("[^a-z0-9_]", "");
        if (clean.isEmpty()) return;
        if (ownerId != null) {
            Map<String, AssistantEntity> m = BY_OWNER.get(ownerId);
            if (m != null) m.remove(assistantName.toLowerCase(), this);
        }
        this.assistantName = clean;
        this.lastShownHealth = -1; // refresh the nametag
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public boolean isAutonomous() {
        return autonomous;
    }

    public void setAutonomous(boolean autonomous) {
        this.autonomous = autonomous;
        this.idleBackoffUntil = 0;
        // Start looking after itself the moment the order lands, not on the next
        // 10-second idle tick — so "work on your own" visibly kicks in right away.
        // Autonomy means self-directed and DETACHED from the player: it stops
        // trailing/teleporting to the owner (FollowOwnerGoal yields while autonomous)
        // and the idle brain runs regardless of mode, so it plays for itself.
        if (autonomous) {
            this.idleKick = true;
            this.getNavigation().stop(); // drop any lingering follow path immediately
        }
    }

    /** Pin (or release, pos=null) this bot to a full-time station. Stationing
     *  turns autonomy on — a specialist works detached — and anchors home at the
     *  post if none was set, so night/retreat behavior centers on its plot.
     *  (Not for a hauler: its home is the DELIVERY end, never the pickup post.)
     *  The post's chunks are kept force-loaded so the station runs while the
     *  player is far away; the window moves/frees as the station changes. */
    public void setStation(@Nullable BlockPos pos, StationTask task) {
        if (level() instanceof net.minecraft.server.level.ServerLevel sl) {
            if (stationPos != null) {
                com.jrpetty.mcassistant.ChunkLoad.setLoaded(sl, getUUID(), stationPos,
                    stationChunkRadius(stationTask), false);
            }
            if (pos != null) {
                com.jrpetty.mcassistant.ChunkLoad.setLoaded(sl, getUUID(), pos,
                    stationChunkRadius(task), true);
            }
        }
        this.stationPos = pos;
        this.stationTask = pos == null ? StationTask.NONE : task;
        freeMobileWindow(); // a task change ends any traveling window cleanly
        if (pos != null) {
            if (homePos == null && task != StationTask.HAUL) setHome(pos);
            setAutonomous(true);
        }
    }

    /**
     * Small change between jobs: sweep up loose drops in reach, and put a torch
     * down if it's standing somewhere dark. Costs nothing, stops a specialist
     * looking idle, and quietly keeps its patch tidy and mob-free.
     */
    private void doIdleChores() {
        // Sweep anything within arm's reach into the pack.
        if (!isPackFull()) {
            for (net.minecraft.world.entity.item.ItemEntity drop : level().getEntitiesOfClass(
                    net.minecraft.world.entity.item.ItemEntity.class,
                    getBoundingBox().inflate(6.0))) {
                if (!drop.isAlive() || !inZone(drop.blockPosition())) continue;
                ItemStack left = insertItem(drop.getItem());
                if (left.isEmpty()) drop.discard(); else drop.setItem(left);
                return;
            }
        }
        // A dark spot on its own ground is where the next creeper comes from.
        if (countCarried(s -> s.is(Items.TORCH)) > 0
            && level().getMaxLocalRawBrightness(blockPosition()) < 6
            && inZone(blockPosition())) {
            placeTorchNearby();
        }
        labelChests();
        // A farmer with bonemeal is a farm that grows while you watch: one
        // application per quiet moment, only on something still growing —
        // never on grass, which vanilla would answer with a lawn of flowers.
        if (stationTask == StationTask.FARM) boneMealOne();
        // A quiet moment and more than one chest close by: straighten the
        // stores. Anybody's chests deserve the storekeeper treatment, not just
        // a storekeeper's — but rarely, so it is a chore, not a career. Gated
        // on chests within the sorter's own 20-block reach, or the job would
        // just announce it can't find the chests it was queued for.
        if (stationTask != StationTask.STORE && tickCount - stationSortTick > 6000
            && lastStashTick > stationSortTick) {  // only when something new was banked
            int close = 0;
            for (ZoneChests.Found f : linkedChests()) {
                if (f.stillThere() && f.pos().distSqr(blockPosition()) <= 18 * 18) close++;
            }
            if (close >= 2) {
                stationSortTick = tickCount;
                enqueue(Job.sort());
            }
        }
    }

    /** Release the hauler's traveling chunk window, re-forcing the fixed post
     *  window afterward in case the two overlapped (tickets are a set — one
     *  remove would otherwise strip a shared chunk from the post's window). */
    private void freeMobileWindow() {
        if (mobileLoadCenter == null) return;
        if (level() instanceof net.minecraft.server.level.ServerLevel sl) {
            com.jrpetty.mcassistant.ChunkLoad.setLoaded(sl, getUUID(), mobileLoadCenter, 1, false);
            if (stationPos != null && stationTask != StationTask.NONE) {
                com.jrpetty.mcassistant.ChunkLoad.setLoaded(sl, getUUID(), stationPos,
                    stationChunkRadius(stationTask), true);
            }
        }
        mobileLoadCenter = null;
    }

    /** How many chunks around the post stay loaded: 3x3 normally; 5x5 for a
     *  mine, whose staircase runs well past the post horizontally. */
    private static int stationChunkRadius(StationTask task) {
        return task == StationTask.MINE ? 2 : 1;
    }

    @Nullable public BlockPos stationPos() { return stationPos; }

    public StationTask stationTask() { return stationTask; }

    /** How many of this item to KEEP BACK when depositing — each specialist
     *  holds onto its working kit: seed stock, saplings, breeding food and
     *  shears, torches and arrows, ore and fuel, or travel rations. */
    /** The one tool this trade works with, or null for jobs with none. */
    @Nullable
    private java.util.function.Predicate<ItemStack> tradeTool() {
        return switch (stationTask) {
            case FARM -> s -> isToolNamed(s, "_hoe");
            case WOOD -> s -> isToolNamed(s, "_axe");
            case MINE -> s -> isToolNamed(s, "_pickaxe");
            case GUARD -> s -> isToolNamed(s, "_sword");
            case RANCH -> s -> s.is(Items.SHEARS);
            case FISH -> s -> s.is(Items.FISHING_ROD);
            default -> null;
        };
    }

    public int depositReserve(ItemStack s) {
        // Exactly ONE of the trade's tool is working kit; any spares go back
        // in the chest on the next stash, so a bot that somehow ended up with
        // five axes hands four back instead of hoarding the whole rack.
        java.util.function.Predicate<ItemStack> tool = tradeTool();
        if (tool != null && tool.test(s)) return 1;
        int job = jobDepositReserve(s);
        if (stationTask == StationTask.NONE) return job;
        // Every working specialist holds its own upkeep back, whatever its job.
        // Without this a miner stashes the very redstone its core charge is
        // about to need — and a farmer the food it eats — then immediately digs
        // it back out of the chest again.
        int upkeep = s.is(Items.REDSTONE) ? 4 : (s.get(DataComponents.FOOD) != null ? 8 : 0);
        return Math.max(job, upkeep);
    }

    private int jobDepositReserve(ItemStack s) {
        return switch (stationTask) {
            case FARM -> (s.is(Items.WHEAT_SEEDS) || s.is(Items.BEETROOT_SEEDS)
                || s.is(Items.CARROT) || s.is(Items.POTATO)) ? 12 : 0;
            case WOOD -> s.is(ItemTags.SAPLINGS) ? 16 : 0;
            case RANCH -> BREEDING_FOOD.test(s) ? 16 : (s.is(Items.SHEARS) ? 1 : 0);
            case GUARD -> s.is(Items.TORCH) ? 16 : (s.is(Items.ARROW) ? 32
                : (s.get(DataComponents.FOOD) != null ? 8 : 0));
            case SMELT -> (s.is(Items.RAW_IRON) || s.is(Items.RAW_GOLD) || s.is(Items.RAW_COPPER)) ? 64
                : ((s.is(Items.COAL) || s.is(Items.CHARCOAL)) ? 32
                : (s.get(DataComponents.FOOD) != null ? 8 : 0));
            case HAUL -> s.get(DataComponents.FOOD) != null ? 8 : 0; // rations for the road
            case MINE -> s.is(Items.TORCH) ? 16 : (s.is(Items.COBBLESTONE) ? 16
                : (s.get(DataComponents.FOOD) != null ? 8 : 0)); // torches, bridging blocks, rations
            case FISH -> s.is(Items.FISHING_ROD) ? 1 : (s.get(DataComponents.FOOD) != null ? 8 : 0);
            case STORE -> s.get(DataComponents.FOOD) != null ? 8 : 0;
            case NONE -> 0;
        };
    }

    /** Say something to the owner. */
    /** Narration — "gathering 16 logs", "stashed 12 items", "dropping a line".
     *  A specialist quietly getting on with its job says NONE of this: it is
     *  the difference between a colleague and a running commentary. Anything
     *  the player actually needs (a missing tool, an empty chest, a level-up,
     *  a death) goes through say() instead and always gets through. */
    public void sayRoutine(String message) {
        if (quiet) return;   // the player asked for the important lines only
        if (stationTask != StationTask.NONE && autonomous) return;
        say(message);
    }

    public boolean quiet() { return quiet; }

    public void toggleQuiet() {
        quiet = !quiet;
        publishJobState();
        say(quiet ? "Understood — I'll keep it to what matters."
                  : "Chatty again, then.");
    }

    public void say(String message) {
        // A stationed specialist runs the same loop for hours — narrating every
        // cycle ("Dropping a line." … "Caught 8 — good haul." … forever) would
        // bury the chat. While one is quietly working its job, a line it has
        // already said recently is dropped; anything NEW still comes straight
        // through, so problems are never silenced. Counts are normalised, so
        // "Stashed 12 items" and "Stashed 37 items" count as the same line.
        if (stationTask != StationTask.NONE && autonomous) {
            String key = message.replaceAll("\\d+", "#");
            Integer last = recentlySaid.get(key);
            if (last != null && tickCount - last < 6000) return; // ~5 minutes
            if (recentlySaid.size() > 32) recentlySaid.clear();
            recentlySaid.put(key, tickCount);
        }
        Player owner = getOwnerPlayer();
        if (owner instanceof ServerPlayer sp) {
            sp.sendSystemMessage(Component.literal("<" + displayNameCap() + "> " + message));
        }
    }

    private final Map<String, Integer> recentlySaid = new java.util.HashMap<>();

    // --- What this specialist has actually produced, so hours of unattended
    //     work are legible instead of invisible. Counted as it stashes. ---
    private final Map<net.minecraft.world.item.Item, Integer> produced = new java.util.LinkedHashMap<>();
    private long lastReportDay = -1;

    /** Called as a job stashes its output — the raw material of the day's report. */
    /** What this one has actually done, for life. Kept separate from the daily
     *  production tally: that resets at dawn, this is its career. */
    public enum Deed {
        BLOCKS_MINED("blocks mined"), ORE_FOUND("ore veins dug"), TREES_FELLED("trees felled"),
        SAPLINGS_PLANTED("saplings planted"), CROPS_HARVESTED("crops harvested"),
        CROPS_PLANTED("crops planted"), ANIMALS_BRED("animals bred"),
        ANIMALS_SHEARED("sheep sheared"), FISH_CAUGHT("fish caught"),
        ITEMS_SMELTED("items smelted"), MOBS_KILLED("hostiles killed"),
        LOADS_HAULED("loads delivered"), CHESTS_SORTED("chests tidied"),
        ITEMS_STASHED("items stashed");

        public final String label;
        Deed(String label) { this.label = label; }
    }

    private final java.util.EnumMap<Deed, Integer> deeds = new java.util.EnumMap<>(Deed.class);

    /** Record a unit of work. Cheap enough to call from the goals directly. */
    public void note(Deed deed, int count) {
        if (count <= 0) return;
        deeds.merge(deed, count, Integer::sum);
        deedStamp++;
    }

    private String lastShownMarks = "";

    /** What a long career shows on the nametag. One chevron for a thousand of
     *  the thing this trade is judged on, two for five thousand, three for
     *  twenty — earned slowly enough that seeing one means something. */
    private String milestoneMarks() {
        int best = 0;
        for (int n : deeds.values()) best = Math.max(best, n);
        if (best >= 20000) return "❯❯❯";
        if (best >= 5000) return "❯❯";
        if (best >= 1000) return "❯";
        return "";
    }

    /** Bumped whenever the career tally changes, so the published career string
     *  can be rebuilt on change rather than on every publish. */
    private long deedStamp;
    private long lastExtraStamp = Long.MIN_VALUE;
    private long lastExtraZone = Long.MIN_VALUE;

    /** Cheap identity for the current patch, for the same reason. */
    private long zoneStamp() {
        return workZone == null ? 0L
            : workZone.min().asLong() * 31L + workZone.max().asLong() + workZone.depth();
    }

    public int deedCount(Deed deed) { return deeds.getOrDefault(deed, 0); }

    /** The two or three things this job is actually judged on, biggest first. */
    public java.util.List<String> workRecord(int topN) {
        return deeds.entrySet().stream()
            .filter(e -> e.getValue() > 0)
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .limit(topN)
            .map(e -> e.getValue() + " " + e.getKey().label)
            .toList();
    }

    /** The whole career tally as "ordinal:count,..." for the record screen.
     *  Sent as one string rather than a tracked field per deed — fourteen
     *  synched ints for a page you open now and then would be absurd. */
    private String deedCsv() {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<Deed, Integer> e : deeds.entrySet()) {
            if (e.getValue() <= 0) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey().ordinal()).append(':').append(e.getValue());
        }
        return sb.toString();
    }

    /** The patch's footprint as "minX,minZ,maxX,maxZ", for drawing it on a map. */
    private String zoneCsv() {
        if (workZone == null) return "";
        return workZone.min().getX() + "," + workZone.min().getZ() + ","
             + workZone.max().getX() + "," + workZone.max().getZ();
    }

    public void noteProduced(net.minecraft.world.item.Item item, int count) {
        if (count <= 0 || stationTask == StationTask.NONE) return;
        if (produced.size() > 24 && !produced.containsKey(item)) return; // keep it bounded
        produced.merge(item, count, Integer::sum);
    }

    /** Everything banked since dawn, as one number — the plot book sums these
     *  across a plot's workers for its yield line. */
    public int producedTodayTotal() {
        int total = 0;
        for (int v : produced.values()) total += v;
        return total;
    }

    /** "64 wheat, 18 raw iron, 3 diamond" — the biggest hauls first, or null. */
    @Nullable
    public String productionSummary(int topN) {
        if (produced.isEmpty()) return null;
        return produced.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .limit(topN)
            .map(e -> e.getValue() + " " + net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(e.getKey()).getPath().replace('_', ' '))
            .collect(java.util.stream.Collectors.joining(", "));
    }

    /** At each dawn, report the night's work and start a fresh tally. */
    private void dailyProductionReport() {
        long day = level().getDayTime() / 24000L;
        if (day == lastReportDay) return;
        if (lastReportDay >= 0) {
            String summary = productionSummary(4);
            if (summary != null) say("Yesterday's work: " + summary + ".");
        }
        lastReportDay = day;
        produced.clear();
    }

    // --------------------------------- modes ---------------------------------

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
        if (mode == Mode.STAY) {
            this.getNavigation().stop();
            this.setTarget(null);
        }
    }

    // ------------------------------- inventory -------------------------------

    public NonNullList<ItemStack> getInventoryItems() {
        return inventory;
    }

    /** Insert a stack, merging into existing piles first. Returns the leftover. */
    public ItemStack insertItem(ItemStack stack) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        ItemStack remaining = stack.copy();
        for (int i = 0; i < inventory.size() && !remaining.isEmpty(); i++) {
            ItemStack slot = inventory.get(i);
            if (!slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, remaining)) {
                int room = slot.getMaxStackSize() - slot.getCount();
                if (room > 0) {
                    int moved = Math.min(room, remaining.getCount());
                    slot.grow(moved);
                    remaining.shrink(moved);
                }
            }
        }
        for (int i = 0; i < inventory.size() && !remaining.isEmpty(); i++) {
            if (inventory.get(i).isEmpty()) {
                inventory.set(i, remaining);
                remaining = ItemStack.EMPTY;
            }
        }
        return remaining;
    }

    public int countItems() {
        int n = 0;
        for (ItemStack s : inventory) n += s.getCount();
        return n;
    }

    /** Count backpack items matching a predicate. */
    public int countMatching(java.util.function.Predicate<ItemStack> what) {
        int n = 0;
        for (ItemStack s : inventory) {
            if (!s.isEmpty() && what.test(s)) n += s.getCount();
        }
        return n;
    }

    /** Remove up to n matching items from the backpack. Returns removed count. */
    public int removeMatching(java.util.function.Predicate<ItemStack> what, int n) {
        int removed = 0;
        for (int i = 0; i < inventory.size() && removed < n; i++) {
            ItemStack s = inventory.get(i);
            if (s.isEmpty() || !what.test(s)) continue;
            int take = Math.min(n - removed, s.getCount());
            s.shrink(take);
            removed += take;
            if (s.isEmpty()) inventory.set(i, ItemStack.EMPTY);
        }
        return removed;
    }

    // --------------------------- tool intelligence ---------------------------

    /** Swap the best tool for this block into the main hand (player rules:
     *  axe for logs, pickaxe for stone — whatever digs fastest wins). */
    public void equipBestTool(BlockState state) {
        // Speed first — never downgrade a diamond pickaxe to an enchanted wooden
        // one — but among tools that dig this block at much the same rate, the
        // enchantment decides. Fortune on ore is free extra yield every swing,
        // and Silk Touch is the difference between a block and nothing at all
        // on the handful that simply shatter without it.
        boolean wantFortune = isOre(state);
        boolean wantSilk = shattersWithoutSilk(state);

        ItemStack held = this.getMainHandItem();
        float bestSpeed = held.getDestroySpeed(state);
        int bestBonus = toolBonus(held, wantFortune, wantSilk);
        int bestIdx = -1;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.get(i);
            if (s.isEmpty()) continue;
            float speed = s.getDestroySpeed(state);
            if (speed < bestSpeed * 0.9F) continue;          // meaningfully slower — no
            int bonus = toolBonus(s, wantFortune, wantSilk);
            boolean better = speed > bestSpeed + 0.01F
                ? bonus >= bestBonus                          // faster, and no worse enchanted
                : bonus > bestBonus;                          // same speed, better enchanted
            if (better) {
                bestSpeed = Math.max(bestSpeed, speed);
                bestBonus = bonus;
                bestIdx = i;
            }
        }
        if (bestIdx >= 0) {
            ItemStack old = this.getMainHandItem();
            this.setItemSlot(EquipmentSlot.MAINHAND, inventory.get(bestIdx));
            inventory.set(bestIdx, old);
        }
    }

    /** How much this tool's enchantments are worth for THIS block. */
    private int toolBonus(ItemStack tool, boolean wantFortune, boolean wantSilk) {
        if (tool.isEmpty()) return 0;
        int bonus = 0;
        if (wantSilk && enchantLevel(tool, net.minecraft.world.item.enchantment.Enchantments.SILK_TOUCH) > 0) {
            bonus += 10;
        }
        if (wantFortune) {
            // Silk Touch on ore is a downgrade for a working miner: one block
            // instead of several drops. Fortune is what we actually want.
            if (enchantLevel(tool, net.minecraft.world.item.enchantment.Enchantments.SILK_TOUCH) > 0) return -1;
            bonus += enchantLevel(tool, net.minecraft.world.item.enchantment.Enchantments.FORTUNE) * 3;
        }
        return bonus;
    }

    /** Enchantment level, looked up through the world's registry — enchantments
     *  are data-driven from 1.21, so there is no constant to compare against. */
    private int enchantLevel(ItemStack stack,
                             net.minecraft.resources.ResourceKey<net.minecraft.world.item.enchantment.Enchantment> key) {
        try {
            var registry = level().registryAccess()
                .registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
            return net.minecraft.world.item.enchantment.EnchantmentHelper
                .getItemEnchantmentLevel(registry.getHolderOrThrow(key), stack);
        } catch (RuntimeException e) {
            return 0;   // a pack without this enchantment is not a crash
        }
    }

    private static boolean isOre(BlockState state) {
        return state.is(net.neoforged.neoforge.common.Tags.Blocks.ORES);
    }

    /** Blocks that leave nothing behind unless the tool is silked. */
    private static boolean shattersWithoutSilk(BlockState state) {
        return state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE)
            || state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE) || state.is(Blocks.BLUE_ICE)
            || state.is(Blocks.SEA_LANTERN) || state.is(Blocks.GLOWSTONE);
    }

    /** Put a specific kind of tool in hand by item-id suffix ("_hoe", "_axe").
     *  Lets a job use — and wear out — the exact tool it asked the player for,
     *  even when that tool isn't the fastest thing in the pack. */
    public boolean equipToolNamed(String suffix) {
        if (net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(getMainHandItem().getItem()).getPath().endsWith(suffix)) {
            return true; // already holding one
        }
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.get(i);
            if (s.isEmpty()) continue;
            if (!net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(s.getItem()).getPath().endsWith(suffix)) continue;
            ItemStack old = this.getMainHandItem();
            this.setItemSlot(EquipmentSlot.MAINHAND, s);
            inventory.set(i, old);
            return true;
        }
        return false;
    }

    /** Ticks of work to break this block with the current tool (player-ish pacing). */
    /** Three seconds between actions for a raw recruit, before any bonus. A bot
     *  that works at machine speed does not read as a person doing a job; this
     *  is the number levels, branch, teamwork and food all pull against. */
    public static final int ACTION_PACE = 60;

    /**
     * The pace the held tool sets, in ticks between actions: six seconds for
     * wood down to three for netherite, the tiers spaced between. This is the
     * first reason to hand a specialist a better tool — the old code fed the
     * tool's dig speed into a floor of three seconds, and every tier from wood
     * up beat the floor, so a netherite pick worked at EXACTLY the pace of a
     * wooden one.
     *
     * <p>Trades whose tool has no tier — shears, a rod, a smelter's free hands
     * — keep the three-second base; there is no better tool to buy them.
     */
    public int toolPaceTicks() {
        if (getMainHandItem().getItem() instanceof net.minecraft.world.item.TieredItem tiered) {
            var tier = tiered.getTier();
            if (tier == net.minecraft.world.item.Tiers.WOOD) return 120;      // 6.0s
            if (tier == net.minecraft.world.item.Tiers.STONE) return 105;     // 5.25s
            if (tier == net.minecraft.world.item.Tiers.IRON) return 90;       // 4.5s
            if (tier == net.minecraft.world.item.Tiers.GOLD) return 80;       // fast metal, brittle tool
            if (tier == net.minecraft.world.item.Tiers.DIAMOND) return 75;    // 3.75s
            if (tier == net.minecraft.world.item.Tiers.NETHERITE) return 60;  // 3.0s
            // A modded tier: place it by its dig speed against the vanilla run.
            float sp = tier.getSpeed();
            return sp >= 9.0F ? 60 : sp >= 8.0F ? 75 : sp >= 6.0F ? 90 : sp >= 4.0F ? 105 : 120;
        }
        return ACTION_PACE;
    }

    public int workTicksFor(BlockState state) {
        float speed = Math.max(1.0F, this.getMainHandItem().getDestroySpeed(state));
        // The tier sets the pace; the dig-speed term only ever SLOWS a
        // mismatched tool further (bare hands on stone), never speeds it past
        // its tier.
        int base = Math.max(toolPaceTicks(), Math.round(48.0F / speed));
        // Veteran hands: 10% faster work at level 10, 20% at 20, 30% at 35 —
        // the cap. An edge you can feel, not a cheat.
        int bonus = veteranLevel() >= 35 ? 30 : (veteranLevel() >= 20 ? 20 : (veteranLevel() >= 10 ? 10 : 0));
        // A forester's axe work (and a husbandman's shears) come off the same
        // clock, so the branch discount lands here alongside the veteran rungs.
        bonus = Math.min(45, bonus + teamworkPercent() + traitWorkPercent());
        int ticks = base * (100 - bonus) / 100 * branchCooldownPercent() / 100;
        // Diet is a multiplier on TIME, not on the bonus: at 30% pace a job
        // takes three times as long, which is what "works at 30% speed" means.
        ticks = ticks * 100 / Math.max(20, dietPercent);
        return Math.max(12, ticks);   // never faster than about half a second
    }

    // --- pacing for everything that is not breaking a block ------------------
    // Shearing, breeding, loading a furnace, sorting a chest: all of it used to
    // happen the instant a goal decided to, which is what made a crew look like
    // a machine rather than a workforce.

    private int nextActionTick;

    /** Has this one finished its last action? */
    public boolean actionReady() {
        return tickCount >= nextActionTick;
    }

    /** Book the pause after doing something, at this bot's own pace. */
    public void noteAction() {
        noteAction(1.0F);
    }

    /** @param weight fraction of a full action — a light touch costs less. */
    public void noteAction(float weight) {
        nextActionTick = tickCount + Math.max(6, Math.round(actionPaceTicks() * weight));
    }

    /** What three seconds has become for this particular specialist, once its
     *  level, branch, crewmates, quirk and dinner are taken into account. */
    public int actionPaceTicks() {
        int bonus = veteranLevel() >= 35 ? 30 : (veteranLevel() >= 20 ? 20 : (veteranLevel() >= 10 ? 10 : 0));
        bonus = Math.min(45, bonus + teamworkPercent() + traitWorkPercent());
        // The tool's tier is the base the bonuses pull against: a fed veteran
        // with a wooden hoe is still slower than a recruit handed netherite.
        int ticks = toolPaceTicks() * (100 - bonus) / 100 * branchCooldownPercent() / 100;
        ticks = ticks * 100 / Math.max(20, dietPercent);
        return Math.max(12, ticks);
    }

    /** Wear the held tool by one use; announces when it breaks. */
    public void damageHeldTool() {
        ItemStack tool = this.getMainHandItem();
        if (tool.isEmpty() || !tool.isDamageableItem()) return;
        boolean nearlyDone = tool.getDamageValue() >= tool.getMaxDamage() - 2;
        tool.hurtAndBreak(1, this, EquipmentSlot.MAINHAND);
        if (nearlyDone && this.getMainHandItem().isEmpty()) {
            say("My tool just broke — I could craft a new one if I have materials (\"craft a stone pickaxe\").");
        }
    }

    // ------------------------- combat gear & ranged --------------------------

    public boolean hasBow() {
        return getMainHandItem().is(Items.BOW) || countMatching(s -> s.is(Items.BOW)) > 0;
    }

    public boolean hasArrows() {
        return countMatching(s -> s.is(Items.ARROW)) > 0;
    }

    /** Hostile monsters within radius (alive). */
    public int threatCount(double radius) {
        return level().getEntitiesOfClass(Monster.class,
            getBoundingBox().inflate(radius), Monster::isAlive).size();
    }

    /** Nearest alive hostile within radius, or null. */
    @Nullable
    public Monster nearestMonster(double radius) {
        Monster best = null;
        double bestSq = Double.MAX_VALUE;
        for (Monster m : level().getEntitiesOfClass(Monster.class,
                getBoundingBox().inflate(radius), Monster::isAlive)) {
            double d = distanceToSqr(m);
            if (d < bestSq) { bestSq = d; best = m; }
        }
        return best;
    }

    /**
     * Threat-aware judgment: is this a fight to break off rather than trade
     * blows? Fleeing a losing fight (then healing behind cover/light) beats
     * dying and dropping everything — the worst outcome for an autonomous agent.
     */
    public boolean shouldDisengage() {
        float hp = getHealth() / getMaxHealth();
        if (hp < 0.35F) return true;                     // critical — get out no matter what
        int threats = threatCount(10.0);
        if (threats == 0) return false;                  // nothing to disengage from
        if (hp < 0.55F && countFood() == 0) return true; // can't out-heal a brawl
        if (hp < 0.60F && threats >= 3 && getArmorValue() < 8) return true; // outnumbered & soft
        // It died here once. Whatever the reason, it is worth a wider margin the
        // second time round rather than walking into it again at half health.
        if (nearDeathSite() && hp < 0.75F) return true;
        return false;
    }

    @Nullable private BlockPos deathSite;   // where the last life ended
    private long deathGameTime;             // when — the map's marker fades on it

    /** Standing where it died before. */
    public boolean nearDeathSite() {
        return deathSite != null && deathSite.distSqr(blockPosition()) < 20.0 * 20.0;
    }

    /** The place it remembers dying, if it has one. */
    @Nullable public BlockPos deathSite() { return deathSite; }

    /** The map's skull: where this one last died, while the grief is fresh.
     *  Empty after a day — an old danger spot is just a spot. */
    private String deathField() {
        if (deathSite == null) return "";
        long age = level().getGameTime() - deathGameTime;
        if (age < 0 || age > 24000) return "";
        return deathSite.getX() + "," + deathSite.getY() + "," + deathSite.getZ() + "," + age;
    }

    private void equipBow() {
        if (getMainHandItem().is(Items.BOW)) return;
        for (int i = 0; i < inventory.size(); i++) {
            if (inventory.get(i).is(Items.BOW)) {
                ItemStack old = getMainHandItem();
                setItemSlot(EquipmentSlot.MAINHAND, inventory.get(i));
                inventory.set(i, old);
                return;
            }
        }
    }

    private static int weaponScore(ItemStack s) {
        if (s.isEmpty()) return 0;
        String path = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        int material = path.contains("netherite") ? 60 : path.contains("diamond") ? 50
            : path.contains("iron") ? 40 : path.contains("stone") ? 30
            : path.contains("golden") ? 20 : path.contains("wooden") ? 10 : 0;
        if (path.endsWith("_sword")) return material + 8;
        if (path.endsWith("_axe")) return material + 6;
        return 0;
    }

    public void equipBestWeapon() {
        int best = weaponScore(getMainHandItem());
        int bestIdx = -1;
        for (int i = 0; i < inventory.size(); i++) {
            int score = weaponScore(inventory.get(i));
            if (score > best) {
                best = score;
                bestIdx = i;
            }
        }
        if (bestIdx >= 0) {
            ItemStack old = getMainHandItem();
            setItemSlot(EquipmentSlot.MAINHAND, inventory.get(bestIdx));
            inventory.set(bestIdx, old);
        }
    }

    /** Pick the right weapon for the current fight. */
    private void combatTick() {
        LivingEntity t = getTarget();
        if (t == null || !t.isAlive()) return;
        boolean creeper = t instanceof Creeper;
        boolean far = distanceToSqr(t) > 49.0;
        boolean ranged = hasBow() && hasArrows();
        if ((creeper || far) && ranged) {
            equipBow();
        } else if (creeper) {
            setTarget(null); // bow broke / out of arrows — do not melee a creeper
        } else {
            equipBestWeapon();
        }
    }

    @Override
    public void performRangedAttack(LivingEntity target, float velocity) {
        if (removeMatching(s -> s.is(Items.ARROW), 1) < 1) return;
        Arrow arrow = new Arrow(level(), this, new ItemStack(Items.ARROW), getMainHandItem().copy());
        double dx = target.getX() - getX();
        double dy = target.getY(0.3333) - arrow.getY();
        double dz = target.getZ() - getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        arrow.shoot(dx, dy + horiz * 0.2, dz, 1.6F, 6.0F);
        this.playSound(net.minecraft.sounds.SoundEvents.SKELETON_SHOOT, 1.0F,
            1.0F / (getRandom().nextFloat() * 0.4F + 0.8F));
        level().addFreshEntity(arrow);
        ItemStack bow = getMainHandItem();
        if (bow.is(Items.BOW)) {
            bow.hurtAndBreak(1, this, EquipmentSlot.MAINHAND);
        }
    }

    /** Armor left in the linked chest is armor this one should be wearing —
     *  the same rule tools already follow. One piece per kit check, the old
     *  piece goes back in the chest in trade, and it says so, so a chestplate
     *  vanishing from storage is never a mystery. */
    private void armorFromChests() {
        for (ZoneChests.Found f : linkedChests()) {
            if (!f.stillThere()) continue;
            Container c = f.container();
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || !(s.getItem() instanceof ArmorItem candidate)) continue;
                EquipmentSlot slot = this.getEquipmentSlotForItem(s);
                if (slot != EquipmentSlot.HEAD && slot != EquipmentSlot.CHEST
                    && slot != EquipmentSlot.LEGS && slot != EquipmentSlot.FEET) continue;
                ItemStack worn = this.getItemBySlot(slot);
                int wornDefense = worn.getItem() instanceof ArmorItem w ? w.getDefense() : -1;
                if (candidate.getDefense() <= wornDefense) continue;
                ItemStack take = s.copy();
                c.setItem(i, worn);
                c.setChanged();
                this.setItemSlot(slot, take);
                playSound(candidate.getEquipSound().value(), 1.0F, 1.0F);
                swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                sayRoutine("Kitted up — took the " + take.getHoverName().getString() + " from the chest.");
                return;
            }
        }
    }

    /** Wear the best armor in the pack, piece by piece (like tools, for the body). */
    private void autoEquipArmor() {
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.get(i);
            if (s.isEmpty() || !(s.getItem() instanceof ArmorItem candidate)) continue;
            EquipmentSlot slot = this.getEquipmentSlotForItem(s);
            if (slot != EquipmentSlot.HEAD && slot != EquipmentSlot.CHEST
                && slot != EquipmentSlot.LEGS && slot != EquipmentSlot.FEET) continue;
            ItemStack current = this.getItemBySlot(slot);
            int currentDefense = current.getItem() instanceof ArmorItem worn ? worn.getDefense() : -1;
            if (current.isEmpty() || candidate.getDefense() > currentDefense) {
                this.setItemSlot(slot, s);
                inventory.set(i, current);
            }
        }
    }

    // --------------------------- companion awareness -------------------------

    /** A guard with a shield uses it like a person would: raised while the
     *  threat is closing or shooting, dropped to swing when it's in reach.
     *  Vanilla's own blocking rules do the damage math — this only decides
     *  when the arm goes up. */
    private void tendShield() {
        if (stationTask != StationTask.GUARD) return;
        ItemStack off = getItemBySlot(EquipmentSlot.OFFHAND);
        if (!off.is(Items.SHIELD)) {
            if (!off.isEmpty()) return;              // off-hand busy with something else
            int slot = firstSlot(s -> s.is(Items.SHIELD));
            if (slot < 0) return;
            setItemSlot(EquipmentSlot.OFFHAND, inventory.get(slot));
            inventory.set(slot, ItemStack.EMPTY);
            sayRoutine("Shield up.");
        }
        LivingEntity t = getTarget();
        boolean raise = t != null && t.isAlive() && distanceToSqr(t) > 9.0;
        if (raise && !isUsingItem()) {
            startUsingItem(InteractionHand.OFF_HAND);
        } else if (!raise && isUsingItem() && getUsedItemHand() == InteractionHand.OFF_HAND) {
            stopUsingItem();
        }
    }

    /** Warn the owner about hostiles closing in that they might not see. */
    private void dangerCallouts() {
        Player owner = getOwnerPlayer();
        if (!(owner instanceof ServerPlayer) || distanceToSqr(owner) > 48.0 * 48.0) return;
        var creepers = level().getEntitiesOfClass(Creeper.class,
            owner.getBoundingBox().inflate(7.0), LivingEntity::isAlive);
        if (!creepers.isEmpty() && tickCount - lastWarnTick > 120) {
            lastWarnTick = tickCount;
            say("CREEPER " + directionFrom(owner, creepers.get(0)) + " of you — move!");
            return;
        }
        if (tickCount - lastWarnTick > 400) {
            Monster nearest = null;
            double best = Double.MAX_VALUE;
            for (Monster m : level().getEntitiesOfClass(Monster.class,
                    owner.getBoundingBox().inflate(12.0), LivingEntity::isAlive)) {
                double d = m.distanceToSqr(owner);
                if (d < best) { best = d; nearest = m; }
            }
            if (nearest != null) {
                lastWarnTick = tickCount;
                say("Heads up — " + nearest.getName().getString() + " "
                    + directionFrom(owner, nearest) + " of you.");
            }
        }
    }

    private static String directionFrom(net.minecraft.world.entity.Entity from, net.minecraft.world.entity.Entity to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double ax = Math.abs(dx);
        double az = Math.abs(dz);
        String ew = dx > 0 ? "east" : "west";
        String ns = dz > 0 ? "south" : "north";
        if (ax > 2 * az) return ew;
        if (az > 2 * ax) return ns;
        return ns + ew;
    }

    /** Totem beats shield beats nothing in the off-hand. */
    private void manageOffhand() {
        ItemStack off = getItemBySlot(EquipmentSlot.OFFHAND);
        if (off.is(Items.TOTEM_OF_UNDYING)) return;
        int totem = slotWith(s -> s.is(Items.TOTEM_OF_UNDYING));
        if (totem >= 0) {
            ItemStack old = off;
            setItemSlot(EquipmentSlot.OFFHAND, inventory.get(totem));
            inventory.set(totem, old);
            return;
        }
        if (off.isEmpty()) {
            int shield = slotWith(s -> s.is(Items.SHIELD));
            if (shield >= 0) {
                setItemSlot(EquipmentSlot.OFFHAND, inventory.get(shield));
                inventory.set(shield, ItemStack.EMPTY);
            }
        }
    }

    private int slotWith(java.util.function.Predicate<ItemStack> what) {
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.get(i);
            if (!s.isEmpty() && what.test(s)) return i;
        }
        return -1;
    }

    public boolean isNightHome() {
        return nightHome;
    }

    public void setNightHome(boolean nightHome) {
        this.nightHome = nightHome;
    }

    /** At dusk, an idle worker with the toggle on walks home and holds. */
    private void nightRoutine() {
        if (!this.level().isNight()) {
            wentHomeTonight = false;
            // Morning: un-park a bot the routine sent home for the night, so it
            // resumes autonomous work at dawn instead of holding at home forever.
            if (parkedForNight) {
                parkedForNight = false;
                if (autonomous && mode == Mode.STAY) {
                    mode = Mode.FOLLOW;
                    say("Morning — back to it.");
                }
            }
            return;
        }
        if (!nightHome || wentHomeTonight || homePos == null || retreating) return;
        if (!jobs.isEmpty()) return; // explicit orders outrank bedtime
        if (homePos.distSqr(blockPosition()) < 16 * 16) return;
        wentHomeTonight = true;
        parkedForNight = true;
        say("Sun's down — heading home for the night.");
        goHome();
    }

    /** Drop a torch at our feet when working somewhere dark. */
    private void torchIfDark() {
        BlockPos pos = blockPosition();
        if (level().getMaxLocalRawBrightness(pos) >= 6) return;
        if (!level().getBlockState(pos).canBeReplaced()) return;
        if (!level().getBlockState(pos.below()).isFaceSturdy(level(), pos.below(), net.minecraft.core.Direction.UP)) return;
        if (removeMatching(s -> s.is(Items.TORCH), 1) == 1) {
            level().setBlockAndUpdate(pos, Blocks.TORCH.defaultBlockState());
        }
    }

    // ------------------------------- waypoints --------------------------------

    public void setWaypoint(String name, BlockPos pos) {
        waypoints.put(name.toLowerCase().trim(), pos.immutable());
    }

    @Nullable
    public BlockPos getWaypoint(String name) {
        return waypoints.get(name.toLowerCase().trim());
    }

    public List<String> waypointNames() {
        return new ArrayList<>(waypoints.keySet());
    }

    public void removeWaypoint(String name) {
        waypoints.remove(name.toLowerCase().trim());
    }

    // -------------------------------- experience ------------------------------

    public int getXp() {
        return xp;
    }

    public void awardXp(int amount) {
        if (amount <= 0) return;
        int before = veteranLevel();
        this.xp = Math.min(100000, this.xp + amount);
        this.lifetimeXp = Math.min(1_000_000, this.lifetimeXp + amount);
        int after = veteranLevel();
        if (after > before) {
            applyLevelPerks();
            lastShownHealth = -1; // refresh the nametag with the new star
            if (after % 5 == 0) { // announce milestones, not every level
                String perk = switch (after) {
                    case 10 -> " I work 10% faster now.";
                    case 20 -> " +2 hearts, and 20% faster work.";
                    case 30 -> " 20% quicker on my feet now.";
                    case 35 -> " 30% faster work — as good as I'll get.";
                    default -> "";
                };
                say("Level " + after + "!" + perk);
            }
        }
    }

    /**
     * A specialist picks a branch at level 20 — the point where it has clearly
     * settled into the work. Each branch deepens what that job is already for
     * rather than handing out generic stats.
     */
    public enum Branch {
        NONE("no speciality", "—"),
        // Farmer
        IRRIGATION("irrigation", "works a bigger plot"),
        HUSBANDRY("husbandry", "breeds and shears faster"),
        // Miner / lumberjack
        PROSPECTOR("prospecting", "spots veins further off"),
        FORESTER("forestry", "fells and replants faster"),
        // Guard / hauler
        SENTINEL("sentinel", "hits harder, watches wider"),
        PORTER("porterage", "carries more per trip");

        public final String label, blurb;
        Branch(String label, String blurb) { this.label = label; this.blurb = blurb; }

        /** The two branches on offer for a given job. */
        public static Branch[] optionsFor(StationTask task) {
            return switch (task) {
                case FARM -> new Branch[]{ IRRIGATION, HUSBANDRY };
                case RANCH -> new Branch[]{ HUSBANDRY, IRRIGATION };
                case MINE -> new Branch[]{ PROSPECTOR, PORTER };
                case WOOD -> new Branch[]{ FORESTER, PORTER };
                case GUARD -> new Branch[]{ SENTINEL, PORTER };
                case HAUL, STORE -> new Branch[]{ PORTER, SENTINEL };
                case SMELT, FISH -> new Branch[]{ PORTER, PROSPECTOR };
                case NONE -> new Branch[]{};
            };
        }
    }

    private Branch branch = Branch.NONE;
    @Nullable private String deathNote;   // how the last life ended, carried by the core
    private long firstServedDay = -1;   // the day it started work, for loyalty

    public Branch branch() { return branch; }

    /** Available once it has settled into the work at level 20. */
    public boolean canChooseBranch() {
        return veteranLevel() >= 20 && stationTask != StationTask.NONE;
    }

    public void cycleBranch() {
        Branch[] options = Branch.optionsFor(stationTask);
        if (options.length == 0 || !canChooseBranch()) {
            say("I need to be a level 20 specialist before I can specialise further.");
            return;
        }
        int at = -1;
        for (int i = 0; i < options.length; i++) if (options[i] == branch) at = i;
        branch = options[(at + 1) % options.length];
        applyLevelPerks();
        refreshJobState();
        say("I'll focus on " + branch.label + " — " + branch.blurb + ".");
    }

    /** Days of service, which is what loyalty is actually measured in. */
    public int daysServed() {
        if (firstServedDay < 0) return 0;
        return (int) Math.max(0, level().getDayTime() / 24000L - firstServedDay);
    }

    /** Small, slow, permanent: +1 heart per week served, capped at +4.
     *  An old hand should be quietly better than a fresh hire. */
    public int loyaltyHearts() {
        if (!com.jrpetty.mcassistant.AssistantConfig.loyaltyEnabled()) return 0;
        return Math.min(4, daysServed() / 7);
    }

    /** Veteran level from LIFETIME xp — spending xp at the enchanting table
     *  never lowers it. Deliberately SLOW: level = sqrt(lifetimeXp / 10),
     *  capped at 50 (L10 = 1000 xp, L20 = 4000, L30 = 9000, L35 = 12250) —
     *  a true veteran is weeks of work, not an afternoon. */
    public int veteranLevel() {
        return Math.min(50, (int) Math.floor(Math.sqrt(
            lifetimeXp / (double) Math.max(1, com.jrpetty.mcassistant.AssistantConfig.levelCurveFactor()))));
    }

    /** Perks with teeth but a ceiling: +2 hearts at level 20 and 20% faster
     *  movement at 30 (attribute modifiers, re-applied idempotently); the
     *  work-speed rungs (10/20/30%) live in workTicksFor. */
    private static final net.minecraft.resources.ResourceLocation BRANCH_DMG_ID =
        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mc_assistant", "branch_damage");

    private void applyLevelPerks() {
        var dmg = getAttribute(Attributes.ATTACK_DAMAGE);
        if (dmg != null) {
            dmg.removeModifier(BRANCH_DMG_ID);
            if (branch == Branch.SENTINEL) {
                dmg.addPermanentModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                    BRANCH_DMG_ID, 2.0,
                    net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE));
            }
        }
        var hp = getAttribute(Attributes.MAX_HEALTH);
        if (hp != null) {
            hp.removeModifier(VETERAN_HP_ID);
            double hearts = (veteranLevel() >= 20 ? 4.0 : 0.0) + loyaltyHearts() * 2.0
                + (trait == Trait.STURDY ? 2.0 : 0.0);
            if (hearts > 0) {
                hp.addPermanentModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                    VETERAN_HP_ID, hearts,
                    net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE));
            }
        }
        var speed = getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.removeModifier(VETERAN_SPEED_ID);
            if (veteranLevel() >= 30 && perk30 == Perk.SWIFT) {
                speed.addPermanentModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                    VETERAN_SPEED_ID, 0.20,
                    net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            }
        }
        var armor = getAttribute(Attributes.ARMOR);
        if (armor != null) {
            armor.removeModifier(PERK_ARMOR_ID);
            if (veteranLevel() >= 30 && perk30 == Perk.TOUGH) {
                armor.addPermanentModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                    PERK_ARMOR_ID, 4.0,
                    net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE));
            }
        }
    }

    private static final net.minecraft.resources.ResourceLocation VETERAN_HP_ID =
        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mc_assistant", "veteran_hearts");
    private static final net.minecraft.resources.ResourceLocation VETERAN_SPEED_ID =
        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mc_assistant", "veteran_speed");
    private static final net.minecraft.resources.ResourceLocation PERK_ARMOR_ID =
        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mc_assistant", "perk_armor");

    /** Spend up to `amount` XP; returns true if it could be paid in full. */
    public boolean spendXp(int amount) {
        if (xp < amount) return false;
        xp -= amount;
        return true;
    }

    // ---------------------------- nether expedition ---------------------------

    public boolean expeditionActive() { return expeditionActive; }
    public int expeditionPhase() { return expeditionPhase; }
    public String expeditionTarget() { return expeditionTarget; }
    public int expeditionRemaining() { return expeditionRemaining; }
    @Nullable public BlockPos expeditionReturn() { return expeditionReturn; }

    public void beginExpedition(String target, int amount, BlockPos returnPos) {
        this.expeditionActive = true;
        this.expeditionPhase = 1;
        this.expeditionTarget = target;
        this.expeditionRemaining = amount;
        this.expeditionReturn = returnPos.immutable();
    }

    public void setExpeditionPhase(int phase) { this.expeditionPhase = phase; }
    public void setExpeditionRemaining(int n) { this.expeditionRemaining = n; }
    public void endExpedition() {
        this.expeditionActive = false;
        this.expeditionPhase = 0;
        this.expeditionRemaining = 0;
        this.expeditionReturn = null;
    }

    // ------------------------------ storage totals ----------------------------

    /** Total count of matching items across the pack AND every remembered,
     *  still-loaded chest. Powers "how much iron do we have?" */
    public int countAcrossStorage(java.util.function.Predicate<ItemStack> what) {
        int total = countMatching(what);
        java.util.Set<Long> counted = new java.util.HashSet<>();
        // Every chest we remember (any distance, as long as it's loaded)...
        for (Long key : chestMemory.keySet()) {
            BlockPos pos = BlockPos.of(key);
            if (level().getBlockEntity(pos) instanceof Container c && counted.add(key)) {
                total += countIn(c, what);
            }
        }
        // ...plus a live scan of chests physically nearby it may never have opened
        // (so "how much iron do we have?" sees the chest right next to it).
        for (ZoneChests.Found found : ZoneChests.around(level(), blockPosition(), 16, 4)) {
            if (!found.stillThere() || !counted.add(found.pos().asLong())) continue;
            Container c = found.container();
            total += countIn(c, what);
            rememberChest(found.pos(), c); // learn it for next time
        }
        return total;
    }

    private static int countIn(Container c, java.util.function.Predicate<ItemStack> what) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (!s.isEmpty() && what.test(s)) n += s.getCount();
        }
        return n;
    }

    /** All remembered chest positions (for the sort goal). */
    public java.util.List<BlockPos> rememberedChests() {
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        for (Long key : chestMemory.keySet()) out.add(BlockPos.of(key));
        return out;
    }

    /** Every container within `radius` (live scan) — for the craft planner. */
    public java.util.List<Container> nearbyContainers(int radius) {
        java.util.List<Container> out = new java.util.ArrayList<>();
        for (ZoneChests.Found found : ZoneChests.around(level(), blockPosition(), radius, 4)) {
            if (found.stillThere()) out.add(found.container());
        }
        return out;
    }

    // --------------------------- self-assessment ------------------------------

    /** The autonomy perception layer: a prioritized read-out of what this
     *  assistant needs right now. Foundation of the survival loop — it can
     *  say these before deciding what to do about them. */
    public java.util.List<String> assessNeeds() {
        java.util.List<String> needs = new java.util.ArrayList<>();
        if (getHealth() < getMaxHealth() * 0.4F) {
            needs.add("I'm hurt (" + (int) getHealth() + "/" + (int) getMaxHealth() + ")");
        }
        if (countFood() == 0) {
            needs.add("I'm out of food");
        }
        ItemStack tool = getMainHandItem();
        if (tool.isDamageableItem() && tool.getMaxDamage() > 0
            && tool.getDamageValue() > tool.getMaxDamage() * 0.9) {
            needs.add("my " + tool.getHoverName().getString() + " is nearly broken");
        }
        if (isPackFull()) {
            needs.add("my pack is full — I should deposit");
        }
        boolean hasPick = countMatching(s -> {
            String p = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
            return p.endsWith("_pickaxe");
        }) > 0 || getMainHandItem().getDestroySpeed(Blocks.STONE.defaultBlockState()) > 1.5F;
        if (!hasPick) {
            needs.add("I have no pickaxe — I can't mine stone or ore");
        }
        if (!standingOrders.isEmpty()) {
            needs.add(standingOrders.size() + " standing order" + (standingOrders.size() == 1 ? "" : "s") + " to keep up");
        }
        if (needs.isEmpty()) {
            needs.add("nothing pressing — I'm in good shape");
        }
        return needs;
    }

    /**
     * The 'decide' rung of autonomy. When idle, the assistant looks after
     * itself before doing any busywork — stash a full pack, get food, keep a
     * working pickaxe, replace a worn-out tool, keep wood on hand. It picks the
     * single most pressing action, announces it, and queues it; returns true if
     * it took initiative this cycle.
     *
     * This is the heart of the end goal: dropped into the world with autonomy
     * on, it keeps itself fed, armed, and alive without being told.
     */
    private boolean decideSurvival() {
        // 1) Pack full — stash so it can keep working (only if a chest exists).
        if (isPackFull() && findChestWith(s -> true, 24) != null) {
            say("Pack's full — stashing before I carry on.");
            enqueue(Job.deposit());
            return true;
        }
        // 2) Keep a food buffer — never let the larder run dry (it eats to heal,
        //    and the quartermaster shares it with the owner).
        if (countFood() < 6 && huntablePreyNearby()) {
            say(countFood() == 0 ? "I'm out of food — hunting something to eat."
                                 : "Food's running low — topping up the larder.");
            enqueue(Job.hunt(null, 4));
            return true;
        }
        // (No prey in range? Don't loop a doomed hunt — fall through so
        //  decideProgress can farm/breed renewable food instead of freezing here.)

        // 2b) A workbench — tools and the furnace all need the 3x3 grid, and a
        //     crafting table is craftable from 4 planks with NO table (2x2). Make
        //     sure one exists before any 3x3 craft so a from-scratch bot can
        //     actually forge its first pickaxe.
        boolean woodAccess = countMatching(s -> s.is(ItemTags.LOGS)) > 0
            || countMatching(s -> s.is(ItemTags.PLANKS)) > 0
            || resourceNearby(GatherGoal.Kind.LOGS, 16);
        if (!craftingTableNearby() && countCarried(s -> s.is(Items.CRAFTING_TABLE)) == 0 && woodAccess) {
            CraftPlanner.Result plan = CraftPlanner.plan(this, "crafting_table", 1);
            if (!plan.jobs().isEmpty()) {
                say("Making a crafting table so I can build tools.");
                for (Job j : plan.jobs()) enqueue(j);
                return true;
            }
        }
        // 3) No pickaxe — it can't mine stone or ore at all; make one, sourcing
        //    the wood/stone itself via the planner. (countCarried so a pickaxe
        //    currently in the main hand still counts.)
        if (countCarried(AssistantEntity::isPickaxe) == 0) {
            String target = countMatching(s -> s.is(net.minecraft.world.item.Items.COBBLESTONE)
                || s.is(net.minecraft.world.item.Items.COBBLED_DEEPSLATE)) >= 3
                ? "stone_pickaxe" : "wooden_pickaxe";
            CraftPlanner.Result plan = CraftPlanner.plan(this, target, 1);
            if (!plan.jobs().isEmpty()) {
                say("I need a pickaxe — " + String.join(", ", plan.narration()) + ".");
                for (Job j : plan.jobs()) enqueue(j);
                return true;
            }
        }
        // 4) Main tool nearly broken — replace it before it snaps.
        ItemStack tool = getMainHandItem();
        if (tool.isDamageableItem() && tool.getMaxDamage() > 0
            && tool.getDamageValue() > tool.getMaxDamage() * 0.9) {
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(tool.getItem()).getPath();
            CraftPlanner.Result plan = CraftPlanner.plan(this, id, 1);
            if (!plan.jobs().isEmpty()) {
                say("My " + tool.getHoverName().getString() + " is nearly broken — making a fresh one.");
                for (Job j : plan.jobs()) enqueue(j);
                return true;
            }
        }
        // 5) Keep wood on hand — it's the root of the tech tree and self-repair.
        if (countMatching(s -> s.is(ItemTags.LOGS)) == 0
            && countMatching(s -> s.is(ItemTags.PLANKS)) < 4
            && resourceNearby(GatherGoal.Kind.LOGS, 16)) {
            say("Low on wood — getting some.");
            enqueue(Job.gather(GatherGoal.Kind.LOGS, 12));
            return true;
        }
        // 6) Shelter kit — keep full blocks on hand so it can wall up when night
        //    falls. Grab cheap dirt during the day when it has no home to run to
        //    (only if there's actually dirt to dig — no spinning on bare stone).
        if (!this.level().isNight() && homePos == null
            && countMatching(com.jrpetty.mcassistant.entity.goal.NightShelterGoal.SHELTER_BLOCK) < 8
            && resourceNearby(GatherGoal.Kind.DIRT, 16)) {
            say("Grabbing some dirt for an emergency shelter, just in case.");
            enqueue(Job.gather(GatherGoal.Kind.DIRT, 12));
            return true;
        }
        return false;
    }

    private static boolean isPickaxe(ItemStack s) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM
            .getKey(s.getItem()).getPath().endsWith("_pickaxe");
    }

    /** Count matching items across the backpack AND worn/held equipment. */
    /** How much of something the whole STATION holds — the pack plus every
     *  linked chest. This is the farmer's view of "what do we grow here":
     *  the stock decides the field, not whatever happens to be in hand. */
    public int countStocked(java.util.function.Predicate<ItemStack> what) {
        int total = countCarried(what);
        for (ZoneChests.Found f : linkedChests()) {
            if (!f.stillThere()) continue;
            Container c = f.container();
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack stack = c.getItem(i);
                if (!stack.isEmpty() && what.test(stack)) total += stack.getCount();
            }
        }
        return total;
    }

    public int countCarried(java.util.function.Predicate<ItemStack> what) {
        int n = countMatching(what);
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack s = getItemBySlot(slot);
            if (!s.isEmpty() && what.test(s)) n += s.getCount();
        }
        return n;
    }

    /**
     * The 'thrive' rung. With survival covered and time to spare, it climbs the
     * tech tree by the single highest-value next step, and only reaches for the
     * next tier once the current one is stable:
     *   stone-stable  — a stone pickaxe, a furnace, torches for light
     *   iron-safe     — iron armor (auto-worn), sword, shield, iron pickaxe,
     *                   mined + smelted + forged, piece by piece
     *   food-secure   — tend a nearby farm / breed animals for renewable food
     * Returns true if it took a step. This is what turns "survives" into
     * "thrives" — the assistant materially improves itself over time.
     */
    private boolean decideProgress() {
        // --- Stone-stable ---
        if (bestPickTier() < 2) {
            CraftPlanner.Result plan = CraftPlanner.plan(this, "stone_pickaxe", 1);
            if (!plan.jobs().isEmpty()) { announcePlan("Upgrading to stone tools", plan); return true; }
        }
        if (!furnaceNearby() && countCarried(s -> s.is(Items.FURNACE)) == 0) {
            CraftPlanner.Result plan = CraftPlanner.plan(this, "furnace", 1);
            if (!plan.jobs().isEmpty()) { announcePlan("Setting up a furnace", plan); return true; }
        }
        // --- Home base: a solo bot claims one spot as home, with a storage chest,
        //     so it has somewhere to return at night and stash surplus (which also
        //     ends "pack full, nowhere to deposit" stalls). Done once. ---
        boolean solo = ownerId == null || Town.center(ownerId) == null;
        if (solo && homePos == null && onGround()) {
            if (findAnyChest(10) != null) {
                setHome(blockPosition());
                setNightHome(true);
                say("Setting up base by this chest — I'll stash here and head home at night.");
                return true;
            }
            if (countCarried(s -> s.is(Items.CHEST)) > 0) {
                if (placeChestNearby()) {
                    setHome(blockPosition());
                    setNightHome(true);
                    say("Base set — dropped a storage chest here, and I'll head home at night.");
                    return true;
                }
            } else {
                CraftPlanner.Result plan = CraftPlanner.plan(this, "chest", 1);
                if (!plan.jobs().isEmpty()) { announcePlan("Making a storage chest to set up base", plan); return true; }
            }
        }
        boolean coalObtainable = countCarried(s -> s.is(Items.COAL) || s.is(Items.CHARCOAL)) > 0
            || resourceNearby(GatherGoal.Kind.COAL, 16);
        if (bestPickTier() >= 2 && countCarried(s -> s.is(Items.TORCH)) < 8 && coalObtainable) {
            CraftPlanner.Result plan = CraftPlanner.plan(this, "torch", 8);
            if (!plan.jobs().isEmpty()) { announcePlan("Making torches for light", plan); return true; }
        }
        // (No coal to be had here? Skip torches rather than looping on them — go
        //  straight for iron, which matters more and lights its own mine.)
        // --- Iron-safe: the biggest survivability jump, built piece by piece. ---
        String piece = nextIronPiece();
        if (piece != null && bestPickTier() >= 2) {
            int ingots = countCarried(s -> s.is(Items.IRON_INGOT));
            int raw = countCarried(s -> s.is(Items.RAW_IRON));
            if (ingots >= ironCost(piece)) {
                CraftPlanner.Result plan = CraftPlanner.plan(this, piece, 1);
                if (!plan.jobs().isEmpty()) { announcePlan("Forging " + CraftPlanner.pretty(piece), plan); return true; }
            } else if (raw > 0) {
                say("Smelting raw iron for my gear.");
                enqueue(Job.smelt("iron", raw));
                return true;
            } else {
                say("Heading down to mine iron for armor and tools.");
                enqueue(Job.mine(40));
                return true;
            }
        }
        // --- Irrigation kit: with iron to spare and a farm about to go in with no
        //     natural water in reach, forge a bucket so the plot can be hydrated
        //     (FarmGoal carves a contained source from it). Also a lava/fire tool. ---
        if (piece == null && bestPickTier() >= 2
                && countCarried(s -> s.is(Items.BUCKET) || s.is(Items.WATER_BUCKET)) == 0
                && countCarried(s -> s.is(Items.IRON_INGOT)) >= 3
                && (hasSeeds() || grassNearby()) && !waterNearby(10)) {
            CraftPlanner.Result plan = CraftPlanner.plan(this, "bucket", 1);
            if (!plan.jobs().isEmpty() && plan.blockers().isEmpty()) { announcePlan("Forging a bucket to irrigate a farm", plan); return true; }
        }
        // --- Food-secure: renewable food. Harvest ripe crops; else bootstrap a
        //     farm from scratch (FarmGoal tills + plants); else breed a herd. ---
        if (matureCropsNearby()) { say("Harvesting the crops."); enqueue(Job.farm()); return true; }
        // Same rule as a stationed farmer: room to plant, not absence of crops.
        int plot = surveyFarm();
        boolean plotRoom = (plot & (FARM_PLANTABLE | FARM_TILLABLE)) != 0;
        if ((hasSeeds() && plotRoom) || (!hasSeeds() && (plot & FARM_GRASS) != 0)) {
            say("Setting up a small farm for steady food.");
            enqueue(Job.farm());
            return true;
        }
        if (animalsNearby() && countCarried(BREEDING_FOOD) >= 4) {
            say("Breeding the animals for a steady food supply.");
            enqueue(Job.breed(null, 2));
            return true;
        }
        // Bake bread from harvested wheat — better, keeps the larder up.
        if (countMatching(s -> s.is(Items.WHEAT)) >= 3 && countFood() < 12) {
            CraftPlanner.Result plan = CraftPlanner.plan(this, "bread", 1);
            if (!plan.jobs().isEmpty() && plan.blockers().isEmpty()) { announcePlan("Baking bread", plan); return true; }
        }
        // --- Diamond tier: only once fully iron-safe (deep mining is riskiest). ---
        if (piece == null && bestPickTier() >= 3 && countFood() > 0) {
            String gem = nextDiamondPiece();
            if (gem != null) {
                if (countCarried(s -> s.is(Items.DIAMOND)) >= diamondCost(gem)) {
                    CraftPlanner.Result plan = CraftPlanner.plan(this, gem, 1);
                    if (!plan.jobs().isEmpty()) { announcePlan("Forging " + CraftPlanner.pretty(gem), plan); return true; }
                } else {
                    say("Fully kitted in iron — digging deep for diamonds now.");
                    enqueue(Job.mine(-54));
                    return true;
                }
            }
        }
        // --- Enchanting: multiply the gear we've got (needs a table + lapis + XP). ---
        if (enchantingReady()) {
            say("Enchanting my gear at the table.");
            enqueue(Job.enchant("gear"));
            return true;
        }
        // --- Ranged kit: a bow once it has string (e.g. from spiders it fights),
        //     then a quiver of arrows if the parts are on hand. ---
        if (countCarried(s -> s.is(Items.BOW)) == 0 && countMatching(s -> s.is(Items.STRING)) >= 3) {
            CraftPlanner.Result plan = CraftPlanner.plan(this, "bow", 1);
            if (!plan.jobs().isEmpty() && plan.blockers().isEmpty()) { announcePlan("Crafting a bow for range", plan); return true; }
        }
        if (countCarried(s -> s.is(Items.BOW)) > 0 && countMatching(s -> s.is(Items.ARROW)) < 16) {
            CraftPlanner.Result plan = CraftPlanner.plan(this, "arrow", 16);
            if (!plan.jobs().isEmpty() && plan.blockers().isEmpty()) { announcePlan("Fletching arrows", plan); return true; }
        }
        // --- Grow a real homestead over time: house -> storage -> smeltery ->
        //     animal pen -> farm -> perimeter wall -> lighthouse. One building per
        //     pass, only once it has (or gathers/crafts) the materials. ---
        if (solo && homePos != null && homesteadStep()) return true;
        return false;
    }

    /** One step of building out the homestead around home. Returns true if it took
     *  an action (walked home, gathered/crafted materials, or placed a building).
     *  Advances baseStage building-by-building until the compound is complete. */
    private boolean homesteadStep() {
        if (homePos == null || baseStage > 6) return false;
        // Work on the compound from home — head back if we've wandered off.
        if (homePos.distSqr(blockPosition()) > 144) {
            getNavigation().moveTo(homePos.getX() + 0.5, homePos.getY(), homePos.getZ() + 0.5, 1.1D);
            return true;
        }
        BlockPos h = homePos;
        Direction f = Direction.NORTH; // fixed layout so buildings tile predictably
        // Block counts are set ABOVE each blueprint's real needs on purpose: the
        // builder only hard-stops when it has zero blocks, so under-gating would
        // leave a half-built structure. Over-gating just gathers a little spare.
        //
        // Compound plan (facing NORTH → every door opens SOUTH): house at home
        // with storage east(6) and smeltery west(6) — all inside the radius-9
        // wall with a one-block gap to it. The pen sits OUTSIDE the wall to the
        // south (gate facing the compound doorway, which is also south-center),
        // and the lighthouse rises just outside to the north. Nothing overlaps.
        return switch (baseStage) {
            case 0 -> buildStage("house", h, f, 0, 120, new ItemNeed[]{
                        new ItemNeed("crafting_table", 1), new ItemNeed("furnace", 1), new ItemNeed("chest", 1) });
            case 1 -> buildStage("storage", h.east(6), f, 0, 80, new ItemNeed[]{
                        new ItemNeed("chest", 4) });
            case 2 -> buildStage("smeltery", h.west(6), f, 0, 80, new ItemNeed[]{
                        new ItemNeed("furnace", 3), new ItemNeed("chest", 2), new ItemNeed("crafting_table", 1) });
            case 3 -> buildStage("pen", h.south(13).east(6), Direction.SOUTH, 0, 0, new ItemNeed[]{
                        new ItemNeed("oak_fence", 24), new ItemNeed("oak_fence_gate", 1) });
            case 4 -> {
                say("Homestead's coming along — laying in a farm for steady food.");
                enqueue(Job.farm());
                baseStage++;
                yield true;
            }
            case 5 -> buildStage("fortify", h, f, 9, 220, new ItemNeed[]{});
            case 6 -> buildStage("lighthouse", h.north(12), f, 0, 100, new ItemNeed[]{
                        new ItemNeed("ladder", 11) });
            default -> false;
        };
    }

    /** A functional item a building needs before it can go up (a recipe key + count). */
    private record ItemNeed(String recipeKey, int count) {}

    /** Make sure the functional items and building blocks for a homestead building
     *  are on hand (crafting/gathering the shortfall first), then place it. */
    private boolean buildStage(String structure, BlockPos anchor, Direction facing,
                               int radius, int blocksNeeded, ItemNeed[] items) {
        for (ItemNeed need : items) {
            net.minecraft.world.item.Item it = RecipeBook.item(need.recipeKey());
            int have = it == Items.AIR ? 0 : countCarried(s -> s.is(it));
            if (have < need.count()) {
                CraftPlanner.Result plan = CraftPlanner.plan(this, need.recipeKey(), need.count() - have);
                if (!plan.jobs().isEmpty() && plan.blockers().isEmpty()) {
                    announcePlan("Making " + (need.count() - have) + " "
                        + CraftPlanner.pretty(need.recipeKey()) + " for the " + structure, plan);
                    return true;
                }
                return false; // can't get it right now — retry a later cycle, don't loop-build
            }
        }
        if (blocksNeeded > 0
            && countMatching(com.jrpetty.mcassistant.entity.goal.NightShelterGoal.SHELTER_BLOCK) < blocksNeeded) {
            GatherGoal.Kind k = resourceNearby(GatherGoal.Kind.STONE, 16)
                ? GatherGoal.Kind.STONE : GatherGoal.Kind.DIRT;
            if (!resourceNearby(k, 16)) return false; // nothing to mine here — explore will relocate
            say("Gathering blocks for the " + structure + ".");
            enqueue(Job.gather(k, Math.min(64, blocksNeeded)));
            return true;
        }
        say("Expanding the homestead — building the " + structure + ".");
        enqueue(Job.buildAt(structure, anchor, facing, radius));
        baseStage++;
        return true;
    }

    private int craftKitTick = -100000;

    /** Point the craft planner at the worn-out kit: map the checklist's gap to
     *  an item, pull raw materials from the stores, and queue the plan. Once a
     *  minute at most — a failed plan should not become a busy-loop. */
    private boolean craftReplacementKit() {
        if (tickCount - craftKitTick < 1200) return false;
        craftKitTick = tickCount;
        String want = null;
        for (String gap : missingEssentials) {
            if (gap.contains("pickaxe")) want = "wooden_pickaxe";   // before "axe"!
            else if (gap.contains("axe")) want = "wooden_axe";
            else if (gap.contains("hoe")) want = "wooden_hoe";
            else if (gap.contains("sword")) want = "wooden_sword";
            else if (gap.contains("fishing rod")) want = "fishing_rod";
            else if (gap.contains("shears")) want = "shears";
            else if (gap.contains("torches")) want = "torches";
            if (want != null) break;
        }
        if (want == null) return false;
        // The makings, from the stores: wood and iron cover every kit recipe.
        scoopFromChests(s -> s.is(ItemTags.PLANKS) || s.is(ItemTags.LOGS) || s.is(Items.STICK)
            || s.is(Items.IRON_INGOT) || s.is(Items.STRING) || s.is(Items.COAL),
            12, chestRange(), false);
        CraftPlanner.Result plan = CraftPlanner.plan(this, want, 1);
        if (plan.jobs().isEmpty() || !plan.blockers().isEmpty()) return false;
        announcePlan("Out of kit — making myself a " + want.replace('_', ' '), plan);
        return true;
    }

    private int precraftTick = -100000;

    /**
     * A tool about to snap is a job about to stop. When every one of this
     * trade's tools within reach is down to its last 15% and there is no fresh
     * one in pack or stores, make the replacement NOW — while the bot can still
     * work — rather than discovering it mid-swing with a broken haft.
     *
     * <p>Tiered to what the stores can pay for, so a crew with iron coming in
     * quietly upgrades itself off wooden kit as it replaces it.
     */
    private boolean precraftWornTool() {
        if (tickCount - precraftTick < 1200) return false;
        java.util.function.Predicate<ItemStack> tool = tradeTool();
        if (tool == null) return false;

        boolean anyWorn = false, anyFresh = false;
        java.util.List<ItemStack> reachable = new java.util.ArrayList<>(inventory);
        reachable.add(getMainHandItem());
        for (ZoneChests.Found f : linkedChests()) {
            if (!f.stillThere()) continue;
            Container c = f.container();
            for (int i = 0; i < c.getContainerSize(); i++) reachable.add(c.getItem(i));
        }
        for (ItemStack st : reachable) {
            if (st.isEmpty() || !tool.test(st)) continue;
            if (!st.isDamageableItem() || st.getMaxDamage() <= 0) { anyFresh = true; continue; }
            int left = (st.getMaxDamage() - st.getDamageValue()) * 100 / st.getMaxDamage();
            if (left <= 15) anyWorn = true; else anyFresh = true;
        }
        // Only when the LAST good one is nearly gone: a spare in the chest
        // means there is nothing to pre-empt.
        if (!anyWorn || anyFresh) return false;

        String recipe = tradeToolRecipe();
        if (recipe == null) return false;
        precraftTick = tickCount;
        scoopFromChests(st -> st.is(ItemTags.PLANKS) || st.is(ItemTags.LOGS) || st.is(Items.STICK)
            || st.is(Items.IRON_INGOT) || st.is(Items.DIAMOND)
            || st.is(ItemTags.STONE_TOOL_MATERIALS), 12, chestRange(), false);
        CraftPlanner.Result plan = CraftPlanner.plan(this, recipe, 1);
        if (plan.jobs().isEmpty() || !plan.blockers().isEmpty()) return false;
        announcePlan("My " + tradeToolName() + " is nearly gone — making a "
            + recipe.replace('_', ' ') + " before it breaks", plan);
        return true;
    }

    private String tradeToolName() {
        return switch (stationTask) {
            case FARM -> "hoe";
            case WOOD -> "axe";
            case MINE -> "pickaxe";
            case GUARD -> "sword";
            case RANCH -> "shears";
            case FISH -> "rod";
            default -> "tool";
        };
    }

    /** This trade's tool at the best tier the stores can actually pay for —
     *  which is also, quietly, how a crew climbs off wooden kit over time. */
    @Nullable
    private String tradeToolRecipe() {
        String kind = switch (stationTask) {
            case FARM -> "hoe";
            case WOOD -> "axe";
            case MINE -> "pickaxe";
            case GUARD -> "sword";
            case RANCH -> "shears";        // iron only — no tiers to climb
            case FISH -> "fishing_rod";    // no tiers either
            default -> null;
        };
        if (kind == null || kind.equals("shears") || kind.equals("fishing_rod")) return kind;
        if (countStocked(st -> st.is(Items.DIAMOND)) >= 3) return "diamond_" + kind;
        if (countStocked(st -> st.is(Items.IRON_INGOT)) >= 3) return "iron_" + kind;
        if (countStocked(st -> st.is(ItemTags.STONE_TOOL_MATERIALS)) >= 3) return "stone_" + kind;
        return "wooden_" + kind;
    }

    private void announcePlan(String intro, CraftPlanner.Result plan) {
        say(intro + " — " + String.join(", ", plan.narration()) + ".");
        for (Job j : plan.jobs()) enqueue(j);
    }

    /** Last resort when there's genuinely nothing to work with right here: strike
     *  out to fresh terrain so the bot keeps progressing instead of standing idle.
     *  Autonomy only (it's playing for itself), and only when it's safe to travel. */
    private void decideExplore() {
        if (!autonomous) return;                         // only when playing for itself
        if (level().isNight() || isPackFull()) return;   // regroup / deposit first
        if (getHealth() < getMaxHealth() * 0.6F) return; // heal up before trekking
        if (usefulResourceNearby(20)) return;            // still stuff here — not idle yet

        BlockPos dest = pickExploreTarget();
        if (dest == null) return;
        say("Nothing left to work with here — scouting " + compass(dest) + " for more.");
        enqueue(Job.explore(dest));
    }

    /** Station duty: stay in the assigned area and run the specialty in a loop —
     *  work what's ready, stash the output in the station chest, wait for
     *  regrowth, repeat. Returns true if it acted this cycle. */
    private boolean decideStation() {
        BlockPos st = stationPos;
        if (st == null) return false;
        // Essentials gate: no tools, no chest, no work. Re-checked a few times a
        // minute (the scan reads the world), and reported by name — once — so the
        // player knows exactly what to hand over.
        if (tickCount - readyCheckTick > 60) {
            readyCheckTick = tickCount;
            refreshJobState();
        }
        if (missingEssentials.isEmpty() && ownerId != null && tickCount % 200 == 0) {
            Requests.clear(ownerId, getUUID());
        }
        // Draw its kit from its own stores first. The checklist counts a tool
        // sat in the zone chest as held — which is the point, a stocked chest
        // should never make a bot nag — so the bot has to actually go and get
        // the thing, or it would work empty-handed under a green "Working".
        // (Also runs from the tick, outside the idle back-off — see topUpKit.)
        topUpKit();
        // And before the last good tool snaps, not after.
        if (missingEssentials.isEmpty() && precraftWornTool()) return true;
        if (!missingEssentials.isEmpty()) {
            // Help itself from its own chests before bothering anyone. A player
            // who stocked spare hoes, pickaxes or torches at the station should
            // be able to walk away for hours — a worn-out tool shouldn't idle a
            // specialist until they happen to come back and notice.
            if (resupplyFromChests()) {
                refreshJobState();
                if (missingEssentials.isEmpty()) {
                    say("Grabbed a replacement from the chest — back to work.");
                    return true;
                }
            }
            // Nothing to TAKE? Make one. The planner reads the game's own
            // recipe book, so a farmer with planks in the chest crafts its own
            // hoe instead of standing there asking for one.
            if (craftReplacementKit()) return true;
            // Say it on the crew's board as well as out loud: a shortage is
            // work someone else can pick up, not just a message for the player.
            if (ownerId != null) {
                for (String gap : missingEssentials) {
                    Requests.Need need = Requests.Need.fromGap(gap);
                    if (need != null) Requests.post(ownerId, getUUID(), need, tickCount);
                }
            }
            if (tickCount - stationWarnTick > 2400) {
                stationWarnTick = tickCount;
                say("I can't work as a " + stationTask.title.toLowerCase()
                    + " yet — I need " + String.join(", ", missingEssentials)
                    + ". Leave spares in my chest and I'll help myself next time.");
            }
            return false;
        }
        // Stay on the patch. With a zone the boundary is the zone itself — step
        // outside the box (a chase, a retreat, a vein that ran on) and the next
        // thing this bot does is walk back in. Without one, fall back to a
        // radius around the post.
        //
        // This rung deliberately sits ABOVE the upkeep check: a bot that has run
        // out of rations must still be able to walk home to the chest holding
        // them. With the order reversed it stalled wherever it stood, repeating
        // "I'm out of rations" with a full chest twenty blocks away, forever.
        if (workZone != null && escortWard() == null && stationTask != StationTask.HAUL) {
            if (!workZone.containsColumn(blockPosition())) {
                BlockPos back = workZone.center();
                getNavigation().moveTo(back.getX() + 0.5, back.getY(), back.getZ() + 0.5, 1.1D);
                return true;
            }
        } else if (workZone == null) {
            int leash = STATION_RADIUS + 6;
            if (st.distSqr(blockPosition()) > (double) leash * leash) {
                getNavigation().moveTo(st.getX() + 0.5, st.getY(), st.getZ() + 0.5, 1.1D);
                return true;
            }
        }
        // Running costs — rations and a core charge. Dry means downing tools,
        // but only once we're stood at the post where the supplies live.
        if (!payUpkeep()) return false;
        if (stationDepositDue()) return true;
        switch (stationTask) {
            case FARM -> {
                // Work when there is anything to do: something ripe to take,
                // ground free to plant, or — with no seeds at all — grass to
                // break for some.
                //
                // This used to ask "are there NO crops here?" before planting,
                // which meant a single seedling anywhere in the plot stopped a
                // farmer planting the other fifty empty squares. It stood in a
                // half-sown field holding a stack of seeds, waiting for one
                // wheat to ripen, looking broken. Having crops and having room
                // are different questions.
                int farm = surveyFarm();
                boolean ripe = (farm & FARM_RIPE) != 0;
                boolean room = (farm & (FARM_PLANTABLE | FARM_TILLABLE)) != 0;
                boolean grass = (farm & FARM_GRASS) != 0;
                if (ripe || (hasSeeds() && room) || (!hasSeeds() && grass)) {
                    enqueue(Job.farm());
                    return true;
                }
            }
            case WOOD -> {
                // Sweep the loose drops and saplings between fellings, then chop
                // whatever has grown back; GatherGoal replants every stump.
                if (looseDropCount(8) >= 3 && !isPackFull()) {
                    enqueue(Job.cleanup());
                    return true;
                }
                if (resourceNearby(GatherGoal.Kind.LOGS, STATION_RADIUS)) {
                    enqueue(Job.gather(GatherGoal.Kind.LOGS, 16));
                    return true;
                }
            }
            case RANCH -> {
                // Shear what's woolly, cull when the herd is big, breed it back
                // up when it's small — a rotating husbandry cycle at the pen.
                // And eggs first: they despawn in five minutes, so a laid egg
                // doesn't wait for three drops to pile up like general litter.
                if (!isPackFull() && !level().getEntitiesOfClass(
                        net.minecraft.world.entity.item.ItemEntity.class,
                        getBoundingBox().inflate(STATION_RADIUS),
                        d -> d.isAlive() && d.getItem().is(Items.EGG)
                            && inZone(d.blockPosition())).isEmpty()) {
                    enqueue(Job.cleanup());
                    return true;
                }
                if (shearableSheepNearby()) {
                    if (countCarried(s -> s.is(Items.SHEARS)) > 0) {
                        enqueue(Job.shear(4));
                        return true;
                    }
                    if (countCarried(s -> s.is(Items.IRON_INGOT)) >= 2) {
                        CraftPlanner.Result plan = CraftPlanner.plan(this, "shears", 1);
                        if (!plan.jobs().isEmpty() && plan.blockers().isEmpty()) {
                            announcePlan("Making shears for the wool", plan);
                            return true;
                        }
                    }
                }
                int adults = adultAnimalsNearby(STATION_RADIUS);
                if (adults > 10) {
                    say("Herd's getting big — culling a couple for the larder.");
                    enqueue(Job.hunt(null, 2));
                    return true;
                }
                // Pace breeding to the animals' ~5-minute love cooldown so the
                // rancher isn't re-running empty breed jobs between litters.
                if (adults >= 2 && tickCount - stationBreedTick > 6000) {
                    if (countCarried(BREEDING_FOOD) < 4) {
                        scoopFromChests(BREEDING_FOOD, 16, chestRange()); // the farm's produce feeds the ranch
                    }
                    if (countCarried(BREEDING_FOOD) >= 4) {
                        stationBreedTick = tickCount;
                        enqueue(Job.breed(null, 2));
                        return true;
                    }
                }
            }
            case GUARD -> {
                // Hold the ground: engage hostiles in the watch radius (creepers
                // only from range), sweep the drops, and keep the area lit.
                // A guard denies its whole patch, not just arm's reach — watch
                // out to the zone's edge (capped so a huge claim stays sane).
                // On escort, the ward IS the post: stay at its shoulder, fight
                // what comes near, and never get pulled back to the patch.
                AssistantEntity ward = escortWard();
                if (ward != null) {
                    Monster threat = nearestMonster(16 + branchWorkRadiusBonus());
                    if (threat != null && !shouldDisengage()
                        && (!(threat instanceof Creeper) || (hasBow() && hasArrows()))) {
                        setTarget(threat);
                        rallyGuards(threat);
                        return true;
                    }
                    if (distanceToSqr(ward) > 36.0) {
                        if (getNavigation().isDone()) getNavigation().moveTo(ward, 1.1D);
                    }
                    return true;
                }
                Monster m = nearestMonster((workZone != null
                    ? Math.min(32, Math.max(STATION_RADIUS, workZone.workRadius())) : STATION_RADIUS)
                    + branchWorkRadiusBonus());
                if (m != null && !shouldDisengage()
                    && (!(m instanceof Creeper) || (hasBow() && hasArrows()))) {
                    setTarget(m);
                    rallyGuards(m);
                    return true;
                }
                if (looseDropCount(8) >= 3 && !isPackFull()) {
                    enqueue(Job.cleanup());
                    return true;
                }
                if (countMatching(s -> s.is(Items.TORCH)) >= 4
                    && tickCount - stationTorchTick > 12000) { // ~10 min between lighting passes
                    stationTorchTick = tickCount;
                    enqueue(Job.torchArea(10));
                    return true;
                }
                // Nothing to fight and nothing to tidy: walk the beat. A guard
                // standing in the middle of its plot only ever meets what comes
                // to it; one walking the perimeter meets it at the edge, which
                // is the whole point of posting a guard on a boundary.
                if (walkTheBeat()) return true;
            }
            case SMELT -> {
                // Keep the furnaces fed: pull ore (and fuel) from the input
                // chests, run the smelts, and the ingot surplus gets stashed by
                // the deposit rung above.
                if (!furnaceNearby()) return false;
                if (countMatching(SMELTABLE_ORE) == 0) {
                    scoopFromChests(SMELTABLE_ORE, 64, chestRange());
                }
                boolean hasFuel = countMatching(s -> s.is(Items.COAL) || s.is(Items.CHARCOAL)) > 0
                    || countMatching(s -> s.is(ItemTags.PLANKS) || s.is(ItemTags.LOGS)) > 0
                    || scoopFromChests(s -> s.is(Items.COAL) || s.is(Items.CHARCOAL), 16, chestRange()) > 0;
                int iron = countMatching(s -> s.is(Items.RAW_IRON) || s.is(Items.IRON_ORE) || s.is(Items.DEEPSLATE_IRON_ORE));
                int gold = countMatching(s -> s.is(Items.RAW_GOLD) || s.is(Items.GOLD_ORE) || s.is(Items.DEEPSLATE_GOLD_ORE));
                int copper = countMatching(s -> s.is(Items.RAW_COPPER) || s.is(Items.COPPER_ORE) || s.is(Items.DEEPSLATE_COPPER_ORE));
                if (iron + gold + copper == 0) {
                    // No ore to run — cook for the crew instead. The rancher's
                    // raw drops become the 100%-pace meals the diet system
                    // wants, and the supply chain hands them to whoever is
                    // hungry. The pen feeds the operation by itself.
                    if (countMatching(RAW_FOOD) == 0) {
                        scoopFromChests(RAW_FOOD, 32, chestRange());
                    }
                    int raw = countMatching(RAW_FOOD);
                    if (raw > 0 && hasFuel) {
                        enqueue(Job.smelt("food", raw));
                        return true;
                    }
                    return false; // input chest is empty — wait
                }
                if (!hasFuel) {
                    if (tickCount - stationWarnTick > 4800) {
                        stationWarnTick = tickCount;
                        say("The smeltery's out of fuel — drop coal (or logs) in the input chest.");
                    }
                    return false;
                }
                if (iron > 0) enqueue(Job.smelt("iron", iron));
                if (gold > 0) enqueue(Job.smelt("gold", gold));
                if (copper > 0) enqueue(Job.smelt("copper", copper));
                return true;
            }
            case MINE -> {
                // A resident miner: each run reuses/extends the staircase, tunnels
                // a fresh gallery grabbing every vein, torches as it goes, then the
                // walk-back + deposit rungs bank the haul at the post chest. The
                // zone's depth setting is the floor it digs down to.
                int targetY = workZone != null ? workZone.depth()
                    : (bestPickTier() >= 3 ? -54 : 12);
                enqueue(Job.mine(targetY));
                return true;
            }
            case FISH -> {
                // A resident angler: fishes the zone's water, cooks nothing, and
                // the deposit rung banks the catch (plus whatever junk treasure).
                enqueue(Job.fish(8));
                return true;
            }
            case STORE -> {
                // A storekeeper: keeps the zone's chests consolidated and sweeps
                // up anything left lying around the storeroom.
                if (looseDropCount(8) >= 3 && !isPackFull()) {
                    enqueue(Job.cleanup());
                    return true;
                }
                // A tidy storeroom needs tidying rarely — without this it would
                // re-sort (and announce it) every single work cycle.
                if (tickCount - stationSortTick > 1200) {
                    stationSortTick = tickCount;
                    enqueue(Job.sort());
                    return true;
                }
            }
            case HAUL -> {
                // Two chests, one route: the wand links the pickup, then the
                // delivery, and the hauler ferries everything between them.
                BlockPos from = preferredChest;
                BlockPos to = deliveryChest;
                if (from == null || to == null
                    || !(level().getBlockEntity(from) instanceof Container)
                    || !(level().getBlockEntity(to) instanceof Container)) {
                    if (tickCount - stationWarnTick > 4800) {
                        stationWarnTick = tickCount;
                        say(from == null
                            ? "Link my route with the wand — click the PICKUP chest first."
                            : to == null
                            ? "Pickup's set — now click the chest I should DELIVER to."
                            : "One of my chests is gone — re-link the route with the wand.");
                    }
                    return false;
                }
                // A rail line between the two ends turns the route into a
                // cart run: a chest minecart is loaded at the pickup, towed
                // along the rails behind the hauler, and emptied at the
                // delivery — a whole chest a trip instead of a packful. Any
                // failure just falls back to walking the route like before.
                if (railRoute(from, to)) return true;
                // Carrying cargo? Deliver it — to THE delivery chest, not
                // whatever chest happens to be closest to where we stand. A
                // part-load goes too once it has sat for a minute, so a slow
                // trickle still crosses the base.
                if (countItems() > 0 && (isPackFull()
                        || countItems() >= 24 + perkPackBonus()
                        || tickCount - lastStashTick > 1200)) {
                    sayRoutine("Running the load over.");
                    enqueue(Job.depositAt(to));
                    return true;
                }
                // Empty hands: stand at the pickup and load it clean.
                if (from.distSqr(blockPosition()) > 9.0) {
                    if (getNavigation().isDone()) {
                        getNavigation().moveTo(from.getX() + 0.5, from.getY(), from.getZ() + 0.5, 1.1D);
                    }
                    return true;
                }
                return loadFrom(from, 512) > 0;
            }
            case NONE -> { }
        }
        // Nothing to do right where it's stood. On a zone bigger than its own
        // search radius that doesn't mean the zone is finished — it means this
        // corner is. Drift to another part of the patch so a big farm, forest or
        // pasture gets worked end to end instead of hollowed out in the middle.
        // Only for jobs that roam: a smelter, fisher, storekeeper and hauler are
        // each tied to a fixed furnace, pond or chest and must stay by it, and a
        // miner belongs at its shaft head.
        // Before drifting at random: is there ground here that paid off before?
        // A worked seam usually has more in it, and a grove replanted an hour
        // ago is standing timber by now. This is what stops a miner wandering
        // off in a straight line away from the ore it just found.
        if ((stationTask == StationTask.MINE || stationTask == StationTask.WOOD)
            && getNavigation().isDone() && tickCount - revisitTick > 600) {
            BlockPos spot = richSpotToRevisit();
            if (spot != null) {
                revisitTick = tickCount;
                getNavigation().moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 1.0D);
                sayRoutine("Back to the good ground.");
                return true;
            }
        }

        boolean roams = stationTask == StationTask.FARM || stationTask == StationTask.WOOD
            || stationTask == StationTask.RANCH || stationTask == StationTask.GUARD;
        // No size gate here — it used to require a radius over 16, but every
        // local search only sees ~16 blocks from where the bot STANDS, so any
        // zone bigger than the view from one spot needs the walk. Paced at one
        // stroll per 15s so an idle bot reads as patrolling, not pacing.
        if (roams && workZone != null && !movementBlocked()
            && getNavigation().isDone() && tickCount - driftTick > 300) {
            BlockPos spot = randomSpotInZone();
            if (spot != null && spot.distSqr(blockPosition()) > 64) {
                driftTick = tickCount;
                getNavigation().moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 1.0D);
                return true;
            }
        }
        return false; // nothing ready — crops/saplings still growing, herd resting
    }

    /** A random walkable-ish spot inside the work zone, for patrolling a big patch. */
    @Nullable
    private BlockPos randomSpotInZone() {
        if (workZone == null) return null;
        net.minecraft.util.RandomSource rnd = getRandom();
        for (int attempt = 0; attempt < 6; attempt++) {
            int x = workZone.min().getX() + rnd.nextInt(Math.max(1, workZone.sizeX()));
            int z = workZone.min().getZ() + rnd.nextInt(Math.max(1, workZone.sizeZ()));
            int y = level().getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (y > level().getMinBuildHeight() + 1) return new BlockPos(x, y, z);
        }
        return null;
    }

    /** Raw or block-form ore the smeltery keeper takes as input. */
    public static final java.util.function.Predicate<ItemStack> SMELTABLE_ORE = s ->
        s.is(Items.RAW_IRON) || s.is(Items.IRON_ORE) || s.is(Items.DEEPSLATE_IRON_ORE)
        || s.is(Items.RAW_GOLD) || s.is(Items.GOLD_ORE) || s.is(Items.DEEPSLATE_GOLD_ORE)
        || s.is(Items.RAW_COPPER) || s.is(Items.COPPER_ORE) || s.is(Items.DEEPSLATE_COPPER_ORE);

    private int looseDropCount(int radius) {
        return level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
            getBoundingBox().inflate(radius)).size();
    }

    public int adultAnimalsNearby(int radius) {
        return level().getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class,
            getBoundingBox().inflate(radius), a -> a.isAlive() && !a.isBaby()).size();
    }

    private boolean shearableSheepNearby() {
        return !level().getEntitiesOfClass(net.minecraft.world.entity.animal.Sheep.class,
            getBoundingBox().inflate(STATION_RADIUS),
            sh -> sh.isAlive() && !sh.isBaby() && sh.readyForShearing()).isEmpty();
    }

    /**
     * Load the pack from ONE specific chest — the hauler's pickup. Paced like
     * any other transfer, and reserves nothing: a hauler's cargo is everything
     * the chest holds.
     */
    // ------------------------------ rail hauling ------------------------------
    // If a rail line connects the two linked chests, the route runs on it: a
    // chest minecart is placed (or adopted) at the pickup end, loaded from the
    // pickup chest, towed along the rails to the delivery end and emptied
    // there — 27 slots a trip instead of a packful. The rail is an upgrade and
    // never a dependency: no rails, no cart in stock, or a line that stops
    // moving, and the hauler simply walks the route the way it always has.

    @Nullable
    private BlockPos railNear(BlockPos chest) {
        for (BlockPos p : BlockPos.betweenClosed(chest.offset(-3, -2, -3), chest.offset(3, 2, 3))) {
            if (level().getBlockState(p).getBlock()
                    instanceof net.minecraft.world.level.block.BaseRailBlock) {
                return p.immutable();
            }
        }
        return null;
    }

    /** The route's cart: the remembered one if it still stands, else any
     *  chest cart already on the line by the pickup (run two reuses run
     *  one's cart, and a reload finds it again by position), else null. */
    @Nullable
    private net.minecraft.world.entity.vehicle.MinecartChest routeCart(BlockPos pickupRail) {
        if (routeCartId >= 0
            && level().getEntity(routeCartId)
                instanceof net.minecraft.world.entity.vehicle.MinecartChest kept
            && kept.isAlive()) {
            return kept;
        }
        routeCartId = -1;
        java.util.List<net.minecraft.world.entity.vehicle.MinecartChest> carts =
            level().getEntitiesOfClass(net.minecraft.world.entity.vehicle.MinecartChest.class,
                new net.minecraft.world.phys.AABB(pickupRail).inflate(5.0));
        if (carts.isEmpty()) {
            carts = level().getEntitiesOfClass(net.minecraft.world.entity.vehicle.MinecartChest.class,
                getBoundingBox().inflate(8.0));
        }
        if (!carts.isEmpty()) {
            routeCartId = carts.get(0).getId();
            return carts.get(0);
        }
        return null;
    }

    /** One tick of the rail route. True = the route is running on rails this
     *  tick; false = no line, no cart, or a line flagged stuck — walk it. */
    private boolean railRoute(BlockPos from, BlockPos to) {
        if (tickCount < railBrokenUntil) return false;
        BlockPos railA = railNear(from);
        if (railA == null || railNear(to) == null) return false;
        net.minecraft.world.entity.vehicle.MinecartChest cart = routeCart(railA);
        if (cart == null) {
            // No cart on the line yet: put one down from stock. Not carrying
            // one and none in the pickup chest = no rail route, no fuss.
            boolean holding = countCarried(s -> s.is(Items.CHEST_MINECART)) > 0;
            if (!holding && level().getBlockEntity(from) instanceof Container c) {
                holding = takeOneFrom(c, s -> s.is(Items.CHEST_MINECART));
            }
            if (!holding) return false;
            if (railA.distSqr(blockPosition()) > 9.0) {
                if (getNavigation().isDone()) {
                    getNavigation().moveTo(railA.getX() + 0.5, railA.getY(), railA.getZ() + 0.5, 1.1D);
                }
                return true;
            }
            if (removeMatching(s -> s.is(Items.CHEST_MINECART), 1) == 1) {
                net.minecraft.world.entity.vehicle.MinecartChest fresh =
                    net.minecraft.world.entity.EntityType.CHEST_MINECART.create(level());
                if (fresh == null) return false;
                fresh.setPos(railA.getX() + 0.5, railA.getY() + 0.1, railA.getZ() + 0.5);
                level().addFreshEntity(fresh);
                routeCartId = fresh.getId();
                popSound();
                say("The route's on rails — the cart carries the loads from here.");
            }
            return true;
        }
        boolean cargo = false;
        for (int i = 0; i < cart.getContainerSize(); i++) {
            if (!cart.getItem(i).isEmpty()) { cargo = true; break; }
        }
        BlockPos goal = cargo ? to : from;
        if (blockPosition().distSqr(goal) <= 25.0 && cart.distanceToSqr(this) <= 36.0) {
            // The hauler is at the working end with the cart beside it: move
            // the load across, one paced transfer like any other.
            if (!(level().getBlockEntity(goal) instanceof Container chest)) return false;
            if (!transferReady()) return true;
            int moved = cargo ? moveBetween(cart, chest, 512) : moveBetween(chest, cart, 512);
            if (moved > 0) {
                beginTransfer();
                popSound();
                if (cargo) sayRoutine("Cart's in — putting the load away.");
                return true;
            }
            if (cargo && tickCount - stationWarnTick > 4800) {
                stationWarnTick = tickCount;
                say("Delivery chest is full — the cart waits until there's room.");
            }
            // A loaded cart with nowhere to unload holds the route; an empty
            // cart at an empty pickup hands the tick back to the foot logic,
            // which idles the same way it would without rails.
            return cargo;
        }
        // Mid-route: walk toward the right end. The tow tick keeps the cart
        // rolling along the rails behind.
        if (getNavigation().isDone()) {
            getNavigation().moveTo(goal.getX() + 0.5, goal.getY(), goal.getZ() + 0.5, 1.1D);
        }
        return true;
    }

    /** Move up to {@code max} items from one container to another. */
    private int moveBetween(Container src, Container dst, int max) {
        int moved = 0;
        for (int i = 0; i < src.getContainerSize() && moved < max; i++) {
            ItemStack s = src.getItem(i);
            if (s.isEmpty()) continue;
            int before = s.getCount();
            ItemStack left = s.copy();
            for (int j = 0; j < dst.getContainerSize() && !left.isEmpty(); j++) {
                ItemStack t = dst.getItem(j);
                if (t.isEmpty()) {
                    dst.setItem(j, left);
                    left = ItemStack.EMPTY;
                } else if (ItemStack.isSameItemSameComponents(t, left)) {
                    int room = t.getMaxStackSize() - t.getCount();
                    if (room > 0) {
                        int put = Math.min(room, left.getCount());
                        t.grow(put);
                        left.shrink(put);
                    }
                }
            }
            int gone = before - left.getCount();
            if (gone > 0) {
                src.setItem(i, left.isEmpty() ? ItemStack.EMPTY : left);
                moved += gone;
            }
        }
        if (moved > 0) { src.setChanged(); dst.setChanged(); }
        return moved;
    }

    /** Take a single matching item out of a container into the pack. */
    private boolean takeOneFrom(Container c, java.util.function.Predicate<ItemStack> what) {
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (s.isEmpty() || !what.test(s)) continue;
            if (!insertItem(s.copyWithCount(1)).isEmpty()) return false;   // pack full
            s.shrink(1);
            if (s.isEmpty()) c.setItem(i, ItemStack.EMPTY);
            c.setChanged();
            return true;
        }
        return false;
    }

    private int loadFrom(BlockPos chestPos, int max) {
        if (!transferReady()) return 0;
        if (!(level().getBlockEntity(chestPos) instanceof Container c)) return 0;
        int moved = 0;
        for (int i = 0; i < c.getContainerSize() && moved < max; i++) {
            ItemStack stack = c.getItem(i);
            if (stack.isEmpty()) continue;
            ItemStack leftover = insertItem(stack.copy());
            int taken = stack.getCount() - leftover.getCount();
            if (taken <= 0) break;   // pack is full
            c.setItem(i, leftover.isEmpty() ? ItemStack.EMPTY : leftover);
            moved += taken;
        }
        if (moved > 0) {
            c.setChanged();
            beginTransfer();
            popSound();
        }
        return moved;
    }

    /** Pull matching items out of chests near the bot into its pack — station
     *  keepers feeding themselves work: the hauler loading cargo, the smeltery
     *  keeper taking ore and fuel, the rancher grabbing breeding food. */
    private int scoopFromChests(java.util.function.Predicate<ItemStack> what, int max, int radius) {
        return scoopFromChests(what, max, radius, true);
    }

    /**
     * @param paced true for ordinary hauling, which takes its two seconds like
     *              any other transfer; false for a bot feeding itself. Eating
     *              must never queue behind a load, or a bot that happened to be
     *              mid-stash would report itself out of upkeep and stop working
     *              over a two-second wait.
     */
    private int scoopFromChests(java.util.function.Predicate<ItemStack> what, int max, int radius,
                                boolean paced) {
        if (paced && !transferReady()) return 0;    // still handling the last load
        int moved = 0;
        // Search from the SAME origin and box the checklist used to accept a
        // chest (zone centre, +/-5 in Y). Scanning from the bot instead meant a
        // chest could pass the requirement check and still be permanently out
        // of reach once the bot roamed to the far side of its own patch.
        BlockPos origin = chestSearchOrigin();
        java.util.List<ZoneChests.Found> stores = radius == chestRange()
            ? linkedChests() : ZoneChests.around(level(), origin, radius, 5);
        // Open the chests in the order a person would: the one the player
        // linked by hand, then the one that answered last time, then whatever
        // is nearest to where the bot is STOOD — not the scan's fixed order,
        // which could walk it across the whole patch past three nearer chests.
        if (stores.size() > 1) {
            java.util.List<ZoneChests.Found> byUse = new java.util.ArrayList<>(stores);
            BlockPos me = blockPosition();
            byUse.sort(java.util.Comparator.comparingDouble(f -> f.pos().distSqr(me)));
            byUse.sort(java.util.Comparator.comparingInt(f ->
                f.pos().equals(preferredChest) ? 0 : f.pos().equals(lastGoodChest) ? 1 : 2));
            stores = byUse;
        }
        for (ZoneChests.Found found : stores) {
            if (!found.stillThere()) continue;
            Container c = found.container();
            int before = moved;
            for (int i = 0; i < c.getContainerSize() && moved < max; i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || !what.test(s)) continue;
                int take = Math.min(s.getCount(), max - moved);
                ItemStack leftover = insertItem(s.copyWithCount(take));
                int taken = take - leftover.getCount();
                if (taken <= 0) { c.setChanged(); return moved; } // pack is full
                s.shrink(taken);
                if (s.isEmpty()) c.setItem(i, ItemStack.EMPTY);
                moved += taken;
            }
            c.setChanged();
            rememberChest(found.pos(), c);
            if (moved > before) lastGoodChest = found.pos();  // this one answers
        }
        if (moved > 0 && paced) beginTransfer();
        return moved;
    }

    /** Output piling up? Stash it at the station, setting up a chest if need be.
     *  Counts only the SURPLUS above the working reserve (seed stock, saplings) —
     *  otherwise a bot holding exactly its reserve would loop stash-nothing runs. */
    private boolean stationDepositDue() {
        // A hauler's whole job is delivering ELSEWHERE — stashing into the pickup
        // chest here would just cycle its own cargo forever.
        if (stationTask == StationTask.HAUL) return false;
        int surplus = switch (stationTask) {
            case FARM -> surplusOf(Items.WHEAT, 0) + surplusOf(Items.BEETROOT, 0)
                + surplusOf(Items.CARROT, 12) + surplusOf(Items.POTATO, 12)
                + surplusOf(Items.WHEAT_SEEDS, 12) + surplusOf(Items.BEETROOT_SEEDS, 12);
            case WOOD -> countMatching(s -> s.is(ItemTags.LOGS));
            case RANCH -> countMatching(s -> s.is(ItemTags.WOOL) || s.is(Items.LEATHER)
                || s.is(Items.FEATHER) || s.is(Items.EGG)
                || s.is(Items.BEEF) || s.is(Items.COOKED_BEEF)
                || s.is(Items.PORKCHOP) || s.is(Items.COOKED_PORKCHOP)
                || s.is(Items.MUTTON) || s.is(Items.COOKED_MUTTON)
                || s.is(Items.CHICKEN) || s.is(Items.COOKED_CHICKEN)
                || s.is(Items.RABBIT) || s.is(Items.COOKED_RABBIT));
            case GUARD -> countMatching(s -> s.is(Items.ROTTEN_FLESH) || s.is(Items.BONE)
                || s.is(Items.STRING) || s.is(Items.GUNPOWDER) || s.is(Items.SPIDER_EYE));
            case SMELT -> surplusOf(Items.IRON_INGOT, 0) + surplusOf(Items.GOLD_INGOT, 0)
                + surplusOf(Items.COPPER_INGOT, 0)
                // The crew's dinners: banked so the supply chain can route
                // them, minus a few kept back for the cook's own table.
                + surplusOf(Items.COOKED_BEEF, 3) + surplusOf(Items.COOKED_PORKCHOP, 3)
                + surplusOf(Items.COOKED_CHICKEN, 0) + surplusOf(Items.COOKED_MUTTON, 0)
                + surplusOf(Items.COOKED_RABBIT, 0) + surplusOf(Items.COOKED_COD, 0)
                + surplusOf(Items.COOKED_SALMON, 0);
            case MINE -> surplusOf(Items.LADDER, 64) + surplusOf(Items.COBBLESTONE, 16) + surplusOf(Items.COBBLED_DEEPSLATE, 0)
                + surplusOf(Items.ANDESITE, 0) + surplusOf(Items.DIORITE, 0)
                + surplusOf(Items.GRANITE, 0) + surplusOf(Items.TUFF, 0)
                + surplusOf(Items.DIRT, 0) + surplusOf(Items.GRAVEL, 0)
                + surplusOf(Items.RAW_IRON, 0) + surplusOf(Items.RAW_GOLD, 0)
                + surplusOf(Items.RAW_COPPER, 0) + surplusOf(Items.COAL, 0)
                + surplusOf(Items.REDSTONE, 0) + surplusOf(Items.LAPIS_LAZULI, 0)
                + surplusOf(Items.DIAMOND, 0) + surplusOf(Items.EMERALD, 0);
            case FISH -> countMatching(s -> s.is(Items.COD) || s.is(Items.SALMON)
                || s.is(Items.TROPICAL_FISH) || s.is(Items.PUFFERFISH)
                || s.is(Items.COOKED_COD) || s.is(Items.COOKED_SALMON));
            // A storekeeper works the chests directly — nothing to haul back.
            case STORE, HAUL, NONE -> 0;
        };
        // Stash sooner than a third of a pack: a rancher trickling in 1-3 wool
        // at a time looked like it never used its chest at all. Any output at
        // all gets banked once the bot has been holding it a while.
        boolean lingering = surplus > 0 && tickCount - lastStashTick > 3600;
        // Bank a batch, not a handful: every stash is a walk there and back,
        // so waiting for a fuller load halves the trips for the same output.
        // The lingering timer still banks a trickle, and a full pack always
        // forces a run — nothing is ever left uncollectable.
        if (!isPackFull() && surplus < 24 + branchPackBonus() + perkPackBonus() && !lingering) return false;
        // A station chest that has filled up must not deadlock the whole job.
        // This rung returns true (meaning "I acted") before the per-job switch,
        // so without a cool-off a full chest meant the bot walked to it, moved
        // nothing, and re-queued the same doomed deposit forever — never
        // farming, mining or fishing again while showing a green "Working".
        if (tickCount - depositBlockedTick < 6000) return false;
        if (findAnyChest(12) == null) {
            // No chest at the post yet: place one we carry, or craft one (a
            // lumberjack always has the planks for it).
            if (countCarried(s -> s.is(Items.CHEST)) > 0 && placeChestNearby()) {
                say("Set up a chest for the station's output.");
                return true;
            }
            CraftPlanner.Result plan = CraftPlanner.plan(this, "chest", 1);
            if (!plan.jobs().isEmpty() && plan.blockers().isEmpty()) {
                announcePlan("Making a chest for the station's output", plan);
                return true;
            }
            // Only nag about a chest for jobs whose checklist actually asked for
            // one. A guard was never told it needed storage — it should keep
            // guarding and simply hold its drops, not complain about a chest the
            // player was never asked to provide.
            boolean chestWasAskedFor = JobSpec.checklist(stationTask).stream()
                .anyMatch(need -> need.contains("chest"));
            if (!chestWasAskedFor) return false;
            // Can't make one (a farm rarely has wood) — tell the player, not
            // too often, instead of silently hoarding a full pack.
            if (tickCount - stationWarnTick > 4800) {
                stationWarnTick = tickCount;
                say("My pack's filling up and there's no chest at my station — drop one nearby and I'll use it.");
            }
            return false;
        }
        sayRoutine("Stashing the station's output.");
        enqueue(Job.deposit());
        return true;
    }

    private int surplusOf(net.minecraft.world.item.Item it, int reserve) {
        return Math.max(0, countMatching(s -> s.is(it)) - reserve);
    }

    /** Anything within r the bot can turn into progress: wood, stone, ore, cane,
     *  water for a farm, or animals/crops for food. Public so ExploreGoal can end
     *  a leg early the moment it walks into a fresh patch. */
    public boolean usefulResourceNearby(int r) {
        return resourceNearby(GatherGoal.Kind.LOGS, r)
            || resourceNearby(GatherGoal.Kind.STONE, r)
            || resourceNearby(GatherGoal.Kind.COAL, r)
            || resourceNearby(GatherGoal.Kind.IRON, r)
            || resourceNearby(GatherGoal.Kind.SUGAR_CANE, r)
            || matureCropsNearby() || grassNearby() || animalsNearby()
            || nearestWater(r) != null;
    }

    /** A ground-level scouting spot ~30 blocks out. Keeps a heading so it spirals
     *  outward rather than pacing, re-rolls away from open water, and turns back
     *  toward base once it has drifted far so it can still get home. */
    @Nullable
    private BlockPos pickExploreTarget() {
        net.minecraft.util.RandomSource rnd = getRandom();
        BlockPos here = feetPos();
        BlockPos anchor = homePos != null ? homePos : here;
        boolean farFromBase = anchor.distSqr(here) > 140.0 * 140.0;
        for (int attempt = 0; attempt < 6; attempt++) {
            float heading;
            if (farFromBase) {
                heading = (float) Math.atan2(anchor.getZ() - here.getZ(), anchor.getX() - here.getX());
            } else if (attempt == 0 && exploreHeading != 0f && rnd.nextInt(3) != 0) {
                heading = exploreHeading;                       // keep the same way (spiral out)
            } else {
                heading = rnd.nextFloat() * ((float) Math.PI * 2f);
            }
            int dist = 28 + rnd.nextInt(12);                    // 28..39 blocks per leg
            int tx = here.getX() + Math.round((float) Math.cos(heading) * dist);
            int tz = here.getZ() + Math.round((float) Math.sin(heading) * dist);
            int ty = level().getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, tx, tz);
            if (ty <= level().getMinBuildHeight() + 1) continue; // empty/unloaded column
            BlockPos surface = new BlockPos(tx, ty, tz);
            // Skip open water/lava columns so it doesn't march into the sea.
            if (!level().getBlockState(surface.below()).getFluidState().isEmpty()) continue;
            exploreHeading = heading;
            return surface;
        }
        return null;
    }

    /** Rough compass word from here to dest, for a readable "scouting east" line. */
    private String compass(BlockPos dest) {
        BlockPos here = feetPos();
        int dx = dest.getX() - here.getX();
        int dz = dest.getZ() - here.getZ();
        String ns = dz < 0 ? "north" : "south";
        String ew = dx < 0 ? "west" : "east";
        if (Math.abs(dx) > 2 * Math.abs(dz)) return ew;
        if (Math.abs(dz) > 2 * Math.abs(dx)) return ns;
        return ns + ew;
    }

    public static final java.util.function.Predicate<ItemStack> BREEDING_FOOD = s ->
        s.is(Items.WHEAT) || s.is(Items.CARROT) || s.is(Items.WHEAT_SEEDS);

    /** Highest-value iron kit piece still missing, or null when fully iron-safe. */
    @Nullable
    private String nextIronPiece() {
        String[] order = {"iron_chestplate", "iron_helmet", "iron_sword",
                          "iron_leggings", "iron_boots", "shield", "iron_pickaxe"};
        for (String id : order) {
            if (id.equals("iron_pickaxe") && bestPickTier() >= 3) continue; // already iron+
            net.minecraft.world.item.Item it = RecipeBook.item(id);
            if (it == Items.AIR) continue;
            if (countCarried(s -> s.is(it)) == 0) return id;
        }
        return null;
    }

    private static int ironCost(String id) {
        return switch (id) {
            case "iron_chestplate" -> 8;
            case "iron_leggings" -> 7;
            case "iron_helmet" -> 5;
            case "iron_boots" -> 4;
            case "iron_pickaxe" -> 3;
            case "iron_sword" -> 2;
            default -> 1; // shield: 1 iron + planks
        };
    }

    private int bestPickTier() {
        int best = pickTier(getMainHandItem());
        for (ItemStack s : inventory) best = Math.max(best, pickTier(s));
        return best;
    }

    private static int pickTier(ItemStack s) {
        if (s.isEmpty()) return 0;
        String p = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        if (!p.endsWith("_pickaxe")) return 0;
        if (p.startsWith("netherite") || p.startsWith("diamond")) return 4;
        if (p.startsWith("iron")) return 3;
        if (p.startsWith("stone")) return 2;
        return 1; // wooden / golden
    }

    private boolean furnaceNearby() {
        BlockPos feet = feetPos();
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-8, -3, -8), feet.offset(8, 3, 8))) {
            BlockState st = level().getBlockState(pos);
            if (st.is(Blocks.FURNACE) || st.is(Blocks.BLAST_FURNACE)) return true;
        }
        return false;
    }

    private boolean craftingTableNearby() {
        BlockPos feet = feetPos();
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-8, -3, -8), feet.offset(8, 3, 8))) {
            if (level().getBlockState(pos).is(Blocks.CRAFTING_TABLE)) return true;
        }
        return false;
    }

    /** Is a block this gather-kind can harvest actually within reach? Used to stop
     *  the idle brain from looping a doomed "gather X" where none exists nearby. */
    private boolean resourceNearby(GatherGoal.Kind kind, int radius) {
        BlockPos feet = feetPos();
        for (BlockPos pos : BlockPos.betweenClosed(
                feet.offset(-radius, -6, -radius), feet.offset(radius, 8, radius))) {
            if (!inZone(pos)) continue; // the goal filters by zone; so must we
            if (kind.matches(level().getBlockState(pos))) return true;
        }
        return false;
    }

    /** Any adult, un-named animal within hunting range (so we don't loop a hunt
     *  with no prey). */
    private boolean huntablePreyNearby() {
        return !level().getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class,
            getBoundingBox().inflate(24.0),
            an -> an.isAlive() && !an.isBaby() && !an.hasCustomName()).isEmpty();
    }

    private static final int FARM_RIPE      = 1;
    private static final int FARM_PLANTABLE = 2;   // bare farmland, ready for seed
    private static final int FARM_TILLABLE  = 4;   // dirt or grass a hoe could turn
    private static final int FARM_GRASS     = 8;   // grass to break for seeds

    /**
     * One pass over the plot answering everything the farm decision needs: is
     * anything ripe, is there anywhere to plant, and is there grass to break for
     * seed. Asking those as three separate scans of the same box was three times
     * the work for one decision, and it is the decision a farmer makes most.
     */
    /** How ripe the patch is, 0-100, refreshed by surveyFarm. The map warms a
     *  farm's colour with it, so "worth visiting?" is answerable from above. */
    private int ripePercent;

    private int surveyFarm() {
        int flags = 0;
        int cropsSeen = 0, cropsRipe = 0;
        BlockPos feet = feetPos();
        // Dirt only counts as somewhere to plant if water can reach it, which
        // is a pair of facts rather than one — so collect a few of each and
        // cross them afterwards. Without this the gate would send a farmer to
        // till ground the goal then refuses to touch, and the bot would look
        // stuck for exactly the reason it looked stuck before.
        java.util.List<BlockPos> dirt = new java.util.ArrayList<>(8);
        java.util.List<BlockPos> water = new java.util.ArrayList<>(8);

        for (BlockPos pos : BlockPos.betweenClosed(
                feet.offset(-12, -3, -12), feet.offset(12, 3, 12))) {
            if (!inZone(pos)) continue;    // FarmGoal filters by zone; match it
            BlockState st = level().getBlockState(pos);
            if (st.getBlock() instanceof net.minecraft.world.level.block.CropBlock crop) {
                cropsSeen++;
                if (crop.isMaxAge(st)) { flags |= FARM_RIPE; cropsRipe++; }
            } else if (st.is(Blocks.MELON) || st.is(Blocks.PUMPKIN)) {
                flags |= FARM_RIPE;   // a fruit is a harvest; the stem stays
                cropsSeen++;
                cropsRipe++;
            } else if (st.is(Blocks.SUGAR_CANE)
                    && level().getBlockState(pos.below()).is(Blocks.SUGAR_CANE)) {
                flags |= FARM_RIPE;   // cane two high: cut above the root
                cropsSeen++;
                cropsRipe++;
            } else if (st.is(Blocks.WATER)) {
                if (water.size() < 8) water.add(pos.immutable());
            } else if (st.isAir()) {
                BlockState below = level().getBlockState(pos.below());
                if (below.is(Blocks.FARMLAND)) {
                    flags |= FARM_PLANTABLE;
                } else if ((below.is(Blocks.DIRT) || below.is(Blocks.GRASS_BLOCK)
                        || below.is(Blocks.COARSE_DIRT) || below.is(Blocks.ROOTED_DIRT))
                        && dirt.size() < 8) {
                    dirt.add(pos.below().immutable());
                }
            } else if (st.is(Blocks.SHORT_GRASS) || st.is(Blocks.TALL_GRASS) || st.is(Blocks.FERN)) {
                flags |= FARM_GRASS;
            }
        }

        // Vanilla hydration: water within four blocks horizontally, at the same
        // level or one above. Dry farmland reverts to dirt, so ground out of
        // that reach is not worth breaking.
        outer:
        for (BlockPos d : dirt) {
            for (BlockPos w : water) {
                if (Math.abs(d.getX() - w.getX()) <= 4 && Math.abs(d.getZ() - w.getZ()) <= 4
                    && w.getY() - d.getY() >= 0 && w.getY() - d.getY() <= 1) {
                    flags |= FARM_TILLABLE;
                    break outer;
                }
            }
        }
        ripePercent = cropsSeen == 0 ? 0 : cropsRipe * 100 / cropsSeen;
        return flags;
    }

    /** Spend one bonemeal on the nearest crop or stem still growing. */
    private void boneMealOne() {
        if (countCarried(s -> s.is(Items.BONE_MEAL)) == 0) return;
        if (!(level() instanceof net.minecraft.server.level.ServerLevel server)) return;
        BlockPos feet = feetPos();
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-6, -2, -6), feet.offset(6, 2, 6))) {
            if (!inZone(pos)) continue;
            BlockState st = level().getBlockState(pos);
            boolean growingCrop = st.getBlock()
                instanceof net.minecraft.world.level.block.CropBlock crop && !crop.isMaxAge(st);
            boolean stem = st.getBlock() instanceof net.minecraft.world.level.block.StemBlock;
            if (!growingCrop && !stem) continue;
            if (st.getBlock() instanceof net.minecraft.world.level.block.BonemealableBlock b
                && b.isValidBonemealTarget(level(), pos, st)
                && removeMatching(s -> s.is(Items.BONE_MEAL), 1) == 1) {
                b.performBonemeal(server, random, pos, level().getBlockState(pos));
                level().levelEvent(1505, pos, 15);   // the green sparkle
                swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                return;   // one per quiet moment — visible help, not a cheat
            }
        }
    }

    private int labelTick = -1000;

    /** Self-labelling storage: put an item frame on a linked chest showing a
     *  COPY of whatever the chest mostly holds, and keep it truthful as the
     *  contents drift. One chest per pass, only with frames in the pack (or
     *  pulled from stock), so a storeroom labels itself over a few quiet
     *  minutes rather than in one flurry. */
    private void labelChests() {
        if (tickCount - labelTick < 1200) return;
        labelTick = tickCount;
        if (countCarried(t -> t.is(Items.ITEM_FRAME)) == 0
            && scoopFromChests(t -> t.is(Items.ITEM_FRAME), 4, chestRange(), false) == 0) {
            return;
        }
        for (ZoneChests.Found f : linkedChests()) {
            if (!f.stillThere()) continue;
            Container c = f.container();
            // What does this chest mostly hold?
            java.util.Map<net.minecraft.world.item.Item, Integer> tally = new java.util.HashMap<>();
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack st = c.getItem(i);
                if (!st.isEmpty()) tally.merge(st.getItem(), st.getCount(), Integer::sum);
            }
            if (tally.isEmpty()) continue;
            net.minecraft.world.item.Item main = null;
            int best = 0;
            for (var e : tally.entrySet()) {
                if (e.getValue() > best) { best = e.getValue(); main = e.getKey(); }
            }
            // A frame already on the chest? Keep its sample truthful.
            java.util.List<net.minecraft.world.entity.decoration.ItemFrame> frames =
                level().getEntitiesOfClass(net.minecraft.world.entity.decoration.ItemFrame.class,
                    new net.minecraft.world.phys.AABB(f.pos()).inflate(1.0));
            boolean labelled = false;
            for (var frame : frames) {
                if (!frame.getPos().relative(frame.getDirection().getOpposite()).equals(f.pos())) continue;
                labelled = true;
                if (!frame.getItem().is(main)) {
                    frame.setItem(new ItemStack(main));
                    swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                }
                break;
            }
            if (labelled) continue;
            // No frame yet: hang one on a free horizontal face.
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                BlockPos facePos = f.pos().relative(d);
                if (!level().getBlockState(facePos).canBeReplaced()) continue;
                var frame = new net.minecraft.world.entity.decoration.ItemFrame(level(), facePos, d);
                if (!frame.survives()) continue;
                if (removeMatching(t -> t.is(Items.ITEM_FRAME), 1) != 1) return;
                level().addFreshEntity(frame);
                frame.setItem(new ItemStack(main));
                placeSound(f.pos());
                swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                sayRoutine("Labelled the chest — it's mostly "
                    + new ItemStack(main).getHoverName().getString() + ".");
                return;   // one label per pass
            }
        }
    }

    private boolean animalsNearby() {
        return !level().getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class,
            getBoundingBox().inflate(12.0), a -> a.isAlive() && !a.isBaby()).isEmpty();
    }

    private boolean matureCropsNearby() {
        BlockPos feet = feetPos();
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-12, -3, -12), feet.offset(12, 3, 12))) {
            if (!inZone(pos)) continue; // FarmGoal filters by zone; match it
            BlockState st = level().getBlockState(pos);
            if (st.getBlock() instanceof net.minecraft.world.level.block.CropBlock crop
                && crop.isMaxAge(st)) return true;
            if (st.is(Blocks.MELON) || st.is(Blocks.PUMPKIN)) return true;
            if (st.is(Blocks.SUGAR_CANE)
                && level().getBlockState(pos.below()).is(Blocks.SUGAR_CANE)) return true;
        }
        return false;
    }

    private boolean grassNearby() {
        BlockPos feet = feetPos();
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-10, -3, -10), feet.offset(10, 3, 10))) {
            if (!inZone(pos)) continue; // FarmGoal filters by zone; match it
            BlockState st = level().getBlockState(pos);
            if (st.is(Blocks.SHORT_GRASS) || st.is(Blocks.TALL_GRASS) || st.is(Blocks.FERN)) return true;
        }
        return false;
    }

    /** Everything a farmer plants. ONE list, referenced everywhere the code
     *  asks "is this a seed" — five hand-copied versions of it is how melon
     *  seeds end up planted but never restocked. */
    public static final java.util.function.Predicate<ItemStack> FARM_SEEDS = s ->
        s.is(Items.WHEAT_SEEDS) || s.is(Items.BEETROOT_SEEDS) || s.is(Items.CARROT)
        || s.is(Items.POTATO) || s.is(Items.MELON_SEEDS) || s.is(Items.PUMPKIN_SEEDS);

    /** What the smelter turns into proper meals in a quiet spell: raw meat and
     *  fish only. Potatoes are deliberately absent — they are a farmer's SEED
     *  stock, and cooking the seed is how a farm quietly disappears. */
    public static final java.util.function.Predicate<ItemStack> RAW_FOOD = s ->
        s.is(Items.BEEF) || s.is(Items.PORKCHOP) || s.is(Items.CHICKEN)
        || s.is(Items.MUTTON) || s.is(Items.RABBIT) || s.is(Items.COD) || s.is(Items.SALMON);

    private boolean hasSeeds() {
        return countMatching(FARM_SEEDS) > 0;
    }

    /** Nearest water block within the given radius, or null. Lets the bot skip
     *  crafting an irrigation bucket when a natural source is already at hand,
     *  and lets it top up an empty bucket from a pool it walks past. */
    @Nullable
    private BlockPos nearestWater(int radius) {
        BlockPos feet = feetPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                feet.offset(-radius, -2, -radius), feet.offset(radius, 2, radius))) {
            if (!level().getBlockState(pos).is(Blocks.WATER)) continue;
            double d = pos.distSqr(feet);
            if (d < bestDist) { bestDist = d; best = pos.immutable(); }
        }
        return best;
    }

    private boolean waterNearby(int radius) {
        return nearestWater(radius) != null;
    }

    /** Highest-value diamond kit piece still missing, or null when fully diamond. */
    @Nullable
    private String nextDiamondPiece() {
        String[] order = {"diamond_pickaxe", "diamond_sword", "diamond_chestplate",
                          "diamond_helmet", "diamond_leggings", "diamond_boots"};
        for (String id : order) {
            net.minecraft.world.item.Item it = RecipeBook.item(id);
            if (it == Items.AIR) continue;
            if (countCarried(s -> s.is(it)) == 0) return id;
        }
        return null;
    }

    private static int diamondCost(String id) {
        return switch (id) {
            case "diamond_chestplate" -> 8;
            case "diamond_leggings" -> 7;
            case "diamond_helmet" -> 5;
            case "diamond_boots" -> 4;
            case "diamond_pickaxe" -> 3;
            case "diamond_sword" -> 2;
            default -> 1;
        };
    }

    /** Opportunistic enchanting: a table nearby, lapis in the pack, and a level
     *  of banked XP to spend. EnchantGoal handles the fair XP/lapis accounting. */
    private boolean enchantingReady() {
        if (getXp() < 40 || countCarried(s -> s.is(Items.LAPIS_LAZULI)) == 0) return false;
        BlockPos feet = feetPos();
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-8, -3, -8), feet.offset(8, 3, 8))) {
            if (level().getBlockState(pos).is(Blocks.ENCHANTING_TABLE)) return true;
        }
        return false;
    }

    /**
     * Town coordination: if the crew has an active Job Board, each autonomous
     * member self-assigns a role to fill the town's plan — divided labour. AUTO
     * balances to whatever the depot chest beside the board is short on
     * (food/wood/stone); a fixed preset biases the whole crew toward one trade.
     * Members are slotted by name so they don't all pick the same job or thrash.
     */
    private void decideTownRole() {
        if (ownerId == null) return;
        BlockPos board = Town.center(ownerId);
        if (board == null) return;
        BlockState bs = level().getBlockState(board);
        if (!(bs.getBlock() instanceof com.jrpetty.mcassistant.block.JobBoardBlock)) {
            Town.clearCenter(ownerId); // board was broken/replaced — dissolve the town
            return;
        }
        com.jrpetty.mcassistant.block.JobBoardBlock.Preset preset =
            bs.getValue(com.jrpetty.mcassistant.block.JobBoardBlock.PRESET);

        List<AssistantEntity> crew = new ArrayList<>();
        for (AssistantEntity a : allFor(ownerId)) {
            if (a.level() == this.level() && a.blockPosition().closerThan(board, 64.0)) crew.add(a);
        }
        crew.sort(java.util.Comparator.comparing(AssistantEntity::getUUID)); // stable slotting
        int idx = crew.indexOf(this);
        if (idx < 0) return;

        Role[] plan = rolePlan(preset, board);
        Role target = plan[idx % plan.length];
        // Debounce: only actually switch (and announce) at most once a minute, so
        // depot counts wobbling across a threshold don't thrash roles or spam chat.
        if (target != role && tickCount - lastTownRoleTick > 1200) {
            setRole(target);
            lastTownRoleTick = tickCount;
            say("Town duty — I'll work as the " + target.name().toLowerCase() + ".");
        }
        // The foreman keeps the town needs board in sync with the depot.
        if (idx == 0) postDepotDeficits(board);
    }

    private Role[] rolePlan(com.jrpetty.mcassistant.block.JobBoardBlock.Preset preset, BlockPos board) {
        return switch (preset) {
            case MINING -> new Role[] {Role.MINER, Role.MINER, Role.MINER, Role.FARMER, Role.LUMBERJACK};
            case FOOD -> new Role[] {Role.FARMER, Role.FARMER, Role.LUMBERJACK, Role.MINER};
            case BUILD -> new Role[] {Role.BUILDER, Role.MINER, Role.LUMBERJACK, Role.BUILDER, Role.FARMER};
            case BALANCED -> new Role[] {Role.MINER, Role.LUMBERJACK, Role.FARMER, Role.BUILDER};
            case AUTO -> autoPlan(board);
        };
    }

    /** Need-driven plan: assign to cover the depot's biggest shortfalls first. */
    private Role[] autoPlan(BlockPos board) {
        BlockPos depot = findDepotNear(board);
        int food = depotCount(depot, s -> s.get(DataComponents.FOOD) != null);
        int wood = depotCount(depot, s -> s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS));
        int stone = depotCount(depot, s -> s.is(Items.COBBLESTONE) || s.is(Items.STONE));
        int iron = depotCount(depot, s -> s.is(Items.IRON_INGOT) || s.is(Items.RAW_IRON));
        int coal = depotCount(depot, s -> s.is(Items.COAL) || s.is(Items.CHARCOAL));
        List<Role> plan = new ArrayList<>();
        if (food < 16) plan.add(Role.FARMER);
        if (wood < 64) plan.add(Role.LUMBERJACK);
        if (stone < 64 || iron < 16 || coal < 16) plan.add(Role.MINER);
        if (plan.isEmpty()) { // depot's healthy — keep a balanced standing crew
            plan.add(Role.MINER); plan.add(Role.LUMBERJACK); plan.add(Role.FARMER); plan.add(Role.BUILDER);
        } else if (!plan.contains(Role.FARMER)) {
            plan.add(Role.FARMER); // always keep the larder tended
        }
        return plan.toArray(new Role[0]);
    }

    @Nullable
    private BlockPos findDepotNear(BlockPos board) {
        for (ZoneChests.Found found : ZoneChests.around(level(), board, 8, 3)) {
            if (found.stillThere()) return found.pos();
        }
        return null;
    }

    private int depotCount(@Nullable BlockPos depot, java.util.function.Predicate<ItemStack> pred) {
        if (depot == null || !(level().getBlockEntity(depot) instanceof Container c)) return 0;
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (!s.isEmpty() && pred.test(s)) n += s.getCount();
        }
        return n;
    }

    /** The foreman (slot 0) posts the depot's material shortfalls to the town
     *  needs board, so idle members whose trade fits go fetch them. */
    private void postDepotDeficits(BlockPos board) {
        BlockPos depot = findDepotNear(board);
        if (depot == null) return;
        int wood = depotCount(depot, s -> s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS));
        int stone = depotCount(depot, s -> s.is(Items.COBBLESTONE) || s.is(Items.STONE));
        int iron = depotCount(depot, s -> s.is(Items.IRON_INGOT) || s.is(Items.RAW_IRON));
        int coal = depotCount(depot, s -> s.is(Items.COAL) || s.is(Items.CHARCOAL));
        if (wood < 64) Town.postNeed(ownerId, GatherGoal.Kind.LOGS, 64 - wood);
        if (stone < 64) Town.postNeed(ownerId, GatherGoal.Kind.STONE, 64 - stone);
        if (iron < 16) Town.postNeed(ownerId, GatherGoal.Kind.IRON, 16 - iron);
        if (coal < 16) Town.postNeed(ownerId, GatherGoal.Kind.COAL, 16 - coal);
    }

    /** Can I fetch this material given my town role and my pickaxe tier? */
    private boolean canDoAsRole(GatherGoal.Kind kind) {
        boolean toolOk = switch (kind) {
            case LOGS, DIRT, SAND, GRAVEL, SUGAR_CANE -> true;
            case COAL, STONE -> bestPickTier() >= 1;
            case IRON -> bestPickTier() >= 2;
        };
        if (!toolOk) return false;
        return switch (role) {
            case MINER -> kind == GatherGoal.Kind.STONE || kind == GatherGoal.Kind.IRON || kind == GatherGoal.Kind.COAL;
            case LUMBERJACK -> kind == GatherGoal.Kind.LOGS;
            case BUILDER -> kind == GatherGoal.Kind.LOGS || kind == GatherGoal.Kind.STONE;
            case FARMER, NONE -> false; // farmers tend the larder, not the mines
        };
    }

    /** Fulfill the town's most-wanted material my trade can supply — mining iron
     *  for the group, chopping wood, etc. — and haul it to the depot. */
    private boolean decideTownWork() {
        if (ownerId == null) return false;
        GatherGoal.Kind kind = Town.claimNeed(ownerId, this::canDoAsRole, 16);
        if (kind == null) return false;
        if (kind == GatherGoal.Kind.IRON) {
            say("Town needs iron — mining a batch for the depot.");
            enqueue(Job.mine(40)); // collects raw iron on the way down
        } else {
            say("Town needs " + kind.label + " — fetching a batch for the depot.");
            enqueue(Job.gather(kind, 16));
        }
        // Deliver to the town depot specifically, not just whatever chest is
        // nearest when the gather ends — otherwise the depot never fills and the
        // foreman re-posts the same need forever.
        BlockPos board = Town.center(ownerId);
        BlockPos depot = board != null ? findDepotNear(board) : null;
        enqueue(depot != null ? Job.depositAt(depot) : Job.deposit());
        return true;
    }

    /** A blocked member posts what it lacks to the town board (e.g. a crafter
     *  short on iron), so an idle miner goes and gets that specific thing. */
    public void postMaterialNeed(String label) {
        if (ownerId == null || Town.center(ownerId) == null || label == null) return;
        String l = label.toLowerCase();
        GatherGoal.Kind kind =
            l.contains("iron") ? GatherGoal.Kind.IRON
          : l.contains("coal") ? GatherGoal.Kind.COAL
          : (l.contains("cobble") || l.contains("stone")) ? GatherGoal.Kind.STONE
          : (l.contains("log") || l.contains("wood") || l.contains("plank")) ? GatherGoal.Kind.LOGS
          : null;
        if (kind != null) {
            Town.postNeed(ownerId, kind, 16);
            say("Posting to the town board — we need " + kind.label + "; someone grab it.");
        }
    }

    // ------------------------------ food & health ----------------------------

    /** Pick the smartest food to heal with: reserve golden apples for the
     *  critical-HP emergency, avoid the risky stuff (rotten flesh, spider eye,
     *  raw chicken) unless it's all we've got, and eat the LEAST-filling safe
     *  food first so a prime steak isn't wasted on a small heal. */
    private int findFoodSlot() {
        int best = -1, bestNutrition = Integer.MAX_VALUE;
        int riskyFallback = -1;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.get(i);
            if (s.isEmpty()) continue;
            FoodProperties fp = s.get(DataComponents.FOOD);
            if (fp == null) continue;
            if (s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE)) continue; // saved for emergencies
            if (isRiskyFood(s)) { if (riskyFallback < 0) riskyFallback = i; continue; }
            if (fp.nutrition() < bestNutrition) { bestNutrition = fp.nutrition(); best = i; }
        }
        return best >= 0 ? best : riskyFallback;
    }

    private static boolean isRiskyFood(ItemStack s) {
        return s.is(Items.ROTTEN_FLESH) || s.is(Items.SPIDER_EYE)
            || s.is(Items.POISONOUS_POTATO) || s.is(Items.PUFFERFISH)
            || s.is(Items.CHICKEN); // raw chicken can give food poisoning
    }

    public int countFood() {
        return countMatching(s -> s.get(DataComponents.FOOD) != null);
    }

    public int lastDamageTick() {
        return lastDamageTick;
    }

    public boolean isRetreating() {
        return retreating;
    }

    public void setRetreating(boolean retreating) {
        this.retreating = retreating;
    }

    // -------------------------------- job queue -------------------------------

    public void enqueue(Job job) {
        jobs.addLast(job);
    }

    /** Push a job in FRONT of everything — used for interjections like
     *  "pack's full, deposit first, then keep going". */
    public void enqueueFront(Job job) {
        jobs.addFirst(job);
    }

    /** No empty slot left (stacks may have room, but it's time to stash). */
    public boolean isPackFull() {
        for (ItemStack s : inventory) {
            if (s.isEmpty()) return false;
        }
        return true;
    }

    @Nullable
    public Job peekJob() {
        return jobs.peekFirst();
    }

    @Nullable
    public Job pollJob() {
        return jobs.pollFirst();
    }

    public int jobCount() {
        return jobs.size();
    }

    public List<String> jobLabels() {
        List<String> out = new ArrayList<>();
        for (Job j : jobs) out.add(j.label());
        return out;
    }

    public void requestGather(GatherGoal.Kind kind, int amount) {
        enqueue(Job.gather(kind, amount));
    }

    public void requestDeposit() {
        enqueue(Job.deposit());
    }

    public void clearQueue() {
        this.jobs.clear();
        this.taskGen++;
        this.getNavigation().stop();
    }

    public void requestStop() {
        int had = jobs.size();
        clearQueue();
        this.setTarget(null);
        this.setMode(Mode.STAY);
        say(had > 0 ? "Stopping — cleared " + had + " queued job" + (had == 1 ? "" : "s") + "." : "Stopping.");
    }

    public int taskGen() {
        return taskGen;
    }

    /** Goals report how each job went so idle initiative can back off when
     *  the area is tapped out instead of spamming doomed jobs. */
    public void noteJobOutcome(boolean productive) {
        if (productive) {
            idleBackoffUntil = 0; // good outcome — resume initiative right away
        } else if (tickCount - restockedTick < 200) {
            // A dry run right after a restock is not a tapped-out area — it is a
            // run that started empty and has since been handed what it was
            // missing. Backing off here is what kept a re-stocked farmer stood
            // still for another forty seconds with seeds in its pack.
            idleBackoffUntil = 0;
        } else {
            idleBackoffUntil = tickCount + 800; // ~40s cool-off on a dry attempt
        }
    }

    // ---------------------------- standing orders ----------------------------

    public List<StandingOrder> standingOrders() {
        return standingOrders;
    }

    public void addStandingOrder(GatherGoal.Kind kind, int amount) {
        standingOrders.removeIf(o -> o.kind() == kind);
        standingOrders.add(new StandingOrder(kind, Math.max(1, Math.min(Job.MAX_AMOUNT, amount))));
    }

    public int clearStandingOrders() {
        int n = standingOrders.size();
        standingOrders.clear();
        return n;
    }

    // ------------------------------- routines --------------------------------

    public List<Routine> routines() {
        return routines;
    }

    /** Schedule (or re-schedule) a recurring chore. Interval is clamped to a
     *  sane band; the first run happens one interval from now. */
    public void addRoutine(RoutineKind kind, int intervalTicks) {
        int interval = Math.max(1200, Math.min(72000, intervalTicks)); // 1 min .. 1 hour
        routines.removeIf(r -> r.kind == kind);
        routines.add(new Routine(kind, interval, tickCount + interval));
    }

    public int clearRoutines() {
        int n = routines.size();
        routines.clear();
        return n;
    }

    /** When idle, fire at most one due chore, then reset its clock. Keeping it to
     *  one per window stops a backlog of chores from stampeding the queue. */
    private void tickRoutines() {
        for (Routine r : routines) {
            if (tickCount >= r.nextTick) {
                enqueue(r.kind.toJob());
                say("Routine: time to " + r.kind.label + ".");
                r.nextTick = tickCount + r.interval;
                return;
            }
        }
    }

    // ----------------------------- storage memory ----------------------------

    /** Learn what a chest holds (called whenever we touch one). */
    public void rememberChest(BlockPos pos, Container container) {
        Set<String> ids = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack s = container.getItem(i);
            if (!s.isEmpty()) {
                ids.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath());
            }
        }
        chestMemory.put(pos.asLong(), ids);
    }

    /** Put down a carried chest on solid ground beside us — the base depot. */
    private boolean placeChestNearby() {
        if (countMatching(s -> s.is(Items.CHEST)) == 0) return false;
        BlockPos feet = feetPos();
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos p = feet.relative(d);
            if (level().getBlockState(p).canBeReplaced()
                && level().getBlockState(p.below()).isSolid()
                && removeMatching(s -> s.is(Items.CHEST), 1) >= 1) {
                level().setBlockAndUpdate(p, Blocks.CHEST.defaultBlockState());
                return true;
            }
        }
        return false;
    }

    /** Nearest container that (per memory, then live scan) holds a match. */
    @Nullable
    public BlockPos findChestWith(java.util.function.Predicate<ItemStack> what, int radius) {
        // Remembered chests first — verify they still match.
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (Long key : chestMemory.keySet()) {
            BlockPos pos = BlockPos.of(key);
            if (pos.distSqr(blockPosition()) > (double) radius * radius) continue;
            if (ZoneChests.isPrivate(level(), pos)) continue;   // signed: hands off
            if (containerHas(pos, what)) {
                double d = pos.distSqr(blockPosition());
                if (d < bestDist) { bestDist = d; best = pos; }
            }
        }
        if (best != null) return best;
        // Fall back to a live look at the containers that are actually there.
        BlockPos feet = blockPosition();
        for (ZoneChests.Found found : ZoneChests.around(level(), feet, radius, 4)) {
            if (!found.holds(what)) continue;
            double d = found.pos().distSqr(feet);
            if (d < bestDist) { bestDist = d; best = found.pos(); }
        }
        return best;
    }

    private boolean containerHas(BlockPos pos, java.util.function.Predicate<ItemStack> what) {
        BlockEntity be = level().getBlockEntity(pos);
        if (!(be instanceof Container c)) return false;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (!s.isEmpty() && what.test(s)) return true;
        }
        return false;
    }

    /** Nearest chest we REMEMBER (no world scan — safe at long range). */
    @Nullable
    public BlockPos nearestRememberedChest(int maxRadius) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (Long key : chestMemory.keySet()) {
            BlockPos pos = BlockPos.of(key);
            double d = pos.distSqr(blockPosition());
            if (d > (double) maxRadius * maxRadius || d >= bestDist) continue;
            if (level().getBlockEntity(pos) instanceof Container) {
                bestDist = d;
                best = pos;
            }
        }
        return best;
    }

    /** Where a stationed bot looks for its chests: the middle of its patch (or
     *  its post), which is what JobSpec checked when it accepted one. A bot
     *  with no station falls back to wherever it happens to be. */
    public BlockPos stationSearchOrigin() { return chestSearchOrigin(); }

    private BlockPos chestSearchOrigin() {
        if (workZone != null) return workZone.center();
        return stationPos != null ? stationPos : feetPos();
    }

    /** Nearest container of any kind. */
    @Nullable
    public BlockPos findAnyChest(int radius) {
        BlockPos feet = chestSearchOrigin();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (ZoneChests.Found found : radius == chestRange()
                ? linkedChests() : ZoneChests.around(level(), feet, radius, 5)) {
            if (!found.stillThere()) continue;
            double d = found.pos().distSqr(feet);
            if (d < bestDist) { bestDist = d; best = found.pos(); }
        }
        return best;
    }

    // ------------------------------ persistence ------------------------------

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (ownerId != null) tag.putUUID("Owner", ownerId);
        if (homePos != null) tag.putLong("Home", homePos.asLong());
        tag.putString("Mode", mode.name());
        tag.putString("Role", role.name());
        tag.putString("Name", assistantName);
        tag.putBoolean("Auto", autonomous);
        tag.putBoolean("NightHome", nightHome);
        tag.putString("Shift", shift.name());
        CompoundTag deedTag = new CompoundTag();
        for (java.util.Map.Entry<Deed, Integer> e : deeds.entrySet()) {
            deedTag.putInt(e.getKey().name(), e.getValue());
        }
        tag.put("Deeds", deedTag);
        tag.putString("Branch", branch.name());
        if (deathNote != null) tag.putString("DeathNote", deathNote);
        if (presetName != null) tag.putString("Preset", presetName);
        if (deathSite != null) tag.putLong("DeathSite", deathSite.asLong());
        tag.putLong("DeathTime", deathGameTime);
        tag.putString("Perk30", perk30.name());
        tag.putString("PatchName", patchName);
        tag.putBoolean("Quiet", quiet);
        tag.putBoolean("Quarry", quarry);
        tag.putLong("OwnerSeen", ownerSeenGameTime);
        if (escortId != null) tag.putUUID("Escort", escortId);
        tag.putInt("WageIron", ironPaid);
        tag.putInt("WageGold", goldPaid);
        tag.putInt("WageDiamond", diamondPaid);
        tag.putInt("WagesPaid", wagesPaid);
        tag.putInt("Shoulder", shoulderTicks);
        tag.putInt("Diet", dietPercent);
        tag.putString("LastMeal", lastMeal);
        tag.putString("Trait", trait.name());
        net.minecraft.nbt.ListTag spots = new net.minecraft.nbt.ListTag();
        for (long k : richSpots) spots.add(net.minecraft.nbt.LongTag.valueOf(k));
        tag.put("RichSpots", spots);
        tag.putLong("FirstDay", firstServedDay);
        if (bedPos != null) tag.putLong("Bed", bedPos.asLong());
        if (preferredChest != null) tag.putLong("PrefChest", preferredChest.asLong());
        if (deliveryChest != null) tag.putLong("DeliverChest", deliveryChest.asLong());
        tag.putInt("BaseStage", baseStage);
        if (stationPos != null) tag.putLong("StationPos", stationPos.asLong());
        tag.putString("StationTask", stationTask.name());
        if (mobileLoadCenter != null) tag.putLong("MobileLoad", mobileLoadCenter.asLong());
        if (workZone != null) tag.put("WorkZone", workZone.save());
        tag.putInt("Xp", xp);
        tag.putInt("LifeXp", lifetimeXp);
        tag.putBoolean("ExpActive", expeditionActive);
        if (expeditionActive) {
            tag.putInt("ExpPhase", expeditionPhase);
            tag.putString("ExpTarget", expeditionTarget);
            tag.putInt("ExpRemaining", expeditionRemaining);
            if (expeditionReturn != null) tag.putLong("ExpReturn", expeditionReturn.asLong());
        }
        ListTag orders = new ListTag();
        for (StandingOrder o : standingOrders) {
            CompoundTag ot = new CompoundTag();
            ot.putString("Kind", o.kind().name());
            ot.putInt("Amount", o.amount());
            orders.add(ot);
        }
        tag.put("Standing", orders);
        ListTag chores = new ListTag();
        for (Routine r : routines) {
            CompoundTag rt = new CompoundTag();
            rt.putString("Kind", r.kind.name());
            rt.putInt("Interval", r.interval);
            chores.add(rt);
        }
        tag.put("Routines", chores);
        ListTag points = new ListTag();
        for (Map.Entry<String, BlockPos> e : waypoints.entrySet()) {
            CompoundTag wt = new CompoundTag();
            wt.putString("Name", e.getKey());
            wt.putLong("Pos", e.getValue().asLong());
            points.add(wt);
        }
        tag.put("Waypoints", points);
        ContainerHelper.saveAllItems(tag, inventory, this.registryAccess());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID("Owner")) ownerId = tag.getUUID("Owner");
        if (tag.contains("Home")) homePos = BlockPos.of(tag.getLong("Home"));
        try {
            mode = Mode.valueOf(tag.getString("Mode"));
        } catch (IllegalArgumentException ignored) {
            mode = Mode.FOLLOW;
        }
        try {
            role = Role.valueOf(tag.getString("Role"));
        } catch (IllegalArgumentException ignored) {
            role = Role.NONE;
        }
        if (tag.contains("Name")) assistantName = tag.getString("Name");
        if (assistantName.isEmpty()) assistantName = "assistant";
        autonomous = tag.getBoolean("Auto");
        nightHome = tag.getBoolean("NightHome");
        try {
            shift = tag.contains("Shift") ? Shift.valueOf(tag.getString("Shift")) : Shift.DAY;
        } catch (IllegalArgumentException ignored) {
            shift = Shift.DAY;
        }
        bedPos = tag.contains("Bed") ? BlockPos.of(tag.getLong("Bed")) : null;
        preferredChest = tag.contains("PrefChest") ? BlockPos.of(tag.getLong("PrefChest")) : null;
        deliveryChest = tag.contains("DeliverChest") ? BlockPos.of(tag.getLong("DeliverChest")) : null;
        try {
            branch = tag.contains("Branch") ? Branch.valueOf(tag.getString("Branch")) : Branch.NONE;
        } catch (IllegalArgumentException ignored) {
            branch = Branch.NONE;
        }
        firstServedDay = tag.contains("FirstDay") ? tag.getLong("FirstDay") : -1;
        deathNote = tag.contains("DeathNote") ? tag.getString("DeathNote") : null;
        presetName = tag.contains("Preset") ? tag.getString("Preset") : null;
        deathSite = tag.contains("DeathSite") ? BlockPos.of(tag.getLong("DeathSite")) : null;
        deathGameTime = tag.getLong("DeathTime");
        try {
            perk30 = tag.contains("Perk30") ? Perk.valueOf(tag.getString("Perk30")) : Perk.NONE;
        } catch (IllegalArgumentException e) {
            perk30 = Perk.NONE;
        }
        patchName = tag.getString("PatchName");
        quiet = tag.getBoolean("Quiet");
        quarry = tag.getBoolean("Quarry");
        ownerSeenGameTime = tag.getLong("OwnerSeen");
        escortId = tag.hasUUID("Escort") ? tag.getUUID("Escort") : null;
        ironPaid = tag.getInt("WageIron");
        goldPaid = tag.getInt("WageGold");
        diamondPaid = tag.getInt("WageDiamond");
        wagesPaid = tag.getInt("WagesPaid");
        shoulderTicks = tag.getInt("Shoulder");
        dietPercent = tag.contains("Diet") ? tag.getInt("Diet") : 100;
        lastMeal = tag.getString("LastMeal");
        try {
            trait = tag.contains("Trait") ? Trait.valueOf(tag.getString("Trait")) : Trait.NONE;
        } catch (IllegalArgumentException ignored) {
            trait = Trait.NONE;
        }
        richSpots.clear();
        net.minecraft.nbt.ListTag spots = tag.getList("RichSpots", net.minecraft.nbt.Tag.TAG_LONG);
        for (int i = 0; i < spots.size() && i < SPOT_MEMORY; i++) {
            if (spots.get(i) instanceof net.minecraft.nbt.LongTag lt) richSpots.add(lt.getAsLong());
        }
        deeds.clear();
        CompoundTag deedTag = tag.getCompound("Deeds");
        for (Deed d : Deed.values()) {
            if (deedTag.contains(d.name())) deeds.put(d, deedTag.getInt(d.name()));
        }
        baseStage = tag.getInt("BaseStage");
        stationPos = tag.contains("StationPos") ? BlockPos.of(tag.getLong("StationPos")) : null;
        try {
            stationTask = StationTask.valueOf(tag.getString("StationTask"));
        } catch (IllegalArgumentException ignored) {
            stationTask = StationTask.NONE;
        }
        if (stationPos == null) stationTask = StationTask.NONE;
        // The traveling window's tickets persist with the world; restoring the
        // center lets the mover tick free them as usual instead of leaking.
        mobileLoadCenter = tag.contains("MobileLoad") ? BlockPos.of(tag.getLong("MobileLoad")) : null;
        workZone = tag.contains("WorkZone") ? WorkZone.load(tag.getCompound("WorkZone")) : null;
        publishJobState(); // the real requirement scan happens on the first work tick
        xp = tag.getInt("Xp");
        // Seed lifetime xp from banked xp for bots saved before levels existed.
        lifetimeXp = tag.contains("LifeXp") ? tag.getInt("LifeXp") : xp;
        applyLevelPerks();
        expeditionActive = tag.getBoolean("ExpActive");
        if (expeditionActive) {
            expeditionPhase = tag.getInt("ExpPhase");
            expeditionTarget = tag.contains("ExpTarget") ? tag.getString("ExpTarget") : "glowstone";
            expeditionRemaining = tag.getInt("ExpRemaining");
            expeditionReturn = tag.contains("ExpReturn") ? BlockPos.of(tag.getLong("ExpReturn")) : null;
        }
        standingOrders.clear();
        for (Tag t : tag.getList("Standing", Tag.TAG_COMPOUND)) {
            CompoundTag ot = (CompoundTag) t;
            try {
                standingOrders.add(new StandingOrder(
                    GatherGoal.Kind.valueOf(ot.getString("Kind")), ot.getInt("Amount")));
            } catch (IllegalArgumentException ignored) {
            }
        }
        routines.clear();
        for (Tag t : tag.getList("Routines", Tag.TAG_COMPOUND)) {
            CompoundTag rt = (CompoundTag) t;
            try {
                RoutineKind kind = RoutineKind.valueOf(rt.getString("Kind"));
                int interval = Math.max(1200, Math.min(72000, rt.getInt("Interval")));
                // Age resets on load, so seed the next run one interval out.
                routines.add(new Routine(kind, interval, interval));
            } catch (IllegalArgumentException ignored) {
            }
        }
        waypoints.clear();
        for (Tag t : tag.getList("Waypoints", Tag.TAG_COMPOUND)) {
            CompoundTag wt = (CompoundTag) t;
            waypoints.put(wt.getString("Name"), BlockPos.of(wt.getLong("Pos")));
        }
        ContainerHelper.loadAllItems(tag, inventory, this.registryAccess());
    }

    // -------------------------------- behavior --------------------------------

    /** Right-click by the owner opens the management GUI. */
    @Override
    protected net.minecraft.world.InteractionResult mobInteract(Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        // Name tags: vanilla would set a custom name, but this entity rebuilds
        // its own nametag (glyph + level + name + hearts) whenever any of those
        // change, which silently wiped the rename moments later. Take the name
        // properly instead, so tagging a bot actually renames it for good.
        if (held.is(net.minecraft.world.item.Items.NAME_TAG)) {
            Component tagName = held.get(DataComponents.CUSTOM_NAME);
            if (tagName != null && isOwner(player)) {
                if (!this.level().isClientSide) {
                    rename(tagName.getString());
                    lastShownHealth = -1; // force the nametag to rebuild with it
                    say("Call me " + displayNameCap() + " from now on.");
                    if (!player.getAbilities().instabuild) held.shrink(1);
                }
                return net.minecraft.world.InteractionResult.sidedSuccess(this.level().isClientSide());
            }
            return super.mobInteract(player, hand);
        }
        if (held.is(net.minecraft.world.item.Items.LEAD)) {
            return super.mobInteract(player, hand);
        }
        // Stand aside for tools that do their own thing to a bot. Vanilla gives
        // the ENTITY first refusal on a right-click and only offers it to the
        // held item if the entity passed — so opening the pack screen here for
        // every item meant the Work Zone Marker's handler was never reached at
        // all, and right-clicking a bot with the wand silently opened its pack
        // instead of binding it or putting it on the marked plot.
        if (held.getItem() instanceof com.jrpetty.mcassistant.item.ZoneMarkerItem) {
            return net.minecraft.world.InteractionResult.PASS;
        }
        if (!isOwner(player)) {
            if (!this.level().isClientSide) say("You're not my owner.");
            return net.minecraft.world.InteractionResult.sidedSuccess(this.level().isClientSide());
        }
        // Mid-transfer its hands are full. A visible beat — the swing — rather
        // than a pack screen opening over a half-finished chest move, or a
        // click that just silently does nothing.
        if (!transferReady()) {
            if (!this.level().isClientSide) this.swing(InteractionHand.MAIN_HAND);
            return net.minecraft.world.InteractionResult.sidedSuccess(this.level().isClientSide());
        }
        if (!this.level().isClientSide && player instanceof ServerPlayer sp) {
            openManagementScreen(sp);
        }
        return net.minecraft.world.InteractionResult.sidedSuccess(this.level().isClientSide());
    }

    /** Open the pack/management screen — from a right-click, or from the
     *  Orders screen's Pack button. */
    public void openManagementScreen(ServerPlayer player) {
        player.openMenu(
            new net.minecraft.world.SimpleMenuProvider(
                (id, inv, p) -> new com.jrpetty.mcassistant.menu.AssistantMenu(id, inv, this),
                Component.literal(displayNameCap())),
            buf -> buf.writeVarInt(this.getId()));
    }

    @Override
    protected void checkFallDamage(double yDist, boolean onGround, BlockState state, BlockPos pos) {
        // A farmer that ruins the field it is tending is worse than useless:
        // vanilla turns farmland back to dirt when a mob lands on it, so a bot
        // hopping around its own plot was quietly destroying the crops.
        if (onGround && state.is(Blocks.FARMLAND)) {
            this.resetFallDistance();
        }
        super.checkFallDamage(yDist, onGround, state, pos);
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (this.level().isClientSide) return;

        // Registry upkeep (owner -> name -> entity). Only when something it is
        // keyed on actually changed — re-writing the same entry twenty times a
        // second allocated a lower-cased name per tick for no reason.
        if (ownerId != null && (!ownerId.equals(registeredOwner)
                || !assistantName.equals(registeredName))) {
            if (registeredOwner != null && registeredName != null) {
                Map<String, AssistantEntity> old = BY_OWNER.get(registeredOwner);
                if (old != null) old.remove(registeredName.toLowerCase(), this);
            }
            BY_OWNER.computeIfAbsent(ownerId, k -> new ConcurrentHashMap<>())
                .put(assistantName.toLowerCase(), this);
            registeredOwner = ownerId;
            registeredName = assistantName;
        }

        // Towing the route cart: the chest minecart follows its hauler along
        // the rails, pulled by a velocity nudge whenever it lags. The rails
        // project the pull onto the line, so the nudge only ever moves it
        // along the track. A cart that stops gaining ground flags the line
        // stuck for five minutes and the route falls back to feet.
        if (stationTask == StationTask.HAUL && routeCartId >= 0 && tickCount >= railBrokenUntil
            && level().getEntity(routeCartId)
                instanceof net.minecraft.world.entity.vehicle.MinecartChest towed
            && towed.isAlive()) {
            double d2 = towed.distanceToSqr(this);
            if (d2 <= 16.0) {
                cartProgressTick = tickCount;
            } else if (d2 <= 1024.0) {
                net.minecraft.world.phys.Vec3 pull =
                    position().subtract(towed.position()).normalize().scale(0.3);
                towed.setDeltaMovement(pull.x, towed.getDeltaMovement().y, pull.z);
                if (d2 <= 64.0) cartProgressTick = tickCount;   // still with us
                if (tickCount - cartProgressTick > 300) {
                    railBrokenUntil = tickCount + 6000;
                    say("The line's stuck — I'll walk the route till the rails run again.");
                }
            }
        }

        // Active unstuck: once a second, if we WANT to move but barely have, hop
        // and force a fresh path. Clears the corners, gaps, and 1-block lips the
        // pathfinder alone gets wedged on. "Want to move" also covers the case
        // where the pathfinder gave up against a wall while following — otherwise
        // the stuck timer would reset and self-rescue (dig/climb) would never fire.
        if (tickCount % 20 == 0) {
            boolean wantsToMove = !this.getNavigation().isDone();
            if (!wantsToMove && mode == Mode.FOLLOW) {
                Player o = getOwnerPlayer();
                if (o != null && distanceToSqr(o) > 9.0) wantsToMove = true; // trailing, but pinned
            }
            if (wantsToMove && this.position().distanceToSqr(lastPathPos) < 0.25) { // <0.5 block in ~1s
                this.stuckStreak++;
                // One hop clears a lip, a fence post or a step. Hopping on the
                // spot for a minute clears nothing — it is a bot committed to
                // somewhere it cannot reach, and it is the single thing that
                // makes a crew look broken. So: hop only at something a hop
                // could actually get over, and only twice. After that, drop the
                // path and ask the work brain for a different idea.
                // A hard cooldown on hopping. Bounding it per attempt was not
                // enough: giving up reset the counter, the work brain picked the
                // same unreachable target a tick later, and the bot hopped twice
                // more — a permanent hop-hop-pause cycle rather than a fix.
                // At most one hop every three seconds, and only at something a
                // hop could clear.
                if (this.stuckStreak <= 2 && jumpableObstacleAhead() && tryHop()) {
                    this.getNavigation().recomputePath();
                } else if (this.stuckStreak >= 3) {
                    // Give up properly: stop, stand still, and refuse to start
                    // another path for ten seconds. Without the block the brain
                    // simply re-issues the route it just failed at.
                    this.getNavigation().stop();
                    this.setJumping(false);
                    this.moveBlockedUntil = tickCount + 200;
                    this.idleKick = true;      // decide again now, not in 10s
                    this.stuckStreak = 0;
                }
            } else {
                this.stuckStreak = 0;
            }
            this.lastPathPos = this.position();
        }

        // Fireproof reflex: on fire and not already wet -> sprint to water.
        if (this.isOnFire() && !this.isInWaterOrBubble() && tickCount % 10 == 0) {
            BlockPos water = findNearestWater(6);
            if (water != null) {
                this.getNavigation().moveTo(water.getX() + 0.5, water.getY(), water.getZ() + 0.5, 1.6D);
            }
        }

        // Item magnet: pick up loose drops we walk near — mob-kill loot (bone,
        // string, gunpowder, arrows), stray resources — so nothing earned is
        // wasted. Only when working on its own or guarding, so a following bot
        // doesn't hoover up the player's dropped items.
        if ((autonomous || mode == Mode.GUARD) && tickCount % 6 == 0 && !isPackFull()) {
            for (net.minecraft.world.entity.item.ItemEntity drop
                    : level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                        getBoundingBox().inflate(1.4))) {
                if (!drop.isAlive() || drop.hasPickUpDelay()) continue;
                ItemStack left = insertItem(drop.getItem());
                if (left.isEmpty()) drop.discard(); else drop.setItem(left);
            }
        }

        // Proactive lighting: standing somewhere dark with torches on hand and
        // none already nearby -> drop one to keep mobs from spawning around it.
        if (tickCount % 60 == 0 && this.onGround() && getTarget() == null
            && level().getBrightness(net.minecraft.world.level.LightLayer.BLOCK, blockPosition()) < 7
            && countMatching(s -> s.is(Items.TORCH)) > 0 && noTorchNear(5)) {
            placeTorchNearby();
        }

        // A hauler's delivery run crosses terrain its post window doesn't cover —
        // carry a small ticking window with it so unattended trips never stall in
        // an unloaded chunk. Moves when it crosses a chunk border; freed on
        // stand-down/death; heals any overlap with the fixed post window.
        if (stationTask == StationTask.HAUL && tickCount % 40 == 0
            && level() instanceof net.minecraft.server.level.ServerLevel haulLevel) {
            BlockPos here = blockPosition();
            if (mobileLoadCenter == null
                || (mobileLoadCenter.getX() >> 4) != (here.getX() >> 4)
                || (mobileLoadCenter.getZ() >> 4) != (here.getZ() >> 4)) {
                BlockPos old = mobileLoadCenter;
                com.jrpetty.mcassistant.ChunkLoad.setLoaded(haulLevel, getUUID(), here, 1, true);
                if (old != null) {
                    com.jrpetty.mcassistant.ChunkLoad.setLoaded(haulLevel, getUUID(), old, 1, false);
                    // Re-force windows the removal may have clipped (set semantics).
                    com.jrpetty.mcassistant.ChunkLoad.setLoaded(haulLevel, getUUID(), here, 1, true);
                    if (stationPos != null) {
                        com.jrpetty.mcassistant.ChunkLoad.setLoaded(haulLevel, getUUID(), stationPos,
                            stationChunkRadius(stationTask), true);
                    }
                }
                mobileLoadCenter = here.immutable();
            }
        }

        if (isSleeping() && (onShift() || getTarget() != null)) {
            stopSleeping();
        }

        if (tickCount % 5 == 0) avoidStupidDeaths();
        if (tickCount % 5 == 0) tendShield();
        // A hand that hasn't seen you in a game-day looks up and waves when
        // you walk back in. Small, and the base feels lived in.
        if (tickCount % 40 == 0) {
            Player ownerNear = getOwnerPlayer();
            if (ownerNear != null && distanceToSqr(ownerNear) < 24.0 * 24.0) {
                long now = level().getGameTime();
                if (ownerSeenGameTime != 0 && now - ownerSeenGameTime > 24000) {
                    getLookControl().setLookAt(ownerNear, 30.0F, 30.0F);
                    swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                    say("Good to see you back!");
                }
                ownerSeenGameTime = now;
            }
        }
        if (tickCount % 200 == 0) tickTeamwork();
        if (tickCount % 100 == 0) tendKit();
        // Restocking is not discretionary work, so it does not sit behind the
        // idle back-off, the job queue or the station brain. A bot that has run
        // out of seeds is a bot whose next move should be the chest.
        if (tickCount % 40 == 0) topUpKit();
        // And ahead of need: kit trending toward empty gets topped up in an
        // idle window, not at zero with the field half-planted.
        if (tickCount % 200 == 0 && peekJob() == null && getNavigation().isDone()) topUpKitAhead();

        // Stand still when there is nothing to do. Bouncing on the spot looks
        // broken, and on a farm it was destroying the very crops the bot is
        // there to grow (landing on farmland reverts it to dirt).
        if (getNavigation().isDone()) {
            setJumping(false);   // no path, no hopping — whatever else is going on
        }
        if (peekJob() == null && getNavigation().isDone()) {
            // Nothing queued? Do the small useful things a worker would: pick up
            // what's lying about, and light a dark corner of its own patch.
            if (stationTask != StationTask.NONE && autonomous
                && tickCount % (isWatched() ? 60 : 240) == 0) {
                doIdleChores();
            }
        }

        // One line a day, at dawn, on what this specialist actually produced —
        // otherwise a night of chunk-loaded work leaves no trace at all.
        if (stationTask != StationTask.NONE && tickCount % 200 == 0) {
            dailyProductionReport();
        }

        // Keep the screen and the nametag honest. Re-check the checklist often
        // while someone is stood next to this bot — that's exactly when they're
        // handing over the tool it just asked for and watching for the message to
        // clear — and occasionally regardless, so the ⚒/⚠ glyph stays truthful.
        // (Unassigned bots cost nothing here: the check returns immediately.)
        if (tickCount % 40 == 0
            && (tickCount % (isWatched() ? 200 : 600) == 0
                || level().getNearestPlayer(this, 12.0) != null)) {
            refreshJobState();
        }

        // NB: the patch outline is no longer drawn just for holding the wand.
        // A permanently glittering fence line is noise; the wand shows it while
        // you are marking it out, and on demand when you right-click the bot.

        // Top up an empty bucket at any water we pass, so an irrigation source is
        // always on hand for a farm (FarmGoal spends it to hydrate a plot).
        if (autonomous && tickCount % 20 == 0 && getTarget() == null
            && countCarried(s -> s.is(Items.WATER_BUCKET)) == 0) {
            int slot = firstSlot(s -> s.is(Items.BUCKET));
            if (slot >= 0 && waterNearby(2)) {
                inventory.set(slot, new ItemStack(Items.WATER_BUCKET));
                say("Filled my bucket at the water — ready to irrigate.");
            }
        }

        // Queued mode switches apply the instant they reach the head.
        // (GO_HOME and GOTO are real walking jobs now — TravelGoal runs them
        // to completion so "go to the mine THEN gather iron" works in order.)
        Job head = peekJob();
        if (head != null && head.type() == Job.Type.MODE && head.mode() != null) {
            pollJob();
            setAutonomous(false); // an explicit follow/stay/guard step re-attaches it
            setMode(head.mode());
            Player owner = getOwnerPlayer();
            if (head.mode() == Mode.FOLLOW && owner != null) {
                this.getNavigation().moveTo(owner, 1.25D);
            }
            say(switch (head.mode()) {
                case FOLLOW -> "Now following you.";
                case STAY -> "Holding here.";
                case GUARD -> "Guard mode on.";
            });
        }

        // Combat loadout: right weapon for the fight (bow for creepers and
        // distant targets, best melee otherwise).
        if (getTarget() != null && tickCount % 20 == 0) {
            combatTick();
        }

        // Wear the best armor we're carrying (like tools, but for the body).
        if (tickCount % 80 == 0) {
            autoEquipArmor();
        }

        // Companion awareness: warn the owner about danger they might not see.
        if (tickCount % 40 == 0) {
            dangerCallouts();
        }

        // Proactive night defense: well-armored, healthy and armed, an autonomous
        // bot out at night seeks nearby hostiles instead of only reacting — it
        // clears its ground and banks the drops (string, bone, gunpowder) + XP.
        // Creepers are left to the ranged/dodge machinery, not chased into melee.
        if (autonomous && mode != Mode.STAY && !retreating && getTarget() == null
            && this.level().isNight() && tickCount % 40 == 0
            && getArmorValue() >= 15 && getHealth() > getMaxHealth() * 0.75F
            && weaponScore(getMainHandItem()) > 0 && !shouldDisengage()) {
            Monster m = nearestMonster(18.0);
            if (m != null && !(m instanceof Creeper)) {
                setTarget(m);
            }
        }

        // Light the worksite: working in the dark invites mobs onto our head.
        if (tickCount % 60 == 0 && !jobs.isEmpty()) {
            torchIfDark();
        }

        // Off-hand management: totem of undying first (a real second life for
        // mobs too), shield otherwise.
        if (tickCount % 40 == 0) {
            manageOffhand();
        }

        // Night routine: idle workers head home at dusk when asked to.
        if (tickCount % 100 == 0) {
            nightRoutine();
        }

        // Quartermaster: a companion looks after its player. If the owner is
        // close and running on empty while we're carrying spare food, hand some
        // over before doing anything else.
        if (distressCd > 0) distressCd--;
        if (quartermasterCd > 0) quartermasterCd--;
        if (tickCount % 40 == 0 && quartermasterCd == 0 && !retreating && getTarget() == null) {
            Player owner = getOwnerPlayer();
            if (owner instanceof ServerPlayer sp && distanceToSqr(owner) < 256.0
                && sp.getFoodData().getFoodLevel() <= 6 && countFood() >= 8) {
                enqueueFront(Job.give("food", 4));
                say("You're running on empty — here, take some food.");
                quartermasterCd = 600; // ~30s before offering again
            }
        }

        // Emergency: at critical HP scarf a golden apple even mid-fight (regen +
        // absorption), and drink milk to shake off poison/wither.
        if (isAlive() && eatCooldown == 0) {
            if (getHealth() < getMaxHealth() * 0.25F) {
                int g = firstSlot(s -> s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE));
                if (g >= 0) {
                    ItemStack rest = this.eat(this.level(), inventory.get(g));
                    inventory.set(g, rest.isEmpty() ? ItemStack.EMPTY : rest);
                    eatCooldown = 40;
                    say("Critical — eating a golden apple!");
                }
            }
            if (hasHarmfulEffect()) {
                int m = firstSlot(s -> s.is(Items.MILK_BUCKET));
                if (m >= 0) {
                    removeAllEffects();
                    inventory.set(m, new ItemStack(Items.BUCKET));
                    eatCooldown = 20;
                }
            }
        }

        // Eat to heal (player rules: no free lunch). Slow fallback regen when
        // starving so it's never permanently crippled.
        if (eatCooldown > 0) eatCooldown--;
        if (isAlive() && getHealth() < getMaxHealth() && tickCount - lastDamageTick > 100) {
            if (eatCooldown == 0) {
                int slot = findFoodSlot();
                if (slot >= 0) {
                    ItemStack food = inventory.get(slot);
                    FoodProperties fp = food.get(DataComponents.FOOD);
                    ItemStack rest = this.eat(this.level(), food); // vanilla: sound, particles, shrink
                    inventory.set(slot, rest.isEmpty() ? ItemStack.EMPTY : rest);
                    heal(fp != null ? Math.max(2.0F, fp.nutrition()) : 2.0F);
                    eatCooldown = 50;
                } else if (tickCount % 160 == 0) {
                    heal(1.0F);
                }
            }
        }

        // Standing orders: when idle, check stock levels and restock.
        if (!standingOrders.isEmpty() && jobs.isEmpty() && !retreating && tickCount % 600 == 0) {
            checkStandingOrders();
        }

        // Scheduled chores: when idle, run any routine that's come due ("tend
        // the farm every 20 minutes"). Works whether or not autonomy is on —
        // it's an explicit standing instruction — but never interrupts a job.
        if (!routines.isEmpty() && jobs.isEmpty() && !retreating
            && mode != Mode.STAY && tickCount % 40 == 0) {
            tickRoutines();
        }

        // Town coordination: if the crew has a Job Board, self-assign a role.
        if (autonomous && ownerId != null && tickCount % 400 == 0) {
            decideTownRole();
        }

        // Idle initiative: with autonomy on and nothing to do, look after
        // survival first (the 'decide' rung). Crew members on an active town
        // then do their assigned ROLE (their town duty) before any personal
        // advancement; solo bots advance themselves, then do role work.
        boolean restingAtHome = nightHome && this.level().isNight()
            && (parkedForNight || (homePos != null && homePos.distSqr(blockPosition()) < 24 * 24));
        // Autonomy is self-direction: run the idle brain regardless of FOLLOW/STAY/
        // GUARD (an explicit mode command turns autonomy off and re-attaches it).
        int decideEvery = stationTask != StationTask.NONE
            ? com.jrpetty.mcassistant.AssistantConfig.workTickInterval() : 200;
        // Nobody within 64 blocks: look for the NEXT job a third as often. The
        // jobs already queued still run at full speed — output is untouched —
        // this only spaces out the thinking nobody is around to see.
        if (!isWatched()) decideEvery *= 3;
        if (autonomous && jobs.isEmpty() && !retreating && getTarget() == null
            && (idleKick || tickCount % decideEvery == 0)
            && !restingAtHome) {
            idleKick = false;
            boolean townMember = ownerId != null && Town.center(ownerId) != null;
            // Survival ALWAYS runs — never held off by the idle-work cool-off; a
            // hungry / toolless / full-pack bot must be free to act. Only the
            // discretionary tiers (progress / role / town) respect the backoff.
            // A stationed specialist works its post and NOTHING else. Crucially
            // it skips the survival brain: those rungs would send a farmer off
            // hunting for a food stockpile, a fisher off chopping wood, or any
            // of them off crafting a pickaxe they don't need — abandoning the
            // zone the player fenced off. A specialist's kit and upkeep are the
            // player's job, which is exactly what the checklist is for; it still
            // eats to heal, stashes when full, and defends itself.
            if (restIfOffShift()) {
                // Off duty: heading to bed / asleep. Never work through it.
            } else if (stationTask != StationTask.NONE && stationPos != null) {
                if (tickCount >= idleBackoffUntil) decideStation();
            } else if (!decideSurvival()) {
                {
                    if (tickCount >= idleBackoffUntil) {
                        if (townMember && role != Role.NONE) {
                            // Town duty first; if the board has no need and no role work,
                            // still advance ourselves rather than idling.
                            if (tickCount % 400 == 0 && !decideTownWork() && !decideProgress()) {
                                enqueueRoleWork();
                            }
                        } else if (!decideProgress() && tickCount % 400 == 0) {
                            enqueueRoleWork(); // role==NONE now keeps busy too (no more statue)
                        }
                    }
                    // Area tapped out and nothing queued? Relocate to fresh terrain and
                    // find more — even during the post-dry cool-off, since exploring is
                    // exactly the answer to a dry area. Solo bots only (town crew stay put).
                    if (peekJob() == null && !townMember) decideExplore();
                }
            }
        }

        // A bell the moment one NEWLY needs something — you hear the stall from
        // across the base instead of noticing a nametag glyph an hour later.
        boolean alertNow = stationTask != StationTask.NONE
            && (!missingEssentials.isEmpty() || upkeepStalled);
        if (alertNow && !alertWas && tickCount - lastChimeTick > 1200) {
            lastChimeTick = tickCount;
            playSound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BELL.value(), 0.8F, 0.65F);
        }
        alertWas = alertNow;

        // Live nametag: a job glyph you can read across the base — ⚠ means that
        // one is stuck and wants something — then the veteran star, name, hearts.
        // Only rebuilt while someone is around to read it; the moment a player
        // walks back into range it catches up on the next tick.
        int hp = Mth.ceil(getHealth());
        char glyph = stationTask == StationTask.NONE ? ' '
            : (!missingEssentials.isEmpty() || upkeepStalled) ? '⚠' : '⚒';
        String marks = milestoneMarks();
        if (isWatched() && (hp != lastShownHealth || veteranLevel() != lastShownLevel
            || glyph != lastShownGlyph || !marks.equals(lastShownMarks))) {
            lastShownMarks = marks;
            lastShownHealth = hp;
            lastShownLevel = veteranLevel();
            lastShownGlyph = glyph;
            int max = (int) getMaxHealth();
            ChatFormatting color = hp > max * 0.6 ? ChatFormatting.GREEN
                : hp > max * 0.3 ? ChatFormatting.YELLOW
                : ChatFormatting.RED;
            var name = Component.literal("");
            if (glyph != ' ') {
                name.append(Component.literal(glyph + " ").withStyle(
                    glyph == '⚠' ? ChatFormatting.RED : ChatFormatting.AQUA));
            }
            if (lastShownLevel >= 1) {
                name.append(Component.literal("✦" + lastShownLevel + " ").withStyle(ChatFormatting.GOLD));
            }
            name.append(Component.literal(displayNameCap() + " "));
            if (!marks.isEmpty()) {
                name.append(Component.literal(marks + " ").withStyle(ChatFormatting.AQUA));
            }
            name.append(Component.literal(hp + "/" + max + "❤").withStyle(color));
            setCustomName(name);
            setCustomNameVisible(true);
        }
    }

    private void checkStandingOrders() {
        for (StandingOrder o : standingOrders) {
            java.util.function.Predicate<ItemStack> match = kindItemMatcher(o.kind());
            BlockPos chest = findChestWith(s -> true, 24); // any chest = the depot
            if (chest == null) return;
            int stock = 0;
            if (level().getBlockEntity(chest) instanceof Container c) {
                for (int i = 0; i < c.getContainerSize(); i++) {
                    ItemStack s = c.getItem(i);
                    if (!s.isEmpty() && match.test(s)) stock += s.getCount();
                }
                rememberChest(chest, c);
            }
            if (stock < o.amount()) {
                int deficit = Math.min(64, o.amount() - stock);
                say("Standing order: chest is low on " + o.kind().label + " (" + stock + "/" + o.amount() + ") — restocking.");
                enqueue(Job.gather(o.kind(), deficit));
                enqueue(Job.deposit());
                return; // one restock cycle at a time
            }
        }
    }

    public static java.util.function.Predicate<ItemStack> kindItemMatcher(GatherGoal.Kind kind) {
        return switch (kind) {
            case LOGS -> s -> s.is(ItemTags.LOGS);
            case STONE -> s -> s.is(Blocks.COBBLESTONE.asItem()) || s.is(Blocks.STONE.asItem())
                || s.is(Blocks.COBBLED_DEEPSLATE.asItem());
            case DIRT -> s -> s.is(Blocks.DIRT.asItem());
            case IRON -> s -> s.is(net.minecraft.world.item.Items.RAW_IRON)
                || s.is(net.minecraft.world.item.Items.IRON_INGOT);
            case COAL -> s -> s.is(net.minecraft.world.item.Items.COAL);
            case SAND -> s -> s.is(net.minecraft.world.item.Items.SAND)
                || s.is(net.minecraft.world.item.Items.RED_SAND);
            case GRAVEL -> s -> s.is(net.minecraft.world.item.Items.GRAVEL);
            case SUGAR_CANE -> s -> s.is(net.minecraft.world.item.Items.SUGAR_CANE);
        };
    }

    private void enqueueRoleWork() {
        switch (role) {
            case MINER -> {
                say("Nothing queued — mining a bit. (Say \"take a break\" to stop.)");
                enqueue(Job.gather(GatherGoal.Kind.STONE, 16));
                enqueue(Job.deposit());
            }
            case LUMBERJACK -> {
                say("Nothing queued — getting wood. (Say \"take a break\" to stop.)");
                enqueue(Job.gather(GatherGoal.Kind.LOGS, 16));
                enqueue(Job.deposit());
            }
            case FARMER -> {
                say("Nothing queued — tending the crops. (Say \"take a break\" to stop.)");
                enqueue(Job.farm());
                enqueue(Job.deposit());
            }
            case BUILDER -> {
                say("Nothing queued — stocking building materials. (Say \"take a break\" to stop.)");
                enqueue(Job.gather(GatherGoal.Kind.LOGS, 8));
                enqueue(Job.craft("planks", 8));
                enqueue(Job.deposit());
            }
            default -> {
                // No role, but autonomous, idle, and fully self-sufficient: keep
                // busy stockpiling instead of standing still like a statue.
                say("Caught up — stockpiling a bit. (Say \"take a break\" to stop.)");
                enqueue(Job.gather(GatherGoal.Kind.LOGS, 12));
                if (bestPickTier() >= 1) enqueue(Job.gather(GatherGoal.Kind.STONE, 12));
                enqueue(Job.deposit());
            }
        }
    }

    @Nullable public BlockPos getHome() { return homePos; }
    public void setHome(@Nullable BlockPos pos) { this.homePos = pos == null ? null : pos.immutable(); }

    /** Walk back to the home point and hold there. */
    public void goHome() {
        if (homePos == null) {
            say("No home set — right-click an Assistant Spawner, or tell me \"set home here\".");
            return;
        }
        setMode(Mode.STAY);
        this.getNavigation().moveTo(homePos.getX() + 0.5, homePos.getY() + 1, homePos.getZ() + 0.5, 1.1D);
        say("Heading home.");
    }

    @Override
    public void remove(net.minecraft.world.entity.Entity.RemovalReason reason) {
        if (ownerId != null) {
            Map<String, AssistantEntity> m = BY_OWNER.get(ownerId);
            if (m != null) m.remove(assistantName.toLowerCase(), this);
        }
        registeredOwner = null;   // so a re-added entity files itself again
        registeredName = null;
        // Death/dismissal frees the station's force-loaded chunks (fixed post
        // window AND the hauler's traveling window). A plain chunk unload must
        // NOT — staying loaded while parked is the whole point.
        if (reason.shouldDestroy() && level() instanceof net.minecraft.server.level.ServerLevel sl) {
            if (mobileLoadCenter != null) {
                com.jrpetty.mcassistant.ChunkLoad.setLoaded(sl, getUUID(), mobileLoadCenter, 1, false);
                mobileLoadCenter = null;
            }
            if (stationPos != null) {
                com.jrpetty.mcassistant.ChunkLoad.setLoaded(sl, getUUID(), stationPos,
                    stationChunkRadius(stationTask), false);
            }
        }
        super.remove(reason);
    }

    /** Melee reach like a player's ~3 blocks, instead of the stubby mob default. */
    @Override
    protected net.minecraft.world.phys.AABB getAttackBoundingBox() {
        return super.getAttackBoundingBox().inflate(1.25, 0.0, 1.25);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean fromOwner = source.getEntity() instanceof Player p && isOwner(p);
        // A held shield soaks half of frontal hits (and wears down doing it).
        if (!fromOwner && source.getEntity() != null && amount > 0
            && getItemBySlot(EquipmentSlot.OFFHAND).is(Items.SHIELD)) {
            net.minecraft.world.phys.Vec3 toAttacker =
                source.getEntity().position().subtract(this.position());
            if (toAttacker.lengthSqr() > 0.001
                && toAttacker.normalize().dot(this.getViewVector(1.0F)) > 0.0) {
                amount *= 0.5F;
                getItemBySlot(EquipmentSlot.OFFHAND).hurtAndBreak(1, this, EquipmentSlot.OFFHAND);
            }
        }
        boolean took = super.hurt(source, fromOwner ? amount * 0.5F : amount);
        if (took) this.lastDamageTick = this.tickCount;
        // Distress call: taking real damage from a hostile while actually in
        // trouble rallies nearby crewmates to come fight it off.
        if (took && !this.level().isClientSide && distressCd == 0
            && source.getEntity() instanceof Monster foe && foe.isAlive()
            && (getHealth() < getMaxHealth() * 0.6F || threatCount(8.0) >= 2)) {
            callForHelp(foe);
            distressCd = 100; // ~5s between shouts
        }
        return took;
    }

    /** Rally the crew: idle, able crewmates within earshot drop what they're
     *  doing and come defend against the attacker. Teamwork under fire. */
    private void callForHelp(Monster attacker) {
        if (ownerId == null) return;
        int rallied = 0;
        for (AssistantEntity mate : allFor(ownerId)) {
            if (mate == this || !mate.isAlive()) continue;
            if (mate.distanceToSqr(this) > 32.0 * 32.0) continue; // out of earshot
            if (mate.respondToDistress(this, attacker)) rallied++;
        }
        if (rallied > 0) say("Under attack — " + rallied + " coming to help!");
    }

    /** Answer a crewmate's distress call. Returns true if we actually turned to
     *  help (idle, healthy enough, not on hold, not already fighting). */
    public boolean respondToDistress(AssistantEntity ally, Monster attacker) {
        if (retreating || mode == Mode.STAY) return false;
        if (getTarget() != null || attacker == null || !attacker.isAlive()) return false;
        if (getHealth() < getMaxHealth() * 0.35F) return false; // too hurt to spare
        equipBestWeapon();
        setTarget(attacker);
        this.getNavigation().moveTo(attacker, 1.3D);
        say("On my way — hold on, " + ally.displayNameCap() + "!");
        return true;
    }

    /** Drop the backpack AND worn gear on the ground — used on death and on
     *  dismiss, so nothing the player gave it is ever lost. */
    public void dropEverything() {
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.get(i);
            if (!s.isEmpty()) {
                this.spawnAtLocation(s);
                inventory.set(i, ItemStack.EMPTY);
            }
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack s = this.getItemBySlot(slot);
            if (!s.isEmpty()) {
                this.spawnAtLocation(s);
                this.setItemSlot(slot, ItemStack.EMPTY);
            }
        }
    }

    @Override
    protected void dropCustomDeathLoot(net.minecraft.server.level.ServerLevel level, DamageSource source, boolean hitByPlayer) {
        super.dropCustomDeathLoot(level, source, hitByPlayer);
        dropEverything();
        // The bot's SELF survives as a Memory Core: right-click the ground with
        // it and this exact companion comes back — name, level, role, waypoints,
        // and station. Its gear stays here where it fell.
        // Write the epitaph before the core, so revival has a story attached.
        String killer = source.getEntity() != null
            ? source.getEntity().getName().getString()
            : source.getMsgId();
        String where = blockPosition().getX() + " " + blockPosition().getY() + " " + blockPosition().getZ();
        deathNote = "Fell to " + killer + " at " + where
            + " on day " + (level().getDayTime() / 24000L);
        deathSite = blockPosition().immutable();
        deathGameTime = level().getGameTime();
        Player owner = getOwnerPlayer();
        if (owner != null) {
            owner.sendSystemMessage(Component.literal("<" + displayNameCap() + "> " + deathNote
                + ". My Memory Core is here.").withStyle(ChatFormatting.RED));
        }

        ItemStack core = new ItemStack(com.jrpetty.mcassistant.McAssistantMod.MEMORY_CORE.get());
        core.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
            net.minecraft.world.item.component.CustomData.of(writeMemoryCore()));
        core.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
            Component.literal("Memory Core — " + displayNameCap()));
        var drop = new net.minecraft.world.entity.item.ItemEntity(
            level, getX(), getY() + 0.5, getZ(), core);
        drop.setExtendedLifetime(); // don't let the bot's self despawn in 5 minutes
        level.addFreshEntity(drop);
        say("...my Memory Core is where I fell (" + blockPosition().getX() + " "
            + blockPosition().getY() + " " + blockPosition().getZ() + ") — bring me back.");
    }

    /** Everything that makes this bot ITSELF (not its gear), for the Memory Core. */
    private CompoundTag writeMemoryCore() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", assistantName);
        tag.putString("Role", role.name());
        tag.putInt("Xp", xp);
        tag.putInt("LifeXp", lifetimeXp);
        tag.putBoolean("NightHome", nightHome);
        tag.putBoolean("Auto", autonomous);
        if (deathNote != null) tag.putString("DeathNote", deathNote);
        tag.putString("Branch", branch.name());
        if (presetName != null) tag.putString("Preset", presetName);
        if (deathSite != null) tag.putLong("DeathSite", deathSite.asLong());
        tag.putLong("DeathTime", deathGameTime);
        tag.putString("Perk30", perk30.name());
        tag.putString("PatchName", patchName);
        tag.putBoolean("Quiet", quiet);
        tag.putBoolean("Quarry", quarry);
        tag.putLong("OwnerSeen", ownerSeenGameTime);
        if (escortId != null) tag.putUUID("Escort", escortId);
        tag.putInt("WageIron", ironPaid);
        tag.putInt("WageGold", goldPaid);
        tag.putInt("WageDiamond", diamondPaid);
        tag.putInt("WagesPaid", wagesPaid);
        tag.putInt("Shoulder", shoulderTicks);
        tag.putInt("Diet", dietPercent);
        tag.putString("LastMeal", lastMeal);
        tag.putString("Trait", trait.name());
        net.minecraft.nbt.ListTag spots = new net.minecraft.nbt.ListTag();
        for (long k : richSpots) spots.add(net.minecraft.nbt.LongTag.valueOf(k));
        tag.put("RichSpots", spots);
        tag.putLong("FirstDay", firstServedDay);
        if (homePos != null) tag.putLong("Home", homePos.asLong());
        if (stationPos != null && stationTask != StationTask.NONE) {
            tag.putLong("StationPos", stationPos.asLong());
            tag.putString("StationTask", stationTask.name());
        }
        if (workZone != null) tag.put("WorkZone", workZone.save());
        ListTag points = new ListTag();
        for (Map.Entry<String, BlockPos> e : waypoints.entrySet()) {
            CompoundTag wt = new CompoundTag();
            wt.putString("Name", e.getKey());
            wt.putLong("Pos", e.getValue().asLong());
            points.add(wt);
        }
        tag.put("Waypoints", points);
        return tag;
    }

    /** Revival: restore the identity a Memory Core carries — the same bot,
     *  minus the gear that died with it. */
    public void applyMemoryCore(CompoundTag tag) {
        rename(tag.getString("Name"));
        try {
            role = Role.valueOf(tag.getString("Role"));
        } catch (IllegalArgumentException ignored) {
            role = Role.NONE;
        }
        xp = tag.getInt("Xp");
        lifetimeXp = tag.getInt("LifeXp");
        nightHome = tag.getBoolean("NightHome");
        try {
            shift = tag.contains("Shift") ? Shift.valueOf(tag.getString("Shift")) : Shift.DAY;
        } catch (IllegalArgumentException ignored) {
            shift = Shift.DAY;
        }
        bedPos = tag.contains("Bed") ? BlockPos.of(tag.getLong("Bed")) : null;
        preferredChest = tag.contains("PrefChest") ? BlockPos.of(tag.getLong("PrefChest")) : null;
        deliveryChest = tag.contains("DeliverChest") ? BlockPos.of(tag.getLong("DeliverChest")) : null;
        try {
            branch = tag.contains("Branch") ? Branch.valueOf(tag.getString("Branch")) : Branch.NONE;
        } catch (IllegalArgumentException ignored) {
            branch = Branch.NONE;
        }
        firstServedDay = tag.contains("FirstDay") ? tag.getLong("FirstDay") : -1;
        deathNote = tag.contains("DeathNote") ? tag.getString("DeathNote") : null;
        presetName = tag.contains("Preset") ? tag.getString("Preset") : null;
        deathSite = tag.contains("DeathSite") ? BlockPos.of(tag.getLong("DeathSite")) : null;
        deathGameTime = tag.getLong("DeathTime");
        try {
            perk30 = tag.contains("Perk30") ? Perk.valueOf(tag.getString("Perk30")) : Perk.NONE;
        } catch (IllegalArgumentException e) {
            perk30 = Perk.NONE;
        }
        patchName = tag.getString("PatchName");
        quiet = tag.getBoolean("Quiet");
        quarry = tag.getBoolean("Quarry");
        ownerSeenGameTime = tag.getLong("OwnerSeen");
        escortId = tag.hasUUID("Escort") ? tag.getUUID("Escort") : null;
        ironPaid = tag.getInt("WageIron");
        goldPaid = tag.getInt("WageGold");
        diamondPaid = tag.getInt("WageDiamond");
        wagesPaid = tag.getInt("WagesPaid");
        shoulderTicks = tag.getInt("Shoulder");
        dietPercent = tag.contains("Diet") ? tag.getInt("Diet") : 100;
        lastMeal = tag.getString("LastMeal");
        try {
            trait = tag.contains("Trait") ? Trait.valueOf(tag.getString("Trait")) : Trait.NONE;
        } catch (IllegalArgumentException ignored) {
            trait = Trait.NONE;
        }
        richSpots.clear();
        net.minecraft.nbt.ListTag spots = tag.getList("RichSpots", net.minecraft.nbt.Tag.TAG_LONG);
        for (int i = 0; i < spots.size() && i < SPOT_MEMORY; i++) {
            if (spots.get(i) instanceof net.minecraft.nbt.LongTag lt) richSpots.add(lt.getAsLong());
        }
        deeds.clear();
        CompoundTag deedTag = tag.getCompound("Deeds");
        for (Deed d : Deed.values()) {
            if (deedTag.contains(d.name())) deeds.put(d, deedTag.getInt(d.name()));
        }
        if (tag.contains("Home")) setHome(BlockPos.of(tag.getLong("Home")));
        waypoints.clear();
        for (Tag t : tag.getList("Waypoints", Tag.TAG_COMPOUND)) {
            CompoundTag wt = (CompoundTag) t;
            waypoints.put(wt.getString("Name"), BlockPos.of(wt.getLong("Pos")));
        }
        applyLevelPerks();
        setHealth(getMaxHealth());
        setAutonomous(tag.getBoolean("Auto"));
        workZone = tag.contains("WorkZone") ? WorkZone.load(tag.getCompound("WorkZone")) : null;
        if (tag.contains("StationPos")) {
            StationTask st = StationTask.NONE;
            try {
                st = StationTask.valueOf(tag.getString("StationTask"));
            } catch (IllegalArgumentException ignored) { }
            if (st != StationTask.NONE) setStation(BlockPos.of(tag.getLong("StationPos")), st);
        }
        lastShownHealth = -1; // refresh the nametag (level star and all)
        refreshJobState();
        if (deathNote != null) say("I remember how it ended: " + deathNote + ".");
        say("Back from the void — level " + veteranLevel() + ", and I remember everything. "
            + "My old gear died with me, though"
            + (stationTask != StationTask.NONE ? " — heading back to my station." : "."));
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    public BlockPos feetPos() {
        return this.blockPosition();
    }
}
