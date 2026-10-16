package fr.sumenium.eco;

import fr.sumenium.eco.data.PlayerData;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;

/** Surnoms donnés par les admins (ex. pour repérer un second compte), affichés dans le chat et le tab. */
public final class Nicknames {

	private Nicknames() {}

	public static String of(ServerPlayer p) {
		if (!SumeniumEco.isReady()) return null;
		PlayerData d = SumeniumEco.store().players.get(p.getUUID().toString());
		return d == null || d.nickname == null || d.nickname.isBlank() ? null : d.nickname;
	}

	/** Nom affiché : le surnom s'il y en a un, sinon le pseudo. */
	public static String shown(ServerPlayer p) {
		String n = of(p);
		return n != null ? n : p.getPlainTextName();
	}

	/** Nom pour le tab (avec le tag de faction), ou null pour laisser le jeu faire. */
	public static Component tabName(ServerPlayer p) {
		String n = of(p);
		if (n == null) return null;
		return PlayerTeam.formatNameForTeam(p.getTeam(), Component.literal(n));
	}

	public static void refresh(MinecraftServer server, ServerPlayer p) {
		server.getPlayerList().broadcastAll(
				new ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME, p));
	}
}
