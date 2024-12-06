package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id){
         return Result.ok(queryWithLogicalExpire(id));
        // return queryWithBlank(id);
    }


    //用空缓存解决缓存穿透问题
    public Result queryWithBlank(Long id) {
        //1、从redis查询商铺缓存
        String key = "cache:shop:"+id;

        //2.判断缓存是否命中
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //3.命中，直接返回
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class); //这个就是反序列化
            return Result.ok(shop);
        }

        //缓存未命中，判断缓存中查询的数据是否是空字符串(isNotBlank把null和空字符串都给排除了 而空缓存是“ ”，null是没有缓存）
        if(shopJson!= null){
            //缓存为空缓存 说明店铺不存在 直接返回 避免查数据库造成的缓存穿透
            return Result.fail("店铺不存在");
        }


        //4、如果缓存为null，根据id查询数据库(用MyBatisPlus）
        Shop shop = getById(id);

        //5.数据库不存在，返回错误且【缓存一个空值】
        if(shop == null)
        {
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.SECONDS);
            return Result.fail("店铺不存在！");

        }
        //6.存在，写入redis，并设置缓存过期时间
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //7.返回
        return Result.ok(shop);

    }


    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //用逻辑过期解决缓存击穿问题
    public Shop queryWithLogicalExpire(Long id) {
        //1、从redis查询商铺缓存
        String key = "cache:shop:"+id;

        //2.判断缓存是否命中
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //3.未命中，直接返回空【这里思考一下为什么可以直接返回空而不需要从数据库读】----因为缓存击穿默认的都是热点Key 都是提前已经部署在redis里面的了 如果redis里面没有 那就说明不存在这个数据
        if(StrUtil.isBlank(shopJson)){
            return null;
        }

        //4.命中，需要把json反序列化【啊啊啊啊这就是反序列化】
        RedisData redisData = JSONUtil.toBean(shopJson,RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data,Shop.class);

        LocalDateTime expireTime = redisData.getExpireTime();

        //5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            //5.1 未过期，直接返回店铺信息
            return shop;

        }

        //5.2 已过期，需要缓存重建

        //6.缓存重建
        //6.1 获取互斥锁
        String lockKey =  LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);

        //6.2 判断获取锁是否成功

        if(isLock){
            //先double check这个redis缓存是否过期 如果存在则无需重建缓存
            if(expireTime.isAfter(LocalDateTime.now()))
                return shop;
            //6.3 成功，开启独立线程，实现缓存重建(用线程池去做）
            CACHE_REBUILD_EXECUTOR.submit(() ->{
               try {
                  //重建缓存
                   this.saveShop2Redis(id, 20L);
               } catch (Exception e){
                   throw new RuntimeException(e);
               }
               finally {
                   unlock(lockKey);
               }
            });
        }

        //6.4 失败，直接返回过期的商铺信息
        return shop;


    }


    public void saveShop2Redis(Long id, Long time) throws InterruptedException {
        //1.查询店铺数据
        Shop shop = getById(id);

        //模拟缓存重建耗时要200ms 说明可能存在的数据不一致性
        Thread.sleep(200);

        //2、封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(time));

        //存入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));

    }

    //上互斥锁
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        // 拆箱要判空，防止NPE
        return BooleanUtil.isTrue(flag);
    }

    //解除互斥锁
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    @Transactional
    @Override
    public Result update (Shop shop){

        Long id = shop.getId();
        if(id == null){
            return Result.fail("店铺id不能为空");
        }

        // 1、先更新数据库中的店铺数据
        boolean f = updateById(shop);
        if (!f){
            // 缓存更新失败，抛出异常，事务回滚
            throw new RuntimeException("数据库更新失败");
        }
        // 2、再删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());

        return Result.ok();

    }
}
