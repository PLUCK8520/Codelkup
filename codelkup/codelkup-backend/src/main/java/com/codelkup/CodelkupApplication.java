package com.codelkup;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Codelkup 后端启动类。
 *
 * @author 刘
 */
@SpringBootApplication
@MapperScan("com.codelkup.module.**.mapper")
@EnableAsync          // 异步事件（计数落库 / 通知）
@EnableScheduling     // 定时任务（热度分快照，V0.2）
public class CodelkupApplication {

    public static void main(String[] args) {
        SpringApplication.run(CodelkupApplication.class, args);
    }
}
