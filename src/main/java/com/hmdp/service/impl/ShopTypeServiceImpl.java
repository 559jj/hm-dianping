package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Autowired
    StringRedisTemplate stringRedisTemplate;
//    @Override
//    public List<ShopType> queryList() {
//        List<ShopType> shopTypes = query().orderByAsc("sort").list();
//        for (int i = 0; i < shopTypes.size(); i++) {
//            for (ShopType shopType : shopTypes) {
//                i=1;
//                stringRedisTemplate.opsForValue().set("cache:shopType"+i, JSONUtil.toJsonStr(shopType));
//            }
//        }
//
//        return shopTypes;
//    }
}
