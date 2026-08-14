#!/usr/bin/env bash
# demo-stack.sh — starts the demo's three applications, waits until they
# answer, runs the automated demo pre-flight (Demo*), and leaves the whole
# stack RUNNING and demo-ready on green. The register itself boots twice:
# once as the @QuarkusTest instance the pre-flight drives, then as the real
# quarkus:dev register the demo uses.
#
# Usage:
#   ./demo/demo-stack.sh            # start engine + imfid, pre-flight, then the register
#   ./demo/demo-stack.sh --no-tests # just start the stack (no pre-flight)
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
RUN_DIR="$IMPOS_DIR/.demo-stack"
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

# ---------- 1. le moteur et le programme ----------
start_app imvaluation "$IMVALUATION_DIR" 8090
start_app imfid       "$IMFID_DIR"       8060
wait_ready imvaluation 8090 180
wait_ready imfid       8060 180

# ---------- 2. le pre-flight (la démo déroulée automatiquement) ----------
if [ "${1:-}" != "--no-tests" ]; then
  echo "· Pre-flight de démo (Demo*) — la caisse de test boote, le parcours se joue…"
  ( cd "$IMPOS_DIR" && mvn verify -DskipITs=false -DskipUTs=true -Dit.test='Demo*' ) \
    || { echo "✗ PRE-FLIGHT ROUGE — la démo n'est PAS prête (la pile reste up pour diagnostic)."; exit 1; }
  echo "✓ PRE-FLIGHT VERT — tout le parcours de démo est opérationnel."
fi

# ---------- 3. la caisse de démo ----------
start_app impos "$IMPOS_DIR" 8080
wait_ready impos 8080 180

cat <<READY

════════════════════════════════════════════════════════
  PILE DE DÉMO PRÊTE
    Caisse        http://localhost:8080/
    Simulateur    http://localhost:8080/simulateur
    Écran client  http://localhost:8080/customer
    Moteur        http://localhost:8090/   (IHM admin)
    imfid         http://localhost:8060/   (IHM programme)
  Logs: $RUN_DIR/   —   Arrêt: ./demo/demo-stack.sh stop
  Module XIII (nœud magasin) : à lancer à part (voir PDF 0.1).
════════════════════════════════════════════════════════
READY
