package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    @Resource
    StringRedisTemplate stringRedisTemplate;
    /**
     * 时间戳初始值
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    /**
     * 时间戳位数
     */
    private static final int COUNT_BITS = 32;

    public long nextId(String keyPrefix) {
        //1.生成时间戳 31位秒单位 有初始值
        LocalDateTime now = LocalDateTime.now();
        long nowEpochSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = nowEpochSecond - BEGIN_TIMESTAMP;
        //2.生成序列号
        //2.1 获取日期 精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //自增长 不会出现空指针 这里redis会帮我们自动创建
        long increment = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        //3.拼接并返回
        return timeStamp << COUNT_BITS | increment;
    }
}
