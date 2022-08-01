package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

public class RefreshTokenInterceptor implements HandlerInterceptor {
    //通过构造函数注入stringRedisTemplate
    private StringRedisTemplate stringRedisTemplate;
    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override//拦截一切请求  获取token查询用户  保存用户到ThreadLocal 刷新token 放行
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //TODO 1、获取请求头中的token，
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)){
            return true;
        }
        //TODO 2、基于token并获取redis中的用户
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(LOGIN_USER_KEY + token);

        //TODO 3、判断用户是否存在
        if (userMap.isEmpty()){
            return true;
        }
        //TODO 4、 将查询到的Hash数据转为UserDTO对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        //TODO 5、存在，保存用户信息到threadLocal并放行
        UserHolder.saveUser(userDTO);
        //TODO 6、刷新token中的有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY + token,LOGIN_USER_TTL, TimeUnit.MINUTES);
        //TODO 7、放行
        return true;
    }

    @Override//拦截时机在视图渲染之后返回给用户之前
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex){
        //移除用户
        UserHolder.removeUser();
    }
}
