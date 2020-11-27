package net.sf.ehcache.distribution;

import net.sf.ehcache.CacheManager;
import net.sf.ehcache.util.NamedThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Tuple;

import java.rmi.RemoteException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @Author haol
 * @Date 20-11-24 10:33
 * @Version 1.0
 * @Desciption 基于redis的注册中心心跳接收者
 */
public final class RedisRegisterCenterKeepaliveHeartbeatReceiver {

    private static final Logger LOG = LoggerFactory.getLogger(RedisRegisterCenterKeepaliveHeartbeatReceiver.class.getName());

    private static final int DEFAULT_HEARTBEAT_INTERVAL = 2500;
    private static final int MINIMUM_HEARTBEAT_INTERVAL = 1000;

    private final RedisRegisterCenterRMICacheManagerPeerProvider peerProvider;
    private final JedisPool registerCenter;
    private final String registerCenterKey;

    private RedisRegisterCenterReceiverThread receiverThread;
    private ExecutorService processingThreadPool;
    private Set<String> rmiUrlsProcessingQueue = Collections.synchronizedSet(new HashSet<>());

    private long heartBeatReceiverInterval = DEFAULT_HEARTBEAT_INTERVAL;

    private volatile boolean stopped;

    /**
     * 构造方法
     * @param peerProvider 成员提供者
     * @param registerCenter 注册中心
     */
    RedisRegisterCenterKeepaliveHeartbeatReceiver(RedisRegisterCenterRMICacheManagerPeerProvider peerProvider
            , JedisPool registerCenter, String registerCenterKey) {
        this.peerProvider = peerProvider;
        this.registerCenter = registerCenter;
        this.registerCenterKey = registerCenterKey;
    }

    /**
     * Initial
     */
    final void init() {
        LOG.debug("initial RedisRegisterCenter heartbeat receiver called");
        this.receiverThread = new RedisRegisterCenterReceiverThread();
        this.receiverThread.start();
        this.processingThreadPool = Executors.newCachedThreadPool(new NamedThreadFactory("RedisRegisterCenter keep-alive Heartbeat Receiver"));
    }

    /**
     * Shutdown the heartbeat.
     */
    final void dispose() {
        LOG.debug("dispose RedisRegisterCenter heartbeat receiver called");
        this.processingThreadPool.shutdownNow();
        this.stopped = true;
        this.receiverThread.interrupt();
    }

    /**
     * 获取心跳间隔时间
     * @return
     */
    public long getHeartBeatReceiverInterval() {
        return heartBeatReceiverInterval;
    }

    /**
     * 设置心跳间隔时间
     * @param heartBeatReceiverInterval
     */
    void setHeartBeatReceiverInterval(long heartBeatReceiverInterval) {
        if (heartBeatReceiverInterval < MINIMUM_HEARTBEAT_INTERVAL) {
            LOG.warn("Trying to set heartbeat interval too low. Using MINIMUM_HEARTBEAT_INTERVAL instead.");
            this.heartBeatReceiverInterval = MINIMUM_HEARTBEAT_INTERVAL;
        } else {
            this.heartBeatReceiverInterval = heartBeatReceiverInterval;
        }
    }

    /**
     * 从注册中心剔除无效的节点
     * @param rmiUrls
     */
    void removeFromRegisterCenter(String rmiUrls) {
        try (Jedis resource = registerCenter.getResource()) {
            // 从注册中心剔除过期的节点
            resource.zrem(registerCenterKey, rmiUrls);
        } catch (Exception e) {
            LOG.info("Unexpected throwable in run thread. Continuing..." + e.getMessage(), e);
        }
    }

    /**
     * 基于redis注册中心的接收线程
     */
    private final class RedisRegisterCenterReceiverThread extends Thread {
        /**
         * 构造方法
         */
        RedisRegisterCenterReceiverThread() {
            super("RedisRegisterCenter Heartbeat Receiver Thread");
            setDaemon(true);
        }

        @Override
        public void run() {
            try {
                while (!stopped) {
                    try (Jedis resource = registerCenter.getResource()) {
                        // 获取所有的ip注册列表
                        Set<Tuple> dataList = resource.zrangeByScoreWithScores(registerCenterKey, 0, System.currentTimeMillis());
                        if (dataList != null && dataList.size() > 0) {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("RedisRegisterCenter receiver peerUrls size: {}.", dataList.size());
                            }
                            for (Tuple tuple : dataList) {
                                // Contains a RMI URL of the form: "//" + hostName + ":" + port + "/" + cacheName;
                                String peerUrls = tuple.getElement();
                                if (self(peerUrls)) {
                                    if (LOG.isDebugEnabled()) {
                                        LOG.debug("RedisRegisterCenter receiver peerUrls: {} is self, skip...", peerUrls);
                                    }
                                    continue;
                                }

                                long date = (long) tuple.getScore();
                                LOG.debug("RedisRegisterCenter receiver peerUrls: {}.", peerUrls);
                                processRmiUrls(peerUrls, new Date(date));
                            }
                        } else {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("RedisRegisterCenter receiver peerUrls is empty.");
                            }
                        }
                    } catch (Exception e) {
                        LOG.info("Unexpected throwable in run thread. Continuing..." + e.getMessage(), e);
                    }

                    try {
                        sleep(heartBeatReceiverInterval);
                    } catch (InterruptedException e) {
                        if (!stopped) {
                            LOG.error("Error receiving heartbeat. Initial cause was " + e.getMessage(), e);
                        }
                    }
                }
            } catch (Throwable t) {
                LOG.error("RedisRegisterCenter receiver thread caught throwable. Cause was " + t.getMessage() + ". Continuing...");
            }
        }

        /**
         * 校验获取的注册地址是不是自己
         * @param rmiUrls
         * @return
         */
        private boolean self(String rmiUrls) {
            CacheManager cacheManager = peerProvider.getCacheManager();
            CacheManagerPeerListener cacheManagerPeerListener = cacheManager.getCachePeerListener("RMI");
            if (cacheManagerPeerListener != null) {
                // 获取当前所有的注册成员
                List boundCachePeers = cacheManagerPeerListener.getBoundCachePeers();
                if (boundCachePeers != null && boundCachePeers.size() > 0) {
                    try {
                        CachePeer boundCachePeer = (CachePeer) boundCachePeers.get(0);
                        String urlBase = boundCachePeer.getUrlBase();
                        int baseUrlMatch = rmiUrls.indexOf(urlBase);
                        return baseUrlMatch != -1;
                    } catch (RemoteException e) {
                        LOG.error("Error getting url base", e);
                        return false;
                    }
                }
            }
            return false;
        }

        /**
         * 处理注册地址
         * @param rmiUrls
         * @param date
         */
        private void processRmiUrls(final String rmiUrls, final Date date) {
            if (rmiUrlsProcessingQueue.contains(rmiUrls)) {
                LOG.debug("We are already processing these rmiUrls. Another heartbeat came before we finished: {}", rmiUrls);
                return;
            }

            if (processingThreadPool == null) {
                return;
            }

            // 异步处理
            processingThreadPool.execute(() -> {
                try {
                    // Add the rmiUrls we are processing.
                    rmiUrlsProcessingQueue.add(rmiUrls);

                    boolean staled = false;
                    for (StringTokenizer stringTokenizer = new StringTokenizer(rmiUrls
                            , PayloadUtil.URL_DELIMITER); stringTokenizer.hasMoreTokens();) {
                        if (stopped) {
                            return;
                        }
                        String rmiUrl = stringTokenizer.nextToken();

                        // 判断当前注册地址是否已经失效
                        if(peerProvider.stale(date)) {
                            // 节点已经失效，需要移除
                            unRegisterNotification(rmiUrl);

                            // 需要从注册中心移除
                            staled = true;
                            if (peerProvider.peerUrls.containsKey(rmiUrl)) {
                                LOG.debug("Aborting processing of rmiUrls since failed to remove rmiUrl: {}", rmiUrl);
                                return;
                            }
                        } else {
                            // 节点还存活，需要注册节点
                            registerNotification(rmiUrl, date);
                            if (!peerProvider.peerUrls.containsKey(rmiUrl)) {
                                LOG.debug("Aborting processing of rmiUrls since failed to add rmiUrl: {}", rmiUrl);
                                return;
                            }
                        }
                    }

                    if (staled) {
                        // 从注册中心剔除无效的节点
                        removeFromRegisterCenter(rmiUrls);
                    }
                } finally {
                    // Remove the rmiUrls we just processed
                    rmiUrlsProcessingQueue.remove(rmiUrls);
                }
            });
        }

        /**
         * 注册通知
         * @param rmiUrl
         * @param date
         */
        private void registerNotification(String rmiUrl, Date date) {
            peerProvider.registerPeer(rmiUrl, date);
        }

        /**
         * 解除注册通知
         * @param rmiUrl
         */
        private void unRegisterNotification(String rmiUrl) {
            peerProvider.unregisterPeer(rmiUrl);
        }
    }
}
