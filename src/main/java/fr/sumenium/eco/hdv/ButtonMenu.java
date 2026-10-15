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

	private final BiConsumer<ServerPlayer, Integer> onClick;

	public ButtonMenu(int id, Inventory inv, SimpleContainer content, BiConsumer<ServerPlayer, Integer> onClick) {
		super(MenuType.GENERIC_9x6, id, inv, content, 6);
		this.onClick = onClick;
	}

	@Override
	public void clicked(int slot, int button, ContainerInput input, Player player) {
		// on ne laisse rien bouger : on resynchronise l'affichage du joueur
		sendAllDataToRemote();
		if (slot >= 0 && slot < 54 && player instanceof ServerPlayer sp) {
			onClick.accept(sp, slot);
		}
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
