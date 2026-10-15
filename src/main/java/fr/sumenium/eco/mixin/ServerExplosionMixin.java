package fr.sumenium.eco.mixin;

import fr.sumenium.eco.SumeniumEco;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Les explosions ne cassent pas les boutiques. */
@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {

	@Shadow
	@Final
	private ServerLevel level;

	@Inject(method = "interactWithBlocks", at = @At("HEAD"))
	private void sumeco$protectShops(List<BlockPos> blocks, CallbackInfo ci) {
		try {
			blocks.removeIf(pos -> SumeniumEco.isProtected(level, pos));
		} catch (UnsupportedOperationException ignored) {
			// liste non modifiable : rien à faire
		}
	}
}
