package fr.sumenium.eco;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Dynamic;
import fr.sumenium.eco.data.DataStore;
import fr.sumenium.eco.data.PlayerData;
import fr.sumenium.eco.data.Shop;
import fr.sumenium.eco.data.Tx;
import fr.sumenium.eco.util.Items;
import fr.sumenium.eco.util.Money;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Reprise des données de Savs Common Economy (config/savs-common-economy/) :
 * soldes des joueurs et boutiques (les boutiques ADMIN deviennent des boutiques du FISC).
 */
public final class SavsImport {

	private SavsImport() {}

	public static List<String> run(boolean balances, boolean shops) throws Exception {
		List<String> report = new ArrayList<>();
		Path dir = SumeniumEco.gameDir().resolve("config").resolve("savs-common-economy");
		DataStore store = SumeniumEco.store();
		if (balances) {
			Path file = dir.resolve("balances.json");
			if (!Files.exists(file)) throw new IllegalStateException("Fichier introuvable : " + file);
			JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			int n = 0;
			long total = 0;
			for (var e : root.entrySet()) {
				JsonObject o = e.getValue().getAsJsonObject();
				String name = o.has("name") ? o.get("name").getAsString() : e.getKey();
				long cents = Money.toCents(o.get("balance").getAsDouble());
				PlayerData d = SumeniumEco.accounts().player(e.getKey(), name);
				d.balance = cents;
				total += cents;
				n++;
				Tx tx = new Tx();
				tx.type = Tx.IMPORT;
				tx.to = "joueur:" + d.uuid;
				tx.toName = d.name;
				tx.amount = cents;
				tx.note = "solde Savs";
				SumeniumEco.txlog().add(tx);
			}
			report.add(n + " soldes repris (total " + Money.format(total) + ")");
		}
		if (shops) {
			Path file = dir.resolve("shops.json");
			if (!Files.exists(file)) throw new IllegalStateException("Fichier introuvable : " + file);
			JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			int fisc = 0, player = 0, skipped = 0;
			for (JsonElement el : root.getAsJsonArray("shops")) {
				JsonObject o = el.getAsJsonObject();
				JsonObject loc = o.getAsJsonObject("chestLocation");
				String world = o.get("worldId").getAsString();
				int x = loc.get("x").getAsInt(), y = loc.get("y").getAsInt(), z = loc.get("z").getAsInt();
				if (store.shops.containsKey(Shop.key(world, x, y, z))) {
					skipped++;
					continue;
				}
				ItemStack stack = decodeSavsItem(o);
				if (stack.isEmpty()) {
					report.add("  item illisible pour la boutique en " + x + " " + y + " " + z);
					skipped++;
					continue;
				}
				boolean admin = "ADMIN".equals(o.get("type").getAsString());
				boolean buying = o.get("buying").getAsBoolean();
				int count = Math.max(1, o.get("itemCount").getAsInt());
				long price = Money.toCents(Double.parseDouble(o.get("price").getAsString()));

				Shop s = new Shop();
				s.id = o.has("shopId") ? o.get("shopId").getAsString() : UUID.randomUUID().toString();
				s.world = world;
				s.x = x;
				s.y = y;
				s.z = z;
				s.item = Items.encode(stack);
				s.itemId = Items.id(stack);
				s.itemName = Items.name(stack);
				s.created = System.currentTimeMillis();
				s.creator = o.get("ownerId").getAsString();
				if (admin) {
					s.ownerType = Shop.OwnerType.FISC;
					s.ownerId = "fisc";
					if (buying) {
						s.kind = Shop.Kind.FISC_RACHAT;
						s.quantity = 1;
						s.price = Math.round((double) price / count); // prix unitaire
					} else {
						if (stack.getItem() == net.minecraft.world.item.Items.PAPER) {
							report.add("  ancien certificat papier du FISC ignoré en " + x + " " + y + " " + z
									+ " (remplacé par les frais de création)");
							skipped++;
							continue;
						}
						s.kind = Shop.Kind.FISC_VENTE;
						s.quantity = count;
						s.price = price;
					}
					fisc++;
				} else {
					if (buying) {
						report.add("  boutique d'achat joueur ignorée en " + x + " " + y + " " + z);
						skipped++;
						continue;
					}
					SumeniumEco.accounts().player(o.get("ownerId").getAsString(), o.get("ownerName").getAsString());
					s.kind = Shop.Kind.ITEM;
					s.ownerType = Shop.OwnerType.JOUEUR;
					s.ownerId = o.get("ownerId").getAsString();
					s.quantity = count;
					s.price = price;
					s.feePaid = 0; // certificat déjà payé avec l'ancien système
					player++;
				}
				SumeniumEco.shops().put(s);
			}
			report.add(fisc + " boutiques FISC et " + player + " boutiques joueurs reprises, " + skipped + " ignorées");
		}
		store.meta.savsImported = true;
		store.meta.savsImportedAt = System.currentTimeMillis();
		store.markDirty();
		store.save();
		SumeniumEco.exporter().requestExport();
		return report;
	}

	private static ItemStack decodeSavsItem(JsonObject o) {
		try {
			if (o.has("itemStackSnbt") && !o.get("itemStackSnbt").isJsonNull()) {
				byte[] bytes = Base64.getDecoder().decode(o.get("itemStackSnbt").getAsString());
				CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
				var ops = SumeniumEco.server().registryAccess().createSerializationContext(NbtOps.INSTANCE);
				ItemStack s = ItemStack.CODEC.parse(new Dynamic<>(ops, tag)).result().orElse(ItemStack.EMPTY);
				if (!s.isEmpty()) return s.copyWithCount(1);
			}
		} catch (Exception e) {
			SumeniumEco.LOGGER.warn("Item Savs illisible, repli sur l'identifiant", e);
		}
		Identifier id = Identifier.tryParse(o.get("itemId").getAsString());
		if (id == null) return ItemStack.EMPTY;
		Item item = BuiltInRegistries.ITEM.getValue(id);
		return item == null ? ItemStack.EMPTY : new ItemStack(item);
	}
}
