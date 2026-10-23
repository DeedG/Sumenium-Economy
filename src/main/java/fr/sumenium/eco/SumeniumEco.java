package fr.sumenium.eco;

import fr.sumenium.eco.command.AccountCommands;
import fr.sumenium.eco.command.AdminCommands;
import fr.sumenium.eco.command.FactionCommands;
import fr.sumenium.eco.command.ShopCommands;
import fr.sumenium.eco.data.DataStore;
import fr.sumenium.eco.data.PlayerData;
import fr.sumenium.eco.data.TxLog;
import fr.sumenium.eco.faction.Factions;
import fr.sumenium.eco.shop.Shops;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Suménium Economy : économie, FISC, boutiques, factions et AFK.
 * Mod 100 % côté serveur.
 */
public final class SumeniumEco implements ModInitializer {

	public static final String MOD_ID = "sumenium-economy";
	public static final Logger LOGGER = LoggerFactory.getLogger("Suménium Economy");

	private static Config config;
	private static Path configFile;
	private static MinecraftServer server;
	private static DataStore store;
	private static TxLog txlog;
	private static Accounts accounts;
	private static Factions factions;
	private static Shops shops;
	private static Exporter exporter;
	private static fr.sumenium.eco.hdv.AuctionHouse hdv;
	private static fr.sumenium.eco.heads.HeadShop heads;
	private static boolean ready;

	public static boolean isReady() {
		return ready;
	}

	@Override
	public void onInitialize() {
		Path game = gameDir();
		configFile = FabricLoader.getInstance().getConfigDir().resolve("sumenium-economy.json");
		config = Config.load(configFile);
		Path data = game.resolve("sumenium-data");
		store = new DataStore(data.resolve("data"));
		txlog = new TxLog(data.resolve("journal"), config.historiqueJours);
		accounts = new Accounts(store);
		factions = new Factions(store);
		shops = new Shops(store);
		exporter = new Exporter(data.resolve("export"));
		hdv = new fr.sumenium.eco.hdv.AuctionHouse(store);
		heads = new fr.sumenium.eco.heads.HeadShop(store, new fr.sumenium.eco.heads.HeadCatalog(data.resolve("tetes")));

		CommandRegistrationCallback.EVENT.register((dispatcher, registry, selection) -> {
			AccountCommands.register(dispatcher);
			ShopCommands.register(dispatcher);
			FactionCommands.register(dispatcher);
			AdminCommands.register(dispatcher);
			fr.sumenium.eco.command.HdvCommands.register(dispatcher);
			fr.sumenium.eco.command.HeadCommands.register(dispatcher);
		});

		ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			server = s;
			store.load();
			factions.cleanupDatapackTeams();
			txlog.loadRecent();
			shops.rebuildIndex();
			ready = true;
			if (!SelfTest.enabled()) heads.catalog().start();
			exporter.requestExport();
			if (SelfTest.enabled()) SelfTest.run(s);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
			if (!ready) return;
			for (ServerPlayer p : s.getPlayerList().getPlayers()) accounts.player(p).lastSeen = System.currentTimeMillis();
			store.save();
			exporter.exportAll();
			heads.catalog().stop();
			ready = false;
		});

		ServerTickEvents.END_SERVER_TICK.register(s -> {
			if (!ready) return;
			int tick = s.getTickCount();
			fr.sumenium.eco.shop.Redstone.tick(s);
			if (tick % 20 == 0) {
				Afk.tick(s);
				shops.tickWatchers(s);
			}
			if (tick % 1200 == 0 && store.isDirty()) store.save();
			if (tick % 6000 == 0) shops.refreshAll();
			if (tick % 100 == 0) {
				exporter.tick();
				fr.sumenium.eco.shop.Directory.tick();
			}
			if (tick % 400 == 0) hdv.tick();
			if (tick % 200 == 0) {
				for (ServerPlayer p : s.getPlayerList().getPlayers()) factions.syncTeam(p, accounts.player(p));
			}
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
			if (!ready) return;
			ServerPlayer p = handler.player;
			PlayerData d = accounts.player(p);
			factions.syncOnJoin(p, d);
			shops.loginRecap(p);
			hdv.loginRecap(p);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> {
			if (!ready) return;
			ServerPlayer p = handler.player;
			accounts.player(p).lastSeen = System.currentTimeMillis();
			store.markDirty();
			Afk.forget(p);
		});

		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!ready || level.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
			if (hdv.isMailboxBlock(level, hit.getBlockPos())) {
				if (hand == net.minecraft.world.InteractionHand.MAIN_HAND && !(shops.hasBypass(sp) && sp.isShiftKeyDown())) {
					hdv.openMailbox(sp);
					return InteractionResult.SUCCESS;
				}
				return hand == net.minecraft.world.InteractionHand.MAIN_HAND ? InteractionResult.PASS : InteractionResult.FAIL;
			}
			return shops.onUse(sp, level, hand, hit);
		});
		ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
			if (!ready) return true;
			Afk.activity(sender);
			if (heads.onChat(sender, message.signedContent())) return false;
			if (shops.onChat(sender, message.signedContent())) return false;
			return !hdv.onChat(sender, message.signedContent());
		});
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> {
			if (!ready || !(player instanceof ServerPlayer sp)) return true;
			boolean special = hdv.isMailboxBlock(level, pos) || fr.sumenium.eco.shop.Directory.isLectern(level, pos);
			if (special && !shops.hasBypass(sp)) {
				fr.sumenium.eco.util.Msg.send(sp, fr.sumenium.eco.util.Msg.err("Bloc de l'hôtel des ventes : /sumeco bypass pour le casser."));
			} else if (shops.beforeBreak(sp, level, pos)) {
				if (special) {
					hdv.removeMailboxBlock(level, pos);
					fr.sumenium.eco.shop.Directory.remove(level, pos);
				}
				return true;
			}
			// casse refusée : on renvoie le bloc et son contenu (texte du panneau) au joueur
			sp.level().getServer().execute(() -> {
				var current = level.getBlockState(pos);
				level.sendBlockUpdated(pos, current, current, 3);
				var entity = level.getBlockEntity(pos);
				if (entity != null && entity.getUpdatePacket() != null) sp.connection.send(entity.getUpdatePacket());
			});
			return false;
		});
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, be) -> {
			if (!ready || !(player instanceof ServerPlayer sp)) return;
			shops.afterBreak(sp, level, pos);
		});

		net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (!ready || level.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
			if (fr.sumenium.eco.heads.HeadShop.isNpc(entity)) {
				if (hand == net.minecraft.world.InteractionHand.MAIN_HAND) heads.openCategories(sp, 0);
				return InteractionResult.SUCCESS;
			}
			if (!fr.sumenium.eco.hdv.AuctionHouse.isNpc(entity)) return InteractionResult.PASS;
			if (hand == net.minecraft.world.InteractionHand.MAIN_HAND) hdv.openMenu(sp, 0, false);
			return InteractionResult.SUCCESS;
		});
		net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (!ready || !(fr.sumenium.eco.hdv.AuctionHouse.isNpc(entity) || fr.sumenium.eco.heads.HeadShop.isNpc(entity))) return InteractionResult.PASS;
			return InteractionResult.FAIL;
		});

		LOGGER.info("Suménium Economy chargé");
	}

	/** Utilisé par les mixins : vrai si le bloc fait partie d'une boutique. */
	public static boolean isProtected(Level level, BlockPos pos) {
		return ready && !level.isClientSide() && shops.isProtected(level, pos);
	}

	public static void reloadConfig() {
		config = Config.load(configFile);
	}

	public static void saveConfig() {
		config.save(configFile);
	}

	public static Path gameDir() {
		return FabricLoader.getInstance().getGameDir();
	}

	public static Config config() {
		return config;
	}

	public static MinecraftServer server() {
		return server;
	}

	public static DataStore store() {
		return store;
	}

	public static TxLog txlog() {
		return txlog;
	}

	public static Accounts accounts() {
		return accounts;
	}

	public static Factions factions() {
		return factions;
	}

	public static Shops shops() {
		return shops;
	}

	public static fr.sumenium.eco.heads.HeadShop heads() {
		return heads;
	}

	public static fr.sumenium.eco.hdv.AuctionHouse hdv() {
		return hdv;
	}

	public static Exporter exporter() {
		return exporter;
	}
}
