package com.netease.nim.camellia.redis.proxy.upstream.sentinel;

import com.netease.nim.camellia.core.model.Resource;
import com.netease.nim.camellia.redis.proxy.upstream.utils.HostAndPort;
import com.netease.nim.camellia.redis.proxy.upstream.connection.RedisConnection;
import com.netease.nim.camellia.redis.proxy.upstream.connection.RedisConnectionHub;
import com.netease.nim.camellia.redis.proxy.enums.RedisCommand;
import com.netease.nim.camellia.redis.proxy.monitor.PasswordMaskUtils;
import com.netease.nim.camellia.redis.proxy.reply.Reply;
import com.netease.nim.camellia.redis.proxy.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Created by caojiajun on 2021/4/9
 */
public class RedisSentinelSlavesListener extends Thread {

    private static final Logger logger = LoggerFactory.getLogger(RedisSentinelMasterListener.class);

    private static final AtomicLong id = new AtomicLong(0);

    private final Resource resource;
    private final HostAndPort sentinel;
    private final SlavesUpdateCallback callback;
    private final String master;
    private boolean running = true;
    private final String userName;
    private final String password;

    public RedisSentinelSlavesListener(Resource resource, HostAndPort sentinel, String master, String userName, String password, SlavesUpdateCallback callback) {
        this.resource = resource;
        this.sentinel = sentinel;
        this.callback = callback;
        this.master = master;
        this.userName = userName;
        this.password = password;
        setName("redis-sentinel-slaves-listener-" + sentinel.toString() + "-" + id.incrementAndGet());
    }

    public interface SlavesUpdateCallback {
        void slavesUpdate(List<HostAndPort> slaves);
    }

    @Override
    public void run() {
        RedisConnection redisConnection = null;
        while (running) {
            try {
                if (redisConnection == null || !redisConnection.isValid()) {
                    if (redisConnection != null && !redisConnection.isValid()) {
                        redisConnection.stop();
                    }
                    redisConnection = RedisConnectionHub.getInstance().newConnection(sentinel.getHost(), sentinel.getPort(), userName, password);
                    while (redisConnection == null || !redisConnection.isValid()) {
                        logger.error("connect to sentinel fail, sentinel = {}. sleeping 5000ms and retrying.", sentinel.getUrl());
                        try {
                            TimeUnit.MILLISECONDS.sleep(3000);
                        } catch (InterruptedException e) {
                            logger.error(e.getMessage(), e);
                        }
                        redisConnection = RedisConnectionHub.getInstance().newConnection(sentinel.getHost(), sentinel.getPort(), userName, password);
                    }
                }
                List<HostAndPort> slaves = null;
                try {
                    CompletableFuture<Reply> future = redisConnection.sendCommand(RedisCommand.SENTINEL.raw(), RedisSentinelUtils.SLAVES, Utils.stringToBytes(master));
                    Reply reply = future.get(10, TimeUnit.SECONDS);
                    slaves = RedisSentinelUtils.processSlaves(reply);
                } catch (Exception e) {
                    logger.error("can not get slaves addr, master name = {}, sentinel = {}", master, sentinel, e);
                }
                if (slaves != null) {
                    callback.slavesUpdate(slaves);
                }
                TimeUnit.MILLISECONDS.sleep(5000);
            } catch (Exception e) {
                logger.error("getSlaveAddrs error, url = {}, sentinel = {}, master = {}", PasswordMaskUtils.maskResource(resource.getUrl()), sentinel.getUrl(), master, e);
            }
        }
        logger.info("redis sentinel slaves listener thread stop, resource = {}, sentinel = {}", PasswordMaskUtils.maskResource(resource.getUrl()), sentinel.getUrl());
    }

    public void shutdown() {
        running = false;
    }
}
