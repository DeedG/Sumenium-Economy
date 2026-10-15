package fr.sumenium.eco.shop;

/**
 * Famille d'un item pour les frais de boutique : les variantes de couleur et d'essence
 * de bois sont regroupées (laine blanche et laine rouge = "wool", planches de chêne et
 * de bouleau = "planks", escaliers en chêne et en acacia = "stairs"…).
 */
public final class Family {

	private static final String[] COLORS = {
			"light_blue", "light_gray", "white", "orange", "magenta", "yellow", "lime", "pink", "gray",
			"cyan", "purple", "blue", "brown", "green", "red", "black"
	};

	private static final String[] WOODS = {
			"dark_oak", "pale_oak", "oak", "spruce", "birch", "jungle", "acacia", "mangrove", "cherry",
			"bamboo", "crimson", "warped"
	};

	private Family() {}

	/** "minecraft:red_wool" -> "wool", "minecraft:stripped_oak_log" -> "stripped_log". */
	public static String of(String itemId) {
		if (itemId == null) return "";
		String id = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
		String prefix = "";
		if (id.startsWith("stripped_")) {
			prefix = "stripped_";
			id = id.substring(prefix.length());
		}
		for (String c : COLORS) {
			if (id.startsWith(c + "_")) return prefix + id.substring(c.length() + 1);
		}
		for (String w : WOODS) {
			if (id.startsWith(w + "_")) return prefix + id.substring(w.length() + 1);
		}
		return prefix + id;
	}
}
