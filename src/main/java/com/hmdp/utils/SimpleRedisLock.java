package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

@Slf4j
public class SimpleRedisLock implements ILock{
    private String name; // 锁的名称
    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-"; // 生成一个全局唯一的前缀，确保不同实例之间的锁不会冲突
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取线程唯一标识
        String threadId = ID_PREFIX + Thread.currentThread().getId() ;
        // 获取锁
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(flag); // 避免自动拆箱导致的NullPointerException
    }

    @Override
    public void unlock() {
        // 获取线程唯一标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁id
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        // 判断是否是当前线程持有锁
        if (threadId.equals(id)) {
            // 释放锁
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }
}
