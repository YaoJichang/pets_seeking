package com.hmdp.service.impl;

import cn.hutool.core.util.JNDIUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

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
    public Result queryById(Long id) {
        //用空缓存解决缓存穿透问题
        //1、从redis查询商铺缓存
        String key = "cache:shop:"+id;

        //2.判断缓存是否命中
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //3.命中，直接返回
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
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
