package fr.sumenium.eco.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/** Détail lisible d'un item pour les exports du site (enchantements, nom, lore, potion, usure). */
public final class ItemDetails {

	private ItemDetails() {}

	public static JsonObject of(JsonElement encoded) {
		ItemStack stack = Items.decode(encoded);
		JsonObject o = new JsonObject();
		if (stack == null || stack.isEmpty()) return o;
		Component custom = stack.get(DataComponents.CUSTOM_NAME);
		if (custom != null) o.addProperty("nomPerso", custom.getString());
		JsonArray ench = new JsonArray();
		StringBuilder summary = new StringBuilder();
		addEnchantments(stack.get(DataComponents.ENCHANTMENTS), ench, summary);
		addEnchantments(stack.get(DataComponents.STORED_ENCHANTMENTS), ench, summary);
		if (!ench.isEmpty()) o.add("enchantements", ench);
		var potion = stack.get(DataComponents.POTION_CONTENTS);
		if (potion != null && potion.potion().isPresent()) {
			potion.potion().get().unwrapKey().ifPresent(k -> o.addProperty("potion", k.identifier().toString()));
		}
		var lore = stack.get(DataComponents.LORE);
		if (lore != null && !lore.lines().isEmpty()) {
			JsonArray lines = new JsonArray();
			for (Component c : lore.lines()) lines.add(c.getString());
			o.add("lore", lines);
		}
		Integer max = stack.get(DataComponents.MAX_DAMAGE);
		if (max != null && max > 0) {
			Integer dmg = stack.get(DataComponents.DAMAGE);
			o.addProperty("durabilite", max - (dmg == null ? 0 : dmg));
			o.addProperty("durabiliteMax", max);
		}
		if (summary.length() > 0) o.addProperty("resume", summary.toString());
		// composants complets (format /give) pour tout le reste
		o.add("composants", encoded);
		return o;
	}

	private static void addEnchantments(ItemEnchantments e, JsonArray out, StringBuilder summary) {
		if (e == null || e.isEmpty()) return;
		for (var entry : e.entrySet()) {
			JsonObject j = new JsonObject();
			entry.getKey().unwrapKey().ifPresent(k -> j.addProperty("id", k.identifier().toString()));
			j.addProperty("niveau", entry.getIntValue());
			String name = Enchantment.getFullname(entry.getKey(), entry.getIntValue()).getString();
			j.addProperty("nom", name);
			out.add(j);
			if (summary.length() > 0) summary.append(", ");
			summary.append(name);
		}
	}
}
