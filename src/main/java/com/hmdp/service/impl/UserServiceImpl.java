package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendVerify(String phone, HttpSession session) {
        // 验证手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机格式无效");
        }

        // 生成验证码
        String verifyCode = RandomUtil.randomNumbers(6);

        // 保存验证码到session
        session.setAttribute("session_verifyCode", verifyCode);
        // 保存验证码到redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, verifyCode);
        stringRedisTemplate.expire(LOGIN_CODE_KEY + phone, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 发送验证码
        System.out.println(verifyCode);

        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {

        String phone = loginForm.getPhone();
        // 验证手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机格式无效");
        }

        // 校验验证码
        if (!loginForm.getCode().equals(stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone))) {
            return Result.fail("验证码错误");
        }
//        String code = (String) session.getAttribute("session_verifyCode");
//        if(!loginForm.getCode().equals(code)) {
//            return Result.fail("验证码错误");
//        }

        // 查询用户
        User user = query().eq("phone", phone).one();
//        System.out.println("user:" + user);

        // 用户是否存在
        if (user == null) {
            // 不存在，创建新用户
            User userTemp = new User();
            userTemp.setPhone(phone);
            userTemp.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
            user = userTemp;
            save(user);
        }

        // 保存用户到redis
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //session.setAttribute("user", userDTO);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue)->fieldValue.toString()));
        String token = UUID.randomUUID().toString();
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);

        return Result.ok(token);
    }
}
