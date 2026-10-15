package fr.sumenium.eco.util;

import fr.sumenium.eco.SumeniumEco;

/** Montants en centimes de sumenien. */
public final class Money {

	private Money() {}

	public static long toCents(double amount) {
		return Math.round(amount * 100.0);
	}

	public static double toUnits(long cents) {
		return cents / 100.0;
	}

	/** 2723 -> "27,23", 150000 -> "1 500" (espaces fines pour les milliers). */
	public static String plain(long cents) {
		boolean neg = cents < 0;
		long abs = Math.abs(cents);
		long units = abs / 100;
		long dec = abs % 100;
		String u = Long.toString(units);
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < u.length(); i++) {
			if (i > 0 && (u.length() - i) % 3 == 0) sb.append(' ');
			sb.append(u.charAt(i));
		}
		if (dec != 0) {
			sb.append(',');
			if (dec % 10 == 0) sb.append(dec / 10);
			else sb.append(String.format("%02d", dec));
		}
		return (neg ? "-" : "") + sb;
	}

	public static String format(long cents) {
		return plain(cents) + " " + SumeniumEco.config().symbole;
	}
}
