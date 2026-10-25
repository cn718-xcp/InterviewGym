package com.interviewgym.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * SQLite 库文件的父目录必须在 DataSource 首次建连（schema.sql 执行）前就绪，
 * 故用 BeanFactoryPostProcessor 在所有单例实例化之前创建目录，早于 Hikari 建连。
 */
@Configuration
class SqliteDirectoryInitializer {

    @Bean
    static BeanFactoryPostProcessor sqliteDirectoryPreparer(Environment environment) {
        return beanFactory -> prepare(environment.getProperty("spring.datasource.url"));
    }

    private static void prepare(String url) {
        if (url == null || !url.startsWith("jdbc:sqlite:")) {
            return;
        }
        String file = url.substring("jdbc:sqlite:".length());
        int query = file.indexOf('?');
        if (query >= 0) {
            file = file.substring(0, query);
        }
        try {
            Path parent = Path.of(file).toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (InvalidPathException | IOException e) {
            throw new IllegalStateException("SQLite 数据目录不可创建，请检查 spring.datasource.url", e);
        }
    }
}
