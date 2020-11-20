package com.lh.cache.service;

import com.lh.cache.dto.Message;

/**
 * @Author haol<haol @ jumei.com>
 * @Date 20-11-20 11:02
 * @Version 1.0
 * @Desciption
 */
public interface TestCache {

    Message add(Message message);

    Message get(int id);

    Message update(Message message);

    boolean delete(int id);
}
