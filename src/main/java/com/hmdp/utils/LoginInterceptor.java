package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpStatus;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

//只需要负责登录时的拦截 其余的拦截都放到刷新里面去了
public class LoginInterceptor implements HandlerInterceptor {

    // new出来的对象是无法直接注入IOC容器的（LoginInterceptor是直接new出来的）
    // 所以这里需要再配置类中注入，然后通过构造器传入到当前类中

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 判断当前用户是否已登录
        if (UserHolder.getUser() == null){
            // 当前用户未登录，直接拦截
            response.setStatus(HttpStatus.HTTP_UNAUTHORIZED);
            return false;
        }
        // 用户存在，直接放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        //移除用户（不然会内存泄漏）因为ThreadLocal底层是ThreadLocalMap，当期线程Threadlocal作为key(弱引用)，user作为value(强引用)然后jvm不会把强引用的value回收掉，所以value没被释放
        UserHolder.removeUser();
    }
}
