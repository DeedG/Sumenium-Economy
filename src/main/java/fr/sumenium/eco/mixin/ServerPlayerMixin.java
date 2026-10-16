package fr.sumenium.eco.mixin;

import fr.sumenium.eco.Afk;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Pseudo "[AFK]" grisé et surnom dans le tab. */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {

	@Inject(method = "getTabListDisplayName", at = @At("HEAD"), cancellable = true)
	private void sumeco$afkName(CallbackInfoReturnable<Component> cir) {
		Component name = Afk.tabName((ServerPlayer) (Object) this);
		if (name == null) name = fr.sumenium.eco.Nicknames.tabName((ServerPlayer) (Object) this);
		if (name != null) cir.setReturnValue(name);
	}
}
