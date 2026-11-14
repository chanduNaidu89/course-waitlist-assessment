#!/usr/bin/env bash
#
# curl scenario tests for the course-waitlist API.
#
# Each suite needs a FRESHLY STARTED application, because state is in memory and the scenarios
# build on each other. ./run.sh test does that for you; to run a suite by hand:
#
#   ./run.sh                               # terminal 1: start the app
#   scripts/curl-tests.sh functional       # terminal 2
#
# The concurrency suite needs extra seeded learners c01..c20 (./run.sh test supplies them).
#
# Environment: BASE_URL (default http://localhost:8080), OFFERING (default CS201).
# Requires only bash and curl (no jq). Works with macOS's bash 3.2.

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
OFFERING="${OFFERING:-CS201}"
API="$BASE_URL/api/offerings/$OFFERING"
CONC_LEARNERS="${CONC_LEARNERS:-20}"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

PASS=0
FAIL=0
STATUS=""
BODY=""
HEADERS=""

if [ -t 1 ]; then GREEN=$'\033[32m'; RED=$'\033[31m'; BOLD=$'\033[1m'; RESET=$'\033[0m'
else GREEN=""; RED=""; BOLD=""; RESET=""; fi

# ---------------------------------------------------------------------------------------------
# HTTP helpers. Each request stores the result in STATUS, BODY and HEADERS.
# ---------------------------------------------------------------------------------------------

NO_BODY="__NO_BODY__"

# request METHOD URL [BODY|__NO_BODY__] [CONTENT_TYPE] [ACCEPT]
request() {
    local method=$1 url=$2 data=${3:-$NO_BODY} ctype=${4:-application/json} accept=${5:-}
    local args=(-s -X "$method" -o "$TMP_DIR/body" -D "$TMP_DIR/headers" -w '%{http_code}')
    if [ "$data" != "$NO_BODY" ]; then
        args+=(-H "Content-Type: $ctype" --data-binary "$data")
    elif [ "$method" = "POST" ]; then
        args+=(-H "Content-Type: $ctype")
    fi
    if [ -n "$accept" ]; then
        args+=(-H "Accept: $accept")
    fi
    STATUS="$(curl "${args[@]}" "$url")" || STATUS="000"
    BODY="$(cat "$TMP_DIR/body" 2>/dev/null)"
    HEADERS="$(cat "$TMP_DIR/headers" 2>/dev/null)"
}

enroll() { request POST "$API/enrollments" "{\"learnerId\":\"$1\"}"; }
drop()   { request DELETE "$API/enrollments/$1"; }
state()  { request GET "$API"; }

# json_array NAME -> the raw contents of "NAME":[...] from BODY, e.g. "alice","bob"
json_array() {
    printf '%s' "$BODY" | sed -n -E "s/.*\"$1\":\[([^]]*)\].*/\1/p"
}

# ---------------------------------------------------------------------------------------------
# Assertions
# ---------------------------------------------------------------------------------------------

scenario() { printf '\n%s== %s%s\n' "$BOLD" "$1" "$RESET"; }

pass() { PASS=$((PASS + 1)); printf '  %sPASS%s %s\n' "$GREEN" "$RESET" "$1"; }

fail() {
    FAIL=$((FAIL + 1))
    printf '  %sFAIL%s %s\n' "$RED" "$RESET" "$1"
    printf '       status: %s\n       body:   %s\n' "$STATUS" "$BODY"
}

expect_status() {
    if [ "$STATUS" = "$1" ]; then pass "HTTP $1${2:+ - $2}"; else fail "expected HTTP $1, got $STATUS${2:+ - $2}"; fi
}

expect_outcome() {
    if printf '%s' "$BODY" | grep -q "\"outcome\":\"$1\""; then pass "outcome $1"; else fail "expected outcome $1"; fi
}

# expect STATUS OUTCOME: the usual pair of checks.
expect() { expect_status "$1"; expect_outcome "$2"; }

expect_body_contains() {
    if printf '%s' "$BODY" | grep -qF -- "$1"; then pass "body contains $1"; else fail "body should contain $1"; fi
}

expect_body_lacks() {
    if printf '%s' "$BODY" | grep -qF -- "$1"; then fail "body should not contain $1"; else pass "body has no $1"; fi
}

expect_body_empty() {
    if [ -z "$BODY" ]; then pass "empty body"; else fail "expected an empty body"; fi
}

# expect_header NAME SUBSTRING (case-insensitive header name)
expect_header() {
    if printf '%s' "$HEADERS" | grep -i "^$1:" | grep -qF -- "$2"; then
        pass "header $1 contains $2"
    else
        fail "header $1 should contain $2 (headers: $(printf '%s' "$HEADERS" | tr -d '\r' | tr '\n' ' '))"
    fi
}

# expect_list NAME EXPECTED -> compares the raw array contents, e.g. '"alice","bob"' or ''
expect_list() {
    local actual
    actual="$(json_array "$1")"
    if [ "$actual" = "$2" ]; then pass "$1 = [$2]"; else fail "expected $1 = [$2], got [$actual]"; fi
}

expect_seats() { expect_body_contains "\"seatsAvailable\":$1"; }

# expect_state ENROLLED WAITLIST SEATS: one GET, three checks.
expect_state() {
    state
    expect_list enrolled "$1"
    expect_list waitlist "$2"
    expect_seats "$3"
}

# ---------------------------------------------------------------------------------------------
# Suite: functional. Expects the seeded config: CS201, capacity 2, prerequisite CS101;
# alice, bob, carol, dave eligible; erin known but missing CS101.
# ---------------------------------------------------------------------------------------------

suite_functional() {
    scenario "0. Initial state: empty offering"
    state
    expect_status 200
    expect_body_contains '"capacity":2'
    expect_body_contains '"prerequisites":["CS101"]'
    expect_list enrolled ''
    expect_list waitlist ''
    expect_seats 2

    scenario "1. Invalid requests are rejected and change nothing (AC 5)"
    enroll "";                                  expect 400 INVALID_REQUEST
    request POST "$API/enrollments" '{}';       expect 400 INVALID_REQUEST
    request POST "$API/enrollments";            expect 400 INVALID_REQUEST   # no body at all
    request POST "$API/enrollments" '{not json'; expect 400 INVALID_REQUEST
    enroll " alice";                            expect 400 INVALID_REQUEST   # surrounding whitespace
    enroll "$(printf 'x%.0s' $(seq 1 65))";     expect 400 INVALID_REQUEST   # longer than 64 chars
    drop "%20alice";                            expect 400 INVALID_REQUEST   # encoded whitespace id
    expect_state '' '' 2

    scenario "2. Protocol errors keep the standard body and required headers"
    request POST "$API/enrollments" 'alice' 'text/plain'
    expect 415 INVALID_REQUEST
    expect_header Accept application/json
    request PUT "$API/enrollments/alice"
    expect 405 INVALID_REQUEST
    expect_header Allow DELETE
    request GET "$API" "$NO_BODY" application/json application/xml
    expect_status 406 "client accepts only XML"
    expect_body_empty

    scenario "3. Unknown offering -> 404 OFFERING_NOT_FOUND"
    request GET "$BASE_URL/api/offerings/NOPE";                                  expect 404 OFFERING_NOT_FOUND
    request POST "$BASE_URL/api/offerings/NOPE/enrollments" '{"learnerId":"alice"}'; expect 404 OFFERING_NOT_FOUND
    request DELETE "$BASE_URL/api/offerings/NOPE/enrollments/alice";             expect 404 OFFERING_NOT_FOUND

    scenario "4. Unknown learner -> 404 LEARNER_NOT_FOUND"
    enroll mallory
    expect 404 LEARNER_NOT_FOUND
    expect_state '' '' 2

    scenario "5. Prerequisite failure with seats available -> 422, not enrolled (AC 1, 4)"
    enroll erin
    expect 422 PREREQUISITE_NOT_MET
    expect_body_contains '[CS101]'
    expect_state '' '' 2

    scenario "6. Enrollment succeeds while capacity remains (AC 1)"
    enroll alice
    expect 201 ENROLLED
    expect_state '"alice"' '' 1

    scenario "7. Duplicate enroll of an enrolled learner is a no-op (AC 2)"
    enroll alice
    expect 200 ALREADY_ENROLLED
    expect_state '"alice"' '' 1

    scenario "8. Last seat taken -> offering full"
    enroll bob
    expect 201 ENROLLED
    expect_state '"alice","bob"' '' 0

    scenario "9. Ineligible learner when full is NOT waitlisted (AC 4)"
    enroll erin
    expect 422 PREREQUISITE_NOT_MET
    expect_state '"alice","bob"' '' 0

    scenario "10. Full: eligible learners join the waitlist in arrival order (AC 2)"
    enroll carol
    expect 202 WAITLISTED
    expect_body_contains '"waitlistPosition":1'
    enroll dave
    expect 202 WAITLISTED
    expect_body_contains '"waitlistPosition":2'
    expect_state '"alice","bob"' '"carol","dave"' 0

    scenario "11. Duplicate enroll of a waitlisted learner keeps the position (AC 2)"
    enroll carol
    expect 200 ALREADY_WAITLISTED
    expect_body_contains '"waitlistPosition":1'
    expect_state '"alice","bob"' '"carol","dave"' 0

    scenario "12. Drop promotes the earliest waitlisted learner - FIFO (AC 3)"
    drop alice
    expect 200 DROPPED
    expect_body_contains '"promotedLearnerId":"carol"'
    expect_body_contains '"removedIneligible":[]'
    expect_state '"bob","carol"' '"dave"' 0

    scenario "13. Repeated drop is a no-op and promotes nobody (AC 5)"
    drop alice
    expect 404 NOT_ENROLLED
    expect_state '"bob","carol"' '"dave"' 0

    scenario "14. Drop of a learner who was never in the offering is a no-op (AC 5)"
    drop erin
    expect 404 NOT_ENROLLED
    expect_state '"bob","carol"' '"dave"' 0

    scenario "15. A waitlisted learner can leave the waitlist; seats unchanged"
    drop dave
    expect 200 LEFT_WAITLIST
    expect_state '"bob","carol"' '' 0

    scenario "16. A learner who dropped can re-enroll, joining at the back"
    enroll alice
    expect 202 WAITLISTED
    expect_body_contains '"waitlistPosition":1'
    enroll dave
    expect 202 WAITLISTED
    expect_body_contains '"waitlistPosition":2'
    expect_state '"bob","carol"' '"alice","dave"' 0

    scenario "17. Successive drops keep promoting in FIFO order (AC 3)"
    drop bob
    expect 200 DROPPED
    expect_body_contains '"promotedLearnerId":"alice"'
    drop carol
    expect 200 DROPPED
    expect_body_contains '"promotedLearnerId":"dave"'
    expect_state '"alice","dave"' '' 0

    scenario "18. Drop with an empty waitlist leaves the seat open"
    drop alice
    expect 200 DROPPED
    expect_body_lacks '"promotedLearnerId"'
    expect_state '"dave"' '' 1

    scenario "19. The open seat is immediately available to the next eligible learner"
    enroll bob
    expect 201 ENROLLED
    expect_state '"dave","bob"' '' 0

    printf '\n  Note: the skip-and-remove policy for a learner who becomes ineligible WHILE waiting\n'
    printf '  needs a learner-record change, and the API deliberately has no endpoint for that.\n'
    printf '  It is covered by EnrollmentServiceTest.PrerequisiteFailure, the concurrency test,\n'
    printf '  and EnrollmentControllerTest.skipPolicyIsVisibleOverHttp (run: mvn test).\n'
}

# ---------------------------------------------------------------------------------------------
# Suite: concurrency. Expects capacity 2 plus eligible learners c01..c$CONC_LEARNERS.
# ---------------------------------------------------------------------------------------------

# Background request: writes "<status> <body>" to a file.
enroll_bg() { curl -s -o "$2.body" -w '%{http_code}' -X POST "$API/enrollments" \
    -H 'Content-Type: application/json' --data-binary "{\"learnerId\":\"$1\"}" > "$2.status"; }
drop_bg() { curl -s -o "$2.body" -w '%{http_code}' -X DELETE "$API/enrollments/$1" > "$2.status"; }

# count_outcome DIR OUTCOME -> how many response bodies in DIR have that outcome
count_outcome() { cat "$1"/*.body 2>/dev/null | grep -o "\"outcome\":\"$2\"" | wc -l | tr -d ' '; }

expect_count() {
    if [ "$2" = "$3" ]; then pass "$1 = $3"; else fail "expected $1 = $3, got $2"; fi
}

# list_items NAME -> one id per line from the current BODY's array
list_items() { { json_array "$1"; echo; } | tr ',' '\n' | tr -d '"' | sed '/^$/d'; }   # echo: count the last item too

suite_concurrency() {
    local n=$CONC_LEARNERS capacity=2 i id dir
    local ids=()
    for i in $(seq -w 1 "$n"); do ids+=("c$i"); done

    scenario "C0. Precondition: fresh offering with learners c01..c$n seeded"
    state
    expect_status 200
    expect_body_contains "\"capacity\":$capacity"
    expect_list enrolled ''

    scenario "C1. $n learners enroll at once, each sending a duplicate concurrently"
    dir="$TMP_DIR/enroll"; mkdir -p "$dir"
    for id in "${ids[@]}"; do
        enroll_bg "$id" "$dir/$id.a" &
        enroll_bg "$id" "$dir/$id.b" &
    done
    wait
    expect_count "ENROLLED responses" "$(count_outcome "$dir" ENROLLED)" "$capacity"
    expect_count "WAITLISTED responses" "$(count_outcome "$dir" WAITLISTED)" "$((n - capacity))"
    expect_count "ALREADY_* responses (duplicates)" \
        "$(( $(count_outcome "$dir" ALREADY_ENROLLED) + $(count_outcome "$dir" ALREADY_WAITLISTED) ))" "$n"
    local positions expected_positions
    positions="$(grep -l '"outcome":"WAITLISTED"' "$dir"/*.body | xargs cat \
        | grep -o '"waitlistPosition":[0-9]*' | cut -d: -f2 | sort -n | tr '\n' ' ')"
    expected_positions="$(seq 1 $((n - capacity)) | tr '\n' ' ')"
    if [ "$positions" = "$expected_positions" ]; then pass "waitlist positions are unique and contiguous 1..$((n - capacity))"
    else fail "waitlist positions not 1..$((n - capacity)): $positions"; fi

    state
    local enrolled_count waitlist_count dupes
    enrolled_count="$(list_items enrolled | wc -l | tr -d ' ')"
    waitlist_count="$(list_items waitlist | wc -l | tr -d ' ')"
    dupes="$( (list_items enrolled; list_items waitlist) | sort | uniq -d | tr '\n' ' ')"
    expect_count "enrolled size" "$enrolled_count" "$capacity"
    expect_count "waitlist size" "$waitlist_count" "$((n - capacity))"
    if [ -z "$dupes" ]; then pass "no learner appears twice across enrolled + waitlist"; else fail "duplicates: $dupes"; fi
    expect_seats 0

    scenario "C2. Every enrolled learner drops twice at once: one promotion per seat"
    local first_enrolled expected_promoted
    first_enrolled="$(list_items enrolled)"
    expected_promoted="$(list_items waitlist | head -n "$capacity" | sort | tr '\n' ' ')"
    dir="$TMP_DIR/drop"; mkdir -p "$dir"
    for id in $first_enrolled; do
        drop_bg "$id" "$dir/$id.a" &
        drop_bg "$id" "$dir/$id.b" &
    done
    wait
    expect_count "DROPPED responses" "$(count_outcome "$dir" DROPPED)" "$capacity"
    expect_count "NOT_ENROLLED responses (repeated drops)" "$(count_outcome "$dir" NOT_ENROLLED)" "$capacity"
    local promoted
    promoted="$(cat "$dir"/*.body | grep -o '"promotedLearnerId":"[^"]*"' | cut -d'"' -f4 | sort | tr '\n' ' ')"
    if [ "$promoted" = "$expected_promoted" ]; then pass "promoted exactly the first $capacity waiters: $promoted"
    else fail "expected promoted [$expected_promoted], got [$promoted]"; fi

    state
    expect_count "enrolled size" "$(list_items enrolled | wc -l | tr -d ' ')" "$capacity"
    expect_count "waitlist size" "$(list_items waitlist | wc -l | tr -d ' ')" "$((n - 2 * capacity))"
    if (list_items enrolled; printf '%s\n' $first_enrolled) | sort | uniq -d | grep -q .; then
        fail "a dropped learner is still enrolled"
    else
        pass "dropped learners no longer hold seats"
    fi
}

# ---------------------------------------------------------------------------------------------

main() {
    local suite=${1:-functional}
    if ! curl -s -o /dev/null "$API"; then
        printf '%sCannot reach %s - start the app first (./run.sh).%s\n' "$RED" "$API" "$RESET"
        exit 2
    fi
    printf '%sRunning "%s" suite against %s%s\n' "$BOLD" "$suite" "$API" "$RESET"
    case "$suite" in
        functional)  suite_functional ;;
        concurrency) suite_concurrency ;;
        *) echo "usage: $0 [functional|concurrency]"; exit 2 ;;
    esac
    printf '\n%s%s suite: %d passed, %d failed%s\n' "$BOLD" "$suite" "$PASS" "$FAIL" "$RESET"
    [ "$FAIL" -eq 0 ]
}

main "$@"
