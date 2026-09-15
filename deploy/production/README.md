# Deploying OpsAtlas

One virtual machine running seven containers, one of which is Caddy. Why this
shape and what it costs is
[ADR 0014](../../docs/adr/0014-deployment-is-one-box.md); this file is how to do
it.

**This has been run.** It was executed end to end against a DigitalOcean droplet
on 2026-09-15 and the result is serving at `https://opsatlas.hoseacodes.com`:
seven containers healthy, a Let's Encrypt certificate obtained over
`tls-alpn-01`, and `GET /api/v1/services` answering 200 for the provisioned
operator and 401 without a token.

It found three faults that a laptop could not have surfaced, all now fixed —
the documented first start was impossible, the console image was permanently
unhealthy while serving traffic correctly, and `publish.yml` produced a `latest`
tag it claimed never to produce. If you are reading this to deploy a second one,
you are following a path that has been walked.

## What you need first

- A VM running Ubuntu 24.04 with **2GB of RAM at minimum, 4GB recommended**, and
  about 25GB of disk. Measured idle usage of all seven containers is ~665MB
  (the JVM 371MB, MongoDB 120MB, the rest under 55MB each), and Ubuntu with
  Docker adds ~450MB — so 2GB fits with roughly 800MB spare.

  The reasons to take 4GB anyway: the JVM's heap ceiling is a *percentage* of
  the box (`MaxRAMPercentage=75`) and MongoDB's WiredTiger cache is about half
  of (RAM − 1GB), so both expand into whatever exists and neither is bounded by
  that 665MB figure. And on a single vCPU the JVM ergonomically selects
  **SerialGC** instead of G1, with one core shared by the prober, the retention
  job and the GitHub sync. Fine for light traffic; less margin when something
  goes wrong.
- A domain you control.
- The GitHub repository's packages, which is where the images come from.

## 1. DNS, before anything else

One A record, pointing at the box's IP:

```text
opsatlas.example.com        A    203.0.113.10
```

That is the whole public surface. The console calls the control plane and the
identity provider from the server over the compose network, so neither needs a
hostname — and leaving the issuer unpublished means its open `/register` is not
on the internet at all, which beats publishing it and relying on the 403 behind
it.

Do this first and let it propagate. Caddy asks Let's Encrypt for a certificate
the moment it starts, and a name that does not resolve yet spends one of the
**five attempts per hostname per week** that the rate limit allows.

`OPSATLAS_ISSUER_ID` is a separate matter and needs no DNS. It is the string
stamped into every token's `iss`, compared by the control plane and never
fetched. Set it to the name you *would* publish the issuer under — changing it
later invalidates every token in circulation, so choosing it now makes
publishing the issuer additive rather than a migration.

Check before continuing:

```bash
dig +short opsatlas.example.com
```

## 2. The box

As root, once:

```bash
# A user that is not root
adduser --disabled-password --gecos "" opsatlas
usermod -aG sudo opsatlas
mkdir -p /home/opsatlas/.ssh
cp ~/.ssh/authorized_keys /home/opsatlas/.ssh/
chown -R opsatlas:opsatlas /home/opsatlas/.ssh
chmod 700 /home/opsatlas/.ssh && chmod 600 /home/opsatlas/.ssh/authorized_keys

# Keys only, no root login
sed -i 's/^#\?PermitRootLogin.*/PermitRootLogin no/' /etc/ssh/sshd_config
sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication no/' /etc/ssh/sshd_config
systemctl restart ssh

# Security updates without being asked
apt-get update && apt-get install -y unattended-upgrades
dpkg-reconfigure -plow unattended-upgrades

# Docker, from Docker's repository rather than Ubuntu's
curl -fsSL https://get.docker.com | sh
usermod -aG docker opsatlas
```

Then the firewall. Do this **after** confirming you can still log in as
`opsatlas`, and note that `ufw` alone is not enough:

```bash
ufw default deny incoming && ufw default allow outgoing
ufw allow 22/tcp && ufw allow 80/tcp && ufw allow 443/tcp
ufw enable
```

> **Docker bypasses ufw.** Docker writes its own iptables rules ahead of ufw's,
> so a published port is reachable whatever ufw says. This compose file only
> publishes 80 and 443, so there is nothing exposed for it to bypass — but if
> you ever add a `ports:` entry, ufw will not protect it. Bind to `127.0.0.1`
> explicitly in that case.
>
> If your provider has a firewall that runs outside the machine — DigitalOcean's
> Cloud Firewall, AWS security groups — use it as well, and treat it as the real
> one. It filters before the packet reaches the host, so nothing Docker does to
> iptables can undo it. `ufw` then becomes defence in depth rather than the only
> thing standing between a misconfigured `ports:` line and the internet.

## 3. Images

Cut a release, which builds and pushes all three:

```bash
git tag v0.1.0 && git push origin v0.1.0
```

Or run **Publish images** from the Actions tab to build from a branch, which
tags each image with the commit SHA. Either way, note the tag — it goes in
`OPSATLAS_TAG` and it is what you roll back to.

The packages are private on first publish. Make them public, or give the box a
read-only token:

```bash
echo "$GHCR_READ_TOKEN" | docker login ghcr.io -u <username> --password-stdin
```

## 4. Configuration

On the box, as `opsatlas`:

```bash
git clone https://github.com/HoseaCodes/OpsAtlas.git ~/opsatlas
cd ~/opsatlas/deploy/production

umask 077                   # before the file exists, not after
cp .env.example .env
```

Generate each secret and paste it in. Do not reuse anything from
`deploy/compose/.env` — that key has been on a laptop:

```bash
openssl rand -base64 32          # OPSATLAS_DB_PASSWORD
openssl rand -base64 32          # OPSATLAS_MONGO_PASSWORD
openssl rand -base64 32          # ACCESS_TOKEN_SECRET
openssl rand -base64 32          # REFRESH_TOKEN_SECRET
openssl genrsa 2048 | base64 -w0 # JWT_PRIVATE_KEY
```

`OPSATLAS_OBSERVER_KEY` comes from `make observer-key`, run anywhere. Leave the
two `OPSATLAS_BOOTSTRAP_*` values empty for now — the issuer has not assigned a
subject yet.

## 5. Start

```bash
docker compose up -d
docker compose ps
```

Watch Caddy get its certificates. This is where DNS mistakes show up:

```bash
docker compose logs -f caddy
```

If a secret is missing, compose refuses to start and names it. That is the
`${VAR:?}` guard working.

## 6. The first user

The stack is now running and **admits nobody**. Storm-Gate has no accounts, and
a token only works once a `principal` row exists for it — so there is one
account to create by hand, and everyone after that is added from inside.

The issuer has no public route, so reach it through an SSH tunnel. In one
terminal:

```bash
ssh -N -L 8090:127.0.0.1:8090 opsatlas@<droplet-ip>
```

That forwards your local 8090 to the loopback port Storm-Gate is bound to on the
box. In another terminal, from a machine with the repository:

```bash
make prod-first-user \
  ISSUER_URL_PROD=http://localhost:8090 \
  PROD_EMAIL=you@example.com \
  PROD_PASSWORD='...' \
  PROD_NAME='Your Name'
```

The tunnel is only needed for this one step; close it afterwards. The target
reads the subject out of the token it gets back and does not care what `iss`
says, so pointing it at the tunnel rather than the issuer's real name is fine.

It prints two lines. Put them in `.env` on the box and restart the control plane
so it provisions the principal on startup:

```bash
docker compose up -d control-plane
```

> Between starting the stack and doing this, signing in succeeds and the catalog
> answers **403 `not-provisioned`**. That is the intended behaviour, not a
> fault: a verified token is not a membership. It is also the property that would
> make publishing the issuer survivable if you ever did — an account created at
> an open `/register` gets 403 here until somebody adds a principal row. Right
> now the issuer is not published at all, so the question does not arise.

## 7. Check it actually works

On the box, since the API is not published:

```bash
# Open by design
docker compose exec control-plane wget -qO- http://localhost:8080/actuator/health

# Every container healthy, none restarting
docker compose ps

# The observer is probing
docker compose logs observer | tail -20

# Caddy got its certificate
docker compose logs caddy | grep -i "certificate obtained"
```

From anywhere:

```bash
curl -sI https://opsatlas.example.com/login | head -3
```

Then sign in at `https://opsatlas.example.com` and register a service. That
exercises the console, the control plane, the issuer and the database in one
action, which makes it a better check than any of the above.

## Operating it

**Update** — change `OPSATLAS_TAG` in `.env`, then:

```bash
docker compose pull && docker compose up -d
```

**Roll back** — the same thing with the previous tag. This is what `latest`
would have cost you.

**Logs** — `docker compose logs -f control-plane`. Capped at 30MB per container,
so they will not fill the disk.

**Back up the database** — nothing does this yet, and until something does, do
not say the data survives the box:

```bash
docker compose exec -T postgres pg_dump -U opsatlas opsatlas | gzip > opsatlas-$(date +%F).sql.gz
```

Copy it off the host. A backup on the machine it protects is not a backup.

## Publishing the API or the issuer later

Neither is wired up, and both are additive. Nothing about doing this
invalidates a token, because `OPSATLAS_ISSUER_ID` already holds the public name.

1. Add an A record for the hostname, pointing at the same IP.
2. Add the variable to the `caddy` service's `environment` in
   `docker-compose.yml` — `OPSATLAS_API_HOST` or `OPSATLAS_AUTH_HOST` — and set
   it in `.env`.
3. Add a site block to the `Caddyfile`:

   ```caddyfile
   {$OPSATLAS_API_HOST} {
   	import common
   	reverse_proxy control-plane:8080
   }
   ```

4. `docker compose up -d caddy`.

Worth doing for the API if you want to `curl` it, fetch the OpenAPI document
over the wire, or run the observer somewhere other than this box. Every endpoint
behind it requires a verified token, and an unprovisioned caller gets 403.

Think harder before publishing the issuer. It puts an open registration endpoint
on the internet. That is survivable — an account created there gets 403 from
OpsAtlas until somebody adds a principal row — but it is a real thing to have
exposed, and nothing currently needs it.

## Known gaps

- **No backups**, beyond the manual command above.
- **No zero-downtime deploy.** `up -d` stops and starts containers; the console
  is down for those seconds.
- **No telemetry.** The collector, Tempo, Prometheus and Grafana stay a local
  profile — four more containers is most of a 4GB box. `OPSATLAS_TRACING_ENABLED`
  stays `false` unless you point it at a collector elsewhere.
- **One box.** No redundancy and no failover. Nothing here is highly available.
