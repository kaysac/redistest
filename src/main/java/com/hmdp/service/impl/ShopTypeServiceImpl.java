package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOPLIST_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

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
    public Result queryRedis() {
        String key = CACHE_SHOPLIST_KEY;
        //从Redis中查询商铺信息
        String shopListJson = stringRedisTemplate.opsForValue().get(key);

        //查到商铺信息
        if(StrUtil.isNotBlank(shopListJson)){
            List<ShopType> shopTypeList = JSONUtil.toList(shopListJson,ShopType.class);
            return Result.ok(shopTypeList);
        }

        //Redis中没有所有商铺信息，去数据库中查找
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();

        if(shopTypeList==null){
            //数据库中也没有
            return Result.fail("列表信息不存在");
        }

        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shopTypeList));
        return Result.ok(shopTypeList);
    }
}
