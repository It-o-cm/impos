#!/usr/bin/env bash
# Test-till helper: build, upload, start and feed impos and its hardware daemon.
# Everything that is site-specific lives in the block below.

set -u

PASS='Stine*Dear132!?'
HOST='root@10.63.31.150'
REMOTE_DIR='/root/Henri'
# Back-office password of the ADMIN account, used for the HTTP Basic pushes
# below. It appears TWICE and the two must agree: it is exported as
# POS_ADMIN_PASSWORD on the start line, where EmployeeBootstrap uses it to
# create the first administrator on an empty database, and it is the
# BACK_OFFICE_PASSWORD of badge 00000000 in employees.csv, which overwrites
# that account on the first push. Let them diverge and every feed after the
# employees one answers 401.
ADMIN_PASSWORD='admin'
# Where this script lives. Everything below hangs off it, so the helper follows
# the repository instead of a fixed place in one person's home, and it can be
# called from any directory. It now sits in sh/, one level under the project
# root. Both directories stay overridable from the environment, like
# demo-stack.sh does, for a checkout laid out differently.
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
IMPOS_DIR="${IMPOS_DIR:-$(cd -- "$SCRIPT_DIR/.." && pwd)}"
HW_DIR="${HW_DIR:-$(cd -- "$IMPOS_DIR/.." && pwd)/impos-hw}"
# The jars are named ABSOLUTELY: they used to be relative and only resolved
# because the build function had just cd'd into the project — a coupling that
# broke the moment the script was invoked from anywhere else.
IMPOS_JAR="$IMPOS_DIR/target/impos-1.0-SNAPSHOT-runner.jar"
HW_JAR="$HW_DIR/target/impos-hw-0.1.0-SNAPSHOT.jar"
HW_PORT='8081'
# Payment terminal. The client directory is on the till; the acceptance point number and
# the island label are declared on the payment server and cannot be guessed -- leave
# either empty and the daemon serves without a terminal, its payment endpoints answering
# 503 like any unbound role.
WCL_DIR='/root/Henri/Client_Verifone_13.1.7_Linux_DEV/ClientKStrad'
TPE_STATION='1'
TPE_ILOT='A99054'
# A payment waits for the cardholder, and a login can update the reader's firmware. This
# is its own option on purpose: --timeout bounds the printer, the drawer and the scale,
# which must be seen failing fast.
TPE_TIMEOUT='300000'
# Which terminal impos talks to: bridge = the real one, through the hardware
# daemon; virtual = the simulator panel; auto = no terminal, immediate accept.
TPE_MODE='bridge'
# Endorsement printed on the back of a cheque. 180 is the usual orientation; set it to
# 0 if the endorsement comes out upside down. The endorsing printer is not declared
# here: the daemon reads its logical name from the JavaPOS device file.
ENDORSE_ROTATION='180'
# Serial port of the scale. The daemon defaults to /dev/ttyS4, where the
# Checkout-Dialog-06 of this till used to answer; after its firmware was
# raised the instrument answers on ttyS0, and a default is not the place for
# one till's history. A scale-test on each ttyS in turn finds it again the day
# it moves.
SCALE_PORT='/dev/ttyS0'
POS_PORT='8080'
SSHOPT='-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR'
# Same options WITHOUT the silencing: used by the file copies alone, so their
# meter and their diagnostics reach the screen.
SCPOPT='-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null'
# Two runtimes on the till, on purpose. impos stays on 21, the version Quarkus is
# supported on; the hardware daemon needs 25 because the payment client is reached
# through java.lang.foreign, final only from 22 on.
POS_JAVA='java'
HW_JAVA='/opt/java/jdk-25.0.4.1+1/bin/java'
KIOSK_SCRIPT="$IMPOS_DIR/kiosk.sh"

HOST_IP="${HOST#*@}"
BASE="http://$HOST_IP:$POS_PORT"
FEEDS="$IMPOS_DIR/demo/feeds"

# Runs a command on the till.
rsh() {
  sshpass -p "$PASS" ssh $SSHOPT "$HOST" "$1"
}

# Copies a local file to the till, WITH the transfer meter.
#
# The uber-jar weighs some seventy megabytes and the copy is the only step of
# a build that says nothing: between BUILD SUCCESS and the deployment message
# the script used to sit mute for as long as the link took, which reads as a
# hang and is indistinguishable from one. So the size goes out before, the
# meter of scp during, and the elapsed time after.
#
# SCPOPT and not SSHOPT: the shared options silence scp below the level where
# it reports anything, and here it is precisely what the transfer has to say
# that is wanted. A meter that does not move separates a slow link from a
# stalled negotiation -- which is what a scp speaking SFTP to a sshd without
# the subsystem looks like.
rcp() {
  local size start
  size=$(du -h "$1" 2>/dev/null | awk '{print $1}')
  printf 'copie de %s (%s) vers %s:%s\n' "$(basename "$1")" "${size:-?}" "$HOST_IP" "$2"
  start=$SECONDS
  sshpass -p "$PASS" scp $SCPOPT "$1" "$HOST:$2" || return 1
  printf 'copie terminee en %s s\n' "$((SECONDS - start))"
}

# Refuses to go on when sshpass is missing.
require_sshpass() {
  if ! command -v sshpass > /dev/null 2>&1; then
    echo "sshpass absent. Installe-le : brew install hudochenkov/sshpass/sshpass"
    return 1
  fi
}

# Builds the impos uber-jar and uploads it.
build_impos() {
  require_sshpass || return 1
  cd "$IMPOS_DIR" || return 1
  mvn clean package -DskipTests -Dquarkus.package.jar.type=uber-jar || return 1
  rcp "$IMPOS_JAR" "$REMOTE_DIR/impos.jar" || return 1
  echo "impos.jar deploye sur $HOST_IP"
}

# Builds the hardware daemon jar and uploads it.
build_hw() {
  require_sshpass || return 1
  cd "$HW_DIR" || return 1
  mvn clean package -DskipTests || return 1
  rcp "$HW_JAR" "$REMOTE_DIR/impos-hw.jar" || return 1
  echo "impos-hw.jar deploye sur $HOST_IP"
}

# Starts the hardware daemon, replacing any process holding its port.
# The launch is wrapped in a subshell with setsid so the process leaves the
# SSH session entirely: a plain background job keeps the channel open and ssh
# never returns.
start_hw() {
  require_sshpass || return 1
  local tpe
  tpe=''
  if [ -n "$WCL_DIR" ] && [ -n "$TPE_STATION" ] && [ -n "$TPE_ILOT" ]; then
    tpe="--wcl-dir $WCL_DIR --station $TPE_STATION --ilot $TPE_ILOT --tpe-timeout $TPE_TIMEOUT"
  fi
  rsh "fuser -k $HW_PORT/tcp > /dev/null 2>&1; sleep 2; cd $REMOTE_DIR && (setsid nohup $HW_JAVA -jar impos-hw.jar serve --port $HW_PORT --scale-port $SCALE_PORT --endorse-rotation $ENDORSE_ROTATION $tpe > hw.log 2>&1 < /dev/null &); sleep 3; exit 0"
  local code
  code=$(rsh "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:$HW_PORT/api/hardware/drawer/status")
  echo "pont materiel : HTTP $code"
  rsh "tail -8 $REMOTE_DIR/hw.log"
}

# Starts impos, replacing any process holding its port.
#
# pos.scanner.enabled=false: on THIS till the hardware bridge owns the
# scanner. HardwareClient states the rule -- "the hardware boundary is HTTP,
# this interface is the whole driver" -- and the bridge's scan bridge already
# reads the device and posts each label to /api/pos/scan. Leaving the
# register's own reader on meant two programs reading the same scanner and
# feeding the same register: every label arrived twice. The register's reader
# stays the fallback of a till that runs no bridge, which is why the property
# is set here and not defaulted off in the application.
start_impos() {
  require_sshpass || return 1
  rsh "fuser -k $POS_PORT/tcp > /dev/null 2>&1; sleep 2; cd $REMOTE_DIR && (POS_ADMIN_PASSWORD='$ADMIN_PASSWORD' setsid nohup $POS_JAVA -Dquarkus.rest-client.hardware-api.url=http://127.0.0.1:$HW_PORT -Dpos.tpe.mode=$TPE_MODE -Dpos.scanner.enabled=false -jar impos.jar > impos.log 2>&1 < /dev/null &); sleep 2; exit 0"
  wait_pos || return 1
  rsh "grep -E 'bootstrap administrator|Listening on|Port $POS_PORT' $REMOTE_DIR/impos.log"
}

# Polls the till until impos answers, up to a minute.
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
  echo "impos ne repond pas apres 60s, regarde $REMOTE_DIR/impos.log"
  return 1
}

# Pushes one CSV feed and prints the answer, with how long it took.
# A feed that takes minutes is not a hang: 109 000 articles pushed as one
# text/plain body into an in-memory database is simply slow, and seeing which
# feed spends the time is what tells the two apart.
push_feed() {
  local start
  start=$SECONDS
  printf '%-36s ' "$1"
  curl -s -w ' [HTTP %{http_code}]' -u "admin:$ADMIN_PASSWORD" -H 'Content-Type: text/plain' \
    --data-binary "@$FEEDS/$1" "$BASE$2"
  printf ' %ss\n' "$((SECONDS - start))"
}

# Runs an import and prints its total time under it.
timed_import() {
  local label start
  label="$1"
  shift
  start=$SECONDS
  "$@"
  echo "$label : $((SECONDS - start)) s au total"
}

# Pushes every referential feed, in catalogue order.
# ONE grammar, /feeds/import/{CODE}: the dedicated per-domain paths this used
# to call -- /products/import, /prices/import... -- never existed on the
# register, they live on the store node, and every push answered 404. The code
# is the referential domain, the same token everywhere in the suite.
# This till has NO store node, so nothing reaches it by the referential draw:
# everything it needs comes through this door, and everything means everything.
# EMPLOYEES first -- the seed that used to create mcurie and jdupont is
# @IfBuildProfile(dev,test) and does not exist in the uber-jar, so without this
# feed the only account on the till is the bootstrap administrator, whose
# password is not typable on a numpad: nobody can open a lane.
# VAT_RATES before PRICES: a price attaches to the regime carrying its rate.
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

# Loads the real store catalogue over whatever is already there.
# Articles, families, categories and prices: stores, groups, offers and
# employees are not touched, so the demo set stays usable for everything else.
import_catalogue_07039() {
  push_feed egalim-regimes.csv /feeds/import/EGALIM_REGIMES
  push_feed products-07039.csv /feeds/import/PRODUCTS
  push_feed product-families-07039.csv /feeds/import/FAMILIES
  push_feed product-category-storages-07039.csv /feeds/import/CATEGORY_STORAGES
  push_feed prices-07039.csv /feeds/import/PRICES
  push_feed budget-groups.csv /feeds/import/BUDGET_GROUPS
  push_feed offers-07039.csv /feeds/import/OFFERS
}

# One GET probe of the hardware bridge, run ON the till: the label, the
# answer and the HTTP code on one line.
#
# Newlines become SPACES rather than disappearing: the state reports of the
# terminal and of the cheque reader are several fields, one per line, and
# gluing them gave 'line1=-- ECHEC LIAISON  --line2=' -- two values welded
# into one word.
hw_get() {
  printf '%-26s ' "$1"
  rsh "curl -s -w ' [HTTP %{http_code}]' http://127.0.0.1:$HW_PORT/api/hardware$2" | tr '\n' ' '
  echo
}

# One POST probe of the hardware bridge, with its body. Bodies stay free of
# quotes: they travel through the remote shell inside single quotes.
hw_post() {
  printf '%-26s ' "$1"
  rsh "curl -s -w ' [HTTP %{http_code}]' -X POST -H 'Content-Type: text/plain' --data-binary '$3' http://127.0.0.1:$HW_PORT/api/hardware$2" | tr '\n' ' '
  echo
}

# Exercises EVERY peripheral of the till, one line each.
#
# The calls run ON the till and target 127.0.0.1, because the bridge port is
# not opened to the network and has no reason to be -- impos itself reaches
# it on the loopback. Run from a workstation they answer nothing at all,
# which looks exactly like dead hardware and is not.
#
# How to read the answers. No answer at all on every line = the bridge is
# down, entry 4 starts it. 503 on one line = that device is not bound on this
# till, the others are fine. The drawer says 204 on the pulse and OPEN
# afterwards, and the two halves are distinct: a pulse that leaves without
# the state contact following is a drawer impos can never know is open.
# The printer really prints and really cuts -- that is the only proof there
# is. The display answers 501 by construction: the BA9x is on the bus but its
# protocol is not known yet, so the bridge logs the text rather than pretend.
# The cheque reader and the payment terminal are only ASKED THEIR STATE:
# starting a reading or a payment would hold the device waiting for a human.
# The scanner is not on the bridge at all -- impos reads the devices itself,
# so its lines come from the register log.
#
# The first drawer call after a start can fall while the dn-portio helper is
# being restarted; run the entry twice before concluding.
test_hardware() {
  require_sshpass || return 1
  echo "tiroir-caisse"
  hw_get  "  etat avant" "/drawer/status"
  hw_post "  impulsion" "/drawer/open" ""
  sleep 1
  hw_get  "  etat apres" "/drawer/status"
  echo "imprimante ticket"
  hw_post "  impression" "/printer/print" "TEST MATERIEL CAISSE"
  hw_post "  coupe" "/printer/cut" ""
  echo "balance"
  hw_get  "  pesee" "/weight"
  echo "afficheur client"
  hw_post "  message" "/display" "TEST CAISSE"
  echo "lecteur de cheques"
  hw_get  "  etat" "/cheque"
  echo "terminal de paiement"
  hw_get  "  etat" "/payment"
  echo "douchette (lue par impos, pas par le pont)"
  rsh "grep -E 'Douchette .* detectee|interfaces sur|Aucune douchette|Lecture douchette active|Fin de lecture douchette|Envoi du code douchette|Ouverture douchette|Trame douchette' $REMOTE_DIR/impos.log | tail -10 | sed 's/^/  /'"
  echo "derniers codes recus par la caisse"
  rsh "grep -E 'handleScan with code' $REMOTE_DIR/impos.log | tail -5 | sed 's/^/  /'"
  echo "aucune ligne ci-dessus = aucun code n'est arrive sur /api/pos/scan"
  echo "douchette vue par le pont materiel (l'autre lecteur du meme noeud)"
  rsh "grep -E 'scanner not present|reading /dev/hidraw|scanner lost|undecodable report|scan ' $REMOTE_DIR/hw.log | tail -6 | sed 's/^/  /'"
}

# Raw diagnosis of the scanner, BELOW impos.
#
# Its two reader threads are alive and blocked on read(2), and the register
# log carries neither a decoded code nor a single "Trame douchette de format
# inconnu" -- so the question is no longer how the bytes are decoded but
# whether there are any at all. This captures for fifteen seconds whatever
# the device sends, in hexadecimal, with impos left running: a hidraw node
# gives every reader its own copy of each report, so the two do not compete.
#
# Bytes here and nothing in the register log = the decoder, and the hex dump
# is what is needed to write the missing format class. Nothing here either =
# the scanner transmits nothing, and that is its own configuration: a Symbol
# in IBM hand-held mode stays mute until the host enables it, and this
# register only ever READS its node -- HidrawScannerSource opens it O_RDONLY
# and never writes a byte.
diag_scanner() {
  require_sshpass || return 1
  echo "-- noeuds hidraw declares --"
  rsh "for n in /sys/class/hidraw/hidraw*; do echo \"== \$n\"; grep -E 'HID_NAME|HID_PHYS|HID_ID' \$n/device/uevent; done"
  echo "-- descripteurs de rapport de la douchette --"
  rsh "for n in \$(grep -l 'Bar Code Scanner' /sys/class/hidraw/hidraw*/device/uevent | sed 's#/device/uevent##'); do echo \"== \$(basename \$n)\"; od -An -tx1 \$n/device/report_descriptor; done"
  echo "-- qui tient les noeuds de la douchette --"
  rsh "for n in \$(grep -l 'Bar Code Scanner' /sys/class/hidraw/hidraw*/device/uevent | sed 's#/device/uevent##' | xargs -n1 basename); do echo \"== /dev/\$n\"; fuser -v /dev/\$n 2>&1; done"
  echo "-- processus et services susceptibles de la tenir --"
  rsh "ps -ef | grep -iE 'javapos|portio|dn-|wn-|opos|scanner' | grep -v grep"
  rsh "systemctl list-units --type=service --state=running --no-legend --no-pager | grep -iE 'dn|wn|pos|scan' 2>/dev/null"
  echo "-- capture 15 s : SCANNE UN ARTICLE MAINTENANT --"
  rsh "rm -f /tmp/hidcap.*; i=0; for n in \$(grep -l 'Bar Code Scanner' /sys/class/hidraw/hidraw*/device/uevent | sed 's#/device/uevent##' | xargs -n1 basename); do (timeout 15 cat /dev/\$n > /tmp/hidcap.\$n 2>/dev/null &); done; sleep 17; for f in /tmp/hidcap.*; do echo \"\$(basename \$f):\"; od -An -tx1 \$f | head -12; done; exit 0"
  echo "aucun octet sous aucun noeud = la douchette n'emet rien"
}

# Stops both processes on the till, by port so no naming mistake survives.
stop_all() {
  require_sshpass || return 1
  rsh "fuser -k $POS_PORT/tcp > /dev/null 2>&1; fuser -k $HW_PORT/tcp > /dev/null 2>&1; sleep 2; ss -lptn 'sport = :$POS_PORT or sport = :$HW_PORT'; exit 0"
  echo "arret demande ; plus aucune ligne d'ecoute ci-dessus = tout est bien arrete"
}

# Shows one log file, in tail or in follow mode.
show_log() {
  require_sshpass || return 1
  local file follow
  echo "  1) impos"
  echo "  2) gestionnaire materiel"
  printf 'Journal : '
  read -r file
  case "$file" in
    1) file='impos.log' ;;
    2) file='hw.log' ;;
    *) echo "Choix inconnu." ; return 1 ;;
  esac
  printf 'Suivre en direct ? (o/N) : '
  read -r follow
  case "$follow" in
    o|O)
      echo "Ctrl-C pour revenir au menu."
      # SIGINT is ignored here so Ctrl-C ends the remote tail, not this script.
      trap ' ' INT
      rsh "tail -f -n 40 $REMOTE_DIR/$file"
      trap - INT
      ;;
    *)
      rsh "tail -n 120 $REMOTE_DIR/$file"
      ;;
  esac
}

# Restarts the two kiosk windows ON THE TILL, one per screen.
#
# The whole placement dance lives in kiosk.sh, which runs on the till: the
# window manager there ignores Chrome's own geometry options, so the windows
# have to be opened, moved with wmctrl and only then made fullscreen. The
# script is re-sent at every call so editing it here is enough.
restart_kiosk() {
  require_sshpass || return 1
  rcp "$KIOSK_SCRIPT" "$REMOTE_DIR/kiosk.sh" || return 1
  rsh "chmod +x $REMOTE_DIR/kiosk.sh && bash $REMOTE_DIR/kiosk.sh"
}

while true; do
  echo
  echo "=== Caisse de test $HOST_IP ==="
  echo "  1) Reconstruire impos et le deployer"
  echo "  2) Reconstruire le gestionnaire materiel et le deployer"
  echo "  3) Lancer impos puis charger les CSV"
  echo "  4) Lancer le gestionnaire materiel"
  echo "  5) Tout arreter sur la caisse"
  echo "  6) Voir les journaux"
  echo "  7) Relancer Chrome sur la caisse"
  echo "  8) Charger le catalogue du magasin 07039 (109 204 articles)"
  echo "  9) Tester tout le materiel de la caisse"
  echo " 10) Diagnostiquer la douchette (capture brute)"
  echo "  q) Quitter"
  printf 'Choix : '
  read -r choice
  case "$choice" in
    1) build_impos ;;
    2) build_hw ;;
    3) start_impos && timed_import 'Chargement des CSV' import_feeds ;;
    4) start_hw ;;
    5) stop_all ;;
    6) show_log ;;
    7) restart_kiosk ;;
    8) timed_import 'Catalogue 07039' import_catalogue_07039 ;;
    9) test_hardware ;;
    10) diag_scanner ;;
    q|Q) exit 0 ;;
    *) echo "Choix inconnu." ;;
  esac
done
