#!/usr/bin/env bash
# demo-stack.sh — starts the demo's three applications, feeds the whole
# stack THROUGH IMPOS (single-import-line doctrine: the engine receives its
# data from the register's feed delivery, never from its own seed), waits
# until the engine acknowledged every feed, runs the automated demo
# pre-flight (Demo*), and leaves the stack RUNNING and demo-ready on green.
#
# Data path demonstrated here:
#   demo/feeds/*.csv --> impos (imports + relay) --> engine_feeds -->
#   delivery loop (5s in demo) --> imvaluation (empty at boot: its own
#   seed is DISABLED by this script).
#
# Usage:
#   ./demo/demo-stack.sh            # start stack, feed via impos, pre-flight
#   ./demo/demo-stack.sh --no-tests # same without the pre-flight
#   ./demo/demo-stack.sh stop       # stop everything started by this script
#
# Layout assumption (override by env): the three workspaces are siblings —
#   IMPOS_DIR       (default: the directory containing this script's parent)
#   IMVALUATION_DIR (default: $IMPOS_DIR/../imvaluation)
#   IMFID_DIR       (default: $IMPOS_DIR/../imfid)
set -u

lsof -ti :8080 | xargs kill
lsof -ti :8060 | xargs kill
lsof -ti :8090 | xargs kill

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
IMVALUATION_DIR="${IMVALUATION_DIR:-$IMPOS_DIR/../imvaluation}"
IMFID_DIR="${IMFID_DIR:-$IMPOS_DIR/../imfid}"
FEEDS_DIR="$IMPOS_DIR/demo/feeds"
RUN_DIR="$IMPOS_DIR/.demo-stack"
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
      --data-binary "@$FEEDS_DIR/$file" "http://localhost:8080$path")
  if [ "$http" != "200" ]; then
    echo "✗ Injection $label refusée (HTTP $http) — voir $RUN_DIR/inject-$label.json"
    exit 1
  fi
  echo "  · $label injecté."
}

# ---------- 1. le moteur (VIDE: son seed est coupé) et le programme ----------
# Le moteur démarre sans données: tout ce qu'il saura viendra d'impos par la
# livraison des flux — c'est la démonstration du canal magasin→caisse→moteur.
start_app imvaluation "$IMVALUATION_DIR" 8090 -Dvaluation.bootstrap.data.enabled=false
start_app imfid       "$IMFID_DIR"       8060
wait_ready imvaluation 8090 180
wait_ready imfid       8060 180

# ---------- 2. la caisse de démo (livraison des flux accélérée à 5s) ----------
start_app impos "$IMPOS_DIR" 8080 -Dpos.valuation.feed-delivery-seconds=5
wait_ready impos 8080 180

# ---------- 3. l'alimentation PAR impos ----------
# Les fichiers partagés passent par les imports de la caisse (appliqués au
# référentiel POS + capturés verbatim); les fichiers propres au moteur
# passent par le relais (capturés sans être ouverts). Ordre du catalogue:
# partagés avant spécifiques, offres en dernier.
echo "· Injection des flux dans impos (ordre du catalogue)…"
inject_feed "/stores/import"                     STORES            stores.csv
inject_feed "/feeds/import/STORE_GROUPS"         STORE_GROUPS      store-groups.csv
inject_feed "/products/import"                   PRODUCTS          products.csv
inject_feed "/product-families/import"           FAMILIES          product-families.csv
inject_feed "/feeds/import/CATEGORY_STORAGES"    CATEGORY_STORAGES product-category-storages.csv
inject_feed "/prices/import"                     PRICES            prices.csv
inject_feed "/feeds/import/OFFERS"               OFFERS            offers.csv

# ---------- 4. attendre l'ACK du moteur sur les 7 flux ----------
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
  echo "  — voir $RUN_DIR/impos.log et $RUN_DIR/imvaluation.log"
  exit 1
fi

# ---------- 5. le pre-flight (la démo déroulée automatiquement) ----------
if [ "${1:-}" != "--no-tests" ]; then
  echo "· Pre-flight de démo (Demo*) — la caisse de test boote, le parcours se joue…"
  ( cd "$IMPOS_DIR" && mvn verify -DskipITs=false -DskipUTs=true -Dit.test='Demo*' ) \
    || { echo "✗ PRE-FLIGHT ROUGE — la démo n'est PAS prête (la pile reste up pour diagnostic)."; exit 1; }
  echo "✓ PRE-FLIGHT VERT — tout le parcours de démo est opérationnel."
fi

cat <<READY

════════════════════════════════════════════════════════
  PILE DE DÉMO PRÊTE — moteur alimenté PAR impos
    Caisse        http://localhost:8080/
    Simulateur    http://localhost:8080/simulateur
    Écran client  http://localhost:8080/customer
    Moteur        http://localhost:8090/   (IHM admin — données reçues d'impos)
    imfid         http://localhost:8060/   (IHM programme)
    État des flux http://localhost:8080/feeds/import/status  (admin/admin)
  Logs: $RUN_DIR/   —   Arrêt: ./demo/demo-stack.sh stop
  Module XIII (nœud magasin) : à lancer à part (voir PDF 0.1).
════════════════════════════════════════════════════════
READY
