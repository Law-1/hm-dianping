package com.hmdp.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MybatisConfig {
/*
    MyBatis-Plus 分页需要**分页插件**
    ### . 返回值 `Page<Blog> page`

    `Page` 是 MP 封装的分页对象，里面包含很多分页信息，不止数据：

    - `page.getRecords()`  **当前页的记录列表（我们最常用），就是这一页的 Blog 集合**
    - `page.getTotal()`：总一共有多少条数据
    - `page.getPages()`：总共有多少页
    - `page.getCurrent()`：当前页码
    - `page.getSize()`：每页条数
    */
    //todo: 分页插件
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
