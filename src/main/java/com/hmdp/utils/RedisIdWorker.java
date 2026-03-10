package com.hmdp.utils;


import cn.hutool.core.lang.Snowflake;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    private static final Long BEGIN_TIMESTAMP = 1767225600L; // 2026-01-01 00:00:00 的时间戳，单位为秒
    private static final int COUNT_BITS = 32;// 序列号位数32位

    // 注入RedisTemplate
    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }


    public long nextId(String keyPrefix) {
        // 生成时间戳
        long timestamp = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
        timestamp -= BEGIN_TIMESTAMP; // 减去开始时间戳，得到时间戳

        // 生成序列号
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));// 获取当前日期，精确到天
        long sequence = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date); // 生成序列号，自增
        // 拼接并返回
        return  timestamp <<  COUNT_BITS | sequence; // 返回结果，时间戳占32位，序列号占32位
    }


//    public static void main(String[] args) {
//        LocalDateTime time = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
//        long timeEpochSecond = time.toEpochSecond(ZoneOffset.UTC);
//        System.out.println(timeEpochSecond);
//
//        long timestamp = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC); //  获取当前时间戳,单位为秒
//        System.out.println(timestamp);
//    }
}
