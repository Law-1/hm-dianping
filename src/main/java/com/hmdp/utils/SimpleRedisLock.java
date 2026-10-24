package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private String name;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private final String KEY_PREFIX = "lock:";
    //todo:用随机表示开头来区分开用户线程，用来防止
    // 业务阻塞时间过长，key失效过后才释放锁，导致其他 线程 ->（02）能够获取锁，且中途 02 锁被释放
    private final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    //提前加载Lua脚本
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
    }

    @Override
    public Boolean tryLock(long timeoutSec) {
        //获取线程表示
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        //获取锁
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);

        return Boolean.TRUE.equals(success);//防止拆箱得到null空指针
    }

    @Override
    public void unlock() {
        //调用Lua脚本来实现原子性释放锁
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                threadId);
    }

//    @Override
//    public void unLock() {
//        //获取线程ID标识
//        String threadId = ID_PREFIX + Thread.currentThread().getId();
//        //获取锁中的线程标识
//        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        //判断是否一致
//        if ( threadId.equals(id)) {
//            //在这之前也有可能阻塞(例如jvm的垃圾回收)
//            //todo:解决方法: Lua脚本
//            //释放锁
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
//    }
}
