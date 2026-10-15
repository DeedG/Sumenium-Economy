package fr.sumenium.eco.mixin;

import fr.sumenium.eco.shop.Redstone;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Signal de redstone émis par un coffre de boutique après une vente ou par un dépôt de service. */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateSignalMixin {

	@Inject(method = "getSignal", at = @At("HEAD"), cancellable = true)
	private void sumeco$signal(BlockGetter level, BlockPos pos, Direction dir, CallbackInfoReturnable<Integer> cir) {
		if (Redstone.isPowered(level, pos)) cir.setReturnValue(15);
	}

	@Inject(method = "getDirectSignal", at = @At("HEAD"), cancellable = true)
	private void sumeco$directSignal(BlockGetter level, BlockPos pos, Direction dir, CallbackInfoReturnable<Integer> cir) {
		if (Redstone.isPowered(level, pos)) cir.setReturnValue(15);
	}
}
