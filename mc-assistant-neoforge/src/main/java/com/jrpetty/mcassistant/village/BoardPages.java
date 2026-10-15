package com.jrpetty.mcassistant.village;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * [folk-ui] The town's books' pages and how the board groups them. A page is known by its number and its name, as ever:
 * {@code /village stats <number>}, the smoke test's page numbers and the commands that open the books at a page by name
 * (Trade, War map, School...) all still open the same page. The board (client/CityScreen) shows them in a handful of
 * sections down its left, each holding its pages as tabs; the section follows from the page. Kept here, apart from the
 * screen, so the server's tests can check every page is in a section and every name other code uses opens a page.
 */
public final class BoardPages {

    private BoardPages() {}

    // Research sits after the stores, before Why: the pages after it are one further on (/village stats <page>
    // and the smoke test's page lists follow the same numbers).
    public static final String[] PAGES = { "Overview", "Growth", "Money", "Production", "Shops", "Jobs", "Folk", "Society", "Leader", "Homes",
        "Buildings", "Stores", "Stock", "Research", "Why", "Trends", "Records", "News", "Board",
        // The school and the museum, last, so the pages before them keep their numbers (School, Museum).
        "School", "Museum",
        // [batchD] The town's culture, after them: its banner, motto, customs, theatre, music, pictures and plaques (CulturePage).
        "Culture",
        // [econ-prices] The town's prices, after them (PriceIndex), so the pages before keep their numbers.
        "Prices",
        // [econ-trade] Trade between towns, after them (TradePage).
        "Trade",
        // [war-scouting] The war map: the enemy where it lies, the scouts' reports and their age (WarMapPage).
        "War map",
        // [war-peace] The war, after them for the same reason (WarPage).
        "War",
        // [caves] The caves' report (CavesPage).
        "Caves",
        // [fashion] What the town wears (FashionPage).
        "Fashion",
        // [crime] The watch's casebook and the crime rate, after them (CasesPage).
        "Cases",
        // [fleet] The auction and the fishing fleet, after them (AuctionPage).
        "Auction",
        // [library] The town library, after them (LibraryPage): its catalogue, its writers, its loans.
        "Library",
        // [transport] The railways, the carts, the ferry and the bridge, after them (TransportPage).
        "Transport",
        // [interviews] The town's interviews, coming and held, every candidate's score part by part (InterviewsPage).
        "Interviews",
        // [identity] What makes the town itself: its ethos, government, laws, traits, fame and renown (IdentityPage).
        "Identity",
        // [cartographer] The maps: the country as the cartographer drew it, the places found, the archive (MapsPage).
        "Maps",
        // [emerald] The emerald account, the villagers' villages, their villagers and offers (TradingPostPage).
        "Trading Post",
        // [nether] The Nether runners' page, after them (NetherPage): the runs, the outpost, the finds, the hauls.
        "Nether",
        // [perks] The wonders of the world, the town's ways, the leader's skills and legacies, the folk's quirks (PerksPage).
        "Perks",
        // [police] The watch as the town's police: the roster, the beats, the incidents, the cells, the crime rate (WatchPage).
        "Watch",
        // [earning] The player's own earnings: the purse, the week by source, the job skills, the ways to earn here (EarningsPage).
        "Earnings",
        // [borders-map] The town's lands: their extent, the neighbours along the border, the disputes, the markers (LandsPage).
        "Lands",
        // [civic-land] The taxes the council sets and how the town takes them; the town's knights and your service (TaxesPage, KnightsPage).
        "Taxes", "Knights" };

    /** One of the board's sections: its name, its icon, what it holds, and its pages by name. */
    public record Section(String name, Item icon, String tip, String... pages) {}

    public static final Section[] SECTIONS = {
        new Section("Overview", Items.COMPASS, "The town at a glance: its readings, what needs attention, today's news",
            "Overview", "News", "Why", "Board"),
        new Section("People", Items.PLAYER_HEAD, "Every folk, how they live together, their homes and schooling",
            "Folk", "Society", "Growth", "Homes", "School", "Interviews"),
        new Section("Economy", Items.EMERALD, "Money, what is made and sold, the prices, the jobs and the stores",
            "Money", "Production", "Prices", "Shops", "Jobs", "Stock", "Stores", "Trade", "Auction", "Trading Post", "Earnings"),
        new Section("Town", Items.BRICKS, "Its buildings, the roads and rails, the maps, the caves and the watch",
            "Buildings", "Transport", "Maps", "Caves", "Nether", "Watch", "Cases"),
        new Section("Civic", Items.BELL, "Who leads, what the town studies, what it stands for, and its wars",
            "Leader", "Research", "Identity", "War", "War map", "Lands", "Taxes", "Knights"),   // [borders-map] [civic-land]
        new Section("Culture", Items.PAINTING, "Its banner and customs, what it wears, its library, museum and wonders",
            "Culture", "Fashion", "Library", "Museum", "Perks"),
        new Section("Records", Items.WRITABLE_BOOK, "Its bests and totals, and how it is trending",
            "Records", "Trends"),
    };

    /** The section a page (by number) is in; 0 for a page no section names (none: BoardGameTests checks). */
    public static int sectionOf(int page) {
        String name = PAGES[Math.max(0, Math.min(PAGES.length - 1, page))];
        for (int i = 0; i < SECTIONS.length; i++) for (String p : SECTIONS[i].pages()) if (p.equals(name)) return i;
        return 0;
    }

    /** A page's number by its name (any case), or a section's first page by the section's name; -1 if neither. */
    public static int pageNumber(String name) {
        for (int i = 0; i < PAGES.length; i++) if (PAGES[i].equalsIgnoreCase(name)) return i;
        for (Section sec : SECTIONS) if (sec.name().equalsIgnoreCase(name)) return pageNumber(sec.pages()[0]);
        return -1;
    }
}
