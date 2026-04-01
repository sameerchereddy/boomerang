.PHONY: test-integration clean-integration

COMPOSE_FILE := test/integration/docker-compose.test.yml

test-integration:
	docker compose -f $(COMPOSE_FILE) up -d
	./scripts/wait-for-health.sh
	npx vitest run --config vitest.integration.config.ts
	docker compose -f $(COMPOSE_FILE) down

clean-integration:
	docker compose -f $(COMPOSE_FILE) down -v
