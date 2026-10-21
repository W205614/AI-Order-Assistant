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
    if [ "$code" -ne 0 ]; then
        last_success="$previous_success"
        write_state failed || printf 'Backup failure status could not be persisted\n' >&2
        printf 'Database backup failed\n' >&2
    fi
    rm -f -- "$name.tmp"
    if [ "$extra_verified" = false ] && [ -f "$extra_dir/$filename.tmp" ]; then rm -f -- "$extra_dir/$filename.tmp"; fi
    rmdir "$lock_dir"
    exit "$code"
}
trap on_exit EXIT
trap 'exit 143' TERM
trap 'exit 130' INT
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
    if timeout "$probe_timeout" mysql --connect-timeout="$probe_timeout" -h mysql -u root --batch --skip-column-names --execute='SELECT 1' ai_order_assistant >/dev/null 2>&1; then
        break
    fi
    remaining=$((ready_deadline - $(date -u +%s)))
    [ "$remaining" -gt 0 ] || continue
    pause=5
    [ "$remaining" -ge 5 ] || pause="$remaining"
    sleep "$pause"
done
mysqldump -h mysql -u root --single-transaction --routines --triggers --hex-blob --no-tablespaces --set-gtid-purged=OFF ai_order_assistant > "$name.tmp"
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
    mkdir -p "$extra_dir"
    if [ "$backup_dir" -ef "$extra_dir" ]; then
        printf 'Extra backup directory resolves to the primary directory\n' >&2
        exit 1
    fi
    cp "$name" "$extra_dir/$filename.tmp"
    [ "$(sha256sum "$extra_dir/$filename.tmp" | cut -d ' ' -f 1)" = "$checksum" ]
    mv "$extra_dir/$filename.tmp" "$extra_dir/$filename"
    printf '%s\n' "$checksum" > "$extra_dir/$filename.sha256"
    extra_verified=true
fi
if [ "$extra_verified" = true ]; then retain_verified "$extra_dir"; fi
last_success=$(date -u +%s)
write_state ok
printf 'Database backup verified\n'
