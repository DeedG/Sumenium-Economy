package fr.sumenium.eco.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import fr.sumenium.eco.SumeniumEco;
import fr.sumenium.eco.heads.HeadCatalog;
import fr.sumenium.eco.util.Money;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.time.Instant;

import static net.minecraft.commands.Commands.literal;

/** /tetes : marchand de têtes (admins). Les joueurs passent par le PNJ. */
public final class HeadCommands {

	private HeadCommands() {}

	public static void register(CommandDispatcher<CommandSourceStack> d) {
		d.register(literal("tetes")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(HeadCommands::status)
				.then(literal("pnj")
						.executes(ctx -> {
							SumeniumEco.heads().spawnNpc(Cmd.player(ctx));
							return Cmd.ok(ctx, "Marchand de têtes placé ici (invulnérable, immobile).");
						})
						.then(literal("retirer").executes(ctx -> SumeniumEco.heads().removeNpc(Cmd.player(ctx))
								? Cmd.ok(ctx, "Marchand de têtes retiré.") : Cmd.err(ctx, "Aucun marchand de têtes à moins de 6 blocs."))))
				.then(literal("ouvrir").executes(ctx -> {
					SumeniumEco.heads().openCategories(Cmd.player(ctx), 0);
					return 1;
				}))
				.then(literal("maj").executes(ctx -> {
					var src = ctx.getSource();
					var server = src.getServer();
					SumeniumEco.heads().catalog().refreshAsync(() -> server.execute(() -> {
						String err = SumeniumEco.heads().catalog().lastError();
						src.sendSuccess(() -> Component.literal(err == null
								? "Catalogue de têtes à jour : " + SumeniumEco.heads().catalog().snapshot().count() + " têtes."
								: "Mise à jour impossible : " + err), true);
					}));
					return Cmd.ok(ctx, "Mise à jour du catalogue lancée…");
				})));
	}

	private static int status(CommandContext<CommandSourceStack> ctx) {
		HeadCatalog c = SumeniumEco.heads().catalog();
		HeadCatalog.Snapshot s = c.snapshot();
		Cmd.ok(ctx, "Marchand de têtes : " + s.count() + " têtes, " + s.categories().size() + " catégories, "
				+ Money.format(SumeniumEco.heads().price()) + " la tête, " + c.excludedCount() + " écartées (AllMobHeads)"
				+ (s.loadedAt() > 0 ? " (chargé le " + Instant.ofEpochMilli(s.loadedAt()) + ")" : ""));
		if (c.lastError() != null) Cmd.err(ctx, "Dernière mise à jour en échec : " + c.lastError());
		return Cmd.ok(ctx, "/tetes pnj [retirer] · /tetes ouvrir (test) · /tetes maj");
	}
}
