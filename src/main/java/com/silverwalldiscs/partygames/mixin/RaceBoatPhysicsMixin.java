package com.silverwalldiscs.partygames.mixin;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class RaceBoatPhysicsMixin {
    private boolean partyGames$isRaceBoat(Entity entity) {
        if (entity == null
                || !"partygames:event".equals(entity.level().dimension().identifier().toString())) {
            return false;
        }
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return id != null && "minecraft:oak_boat".equals(id.toString());
    }

    @Inject(method = "maxUpStep", at = @At("HEAD"), cancellable = true)
    private void partyGames$allowOneBlockStep(CallbackInfoReturnable<Float> cir) {
        if (partyGames$isRaceBoat((Entity) (Object) this)) {
            cir.setReturnValue(1.0F);
        }
    }

    @Inject(method = "canCollideWith", at = @At("HEAD"), cancellable = true)
    private void partyGames$disableRaceBoatCollision(Entity other, CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (partyGames$isRaceBoat(self) && partyGames$isRaceBoat(other)) {
            cir.setReturnValue(false);
        }
    }
}
