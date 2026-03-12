package com.hmdp.service.impl;

import com.hmdp.config.RedissonConfig;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 创建一个Lua脚本对象，加载秒杀脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua")); // 获取Lua脚本文件
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private ArrayBlockingQueue<Object> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    private static final ExecutorService SECKILL_ORDER_EXECTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    public void init() {
        SECKILL_ORDER_EXECTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    // 获取队列中的订单信息
                    VoucherOrder order = (VoucherOrder) orderTasks.take();
                    // 创建订单
                    handleVoucherOrder(order);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder order) {
        // 获取用户ID
        Long userId = order.getUserId();

        // 使用Redisson 实现分布式锁
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.debug("获取锁失败");
            return;
        }
        try {
            proxy.createVoucherOrder(order);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 秒杀优惠券
     * @param voucherId
     * @return
     */
    private IVoucherOrderService proxy;
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 从数据库中查询优惠券信息
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //1、判断优惠券是否在秒杀时间内
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("优惠券尚未开始");
        }
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("优惠券已过期");
        }
        // 获取当前用户ID
        Long UserId = UserHolder.getUser().getId();

        // 执行LUA脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), UserId.toString()
        );
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 为0，表示成功，添加订单信息到阻塞队列
        long orderId = redisIdWorker.nextId("order:" + voucherId);

        // 将订单信息保存到阻塞队列，等待异步秒杀
        VoucherOrder voucherOrder = new VoucherOrder().setId(orderId).setUserId(UserId).setVoucherId(voucherId);

        // 创建阻塞队列
        orderTasks.add(voucherOrder);

        // 获取代理对象（事务）
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        // 返回订单ID
        return Result.ok(orderId);
    }

    /**
     * 创建订单(加锁)
     * @param voucherId
     * @return
     */
    @Transactional
    public synchronized Result createVoucherOrder(Long voucherId) {
        //3、下单（扣库存、创建订单）
        Long userId = UserHolder.getUser().getId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("用户已抢购过该优惠券");
        }
        //4、扣减库存
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1") // setSql()
                .eq("voucher_id", voucherId) // where条件
                .gt("stock", 0).update();
        if (!success) {
            return Result.fail("库存不足");
        }

        VoucherOrder order = new VoucherOrder();
        long order_id = redisIdWorker.nextId("order");
        order.setId(order_id);
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        order.setCreateTime(LocalDateTime.now());
        save(order);
        return Result.ok(order.getId());
    }


    /**
     * 创建订单，用于异步处理订单
     * @param order
     */
    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder order) {
        //3、下单（扣库存、创建订单）
        Long userId = order.getUserId();
        int count = query().eq("user_id", userId).eq("voucher_id", order.getVoucherId()).count();
        if (count > 0) {
            log.error("用户已抢购过该优惠券");
            return;
        }
        //4、扣减库存
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1") // setSql()
                .eq("voucher_id", order.getVoucherId()) // where条件
                .gt("stock", 0).update();
        if (!success) {
            log.error("库存不足");
            return;
        }
        save(order);
    }

    // 原版
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 从数据库中查询优惠券信息
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        //1、判断优惠券是否在秒杀时间内
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("优惠券尚未开始");
//        }
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("优惠券已过期");
//        }
//        //2、判断优惠券库存是否充足
//        if (voucher.getStock() < 1) {
//            return Result.fail("优惠券已抢购完!");
//        }
//
//        Long userId = UserHolder.getUser().getId();
//
//        // 使用Redisson 实现分布式锁
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        boolean isLock = lock.tryLock();
//        if (!isLock) {
//            return Result.fail("不允许重复下单");
//        }
//        try {
//            // 获取代理对象（事务）
//            IVoucherOrderService currentProxy = (IVoucherOrderService) AopContext.currentProxy();
//            return currentProxy.createVoucherOrder(voucherId);
//        } finally {
//            lock.unlock();
//        }
//    }
}
