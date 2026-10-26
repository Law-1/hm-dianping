package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;


import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;


@Slf4j
@Component
public class CacheClient {

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Resource
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }
    //设置逻辑过期
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 缓存穿透解决方案：查询指定 key 的缓存，若缓存未命中则通过 dbFallBack 回源查询数据库，
     * 并将查询结果写入缓存；若数据库中也不存在该数据，则缓存一个空字符串，避免后续请求重复穿透到数据库。
     *
     * @param keyPreFix  缓存 key 的前缀（当前实现未使用此参数，实际拼接 key 时固定使用 {@link RedisConstants#CACHE_SHOP_KEY}）
     * @param id         主键 ID，用于拼接缓存 key，同时作为 dbFallBack 的查询参数
     * @param type       目标对象类型的 Class，用于将缓存中的 JSON 字符串反序列化为对应的 Java 对象
     * @param dbFallBack 数据库查询函数，当缓存未命中时调用，传入 id 返回对应的数据库实体
     * @param time       缓存过期时间，正常数据和空值缓存均使用此时长（建议空值缓存设置较短的过期时间）
     * @param unit       缓存过期时间的单位
     * @param <R>        返回结果对象的泛型类型
     * @param <ID>       主键 ID 的泛型类型
     * @return 查询到的对象；若命中空值缓存或数据库查询结果为空，则返回 {@code null}
     */
    public <R, ID> R queryWithPassThrough(String keyPreFix, ID id, Class<R> type, Function<ID, R> dbFallBack
                , Long time, TimeUnit unit){




        String key = keyPreFix + id;
        //从Redis中查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if (StrUtil.isNotBlank(json)){
            //先转成java对象，然后再返回
            return JSONUtil.toBean(json, type);
        }
        //命中空缓存，返回错误信息
        if (json != null){
            return null;
        }
        //不存在，从数据库中查询
        R r = dbFallBack.apply(id);
        //数据库中也不存在
        if (r == null){
            //存入空值到Redis中，防止缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", 2+RandomUtil.randomLong(0,5), TimeUnit.MINUTES);
            return null;
        }
        //存在，存入Redis
        this.set(key, r, time, unit);
        return r;
    }


    /**
     * 缓存击穿解决方案（逻辑过期策略）：缓存不设置 TTL，而是把过期时间写在 value 内部。
     * 查询时若发现数据已逻辑过期，则尝试获取互斥锁，由拿到锁的线程开启独立线程池异步重建缓存，
     * 当前请求不阻塞、直接返回旧数据，以此保证高并发下的可用性（牺牲一致性换取可用性）。
     * <p>
     * 注意：使用本方法前，需先通过 {@link #setWithLogicalExpire(String, Object, Long, TimeUnit)}
     * 预热缓存数据，否则缓存未命中时将直接返回 {@code null}，不会回源查询数据库。
     *
     * @param keyPreFix  缓存 key 的前缀，与 id 拼接后作为 Redis 的完整 key
     * @param id         主键 ID，用于拼接缓存 key、互斥锁 key，同时作为 dbFallBack 的查询参数
     * @param type       目标对象类型的 Class，用于将 {@link RedisData} 中的 data 字段反序列化为对应的 Java 对象
     * @param dbFallBack 数据库查询函数，仅在缓存重建的异步线程中调用，传入 id 返回最新的数据库实体
     * @param time       逻辑过期时间长度，重建缓存时以此计算新的过期时间点（非 Redis TTL）
     * @param unit       逻辑过期时间的单位
     * @param <R>        返回结果对象的泛型类型
     * @param <ID>       主键 ID 的泛型类型
     * @return 缓存中的数据（可能是逻辑过期但尚未重建完成的旧数据）；若缓存不存在则返回 {@code null}
     */
    public <R, ID> R queryWithLogicalExpire(String keyPreFix,String lockKeyPreFix, ID id,
                                            Class<R> type, Function<ID, R> dbFallBack,
                                            Long time, TimeUnit unit){
        String key = keyPreFix + id;
        //从Redis中查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if (StrUtil.isBlank(json)){
            return null;
        }
        //命中缓存,把JSON反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //未过期，直接返回商铺信息
            return r;

        }
        //过期则重建缓存
        //获取互斥锁
        String lockKey = lockKeyPreFix + id;
        Boolean flag = tryLock(lockKey);
        //判断是否成功
        if (BooleanUtil.isTrue(flag)) {
            //成功 开启线程，并返回旧信息
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //查询数据库
                    R r1 = dbFallBack.apply(id);
                    //逻辑过期,存入Redis
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }

        //失败 返回旧信息
        return r;
    }

    /**
     * 缓存击穿解决方案（互斥锁策略）：查询缓存未命中时，先尝试获取互斥锁，
     * 只有拿到锁的线程才回源查询数据库并重建缓存；未拿到锁的线程休眠后重试，
     * 以此保证同一时刻只有一个线程重建缓存（牺牲可用性换取一致性）。
     * 同时通过缓存空值解决缓存穿透问题。
     *
     * @param keyPreFix  缓存 key 的前缀，与 id 拼接后作为 Redis 的完整 key
     * @param id         主键 ID，用于拼接缓存 key、互斥锁 key，同时作为 dbFallBack 的查询参数
     * @param type       目标对象类型的 Class，用于将缓存中的 JSON 字符串反序列化为对应的 Java 对象
     * @param dbFallBack 数据库查询函数，当缓存未命中且成功获取互斥锁时调用，传入 id 返回对应的数据库实体
     * @param time       缓存过期时间（TTL），正常数据和空值缓存均使用此时长
     * @param unit       缓存过期时间的单位
     * @param <R>        返回结果对象的泛型类型
     * @param <ID>       主键 ID 的泛型类型
     * @return 查询到的对象；若命中空值缓存或数据库查询结果为空，则返回 {@code null}
     */
       public <R, ID> R queryWithMutex(String keyPreFix, String lockKeyPreFix, ID id,
                                   Class<R> type, Function<ID, R> dbFallBack,
                                   Long time, TimeUnit unit){
        String key = keyPreFix + id;
        //从Redis中查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if (StrUtil.isNotBlank(json)){
            //先转成java对象，然后再返回
            return JSONUtil.toBean(json, type);
        }
        //命中空缓存，返回错误信息
        if (json != null){
            return null;
        }

        //实现缓存重建
        //获取互斥锁
        String lockKey = lockKeyPreFix +id;
        R r =null;
        try {
            Boolean isLock = tryLock(lockKey);
            //判断是否成功
            if (!isLock){
                //失败，休眠，重试
                Thread.sleep(50);
                return queryWithMutex(keyPreFix,lockKeyPreFix, id, type, dbFallBack, time, unit);
            }

            // ========== 新增：double check ==========
            String jsonAgain = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(jsonAgain)) {
                return JSONUtil.toBean(jsonAgain, type);
            }
            if(jsonAgain != null){
                return null;
            }

            //不存在，从数据库中查询
            r = dbFallBack.apply(id);
            //数据库中也不存在
            if (r == null){
                //存入空值到Redis中，防止缓存穿透
                stringRedisTemplate.opsForValue().set(key, "", 2+RandomUtil.randomLong(0,5), TimeUnit.MINUTES);
                return null;
            }
            //存在，存入Redis
//            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(r), time, unit);
            this.set(key,r,time,unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //释放互斥锁
            unLock(lockKey);
        }

        return r;
    }

    //获取锁
    private Boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        //防止空指针（null）
        return BooleanUtil.isTrue(flag);
    }
    //释放锁
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

}
