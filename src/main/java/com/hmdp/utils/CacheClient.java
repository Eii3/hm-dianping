package com.hmdp.utils;


import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
@Slf4j
public class CacheClient {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

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
