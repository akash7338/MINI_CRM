#!/bin/bash

# Load environment variables (Twilio creds, DB overrides, etc.)
ENV_FILE="$HOME/.envs/minigenesys.env"
if [ -f "$ENV_FILE" ]; then
    source "$ENV_FILE"
    echo "✅ Loaded env vars from $ENV_FILE"
else
    echo "⚠️  WARNING: $ENV_FILE not found. Services will start with dummy defaults."
fi

# Create logs directory if not exists
mkdir -p logs

echo "=== Starting MiniGenesys Backend Services ==="

# Function to start a service
start_service() {
    local dir=$1
    local name=$2
    echo "Starting $name..."
    # With a root multi-project build, we run the gradle wrapper from the root directory
    ./gradlew :$name:bootRun > "logs/$name.log" 2>&1 &
    echo $! > "logs/$name.pid"
    sleep 2 # Let it start initializing
}

# Start infrastructure services (already running in Docker: Postgres, Kafka, Redis, FreeSWITCH)

# Start Java services
start_service "user-service" "user-service"
start_service "agent-state-service" "agent-state-service"
start_service "call-service" "call-service"
start_service "routing-service" "routing-service"
start_service "websocket-gateway" "websocket-gateway"
start_service "telephony-service" "telephony-service"
# freeswitch-service is already running or we can start it if not running
if ! ps aux | grep FreeswitchServiceApplication | grep -v grep > /dev/null; then
    start_service "freeswitch-service" "freeswitch-service"
else
    echo "freeswitch-service is already running."
fi
start_service "api-gateway" "api-gateway"

echo "=== All services launched. Logs are in backend/logs/ ==="
