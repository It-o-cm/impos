# Calibrage Playwright — scénario A1 (Prise de poste nominale)

Pivot de la campagne e2e du HTTP/RestAssured vers le **vrai bout-en-bout
navigateur** : `@QuarkusTest` + Playwright (Chromium headless) via
`io.quarkiverse.playwright:quarkus-playwright:2.3.8`. Ce document calibre le
coût réel d'un scénario rejoué à l'écran, avant d'ouvrir la rafale A2–A7.

## Résultat

- **Scénario** : A1 — badge `12341234` → PIN au pavé → écran SESSION (aucune
  session) → fonds `200 €` → OUVRIR → écran de VENTE ; session `C04-Sxxxxx`
  OPEN en base.
- **Statut** : **VERT au premier essai. Itérations = 1.** Aucune reprise, aucun
  ajustement de sélecteur post-écriture (les sélecteurs ont été dérivés en
  lisant les templates avant d'écrire).
- **Commande** :
  `mvn -q verify -Dsurefire.skip=true -Dit.test=GroupAIT -DskipITs=false`
- **Preuve failsafe** : `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0` —
  `Time elapsed: 26.50 s`.

## Durée du run

| Mesure | Valeur |
|---|---|
| Wall-clock total de la commande | **~56 s** |
| Test-set failsafe `GroupAIT` (boot Quarkus IT + lancement navigateur + scénario) | **26,5 s** |
| Reste (~30 s) | suite unitaire surefire **exécutée quand même** (voir point dur 6) |

Le scénario lui-même (navigation, scan, 4 taps PIN, saisie du fond, ouverture)
est négligeable devant le boot de l'app IT et le démarrage du contexte
navigateur. Le vrai coût fixe d'un IT Playwright, ici, c'est **~26 s de
boot** — pas le nombre de gestes.

## Points durs rencontrés

### 1. Démarrage / téléchargement du navigateur
- Chromium était déjà en cache (`~/Library/Caches/ms-playwright/chromium-1228`,
  daté du 15 juin) : **pas de téléchargement de Chromium** sur cette machine.
- **Mais** ce premier run de l'extension a tout de même provisionné
  `firefox-1532` et `webkit-2311` (mtime = aujourd'hui 09:51) : le bundle
  driver Playwright tire les navigateurs manquants au premier usage, même si on
  ne pilote que Chromium.
- Conséquence sur une machine froide / CI : budgéter **un** téléchargement
  unique (Chromium + Firefox + WebKit, ~300 Mo) avant le premier test. Les runs
  suivants réutilisent le cache et ne paient rien.

### 2. Sélecteurs
- L'écran de lock est un **overlay JS caché** (`#resumeScreen`) : rien n'est
  cliquable tant que le badge (ou `showResume()`) ne l'a pas activé. On attend
  donc le **texte du catalogue** `Entrez votre code PIN :`, jamais une attente
  fixe.
- Le pavé numérique est rendu dynamiquement dans `#keyboardArea` ; les touches
  sont de vrais `<button>`. On les cible par rôle+nom exact **en scoppant sous
  `#keyboardArea`** pour ne pas heurter les bascules `CODE PIN RAPIDE` /
  `IDENTIFIANT`. La validation est `#actionBtn` (« CONNEXION »).
- **Surprise** : l'écran SESSION n'a **pas** de pavé PosInput. Le fond de caisse
  est un `<input>` natif `#openingFloat` (`inputmode="decimal"`). La « saisie au
  pavé » se fait donc au clavier (`fill("200,00")`), pas via un numpad à l'écran.
- Le clavier ID est **alpha (AZERTY sans chiffres)** : un numéro de badge ne
  peut pas y être tapé — d'où le point 4.

### 3. Timing et interaction avec les polls
- Ordre impératif : **naviguer `/lock` d'abord, injecter le badge ensuite**.
  `GET /lock` déconnecte l'opérateur et **vide la boîte aux lettres badge** ;
  injecter avant navigation perd le badge.
- Le poll `/lock-data` est à **1 s** et la boîte est **one-shot** (consommée à
  la première lecture). On ne « dort » pas 1 s : on laisse l'auto-wait de
  Playwright bloquer sur l'apparition du prompt PIN. Zéro `sleep`.

### 4. Le badge n'a aucune affordance à l'écran
- Un lecteur de badge est un **périphérique**. Dans un navigateur headless, la
  seule façon fidèle de présenter un badge est de le pousser sur le **bus
  matériel** : `POST /api/pos/scan` (`text/plain`, code 8 chiffres). Le
  `AuthScanHandler` le dépose dans la boîte lock, le poll le récupère, l'overlay
  bascule en saisie PIN. C'est **le seul geste hors-écran** d'un login par
  ailleurs 100 % à l'écran — assumé et documenté.

### 5. Matériel fail-soft (bruit de log attendu)
- L'unlock ouvre le tiroir (`openDrawer()`), tout comme le Z. Avec une
  `hardware-api.url` morte, RESTEasy jette une `ConnectException` **journalisée
  en stacktrace** mais **avalée** par `HardwareService` : le test passe. La
  valeur `http://localhost:0` se dégrade en `localhost:80`. Bruyant, inoffensif.

### 6. `-Dsurefire.skip=true` **n'a pas** sauté la suite unitaire
- 120 rapports surefire ont été (ré)écrits à 09:51:02 : surefire **ignore** la
  propriété `surefire.skip`. La rafale paierait donc à chaque run **toute la
  suite unitaire** en plus des IT (~30 s ici). À trancher pour la rafale : soit
  un vrai mécanisme de skip côté surefire, soit l'assumer.

## Lignes que je proposerais d'ajouter au contrat (NON appliquées ici)

Pour que A2–A7 et la rafale ne re-paient pas ces découvertes, j'ajouterais au
bloc « E2E scenario tests » du CLAUDE.md :

- **Recette de login réutilisable** : « Présenter le badge par
  `POST /api/pos/scan` (`text/plain`, code 8 chiffres) APRÈS avoir navigué
  `/lock` ; le poll `/lock-data` (1 s) bascule l'overlay en saisie PIN — attendre
  le texte `Entrez votre code PIN :`, taper le PIN sur les boutons de
  `#keyboardArea` (rôle+nom exact), valider avec `#actionBtn`. Jamais de
  `sleep`, toujours l'auto-wait. »
- **Table d'ancrages de sélecteurs (groupe A)** : lock `#resumeScreen` /
  `#keyboardArea` / `#actionBtn` / prompt `Entrez votre code PIN :` ; session
  `#openingFloat` (champ natif, pas de numpad → `fill`), boutons
  `OUVRIR LA SESSION`, titre `Aucune session ouverte` ; vente `TOTAL À PAYER`,
  opérateur dans `#opInfo`.
- **Aiguillage post-unlock** : sans session → écran SESSION ; **session déjà
  ouverte (A2) → directement l'écran de VENTE** (pas de détour session) — à
  asserter par `TOTAL À PAYER`, pas par l'écran session. Le mode formation saute
  aussi le détour.
- **Le badge est un geste matériel**, pas un formulaire : le clavier IDENTIFIANT
  est alpha sans chiffres, un badge numérique ne peut pas y être tapé — toujours
  passer par le bus scan.
- **Profil partagé** : extraire `GroupAIT.E2eProfile` en classe top-level
  `E2eTestProfile` (pin `pos.terminal.id=C04` + `quarkus.rest-client.hardware-api.url`)
  pour que chaque `GroupXIT` la réutilise sans la redéclarer.
- **Matériel fail-soft attendu** : les stacktraces `ConnectException` sur unlock
  et Z (ouverture tiroir) sont normales avec une `hardware-api.url` morte — ne
  pas les confondre avec un échec.
- **Commande de campagne** : `-Dsurefire.skip=true` est **inopérant** (la suite
  unitaire tourne quand même). Pour la rafale, soit sauter réellement surefire
  (config plugin / profil dédié), soit documenter que la suite unitaire
  co-tourne — sinon chaque run de rafale paie ~30 s de tests unitaires.
- **Coût fixe par IT** : ~26 s de boot Quarkus + démarrage navigateur, quasi
  indépendant du nombre de gestes — regrouper plusieurs scénarios par classe
  `@QuarkusTest` (un seul boot par groupe) plutôt qu'émietter en classes.
- **Provisioning navigateur** : budgéter un téléchargement unique du bundle
  Playwright (Chromium + Firefox + WebKit) au premier run d'environnement.

## Périmètre / résidu

- Seul **A1 [S]** est implémenté (campagne de calibrage). A2–A7 restent à
  écrire ; les scénarios `[V]`/`[N]` du groupe A : aucun (groupe A entièrement
  `[S]`).
- Aucune modification `src/main`. Modifications autorisées consommées :
  `pom.xml` (dépendance test Playwright, une fois) et la section architecture du
  `CLAUDE.md`. Rien n'a été committé.
