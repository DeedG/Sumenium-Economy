package fr.sumenium.eco.shop;

import fr.sumenium.eco.Accounts.Account;
import fr.sumenium.eco.Config;
import fr.sumenium.eco.SumeniumEco;
import fr.sumenium.eco.data.DataStore;
import fr.sumenium.eco.data.Deposit;
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
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Boutiques : création, transactions (clic puis quantité tapée dans le chat),
 * rachat FISC, panneaux, protections et alertes de stock.
 */
public final class Shops {

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
			.withZone(fr.sumenium.eco.data.TxLog.ZONE);
	/** Temps laissé pour taper la quantité dans le chat. */
	private static final long CHAT_DELAY_MS = 30_000;
	/** Distance max entre le joueur et la boutique quand il tape la quantité. */
	private static final double MAX_DISTANCE_SQ = 12 * 12;
	/** Quantité "tout". */
	public static final int ALL = Integer.MAX_VALUE;

	private final DataStore store;
	/** Positions protégées (coffres et panneaux des boutiques), par monde : très rapide pour les mixins. */
	private final Map<String, LongOpenHashSet> index = new HashMap<>();
	/** clé du panneau -> boutique */
	private final Map<String, Shop> signs = new HashMap<>();
	/** Transaction en attente de la quantité tapée dans le chat. */
	private final Map<UUID, Pending> pending = new HashMap<>();
	/** Membres qui ont ouvert le coffre d'une boutique : on met le stock à jour à la fermeture. */
	private final Map<UUID, String> watching = new HashMap<>();
	/** Joueurs qui ont ouvert un dépôt de service : on cherche un bon valide à la fermeture. */
	private final Map<UUID, String> depositWatching = new HashMap<>();
	/** Admins en mode gestion (/sumeco bypass) : ils ouvrent et cassent toutes les boutiques. */
	private final Set<UUID> bypass = new HashSet<>();
	public final DynamicPrices dynamic = new DynamicPrices();

	private record Pending(String shopKey, long until) {}

	public Shops(DataStore store) {
		this.store = store;
	}

	// ------------------------------------------------------------------ index et accès

	public void rebuildIndex() {
		index.clear();
		signs.clear();
		for (Shop s : store.shops.values()) index(s);
		for (Deposit d : store.deposits.values()) {
			index.computeIfAbsent(d.world, w -> new LongOpenHashSet()).add(BlockPos.asLong(d.x, d.y, d.z));
		}
	}

	private void index(Shop s) {
		LongOpenHashSet set = index.computeIfAbsent(s.world, w -> new LongOpenHashSet());
		set.add(BlockPos.asLong(s.x, s.y, s.z));
		if (s.hasSign()) {
			set.add(BlockPos.asLong(s.signX, s.signY, s.signZ));
			signs.put(s.signKey(), s);
		}
	}

	private void unindex(Shop s) {
		LongOpenHashSet set = index.get(s.world);
		if (set != null) set.remove(BlockPos.asLong(s.x, s.y, s.z));
		unindexSign(s);
	}

	private void unindexSign(Shop s) {
		if (!s.hasSign()) return;
		LongOpenHashSet set = index.get(s.world);
		if (set != null) set.remove(BlockPos.asLong(s.signX, s.signY, s.signZ));
		signs.remove(s.signKey());
	}

	private static final Map<Object, String> WORLD_NAMES = new IdentityHashMap<>();

	public static String world(Level level) {
		// appelé très souvent par les entonnoirs : on évite de recréer la chaîne à chaque fois
		return WORLD_NAMES.computeIfAbsent(level.dimension(), k -> level.dimension().identifier().toString());
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

	/** Dépôt de service sur ce bloc ou sur l'autre moitié d'un double coffre. */
	public Deposit depositAt(Level level, BlockPos pos) {
		String w = world(level);
		if (indexed(level, pos)) {
			Deposit d = store.deposits.get(Shop.key(w, pos.getX(), pos.getY(), pos.getZ()));
			if (d != null) return d;
		}
		BlockPos other = connectedHalf(level, pos);
		if (other != null && indexed(level, other)) return store.deposits.get(Shop.key(w, other.getX(), other.getY(), other.getZ()));
		return null;
	}

	public Shop shopById(String id) {
		for (Shop s : store.shops.values()) if (s.id.equals(id)) return s;
		return null;
	}

	/** Boutique dont ce bloc est le panneau. */
	public Shop bySign(Level level, BlockPos pos) {
		if (!indexed(level, pos)) return null;
		return signs.get(Shop.key(world(level), pos.getX(), pos.getY(), pos.getZ()));
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

	/** Mode gestion admin : bascule et renvoie le nouvel état. */
	public boolean toggleBypass(ServerPlayer p) {
		if (!bypass.remove(p.getUUID())) {
			bypass.add(p.getUUID());
			return true;
		}
		return false;
	}

	public boolean hasBypass(ServerPlayer p) {
		return bypass.contains(p.getUUID()) && isAdmin(p);
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

	/** Peut gérer (prix, suppression, casse) : membres, admins pour le FISC, ou mode gestion. */
	public boolean canManage(ServerPlayer p, Shop s) {
		return isMember(p, s) || hasBypass(p) || (s.isFisc() && isAdmin(p));
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
		boolean onSign = false;
		if (s == null) {
			s = bySign(level, pos);
			onSign = s != null;
		}
		if (s != null) {
			boolean member = isMember(p, s) || hasBypass(p);
			if (member && !onSign) {
				// ouverture normale pour ravitailler ; stock et panneau mis à jour à la fermeture
				watching.put(p.getUUID(), s.posKey());
				if (!s.hasSign()) linkSign(s);
				return InteractionResult.PASS;
			}
			if (hand != InteractionHand.MAIN_HAND) return InteractionResult.FAIL;
			Account seller = ownerAccount(s);
			if (member && seller != null && seller.ref().equals(SumeniumEco.accounts().active(p).ref())) {
				// sa propre boutique avec le compte qui l'encaisse : on montre juste les infos
				Msg.send(p, describe(s, true, p));
				Msg.send(p, Msg.info(s.salesCount + " vente(s), chiffre d'affaires ").append(Msg.money(s.revenue)));
			} else {
				// le panneau sert aussi aux membres d'une faction qui achètent avec leur compte perso
				customerClick(p, s);
			}
			return InteractionResult.SUCCESS;
		}
		Deposit dep = depositAt(level, pos);
		if (dep != null) {
			// tout le monde peut ouvrir un dépôt pour y poser son bon ; vérifié à la fermeture
			if (hand == InteractionHand.MAIN_HAND) depositWatching.put(p.getUUID(), dep.posKey());
			return InteractionResult.PASS;
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

	/** Avant de casser un bloc : seuls les membres (et le mode gestion admin) cassent une boutique ou son panneau. */
	public boolean beforeBreak(ServerPlayer p, Level level, BlockPos pos) {
		Shop s = find(level, pos);
		if (s == null) s = bySign(level, pos);
		if (s == null) {
			Deposit dep = depositAt(level, pos);
			if (dep == null) return true;
			s = shopById(dep.shopId);
			if (s == null) return true;
		}
		if (canManage(p, s)) return true;
		Msg.send(p, Msg.err("Cette boutique appartient à " + ownerName(s) + "."));
		return false;
	}

	public void afterBreak(ServerPlayer p, Level level, BlockPos pos) {
		Shop s = at(level, pos);
		if (s != null) {
			remove(s, p, true);
			return;
		}
		Deposit dep = store.deposits.get(Shop.key(world(level), pos.getX(), pos.getY(), pos.getZ()));
		if (dep != null) {
			removeDeposit(dep);
			Msg.send(p, Msg.info("Dépôt du service « " + dep.serviceName + " » supprimé."));
			return;
		}
		Shop bySign = bySign(level, pos);
		if (bySign != null) {
			unindexSign(bySign);
			bySign.signX = bySign.signY = bySign.signZ = null;
			bySign.signCache = null;
			store.markDirty();
		}
	}

	/** Chaque seconde : met à jour les boutiques dont un membre vient de fermer le coffre. */
	public void tickWatchers(MinecraftServer server) {
		if (watching.isEmpty()) return;
		Iterator<Map.Entry<UUID, String>> it = watching.entrySet().iterator();
		while (it.hasNext()) {
			var e = it.next();
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			if (p != null && p.containerMenu != p.inventoryMenu) continue; // encore ouvert
			it.remove();
			Shop s = store.shops.get(e.getValue());
			if (s != null) refreshStock(s);
		}
		var dit = depositWatching.entrySet().iterator();
		while (dit.hasNext()) {
			var e = dit.next();
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			if (p != null && p.containerMenu != p.inventoryMenu) continue;
			dit.remove();
			Deposit d = store.deposits.get(e.getValue());
			if (d != null) checkDeposit(d, p);
		}
	}

	// ------------------------------------------------------------------ dépôts de service

	/**
	 * Cherche un bon valide pour ce service dans le dépôt : il est consommé (un par fermeture)
	 * et le coffre émet un signal de redstone.
	 * @return true si un bon a été accepté
	 */
	public boolean checkDeposit(Deposit d, ServerPlayer p) {
		ServerLevel level = null;
		for (ServerLevel l : SumeniumEco.server().getAllLevels()) if (world(l).equals(d.world)) level = l;
		if (level == null) return false;
		BlockPos pos = new BlockPos(d.x, d.y, d.z);
		Container box = container(level, pos);
		if (box == null) return false;
		String refused = null;
		for (int i = 0; i < box.getContainerSize(); i++) {
			ItemStack st = box.getItem(i);
			String id = voucherId(st);
			if (id == null) continue;
			Voucher v = store.vouchers.get(id);
			if (v == null || !d.shopId.equals(v.shopId)) {
				refused = "ce bon n'est pas pour le service « " + d.serviceName + " »";
				continue;
			}
			if (v.usedBy != null) {
				refused = "ce bon a déjà été utilisé";
				continue;
			}
			box.removeItem(i, 1);
			box.setChanged();
			v.usedBy = "dépôt" + (p != null ? " (" + p.getPlainTextName() + ")" : "");
			v.usedAt = System.currentTimeMillis();
			d.used++;
			store.markDirty();
			Tx tx = new Tx();
			tx.type = Tx.BON_UTILISE;
			if (p != null) {
				tx.actor = p.getUUID().toString();
				tx.actorName = p.getPlainTextName();
			}
			tx.shop = d.shopId;
			tx.itemName = d.serviceName;
			tx.note = "bon " + v.id + " de " + v.buyerName + " (dépôt)";
			tx.world = d.world;
			tx.x = d.x;
			tx.y = d.y;
			tx.z = d.z;
			SumeniumEco.txlog().add(tx);
			Redstone.pulse(level, pos, SumeniumEco.config().signalDepotTicks);
			if (p != null) Msg.send(p, Msg.ok("Bon « " + d.serviceName + " » accepté, le service démarre."));
			return true;
		}
		if (refused != null && p != null) Msg.send(p, Msg.err("Bon refusé : " + refused + "."));
		return false;
	}

	/** Crée un dépôt pour un service du joueur sur le coffre visé. */
	public String createDeposit(ServerPlayer p, BlockPos pos, String serviceName) {
		ServerLevel level = p.level();
		if (pos == null || !isShopBlock(level.getBlockState(pos))) return "Regarde le coffre ou le tonneau qui servira de dépôt.";
		if (find(level, pos) != null || depositAt(level, pos) != null) return "Ce coffre est déjà une boutique ou un dépôt.";
		Shop service = null;
		for (Shop s : store.shops.values()) {
			if (s.kind == Shop.Kind.SERVICE && s.serviceName.equalsIgnoreCase(serviceName.trim()) && canManage(p, s)) service = s;
		}
		if (service == null) return "Aucun de tes services ne s'appelle « " + serviceName + " ».";
		Deposit d = new Deposit();
		d.shopId = service.id;
		d.serviceName = service.serviceName;
		d.world = world(level);
		d.x = pos.getX();
		d.y = pos.getY();
		d.z = pos.getZ();
		d.created = System.currentTimeMillis();
		store.deposits.put(d.posKey(), d);
		index.computeIfAbsent(d.world, w -> new LongOpenHashSet()).add(pos.asLong());
		store.markDirty();
		return null;
	}

	public void removeDeposit(Deposit d) {
		store.deposits.remove(d.posKey());
		LongOpenHashSet set = index.get(d.world);
		if (set != null) set.remove(BlockPos.asLong(d.x, d.y, d.z));
		store.markDirty();
	}

	/** Noms des services que ce joueur peut gérer (suggestions de commande). */
	public List<String> serviceNames(ServerPlayer p) {
		List<String> out = new ArrayList<>();
		for (Shop s : store.shops.values()) if (s.kind == Shop.Kind.SERVICE && canManage(p, s)) out.add(s.serviceName);
		return out;
	}

	/** Signal de redstone sur le coffre (et l'autre moitié d'un double coffre) après une vente. */
	private void salePulse(Shop s) {
		int ticks = SumeniumEco.config().signalVenteTicks;
		ServerLevel level = level(s);
		if (ticks <= 0 || level == null) return;
		Redstone.pulse(level, pos(s), ticks);
		BlockPos other = connectedHalf(level, pos(s));
		if (other != null) Redstone.pulse(level, other, ticks);
	}

	// ------------------------------------------------------------------ clients

	private void customerClick(ServerPlayer p, Shop s) {
		Msg.send(p, describe(s, true, p));
		if (s.kind == Shop.Kind.FISC_RACHAT) {
			int have = countInInventory(p, Items.decode(s.item));
			Msg.send(p, Msg.info("Combien veux-tu en vendre ? Tape un nombre dans le chat ou « tout » (tu en as " + have + ")."));
		} else {
			Msg.send(p, Msg.info("Combien veux-tu en acheter ? Tape un nombre dans le chat ou « tout », avec ")
					.append(accountLabel(p)).append(Component.literal(".").withStyle(ChatFormatting.YELLOW)));
		}
		pending.put(p.getUUID(), new Pending(s.posKey(), System.currentTimeMillis() + CHAT_DELAY_MS));
	}

	/**
	 * Message du chat : si le joueur doit indiquer une quantité, on l'utilise et
	 * le message n'est pas envoyé aux autres. Renvoie true si le message est consommé.
	 */
	public boolean onChat(ServerPlayer p, String raw) {
		Pending pend = pending.get(p.getUUID());
		if (pend == null) return false;
		if (System.currentTimeMillis() > pend.until) {
			pending.remove(p.getUUID());
			return false;
		}
		String text = raw.trim().toLowerCase(Locale.ROOT);
		if (text.equals("annuler") || text.equals("stop") || text.equals("non")) {
			pending.remove(p.getUUID());
			Msg.send(p, Msg.info("Transaction annulée."));
			return true;
		}
		int amount;
		if (text.equals("tout") || text.equals("max") || text.equals("all")) {
			amount = ALL;
		} else {
			try {
				amount = Integer.parseInt(text);
			} catch (NumberFormatException e) {
				pending.remove(p.getUUID()); // message normal : on laisse passer et on oublie la transaction
				return false;
			}
			if (amount <= 0) {
				Msg.send(p, Msg.err("Quantité invalide."));
				return true;
			}
		}
		pending.remove(p.getUUID());
		Shop s = store.shops.get(pend.shopKey);
		if (s == null) {
			Msg.send(p, Msg.err("Cette boutique n'existe plus."));
			return true;
		}
		if (!Shops.world(p.level()).equals(s.world) || p.blockPosition().distSqr(pos(s)) > MAX_DISTANCE_SQ) {
			Msg.send(p, Msg.err("Tu es trop loin de la boutique."));
			return true;
		}
		trade(p, s, amount);
		return true;
	}

	private MutableComponent accountLabel(ServerPlayer p) {
		Account a = SumeniumEco.accounts().active(p);
		return Component.literal(a.isFaction() ? "la banque de ta faction" : "ton compte perso")
				.withStyle(ChatFormatting.WHITE);
	}

	public MutableComponent describe(Shop s, boolean withStock) {
		return describe(s, withStock, null);
	}

	/** Ligne de description d'une boutique (avec l'item survolable). */
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
				double f = dynamic.currentFactor(dynamicScope(viewer), s.itemId);
				c.append(Component.literal("Le FISC rachète ").withStyle(ChatFormatting.YELLOW))
						.append(Msg.item(item))
						.append(Component.literal(" à ").withStyle(ChatFormatting.YELLOW))
						.append(Msg.money(Math.round(s.price * f)))
						.append(Component.literal(" l'unité").withStyle(ChatFormatting.YELLOW));
			}
		}
		return c;
	}

	/** Place libre dans l'inventaire principal pour cet item. */
	private static int freeSpace(ServerPlayer p, ItemStack model) {
		int max = Math.max(1, model.getMaxStackSize());
		int free = 0;
		for (ItemStack st : p.getInventory().getNonEquipmentItems()) {
			if (st.isEmpty()) free += max;
			else if (Items.same(st, model)) free += Math.max(0, max - st.getCount());
		}
		return free;
	}

	private static int countInInventory(ServerPlayer p, ItemStack model) {
		if (model.isEmpty()) return 0;
		int n = 0;
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack st = inv.getItem(i);
			if (!st.isEmpty() && ItemStack.isSameItem(st, model)) n += st.getCount();
		}
		return n;
	}

	/** Réalise la transaction pour `amount` items (ou ALL). */
	public void trade(ServerPlayer p, Shop s, int amount) {
		if (s.kind == Shop.Kind.FISC_RACHAT) {
			fiscBuyback(p, s, amount);
		} else {
			purchase(p, s, amount);
		}
	}

	private void purchase(ServerPlayer p, Shop s, int amount) {
		ServerLevel level = p.level();
		Config cfg = SumeniumEco.config();
		Account buyer = SumeniumEco.accounts().active(p);
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

		// nombre de lots : demandés, en stock, payables, et qui rentrent dans l'inventaire
		int lots;
		int perLot = s.kind == Shop.Kind.SERVICE ? 1 : s.quantity;
		if (amount == ALL) lots = Integer.MAX_VALUE;
		else {
			lots = amount / perLot;
			if (lots == 0) {
				Msg.send(p, Msg.err("Cette boutique vend par lots de " + perLot + "."));
				return;
			}
		}
		String limit = null;
		Container box = null;
		if (s.kind == Shop.Kind.ITEM) {
			box = container(level, pos(s));
			if (box == null) {
				Msg.send(p, Msg.err("Coffre introuvable."));
				return;
			}
			int stock = Items.count(box, model);
			s.lastStock = stock;
			if (stock / perLot < lots) {
				lots = stock / perLot;
				limit = "stock";
			}
		}
		if (s.kind == Shop.Kind.SERVICE && lots > 16) {
			lots = 16;
			limit = "16 bons maximum à la fois";
		}
		if (s.price > 0 && buyer.balance() / s.price < lots) {
			lots = (int) (buyer.balance() / s.price);
			limit = "fonds";
		}
		if (s.kind != Shop.Kind.SERVICE) {
			int space = freeSpace(p, model) / perLot;
			if (space < lots) {
				lots = space;
				limit = "place dans l'inventaire";
			}
		} else if (p.getInventory().getFreeSlot() < 0) {
			lots = 0;
			limit = "place dans l'inventaire";
		}
		if (lots <= 0) {
			switch (limit == null ? "" : limit) {
				case "stock" -> {
					Msg.send(p, Msg.err("Rupture de stock."));
					outOfStock(s);
				}
				case "fonds" -> Msg.send(p, Msg.err("Fonds insuffisants sur " + (buyer.isFaction() ? "la banque de ta faction" : "ton compte")
						+ " (" + Money.format(buyer.balance()) + ")."));
				default -> Msg.send(p, Msg.err("Pas assez de place dans ton inventaire."));
			}
			return;
		}

		long total = s.price * lots;
		if (!buyer.withdraw(total)) {
			Msg.send(p, Msg.err("Fonds insuffisants."));
			return;
		}
		int items = lots * perLot;
		long tax = s.kind == Shop.Kind.FISC_VENTE ? total : Math.round(total * cfg.taxeVente);
		long net = total - tax;
		Tx tx = baseTx(p, s);
		tx.from = buyer.ref();
		tx.fromName = buyer.label();
		tx.amount = total;
		tx.tax = tax;
		tx.qty = items;

		switch (s.kind) {
			case ITEM -> {
				Items.remove(box, model, items);
				Items.give(p, model, items);
				s.lastStock = Items.count(box, model);
				tx.type = Tx.ACHAT;
			}
			case SERVICE -> {
				List<String> ids = new ArrayList<>();
				for (int i = 0; i < lots; i++) ids.add(issueVoucher(p, s).id);
				tx.type = Tx.ACHAT_SERVICE;
				tx.note = "bons " + String.join(",", ids);
			}
			case FISC_VENTE -> {
				Items.give(p, model, items);
				tx.type = Tx.ACHAT_FISC;
			}
			default -> {
			}
		}
		if (s.kind == Shop.Kind.SERVICE) {
			Msg.send(p, Msg.ok(lots + " bon(s) « " + s.serviceName + " » acheté(s) pour ").append(Msg.money(total))
					.append(Component.literal(". Remets-le à " + ownerName(s) + ".").withStyle(ChatFormatting.GREEN)));
		} else {
			Msg.send(p, Msg.ok("Achat : ").append(Msg.item(model, items))
					.append(Component.literal(" pour ").withStyle(ChatFormatting.GREEN)).append(Msg.money(total)));
		}
		if (limit != null && amount != ALL) {
			Msg.send(p, Msg.info("Quantité réduite (" + limit + ")."));
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
		s.itemsMoved += items;
		s.revenue += seller != null ? net : total;
		store.markDirty();
		SumeniumEco.txlog().add(tx);
		if (seller != null) notifySale(s, p, net, s.kind == Shop.Kind.SERVICE ? lots : items);
		if (s.kind == Shop.Kind.ITEM) {
			if (s.lastStock < s.quantity) outOfStock(s);
			else s.outOfStockNotified = false;
		}
		updateSign(s);
		salePulse(s);
	}

	private Voucher issueVoucher(ServerPlayer p, Shop s) {
		Voucher v = new Voucher();
		v.id = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
		v.shopId = s.id;
		v.serviceName = s.serviceName;
		v.sellerName = ownerName(s);
		v.buyerUuid = p.getUUID().toString();
		v.buyerName = p.getPlainTextName();
		v.price = s.price;
		v.issued = System.currentTimeMillis();
		store.vouchers.put(v.id, v);

		ItemStack paper = new ItemStack(net.minecraft.world.item.Items.PAPER);
		paper.set(DataComponents.CUSTOM_NAME, Component.literal(s.serviceName).withStyle(st -> st.withItalic(false).withColor(ChatFormatting.GOLD)));
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

	private void fiscBuyback(ServerPlayer p, Shop s, int amount) {
		ItemStack model = Items.decode(s.item);
		if (model.isEmpty()) {
			Msg.send(p, Msg.err("Item de rachat illisible, préviens un admin."));
			return;
		}
		int have = countInInventory(p, model);
		int count = amount == ALL ? have : Math.min(amount, have);
		if (count <= 0) {
			Msg.send(p, Msg.err("Tu n'as pas cet item sur toi."));
			return;
		}
		var inv = p.getInventory();
		int left = count;
		for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
			ItemStack st = inv.getItem(i);
			if (!st.isEmpty() && ItemStack.isSameItem(st, model)) {
				int take = Math.min(left, st.getCount());
				st.shrink(take);
				if (st.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
				left -= take;
			}
		}
		p.containerMenu.broadcastChanges();
		Account to = SumeniumEco.accounts().active(p);
		long total = dynamic.sell(dynamicScope(p), s.itemId, s.price, count);
		to.add(total);
		Tx tx = baseTx(p, s);
		tx.type = Tx.RACHAT_FISC;
		tx.from = "fisc";
		tx.fromName = "FISC";
		tx.to = to.ref();
		tx.toName = to.label();
		tx.qty = count;
		tx.amount = total;
		s.salesCount++;
		s.itemsMoved += count;
		s.revenue += total;
		store.markDirty();
		SumeniumEco.txlog().add(tx);
		salePulse(s);
		Msg.send(p, Msg.ok("Le FISC t'achète ").append(Msg.item(model, count))
				.append(Component.literal(" : +").withStyle(ChatFormatting.GREEN)).append(Msg.money(total))
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

	// ------------------------------------------------------------------ panneaux

	/** Cherche et rattache un panneau posé sur le coffre de la boutique. */
	public void linkSign(Shop s) {
		ServerLevel level = level(s);
		if (level == null || !level.isLoaded(pos(s))) return;
		List<BlockPos> parts = new ArrayList<>(2);
		parts.add(pos(s));
		BlockPos other = connectedHalf(level, pos(s));
		if (other != null) parts.add(other);
		BlockPos sign = ShopSigns.find(level, parts, p -> bySign(level, p) != null);
		if (sign == null) return;
		s.signX = sign.getX();
		s.signY = sign.getY();
		s.signZ = sign.getZ();
		s.signCache = null;
		index(s);
		store.markDirty();
		updateSign(s);
	}

	/** Pose un panneau neuf contre le coffre (face avant d'abord) et le rattache. */
	public boolean placeSign(Shop s) {
		ServerLevel level = level(s);
		if (level == null || !level.isLoaded(pos(s))) return false;
		BlockPos pos = pos(s);
		BlockState chest = level.getBlockState(pos);
		List<Direction> dirs = new ArrayList<>();
		if (chest.hasProperty(ChestBlock.FACING)) dirs.add(chest.getValue(ChestBlock.FACING));
		else if (chest.hasProperty(BarrelBlock.FACING) && chest.getValue(BarrelBlock.FACING).getAxis().isHorizontal()) {
			dirs.add(chest.getValue(BarrelBlock.FACING));
		}
		for (Direction d : Direction.Plane.HORIZONTAL) if (!dirs.contains(d)) dirs.add(d);
		List<BlockPos> parts = new ArrayList<>(List.of(pos));
		BlockPos other = connectedHalf(level, pos);
		if (other != null) parts.add(other);
		for (BlockPos part : parts) {
			for (Direction d : dirs) {
				BlockPos at = part.relative(d);
				if (parts.contains(at) || !level.getBlockState(at).canBeReplaced()) continue;
				level.setBlock(at, net.minecraft.world.level.block.Blocks.OAK_WALL_SIGN.defaultBlockState()
						.setValue(net.minecraft.world.level.block.WallSignBlock.FACING, d), 3);
				return attachSign(s, at);
			}
		}
		BlockPos top = pos.above();
		if (level.getBlockState(top).canBeReplaced()) {
			level.setBlock(top, net.minecraft.world.level.block.Blocks.OAK_SIGN.defaultBlockState(), 3);
			return attachSign(s, top);
		}
		return false;
	}

	private boolean attachSign(Shop s, BlockPos sign) {
		unindexSign(s);
		s.signX = sign.getX();
		s.signY = sign.getY();
		s.signZ = sign.getZ();
		s.signCache = null;
		index(s);
		store.markDirty();
		updateSign(s);
		return s.hasSign();
	}

	/** /boutique panneau : réécrit le panneau, en retrouve un ou en pose un neuf. */
	public boolean ensureSign(Shop s) {
		if (s.hasSign()) {
			s.signCache = null;
			updateSign(s);
			if (s.hasSign()) return true;
		}
		linkSign(s);
		return s.hasSign() || placeSign(s);
	}

	/** Passe une boutique perso en boutique de faction (ou l'inverse). Renvoie un message d'erreur ou null. */
	public String transfer(ServerPlayer p, Shop s, boolean toFaction) {
		if (s.isFisc()) return "Les boutiques du FISC ne se transfèrent pas.";
		PlayerData d = SumeniumEco.accounts().player(p);
		if (toFaction) {
			if (s.ownerType == Shop.OwnerType.FACTION) return "C'est déjà une boutique de faction.";
			PlayerData owner = store.players.get(s.ownerId);
			if (!s.ownerId.equals(d.uuid) && !hasBypass(p)) return "Seul le propriétaire peut la donner à sa faction.";
			Faction f = SumeniumEco.accounts().factionOf(owner);
			if (f == null) return "Le propriétaire n'est dans aucune faction.";
			s.ownerType = Shop.OwnerType.FACTION;
			s.ownerId = f.id;
		} else {
			if (s.ownerType == Shop.OwnerType.JOUEUR) return "C'est déjà une boutique perso.";
			Faction f = store.factions.get(s.ownerId);
			boolean chef = f != null && f.role(d.uuid) == Faction.Role.CHEF;
			boolean creator = d.uuid.equals(s.creator) && f != null && f.members.containsKey(d.uuid);
			if (!chef && !creator) return "Seuls le chef et le créateur de la boutique peuvent la reprendre en perso.";
			s.ownerType = Shop.OwnerType.JOUEUR;
			s.ownerId = d.uuid;
		}
		s.signCache = null;
		store.markDirty();
		updateSign(s);
		SumeniumEco.exporter().requestExport();
		return null;
	}

	/** Réécrit le panneau si besoin ; le détache s'il a disparu. */
	public void updateSign(Shop s) {
		if (!s.hasSign()) return;
		ServerLevel level = level(s);
		if (level == null) return;
		if (!ShopSigns.write(level, s, ShopSigns.ownerLabel(s), ShopSigns.ownerColor(s))) {
			unindexSign(s);
			s.signX = s.signY = s.signZ = null;
			s.signCache = null;
			store.markDirty();
		}
	}

	// ------------------------------------------------------------------ notifications

	private void notifySale(Shop s, ServerPlayer buyer, long net, int qty) {
		MinecraftServer server = SumeniumEco.server();
		String what = s.kind == Shop.Kind.SERVICE ? qty + "× « " + s.serviceName + " »" : qty + "× " + s.itemName;
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
		if (s.kind != Shop.Kind.ITEM) {
			updateSign(s);
			return -1;
		}
		ServerLevel level = level(s);
		if (level == null || !level.isLoaded(pos(s))) return s.lastStock;
		Container box = container(level, pos(s));
		if (box == null) return s.lastStock;
		int stock = Items.count(box, Items.decode(s.item));
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
		updateSign(s);
		return stock;
	}

	/** Contrôle régulier : stocks, panneaux, et boutiques dont le coffre a disparu. */
	public void refreshAll() {
		for (Shop s : new ArrayList<>(store.shops.values())) {
			ServerLevel level = level(s);
			if (level == null || !level.isLoaded(pos(s))) continue;
			if (!isShopBlock(level.getBlockState(pos(s)))) {
				SumeniumEco.LOGGER.info("Boutique {} supprimée : plus de coffre en {} {} {}", s.id, s.x, s.y, s.z);
				remove(s, null, false);
				continue;
			}
			if (!s.hasSign()) linkSign(s);
			refreshStock(s);
		}
	}

	/** Récap à la connexion : seulement les boutiques du joueur et de sa faction. */
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

	/** Bloc visé par le joueur (5 blocs max). */
	public static BlockPos target(ServerPlayer p) {
		var hit = p.pick(5.0, 1.0f, false);
		if (hit instanceof BlockHitResult bhr && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
			return bhr.getBlockPos();
		}
		return null;
	}

	/** Frais de création : réduits si le propriétaire a déjà une boutique de la même famille d'items. */
	public long creationFee(Shop.Kind kind, Shop.OwnerType ownerType, String ownerId, String itemId) {
		Config cfg = SumeniumEco.config();
		if (kind == Shop.Kind.SERVICE) return Money.toCents(cfg.fraisBoutiqueService);
		String family = Family.of(itemId);
		for (Shop other : store.shops.values()) {
			if (other.kind == Shop.Kind.ITEM && other.ownerType == ownerType && other.ownerId.equals(ownerId)
					&& Family.of(other.itemId).equals(family)) {
				return Money.toCents(cfg.fraisBoutiqueMemeFamille);
			}
		}
		return Money.toCents(cfg.fraisBoutiqueItem);
	}

	public String create(ServerPlayer p, Shop.Kind kind, long price, int quantity, String serviceName, boolean admin) {
		return create(p, target(p), kind, price, quantity, serviceName, admin);
	}

	/**
	 * Crée une boutique sur le coffre indiqué.
	 * @return message d'erreur, ou null si tout s'est bien passé
	 */
	public String create(ServerPlayer p, BlockPos pos, Shop.Kind kind, long price, int quantity, String serviceName, boolean admin) {
		ServerLevel level = p.level();
		if (pos == null || !isShopBlock(level.getBlockState(pos))) return "Regarde un coffre ou un tonneau.";
		if (find(level, pos) != null) return "Ce coffre est déjà une boutique.";
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
			Account acc = SumeniumEco.accounts().active(p);
			s.ownerType = acc.isFaction() ? Shop.OwnerType.FACTION : Shop.OwnerType.JOUEUR;
			s.ownerId = acc.isFaction() ? acc.faction.id : acc.player.uuid;
			long fee = creationFee(kind, s.ownerType, s.ownerId, s.itemId);
			if (!acc.withdraw(fee)) {
				return "Il faut " + Money.format(fee) + " sur " + (acc.isFaction() ? "la banque de ta faction" : "ton compte")
						+ " pour créer cette boutique.";
			}
			s.feePaid = fee;
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
		linkSign(s);
		if (!s.hasSign()) placeSign(s);
		refreshStock(s);
		SumeniumEco.exporter().requestExport();
		return null;
	}

	/** Supprime une boutique, avec remboursement partiel des frais. */
	public void remove(Shop s, ServerPlayer actor, boolean refund) {
		store.shops.remove(s.posKey());
		unindex(s);
		for (Deposit d : new ArrayList<>(store.deposits.values())) if (d.shopId.equals(s.id)) removeDeposit(d);
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
		linkSign(s);
		refreshStock(s);
	}

	public List<Shop> ownedBy(PlayerData d) {
		List<Shop> out = new ArrayList<>();
		for (Shop s : store.shops.values()) if (isOwnedBy(s, d)) out.add(s);
		return out;
	}
}
