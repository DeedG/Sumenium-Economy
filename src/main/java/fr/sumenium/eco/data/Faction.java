package fr.sumenium.eco.data;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Une faction : membres, rôles, banque. Son id est aussi le nom de l'équipe vanilla. */
public final class Faction {

	public enum Role {
		MEMBRE, TRESORIER, CHEF;

		public String label() {
			return switch (this) {
				case MEMBRE -> "membre";
				case TRESORIER -> "trésorier";
				case CHEF -> "chef";
			};
		}
	}

	public String id;
	/** Tag court affiché devant le pseudo, ex. "LOGA". */
	public String tag;
	/** Couleur vanilla (red, gold, aqua…). */
	public String color = "white";
	/** Banque en centimes. */
	public long bank;
	public long created;
	/** uuid -> rôle */
	public Map<String, Role> members = new LinkedHashMap<>();
	/** uuids invités, en attente de /faction rejoindre */
	public Set<String> invites = new HashSet<>();

	public Faction() {}

	public Faction(String id, String tag, String color) {
		this.id = id;
		this.tag = tag;
		this.color = color;
		this.created = System.currentTimeMillis();
	}

	public Role role(String uuid) {
		return members.get(uuid);
	}
}
