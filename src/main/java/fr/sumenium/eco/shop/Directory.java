package fr.sumenium.eco.shop;

import fr.sumenium.eco.SumeniumEco;
import fr.sumenium.eco.data.HdvData;
import fr.sumenium.eco.data.Shop;
import fr.sumenium.eco.data.TxLog;
import fr.sumenium.eco.util.Money;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Annuaire des boutiques : un livre posé sur un ou plusieurs lutrins,
 * réécrit automatiquement quand les boutiques changent.
 */
public final class Directory {

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(TxLog.ZONE);
	private static final int SHOPS_PER_PAGE = 3;
	private static boolean dirty = true;

	private Directory() {}

	public static void markDirty() {
		dirty = true;
	}

	/** Appelé régulièrement : réécrit les livres si les boutiques ont changé (et remet un livre pris). */
	public static void tick() {
		List<HdvData.Place> lecterns = SumeniumEco.store().hdv.lecterns;
		if (lecterns.isEmpty()) return;
		boolean missing = false;
		for (HdvData.Place pl : lecterns) {
			ServerLevel level = level(pl.world);
			BlockPos pos = new BlockPos(pl.x, pl.y, pl.z);
			if (level != null && level.isLoaded(pos) && level.getBlockEntity(pos) instanceof LecternBlockEntity be && !be.hasBook()) missing = true;
		}
		if (!dirty && !missing) return;
		dirty = false;
		ItemStack book = buildBook();
		for (HdvData.Place pl : lecterns) place(pl, book);
	}

	private static ServerLevel level(String world) {
		for (ServerLevel l : SumeniumEco.server().getAllLevels()) if (Shops.world(l).equals(world)) return l;
		return null;
	}

	private static void place(HdvData.Place pl, ItemStack book) {
		ServerLevel level = level(pl.world);
		BlockPos pos = new BlockPos(pl.x, pl.y, pl.z);
		if (level == null || !level.isLoaded(pos)) return;
		if (!(level.getBlockEntity(pos) instanceof LecternBlockEntity be)) return;
		BlockState state = level.getBlockState(pos);
		if (!state.getValue(LecternBlock.HAS_BOOK)) {
			level.setBlock(pos, state.setValue(LecternBlock.HAS_BOOK, true), 3);
		}
		be.setBook(book.copy());
		be.setChanged();
		BlockState now = level.getBlockState(pos);
		level.sendBlockUpdated(pos, now, now, 3);
	}

	public static boolean isLectern(Level level, BlockPos pos) {
		String w = Shops.world(level);
		for (HdvData.Place pl : SumeniumEco.store().hdv.lecterns) if (pl.is(w, pos.getX(), pos.getY(), pos.getZ())) return true;
		return false;
	}

	public static void add(Level level, BlockPos pos) {
		SumeniumEco.store().hdv.lecterns.add(new HdvData.Place(Shops.world(level), pos.getX(), pos.getY(), pos.getZ()));
		SumeniumEco.store().markDirty();
		dirty = true;
		tick();
	}

	public static boolean remove(Level level, BlockPos pos) {
		String w = Shops.world(level);
		boolean removed = SumeniumEco.store().hdv.lecterns.removeIf(pl -> pl.is(w, pos.getX(), pos.getY(), pos.getZ()));
		if (removed) SumeniumEco.store().markDirty();
		return removed;
	}

	/** Le livre : une page de garde, puis 3 boutiques par page (FISC d'abord, puis par propriétaire). */
	public static ItemStack buildBook() {
		Shops shops = SumeniumEco.shops();
		List<Shop> list = new ArrayList<>(SumeniumEco.store().shops.values());
		list.sort(Comparator.comparing((Shop s) -> s.ownerType != Shop.OwnerType.FISC)
				.thenComparing(s -> shops.ownerName(s).toLowerCase())
				.thenComparing(s -> s.itemName == null ? "" : s.itemName.toLowerCase()));
		List<Filterable<Component>> pages = new ArrayList<>();
		MutableComponent cover = Component.literal("ANNUAIRE\nDES BOUTIQUES\n\n").withStyle(ChatFormatting.DARK_BLUE, ChatFormatting.BOLD)
				.append(Component.literal(list.size() + " boutiques\n\nMis à jour le\n" + DATE.format(Instant.now())
						+ "\n\nClic sur un coffre ou son panneau, puis tape la quantité dans le chat.").withStyle(s -> s.withBold(false).withColor(ChatFormatting.BLACK)));
		pages.add(Filterable.passThrough(cover));
		for (int i = 0; i < list.size() && pages.size() < 100; i += SHOPS_PER_PAGE) {
			MutableComponent page = Component.empty();
			for (int j = i; j < Math.min(list.size(), i + SHOPS_PER_PAGE); j++) {
				Shop s = list.get(j);
				if (j > i) page.append(Component.literal("\n"));
				page.append(Component.literal(fit(shops.ownerName(s), true) + "\n").withStyle(ChatFormatting.DARK_BLUE, ChatFormatting.BOLD));
				page.append(itemLine(s));
				boolean empty = s.kind == Shop.Kind.ITEM && s.lastStock >= 0 && s.lastStock < s.quantity;
				page.append(Component.literal(priceLine(s, empty) + "\n")
						.withStyle(empty ? ChatFormatting.DARK_RED : ChatFormatting.DARK_GREEN));
				page.append(Component.literal(fit(s.x + " " + s.y + " " + s.z, false) + "\n").withStyle(ChatFormatting.DARK_GRAY));
			}
			pages.add(Filterable.passThrough(page));
		}
		ItemStack book = new ItemStack(net.minecraft.world.item.Items.WRITTEN_BOOK);
		book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
				Filterable.passThrough("Annuaire des boutiques"), "FISC", 0, pages, true));
		return book;
	}

	/** Largeur utile d'une page de livre en pixels (114), avec une marge. */
	static final int PAGE_WIDTH = 110;

	/** Largeur d'un caractère dans la police vanilla (espacement compris). */
	static int charWidth(char c, boolean bold) {
		int w = switch (c) {
			case 'i', '!', '.', ',', ':', ';', '|', '\'' -> 2;
			case 'l', '`' -> 3;
			case 'I', 't', '[', ']', ' ', ' ', ' ' -> 4;
			case 'f', 'k', '<', '>', '"', '(', ')', '{', '}', '*' -> 5;
			case '@', '~' -> 7;
			default -> 6;
		};
		return bold ? w + 1 : w;
	}

	public static int width(String s, boolean bold) {
		int w = 0;
		for (int i = 0; i < s.length(); i++) w += charWidth(s.charAt(i), bold);
		return w;
	}

	/** Coupe le texte avec « .. » pour qu'il tienne sur une seule ligne de la page. */
	public static String fit(String s, boolean bold) {
		return fit(s, bold, PAGE_WIDTH);
	}

	static String fit(String s, boolean bold, int limit) {
		if (s == null) return "";
		if (width(s, bold) <= limit) return s;
		int max = limit - width("..", bold);
		StringBuilder b = new StringBuilder();
		int w = 0;
		for (int i = 0; i < s.length(); i++) {
			int cw = charWidth(s.charAt(i), bold);
			if (w + cw > max) break;
			b.append(s.charAt(i));
			w += cw;
		}
		return b.toString().stripTrailing() + "..";
	}

	/** Ligne de prix, raccourcie si besoin (la couleur rouge suffit à signaler la rupture). */
	static String priceLine(Shop s, boolean empty) {
		String price = Money.format(s.price);
		List<String> tries = new ArrayList<>();
		if (s.kind == Shop.Kind.FISC_RACHAT) {
			tries.add("Rachète " + price + " l'unité");
			tries.add("Rachète " + price + "/u");
			tries.add(price + "/u");
		} else {
			String base = (s.quantity > 1 ? s.quantity + " pour " : "") + price;
			if (empty) tries.add(base + " (rupture)");
			tries.add(base);
			if (s.quantity > 1) tries.add(s.quantity + " = " + price);
		}
		for (String t : tries) if (width(t, false) <= PAGE_WIDTH) return t;
		return fit(tries.get(tries.size() - 1), false);
	}

	/** Nom de l'item survolable (comme dans le chat) ; pour un livre enchanté, l'enchantement plutôt que « Enchanted Book ». */
	private static MutableComponent itemLine(Shop s) {
		if (s.kind == Shop.Kind.SERVICE) return Component.literal(fit("Service : " + s.serviceName, false) + "\n").withStyle(ChatFormatting.BLACK);
		ItemStack stack = fr.sumenium.eco.util.Items.decode(s.item);
		if (stack == null || stack.isEmpty()) return Component.literal(fit(s.itemName, false) + "\n").withStyle(ChatFormatting.BLACK);
		String name = stack.getHoverName().getString();
		net.minecraft.world.item.enchantment.ItemEnchantments ench = stack.get(DataComponents.STORED_ENCHANTMENTS);
		if (ench != null && !ench.isEmpty()) {
			var entries = new ArrayList<>(ench.entrySet());
			var first = entries.get(0);
			name = net.minecraft.world.item.enchantment.Enchantment.getFullname(first.getKey(), first.getIntValue()).getString();
			if (entries.size() > 1) {
				String more = " +" + (entries.size() - 1);
				name = fit(name, false, PAGE_WIDTH - width(more, false)) + more;
			}
		}
		return Component.empty()
				.append(Component.literal(fit(name, false)).withStyle(st -> st.withColor(ChatFormatting.DARK_AQUA).withUnderlined(true)
						.withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowItem(
								net.minecraft.world.item.ItemStackTemplate.fromNonEmptyStack(stack)))))
				.append(Component.literal("\n"));
	}
}
