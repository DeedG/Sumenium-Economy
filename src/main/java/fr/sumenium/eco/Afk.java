package fr.sumenium.eco;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Détection AFK (remplace le datapack "afk display") :
 * pseudo grisé avec [AFK] dans le tab, et joueurs AFK ignorés pour passer la nuit.
 */
public final class Afk {

	private static final Set<UUID> AFK = new HashSet<>();

	private Afk() {}

	public static boolean isAfk(ServerPlayer p) {
		return SumeniumEco.config().afkActif && AFK.contains(p.getUUID());
	}

	/** Les joueurs AFK sont traités comme des spectateurs par le calcul du sommeil. */
	public static boolean ignoredForSleep(ServerPlayer p) {
		return SumeniumEco.config().afkIgnoresPourDormir && isAfk(p);
	}

	/** Nom affiché dans le tab pendant l'AFK (null = affichage normal avec l'équipe). */
	public static Component tabName(ServerPlayer p) {
		if (!isAfk(p)) return null;
		return Component.literal("[AFK] " + p.getPlainTextName()).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
	}

	/** Appelé chaque seconde. */
	public static void tick(MinecraftServer server) {
		if (!SumeniumEco.config().afkActif) {
			if (!AFK.isEmpty()) {
				Set<UUID> was = new HashSet<>(AFK);
				AFK.clear();
				for (UUID id : was) {
					ServerPlayer p = server.getPlayerList().getPlayer(id);
					if (p != null) changed(server, p);
				}
			}
			return;
		}
		long now = Util.getMillis();
		long limit = SumeniumEco.config().afkSecondes * 1000L;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			boolean idle = now - p.getLastActionTime() >= limit;
			boolean was = AFK.contains(p.getUUID());
			if (idle && !was) {
				AFK.add(p.getUUID());
				changed(server, p);
			} else if (!idle && was) {
				AFK.remove(p.getUUID());
				changed(server, p);
			}
		}
	}

	public static void forget(ServerPlayer p) {
		AFK.remove(p.getUUID());
	}

	private static void changed(MinecraftServer server, ServerPlayer p) {
		server.getPlayerList().broadcastAll(
				new ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME, p));
		// recalcule le nombre de joueurs nécessaires pour dormir
		ServerLevel level = p.level();
		level.updateSleepingPlayerList();
	}
}
