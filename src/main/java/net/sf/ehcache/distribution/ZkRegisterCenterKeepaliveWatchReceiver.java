package net.sf.ehcache.distribution;

import net.sf.ehcache.CacheException;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.util.NamedThreadFactory;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.rmi.RemoteException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @Author haol<haol @ jumei.com>
 * @Date 20-11-25 15:01
 * @Version 1.0
 * @Desciption 基于zookeeper的自动发现接受者
 */
public final class ZkRegisterCenterKeepaliveWatchReceiver {

    private static final Logger LOG = LoggerFactory.getLogger(ZkRegisterCenterKeepaliveWatchReceiver.class.getName());

    private final ZkRegisterCenterRMICacheManagerPeerProvider peerProvider;
    private final String serverName;

    private PathChildrenCache serverRmiUrlPathChildrenCache;
    private ExecutorService processingThreadPool;
    private Set<String> rmiUrlsProcessingQueue = Collections.synchronizedSet(new HashSet<>());

    private volatile boolean stopped;

    /**
     * 构造方法
     * @param peerProvider
     * @param serverName
     */
    ZkRegisterCenterKeepaliveWatchReceiver(ZkRegisterCenterRMICacheManagerPeerProvider peerProvider, String serverName) {
        this.peerProvider = peerProvider;
        this.serverName = serverName;

        // 增加节点目录下的子节点创建、删除、更新操作的监听
        String path = peerProvider.processZnodePath(serverName);
        this.serverRmiUrlPathChildrenCache = new PathChildrenCache(peerProvider.getClient(), path, true);
        this.serverRmiUrlPathChildrenCache.getListenable().addListener(new ZkRegisterCenterReceiverListener());
    }

    /**
     * Initial
     */
    final void init() throws Exception {
        LOG.debug("initial ZkRegisterCenter watcher receiver called");
        this.serverRmiUrlPathChildrenCache.start();

        this.processingThreadPool = Executors.newCachedThreadPool(new NamedThreadFactory("ZKRegisterCenter keep-alive Watcher Receiver"));

        // 自动发现
        this.autoDiscover();
    }

    /**
     * 自动发现
     */
    private void autoDiscover() throws CacheException {
        try {
            // 拉取rmi服务注册列表
            String path = this.peerProvider.processZnodePath(this.serverName);
            // 创建数据节点
            List<String> rmiUrlProviders = this.peerProvider.getClient().getChildren().forPath(path);
            if (rmiUrlProviders != null && rmiUrlProviders.size() > 0) {
                LOG.debug("Get znode[{}] success. size: {}.", path, rmiUrlProviders.size());
                for (String rmiUrlProvider : rmiUrlProviders) {
                    LOG.debug("Get znode[{}] success. rmiUrlProvider: {}.", path, rmiUrlProvider);
                    byte[] data = this.peerProvider.getClient().getData().forPath(path + this.peerProvider.processZnodePath(rmiUrlProvider));
                    if (data != null && data.length > 0) {
                        String peerUrls = new String(data);
                        if (peerUrls.startsWith("Provider-")) {
                            if (self(peerUrls)) {
                                if (LOG.isDebugEnabled()) {
                                    LOG.debug("RedisRegisterCenter receiver peerUrls: {} is self, skip...", peerUrls);
                                }
                            } else {
                                LOG.debug("RedisRegisterCenter receiver peerUrls: {}.", peerUrls);
                                // 处理注册地址
                                processRmiUrls(peerUrls, PathChildrenCacheEvent.Type.CHILD_ADDED);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.error(e.getMessage(), e);
            throw new CacheException(e);
        }
    }

    /**
     * Shutdown the Watcher.
     */
    final void dispose() throws IOException {
        LOG.debug("dispose ZkRegisterCenter watcher receiver called");
        this.processingThreadPool.shutdownNow();
        this.serverRmiUrlPathChildrenCache.close();
        this.stopped = true;
    }

    /**
     * 处理注册地址
     * @param rmiUrls
     * @param type
     */
    private void processRmiUrls(String rmiUrls, final PathChildrenCacheEvent.Type type) {
        if (rmiUrlsProcessingQueue.contains(rmiUrls)) {
            LOG.debug("We are already processing these rmiUrls. Another heartbeat came before we finished: {}", rmiUrls);
            return;
        }

        if (processingThreadPool != null) {
            // 异步处理
            processingThreadPool.execute(() -> {
                try {
                    // Add the rmiUrls we are processing.
                    rmiUrlsProcessingQueue.add(rmiUrls);

                    for (StringTokenizer stringTokenizer = new StringTokenizer(rmiUrls
                            , PayloadUtil.URL_DELIMITER); stringTokenizer.hasMoreTokens();) {
                        if (stopped) {
                            return;
                        }
                        String rmiUrl = stringTokenizer.nextToken();

                        if(PathChildrenCacheEvent.Type.CHILD_ADDED.equals(type)
                                || PathChildrenCacheEvent.Type.CHILD_UPDATED.equals(type)) {
                            // 节点还存活，需要注册节点
                            registerNotification(rmiUrl);
                            if (!peerProvider.peerUrls.containsKey(rmiUrl)) {
                                LOG.debug("Aborting processing of rmiUrls since failed to add rmiUrl: {}", rmiUrl);
                                return;
                            }
                        } else if (PathChildrenCacheEvent.Type.CHILD_REMOVED.equals(type)){
                            // 节点已经失效，需要移除
                            unRegisterNotification(rmiUrl);
                            if (peerProvider.peerUrls.containsKey(rmiUrl)) {
                                LOG.debug("Aborting processing of rmiUrls since failed to remove rmiUrl: {}", rmiUrl);
                                return;
                            }
                        }
                    }
                } finally {
                    // Remove the rmiUrls we just processed
                    rmiUrlsProcessingQueue.remove(rmiUrls);
                }
            });
        }
    }

    /**
     * 校验获取的注册地址是不是自己
     * @param rmiUrls
     * @return
     */
    private boolean self(String rmiUrls) {
        CacheManager cacheManager = this.peerProvider.getCacheManager();
        CacheManagerPeerListener cacheManagerPeerListener = cacheManager.getCachePeerListener("RMI");
        if (cacheManagerPeerListener == null) {
            return false;
        }

        // 获取当前所有的注册成员
        List boundCachePeers = cacheManagerPeerListener.getBoundCachePeers();
        if (boundCachePeers == null || boundCachePeers.size() == 0) {
            return false;
        }

        CachePeer boundCachePeer = (CachePeer) boundCachePeers.get(0);
        try {
            String urlBase = boundCachePeer.getUrlBase();
            int baseUrlMatch = rmiUrls.indexOf(urlBase);
            return baseUrlMatch != -1;
        } catch (RemoteException e) {
            LOG.error("Error getting url base", e);
            return false;
        }
    }

    private void registerNotification(String rmiUrl) {
        this.peerProvider.registerPeer(rmiUrl);
    }

    private void unRegisterNotification(String rmiUrl) {
        this.peerProvider.unregisterPeer(rmiUrl);
    }

    /**
     * 基于zookeeper注册中心的接收监听器
     */
    private final class ZkRegisterCenterReceiverListener implements PathChildrenCacheListener {

        @Override
        public void childEvent(CuratorFramework curatorFramework, PathChildrenCacheEvent event) throws Exception {
            if (!stopped) {
                if (PathChildrenCacheEvent.Type.CHILD_ADDED.equals(event.getType())
                        || PathChildrenCacheEvent.Type.CHILD_UPDATED.equals(event.getType())
                        || PathChildrenCacheEvent.Type.CHILD_REMOVED.equals(event.getType())) {
                    String peerUrls = new String(event.getData().getData());
                    if (self(peerUrls)) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("RedisRegisterCenter receiver peerUrls: {} is self, skip...", peerUrls);
                        }
                    } else {
                        LOG.debug("RedisRegisterCenter receiver peerUrls: {}.", peerUrls);
                        // 处理注册地址
                        processRmiUrls(peerUrls, event.getType());
                    }
                }
            }
        }
    }
}
