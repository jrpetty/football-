package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.armortrim.ArmorTrim;
import net.minecraft.world.item.armortrim.TrimMaterial;
import net.minecraft.world.item.armortrim.TrimMaterials;
import net.minecraft.world.item.armortrim.TrimPattern;
import net.minecraft.world.item.armortrim.TrimPatterns;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.Unbreakable;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * [war-polish] An army's battle dress, so two armies read apart at a glance from a hill, the Minecraft way: each side
 * in a colour of its own (its town's arms, the other side's clash changed for a contrasting one), shown as armour
 * trims of the nearest trim material on every piece, a dyed leather tunic on anybody without metal on its chest, its
 * shield painted with the town's banner on the army's colour, a company standard worn high on the helm of one in
 * every company (the army's own standard-bearer carries the town's banner in hand), and its fighters' name tags in
 * the colour (a scoreboard team per army).
 *
 * <p><b>Nothing minted.</b> All of it is cosmetic for the battle: every piece it touches remembers what it was (in a
 * mark of its own on the piece) and is put back exactly so after the battle; the tunic and the helm standard are lent
 * for the day and taken back, never dropped, never kept (a fighter that falls is undressed before its things drop).
 *
 * <p><b>Its cost:</b> dressed once at the muster (or the defenders at their gate), undressed once after the battle
 * (and on any discharge); a look at a folk's six slots when it comes into the world, for one a restart or an
 * unloaded chunk left in its dress. Nothing a tick.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class WarDress {

    private WarDress() {}

    /** The mark on a dressed piece (CUSTOM_DATA): what it was before, to be put back. */
    static final String MARK = "mca_war_dress";
    /** The mark's "lent for the battle, take it back" flag, and the helmet kept under a helm standard. */
    private static final String LENT = "lent", UNDER = "under";
    /** Fighters in a company: past the first (the army's own standard-bearer), one in every company wears its standard. */
    public static final int COMPANY = 10;
    /** The armies' teams: this, then the town's id (short, fixed: the same team found again after a restart). */
    static final String TEAM = "mcawar";
    /** The trim every army wears: one pattern, so only its colour tells the sides apart. */
    private static final ResourceKey<TrimPattern> PATTERN = TrimPatterns.SENTRY;

    /**
     * The colours an eye tells apart at a distance, each as the game shows it: its trim material, its dye (the tunic,
     * the shield's field), its name-tag colour and its boss-bar colour; {@code family} groups those too alike to set
     * against each other (blue and cyan, red and orange).
     */
    public enum Tint {
        LAPIS(TrimMaterials.LAPIS, DyeColor.BLUE, ChatFormatting.BLUE, BossEvent.BossBarColor.BLUE, 0),
        DIAMOND(TrimMaterials.DIAMOND, DyeColor.CYAN, ChatFormatting.AQUA, BossEvent.BossBarColor.BLUE, 0),
        REDSTONE(TrimMaterials.REDSTONE, DyeColor.RED, ChatFormatting.RED, BossEvent.BossBarColor.RED, 1),
        COPPER(TrimMaterials.COPPER, DyeColor.ORANGE, ChatFormatting.GOLD, BossEvent.BossBarColor.YELLOW, 1),
        EMERALD(TrimMaterials.EMERALD, DyeColor.GREEN, ChatFormatting.GREEN, BossEvent.BossBarColor.GREEN, 2),
        GOLD(TrimMaterials.GOLD, DyeColor.YELLOW, ChatFormatting.YELLOW, BossEvent.BossBarColor.YELLOW, 3),
        AMETHYST(TrimMaterials.AMETHYST, DyeColor.PURPLE, ChatFormatting.LIGHT_PURPLE, BossEvent.BossBarColor.PURPLE, 4),
        QUARTZ(TrimMaterials.QUARTZ, DyeColor.WHITE, ChatFormatting.WHITE, BossEvent.BossBarColor.WHITE, 5),
        NETHERITE(TrimMaterials.NETHERITE, DyeColor.BLACK, ChatFormatting.DARK_GRAY, BossEvent.BossBarColor.WHITE, 6);

        public final ResourceKey<TrimMaterial> material;
        public final DyeColor dye;
        public final ChatFormatting chat;
        public final BossEvent.BossBarColor bar;
        final int family;

        Tint(ResourceKey<TrimMaterial> material, DyeColor dye, ChatFormatting chat, BossEvent.BossBarColor bar, int family) {
            this.material = material;
            this.dye = dye;
            this.chat = chat;
            this.bar = bar;
            this.family = family;
        }

        /** The nearest trim colour to a dye. */
        public static Tint of(DyeColor c) {
            return switch (c) {
                case WHITE, LIGHT_GRAY -> QUARTZ;
                case GRAY, BLACK -> NETHERITE;
                case BROWN, ORANGE -> COPPER;
                case RED, PINK -> REDSTONE;
                case YELLOW -> GOLD;
                case LIME, GREEN -> EMERALD;
                case CYAN, LIGHT_BLUE -> DIAMOND;
                case BLUE -> LAPIS;
                case PURPLE, MAGENTA -> AMETHYST;
            };
        }

        /** Two tints an eye tells apart from a hill. */
        public boolean apart(Tint other) {
            return family != other.family;
        }
    }

    /** A second colour for a side whose own all clash with the other's: strong, plain colours, in this order. */
    private static final Tint[] FALLBACK = { Tint.REDSTONE, Tint.LAPIS, Tint.GOLD, Tint.QUARTZ, Tint.EMERALD, Tint.NETHERITE };

    // ------------------------------------------------------------------ the colours

    /** A town's own colours, its arms' field first, then its charges; the colour its watch wears with no arms. */
    static List<DyeColor> colours(Armies.Army a) {
        Set<DyeColor> out = new LinkedHashSet<>();
        Heraldry.Design d = Heraldry.design(a.town);
        if (d != null) {
            out.add(d.field());
            for (Heraldry.Layer l : d.layers()) out.add(l.colour());
        }
        out.add(a.surcoat.getOrDefault(DataComponents.BASE_COLOR, DyeColor.WHITE));
        return new ArrayList<>(out);
    }

    /**
     * Each side's colour: the first its own field; the second its own field too unless that clashes, else the first of
     * its other colours that does not, else a contrasting one.
     */
    public static Tint[] pick(List<DyeColor> first, List<DyeColor> second) {
        Tint a = Tint.of(first.isEmpty() ? DyeColor.BLUE : first.get(0));
        for (DyeColor c : second) if (Tint.of(c).apart(a)) return new Tint[]{ a, Tint.of(c) };
        for (Tint t : FALLBACK) if (t.apart(a)) return new Tint[]{ a, t };
        return new Tint[]{ a, Tint.REDSTONE };   // unreachable: the fallbacks span every family
    }

    /** The colours of a campaign's armies (either may be missing: then its own). */
    static Tint[] tints(Campaigns.Campaign c) {
        Armies.Army a = c.armies[0], b = c.armies[1];
        if (a != null && b != null) return pick(colours(a), colours(b));
        Tint one = Tint.of(a != null ? colours(a).get(0) : b != null ? colours(b).get(0) : DyeColor.BLUE);
        return new Tint[]{ one, one };
    }

    // ------------------------------------------------------------------ dressing

    /** Both armies of the campaign into their battle dress (again, if they were: the colours set afresh). */
    static void dress(ServerLevel level, Campaigns.Campaign c) {
        Tint[] t = tints(c);
        for (int i = 0; i < 2; i++) if (c.armies[i] != null) dress(level, c.armies[i], t[i]);
    }

    /** One army in this colour: its surcoat, its team, and every fighter's kit. */
    static void dress(ServerLevel level, Armies.Army a, Tint tint) {
        DyeColor field = a.surcoat.getOrDefault(DataComponents.BASE_COLOR, DyeColor.WHITE);
        if (!a.surcoat.isEmpty() && Tint.of(field) != tint) {
            a.surcoat = a.surcoat.copy();
            a.surcoat.set(DataComponents.BASE_COLOR, tint.dye);
        }
        PlayerTeam team = team(level, a, tint);
        Set<UUID> standards = standards(a);
        Heraldry.Design d = Heraldry.design(a.town);
        for (Armies.Fighter f : a.fighters) {
            if (f.dead || !(level.getEntity(f.id) instanceof VillageFolkEntity e)) continue;
            join(level.getScoreboard(), team, e);
            dressFighter(level, e, a.town, tint, d, standards.contains(f.id));
        }
    }

    /**
     * The company standards: past the first company (whose standard the army's bearer carries), one of every company
     * of the line wears the town's banner on its helm, a sergeant first.
     */
    static Set<UUID> standards(Armies.Army a) {
        List<Armies.Fighter> line = new ArrayList<>();
        for (Armies.Fighter f : a.fighters) {
            if (f.role == com.jrpetty.mcassistant.village.BattleModel.Role.LINE && (f.rank == Armies.Rank.NONE || f.rank == Armies.Rank.SERGEANT)) line.add(f);
        }
        line.sort((x, y) -> Integer.compare(x.rank == Armies.Rank.SERGEANT ? 0 : 1, y.rank == Armies.Rank.SERGEANT ? 0 : 1));
        int companies = (line.size() + COMPANY - 1) / COMPANY;
        Set<UUID> out = new LinkedHashSet<>();
        for (int k = 0; k < companies - 1 && k < line.size(); k++) out.add(line.get(k).id);
        return out;
    }

    /** One fighter in the colours: trims on its armour, a tunic or a dye on a chest with no metal, its shield painted, the standard on its helm. */
    static void dressFighter(ServerLevel level, VillageFolkEntity e, UUID town, Tint tint, @Nullable Heraldry.Design d, boolean standard) {
        HolderLookup.Provider reg = level.registryAccess();
        Optional<? extends Holder<TrimMaterial>> mat = level.registryAccess().registryOrThrow(Registries.TRIM_MATERIAL).getHolder(tint.material);
        Optional<? extends Holder<TrimPattern>> pat = level.registryAccess().registryOrThrow(Registries.TRIM_PATTERN).getHolder(PATTERN);
        ArmorTrim trim = mat.isPresent() && pat.isPresent() ? new ArmorTrim(mat.get(), pat.get()) : null;
        for (EquipmentSlot slot : new EquipmentSlot[]{ EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET }) {
            ItemStack worn = e.getItemBySlot(slot);
            if (slot == EquipmentSlot.HEAD && standard) {
                if (!lent(worn)) e.setItemSlot(slot, helmStandard(level, reg, town, tint, d, worn));
                continue;
            }
            if (slot == EquipmentSlot.CHEST && !(worn.getItem() instanceof ArmorItem)) {
                if (!worn.isEmpty()) continue;                                 // something of its own there: left be
                ItemStack tunic = new ItemStack(Items.LEATHER_CHESTPLATE);
                tunic.set(DataComponents.UNBREAKABLE, new Unbreakable(false));
                mark(tunic, lentMemo());
                e.setItemSlot(slot, tunic);
                worn = tunic;
            }
            if (!(worn.getItem() instanceof ArmorItem) || !worn.is(ItemTags.TRIMMABLE_ARMOR)) continue;
            CompoundTag memo = memo(worn);
            if (trim != null && remember(reg, worn, memo, DataComponents.TRIM, "trim")) worn.set(DataComponents.TRIM, trim);
            if (worn.is(ItemTags.DYEABLE) && remember(reg, worn, memo, DataComponents.DYED_COLOR, "dye")) {
                worn.set(DataComponents.DYED_COLOR, new DyedItemColor(tint.dye.getTextureDiffuseColor() & 0xFFFFFF, false));
            }
            mark(worn, memo);
        }
        ItemStack off = e.getOffhandItem();
        if (off.getItem() instanceof ShieldItem) {
            CompoundTag memo = memo(off);
            if (remember(reg, off, memo, DataComponents.BASE_COLOR, "base") && remember(reg, off, memo, DataComponents.BANNER_PATTERNS, "arms")) {
                off.set(DataComponents.BASE_COLOR, tint.dye);
                off.set(DataComponents.BANNER_PATTERNS, arms(level, town, tint, d));
            }
            mark(off, memo);
        }
    }

    /** The town's banner on the army's colour, lent for the battle to wear on the helm; the helmet it covers kept in it. */
    static ItemStack helmStandard(ServerLevel level, HolderLookup.Provider reg, UUID town, Tint tint, @Nullable Heraldry.Design d, ItemStack helmet) {
        ItemStack banner = d == null ? new ItemStack(Heraldry.bannerItem(tint.dye)) : Heraldry.item(level, town, new Heraldry.Design(tint.dye, d.layers()));
        CompoundTag memo = lentMemo();
        if (!helmet.isEmpty()) memo.put(UNDER, helmet.save(reg));
        mark(banner, memo);
        return banner;
    }

    /** The town's charges, to paint on a field of the army's colour (none for a town with no arms). */
    static BannerPatternLayers arms(ServerLevel level, UUID town, Tint tint, @Nullable Heraldry.Design d) {
        if (d == null) return BannerPatternLayers.EMPTY;
        return Heraldry.item(level, town, new Heraldry.Design(tint.dye, d.layers())).getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY);
    }

    // ------------------------------------------------------------------ undressing

    /** The army out of its battle dress: every fighter's kit as it was, its team gone. */
    static void undress(ServerLevel level, Armies.Army a) {
        for (Armies.Fighter f : a.fighters) if (level.getEntity(f.id) instanceof VillageFolkEntity e) undress(e);
        PlayerTeam team = level.getScoreboard().getPlayerTeam(teamName(a.town));
        if (team != null) level.getScoreboard().removePlayerTeam(team);
    }

    /** Both armies of a campaign out of their dress. */
    static void undress(ServerLevel level, Campaigns.Campaign c) {
        for (Armies.Army a : c.armies) if (a != null) undress(level, a);
    }

    /**
     * One folk out of its battle dress: what it wears, holds and carries put back as it was (the lent tunic and
     * standard taken back, the helmet under the standard back on its head), off its army's team. Whether anything was.
     */
    public static boolean undress(VillageFolkEntity e) {
        HolderLookup.Provider reg = e.level().registryAccess();
        boolean any = false;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack s = e.getItemBySlot(slot);
            if (!dressed(s)) continue;
            any = true;
            e.setItemSlot(slot, restored(reg, s));
        }
        List<ItemStack> pack = e.getInventoryItems();
        for (int i = 0; i < pack.size(); i++) {
            ItemStack s = pack.get(i);
            if (!dressed(s)) continue;
            any = true;
            ItemStack back = restored(reg, s);
            if (back.isEmpty()) s.setCount(0);
            else pack.set(i, back);
        }
        Scoreboard board = e.level().getScoreboard();
        PlayerTeam t = board.getPlayersTeam(e.getScoreboardName());
        if (t != null && t.getName().startsWith(TEAM)) board.removePlayerFromTeam(e.getScoreboardName(), t);
        return any;
    }

    /** Is any of what it wears in battle dress? */
    public static boolean dressed(VillageFolkEntity e) {
        for (EquipmentSlot slot : EquipmentSlot.values()) if (dressed(e.getItemBySlot(slot))) return true;
        return false;
    }

    /** A folk come into the world still in battle dress with no army in the field for it (a restart, an unloaded chunk): out of it. */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent ev) {
        if (ev.getLevel().isClientSide() || !(ev.getEntity() instanceof VillageFolkEntity e) || !Campaigns.loaded() || Armies.inLine(e)) return;
        if (dressed(e)) undress(e);
    }

    /** A dressed piece as it was: a lent one taken back (the helmet under a standard returned), the rest their own colours again. */
    static ItemStack restored(HolderLookup.Provider reg, ItemStack s) {
        CompoundTag memo = memo(s);
        if (memo.getBoolean(LENT)) {
            return memo.contains(UNDER) ? ItemStack.parse(reg, memo.get(UNDER)).orElse(ItemStack.EMPTY) : ItemStack.EMPTY;
        }
        ItemStack back = s.copy();
        restore(reg, back, memo, DataComponents.TRIM, "trim");
        restore(reg, back, memo, DataComponents.DYED_COLOR, "dye");
        restore(reg, back, memo, DataComponents.BASE_COLOR, "base");
        restore(reg, back, memo, DataComponents.BANNER_PATTERNS, "arms");
        CustomData.update(DataComponents.CUSTOM_DATA, back, t -> t.remove(MARK));
        return back;
    }

    // ------------------------------------------------------------------ the mark

    /** Is this piece in battle dress (it remembers what it was)? */
    public static boolean dressed(ItemStack s) {
        CustomData data = s.isEmpty() ? null : s.get(DataComponents.CUSTOM_DATA);
        return data != null && data.contains(MARK);
    }

    /** Is this piece lent for the battle (a tunic, a helm standard)? */
    static boolean lent(ItemStack s) {
        return dressed(s) && memo(s).getBoolean(LENT);
    }

    private static CompoundTag lentMemo() {
        CompoundTag t = new CompoundTag();
        t.putBoolean(LENT, true);
        return t;
    }

    /** What the piece remembers of itself (a copy; empty for one not yet dressed). */
    static CompoundTag memo(ItemStack s) {
        CustomData data = s.get(DataComponents.CUSTOM_DATA);
        if (data == null || !data.contains(MARK)) return new CompoundTag();
        return data.copyTag().getCompound(MARK);
    }

    private static void mark(ItemStack s, CompoundTag memo) {
        CustomData.update(DataComponents.CUSTOM_DATA, s, t -> t.put(MARK, memo));
    }

    /**
     * What the piece had of this before it was first dressed, written into the memo (once: dressed again, the first
     * is kept). Whether it may be changed: false if it could not be written down, so it is left as it is.
     */
    private static <T> boolean remember(HolderLookup.Provider reg, ItemStack s, CompoundTag memo, DataComponentType<T> type, String key) {
        if (memo.contains(key) || memo.getBoolean(key + "_none")) return true;
        T v = s.get(type);
        if (v == null) {
            memo.putBoolean(key + "_none", true);
            return true;
        }
        Optional<Tag> t = type.codecOrThrow().encodeStart(reg.createSerializationContext(NbtOps.INSTANCE), v).result();
        t.ifPresent(x -> memo.put(key, x));
        return t.isPresent();
    }

    private static <T> void restore(HolderLookup.Provider reg, ItemStack s, CompoundTag memo, DataComponentType<T> type, String key) {
        if (memo.getBoolean(key + "_none")) {
            s.remove(type);
            return;
        }
        if (!memo.contains(key)) return;
        type.codecOrThrow().parse(reg.createSerializationContext(NbtOps.INSTANCE), memo.get(key)).result().ifPresent(v -> s.set(type, v));
    }

    // ------------------------------------------------------------------ the team

    static String teamName(UUID town) {
        return TEAM + town.toString().substring(0, 8);
    }

    /** The army's team: its name tags in its colour while the battle lasts. */
    static PlayerTeam team(ServerLevel level, Armies.Army a, Tint tint) {
        Scoreboard board = level.getScoreboard();
        String name = teamName(a.town);
        PlayerTeam t = board.getPlayerTeam(name);
        if (t == null) t = board.addPlayerTeam(name);
        t.setColor(tint.chat);
        t.setDisplayName(Component.literal("Army of " + Villages.name(a.town)));
        return t;
    }

    /** On its army's team, unless it is on a team of its own already (left be). */
    private static void join(Scoreboard board, PlayerTeam team, VillageFolkEntity e) {
        PlayerTeam now = board.getPlayersTeam(e.getScoreboardName());
        if (now == team || now != null && !now.getName().startsWith(TEAM)) return;
        board.addPlayerToTeam(e.getScoreboardName(), team);
    }

    /** The army's colour as a boss bar shows it (Battles' bar), never the other side's. */
    static BossEvent.BossBarColor bar(Tint t, @Nullable BossEvent.BossBarColor other) {
        if (other == null || t.bar != other) return t.bar;
        for (Tint f : FALLBACK) if (f.bar != other) return f.bar;
        return BossEvent.BossBarColor.PINK;
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the colours of a campaign's two armies. */
    public static Tint[] tintsForTests(Campaigns.Campaign c) {
        return tints(c);
    }

    /** Tests: a piece's trim material key, or null. */
    @Nullable
    public static ResourceKey<TrimMaterial> trimForTests(ItemStack s) {
        ArmorTrim t = s.get(DataComponents.TRIM);
        return t == null ? null : t.material().unwrapKey().orElse(null);
    }

    /** Tests: the name of the army's team for this town. */
    public static String teamForTests(UUID town) {
        return teamName(town);
    }
}
