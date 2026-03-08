package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import io.netty.util.internal.StringUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public ShopServiceImpl(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
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
        Shop shop = queryWithPassThrough(id);
//        return Result.ok(shop);

        // 缓存击穿
        Shop shop_1 = queryWithMutex_v1(id);
        if (shop_1 == null){
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop_1);
    }

    // hm
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

    // v1
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

    // 互斥锁查询商铺信息
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
