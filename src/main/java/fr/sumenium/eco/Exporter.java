package fr.sumenium.eco;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fr.sumenium.eco.data.DataStore;
import fr.sumenium.eco.data.Faction;
import fr.sumenium.eco.data.PlayerData;
import fr.sumenium.eco.data.Shop;
import fr.sumenium.eco.data.Tx;
import fr.sumenium.eco.data.TxLog;
import fr.sumenium.eco.util.Money;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fichiers lisibles pour le site (récupérés en SFTP) dans sumenium-data/export/ :
 * boutiques.json (avec coordonnées), factions.json, comptes.json, prix-fisc.json, stats.json,
 * plus masse-monetaire.jsonl (un point par heure) pour tracer la courbe de l'argent en circulation.
 */
public final class Exporter {

	private final Path dir;
	private long lastExport;
	private long lastSupplyPoint;
	private boolean requested;

	public Exporter(Path dir) {
		this.dir = dir;
	}

	public void requestExport() {
		requested = true;
	}

	public void tick() {
		long now = System.currentTimeMillis();
		long every = Math.max(1, SumeniumEco.config().exportMinutes) * 60_000L;
		if (now - lastExport >= every || (requested && now - lastExport >= 10_000)) {
			exportAll();
		}
	}

	public void exportAll() {
		lastExport = System.currentTimeMillis();
		requested = false;
		DataStore store = SumeniumEco.store();
		try {
			Files.createDirectories(dir);
			write("boutiques.json", shops(store));
			write("factions.json", factions(store));
			write("comptes.json", accounts(store));
			write("prix-fisc.json", fiscPrices(store));
			write("stats.json", stats(store));
			write("hdv.json", hdv());
			write("bluemap-markers.json", bluemap(store));
			if (lastExport - lastSupplyPoint >= 3_600_000L) {
				lastSupplyPoint = lastExport;
				JsonObject point = new JsonObject();
				point.addProperty("t", Instant.ofEpochMilli(lastExport).toString());
				point.addProperty("masseMonetaire", Money.toUnits(SumeniumEco.accounts().moneySupply()));
				Files.writeString(dir.resolve("masse-monetaire.jsonl"), point + "\n", StandardCharsets.UTF_8,
						StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			}
		} catch (IOException | RuntimeException e) {
			SumeniumEco.LOGGER.error("Export Suménium impossible", e);
		}
	}

	private void write(String name, Object json) throws IOException {
		Path tmp = dir.resolve(name + ".tmp");
		Files.writeString(tmp, DataStore.GSON.toJson(json), StandardCharsets.UTF_8);
		Files.move(tmp, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	private JsonObject envelope() {
		JsonObject o = new JsonObject();
		o.addProperty("genereLe", Instant.now().toString());
		o.addProperty("monnaie", SumeniumEco.config().symbole);
		return o;
	}

	private JsonObject shops(DataStore store) {
		JsonObject root = envelope();
		JsonArray arr = new JsonArray();
		var shops = SumeniumEco.shops();
		for (Shop s : store.shops.values()) {
			JsonObject o = new JsonObject();
			o.addProperty("id", s.id);
			o.addProperty("type", s.kind.name());
			o.addProperty("proprietaireType", s.ownerType.name());
			o.addProperty("proprietaireId", s.ownerId);
			o.addProperty("proprietaire", shops.ownerName(s));
			o.addProperty("monde", s.world);
			o.addProperty("x", s.x);
			o.addProperty("y", s.y);
			o.addProperty("z", s.z);
			o.addProperty("item", s.itemId);
			o.addProperty("nom", s.itemName);
			if (s.serviceName != null) o.addProperty("service", s.serviceName);
			o.addProperty("quantite", s.quantity);
			o.addProperty("prix", Money.toUnits(s.price));
			o.addProperty("stock", s.infiniteStock() ? null : s.lastStock);
			o.addProperty("rupture", s.kind == Shop.Kind.ITEM && s.lastStock >= 0 && s.lastStock < s.quantity);
			o.addProperty("ventes", s.salesCount);
			o.addProperty("itemsEchanges", s.itemsMoved);
			o.addProperty("chiffreAffaires", Money.toUnits(s.revenue));
			o.addProperty("creeLe", Instant.ofEpochMilli(s.created).toString());
			arr.add(o);
		}
		root.add("boutiques", arr);
		return root;
	}

	private JsonObject hdv() {
		JsonObject root = envelope();
		JsonArray arr = new JsonArray();
		for (fr.sumenium.eco.data.Listing l : SumeniumEco.hdv().listings()) {
			JsonObject o = new JsonObject();
			o.addProperty("id", l.id);
			o.addProperty("vendeur", l.sellerName);
			o.addProperty("item", l.itemId);
			o.addProperty("nom", l.itemName);
			o.addProperty("quantite", l.count);
			o.addProperty("prixDepart", Money.toUnits(l.startPrice));
			o.addProperty("achatImmediat", l.buyNow == null ? null : Money.toUnits(l.buyNow));
			o.addProperty("offreActuelle", l.bid > 0 ? Money.toUnits(l.bid) : null);
			o.addProperty("meilleurEncherisseur", l.bidderName);
			o.addProperty("nbOffres", l.bids);
			o.addProperty("fin", Instant.ofEpochMilli(l.expires).toString());
			arr.add(o);
		}
		root.add("annonces", arr);
		return root;
	}

	/**
	 * Marqueurs au format BlueMap (un "marker set" par catégorie), à copier dans
	 * maps/<monde>/live/markers.json ou à fusionner par le site.
	 */
	private JsonObject bluemap(DataStore store) {
		JsonObject root = new JsonObject();
		JsonObject shopsSet = markerSet("Boutiques", 0);
		JsonObject fiscSet = markerSet("FISC", 1);
		var shops = SumeniumEco.shops();
		for (Shop s : store.shops.values()) {
			if (!"minecraft:overworld".equals(s.world)) continue;
			JsonObject m = new JsonObject();
			m.addProperty("type", "poi");
			String what = s.kind == Shop.Kind.SERVICE ? s.serviceName : s.itemName;
			m.addProperty("label", shops.ownerName(s) + " : " + what);
			JsonObject pos = new JsonObject();
			pos.addProperty("x", s.x + 0.5);
			pos.addProperty("y", s.y + 0.5);
			pos.addProperty("z", s.z + 0.5);
			m.add("position", pos);
			String price = (s.quantity > 1 ? s.quantity + " pour " : "") + Money.format(s.price);
			boolean empty = s.kind == Shop.Kind.ITEM && s.lastStock >= 0 && s.lastStock < s.quantity;
			m.addProperty("detail", "<b>" + esc(shops.ownerName(s)) + "</b><br>" + esc(what) + "<br>"
					+ esc(s.kind == Shop.Kind.FISC_RACHAT ? "Rachat " + Money.format(s.price) : price)
					+ (empty ? "<br><i>Rupture de stock</i>" : ""));
			m.addProperty("max-distance", 1000);
			(s.isFisc() ? fiscSet : shopsSet).getAsJsonObject("markers").add("sumeco-" + s.id, m);
		}
		JsonObject hdvSet = markerSet("Hôtel des ventes", 2);
		int i = 0;
		for (fr.sumenium.eco.data.HdvData.Place pl : store.hdv.npcs) {
			if (!"minecraft:overworld".equals(pl.world)) continue;
			JsonObject m = new JsonObject();
			m.addProperty("type", "poi");
			m.addProperty("label", "Hôtel des ventes");
			JsonObject pos = new JsonObject();
			pos.addProperty("x", pl.x + 0.5);
			pos.addProperty("y", pl.y + 1.0);
			pos.addProperty("z", pl.z + 0.5);
			m.add("position", pos);
			m.addProperty("detail", "<b>Hôtel des ventes</b><br>" + SumeniumEco.hdv().listings().size() + " annonce(s)");
			hdvSet.getAsJsonObject("markers").add("sumeco-hdv-" + (i++), m);
		}
		root.add("sumenium-boutiques", shopsSet);
		root.add("sumenium-fisc", fiscSet);
		root.add("sumenium-hdv", hdvSet);
		return root;
	}

	private static JsonObject markerSet(String label, int sorting) {
		JsonObject set = new JsonObject();
		set.addProperty("label", label);
		set.addProperty("toggleable", true);
		set.addProperty("default-hidden", false);
		set.addProperty("sorting", sorting);
		set.add("markers", new JsonObject());
		return set;
	}

	private static String esc(String s) {
		return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private JsonObject factions(DataStore store) {
		JsonObject root = envelope();
		JsonArray arr = new JsonArray();
		for (Faction f : store.factions.values()) {
			JsonObject o = new JsonObject();
			o.addProperty("id", f.id);
			o.addProperty("tag", f.tag);
			o.addProperty("couleur", f.color);
			o.addProperty("banque", Money.toUnits(f.bank));
			JsonArray members = new JsonArray();
			for (var e : f.members.entrySet()) {
				JsonObject m = new JsonObject();
				PlayerData d = store.players.get(e.getKey());
				m.addProperty("uuid", e.getKey());
				m.addProperty("nom", d == null ? null : d.name);
				m.addProperty("role", e.getValue().name());
				members.add(m);
			}
			o.add("membres", members);
			arr.add(o);
		}
		root.add("factions", arr);
		return root;
	}

	private JsonObject accounts(DataStore store) {
		JsonObject root = envelope();
		JsonArray arr = new JsonArray();
		for (PlayerData d : store.players.values()) {
			JsonObject o = new JsonObject();
			o.addProperty("uuid", d.uuid);
			o.addProperty("nom", d.name);
			o.addProperty("solde", Money.toUnits(d.balance));
			o.addProperty("faction", d.faction);
			o.addProperty("vuLe", d.lastSeen == 0 ? null : Instant.ofEpochMilli(d.lastSeen).toString());
			arr.add(o);
		}
		root.add("joueurs", arr);
		return root;
	}

	private JsonObject fiscPrices(DataStore store) {
		JsonObject root = envelope();
		root.addProperty("prixDynamiques", SumeniumEco.config().prixDynamiquesMode);
		JsonArray rachat = new JsonArray();
		JsonArray vente = new JsonArray();
		for (Shop s : store.shops.values()) {
			if (!s.isFisc()) continue;
			JsonObject o = new JsonObject();
			o.addProperty("item", s.itemId);
			o.addProperty("nom", s.itemName);
			o.addProperty("prix", Money.toUnits(s.price));
			if (s.kind == Shop.Kind.FISC_VENTE) o.addProperty("quantite", s.quantity);
			o.addProperty("x", s.x);
			o.addProperty("y", s.y);
			o.addProperty("z", s.z);
			(s.kind == Shop.Kind.FISC_RACHAT ? rachat : vente).add(o);
		}
		root.add("rachat", rachat);
		root.add("vente", vente);
		return root;
	}

	private JsonObject stats(DataStore store) {
		JsonObject root = envelope();
		Accounts acc = SumeniumEco.accounts();
		long players = 0, banks = 0;
		for (PlayerData d : store.players.values()) players += d.balance;
		for (Faction f : store.factions.values()) banks += f.bank;
		root.addProperty("masseMonetaire", Money.toUnits(acc.moneySupply()));
		root.addProperty("soldesJoueurs", Money.toUnits(players));
		root.addProperty("banquesFactions", Money.toUnits(banks));
		root.addProperty("nbBoutiques", store.shops.size());
		root.addProperty("nbFactions", store.factions.size());
		root.addProperty("nbJoueurs", store.players.size());

		LocalDate today = LocalDate.now(TxLog.ZONE);
		Map<String, JsonObject> days = new LinkedHashMap<>();
		Map<String, long[]> topItems = new HashMap<>(); // item -> [quantité, montant]
		Map<String, long[]> fiscItems = new HashMap<>();
		for (Tx tx : SumeniumEco.txlog().recent()) {
			LocalDate day = Instant.ofEpochMilli(tx.t).atZone(TxLog.ZONE).toLocalDate();
			if (day.isBefore(today.minusDays(6))) continue;
			JsonObject d = days.computeIfAbsent(day.toString(), k -> {
				JsonObject o = new JsonObject();
				o.addProperty("jour", k);
				return o;
			});
			String key = switch (tx.type) {
				case Tx.ACHAT, Tx.ACHAT_SERVICE -> "ventesJoueurs";
				case Tx.RACHAT_FISC -> "rachatsFisc";
				case Tx.ACHAT_FISC -> "achatsFisc";
				case Tx.FRAIS_BOUTIQUE -> "fraisBoutiques";
				case Tx.HDV_VENTE -> "ventesHdv";
				default -> null;
			};
			if (key == null) continue;
			d.addProperty(key, (d.has(key) ? d.get(key).getAsDouble() : 0) + Money.toUnits(tx.amount));
			d.addProperty(key + "Nb", (d.has(key + "Nb") ? d.get(key + "Nb").getAsLong() : 0) + 1);
			if (tx.tax > 0 && !Tx.FRAIS_BOUTIQUE.equals(tx.type) && !Tx.ACHAT_FISC.equals(tx.type)) {
				d.addProperty("taxes", (d.has("taxes") ? d.get("taxes").getAsDouble() : 0) + Money.toUnits(tx.tax));
			}
			if (tx.itemName != null && (Tx.ACHAT.equals(tx.type) || Tx.ACHAT_SERVICE.equals(tx.type))) {
				long[] v = topItems.computeIfAbsent(tx.itemName, k -> new long[2]);
				v[0] += tx.qty;
				v[1] += tx.amount;
			}
			if (Tx.RACHAT_FISC.equals(tx.type) && tx.item != null) {
				long[] v = fiscItems.computeIfAbsent(tx.item, k -> new long[2]);
				v[0] += tx.qty;
				v[1] += tx.amount;
			}
		}
		JsonArray daysArr = new JsonArray();
		days.values().forEach(daysArr::add);
		root.add("parJour7j", daysArr);
		root.add("topVentesJoueurs7j", top(topItems));
		root.add("rachatsFisc7j", top(fiscItems));
		return root;
	}

	private JsonArray top(Map<String, long[]> map) {
		JsonArray arr = new JsonArray();
		map.entrySet().stream()
				.sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]))
				.limit(20)
				.forEach(e -> {
					JsonObject o = new JsonObject();
					o.addProperty("item", e.getKey());
					o.addProperty("quantite", e.getValue()[0]);
					o.addProperty("montant", Money.toUnits(e.getValue()[1]));
					arr.add(o);
				});
		return arr;
	}
}
