package com.hmdp.utils;

import com.hmdp.dto.UserDTO;

public class UserHolder {
    // 声明ThreadLocal，存UserDTO，每个线程独有一份数据
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();
    // 保存用户信息到当前线程
    public static void saveUser(UserDTO user){
        tl.set(user);
    }
    // 获取当前线程里的用户信息
    public static UserDTO getUser(){
        return tl.get();
    }
    // 移除当前线程里的用户信息（必须！防止内存泄漏）
    public static void removeUser(){
        tl.remove();
    }
}
