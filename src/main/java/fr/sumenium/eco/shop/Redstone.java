package fr.sumenium.eco.shop;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Impulsions de redstone émises par les coffres des boutiques (à chaque vente)
 * et par les coffres de dépôt de service (bon valide déposé).
 * Lu par le mixin sur BlockStateBase#getSignal : quand rien n'est actif, le coût est un simple test.
 */
public final class Redstone {

	/** monde -> (position -> tick de fin) */
	private static final Map<Level, Long2IntOpenHashMap> ACTIVE = new IdentityHashMap<>();
	private static volatile boolean any;

	private Redstone() {}

	/** Appelé par le mixin, potentiellement très souvent. */
	public static boolean isPowered(BlockGetter getter, BlockPos pos) {
		if (!any || !(getter instanceof Level level)) return false;
		Long2IntOpenHashMap map = ACTIVE.get(level);
		return map != null && map.containsKey(pos.asLong());
	}

	/** Allume le bloc pendant `ticks` ticks (prolonge si déjà allumé). */
	public static void pulse(ServerLevel level, BlockPos pos, int ticks) {
		if (ticks <= 0) return;
		int end = level.getServer().getTickCount() + ticks;
		Long2IntOpenHashMap map = ACTIVE.computeIfAbsent(level, l -> new Long2IntOpenHashMap());
		boolean was = map.containsKey(pos.asLong());
		map.put(pos.asLong(), Math.max(end, map.getOrDefault(pos.asLong(), 0)));
		any = true;
		if (!was) notify(level, pos);
	}

	/** Chaque tick : éteint les impulsions terminées. */
	public static void tick(MinecraftServer server) {
		if (!any) return;
		int now = server.getTickCount();
		boolean left = false;
		for (var e : ACTIVE.entrySet()) {
			Long2IntOpenHashMap map = e.getValue();
			if (map.isEmpty()) continue;
			var it = map.long2IntEntrySet().iterator();
			while (it.hasNext()) {
				var entry = it.next();
				if (entry.getIntValue() <= now) {
					it.remove();
					notify((ServerLevel) e.getKey(), BlockPos.of(entry.getLongKey()));
				}
			}
			if (!map.isEmpty()) left = true;
		}
		any = left;
	}

	private static void notify(ServerLevel level, BlockPos pos) {
		var block = level.getBlockState(pos).getBlock();
		level.updateNeighborsAt(pos, block, null);
	}
}
