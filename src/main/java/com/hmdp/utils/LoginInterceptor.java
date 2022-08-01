package com.hmdp.utils;

import org.springframework.web.servlet.HandlerInterceptor;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


public class LoginInterceptor implements HandlerInterceptor {


    @Override//校验用户的登陆  比如拦截UserController中的/user/me
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        //判断是否需要拦截（ThreadLocal中是否有用户）
        if (UserHolder.getUser()==null){
            response.setStatus(401);
            return false;
        }

        return true;
    }
}
