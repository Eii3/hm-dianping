package com.jktt.service;

import com.jktt.dto.Result;
import com.jktt.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {
    Result seckillVoucher(Long voucherID);

    Result createVoucherOrder(Long voucherID, Long userID);
}
