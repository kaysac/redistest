package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

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
    @Override
    public Result sendVerify(String phone, HttpSession session) {
        // 验证手机号
        if (!RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机格式无效");
        }

        // 生成验证码
        String verifyCode = RandomUtil.randomString(6);

        // 保存验证码到session
        session.setAttribute("session_verifyCode", verifyCode);

        // 发送验证码
        System.out.println(verifyCode);

        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {

        // 验证手机号
        if (!RegexUtils.isPhoneInvalid(loginForm.getPhone())) {
            return Result.fail("手机格式无效");
        }

        // 校验验证码
        if (!loginForm.getCode().equals(session.getAttribute("session_verifyCode"))) {
            return Result.fail("验证码错误");
        }

        // 查询用户
        User user = query().eq("phone", loginForm.getPhone()).one();
//        System.out.println("user:" + user);

        // 用户是否存在
        if (user == null) {
            // 不存在，创建新用户
            User userTemp = new User();
            userTemp.setPhone(loginForm.getPhone());
            userTemp.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
            user = userTemp;
            save(user);
        }

        // 保存用户到session
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        session.setAttribute("user", userDTO);

        return Result.ok();
    }
}
