package fr.sumenium.eco.data;

import java.util.ArrayList;
import java.util.List;

/** Compte et préférences d'un joueur. */
public final class PlayerData {
	public String uuid;
	public String name;
	/** Solde en centimes de sumenien. */
	public long balance;
	/** Identifiant de la faction, null si sans faction. */
	public String faction;
	/** Si vrai, achats et créations de boutique utilisent la banque de faction. */
	public boolean useFaction;
	public long lastSeen;
	/** Surnom affiché dans le chat et le tab (donné par un admin), null sinon. */
	public String nickname;
	/** Ventes réalisées pendant que le joueur était déconnecté (pour le récap). */
	public int offlineSales;
	public long offlineRevenue;
	/** Messages en attente (ruptures de stock, etc.). */
	public List<String> pending = new ArrayList<>();

	public PlayerData() {}

	public PlayerData(String uuid, String name) {
		this.uuid = uuid;
		this.name = name;
	}
}
