package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Chronicle;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A village's history, bound as a book: its name and founding, the state it is in
 * now, and then everything that happened, day by day — every building, every age,
 * every birth and death and dream come true, and what players did for it.
 */
public final class Chronicles {

    private Chronicles() {}

    private static final int LINES_PER_PAGE = 12;
    private static final int CHARS_PER_LINE = 19;

    /** The village register: everybody who lives there, who they are and how they are. */
    public static ItemStack register(UUID village, long today) {
        String name = Villages.name(village);
        List<Filterable<Component>> pages = new ArrayList<>();
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) if (a instanceof VillageFolkEntity f) folk.add(f);
        folk.sort(java.util.Comparator.comparing(VillageFolkEntity::displayNameCap));
        String elder = Villages.elderName(village);
        pages.add(Filterable.passThrough(Component.literal(name + "\n\nThe Register\n\n" + folk.size() + " people"
            + (elder.isEmpty() ? "" : "\nElder: " + elder) + "\n\nWritten on day " + today + ".")));
        StringBuilder page = new StringBuilder();
        int used = 0;
        for (VillageFolkEntity f : folk) {
            Persona me = f.persona();
            Social.Life life = f.life();
            StringBuilder one = new StringBuilder("§l").append(f.displayNameCap()).append("§r");
            if (f.isElder()) one.append(" (elder)");
            one.append("\n").append(f.isBaby() ? "a child" : f.stationTask().title.toLowerCase(java.util.Locale.ROOT));
            if (life.rolled()) one.append(", ").append(life.traitsLabel().toLowerCase(java.util.Locale.ROOT));
            if (life.partner() != null) one.append(". Partner: ").append(life.partnerName());
            if (life.children() > 0) one.append(". ").append(life.children()).append(life.children() == 1 ? " child" : " children");
            if (me.rolled()) {
                one.append(". Loves ").append(me.hobby().word).append(". Feels ").append(Persona.moodWord(me.mood()));
                one.append(me.ambitionMet() ? ". Dream come true." : ".");
            } else {
                one.append('.');
            }
            String entry = one.toString();
            int cost = (entry.replace("§l", "").replace("§r", "").length() + CHARS_PER_LINE - 1) / CHARS_PER_LINE + 1;
            if (used + cost > LINES_PER_PAGE + 1 && page.length() > 0) {
                pages.add(Filterable.passThrough(Component.literal(page.toString())));
                page.setLength(0);
                used = 0;
                if (pages.size() >= 99) break;
            }
            page.append(entry).append("\n\n");
            used += cost;
        }
        if (page.length() > 0 && pages.size() < 100) pages.add(Filterable.passThrough(Component.literal(page.toString())));
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        String title = (name + " Register").length() <= 32 ? name + " Register" : name;
        book.set(DataComponents.WRITTEN_BOOK_CONTENT,
            new WrittenBookContent(Filterable.passThrough(title), "the elder of " + name, 0, pages, true));
        return book;
    }

    public static ItemStack book(UUID village, long today) {
        String name = Villages.name(village);
        List<Filterable<Component>> pages = new ArrayList<>();
        long founded = Chronicle.foundedOn(village);
        StringBuilder front = new StringBuilder();
        front.append(name).append("\n\nA Chronicle\n\n");
        if (founded >= 0) front.append("Founded on day ").append(founded).append(".\n");
        front.append("Now in ").append(Villages.ageOf(village).label).append(", ")
            .append(Villages.headcount(village)).append(" people.\n");
        int renown = Villages.renown(village);
        if (renown > 0) front.append(renown).append(renown == 1 ? " great work.\n" : " great works.\n");
        front.append("\nWritten on day ").append(today).append('.');
        pages.add(Filterable.passThrough(Component.literal(front.toString())));

        List<String> lines = new ArrayList<>();
        long lastDay = Long.MIN_VALUE;
        for (Chronicle.Entry e : Chronicle.of(village)) {
            if (e.day() != lastDay) {
                lines.add("§lDay " + e.day() + "§r");
                lastDay = e.day();
            }
            String text = Character.toUpperCase(e.text().charAt(0)) + e.text().substring(1) + ".";
            lines.add(text);
        }
        if (lines.isEmpty()) lines.add("Nothing has happened here yet.");
        StringBuilder page = new StringBuilder();
        int used = 0;
        for (String line : lines) {
            int cost = Math.max(1, (line.replace("§l", "").replace("§r", "").length() + CHARS_PER_LINE - 1) / CHARS_PER_LINE);
            if (used + cost > LINES_PER_PAGE && page.length() > 0) {
                pages.add(Filterable.passThrough(Component.literal(page.toString())));
                page.setLength(0);
                used = 0;
                if (pages.size() >= 90) break;
            }
            page.append(line).append('\n');
            used += cost;
        }
        if (page.length() > 0 && pages.size() < 100) pages.add(Filterable.passThrough(Component.literal(page.toString())));

        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        String title = (name + " Chronicle").length() <= 32 ? name + " Chronicle" : name;
        book.set(DataComponents.WRITTEN_BOOK_CONTENT,
            new WrittenBookContent(Filterable.passThrough(title), "the people of " + name, 0, pages, true));
        return book;
    }
}
