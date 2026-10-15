package fr.sumenium.eco.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fr.sumenium.eco.SumeniumEco;
import fr.sumenium.eco.data.PlayerData;
import fr.sumenium.eco.data.Shop;
import fr.sumenium.eco.data.Tx;
import fr.sumenium.eco.data.Voucher;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.List;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** /boutique, /bon et /fisc (admin). */
public final class ShopCommands {

	private ShopCommands() {}

	public static void register(CommandDispatcher<CommandSourceStack> d) {
		d.register(literal("boutique")
				.executes(ShopCommands::help)
				.then(literal("aide").executes(ShopCommands::help))
				.then(literal("creer")
						.then(literal("item")
								.then(argument("prix", DoubleArgumentType.doubleArg(0.01))
										.then(argument("quantite", IntegerArgumentType.integer(1, 3456))
												.executes(ctx -> create(ctx, Shop.Kind.ITEM)))))
						.then(literal("service")
								.then(argument("prix", DoubleArgumentType.doubleArg(0.01))
										.then(argument("nom", StringArgumentType.greedyString())
												.executes(ctx -> create(ctx, Shop.Kind.SERVICE))))))
				.then(literal("info").executes(ShopCommands::info))
				.then(literal("liste").executes(ShopCommands::list))
				.then(literal("supprimer").executes(ShopCommands::delete))
				.then(literal("panneau").executes(ShopCommands::sign))
				.then(literal("faction").executes(ctx -> transfer(ctx, true)))
				.then(literal("perso").executes(ctx -> transfer(ctx, false)))
				.then(literal("prix")
						.then(argument("prix", DoubleArgumentType.doubleArg(0.01))
								.executes(ctx -> setPrice(ctx, -1))
								.then(argument("quantite", IntegerArgumentType.integer(1, 3456))
										.executes(ctx -> setPrice(ctx, IntegerArgumentType.getInteger(ctx, "quantite")))))));

		d.register(literal("bon")
				.then(literal("verifier").executes(ShopCommands::checkVoucher))
				.then(literal("valider").executes(ShopCommands::useVoucher)));

		d.register(literal("fisc")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(literal("rachat").then(argument("prixUnitaire", DoubleArgumentType.doubleArg(0))
						.executes(ctx -> adminCreate(ctx, Shop.Kind.FISC_RACHAT,
								DoubleArgumentType.getDouble(ctx, "prixUnitaire"), 1))))
				.then(literal("vente").then(argument("prix", DoubleArgumentType.doubleArg(0))
						.then(argument("quantite", IntegerArgumentType.integer(1, 3456))
								.executes(ctx -> adminCreate(ctx, Shop.Kind.FISC_VENTE,
										DoubleArgumentType.getDouble(ctx, "prix"), IntegerArgumentType.getInteger(ctx, "quantite"))))))
				.then(literal("prix").then(argument("prix", DoubleArgumentType.doubleArg(0))
						.executes(ctx -> setPrice(ctx, -1)))));
	}

	private static int create(CommandContext<CommandSourceStack> ctx, Shop.Kind kind) throws CommandSyntaxException {
		ServerPlayer p = Cmd.player(ctx);
		long price = Money.toCents(DoubleArgumentType.getDouble(ctx, "prix"));
		int qty = kind == Shop.Kind.ITEM ? IntegerArgumentType.getInteger(ctx, "quantite") : 1;
		String name = kind == Shop.Kind.SERVICE ? StringArgumentType.getString(ctx, "nom").trim() : null;
		if (name != null && (name.isEmpty() || name.length() > 48)) return Cmd.err(ctx, "Nom du bon : 1 à 48 caractères.");
		String error = SumeniumEco.shops().create(p, kind, price, qty, name, false);
		if (error != null) return Cmd.err(ctx, error);
		Shop s = SumeniumEco.shops().find(p.level(), Shops.target(p));
		Cmd.ok(ctx, SumeniumEco.shops().describe(s, true));
		return Cmd.ok(ctx, "Boutique créée (" + Money.format(s.feePaid) + " de frais, payés par "
				+ (s.ownerType == Shop.OwnerType.FACTION ? "la banque de faction" : "ton compte") + ").");
	}

	private static int adminCreate(CommandContext<CommandSourceStack> ctx, Shop.Kind kind, double price, int qty)
			throws CommandSyntaxException {
		ServerPlayer p = Cmd.player(ctx);
		String error = SumeniumEco.shops().create(p, kind, Money.toCents(price), qty, null, true);
		if (error != null) return Cmd.err(ctx, error);
		Shop s = SumeniumEco.shops().find(p.level(), Shops.target(p));
		return Cmd.ok(ctx, SumeniumEco.shops().describe(s, false));
	}

	private static Shop targeted(CommandContext<CommandSourceStack> ctx, ServerPlayer p) {
		BlockPos pos = Shops.target(p);
		return pos == null ? null : SumeniumEco.shops().find(p.level(), pos);
	}

	private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = Cmd.player(ctx);
		Shop s = targeted(ctx, p);
		if (s == null) return Cmd.err(ctx, "Regarde une boutique.");
		Cmd.ok(ctx, SumeniumEco.shops().describe(s, true));
		if (SumeniumEco.shops().canManage(p, s)) {
			Cmd.ok(ctx, Msg.info(s.salesCount + " vente(s), " + s.itemsMoved + " item(s), chiffre d'affaires ")
					.append(Msg.money(s.revenue)));
		}
		return 1;
	}

	private static int list(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = Cmd.player(ctx);
		PlayerData d = SumeniumEco.accounts().player(p);
		List<Shop> shops = SumeniumEco.shops().ownedBy(d);
		if (shops.isEmpty()) return Cmd.err(ctx, "Tu n'as aucune boutique.");
		Cmd.ok(ctx, Msg.info(shops.size() + " boutique(s) :"));
		for (Shop s : shops) {
			int stock = SumeniumEco.shops().refreshStock(s);
			boolean empty = s.kind == Shop.Kind.ITEM && stock >= 0 && stock < s.quantity;
			MutableComponent line = Component.literal(" • ").withStyle(ChatFormatting.GRAY)
					.append(Component.literal(s.kind == Shop.Kind.SERVICE ? "« " + s.serviceName + " »" : s.quantity + "× " + s.itemName)
							.withStyle(ChatFormatting.WHITE))
					.append(Component.literal(" " + Money.format(s.price)).withStyle(ChatFormatting.GOLD))
					.append(Component.literal(" — " + s.x + " " + s.y + " " + s.z).withStyle(ChatFormatting.GRAY))
					.append(Component.literal(s.kind == Shop.Kind.ITEM ? (stock < 0 ? " (stock ?)" : " (stock " + stock + ")") : "")
							.withStyle(empty ? ChatFormatting.RED : ChatFormatting.GRAY))
					.append(Component.literal(s.ownerType == Shop.OwnerType.FACTION ? " [faction]" : "").withStyle(ChatFormatting.DARK_AQUA));
			ctx.getSource().sendSuccess(() -> line, false);
		}
		return 1;
	}

	private static int delete(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = Cmd.player(ctx);
		Shop s = targeted(ctx, p);
		if (s == null) return Cmd.err(ctx, "Regarde une boutique.");
		if (!SumeniumEco.shops().canManage(p, s)) return Cmd.err(ctx, "Ce n'est pas ta boutique.");
		SumeniumEco.shops().remove(s, p, true);
		return 1;
	}

	private static int help(CommandContext<CommandSourceStack> ctx) {
		String[][] lines = {
				{"/boutique creer item <prix> <quantité>", "créer (item en main, regarder le coffre)"},
				{"/boutique creer service <prix> <nom>", "créer un service (bons)"},
				{"/boutique info", "offre, stock, ventes"},
				{"/boutique liste", "tes boutiques et celles de ta faction"},
				{"/boutique prix <prix> [quantité]", "changer le prix"},
				{"/boutique panneau", "réécrire ou poser le panneau"},
				{"/boutique faction | perso", "donner à la faction / reprendre"},
				{"/boutique supprimer", "supprimer (50 % remboursés)"},
				{"/compte · /historique · /baltop", "argent, historique, classement"},
		};
		Cmd.ok(ctx, Msg.info("Boutiques : clic sur le coffre ou le panneau, puis la quantité dans le chat."));
		for (String[] l : lines) {
			ctx.getSource().sendSuccess(() -> Component.literal(" " + l[0]).withStyle(s -> s.withColor(ChatFormatting.AQUA)
					.withClickEvent(new net.minecraft.network.chat.ClickEvent.SuggestCommand(l[0].split(" <")[0].split(" \\[")[0] + " ")))
					.append(Component.literal(" : " + l[1]).withStyle(ChatFormatting.GRAY)), false);
		}
		return 1;
	}

	private static int sign(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = Cmd.player(ctx);
		Shop s = targeted(ctx, p);
		if (s == null) return Cmd.err(ctx, "Regarde le coffre d'une boutique.");
		if (!SumeniumEco.shops().canManage(p, s)) return Cmd.err(ctx, "Ce n'est pas ta boutique.");
		if (!SumeniumEco.shops().ensureSign(s)) return Cmd.err(ctx, "Pas de place autour du coffre pour un panneau.");
		return Cmd.ok(ctx, "Panneau de la boutique à jour en " + s.signX + " " + s.signY + " " + s.signZ + ".");
	}

	private static int transfer(CommandContext<CommandSourceStack> ctx, boolean toFaction) throws CommandSyntaxException {
		ServerPlayer p = Cmd.player(ctx);
		Shop s = targeted(ctx, p);
		if (s == null) return Cmd.err(ctx, "Regarde le coffre d'une boutique.");
		String error = SumeniumEco.shops().transfer(p, s, toFaction);
		if (error != null) return Cmd.err(ctx, error);
		return Cmd.ok(ctx, toFaction ? "La boutique appartient maintenant à la faction : tous les membres peuvent la ravitailler et les ventes vont dans la banque."
				: "La boutique est maintenant à toi : les ventes vont sur ton compte perso.");
	}

	private static int setPrice(CommandContext<CommandSourceStack> ctx, int qty) throws CommandSyntaxException {
		ServerPlayer p = Cmd.player(ctx);
		Shop s = targeted(ctx, p);
		if (s == null) return Cmd.err(ctx, "Regarde une boutique.");
		if (!SumeniumEco.shops().canManage(p, s)) return Cmd.err(ctx, "Ce n'est pas ta boutique.");
		if (s.isFisc() && !Shops.isAdmin(p)) return Cmd.err(ctx, "Réservé aux admins.");
		s.price = Money.toCents(DoubleArgumentType.getDouble(ctx, "prix"));
		if (qty > 0 && s.kind == Shop.Kind.ITEM) s.quantity = qty;
		SumeniumEco.store().markDirty();
		SumeniumEco.exporter().requestExport();
		return Cmd.ok(ctx, SumeniumEco.shops().describe(s, false));
	}

	private static int checkVoucher(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = Cmd.player(ctx);
		String id = Shops.voucherId(p.getMainHandItem());
		if (id == null) return Cmd.err(ctx, "Ce n'est pas un bon de service authentique.");
		Voucher v = SumeniumEco.store().vouchers.get(id);
		if (v == null) return Cmd.err(ctx, "Bon n° " + id + " inconnu du FISC.");
		String status = v.usedBy == null ? "valable" : "déjà utilisé (validé par " + v.usedBy + ")";
		return Cmd.ok(ctx, Msg.info("Bon n° " + v.id + " « " + v.serviceName + " » vendu par " + v.sellerName
				+ " à " + v.buyerName + " pour " + Money.format(v.price) + " : " + status + "."));
	}

	private static int useVoucher(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = Cmd.player(ctx);
		ItemStack held = p.getMainHandItem();
		String id = Shops.voucherId(held);
		if (id == null) return Cmd.err(ctx, "Prends en main le bon à valider.");
		Voucher v = SumeniumEco.store().vouchers.get(id);
		if (v == null) return Cmd.err(ctx, "Bon inconnu du FISC.");
		if (v.usedBy != null) return Cmd.err(ctx, "Ce bon a déjà été utilisé.");
		Shop shop = null;
		for (Shop s : SumeniumEco.store().shops.values()) if (s.id.equals(v.shopId)) shop = s;
		if (shop != null && !SumeniumEco.shops().canManage(p, shop)) {
			return Cmd.err(ctx, "Seul le vendeur du service peut valider ce bon.");
		}
		v.usedBy = p.getPlainTextName();
		v.usedAt = System.currentTimeMillis();
		held.shrink(1);
		if (held.isEmpty()) p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
		SumeniumEco.store().markDirty();
		Tx tx = new Tx();
		tx.type = Tx.BON_UTILISE;
		tx.actor = p.getUUID().toString();
		tx.actorName = p.getPlainTextName();
		tx.shop = v.shopId;
		tx.itemName = v.serviceName;
		tx.note = "bon " + v.id + " acheté par " + v.buyerName;
		SumeniumEco.txlog().add(tx);
		return Cmd.ok(ctx, "Bon « " + v.serviceName + " » de " + v.buyerName + " validé.");
	}
}
