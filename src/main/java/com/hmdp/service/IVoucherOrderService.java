package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
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
    // 【给Controller调用】接收前端秒杀请求，执行Lua，生产消息
    Result seckillVoucher(Long voucherId);
    // 【后台消费线程调用】数据库落单，带@Transactional事务
    void createVoucherOrder(VoucherOrder voucherOrder, Long userId);
}
