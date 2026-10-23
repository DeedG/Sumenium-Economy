package fr.sumenium.eco.heads;

import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import fr.sumenium.eco.Accounts.Account;
import fr.sumenium.eco.SumeniumEco;
import fr.sumenium.eco.data.DataStore;
import fr.sumenium.eco.data.HdvData;
import fr.sumenium.eco.data.Tx;
import fr.sumenium.eco.hdv.ButtonMenu;
import fr.sumenium.eco.shop.Shops;
import fr.sumenium.eco.util.Items;
import fr.sumenium.eco.util.Money;
import fr.sumenium.eco.util.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.entity.EntityTypeTest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Marchand de têtes : un PNJ ouvre un menu trié par catégorie (puis par nom),
 * avec une recherche dans le chat. Un clic sur une tête l'achète au prix unique, payé au FISC.
 */
public final class HeadShop {

	public static final String NPC_TAG = "sumeco_tetes";
	private static final int PER_PAGE = 45;

	private final DataStore store;
	private final HeadCatalog catalog;
	/** Joueurs qui doivent taper leur recherche dans le chat (uuid → fin du délai). */
	private final Map<UUID, Long> searching = new HashMap<>();

	public HeadShop(DataStore store, HeadCatalog catalog) {
		this.store = store;
		this.catalog = catalog;
	}

	public HeadCatalog catalog() {
		return catalog;
	}

	public long price() {
		return Money.toCents(SumeniumEco.config().tetesPrix);
	}

	// ------------------------------------------------------------------ PNJ

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
		v.setCustomName(Component.literal("Marchand de têtes").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
		v.setCustomNameVisible(true);
		v.addTag(NPC_TAG);
		// une tête sur le PNJ pour qu'on le reconnaisse
		List<HeadCatalog.Head> sample = allHeads();
		if (!sample.isEmpty()) {
			v.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, item(sample.get(level.getRandom().nextInt(sample.size()))));
			v.setDropChance(net.minecraft.world.entity.EquipmentSlot.HEAD, 0f);
		}
		level.addFreshEntity(v);
		HdvData.Place place = new HdvData.Place(Shops.world(level), v.blockPosition().getX(), v.blockPosition().getY(), v.blockPosition().getZ());
		place.entity = v.getUUID().toString();
		store.hdv.headNpcs.add(place);
		store.markDirty();
	}

	/** Retire le marchand de têtes le plus proche (6 blocs). */
	public boolean removeNpc(ServerPlayer admin) {
		ServerLevel level = admin.level();
		List<? extends Entity> found = level.getEntities(EntityTypeTest.forClass(Entity.class),
				e -> isNpc(e) && e.distanceToSqr(admin) < 36);
		if (found.isEmpty()) return false;
		Entity e = found.stream().min(Comparator.comparingDouble(x -> x.distanceToSqr(admin))).get();
		store.hdv.headNpcs.removeIf(pl -> e.getUUID().toString().equals(pl.entity));
		e.discard();
		store.markDirty();
		return true;
	}

	// ------------------------------------------------------------------ item

	/** La tête prête à être donnée (texture HeadDB, nom affiché). */
	public static ItemStack item(HeadCatalog.Head h) {
		ItemStack st = new ItemStack(net.minecraft.world.item.Items.PLAYER_HEAD);
		String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/" + h.hash() + "\"}}}";
		String b64 = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
		PropertyMap props = new PropertyMap(ImmutableMultimap.of("textures", new Property("textures", b64)));
		UUID id = UUID.nameUUIDFromBytes(("sumeco-tete:" + h.hash()).getBytes(StandardCharsets.UTF_8));
		st.set(DataComponents.PROFILE, ResolvableProfile.createResolved(new GameProfile(id, "Sumenium", props)));
		st.set(DataComponents.CUSTOM_NAME, Component.literal(h.name()).withStyle(s -> s.withItalic(false).withColor(ChatFormatting.YELLOW)));
		return st;
	}

	private ItemStack display(HeadCatalog.Head h) {
		ItemStack st = item(h);
		List<Component> lines = new ArrayList<>();
		lines.add(line("Prix : " + Money.format(price()), ChatFormatting.GOLD));
		lines.add(line("Clic pour acheter", ChatFormatting.GREEN));
		lines.add(line("N° " + h.id(), ChatFormatting.DARK_GRAY));
		st.set(DataComponents.LORE, new ItemLore(lines));
		return st;
	}

	private static Component line(String s, ChatFormatting color) {
		return Component.literal(s).withStyle(st -> st.withItalic(false).withColor(color));
	}

	private static ItemStack button(net.minecraft.world.item.Item item, String name, String... lore) {
		ItemStack st = new ItemStack(item);
		st.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GOLD)));
		if (lore.length > 0) {
			List<Component> lines = new ArrayList<>();
			for (String l : lore) lines.add(line(l, ChatFormatting.GRAY));
			st.set(DataComponents.LORE, new ItemLore(lines));
		}
		return st;
	}

	private List<HeadCatalog.Head> allHeads() {
		List<HeadCatalog.Head> all = new ArrayList<>();
		for (HeadCatalog.Category c : catalog.snapshot().categories()) all.addAll(c.heads());
		return all;
	}

	// ------------------------------------------------------------------ menus

	/** Menu principal : les catégories. */
	public void openCategories(ServerPlayer p, int page) {
		List<HeadCatalog.Category> cats = catalog.snapshot().categories();
		if (cats.isEmpty()) {
			Msg.send(p, Msg.err("Le catalogue de têtes n'est pas encore chargé, réessaie dans un instant."));
			return;
		}
		int pages = Math.max(1, (cats.size() + PER_PAGE - 1) / PER_PAGE);
		int pg = Math.max(0, Math.min(page, pages - 1));
		SimpleContainer content = new SimpleContainer(54);
		HeadCatalog.Category[] at = new HeadCatalog.Category[54];
		for (int i = 0; i < PER_PAGE && pg * PER_PAGE + i < cats.size(); i++) {
			HeadCatalog.Category c = cats.get(pg * PER_PAGE + i);
			ItemStack icon = c.heads().isEmpty() ? new ItemStack(net.minecraft.world.item.Items.PLAYER_HEAD) : item(c.heads().get(0));
			icon.set(DataComponents.CUSTOM_NAME, Component.literal(c.name()).withStyle(s -> s.withItalic(false).withColor(ChatFormatting.AQUA).withBold(true)));
			icon.set(DataComponents.LORE, new ItemLore(List.of(line(c.heads().size() + " têtes", ChatFormatting.GRAY),
					line("Clic pour voir", ChatFormatting.GREEN))));
			content.setItem(i, icon);
			at[i] = c;
		}
		if (pg > 0) content.setItem(45, button(net.minecraft.world.item.Items.ARROW, "« Page précédente"));
		content.setItem(48, info(p));
		content.setItem(49, button(net.minecraft.world.item.Items.COMPASS, "Rechercher une tête", "Clic, puis tape un mot dans le chat",
				"(nom, thème… en anglais le plus souvent)"));
		if (pg < pages - 1) content.setItem(53, button(net.minecraft.world.item.Items.ARROW, "Page suivante »"));
		final int current = pg;
		p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new ButtonMenu(id, inv, content, (sp, slot) -> {
			if (slot == 45 && current > 0) openCategories(sp, current - 1);
			else if (slot == 53 && current < pages - 1) openCategories(sp, current + 1);
			else if (slot == 49) startSearch(sp);
			else if (slot < PER_PAGE && at[slot] != null) openList(sp, at[slot].name(), at[slot].heads(), 0, current);
		}), Component.literal("Marchand de têtes")));
	}

	private ItemStack info(ServerPlayer p) {
		HeadCatalog.Snapshot s = catalog.snapshot();
		Account acc = SumeniumEco.accounts().active(p);
		return button(net.minecraft.world.item.Items.GOLD_INGOT, "Têtes à " + Money.format(price()),
				s.count() + " têtes dans " + s.categories().size() + " catégories",
				"Ton solde" + (acc.isFaction() ? " (banque de faction)" : "") + " : " + Money.format(acc.balance()),
				"Clic sur une tête = achat d'une tête");
	}

	/** Liste de têtes (une catégorie ou un résultat de recherche), 45 par page. */
	public void openList(ServerPlayer p, String title, List<HeadCatalog.Head> heads, int page, int backPage) {
		int pages = Math.max(1, (heads.size() + PER_PAGE - 1) / PER_PAGE);
		int pg = Math.max(0, Math.min(page, pages - 1));
		SimpleContainer content = new SimpleContainer(54);
		HeadCatalog.Head[] at = new HeadCatalog.Head[54];
		for (int i = 0; i < PER_PAGE && pg * PER_PAGE + i < heads.size(); i++) {
			HeadCatalog.Head h = heads.get(pg * PER_PAGE + i);
			content.setItem(i, display(h));
			at[i] = h;
		}
		if (pg > 0) content.setItem(45, button(net.minecraft.world.item.Items.ARROW, "« Page précédente"));
		content.setItem(46, button(net.minecraft.world.item.Items.BARRIER, "Retour aux catégories"));
		content.setItem(48, info(p));
		content.setItem(49, button(net.minecraft.world.item.Items.PAPER, title, "Page " + (pg + 1) + "/" + pages + " — " + heads.size() + " têtes"));
		content.setItem(50, button(net.minecraft.world.item.Items.COMPASS, "Rechercher une tête", "Clic, puis tape un mot dans le chat"));
		if (pg < pages - 1) content.setItem(53, button(net.minecraft.world.item.Items.ARROW, "Page suivante »"));
		final int current = pg;
		String shortTitle = title.length() > 28 ? title.substring(0, 27) + "…" : title;
		p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new ButtonMenu(id, inv, content, (sp, slot) -> {
			if (slot == 45 && current > 0) openList(sp, title, heads, current - 1, backPage);
			else if (slot == 53 && current < pages - 1) openList(sp, title, heads, current + 1, backPage);
			else if (slot == 46) openCategories(sp, backPage);
			else if (slot == 50) startSearch(sp);
			else if (slot < PER_PAGE && at[slot] != null) {
				buy(sp, at[slot]);
				openList(sp, title, heads, current, backPage);
			}
		}), Component.literal(shortTitle)));
	}

	// ------------------------------------------------------------------ recherche

	public void startSearch(ServerPlayer p) {
		p.closeContainer();
		searching.put(p.getUUID(), System.currentTimeMillis() + 60_000);
		Msg.send(p, Msg.info("Tape un mot à chercher dans le chat (60 s), ou « annuler ». Ce message n'est pas vu par les autres."));
	}

	/** Le joueur a tapé sa recherche : on n'envoie pas le message dans le chat. */
	public boolean onChat(ServerPlayer p, String raw) {
		Long until = searching.get(p.getUUID());
		if (until == null) return false;
		searching.remove(p.getUUID());
		if (System.currentTimeMillis() > until) return false;
		String q = raw.trim();
		if (q.equalsIgnoreCase("annuler") || q.equalsIgnoreCase("stop")) {
			Msg.send(p, Msg.info("Recherche annulée."));
			return true;
		}
		if (q.length() < 2) {
			Msg.send(p, Msg.err("Tape au moins 2 lettres."));
			return true;
		}
		List<HeadCatalog.Head> found = catalog.search(q);
		if (found.isEmpty()) {
			Msg.send(p, Msg.err("Aucune tête trouvée pour « " + q + " ». Les noms sont souvent en anglais (ex. cat, pumpkin)."));
			return true;
		}
		// le menu s'ouvre au tick suivant, sur le thread du serveur
		var server = p.level().getServer();
		server.execute(() -> openList(p, "Recherche : " + q, found, 0, 0));
		return true;
	}

	// ------------------------------------------------------------------ achat

	public void buy(ServerPlayer p, HeadCatalog.Head h) {
		long price = price();
		Account acc = SumeniumEco.accounts().active(p);
		if (!acc.withdraw(price)) {
			Msg.send(p, Msg.err("Fonds insuffisants : une tête coûte " + Money.format(price) + " (solde : " + Money.format(acc.balance()) + ")."));
			return;
		}
		ItemStack stack = item(h);
		Items.give(p, stack, 1);
		Tx tx = new Tx();
		tx.t = System.currentTimeMillis();
		tx.type = Tx.ACHAT_TETE;
		tx.actor = p.getUUID().toString();
		tx.actorName = p.getPlainTextName();
		tx.from = acc.ref();
		tx.fromName = acc.label();
		tx.to = "fisc";
		tx.toName = "FISC";
		tx.item = "minecraft:player_head";
		tx.itemName = h.name();
		tx.qty = 1;
		tx.amount = price;
		tx.world = Shops.world(p.level());
		tx.x = p.getBlockX();
		tx.y = p.getBlockY();
		tx.z = p.getBlockZ();
		tx.note = "tete:" + h.id();
		SumeniumEco.txlog().add(tx);
		store.markDirty();
		Msg.send(p, Msg.ok("Achat : ").append(Msg.item(stack)).append(Component.literal(" pour ").withStyle(ChatFormatting.GREEN))
				.append(Msg.money(price)).append(Component.literal(acc.isFaction() ? " (banque de faction)" : "").withStyle(ChatFormatting.GRAY)));
	}
}
