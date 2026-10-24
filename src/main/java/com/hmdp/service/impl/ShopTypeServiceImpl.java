package com.hmdp.service.impl;


import cn.hutool.core.util.StrUtil;


import cn.hutool.json.JSONUtil;
import com.alibaba.fastjson.JSON;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;


/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        //定义Redis的key
        String key = CACHE_SHOP_TYPE_KEY;
        //查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //判断缓存是否存在
        if (StrUtil.isNotBlank(json)){
            //存在，JSON字符串转List
//            JSON.parseArray(json, ShopType.class);
            List<ShopType> shopTypeList = JSONUtil.toList(json, ShopType.class);
            return Result.ok(shopTypeList);
        }
        //缓存不存在，查数据库
        List<ShopType> shopTypeList00 = query().orderByAsc("sort").list();
        //存入缓存
//        JSON.toJSONString(shopTypeList00);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shopTypeList00));
        return Result.ok(shopTypeList00);
    }
}
