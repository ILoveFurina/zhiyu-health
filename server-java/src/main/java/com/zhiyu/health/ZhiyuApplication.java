package com.zhiyu.health;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 智愈业务后端主类：唯一对外入口与唯一业务写入方 */
@SpringBootApplication
public class ZhiyuApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZhiyuApplication.class, args);
    }
}
