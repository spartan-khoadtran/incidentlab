# Incident investigation lab (RED & USE). `make` (no target) lists the commands.
COMPOSE ?= docker compose
LABCTL   = /run/app/labctl/bin/labctl
CTL      = $(COMPOSE) exec control $(LABCTL)
GRADLE   = cd service && ./gradlew

.DEFAULT_GOAL := help
.PHONY: help up up-datadog wait new hint answer reveal clear status list history quiz load \
        open logs ps stop down nuke check build test lint format dashboards

help: ## List the commands
	@echo "redlab: incident investigation lab (RED & USE)"
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-11s\033[0m %s\n", $$1, $$2}'

# ----------------------------------------------------------------------------- lifecycle
up: check ## Build and start the whole lab
	$(COMPOSE) up -d --build
	@$(MAKE) --no-print-directory wait

up-datadog: check ## Like `up`, with the Datadog Agent (needs DD_API_KEY in .env)
	@test -n "$$(grep -s '^DD_API_KEY=.' .env)" || (echo "DD_API_KEY is missing from .env (see .env.example)"; exit 1)
	OTLP_ENDPOINT=http://datadog-agent:4318 LOADGEN_TRACE_SAMPLE_RATIO=1.0 $(COMPOSE) --profile datadog up -d --build
	@$(MAKE) --no-print-directory wait

wait:
	@printf "Waiting for the system to come up"
	@for i in $$(seq 1 90); do \
	  if curl -sf -o /dev/null http://localhost:8080/products/1; then echo " OK"; break; fi; \
	  printf "."; sleep 3; \
	  if [ $$i -eq 90 ]; then echo; echo "api is not answering yet. Check: make ps, make logs S=api"; exit 1; fi; \
	done
	@echo ""
	@echo "  Grafana     http://localhost:3000"
	@echo "  Traces      in Grafana: Explore > Tempo (or click an exemplar dot on a latency panel)"
	@echo "  Prometheus  http://localhost:9090"
	@echo "  Envoy       http://localhost:8080 (admin: http://localhost:9901)"
	@echo ""
	@echo "Let the system run for 3-5 minutes to get a baseline, then type: make new"

stop: ## Stop the containers, keep the data (start again with make up)
	$(COMPOSE) --profile datadog stop

down: ## Stop and remove everything: containers, network, volumes (data, exercise history)
	$(COMPOSE) --profile datadog down -v --remove-orphans

nuke: ## Like `down`, and also remove the built image
	$(COMPOSE) --profile datadog down -v --remove-orphans --rmi local

# ----------------------------------------------------------------------------- exercises
new: ## New incident exercise on the live lab. Options: LEVEL=1|2|3 TOPIC=async ID=pool_hold FORCE=1
	@$(CTL) new $(if $(LEVEL),--level $(LEVEL)) $(if $(TOPIC),--topic $(TOPIC)) $(if $(ID),--id $(ID)) $(if $(FORCE),--force)

hint: ## Next hint (costs 0.5 point each)
	@$(CTL) hint

answer: ## Submit your answer, see the score and the explanation
	@$(CTL) answer

reveal: ## Show the answer, not scored
	@$(CTL) reveal

clear: ## Cancel the running fault
	@$(CTL) clear

status: ## Current exercise and service status
	@$(CTL) status

list: ## Exercise catalog by level and topic
	@$(CTL) list

history: ## History and scores
	@$(CTL) history

quiz: ## Theory drills from the guide (N=20, KIND=numbers|concepts|all). Does not need the lab running
	@$(COMPOSE) build -q control
	@$(COMPOSE) run --rm --no-deps control $(LABCTL) quiz -n $(or $(N),20) --kind $(or $(KIND),all)

load: ## Change the background load: X=1.5 (times the default 50 rps)
	@$(CTL) load $(or $(X),1)

# ----------------------------------------------------------------------------- tools
open: ## Open Grafana in the browser (macOS)
	@open http://localhost:3000 || true

logs: ## Tail logs: S=worker (empty = all services)
	$(COMPOSE) logs -f --tail=100 $(S)

ps: ## Container status
	$(COMPOSE) ps

dashboards: ## Regenerate the Grafana dashboards from service/tool/dashboards
	@$(COMPOSE) build -q control
	@$(COMPOSE) run --rm --no-deps -v "$(CURDIR)/config/grafana/dashboards:/out" control /run/app/dashboards/bin/dashboards /out

# ----------------------------------------------------------------------------- development (needs a JDK; Gradle fetches JDK 21 itself)
build: ## Build every service with Gradle on this machine
	$(GRADLE) installDist

test: ## Run the unit tests
	$(GRADLE) test

lint: ## Run ktlint
	$(GRADLE) ktlintCheck --continue

format: ## Apply ktlint formatting
	$(GRADLE) ktlintFormat

check:
	@command -v docker >/dev/null || (echo "Docker is needed: install Docker Desktop (https://www.docker.com/products/docker-desktop/) or OrbStack (https://orbstack.dev)"; exit 1)
	@docker info >/dev/null 2>&1 || (echo "Docker is not running. Start Docker Desktop or OrbStack and try again."; exit 1)
	@test -f .env || cp .env.example .env
