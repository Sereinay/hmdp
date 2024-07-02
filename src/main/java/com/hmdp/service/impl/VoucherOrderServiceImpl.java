package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
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
    RedisIdWorker redisIdWorker;

    @Transactional
    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.拿到id 进行查询
        SeckillVoucher voucher = iSeckillVoucherService.getById(voucherId);
        //2.判断是否在秒杀时间端内（开始+结束）
        LocalDateTime beginTime = voucher.getBeginTime();
        LocalDateTime endTime = voucher.getEndTime();
        //now在开始前或者结束后，直接返回null
        if (LocalDateTime.now().isBefore(beginTime) || LocalDateTime.now().isAfter(endTime)) {
            return Result.fail("秒杀已过期！");
        }
        //3.判断库存是否充足
        Integer count = voucher.getStock();
        if (count <= 0) {
            return Result.fail("库存不足！抢购失败");
        }
        //4.扣减库存 创建订单 返回订单id
        boolean success = iSeckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .update();
        if (!success) {
            return Result.fail("下单失败！");
        }
        //创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //初始化以下的几个字段即可，其他的部分直接按照默认即可：
        //订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //用户id
        Long userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);
        //代金劵id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        return Result.ok(orderId);
    }
}
