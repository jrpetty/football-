package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.ServiceHours;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Set;

/**
 * [earning] The paid shifts a player may work in a town (Shifts): each role's name and its senior post, its hours, the
 * wage a task, the par time a task is measured against, what it needs of the town, the folk who boss it, and its
 * "how this job works" card. Data: a new job is a line here and its tasks in ShiftTasks.
 */
public enum ShiftRole {
    COUNTER("counter", "Counter hand", "Head of the counter", "the counter", 1.0, 240,
        "a shop, café or tavern with a service counter", Set.of(StationTask.SHOP, StationTask.COOK),
        List.of("Stand behind the counter and right-click it to open the counter screen.",
            "Customers queue in front. Serve the one first in line from the Work tab.",
            "Quick service earns stars; serving out of turn is a mistake.",
            "Each customer served pays at once; happy customers tip.")),
    WAITER("waiter", "Waiter", "Head waiter", "the dining room", 1.0, 400,
        "a café, tavern or restaurant", Set.of(StationTask.COOK),
        List.of("A plate ready on the pass shows on your card with its table.",
            "Right-click the pass to pick the plate up, then right-click its table to set it down.",
            "The wrong table is a mistake; a dropped or eaten plate goes back to the kitchen unpaid.",
            "Diners tip a quick, careful waiter.")),
    COOK("cook", "Cook", "Head cook", "the kitchen", 1.5, 500,
        "a café, tavern or restaurant with orders coming in", Set.of(StationTask.COOK),
        List.of("A ticket shows the dish and the station to cook it at.",
            "Right-click the station to put it on; the card counts down until it is done.",
            "Right-click again once it says READY: too soon and it is not done, too late and it burns.",
            "The makings come out of the town's stores; a burnt dish pays nothing.")),
    FARM("farm", "Farm hand", "Farm foreman", "the fields", 0.5, 300,
        "ripe crops in the town's fields", Set.of(StationTask.FARM),
        List.of("Ripe plots are marked with green sparks.",
            "Break the crop: the harvest goes straight into the town's stores, and you are handed the seed.",
            "Plant the seed back on the same plot to finish the task.",
            "A plot left bare costs stars.")),
    HAUL("haul", "Hauler", "Head hauler", "the storehouse", 2.0, 900,
        "a storehouse, and a workshop short of its makings", Set.of(StationTask.HAUL, StationTask.STORE),
        List.of("Your card lists the goods, where to fetch them and where they go.",
            "Right-click the storehouse to load up: a small deposit is held for the load.",
            "Right-click the marked chest to unload. The deposit comes back with your pay.",
            "Short loads are mistakes; a load never delivered keeps the deposit.")),
    SMELT("smelt", "Smelter", "Master smelter", "the smithy", 0.4, 1800,
        "raw ore in the stores", Set.of(StationTask.SMITH, StationTask.SMELT),
        List.of("Collect the raw ore at the storehouse (a deposit is held for it).",
            "Smelt it in any furnace with your own fuel.",
            "Bring the ingots to the marked chest. Pay is by the ingot, and the deposit comes back.",
            "Fewer ingots than ore is a mistake.")),
    WATCH("watch", "Night watch", "Captain of the watch", "the town at night", 1.5, 400,
        "night time", Set.of(StationTask.GUARD),
        List.of("Walk the round: each marked post in turn.",
            "Kill any monster in the town as you go: each one pays, and the bounty on top.",
            "The faster round earns more stars.",
            "The watch ends at dawn.")),
    BUILD("build", "Builder's hand", "Foreman", "a building of the town's", 0.6, 600,
        "a town building with blocks missing", Set.of(StationTask.NONE),
        List.of("Collect the blocks at the storehouse (a deposit is held for them).",
            "Place each one on its marked spot: it is set the way the drawing has it.",
            "A block in the wrong spot is refused and counts as a mistake.",
            "Pay is by the block; the deposit comes back when they are all in.")),
    // [roles-a] The healer's assistant: the round of the laid-up, with the stores' remedies (Health.tendByHand).
    HEALER("heal", "Healer's assistant", "Infirmary nurse", "the infirmary", 0.8, 500,
        "an infirmary, and somebody laid up", Set.of(StationTask.HEAL),
        List.of("A patient laid up shows on your card: at the infirmary or at home, marked.",
            "Go to the bedside: standing by it, you give the best remedy the stores have (honey, a bandage, a potion).",
            "With nothing in the stores you sit with them a while, which helps a little.",
            "A quick round earns stars; each patient seen to pays at once.")),
    // [roles-d] The woodcutter's hand: a grown tree of the town's wood felled, its logs to the stores, a sapling set back
    // (OutdoorShifts).
    WOOD("wood", "Woodcutter's hand", "Head woodsman", "the woods", 0.8, 900,
        "grown trees in the town's wood", Set.of(StationTask.WOOD),
        List.of("A grown tree in the town's wood is marked.",
            "Fell it, every log of it: the logs go straight into the town's stores.",
            "Then plant a sapling where it stood: one from the stores is handed you if they have one.",
            "A tree left unplanted costs stars.")),
    // [roles-d] The shearer: a sheep of the town's flock shorn with the player's own shears, the wool to the stores.
    SHEAR("shear", "Shearer", "Head shepherd", "the pen", 0.4, 300,
        "sheep of the town's flock in wool", Set.of(StationTask.RANCH),
        List.of("A sheep of the town's flock is marked.",
            "Shear it with your own shears: the wool goes straight into the town's stores.",
            "Shearing one of the flock that is not marked is a mistake.",
            "A quick, clean job earns stars.")),
    // [roles-c] The baker's hand: a batch of what the stores are shortest of, into the bakery's oven and out before it
    // burns, the makings out of the stores and the bake into them (HospitalityShifts).
    BAKE("bake", "Baker's hand", "Master baker", "the bakery", 1.2, 600,
        "a bakery, and bread or cakes short in the stores", Set.of(StationTask.COOK),
        List.of("A batch the stores are short of shows on your card: bread, cookies, a pie or a cake.",
            "Right-click the bakery's oven to put it in: its makings come out of the town's stores.",
            "Right-click again once it says READY: too soon and it is not done, too late and it burns.",
            "The bake goes into the town's stores; a burnt batch pays nothing."));

    public final String id, title, senior, place;
    /** The wage a task, in coin, before the town's pay level, the stars and the skill. */
    public final double wage;
    /** The par time of a task, in ticks (the actual task may set its own from the distances). */
    public final int par;
    /** What the town must have for the job to be offered, in words. */
    public final String needs;
    /** The folk who boss it (asked in talk). */
    public final Set<StationTask> bosses;
    /** The "how this job works" card. */
    public final List<String> howTo;

    ShiftRole(String id, String title, String senior, String place, double wage, int par, String needs, Set<StationTask> bosses,
              List<String> howTo) {
        this.id = id;
        this.title = title;
        this.senior = senior;
        this.place = place;
        this.wage = wage;
        this.par = par;
        this.needs = needs;
        this.bosses = bosses;
        this.howTo = howTo;
    }

    /** The day's hours this job keeps (the counters and dining rooms their own). */
    public ServiceHours hours(@Nullable ServiceRole served) {
        if (this == WATCH) return new ServiceHours(13000, 23000);
        if ((this == COUNTER || this == WAITER || this == COOK) && served != null) return served.hours;
        return new ServiceHours(1000, 12000);
    }

    @Nullable
    public static ShiftRole of(String id) {
        for (ShiftRole r : values()) if (r.id.equals(id)) return r;
        return null;
    }
}
