package fr.sumenium.eco.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fr.sumenium.eco.SavsImport;
import fr.sumenium.eco.SumeniumEco;
import fr.sumenium.eco.util.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
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
				.then(literal("bypass").executes(AdminCommands::bypass)));
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

	private static int bypass(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = Cmd.player(ctx);
		boolean on = SumeniumEco.shops().toggleBypass(p);
		return Cmd.ok(ctx, on ? "Mode gestion activé : tu ouvres et casses toutes les boutiques. /sumeco bypass pour l'arrêter."
				: "Mode gestion désactivé : tu utilises les boutiques comme un joueur.");
	}
}
