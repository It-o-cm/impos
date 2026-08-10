#!/usr/bin/env bash
# One-time creation of the qualification VM on GCP.
# Run LOCALLY (requires gcloud authenticated on the target project).
# Pattern mirrors the Mobipay qualification setup (europe-west1-b, Caddy, systemd).
set -euo pipefail

PROJECT="${PROJECT:-impos-qualif}"          # GCP project id (create it first in the console if needed)
ZONE="${ZONE:-europe-west1-b}"
VM_NAME="${VM_NAME:-qualif}"          # stable name: every script defaults to it, no env var needed
MACHINE_TYPE="${MACHINE_TYPE:-e2-standard-2}"  # 3 JVMs + PostgreSQL: 8 GB is comfortable
DISK_SIZE="${DISK_SIZE:-30GB}"

gcloud config set project "$PROJECT"

# Static external IP (needed for the 3 DNS A records)
gcloud compute addresses create "${VM_NAME}-ip" --region="${ZONE%-*}" || true
STATIC_IP=$(gcloud compute addresses describe "${VM_NAME}-ip" --region="${ZONE%-*}" --format='value(address)')

# Firewall: HTTP/HTTPS only (Caddy). App ports 8080/8090/8060 stay internal.
gcloud compute firewall-rules create allow-http-https \
  --allow=tcp:80,tcp:443 --target-tags=web --direction=INGRESS || true

gcloud compute instances create "$VM_NAME" \
  --zone="$ZONE" \
  --machine-type="$MACHINE_TYPE" \
  --image-family=debian-12 --image-project=debian-cloud \
  --boot-disk-size="$DISK_SIZE" \
  --tags=web \
  --address="$STATIC_IP"

# --- Cost lever: automatic nightly stop -------------------------------
# Stopped instance costs only disk + reserved IP (~$7/month instead of ~$60).
# Stop is automatic every evening; start is always MANUAL (./qualif.sh start),
# so a forgotten VM never bills a full night or weekend.
REGION="${ZONE%-*}"
gcloud compute resource-policies create instance-schedule stop-nightly \
  --region="$REGION" \
  --vm-stop-schedule="0 20 * * *" \
  --timezone="Europe/Paris" || true

# The Compute Engine system service agent executes the schedule and needs
# instanceAdmin on the project (classic gotcha: without it the schedule silently does nothing).
PROJECT_NUMBER=$(gcloud projects describe "$PROJECT" --format='value(projectNumber)')
gcloud projects add-iam-policy-binding "$PROJECT" \
  --member="serviceAccount:service-${PROJECT_NUMBER}@compute-system.iam.gserviceaccount.com" \
  --role="roles/compute.instanceAdmin.v1" --condition=None >/dev/null

gcloud compute instances add-resource-policies "$VM_NAME" \
  --resource-policies=stop-nightly --zone="$ZONE"

echo "VM created. Static IP: $STATIC_IP"
echo "Auto-stop scheduled every day at 20:00 Europe/Paris (start stays manual: ./qualif.sh start)"
echo "Now create 3 DNS A records pointing to $STATIC_IP:"
echo "  impos-qualif.it-o-cm.fr"
echo "  imvaluation-qualif.it-o-cm.fr"
echo "  imfid-qualif.it-o-cm.fr"
echo "Then: gcloud compute scp 02-provision.sh Caddyfile *.service ${VM_NAME}:~ --zone=$ZONE"
echo "      gcloud compute ssh ${VM_NAME} --zone=$ZONE -- 'sudo bash 02-provision.sh'"
