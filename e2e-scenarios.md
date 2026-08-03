# Catalogue des scénarios e2e — POS Intermarché

Couche complémentaire des tests unitaires (agent local, sans H2) : ici l'application
réelle démarre, la base H2 vit, le simulateur matériel pilote scanner/balance/TPE/tiroir.
Infrastructure par groupe : **[S]** = caisse seule + simulateur ; **[V]** = + valorisateur
sur :8090 (seed miroir) ; **[N]** = + second nœud en rôle store (consolidation).

Convention : chaque scénario = parcours → attendus vérifiables (écran, base, log, impression).

## A. Prise de poste & authentification [S]

- **A1 Prise de poste nominale** : badge `12341234` → PIN → tiroir s'ouvre → écran SESSION
  (aucune session) → fonds 200 € → OUVRIR → écran de VENTE. Session C04-Sxxxxx OPEN en base.
- **A2 Reverrouillage en journée** : session ouverte, LOCK → badge+PIN → retour direct VENTE
  (pas de détour session).
- **A3 Verrouillage PIN** : 3 PIN faux → compte verrouillé 5 min (message), badge+PIN bon
  refusé pendant le lockout, accepté après expiration. Compteur remis à zéro au succès.
- **A4 Badge sur écran de lock** : scan badge → login prérempli (boîte aux lettres one-shot).
- **A5 Badge en session ouverte, sans modale** : ignoré — pas de changement d'opérateur
  automatique.
- **A6 Changement de PIN** : ancien faux → refus (hors compteur partagé) ; nouveau ≠
  confirmation → refus ; succès → reconnexion avec le nouveau.
- **A7 Logout** : panier en cours + logout → panier mémoire abandonné, draft intact en base
  (recouvré au prochain login — croiser avec K1).

## B. Vente — scan & saisie [S]

- **B1 Scan EAN catalogue** : ligne créée, prix/TVA snapshotés ; re-scan même EAN → fusion
  (qté 2, une ligne).
- **B2 Refus de fusion** : ligne remisée, puis re-scan du même EAN → deux lignes ; idem
  après changement de prix catalogue entre deux scans (prix différent).
- **B3 PLU tapé** (`4020`) → qté 1 au prix courant, ligne pesée (jamais fusionnée).
- **B4 Pesée FRUITS** : poids simulateur 1,250 kg → tap 🍎 → ligne 1,250 × prix/kg ;
  poids nul/absent → refus propre.
- **B5 Étiquette 2x poids** (prefix 23-26) : ligne au poids embarqué, prix catalogue ;
  checksum invalide → chute silencieuse vers handlers suivants → CODE INCONNU.
- **B6 Étiquette 2x prix** (21-22) : ligne au prix embarqué ; re-scan du MÊME sticker →
  refus anti-double-scan ; redémarrage → re-scan accepté (limite assumée).
- **B7 Code inconnu** → message transitoire CODE INCONNU, effacé au scan suivant.
- **B8 Produit interdit à la vente** → refus avec message, aucune ligne.
- **B9 SAISIE DIRECTE** : racine = familles de tête uniquement (pas de doublon de branche),
  drill jusqu'à un EAN, ajout avec quantité ; article inconnu au prix tapé → ligne sans EAN.
- **B10 Recherche** : « pomm » → hits avec prix ; tap → même chaîne que le scan (ligne
  pesée pour un produit à PLU) ; recherche sans résultat → état vide propre.
- **B11 Déconsigne** : scan bon 298… → ligne négative TVA 0, non fusionnable ; le même
  scan PENDANT un paiement → ignoré (une consigne est une ligne de vente).
- **B12 Annulation de ligne** : sélection + annuler → ligne retirée, total recalculé,
  draft resynchronisé.
- **B13 QUANTITÉ** : modale directe (sans avenant), 1-999, refusée sur ligne pesée/négative.
- **B14 Annulation de ticket** : avenant manager → draft CANCELLED en base, écran vierge.

## C. Gestes de prix sous avenant [S]

- **C1 REMISE €** : demande → modale avenant → PIN manager → appliquée (label + delta) ;
  remise > total de ligne → plancher 0.
- **C2 DISCOUNT %** : 0 %, 100 %, 101 % (refus), arrondi au centime vérifié sur l'écran
  ET le ticket imprimé.
- **C3 FORÇAGE prix** : nouveau total de ligne → prix unitaire recalculé (total/qté).
- **C4 Avenant refusé** : PIN faux ×3 → compte manager verrouillé (compteur partagé
  avec le login) ; annulation de la modale → le geste parqué reste inerte, rien n'est
  appliqué.
- **C5 Badge manager sur la modale** : précédence sur l'écran (A4 inversé) — badge rempli,
  PIN, application.
- **C6 Geste sur ligne déjà modifiée** : second geste remplace le premier (label et
  structuré cohérents), la fusion reste interdite.

## D. Fidélité [S]

- **D1 Scan carte en cours de panier** → attachée (affichage), persiste sur le draft,
  survit à un redémarrage (croiser K1).
- **D2 Saisie manuelle** (fallback) → même effet ; deuxième carte → la dernière gagne.
- **D3 Carte sur écran de lock** → inerte.

## E. Valorisation continue [V]

- **E1 2FOR1 en direct** : scanner `3300000000001` deux fois → le total panier baisse AU
  DEUXIÈME SCAN (sans passer par PAYER) ; log `Valorisation moteur` à chaque mutation.
- **E2 Bundle multi-taux** : café (20 %) + biscuits (5,5 %) → montants par ligne au
  centime, ventilation TVA du ticket clos = taux snapshotés caisse (jamais le taux mélangé).
- **E3 Gestes en données** : remise/discount/forçage sur ligne éligible → le moteur
  ré-applique, totaux caisse et moteur égaux au centime (log de divergence silencieux).
- **E4 Portions multiples** : une ligne qté 3 revenant en 2+1 → agrégée, un seul delta.
- **E5 Panier mixte** : ligne inconnue (sans EAN) + éligibles → valorisation partielle,
  la ligne locale s'additionne hors moteur.
- **E6 Panier 100 % inéligible** : article inconnu seul → statut LOCAL, DEBUG « aucune
  ligne éligible », zéro appel moteur.
- **E7 Disjoncteur** : tuer le moteur en plein panier → scan suivant = bascule locale
  SANS timeout perceptible, totaux revenus au catalogue, plus d'appel pendant 10 s,
  reprise automatique au redémarrage du moteur.
- **E8 Entrée en paiement** : revalorisation fraîche, bannière verte AVANTAGES avec le
  montant ; moteur mort → bannière rouge VALORISATION INDISPONIBLE, la vente continue.
- **E9 Annulation des paiements** : retour panier = revert (totaux locaux, trace
  effacée), revalorisation immédiate puis à la ré-entrée.
- **E10 Upsell** : panier à un article du pack → bandeau ambre SUGGESTION sur pay avec
  le libellé produit (pas l'EAN) ; ajout de l'article → suggestion disparaît, bundle
  appliqué.
- **E11 Plafond titre-restaurant** : assiette MEAL_VOUCHER 4,20 € → paiement TR de 10 €
  plafonné à 4,20 (afficheur TR PLAFONNE) ; second TR → refus assiette épuisée ;
  panier sans advantage → TR non plafonné (comportement historique).
- **E12 Formation** : mode formation actif → AUCUN appel moteur (logs du moteur vierges).

## F. Paiement & clôture [S] ([V] pour F7)

- **F1 Espèces exactes** → complétion ; trop-perçu → rendu calculé affiché + afficheur
  client, tiroir ouvert.
- **F2 Multi-paiements** : CB partielle + espèces solde → deux entrées, restant dû juste
  à chaque étape.
- **F3 TPE virtuel** : demande CB → overlay TPE, accept simulateur → enregistrée ;
  refuse → panier intact ; ANNULER caisse pendant l'attente → demande retirée (409 côté
  simulateur ensuite).
- **F4 Bons** : bon encodé scanné (montant décodé du numéro), Catalina (montant manuel),
  bon générique ; motif inconnu → erreur propre ; bon > restant dû → comportement de
  plafonnement vérifié.
- **F5 Chèque et TR** → tiroir ouvert (rangement), théorique espèces inchangé.
- **F6 Arrondi solidaire** : toggle pendant le paiement → ligne don TVA 0 au prochain
  euro, re-toggle → retirée ; avec valorisation active → total juste (ajustement
  constant).
- **F7 Complétion, contrat des deux boutons** : IMPRIMER → ticket imprimé, draft
  ENCORE OPEN en base ; TERMINER → moment fiscal (CLOSED, numéro, signature) ;
  TERMINER direct sans IMPRIMER → même clôture.
- **F8 Après TERMINER** : numérotation séquentielle sans trou, chaîne de signatures
  vérifiable (hash n dépend de n-1), grand total perpétuel incrémenté, ventilation
  TVA = somme des lignes au centime, ticket imprimé complet (gestes, deltas AVANTAGE,
  paiements, rendu, table TVA).

## G. Après-vente [S]

- **G1 Ticket dématérialisé** : QR/lien /t/{id}/{clé} → page publique du ticket CLOS ;
  mauvaise clé → 404 indistinct ; ticket encore OPEN → refus ; capture email →
  DIGITAL_TICKET_SENT journalisé.
- **G2 Écran client** : fenêtre /customer suit les phases accueil → panier (lignes en
  direct) → paiement (restant, rendu) → merci (lien + QR) ; bannière formation ;
  totaux valorisés identiques à la caisse.
- **G3 Réimpression** : historique paginé (6/page), détail paginé, ouverture d'un ticket
  court après un long → page clampée ; IMPRIMER → duplicata NUMÉROTÉ (print_count
  incrémenté, mention duplicata).
- **G4 Parking** : panier parqué → repris depuis la liste → identique (lignes, uids,
  gestes, fidélité, déconsigne) ; parquer pendant un paiement → bloqué ; les PARKED
  meurent au Z (croiser I3).

## H. Retours [S]

- **H1 Retour nominal espèces** : recherche ticket → quantités → avenant CASH →
  remboursement créé, TVA restituée, théorique tiroir diminué, journalisé.
- **H2 Anti-double remboursement** : second retour sur les mêmes lignes → plafonné aux
  quantités restantes (refus au staging ET en transaction — tester les deux en
  parallélisant si possible).
- **H3 Plafond ticket avec montant libre** : montant manuel > restant remboursable →
  refus avec message.
- **H4 Bon de remboursement** : méthode VOUCHER → bon imprimé scannable (≤ 99,99 €),
  encaissable sur une vente suivante (limite connue : pas de registre — l'avoir viendra).
- **H5 Retour en formation** → bloqué (document réel).

## I. Session & clôture Z [S]

- **I1 Rapport X** : à tout moment, lecture seule, imprimable, ne mute rien.
- **I2 Comptage Z** : calculette par coupures (billets/pièces/rouleaux au rouleau
  entier), total transmis en String, écart compté/théorique calculé, prélèvement,
  fonds suivant → session CLOSED, nouvelle session possible.
- **I3 Effets du Z** : PARKED annulés, vente bloquée (message MENU CAISSE) jusqu'à
  réouverture, flux du matin (A1) au prochain login.
- **I4 Z en formation** → bloqué.

## J. Mode formation [S]

- **J1 Entrée/sortie sous avenant** : bannières orange sur tous les écrans (caisse ET
  client), état propre à la sortie.
- **J2 Neutralisation fiscale** : vente sans session, scans OK, AUCUN draft en base,
  paiements simulés, tiroir jamais ouvert, reçu mémoire NON VALABLE.
- **J3 Actions bloquées** : réimpression (impression), création de retour, mutations de
  session — sur les deux chemins (bouton et URL directe).

## K. Reprise & robustesse [S]

- **K1 Crash en plein panier** : kill -9 avec 3 lignes (dont une remisée, une pesée,
  carte fidélité) → redémarrage → panier restauré à l'identique (uids, gestes
  structurés, fidélité), draft réconcilié — pas de doublon.
- **K2 Crash en plein paiement** : après une CB enregistrée → redémarrage → état
  cohérent (draft OPEN, paiements persistés, complétion possible).
- **K3 Matériel dégradé** : imprimante coupée → vente continue + TechnicalEvent ;
  capteur tiroir mort → garde tiroir désactivée (pas de brique) ; afficheur coupé →
  silencieux.

## L. Consolidation magasin [N]

- **L1 Push transactionnel** : vente clôturée → outbox drainé ≤ 10 s → ticket ingéré
  au store (upsert idempotent : rejouer le même push = zéro doublon), ordre
  sessions→tickets→remboursements→events respecté.
- **L2 Dashboard** : CA/tickets/panier moyen/CA par caisse/top 10 rafraîchis ≤ 5 s
  après une vente ; annulation comptée à la création, CA à la clôture.
- **L3 Appel superviseur** : motif choisi en caisse → bandeau dashboard temps réel →
  ACQUITTER → confirmation en caisse (zone message).
- **L4 Pull référentiel** : changer un prix au store → empreinte modifiée → pull ≤ 300 s
  → prix remplacé en caisse ; employé désactivé au store → désactivé en caisse,
  verrouillages locaux préservés.
- **L5 Thème et pull** : préférence choisie à la caisse → survit au pull suivant ;
  employé sans choix → reçoit le seed du store.
- **L6 Caisse coupée du store** : ventes normales, outbox s'accumule, rattrapage
  complet au retour du lien.

## M. Thèmes [S]

- **M1 Cascade** : Marie (préf clair) → écrans clairs partout dès l'unlock ; Jean →
  sombre magasin ; Store.theme=clair + Jean → clair.
- **M2 Sélecteur** : AUTRES → THÈME → CLAIR → rendu immédiat ; DÉFAUT MAGASIN →
  préférence effacée, cascade reprend.
- **M3 Iso-rendu sombre** : capture avant/après passes de thème → zéro différence
  visuelle en sombre (le contrat des 4 passes).
- **M4 Couverture clair** : parcourir TOUS les écrans en clair → aucun bandeau/fond/
  bouton resté sombre (barres, lignes, muted, claviers).

## N. Compléments — les coutures (2e passe d'audit)

- **N1 Bon de paiement scanné en phase panier** [S] : un bon 50…/789… scanné hors
  paiement → chute dans la chaîne → CODE INCONNU (seule la déconsigne 298 vit au
  panier). Vérifie la scission deposit/paiement des deux côtés.
- **N2 Rejeu d'un bon de déconsigne** [S] : le MÊME numéro 298 scanné sur deux ventes →
  accepté deux fois (aucun registre de consignes — limite connue à documenter par le
  test, l'avoir apportera le patron du registre).
- **N3 Mutation du panier pendant un paiement valorisé** [V] : retour à main pendant un
  paiement en cours (URL directe), scan d'un article → la limite documentée (calcul
  local sur la ligne ajoutée) est-elle bien le comportement observé, sans corruption ?
- **N4 Remboursement d'une ligne valorisée** [V] : vendre 3 pommes en 2FOR1 (payées
  2,38), puis retour d'une pomme → QUELLE base de remboursement ? (ligne valorisée vs
  prix catalogue). ⚠ QUESTION DE DESIGN OUVERTE — le test documente le comportement
  actuel en attendant l'arbitrage.
- **N5 Pull référentiel pendant un panier ouvert** [N] : changement de prix au store,
  pull appliqué, la ligne déjà scannée GARDE son snapshot, un nouveau scan du même EAN
  prend le nouveau prix (et ne fusionne pas — prix différent). La preuve vivante du
  moment du snapshot.
- **N6 Fenêtre de prix expirée** [S] : produit dont le prix a une endDate passée →
  scan → comportement de résolution sans prix courant (taux par défaut / refus) vérifié
  et assumé.
- **N7 Mécanique du return-URL tiroir** [S] : tiroir ouvert, GET bloqué → fermer le
  tiroir → retour AU CHEMIN bloqué ; tiroir ouvert, POST bloqué → retour à la PAGE
  D'ORIGINE (referer), jamais de rejeu de la mutation. La subtilité gravée dans
  DrawerCheckFilter, prouvée.
- **N8 Session par caisse, pas par caissier** [S] : session ouverte par A, lock, B
  déverrouille → B vend dans la session de A (comportement assumé du modèle
  mono-caisse) ; le Z impute à la session, pas au caissier.
- **N9 Collision d'identifiants de caisse** [N] : deux caisses avec le MÊME
  pos.terminal.id (le piège POS01 par défaut) → collisions de numéros/chaînes au
  store — test NÉGATIF documentant le symptôme pour le diagnostic terrain.
- **N10 Appel superviseur sans store-url** [S] : caisse autonome, motif choisi →
  échec propre avec message (pas de crash, pas de blocage).

## O. Inventaire exhaustif — messages d'erreur & modales

Relevé PAR LE CODE (grep des canaux d'affichage) : chaque vérification = déclencheur →
texte EXACT → effacement. Périmètre valorisation volontairement non étendu (module dédié) :
ses trois bandeaux sont référencés, pas approfondis.

### O-A. Zone message du ticket (transitoire — effacée au scan suivant) [S]

| Déclencheur | Message exact |
|---|---|
| Scan/vente sans session ouverte | `AUCUNE SESSION OUVERTE - MENU CAISSE` |
| Code non reconnu par la chaîne | `CODE INCONNU: <code>` |
| Scan d'un produit interdit | `PRODUIT INTERDIT À LA VENTE` |
| EAN absent du catalogue (ajout direct) | `PRODUIT INTROUVABLE` |
| PLU tapé inconnu | `PLU INTROUVABLE` |
| Étiquette 2x : PLU du code inconnu | `ARTICLE BALANCE INTROUVABLE (<plu>)` |
| Étiquette 2x prix déjà scannée | `ÉTIQUETTE DÉJÀ SCANNÉE` |
| Pesée FRUITS sans poids / poids nul | `POIDS INVALIDE` |
| Deux pesées consécutives, balance inchangée | `ERREUR POIDS IDENTIQUE` |
| Prix tapé invalide (article inconnu) | `ERREUR PRIX SAISI` |
| Bon 298 au format/checksum invalide | `BON DE CONSIGNE ILLISIBLE` |
| Action de ligne sans sélection | `AUCUNE LIGNE SÉLECTIONNÉE` |
| Ligne sélectionnée disparue (uid périmé) | `LIGNE INTROUVABLE` |
| QUANTITÉ hors bornes | `QUANTITÉ INVALIDE (1-999)` |
| QUANTITÉ sur ligne pesée/négative/modifiée | `QUANTITÉ NON MODIFIABLE SUR CETTE LIGNE` |
| Saisie invalide dans la modale de geste | `VALEUR INVALIDE` |
| TPE virtuel : refus simulateur | `PAIEMENT REFUSÉ PAR LE TPE` |
| Réimpression du dernier ticket en formation | `RÉIMPRESSION INDISPONIBLE EN FORMATION` |
| Action de vente pendant un paiement actif | `TERMINEZ OU ANNULEZ LE TICKET D'ABORD` |
| Appel superviseur sans store-url | `SUPERVISION NON CONFIGURÉE SUR CETTE CAISSE` |
| Appel superviseur : store injoignable | `APPEL SUPERVISEUR IMPOSSIBLE` |
| Appel superviseur : interruption réseau | `APPEL SUPERVISEUR INTERROMPU` |
| Appel superviseur : refus HTTP du store | `APPEL SUPERVISEUR REFUSÉ (<code>)` |
| Confirmation (non-erreur, même zone) | `SUPERVISEUR PRÉVENU` |

Vérifier pour CHAQUE entrée : le message s'affiche, ET s'efface au scan/action suivant
(contrat transitoire).

### O-B. Messages de page (query param / état) [S]

- **Lock** : identifiants faux → `IDENTIFIANTS INCORRECTS` ; compte verrouillé →
  `COMPTE VERROUILLÉ - RÉESSAYEZ PLUS TARD` (persiste pendant le lockout, badge compris).
- **Session** : ouverture en formation → `INDISPONIBLE EN FORMATION` ; échec d'ouverture
  → message `open-failed` rendu.
- **Changement de PIN** : PIN actuel faux → `Code PIN actuel incorrect` ; format →
  `Le nouveau code doit comporter 4 chiffres` ; confirmation ≠ →
  `Les nouveaux codes ne correspondent pas` ; succès → `Code PIN modifié`.

### O-C. Écran retours (staging — persiste jusqu'à correction) [S]

- Quantités nulles partout → `RIEN À REMBOURSER`.
- Ligne au-delà du restant remboursable → `QUANTITÉ DÉJÀ REMBOURSÉE (<détail>)`.
- Montant libre > plafond → `PLAFOND DU TICKET DÉPASSÉ (DÉJÀ REMBOURSÉ : <montant>)`.
- Méthode demandée en formation → `RETOURS INDISPONIBLES EN FORMATION`.
- Ces gardes se re-vérifient EN TRANSACTION : provoquer l'écart entre staging et
  exécution (2e remboursement intercalé) → même message, rollback complet.

### O-D. Bons de paiement (zone bon de l'écran pay) [S]

- Numéro ne matchant aucun type → `Type de bon inconnu`.
- Type MANUAL : numéro invalide → `Numéro non reconnu — vérifiez la saisie`.

### O-E. Modales & overlays — cycle complet de chaque surface [S]

- **Modale de geste (main)** ×4 types (REMISE/DISCOUNT/FORCE_PRICE/QUANTITY) :
  ouverture (PosInput branché), saisie invalide → `VALEUR INVALIDE`, ANNULER → aucune
  trace (geste parqué inerte), VALIDER → avenant (sauf QUANTITY : direct).
- **Modale d'avenant (endorsement)** : PIN manager OK → exécution ; PIN faux →
  `AUTORISATION REFUSÉE` (compteur partagé) ; badge manager → pré-rempli ; action
  inconnue dans la chaîne → `ACTION DE REMBOURSEMENT INCONNUE` (dispatch) ; ANNULER →
  geste abandonné ; opérateur déconnecté entre-temps → `Aucun opérateur connecté`.
- **Overlay TPE (pay)** : apparition à la demande CB (montant affiché), ACCEPT
  simulateur → disparition + paiement ; REFUSE → disparition + `PAIEMENT REFUSÉ PAR LE
  TPE` ; ANNULER caisse → disparition + demande retirée.
- **Modale de complétion (pay)** : apparition à solde nul, contrat des deux boutons
  (IMPRIMER = draft OPEN, TERMINER = clôture), rendu affiché si trop-perçu.
- **Interstitiel tiroir (drawer-error)** : GET bloqué → page + retour AU CHEMIN à la
  fermeture ; POST bloqué → retour au REFERER, jamais de rejeu (croiser N7).
- **Bandeaux overlay** : FORMATION (z-50, tous écrans + client), VALORISATION
  INDISPONIBLE (z-49), AVANTAGES (z-48), SUGGESTION (z-47) — empilement vérifié quand
  plusieurs coexistent (formation + dégradé = formation dessus). Déclencheurs : voir
  E/J, sans extension.

---
62 scénarios + inventaire O (24 messages de zone, 8 messages de page, 5 gardes retours,
2 erreurs de bons, 6 surfaces modales). Priorité d'exécution suggérée : F (l'argent) → E (valorisation) → K
(reprise) → H/I (documents fiscaux) → le reste. Les scénarios [N] demandent le
lancement biface (deux instances, rôles caisse/store) ; les [V] demandent le moteur
seedé miroir.
