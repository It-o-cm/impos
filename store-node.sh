#!/usr/bin/env bash
# store-node.sh — the MODULE XIII companion: the store node is THE SAME
# application in another role, so it needs its own port AND its own database
# (two instances on one H2 file lock each other — the classic trap).
#
# Usage:
#   ./demo/store-node.sh          # package if needed, start the node, wait for it
#   ./demo/store-node.sh test     # start it (if down) then run DemoStoreIT against it
#   ./demo/store-node.sh stop     # stop the node started by this script
#   ./demo/store-node.sh status   # is it answering?
#
# TWO settings decide everything here:
#  - pos.role=store: without it the node boots in "register" role and every
#    ingestion/export endpoint answers 403 off-role.
#  - QUARKUS DEV MODE: the referential seed (DataInitializer) is annotated
#    @IfBuildProfile(dev, test), a BUILD-time filter — a prod-packaged jar
#    simply does not contain it so the node would start with an empty
#    database and refuse every ingestion with "409 Aucun magasin sur le nœud
#    magasin". No runtime flag can bring it back; the node must run in dev,
#    like the engine and imfid do.
#
# Env overrides: STORE_PORT (8082), STORE_DB (./data/store-node), IMPOS_DIR,
# STORE_TOKEN (shared X-Sync-Token, only if your registers send one).
set -u

lsof -ti :8082 | xargs kill
lsof -ti :8080 | xargs kill

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -z "${IMPOS_DIR:-}" ]; then
  if [ -f "$SCRIPT_DIR/pom.xml" ]; then IMPOS_DIR="$SCRIPT_DIR"
  else IMPOS_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"; fi
fi
if [ ! -f "$IMPOS_DIR/pom.xml" ]; then
  echo "✗ Racine impos introuvable ($IMPOS_DIR sans pom.xml) — exporter IMPOS_DIR."; exit 1
fi

STORE_PORT="${STORE_PORT:-8082}"
STORE_DB="${STORE_DB:-./data/store-node}"
STORE_URL="http://localhost:$STORE_PORT"
RUN_DIR="$IMPOS_DIR/.demo-stack"
PID_FILE="$RUN_DIR/store-node.pid"
LOG_FILE="$RUN_DIR/store-node.log"
mkdir -p "$RUN_DIR"

is_up() { curl -s -o /dev/null --max-time 1 "$STORE_URL/dashboard-data"; }

case "${1:-start}" in

  stop)
    if [ -f "$PID_FILE" ]; then
      pid=$(cat "$PID_FILE")
      kill "$pid" 2>/dev/null && echo "Nœud magasin arrêté (pid $pid)."
      rm -f "$PID_FILE"
    else
      echo "Aucun nœud magasin lancé par ce script."
      echo "S'il tourne quand même : lsof -ti :$STORE_PORT | xargs kill"
    fi
    exit 0
    ;;

  status)
    if is_up; then echo "✓ Nœud magasin actif — $STORE_URL/dashboard"
    else echo "✗ Nœud magasin muet sur :$STORE_PORT"; fi
    exit 0
    ;;

  start|test) ;;
  *) echo "Usage: $0 [start|test|stop|status]"; exit 1 ;;
esac

# ---------- start ----------
if is_up; then
  # A node this script did not start is a TRAP: it may be the prod-packaged
  # jar (no referential seed → "409 Aucun magasin") or a register-role node
  # (403 off-role), and reusing it silently costs a full 40 s test run to
  # rediscover. Refuse rather than reuse what we cannot vouch for.
  if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    echo "· Nœud magasin déjà actif sur :$STORE_PORT (lancé par ce script) — réutilisé."
  else
    echo "✗ Un nœud NON GÉRÉ par ce script occupe :$STORE_PORT."
    echo "  Il tourne peut-être en prod (pas de seed → 409 « Aucun magasin »)"
    echo "  ou en rôle caisse (403 hors-rôle). Le tuer puis relancer :"
    echo "      lsof -ti :$STORE_PORT | xargs kill"
    echo "      $0 ${1:-start}"
    exit 1
  fi
else
  echo "· Démarrage du nœud magasin en DEV (port $STORE_PORT, base $STORE_DB)…"
  TOKEN_ARG=""
  if [ -n "${STORE_TOKEN:-}" ]; then TOKEN_ARG="-Dpos.sync.token=$STORE_TOKEN"; fi
  ( cd "$IMPOS_DIR" && nohup mvn -q quarkus:dev -Dquarkus.console.enabled=false \
      -Dquarkus.http.port="$STORE_PORT" \
      -Dquarkus.datasource.jdbc.url="jdbc:h2:file:$STORE_DB" \
      -Dpos.role=store \
      -Ddebug=false \
      $TOKEN_ARG \
      > "$LOG_FILE" 2>&1 & echo $! > "$PID_FILE" )

  printf "· Attente de :%s " "$STORE_PORT"
  waited=0
  while [ $waited -lt 120 ]; do
    if is_up; then echo "— prêt (${waited}s)."; break; fi
    sleep 2; waited=$((waited+2)); printf "."
  done
  if ! is_up; then
    echo; echo "✗ Muet après ${waited}s — voir $LOG_FILE"; exit 1
  fi
fi

# ---------- test ----------
if [ "${1:-start}" = "test" ]; then
  echo "· DemoStoreIT contre le nœud magasin…"
  ( cd "$IMPOS_DIR" && mvn verify -DskipITs=false -DskipUTs=true -Dit.test='DemoStoreIT' ) \
    || { echo "✗ MODULE XIII ROUGE (le nœud reste up pour diagnostic)."; exit 1; }
  echo "✓ MODULE XIII VERT — la vente remonte au magasin."
fi

cat <<READY

════════════════════════════════════════════════════════
  NŒUD MAGASIN PRÊT (module XIII)
    Dashboard     $STORE_URL/dashboard      ← LE seul agrégateur
    Rôle          pos.role=store (sans lui : 403 hors-rôle sur tout)
    Mode          dev (le seed du référentiel n'existe pas en prod)
    Superviseur   depuis la caisse : menu AUTRES → APPEL SUPERVISEUR
  Log: $LOG_FILE   —   Arrêt: ./demo/store-node.sh stop

  ⚠ La CAISSE doit savoir où pousser : elle doit tourner avec
      -Dpos.sync.store-url=$STORE_URL
    (sinon le dashboard restera à zéro — ce n'est pas un bug).
════════════════════════════════════════════════════════
READY
