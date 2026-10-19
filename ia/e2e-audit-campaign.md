# Campagne e2e — ré-auditer le catalogue, puis le combler

*Établie le 06/10/2026. Les chiffres du §1 sont mesurés sur le dépôt, pas
estimés : ils sont le point de départ et ils font foi.*

Deux travaux, dans cet ordre, le premier commandant le second :

1. **revisiter et compléter la liste des cas de test e2e** — `e2e-scenarios.md`
   à la racine a dérivé derrière le code ;
2. **écrire, corriger et compléter les tests** qui portent cette liste.

Ne commence pas le second avant d'avoir fini le premier : écrire des tests
contre un catalogue faux, c'est payer deux fois.

---

## 0. Ce qui est déjà écrit et qu'on ne réécrit pas

- **`CLAUDE.md`, section « E2E scenario tests (*IT classes) — separate
  contract »** : le contrat technique complet — recette de connexion par badge
  et PIN, doctrine de sélecteurs, profil de test, port, commande de run, coût
  d'un boot. **À lire en entier avant la première ligne** et à ne pas
  re-déduire : chaque règle y est le prix d'une journée déjà passée.
- **`ia/e2e-completion-campaign.md`, section « Ce qui a coûté cher aujourd'hui »** :
  sept pièges déjà payés — passer par la route et non par le libellé, viser un
  rôle et non un texte, nommer l'état observé dans chaque assertion, attendre un
  effet asynchrone sur l'état serveur, `@AfterEach` qui remet la caisse à zéro,
  un test qui révèle un bug reste rouge, jamais de ré-enregistrement de golden
  master. À appliquer, pas à redécouvrir.
- **`com.intermarche.demo.Demo*IT`** : le pré-vol de démonstration. **On n'y
  touche pas.**
- **Les tests existants ne se redécoupent pas.** Un `@Test` couvre un scénario
  entier, cinq assertions en moyenne : les nouveaux s'écrivent au même grain.

---

## 1. Première partie — le catalogue

### 1.1 L'état réel, mesuré

| Mesure | Valeur |
|---|---|
| Entrées de scénario dans `e2e-scenarios.md` | **242** |
| Identifiants distincts | **227** |
| Méthodes `@Test` dans `com.intermarche.e2e` | **124** |
| Identifiants du catalogue portés par un test | **118** |
| **Scénarios catalogués sans test** | **109** |

Quatre défauts, à traiter dans l'ordre.

**D1 — Treize identifiants portent deux ou trois scénarios différents.**
`B25`, `B26`, `B27`, `B28`, `B29`, `C9`, `C10`, `F20`, `F21`, `I6`, `Q1` deux
fois ; **`H13` et `H14` trois fois chacun**. Les passes successives ont ajouté
en fin de section sans renuméroter : le groupe H porte aujourd'hui un `H13`
« sous-total », un `H13` « remboursement partiel » et un `H13` « retour par scan
du numéro », qui n'ont rien à voir.

**D2 — Les sous-sections `O-A` à `O-L` n'ont pas d'entrée numérotée**, alors que
six méthodes de test citent déjà `OA1`, `OA2`, `OA3`, `OE1`, `OE2`, `OE3`. Le
catalogue doit rattraper les tests, pas l'inverse.

**D3 — Le tableau « Couverture » de fin de document annonce « 157 scénarios
catalogués, 118 portés ».** Le 157 est faux de soixante-dix ; le 118 est juste
par coïncidence. Groupe par groupe, le tableau est à refaire.

**D4 — Trois chantiers livrés n'ont aucune entrée, ou une entrée périmée** (§1.3).

### 1.2 Un piège de comptage, à désamorcer avant de compter

Les noms de méthode sont tantôt zéro-padés (`b01_`, `d07_`), tantôt non
(`h13_`, `f19_`). **`B1` et `b01` sont le MÊME scénario.** Normalise les zéros
de tête des deux côtés avant toute comparaison, sinon tu « découvres » une
vingtaine de faux manques et tu écris des tests qui existent déjà.

### 1.3 Ce qui manque au catalogue

Trois chantiers sont dans `src/main` et absents — ou périmés — au catalogue.
Pour chacun, les entrées sont à écrire en nommant **le texte exact affiché, la
route exacte, le paramètre exact, l'effet en base ou au journal**. Un attendu
approximatif coûte un run de vingt-six secondes pour rien.

**A — Retours, lots R1 à R6.** Le groupe H a douze tests et dix entrées
récentes sans test ; en plus, six comportements livrés depuis n'y sont pas du
tout. Numérote-les à la suite du premier identifiant H libre après le
dédoublonnage, et porte le code d'exigence à côté du titre comme le font les
entrées récentes.

- `LC-05-01-01` / `-03`, `LC-05-02-09` / `-14` — la touche **MODE RETOUR**
  (`/action/return-mode`) et ses trois paramètres : `return.in-sale-allowed`
  (reprise interdite dans un ticket qui porte déjà des ventes),
  `return.dedicated-register` (mode forcé à la connexion, la touche refuse de
  quitter le mode), `return.without-origin-allowed`. L'entrée dans le mode
  demande l'aval superviseur quand `gesture.endorsement-return` est actif, et
  l'aval est rejoué sous l'action `RETURN_MODE_TOGGLE` ; la sortie du mode est
  refusée tant que le ticket porte une ligne de reprise.
- `LC-05-02-12` — le **prix réellement remboursé** :
  `TicketLine.getEffectiveUnitPrice()`, total de ligne divisé par sa quantité,
  quatre décimales. Promotions du moteur et quote-part de remise ticket
  comprises ; la TVA restituée suit le même montant.
- `LC-05-02-13` et `LC-05-07-02` — **l'appel au moteur au retour** et le bloc
  `RECALCUL DES AVANTAGES` du ticket : `AVANTAGE REPRIS` chiffré,
  `Avantages conserves`, `Cagnotte et coupons` transmis, et **`Moteur
  indisponible` moteur éteint, le client remboursé quand même**. Les trois
  statuts de `ReturnValuationService` et les quatre colonnes du `Refund` sont
  autant d'attendus distincts.
- `LC-05-02-11` — la **saisie libre contrôlée** : `/return/enter-article`,
  l'article scanné ou saisi désigne la ligne du ticket d'origine, un code absent
  du ticket est refusé, le plafond par ligne reste l'autorité, la page du détail
  se porte sur la ligne trouvée, `/return/leave` ressort.
- `LC-05-04-01` — les **touches retour préprogrammées** : `return.types`, la
  résolution PAR LIBELLÉ, le journal `RETURN_TYPE_SELECTED`, un type
  `Libellé>TICKET` qui route vers l'écran retour contre un type simple qui entre
  en MODE RETOUR par l'entrée gardée.
- `LC-05-02-07` — la **vérification du PDV du ticket d'origine** :
  comparaison par code magasin, refus annoncé, `return.foreign-store-accepted`
  qui l'accepte, et référence absente d'un côté ou de l'autre → le retour passe.

**B — Les langues : un groupe neuf, `T. Langues [S]`.** La lettre T est libre.
Le chantier `LC-13-06` est entièrement hors catalogue : aucune occurrence de
« langue » dans `e2e-scenarios.md`. Minimum à cataloguer, à compléter par
l'audit du code :

- le choix de langue par l'opérateur (`/language-select`, `/action/language`,
  entrée `LANGUE` du menu AUTRE) ;
- la chaîne de résolution, dans cet ordre : langue choisie à l'écran, puis
  langue du salarié (`Employee.language`), puis `language.system`, puis `fr` ;
- `language.operator-change` à faux → l'écran de choix n'est pas offert ;
- `language.available` qui restreint la liste proposée ;
- `language.customer-second` → le double affichage, la seconde langue d'abord
  (`Terug te geven / A rendre`) ;
- la déconnexion qui remet `state.language` à null ;
- une langue inconnue ou un bundle absent → repli sur le français, jamais une
  clé crue à l'écran ;
- `/language-select` et `/action/language` joignables hors session
  (`SessionCheckFilter`).

**C — Ce que l'audit trouvera.** Reprends la méthode de la deuxième passe :
parcours les routes des ressources de `ui/*`, les gabarits de
`src/main/resources/templates`, et les clés ajoutées au catalogue de
`PosSettingsService` ; pour chacune, demande si un scénario la nomme. Ce qui
n'est nommé nulle part devient une entrée.

### 1.4 Les quatre travaux, et leurs règles

**T1 — Dédoublonner et renuméroter (D1).** L'entrée **la plus ancienne garde
son identifiant** ; la plus récente prend le premier numéro libre du groupe. Si
un test porte l'identifiant déplacé, **son nom de méthode et sa javadoc suivent
dans le même commit** — un identifiant qui ne désigne plus la même chose est un
faux témoignage.

**T2 — Numéroter les sous-sections O (D2)**, en adoptant les identifiants que
les tests citent déjà (`OA1`…`OE3`), et en continuant pour les sous-sections qui
n'en ont aucun.

**T3 — Ajouter ce qui manque (D4, §1.3)**, chaque entrée portant son tag
d'infrastructure `[S]` / `[V]` / `[N]` / `[C]` et son code d'exigence.

**T4 — Refaire le tableau de couverture (D3)** avec les chiffres mesurés, dans
`e2e-scenarios.md` lui-même. **Ce document est le seul** : pas de fichier de
suivi à côté, pas de tableau dupliqué dans un rapport.

**Règle qui vaut pour les quatre : on n'invente aucun comportement.** Toute
entrée ajoutée ou corrigée est vérifiée dans `src/main` — texte exact, route
exacte, nom de paramètre exact. Un scénario qui ne nomme aucun attendu
observable (écran, base, journal, impression) n'est pas un scénario.

Livraison de la première partie : un commit pour T1+T2 (le catalogue et les
noms de méthode touchés), un commit pour T3, un commit pour T4.

---

## 2. Deuxième partie — écrire, corriger, compléter

**Uniquement les scénarios `[S]`** — caisse seule et simulateur matériel. Un
`[V]`, `[N]` ou `[C]` rencontré en chemin n'est ni écrit ni simulé : il est
listé dans le rapport et reporté.

Ordre d'exécution, du plus utile au moins urgent :

1. **Les scénarios neufs du T3** — retours R1 à R6, puis le groupe `T` des
   langues. C'est le code le plus récent, donc le moins couvert et le plus
   exposé ; une régression y passerait aujourd'hui sans témoin.
2. **Les deux rouges connus de `GroupBIT`** : `b02` (`VatRate` transitoire dans
   le helper `insertPromoPrice`) et `b14` (avenant après `ANNULER TICKET`),
   rouges avant le lot 2 et laissés tels. Vérifie d'abord qu'ils le sont
   toujours. **Si le rouge vient du produit, il reste rouge** et le rapport le
   décrit — scénario, attendu, obtenu, cause probable, fichier:ligne. On
   n'affaiblit pas un test, on n'aligne pas un attendu sur le code.
3. **Les 109 scénarios catalogués sans test**, groupe par groupe, en ne gardant
   que les `[S]`. Le tag du catalogue fait foi pour trancher : ne devine pas
   l'infrastructure d'un scénario, lis son tag.

**Un groupe = une livraison = un run vert = un commit.** On ne démarre pas le
groupe suivant avant que le précédent soit vert ou que son rouge soit documenté.

### 2.1 Les trois pièges qui transforment un run en faux témoignage

**La commande de run doit porter `-DskipITs=false`.** Sans lui, failsafe
annonce « Tests are skipped » et `BUILD SUCCESS` est un **faux vert**.

**Jamais deux runs en même temps sur le dépôt.** La campagne est clouée au port
8081. Si le port est occupé, ce n'est pas un contournement qu'il faut mais le
processus survivant qu'il faut tuer ; `-Dpos.e2e.port=8082` n'existe que pour
cohabiter sciemment.

**En tâche de fond, et on lit le rapport failsafe**, jamais la sortie console
d'un shell qui a expiré :

```
cd ~/Documents/Workspaces/impos
nohup mvn verify -DskipUTs=true -DskipITs=false -DskipJsTests=true -Dit.test='GroupTIT' > /tmp/impos-T.log 2>&1 &
```

puis `target/failsafe-reports/com.intermarche.e2e.GroupTIT.txt`.

Et jamais `-Dgolden.record=true`.

### 2.2 La règle d'interface qui ne souffre aucune exception

**Une caisse n'a PAS de clavier physique.** Toute saisie passe par un pavé
tactile ; ce que le caissier tape s'affiche dans un élément de texte
(`pad-display`), jamais dans un `<input>`, et la valeur postée voyage dans un
champ caché. Un test qui remplit un champ de saisie libre sur un écran de caisse
teste un défaut, pas une fonctionnalité — sauf `digital-ticket`, page servie au
téléphone du client, qui a un vrai clavier. Le pavé de pré-saisie de l'écran de
vente est en bas à droite, dernière brique de la colonne d'actions, sous les dix
touches.

---

## 3. Règles dures

- Code et commentaires en anglais ; les libellés assertés restent en français,
  au caractère près.
- **Javadoc sur toutes les méthodes**, méthodes de test et helpers privés
  compris, l'identifiant du scénario dans le nom de méthode **et** dans la
  javadoc.
- Portée stricte : rien hors de ce document. Une observation hors périmètre
  tient en une ligne du rapport.
- `git add` et `git commit` uniquement. **Jamais de `git push`**, jamais de
  branche, jamais d'autre commande git.
- On ne touche pas aux `Demo*IT`, on ne redécoupe pas l'existant, on ne crée
  aucun fichier de suivi à côté de `e2e-scenarios.md`.

---

## 4. Rapport

Par livraison :

```
<groupe ou travail> : <titre>
scenarios catalogues touches : ...
tests ecrits : k   tests corriges : j
run : <commande> -> <verts>/<total>
rouges restants : <id> (attendu / obtenu / cause probable / fichier:ligne)
reportes faute d'infrastructure : <id> ([V]/[N]/[C])
hors perimetre repere : ... (ou aucun)
```

Rapport final, en cinq listes :

1. les treize identifiants dédoublonnés, avec l'ancien et le nouveau numéro, et
   les méthodes de test renommées en conséquence ;
2. les scénarios **ajoutés** au catalogue, par chantier ;
3. le tableau de couverture avant et après, avec le total réel ;
4. les rouges qui restent, avec leur cause probable — produit ou test ;
5. les écarts entre le code et le catalogue que l'audit a révélés et que ce
   document ne tranche pas, formulés en une phrase chacun, sans y répondre.
