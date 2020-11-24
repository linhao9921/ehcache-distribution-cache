package net.sf.ehcache.distribution;

import net.sf.ehcache.CacheException;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Ehcache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.io.IOException;
import java.rmi.NotBoundException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * @Author haol<haol @ jumei.com>
 * @Date 20-11-23 18:42
 * @Version 1.0
 * @Desciption
 */
public class RedisRegisterCenterRMICacheManagerPeerProvider extends RMICacheManagerPeerProvider implements CacheManagerPeerProvider {

    private static final Logger LOG = LoggerFactory.getLogger(RedisRegisterCenterRMICacheManagerPeerProvider.class.getName());

    /**
     * One tenth of a second, in ms
     */
    private static final int SHORT_DELAY = 100;

    private final RedisRegisterCenterKeepaliveHeartbeatReceiver heartbeatReceiver;
    private final RedisRegisterCenterKeepaliveHeartbeatSender heartbeatSender;

    private final JedisPool registerCenter;

    /**
     * Creates and starts a redis register center peer provider
     * @param cacheManager 缓存管理器
     * @param redisConfigCenterHost redis注册中心地址
     * @param redisConfigCenterPort redis注册中心端口
     * @param registerCenterKey redis注册中心key
     * @param socketTimeout redis的连接超时时间
     * @param heartBeatSenderInterval 设置发送心跳间隔时间，默认5000ms(RedisRegisterCenterKeepaliveHeartbeatSender.DEFAULT_HEARTBEAT_INTERVAL)
     * @param heartBeatStaleTime 设置节点存活时间，默认-1， 当小于0时使用（2*HeartBeatInterval）+100）毫秒
     * @param heartBeatReceiverInterval 设置接受心跳间隔时间，默认5000ms(RedisRegisterCenterKeepaliveHeartbeatReceiver.DEFAULT_HEARTBEAT_INTERVAL)
     */
    public RedisRegisterCenterRMICacheManagerPeerProvider(CacheManager cacheManager, String redisConfigCenterHost, int redisConfigCenterPort
            , String registerCenterKey, int socketTimeout, Long heartBeatSenderInterval, Long heartBeatStaleTime, Long heartBeatReceiverInterval) {
        super(cacheManager);

        // Jedis配置
        JedisPoolConfig jedisPoolConfig = new JedisPoolConfig();
        jedisPoolConfig.setMaxTotal(1024);
        jedisPoolConfig.setMaxIdle(100);
        jedisPoolConfig.setMaxWaitMillis(100);
        jedisPoolConfig.setTestOnBorrow(false);//jedis 第一次启动时，会报错
        jedisPoolConfig.setTestOnReturn(true);

        // 初始化JedisPool
        this.registerCenter = new JedisPool(jedisPoolConfig, redisConfigCenterHost, redisConfigCenterPort, socketTimeout);

        // 初始化心跳接受者
        this.heartbeatReceiver = new RedisRegisterCenterKeepaliveHeartbeatReceiver(this, this.registerCenter, registerCenterKey);
        if (heartBeatReceiverInterval != null) {
            this.heartbeatReceiver.setHeartBeatReceiverInterval(heartBeatReceiverInterval);
        }
        // 初始化心跳发送者
        this.heartbeatSender = new RedisRegisterCenterKeepaliveHeartbeatSender(cacheManager, this.registerCenter, registerCenterKey);
        if (heartBeatSenderInterval != null) {
            this.heartbeatSender.setHeartBeatSenderInterval(heartBeatSenderInterval);
        }
        if (heartBeatStaleTime != null) {
            this.heartbeatSender.setHeartBeatStaleTime(heartBeatStaleTime);
        }
    }

    /**
     * Initial the heartbeat
     */
    @Override
    public void init() {
        this.heartbeatReceiver.init();
        this.heartbeatSender.init();
    }

    /**
     * Shutdown the heartbeat
     */
    @Override
    public final void dispose() {
        // 关闭心跳发送者和接收者
        this.heartbeatSender.dispose();
        this.heartbeatReceiver.dispose();

        // 本机（注册中心）下线
        this.offline();


        // 关闭redis连接池
        this.registerCenter.destroy();
    }

    /**
     * 本机（注册中心）下线
     */
    private void offline() {
        // 本机注册节点主动下线
        Set<String> rmiPeers = this.heartbeatSender.createCachePeersPayload();
        if (rmiPeers != null && rmiPeers.size() > 0) {
            this.heartbeatReceiver.removeFromRegisterCenter(this.heartbeatSender.translateRegisterCenterValue(rmiPeers));
        }
    }

    /**
     * 集群形成的时间到了。这取决于具体实施情况，差别很大。
     * @return
     */
    @Override
    public long getTimeForClusterToForm() {
        return this.heartbeatSender.getHeartBeatSenderInterval() * 2 + SHORT_DELAY;
    }

    /**
     * 成员注册
     * @param rmiUrl
     */
    @Override
    public final void registerPeer(String rmiUrl) {
        this.registerPeer(rmiUrl, new Date());
    }

    /**
     * 成员注册
     * @param rmiUrl
     * @param newDate 新的时间
     */
    final void registerPeer(String rmiUrl, Date newDate) {
        try {
            CachePeerEntry cachePeerEntry = (CachePeerEntry) peerUrls.get(rmiUrl);
            if (cachePeerEntry == null || stale(cachePeerEntry.getDate())) {
                // 如果是空或者已经过期了， 重新注册
                CachePeer cachePeer = lookupRemoteCachePeer(rmiUrl);
                cachePeerEntry = new CachePeerEntry(cachePeer, new Date());
                //synchronized due to peerUrls being a synchronizedMap
                peerUrls.put(rmiUrl, cachePeerEntry);
            } else {
                // 刷新时间即可
                cachePeerEntry.date = newDate;
            }
        } catch (IOException e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Unable to lookup remote cache peer for " + rmiUrl + ". Removing from peer list. Cause was: " + e.getMessage());
            }
            // 网络异常，直接移除该节点
            this.unregisterPeer(rmiUrl);
        } catch (NotBoundException e) {
            peerUrls.remove(rmiUrl);
            if (LOG.isDebugEnabled()) {
                LOG.debug("Unable to lookup remote cache peer for " + rmiUrl + ". Removing from peer list. Cause was: " + e.getMessage());
            }
        } catch (Throwable t) {
            LOG.error("Unable to lookup remote cache peer for " + rmiUrl
                    + ". Cause was not due to an IOException or NotBoundException which will occur in normal operation:" + t.getMessage());
        }
    }

    /**
     * 列表当前所有的远程成员对象
     * @param cache
     * @return
     * @throws CacheException
     */
    @Override
    public final synchronized List listRemoteCachePeers(Ehcache cache) throws CacheException {
        List<CachePeer> remoteCachePeers = new ArrayList<>();
        List<String> staleList = new ArrayList<>();

        synchronized (peerUrls) {
            for (Object o : peerUrls.keySet()) {
                String rmiUrl = (String) o;
                String rmiUrlCacheName = extractCacheName(rmiUrl);
                try {
                    if (!rmiUrlCacheName.equals(cache.getName())) {
                        continue;
                    }

                    // 处理当前成员
                    CachePeerEntry cachePeerEntry = (CachePeerEntry) peerUrls.get(rmiUrl);
                    Date date = cachePeerEntry.date;
                    if (!stale(date)) {
                        // 未过期
                        CachePeer cachePeer = cachePeerEntry.cachePeer;
                        remoteCachePeers.add(cachePeer);
                    } else {
                        LOG.debug("rmiUrl[{}] is stale. Either the remote peer is shutdown or the network connectivity has been interrupted. " +
                                "Will be removed from list of remote cache peers", rmiUrl);
                        // 记录不可用的机器地址, 未发送心跳
                        staleList.add(rmiUrl);
                    }
                } catch (Exception e) {
                    LOG.error(e.getMessage(), e);
                    throw new CacheException("Unable to list remote cache peers. Error was " + e.getMessage());
                }
            }

            // 必须在遍历完条目之后删除它们
            for (String rmiUrl : staleList) {
                peerUrls.remove(rmiUrl);
            }
        }

        return remoteCachePeers;
    }

    /**
     * 条目是否应被视为过时。这将取决于RMICacheManagerPeerProvider的类型。对于基于日期过时的实现，应重写此方法
     * @param date
     * @return
     */
    @Override
    protected final boolean stale(Date date) {
        long now = System.currentTimeMillis();
        return date.getTime() < (now - this.heartbeatSender.getHeartBeatStaleTime());
    }

    /**
     * Entry containing a looked up CachePeer and date
     */
    protected static final class CachePeerEntry {
        private final CachePeer cachePeer;
        private Date date;

        /**
         * 构造方法
         * @param cachePeer
         * @param date
         */
        public CachePeerEntry(CachePeer cachePeer, Date date) {
            this.cachePeer = cachePeer;
            this.date = date;
        }

        public CachePeer getCachePeer() {
            return cachePeer;
        }

        public Date getDate() {
            return date;
        }

        public void setDate(Date date) {
            this.date = date;
        }
    }
}
