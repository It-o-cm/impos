#!/usr/bin/env bash
# demo-stack.sh — starts the demo's FOUR applications, feeds the whole
# stack THROUGH THE STORE NODE (single-import-line doctrine: one injection
# point, everything downstream is diffusion), waits until the engine
# acknowledged every feed, runs the automated demo pre-flight (Demo*), and
# leaves the stack RUNNING and demo-ready on green.
#
# Data path demonstrated here:
#   demo/feeds/*.csv --> STORE NODE (imports + verbatim ENGINE_FEEDS
#   capture) --> referential pull (5s in demo, ENGINE_FEEDS included) -->
#   register --> delivery loop (5s in demo) --> imvaluation (empty at
#   boot: its own seed is DISABLED by this script).
#
# Injecting at the register instead would NOT survive: the register pulls
# its referential from the store node every cycle, and the pull DEACTIVATES
# whatever the store node does not know — a catalog injected register-side
# is wiped back to the store's referential within one pull period.
#
# Usage:
#   ./demo-stack.sh            # start stack, feed via impos, pre-flight
#
# The stack is the whole ecosystem, on four ports:
#   8090 imvaluation (engine)   8060 imfid (loyalty)
#   8070 the STORE NODE         8080 the register
# Store node and register are TWO PROJECTS since the imedge split: the node is
# imedge, the register is impos. They still need two ports and two databases
# (one H2 file for both locks itself — the trap documented in imedge's
# sh/imedge.sh).
#   ./demo-stack.sh --no-tests # same without the pre-flight
#   ./demo-stack.sh stop       # stop everything started by this script
#
# Layout assumption (override by env): the four workspaces are siblings —
#   IMPOS_DIR       (default: the directory containing this script's parent)
#   IMEDGE_DIR      (default: $IMPOS_DIR/../imedge)
#   IMVALUATION_DIR (default: $IMPOS_DIR/../imvaluation)
#   IMFID_DIR       (default: $IMPOS_DIR/../imfid)
set -u

lsof -ti :8080 | xargs kill
lsof -ti :8060 | xargs kill
lsof -ti :8090 | xargs kill
lsof -ti :8070 | xargs kill

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# Location-agnostic: the script may live at the impos root OR under demo/ —
# the impos root is wherever the pom.xml is.
if [ -z "${IMPOS_DIR:-}" ]; then
  if [ -f "$SCRIPT_DIR/pom.xml" ]; then IMPOS_DIR="$SCRIPT_DIR"
  else IMPOS_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"; fi
fi
if [ ! -f "$IMPOS_DIR/pom.xml" ]; then
  echo "✗ Racine impos introuvable ($IMPOS_DIR sans pom.xml) — exporter IMPOS_DIR."; exit 1
fi
IMEDGE_DIR="${IMEDGE_DIR:-$IMPOS_DIR/../imedge}"
if [ ! -f "$IMEDGE_DIR/pom.xml" ]; then
  echo "✗ Racine imedge introuvable ($IMEDGE_DIR sans pom.xml) — exporter IMEDGE_DIR."; exit 1
fi
IMVALUATION_DIR="${IMVALUATION_DIR:-$IMPOS_DIR/../imvaluation}"
IMFID_DIR="${IMFID_DIR:-$IMPOS_DIR/../imfid}"
FEEDS_DIR="$IMPOS_DIR/demo/feeds"
RUN_DIR="$IMPOS_DIR/.demo-stack"
# Compte ADMIN du seed (DataInitializer) : identifiant "admin", mot de passe
# BACK-OFFICE "admin" -- surtout pas le PIN "0000", qui n'ouvre que la caisse.
# Depuis que la securite HTTP est active ces identifiants sont reellement
# verifies contre la table employees : ce couple doit correspondre au seed,
# sinon les injections ci-dessous echouent en 401.
ADMIN_AUTH="admin:admin"
mkdir -p "$RUN_DIR"

# ---------- stop ----------
if [ "${1:-}" = "stop" ]; then
  for pidfile in "$RUN_DIR"/*.pid; do
    [ -e "$pidfile" ] || continue
    pid=$(cat "$pidfile")
    name=$(basename "$pidfile" .pid)
    if kill -0 "$pid" 2>/dev/null; then
      echo "Arrêt de $name (pid $pid)…"
      kill "$pid" 2>/dev/null
    fi
    rm -f "$pidfile"
  done
  echo "Pile arrêtée."
  exit 0
fi

if [ ! -d "$FEEDS_DIR" ]; then
  echo "✗ Fichiers de flux introuvables: $FEEDS_DIR"; exit 1
fi

# ---------- helpers ----------
start_app() { # name dir port extra_args…
  local name="$1" dir="$2" port="$3"; shift 3
  if curl -s -o /dev/null --max-time 1 "http://localhost:$port/q/health" \
     || curl -s -o /dev/null --max-time 1 "http://localhost:$port/"; then
    echo "· $name déjà actif sur :$port — réutilisé (état du jour NON réinitialisé :"
    echo "  la règle quotidienne de cagnotte peut refuser un second pre-flight)."
    return 0
  fi
  if [ ! -d "$dir" ]; then
    echo "✗ $name introuvable: $dir"
    echo "  (exporter IMVALUATION_DIR / IMFID_DIR / IMPOS_DIR selon le cas)"
    exit 1
  fi
  echo "· Démarrage de $name ($dir, port $port)…"
  ( cd "$dir" && nohup mvn -q quarkus:dev -Dquarkus.console.enabled=false "$@" \
      > "$RUN_DIR/$name.log" 2>&1 & echo $! > "$RUN_DIR/$name.pid" )
}

wait_ready() { # name port timeout_s
  local name="$1" port="$2" timeout="$3" waited=0
  printf "· Attente de %s sur :%s " "$name" "$port"
  while [ $waited -lt "$timeout" ]; do
    if curl -s -o /dev/null --max-time 1 "http://localhost:$port/q/health" \
       || curl -s -o /dev/null --max-time 1 "http://localhost:$port/"; then
      echo "— prêt (${waited}s)."; return 0
    fi
    sleep 2; waited=$((waited+2)); printf "."
  done
  echo; echo "✗ $name muet après ${timeout}s — voir $RUN_DIR/$name.log"; exit 1
}

inject_feed() { # path label file
  local path="$1" label="$2" file="$3" http
  http=$(curl -s -o "$RUN_DIR/inject-$label.json" -w "%{http_code}" \
      -u "$ADMIN_AUTH" -H "Content-Type: text/plain; charset=utf-8" \
      --data-binary "@$FEEDS_DIR/$file" "http://localhost:8070$path")
  if [ "$http" != "200" ]; then
    echo "✗ Injection $label refusée (HTTP $http) — voir $RUN_DIR/inject-$label.json"
    exit 1
  fi
  echo "  · $label injecté."
}

inject_nomenclature() { # scheme label enseigne file
  local scheme="$1" label="$2" enseigne="$3" file="$4" http
  http=$(curl -s -o "$RUN_DIR/inject-NOMENCLATURE.json" -w "%{http_code}" \
      -u "$ADMIN_AUTH" -H "Content-Type: text/plain; charset=utf-8" \
      --data-binary "@$FEEDS_DIR/$file" \
      "http://localhost:8070/nomenclatures/import?scheme=$scheme&label=$label&enseigne=$enseigne")
  if [ "$http" != "200" ]; then
    echo "✗ Injection NOMENCLATURE refusée (HTTP $http) — voir $RUN_DIR/inject-NOMENCLATURE.json"
    exit 1
  fi
  echo "  · NOMENCLATURE injectée ($(sed 's/.*createdCount.:\([0-9]*\).*/\1/' "$RUN_DIR/inject-NOMENCLATURE.json" 2>/dev/null) nœuds créés)."
}

# ---------- 1. le moteur (VIDE: son seed est coupé) et le programme ----------
# Le moteur démarre sans données: tout ce qu'il saura viendra d'impos par la
# livraison des flux — c'est la démonstration du canal magasin→caisse→moteur.
start_app imvaluation "$IMVALUATION_DIR" 8090 -Dvaluation.bootstrap.data.enabled=false
start_app imfid       "$IMFID_DIR"       8060
wait_ready imvaluation 8090 180
wait_ready imfid       8060 180

# ---------- 2. le nœud magasin (projet imedge, base à part) ---------------
# Il monte AVANT la caisse pour que celle-ci ait où pousser dès son premier
# ticket. Mode dev obligatoire: le seed du référentiel est @IfBuildProfile
# (dev, test), un jar de prod ne le contient pas et le nœud refuserait toute
# ingestion par « 409 Aucun magasin ».
# Le port DOIT etre passe a l'application: le 3e argument de start_app ne sert
# qu'a l'attente de disponibilite. Sans -Dquarkus.http.port le noeud demarre
# sur le 8080 par defaut, wait_ready interroge le 8070 dans le vide et la pile
# s'arrete sur « store-node muet ».
# pos.role n'est plus passe ici: imedge porte pos.role=store dans son propre
# application.properties, c'est le noeud.
# URL moteur VIDE au noeud: sa livraison des flux est coupée — c'est la
# caisse qui livre le moteur, avec les ENGINE_FEEDS qu'elle tire du noeud.
# Sans cela les deux instances livreraient le même moteur en double.
start_app store-node "$IMEDGE_DIR" 8070 \
    -Dquarkus.http.port=8070 \
    -Dquarkus.datasource.jdbc.url="jdbc:h2:file:./data/store-node" \
    -Dpos.valuation.url=
wait_ready store-node 8070 180

# ---------- 3. la caisse de démo (livraison des flux accélérée à 5s) -------
# pos.sync.store-url fait remonter ventes, sessions et appels superviseur
# au nœud (sans lui le dashboard magasin reste à zéro), ET arme le tirage
# référentiel descendant — accéléré à 5s ici pour que la caisse reflète
# l'injection au nœud sans attendre les 300s de production.
start_app impos "$IMPOS_DIR" 8080 -Dpos.valuation.feed-delivery-seconds=5 \
    -Dpos.sync.store-url=http://localhost:8070 \
    -Dpos.referential.pull-seconds=5
wait_ready impos 8080 180

# ---------- 4. l'alimentation PAR LE NŒUD MAGASIN ----------
# Tous les flux entrent au nœud (8070): les fichiers partagés y sont
# appliqués au référentiel magasin ET capturés verbatim; les fichiers
# propres au moteur passent par le relais (capturés sans être ouverts).
# La caisse reçoit ensuite TOUT par le tirage (référentiel + ENGINE_FEEDS)
# et livre elle-même le moteur. Grammaire unifiée /feeds/import/{code}:
# les codes partagés sont délégués aux imports dédiés, les codes propres
# au moteur sont capturés verbatim. Ordre du catalogue: partagés avant
# spécifiques, offres en dernier.
# NB: STORES n'est pas un domaine du tirage — la fiche magasin de la
# caisse reste celle de son seed; stores.csv sert le nœud et le moteur.
echo "· Injection des flux dans le nœud magasin (ordre du catalogue)…"
inject_feed "/feeds/import/STORES"               STORES            stores.csv
inject_feed "/feeds/import/STORE_GROUPS"         STORE_GROUPS      store-groups.csv
inject_feed "/feeds/import/PRODUCTS"             PRODUCTS          products.csv
inject_feed "/feeds/import/FAMILIES"             FAMILIES          product-families.csv
inject_feed "/feeds/import/CATEGORY_STORAGES"    CATEGORY_STORAGES product-category-storages.csv
inject_feed "/feeds/import/PRICES"               PRICES            prices.csv
inject_feed "/feeds/import/OFFERS"               OFFERS            offers.csv

# La NOMENCLATURE ne passe pas par la grammaire /feeds/import/{code}, et ce
# n'est pas un oubli: cette grammaire n'a pas de place pour dire DANS QUELLE
# nomenclature charger. Deux enseignes publient les mêmes colonnes et rien
# dans le fichier ne les distingue, donc le schéma se nomme sur la requête.
# Le fichier est l'export réel du groupe: 2504 nœuds, quatre niveaux de 2, 4,
# 8 et 12 caractères, le niveau le plus fin entièrement CUSTOM.
inject_nomenclature ITM_FR "Nomenclature+ITM+France" ITM hierarchies.csv

# ---------- 5. attendre l'ACK du moteur sur les 7 flux ----------
# Le statut est lu SUR LA CAISSE (8080): un flux n'y existe qu'une fois
# tiré du nœud, et n'est 'applied' qu'une fois le moteur servi — ce
# compteur 7/7 prouve donc la chaîne entière nœud → tirage → caisse →
# livraison → moteur.
printf "· Livraison au moteur (ACK 7/7 attendu) "
waited=0
while [ $waited -lt 120 ]; do
  applied=$(curl -s -u "$ADMIN_AUTH" "http://localhost:8080/feeds/import/status" \
      | grep -o '"applied":true' | wc -l | tr -d ' ')
  if [ "$applied" = "7" ]; then echo "— moteur à jour (${waited}s)."; break; fi
  sleep 2; waited=$((waited+2)); printf "."
done
if [ "${applied:-0}" != "7" ]; then
  echo
  echo "✗ Moteur pas à jour après 120s ($applied/7 flux acquittés) :"
  curl -s -u "$ADMIN_AUTH" "http://localhost:8080/feeds/import/status"; echo
  echo "  — voir $RUN_DIR/store-node.log (imports), $RUN_DIR/impos.log (tirage" 
  echo "    + livraison) et $RUN_DIR/imvaluation.log"
  exit 1
fi

# ---------- 6. le pre-flight (la démo déroulée automatiquement) ----------
if [ "${1:-}" != "--no-tests" ]; then
  echo "· Pre-flight de démo (Demo*) — la caisse de test boote, le parcours se joue…"
  ( cd "$IMPOS_DIR" && mvn verify -DskipITs=false -DskipUTs=true -Dit.test='Demo*' ) \
    || { echo "✗ PRE-FLIGHT ROUGE — la démo n'est PAS prête (la pile reste up pour diagnostic)."; exit 1; }
  echo "✓ PRE-FLIGHT VERT — tout le parcours de démo est opérationnel."
fi

cat <<READY

════════════════════════════════════════════════════════
  PILE DE DÉMO PRÊTE — quatre applications, alimentées PAR LE NŒUD MAGASIN
    Caisse        http://localhost:8080/
    Simulateur    $IMPOS_DIR/docs/simulateur.html   (page STATIQUE: 'open' ce
                  fichier — l'appli ne la sert pas, elle parle au 8080)
    Écran client  http://localhost:8080/customer
    NŒUD MAGASIN  http://localhost:8070             ← l'adresse seule suffit
    Dashboard     http://localhost:8070/dashboard   ← LE seul agrégateur
    Nomenclature  http://localhost:8070/admin/nomenclatures  (2504 nœuds réels)
    Back-office   http://localhost:8070/admin       (admin / admin) — LE back-office,
                  et le seul : la caisse n'en sert plus un seul écran, ils ont
                  quitté impos avec le journal, les imports et GraphQL.
    Moteur        http://localhost:8090/   (IHM admin — données reçues d'impos)
    imfid         http://localhost:8060/   (IHM programme)
    État des flux http://localhost:8080/feeds/import/status  (sans identifiants :
                  la caisse n'a plus de surface HTTP authentifiée)
  Logs: $RUN_DIR/   —   Arrêt: ./demo-stack.sh stop
  La caisse pousse vers :8070 — le dashboard se remplit à la première vente.
════════════════════════════════════════════════════════
READY
