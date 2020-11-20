package com.lh.cache.service.impl;

import com.lh.cache.dto.Message;
import com.lh.cache.service.TestCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * @Author haol<haol @ jumei.com>
 * @Date 20-11-20 11:03
 * @Version 1.0
 * @Desciption
 */
@Service("testCache")
public class TestCacheImpl implements TestCache {

    private static final Logger logger = LoggerFactory.getLogger(TestCacheImpl.class);


    @Override
    @CachePut(value = "message", key = "#message.id")
    public Message add(Message message) {
        logger.info("add========================{}", message);
        return message;
    }

    @Override
    @Cacheable(value = "message", key = "#id")
    public Message get(int id) {
        logger.info("get========================{}", id);
        return new Message(id, "测试ehcache缓存[id=" + id + "]");
    }

    @Override
    @CachePut(value = "message", key = "#message.id")
    public Message update(Message message) {
        logger.info("update========================{}", message);
        return message;
    }

    @Override
    @CacheEvict(value = "message", key = "#id")
    public boolean delete(int id) {
        logger.info("delete========================{}", id);
        return false;
    }
}
