package fr.sumenium.eco.mixin;

import fr.sumenium.eco.Afk;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.SleepStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Les joueurs AFK ne comptent pas dans le pourcentage de joueurs qui doivent dormir. */
@Mixin(SleepStatus.class)
public abstract class SleepStatusMixin {

	@Redirect(method = "update",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;isSpectator()Z"))
	private boolean sumeco$ignoreAfk(ServerPlayer player) {
		return player.isSpectator() || Afk.ignoredForSleep(player);
	}
}
