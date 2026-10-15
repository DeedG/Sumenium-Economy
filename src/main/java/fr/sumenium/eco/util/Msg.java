package fr.sumenium.eco.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;

/** Petits raccourcis pour les messages du mod. */
public final class Msg {

	private Msg() {}

	public static MutableComponent prefix() {
		return Component.literal("[FISC] ").withStyle(ChatFormatting.GOLD);
	}

	public static MutableComponent text(String s) {
		return Component.literal(s);
	}

	public static MutableComponent info(String s) {
		return prefix().append(Component.literal(s).withStyle(ChatFormatting.YELLOW));
	}

	public static MutableComponent ok(String s) {
		return prefix().append(Component.literal(s).withStyle(ChatFormatting.GREEN));
	}

	public static MutableComponent err(String s) {
		return prefix().append(Component.literal(s).withStyle(ChatFormatting.RED));
	}

	public static MutableComponent money(long cents) {
		return Component.literal(Money.format(cents)).withStyle(ChatFormatting.GOLD);
	}

	/** Nom de l'item entre crochets, survolable pour voir l'item complet (enchantements…). */
	public static MutableComponent item(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return Component.literal("[?]").withStyle(ChatFormatting.GRAY);
		MutableComponent c = Component.literal("[").append(stack.getHoverName()).append("]");
		return c.withStyle(s -> s.withColor(ChatFormatting.AQUA)
				.withHoverEvent(new HoverEvent.ShowItem(ItemStackTemplate.fromNonEmptyStack(stack))));
	}

	public static MutableComponent item(ItemStack stack, int count) {
		return Component.literal(count + "× ").withStyle(ChatFormatting.WHITE).append(item(stack));
	}

	/** Texte cliquable qui lance une commande. */
	public static MutableComponent command(String label, String command) {
		return Component.literal(label).withStyle(s -> s.withColor(ChatFormatting.AQUA).withUnderlined(true)
				.withClickEvent(new ClickEvent.RunCommand(command)));
	}

	public static void send(ServerPlayer p, Component c) {
		p.sendSystemMessage(c);
	}

	public static void actionBar(ServerPlayer p, Component c) {
		p.sendSystemMessage(c, true);
	}
}
