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
				"net.minecraft.world.level.block.entity.ChestBlockEntity",
				"net.minecraft.world.CompoundContainer",
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
		seller.setPos(0.5, 100, 3.5);
		buyer.setPos(1.5, 100, 3.5);

		// Panneau posé sur la face sud du coffre, avant la création de la boutique
		BlockPos signPos = chest.south();
		level.setBlock(signPos, Blocks.OAK_WALL_SIGN.defaultBlockState()
				.setValue(net.minecraft.world.level.block.WallSignBlock.FACING, Direction.SOUTH), 3);

		// Boutique : 16 pains pour 20 Su, frais 150
		seller.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(net.minecraft.world.item.Items.BREAD, 1));
		String err = SumeniumEco.shops().create(seller, chest, Shop.Kind.ITEM, 2_000, 16, null, false);
		check(out, err == null, "création boutique " + (err == null ? "" : err));
		check(out, ds.balance == 100_000 - 15_000, "frais de création 150 : " + ds.balance);
		Container box = Shops.container(level, chest);
		box.setItem(0, new ItemStack(net.minecraft.world.item.Items.BREAD, 40));

		Shop shop = SumeniumEco.shops().at(level, chest);
		SumeniumEco.shops().refreshStock(shop); // le vendeur vient de remplir le coffre
		check(out, shop.hasSign() && shop.signZ == signPos.getZ(), "panneau rattaché automatiquement");
		var signBe = (net.minecraft.world.level.block.entity.SignBlockEntity) level.getBlockEntity(signPos);
		var lines = signBe.getText(net.minecraft.world.level.block.entity.SignTextSlot.FRONT).getMessages(false);
		check(out, signBe.isWaxed() && lines.get(0).getString().equals("Vendeur") && lines.get(1).getString().equals("Bread")
				&& lines.get(3).getString().equals("Stock : 40"),
				"panneau écrit et ciré : " + lines.get(0).getString() + " / " + lines.get(1).getString() + " / "
						+ lines.get(2).getString() + " / " + lines.get(3).getString());

		BlockPos wire = chest.west();
		level.setBlock(wire, Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
		BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(chest), Direction.UP, chest, false);
		InteractionResult r1 = SumeniumEco.shops().onUse(buyer, level, InteractionHand.MAIN_HAND, hit);
		check(out, r1 == InteractionResult.SUCCESS && db.balance == 50_000, "clic = info, coffre non ouvert, pas d'achat");
		check(out, !SumeniumEco.shops().onChat(buyer, "salut tout le monde"), "un message normal passe dans le chat");
		SumeniumEco.shops().onUse(buyer, level, InteractionHand.MAIN_HAND, hit);
		boolean consumed = SumeniumEco.shops().onChat(buyer, "32");
		int bread = 0;
		for (int i = 0; i < buyer.getInventory().getContainerSize(); i++) {
			ItemStack s = buyer.getInventory().getItem(i);
			if (s.is(net.minecraft.world.item.Items.BREAD)) bread += s.getCount();
		}
		check(out, consumed, "quantité tapée dans le chat consommée (pas envoyée aux autres)");
		check(out, db.balance == 46_000 && bread == 32, "achat de 32 : acheteur -40 Su, +32 pains (" + db.balance + ", " + bread + ")");
		check(out, ds.balance == 85_000 + 4_000, "achat : vendeur +40 Su (" + ds.balance + ")");
		int power = level.getBlockState(wire).getValue(net.minecraft.world.level.block.RedstoneWireBlock.POWER);
		check(out, power > 0 && fr.sumenium.eco.shop.Redstone.isPowered(level, chest), "signal de redstone après la vente (poudre à " + power + ")");
		check(out, Items.count(box, new ItemStack(net.minecraft.world.item.Items.BREAD)) == 8, "stock retiré du coffre");
		lines = signBe.getText(net.minecraft.world.level.block.entity.SignTextSlot.FRONT).getMessages(false);
		check(out, lines.get(3).getString().equals("Rupture"), "panneau mis à jour : " + lines.get(3).getString());
		SumeniumEco.shops().onUse(buyer, level, InteractionHand.MAIN_HAND,
				new BlockHitResult(Vec3.atCenterOf(signPos), Direction.NORTH, signPos, false));
		check(out, SumeniumEco.shops().onChat(buyer, "annuler") && db.balance == 46_000, "clic sur le panneau + annuler");
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
		check(out, level.getBlockState(signPos).is(Blocks.OAK_WALL_SIGN), "explosion : le panneau reste");
		check(out, !SumeniumEco.shops().beforeBreak(buyer, level, signPos), "un autre joueur ne peut pas casser le panneau");
		check(out, !level.getBlockState(neighbour).is(Blocks.OAK_PLANKS), "explosion a bien cassé le bloc voisin");

		// Rachat FISC : 10 minerais de diamant à 70
		seller.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(net.minecraft.world.item.Items.DIAMOND_ORE, 1));
		err = SumeniumEco.shops().create(seller, fiscChest, Shop.Kind.FISC_RACHAT, 7_000, 1, null, true);
		check(out, err == null, "boutique FISC rachat " + (err == null ? "" : err));
		buyer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(net.minecraft.world.item.Items.DIAMOND_ORE, 10));
		long before = db.balance;
		BlockHitResult fiscHit = new BlockHitResult(Vec3.atCenterOf(fiscChest), Direction.UP, fiscChest, false);
		SumeniumEco.shops().onUse(buyer, level, InteractionHand.MAIN_HAND, fiscHit);
		SumeniumEco.shops().onChat(buyer, "4");
		check(out, db.balance - before == 28_000 && buyer.getMainHandItem().getCount() == 6,
				"rachat FISC 4 × 70 = 280 (" + (db.balance - before) + ")");
		SumeniumEco.shops().onUse(buyer, level, InteractionHand.MAIN_HAND, fiscHit);
		SumeniumEco.shops().onChat(buyer, "tout");
		check(out, db.balance - before == 70_000 && buyer.getMainHandItem().isEmpty(),
				"rachat FISC « tout » : total 700 (" + (db.balance - before) + ")");

		// Frais par famille : planches de chêne 150, puis planches de bouleau 50
		BlockPos w1 = base.offset(-2, 0, -2), w2 = base.offset(2, 0, -2);
		level.setBlock(w1, Blocks.BARREL.defaultBlockState(), 3);
		level.setBlock(w2, Blocks.BARREL.defaultBlockState(), 3);
		seller.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse("minecraft:oak_planks")), 1));
		long b0 = ds.balance;
		SumeniumEco.shops().create(seller, w1, Shop.Kind.ITEM, 100, 1, null, false);
		long fee1 = b0 - ds.balance;
		seller.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse("minecraft:birch_planks")), 1));
		SumeniumEco.shops().create(seller, w2, Shop.Kind.ITEM, 100, 1, null, false);
		long fee2 = b0 - fee1 - ds.balance;
		check(out, fee1 == 15_000 && fee2 == 5_000, "frais par famille : planches de chêne " + fee1 + ", planches de bouleau " + fee2);
		check(out, fr.sumenium.eco.shop.Family.of("minecraft:stripped_dark_oak_log").equals("stripped_log")
				&& fr.sumenium.eco.shop.Family.of("minecraft:light_gray_concrete").equals("concrete"), "familles bois et couleurs");

		// Import des équipes : on ignore les équipes de datapacks et les noms inconnus
		var sb = server.getScoreboard();
		sb.addPlayerToTeam("Fictif", sb.addPlayerTeam("bac_team_test"));
		sb.addPlayerToTeam("Inconnu", sb.addPlayerTeam("EquipeVide"));
		sb.addPlayerToTeam("Vendeur", sb.addPlayerTeam("EquipeTest"));
		SumeniumEco.factions().importTeams();
		check(out, !SumeniumEco.store().factions.containsKey("bac_team_test") && !SumeniumEco.store().factions.containsKey("EquipeVide")
				&& SumeniumEco.store().factions.containsKey("EquipeTest"), "import des équipes : seulement les vraies");

		// Panneau posé automatiquement quand il n'y en a pas
		Shop planks = SumeniumEco.shops().at(level, w1);
		check(out, planks.hasSign() && level.getBlockState(new BlockPos(planks.signX, planks.signY, planks.signZ))
				.getBlock() instanceof net.minecraft.world.level.block.SignBlock, "panneau posé automatiquement à la création");

		// Boutique donnée à la faction, puis service acheté par un membre avec son compte perso (via le panneau)
		String terr = SumeniumEco.shops().transfer(seller, planks, true);
		check(out, terr == null && planks.ownerType == Shop.OwnerType.FACTION && planks.ownerId.equals("EquipeTest"),
				"boutique perso donnée à la faction " + (terr == null ? "" : terr));
		BlockPos w3 = base.offset(4, 0, -2);
		level.setBlock(w3, Blocks.BARREL.defaultBlockState(), 3);
		SumeniumEco.shops().create(seller, w3, Shop.Kind.SERVICE, 30_000, 1, "Usine XP 30 min", false);
		Shop service = SumeniumEco.shops().at(level, w3);
		SumeniumEco.shops().transfer(seller, service, true);
		Faction team = SumeniumEco.store().factions.get("EquipeTest");
		long bankBefore = team.bank;
		long sellerBefore = ds.balance;
		BlockPos ss = new BlockPos(service.signX, service.signY, service.signZ);
		SumeniumEco.shops().onUse(seller, level, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(ss), Direction.UP, ss, false));
		SumeniumEco.shops().onChat(seller, "1");
		boolean voucher = false;
		for (int i = 0; i < seller.getInventory().getContainerSize(); i++) {
			if (Shops.voucherId(seller.getInventory().getItem(i)) != null) voucher = true;
		}
		check(out, voucher && ds.balance == sellerBefore - 30_000 && team.bank == bankBefore + 30_000,
				"bon de service acheté par un membre (perso -> banque faction)");

		// Dépôt de service : le bon valide est consommé et le dépôt émet un signal
		BlockPos w4 = base.offset(4, 0, 2);
		level.setBlock(w4, Blocks.BARREL.defaultBlockState(), 3);
		String derr = SumeniumEco.shops().createDeposit(seller, w4, "usine xp 30 min");
		ItemStack bon = ItemStack.EMPTY;
		for (int i = 0; i < seller.getInventory().getContainerSize(); i++) {
			if (Shops.voucherId(seller.getInventory().getItem(i)) != null) {
				bon = seller.getInventory().getItem(i).copy();
				seller.getInventory().setItem(i, ItemStack.EMPTY);
			}
		}
		Container depot = Shops.container(level, w4);
		String bonId = Shops.voucherId(bon);
		depot.setItem(0, bon.copy());
		var depo = SumeniumEco.shops().depositAt(level, w4);
		boolean accepted = depo != null && SumeniumEco.shops().checkDeposit(depo, seller);
		check(out, derr == null && accepted && depot.getItem(0).isEmpty()
				&& fr.sumenium.eco.shop.Redstone.isPowered(level, w4)
				&& bonId != null && SumeniumEco.store().vouchers.get(bonId).usedBy != null,
				"dépôt : bon accepté, consommé, signal émis " + (derr == null ? "" : derr));
		depot.setItem(0, bon.copy());
		check(out, !SumeniumEco.shops().checkDeposit(depo, seller) && !depot.getItem(0).isEmpty(), "dépôt : bon déjà utilisé refusé et laissé");
		check(out, depo != null && depo.signX != null && level.getBlockState(new BlockPos(depo.signX, depo.signY, depo.signZ))
				.getBlock() instanceof net.minecraft.world.level.block.SignBlock, "dépôt : panneau explicatif posé");
		// vrai parcours joueur : clic sur le dépôt, bon déposé, fermeture détectée par la tâche régulière
		SumeniumEco.shops().onUse(seller, level, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(w4), Direction.UP, w4, false));
		SumeniumEco.shops().onChat(seller, "x"); // rien en attente côté chat
		ds.balance += 100_000;
		seller.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(net.minecraft.world.item.Items.TORCH, 1));
		SumeniumEco.shops().onUse(seller, level, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(ss), Direction.UP, ss, false));
		SumeniumEco.shops().onChat(seller, "1");
		ItemStack bon2 = ItemStack.EMPTY;
		for (int i = 0; i < seller.getInventory().getContainerSize(); i++) {
			if (Shops.voucherId(seller.getInventory().getItem(i)) != null) {
				bon2 = seller.getInventory().getItem(i).copy();
				seller.getInventory().setItem(i, ItemStack.EMPTY);
			}
		}
		depot.setItem(0, ItemStack.EMPTY);
		depot.setItem(1, bon2.copy());
		SumeniumEco.shops().onUse(seller, level, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(w4), Direction.UP, w4, false));
		SumeniumEco.shops().tickWatchers(server);
		check(out, !bon2.isEmpty() && depot.getItem(1).isEmpty(), "dépôt : bon consommé à la fermeture (parcours complet) bon2=" + bon2 + " slot1=" + depot.getItem(1)
						+ " solde=" + ds.balance);

		// Fin des signaux : on simule le passage du temps (plusieurs impulsions éteintes d'un coup)
		try {
			java.lang.reflect.Field f = net.minecraft.server.MinecraftServer.class.getDeclaredField("tickCount");
			f.setAccessible(true);
			f.setInt(server, server.getTickCount() + 1000);
			fr.sumenium.eco.shop.Redstone.tick(server);
			check(out, !fr.sumenium.eco.shop.Redstone.isPowered(level, chest) && !fr.sumenium.eco.shop.Redstone.isPowered(level, w4)
					&& (!level.getBlockState(wire).is(Blocks.REDSTONE_WIRE)
							|| level.getBlockState(wire).getValue(net.minecraft.world.level.block.RedstoneWireBlock.POWER) == 0),
					"signaux éteints après la durée, sans erreur");
		} catch (ReflectiveOperationException e) {
			out.add("INFO tickCount inaccessible : " + e);
		}

		// Les vraies commandes, passées par le dispatcher (comme un joueur dans le chat)
		BlockPos cmdBox = new BlockPos(0, 100, 6);
		for (int x = -1; x <= 1; x++) for (int z = 3; z <= 7; z++) {
			level.setBlock(new BlockPos(x, 99, z), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(new BlockPos(x, 100, z), Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(new BlockPos(x, 101, z), Blocks.AIR.defaultBlockState(), 3);
		}
		level.setBlock(cmdBox, Blocks.BARREL.defaultBlockState(), 3);
		seller.setPos(0.5, 100, 3.5);
		seller.setYRot(0f);
		seller.setXRot(30f);
		seller.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(net.minecraft.world.item.Items.TORCH, 1));
		ds.balance += 100_000;
		check(out, cmdBox.equals(Shops.target(seller)), "commande : le joueur regarde bien le tonneau (" + Shops.target(seller) + ")");
		var dispatcher = server.getCommands().getDispatcher();
		List<String> feedback = new ArrayList<>();
		var source = seller.createCommandSourceStack().withSource(new net.minecraft.commands.CommandSource() {
			@Override public void sendSystemMessage(Component c) { feedback.add(c.getString()); }
			@Override public boolean acceptsSuccess() { return true; }
			@Override public boolean acceptsFailure() { return true; }
			@Override public boolean shouldInformAdmins() { return false; }
		});
		for (String cmd : new String[] {"boutique creer item 10 4", "boutique info", "boutique liste", "boutique panneau", "boutique",
				"compte", "historique", "baltop", "baltop factions", "faction", "faction liste", "bon verifier"}) {
			try {
				dispatcher.execute(cmd, source);
				out.add("OK commande /" + cmd);
			} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
				out.add("OK commande /" + cmd + " (refus normal : " + e.getMessage() + ")");
			} catch (RuntimeException e) {
				out.add("ECHEC commande /" + cmd + " : " + e);
				SumeniumEco.LOGGER.error("selftest /" + cmd, e);
			}
		}
		check(out, SumeniumEco.shops().at(level, cmdBox) != null, "boutique créée par la commande : "
				+ (feedback.isEmpty() ? "" : feedback.get(0)));

		// Historique : dépôt en faction vu par le joueur (perso -50, banque +50)
		fr.sumenium.eco.data.Tx dep = new fr.sumenium.eco.data.Tx();
		dep.t = System.currentTimeMillis();
		dep.type = fr.sumenium.eco.data.Tx.DEPOT_FACTION;
		dep.actor = ds.uuid;
		dep.from = "joueur:" + ds.uuid;
		dep.to = "faction:EquipeTest";
		dep.amount = 5_000;
		String depLine = fr.sumenium.eco.command.AccountCommands.historyLine(dep, "joueur:" + ds.uuid, "faction:EquipeTest", ds.uuid).getString();
		check(out, depLine.contains("-50 Su") && depLine.contains("banque +50 Su"), "historique dépôt faction : " + depLine);
		dep.type = fr.sumenium.eco.data.Tx.ACHAT_SERVICE;
		dep.tax = 0;
		String seen = fr.sumenium.eco.command.AccountCommands.historyLine(dep, "joueur:" + ds.uuid, "faction:EquipeTest", ds.uuid).getString();
		check(out, seen.contains("-50 Su") && seen.contains("banque +50 Su"), "historique achat dans la boutique de sa faction : " + seen);

		// Suppression par le propriétaire : remboursement 50 %
		long b2 = ds.balance;
		SumeniumEco.shops().remove(SumeniumEco.shops().at(level, chest), seller, true);
		check(out, ds.balance - b2 == 7_500 && !SumeniumEco.isProtected(level, chest), "suppression : 75 remboursés");
		check(out, SumeniumEco.txlog().find(tx -> true, 100).size() >= 4, "journal rempli");
		SumeniumEco.exporter().exportAll();
		check(out, Files.exists(SumeniumEco.gameDir().resolve("sumenium-data/export/boutiques.json")), "export écrit");

		// Entonnoir sous une boutique : ne doit rien aspirer
		BlockPos chest2 = base.south(2);
		level.setBlock(chest2, Blocks.CHEST.defaultBlockState(), 3);
		level.setBlock(chest2.below(), Blocks.HOPPER.defaultBlockState(), 3);
		seller.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(net.minecraft.world.item.Items.COAL, 1));
		err = SumeniumEco.shops().create(seller, chest2, Shop.Kind.ITEM, 100, 1, null, false);
		Shops.container(level, chest2).setItem(0, new ItemStack(net.minecraft.world.item.Items.COAL, 10));
		var hopper = (net.minecraft.world.level.block.entity.HopperBlockEntity) level.getBlockEntity(chest2.below());
		net.minecraft.world.level.block.entity.HopperBlockEntity.suckInItems(level, hopper);
		int coalLeft = Items.count(Shops.container(level, chest2), new ItemStack(net.minecraft.world.item.Items.COAL));
		check(out, err == null && hopper.isEmpty() && coalLeft == 10,
				"entonnoir bloqué sous une boutique (err=" + err + ", entonnoir vide=" + hopper.isEmpty() + ", reste=" + coalLeft
						+ ", protégé=" + SumeniumEco.isProtected(level, chest2) + ")");
		check(out, !Shops.container(level, chest2).canTakeItem(hopper, 0, new ItemStack(net.minecraft.world.item.Items.COAL)),
				"extraction refusée au niveau du coffre");

		// Sommeil : un joueur AFK ne compte pas
		var sleep = new net.minecraft.server.players.SleepStatus();
		Afk.markForTest(buyer.getUUID());
		sleep.update(List.of(seller, buyer));
		check(out, sleep.sleepersNeeded(100) == 1, "AFK ignoré pour dormir (besoin : " + sleep.sleepersNeeded(100) + ")");
		Afk.forget(buyer);
		sleep.update(List.of(seller, buyer));
		check(out, sleep.sleepersNeeded(100) == 2, "sans AFK, les deux comptent");

		// Reprise Savs avec des fichiers d'exemple
		try {
			var dir = SumeniumEco.gameDir().resolve("config/savs-common-economy");
			Files.createDirectories(dir);
			Files.writeString(dir.resolve("balances.json"), """
					{"00000000-0000-0000-0000-0000000000c1": {"name": "Ancien", "balance": 2723.5, "version": 3}}""");
			Files.writeString(dir.resolve("shops.json"), """
					{"shops": [
					 {"shopId": "s1", "worldId": "minecraft:overworld", "chestLocation": {"x": 10, "y": 100, "z": 10},
					  "ownerId": "00000000-0000-0000-0000-0000000000c1", "ownerName": "Ancien", "type": "ADMIN",
					  "itemId": "minecraft:lapis_ore", "itemCount": 1,
					  "itemStackSnbt": "H4sIAAAAAAAA/+NiYOBgYMpMYRDOzcxLTS5KTCuxykksyCyOzy9KZWZgTc4vzSthYGBgZAAAMbIK/CoAAAA=",
					  "price": "5.0", "buying": true, "stock": 1728},
					 {"shopId": "s2", "worldId": "minecraft:overworld", "chestLocation": {"x": 12, "y": 100, "z": 10},
					  "ownerId": "00000000-0000-0000-0000-0000000000c1", "ownerName": "Ancien", "type": "PLAYER",
					  "itemId": "minecraft:enchanted_book", "itemCount": 1,
					  "itemStackSnbt": "H4sIAAAAAAAA/03MMQqAMBBE0dEgSGoLGy/iZULcRAyaXdHN/VUQtJjq88YCLeoU0OfEkQ4/6xiZFs8ag5tEVoOGpLACqCwsSd6FI+tpMXzmVDlu8NL8dIPu19O2OpVCy/Nz7wKck+ykeQAAAA==",
					  "price": "350.0", "buying": false, "stock": 2},
					 {"shopId": "s3", "worldId": "minecraft:overworld", "chestLocation": {"x": 14, "y": 100, "z": 10},
					  "ownerId": "00000000-0000-0000-0000-0000000000c1", "ownerName": "Ancien", "type": "ADMIN",
					  "itemId": "minecraft:paper", "itemCount": 1, "itemStackSnbt": null,
					  "price": "150.0", "buying": false, "stock": 0}
					]}""");
			List<String> report = SavsImport.run(true, true);
			PlayerData old = SumeniumEco.accounts().player("00000000-0000-0000-0000-0000000000c1", "Ancien");
			Shop lapis = SumeniumEco.store().shops.get(Shop.key("minecraft:overworld", 10, 100, 10));
			Shop book = SumeniumEco.store().shops.get(Shop.key("minecraft:overworld", 12, 100, 10));
			check(out, old.balance == 272_350, "Savs : solde repris " + old.balance);
			check(out, lapis != null && lapis.kind == Shop.Kind.FISC_RACHAT && lapis.price == 500
					&& "minecraft:lapis_ore".equals(lapis.itemId), "Savs : rachat FISC lapis à 5");
			check(out, book != null && book.kind == Shop.Kind.ITEM && book.price == 35_000
					&& Items.decode(book.item).has(DataComponents.STORED_ENCHANTMENTS), "Savs : livre enchanté repris avec ses enchantements");
			check(out, !SumeniumEco.store().shops.containsKey(Shop.key("minecraft:overworld", 14, 100, 10)), "Savs : certificat papier ignoré");
			out.add("INFO " + String.join(" / ", report));
		} catch (Exception e) {
			out.add("ECHEC Savs : " + e);
		}
	}

	private static void check(List<String> out, boolean cond, String label) {
		out.add((cond ? "OK " : "ECHEC ") + label);
	}
}
