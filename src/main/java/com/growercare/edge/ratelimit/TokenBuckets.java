package com.growercare.edge.ratelimit;

import com.growercare.edge.config.EdgeProperties.Bucket;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * SPEC-EDGE-01 D5: token buckets in Redis, updated atomically by one Lua script per request (Redis' own clock, so all
 * gateway pods agree). Keys {@code rl:<scope>:<id>} expire once a bucket would be full again. Redis is never a system
 * of record: a lost key only means a fresh, full bucket.
 */
@Component
public class TokenBuckets {

    /** The outcome of taking one token. */
    public record Decision(boolean allowed, long retryAfterSeconds) { }

    private static final RedisScript<List> TAKE = RedisScript.of("""
            local capacity = tonumber(ARGV[1])
            local per_ms = tonumber(ARGV[2])
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(state[1])
            local ts = tonumber(state[2])
            if tokens == nil or ts == nil then
              tokens = capacity
              ts = now
            end
            tokens = math.min(capacity, tokens + math.max(0, now - ts) * per_ms)
            local allowed = 0
            local wait_ms = 0
            if tokens >= 1 then
              tokens = tokens - 1
              allowed = 1
            else
              wait_ms = math.ceil((1 - tokens) / per_ms)
            end
            redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
            redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / per_ms) + 1000)
            return {allowed, wait_ms}
            """, List.class);

    private final StringRedisTemplate redis;

    public TokenBuckets(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Takes one token from {@code rl:<scope>:<id>}; Redis errors propagate (the caller decides open or closed). */
    public Decision take(String scope, String id, Bucket bucket) {
        @SuppressWarnings("unchecked")
        List<Long> result = redis.execute(TAKE, List.of("rl:" + scope + ":" + id),
                Integer.toString(bucket.capacity()), Double.toString(bucket.refillPerMilli()));
        boolean allowed = result != null && result.get(0) == 1L;
        long waitMs = result == null ? 1000 : result.get(1);
        return new Decision(allowed, Math.max(1, (waitMs + 999) / 1000));
    }
}
