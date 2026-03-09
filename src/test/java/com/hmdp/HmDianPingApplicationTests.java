package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest
@RunWith(SpringRunner.class)
public class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopServiceImpl;

    @Resource
    private CacheClient cacheClient;

    @Test
    public void testSaveShop() throws InterruptedException {
        shopServiceImpl.saveShopToRedis(2L, 10L);
    }

    @Test public void testLogicalExpire() throws InterruptedException {
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + 2L, shopServiceImpl.getById(2L), 10L, TimeUnit.SECONDS);
    }


}
