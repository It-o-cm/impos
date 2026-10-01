# BO-08 Supervision — re-cotation adossée au code

Relecture des 46 lignes contre le code livré, pas contre le texte de
l'exigence. État de départ : 2 en `1`, 24 en `1a`, 20 en `3`.

## Ce sur quoi la relecture s'appuie

- imedge `/admin/sync` (`SyncSupervisionResource` + `SyncSupervisionService`) :
  par flux, code / version reçue / version appliquée / appliqué / **dernière
  erreur** / reçu à / appliqué à ; empreintes de pull par domaine ; backlog de
  l'outbox ; livraisons programmées. Relances : `POST /admin/sync/pull`,
  `/deliver`, `/deliver/{code}`.
- impos `/supervisor/health` (`ExchangeHealthResource` + `ExchangeHealthService`)
  : fragments `uplink`, `consumption`, `feeds` ; uplink = nombre, plus ancien,
  dernière erreur. Plus `/feeds/import/status` en JSON.
- `EngineFeedService.CATALOG`, dix flux : STORES, STORE_GROUPS,
  EGALIM_REGIMES, PRODUCTS, FAMILIES, CATEGORY_STORAGES, VAT_RATES, PRICES,
  BUDGET_GROUPS, OFFERS.
- `SyncOutboxService` : compteur `attempts` par ligne, donc reprise.
- `TechnicalEventService.log(type, detail, badge)` : 31 sites d'appel.

## Promotions défendables : 1a -> 1 (8 lignes)

Toutes nomment UN échelon, celui de la caisse ou du magasin, et cet échelon
affiche l'état d'intégration flux par flux avec sa dernière erreur.

| Ligne | Pourquoi |
|---|---|
| BO-08-01-10 | TVA sur le edge — VAT_RATES est au catalogue, état et erreur sur `/admin/sync` |
| BO-08-01-12 | Articles sur les points de contact — `/supervisor/health/feeds` |
| BO-08-01-13 | Nomenclature sur les points de contact — idem |
| BO-08-01-16 | Offres commerciales sur les points de contact — OFFERS au catalogue |
| BO-08-01-18 | Attributs des articles sur les points de contact — portés par PRODUCTS |
| BO-08-02-01 | Résultat d'intégration des attributs sur les caisses — `/feeds/import/status` |
| BO-08-03-08 | Synchronisation automatique serveurs / caisses — pull périodique + outbox |
| BO-08-04-18 | Heartbeat tickets — uplink : nombre, plus ancien, dernière erreur |

## Arbitrage tranché : les trois échelons sont couverts (14 lignes)

BO-08-01-01/02/03, BO-08-04-01/02, BO-08-04-03/04/06/07/08,
BO-08-04-09/10/12/13.

Elles énumèrent trois échelons : brique centrale, point de vente, point de
contact. La brique centrale EST une instance d'imedge portant
`pos.role=central` — pas une autre application. Vérifié dans le code :

- `pos.role` prend trois valeurs, `register`, `store` et `central`
  (`RegisterCommandResource`, `RefExportResource`).
- `RefExportResource` sert les domaines d'échelon quand le rôle est `central`,
  les domaines magasin sinon ; `RefPullService` dit qu'un nœud magasin tire
  les domaines d'échelon depuis le nœud CENTRAL.
- `Country`, `Enseigne` et `EchelonSetting` portent en javadoc « Held on the
  CENTRAL node » ; `AdminEchelonResource` édite ce registre central.
- `SyncSupervisionResource` n'est gardé que par `@RolesAllowed("ADMIN")`, PAS
  par `pos.role` ; et les importeurs CSV ne portent aucune garde de rôle.

Donc une instance centrale sert le même `/admin/sync`, avec le même état par
flux, la même dernière erreur et les mêmes relances. Les quatorze lignes sont
couvertes **par le code**.

Ce qui manque n'est pas du développement : **aucun déploiement ne lance ce
rôle**. `application.properties` livre `pos.role=store`, `demo-stack.sh` ne
démarre qu'un imedge, et aucune unité de `deploy/gcp-qualif` ne pose
`central`. La démonstration tient à un second imedge dans la pile de démo.

Réserve indépendante : les quatre relances **manuelles**
(BO-08-04-09/10/12/13) n'existent pas côté caisse. Le seul `POST` de
`ExchangeHealthResource` est `/consumption/replay`, qui rejoue la remontée des
consommations, pas l'intégration d'un référentiel. Un bouton « relancer le
pull » sur `/supervisor` fermerait cet échelon.

## Les « clients en compte » : diagnostic corrigé (5 lignes)

BO-08-01-04/09/14, BO-08-04-05/11.

**Premier diagnostic, faux** : « `ACCOUNT_CUSTOMERS` manque dans
`EngineFeedService.CATALOG` ». Ce catalogue est la liste des flux livrés au
MOTEUR de valorisation, qui n'a rien à faire des clients en compte. L'y
ajouter aurait fait poster au moteur un fichier qu'il n'a aucun point d'entrée
pour recevoir.

**Ce que le code dit réellement.** Tout existe déjà, sous le nom `CUSTOMERS` :

- `RefPayloads.DOMAINS` porte `CUSTOMERS` — le domaine est tiré par les
  caisses comme les articles et les prix.
- `EngineFeedRelayResource.delegates()` route le code `CUSTOMERS` vers
  `accountCustomerCsvResource.importCustomers(stream, mode)` : la porte
  d'entrée CSV existe aux trois échelons.
- `RefApplyService` applique les clients en compte, champs libres et origine
  comprises. `SyncOutboxService` les remonte.

**Le seul manque était la VISIBILITÉ de l'échec.** Une ligne `RefState` n'est
écrite qu'en cas de succès : un domaine qui échouait à s'appliquer ne laissait
aucune trace. Les domaines qui sont AUSSI des flux moteur portaient leur
erreur sur la ligne de flux ; les dix qui ne sont que des domaines de tirage —
dont `CUSTOMERS` — n'en portaient aucune.

Corrigé : `RefPullService` retient la dernière erreur par domaine, l'oublie
quand le domaine s'applique, et `/admin/sync` affiche la colonne. Un domaine
en échec qui n'a jamais rien appliqué apparaît quand même dans la table.

**Effet de bord voulu, et il vaut plus que les cinq lignes.** `pullOnce()`
laissait la première exception interrompre le cycle : un domaine fautif
empêchait les suivants d'être tirés. Chaque domaine est désormais isolé, le
cycle continue, et l'échec est rapporté à la fin.

## Écart réel, constructible (5 lignes)

| Ligne | Ce qui manque |
|---|---|
| BO-08-01-17 | Graphique, vision agrégée par échelon, export XLS/CSV. La liste existe, le reste non |
| BO-08-04-15 | Même chose côté activité, plus l'état des périphériques |
| BO-08-04-16 | Tableau de bord santé des caisses avec filtre sur les caisses en anomalie |
| BO-08-02-02 | Le mécanisme existe (31 appels à `TechnicalEventService`), « chaque utilisation de fonction » non |
| BO-08-04-20 | Inventaire matériel. `impos-hw` a un paquet `discovery` ; rien ne remonte au back-office |

## Hors produit — à assumer en `3` (12 lignes)

BO-08-03-01 à -07, -09, -10 : prérequis avant montée de version, sélection des
points de vente à mettre à jour, programmation horaire des lots de
déploiement, phases pilotes, IHM de suivi des déploiements. Plus BO-08-04-14
(retour automatique à la version précédente) et BO-08-04-17 (prise en main à
distance).

Cela décrit un gestionnaire de parc applicatif. Les coter autrement serait
mentir, et le commentaire d'éditeur doit le dire une fois pour toutes.

## Reste

BO-08-01-19 — « motifs associés aux TVA » : aucun référentiel de motifs de TVA
n'existe. Reste `3`.

## Effet chiffré

- Sans rien développer : **2 -> 10 lignes en `1`**, les huit promotions.
- Avec un imedge `pos.role=central` dans la pile de démo : quatorze lignes de
  plus deviennent démontrables, dont quatre attendent en plus le bouton de
  relance côté caisse.
- Avec l'erreur par domaine visible sur `/admin/sync` : les cinq lignes
  « clients en compte » quittent `3` sur les mêmes bases que les huit autres.
- Reste alors cinq lignes d'IHM à construire, douze hors produit et une sans
  référentiel.
