package fr.sumenium.eco.heads;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.sumenium.eco.SumeniumEco;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Catalogue de têtes au format HeadDB (manifest → index → parties compressées en zstd).
 * Téléchargé en arrière-plan, gardé en cache dans sumenium-data/tetes/ pour fonctionner hors ligne.
 */
public final class HeadCatalog {

	public record Head(int id, String name, String hash, String category, String search) {}

	public record Category(String id, String name, List<Head> heads) {}

	public record Snapshot(List<Category> categories, Map<Integer, Head> byId, int count, long revision, long loadedAt) {
		static final Snapshot EMPTY = new Snapshot(List.of(), Map.of(), 0, 0, 0);
	}

	private final Path dir;
	private volatile Snapshot snapshot = Snapshot.EMPTY;
	private volatile String lastError;
	private ScheduledExecutorService executor;
	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(20))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	public HeadCatalog(Path dir) {
		this.dir = dir;
	}

	public Snapshot snapshot() {
		return snapshot;
	}

	private volatile int excludedCount;

	/** Nombre de têtes du catalogue écartées (déjà obtenables avec le datapack AllMobHeads, etc.). */
	public int excludedCount() {
		return excludedCount;
	}

	/** Empreinte comparable : minuscules, sans zéros en tête. */
	private static String norm(String hash) {
		String h = hash.trim().toLowerCase(Locale.ROOT);
		int i = 0;
		while (i < h.length() - 1 && h.charAt(i) == '0') i++;
		return h.substring(i);
	}

	/**
	 * Têtes jamais vendues : la liste fournie avec le mod (datapack AllMobHeads)
	 * et, si présent, sumenium-data/tetes/exclues.txt (une empreinte ou une URL de texture par ligne).
	 */
	private Set<String> excludedHashes() {
		Set<String> out = new HashSet<>();
		try (InputStream in = HeadCatalog.class.getResourceAsStream("/sumenium-economy/tetes-exclues.txt")) {
			if (in != null) addHashes(new String(in.readAllBytes(), StandardCharsets.UTF_8), out);
		} catch (Exception e) {
			SumeniumEco.LOGGER.warn("Têtes : liste d'exclusion interne illisible ({})", e.getMessage());
		}
		try {
			Path extra = dir.resolve("exclues.txt");
			if (Files.exists(extra)) addHashes(Files.readString(extra, StandardCharsets.UTF_8), out);
		} catch (Exception e) {
			SumeniumEco.LOGGER.warn("Têtes : exclues.txt illisible ({})", e.getMessage());
		}
		return out;
	}

	private static void addHashes(String text, Set<String> out) {
		for (String line : text.split("\\R")) {
			String l = line.trim();
			if (l.isEmpty() || l.startsWith("#")) continue;
			int slash = l.lastIndexOf('/');
			if (slash >= 0) l = l.substring(slash + 1);
			if (l.matches("[0-9a-fA-F]{16,128}")) out.add(norm(l));
		}
	}

	public String lastError() {
		return lastError;
	}

	/** Charge le cache puis met à jour en arrière-plan, et toutes les N heures. */
	public void start() {
		if (executor != null) return;
		executor = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "sumeco-tetes");
			t.setDaemon(true);
			return t;
		});
		executor.execute(() -> {
			try {
				loadCached();
			} catch (Exception e) {
				SumeniumEco.LOGGER.info("Têtes : pas de cache utilisable ({})", e.getMessage());
			}
		});
		int hours = Math.max(1, SumeniumEco.config().tetesMajHeures);
		executor.scheduleWithFixedDelay(this::refreshQuietly, 5, hours * 3600L, TimeUnit.SECONDS);
	}

	public void stop() {
		if (executor != null) executor.shutdownNow();
		executor = null;
	}

	/** Mise à jour demandée par un admin (en arrière-plan). */
	public void refreshAsync(Runnable done) {
		Thread t = new Thread(() -> {
			refreshQuietly();
			if (done != null) done.run();
		}, "sumeco-tetes-maj");
		t.setDaemon(true);
		t.start();
	}

	private void refreshQuietly() {
		try {
			refresh();
			lastError = null;
		} catch (Exception e) {
			lastError = e.getClass().getSimpleName() + " : " + e.getMessage();
			SumeniumEco.LOGGER.warn("Têtes : mise à jour du catalogue impossible ({})", lastError);
		}
	}

	// ------------------------------------------------------------------ téléchargement

	/** Télécharge le catalogue (synchrone). */
	public synchronized void refresh() throws Exception {
		Files.createDirectories(dir);
		String manifestText = new String(get(URI.create(SumeniumEco.config().tetesCatalogue)), StandardCharsets.UTF_8);
		JsonObject manifest = JsonParser.parseString(manifestText).getAsJsonObject();
		Snapshot snap = build(manifest, true);
		Files.writeString(dir.resolve("manifest.json"), manifestText, StandardCharsets.UTF_8);
		snapshot = snap;
		cleanCache(manifest);
		SumeniumEco.LOGGER.info("Têtes : {} têtes dans {} catégories", snap.count(), snap.categories().size());
	}

	private void loadCached() throws Exception {
		Path m = dir.resolve("manifest.json");
		if (!Files.exists(m)) return;
		JsonObject manifest = JsonParser.parseString(Files.readString(m, StandardCharsets.UTF_8)).getAsJsonObject();
		Snapshot snap = build(manifest, false);
		if (snapshot.count() == 0) snapshot = snap;
	}

	private Snapshot build(JsonObject manifest, boolean download) throws Exception {
		List<URI> mirrors = mirrors(manifest);
		JsonObject resources = manifest.getAsJsonObject("resources");
		JsonObject catalogIndex = readJson(mirrors, resources.getAsJsonObject("catalog").getAsJsonObject("index"), download);

		Map<String, String> categoryNames = new LinkedHashMap<>();
		for (JsonElement c : array(catalogIndex, "categories")) {
			JsonObject o = c.getAsJsonObject();
			categoryNames.put(str(o, "id"), nameOr(str(o, "name"), str(o, "id")));
		}
		Map<String, String> tagNames = new HashMap<>();
		for (JsonElement t : array(catalogIndex, "tags")) {
			JsonObject o = t.getAsJsonObject();
			tagNames.put(str(o, "id"), nameOr(str(o, "name"), str(o, "id")));
		}

		Set<Integer> revoked = new HashSet<>();
		if (resources.has("revocations")) {
			try {
				JsonObject revIndex = readJson(mirrors, resources.getAsJsonObject("revocations").getAsJsonObject("index"), download);
				for (JsonElement part : array(revIndex, "artifacts")) {
					JsonObject p = readJson(mirrors, part.getAsJsonObject(), download);
					for (JsonElement r : array(p, "revocations")) {
						JsonObject o = r.getAsJsonObject();
						if (o.has("id")) revoked.add(o.get("id").getAsInt());
					}
				}
			} catch (Exception e) {
				SumeniumEco.LOGGER.warn("Têtes : liste des têtes retirées illisible ({})", e.getMessage());
			}
		}

		Set<String> excluded = excludedHashes();
		int skipped = 0;
		Map<String, List<Head>> byCat = new LinkedHashMap<>();
		Map<Integer, Head> byId = new HashMap<>();
		for (JsonElement part : array(catalogIndex, "artifacts")) {
			JsonObject p = readJson(mirrors, part.getAsJsonObject(), download);
			for (JsonElement h : array(p, "heads")) {
				JsonObject o = h.getAsJsonObject();
				if (!o.has("id") || !o.has("texture")) continue;
				int id = o.get("id").getAsInt();
				if (revoked.contains(id)) continue;
				String hash = str(o, "texture").trim().toLowerCase(Locale.ROOT);
				if (!hash.matches("[0-9a-f]{16,128}")) continue;
				if (excluded.contains(norm(hash))) {
					skipped++;
					continue;
				}
				String name = nameOr(str(o, "name"), "Tête n° " + id);
				String cat = nameOr(str(o, "category"), "autres");
				StringBuilder search = new StringBuilder(fold(name));
				if (o.has("tags") && o.get("tags").isJsonArray()) {
					for (JsonElement t : o.getAsJsonArray("tags")) {
						String tag = t.getAsString();
						search.append(' ').append(fold(tagNames.getOrDefault(tag, tag)));
					}
				}
				search.append(' ').append(fold(categoryNames.getOrDefault(cat, cat)));
				Head head = new Head(id, name, hash, cat, search.toString());
				byId.put(id, head);
				byCat.computeIfAbsent(cat, k -> new ArrayList<>()).add(head);
			}
		}
		Comparator<Head> order = Comparator.comparing((Head h) -> fold(h.name())).thenComparingInt(Head::id);
		List<Category> cats = new ArrayList<>();
		for (var e : byCat.entrySet()) {
			List<Head> list = new ArrayList<>(e.getValue());
			list.sort(order);
			cats.add(new Category(e.getKey(), categoryNames.getOrDefault(e.getKey(), capitalize(e.getKey())), List.copyOf(list)));
		}
		cats.sort(Comparator.comparing(c -> fold(c.name())));
		long rev = manifest.has("revision") ? manifest.get("revision").getAsLong() : 0;
		excludedCount = skipped;
		return new Snapshot(List.copyOf(cats), Map.copyOf(byId), byId.size(), rev, System.currentTimeMillis());
	}

	/** Lit un fichier du catalogue : depuis le cache s'il est intact, sinon téléchargé (si autorisé). */
	private JsonObject readJson(List<URI> mirrors, JsonObject artifact, boolean download) throws Exception {
		String path = str(artifact, "path");
		if (path == null || path.contains("..") || path.startsWith("/")) throw new IllegalArgumentException("chemin invalide : " + path);
		JsonObject integrity = artifact.has("integrity") ? artifact.getAsJsonObject("integrity") : null;
		String digest = integrity != null ? str(integrity, "digest") : null;
		Path cached = digest != null ? dir.resolve("cache").resolve(digest.toLowerCase(Locale.ROOT) + ".bin") : null;
		byte[] raw = null;
		if (cached != null && Files.exists(cached)) {
			byte[] b = Files.readAllBytes(cached);
			if (digest.equalsIgnoreCase(sha256(b))) raw = b;
		}
		if (raw == null) {
			if (!download) throw new IllegalStateException("fichier absent du cache : " + path);
			Exception last = null;
			for (URI m : mirrors) {
				try {
					byte[] b = get(m.resolve(path));
					if (digest != null && !digest.equalsIgnoreCase(sha256(b))) throw new IllegalStateException("empreinte incorrecte pour " + path);
					raw = b;
					break;
				} catch (Exception e) {
					last = e;
				}
			}
			if (raw == null) throw last != null ? last : new IllegalStateException("aucun miroir");
			if (cached != null) {
				Files.createDirectories(cached.getParent());
				Files.write(cached, raw);
			}
		}
		String compression = str(artifact, "compression");
		byte[] json = compression == null || compression.equalsIgnoreCase("none") || compression.equalsIgnoreCase("identity")
				? raw : Zstd.decompress(raw);
		return JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
	}

	/** Supprime du cache les fichiers qui ne servent plus. */
	private void cleanCache(JsonObject manifest) {
		try {
			Path cache = dir.resolve("cache");
			if (!Files.isDirectory(cache)) return;
			Set<String> keep = new HashSet<>();
			collectDigests(manifest, keep);
			for (Path f : Files.list(cache).toList()) {
				String n = f.getFileName().toString();
				if (n.endsWith(".bin") && !keep.contains(n.substring(0, n.length() - 4))) Files.deleteIfExists(f);
			}
		} catch (Exception ignored) {
		}
	}

	private void collectDigests(JsonObject manifest, Set<String> keep) throws Exception {
		List<URI> mirrors = mirrors(manifest);
		JsonObject resources = manifest.getAsJsonObject("resources");
		for (String key : resources.keySet()) {
			JsonObject index = resources.getAsJsonObject(key).getAsJsonObject("index");
			keep.add(digestOf(index));
			JsonObject idx = readJson(mirrors, index, false);
			for (JsonElement part : array(idx, "artifacts")) keep.add(digestOf(part.getAsJsonObject()));
		}
	}

	private static String digestOf(JsonObject artifact) {
		JsonObject i = artifact.getAsJsonObject("integrity");
		String d = i == null ? null : str(i, "digest");
		return d == null ? "" : d.toLowerCase(Locale.ROOT);
	}

	private static List<URI> mirrors(JsonObject manifest) {
		List<JsonObject> list = new ArrayList<>();
		for (JsonElement m : array(manifest, "mirrors")) list.add(m.getAsJsonObject());
		list.sort(Comparator.comparingInt(o -> o.has("priority") ? o.get("priority").getAsInt() : 0));
		List<URI> out = new ArrayList<>();
		for (JsonObject o : list) {
			String url = str(o, "url");
			if (url == null) continue;
			if (!url.endsWith("/")) url += "/";
			if (url.startsWith("https://") || url.startsWith("http://")) out.add(URI.create(url));
		}
		if (out.isEmpty()) throw new IllegalArgumentException("aucun miroir dans le manifest");
		return out;
	}

	private byte[] get(URI uri) throws Exception {
		HttpRequest req = HttpRequest.newBuilder(uri).GET().timeout(Duration.ofSeconds(60))
				.header("Accept", "*/*").header("User-Agent", "Sumenium-Economy").build();
		HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
		try (InputStream in = resp.body()) {
			if (resp.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + resp.statusCode() + " pour " + uri);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[65536];
			long total = 0;
			int n;
			while ((n = in.read(buf)) > 0) {
				total += n;
				if (total > 512L * 1024 * 1024) throw new IllegalStateException("fichier trop gros : " + uri);
				out.write(buf, 0, n);
			}
			return out.toByteArray();
		}
	}

	// ------------------------------------------------------------------ outils

	private static JsonArray array(JsonObject o, String key) {
		return o != null && o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
	}

	private static String str(JsonObject o, String key) {
		return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
	}

	private static String nameOr(String s, String fallback) {
		return s == null || s.isBlank() ? fallback : s.trim();
	}

	private static String capitalize(String s) {
		if (s == null || s.isEmpty()) return "Autres";
		String t = s.replace('_', ' ').replace('-', ' ');
		return Character.toUpperCase(t.charAt(0)) + t.substring(1);
	}

	/** Minuscules sans accents, pour trier et chercher. */
	public static String fold(String s) {
		if (s == null) return "";
		String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
		return n.toLowerCase(Locale.ROOT);
	}

	static String sha256(byte[] b) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
	}

	/**
	 * Recherche : chaque mot tapé doit commencer un mot du nom, d'un tag ou de la catégorie.
	 * Les têtes dont le nom correspond passent en premier.
	 */
	public List<Head> search(String query) {
		String[] words = fold(query).trim().split("[^\\p{L}\\p{N}]+");
		List<Head> byName = new ArrayList<>();
		List<Head> byTag = new ArrayList<>();
		for (Category c : snapshot.categories()) {
			for (Head h : c.heads()) {
				String[] tokens = h.search().split("[^\\p{L}\\p{N}]+");
				String[] nameTokens = fold(h.name()).split("[^\\p{L}\\p{N}]+");
				boolean all = true, inName = true;
				for (String w : words) {
					if (w.isEmpty()) continue;
					if (!startsAny(nameTokens, w)) inName = false;
					if (!startsAny(tokens, w)) {
						all = false;
						break;
					}
				}
				if (all) (inName ? byName : byTag).add(h);
			}
		}
		Comparator<Head> order = Comparator.comparing((Head h) -> fold(h.name())).thenComparingInt(Head::id);
		byName.sort(order);
		byTag.sort(order);
		byName.addAll(byTag);
		return byName;
	}

	private static boolean startsAny(String[] tokens, String w) {
		for (String t : tokens) if (t.startsWith(w)) return true;
		return false;
	}

	/** Pour le test de démarrage : construit un catalogue à partir de JSON en mémoire. */
	void setForTest(Snapshot s) {
		snapshot = s;
	}

	static ByteArrayInputStream stream(byte[] b) {
		return new ByteArrayInputStream(b);
	}
}
