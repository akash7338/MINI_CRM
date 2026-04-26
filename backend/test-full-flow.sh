#!/bin/bash

set -e

TENANT_ID="tenant1"
AGENT_ID="agent-$(date +%s)"
AGENT_NAME="Agent $(date +%s)"
SKILL="sales"

echo "=== MiniGenesys Full Flow Test ==="

echo "1. Ensuring agent DB record exists..."

psql minigenesys_agent_state <<SQL
INSERT INTO agents (
    id,
    name,
    status,
    tenant_id,
    last_assigned_time,
    created_at,
    updated_at
)
VALUES (
    '$AGENT_ID',
    '$AGENT_NAME',
    'OFFLINE',
    '$TENANT_ID',
    0,
    NOW(),
    NOW()
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO agent_skills (agent_id, skill)
VALUES ('$AGENT_ID', '$SKILL')
ON CONFLICT DO NOTHING;
SQL

echo "2. Logging in agent..."

curl -s -X POST "http://localhost:8086/api/v1/agents/$AGENT_ID/login" \
  -H "X-Tenant-Id: $TENANT_ID"

echo
echo "3. Checking Redis availability..."

docker exec funny_napier redis-cli ZRANGE "tenant:$TENANT_ID:skill:$SKILL:available" 0 -1 WITHSCORES

echo
echo "4. Creating call through Call Service..."

CALL_RESPONSE=$(curl -s -X POST "http://localhost:8087/api/v1/calls" \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: $TENANT_ID" \
  -d "{
    \"requiredSkills\": [\"$SKILL\"],
    \"priority\": 1
  }")

echo "$CALL_RESPONSE"

echo
echo "5. Waiting for routing-service to consume Kafka event..."
sleep 3

echo
echo "6. Checking Redis availability after routing..."

docker exec funny_napier redis-cli ZRANGE "tenant:$TENANT_ID:skill:$SKILL:available" 0 -1 WITHSCORES

echo
echo "7. Latest routing-events from Kafka:"
gtimeout 5 docker exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic routing-events \
  --from-beginning || true

echo
echo "=== Test completed ==="
