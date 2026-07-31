package com.provenance.mod.client;

import com.provenance.core.MilestoneTier;
import com.provenance.mod.HistorySnapshot;
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
 * <p>The layout exists to stop a screenful of numbers reading as one grey
 * column. Three things do that work:
 *
 * <ul>
 *   <li>Every statistic sits in its own card with a coloured accent bar keyed to
 *       its family — combat, mining, upkeep and so on — so related figures group
 *       at a glance.</li>
 *   <li>Values are right-aligned in a column of their own, so digits line up and
 *       magnitudes are comparable without reading them.</li>
 *   <li>The statistic that defines the item is drawn larger than the rest, so
 *       the eye lands on it first.</li>
 * </ul>
 *
 * <p>Colour is always a second channel: every row keeps its full text label, and
 * the current owner is marked with a bar and a word rather than a hue alone, so
 * nothing depends on distinguishing colours.
 *
 * <p>Renders only what the server sent. There is no client-side accumulation and
 * no way to edit a value from here.
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
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(ZoneId.systemDefault());

    private static final int PANEL_WIDTH = 420;
    private static final int PANEL_HEIGHT = 248;
    private static final int HEADER_HEIGHT = 40;
    private static final int TAB_HEIGHT = 18;
    private static final int FOOTER_HEIGHT = 26;

    // Surfaces dark enough that the accent colours carry.
    private static final int SURFACE = 0xF2101216;
    private static final int ROW_ALT = 0x0CFFFFFF;
    private static final int DIVIDER = 0x1AFFFFFF;
    private static final int TEXT = 0xFFE8E8E8;
    private static final int TEXT_DIM = 0xFF8A8F96;
    private static final int TEXT_FAINT = 0xFF5F656D;
    private static final int OWNER_GREEN = 0xFF9BE59B;

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

    private int contentTop() {
        return top + HEADER_HEIGHT + TAB_HEIGHT + 8;
    }

    private int contentBottom() {
        return top + PANEL_HEIGHT - FOOTER_HEIGHT;
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        left = (width - PANEL_WIDTH) / 2;
        top = (height - PANEL_HEIGHT) / 2;

        int tabWidth = PANEL_WIDTH / Tab.values().length;
        int tabX = left;
        for (Tab value : Tab.values()) {
            addRenderableWidget(Button.builder(Component.literal(value.label), b -> {
                tab = value;
                rebuild();
            }).bounds(tabX, top + HEADER_HEIGHT, tabWidth, TAB_HEIGHT).build());
            tabX += tabWidth;
        }

        int footerY = top + PANEL_HEIGHT - FOOTER_HEIGHT + 4;

        if (tab == Tab.STATISTICS) {
            addRenderableWidget(Button.builder(
                    Component.literal(showOwnerView
                            ? "Viewing: " + snapshot.ownerDisplayName()
                            : "Viewing: Overall item"),
                    b -> {
                        showOwnerView = !showOwnerView;
                        rebuild();
                    }).bounds(left + 8, footerY, 170, 18).build());
        }

        if (tab == Tab.CONTRIBUTORS) {
            searchBox = new EditBox(font, left + 8, footerY, 120, 18, Component.literal("Search"));
            searchBox.setValue(search);
            searchBox.setResponder(value -> search = value);
            addRenderableWidget(searchBox);

            addRenderableWidget(Button.builder(Component.literal("Find"), b ->
                            ClientEvents.requestInspection(-1, 0, sortDescending, search))
                    .bounds(left + 132, footerY, 36, 18).build());

            addRenderableWidget(Button.builder(
                            Component.literal(sortDescending ? "Sort desc" : "Sort asc"), b -> {
                                sortDescending = !sortDescending;
                                ClientEvents.requestInspection(-1, 0, sortDescending, search);
                            })
                    .bounds(left + 172, footerY, 62, 18).build());

            if (snapshot.contributorPageIndex() > 0) {
                addRenderableWidget(Button.builder(Component.literal("<"), b ->
                                ClientEvents.requestInspection(-1,
                                        snapshot.contributorPageIndex() - 1, sortDescending, search))
                        .bounds(left + PANEL_WIDTH - 56, footerY, 20, 18).build());
            }
            if (snapshot.contributorPageIndex() < snapshot.contributorPageCount() - 1) {
                addRenderableWidget(Button.builder(Component.literal(">"), b ->
                                ClientEvents.requestInspection(-1,
                                        snapshot.contributorPageIndex() + 1, sortDescending, search))
                        .bounds(left + PANEL_WIDTH - 32, footerY, 20, 18).build());
            }
        }
    }

    private void rebuild() {
        rebuildWidgets();
    }

    // ------------------------------------------------------------------
    // Render
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);

        int accent = Emblems.borderColour(snapshot.currentTierIndex());

        graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, SURFACE);
        // A tier-coloured rule along the top: the one place the tier colours the
        // whole frame, rather than tinting every value on the screen.
        graphics.fill(left, top, left + PANEL_WIDTH, top + 2, accent);
        graphics.renderOutline(left, top, PANEL_WIDTH, PANEL_HEIGHT, 0x33FFFFFF);

        renderHeader(graphics, accent);
        renderTabIndicator(graphics, accent);

        super.render(graphics, mouseX, mouseY, partialTick);

        switch (tab) {
            case OVERVIEW -> renderOverview(graphics);
            case STATISTICS -> renderStatistics(graphics);
            case MILESTONES -> renderMilestones(graphics);
            case CONTRIBUTORS -> renderContributors(graphics);
        }
    }

    private void renderHeader(GuiGraphics graphics, int accent) {
        String title = snapshot.customName().isEmpty()
                ? Labels.prettyId(snapshot.itemTypeId())
                : snapshot.customName();

        graphics.pose().pushPose();
        graphics.pose().translate(left + 12, top + 8, 0);
        graphics.pose().scale(1.25f, 1.25f, 1.0f);
        graphics.drawString(font, trim(title, 230), 0, 0, TEXT, false);
        graphics.pose().popPose();

        graphics.drawString(font, Labels.prettyId(snapshot.itemTypeId()), left + 12, top + 24, TEXT_DIM, false);

        // Tier badge, right-aligned and boxed so it reads as a label rather
        // than more body text.
        if (snapshot.currentTierIndex() >= 0) {
            MilestoneTier tier = MilestoneTier.byIndex(snapshot.currentTierIndex());
            String badge = Emblems.glyph(tier) + " " + tier.displayName().toUpperCase(Locale.ENGLISH);
            int badgeWidth = font.width(badge) + 14;
            int badgeX = left + PANEL_WIDTH - badgeWidth - 12;
            graphics.fill(badgeX, top + 12, badgeX + badgeWidth, top + 27, 0x33000000);
            graphics.fill(badgeX, top + 12, badgeX + 2, top + 27, accent);
            graphics.drawString(font, badge, badgeX + 8, top + 16, accent, false);
        } else {
            String badge = "UNPROVEN";
            graphics.drawString(font, badge, left + PANEL_WIDTH - font.width(badge) - 14, top + 16,
                    TEXT_FAINT, false);
        }

        graphics.fill(left + 1, top + HEADER_HEIGHT - 1, left + PANEL_WIDTH - 1, top + HEADER_HEIGHT, DIVIDER);
    }

    /** The buttons draw themselves; this marks which one is live. */
    private void renderTabIndicator(GuiGraphics graphics, int accent) {
        int tabWidth = PANEL_WIDTH / Tab.values().length;
        int x = left + tab.ordinal() * tabWidth;
        graphics.fill(x, top + HEADER_HEIGHT + TAB_HEIGHT - 2, x + tabWidth,
                top + HEADER_HEIGHT + TAB_HEIGHT, accent);
    }

    // ------------------------------------------------------------------
    // Overview
    // ------------------------------------------------------------------

    private void renderOverview(GuiGraphics graphics) {
        int columnWidth = (PANEL_WIDTH - 28) / 2;
        int rightX = left + 16 + columnWidth;

        int y = sectionHeading(graphics, "Provenance", left + 12, contentTop());
        y = detailRow(graphics, left + 12, y, columnWidth, "Crafted by", snapshot.crafterName());
        y = detailRow(graphics, left + 12, y, columnWidth, "Manufactured by", snapshot.manufacturerName());
        y = detailRow(graphics, left + 12, y, columnWidth, "Created", formatCreated());
        y = detailRow(graphics, left + 12, y, columnWidth, "Age", formatAge());

        int ry = sectionHeading(graphics, "Custody and upkeep", rightX, contentTop());
        ry = detailRow(graphics, rightX, ry, columnWidth, "Current owner", snapshot.ownerName());
        ry = detailRow(graphics, rightX, ry, columnWidth, "Repairs",
                Labels.number(statOf(snapshot.overallStats(), "repairs")));
        ry = detailRow(graphics, rightX, ry, columnWidth, "Distance carried",
                formatDistance(statOf(snapshot.overallStats(), "distance_cm")));
        ry = detailRow(graphics, rightX, ry, columnWidth, "Origin", originLabel());

        int belowY = Math.max(y, ry) + 6;
        graphics.fill(left + 12, belowY, left + PANEL_WIDTH - 12, belowY + 1, DIVIDER);
        belowY += 10;

        // The headline number, given the space it deserves.
        belowY = sectionHeading(graphics, "Lifetime", left + 12, belowY);
        String value = Labels.number(snapshot.primaryStatValue());
        String label = Labels.of(snapshot.primaryStatId());
        StatStyle.Family family = StatStyle.familyOf(snapshot.primaryStatId());

        graphics.fill(left + 12, belowY, left + PANEL_WIDTH - 12, belowY + 26, family.wash());
        graphics.fill(left + 12, belowY, left + 15, belowY + 26, family.colour());

        graphics.pose().pushPose();
        graphics.pose().translate(left + 22, belowY + 6, 0);
        graphics.pose().scale(1.6f, 1.6f, 1.0f);
        graphics.drawString(font, value, 0, 0, TEXT, false);
        graphics.pose().popPose();

        int valueWidth = (int) (font.width(value) * 1.6f);
        graphics.drawString(font, label, left + 22 + valueWidth + 10, belowY + 11, TEXT_DIM, false);
    }

    private int sectionHeading(GuiGraphics graphics, String text, int x, int y) {
        graphics.drawString(font, text.toUpperCase(Locale.ENGLISH), x, y, TEXT_FAINT, false);
        return y + 12;
    }

    private int detailRow(GuiGraphics graphics, int x, int y, int width, String label, String value) {
        graphics.drawString(font, label, x, y, TEXT_DIM, false);
        String shown = trim(value, width - font.width(label) - 14);
        graphics.drawString(font, shown, x + width - font.width(shown), y, TEXT, false);
        return y + 12;
    }

    // ------------------------------------------------------------------
    // Statistics
    // ------------------------------------------------------------------

    private void renderStatistics(GuiGraphics graphics) {
        Map<String, Long> stats = showOwnerView ? snapshot.ownerStats() : snapshot.overallStats();
        int y = contentTop();

        String heading = showOwnerView
                ? "Current owner — " + snapshot.ownerDisplayName()
                : "Overall item — every contributor combined";
        graphics.drawString(font, heading, left + 12, y, showOwnerView ? OWNER_GREEN : TEXT, false);
        y += 14;

        if (stats.isEmpty()) {
            graphics.drawString(font, "Nothing recorded yet.", left + 12, y, TEXT_FAINT, false);
            return;
        }

        int columnWidth = (PANEL_WIDTH - 28) / 2;
        int columnGap = 4;
        int leftY = y;
        int rightY = y;
        boolean nextIsLeft = true;

        for (Map.Entry<String, Long> entry : stats.entrySet()) {
            String id = entry.getKey();
            String value = formatStat(id, entry.getValue());

            // The defining statistic gets a full-width, taller card.
            if (StatStyle.isHeadline(id)) {
                int cardY = Math.max(leftY, rightY);
                if (cardY + 22 > contentBottom()) {
                    break;
                }
                drawStatCard(graphics, left + 12, cardY, PANEL_WIDTH - 24, 22, id, value, true);
                leftY = cardY + 25;
                rightY = cardY + 25;
                nextIsLeft = true;
                continue;
            }

            int cardX = nextIsLeft ? left + 12 : left + 16 + columnWidth;
            int cardY = nextIsLeft ? leftY : rightY;
            if (cardY + 16 > contentBottom()) {
                if (nextIsLeft) {
                    nextIsLeft = false;
                    continue;
                }
                break;
            }
            drawStatCard(graphics, cardX, cardY, columnWidth - columnGap, 16, id, value, false);
            if (nextIsLeft) {
                leftY = cardY + 18;
            } else {
                rightY = cardY + 18;
            }
            nextIsLeft = !nextIsLeft;
        }

        if (!showOwnerView && !snapshot.mostFrequentPrimary().isEmpty()) {
            int footerY = Math.max(leftY, rightY) + 2;
            if (footerY + 10 <= contentBottom()) {
                graphics.drawString(font, "Most common: " + Labels.prettyId(snapshot.mostFrequentPrimary()),
                        left + 12, footerY, TEXT_FAINT, false);
            }
        }
    }

    /**
     * One statistic as a card: a family-coloured accent bar, a dim label, and a
     * right-aligned value so figures line up down the column.
     */
    private void drawStatCard(GuiGraphics graphics, int x, int y, int width, int height,
                              String statId, String value, boolean headline) {
        StatStyle.Family family = StatStyle.familyOf(statId);

        graphics.fill(x, y, x + width, y + height, family.wash());
        graphics.fill(x, y, x + 3, y + height, family.colour());

        String label = Labels.of(statId);

        if (headline) {
            graphics.drawString(font, label, x + 10, y + 7, TEXT_DIM, false);
            float scale = 1.35f;
            int valueWidth = (int) (font.width(value) * scale);
            graphics.pose().pushPose();
            graphics.pose().translate(x + width - valueWidth - 8, y + 5, 0);
            graphics.pose().scale(scale, scale, 1.0f);
            graphics.drawString(font, value, 0, 0, TEXT, false);
            graphics.pose().popPose();
        } else {
            int textY = y + (height - 8) / 2;
            graphics.drawString(font, trim(label, width - font.width(value) - 26), x + 10, textY, TEXT_DIM, false);
            graphics.drawString(font, value, x + width - font.width(value) - 7, textY, TEXT, false);
        }
    }

    // ------------------------------------------------------------------
    // Milestones
    // ------------------------------------------------------------------

    private void renderMilestones(GuiGraphics graphics) {
        int y = contentTop();
        int railX = left + 20;

        // A rail joining the tiers, so the ladder reads as a path rather than a
        // list of unrelated lines.
        graphics.fill(railX, y + 4, railX + 1, y + snapshot.tiers().size() * 14 - 6, 0x22FFFFFF);

        for (HistorySnapshot.TierEntry entry : snapshot.tiers()) {
            MilestoneTier tier = MilestoneTier.byIndex(entry.tierIndex());
            boolean done = entry.achieved();
            int colour = done ? Emblems.borderColour(entry.tierIndex()) : 0xFF3A3E44;

            // Node on the rail: filled when earned, hollow when not.
            graphics.fill(railX - 3, y + 1, railX + 4, y + 8, colour);
            if (!done) {
                graphics.fill(railX - 2, y + 2, railX + 3, y + 7, SURFACE);
            }

            graphics.drawString(font, tier.displayName(), railX + 12, y, done ? TEXT : TEXT_FAINT, false);

            String requirement = Labels.number(entry.threshold());
            graphics.drawString(font, requirement, railX + 110 - font.width(requirement), y,
                    done ? TEXT_DIM : TEXT_FAINT, false);

            if (done && entry.achievedEpochMilli() > 0) {
                String date = DATE_FORMAT.format(Instant.ofEpochMilli(entry.achievedEpochMilli()));
                graphics.drawString(font, date, left + PANEL_WIDTH - font.width(date) - 14, y, TEXT_FAINT, false);
            }
            y += 14;
        }

        y += 4;
        graphics.fill(left + 12, y, left + PANEL_WIDTH - 12, y + 1, DIVIDER);
        y += 9;

        long value = snapshot.primaryStatValue();
        long next = snapshot.nextTierThreshold();

        if (next < 0) {
            graphics.drawString(font, "This item is a Server Relic. The ladder ends here.",
                    left + 12, y, Emblems.borderColour(snapshot.currentTierIndex()), false);
            return;
        }

        long floor = snapshot.currentTierThreshold();
        double fraction = next > floor ? (double) (value - floor) / (double) (next - floor) : 1.0d;
        fraction = Math.max(0.0d, Math.min(1.0d, fraction));

        String progress = Labels.number(value) + " / " + Labels.number(next);
        graphics.drawString(font, "Next: " + Labels.of(snapshot.primaryStatId()), left + 12, y, TEXT_DIM, false);
        graphics.drawString(font, progress, left + PANEL_WIDTH - font.width(progress) - 14, y, TEXT, false);
        y += 12;

        int barWidth = PANEL_WIDTH - 26;
        int nextTierIndex = Math.min(6, snapshot.currentTierIndex() + 1);
        int accent = Emblems.borderColour(Math.max(0, nextTierIndex));
        graphics.fill(left + 13, y, left + 13 + barWidth, y + 7, 0xFF23262B);
        graphics.fill(left + 13, y, left + 13 + (int) (barWidth * fraction), y + 7, accent);
        graphics.renderOutline(left + 13, y, barWidth, 7, 0x22FFFFFF);
        y += 11;

        graphics.drawString(font, String.format(Locale.ENGLISH, "%.1f%% of the way to %s",
                        fraction * 100.0d, MilestoneTier.byIndex(nextTierIndex).displayName()),
                left + 13, y, TEXT_FAINT, false);
    }

    // ------------------------------------------------------------------
    // Contributors
    // ------------------------------------------------------------------

    private void renderContributors(GuiGraphics graphics) {
        int y = contentTop();

        int nameX = left + 28;
        int primaryRight = left + 240;
        int repairsRight = left + 308;
        int distanceRight = left + PANEL_WIDTH - 14;

        String primaryHeader = Labels.of(snapshot.primaryStatId()).toUpperCase(Locale.ENGLISH);
        graphics.drawString(font, "PLAYER", nameX, y, TEXT_FAINT, false);
        graphics.drawString(font, primaryHeader, primaryRight - font.width(primaryHeader), y, TEXT_FAINT, false);
        graphics.drawString(font, "REPAIRS", repairsRight - font.width("REPAIRS"), y, TEXT_FAINT, false);
        graphics.drawString(font, "DISTANCE", distanceRight - font.width("DISTANCE"), y, TEXT_FAINT, false);
        y += 11;
        graphics.fill(left + 12, y, left + PANEL_WIDTH - 12, y + 1, DIVIDER);
        y += 5;

        int rank = snapshot.contributorPageIndex() * 25 + 1;
        boolean stripe = false;

        for (HistorySnapshot.ContributorRow row : snapshot.contributors()) {
            if (y + 12 > contentBottom()) {
                break;
            }
            if (stripe) {
                graphics.fill(left + 12, y - 2, left + PANEL_WIDTH - 12, y + 10, ROW_ALT);
            }
            stripe = !stripe;

            // The owner is marked with a bar and a word, not colour alone.
            if (row.isOwner()) {
                graphics.fill(left + 12, y - 2, left + 14, y + 10, 0xFF7FD07F);
            }

            graphics.drawString(font, String.valueOf(rank++), left + 17, y, TEXT_FAINT, false);

            String suffix = row.isOwner() ? " (owner)" : row.isCrafter() ? " (maker)" : "";
            String name = trim(row.name() + suffix, 150);
            graphics.drawString(font, name, nameX, y, row.isOwner() ? OWNER_GREEN : TEXT, false);

            String primary = Labels.number(row.primary());
            graphics.drawString(font, primary, primaryRight - font.width(primary), y, TEXT, false);

            String repairs = Labels.number(row.repairs());
            graphics.drawString(font, repairs, repairsRight - font.width(repairs), y, TEXT_DIM, false);

            String distance = formatDistance(row.distanceCm());
            graphics.drawString(font, distance, distanceRight - font.width(distance), y, TEXT_DIM, false);

            y += 12;
        }

        String footer = snapshot.contributorTotal() + " contributor"
                + (snapshot.contributorTotal() == 1 ? "" : "s")
                + "   ·   page " + (snapshot.contributorPageIndex() + 1)
                + " of " + snapshot.contributorPageCount();
        graphics.drawString(font, footer, left + 12, contentBottom() + 6, TEXT_FAINT, false);
    }

    // ------------------------------------------------------------------
    // Formatting
    // ------------------------------------------------------------------

    private String trim(String text, int maxWidth) {
        if (maxWidth <= 0 || font.width(text) <= maxWidth) {
            return text;
        }
        String cut = text;
        while (cut.length() > 1 && font.width(cut + "...") > maxWidth) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "...";
    }

    private String originLabel() {
        return switch (snapshot.originId()) {
            case "legacy" -> "Legacy item";
            case "found" -> "Found, no maker";
            case "duplicate" -> "Re-registered";
            default -> "Crafted";
        };
    }

    private String formatCreated() {
        String date = DATE_FORMAT.format(Instant.ofEpochMilli(snapshot.createdEpochMilli()));
        return snapshot.createdApproximate() ? date + " (approx)" : date;
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
        return years + (years == 1 ? " year, " : " years, ") + remainder + "d";
    }

    private static long statOf(Map<String, Long> stats, String key) {
        Long value = stats.get(key);
        return value == null ? 0L : value;
    }

    private String formatStat(String key, long value) {
        return switch (key) {
            case "distance_cm" -> formatDistance(value);
            case "longest_kill_cm" -> String.format(Locale.ENGLISH, "%.1f m", value / 100.0d);
            case "damage_dealt", "damage_absorbed" -> String.format(Locale.ENGLISH, "%.1f", value / 100.0d);
            case "time_worn_ticks" -> formatTicks(value);
            case "deepest_block_y" -> "Y " + value;
            default -> Labels.number(value);
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

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
