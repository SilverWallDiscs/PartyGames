package com.silverwalldiscs.partygames.mixin;

import com.silverwalldiscs.partygames.PartyGamesMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerDropMixin {
    @Inject(method = "drop", at = @At("HEAD"), cancellable = true)
    private void partyGames$preventDrop(boolean entireStack, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ServerPlayer player
                && PartyGamesMod.instance() != null
                && PartyGamesMod.instance().isProtectedPlayer(player.getUUID())) {
            cir.setReturnValue(false);
        }
    }
}
