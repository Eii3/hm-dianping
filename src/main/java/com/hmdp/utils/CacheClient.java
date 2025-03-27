package com.hmdp.utils;


import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
@Slf4j
public class CacheClient {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 一个线程池
    // TODO ExecutorService
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);


    public void set(String key, Object value, Long time, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }

    // + 逻辑过期时间
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    // 封装一个基于逻辑过期解决缓存击穿的工具
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit timeUnit) {
        String key = keyPrefix + id;
        // 1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        //isNotBlank: null、""、"\t\n"为false
        if (StrUtil.isBlank(json)) {
            // 3.不存在 直接返回null 热点key会提前存入缓存 如果查询到不存在则不是热点key
            return null;
        }

        // 4.存在 对json反序列化
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);

        // 5.判断缓存是否过期
        LocalDateTime expireTime = redisData.getExpireTime();
        //json反序列化需要根据RedisData字节码文件 RedisData中用于存储shop的属性为Object类 所以实质上是被转化成了JsonObject类
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 5.1.未过期 直接返回商铺信息
            return r;
        }

        // 5.2. 过期
        // 6.缓存重建 尝试获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;

        // 7.判断是否获取锁

        if (tryLock(lockKey)) {
            // 7.1.是 开启独立线程实现缓存重建 利用线程池做 自己创建线程性能不好
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 缓存重建 (因为我的逻辑时间设置得很短 所以走了数据库)
                    // 查询数据库
                    R dbR = dbFallback.apply(id);

                    // 缓存重建
                    this.setWithLogicalExpire(key, dbR, time, timeUnit);

                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    //释放锁 无论缓存重建是否成功都必须要释放锁
                    unlock(lockKey);
                }
            });
        }

        // 7.2.返回商铺信息
        // 否 返回的是过期的商铺信息
        return r;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    // 封装一个解决缓存穿透的工具
    // 返回值类型不确定 使用泛型Class<R> 泛型的推断
    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit timeUnit) {
        String key = keyPrefix + id;

        // 1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2.判断是否存在
        //isNotBlank: null、""、"\t\n"为false
        if (StrUtil.isNotBlank(json)) {
            // 3.存在 直接返回
            return JSONUtil.toBean(json, type);
        }
        // 判断是否命中空值
        if (json != null) {
            // 返回一个错误信息
            return null;
        }

        // 4.不存在 根据id查询数据库
        R r = dbFallback.apply(id);
        // 4.1.判断商铺是否存在
        if (r == null) {
            // 4.2.不存在 返回404
            // 将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 4.3.存在 存入redis
        this.set(key, r, time, timeUnit);

        // 5.返回商铺信息
        return r;
    }
}
