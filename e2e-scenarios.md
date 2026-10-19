# Catalogue des scénarios e2e — POS Intermarché

Couche complémentaire des tests unitaires (agent local, sans H2) : ici l'application
réelle démarre, la base H2 vit, le simulateur matériel pilote scanner/balance/TPE/tiroir.
Infrastructure par groupe : **[S]** = caisse seule + simulateur ; **[V]** = + valorisateur
sur :8090 (seed miroir) ; **[N]** = + second nœud en rôle store (consolidation) ;
**[C]** = + une brique centrale, c'est-à-dire un troisième imedge portant
`pos.role=central`, qui sert au nœud magasin les domaines d'échelon.

Convention : chaque scénario = parcours → attendus vérifiables (écran, base, log, impression).

**Règle d'interface, sans exception : la caisse n'a PAS de clavier physique.** Toute saisie
passe par un pavé tactile, et ce que le caissier tape s'affiche dans un **élément de texte**
(classe `pad-display`), jamais dans un `<input>` : un champ visible y montrerait un curseur
attendant des frappes impossibles, et inviterait à brancher un clavier. La valeur postée voyage
dans un champ caché nommé par `hiddenId`. Un scénario qui trouve un champ de saisie libre sur un
écran de caisse est un défaut, pas une variante. Seule exception : `digital-ticket`, page servie
au téléphone du CLIENT, qui a un vrai clavier.
Le **pavé de pré-saisie** de l'écran de vente occupe le coin **bas droit** (dernière brique de la
colonne d'actions, sous les dix touches) ; sur les écrans à panneau, le pavé reste dans le
panneau qu'il sert.

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
- **B18 Répéter article** : `RÉPÉTER ARTICLE` → +1 sur la dernière ligne, autant de fois que la
  touche est pressée (voir B29), notice
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
- **B29 Quantité refusée sur une ligne pesée** (LC-02-13-15) : ligne pesée à 1,234 kg,
  touche QUANTITÉ puis `3` (ou `0,850`) → `QUANTITÉ NON MODIFIABLE SUR CETTE LIGNE`, la ligne
  reste à 1,234 kg. Le poids d'une ligne pesée est une MESURE : ni la touche quantité, ni
  l'armement avant article, ni la répétition ne la réécrivent. La saisie manuelle du poids est
  une autre touche, avant l'article (LC-02-02-03), et c'est elle qui portera la déduction de
  tare de LC-14-01-08 — sans cette touche, LC-14-01-08 n'a pas de point d'application.
- **B29b Quantité décimale sur article « quantité décimale »** (LC-02-13-02/03) : ligne d'un
  article portant l'attribut `QUANTITY_TO_ENTER` (tuyau au mètre, nappe au m²) → QUANTITÉ
  `2,360` acceptée telle quelle, total recalculé ; sur une ligne unitaire ordinaire la même
  saisie donne `QUANTITÉ INVALIDE (1-999)`. Bornes : > 0, au plus 3 décimales, 999 inclus.

- **B33 Code-barres à prix embarqué → poids affiché** [S] (EXP-26, LC-02-05-01/04) : scan
  d'une étiquette à prix embarqué (préfixe 02/21/22/24/26/28, valeur digits 8–12) → ligne au
  prix lu, et POIDS DÉDUIT du prix au kilo du référentiel (sens inverse) affiché sur les deux
  visus et imprimé sur le ticket (« 0,375 kg à 4,00 €/kg ») ; sans prix référentiel, pas de poids.
- **B34 Code-barres à poids embarqué + préfixes reclassés** [S] (EXP-27) : préfixes poids
  23/25/29 (24 et 26 basculés côté prix, 02/29 ajoutés) ; **27 appartient au ticket balance
  comptoir** (B15) et n'est donc PAS un préfixe poids : les deux lots de préfixes sont disjoints
  par contrat — les deux handlers partagent la priorité 1, et 27 présent des deux côtés laissait
  l'ordre des beans décider lequel ramassait le papier du comptoir. Le repli ne dépend plus du
  filtre 2\d{12} mais du préfixe administré. Étiquette en francs → conversion en euros au taux
  6,55957 descendu du back-office (Currency), position/longueur administrées par plage.
- **B35 Code famille** [S] (EXP-24) : « 80 » VALIDER « 2,35 » VALIDER → ligne « EPICERIE
  2,35 € » portant le libellé de la famille et la TVA par défaut ; famille inconnue → FAMILLE
  INCONNUE ; prix négatif/illisible → ERREUR PRIX SAISI.
- **B36 Défilement Fruits & Légumes** [S] (EXP-22) : le tableau des tuiles F&L défile au lieu
  de paginer.
- **B37 Répétition sans plafond** [S] (EXP-29, revu en recette) : RÉPÉTER ARTICLE trois fois de
  suite sur la même ligne → quatre unités, trois notices `ARTICLE RÉPÉTÉ`, aucun refus ; sur une
  ligne dont la quantité a été SAISIE (3) → quatre. L'ancien refus `RÉPÉTITION IMPOSSIBLE :
  QUANTITÉ SAISIE` a disparu : il lisait la quantité, qu'il ne pouvait pas distinguer de celle
  que la répétition venait elle-même de monter, et plafonnait donc la fonction à deux unités.
  Seules la pesée et le prix embarqué refusent encore.

- **B30 L'affichage du ticket suit la saisie** [S] (2e passe, P1) : sur un ticket long (plus
  d'une page de lignes), l'ajout d'une ligne fait sauter l'affichage sur LA page qui porte
  cette ligne (`showLinePage`), au lieu de rester sur la page courante ; un uid inconnu ou
  null ne provoque aucun saut (page inchangée).
- **B31 Sélection de ligne par uid** [S] (2e passe, P2) : sur un ticket multi-pages,
  sélectionner la DEUXIÈME ligne de la TROISIÈME page (`/action/select/<uid>`) retient bien
  cette ligne (index global 13), jamais la deuxième du ticket ; uid absent → aucune sélection
  changée. C'est le test qui aurait attrapé le défaut « sélection hors page 1 ».
- **B32 Ligne de détail unique qté × PU** [S] (2e passe, P3-5) : chaque ligne montre son
  libellé seul, puis — seulement si nécessaire (qté > 1, pesée, ou poids/prix embarqué) — une
  SEULE ligne de détail « q x PU € » (« q kg x PU €/kg » pour une pesée), identique sur la
  visu caisse, la visu client ET le ticket imprimé ; une qté 1 non pesée n'a pas de ligne de
  détail.

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

- **C9 Autorisation par geste** [S] (EXP-32) : chaque geste négatif porte son propre
  `gesture.endorsement-<geste>` (annulation ligne/dernier article, abandon, retour, remboursement,
  remise, forçage). Drapeau OFF → le geste passe sans modale ; ON → aval demandé ; un caissier
  MANAGER/ADMIN (`operatorIsSupervisor`) ne demande jamais.
- **C10 Session superviseur** [S] (EXP-33) : `gesture.endorsement-scope=PER_TICKET` → un aval accordé
  reste attaché au ticket (`supervisorSessionBadge`), les gestes suivants passent sans redemander ;
  bandeau SUPERVISEUR visible ; touche FIN SUPERVISEUR (`/action/supervisor-session/end`) referme la
  session ; la fin du ticket la referme aussi. PER_OP → chaque geste redemande.
- **C11 Garde-fou dernier article** [S] (EXP-35) : l'annulation refuse quand l'article est le seul du
  ticket (`DERNIER ARTICLE — UTILISEZ L'ABANDON DE TICKET`) — la sortie nommée est l'abandon.
- **C12 Annulation par scan** [S] (EXP-36) : touche ANNULER PAR SCAN (`/action/cancel-by-scan`) arme le
  mode ; le prochain code scanné désigne la ligne à annuler (via la même décision aval/dernier-article) ;
  code absent du ticket → `ARTICLE ABSENT DU TICKET` ; désarmé après usage et à la fin du ticket.

- **C13 Remise € = remise unitaire × quantité** [S] (2e passe, P8) : une REMISE de 2,00 € sur
  une ligne de 10 articles à 10,00 € abaisse la ligne à 80,00 € (2 € × 10), jamais 98 € ; un
  article identique scanné ensuite repart au prix plein (10,00 €, la remise ne colle pas au
  produit) ; passer de 10 à 11 unités remise aussi la onzième ; le plafond euro administré
  (`discount.line-max-amount`) écrête la remise de LIGNE — 2 € × 10 = 20 € refusé sous un
  plafond de 10 €, tandis que la même valeur sur une seule unité passerait.
- **C14 Le superviseur connecté ne se demande pas son propre aval** [S] (2e passe, P6) : un
  opérateur superviseur, ou une session superviseur ouverte (`supervisorSessionBadge`),
  autorise DIRECTEMENT les gestes sans drapeau propre — impression du badge opérateur,
  bascule du mode formation — via un point de décision unique (`selfAuthorized`), sans modale
  d'aval ; un caissier simple, lui, voit la modale d'autorisation.

- **C15 Pré-saisie (le nombre avant la touche)** : un pavé reste à l'écran de vente, dans le
  **coin bas droit** — dernière brique de la colonne d'actions, sous les dix touches, qui
  gardent leurs deux colonnes et leurs cinq rangées —, et alimente le tampon `PosPrefill`.
  L'afficheur du tampon est un simple cadre de texte, **jamais un champ de saisie** : une
  borne n'a pas de clavier physique, et un `<input>` y montrerait un curseur attendant des
  frappes impossibles. Il occupe toute la largeur de la zone et ne porte **aucune consigne** :
  le geste s'apprend une fois. Le pavé a une touche **`00`** (jamais en tête de nombre, jamais
  à cheval sur la longueur maximale) en plus des chiffres, de `⌫`, `C`, `,` et `OK`.
  Taper `3` puis **QUANTITÉ**
  applique la quantité sans ouvrir la modale (`?value=`) ; taper `2,50` puis **REMISE / PRIX**
  applique la remise en euros ; taper puis **REMISE TICKET** applique la remise globale.
  **Rien de tapé, la touche ouvre sa modale exactement comme avant** — aucun geste n'est
  retiré, et c'est ce qui permet de poser le mécanisme partout d'un coup. La valeur passe
  PAR l'ouverture : elle hérite donc de ses refus (type inconnu, aucune ligne sélectionnée)
  au lieu de les redécider. Valeur illisible → `VALEUR INVALIDE`, rien n'est appliqué.
  Les autres modes de la modale (discount %, prix forcé, remise ticket en %) restent
  accessibles en ne tapant rien. La touche `OK` du pavé envoie le code tapé par
  **l'admission du scan** (`POST /api/pos/scan`), seul chemin qui connaît les EAN, les GS1,
  les bons et les codes à prix embarqué : taper un code vaut le scanner.
  **RECHERCHE sur un code tapé ne passe plus par l'écran recherche** : taper un EAN ou un PLU
  puis **RECHERCHE** (`/action/search/direct`) fait entrer l'article directement au ticket par
  la même admission du scan, et l'écran de vente ne bouge pas. L'EAN est interrogé avant le
  PLU — c'est ce qu'un scanner aurait lu, et les deux espaces se croisent sur les petits
  nombres. Code inconnu des deux espaces → l'écran recherche s'ouvre **prérempli du code**
  (`/search?q=`) : un caissier qui a appuyé sur RECHERCHE a demandé une recherche, il lui est
  dû une liste et non une erreur. Rien de tapé → RECHERCHE ouvre l'écran recherche vide comme
  avant.
- **C16 Pré-saisie sur les autres écrans** : **Règlement** — taper le montant puis
  **ESPÈCES / CARTE / TICKET RESTO / CHÈQUE / CAGNOTTE** règle en un geste ; rien de tapé,
  la saisie s'ouvre préremplie du reste dû comme avant ; les saisies de CODE (Resto Flash,
  carte cadeau, SOGEC) sont exclues, ce qu'on y tape étant un numéro et non une somme.
  **Fruits & légumes** — le pavé occupe le bas d'une colonne à droite de la grille (qui passe
  de quatre à trois colonnes et continue de défiler), `OK` ajoute l'article du code tapé.
  Sur chacun de ces écrans le pavé est posé dans le **coin bas droit**, à la même place qu'à
  l'écran de vente : la main du caissier n'a pas à le chercher d'un écran à l'autre.
  **Saisie directe** — taper la quantité (produit connu) ou le prix (produit non reconnu)
  puis le produit enregistre sans passer par la saisie.
  Un seul pavé par écran, deux propriétaires successifs : le tampon tant que rien n'est
  choisi, `PosInput` dès qu'un champ l'est — jamais les deux à la fois.

- **C17 Plafond de montant d'un article** [S] (BO-10-02-16) : le FORÇAGE de prix passe par le
  point de contrôle `control.item-amount`, comparé au plafond `control.item-amount-max`.
  Plafond vide ou illisible → aucun contrôle, c'est le défaut ; figure égale au plafond →
  passe (la comparaison est un AU-DELÀ) ; au-delà → `NONE` passe en silence, `WARN` laisse
  l'alerte et poursuit, `BLOCK` refuse sans proposer d'aval, `ENDORSE` parque le geste pour
  un manager SANS consulter `gesture.endorsement-*` (c'est ce qui distingue cette route de
  la route normale). **Comparé EN VALEUR ABSOLUE** : un prix forcé de −150 sur un retour est
  plafonné comme une vente de 150, ce que l'exigence demande en toutes lettres. Les autres
  gestes de prix (remise, discount) ne sont pas concernés et ne lisent même pas le plafond.
- **C18 Plafond de montant d'un règlement** [S] (BO-10-02-17) : tout montant TAPÉ à l'écran
  de règlement — les treize panneaux passent par le même parseur — est confronté au point
  `control.payment-amount` et au plafond `control.payment-amount-max`, en valeur absolue
  elle aussi. `WARN` prévient et règle, `BLOCK` refuse et le service de règlement n'est pas
  appelé, `ENDORSE` refuse également : l'écran de règlement n'a pas de modale où parquer un
  règlement, et le point n'est donc pas avalisable. Ce que la caisse calcule elle-même —
  reste dû, arrondi espèces, rendu monnaie — ne passe pas par là et n'est pas plafonné : le
  magasin plafonne ce qu'on tape, pas ce qu'il doit. Sur le panneau des bons, un refus
  **laisse le bon en attente** pour que le caissier retape un montant recevable.

## D. Fidélité [S]

- **D20 Recherche client par ville** [S] (EXP-74) : mode VILLE sur l'écran fidélité →
  `lookupCards` reçoit le critère ville, les autres axes null ; s'ajoute à téléphone /
  e-mail / nom.
- **D21 Message du service fidélité** [S] (EXP-76) : le champ `warnings` renvoyé par imfid
  s'affiche dans la zone message du ticket (ligne FIDÉLITÉ, non bloquante).
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

- **F30 Mode CB réel tracé** [S] (EXP-54) : un paiement carte enregistre le mode d'entrée
  réel renvoyé par le TPE (SANS_CONTACT / CODE) sur le `CardPayment` (tracé au ticket et
  aux états), là où un seul flux indifférencié était délégué au TPE.
- **G30 Ticket par SMS** [S] (EXP-48) : sur l'écran d'envoi du ticket, la saisie d'un
  numéro de téléphone envoie le ticket dématérialisé par SMS (passerelle stubée, journalisé
  `DIGITAL_TICKET_SENT` même sans passerelle), en parallèle du mail.

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

- **F35 Touches d'arrondi et coupures sur le pavé paiement** [S] (EXP-55) : touche ARRONDI au
  10 € supérieur et touches coupures (5/10/20/50/100 €) remplissent le montant à régler.
- **F36 Annulation d'un règlement seul** [S] (EXP-63) : sur plusieurs règlements enregistrés,
  annuler UNE ligne (index) sans purger les autres ; le paiement reste en cours, le draft est
  re-persisté avec les règlements survivants ; index hors plage → aucun effet.
- **F22 INFO ARTICLE** [S] (EXP-93, revu en recette) : écran INFO ARTICLE (`/article-info`)
  atteignable CAISSE FERMÉE sans code utilisateur (allowlist LockCheckFilter) ; scan/saisie d'un
  code → libellé, prix, TVA, marque ; code inconnu → message introuvable.
  **JE PRENDS L'ARTICLE** (`/action/article-info/take`) apparaît quand une vente est possible —
  caisse déverrouillée ET session ouverte — et ajoute UNE unité par l'admission du scan, puis
  rend l'écran de vente ; RETOUR garde son sens : non. Caisse verrouillée ou sans session, la
  touche n'apparaît pas et l'action postée directement n'ajoute rien : l'écran redevient la
  consultation pure qu'il doit rester hors vente. Le défaut corrigé : une fois l'article affiché,
  le caissier n'avait aucune sortie sauf RETOUR, alors qu'il cherche le plus souvent à le vendre.
- **F31 Carte TRD (assiette + plafond)** [S] (EXP-60) : règlement `/action/pay-trd` — borné à
  l'assiette éligible titre-restaurant (au-delà → `TRD PLAFONNE`), et au plafond administré
  `payment.trd-card-cap` ; assiette nulle → `TRD: AUCUN ARTICLE ELIGIBLE`, rien réglé.
- **F32 Resto Flash** [S] (EXP-67) : `/action/pay-restoflash` — token confirmé par le service
  (stub) → règlement sur l'assiette éligible ; token refusé → `RESTO FLASH REFUSE`, rien réglé.
- **F33 Carte cadeau fournisseur** [S] (EXP-66) : `/action/pay-giftcard` — interrogation du
  registre émetteur ; carte invalide → `CARTE CADEAU REFUSEE` ; solde nul → `CARTE CADEAU: SOLDE NUL` ;
  montant > solde → plafonné au solde (`CARTE CADEAU x,xx E`).
- **F34 Bon SOGEC** [S] (EXP-58, revu en recette) : `/action/pay-sogec` — circuit distinct du
  coupon GS1 générique ; le bon règle **sa valeur faciale**, jamais le reste dû, et une trace de
  compensation `SOGEC_COMPENSATION` est journalisée pour le montant imputé. L'écran a deux
  champs, le code et la valeur, le pavé se reportant sur celui qu'on touche. Valeur portée par
  le code-barres du bon (`(390n)`) → elle gagne sur ce qui est tapé ; aucune valeur ni décodée
  ni tapée → `BON SOGEC: VALEUR REQUISE`, rien n'est réglé ; bon de 20 € sur un ticket de
  56,50 € → 20 € réglés, 36,50 € restants ; bon de 20 € sur un reste dû de 8 € →
  `BON SOGEC PLAFONNE  8,00 E`, 8 € imputés, aucun rendu ; ticket déjà soldé → rien, sans
  message. Le défaut corrigé : sans montant le serveur retombait sur le reste dû et le ticket
  sortait intégralement payé en SOGEC.

## G. Après-vente [S]

- **G7 Recherche ticket partagée** [S] (EXP-47) : le moteur unique cherche un ticket CLOSED
  par numéro, par date (jj/mm/aaaa) ou par montant (0,00), branché des deux côtés — la
  RECHERCHE TICKET du duplicata (`/reprint/search`) et la recherche du retour (`/return/search`).
  Register-local : frontière magasin honorée par la localité.

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
- **G20 Rappel par code — devis / commande / bon de vente / click&collect** [N] (EXP-90/91/92) :
  écran RAPPEL CODE (`/recall`), saisie du code remis par le back-office → chargement des lignes
  du document dans la vente (via le même entonnoir `addItem`). Le document vit sur imedge :
  caisse autonome → `DOCUMENT INTROUVABLE` (sur l'écran de vente) ; document vide → `DOCUMENT VIDE`.
  Le fetch d'un document réel exige imedge → résidu **[N]**.
- **G21 Acompte rattaché / repris sur commande** [N] (EXP-85) : formulaire acompte de `/recall`
  → mouvement `CUSTOMER_DEPOSIT` portant `orderReference` (rattachement) ; au rappel d'une
  COMMANDE portant un acompte déjà versé, celui-ci est crédité comme règlement et déduit du
  reste dû. La reprise réelle depuis le nœud est **[N]**.
- **G22 Reprise inter-caisses** (EXP-46) : hors ligne, un ticket en attente ne se reprend que
  sur SA caisse (filtre `terminalId` — règle dégradée assumée) [S] ; en mode connecté, le scan
  du numéro d'un ticket mis en attente sur UNE AUTRE caisse le fait rappeler via le nœud
  (`InterTillResumeClient`), re-domicilié sur cette caisse et rouvert. Le fetch réel depuis le
  nœud exige imedge → résidu **[N]**.
- **G23 Prélèvement : destination obligatoire** [S] (EXP-87, revu en recette) : l'écran
  PRÉLÈVEMENT n'a plus qu'une validation et une **destination à choisir** — le coffre, toujours
  offert, plus les destinations administrées (`cash.withdrawal-destinations`). Destination vide →
  `DESTINATION OBLIGATOIRE`, rien n'est enregistré. Destination « Coffre » sur les ESPÈCES (casse
  indifférente) → mouvement `WITHDRAWAL` marqué `safeDeposit` (motif « Prélèvement coffre »),
  enfilé pour le nœud, comme l'ancien bouton VERS LE COFFRE. Destination « Coffre » sur un AUTRE
  moyen → prélèvement ordinaire dont le motif nomme le coffre (le mouvement d'alimentation du
  coffre est une notion espèces sur le nœud). Toute autre destination → motif « Prélèvement
  <moyen> vers <destination> ». Le défaut corrigé : deux boutons côte à côte dont rien n'expliquait
  la différence, l'un marquant le coffre, l'autre ne nommant aucune destination. Le coffre
  lui-même est une entité du nœud magasin (imedge) : la caisse n'enregistre que le mouvement qui
  l'alimente ; le solde du coffre reste **[N]**.

## H. Retours [S]

- **H21 Sous-total sur le ticket** [S] (EXP-43) : touche SOUS-TOTAL → marqueur après la
  dernière ligne, somme du groupe depuis le marqueur précédent affichée à l'écran et imprimée
  (`SOUS-TOTAL x,xx`) sur le ticket, le duplicata et le ticket dématérialisé ; le total et les
  offres à seuil voient toujours le ticket entier. Un second marqueur somme depuis le premier.
- **H23 Un seul ticket en attente** [S] (EXP-45) : un ticket déjà en attente → la mise en
  attente d'un second est refusée (« UN TICKET EST DÉJÀ EN ATTENTE - REPRENEZ-LE D'ABORD »).

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

- **H20 Retour par scan du numéro** [S] (EXP-37) : le numéro imprimé en bas d'un ticket CLÔTURÉ,
  scanné sur l'écran de vente, route vers `/return` (poll `returnRequested` → navigation), la recherche
  retour préremplie. Un numéro d'un ticket EN ATTENTE reprend le panier (comportement inchangé) ; un
  numéro d'un autre magasin n'existe pas en base locale (refus implicite) ; autre caisse et autre jour OK.
- **H22 Lignes déjà retournées grisées** [S] (EXP-37) : sur la fiche retour, une ligne entièrement
  retournée par un retour antérieur est grisée (`.receipt-line.refunded`, « DÉJÀ RETOURNÉ ») et non
  sélectionnable ; les lignes encore retournables restent actives.
- **H16 Ticket mixte : retour + achats, total signé** [S] (EXP-38) : un article repris (ligne
  négative, `returnLine`) et des achats cohabitent sur UN ticket ; le total est SIGNÉ. Positif → on
  encaisse (flux habituel) ; négatif → l'écran de paiement bascule en remboursement
  (`isNetRefund`, `pay-net-refund` règle le net dû au client dans le moyen choisi) ; une ligne de
  retour n'est PAS envoyée au valorisateur (contrat inchangé), le total la porte quand même.
- **H17 Retour sans ticket** [S] (EXP-39) : en mode retour, un article sans ticket d'origine crée une
  ligne négative à son prix courant sous le drapeau d'aval `gesture.endorsement-return`.
- **H18 Mode caisse retour de la journée** [S] (EXP-40) : bascule MODE RETOUR (`/action/return-mode`,
  bandeau visible) → chaque article entré est repris (ligne négative), jusqu'à extinction du mode ;
  c'est un mode JOUR (non remis à zéro entre tickets).
- **H19 Annulation d'un ticket payé** [S] (EXP-49) : rappel par numéro d'un ticket CLÔTURÉ →
  « ANNULER CE TICKET PAYÉ » sous aval superviseur (`/return/cancel-paid`, action
  `CANCEL_PAID_TICKET`) → l'original reste INTACT et un second document de reversal (négatif de
  l'original) est produit et journalisé `PAID_TICKET_CANCELLED` ; refus superviseur → message, rien.

- **H24 MODE RETOUR — entrée, paramètres et aval** [S] (LC-05-01-01/-03, LC-05-02-09/-14) : touche
  MODE RETOUR (`/action/return-mode`, `toggleReturnMode`) → notice `MODE RETOUR ACTIVÉ — LES ARTICLES
  SONT REPRIS`, ressortie → `MODE RETOUR TERMINÉ`. Trois paramètres : `return.in-sale-allowed` à faux +
  panier non vide → `RETOUR DANS UN TICKET DE VENTE INTERDIT` (et, une fois le mode pris, la ressortie
  est refusée tant qu'une ligne de reprise est présente → `TICKET DE RETOUR — VENTE INTERDITE SUR CE
  TICKET`) ; `return.without-origin-allowed` à faux → `RETOUR SANS TICKET D'ORIGINE INTERDIT` ;
  `return.dedicated-register` → mode forcé à la connexion (`AuthService`). L'ENTRÉE demande l'aval
  superviseur quand `gesture.endorsement-return` est actif (geste `return`), rejoué sous l'action
  `RETURN_MODE_TOGGLE` ; la SORTIE n'est jamais endossée.
- **H25 Prix réellement remboursé** [S] (LC-05-02-12) : `TicketLine.getEffectiveUnitPrice()` = total de
  ligne ÷ quantité, arrondi à quatre décimales (HALF_UP) — promotions du moteur et quote-part de remise
  ticket comprises, puisqu'elles sont déjà dans le total de ligne ; total ou quantité absents, ou quantité
  nulle → repli sur le prix unitaire brut. La TVA restituée suit ce montant.
- **H26 Recalcul moteur au retour & bloc RECALCUL DES AVANTAGES** [S] (LC-05-02-13, LC-05-07-02) : à la
  création du remboursement, `ReturnValuationService.recompute` interroge le moteur sur le panier
  CONSERVÉ ; trois statuts (`RECOMPUTED`, `NOT_APPLICABLE`, `UNAVAILABLE`) et quatre colonnes portées par
  le `Refund` (`withdrawnGenerosity`, `keptAdvantages`, `loyaltyRecalculationSent`, statut). Le ticket de
  retour imprime le bloc `RECALCUL DES AVANTAGES` : `AVANTAGE REPRIS <x> E` puis `Avantages conserves :`
  (RECOMPUTED) ; `Moteur indisponible :` / `recalcul non effectue` (UNAVAILABLE — le client est remboursé
  quand même) ; `Cagnotte et coupons :` / `recalcul transmis au programme` (origine portant une carte).
- **H27 Saisie libre contrôlée du retour** [S] (LC-05-02-11) : sur l'écran retour, `/return/enter-article`
  — un article scanné ou saisi désigne la LIGNE du ticket d'origine ; un code absent du ticket →
  `ARTICLE ABSENT DU TICKET D'ORIGINE` ; le plafond par ligne (quantité restante remboursable) reste
  l'autorité ; la page du détail se porte sur la ligne trouvée ; `/return/leave` ressort de l'écran.
- **H28 Touches retour préprogrammées** [S] (LC-05-04-01) : `return.types` administrés (`Libellé` ou
  `Libellé>TICKET`), rendus en touches (`/action/return-type`). Résolution PAR LIBELLÉ, jamais par
  position, relue à chaque tap ; libellé inconnu → `TYPE DE RETOUR INCONNU`, aucune sélection retenue ;
  journal `RETURN_TYPE_SELECTED` au libellé choisi. Un type `Libellé>TICKET` (dédié) route vers l'écran
  retour ; un type simple entre en MODE RETOUR par l'entrée gardée (aval compris).
- **H29 Vérification du PDV du ticket d'origine** [S] (LC-05-02-07) : à la sélection d'un ticket d'origine
  (`/return`, `selectTicket`), le code magasin du ticket est comparé à celui de la caisse ; différent →
  refus `TICKET D'UN AUTRE MAGASIN` ; `return.foreign-store-accepted` à vrai → accepté ; code magasin
  absent d'un côté ou de l'autre → le retour passe (pas de refus sur une référence manquante).

## I. Session & clôture Z [S]

- **I1 Rapport X** : à tout moment, lecture seule, imprimable, ne mute rien.
- **I2 Comptage Z** : calculette par coupures (billets/pièces/rouleaux au rouleau
  entier), total transmis en String, écart compté/théorique calculé, prélèvement,
  fonds suivant → session CLOSED, nouvelle session possible.
- **I3 Effets du Z** : PARKED annulés, vente bloquée (message MENU CAISSE) jusqu'à
  réouverture, flux du matin (A1) au prochain login.
- **I4 Z en formation** → bloqué.
- **I5 Écart au-delà du seuil** [S] (EXP-82) : `cash.variance-threshold` administré (usuel
  1 €) ; un comptage dont l'écart absolu dépasse le seuil ne clôture PAS — la caisse route
  vers l'écran d'arbitrage `/session/variance` (seuil rappelé), avec deux
  sorties : RECOMPTER (retour `/cash-count`) ou validation superviseur
  (`/action/session/close-endorsed`). La validation laisse la trace nominative
  (`varianceEndorsedBy` en base + journal `SESSION_CLOSED … écart validé par <badge>`) ;
  un superviseur refusé renvoie `/session/variance?error=supervisor`. Un écart ≤ seuil
  clôture directement comme aujourd'hui.
  - **Le MONTANT de l'écart n'est pas montré au caissier** [S] (recette) :
    `cash.variance-amount-visible` (défaut **non**) → l'écran annonce `AU-DELÀ DU SEUIL` sans le
    chiffre ; activé, il affiche le montant comme avant. Un caissier qui lit l'écart sait quoi
    changer pour le faire disparaître, et le comptage déclaré cesse d'être un comptage. Le
    montant reste sur le Z, dans le journal et dans le rapport que lit la responsable.
- **I6 Mode de fond de caisse et période de RAZ caissière** [S] (EXP-95, complété en recette) :
  `cash.float-mode` a **trois** valeurs, et c'est elle qui dit quand la caissière est
  réellement comptée — `FIXED` (fond fixe : comptage et RAZ caissière **chaque soir**, défaut),
  `FLOATING_WEEK_MONTH` (fond flottant : comptage en fin de semaine et le dernier jour du mois),
  `FLOATING_MONTH` (fond flottant : dernier jour du mois seulement, cas rare). `FLOATING` reste
  accepté comme ancienne écriture de `FLOATING_WEEK_MONTH`. La période existe pour que le CA
  d'une période corresponde à ce qui a été remis en banque. Les soirs qui NE sont PAS un jour de
  comptage complet (`isFullCountDay`), `close-start` clôture directement sans écran de comptage :
  prélèvement = surplus au-dessus du fond initial, `floatingNoCount=true`, AUCUN écart arbitré
  (journal « fond flottant sans comptage »). La RAZ du **financier point de vente**, elle, est
  quotidienne dans tous les modes : c'est la journée du MAGASIN, elle ne vit pas sur la caisse
  (bloc 09, imedge) → **[N]**.
- **I6b Prélèvement de clôture proposé** [S] (recette) : l'écran de comptage calcule le
  prélèvement au fil du comptage — **compté moins ce que le tiroir doit garder**, ce dernier
  valant fond de caisse + mouvements nets du jour (une monnaie reçue en apport AUGMENTE ce qu'on
  garde, un prélèvement déjà fait le diminue ; comptage juste → la différence est exactement la
  recette espèces). Le montant reste modifiable : dès que la caissière touche le champ, c'est le
  sien qui part. Tiroir inférieur au fond → zéro proposé, jamais de prélèvement négatif. Le
  défaut corrigé : la caissière faisait cette soustraction au crayon sur un papier.
- **I7 Moyens papier comptés** [S] (EXP-83, revu en recette) : l'écran de comptage liste les
  moyens papier déclarés (tenders `cashierDeclaration` : chèques, bons, avoirs) avec un champ de
  saisie **vide**, et la quantité théorique **n'est plus affichée** : un champ prérempli de la
  réponse attendue n'est pas une déclaration, c'est une validation. La caissière compte ses
  chèques et saisit ce qu'elle compte ; la déclaration (déclaré vs théorique) est postée en JSON
  (`paperDeclaration`), stockée sur la session et reportée au Z, où l'écart se lit.
- **I8 CA par caissier** [S] (EXP-79) : le rapport X/Z agrège, EN PLUS du total terminal
  (document du poste, inchangé), le CA TTC et le nombre de tickets PAR caissier
  (`Ticket.cashier`), rendu sous les totaux terminal.
- **I9 Bordereau de règlement autonome** [S] (EXP-80) : touche BORDEREAU DE RÈGLEMENT
  (`/action/session/settlement-voucher`) → document autonome listant les règlements par
  moyen + total TTC, indépendant du ticket de prélèvement.

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
- **L12 Réception du point de contact** (BO-03-09, BO-03-10) : le domaine
  `CHECKOUT_POINTS` est tiré juste après `ISLANDS` et appliqué par upsert sur
  l'identifiant du terminal. Un point absent du snapshot est **désactivé et jamais
  supprimé** ; il se réactive s'il revient. Une ligne sans identifiant, ou à
  identifiant vide, est ignorée avant toute recherche. Un type de TPV ou d'afficheur
  que cette caisse ne connaît pas retombe à `null` et non sur une valeur devinée : la
  colonne dit « le nœud ne l'a pas décrit », ce qui est la vérité.
- **L13 Ce que le point change à la caisse** (BO-03-09-01/05/13) : point **désactivé**
  → la prise de poste est refusée avec « CAISSE HORS SERVICE — POINT DE CONTACT
  DÉSACTIVÉ AU BACK OFFICE », avant même la vérification des identifiants, parce que
  le refus n'a rien à voir avec qui se présente. Point **absent** (nœud non à jour, ou
  premier tirage jamais abouti) → la caisse ouvre comme avant : un référentiel qu'on
  n'a pas reçu n'interdit rien. **Île** lue sur le point pour le contrôle des bons,
  le balayage inverse de `terminalIds` restant un repli. **Afficheur client déclaré
  `NONE`** → plus signalé en défaut à la porte, l'afficheur absent n'étant plus un
  afficheur en panne ; point absent ou type non décrit → afficheur attendu comme
  avant.
- **L14 Réglages résolus jusqu'au poste** (BO-03-09, BO-03-10) : une clé posée sur le
  point de contact d'une seule caisse ne descend que sur elle ; posée sur l'îlot, elle
  descend sur toutes ses caisses sauf celles qui la surchargent ; deux caisses du même
  magasin reçoivent des valeurs différentes dans le même cycle — c'est la vérification
  qui attrape une empreinte SETTINGS partagée entre terminaux.

## M. Thèmes [S]

- **M0 Barre à sept onglets** [S] (étape 8) : la barre du bas porte désormais les sept
  onglets de la nomenclature expert — Articles, Caisse, Règlement, Ticket, Générosité,
  Tiroir, Autre. Chaque touche est joignable sous son onglet (Articles : saisie/quantité/
  sous-total/info article ; Caisse : verrouiller/fermer/session/calculatrice/formation ;
  Règlement : encaisser/remises ; Ticket : attente/annuler/retour/facture/duplicata ;
  Générosité : fidélité/solde ; Tiroir : mouvement/prélèvement/apport/transfert/déconsignation ;
  Autre : superviseur/dégradé/badge/PIN/thème/rappel code). Aucune touche perdue au reslotting.

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

- **Q1 Mouvement générique** [S] (revu en recette) — **TROIS types** : DÉPENSE, ACOMPTE CLIENT,
  DÉCLARATION. Enregistrement → bandeau `MOUVEMENT ENREGISTRÉ` **et impression d'un justificatif**
  (type, motif, moyen, montant, ligne de signature) : ces trois mouvements ne laissaient aucun
  papier, une dépense sortait du tiroir sans pièce à classer. Au-delà du seuil administré sans
  superviseur → aval manager parqué (avertissement « Au-delà de <seuil> €, un aval manager sera
  demandé. ») ; formation → `INDISPONIBLE EN FORMATION` ; hors session → `AUCUNE SESSION OUVERTE` ;
  type inconnu → `TYPE DE MOUVEMENT INVALIDE` ; aval refusé/absent → `AVAL MANAGER REFUSÉ OU
  MANQUANT`. Tender vide = espèces.
  - **PRÉLÈVEMENT et APPORT y sont refusés** → `PRÉLÈVEMENT ET APPORT : ÉCRANS DÉDIÉS`. Ils ont
    leurs propres écrans, où ils sont comptés coupure par coupure et impriment leur bordereau ;
    le formulaire plat les enregistrait sans ce comptage — deux chemins pour un même geste, dont
    un dégradé.
  - **DÉPENSE : motif obligatoire**, choisi dans la liste administrée `cash.movement-reasons`,
    désormais une liste de motifs de DÉPENSE seulement (elle proposait « Apport de fond » pour
    une dépense). Motif absent ou blanc → `MOTIF OBLIGATOIRE`.
  - **ACOMPTE CLIENT : référence de commande obligatoire** → `RÉFÉRENCE DE COMMANDE OBLIGATOIRE`
    si elle manque. L'acompte passe par `recordAcompte` (EXP-85), qui porte la référence dans sa
    propre colonne : c'est elle que le rappel de la commande lit pour déduire ce qui a déjà été
    versé (croiser G21). La commande elle-même vit sur le nœud ; la caisse en cite la référence.
  - **DÉCLARATION : ni motif ni référence** — elle énonce un comptage, et cet énoncé est
    maintenant COMPARÉ (voir Q1b).
- **Q1b Écart de déclaration au X et au Z** [S] (recette) : le montant déclaré est mis en face du
  théorique espèces, à l'écran rapport comme sur le X/Z imprimé — `Espèces déclarées` et
  `Écart déclaration`. La DERNIÈRE déclaration espèces de la session gagne (un caissier qui
  déclare deux fois s'est corrigé) ; une déclaration portant un AUTRE moyen n'est pas les
  espèces et est ignorée ; aucune déclaration → aucune des deux lignes (une ligne à zéro
  énoncerait un comptage que personne n'a fait). Le mouvement lui-même ne déplace toujours rien.
  Le défaut corrigé : la déclaration était écrite et jamais relue.
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
  atteint par la touche **MVT FINANCIER** du menu TIROIR — la route n'était référencée par aucun
  bouton (trouvée en recette, écran inatteignable),
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
  `/change-order`, atteint par la touche **COMMANDE MONNAIE** du menu TIROIR — la route n'était
  référencée par aucun bouton, comme le mouvement financier (trouvé à la recotation HD_29) —
  listant toutes les dénominations euro ; « IMPRIMER BORDEREAU VIERGE » sort un
  bordereau ne portant que la liste des dénominations (colonnes montant et nbr. rouleaux vides)
  pour saisie manuscrite (LC-12-05-02).
- **Q7 Bordereau — saisie et calcul croisé** (LC-12-05-03/04/05/06/07, P2) : par dénomination,
  saisie d'un montant → nombre de rouleaux calculé (LC-12-05-06 : face 0,20 € roul.=8 € →
  8 € = 1 rouleau) ou saisie d'un nombre de rouleaux → montant calculé (LC-12-05-07 : face
  0,01 € roul.=0,50 € → 3 rouleaux = 1,50 €), le sens choisi (LC-12-05-05) pilotant la ligne ;
  correspondance administrée `change-order.roll-values` ; dénomination sans valeur de rouleau →
  rouleaux non proposés ; les deux sens se vérifient l'un l'autre.
- **Q8 Bordereau — impression rempli** (LC-12-05-08, complété en recette) : « IMPRIMER LE
  BORDEREAU » sort le bordereau portant le numéro de caisse, l'opérateur (numéro et nom),
  l'horodatage et un tableau dénomination / montant / nbr. rouleaux, cellules remplies ou vides
  selon la saisie, puis **`TOTAL A REMETTRE` et une ligne de signature** : c'est la procédure
  supermarché — la caissière remet ce papier, son numéro, son nom et **ce montant** à sa
  responsable, qui lui rend la monnaie. Le total somme les montants quel que soit le sens de
  saisie (un nombre de rouleaux compte par son montant calculé) ; une dénomination sans valeur de
  rouleau administrée ne porte aucun montant et compte pour zéro ; rien de saisi → total
  `0,00`. Le bordereau VIERGE (Q6), lui, n'énonce aucun total — il est fait pour être rempli à la
  main. Garde `change-order.print` désactivé → `IMPRESSION DU BORDEREAU DÉSACTIVÉE` ;
  dénomination inconnue → `DÉNOMINATION INCONNUE`.
  - En **hypermarché** la procédure est l'autre : la caissière demande ses rouleaux et le montant
    est saisi en **apport** (Q9), ce qui augmente ce que son tiroir doit garder — et c'est
    exactement ce que I6b déduit au moment de la clôture. L'échange lui-même n'est jamais
    enregistré sur la caisse, ni au départ ni au retour.

- **Q9 Apport de monnaie par coupure** [S] (EXP-15) : écran APPORT MONNAIE (`/apport`), saisie
  par coupure via les onglets billets/pièces/rouleaux (mêmes fragments `/cash-count/fragment`
  que le prélèvement), total calculé, VALIDER L'APPORT → mouvement `DEPOSIT` en base portant le
  `denominationDetail`, bordereau `APPORT DE MONNAIE` imprimé (total + signature) ; au-delà du
  seuil `cash.movement-endorsement-threshold` la modale superviseur s'ouvre (parking op=apport).
- **Q10 Calculatrice de fond** [S] (EXP-04, complété en recette) : écran CALCULATRICE DE FOND
  (`/cash-calculator`), comptage par coupure billets jusqu'à 200 €, total affiché en direct,
  première coupure sélectionnée d'emblée (voir C-pré-saisie), REMETTRE À ZÉRO ; aucun mouvement
  en base, aucun `TechnicalEvent` — distinct du comptage Z opposable.
  **IMPRIMER LE COMPTAGE** (`/action/cash-calculator/print`) sort un ticket
  `COMPTAGE DE CONTROLE (fond de caisse)` : coupures comptées, total, mention « document de
  controle - aucun mouvement », puis bandeau `COMPTAGE IMPRIMÉ`. Imprimer n'est pas enregistrer :
  le total et le détail partent le temps d'un ticket, rien n'est écrit. Total nul → rien
  n'est imprimé et l'écran revient sans erreur (un comptage pas encore fait n'est pas une faute).
  C'est le papier que la caissière garde le matin et montre à sa responsable quand le fond n'est
  pas juste.
- **Q11 Plafond de contenu du tiroir (seuil d'assurance)** [S] (EXP-17, complété en recette) :
  `cash.drawer-cap` administré — le montant que l'assurance garantit sur **tout le caisson**,
  fond de caisse ET recette, ce que le théorique espèces mesure déjà. Quand il est dépassé,
  l'écran de vente affiche le bandeau `PRÉLÈVEMENT BILLETS — CONTENU TIROIR x,xx €` suivi du
  lien **PRÉLEVER**, sans bloquer la vente ; plafond 0 → aucun bandeau. Le lien ouvre
  `/withdrawal?method=CASH&notes=1` : comptage **BILLETS SEULEMENT**, les onglets PIÈCES et
  ROULEAUX disparaissent et l'écran le dit (« les pièces restent pour rendre la monnaie »). Ce
  qui ramène un tiroir sous le plafond, c'est la liasse. C'est une présentation, pas une règle :
  le chemin d'enregistrement est le même.
- **Q12 Le plafond se dit aussi à l'écran de session** [S] (BO-10-02-11, 12/10) : le même
  bandeau — même chiffre, même phrase, même lien **PRÉLEVER** vers le mode billets — figure
  désormais sur `/session`. C'était le seul écran muet alors que c'est celui où le caissier
  vient agir : rapport X, prélèvement, clôture. Les deux écrans lisent le même
  `drawerCapExcess()` et formatent la même clé de message, et deux tests tiennent la phrase
  littérale des deux côtés pour qu'elle ne diverge pas. Aucun geste de session n'est bloqué
  par un tiroir plein : plafond non atteint, non administré ou session fermée → aucun bandeau,
  pas un bandeau vide.

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
- **S2 Demande de service — liste administrée** [S] (EXP-84) : l'écran `/supervisor` propose les
  motifs de `supervisor.service-reasons` (liste administrée, points-virgules) au lieu des quatre
  boutons figés ; motif vide → liste vide.
- **S3 Message libre caisse → back-office** [S] (EXP-19) : `POST /action/supervisor-message`
  (reason + message libre) → `callSupervisor` journalise `SUPERVISOR_CALLED` (« reason — message »)
  et pousse vers le nœud ; message vide → motif seul. Round-trip réel = résidu [N] (nécessite imedge).
- **S4 Message back-office → caisse (non perturbant)** [N] (EXP-20) : `POST /api/bo-message`
  (text) déposé par le nœud → bandeau non bloquant sur l'écran caissier seul
  (`state.backOfficeMessage`, survit à la vente). Producteur = imedge → résidu [N] ; la
  consommation caisse est testée unitairement (BackOfficeMessageResourceTest).
- **S5 Sondage back-office** [N] (EXP-89) : `POST /api/bo-message` avec `question`+`options` →
  sondage affiché ; `POST /action/survey-answer` renvoie la réponse en amont (reason `SONDAGE`)
  et efface le sondage. Round-trip = résidu [N].

## T. Langues [S]

Chantier LC-13-06, absent du catalogue jusqu'ici. Textes, routes et paramètres vérifiés dans
`ui/LanguageResource`, `ui/Messages`, `ui/SessionCheckFilter`, `ui/PosState`, `domain/people/Employee`.

- **T1 Choix de langue par l'opérateur** [S] (LC-13-06-03) : entrée LANGUE du menu AUTRE (lien
  `/language-select`, libellé `{inject:i18n.get('common.language')}`, joignable aussi depuis l'écran de
  lock) → écran listant les langues offertes plus le choix « langue du magasin » ; `POST /action/language`
  (form `language`) retient le tag, blanc = suivre le magasin (`state.language=null`) ; redirection vers
  l'écran d'origine (`/lock` si verrouillé, `/` sinon), le nouveau libellé rendu aussitôt. Le choix vit
  dans l'état caisse et N'EST PAS écrit sur la fiche salarié.
- **T2 Chaîne de résolution** [S] (LC-13-06-01/-02/-05) : langue rendue (`Messages.language()`) = langue
  choisie à l'écran (`state.language`), sinon langue du salarié connecté (`Employee.language`), sinon
  `language.system`, sinon `fr` ; chaque étape est ignorée si le tag n'est pas une langue livrée
  (fr/nl/pt). Les quatre atterrissages se vérifient sur un même écran (`common.back` = RETOUR/TERUG/VOLTAR).
- **T3 Changement interdit pendant la session** [S] (LC-13-06-04) : `language.operator-change` à faux +
  session ouverte → le choix n'est PAS pris (`chooseLanguage` ressort sans muter) et l'écran l'indique
  (`allowed=false`) ; caisse verrouillée, le changement est toujours permis (`changeAllowed = isLocked ||
  language.operator-change`). ÉCART catalogue : le cahier attendait « l'écran de choix n'est pas offert »,
  le code garde la touche visible et refuse le choix.
- **T4 Liste restreinte aux langues offertes** [S] (LC-13-06-04) : `language.available` limite les langues
  proposées à l'intersection avec les bundles livrés (`getAvailableLanguages`), sans doublon et jamais
  vide — le français (`DEFAULT_LANGUAGE`) est ajouté quand l'intersection est vide.
- **T5 Double affichage client bilingue** [S] (LC-13-06-06/-11) : `language.customer-second` administré →
  visu client et ticket en DEUX langues, la SECONDE d'abord, séparées par un slash
  (`Terug te geven / A rendre`, `Messages.getSecondLang`/`both`) ; repli sur la langue unique quand il n'y
  a pas de seconde langue, quand c'est la langue rendue, ou quand les deux rendus sont identiques.
- **T6 Déconnexion remet la langue à zéro** [S] (LC-13-06) : le logout remet `state.language` à null
  (`PosState`), le choix d'écran meurt à la déconnexion et l'opérateur suivant repart sur la résolution
  par défaut.
- **T7 Langue inconnue ou bundle absent → français** [S] (LC-13-06-05/-10) : une langue choisie ou
  administrée hors bundles, ou un bundle introuvable, retombe sur le français — jamais une clé crue à
  l'écran (`bundle`/`load` rendent une map vide, `get` retombe sur la clé par défaut fr).
- **T8 Routes langue et gardes session/verrou** [S] (LC-13-06-03) : `/language-select` et
  `/action/language` sont hors du gate SESSION (`ALLOWED_PREFIXES` de `SessionCheckFilter`) → joignables
  une fois connecté SANS session (écran session) comme en session. Ils sont AUSSI dans
  `ALLOWED_PREFIXES` de `LockCheckFilter` : caisse VERROUILLÉE, l'écran de langue répond au lieu
  d'être dérouté — c'est l'écran qu'une caisse fermée offre, et le lien langue de `lock.html` y
  mène vraiment (LC-13-06-03 : choisir sa langue AVANT la prise de poste).

## O. Inventaire exhaustif — messages d'erreur & modales

Relevé PAR LE CODE (grep des canaux d'affichage) : chaque vérification = déclencheur →
texte EXACT → effacement. Périmètre valorisation volontairement non étendu (module dédié) :
ses trois bandeaux sont référencés, pas approfondis.

### O-A. Zone message du ticket (transitoire — effacée au scan suivant) [S]

Trois scénarios portent la ZONE elle-même (affichage puis effacement au geste suivant) ; les autres lignes du tableau sont inventoriées ici et vérifiées depuis leur groupe d'origine.
- **OA1** Zone message au scan : `AUCUNE SESSION OUVERTE - MENU CAISSE`, `PRODUIT INTERDIT À LA VENTE`, `CODE INCONNU: <code>`, `PRODUIT INTROUVABLE`, `PLU INTROUVABLE`, `ARTICLE BALANCE INTROUVABLE (<plu>)`, `ÉTIQUETTE DÉJÀ SCANNÉE`, `POIDS INVALIDE`, `ERREUR POIDS IDENTIQUE`, `ERREUR PRIX SAISI`, `BON DE CONSIGNE ILLISIBLE` — chacun affiché puis effacé au scan valide suivant.
- **OA2** Zone message sur action de ligne : `AUCUNE LIGNE SÉLECTIONNÉE`, `LIGNE INTROUVABLE`, `QUANTITÉ INVALIDE (1-999)`, `QUANTITÉ NON MODIFIABLE SUR CETTE LIGNE`, `VALEUR INVALIDE`.
- **OA3** Zone message, gardes d'état : `TERMINEZ OU ANNULEZ LE TICKET D'ABORD`, `FONCTION INDISPONIBLE EN FORMATION` (réimpression en formation), `SUPERVISION NON CONFIGURÉE SUR CETTE CAISSE`, `PAIEMENT REFUSÉ PAR LE TPE` ; les quatre sorties superviseur par le nœud sont du résidu [N].

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
| Réimpression du dernier ticket en formation | `FONCTION INDISPONIBLE EN FORMATION` |
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

### O-B. Messages de page (query param / état) [S] (scénario **OB1**)

- **Lock** : identifiants faux → `IDENTIFIANTS INCORRECTS` ; compte verrouillé →
  `COMPTE VERROUILLÉ - RÉESSAYEZ PLUS TARD` (persiste pendant le lockout, badge compris).
- **Session** : ouverture en formation → `INDISPONIBLE EN FORMATION` ; échec d'ouverture
  → message `open-failed` rendu.
- **Changement de PIN** : PIN actuel faux → `Code PIN actuel incorrect` ; format →
  `Le nouveau code doit comporter 4 chiffres` ; confirmation ≠ →
  `Les nouveaux codes ne correspondent pas` ; succès → `Code PIN modifié`.

### O-C. Écran retours (staging — persiste jusqu'à correction) [S] (scénario **OC1**)

- Quantités nulles partout → `RIEN À REMBOURSER`.
- Ligne au-delà du restant remboursable → `QUANTITÉ DÉJÀ REMBOURSÉE (<détail>)`.
- Montant libre > plafond → `PLAFOND DU TICKET DÉPASSÉ (DÉJÀ REMBOURSÉ : <montant>)`.
- Méthode demandée en formation → `RETOURS INDISPONIBLES EN FORMATION`.
- Ces gardes se re-vérifient EN TRANSACTION : provoquer l'écart entre staging et
  exécution (2e remboursement intercalé) → même message, rollback complet.

### O-D. Bons de paiement (zone bon de l'écran pay) [S] (scénario **OD1**)

- Numéro ne matchant aucun type → `Type de bon inconnu`.
- Type MANUAL : numéro invalide → `Numéro non reconnu — vérifiez la saisie`.

### O-E. Modales & overlays — cycle complet de chaque surface [S]

- **OE1** Modales geste & avenant : ouverture des quatre types (REMISE / DISCOUNT / FORCE_PRICE / QUANTITY), saisie invalide → `VALEUR INVALIDE`, ANNULER inerte (aucune trace), QUANTITY direct, REMISE par l'avenant ; PIN manager faux → `AUTORISATION REFUSÉE` (compteur partagé), badge manager pré-rempli, ANNULER → geste abandonné.
- **OE2** Overlay TPE & modale de complétion : demande CB (montant affiché), ACCEPT règle et complète, REFUSE → `PAIEMENT REFUSÉ PAR LE TPE`, ANNULER caisse retire la demande ; modale de complétion à solde nul (IMPRIMER = draft OPEN, NOUVELLE VENTE = clôture fiscale, `Rendu Client` si trop-perçu).
- **OE3** Interstitiel tiroir & bandeaux : GET bloqué → `/drawer-error` → retour AU CHEMIN à la fermeture ; POST bloqué → retour au REFERER, jamais de rejeu ; bandeau FORMATION (z-50) sur tous les écrans.

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

### O-F. Écran paiement — règles de tender & moyens physiques [S] (scénario **OF1**)

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

### O-G. Écran paiement — crédit client, devise, secours [S] (scénario **OG1**)

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

### O-H. Bons d'achat & consultation de solde [S] (scénario **OH1**)

| Déclencheur | Message exact |
|---|---|
| Type de bon inconnu | `Type de bon inconnu` |
| Numéro de bon non reconnu | `Numéro non reconnu — vérifiez la saisie` |
| Instrument registre inconnu | `BON INCONNU AU REGISTRE` |
| Instrument registre épuisé | `BON DÉJÀ UTILISÉ - SOLDE ÉPUISÉ` |
| Instrument déjà scanné sur la vente | `BON DÉJÀ SCANNÉ SUR CETTE VENTE` |
| Consultation solde vide | `AUCUN NUMÉRO SAISI` |

### O-I. Fidélité — recherche, consultation, cagnotte [S] (scénario **OI1**)

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

### O-J. Facturation [S] (scénario **OJ1**)

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

### O-K. Mouvements de caisse, réimpression, retours [S] (scénario **OK1**)

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

### O-L. Messages de page — session, clôture, supervision [S] (scénario **OL1**)

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

Relevé MESURÉ sur `src/test/java/com/intermarche/e2e` (identifiants normalisés — `b01` ≡ `B1`) :
**272 scénarios catalogués, 124 portés par un test, 148 restants.** Une classe `Group<X>IT` par
groupe, un `@Test` par scénario, l'identifiant dans le nom de la méthode. Le catalogue a été
ré-audité contre `src/main` (ressources `ui/*`, filtres, services, imports, sync) ; les 13 entrées
à identifiant partagé ont été dédoublonnées (T1), l'inventaire O numéroté (T2, 16 scénarios dont 6
portés par `GroupOIT`), et les chantiers retours (H24–H29) et langues (T1–T8) ajoutés (T3).

| Groupe | Catalogués | Testés | Reste | Non portés |
|---|---|---|---|---|
| A. Prise de poste | 17 | 10 | 7 | A10 ([N]) · A11–A16 ([S], EXP verrou/forçage/accueil/report) |
| B. Vente | 37 | 24 | 13 | B25–B29 (tare LC-14) · B30–B32 (2e passe) · B33–B37 (prix embarqué/famille/F&L/répéter) |
| C. Gestes de prix | 16 | 8 | 8 | C9–C14 · C15–C16 (pré-saisie) |
| D. Fidélité | 16 | 8 | 8 | D9–D14 · D20–D21 |
| E. Valorisation continue | 14 | 0 | 14 | E1–E14 ([V], moteur seedé miroir) |
| F. Paiement & clôture | 29 | 18 | 11 | F7 ([V]) · F20–F22 · F30–F36 (îlot/arrondi/annulation) |
| G. Après-vente | 12 | 5 | 7 | G6/G20–G23 ([N] rappel code) · G7 · G30 ([S] SMS) |
| H. Retours | 29 | 9 | 20 | H10–H29 ([S] — motif, partiel, crédit, avoir, sous-total, mode retour, recalcul, saisie, types, PDV) |
| I. Session & clôture Z | 10 | 4 | 6 | I5–I9, I6b ([S], EXP écart/fond/moyens/CA/bordereau) |
| J. Mode formation | 3 | 3 | 0 | — |
| K. Reprise & robustesse | 3 | 3 | 0 | — |
| L. Consolidation magasin | 14 | 0 | 14 | L1–L14 ([N]/[C], pile magasin) |
| M. Thèmes | 5 | 4 | 1 | M0 |
| N. Compléments | 11 | 7 | 4 | N3,N4 ([V]) · N5,N9 ([N]) |
| O. Inventaire messages/modales | 16 | 6 | 10 | OB1,OC1,OD1,OF1,OG1,OH1,OI1,OJ1,OK1,OL1 ([S]) |
| P. Facturation | 8 | 8 | 0 | — |
| Q. Mouvements de caisse | 12 | 3 | 9 | Q4–Q11, Q1b ([S], mouvement financier/bordereau/apport/calculatrice/plafond) |
| R. Consommation & budgets | 7 | 3 | 4 | R1,R2,R4,R6 ([V]) |
| S. Supervision locale | 5 | 1 | 4 | S2,S3 ([S]) · S4,S5 ([N]) |
| T. Langues | 8 | 0 | 8 | T1–T8 ([S]) |
| **Total** | **272** | **124** | **148** | |

Les 124 testés incluent les 6 scénarios O portés par `GroupOIT` (OA1–OA3, OE1–OE3), désormais
catalogués. Le tag de chaque scénario fait foi pour la suite [S]/[V]/[N]/[C] : seuls les [S]
s'écrivent caisse seule + simulateur ; les [V] demandent le valorisateur, les [N] le nœud magasin,
les [C] la brique centrale.
