# Campagne couverture — combler ce qui reste

But : porter la couverture de branches d'impos au maximum utile en traitant
ce que `sh/run-tests-campaign.sh` ne traite pas. Ce script saute toute classe
qui possède déjà un `<Classe>Test.java` ; les branches non couvertes qui
restent sont donc presque toutes DANS des classes déjà testées.

`CLAUDE.md` est le contrat de forme. Le lire, l'appliquer, ne pas le
paraphraser dans le rapport.

## Étape 0 — établir la cible, ne pas la deviner

```
mvn -q verify
mvn -q jacoco:report
```

Lire `target/site/jacoco/jacoco.csv`. Trier par `BRANCH_MISSED` décroissant.
Retenir les classes de `com.intermarche.pos` hors `e2e`, hors
`com.intermarche.demo`, hors classes sans branche.

Écrire la liste retenue, avec pour chaque classe `BRANCH_MISSED/BRANCH_COVERED`
et l'existence ou non d'une classe de test, AVANT d'écrire la moindre ligne.

Si `jacoco.csv` est absent ou vide, s'arrêter et le dire. Ne jamais estimer
une couverture en lisant le code : la seule source est le rapport.

## Étape 1 — une classe à la fois, dans l'ordre

Pour chaque classe retenue :

1. Lire la classe de production et, si elle existe, sa classe de test.
2. Ouvrir le rapport HTML de la classe (`target/site/jacoco/<pkg>/<Classe>.html`)
   et relever les lignes rouges et les losanges jaunes. Ce sont les cibles.
   Ne pas travailler de mémoire.
3. Pour chaque branche manquante, écrire la méthode de test qui l'atteint.
   Une classe de test qui existe s'ENRICHIT : on y ajoute des méthodes, on ne
   la réécrit pas, on ne réordonne pas l'existant, on ne renomme rien.
4. `mvn -q -Dtest=<Classe>Test test` jusqu'au vert.
5. `mvn -q verify && mvn -q jacoco:report`, relire la ligne de la classe dans
   `jacoco.csv`, et recommencer tant qu'il reste une branche atteignable.
6. Commiter cette classe seule. Jamais de `git push`.

## La nullité — la première chose à couvrir, pas la dernière

C'est le manque qui revient. Un rapport JaCoCo à 100 % de branches peut
parfaitement laisser une NullPointerException en production, parce qu'une
branche comptée couverte ne l'a été que par son bras non nul.

Pour CHAQUE méthode traitée, passer cette liste avant d'écrire, et la passer
en entier :

- **Chaque paramètre** : un cas où il vaut `null`. Y compris ceux dont la
  javadoc jure qu'ils ne le sont jamais — c'est justement là que la garde
  manque.
- **Chaque garde composée** : un cas par jambe VRAIE, pas un cas pour le `if`.
  `if (a == null || b == null || c.isEmpty())` réclame trois tests, plus
  celui où tout passe. Une seule jambe testée laisse les autres non exercées
  même quand JaCoCo affiche la ligne en vert.
- **Chaque ternaire** : ses deux bras, séparément.
- **Chaque valeur rendue par un collaborateur moqué** : le cas où elle est
  `null`. Un `findById` qui rend `null`, un service qui rend `null`, un
  `Optional.empty()`.
- **Chaîne, collection, tableau** : distinguer `null`, vide, et — pour une
  chaîne — blanc. Trois cas distincts, trois comportements possibles.
- **Élément nul DANS une collection** : une liste non nulle dont un élément
  est `null`, quand la méthode itère.
- **Champ d'entité non renseigné** : une entité dont le champ lu vaut `null`
  (`checksum`, une date, un libellé, une adresse), pas seulement une entité
  absente.
- **Nombre** : `null`, zéro et négatif sont trois cas, jamais un seul.

Quand une garde de nullité n'existe pas et qu'elle devrait exister, ne pas
l'ajouter : `src/main` ne se touche pas. Écrire le test qui la réclame, le
voir échouer, et rapporter la ligne en une phrase.

Le rapport de chaque classe indique combien de cas de nullité ont été
ajoutés. Zéro est une réponse recevable seulement si la méthode n'a ni
paramètre, ni collaborateur, ni champ susceptible d'être nul — et il faut
alors le dire.

## Vérifier que le test a réellement tourné

Après le premier `mvn -Dtest=<Classe>Test test`, lire le nombre de tests
exécutés dans la sortie surefire. **Un total inchangé après l'ajout de
méthodes signifie que rien n'a tourné** — c'est arrivé sur ce dépôt. Ce n'est
pas un succès, c'est un montage à réparer avant de continuer.

## Pièges mesurés de ce dépôt

- `ReceiptLogo.directive()` lit `src/main/resources/print/logo.png` et le met
  en cache statique. Sous `mvn`, tout document produit par
  `TicketPrinterService` commence donc par une ligne `[[IMAGE ...]]` d'environ
  4 600 caractères. Ne jamais affirmer qu'aucune ligne ne dépasse 42
  caractères sans exclure cette directive — et compter les directives
  exclues, une seule est légitime.
- `TicketLayoutGoldenMasterTest` compare neuf documents aux références de
  `src/test/resources/golden/`. **Ne jamais lancer `-Dgolden.record=true`.**
  Un document qui change est un échec à rapporter, pas une référence à
  réécrire.
- `DateTimeProvider.now()` se fige par `mockStatic` dans tout test qui touche
  une date, sinon le test est vert aujourd'hui et rouge demain.
- `BaseEntity.checksum` est un `Integer` : la comparaison passe par
  `changedFrom(int)`. Un `checksum != x` direct déballe et lève sur une ligne
  sans somme de contrôle.
- Les entités Panache ne sont pas enhanced sous `mvn test` : les finders
  statiques se moquent avec `mockStatic(PanacheEntityBase.class)`, et
  `persist()` se neutralise avec `mockConstruction`.
- Les tests RestAssured affirment par `.then().statusCode(...)`, sans le mot
  `assert`. Ne pas les compter comme non couvrants.

## Interdits

- Ne rien modifier sous `src/main`. Un obstacle à la testabilité se rapporte
  en une ligne et s'arrête là ; il ne se contourne pas en changeant la
  production.
- Pas de `@QuarkusTest`, pas de H2, pas d'AssertJ, pas de démarrage
  d'application pour un test de classe.
- Pas de test sans vérification. Un test dont le corps appelle la méthode et
  s'arrête n'est recevable que dans deux cas, et sa javadoc doit alors le
  dire : soit aucun `mockStatic` n'est ouvert et toute entrée-sortie lèverait
  donc d'elle-même, soit la propriété testée EST « ne lève pas » (le bras
  accepté d'un validateur, par exemple).
- Ne pas renuméroter, reformater ou réordonner du test existant.
- Jamais de `git push`.

## Branches qu'il ne faut PAS chercher à couvrir

Les rapporter plutôt que les forcer :

- les bras défensifs d'un `switch` sur une énumération exhaustive ;
- les `catch` d'exceptions que le montage ne peut pas produire sans
  instrumenter la production ;
- le code injoignable derrière une garde qu'une autre garde rend impossible.

Chacune se justifie en une phrase dans le rapport, avec le numéro de ligne.

## Rapport, à chaque classe

```
<Classe> : branches n/n (x %) -> m/m (y %)
tests ajoutes : k  dont cas de nullite : j
tests executes : avant -> apres
branches laissees : ligne L (raison), ligne L (raison)
obstacle src/main : ... (ou aucun)
```

## Terminé quand

Toutes les classes de la liste de l'étape 0 sont passées, `mvn -q verify` est
vert, et le rapport final donne la couverture de branches globale avant et
après, plus la liste consolidée des branches laissées avec leur raison.
