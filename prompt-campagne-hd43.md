# Campagne HD_43 — tout ce qui reste faisable sur impos

## 1. Contexte

Tu travailles sur la suite de caisse Intermarché, quatre dépôts voisins :

| Dépôt | Rôle | Paquet racine |
|---|---|---|
| `/Users/henridarmet/Documents/Workspaces/impos` | caisse (TPV) | `com.intermarche.pos` |
| `/Users/henridarmet/Documents/Workspaces/imedge` | nœud magasin / central + Back Office | `com.intermarche.edge` |
| `/Users/henridarmet/Documents/Workspaces/imvaluation` | moteur de valorisation | `com.intermarche.valuation` |
| `/Users/henridarmet/Documents/Workspaces/impos-hw` | module matériel (pilotes JavaPOS / OPOS) | — |

Stack : Quarkus 3, Qute, Panache, RESTEasy.

Référentiel des exigences : `impos/Questionnaire Fonctionnalites_Encaissem HD_43.xlsx` (version du 28/10/2026). Il contient quatre feuilles :
- Back Office ;
- Ligne de caisses ;
- Libre-Service ;
- Digital.

Colonnes utiles :
- B : code ;
- E : epic ;
- F : fonctionnalité ;
- G : sous-fonctionnalité ;
- H : description ;
- I : priorité ;
- L : cotation ;
- O : commentaire éditeur.

Les colonnes L et O de chaque ligne disent ce qui est fait et ce qui manque (« Écart : … », « À faire : … »).

État au 28/10/2026, P1 :

| Feuille | Intégral | Partiel | Non couvert |
|---|---|---|---|
| Back Office | 685 | 19 | 0 |
| Ligne de caisses | 460 | 5 | 12 |
| Libre-Service (hors CLS) | 84 | 71 | 131 |
| Digital | 26 | 0 | 0 |

## 2. Objectif

Amener à « 1. Couvert intégralement » toutes les lignes faisables dans ces dépôts.

Le périmètre comprend toutes les lignes P1, P2 et P3 des feuilles Back Office, Ligne de caisses et Libre-Service qui remplissent deux conditions :
- L n'est pas « 1. Couvert intégralement » ;
- O ne commence pas par « Hors périmètre ».

Cela fait 504 lignes :
- Back Office : 22 ;
- Ligne de caisses : 117 ;
- Libre-Service : 365.

Extrais-les toi-même du classeur, puis retire les exclusions du §4.

Corrige aussi les défauts connus :
- `POST /valuation` (imvaluation) répond 500 pour un article inconnu sans prix poussé et pour un magasin inconnu. Il doit répondre 422, avec un message qui nomme l'EAN ou le magasin.
- Un second retour partiel d'une même commande e-commerce ignore les retours précédents : le snapshot de clôture ne tient pas compte des quantités déjà rendues. imedge doit transmettre les retours antérieurs à `POST /returns/valuation`, et le moteur doit refuser une quantité qui dépasse le reste à rendre.
- impos et impos-hw ne sont pas strictement compatibles :
  - six routes appelées par impos n'existent pas dans impos-hw : `/configuration`, `/slip/print`, `/printer2/print`, `/cashchanger/dispense`, `/cashchanger/replenish`, `/cashchanger/inventory` ;
  - les directives BOLD et DOUBLE du corps de ticket ne sont pas interprétées par `ReceiptBody` d'impos-hw.
  
  Aligne impos-hw sur ce qu'impos envoie : routes, contrats JSON, directives.

## 3. Règles non négociables

### Exécution
- **Jamais de prod.**
  - Profil de construction `dev`, H2 en mémoire avec `drop-and-create`, matériel simulé (`MockHardwareResource`, `DataInitializer`).
  - Aucune migration incrémentale, aucun durcissement, aucune persistance « au cas où ».
- **Ne pose aucune question de confirmation.** Prends la lecture la plus raisonnable d'une exigence, écris-la dans le commentaire O, et avance.
- **Un défaut rencontré se corrige**, avec un test, même hors de la ligne en cours. La portée stricte vaut pour les améliorations, pas pour les défauts.
- **Git :**
  - un commit par lot et par dépôt touché ;
  - jamais de push, jamais de branche.

### Architecture
- **Valorisation sans état.** imvaluation ne conserve jamais de panier. Le canal qui vend (la caisse, ou la plateforme e-commerce) garde le panier et sa photo de clôture.
  - Le moteur valorise par `POST /valuation`.
  - La consommation est déclarée par `POST /consumptions` (SALE / CANCELLATION).
  - Aucune entité Panache « panier » dans imvaluation.
  - imedge ne garde que les enregistrements du magasin : commandes, tickets, remboursements.
- **Classes copiées.** Une classe présente dans impos et dans imedge au même chemin relatif doit être identique, à la ligne `package` près. Exceptions : les classes e2e `Group*IT`.
  - Toute modification d'une copie se reporte dans l'autre.
  - Vérifie-le en fin de lot par un diff des paires.
- **Paramètres administrés.** Ils vont au catalogue `PosSettingsService`, dans les deux copies, avec leurs lignes dans `AdminSettingsResourceTest`. Ils sont regroupés dans un bloc `// ---- HD_43 lot <n> parameters ----`.
- **Libellés d'écran :** bundles fr, nl et pt, dans un bloc `# ---- HD_43 lot <n> ----`.
- **Paquets.**
  - Jamais de paquet à une seule classe.
  - `com.intermarche.pos.service` ne reçoit que les services généraux.
  - Un service qui injecte `PosState` ou qui sert un flux d'écran va dans `ui.*`.
- **Libre-service.** La caisse libre-service (CLS : interface client de la caisse automatique, blocs LS-01 à LS-04) est hors du périmètre actuel. Sur ces lignes :
  - ne construis pas le mode CLS ;
  - ferme seulement la part générique de l'écart (caisse fixe, Back Office, supervision, journal) ;
  - si l'écart restant n'est plus que le mode CLS, la ligne reste « 1a » et O le dit.
  
  Les blocs LS-05 à LS-12 sont dans le périmètre :
  - self-scanning (borne, scannette, contrôles, reprise en caisse, Back Office) ;
  - mobilité opérateur ;
  - borne prix ;
  - autres services.

### Code
- Code et commentaires en anglais.
- **Javadoc sur toutes les méthodes sans exception** : constructeurs, getters et setters, méthodes privées, callbacks, méthodes redéfinies, méthodes de test.
- Tests unitaires : JUnit 5 + Mockito, sans `@QuarkusTest`, assertions `org.junit.jupiter.api.Assertions`.
  - Couvre chaque jambe de chaque expression booléenne composée, et les deux bras de chaque garde de nullité.
  - Lance les tests des classes touchées.
  - Ne lance pas de `mvn verify` complet et ne poursuis pas la jauge de couverture : le testing global est géré à part.
  - Un test cassé, même préexistant, se répare.
- Le `CLAUDE.md` d'impos décrit une session de génération de tests (« never modify src/main »). Il ne s'applique pas ici, sauf ses règles de style de test.

## 4. Exclusions

Ces lignes ne sont pas faisables dans ces dépôts. Laisse leur cotation et précise l'écart dans O s'il est flou.

| Ce qui manque | Lignes |
|---|---|
| **Protocoles monétiques des prestataires (module matériel / spécifications absentes)** | LC-01-01-06, LC-01-01-07 (n° de série et maintenance Verifone) ; LC-05-06-04, -09 ; LC-07-01-05 ; LC-07-04-02, -03, -05, -06, -09, -14, -15, -16, -17, -18 ; LC-07-07-02, -05 ; LC-07-08-01 ; LC-08-05-08, -14 ; BO-03-09-19, -29, -30, -31 ; LS-09-01-02, -04, LS-09-06-11 (TPE Android) |
| **Pilotes matériels sans matériel ni spécification** | lecteur de chèque (LC-07-05-01, -02) ; lecteur RFID (LC-01-01-05, LC-01-02-05, LC-01-05-05) ; balance DIGI, couche OPOS Datalogic et réglages d'imprimante (BO-03-09-15, -17, -21) ; commandes monnayeur sans équivalent JavaPOS (LC-13-01-04) ; berceaux et scannettes physiques (LS-08-07-07, -08) |
| **imfid (dépôt absent)** | adresse du porteur (LC-03-04-18, -20) ; PIN de décagnottage sur TPE ou afficheur (LC-07-10-06, -07 ; LS-01-06-19, -20 ; LS-02-04-24) ; modification du client fidèle (LS-11-02-03) |
| **Formats d'éditeurs non disponibles** | Catalina (BO-11-01-07) ; TFREMA/DS (BO-11-02-01) ; Anaveo (BO-11-02-04) ; EEG SES/Hanshow (BO-02-03-37, -38) ; Restoflash (LC-07-06-03, -06, -07) ; titre prépayé papier par le hub (LC-07-12-03) ; produits dématérialisés wynid/Prosodie/ATOS/NEXO (BO-03-11-05, -06) |
| **Choix ou outil tiers** | lancement d'un exécutable local, refusé pour la sécurité (BO-03-09-27) ; prise en main de l'écran à distance (BO-08-04-17) ; contrôle à la dépose consommé par une CLS ou une balance de sécurité (BO-02-03-24, -25) |

Une ligne exclue dont l'écart contient aussi une part faisable (écran, paramètre, journal, simulateur) : fais cette part, et la ligne reste « 1a ».

Toute ligne non listée ici est à faire. Si, à l'analyse, une ligne non listée se révèle bloquée par un pilote ou une spécification d'éditeur absente :
- fais tout ce qui est faisable ;
- écris l'écart précis dans O ;
- signale-la dans le rapport.

## 5. Découpage en lots

Fais des lots conséquents, dans cet ordre.

1. **Défauts connus.** Les trois défauts du §2 : erreurs 422 d'imvaluation, retours partiels successifs, alignement impos-hw.

2. **Back Office et session.**
   - Changement de PIN imposé à la première prise de poste en caisse (BO-01-02-06, -11).
   - Choix de la seconde imprimante par document en caisse (BO-03-09-22).
   - Audit des mises à jour article par article (BO-10-08-23).
   - Lignes LC-01 et LC-13 restantes faisables, dont l'échange de monnaie par le monnayeur JavaPOS (LC-13-01-03).

3. **Visu client (LC-10).**
   - Mention d'éligibilité et total éligible.
   - Avantages, nombre de lignes, remises permanentes.
   - Message permanent, médias selon l'état de la caisse.
   - Code pays et NIF, ligne sous-total.

4. **Visu caisse, articles et ticket (LC-02, LC-08 et LC-09).**
   - Code interne et lettre de TVA, sur l'écran et sur le ticket.
   - Groupes d'articles : filtre A-Z, défilement, prix au kilo, tailles.
   - Données enrichies, articles complémentaires, quantités décimales et regroupement.
   - Annulation par code interne.
   - Disposition gaucher/droitier, libellés de touches, clavier à la demande.
   - Deux tickets en parallèle.

5. **Transactions, retours et paiements (LC-03 à LC-05, LC-07).**
   - Vignettes, recharge de carte cadeau par le hub Nexo existant, contrôle du type de carte sociale.
   - Retour d'un ticket d'un autre PDV, remboursement d'un ticket négatif par tout moyen (LC-05 P1).
   - Portugal : FT avec indicateur permanent, arrondi HT, blocage sans ATCUD, NIF étranger et NIF du client fidélisé.
   - Explication du calcul des promotions, avec sa part moteur dans imvaluation.

6. **Tiroir, maintenance et échanges externes (LC-06, LC-11, LC-12).**
   - Ouverture d'urgence du tiroir avec alerte au Back Office, contrôle du comptage.
   - Bordereau des règlements, code de référence des dépenses.
   - Statut de session et forçage de disponibilité, indicateur de mise à jour en arrière-plan.
   - Génération d'EAN-13, valeur du code-barres imprimée.
   - Fichiers plats : ticket unique balance, devis et dépôt de devis, bons de vente, self-scanning.

7. **Libre-service, part générique (LS-01 à LS-04).**
   - Supervision : regroupement et filtre par îlot, multi-sélection.
   - Avals tracés avec le ticket et le détail.
   - Alertes bloquantes côté caisse fixe et Back Office.
   - Monnayeur en coupures.
   - Rapports et paramétrages Back Office.
   
   Rien du mode CLS.

8. **Self-scanning, Back Office (LS-05, LS-07, LS-08).**
   - Borne et API d'éligibilité.
   - Paramétrage des contrôles partiel et total (seuils, fourchettes, pourcentages, règles).
   - Estimation des caisses à ouvrir, listes de courses.
   - Sessions de courses (inactivité, abandon).
   - Identification des bornes et des scannettes (identifiants techniques, sans pilote).
   - Clients (nouveau compte, contrôle d'identité à la reprise), supervision, reporting.
   - Reprise en caisse et lutte contre la fraude.

9. **Self-scanning, interface client (LS-06).** Une application web de scannette, servie par le nœud magasin, avec appareil simulé en dev :
   - session ;
   - enregistrement des articles ;
   - affichage de la transaction ;
   - générosité (valorisation par le moteur, panier gardé par la scannette) ;
   - liste de courses ;
   - paiement ou reprise en caisse ;
   - fin de session.

10. **Mobilité opérateur et autres libre-service (LS-09 à LS-12).**
    - Application web d'assistance mobile : session, autorisations, enregistrement, panier virtuel, générosité (solde fidélité par l'API imfid déjà utilisée par la caisse fixe), paiement hors TPE Android, fin de transaction.
    - Paramétrage Back Office de la mobilité.
    - Borne prix, autres services.

Au début de chaque lot, relis dans le code chaque ligne qu'il prend en charge. Une ligne déjà servie et seulement non prouvée passe en RELECTURE (voir §6).

## 6. Chaque lot se termine par

1. **Recette dev réelle.**
   - Lance les services en profil dev : caisse, nœud magasin, et central si une ligne concerne le PDV ou l'adhérent.
   - Joue les parcours à l'écran ou par API.
   - Note les messages et valeurs exactes observés.

2. **Mise à jour du classeur.** Écris L et O de chaque ligne traitée, sans toucher aux autres cellules ni aux styles.
   - **L** : « 1. Couvert intégralement » ou « 1a. Couvert partiellement ».
   - **O, ligne intégrale :** « Couvert par CODE (jj/mm/aaaa), code. » puis 2 à 4 phrases : ce qu'administre le Back Office (clés, écrans) et ce que fait la caisse ou le nœud. Termine par « Tests Java ; recette dev : <preuve exacte> ».
   - **O, ligne déjà servie avant le lot :** « Couvert par RELECTURE (jj/mm/aaaa) » au lieu de CODE.
   - **O, ligne partielle :** « Partiel (jj/mm/aaaa) : <ce qui est fait>. Écart : <ce qui manque, la raison précise>. »
   - O fait moins de 900 caractères et contient uniquement des faits.

3. **Section e2e en français** ajoutée à `imedge/e2e-scenarios.md`, sur le modèle des sections HD_43 existantes :
   - titre ;
   - gain ;
   - défauts corrigés ;
   - préconditions ;
   - étapes numérotées par thème, avec codes, actions et messages attendus ;
   - limites ;
   - date de recette.

4. **Diff des classes copiées** impos ↔ imedge, puis commits.

5. **Rapport de lot :**
   - lignes passées intégrales, lignes restées partielles et leur raison ;
   - défauts corrigés ;
   - fichiers touchés ;
   - **couverture P1 de chaque feuille, intégrale ET partielle, en nombre et en pourcentage** ;
   - même chose pour P2 et P3.

## 7. Fin de campagne

Rapport final :
- tableau par feuille et par priorité (intégral, partiel, non couvert, hors périmètre), avec l'avant et l'après ;
- liste des lignes restées non intégrales, chacune avec sa raison en une ligne.
