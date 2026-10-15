package fr.sumenium.eco.mixin;

import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Double coffre : on ne peut rien extraire si l'une des moitiés est une boutique. */
@Mixin(CompoundContainer.class)
public abstract class CompoundContainerMixin implements Container {

	@Shadow
	@Final
	private Container container1;

	@Shadow
	@Final
	private Container container2;

	@Override
	public boolean canTakeItem(Container target, int slot, ItemStack stack) {
		return container1.canTakeItem(target, slot, stack) && container2.canTakeItem(target, slot, stack);
	}
}
