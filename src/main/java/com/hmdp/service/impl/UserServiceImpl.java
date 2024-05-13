package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.db.Session;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("无效的手机号！");
        }
        //2.校验验证码
        String code = loginForm.getCode();
        Object sessionCode = session.getAttribute("code");
        if (sessionCode == null || !sessionCode.toString().equals(code)) {
            //3.不一致，让其重新提交，即报错
            //这里选择反向校验，把错误过滤掉，剩下的就正确的，可以避免if嵌套，看起来更优雅、方便阅读
            return Result.fail("验证码错误！");
        }
        //4.一致，根据手机号查询用户（数据库的表是tb_user）这里extends了ServiceImpl<UserMapper, User>
        //  我们可以直接利用MybatisPlus的query（）来进行单表的查询。
        User user = query().eq("phone", phone).one();
        //5.判断用户是否存在
        if (user == null) {
            //6.不存在，创建新用户并保存
            user = createUserWithPhone(phone);
        }
        //7.保存用户信息到session
        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        return Result.ok();
    }

    private User createUserWithPhone(String phone) {
        //1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(6));
        //2.保存到数据库
        save(user);
        return user;
    }

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2.如果不符合，返回错误信息
            return Result.fail("无效的手机号！");
        }
        //3.如果符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

        //4.保存验证码到Session
        session.setAttribute("code", code);
        //5.发送验证码
        log.debug("发送验证码成功， 验证码：{}", code);
        //6.返回值
        return Result.ok();
    }
}
