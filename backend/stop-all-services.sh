#!/bin/bash

echo "=== Stopping MiniGenesys Backend Services ==="

for pid_file in logs/*.pid; do
    if [ -f "$pid_file" ]; then
        pid=$(cat "$pid_file")
        name=$(basename "$pid_file" .pid)
        if kill -0 "$pid" 2>/dev/null; then
            echo "Stopping $name (PID: $pid)..."
            kill "$pid"
        else
            echo "$name is not running."
        fi
        rm "$pid_file"
    fi
done

# Also kill any remaining gradle/bootRun tasks
pkill -f bootRun || true
pkill -f FreeswitchServiceApplication || true

echo "=== All services stopped ==="
