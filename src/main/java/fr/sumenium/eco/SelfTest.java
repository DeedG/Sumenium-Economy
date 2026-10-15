package fr.sumenium.eco;

import com.mojang.authlib.GameProfile;
import fr.sumenium.eco.data.Faction;
import fr.sumenium.eco.data.PlayerData;
import fr.sumenium.eco.data.Shop;
import fr.sumenium.eco.shop.Shops;
import fr.sumenium.eco.util.Items;
import fr.sumenium.eco.util.Money;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

			shopTests(server, out);
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

	/** Scénario complet avec de faux joueurs : boutique, achat, rachat FISC, protections. */
	private static void shopTests(MinecraftServer server, List<String> out) {
		ServerLevel level = server.overworld();
		BlockPos base = new BlockPos(0, 100, 0);
		BlockPos chest = base;
		BlockPos neighbour = base.east(2);
		BlockPos fiscChest = base.west(4);
		for (int x = -6; x <= 6; x++) for (int z = -2; z <= 2; z++) {
			level.setBlock(base.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
		}
		level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
		level.setBlock(neighbour, Blocks.OAK_PLANKS.defaultBlockState(), 3);
		level.setBlock(fiscChest, Blocks.BARREL.defaultBlockState(), 3);

		FakePlayer seller = FakePlayer.get(level, new GameProfile(UUID.fromString("00000000-0000-0000-0000-00000000000a"), "Vendeur"));
		FakePlayer buyer = FakePlayer.get(level, new GameProfile(UUID.fromString("00000000-0000-0000-0000-00000000000b"), "Acheteur"));
		PlayerData ds = SumeniumEco.accounts().player(seller);
		PlayerData db = SumeniumEco.accounts().player(buyer);
		ds.balance = 100_000;
		db.balance = 50_000;

		// Boutique : 16 pains pour 20 Su, frais 150
		seller.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(net.minecraft.world.item.Items.BREAD, 1));
		String err = SumeniumEco.shops().create(seller, chest, Shop.Kind.ITEM, 2_000, 16, null, false);
		check(out, err == null, "création boutique " + (err == null ? "" : err));
		check(out, ds.balance == 100_000 - 15_000, "frais de création 150 : " + ds.balance);
		Container box = Shops.container(level, chest);
		box.setItem(0, new ItemStack(net.minecraft.world.item.Items.BREAD, 40));

		BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(chest), Direction.UP, chest, false);
		InteractionResult r1 = SumeniumEco.shops().onUse(buyer, level, InteractionHand.MAIN_HAND, hit);
		check(out, db.balance == 50_000, "premier clic = info, pas d'achat");
		InteractionResult r2 = SumeniumEco.shops().onUse(buyer, level, InteractionHand.MAIN_HAND, hit);
		int bread = 0;
		for (int i = 0; i < buyer.getInventory().getContainerSize(); i++) {
			ItemStack s = buyer.getInventory().getItem(i);
			if (s.is(net.minecraft.world.item.Items.BREAD)) bread += s.getCount();
		}
		check(out, r1 == InteractionResult.SUCCESS && r2 == InteractionResult.SUCCESS, "clics client interceptés (coffre non ouvert)");
		check(out, db.balance == 48_000 && bread == 16, "achat : acheteur -20 Su, +16 pains (" + db.balance + ", " + bread + ")");
		check(out, ds.balance == 85_000 + 2_000, "achat : vendeur +20 Su (" + ds.balance + ")");
		check(out, Items.count(box, new ItemStack(net.minecraft.world.item.Items.BREAD)) == 24, "stock retiré du coffre");
		check(out, SumeniumEco.shops().onUse(seller, level, InteractionHand.MAIN_HAND, hit) == InteractionResult.PASS,
				"propriétaire : ouverture normale");

		// Protections
		check(out, SumeniumEco.isProtected(level, chest), "coffre protégé");
		check(out, !SumeniumEco.shops().beforeBreak(buyer, level, chest), "un autre joueur ne peut pas casser");
		buyer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(net.minecraft.world.item.Items.CHEST));
		BlockHitResult side = new BlockHitResult(Vec3.atCenterOf(chest.east()), Direction.UP, chest.east().below(), false);
		check(out, SumeniumEco.shops().onUse(buyer, level, InteractionHand.MAIN_HAND, side) == InteractionResult.FAIL,
				"interdit de coller un coffre (double coffre)");
		level.explode(null, chest.getX() + 1.5, chest.getY() + 0.5, chest.getZ() + 0.5, 3f, Level.ExplosionInteraction.TNT);
		check(out, level.getBlockState(chest).is(Blocks.CHEST), "explosion : la boutique reste");
		check(out, !level.getBlockState(neighbour).is(Blocks.OAK_PLANKS), "explosion a bien cassé le bloc voisin");

		// Rachat FISC : 10 minerais de diamant à 70
		seller.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(net.minecraft.world.item.Items.DIAMOND_ORE, 1));
		err = SumeniumEco.shops().create(seller, fiscChest, Shop.Kind.FISC_RACHAT, 7_000, 1, null, true);
		check(out, err == null, "boutique FISC rachat " + (err == null ? "" : err));
		buyer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(net.minecraft.world.item.Items.DIAMOND_ORE, 10));
		long before = db.balance;
		SumeniumEco.shops().onUse(buyer, level, InteractionHand.MAIN_HAND,
				new BlockHitResult(Vec3.atCenterOf(fiscChest), Direction.UP, fiscChest, false));
		check(out, db.balance - before == 70_000 && buyer.getMainHandItem().isEmpty(),
				"rachat FISC 10 × 70 = 700 (" + (db.balance - before) + ")");

		// Suppression par le propriétaire : remboursement 50 %
		long b2 = ds.balance;
		SumeniumEco.shops().remove(SumeniumEco.shops().at(level, chest), seller, true);
		check(out, ds.balance - b2 == 7_500 && !SumeniumEco.isProtected(level, chest), "suppression : 75 remboursés");
		check(out, SumeniumEco.txlog().find(tx -> true, 100).size() >= 4, "journal rempli");
		SumeniumEco.exporter().exportAll();
		check(out, Files.exists(SumeniumEco.gameDir().resolve("sumenium-data/export/boutiques.json")), "export écrit");
	}

	private static void check(List<String> out, boolean cond, String label) {
		out.add((cond ? "OK " : "ECHEC ") + label);
	}
}
