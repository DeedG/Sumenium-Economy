package fr.sumenium.eco.data;

/** Une ligne du journal des transactions (sumenium-data/journal/AAAA-MM-JJ.jsonl). */
public final class Tx {

	public static final String ACHAT = "ACHAT";
	public static final String ACHAT_SERVICE = "ACHAT_SERVICE";
	public static final String RACHAT_FISC = "RACHAT_FISC";
	public static final String ACHAT_FISC = "ACHAT_FISC";
	public static final String FRAIS_BOUTIQUE = "FRAIS_BOUTIQUE";
	public static final String REMBOURSEMENT_BOUTIQUE = "REMBOURSEMENT_BOUTIQUE";
	public static final String SUPPRESSION_BOUTIQUE = "SUPPRESSION_BOUTIQUE";
	public static final String DEPOT_FACTION = "DEPOT_FACTION";
	public static final String RETRAIT_FACTION = "RETRAIT_FACTION";
	public static final String ADMIN = "ADMIN";
	public static final String IMPORT = "IMPORT";
	public static final String BON_UTILISE = "BON_UTILISE";
	public static final String HDV_OFFRE = "HDV_OFFRE";
	public static final String HDV_REMBOURSEMENT = "HDV_REMBOURSEMENT";
	public static final String HDV_VENTE = "HDV_VENTE";

	/** Horodatage (ms epoch). */
	public long t;
	public String type;
	/** Joueur à l'origine de l'action. */
	public String actor;
	public String actorName;
	/** Compte débité : "joueur:uuid", "faction:id", "fisc" ou null. */
	public String from;
	/** Compte crédité. */
	public String to;
	/** Nom lisible du compte crédité ou du vendeur. */
	public String toName;
	public String fromName;
	public String shop;
	public String item;
	public String itemName;
	public int qty;
	/** Montant payé par l'acheteur, en centimes. */
	public long amount;
	/** Taxe retirée de l'économie, en centimes. */
	public long tax;
	public String world;
	public Integer x, y, z;
	public String note;
}
