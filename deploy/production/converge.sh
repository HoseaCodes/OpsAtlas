#!/usr/bin/env bash
#
# Bring this box to the state master describes.
#
# Deployment is a pull, not a push. Nothing reaches in: there is no SSH key in
# GitHub Actions, no inbound port beyond the two Caddy holds, and no credential
# in a public repository's secrets that would put a shell on this machine. The
# cost is up to one interval of lag, which for a catalog is not a cost.
#
# The deployed version is `deploy/production/VERSION`, committed. That makes
# `git log -- deploy/production/VERSION` the deployment history, and a rollback
# a commit rather than an SSH session and a memory of what the old tag was.
#
# Safe to run at any time and as often as you like: it exits without touching
# anything when the box already matches master.
set -euo pipefail

REPO="${OPSATLAS_REPO:-/home/opsatlas/opsatlas}"
DIR="$REPO/deploy/production"
BRANCH="${OPSATLAS_BRANCH:-master}"

# One convergence at a time. The timer fires on a schedule and a compose pull can
# outlast an interval, and two `docker compose up` runs against one project is a
# way to get a half-applied stack.
exec 9>"/tmp/opsatlas-converge.lock"
if ! flock -n 9; then
    echo "another convergence is already running; leaving it to finish"
    exit 0
fi

cd "$REPO"

git fetch -q origin "$BRANCH"
before="$(git rev-parse HEAD)"
after="$(git rev-parse "origin/$BRANCH")"

if [ "$before" != "$after" ]; then
    echo "repository: $before -> $after"
    # Hard reset on purpose. This box is not a place where work happens, and a
    # local edit surviving a deploy is how a machine quietly stops matching the
    # thing that is supposed to describe it.
    git reset -q --hard "origin/$BRANCH"
fi

if [ ! -r "$DIR/VERSION" ]; then
    echo "no $DIR/VERSION, so there is no declared version to converge on" >&2
    exit 1
fi

desired="$(tr -d ' \t\r\n' < "$DIR/VERSION")"

# The version is interpolated into an image reference and written into .env, so
# it is checked rather than trusted. A malformed VERSION should stop this script,
# not produce an inventive image name.
if ! printf '%s' "$desired" | grep -qE '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$'; then
    echo "VERSION does not look like an image tag: '$desired'" >&2
    exit 1
fi

# ADR 0014 says a running version you cannot name is one you cannot roll back,
# and `latest` is the name that names nothing. The publish workflow no longer
# produces one; this refuses to deploy one if it ever does again.
if [ "$desired" = "latest" ]; then
    echo "VERSION is 'latest', which is not a deployable identifier (ADR 0014)." >&2
    echo "Put the release tag or commit SHA you actually mean." >&2
    exit 1
fi

current="$(sed -n 's/^OPSATLAS_TAG=//p' "$DIR/.env" 2>/dev/null | tr -d ' \t\r\n' || true)"

if [ "$desired" = "$current" ] && [ "$before" = "$after" ]; then
    echo "already at $desired and up to date with origin/$BRANCH; nothing to do"
    exit 0
fi

cd "$DIR"

if [ "$desired" != "$current" ]; then
    echo "version: ${current:-none} -> $desired"
    # Written into .env rather than exported, so that `docker compose ps` and
    # `logs` run by hand afterwards see the same version this script applied.
    # Two sources of truth for what is running is how you end up debugging the
    # wrong image.
    if grep -q '^OPSATLAS_TAG=' .env; then
        sed -i "s|^OPSATLAS_TAG=.*|OPSATLAS_TAG=$desired|" .env
    else
        printf 'OPSATLAS_TAG=%s\n' "$desired" >> .env
    fi
fi

# A pull that fails leaves the running stack alone: `up -d` is never reached, so
# a tag that was never published cannot take the site down, it can only fail to
# deploy. That is the right way round.
echo "pulling images"
docker compose pull --quiet

echo "applying"
docker compose up -d --remove-orphans

echo "converged to $desired at $(git rev-parse --short HEAD)"
