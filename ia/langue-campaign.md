# Campagne — généraliser la localisation de la caisse

*Établie le 08/10/2026. La mécanique est en place et éprouvée sur l'écran
verrouillé ; ce qui reste est la conversion de tout le reste. Les chiffres
ci-dessous sont mesurés sur le dépôt, pas estimés.*

## 0. L'état exact du départ

| Mesure | Valeur |
|---|---:|
| Clés dans chaque bundle (`fr`, `nl`, `pt`, alignés) | **39** |
| Gabarits appelant `inject:i18n` | **3** sur 45 |
| Libellés visibles encore en dur dans les gabarits | **485 distincts** |
| Messages opérateur en dur dans le Java (`setError`/`setNotice`) | **74** |
| Littéraux de `TicketPrinterService` | **266 bruts** |
| Assertions de texte dans les tests e2e (`getByText`, `hasText`, `contains`) | **399** |
| Assertions de message dans les tests unitaires | **87** |

Ordre de grandeur de l'arrivée : **600 à 700 clés**.

## 1. Ce qui est déjà construit et qu'on ne refait pas

- **`ui/Messages.java`** : bean `@Named("i18n") @RequestScoped`, atteint des
  gabarits par `{inject:i18n.get('clé')}`. Chaîne de résolution : langue choisie
  par l'opérateur → langue du salarié → langue système du magasin → français.
  Repli **clé par clé** sur le français ; une clé absente partout rend son propre
  nom, donc un oubli est visible à l'écran et jamais silencieux.
- **La liste des langues est DÉCOUVERTE**, jamais écrite : `bundledLanguages()`
  balaye `messages/msg_*.properties` du classpath et le répertoire externe
  `pos.messages.dir`. Chaque bundle porte le nom de sa langue dans sa clé
  `language.self`. **N'introduis aucune énumération de langues, nulle part.**
- **`format(clé, args…)`** pour un libellé à paramètres `{0}`, `{1}`.
- **`both(clé)`** pour le double affichage client, seconde langue d'abord.
- **`MessageBundleExhaustivenessTest`** garde les bundles alignés : même jeu de
  clés, aucune valeur vide, chaque bundle se nomme. Il tournera à chaque lot.

## 2. Les règles qui ne se discutent pas

**R1 — Le libellé d'un article ne change JAMAIS de langue.** Il vient du
référentiel articles. Aucune clé pour un nom de produit, une famille, une
nomenclature, un motif administré, un type de retour, une destination de
mouvement : tout ce qui est administré au back-office se traduit au
back-office, pas ici.

**R2 — Une apostrophe dans un libellé passé à `format()` se DOUBLE** (`''`),
`MessageFormat` s'en servant pour échapper. En français, presque chaque phrase
en porte une. Un libellé rendu par `get()` ne double rien. Se tromper de sens
produit un texte amputé à l'exécution, pas une erreur de compilation.

**R3 — Les libellés qu'un script écrit lui-même** sont résolus **côté serveur**
à l'affichage, par une table en tête de script. Le patron existe, c'est `LBL`
dans `lock.html` : `var LBL = { enterPin: "{inject:i18n.get('lock.enterPin')}" }`,
et le script lit la table. **Le script ne connaît aucune langue.** Jamais de
`fetch` de traduction, jamais de table de langues en JS.

**R4 — Nommage des clés : `<écran>.<chose>`**, l'écran étant le nom du gabarit
sans son extension, en camel si besoin. `pay.cashGiven`, `session.openFloat`,
`withdrawal.destination`. Trois préfixes transverses existent et sont les seuls :
`common.` (ce qui apparaît sur plus d'un écran : retour, annuler, valider…),
`customer.` (afficheur client), `language.`. **Un libellé identique sur deux
écrans devient une clé `common.`** — une seule fois, pas deux.

**R5 — Un libellé qui est une PHRASE d'erreur garde sa phrase**, pas un code.
`pay.noTenderAllowed=MOYEN NON AUTORISÉ AU REMBOURSEMENT`. La casse et la
ponctuation du français actuel sont reprises à l'identique : elles sont assertées
ailleurs (§4).

**R6 — Zéro libellé en dur qui reste.** À la fin de chaque lot, le gabarit ou la
classe traités ne contiennent plus un seul texte visible en français. Ce qui ne
peut pas être traduit (R1) est laissé et nommé dans le rapport.

## 3. Les trois lots, dans cet ordre

### Lot 1 — Les gabarits (485 libellés, 45 fichiers)

Mécanique, aucun arbitrage. Ordre par volume décroissant, qui est aussi l'ordre
d'utilité : `pay.html` (79), `main.html` (58), `invoice-request.html` (31),
`invoice.html` (29), `fidelity.html` (25), `supervisor.html` (25),
`session-report.html` (21), `session.html` (19), puis le reste.

Pour chaque gabarit : relever les textes visibles hors `<style>`, `<script>`,
commentaires Qute et attributs techniques ; nommer les clés selon R4 ; écrire les
trois bundles ; remplacer. Les libellés écrits par le script passent par R3.

**Attention aux trois endroits qu'un balayage naïf manque** : le texte dans un
attribut (`placeholder`, `title`, `data-placeholder`, `aria-label`), le texte
dans une branche `{#if}…{#else}` (les deux branches), et le texte d'un
`<option>` de `<select>`.


### Lot 2 — Les messages opérateur du Java (74)

Ce n'est pas un travail de traduction mais de refactoring : ces phrases sont
posées par des services, qui ne sont pas des écrans.

`Messages` est `@RequestScoped` et injectable partout dans `ui.*`. Dans un
service qui sert déjà un flux écran, injecte-le et remplace
`state.ticket.setError("DERNIER ARTICLE — …")` par
`state.ticket.setError(messages.get("sale.lastArticle"))`.

**Et c'est là que ça coûte** : ces phrases sont assertées au caractère près —
**87 assertions dans les tests unitaires, 399 assertions de texte dans les tests
e2e**. Chaque phrase déplacée vers une clé oblige à mettre à jour les assertions
qui la nomment. Règle : **l'assertion garde la phrase française littérale** (le
bundle français est la référence, la phrase ne change pas), on ne la remplace
par un appel au bundle que dans les tests qui éprouvent justement la traduction.
Un test rouge après un déplacement est un test à relire, jamais un test à
affaiblir.


### Lot 3 — Le ticket imprimé, bilingue (LC-13-06-11)

Le plus coûteux, et le seul qui porte une exigence propre : le ticket doit
pouvoir s'imprimer en **deux langues**, la seconde étant administrée par
`language.customer-second`. Trois contraintes qui en font un lot à part :

- le papier fait **42 colonnes** : une ligne doublée ne tient pas toujours, et il
  faut décider par ligne si la seconde langue va **sous** la première ou si la
  ligne reste monolingue ;
- le ticket est sur le **chemin fiscal** : la signature et le chaînage ne doivent
  pas bouger ;
- `both(clé)` existe déjà pour le double affichage — c'est la primitive, elle
  rend « seconde langue / première ».

Commence par **lister** les 266 littéraux et trier : entête, pied, lignes
d'article, blocs de règlement, mentions légales. **Rapporte ce tri et arrête-toi
là si une décision de mise en page s'impose** — mais ne t'arrête pas pour
demander comment nommer une clé.

## 4. Les traductions

Les bundles `nl` et `pt` sont une **première passe non relue**. Écris-les
quand même — une clé sans valeur néerlandaise rendrait du français, ce qui est
pire qu'une traduction perfectible parce que personne ne la verrait. Et
**rapporte à la fin la liste des clés dont tu n'es pas sûr**, pour la relecture
humaine. Pas de « TODO » dans les fichiers.

## 5. Règles dures

- Anglais pour le code et les commentaires ; les **valeurs** des bundles sont
  dans leur langue, évidemment.
- **Javadoc sur chaque méthode** sans exception.
- Périmètre strict : la localisation, rien d'autre. Une observation hors
  périmètre tient en une ligne du rapport.
- **NE T'OCCUPE PAS DU DÉPÔT.** Aucune commande git, pas même en lecture : ni
  commit, ni stash, ni statut. L'arbre de travail porte d'autres chantiers en
  cours et ce n'est pas ton affaire — tu écris les fichiers, leur propriétaire
  s'occupe de son historique. Un arbre « sale » n'est JAMAIS une raison de
  t'arrêter.
- `MessageBundleExhaustivenessTest` et `MessagesTest` doivent être verts à la
  fin de chaque lot. Les lancer, pas les supposer.

## 6. Rapport

Par gabarit ou par classe :

```
<fichier> : <n> cles ajoutees
cles common. reutilisees : <n>
libelles laisses en dur : <lesquels> (raison : R1, technique, autre)
tests mis a jour : <n>
```

Final, en quatre listes : le compte de clés par préfixe ; les libellés laissés en
dur avec leur raison ; les clés dont la traduction demande une relecture
humaine ; et les décisions de mise en page que le ticket bilingue réclame.
