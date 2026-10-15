package fr.sumenium.eco.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fr.sumenium.eco.SumeniumEco;
import fr.sumenium.eco.data.Listing;
import fr.sumenium.eco.hdv.AuctionHouse;
import fr.sumenium.eco.shop.Directory;
import fr.sumenium.eco.shop.Shops;
import fr.sumenium.eco.util.Money;
import fr.sumenium.eco.util.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.LecternBlock;

import java.util.List;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** /hdv (hôtel des ventes) et /annuaire. */
public final class HdvCommands {

	private HdvCommands() {}

	public static void register(CommandDispatcher<CommandSourceStack> d) {
		d.register(literal("hdv")
				.executes(HdvCommands::help)
				.then(literal("vendre").then(argument("prix", DoubleArgumentType.doubleArg(0.01))
						.executes(ctx -> sell(ctx, false))
						.then(argument("achatImmediat", DoubleArgumentType.doubleArg(0.01)).executes(ctx -> sell(ctx, true)))))
				.then(literal("annonces").executes(HdvCommands::mine))
				.then(literal("annuler").then(argument("numero", StringArgumentType.word()).executes(HdvCommands::cancel)))
				.then(literal("pnj").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.executes(ctx -> {
							SumeniumEco.hdv().spawnNpc(Cmd.player(ctx));
							return Cmd.ok(ctx, "PNJ de l'hôtel des ventes placé ici (invulnérable, immobile).");
						})
						.then(literal("retirer").executes(ctx -> SumeniumEco.hdv().removeNpc(Cmd.player(ctx))
								? Cmd.ok(ctx, "PNJ retiré.") : Cmd.err(ctx, "Aucun PNJ de l'hôtel des ventes à moins de 6 blocs."))))
				.then(literal("boite").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.executes(HdvCommands::addMailbox)
						.then(literal("retirer").executes(HdvCommands::removeMailbox))));

		d.register(literal("annuaire")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(literal("lutrin").executes(HdvCommands::addLectern))
				.then(literal("retirer").executes(HdvCommands::removeLectern)));
	}

	private static int help(CommandContext<CommandSourceStack> ctx) {
		Cmd.ok(ctx, Msg.info("Hôtel des ventes : clic droit sur le PNJ au spawn pour voir les enchères."));
		for (String[] l : new String[][] {
				{"/hdv vendre <prix de départ> [achat immédiat]", "près du PNJ, le stack en main"},
				{"/hdv annonces", "tes annonces en cours"},
				{"/hdv annuler <n°>", "retirer une annonce sans offre"}}) {
			ctx.getSource().sendSuccess(() -> Component.literal(" " + l[0]).withStyle(ChatFormatting.AQUA)
					.append(Component.literal(" : " + l[1]).withStyle(ChatFormatting.GRAY)), false);
		}
		var c = SumeniumEco.config();
		return Cmd.ok(ctx, Msg.info(c.hdvAnnoncesMax + " annonces max, " + c.hdvDureeJours + " jours, taxe "
				+ Math.round(c.hdvTaxe * 100) + " % à la vente. Items gagnés ou invendus : boîte aux lettres du spawn."));
	}

	private static int sell(CommandContext<CommandSourceStack> ctx, boolean withBuyNow) throws CommandSyntaxException {
		ServerPlayer p = Cmd.player(ctx);
		long start = Money.toCents(DoubleArgumentType.getDouble(ctx, "prix"));
		Long buyNow = withBuyNow ? Money.toCents(DoubleArgumentType.getDouble(ctx, "achatImmediat")) : null;
		String error = SumeniumEco.hdv().sell(p, start, buyNow);
		if (error != null) return Cmd.err(ctx, error);
		return Cmd.ok(ctx, "Annonce publiée pour " + SumeniumEco.config().hdvDureeJours + " jours. Tu seras payé à la fin de l'enchère.");
	}

	private static int mine(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = Cmd.player(ctx);
		List<Listing> list = SumeniumEco.hdv().activeOf(p.getUUID().toString());
		if (list.isEmpty()) return Cmd.err(ctx, "Aucune annonce en cours.");
		Cmd.ok(ctx, Msg.info(list.size() + " annonce(s) :"));
		for (Listing l : list) {
			MutableComponent line = Component.literal(" " + l.id + " ").withStyle(ChatFormatting.DARK_GRAY)
					.append(Component.literal(l.count + "× " + l.itemName + " ").withStyle(ChatFormatting.WHITE))
					.append(Component.literal(l.bid > 0 ? Money.format(l.bid) + " (" + l.bidderName + ")" : "départ " + Money.format(l.startPrice))
							.withStyle(ChatFormatting.GOLD))
					.append(Component.literal(" — fin dans " + AuctionHouse.remaining(l.expires) + " ").withStyle(ChatFormatting.GRAY));
			if (l.bid == 0) line.append(Msg.command("[retirer]", "/hdv annuler " + l.id));
			ctx.getSource().sendSuccess(() -> line, false);
		}
		return 1;
	}

	private static int cancel(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String error = SumeniumEco.hdv().cancel(Cmd.player(ctx), StringArgumentType.getString(ctx, "numero"));
		if (error != null) return Cmd.err(ctx, error);
		return Cmd.ok(ctx, "Annonce retirée : l'item t'attend à la boîte aux lettres.");
	}

	private static int addMailbox(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = Cmd.player(ctx);
		BlockPos pos = Shops.target(p);
		if (pos == null || !Shops.isShopBlock(p.level().getBlockState(pos))) return Cmd.err(ctx, "Regarde un coffre ou un tonneau.");
		if (SumeniumEco.shops().find(p.level(), pos) != null) return Cmd.err(ctx, "C'est une boutique.");
		SumeniumEco.hdv().addMailboxBlock(p.level(), pos);
		return Cmd.ok(ctx, "Ce coffre est maintenant une boîte aux lettres : chaque joueur y retrouve ses propres items.");
	}

	private static int removeMailbox(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = Cmd.player(ctx);
		BlockPos pos = Shops.target(p);
		return pos != null && SumeniumEco.hdv().removeMailboxBlock(p.level(), pos)
				? Cmd.ok(ctx, "Boîte aux lettres retirée.") : Cmd.err(ctx, "Regarde une boîte aux lettres.");
	}

	private static int addLectern(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = Cmd.player(ctx);
		BlockPos pos = Shops.target(p);
		if (pos == null || !(p.level().getBlockState(pos).getBlock() instanceof LecternBlock)) return Cmd.err(ctx, "Regarde un lutrin.");
		if (Directory.isLectern(p.level(), pos)) return Cmd.err(ctx, "Ce lutrin a déjà l'annuaire.");
		Directory.add(p.level(), pos);
		return Cmd.ok(ctx, "Annuaire posé : il se met à jour tout seul quand les boutiques changent.");
	}

	private static int removeLectern(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = Cmd.player(ctx);
		BlockPos pos = Shops.target(p);
		return pos != null && Directory.remove(p.level(), pos)
				? Cmd.ok(ctx, "Ce lutrin n'affiche plus l'annuaire.") : Cmd.err(ctx, "Regarde le lutrin de l'annuaire.");
	}
}
