package fr.sumenium.eco.data;

/** Coffre de dépôt d'un service : un bon valide déposé dedans déclenche un signal de redstone. */
public final class Deposit {
	public String shopId;
	public String serviceName;
	public String world;
	public int x, y, z;
	public long created;
	public long used;
	/** Panneau explicatif posé à la création (null si aucun). */
	public Integer signX, signY, signZ;

	public String posKey() {
		return Shop.key(world, x, y, z);
	}
}
