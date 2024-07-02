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
import org.jetbrains.annotations.NotNull;
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
    RedisIdWorker redisIdWorker;

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

        Long userId = UserHolder.getUser().getId();
        synchronized (userId.toString().intern()) {
            /*
                intern()是返回字符串对象的规范表示，即从字符串常量池中找该值的字符串引用返回
                只对userId加锁的话每次都是新对象 toString（）返回的是new的字符串 也是新对象
                从而保证了对于userId值相同的是同一把锁

                同时我们要注意 这里的锁的范围应该是在事务范围之外 否则就失去了锁的作用

                还有 这里关于事务的问题 Spring的事务管理是通过AOP的方式走Spring的动态代理来实现的
                我们必须拿到这个proxy来去加注解 否则@Transactional自调用不起作用
                (pom.xml里加了aspectj相关依赖 同时在启动项加了@EnableAspectJAutoProxy(exposeProxy = true)的注解 暴露出来proxy)
             */

            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        //一人一单判断
        long count1 = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count1 > 0) {
            return Result.fail("该用户已经购买过了！");
        }

        //4.扣减库存 创建订单 返回订单id
        boolean success = iSeckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                //下面这行代码利用CAS 乐观锁 解决超卖的问题
                //即在查询时判断是否存在再继续
                .gt("stock", 0)
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
        voucherOrder.setUserId(userId);
        //代金劵id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        return Result.ok(orderId);

    }
}
