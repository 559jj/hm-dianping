package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;
@Component
public class CacheClient {

    @Autowired
    StringRedisTemplate redisTemplate;
    public static final ExecutorService CACHE_BUILD_EXECUTOR= Executors.newFixedThreadPool(10);


    //将数据存入redis
    public void set(String key,Object value, Long time, TimeUnit unit){
        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }
    //将数据存入redis并设置逻辑时间
    public void setWithLogicalExpire(String key,Object value, Long time, TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    //防止缓存穿透
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time,TimeUnit unit){
        //TODO 1、从redis查询商铺缓存
        String key=keyPrefix+ id;
        String json = redisTemplate.opsForValue().get(key);
        //TODO 2、判断是否存在，存在直接返回
        if (StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json, type);
        }
        //TODO 2.1、判断是否是空值  StrUtil.isNotBlank()方法中的参数为null、""时条件为假
        if (json!=null){//所以当shopJson为空串时上面的if判断为假，但是在这个判断中为真
            return null;
        }
        //TODO 3、确认redis不存在后，查询数据库是否存在，若不存在 为了防止缓存穿透将数据设为空串存入redis再返回
        R r = dbFallback.apply(id);
        if (r==null){
            redisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //TODO 4、存在先将数据转json存入redis，然后在将数据返回
        set(key,r,time,unit);
        return r;
    }
    //用互斥锁防止缓存击穿
    public <R,ID> R queryWithMutex(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time,TimeUnit unit){
        //TODO 1、从redis查询商铺缓存
        String key=keyPrefix+ id;
        String json = redisTemplate.opsForValue().get(key);
        //TODO 2、判断是否存在，存在直接返回
        if (StrUtil.isNotBlank(json)){
            return  JSONUtil.toBean(json, type);
        }
        //TODO 2.1、判断是否是空值  StrUtil.isNotBlank()方法中的参数为null、""时条件为假
        if (json!=null){//所以当shopJson为空串时上面的if判断为假，但是在这个判断中为真
            return null;
        }
        //TODO 获取互斥锁
        String lockKey=LOCK_SHOP_KEY+id;
        R r = null;
        try {
            boolean isLock = tryLock(lockKey);
            //TODO 判断是否成功获取锁，若失败线程进入休眠并递归
            if (!isLock){
                Thread.sleep(50);
                return queryWithMutex(keyPrefix,id,type,dbFallback,time,unit);
            }
            //TODO 获取成功根据id查询数据库数据是否存在
            r = dbFallback.apply(id);
            //模拟重建的延迟
            Thread.sleep(200);
            //TODO 3、确认redis不存在后，查询数据库是否存在，若不存在 为了防止缓存穿透将数据设为空串存入redis再返回
            if (r==null){
                set(key,"",time,unit);
            }
            //TODO 4、存在先将数据转json存入redis，释放互斥锁然后在将数据返回
            set(key,r,time,unit);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }finally {
            //TODO 释放互斥锁
            unLock(lockKey);
        }
        return r;
    }

    //用逻辑过期防止缓存击穿
    public <R,ID> R queryWithLogicExpire(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time,TimeUnit unit){
        //TODO 1、从redis查询商铺缓存
        String key=keyPrefix+ id;
        String json = redisTemplate.opsForValue().get(key);
        //TODO 2、判断是否存在，不存在直接返回
        if (StrUtil.isBlank(json)){
            return null;
        }
        //TODO 3、若redis存在，判断缓存时间是否过期
        RedisData redisData = JSONUtil.toBean(json,RedisData.class);
        JSONObject jsonObj =(JSONObject) redisData.getData();
        R r = JSONUtil.toBean(jsonObj, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //TODO 3.1 未过期，直接返回店铺信息
        if (expireTime.isAfter(LocalDateTime.now())){
            return r;
        }
        //TODO 3.2 已过期，需要缓存重建
        //TODO 4. 获取互斥锁
        String lockKey=LOCK_SHOP_KEY+id;
        //TODO 4.1 判断是否成功获取锁
        if (tryLock(lockKey)){
            //TODO 4.2 成功获取锁，开启独立线程实现缓存重建
            CACHE_BUILD_EXECUTOR.submit(()->{
                try {
                    //查询数据库
                    R r1 = dbFallback.apply(id);
                    //写入redis
                    setWithLogicalExpire(key,r1,time,unit);
                } catch (Exception e) {
                    e.printStackTrace();
                }finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }
        //TODO 4.3、获取锁失败直接返回过期的数据信息
        return r;
    }
    //生成互斥锁方法
    public boolean tryLock(String key){
        Boolean aBoolean = redisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(aBoolean);
    }
    //释放互斥锁方法
    public void unLock(String key){
        redisTemplate.delete(key);
    }

}
