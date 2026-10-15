package fr.sumenium.eco.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fr.sumenium.eco.Config;
import fr.sumenium.eco.SavsImport;
import fr.sumenium.eco.SumeniumEco;
import fr.sumenium.eco.shop.Shops;
import fr.sumenium.eco.util.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

import static net.minecraft.commands.Commands.literal;

/** /sumeco : outils d'administration. */
public final class AdminCommands {

	private AdminCommands() {}

	public static void register(CommandDispatcher<CommandSourceStack> d) {
		d.register(literal("sumeco")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(literal("importer-savs")
						.executes(ctx -> importSavs(ctx, true, true, false))
						.then(literal("soldes").executes(ctx -> importSavs(ctx, true, false, false)))
						.then(literal("boutiques").executes(ctx -> importSavs(ctx, false, true, false)))
						.then(literal("forcer").executes(ctx -> importSavs(ctx, true, true, true))))
				.then(literal("recharger").executes(AdminCommands::reload))
				.then(literal("exporter").executes(ctx -> {
					SumeniumEco.store().save();
					SumeniumEco.exporter().exportAll();
					return Cmd.ok(ctx, "Données sauvegardées et fichiers d'export écrits dans sumenium-data/export/.");
				}))
				.then(literal("stats").executes(ctx -> Cmd.ok(ctx, Msg.info("Argent en circulation : ")
						.append(Msg.money(SumeniumEco.accounts().moneySupply()))
						.append(Component.literal(" — " + SumeniumEco.store().shops.size() + " boutiques, "
								+ SumeniumEco.store().factions.size() + " factions").withStyle(ChatFormatting.YELLOW)))))
				.then(literal("zone")
						.then(literal("coin1").executes(ctx -> corner(ctx, 1)))
						.then(literal("coin2").executes(ctx -> corner(ctx, 2)))
						.then(literal("activer").executes(ctx -> zone(ctx, true)))
						.then(literal("desactiver").executes(ctx -> zone(ctx, false)))));
	}

	private static int importSavs(CommandContext<CommandSourceStack> ctx, boolean balances, boolean shops, boolean force) {
		if (SumeniumEco.store().meta.savsImported && !force) {
			return Cmd.err(ctx, "La reprise Savs a déjà été faite. /sumeco importer-savs forcer pour la refaire (les soldes seront écrasés).");
		}
		try {
			List<String> report = SavsImport.run(balances, shops);
			Cmd.ok(ctx, "Reprise Savs terminée :");
			for (String line : report) ctx.getSource().sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
			return 1;
		} catch (Exception e) {
			SumeniumEco.LOGGER.error("Reprise Savs impossible", e);
			return Cmd.err(ctx, "Reprise impossible : " + e.getMessage());
		}
	}

	private static int reload(CommandContext<CommandSourceStack> ctx) {
		SumeniumEco.reloadConfig();
		return Cmd.ok(ctx, "Configuration rechargée.");
	}

	private static int corner(CommandContext<CommandSourceStack> ctx, int n) throws CommandSyntaxException {
		ServerPlayer p = Cmd.player(ctx);
		Config c = SumeniumEco.config();
		BlockPos pos = p.blockPosition();
		c.zoneMonde = Shops.world(p.level());
		if (n == 1) {
			c.zoneX1 = pos.getX();
			c.zoneZ1 = pos.getZ();
		} else {
			c.zoneX2 = pos.getX();
			c.zoneZ2 = pos.getZ();
		}
		SumeniumEco.saveConfig();
		return Cmd.ok(ctx, "Coin " + n + " de la zone commerciale : " + pos.getX() + " " + pos.getZ()
				+ (c.zoneActive ? "" : ". Active-la avec /sumeco zone activer"));
	}

	private static int zone(CommandContext<CommandSourceStack> ctx, boolean on) {
		Config c = SumeniumEco.config();
		c.zoneActive = on;
		SumeniumEco.saveConfig();
		return Cmd.ok(ctx, on ? "Zone commerciale active : de " + c.zoneX1 + " " + c.zoneZ1 + " à " + c.zoneX2 + " " + c.zoneZ2
				: "Zone commerciale désactivée : boutiques possibles partout.");
	}
}
