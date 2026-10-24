package com.silverwalldiscs.partygames.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class RaceBoatPhysicsMixin {
    private static final String PARTY_GAMES_RACE_BOAT_TAG = "partygames_race_boat";

    @Inject(method = "maxUpStep", at = @At("HEAD"), cancellable = true)
    private void partyGames$allowOneBlockStep(CallbackInfoReturnable<Float> cir) {
        Entity self = (Entity) (Object) this;
        if (self.getTags().contains(PARTY_GAMES_RACE_BOAT_TAG)) {
            cir.setReturnValue(1.0F);
        }
    }

    @Inject(method = "canCollideWith", at = @At("HEAD"), cancellable = true)
    private void partyGames$disableRaceBoatCollision(Entity other, CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (self.getTags().contains(PARTY_GAMES_RACE_BOAT_TAG)
                && other != null
                && other.getTags().contains(PARTY_GAMES_RACE_BOAT_TAG)) {
            cir.setReturnValue(false);
        }
    }
}
