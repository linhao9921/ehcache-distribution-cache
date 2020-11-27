package net.sf.ehcache.distribution;

import com.lh.cache.util.NetworkUtil;
import net.sf.ehcache.CacheException;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.rmi.RemoteException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * @Author haol
 * @Date 20-11-25 15:01
 * @Version 1.0
 * @Desciption 基于zookeeper的自动注册发送者
 */
public final class ZkRegisterCenterKeepaliveRegisterSender {

    private static final Logger LOG = LoggerFactory.getLogger(ZkRegisterCenterKeepaliveRegisterSender.class.getName());

    private static final int DEFAULT_AUTOR_EGISTER_INTERVAL = 5000;

    private final ZkRegisterCenterRMICacheManagerPeerProvider peerProvider;
    private final String serverName;

    private ZkRegisterCenterSenderThread senderThread;

    private volatile boolean stopped;
    private volatile boolean online;

    /**
     * 构造方法
     * @param peerProvider
     * @param serverName
     */
    ZkRegisterCenterKeepaliveRegisterSender(ZkRegisterCenterRMICacheManagerPeerProvider peerProvider, String serverName) {
        this.peerProvider = peerProvider;
        this.serverName = serverName;
    }

    /**
     * Initial
     */
    final void init() throws CacheException {
        LOG.debug("initial ZkRegisterCenter register sender called");
        // 创建根节点
        this.createRootZnode();

        // 自动注册
        this.autoRegister();

        // 创建会话监听
        this.addSessionListener();

        // 服务实力还未创建出来，需要任务轮询
        if (!this.online) {
            this.senderThread = new ZkRegisterCenterSenderThread();
            this.senderThread.start();
        }
    }

    /**
     * 创建根节点
     */
    private void createRootZnode() throws CacheException{
        try {
            String path = this.peerProvider.processZnodePath(this.serverName);
            // 判断节点目录是否存在
            Stat stat = this.peerProvider.getClient().checkExists().forPath(path);
            if (stat == null) {
                String data = NetworkUtil.getLocalId();
                if (data == null) {
                    data = "Name:" + this.serverName;
                } else {
                    data = "Name:" + this.serverName + ",IP: " + data;
                }
                // 创建数据节点
                String re = this.peerProvider.getClient().create()
                        .creatingParentContainersIfNeeded()                                  // 递归创建所需父节点
                        .withMode(CreateMode.PERSISTENT)                                     // 创建类型为持久节点
                        .forPath(path, data.getBytes());                                     // 创建目录和内容
                LOG.debug("Create znode[{}] success. result: {}.", path, re);
            }
        } catch (Exception e) {
            LOG.error(e.getMessage(), e);
            throw new CacheException(e);
        }
    }

    /**
     * 自动注册
     */
    private void autoRegister() throws CacheException {
        // 创建本地的注册地址
        Set<String> rmiPeers = createCachePeersPayload();
        if (rmiPeers != null && rmiPeers.size() > 0) {
            try {
                // 转换注册地址
                String rmiUrl = this.translateRegisterCenterValue(rmiPeers);
                String path = this.peerProvider.processZnodePath(this.serverName) + this.peerProvider.processZnodePath("Provider-");
                // 创建数据节点
                String re = this.peerProvider.getClient().create()
                        .creatingParentContainersIfNeeded()                         // 递归创建所需父节点
                        .withMode(CreateMode.EPHEMERAL_SEQUENTIAL)                  // 创建类型为临时自动编号节点, 一旦创建这个节点,当回话结束, 节点会被删除
                        .forPath(path, rmiUrl.getBytes());                          // 创建目录和内容(暴露的rmi地址存储到数据上)
                LOG.debug("Create znode[{}] success. result: {}.", path, re);

                // 注册成功
                this.online = true;
            } catch (Exception e) {
                LOG.error(e.getMessage(), e);
                throw new CacheException(e);
            }
        }
    }

    /**
     * 创建会话监听
     */
    private void addSessionListener(){
        // 创建会话监听
        this.peerProvider.getClient()
                .getConnectionStateListenable()
                .addListener(new ZkRegisterCenterSessionConnectionListener());
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
                        LOG.error("[ZK]This should never be thrown as it is called locally");
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
        for (;;) {
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
     * 自动注册线程
     */
    private final class ZkRegisterCenterSenderThread extends Thread {
        /**
         * 构造方法
         */
        ZkRegisterCenterSenderThread() {
            super("ZKRegisterCenter AutoRegister Sender Thread");
        }


        @Override
        public void run() {
            while (!stopped && !online) {
                try  {
                    autoRegister();
                } catch (Exception e) {
                    LOG.info("Unexpected throwable in run thread. Continuing..." + e.getMessage(), e);
                }

                try {
                    sleep(DEFAULT_AUTOR_EGISTER_INTERVAL);
                } catch (InterruptedException e) {
                    if (!stopped) {
                        LOG.error("Error receiving heartbeat. Initial cause was " + e.getMessage(), e);
                    }
                }
            }
        }
    }

    /**
     * 断线重连机制
     */
    private final class ZkRegisterCenterSessionConnectionListener implements ConnectionStateListener {
        @Override
        public void stateChanged(CuratorFramework client, ConnectionState newState) {
            if (!stopped && newState == ConnectionState.LOST) {
                while (true) {
                    try {
                        if (client.getZookeeperClient().blockUntilConnectedOrTimedOut()) {
                            if (!stopped) {
                                // 重新自动注册
                                autoRegister();
                            }
                            break;
                        }
                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception e) {
                        LOG.info("Unexpected throwable in run thread. Continuing..." + e.getMessage(), e);
                        if (e instanceof CacheException) {
                            break;
                        }
                    }
                }
            }
        }
    }
}
