package com.provenance.mod.client;

import com.provenance.core.MilestoneTier;
import com.provenance.mod.HistorySnapshot;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/**
 * The Item History screen: Overview, Statistics, Milestones and Contributors.
 *
 * <p>Renders only what the server sent. There is no client-side accumulation
 * and no way to edit a value from here.
 */
public final class ItemHistoryScreen extends Screen {

    private enum Tab {
        OVERVIEW("Overview"),
        STATISTICS("Statistics"),
        MILESTONES("Milestones"),
        CONTRIBUTORS("Contributors");

        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH).withZone(ZoneId.systemDefault());

    private static final int PANEL_WIDTH = 340;
    private static final int PANEL_HEIGHT = 200;

    private HistorySnapshot snapshot;
    private Tab tab = Tab.OVERVIEW;

    /** Statistics tab: false shows the item's combined totals, true the owner's own. */
    private boolean showOwnerView;

    private boolean sortDescending = true;
    private String search = "";
    private EditBox searchBox;

    private int left;
    private int top;

    public ItemHistoryScreen(HistorySnapshot snapshot) {
        super(Component.literal("Item History"));
        this.snapshot = snapshot;
    }

    public void refresh(HistorySnapshot updated) {
        this.snapshot = updated;
    }

    @Override
    protected void init() {
        left = (width - PANEL_WIDTH) / 2;
        top = (height - PANEL_HEIGHT) / 2;

        int tabX = left + 8;
        for (Tab value : Tab.values()) {
            int buttonWidth = 78;
            addRenderableWidget(Button.builder(Component.literal(value.label), b -> {
                tab = value;
                rebuild();
            }).bounds(tabX, top + 6, buttonWidth, 16).build());
            tabX += buttonWidth + 2;
        }

        if (tab == Tab.STATISTICS) {
            addRenderableWidget(Button.builder(
                    Component.literal(showOwnerView
                            ? "Showing: " + snapshot.ownerDisplayName()
                            : "Showing: Overall Item"),
                    b -> {
                        showOwnerView = !showOwnerView;
                        rebuild();
                    }).bounds(left + 8, top + PANEL_HEIGHT - 26, 160, 16).build());
        }

        if (tab == Tab.CONTRIBUTORS) {
            searchBox = new EditBox(font, left + 8, top + PANEL_HEIGHT - 26, 120, 16,
                    Component.literal("Search"));
            searchBox.setValue(search);
            searchBox.setResponder(value -> search = value);
            addRenderableWidget(searchBox);

            addRenderableWidget(Button.builder(Component.literal("Find"), b ->
                            ClientEvents.requestInspection(-1, 0, sortDescending, search))
                    .bounds(left + 132, top + PANEL_HEIGHT - 26, 34, 16).build());

            addRenderableWidget(Button.builder(
                            Component.literal(sortDescending ? "Sort ▼" : "Sort ▲"), b -> {
                                sortDescending = !sortDescending;
                                ClientEvents.requestInspection(-1, 0, sortDescending, search);
                            })
                    .bounds(left + 170, top + PANEL_HEIGHT - 26, 48, 16).build());

            if (snapshot.contributorPageIndex() > 0) {
                addRenderableWidget(Button.builder(Component.literal("<"), b ->
                                ClientEvents.requestInspection(-1,
                                        snapshot.contributorPageIndex() - 1, sortDescending, search))
                        .bounds(left + 250, top + PANEL_HEIGHT - 26, 20, 16).build());
            }
            if (snapshot.contributorPageIndex() < snapshot.contributorPageCount() - 1) {
                addRenderableWidget(Button.builder(Component.literal(">"), b ->
                                ClientEvents.requestInspection(-1,
                                        snapshot.contributorPageIndex() + 1, sortDescending, search))
                        .bounds(left + 274, top + PANEL_HEIGHT - 26, 20, 16).build());
            }
        }
    }

    private void rebuild() {
        // Screen's own re-init path: clears widgets and calls init() with the
        // current size, rather than reimplementing that by hand.
        rebuildWidgets();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xEE101010);
        graphics.renderOutline(left, top, PANEL_WIDTH, PANEL_HEIGHT, tierColour());

        super.render(graphics, mouseX, mouseY, partialTick);

        int y = top + 28;
        switch (tab) {
            case OVERVIEW -> renderOverview(graphics, y);
            case STATISTICS -> renderStatistics(graphics, y);
            case MILESTONES -> renderMilestones(graphics, y);
            case CONTRIBUTORS -> renderContributors(graphics, y);
        }
    }

    private void renderOverview(GuiGraphics graphics, int y) {
        String title = snapshot.customName().isEmpty()
                ? prettyId(snapshot.itemTypeId())
                : snapshot.customName();

        graphics.drawString(font, Component.literal(title).withStyle(ChatFormatting.WHITE), left + 10, y, 0xFFFFFF);
        y += 11;
        graphics.drawString(font, prettyId(snapshot.itemTypeId()), left + 10, y, 0xA0A0A0);
        y += 11;

        // The earned title is separate from whatever the owner renamed it to.
        graphics.drawString(font, Component.literal("Milestone: " + tierName())
                .withStyle(ChatFormatting.GOLD), left + 10, y, 0xFFD700);
        y += 14;

        line(graphics, "Crafted by", snapshot.crafterName(), y);
        y += 11;
        line(graphics, "Manufactured by", snapshot.manufacturerName(), y);
        y += 11;
        line(graphics, "Created", formatCreated(), y);
        y += 11;
        line(graphics, "Age", formatAge(), y);
        y += 11;
        line(graphics, "Current owner", snapshot.ownerName(), y);
        y += 11;
        line(graphics, "Repairs performed", String.valueOf(statOf(snapshot.overallStats(), "repairs")), y);
        y += 11;
        line(graphics, "Distance carried", formatDistance(statOf(snapshot.overallStats(), "distance_cm")), y);

        if (!snapshot.originId().equals("crafted")) {
            y += 14;
            graphics.drawString(font, Component.literal("Origin: " + originLabel())
                    .withStyle(ChatFormatting.DARK_GRAY), left + 10, y, 0x808080);
        }
    }

    private void renderStatistics(GuiGraphics graphics, int y) {
        Map<String, Long> stats = showOwnerView ? snapshot.ownerStats() : snapshot.overallStats();

        String heading = showOwnerView
                ? "Current owner — " + snapshot.ownerDisplayName()
                : "Overall item";
        graphics.drawString(font, Component.literal(heading).withStyle(ChatFormatting.YELLOW),
                left + 10, y, 0xFFFF55);
        y += 14;

        if (stats.isEmpty()) {
            graphics.drawString(font, "Nothing recorded yet.", left + 10, y, 0x808080);
            return;
        }

        for (Map.Entry<String, Long> entry : stats.entrySet()) {
            line(graphics, labelFor(entry.getKey()), formatStat(entry.getKey(), entry.getValue()), y);
            y += 11;
            if (y > top + PANEL_HEIGHT - 36) {
                break;
            }
        }

        if (!showOwnerView && !snapshot.mostFrequentPrimary().isEmpty()) {
            line(graphics, "Most common", prettyId(snapshot.mostFrequentPrimary()), y);
        }
    }

    private void renderMilestones(GuiGraphics graphics, int y) {
        for (HistorySnapshot.TierEntry tier : snapshot.tiers()) {
            MilestoneTier value = MilestoneTier.byIndex(tier.tierIndex());
            String mark = tier.achieved() ? "✓ " : "○ ";
            String text = mark + value.displayName() + " — " + format(tier.threshold());

            int colour = tier.achieved() ? 0x55FF55 : 0x707070;
            graphics.drawString(font, text, left + 10, y, colour);

            if (tier.achieved() && tier.achievedEpochMilli() > 0) {
                String date = DATE_FORMAT.format(Instant.ofEpochMilli(tier.achievedEpochMilli()));
                graphics.drawString(font, date, left + 210, y, 0x808080);
            }
            y += 12;
        }

        y += 6;
        long value = snapshot.primaryStatValue();
        long next = snapshot.nextTierThreshold();

        if (next < 0) {
            graphics.drawString(font, Component.literal("This item is a Server Relic.")
                    .withStyle(ChatFormatting.LIGHT_PURPLE), left + 10, y, 0xFF55FF);
            return;
        }

        graphics.drawString(font, labelFor(snapshot.primaryStatId()) + ": " + format(value),
                left + 10, y, 0xFFFFFF);
        y += 12;

        long floor = snapshot.currentTierThreshold();
        double fraction = next > floor ? (double) (value - floor) / (double) (next - floor) : 1.0d;
        fraction = Math.max(0.0d, Math.min(1.0d, fraction));

        int barWidth = PANEL_WIDTH - 40;
        graphics.fill(left + 10, y, left + 10 + barWidth, y + 8, 0xFF303030);
        graphics.fill(left + 10, y, left + 10 + (int) (barWidth * fraction), y + 8, 0xFF55AA55);
        y += 12;

        graphics.drawString(font, format(value) + " / " + format(next)
                        + "  (" + String.format(Locale.ENGLISH, "%.1f", fraction * 100.0d) + "%)",
                left + 10, y, 0xC0C0C0);
    }

    private void renderContributors(GuiGraphics graphics, int y) {
        graphics.drawString(font, "Player", left + 10, y, 0xFFFF55);
        graphics.drawString(font, labelFor(snapshot.primaryStatId()), left + 120, y, 0xFFFF55);
        graphics.drawString(font, "Repairs", left + 220, y, 0xFFFF55);
        graphics.drawString(font, "Distance", left + 270, y, 0xFFFF55);
        y += 12;

        for (HistorySnapshot.ContributorRow row : snapshot.contributors()) {
            String name = row.name();
            int colour = 0xFFFFFF;
            if (row.isOwner()) {
                colour = 0x55FF55;
            }
            if (row.isCrafter()) {
                // Small maker marker beside the original crafter.
                name = "⚒ " + name;
            }
            graphics.drawString(font, name, left + 10, y, colour);
            graphics.drawString(font, format(row.primary()), left + 120, y, 0xC0C0C0);
            graphics.drawString(font, format(row.repairs()), left + 220, y, 0xC0C0C0);
            graphics.drawString(font, formatDistance(row.distanceCm()), left + 270, y, 0xC0C0C0);
            y += 11;
            if (y > top + PANEL_HEIGHT - 36) {
                break;
            }
        }

        graphics.drawString(font,
                "Page " + (snapshot.contributorPageIndex() + 1) + " of " + snapshot.contributorPageCount()
                        + "  —  " + snapshot.contributorTotal() + " contributors",
                left + 10, top + PANEL_HEIGHT - 40, 0x808080);
    }

    // ------------------------------------------------------------------
    // Formatting
    // ------------------------------------------------------------------

    private void line(GuiGraphics graphics, String label, String value, int y) {
        graphics.drawString(font, label + ":", left + 10, y, 0x909090);
        graphics.drawString(font, value, left + 130, y, 0xFFFFFF);
    }

    private String tierName() {
        int index = snapshot.currentTierIndex();
        return index < 0 ? "Unproven" : MilestoneTier.byIndex(index).displayName();
    }

    private int tierColour() {
        return switch (snapshot.currentTierIndex()) {
            case 0 -> 0xFF8B5A2B;
            case 1 -> 0xFFC0C0C0;
            case 2 -> 0xFFB87333;
            case 3 -> 0xFFFFD700;
            case 4 -> 0xFF4EE2EC;
            case 5 -> 0xFFDA70D6;
            case 6 -> 0xFFFF4500;
            default -> 0xFF404040;
        };
    }

    private String originLabel() {
        return switch (snapshot.originId()) {
            case "legacy" -> "Legacy item, adopted when provenance tracking began";
            case "found" -> "Found, no recorded maker";
            case "duplicate" -> "Re-registered after a failed identity check";
            default -> "Crafted";
        };
    }

    private String formatCreated() {
        String date = DATE_FORMAT.format(Instant.ofEpochMilli(snapshot.createdEpochMilli()));
        return snapshot.createdApproximate() ? date + " (approximate)" : date;
    }

    private String formatAge() {
        long millis = Math.max(0L, System.currentTimeMillis() - snapshot.createdEpochMilli());
        long days = millis / 86_400_000L;
        if (days < 1) {
            return "Less than a day";
        }
        if (days < 365) {
            return days + (days == 1 ? " day" : " days");
        }
        long years = days / 365;
        long remainder = days % 365;
        return years + (years == 1 ? " year, " : " years, ") + remainder + " days";
    }

    private static long statOf(Map<String, Long> stats, String key) {
        Long value = stats.get(key);
        return value == null ? 0L : value;
    }

    private String formatStat(String key, long value) {
        return switch (key) {
            case "distance_cm" -> formatDistance(value);
            case "longest_kill_cm" -> String.format(Locale.ENGLISH, "%.1f m", value / 100.0d);
            case "damage_dealt", "damage_absorbed", "damage_prevented" ->
                    String.format(Locale.ENGLISH, "%.1f", value / 100.0d);
            case "time_used_ticks", "time_worn_ticks", "blocking_time_ticks" -> formatTicks(value);
            case "deepest_block_y" -> "Y " + value;
            default -> format(value);
        };
    }

    private static String formatDistance(long centimetres) {
        double metres = centimetres / 100.0d;
        if (metres < 1_000) {
            return String.format(Locale.ENGLISH, "%.0f m", metres);
        }
        return String.format(Locale.ENGLISH, "%.1f km", metres / 1_000.0d);
    }

    private static String formatTicks(long ticks) {
        long seconds = ticks / 20L;
        long hours = seconds / 3_600L;
        if (hours >= 1) {
            return hours + (hours == 1 ? " hour" : " hours");
        }
        long minutes = seconds / 60L;
        return minutes + (minutes == 1 ? " minute" : " minutes");
    }

    private static String format(long value) {
        return String.format(Locale.ENGLISH, "%,d", value);
    }

    private static String labelFor(String statId) {
        String spaced = statId.replace('_', ' ');
        if (spaced.endsWith(" cm")) {
            spaced = spaced.substring(0, spaced.length() - 3);
        }
        if (spaced.endsWith(" ticks")) {
            spaced = spaced.substring(0, spaced.length() - 6);
        }
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private static String prettyId(String id) {
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        String spaced = path.replace('_', ' ');
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
