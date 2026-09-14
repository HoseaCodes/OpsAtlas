# OpsAtlas — single entry point.
#
# Targets appear here only when they work. Nothing below is a placeholder; if a
# capability is not built yet, its target does not exist. See docs/roadmap.md.
#
# Compatible with GNU Make 3.81 (the version macOS ships), so no .ONESHELL and
# no $(file ...).

COMPOSE := docker compose -f deploy/compose/docker-compose.yml
GRADLE  := ./gradlew --console=plain

.DEFAULT_GOAL := help
.PHONY: help install check-examples check-secrets observer-key issuer-key first-user prometheus-key typecheck test test-all test-java test-web test-observer check dev dev-web dev-observer e2e up up-telemetry down down-telemetry logs psql clean clean-db openapi check-openapi

help: ## Show the targets that exist today
	@echo ""
	@echo "  OpsAtlas — phase 8 (see docs/roadmap.md)"
	@echo ""
	@grep -E '^[a-z-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[1m%-16s\033[0m %s\n", $$1, $$2}'
	@echo ""

# -- Running ----------------------------------------------------------------

issuer-key: ## Generate this machine's local signing key for the identity provider
	@if grep -q '^JWT_PRIVATE_KEY=' deploy/compose/.env 2>/dev/null; then \
		echo "A local signing key already exists in deploy/compose/.env; leaving it alone."; \
	else \
		mkdir -p deploy/compose; \
		printf 'JWT_PRIVATE_KEY=%s\n' "$$(openssl genrsa 2048 2>/dev/null | base64 | tr -d '\n')" \
			>> deploy/compose/.env; \
		echo "Generated a local signing key into deploy/compose/.env (gitignored)."; \
		echo "It signs tokens for local development only. Never reuse it anywhere real."; \
	fi

up: issuer-key ## Start PostgreSQL, the identity provider, and wait for them
	$(COMPOSE) up -d
	@echo "Waiting for PostgreSQL to become healthy..."
	@for i in $$(seq 1 60); do \
		state=$$($(COMPOSE) ps --format json postgres 2>/dev/null | grep -o '"Health":"[a-z]*"' | head -1); \
		case "$$state" in \
			*healthy*) echo "PostgreSQL is ready."; exit 0 ;; \
		esac; \
		sleep 1; \
	done; \
	echo "PostgreSQL did not become healthy in 60s. Try: make logs"; exit 1

up-telemetry: ## Start the collector, Tempo, Prometheus and Grafana
	$(COMPOSE) --profile telemetry up -d
	@echo ""
	@echo "  Grafana     http://localhost:3001   (Tempo and Prometheus provisioned)"
	@echo "  Tempo       http://localhost:3200"
	@echo "  Prometheus  http://localhost:9091"
	@echo ""
	@echo "  Behind a profile on purpose: working on the catalog should not"
	@echo "  require running four more containers."
	@echo ""

down: ## Stop PostgreSQL, keeping its data
	$(COMPOSE) down

down-telemetry: ## Stop the telemetry stack
	$(COMPOSE) --profile telemetry down

logs: ## Follow the PostgreSQL logs
	$(COMPOSE) logs -f postgres

psql: ## Open a psql shell in the running database container
	$(COMPOSE) exec postgres psql -U opsatlas -d opsatlas

# The account the local stack is used as.
#
# A fresh stack admits nobody: the identity provider has no accounts, and a token
# only works once a principal row exists for it (ADR 0013). This creates the
# account at the issuer, reads back the subject the issuer assigned, and records
# it so `make dev` can provision it on startup. Idempotent - run it again and it
# signs in instead.
LOCAL_EMAIL ?= operator@opsatlas.local
LOCAL_PASSWORD ?= Str0ng-Local-Passw0rd!
ISSUER_URL ?= http://localhost:8090

first-user: up ## Create the local account and record it for bootstrap
	@set -e; \
	body='{"name":"Local Operator","email":"$(LOCAL_EMAIL)","username":"operator",'; \
	body="$$body\"password\":\"$(LOCAL_PASSWORD)\",\"role\":0,\"application\":\"opsatlas\"}"; \
	token=$$(curl -s -X POST $(ISSUER_URL)/register -H 'Content-Type: application/json' -d "$$body" \
		| sed -n 's/.*"accesstoken":"\([^"]*\)".*/\1/p'); \
	if [ -z "$$token" ]; then \
		token=$$(curl -s -X POST $(ISSUER_URL)/login -H 'Content-Type: application/json' \
			-d '{"email":"$(LOCAL_EMAIL)","password":"$(LOCAL_PASSWORD)"}' \
			| sed -n 's/.*"accesstoken":"\([^"]*\)".*/\1/p'); \
	fi; \
	if [ -z "$$token" ]; then \
		echo "Could not create or sign in to $(LOCAL_EMAIL) at $(ISSUER_URL)."; \
		echo "Is the stack up? Try: make up"; exit 1; \
	fi; \
	subject=$$(printf '%s' "$$token" | cut -d. -f2 | tr '_-' '/+' \
		| awk '{ while (length($$0) % 4) $$0 = $$0 "="; print }' | base64 -d 2>/dev/null \
		| sed -n 's/.*"id":"\([^"]*\)".*/\1/p'); \
	if [ -z "$$subject" ]; then echo "The issuer returned a token with no subject id."; exit 1; fi; \
	sed -i.bak '/^OPSATLAS_BOOTSTRAP_/d' deploy/compose/.env 2>/dev/null || true; \
	rm -f deploy/compose/.env.bak; \
	{ echo "OPSATLAS_BOOTSTRAP_ISSUER=$(ISSUER_URL)"; \
	  echo "OPSATLAS_BOOTSTRAP_SUBJECT=$$subject"; \
	  echo "OPSATLAS_BOOTSTRAP_DISPLAY_NAME='Local Operator'"; } >> deploy/compose/.env; \
	echo ""; \
	echo "  $(LOCAL_EMAIL) is ready at the issuer, subject $$subject."; \
	echo "  Recorded in deploy/compose/.env, which is gitignored."; \
	echo "  'make dev' will provision it on startup."; \
	echo ""

dev: up ## Start the database, then run the control plane on :8080
	@echo ""
	@echo "  Control plane starting on http://localhost:8080"
	@echo "  Then, in another terminal: make dev-web"
	@echo ""
	@if [ -f deploy/compose/.env ]; then set -a; . ./deploy/compose/.env; set +a; fi; \
	$(GRADLE) :control-plane:bootRun

dev-web: ## Run the console on :3000 (needs the control plane running)
	pnpm --filter @opsatlas/web dev

dev-observer: ## Run the observer (needs the control plane running; Go 1.27+)
	@echo ""
	@echo "  Probing every environment the catalog declares."
	@echo "  Metrics on http://localhost:9090/metrics"
	@echo ""
	cd apps/observer && go run ./cmd/observer

# -- Contracts --------------------------------------------------------------

openapi: ## Regenerate the OpenAPI document and the TypeScript types
	$(GRADLE) :control-plane:generateOpenApiDocument
	pnpm --filter @opsatlas/contracts generate:types

check-openapi: openapi ## Fail if the committed contract is out of date with the code
	@git diff --exit-code -- packages/contracts/openapi packages/contracts/src/generated \
		|| (echo ""; \
		    echo "The committed OpenAPI contract is out of date with the controllers."; \
		    echo "Run 'make openapi' and commit the result."; \
		    exit 1)
	@echo "OpenAPI contract is current."

# -- Checking ---------------------------------------------------------------

install: ## Install workspace dependencies
	pnpm install

check-examples: ## Validate every example and fixture manifest against the schema
	pnpm --filter @opsatlas/contracts check:examples

observer-key: ## Generate a key for the observer (set it on both sides)
	@printf 'opsatlas_sk_%s\n' "$$(LC_ALL=C tr -dc 'A-Za-z0-9_-' < /dev/urandom | head -c 43)"
	@echo "" >&2
	@echo "  Set it as OPSATLAS_OBSERVER_KEY on the control plane," >&2
	@echo "  and as OPSATLAS_API_KEY on the observer. It is never printed again." >&2
	@echo "" >&2

prometheus-key: ## Generate the key Prometheus scrapes the control plane with
	@mkdir -p deploy/compose/telemetry
	@if [ -s deploy/compose/telemetry/opsatlas.key ]; then \
		echo "A scrape key already exists; leaving it alone."; \
	else \
		printf 'opsatlas_sk_%s' "$$(LC_ALL=C tr -dc 'A-Za-z0-9_-' < /dev/urandom | head -c 43)" \
			> deploy/compose/telemetry/opsatlas.key; \
		echo "Wrote deploy/compose/telemetry/opsatlas.key (gitignored)."; \
	fi
	@echo ""
	@echo "  Start the control plane with:"
	@echo "    OPSATLAS_PROMETHEUS_KEY=$$(cat deploy/compose/telemetry/opsatlas.key) make dev"
	@echo ""

check-secrets: ## Refuse credential-shaped strings and tracked key files
	./scripts/check-secrets.sh

test-java: ## Run the control plane tests (needs a running Docker daemon)
	$(GRADLE) :control-plane:test

typecheck: ## Type-check the workspace
	pnpm --filter @opsatlas/contracts typecheck
	pnpm --filter @opsatlas/web typecheck

test-web: ## Run the console component tests
	pnpm --filter @opsatlas/web test

test-observer: ## Run the observer tests, with the race detector
	cd apps/observer && go test ./... -race

e2e: ## Run the browser smoke test (needs the control plane running)
	pnpm --filter @opsatlas/web e2e

test: check-secrets check-examples typecheck test-web test-observer test-java ## Every test that needs no running server

test-all: test e2e ## Everything, including the browser smoke test

check: test ## Alias for test

# -- Cleaning ---------------------------------------------------------------

clean: ## Remove build output and installed dependencies
	$(GRADLE) clean
	rm -rf apps/web/.next apps/web/test-results
	cd apps/observer && go clean -cache -testcache
	rm -rf node_modules packages/*/node_modules apps/web/node_modules

clean-db: ## Destroy the local database and its data
	$(COMPOSE) down -v
