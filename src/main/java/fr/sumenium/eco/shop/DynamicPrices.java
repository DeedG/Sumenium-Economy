package fr.sumenium.eco.shop;

import fr.sumenium.eco.Config;
import fr.sumenium.eco.SumeniumEco;

import java.util.HashMap;
import java.util.Map;

/**
 * Prix dynamiques du rachat FISC (désactivés par défaut).
 * Chaque lot vendu baisse le prix de X % jusqu'au plancher ; la baisse
 * disparaît progressivement et le prix est revenu à la normale au bout du délai configuré.
 * Suivi par joueur ou par faction selon la config.
 */
public final class DynamicPrices {

	private static final class Entry {
		double lots;
		long updated;
	}

	private final Map<String, Entry> entries = new HashMap<>();

	private static double maxLots(Config c) {
		double base = 1.0 - c.prixDynamiquesBaisseParLot;
		if (base <= 0 || base >= 1 || c.prixDynamiquesPlancher <= 0) return 0;
		return Math.log(c.prixDynamiquesPlancher) / Math.log(base);
	}

	private Entry entry(String key, long now) {
		Config c = SumeniumEco.config();
		Entry e = entries.computeIfAbsent(key, k -> new Entry());
		if (e.updated > 0 && e.lots > 0) {
			double perMs = maxLots(c) / Math.max(1.0, c.prixDynamiquesRetourMinutes * 60_000.0);
			e.lots = Math.max(0, e.lots - (now - e.updated) * perMs);
		}
		e.updated = now;
		return e;
	}

	private static double factor(Config c, double lots) {
		double f = Math.pow(1.0 - c.prixDynamiquesBaisseParLot, lots);
		return Math.max(c.prixDynamiquesPlancher, f);
	}

	/** Coefficient actuel (1.0 = prix normal). */
	public double currentFactor(String scope, String itemId) {
		Config c = SumeniumEco.config();
		if (!c.dynamicEnabled()) return 1.0;
		return factor(c, entry(scope + "|" + itemId, System.currentTimeMillis()).lots);
	}

	/**
	 * Calcule le montant (centimes) pour `count` items au prix unitaire `unitPrice`
	 * et enregistre la vente.
	 */
	public long sell(String scope, String itemId, long unitPrice, int count) {
		Config c = SumeniumEco.config();
		if (!c.dynamicEnabled()) return unitPrice * count;
		Entry e = entry(scope + "|" + itemId, System.currentTimeMillis());
		int lot = Math.max(1, c.prixDynamiquesTailleLot);
		double total = 0;
		int left = count;
		while (left > 0) {
			int n = Math.min(lot, left);
			total += unitPrice * n * factor(c, e.lots);
			e.lots += (double) n / lot;
			left -= n;
		}
		return Math.round(total);
	}

	public void reset() {
		entries.clear();
	}
}
