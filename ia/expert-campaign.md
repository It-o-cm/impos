# Campagne « rapport de l'expert caisse » — prompt unique

Tu traites **l'intégralité** des écarts relevés par l'expert fonctionnel de la
ligne de caisse : quatre-vingt-seize exigences cotées, cinquante-trois à
servir. Tu ne t'arrêtes pas avant d'avoir tout livré.

---

## 1. Ce que tu fais

Un expert fonctionnel a tenu une caisse une journée et écrit ce qu'il y a vu
manquer. Son rapport décrit le métier tel qu'il se pratique, pas tel qu'un
cahier des charges le formule : c'est sa valeur, et c'est pourquoi il ne se lit
pas comme une liste d'écrans à produire.

Les quatre-vingt-seize exigences en ont été extraites et cotées ligne à ligne
contre le code, avec fichier et numéro de ligne : `cotation-expert-caisse.xlsx`,
onglet `Cotation`, colonne `Code` = `EXP-nn`. Quarante-trois sont acquises et
ne demandent rien. **Tu sers les cinquante-trois autres** — celles dont la
colonne `Verdict` vaut `PARTIEL` ou `KO`.

Ce que tu livres n'est pas « la fonction » mais **la fonction et sa trace** :
un geste de caisse qui ne laisse rien derrière lui n'est pas opposable, et la
moitié de ces exigences existent précisément pour qu'un tiers puisse constater
après coup ce qui s'est passé au tiroir.

## 2. Sources normatives, par ordre d'autorité

1. **Le §6 de ce fichier** — l'ordre de travail et les décisions déjà prises.
2. **`cotation-expert-caisse.xlsx`, onglet `Cotation`** — pour chaque `EXP-nn` :
   l'exigence telle que l'expert l'a écrite, le verdict, la preuve dans le code,
   l'écart constaté. La colonne `Preuve` donne le point d'entrée exact ;
   commence par là plutôt que par une recherche.
3. **`Questionnaire Fonctionnalites_Encaissem HD_26.xlsx`**, onglets
   `Back Office` et `Ligne de caisse` — quand un code `LC-xx` ou `BO-xx` est
   cité, c'est la formulation de l'appel d'offres qui fait foi sur *ce qui est
   demandé*. Elle prime sur la reformulation de l'expert, qui décrit un usage
   et non une exigence contractuelle.
4. **`2026-09-28-HENRI-CAISSE.txt`** — le rapport d'origine, pour le contexte
   d'usage d'une exigence qui te paraîtrait sèche.
5. **`CLAUDE.md`** — conventions de code et de test, **sauf** sa clause « ne
   jamais modifier `src/main` », que le §4 remplace.
6. **Les Javadoc du code existant** — elles portent les contrats sémantiques du
   produit : l'entonnoir `syncDraft`, le moment fiscal de `validateTicket`, la
   chaîne de scan, `PosState` racine de composition, l'outbox, l'écrivain
   unique de `PaymentService`. Les relire prime sur toute supposition.

## 3. Les cinq mécanismes existants — à trouver avant d'écrire une ligne

Rien de cette campagne n'introduit une plomberie nouvelle. Tout se greffe sur
l'un de ces cinq mécanismes déjà écrits.

1. **L'aval superviseur** — `EndorsementState`, `requestAuthorization`, la
   modale partagée `endorsement.html`, le dépôt prioritaire du badge scanné par
   `AuthScanHandler`. C'est une demande **ponctuelle**, effacée après chaque
   geste. Tu lui ajoutes une durée de vie ; tu ne la remplaces pas.
2. **Les paramètres administrés** — `PosSettingsService`, une centaine
   d'entrées `Def`, résolues le long de la chaîne `COUNTRY → ENSEIGNE → PDV`,
   du plus spécifique au plus général. Tout seuil, tout plafond, tout libellé,
   tout drapeau d'autorisation que tu ajoutes est un `Def` de plus, jamais une
   constante dans le code.
3. **Les événements techniques** — `TechnicalEventService.log(EventType, detail)`,
   qui crée l'événement, lui donne son `eventUid` et l'enfile dans l'outbox dans
   la transaction de l'appelant. Un geste nouveau = une valeur de plus dans
   `TechnicalEvent.EventType` et un appel à `log(...)`. Jamais un second chemin.
4. **L'impression** — `TicketPrinterService`, qui porte déjà les bordereaux de
   prélèvement, de commande de monnaie et d'enlèvement. Un bordereau nouveau est
   une méthode de plus dans cette classe, sur le même canal ESC/POS.
5. **L'écrivain unique du paiement** — `PaymentService` prend
   `synchronized (state)` en une dizaine d'endroits, et
   `TicketPersistenceService.syncDraft` écrit la même ligne de ticket. Toute
   écriture concurrente sur l'état de paiement passe par le même verrou, ou
   dépose et fait régler à la requête suivante, comme `settlePendingChequeRead`.

## 4. Règle de portée — elle remplace celle du `CLAUDE.md`

Cette campagne **construit**, et elle construit sur la surface de vente. La
frontière se déplace, elle ne disparaît pas.

- Tu ne traites **que** les `EXP-nn` cotés `PARTIEL` ou `KO`. Une exigence
  cotée `OK` ne se retouche pas, même si son code te déplaît.
- Tu crées et modifies sous `src/main` ce que ces exigences demandent, et rien
  de plus. Un refactoring rencontré en chemin se signale en une ligne dans le
  rapport final ; il ne se fait pas.
- Tu ne touches ni aux neuf références `src/test/resources/golden/`, ni à la
  commande qui les régénère : `-Dgolden.record=true` ne s'exécute jamais.
- Git : un commit par étape du §6, sur la branche courante, message en une
  ligne citant les `EXP-nn` servis. Jamais de branche nouvelle, jamais de push.
- Package : `com.intermarche.pos.service` accueille les services généraux —
  ceux qui ne servent pas directement l'IHM. Tout service qui injecte
  `PosState` ou sert un flux écran va dans `ui.*`. Jamais de package à classe
  unique.

## 5. Tests

- Unitaires : JUnit 5 + Mockito nu, selon `CLAUDE.md`. Jamais `@QuarkusTest`,
  jamais H2 pour un test de classe.
- **Chaque jambe de chaque expression booléenne composée a son cas de test**,
  nullité comprise : `a || b || c` fait trois cas où une seule jambe est vraie,
  plus le cas où aucune ne l'est. Passe-les mentalement avant de livrer ; ne les
  découvre pas dans JaCoCo.
- E2E : tout geste rendu visible à l'écran entre dans `e2e-scenarios.md` — une
  entrée dans le groupe qui lui correspond, avec son identifiant et son texte,
  **dans le fichier existant** et jamais dans un fichier satellite. Tu écris la
  classe `*IT` correspondante quand le groupe existe déjà ; sinon tu déclares le
  scénario et tu passes.
- `mvn verify` vert à la fin de chaque étape du §6, pas seulement à la fin de la
  campagne. Les deux classes rouges préexistantes d'`imedge` et les six
  d'`imfid` ne te concernent pas.

## 6. L'ordre de travail

Les étapes sont ordonnées par dépendance, pas par importance. Tu les fais dans
cet ordre, tu commites à la fin de chacune, et tu ne passes à la suivante que
`mvn verify` au vert.

### Étape 1 — Tenue du poste
**`EXP-01`, `EXP-02`, `EXP-03`, `EXP-04`, `EXP-10`, `EXP-11`, `EXP-12`,
`EXP-13`, `EXP-14`, `EXP-15`, `EXP-17`, `EXP-96`**

C'est ce qui fait de la caisse un poste tenu par une personne responsable de son
tiroir, au lieu d'un calculateur que n'importe qui réveille. Tout le reste
suppose ces états.

**Le jargon, et c'est lui qui commande le modèle.** « La déconnexion n'est pas
dans le jargon retail. » Il y a **trois** notions, pas deux, et le produit n'en
connaît aujourd'hui qu'une et demie :

- **Verrouiller / déverrouiller** — une pause courte. Le tiroir ne s'ouvre pas,
  l'écran affiche `CAISSE VERROUILLÉE`, et **le ticket en cours survit**.
- **Ouvrir / fermer sa caisse** — la caissière **retire son tiroir** pour aller
  déjeuner, ou pour emporter son tiroir sur une autre caisse. Rouvrir l'après-midi
  est **la continuité du matin** : même session, même fond, aucun nouveau
  comptage, aucun nouveau fond à déclarer.
- **Clôturer sa caisse** — compter, prélever tous les moyens de règlement, RAZ.
  C'est le geste de fin de poste, et lui seul. Le lendemain, la caissière
  redémarre son fond.

Ce que le produit appelle aujourd'hui « clôture Z » est la troisième notion, et
elle est correcte. Ce qui manque est **la seconde** : fermer et rouvrir sans
rien clôturer. La touche `DÉCONNEXION` de `main.html` disparaît au profit
d'elle. Et `AuthService.logout()` appelle `state.clearTicket()`, ce qui est
faux pour un verrouillage : sépare les chemins. La fermeture est refusée
pendant une transaction — `SessionCloseService.obstacle()` ne teste que les
tickets en attente et le mot de passe.

**L'identité du verrouilleur.** `AuthState` efface `operatorId` au
verrouillage, si bien que le pré-remplissage de `lock.html` ne se déclenche
jamais et que n'importe quel opérateur valide déverrouille la caisse d'une
autre. Conserve l'identité, affiche son code, ne demande que son mot de passe.

**Les deux forçages** ne servent pas le même cas et ne produisent pas le même
effet. Mot de passe oublié : le superviseur déverrouille, **la caissière garde
sa session et son tiroir**, et un changement de mot de passe lui est imposé à la
reprise. Caissière qui ne revient pas : c'est une **fin de journée forcée** —
`SessionCloseService.FORCE_CLOSE_ACTION` existe, relie-le au poste verrouillé et
abandonné (`EXP-96`).

**Le tiroir.** Un `Def` nouveau porte le plafond de contenu du tiroir, calé sur
le montant garanti par l'assurance, administré au **point de vente**. Au-delà,
la caisse affiche `PRÉLÈVEMENT` sans bloquer la vente. L'apport de monnaie
(`EXP-15`) quitte le montant unique de `CashMovement.DEPOSIT` pour la saisie par
coupure — réutilise les onglets billets/pièces/rouleaux de
`/cash-count/fragment/{type}`, comme `withdrawal.html` — et imprime son
bordereau.

**La calculatrice de fond** (`EXP-04`) est une **seconde** fonction, distincte du
comptage de clôture : mêmes coupures, billets jusqu'à 200 €, total affiché,
chaque opération annule la précédente, **rien n'est enregistré**, aucun événement
n'est émis. Ne la fabrique pas en ajoutant un drapeau à `CashCountService` : un
comptage qui n'écrit pas et un comptage opposable ne partagent que leur
arithmétique.

Les libellés (`EXP-01`, `EXP-03`) : `Bonjour : Connectez-vous` et
`CLIENT SUIVANT`, administrés, pas figés. Le fond théorique affiché à
l'ouverture (`EXP-02`) est le report de la clôture précédente, pas le paramètre
`cash.default-opening-float`.

### Étape 2 — Autorisations et opérations négatives
**`EXP-32`, `EXP-33`, `EXP-35`, `EXP-36`, `EXP-37`**

**La matrice.** Chaque fonction négative porte son propre drapeau d'autorisation
administré : annulation de ligne, annulation du dernier article, abandon de
ticket, retour, remboursement, remise, forçage de prix. Ces gestes appellent
aujourd'hui `requestAuthorization` **en dur**. Un `Def` par geste, résolu par la
chaîne d'échelons. La règle existante reste vraie : un caissier lui-même MANAGER
ou ADMIN (`operatorIsSupervisor`, LC-01-05-07) n'a jamais d'aval à demander.

**La session superviseur** (`EXP-33`) est le cœur de l'étape. Un second `Def` dit
la **portée** de l'aval : à chaque opération, ou une fois pour tout le ticket.
Dans le second cas l'identité du superviseur reste attachée à l'état de vente
jusqu'à la fin du ticket **ou** jusqu'à une touche `FIN SUPERVISEUR` explicite.
Cette touche n'est pas un confort : un superviseur qui s'en va en laissant son
autorisation ouverte est exactement la fraude que l'autorisation prétend
empêcher. Elle est donc visible, pas enfouie.

**Le garde-fou** (`EXP-35`) : l'annulation du dernier article est refusée quand
l'article est **le seul du ticket** — cela ramènerait le ticket à zéro article,
porte de fraude connue. Le refus nomme la sortie : l'abandon de ticket.

**L'annulation par scan** (`EXP-36`) : un mode qui s'arme, puis le prochain code
scanné désigne la ligne à annuler. Refus explicite si l'article n'est pas au
ticket. Le mode s'arme comme s'arme déjà la quantité (`HomeService.armQuantity`)
— même forme, même désarmement.

**Le retour ticket** (`EXP-37`) : le numéro imprimé en bas du ticket devient
scannable, et un `*ScanHandler` le route vers `/return` comme
`ParkedTicketScanHandler` route un ticket en attente. Les lignes déjà retournées
sont **grisées et non sélectionnables** — la classe CSS `.receipt-line.refunded`
de `return-detail.html` existe et n'est jamais posée. Acceptation : autre caisse
oui, autre jour oui, **autre magasin non** — chaque point de vente est une
société distincte.

### Étape 3 — Clôture opposable
**`EXP-79`, `EXP-80`, `EXP-82`, `EXP-83`, `EXP-95`**

Le comptage des espèces, le théorique et l'écart sont déjà calculés, stockés et
imprimés par `CashSessionService`. Tu leur donnes ce qui les rend opposables.

**Le seuil d'écart** (`EXP-82`) : un `Def` administré au **point de vente**,
usuellement 1 €. Au-delà en valeur absolue, la caisse alerte et propose deux
sorties : recompter, ou faire valider par le superviseur. Tant que l'une des deux
n'a pas eu lieu, la clôture ne passe pas. La validation laisse une trace
nominative.

**Le fond flottant** (`EXP-95`) change la clôture et non un paramètre. Deux modes
coexistent : le **fond fixe**, compté chaque soir — l'actuel ; et le **fond
flottant**, où le caissier prélève le soir les billets en laissant l'équivalent
du fond initial, et ne compte réellement qu'en fin de semaine et le dernier jour
du mois. Mode administré au point de vente. En flottant, les soirs sans comptage
ne calculent aucun écart.

**Les moyens papier** (`EXP-83`) : bons, avoirs, chèques se comptent en
**quantités** et se comparent aux quantités théoriques. Les drapeaux
`cashierDeclaration` et `automaticWithdrawal` de `TenderDefinition` portent déjà
la distinction papier / automatique ; il manque l'écran de saisie et la
comparaison.

**Le CA par caissier** (`EXP-79`) : le rapport X agrège aujourd'hui la session du
**terminal**. `Ticket.cashier` existe : ajoute l'agrégation par caissier sans
retirer celle du terminal, qui reste le document du poste. **Le bordereau de
règlement** (`EXP-80`) devient un document autonome, et cesse d'être le
sous-produit du ticket de prélèvement de `DrawerMethodService.transactionsOf`.

### Étape 4 — Ticket signé et recherche
**`EXP-43`, `EXP-45`, `EXP-47`, `EXP-38`, `EXP-39`, `EXP-40`, `EXP-49`**

**Le sous-total** (`EXP-43`) est le montant du groupe de lignes depuis le
précédent sous-total, et rien d'autre : il **ne découpe pas** le ticket en
paniers, les offres à seuil continuent de se calculer sur le ticket entier,
**le contrat avec le valorisateur est inchangé**. Un compteur et un marqueur de
ligne ; ne le construis pas comme une agrégation.

Mais il **s'imprime sur le ticket papier**, et c'est même sa raison d'être : on
fait les courses de plusieurs personnes en une fois, et chacune doit lire sa
part sur le ticket. Il entre donc dans le document fiscal — ligne dans
`TicketPrinterService`, présence dans le duplicata et dans le ticket
dématérialisé, et prise en compte par les références golden, qu'il faudra
régénérer par le geste prévu pour cela et jamais avec `-Dgolden.record=true`.

**Le moteur de recherche de ticket** (`EXP-47`) est commun au duplicata et au
retour : par numéro, par date et heure, par montant. Écris-le une fois,
branche-le des deux côtés.

**Le ticket mixte** (`EXP-38`) est le vrai chantier. Un retour produit aujourd'hui
un document `Refund` séparé. L'exigence est qu'un retour et des achats cohabitent
dans un même ticket, le total signé disant à la fin qui rembourse et qui paie :
lignes négatives dans `TicketState`, total signé, bascule du règlement vers le
remboursement selon le sens. C'est ce qui conditionne le retour sans ticket
(`EXP-39`) et le mode caisse retour de la journée (`EXP-40`), qui deviennent
presque gratuits une fois le modèle en place. Traite-les dans cet ordre.

**L'annulation d'un ticket payé** (`EXP-49`) : `CLOSED` est aujourd'hui un état
terminal de `Ticket`. Le rappel d'un ticket déjà payé pour en annuler les
articles est un geste fiscal à part entière, sous aval superviseur, et il laisse
deux documents, jamais un seul modifié. La limite « un seul ticket en attente »
(`EXP-45`) est une ligne : plusieurs sont permis aujourd'hui.

### Étape 5 — Référentiel et saisie
**`EXP-22`, `EXP-24`, `EXP-26`, `EXP-27`, `EXP-29`, `EXP-55`, `EXP-63`,
`EXP-93`**

**Les codes-barres à valeur** sont la partie délicate, et l'appel d'offres est ici
plus précis que le rapport : lis `LC-02-05-01`, `LC-02-05-02`, `LC-02-05-04`,
`BO-03-06-03`, `BO-03-06-04`, `BO-03-06-05` avant d'écrire.

- Préfixes à **prix embarqué** : `02, 21, 22, 24, 26, 28`. Préfixes à **poids
  embarqué** : `23, 25, 27, 29`. Valeur sur les digits 8 à 12, clé EAN-13
  standard. La liste administrée vaut aujourd'hui `21,22` pour le prix et
  `23,24,25,26` pour le poids : `24` et `26` sont classés à contresens, `02` est
  exclu par le filtre de repli `2\d{12}` de `WeightedEanScanHandler`, `27` et
  `29` manquent.
- La **position et la longueur** du champ sont administrées par plage, pas figées
  à 8-12.
- Le **format monétaire** est administré par plage : euro **ou** franc français.
  Les deux coexistent. Sur une étiquette en francs, le montant est converti en
  euros au taux **descendu du back-office**, valeur `6,55957` — administré comme
  un taux de devise, jamais en dur. `Currency` et `RefApplyService` montrent le
  chemin déjà emprunté par les devises.
- Sur un code à **prix** embarqué, `LC-02-05-01` demande que **le poids soit
  affiché, déduit du prix lu et du prix au kilo du référentiel**. C'est l'inverse
  du sens habituel. La caisse crée aujourd'hui une ligne en quantité 1 et masque
  volontairement la quantité ; il faut afficher le poids calculé, le prix au kilo
  et le prix total sur les deux visus et sur le ticket (`LC-02-05-04`).

**Le code famille** (`EXP-24`) : l'article non reconnu à prix libre existe mais
sans code famille — libellé figé, TVA par défaut, famille choisie par tuiles.
L'exigence est la saisie d'un **code famille** puis d'un prix : « 80, valider,
235, valider » affiche « Epicerie 2,35 € ». La famille porte son libellé et son
taux de TVA.

Le reste est court : défilement du tableau Fruits et Légumes au lieu de la
pagination (`EXP-22`) ; refus de répétition quand une quantité a été saisie
(`EXP-29`) ; touches d'arrondi au 10 € supérieur et coupures de 5 à 100 € sur le
pavé de paiement (`EXP-55`) ; annulation d'**un** règlement sans purger les
autres (`EXP-63`, seule l'annulation globale existe) ; touche `INFO ARTICLE` en
lecture seule (`EXP-93`), accessible **caisse fermée sans code utilisateur** —
donc dans l'allowlist de `LockCheckFilter`, et sans aucun chemin d'ajout au
ticket depuis cet écran.

### Étape 6 — Moyens de paiement manquants
**`EXP-54`, `EXP-58`, `EXP-60`, `EXP-66`, `EXP-67`, `EXP-48`, `EXP-74`,
`EXP-76`**

Ces exigences sont indépendantes les unes des autres et du reste : elles
s'ajoutent à un socle de paiement qui fonctionne. Carte TRD avec son assiette
d'éligibilité et son plafond, sur le modèle du titre restaurant papier déjà écrit
(`PaymentService.processTicketResto`) ; Resto Flash ; carte cadeau fournisseur
avec interrogation du registre de l'émetteur ; circuit SOGEC distinct du coupon
GS1 générique ; traçage du mode réel de la carte bancaire, sans contact ou code
secret, que `VerifoneTerminalClient` lit déjà sans l'enregistrer. Puis l'envoi du
ticket dématérialisé par SMS, le critère « ville » de la recherche client, et
l'affichage du champ `warnings` de la projection imfid, désérialisé aujourd'hui
et jamais lu.

### Étape 7 — Nœud magasin et onglet G
**`EXP-19`, `EXP-20`, `EXP-46`, `EXP-84`, `EXP-85`, `EXP-87`, `EXP-89`,
`EXP-90`, `EXP-91`, `EXP-92`**

La plus grosse étape, et la dernière parce qu'elle suppose les précédentes.

La règle dégradée est tranchée : **caisse hors ligne, la reprise d'un ticket en
attente ne vaut que sur sa propre caisse, et il n'y a aucune communication avec
le back-office**. C'est exactement le comportement actuel du code
(`TicketParkingService` filtre sur `terminalId`) : il devient la règle assumée,
et c'est le **mode connecté** qui est à construire. En dépendent les messages
montants et descendants, la reprise inter-caisses, les devis et commandes, le bon
de vente et le click and collect — tous bâtis sur le même rappel par code remis
au client par le back-office.

Deux avertissements. **Le coffre n'appartient pas à la caisse** :
`FeedImportResource` le déclare explicitement, c'est une entité du nœud magasin,
et la moitié caisse d'`EXP-87` se réduit aux mouvements qui l'alimentent.
**L'acompte** (`EXP-85`) existe comme mouvement de caisse `CUSTOMER_DEPOSIT` mais
sans rattachement : il ne prend son sens qu'avec les commandes, donc après elles.

### Étape 8 — La barre du bas
La nomenclature de l'expert compte sept onglets — Articles, Caisse, Règlement,
Ticket, Générosité, Tiroir, Autre — là où `PosMenu` en compte cinq. Une fois
toutes les fonctions livrées, réorganise la barre selon cette nomenclature. En
dernier, parce que déplacer des boutons avant de savoir combien il y en aura est
du travail fait deux fois.

## 7. Les décisions déjà prises

Quatre questions fonctionnelles restent sans réponse. Elles ne bloquent rien :
tu prends l'hypothèse ci-dessous, tu l'écris dans la Javadoc de la classe
concernée en la nommant comme telle, et tu la signales dans ton rapport final.

Trois autres ont été tranchées et ne sont **pas** des hypothèses : le sous-total
s'imprime sur le ticket (étape 4), fermer sa caisse n'est pas la clôturer et
rouvrir l'après-midi continue la session du matin (étape 1), et le badge
superviseur reste **EAN-13 seul** — pas de QR code pour le moment.

- **Avoir émis** : mentions légales = numéro, date d'émission, date de fin de
  validité, identification du point de vente. Format existant de `StoredValue`
  conservé. Repris **uniquement** dans le point de vente émetteur.
- **Fond flottant** : aucune tolérance contrôlée les soirs sans comptage ;
  l'écart n'est arbitré que les jours de comptage complet.
- **Rappel d'un ticket** : profondeur de rétention inchangée, celle que porte
  déjà la base.
- **Ordre de l'étape 7** : celui de la liste ci-dessus, dépendances d'abord.

## 8. Ce que tu rends

À la fin de chaque étape, trois lignes : les `EXP-nn` servis, le verdict de
`mvn verify`, les scénarios e2e déclarés.

À la fin de la campagne, un rapport court : le décompte des cinquante-trois
exigences servies, les fichiers créés et modifiés, les branches couvertes, et —
en une ligne chacune — les questions restées ouvertes et les améliorations hors
périmètre repérées en chemin.
