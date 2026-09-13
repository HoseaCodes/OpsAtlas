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
.PHONY: help install check-examples typecheck test test-all test-java test-web test-observer check dev dev-web dev-observer e2e up down logs psql clean clean-db openapi check-openapi

help: ## Show the targets that exist today
	@echo ""
	@echo "  OpsAtlas — phase 7 (see docs/roadmap.md)"
	@echo ""
	@grep -E '^[a-z-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[1m%-16s\033[0m %s\n", $$1, $$2}'
	@echo ""

# -- Running ----------------------------------------------------------------

up: ## Start PostgreSQL and wait until it is accepting connections
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

down: ## Stop PostgreSQL, keeping its data
	$(COMPOSE) down

logs: ## Follow the PostgreSQL logs
	$(COMPOSE) logs -f postgres

psql: ## Open a psql shell in the running database container
	$(COMPOSE) exec postgres psql -U opsatlas -d opsatlas

dev: up ## Start the database, then run the control plane on :8080
	@echo ""
	@echo "  Control plane starting on http://localhost:8080"
	@echo "  Then, in another terminal: make dev-web"
	@echo ""
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

test: check-examples typecheck test-web test-observer test-java ## Every test that needs no running server

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
