# Campagne — compléter le jeu de tests e2e d'impos

## La source

`e2e-scenarios.md` à la racine : **151 scénarios catalogués, 60 portés par un test,
91 sans test**. Sa section « Couverture » donne le reste à faire groupe par groupe ;
c'est la liste de travail de cette campagne, et elle fait foi.

Infrastructure par groupe, en tête du document : **[S]** caisse seule + simulateur,
**[V]** + valorisateur sur :8090, **[N]** + nœud magasin, **[C]** + brique centrale.

## L'existant

`src/test/java/com/intermarche/e2e` : 12 classes `Group*IT` plus `RoutingIT`,
**106 tests, 558 assertions, 9 410 lignes**. Les cinq `Demo*IT` de
`com.intermarche.demo` sont le pré-vol de démonstration : **on n'y touche pas**.

Un `@Test` existant couvre un SCÉNARIO entier, avec cinq assertions en moyenne. On
garde ce grain : les nouveaux tests s'écrivent au même, un `@Test` par scénario du
catalogue, l'identifiant dans le nom de méthode et dans la javadoc. Pas de
redécoupage de l'existant.

Sept groupes n'ont pas encore de classe : **E, L, N, P, Q, R, S**. Les autres
s'étendent.

## Les lots

**Un lot = une livraison + un run vert + un rapport.** On ne démarre pas le suivant
avant.

1. **Les soudures des classes vertes** — A8→A10, C7→C8, D4→D8, G5→G6, H6→H9. Dix-neuf
   scénarios [S], dans cinq classes qui tournent déjà au vert : le risque est nul et
   ça vide cinq lignes du tableau.
2. **Vente et paiement** — B15→B24 et F9→F19. Vingt et un scénarios [S], le plus gros
   gisement et le plus utile : chèque, TR, crédit client, devise, secours, dégradé
   monétique, règles de tender, GS1, ticket balance, répéter, à-enlever, contrôle
   d'âge. F7 est [V], il attend le lot 5.
3. **Les chantiers jamais entamés, en [S]** — `GroupPIT` (facturation, P1→P8),
   `GroupQIT` (mouvements de caisse, Q1→Q3), `GroupSIT` (supervision locale, S1).
   Douze scénarios, trois classes neuves.
4. **`GroupNIT`** — les onze compléments de la 2e passe d'audit (N1→N11), [S].
5. **`GroupEIT`** — valorisation continue, E1→E14, **[V]** : demande le valorisateur
   réel sur :8090, donc la pile de démo. Plus F7.
6. **`GroupRIT`** (R1→R7) et **`GroupLIT`** (L1→L9) — consommation, flux moteur et
   consolidation magasin : [V] et [N], pile complète. En dernier.

Dans les lots 1 à 4, **aucun scénario [V], [N] ou [C]** n'est écrit ni simulé. S'il
s'en trouve un dans une plage, il est listé dans le rapport et reporté.

## Ce qui a coûté cher aujourd'hui — à ne pas refaire

**Ne jamais cliquer un lien par son libellé quand une route existe.** Trois échecs de
la journée venaient de libellés d'IHM déplacés ou renommés — `REMISE` devenu
`REMISE / PRIX` le 16/09, `ANNULER TICKET` passé dans le menu TICKET le 10/09. Les
gestes ont des GET : `/action/price-mod/<type>`, `/action/menu/<key>`. On passe par
la route, et le libellé ne casse plus rien.

**Un locator vise un élément, pas un texte.** La zone de message du fragment polé
rend du texte qui peut nommer un article — la bannière `PRIX ÉLEVÉ` en est une — et
`getByText("<libellé>")` résout alors deux éléments et Playwright refuse. Une ligne
de ticket est un lien (`<a href="/action/select/{i}">`), la zone de message un simple
`div` : on vise le rôle.

**Chaque assertion nomme l'état observé.** Pas « attendu 2, obtenu 1 » : le reste dû,
les règlements enregistrés, le message transitoire, ce qui est en attente. Un message
d'échec bien construit a livré en un seul run, ce soir, une cause qu'on cherchait
depuis le matin.

**Un effet asynchrone s'attend sur l'état SERVEUR**, avec une échéance bornée — jamais
sur un rendu d'écran ni une impulsion de tiroir, qui courent tous les deux après lui.

**`@AfterEach` qui remet la caisse à zéro** : mode formation à faux, panier vidé,
boîtes aux lettres à badge purgées (`auth.scannedBadgeId` et
`endorsement.scannedBadge`), pages Playwright fermées. Un test qui meurt entre
« j'active » et « je désactive » a contaminé trois scénarios ce matin et produit trois
faux diagnostics.

**Un test qui révèle un bug applicatif ne le corrige pas** : il reste rouge, et le
rapport le décrit (scénario, attendu, obtenu, cause probable, fichier:ligne). On
n'affaiblit pas un test pour le faire passer, on n'aligne pas un attendu sur le code,
et **on ne ré-enregistre jamais un golden master** — `-Dgolden.record=true` est
interdit.

## Règles dures

Code et commentaires en anglais. **Javadoc sur toutes les méthodes**, tests et
méthodes privées comprises. Portée stricte : rien hors du lot, une observation hors
périmètre tient en une ligne du rapport. Pas de git.

## Exécution

Une classe à la fois, en tâche de fond — une classe de neuf scénarios prend deux
minutes, bien au-delà du délai d'un shell :

```
cd ~/Documents/Workspaces/impos
nohup mvn verify -DskipUTs=true -DskipITs=false -DskipJsTests=true -Dit.test='GroupPIT' > /tmp/impos-P.log 2>&1 &
```

puis lire `target/failsafe-reports/com.intermarche.e2e.GroupPIT.txt`.

**Jamais deux runs en même temps sur le dépôt.** La campagne est clouée à un port :
`E2eTestProfile` donne à l'application sa propre adresse pour qu'elle joigne ses
simulateurs. Si 8081 est occupé, ce n'est pas un contournement qu'il faut mais le
processus survivant qu'il faut tuer ; `-Dpos.e2e.port=8082` n'est là que pour cohabiter
sciemment.

## Livrable par lot

Les tests, le rapport (scénarios écrits, résultat du run, rouges restants classés,
scénarios reportés faute d'infrastructure, écarts code/catalogue), et **la mise à jour
du tableau de couverture DANS `e2e-scenarios.md`** — ce document est le seul, on ne
crée pas de fichier de suivi à côté.
