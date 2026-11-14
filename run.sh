#!/usr/bin/env bash
#
# Single entry point for the course-waitlist service.
#
#   ./run.sh               Build and run the app in the foreground (Ctrl+C to stop)
#   ./run.sh test          Build, then run the curl scenario suites, each against a fresh app instance
#   ./run.sh unit          Run the JUnit test suite (mvn test)
#   ./run.sh all           unit + test
#   ./run.sh help          Show this help
#
# Environment:
#   PORT=8080              HTTP port (default 8080)
#   MVN_FLAGS=-o           Extra Maven flags, e.g. -o to build offline
#
# Requires JDK 17+, Maven 3.9+, curl and bash (macOS's bash 3.2 is fine).

set -euo pipefail
cd "$(dirname "$0")"

PORT="${PORT:-8080}"
MVN_FLAGS="${MVN_FLAGS:-}"
JAR="target/course-waitlist-0.1.0.jar"
BASE_URL="http://localhost:$PORT"
HEALTH_URL="$BASE_URL/api/offerings/CS201"
LOG_DIR="target/run-logs"
APP_PID=""

usage() { sed -n '3,15p' "$0" | sed 's/^# \{0,1\}//'; }

build() {
    echo "==> Building $JAR"
    # shellcheck disable=SC2086
    mvn -q $MVN_FLAGS -DskipTests package
}

port_in_use() { curl -s -o /dev/null "$BASE_URL" 2>/dev/null; }

# start_app LOG_NAME [extra Spring args...] - starts in the background and waits until ready
start_app() {
    local name=$1; shift
    mkdir -p "$LOG_DIR"
    local log="$LOG_DIR/$name.log"
    echo "==> Starting app ($name) on port $PORT, log: $log"
    java -jar "$JAR" --server.port="$PORT" "$@" > "$log" 2>&1 &
    APP_PID=$!
    local i
    for i in $(seq 1 60); do
        if [ "$(curl -s -o /dev/null -w '%{http_code}' "$HEALTH_URL" 2>/dev/null)" = "200" ]; then
            echo "    ready after ${i}s"
            return 0
        fi
        if ! kill -0 "$APP_PID" 2>/dev/null; then
            echo "!! App exited during startup. Last log lines:"; tail -n 30 "$log"; APP_PID=""; return 1
        fi
        sleep 1
    done
    echo "!! App did not become ready within 60s. Last log lines:"; tail -n 30 "$log"
    return 1
}

stop_app() {
    if [ -n "$APP_PID" ] && kill -0 "$APP_PID" 2>/dev/null; then
        kill "$APP_PID" 2>/dev/null || true
        wait "$APP_PID" 2>/dev/null || true
    fi
    APP_PID=""
}
trap stop_app EXIT INT TERM

run_curl_suites() {
    if port_in_use; then
        echo "!! Something is already listening on port $PORT. Stop it, or use PORT=<other> ./run.sh test"
        exit 1
    fi
    build

    local functional_rc=0 concurrency_rc=0

    start_app functional
    BASE_URL="$BASE_URL" scripts/curl-tests.sh functional || functional_rc=$?
    stop_app

    # Fresh instance with 20 extra eligible learners for the concurrency suite. The bracket form keeps
    # map keys verbatim, and these entries are merged with the learners in application.properties.
    local seed=() i
    for i in $(seq -w 1 20); do seed+=("--waitlist.learners[c$i]=CS101"); done
    start_app concurrency "${seed[@]}"
    BASE_URL="$BASE_URL" CONC_LEARNERS=20 scripts/curl-tests.sh concurrency || concurrency_rc=$?
    stop_app

    echo
    echo "==> curl suites: functional $( [ $functional_rc -eq 0 ] && echo PASSED || echo FAILED )," \
         "concurrency $( [ $concurrency_rc -eq 0 ] && echo PASSED || echo FAILED )"
    [ $functional_rc -eq 0 ] && [ $concurrency_rc -eq 0 ]
}

run_unit() {
    echo "==> Running JUnit tests"
    # shellcheck disable=SC2086
    mvn $MVN_FLAGS test
}

case "${1:-run}" in
    run)
        if port_in_use; then echo "!! Port $PORT is already in use. Try PORT=<other> ./run.sh"; exit 1; fi
        build
        echo "==> Starting app on $BASE_URL (Ctrl+C to stop)"
        echo "    Try: curl -s $HEALTH_URL"
        trap - EXIT INT TERM
        exec java -jar "$JAR" --server.port="$PORT"
        ;;
    test) run_curl_suites ;;
    unit) run_unit ;;
    all)  run_unit && run_curl_suites ;;
    help|-h|--help) usage ;;
    *) usage; exit 2 ;;
esac
