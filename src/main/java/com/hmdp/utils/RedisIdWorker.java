package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
//生成订单号
@Component
public class RedisIdWorker {

    private static final long BEGINB_TIMESTAMP=1658275200;//2022年7月20日零点零分
    private static final int COUNT_BITS=32;//序列号的位数
    @Autowired
    StringRedisTemplate redisTemplate;
    public long nextId(String keyPrefix){
        //1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp=nowSecond- BEGINB_TIMESTAMP;
        //2.通过自增长生成序列号
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = redisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        //3.拼接时间戳和序列号   因为他们都是长整型并不是字符串所以不能直接通过“+”进行拼接
        // 下面的方式是通过将时间戳向左移序列号个位数然后将被移走的位置与序列号进行或运算 从而完成拼接
        return timestamp << COUNT_BITS | count;

    }

//    public static void main(String[] args) {
//        LocalDateTime time = LocalDateTime.of(2022, 7, 20, 0, 0);
//        long second = time.toEpochSecond(ZoneOffset.UTC);
//        System.out.println(second);
//    }

}
