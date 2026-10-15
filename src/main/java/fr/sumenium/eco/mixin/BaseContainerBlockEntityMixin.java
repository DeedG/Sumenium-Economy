package fr.sumenium.eco.mixin;

import fr.sumenium.eco.SumeniumEco;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Deuxième protection contre les entonnoirs (y compris avec les optimisations de Lithium) :
 * aucun item ne peut être extrait automatiquement d'un coffre de boutique.
 */
@Mixin(BaseContainerBlockEntity.class)
public abstract class BaseContainerBlockEntityMixin implements Container {

	@Override
	public boolean canTakeItem(Container target, int slot, ItemStack stack) {
		BlockEntity self = (BlockEntity) (Object) this;
		return self.getLevel() == null || !SumeniumEco.isProtected(self.getLevel(), self.getBlockPos());
	}
}
