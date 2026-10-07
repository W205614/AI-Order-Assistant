#!/bin/sh
set -eu
# Environment is injected at runtime. No password is written into the dump.
export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
mkdir -p /backups
umask 077
name="/backups/orders-$(date -u +%Y%m%dT%H%M%SZ).sql"
mysqldump -h mysql -u root --single-transaction --routines --triggers --hex-blob --no-tablespaces --set-gtid-purged=OFF ai_order_assistant > "$name.tmp"
[ -s "$name.tmp" ]
grep -q 'Dump completed' "$name.tmp"
mv "$name.tmp" "$name"
sha256sum "$name" | cut -d ' ' -f 1 > "$name.sha256"
# All targets are fixed within this dedicated backup volume.
ls -1t /backups/orders-*.sql | tail -n +8 | while IFS= read -r file; do
    if [ -f "$file.sha256" ] && [ "$(sha256sum "$file" | cut -d ' ' -f 1)" = "$(cat "$file.sha256")" ]; then
        rm -- "$file" "$file.sha256"
    else
        printf 'Unverified old backup preserved\n' >&2
    fi
done
printf 'Database backup verified\n'
