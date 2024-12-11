package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

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
    public List<ShopType> queryTypeList() {
        List<ShopType> typeList;
        String key = "cache:shopType";
        // 1.查缓存
        String shopTypeList = stringRedisTemplate.opsForValue().get(key);
        // 2.有-直接返回
        if(StrUtil.isNotBlank(shopTypeList)){
            typeList = JSONUtil.toList(shopTypeList,ShopType.class);
            return typeList;
        }
        // 3.没有-查数据库
        typeList = query().orderByAsc("sort").list();
        // 4.存入缓存
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(typeList));
        // 5.返回
        return typeList;
    }
}
