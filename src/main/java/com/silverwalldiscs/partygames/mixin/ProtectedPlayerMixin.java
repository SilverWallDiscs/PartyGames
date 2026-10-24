package com.silverwalldiscs.partygames.mixin;

import com.silverwalldiscs.partygames.PartyGamesMod;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class ProtectedPlayerMixin {
    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void partyGames$preventDamage(ServerLevel level, DamageSource source, float damage,
                                           CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ServerPlayer player
                && PartyGamesMod.instance() != null
                && PartyGamesMod.instance().isProtectedPlayer(player.getUUID())) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "kill", at = @At("HEAD"), cancellable = true)
    private void partyGames$preventKill(ServerLevel level, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer player
                && PartyGamesMod.instance() != null
                && PartyGamesMod.instance().isProtectedPlayer(player.getUUID())) {
            ci.cancel();
        }
    }
}
