package net.sf.ehcache.distribution;

import net.sf.ehcache.CacheException;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Ehcache;
import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * @Author haol
 * @Date 20-11-25 11:43
 * @Version 1.0
 * @Desciption
 */
public class ZkRegisterCenterRMICacheManagerPeerProvider extends RMICacheManagerPeerProvider implements CacheManagerPeerProvider {

    private static final Logger LOG = LoggerFactory.getLogger(ZkRegisterCenterRMICacheManagerPeerProvider.class.getName());

    private final ZkRegisterCenterKeepaliveWatchReceiver watchReceiver;
    private final ZkRegisterCenterKeepaliveRegisterSender registerSender;

    private final CuratorFramework client;

    /**
     * Creates and starts a zk register center peer provider
     * @param cacheManager 缓存管理器
     * @param zkConfigCenterAddress 注册中心地址
     * @param sessionTimeoutMs 注册中心session执行超时时间
     * @param connectionTimeoutMs 注册中心连接超时时间
     * @param retryPolicyBaseSleepTimeMs 注册中心重试策略基础睡眠时间
     * @param retryPolicyMaxRetries 注册中心重试策略最大重试次数
     * @param zkConfigCenterNamespace 注册中心的隔离名称
     * @param serverName 注册中心的服务名
     */
    ZkRegisterCenterRMICacheManagerPeerProvider(CacheManager cacheManager, String zkConfigCenterAddress
            , int sessionTimeoutMs, int connectionTimeoutMs, int retryPolicyBaseSleepTimeMs
            , int retryPolicyMaxRetries, String zkConfigCenterNamespace, String serverName) {
        super(cacheManager);

        // 设置重试次数
        RetryPolicy retryPolicy = new ExponentialBackoffRetry(retryPolicyBaseSleepTimeMs, retryPolicyMaxRetries);

        this.client = CuratorFrameworkFactory.builder()
                .connectString(zkConfigCenterAddress)           // zk链接地址
                .sessionTimeoutMs(sessionTimeoutMs)             // 会话超时时间
                .connectionTimeoutMs(connectionTimeoutMs)       // 连接超时时间
                .retryPolicy(retryPolicy)
                .namespace(zkConfigCenterNamespace)             // 包含隔离的名称
                .build();

        // 初始化基于zookeeper的自动发现接受者
        this.watchReceiver = new ZkRegisterCenterKeepaliveWatchReceiver(this, serverName);
        // 初始化基于zookeeper的自动注册接受者
        this.registerSender = new ZkRegisterCenterKeepaliveRegisterSender(this, serverName);
    }

    @Override
    public void init() throws CacheException {
        // 启动zk客户端
        this.client.start();

        try {
            // 初始化基于zookeeper的自动注册发送者
            this.registerSender.init();
            // 初始化基于zookeeper的自动发现接受者
            this.watchReceiver.init();
        } catch (Exception e) {
            LOG.error(e.getMessage(), e);
            throw new CacheException(e);
        }
    }

    @Override
    public void dispose() throws CacheException {
        try {
            // 销毁基于zookeeper的自动发现接受者
            this.watchReceiver.dispose();
            // 基于zookeeper的自动注册发送者
            this.registerSender.dispose();
        } catch (IOException e) {
            // 关闭zk的客户端
            this.client.close();

            LOG.error(e.getMessage(), e);
            throw new CacheException(e);
        }

        // 关闭zk的客户端
        this.client.close();
    }

    @Override
    public long getTimeForClusterToForm() {
        return 0;
    }

    @Override
    public final void registerPeer(String rmiUrl) {
        // 存储地址
        peerUrls.put(rmiUrl, new Date());
    }

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
                    Date date = (Date) peerUrls.get(rmiUrl);
                    if (!stale(date)) {
                        CachePeer cachePeer;
                        try {
                            cachePeer = lookupRemoteCachePeer(rmiUrl);
                            remoteCachePeers.add(cachePeer);
                        } catch (Exception e) {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("Looking up rmiUrl " + rmiUrl + " through exception " + e.getMessage()
                                        + ". This may be normal if a node has gone offline. Or it may indicate network connectivity difficulties", e);
                            }
                        }
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

    @Override
    protected boolean stale(Date date) {
        return false;
    }

    String processZnodePath(String path){
        return path.startsWith("/") ? path : "/" + path;
    }

    CuratorFramework getClient() {
        return client;
    }
}
