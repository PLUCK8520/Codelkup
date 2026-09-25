package com.codelkup.config;

import com.codelkup.security.CurrentUserArgumentResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Web MVC 配置。
 *
 * @author 刘
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final CurrentUserArgumentResolver currentUserArgumentResolver;

    @Value("${codelkup.upload.root:./uploads}")
    private String uploadRoot;

    @Value("${codelkup.upload.url-prefix:/uploads}")
    private String urlPrefix;

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserArgumentResolver);
    }

    /**
     * 上传文件的静态资源映射。
     *
     * <p>⚠️ 两点说明：
     * <ol>
     *   <li>{@code application.yml} 里配了 {@code spring.web.resources.add-mappings=false}，
     *       那是为了关闭 <b>classpath 默认静态目录</b> 的兜底 Handler（让 404 能正确抛出）。
     *       这里显式注册映射不受其影响 —— 显式声明的 ResourceHandler 依然生效。</li>
     *   <li>必须用 {@code file:} 前缀指向文件系统绝对路径。用相对路径会解析到
     *       进程工作目录，从 IDE 启动和从 jar 启动的结果会不一致。</li>
     * </ol>
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path root = Paths.get(uploadRoot).toAbsolutePath().normalize();
        String location = root.toUri().toString();   // 生成 file:/// 形式，跨平台安全

        registry.addResourceHandler(urlPrefix + "/**")
                .addResourceLocations(location)
                // 头像换成 UUID 新名，旧文件不会被复用，缓存可以放长
                .setCachePeriod(3600);

        log.info("静态资源映射：{} -> {}", urlPrefix + "/**", location);
    }
}
