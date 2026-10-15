package fr.sumenium.eco.data;

import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Données de l'hôtel des ventes et de l'annuaire (sumenium-data/data/hdv.json). */
public final class HdvData {

	public static final class Place {
		public String world;
		public int x, y, z;
		/** UUID de l'entité pour un PNJ. */
		public String entity;

		public Place() {}

		public Place(String world, int x, int y, int z) {
			this.world = world;
			this.x = x;
			this.y = y;
			this.z = z;
		}

		public boolean is(String w, int x, int y, int z) {
			return world.equals(w) && this.x == x && this.y == y && this.z == z;
		}
	}

	public Map<String, Listing> listings = new LinkedHashMap<>();
	/** uuid -> items à récupérer à la boîte aux lettres (format ItemStack.CODEC). */
	public Map<String, List<JsonElement>> mailboxes = new LinkedHashMap<>();
	public List<Place> npcs = new ArrayList<>();
	public List<Place> mailboxBlocks = new ArrayList<>();
	public List<Place> lecterns = new ArrayList<>();
}
