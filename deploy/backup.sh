#!/bin/sh
set -eu
# These paths are fixed by Compose; overrides support isolated script tests only.
backup_dir="${BACKUP_DIRECTORY:-/backups}"
health_dir="${BACKUP_HEALTH_DIRECTORY:-/health}"
extra_dir="${BACKUP_EXTRA_MOUNT:-/extra-backups}"
umask 077
mkdir -p "$backup_dir" "$health_dir"
chmod 755 "$health_dir"
lock_dir="$backup_dir/.backup-lock"
if ! mkdir "$lock_dir"; then
    printf 'Backup lock unavailable; check for an active backup before retrying\n' >&2
    exit 1
fi
lock_owner="$$-$(date -u +%s)"
printf '%s\n' "$lock_owner" > "$lock_dir/owner"
active=""
attempt=$(date -u +%s)
last_success=0
if [ -f "$backup_dir/backup-status.json" ]; then
    previous=$(sed -n 's/.*"lastSuccessAt"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$backup_dir/backup-status.json")
    case "$previous" in ''|*[!0-9]*) ;; *) last_success="$previous" ;; esac
fi
previous_success="$last_success"
filename="orders-$(date -u +%Y%m%dT%H%M%SZ)-$$.sql"
name="$backup_dir/$filename"
checksum=""
extra_verified=false
write_state() {
    state="$1"
    success=0
    [ "$state" != ok ] || success=1
    printf '# TYPE ai_order_backup_last_success_timestamp_seconds gauge\nai_order_backup_last_success_timestamp_seconds %s\n# TYPE ai_order_backup_last_attempt_success gauge\nai_order_backup_last_attempt_success %s\n' "$last_success" "$success" > "$health_dir/backup.prom.$$.tmp"
    chmod 644 "$health_dir/backup.prom.$$.tmp" || return 1
    mv "$health_dir/backup.prom.$$.tmp" "$health_dir/backup.prom" || return 1
    printf '{"schemaVersion":1,"status":"%s","lastAttemptAt":%s,"lastSuccessAt":%s,"backupFile":"%s","sha256":"%s","extraCopyVerified":%s}\n' "$state" "$attempt" "$last_success" "$filename" "$checksum" "$extra_verified" > "$backup_dir/backup-status.json.$$.tmp"
    mv "$backup_dir/backup-status.json.$$.tmp" "$backup_dir/backup-status.json"
}
on_exit() {
    code=$?
    trap - EXIT TERM INT
    # GNU timeout forwards TERM to the worker process group, then escalates to
    # KILL after five seconds. Reap it before deleting partial files or the lock.
    if [ -n "$active" ]; then
        kill -TERM "$active" 2>/dev/null || :
        wait "$active" 2>/dev/null || :
        active=""
    fi
    if [ "$code" -ne 0 ]; then
        last_success="$previous_success"
        write_state failed || printf 'Backup failure status could not be persisted\n' >&2
        printf 'Database backup failed\n' >&2
    fi
    rm -f -- "$name.tmp"
    if [ "$extra_verified" = false ] && [ -f "$extra_dir/$filename.tmp" ]; then rm -f -- "$extra_dir/$filename.tmp"; fi
    if [ -f "$lock_dir/owner" ] && [ "$(cat "$lock_dir/owner")" = "$lock_owner" ]; then
        rm -f -- "$lock_dir/owner"
        rmdir "$lock_dir" || :
    else
        printf 'Backup lock ownership changed; lock preserved\n' >&2
    fi
    exit "$code"
}
trap on_exit EXIT
trap 'exit 143' TERM
trap 'exit 130' INT
run_timed() {
    seconds="$1"
    shift
    "$timeout_command" --kill-after=5 "$seconds" "$@" &
    active=$!
    result=0
    wait "$active" || result=$?
    active=""
    return "$result"
}
# Process-group termination is part of this script's contract. Ubuntu 26.04
# defaults to uutils timeout, whose KILL escalation can leave grandchildren
# alive with inherited pipes. Use a verified GNU implementation, including the
# separately named binary Ubuntu already provides, or fail before starting work.
timeout_command=""
for candidate in timeout gnutimeout; do
    resolved=$(command -v "$candidate" 2>/dev/null || :)
    if [ -n "$resolved" ] && "$resolved" --version 2>/dev/null | grep -q '^timeout (GNU coreutils) '; then
        timeout_command="$resolved"
        break
    fi
done
if [ -z "$timeout_command" ]; then
    printf 'GNU timeout is required for backup process-group supervision\n' >&2
    exit 1
fi
dump_timeout="${BACKUP_DUMP_TIMEOUT_SECONDS:-300}"
copy_timeout="${BACKUP_COPY_TIMEOUT_SECONDS:-120}"
case "$dump_timeout" in ''|*[!0-9]*|?????*) printf 'Invalid backup dump timeout\n' >&2; exit 1 ;; esac
case "$copy_timeout" in ''|*[!0-9]*|?????*) printf 'Invalid backup copy timeout\n' >&2; exit 1 ;; esac
if [ "$dump_timeout" -lt 1 ] || [ "$dump_timeout" -gt 3600 ]; then exit 1; fi
if [ "$copy_timeout" -lt 1 ] || [ "$copy_timeout" -gt 1800 ]; then exit 1; fi
# Environment is injected at runtime. No password is written into dump/status.
export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
# Engine-level container restarts do not reapply Compose depends_on. Wait for
# DNS, TCP, authentication and the selected schema before starting the dump.
ready_wait="${BACKUP_READY_TIMEOUT_SECONDS:-120}"
case "$ready_wait" in ''|*[!0-9]*|????*) printf 'Invalid backup readiness timeout\n' >&2; exit 1 ;; esac
[ "$ready_wait" -ge 1 ] && [ "$ready_wait" -le 120 ]
ready_deadline=$(($(date -u +%s) + ready_wait))
while true; do
    remaining=$((ready_deadline - $(date -u +%s)))
    if [ "$remaining" -le 0 ]; then
        printf 'Database was not ready within the backup readiness deadline\n' >&2
        exit 1
    fi
    probe_timeout=5
    [ "$remaining" -ge 5 ] || probe_timeout="$remaining"
    if run_timed "$probe_timeout" mysql --connect-timeout="$probe_timeout" -h mysql -u root --batch --skip-column-names --execute='SELECT 1' ai_order_assistant >/dev/null 2>&1; then
        break
    fi
    remaining=$((ready_deadline - $(date -u +%s)))
    [ "$remaining" -gt 0 ] || continue
    pause=5
    [ "$remaining" -ge 5 ] || pause="$remaining"
    sleep "$pause"
done
run_timed "$dump_timeout" mysqldump -h mysql -u root --single-transaction --routines --triggers --hex-blob --no-tablespaces --set-gtid-purged=OFF ai_order_assistant > "$name.tmp"
[ -s "$name.tmp" ]
grep -q 'Dump completed' "$name.tmp"
mv "$name.tmp" "$name"
checksum=$(sha256sum "$name" | cut -d ' ' -f 1)
[ "${#checksum}" -eq 64 ]
case "$checksum" in *[!a-fA-F0-9]*) exit 1 ;; esac
printf '%s\n' "$checksum" > "$name.sha256"
retain_verified() {
    # Targets remain within this dedicated directory; preserve unverifiable files.
    ls -1t "$1"/orders-*.sql | tail -n +8 | while IFS= read -r file; do
        if [ -f "$file.sha256" ] && [ "$(sha256sum "$file" | cut -d ' ' -f 1)" = "$(cat "$file.sha256")" ]; then
            rm -- "$file" "$file.sha256"
        else
            printf 'Unverified old backup preserved\n' >&2
        fi
    done
}
# A failing additional destination must not grow primary storage on each retry.
retain_verified "$backup_dir"
if [ "${BACKUP_EXTRA_ENABLED:-}" = true ]; then
    [ "$backup_dir" != "$extra_dir" ]
    # Bound the whole additional-copy stage, including verification and rename.
    run_timed "$copy_timeout" /bin/sh -eu -c '
        # Keep this supervisor alive until timeout kills the entire group. If
        # only the shell exited on TERM, a TERM-resistant cp could outlive it.
        trap "" TERM
        mkdir -p "$2"
        if [ "$1" -ef "$2" ]; then
            printf "Extra backup directory resolves to the primary directory\n" >&2
            exit 1
        fi
        cp "$3" "$2/$4.tmp"
        [ "$(sha256sum "$2/$4.tmp" | cut -d " " -f 1)" = "$5" ]
        mv "$2/$4.tmp" "$2/$4"
        printf "%s\n" "$5" > "$2/$4.sha256"
    ' backup-copy "$backup_dir" "$extra_dir" "$name" "$filename" "$checksum"
    extra_verified=true
fi
if [ "$extra_verified" = true ]; then retain_verified "$extra_dir"; fi
last_success=$(date -u +%s)
write_state ok
printf 'Database backup verified\n'
