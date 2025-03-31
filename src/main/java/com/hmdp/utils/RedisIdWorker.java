package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    // 开始时间 2023-9-3-0:00
    private static final long BEGIN_TIMESTAMP = 1693612800L;
    // 序列号的位数
    private static final int COUNT_BITS = 32;
    @Resource
    StringRedisTemplate stringRedisTemplate;

    public long nextID(String keyPrefix){
        LocalDateTime now = LocalDateTime.now();
        // 1.生成时间戳
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = nowSecond - BEGIN_TIMESTAMP;

        // 2.生成序列号
        // 2.1.获取当前日期 精确到天
        String day = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2.自增长
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + day);

        // 3.拼接返回
        // timeStamp向左位移空出序列号的位数
        // 序列号与空出来的0坐或运算 01为1 00为0
        return timeStamp << COUNT_BITS | count;
    }
}
