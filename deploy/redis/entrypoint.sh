#!/bin/sh
set -eu
: "${REDIS_GATEWAY_PASSWORD:?Redis gateway credential is required}"
: "${REDIS_AGENT_PASSWORD:?Redis Agent credential is required}"
gateway_hash=$(printf '%s' "$REDIS_GATEWAY_PASSWORD" | sha256sum | cut -d ' ' -f 1)
agent_hash=$(printf '%s' "$REDIS_AGENT_PASSWORD" | sha256sum | cut -d ' ' -f 1)
umask 077
mkdir -p /run/redis
{
    printf 'user default reset off\n'
    printf 'user gateway reset on #%s ~merchantMenu::* ~ai-order-gateway:* +ping +hello +auth +select +info +client|setinfo +client|setname +client|id +get +set +del +exists +eval +evalsha +time +zremrangebyscore +zcard +zadd +pexpire\n' "$gateway_hash"
    printf 'user agent reset on #%s ~ai-order-agent:* +ping +hello +auth +select +client|setinfo +client|setname +get +set +del +expire +incrby +decrby +eval +evalsha +time +zremrangebyscore +zcard +zadd +pexpire +multi +exec +discard +rpush +ltrim +lrange +hget +hset +hgetall\n' "$agent_hash"
} > /run/redis/users.acl
chown -R redis:redis /run/redis
exec /usr/local/bin/docker-entrypoint.sh redis-server --appendonly yes --aclfile /run/redis/users.acl
