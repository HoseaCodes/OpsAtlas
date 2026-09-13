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
.PHONY: help install check-examples test test-java check dev up down logs psql clean clean-db

help: ## Show the targets that exist today
	@echo ""
	@echo "  OpsAtlas — slice one, phase 1 (see docs/roadmap.md)"
	@echo ""
	@grep -E '^[a-z-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[1m%-16s\033[0m %s\n", $$1, $$2}'
	@echo ""
	@echo "  Not yet, and deliberately absent rather than stubbed:"
	@echo "    openapi, web    phase 4"
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
	@echo "  Try:  curl -s localhost:8080/api/v1/services | jq"
	@echo ""
	$(GRADLE) :control-plane:bootRun

# -- Checking ---------------------------------------------------------------

install: ## Install workspace dependencies
	pnpm install

check-examples: ## Validate every example and fixture manifest against the schema
	pnpm --filter @opsatlas/contracts check:examples

test-java: ## Run the control plane tests (needs a running Docker daemon)
	$(GRADLE) :control-plane:test

test: check-examples test-java ## Run every test there is

check: test ## Alias for test

# -- Cleaning ---------------------------------------------------------------

clean: ## Remove build output and installed dependencies
	$(GRADLE) clean
	rm -rf node_modules packages/*/node_modules apps/web/node_modules

clean-db: ## Destroy the local database and its data
	$(COMPOSE) down -v
