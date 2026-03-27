package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

@Component
@Slf4j
public class TwoLevelCacheClient {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private Cache<String, String> localCache;

    // 缓存重建线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long redisTtl, TimeUnit redisUnit) {
        String key = keyPrefix + id;

        String localJson = localCache.getIfPresent(key);
        if (StrUtil.isNotBlank(localJson)) {
            return JSONUtil.toBean(localJson, type);
        }
        if (localJson != null) {
            return null;
        }

        String redisJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(redisJson)) {
            localCache.put(key, redisJson);
            return JSONUtil.toBean(redisJson, type);
        }
        if (redisJson != null) {
            localCache.put(key, "");
            return null;
        }

        R r = dbFallback.apply(id);
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            localCache.put(key, "");
            return null;
        }

        String value = JSONUtil.toJsonStr(r);
        stringRedisTemplate.opsForValue().set(key, value, redisTtl, redisUnit);
        localCache.put(key, value);
        return r;
    }

    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit timeUnit) {
        String key = keyPrefix + id;

        // 1.优先查 L1（Caffeine）
        String json = localCache.getIfPresent(key);
        if (StrUtil.isNotBlank(json)) {
            return handleLogicalExpireJson(key, json, type, dbFallback, time, timeUnit, id);
        }
        if (json != null) {
            // L1 命中了空值（""）-> 说明逻辑上就是不存在
            return null;
        }

        // 2. L1 miss -> 查 L2（Redis）
        String redisJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(redisJson)) {
            return null;
        }

        // 3.回填 L1
        localCache.put(key, redisJson);
        return handleLogicalExpireJson(key, redisJson, type, dbFallback, time, timeUnit, id);
    }

    private <R, ID> R handleLogicalExpireJson(
            String key, String json, Class<R> type,
            Function<ID, R> dbFallback, Long time, TimeUnit timeUnit, ID id) {
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 反序列化时需要拿 data 对象
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);

        if (expireTime != null && expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }

        // 过期：异步重建（类似 CacheClient 的逻辑）
        String lockKey = LOCK_SHOP_KEY + id;
        if (tryLock(lockKey)) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R dbR = dbFallback.apply(id);
                    this.setWithLogicalExpire(key, dbR, time, timeUnit);
                } catch (Exception e) {
                    log.error("缓存重建异常", e);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        return r;
    }

    public void invalidate(String key) {
        localCache.invalidate(key);
        stringRedisTemplate.delete(key);
    }

    public void set(String key, Object value, Long redisTtl, TimeUnit redisUnit) {
        String json = JSONUtil.toJsonStr(value);
        stringRedisTemplate.opsForValue().set(key, json, redisTtl, redisUnit);
        localCache.put(key, json);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));

        String json = JSONUtil.toJsonStr(redisData);
        // 注意：逻辑过期时间在 json 内部，不使用 redis TTL
        stringRedisTemplate.opsForValue().set(key, json);
        localCache.put(key, json);
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
