package com.jrpetty.mcassistant.menu;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * The screen menu behind the right-click GUI. Lays out the assistant's backpack
 * (27) and equipment (6) alongside the player's own inventory, and routes the
 * on-screen control buttons (Stop / Follow / Stay / Guard / Deposit / Come)
 * through clickMenuButton so they run server-side on the real entity.
 */
public class AssistantMenu extends AbstractContainerMenu {

    // Button ids fired by the screen widgets.
    public static final int BTN_STOP = 0;
    public static final int BTN_FOLLOW = 1;
    public static final int BTN_STAY = 2;
    public static final int BTN_GUARD = 3;
    public static final int BTN_DEPOSIT = 4;
    public static final int BTN_COME = 5;
    // Specialisation panel.
    public static final int BTN_JOB_PREV = 6;
    public static final int BTN_JOB_NEXT = 7;
    public static final int BTN_WORK = 8;
    public static final int BTN_DEPTH_DOWN = 9;
    public static final int BTN_DEPTH_UP = 10;

    private final Container container;
    @Nullable private final AssistantEntity assistant;

    // ----- server-side constructor (has the real entity) -----
    public AssistantMenu(int containerId, Inventory playerInv, AssistantEntity assistant) {
        this(containerId, playerInv, assistant, new AssistantInventoryContainer(assistant));
    }

    // ----- client-side constructor (entity id arrives in the buffer) -----
    public AssistantMenu(int containerId, Inventory playerInv, RegistryFriendlyByteBuf buf) {
        this(containerId, playerInv, resolve(playerInv, buf.readVarInt()), null);
    }

    private AssistantMenu(int containerId, Inventory playerInv, @Nullable AssistantEntity assistant, @Nullable Container serverContainer) {
        super(com.jrpetty.mcassistant.McAssistantMod.ASSISTANT_MENU.get(), containerId);
        this.assistant = assistant;
        this.container = serverContainer != null ? serverContainer
            : (assistant != null ? new AssistantInventoryContainer(assistant) : new net.minecraft.world.SimpleContainer(AssistantInventoryContainer.SIZE));

        // Backpack: 3 rows x 9, top-left.
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(container, col + row * 9, 8 + col * 18, 18 + row * 18));
            }
        }

        // Equipment: a row of 6 (helmet, chest, legs, boots, main hand, off hand).
        for (int i = 0; i < AssistantInventoryContainer.EQUIP; i++) {
            final EquipmentSlot eq = AssistantInventoryContainer.EQUIPMENT[i];
            this.addSlot(new Slot(container, AssistantInventoryContainer.BACKPACK + i, 8 + i * 18, 78) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return acceptsEquipment(assistant, eq, stack);
                }

                @Override
                public int getMaxStackSize() {
                    return 1;
                }
            });
        }

        // Player inventory (3 rows) + hotbar. Left low enough for two button rows.
        int invY = 160; // leaves room for the specialisation panel above
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInv, col + row * 9 + 9, 8 + col * 18, invY + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInv, col, 8 + col * 18, invY + 58));
        }
    }

    @Nullable
    private static AssistantEntity resolve(Inventory playerInv, int entityId) {
        Entity e = playerInv.player.level().getEntity(entityId);
        return e instanceof AssistantEntity a ? a : null;
    }

    private static boolean acceptsEquipment(@Nullable AssistantEntity assistant, EquipmentSlot slot, ItemStack stack) {
        if (stack.isEmpty()) return true;
        if (slot == EquipmentSlot.MAINHAND || slot == EquipmentSlot.OFFHAND) return true;
        // Armor slots: only accept an item that would actually go in that slot.
        if (assistant != null) {
            return assistant.getEquipmentSlotForItem(stack) == slot;
        }
        return true;
    }

    @Nullable
    public AssistantEntity getAssistant() {
        return assistant;
    }

    @Override
    public boolean stillValid(Player player) {
        return container.stillValid(player);
    }

    /** Step through the specialisations and report the new job's checklist. */
    private void cycleJob(int step) {
        if (assistant == null) return;
        AssistantEntity.StationTask next =
            AssistantEntity.StationTask.byOrdinal(assistant.stationTask().ordinal() + step);
        assistant.setJob(next);
        if (next == AssistantEntity.StationTask.NONE) {
            assistant.say("Off the roster — give me a job when you're ready.");
            return;
        }
        java.util.List<String> gaps = assistant.missingEssentials();
        assistant.say("I can be your " + next.title.toLowerCase() + ". "
            + (assistant.workZone() == null
                ? "Mark me a work zone with the Zone Marker."
                : gaps.isEmpty() ? "I've got everything I need — say the word."
                                 : "I still need " + String.join(", ", gaps) + "."));
    }

    /** Send the specialist to work, or explain exactly what's stopping it. */
    private void startWork() {
        if (assistant == null) return;
        if (assistant.stationTask() == AssistantEntity.StationTask.NONE) {
            assistant.say("Pick what I should specialise in first.");
            return;
        }
        if (assistant.workZone() == null) {
            assistant.say("I need a work zone — mark one out and hand it to me.");
            return;
        }
        java.util.List<String> gaps = assistant.missingEssentials();
        if (!gaps.isEmpty()) {
            assistant.say("Not yet — I need " + String.join(", ", gaps) + ".");
            return;
        }
        assistant.clearQueue();
        assistant.setAutonomous(true);
        assistant.say("On it — working my zone as your " + assistant.stationTask().title.toLowerCase() + ".");
    }

    /** Miner only: how deep the shaft goes. */
    private void adjustDepth(int delta) {
        if (assistant == null) return;
        com.jrpetty.mcassistant.entity.WorkZone zone = assistant.workZone();
        if (zone == null) {
            assistant.say("Mark me a zone first, then I'll know where to dig.");
            return;
        }
        assistant.setWorkZone(zone.withDepth(zone.depth() + delta));
        assistant.say("I'll dig down to Y" + assistant.workZone().depth() + ".");
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (assistant == null || !assistant.isOwner(player)) return false;
        switch (id) {
            case BTN_STOP -> assistant.requestStop();
            case BTN_FOLLOW -> { assistant.clearQueue(); assistant.setMode(AssistantEntity.Mode.FOLLOW); assistant.say("Following you."); }
            case BTN_STAY -> { assistant.clearQueue(); assistant.setMode(AssistantEntity.Mode.STAY); assistant.say("Holding here."); }
            case BTN_GUARD -> { assistant.clearQueue(); assistant.setMode(AssistantEntity.Mode.GUARD); assistant.say("Guard mode on."); }
            case BTN_DEPOSIT -> assistant.requestDeposit();
            case BTN_COME -> {
                assistant.clearQueue();
                assistant.setMode(AssistantEntity.Mode.FOLLOW);
                assistant.getNavigation().moveTo(player, 1.25D);
                assistant.say("Coming.");
            }
            case BTN_JOB_PREV -> cycleJob(-1);
            case BTN_JOB_NEXT -> cycleJob(1);
            case BTN_WORK -> startWork();
            case BTN_DEPTH_DOWN -> adjustDepth(-8);
            case BTN_DEPTH_UP -> adjustDepth(8);
            default -> { return false; }
        }
        return true;
    }

    // Shift-click movement between the assistant's slots and the player's.
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) return result;

        ItemStack stack = slot.getItem();
        result = stack.copy();

        final int assistantEnd = AssistantInventoryContainer.SIZE; // 33
        final int playerEnd = this.slots.size(); // 33 + 36 = 69

        if (index < assistantEnd) {
            // From the assistant -> into the player's inventory.
            if (!this.moveItemStackTo(stack, assistantEnd, playerEnd, true)) return ItemStack.EMPTY;
        } else {
            // From the player -> try the assistant's backpack (0..26).
            if (!this.moveItemStackTo(stack, 0, AssistantInventoryContainer.BACKPACK, false)) return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return result;
    }
}
