package fr.sumenium.eco.shop;

import fr.sumenium.eco.Accounts;
import fr.sumenium.eco.Accounts.Account;
import fr.sumenium.eco.Config;
import fr.sumenium.eco.SumeniumEco;
import fr.sumenium.eco.data.DataStore;
import fr.sumenium.eco.data.Faction;
import fr.sumenium.eco.data.PlayerData;
import fr.sumenium.eco.data.Shop;
import fr.sumenium.eco.data.Tx;
import fr.sumenium.eco.data.Voucher;
import fr.sumenium.eco.util.Items;
import fr.sumenium.eco.util.Money;
import fr.sumenium.eco.util.Msg;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Boutiques : création, achats, rachat FISC, protections, alertes de stock. */
public final class Shops {

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
			.withZone(fr.sumenium.eco.data.TxLog.ZONE);

	private final DataStore store;
	private final Map<String, LongOpenHashSet> index = new HashMap<>();
	/** Achat en attente de confirmation : joueur -> (clé boutique, échéance ms). */
	private final Map<UUID, Pending> pending = new HashMap<>();
	public final DynamicPrices dynamic = new DynamicPrices();

	private record Pending(String shopKey, long until) {}

	public Shops(DataStore store) {
		this.store = store;
	}

	// ------------------------------------------------------------------ index et accès

	public void rebuildIndex() {
		index.clear();
		for (Shop s : store.shops.values()) index(s);
	}

	private void index(Shop s) {
		index.computeIfAbsent(s.world, w -> new LongOpenHashSet()).add(BlockPos.asLong(s.x, s.y, s.z));
	}

	private void unindex(Shop s) {
		LongOpenHashSet set = index.get(s.world);
		if (set != null) set.remove(BlockPos.asLong(s.x, s.y, s.z));
	}

	public static String world(Level level) {
		return level.dimension().identifier().toString();
	}

	private boolean indexed(Level level, BlockPos pos) {
		LongOpenHashSet set = index.get(world(level));
		return set != null && set.contains(pos.asLong());
	}

	public Shop at(Level level, BlockPos pos) {
		if (!indexed(level, pos)) return null;
		return store.shops.get(Shop.key(world(level), pos.getX(), pos.getY(), pos.getZ()));
	}

	/** Boutique sur ce bloc ou sur l'autre moitié d'un double coffre. */
	public Shop find(Level level, BlockPos pos) {
		Shop s = at(level, pos);
		if (s != null) return s;
		BlockPos other = connectedHalf(level, pos);
		return other == null ? null : at(level, other);
	}

	/** Appelé par les mixins (entonnoirs, explosions, golems) : rapide. */
	public boolean isProtected(Level level, BlockPos pos) {
		if (index.isEmpty()) return false;
		if (indexed(level, pos)) return true;
		BlockPos other = connectedHalf(level, pos);
		return other != null && indexed(level, other);
	}

	private static BlockPos connectedHalf(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE)
				&& state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
			return ChestBlock.getConnectedBlockPos(pos, state);
		}
		return null;
	}

	public static boolean isShopBlock(BlockState state) {
		Block b = state.getBlock();
		return b instanceof ChestBlock || b instanceof BarrelBlock;
	}

	/** Contenu du coffre (les deux moitiés pour un double coffre). */
	public static Container container(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state.getBlock() instanceof ChestBlock chest) {
			return ChestBlock.getContainer(chest, state, level, pos, true);
		}
		BlockEntity be = level.getBlockEntity(pos);
		return be instanceof Container c ? c : null;
	}

	public ServerLevel level(Shop s) {
		MinecraftServer server = SumeniumEco.server();
		for (ServerLevel l : server.getAllLevels()) {
			if (world(l).equals(s.world)) return l;
		}
		return null;
	}

	public BlockPos pos(Shop s) {
		return new BlockPos(s.x, s.y, s.z);
	}

	// ------------------------------------------------------------------ droits

	public static boolean isAdmin(ServerPlayer p) {
		return p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}

	/** Propriétaire ou membre de la faction propriétaire. */
	public boolean isMember(ServerPlayer p, Shop s) {
		String uuid = p.getUUID().toString();
		return switch (s.ownerType) {
			case JOUEUR -> uuid.equals(s.ownerId);
			case FACTION -> {
				Faction f = SumeniumEco.accounts().factionOf(store.players.get(uuid));
				yield f != null && f.id.equals(s.ownerId);
			}
			case FISC -> false;
		};
	}

	public boolean canManage(ServerPlayer p, Shop s) {
		return isMember(p, s) || isAdmin(p);
	}

	public Account ownerAccount(Shop s) {
		return switch (s.ownerType) {
			case JOUEUR -> {
				PlayerData d = store.players.get(s.ownerId);
				yield d == null ? null : Account.of(d);
			}
			case FACTION -> {
				Faction f = store.factions.get(s.ownerId);
				yield f == null ? null : Account.of(f);
			}
			case FISC -> null;
		};
	}

	public String ownerName(Shop s) {
		return switch (s.ownerType) {
			case JOUEUR -> {
				PlayerData d = store.players.get(s.ownerId);
				yield d == null ? "?" : d.name;
			}
			case FACTION -> {
				Faction f = store.factions.get(s.ownerId);
				yield f == null ? s.ownerId : "faction " + f.tag;
			}
			case FISC -> "FISC";
		};
	}

	/** Joueurs (uuid) à prévenir pour cette boutique. */
	private List<String> owners(Shop s) {
		List<String> out = new ArrayList<>();
		if (s.ownerType == Shop.OwnerType.JOUEUR) out.add(s.ownerId);
		else if (s.ownerType == Shop.OwnerType.FACTION) {
			Faction f = store.factions.get(s.ownerId);
			if (f != null) out.addAll(f.members.keySet());
		}
		return out;
	}

	// ------------------------------------------------------------------ événements

	/** Clic droit sur un bloc (UseBlockCallback). */
	public InteractionResult onUse(ServerPlayer p, Level level, InteractionHand hand, BlockHitResult hit) {
		BlockPos pos = hit.getBlockPos();
		Shop s = find(level, pos);
		if (s != null) {
			if (isMember(p, s) || (isAdmin(p) && (p.isCreative() || p.isShiftKeyDown()))) {
				return InteractionResult.PASS; // ouverture normale pour ravitailler
			}
			if (hand != InteractionHand.MAIN_HAND) return InteractionResult.FAIL;
			customerClick(p, s);
			return InteractionResult.SUCCESS;
		}
		// Empêche de coller un coffre à une boutique dont on n'est pas membre (double coffre).
		ItemStack held = p.getItemInHand(hand);
		if (held.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ChestBlock) {
			BlockPos place = level.getBlockState(pos).canBeReplaced() ? pos : pos.relative(hit.getDirection());
			for (Direction d : Direction.Plane.HORIZONTAL) {
				BlockPos n = place.relative(d);
				Shop near = at(level, n);
				if (near != null && level.getBlockState(n).getBlock() == bi.getBlock() && !canManage(p, near)) {
					Msg.send(p, Msg.err("Tu ne peux pas poser un coffre collé à la boutique de " + ownerName(near) + "."));
					return InteractionResult.FAIL;
				}
			}
		}
		return InteractionResult.PASS;
	}

	/** Avant de casser un bloc : seuls les membres (et les admins) cassent une boutique. */
	public boolean beforeBreak(ServerPlayer p, Level level, BlockPos pos) {
		Shop s = find(level, pos);
		if (s == null) return true;
		if (canManage(p, s)) return true;
		Msg.send(p, Msg.err("Cette boutique appartient à " + ownerName(s) + "."));
		return false;
	}

	public void afterBreak(ServerPlayer p, Level level, BlockPos pos) {
		Shop s = at(level, pos);
		if (s != null) remove(s, p, true);
	}

	// ------------------------------------------------------------------ clients

	private void customerClick(ServerPlayer p, Shop s) {
		if (s.kind == Shop.Kind.FISC_RACHAT) {
			fiscBuyback(p, s);
			return;
		}
		long now = System.currentTimeMillis();
		Pending pend = pending.get(p.getUUID());
		if (pend != null && pend.shopKey.equals(s.posKey()) && now <= pend.until) {
			pending.remove(p.getUUID());
			purchase(p, s);
			return;
		}
		pending.put(p.getUUID(), new Pending(s.posKey(), now + SumeniumEco.config().delaiConfirmationAchat * 1000L));
		Msg.send(p, describe(s, true));
		Msg.send(p, Msg.info("Clique encore dans les " + SumeniumEco.config().delaiConfirmationAchat
				+ " s pour acheter avec ").append(accountLabel(p)).append(Component.literal(".").withStyle(ChatFormatting.YELLOW)));
	}

	private MutableComponent accountLabel(ServerPlayer p) {
		Account a = SumeniumEco.accounts().active(p);
		return Component.literal(a.isFaction() ? "la banque de ta faction" : "ton compte perso")
				.withStyle(ChatFormatting.WHITE);
	}

	/** Ligne de description d'une boutique (avec l'item survolable). */
	public MutableComponent describe(Shop s, boolean withStock) {
		return describe(s, withStock, null);
	}

	/** Idem, avec le prix de rachat propre au joueur si les prix dynamiques sont actifs. */
	public MutableComponent describe(Shop s, boolean withStock, ServerPlayer viewer) {
		ItemStack item = Items.decode(s.item);
		MutableComponent c = Msg.prefix();
		switch (s.kind) {
			case ITEM -> {
				c.append(Component.literal("Boutique de " + ownerName(s) + " : ").withStyle(ChatFormatting.YELLOW))
						.append(Msg.item(item, s.quantity))
						.append(Component.literal(" pour ").withStyle(ChatFormatting.YELLOW))
						.append(Msg.money(s.price));
				if (withStock) {
					int stock = refreshStock(s);
					c.append(Component.literal(stock >= 0 ? " (stock : " + stock + ")" : "").withStyle(ChatFormatting.GRAY));
				}
			}
			case SERVICE -> c.append(Component.literal("Service de " + ownerName(s) + " : ").withStyle(ChatFormatting.YELLOW))
					.append(Component.literal("« " + s.serviceName + " »").withStyle(ChatFormatting.AQUA))
					.append(Component.literal(" pour ").withStyle(ChatFormatting.YELLOW))
					.append(Msg.money(s.price));
			case FISC_VENTE -> c.append(Component.literal("Le FISC vend ").withStyle(ChatFormatting.YELLOW))
					.append(Msg.item(item, s.quantity))
					.append(Component.literal(" pour ").withStyle(ChatFormatting.YELLOW))
					.append(Msg.money(s.price));
			case FISC_RACHAT -> {
				String scope = dynamicScope(viewer);
				double f = dynamic.currentFactor(scope, s.itemId);
				c.append(Component.literal("Le FISC rachète ").withStyle(ChatFormatting.YELLOW))
						.append(Msg.item(item))
						.append(Component.literal(" à ").withStyle(ChatFormatting.YELLOW))
						.append(Msg.money(Math.round(s.price * f)))
						.append(Component.literal(" l'unité").withStyle(ChatFormatting.YELLOW));
			}
		}
		return c;
	}

	private void purchase(ServerPlayer p, Shop s) {
		ServerLevel level = p.level();
		Config cfg = SumeniumEco.config();
		Accounts accounts = SumeniumEco.accounts();
		Account buyer = accounts.active(p);
		Account seller = ownerAccount(s);
		ItemStack model = Items.decode(s.item);

		if (s.kind != Shop.Kind.FISC_VENTE && seller == null) {
			Msg.send(p, Msg.err("Le propriétaire de cette boutique n'existe plus."));
			return;
		}
		if (seller != null && seller.ref().equals(buyer.ref())) {
			Msg.send(p, Msg.err("Tu ne peux pas acheter dans ta propre boutique."));
			return;
		}
		if ((s.kind == Shop.Kind.ITEM || s.kind == Shop.Kind.FISC_VENTE) && model.isEmpty()) {
			Msg.send(p, Msg.err("Item de la boutique illisible, préviens un admin."));
			return;
		}
		Container box = null;
		if (s.kind == Shop.Kind.ITEM) {
			box = container(level, pos(s));
			if (box == null) {
				Msg.send(p, Msg.err("Coffre introuvable."));
				return;
			}
			int stock = Items.count(box, model);
			s.lastStock = stock;
			if (stock < s.quantity) {
				Msg.send(p, Msg.err("Rupture de stock."));
				outOfStock(s);
				return;
			}
		}
		if (!buyer.withdraw(s.price)) {
			Msg.send(p, Msg.err("Fonds insuffisants sur " + (buyer.isFaction() ? "la banque de ta faction" : "ton compte")
					+ " (" + Money.format(buyer.balance()) + ")."));
			return;
		}

		long tax = s.kind == Shop.Kind.FISC_VENTE ? s.price : Math.round(s.price * cfg.taxeVente);
		long net = s.price - tax;
		Tx tx = baseTx(p, s);
		tx.from = buyer.ref();
		tx.fromName = buyer.label();
		tx.amount = s.price;
		tx.tax = tax;

		switch (s.kind) {
			case ITEM -> {
				Items.remove(box, model, s.quantity);
				Items.give(p, model, s.quantity);
				s.lastStock = Items.count(box, model);
				tx.type = Tx.ACHAT;
				tx.qty = s.quantity;
				Msg.send(p, Msg.ok("Achat : ").append(Msg.item(model, s.quantity))
						.append(Component.literal(" pour ").withStyle(ChatFormatting.GREEN)).append(Msg.money(s.price)));
			}
			case SERVICE -> {
				Voucher v = issueVoucher(p, s);
				tx.type = Tx.ACHAT_SERVICE;
				tx.qty = 1;
				tx.note = "bon " + v.id;
				Msg.send(p, Msg.ok("Bon « " + s.serviceName + " » acheté pour ").append(Msg.money(s.price))
						.append(Component.literal(". Remets-le à " + ownerName(s) + ".").withStyle(ChatFormatting.GREEN)));
			}
			case FISC_VENTE -> {
				Items.give(p, model, s.quantity);
				tx.type = Tx.ACHAT_FISC;
				tx.qty = s.quantity;
				Msg.send(p, Msg.ok("Achat au FISC : ").append(Msg.item(model, s.quantity))
						.append(Component.literal(" pour ").withStyle(ChatFormatting.GREEN)).append(Msg.money(s.price)));
			}
			default -> {
			}
		}
		if (seller != null) {
			seller.add(net);
			tx.to = seller.ref();
			tx.toName = seller.label();
		} else {
			tx.to = "fisc";
			tx.toName = "FISC";
		}
		s.salesCount++;
		s.itemsMoved += tx.qty;
		s.revenue += seller != null ? net : s.price;
		store.markDirty();
		SumeniumEco.txlog().add(tx);
		if (seller != null) notifySale(s, p, net, tx.qty);
		if (s.kind == Shop.Kind.ITEM) {
			if (s.lastStock < s.quantity) outOfStock(s);
			else s.outOfStockNotified = false;
		}
	}

	private Voucher issueVoucher(ServerPlayer p, Shop s) {
		Voucher v = new Voucher();
		v.id = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
		v.shopId = s.id;
		v.serviceName = s.serviceName;
		v.sellerName = ownerName(s);
		v.buyerUuid = p.getUUID().toString();
		v.buyerName = p.getPlainTextName();
		v.price = s.price;
		v.issued = System.currentTimeMillis();
		store.vouchers.put(v.id, v);

		ItemStack paper = new ItemStack(net.minecraft.world.item.Items.PAPER);
		paper.set(DataComponents.CUSTOM_NAME, Component.literal(s.serviceName).withStyle(s2 -> s2.withItalic(false).withColor(ChatFormatting.GOLD)));
		List<Component> lore = new ArrayList<>();
		lore.add(Component.literal("Bon de service").withStyle(ChatFormatting.GRAY));
		lore.add(Component.literal("Vendu par " + v.sellerName).withStyle(ChatFormatting.GRAY));
		lore.add(Component.literal("Acheté par " + v.buyerName).withStyle(ChatFormatting.GRAY));
		lore.add(Component.literal("Le " + DATE.format(Instant.ofEpochMilli(v.issued))).withStyle(ChatFormatting.GRAY));
		lore.add(Component.literal("N° " + v.id).withStyle(ChatFormatting.DARK_GRAY));
		paper.set(DataComponents.LORE, new ItemLore(lore));
		CompoundTag tag = new CompoundTag();
		tag.putString("sumeco_bon", v.id);
		paper.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		Items.give(p, paper, 1);
		return v;
	}

	/** Numéro de bon porté par l'item, ou null si ce n'est pas un vrai bon. */
	public static String voucherId(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) return null;
		return data.copyTag().getStringOr("sumeco_bon", null);
	}

	private String dynamicScope(ServerPlayer p) {
		Config c = SumeniumEco.config();
		if (p == null || !c.dynamicEnabled()) return "global";
		PlayerData d = SumeniumEco.accounts().player(p);
		if ("faction".equalsIgnoreCase(c.prixDynamiquesMode)) {
			Faction f = SumeniumEco.accounts().factionOf(d);
			if (f != null) return "faction:" + f.id;
		}
		return "joueur:" + d.uuid;
	}

	private void fiscBuyback(ServerPlayer p, Shop s) {
		ItemStack model = Items.decode(s.item);
		if (model.isEmpty()) {
			Msg.send(p, Msg.err("Item de rachat illisible, préviens un admin."));
			return;
		}
		ItemStack held = p.getMainHandItem();
		if (held.isEmpty() || !ItemStack.isSameItem(held, model)) {
			Msg.send(p, describe(s, false, p));
			Msg.send(p, Msg.info("Clique avec l'item en main pour vendre le stack, accroupi pour tout vendre."));
			return;
		}
		int count = 0;
		if (p.isShiftKeyDown()) {
			var inv = p.getInventory();
			for (int i = 0; i < inv.getContainerSize(); i++) {
				ItemStack st = inv.getItem(i);
				if (!st.isEmpty() && ItemStack.isSameItem(st, model)) {
					count += st.getCount();
					inv.setItem(i, ItemStack.EMPTY);
				}
			}
		} else {
			count = held.getCount();
			p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
		}
		p.containerMenu.broadcastChanges();
		Account to = SumeniumEco.accounts().active(p);
		long amount = dynamic.sell(dynamicScope(p), s.itemId, s.price, count);
		to.add(amount);
		Tx tx = baseTx(p, s);
		tx.type = Tx.RACHAT_FISC;
		tx.from = "fisc";
		tx.fromName = "FISC";
		tx.to = to.ref();
		tx.toName = to.label();
		tx.qty = count;
		tx.amount = amount;
		s.salesCount++;
		s.itemsMoved += count;
		s.revenue += amount;
		store.markDirty();
		SumeniumEco.txlog().add(tx);
		Msg.send(p, Msg.ok("Le FISC t'achète ").append(Msg.item(model, count))
				.append(Component.literal(" : +").withStyle(ChatFormatting.GREEN)).append(Msg.money(amount))
				.append(Component.literal(to.isFaction() ? " (banque de faction)" : "").withStyle(ChatFormatting.GRAY)));
	}

	private Tx baseTx(ServerPlayer p, Shop s) {
		Tx tx = new Tx();
		tx.actor = p.getUUID().toString();
		tx.actorName = p.getPlainTextName();
		tx.shop = s.id;
		tx.item = s.kind == Shop.Kind.SERVICE ? null : s.itemId;
		tx.itemName = s.kind == Shop.Kind.SERVICE ? s.serviceName : s.itemName;
		tx.world = s.world;
		tx.x = s.x;
		tx.y = s.y;
		tx.z = s.z;
		return tx;
	}

	// ------------------------------------------------------------------ notifications

	private void notifySale(Shop s, ServerPlayer buyer, long net, int qty) {
		MinecraftServer server = SumeniumEco.server();
		String what = s.kind == Shop.Kind.SERVICE ? "« " + s.serviceName + " »" : qty + "× " + s.itemName;
		for (String uuid : owners(s)) {
			ServerPlayer o = server.getPlayerList().getPlayer(UUID.fromString(uuid));
			if (o != null) {
				Msg.actionBar(o, Component.literal(buyer.getPlainTextName() + " a acheté " + what + " : +" + Money.format(net))
						.withStyle(ChatFormatting.GOLD));
			} else {
				PlayerData d = store.players.get(uuid);
				if (d != null) {
					d.offlineSales++;
					d.offlineRevenue += net;
				}
			}
		}
	}

	private void outOfStock(Shop s) {
		if (s.outOfStockNotified) return;
		s.outOfStockNotified = true;
		store.markDirty();
		String msg = "Rupture de stock : boutique " + s.itemName + " en " + s.x + " " + s.y + " " + s.z;
		MinecraftServer server = SumeniumEco.server();
		for (String uuid : owners(s)) {
			ServerPlayer o = server.getPlayerList().getPlayer(UUID.fromString(uuid));
			if (o != null) Msg.send(o, Msg.err(msg));
			else {
				PlayerData d = store.players.get(uuid);
				if (d != null && !d.pending.contains(msg)) d.pending.add(msg);
			}
		}
	}

	/** Met à jour le stock connu si le coffre est chargé ; renvoie -1 si inconnu. */
	public int refreshStock(Shop s) {
		if (s.kind != Shop.Kind.ITEM) return -1;
		ServerLevel level = level(s);
		if (level == null || !level.isLoaded(pos(s))) return s.lastStock;
		Container box = container(level, pos(s));
		if (box == null) return s.lastStock;
		ItemStack model = Items.decode(s.item);
		int stock = Items.count(box, model);
		if (stock != s.lastStock) {
			s.lastStock = stock;
			store.markDirty();
		}
		if (stock >= s.quantity && s.outOfStockNotified) {
			s.outOfStockNotified = false;
			store.markDirty();
		} else if (stock < s.quantity) {
			outOfStock(s);
		}
		return stock;
	}

	/** Contrôle régulier : stocks, et boutiques dont le coffre a disparu. */
	public void refreshAll() {
		for (Shop s : new ArrayList<>(store.shops.values())) {
			ServerLevel level = level(s);
			if (level == null || !level.isLoaded(pos(s))) continue;
			if (!isShopBlock(level.getBlockState(pos(s)))) {
				SumeniumEco.LOGGER.info("Boutique {} supprimée : plus de coffre en {} {} {}", s.id, s.x, s.y, s.z);
				remove(s, null, false);
				continue;
			}
			refreshStock(s);
		}
	}

	/** Récap à la connexion. */
	public void loginRecap(ServerPlayer p) {
		PlayerData d = SumeniumEco.accounts().player(p);
		if (d.offlineSales > 0) {
			Msg.send(p, Msg.info("Pendant ton absence : " + d.offlineSales + " vente(s), ")
					.append(Msg.money(d.offlineRevenue)).append(Component.literal(" encaissés.").withStyle(ChatFormatting.YELLOW)));
		}
		for (String m : d.pending) Msg.send(p, Msg.err(m));
		int empty = 0;
		for (Shop s : store.shops.values()) {
			if (s.kind == Shop.Kind.ITEM && isOwnedBy(s, d) && s.lastStock >= 0 && s.lastStock < s.quantity) empty++;
		}
		if (empty > 0 && d.pending.isEmpty()) {
			Msg.send(p, Msg.err(empty + " de tes boutiques sont en rupture de stock."));
		}
		if (d.offlineSales > 0 || !d.pending.isEmpty() || empty > 0) {
			Msg.send(p, Msg.prefix().append(Msg.command("Voir l'historique", "/historique")));
		}
		d.offlineSales = 0;
		d.offlineRevenue = 0;
		d.pending.clear();
		store.markDirty();
	}

	public boolean isOwnedBy(Shop s, PlayerData d) {
		if (s.ownerType == Shop.OwnerType.JOUEUR) return s.ownerId.equals(d.uuid);
		if (s.ownerType == Shop.OwnerType.FACTION) return s.ownerId.equals(d.faction)
				&& SumeniumEco.accounts().factionOf(d) != null;
		return false;
	}

	// ------------------------------------------------------------------ création / suppression

	public boolean inZone(Level level, BlockPos pos) {
		Config c = SumeniumEco.config();
		if (!c.zoneActive) return true;
		if (!world(level).equals(c.zoneMonde)) return false;
		int minX = Math.min(c.zoneX1, c.zoneX2), maxX = Math.max(c.zoneX1, c.zoneX2);
		int minZ = Math.min(c.zoneZ1, c.zoneZ2), maxZ = Math.max(c.zoneZ1, c.zoneZ2);
		return pos.getX() >= minX && pos.getX() <= maxX && pos.getZ() >= minZ && pos.getZ() <= maxZ;
	}

	/** Bloc visé par le joueur (5 blocs max). */
	public static BlockPos target(ServerPlayer p) {
		var hit = p.pick(5.0, 1.0f, false);
		if (hit instanceof BlockHitResult bhr && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
			return bhr.getBlockPos();
		}
		return null;
	}

	/**
	 * Crée une boutique sur le coffre visé.
	 * @return message d'erreur, ou null si tout s'est bien passé
	 */
	public String create(ServerPlayer p, Shop.Kind kind, long price, int quantity, String serviceName, boolean admin) {
		return create(p, target(p), kind, price, quantity, serviceName, admin);
	}

	public String create(ServerPlayer p, BlockPos pos, Shop.Kind kind, long price, int quantity, String serviceName, boolean admin) {
		ServerLevel level = p.level();
		if (pos == null || !isShopBlock(level.getBlockState(pos))) return "Regarde un coffre ou un tonneau.";
		if (find(level, pos) != null) return "Ce coffre est déjà une boutique.";
		BlockPos other = connectedHalf(level, pos);
		if (!admin && !inZone(level, pos)) return "Les boutiques se créent dans la zone commerciale du spawn.";
		if (price < 0 || (kind != Shop.Kind.FISC_RACHAT && price == 0 && !admin)) return "Prix invalide.";

		ItemStack model = ItemStack.EMPTY;
		if (kind != Shop.Kind.SERVICE) {
			model = p.getMainHandItem();
			if (model.isEmpty()) return "Prends en main l'item à vendre.";
			if (Shops.voucherId(model) != null) return "Un bon de service ne peut pas être revendu.";
		}

		Shop s = new Shop();
		s.id = UUID.randomUUID().toString();
		s.kind = kind;
		s.creator = p.getUUID().toString();
		s.world = world(level);
		s.x = pos.getX();
		s.y = pos.getY();
		s.z = pos.getZ();
		s.quantity = Math.max(1, quantity);
		s.price = price;
		s.created = System.currentTimeMillis();
		if (kind == Shop.Kind.SERVICE) {
			s.serviceName = serviceName;
			s.itemId = "minecraft:paper";
			s.itemName = serviceName;
		} else {
			s.item = Items.encode(model);
			s.itemId = Items.id(model);
			s.itemName = Items.name(model);
		}

		if (admin) {
			s.ownerType = Shop.OwnerType.FISC;
			s.ownerId = "fisc";
		} else {
			Config cfg = SumeniumEco.config();
			Account acc = SumeniumEco.accounts().active(p);
			long fee = Money.toCents(kind == Shop.Kind.SERVICE ? cfg.fraisBoutiqueService : cfg.fraisBoutiqueItem);
			if (!acc.withdraw(fee)) {
				return "Il faut " + Money.format(fee) + " sur " + (acc.isFaction() ? "la banque de ta faction" : "ton compte")
						+ " pour créer cette boutique.";
			}
			s.feePaid = fee;
			s.ownerType = acc.isFaction() ? Shop.OwnerType.FACTION : Shop.OwnerType.JOUEUR;
			s.ownerId = acc.isFaction() ? acc.faction.id : acc.player.uuid;
			Tx tx = baseTx(p, s);
			tx.type = Tx.FRAIS_BOUTIQUE;
			tx.from = acc.ref();
			tx.fromName = acc.label();
			tx.to = "fisc";
			tx.toName = "FISC";
			tx.amount = fee;
			tx.tax = fee;
			SumeniumEco.txlog().add(tx);
		}
		store.shops.put(s.posKey(), s);
		index(s);
		store.markDirty();
		if (other != null && !admin) {
			// double coffre : l'autre moitié est protégée automatiquement
			SumeniumEco.LOGGER.debug("Boutique sur double coffre {} / {}", pos, other);
		}
		refreshStock(s);
		SumeniumEco.exporter().requestExport();
		return null;
	}

	/** Supprime une boutique, avec remboursement partiel des frais. */
	public void remove(Shop s, ServerPlayer actor, boolean refund) {
		store.shops.remove(s.posKey());
		unindex(s);
		store.markDirty();
		long back = 0;
		Account owner = ownerAccount(s);
		if (refund && owner != null && s.feePaid > 0) {
			back = Math.round(s.feePaid * SumeniumEco.config().remboursementSuppression);
			owner.add(back);
		}
		Tx tx = new Tx();
		tx.type = back > 0 ? Tx.REMBOURSEMENT_BOUTIQUE : Tx.SUPPRESSION_BOUTIQUE;
		if (actor != null) {
			tx.actor = actor.getUUID().toString();
			tx.actorName = actor.getPlainTextName();
		}
		tx.shop = s.id;
		tx.item = s.itemId;
		tx.itemName = s.itemName;
		tx.from = "fisc";
		tx.fromName = "FISC";
		if (owner != null) {
			tx.to = owner.ref();
			tx.toName = owner.label();
		}
		tx.amount = back;
		tx.world = s.world;
		tx.x = s.x;
		tx.y = s.y;
		tx.z = s.z;
		SumeniumEco.txlog().add(tx);
		if (actor != null) {
			Msg.send(actor, Msg.info("Boutique supprimée" + (back > 0 ? ", " + Money.format(back) + " remboursés à " + owner.label() : "") + "."));
		}
		SumeniumEco.exporter().requestExport();
	}

	/** Pour l'import Savs : ajoute une boutique déjà construite. */
	public void put(Shop s) {
		store.shops.put(s.posKey(), s);
		index(s);
		store.markDirty();
	}

	public List<Shop> ownedBy(PlayerData d) {
		List<Shop> out = new ArrayList<>();
		for (Shop s : store.shops.values()) if (isOwnedBy(s, d)) out.add(s);
		return out;
	}
}
