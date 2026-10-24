package com.silverwalldiscs.partygames;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Container;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;

public final class PartyGamesAdminMenu extends ChestMenu {
    public enum View {
        MAIN,
        PLACES,
        ITEMS,
        COMMANDS
    }

    public static final class AdminContainer extends SimpleContainer {
        private final View view;

        public AdminContainer(int size, View view) {
            super(size);
            this.view = view;
        }

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            // Only the input area of the item editor accepts actual inventory items.
            return view == View.ITEMS && slot >= 9 && slot <= 35;
        }
    }

    private final ServerPlayer owner;
    private final PartyGamesManager manager;
    private final View view;
    private final int place;
    private final int page;
    private final AdminContainer container;
    private boolean committed;

    public PartyGamesAdminMenu(int containerId, Inventory playerInventory, ServerPlayer owner,
                               PartyGamesManager manager, View view, int place, int page,
                               AdminContainer container) {
        super(container.getContainerSize() == 27 ? MenuType.GENERIC_9x3 : MenuType.GENERIC_9x6,
                containerId, playerInventory, container,
                container.getContainerSize() == 27 ? 3 : 6);
        this.owner = owner;
        this.manager = manager;
        this.view = view;
        this.place = place;
        this.page = page;
        this.container = container;
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (!owner.getUUID().equals(player.getUUID())) {
            return;
        }

        if (slotId >= 0 && slotId < container.getContainerSize()) {
            if (view == View.ITEMS && slotId >= 9 && slotId <= 35) {
                super.clicked(slotId, button, clickType, player);
                return;
            }

            if (clickType == ClickType.PICKUP) {
                manager.handleAdminMenuClick(owner, view, place, page, slotId, button, container);
            }
            return;
        }

        super.clicked(slotId, button, clickType, player);
    }

    @Override
    public boolean stillValid(Player player) {
        return owner.getUUID().equals(player.getUUID());
    }

    @Override
    public void removed(Player player) {
        if (view == View.ITEMS && !committed) {
            manager.returnUncommittedItems(owner, container);
        }
        super.removed(player);
    }

    public void markCommitted() {
        committed = true;
    }

    public View view() {
        return view;
    }

    public static void open(ServerPlayer player, PartyGamesManager manager, View view, int place,
                            int page, AdminContainer container, String title) {
        player.openMenu(new net.minecraft.world.SimpleMenuProvider(
                (containerId, inventory, ignoredPlayer) ->
                        new PartyGamesAdminMenu(containerId, inventory, player, manager, view, place, page, container),
                Component.literal(title)));
    }
}
