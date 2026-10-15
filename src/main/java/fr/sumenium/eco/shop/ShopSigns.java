package fr.sumenium.eco.shop;

import fr.sumenium.eco.SumeniumEco;
import fr.sumenium.eco.data.Faction;
import fr.sumenium.eco.data.Shop;
import fr.sumenium.eco.faction.Factions;
import fr.sumenium.eco.util.Money;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.CeilingHangingSignBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallHangingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Panneau d'une boutique : trouvé automatiquement (panneau posé sur le coffre,
 * au-dessus, ou suspendu dessous), ciré pour qu'on ne puisse plus le modifier,
 * et réécrit seulement quand son contenu change (propriétaire, item, prix, stock).
 */
final class ShopSigns {

	private ShopSigns() {}

	/** Cherche un panneau accroché au coffre (ou à l'autre moitié d'un double coffre). */
	static BlockPos find(ServerLevel level, List<BlockPos> containerParts, java.util.function.Predicate<BlockPos> taken) {
		for (BlockPos part : containerParts) {
			for (Direction d : Direction.values()) {
				BlockPos pos = part.relative(d);
				if (containerParts.contains(pos) || taken.test(pos)) continue;
				BlockState state = level.getBlockState(pos);
				if (!(state.getBlock() instanceof SignBlock)) continue;
				if (supportOf(pos, state).contains(part)) return pos;
			}
		}
		return null;
	}

	/** Blocs qui portent ce panneau. */
	private static List<BlockPos> supportOf(BlockPos pos, BlockState state) {
		List<BlockPos> out = new ArrayList<>(2);
		if (state.getBlock() instanceof WallSignBlock) {
			out.add(pos.relative(state.getValue(WallSignBlock.FACING).getOpposite()));
		} else if (state.getBlock() instanceof StandingSignBlock) {
			out.add(pos.below());
		} else if (state.getBlock() instanceof CeilingHangingSignBlock) {
			out.add(pos.above());
		} else if (state.getBlock() instanceof WallHangingSignBlock) {
			Direction f = state.getValue(WallHangingSignBlock.FACING);
			out.add(pos.relative(f.getClockWise()));
			out.add(pos.relative(f.getCounterClockWise()));
		}
		return out;
	}

	/** Écrit le panneau si son texte a changé. Renvoie false si le panneau n'existe plus. */
	static boolean write(ServerLevel level, Shop s, String ownerLabel, ChatFormatting ownerColor) {
		BlockPos pos = new BlockPos(s.signX, s.signY, s.signZ);
		if (!level.isLoaded(pos)) return true;
		if (!(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) return false;

		Component[] lines = lines(s, ownerLabel, ownerColor);
		StringBuilder key = new StringBuilder();
		for (Component c : lines) key.append(c.getString()).append('|');
		key.append(ownerColor.name());
		if (key.toString().equals(s.signCache) && sign.isWaxed()) return true;

		SignText text = new SignText(List.of(lines), List.of(lines), DyeColor.BLACK, false);
		sign.setText(text, SignTextSlot.FRONT);
		sign.setText(text, SignTextSlot.BACK);
		sign.setWaxed(true);
		sign.setChanged();
		BlockState state = level.getBlockState(pos);
		level.sendBlockUpdated(pos, state, state, 3);
		s.signCache = key.toString();
		return true;
	}

	private static Component[] lines(Shop s, String ownerLabel, ChatFormatting ownerColor) {
		String price = Money.format(s.price);
		Component l1 = Component.literal(cut(ownerLabel)).withStyle(ownerColor);
		Component l2 = Component.literal(cut(s.kind == Shop.Kind.SERVICE ? s.serviceName : s.itemName));
		Component l3;
		Component l4;
		switch (s.kind) {
			case ITEM -> {
				l3 = Component.literal(cut(s.quantity > 1 ? s.quantity + " pour " + price : "Vente : " + price));
				boolean empty = s.lastStock >= 0 && s.lastStock < s.quantity;
				l4 = s.lastStock < 0 ? Component.literal("")
						: empty ? Component.literal("Rupture").withStyle(ChatFormatting.RED)
						: Component.literal("Stock : " + s.lastStock);
			}
			case SERVICE -> {
				l3 = Component.literal(cut("Service : " + price));
				l4 = Component.literal("Bon nominatif").withStyle(ChatFormatting.DARK_GRAY);
			}
			case FISC_RACHAT -> {
				l3 = Component.literal(cut("Rachat : " + price));
				l4 = Component.literal("Clic pour vendre").withStyle(ChatFormatting.DARK_GRAY);
			}
			default -> {
				l3 = Component.literal(cut((s.quantity > 1 ? s.quantity + " pour " : "Vente : ") + price));
				l4 = Component.literal("Clic pour acheter").withStyle(ChatFormatting.DARK_GRAY);
			}
		}
		return new Component[] {l1, l2, l3, l4};
	}

	/** Un panneau affiche environ 15 caractères par ligne. */
	private static String cut(String s) {
		if (s == null) return "";
		return s.length() <= 15 ? s : s.substring(0, 14) + "…";
	}

	static String ownerLabel(Shop s) {
		return switch (s.ownerType) {
			case FISC -> "FISC";
			case FACTION -> {
				Faction f = SumeniumEco.store().factions.get(s.ownerId);
				yield f == null ? s.ownerId : f.tag;
			}
			case JOUEUR -> {
				var d = SumeniumEco.store().players.get(s.ownerId);
				yield d == null ? "?" : d.name;
			}
		};
	}

	static ChatFormatting ownerColor(Shop s) {
		return switch (s.ownerType) {
			case FISC -> ChatFormatting.GOLD;
			case FACTION -> {
				Faction f = SumeniumEco.store().factions.get(s.ownerId);
				yield f == null ? ChatFormatting.DARK_BLUE : Factions.chatColor(f.color);
			}
			case JOUEUR -> ChatFormatting.DARK_BLUE;
		};
	}
}
