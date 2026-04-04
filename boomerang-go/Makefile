.PHONY: test test-integration tidy lint

test:
	go test ./...

tidy:
	go mod tidy

lint:
	go vet ./...

test-integration:
	docker compose -f docker-compose.test.yml up -d --wait
	BOOMERANG_URL=http://localhost:8080 \
	WIREMOCK_WORKER_URL=http://wiremock:8080/worker \
	WIREMOCK_CALLBACK_URL=http://wiremock:8080/callback \
	go test -tags=integration -v ./...
	docker compose -f docker-compose.test.yml down
