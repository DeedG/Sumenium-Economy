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
   - `/faction admin importer-equipes` : crée une faction par équipe vanilla qui contient de vrais joueurs. Les équipes des datapacks (BACAP `bac_team_*`, AFK) sont ignorées et jamais modifiées.
   - `/faction admin role <joueur> chef` : un chef par faction.
6. Vérifier les prix du FISC (ex. cuivre deepslate à 3,5) : regarder le coffre et `/fisc prix 3.5`.

Le jar compilé et testé est publié automatiquement sur la branche [`builds`](https://github.com/DeedG/Sumenium-Economy/tree/builds) à chaque modification. On peut aussi le compiler avec `gradle build` (Java 25).

Chaque build démarre deux vrais serveurs 26.3 (sans et avec Lithium) qui jouent un scénario complet avec de faux joueurs : création de boutique, achat, rachat FISC, explosion, entonnoir, double coffre, sommeil des AFK, reprise Savs.

## Joueurs

| Commande | Effet |
| --- | --- |
| `/compte` | Solde perso, banque de faction et compte utilisé |
| `/compte perso` · `/compte faction` | Choisir le compte utilisé pour acheter et créer des boutiques (faction : chef et trésoriers) |
| `/historique [page]` | Ventes, achats, frais, dépôts (30 derniers jours) : montant sur ton compte et sur la banque de ta faction |
| `/baltop [page]` · `/baltop factions [page]` | Classement des joueurs ou des banques de faction |
| `/boutique` | Aide des commandes de boutique |
| `/boutique creer item <prix> <quantité>` | Regarder un coffre ou un tonneau, l'item à vendre en main. Le prix est pour la quantité (ex. `20 16` = 16 items pour 20 Su) |
| `/boutique creer service <prix> <nom du bon>` | Service à stock infini : l'acheteur reçoit un bon nommé |
| `/boutique info` · `liste` · `supprimer` · `prix <prix> [quantité]` | Gérer ses boutiques (supprimer rembourse 50 % des frais) |
| `/boutique depot <service>` · `/boutique depot retirer` | Le coffre visé devient le dépôt d'un de tes services : un bon valide déposé est consommé à la fermeture et le coffre émet un signal de redstone |
| `/boutique panneau` | Réécrit le panneau, en reprend un accroché au coffre ou en pose un neuf |
| `/boutique faction` · `/boutique perso` | Donner sa boutique à sa faction (tous les membres ravitaillent) ou la reprendre |
| `/bon verifier` · `/bon valider` | Vérifier un bon en main / le valider (vendeur du service) |
| `/faction` · `info <faction>` · `liste` | Voir les factions |
| `/faction inviter` · `rejoindre` · `quitter` · `exclure` · `role` | Gestion par le chef |
| `/faction deposer <montant>` · `retirer <montant>` | Banque de faction (retrait : chef et trésoriers) |

Les montants s'écrivent avec un point : `/boutique creer item 3.5 16`.

**Acheter** : clic droit sur le coffre ou sur son panneau. Le chat affiche l'item (survolable), le prix et le stock ; on tape ensuite la quantité voulue dans le chat (`32`, `tout` ou `annuler`, 30 s pour répondre). Ce message n'est pas envoyé aux autres joueurs.
**Vendre au FISC** : pareil, clic sur le coffre du FISC puis la quantité à vendre (ou `tout`).
**Ravitailler** : le propriétaire et les membres de la faction ouvrent le coffre normalement ; le panneau et le stock se mettent à jour à la fermeture.

**Redstone** : à chaque vente, le coffre de la boutique émet un signal de redstone d'une seconde (poudre, répéteur, comparateur ou lampe collés au coffre). Réglable avec `signalVenteTicks`.

**Panneau** : un panneau posé sur le coffre (contre une face, au-dessus, ou suspendu) est rattaché automatiquement : il affiche le propriétaire, l'item, le prix et le stock, et il est ciré pour que personne ne le modifie. Les panneaux des anciennes boutiques Savs sont repris de la même façon.

**Frais de création** : 150 Su pour une boutique d'item, 50 Su si le même propriétaire (joueur ou faction) a déjà une boutique de la même famille (toutes les couleurs de laine, toutes les essences de bois, etc.), 300 Su pour un service. La moitié est remboursée quand la boutique est supprimée.

**Bons de service** : `/bon verifier` montre si un bon est authentique et déjà utilisé. `/bon valider` (vendeur) marque le bon comme utilisé et le retire, pour qu'il ne serve pas deux fois. C'est facultatif : pour une ferme en libre service, un coffre de dépôt suffit.

Les boutiques sont protégées : seuls les membres ouvrent et cassent ; entonnoirs, wagonnets à entonnoir, golems de cuivre et explosions n'y touchent pas ; personne ne peut coller un coffre pour faire un double coffre.

## Hôtel des ventes (V2)

| Commande | Effet |
| --- | --- |
| Clic droit sur le PNJ | Menu des annonces ; clic sur un item puis l'offre dans le chat (`achat` = achat immédiat) |
| `/hdv vendre <prix de départ> [achat immédiat]` | Près du PNJ, met en vente le stack tenu en main (7 jours) |
| `/hdv annonces` · `/hdv annuler <n°>` | Ses annonces ; retirer une annonce sans offre |
| Clic droit sur la boîte aux lettres | Récupérer ses items gagnés ou invendus |

Admins : `/hdv pnj [retirer]`, `/hdv boite [retirer]`, `/annuaire lutrin|retirer`. Réglages : `hdvAnnoncesMax` (3), `hdvDureeJours` (7), `hdvTaxe` (0.15), `hdvSurenchereMin` (0.05), `hdvProlongationMinutes` (5).

## Admins

| Commande | Effet |
| --- | --- |
| `/fisc rachat <prix unitaire>` | Le coffre visé devient un point de rachat du FISC pour l'item en main |
| `/fisc vente <prix> <quantité>` | Le FISC vend l'item en main (stock infini), ex. hopper filtre |
| `/fisc prix <prix>` | Changer le prix d'une boutique du FISC |
| `/eco voir\|donner\|retirer\|definir <joueur> [montant]` | Comptes joueurs |
| `/eco faction <faction> donner\|retirer\|definir <montant>` | Banques de faction |
| `/faction admin creer <id> <tag> <couleur>` · `supprimer` · `ajouter` · `retirer` · `role` · `tag` · `couleur` | Factions |
| `/sumeco bypass` | Mode gestion : ouvrir et casser toutes les boutiques (à réactiver après chaque redémarrage) |
| `/sumeco stats` · `exporter` · `recharger` · `importer-savs` | Outils |

Par défaut, un admin est un joueur comme les autres (achats, notifications de ses propres boutiques seulement). `/sumeco bypass` active le mode gestion pour ouvrir et casser n'importe quelle boutique, et le désactive.

## Configuration

`config/sumenium-economy.json`, rechargeable avec `/sumeco recharger`. Principales options :

- `fraisBoutiqueItem` (150), `fraisBoutiqueMemeFamille` (50), `fraisBoutiqueService` (300), `remboursementSuppression` (0.5)
- `taxeVente` : 0 = désactivée ; 0.05 = 5 % prélevés par le FISC sur chaque vente entre joueurs
- `prixDynamiquesMode` : `aucun` (défaut), `joueur` ou `faction` ; avec `prixDynamiquesBaisseParLot`, `prixDynamiquesTailleLot`, `prixDynamiquesPlancher`, `prixDynamiquesRetourMinutes`
- `factionMembresMax` (8), `factionCreationParJoueurs` (false), `factionMembresPeuventDepenser` (false)
- `afkSecondes` (150), `afkIgnoresPourDormir` (true) : les AFK ne comptent pas pour la gamerule de sommeil

## Fichiers pour le site

Tout est dans `sumenium-data/` à la racine du serveur, à récupérer en SFTP :

- `export/boutiques.json` : toutes les boutiques avec coordonnées, item, prix, stock, rupture, ventes, chiffre d'affaires
- `export/factions.json`, `export/comptes.json`, `export/prix-fisc.json`
- `export/stats.json` : argent en circulation, totaux des 7 derniers jours, top des ventes, rachats FISC
- `export/masse-monetaire.jsonl` : un point par heure pour tracer la courbe de l'argent en circulation
- `export/hdv.json` : annonces de l'hôtel des ventes
- `export/bluemap-markers.json` : boutiques, FISC et hôtel des ventes au format des marqueurs BlueMap
- `journal/AAAA-MM-JJ.jsonl` : chaque transaction, une par ligne
- `data/` : données internes du mod (ne pas modifier serveur allumé)

Les exports sont réécrits toutes les 5 minutes et après chaque création ou suppression de boutique.
