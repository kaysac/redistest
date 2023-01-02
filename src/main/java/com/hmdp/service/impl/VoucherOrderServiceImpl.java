package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.IdWorker;
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
    private ISeckillVoucherService iSeckillVoucherService;


    @Resource
    private IdWorker idWorker;

    @Override
    public Result getOrder(Long voucherId) {
        //先去数据库查询voucher
        SeckillVoucher voucher = iSeckillVoucherService.getById(voucherId);

        //是否开始或者过期
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("活动尚未开始");
        }

        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("活动已结束");
        }

        //是否还有存量
        if (voucher.getStock() <= 0) {
            return Result.fail("优惠券没有了");
        }

        Long userId = UserHolder.getUser().getId();
        synchronized (userId.toString().intern()) {
            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
            return proxy.getVoucherOrder(voucherId);
        }
    }

    @Transactional
    public Result getVoucherOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        //一人一单

        int count = query().eq("voucher_id", voucherId).eq("user_id", userId).count();
        if (count > 0) {
            return Result.fail("请不要重复下单");
        }

        //减库存
        boolean success = iSeckillVoucherService.update().
                setSql("stock=stock-1").eq("voucher_id", voucherId).gt("stock", 0).update();
        if (!success) {
            return Result.fail("抢购失败，再多多尝试");
        }
        //返回订单
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = idWorker.nextId("order");

        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        save(voucherOrder);

        return Result.ok(orderId);

    }
}
