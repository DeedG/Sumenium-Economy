package fr.sumenium.eco.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import fr.sumenium.eco.SumeniumEco;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Données persistantes du mod, dans sumenium-data/data/.
 * Tout est en mémoire ; sauvegarde régulière et à l'arrêt du serveur.
 */
public final class DataStore {

	public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	public final Map<String, PlayerData> players = new LinkedHashMap<>();
	public final Map<String, Faction> factions = new LinkedHashMap<>();
	/** clé de position -> boutique */
	public final Map<String, Shop> shops = new LinkedHashMap<>();
	public final Map<String, Voucher> vouchers = new LinkedHashMap<>();
	/** clé de position -> dépôt de service */
	public final Map<String, Deposit> deposits = new LinkedHashMap<>();
	public Meta meta = new Meta();

	private final Path dir;
	private boolean dirty;

	public static final class Meta {
		public boolean savsImported;
		public long savsImportedAt;
	}

	public DataStore(Path dir) {
		this.dir = dir;
	}

	public void markDirty() {
		dirty = true;
	}

	public boolean isDirty() {
		return dirty;
	}

	public void load() {
		players.clear();
		factions.clear();
		shops.clear();
		vouchers.clear();
		deposits.clear();
		List<PlayerData> p = read("joueurs.json", new TypeToken<List<PlayerData>>() {}.getType());
		if (p != null) for (PlayerData d : p) players.put(d.uuid, d);
		List<Faction> f = read("factions.json", new TypeToken<List<Faction>>() {}.getType());
		if (f != null) for (Faction d : f) factions.put(d.id, d);
		List<Shop> s = read("boutiques.json", new TypeToken<List<Shop>>() {}.getType());
		if (s != null) for (Shop d : s) shops.put(d.posKey(), d);
		List<Voucher> v = read("bons.json", new TypeToken<List<Voucher>>() {}.getType());
		if (v != null) for (Voucher d : v) vouchers.put(d.id, d);
		List<Deposit> dp = read("depots.json", new TypeToken<List<Deposit>>() {}.getType());
		if (dp != null) for (Deposit d : dp) deposits.put(d.posKey(), d);
		Meta m = read("meta.json", Meta.class);
		if (m != null) meta = m;
		dirty = false;
		SumeniumEco.LOGGER.info("Suménium Economy : {} joueurs, {} factions, {} boutiques chargés",
				players.size(), factions.size(), shops.size());
	}

	public void save() {
		write("joueurs.json", new ArrayList<>(players.values()));
		write("factions.json", new ArrayList<>(factions.values()));
		write("boutiques.json", new ArrayList<>(shops.values()));
		write("bons.json", new ArrayList<>(vouchers.values()));
		write("depots.json", new ArrayList<>(deposits.values()));
		write("meta.json", meta);
		dirty = false;
	}

	private <T> T read(String name, Type type) {
		Path file = dir.resolve(name);
		if (!Files.exists(file)) return null;
		try {
			return GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), type);
		} catch (IOException | RuntimeException e) {
			SumeniumEco.LOGGER.error("Lecture impossible de {}", file, e);
			// on garde une copie du fichier illisible pour ne rien perdre
			try {
				Files.copy(file, dir.resolve(name + ".corrompu-" + System.currentTimeMillis()));
			} catch (IOException ignored) {
			}
			return null;
		}
	}

	private void write(String name, Object value) {
		try {
			Files.createDirectories(dir);
			Path tmp = dir.resolve(name + ".tmp");
			Files.writeString(tmp, GSON.toJson(value), StandardCharsets.UTF_8);
			Files.move(tmp, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			SumeniumEco.LOGGER.error("Écriture impossible de {}", name, e);
		}
	}
}
