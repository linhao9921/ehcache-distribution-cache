package net.sf.ehcache.distribution;

import com.lh.cache.util.NetworkUtil;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.CloseableClient;
import io.etcd.jetcd.KV;
import io.etcd.jetcd.Lease;
import io.etcd.jetcd.kv.PutResponse;
import io.etcd.jetcd.lease.LeaseGrantResponse;
import io.etcd.jetcd.lease.LeaseKeepAliveResponse;
import io.etcd.jetcd.options.PutOption;
import io.grpc.stub.StreamObserver;
import net.sf.ehcache.CacheException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;
import java.rmi.RemoteException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * @Author haol
 * @Date 20-11-27 16:28
 * @Version 1.0
 * @Desciption 基于etcd的自动注册发送者
 */
public final class EtcdRegisterCenterKeepaliveRegisterSender {

    private static final Logger LOG = LoggerFactory.getLogger(EtcdRegisterCenterKeepaliveRegisterSender.class.getName());

    private static final Charset DEFAULT_CHARSET = Charset.forName("UTF-8");

    private final EtcdRegisterCenterRMICacheManagerPeerProvider peerProvider;
    private final String serverName;
    private final long longLeaseTtlSeconds;

    private EtcdRegisterCenterSenderThread senderThread;

    private volatile boolean stopped;

    /**
     * 构造方法
     * @param peerProvider
     * @param serverName
     * @param longLeaseTtlSeconds
     */
    EtcdRegisterCenterKeepaliveRegisterSender(EtcdRegisterCenterRMICacheManagerPeerProvider peerProvider
            , String serverName, long longLeaseTtlSeconds) {
        this.peerProvider = peerProvider;
        this.serverName = serverName;
        this.longLeaseTtlSeconds = longLeaseTtlSeconds;
    }

    /**
     * Initial
     */
    final void init() throws CacheException {
        LOG.debug("initial EtcdRegisterCenter register sender called");

        // 发送心跳线程
        this.senderThread = new EtcdRegisterCenterSenderThread();
        this.senderThread.start();
    }

    /**
     * Shutdown this register sender
     */
    final void dispose() {
        this.stopped = true;

        if (this.senderThread != null) {
            this.senderThread.interrupt();
        }
    }

    /**
     * 创建本地的注册地址
     * @return
     */
    private Set<String> createCachePeersPayload() {
        CacheManagerPeerListener cacheManagerPeerListener = this.peerProvider.cacheManager.getCachePeerListener("RMI");
        if (cacheManagerPeerListener != null) {
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
                        LOG.error("[Etcd]This should never be thrown as it is called locally");
                    }
                }
            }
            return rmiUrls;
        }

        LOG.warn("[ZK]The RMICacheManagerPeerListener is missing. You need to configure a cacheManagerPeerListenerFactory" +
                " with class=\"net.sf.ehcache.distribution.RMICacheManagerPeerListenerFactory\" in ehcache.xml.");
        return new HashSet<>();
    }

    /**
     * 转换注册地址
     * @param rmiPeers
     * @return
     */
    private String translateRegisterCenterValue(Set<String> rmiPeers) {
        // 构造注册地址
        Iterator<String> it = rmiPeers.iterator();
        StringBuilder sb = new StringBuilder();
        while (true) {
            String e = it.next();
            sb.append(e);
            if (it.hasNext())
                sb.append(PayloadUtil.URL_DELIMITER);
            else
                break;
        }
        return sb.toString();
    }

    /**
     * 定时租约续约
     */
    private final class EtcdRegisterCenterSenderThread extends Thread {
        private Lease leaseClient;
        private CloseableClient leaseKeepAliveClient;
        private Long leaseId;

        /**
         * 构造方法
         */
        EtcdRegisterCenterSenderThread() {
            super("EtcdRegisterCenter AutoRegister Sender Thread");
            setDaemon(true);
        }

        @Override
        public void run() {
            while (!stopped) {
                long millis = 3000L;
                if (this.leaseId == null) {
                    // 自动注册
                    this.autoRegister();
                }

                try {
                    sleep(millis);
                } catch (InterruptedException e) {
                    if (!stopped) {
                        LOG.error("Error sending heartbeat. Initial cause was " + e.getMessage(), e);
                    }
                }
            }
        }

        @Override
        public void interrupt() {
            // 关闭心跳观察者
            if (this.leaseKeepAliveClient != null) {
                this.leaseKeepAliveClient.close();
            }

            // 关闭租约客户端
            if (this.leaseClient != null) {
                if (this.leaseId != null) {
                    // 释放租约
                    try {
                        this.leaseClient.revoke(this.leaseId).get();
                    } catch (InterruptedException | ExecutionException e) {
                        LOG.error(e.getMessage(), e);
                    }
                }

                // 关闭租约客户端
                this.leaseClient.close();
            }
            // 线程中断
            super.interrupt();
        }

        /**
         * 自动注册
         */
        private void autoRegister() {
            // 创建本地的注册地址
            Set<String> rmiPeers = createCachePeersPayload();
            if (rmiPeers != null && rmiPeers.size() > 0) {
                // 获取一个租约客户端
                if (this.leaseClient == null) {
                    this.leaseClient = peerProvider.getClient().getLeaseClient();
                }
                // 转换注册地址
                String path = peerProvider.processNodePath(serverName + "-" + NetworkUtil.getLocalId());
                String rmiUrl = translateRegisterCenterValue(rmiPeers);

                // 采用租约方式存储
                try (KV kvClient = peerProvider.getClient().getKVClient()) {
                    ByteSequence key = ByteSequence.from(path, DEFAULT_CHARSET);           // 存储到指定目录
                    ByteSequence value = ByteSequence.from(rmiUrl, DEFAULT_CHARSET);       // 存储rmi地址

                    // 申请一个租约
                    CompletableFuture<LeaseGrantResponse> leaseGrantResponseFuture = this.leaseClient.grant(longLeaseTtlSeconds);
                    LeaseGrantResponse leaseGrantResponse = leaseGrantResponseFuture.get();

                    // 获取租约id
                    long leaseId = leaseGrantResponse.getID();

                    // 使用租约存储
                    CompletableFuture<PutResponse> putFuture = kvClient.put(key, value, PutOption.newBuilder().withLeaseId(leaseId).build());
                    putFuture.get();

                    // 存储租约id
                    this.leaseId = leaseId;

                    // 保持心跳
                    this.leaseKeepAliveClient = this.leaseClient.keepAlive(this.leaseId, new HeartbeatStreamObserver());
                    LOG.debug("Create lease[{}] success. leaseId: {}, ttl: {}.", path, this.leaseId, longLeaseTtlSeconds);
                } catch (Exception e) {
                    LOG.error(e.getMessage(), e);
                }
            }
        }

        /**
         * 重新连接
         */
        private void reAutoRegister(){
            // 优先关闭观察者
            if (this.leaseKeepAliveClient != null) {
                this.leaseKeepAliveClient.close();

                // 重新自动注册
                this.autoRegister();
            }
        }
    }

    /**
     * 租约心跳观察者
     */
    private final class HeartbeatStreamObserver implements StreamObserver<LeaseKeepAliveResponse> {

        @Override
        public void onNext(LeaseKeepAliveResponse response) {
            if (response.getID() == senderThread.leaseId) {
                LOG.debug("Refresh lease id: {}, ttl: {}.", response.getID(), response.getTTL());
            }
        }

        @Override
        public void onError(Throwable throwable) {
            if (!stopped) {
                LOG.error(throwable.getMessage(), throwable);
                // 自动重连
                senderThread.reAutoRegister();
            }
        }

        @Override
        public void onCompleted() {
            LOG.debug("HeartbeatStreamObserver start success.");
        }
    }
}
