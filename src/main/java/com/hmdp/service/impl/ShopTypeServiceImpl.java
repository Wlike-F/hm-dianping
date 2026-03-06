package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
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
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    // 注入redisTemplate
    private final StringRedisTemplate redisTemplate;
    public ShopTypeServiceImpl(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }


    // 首页查询商铺类型
    @Override
    public Result queryTypeList() {
        // 1. 从redis查询商铺类型列表
        String shopTypeList = redisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_TYPE_KEY);
        if (StrUtil.isNotBlank(shopTypeList)) {
            // 2、存在，直接返回
            return Result.ok(JSONUtil.toBean(shopTypeList, Object.class));
        }
        // 3. 不存在，查询数据库
        List<ShopType> shopTypeList_db = this.query().orderByAsc("sort").list();
        if (shopTypeList_db != null) {
            // 4.存在，写入redis，返回
            redisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_TYPE_KEY, JSONUtil.toJsonStr(shopTypeList_db), RedisConstants.CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
        }
        // 5. 不存在，返回错误信息
        return Result.fail("查询商铺类型列表失败！");
    }
}
