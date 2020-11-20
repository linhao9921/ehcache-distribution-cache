package com.lh.cache.service.impl;

import com.lh.cache.dto.Message;
import com.lh.cache.service.TestCache;
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


    @Override
    @CachePut(value = "message", key = "#message.id")
    public Message add(Message message) {
        System.out.println("add========================" + message);
        return message;
    }

    @Override
    @Cacheable(value = "message", key = "#id")
    public Message get(int id) {
        System.out.println("get========================" + id);
        return new Message(1, "测试ehcache缓存");
    }

    @Override
    @CachePut(value = "message", key = "#message.id")
    public Message update(Message message) {
        System.out.println("update========================" + message);
        return message;
    }

    @Override
    @CacheEvict(value = "message", key = "#id")
    public boolean delete(int id) {
        System.out.println("delete========================" + id);
        return false;
    }
}
