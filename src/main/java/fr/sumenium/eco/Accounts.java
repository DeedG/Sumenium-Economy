package fr.sumenium.eco;

import fr.sumenium.eco.data.DataStore;
import fr.sumenium.eco.data.Faction;
import fr.sumenium.eco.data.PlayerData;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/** Comptes des joueurs et banques de faction. */
public final class Accounts {

	/** Un compte qu'on peut débiter ou créditer : un joueur ou une faction. */
	public static final class Account {
		public final PlayerData player;
		public final Faction faction;

		private Account(PlayerData p, Faction f) {
			this.player = p;
			this.faction = f;
		}

		public static Account of(PlayerData p) {
			return new Account(p, null);
		}

		public static Account of(Faction f) {
			return new Account(null, f);
		}

		public boolean isFaction() {
			return faction != null;
		}

		public long balance() {
			return faction != null ? faction.bank : player.balance;
		}

		public void add(long cents) {
			if (faction != null) faction.bank += cents;
			else player.balance += cents;
			SumeniumEco.store().markDirty();
		}

		public boolean withdraw(long cents) {
			if (balance() < cents) return false;
			add(-cents);
			return true;
		}

		public String ref() {
			return faction != null ? "faction:" + faction.id : "joueur:" + player.uuid;
		}

		public String label() {
			return faction != null ? "banque " + faction.tag : player.name;
		}
	}

	private final DataStore store;

	public Accounts(DataStore store) {
		this.store = store;
	}

	public PlayerData player(ServerPlayer p) {
		return player(p.getUUID().toString(), p.getPlainTextName());
	}

	public PlayerData player(String uuid, String name) {
		PlayerData d = store.players.get(uuid);
		if (d == null) {
			d = new PlayerData(uuid, name);
			store.players.put(uuid, d);
			store.markDirty();
		} else if (name != null && !name.equals(d.name)) {
			d.name = name;
			store.markDirty();
		}
		return d;
	}

	public PlayerData find(UUID uuid) {
		return store.players.get(uuid.toString());
	}

	public PlayerData findByName(String name) {
		for (PlayerData d : store.players.values()) {
			if (d.name != null && d.name.equalsIgnoreCase(name)) return d;
		}
		return null;
	}

	public Faction faction(String id) {
		if (id == null) return null;
		Faction f = store.factions.get(id);
		if (f != null) return f;
		for (Faction x : store.factions.values()) {
			if (x.id.equalsIgnoreCase(id) || x.tag.equalsIgnoreCase(id)) return x;
		}
		return null;
	}

	public Faction factionOf(PlayerData d) {
		if (d == null || d.faction == null) return null;
		Faction f = store.factions.get(d.faction);
		if (f == null || !f.members.containsKey(d.uuid)) return null;
		return f;
	}

	public boolean canSpend(PlayerData d, Faction f) {
		if (f == null) return false;
		Faction.Role r = f.role(d.uuid);
		if (r == null) return false;
		return r != Faction.Role.MEMBRE || SumeniumEco.config().factionMembresPeuventDepenser;
	}

	/**
	 * Compte actif du joueur : la banque de faction s'il l'a choisie avec /compte faction
	 * et qu'il a le droit de dépenser, sinon son compte perso.
	 */
	public Account active(ServerPlayer p) {
		PlayerData d = player(p);
		if (d.useFaction) {
			Faction f = factionOf(d);
			if (f != null && canSpend(d, f)) return Account.of(f);
		}
		return Account.of(d);
	}

	public Account personal(ServerPlayer p) {
		return Account.of(player(p));
	}

	/** Retrouve un compte à partir de sa référence "joueur:uuid" / "faction:id". */
	public Account byRef(String ref) {
		if (ref == null) return null;
		if (ref.startsWith("faction:")) {
			Faction f = store.factions.get(ref.substring(8));
			return f == null ? null : Account.of(f);
		}
		if (ref.startsWith("joueur:")) {
			PlayerData d = store.players.get(ref.substring(7));
			return d == null ? null : Account.of(d);
		}
		return null;
	}

	/** Somme de tout l'argent en circulation (joueurs + banques). */
	public long moneySupply() {
		long total = 0;
		for (PlayerData d : store.players.values()) total += d.balance;
		for (Faction f : store.factions.values()) total += f.bank;
		return total;
	}

	public static String normalizeId(String s) {
		return s.trim().replaceAll("[^A-Za-z0-9_\\-.]", "_");
	}
}
