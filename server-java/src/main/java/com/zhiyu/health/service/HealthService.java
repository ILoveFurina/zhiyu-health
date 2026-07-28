package com.zhiyu.health.service;

import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/** 健康探活：分别探测 PG 与 Redis，任一失败则整体 degraded */
@Service
public class HealthService {

    private final JdbcTemplate jdbcTemplate;
    private final RedisConnectionFactory redisConnectionFactory;

    public HealthService(JdbcTemplate jdbcTemplate, RedisConnectionFactory redisConnectionFactory) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisConnectionFactory = redisConnectionFactory;
    }

    public Map<String, Object> check() {
        Map<String, Object> services = new LinkedHashMap<>();
        services.put("postgres", Map.of("status", probePostgres()));
        services.put("redis", Map.of("status", probeRedis()));

        boolean allOk = services.values().stream()
                .allMatch(v -> "ok".equals(((Map<?, ?>) v).get("status")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", allOk ? "ok" : "degraded");
        result.put("services", services);
        return result;
    }

    private String probePostgres() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return "ok";
        } catch (Exception e) {
            return "error";
        }
    }

    private String probeRedis() {
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            connection.ping();
            return "ok";
        } catch (Exception e) {
            return "error";
        }
    }
}
