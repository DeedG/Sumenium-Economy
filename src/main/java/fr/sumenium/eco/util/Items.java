package fr.sumenium.eco.util;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import fr.sumenium.eco.SumeniumEco;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Sérialisation et manipulation des items des boutiques. */
public final class Items {

	private Items() {}

	public static JsonElement encode(ItemStack stack) {
		var ops = SumeniumEco.server().registryAccess().createSerializationContext(JsonOps.INSTANCE);
		return ItemStack.CODEC.encodeStart(ops, stack.copyWithCount(1)).getOrThrow();
	}

	public static ItemStack decode(JsonElement json) {
		if (json == null) return ItemStack.EMPTY;
		try {
			var ops = SumeniumEco.server().registryAccess().createSerializationContext(JsonOps.INSTANCE);
			return ItemStack.CODEC.parse(ops, json).result().orElse(ItemStack.EMPTY);
		} catch (RuntimeException e) {
			SumeniumEco.LOGGER.warn("Item de boutique illisible : {}", json, e);
			return ItemStack.EMPTY;
		}
	}

	public static String id(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}

	public static String name(ItemStack stack) {
		return stack.getHoverName().getString();
	}

	public static boolean same(ItemStack a, ItemStack b) {
		return ItemStack.isSameItemSameComponents(a, b);
	}

	/** Nombre d'items identiques au modèle dans le conteneur. */
	public static int count(Container c, ItemStack model) {
		int n = 0;
		for (int i = 0; i < c.getContainerSize(); i++) {
			ItemStack s = c.getItem(i);
			if (!s.isEmpty() && same(s, model)) n += s.getCount();
		}
		return n;
	}

	/** Retire `amount` items identiques au modèle ; renvoie le nombre réellement retiré. */
	public static int remove(Container c, ItemStack model, int amount) {
		int left = amount;
		for (int i = 0; i < c.getContainerSize() && left > 0; i++) {
			ItemStack s = c.getItem(i);
			if (!s.isEmpty() && same(s, model)) {
				int take = Math.min(left, s.getCount());
				c.removeItem(i, take);
				left -= take;
			}
		}
		c.setChanged();
		return amount - left;
	}

	/** Donne des items au joueur ; ce qui ne rentre pas tombe à ses pieds. */
	public static void give(ServerPlayer p, ItemStack model, int amount) {
		int max = Math.max(1, model.getMaxStackSize());
		int left = amount;
		while (left > 0) {
			int n = Math.min(max, left);
			ItemStack s = model.copyWithCount(n);
			left -= n;
			if (!p.getInventory().add(s) || !s.isEmpty()) {
				if (!s.isEmpty()) {
					ItemEntity e = new ItemEntity(p.level(), p.getX(), p.getY() + 0.5, p.getZ(), s);
					e.setNoPickUpDelay();
					p.level().addFreshEntity(e);
				}
			}
		}
		p.containerMenu.broadcastChanges();
	}
}
