package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private SeckillVoucherServiceImpl seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result seckillVoucher(Long voucherID) {
        // 1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherID);

        // 2.判断是否开始/结束
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀未开始");
        }
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已结束");
        }

        // 3.判断库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }

        Long userID = UserHolder.getUser().getId();

        SimpleRedisLock lock = new SimpleRedisLock("order:" + userID, stringRedisTemplate);

        // 获取锁
        boolean tryLock = lock.tryLock(1200);
        if(!tryLock){
            // 获取失败
            return Result.fail("不允许重复下单");
        }

        // 获取成功
        try{
            // @transactional是利用spring的代理对象实现的，如果直接用this调用目标对象无法实现事务，所以要获取代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherID, userID);
        }finally {
            // 释放锁 （这段代码可能会遇到异常 但无论是否异常 锁都应该被释放）
            lock.unlock();
        }


        // 这个办法如果有两个进程则会有两把锁 那么就不能实现一人一单
        /*synchronized (userID.toString().intern()) {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherID, userID);
        }*/
    }

    @Transactional
    public Result createVoucherOrder(Long voucherID, Long userID) {
        // 4.一人一单
        int count = query().eq("user_id", userID).eq("voucher_id", voucherID).count();
        if (count > 0) {
            // 该用户至少已购一单
            return Result.fail("同一用户不可重复下单");
        }

        // 5.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherID)
                .gt("stock", 0) // 防止库存超卖
                .update();
        if (!success) {
            return Result.fail("库存不足");
        }

        // 6.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 6.1.订单id
        long orderID = redisIdWorker.nextID("voucher:order");
        voucherOrder.setId(orderID);

        // 6.2.用户id
        voucherOrder.setUserId(userID);

        // 6.3.代金券id
        voucherOrder.setVoucherId(voucherID);

        // 6.4.保存
        save(voucherOrder);

        // 7.返回订单id
        return Result.ok(orderID);
    }
}
