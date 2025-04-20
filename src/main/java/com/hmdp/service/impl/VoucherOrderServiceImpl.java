package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
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
        synchronized (userID.toString().intern()) {
            // @transactional是利用spring的代理对象实现的，如果直接用this调用目标对象无法实现事务，所以要获取代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherID, userID);
        }
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
