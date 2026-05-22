#!/bin/bash
echo "Starting agent login and keep-alive loop..."
curl -s -X POST http://localhost:8086/api/v1/agents/AG-FREESWITCH/login -H "X-Tenant-Id: tenant-freeswitch"
while true; do
  curl -s -X POST http://localhost:8086/api/v1/agents/AG-FREESWITCH/heartbeat -H "X-Tenant-Id: tenant-freeswitch" > /dev/null
  sleep 5
done
