package fr.sumenium.eco.hdv;

import com.google.gson.JsonElement;
import fr.sumenium.eco.Accounts;
import fr.sumenium.eco.Accounts.Account;
import fr.sumenium.eco.Config;
import fr.sumenium.eco.SumeniumEco;
import fr.sumenium.eco.data.DataStore;
import fr.sumenium.eco.data.HdvData;
import fr.sumenium.eco.data.Listing;
import fr.sumenium.eco.data.PlayerData;
import fr.sumenium.eco.data.Tx;
import fr.sumenium.eco.shop.Shops;
import fr.sumenium.eco.util.Items;
import fr.sumenium.eco.util.Money;
import fr.sumenium.eco.util.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Hôtel des ventes : un PNJ au spawn, des enchères de 7 jours avec achat immédiat facultatif,
 * et une boîte aux lettres où l'on récupère les items gagnés ou invendus.
 */
public final class AuctionHouse {

	public static final String NPC_TAG = "sumeco_hdv";
	private static final int PER_PAGE = 45;
	private static final long CHAT_DELAY_MS = 60_000;

	private final DataStore store;
	/** Offre en attente dans le chat : joueur -> annonce. */
	private final Map<UUID, Pending> pending = new HashMap<>();

	private record Pending(String listingId, long until) {}

	public AuctionHouse(DataStore store) {
		this.store = store;
	}

	private HdvData data() {
		return store.hdv;
	}

	private Config cfg() {
		return SumeniumEco.config();
	}

	// ------------------------------------------------------------------ PNJ et boîtes aux lettres

	public static boolean isNpc(Entity e) {
		return e != null && e.entityTags().contains(NPC_TAG);
	}

	public void spawnNpc(ServerPlayer admin) {
		ServerLevel level = admin.level();
		Villager v = net.minecraft.world.entity.EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
		if (v == null) return;
		v.snapTo(admin.getX(), admin.getY(), admin.getZ(), admin.getYRot() + 180f, 0f);
		v.setYHeadRot(admin.getYRot() + 180f);
		v.setNoAi(true);
		v.setSilent(true);
		v.setPermanentlyInvulnerable(true);
		v.setPersistenceRequired();
		v.setCustomName(Component.literal("Hôtel des ventes").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
		v.setCustomNameVisible(true);
		v.addTag(NPC_TAG);
		level.addFreshEntity(v);
		HdvData.Place place = new HdvData.Place(Shops.world(level), v.blockPosition().getX(), v.blockPosition().getY(), v.blockPosition().getZ());
		place.entity = v.getUUID().toString();
		data().npcs.add(place);
		store.markDirty();
	}

	/** Retire le PNJ le plus proche (6 blocs). */
	public boolean removeNpc(ServerPlayer admin) {
		ServerLevel level = admin.level();
		List<? extends Entity> found = level.getEntities(EntityTypeTest.forClass(Entity.class),
				e -> isNpc(e) && e.distanceToSqr(admin) < 36);
		if (found.isEmpty()) return false;
		Entity e = found.stream().min(Comparator.comparingDouble(x -> x.distanceToSqr(admin))).get();
		data().npcs.removeIf(pl -> e.getUUID().toString().equals(pl.entity));
		e.discard();
		store.markDirty();
		return true;
	}

	public boolean nearNpc(ServerPlayer p) {
		String w = Shops.world(p.level());
		double max = (double) cfg().hdvDistance * cfg().hdvDistance;
		for (HdvData.Place pl : data().npcs) {
			if (pl.world.equals(w) && p.blockPosition().distSqr(new BlockPos(pl.x, pl.y, pl.z)) <= max) return true;
		}
		return false;
	}

	public boolean isMailboxBlock(Level level, BlockPos pos) {
		if (data().mailboxBlocks.isEmpty()) return false;
		String w = Shops.world(level);
		for (HdvData.Place pl : data().mailboxBlocks) if (pl.is(w, pos.getX(), pos.getY(), pos.getZ())) return true;
		return false;
	}

	public void addMailboxBlock(Level level, BlockPos pos) {
		data().mailboxBlocks.add(new HdvData.Place(Shops.world(level), pos.getX(), pos.getY(), pos.getZ()));
		store.markDirty();
	}

	public boolean removeMailboxBlock(Level level, BlockPos pos) {
		String w = Shops.world(level);
		boolean removed = data().mailboxBlocks.removeIf(pl -> pl.is(w, pos.getX(), pos.getY(), pos.getZ()));
		if (removed) store.markDirty();
		return removed;
	}

	// ------------------------------------------------------------------ boîte aux lettres

	public void addToMailbox(String uuid, ItemStack stack) {
		if (stack.isEmpty()) return;
		List<JsonElement> box = data().mailboxes.computeIfAbsent(uuid, k -> new ArrayList<>());
		int max = Math.max(1, stack.getMaxStackSize());
		int left = stack.getCount();
		while (left > 0) {
			int n = Math.min(max, left);
			var ops = SumeniumEco.server().registryAccess().createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
			box.add(ItemStack.CODEC.encodeStart(ops, stack.copyWithCount(n)).getOrThrow());
			left -= n;
		}
		store.markDirty();
	}

	public int mailboxCount(String uuid) {
		List<JsonElement> box = data().mailboxes.get(uuid);
		return box == null ? 0 : box.size();
	}

	private static ItemStack decodeStack(JsonElement json) {
		try {
			var ops = SumeniumEco.server().registryAccess().createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
			return ItemStack.CODEC.parse(ops, json).result().orElse(ItemStack.EMPTY);
		} catch (RuntimeException e) {
			return ItemStack.EMPTY;
		}
	}

	public void openMailbox(ServerPlayer p) {
		String uuid = p.getUUID().toString();
		List<JsonElement> box = data().mailboxes.getOrDefault(uuid, new ArrayList<>());
		SimpleContainer content = new SimpleContainer(54);
		List<JsonElement> overflow = new ArrayList<>();
		int slot = 0;
		for (JsonElement json : box) {
			if (slot < 54) content.setItem(slot++, decodeStack(json));
			else overflow.add(json);
		}
		data().mailboxes.remove(uuid);
		store.markDirty();
		p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new MailboxMenu(id, inv, content, c -> {
			List<JsonElement> rest = new ArrayList<>();
			var ops = SumeniumEco.server().registryAccess().createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
			for (int i = 0; i < c.getContainerSize(); i++) {
				ItemStack st = c.getItem(i);
				if (!st.isEmpty()) rest.add(ItemStack.CODEC.encodeStart(ops, st).getOrThrow());
			}
			rest.addAll(overflow);
			if (!rest.isEmpty()) data().mailboxes.put(uuid, rest);
			store.markDirty();
		}), Component.literal("Boîte aux lettres" + (overflow.isEmpty() ? "" : " (suite au prochain passage)"))));
	}

	// ------------------------------------------------------------------ mise en vente

	public long minBid(Listing l) {
		if (l.bid <= 0) return l.startPrice;
		long step = Math.max(100, Math.round(l.bid * cfg().hdvSurenchereMin));
		return l.bid + step;
	}

	public List<Listing> activeOf(String uuid) {
		List<Listing> out = new ArrayList<>();
		for (Listing l : data().listings.values()) if (l.seller.equals(uuid)) out.add(l);
		return out;
	}

	/** Met en vente le stack tenu en main. Renvoie un message d'erreur ou null. */
	public String sell(ServerPlayer p, long start, Long buyNow) {
		if (!nearNpc(p)) return "Va voir le PNJ de l'hôtel des ventes au spawn pour mettre un item en vente.";
		ItemStack hand = p.getMainHandItem();
		if (hand.isEmpty()) return "Prends en main l'item à vendre (tout le stack est mis en vente).";
		if (Shops.voucherId(hand) != null) return "Un bon de service ne peut pas être revendu.";
		if (start <= 0) return "Prix de départ invalide.";
		if (buyNow != null && buyNow < start) return "Le prix d'achat immédiat doit être au moins égal au prix de départ.";
		String uuid = p.getUUID().toString();
		if (activeOf(uuid).size() >= cfg().hdvAnnoncesMax) {
			return "Tu as déjà " + cfg().hdvAnnoncesMax + " annonces en cours (/hdv annonces).";
		}
		Listing l = new Listing();
		l.id = UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
		l.seller = uuid;
		l.sellerName = p.getPlainTextName();
		l.sellerAccount = SumeniumEco.accounts().active(p).ref();
		l.item = Items.encode(hand);
		l.itemId = Items.id(hand);
		l.itemName = Items.name(hand);
		l.count = hand.getCount();
		l.startPrice = start;
		l.buyNow = buyNow;
		l.created = System.currentTimeMillis();
		l.expires = l.created + cfg().hdvDureeJours * 86_400_000L;
		ItemStack display = hand.copy();
		p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
		data().listings.put(l.id, l);
		store.markDirty();
		SumeniumEco.exporter().requestExport();
		MutableComponent msg = Msg.info("Hôtel des ventes : ").append(Msg.item(display, l.count))
				.append(Component.literal(" mis en vente par " + l.sellerName + " à partir de ").withStyle(ChatFormatting.YELLOW))
				.append(Msg.money(start));
		if (buyNow != null) msg.append(Component.literal(" (achat immédiat ").withStyle(ChatFormatting.YELLOW))
				.append(Msg.money(buyNow)).append(Component.literal(")").withStyle(ChatFormatting.YELLOW));
		SumeniumEco.server().getPlayerList().broadcastSystemMessage(msg, false);
		return null;
	}

	/** Le vendeur retire son annonce (seulement sans offre). */
	public String cancel(ServerPlayer p, String id) {
		Listing l = data().listings.get(id.toUpperCase(Locale.ROOT));
		if (l == null) return "Annonce introuvable.";
		if (!l.seller.equals(p.getUUID().toString()) && !SumeniumEco.shops().hasBypass(p)) return "Ce n'est pas ton annonce.";
		if (l.bid > 0) return "Il y a déjà une offre : l'annonce ne peut plus être retirée.";
		data().listings.remove(l.id);
		addToMailbox(l.seller, Items.decode(l.item).copyWithCount(l.count));
		store.markDirty();
		SumeniumEco.exporter().requestExport();
		return null;
	}

	// ------------------------------------------------------------------ menu du PNJ

	public void openMenu(ServerPlayer p, int page, boolean mine) {
		List<Listing> all = new ArrayList<>();
		String uuid = p.getUUID().toString();
		for (Listing l : data().listings.values()) if (!mine || l.seller.equals(uuid)) all.add(l);
		all.sort(Comparator.comparingLong((Listing l) -> l.expires));
		int pages = Math.max(1, (all.size() + PER_PAGE - 1) / PER_PAGE);
		int pg = Math.max(0, Math.min(page, pages - 1));
		SimpleContainer content = new SimpleContainer(54);
		String[] ids = new String[54];
		for (int i = 0; i < PER_PAGE && pg * PER_PAGE + i < all.size(); i++) {
			Listing l = all.get(pg * PER_PAGE + i);
			content.setItem(i, display(l));
			ids[i] = l.id;
		}
		if (pg > 0) content.setItem(45, button(net.minecraft.world.item.Items.ARROW, "« Page précédente"));
		content.setItem(49, button(net.minecraft.world.item.Items.PAPER, "Page " + (pg + 1) + "/" + pages + " — " + all.size() + " annonce(s)",
				"Clic sur un item pour enchérir ou l'acheter.",
				"Vendre : /hdv vendre <prix> [achat immédiat]",
				"Boîte aux lettres : " + mailboxCount(uuid) + " item(s) à récupérer"));
		content.setItem(50, button(mine ? net.minecraft.world.item.Items.CHEST : net.minecraft.world.item.Items.BOOK,
				mine ? "Toutes les annonces" : "Mes annonces"));
		if (pg < pages - 1) content.setItem(53, button(net.minecraft.world.item.Items.ARROW, "Page suivante »"));
		final int current = pg;
		p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new ButtonMenu(id, inv, content, (sp, slot) -> {
			if (slot == 45 && current > 0) openMenu(sp, current - 1, mine);
			else if (slot == 53 && current < pages - 1) openMenu(sp, current + 1, mine);
			else if (slot == 50) openMenu(sp, 0, !mine);
			else if (slot < PER_PAGE && ids[slot] != null) {
				sp.closeContainer();
				listingClick(sp, ids[slot]);
			}
		}), Component.literal(mine ? "Mes annonces" : "Hôtel des ventes")));
	}

	private static ItemStack button(net.minecraft.world.item.Item item, String name, String... lore) {
		ItemStack st = new ItemStack(item);
		st.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GOLD)));
		if (lore.length > 0) {
			List<Component> lines = new ArrayList<>();
			for (String l : lore) lines.add(Component.literal(l).withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GRAY)));
			st.set(DataComponents.LORE, new ItemLore(lines));
		}
		return st;
	}

	private ItemStack display(Listing l) {
		ItemStack st = Items.decode(l.item).copyWithCount(Math.max(1, Math.min(l.count, 99)));
		List<Component> lines = new ArrayList<>();
		ItemLore old = st.get(DataComponents.LORE);
		if (old != null) lines.addAll(old.lines());
		lines.add(Component.literal(""));
		lines.add(line("Quantité : " + l.count, ChatFormatting.WHITE));
		lines.add(line("Vendeur : " + l.sellerName, ChatFormatting.GRAY));
		if (l.bid > 0) lines.add(line("Offre actuelle : " + Money.format(l.bid) + " (" + l.bidderName + ")", ChatFormatting.GOLD));
		else lines.add(line("Prix de départ : " + Money.format(l.startPrice), ChatFormatting.GOLD));
		if (l.buyNow != null) lines.add(line("Achat immédiat : " + Money.format(l.buyNow), ChatFormatting.GREEN));
		lines.add(line("Fin dans " + remaining(l.expires), ChatFormatting.GRAY));
		lines.add(line("N° " + l.id, ChatFormatting.DARK_GRAY));
		st.set(DataComponents.LORE, new ItemLore(lines));
		return st;
	}

	private static Component line(String s, ChatFormatting color) {
		return Component.literal(s).withStyle(st -> st.withItalic(false).withColor(color));
	}

	public static String remaining(long until) {
		long ms = Math.max(0, until - System.currentTimeMillis());
		long min = ms / 60_000;
		if (min >= 1440) return (min / 1440) + " j " + (min % 1440) / 60 + " h";
		if (min >= 60) return (min / 60) + " h " + (min % 60) + " min";
		return Math.max(1, min) + " min";
	}

	// ------------------------------------------------------------------ offres

	private void listingClick(ServerPlayer p, String id) {
		Listing l = data().listings.get(id);
		if (l == null) {
			Msg.send(p, Msg.err("Cette annonce n'existe plus."));
			return;
		}
		ItemStack st = Items.decode(l.item);
		Msg.send(p, Msg.info("").append(Msg.item(st, l.count))
				.append(Component.literal(" de " + l.sellerName + " — ").withStyle(ChatFormatting.YELLOW))
				.append(Component.literal(l.bid > 0 ? "offre actuelle " : "départ ").withStyle(ChatFormatting.YELLOW))
				.append(Msg.money(l.bid > 0 ? l.bid : l.startPrice))
				.append(Component.literal(", fin dans " + remaining(l.expires)).withStyle(ChatFormatting.GRAY)));
		if (l.seller.equals(p.getUUID().toString())) {
			if (l.bid == 0) Msg.send(p, Msg.prefix().append(Msg.command("[Retirer mon annonce]", "/hdv annuler " + l.id)));
			else Msg.send(p, Msg.info("Il y a déjà une offre, l'annonce ira jusqu'au bout."));
			return;
		}
		MutableComponent ask = Msg.info("Tape ton offre dans le chat (minimum ").append(Msg.money(minBid(l)))
				.append(Component.literal(")").withStyle(ChatFormatting.YELLOW));
		if (l.buyNow != null) ask.append(Component.literal(", « achat » pour l'acheter tout de suite à ").withStyle(ChatFormatting.YELLOW))
				.append(Msg.money(l.buyNow));
		ask.append(Component.literal(", ou « annuler ».").withStyle(ChatFormatting.YELLOW));
		Msg.send(p, ask);
		pending.put(p.getUUID(), new Pending(l.id, System.currentTimeMillis() + CHAT_DELAY_MS));
	}

	/** Message du chat : offre ou achat immédiat. Renvoie true si consommé. */
	public boolean onChat(ServerPlayer p, String raw) {
		Pending pend = pending.get(p.getUUID());
		if (pend == null) return false;
		if (System.currentTimeMillis() > pend.until) {
			pending.remove(p.getUUID());
			return false;
		}
		String text = raw.trim().toLowerCase(Locale.ROOT).replace(',', '.');
		if (text.equals("annuler") || text.equals("stop") || text.equals("non")) {
			pending.remove(p.getUUID());
			Msg.send(p, Msg.info("Offre annulée."));
			return true;
		}
		Listing l = data().listings.get(pend.listingId);
		if (text.equals("achat") || text.equals("acheter")) {
			pending.remove(p.getUUID());
			if (l == null) Msg.send(p, Msg.err("Cette annonce est terminée."));
			else buyNow(p, l);
			return true;
		}
		double value;
		try {
			value = Double.parseDouble(text.replace("su", "").trim());
		} catch (NumberFormatException e) {
			pending.remove(p.getUUID());
			return false;
		}
		pending.remove(p.getUUID());
		if (l == null) Msg.send(p, Msg.err("Cette annonce est terminée."));
		else bid(p, l, Money.toCents(value));
		return true;
	}

	public void bid(ServerPlayer p, Listing l, long amount) {
		String uuid = p.getUUID().toString();
		if (l.seller.equals(uuid)) {
			Msg.send(p, Msg.err("Tu ne peux pas enchérir sur ta propre annonce."));
			return;
		}
		if (l.buyNow != null && amount >= l.buyNow) {
			buyNow(p, l);
			return;
		}
		long min = minBid(l);
		if (amount < min) {
			Msg.send(p, Msg.err("L'offre minimum est de " + Money.format(min) + "."));
			return;
		}
		if (uuid.equals(l.bidder)) {
			Msg.send(p, Msg.err("Tu as déjà la meilleure offre."));
			return;
		}
		Account acc = SumeniumEco.accounts().active(p);
		if (!acc.withdraw(amount)) {
			Msg.send(p, Msg.err("Fonds insuffisants (" + Money.format(acc.balance()) + ")."));
			return;
		}
		refundBidder(l, "surenchéri par " + p.getPlainTextName());
		l.bid = amount;
		l.bidder = uuid;
		l.bidderName = p.getPlainTextName();
		l.bidderAccount = acc.ref();
		l.bids++;
		long extend = cfg().hdvProlongationMinutes * 60_000L;
		if (extend > 0 && l.expires - System.currentTimeMillis() < extend) l.expires = System.currentTimeMillis() + extend;
		store.markDirty();
		Tx tx = tx(l, Tx.HDV_OFFRE);
		tx.actor = uuid;
		tx.actorName = p.getPlainTextName();
		tx.from = acc.ref();
		tx.fromName = acc.label();
		tx.to = "hdv";
		tx.toName = "Hôtel des ventes";
		tx.amount = amount;
		tx.note = "offre bloquée jusqu'à la fin de l'enchère";
		SumeniumEco.txlog().add(tx);
		Msg.send(p, Msg.ok("Offre de ").append(Msg.money(amount)).append(Component.literal(" enregistrée pour ").withStyle(ChatFormatting.GREEN))
				.append(Msg.item(Items.decode(l.item), l.count))
				.append(Component.literal(". L'argent est bloqué jusqu'à la fin de l'enchère.").withStyle(ChatFormatting.GREEN)));
		notifyPlayer(l.seller, "Nouvelle offre de " + Money.format(amount) + " sur ton annonce " + l.count + "× " + l.itemName + ".");
		SumeniumEco.exporter().requestExport();
	}

	private void buyNow(ServerPlayer p, Listing l) {
		if (l.buyNow == null) {
			Msg.send(p, Msg.err("Cette annonce n'a pas d'achat immédiat."));
			return;
		}
		if (l.seller.equals(p.getUUID().toString())) {
			Msg.send(p, Msg.err("C'est ton annonce."));
			return;
		}
		Account acc = SumeniumEco.accounts().active(p);
		if (!acc.withdraw(l.buyNow)) {
			Msg.send(p, Msg.err("Fonds insuffisants (" + Money.format(acc.balance()) + ")."));
			return;
		}
		refundBidder(l, "acheté immédiatement par " + p.getPlainTextName());
		l.bid = l.buyNow;
		l.bidder = p.getUUID().toString();
		l.bidderName = p.getPlainTextName();
		l.bidderAccount = acc.ref();
		Tx tx = tx(l, Tx.HDV_OFFRE);
		tx.actor = l.bidder;
		tx.actorName = l.bidderName;
		tx.from = acc.ref();
		tx.fromName = acc.label();
		tx.to = "hdv";
		tx.toName = "Hôtel des ventes";
		tx.amount = l.buyNow;
		tx.note = "achat immédiat";
		SumeniumEco.txlog().add(tx);
		complete(l, p);
	}

	private void refundBidder(Listing l, String reason) {
		if (l.bid <= 0 || l.bidderAccount == null) return;
		Account prev = SumeniumEco.accounts().byRef(l.bidderAccount);
		if (prev != null) prev.add(l.bid);
		Tx tx = tx(l, Tx.HDV_REMBOURSEMENT);
		tx.from = "hdv";
		tx.fromName = "Hôtel des ventes";
		tx.to = l.bidderAccount;
		tx.toName = prev == null ? l.bidderName : prev.label();
		tx.amount = l.bid;
		tx.note = reason;
		SumeniumEco.txlog().add(tx);
		notifyPlayer(l.bidder, "Ton offre de " + Money.format(l.bid) + " sur " + l.count + "× " + l.itemName + " a été dépassée ("
				+ reason + ") : l'argent t'est rendu.");
	}

	/** Vente conclue : l'argent bloqué va au vendeur (moins la taxe), l'item à l'acheteur. */
	private void complete(Listing l, ServerPlayer buyerOnline) {
		data().listings.remove(l.id);
		long tax = Math.round(l.bid * cfg().hdvTaxe);
		long net = l.bid - tax;
		Account seller = SumeniumEco.accounts().byRef(l.sellerAccount);
		if (seller == null) {
			PlayerData d = store.players.get(l.seller);
			if (d != null) seller = Account.of(d);
		}
		if (seller != null) seller.add(net);
		ItemStack item = Items.decode(l.item).copyWithCount(l.count);
		if (buyerOnline != null) {
			int before = item.getCount();
			ItemStack rest = item.copy();
			if (!buyerOnline.getInventory().add(rest) || !rest.isEmpty()) addToMailbox(l.bidder, rest);
			buyerOnline.containerMenu.broadcastChanges();
			Msg.send(buyerOnline, Msg.ok("Achat conclu : ").append(Msg.item(item, before))
					.append(Component.literal(" pour ").withStyle(ChatFormatting.GREEN)).append(Msg.money(l.bid))
					.append(Component.literal(rest.isEmpty() ? "." : " (le reste est dans ta boîte aux lettres).").withStyle(ChatFormatting.GREEN)));
		} else {
			addToMailbox(l.bidder, item);
			notifyPlayer(l.bidder, "Tu as remporté " + l.count + "× " + l.itemName + " pour " + Money.format(l.bid)
					+ " : récupère-le à la boîte aux lettres du spawn.");
		}
		notifyPlayer(l.seller, "Vendu : " + l.count + "× " + l.itemName + " à " + l.bidderName + " pour " + Money.format(l.bid)
				+ " (+" + Money.format(net) + " après la taxe).");
		Tx tx = tx(l, Tx.HDV_VENTE);
		tx.actor = l.bidder;
		tx.actorName = l.bidderName;
		tx.from = "hdv";
		tx.fromName = l.bidderName;
		tx.to = seller == null ? null : seller.ref();
		tx.toName = seller == null ? l.sellerName : seller.label();
		tx.amount = l.bid;
		tx.tax = tax;
		SumeniumEco.txlog().add(tx);
		store.markDirty();
		SumeniumEco.exporter().requestExport();
	}

	/** Toutes les 20 s : clôture des enchères terminées. */
	public void tick() {
		long now = System.currentTimeMillis();
		for (Listing l : new ArrayList<>(data().listings.values())) {
			if (l.expires > now) continue;
			if (l.bid > 0) {
				MinecraftServer server = SumeniumEco.server();
				ServerPlayer buyer = server.getPlayerList().getPlayer(UUID.fromString(l.bidder));
				complete(l, buyer);
			} else {
				data().listings.remove(l.id);
				addToMailbox(l.seller, Items.decode(l.item).copyWithCount(l.count));
				notifyPlayer(l.seller, "Ton annonce " + l.count + "× " + l.itemName
						+ " est terminée sans offre : l'item t'attend à la boîte aux lettres du spawn.");
				store.markDirty();
				SumeniumEco.exporter().requestExport();
			}
		}
	}

	private Tx tx(Listing l, String type) {
		Tx tx = new Tx();
		tx.type = type;
		tx.shop = "hdv:" + l.id;
		tx.item = l.itemId;
		tx.itemName = l.itemName;
		tx.qty = l.count;
		return tx;
	}

	private void notifyPlayer(String uuid, String message) {
		if (uuid == null) return;
		ServerPlayer o = SumeniumEco.server().getPlayerList().getPlayer(UUID.fromString(uuid));
		if (o != null) Msg.send(o, Msg.info(message));
		else {
			PlayerData d = store.players.get(uuid);
			if (d != null) {
				d.pending.add("[HDV] " + message);
				store.markDirty();
			}
		}
	}

	/** À la connexion : rappel des items à récupérer. */
	public void loginRecap(ServerPlayer p) {
		int n = mailboxCount(p.getUUID().toString());
		if (n > 0) Msg.send(p, Msg.info("Tu as " + n + " item(s) à récupérer à la boîte aux lettres de l'hôtel des ventes."));
	}

	/** Toutes les annonces (pour l'export). */
	public java.util.Collection<Listing> listings() {
		return data().listings.values();
	}

	public Listing get(String id) {
		return data().listings.get(id.toUpperCase(Locale.ROOT));
	}

	/** Pour les tests : ouvre l'enchère sur une annonce sans passer par le menu. */
	public void startChat(ServerPlayer p, String id) {
		listingClick(p, id);
	}

	public Accounts accounts() {
		return SumeniumEco.accounts();
	}
}
