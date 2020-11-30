package net.sf.ehcache.distribution;

import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.KV;
import io.etcd.jetcd.KeyValue;
import io.etcd.jetcd.Watch;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.options.GetOption;
import io.etcd.jetcd.options.WatchOption;
import io.etcd.jetcd.watch.WatchEvent;
import io.etcd.jetcd.watch.WatchResponse;
import net.sf.ehcache.CacheException;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.util.NamedThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;
import java.rmi.RemoteException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @Author haol
 * @Date 20-11-27 18:04
 * @Version 1.0
 * @Desciption 基于etcd的自动发现接受者
 */
public final class EtcdRegisterCenterKeepaliveWatchReceiver {

    private static final Logger LOG = LoggerFactory.getLogger(EtcdRegisterCenterKeepaliveWatchReceiver.class.getName());

    private static final Charset DEFAULT_CHARSET = Charset.forName("UTF-8");

    private final EtcdRegisterCenterRMICacheManagerPeerProvider peerProvider;
    private final String serverName;

    private Watch watchClient;
    private ExecutorService processingThreadPool;
    private Set<String> rmiUrlsProcessingQueue = Collections.synchronizedSet(new HashSet<>());
    private final ConcurrentHashMap<String, String> pathPeerUrlMaps = new ConcurrentHashMap<>();

    private volatile boolean stopped;

    /**
     * 构造方法
     * @param peerProvider
     * @param serverName
     */
    EtcdRegisterCenterKeepaliveWatchReceiver(EtcdRegisterCenterRMICacheManagerPeerProvider peerProvider, String serverName) {
        this.peerProvider = peerProvider;
        this.serverName = serverName;
    }

    /**
     * Initial
     */
    final void init() throws Exception {
        LOG.debug("initial EtcdRegisterCenter watcher receiver called");

        this.processingThreadPool = Executors.newCachedThreadPool(new NamedThreadFactory("EtcdRegisterCenter keep-alive Watcher Receiver"));

        this.pathPeerUrlMaps.clear();

        // 自动发现
        this.autoDiscover();

        // 开启节点监听
        this.addWatchListener();
    }

    /**
     * 开启监听
     * @throws CacheException
     */
    private void addWatchListener() throws CacheException {
        try {
            this.watchClient = this.peerProvider.getClient().getWatchClient();
            String path = this.peerProvider.processNodePath(this.serverName);
            WatchOption watchOption = WatchOption.newBuilder()
                    .withPrevKV(true)
                    .withProgressNotify(true)
                    .withPrefix(ByteSequence.from(this.serverName, DEFAULT_CHARSET))   // 监听指定前缀
                    .withNoPut(false)                                                  // 监听put
                    .withNoDelete(false)                                               // 监听delete
                    .build();
            this.watchClient.watch(ByteSequence.from(path, DEFAULT_CHARSET), watchOption, new WatcherListener());
        } catch (Exception e) {
            LOG.error(e.getMessage(), e);
            throw new CacheException(e);
        }
    }

    /**
     * Shutdown the Watcher.
     */
    final void dispose() {
        LOG.debug("dispose EtcdRegisterCenter watcher receiver called");
        this.processingThreadPool.shutdownNow();
        this.stopped = true;

        if (this.watchClient != null) {
            this.watchClient.close();
        }
        this.pathPeerUrlMaps.clear();
    }

    /**
     * 自动发现
     */
    private void autoDiscover() throws CacheException {
        try {
            // 拉取rmi服务注册列表
            String path = this.peerProvider.processNodePath(this.serverName);
            try (KV kvClient = this.peerProvider.getClient().getKVClient()) {
                ByteSequence key = ByteSequence.from(path, DEFAULT_CHARSET);

                // 采用前缀过滤方式获取当前服务的所有注册列表
                GetOption getOption = GetOption.newBuilder().withPrefix(ByteSequence.from(this.serverName, DEFAULT_CHARSET)).build();
                CompletableFuture<GetResponse> getFuture = kvClient.get(key, getOption);
                GetResponse getResponse = getFuture.get();
                LOG.debug("Get lease[{}] success. size: {}.", path, getResponse.getCount());

                List<KeyValue> keyValues = getResponse.getKvs();
                for (KeyValue keyValue : keyValues) {
                    String rmiUrlPath = new String(keyValue.getKey().getBytes());
                    String rmiUrlProvider = new String(keyValue.getValue().getBytes());
                    long leaseSeconds = keyValue.getLease();

                    // 存储ETCD注册中心上的地址映射
                    this.pathPeerUrlMaps.put(rmiUrlPath, rmiUrlProvider);
                    if (self(rmiUrlProvider)) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("EtcdRegisterCenter receiver peerUrls: {} is self, leaseSeconds: {}, skip...", rmiUrlProvider, leaseSeconds);
                        }
                    } else {
                        LOG.debug("EtcdRegisterCenter receiver peerUrls: {}, leaseSeconds: {}.", rmiUrlProvider, leaseSeconds);
                        // 处理注册地址
                        processRmiUrls(rmiUrlProvider, WatchEvent.EventType.PUT);
                    }
                }
            } catch (Exception e) {
                LOG.error(e.getMessage(), e);
                throw new CacheException(e);
            }
        } catch (Exception e) {
            LOG.error(e.getMessage(), e);
            throw new CacheException(e);
        }
    }

    /**
     * 处理注册地址
     * @param rmiUrls
     * @param eventType
     */
    private void processRmiUrls(String rmiUrls, WatchEvent.EventType eventType) {
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

                        if(WatchEvent.EventType.PUT.equals(eventType)) {
                            // 节点还存活，需要注册节点
                            registerNotification(rmiUrl);
                            if (!peerProvider.peerUrls.containsKey(rmiUrl)) {
                                LOG.debug("Aborting processing of rmiUrls since failed to add rmiUrl: {}", rmiUrl);
                                return;
                            }
                        } else if (WatchEvent.EventType.DELETE.equals(eventType)){
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
        if (cacheManagerPeerListener != null) {
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
                LOG.error("Error ectd getting url base", e);
                return false;
            }
        }
        return false;
    }

    private void registerNotification(String rmiUrl) {
        this.peerProvider.registerPeer(rmiUrl);
    }

    private void unRegisterNotification(String rmiUrl) {
        this.peerProvider.unregisterPeer(rmiUrl);
    }

    /**
     * 节点监听器
     */
    private final class WatcherListener implements Watch.Listener {

        @Override
        public void onNext(WatchResponse response) {
            if (!stopped) {
                List<WatchEvent> events = response.getEvents();
                if (events != null && events.size() > 0) {
                    for (WatchEvent event : events) {
                        if (WatchEvent.EventType.PUT == event.getEventType()
                            || WatchEvent.EventType.DELETE == event.getEventType()) {
                            String rmiUrlPath = new String(event.getKeyValue().getKey().getBytes());
                            String rmiUrlProvider = new String(event.getKeyValue().getValue().getBytes());
                            long leaseSeconds = event.getKeyValue().getLease();

                            if (WatchEvent.EventType.DELETE == event.getEventType()) {
                                // 由于删除的时候没有数据返回，从缓存中获取
                                if (rmiUrlProvider.length() == 0) {
                                    // 尝试从缓存中获取
                                    rmiUrlProvider = pathPeerUrlMaps.get(rmiUrlPath);
                                    if (rmiUrlProvider == null || rmiUrlProvider.length() == 0) {
                                        // 未取到数据
                                        LOG.warn("EtcdRegisterCenter receiver eventType: DELETE, no found peerUrls: {}, skip...", rmiUrlProvider);
                                        return;
                                    }
                                    // 删除本地ETCD注册中心上的地址映射
                                    pathPeerUrlMaps.remove(rmiUrlPath);
                                }
                            } else {
                                // 存储ETCD注册中心上的地址映射
                                pathPeerUrlMaps.put(rmiUrlPath, rmiUrlProvider);
                            }

                            if (self(rmiUrlProvider)) {
                                if (LOG.isDebugEnabled()) {
                                    LOG.debug("EtcdRegisterCenter receiver peerUrls: {} is self, leaseSeconds: {}, skip...", rmiUrlProvider, leaseSeconds);
                                }
                            } else {
                                LOG.debug("EtcdRegisterCenter receiver peerUrls: {}, leaseSeconds: {}.", rmiUrlProvider, leaseSeconds);
                                // 处理注册地址
                                processRmiUrls(rmiUrlProvider, event.getEventType());
                            }
                        }
                    }
                }
            }
        }

        @Override
        public void onError(Throwable throwable) {
            if (!stopped) {
                LOG.error(throwable.getMessage(), throwable);
            }
        }

        @Override
        public void onCompleted() {
            LOG.debug("Watcher server completed.");
        }
    }
}
