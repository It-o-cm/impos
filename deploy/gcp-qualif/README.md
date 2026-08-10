# Qualification environment — impos / imvaluation / imfid on Google Cloud

**Who this is for.** Anyone who has to install, run, or fix this environment,
including people who have never used Google Cloud before. No prior cloud or
Linux administration experience is assumed: every concept is explained the
first time it appears, and every command shows what you should see when it
works. Experienced readers can jump straight to §6 (everyday use) and §12
(command reference).

**How to read it.** §1–§2 explain what you are building and the vocabulary.
§3–§5 are the one-time installation, in order. §6 is the page you will use
every day. §7–§11 are for when you need to manage data, fix a problem, or
understand a bill. Nothing here requires touching the applications' source
code.

---

## 1. What this environment is, in plain words

We have three Java applications that together form a supermarket checkout
ecosystem:

- **impos** — the cash register itself (the screen the cashier uses);
- **imvaluation** — the pricing engine: it computes promotions and the exact
  price of the basket. On a real cash register it runs on the same physical
  machine as impos, which is why the two always live side by side here too;
- **imfid** — the loyalty program: cards, points ("cagnotte"), earn/burn
  rules. Unlike the other two, it is a central service shared by all registers.

"Qualification" means: a copy of this system running on a server on the
internet, so it can be tested and demonstrated in realistic conditions,
without being the real production. It must be easy to reset, cheap, and
allowed to break.

Instead of buying a physical server, we **rent a virtual machine** (a "VM" — a
computer that exists inside Google's data centers) from **Google Cloud
Platform** ("GCP"). The three applications run on that single VM, and three
web addresses make them reachable from any browser:

- https://impos-qualif.it-o-cm.fr — the cash register screen
- https://imvaluation-qualif.it-o-cm.fr — the pricing engine's admin screens
- https://imfid-qualif.it-o-cm.fr — the loyalty admin screens
- https://impos-qualif.it-o-cm.fr/simulateur/ — the **hardware simulator**, a
  web page that plays the role of the physical devices (barcode scanner,
  scale, payment terminal, cash drawer) since the VM obviously has none.

The VM is **switched off automatically every evening at 20:00** to save
money, and you switch it on manually when you need it (one command, §6).

## 2. Small glossary

You do not need to memorize this — come back to it when a word is unclear.

| Term | What it means here |
|---|---|
| **GCP** | Google Cloud Platform: Google's service for renting computing resources. You pay only for what you use. |
| **Project** | A folder inside GCP that groups resources and their bill. Ours is called `impos-qualif`. |
| **VM (virtual machine)** | A rented computer running in a Google data center. Ours is named `qualif`, runs Debian Linux, and lives in the `europe-west1-b` zone (Belgium). |
| **`gcloud`** | The command-line tool installed on YOUR computer to control GCP: create the VM, start it, stop it, open a terminal on it. |
| **SSH** | The standard way to open a secure terminal on a remote Linux machine. You never type a password: `gcloud` handles the keys. |
| **Static IP** | The fixed "phone number" of the VM on the internet. We reserve one so it never changes, even when the VM is stopped. |
| **DNS / A record** | The internet's phone book. An "A record" says "the name `impos-qualif.it-o-cm.fr` means this IP". The `it-o-cm.fr` zone is hosted on **Google Cloud DNS**, inside the Mobipay GCP project — records are added with `gcloud dns` commands (Step 2). |
| **HTTPS / certificate** | The padlock in the browser. Certificates are obtained and renewed automatically by Caddy — you never manage them. |
| **Caddy** | A small web server installed on the VM. It is the only thing exposed to the internet; it receives the HTTPS traffic and forwards it to the right application. This role is called a *reverse proxy*. |
| **systemd / service** | The Linux mechanism that starts programs at boot and restarts them if they crash. Each application is a "service": `impos`, `imvaluation`, `imfid`, `caddy`, `postgresql`. |
| **PostgreSQL** | A database server. Here it stores only imfid's data (the loyalty accounts). |
| **H2** | A tiny database that lives in a simple file next to the application. impos and imvaluation use it, exactly like on a real cash register. |
| **Snapshot** | A photo of the VM's entire disk, taken automatically every night. It is the "restore point" if everything goes wrong. |
| **Deploy** | Sending a new version of an application to the VM and restarting it. Done by one script from your computer. |

## 3. The moving parts

```
                    Internet (HTTPS, padlock handled by Caddy)
                              |
                        [ Caddy :443 ]  <- the ONLY door open to the internet
        ______________________|______________________
       |                      |                      |
impos-qualif.…        imvaluation-qualif.…    imfid-qualif.…
  (+ /simulateur)             |                      |
       |                      |                      |
 [ impos :8080 ]      [ imvaluation :8090 ]   [ imfid :8060 ]
   H2 file DB             H2 file DB                 |
 /opt/apps/impos/data  /opt/apps/imvaluation/  [ PostgreSQL 16 ]
                            data                 db: imfid
       |______________________|______________________|
              the apps talk to each other INSIDE the VM
              (impos -> imvaluation for prices, impos -> imfid for loyalty)
```

Why it is built this way — three decisions worth knowing, because they answer
most "why not…?" questions:

1. **One single VM.** On a real till, the pricing engine runs on the same
   machine as the register (instant local calls). One VM reproduces exactly
   that. It is also the cheapest and simplest option.
2. **impos and imvaluation keep their data in H2 files** (no database server),
   because that is how a real cash register works: it must be able to sell
   even if every network cable is cut. imfid is the only service that is
   central by nature (a loyalty account is shared between stores), so it is
   the only one on PostgreSQL.
3. **The application ports (8080/8090/8060) are never open to the internet.**
   Everything goes through Caddy on the standard HTTPS port. The GCP firewall
   enforces this — even a misconfigured application cannot be reached
   directly.

## 4. What you need before starting

### 4.1 Accounts and access

- **A Google Cloud account with billing enabled.** Go to
  https://console.cloud.google.com, sign in with a Google account, and follow
  "Activate billing" (a credit card is required; new accounts get 300 $ of
  free credit, enough for ~5 months of this environment). The GCP *project*
  itself is created later, by command line, in Step 0 of §5.
- **Access to the GCP project that hosts the `it-o-cm.fr` DNS zone.** The
  zone is served by Google Cloud DNS (name servers `ns-cloud-d*.googledomains.com`
  — verifiable with `whois it-o-cm.fr`) and lives in the Mobipay project
  (`mobipay-calife`). The same Google account used for Mobipay therefore
  already has what is needed. The domain itself was bought through the
  registrar Key-Systems, but nothing is ever done there for this environment.
- **A GitHub account with access to the `It-o-cm` organisation**, with an SSH
  key registered (GitHub → Settings → SSH keys). This is only needed to clone
  the three source repositories.

### 4.2 Tools on your computer (macOS)

Open the Terminal application and install:

```bash
# The Google Cloud command-line tool
brew install google-cloud-sdk

# Java 21 (needed to BUILD the applications; the VM never compiles anything)
brew install --cask temurin@21
```

Check both:

```bash
gcloud --version     # prints "Google Cloud SDK …"
java -version        # must print "21.x". If it prints another version:
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
```

Then authenticate `gcloud` and set the defaults once and for all (this is what
lets every later command be short):

```bash
gcloud auth login                          # opens the browser, sign in
gcloud config set project impos-qualif
gcloud config set compute/zone europe-west1-b
```

No Maven installation is needed: each repository carries its own `./mvnw`
wrapper which downloads the right Maven automatically on first use.

### 4.3 The source code

The deploy script builds the applications from local copies of the three
repositories, laid out like this (the deployment kit itself lives inside
impos):

```
Workspaces/
├── impos/          <- contains this kit in deploy/gcp-qualif/
├── imvaluation/
└── imfid/
```

To create that layout:

```bash
cd ~/Documents/Workspaces
git clone git@github.com:It-o-cm/impos.git
git clone git@github.com:It-o-cm/imvaluation.git
git clone git@github.com:It-o-cm/imfid.git
```

Sanity check that the whole build chain works (takes a minute the first time):

```bash
cd imfid && ./mvnw -q clean package -DskipTests -DskipITs \
  && ls target/quarkus-app/quarkus-run.jar     # the file must exist
```

Good habit before any deploy: run `git status` in each repo — what you deploy
is the working copy as it is, so make sure it corresponds to a known commit.

## 5. First-time installation

Five steps, numbered 0 to 4, in this order, all run from
`impos/deploy/gcp-qualif/` in your terminal. Total time: about 30 minutes,
most of it waiting.

### Step 0 — Create the GCP project (and make the scripts executable)

A *project* is the folder inside GCP that will hold the VM and its bill. It
does not exist yet — `gcloud config set project` alone never creates one, it
only points at it (and warns "does not have permission … or it may not
exist" if you point at nothing). Create it, attach billing, and switch on the
Compute Engine API (each GCP API must be enabled once per project — without
it, even `gcloud compute zones list` complains):

```bash
gcloud projects create impos-qualif
# If it answers "already exists": project IDs are unique WORLDWIDE — someone
# else owns that one. Pick a variant (e.g. impos-qualif-ito) and use it
# everywhere below and as PROJECT=... when running the scripts.

gcloud billing accounts list          # note the ID, format XXXXXX-XXXXXX-XXXXXX
gcloud billing projects link impos-qualif --billing-account=XXXXXX-XXXXXX-XXXXXX

gcloud services enable compute.googleapis.com --project=impos-qualif   # ~30 s
gcloud auth application-default set-quota-project impos-qualif  # silences a recurring warning
```

Verify before moving on — this must answer without any permission error:

```bash
gcloud projects describe impos-qualif
```

Last local detail: if the files arrived via a zip download, the execute
permission was lost in transit. Restore it once:

```bash
chmod +x *.sh
```

(`zsh: permission denied: ./01-create-vm.sh` is the symptom of forgetting
this.)

### Step 1 — Create the VM

```bash
./01-create-vm.sh
```

What this does, in order: reserves the static IP; opens ports 80/443 in the
firewall (and nothing else); creates the VM `qualif` (Debian 12, 2 CPUs, 8 GB
RAM, 30 GB disk); installs the automatic evening shutdown (20:00 Paris time,
including the permission grant without which the schedule silently does
nothing).

At the end it prints something like:

```
VM created. Static IP: 34.76.xxx.xxx
Auto-stop scheduled every day at 20:00 Europe/Paris (start stays manual: ./qualif.sh start)
```

**Write down that IP** — you need it for step 2.

### Step 2 — Declare the three web addresses (DNS)

The `it-o-cm.fr` zone is hosted on Google Cloud DNS, inside the **Mobipay**
project — not in the new qualification project, and not at the domain's
registrar. So this step is three `gcloud` commands, run from your computer
(note the explicit `--project`: the active project is `impos-qualif`, but the
zone lives elsewhere):

```bash
# 1. Find the zone's internal name (once)
gcloud dns managed-zones list --project=mobipay-calife
```

The output lists the zone for `it-o-cm.fr.` — note its NAME (first column).
Then create the three records (mind the **trailing dot**: DNS names here are
absolute):

```bash
ZONE=<the-name-found-above>
for h in impos-qualif imvaluation-qualif imfid-qualif; do
  gcloud dns record-sets create ${h}.it-o-cm.fr. --project=mobipay-calife \
    --zone="$ZONE" --type=A --ttl=300 --rrdatas=<STATIC_IP-from-step-1>
done
```

Verify from your terminal (Cloud DNS propagates almost immediately):

```bash
dig +short impos-qualif.it-o-cm.fr      # must print the static IP
```

Alternative for the console-minded: GCP console → project `mobipay-calife` →
"Network services" → "Cloud DNS" → the `it-o-cm.fr` zone → "Add record set",
three times (type A, TTL 300, the static IP).

This step matters because the HTTPS certificates are requested automatically
by Caddy the first time someone visits — and that only works once the names
resolve. If `managed-zones list` finds nothing in `mobipay-calife`, list your
projects (`gcloud projects list`) and query each: the zone necessarily exists
somewhere, since `calife-mobipay.it-o-cm.fr` already resolves from it.

### Step 3 — Install the software on the VM

Send the installation files to the VM, open a terminal on it, and run the
provisioning script:

```bash
gcloud compute scp 02-provision.sh Caddyfile impos.service imvaluation.service imfid.service qualif:~
gcloud compute ssh qualif
```

You are now typing **on the VM** (the prompt changes). Choose a real database
password and run:

```bash
sudo DB_PASSWORD='choose-a-password-here' bash 02-provision.sh
```

This installs Java, PostgreSQL and Caddy, creates the `imfid` database, puts
the three services in place and enables everything. It ends with:

```
Provisioning done. Deploy the jars with 03-deploy.sh, then services will start.
```

Type `exit` to come back to your own computer. (At this point the three app
services exist but have nothing to run yet — that is normal.)

### Step 4 — First deploy

```bash
./03-deploy.sh
```

This builds the three applications on your computer, copies them to the VM,
starts them, and waits until each answers its health check. Success looks
like:

```
== Building impos ==
== Building imvaluation ==
== Building imfid ==
== Deploying impos ==
...
Health impos UP
Health imvaluation UP
Health imfid UP
All deployed and healthy.
```

Open https://impos-qualif.it-o-cm.fr in a browser: you should see the cash
register lock screen, with a valid padlock. The environment is live.

## 6. Everyday use — the only three commands

The VM stops alone every evening at 20:00. Your daily routine:

```bash
./qualif.sh start     # morning: boots the VM, waits until the 3 apps answer,
                      # then prints the URL. Takes 1–2 minutes.
./qualif.sh stop      # evening (optional): stop now instead of waiting for 20:00
./qualif.sh status    # doubt: prints RUNNING or TERMINATED and the IP
```

And whenever a new version of an application must go up:

```bash
./03-deploy.sh                # all three applications
./03-deploy.sh imfid          # just one
./03-deploy.sh impos imfid    # any subset
```

The deploy script is safe by construction: it builds BEFORE touching the VM
(a broken build stops everything while the VM still runs the old version),
backs up the imfid database first, and refuses to finish while an application
does not answer its health check — in which case it prints the application's
last log lines so you can see why.

Two behaviours that are normal, not bugs:

- Right after a start, the register may show a red banner "VALORISATION
  INDISPONIBLE": impos simply booted faster than the pricing engine. It heals
  by itself within seconds (the register re-probes every 10 s).
- After a cash payment the register waits for the drawer to be closed — on the
  simulator page, like on a real till.

## 7. Where the data lives, and how to manage it

Three data stores, three habits:

| Store | Contains | Where |
|---|---|---|
| H2 file | impos: tickets, sessions, the fiscal chain | `/opt/apps/impos/data/` on the VM |
| H2 file | imvaluation: products, prices, offers (rebuildable by re-import) | `/opt/apps/imvaluation/data/` |
| PostgreSQL | imfid: loyalty accounts and movements | database `imfid` |

All commands below are typed **on the VM** (`gcloud compute ssh qualif`
first).

**Reset an application to a fresh state** (typical before a test campaign):

```bash
# impos (the register reseeds its demo data at boot)
sudo systemctl stop impos && sudo rm -f /opt/apps/impos/data/pos-c04* && sudo systemctl start impos

# imvaluation (re-run the CSV imports afterwards if you use a specific referential)
sudo systemctl stop imvaluation && sudo rm -f /opt/apps/imvaluation/data/engine* && sudo systemctl start imvaluation

# imfid
sudo systemctl stop imfid
sudo -u postgres psql -c "DROP DATABASE imfid;" -c "CREATE DATABASE imfid OWNER imfid;"
sudo systemctl start imfid
```

**Manual backup before something risky:**

```bash
# imfid -> a compressed dump file
sudo -u postgres pg_dump imfid | gzip > /tmp/imfid-$(date +%F).sql.gz

# an H2 app -> stop, copy the file, restart (never copy while the app runs)
sudo systemctl stop impos && sudo cp /opt/apps/impos/data/pos-c04.mv.db /tmp/ && sudo systemctl start impos
```

**Restore an imfid dump:**

```bash
zcat /tmp/imfid-2026-08-10.sql.gz | sudo -u postgres psql imfid
```

**The safety net you never think about:** a snapshot (photo of the whole
disk) is taken automatically every night at 03:00 and kept 7 days. It covers
everything at once — both H2 files, PostgreSQL, and the configuration. To set
it up (once) or check it exists:

```bash
gcloud compute resource-policies create snapshot-schedule daily-snap \
  --region=europe-west1 --max-retention-days=7 --daily-schedule --start-time=03:00
gcloud compute disks add-resource-policies qualif --resource-policies=daily-snap
gcloud compute snapshots list        # the inventory of restore points
```

Restoring from a snapshot means creating a new disk from it and booting a VM
on that disk — for a qualification environment this is a "rebuild from a
known good day", not a high-availability promise. Also worth knowing: the
deploy script checks nothing about profiles, so make sure each application's
production profile uses `quarkus.hibernate-orm.database.generation=update` —
a `drop-and-create` left over from development would wipe the data at every
restart.

**Bootstrap accounts (first logins).** In production mode the applications
refuse to start without their bootstrap credentials, provided as environment
variables in the `.service` files (placeholders substituted by
`02-provision.sh`). To read the values currently in force on the VM:

```bash
gcloud compute ssh qualif --command="grep -h BOOTSTRAP /etc/systemd/system/*.service"
```

| Application | Login | Password source | Note |
|---|---|---|---|
| imvaluation back-office | `admin` | `VALUATION_BOOTSTRAP_ADMIN_PASSWORD` | password change forced at first login |
| imfid admin | the `IMFID_BOOTSTRAP_ADMIN_EMAIL` address | `IMFID_BOOTSTRAP_ADMIN_PASSWORD` | |
| imfid `pos` machine account | `pos` | `IMFID_BOOTSTRAP_POS_PASSWORD` | MUST equal `pos.fid.password` in `impos.service` (`pos-password`), or every loyalty call from the register fails 401. Not a human account. |

These variables only matter at bootstrap (first start on an empty database)
and after any database reset — day-to-day logins use whatever passwords were
set in the applications afterwards.

## 8. Reading the logs (the reflex for every problem)

Each service writes its log into the system journal. On the VM:

```bash
sudo journalctl -u impos -f              # live view of one app (Ctrl-C to quit)
sudo journalctl -u imvaluation -n 200    # the last 200 lines
sudo journalctl -u imfid --since today
sudo journalctl -u caddy -n 100          # the web/HTTPS side
systemctl status impos imvaluation imfid # one-glance state of the three
```

From your own computer, without opening a session first:

```bash
gcloud compute ssh qualif --command="sudo journalctl -u impos -n 100 --no-pager"
```

A healthy application answers `{"status":"UP", ...}` on its health endpoint:

```bash
curl -s http://127.0.0.1:8060/q/health   # from the VM; 8080 impos, 8090 imvaluation
```

## 9. When something goes wrong

| What you see | What it usually means | What to do |
|---|---|---|
| `./qualif.sh start` says "still DOWN after 120s" | One app failed to boot | `gcloud compute ssh qualif` then `systemctl status` + `journalctl -u <the red one> -n 100` |
| Deploy ends with `Health <app> DOWN` | The new version crashes at startup | The script already printed the last 50 log lines: read them; fix; redeploy. The VM still runs, only that app is down. |
| Browser says the site cannot be reached | VM stopped (it is 20:01!) or DNS typo | `./qualif.sh status`; if TERMINATED → `./qualif.sh start`. Otherwise `dig +short <name>` must print the VM's IP. |
| Certificate warning in the browser | First visit before DNS had propagated | Wait for DNS (step 2 check), then on the VM: `sudo systemctl reload caddy` |
| Register shows red "VALORISATION INDISPONIBLE" | Pricing engine down or still booting | Normal for a few seconds after start. If it stays: `journalctl -u imvaluation` |
| Forms fail with security/CSRF errors | Someone removed the proxy variable from a unit | Each `.service` must keep `QUARKUS_HTTP_PROXY_PROXY_ADDRESS_FORWARDING=true` |
| impos refuses to boot, log mentions a lock file | Leftover `.lock.db` after a brutal stop | With the service stopped: `sudo rm /opt/apps/impos/data/*.lock.db`, then start |
| imfid data vanished after a restart | `drop-and-create` left in the prod profile | Fix the profile (§7 last paragraph), restore the latest dump or snapshot |
| VM never stops at 20:00 | The schedule's permission grant is missing | Re-run the IAM block of `01-create-vm.sh` (it is safe to re-run) |
| A `gcloud` command says the VM does not exist | Wrong project/zone configured | `gcloud config set project impos-qualif` and `gcloud config set compute/zone europe-west1-b` |
| "does not have permission to access projects instance … or it may not exist" | The project was never created (Step 0 skipped) | Do Step 0, then `gcloud projects describe impos-qualif` must succeed |
| `zsh: permission denied: ./01-create-vm.sh` | Execute bit lost in the zip download | `chmod +x *.sh` once (end of Step 0) |
| "europe-west1-b is not a valid zone" warning | Compute API not yet enabled on the project — gcloud could not check the zone, which is valid | `gcloud services enable compute.googleapis.com`, answer Y meanwhile |
| Browser shows **502 Bad Gateway** on one site | Caddy is up but that app is down (often a crash-loop) | `systemctl is-active <app>` then read its journal — see next row |
| Journal shows "Failed to load config value … for: X" or "Could not expand value Y" in a restart loop | A mandatory %prod config key has no value | Add `Environment=<ENV_NAME>=value` to the app's `.service` (env name = key uppercased, dots/dashes → `_`), `daemon-reload`, restart. The kit units already carry all known ones. |
| An admin screen answers 500 with an error id | Application exception | `journalctl -u <app> --no-pager \| grep -A 40 '<error id>'` and read the `Caused by` |
| imfid admin 500 "Unable to access lob stream" | Jar predates the LOB→TEXT fix, or DB still has `oid` columns | Deploy a fixed build, then reset the imfid database (§7) — Hibernate `update` cannot convert `oid` columns |

Golden rule: **the VM is disposable, the repos and the snapshots are not.**
If an environment is beyond repair, recreating it from scratch (§5) takes
30 minutes and loses nothing that matters.

## 10. Security, in short

- Only ports 80/443 are open. The applications, PostgreSQL and SSH are not
  directly reachable; SSH goes through Google's identity layer
  (`gcloud compute ssh`), so access = having a Google account with the right
  role on the project. To grant a colleague full VM access:

```bash
gcloud projects add-iam-policy-binding impos-qualif \
  --member=user:their-email@gmail.com --role=roles/compute.instanceAdmin.v1
```

- The passwords used here (database, `pos`/`pos-password` between the apps)
  are qualification-grade: acceptable because the environment is disposable
  and private, to be replaced the day an environment matters. They live in the
  `.service` files on the VM (edit, then `sudo systemctl daemon-reload` and
  restart the service).
- The admin screens and the simulator are on the public internet, protected
  only by their login forms. If the audience is just the team, the Caddyfile
  can restrict a site to given IPs — ask for the one-liner or see the
  commented example in the file's history.

## 11. What it costs

| Situation | Per month |
|---|---|
| VM running 24/7 | ~60–65 $ |
| VM stopped (disk + reserved IP + snapshots) | ~7–8 $ |
| Typical office-hours use with the 20:00 auto-stop | ~20–25 $ |

New Google Cloud accounts include 300 $ of trial credit — roughly five months
of full-time running, more than a year of normal qualification use. The bill
is visible in the GCP console under "Billing".

## 12. Command reference (cheat-sheet)

```bash
# Daily
./qualif.sh start | stop | status
./03-deploy.sh [impos] [imvaluation] [imfid]

# Terminal on the VM / run one command / copy files
gcloud compute ssh qualif
gcloud compute ssh qualif --command="systemctl status impos"
gcloud compute scp somefile qualif:~            # local -> VM
gcloud compute scp qualif:/tmp/dump.sql.gz .    # VM -> local

# Reach the app ports directly from your computer (tunnel, bypasses Caddy)
gcloud compute ssh qualif -- -L 8080:localhost:8080 -L 8090:localhost:8090 -L 8060:localhost:8060
# then locally: curl http://localhost:8090/q/health

# Inventory
gcloud compute instances list
gcloud compute addresses list
gcloud compute snapshots list
```

## Appendix — what each file in this directory is

| File | Role | You run it… |
|---|---|---|
| `01-create-vm.sh` | Creates the VM, IP, firewall, evening auto-stop | once, from your computer |
| `02-provision.sh` | Installs everything on the VM (Java, PostgreSQL, Caddy, services) | once, on the VM |
| `03-deploy.sh` | Builds and ships the applications (+ the simulator page) | at every new version |
| `qualif.sh` | Start / stop / status of the VM | every day |
| `Caddyfile` | Which web address goes to which application (+ serves the simulator) | never — installed by `02-provision.sh` |
| `impos.service`, `imvaluation.service`, `imfid.service` | How each application is launched (port, database, wiring between apps) | never — installed by `02-provision.sh` |

The wiring between applications (impos → imvaluation, impos → imfid,
passwords, ports) lives entirely in the three `.service` files: that is the
single place to look when a connection question arises.
