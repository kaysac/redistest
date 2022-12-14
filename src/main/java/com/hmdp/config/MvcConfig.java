package com.hmdp.config;

import com.hmdp.utils.LoginIntercepter;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class MvcConfig implements WebMvcConfigurer {
//    @Override
//    public void addInterceptors(InterceptorRegistry registry) {
//        registry.addInterceptor(new LoginIntercepter()).excludePathPatterns("/shop/**",
//                "/voucher/**",
//                "/shop-type/**",
//                "/upload/**",
//                "/blog/hot",
//                "/user/code",
//                "/user/login");
//    }
}
