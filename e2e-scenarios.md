# Catalogue des scénarios e2e — POS Intermarché

Couche complémentaire des tests unitaires (agent local, sans H2) : ici l'application
réelle démarre, la base H2 vit, le simulateur matériel pilote scanner/balance/TPE/tiroir.
Infrastructure par groupe : **[S]** = caisse seule + simulateur ; **[V]** = + valorisateur
sur :8090 (seed miroir) ; **[N]** = + second nœud en rôle store (consolidation) ;
**[C]** = + une brique centrale, c'est-à-dire un troisième imedge portant
`pos.role=central`, qui sert au nœud magasin les domaines d'échelon.

Convention : chaque scénario = parcours → attendus vérifiables (écran, base, log, impression).

## A. Prise de poste & authentification [S]

- **A0 Sonde de santé publique** [S] : `GET /q/health` est un endpoint NON applicatif
  servi hors JAX-RS ; il échappe par construction au filtre tiroir (`@DrawerMustBeClosed`)
  et au verrou session/PIN. Avant tout login (caisse verrouillée) il répond `200`
  anonymement ; il reste `200` tiroir OUVERT comme tiroir fermé. Le contrat « sonde
  publique » gravé pour la campagne (porté par `GroupAIT.a0_`).
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
- **A8 Auto-verrouillage par inactivité** [S] : `idleLockoutSeconds` dépassé → journal
  `REGISTER_LOCKED`, opérateur déconnecté, la requête courante atterrit sur /lock ; l'horloge
  d'inactivité est remise à zéro à chaque geste. Le délai s'AGE en base (jamais de sleep).
- **A9 Fermeture / verrouillage du poste** [S] : écran `/close`. Voie code opérateur (`Code
  opérateur incorrect.` si faux) OU forçage superviseur (`Autorisation superviseur refusée.`
  si refusé). Obstacle « tickets en attente » → `Des tickets en attente exigent une
  autorisation superviseur.`, levé par aval. Fermeture par BADGE = la preuve : l'obstacle
  mot de passe est neutralisé (LC-01-02-03). Reçus OUVERTURE / FERMETURE DE CAISSE conditionnés
  par leurs flags (tester ON et OFF) ; fermeture forcée → ligne `FERMETURE FORCÉE — <auteur>`.
- **A10 Fermeture distante back-office** [N] : ordre `CLOSE_SESSION` (exécuté tout de suite)
  et `CLOSE_SESSION_IF_IDLE` (laissé PENDING et rejoué si une vente est en cours, exécuté si
  caisse au repos) tirés du nœud, puis ACK. Registre déjà fermé → ACK quand même (idempotence).
  Ordre de type inconnu → non acquitté (un registre plus récent l'exécutera) ; nœud injoignable
  → réessai au tour suivant, rien de perdu.

- **A11 Verrouillage / déverrouillage (pause)** [S] (EXP-10/12/13) : touche VERROUILLER
  (`/action/lock`) sur un ticket en cours → l'écran affiche `CAISSE VERROUILLÉE`, le ticket
  et l'identité du verrouilleur survivent, le tiroir reste fermé. Le badge du verrouilleur
  est préaffiché ; seul lui déverrouille (code PIN seul) et retombe DIRECTEMENT sur la vente,
  son panier intact — pas de détour session, pas d'impulsion tiroir. Un autre opérateur est
  refusé. Se distingue de la fermeture : `pause()` garde l'opérateur de côté (`lockedOperatorId`).
- **A12 Fermeture impossible pendant une transaction** [S] (EXP-11) : touche FERMER (`/close`)
  avec un ticket en cours → obstacle `IN_TRANSACTION`, message « Une vente est en cours :
  impossible de fermer la caisse », ni forçable ni neutralisable par badge. Panier vidé/mis en
  attente → la fermeture repasse.
- **A13 Message d'accueil administré** [S] (EXP-01) : caisse fermée, l'écran affiche le message
  `session.welcome-message` (défaut `Bonjour : Connectez-vous`) à la place de l'ancien
  « inutilisable ».
- **A14 Forçage du déverrouillage (mot de passe oublié)** [S] (EXP-14) : sur une caisse
  verrouillée, volet SUPERVISEUR → `/action/force-unlock` avec un badge manager → la caissière
  reprend sa session et son tiroir, `passwordChangeRequired` posé, redirection `/pin-change`
  imposée ; un superviseur refusé renvoie `/lock?error=supervisor`.
- **A15 Forçage fin de journée (poste abandonné)** [S] (EXP-96) : sur une caisse verrouillée,
  volet SUPERVISEUR → `/action/force-close` avec un badge manager → fermeture forcée (journal
  `SUPERVISOR_CALLED`, reçu `FERMETURE FORCÉE — poste abandonné — superviseur`), retour `/lock`.
- **A16 Fond théorique à l'ouverture** [S] (EXP-02) : après une clôture Z laissant un report en
  tiroir, l'écran session préaffiche ce report (counted − withdrawn) et l'annonce « Fond
  théorique (report clôture précédente) », au lieu du paramètre `cash.default-opening-float`.

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
  Abandon via `/abandon` : motif administré (`MOTIF D'ABANDON OBLIGATOIRE` si requis vide,
  `MOTIF D'ABANDON INCONNU` hors liste), option `Imprimer le ticket d'abandon`, blocage si un
  règlement partiel subsiste (`REGLEMENT PARTIEL … - ANNULEZ-LE D'ABORD`).
- **B15 Ticket balance comptoir** (préfixe 27) [S] : scan d'un ticket balance → lignes
  intégrées par `BalanceTicketService` ; checksum invalide → chute silencieuse vers l'EAN
  générique ; un refus du service pose déjà son message et n'est jamais recouvert d'`ARTICLE
  INCONNU`.
- **B16 Code GS1 Databar/DataMatrix — article** [S] : payload marqué nommant un article →
  ligne ajoutée (pricing 392n/390n = prix embarqué, 310n = poids × prix/kg, sinon qté portée
  par le code) ; introuvable marqué → `ARTICLE GS1 INTROUVABLE (<code>)`, introuvable NON
  marqué → chute silencieuse ; `PRODUIT INTERDIT À LA VENTE` ; rappel → `ARTICLE EN
  RETRAIT/RAPPEL` ou lot nommé `PRODUIT EN RAPPEL - LOT <lot>` ; périmé bloquant `ARTICLE
  PERIME LE <date>` ; date courte (avertissement, la ligne passe) `DATE COURTE : <date>` ;
  quantité déjà portée par le code + touche quantité armée → `QUANTITÉ DÉJÀ PORTÉE PAR LE CODE
  GS1`.
- **B17 Code GS1 — règlement** [S] : chèque cadeau (GDTI 253) / coupon (GCN 255) hors phase
  paiement → `BON VALABLE EN PHASE PAIEMENT` ; type non paramétré → `CHEQUE CADEAU GS1 NON
  PARAMETRE` / `COUPON GS1 NON PARAMETRE` ; coupon expiré → `BON EXPIRE LE <date>`.
- **B18 Répéter article** : `RÉPÉTER ARTICLE` → +1 sur la dernière ligne unité, notice
  `ARTICLE RÉPÉTÉ : <label>` ; aucun dernier article → `AUCUN ARTICLE À RÉPÉTER` ; ligne pesée
  → `RÉPÉTITION IMPOSSIBLE : ARTICLE EN PESÉE` ; prix embarqué → `RÉPÉTITION IMPOSSIBLE :
  ARTICLE PRIX EMBARQUÉ`.
- **B19 Marquage « À enlever »** (LC-02-08) : toggle sur la ligne (`ARTICLE MARQUÉ À ENLEVER`
  / `MARQUAGE À ENLEVER RETIRÉ`) ou armé pour le prochain (`PROCHAIN ARTICLE À ENLEVER` /
  `MARQUAGE À ENLEVER ANNULÉ`) ; deux articles à destinations différentes ne fusionnent pas.
- **B20 Contrôle d'âge** : article âge-restreint au-dessus du seuil vérifié → modale `CONTRÔLE
  D'ÂGE (<seuil>+)` ; CLIENT MAJEUR → rejoue le code (PLU/EAN/scan), journal
  `AGE_CHECK_CONFIRMED`, seuil mémorisé au max ; REFUSER → `VENTE REFUSÉE - CONTRÔLE D'ÂGE`,
  journal `AGE_CHECK_REFUSED` ; seuil non persisté sur le draft (re-demandé après crash).
- **B21 Invite prix / quantité à saisir** (LC-02-03) : article `priceToEnter` / `quantityToEnter`
  → modale `PRIX À SAISIR` / `QUANTITÉ À SAISIR` ; valeur ≤ 0 → `VALEUR INVALIDE` ; produit
  disparu → `PRODUIT INTROUVABLE` ; qté puis prix chaînés ; ANNULER → `SAISIE ABANDONNÉE`.
- **B22 Quantité armée pour le prochain article** : `POUR LE PROCHAIN ARTICLE` (n) → notice
  `QUANTITÉ <n> — SAISISSEZ L'ARTICLE` ; n ≤ 0 → `QUANTITÉ INVALIDE` ; consommée par la
  prochaine ligne unité ; une ligne pesée l'ignore mais consomme l'armement.
- **B23 Clé EAN13** : contrôle clé activé + 13 chiffres + checksum faux → `CODE EAN INVALIDE`
  (n'atteint PAS le handler « code inconnu »).
- **B24 Étiquette balance en devise non euro** : plage administrée dans une devise non
  paramétrée → `DEVISE <ISO> NON PARAMÉTRÉE`.
- **B25 Tare logicielle par défaut** (LC-14-01-03/06) : `balance.default-tare` = 0,010 kg ;
  balance préréglée à 0,300 kg → tap PLU → la tare est ENVOYÉE à la balance (`GET
  /api/hardware/weight-with-tare?tare=0.010`), la balance renvoie le net 0,290 kg et la
  ligne est enregistrée à 0,290 kg TELLE QUELLE (jamais 0,280 : pas de re-déduction caisse).
- **B26 Tare logicielle article** (LC-14-01-07) : article portant l'attribut `TARE`=0,020 kg ;
  la tare article REMPLACE la tare par défaut (0,020 envoyée, non 0,010) ; net enregistré tel quel.
- **B27 Sélection tare contenant** (LC-14-01-04) : `balance.tare-containers` administrée
  (`BQS:Barquette S:0.035;…`) → sur l'écran FRUITS, tap « Barquette S » (`/tare/select/BQS`) →
  bandeau `TARE : Barquette S (0.035 kg)` ; la tare contenant REMPLACE ponctuellement article
  et défaut ; consommée après une pesée (à re-sélectionner) ; `/tare/clear` la retire.
- **B28 Scan tare contenant** (LC-14-01-05) : `/tare/scan/<code-barre>` d'un contenant
  administré arme la même tare ponctuelle que la sélection.
- **B29 Tare et saisie manuelle du poids** (LC-14-01-08) : ligne pesée existante, tare résolue
  0,100 kg, QUANTITÉ tapée 0,850 kg → la caisse DÉDUIT la tare (seul endroit où elle déduit) →
  ligne à 0,750 kg ; une tare qui ne laisse rien (≥ poids saisi) → `POIDS INVALIDE (0,001-99,999 KG)`.

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
- **C7 Remise / discount TICKET (global)** : `REMISE TICKET` € et `DISCOUNT TICKET` % sous
  avenant → ventilation prorata sur les lignes positives (résidu sur la plus grosse) ; € au-delà
  du plafond → `REMISE SUPERIEURE AU PLAFOND` ; % > cap ou valeur < 0 → `VALEUR INVALIDE` ;
  demandée pendant un paiement → `TERMINEZ OU ANNULEZ LE TICKET D'ABORD`.
- **C8 Gardes d'ouverture / soumission** : type inconnu → `MODIFICATION INCONNUE` ; ticket
  vide → `AUCUNE LIGNE SÉLECTIONNÉE` ; valeur illisible → `VALEUR INVALIDE` ; remises
  désactivées au référentiel → `REMISES DÉSACTIVÉES` ; refus SILENCIEUX des apply (money
  product, discountForbidden, dépassement du plafond de ligne) → aucune mutation, à prouver
  par ABSENCE d'effet, pas par un message.

## D. Fidélité [S]

- **D1 Scan carte en cours de panier** → attachée (affichage), persiste sur le draft,
  survit à un redémarrage (croiser K1).
- **D2 Saisie manuelle** (fallback) → même effet ; deuxième carte → la dernière gagne.
- **D3 Carte sur écran de lock** → inerte.
- **D4 Recherche manuelle (lookup)** : par téléphone / e-mail (exacts) ou nom+prénom (préfixe)
  → liste de cartes ; aucun critère → `SAISISSEZ UN TÉLÉPHONE, UN E-MAIL OU UN NOM` ; service
  non configuré / breaker ouvert → `SERVICE FIDÉLITÉ INDISPONIBLE` ; imfid en mode CRM →
  `IDENTITÉS GÉRÉES PAR LE CRM - RECHERCHE INDISPONIBLE EN CAISSE` ; refus service →
  `RECHERCHE REFUSÉE PAR LE SERVICE FIDÉLITÉ` ; aucun résultat → `AUCUNE CARTE TROUVÉE -
  ESSAYEZ UN AUTRE CRITÈRE` ; plus de 20 correspondances → `TROP DE CORRESPONDANCES - PRÉCISEZ
  LE CRITÈRE (TÉLÉPHONE)`.
- **D5 Attachement d'une carte trouvée** : ACTIVE → attachée (nom porteur affiché seulement si
  le flag l'autorise) ; `RESILIATED` → refus `CARTE RÉSILIÉE - INVITER LE CLIENT À PASSER À
  L'ACCUEIL` (renvoi ACCUEIL) ; `PENDING_ACTIVATION` → attachée + `CARTE EN ATTENTE
  D'ACTIVATION - CAGNOTTE SANS UTILISATION`.
- **D6 Consultation en magasin** : soldes + mouvements traduits (`CAGNOTTE (<règle>)`,
  `UTILISATION EN CAISSE`, `REPRISE SUR RETOUR`, `PÉREMPTION`, …) ; aucune carte attachée →
  `AUCUNE CARTE ATTACHÉE — SCANNEZ OU SAISISSEZ LA CARTE` ; non configuré → `SERVICE FIDÉLITÉ
  NON CONFIGURÉ` ; compte 404 → `CARTE FIDÉLITÉ INCONNUE`.
- **D7 Service dégradé / disjoncteur** : imfid injoignable → affichage sans solde (breaker
  10 s), promotions vidées, la vente est valorisée sans elles ; log dégradé, aucune erreur
  bloquante ; magasin non configuré n'est PAS « indisponible ».
- **D8 Carte déjà scannée** : re-scan sans multi-scan autorisé → `CARTE FIDÉLITÉ DÉJÀ
  SCANNÉE`.
- **D10 Avantages regroupés par type** (imfid réponse A) : une carte dont l'earn porte
  plusieurs types d'avantage → le ticket regroupe les lignes par `advantageType`, ordonne
  les groupes par `advantageTypeOrder` (« Vos avantages produits » avant « Vos avantages
  ticket ») et imprime les libellés reçus tels quels ; une réimpression du journal
  regroupe à l'identique. Entrées sans type (règle antérieure) → liste plate inchangée.
- **D11 Cagnotte prévisionnelle** (imfid réponse B) : carte résolue → le bloc `balances`
  imprime `projectedAfterEarn` sous le libellé administré `fidelity.projected-label`,
  marqué `(PREVISIONNEL)` ; rien n'est crédité avant l'ingestion.
- **D12 Solde après burn** (imfid réponse B) : un paiement cagnotte confirmé → le corps de
  la confirmation est lu et `SOLDE APRES` = `balanceAfter` (jamais recalculé) ; rejeu de la
  confirmation avec `balanceBefore` null → toléré.
- **D13 Projection « non porteur »** (imfid réponse C, BO-03-03-28) : ticket sans carte,
  `fidelity.anonymous-projection` activé → appel `/earn` en `ANONYMOUS` et impression
  « avec la carte, vous auriez gagné … » (pas de solde). Réglage désactivé → rien
  d'anonyme n'est demandé ni imprimé (flux nominal strictement inchangé, pas de champ
  `projectionMode` envoyé).
- **D14 Promotion carte remise immédiate** (addendum promotions §3.1) : carte 2990000000019
  + café 3300000000004 (AMOUNT 0,50 €/unité) → remise `value × unités` (pesée = 1) sur les
  lignes à cet EAN, une fois par promotion ; la promotion thé (CAGNOTTE) n'est JAMAIS
  remisée en caisse, elle arrive comptée en entrée `CARD_PROMO`. Le partage §2 « jamais les
  deux » est piloté par `fidelity.jv-promotion-enabled` (moteur si actif, caisse sinon).
- **D9 Canal promotion de l'îlot** (BO-03-10-08) : `island.promotion-channel` renseigné
  (PDV, Web, autres) → l'événement `TICKET_CLOSED` remonté à la fidélité porte le membre
  `channel`. Réglage vide/absent → la remontée reste identique à l'octet près à
  aujourd'hui (aucun membre `channel` ajouté).

> **Note infrastructure fidélité (D4/D6).** imfid est joint par un client HTTP maison
> (`pos.fid.url`, défaut `:8060`), et il n'existe AUCUN simulateur imfid embarqué (à la
> différence du matériel et du valorisateur). En caisse seule autonome, imfid est donc
> injoignable et le disjoncteur court-circuite avant les branches à données. `GroupDIT`
> couvre les branches jouables sans imfid — D4 (indisponible), D5 (ACTIVE/RÉSILIÉE/EN
> ATTENTE via l'endpoint de sélection), D6 (aucune carte, non configuré), D7 (dégradé),
> D8 (déjà scannée). Restent en résidu, faute d'un imfid pilotable : D4 (aucun critère,
> CRM, refus service, aucune carte, +20), D6 (soldes, mouvements, 404 `CARTE FIDÉLITÉ
> INCONNUE`). À reprendre avec la pile démo (imfid) ou un `MockImfidResource`.

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

- **E13 Plafond EGAlim administré** [V] : les plafonds EGAlim sont injectés au nœud
  (flux EGALIM_REGIMES), tirés par la caisse et livrés au moteur ; deux sodas d'un
  régime plafonné → la reprise appliquée suit le plafond ADMINISTRÉ et non la valeur
  compilée dans l'énumération du moteur. Modifier le plafond au référentiel → nouvelle
  valorisation après le tirage suivant, sans redéploiement.
- **E14 Régime EGAlim inconnu** [V] : un article nomme un code de régime absent du
  référentiel → le moteur retombe sur son défaut compilé sans refuser la ligne, et la
  trace de valorisation dit lequel des trois niveaux a servi (référentiel, spécification
  de l'offre, défaut).

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
- **F9 Arrondi légal des espèces** : pas d'arrondi > 1 centime → versement hors pas →
  `MONTANT NON MULTIPLE DE <pas> E` ; ligne `ARRONDI` signée (réel − arrondi) bookée
  UNIQUEMENT quand le versement couvre le restant arrondi, sinon paiement partiel non arrondi ;
  demi vers le haut.
- **F10 Règles de tender administrées** : plafond `MONTANT SUPERIEUR AU PLAFOND (<max> E)`,
  second plafond, minimum `MONTANT INFERIEUR AU MINIMUM (<min> E)`, comptage `NOMBRE MAXIMUM
  DE REGLEMENTS ATTEINT (<n>)`, rendu plafonné `RENDU SUPERIEUR AU PLAFOND (<max> E)`. Niveau
  bloquant simple → refus sec (règlement non enregistré). Niveau bloquant-superviseur → panneau
  d'autorisation du règlement TENU (login/password) : accord → rejeu du règlement ; refus →
  `AUTORISATION REFUSEE` ; le message affiché porte le suffixe ` - APPELER UN SUPERVISEUR`.
  Niveau informatif → règlement enregistré PUIS message a posteriori.
- **F11 Chèque** : hors formation, LECTURE avant enregistrement — overlay `LECTURE DU CHEQUE`
  + `Insérez le chèque dans l'imprimante`, endos imprimé (date + montant), puis lecture ;
  OK → enregistré, tiroir ouvert (rangement) ; pont injoignable → `LECTEUR DE CHEQUES
  INJOIGNABLE` ; délai 60 s → `PAS DE REPONSE DU LECTEUR DE CHEQUES` ; lecture vide → `CHEQUE
  NON LU` ; échec matériel → message du lecteur ; ANNULER caisse pendant l'attente → demande
  retirée, un chèque lu ensuite est ignoré ; en formation → enregistrement direct sans lecture.
- **F12 Titre-restaurant, assiette éligible** : montant plafonné à l'assiette éligible LOCALE
  (attributs articles) quand le moteur n'a pas répondu → `TR PLAFONNE <x> E` ; assiette épuisée
  (deuxième TR au-delà) → `TR: AUCUN ARTICLE ELIGIBLE` ; tiroir ouvert (rangement titres).
  Cas moteur/plafond administré = E11.
- **F13 Crédit / compte client** : sélection par numéro (`NUMERO DE COMPTE REQUIS`, hors plage
  `NUMERO DE COMPTE HORS PLAGE : <n>`, absent `COMPTE INTROUVABLE : <n>`) ou par nom
  (`SAISIR AU MOINS 2 CARACTERES`, 1 résultat = confirmation directe, sinon liste). Paiement :
  aucun compte → `COMPTE CLIENT NON IDENTIFIE` ; crédit non autorisé → `COMPTE SANS
  AUTORISATION DE CREDIT` ; référentiel dégradé → `REFERENTIEL CLIENT NON A JOUR - CREDIT
  REFUSE` ; partiel interdit → `PAIEMENT PARTIEL EN CREDIT CLIENT NON AUTORISE` ; plafond
  dépassé → montant tenu + `PLAFOND DEPASSE - ENCOURS <e> / PLAFOND <p> - AUTORISATION REQUISE`
  → autorisation superviseur (accord = enregistre en over-limit, le SOLDE bouge jamais le
  plafond ; refus → `AUTORISATION REFUSEE`). Pas de tiroir.
- **F14 Devise étrangère** : ouverture (aucune devise active → `AUCUNE DEVISE PARAMETREE`),
  sélection (code blanc/inconnu → `DEVISE NON SELECTIONNEE`, révèle taux + montant dû en
  devise), paiement → crédite la valeur EURO, affichage `<montant> <unité> = <euros> E` puis
  `RENDU <x> E`, tiroir ouvert ; montant ou valeur euro ≤ 0 → `MONTANT EN DEVISE INVALIDE`.
- **F15 Secours monétique (TPE hors service)** : `SECOURS MONÉTIQUE` → QR de demande (restant),
  restant nul → `RIEN A REGLER`. Voie SCANNÉE du code retour : aucune demande → `SECOURS
  MONETIQUE : AUCUNE DEMANDE EN COURS` ; signature forgée / code d'une autre caisse / périmé →
  `SECOURS MONETIQUE : CODE INVALIDE OU ETRANGER A CETTE VENTE` (un seul message). Voie MANUELLE
  (aval superviseur) : montant ≤ 0 → `MONTANT ACCEPTE INVALIDE` ; refus d'aval → `AUTORISATION
  REFUSEE`. Plafonné au restant, aucun rendu, tiroir fermé.
- **F16 Mode dégradé monétique** : activation sous aval → `MODE DÉGRADÉ MONÉTIQUE ACTIVÉ
  (<min> MIN)` (refus d'aval → `AUTORISATION REFUSEE`), désactivation → `MODE DÉGRADÉ MONÉTIQUE
  DÉSACTIVÉ` (déjà inactif → `MODE DÉGRADÉ MONÉTIQUE DÉJÀ INACTIF`), expiration automatique.
- **F17 Bons d'achat & instruments registre** : type introuvable → `Type de bon inconnu` ;
  numéro non reconnu → `Numéro non reconnu — vérifiez la saisie` ; instrument registre
  (avoir / carte cadeau) inconnu → `BON INCONNU AU REGISTRE`, épuisé → `BON DÉJÀ UTILISÉ -
  SOLDE ÉPUISÉ`, déjà passé sur la vente → `BON DÉJÀ SCANNÉ SUR CETTE VENTE` ; encodé (montant
  décodé du numéro), manuel (montant saisi) ; toujours plafonné au restant, jamais de rendu.
- **F18 Rendu en avoir** (BO-03-02-16) : sur un tender à rendu-en-avoir, le trop-perçu →
  `RENDU EN AVOIR <x> E` et un avoir numéroté est émis au moment fiscal (jamais d'espèces).
- **F19 Consultation de solde bon / carte cadeau** : écran `/voucher-balance` — trouvé
  (`CARTE CADEAU` / `AVOIR`, `ACTIVE` / `ÉPUISÉ`, solde formaté), inconnu → `BON INCONNU AU
  REGISTRE`, consultation vide → `AUCUN NUMÉRO SAISI`. Lecture seule, ne mute rien.
- **F20 Rendu monnaie interdit par l'îlot** (BO-03-10-04) : `island.change-allowed` = false
  → un trop-perçu sur un moyen de paiement dont la règle autoriserait le rendu ne rend
  rien (l'interdiction d'îlot prime). Réglage absent ou à true → comportement inchangé.
- **F21 Déclaration automatique de fin de période** (BO-03-10-10) : `island.auto-declaration`
  = true → la clôture saute l'écran de comptage, le montant déclaré est le théorique, le Z
  s'imprime et la caisse se verrouille ; le journal `SESSION_CLOSED` porte « déclaration
  automatique » (un écart nul d'auto-déclaration reste distinguable d'un comptage juste).
  Réglage absent/false → écran de comptage présenté comme aujourd'hui.

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
  meurent au Z (croiser I3). Refus de parking : panier vide → `AUCUN TICKET À METTRE EN
  ATTENTE` ; paiement en cours → `PAIEMENT EN COURS - MISE EN ATTENTE IMPOSSIBLE`. Reprise :
  cart en cours → `TICKET EN COURS - METTEZ-LE EN ATTENTE D'ABORD` ; introuvable / autre
  terminal → `TICKET EN ATTENTE INTROUVABLE`.
- **G5 Bon pour échange** (LC-08-05) : depuis un ticket réimprimé, sélection de lignes
  (`TOUT LE TICKET` / `ARTICLES CHOISIS : <n>`, AUCUN montant sur le bon) → `IMPRIMER LE BON` ;
  en formation → `IMPRESSION INDISPONIBLE EN FORMATION`.
- **G6 Réimpression d'un ticket d'une autre caisse** [N] : `/reprint/foreign` numéro → duplicata
  tiré du nœud magasin (le compteur de duplicata reste celui de la caisse d'origine, rien n'est
  stocké localement) ; formation → `RÉIMPRESSION INDISPONIBLE EN FORMATION` ; numéro vide →
  `NUMÉRO DE TICKET VIDE` ; pas de nœud configuré → `AUCUN NŒUD MAGASIN CONFIGURÉ` ; absent au
  magasin / nœud injoignable → `TICKET INTROUVABLE AU MAGASIN : <n>`.

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
- **H5 Retour en formation** → bloqué (document réel) : `RETOURS INDISPONIBLES EN FORMATION`.
- **H6 Remboursement en cagnotte (fidélité)** : méthode CAGNOTTE → `CREDIT FIDELITE <x> E`
  affiché + reçu de crédit imprimé, événement fidélité posté ; ticket d'origine SANS carte
  fidélité → refus en transaction `AUCUNE CARTE FIDÉLITÉ SUR LE TICKET D'ORIGINE` (rollback,
  rien écrit).
- **H7 Retour interdit sur carte cadeau** : ligne « carte cadeau » sélectionnée → `RETOUR
  INTERDIT SUR CARTE CADEAU` (staging).
- **H8 Méthode non autorisée au remboursement** (BO-03-02-15) : tender interdit → `MOYEN NON
  AUTORISE AU REMBOURSEMENT` ; total ≤ 0 → `RIEN À REMBOURSER`.
- **H9 Bon d'avoir (VOUCHER)** : émet un avoir StoredValue CREDIT_NOTE — désormais SANS plafond
  99,99 € (le plafond historique est levé) et adossé à un registre, donc réutilisable et
  consultable (croiser F19) ; met à jour la limite documentée de H4. Recherche de ticket :
  motif < 3 caractères → `Saisir au moins 3 caractères`, aucun ticket → `Aucun ticket trouvé`.
- **H10 Sélection du motif de retour** (LC-05-05-01) : écran détail retour → la liste
  administrée `return.reasons` s'affiche en touches ; taper `Article périmé` → la touche
  passe en sélectionnée (`.on`) et le motif est retenu côté serveur (`state.refund.returnReason`).
  Un motif hors liste (URL forgée) → aucune sélection retenue.
- **H11 Affectation groupée & journalisation** (LC-05-05-02) : un seul motif choisi
  s'applique à tout le retour ; après avenant, le motif est porté sur le remboursement
  (`Refund.returnReason`) et journalisé dans l'événement `REFUND_CREATED` (suffixe
  `MOTIF:<motif>`), exploitable dans les rapports BackOffice.
- **H12 Impression du motif** (LC-05-05-03) : `return.print-reason` actif → le ticket de
  retour porte la ligne `Motif  : <motif>` (entre `Mode` et la ventilation TVA) ; réglage
  désactivé → le motif est journalisé mais n'apparaît pas sur le ticket.
- **H13 Remboursement partiel réparti sur plusieurs moyens** (LC-05-06-01) : saisir un montant
  partiel (< reste dû du ticket) puis un moyen → un `Refund` est créé pour ce montant, l'écran
  RESTE sur le ticket et annonce `REMBOURSEMENT PARTIEL - RESTE <x> €` ; le caissier rend le
  solde sur un AUTRE moyen (Espèces puis Avoir, plusieurs CB…), leg par leg ; le plafond ticket
  (somme des remboursements ≤ total ticket) empêche tout dépassement. Un montant complet (ou un
  retour par lignes) solde et efface l'écran comme auparavant.
- **H14 Remboursement en crédit client en compte** (LC-05-06-05) : saisir le n° de compte
  (`/return/credit-account`) → le client est nommé, sinon `CLIENT INTROUVABLE` ; sans client
  sélectionné, `CRÉDIT CLIENT` → `AUCUN CLIENT EN COMPTE SÉLECTIONNÉ` ; avec client + avenant,
  l'encours du compte (`AccountCustomer.creditBalance`) est DIMINUÉ du montant remboursé, le
  compte est nommé sur le `Refund` (`creditAccountNumber`), et le ticket porte le mode
  `CREDIT CLIENT`. Limite documentée : l'encours est réécrit au prochain tirage du référentiel
  client (crédit POS transitoire), et aucun rapport BackOffice « remboursement au compte »
  n'existe encore.
- **H15 Ticket d'avoir — code-barre et validité** (LC-05-06-07) : le ticket « AVOIR » d'un
  remboursement VOUCHER porte désormais un code-barre Code-128 du numéro de registre
  (`[[BARCODE 297…]]`, repris au scan en caisse) et une date de fin de validité
  (`Valable jusqu'au : <date>`), la validité étant aussi stockée sur l'instrument
  (`StoredValue.validUntil`).

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

- **L7 Chaîne central → magasin → caisse** [C] : une enseigne créée sur la brique
  centrale → empreinte des domaines d'échelon (COUNTRIES, ENSEIGNES, PDVS,
  ECHELON_SETTINGS) modifiée → tirée par le nœud magasin dans la cadence → lisible sur
  son écran de supervision. Un nœud magasin ne seed jamais le registre d'échelons :
  vérifier qu'il est vide tant que le central n'a rien servi.
- **L8 Domaine fautif isolé** [C] : un fichier invalide déposé pour un domaine → ce
  domaine porte sa dernière erreur d'intégration sur l'écran de supervision du nœud ET
  sur celui de la caisse, et les autres domaines du même cycle sont tirés quand même.
  Le test de la robustesse du tirage autant que de sa restitution.
- **L9 Relance manuelle du tirage à la caisse** [S] : un domaine laissé en erreur →
  bouton « Relancer le tirage » sur l'écran superviseur → l'issue de la relance est
  affichée, et le domaine repasse au vert sans attendre le cycle périodique.
- **L10 Tirage nommé par le TPV** (BO-03-09/BO-03-10) : le tirage porte l'identifiant du
  terminal (`pos.terminal.id`) dans l'en-tête `X-Pos-Terminal`, afin que le nœud résolve
  les réglages par point de contact puis îlot avant le PDV. Compat : face à un nœud non
  mis à jour qui ignore l'en-tête, le tirage aboutit à l'identique et la caisse conserve
  ses référentiels — aucune régression dans un sens comme dans l'autre.
- **L11 Charge utile des îlots, deux formes** (BO-03-09) : un snapshot ISLANDS ne portant
  que `terminalIds` (ancienne forme) s'applique comme avant ; un snapshot ne portant que
  le rattachement par points de contact (nouvelle forme) attache les mêmes terminaux ; une
  caisse ne perd jamais ses îlots parce que le champ a changé de nom.

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

> **Lot 4 (GroupNIT) livré** — N1, N2, N6, N7, N8, N10, N11 [S] verts (`GroupNIT`).
> N3/N4 sont [V], N5/N9 sont [N] → reportés. **Écart N1** : le catalogue attend `CODE
> INCONNU` pour un bon de paiement (50…/789…) scanné au panier ; le code le CONSOMME
> avec `BON VALABLE EN PHASE PAIEMENT` (scission deposit/paiement explicite) — le test
> fige le comportement réel. **N6** : prix expiré → ligne à 0,00 € au taux de TVA par
> défaut (pas de refus). **N11** : moitié « sans fichier logo » inatteignable en
> @QuarkusTest (ressource classpath cachée, sans flag) → résidu (couvert par
> `ReceiptLogoTest`).

- **N11 Logo sur tous les documents** [S] : les neuf documents imprimables portent la
  directive logo en tête. L'avoir et le crédit cagnotte la portent À LA PLACE du nom
  d'enseigne ; le ticket de vente la porte EN PLUS du nom, par la décision inscrite dans
  l'en-tête de `renderTicket`. Le test fige la divergence assumée entre les deux
  conventions, et vérifie qu'une caisse sans fichier de logo imprime les mêmes documents
  avec le nom en texte.

## P. Facturation [S]

Flux à étapes TICKET → (DOCUMENT si plusieurs types) → CLIENT → APERÇU → (INSERTION si
station à feuilles). **Rien n'est écrit ni numéroté avant l'émission** (`/invoice/issue`).

- **P1 Masque & sélection du ticket** : `/invoice` — shortlist des 12 derniers tickets clos,
  pré-remplissage N°/date/caisse du dernier ; nommer un ticket introuvable → `TICKET
  INTROUVABLE : <n>` ; date illisible → recherche élargie (pas de refus).
- **P2 Type de document** : un seul type éligible → saute l'étape ; plusieurs → choix ; type
  inconnu ou désactivé → `TYPE DE DOCUMENT INDISPONIBLE`.
- **P3 Sélection client** : par numéro (`NUMERO CLIENT VIDE`, introuvable `CLIENT
  INTROUVABLE : <n>`) ou par nom (liste max 12, sans résultat → écran « Aucun client ne
  correspond … Créez-le avec NOUVEAU. »). Le numéro l'emporte sur le nom.
- **P4 Création d'un client en caisse** (volatile) : masque administré, NIF étranger préfixé du
  code pays, séquence caisse, outbox CUSTOMER ; droit fermé → `CREATION CLIENT NON AUTORISEE
  EN CAISSE` ; champ requis vide → `<LABEL> OBLIGATOIRE` ; raison sociale vide → `RAISON
  SOCIALE OBLIGATOIRE` ; NIF déjà porté → `N° FISCAL DEJA ENREGISTRE`.

> **Lot 3 (P/Q/S) livré** — classes neuves `GroupPIT` (8/8), `GroupQIT` (3/3), `GroupSIT`
> (1/1), toutes [S] et vertes. Résidus documentés : P8 succès imprimante A4 (réseau requis),
> Q2 succès prélèvement cash (nécessite un détail de dénominations), Q1 `AVAL MANAGER REFUSÉ
> OU MANQUANT` (garde serveur défensive, inatteignable par la modale), Q3 messages de garde
> (bannière en query-param — refus prouvé par l'absence de mouvement), S1 relance tirage
> succès `Tirage des référentiels relancé.` (nœud magasin requis, [N]). Note : le pavé
> numérique de l'écran transfert ne relie pas son champ montant — le montant y est posté
> directement.
- **P5 Correction d'un client** (BO-10-04-03) : bouton visible seulement si le droit est ouvert ;
  droit fermé → `MISE A JOUR CLIENT NON AUTORISEE EN CAISSE` ; aucun client → `AUCUN CLIENT
  SELECTIONNE` ; mêmes gardes que P4 ; client disparu → `CLIENT INTROUVABLE`.
- **P6 Émission & duplicata** : point de non-retour — numéro tiré, document écrit, imprimé,
  outbox DOCUMENT ; 2ᵉ demande sur le même ticket+type → RÉIMPRIME l'existant (`*** DUPLICATA
  N°<n> ***`), PAS de 2ᵉ original ; ticket/client/type manquant → `TICKET OU CLIENT MANQUANT` ;
  formation → `FACTURE INDISPONIBLE EN FORMATION`. Avant émission, l'écran affiche « Brouillon —
  aucun numéro attribué, rien n'est écrit ».
- **P7 Impression feuille à feuille** (station à insertion) : `Insérez la feuille <n> sur
  <total>`, avance d'une feuille et s'arrête seule ; bouton `INTERROMPRE L'IMPRESSION`.
- **P8 Repli imprimante A4 réseau** : cible A4 injoignable → repli sur rouleau caisse +
  `IMPRIMANTE RESEAU INJOIGNABLE - DOCUMENT IMPRIME EN CAISSE`.

## Q. Mouvements de caisse [S]

- **Q1 Mouvement générique** (WITHDRAWAL / DEPOSIT / EXPENSE / CUSTOMER_DEPOSIT / DECLARATION) :
  enregistrement → bandeau `MOUVEMENT ENREGISTRÉ` ; au-delà du seuil administré sans superviseur
  → aval manager parqué (avertissement « Au-delà de <seuil> €, un aval manager sera demandé. ») ;
  formation → `INDISPONIBLE EN FORMATION` ; hors session → `AUCUNE SESSION OUVERTE` ; type
  inconnu → `TYPE DE MOUVEMENT INVALIDE` ; aval refusé/absent → `AVAL MANAGER REFUSÉ OU
  MANQUANT`. Tender vide = espèces.
- **Q2 Prélèvement manuel d'un tender** (LC-12-03) : cash = comptage par dénominations,
  non-cash = totalité du théorique du registre (aucun champ) → `PRÉLÈVEMENT ENREGISTRÉ`
  (+ impression si le flag l'exige) ; moyen non retirable → `MOYEN DE PAIEMENT NON AUTORISÉ` ;
  montant ≤ 0 → `MONTANT INVALIDE` ; aval → `AVAL MANAGER REFUSÉ OU MANQUANT` ; aucun moyen
  paramétré → écran `AUCUN MOYEN DE PAIEMENT PARAMÉTRÉ EN PRÉLÈVEMENT MANUEL`.
- **Q3 Transfert inter-tenders** (LC-12-10) : source ∈ sources, destination ∈ destinations
  (droits distincts BO-05-02-17) → `TRANSFERT ENREGISTRÉ` ; source = destination → `SOURCE ET
  DESTINATION IDENTIQUES` ; montant > théorique du tiroir → `MONTANT SUPÉRIEUR AU THÉORIQUE DU
  TIROIR` (plafond NON contournable) ; mêmes gardes formation / session / méthode / aval ;
  liste vide → `AUCUN MOYEN DE PAIEMENT PARAMÉTRÉ POUR LE TRANSFERT`.
- **Q4 Mouvement financier — enregistrement** (LC-12-06-01/02/03) : écran `/financial-movement`,
  moyen perçu ∈ `movement.financial-sources`, moyen rendu ∈ `movement.financial-destinations`
  (LC-12-06-02, listes administrées), montant > 0 → `MOUVEMENT ENREGISTRÉ`, un mouvement
  TRANSFER espèces→crédit client est écrit (source `paymentMethod`, destination `transferTo`) et
  — si `movement.financial-print` est actif (LC-12-06-03) — un ticket « Mouvement financier »
  sort indiquant perçu / rendu / montant. Contrairement au transfert Q3, AUCUN plafond de
  théorique tiroir (la destination Crédit client n'est pas tenue au tiroir). Au-delà du seuil
  administré sans superviseur → aval manager parqué puis modale partagée.
- **Q5 Mouvement financier — gardes** (LC-12-06) : formation → `INDISPONIBLE EN FORMATION` ;
  hors session → `AUCUNE SESSION OUVERTE` ; source ou destination hors liste → `MOYEN DE
  PAIEMENT INVALIDE` ; source = destination → `MEME MOYEN EN SOURCE ET DESTINATION` ; montant
  ≤ 0 → `MONTANT INVALIDE` ; aval refusé/absent → `AVAL MANAGER REFUSÉ OU MANQUANT`.
- **Q6 Bordereau de commande de monnaie — impression vierge** (LC-12-05-01/02) : écran
  `/change-order` listant toutes les dénominations euro ; « IMPRIMER BORDEREAU VIERGE » sort un
  bordereau ne portant que la liste des dénominations (colonnes montant et nbr. rouleaux vides)
  pour saisie manuscrite (LC-12-05-02).
- **Q7 Bordereau — saisie et calcul croisé** (LC-12-05-03/04/05/06/07, P2) : par dénomination,
  saisie d'un montant → nombre de rouleaux calculé (LC-12-05-06 : face 0,20 € roul.=8 € →
  8 € = 1 rouleau) ou saisie d'un nombre de rouleaux → montant calculé (LC-12-05-07 : face
  0,01 € roul.=0,50 € → 3 rouleaux = 1,50 €), le sens choisi (LC-12-05-05) pilotant la ligne ;
  correspondance administrée `change-order.roll-values` ; dénomination sans valeur de rouleau →
  rouleaux non proposés ; les deux sens se vérifient l'un l'autre.
- **Q8 Bordereau — impression rempli** (LC-12-05-08) : « IMPRIMER LE BORDEREAU » sort le
  bordereau portant le numéro de caisse, l'opérateur (numéro et nom), l'horodatage et un tableau
  dénomination / montant / nbr. rouleaux, cellules remplies ou vides selon la saisie ; garde
  `change-order.print` désactivé → `IMPRESSION DU BORDEREAU DÉSACTIVÉE` ; dénomination inconnue
  → `DÉNOMINATION INCONNUE`.

- **Q9 Apport de monnaie par coupure** [S] (EXP-15) : écran APPORT MONNAIE (`/apport`), saisie
  par coupure via les onglets billets/pièces/rouleaux (mêmes fragments `/cash-count/fragment`
  que le prélèvement), total calculé, VALIDER L'APPORT → mouvement `DEPOSIT` en base portant le
  `denominationDetail`, bordereau `APPORT DE MONNAIE` imprimé (total + signature) ; au-delà du
  seuil `cash.movement-endorsement-threshold` la modale superviseur s'ouvre (parking op=apport).
- **Q10 Calculatrice de fond** [S] (EXP-04) : écran CALCULATRICE DE FOND (`/cash-calculator`),
  comptage par coupure billets jusqu'à 200 €, total affiché en direct, REMETTRE À ZÉRO ; RIEN
  n'est posté, aucun mouvement en base, aucun `TechnicalEvent` — distinct du comptage Z opposable.
- **Q11 Plafond de contenu du tiroir** [S] (EXP-17) : `cash.drawer-cap` administré ; quand le
  théorique espèces du tiroir dépasse le plafond, l'écran de vente affiche le bandeau
  `PRÉLÈVEMENT — CONTENU TIROIR x,xx €` sans bloquer la vente ; plafond 0 → aucun bandeau.

## R. Consommation & budgets moteur (campagnes)

Moitié caisse du chantier campagnes/budgets : la caisse n'administre AUCUNE campagne (BO =
nœud imedge) ; elle DÉRIVE la déclaration de consommation depuis le ticket encaissé et TIRE le
flux `BUDGET_GROUPS` du nœud pour le livrer au moteur en pile démo.

> **Sous-ensemble [S] de R livré** — R3 (`GroupRIT`, profil moteur mort → valorisation
> DEGRADED, rien à déclarer), R5 (`GroupR5IT`, profil `pos.valuation.url` absent → livraison
> désactivée, déclarations en file visibles en supervision, vente non cassée), R7 (`GroupRIT`,
> `/feeds/import/status` : `applied ⇔ version == appliedVersion` via des `EngineFeed` semés).
> R1/R2/R4/R6 restent [V]/[N] : le `MockValuationResource` embarqué n'émet jamais de
> `discountAmount`, donc aucune déclaration SALE n'est dérivée sans le valorisateur réel — à
> reprendre avec la pile démo. (R3/R5 exigent des profils opposés → deux classes.)

- **R1 Dérivation SALE** [V] : ticket finalisé portant des avantages moteur → UNE déclaration
  `SALE` dérivée (ticketId, storeCode, finalizedAt, `advantages:[{offerCode, amount}]` en
  valeur absolue, extraits de la valorisation retenue au paiement) mise en file, livrée au
  moteur. L'encaissement ne l'attend jamais (jamais bloquant).
- **R2 Dérivation CANCELLATION** [V] : annulation d'un ticket ayant déclaré une SALE →
  déclaration `CANCELLATION` symétrique (réutilise le même `advantages`) ; annulation d'un
  ticket jamais finalisé (aucune SALE) → RIEN à déclarer.
- **R3 Mode dégradé LOCAL** [S] : valorisation locale (moteur injoignable au moment de la
  vente) → aucun avantage moteur appliqué → RIEN à déclarer (log « nothing to declare »).
- **R4 Idempotence & rejeu** [V] : clé unique `(storeCode, ticketId, direction)` côté file ET
  côté moteur → rejeu sûr par n'importe quel maillon, pas de double comptage ; la file est
  persistée → une pending non appliquée est reprise après redémarrage, une appliquée le reste.
- **R5 Livraison désactivée** [S] : `pos.valuation.url` absent → livraison consommation ET flux
  moteur désactivées (log dédié) ; les déclarations s'accumulent en file, visibles en
  supervision, sans casser la vente.
- **R6 Ordre BUDGET_GROUPS avant OFFERS** [V] : les flux moteur sont livrés dans l'ordre du
  catalogue (… VAT_RATES → PRICES → BUDGET_GROUPS → OFFERS), avec ARRÊT au premier échec —
  faire échouer `BUDGET_GROUPS` prouve qu'`OFFERS` n'est PAS livré ; une offre au
  `BUDGET_GROUP_CODE` inconnu est rejetée ligne à ligne par le moteur (vérifié sur la pile
  démo). La version d'un flux = SHA-256 du contenu (rejeu du même fichier = no-op).
- **R7 Statut des flux moteur** [S] : `/feeds/import/status` reflète par flux `version` /
  `appliedVersion` / `applied` / `lastError` ; `applied` ⇔ versions égales.

## S. Supervision locale des échanges [S]

- **S1 Panneau « Santé des échanges »** : `/supervisor` — 4 canaux LOCAUX (uplink outbox,
  consommation, flux moteur, tirages) ; bandeau vert `Tous les canaux de cette caisse sont au
  vert.` / rouge `Un canal de cette caisse demande attention.` ; `healthy` ⇔ outbox vide ET
  aucune consommation en attente ET aucun flux en erreur. Panneau réservé au superviseur connecté
  (les fragments `/supervisor/health/*` renvoient 403 sinon). Boutons `Rejouer` (consommation,
  rejeu idempotent) et `Relancer le tirage` → `Tirage des référentiels relancé.` / échec
  `Tirage des référentiels en échec : <err>` (croiser L9). Feeds vides → `Aucun flux reçu sur
  cette caisse.` ; pulls vides → `Aucun référentiel appliqué (caisse fraîchement démarrée).`.

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
| Clé EAN13 invalide (contrôle activé) | `CODE EAN INVALIDE` |
| Article en retrait / rappel | `ARTICLE EN RETRAIT/RAPPEL` |
| Article GS1 introuvable (payload marqué) | `ARTICLE GS1 INTROUVABLE (<code>)` |
| Lot rappelé nommé par le code GS1 | `PRODUIT EN RAPPEL - LOT <lot>` |
| Article GS1 périmé (bloquant) | `ARTICLE PERIME LE <date>` |
| Date courte GS1 (avertissement, la ligne passe) | `DATE COURTE : <date>` |
| Quantité déjà portée par le code GS1 + touche armée | `QUANTITÉ DÉJÀ PORTÉE PAR LE CODE GS1` |
| Chèque cadeau GS1 non paramétré | `CHEQUE CADEAU GS1 NON PARAMETRE` |
| Coupon GS1 non paramétré | `COUPON GS1 NON PARAMETRE` |
| Bon / coupon expiré | `BON EXPIRE LE <date>` |
| Bon valable seulement en paiement (scan panier) | `BON VALABLE EN PHASE PAIEMENT` |
| Étiquette balance en devise non paramétrée | `DEVISE <ISO> NON PARAMÉTRÉE` |
| Répéter : aucun dernier article | `AUCUN ARTICLE À RÉPÉTER` |
| Répéter : ligne pesée | `RÉPÉTITION IMPOSSIBLE : ARTICLE EN PESÉE` |
| Répéter : ligne prix embarqué | `RÉPÉTITION IMPOSSIBLE : ARTICLE PRIX EMBARQUÉ` |
| Quantité armée pour le prochain article | `QUANTITÉ <n> — SAISISSEZ L'ARTICLE` |
| Contrôle d'âge refusé | `VENTE REFUSÉE - CONTRÔLE D'ÂGE` |
| Invite de saisie abandonnée | `SAISIE ABANDONNÉE` |
| Type de modification de prix inconnu | `MODIFICATION INCONNUE` |
| Remises désactivées au référentiel | `REMISES DÉSACTIVÉES` |
| Remise ticket € au-delà du plafond | `REMISE SUPERIEURE AU PLAFOND` |
| Parking : panier vide | `AUCUN TICKET À METTRE EN ATTENTE` |
| Parking : paiement en cours | `PAIEMENT EN COURS - MISE EN ATTENTE IMPOSSIBLE` |
| Reprise : cart en cours | `TICKET EN COURS - METTEZ-LE EN ATTENTE D'ABORD` |
| Reprise : ticket introuvable / autre terminal | `TICKET EN ATTENTE INTROUVABLE` |
| Étiquette balance : PLU introuvable | `ARTICLE BALANCE INTROUVABLE (<plu>)` |
| Abandon : motif obligatoire vide | `MOTIF D'ABANDON OBLIGATOIRE` |
| Abandon : motif hors liste | `MOTIF D'ABANDON INCONNU` |

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

### O-F. Écran paiement — règles de tender & moyens physiques [S]

| Déclencheur | Message exact |
|---|---|
| Espèces hors pas d'arrondi | `MONTANT NON MULTIPLE DE <pas> E` |
| Plafond de tender dépassé | `MONTANT SUPERIEUR AU PLAFOND (<max> E)` |
| Second plafond dépassé | `MONTANT SUPERIEUR AU SECOND PLAFOND (<max2> E)` |
| Sous le minimum de tender | `MONTANT INFERIEUR AU MINIMUM (<min> E)` |
| Comptage max de règlements atteint | `NOMBRE MAXIMUM DE REGLEMENTS ATTEINT (<n>)` |
| Rendu au-dessus du plafond | `RENDU SUPERIEUR AU PLAFOND (<max> E)` |
| Suffixe niveau bloquant-superviseur | ` - APPELER UN SUPERVISEUR` |
| Autorisation superviseur refusée (tender / crédit / secours / dégradé) | `AUTORISATION REFUSEE` |
| Refus TPE (carte) | `PAIEMENT REFUSÉ PAR LE TPE` |
| Chèque : pont injoignable | `LECTEUR DE CHEQUES INJOIGNABLE` |
| Chèque : délai 60 s | `PAS DE REPONSE DU LECTEUR DE CHEQUES` |
| Chèque : lecture vide | `CHEQUE NON LU` |
| TR : assiette éligible épuisée | `TR: AUCUN ARTICLE ELIGIBLE` |
| TR : montant plafonné à l'assiette | `TR PLAFONNE <x> E` |
| Rendu payé en avoir | `RENDU EN AVOIR <x> E` |

### O-G. Écran paiement — crédit client, devise, secours [S]

| Déclencheur | Message exact |
|---|---|
| Crédit : numéro vide | `NUMERO DE COMPTE REQUIS` |
| Crédit : numéro hors plage | `NUMERO DE COMPTE HORS PLAGE : <n>` |
| Crédit : compte absent | `COMPTE INTROUVABLE : <n>` / `COMPTE INTROUVABLE` |
| Crédit : recherche trop courte | `SAISIR AU MOINS 2 CARACTERES` |
| Crédit : compte non identifié | `COMPTE CLIENT NON IDENTIFIE` |
| Crédit : sans autorisation | `COMPTE SANS AUTORISATION DE CREDIT` |
| Crédit : référentiel dégradé | `REFERENTIEL CLIENT NON A JOUR - CREDIT REFUSE` |
| Crédit : partiel interdit | `PAIEMENT PARTIEL EN CREDIT CLIENT NON AUTORISE` |
| Crédit : plafond dépassé | `PLAFOND DEPASSE - ENCOURS <e> / PLAFOND <p> - AUTORISATION REQUISE` |
| Devise : aucune paramétrée | `AUCUNE DEVISE PARAMETREE` |
| Devise : aucune sélectionnée | `DEVISE NON SELECTIONNEE` |
| Devise : montant / valeur invalide | `MONTANT EN DEVISE INVALIDE` |
| Secours : restant nul | `RIEN A REGLER` |
| Secours : aucune demande en cours | `SECOURS MONETIQUE : AUCUNE DEMANDE EN COURS` |
| Secours : code invalide/étranger | `SECOURS MONETIQUE : CODE INVALIDE OU ETRANGER A CETTE VENTE` |
| Secours : montant manuel invalide | `MONTANT ACCEPTE INVALIDE` |
| Dégradé monétique activé | `MODE DÉGRADÉ MONÉTIQUE ACTIVÉ (<min> MIN)` |
| Dégradé monétique déjà inactif | `MODE DÉGRADÉ MONÉTIQUE DÉJÀ INACTIF` |
| Dégradé monétique désactivé | `MODE DÉGRADÉ MONÉTIQUE DÉSACTIVÉ` |

### O-H. Bons d'achat & consultation de solde [S]

| Déclencheur | Message exact |
|---|---|
| Type de bon inconnu | `Type de bon inconnu` |
| Numéro de bon non reconnu | `Numéro non reconnu — vérifiez la saisie` |
| Instrument registre inconnu | `BON INCONNU AU REGISTRE` |
| Instrument registre épuisé | `BON DÉJÀ UTILISÉ - SOLDE ÉPUISÉ` |
| Instrument déjà scanné sur la vente | `BON DÉJÀ SCANNÉ SUR CETTE VENTE` |
| Consultation solde vide | `AUCUN NUMÉRO SAISI` |

### O-I. Fidélité — recherche, consultation, cagnotte [S]

| Déclencheur | Message exact |
|---|---|
| Service indisponible / breaker | `SERVICE FIDÉLITÉ INDISPONIBLE` |
| Service non configuré (consultation) | `SERVICE FIDÉLITÉ NON CONFIGURÉ` |
| Aucun critère de recherche | `SAISISSEZ UN TÉLÉPHONE, UN E-MAIL OU UN NOM` |
| imfid en mode CRM | `IDENTITÉS GÉRÉES PAR LE CRM - RECHERCHE INDISPONIBLE EN CAISSE` |
| Recherche refusée par le service | `RECHERCHE REFUSÉE PAR LE SERVICE FIDÉLITÉ` |
| Aucune carte trouvée | `AUCUNE CARTE TROUVÉE - ESSAYEZ UN AUTRE CRITÈRE` |
| Trop de correspondances | `TROP DE CORRESPONDANCES - PRÉCISEZ LE CRITÈRE (TÉLÉPHONE)` |
| Carte déjà scannée | `CARTE FIDÉLITÉ DÉJÀ SCANNÉE` |
| Carte résiliée | `CARTE RÉSILIÉE - INVITER LE CLIENT À PASSER À L'ACCUEIL` |
| Carte en attente d'activation | `CARTE EN ATTENTE D'ACTIVATION - CAGNOTTE SANS UTILISATION` |
| Aucune carte attachée (consultation) | `AUCUNE CARTE ATTACHÉE — SCANNEZ OU SAISISSEZ LA CARTE` |
| Compte inconnu | `CARTE FIDÉLITÉ INCONNUE` |
| Cagnotte : aucun montant utilisable | `CAGNOTTE: AUCUN MONTANT UTILISABLE` |
| Cagnotte réservée ailleurs (409) | `CAGNOTTE RÉSERVÉE SUR UNE AUTRE CAISSE` |
| Cagnotte : solde insuffisant | `SOLDE CAGNOTTE INSUFFISANT` |
| Cagnotte : règle journalière | `CAGNOTTE DÉJÀ UTILISÉE AUJOURD'HUI` |
| Cagnotte : compte inactif | `COMPTE FIDÉLITÉ INACTIF` |
| Cagnotte : refus générique | `PAIEMENT FIDÉLITÉ REFUSÉ` |

### O-J. Facturation [S]

| Déclencheur | Message exact |
|---|---|
| Ticket introuvable | `TICKET INTROUVABLE : <n>` |
| Type de document indisponible | `TYPE DE DOCUMENT INDISPONIBLE` |
| Numéro client vide | `NUMERO CLIENT VIDE` |
| Client introuvable | `CLIENT INTROUVABLE : <n>` / `CLIENT INTROUVABLE` |
| Création client interdite en caisse | `CREATION CLIENT NON AUTORISEE EN CAISSE` |
| Champ obligatoire manquant | `<LABEL> OBLIGATOIRE` |
| Raison sociale vide | `RAISON SOCIALE OBLIGATOIRE` |
| NIF déjà enregistré | `N° FISCAL DEJA ENREGISTRE` |
| Mise à jour client interdite en caisse | `MISE A JOUR CLIENT NON AUTORISEE EN CAISSE` |
| Aucun client sélectionné | `AUCUN CLIENT SELECTIONNE` |
| Émission sans ticket/client | `TICKET OU CLIENT MANQUANT` |
| Émission en formation | `FACTURE INDISPONIBLE EN FORMATION` |
| Imprimante A4 réseau injoignable | `IMPRIMANTE RESEAU INJOIGNABLE - DOCUMENT IMPRIME EN CAISSE` |

### O-K. Mouvements de caisse, réimpression, retours [S]

| Déclencheur | Message exact |
|---|---|
| Mouvement / prélèvement / transfert en formation | `INDISPONIBLE EN FORMATION` |
| Mouvement / prélèvement / transfert hors session | `AUCUNE SESSION OUVERTE` |
| Type de mouvement invalide | `TYPE DE MOUVEMENT INVALIDE` |
| Aval manager refusé ou manquant | `AVAL MANAGER REFUSÉ OU MANQUANT` |
| Moyen non autorisé (prélèvement / transfert) | `MOYEN DE PAIEMENT NON AUTORISÉ` |
| Montant invalide (prélèvement / transfert) | `MONTANT INVALIDE` |
| Transfert : source = destination | `SOURCE ET DESTINATION IDENTIQUES` |
| Transfert : au-delà du théorique | `MONTANT SUPÉRIEUR AU THÉORIQUE DU TIROIR` |
| Ouverture session déjà ouverte | `OUVERTURE IMPOSSIBLE (SESSION DÉJÀ OUVERTE ?)` |
| Réimpression / duplicata en formation | `RÉIMPRESSION INDISPONIBLE EN FORMATION` |
| Bon d'échange en formation | `IMPRESSION INDISPONIBLE EN FORMATION` |
| Réimpression autre caisse : numéro vide | `NUMÉRO DE TICKET VIDE` |
| Réimpression autre caisse : pas de nœud | `AUCUN NŒUD MAGASIN CONFIGURÉ` |
| Réimpression autre caisse : ticket absent | `TICKET INTROUVABLE AU MAGASIN : <n>` |
| Retour interdit sur carte cadeau | `RETOUR INTERDIT SUR CARTE CADEAU` |
| Retour : moyen non autorisé | `MOYEN NON AUTORISE AU REMBOURSEMENT` |
| Retour cagnotte sans carte d'origine | `AUCUNE CARTE FIDÉLITÉ SUR LE TICKET D'ORIGINE` |

### O-L. Messages de page — session, clôture, supervision [S]

- **Fermeture du poste** (`/close`) : `Code opérateur incorrect.` ; `Autorisation superviseur
  refusée.` ; `Des tickets en attente exigent une autorisation superviseur.`.
- **Rapport X / clôture Z** : hors session → page session `AUCUNE SESSION OUVERTE` ;
  ouverture en formation → `INDISPONIBLE EN FORMATION`.
- **Santé des échanges** (`/supervisor`) : `Tirage des référentiels relancé.` /
  `Tirage des référentiels en échec : <err>` ; fragments réservés superviseur (403 sinon).

Note : plusieurs branches sont des refus SILENCIEUX (montant ≤ 0, restant ≤ 0, transaction
déjà en vol, apply de geste refusé) — les tester par ABSENCE d'effet, pas par un message.

---
## Couverture — scénarios catalogués contre tests écrits

Relevé sur `src/test/java/com/intermarche/e2e` : **152 scénarios catalogués, 118 portés
par un test**. Une classe `Group<X>IT` par groupe, un `@Test` par scénario, l'identifiant
dans le nom de la méthode. Le catalogue a été RÉ-AUDITÉ contre `src/main` (ressources UI,
filtres, services de paiement, imports, sync).
Le **Lot 1** (soudures des classes vertes) a livré A8, A9, C7, C8, D4→D8, G5, H6→H9
(14 scénarios [S], + l'entrée A0 formalisée), tous verts ; A10 et G6 sont [N] et reportés,
D4/D6 partiels (résidu imfid, voir la note du groupe D).
Le **Lot 2** (vente &amp; paiement) a livré B15→B24 et F9→F19 (21 scénarios [S]), tous verts.
F7 est [V] (reporté au Lot 5). Résidu documenté : B15 lignes-intégrées (nœud magasin
requis), B16 rappel/retrait/lot + date-courte, B17 coupon expiré, F10 min/comptage/2ᵉ
plafond/rendu/informatif, F11 pannes lecteur chèque, F15 QR/code scanné + RIEN A REGLER,
F16 DÉJÀ INACTIF (inatteignable par la bascule unique). Deux rouges PRÉ-EXISTANTS dans
`GroupBIT` (b02 VatRate transient dans le helper `insertPromoPrice` ; b14 avenant après
ANNULER TICKET) — prouvés en échec à HEAD (avant le Lot 2), hors périmètre B15→B24.

| Groupe | Catalogués | Testés | Reste |
|---|---|---|---|
| A. Prise de poste | 11 | 10 | A10 ([N]) |
| B. Vente | 24 | 24 | — (b02/b14 rouges pré-existants, hors périmètre) |
| C. Gestes de prix | 8 | 8 | — |
| D. Fidélité | 8 | 8 | — (D4/D6 partiels, résidu imfid) |
| E. Valorisation continue | 14 | 0 | E1–E14 |
| F. Paiement & clôture | 19 | 18 | F7 ([V]) |
| G. Après-vente | 6 | 5 | G6 ([N]) |
| H. Retours | 12 | 12 | — (H10–H12 motif de retour LC-05-05) |
| I. Session & clôture Z | 4 | 4 | — |
| J. Mode formation | 3 | 3 | — |
| K. Reprise & robustesse | 3 | 3 | — |
| L. Consolidation magasin | 9 | 0 | L1–L9 |
| M. Thèmes | 4 | 4 | — |
| N. Compléments | 11 | 7 | N3,N4 ([V]) · N5,N9 ([N]) |
| P. Facturation | 8 | 8 | — (P8 succès A4 = résidu réseau) |
| Q. Mouvements de caisse | 3 | 3 | — (Q2 succès cash-count = résidu dénominations) |
| R. Consommation & budgets | 7 | 3 | R1,R2,R4,R6 ([V]/[N]) |
| S. Supervision locale | 1 | 1 | — (relance tirage succès = résidu [N]) |
| **Total** | **152** | **118** | **34** |

L'`a0_` de `GroupAIT` a désormais son entrée au catalogue (A0, sonde de santé publique).

Ce que dit ce tableau. Le premier relevé (95 scénarios) ne couvrait que le tronc
vente/paiement de base ; le ré-audit révélait 91 scénarios sans test. Après les Lots 1 à 4
et le sous-ensemble [S] de R (R3, R5, R7) il en reste **34 sans test** — tous [V]/[N]/[C]
(E1–E14, F7, R1/R2/R4/R6, L1–L9), qui exigent la pile de démo et sont reportés à une session
avec la pile disponible, dont une majorité
de moyens de paiement (chèque, TR, crédit client, devise, secours, dégradé monétique, règles
de tender), de gestes de vente (GS1, ticket balance, répéter, à-enlever, contrôle d'âge,
invite prix/quantité) et des chantiers entiers **jamais entamés** : facturation (P),
mouvements de caisse (Q), consommation & budgets campagnes (R), supervision locale (S). La
plupart sont marqués [S] et ne demandent QUE la caisse et son simulateur — c'est le travail
le moins cher.

Priorité d'exécution suggérée : d'abord tout le neuf [S] à infrastructure nulle — les moyens
de paiement F9→F19, les gestes de vente B15→B24, la facturation P, les mouvements de caisse Q,
la fidélité D4→D8, la supervision locale S1 — puis F7 et E (le moteur seedé miroir), R
(consommation, [V]/[N]), enfin L, N et les [C] qui demandent la pile de démo à cinq services.
Les scénarios [N] demandent le lancement biface ; les [V] demandent le moteur seedé miroir ;
les [C] demandent la pile complète.
