#!/usr/bin/env bash
# Daily driver for the qualification VM: start it when you need it, stop it when done.
# A stopped VM bills only disk + reserved IP; the nightly schedule (20:00) is the safety net.
#
# Usage: ./qualif.sh start | stop | status
set -euo pipefail

ZONE="${ZONE:-europe-west1-b}"
VM_NAME="${VM_NAME:-qualif}"
# Named explicitly, never inherited from the active gcloud configuration: that
# configuration follows whatever project was worked on last, and a command
# aimed at the wrong one does not fail politely — it offers to enable the
# Compute API there, which is a change to a project that has nothing to do
# with this environment. 01-create-vm.sh is the only script that may move the
# active project, because setting it up is its job.
PROJECT="${PROJECT:-impos-qualif}"
PORTS=(8080 8070 8090 8060)

case "${1:-status}" in
  start)
    gcloud compute instances start "$VM_NAME" --project="$PROJECT" --zone="$ZONE"
    # Bounded readiness check: every app answers its own probe (systemd autostarts them)
    echo -n "Waiting for the 4 apps "
    for i in $(seq 1 24); do
      if gcloud compute ssh "$VM_NAME" --project="$PROJECT" --zone="$ZONE" --command \
        "curl -sf http://127.0.0.1:8080/lock >/dev/null && \
         curl -sf http://127.0.0.1:8070/q/health >/dev/null && \
         curl -sf http://127.0.0.1:8090/ >/dev/null && \
         curl -sf http://127.0.0.1:8060/q/health >/dev/null" 2>/dev/null; then
        echo " UP — https://impos-qualif.it-o-cm.fr"; exit 0
      fi
      echo -n "."; sleep 5
    done
    echo " still DOWN after 120s — check: gcloud compute ssh $VM_NAME --project=$PROJECT --zone=$ZONE"; exit 1
    ;;
  stop)
    gcloud compute instances stop "$VM_NAME" --project="$PROJECT" --zone="$ZONE"
    echo "Stopped. Billing now limited to disk + reserved IP."
    ;;
  status)
    gcloud compute instances describe "$VM_NAME" --project="$PROJECT" --zone="$ZONE" \
      --format='value(name,status,networkInterfaces[0].accessConfigs[0].natIP)'
    ;;
  *)
    echo "Usage: $0 start|stop|status"; exit 2
    ;;
esac
