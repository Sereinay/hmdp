package com.hmdp.service.impl;

import cn.hutool.Hutool;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;

import static com.hmdp.utils.RedisConstants.SHOP_TYPE_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        String key = SHOP_TYPE_KEY;
        //1.从redis里查询是否存在
        String shopTypeJson = stringRedisTemplate.opsForValue().get(key);
        //2.若存在，直接返回
        List<ShopType> list;
        if (StrUtil.isNotBlank(shopTypeJson)) {
            list = JSONUtil.toList(shopTypeJson, ShopType.class);
            return Result.ok(list);
        }
        //3.若不存在，去数据库里进行查询
        list = this.list(new LambdaQueryWrapper<ShopType>()
                .orderByAsc(ShopType::getSort));
        //4.数据库里不存在，返回
        if (list == null) {
            return Result.fail("不存在对应类型商户！");
        }
        // 存在，则写回redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(list));
        //5.返回
        return Result.ok(list);
    }
}
