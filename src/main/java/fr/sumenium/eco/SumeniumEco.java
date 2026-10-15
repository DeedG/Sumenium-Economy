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
	private static boolean ready;

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

		CommandRegistrationCallback.EVENT.register((dispatcher, registry, selection) -> {
			AccountCommands.register(dispatcher);
			ShopCommands.register(dispatcher);
			FactionCommands.register(dispatcher);
			AdminCommands.register(dispatcher);
		});

		ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			server = s;
			store.load();
			txlog.loadRecent();
			shops.rebuildIndex();
			ready = true;
			exporter.requestExport();
			if (SelfTest.enabled()) SelfTest.run(s);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
			if (!ready) return;
			for (ServerPlayer p : s.getPlayerList().getPlayers()) accounts.player(p).lastSeen = System.currentTimeMillis();
			store.save();
			exporter.exportAll();
			ready = false;
		});

		ServerTickEvents.END_SERVER_TICK.register(s -> {
			if (!ready) return;
			int tick = s.getTickCount();
			if (tick % 20 == 0) Afk.tick(s);
			if (tick % 1200 == 0 && store.isDirty()) store.save();
			if (tick % 6000 == 0) shops.refreshAll();
			if (tick % 100 == 0) exporter.tick();
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
			if (!ready) return;
			ServerPlayer p = handler.player;
			PlayerData d = accounts.player(p);
			factions.syncOnJoin(p, d);
			shops.loginRecap(p);
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
			return shops.onUse(sp, level, hand, hit);
		});
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> {
			if (!ready || !(player instanceof ServerPlayer sp)) return true;
			return shops.beforeBreak(sp, level, pos);
		});
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, be) -> {
			if (!ready || !(player instanceof ServerPlayer sp)) return;
			shops.afterBreak(sp, level, pos);
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

	public static Exporter exporter() {
		return exporter;
	}
}
