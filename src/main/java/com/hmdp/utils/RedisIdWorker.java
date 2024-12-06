package com.hmdp.utils;


import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

//ID生成器的算法实现
@Component
public class RedisIdWorker {

    private  static  final  long BEGIN_TIMESTAM = 1733505420l; //根据下面的main函数生成的开始时间戳

    private StringRedisTemplate stringRedisTemplate;

     public  RedisIdWorker (StringRedisTemplate stringRedisTemplate){
         this.stringRedisTemplate = stringRedisTemplate;
     }

    public  long nextId(String keyPrefix){
        //1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStap = nowSecond - BEGIN_TIMESTAM;

        //2.生成序列号
        // 2.1 获取当前日期，精确到天【当时面试就碰到了这个地方不会调！！！】
        String date =  now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //2.2 自增长
        long count = stringRedisTemplate.opsForValue().increment("icr:"+keyPrefix+":"+ date);

        //3、拼接并返回
        return (timeStap << 32) | count;
    }

    public static void main(String[] args) {


        LocalDateTime time = LocalDateTime.of(2024,12,6,17,17,0);

        //将time表示的时间转换为自Unix纪元（1970年1月1日00:00:00 UTC）以来的秒数。拿来做时间戳
        long second = time.toEpochSecond(ZoneOffset.UTC);

        System.out.println(second);
    }

}
