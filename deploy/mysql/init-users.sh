#!/bin/bash
: "${DB_MIGRATION_PASSWORD:?DB_MIGRATION_PASSWORD is required}"
application_hex=$(printf '%s' "${DB_APP_PASSWORD:?DB_APP_PASSWORD is required}" | od -An -tx1 | tr -d ' \n')
migration_hex=$(printf '%s' "$DB_MIGRATION_PASSWORD" | od -An -tx1 | tr -d ' \n')
{
    printf 'SET @application_password=CONVERT(0x%s USING utf8mb4);\n' "$application_hex"
    printf 'SET @migration_password=CONVERT(0x%s USING utf8mb4);\n' "$migration_hex"
    cat /usr/local/share/ai-order/provision-users.sql
} | docker_process_sql --database="$MYSQL_DATABASE"
