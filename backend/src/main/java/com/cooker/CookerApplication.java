package com.cooker;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Cooker 应用启动入口。
 */
@SpringBootApplication
@MapperScan(basePackages = "com.cooker", annotationClass = Mapper.class)
public class CookerApplication {

    public static void main(String[] args) {
        SpringApplication.run(CookerApplication.class, args);
    }
}

