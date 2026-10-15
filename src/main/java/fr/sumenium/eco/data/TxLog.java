package fr.sumenium.eco.data;

import com.google.gson.Gson;
import fr.sumenium.eco.SumeniumEco;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Predicate;

/**
 * Journal des transactions : un fichier JSONL par jour (une transaction par ligne),
 * plus les derniers jours en mémoire pour /historique et les statistiques.
 */
public final class TxLog {

	public static final ZoneId ZONE = ZoneId.of("Europe/Paris");
	private static final Gson GSON = new Gson();

	private final Path dir;
	private final int keepDays;
	private final Deque<Tx> recent = new ArrayDeque<>();

	public TxLog(Path dir, int keepDays) {
		this.dir = dir;
		this.keepDays = Math.max(1, keepDays);
	}

	public void loadRecent() {
		recent.clear();
		LocalDate today = LocalDate.now(ZONE);
		for (int i = keepDays - 1; i >= 0; i--) {
			Path file = dir.resolve(today.minusDays(i) + ".jsonl");
			if (!Files.exists(file)) continue;
			try {
				for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
					if (line.isBlank()) continue;
					try {
						recent.addLast(GSON.fromJson(line, Tx.class));
					} catch (RuntimeException ignored) {
					}
				}
			} catch (IOException e) {
				SumeniumEco.LOGGER.warn("Lecture du journal {} impossible", file, e);
			}
		}
	}

	public void add(Tx tx) {
		if (tx.t == 0) tx.t = System.currentTimeMillis();
		recent.addLast(tx);
		prune();
		LocalDate day = Instant.ofEpochMilli(tx.t).atZone(ZONE).toLocalDate();
		Path file = dir.resolve(day + ".jsonl");
		try {
			Files.createDirectories(dir);
			try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
				w.write(GSON.toJson(tx));
				w.newLine();
			}
		} catch (IOException e) {
			SumeniumEco.LOGGER.error("Écriture du journal impossible", e);
		}
	}

	private void prune() {
		long limit = System.currentTimeMillis() - keepDays * 86_400_000L;
		while (!recent.isEmpty() && recent.peekFirst().t < limit) recent.pollFirst();
	}

	/** Transactions récentes qui correspondent au filtre, la plus récente d'abord. */
	public List<Tx> find(Predicate<Tx> filter, int max) {
		List<Tx> out = new ArrayList<>();
		var it = recent.descendingIterator();
		while (it.hasNext() && out.size() < max) {
			Tx tx = it.next();
			if (filter.test(tx)) out.add(tx);
		}
		return out;
	}

	public Iterable<Tx> recent() {
		return recent;
	}
}
