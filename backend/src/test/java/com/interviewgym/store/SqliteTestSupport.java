package com.interviewgym.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 测试用 SQLite 数据源/存储工厂：临时文件库 + 手工执行 schema.sql（不依赖 Spring 上下文）。
 */
public final class SqliteTestSupport {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private SqliteTestSupport() {
    }

    /** 新建指向 dbFile 的 DataSource 并执行幂等 schema（同一文件可多次调用） */
    public static SimpleDriverDataSource newDataSource(Path dbFile) {
        SimpleDriverDataSource dataSource =
                new SimpleDriverDataSource(new org.sqlite.JDBC(), "jdbc:sqlite:" + dbFile.toAbsolutePath());
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        } catch (SQLException e) {
            throw new IllegalStateException("测试库 schema 初始化失败", e);
        }
        return dataSource;
    }

    public static SqliteInterviewStore open(Path dbFile) {
        return new SqliteInterviewStore(new JdbcTemplate(newDataSource(dbFile)), OBJECT_MAPPER);
    }
}
