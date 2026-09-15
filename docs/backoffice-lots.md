# Back-office — backlog par lot

Ventilation **ligne à ligne** des 796 exigences de l'onglet Back Office du
questionnaire Intermarché. Compagnon de `backoffice-cadrage.md`, qui porte le
constat, la doctrine de cotation et le plan : ce fichier-ci porte le détail
que consomme une campagne agent.

**Partition stricte** : chaque exigence appartient à un lot et à un seul.
592 lignes dans les 13 lots fermés, 204 dans les 7 lots
conditionnels, 796 au total — le contrôle doit toujours retomber sur 796.

## Comment s'en servir pour une campagne

- **Un lot = une session d'agent.** On lui donne l'en-tête du lot (objectif,
  socle, critère de fin) et la table de ses exigences, rien d'autre.
- **La colonne « État » est le point de départ**, pas une décoration :
  `3` = à construire de zéro ; `1a` = un mécanisme existe, il s'agit de
  l'étendre ou de l'exposer — l'agent doit le trouver avant d'écrire ;
  `1` = déjà couvert, à ne pas retoucher, seulement à ne pas casser.
- **Le catalogue e2e du lot s'écrit avant le code**, à partir de cette table,
  comme `e2e-scenarios.md` a précédé les tests de la caisse.
- **Le critère de fin d'un lot est le décompte** : combien de ses lignes
  passent effectivement en `1` dans la colonne L du questionnaire. Le
  questionnaire est le juge, pas l'impression de progrès.
- **L'ordre des lots ci-dessous est l'ordre d'exécution recommandé.** Le lot 0
  conditionne tous les autres : rien ne commence avant qu'il soit vert.

Colonnes : code de l'exigence · priorité AO · état actuel en colonne L ·
sous-fonctionnalité · attendu (texte du questionnaire, ramené à l'essentiel).

---

# Lots fermés

## Lot 0 — Identité et accès

**14 exigences** (14 P1) — état actuel : 0 en `1`, 8 en `1a`, 6 en `3` · **1 session (écrit, à valider)**

*Objectif.* Fermer la surface : providers d'identité sur la table employees, mot de passe back-office distinct du PIN, changement imposé, @RolesAllowed vivantes.

*Socle.* com.intermarche.pos.security (4 classes), AdminAuthResource, form auth + HTTP Basic.

*Critère de fin.* mvn verify vert ; les 31 @RolesAllowed prouvées vivantes par un test qui échoue si on retire l'extension ; groupe e2e connexion/refus/changement imposé/401 anonyme.

| Code | P | État | Sous-fonctionnalité | Attendu |
|---|---|---|---|---|
| | | | **01 - 01 Gestion utilisateurs** | |
| `BO-01-01-01` | P1 | 1a | Comptes Administrateur | Un profil « Administrateur » doit être disponible au Groupement Les Mousquetaires qui créera autant de comptes nominatifs d’administration que nécessaire, et doit permettre de gérer les différentes habilitations des Utilisateurs. |
| `BO-01-01-02` | P1 | 1a | Comptes Utilisateur CRUD | Un « Administrateur » ou un Compte avec le niveau hiérachique nécessaire doit pouvoir créer /modifier /supprimer autant de Comptes Utilisateurs que nécessaire sans limite. |
| `BO-01-01-04` | P1 | 3 | Gestion de la Langue Paramétrage | Dans les attributs des utilisateurs, la langue de préférence doit pouvoir être paramétrée. Par défaut, la langue de référence de l’échelon auquel l’utilisateur est rattaché s’applique. Le choix de la langue de l'utilisateur s'applique aussi bien sur le BackOffice que sur la caisse. |
| `BO-01-01-05` | P1 | 3 | Gestion de la Langue Affichage | Les écrans doivent être dynamiques en fonction de la langue choisie et de la taille de l’écran. Ajustement dynamique des libellés et des textes sans impacter la résolution. Le périmètre de cette exigence s'applique aussi bien au BackOffice qu'aux différents types de caisse. |
| `BO-01-01-06` | P1 | 1a | Informations Compte Locaux Utilisateurs | À minima les informations suivantes doivent être prévues sans être obligatoires pour la création d'un compte utilisateur local: - Nom et prenom - téléphone - date de début |
| | | | **01 - 02 Authentification** | |
| `BO-01-02-01` | P1 | 3 | Authentification déléguée OIDC | Les méthodes d’authentification proposées par le soumissionnaire doivent être compatibles avec la délégation d’authentification à la solution SSO Stime (MMPU) avec les protocoles OIDC. -- Un atelier de conception de ce flux sera Prévu En tant que Utlisteur du BO (Central et/ou PDV) Je souhaite… |
| `BO-01-02-03` | P1 | 1a | Authentification Comptes Locaux | La solution doit aussi avoir la capacité de gerer, en complément de la délégation d'authentification, une authentification avec des comptes locaux créés (modifiés /supprimés) par un compte avec le niveau hiérachique nécessaire. |
| `BO-01-02-04` | P1 | 1a | Authentification Mode Standard | L'authentification au Back Office et au Front Office doit être de type Identifiant et Mot de Passe -- |
| `BO-01-02-06` | P1 | 1a | Authentification Mode allégé - Badge | Pour les Front Office, l'authentification peut être de type “Passwordless” par badge à lecture optique (code-barres 1D / 2D) -- Dernière décision de la sécurité : l’authentification à la caisse se fait via un badge et un mot de passe. |
| `BO-01-02-07` | P1 | 3 | Authentification Mode allégé - Clé Dallas | Pour les Front Office, l'authentification peut être de type “Passwordless” par clé Dallas -- Requis pour les Caisses Libre-Service |
| `BO-01-02-09` | P1 | 3 | Authentification Administration Modes | Le niveau et le type de sécurité attendus doivent être paramétrables à chaque échelon de l'organisation (un ou x Points de vente, une enseigne, un pays, un type de Points de Contact d'encaissement, un seul Point de Contact d'Encaissemen,etc.) et/ou à chaque profil et/ou à chaque utilisateur. --- Il… |
| `BO-01-02-10` | P1 | 1a | Changement du mot de passe Utilisateur | Un Administrateur ou un Compte avec le niveau hiérarchique nécessaire doit pouvoir réinitialiser le mot de passe d'un utilisateur de niveau inférieur. |
| `BO-01-02-11` | P1 | 1a | Saisie du nouveau mot de passe | L'utilisateur du Back Office ou du Front Office caisse sera invité à saisir son nouveau mot de passe à la première connexion. |
| `BO-01-02-12` | P1 | 3 | Sécurité - Mot de passe | Configuration du délai (en jours) après lequel le système invite automatiquement l’utilisateur (opérateur/opératrice de caisse) à modifier son mot de passe côté Front (caisse) et ou backoffice dans le cas d'une authentification non déléguée. |

## Lot 1 — Échelons

**13 exigences** (13 P1) — état actuel : 0 en `1`, 5 en `1a`, 8 en `3` · **2 à 3 sessions**

*Objectif.* Le modèle Pays → Enseigne → PDV → îlot, le rôle central, le tirage à deux niveaux, l'héritage et la surcharge.

*Socle.* StoreGroup et son atelier glisser-déposer (imvaluation) ; RefState, empreintes par domaine, RefPullService.

*Critère de fin.* Un paramètre posé au niveau enseigne se retrouve sur une caisse de deux PDV différents sans avoir été touché localement, et la surcharge d'un PDV survit au tirage suivant.

> **Seconde session — ce qui reste (01/09/2026).** La première session a livré
> le modèle Pays → Enseigne → PDV, la résolution d'héritage câblée dans
> `PosSettingsService` et l'écran central. Elle n'a **pas** livré la
> distribution : `pos.role=central` n'existe pas, et aucune entité d'échelon
> n'est dans un domaine du tirage. Tant que c'est le cas, une valeur posée au
> niveau enseigne ne peut atteindre aucune caisse, et les quatre lignes
> `BO-02-05-*` restent en `1a` quels que soient les écrans. La seconde session
> construit les cinq points listés au **§5.2.1 du cadrage** — la spécification
> de la route A —, et rien d'autre. Elle ne reconstruit pas le modèle : elle le
> distribue.

| Code | P | État | Sous-fonctionnalité | Attendu |
|---|---|---|---|---|
| | | | **01 - 01 Gestion utilisateurs** | |
| `BO-01-01-03` | P1 | 3 | Comptes Utilisateur / PDV | Un « Administrateur » ou un Compte avec le niveau hiérachique nécessaire doit pouvoir affecter/modifier/supprimer un Compte Utilisateur à un ou x échelons de l'organisation (Points de vente, enseigne, pays, type de Points de Contact d'encaissement, un seul Point de Contact d'Encaissemen,etc.). |
| | | | **01 - 03 Gestion Profils** | |
| `BO-01-03-04` | P1 | 3 | Profils Affectation PDV | Un Administrateur ou un Compte avec le niveau hiérarchique nécessaire doit pouvoir affecter plusieurs profils à un même utilisateur dans un ou x échelon de l'organisation (Points de Vente ou enseigne par exemple). -- *Au Niveau d'un ou de plusieurs PDV En tant que ou Adhérent/directeurs de… |
| | | | **02 - 05 Référentiel Point De Vente** | |
| `BO-02-05-01` | P1 | 1a | Format et gestion du numéro de Point De Vente (PDV) | Le numéro de Point De Vente (N° PDV) doit être composé de 5 digits exactement. Ce numéro doit être unique à l'échelle globale, tous pays et toutes enseignes confondus (aucun doublon possible, quel que soit le pays ou l'enseigne). Une fois le PDV créé, son numéro doit pouvoir être modifié. En cas de… |
| `BO-02-05-02` | P1 | 3 | Changement d'enseigne d'un Point De Vente | Un Point De Vente doit pouvoir changer d'enseigne (rattachement à une nouvelle enseigne). Ce changement doit permettre le déplacement du PDV sous la nouvelle enseigne, avec héritage automatique de l'ensemble des paramètres de la nouvelle enseigne (menus, profils, TVA, modèles de documents, langue,… |
| `BO-02-05-03` | P1 | 1a | Import des Points De Vente avec leur organisation via API | Il doit être possible d'intégrer, via API, les Points De Vente ainsi que leur rattachement organisationnel (pays, enseigne, échelon hiérarchique) dans le système d'encaissement. |
| `BO-02-05-04` | P1 | 3 | Héritage des règles/paramètres par pays et par enseigne | Chaque Point De Vente doit bénéficier automatiquement des règles et paramètres définis au niveau de son pays et de son enseigne de rattachement (fiscalité, TVA, langue, modèles de documents, menus, profils, etc.) |
| `BO-02-05-05` | P1 | 1a | Rattachement de plusieurs PDV à un même Adhérent | Il doit être possible de rattacher plusieurs points de vente à un même adhérent. Les niveaux organisationnels Pays, Enseigne et Point de Vente doivent permettre d'obtenir une vue de regroupement par adhérent. Cette vue de regroupement par adhérent doit pouvoir être utilisée dans les différents… |
| | | | **03 - 12 Administration technique** | |
| `BO-03-12-01` | P1 | 1a | Synchronisation données Point De Vente - Caisses | Les mises à jour de données/configurations effectuées en Backoffice Point De Vente doivent être immédiatement disponibles en caisses. |
| `BO-03-12-03` | P1 | 3 | Déploiement Partiel des données configurées - Pays -… | Mise à jour programmable des configurations partielles en Point de Vente sans impact sur les caisses - les règles d'encaissement - Motif TVA, TVA - modèle de document - codes postaux - Alertes - menu - profil - utilisateurs - configuration code-barres, configuration des caissières - îlots de caisse… |
| `BO-03-12-04` | P1 | 3 | Déploiement Complet des données configurées vers une liste… | Mise à jour programmable des configurations complètes sans impact sur les caisses : - règle encaissement - Motif TVA - TVA - modèle de document - codes postaux - Alertes - menu - profil - utilisateurs - configuration code-barres - configuration des caissières - îlots de caisse - code raison -… |
| `BO-03-12-05` | P1 | 3 | Activation / désactivation manuel du mode dégradé monétique | Fonction permettant au Point De Vente de passer en mode dégradé monétique en cas de coupure vers les serveurs monétique(permet d'éviter une latence monétique à chaque paiement cb) |
| `BO-03-12-06` | P1 | 1a | Configuration des données Point De Vente | Configuration des informations du point de vente : - Nom magasin - N° Point De Vente ==> donnée non modifiable par le Point De Vente - adresse - ville - Numéro TVA intracommunautaire - N° Siret - N° téléphone - Pays - N° compte (chèque) |
| `BO-03-12-07` | P1 | 3 | Vue centrale des PDV ayant personnalisé un paramètre par… | Depuis l'écran des paramètres par défaut d'un pays ou d'une enseigne, il doit être possible de visualiser la liste des Points De Vente ayant une personnalisation locale sur un paramètre donné. |

## Lot 3 — Paramétrage généralisé (élargissement)

**59 exigences** (52 P1) — état actuel : 3 en `1`, 35 en `1a`, 21 en `3` · **2 à 3 sessions**

*Objectif.* Exposer au back-office les comportements que la caisse SAIT DÉJÀ FAIRE : une clé au catalogue, un câblage, rien à inventer. C'est le lot 3 tel qu'il aurait dû être décrit.

*Socle.* PosSettingsService, domaine SETTINGS du tirage, écran au gabarit admin. Le tirage est générique : une clé nouvelle ne demande aucune modification de la plomberie de distribution.

*Critère de fin.* Chaque clé ajoutée est câblée à un comportement caisse et démontrée par un test — jamais de bouton mort. Le décompte du lot compte les lignes qui passent en 1, pas les clés créées.

| Code | P | État | Sous-fonctionnalité | Attendu |
|---|---|---|---|---|
| | | | **03 - 07 Gestion des remises - rabais** | |
| `BO-03-07-01` | P1 | 1a | Activation Remise/Rabais | Configuration permettant l'activation des remises rabais afin d'avoir la fonctionnalité active en caisse |
| `BO-03-07-04` | P1 | 1 | Seuil Remise / Rabais | Chaque remise/rabais configuré(e) (à l'article ou au total) doit permettre l'ajout d'un seuil maximum. Par exemple il ne doit pas être possible d'appliquer une remise total ticket de plus de 50% ou d'avoir une remise article de plus de 90% |
| `BO-03-07-10` | P1 | 1a | Mise en supervision | Mise en appel superviseur des touches Remise / Rabais via configuration |
| `BO-03-07-11` | P1 | 3 | Remise - rabais article déjà remisé | En tant qu' administrateur / adhérent Je veux pouvoir configurer, au niveau du BackOffice, si une remise/rabais appliqué(e) directement sur un article peut ou non se cumuler avec une remise déjà existante sur ce même article (promotion automatique ou remise/rabais manuel préalable) Afin de… |
| `BO-03-07-12` | P1 | 3 | Autorisation d'application d'un(e) remise/rabais global(e)… | En tant qu' administrateur / adhérent Je veux pouvoir configurer, au niveau du BackOffice, si un(e) remise/rabais global(e) appliqué(e) sur le total du ticket peut ou non s'appliquer sur des articles déjà remisés (par promotion automatique ou remise/rabais manuel) Afin de contrôler le cumul des… |
| | | | **03 - 08 Gestion des messages** | |
| `BO-03-08-01` | P1 | 1a | Message à l'ouverture caisse | Paramétrage d'un message spécifique pour Affichage à l'ouverture de caisse |
| `BO-03-08-02` | P1 | 1a | Message à la fermeture caisse | Paramétrage d'un message spécifique pour Affichage à la fermeture de caisse |
| `BO-03-08-03` | P1 | 3 | Message en début /fin de ticket | Paramétrage d'un message spécifique pour Affichage en début ou en fin de ticket |
| `BO-03-08-05` | P2 | 3 | Ticket de caisse | Paramétrage d'un message spécifique pour impression sur le ticket |
| | | | **03 - 13 Paramétrage des retours** | |
| `BO-03-13-09` | P1 | 1a | Contrôle Retour | Configurer un contrôle au retour. Cela permet d'associer un contrôle de type superviseur afin de valider l'action de retour en caisse par un superviseur |
| `BO-03-13-11` | P1 | 1a | Configuration Retour: déconsigne | Activer le retour comme étant une déconsigne. Cela permet d'alimenter les rapports consigne/déconsigne |
| | | | **10 - 02 Caisse** | |
| `BO-10-02-12` | P1 | 3 | Tiroir - règle d'ouverture tiroir | Activation ou non des règles d'ouverture tiroir: - ouverture tiroir rattachée au paiement - ouverture tiroir en mode école - ouverture tiroir lors de la fin de période |
| `BO-10-02-14` | P1 | 1a | Scan - Article inconnu | Au scan d’un produit inconnu, afficher un message d’erreur : « article inconnu » et proposer la saisie d’une famille/code. |
| `BO-10-02-18` | P1 | 3 | Gestion règle d'arrondi | Configuration de la règle d'arrondi sur les calculs effectué en caisse par exemple pour les factures / ventilation |
| `BO-10-02-21` | P1 | 3 | CheckDigit Saisie Article | Règle permettant d'activer le contrôle ou non du checkdigit de l'ean13 |
| `BO-10-02-22` | P1 | 1a | Connexion - Sécurité | Activation de la demande de saisie mot de passe à l'ouverture caisse |
| `BO-10-02-23` | P1 | 3 | Connexion - Sécurité | Paramètre de définition du nombre de chiffres(digits) pour la saisie mot de passe |
| `BO-10-02-25` | P1 | 1a | Connexion - tiroir | Paramètre d'activation ouverture du tiroir à l'ouverture à caisse |
| `BO-10-02-27` | P1 | 1 | Verrouillage/fermeture - Sécurité 1/2 | Configuration du nombre de minutes d’inactivité en caisse afin d’effectuer un verrouillage ou une fermeture de caisse. |
| `BO-10-02-28` | P2 | 1a | Verrouillage/fermeture - Sécurité 2/2 | Configuration du mode de sécurité sans activité de la caisse : verrouillage ou fermeture caisse |
| `BO-10-02-29` | P1 | 1a | Connexion - Sécurité | Activation du scan code-barres hôte de caisse à l'ouverture caisse |
| `BO-10-02-30` | P1 | 1a | Déverrouillage - Sécurité | Activation du scan code-barres hôte de caisse afin de sortir du mode caisse verrouillée |
| `BO-10-02-31` | P1 | 1a | Superviseur - Sécurité | Paramètre définissant le type de validation à utiliser comme superviseur Définir le choix de validation superviseur : - code superviseur + mot de passe - code-barres superviseur - code-barres superviseur + code + mot de passe - code-barres superviseur + mot de passe - clé dallas |
| `BO-10-02-34` | P1 | 1a | Code article Ticket | Paramètre permettant d'activer l'impression du code article. Si ce paramêtre est activé par défaut tous les articles auront le flag code article article activé |
| `BO-10-02-35` | P1 | 1a | Code article écrans | Paramètre permettant d'activer l' affichage code article sur les écrans hôte(sse) de caisse et client |
| | | | **10 - 03 Fidélité** | |
| `BO-10-03-01` | P1 | 3 | Fidélité - Code enseigne | Paramètre permettant de définir le code enseigne de rattachement FID du Point de Vente. ITM France = IF Netto France = NF Brico France = BF ITM Belgique=IB ITM Portugal=IP PT Brico = BP Bricocash France = BC Bricorama France = BR |
| `BO-10-03-02` | P1 | 1a | Fidélité - scan multiple de carte de fidélité | Paramètre permettant d'autoriser ou non le scan multiple de carte de fidélité en cours de transaction. En cas de scan multiple, seule la dernière carte de fidélité est prise en compte. |
| `BO-10-03-03` | P1 | 3 | Fidélité - affichage nom client FID sur le viseur client | Paramètre permettant d'activer ou non l'affichage du nom prénom client sur le viseur client au scan de la carte de fidélité. |
| `BO-10-03-04` | P1 | 1a | Fidélité - affichage nom client FID sur le viseur hôte(sse)… | Paramètre permettant d'activer ou non l'affichage du nom prénom client sur le viseur hôte(sse) de caisse au scan de la carte de fidélité. |
| `BO-10-03-07` | P1 | 1a | Fidélité - Activation d'une Fidélité externe | paramètre permettant d'activer une fidélité externe au système d'encaissement |
| `BO-10-03-08` | P1 | 1a | Fidélité - configuration | paramètre permettant de configurer les appels FID |
| `BO-10-03-10` | P1 | 1a | Fidélité - délai de réponse | Définir le délai de réponse de la Fid avant affichage du message d'indisponibilité fid |
| `BO-10-03-15` | P1 | 1a | Fidélité - Avantage | Paramètre permettant d'activer les demandes d'avantages FID. Si ce paramètre est activer, le modèle de ticket avantage fid sera imprimé dans le cas contraire il n'y aura pas de section ticket fid |
| `BO-10-03-16` | P1 | 1a | Fidélité - validation transaction | Gestion de la validation du ticket vers la Fidélité en asynchrone avec SAF en cas de dépassement délais |
| `BO-10-03-19` | P1 | 3 | Fidélité - Activation de l'envoi de la sous-famille | Paramètre permettant d'activer l'envoi de la sous famille au moteur de promotion afin de déclencher les avantages à la sous-famille |
| `BO-10-03-22` | P1 | 1a | Fidélité - moteur de promotion Activation JV (activation de… | Paramètre permettant d'activer les appels de déclenchement EARN/BURN promotion personnalisée à la carte de fidélité. Cela permet au moteur de promotion de faire appel au référentiel carte client afin de connaitre les promotions éligibles à la carte du client scanné et ensuite de remonter au… |
| | | | **10 - 07 Présentation** | |
| `BO-10-07-01` | P1 | 1a | Affichage Ecran clients 10'' | Gestion des données d'affichage de l'écran client en caisse ouverte, caisse fermée, en cours de ticket, caisse en veille |
| `BO-10-07-02` | P1 | 1a | Affichage QRCODE Ecran Client 10'' | Activation de l'affichage du QRCODE pour récupération Ticket en fin de transaction |
| `BO-10-07-04` | P1 | 3 | Ticket - symbole monétaire | Activation de l'impression du symbole sur les tickets |
| `BO-10-07-05` | P1 | 3 | Ticket - Configuration symbole | Configuration des symboles : - Symbole monétaire, exemple EUR ou € - Texte imprimé sur le chèque pour les devises : exemple Euro(s) - Texte imprimé sur le chèque pour les devises centimes: exemple Cent(s) |
| `BO-10-07-06` | P1 | 3 | Ticket - Position symbole monétaire | Configuration du positionnement du symbole monétair : à gauche ou à droite du montant |
| `BO-10-07-07` | P1 | 1a | Ticket - mode Ecole | La mention "mode école" ou équivalent est imprimée clairement à plusieurs endroits sur le ticket (par exemple dans l'en-tête, milieu et dans le pied de ticket) afin d'empêcher la fraude à la découpe de l'entête du ticket et distinguer clairement un ticket école d'un vrai ticket de vente |
| `BO-10-07-08` | P1 | 1a | Ecran - mode Ecole | Configuration de l"écran caisse" spécifique au mode école (training/formation) différent de l'écran de vente mode standard |
| `BO-10-07-10` | P1 | 3 | Ticket - impression total Article | Configuration du total Article sur le ticket: - pas d'impression - sur la ligne montant dû - sur une ligne individuel |
| `BO-10-07-12` | P1 | 1a | Ticket - impression prix d'origine forçage prix | Afficher / masquer au client sur l'écran et sur le ticket le prix d'origine article lors du forçage prix |
| `BO-10-07-14` | P1 | 3 | Ticket - Montant dû | choix du mode d'impression du montant dû : en plus grand que les fontes standard |
| `BO-10-07-16` | P1 | 3 | Ticket - Archive | Configuration du délai maximum avant abandon caisse de la recherche ticket |
| `BO-10-07-19` | P1 | 1a | Ecran CAISSE mode Déconnecté (offline) | Activation et délai d'affichage écran caisse mode déconnecté (fond d'écran, apparence caisse, en mode offline diffèrent de l'apparence caisse en mode connecté) |
| `BO-10-07-21` | P1 | 1a | Configuration - Impression Conditionnelle | Activation de la fonctionnalité Impression Conditionnelle qui permettra d'avoir une demande d'impression du ticket en fin de parcours avec les options suivantes : - Tickets papier - Ticket CB uniquement - Pas de Ticket Avec l'option de saisie d'email |
| `BO-10-07-22` | P2 | 1a | Configuration - envoi de mail | Activation de l'option d'envoi de mail. Cette fonctionnalité doit permettre d'envoyer les emails client en fin de parcours selon les choix effectués en caisse |
| `BO-10-07-23` | P2 | 1a | Configuration - Modification de mail | Activation de la possibilité de modifier en caisse l'adresse mail du client avant envoi de l'email. La saisie de l'adresse mail est par défaut possible si aucune adresse mail client n'est configurée |
| | | | **10 - 08 Backoffice** | |
| `BO-10-08-01` | P1 | 1a | Alerte - Activation | Activation de l'affichage des alertes caisses sur le backoffice |
| `BO-10-08-22` | P1 | 3 | Article - sous famille | Paramètre permettant d'activer la gestion article à la sous famille notamment utiliser pour les IM FR afin d'envoyer la sous famille à la FID |
| `BO-10-08-25` | P1 | 1 | Référentiel - Mise à jour | Les mises à jour de données ARTICLES/PROMOTIONS en caisse pouvant être effectuées en cours de journée NE DOIVENT PAS avoir d'impact sur le déclenchement des promotions / ventes d'articles en caisse. |
| `BO-10-08-28` | P1 | 3 | Unité et mesure caisse - nombre de décimales | Configuration pour les unités de mesure du nombre de décimales à afficher après la virgule |
| `BO-10-08-29` | P1 | 3 | Unité et mesure caisse - Poids | Configuration du symbole à afficher et à imprimer sur tous les écrans et reçus pour des quantités de pesées |
| `BO-10-08-30` | P2 | 3 | Affichage ticket Annulation article / annulation Ligne | Il doit être possible via configuration de faire afficher ou non les lignes articles en annulation ligne ou annulation article sur le ticket lors de l'utilisation des ces fonctions en caisses |
| `BO-10-08-31` | P2 | 1a | Optimisation des lignes article sur le ticket | Il doit être possible via configuration d'optimiser les lignes articles afin de regrouper les mêmes articles avec la quantité |
| `BO-10-08-32` | P3 | 1a | Optimisation des lignes article écrans caisse | Il doit être possible via configuration d'optimiser les lignes articles afin de regrouper les mêmes articles avec la quantité |

## Lot 3B — Fonctions de caisse à construire

**57 exigences** (51 P1) — état actuel : 0 en `1`, 7 en `1a`, 50 en `3` · **hors plan back-office**

*Objectif.* Ces exigences se présentent comme du paramétrage mais le comportement à paramétrer N'EXISTE PAS : moteur d'alertes et de points de contrôle, référentiel des remises, types de retour administrables, politique de rétention, i18n. Il faut d'abord écrire la fonction en caisse.

*Socle.* Aucun — c'est là le problème. Chacune est un chantier de fonctionnalité, à instruire et à chiffrer séparément.

*Critère de fin.* Ne pas ouvrir depuis la campagne back-office. Ces lignes remontent en arbitrage de périmètre, au même titre que les sept lots conditionnels.

| Code | P | État | Sous-fonctionnalité | Attendu |
|---|---|---|---|---|
| | | | **03 - 07 Gestion des remises - rabais** | |
| `BO-03-07-02` | P1 | 1a | Création Remise Article | Création d'une remise avec une valeur de saisie en pourcentage. Comportement attendu : La valeur en % est saisie en caisse lors de la sélection de cette touche |
| `BO-03-07-03` | P2 | 3 | Création Remise x% Article | Création d'une remise avec un pourcentage défini, permettant ainsi de sélectionner directement la touche avec le bon pourcentage en caisse. Exemple : Sélection en caisse de la touche 20%, donne lieu à l'application d'une remise de 20% sur la ligne article |
| `BO-03-07-05` | P1 | 1a | Création Rabais Article | Création d'un rabais, permettant la saisie du montant du rabais en euros en caisse |
| `BO-03-07-06` | P2 | 3 | Création Rabais Xeur Article | Création d'un rabais avec un montant défini en Euros, permettant ainsi l'application d'un rabais en euros sur l'article lors en caisse lors de la sélection de la touche. |
| `BO-03-07-07` | P1 | 1a | Création Rabais Total | Création d'un rabais total pouvant être déclenché en caisse après Total avec saisie du montant |
| `BO-03-07-08` | P1 | 1a | Création Remise Total | Création d'une remise totale pouvant être déclenchée en caisse après Total avec saisie du % de remise |
| `BO-03-07-09` | P1 | 3 | Suppression | Suppression une/des remise(s) rabais avec mise suppression en caisse de la fonctionnalité |
| | | | **03 - 08 Gestion des messages** | |
| `BO-03-08-04` | P2 | 3 | Paramétrages diffusion Message | Paramétrages : - de la période de diffusion en jj:mm:aa hh/mn - durée d'affichage - la population concernée : l'ensemble des hôtesses de caisse, une liste limitée (îlot), un utilisateur, un Point de Contact d'Encaissement - la réception d'une alerte suite à la confirmation de l'hôte(sse) de caisse… |
| `BO-03-08-08` | P2 | 3 | Messages immédiats | Cette fonctionnalité permet d'afficher immédiatement sur les Front-Offices un message d'information, paramétré en Back office, aux hôtesses de caisse |
| | | | **03 - 13 Paramétrage des retours** | |
| `BO-03-13-01` | P1 | 3 | Retour Création | Possibilité de créer un type de retour afin de permettre de gérer les différents cas de retour |
| `BO-03-13-02` | P1 | 3 | Retour Suppression | Supprimer un type de retour |
| `BO-03-13-03` | P1 | 3 | Retour Activation | Activation d'un retour activation d'un type de retour. Une fois activé ce dernier est automatiquement disponible en caisse |
| `BO-03-13-04` | P1 | 1a | Retour Type Ticket Retour | Configuration d'un retour comme étant un Type retour Ticket L'objectif étant de pouvoir effectuer en caisse un retour de ticket via scan du code-barres du ticket d'origine ou saisie du N°Ticket/date |
| `BO-03-13-05` | P1 | 1a | Retour Type Retour ticket Origine | Configuration d'un retour permettant de récupérer via scan du code-barres ticket de vente ou saisie des informations ticket d'effectuer un retour : - article basé sur la récupération du ticket - total ticket |
| `BO-03-13-06` | P1 | 3 | Retour Type Retour ticket Origine non trouvé | La fonction ticket retour basée sur le ticket d'origine devra permettre en cas de ticket NON trouvé permettre le retour du ticket / article via une une autorisation superviseur |
| `BO-03-13-07` | P1 | 1a | Retour Type Retour Article | Configuration d'un retour comme étant un Type retour Article L'objectif étant de pouvoir effectuer en caisse un retour article dans un ticket de vente |
| `BO-03-13-08` | P1 | 3 | Retour Type Retour Article Spécifique | Il doit être possible de configurer des retours spécifiques de type « RETOUR ARTICLES SPÉCIFIQUES», permettant la réalisation d’un retour article en caisse sans nécessiter de validation ou d’autorisation superviseur. Exemple : Échange d’article de même valeur |
| `BO-03-13-10` | P1 | 3 | Configuration Retour: confirmation du prix | Configurer la confirmation du prix au retour dans le cadre des retours manuels (hors retour à partir d'un ticket d'origine). Cela permet en caisse de valider le prix de retour de l'article ou de le modifier, cette action de confirmation ou de modification prix est effectuée uniquement dans le cadre… |
| | | | **10 - 02 Caisse** | |
| `BO-10-02-01` | P1 | 3 | Points de contrôle - Vente annulation article | Possibilité de mettre en place des alertes/contrôle en mode vente sur l'utilisation de la fonction annulation article en mode vente. Les contrôles devant permettre d'associer un type d'alerte pouvant être non bloquant ou bloquant avec validation ou pas superviseur |
| `BO-10-02-02` | P1 | 3 | Points de contrôle - Vente annulation paiement partiel | Possibilité de mettre en place des alertes/contrôle en mode vente sur l'utilisation de la fonction annulation paiement lors d'un paiement partiel. Les contrôles devant permettre d'associer un type d'alerte pouvant être non bloquant ou bloquant avec validation ou pas superviseur |
| `BO-10-02-03` | P1 | 3 | Points de contrôle - Retour | Possibilité de mettre en place des alertes/contrôle sur l'utilisation de la fonction Retour. Les contrôles devant permettre d'associer un type d'alerte pouvant être non bloquant ou bloquant avec validation ou pas superviseur |
| `BO-10-02-04` | P1 | 3 | Points de contrôle - Vente tiroir | Possibilité de mettre en place des alertes d'ouverture tiroir en dehors du mode vente. Les contrôles devant permettre d'associer un type d'alerte pouvant être non bloquant ou bloquant avec validation ou pas superviseur |
| `BO-10-02-05` | P1 | 3 | Points de contrôle - Vente Forçage Prix | Possibilité de mettre en place des alertes sur l'utilisation Forçage Prix Les contrôles devant permettre d'associer un type d'alerte pouvant être non bloquant ou bloquant avec validation ou pas superviseur |
| `BO-10-02-06` | P1 | 3 | Points de contrôle - Vente quantité | Possibilité de mettre en place un contrôle de saisie quantité article. Les contrôles devant permettre d'associer un type d'alerte pouvant être non bloquant ou bloquant avec validation ou pas superviseur |
| `BO-10-02-07` | P1 | 3 | Points de contrôle - Vente montant max | Possibilité de mettre en place un contrôle de montant total maximum.Les contrôles devant permettre d'associer un type d'alerte pouvant être non bloquant ou bloquant avec validation ou pas superviseur |
| `BO-10-02-08` | P1 | 3 | Points de contrôle - log off ticket en attente | Possibilité de mettre en place des alertes/contrôle en caisses configurable lors de la fermeture de session caisse si présence de ticket en attente, le contrôle devant permettre d'y associer une alerte caisse en warning non bloquant ou bloquant avec validation ou pas superviseur Les contrôles… |
| `BO-10-02-10` | P3 | 3 | Points de contrôle - Moyen de paiement | Possibilité de mettre en place des alertes/contrôle sur l'utilisation des moyens de paiement monétique Les contrôles devant permettre d'associer un type d'alerte pouvant être non bloquant ou bloquant avec validation ou pas superviseur |
| `BO-10-02-11` | P1 | 3 | Tiroir - alerte montant | Mise en place d'une alerte affichage icone prélèvement en cas de dépassement du montant configuré |
| `BO-10-02-13` | P1 | 3 | Clavier - touche entrée | Activation ou non des règles d'utilisation de la touche entrée: - utilisation de la touche entrée comme total |
| `BO-10-02-15` | P1 | 3 | Scan - Vente Article mode/moyen de Paiement | Règle permettant d'autoriser ou non les ventes en mode moyen de paiement |
| `BO-10-02-16` | P1 | 3 | Scan - Montant maximum Article | Il doit être possible de configurer le montant maximum autorisé pour un article, soit en définissant une valeur maximale (par exemple : Montant maximum = 99 999,99), soit en paramétrant le nombre maximal de chiffres acceptés pour le montant de l’article. Cette limite doit s’appliquer aussi bien aux… |
| `BO-10-02-17` | P1 | 3 | Scan - Montant maximum Moyen de Paiement | Il doit être possible de configurer le montant maximum autorisé pour un moyen de paiement, soit en définissant une valeur maximale (par exemple : Montant maximum = 99 999,99), soit en paramétrant le nombre maximal de chiffres acceptés pour le montant d’un moyen de paiement, décimales incluses.… |
| `BO-10-02-24` | P1 | 3 | Déconnexion - Sécurité | Activation de la demande de saisie mot de passe à la fermeture caisse |
| `BO-10-02-26` | P1 | 3 | Déconnexion - tiroir | Paramètre d'activation ouverture du tiroir à la fermeture caisse |
| `BO-10-02-32` | P1 | 3 | Préfixe Superviseur - Sécurité | Mise en place d'un préfixe masqué afin d'empêcher la saisie du code-barres Paramètre définissant le préfixe du code-barres superviseur (code-barres imprimé = préfixe + code superviseur définit dans les codes barre) |
| | | | **10 - 03 Fidélité** | |
| `BO-10-03-09` | P1 | 3 | Fidélité - images/icônes | Gestion des icônes/images associés aux avantages. Exemple impression des images de fréquentation, de cadeaux, …. |
| `BO-10-03-14` | P1 | 3 | Fidélité - Avantage Non porteur | Paramètre permettant d'activer les demandes d'avantages FID pour les non porteurs. Si ce paramètre est activer, le modèle de ticket avantage fid non porteur sera imprimé dans le cas contraire il n'y aura pas de section ticket fid |
| `BO-10-03-17` | P1 | 3 | Fidélité - demande avantage à date commande | Paramètre permettant d'activer l'envoi de la valeur date de commande au moteur de promotion FID afin de déclencher les avantages générés à la commande |
| | | | **10 - 07 Présentation** | |
| `BO-10-07-09` | P1 | 3 | Ticket - impression ticket ouverture/fermeture | En tant qu'administrateur Je veux pouvoir activer ou désactiver, (applicable à l'ensemble des caisses), l'impression automatique des tickets d'ouverture et/ou de fermeture de session de caisse Afin de garder une trace papier des opérations d'ouverture et de fermeture pour le contrôle de caisse /… |
| `BO-10-07-17` | P1 | 3 | Langue : Bilinguisme | Configuration des 2 langues sur l'afficheur client. |
| `BO-10-07-18` | P1 | 3 | Langue | Configuration de la langue par défaut du système d'encaissement (BackOffice et différents types de caisse). Paramètre permettant de choisir la langue par défaut de l'application si aucune préférence langue utilisateurs est configurée |
| `BO-10-07-20` | P1 | 3 | Message Article vente interdit | Configuration du message devant être affiché en caisse si l'article est en vente interdite même si le message n'est pas configuré sur l'article |
| | | | **10 - 08 Backoffice** | |
| `BO-10-08-02` | P2 | 3 | Alerte - Activation | Activation de l'affichage des alertes caisses sur un device mobile |
| `BO-10-08-03` | P1 | 3 | Archivage - Ventes Articles | Configuration du délais (jour / semaine /mois/ année) de conservation de la donnée vente article en base avant suppression (Supression ou Archivage à définir) |
| `BO-10-08-07` | P1 | 3 | Archivage - Historiques Ventes Caissières | Configuration du nombre de rapport états caissières conservés en base de données avant suppression |
| `BO-10-08-08` | P1 | 3 | Archivage - Historiques tables historiques | Configuration du nombre de jours pendant lequel toutes les tables quotidiennes non définie individuellement sont conservés dans la base de données avant d'être supprimées, telle que les donnée quotidienne d'ouverture de session,..; |
| `BO-10-08-09` | P1 | 3 | Archivage - Historiques Journal accès utilisateurs | Configuration du nombre de jours pendant lequel sont conservés les enregistrements de l'accès utilisateur aux options et fonctions dans les tables journal d'accès avant d'être supprimées. |
| `BO-10-08-10` | P1 | 3 | Archivage -Historiques Maj… | Configuration du nombre de jours pendant lequel sont conservés les résultats d'intégration article en base Point De Vente. |
| `BO-10-08-11` | P1 | 3 | Archivage - Historiques alerte | Configuration du nombre de jours pendant lequel des enregistrements d'alertes sont conservés en base de données avant d'être supprimés |
| `BO-10-08-12` | P1 | 3 | Archivage - Nombre jours ticket en attente | Configuration du nombre de jours pendant lequel les tickets en attentes sont conservés en base de données avant d'être supprimés |
| `BO-10-08-13` | P1 | 3 | Archive - Nombre jours code-barres non utilisés expirés | Configuration du nombre de jours pendant lequel les codes-barres non utilisés expirés sont conservés en base de données avant d'être supprimés |
| `BO-10-08-14` | P1 | 3 | Archivage - Nombre jours code-barres utilisés expirés | Configuration du nombre de jours pendant lequel les codes-barres utilisés après leur date d'expiration sont conservés en base de données avant d'être supprimés |
| `BO-10-08-15` | P1 | 3 | Archivage - Nombre jours code-barres | Configuration du nombre de jours pendant lequel les codes-barres n'ayant pas de date d'expiration sont conservés en base de données avant d'être supprimés |
| `BO-10-08-16` | P1 | 3 | Archivage - Factures | Configuration du nombre de jours pendant lequel les Factures/bonde Livraison sont conservés en base de données avant d'être supprimés |
| `BO-10-08-17` | P1 | 3 | Archivage - Promotions échues | Configuration du nombre de jours pendant lequel les promotions échues sont conservés en base de données avant d'être supprimés |
| `BO-10-08-18` | P1 | 3 | Nombre suppression Promotions échues | Configuration du nombre maximum de promotions échues à supprimer pendant la fin de période |
| `BO-10-08-24` | P1 | 3 | Article - Suppression | Configuration du nombre de jours pendant lequel les articles "supprimés" sont conservés |

## Lot 3C — Paramètres portés par un autre lot

**20 exigences** (19 P1) — état actuel : 0 en `1`, 1 en `1a`, 19 en `3` · **—**

*Objectif.* Le réglage est légitime mais l'objet qu'il configure est construit ailleurs : modèles de ticket, moyens de règlement, plages de codes-barres, référentiel articles, clients en compte.

*Socle.* Les lots 5, 7, 8, 9 et les lots conditionnels A et C.

*Critère de fin.* Aucune session propre : chaque ligne bascule avec le lot qui construit son objet, et la colonne « Porté par » ci-dessous dit lequel.

| Code | P | État | Sous-fonctionnalité | Attendu |
|---|---|---|---|---|
| | | | **03 - 08 Gestion des messages** | |
| `BO-03-08-06` | P1 | 3 | Provider - message TPV | Configuration des messages transmis par les Providers devant être affichés en caisse Cette fonctionnalité permet de modifier le message transmis par le provider via le code du message de ce dernier. ---- Cas d'usage : configuration des messages receptionné en provenance du fournisseur illicado Lors… |
| `BO-03-08-07` | P1 | 3 | Provider - message Ticket | Configuration des messages transmis par les providers pour impression sur le ticket. Cette fonctionnalité permet de modifier le message transmis par le provider via le code du message de ce dernier. ----- Cas d'usage : traduction des messages d'impression ticket suite paiement avec une carte cadeau… |
| `BO-03-08-09` | P2 | 3 | Paramétrage message Article | Paramétrage d'un message en Back Office d'information rattaché à un article, pour affichage sur les Front-Offices dans la transaction à la saisie de l'article |
| | | | **03 - 13 Paramétrage des retours** | |
| `BO-03-13-12` | P1 | 3 | Code-barres Type Retour article | Configuration des code-barres Types retour : Configuration d'un code-barres devant être rattaché à un retour afin de permettre au Point De Vente de pouvoir effectuer un retour article sans appel superviseur. Le code-barres est constitué d'un préfixe et du code produit ainsi que le prix retour de ce… |
| | | | **10 - 02 Caisse** | |
| `BO-10-02-19` | P1 | 3 | Gestion règle d'arrondi - Nombre décimales | Les calculs HT effectués en encaissement doivent pouvoir être configurés pour être imprimés sur les documents Facture/Bon de livraison avec : - ligne article Prix Unitaire HT imprimé sur 2 ou 3 décimales - ligne article Prix Total HT imprimé sur 2 ou 3 décimales - ligne tva HT imprimé sur 2 ou 3… |
| `BO-10-02-20` | P1 | 3 | Impression second code | Règle permettant d'imprimer (oui ou non) le second code saisie rattaché au code ean13 (cas des articles produits démat) |
| `BO-10-02-33` | P1 | 3 | Fidélité - cagnottes | Paramètre permettant d'activer la demande des soldes des differentes cagnottes FID Portugal |
| | | | **10 - 03 Fidélité** | |
| `BO-10-03-05` | P1 | 3 | Fidélité - modèles de ticket | Paramètre permettant de définir le modèle de ticket pour : - une fidélité en ligne - une fidélité hors ligne - sans carte de fidélité en ligne - sans carte de fidélité hors ligne |
| `BO-10-03-06` | P1 | 1a | Fidélité - Moyen de Paiement | Paramètre permettant de définir le moyen de paiement rattaché au paiement Fidélité |
| `BO-10-03-11` | P1 | 3 | Fidélité - modèle ticket FID offline | Configuration du modèle ticket FID offline parmi la liste des modèles créés cf. modèle ticket fid offline |
| `BO-10-03-12` | P1 | 3 | Fidélité - modèle ticket FID online | Configuration du modèle de ticket FID En tant qu'administateur je veux pouvoir configurer le modèle de ticket Fidélité. |
| `BO-10-03-13` | P1 | 3 | Fidélité - modèle ticket non porteur - FID offline | Configuration du modèle de ticket FID pour les non porteurs lors d'une connexion FID offline |
| `BO-10-03-20` | P1 | 3 | Fidélité - Activation du regroupement des avantages par… | Paramètre permettant d'activer le regroupement des articles par catégorie permettant ainsi d'imprimer le ticket partie FID avec le regroupement et la priorité catégorie qui sera communiqué par la réponse FID |
| `BO-10-03-21` | P1 | 3 | Fidélité - Modèle ticket de regroupement des avantages par… | Paramètre permettant de définir le modèle de ticket FID devant être imprimé : avec le regroupement des articles par catégorie ou sans regroupement |
| | | | **10 - 07 Présentation** | |
| `BO-10-07-11` | P1 | 3 | Ticket - impression code-barres Article Inconnu | Activation/Désactivation de l'impression du code-barres sur le ticket " article inconnu " |
| `BO-10-07-13` | P1 | 3 | Ticket - EFT | Gestion des impressions des données monétique : - impression du ticket monétique incluant les logo/images EFT |
| `BO-10-07-15` | P1 | 3 | Ticket - Tri du Ticket | Configuration du mode du tri ticket: - Impression des lignes articles par nomenclature (famille/sous famille) - Activation de la mise en gras des valeurs montants - Activation des sous-totaux par nomenclature et mises en valeurs des montants |
| | | | **10 - 08 Backoffice** | |
| `BO-10-08-23` | P1 | 3 | Article - contrôle mise à jour | Paramètre permettant d'activer le contrôle de mise à jours article, cela permettant d'alimenter l'utilitaire " audit mise à jour référentiel" |
| `BO-10-08-26` | P1 | 3 | Client - archive | Configuration du nombre de jours après lequel les clients seront purgés du système après la dernière vente/activé |
| `BO-10-08-27` | P1 | 3 | Client en compte - Intégration | Paramètre permettant d'activer le rejet des intégrations client en compte si les données adresse sont manquantes |

## Lot 4 — Journal électronique

**46 exigences** (44 P1) — état actuel : 0 en `1`, 0 en `1a`, 46 en `3` · **2 à 3 sessions**

*Objectif.* Un écran de recherche multicritères sur le nœud consolidé, le détail d'un ticket, la séparation transactionnel/fonctionnel, l'export.

*Socle.* La donnée est déjà consolidée par l'outbox : tickets, lignes, paiements, remboursements, TechnicalEvent. Ce lot ne produit rien, il ouvre la lecture.

*Critère de fin.* Les 46 critères de recherche servis, et un export qui reproduit à l'identique ce que l'écran affiche.

| Code | P | État | Sous-fonctionnalité | Attendu |
|---|---|---|---|---|
| | | | **04 - 01 Journal électronique** | |
| `BO-04-01-01` | P1 | 3 | Consultation - Texte libre | Rechercher un ticket sur critères : - Un texte libre : permet de rechercher un texte présent dans un ticket, par exemple le libellé d'un article |
| `BO-04-01-02` | P1 | 3 | Consultation - N° caissière | Rechercher un ticket sur critères : - N° caissière : permet d'extraire les tickets encaissés par une ou plusieurs caissière(s) en particulier (en saisissant une plage de numéros de caissière) |
| `BO-04-01-03` | P1 | 3 | Consultation - N° TPV | Rechercher un ticket sur critères : - N° TPV : permet d'extraire les tickets encaissés sur une ou plusieurs caisses (TPV) en particulier (en saisissant une plage de numéros de TPV) |
| `BO-04-01-04` | P1 | 3 | Consultation - N° transaction | Rechercher un ticket sur critères : - N° transaction : permet d'extraire les tickets correspondant à un ou plusieurs numéros de transaction (en saisissant une plage de numéros de transaction) |
| `BO-04-01-05` | P1 | 3 | Consultation - Montant de la transaction | Rechercher un ticket sur critères : - Montant de la transaction (avec et sans réduction) : permet d'extraire les tickets dont le montant de la transaction correspond à une plage de montants et qui peuvent être inférieurs à zéro. Il faut prévoir d'extraire sur le montant remisé et le montant hors… |
| `BO-04-01-06` | P1 | 3 | Consultation - Mode de règlement | Rechercher un ticket sur critères : - Mode de règlement : permet d'extraire les tickets encaissés avec le mode de règlement sélectionné parmi une liste proposée |
| `BO-04-01-07` | P1 | 3 | Consultation - Mode de règlement + plage de montants | Rechercher un ticket sur critères : - Mode de règlement + plage de montants : permet d'extraire les tickets encaissés avec le mode de règlement sélectionné et dont le montant est compris dans la plage de montants saisies |
| `BO-04-01-08` | P1 | 3 | Consultation - N° d'autorisation | Rechercher un ticket sur critères : - N° d'autorisation : permet d'extraire les tickets encaissés avec un ou plusieurs numéro d'autorisation correspondant à la plage saisie |
| `BO-04-01-09` | P1 | 3 | Consultation - Heure de la transaction | Rechercher un ticket sur critères : - Heure de la transaction : permet d'extraire les tickets qui ont été encaissés pendant une plage horaire définie |
| `BO-04-01-10` | P1 | 3 | Consultation - Articles | Rechercher un ticket sur critères : - Articles : permet d'extraire les tickets encaissés correspondant à un N° PLU ou à une plage de numéros de PLU |
| `BO-04-01-11` | P1 | 3 | Consultation - Plage de familles | Rechercher un ticket sur critères : - N° de famille ou plage de familles : permet d'extraire les tickets encaissés sur des articles correspondant à une famille ou une liste de familles sélectionnées à partir d'une liste |
| `BO-04-01-12` | P1 | 3 | Consultation - Acompte | Rechercher un ticket sur critères : - Acomptes : permet d'extraire les tickets correspondant à des acomptes payés par les clients |
| `BO-04-01-13` | P1 | 3 | Consultation - Choix multiple | Rechercher un ticket sur critères (Choix multiple) : cette option permet d'extraire les tickets encaissés répondant à une combinaison de différents critères possibles : * Plage de numéros de TPV * Plage de numéros de caissière * Plage de numéros de transaction * Plage horaire * Plage de montants du… |
| `BO-04-01-14` | P1 | 3 | Consultation - Evénement | Rechercher un ticket sur critères : - Evénement : cette option permet d'extraire les tickets liés à un événement particulier. Dans ce cas, une liste doit s'afficher pour permettre de sélectionner un événement précis parmi les choix suivants : annulé, déduction, remise, retour, transaction négative,… |
| `BO-04-01-16` | P1 | 3 | Consultation- Annulation article | Rechercher un ticket sur critères : - Annulation article (plage de PLU & plage de montants) |
| `BO-04-01-19` | P1 | 3 | Consultation - Annulation de règlement | Rechercher un ticket sur critères : - Annulation de règlement (plage de montants & sélection d'un mode de règlement ou tous) |
| `BO-04-01-23` | P1 | 3 | Consultation - Remboursement article | Rechercher un ticket sur critères : - Remboursement article (plage de PLU et plage de montants) |
| `BO-04-01-25` | P1 | 3 | Consultation - Article à prix zéro | Rechercher un ticket sur critères : - Article à prix zéro |
| `BO-04-01-26` | P1 | 3 | Consultation - Réduction manuelle | Rechercher un ticket sur critères : - Réduction manuelle (plage de montants) |
| `BO-04-01-27` | P1 | 3 | Consultation - Départ en pause | Rechercher une action sur critères : - Entrer mode verrouillage (plage de N° caissières)-> fournit la liste des TPV passés en pause |
| `BO-04-01-28` | P1 | 3 | Consultation - Retour de pause | Rechercher une action sur critères : - Sortie du mode verrouillage (plage de N° caissières) -> fournit la liste des TPV sortis en pause |
| `BO-04-01-29` | P1 | 3 | Consultation - Modification mot de passe | Rechercher une action sur critères : - Modification mot de passe (plage de N° caissière) |
| `BO-04-01-30` | P1 | 3 | Consultation - Mot de passe incorrect | Rechercher une action sur critères : - Mot de passe incorrect (plage de N° caissière) |
| `BO-04-01-31` | P1 | 3 | Consultation - Ticket avec retour | Rechercher un ticket sur critères : - Ticket avec retour |
| `BO-04-01-32` | P1 | 3 | Consultation - Ticket avec bons de réduction | Rechercher un ticket sur critères : - Ticket avec bons de réduction |
| `BO-04-01-33` | P1 | 3 | Consultation - Seuil d'espèces dans le tiroir | Rechercher une action critères : - Seuil d'espèces dans le tiroir (plage de N° Caissière) -> comptage du tiroir réalisé par une caissière |
| `BO-04-01-34` | P1 | 3 | Consultation- Présence d'articles inconnus | Rechercher un ticket sur critères : - Présence d'articles inconnus (plage de PLU) -> permet d'afficher le code EAN du produit scanné qui n'est pas passé |
| `BO-04-01-35` | P1 | 3 | Consultation - Ticket avec dépense | Rechercher un ticket sur critères : - Ticket avec dépense : permet d'afficher les tickets saisis en caisse correspondant à une dépense faite par le point de vente (achat de timbres, pharmacie,...) |
| `BO-04-01-36` | P1 | 3 | Consultation - Ticket de prélèvement | Rechercher un ticket sur critères : - Ticket de prélèvement (plage de N° caissière) |
| `BO-04-01-37` | P1 | 3 | Consultation - Ticket d'apport | Rechercher un ticket sur critères : - Ticket d'apport (plage de N° caissières) |
| `BO-04-01-38` | P1 | 3 | Consultation - Vérification de prix | Rechercher une action sur critères : - Vérification de prix |
| `BO-04-01-39` | P1 | 3 | Consultation - Forçage superviseur | Rechercher une action sur critères : - Forçage superviseur (plage de N° superviseur) |
| `BO-04-01-40` | P1 | 3 | Consultation - Prélèvement espèces | Rechercher une action sur critères : - Prélèvement espèces |
| `BO-04-01-41` | P1 | 3 | Consultation - Tickets self-scanning | Rechercher une actionsur critères : - Tickets self-scanning |
| `BO-04-01-42` | P1 | 3 | Consultation - Tickets self-scanning contrôlés | Rechercher une action sur critères : - Tickets self-scanning contrôlés (décidés par l'hôtesse) |
| `BO-04-01-43` | P1 | 3 | Consultation - Tickets self-scanning vérifiés | Rechercher une action sur critères : - Tickets self-scanning vérifiés (= définis par le système) |
| `BO-04-01-44` | P1 | 3 | Consultation - Déclaration caissière | Rechercher un ticket sur critères : - Déclaration caissière (Résultat des comptage des espèces) |
| `BO-04-01-46` | P1 | 3 | Consultation - Total monétique | Rechercher un ticket sur critères : - Total monétique (toutes les transactions réalisées en carte bancaire) |
| `BO-04-01-47` | P1 | 3 | Consultation - Transactions monétiques en mode dégradé | Rechercher un ticket sur critères : - Transactions monétiques en mode dégradé (tickets correspondant à des ventes réalisées en mode dégradé |
| `BO-04-01-48` | P1 | 3 | Consultation - Transactions monétiques serveur secondaire | Rechercher un ticket sur critères : - Transactions monétiques serveur secondaire (tickets correspondant à des ventes réalisées en mode dégradé sur le serveur secondaire) -> destiné au service support |
| `BO-04-01-49` | P1 | 3 | Consultation - Transactions monétiques mode dégradé manuel | Rechercher un ticket sur critères : - Transactions monétiques mode dégradé manuel (tickets correspondant à des ventes réalisées en mode dégradé) |
| `BO-04-01-50` | P1 | 3 | Consultation - Affichage de la liste des tickets | Affichage du résultat de la recherhe en liste avec les éléments suivants : - N° TPV / N° transaction / N° caissière / Date / Heure / Montant du ticket / Nb d'articles / Mode école (O/N) / Autonome (O/N) Cette liste doit pouvoir être exportée sous Excel. A partir de cette liste, il doit être… |
| `BO-04-01-51` | P1 | 3 | Consultation - Possibilité de trier le résultat obtenu | Tri dans la liste du résultat de la recherche : - N° TPV / N° transaction / N° caissière / Date / Heure / Montant du ticket / Nb d'articles / Mode école (O/N) / Autonome (O/N) qui doit pouvoir être trié (ordre croissant ou décroissant) en fonction des critères suivants : Montant / Heure / Numéro de… |
| `BO-04-01-52` | P1 | 3 | IHM séparées | L'IHM dédiée au journal électronique devra être scindée en deux rubriques (ou onglets) distinctes : - Un onglet pour le Journal transactionnel, - Un onglet pour le Journal fonctionnel. |
| `BO-04-01-53` | P2 | 3 | Consultation - Factures/Bon de livraison imprimés en caisse | Réimpression des Factures à partir du backoffice encaissement (avec ajout mention duplicata) |
| `BO-04-01-55` | P2 | 3 | Consultation - ticket Vente taux de TVA | Recherche/consultation/export des tickets de vente par taux de TVA |

## Lot 2 — Profils et droits unitaires

**9 exigences** (6 P1) — état actuel : 0 en `1`, 2 en `1a`, 7 en `3` · **2 sessions**

*Objectif.* Le profil comme ensemble de (fonctionnalité × option CRUD) × échelon, en remplacement des quatre rôles figés.

*Socle.* L'écran utilisateurs d'imvaluation pour le gabarit liste/formulaire.

*Critère de fin.* Un profil créé dans l'IHM, sans redéploiement, autorise ou refuse une fonctionnalité précise sur un PDV précis.

| Code | P | État | Sous-fonctionnalité | Attendu |
|---|---|---|---|---|
| | | | **01 - 03 Gestion Profils** | |
| `BO-01-03-01` | P1 | 3 | Profils CRUD | Un « Administrateur » ou un Compte avec le niveau hiérachique nécessaire doit pouvoir créer /modifier /supprimer autant de Profils que nécessaire sans limite. |
| `BO-01-03-02` | P1 | 1a | Profil Affectation | Un administrateur ou un Compte avec le niveau hiérarchique nécessaire doit pouvoir affecter un profil à un utilisateur. |
| `BO-01-03-03` | P1 | 1a | Profils Affectations | Un Administrateur ou un Compte avec le niveau hiérarchique nécessaire doit pouvoir affecter plusieurs profils à un même utilisateur. -- *Au Niveau Central En tant que membre de l’équipe Études & Encaissement Je souhaite pouvoir affecter un/ Plusieurs utilisateurs à plusieurs Profils Afin de pouvoir… |
| | | | **01 - 04 Gestion fonctions** | |
| `BO-01-04-01` | P1 | 3 | Accès Fonctionnalités | Chaque Fonctionnalité doit être unitairement adressable à un profil. |
| `BO-01-04-02` | P1 | 3 | Accès Fonctionnalités Options | Les options d'utilisation par un profil pour chaque fonctionnalité sont la visualisation, la création, la modification et la supression. |
| `BO-01-04-03` | P3 | 3 | Accès Fonctionnalités Validation | Les options d'utilisation par un profil (la suppression par exemple) des fonctionnalités doivent pouvoir être paramétrées avec une validation. |
| `BO-01-04-05` | P3 | 3 | Accès Fonctionnalités Validation Tiers | Les options d'utilisation par un profil (la suppression par exemple) des fonctionnalités doivent pouvoir être paramétrées avec une validation par un autre profil. (Mode Superviseur en Front office par exemple) -- |
| `BO-01-04-06` | P3 | 3 | Accès Fonctionnalités Agregats | Les fonctionnalités cohérentes peuvent être agrégées dans des menus néanmoins il doit être possible d'administrer le menu dans sa totatlité ou chacune des fonctions de ce menu. |
| `BO-01-04-08` | P1 | 3 | Configuration Favoris | Les comptes utilisateurs doivent pouvoir dans la limite des fonctionnalités ou menus autorisés de leur profil masquer des fonctionnalités, des menus, des types de rapport etc. |

## Lot 5 — Référentiels administrés

**66 exigences** (47 P1) — état actuel : 0 en `1`, 21 en `1a`, 45 en `3` · **3 sessions**

*Objectif.* La fiche article complète, la recherche multicritères, les attributs administrés, les groupes articles et les touches caisse, le référentiel de TVA.

*Socle.* Les écrans liste/formulaire d'imvaluation, sa machinerie d'import CSV, le GraphQL des deux côtés.

*Critère de fin.* Une fiche article modifiée dans le back-office change le comportement de la caisse au tirage suivant, y compris sur un attribut nouvellement déclaré.

> **Découpé en cinq (01/09/2026) — un lot, une session.** Soixante-six lignes
> dans un lot prévu pour une session, c'est ce qui a fait caler le lot 3 et le
> lot C5 : l'agent rend un demi-travail, ne commite pas, et la campagne
> s'arrête. Le découpage suit la nature du travail, pas le nombre de lignes.
> La partition est vérifiée : 24 + 9 + 21 + 5 + 7 = 66, aucun doublon, aucune
> orpheline. Chaque sous-lot cote SES lignes et elles seules.
>
> - **5A — Les attributs de la fiche article** (24 lignes, `BO-02-03-01` à
>   `-27`). Le mécanisme d'attribut déclaré, puis les attributs eux-mêmes.
>   Attention : la moitié d'entre eux sont des **comportements de caisse** —
>   vente interdite, remise interdite, contrôle d'âge, prix à saisir, quantité
>   décimale, article pesé, TVA exonérée. Ce lot touche donc la surface de
>   vente : invariance des totaux à prouver, et un attribut qui ne change rien
>   en caisse ne vaut pas `1`.
> - **5B — Les écrans article** (9 lignes : `-28` à `-35`, `-45`). Liste,
>   recherche multicritères, critères de sélection, administration des
>   attributs et des données, historique des prix. Lecture et administration,
>   aucun comportement de vente.
> - **5C — L'arborescence des groupes articles** (8 lignes : `-01` à `-05`,
>   `-16`, `-17`, `-18`). Création, modification, duplication, ajout d'articles
>   à l'unité et en masse, désactivation d'un article dans un groupe et par
>   exception. Administration pure, aucun comportement de vente.
> - **5F — Les touches caisse** (8 lignes : `-06`, `-07`, `-08`, `-10`, `-11`,
>   `-13`, `-15`, `-24`). Nombre de touches, groupes en affichage permanent,
>   tailles, ordres alphabétique / personnalisé / par volume, images et leur
>   import redimensionné. **Ce lot touche l'écran de vente** : ce qui est
>   administré ici doit changer ce que la caissière voit, sinon la ligne ne
>   vaut pas `1` — la règle que 5A a appliquée et que 5B a manquée.
> - **5G — Rattachements et traductions** (5 lignes : `-19` à `-23`). À NE PAS
>   lancer en l'état : les deux lignes de traduction supposent une i18n qui
>   n'existe nulle part dans l'application (cf. `BO-01-01-04`, coté `3` pour
>   cette raison), et les trois rattachements — îlots, caisses, types de point
>   de contact — supposent le parc du lot 10. Ce lot attend son socle ; le
>   lancer maintenant produirait cinq lignes d'arbitrage et zéro code.
>
>   *Cotation rectifiée le 02/09/2026 :* `BO-02-03-45` (code article sur
>   l'écran caisse), livré par 5B, reste en `1a` et non `1`. L'attribut est
>   administré et distribué, mais aucun écran de vente ne le lit — c'est
>   exactement le cas que 5A a refusé de coter `1` pour quatre autres
>   attributs. Un paramètre qui ne change rien en caisse ne vaut pas `1`.
> - **5D — Nomenclature et TVA** (5 lignes, `BO-02-01-*` et `BO-02-02-*`).
>   Contient la mention légale par taux du Portugal (`BO-02-02-02`), qui
>   s'imprime sur les documents fiscaux : à traiter avec la prudence due au
>   chemin fiscal.
> - **5E — Spécialités article** (7 lignes : `-37`, `-38`, `-39`, `-41` à
>   `-44`). EEG et son export, tare et contenants, TVA vente de prestation,
>   n° de lot pour le retrait-rappel. Sujets indépendants les uns des autres :
>   à prendre en dernier, ou à ventiler si l'un d'eux grossit.
>
> L'ordre recommandé est 5A, 5B, 5C, 5D, 5E : les attributs commandent les
> écrans, et les groupes articles s'appuient sur la fiche.

| Code | P | État | Sous-fonctionnalité | Attendu |
|---|---|---|---|---|
| | | | **02 - 01 Référentiel nomenclature** | |
| `BO-02-01-01` | P1 | 1a | Gestion des nomenclatures | Intégration Nomenclature en provenance de la gestion commerciale. Les Nomenclatures sont modifiables dans le backoffice Gestion Commerciale par le Point De Vente selon le pays/enseigne. il peut donc y avoir pour un même article, 2 nomenclatures différentes pour 2 Points De Vente. |
| `BO-02-01-08` | P3 | 3 | Affichage Nomenclature | On doit pouvoir visualiser la totalité de nomenclature dans un affichage spécifique |
| | | | **02 - 02 Référentiel TVA** | |
| `BO-02-02-01` | P2 | 3 | Intégration des TVA propres à chaque pays | Intégration des TVA/Codes TVA par pays. Gestion TVA France, Belgique, Portugal avec Exonération de TVA Le système doit permettre de définir/d'intégrer plusieurs taux ayant le même pourcentage. |
| `BO-02-02-02` | P1 | 3 | Motif associé TVA | Obligation réglementaire Portugal : Association d'un libellé/motif/mention légal(e) à chaque TVA devant être imprimé sur tous les documents imprimés en caisse (FS/FT/NC/RC). Fonctionnalité mise à disposition du Point de vente |
| `BO-02-02-03` | P3 | 3 | Affichage TVA | On doit pouvoir visualiser la totalité des TVA dans un affichage spécifique. |
| | | | **02 - 03 Référentiel articles** | |
| `BO-02-03-01` | P1 | 1a | Intégration article - libellé commercial | Intégration initiale et mises à jour des articles en provenance de la Gestion Commerciale - libellé commercial |
| `BO-02-03-02` | P1 | 3 | Intégration article - libellé encaissement | intégration initiale et mises à jour des articles en provenance de la Gestion Commerciale - libellé encaissement |
| `BO-02-03-03` | P1 | 1a | Intégration Article : - Code EAN | Intégration initiale et mise à jour d'un nombre non limité de codes de vente articles A minima, lecture en caisse des EAN13, EAN8, PPV,... |
| `BO-02-03-04` | P2 | 1a | Intégration Article : - Code Interne | Intégration du code interne de l'article. Ce code interne doit pouvoir permettre un encaissement via le scan ou la saisie de ce dernier en plus du code EAN. |
| `BO-02-03-05` | P1 | 3 | Intégration article - appel prix | intégration initiale et mises à jour des modes de passage en caisse des articles en provenance de la Gestion Commerciale : - appel prix |
| `BO-02-03-06` | P1 | 1a | Intégration article - article éligible Titre Restaurant | Intégration initiale et mises à jour articles en provenance de la Gestion Commerciale - article éligible Titre Restaurant |
| `BO-02-03-07` | P1 | 3 | Intégration article - article vente interdite | Intégration initiale et mises à jour des articles en provenance de la Gestion Commerciale - article vente interdite |
| `BO-02-03-08` | P1 | 1a | Intégration article - article contrôle d'âge | Option de configration des articles : - article soumis à un contrôle d'âge |
| `BO-02-03-09` | P1 | 3 | Intégration article - remise interdite | Intégration initiale et mises à jour des articles en provenance de la Gestion Commerciale: - remise interdite |
| `BO-02-03-10` | P1 | 3 | Intégration article - article avec Message | Intégration initiale et mises à jour de articles en provenance de la Gestion Commerciale avec prise en compte du message associé à ce dernier - article avec Message |
| `BO-02-03-11` | P1 | 3 | Intégration article - article retrait rappel | Intégration initiale et mises à jour des articles en provenance de la Gestion Commerciale : - article en retrait rappel |
| `BO-02-03-12` | P1 | 1a | Configuration Article: code article Ticket | En tant qu' adhérent ou administrateur Je veux pouvoir activer ou désactiver l'impression du code article sur le ticket de caisse, soit sur un article individuel via sa fiche article, soit en masse sur l'ensemble des articles (ou une sélection d'articles) Afin de faciliter l'identification des… |
| `BO-02-03-14` | P1 | 1a | Intégration article : - nomenclature | Intégration initiale et mises à jour de la nomenclature des articles en provenance de la Gestion Commerciale : - nomenclature: -Activité Rayon Famille et sous-famille Intermaché France :Activité (2) / Rayaon (2) /Famille(4)/ SsFamille(4) Bricomarché FR SAP : Rayon(2) / Familles(3) Groupe… |
| `BO-02-03-15` | P1 | 3 | Intégration article : - article lié | Intégration initiale et mises à jour articles en provenance de la Gestion Commerciale : - article lié |
| `BO-02-03-16` | P2 | 3 | Intégration article : - article complémentaire | Intégraton initiale et mises à jour des articles en provenance de la Gestion Commerciale : - article complémentaire :liste d'article affichée à la vente d'un produit |
| `BO-02-03-17` | P1 | 1a | Intégration article : - unité de mesure | Intégration initiale et mises à jour du code de l'unité de mesure des articles en provenance de la Gestion Commerciale : - Unité de mesure L'unité de mesure sera prise en compte lors des saisies caisse d'un article quantité décimale Il est demandé 99 unités de mesure possibles |
| `BO-02-03-18` | P1 | 3 | Intégration article - attributs | Intégration initiale et mises à jour des attributs des articles en provenance de la Gestion Commerciale : - attributs. Il est demandé minimum 99 attributs possibles |
| `BO-02-03-21` | P1 | 3 | Intégration article - prix à saisir | intégration initiale et mises à jour des modes de passage en caisse des articles en provenance de la Gestion Commerciale : - Prix à saisir. |
| `BO-02-03-22` | P1 | 3 | Intégration article - quantité à saisir | Intégration initiale et mises à jour des articles en provenance de la Gestion Commerciale : - quantité à saisir |
| `BO-02-03-23` | P1 | 1a | Intégration article - quantité décimale à saisir | Intégration initiale et mises à jour des modes de passage en caisse des articles en provenance de la Gestion Commerciale : - quantité décimale à saisir |
| `BO-02-03-24` | P1 | 1a | Intégration article - article pesé | Intégration initiale et mises à jour des modes de passage en caisse des articles en provenance de la Gestion Commerciale : - article pesé |
| `BO-02-03-25` | P1 | 3 | Gestion des articles - article encombrant | Intégration initiale et mises à jour des attributs des articles en provenance de la Gestion Commerciale : - article encombrant -- |
| `BO-02-03-26` | P2 | 3 | Intégration article - TVA exonérée | Intégration initiale et mises à jour des articles exonérés de TVA en provenance de la Gestion Commerciale : - TVA Exonérée |
| `BO-02-03-27` | P2 | 1a | Intégration des articles : - TVA exonéré | Intégration initiale et mises à jour des articles exonérés de TVA en provenance de la Gestion Commerciale , l'objectif est d'intégrer les articles avec leur TVA, y compris exonéré, depuis la Gestion Commercial |
| `BO-02-03-28` | P1 | 3 | Affichage des données basiques des articles | Dans la fiche Article, l'affichage de la totalité des données basiques des articles est requis -- |
| `BO-02-03-29` | P1 | 3 | Affichage des différents attributs des articles | Dans la fiche Article,au dela de toutes les données basiques l'affichage des différents attributs des articles est requis |
| `BO-02-03-30` | P1 | 3 | Affichage des articles - Recherche article | Disposer d'une fenêtre de sélection plus détaillée permettant de retrouver un article ou une liste d'article en se basant sur plusieurs critères. |
| `BO-02-03-31` | P1 | 3 | Critères de sélection articles | Configuration d'une liste d'articles par regroupement réutilisable dans les rapports / exports pour restriction du périmètre. |
| `BO-02-03-32` | P1 | 3 | Administration des Attributs article | Configurations des Attributs articles devant ensuite être rattachés aux fiches articles. |
| `BO-02-03-33` | P1 | 1a | Administration des données Article | Modification /suppresion des données basiques de l'article ou de l'article lui même. |
| `BO-02-03-34` | P1 | 3 | Affichage des offres de l'article | Dans la fiche Article, l'affichage de la totalité des offres en cours et passées est requis |
| `BO-02-03-35` | P1 | 1a | Affichage Historique Prix de vente | Dans la fiche Article, l'affichage de la totalité de l'historique des prix de ventes de l'article est requis |
| `BO-02-03-37` | P3 | 3 | Affichage Information Prix de vente EEG | Dans la fiche Article, l'affichage du prix de vente à l'instant T de l'étiquette EEG est requis via un appel API étiquette électronique. |
| `BO-02-03-38` | P3 | 3 | Export : EEG (1 à n EEG) | Envoi des prix de ventes aux étiquettes électronique Il existe plusieurs fournisseurs d'étiquettes électronique, un point de vente pouvant avoir plusieurs fournisseurs. (Flux soumis à activation) |
| `BO-02-03-39` | P1 | 3 | Gestion de la tare / des contenants | Configuration/Création des tares des différents contenants utilisés pour la pesée en caisse. Ces différents contenants doivent pouvoir être utilisés lors du passage en caisse pour déduire le poids du contenant de la pesée. |
| `BO-02-03-41` | P2 | 3 | TVA Vente prestation - rapport de TVA | Attribut TVA Vente prestation déclencheur des fonctionnalités ci-dessous Exclusion du rapport de TVA des montants des articles vendus pour un tiers |
| `BO-02-03-42` | P2 | 3 | TVA Vente prestation - ticket | Attribut TVA Vente prestation déclencheur des fonctionnalités ci-dessous Le ticket de vente des articles vendus pour un tiers affiche le bon taux de tva des produits |
| `BO-02-03-43` | P2 | 3 | TVA Vente prestation - CA | Attribut TVA Vente prestation déclencheur des fonctionnalités ci-dessous Exclusion du chiffre d'affaire du livre de caisse des montants de tva des articles vendus pour un tiers |
| `BO-02-03-44` | P2 | 3 | N°LOT Retrait Rappel Article | En tant que Système (intégration avec [source externe) Je veux intégrer et maintenir à jour des listes de numéros de lots faisant l'objet d'un retrait ou d'un rappel article Afin de permettre la détection automatique, au niveau caisse, des articles concernés par un retrait/rappel lors de leur… |
| `BO-02-03-45` | P1 | 1a | Configuration Article: Code article sur l'écran caisse | En tant qu' adhérent ou administrateur Je veux pouvoir activer ou désactiver l'affichage du code article sur l'écran caisse, soit sur un article individuel via sa fiche article, soit en masse sur l'ensemble des articles (ou une sélection d'articles) Afin de faciliter l'identification des produits… |
| | | | **03 - 01 Gestion des groupes articles** | |
| `BO-03-01-01` | P1 | 1a | Création des groupes articles | Création d'une arborescence de groupes articles pouvant aller du Niveau 0 au niveau 4 minimum |
| `BO-03-01-02` | P1 | 1a | Gestion des groupes articles - modification/suppression | Modification/suppression des groupes articles. En tant qu'administrateur de la solution/ adhérent.. personne habilitée, Je souhaite ajouter un groupe article au groupe déjà existante. L'ajout du groupe article est applicable au niveau 1 et aux niveau suivant. exemple : Ajout du groupe BOULANGERIE… |
| `BO-03-01-03` | P2 | 3 | Gestion des groupes articles - duplication | Les groupes articles doivent pouvoir être dupliqués pour en créer un nouveau |
| `BO-03-01-04` | P1 | 1a | Gestion des groupes articles - Ajout Article | En tant qu’administrateur de la solution/Adhérent /tout autre profil habilité, je souhaite ajouter un article à un groupe ou sous-groupe d’articles via, à minima, les critères suivants : - code interne - code ean - famille - plage d'EAN / code interne - libellé |
| `BO-03-01-05` | P1 | 3 | Gestion des groupes articles - Niveau 0 ajout Article | En tant qu’administrateur de la solution/Adhérent /tout autre profil habilité, Je souhaite ajouter un article au niveau 0 afin d'avoir l'article immediatement en touche caisse sans passer une touche groupe article. --- Cas d'usage : ajout de l'article fréquemment présent comme le produit "SAC" dans… |
| `BO-03-01-06` | P1 | 3 | Gestion de l'affichage en caisse | Il devra être possible de gérer le nombre de touches groupe article en caisse par écran / page en fonction de la taille de l'écran -- En tant qu’administrateur de la solution/tout autre profil habilité, Je souhaite Paramétrer/gérer le nombre de boutons des groupes d’articles en caisse par page, en… |
| `BO-03-01-07` | P1 | 3 | Affichage permanent en caisse | Il devra être possible de garder certains groupes articles affichés en permanence en caisse en dépit de la navigation -- En tant qu’administrateur de la solution/tout autre profil habilité, Je souhaite paramétrer un nombre limité de groupes d’articles (maximum = 4) à épingler, de sorte que même si… |
| `BO-03-01-08` | P1 | 3 | Taille des touches | Il devra être possible de définir des touches de tailles différents pour les groupes articles |
| `BO-03-01-10` | P3 | 3 | Ordre d'affichage en caisse: mode alphabétique | Gestion de l'ordre d'affichage des articles en mode alphabétique, configuration applicable par défaut si aucun mode de tri n'est appliqué en caisse |
| `BO-03-01-11` | P1 | 3 | Ordre d'affichage en caisse: mode personnalisé | Gestion de l'ordre d'affichage des articles en mode personnalisé |
| `BO-03-01-13` | P3 | 3 | Ordre d'affichage en caisse: en mode volume de vente | Gestion de l'ordre d'affichage des articles dans les groupes articles triés en volume de vente. |
| `BO-03-01-15` | P1 | 1a | Paramétrage des images associées à des articles (PLU) | Rattachement d'une image à un article afin que ce dernier soit visible dans les groupes articles en Backoffice et en tout point d'encaissement tel qu'à minima: - CAISSE - Caisse Libre-Service - MOBILITE (Qbusting, Self Scanning, …) L'ajout d'une image sur un article du groupe article est dynamique… |
| `BO-03-01-16` | P1 | 1a | Gestion des groupes articles - intégration en masse | Ajout articles dans le groupe article en masse via importation d' une liste d'article pouvant être un fichier Excel. L'ajout des articles dans un groupe article est dynamique en caisse, ces derniers sont automatiquement disponibles. |
| `BO-03-01-17` | P1 | 1a | Gestion des groupes articles - désactivation article | Désactivation d'un article afin de ne plus apparaître dans la liste des groupes articles. Dés lors que ce dernier est réactivé il doit pouvoir être utilisé en caisse sans nécessité de renvoyer la donnée ou l'image. |
| `BO-03-01-18` | P2 | 3 | Gestion des groupes articles - désactivation article par… | Par exception, un article doit pouvoir être désactivé d'un groupe sur seulement un ou plusieurs îlots ou une ou plusieurs caisses |
| `BO-03-01-19` | P1 | 3 | Groupes articles - Traduction | Gestion des traductions de l'arborescence des groupes articles afin que ces derniers soient affichés en fonction de la langue sélectionnée par l'hôte(sse) de caisse / Client (si Caisse Libre-Service) |
| `BO-03-01-20` | P3 | 3 | Groupes articles - Traduction des articles | Gestion des traductions des libellés articles rattachés aux groupes articles afin que ces derniers soient affichés en fonction de la langue sélectionnée par l'hôte(sse) de caisse / Client (si SCO/Caisse Libre-Service) |
| `BO-03-01-21` | P1 | 3 | Groupes articles - Rattachement des groupes articles aux… | Rattachement des groupes articles aux îlots de caisse. Un groupe article doit pouvoir être rattaché à 1 ou N îlots de caisse. |
| `BO-03-01-22` | P1 | 3 | Groupes articles - Rattachement aux caisses | Les groupes articles doivent pouvoir être rattachés à une ou plusieurs caisses |
| `BO-03-01-23` | P2 | 3 | Groupes articles - Rattachement aux types de points de… | Les groupes articles doivent pouvoir être rattachés à un ou plusieurs types de points de contact d'encaissement |
| `BO-03-01-24` | P1 | 3 | Import images AVEC le redimensionnement attendu : -taille -… | Pouvoir importer en masse des images dans les groupes articles avec gestion du format de l'image |

## Lot 6 — Supervision et déploiement

**71 exigences** (58 P1) — état actuel : 0 en `1`, 25 en `1a`, 46 en `3` · **3 sessions**

*Objectif.* La supervision configurable fonction par fonction, la validation et le refus depuis le back-office avec effet temps réel, les consoles d'intégration et de déploiement.

*Socle.* Le dashboard et les appels superviseur acquittables ; RefState et les versions par domaine.

*Critère de fin.* Un refus prononcé sur le back-office fait apparaître le message de refus sur la caisse qui attendait, sans rechargement manuel.

> **Découpé en six (02/09/2026) — un lot, une session.** Soixante et onze
> lignes, six sous-fonctionnalités et quatre produits différents derrière une
> seule étiquette. Partition vérifiée : 10 + 15 + 17 + 10 + 14 + 5 = 71, aucun
> doublon, aucune orpheline. Le critère de fin ci-dessus appartient au seul
> **6A** ; les autres ont le leur, écrit dans leur fiche.
>
> - **6A — Les demandes émises par les caisses** (10 lignes, `BO-04-02-*`).
>   Configuration de la supervision fonction par fonction, validation et refus
>   depuis le back-office, codes superviseurs et leur impression. C'est le lot
>   qui porte le critère de fin du lot 6 : **un refus doit atteindre la caisse
>   qui attend, sans rechargement**. Le tirage référentiel tourne toutes les
>   cinq minutes : il ne peut pas servir ce chemin. Le socle est le sondage
>   déjà en place sur le tableau de bord pour les appels superviseur — à
>   étendre, pas à réinventer. Ce lot touche l'écran de vente.
> - **6B — Le tableau de bord et le monitoring** (15 lignes, `BO-04-03-*`).
>   IHM et responsive, monitoring des caisses y compris hors ligne, surveillance
>   caissier, alertes, justifications, fermeture à distance, traçabilité.
>   Attention : les trois lignes de *justification* (`-10` à `-12`) sont une
>   fonction de caisse à part entière — un motif imposé au geste — et non un
>   écran ; si elles enflent, elles sortent du lot.
> - **6C — Les consoles d'intégration** (17 lignes, `BO-08-01-*` et
>   `BO-08-02-*`). L'état de chaque référentiel à chaque étage : central, PDV,
>   caisse. C'est exactement `RefState` et les empreintes par domaine, et les
>   trois étages existent depuis la seconde session du lot 1. Le lot le mieux
>   préparé de la série ; aucun comportement de vente.
> - **6D — Le déploiement** (10 lignes, `BO-08-03-*`). Prérequis, définition
>   des cibles et de la période, programmation vers la brique centrale, console,
>   mise à jour automatique des caisses, phase pilote. S'appuie sur la **date
>   d'effet** livrée par le lot 1 ; mais `-03` (définition des points de contact)
>   suppose le parc du lot 10, et la contrainte d'unicité `(niveau, code, clé)`
>   empêche encore une valeur datée de coexister avec la courante — l'arbitrage
>   remonté par le lot 1 tombe ici.
> - **6E — Les reprises sur erreur** (14 lignes, `BO-08-04-01` à `-14`).
>   Relance automatique et manuelle par référentiel, retour arrière. À vérifier
>   avant de lancer : **une intégration en erreur laisse-t-elle une trace
>   reprenable ?** Si l'échec n'est pas modélisé, ce lot commence par le
>   modéliser, et quatorze lignes reposent sur ce préalable.
> - **6F — Parc et prise de contrôle** (5 lignes, `BO-08-04-15` à `-20`).
>   Console d'activité, supervision caisse, prise de contrôle à distance,
>   ticket, gestion du parc logiciel et matériel. **À ne pas lancer** : la prise
>   de contrôle à distance et la gestion de parc sont des produits
>   d'infrastructure, pas des écrans de back-office. Ces cinq lignes appellent
>   une décision de périmètre commercial avant une session de développement.
>
> Ordre recommandé : **6C, 6A, 6B, 6D**, puis 6E si le préalable est levé. 6C
> d'abord parce qu'il est prêt et sans risque ; 6A ensuite parce qu'il porte le
> critère de fin.

| Code | P | État | Sous-fonctionnalité | Attendu |
|---|---|---|---|---|
| | | | **04 - 02 Supervision caisse** | |
| `BO-04-02-01` | P1 | 1a | Configuration de la supervision sur toutes les fonctions de… | Mise en supervision de toutes fonctions de caisse. L’ensemble des fonctionnalités utilisées en caisse doivent pouvoir être configurées afin de générer un appel superviseur en caisse, avec un ou plusieurs profils associés. Exemple : - configuration des touches annulation article, forçage prix,… |
| `BO-04-02-02` | P1 | 1a | Gestion des demandes émises par les caisses :… | Affichage et choix de validation/acquittement sur le BackOffice des demandes émises par les caisses. L'autorisation superviseur de la fonction ou acquittement de la demande de service s'applique en temps réel sur la caisse. Selon le paramétrage sur le BackOffice, la demande reste ensuite affichée… |
| `BO-04-02-03` | P1 | 3 | Gestion des demandes émises par les caisses : validation… | Si la demande a été validée par un superviseur en caisse, elle peut selon le paramétrage rester affichée dans la liste sur le BackOffice pour le suivi et la surveillance mais elle est clairement automatiquement marquée comme 'déjà traitée en caisse' afin d'indiquer qu'il n'y a pas/plus d'action… |
| `BO-04-02-04` | P1 | 3 | Gestion des demandes émises par les caisses : validation à… | Affichage et choix 'à traiter en caisse' sur le BackOffice des demandes émises par les caisses. Selon le paramétrage sur le BackOffice, la demande reste ensuite affichée dans la liste sur le BackOffice mais marquée clairement comme 'à traiter en caisse' ou bien disparait de la liste des demandes. |
| `BO-04-02-05` | P1 | 3 | Gestion des demandes émises par les caisses : refus | Affichage et choix de refus/rejet sur le BackOffice des demandes émises par les caisses. Auquel cas, un message d'erreur de refus d'autorisation superviseur est affiché en caisse, la fonction n'est pas autorisée en caisse. Selon le paramétrage sur le BackOffice, la demande reste ensuite affichée… |
| `BO-04-02-06` | P1 | 3 | Configuration des demandes caisse "Article Inconnu" | Configuration de l'affichage des alertes de la remontée du Point de contact Encaissement vers le Back Office lors des scan des Articles Inconnus |
| `BO-04-02-07` | P1 | 1a | Configuration des demandes de service émises par les caisses | Configuration des types de demandes de service prédéfinis envoyés des caisses vers le BackOffice. Configuration du libellé de la demande qui sera affiché dynamiquement sur la touche correspondante en caisse et du niveau de priorité/sévérité de la demande qui sera affiché sur le BackOffice à sa… |
| `BO-04-02-09` | P1 | 1a | Création des codes superviseurs | Cette fonctionnalité permet de créer les cartes des superviseurs. |
| `BO-04-02-11` | P1 | 1a | Impression des codes superviseurs | Cette fonctionnalité permet d'imprimer les cartes des superviseurs. A partir du Backoffice |
| `BO-04-02-12` | P1 | 1a | Gestion des demandes émises par les caisses : affichage… | Lorsqu'une fonction en caisse demande une autorisation superviseur ou qu'une demande de service est effectuée en caisse, la demande est immédiatement envoyée sur le BackOffice. Une alerte est affichée immédiatement et dynamiquement sur le BackOffice indépendamment du menu dans lequel se trouve… |
| | | | **04 - 03 Contrôle** | |
| `BO-04-03-01` | P1 | 1a | IHM | Dans la totalité des Interfaces du Back Office, en fonction du profil, les indicateurs de controle de l'activité en temps réel doivent être affichés. - CA jour en € - Détail CA en € au rayon ou à la famille en € (paramétrage à prévoir sur la totalité de la nomenclature) - Panier moyen en quantité… |
| `BO-04-03-02` | P1 | 1a | IHM Responsive | Le contrôle s'effectuant sur la base de tableaux de bord, de visualisation d'alerte et d'opérations simple ou complexe de supervision les IHM doivent d'une manière générale être responsives pour pouvoir être utilisées sur des devices de type Smartphone ou tablette |
| `BO-04-03-03` | P1 | 1a | Tableau de Bord | Tableaux de bord visuels mettant en évidence les différents indicateurs du point de vente ainsi que des alertes. Les tableaux de bord doivent être totalement paramétrables avec l'ensemble des données disponibles dans le système d'encaissement. Ils doivent être modulables jusqu'à l'échelon le plus… |
| `BO-04-03-04` | P2 | 3 | Visualisation des accès utilisateurs | Suivi des connexions sur le Backoffice encaissement. Identification des menus du backoffice à surveiller. |
| `BO-04-03-05` | P1 | 3 | Monitoring caisse | Détection des caisses non connectées au réseau (fait partie de la check-list de fermeture). |
| `BO-04-03-06` | P1 | 3 | Monitoring caisse offline | Affichage d'alertes en cas de caisse déconnectée du réseau lors du lancement de la fin de période |
| `BO-04-03-07` | P1 | 1a | Surveillance caissier | En tant qu'adhérent, gérant et ou superviseur de caisse Je veux disposer d'un menu de supervision affichant en temps réel les transactions effectuées en caisse (ventes, annulations, ouvertures de tiroir, remises...) Afin de suivre l'activité du magasin en direct / détecter les anomalies / assurer… |
| `BO-04-03-08` | P1 | 3 | Gestion des alertes de caisse Périmêtre | Alertes associées à des fonctionnalités (annulation, retour, caisse offline,...), des articles, des moyens de paiement, des code-barres,.... Toutes les fonctions doivent pouvoir être adressées |
| `BO-04-03-09` | P1 | 3 | Gestion des alertes en caisse Type | Les alertes doivent être configurables avec les éléments ci-dessous: #Type de contrôle : - Demande Oui /Non (affichage en caisse) - Demande validation superviseur - Alerte information (sur le back office) - Bloquer la fonction # afficher une notification à l'hôte(sse) de caisse # Nombre de fois… |
| `BO-04-03-10` | P1 | 3 | Gestion des justifications en caisse Fonctions | Configuration des listes de justification (motifs) liés aux fonctions de caisse La liste des justifications affichée en caisse doit être propre au contexte. Par exemple, il n'est pas requis d'afficher la raison "ajout de monnaie" lors de la sélection de la fonction retour mais uniquement lors de la… |
| `BO-04-03-11` | P2 | 3 | Gestion des justifications en caisse Justification | Configuration des justifications : Motifs justifiant de l'utilisation de la fonction (libellé et code). |
| `BO-04-03-12` | P2 | 3 | Gestion des justification en caisse Liste de justification | Constitution d'une liste de justifications, regroupées sous un même libellé, pouvant être associée à une ou plusieurs fonctions afin de définir les justifications disponibles selon les opérations réalisées en caisse. |
| `BO-04-03-13` | P2 | 3 | Fermeture caisse à distance | En tant que adhérent, gérant et ou superviseur de caisse Je veux pouvoir forcer la clôture d'une session de caisse restée ouverte, depuis le backoffice. Afin de garantir la clôture quotidienne des caisses même en cas d'oubli. |
| `BO-04-03-14` | P2 | 1a | Traçabilité | La totalité des utilisations des fonctionnalités de contrôle doivent être accessibles à minima dans le journal électronique. |
| `BO-04-03-15` | P2 | 3 | Tableau de bord | La totalité des utilisations des fonctionnalités de contrôle doivent être adressables dans les tableaux de bord. |
| | | | **08 - 01 Supervision Intégration** | |
| `BO-08-01-01` | P1 | 1a | Référentiel PDV | Contrôle d'intégration des mises à jour de Points De vente importées sur la brique centrale |
| `BO-08-01-02` | P1 | 1a | Référentiel articles | Contrôle d'intégration des mises à jour articles importées sur la brique centrale |
| `BO-08-01-03` | P1 | 1a | Référentiel nomenclature | Contrôle d'intégration des mises à jour nomenclature importées sur la brique centrale |
| `BO-08-01-04` | P1 | 3 | Référentiel client en compte | Contrôle d'intégration des mises à jour client en compte importées sur la brique centrale |
| `BO-08-01-07` | P1 | 1a | Référentiel articles PDV | Contrôle d'intégration des mises à jour articles sur le Point de Vente : sur le edge et les caisses |
| `BO-08-01-08` | P1 | 1a | Référentiel nomenclature PDV | Contrôle d'intégration des mises à jour nomenclature sur le Point de Vente (edge) |
| `BO-08-01-09` | P1 | 3 | Référentiel client en compte PDV | Contrôle d'intégration des mises à jour client en compte sur le Point de Vente (edge) |
| `BO-08-01-10` | P3 | 3 | Référentiel TVA PDV | Contrôle d'intégration des mises à jour TVA sur le Point de Vente (edge) |
| `BO-08-01-12` | P1 | 1a | Référentiel articles Caisse | Contrôle d'intégration des mises à jour articles sur les Points de Contact d'Encaissement |
| `BO-08-01-13` | P1 | 1a | Référentiel nomenclature Caisse | Contrôle d'intégration des mises à jour nomenclature sur les Points de Contact d'Encaissement |
| `BO-08-01-14` | P1 | 3 | Référentiel client en compte Caisse | Contrôle d'intégration des mises à jour client en compte sur les Points de Contact d'Encaissement |
| `BO-08-01-16` | P1 | 1a | Référentiel Offres Commerciales Caisse | Contrôle d'intégration des mises à jour des Offres Commerciales sur les Points de Contact d'Encaissement |
| `BO-08-01-17` | P1 | 3 | Intégration - Console | IHM de Visualisation avec liste et graphique : - Vision globale de l'organsiation - Vision sur chaque échelon de l'organisation - Vision ok et/ou ko - Export liste XLS, CSV |
| `BO-08-01-18` | P1 | 3 | Intégration attributs Articles | Contrôle d'intégration des mise à jours d'attributs des articles sur les Points de Contact d'Encaissement en Point de Vente |
| `BO-08-01-19` | P3 | 3 | Motifs associés TVA | Contrôle d'intégration des Motifs associés aux TVA Statut de réception de la donnée sur chaque Point De Vente en centrale, disponible en consultation tableau de bord et en API. |
| | | | **08 - 02 Supervision Caisse** | |
| `BO-08-02-01` | P1 | 3 | Intégration attributs Articles | Cette fonctionnalité permet d'afficher le résultat d'intégration des mise à jours d'attributs des articles sur les caisses Point de Vente |
| `BO-08-02-02` | P2 | 1a | Contrôle des fonctions utilisées en caisse | Enregistrement de chaque utilisation de fonction caisse et backoffice pour statistiques et analyses |
| | | | **08 - 03 Déploiement** | |
| `BO-08-03-01` | P1 | 3 | Déploiement - Prérequis | Contrôle / affichage état des Prérequis avant mise à jour en Point de Vente, à minima: - niveau de version - caisse offline |
| `BO-08-03-02` | P1 | 3 | Déploiement - Définition des Points de Vente | Sélection des Points de vente à mettre à jour: - unitaire - groupée sur chaque échelon de l'organisation - groupée sur les attributs Points de Vente (vocation par exemple) - groupée logique (caisse paire par exemple) - groupée par import d'une liste externe -groupée par rappel d'une sélection… |
| `BO-08-03-03` | P1 | 3 | Déploiement - Définition des Points de Contact… | Sélection des Points de Contact d'Encaissement unitaire ou groupée dans un ou x Points de Vente à mettre à jour |
| `BO-08-03-04` | P1 | 3 | Déploiement - Définition de la Période | Spécification horaire de la mise à jour de chaque lot de déploiement / mise à jour : jj/mm/aaaa hh:mn à jj/mm/aaaa hh:mn |
| `BO-08-03-05` | P2 | 3 | Déploiement - Programmation Mise à jour brique Centrale | Programmation des Mises à Jour des versions sur le Backoffice centralisé |
| `BO-08-03-06` | P1 | 3 | Déploiement - Console | IHM de Visualisation des déploiement Mises à Jour avec liste et graphique : - Vision globale de l'organisation - Vision sur chaque échelon de l'organisation - Vision ok et/ou ko deploiement ou mises à jour -Vision versions - Export liste XLS, CSV … |
| `BO-08-03-07` | P1 | 3 | Déploiement - Mise à jour caisse automatique | Mise à jour Point de Vente ou Point de Contact d'encaissement automatiquement suite démarrage si niveau de version inférieure |
| `BO-08-03-08` | P1 | 1a | Synchronisation données Point De Vente - Caisses | synchronisation automatique des données serveurs avec les caisses |
| `BO-08-03-09` | P1 | 3 | Phase Pilote Backoffice | Pouvoir effectuer une phase pilote d'une version majeure ou mineure sur un groupe de Points De Vente sans impacter les autres points de vente en production |
| `BO-08-03-10` | P1 | 3 | Phase Pilote Caisse | Pouvoir effectuer une phase pilote d'une version majeure ou mineure sur la ligne de caisses d' un groupe de Points De Vente sans impacter les autres points de vente en production |
| | | | **08 - 04 Administration technique** | |
| `BO-08-04-01` | P1 | 3 | Déploiement - reprise automatique sur erreur | Reprise automatique sur erreur des mises à jour - Brique centrale - Point de Vente - Point de Contact d'Encaissement |
| `BO-08-04-02` | P1 | 3 | Déploiement - reprise manuelle sur erreur | Reprise manuelle (forçage) sur erreur des mises à jour - Brique centrale - Point de Vente - Point de Contact d'Encaissement Reprise sur erreur des mises à jour backoffice et caisse |
| `BO-08-04-03` | P1 | 1a | Relance automatique sur erreur Intégration Articles | Relance automatique en cas d'erreur d'intégration des mises à jour article sur : - la brique Centrale - le Point de Vente - le Point de Contact d'encaissement |
| `BO-08-04-04` | P1 | 1a | Relance Automatique sur erreur Intégration Nomenclature | Relance automatique en cas d'erreur d''intégration des mises à jour des nomenclatures sur : - la brique Centrale - le Point de Vente - le Point de Contact d'encaissement |
| `BO-08-04-05` | P1 | 3 | Relance Automatique sur erreur Intégration Client en compte | Relance automatique en cas d'erreur d''intégration des mises à jour des clients en compte sur : - la brique Centrale - le Point de Vente - le Point de Contact d'encaissement |
| `BO-08-04-06` | P2 | 3 | Relance Automatique sur erreur Intégration TVA | Relance automatique en cas d'erreur d''intégration des mises à jour des TVA sur : - la brique Centrale - le Point de Vente - le Point de Contact d'encaissement |
| `BO-08-04-07` | P1 | 3 | Relance Automatique sur erreur Intégration attributs… | Relance automatique en cas d'erreur d''intégration des mises à jour des attributs des articles sur : - la brique Centrale - le Point de Vente - le Point de Contact d'encaissement |
| `BO-08-04-08` | P1 | 1a | Relance automatique sur erreur Intégration Offres… | Relance automatique en cas d'erreur d''intégration des Offres Commerciales sur : - la brique Centrale - le Point de Vente - le Point de Contact d'encaissement |
| `BO-08-04-09` | P1 | 3 | Relance manuelle sur erreur Intégration Articles | Relance manuelle en cas d'erreur d''intégration des mise à jour article sur : - la brique Centrale - le Point de Vente - le Point de Contact d'encaissement |
| `BO-08-04-10` | P1 | 3 | Relance manuelle sur erreur Intégration Nomenclature | Relance manuelle en cas d'erreur d''intégration des mises à jour des nomenclatures sur : - la brique Centrale - le Point de Vente - le Point de Contact d'encaissement |
| `BO-08-04-11` | P1 | 3 | Relance manuelle sur erreur Intégration Client en compte | Relance manuelle en cas d'erreur d''intégration des mises à jour des clients en compte sur : - la brique Centrale - le Point de Vente - le Point de Contact d'encaissement |
| `BO-08-04-12` | P2 | 3 | Relance manuelle sur erreur Intégration TVA | Relance manuelle en cas d'erreur d''intégration des mises à jour des TVA sur : - la brique Centrale - le Point de Vente - le Point de Contact d'encaissement |
| `BO-08-04-13` | P1 | 3 | Relance manuelle sur erreur Intégration Offres Commerciales | Relance manuelle en cas d'erreur d''intégration des Offres Commerciales sur : - la brique Centrale - le Point de Vente - le Point de Contact d'encaissement |
| `BO-08-04-14` | P2 | 3 | Retour arrière Configuration Automatique | En cas de d'échec de la Mise à Jour, remise en état automatique avec la version applicative précédente (ne concerne que l'application, pas les données des référentiels) |
| `BO-08-04-15` | P1 | 3 | Activité - Console | IHM de Visualisation de l'activité avec liste et graphique : - Vision globale de l'organisation - Vision sur chaque échelon de l'organisation - Vision ok et/ou ko applicatif -Vision ok et/ou ko périphérique des Points de Contatct d'Encaissement - Export liste XLS, CSV … |
| `BO-08-04-16` | P1 | 3 | Supervision Caisse | Tableau de bord santé des caisses par Point De Vente avec filtre sur les caisses Point De Vente en anomalie (exemple périphérique, synchro données, …) |
| `BO-08-04-17` | P1 | 3 | Outil Prise de Contrôle à distance -Point De Vente | Prise en main à distance du Point De Vente (Edge et Points de Contact d'Encaissement) afin de réaliser des actions de maintenance (relance TPV, ….) |
| `BO-08-04-18` | P1 | 1a | Ticket | Supervision de la remontée du heartbeat Tickets |
| `BO-08-04-20` | P1 | 3 | Gestion du parc logiciel et matériel | Supervision du matériel /périphériques installés Tableau de bord inventaire matériels : - caisse - périphériques installés |

## Lot 10 — Parc des caisses et îlots

**50 exigences** (44 P1) — état actuel : 0 en `1`, 8 en `1a`, 42 en `3` · **2 à 3 sessions**

*Objectif.* La configuration des points de contact — activation, type, périphériques et pilotes, entête et logo — et la configuration groupée par îlot.

*Socle.* Ces réglages existent en clés de configuration par nœud ; le lot les fait passer au référentiel administré.

*Critère de fin.* Une caisse déclarée et configurée depuis le back-office démarre avec ses périphériques sans qu'on touche à son fichier de propriétés.

| Code | P | État | Sous-fonctionnalité | Attendu |
|---|---|---|---|---|
| | | | **03 - 09 Configuration Points de Contact d'Encaissement** | |
| `BO-03-09-01` | P1 | 3 | Configuration - Activation | Activation des caisses Point De Vente. Fonctionnalité permettant selon le profil connecté adhérent / Stime d'activer ou de désactiver les caisses. Cela permettant par exemple d'exclure les caisses non activées des rapports, des contôles de clôture, ou de connectivité, .... |
| `BO-03-09-02` | P1 | 3 | Configuration des caisses - entête/pied de page | Configuration de l'entête et pied de page par TPV parmi la liste de choix des modèles d'entête/pied de ticket |
| `BO-03-09-03` | P1 | 3 | Configuration des caisses - tableau TVA | Configuration du tableau de TVA devant être mis en place sur le TICKET parmi la liste de choix des modèles: - aucun - combiné au ticket - séparé - uniquement le montant tva |
| `BO-03-09-04` | P1 | 3 | Configuration des caisses - LOGO | Configuration du LOGO devant être imprimé par TPV au choix parmi la liste des Logo configurés/importés |
| `BO-03-09-05` | P1 | 3 | Configuration des îlots caisses | Configuration de l'îlot de rattachement au TPV. Il doit être possible de créer 1 à N îlot(s) de caisses permettant de regrouper plusieurs points de contact, par exemple : - îlot caisse Libre Service, regroupant plusieurs caisse de type CLS - îlot boulangerie : regroupant les caisses de type… |
| `BO-03-09-06` | P1 | 3 | Configuration du - Menu caisse | Configuration du menu de la caisse parmi la liste des menus disponibles |
| `BO-03-09-07` | P1 | 3 | Configuration - Type de TPV | Configuration du type de TPV: - Caisse - Caisse Libre-Service protocole TGCS - Caisse Libre-Service Protocole DN - Caisse Libre-Service Protocole NCR |
| `BO-03-09-08` | P1 | 3 | Configuration - Hôtesse caisse | Configuration de l'hôtesse technique de caisse (parmi la liste créée) pour les TPV type caisse libre service |
| `BO-03-09-09` | P1 | 3 | Configuration - Activation code-barres ticket (ticket de… | Activation du ticket code-barres par TPV (BO-03-06-55). L'activation de cette fonctionnalité permet l'ajout du code-barres unique du ticket sur le ticket vente/retour (également en duplicata). Le format doit être de type : - EAN 128 ou - CODE 2D |
| `BO-03-09-10` | P1 | 3 | Configuration - File Unique | Configuration de l'activation File Unique en caisse permettant à l'hôtesse de caisse d'informer le système Tiers que la caisse est : - disponible pour le client suivant - non disponible |
| `BO-03-09-11` | P1 | 1a | Configuration - Tiroir | Configuration/Activation du driver Tiroir |
| `BO-03-09-12` | P1 | 3 | Configuration - Afficheur caissière | Configuration du type d'afficheur caissière ainsi que la résolution |
| `BO-03-09-13` | P1 | 3 | Configuration - Afficheur Client | Configuration du type d'afficheur client ainsi que la résolution |
| `BO-03-09-14` | P1 | 1a | Configuration - Balance - Activation | Configuration/Activation du driver balance |
| `BO-03-09-15` | P1 | 3 | Configuration - Paramètres Balances | Configuration du driver balance DIGI |
| `BO-03-09-16` | P1 | 1a | Configuration - Scanner - Activation | Configuration/Activation du driver Scanner |
| `BO-03-09-17` | P1 | 3 | Configuration - Paramètres Scanner | Configuration driver Scanner avec l'opos DATALOGIC |
| `BO-03-09-18` | P1 | 3 | Configuration caisse - Paramètres Second scanner | Configuration/Activation du driver du Second Scanner, permettant ainsi au client via ce second scanner de scanner sa carte de fidélité/coupons. L'utilisation de ce second scanner doit être possible en même temps que le premier scanner |
| `BO-03-09-19` | P1 | 1a | Configuration - monétique | Configuration du type de monétique à minima: - Verifone (monétique centralisée) - Ingenico: Portugal (SIBS) - Worldline: Belgique |
| `BO-03-09-20` | P1 | 3 | Configuration - Activation Flux Catalina | Configuration / Activation du Flux Catalina pour la/les TPV(s). Le périmètre de remontée du flux vers Catalina est tous les points d'encaissement (ligne de caisse, Caisse Libre-Service, mobilité, e-Commerce, …) Suite à cette configuration, les tickets de vente (détail lignes articles + Moyens de… |
| `BO-03-09-21` | P1 | 1a | Configuration - Activation imprimantes | Configuration/Activation du driver Imprimante caisse: - type - vitesse - page encodage - taille page - retournement du chèque |
| `BO-03-09-22` | P1 | 3 | Configuration - Activation Seconde imprimante | Configuration d'une Seconde Imprimante pouvant être de type A4 - local ou réseau |
| `BO-03-09-23` | P1 | 3 | Configuration - Flux Temps réel | Configuration de l'export des données transactions EN TEMPS REELS vers une liste au choix des serveurs/provider. |
| `BO-03-09-24` | P1 | 3 | Configuration - Monnayeur Gunnebo | Configuration du monnayeur par Caisse / Caisse Libre-Service: Protocole Gunnebo adresse Port |
| `BO-03-09-25` | P1 | 3 | Configuration - Monnayeur Glory | Configuration du monnayeur par Caisse / Caisse Libre-Service: Protocole Glory adresse Port |
| `BO-03-09-26` | P1 | 3 | Configuration - Monnayeur Toshiba | Configuration du monnayeur par Caisse / Caisse Libre-Service: Protocole Toshiba adresse Port |
| `BO-03-09-27` | P2 | 3 | Configuration - application Tierce en caisse | Il devra être possible de configurer la localisation d'une application tierce pouvant être appelée par une touche en caisse. |
| `BO-03-09-28` | P1 | 1a | Configuration du N° de terminal | Choix de création du N° du terminal. |
| `BO-03-09-29` | P1 | 3 | Configuration - Label monétique | Configuration du Label monétique (utilisé dans le cadre de la monétique centralisée avec Verifone) : Communication de l'îlot label dans la requête monétique: Tag {D7V} |
| `BO-03-09-30` | P1 | 3 | Moyen de paiement Carte multiproduit | Configuration des moyens de paiement carte multiproduits retournés par la monétique en Belgique: - titre restaurant - eco cheque - carte cadeau. Configuration permettant l’envoi des codes à la Monétique - La caisse doit envoyer dans le product code du protocole VIC (TPE) : {DAJ}01 pour Mealpass :… |
| `BO-03-09-31` | P1 | 3 | Activation Label Point De Vente Verifone | Configuration permettant activer l'ajout du Label Point De Vente caisse dans les requêtes monétiques |
| | | | **03 - 10 Configuration groupée de Points de Contact d'Encaissement** | |
| `BO-03-10-01` | P1 | 3 | Configuration îlots de caisse - Ajout | Création /modification des îlots point de vente Objectif: Créer des îlots de caisse afin d'y rattacher les caisses |
| `BO-03-10-02` | P1 | 3 | Configuration îlots de caisse - Suppression | Suppression des îlots |
| `BO-03-10-03` | P1 | 3 | Configuration îlots de caisse - Activation | Activer l'îlot de caisse pour le Point De Vente |
| `BO-03-10-04` | P1 | 3 | Configuration îlots de caisse - Rendu monnaie autorisée | Configuration du rendu monnaie autorisé pour les caisses rattachées à cet îlot |
| `BO-03-10-05` | P1 | 3 | Configuration îlots de caisse - Impression Ticket "Article… | Configuration de l’activation / désactivation de l’impression du ticket "article inconnu" pour les caisses rattachées à cet îlot |
| `BO-03-10-06` | P1 | 3 | Configuration îlots de caisse - Exclu du FLUX PLANEXA | Exclusion du fichier Planexa des transactions caisses rattachées à cet îlot. Ce flux permet de constituer le planning des hôtesse des caisse et donc d'exclure les îlots pour lesquels il n'est pas requis d'avoir d'hôtesse |
| `BO-03-10-08` | P1 | 3 | Configuration îlots de caisse - Canal Promotion (Point De… | Configuration du canal de l'îlot qui sera transmis à la fidélité pour déclenchement des avantages lié au canal (a minima): - Point De Vente - Web - Autres |
| `BO-03-10-09` | P1 | 3 | Configuration îlots de caisse - Menu caisse | Configuration du menu caisse pour les caisses rattachées à cet îlot. Si la configuration du menu est effectuée au niveau de la caisse cette dernière est prioritaire à celle configurer dans l'îlot de caisse. |
| `BO-03-10-10` | P1 | 3 | Configuration îlots de caisse - Déclaration automatique Fin… | Configuration de l'îlot en déclaration automatique de fin de période. Cette option permet au hôte(sse)s rattaché(e)s à cet îlot d'avoir la déclaration automatique ( pas de comptage manuel à faire lors des clôtures) |
| `BO-03-10-11` | P2 | 3 | Configuration îlots de caisse - Contrôle Montant maximum… | Configuration d'un contrôle sur un montant maximum de vente configurable |
| `BO-03-10-12` | P2 | 3 | Configuration îlots de caisse - Format Annulation ticket/… | Configurer le format du ticket d'annulation pour les caisses rattachées à cet îlot : - Standard : impression du ticket avec les lignes articles produits scannés - Anti-Fraude : impression du ticket uniquement avec le titre ANNULATION TICKET - Sans impression : pas d'impression ticket |
| `BO-03-10-13` | P2 | 3 | Configuration îlots de caisse - Blocage Pop Up Article | Blocage des Pop ups liés à l'article sur les caisses rattachées à cet îlot. Suppression de l'affichage des messages associés à la vente des articles. |
| `BO-03-10-14` | P2 | 3 | Configuration îlots de caisse - Blocage Pop Up Paiement | Blocage des Pop ups liés au moyen de paiement sur les îlots spécifiés |
| `BO-03-10-15` | P2 | 3 | Configuration îlots de caisse - Affichage des caisse… | Affichage/Visualisation des caisses rattachées a l'îlot |
| `BO-03-10-16` | P1 | 3 | Menu caisse - configuration des modèles/menus de caisse | Configuration des différents menus de caisse : Possibilité de pouvoir gérer à minima 99 modèles / menus de caisse |
| `BO-03-10-17` | P1 | 3 | Menu caisse - configuration des écrans de caisse | Il doit être possible de configurer les differents écrans de caisse, l'affichage doit être dynamique : - affichage du modèle d'écran hôte(sse) caisse et client en fonction du modèle configuré sur l'îlot de caisse - les fonctionnalités/moyens de paiement affichés sur les écrans hôte(sse) en fonction… |
| `BO-03-10-18` | P1 | 3 | Paramétrage des enseignes | Configuration des spécificités par Pays et enseignes: - langue(s) - gestion des menus /profil par pays /enseigne - Données de base permettant de couvrir les formations PDV avant ouverture : Nomenclature, article de tests, utilisateur caisse générique |
| `BO-03-10-19` | P1 | 1a | Configuration des données Point De Vente | Configuration des informations du point de vente : - Nom magasin - adresse - ville - Numéro TVA intracommunautaire - N° Siret - N° téléphone - N° compte (cheque) Les données configurées sont reprises dans les Entêtes de documents (ticket, facture,...) si ces dernières sont ajoutées en configuration… |
| `BO-03-10-20` | P1 | 1a | Données Point De Vente | Les données configurées dans la fiche magasin doivent être disponible en re reprise automatique dans les Entêtes de documents (ticket, facture,...) si ces dernières sont ajoutées en configuration. Exemple, Dans le template de l'entête, le %Nom Magasin% est présent,. L'entête imprimé en caisse aura… |

## Lot 9 — Codes-barres

**71 exigences** (68 P1) — état actuel : 0 en `1`, 14 en `1a`, 57 en `3` · **3 sessions**

*Objectif.* La généralisation de CouponType en référentiel de plages — positions, longueurs, dates, contrôles, actions — les écrans de visualisation, le ticket balance et GS1.

*Socle.* CouponType, la chaîne de scan et ses handlers priorisés, les patterns de configuration existants.

*Critère de fin.* Une plage créée dans le back-office est reconnue au scan en caisse au tirage suivant, sans redéploiement.

| Code | P | État | Sous-fonctionnalité | Attendu |
|---|---|---|---|---|
| | | | **03 - 06 Gestion des codes-barres** | |
| `BO-03-06-01` | P1 | 1a | Code-barres | Lecture des codes-barres créés/interprétés par le système d'encaissement : - code 128 alphanumérique; code 8, code 13, code 2D; |
| `BO-03-06-02` | P1 | 3 | Type de code-barres : définition article | Réservation d'une plage pour affection à des articles spécifiques Mise en place d'une plage d'article avec une programmation de code-barres d'une longueur mini à maxi de type numérique alphanumérique |
| `BO-03-06-03` | P1 | 1a | Type de code-barres : définition article PPV PRIX | Lecture / interprétation Code Article PPV PRIX Configuration de la position/longueur de lecture du prix, ainsi que du format du prix (Euro ou Franc Français). |
| `BO-03-06-04` | P1 | 1a | Type de code-barres : définition article PPV POIDS | Lecture / interprétation Code Article PPV POIDS quantités en décimales Configuration de la lecture de la quantité (pouvant être en décimale) pour les articles de type ppv poids |
| `BO-03-06-05` | P1 | 3 | Type de code-barres : définition article PPV PRIX FR | Lecture / interprétation Gestion Code Article PPV PRIX configuration de la position/longueur de lecture du prix en devise Franc Français |
| `BO-03-06-06` | P1 | 1a | Type de code-barres : définition article Check Digit | Lecture / interprétation Code Article avec contrôle de la clé EAN13 configuration du contrôle de la clé EAN13 |
| `BO-03-06-07` | P1 | 3 | Exclusion/acceptation du code-barres par îlots | Configuration de l'acceptation ou l'exclusion du code-barres sur certains Points de Contact d'encaissement ou groupes de caisses |
| `BO-03-06-08` | P1 | 1a | Type de code-barres : Moyen de Paiement | Lecture/ configuration / interprétation d'une plage de code-barres permettant à la lecture de ce dernier de déclencher le paiement associé en caisse. --- cas d'usage : Configuration d'un code-barres bon d'achat 981234XXXXXXXX au moyen de paiement ID n° 14 " bon d'achat". Au scan du bon en caisse ce… |
| `BO-03-06-09` | P1 | 1a | Type de code-barres : alphanumérique | Lecture/ configuration / interprétation d'une plage de code-barres de type alphanumérique |
| `BO-03-06-10` | P1 | 1a | Code-barres : longueur | Lecture/ configuration / interprétation d'une plage de code-barres de 1 à 38 caractères |
| `BO-03-06-11` | P1 | 3 | Code-barres : Montant | Configuration du positionnement de lecture du Montant présent dans le code |
| `BO-03-06-12` | P1 | 3 | Code-barres : Montant 9999 | Configurer l'autorisation de saisie manuelle du montant en caisse lors de la lecture du code-barres, si tous les chiffres du prix sont des « 9 ». Lors du scan d'un bon d'achat en caisse, si le code-barres lu contient un montant constitué uniquement de « 9 », une demande de saisie de la valeur du… |
| `BO-03-06-13` | P1 | 3 | Code-barres : Prix en décimales | Configuration du positionnement de la lecture du Prix avec sa longueur ainsi que le nombre de décimales. Exemple : offset Prix = 3 Longueur Prix = 6 Nombre de décimales dans le prix = 2 |
| `BO-03-06-15` | P1 | 3 | Code-barres : Information magasin | Configuration du positionnement de lecture du numéro de Point De Vente. Cela permet de pouvoir accepter en caisse que les codes-barres propre au Point De Vente si nécessaire |
| `BO-03-06-16` | P1 | 3 | Code-barres : Date début | Configuration du positionnement de lecture de la date de début |
| `BO-03-06-17` | P1 | 3 | Code-barres : Date de Fin | Configuration du positionnement de lecture de la date de d'expiration |
| `BO-03-06-18` | P1 | 3 | Code-barres : Paramètre Date JJMMAA | Configuration du positionnement de lecture de la date au format JJMMAA |
| `BO-03-06-19` | P1 | 3 | Code-barres : Paramètre Date MMJJAA | Configuration du positionnement de lecture de la date au format MMJJAA |
| `BO-03-06-21` | P1 | 3 | Code-barres : Paramètre Date MMJJ | Configuration du positionnement de lecture de la date au format MMJJ |
| `BO-03-06-22` | P1 | 3 | Code-barres : Paramètre Date JJMM | Configuration du positionnement de lecture de la date au format JJMM |
| `BO-03-06-23` | P1 | 3 | Code-barres : Paramètre Date JJMMAAAA | Configuration du positionnement de lecture de la date au format JJMMAAAA |
| `BO-03-06-24` | P1 | 3 | Code-barres : Paramètre Date DDD (N°Jour) | Configuration du positionnement de lecture de la date au format DDD (N°Jour dans l'année 1 à 365) |
| `BO-03-06-25` | P2 | 3 | Code-barres : Paramètre Date AAAAMMJJ | Configuration du positionnement de lecture de la date au format AAAAMMJJ |
| `BO-03-06-27` | P1 | 3 | Code-barres : Caractère de contrôle Point De Vente | Configuration du positionnement de lecture du contrôle de N° Point De Vente Configuration du positionnement de lecture /génération du contrôle de N° Point De Vente Permet notamment d'accepter les coupons/bon à destination du Point De Vente uniquement |
| `BO-03-06-28` | P1 | 3 | Code-barres : minimum Total Ticket | Configuration du positionnement et longueur de lecture /génération du montant total minimum Permet d'accepter les Bon d'achats si le minimum Total ticket spécifié dans le bon est atteint |
| `BO-03-06-29` | P1 | 3 | Code-barres : minimum Total Ticket avec décimales | Configuration du positionnement et la longueur de lecture du minimum total ticket avec décimales |
| `BO-03-06-30` | P1 | 3 | Code-barres : Heure | Configuration du positionnement de l'heure dans le code-barres afin d'être reconnu/imprimé en caisse |
| `BO-03-06-31` | P1 | 3 | Code-barres : Heure Format HHMM | Configuration du format de l'heure HHMM dans le code-barres afin d'être reconnu/imprimé en caisse |
| `BO-03-06-32` | P1 | 3 | Code-barres : Heure Format HHMM | Configuration du format de l'heure HHMMSS dans le code-barres afin d'être reconnu/imprimé en caisse |
| `BO-03-06-33` | P1 | 3 | Code-barres : N° Ticket | Configuration du positionnement du N° de ticket dans le code-barres afin d'être reconnu/imprimé en caisse |
| `BO-03-06-34` | P1 | 3 | Code-barres : longueur N° Ticket | Configuration de la longueur du N° de ticket dans le code-barres afin d'être reconnu/imprimé en caisse |
| `BO-03-06-35` | P1 | 3 | Code-barres : N° séquence | Configuration du positionnement du N° séquence dans le code-barres afin d'être reconnu/imprimé en caisse |
| `BO-03-06-36` | P1 | 3 | Code-barres : longueur N° séquence | Configuration de la longueur du N° d'édition dans le code-barres afin d'être reconnu/imprimé en caisse Permet de contrôler les doublons des codes-barres bons d'achats lu en caisse |
| `BO-03-06-37` | P1 | 3 | Code-barres : N° TPV | Configuration du positionnement du N° TPV dans le code-barres afin d'être reconnu/imprimé en caisse |
| `BO-03-06-38` | P1 | 3 | Code-barres : longueur N° TPV | Configuration de la longueur du N° TPV dans le code-barres afin d'être reconnu/imprimé en caisse |
| `BO-03-06-39` | P1 | 1a | Code-barres : Flag de Comptage / log transaction | Activation de comptage du code-barres / Enregistrement du code-barres dans la transaction Paramètre permettant de contrôler les doublons de code-barres lu. En cas de doublon un contrôle de type warning ou bloquant est mis en place Gestion de contrôle en cas de scan du code-barres en doublon |
| `BO-03-06-40` | P1 | 3 | Code-barres : carte de fidélité requise | Vérification de la présence de la carte de fidélité dans la transaction pour application du code-barres. Le contrôle de la carte de fidélité doit également permettre de vérfier en option si une partie du code de la carte fidélité lu correspond bien au code présent sur le coupon Avec gestion de… |
| `BO-03-06-41` | P2 | 3 | Code-barres : appel externe | Configuration de l'appel d'un webservice application tierce au scan du code-barres |
| `BO-03-06-42` | P1 | 3 | Code-barres : appel Moteur de promotion | Configuration de l'appel du moteur de promotion. Le code-barres scanné est transmis au moteur de promotion qui dans ce cadre appliquera la promotion associé au code-barres |
| `BO-03-06-43` | P1 | 3 | Code-barres : déclenchement promotion | Configuration de l'appel d'une promotion spécifique. Permet de déclencher une promotion spécifique au scan du code-barres |
| `BO-03-06-44` | P1 | 3 | Code-barres : second code-barres requis | Activation second code-barres requis notamment pour les produits Démat. Certain produits de type démat nécessite un scan d'un second code-barres après le scan de l'ean. Le second code-barres est le code d'activation qui est ensuite transmis au provider (provider configuré au produit) |
| `BO-03-06-45` | P1 | 3 | Contrôle Caisse Offline | Affichage d'un message d'alerte de type warning ou bloquant si la caisse est offline lors de la lecture d'un code-barres |
| `BO-03-06-46` | P1 | 3 | Contrôle Caisse "Autre magasin" | Affichage d'un message d'alerte de type warning ou bloquant lors du scan d'un code-barres si le N°Point De Vente devant être contrôlé est diffèrent |
| `BO-03-06-47` | P1 | 3 | Contrôle Caisse "date expiration" | Affichage d'un message d'alerte de type warning ou bloquant lors du scan d'un code-barres si la date incluse dans le code-barres est inférieur à la date du jour |
| `BO-03-06-48` | P1 | 1a | Contrôle Caisse "code-barres non trouvé" | Affichage d'un message d'alerte de type warning ou bloquant lors du scan d'un code-barres est absent de la base de donnée Point de vente ou centrale. --- cas d'usage Emission d'un avoir en caisse lors d'un retour produit scan d'un avoir en caisse différent de celui précédemment émis sur le pdv -->… |
| `BO-03-06-49` | P1 | 1a | Contrôle Caisse "code-barres déjà utilisé" | Affichage d'un message d'alerte de type warning ou bloquant lors du scan d'un code-barres ayant déjà été utilisé sur le Point De Vente : code-barres locale point de vente ou centralisé. |
| `BO-03-06-50` | P1 | 3 | Îlot de caisse | Configuration des îlots devant accepter /refuser le code-barres |
| `BO-03-06-51` | P1 | 1a | Carte superviseur | Création de code-barres superviseur avec rattachement de la carte à l'hôte(sse) |
| `BO-03-06-52` | P1 | 3 | Client en Compte | Configuration des plages de code pour les comptes clients. Permettant ainsi de définir la plage de code compte client acceptée en caisse en paiement crédit client |
| `BO-03-06-53` | P1 | 3 | Client de "TPV" | Configuration des plages de code pour les créations de clients en caisse lors des demandes de Facture/Bon de Livraison. Les clients créés en caisse seront créés avec les paramètres de code définis dans cette plage. |
| `BO-03-06-54` | P1 | 1a | Code-barres : client fidélité | Configuration des plages de cartes acceptées comme client fidélité. Configuration de la plage de code avec le check digit 19 de Type Luhn |
| `BO-03-06-55` | P1 | 3 | Code-barres : Ticket | Configuration de la plage de code-barres devant être imprimés sur les tickets. Plage de code imprimé sur les tickets pouvant être utilisé pour les portiques ou les bornes de jeux |
| `BO-03-06-56` | P1 | 3 | Code-barres : Ticket Balance | Configuration de la plage de code-barres acceptés en caisse comme ticket balance. Ticket Balance = Ticket émis par les balanciers rayon traiteurs/fromage et autres permettant de constituer un "sachet" avec plusieurs produits. Les balanciers mettent à disposition de l'encaissement (via ftp) le… |
| `BO-03-06-57` | P1 | 3 | Code-barres : Qbusting | Configuration de la plage de code-barres pour récupération en caisse / caisse libre-service des transactions de type qbusting |
| `BO-03-06-58` | P1 | 3 | Code-barres : Commande/devis/Bon de vente | Configuration de la plage de code-barres acceptés en caisse pour les transactions de type commande issue du backoffice Mercalys/SAP |
| `BO-03-06-59` | P1 | 3 | Code-barres : Appel Fonction Caisse | Configuration de la plage de code-barres permettant au scan de cette dernière de déclencher un appel de fonction caisse. L'appel de la fonction en caisse s'effectue par la lecture du contenant l'information de la fonction à déclencher. Cela permet de déclencher des fonctions de caisse comme par… |
| `BO-03-06-60` | P1 | 3 | Code-barres : Tare | Configuration de la plage de code-barress acceptés en caisse permettant de déduire la Tare du contenant |
| `BO-03-06-61` | P1 | 3 | Code-barres : Retour | Configuration du type de retour à déclencher à la lecture d'un code-barres. Au scan du code-barres en caisse, cela déclenchera automatiquement le retour de l'article configuré dans le code-barres avec son prix. |
| `BO-03-06-62` | P1 | 1a | Visualisation codes-barres imprimés en caisses | Interface de visualisation des codes-barres imprimés en caisses de type bons d'achat , avoir, acompte avec les informations suivantes : - N° TPV - N° transaction - Montant - Date d'impression - Date expiration |
| `BO-03-06-63` | P1 | 1a | Visualisation codes-barres passés en caisses | Afficher la liste des codes-barres scannés en caisse avec les informations suivantes : - N° TPV - N° transaction - Montant - Date expiration - Date utilisation - Code-barres trouvé |
| `BO-03-06-64` | P1 | 3 | Gestion GS1 AI (17) | Il devra être possible de pouvoir configurer pour les codes-barres de type GS1, AI (17): - une alerte (warning, bloquant avec option appel superviseur pour forcer, bloquant sans appel superviseur) - le message associé - nombre de jours restants avant la DLC Exemple Paramêtre Backoffice code-barres… |
| `BO-03-06-65` | P1 | 3 | Gestion GS1 AI (253) | Il devra être possible de pouvoir configurer pour les codes-barres de type GS1, AI (253): - la liste des codes émetteurs et le moyen de paiement associé |
| `BO-03-06-66` | P1 | 3 | Gestion GS1 AI (255) | Il devra être possible de pouvoir configurer pour les codes-barres de type GS1, AI (255): le moyen de paiement associé |
| `BO-03-06-67` | P1 | 3 | Contrôle Caisse "date de début" | Affichage d'un message d'alerte de type warning ou bloquant lors du scan d'un code-barres si la date de début incluse dans le code-barres est supérieure à la date du jour |
| | | | **10 - 05 Balance** | |
| `BO-10-05-01` | P1 | 3 | Ticket Balance - configuration | Configuration du mode de récupération des paniers Ticket balance. Ticket Balance = lecture d'un code-barres émis par les balanciers, qui fait référence à une liste d'EAN13 + poids ou quantité + prix. Le ticket balance lu en caisse permet d'afficher / traiter la liste des articles présents dans le… |
| `BO-10-05-02` | P1 | 3 | Ticket Balance - référence prix article | Paramètre permettant de définir si le prix des articles du panier Ticket Balance doit être repris de la base de donnée Point De Vente ou du panier Ticket Balance |
| `BO-10-05-03` | P1 | 3 | Tare Balance - Tare logicielle (par défaut) | Configuration d'une Tare par défaut qui sera appliquée par défaut en pesée en caisse (pesée ou saisie poids). La configuration de cette Tare doit être mise à disposition du Point de Vente. La Tare configurée doit être appliquée en Ligne de caisse et Caisse Libre-Service |
| `BO-10-05-04` | P2 | 3 | Tare Balance - Tare logicielle (par défaut) TICKET | La valeur de cette Tare (kg) peut être imprimée sur le ticket si souhaité |
| | | | **10 - 09 GS1** | |
| `BO-10-09-01` | P1 | 3 | GS1 - Activation | A partir du backoffice il devra être possible d'activer les règles de lecture des codes GS1 Activation de la lecture des codes GS1 en caisse |
| `BO-10-09-04` | P1 | 3 | GS1 - Alerte | Il devra être possible de pouvoir à la lecture d'une AI configurer une alerte (warning, appel superviseur,..) et de l'associer à un message |
| `BO-10-09-09` | P1 | 3 | GS1 - AI 17 Appel Promotion | Selon le délai avec la date d'expiration, il devra être possible en caisse de déclencher automatiquement une promotion associée au délai de péremption, le système doit donc être capacité de transmettre ces informations au moteur de promotion tierce |

## Lot 7 — Modes de règlement

**47 exigences** (39 P1) — état actuel : 0 en `1`, 1 en `1a`, 46 en `3` · **4 à 5 sessions**

*Objectif.* Un référentiel administrable de moyens de paiement — plafonds, contrôles, remboursement, rendu, tiroir, modèle d'impression — en remplacement des paiements figés.

*Socle.* PaymentService et la séquence fiscale, à ouvrir avec précaution.

*Critère de fin.* Un moyen de paiement créé dans le back-office est utilisable en caisse au tirage suivant, sans déploiement.

| Code | P | État | Sous-fonctionnalité | Attendu |
|---|---|---|---|---|
| | | | **03 - 02 Gestion des modes de règlements** | |
| `BO-03-02-01` | P1 | 3 | Mode de règlement - Ajout | Un mode de règlement doit pouvoir être ajouté. |
| `BO-03-02-02` | P1 | 3 | Mode de règlement - Suppression | Un mode de règlement doit pouvoir être supprimé |
| `BO-03-02-03` | P1 | 3 | Paramétrage mode de règlement - Activation | Configuration de l'activation du règlement Cette option doit pouvoir être modifiable par le Point De Vente pour l'ensemble des moyens de paiement -- En tant qu’adhérent, directeur de magasin ou tout autre profil habilité, Je souhaite avoir la possibilité d’activer ou de désactiver les moyens de… |
| `BO-03-02-04` | P1 | 3 | Mode de règlement - Nombre de règlements | Le nombre de moyens de règlement pouvant être créés devra être sur 3 digits minimum. L'ID unique du moyen de paiement ne doit en aucun cas être au format 2 digits qui limiterait de facto le nombre de moyens de paiements distincts à 99 uniquement. |
| `BO-03-02-05` | P3 | 3 | Mode de règlement - Impression liste des modes de règlement | La liste des modes de règlement doit pouvoir être imprimée/exportée |
| `BO-03-02-06` | P1 | 3 | Mode de règlement - Paramétrage de règlement non modifiable | Configuration d'une plage de moyens de paiement figée non modifiable par le Point De Vente hors activation ou non de ce dernier par le Point De Vente |
| `BO-03-02-07` | P1 | 3 | Mode de règlement - Paramétrage de règlement modifiable… | Configuration d'une plage de moyens de paiement modifiable par le Point De Vente. -- En tant qu’adhérent, directeur de magasin ou tout autre profil habilité, Je souhaite pouvoir modifier quelque moyens de paiement (Ex :les 50 derniers), Afin de disposer d’une autonomie dans la configuration des… |
| `BO-03-02-10` | P1 | 3 | Mode de règlement - Montant maximum dans le ticket et… | Configuration du règlement : Montant maximum dans le ticket et contrôle configurer le montant maximum et le contrôle associé (warning, info, bloquant superviseur, bloquant) Par exemple le paiement titre restaurant est limité à 25€ maximum |
| `BO-03-02-11` | P2 | 3 | Mode de règlement - Montant maximum rendu dans le ticket et… | Configuration du règlement : Montant maximum rendu dans le ticket et contrôle : configurer le montant maximum en rendu et le contrôle associé, en cas de non-respect de ce contrôle (warning, info, bloquant superviseur, bloquant) |
| `BO-03-02-12` | P1 | 3 | Mode de règlement - maximum de fois dans le ticket et… | Configuration du règlement : maximum de fois dans le ticket et contrôle Configurer le nombre maximal d’utilisations de ce moyen de paiement par transaction ainsi que le contrôle associé (warning, info, bloquant superviseur, bloquant) Par exemple, en France le nombre maximum de paiements titre… |
| `BO-03-02-13` | P1 | 3 | Mode de règlement - second montant maximum dans le ticket… | Configuration du règlement : un second montant maximum dans le ticket et contrôle configurer un second montant maximum et le contrôle associé (warning, info, bloquant superviseur, bloquant) |
| `BO-03-02-14` | P1 | 3 | Mode de règlement - montant minimum dans le ticket et… | Configuration du règlement : montant minimum dans le ticket et contrôle: configurer un montant minimum et le contrôle associé (warning, info, bloquant superviseur, bloquant) |
| `BO-03-02-15` | P1 | 3 | Mode de règlement - autorisé ou non pour le remboursement | Configuration du règlement : autoriser ou non pour le remboursement Les moyens de paiement doivent pouvoir être configurés pour être utilisés pour le remboursement |
| `BO-03-02-16` | P1 | 3 | Mode de règlement - autorisé ou non le rendu monnaie et… | Configuration du règlement : autoriser ou non le rendu monnaie et avec lequel:Les moyens de paiement doivent pouvoir être configurés pour autoriser ou non le rendu monnaie ainsi que définir lequel. Exemple : rendu avoir autorisé avec un avoir et espèce -- En cas de retours, les moyens de paiement… |
| `BO-03-02-17` | P1 | 3 | Mode de règlement - déclaration automatique caissière | Configuration du règlement : déclaration automatique caissière:Les moyens de paiement doivent pouvoir être configurés afin d'être en déclaration automatique caissière -- Déclaration automatique caissière: configuration de ce moyen de payement afin d'être en déclaration automatique caissière (Oui… |
| `BO-03-02-18` | P1 | 3 | Mode de règlement - prélèvement automatique | Configuration du règlement : prélèvement automatique Les moyens de paiement doivent pouvoir être configurés afin d'être en prélèvement automatique |
| `BO-03-02-19` | P1 | 3 | Mode de règlement - ouverture tiroir | Configuration du règlement : ouverture tiroir Les moyens de paiement doivent pouvoir être configurés afin d'ouvrir le tiroir Avec possibilité de choisir que le tiroir s’ouvre : - Immédiatement après le paiement - Uniquement si un rendu est due au client - Uniquement après l’impression d’un reçu… |
| `BO-03-02-20` | P1 | 3 | Mode de règlement - autoriser dépense /apport | Configuration du règlement : autoriser dépense /apport : Les moyens de paiement doivent pouvoir être configurés afin d'être autorisés pour les apports/dépenses |
| `BO-03-02-21` | P1 | 3 | Mode de règlement - remise en banque | Configuration du règlement : remise en banque Les moyens de paiement doivent pouvoir être configurés afin d'être remis en banque |
| `BO-03-02-22` | P1 | 3 | Mode de règlement - Autoriser le règlement d'être en fond… | Configuration du règlement espèce : Autoriser le règlement d'être en fond de caisse comme par exemple les ESPECES |
| `BO-03-02-23` | P1 | 3 | Mode de règlement - Afficher montant Total par défaut à… | Configuration du règlement :Afficher Total par défaut Les moyens de paiement doivent pouvoir être configurés afin d'afficher le total par défaut. Par exemple si le moyen de paiement "Chèque Auto" est actif sur l'option Afficher Total par défaut, le montant total du ticket sera proposé en paiement… |
| `BO-03-02-24` | P1 | 3 | Mode de règlement - règlement chèque auto | Configuration du règlement cheque auto: Les moyens de paiement doivent pouvoir être configurés afin : - d'être en lecture via le MICR - d'avoir une autorisation chèque - que le N° de compte puisse être saisi par la caissière - avec un Template d'impression - en réimpression automatique |
| `BO-03-02-25` | P2 | 3 | Mode de règlement - Affichage détaillé pour rapport de… | Configuration du règlement : Affichage détaillé pour rapport de prélèvement Les moyens de paiement doivent pouvoir être configurés afin d'afficher le détail pour rapport de prélèvement |
| `BO-03-02-26` | P1 | 3 | Mode de règlement - duplicata ticket additionnel | Configuration du règlement : duplicata Pour les moyens de paiement entraînant l'impression de ticket additionnel spécifique tel que les acomptes, il doit être possible de configurer l'option d'impression supplémentaire d'un duplicata. Cas d'usage : En magasin Bricolage, lors de la génération d'un… |
| `BO-03-02-27` | P1 | 3 | Mode de règlement - disponible en Caisse Libre-Service | Les moyens de paiement doivent pouvoir être configurés afin d'être disponibles en Caisse Libre-Service |
| `BO-03-02-29` | P1 | 3 | Mode de règlement - Supervision moyen de paiement en Caisse… | Configuration de certains moyens de paiement nécessitant un appel superviseur sur un îlot spécifié tel que l'îlot Caisse Libre-Service. Cas d'usage : En Caisse Libre Service le paiement Carte de Fidélité est soumis à un appel superviseur |
| `BO-03-02-30` | P1 | 3 | Mode de règlement - remontée paiement Fidélité | Configuration du règlement : remontée paiement Fidélité Pour les moyens de paiement lié à l'utilisation de la cagnotte fidélité, il doit être possible de les configurer de manière à ce que l'usage de ce moyen de paiement puisse être extrait |
| `BO-03-02-31` | P1 | 3 | Mode de règlement - impression forcée | Configuration du ticket du règlement : impression forcée Le ticket associé aux moyens de paiement (configuré dans les templates) doit pouvoir être forcé en impression en cas de dématérialisation du ticket de caisse (ou de non-impression du ticket). |
| `BO-03-02-32` | P1 | 3 | Mode de règlement - modèle d'impression | Configuration du ticket du règlement : modèle d'impression Le modèle d'impression du moyen de paiement doit pouvoir être configuré -- Cas d'usage : Rattachement du modèle de ticket (template) par exemple Avoir au moyen de paiement Avoir. Rattachement du modèle de ticket (template) carte prépayé au… |
| `BO-03-02-33` | P1 | 3 | Mode de règlement - nombre d'impressions | Configuration du ticket du règlement : nombre d'impressions Le nombre d'impressions du moyen de paiement doit pouvoir être configuré -- Nombre d’impressions : le nombre de fois que le ticket attaché à un moyen de paiement doit être imprimé doit pouvoir être configuré (1 par défaut) |
| `BO-03-02-34` | P1 | 3 | Mode de règlement - ticket CB | Configuration du ticket du règlement partie monétique : Le modèle d'impression du ticket avec le ticket du moyen de paiement CB doit pouvoir être configuré : -Ticket CB Associé au ticket principal, -Ticket CB Imprimé sur un ticket séparé -Sans impression (pas de ticket CB). Par exemple en Belgique,… |
| `BO-03-02-35` | P1 | 3 | Modèle de ticket du règlement en cas d'annulation | Configuration du modèle de ticket du règlement en cas d'annulation Un modèle d'impression de ticket doit pouvoir être configuré en cas d'annulation du moyen de règlement |
| `BO-03-02-36` | P1 | 3 | Mode de règlement - Borne de paiement | Configuration du mode de règlement rattaché à un monnayeur Configuration du mode de paiement "espèces" avec l’option monnayeur afin qu’en caisse, l’utilisation de ce mode de paiement soit connectée au monnayeur configuré dans la configuration de caisse. |
| `BO-03-02-37` | P1 | 3 | Mode de règlement - Type carte carte prépayée | Configuration des types de cartes carte prépayée avec le protocole associé à cette dernière: Protocole carte : - nexo …. |
| `BO-03-02-38` | P1 | 1a | Mode de règlement - Eligibilité | Configuration du contrôle d'éligibilité d'application du mode de règlement selon les articles de la transaction. Cette configuration permet d'accepter le moyen de paiement si l'article est éligible à ce dernier. Exemple : titre restaurant, eco-chèque Belgique, carte achat sociale Portugal |
| `BO-03-02-39` | P1 | 3 | Taux de change: monnaie d'échange | Configuration de la monnaie d'échange Ce paramétrage est utilisé par les Point De Vente frontaliers. Il permet de pouvoir accepter une monnaie étrangère et de choisir la monnaie d'échange. |
| `BO-03-02-40` | P1 | 3 | Taux de change | Configuration du taux de change par défaut, mise à dispo du Point De Vente Ce paramétrage est utilisé par les Point De Vente frontaliers. Il est nécessaire de pouvoir gérer un taux de change avec un historique (stocker la date de modification du taux de change) Utilisateurs : profil "responsable" |
| `BO-03-02-41` | P1 | 3 | Paramétrage fonds de caisse espèces | Configuration d'un fond de caisse par défaut à chaque échelon de l'organisation jusqu'au Point de Contact d'Encaissement |
| `BO-03-02-42` | P3 | 3 | Paramétrage rouleaux de monnaie | Permettre de configurer la composition des rouleaux - Valeur faciale - Montant (en €) - Nombre de pièces |
| `BO-03-02-43` | P2 | 3 | Endossement Chèque | Le PDV doit pouvoir configurer les informations d'endossement des chèques, qui seront automatiquement imprimées au dos de chaque chèque, permettant ainsi la remise en banque et évitant les saisies manuelles. |
| `BO-03-02-44` | P1 | 3 | Type Carte bancaire | Configuration des correspondances de cartes bancaires (intégration monetique) associé à un moyen de paiement --> Table de correspondance monétique Tag D16 et Tag D46 Cas d'usage : lors des paiements monétique en caisse, au retour du tad D16, enregistrement dans la transaction du moyen de paiement… |
| `BO-03-02-45` | P1 | 3 | Dépense - Création / modification / suppression | Configuration des Dépenses : - libellé - mode de paiement : espèces ou autre - activation |
| `BO-03-02-46` | P1 | 3 | Dépense - Activation | Les dépenses configurées doivent pouvoir être activées afin d'être disponibles immédiatement en caisse (de manière dynamique). -- Cas d'usage : Le Point de vente après avoir configuré la liste des dépenses, choisit de pouvoir activer certaines ou toutes afin de les rendre accessibles en caisse.… |
| `BO-03-02-47` | P1 | 3 | Dépense - Montant | Configuration d'un montant maximum avec un contrôle / Alerte |
| `BO-03-02-48` | P2 | 3 | Mode dégradé monétique manuel - Activation Point De Vente | Configuration du délai de désactivation automatique du mode dégradé monétique manuel |
| `BO-03-02-49` | P3 | 3 | Activation Mode dégradé monétique manuel - Autorisation… | Configuration de l'autorisation du superviseur (le mode superviseur) pour l'activation du mode dégradé monétique manuel. |
| `BO-03-02-50` | P3 | 3 | Désactivation Mode dégradé monétique manuel - Autorisation… | Configuration de l'autorisation du superviseur (le mode superviseur) pour la désactivation du mode dégradé monétique manuel. |

## Lot 8 — Documents imprimés

**69 exigences** (67 P1) — état actuel : 0 en `1`, 0 en `1a`, 69 en `3` · **4 sessions**

*Objectif.* Un moteur de gabarits d'impression : zones, assemblage, entêtes et pieds, modèles par type de document et par moyen de paiement.

*Socle.* TicketPrinterService et les formats aujourd'hui codés en dur.

*Critère de fin.* Le ticket actuel reproduit à l'identique par un gabarit administré, prouvé par comparaison du contenu imprimé avant/après.

| Code | P | État | Sous-fonctionnalité | Attendu |
|---|---|---|---|---|
| | | | **03 - 03 Template des zones des documents** | |
| `BO-03-03-01` | P1 | 3 | Impression Forcée | Il doit être possible de configurer une impression forcée des éléments associés aux tickets, afin qu’ils soient imprimés dans le cadre de l’activation de l’impression conditionnelle: - avoir - acompte - bon d'achat - paiement FID - articles avec produits dématérialisé - paiement fidélité - paiement… |
| `BO-03-03-02` | P1 | 3 | Configuration modèles des documents : ENTETE Facture /Bon… | Définition du contenu des modèles l'élément ENTETE utilisé dans le cadre des FACTURES/BON DE LIVRAISONS/TICKETS. Dans le cadre des Factures / Bon de livraison, par exemple il devra être possible de configurer l'entête avec : - format d'impression (facturette, facture A4) - organisation du contenu… |
| `BO-03-03-03` | P1 | 3 | Configuration des modèles des documents : Lignes Articles | Configuration des formats d'impression des documents émis en caisse de type Ticket , Facture/Bon de livraison: - format d'impression (facturette, facture A4) - organisation du contenu d'impression lignes articles # libellé # ean (si impression activée) # quantité (unité, kg,...) # Prix unitaire… |
| `BO-03-03-04` | P1 | 3 | Configuration des modèles des documents : tableau TVA | Configuration Tableau TVA modèles des documents - format d'impression (facturette, facture A4) - organisation du contenu d'impression tableau TVA # code TVA # Taux # TVA # H.T # T.T.C le tableau de TVA doit être conforme à celui imprimé en Ticket de caisse |
| `BO-03-03-05` | P1 | 3 | Configuration des modèles des documents : Total | Configuration TOTAL modèles des documents : Configuration des formats d'impression des documents émis en caisse: - format d'impression (facturette, facture A4) - organisation du contenu d'impression du TOTAL # remise Total # Total Hors Taxe # Total TTC Le total ttc doit être conforme à celui du… |
| `BO-03-03-06` | P1 | 3 | Configuration des modèles des documents : Ticket en attente | Configuration Template des documents : Ticket en attente: Configuration des formats d'impression des documents émis en caisse: - format d'impression : ticket - organisation du contenu d'impression : # Titre # Code-barres # N° code-barres # date d'impression # N° ticket |
| `BO-03-03-07` | P1 | 3 | Configuration des modèles des documents : Reprise Ticket en… | Configuration Template des documents : Reprise Ticket en attente: Configuration des formats d'impression des documents émis en caisse: - format d'impression : ticket - organisation du contenu d'impression : # Titre # date d'impression # N° ticket # N° ticket de référence |
| `BO-03-03-08` | P1 | 3 | Configuration des modèles des documents : Avoir | Configuration des formats d'impression des documents émis en caisse : Avoir - format d'impression : ticket - Forçage impression (Si impression conditionnée ACTIVE) - organisation du contenu d'impression : # Titre # Code-barres # N° code-barres # date d'expiration # date d'impression # N° unique |
| `BO-03-03-09` | P1 | 3 | Configuration des modèles des documents : Acompte | configuration Template des documents : Acompte Configuration des formats d'impression des documents émis en caisse: - format d'impression : ticket - Forçage impression (Si impression conditionnée ACTIVE) - organisation du contenu d'impression : # Titre # Code-barres # N° code-barres # Nom client :… |
| `BO-03-03-10` | P1 | 3 | Configuration des modèles des documents : de PAIEMENT… | configuration des Template utilisé dans le cadre des Moyen de Paiement Configuration des formats d'impression des documents associé à un moyen de paiement: - format d'impression : ticket additionnel au ticket principal - organisation du contenu d'impression : # entête ticket # Paiement total FID :… |
| `BO-03-03-11` | P1 | 3 | Configuration des modèles des documents : de PAIEMENT CARTE… | configuration des Template utilisé dans le cadre des Moyen de Paiement Configuration des formats d'impression des documents associé à un moyen de paiement: - format d'impression : ticket additionnel au ticket principal - Forçage impression (Si impression conditionnée ACTIVE) - organisation du… |
| `BO-03-03-12` | P1 | 3 | Configuration des modèles des documents : de PAIEMENT… | Configuration des Template utilisé dans le cadre des Moyen de Paiement Configuration des formats d'impression des documents associé à un moyen de paiement: - format d'impression : ticket additionnel ou associé ticket principal - organisation du contenu d'impression du ticket monétique # % ticket… |
| `BO-03-03-14` | P1 | 3 | Paramétrage LOGO TICKET EPSON | Configuration des LOGO Ticket pour les imprimantes EPSON - format d'impression : imprimante Epson TMH6000 (IV et V) Le format d'impression du logo doit être dynamique en fonction de l'imprimante (définition) |
| `BO-03-03-15` | P1 | 3 | Paramétrage LOGO TICKET PAYS/ENSEIGNE/CONCEPT | Configuration des LOGO Ticket par Pays /Enseigne Configuration /importation des logo par PAYS / ENSEIGNE / CONCEPT - Nombre de LOGO minimum 10 |
| `BO-03-03-17` | P1 | 3 | Paramétrage TICKET - ligne Articles Tri par nomenclature | Configuration Template d'impression des Tickets: Option de pouvoir regrouper les lignes articles par Nomenclature, choix possible du niveau de nomenclature à appliquer, avec option sur l'ordre alphabétique du libellé de la nomenclature |
| `BO-03-03-18` | P2 | 3 | Paramétrage TICKET - ligne Articles Séparation ou tri sur… | Configuration Template d'impression des Tickets: Option de pouvoir séparer les lignes articles sur une valeur déterminée d'un attribut au choix, ou bien de faire de cet attribut un critère de tri, avec option : - sur l'ordre alphabétique du libellé de l'attribut - sur l'isolement en début ou en fin… |
| `BO-03-03-19` | P1 | 3 | Paramétrage TICKET - ligne Articles EAN | Configuration Template d'impression des Tickets: Option de pouvoir ajouter l'ean sur les lignes articles - format d'impression : ticket - organisation du contenu d'impression : # ligne article avec ajout de l'EAN de vente Dans le cas où la fonctionnalité BO-10-02-34 « code article ticket » est… |
| `BO-03-03-20` | P1 | 3 | Paramétrage TICKET - Duplicata | Configuration Template Ticket gestion des réimpression ticket - format d'impression : ticket - organisation du contenu d'impression : # affichage mention Duplicata sur les tickets ayant déjà fait l'objet d'une demande d'impression physique. |
| `BO-03-03-21` | P1 | 3 | Paramétrage TICKET - Attributs Articles Légale Belgique | Configuration Template Ticket gestion des Attributs articles : impression des attributs optionnelles. - format d'impression : ticket - organisation du contenu d'impression : # Option d'impression attribut spécifique articles du tickets Les attributs liés à l'article ne doivent pas être imprimés sur… |
| `BO-03-03-22` | P1 | 3 | Paramétrage TICKET - Remise/Rabais Article | Configuration Template Ticket : impression des remises/rabais articles sous les lignes articles. - format d'impression : ticket - organisation du contenu d'impression : # impression des remises/rabais articles sous les lignes articles. # impression ligne Remise / Rabais Total sous la ligne Total |
| `BO-03-03-23` | P1 | 3 | Paramétrage TICKET - Promotions Articles | Configuration Template Ticket : impression des lignes promotions articles sous les articles concernés - format d'impression : ticket - organisation du contenu d'impression : # ligne promotion sous l'article concerné par la promotion |
| `BO-03-03-24` | P1 | 3 | Paramétrage TICKET - Remises Immédiates | Configuration Template Ticket : impression du Total Remises IMMEDIATES - format d'impression : ticket - organisation du contenu d'impression : # impression du Total remises immédiates |
| `BO-03-03-25` | P1 | 3 | Paramétrage TICKET - Avantages Fidélité | Configuration Template Ticket : impression des lignes avantages Fidélité regroupé par type d'avantage - format d'impression : ticket - organisation du contenu d'impression : # zone d'avantage carte : liste des avantages regroupé par type d'avantage - avantage produits - avantage tickets - …. |
| `BO-03-03-26` | P1 | 3 | Paramétrage TICKET - Message 2 lignes Fidélité | Les messages fidélité tiers Animco supportent aujourd'hui 2 formats de messages : - avantage message 2 lignes - avantage message commercial 10 lignes Les messages 10 lignes sont de type commercial et permettent de véhiculer des messages longs en fin de section fidélité sur le ticket. Les messages 2… |
| `BO-03-03-27` | P1 | 3 | Paramétrage TICKET - Messages commerciaux 10 lignes Fidélité | Configuration Template Ticket : impression de l'avantage message commercial Fidélité 10 lignes (possibilité d'avoir 5 messages commerciaux) Configuration des formats d'impression des documents émis en caisse: - format d'impression : ticket - organisation du contenu d'impression : # zone d'avantage… |
| `BO-03-03-28` | P1 | 3 | Paramétrage TICKET - Message FID NON PORTEUR | Configuration Template Ticket : impression d'un message générique en cas d'absence de carte Fid dans la transaction Configuration du format d'impression des ticket émis en caisse pour les NON porteurs (absence de carte de fidélité): - format d'impression : ticket - organisation du contenu… |
| `BO-03-03-29` | P1 | 3 | Paramétrage TICKET - Message FID fidélité Offline | Configuration Template Ticket : impression d'un message générique en cas d'indisponibilité Fid Configuration des formats d'impression des documents émis en caisse: - format d'impression : ticket - organisation du contenu d'impression : # carte fidélité : %N° carte% # message générique : Fidélité… |
| `BO-03-03-30` | P1 | 3 | Paramétrage TICKET - Message FID NON PORTEUR - Fidélité… | Configuration Template Ticket : impression d'un message générique pour les non porteurs de carte de Fidélité en cas d'indisponibilité Fid |
| `BO-03-03-31` | P1 | 3 | Paramétrage TICKET - Soldes Fidélité | Configuration Template Ticket : impression des soldes : - format d'impression : ticket - organisation du contenu d'impression : # zone d'avantage carte : soldes carte fidélité - solde disponible - total avantages jour - montant utilisé |
| `BO-03-03-32` | P1 | 3 | Paramétrage TICKET - Carte Fidélité | Configuration Template Ticket : impression N° carte de fidélité - format d'impression : ticket - organisation du contenu d'impression : # zone d'avantage carte : impression mention carte fidélité + N° carte fidélité |
| `BO-03-03-33` | P1 | 3 | Paramétrage TICKET - Avantages Fidélité Catégorie | Configuration Template Ticket : impression des lignes avantages Fidélité par catégorie Configuration des formats d'impression des documents émis en caisse: - format d'impression : ticket - organisation du contenu d'impression : # zone d'avantage carte : liste des avantages par catégorie |
| `BO-03-03-34` | P1 | 3 | Paramétrage TICKET - Soldes Fidélité | Configuration des formats d'impression des documents émis en caisse: - format d'impression : ticket - organisation du contenu d'impression : # zone d'avantage carte : soldes carte fidélité - solde disponible - total avantages jour - montant utilisé - Nouveau solde |
| `BO-03-03-35` | P1 | 3 | Paramétrage TICKET - Soldes Fidélité Portugal | Configuration des formats d'impression des documents émis en caisse: - format d'impression : ticket - organisation du contenu d'impression : # zone d'avantage carte : soldes carte fidélité - solde disponible - total avantages jour - montant utilisé - Soldes disponibles sur les différentes cagnottes |
| `BO-03-03-36` | P1 | 3 | Paramétrage TICKET DE SORTIE | Configuration des formats d'impression des documents émis en caisse: - format d'impression : ticket substitutif au ticket - organisation du contenu d'impression : # Titre # Code-barres # N° code-barres # Montant total TTC # Nombre d'articles # X articles les plus chers avec prix et quantité |
| `BO-03-03-37` | P1 | 3 | Paramétrage TICKET - Taux de TVA | Configuration Template Ticket: - format d'impression : ticket - organisation du contenu d'impression : # affichage du code du taux de TVA sur la ligne article |
| | | | **03 - 04 Assemblage des Template des documents imprimés en caisse** | |
| `BO-03-04-02` | P1 | 3 | Entête Documents caisse | Le modèle d'entête des documents doivent pouvoir être sélectionner selon les modèles d'entête disponible pour le format du document (format ticket, facturette, A4) |
| `BO-03-04-03` | P1 | 3 | Zone d'impression | Organisation des zones d'impressions des documents imprimés en caisse comme les factures, bon de livraison. Configuration du positionnement de la zone devant être imprimée en dynamique c’est-à-dire impression des zones les unes derrières les autres. Ou saisie du positionnement de la zone… |
| `BO-03-04-04` | P1 | 3 | Choix du modèle d'impression des zones | Définition des modèles d'impression pour chaque zone (entête, tva, ligne article, …): il doit être possible de pouvoir choisir le modèle de la zone devant être imprimé dans un document de type facture, bon de livraison et autres. configurer les modèles des zones d'impression: - entête - titre -… |
| `BO-03-04-05` | P1 | 3 | Visualisation du modèle d'impression | Visualiser les modèles d'impression des documents configurés |
| `BO-03-04-06` | P1 | 3 | Activation du document | Activer les documents pouvant être proposés et utilisés en caisse. L'activation du document rend ce dernier automatiquement disponible en caisse pour l'impression des Factures/Bon de Livraison |
| `BO-03-04-07` | P1 | 3 | Modèle éléments de documents | De manière générale, les différentes zones utilisées dans le cadre des impressions doivent pouvoir être définies et imprimées dynamiquement selon le contexte d'encaissement. Zone : - entête - corps - pieds |
| `BO-03-04-08` | P1 | 3 | Modèle entête/pied document | Il est attendu de pouvoir configurer plusieurs modèle d'entête et pied de document (plus de 10). Si une limite existe, merci de préciser le maximum dans les commentaires |
| `BO-03-04-09` | P1 | 3 | Création des documents pouvant être imprimés en caisse | Création des documents pouvant être imprimés en caisse |
| `BO-03-04-10` | P1 | 3 | Suppression des documents pouvant être imprimés en caisse | Suppression des documents pouvant être imprimés en caisse |
| `BO-03-04-12` | P1 | 3 | Configuration du nom du document devant être imprimé | Configuration du nom du document devant être imprimé |
| `BO-03-04-13` | P1 | 3 | Configuration du nom du document en cas de réimpression | Configuration du nom du document en cas de réimpression |
| `BO-03-04-14` | P1 | 3 | Configuration du la sortie d'impression du document | Configuration du la sortie d'impression du document configuration de la sortie d'impression document : imprimante "rouleau" caisse, imprimante caisse micr (facturette), imprimante réseau |
| `BO-03-04-15` | P1 | 3 | Ecran de Saisie | Configuration des écrans de saisie devant être renseignés et imprimés durant l'impression du document, exemple - Nom du client - adresse ….. |
| `BO-03-04-16` | P1 | 3 | Facture client en compte | Configuration du numéro de compte client obligatoire sur le document |
| `BO-03-04-17` | P1 | 3 | Données client : adresse | Configuration de la saisie obligatoire de l'adresse client sur le document |
| `BO-03-04-18` | P1 | 3 | Données client à valider | Configuration de la validation des données client existant en base avant impression du document Cela permet de pouvoir les corriger en caisse si l'hôtesse de caisse choisit le les modifier. ---- Cas d'usage: L'hôte de caisse recherche le client "xxx" L'hôte de caisse sélection un client parmit une… |
| `BO-03-04-19` | P1 | 3 | Numéro du document | Configuration de la référence du numéro de séquence du document avec maintien du nombre de séquences du document. Le numéro de document doit être unique, séquentiel par type de document |
| `BO-03-04-20` | P1 | 3 | Paramétrage sortie impression des documents | Configuration d'impression des documents Portugal sur le rouleau de caisse |
| `BO-03-04-21` | P1 | 3 | Paramétrage format des documents FS | Gestion et configuration des format de document FS - FS (avec N° du document unique par caisse/document/Point De Vente) |
| `BO-03-04-22` | P1 | 3 | Paramétrage format des documents PREFIXE | Gestion et configuration des format de document PREFIXE - zone de configuration préfixe des documents : FT/NC/RC Le préfixe permettant ici d'avoir un compteur de N° de document identifiable par type : FT , NC et RC |
| `BO-03-04-23` | P1 | 3 | Paramétrage ID documents | Gestion et configuration des ID document chaque FS /FT / RC pour Chaque caisse Point De Vente doit avoir son code unique par Année - zone de configuration ID documents N° du document unique par caisse/document/Point De Vente |
| `BO-03-04-24` | P1 | 3 | Paramétrage nom de document ORIGINAL | Gestion et configuration des format de document Objectif : Mise en place des documents spécifique au Pays : Paramétrage et Activation du nom de document ORIGINAL Mise en place des documents spécifique au Pays : - FS - FT : 3 impressions - NC : 2 impressions avec obligation de la saisie et… |
| `BO-03-04-25` | P1 | 3 | Paramétrage nom de document Duplicata | Gestion et configuration des format de document Paramétrage et Activation du nom de document DUPLICADO Mise en place des documents spécifique au Pays : - FS - FT : 3 impressions - NC : 2 impressions avec obligation de la saisie et impression du document de référence - RC: 1 impression |
| `BO-03-04-26` | P1 | 3 | Paramétrage nom de document Triple | Gestion et configuration des format de document Paramétrage et Activation du nom de document TRIPLICADO Mise en place des documents spécifique au Pays : - FS - FT : 3 impressions - NC : 2 impressions avec obligation de la saisie et impression du document de référence - RC: 1 impression |
| `BO-03-04-27` | P1 | 3 | Obligation réglementaire QRCODE / ATCUD | Impression du QRCODE et de l'ATCUD sur les documents émis en caisse Obligation légale du Portugal: ordonnance N°195/2020 Le code ATCUD est un code qui est délivré par l’administration Fiscale au Point De Vente. Le contenu et format du QRCODE sont spécifiés dans les spécifications fiscales du… |
| `BO-03-04-28` | P1 | 3 | Définition du code des documents par type et par caisse | Chaque FS /FT / RC pour Chaque caisse Point De Vente doit avoir son code unique par Année |
| | | | **03 - 05 Personnalisation des documents** | |
| `BO-03-05-01` | P1 | 3 | Configuration entête Ticket | Fonctionnalité permettant de mettre à jour l' entête de ticket. Le nombre de lignes de saisie doit être minimum de 10. L’ergonomie de ces mises à jour doit être aisée. Il doit être possible de pouvoir centrer un champ, de justifier la ligne à gauche ou à droite, de mettre en gras certains mots, de… |
| `BO-03-05-02` | P1 | 3 | Configuration entête Facture | Fonctionnalité permettant de mettre à jour l' entête Facture Le nombre de lignes de saisie doit être minimum de 10. L’ergonomie de ces mises à jour doit être aisée. Il doit être possible de pouvoir centrer un champ, de justifier la ligne à gauche ou à droite, de mettre en gras certains mots, de… |
| `BO-03-05-03` | P1 | 3 | Configuration pied Ticket | Fonctionnalité permettant de mettre à jour le pied de ticket Le nombre de lignes de saisie doit être minimum de 10. L’ergonomie de ces mises à jour doit être aisée. Il doit être possible de pouvoir centrer un champ, de justifier la ligne à gauche ou à droite, de mettre en gras certains mots, de… |
| `BO-03-05-04` | P1 | 3 | Configuration pied Facture | Fonctionnalité permettant de mettre à jour le pied de facture Le nombre de lignes de saisie doit être minimum de 10. L’ergonomie de ces mises à jour doit être aisée. Il doit être possible de pouvoir centrer un champ, de justifier la ligne à gauche ou à droite, de mettre en gras certains mots, de… |
| `BO-03-05-05` | P1 | 3 | Configuration des lignes entête / pied Ticket | Configuration entête / pied de ticket: cette fonctionnalité permet de configurer les lignes d'impression des entêtes de ticket : - activer/désactiver la ligne - centrer la ligne - justifier à gauche la ligne - mise en gras de la ligne Les lignes désactivées ne doivent pas donner lieu à une… |
| `BO-03-05-06` | P1 | 3 | Configuration des lignes entête / pied Facture | Configuration entête / pied de facture: - activer/désactiver la ligne - centrer la ligne - justifier à gauche la ligne - mise en gras de la ligne Les lignes désactivées ne doivent pas donner lieu à une impression |
| `BO-03-05-07` | P1 | 3 | Visualisation des lignes entête / pied Ticket/Facture | Visualisation entête / pied de Ticket/ facture. cette fonctionnalité permet de visualiser les lignes d'impression des entêtes et pied de ticket/facture. |
| `BO-03-05-08` | P2 | 3 | Paramétrage pied de ticket / facture | Ajout de ligne permettant une impression d'un QRCODE Pouvoir configurer des impressions pied de ticket/facture au format QRCODE. Elément pouvant être imprimé en pied de ticket et ou facture (selon impression vers la sortie rouleau ticket) ----- Cas d'usage : En tant que directeur de magasin ou… |

---

# Lots conditionnels

Ces lots ne sont pas hors plan, ils sont **hors décision** : chacun est un
domaine fonctionnel entier absent de la suite, qu'on ne commence pas parce
qu'il manque un écran mais parce qu'il faut d'abord dire si on le construit.
La question n'est pas « quand », elle est « si ».

## Lot A — Clients en compte et crédit client

**41 exigences** (38 P1) · **4 à 5 sessions** · dépend de : —

*Objectif.* Le référentiel des clients professionnels (numéro, civilité, coordonnées, SIRET, TVA intracommunautaire, blocage, plafond, échéance, encours), le paiement par crédit client, la facture et le bon de livraison. Rien n'est réutilisable : imfid gère des cartes pseudonymes.

| Code | P | État | Sous-fonctionnalité | Attendu |
|---|---|---|---|---|
| | | | **02 - 04 Référentiel clients** | |
| `BO-02-04-01` | P1 | 3 | Intégration clients en compte : Numéro | Intégration des n° de client en compte en provenance de la Gestion Commerciale |
| `BO-02-04-02` | P1 | 3 | Intégration clients en compte : - Gestion des civilités | Intégration de la civilité du client en compte en provenance de la Gestion Commerciale |
| `BO-02-04-03` | P1 | 3 | Intégration clients en compte : Coordonnées | Intégration des coordonnées client en provenance de la Gestion Commerciale |
| `BO-02-04-04` | P1 | 3 | Intégration clients en compte : - N° Siret | Intégration du N° de SIRET en provenance de la Gestion Commerciale |
| `BO-02-04-05` | P1 | 3 | Intégration clients en compte : - N° TVA Intracommunautaire | Intégration du N° TVA Intracommunautaire en provenance de la Gestion Commerciale |
| `BO-02-04-06` | P1 | 3 | Intégration clients en compte : - Compte client bloqué | Intégration du blocage client au passage caisse en provenance de la Gestion Commerciale |
| `BO-02-04-07` | P1 | 3 | Intégration clients en compte : - Plafond crédit | Intégration du plafond crédit autorisé en provenance de la Gestion Commerciale |
| `BO-02-04-08` | P1 | 3 | Intégration clients en compte : - Date d'échéance | Intégration de la date d'échéance du client en provenance de la Gestion Commerciale. La date d'échéance doit obligatoirement être ajoutée sur les factures/bon de livraison émis en caisse |
| `BO-02-04-09` | P1 | 3 | Intégration clients en compte : - Remise/Rabais | Intégration du pourcentage de remise accordée en provenance de la Gestion Commerciale |
| `BO-02-04-10` | P1 | 3 | Intégration clients en compte : - Solde en cours | Intégration pour affichage du solde en cours du client en compte en provenance de la Gestion Commerciale: |
| `BO-02-04-11` | P1 | 3 | Intégration Libre | Réserve de 99 champs de données libres composés d'un libellé et d'une valeur alphanumerique |
| `BO-02-04-12` | P1 | 3 | Administration - Modification des données clients | La création d'un Client en Compte n'est pas autorisé dans le système d'encaissement par insertion manuelle. Seul l'adminstrateur Stime est autorisé à faire cette action au niveau du Back Office. Les informations de la fiche client sont accessibles et modifiables par les utilisteurs Backoffice. --… |
| `BO-02-04-13` | P1 | 3 | Administration - Mise à jour des données clients | Les informations modifiées des Clients en Compte dans le système d'encaissement au niveau Backoffice ou ligne de caisse doivent être remontées dans la Gestion Commerciale |
| `BO-02-04-14` | P1 | 3 | Affichage dernier mouvement client | Affichage de la date du dernier passage (paiement crédit client) avec le montant -- - En tant qu'utilisateur de BO Lors de la sélection d’un client en compte (ou du rattachement de la transaction en cours à un client en compte), je souhaite que le système affiche la date du dernier paiement… |
| `BO-02-04-15` | P2 | 3 | Codes Postaux : Import. Liste Codes Postaux | La liste des codes postaux devra pouvoir être importée à l'échelle nationale / enseigne / point de vente à partir d'une API et/ou d'un fichier externe. Cette donnée permettant ainsi de simplifier la saisie en caisse des adresses clients.. |
| `BO-02-04-16` | P2 | 3 | Codes Postaux : CRUD | Il devra être possible de créer une liste de codes postaux avec Données requises : code postal, localisation -- En tant qu’administrateur de la solution / Adhérent / tout autre profil habilité, Je souhaite créer et mettre à jour la liste des codes postaux associés à la localisation de chaque… |
| `BO-02-04-18` | P1 | 3 | Gestion du numéro fiscal (NIF) | Visualisation/extraction des clients via NIF -- En tant qu’adhérent, directeur de magasin, comptable, membre de l'équipe Etude et encaissement de ou tout autre profil habilité, Je souhaite pouvoir visualiser et extraire, via le back-office, la liste de tous les clients disposant d’un NIF, afin de… |
| `BO-02-04-19` | P1 | 3 | Administration : - Information Documents | Visualisation / export des Factures imprimées en caisse : FS / FT /RC avec gestion par Filtre NIF et … -- En tant qu’adhérent, directeur de magasin, comptable,membre de l'équipe Etude et encaissement ou tout autre profil habilité, Je souhaite Pouvoir visualiser et extraire, via le back-office, tous… |
| `BO-02-04-20` | P1 | 3 | Gestion du client en compte - Intégration des clients avec… | Intégration des fiches client en compte avec le NIF spécifiquement pour les clients Pays Portugal en provenance de la Gestion Commerciale dans l'encaissement. Données clients pouvant être transmises durant les fins de période ou en cours de journée par action Point De Vente de vente: |
| `BO-02-04-21` | P2 | 3 | Codes Postaux : Import Liste Portugal | La liste des codes postaux devra pouvoir être importée à partir d'un fichier externe Le format des codes postaux est propre au pays, au Portugal, il (código postal) est formé de quatre chiffres, un trait d'union, puis trois chiffres, suivis d'un emplacement postal de 25 caractères maximum en… |
| `BO-02-04-22` | P1 | 3 | Consultation des Clients créés en caisse et client en compte | Consultation des Clients créés en caisse et clients en compte -- En tant qu’adhérent, directeur de magasin ou tout autre profil habilité, je souhaite pouvoir consulter et extraire la liste des clients professionnels (clients en compte créés dans le système de gestion commerciale et importés dans le… |
| `BO-02-04-23` | P1 | 3 | Consultation des données clients en compte | Consultation des données clients en compte issus de la Gestion Commerciale |
| | | | **10 - 04 Client** | |
| `BO-10-04-01` | P1 | 3 | Client - Activation création client volatil en caisse | Paramètre permettant d'autoriser la création de client "volatil":(de passage) en caisse |
| `BO-10-04-02` | P1 | 3 | Client - affichage Coordonnées Client (en compte ou… | Paramètre permettant d'activer l'affichage des coordonnées du client en caisse lorsque que le client est identifié |
| `BO-10-04-03` | P1 | 3 | Client - Mise à jour coordonnées en caisse | Paramètre permettant d'activer la mise à jour des coordonnées client en caisse. Enregistrement des modifications clients en base |
| `BO-10-04-04` | P1 | 3 | Client - Mise à jour coordonnées en caisse / enregistrement | Les modifications de coordonnées clients (en compte ou volatil) doivent être prise en compte pour l'impression et enregistrées en base |
| `BO-10-04-05` | P1 | 3 | Client - Création Client en Compte | Paramètre permettant de bloquer la création de compte client en caisse et au backoffice encaissement |
| `BO-10-04-06` | P1 | 3 | Client - identifiant client volatil | Le n° de client "volatil" créé en caisse doit être unique |
| `BO-10-04-07` | P1 | 3 | Client - Segment Client en Compte | Configuration du Segment client en compte pour la prise en compte de ces derniers dans le cadre de la mise en place de remise/rabais client en compte |
| `BO-10-04-08` | P1 | 3 | Client - Affichage hôte(sse) de caisse - remise Client en… | Paramètre permettant d'activer l'affichage de la remise du client en compte en caisse au scan/saisie de ce dernier |
| `BO-10-04-09` | P1 | 3 | Client - Moyen de paiement crédit Client en Compte | Paramètre permettant de définir le moyen de paiement associé au crédit client |
| `BO-10-04-10` | P1 | 3 | Client - Paiement partiel crédit Client en Compte | Paramètre permettant de configurer l'acceptation ou non de paiement partiel crédit client |
| `BO-10-04-11` | P1 | 3 | Client - Caisse offline | Permettre l'usage des fonctionnalités "Client" en caisse autonome "offline" : - réimpression facture /bon de livraison - recherche client - paiement crédit client |
| `BO-10-04-12` | P1 | 3 | Client - Alerte en mode Caisse offline | Configuration de l'alerte devant être affichée lors des appels de fonctionnalités client en caisse si cette dernière est offline (autonome) |
| `BO-10-04-13` | P1 | 3 | Client - Plafond crédit client | Configuration de l'alerte devant être affichée en caisse lorsque le plafond de crédit du client est dépassé |
| `BO-10-04-14` | P1 | 3 | Client - Extraction Eco-Taxe | Paramètre permettant d'intégrer dans les exportations de factures les Eco-Taxe associés à la transaction du paiement crédit client en compte |
| `BO-10-04-15` | P1 | 3 | Client - configuration du NIF par défaut | Paramètre permettant de définir le NIF par défaut devant être présenté en caisse avant validation et impression du ticket par l'hôte(sse) de caisse. Ce dernier peut être modifié avant l'impression avec le NIF du client s'il souhaite le communiquer |
| `BO-10-04-16` | P1 | 3 | Client - Libellé NIF par défaut | Configuration du libellé CONSUMIDOR FINAL devant être imprimé pour le NIF par défaut(999999990) |
| `BO-10-04-17` | P1 | 3 | Client - NIF ETRANGER | Configuration de la liste des codes pays à présenter en caisse pour la saisie du NIF client étranger car la saisie du NIF (Portugais) avant impression du ticket doit respecter une syntaxe (c’est-à-dire l'algorithme) |
| `BO-10-04-18` | P1 | 3 | Unicité NIF | Il ne doit pas être possible de créer un N° de client en base si le NIF du client existe déjà |
| `BO-10-04-19` | P1 | 3 | Facture client - date échéance | Configuration permettant d'activer l'ajout de la date d'échéance dans les extractions factures client en compte |

## Lot B — Rapports et états

**31 exigences** (19 P1) · **3 sessions** · dépend de : lot 4

*Objectif.* Les états de ventes, caissières, TVA, offres et clients, avec export Excel/CSV/PDF et restitution graphique. Après le lot 4, qui aura ouvert la donnée et posé les index.

| Code | P | État | Sous-fonctionnalité | Attendu |
|---|---|---|---|---|
| | | | **06 - 01 Gestion communes des rapports** | |
| `BO-06-01-01` | P1 | 3 | Rapport Excel | La totalité des rapports doivent être exportables en Excel |
| `BO-06-01-02` | P1 | 3 | Rapport csv | La totalité des rapports doivent être exportables en csv |
| `BO-06-01-03` | P1 | 3 | Rapport PDF | La totalité des rapports doivent être exportables en pdf |
| `BO-06-01-04` | P1 | 3 | Graphique | La totalité des affichages de rapports (hors liste) doivent être visualisables par graphique. |
| | | | **06 - 03 Rapports clients** | |
| `BO-06-03-01` | P3 | 3 | Sondage clients : création d'un questionnaire et affichage… | Message proprement dit - Numéro du message - Libellé de la question à afficher - Moment de l'affichage dans le process d'encaissement : début du ticket, fin du ticket ou déclenché par une fonction - Type de réponse : O/N, numérique, code postal, pas de réponse attendue Période d'affichage - Actif… |
| `BO-06-03-02` | P3 | 3 | Etat sondages clients | Données requises (exigence minimum) : Pour chaque réponse possible, nombre de clients ayant donné cette réponse, % de clients ayant donné cette réponse, montant total des ventes des clients ayant donné cette réponse, % des ventes de ces clients par rapport au montant total des ventes, panier moyen… |
| `BO-06-03-03` | P3 | 3 | Sondage codes postaux | Objectif : Il doit être possible de recueillir ponctuellement les codes postaux des clients en période de promo par exemple. Le rapport obtenu doit pouvoir être exporté sous Excel. Avoir la possibilité d'importer un fichier Excel dans le logiciel pour baser le sondage sur une liste de codes postaux… |
| `BO-06-03-04` | P2 | 3 | Liste des clients | Données requises : Numéro du compte client, Code Pays, Nom du client, Prénom du client, Adresse, Type d'adresse (principale ou facturation), Numéro de téléphone, Code TVA (ex. : cas d'une mairie), SIRET, Type de facturation (Montant, Pourcentage, code supplément), Profession Paramètres d'édition :… |
| `BO-06-03-05` | P1 | 3 | Liste des Factures imprimées en caisse | Rapport (liste) des factures / bon de livraison imprimés en caisse Données requises (exigence minimum) : Dates de la période concernée, date du rapport, Point de vente, Numéro de facture ou du bon de livraison, Prénom du client, Nom du client, Numero Client, Montant de la vente TTC, Montant de la… |
| | | | **06 - 04 Etats Ventes** | |
| `BO-06-04-01` | P1 | 3 | Etat Ventes par famille | Données requises (exigences minimum) : Par famille, libellé de la famille, Ventes Net (en €), % ventes nettes (par rapport aux ventes nettes du rayon), nombre de ventes, panier moyen, montant des remises, nombre de remises, montant des ventes (en €), montant des retours (en €), nombre de retours,… |
| `BO-06-04-02` | P2 | 3 | Etat Ventes par emplacement | Données requises (exigence minimum) : N° interne de l'article, Code EAN de l'article, Description de l'article, Code Famille, Prix de l'article, Quantité vendue, Total des ventes (€) Total général sur les quantités vendues et le total des ventes (€) Paramètres d'édition : plage horaire et plage de… |
| `BO-06-04-03` | P2 | 3 | Liste des Factures imprimées en caisse - Réimpression des… | Réimpression des Factures à partir du backoffice encaissement (avec ajout mention duplicata) |
| `BO-06-04-05` | P1 | 1a | Etat Ventes Magasin | Rapport des Ventes : Données requises (exigence minimum) : Première partie : Ventes Nettes (€), Ventes non march.(€), Paiement divers (€), Ventes imposables (€), Ventes brutes (€), TVA brute (€), TVA sur retours (en nombre de tickets et en €), TVA exonérée (en nombre et en €), Ventes mode école (en… |
| `BO-06-04-06` | P2 | 3 | Etat Productivité horaire | Données requises (exigence minimum) : Tranche horaire, Montant des ventes, % des ventes, Nombre de clients, Nb TPV ouverts, Montant moyen par TPV, Nombre de clients moyen par TPV, Montant moyen par client, Nombre d'articles. Total / moyenne des différentes colonnes pour l'ensemble de la journée.… |
| `BO-06-04-07` | P1 | 3 | Etat Ventes horaires | Données requises (exigence minimum) : Tranche horaire, Unités vendues et CA de la sélection d'articles choisie Paramètres d'édition : plage horaire et plage de dates Avec la possibilité de filtrer sur les critères articles suivants : - Plage d'EAN - Famille - Code EAN - sélection d'articles Et la… |
| | | | **06 - 06 Etats Caissières** | |
| `BO-06-06-01` | P1 | 1a | Etat des tickets en attente | Données requises (exigence minimum) : Date de mise en attente, n° TPV, N° caissière, Nom de la caissière, n° du ticket, montant du ticket, mode école (O/N) pour les tickets rappelés, ajouter la date, l'heure, le n° TPV, le n° du ticket de rappel (= nouveau numéro du ticket dès qu'il a été repris)… |
| `BO-06-06-02` | P1 | 3 | Etat profil des performances caissières | Données présentes (exigence minimum) : Premier partie : Pour chaque caissière avec un comparatif avec la moyenne du magasin : Durée totale de la session, nb de fermetures, nb de verrouillages manuels (action manuelle réalisée par l'hôte(sse) de caisse), nb de verrouillages automatiques (au bout de… |
| `BO-06-06-03` | P1 | 1a | Etat Caissière | Données requises (exigence minimum) : par caissière, - Nombre de tickets "non-vente" (= nb d'ouverture du tiroir), Nb de clients - Ventes (en €), Retours (en nombre et en €), Remise (en nombre et en €), Recette nette (en €) - Par moyen de paiement, Ventes (en €), Prélèvement (en €), Fond de caisse… |
| `BO-06-06-04` | P1 | 3 | Etat Annulation | Données requises (exigence minimum) : pour chaque hôte(sse) de caisse, type de transaction, libellé de l'article, montant, n° ticket, Date et heure, Nom du superviseur, Libellé de la raison, Signature Paramètres d'édition : journée en cours, période (jour, semaine, mois ou année), plages de dates -… |
| `BO-06-06-05` | P1 | 3 | Etat Remboursement | Données requises (exigence minimum) : pour chaque remboursement, date et heure, code de la caissière, n° de TPV, Type de retour (code et libellé - retour article (soit un ticket spécifique de remboursement ou au sein d'un ticket de caisse (montant déduit du total d'achat)), ticket retour (ticket ne… |
| `BO-06-06-06` | P1 | 3 | Etat Acomptes / Avoirs / Coupons / Bons | Données requises (exigences minimum) : pour chaque acompte/avoir/bon d'achat, le libellé du code-barre, le code de la caissière (émission), le n° du ticket (émission), le montant et la date d'émission, le code de la caissière (utilisation)et la date de l'utilisation Paramètres d'édition : Période… |
| `BO-06-06-07` | P1 | 3 | Etat des codes raison | Données requises (exigence minimum) : Date et heure, code de la caissière, prénom et nom de la caissière, code du type de code raison, libellé du type de code raison, code raison, libellé du code raison Paramètres d'édition : journée en cours, période (jour, semaine, mois ou année), plages de dates… |
| `BO-06-06-08` | P3 | 3 | Synthèse hebdomadaire caissière | Données requises (exigence minimum) : deux rapports existants sont actuellement utilisés pour réaliser le fichier de synthèse (l'état profil des performances caissières (repris ci-dessus) et la synthèse des paiements caissières (non repris car proche de l'état caissière en plus synthétique) /… |
| `BO-06-06-09` | P1 | 3 | Etat des règlements | Données requises (exigence minimum) : Type de mode de paiement, libellé du mode de paiement, Nombre et Valeur nette Total au groupe de moyens de paiement (chèques auto et chèques manuels dans le groupe chèque) et total du rapport Paramètres d'édition : journée en cours, période (jour, semaine, mois… |
| `BO-06-06-10` | P1 | 3 | Etat des forçages prix | Données requises (exigence minimum) : - si baisse de prix : Heure, emplacement(ou N° TPV), n° ticket, n° caissière, nom de la caissière, type de réduction, n° article, n° famille, libellé de l'article, prix au détail, prix réduit, quantité vendue à prix forcé, perte en espèce, % démarque - si… |
| `BO-06-06-11` | P2 | 3 | Suivi Carte Fidélité Caissière | Données requises (exigence minimum) : Par caissière et par Date, Nb total de clients, Nb clients fidélité, % de clients fidélité Paramètres d'édition : liste de caissière, journée ou plage de dates - niveau détaillé ou total Point De Vente |
| | | | **06 - 07 Etats TVA** | |
| `BO-06-07-01` | P1 | 3 | Etat TVA par nomenclature | Données requises (exigence minimum) : Code de la famille, libellé de la famille, code du taux de tva, libellé du taux de tva, ventes nettes (en €), quantité vendue, montant de la tva Rupture par rayon (afficher code et libellé du rayon) Paramètres d'édition : Niveau et périmètre concerné : des… |
| `BO-06-07-02` | P1 | 3 | Etat TVA des remises fidélité | Données requises (exigence minimum) : par code TVA, code TVA, Libellé du code TVA, Ventes TTC (en €), Montant TVA (€), Ventes HT (€), Règlement fidélité HT (en €), Règlement fidélité TTC (€) TVA fidélité (€) avec un total pour chaque colonne Paramètres d'édition : journée en cours, période (jour,… |
| `BO-06-07-03` | P3 | 3 | Suivi du remboursement de la détaxe | Données requises (exigence minimum) : Date, Nom du client, Montant à rembourser, Somme des montants des articles vendus avec un taux de TVA à 2,10%, Montant de la TVA pour les articles vendus avec un taux de TVA à 2,10%, Somme des montants des articles vendus avec un taux de TVA à 5,5%, Montant de… |
| | | | **06 - 08 Etats Offres** | |
| `BO-06-08-01` | P2 | 3 | Etat des ventes promotionnelles | Données requises (exigence minimum) : N° de la caissière, Nom de la caissière, Nombre de déclenchements, Montant Net des ventes, % du montant de la remise par rapport au montant Total Net des ventes Paramètres d'édition : journée en cours ou plage de dates - Sélection d'une, plusieurs ou toutes les… |
| `BO-06-08-02` | P2 | 3 | Etat des ventes articles promotionnels | Données requises (exigence minimum) : N° d'article, Libellé de l'article, N° de la promotion, Libellé de la promotion, quantité vendue, Montant Net des ventes, Montant de la remise, Financement AMONT (Oui ou Non) Paramètres d'édition : journée en cours ou plage de dates - Choix d'une sélection… |

## Lot C — Flux, connecteurs et providers

**40 exigences** (38 P1) · **4 sessions** · dépend de : lot 1

*Objectif.* Les flux sortants — chiffre d'affaires, livre de caisse, transactions, panels, vignettes, factures ERP — au fil de l'eau ou programmés, et la déclaration des fournisseurs de services.

| Code | P | État | Sous-fonctionnalité | Attendu |
|---|---|---|---|---|
| | | | **03 - 11 Provider** | |
| `BO-03-11-01` | P1 | 3 | Création / modification | Ajout de nouveau provider (fournisseur de service) dans l'optique d'utilisation des protocoles de ces derniers. |
| `BO-03-11-02` | P1 | 3 | Suppression | Suppression du/des fournisseur(s) de services |
| `BO-03-11-03` | P1 | 3 | Configuration du Type | Configuration du type du fournisseur de services |
| `BO-03-11-04` | P1 | 3 | Configuration des Fournisseurs de service | Configuration de mode d'échange de données des fournisseurs de services (IP/URL/Clé d'authentification, …) |
| `BO-03-11-05` | P1 | 3 | Type Produits dématérialisés - Selon les PROTOCOLES | Configuration du fournisseur de service associé à la vente du produits ainsi que le mode d'appel du protocole. - la phase d'appel du provider : # au scan produit # au total # à la fin du paiement Attention particulière sur le parcours en caisse --- cas d'usage : vente des produits démat,… |
| `BO-03-11-06` | P1 | 3 | Liste des Articles Dématérialisés | Rattachement de la liste des articles au type de Produits dématérialisés. Configuration du fournisseur de service/protocole associé à la vente du produits ainsi que le type protocole: - wynid - prosodie - ATOS - Nexo retailer - configuration des modèle d'impression associés aux produits démat: #… |
| | | | **11 - 01 Automatique** | |
| `BO-11-01-01` | P1 | 1a | Flux d'intégration - Émetteur | Connecteur permettant l'intégration et la synchronisation des données entre les systèmes (service commercial=> système d'encaissement) : - articles & attributs articles - nomenclature - client - tva |
| `BO-11-01-02` | P1 | 1a | Flux d'exportation - Destinataire | Connecteurs permettant de mettre les données à disposition pour une exploitation ultérieure: - Flux : Chiffre d'affaire - Flux : Livre de caisse : Liste des moyens de paiement - fichiers factures client … Le connecteur doit pouvoir être activé pour de la donnée en local et en central Voir les… |
| `BO-11-01-03` | P1 | 3 | Flux Chiffre d'affaire par date | Configuration d’un système d’exposition des données relatives au chiffre d’affaires par date. (afin de répondre au cas de cumul des transactions sur plusieurs journées). |
| `BO-11-01-04` | P1 | 3 | Flux Livre de caisse par date | Configuration d’un système d’exposition des données relatives au Livre de caisse (Liste des moyens de paiement) par date. (afin de répondre au cas de cumul des transactions sur plusieurs journées). |
| `BO-11-01-05` | P1 | 3 | Heure de découpage pour lisser les données par date. | Configuration d'une heure de bascule, appliquée conjointement aux données comptables exposées (tranca3 et tranFC) Afin de déterminer à partir de quelle heure les enregistrements de vente sont rattachés à la journée comptable en cours plutôt qu'à la journée précédente ou suivante. |
| `BO-11-01-06` | P1 | 3 | Facture Client | A l'échelle Pays / Enseigne / Point de Vente, activation des exports de données relatifs au facture client en compte Afin de transmettre ces données au système comptable pour le suivi de la facturation et des encours clients |
| `BO-11-01-07` | P1 | 3 | Catalina | Export via API des transations caisse vers le serveur CATALINA (via API) CF. Catalina Pos Web Service Interface V1.21.pdf |
| `BO-11-01-08` | P1 | 3 | Export fichiers CA par famille | Exposition quotidienne des données relatives au chiffre d'affaire par famille et par îlot de caisse et intégrant les montants des remises immédiates remboursées par l’amont |
| `BO-11-01-12` | P1 | 3 | Export facturation: clients en compte | Mise à disposition périodique à la comptabilité et à la gestion commerciale des factures de paiement par crédit client (pour les clients en compte). (structure des données au format fichier plat.dat contenant la civilité, les coordonnées, les lignes de facturation, écotaxe...) - clifact.dat:… |
| `BO-11-01-13` | P1 | 3 | Export mensuel conso Fidélité / TVA | Mise à disposition mensuelle des données des paiements de fidélité ventilées par TVA |
| `BO-11-01-14` | P1 | 3 | Export quotidien des transactions | Mise à disposition quotidienne (au format csv) des données de vente : liste des transactions detaillées (article / prix / total, promo,..) |
| `BO-11-01-17` | P1 | 3 | Fin de période : Transactions | Mise à disposition des données de synthèse des transactions générées lors de la clôture périodique (ex. fin de journée), afin de contrôler la complétude des tickets (ventes et non-ventes). |
| `BO-11-01-18` | P3 | 3 | Export mode de paiement GS1 | Exposition des données des paiements GS1 et scannés en caisse. (Prendre en considération les évolutions exigées concernant les identifiants GS1 (EX:DataMatrix)) Les bons scannés doivent pouvoir être gérés au niveau du burn (centralisation des bons) |
| `BO-11-01-19` | P1 | 3 | Export fréquentation panel (marketing scan) | Exposition des données relatives à des panels préconfigurés. : '- Scannels.DDD - Scvenmag.DDD - Tlogextr.DDD Zip des 3 fichiers en MS.Point De Vente.DDD.zip |
| `BO-11-01-20` | P1 | 3 | Export des vignettes en mode théorique et déclaratif | Mise à disposition des données relatives à la gestion des vignettes distribuées en caisse : - Nombre de vignettes théoriques distribuées : une formule à communiquer - Nombre de vignettes déclarées par la caissière, par caisse, par prise de poste.. -... |
| `BO-11-01-21` | P1 | 3 | Export : rapport de productivité de la caisse. | Exposition des données(KPI) relatives aux contrôles de la productivité de la caisse par caissière, par prise de poste, par Date… |
| `BO-11-01-22` | P1 | 3 | Exposition des Factures émises au cours de la journée | Exposition des factures émises en caisse à destination de l'ERP SAP Equipement Maison Afin de permettre l'intégration comptable et le suivi des encours clients dans SAP |
| `BO-11-01-23` | P1 | 3 | Export : Rapport de la ventilation des TVA sur les acomptes | Exposition, après chaque clôture de journée, la ventilation par taux de TVA des montants totaux des commandes de la journée. Afin de permettre le contrôle et la déclaration de la TVA collectée par l'ERP comptable |
| | | | **11 - 02 Au fil de l'eau** | |
| `BO-11-02-01` | P1 | 1a | Transmission en temps réel des données de vente unitaire… | Transmission en temps réel des données de vente (article, quantité, date/heure) vers la Gestion Commerciale Afin de garantir une mise à jour continue et fiable des niveaux de stock |
| `BO-11-02-02` | P1 | 1a | Mise à disposition des TICKETS | Exposition en temps réel, de l’ensemble des transactions de type ventes et événements, incluant les données des tickets (données techniques et image du ticket, compressée ou non), pour consommation par des applications tierces. - Vente - Retour - Ticket Mode école - Ticket en attente - Ticket… |
| `BO-11-02-03` | P1 | 3 | Ticket Monétique | Exposition en temps réel des tickets monétiques au fil de l’eau pour extraction ou consommation par une application tierce. |
| `BO-11-02-04` | P1 | 3 | DataTimeRealExport | Intégration avec des systèmes tiers et mise à disposition des données relatives aux transactions en caisse, pour exploitation et gestion par des outils externes tels que la vidéosurveillance, la file unique, etc. EX: fournisseur Anaveo pour le vidéosurveillance |
| `BO-11-02-05` | P1 | 1a | Tickets Healthcheck | Supervsion continue de l'exposition des données tickets avec un dispositif d’alerte et de reprise robuste, permettant la détection et la gestion automatique des erreurs en cas d’incident (sans perte de données) |
| `BO-11-02-06` | P1 | 3 | Borne de prix | Configuration d’un système d’exposition des données (Ex: Prix) à la demande entre un système tiers (borne de prix) et la base d’encaissement, conformément aux exigences du protocole du système tiers. |
| | | | **11 - 03 A la demande** | |
| `BO-11-03-01` | P1 | 3 | Exportation des données à la demande | Outil permettant aux utilisateurs de la Stime (Ex : Equipe support) de régénérer les données émis par le système d’encaissement : données CA, … |
| `BO-11-03-03` | P1 | 3 | Cash management | Intégration entre la caisse et le monnayeur pour un paiement en espèces automatisé: - Demande de paiement en espèces et attente de retour du monnayeur - gestion des apports - gestion des prélèvements - information niveau d'espèces |
| `BO-11-03-04` | P1 | 3 | Produits dématérialisés | Configuration du protocole d'échange entre la caisse et les fournisseurs de produits dématérialisés (les cartes et les coffrets Cadeaux). Le protocole de gestion des produits dématérialisés est actuellement configuré avec le protocole NEXO Retailer. Cas d'usage: En caisse lors de la vente d'un… |
| `BO-11-03-05` | P3 | 3 | Envoi EEG (1 à n EEG) | Intégration du système permettant la configuration et l’envoi des prix de vente vers les étiquettes électroniques. Il existe plusieurs fournisseurs d'étiquettes électroniques, un point de vente pouvant avoir plusieurs fournisseurs. (Flux soumis à activation) |
| | | | **11 - 04 Webservice** | |
| `BO-11-04-02` | P1 | 3 | SAFT - Webservice | Configuration et Remontée au fil de l'eau des transactions vers l'administration Fiscale |
| `BO-11-04-03` | P1 | 1a | Webservice e-Commerce | Exposition du moteur de valorisation au Ecommerce et intégration du retour statut intégration des transactions à l'échelle pdv au Ecommerce |
| `BO-11-04-04` | P1 | 1a | Webservice Fidélité - AR | Configuration/intégration de l'appel Fidélité pour valorisé avec les avantages fid dans le panier |
| `BO-11-04-05` | P1 | 1a | Webservice Fidélité - VT | Configuration/intégration de l'appel Fidélité pour envoyer le Panier et les données de Paiement au serveur de fidélité . Cette appel contient l’ensemble des informations paiements et est émise en synchrone |
| `BO-11-04-06` | P1 | 1a | Webservice Fidélité - GT | Configurationde l'appel fidélité GT permettant le renvoi des transactions n'ayant pas abouti lors des fin de tickets. |
| `BO-11-04-07` | P1 | 1a | Webservice Fidélité - VT2 | Configuration de l'appel fidélité VT avant l'impression du ticket afin d'avoir le solde de la carte en tenant compte de la transaction en cours. |

## Lot D — Gestion du coffre

**17 exigences** (17 P1) · **3 sessions** · dépend de : —

*Objectif.* Dépenses, recettes, monnaie, validation des mouvements, remise en banque, remise au coffre, inventaire, clôture, salariés, monnayeurs. Le comptage de caisse existe, le coffre non.

| Code | P | État | Sous-fonctionnalité | Attendu |
|---|---|---|---|---|
| | | | **05 - 02 Gestion du coffre** | |
| `BO-05-02-01` | P1 | 3 | Gestion des mouvements hors encaissement - les dépenses | Sortie d'espèces du Coffre pour effectuer un achat, un remboursement, un acompte salarié hors « caisses ». Les dépenses comportent soit aucune TVA, soit une seule TVA ou plusieurs TVA. |
| `BO-05-02-02` | P1 | 3 | Gestion des mouvements hors encaissement - les recettes -… | Réception manuelle de règlements hors « Caisses » Les réceptions comportent soit acucune TVA soit une seule TVA ou plusieurs TVA |
| `BO-05-02-03` | P1 | 3 | Gestion des mouvements hors encaissement - les recettes -… | Réception automatique de règlements hors « Caisses » Les réceptions comportent soit acucune TVA soit une seule TVA ou plusieurs TVA |
| `BO-05-02-04` | P1 | 3 | Gestion des mouvements hors encaissement - les demandes /… | Saisie d'une demande de monnaie pour impression (qui pourra être transmise à la banque) Rappel de la demande saisie pour enregsitrement de la réception |
| `BO-05-02-05` | P1 | 3 | Gestion des mouvements hors encaissement - réception monnaie | Saisie d'une demande de monnaie pour impression (qui pourra être transmise à la banque) Rappel de la demande saisie pour enregsitrement de la réception |
| `BO-05-02-06` | P1 | 3 | Gestion des mouvements hors encaissement - validation des… | Il s'agit de la possibilité de valider les différents mouvements qui ont été saisis pendant la période d'ouverture et de pouvoir réaliser des corrections avant validation |
| `BO-05-02-07` | P1 | 3 | Gestion des mouvements hors encaissement - la remise en… | Il s'agit de la possibilité de déclarer une remise en banque dans le logiciel (saisir un numéro de sac et un libellé), de générer un débit du coffre et d'éditer les pièces comptables associées ("bordereau espèces" et "versement espèce") |
| `BO-05-02-08` | P1 | 1a | Gestion des mouvements de caisse - l'historique des journées | L'historique des journées reprend le CA des caisses ventilé par moyens de paiement (espèces, CB, chèques, …) |
| `BO-05-02-09` | P1 | 1a | Gestion des mouvements de caisse - validation des… | Il s'agit de la possibilité de valider les différents mouvements réalisées par les caissières de manière détaillée (par moyen de paiement), y compris les apports et les prélèvements. A ce niveau, il doit également être possible de saisir des corrections ou transferts de règlements. |
| `BO-05-02-10` | P1 | 3 | Gestion des mouvements de caisse - la remise au coffre | Une fois les mouvements de caisse validés, l’utilisateur doit impérativement enregistrer les prélèvements dans la remise au Coffre. Cette opération consiste à créer une ou deux pochettes contenant les recettes des espèces de la journée comptable afin de remettre ultérieurement les fonds à la banque. |
| `BO-05-02-11` | P1 | 3 | Gestion des mouvements de caisse - l'inventaire du coffre | Il doit être possible de réaliser un inventaire du coffre et comparer l'inventaire physique du coffre avec les montants théoriques calculés par la gestion du coffre. |
| `BO-05-02-12` | P1 | 3 | Gestion des mouvements de caisse - lancement de la clôture… | Le lancement de la clôture du Coffre permet d’arrêter une période comptable après avoir pointé et validé les mouvements financiers de la période à clôturer. |
| `BO-05-02-13` | P1 | 3 | Gestion des mouvements de caisse - reporting | Il s'agit des reportings nécessaires à la bonne gestion du coffre. |
| `BO-05-02-14` | P1 | 3 | Gestion des profils donnant accès au coffre | Description des profils à créer pour gérer le coffre |
| `BO-05-02-15` | P1 | 3 | Gestion des salariés | La liste des salariés reprend les personnes (physiques ou morales) qui exercent une activité dans le magasin et qui peuvent bénéficier d'un acompte, d'une avance sur dépense, recevoir un remboursement, … |
| `BO-05-02-16` | P1 | 3 | Gestion des monnayeurs | Objectif : La gestion des monnayeurs doit permettre de connaître le montant disponible dans chaque monnayeur à partir du logiciel d’encaissement sans faire de requête au monnayeur (voir schéma et détail des flux dans le RFP au format Word) |
| `BO-05-02-17` | P1 | 3 | Transfert Règlement | Le transfert de règlement n'est possible que si le moyen de paiement source a bien été réalisé (contrôle du montant théorique enregistré sur le moyen de paiement) A partir de la gestion du coffre ou de la configuration des moyens de paiement il devra être possible de configurer quels moyens de… |

## Lot E — Omnicanalité et e-commerce

**43 exigences** (43 P1) · **5 à 6 sessions** · dépend de : lot 7

*Objectif.* La reprise en caisse des paniers venus d'ailleurs : e-commerce, self-scanning, qbusting, mobilité, commandes de gestion commerciale, bons de vente. Chacun avec sa règle de prix, son canal et son traitement des articles inconnus ou interdits.

| Code | P | État | Sous-fonctionnalité | Attendu |
|---|---|---|---|---|
| | | | **07 - 01 Intégration des commandes e-Commerce** | |
| `BO-07-01-01` | P1 | 3 | Configuration de l'activité d'intégration des ventes issues… | Intégration des ventes à l'échelon Point de vente issues des systèmes tiers tels que le E-Commerce, de la mobilité,.. Exemples de systèmes tiers : - E-Commerce - mobilité |
| `BO-07-01-02` | P1 | 3 | Mise à jour du CA E-Commerce | Les transactions issues du Ecommerce s'intègrent au chiffre d'affaires du PDV d'origine de la commande |
| `BO-07-01-03` | P1 | 3 | Déclenchement des promotions Carte FID Canal E-Commerce | Déclenchement Cagnottage FID lors de l'intégration de la commande E-Commerce |
| `BO-07-01-04` | P1 | 3 | Intégration des paiements E-Commerce | Mise à jour du livre de caisse avec les paiements e-Commerce |
| `BO-07-01-06` | P1 | 3 | Export SAFT sur les transactions E-Commerce | Les exports SAF-T mis à disposition de l'administration du Portugal doivent inclure les transactions ecommerce |
| `BO-07-01-07` | P1 | 3 | Intégration du NIF client pour les transactions E-Commerce | Les transactions de type ecommerce devront également prendre en compte le NIF client |
| `BO-07-01-08` | P1 | 3 | NIF Par défaut client pour les transactions E-Commerce | Le NIF par défaut des transaction ecommerce est le 999999990 tout comme en ligne de caisse. Si le NIF n'est pas renseigné par l'appel Ecommerce, la valeur prendra par défaut 999999990 |
| | | | **10 - 01 Omnicanalité** | |
| `BO-10-01-01` | P1 | 3 | eCommerce - Activation | Paramètre d'activation du E-Commerce en point de vente |
| `BO-10-01-03` | P1 | 3 | eCommerce - clôture panier | Retour au Ecommerce du résultat d'intégration de la commande dans l'encaissement : L'objectif d'intégration de la commande validée ecommerce est de permettre la mise à jour du chiffre d'affaires du PDV avec les transactions ecommerce. |
| `BO-10-01-04` | P1 | 3 | eCommerce - Retour ticket | Configuration du type de retour autorisé pour les retours ticket effectués par le E-Commerce |
| `BO-10-01-05` | P1 | 3 | eCommerce - Retour article | Configuration du type de retour autorisé pour les retours articles effectué par le E-Commerce |
| `BO-10-01-06` | P1 | 3 | eCommerce - Article inconnu | Mise en place d’une solution permettant d’identifier et de marquer les articles inconnus lors de la reprise d’une transaction e-commerce, sans bloquer l’intégration de la commande. (nécessaire dans le cas d'un référentiel article E-Commerce diffèrent de celui de la caisse) Ex de solution : fixer un… |
| `BO-10-01-07` | P1 | 3 | eCommerce - référence prix article | Paramètre permettant de définir si le prix des articles du panier E-Commerce doit être repris de la base de donnée Point De Vente ou de la transaction E-Commerce |
| `BO-10-01-08` | P1 | 3 | eCommerce - Îlot caisse | Définition de l'îlot de rattachement du process d'intégration des transactions e-Commerce: Rattacher les transactions de type e-Commerce à un îlots spécifique, cela permet d'appliquer les règles de l'îlot définit à cette transactions comme par exemple: - affection du CA transaction e-Commerce à… |
| `BO-10-01-09` | P1 | 3 | eCommerce - Moyen de paiement par défaut | Définir le moyen de paiement par défaut (dans la liste des moyens de paiement configurée) afin d'intégrer les commandes e-Commerce ayant un moyen de paiement inconnu du système d'encaissement |
| `BO-10-01-11` | P1 | 3 | eCommerce - archivage des commandes d'origine eCommerce | Paramètre permettant d'archiver les commandes d'origine du e-Commerce |
| `BO-10-01-13` | P1 | 3 | eCommerce - définition du canal | Définition du canal du E-Commerce devant être transmis au moteur de promotion (avantage carte et remise immédiate): - Point De Vente - WEB - autre |
| `BO-10-01-14` | P1 | 3 | eCommerce - article vente interdite | Configuration de la validation ou le rejet d'intégration des commandes E-Commerce ayant des articles de type vente interdite |
| `BO-10-01-15` | P1 | 3 | Self scanning - Activation | Paramètre d'activation du self scanning en point de vente |
| `BO-10-01-16` | P1 | 3 | Self scanning - mode | Configuration du mode de récupération des paniers de self-scanning |
| `BO-10-01-17` | P1 | 3 | Self scanning - Ajout article | Paramètre permettant de définir si l’ajout d’articles en caisse est autorisé avant ou après la récupération du panier de self-scanning |
| `BO-10-01-18` | P1 | 3 | Self scanning - Identifiant | Paramètre permettant de définir l'identifiant de reprise du panier self scanning (N° de client ou code-barres transaction) |
| `BO-10-01-19` | P1 | 3 | Self scanning - référence prix article | Paramètre permettant de définir si le prix des articles du panier self scanning doit être repris de la base de donnée Point De Vente ou de la transaction self scanning |
| `BO-10-01-20` | P1 | 3 | Self scanning - Coupon | Paramètre permettant de valider l’acceptation des coupons scannés dans les paniers de self-scanning ainsi que leur reprise en caisse. |
| `BO-10-01-21` | P1 | 3 | Self scanning - Article inconnu | Mise en place d’une solution permettant d’identifier et de marquer les articles inconnus lors de la reprise d’une transaction self-scanning, sans bloquer l’intégration de la commande. (nécessaire dans le cas d'un référentiel article self-scanning diffèrent de celui de la caisse) Ex de solution :… |
| `BO-10-01-22` | P1 | 3 | Self scanning - définition du canal | Définition du canal du self scanning devant être transmis au moteur de promotion(avantage carte et remise immédiate): - Point De Vente - WEB - autre |
| `BO-10-01-23` | P1 | 3 | Self-Scanning - SLS article vente interdite | Configuration de la validation ou le rejet d'intégration des transactions SLS en caisse si le panier SLS possède des articles de type vente interdite. En cas de blocage : la caisse affichera le Libellé de l'article ainsi que le message associé à l'article+ code de l'article en vente interdite |
| `BO-10-01-24` | P1 | 3 | Gestion Commerciale - Acceptation transaction Devis… | Configuration de la validation ou le rejet d'intégration des commandes Gestion commerciale ayant des articles de type vente interdite En cas de blocage : la caisse affichera le Libellé de l'article ainsi que le message associé à l'article+ code de l'article en vente interdite |
| `BO-10-01-25` | P1 | 3 | Gestion Commerciale - Commande/Devis | Configuration du mode de récupération des commandes depuis la gestion commerciale, incluant le mode de transmission des données et leur fonctionnement (fréquence, sens et mécanisme d'échange). Cas d'usage : récupération en caisse des commandes de gestion commerciale |
| `BO-10-01-26` | P1 | 3 | Gestion Commerciale - document impression automatique | Configuration du modèle de document devant être imprimé en automatique à la suite de l'encaissement d'une commande gestion commerciale Le document sera automatiquement imprimé suite à l'encaissement d'une commande gestion commerciale |
| `BO-10-01-27` | P1 | 3 | Gestion Commerciale - Gestion acompte | Configuration du moyen de paiement dans le cadre des acomptes perçus en encaissement de commande gestion commerciale |
| `BO-10-01-28` | P1 | 3 | Gestion Commerciale - définition du canal | Définition du canal du Gestion Commerciale devant être transmis au moteur de promotion (avantage carte et remise immédiate): - Point De Vente - WEB - autre |
| `BO-10-01-29` | P1 | 3 | Gestion Commerciale - référence prix article | Paramètre permettant de définir si le prix des articles du panier Commande doit être repris de la base de donnée Point De Vente ou de la commande |
| `BO-10-01-30` | P1 | 3 | Qbusting - mode | Configuration du mode de récupération des paniers Qbusting |
| `BO-10-01-31` | P1 | 3 | Qbusting - définition du canal | Définition du canal du Qbusting devant être transmis au moteur de promotion (avantage carte et remise immédiate): - Point De Vente - WEB - autre |
| `BO-10-01-32` | P1 | 3 | Qbusting - Article Interdit | Paramètre permettant d'autoriser ou non la vente d'article interdit en reprise transaction Qbusting. En cas de blocage : la caisse affichera le Libellé de l'article ainsi que le message associé à l'article+ code de l'article en vente interdite |
| `BO-10-01-33` | P1 | 3 | Qbusting - référence prix article | Paramètre permettant de définir si le prix des articles du panier Qbusting doit être repris de la base de donnée Point De Vente ou de la transaction Qbusting |
| `BO-10-01-34` | P1 | 3 | Mobilité - définition du canal | Définition du canal du process Mobilité (par exemple qbusting, ou encaissement mobile) devant être transmis au moteur de promotion (avantage carte et remise immédiate): - Point De Vente - WEB - autre |
| `BO-10-01-36` | P1 | 3 | Mobilité - référence prix article | Paramètre permettant de définir si le prix des articles du panier Mobilité doit être repris de la base de donnée Point De Vente ou de la transaction Mobilité |
| `BO-10-01-37` | P1 | 3 | Mobilité - article vente interdite | Configuration de la validation ou le rejet d'intégration des transactions Mobilité ayant des articles de type vente interdite |
| `BO-10-01-39` | P1 | 3 | Mobilité - Paiement "fictif" | Configuration du moyen de paiement devant être ajouté à la transaction Mobilité lors de son intégration dans le système d'encaissement si le total payé est inférieur au total du ticket (cas où une promotion a été déclenchée lors de la mobilité mais non déclenchée suite envoi de la transaction… |
| `BO-10-01-40` | P1 | 3 | Bon de vente - mode | Définir le mode de récupératin des paniers de type bon de vente. Un bon de vente est une commande client effectuer en rayon qui est valable uniquement le jour J. Les numéros de Bon de vente sont réinitialisés au quotidien, il sont repris en caisse via la saisie de ce numéro. |
| `BO-10-01-41` | P1 | 3 | Bon de vente - référence prix article | Paramètre permettant de définir si le prix des articles du panier Commande doit être repris de la base de donnée Point De Vente ou de la commande |

## Lot F — Fin de journée pilotée

**20 exigences** (15 P1) · **2 à 3 sessions** · dépend de : lots 1 et 6

*Objectif.* Ordonnancement de la fin de période, alertes et comptes à rebours, fermeture forcée, impression automatique des rapports, archivage. La clôture Z existe mais elle est manuelle et locale.

| Code | P | État | Sous-fonctionnalité | Attendu |
|---|---|---|---|---|
| | | | **03 - 12 Administration technique** | |
| `BO-03-12-02` | P1 | 3 | Paramétrage fin de periode Points de Vente | Gestion des actions de clôture Point De Vente intégré en paramètre. Automatisation de la fin de période avec gestion du calendrier d'ouverture du Point De Vente |
| | | | **09 - 01 Fin de journée automatique** | |
| `BO-09-01-04` | P1 | 3 | Fin de journée Automatique : caisse offline | Fin de journée automatique, caisses offline Alerte sur le backoffice avec affichage du compte à rebours Paramétrage du délai de compte à rebours Ces opérations doivent être enregistrées dans le journal électronique |
| `BO-09-01-05` | P1 | 3 | Fin de journée Automatique : Ticket en attente | Fin de journée automatique, tickets en attente Alerte sur le backoffice avec affichage du compte à rebours Enregistrement de la totalité des tickets en attente (Cloture avec des tickets en attente) Ces opérations doivent être enregistrées dans le journal électronique |
| `BO-09-01-06` | P2 | 3 | Fin de journée Automatique - caisse ouverte | Fin de journée automatique, caisses ouvertes Alerte sur le Back Office avec affichage d'un compte à rebours Déclenchement de la fermeture forcée des caisses ouvertes (avec impression en caisse d'un ticket technique) Ces opérations doivent être enregistrées dans le journal électronique |
| `BO-09-01-07` | P1 | 3 | Programmation Automatisation | Ordonnancement de la fin de période : Determiner l'horaire et activer l'automatisation le jour choisi de fin de semaine de la semaine, du mois ou de l'année |
| `BO-09-01-08` | P1 | 3 | Contrôle Tickets en Attente et RAZ | Fin de journée sur les caisses - Ticket en attente Alerte sur Back Office avec liste des tickets en attente avec affichage du compte a rebours Raz Ticket en attente Ces opérations doivent être enregistrées dans le journal électronique |
| `BO-09-01-09` | P1 | 3 | Forçage fermeture | Fermeture caisse forcée si ouverte Alerte sur le backoffice Ces opérations doivent être enregistrées dans le journal électronique |
| `BO-09-01-10` | P1 | 3 | Impression des rapports backoffice | Déclenchement automatique Impression des rapports programmés en fin de période : - Etat vente magasin - Etat caissière (synthétique & détaillé) - Etat règlements |
| `BO-09-01-11` | P1 | 3 | Programmation Rapports Back Office | Sélection des Rapports à éditer automatiquement - format |
| | | | **09 - 02 Fin de journée manuelle** | |
| `BO-09-02-01` | P2 | 3 | Fin de journée manuelle - Alerte ticket en attente | Fin de journée manuelle, tickets en attente Alerte sur le backoffice avec affichage du compte à rebours Enregistrement de la totalité des tickets en attente Ces opérations doivent être enregistrées dans le journal électronique |
| `BO-09-02-02` | P2 | 3 | Fin de journée manuelle - Alerte caisse offline | Fin de journée manuelle, caisses offline Alerte sur le backoffice avec affichage du compte à rebours Paramétrage du délai de compte à rebours Ces opérations doivent être enregistrées dans le journal électronique |
| `BO-09-02-03` | P2 | 3 | Fin de journée manuelle - Alerte caisse ouverte | Fin de journée manuelle, caisses ouvertes Alerte sur le Back Office avec affichage compte à rebours Déclenchement de la fermeture forcée des caisses ouvertes (avec impression en caisse d'un ticket technique) Ces opérations doivent être enregistrées dans le journal électronique |
| `BO-09-02-04` | P2 | 3 | Points d'entrée technique | Lancement des points d'entrée traitements avant ou après Fin de journée (script) Mise à dispo. de point d'entrée technique (scripts) afin d'exécuter des besoins spécifique avant ou après la fin de période |
| | | | **09 - 03 Paramétrage** | |
| `BO-09-03-01` | P1 | 3 | Gestion des utilisateurs caisses - Déclaration automatique… | configuration des hôte(sse)s de caisse rattachées au îlots Caisse Libre-Service en déclaration automatique de fin de période |
| `BO-09-03-02` | P1 | 3 | Archivage Fin de Période | Configuration de l'archivage Configurer le nombre de jours de rétention pour chaque type de données ou d'édition |
| `BO-09-03-03` | P1 | 3 | Fin de période Automatique - Activation | Activation du lancement de fin de période en automatique |
| `BO-09-03-04` | P1 | 3 | Fin de période - Réinitialisation compteur caisse | Paramètre d'Activation de la réinitialisation du compteur transaction caisse à 1 après chaque fin de période |
| `BO-09-03-05` | P1 | 3 | Fin de période - Vérification caisse | Paramètre permettant d'activer le contrôle des données caisse vs serveur durant la fin de période. En cas d'écarts de données une synchro caisse est effectuée |
| `BO-09-03-06` | P1 | 1a | Fin de période - Ticket en attente | Paramètre permettant de choisir quel type d'action devra être appliquée en cas de présence ticket en attente en caisse : - Pas d'alerte - Alerte seulement - Erreur : arrêt de la fin de période |
| `BO-09-03-07` | P1 | 3 | Fin de période Automatique - Délai Fermeture caisse | Configuration du délai de fermeture caisse avant lancement de la fin de période automatique. Par exemple, 5 mn avant la fin de période automatique si une caisse est ouverte sans activité cette dernière sera automatiquement fermée |

## Lot G — Fiscal Portugal

**12 exigences** (12 P1) · **3 à 4 sessions** · dépend de : lot 8

*Objectif.* SAF-T manuel et au fil de l'eau, ATCUD et QR-code, numéro de certification, préfixes et compteurs FS/FT/NC/RC, éco-taxes. Après le lot 8 : ce sont pour l'essentiel des mentions imprimées.

| Code | P | État | Sous-fonctionnalité | Attendu |
|---|---|---|---|---|
| | | | **05 - 01 Fiscal** | |
| `BO-05-01-01` | P1 | 3 | Mise à disposition de la Base de Donnée / transactions /… | TaxAuditExtracts_XXXXX_yyyymmdd.zip TaxAuditReports_XXXXX_yyyymmdd.zip |
| `BO-05-01-02` | P1 | 3 | E-invoicing | Exigence légale 2024-2026 |
| `BO-05-01-03` | P1 | 3 | E-reporting | Exigence légale 2024-2026 |
| `BO-05-01-04` | P1 | 3 | SAFT Portugal = Portaria 48/2020 : Transactions mensuelles… | Outil d'Extraction du fichier fiscal Saf-T par le Point De Vente: Mise à disposition du Point de vente d'un outil leur permettant de générer les fichiers SAF-T afin de les transmettre à l'administration fiscale. L'extraction est effectuée par le Point De Vente avant le 25 du mois pour le mois… |
| `BO-05-01-05` | P1 | 3 | SAFT Portugal = Portaria 48/2020 : Transactions mensuelles… | Mise à dispo des données SAF-T au fil de l'eau des encaissement en Point De Vente via appel API (spécification technique Portugal) (cf. Flux webservice) |
| `BO-05-01-06` | P1 | 3 | Export Transactions | Mise à dispo de l'ensemble des Ticket/Facture sur 12 ans en ligne pour le Point De Vente Outil de visualisation/extraction/impression de l'ensemble des tickets et facture émis en Point De Vente avec filtre : - NIF client, - date début - date fin, - Type de doocument (FS, FT, NC, RC) - N° de client… |
| | | | **10 - 06 Fiscal** | |
| `BO-10-06-01` | P1 | 3 | SAF-T - FS | Configuration du préfixe et du code du document vente ticket Portugal. Exemple: Préfixe = FS Identifiant = 1 |
| `BO-10-06-02` | P1 | 3 | SAF-T - NC | Configuration du préfixe et du code du document vente ticket Portugal. Exemple: Préfixe = NC Identifiant = 2 |
| `BO-10-06-03` | P1 | 3 | SAF-T - N° Certification | Configuration du N° de certificat Fiscal Portugais Ce dernier est imprimé sur l'ensemble des documents de vente/retour caisse et est également présent dans l'exportation saf-t |
| `BO-10-06-04` | P1 | 3 | TVA | Activation de l'impression par Taux du tableau de TVA ticket Avoir dans le tableau de récapitulatif TVA du ticket le détail de tva par taux |
| `BO-10-06-05` | P1 | 3 | Eco-Taxe | Configuration des attributs dynamiques en Eco-taxe (minimum 5 eco-taxes) |
| `BO-10-06-06` | P1 | 3 | Eco-Taxe - impression | Configuration du choix d'impression oui / non de chaque écotaxe sur le ticket |

---

*Backlog établi le 31/08/2026 à partir de la cotation complète de l'onglet
Back Office. Toute révision doit conserver la partition : 796 exigences,
un lot chacune, contrôle de somme publié.*


---

# Campagne câblage — les partiels atteignables depuis le code

Ajoutée le 11/09/2026. Cette partie est une **seconde coupe** des mêmes 796
exigences, orthogonale aux 13 lots ci-dessus : elle ne regroupe pas par épic,
elle regroupe par **atteignabilité depuis le code**.

Elle applique la leçon la plus chère de la campagne du 31/08 : *on ne
dimensionne pas un lot d'administration depuis le texte de l'exigence,
seulement depuis le code*. Les 292 exigences P1 cotées `1a` ont donc été
relues une par une contre les sources, chacune avec un `fichier:ligne`
réellement ouvert, et rangées en quatre verdicts :

| Verdict | Lignes | Sens |
|---|---|---|
| **Câblable** | 97 | Le comportement existe et un point de configuration l'atteint en 2 ou 3 sites, sans changer de signature publique ni toucher au chemin fiscal. |
| **Figé** | 68 | Le comportement existe mais n'est pas atteignable : littéral répété, objet d'état sans accès aux réglages, valeur figée dans une signature. Refactor, hors campagne. |
| **Fiscal** | 19 | Chemin fiscal, sécurité ou authentification. Non gatable sans instruction séparée. |
| **Inexistant** | 107 | Le comportement n'existe pas. Construction, pas câblage. |

Un tiers exactement est câblable, et **28 des 97 ne coûtent aucune ligne de
code** : elles sont déjà administrées, et cotées `1a` faute d'avoir été
vérifiées. D'où le lot C0, qui ne construit rien.

Trois constats structurels sortis de l'audit, à ne pas redécouvrir :

- **Les codes-barres sont le pire bloc, pas le meilleur** : zéro câblable sur
  26. Tout passe par `CouponType`, dont le javadoc admet n'avoir aucun écran
  d'administration, et le reste du décodage est un parseur à largeur fixe aux
  offsets en dur.
- **Le ticket n'est pas un système de zones** : `TicketPrinterService` est une
  concaténation de chaînes dans une méthode. Les 22 lignes de template de
  documents donnent 3 câblables, toutes déjà couvertes.
- **Les réglages sont portés par magasin, jamais par caisse.** `PosSetting`
  n'a pas de colonne terminal et `EchelonLevel` documente que l'héritage
  s'arrête au PDV. Les 13 lignes qui demandent une configuration par caisse ou
  par îlot sont inexistantes par construction, et le resteront tant que cet
  arbitrage n'est pas rendu.

## Lot C0 — Vérification sans code

**28 exigences**, toutes déjà administrées ou déjà couvertes. *Aucune ligne de
`src/main` ne doit être modifiée par ce lot.*

*Objectif.* Rouvrir chaque ligne, vérifier au `fichier:ligne` que le
comportement est bien administré de bout en bout, et le prouver par un test
qui échoue si le réglage cesse d'être lu. Les lignes confirmées passent en
`1` sans qu'une seule ligne de production ait bougé.

*Critère de fin.* Pour chacune des 28 : confirmée avec son test, ou recalée
avec la raison. Une ligne qu'aucun test ne démontre reste en `1a`.

| Code | Preuve dans le code | Ce qu'il faut câbler |
|---|---|---|
| `BO-03-02-38` | `service/PosSettingsService.java:282-285 ; ui/home/HomeService.java:130-147` | deja administre par tender.restricted |
| `BO-02-03-45` | `ui/admin/AdminArticleResource.java:229-230,243-260` | deja entierement cable, fiche et bulk |
| `BO-10-02-05` | `ui/home/HomeService.java:475-493 ; service/PosSettingsService.java:82-85` | deja administre par gesture.endorsement-required |
| `BO-10-05-02` | `service/PosSettingsService.java:190-193` | deja administre par balance.counter-price |
| `BO-03-03-19` | `ui/hardware/TicketPrinterService.java:202-205` | deja cable — display.show-ean |
| `BO-03-08-01` | `service/PosSettingsService.java:102-105` | deja cable — customer.message-open |
| `BO-03-08-02` | `service/PosSettingsService.java:106-109` | deja cable — customer.message-closed |
| `BO-03-10-19` | `ui/admin/AdminStoreResource.java:83-101 ; domain/Store.java:56,71,85-107` | deja cable — tous les champs sont editables |
| `BO-03-12-06` | `ui/admin/AdminStoreResource.java:29-36,96` | deja cable |
| `BO-03-12-07` | `ui/admin/AdminEchelonResource.java:69-90 ; service/EchelonSettingService.java:178-203` | deja cable — vue des PDV personnalises |
| `BO-03-01-01` | `ui/admin/AdminProductFamilyResource.java:116-218` | deja couvert integralement, CRUD complet |
| `BO-03-01-08` | `ui/admin/AdminTouchResource.java:117-139` | deja couvert — tailles SMALL/NORMAL/LARGE |
| `BO-03-01-11` | `ui/ticket/ManualService.java:369-380` | deja administre par touch.display-order |
| `BO-03-01-15` | `ui/admin/AdminTouchResource.java:177-235` | deja couvert — image par article |
| `BO-03-01-17` | `ui/admin/AdminProductFamilyResource.java:301-316` | deja couvert — bascule actif/inactif |
| `BO-03-01-24` | `ui/admin/ImageResizeService.java:31,57-83` | deja couvert ; taille max figee, cle optionnelle touch.image-max-dimension |
| `BO-03-07-02` | `ui/ticket/TicketService.java:502-519` | deja administre par discount.enabled et discount.line-max-percent |
| `BO-03-07-05` | `ui/ticket/TicketService.java:475-494` | deja administre par discount.enabled |
| `BO-03-07-07` | `ui/ticket/TicketService.java:132-148` | deja administre par discount.enabled |
| `BO-03-07-08` | `ui/ticket/TicketService.java:132-148` | deja administre par discount.enabled et global-max-percent |
| `BO-03-07-10` | `ui/home/HomeService.java:479-493` | deja administre par gesture.endorsement-required |
| `BO-03-13-05` | `ui/returnprocess/RefundService.java:169-232` | deja couvert par la saisie du numero |
| `BO-03-13-07` | `ui/returnprocess/RefundService.java:204-232` | deja couvert integralement |
| `BO-10-03-01` | `ui/admin/AdminEchelonResource.java:135-143` | deja administre par Enseigne.code, non encore transmis a imfid |
| `BO-10-03-15` | `service/PosSettingsService.java:214-217` | deja administre par fidelity.advantages-enabled |
| `BO-10-07-01` | `service/PosSettingsService.java:102-109` | deja administre pour ouverte/fermee ; l'etat veille s'ajoute sur le meme schema |
| `BO-10-07-12` | `service/PosSettingsService.java:98-101` | deja administre par price.show-original-on-force |
| `BO-10-07-21` | `service/PosSettingsService.java:130-141` | deja administre par print.conditional-enabled et print.forced-documents |

## Lot C1 — Référentiel articles et échelons

**17 exigences.** Le bloc le plus mécanique : six attributs du catalogue sont
éditables en fiche mais absents de l'import CSV, trois champs de `Product`
sont rendus `disabled` alors que la donnée existe, et le renommage d'un PDV
laisse ses `EchelonSetting` orphelins.

*Critère de fin.* `mvn verify` vert ; chaque attribut nouvellement importable
prouvé par un test d'import sur ses deux arms (colonne présente / absente).

| Code | Preuve dans le code | Ce qu'il faut câbler |
|---|---|---|
| `BO-02-03-06` | `attribute/ProductAttributeCatalog.java:42-43 ; imports/ProductCsvResource.java:205-238` | exposer MEAL_VOUCHER_ELIGIBLE dans l'import CSV (3 sites) |
| `BO-02-03-09` | `attribute/ProductAttributeCatalog.java:30-31 ; imports/ProductCsvResource.java:205-238` | idem pour DISCOUNT_FORBIDDEN |
| `BO-02-03-11` | `attribute/ProductAttributeCatalog.java:36-37 ; imports/ProductCsvResource.java:205-238` | idem pour RECALL |
| `BO-02-03-17` | `domain/Product.java:162-163 ; templates/admin-article.html:65-68` | unitName est disabled dans la fiche : le rendre editable (2-3 sites) |
| `BO-02-03-18` | `domain/Product.java:221-226 ; imports/ProductCsvResource.java:205-238` | colonne generique ATTRIBUTES=code=valeur dans l'import |
| `BO-02-03-21` | `attribute/ProductAttributeCatalog.java:54-55` | exposer PRICE_TO_ENTER dans l'import CSV |
| `BO-02-03-22` | `attribute/ProductAttributeCatalog.java:57-58` | exposer QUANTITY_TO_ENTER dans l'import CSV |
| `BO-02-03-24` | `domain/Product.java:68-69 ; templates/admin-article.html` | variableWeight alimente par CSV mais absent de la fiche (2 sites) |
| `BO-02-03-25` | `attribute/ProductAttributeCatalog.java:51-52` | exposer BULKY dans l'import CSV |
| `BO-02-03-28` | `templates/admin-article.html:24-228` | ajouter l'affichage de variableWeight |
| `BO-02-03-30` | `ui/admin/ArticleSearchCriteria.java:46-66` | etendre la recherche : plage EAN, famille, plage de prix, taux TVA |
| `BO-02-03-31` | `domain/ArticleSelection.java:29-46` | ajouter pdvCode + adapter l'unicite (2-3 sites) |
| `BO-02-03-33` | `ui/admin/AdminArticleResource.java:204-232 ; templates/admin-article.html:69-80` | giftCardAmount, referenceWeight, referenceVolume sont en lecture seule |
| `BO-02-05-01` | `domain/Pdv.java:44-96 ; ui/admin/AdminEchelonResource.java:161-190` | le renommage ne migre pas les EchelonSetting rattaches (2-3 sites) |
| `BO-02-05-03` | `ui/admin/AdminEchelonResource.java:98-190` | ajouter un importeur CSV/API pour Country/Enseigne/Pdv |
| `BO-02-05-04` | `service/EchelonSettingService.java:47-87` | ajouter au CATALOG les cles absentes : elles heritent automatiquement |
| `BO-02-05-05` | `domain/Pdv.java:66-72,118-127` | adherentCode et listByAdherent existent mais ne sont appeles nulle part |

## Lot C2 — Supervision et flux sortants

**20 exigences.** Le mécanisme existe entièrement — outbox, tirage, livraison
moteur — et rien ne l'expose. `/feeds/import/status` répond déjà et n'a aucun
écran ; `pullOnce` et `deliverPending` sont publiques et aucun bouton ne les
appelle ; les cadences sont des `ConfigProperty` qui n'ont qu'à rejoindre le
catalogue.

*Attention.* Toute exigence supposant une console centrale supervisant
PLUSIEURS PDV est hors de ce lot : le nœud ne connaît que lui-même.

*Critère de fin.* `mvn verify` vert ; l'écran de statut affiche les trois
codes de flux et leur dernière erreur ; les deux relances manuelles sont
journalisées.

| Code | Preuve dans le code | Ce qu'il faut câbler |
|---|---|---|
| `BO-08-01-07` | `imports/EngineFeedRelayResource.java:144-168` | ecran admin sur l'endpoint de statut existant, code PRODUCTS |
| `BO-08-01-08` | `imports/EngineFeedRelayResource.java:144-168` | meme ecran, code FAMILIES |
| `BO-08-01-12` | `imports/EngineFeedRelayResource.java:144-168` | meme ecran consulte depuis une caisse |
| `BO-08-01-13` | `imports/EngineFeedRelayResource.java:144-168` | meme ecran, FAMILIES sur caisse |
| `BO-08-01-16` | `service/sync/EngineFeedService.java:47-54` | meme ecran, code OFFERS |
| `BO-08-03-08` | `service/sync/SyncPushService.java:47,81 ; RefPullService.java:58,120,200` | ajouter les cadences au CATALOG et afficher le dernier sync |
| `BO-08-04-03` | `service/sync/SyncPushService.java:134-141 ; domain/SyncOutbox.java:70-76` | ecran exposant attempts et lastError par code |
| `BO-08-04-04` | `service/sync/RefPullService.java:184-191` | meme ecran, code FAMILIES |
| `BO-08-04-08` | `service/sync/EngineFeedDeliveryService.java:137-141` | exposer EngineFeed.lastError du code OFFERS |
| `BO-08-04-09` | `service/sync/RefPullService.java:179` | endpoint admin appelant pullOnce, domaine PRODUCTS |
| `BO-08-04-10` | `service/sync/RefPullService.java:179` | meme endpoint, FAMILIES |
| `BO-08-04-13` | `service/sync/EngineFeedDeliveryService.java:137-141` | endpoint admin appelant deliverPending |
| `BO-08-04-16` | `ui/dashboard/DashboardService.java:66-82,124-134` | colonne anomalie basee sur le backlog outbox par terminal |
| `BO-09-01-09` | `service/CashSessionService.java:349-388` | bouton forcage fermeture appelant closeSession(null, ...) |
| `BO-11-02-05` | `domain/SyncOutbox.java:70-76 ; service/sync/SyncPushService.java:99-144` | ecran healthcheck sync sur le tableau de bord |
| `BO-11-03-01` | `ui/journal/JournalResource.java:143-153` | reutiliser l'export CSV a la demande |
| `BO-11-04-04` | `ui/fidelity/ImfidClient.java:39-48,115-134` | fidelity.url/user/password au CATALOG |
| `BO-11-04-05` | `ui/fidelity/FidelityService.java:254-310` | meme cle de configuration |
| `BO-11-04-06` | `service/sync/FidEventOutboxService.java:129-161` | meme cle de configuration |
| `BO-11-04-07` | `ui/fidelity/FidelityService.java:373-391` | meme cle de configuration |

## Lot C3 — Journal électronique et tableau de bord

**14 exigences.** Quatre d'entre elles sont le *même* correctif d'une ligne :
les recherches par PLU testent `l.plu` sans jamais tester `l.product.plu`, ce
qui rend invisibles au journal les articles au PLU catalogue vendus hors
pesée. Le reste est de l'extension de critères sur des requêtes existantes.

*Critère de fin.* `mvn verify` vert ; chaque nouveau critère prouvé sur ses
deux arms (critère posé / critère absent) et sur sa borne.

| Code | Preuve dans le code | Ce qu'il faut câbler |
|---|---|---|
| `BO-04-01-05` | `ui/journal/JournalService.java:106-113 ; domain/ticket/Ticket.java:208` | second couple de bornes hors remise sur totalIncludingTax + globalDiscountApplied |
| `BO-04-01-09` | `ui/journal/JournalService.java:127-134` | ajouter hourFrom/hourTo (extract hour) pour une plage horaire repetee |
| `BO-04-01-10` | `ui/journal/JournalService.java:156-174` | OR-er l.product.plu : les articles au PLU catalogue vendus hors pesee |
| `BO-04-01-11` | `ui/journal/JournalService.java:187-204 ; templates/journal.html:81-87` | multi-select familles + critere IN |
| `BO-04-01-16` | `ui/journal/JournalService.java:292-317` | meme correctif que 04-01-10 |
| `BO-04-01-23` | `ui/journal/JournalService.java:221-248` | meme correctif que 04-01-10 |
| `BO-04-01-26` | `ui/journal/JournalService.java:257-275` | restreindre a REMISE/DISCOUNT pour exclure le forcage prix |
| `BO-04-01-33` | `ui/journal/JournalService.java:666-697` | ajouter movementAmountMin/Max sur m.amount |
| `BO-04-01-34` | `ui/journal/JournalCriteria.java:49-50 ; JournalService.java:372-375` | plage de PLU jumelee au flag UNKNOWN_ITEM |
| `BO-04-01-39` | `ui/endorsement/EndorsementService.java:63-67 ; service/TechnicalEventService.java:52-61` | appeler log() a 3 arguments : le filtre par N° caissiere fonctionnera |
| `BO-04-01-46` | `ui/journal/JournalService.java:383-386 ; domain/ticket/BackupPayment.java:28-30` | OR-er BackupPayment dans le total monetique |
| `BO-04-02-12` | `templates/dashboard.html:3,134 ; templates/admin-layout.html` | deplacer le bandeau d'alerte et son poll dans le gabarit partage |
| `BO-04-03-07` | `templates/dashboard.html:3,134 ; ui/dashboard/DashboardService.java:47-137` | ajouter une requete activite recente au poll existant |
| `BO-04-03-10` | `service/PosSettingsService.java:226-229 ; ui/cash/CashMovementResource.java:88-90` | decliner cash.movement-reasons par MovementType |

## Lot C4 — Règles caisse, remises, fidélité, profils

**18 exigences**, la traîne : ce qui reste câblable une fois les trois blocs
denses sortis. Chaque ligne est indépendante des autres.

*Critère de fin.* `mvn verify` vert ; chaque clé nouvelle câblée à un
comportement qui existait déjà et testée sur ses deux arms. **Zéro bouton
mort** : une clé qui n'éteint ou n'allume rien de démontrable n'est pas
livrée.

| Code | Preuve dans le code | Ce qu'il faut câbler |
|---|---|---|
| `BO-03-02-20` | `ui/cash/CashMovementResource.java:136 ; templates/cash-movement.html:60-66` | cash.movement-tenders — record() accepte deja paymentMethod, il suffit de le remonter du formulaire |
| `BO-03-02-41` | `ui/cash/CashSessionResource.java:61-91` | cash.default-opening-float — herite automatiquement par echelon |
| `BO-10-02-12` | `service/PosSettingsService.java:174-177 ; ui/cash/CashSessionResource.java:151-162` | paiement deja couvert ; ajouter drawer.open-on-session-close |
| `BO-10-02-26` | `ui/cash/CashSessionResource.java:151-162` | drawer.open-on-session-close |
| `BO-10-02-29` | `ui/scanner/AuthScanHandler.java:44-50` | auth.badge-scan-enabled |
| `BO-10-02-30` | `ui/scanner/AuthScanHandler.java:44-50` | meme site et meme cle que 02-29 |
| `BO-10-06-04` | `ui/hardware/TicketPrinterService.java:257-260,460-462` | ticket.vat-breakdown-enabled (2 sites) |
| `BO-03-07-04` | `ui/ticket/TicketService.java:475-494,132-148` | ajouter discount.line-max-amount et discount.global-max-amount |
| `BO-10-03-04` | `templates/main.html:163 ; ui/home/HomeResource.java:115-117` | fidelity.show-holder-name (2-3 sites) |
| `BO-10-03-07` | `ui/fidelity/ImfidClient.java:64-66` | fidelity.external-enabled dans isConfigured (2 sites) |
| `BO-10-03-22` | `ui/valuation/ValuationService.java:358` | fidelity.jv-promotion-enabled (2 sites) |
| `BO-10-07-08` | `ui/ThemeService.java:52-64` | point de resolution unique : ajouter un theme ecole (2-3 sites) |
| `BO-01-02-10` | `domain/Employee.java:280,218-219` | action POST /admin/users/reset-password reutilisant les methodes existantes |
| `BO-01-03-04` | `domain/EmployeeProfile.java:50-74 ; ui/admin/AdminUserProfileResource.java:138-168` | selection multiple de PDV rebouclant sur assign() |
| `BO-01-04-01` | `domain/Feature.java:22-35 ; ui/journal/JournalResource.java:65` | ajouter une Feature et remplacer @RolesAllowed par PermissionService.guard() |
| `BO-01-04-02` | `domain/CrudOption.java:17-27` | s'etend automatiquement a toute nouvelle Feature |
| `BO-01-04-08` | `ui/admin/AdminFavoriteResource.java:34-50` | ecran favoris existant, s'etend avec chaque Feature |
| `BO-06-06-09` | `service/CashSessionService.java:71,100-106` | nouvel ecran reutilisant totalsByMethod deja calcule |

*Audit établi le 11/09/2026 contre le code du jour. Il se périme : une ligne
passée en `1` par une autre campagne sort de cette table, et un refactor qui
dégèle un littéral y fait entrer des lignes aujourd'hui classées « figé ».*

---

# Le reste du back-office — les 195 P1 partielles hors câblage

Ajouté le 11/09/2026, le soir. La partie « Campagne câblage » ci-dessus nommait
ses 97 lignes ; les 195 autres P1 cotées `1a` étaient comptées et non nommées.
Cette partie les nomme, une par une, chacune avec un `fichier:ligne` réellement
ouvert. Six agents en parallèle, même discipline que l'audit du matin : *le code
a raison contre le texte de l'exigence*.

## Le verdict corrigé

L'audit du matin annonçait 68 figées, 19 fiscales et 107 inexistantes. Le
comptage réel, ligne à ligne, est sensiblement différent :

| Verdict | Estimé le matin | **Réel** | Écart |
|---|---|---|---|
| Câblable | 0 | **8** | +8 |
| Figé | 68 | **109** | +41 |
| Fiscal | 19 | **18** | −1 |
| Inexistant | 107 | **60** | −47 |

**Le produit est plus avancé que l'audit ne le disait.** Quarante-sept lignes
classées « à construire » portent en réalité un comportement qui existe et
fonctionne — il n'est simplement pas atteignable depuis une configuration. La
part de construction pure tombe de 107 à 60.

C'est une bonne nouvelle pour la cotation et une moins bonne pour la
délégation : un refactor se pilote moins bien qu'une construction, parce qu'il
suppose des arbitrages sur du code qui tourne déjà.

Le total câblable passe de 97 à **105**.

---

## 1. Les 8 câblables que le premier tri avait manquées

À verser au lot C5. Chacune tient en deux sites, sans changement de signature
publique ni contact avec le chemin fiscal.

| Code | Preuve | Ce qu'il faut câbler |
|---|---|---|
| `BO-03-01-05` | `ui/ticket/ManualService.java:224` | La tuile article existe déjà comme primitive, elle n'est jamais appelée à la racine. Clé `touch.level-zero-group` + un appel dans `getManualRootData`. |
| `BO-03-06-54` | `ui/scanner/FidelityScanHandler.java:40` | Le motif de carte fidélité est déjà externalisé et lu en UN site. `scan.fidelity-pattern` au CATALOG. (Le contrôle Luhn reste à construire.) |
| `BO-03-09-16` | `ui/hardware/scanner/ScannerReaderService.java:91` | L'interrupteur d'activation du scanner existe et n'est testé qu'une fois. `scanner.enabled` au CATALOG. |
| `BO-03-13-09` | `ui/returnprocess/RefundService.java:278` | `performRefund` est déjà public et appelé tel quel par le répartiteur d'avenants. `refund.endorsement-required` + le `if/else` recopié de `HomeService:493`. |
| `BO-02-03-12` | `ui/admin/AdminArticleResource.java:72,255` | Le drapeau par article ET sa pose en masse existent ; ils visent l'écran, pas le ticket. Clé `ticket.article-code` lue en `TicketPrinterService:202`. |
| `BO-02-03-23` | `ui/ticket/TicketService.java:250` | La saisie décimale fonctionne de bout en bout ; il manque l'attribut qui la déclare. `DECIMAL_QUANTITY` au `ProductAttributeCatalog`. |
| `BO-10-02-35` | `ui/ThemeService.java:140` | `display.show-ean` gouverne écrans ET papier d'un seul tenant. Scinder : `display.show-ean-screens`, 2 sites de lecture. |
| `BO-08-04-07` | `service/sync/SyncOutboxService.java:196` | La relance automatique existe sur les trois jambes ; `attempts` est incrémenté et jamais lu. `sync.retry-max-attempts` + 2 sites de bornage. |

---

## 2. Les 109 figées, groupées par verrou

Elles ne se traitent pas une par une. Quatorze verrous les expliquent presque
toutes, et c'est à cette maille qu'il faut décider.

### V1 — Un moyen de paiement est compilé, pas administré (≈ 20 lignes)

`domain/ticket/FidelityPayment.java:20` — l'identité d'un moyen est un
quadruplet figé : une sous-classe JPA `@DiscriminatorValue`, une entrée du
registre `ui/journal/PaymentTypes.java:31`, une méthode `process*` dans
`PaymentService`, une route JAX-RS (`PaymentResource.java:154-210`) et un bouton
HTML (`pay.html:334-350`). Aucune configuration ne peut désigner un moyen qui
n'existe pas à la compilation.

Lignes : `BO-03-02-01/02/03/04/10/15/16/17/18/19/22/23/30/37/44/45/46/47`,
`BO-10-03-06`, `BO-10-01-27`.

*Point positif à retenir pour la réponse : il n'y a aucune limite à 2 digits sur
l'identifiant d'un moyen — l'identité est textuelle.*

### V2 — Il n'existe aucun moteur de gabarit documentaire (≈ 16 lignes)

Trois rendus parallèles et divergents : le ticket est une méthode de 190 lignes
qui concatène des littéraux sur `WIDTH = 42`
(`ui/hardware/TicketPrinterService.java:136-327`), la facture une classe `final`
à méthodes `private static` (`ui/invoice/InvoiceRenderer.java:45`), l'A4 un
troisième (`NetworkDocumentPrinter`). Ils divergent déjà factuellement — le
tableau TVA du ticket n'a pas la colonne TTC que la facture a, le total HT est
sur la facture et pas sur le ticket — alors que deux exigences demandent
explicitement qu'ils soient conformes entre eux.

Lignes : `BO-03-03-02/03/04/05/06/08/20/23/25`, `BO-03-04-08/13`,
`BO-03-05-01/03`, `BO-03-09-02/03`, `BO-10-08-28/29`, `BO-10-02-34`.

### V3 — `CouponType` est le seul modèle de plage de code-barres (≈ 21 lignes)

`domain/CouponType.java:46` et `:61` : deux regex (`matchPattern`,
`amountPattern`) remplacent à elles seules tout ce que l'AO appelle offset,
longueur, décimales, n° ticket, n° TPV, n° séquence, flag de comptage, niveau
d'alerte. Une position n'est pas une donnée ici, c'est un groupe capturant. Et
le javadoc de la classe l'admet lui-même : *« there is no in-application
administration screen »*.

Lignes : `BO-03-06-01/02/03/04/08/09/11/13/33/34/37/38/48/49/63`, `BO-03-13-11`.

### V4 — La colonne EAN est plafonnée à 13 caractères (2 lignes)

`domain/Product.java:53` et `domain/ticket/TicketLine.java:56` :
`@Column(name = "ean", length = 13)`. Un code de 38 caractères ne peut ni être
catalogué ni être écrit sur une ligne de ticket, avant toute discussion de
paramétrage. Lignes : `BO-03-06-09/10`.

### V5 — La frontière CATALOG / `@ConfigProperty` (≈ 10 lignes)

Drivers, monétique, fournisseurs, flux temps réel, n° terminal, motifs de scan :
tous existent et fonctionnent, tous sont des propriétés de déploiement hors du
catalogue administrable, donc hors de la chaîne d'héritage pays → enseigne →
PDV. Le mécanisme de repli existe pourtant déjà
(`PosSettingsService.java:394-398`, `configFallback`).

Lignes : `BO-03-09-11/14/19/21/23`, `BO-03-11-04`, `BO-03-12-01`,
`BO-10-03-08/10`, `BO-01-02-09`.

### V6 — `Feature` ne connaît que cinq écrans back-office (≈ 8 lignes)

`domain/Feature.java:23-35`. Or `PermissionService.Decision` porte déjà
exactement la sémantique demandée — `allowed` / `requiresValidation` /
`validatingProfileId` (`PermissionService.java:56-62`) — mais aucun geste de
caisse n'y est adressable. **Étendre `Feature` aux gestes d'encaissement est le
levier unique qui débloque tout ce groupe d'un coup.**

Lignes : `BO-10-02-01/03/04/06/08`, `BO-04-02-01`, `BO-05-02-14`.

### V7 — Ce qui devrait être administré est une énumération Java (≈ 6 lignes)

`ui/PosMenu.java:22` (5 menus caisse), `ui/PriceModType.java:22` (6 gestes de
prix), `ui/ThemeService.java:41` (2 thèmes), `domain/ticket/Refund.java:53`
(4 modes de remboursement), `domain/ticket/DocumentType.java:26` (2 types de
document). Le comportement existe et fonctionne ; il est compilé.

Lignes : `BO-03-07-09`, `BO-03-09-06`, `BO-03-02-15`, `BO-03-04-13`,
`BO-03-10-17`, `BO-10-07-19`.

### V8 — L'export n'est pas un mécanisme, c'est une méthode (5 lignes)

`ui/journal/JournalService.java:815` : l'en-tête et les neuf colonnes sont un
littéral, et `JournalResource.java:146` est le **seul** `@Produces("text/csv")`
du projet. Un sérialiseur générique branché sur les trois onglets du journal et
sur le tableau de bord résorbe les cinq.

Lignes : `BO-06-01-02`, `BO-05-01-06`, `BO-05-02-13`, `BO-11-01-14`,
`BO-04-01-50`.

### V9 — L'invariant mono-nœud (≈ 7 lignes)

`ui/supervision/SyncSupervisionResource.java:41` le pose en doctrine — *« The
node knows only itself »* — et `service/sync/RefState.java:25` en schéma :
colonne `domain` unique, aucun identifiant de terminal. Toute vue consolidée par
échelon demande un même canal de remontée d'état et une même agrégation magasin.

Lignes : `BO-08-01-17/18`, `BO-08-02-01`, `BO-08-04-15/18`, `BO-04-03-01/03`.

### V10 — Le bilan d'intégration est calculé puis jeté (3 lignes)

`imports/ImporterCsvResource.java:191` : `buildAnswer(counters, errors)`
sérialise le bilan en JSON et le laisse mourir avec la réponse HTTP. Un seul
refactor à cet endroit sert les trois exigences PDV, articles et nomenclature.
Lignes : `BO-08-01-01/02/03`.

### V11 — Un destinataire de flux unique (4 lignes)

`service/sync/SyncPushService.java:121` : l'URL est construite en ligne, et
`PreparedItem` ne porte qu'un `pathSuffix`. Le mécanisme temps réel est complet
et transactionnel ; il lui manque l'éventail. Lignes : `BO-11-02-01/02`,
`BO-03-09-23`, `BO-03-12-04`.

### V12 — Aucune internationalisation (3 lignes)

`Country.defaultLanguage` et `Enseigne.defaultLanguage` traversent saisie, export
et réapplication sans **aucun** consommateur : tous les libellés sont des
littéraux français dispersés (« PRODUIT INTERDIT À LA VENTE » sur 5 sites).
Externaliser les chaînes est le préalable, pas le réglage. Lignes :
`BO-10-07-18/20`, `BO-03-10-18`, `BO-03-10-20`.

### V13 — `CashMovement` n'a ni TVA, ni état, et vit dans une session (4 lignes)

`domain/CashMovement.java:123-127` s'arrête à `amount` + `reason`, et
`CashMovementResource.java:120` exige une session de caisse ouverte. Tout ce qui
est « hors caisses » est hors du modèle. Lignes : `BO-05-02-08/09/13/15`,
`BO-07-01-02/04`.

### V14 — `Gs1AlertLevel` est le seul mécanisme warning/bloquant (5 lignes)

`domain/gs1/Gs1AlertLevel.java:19-28` porte exactement la forme demandée
(NONE / INFO / BLOCK) mais est réservé aux dates GS1. Les refus équivalents sont
des `setError("…")` littéraux, toujours bloquants, répartis sur
`UnknownScanHandler:28`, `VoucherService:116/122/129`,
`WeightedEanScanHandler:103`. Généraliser ce type couvrirait le groupe.
Lignes : `BO-03-06-45/46`, `BO-04-03-09`, `BO-10-02-14`, `BO-10-07-20`.

### Figées restantes, hors verrou commun

`BO-03-01-04`, `BO-02-03-03`, `BO-02-03-05`, `BO-02-03-14`, `BO-02-05-02`,
`BO-04-01-14`, `BO-04-01-19`, `BO-04-02-02`, `BO-04-02-11`, `BO-04-03-02`,
`BO-06-06-10`, `BO-10-01-06`, `BO-10-01-13`, `BO-10-02-18`, `BO-10-08-25`,
`BO-11-01-21`, `BO-11-02-06`, `BO-03-13-04`, `BO-03-12-03`, `BO-05-02-01/02`.

---

## 3. Les 60 inexistantes, groupées par ce qui manque

### Le coffre n'existe pas comme objet (6 lignes)

Aucune entité, aucun service, aucun écran : `grep "coffre|Safe|pochette"` ne
retourne rien. Construire l'entité coffre et détacher le mouvement de la session
débloque les six. Lignes : `BO-05-02-01/02/09/10`, plus la ventilation TVA.

### Le module « Rapports » n'existe pas (8 lignes)

Il n'y a que le journal, le tableau de bord et le rapport X/Z. Sept états à
construire, et deux socles leur manquent en commun : un **sélecteur de période**
(le tableau de bord est figé sur `LocalDate.now().atStartOfDay()`,
`DashboardService.java:48`) et un **catalogue de codes raison** (aucun
`reasonCode` dans le code ; les motifs sont du texte libre).
Lignes : `BO-06-04-05`, `BO-06-06-01/03/04/05/06/07`.

### Quatre processus amont n'existent pas du tout (5 lignes)

E-commerce, self-scanning, qbusting, mobilité. `ui/journal/JournalRow.java:12`
l'écrit noir sur blanc : *« self-scanning is not a modelled channel »*. Le canal
lui-même est par ailleurs figé (`ValuationPayloads.java:56`,
`deliveryMode = "IN_STORE"` en littéral d'initialisation).
Lignes : `BO-10-01-03/21/22/28/31/34`, `BO-07-01-01`.

### Les comptes utilisateurs n'ont pas d'écran (5 lignes)

Ils naissent d'un CSV (`imports/EmployeeCsvResource.java:147`) ou du tirage,
jamais d'une IHM. Pas de création nominative, pas de téléphone ni date de début,
pas de génération badge + mot de passe, pas de carte superviseur.
Lignes : `BO-01-01-04/06`, `BO-04-02-09`, `BO-02-04-19`.

### Trois gisements sont muets par construction

`trainingMode` et `autonomous` valent invariablement `"N"` sur le nœud
consolidé, `TicketLine` ne distingue pas scanné/saisi, et rien ne mesure une
durée. C'est de la donnée à produire **en caisse**, pas du rapport à écrire en
back-office — et ça prive plusieurs états d'une fraction de leurs colonnes.

### Autres constructions

Alertes paramétrables (`BO-04-03-08`, `BO-04-02-06` : aucun modèle d'alerte),
éco-taxe à montant (`BO-10-06-05`), cagnottes multiples (`BO-10-02-33`),
sous-famille au payload (`BO-10-08-22`), nomenclature par échelon
(`BO-02-01-01`), import XLSX (`BO-03-01-16`), NEXO (`BO-03-02-37`,
`BO-11-03-04`), référentiel TVA (`BO-11-01-01`), connecteurs d'exportation
(`BO-11-01-02`), heartbeat tickets (`BO-08-04-18`), état de connectivité poste
(`BO-10-07-19`), îlot de caisse (`BO-03-10-09`), retour article par code-barres
(`BO-03-13-12`), sentinelle de montant (`BO-03-06-12`), contrôle de doublon
(`BO-03-06-36/39`), coupon au moteur (`BO-03-06-42`), fonction caisse par scan
(`BO-03-06-59`), liste des codes-barres émis (`BO-03-06-62`).

---

## 4. Les 18 fiscales — instruction séparée

Elles ne se traitent ni en câblage ni en construction ordinaire : elles touchent
la signature chaînée, la clôture de caisse ou l'authentification.

**Chaîne fiscale et clôture** — `BO-03-04-19` et `BO-03-04-28` (le format et
l'unicité annuelle du numéro sont produits par le même service que
`nextTicketNumber()`, premier élément joint du SHA-256 :
`TicketPersistenceService.java:442`) ; `BO-03-09-28` (le n° de terminal est
l'identité de la chaîne par caisse) ; `BO-10-02-18` (l'arrondi de ventilation
alimente la signature) ; `BO-09-01-05`, `BO-09-01-08`, `BO-09-03-05` (trois
modifications du même chemin, `CashSessionService.closeSession:349-388`, à
traiter ensemble).

**Sécurité et authentification** — `BO-01-01-01/02/03`, `BO-01-02-03/06/09/11`,
`BO-01-03-03`, `BO-03-06-51`, `BO-10-02-22/23/31`.

Le modèle d'habilitation est à deux étages non réconciliés : l'enum figé
`Employee.EmployeeRole` (4 valeurs, un rôle par compte) que lisent la caisse et
les `@RolesAllowed`, et le modèle administré `Profile` × `ProfileGrant` qui ne
couvre que cinq `Feature` back-office et que `ADMIN` court-circuite entièrement
(`PermissionService.java:153`). C'est l'arbitrage de fond de tout l'épic 01.

---

## 5. Ce que ça change pour la campagne

**Le plafond câblable monte à 105.** Les 8 lignes du §1 rejoignent le lot C5.

**Les 109 figées ne sont pas 109 chantiers, mais quatorze.** Trois d'entre eux
suffiraient à en débloquer une cinquantaine : extraire le registre des moyens de
paiement (V1, ≈ 20 lignes), étendre `Feature` aux gestes de caisse (V6, ≈ 8
lignes mais c'est le meilleur rapport du lot), et écrire un moteur de gabarit
documentaire (V2, ≈ 16 lignes). Ce sont des décisions d'architecture, pas du
travail mécanique : elles se tranchent avant d'être déléguées.

**Les 60 inexistantes se délèguent, elles.** Construction ordinaire sur un socle
dont les conventions sont écrites — c'est ce que les treize lots thématiques
avaient été taillés pour absorber.

**Les 18 fiscales attendent une instruction.** Sept touchent la signature ou la
clôture, onze l'authentification.
