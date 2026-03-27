package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import cn.hutool.json.JSONUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Collections;

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
    private static final String ORDER_TOPIC = "voucher-order-topic";
    @Resource
    private SeckillVoucherServiceImpl seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private KafkaTemplate<String, String> kafkaTemplate;

    // Lua脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 完成库存扣减和订单生成
    @KafkaListener(topics = ORDER_TOPIC, groupId = "voucher-order-group")
    @Transactional
    public void handleVoucherOrder(String msg) {
        VoucherOrder voucherOrder = JSONUtil.toBean(msg, VoucherOrder.class);
        // 在Redis已经做了库存是否充足和一人一单的校验,能够到这里说明用户已经秒杀成功了,所以这里其实不需要加锁
        // 1.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock -1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if(!success){
            // 扣减库存失败
            log.error("库存不足");
            return;
        }
        // 2.创建订单
        save(voucherOrder);
    }

    @Override
    public Result seckillVoucher(Long voucherID) {
        Long userID = UserHolder.getUser().getId();

        // 1.执行lua脚本,判断是否有资格下单
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherID.toString(),
                userID.toString()
        );
        if(result == 1){
            return Result.fail("库存不足");
        }
        if(result == 2){
            return Result.fail("重复下单");
        }
        // 有购买资格
        long orderID = redisIdWorker.nextID("order");
        // 2.保存信息到阻塞队列,会有一个线程不断从当中取出信息,执行扣库存和生成订单
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderID);    // 订单ID
        voucherOrder.setUserId(userID); // 用户ID
        voucherOrder.setVoucherId(voucherID); // 优惠券ID
        kafkaTemplate.send(ORDER_TOPIC, JSONUtil.toJsonStr(voucherOrder));
        return Result.ok(orderID);
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
