# Deploying OpsAtlas

One virtual machine running six containers behind Caddy. Why this shape and what
it costs is [ADR 0014](../../docs/adr/0014-deployment-is-one-box.md); this file
is how to do it.

Nothing here has been run against a real host yet. It is assembled from parts
that were each verified locally — the compose file renders and refuses missing
secrets, the Caddyfile passes `caddy validate`, all three images build,
`make prod-first-user` creates an account and reads back its subject — but the
sequence below has not been executed end to end. Expect to hit something.

## What you need first

- A VM with **4GB of RAM** and about 20GB of disk, running Ubuntu 24.04. The JVM
  wants roughly 1GB and PostgreSQL, MongoDB, two Node processes and Go fill most
  of the rest. 2GB is not enough.
- A domain you control.
- The GitHub repository's packages, which is where the images come from.

## 1. DNS, before anything else

Three A records, all pointing at the box's IP:

```text
opsatlas.example.com        A    203.0.113.10
api.opsatlas.example.com    A    203.0.113.10
auth.opsatlas.example.com   A    203.0.113.10
```

Do this first and let it propagate. Caddy asks Let's Encrypt for certificates
the moment it starts, and a name that does not resolve yet spends one of the
**five attempts per hostname per week** that the rate limit allows.

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

From a machine with the repository:

```bash
make prod-first-user \
  ISSUER_URL_PROD=https://auth.opsatlas.example.com \
  PROD_EMAIL=you@example.com \
  PROD_PASSWORD='...' \
  PROD_NAME='Your Name'
```

It prints two lines. Put them in `.env` on the box and restart the control plane
so it provisions the principal on startup:

```bash
docker compose up -d control-plane
```

> Between starting the stack and doing this, signing in succeeds and the catalog
> answers **403 `not-provisioned`**. That is the intended behaviour, not a
> fault: a verified token is not a membership. It is also why Storm-Gate's open
> `/register` can face the internet — anyone can create an account there and it
> gets them nothing here.

## 7. Check it actually works

```bash
# Open by design
curl -s https://api.opsatlas.example.com/actuator/health

# Refused without a credential
curl -s -o /dev/null -w '%{http_code}\n' https://api.opsatlas.example.com/api/v1/services   # 401

# The issuer publishes its keys
curl -s https://auth.opsatlas.example.com/.well-known/jwks.json | head -c 100

# The observer is probing
docker compose logs observer | tail -20
```

Then sign in at `https://opsatlas.example.com` and register a service.

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

## Known gaps

- **No backups**, beyond the manual command above.
- **No zero-downtime deploy.** `up -d` stops and starts containers; the console
  is down for those seconds.
- **No telemetry.** The collector, Tempo, Prometheus and Grafana stay a local
  profile — four more containers is most of a 4GB box. `OPSATLAS_TRACING_ENABLED`
  stays `false` unless you point it at a collector elsewhere.
- **One box.** No redundancy and no failover. Nothing here is highly available.
