package com.jrpetty.mcassistant.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;

import java.util.ArrayList;
import java.util.List;

/**
 * The Assistant Handbook — a real written book handed over with a player's
 * first specialist, so the whole system explains itself inside the game
 * instead of living in a text file somewhere else. Kept deliberately short:
 * enough to get someone from "I placed a block" to "my farm runs itself".
 */
public final class AssistantHandbook {

    public static final String TITLE = "Assistant Handbook";

    private AssistantHandbook() {}

    private static final String[] PAGES = {
        """
        Assistant Handbook

        Place a spawner and a specialist appears.

        RIGHT-CLICK it to open its screen, pick a job with the arrows, and give it the tools it asks for.

        That is the whole thing.""",

        """
        THE SCREEN

        < Job >
        The arrows pick the job. The middle button names it - click that to start or pause the work.

        Follow / Stay
        Stash
        - / + dig depth (miner)
        Drop (hauler)""",

        """
        THE TWO LINES

        Top: the job and the patch it works.

        Bottom: how it is doing.

        Green - working
        Amber - setting up
        Red - blocked

        A red line names exactly what it still needs.""",

        """
        JOBS 1/2

        Farmer
        a hoe, a chest

        Lumberjack
        an axe, a chest

        Miner
        a pickaxe, 8 torches, a chest

        Rancher
        shears, a chest

        Guard
        a sword, 8 torches""",

        """
        JOBS 2/2

        Smelter
        a furnace in the zone, fuel, a chest

        Fisher
        a rod, water, a chest

        Storekeeper
        two chests

        Hauler
        a chest, and a drop-off set with the Drop button""",

        """
        WORK ZONES

        A bot claims the ground around itself, so you need nothing else.

        To pick a different patch, craft a Zone Marker: redstone over two sticks.

        Click two corners, click more blocks to grow it, then right-click the bot.""",

        """
        RUNNING COSTS

        A working specialist eats 1 food every 2 or 3 minutes, and burns 1 redstone every 10.

        It takes both from its own pack or the chests in its zone.

        Run either dry and it downs tools and says so.""",

        """
        YOUR CREW

        Right-click a Job Board for the whole roster: who is working, who is stuck, how far away they are, and what each one made today.

        On the nametag:
        aqua - working
        red - needs something""",

        """
        LEVELS AND DEATH

        Bots level up slowly as they work: faster hands, more hearts, quicker feet.

        If one dies it drops a Memory Core. Right-click the ground with that and the same bot comes back."""
    };

    public static ItemStack create() {
        List<Filterable<Component>> pages = new ArrayList<>(PAGES.length);
        for (String page : PAGES) {
            pages.add(Filterable.passThrough(Component.literal(page)));
        }
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT,
            new WrittenBookContent(Filterable.passThrough(TITLE), "MC Assistant", 0, pages, true));
        return book;
    }

    /** Already carrying one? Don't bury the player in duplicate books. */
    public static boolean carriedBy(Player player) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            WrittenBookContent content = inv.getItem(i).get(DataComponents.WRITTEN_BOOK_CONTENT);
            if (content != null && TITLE.equals(content.title().raw())) return true;
        }
        return false;
    }

    /** Hand one over (dropping it at their feet if their pack is full). */
    public static void giveTo(Player player) {
        if (carriedBy(player)) return;
        ItemStack book = create();
        if (!player.getInventory().add(book)) {
            player.drop(book, false);
        }
    }
}
