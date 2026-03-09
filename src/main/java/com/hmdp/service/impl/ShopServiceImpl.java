package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    private final StringRedisTemplate redisTemplate;
    private final CacheClient cacheClient;

    public ShopServiceImpl(StringRedisTemplate redisTemplate, CacheClient cacheClient) {
        this.redisTemplate = redisTemplate;
        this.cacheClient = cacheClient;
    }

    // 获取锁
    private boolean tryLock(String key){
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, "1", 10L, TimeUnit.SECONDS));
    }
    // 释放锁
    private void unLock(String key){
        redisTemplate.delete(key);
    }

    @Override
    public Result queryById(Long id) {
        // 缓存穿透
//        // 普通写法
//        Shop shop = queryWithPassThrough(id);
//        if (shop != null){
//            return Result.ok(shop);
//        }
        // 使用工具类
//        Shop shop = cacheClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        if (shop != null){
//            return Result.ok(shop);
//        }


        // 缓存击穿
//        Shop shop_1 = queryWithMutex_v1(id);
//        if (shop_1 != null){
//            return Result.ok(shop_1);
//        }
//
//        // 逻辑过期
//        Shop withLogicalExpire = queryWithLogicalExpire(id);
//        if (withLogicalExpire != null){
//            return Result.ok(withLogicalExpire);
//        }
        //使用工具类进行逻辑过期查询
        Shop shop_2 = cacheClient.
                queryWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY,
                        id, Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.SECONDS);
        if (shop_2 != null){
            return Result.ok(shop_2);
        }

        // 都没有，返回错误信息
        return Result.fail("店铺不存在");

    }

    // hm，未用
    private Shop queryWithMutex(Long id) {
        // 1、从redis中查询商铺缓存
        String shopJson = redisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        // 2、判断是否存在,存在直接返回
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);// 存在，直接返回
        }
        if (shopJson != null){
            return null;
        }
        // 未命中，获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        if (tryLock(lockKey)){
            try {
                // 获取锁成功，根据id查询数据库
                Shop shop = getById(id);
                // 存在，写入redis,设置过期时间
                if (shop != null) {
                    redisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
                    unLock(lockKey); // 释放锁
                    return shop;
                }else {
                    // 不存在,缓存空对象，设置过期时间，防止缓存穿透
                    redisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                    unLock(lockKey);// 释放锁
                    return null;
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }finally {
                unLock(lockKey); // 释放锁
            }
        }else {
            // 获取锁失败，休眠并重试
            try {
                Thread.sleep(50);
                queryWithMutex(id); // 递归重试
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }finally {
                unLock(lockKey); // 释放锁
            }
            return queryWithMutex(id);
        }
    }

    // v1，未用
    private Shop queryWithMutex_v1(Long id) {
        // 1、从 redis 中查询商铺缓存
        String shopJson = redisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        // 2、判断是否存在，存在直接返回
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if (shopJson != null){
            return null;
        }

        // 未命中，获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        int retryCount = 0;

        while (retryCount < 100) {
            if (tryLock(lockKey)) {
                try {
                    // 获取锁成功，再次检查缓存（双重检查）
                    shopJson = redisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
                    if (StrUtil.isNotBlank(shopJson)) {
                        return JSONUtil.toBean(shopJson, Shop.class);
                    }

                    // 根据 id 查询数据库
                    Shop shop = getById(id);

                    if (shop != null) {
                        redisTemplate.opsForValue().set(
                                RedisConstants.CACHE_SHOP_KEY + id,
                                JSONUtil.toJsonStr(shop),
                                RedisConstants.CACHE_SHOP_TTL,
                                TimeUnit.MINUTES
                        );
                        return shop;
                    } else {
                        redisTemplate.opsForValue().set(
                                RedisConstants.CACHE_SHOP_KEY + id,
                                "",
                                RedisConstants.CACHE_NULL_TTL,
                                TimeUnit.MINUTES
                        );
                        return null;
                    }
                } catch (Exception e) {
                    throw new RuntimeException("查询店铺异常", e);
                } finally {
                    unLock(lockKey);
                }
            }

            retryCount++;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("等待锁被中断", e);
            }
        }

        throw new RuntimeException("获取锁失败，请稍后重试");
    }

    // 互斥锁查询商铺信息，未用
    public Shop queryWithPassThrough(Long id) {
        // 1、从redis中查询商铺缓存
        String shopJson = redisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        // 2、判断是否存在,存在直接返回
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);// 存在，直接返回
        }
        if (shopJson != null){
            return null;
        }
        // 3、不存在，根据id查询数据库
        Shop shop = getById(id);
        // 4、存在，写入redis,设置过期时间，不存在，返回错误信息
        if (shop != null) {
            // 存在，写入redis,设置过期时间
            redisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
            return shop;
        }else {
            // 不存在,缓存空对象，设置过期时间，防止缓存穿透
            redisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
    }

    /**
     * 把商铺信息保存到redis中
     * @param id
     * @param expireSeconds
     */
    public void saveShopToRedis(Long id,Long expireSeconds) throws InterruptedException {
        // 1、查询店铺信息
        Shop shop = getById(id);
        Thread.sleep(200);// 模拟重建的耗时
        // 2、封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 3、写入redis
        redisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
        log.debug("写入redis成功！");
    }

    /**
     *逻辑过期，查询商铺信息，未用
     * @param id
     * @return
     */
    public Shop queryWithLogicalExpire(Long id) {
        // 1、从redis中查询商铺缓存
        String shopJson = redisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        // 2、判断缓存命中，未命中，返回空
        if (shopJson == null || shopJson.isEmpty()){
            return null;
        }
        // 3、命中了，判断是否缓存过期
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);  // 获取缓存数据
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())){
            return shop; // 未过期，直接返回
        }
        // 过期了，缓存重建，获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        if (tryLock(lockKey)){
            // 获取锁成功，开启独立线程，实现缓存重建
            new Thread(() -> {
                try {
                    // 缓存重建
                    this.saveShopToRedis(id, 20L);
                    log.debug("缓存重建成功！");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lockKey);// 释放锁
                }
            }).start();
        }
        // 获取锁失败，返回商铺信息
        return shop;

    }


    // 更新商铺信息
    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        Long id = shop.getId();
        // 判断id是否为空
        if (id == null){
            return Result.fail("店铺id不能为空！");
        }
        // 1、更新数据库
        updateById(shop);
        // 2、删除缓存
        redisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
