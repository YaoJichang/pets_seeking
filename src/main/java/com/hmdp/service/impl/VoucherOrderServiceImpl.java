package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;



    //25/1/14 用lua脚本后 这个代码也注释掉
//   // @Transactional //涉及到多张表 最好加上事务 一旦发生错误能够回滚
//    //下面加了锁以后 就不要这个事务了 （锁要包裹事务，事务不能包裹锁）
//    public Result seckillVoucher(Long voucherId) {
//        //1.查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        //2.判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀尚未开始");
//        }
//        //3.判断秒杀是否结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已经结束");
//        }
//
//        //4.判断库存是否充足
//        if (voucher.getStock() < 1) {
//            return Result.fail("库存不足");
//        }
//
//        //上锁实现一人一单
//        Long userId = UserHolder.getUser().getId();
////        //创建锁对象
////        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId,stringRedisTemplate);
////        //获取锁
////        boolean isLock = lock.tryLock(1200);
//        //--------------------------------------------------------------------------------------
//        //用redission来究极优化分布式锁
//        RLock lock = redissonClient.getLock(RedisConstants.LOCK_ORDER_KEY + userId);
//        boolean isLock = lock.tryLock();
//
//
//        if(!isLock){
//          //获取锁失败，返回错误或重试
//            return Result.fail("不允许重复下单");
//
//        }
//           try{
//               // 获取代理对象（事务）
//               IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//               return  proxy.createVoucherOrder(voucherId);
//           }
//           finally {
//               // 释放锁
//               lock.unlock();
//           }
//
//    }

    /**
     * 加载 判断秒杀券库存是否充足 并且 判断用户是否已下单 的Lua脚本
     */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("lua/seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //存储订单的阻塞队列
    //todo 优化消息队列（思考一下有没有用的必要）
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    //线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();



    /**
     * 线程任务: 不断从阻塞队列中获取订单
     */
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                // 从阻塞队列中获取订单信息，并创建订单
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }

        /**
         * 当前类初始化完毕就立马执行该方法
         */
        @PostConstruct
        private void init() {
            // 执行线程任务
            SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
        }

        /**
         * 创建订单
         *
         * @param voucherOrder
         */
        private void handleVoucherOrder(VoucherOrder voucherOrder) {
            Long userId = voucherOrder.getUserId();
            RLock lock = redissonClient.getLock(RedisConstants.LOCK_ORDER_KEY + userId);
            boolean isLock = lock.tryLock();
            if (!isLock) {
                // 索取锁失败，重试或者直接抛异常（这个业务是一人一单，所以直接返回失败信息）
                log.error("一人只能下一单");
                return;
            }
            try {
                // 创建订单（使用代理对象调用，是为了确保事务生效）
                proxy.createVoucherOrder(voucherOrder);
            } finally {
                lock.unlock();
            }
        }

        /**
         * VoucherOrderServiceImpl类的代理对象
         * 将代理对象的作用域进行提升，方面子线程取用
         */
        private IVoucherOrderService proxy;


        @Transactional
        public Result seckillVoucher(Long voucherId) {
            // 1、执行Lua脚本，判断用户是否具有秒杀资格
            Long result = null;
            try {
                result = stringRedisTemplate.execute(
                        SECKILL_SCRIPT,
                        Collections.emptyList(),
                        voucherId.toString(),
                        UserHolder.getUser().getId().toString()
                );
            } catch (Exception e) {
                log.error("Lua脚本执行失败");
                throw new RuntimeException(e);
            }
            if (result != null && !result.equals(0L)) {
                // result为1表示库存不足，result为2表示用户已下单
                int r = result.intValue();
                return Result.fail(r == 2 ? "不能重复下单" : "库存不足");
            }
            // 2、result为0，用户具有秒杀资格，将订单保存到阻塞队列中，实现异步下单
            long orderId = redisIdWorker.nextId("order");
            // 创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            voucherOrder.setId(orderId);
            voucherOrder.setUserId(UserHolder.getUser().getId());
            voucherOrder.setVoucherId(voucherId);
            // 将订单保存到阻塞队列中
            orderTasks.add(voucherOrder);
            // 索取锁成功，创建代理对象，使用代理对象调用第三方事务方法， 防止事务失效
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            this.proxy = proxy;
            return Result.ok();
        }

        // 1/14 改造了该函数
//    @Transactional
//    public Result createVoucherOrder(Long voucherId){
//        //6. 一人一单
//        Long userId = UserHolder.getUser().getId();
//        //6.1 查询订单
//        int count = query().eq("user_id",userId).eq("voucher_id",voucherId).count();
//        //6.2 判断是否存在
//        if(count>0){
//            //用户已经购买过了
//            return Result.fail("用户已经购买过一次！");
//        }
//
//        //6.3.扣减库存
//        boolean success = seckillVoucherService.update()
//                .setSql("stock = stock -1")
//                .eq("voucher_id", voucherId)
//                .gt("stock", 0) //where id = ? and stock > 0
//                //   .eq("stock",voucher.getStock()) // where id = ? and stock = ? ---->这是用的CAS的方法 但是库存比较特殊 用上面的与0比较更好 不会出现库存还有但是大家都抢不到的情况
//                .update();
//        //知识点：如果有些方案里面乐观锁要锁的不是库存，它只能通过数据有没有变化来去判断是否安全，要提高乐观存的成功率的话 可以采用【分批加锁】或者叫【分段锁】的方案--->把资源分表存放 比如100个资源 分到10张表中，每张表是10 抢的时候可以去多张表分别去抢 成功率会提高（这种思想在hashmap中有实现
//
//        if (!success) {
//            //扣减失败v
//            return Result.fail("库存不足！");
//        }
//        //7.创建订单
//        VoucherOrder order = new VoucherOrder();
//        //7.1 订单id
//        long orderId = redisIdWorker.nextId("order");
//        order.setId(orderId);
//        //7.2 用户id
//        order.setUserId(userId);
//        //7.3代金券id
//        order.setVoucherId(voucherId);
//        //秒杀代金券存入数据库
//        save(order);
//        //8.返回订单id
//        return Result.ok(orderId);
//        }

        /**
         * 创建订单
         *
         * @param voucherOrder
         * @return
         */
        @Transactional
        public void createVoucherOrder(VoucherOrder voucherOrder) {
            Long userId = voucherOrder.getUserId();
            Long voucherId = voucherOrder.getVoucherId();
            // 1、判断当前用户是否是第一单
            int count = query().eq(
                    "user id",userId).eq(
                    "voucher id",voucherOrder).count();
            if (count >= 1) {
                // 当前用户不是第一单
                log.error("当前用户不是第一单");
                return;
            }
            // 2、用户是第一单，可以下单，秒杀券库存数量减一
            boolean flag = seckillVoucherService.update(new LambdaUpdateWrapper<SeckillVoucher>()
                    .eq(SeckillVoucher::getVoucherId, voucherId)
                    .gt(SeckillVoucher::getStock, 0)
                    .setSql("stock = stock -1"));
            if (!flag) {
                throw new RuntimeException("秒杀券扣减失败");
            }
            // 3、将订单保存到数据库
            flag = save(voucherOrder);
            if (!flag) {
                throw new RuntimeException("创建秒杀券订单失败");
            }
        }

    }

