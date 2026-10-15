package fr.sumenium.eco.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fr.sumenium.eco.Accounts;
import fr.sumenium.eco.SumeniumEco;
import fr.sumenium.eco.data.Faction;
import fr.sumenium.eco.data.PlayerData;
import fr.sumenium.eco.data.Tx;
import fr.sumenium.eco.data.TxLog;
import fr.sumenium.eco.util.Money;
import fr.sumenium.eco.util.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** /compte, /historique et /eco (admin). */
public final class AccountCommands {

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(TxLog.ZONE);
	private static final int PAGE = 10;

	private AccountCommands() {}

	public static void register(CommandDispatcher<CommandSourceStack> d) {
		d.register(literal("compte")
				.executes(AccountCommands::show)
				.then(literal("perso").executes(ctx -> choose(ctx, false)))
				.then(literal("faction").executes(ctx -> choose(ctx, true))));

		d.register(literal("historique")
				.executes(ctx -> history(ctx, 1))
				.then(argument("page", IntegerArgumentType.integer(1)).executes(ctx ->
						history(ctx, IntegerArgumentType.getInteger(ctx, "page")))));

		d.register(literal("baltop")
				.executes(ctx -> baltop(ctx, false, 1))
				.then(argument("page", IntegerArgumentType.integer(1)).executes(ctx ->
						baltop(ctx, false, IntegerArgumentType.getInteger(ctx, "page"))))
				.then(literal("factions")
						.executes(ctx -> baltop(ctx, true, 1))
						.then(argument("page", IntegerArgumentType.integer(1)).executes(ctx ->
								baltop(ctx, true, IntegerArgumentType.getInteger(ctx, "page"))))));

		d.register(literal("eco")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(literal("voir").then(argument("joueur", StringArgumentType.word()).suggests(Cmd.PLAYERS)
						.executes(AccountCommands::adminShow)))
				.then(adminOp("donner"))
				.then(adminOp("retirer"))
				.then(adminOp("definir"))
				.then(literal("faction").then(argument("faction", StringArgumentType.word()).suggests(Cmd.FACTIONS)
						.then(adminFactionOp("donner"))
						.then(adminFactionOp("retirer"))
						.then(adminFactionOp("definir")))));
	}

	private static int show(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = Cmd.player(ctx);
		Accounts acc = SumeniumEco.accounts();
		PlayerData d = acc.player(p);
		Faction f = acc.factionOf(d);
		MutableComponent m = Msg.info("Ton compte : ").append(Msg.money(d.balance));
		if (f != null) {
			m.append(Component.literal(" — banque " + f.tag + " : ").withStyle(ChatFormatting.YELLOW)).append(Msg.money(f.bank));
		}
		Cmd.ok(ctx, m);
		Accounts.Account active = acc.active(p);
		MutableComponent a = Msg.info("Compte utilisé pour les achats et les boutiques : ")
				.append(Component.literal(active.isFaction() ? "banque de faction" : "perso").withStyle(ChatFormatting.WHITE));
		if (f != null) {
			a.append(Component.literal("  "))
					.append(Msg.command("[perso]", "/compte perso"))
					.append(Component.literal(" "))
					.append(Msg.command("[faction]", "/compte faction"));
		}
		return Cmd.ok(ctx, a);
	}

	private static int choose(CommandContext<CommandSourceStack> ctx, boolean faction) throws CommandSyntaxException {
		ServerPlayer p = Cmd.player(ctx);
		Accounts acc = SumeniumEco.accounts();
		PlayerData d = acc.player(p);
		if (faction) {
			Faction f = acc.factionOf(d);
			if (f == null) return Cmd.err(ctx, "Tu n'es dans aucune faction.");
			if (!acc.canSpend(d, f)) return Cmd.err(ctx, "Seuls le chef et les trésoriers peuvent dépenser l'argent de la faction.");
		}
		d.useFaction = faction;
		SumeniumEco.store().markDirty();
		return Cmd.ok(ctx, faction ? "Tes achats et tes boutiques utilisent maintenant la banque de faction."
				: "Tes achats et tes boutiques utilisent maintenant ton compte perso.");
	}

	private static int history(CommandContext<CommandSourceStack> ctx, int page) throws CommandSyntaxException {
		ServerPlayer p = Cmd.player(ctx);
		PlayerData d = SumeniumEco.accounts().player(p);
		Faction f = SumeniumEco.accounts().factionOf(d);
		String me = "joueur:" + d.uuid;
		String fac = f == null ? null : "faction:" + f.id;
		List<Tx> list = SumeniumEco.txlog().find(tx -> me.equals(tx.from) || me.equals(tx.to) || d.uuid.equals(tx.actor)
				|| (fac != null && (fac.equals(tx.from) || fac.equals(tx.to))), page * PAGE);
		int from = (page - 1) * PAGE;
		if (list.size() <= from) return Cmd.err(ctx, page == 1 ? "Aucune transaction récente." : "Pas de page " + page + ".");
		Cmd.ok(ctx, Msg.info("Historique (page " + page + ") — " + SumeniumEco.config().historiqueJours + " derniers jours"));
		for (int i = from; i < Math.min(list.size(), from + PAGE); i++) {
			Tx tx = list.get(i);
			ctx.getSource().sendSuccess(() -> historyLine(tx, me, fac, d.uuid), false);
		}
		if (list.size() > from + PAGE) {
			ctx.getSource().sendSuccess(() -> Msg.prefix().append(Msg.command("Page suivante", "/historique " + (page + 1))), false);
		}
		return 1;
	}

	/** Classement des plus riches : joueurs (compte perso) ou banques de faction, 10 par page. */
	private static int baltop(CommandContext<CommandSourceStack> ctx, boolean factions, int page) {
		record Row(String name, long amount, boolean me) {}
		ServerPlayer viewer = ctx.getSource().getPlayer();
		PlayerData vd = viewer == null ? null : SumeniumEco.accounts().player(viewer);
		Faction vf = vd == null ? null : SumeniumEco.accounts().factionOf(vd);
		java.util.List<Row> rows = new java.util.ArrayList<>();
		if (factions) {
			for (Faction f : SumeniumEco.store().factions.values()) rows.add(new Row(f.tag, f.bank, f == vf));
		} else {
			for (PlayerData p : SumeniumEco.store().players.values()) {
				if (p.name != null) rows.add(new Row(p.name, p.balance, vd != null && p.uuid.equals(vd.uuid)));
			}
		}
		rows.sort((a, b) -> Long.compare(b.amount, a.amount));
		int pages = Math.max(1, (rows.size() + PAGE - 1) / PAGE);
		if (page > pages) return Cmd.err(ctx, "Il n'y a que " + pages + " page(s).");
		long total = rows.stream().mapToLong(Row::amount).sum();
		Cmd.ok(ctx, Msg.info((factions ? "Banques des factions" : "Joueurs les plus riches") + " — page " + page + "/" + pages
				+ " — total ").append(Msg.money(total)));
		for (int i = (page - 1) * PAGE; i < Math.min(rows.size(), page * PAGE); i++) {
			Row r = rows.get(i);
			MutableComponent line = Component.literal(String.format("%2d. ", i + 1)).withStyle(ChatFormatting.GRAY)
					.append(Component.literal(r.name + " ").withStyle(r.me ? ChatFormatting.AQUA : ChatFormatting.WHITE))
					.append(Msg.money(r.amount));
			ctx.getSource().sendSuccess(() -> line, false);
		}
		for (int i = 0; i < rows.size(); i++) {
			if (rows.get(i).me && (i < (page - 1) * PAGE || i >= page * PAGE)) {
				int rank = i + 1;
				ctx.getSource().sendSuccess(() -> Msg.info((factions ? "Ta faction est " : "Tu es ") + rank + "e."), false);
			}
		}
		MutableComponent nav = Msg.prefix();
		if (page > 1) nav.append(Msg.command("« Page précédente", "/baltop " + (factions ? "factions " : "") + (page - 1))).append(Component.literal("  "));
		if (page < pages) nav.append(Msg.command("Page suivante »", "/baltop " + (factions ? "factions " : "") + (page + 1)));
		if (page > 1 || page < pages) ctx.getSource().sendSuccess(() -> nav, false);
		return 1;
	}

	/** Variation d'un compte dans une transaction (le vendeur reçoit le montant moins la taxe). */
	private static long delta(Tx tx, String account) {
		if (account == null) return 0;
		boolean sale = Tx.ACHAT.equals(tx.type) || Tx.ACHAT_SERVICE.equals(tx.type) || Tx.HDV_VENTE.equals(tx.type);
		long d = 0;
		if (account.equals(tx.from)) d -= tx.amount;
		if (account.equals(tx.to)) d += tx.amount - (sale ? tx.tax : 0);
		return d;
	}

	/** Une ligne d'historique vue par le joueur : son compte perso d'abord, puis la banque de sa faction. */
	public static MutableComponent historyLine(Tx tx, String me, String fac, String uuid) {
		boolean sale = Tx.ACHAT.equals(tx.type) || Tx.ACHAT_SERVICE.equals(tx.type);
		boolean seller = sale && (me.equals(tx.to) || (fac != null && fac.equals(tx.to)));
		MutableComponent line = Component.literal(DATE.format(Instant.ofEpochMilli(tx.t)) + " ").withStyle(ChatFormatting.DARK_GRAY)
				.append(Component.literal(label(tx, seller) + " ").withStyle(ChatFormatting.GRAY));
		if (tx.itemName != null && !Tx.DEPOT_FACTION.equals(tx.type) && !Tx.RETRAIT_FACTION.equals(tx.type)) {
			line.append(Component.literal((tx.qty > 0 ? tx.qty + "× " : "") + tx.itemName + " ").withStyle(ChatFormatting.WHITE));
		}
		String other = switch (tx.type) {
			case Tx.ACHAT, Tx.ACHAT_SERVICE -> seller ? tx.actorName : tx.toName;
			case Tx.DEPOT_FACTION, Tx.RETRAIT_FACTION -> uuid.equals(tx.actor) ? null : tx.actorName;
			case Tx.ADMIN -> "par " + tx.actorName;
			case Tx.BON_UTILISE -> tx.note;
			default -> null;
		};
		if (other != null) line.append(Component.literal("(" + other + ") ").withStyle(ChatFormatting.DARK_GRAY));
		long perso = delta(tx, me);
		long bank = delta(tx, fac);
		if (perso != 0) line.append(signed(perso)).append(Component.literal(" "));
		if (bank != 0) {
			line.append(Component.literal("banque ").withStyle(ChatFormatting.DARK_AQUA)).append(signed(bank));
		}
		return line;
	}

	private static MutableComponent signed(long cents) {
		return Component.literal((cents > 0 ? "+" : "") + Money.format(cents))
				.withStyle(cents > 0 ? ChatFormatting.GREEN : ChatFormatting.RED);
	}

	private static String label(Tx tx, boolean seller) {
		if (Tx.ACHAT.equals(tx.type)) return seller ? "Vente" : "Achat";
		if (Tx.ACHAT_SERVICE.equals(tx.type)) return seller ? "Vente service" : "Achat service";
		return label(tx);
	}

	private static String label(Tx tx) {
		return switch (tx.type) {
			case Tx.ACHAT -> "Achat";
			case Tx.ACHAT_SERVICE -> "Service";
			case Tx.RACHAT_FISC -> "Vente au FISC";
			case Tx.ACHAT_FISC -> "Achat au FISC";
			case Tx.FRAIS_BOUTIQUE -> "Création boutique";
			case Tx.REMBOURSEMENT_BOUTIQUE -> "Remboursement boutique";
			case Tx.SUPPRESSION_BOUTIQUE -> "Boutique supprimée";
			case Tx.DEPOT_FACTION -> "Dépôt faction";
			case Tx.RETRAIT_FACTION -> "Retrait faction";
			case Tx.ADMIN -> "Admin";
			case Tx.IMPORT -> "Reprise Savs";
			case Tx.BON_UTILISE -> "Bon validé";
			case Tx.HDV_OFFRE -> "Offre HDV";
			case Tx.HDV_REMBOURSEMENT -> "Offre HDV rendue";
			case Tx.HDV_VENTE -> "Vente HDV";
			default -> tx.type;
		};
	}

	// ------------------------------------------------------------------ admin

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> adminOp(String op) {
		return literal(op).then(argument("joueur", StringArgumentType.word()).suggests(Cmd.PLAYERS)
				.then(argument("montant", DoubleArgumentType.doubleArg(0)).executes(ctx -> {
					PlayerData d = Cmd.known(ctx, StringArgumentType.getString(ctx, "joueur"));
					if (d == null) return Cmd.err(ctx, "Joueur inconnu (il doit s'être connecté au moins une fois).");
					long amount = Money.toCents(DoubleArgumentType.getDouble(ctx, "montant"));
					return apply(ctx, Accounts.Account.of(d), op, amount);
				})));
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> adminFactionOp(String op) {
		return literal(op).then(argument("montant", DoubleArgumentType.doubleArg(0)).executes(ctx -> {
			Faction f = SumeniumEco.accounts().faction(StringArgumentType.getString(ctx, "faction"));
			if (f == null) return Cmd.err(ctx, "Faction inconnue.");
			long amount = Money.toCents(DoubleArgumentType.getDouble(ctx, "montant"));
			return apply(ctx, Accounts.Account.of(f), op, amount);
		}));
	}

	private static int apply(CommandContext<CommandSourceStack> ctx, Accounts.Account a, String op, long amount) {
		long before = a.balance();
		switch (op) {
			case "donner" -> a.add(amount);
			case "retirer" -> a.add(-Math.min(amount, before));
			case "definir" -> a.add(amount - before);
			default -> {
			}
		}
		long delta = a.balance() - before;
		Tx tx = new Tx();
		tx.type = Tx.ADMIN;
		ServerPlayer admin = ctx.getSource().getPlayer();
		if (admin != null) {
			tx.actor = admin.getUUID().toString();
			tx.actorName = admin.getPlainTextName();
		} else {
			tx.actorName = "console";
		}
		if (delta >= 0) {
			tx.to = a.ref();
			tx.toName = a.label();
		} else {
			tx.from = a.ref();
			tx.fromName = a.label();
		}
		tx.amount = Math.abs(delta);
		tx.note = op;
		SumeniumEco.txlog().add(tx);
		return Cmd.ok(ctx, Msg.ok(a.label() + " : ").append(Msg.money(a.balance()))
				.append(Component.literal(" (" + (delta >= 0 ? "+" : "") + Money.format(delta) + ")").withStyle(ChatFormatting.GRAY)));
	}

	private static int adminShow(CommandContext<CommandSourceStack> ctx) {
		PlayerData d = Cmd.known(ctx, StringArgumentType.getString(ctx, "joueur"));
		if (d == null) return Cmd.err(ctx, "Joueur inconnu.");
		Faction f = SumeniumEco.accounts().factionOf(d);
		MutableComponent m = Msg.info(d.name + " : ").append(Msg.money(d.balance));
		if (f != null) m.append(Component.literal(" — faction " + f.tag + " (" + f.role(d.uuid).label() + "), banque ").withStyle(ChatFormatting.YELLOW))
				.append(Msg.money(f.bank));
		return Cmd.ok(ctx, m);
	}
}
