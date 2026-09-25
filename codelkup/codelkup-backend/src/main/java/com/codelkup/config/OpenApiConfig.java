package com.codelkup.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI（Swagger UI）配置。
 *
 * @author 刘
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI codelkupOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Codelkup API")
                        .description("面向在校 CS 学生的技术社区论坛 —— 后端接口文档")
                        .version("v0.1")
                        .contact(new Contact().name("刘"))
                        .license(new License().name("MIT")));
    }
}
