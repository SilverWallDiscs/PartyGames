package com.silverwalldiscs.partygames.mixin;

import com.silverwalldiscs.partygames.PartyGamesMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerMenu.class)
public abstract class ProtectedContainerDropMixin {
    @Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
    private void partyGames$preventInventoryDrop(int slotIndex, int buttonNum, ContainerInput input,
                                                  Player player, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer serverPlayer)
                || PartyGamesMod.instance() == null
                || !PartyGamesMod.instance().isProtectedPlayer(serverPlayer.getUUID())) {
            return;
        }

        // THROW covers Q / Ctrl+Q while hovering a slot; -999 is the drop-outside-container action.
        if (input == ContainerInput.THROW || slotIndex == -999) {
            serverPlayer.sendSystemMessage(Component.literal("You cannot drop items during PartyGames."));
            ci.cancel();
        }
    }
}
