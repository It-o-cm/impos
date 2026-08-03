# Tiroir — la moitié sortante du matériel existe-t-elle en test ?

Question : le simulateur matériel embarqué expose-t-il les endpoints que le
rest-client hardware appelle (ouverture tiroir, statut tiroir) ? On compare les
chemins de l'interface rest-client avec ceux des resources du simulateur.

## Comparaison chemin par chemin

- **Rest-client** : `HardwareClient` — `@Path("/api/hardware")`,
  `@RegisterRestClient(configKey = "hardware-api")`
  (`src/main/java/com/intermarche/pos/ui/hardware/HardwareClient.java`).
- **Simulateur** : `MockHardwareResource` — `@Path("/api/hardware")`,
  `@ApplicationScoped` (`src/mock/java/com/intermarche/pos/ui/resource/MockHardwareResource.java`).

| Appel client (méthode) | Verbe + chemin complet | Endpoint simulateur | Match |
|---|---|---|---|
| `getWeight()` | `GET /api/hardware/weight` | `GET /weight` (l.41) | ✅ |
| `setDisplay()` | `POST /api/hardware/display` | `POST /display` (l.58) | ✅ |
| **`openDrawer()`** | **`POST /api/hardware/drawer/open`** | **`POST /drawer/open` (l.75)** | ✅ |
| **`getDrawerStatus()`** | **`GET /api/hardware/drawer/status`** | **`GET /drawer/status` (l.89)** | ✅ |
| `printTicket()` | `POST /api/hardware/printer/print` | `POST /printer/print` (l.102) | ✅ |
| `cutPaper()` | `POST /api/hardware/printer/cut` | `POST /printer/cut` (l.129) | ✅ |

**Les 6 appels sortants du rest-client sont couverts** — en particulier les deux
visés : **ouverture tiroir** et **statut tiroir**. Le simulateur est même un
sur-ensemble : il ajoute des surfaces de pilotage/test (`POST /set-weight`,
`GET /display`, **`POST /drawer/close`**, `GET /printer/content`,
`GET /printer/status`, `POST /printer/toggle-paper`, `POST /printer/clear`).
Le `POST /drawer/close` est précisément le geste de fermeture physique dont on a
besoin.

### Verdict : **OUI**

La moitié sortante existe bel et bien en test. On prend la branche « Si oui ».
Réserve unique, structurelle (déjà traitée) : `MockHardwareResource` vit dans
`src/mock/java`, une racine de sources **non câblée** au build ; sans câblage,
l'appli de test ne sert pas ces routes (404). Elle est désormais ajoutée en
scope **test** via `build-helper-maven-plugin` (`add-test-source`) — jamais dans
le jar de prod.

## Actions prises (branche « Si oui »)

1. **URL du rest-client pointée sur l'appli de test elle-même.** Dans le profil
   partagé (`GroupAIT.E2eProfile`, `getConfigOverrides`) :
   `quarkus.rest-client.hardware-api.url = http://localhost:8081` (le port
   `@QuarkusTest`). Les appels matériels frappent donc le simulateur embarqué et
   répondent 200 — plus de `ConnectException`/404, plus de stacktraces
   `HardwareService`.
   > Nota : le contrat parle d'un « Shared QuarkusTestProfile » ; il est pour
   > l'instant imbriqué dans `GroupAIT` sous le nom `E2eProfile`. Son extraction
   > en classe top-level partagée reste une amélioration ouverte (cf.
   > `reports/calibrage-playwright-A1.md`).

2. **Geste de fermeture du tiroir ajouté à la recette de login du contrat**
   (CLAUDE.md, bullet « Login is performed on the lock screen … »). Comme le
   caissier pousse physiquement le tiroir : `POST /api/hardware/drawer/close`
   sur le bus, après l'unlock et avant tout écran `@DrawerMustBeClosed`. Le
   test le joue sur l'écran SESSION, juste avant OUVRIR
   (`GroupAIT.java` : `context.request().post(root + "api/hardware/drawer/close", …)`).

3. **La garde tiroir ne bloque plus aucun écran** du parcours A1 (vérifié).

## Couverture de la garde tiroir

`@DrawerMustBeClosed` (appliquée par `DrawerCheckFilter`) couvre : **Home (écran
de VENTE)**, Payment, Manual, Fruits, Fidelity, Reprint, Theme. `/lock`
(`@DrawerMayBeOpen`) et **`/session`** (non annotée) restent **accessibles tiroir
ouvert** — ce qui explique que le fond de caisse se saisit alors que le tiroir
est encore sorti.

Chaîne d'événements réelle en A1 : unlock → `openDrawer()` (tiroir **OPEN**) →
`/session` (non gardé, OK tiroir ouvert) → **fermeture bus** (`/drawer/close`,
tiroir **CLOSED**) → OUVRIR → `/` gardé : `getDrawerStatus()` renvoie `CLOSED`
→ **passage autorisé** → écran de VENTE.

## Preuves (run frais)

`mvn -q verify -Dsurefire.skip=true -Dit.test=GroupAIT -DskipITs=false` → **RC=0**.

- Failsafe : `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0` —
  `Time elapsed: 10.52 s`.
- Classe simulateur déployée : `target/test-classes/…/MockHardwareResource.class`.
- Timeline matériel côté IT (thread `executor-thread-1`), **seule** ligne :
  `INFO … Ordre d'ouverture du tiroir envoyé.` (l'ordre part vers le mock,
  répond 200).
- Recherche sur le thread IT de `Accès bloqué` (garde tiroir),
  `HardwareService … ERROR`, `ResteasyNotFoundException`/`404 Not Found`,
  `ConnectException` → **NONE**. La garde ne dévie plus vers `/drawer-error` et
  aucune stacktrace matériel n'apparaît.

> Rappel : les 9 `ERROR HardwareService` restant dans un `mvn verify` complet
> proviennent de `HardwareServiceTest` (tests unitaires à échecs injectés
> volontaires, thread `main`) — sans rapport avec l'IT. En run IntelliJ de
> `GroupAIT` seul, le log est intégralement propre.

## Périmètre

Rien n'est committé. Fichiers touchés cette itération : `CLAUDE.md` (recette de
login + bullet profil), `reports/tiroir.md` (ce document). L'URL du profil et le
câblage `src/mock` / le geste de fermeture dans `GroupAIT.java` et `pom.xml`
étaient déjà en place à l'itération précédente ; ce rapport les confirme par la
comparaison des chemins et un run de vérification.
