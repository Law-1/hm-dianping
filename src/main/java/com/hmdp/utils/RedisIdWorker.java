package com.hmdp.utils;


import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 开始时间戳
     */
    private static final Long BEGIN_TIMESTAMP = 1735689600L;
    /**
     * 序列号位数
     */
    private static final int COUNT_BITS = 32;

    //生成全局唯一ID
    public Long nextId(String keyPreFix){
        LocalDateTime now = LocalDateTime.now();
        //生成时间戳
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        Long timeSamp = nowSecond - BEGIN_TIMESTAMP;
        //生成序列号
        //获取当前日期
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPreFix + ":" + date);
        //拼接返回
        return timeSamp << COUNT_BITS | count;
    }

    public static void main(String[] args) {
        long second = LocalDateTime.of(2025, 1, 1, 0, 0, 0).toEpochSecond(ZoneOffset.UTC);
    }
}
