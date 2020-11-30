package net.sf.ehcache.distribution;

import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import net.sf.ehcache.CacheException;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Ehcache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.charset.Charset;
import java.util.*;

/**
 * @Author haol
 * @Date 20-11-27 10:45
 * @Version 1.0
 * @Desciption
 */
public class EtcdRegisterCenterRMICacheManagerPeerProvider extends RMICacheManagerPeerProvider implements CacheManagerPeerProvider {

    private static final Logger LOG = LoggerFactory.getLogger(EtcdRegisterCenterRMICacheManagerPeerProvider.class.getName());

    private static final Charset DEFAULT_CHARSET = Charset.forName("UTF-8");

    private final EtcdRegisterCenterKeepaliveRegisterSender registerSender;
    private final EtcdRegisterCenterKeepaliveWatchReceiver watchReceiver;

    private final Client client;

    /**
     * Creates and starts a etcd register center peer provider
     * @param cacheManager 缓存管理器
     * @param etcdConfigCenterAddress 注册中心地址
     * @param etcdConfigCenterNamespace 注册中心的隔离名称
     * @param etcdConfigCenterServerName 注册中心的服务名
     * @param longLeaseTtlSeconds 注册中心的租约时长
     */
    EtcdRegisterCenterRMICacheManagerPeerProvider(CacheManager cacheManager, String etcdConfigCenterAddress
            , String etcdConfigCenterNamespace, String etcdConfigCenterServerName, long longLeaseTtlSeconds) {
        super(cacheManager);

        // 分割
        StringTokenizer stringTokenizer = new StringTokenizer(etcdConfigCenterAddress, ",");
        Set<URI> endpoints = new HashSet<>(stringTokenizer.countTokens());
        while (stringTokenizer.hasMoreTokens()) {
            endpoints.add(URI.create(stringTokenizer.nextToken()));
        }

        // 使用命名空间
        String namespace = this.processNodePath(etcdConfigCenterNamespace);
        // 创建etcd客户端
        this.client = Client.builder()
                .endpoints(endpoints)                                                   // etcd地址
                .namespace(ByteSequence.from(namespace, DEFAULT_CHARSET))               // 设置命名空间
                .build();

        this.watchReceiver = new EtcdRegisterCenterKeepaliveWatchReceiver(this, etcdConfigCenterServerName);
        this.registerSender = new EtcdRegisterCenterKeepaliveRegisterSender(this, etcdConfigCenterServerName, longLeaseTtlSeconds);
    }

    @Override
    public void init() throws CacheException {
        try {
            // 初始化基于etcd的自动发现接受者
            this.watchReceiver.init();
            // 初始化基于etcd的自动注册发送者
            this.registerSender.init();
        } catch (Exception e) {
            LOG.error(e.getMessage(), e);
            throw new CacheException(e);
        }
    }

    @Override
    public void dispose() throws CacheException {
        try {
            // 销毁基于etcd的自动发现接受者
            this.watchReceiver.dispose();
            // 基于etcd的自动注册发送者
            this.registerSender.dispose();
        } catch (CacheException e) {
            // 关闭etcd客户端
            this.client.close();

            LOG.error(e.getMessage(), e);
            throw new CacheException(e);
        }

        // 关闭etcd客户端
        this.client.close();
    }

    @Override
    public long getTimeForClusterToForm() {
        return 0;
    }

    @Override
    public final void registerPeer(String rmiUrl) {

    }

    @Override
    public final synchronized List listRemoteCachePeers(Ehcache cache) throws CacheException {
        return new ArrayList();
    }

    @Override
    protected boolean stale(Date date) {
        return false;
    }

    String processNodePath(String path){
        return path.startsWith("/") ? path : "/" + path;
    }

    Client getClient() {
        return client;
    }
}
