package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.*;
import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.DEFAULT_PAGE_SIZE;

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

//    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Resource
    private CacheClient cacheClient;

//    给ttl设置随机值防止缓存雪崩
    long totalTTLShop = CACHE_SHOP_TTL + RandomUtil.randomLong(0,3);
//    long totalTTLNull = CACHE_NULL_TTL + RandomUtil.randomLong(0,10);

    //根据ID查询商铺
    @Override
    public Result queryById(Long id) {
        //解决缓存穿透
//        Shop shop = queryWithPassThrough(id);
//        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY + id, id, Shop.class,
////                id00 -> getById(id00)
//                this::getById,
//                totalTTLShop, TimeUnit.MINUTES);

        //互斥锁解决缓存击穿+缓存穿透
//        Shop shop = queryWithMutex(id);
/*        Shop shop = cacheClient.queryWithMutex(CACHE_SHOP_KEY + id,LOCK_SHOP_KEY, id,
                Shop.class, this::getById, totalTTLShop, TimeUnit.MINUTES);*/

        //      逻辑过期缓存击穿
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY + id,LOCK_SHOP_KEY, id,
                Shop.class, this::getById, totalTTLShop, TimeUnit.MINUTES);

        if (shop == null) {
            return Result.fail("店铺不存在");
        }


        return Result.ok(shop);
    }

//  互斥锁缓存击穿method
  /*  public Shop queryWithMutex(Long id){
        String key = CACHE_SHOP_KEY + id;
        //从Redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if (StrUtil.isNotBlank(shopJson)){
            //先转成java对象，然后再返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //命中空缓存，返回错误信息
        if (shopJson != null){
            return null;
        }

        //实现缓存重建
        //获取互斥锁
        String lockKey = LOCK_SHOP_KEY +id;
        Shop shop = null;
        try {
            Boolean isLock = tryLock(lockKey);
            //判断是否成功
            if (!isLock){
                //失败，休眠，重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }


            //不存在，从数据库中查询
            shop = getById(id);
            //数据库中也不存在
            if (shop == null){
                //存入空值到Redis中，防止缓存穿透
                stringRedisTemplate.opsForValue().set(key, "", totalTTLNull, TimeUnit.MINUTES);
                return null;
            }
            //存在，存入Redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), totalTTLShop, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //释放互斥锁
            unLock(lockKey);
        }

        return shop;
    }*/

    //封装缓存穿透Method
   /* public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY + id;
        //从Redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if (StrUtil.isNotBlank(shopJson)){
            //先转成java对象，然后再返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //命中空缓存，返回错误信息
        if (shopJson != null){
            return null;
        }
        //不存在，从数据库中查询
        Shop shop = getById(id);
        //数据库中也不存在
        if (shop == null){
            //存入空值到Redis中，防止缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", totalTTLNull, TimeUnit.MINUTES);
            return null;
        }
        //存在，存入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), totalTTLShop, TimeUnit.MINUTES);
        return shop;
    }*/

//  逻辑过期缓存击穿method
 /*   public Shop queryWithLogicalExpire(Long id){
        String key = CACHE_SHOP_KEY + id;
        //从Redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if (StrUtil.isBlank(shopJson)){
            return null;
        }
        //命中缓存,把JSON反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //未过期，直接返回商铺信息
            return shop;

        }
        //过期则重建缓存
        //获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Boolean flag = tryLock(lockKey);
        //判断是否成功
        if (BooleanUtil.isTrue(flag)) {
            //成功 开启线程，并返回旧信息
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    this.saveShop2Redis(id, 30*60L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }

        //失败 返回旧信息
        return shop;
    }*/

/*    //获取锁
    private Boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        //防止空指针（null）
        return BooleanUtil.isTrue(flag);
    }
    //释放锁
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }*/

    //提前缓存热点key
/*    public void saveShop2Redis(Long id, Long expireSeconds){
        //查询店铺数据
        Shop shop = getById(id);
        //封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //缓存到Redis中
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY +id, JSONUtil.toJsonStr(redisData));

    }*/

    //todo:更新商铺
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null){
            return Result.fail("商铺ID不能为空");
        }
        //更新数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    //todo:实现附近店铺
    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 判断是否需要根据坐标查询
        if (x == null || y == null){
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }
        //计算分页参数
        int from = (current - 1) * DEFAULT_PAGE_SIZE;
        int end = current * DEFAULT_PAGE_SIZE;
        // 查询redis，按照距离排序、分页。结果：shopId,distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS number WITHDISTANCE
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance()
                                .limit(end)
                );
        //解析出id
        if (results == null){
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from){
            //没有下一页 *注意点*
            return Result.ok(Collections.emptyList());
        }
        // 截取from - end 部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 获取店铺Id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        //根据店铺id查询店铺信息
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }
}
