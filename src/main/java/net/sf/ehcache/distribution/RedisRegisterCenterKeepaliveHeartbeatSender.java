package net.sf.ehcache.distribution;

import net.sf.ehcache.CacheManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.rmi.RemoteException;
import java.util.*;

/**
 * @Author haol<haol @ jumei.com>
 * @Date 20-11-24 11:32
 * @Version 1.0
 * @Desciption 基于redis的注册中心心跳发送者
 */
public final class RedisRegisterCenterKeepaliveHeartbeatSender {

    private static final Logger LOG = LoggerFactory.getLogger(RedisRegisterCenterKeepaliveHeartbeatSender.class.getName());

    private static final int DEFAULT_HEARTBEAT_INTERVAL = 5000;
    private static final int MINIMUM_HEARTBEAT_INTERVAL = 1000;
    private static final int ONE_HUNDRED_MS = 100;

    private final CacheManager cacheManager;
    private final JedisPool registerCenter;
    private final String registerCenterKey;

    private RedisRegisterCenterSenderThread senderThread;
    private long heartBeatSenderInterval = DEFAULT_HEARTBEAT_INTERVAL;
    private long heartBeatStaleTime = -1;

    private volatile boolean stopped;

    /**
     * 构造方法
     * @param cacheManager
     * @param registerCenter 注册中心
     * @param registerCenterKey 注册中心的key
     */
    public RedisRegisterCenterKeepaliveHeartbeatSender(CacheManager cacheManager, JedisPool registerCenter, String registerCenterKey) {
        this.cacheManager = cacheManager;
        this.registerCenter = registerCenter;
        this.registerCenterKey = registerCenterKey;
    }

    /**
     * Initial
     */
    final void init() {
        LOG.debug("initial RedisRegisterCenter heartbeat sender called");
        this.senderThread = new RedisRegisterCenterSenderThread();
        this.senderThread.start();
    }

    /**
     * Shutdown this heartbeat sender
     */
    final synchronized void dispose() {
        this.stopped = true;
        notifyAll();
        this.senderThread.interrupt();
    }

    /**
     * 获取心跳间隔时间
     * @return
     */
    long getHeartBeatSenderInterval() {
        return heartBeatSenderInterval;
    }

    /**
     * 设置心跳间隔时间
     * @param heartBeatSenderInterval
     */
    void setHeartBeatSenderInterval(long heartBeatSenderInterval) {
        if (heartBeatSenderInterval < MINIMUM_HEARTBEAT_INTERVAL) {
            LOG.warn("Trying to set heartbeat interval too low. Using MINIMUM_HEARTBEAT_INTERVAL instead.");
            this.heartBeatSenderInterval = MINIMUM_HEARTBEAT_INTERVAL;
        } else {
            this.heartBeatSenderInterval = heartBeatSenderInterval;
        }
    }

    /**
     * 返回心跳信号被视为过时的时间
     * @return
     */
    long getHeartBeatStaleTime() {
        if (this.heartBeatStaleTime < 0) {
            return (this.heartBeatSenderInterval * 2) + ONE_HUNDRED_MS;
        } else {
            return this.heartBeatStaleTime;
        }
    }

    /**
     * 将心跳停止时间设置为默认值（（2*HeartBeatSenderInterval）+100）毫秒。这对于测试很有用，但不建议用于生产。此方法是静态的，因此会影响所有用户的过期时间。
     * @param heartBeatStaleTime
     */
    void setHeartBeatStaleTime(long heartBeatStaleTime) {
        this.heartBeatStaleTime = heartBeatStaleTime;
    }

    /**
     * 创建本地的注册地址
     * @return
     */
    Set<String> createCachePeersPayload() {
        CacheManagerPeerListener cacheManagerPeerListener = cacheManager.getCachePeerListener("RMI");
        if (cacheManagerPeerListener == null) {
            LOG.warn("The RMICacheManagerPeerListener is missing. You need to configure a cacheManagerPeerListenerFactory" +
                    " with class=\"net.sf.ehcache.distribution.RMICacheManagerPeerListenerFactory\" in ehcache.xml.");
            return new HashSet<>();
        }
        Set<String> rmiUrls = new HashSet<>();
        // 获取当前所有的注册成员
        List boundCachePeers = cacheManagerPeerListener.getBoundCachePeers();
        if (boundCachePeers != null && boundCachePeers.size() > 0) {
            for (Object boundCachePeer : boundCachePeers) {
                CachePeer cachePeer = (CachePeer) boundCachePeer;
                try {
                    String rmiUrl = cachePeer.getUrl();
                    rmiUrls.add(rmiUrl);
                } catch (RemoteException e) {
                    LOG.error("This should never be thrown as it is called locally");
                }
            }
        }
        return rmiUrls;
    }

    /**
     * 构造注册地址,转换为注册中心存储的值
     * @param rmiPeers
     * @return
     */
    String translateRegisterCenterValue(Set<String> rmiPeers) {
        // 构造注册地址
        Iterator<String> it = rmiPeers.iterator();
        StringBuilder sb = new StringBuilder();
        for (;;) {
            String e = it.next();
            sb.append(e);
            if (!it.hasNext())
                break;
            sb.append(PayloadUtil.URL_DELIMITER);
        }
        return sb.toString();
    }

    /**
     * 基于redis注册中心的发送线程
     */
    private final class RedisRegisterCenterSenderThread extends Thread {
        /**
         * 构造方法
         */
        RedisRegisterCenterSenderThread() {
            super("RedisRegisterCenter Heartbeat Sender Thread");
            setDaemon(true);
        }

        @Override
        public void run() {
            while (!stopped) {
                try (Jedis resource = registerCenter.getResource()) {
                    Set<String> rmiPeers = createCachePeersPayload();
                    if (rmiPeers != null && rmiPeers.size() > 0) {
                        // 使用现在时间作为分数
                        long now = System.currentTimeMillis();
                        
                        // 往注册中心发送心跳信息，注册所有的rmi地址
                        resource.zadd(registerCenterKey, now, translateRegisterCenterValue(rmiPeers));
                    }
                } catch (Exception e) {
                    LOG.info("Unexpected throwable in run thread. Continuing..." + e.getMessage(), e);
                }

                try {
                    synchronized (this) {
                        wait(heartBeatSenderInterval);
                    }
                } catch (InterruptedException e) {
                    if (!stopped) {
                        LOG.error("Error receiving heartbeat. Initial cause was " + e.getMessage(), e);
                    }
                }
            }
        }
    }
}
