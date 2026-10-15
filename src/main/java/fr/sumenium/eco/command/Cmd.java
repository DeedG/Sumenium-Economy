package fr.sumenium.eco.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import fr.sumenium.eco.SumeniumEco;
import fr.sumenium.eco.data.Faction;
import fr.sumenium.eco.data.PlayerData;
import fr.sumenium.eco.util.Msg;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.Set;

/** Outils communs aux commandes. */
final class Cmd {

	private Cmd() {}

	static ServerPlayer player(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		return ctx.getSource().getPlayerOrException();
	}

	static int ok(CommandContext<CommandSourceStack> ctx, String msg) {
		ctx.getSource().sendSuccess(() -> Msg.ok(msg), false);
		return 1;
	}

	static int ok(CommandContext<CommandSourceStack> ctx, Component msg) {
		ctx.getSource().sendSuccess(() -> msg, false);
		return 1;
	}

	static int err(CommandContext<CommandSourceStack> ctx, String msg) {
		ctx.getSource().sendFailure(Msg.err(msg));
		return 0;
	}

	static final SuggestionProvider<CommandSourceStack> PLAYERS = (ctx, b) -> {
		Set<String> names = new HashSet<>();
		for (ServerPlayer p : ctx.getSource().getServer().getPlayerList().getPlayers()) names.add(p.getPlainTextName());
		for (PlayerData d : SumeniumEco.store().players.values()) if (d.name != null) names.add(d.name);
		return SharedSuggestionProvider.suggest(names, b);
	};

	static final SuggestionProvider<CommandSourceStack> FACTIONS = (ctx, b) -> {
		Set<String> names = new HashSet<>();
		for (Faction f : SumeniumEco.store().factions.values()) names.add(f.id);
		return SharedSuggestionProvider.suggest(names, b);
	};

	static final SuggestionProvider<CommandSourceStack> ROLES = (ctx, b) ->
			SharedSuggestionProvider.suggest(new String[] {"membre", "tresorier", "chef"}, b);

	static final SuggestionProvider<CommandSourceStack> COLORS = (ctx, b) ->
			SharedSuggestionProvider.suggest(new String[] {"black", "dark_blue", "dark_green", "dark_aqua", "dark_red",
					"dark_purple", "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"}, b);

	static Faction.Role role(String s) {
		return switch (s.toLowerCase()) {
			case "chef" -> Faction.Role.CHEF;
			case "tresorier", "trésorier" -> Faction.Role.TRESORIER;
			case "membre" -> Faction.Role.MEMBRE;
			default -> null;
		};
	}

	/** Joueur connu (connecté ou déjà venu), par pseudo. */
	static PlayerData known(CommandContext<CommandSourceStack> ctx, String name) {
		ServerPlayer online = ctx.getSource().getServer().getPlayerList().getPlayerByName(name);
		if (online != null) return SumeniumEco.accounts().player(online);
		return SumeniumEco.accounts().findByName(name);
	}
}
