# OpsAtlas — single entry point.
#
# Targets appear here only when they work. There is no `make dev` yet because
# there is no control plane yet; it arrives in phase 1. See docs/roadmap.md.
#
# Compatible with GNU Make 3.81 (the version macOS ships), so no .ONESHELL and
# no $(file ...).

.DEFAULT_GOAL := help
.PHONY: help install check-examples check clean

help: ## Show the targets that exist today
	@echo ""
	@echo "  OpsAtlas — phase 0 of slice one (see docs/roadmap.md)"
	@echo ""
	@grep -E '^[a-z-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[1m%-16s\033[0m %s\n", $$1, $$2}'
	@echo ""
	@echo "  Not yet, and deliberately absent rather than stubbed:"
	@echo "    dev, up, down, test, openapi   phase 1 onward"
	@echo ""

install: ## Install workspace dependencies
	pnpm install

check-examples: ## Validate every example and fixture manifest against the schema
	pnpm --filter @opsatlas/contracts check:examples

check: check-examples ## Run every check that exists today

clean: ## Remove installed dependencies and build output
	rm -rf node_modules packages/*/node_modules apps/*/node_modules
