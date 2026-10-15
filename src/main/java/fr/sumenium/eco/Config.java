package fr.sumenium.eco;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configuration du mod : config/sumenium-economy.json.
 * Les montants sont en sumeniens (Su), avec 2 décimales maximum.
 */
public final class Config {

	public String symbole = "Su";

	// --- Boutiques ---
	/** Frais de création d'une boutique d'item. */
	public double fraisBoutiqueItem = 150;
	/** Frais si le propriétaire a déjà une boutique de la même famille (laines, bois…). */
	public double fraisBoutiqueMemeFamille = 50;
	/** Frais de création d'une boutique de service. */
	public double fraisBoutiqueService = 300;
	/** Part des frais remboursée quand la boutique est supprimée (0.5 = 50 %). */
	public double remboursementSuppression = 0.5;
	/** Taxe du FISC sur chaque vente entre joueurs (0 = désactivée, 0.05 = 5 %). */
	public double taxeVente = 0.0;

	// --- Prix dynamiques du FISC (rachat) ---
	/** "aucun", "joueur" ou "faction". */
	public String prixDynamiquesMode = "aucun";
	/** Baisse appliquée par lot vendu (0.02 = 2 %). */
	public double prixDynamiquesBaisseParLot = 0.02;
	/** Taille d'un lot en items. */
	public int prixDynamiquesTailleLot = 64;
	/** Prix minimum en part du prix de base (0.4 = 40 %). */
	public double prixDynamiquesPlancher = 0.4;
	/** Temps pour que le prix revienne entièrement à la normale (minutes). */
	public int prixDynamiquesRetourMinutes = 120;

	// --- Factions ---
	public int factionMembresMax = 8;
	/** Les joueurs peuvent créer une faction eux-mêmes (sinon admins seulement). */
	public boolean factionCreationParJoueurs = false;
	/** Les simples membres peuvent dépenser l'argent de la faction. */
	public boolean factionMembresPeuventDepenser = false;
	/** Gérer les équipes vanilla (tag dans le tab, le chat et au-dessus de la tête). */
	public boolean factionSynchroEquipes = true;

	// --- AFK ---
	public boolean afkActif = true;
	/** Secondes sans bouger avant de passer AFK. */
	public int afkSecondes = 150;
	/** Les joueurs AFK ne comptent pas pour passer la nuit. */
	public boolean afkIgnoresPourDormir = true;

	// --- Export ---
	/** Fréquence d'écriture des fichiers d'export (minutes). */
	public int exportMinutes = 5;
	/** Nombre de jours de journal gardés en mémoire pour /historique. */
	public int historiqueJours = 30;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	public static Config load(Path file) {
		Config cfg = new Config();
		try {
			if (Files.exists(file)) {
				cfg = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Config.class);
				if (cfg == null) cfg = new Config();
			}
			// réécrit pour ajouter les nouvelles clés éventuelles
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(cfg), StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException e) {
			SumeniumEco.LOGGER.error("Impossible de lire la config {}, valeurs par défaut utilisées", file, e);
		}
		return cfg;
	}

	public void save(Path file) {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException e) {
			SumeniumEco.LOGGER.error("Impossible d'écrire la config {}", file, e);
		}
	}

	public boolean dynamicEnabled() {
		return prixDynamiquesMode != null && !prixDynamiquesMode.equalsIgnoreCase("aucun");
	}
}
