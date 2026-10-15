package fr.sumenium.eco.data;

import com.google.gson.JsonElement;

/** Une boutique posée sur un coffre ou un tonneau. */
public final class Shop {

	public enum Kind {
		/** Un joueur ou une faction vend des items stockés dans le coffre. */
		ITEM,
		/** Un joueur ou une faction vend un service : un bon nommé, stock infini. */
		SERVICE,
		/** Le FISC rachète un item (minerais) : le joueur clique avec l'item en main. */
		FISC_RACHAT,
		/** Le FISC vend un item, stock infini. */
		FISC_VENTE
	}

	public enum OwnerType { JOUEUR, FACTION, FISC }

	public String id;
	public Kind kind;
	public OwnerType ownerType;
	/** uuid du joueur, id de faction, ou "fisc". */
	public String ownerId;
	/** Créateur (joueur), pour l'historique. */
	public String creator;
	public String world;
	public int x, y, z;
	/** Item vendu ou racheté (format ItemStack.CODEC). Null pour un service. */
	public JsonElement item;
	/** Nom de l'item, pour l'export (lisible sans le jeu). */
	public String itemId;
	public String itemName;
	/** Quantité par achat. */
	public int quantity;
	/** Prix en centimes pour la quantité (FISC_RACHAT : prix unitaire). */
	public long price;
	/** Nom du bon pour un service. */
	public String serviceName;
	public long created;
	public long feePaid;
	public long salesCount;
	public long itemsMoved;
	public long revenue;
	/** Dernier stock connu en items (-1 = inconnu, ignoré pour les stocks infinis). */
	public int lastStock = -1;
	public boolean outOfStockNotified;
	/** Panneau rattaché à la boutique (null si aucun). */
	public Integer signX, signY, signZ;
	/** Dernier texte écrit sur le panneau, pour ne le réécrire que s'il change. */
	public transient String signCache;

	public boolean hasSign() {
		return signX != null && signY != null && signZ != null;
	}

	public String signKey() {
		return hasSign() ? key(world, signX, signY, signZ) : null;
	}

	public boolean isFisc() {
		return kind == Kind.FISC_RACHAT || kind == Kind.FISC_VENTE;
	}

	public boolean infiniteStock() {
		return kind != Kind.ITEM;
	}

	public String posKey() {
		return key(world, x, y, z);
	}

	public static String key(String world, int x, int y, int z) {
		return world + "|" + x + "|" + y + "|" + z;
	}
}
