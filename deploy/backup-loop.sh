#!/bin/sh
set -eu
# One child at a time, including sleeps. Keep retry state across failed attempts
# instead of restarting the container (which would reset the backoff).
script="${1:-/backup.sh}"
# The override is used by isolated scheduler tests; Compose uses normal sleep.
sleep_command="${BACKUP_SLEEP_COMMAND:-sleep}"
active=""
shutdown() {
    if [ -n "$active" ]; then
        kill -TERM "$active" 2>/dev/null || :
        wait "$active" 2>/dev/null || :
    fi
    exit 0
}
trap shutdown TERM INT
run_wait() {
    "$@" &
    active=$!
    result=0
    wait "$active" || result=$?
    active=""
    return "$result"
}
delay=30
while true; do
    if run_wait /bin/sh "$script"; then
        delay=30
        interval=86400
    else
        interval="$delay"
        case "$delay" in 30) delay=60 ;; 60) delay=120 ;; *) delay=300 ;; esac
        printf 'Backup will retry in %s seconds\n' "$interval" >&2
    fi
    run_wait "$sleep_command" "$interval"
done
