package fr.sumenium.eco;

import fr.sumenium.eco.data.Faction;
import fr.sumenium.eco.data.PlayerData;
import fr.sumenium.eco.util.Items;
import fr.sumenium.eco.util.Money;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Test de démarrage lancé seulement avec -Dsumeco.selftest=true (intégration continue) :
 * force le chargement des classes modifiées par les mixins, vérifie quelques calculs,
 * écrit selftest.txt puis arrête le serveur.
 */
final class SelfTest {

	private SelfTest() {}

	static boolean enabled() {
		return Boolean.getBoolean("sumeco.selftest");
	}

	static void run(MinecraftServer server) {
		List<String> out = new ArrayList<>();
		boolean ok = true;
		String[] classes = {
				"net.minecraft.server.players.SleepStatus",
				"net.minecraft.server.level.ServerPlayer",
				"net.minecraft.world.level.block.entity.HopperBlockEntity",
				"net.minecraft.world.level.ServerExplosion",
				"net.minecraft.world.entity.ai.behavior.TransportItemsBetweenContainers$TransportItemTarget",
		};
		for (String c : classes) {
			try {
				Class.forName(c, true, SelfTest.class.getClassLoader());
				out.add("OK classe " + c);
			} catch (Throwable t) {
				ok = false;
				out.add("ECHEC classe " + c + " : " + t);
			}
		}
		try {
			check(out, Money.plain(272_300).equals("2 723"), "format 2 723 : " + Money.plain(272_300));
			check(out, Money.plain(350).equals("3,5"), "format 3,5 : " + Money.plain(350));
			check(out, Money.toCents(1.5) == 150, "centimes");

			ItemStack stack = new ItemStack(net.minecraft.world.item.Items.DIAMOND_ORE, 3);
			stack.set(DataComponents.CUSTOM_NAME, Component.literal("Test"));
			ItemStack back = Items.decode(Items.encode(stack));
			check(out, Items.same(stack, back) && back.getCount() == 1, "item encode/decode : " + back);

			PlayerData a = SumeniumEco.accounts().player("00000000-0000-0000-0000-000000000001", "TestA");
			Faction f = SumeniumEco.factions().create("SelfTest", "ST", "gold");
			SumeniumEco.factions().addMember(a, f, Faction.Role.CHEF);
			check(out, SumeniumEco.accounts().factionOf(a) == f, "faction membre");
			check(out, server.getScoreboard().getPlayerTeam("SelfTest") != null, "équipe vanilla créée");
			Accounts.Account acc = Accounts.Account.of(f);
			acc.add(10_000);
			check(out, acc.withdraw(2_500) && f.bank == 7_500 && !acc.withdraw(10_000), "banque faction");
			SumeniumEco.factions().delete(f);
			check(out, a.balance == 7_500 && a.faction == null, "suppression faction -> banque au chef");

			long sale = SumeniumEco.shops().dynamic.sell("x", "y", 100, 64);
			check(out, sale == 6_400, "prix dynamiques désactivés par défaut : " + sale);
		} catch (Throwable t) {
			ok = false;
			out.add("ECHEC exception : " + t);
			SumeniumEco.LOGGER.error("Selftest", t);
		}
		for (String line : out) {
			if (line.startsWith("ECHEC")) ok = false;
		}
		out.add(0, ok ? "SELFTEST OK" : "SELFTEST ECHEC");
		try {
			Files.writeString(SumeniumEco.gameDir().resolve("selftest.txt"), String.join("\n", out) + "\n", StandardCharsets.UTF_8);
		} catch (Exception e) {
			SumeniumEco.LOGGER.error("selftest.txt", e);
		}
		for (String line : out) SumeniumEco.LOGGER.info("[selftest] {}", line);
		server.halt(false);
	}

	private static void check(List<String> out, boolean cond, String label) {
		out.add((cond ? "OK " : "ECHEC ") + label);
	}
}
