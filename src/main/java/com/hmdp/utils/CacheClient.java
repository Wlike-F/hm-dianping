package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    // 获取锁
    private boolean tryLock(String key){
        return Boolean.TRUE.equals(stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10L, TimeUnit.SECONDS));
    }
    // 释放锁
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

    /**
     * 设置缓存
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 逻辑过期缓存
     * @param key
     * @param value
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }


    /**
     * 缓存穿透
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param unit
     * @return
     * @param <R>
     * @param <ID>
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        // 设置redis key
        String key = keyPrefix + id;
        // 1、从redis中查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2、判断是否存在,存在直接返回
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);// 存在，直接返回
        }
        if (json != null){
            return null;
        }

        // 3、不存在，执行数据库操作
        R r = dbFallback.apply(id);
        // 4、存在，写入redis,设置过期时间，不存在，返回错误信息
        if (r != null) {
            // 存在，写入redis,设置过期时间
            this.set(key, r, time, unit);
            return r;
        }else {
            // 不存在,缓存空对象，设置过期时间，防止缓存穿透
            this.set(key, "", time, unit);
            return null;
        }
    }

    /**
     * 逻辑过期缓存
     * @param keyPreFix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param unit
     * @return
     * @param
     * @param <ID>
     */
    public <R,ID> R queryWithLogicalExpire(String keyPreFix, ID id, Class<R> type, Function<ID,R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPreFix + id;
        // 1、从redis中查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2、判断缓存命中，未命中，返回空
        if (json == null || json.isEmpty()){
            return null;
        }
        // 3、命中了，判断是否缓存过期
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);  // 获取缓存数据
        R r = JSONUtil.toBean((JSONObject) redisData.getData(),type);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())){
            return r; // 未过期，直接返回
        }
        // 过期了，缓存重建，获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        if (tryLock(lockKey)){
            // 获取锁成功，开启独立线程，实现缓存重建
            new Thread(() -> {
                try {
                    // 缓存重建,先查数据库，再插入
                    R r1 = dbFallback.apply(id);
                    this.setWithLogicalExpire(key, r1, time, unit);
                    log.debug("缓存重建成功！");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lockKey);// 释放锁
                }
            }).start();
        }
        // 获取锁失败，返回商铺信息
        return r;
    }
}
