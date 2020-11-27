package com.lh.cache.factory;

import net.sf.ehcache.CacheManager;
import net.sf.ehcache.event.CacheManagerEventListener;
import net.sf.ehcache.event.CacheManagerEventListenerFactory;

import java.util.Properties;

/**
 * @Author haol
 * @Date 20-11-23 11:31
 * @Version 1.0
 * @Desciption rmi缓存管理事件监听工厂
 */
public class RmiCacheManagerEventListenerFactory extends CacheManagerEventListenerFactory {

    @Override
    public CacheManagerEventListener createCacheManagerEventListener(CacheManager cacheManager, Properties properties) {
        return null;
    }
}
