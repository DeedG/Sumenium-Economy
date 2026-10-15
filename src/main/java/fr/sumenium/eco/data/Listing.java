package fr.sumenium.eco.data;

import com.google.gson.JsonElement;

/** Une annonce de l'hôtel des ventes (enchères, avec achat immédiat facultatif). */
public final class Listing {
	public String id;
	public String seller;
	public String sellerName;
	/** Compte crédité à la vente : "joueur:uuid" ou "faction:id". */
	public String sellerAccount;
	public JsonElement item;
	public String itemId;
	public String itemName;
	public int count;
	/** Prix de départ, en centimes. */
	public long startPrice;
	/** Prix d'achat immédiat (null = pas d'achat immédiat). */
	public Long buyNow;
	/** Meilleure offre (0 = aucune). */
	public long bid;
	public String bidder;
	public String bidderName;
	/** Compte débité par la meilleure offre (argent bloqué jusqu'à la fin). */
	public String bidderAccount;
	public int bids;
	public long created;
	public long expires;
}
