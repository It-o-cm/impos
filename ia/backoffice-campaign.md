# Campagne back-office impos — prompt de session

Un prompt, une session, **un lot**. Le numéro du lot est le seul paramètre.

> **LOT = _<0 à 10>_**

---

## 1. Ce que tu fais

Tu construis le lot indiqué du back-office d'impos et tu l'accompagnes de ses
tests unitaires. Tu ne fais rien d'autre : ni le lot suivant, ni le lot
précédent, ni les améliorations que tu repères en chemin.

## 2. Sources normatives, par ordre d'autorité

1. **`docs/backoffice-lots.md`** — la table de ton lot **est le contrat**.
   Chaque ligne `BO-xx-xx-xx` est une exigence à satisfaire, avec sa priorité
   et son état actuel.
2. **`docs/backoffice-cadrage.md`** — l'objectif du lot, son socle, son
   critère de fin, et les décisions déjà rendues (notamment la doctrine de
   cotation et le choix de topologie).
3. **`CLAUDE.md`** — les conventions de code et de test, **sauf** sa clause
   « ne jamais modifier `src/main` », que le §3 ci-dessous remplace.
4. **Les Javadoc du code existant** — elles portent les contrats sémantiques
   du produit (l'entonnoir `syncDraft`, le moment fiscal de `validateTicket`,
   la chaîne de scan et ses priorités, `PosState` racine de composition).
   Les relire prime sur toute supposition.

La colonne **État** de la table est ton point de départ, pas une décoration :

- `3` — à construire de zéro.
- `1a` — **un mécanisme existe déjà**. Tu dois le trouver dans le code avant
  d'écrire une ligne. Redévelopper à côté de ce qui tourne est la faute la
  plus coûteuse de cette campagne.
- `1` — déjà couvert. Tu n'y touches pas ; tu t'assures seulement de ne pas
  le casser.

## 3. Règle de portée — elle remplace celle du CLAUDE.md

Cette campagne **construit** : tu modifies `src/main`, c'est son objet. En
contrepartie, la frontière se déplace, elle ne disparaît pas.

- Tu ne traites **que** les exigences de ton lot.
- Tu ne renommes pas, ne réorganises pas, ne refactores pas ce que tu croises.
  Une amélioration hors périmètre se **signale en une ligne** dans le rapport,
  elle ne s'applique pas.
- Tu ne touches aux écrans de vente que si une exigence de ton lot l'exige
  explicitement, et alors au strict minimum.
- **Tu ne tranches aucun arbitrage produit.** Si une exigence suppose une
  décision (un choix de modèle, une règle métier non écrite, une frontière
  entre deux applications), tu ne l'implémentes pas : tu la listes en
  arbitrage dans le rapport et tu passes à la suivante.
- Tu ne modifies jamais le questionnaire `.xlsx`.

## 4. Invariants du produit — à ne jamais casser

Ils priment sur toute exigence de l'AO. Si une exigence semble en demander la
violation, tu t'arrêtes et tu la signales.

- **Une caisse coupée du réseau continue de vendre.** Aucun nœud central ne
  devient un point de passage obligé de la vente.
- **Un exécutable = une caisse, une base par caisse.**
- **Toute ligne de ticket porte un EAN.** Les lignes fabriquées par la caisse
  portent un EAN technique administré.
- **Le moment fiscal est `validateTicket`** ; l'ordre de signature est un
  contrat.
- **Le tirage référentiel prend un snapshot** : une mise à jour en cours de
  journée n'impacte pas une vente ouverte (`BO-10-08-25`, déjà couvert).
- **État local, jamais transporté par le tirage** : `failedAttempts`,
  `lockedUntil`, `bo_password`, `must_change_password`. Si ton lot ajoute un
  champ, décide explicitement de son côté de la frontière et écris-le dans la
  Javadoc de l'entité.

## 5. Ordre de travail d'une session

1. Lire l'en-tête du lot et sa table dans `docs/backoffice-lots.md`.
2. Lire le code existant concerné — en priorité pour les lignes en `1a`.
3. **Énumérer les embranchements avant d'écrire**, exigence par exigence.
4. Construire dans l'ordre de la table.
5. Écrire les tests unitaires au fil de l'eau, pas à la fin.
6. `mvn -Dtest=<Classes> test` jusqu'au vert, puis `mvn verify`, puis lire le
   rapport JaCoCo des classes du lot et combler les branches manquantes.
7. Commit, puis rapport.

## 6. Conventions de code

- **Anglais** pour tout le code et tous les commentaires.
- **Javadoc sur chaque méthode, sans exception** : getters, setters,
  constructeurs, méthodes privées, callbacks de cycle de vie, redéfinitions.
- **Placement des paquets** : dans `pos.service`, uniquement les services qui
  servent d'autres services ; ce qui ne sert que la couche IHM va dans
  `ui.<sous-paquet>`, jamais directement sous `ui`. **Jamais de paquet à
  classe unique.**
- **Back-office** : tout écran passe par `{#include admin-layout}`.
  `META-INF/resources/admin/base.css` est une copie verbatim de la feuille
  d'imvaluation et **reste intouchée** ; le spécifique va dans une feuille par
  aire (`dashboard.css`, `auth.css`, …), déclarée dans le layout. Aucun bloc
  `<style>` dans un template. Cycle `POST → 303 → notice`. Jetons `--im-*`
  uniquement, jamais de couleur en dur.
- **Accès** : `@RolesAllowed` sur les ressources d'écran — c'est ce qui laisse
  l'authentification par formulaire émettre sa redirection. Une politique de
  permission n'est là que pour forcer `basic` sur les surfaces machine.
- **`application.properties` est en ISO-8859-1** : aucun caractère non-ASCII
  dans les nouvelles clés ni dans leurs commentaires.
- **Un paramètre administré passe par le catalogue typé de
  `PosSettingsService` et le domaine `SETTINGS` du tirage.** On n'ajoute pas
  une clé de configuration en dur pour un réglage que l'AO veut administrable.
- `BigDecimal` pour toute somme, échelle 2. Logger JBoss, jamais `System.out`.
  Jamais de collection nulle : `List.of()`.

## 7. Conventions de test

- **JUnit 5 + Mockito, tests unitaires purs.** Jamais `@QuarkusTest` pour un
  test de classe, jamais H2, jamais de démarrage d'application.
- Les entités Panache ne sont pas bytecode-enhanced sous `mvn test` : les
  finders statiques se mockent par `mockStatic(PanacheEntityBase.class)` et
  `persist()` se neutralise par `mockConstruction(<Entity>.class)`.
- `QuarkusTransaction` se mocke par
  `mockStatic(QuarkusTransaction.class, Answers.RETURNS_DEEP_STUBS)` en
  exécutant le `Runnable`/`Callable` reçu — **patron exact dans
  `FidEventOutboxServiceTest`**, à copier plutôt qu'à réinventer.
- Tous les mocks statiques en try-with-resources.
- **Assertions `org.junit.jupiter.api.Assertions` uniquement**, jamais AssertJ.
- Valeurs absolues attendues, aucune dépendance d'ordre, aucun état partagé.
- **Chaque jambe de chaque expression booléenne composée** a son cas de test
  (`a || b || c` = trois cas), et **les deux bras** de chaque ternaire et de
  chaque garde de nullité. C'est le point sur lequel les campagnes précédentes
  ont le plus dérapé : passe-les mentalement une par une avant de livrer.
- Pas de ligne vide dans un corps de méthode. Javadoc sur chaque test et
  chaque helper.
- **100 % de branches JaCoCo** sur les classes créées par le lot. Tout résidu
  se justifie ligne à ligne dans le rapport.

## 8. Critère de fin

- `mvn verify` vert.
- 100 % de branches sur les classes nouvelles, ou un résidu justifié.
- **Le décompte** : pour chaque exigence du lot, dire si elle passe en `1`,
  reste en `1a`, ou reste en `3`, avec la raison. Le questionnaire est le
  juge, pas l'impression de progrès.
- Tu n'annonces jamais couverte une exigence que rien ne démontre.

## 9. Rapport de fin de session — format imposé

```
Lot <n> — <titre>
Exigences du lot : <total>  →  1 : <n>   1a : <n>   3 : <n>
Fichiers créés   : ...
Fichiers modifiés: ...
Couverture       : <Classe> <branches couvertes>/<branches totales>  (une ligne par classe nouvelle)
Itérations mvn   : <n>
Résidus          : une ligne par résidu, avec sa raison
Arbitrages       : une ligne par décision à rendre
Hors périmètre   : une ligne par amélioration repérée et NON appliquée
```

## 10. Git

- **Un commit par lot**, message **exactement** `feat(bo): lot <n>` — sans
  titre ni suffixe : le script de campagne le compare caractère pour
  caractère, c'est lui qui décide si le lot est passé.
- **Jamais de push.**
- Le commit est le seul juge : pas de commit, pas de lot. Le code de retour
  de l'agent ne décide de rien.

## 11. Cas particulier du lot 0

Le lot 0 est **déjà écrit et compile**. Sa session ne construit rien : elle
écrit les tests unitaires manquants des quatre classes de
`com.intermarche.pos.security` — `CurrentUserTest` et
`PasswordChangeFilterTest` existent déjà, restent
`EmployeeIdentityProviderTest` et `EmployeeTrustedIdentityProviderTest` — et
elle ajoute un test qui **échouerait si l'extension de sécurité était
retirée**, pour que les `@RolesAllowed` des imports CSV et du GraphQL ne
puissent plus redevenir décoratives sans qu'on le voie.

## 12. Ce qui n'est pas dans cette campagne

- Les scénarios **e2e** : campagne séparée, dont le catalogue s'écrit dans
  `e2e-scenarios.md` à partir des mêmes tables.
- Les **sept lots conditionnels A à G** : hors décision, pas hors plan.
- Toute modification du questionnaire.

---

*Campagne établie le 31/08/2026. Le backlog qu'elle consomme est
`docs/backoffice-lots.md` ; le raisonnement qui l'a produit est
`docs/backoffice-cadrage.md`.*

---

## 13. Campagne câblage — lots `C0` à `C4`

Ajoutée le 11/09/2026. Même prompt, même discipline, **autre paramètre** :

> **LOT = _C0 à C4_**

Tout ce qui précède s'applique tel quel — sources normatives, règle de
portée, invariants, conventions de code et de test, critère de fin, format de
rapport. Les six précisions ci-dessous sont les seules différences.

**Le backlog n'est pas le même.** La table de ton lot est dans la partie
« Campagne câblage » de `docs/backoffice-lots.md`, pas dans les 13 lots
thématiques. Elle a une colonne de plus : **Preuve dans le code**, un
`fichier:ligne` ouvert pendant l'audit. Tu l'ouvres avant d'écrire quoi que ce
soit. Si ce que tu y trouves contredit le verdict, tu ne câbles pas : tu
reclasses la ligne en résidu avec ce que tu as lu. L'audit est une carte, pas
un contrat — le code a toujours raison contre lui.

**Tu ne cherches pas le comportement, il est déjà là.** Ces exigences ont
toutes été classées « câblables » parce qu'un mécanisme existe et que rien ne
l'atteint depuis un point de configuration. Écrire un second mécanisme à côté
du premier est la faute que cette campagne existe pour éviter.

**Zéro bouton mort.** Une clé de réglage se livre avec le comportement
qu'elle éteint et celui qu'elle allume, tous deux démontrés par un test. Une
clé lue par personne, ou dont les deux valeurs produisent le même effet
observable, n'est pas livrée — elle est listée en résidu.

**Deux ou trois sites, pas davantage.** Si le câblage d'une exigence en
demande plus, c'est que l'audit s'est trompé sur elle : tu t'arrêtes, tu la
listes en résidu avec le nombre de sites réellement touchés, et tu passes à la
suivante. Un refactor entamé en cours de lot est un lot perdu.

**Le lot C0 ne construit rien.** Ses 28 exigences sont déjà administrées ou
déjà couvertes ; sa session écrit uniquement les tests qui le prouvent —
chacun devant échouer si le réglage cessait d'être lu — et ne modifie aucune
ligne de `src/main`. Une ligne qu'aucun test ne démontre reste en `1a`, même
si le code a l'air d'y répondre.

**Le message de commit devient `feat(bo): lot C<n>`**, comparé caractère pour
caractère par le script de campagne comme les autres.

### Avant ta première commande git

Si ta session tourne à travers le pont d'accès aux fichiers, l'effacement y
est refusé par défaut : une commande git qui rafraîchit l'index y laisse un
`.git/index.lock` qu'elle ne peut pas retirer, et toutes les écritures
suivantes échouent. **Demande la permission d'effacer sur le dossier du dépôt
avant ta première commande git**, ou vérifie et retire le verrou en fin de
session. Un verrou du 02/09 est resté huit jours en place pour cette raison.
