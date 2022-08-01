package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result sendCode(String phone) {
        //1、校验手机号
        if (RegexUtils.isPhoneInvalid(phone)){
            //1.1、如果不符合
            return Result.fail("手机号格式不正确");
        }
        //1.2、符合,生成验证码并保存到redis中
        String code = RandomUtil.randomNumbers(6);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //2、发送验证码
        log.debug("发送验证码成功，验证码：{}",code);
        //3、返回ok
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm) {
        //1、校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)){
            //1.1、如果不符合，报错
            return Result.fail("手机号格式不正确");
        }
        //2、从redis获取验证码并校验
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if (cacheCode==null || !cacheCode.equals(code)){
            //2.1、校验不一致，报错
            return Result.fail("验证码不正确");
        }
        //3、校验一致，根据手机号查询用户select * from tb_user where phone = ?
        User user= query().eq("phone", phone).one();
        //4、如果用户不存在 创建新用户
        if (user==null){
            user=createUserWithPhone(phone);
        }
        //5、如果用户已存在  将用户保存到redis中
        //5.1随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        //5.2将User对象转成Hash存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                    .setIgnoreNullValue(true)
                    .setFieldValueEditor((fieldName,fieldValue) -> fieldValue.toString()));
        //5.3保存数据到redis
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY+token,userMap);
        //5.4设置token有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY+token,LOGIN_USER_TTL,TimeUnit.MINUTES);
        //6、返回token到客户端浏览器
        return Result.ok(token);
    }
    //创建新用户
    private User createUserWithPhone(String phone){
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(5));
        //保存用户
        save(user);
        return user;
    }
}
