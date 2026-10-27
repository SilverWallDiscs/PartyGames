package com.silverwalldiscs.partygames.mixin;

import com.silverwalldiscs.partygames.PartyGamesMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class HorseRaceDismountMixin {
    @Inject(method = "stopRiding", at = @At("HEAD"), cancellable = true)
    private void partyGames$preventHorseRaceDismount(CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer player
                && PartyGamesMod.instance() != null
                && PartyGamesMod.instance().shouldPreventHorseDismount(player)) {
            ci.cancel();
        }
    }
}
