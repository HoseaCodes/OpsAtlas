# The console, as a container.
#
# Next's standalone output is the whole point of the split: the build stage needs
# the pnpm workspace and every dev dependency, and the runtime needs a server
# file and the modules it actually reaches.

# ---- build -----------------------------------------------------------------
FROM node:20-alpine AS build

WORKDIR /src

RUN corepack enable

# Workspace manifests first. Changing a component should not re-install the
# dependency tree.
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY apps/web/package.json ./apps/web/
COPY packages/contracts/package.json ./packages/contracts/
RUN pnpm install --frozen-lockfile

COPY packages/contracts ./packages/contracts
COPY apps/web ./apps/web

# The console reads the control plane's address at runtime, not at build time,
# so one image runs against any deployment.
RUN pnpm --filter @opsatlas/web build

# ---- runtime ---------------------------------------------------------------
FROM node:20-alpine AS runtime

ENV NODE_ENV=production
WORKDIR /app

RUN addgroup -g 1001 -S opsatlas && adduser -S opsatlas -u 1001 -G opsatlas

# Root-owned: the server runs this code and never writes it.
COPY --from=build /src/apps/web/.next/standalone ./
COPY --from=build /src/apps/web/.next/static ./apps/web/.next/static
# No public/ directory: this console ships no static assets of its own. Copying
# one that does not exist fails the build, which is how this was noticed.

USER opsatlas

EXPOSE 3000
ENV PORT=3000 HOSTNAME=0.0.0.0

# /login is the one page that renders without a session, so it is the honest
# thing to health-check: any other path answers a redirect.
#
# 127.0.0.1 rather than localhost, and not as a style preference: the container's
# /etc/hosts maps localhost to both 127.0.0.1 and ::1, Next's standalone server
# binds 0.0.0.0 and so listens on IPv4 only, and wget tries ::1 first. The
# result is a permanently unhealthy container serving traffic perfectly well.
# It passed locally, where the resolver happened to order them the other way,
# and failed on the first real host - so the address has to be explicit.
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=5 \
  CMD wget -qO- http://127.0.0.1:3000/login > /dev/null || exit 1

CMD ["node", "apps/web/server.js"]
