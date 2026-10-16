package fr.sumenium.eco.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Surnom à la place du pseudo dans le chat (et les messages de connexion, de mort…). Le survol garde le vrai pseudo. */
@Mixin(Player.class)
public abstract class PlayerMixin {

	@Redirect(method = "getDisplayName", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/player/Player;getName()Lnet/minecraft/network/chat/Component;"))
	private Component sumeco$nickname(Player self) {
		if (self instanceof ServerPlayer sp) {
			String n = fr.sumenium.eco.Nicknames.of(sp);
			if (n != null) return Component.literal(n);
		}
		return self.getName();
	}
}
