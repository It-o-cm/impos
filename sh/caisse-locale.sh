#!/usr/bin/env bash
# Runs ON THE TILL, as root. Starts the hardware daemon, the register, loads
# the CSV feeds and opens the two kiosk windows, without going through
# sh/caisse.sh.
#
# sh/caisse.sh drives this same till from a Mac: it builds, uploads and then
# runs every step over ssh. This script is the till-side half alone. It builds
# nothing and uploads nothing -- it expects the two jars and the feeds
# directory to be here already -- and it runs the exact command lines and the
# exact feed sequence caisse.sh sends, so a register started by either is the
# same register with the same catalogue.
#
# The import is not optional by accident: the database is H2 in memory, it dies
# with the process, so every start begins on an empty catalogue and the feeds
# have to go back in. Skipping them leaves a register that opens, logs a
# cashier in and finds no article.
#
#   ./caisse-locale.sh              daemon, register, feeds, kiosk
#   ./caisse-locale.sh --07039      and then the 07039 catalogue on top
#   ./caisse-locale.sh --no-feeds   no import at all
#   ./caisse-locale.sh --no-hw      no hardware daemon
#   ./caisse-locale.sh --no-kiosk   no Chrome window
#   ./caisse-locale.sh --stop       stops everything, by port
#
# Anything site-specific is in the block below and matches caisse.sh.

set -u

DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
IMPOS_JAR="${IMPOS_JAR:-$DIR/impos.jar}"
HW_JAR="${HW_JAR:-$DIR/impos-hw.jar}"
KIOSK_SCRIPT="${KIOSK_SCRIPT:-$DIR/kiosk.sh}"
POS_PORT='8080'
HW_PORT='8081'
BASE="http://127.0.0.1:$POS_PORT"
# Back-office password of the ADMIN account, used for the HTTP Basic pushes
# below. It appears TWICE and the two must agree: it is exported as
# POS_ADMIN_PASSWORD on the start line, where EmployeeBootstrap uses it to
# create the first administrator on an empty database, and it is the
# BACK_OFFICE_PASSWORD of badge 00000000 in employees.csv, which overwrites
# that account on the first push. Let them diverge and every feed after the
# employees one answers 401.
ADMIN_PASSWORD='admin'
# Payment terminal. The acceptance point number and the island label are
# declared on the payment server and cannot be guessed -- leave either empty
# and the daemon serves without a terminal, its payment endpoints answering
# 503 like any unbound role.
WCL_DIR='/root/Henri/Client_Verifone_13.1.7_Linux_DEV/ClientKStrad'
TPE_STATION='1'
TPE_ILOT='A99054'
# A payment waits for the cardholder, and a login can update the reader's
# firmware. This is its own option on purpose: --timeout bounds the printer,
# the drawer and the scale, which must be seen failing fast.
TPE_TIMEOUT='300000'
# Which terminal impos talks to: bridge = the real one, through the hardware
# daemon; virtual = the simulator panel; auto = no terminal, immediate accept.
TPE_MODE="${TPE_MODE:-bridge}"
# Endorsement printed on the back of a cheque. 180 is the usual orientation;
# set it to 0 if the endorsement comes out upside down.
ENDORSE_ROTATION='180'
# Serial port of the scale. The daemon defaults to /dev/ttyS4, where the
# Checkout-Dialog-06 of the first till was; the instrument now on this till
# answers on ttyS0, so the port is declared here rather than left to a default
# that is one till's history. A scale-test on each ttyS in turn is what finds
# it again the day it moves.
SCALE_PORT="${SCALE_PORT:-/dev/ttyS0}"
# Two runtimes on this till, on purpose. impos stays on 21, the version Quarkus
# is supported on; the hardware daemon needs 25 because the payment client is
# reached through java.lang.foreign, final only from 22 on.
POS_JAVA="${POS_JAVA:-java}"
HW_JAVA="${HW_JAVA:-/opt/java/jdk-25.0.4.1+1/bin/java}"

FEEDS=''
WITH_HW=1
WITH_KIOSK=1
WITH_FEEDS=1
WITH_07039=0
STOP_ONLY=0

# Reads the command line, leaving the flags above set.
parse_options() {
  local arg
  for arg in "$@"; do
    case "$arg" in
      --no-hw) WITH_HW=0 ;;
      --no-kiosk) WITH_KIOSK=0 ;;
      --no-feeds) WITH_FEEDS=0 ;;
      --07039) WITH_07039=1 ;;
      --stop) STOP_ONLY=1 ;;
      --tpe-mode=*) TPE_MODE="${arg#*=}" ;;
      *) echo "Option inconnue : $arg" ; return 1 ;;
    esac
  done
}

# Refuses to go further unless this shell is root: both jars listen on their
# port, the daemon opens the JavaPOS devices and kiosk.sh switches to the
# desktop user, none of which a plain account can do.
require_root() {
  if [ "$(id -u)" != '0' ]; then
    echo "A lancer en root."
    return 1
  fi
}

# Finds the CSV directory, accepting the two layouts that exist: the feeds
# folder copied on its own next to the jar, and a whole checkout where they
# still sit under demo/.
locate_feeds() {
  if [ -d "$DIR/feeds" ]; then
    FEEDS="$DIR/feeds"
  elif [ -d "$DIR/demo/feeds" ]; then
    FEEDS="$DIR/demo/feeds"
  else
    FEEDS=''
  fi
}

# Says what it found before anything is started, so a missing file is read as
# a missing file and not as a silent failure to come.
report_layout() {
  echo "Repertoire      : $DIR"
  printf 'impos.jar       : %s\n' "$([ -f "$IMPOS_JAR" ] && echo "$IMPOS_JAR" || echo "ABSENT ($IMPOS_JAR)")"
  printf 'impos-hw.jar    : %s\n' "$([ -f "$HW_JAR" ] && echo "$HW_JAR" || echo "ABSENT ($HW_JAR)")"
  printf 'java caisse     : %s\n' "$(command -v "$POS_JAVA" || echo "ABSENT ($POS_JAVA)")"
  printf 'java materiel   : %s\n' "$([ -x "$HW_JAVA" ] && echo "$HW_JAVA" || echo "ABSENT ($HW_JAVA)")"
  printf 'kiosk.sh        : %s\n' "$([ -f "$KIOSK_SCRIPT" ] && echo "$KIOSK_SCRIPT" || echo "ABSENT ($KIOSK_SCRIPT)")"
  printf 'CSV             : %s\n' "${FEEDS:-ABSENT ($DIR/feeds ou $DIR/demo/feeds)}"
  printf 'port balance    : %s\n' "$([ -c "$SCALE_PORT" ] && echo "$SCALE_PORT" || echo "ABSENT ($SCALE_PORT)")"
  printf 'mode TPE        : %s\n' "$TPE_MODE"
  echo
}

# Frees a port, whatever holds it, and waits for the kernel to release it.
free_port() {
  fuser -k "$1/tcp" > /dev/null 2>&1
  sleep 2
}

# Starts the hardware daemon, replacing any process holding its port.
#
# setsid and nohup even though nothing here is an ssh channel: the daemon must
# outlive this shell, and a plain background job dies with the terminal it was
# started from.
start_hw() {
  local tpe
  if [ ! -f "$HW_JAR" ]; then
    echo "impos-hw.jar introuvable, pont materiel non demarre."
    return 1
  fi
  if [ ! -x "$HW_JAVA" ]; then
    echo "$HW_JAVA introuvable, pont materiel non demarre."
    return 1
  fi
  tpe=''
  if [ -n "$WCL_DIR" ] && [ -n "$TPE_STATION" ] && [ -n "$TPE_ILOT" ]; then
    tpe="--wcl-dir $WCL_DIR --station $TPE_STATION --ilot $TPE_ILOT --tpe-timeout $TPE_TIMEOUT"
  else
    echo "TPE non declare : le pont servira sans terminal (503 sur les paiements)."
  fi
  free_port "$HW_PORT"
  cd "$DIR" || return 1
  # shellcheck disable=SC2086
  (setsid nohup "$HW_JAVA" -jar "$HW_JAR" serve --port "$HW_PORT" \
      --scale-port "$SCALE_PORT" \
      --endorse-rotation "$ENDORSE_ROTATION" $tpe > "$DIR/hw.log" 2>&1 < /dev/null &)
  sleep 3
  local code
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 \
      "http://127.0.0.1:$HW_PORT/api/hardware/drawer/status")
  echo "pont materiel : HTTP $code"
  tail -8 "$DIR/hw.log"
}

# Starts impos, replacing any process holding its port.
#
# pos.scanner.enabled=false: on THIS till the hardware bridge owns the scanner.
# HardwareClient states the rule -- "the hardware boundary is HTTP, this
# interface is the whole driver" -- and the bridge's scan bridge already reads
# the device and posts each label to /api/pos/scan. Leaving the register's own
# reader on meant two programs reading the same scanner and feeding the same
# register: every label arrived twice. The register's reader stays the fallback
# of a till that runs no bridge, which is why the property is set here and not
# defaulted off in the application.
start_impos() {
  if [ ! -f "$IMPOS_JAR" ]; then
    echo "impos.jar introuvable."
    return 1
  fi
  if ! command -v "$POS_JAVA" > /dev/null 2>&1; then
    echo "$POS_JAVA introuvable."
    return 1
  fi
  free_port "$POS_PORT"
  cd "$DIR" || return 1
  (POS_ADMIN_PASSWORD="$ADMIN_PASSWORD" setsid nohup "$POS_JAVA" \
      -Dquarkus.rest-client.hardware-api.url="http://127.0.0.1:$HW_PORT" \
      -Dpos.tpe.mode="$TPE_MODE" \
      -Dpos.scanner.enabled=false \
      -jar "$IMPOS_JAR" > "$DIR/impos.log" 2>&1 < /dev/null &)
  sleep 2
  wait_pos || return 1
  grep -E 'bootstrap administrator|Listening on|Port '"$POS_PORT" "$DIR/impos.log"
}

# Polls the register until it answers, up to a minute. "000" is curl saying the
# connection never opened, which is the register still starting.
wait_pos() {
  local i code
  i=0
  while [ "$i" -lt 30 ]; do
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$BASE/lock")
    if [ "$code" != "000" ]; then
      echo "impos repond (HTTP $code)"
      return 0
    fi
    i=$((i + 1))
    sleep 2
  done
  echo "impos ne repond pas apres 60s, regarde $DIR/impos.log"
  return 1
}

# Pushes one CSV feed and prints the answer, with how long it took.
#
# A feed that takes minutes is not a hang: a hundred thousand articles pushed
# as one text/plain body into an in-memory database is simply slow, and seeing
# which feed spends the time is what tells the two apart.
push_feed() {
  local start
  start=$SECONDS
  printf '%-36s ' "$1"
  curl -s -w ' [HTTP %{http_code}]' -u "admin:$ADMIN_PASSWORD" -H 'Content-Type: text/plain' \
    --data-binary "@$FEEDS/$1" "$BASE$2"
  printf ' %ss\n' "$((SECONDS - start))"
}

# Runs an import and prints how long the whole sequence took.
timed_import() {
  local label start
  label="$1"
  shift
  start=$SECONDS
  "$@"
  echo "$label : $((SECONDS - start)) s au total"
}

# The demonstration catalogue. employees.csv goes first on purpose: it carries
# the ADMIN account the pushes that follow authenticate with.
import_feeds() {
  push_feed employees.csv /feeds/import/EMPLOYEES
  push_feed egalim-regimes.csv /feeds/import/EGALIM_REGIMES
  push_feed stores.csv /feeds/import/STORES
  push_feed store-groups.csv /feeds/import/STORE_GROUPS
  push_feed products.csv /feeds/import/PRODUCTS
  push_feed product-families.csv /feeds/import/FAMILIES
  push_feed product-category-storages.csv /feeds/import/CATEGORY_STORAGES
  push_feed vat-rates.csv /feeds/import/VAT_RATES
  push_feed prices.csv /feeds/import/PRICES
  push_feed budget-groups.csv /feeds/import/BUDGET_GROUPS
  push_feed offers.csv /feeds/import/OFFERS
  push_feed hierarchies.csv "/feeds/import/NOMENCLATURE?scheme=ITM_FR&label=Nomenclature+ITM+France&enseigne=ITM"
}

# The real export of store 07039, on top of the demonstration catalogue: it
# replaces the articles, the families, the storages, the prices and the offers
# and leaves everything else -- employees, stores, VAT rates, nomenclature --
# as the sequence above left it, which is why it is never run alone.
import_catalogue_07039() {
  push_feed egalim-regimes.csv /feeds/import/EGALIM_REGIMES
  push_feed products-07039.csv /feeds/import/PRODUCTS
  push_feed product-families-07039.csv /feeds/import/FAMILIES
  push_feed product-category-storages-07039.csv /feeds/import/CATEGORY_STORAGES
  push_feed prices-07039.csv /feeds/import/PRICES
  push_feed budget-groups.csv /feeds/import/BUDGET_GROUPS
  push_feed offers-07039.csv /feeds/import/OFFERS
}

# Loads the catalogue the options asked for, refusing to pretend it did when
# the CSV directory is not there.
load_data() {
  if [ -z "$FEEDS" ]; then
    echo "Aucun repertoire de CSV : la caisse demarre avec un catalogue VIDE."
    return 1
  fi
  timed_import 'Chargement des CSV' import_feeds
  if [ "$WITH_07039" = '1' ]; then
    timed_import 'Catalogue 07039' import_catalogue_07039
  fi
}

# Opens the two Chrome windows, one per screen.
#
# The placement dance is not repeated here: it is kiosk.sh, which is the script
# caisse.sh itself uploads and runs, and duplicating it would make two versions
# of a window-manager workaround that was hard enough to get right once.
start_kiosk() {
  if [ ! -f "$KIOSK_SCRIPT" ]; then
    echo "kiosk.sh introuvable, fenetres non ouvertes."
    return 1
  fi
  chmod +x "$KIOSK_SCRIPT"
  bash "$KIOSK_SCRIPT"
}

# Stops everything, by port so no naming mistake survives, and closes the
# windows of the desktop user.
stop_all() {
  free_port "$POS_PORT"
  free_port "$HW_PORT"
  pkill -f google-chrome > /dev/null 2>&1
  ss -lptn "sport = :$POS_PORT or sport = :$HW_PORT"
  echo "arret demande ; plus aucune ligne d'ecoute ci-dessus = tout est bien arrete"
}

parse_options "$@" || exit 1
require_root || exit 1

if [ "$STOP_ONLY" = '1' ]; then
  stop_all
  exit 0
fi

locate_feeds
report_layout
if [ "$WITH_HW" = '1' ]; then
  start_hw
fi
start_impos || exit 1
if [ "$WITH_FEEDS" = '1' ]; then
  load_data
fi
if [ "$WITH_KIOSK" = '1' ]; then
  start_kiosk
fi
echo
echo "caisse sur http://localhost:$POS_PORT  -  journaux : $DIR/impos.log, $DIR/hw.log"
