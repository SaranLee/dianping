package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

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

    final
    StringRedisTemplate stringRedisTemplate;

    public ShopTypeServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public Result listCacheable() {
        // 1. 从redis中查
        String shopTypeJsonStr = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_TYPE_KEY);
        // 2. redis不为空，返回
        if (shopTypeJsonStr != null) {
            List<ShopType> typeList = JSONUtil.toList(shopTypeJsonStr, ShopType.class);
            return Result.ok(typeList);
        }
        // 3. redis未缓存，从数据库查
        List<ShopType> typeList = this.query().orderByAsc("sort").list();
        // 4. 如果数据库查到list大小位0，返回错误
        if (typeList == null || typeList.size() == 0) {
            return Result.fail("shopType error!");
        }
        // 5. 缓存进redis，返回
        stringRedisTemplate.opsForValue().set(
                RedisConstants.CACHE_SHOP_TYPE_KEY,
                JSONUtil.toJsonStr(typeList),
                RedisConstants.CACHE_SHOP_TTL,
                TimeUnit.MINUTES
        );
        return Result.ok(typeList);
    }
}
