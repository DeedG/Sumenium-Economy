package fr.sumenium.eco.hdv;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;

import java.util.function.Consumer;

/** Boîte aux lettres : un coffre virtuel ; ce qui reste dedans à la fermeture est conservé. */
public final class MailboxMenu extends ChestMenu {

	private final SimpleContainer content;
	private final Consumer<SimpleContainer> onClose;

	public MailboxMenu(int id, Inventory inv, SimpleContainer content, Consumer<SimpleContainer> onClose) {
		super(MenuType.GENERIC_9x6, id, inv, content, 6);
		this.content = content;
		this.onClose = onClose;
	}

	/** On peut seulement retirer des items de la boîte, pas en déposer. */
	@Override
	public void clicked(int slot, int button, net.minecraft.world.inventory.ContainerInput input, Player player) {
		boolean top = slot >= 0 && slot < 54;
		boolean blocked = switch (input) {
			case PICKUP -> top && !getCarried().isEmpty();
			case QUICK_MOVE -> !top; // depuis l'inventaire vers la boîte
			case SWAP, QUICK_CRAFT -> true;
			default -> false;
		};
		if (blocked) {
			sendAllDataToRemote();
			return;
		}
		super.clicked(slot, button, input, player);
	}

	@Override
	public void removed(Player player) {
		super.removed(player);
		onClose.accept(content);
	}

	@Override
	public boolean stillValid(Player player) {
		return true;
	}
}
