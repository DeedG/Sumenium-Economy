package fr.sumenium.eco.hdv;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import java.util.function.BiConsumer;

/**
 * Menu coffre de 6 lignes en lecture seule : aucun item ne peut être pris ni posé,
 * chaque clic sur une case du haut appelle l'action donnée.
 */
public final class ButtonMenu extends ChestMenu {

	/** Dernière action acceptée par joueur (tous menus confondus). */
	private static final java.util.Map<java.util.UUID, Long> LAST_ACTION = new java.util.concurrent.ConcurrentHashMap<>();
	/** Délai après l'ouverture : les mods de tri agissent tout de suite. */
	private static final long OPEN_GRACE_MS = 300;
	/** Une action au plus toutes les 200 ms. */
	private static final long MIN_INTERVAL_MS = 200;
	/** Rafale (plus de 3 clics en 100 ms) : menu bloqué 1,5 s. */
	private static final long BURST_WINDOW_MS = 100, BURST_LOCK_MS = 1500;

	private final BiConsumer<ServerPlayer, Integer> onClick;
	private final long openedAt = System.currentTimeMillis();
	private long burstStart;
	private int burstCount;
	private long lockedUntil;

	public ButtonMenu(int id, Inventory inv, SimpleContainer content, BiConsumer<ServerPlayer, Integer> onClick) {
		super(MenuType.GENERIC_9x6, id, inv, content, 6);
		this.onClick = onClick;
	}

	@Override
	public void clicked(int slot, int button, ContainerInput input, Player player) {
		// on ne laisse rien bouger : on resynchronise l'affichage du joueur
		setCarried(ItemStack.EMPTY);
		sendAllDataToRemote();
		if (!(player instanceof ServerPlayer sp)) return;
		long now = System.currentTimeMillis();
		// détection des rafales (mods de tri, macros) : tout clic compte, même ignoré
		if (now - burstStart > BURST_WINDOW_MS) {
			burstStart = now;
			burstCount = 0;
		}
		if (++burstCount > 3) lockedUntil = now + BURST_LOCK_MS;
		if (now < lockedUntil || now - openedAt < OPEN_GRACE_MS) return;
		// seul un vrai clic gauche ou droit sur une case du menu déclenche une action
		if (input != ContainerInput.PICKUP || (button != 0 && button != 1)) return;
		if (slot < 0 || slot >= 54) return;
		Long last = LAST_ACTION.get(sp.getUUID());
		if (last != null && now - last < MIN_INTERVAL_MS) return;
		LAST_ACTION.put(sp.getUUID(), now);
		onClick.accept(sp, slot);
	}

	@Override
	public void removed(Player player) {
		setCarried(ItemStack.EMPTY);
		super.removed(player);
	}

	@Override
	public ItemStack quickMoveStack(Player player, int slot) {
		return ItemStack.EMPTY;
	}

	@Override
	public boolean stillValid(Player player) {
		return true;
	}
}
