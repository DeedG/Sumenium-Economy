package fr.sumenium.eco.faction;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import fr.sumenium.eco.SumeniumEco;
import fr.sumenium.eco.data.DataStore;
import fr.sumenium.eco.data.Faction;
import fr.sumenium.eco.data.PlayerData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.TeamColor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Gestion des factions et synchronisation avec les équipes vanilla
 * (même nom), qui affichent le tag dans le tab, le chat et au-dessus de la tête.
 */
public final class Factions {

	/** Équipe utilisée par l'ancien datapack AFK : à vider, le mod gère l'AFK lui-même. */
	public static final String OLD_AFK_TEAM = "afkDis.afk";
	private static final String OLD_AFK_TAG = "afkDis.t_";

	private final DataStore store;

	public Factions(DataStore store) {
		this.store = store;
	}

	private MinecraftServer server() {
		return SumeniumEco.server();
	}

	private boolean sync() {
		return SumeniumEco.config().factionSynchroEquipes && server() != null;
	}

	public static Optional<TeamColor> color(String name) {
		if (name == null) return Optional.empty();
		try {
			return Optional.ofNullable(TeamColor.byName(name.toLowerCase(Locale.ROOT)));
		} catch (RuntimeException e) {
			return Optional.empty();
		}
	}

	public static ChatFormatting chatColor(String name) {
		if (name == null) return ChatFormatting.WHITE;
		try {
			ChatFormatting f = ChatFormatting.valueOf(name.toUpperCase(Locale.ROOT));
			return f.ordinal() <= ChatFormatting.WHITE.ordinal() ? f : ChatFormatting.WHITE;
		} catch (IllegalArgumentException e) {
			return ChatFormatting.WHITE;
		}
	}

	public Faction create(String id, String tag, String color) {
		Faction f = new Faction(id, tag, color);
		store.factions.put(id, f);
		store.markDirty();
		if (sync()) {
			ServerScoreboard sb = server().getScoreboard();
			PlayerTeam team = sb.getPlayerTeam(id);
			if (team == null) {
				team = sb.addPlayerTeam(id);
				applyStyle(f, team);
			}
		}
		return f;
	}

	public void applyStyle(Faction f) {
		if (!sync()) return;
		PlayerTeam team = team(f);
		applyStyle(f, team);
	}

	private void applyStyle(Faction f, PlayerTeam team) {
		ChatFormatting c = chatColor(f.color);
		team.setDisplayName(Component.literal(f.tag));
		team.setColor(color(f.color));
		team.setPlayerPrefix(Component.literal("[" + f.tag + "] ").withStyle(c));
	}

	private PlayerTeam team(Faction f) {
		ServerScoreboard sb = server().getScoreboard();
		PlayerTeam team = sb.getPlayerTeam(f.id);
		if (team == null) {
			team = sb.addPlayerTeam(f.id);
			applyStyle(f, team);
		}
		return team;
	}

	public boolean isFull(Faction f) {
		return f.members.size() >= SumeniumEco.config().factionMembresMax;
	}

	/** Ajoute le joueur (en le retirant de son ancienne faction). */
	public void addMember(PlayerData d, Faction f, Faction.Role role) {
		Faction old = SumeniumEco.accounts().factionOf(d);
		if (old != null && old != f) old.members.remove(d.uuid);
		f.members.put(d.uuid, role);
		f.invites.remove(d.uuid);
		d.faction = f.id;
		store.markDirty();
		if (sync() && d.name != null) {
			server().getScoreboard().addPlayerToTeam(d.name, team(f));
		}
	}

	public void removeMember(PlayerData d) {
		Faction f = SumeniumEco.accounts().factionOf(d);
		if (f != null) f.members.remove(d.uuid);
		d.faction = null;
		d.useFaction = false;
		store.markDirty();
		if (sync() && d.name != null) {
			ServerScoreboard sb = server().getScoreboard();
			PlayerTeam t = sb.getPlayersTeam(d.name);
			if (t != null && f != null && t.getName().equals(f.id)) sb.removePlayerFromTeam(d.name, t);
		}
	}

	/** Supprime la faction ; la banque revient au chef (ou au premier membre). */
	public PlayerData delete(Faction f) {
		PlayerData heir = null;
		for (var e : f.members.entrySet()) {
			PlayerData d = store.players.get(e.getKey());
			if (d == null) continue;
			if (heir == null || e.getValue() == Faction.Role.CHEF) heir = d;
		}
		if (heir != null && f.bank > 0) heir.balance += f.bank;
		f.bank = 0;
		for (String uuid : new ArrayList<>(f.members.keySet())) {
			PlayerData d = store.players.get(uuid);
			if (d != null) removeMember(d);
		}
		store.factions.remove(f.id);
		store.markDirty();
		return heir;
	}

	/**
	 * À la connexion : remet le joueur dans l'équipe de sa faction
	 * (corrige aussi les joueurs restés dans l'équipe AFK de l'ancien datapack).
	 */
	public void syncOnJoin(ServerPlayer p, PlayerData d) {
		if (!sync()) return;
		ServerScoreboard sb = server().getScoreboard();
		String name = p.getPlainTextName();
		Faction f = SumeniumEco.accounts().factionOf(d);
		// Ancien datapack AFK : le joueur garde un tag avec le nom de son équipe d'origine.
		for (String tag : new ArrayList<>(p.entityTags())) {
			if (!tag.startsWith(OLD_AFK_TAG)) continue;
			String original = tag.substring(OLD_AFK_TAG.length());
			if (f == null) {
				Faction from = store.factions.get(original);
				if (from != null && !isFull(from)) {
					addMember(d, from, Faction.Role.MEMBRE);
					f = from;
				} else if (sb.getPlayerTeam(original) != null) {
					// faction pas encore importée : on remet au moins le joueur dans son équipe
					sb.addPlayerToTeam(name, sb.getPlayerTeam(original));
				}
			}
			p.removeTag(tag);
		}
		PlayerTeam current = sb.getPlayersTeam(name);
		if (f != null) {
			if (current == null || !current.getName().equals(f.id)) sb.addPlayerToTeam(name, team(f));
		} else if (current != null && (current.getName().equals(OLD_AFK_TEAM) || store.factions.containsKey(current.getName()))) {
			sb.removePlayerFromTeam(name, current);
		}
	}

	/**
	 * Crée une faction pour chaque équipe vanilla qui a des joueurs
	 * (les pseudos sont retrouvés dans usercache.json et les comptes connus).
	 */
	public List<String> importTeams() {
		List<String> report = new ArrayList<>();
		ServerScoreboard sb = server().getScoreboard();
		Map<String, String> uuids = nameToUuid();
		for (PlayerTeam team : new ArrayList<>(sb.getPlayerTeams())) {
			String id = team.getName();
			if (id.startsWith("afkDis") || team.getPlayers().isEmpty()) continue;
			Faction f = store.factions.get(id);
			int added = 0;
			if (f == null) {
				String tag = team.getDisplayName().getString();
				if (tag.isBlank()) tag = id;
				String color = team.getColor().map(TeamColor::getSerializedName).orElse("white");
				f = new Faction(id, tag, color);
				store.factions.put(id, f);
			}
			for (String member : new ArrayList<>(team.getPlayers())) {
				String uuid = uuids.get(member.toLowerCase(Locale.ROOT));
				if (uuid == null) {
					report.add("  " + member + " : UUID inconnu, à ajouter à la main");
					continue;
				}
				PlayerData d = SumeniumEco.accounts().player(uuid, member);
				if (!f.members.containsKey(uuid)) {
					f.members.put(uuid, Faction.Role.MEMBRE);
					added++;
				}
				d.faction = f.id;
			}
			report.add(0, id + " : " + added + " membre(s) ajouté(s)");
		}
		store.markDirty();
		return report;
	}

	private Map<String, String> nameToUuid() {
		Map<String, String> map = new HashMap<>();
		for (PlayerData d : store.players.values()) {
			if (d.name != null) map.put(d.name.toLowerCase(Locale.ROOT), d.uuid);
		}
		Path cache = SumeniumEco.gameDir().resolve("usercache.json");
		try {
			if (Files.exists(cache)) {
				JsonArray arr = JsonParser.parseString(Files.readString(cache, StandardCharsets.UTF_8)).getAsJsonArray();
				for (JsonElement e : arr) {
					var o = e.getAsJsonObject();
					map.putIfAbsent(o.get("name").getAsString().toLowerCase(Locale.ROOT), o.get("uuid").getAsString());
				}
			}
		} catch (Exception e) {
			SumeniumEco.LOGGER.warn("usercache.json illisible", e);
		}
		for (ServerPlayer p : server().getPlayerList().getPlayers()) {
			map.put(p.getPlainTextName().toLowerCase(Locale.ROOT), p.getUUID().toString());
		}
		return map;
	}
}
