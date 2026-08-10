#!/usr/bin/env bash
# Recurring deployment of the 3 apps to the qualification VM.
# Run LOCALLY. Pattern mirrors Mobipay's redeploy-reset.sh:
# pre-flight build, pg_dump backup, differential rsync, restart, bounded health check.
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

declare -A DIRS=( [impos]="$IMPOS_DIR" [imvaluation]="$IMVALUATION_DIR" [imfid]="$IMFID_DIR" )
declare -A PORTS=( [impos]=8080 [imvaluation]=8090 [imfid]=8060 )
APPS=( "${@:-impos imvaluation imfid}" )
# Hardware simulator static page: lives in a doc dir at the impos repo root
# (NOT under src, on purpose). Adjust the default to the actual dir name.
SIMULATOR_DIR="${SIMULATOR_DIR:-$IMPOS_DIR/doc/simulateur}"

ssh_vm() { gcloud compute ssh "$VM_NAME" --zone="$ZONE" --command="$1"; }

# --- Pre-flight: build everything BEFORE touching the VM ----------------
for app in ${APPS[@]}; do
  echo "== Building $app =="
  ( cd "${DIRS[$app]}" && ./mvnw -q clean package -DskipTests -DskipITs )
  test -f "${DIRS[$app]}/target/quarkus-app/quarkus-run.jar" || { echo "Build output missing for $app"; exit 1; }
done

# --- Backup the PostgreSQL database (imfid only — impos and imvaluation are H2)
ssh_vm "sudo -u postgres pg_dump imfid | gzip > /tmp/imfid-\$(date +%Y%m%d-%H%M).sql.gz" || true

# --- Differential rsync + restart, one app at a time --------------------
for app in ${APPS[@]}; do
  echo "== Deploying $app =="
  gcloud compute scp --recurse --zone="$ZONE" --compress \
    "${DIRS[$app]}/target/quarkus-app" "$VM_NAME:/tmp/${app}-quarkus-app"
  ssh_vm "sudo rsync -a --delete /tmp/${app}-quarkus-app/ /opt/apps/${app}/quarkus-app/ \
          && sudo chown -R apps:apps /opt/apps/${app} \
          && sudo systemctl restart ${app} && rm -rf /tmp/${app}-quarkus-app"
  # Simulator page rides along with impos (static, no restart needed)
  if [ "$app" = "impos" ] && [ -d "$SIMULATOR_DIR" ]; then
    gcloud compute scp --recurse --zone="$ZONE" --compress "$SIMULATOR_DIR" "$VM_NAME:/tmp/simulator"
    ssh_vm "sudo rsync -a --delete /tmp/simulator/ /opt/apps/impos/simulator/ \
            && sudo chown -R apps:apps /opt/apps/impos/simulator && rm -rf /tmp/simulator"
  fi
done

# --- Bounded health check (90 s per app, /q/health) ---------------------
for app in ${APPS[@]}; do
  echo -n "Health $app "
  for i in $(seq 1 18); do
    if ssh_vm "curl -sf http://127.0.0.1:${PORTS[$app]}/q/health >/dev/null" 2>/dev/null; then
      echo "UP"; continue 2
    fi
    echo -n "."; sleep 5
  done
  echo "DOWN after 90s"; ssh_vm "sudo journalctl -u ${app} -n 50 --no-pager"; exit 1
done

echo "All deployed and healthy."
