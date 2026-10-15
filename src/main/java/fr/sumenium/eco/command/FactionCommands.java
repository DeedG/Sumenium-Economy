package fr.sumenium.eco.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fr.sumenium.eco.Accounts;
import fr.sumenium.eco.SumeniumEco;
import fr.sumenium.eco.data.Faction;
import fr.sumenium.eco.data.PlayerData;
import fr.sumenium.eco.data.Tx;
import fr.sumenium.eco.faction.Factions;
import fr.sumenium.eco.util.Money;
import fr.sumenium.eco.util.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** /faction */
public final class FactionCommands {

	private FactionCommands() {}

	public static void register(CommandDispatcher<CommandSourceStack> d) {
		d.register(literal("faction")
				.executes(ctx -> info(ctx, null))
				.then(literal("info").then(argument("faction", StringArgumentType.word()).suggests(Cmd.FACTIONS)
						.executes(ctx -> info(ctx, StringArgumentType.getString(ctx, "faction")))))
				.then(literal("liste").executes(FactionCommands::list))
				.then(literal("creer").then(argument("id", StringArgumentType.word())
						.then(argument("tag", StringArgumentType.word()).executes(FactionCommands::playerCreate))))
				.then(literal("inviter").then(argument("joueur", StringArgumentType.word()).suggests(Cmd.PLAYERS)
						.executes(FactionCommands::invite)))
				.then(literal("rejoindre").then(argument("faction", StringArgumentType.word()).suggests(Cmd.FACTIONS)
						.executes(FactionCommands::join)))
				.then(literal("quitter").executes(FactionCommands::leave))
				.then(literal("exclure").then(argument("joueur", StringArgumentType.word()).suggests(Cmd.PLAYERS)
						.executes(FactionCommands::kick)))
				.then(literal("role").then(argument("joueur", StringArgumentType.word()).suggests(Cmd.PLAYERS)
						.then(argument("role", StringArgumentType.word()).suggests(Cmd.ROLES)
								.executes(ctx -> setRole(ctx, false)))))
				.then(literal("deposer").then(argument("montant", DoubleArgumentType.doubleArg(0.01))
						.executes(FactionCommands::deposit)))
				.then(literal("retirer").then(argument("montant", DoubleArgumentType.doubleArg(0.01))
						.executes(FactionCommands::withdraw)))
				.then(literal("admin").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(literal("creer").then(argument("id", StringArgumentType.word())
								.then(argument("tag", StringArgumentType.word())
										.then(argument("couleur", StringArgumentType.word()).suggests(Cmd.COLORS)
												.executes(FactionCommands::adminCreate)))))
						.then(literal("supprimer").then(argument("faction", StringArgumentType.word()).suggests(Cmd.FACTIONS)
								.executes(FactionCommands::adminDelete)))
						.then(literal("ajouter").then(argument("joueur", StringArgumentType.word()).suggests(Cmd.PLAYERS)
								.then(argument("faction", StringArgumentType.word()).suggests(Cmd.FACTIONS)
										.executes(FactionCommands::adminAdd))))
						.then(literal("retirer").then(argument("joueur", StringArgumentType.word()).suggests(Cmd.PLAYERS)
								.executes(FactionCommands::adminRemove)))
						.then(literal("role").then(argument("joueur", StringArgumentType.word()).suggests(Cmd.PLAYERS)
								.then(argument("role", StringArgumentType.word()).suggests(Cmd.ROLES)
										.executes(ctx -> setRole(ctx, true)))))
						.then(literal("tag").then(argument("faction", StringArgumentType.word()).suggests(Cmd.FACTIONS)
								.then(argument("tag", StringArgumentType.word()).executes(FactionCommands::adminTag))))
						.then(literal("couleur").then(argument("faction", StringArgumentType.word()).suggests(Cmd.FACTIONS)
								.then(argument("couleur", StringArgumentType.word()).suggests(Cmd.COLORS)
										.executes(FactionCommands::adminColor))))
						.then(literal("importer-equipes").executes(FactionCommands::importTeams))));
	}

	private static Factions factions() {
		return SumeniumEco.factions();
	}

	private static Faction mine(CommandContext<CommandSourceStack> ctx, PlayerData d) {
		return SumeniumEco.accounts().factionOf(d);
	}

	private static int info(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
		Faction f;
		if (name == null) {
			PlayerData d = SumeniumEco.accounts().player(Cmd.player(ctx));
			f = mine(ctx, d);
			if (f == null) return Cmd.err(ctx, "Tu joues sans faction. /faction liste pour voir les factions.");
		} else {
			f = SumeniumEco.accounts().faction(name);
			if (f == null) return Cmd.err(ctx, "Faction inconnue.");
		}
		Cmd.ok(ctx, Msg.info("Faction ").append(Component.literal(f.tag).withStyle(Factions.chatColor(f.color)))
				.append(Component.literal(" (" + f.members.size() + "/" + SumeniumEco.config().factionMembresMax + ") — banque ")
						.withStyle(ChatFormatting.YELLOW))
				.append(Msg.money(f.bank)));
		for (var e : f.members.entrySet()) {
			PlayerData m = SumeniumEco.store().players.get(e.getKey());
			MutableComponent line = Component.literal(" • " + (m == null ? e.getKey() : m.name)).withStyle(ChatFormatting.WHITE)
					.append(Component.literal(" " + e.getValue().label()).withStyle(ChatFormatting.GRAY));
			ctx.getSource().sendSuccess(() -> line, false);
		}
		return 1;
	}

	private static int list(CommandContext<CommandSourceStack> ctx) {
		if (SumeniumEco.store().factions.isEmpty()) return Cmd.err(ctx, "Aucune faction.");
		MutableComponent m = Msg.info("Factions : ");
		boolean first = true;
		for (Faction f : SumeniumEco.store().factions.values()) {
			if (!first) m.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
			m.append(Component.literal(f.tag + " (" + f.members.size() + ")").withStyle(Factions.chatColor(f.color)));
			first = false;
		}
		return Cmd.ok(ctx, m);
	}

	private static int playerCreate(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		if (!SumeniumEco.config().factionCreationParJoueurs) {
			return Cmd.err(ctx, "Les factions sont créées par les admins (inscription sur le site).");
		}
		ServerPlayer p = Cmd.player(ctx);
		PlayerData d = SumeniumEco.accounts().player(p);
		if (mine(ctx, d) != null) return Cmd.err(ctx, "Quitte d'abord ta faction actuelle.");
		String id = Accounts.normalizeId(StringArgumentType.getString(ctx, "id"));
		if (SumeniumEco.store().factions.containsKey(id)) return Cmd.err(ctx, "Cette faction existe déjà.");
		Faction f = factions().create(id, StringArgumentType.getString(ctx, "tag"), "white");
		factions().addMember(d, f, Faction.Role.CHEF);
		return Cmd.ok(ctx, "Faction " + f.tag + " créée, tu en es le chef.");
	}

	private static boolean isChef(Faction f, PlayerData d) {
		return f != null && f.role(d.uuid) == Faction.Role.CHEF;
	}

	private static int invite(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = Cmd.player(ctx);
		PlayerData d = SumeniumEco.accounts().player(p);
		Faction f = mine(ctx, d);
		if (!isChef(f, d)) return Cmd.err(ctx, "Seul le chef peut inviter.");
		if (factions().isFull(f)) return Cmd.err(ctx, "La faction est complète.");
		PlayerData t = Cmd.known(ctx, StringArgumentType.getString(ctx, "joueur"));
		if (t == null) return Cmd.err(ctx, "Joueur inconnu.");
		if (f.members.containsKey(t.uuid)) return Cmd.err(ctx, t.name + " est déjà dans la faction.");
		f.invites.add(t.uuid);
		SumeniumEco.store().markDirty();
		ServerPlayer tp = ctx.getSource().getServer().getPlayerList().getPlayer(UUID.fromString(t.uuid));
		if (tp != null) {
			Msg.send(tp, Msg.info(p.getPlainTextName() + " t'invite dans la faction " + f.tag + " : ")
					.append(Msg.command("[rejoindre]", "/faction rejoindre " + f.id)));
		}
		return Cmd.ok(ctx, t.name + " est invité. Il doit taper /faction rejoindre " + f.id);
	}

	private static int join(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = Cmd.player(ctx);
		PlayerData d = SumeniumEco.accounts().player(p);
		Faction f = SumeniumEco.accounts().faction(StringArgumentType.getString(ctx, "faction"));
		if (f == null) return Cmd.err(ctx, "Faction inconnue.");
		if (!f.invites.contains(d.uuid)) return Cmd.err(ctx, "Tu n'as pas été invité dans cette faction.");
		if (factions().isFull(f)) return Cmd.err(ctx, "La faction est complète.");
		if (mine(ctx, d) != null) return Cmd.err(ctx, "Quitte d'abord ta faction actuelle avec /faction quitter.");
		factions().addMember(d, f, Faction.Role.MEMBRE);
		return Cmd.ok(ctx, "Bienvenue dans la faction " + f.tag + " !");
	}

	private static int leave(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = Cmd.player(ctx);
		PlayerData d = SumeniumEco.accounts().player(p);
		Faction f = mine(ctx, d);
		if (f == null) return Cmd.err(ctx, "Tu n'es dans aucune faction.");
		if (isChef(f, d) && f.members.size() > 1
				&& f.members.values().stream().filter(r -> r == Faction.Role.CHEF).count() == 1) {
			return Cmd.err(ctx, "Nomme d'abord un autre chef avec /faction role <joueur> chef.");
		}
		factions().removeMember(d);
		return Cmd.ok(ctx, "Tu as quitté la faction " + f.tag + ". Tu joues maintenant sans faction.");
	}

	private static int kick(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = Cmd.player(ctx);
		PlayerData d = SumeniumEco.accounts().player(p);
		Faction f = mine(ctx, d);
		if (!isChef(f, d)) return Cmd.err(ctx, "Seul le chef peut exclure.");
		PlayerData t = Cmd.known(ctx, StringArgumentType.getString(ctx, "joueur"));
		if (t == null || !f.members.containsKey(t.uuid)) return Cmd.err(ctx, "Ce joueur n'est pas dans ta faction.");
		if (t.uuid.equals(d.uuid)) return Cmd.err(ctx, "Utilise /faction quitter.");
		factions().removeMember(t);
		return Cmd.ok(ctx, t.name + " a été exclu de la faction.");
	}

	private static int setRole(CommandContext<CommandSourceStack> ctx, boolean admin) throws CommandSyntaxException {
		PlayerData t = Cmd.known(ctx, StringArgumentType.getString(ctx, "joueur"));
		Faction.Role role = Cmd.role(StringArgumentType.getString(ctx, "role"));
		if (role == null) return Cmd.err(ctx, "Rôle : membre, tresorier ou chef.");
		if (t == null) return Cmd.err(ctx, "Joueur inconnu.");
		Faction f = SumeniumEco.accounts().factionOf(t);
		if (f == null) return Cmd.err(ctx, t.name + " n'est dans aucune faction.");
		if (!admin) {
			PlayerData d = SumeniumEco.accounts().player(Cmd.player(ctx));
			if (!isChef(f, d) || !f.members.containsKey(d.uuid)) return Cmd.err(ctx, "Seul le chef de la faction peut changer les rôles.");
			if (t.uuid.equals(d.uuid) && role != Faction.Role.CHEF
					&& f.members.values().stream().filter(r -> r == Faction.Role.CHEF).count() == 1) {
				return Cmd.err(ctx, "Nomme d'abord un autre chef.");
			}
		}
		f.members.put(t.uuid, role);
		if (role == Faction.Role.MEMBRE && !SumeniumEco.config().factionMembresPeuventDepenser) t.useFaction = false;
		SumeniumEco.store().markDirty();
		return Cmd.ok(ctx, t.name + " est maintenant " + role.label() + " de " + f.tag + ".");
	}

	private static int deposit(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = Cmd.player(ctx);
		PlayerData d = SumeniumEco.accounts().player(p);
		Faction f = mine(ctx, d);
		if (f == null) return Cmd.err(ctx, "Tu n'es dans aucune faction.");
		long amount = Money.toCents(DoubleArgumentType.getDouble(ctx, "montant"));
		Accounts.Account from = Accounts.Account.of(d);
		if (!from.withdraw(amount)) return Cmd.err(ctx, "Fonds insuffisants (" + Money.format(d.balance) + ").");
		Accounts.Account to = Accounts.Account.of(f);
		to.add(amount);
		log(p, Tx.DEPOT_FACTION, from, to, amount);
		return Cmd.ok(ctx, Msg.ok("Dépôt de ").append(Msg.money(amount))
				.append(Component.literal(" dans la banque " + f.tag + ".").withStyle(ChatFormatting.GREEN)));
	}

	private static int withdraw(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = Cmd.player(ctx);
		PlayerData d = SumeniumEco.accounts().player(p);
		Faction f = mine(ctx, d);
		if (f == null) return Cmd.err(ctx, "Tu n'es dans aucune faction.");
		if (!SumeniumEco.accounts().canSpend(d, f)) return Cmd.err(ctx, "Seuls le chef et les trésoriers peuvent retirer.");
		long amount = Money.toCents(DoubleArgumentType.getDouble(ctx, "montant"));
		Accounts.Account from = Accounts.Account.of(f);
		if (!from.withdraw(amount)) return Cmd.err(ctx, "Fonds insuffisants dans la banque (" + Money.format(f.bank) + ").");
		Accounts.Account to = Accounts.Account.of(d);
		to.add(amount);
		log(p, Tx.RETRAIT_FACTION, from, to, amount);
		return Cmd.ok(ctx, Msg.ok("Retrait de ").append(Msg.money(amount))
				.append(Component.literal(" de la banque " + f.tag + ".").withStyle(ChatFormatting.GREEN)));
	}

	private static void log(ServerPlayer p, String type, Accounts.Account from, Accounts.Account to, long amount) {
		Tx tx = new Tx();
		tx.type = type;
		tx.actor = p.getUUID().toString();
		tx.actorName = p.getPlainTextName();
		tx.from = from.ref();
		tx.fromName = from.label();
		tx.to = to.ref();
		tx.toName = to.label();
		tx.amount = amount;
		SumeniumEco.txlog().add(tx);
	}

	// ------------------------------------------------------------------ admin

	private static int adminCreate(CommandContext<CommandSourceStack> ctx) {
		String id = Accounts.normalizeId(StringArgumentType.getString(ctx, "id"));
		if (SumeniumEco.store().factions.containsKey(id)) return Cmd.err(ctx, "Cette faction existe déjà.");
		String color = StringArgumentType.getString(ctx, "couleur");
		if (Factions.color(color).isEmpty()) return Cmd.err(ctx, "Couleur inconnue.");
		Faction f = factions().create(id, StringArgumentType.getString(ctx, "tag"), color);
		return Cmd.ok(ctx, "Faction " + f.tag + " créée (id " + f.id + "). /faction admin ajouter <joueur> " + f.id);
	}

	private static int adminDelete(CommandContext<CommandSourceStack> ctx) {
		Faction f = SumeniumEco.accounts().faction(StringArgumentType.getString(ctx, "faction"));
		if (f == null) return Cmd.err(ctx, "Faction inconnue.");
		long bank = f.bank;
		PlayerData heir = factions().delete(f);
		return Cmd.ok(ctx, "Faction " + f.tag + " supprimée" + (heir != null && bank > 0
				? ", banque (" + Money.format(bank) + ") versée à " + heir.name : "") + ".");
	}

	private static int adminAdd(CommandContext<CommandSourceStack> ctx) {
		PlayerData t = Cmd.known(ctx, StringArgumentType.getString(ctx, "joueur"));
		Faction f = SumeniumEco.accounts().faction(StringArgumentType.getString(ctx, "faction"));
		if (t == null) return Cmd.err(ctx, "Joueur inconnu (il doit s'être connecté une fois).");
		if (f == null) return Cmd.err(ctx, "Faction inconnue.");
		if (factions().isFull(f)) return Cmd.err(ctx, "La faction est complète (" + SumeniumEco.config().factionMembresMax + ").");
		Faction.Role role = f.members.isEmpty() ? Faction.Role.CHEF : Faction.Role.MEMBRE;
		factions().addMember(t, f, role);
		return Cmd.ok(ctx, t.name + " ajouté à " + f.tag + " (" + role.label() + ").");
	}

	private static int adminRemove(CommandContext<CommandSourceStack> ctx) {
		PlayerData t = Cmd.known(ctx, StringArgumentType.getString(ctx, "joueur"));
		if (t == null) return Cmd.err(ctx, "Joueur inconnu.");
		factions().removeMember(t);
		return Cmd.ok(ctx, t.name + " joue maintenant sans faction.");
	}

	private static int adminTag(CommandContext<CommandSourceStack> ctx) {
		Faction f = SumeniumEco.accounts().faction(StringArgumentType.getString(ctx, "faction"));
		if (f == null) return Cmd.err(ctx, "Faction inconnue.");
		f.tag = StringArgumentType.getString(ctx, "tag");
		factions().applyStyle(f);
		SumeniumEco.store().markDirty();
		return Cmd.ok(ctx, "Tag de " + f.id + " : " + f.tag);
	}

	private static int adminColor(CommandContext<CommandSourceStack> ctx) {
		Faction f = SumeniumEco.accounts().faction(StringArgumentType.getString(ctx, "faction"));
		if (f == null) return Cmd.err(ctx, "Faction inconnue.");
		String color = StringArgumentType.getString(ctx, "couleur");
		if (Factions.color(color).isEmpty()) return Cmd.err(ctx, "Couleur inconnue.");
		f.color = color;
		factions().applyStyle(f);
		SumeniumEco.store().markDirty();
		return Cmd.ok(ctx, "Couleur de " + f.tag + " : " + color);
	}

	private static int importTeams(CommandContext<CommandSourceStack> ctx) {
		List<String> report = factions().importTeams();
		Cmd.ok(ctx, "Import des équipes vanilla :");
		for (String line : report) ctx.getSource().sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
		return Cmd.ok(ctx, "Pense à nommer un chef par faction : /faction admin role <joueur> chef");
	}
}
