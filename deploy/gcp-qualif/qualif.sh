#!/usr/bin/env bash
# Daily driver for the qualification VM: start it when you need it, stop it when done.
# A stopped VM bills only disk + reserved IP; the nightly schedule (20:00) is the safety net.
#
# Usage: ./qualif.sh start | stop | status
set -euo pipefail

ZONE="${ZONE:-europe-west1-b}"
VM_NAME="${VM_NAME:-qualif}"
PORTS=(8080 8090 8060)

case "${1:-status}" in
  start)
    gcloud compute instances start "$VM_NAME" --zone="$ZONE"
    # Bounded readiness check: all three apps answer /q/health (systemd autostarts them)
    echo -n "Waiting for the 3 apps "
    for i in $(seq 1 24); do
      if gcloud compute ssh "$VM_NAME" --zone="$ZONE" --command \
        "curl -sf http://127.0.0.1:8080/lock >/dev/null && \
         curl -sf http://127.0.0.1:8090/ >/dev/null && \
         curl -sf http://127.0.0.1:8060/q/health >/dev/null" 2>/dev/null; then
        echo " UP — https://impos-qualif.it-o-cm.fr"; exit 0
      fi
      echo -n "."; sleep 5
    done
    echo " still DOWN after 120s — check: gcloud compute ssh $VM_NAME --zone=$ZONE"; exit 1
    ;;
  stop)
    gcloud compute instances stop "$VM_NAME" --zone="$ZONE"
    echo "Stopped. Billing now limited to disk + reserved IP."
    ;;
  status)
    gcloud compute instances describe "$VM_NAME" --zone="$ZONE" \
      --format='value(name,status,networkInterfaces[0].accessConfigs[0].natIP)'
    ;;
  *)
    echo "Usage: $0 start|stop|status"; exit 2
    ;;
esac
