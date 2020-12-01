# ehcache-distribution-cache
基于ehcache的分布式缓存


## 1.分布式缓存缓存（手动配置方式）
    <!--方式1-->
    <!--
        集群多台服务器中的缓存，这里是要同步一些服务器的缓存
        server1 hostName:192.168.8.9 port:400001 cacheName:mobileCache
        server2 hostName:192.168.8.32 port:400002 cacheName:mobileCache
        server3 hostName:192.168.8.231 port:400003 cacheName:mobileCache
        注意：每台要同步缓存的服务器的RMI通信socket端口都不一样，在配置的时候注意设置
    -->
    <!-- server1 的cacheManagerPeerProviderFactory配置 -->
<!--    <cacheManagerPeerProviderFactory-->
<!--            class="net.sf.ehcache.distribution.RMICacheManagerPeerProviderFactory"-->
<!--            properties="hostName=172.20.32.11,-->
<!--        port=10990,-->
<!--        socketTimeoutMillis=2000,-->
<!--        peerDiscovery=manual,-->
<!--        rmiUrls=//172.20.32.8:10990/message"-->
<!--    />-->




## 2.分布式缓存（广播方式）
    <!--方式2： 自动发现方式-->
    <!--
        集群多台服务器中的缓存工厂,采用automatic方式，利用广播方式连接
        peerDiscovery=automatic: 自动发现
        multicastGroupAddress=230.0.0.1： 广播地址
        multicastGroupPort=4004： 广播端口号
        timeToLive=32： 搜索某个网段上的缓存
            0是限制在同一个服务器
            1是限制在同一个子网
            32是限制在同一个网站
            64是限制在同一个region
            128是限制在同一个大洲
            255是不限制
    -->
<!--    <cacheManagerPeerProviderFactory-->
<!--            class="net.sf.ehcache.distribution.RMICacheManagerPeerProviderFactory"-->
<!--            properties="peerDiscovery=automatic,multicastGroupAddress=230.0.0.1,multicastGroupPort=4004,timeToLive=32"  />-->




## 3.分布式缓存（redis为注册中心方式）
    <!--方式3： 自动发现(redis作为注册中心)方式-->
    <!--
        集群多台服务器中的缓存工厂,采用redis_register_center_automatic方式，利用redis方式连接
        peerDiscovery=redis_register_center_automatic: 基于redis注册中心自动发现
        redisRegisterCenterHost=xxx.xxx.xxx.xxx： redis注册中心地址
        redisRegisterCenterPort=6379： redis注册中心端口
        redisRegisterCenterKey=xxx： 注册中心key(每个服务唯一)
        socketTimeout=2000： redis执行超时时间
        heartBeatSenderInterval=5000: 注册中心发送心跳时间间隔
        heartBeatStaleTime=-1： 心跳未发送的最大允许节点存活时间
        heartBeatReceiverInterval=2500: 注册中心接受心跳时间间隔
    -->
<!--    <cacheManagerPeerProviderFactory-->
<!--            class="net.sf.ehcache.distribution.RMICacheManagerExtendsPeerProviderFactory"-->
<!--            properties="peerDiscovery=redis_register_center_automatic-->
<!--                ,redisRegisterCenterHost=172.20.17.67-->
<!--                ,redisRegisterCenterPort=6379-->
<!--                ,redisRegisterCenterKey=ehcache_distribution:cache:register:center-->
<!--                ,socketTimeout=3000-->
<!--                ,heartBeatSenderInterval=3000-->
<!--                ,heartBeatStaleTime=6100-->
<!--                ,heartBeatReceiverInterval=2000"  />-->



## 4.分布式缓存（zookeeper为注册中心方式）
    <!--方式4： 自动发现(zookeeper作为注册中心)方式-->
    <!--
        集群多台服务器中的缓存工厂,采用zk_register_center_automatic方式，利用zk方式连接
        peerDiscovery=zk_register_center_automatic: 基于zk注册中心自动发现
        zkRegisterCenterAddress=172.20.32.11:2181: 注册中心地址
        sessionTimeoutMs=5000: 注册中心session执行超时时间
        connectionTimeoutMs=5000: 注册中心连接超时时间
        retryPolicyBaseSleepTimeMs=100: 注册中心重试策略基础睡眠时间
        retryPolicyMaxRetries=3: 注册中心重试策略最大重试次数
        zkRegisterCenterNamespace=xxx: 注册中心的隔离名称
        serverName=xxx: 注册中心的服务名称
    -->
<!--    <cacheManagerPeerProviderFactory-->
<!--            class="net.sf.ehcache.distribution.RMICacheManagerExtendsPeerProviderFactory"-->
<!--            properties="peerDiscovery=zk_register_center_automatic-->
<!--                ,zkRegisterCenterAddress=172.20.32.11:2181-->
<!--                ,sessionTimeoutMs=60000-->
<!--                ,connectionTimeoutMs=5000-->
<!--                ,retryPolicyBaseSleepTimeMs=100-->
<!--                ,retryPolicyMaxRetries=3-->
<!--                ,zkRegisterCenterNamespace=ehcache_distribution_register_center-->
<!--                ,serverName=ehcache_distribution_test"  />-->



## 5.分布式缓存（etcd为注册中心方式）
    <!--方式5： 自动发现(etcd作为注册中心)方式-->
    <!--
        集群多台服务器中的缓存工厂,采用etcd_register_center_automatic方式，利用etcd方式连接
        peerDiscovery=etcd_register_center_automatic: 基于etcd注册中心自动发现
        etcdRegisterCenterAddress=http://172.20.32.11:2379: 注册中心地址
        etcdRegisterCenterNamespace=xxx: 注册中心的隔离名称
        serverName=xxx: 注册中心的服务名称
    -->
    <cacheManagerPeerProviderFactory
            class="net.sf.ehcache.distribution.RMICacheManagerExtendsPeerProviderFactory"
            properties="peerDiscovery=etcd_register_center_automatic
                ,etcdRegisterCenterAddress=http://172.20.32.11:2379
                ,etcdRegisterCenterNamespace=ehcache_distribution_register_center
                ,serverName=ehcache_distribution_test
                ,etcdRegisterCenterLeaseTtlSeconds=60"  />

    <!--
        集群多台服务器中的缓存监听器工厂
        port: 建立RMI连接的端口
        socketTimeoutMillis： 建立RMI连接的socket超时时间
    -->
    <cacheManagerPeerListenerFactory
            class="net.sf.ehcache.distribution.RMICacheManagerPeerListenerFactory"
            properties="port=4001,socketTimeoutMillis=20000" />

## 其中： 方式3（redis为注册中心）、方式4（zookeeper为注册中心）、方式5（etcd为注册中心）为自定义扩展方式。