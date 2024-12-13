package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Transactional //涉及到多张表 最好加上事务 一旦发生错误能够回滚
    public Result seckillVoucher(Long voucherId) {
    //1.查询优惠券
    SeckillVoucher voucher =  seckillVoucherService.getById(voucherId);
    //2.判断秒杀是否开始
    if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
        return  Result.fail("秒杀尚未开始");
    }
    //3.判断秒杀是否结束
    if (voucher.getEndTime().isBefore(LocalDateTime.now())){
            return  Result.fail("秒杀已经结束");
        }

    //4.判断库存是否充足
        if(voucher.getStock()<1)
        {
            return  Result.fail("库存不足");
        }
    //5.扣减库存
       boolean success =  seckillVoucherService.update()
                .setSql("stock = stock -1")
                .eq("voucher_id",voucherId)
                .gt("stock",0) //where id = ? and stock > 0
             //   .eq("stock",voucher.getStock()) // where id = ? and stock = ? ---->这是用的CAS的方法 但是库存比较特殊 用上面的与0比较更好 不会出现库存还有但是大家都抢不到的情况
                .update();
        //知识点：如果有些方案里面乐观锁要锁的不是库存，它只能通过数据有没有变化来去判断是否安全，要提高乐观存的成功率的话 可以采用【分批加锁】或者叫【分段锁】的方案--->把资源分表存放 比如100个资源 分到10张表中，每张表是10 抢的时候可以去多张表分别去抢 成功率会提高（这种思想在hashmap中有实现

        if(!success){
            //扣减失败v
            return Result.fail("库存不足！");
        }

    //6.创建订单
        VoucherOrder order = new VoucherOrder();
        //6.1 订单id
        long orderId = redisIdWorker.nextId("order");
        order.setId(orderId);
        //6.2 用户id
        long userId = UserHolder.getUser().getId();
        order.setUserId(userId);
        //6.3代金券id
        order.setVoucherId(voucherId);

        //秒杀代金券存入数据库
        save(order);
    //7.返回订单id
        return Result.ok(orderId);
    }
}
