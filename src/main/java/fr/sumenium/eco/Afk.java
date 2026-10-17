package fr.sumenium.eco;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Détection AFK (remplace le datapack "afk display") :
 * pseudo grisé avec [AFK] dans le tab, et joueurs AFK ignorés pour passer la nuit.
 * <p>
 * Seuls comptent comme activité : tourner la caméra, marcher (hors véhicule et hors eau, pour ignorer
 * les courants et les fermes) et écrire dans le chat. Les clics (autoclicker) ne retirent pas l'AFK.
 */
public final class Afk {

	private static final Set<UUID> AFK = new HashSet<>();
	private static final Map<UUID, Seen> SEEN = new HashMap<>();

	/** Dernière position et orientation vues, et moment de la dernière vraie activité. */
	private static final class Seen {
		double x, z;
		float yaw, pitch;
		long active;
	}

	/** Le joueur a écrit dans le chat ou lancé une commande. */
	public static void activity(ServerPlayer p) {
		Seen s = SEEN.get(p.getUUID());
		if (s != null) s.active = Util.getMillis();
	}

	/** Met à jour l'activité du joueur à partir de ses mouvements ; renvoie le moment de la dernière activité. */
	static long lastActive(ServerPlayer p, long now) {
		Seen s = SEEN.get(p.getUUID());
		if (s == null) {
			s = new Seen();
			s.x = p.getX();
			s.z = p.getZ();
			s.yaw = p.getYRot();
			s.pitch = p.getXRot();
			s.active = now;
			SEEN.put(p.getUUID(), s);
			return now;
		}
		boolean turned = Math.abs(net.minecraft.util.Mth.wrapDegrees(p.getYRot() - s.yaw)) > 1.0f
				|| Math.abs(p.getXRot() - s.pitch) > 1.0f;
		double dx = p.getX() - s.x, dz = p.getZ() - s.z;
		boolean walked = dx * dx + dz * dz > 0.04 && !p.isPassenger() && !p.isInWater() && !p.isFallFlying();
		if (turned || walked) s.active = now;
		s.x = p.getX();
		s.z = p.getZ();
		s.yaw = p.getYRot();
		s.pitch = p.getXRot();
		return s.active;
	}

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
		return Component.literal("[AFK] " + Nicknames.shown(p)).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
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
			boolean idle = now - lastActive(p, now) >= limit;
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

	/** Pour le test de démarrage uniquement. */
	static void markForTest(UUID id) {
		AFK.add(id);
	}

	public static void forget(ServerPlayer p) {
		AFK.remove(p.getUUID());
		SEEN.remove(p.getUUID());
	}

	private static void changed(MinecraftServer server, ServerPlayer p) {
		server.getPlayerList().broadcastAll(
				new ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME, p));
		// recalcule le nombre de joueurs nécessaires pour dormir
		ServerLevel level = p.level();
		level.updateSleepingPlayerList();
	}
}
