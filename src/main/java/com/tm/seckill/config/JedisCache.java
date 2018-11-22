package com.tm.seckill.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPool;

@Component
public class JedisCache {

    @Autowired
    private JedisPool jedisPool;

}
