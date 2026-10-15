package fr.sumenium.eco.mixin;

import fr.sumenium.eco.SumeniumEco;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.behavior.TransportItemsBetweenContainers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Les golems de cuivre ne prennent ni ne déposent rien dans une boutique. */
@Mixin(TransportItemsBetweenContainers.TransportItemTarget.class)
public abstract class TransportItemTargetMixin {

	@Inject(method = "tryCreatePossibleTarget(Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/level/Level;)Lnet/minecraft/world/entity/ai/behavior/TransportItemsBetweenContainers$TransportItemTarget;",
			at = @At("HEAD"), cancellable = true)
	private static void sumeco$protectShops(BlockEntity be, Level level,
			CallbackInfoReturnable<TransportItemsBetweenContainers.TransportItemTarget> cir) {
		if (be != null && SumeniumEco.isProtected(level, be.getBlockPos())) cir.setReturnValue(null);
	}

	@Inject(method = "tryCreatePossibleTarget(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/Level;)Lnet/minecraft/world/entity/ai/behavior/TransportItemsBetweenContainers$TransportItemTarget;",
			at = @At("HEAD"), cancellable = true)
	private static void sumeco$protectShopsPos(BlockPos pos, Level level,
			CallbackInfoReturnable<TransportItemsBetweenContainers.TransportItemTarget> cir) {
		if (SumeniumEco.isProtected(level, pos)) cir.setReturnValue(null);
	}
}
