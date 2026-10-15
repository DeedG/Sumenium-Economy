# Suménium Economy

Mod Fabric **100 % côté serveur** pour Suménium (Minecraft 26.3) : économie en sumeniens, FISC, boutiques de joueurs et de factions, factions avec banque, AFK et journal complet. Les joueurs n'ont rien à installer.

Il remplace **Savs Common Economy** et le datapack **afk display**.

## Installation et bascule depuis Savs

1. Arrêter le serveur.
2. Garder le dossier `config/savs-common-economy/` (il sert à la reprise), puis retirer `savs-common-economy-*.jar` de `mods/`.
3. Retirer le datapack `afk display` de `world/datapacks/`.
4. Copier `sumenium-economy-x.y.z.jar` dans `mods/` (avec Fabric API, déjà présent).
5. Démarrer le serveur, puis en jeu (op) :
   - `/sumeco importer-savs` : reprend les soldes et les boutiques (les boutiques ADMIN deviennent des boutiques du FISC ; les anciens certificats papier sont ignorés).
   - `/faction admin importer-equipes` : crée une faction par équipe vanilla existante, avec ses membres.
   - `/faction admin role <joueur> chef` : un chef par faction.
   - `/sumeco zone coin1` et `/sumeco zone coin2` à deux coins de la zone commerciale, puis `/sumeco zone activer`.
6. Vérifier les prix du FISC (ex. cuivre deepslate à 3,5) : regarder le coffre et `/fisc prix 3.5`.

Le jar compilé et testé est publié automatiquement sur la branche [`builds`](https://github.com/DeedG/Sumenium-Economy/tree/builds) à chaque modification. On peut aussi le compiler avec `gradle build` (Java 25).

Chaque build démarre deux vrais serveurs 26.3 (sans et avec Lithium) qui jouent un scénario complet avec de faux joueurs : création de boutique, achat, rachat FISC, explosion, entonnoir, double coffre, sommeil des AFK, reprise Savs.

## Joueurs

| Commande | Effet |
| --- | --- |
| `/compte` | Solde perso, banque de faction et compte utilisé |
| `/compte perso` · `/compte faction` | Choisir le compte utilisé pour acheter et créer des boutiques (faction : chef et trésoriers) |
| `/historique [page]` | Ventes, achats, frais, dépôts (30 derniers jours) |
| `/boutique creer item <prix> <quantité>` | Regarder un coffre ou un tonneau, l'item à vendre en main |
| `/boutique creer service <prix> <nom du bon>` | Service à stock infini : l'acheteur reçoit un bon nommé |
| `/boutique info` · `liste` · `supprimer` · `prix <prix> [quantité]` | Gérer ses boutiques (supprimer rembourse 50 % des frais) |
| `/bon verifier` · `/bon valider` | Vérifier un bon en main / le valider (vendeur du service) |
| `/faction` · `info <faction>` · `liste` | Voir les factions |
| `/faction inviter` · `rejoindre` · `quitter` · `exclure` · `role` | Gestion par le chef |
| `/faction deposer <montant>` · `retirer <montant>` | Banque de faction (retrait : chef et trésoriers) |

Les montants s'écrivent avec un point : `/boutique creer item 3.5 16`.

**Acheter** : clic droit sur une boutique pour voir l'offre, second clic dans les 5 s pour acheter. L'item acheté s'affiche dans le chat et se survole.
**Vendre au FISC** : clic droit sur le coffre du FISC avec le minerai en main (le stack), accroupi pour vendre tout l'inventaire.
**Ravitailler** : le propriétaire et les membres de la faction ouvrent le coffre normalement.

Les boutiques sont protégées : seuls les membres ouvrent et cassent ; entonnoirs, wagonnets à entonnoir, golems de cuivre et explosions n'y touchent pas ; personne ne peut coller un coffre pour faire un double coffre.

## Admins

| Commande | Effet |
| --- | --- |
| `/fisc rachat <prix unitaire>` | Le coffre visé devient un point de rachat du FISC pour l'item en main |
| `/fisc vente <prix> <quantité>` | Le FISC vend l'item en main (stock infini), ex. hopper filtre |
| `/fisc prix <prix>` | Changer le prix d'une boutique du FISC |
| `/eco voir\|donner\|retirer\|definir <joueur> [montant]` | Comptes joueurs |
| `/eco faction <faction> donner\|retirer\|definir <montant>` | Banques de faction |
| `/faction admin creer <id> <tag> <couleur>` · `supprimer` · `ajouter` · `retirer` · `role` · `tag` · `couleur` | Factions |
| `/sumeco stats` · `exporter` · `recharger` · `zone …` · `importer-savs` | Outils |

En mode créatif ou accroupi, un admin ouvre n'importe quelle boutique.

## Configuration

`config/sumenium-economy.json`, rechargeable avec `/sumeco recharger`. Principales options :

- `fraisBoutiqueItem` (150), `fraisBoutiqueService` (300), `remboursementSuppression` (0.5)
- `taxeVente` : 0 = désactivée ; 0.05 = 5 % prélevés par le FISC sur chaque vente entre joueurs
- `prixDynamiquesMode` : `aucun` (défaut), `joueur` ou `faction` ; avec `prixDynamiquesBaisseParLot`, `prixDynamiquesTailleLot`, `prixDynamiquesPlancher`, `prixDynamiquesRetourMinutes`
- `factionMembresMax` (8), `factionCreationParJoueurs` (false), `factionMembresPeuventDepenser` (false)
- `afkSecondes` (150), `afkIgnoresPourDormir` (true) : les AFK ne comptent pas pour la gamerule de sommeil
- `zoneActive` et ses coins : boutiques des joueurs seulement dans la zone commerciale

## Fichiers pour le site

Tout est dans `sumenium-data/` à la racine du serveur, à récupérer en SFTP :

- `export/boutiques.json` : toutes les boutiques avec coordonnées, item, prix, stock, rupture, ventes, chiffre d'affaires
- `export/factions.json`, `export/comptes.json`, `export/prix-fisc.json`
- `export/stats.json` : argent en circulation, totaux des 7 derniers jours, top des ventes, rachats FISC
- `export/masse-monetaire.jsonl` : un point par heure pour tracer la courbe de l'argent en circulation
- `journal/AAAA-MM-JJ.jsonl` : chaque transaction, une par ligne
- `data/` : données internes du mod (ne pas modifier serveur allumé)

Les exports sont réécrits toutes les 5 minutes et après chaque création ou suppression de boutique.
