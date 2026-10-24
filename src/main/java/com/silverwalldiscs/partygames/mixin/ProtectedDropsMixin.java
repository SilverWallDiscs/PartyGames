package com.silverwalldiscs.partygames.mixin;

import com.silverwalldiscs.partygames.PartyGamesMod;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ProtectedDropsMixin {
    @Shadow
    public abstract ServerPlayer getPlayer();

    @Inject(method = "handlePlayerAction", at = @At("HEAD"), cancellable = true)
    private void partyGames$preventQDrop(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        PartyGamesMod mod = PartyGamesMod.instance();
        if (mod == null) return;
        ServerPlayer player = this.getPlayer();
        if (!mod.isProtectedPlayer(player.getUUID())) return;

        ServerboundPlayerActionPacket.Action action = packet.getAction();
        if (action == ServerboundPlayerActionPacket.Action.DROP_ITEM
                || action == ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS) {
            player.sendSystemMessage(Component.literal("You cannot drop items during PartyGames."));
            ci.cancel();
        }
    }
}
