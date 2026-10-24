package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IShopService shopService;

    @Test
    void loadShopData(){
        //查询店铺信息
        List<Shop> shops = shopService.list();
        //按照typeId分组
        Map<Long, List<Shop>> typeIdMap = shops.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //分批存入redis中
        for (Map.Entry<Long, List<Shop>> entry : typeIdMap.entrySet()) {
            //获取店铺类型id
            Long typeId = entry.getKey();
            String key = SHOP_GEO_KEY + typeId;
            //获取同类型的店铺集合
            List<Shop> value = entry.getValue();
            // List<RedisGeoCommands.GeoLocation<member.class>>
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
            for (Shop shop : value) {
//                stringRedisTemplate.opsForGeo().add(key, new Point(shop.getX(),shop.getY()), shop.getId().toString());
                locations.add(
                        new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(), new Point(shop.getX(), shop.getY())
                        )
                );
            }
            //存入redis GEOADD key 经度 纬度 member
            stringRedisTemplate.opsForGeo().add(key,locations);
        }
    }
}
