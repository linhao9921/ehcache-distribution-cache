package net.sf.ehcache.distribution;

import net.sf.ehcache.CacheException;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.util.PropertyUtil;

import java.util.Properties;

/**
 * @Author haol
 * @Date 20-11-23 18:24
 * @Version 1.0
 * @Desciption 扩展RMICacheManagerPeerProviderFactory的PEER_DISCOVERY(扩展成员发现方式)
 */
public class RMICacheManagerExtendsPeerProviderFactory extends RMICacheManagerPeerProviderFactory {

    /**
     * The default timeout for cache replication for a single replication action.
     * This may need to be increased for large data transfers.
     */
    private static final int DEFAULT_SOCKET_TIMEOUT = 2000;
    private static final int DEFAULT_REDIS_REGISTER_CENTER_PORT = 6379;

    private static final String REDIS_REGISTER_CENTER_HOST = "redisRegisterCenterHost";
    private static final String REDIS_REGISTER_CENTER_PORT = "redisRegisterCenterPort";
    private static final String REDIS_REGISTER_CENTER_KEY = "redisRegisterCenterKey";
    private static final String SOCKET_TIMEOUT = "socketTimeout";
    private static final String HEARTBEAT_SENDER_INTERVAL = "heartBeatSenderInterval";
    private static final String HEARTBEAT_RECEIVER_INTERVAL = "heartBeatReceiverInterval";
    private static final String HEARTBEAT_STALE_TIME = "heartBeatStaleTime";

    private static final int DEFAULT_SESSION_TIMEOUT_MS = 5000;
    private static final int DEFAULT_CONNECTION_TIMEOUT_MS = 5000;
    private static final int DEFAULT_RETRY_POLICY_BASE_SLEEP_TIME_MS = 100;
    private static final int DEFAULT_RETRY_POLICY_MAX_RETRIES = 3;

    private static final String ZK_REGISTER_CENTER_ADDRESS = "zkRegisterCenterAddress";
    private static final String SESSION_TIMEOUT_MS = "sessionTimeoutMs";
    private static final String CONNECTION_TIMEOUT_MS = "connectionTimeoutMs";
    private static final String RETRY_POLICY_BASE_SLEEP_TIME_MS = "retryPolicyBaseSleepTimeMs";
    private static final String RETRY_POLICY_MAX_RETRIES = "retryPolicyMaxRetries";
    private static final String ZK_REGISTER_CENTER_NAMESPACE = "zkRegisterCenterNamespace";
    private static final String ZK_REGISTER_CENTER_SERVER_NAME = "serverName";

    private static final String PEER_DISCOVERY = "peerDiscovery";
    private static final String REDIS_AUTOMATIC_PEER_DISCOVERY = "redis_register_center_automatic";
    private static final String ZK_AUTOMATIC_PEER_DISCOVERY = "zk_register_center_automatic";

    @Override
    public CacheManagerPeerProvider createCachePeerProvider(CacheManager cacheManager, Properties properties) throws CacheException {
        String peerDiscovery = PropertyUtil.extractAndLogProperty(PEER_DISCOVERY, properties);
        // 判断是否使用基于redis的自动发现
        if (REDIS_AUTOMATIC_PEER_DISCOVERY.equalsIgnoreCase(peerDiscovery)) {
            return createRedisAutomaticallyConfiguredCachePeerProvider(cacheManager, properties);
        } else if (ZK_AUTOMATIC_PEER_DISCOVERY.equalsIgnoreCase(peerDiscovery)) {
            return createZkAutomaticallyConfiguredCachePeerProvider(cacheManager, properties);
        }

        return super.createCachePeerProvider(cacheManager, properties);
    }

    /**
     * 创建基于redis自动发现的缓存管理成员提供者
     * @param cacheManager
     * @param properties
     * @return
     */
    private CacheManagerPeerProvider createRedisAutomaticallyConfiguredCachePeerProvider(CacheManager cacheManager, Properties properties) {
        // 注册中心地址
        String redisConfigCenterHost = getStringConfig(properties, REDIS_REGISTER_CENTER_HOST);

        // 注册中心端口
        int redisConfigCenterPort = getIntConfig(properties, REDIS_REGISTER_CENTER_PORT, DEFAULT_REDIS_REGISTER_CENTER_PORT);

        // 注册中心key
        String redisConfigCenterKey = getStringConfig(properties, REDIS_REGISTER_CENTER_KEY);

        // 注册中心socket执行超时时间
        int socketTimeout = getIntConfig(properties, SOCKET_TIMEOUT, DEFAULT_SOCKET_TIMEOUT);

        // 注册中心跳（发送）时间
        String heartBeatSenderIntervalString = PropertyUtil.extractAndLogProperty(HEARTBEAT_SENDER_INTERVAL, properties);
        Long heartBeatSenderInterval = null;
        if (heartBeatSenderIntervalString != null && heartBeatSenderIntervalString.length() > 0) {
            heartBeatSenderInterval = Long.valueOf(heartBeatSenderIntervalString);
        }

        // 节点超时时间
        String heartBeatStaleTimeString = PropertyUtil.extractAndLogProperty(HEARTBEAT_STALE_TIME, properties);
        Long heartBeatStaleTime = null;
        if (heartBeatStaleTimeString != null && heartBeatStaleTimeString.length() > 0) {
            heartBeatStaleTime = Long.valueOf(heartBeatStaleTimeString);
        }

        // 注册中心跳（发送）时间
        String heartBeatReceiverIntervalString = PropertyUtil.extractAndLogProperty(HEARTBEAT_RECEIVER_INTERVAL, properties);
        Long heartBeatReceiverInterval = null;
        if (heartBeatReceiverIntervalString != null && heartBeatReceiverIntervalString.length() > 0) {
            heartBeatReceiverInterval = Long.valueOf(heartBeatReceiverIntervalString);
        }

        return new RedisRegisterCenterRMICacheManagerPeerProvider(cacheManager, redisConfigCenterHost, redisConfigCenterPort
                , redisConfigCenterKey, socketTimeout, heartBeatSenderInterval, heartBeatStaleTime, heartBeatReceiverInterval);
    }

    /**
     * 创建基于zk自动发现的缓存管理成员提供者
     * @param cacheManager
     * @param properties
     * @return
     */
    private CacheManagerPeerProvider createZkAutomaticallyConfiguredCachePeerProvider(CacheManager cacheManager, Properties properties) {
        // 注册中心地址
        String zkConfigCenterAddress = getStringConfig(properties, ZK_REGISTER_CENTER_ADDRESS);

        // 注册中心session执行超时时间
        int sessionTimeoutMs = getIntConfig(properties, SESSION_TIMEOUT_MS, DEFAULT_SESSION_TIMEOUT_MS);

        // 注册中心连接超时时间
        int connectionTimeoutMs = getIntConfig(properties, CONNECTION_TIMEOUT_MS, DEFAULT_CONNECTION_TIMEOUT_MS);

        // 注册中心重试策略基础睡眠时间
        int retryPolicyBaseSleepTimeMs = getIntConfig(properties, RETRY_POLICY_BASE_SLEEP_TIME_MS, DEFAULT_RETRY_POLICY_BASE_SLEEP_TIME_MS);

        // 注册中心重试策略最大重试次数
        int retryPolicyMaxRetries = getIntConfig(properties, RETRY_POLICY_MAX_RETRIES, DEFAULT_RETRY_POLICY_MAX_RETRIES);

        // 注册中心的隔离名称
        String zkConfigCenterNamespace = getStringConfig(properties, ZK_REGISTER_CENTER_NAMESPACE);

        // 注册中心的服务名称
        String zkConfigCenterServerName = getStringConfig(properties, ZK_REGISTER_CENTER_SERVER_NAME);

        return new ZkRegisterCenterRMICacheManagerPeerProvider(cacheManager, zkConfigCenterAddress
                , sessionTimeoutMs, connectionTimeoutMs, retryPolicyBaseSleepTimeMs
                , retryPolicyMaxRetries, zkConfigCenterNamespace, zkConfigCenterServerName);
    }

    private String getStringConfig(Properties properties, String key) throws CacheException {
        String config = PropertyUtil.extractAndLogProperty(key, properties);
        if (config == null || config.length() == 0) {
            throw new CacheException("The " + key + "\'s config value required");
        }
        return config.trim();
    }

    private int getIntConfig(Properties properties, String key, int defaultVal) {
        String config = PropertyUtil.extractAndLogProperty(key, properties);
        int configInt;
        if (config == null || config.length() == 0) {
            configInt = defaultVal;
        } else {
            configInt = Integer.valueOf(config);
        }
        return configInt;
    }
}
