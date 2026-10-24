package com.silverwalldiscs.partygames.mixin;

import com.silverwalldiscs.partygames.PartyGamesMod;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ProtectedCommandsMixin {
    @Shadow
    public abstract ServerPlayer getPlayer();

    @Inject(method = "handleChatCommand", at = @At("HEAD"), cancellable = true)
    private void partyGames$captureOrBlockCommand(ServerboundChatCommandPacket packet, CallbackInfo ci) {
        partyGames$captureOrBlock(packet.command(), ci);
    }

    @Inject(method = "handleSignedChatCommand", at = @At("HEAD"), cancellable = true)
    private void partyGames$captureOrBlockSignedCommand(ServerboundChatCommandSignedPacket packet, CallbackInfo ci) {
        partyGames$captureOrBlock(packet.command(), ci);
    }

    private void partyGames$captureOrBlock(String command, CallbackInfo ci) {
        PartyGamesMod mod = PartyGamesMod.instance();
        if (mod == null) return;

        ServerPlayer player = this.getPlayer();
        if (mod.capturePendingPrizeCommand(player, command)) {
            ci.cancel();
            return;
        }

        if (mod.isCommandsBlocked(player)) {
            player.sendSystemMessage(Component.literal(
                    "Commands are disabled inside the PartyGames dimension. You will be able to use them again after returning."));
            ci.cancel();
        }
    }
}
