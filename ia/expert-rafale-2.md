# Seconde passe de l'expert caisse — prompt unique

L'expert fonctionnel a repris la caisse après la première campagne et relevé dix
points. Tu les traites tous, dans l'ordre ci-dessous, et tu ne t'arrêtes pas
avant d'avoir fini.

Ce prompt **complète** `ia/expert-campaign.md` et le précise sur deux points déjà
nommés là-bas — la session superviseur de l'étape 2, le sous-total de l'étape 4.
En cas de désaccord entre les deux textes, c'est celui-ci qui vaut : il est
postérieur et il vient d'un usage réel.

---

## 1. Sources et conventions

- **`CLAUDE.md`** — conventions de code et de test, **sauf** sa clause « ne
  jamais modifier `src/main` », que le §2 remplace.
- **Les Javadoc du code existant** — elles portent les contrats du produit.
  Les relire prime sur toute supposition.
- **`cotation-expert-caisse.xlsx`** pour les `EXP-nn` cités, quand tu veux le
  texte d'origine d'une exigence.

## 2. Portée

- Tu ne traites que les dix points du §3.
- Tu crées et modifies sous `src/main` ce qu'ils exigent, et rien de plus. Un
  refactoring rencontré en chemin se signale en une ligne ; il ne se fait pas.
- Tu ne touches ni aux neuf références `src/test/resources/golden/`, ni à la
  commande qui les régénère : `-Dgolden.record=true` ne s'exécute jamais. Le
  point 5 change pourtant le ticket imprimé : tu signales alors précisément
  quelles références sont à refaire, et tu laisses le geste à l'humain.
- Git : un commit par point, sur la branche courante. Jamais de branche, jamais
  de push.
- Package : `com.intermarche.pos.service` pour les services généraux ; tout ce
  qui injecte `PosState` ou sert un flux écran va dans `ui.*`. Jamais de package
  à classe unique.

## 3. Les dix points

### 1 · La page ne suit pas la saisie

« Je scanne 30 articles avec un article par page, l'application reste figée sur
les 6 premiers articles de la première page. »

`PosState` pagine le ticket : `ticketCurrentPage`, `PAGE_SIZE`, `visibleItems`,
et un écrêtage à la dernière page existante. Ce qu'il manque est qu'un
**enregistrement ramène l'affichage sur la ligne qu'il vient de créer** — donc
sur la dernière page. Aujourd'hui la page reste où elle était et la caissière
regarde un ticket qui ne bouge plus pendant qu'elle scanne.

La règle : tout ajout de ligne positionne l'affichage sur la page qui contient
cette ligne. Le feuilletage manuel reste possible, et le scan suivant ramène à
la fin — la caissière consulte, elle ne navigue pas. Une annulation de ligne
laisse l'affichage sur une page qui existe encore, ce que l'écrêtage fait déjà.

### 2 · On ne peut sélectionner que la première page

« Si je sélectionne un article dans une autre page, il sélectionne l'article de
la première page positionné sur la même ligne. »

C'est un défaut d'indice, et il est visible à l'œil nu. `ticket.html` boucle sur
`state.visibleItems` et passe `{item_index}` à `/action/select/{index}` : c'est
le rang **dans la page affichée**. `HomeService.selectLine` le range dans
`selectedTicketIndex`, qui est un rang **dans le ticket entier**. Sur la page 1
les deux coïncident, et c'est pourquoi personne ne l'avait vu.

Corrige-le par l'identité de la ligne et non par un calcul d'offset : chaque
ligne porte déjà un `uid`, la sélection doit le désigner. Un rang recalculé
resterait faux le jour où une ligne s'insère ou disparaît entre l'affichage et
le clic.

### 3 · La quantité et le prix unitaire manquent sous le libellé

« Lorsque j'ai une ligne avec 5 articles, j'ai bien le prix des 5. Il manque sur
le ticket, en dessous du libellé : prix unitaire et quantité. »

La colonne de droite porte le prix à payer, et c'est juste. Sous le libellé il
faut la décomposition qui l'explique : `5 x 2,50 €`. Elle n'apparaît que
lorsqu'elle apprend quelque chose — une ligne d'une seule unité n'en a pas
besoin.

### 4 · Idem pour les quantités décimales

Un produit vendu au mètre ou à toute autre unité de mesure affiche la même
décomposition, avec **l'unité du référentiel** et jamais une unité en dur :
`1,25 m x 12,00 €/m`. Le prix à payer reste dans la colonne de droite.

### 5 · Idem pour les codes-barres à valeur

Pour un PPV **poids**, la décomposition est `0,188 kg x 17,99 €/kg`. Pour un PPV
**prix**, c'est la même forme, sauf que le poids y est **déduit** du prix lu et
du prix au kilo du référentiel — `LC-02-05-01` le demande explicitement. La
brique existe déjà : `item.hasEmbeddedWeight` et `item.embeddedWeightLabel` sont
posés et affichés par `ticket.html`. Vérifie ce qu'ils produisent réellement,
complète-les plutôt que d'écrire un second chemin, et fais-les servir les quatre
cas des points 3 à 5 par une seule et même ligne de détail.

`LC-02-05-04` demande cette ligne sur **la visu caisse, la visu client et le
ticket de caisse**. C'est donc aussi une ligne imprimée, d'où les références
golden à refaire.

### 6 · Le superviseur se demande son propre aval

« Je suis un superviseur qui a ouvert sa caisse. Lorsque je réalise une
opération qui demande une autorisation superviseur, la demande ne doit pas
apparaître. »

Le mécanisme existe — `operatorIsSupervisor`, `LC-01-05-07` — et il n'est pas
consulté partout. Trouve les appels à `requestAuthorization` qui l'ignorent et
fais-les tous passer par le même point de décision, une fois, plutôt que de
répéter le test. Un caissier MANAGER ou ADMIN n'a jamais d'aval à demander.

### 7 · Le mode superviseur ne survit pas au geste

« Lorsque je saisis le mode superviseur, il doit rester jusqu'à la fin du ticket
ou si je sélectionne la fonction fin superviseur. »

C'est l'exigence `EXP-33`, et l'étape 2 de `ia/expert-campaign.md` la décrit en
détail : un `Def` administré dit la portée — à chaque opération, ou une fois
pour tout le ticket — et dans le second cas l'identité du superviseur reste
attachée à l'état de vente jusqu'à la fin du ticket **ou** jusqu'à une touche
`FIN SUPERVISEUR` explicite et visible.

Cette touche n'est pas un confort. L'expert dit pourquoi : « une fois une
annulation réalisée, le superviseur s'en va et le caissier peut faire ce qu'il
veut car le mode superviseur est toujours présent jusqu'à la fin du ticket. »
C'est exactement la fraude que l'aval prétend empêcher.

### 8 · La remise en euros porte sur l'ARTICLE, pas sur la ligne

« J'ai 10 articles que je vends 100 €, je fais une remise de 2,00 € : c'est une
remise à l'article et je paye donc 80 €. Par contre si je scanne un autre
article identique, il est bien à 10,00 €. »

Donc la valeur saisie est une remise **unitaire**, et son effet sur la ligne est
cette valeur multipliée par la quantité. Aujourd'hui `REMISE` est comprise comme
des euros retirés du total de la ligne, ce qui donne 98 € là où le métier attend
80 €.

Deux conséquences, et elles comptent autant que la première :

- **Le contrat avec le moteur ne change pas.** `ValuationPayloads.ItemDto`
  déclare `manualDiscountAmount` comme « euros retirés du total de la ligne », et
  cela reste vrai. C'est `ValuationService.applyGesture` qui **multiplie la
  remise unitaire par la quantité** avant de l'envoyer. Redéfinir la sémantique
  d'un champ existant casserait tout appelant qui l'utilise correctement.
- **L'évaluateur embarqué de repli doit dire la même chose.** Il ne sert que
  lorsque le moteur est injoignable, et une caisse qui ne calcule pas pareil
  selon que le réseau est là ou non est pire qu'une caisse qui calcule faux.

La remise ne colle pas au produit : un article identique scanné ensuite repart
au prix plein. Et la remise unitaire suit la quantité de sa ligne — passer de 10
à 11 unités remise la onzième aussi.

### 9 · Paliers et alerte de marge

« Il doit y avoir des paliers et prévenir que l'on dépasse la marge, car c'est
légal : interdit de vente à perte, sauf si exceptionnel car le produit est
dégradé et invendable. »

**Ce n'est pas toi qui calcules.** Le seuil de revente à perte appartient à
`imvaluation`, qui le porte déjà : garde `SRP_GUARD`, plancher transporté comme
une ligne de prix d'usage `SRP_FLOOR`, arbitré le 26 septembre. Le prix d'achat
ne descend jamais jusqu'à la caisse, et il ne faut surtout pas l'y faire
descendre.

Ce qui te revient est l'écran, et rien d'autre :

- afficher que la remise demandée a été **écrêtée**, avec le montant réellement
  accordé, quand la réponse du moteur le dit ;
- afficher l'**avertissement** de palier quand elle le porte, sans bloquer ;
- offrir la **dérogation produit dégradé** : un motif choisi par l'opérateur sous
  aval superviseur, envoyé sur la ligne du panier, et tracé.

Les codes de motif et les champs viennent du moteur. **Tant qu'`imvaluation` n'a
pas livré son contrat, ce point attend** : traite les neuf autres, et signale
celui-ci comme bloqué plutôt que d'inventer un format que l'autre côté ne
produira pas.

### 10 · Remise en pourcentage

Même traitement que le point 9 pour l'affichage. Le calcul d'un pourcentage sur
le prix unitaire est déjà, par construction, une remise à l'article — il n'y a
rien à corriger sur ce point-là.

## 4. Tests

- JUnit 5 + Mockito nu, selon `CLAUDE.md`. Jamais `@QuarkusTest`, jamais H2 pour
  un test de classe.
- **Chaque jambe de chaque expression booléenne composée a son cas de test**,
  nullité comprise. Sur ce lot en particulier : ligne d'une unité contre ligne
  de plusieurs, quantité entière contre décimale, poids lu contre poids déduit,
  page unique contre pages multiples, sélection sur la première page contre
  sélection ailleurs, opérateur superviseur contre opérateur caissier.
- Le point 2 mérite un test qui aurait attrapé le défaut : sélectionner la
  deuxième ligne de la troisième page et vérifier que c'est bien elle qui est
  retenue, jamais la deuxième du ticket.
- E2E : chaque geste rendu visible entre dans `e2e-scenarios.md`, dans le groupe
  qui lui correspond, **dans le fichier existant** et jamais dans un satellite.
- `mvn verify` vert à la fin de chaque point, pas seulement à la fin.

## 5. Ce que tu rends

Par point : ce qui a été fait, les fichiers touchés, le verdict de `mvn verify`.

À la fin : les références golden à régénérer, l'état du point 9, et — en une
ligne chacune — les questions restées ouvertes et les améliorations hors
périmètre repérées en chemin.
