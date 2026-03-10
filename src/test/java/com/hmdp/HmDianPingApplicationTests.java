package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest
@RunWith(SpringRunner.class)
public class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopServiceImpl;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private RedisIdWorker redisIdWorker;

    // 定义一个线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(500);

    @Test
    public void testIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);
        Runnable task = () -> {
            for (int i = 0; i < 1000; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("生成的id: " + id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        System.out.println("开始生成id"+ begin);
        for (int i = 0; i < 300; i++) {
            CACHE_REBUILD_EXECUTOR.submit(task);
        }
        latch.await();
        System.out.println("生成id结束时间："+ (System.currentTimeMillis() - begin));
    }

    @Test
    public void testSaveShop() throws InterruptedException {
        shopServiceImpl.saveShopToRedis(2L, 10L);
    }

    @Test public void testLogicalExpire() throws InterruptedException {
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + 2L, shopServiceImpl.getById(2L), 10L, TimeUnit.SECONDS);
    }


}
