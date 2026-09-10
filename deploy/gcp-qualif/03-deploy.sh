#!/usr/bin/env bash
# Recurring deployment of the 3 apps to the qualification VM.
# Run LOCALLY. Pattern mirrors Mobipay's redeploy-reset.sh:
# pre-flight build, pg_dump backup, differential rsync, restart, bounded health check.
# NOTE: kept bash-3.2 compatible (macOS default shell) — no associative arrays.
#
# Usage: ./03-deploy.sh [impos|imvaluation|imfid ...]   (no args = all three)
set -euo pipefail

ZONE="${ZONE:-europe-west1-b}"
VM_NAME="${VM_NAME:-qualif}"
# Local workspace roots. Defaults are anchored on this script's location,
# assuming the canonical layout impos/deploy/gcp-qualif/ with the two other
# repos as siblings of impos. Override via env vars for any other layout.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IMPOS_DIR="${IMPOS_DIR:-$SCRIPT_DIR/../..}"
IMVALUATION_DIR="${IMVALUATION_DIR:-$SCRIPT_DIR/../../../imvaluation}"
IMFID_DIR="${IMFID_DIR:-$SCRIPT_DIR/../../../imfid}"
# Hardware simulator static page: ONE file under the impos repo's docs/ (NOT
# under src, on purpose — a real till must never ship it). Only that file is
# shipped: docs/ also holds internal documents that have no business on a
# public host.
SIMULATOR_PAGE="${SIMULATOR_PAGE:-$IMPOS_DIR/docs/simulateur.html}"

APPS="${*:-impos imvaluation imfid}"

dir_for() {
  case "$1" in
    impos)       echo "$IMPOS_DIR" ;;
    imvaluation) echo "$IMVALUATION_DIR" ;;
    imfid)       echo "$IMFID_DIR" ;;
    *)           echo "Unknown app: $1 (expected impos|imvaluation|imfid)" >&2; exit 2 ;;
  esac
}

port_for() {
  case "$1" in
    impos) echo 8080 ;;
    imvaluation) echo 8090 ;;
    imfid) echo 8060 ;;
  esac
}

# impos and imvaluation do not ship the smallrye-health extension: probe a
# real page instead (302/303 to login counts as alive; curl -f fails only >=400).
probe_for() {
  case "$1" in
    impos)       echo "/lock" ;;
    imvaluation) echo "/" ;;
    *)           echo "/q/health" ;;
  esac
}

ssh_vm() { gcloud compute ssh "$VM_NAME" --zone="$ZONE" --command="$1"; }

# --- Pre-flight: build everything BEFORE touching the VM ----------------
for app in $APPS; do
  dir="$(dir_for "$app")"
  if [ ! -f "$dir/pom.xml" ]; then
    echo "No pom.xml found in resolved dir for $app: $dir"
    echo "Either move this kit to its canonical place (impos/deploy/gcp-qualif/)"
    echo "or override: IMPOS_DIR=... IMVALUATION_DIR=... IMFID_DIR=... ./03-deploy.sh"
    exit 1
  fi
  echo "== Building $app =="
  # -DskipJsTests: the Vitest bench is wired via exec-maven-plugin, which
  # -DskipTests does NOT cover — a deploy build must not depend on test state.
  ( cd "$dir" && if [ -x ./mvnw ]; then ./mvnw -q clean package -DskipTests -DskipITs -DskipJsTests; \
                 else mvn -q clean package -DskipTests -DskipITs -DskipJsTests; fi )
  test -f "$dir/target/quarkus-app/quarkus-run.jar" || { echo "Build output missing for $app"; exit 1; }
done

# --- Backup the PostgreSQL database (imfid only — impos and imvaluation are H2)
ssh_vm "sudo -u postgres pg_dump imfid | gzip > /tmp/imfid-\$(date +%Y%m%d-%H%M).sql.gz" || true

# --- Tarball + restart, one app at a time -------------------------------
# quarkus-app/ contains hundreds of files (lib/*.jar): shipping it as ONE
# compressed tarball is far faster over scp than a recursive per-file copy.
for app in $APPS; do
  dir="$(dir_for "$app")"
  echo "== Deploying $app =="
  tar -czf "/tmp/${app}-qa.tgz" -C "$dir/target" quarkus-app
  gcloud compute scp --zone="$ZONE" "/tmp/${app}-qa.tgz" "$VM_NAME:/tmp/${app}-qa.tgz"
  rm -f "/tmp/${app}-qa.tgz"
  ssh_vm "rm -rf /tmp/${app}-qa && mkdir -p /tmp/${app}-qa \
          && tar -xzf /tmp/${app}-qa.tgz -C /tmp/${app}-qa \
          && sudo rsync -a --delete /tmp/${app}-qa/quarkus-app/ /opt/apps/${app}/quarkus-app/ \
          && sudo chown -R apps:apps /opt/apps/${app} \
          && sudo systemctl restart ${app} \
          && rm -rf /tmp/${app}-qa /tmp/${app}-qa.tgz"
  # Simulator page rides along with impos (static, no restart needed). Caddy
  # serves /opt/apps/impos/simulator as a file server under /simulateur, so the
  # page is deployed as that directory's index.html. A missing source file is
  # announced, never skipped in silence.
  if [ "$app" = "impos" ]; then
    if [ -f "$SIMULATOR_PAGE" ]; then
      gcloud compute scp --zone="$ZONE" --compress "$SIMULATOR_PAGE" "$VM_NAME:/tmp/simulateur.html"
      ssh_vm "sudo mkdir -p /opt/apps/impos/simulator \
              && sudo cp /tmp/simulateur.html /opt/apps/impos/simulator/index.html \
              && sudo chown -R apps:apps /opt/apps/impos/simulator \
              && rm -f /tmp/simulateur.html"
      echo "   · simulateur déployé (/simulateur)."
    else
      echo "   ! simulateur INTROUVABLE ($SIMULATOR_PAGE) — page NON déployée." >&2
    fi
  fi
done

# --- Bounded health check (90 s per app, /q/health) ---------------------
for app in $APPS; do
  port="$(port_for "$app")"
  probe="$(probe_for "$app")"
  echo -n "Health $app "
  ok=0
  for i in $(seq 1 18); do
    if ssh_vm "curl -sf http://127.0.0.1:${port}${probe} >/dev/null" 2>/dev/null; then
      ok=1; break
    fi
    echo -n "."; sleep 5
  done
  if [ "$ok" = 1 ]; then
    echo "UP"
  else
    echo "DOWN after 90s"; ssh_vm "sudo journalctl -u ${app} -n 50 --no-pager"; exit 1
  fi
done

echo "All deployed and healthy."
