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
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1. 接受手机号并判定是否正确
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("请输入正确格式的手机号");
        }
        //2. 产生验证码
        String code = RandomUtil.randomNumbers(6);

//        //3.将验证码保存在session中
//        session.setAttribute("code",code);

        //3.将验证码保存在redis中
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
//        session.setAttribute("code",code);

        //4.发送验证码(这里直接用log输出)
        log.debug("验证码为：{}", code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("请输入正确格式的手机号");
        }
//        //2.验证验证码是否正确
//        Object code = session.getAttribute("code");
//        String postCode = loginForm.getCode();
//        if (code == null || !code.toString().equals(postCode)) {
//            return Result.fail("验证码不正确");
//        }

        //2.验证验证码是否正确
        String code = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String postCode = loginForm.getCode();
        if (code == null || !code.equals(postCode)) {
            return Result.fail("验证码不正确");
        }

//        3.查看数据库是否有用户
        //这里需要学习mybatisplus的用法
        User user = query().eq("phone", phone).one();

        if (user == null) {
            //存入数据库
            user = createUserWithPhone(phone);

        }

//        //将user存放在session中
//        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        //将user存放在redis中
        String token = UUID.randomUUID().toString(true);
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(), CopyOptions.create().
                setIgnoreNullValue(true).setFieldValueEditor((fildName, fildValue) -> fildValue.toString()));

        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);

        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {

        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }

    @Override
    public Result logout(HttpServletRequest request) {
        String token = request.getHeader("authorization");
        Boolean success = stringRedisTemplate.delete(LOGIN_USER_KEY + token);
        if(success){
            return Result.ok(token);
        }
        return Result.fail("退出失败");

    }
}
