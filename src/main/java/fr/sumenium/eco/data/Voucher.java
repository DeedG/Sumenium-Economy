package fr.sumenium.eco.data;

/** Bon de service émis à l'achat d'un service. */
public final class Voucher {
	public String id;
	public String shopId;
	public String serviceName;
	public String sellerName;
	public String buyerUuid;
	public String buyerName;
	public long price;
	public long issued;
	/** Rempli quand l'exécutant a validé le bon. */
	public String usedBy;
	public long usedAt;
}
