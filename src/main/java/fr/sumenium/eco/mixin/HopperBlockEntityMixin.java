package fr.sumenium.eco.mixin;

import fr.sumenium.eco.SumeniumEco;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Les entonnoirs (et wagonnets à entonnoir) ne peuvent pas vider une boutique. */
@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin {

	@Inject(method = "getSourceContainer", at = @At("HEAD"), cancellable = true)
	private static void sumeco$protectShops(Level level, Hopper hopper, BlockPos pos, BlockState state,
			CallbackInfoReturnable<Container> cir) {
		if (SumeniumEco.isProtected(level, pos)) cir.setReturnValue(null);
	}
}
