package com.netease.nim.camellia.redis.proxy.command;

import com.netease.nim.camellia.redis.proxy.auth.AuthCommandProcessor;
import com.netease.nim.camellia.redis.proxy.auth.ClientCommandUtil;
import com.netease.nim.camellia.redis.proxy.auth.ConnectLimiter;
import com.netease.nim.camellia.redis.proxy.auth.HelloCommandUtil;
import com.netease.nim.camellia.redis.proxy.cluster.ProxyClusterModeProcessor;
import com.netease.nim.camellia.redis.proxy.enums.RedisKeyword;
import com.netease.nim.camellia.redis.proxy.plugin.*;
import com.netease.nim.camellia.redis.proxy.reply.IntegerReply;
import com.netease.nim.camellia.redis.proxy.upstream.IUpstreamClientTemplate;
import com.netease.nim.camellia.redis.proxy.upstream.IUpstreamClientTemplateFactory;
import com.netease.nim.camellia.redis.proxy.upstream.connection.RedisConnectionHub;
import com.netease.nim.camellia.redis.proxy.info.ProxyInfoUtils;
import com.netease.nim.camellia.redis.proxy.enums.RedisCommand;
import com.netease.nim.camellia.redis.proxy.monitor.ChannelMonitor;
import com.netease.nim.camellia.redis.proxy.netty.ChannelInfo;
import com.netease.nim.camellia.redis.proxy.reply.ErrorReply;
import com.netease.nim.camellia.redis.proxy.reply.Reply;
import com.netease.nim.camellia.redis.proxy.reply.StatusReply;
import com.netease.nim.camellia.redis.proxy.util.ErrorLogCollector;
import com.netease.nim.camellia.redis.proxy.util.Utils;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 *
 * Created by caojiajun on 2021/5/26
 */
public class CommandsTransponder {

    private static final Logger logger = LoggerFactory.getLogger(CommandsTransponder.class);

    private final AuthCommandProcessor authCommandProcessor;
    private final ProxyClusterModeProcessor clusterModeProcessor;
    private final IUpstreamClientTemplateFactory factory;
    private final ProxyPluginFactory proxyPluginFactory;

    private boolean eventLoopSetSuccess = false;

    private ProxyPluginInitResp proxyPluginInitResp;

    public CommandsTransponder(IUpstreamClientTemplateFactory factory, CommandInvokeConfig commandInvokeConfig) {
        this.factory = factory;
        this.authCommandProcessor = commandInvokeConfig.getAuthCommandProcessor();
        this.clusterModeProcessor = commandInvokeConfig.getClusterModeProcessor();
        this.proxyPluginFactory = commandInvokeConfig.getProxyPluginFactory();
        this.proxyPluginInitResp = proxyPluginFactory.initPlugins();
        // 刷新插件用的
        proxyPluginFactory.registerPluginUpdate(() -> proxyPluginInitResp = proxyPluginFactory.initPlugins());
    }

    public void transpond(ChannelInfo channelInfo, List<Command> commands) {
        if (!eventLoopSetSuccess) {
            RedisConnectionHub.getInstance().updateEventLoop(channelInfo.getCtx().channel().eventLoop());
            eventLoopSetSuccess = true;
        }
        try {
            boolean hasCommandsSkip = false;
            //  任务队列
            CommandTaskQueue taskQueue = channelInfo.getCommandTaskQueue();

            if (logger.isDebugEnabled()) {
                List<String> commandNameList = new ArrayList<>(commands.size());
                for (Command command : commands) {
                    commandNameList.add(command.getName());
                }
                logger.debug("receive commands, commands.size = {}, consid = {}, commands = {}",
                        commands.size(), taskQueue.getChannelInfo().getConsid(), commandNameList);
            }

            List<CommandTask> tasks = new ArrayList<>(commands.size());
            ChannelHandlerContext ctx = channelInfo.getCtx();

            for (Command command : commands) {
                //设置channelInfo
                command.setChannelInfo(channelInfo);

                //任务
                CommandTask task = new CommandTask(taskQueue, command, proxyPluginInitResp.getReplyPlugins());
                boolean add = taskQueue.add(task);
                if (!add) {
                    taskQueue.clear();
                    logger.warn("CommandTaskQueue full, client connect will be disconnect, remote.ip = {}", ctx.channel().remoteAddress());
                    ctx.writeAndFlush(ErrorReply.TOO_BUSY).addListener((ChannelFutureListener) future -> ctx.close());
                    return;
                }

                List<ProxyPlugin> requestPlugins = proxyPluginInitResp.getRequestPlugins();
                if (!requestPlugins.isEmpty()) {
                    boolean pluginBreak = false;
                    //执行插件，跟spring boot的拦截器一样的思维，算是一种责任链，上一个没通过下一个plugin也不会执行了
                    ProxyRequest request = new ProxyRequest(channelInfo.getDb(), command, factory);
                    for (ProxyPlugin plugin : proxyPluginInitResp.getRequestPlugins()) {
                        try {
                            ProxyPluginResponse response = plugin.executeRequest(request);
                            if (!response.isPass()) {
                                reply(channelInfo, task, command.getRedisCommand(), response.getReply(), true);
                                hasCommandsSkip = true;
                                pluginBreak = true;
                                break;
                            }
                        } catch (Exception e) {
                            ErrorLogCollector.collect(CommandsTransponder.class, "executeRequest error", e);
                        }
                    }
                    // 如果在插件处中断了，说明命令不需要继续执行
                    if (pluginBreak) {
                        continue;
                    }
                }

                RedisCommand redisCommand = command.getRedisCommand();

                //不支持的命令直接返回NOT_SUPPORT
                if (redisCommand == null || redisCommand.getSupportType() == RedisCommand.CommandSupportType.NOT_SUPPORT) {
                    reply(channelInfo, task, redisCommand, ErrorReply.NOT_SUPPORT, false);
                    ErrorLogCollector.collect(CommandsTransponder.class, "not support command = " + command.getName());
                    hasCommandsSkip = true;
                    continue;
                }

                //subscribe状态下，只能使用指定的命令
                if (channelInfo.isInSubscribe()) {
                    if (redisCommand != RedisCommand.SUBSCRIBE && redisCommand != RedisCommand.PSUBSCRIBE
                            && redisCommand != RedisCommand.UNSUBSCRIBE && redisCommand != RedisCommand.PUNSUBSCRIBE
                            && redisCommand != RedisCommand.PING && redisCommand != RedisCommand.QUIT) {
                        ErrorReply errorReply = new ErrorReply("Command " + redisCommand.strRaw() + " not allowed while subscribed. Allowed commands are: [PSUBSCRIBE, PUNSUBSCRIBE, SUBSCRIBE, UNSUBSCRIBE, QUIT, PING]");
                        reply(channelInfo, task, redisCommand, errorReply, false);
                        hasCommandsSkip = true;
                        continue;
                    }
                }

                //DB类型的命令，before auth
                if (redisCommand.getCommandType() == RedisCommand.CommandType.DB) {
                    if (redisCommand == RedisCommand.PING) {
                        if (channelInfo.isFromCport()) {
                            task.replyCompleted(StatusReply.PONG);
                            hasCommandsSkip = true;
                            continue;
                        }
                    }
                    //quit命令直接断开连接
                    if (redisCommand == RedisCommand.QUIT) {
                        channelInfo.getCtx().close();
                        return;
                    }

                    //auth命令
                    if (redisCommand == RedisCommand.AUTH) {
                        boolean hasBidBgroup = channelInfo.getBid() != null && channelInfo.getBgroup() != null;
                        Reply reply = authCommandProcessor.invokeAuthCommand(channelInfo, command);
                        if (!hasBidBgroup) {
                            boolean pass = checkConnectLimit(channelInfo);
                            if (!pass) return;
                        }
                        task.replyCompleted(reply);
                        hasCommandsSkip = true;
                        continue;
                    }

                    //hello命令
                    if (redisCommand == RedisCommand.HELLO) {
                        boolean hasBidBgroup = channelInfo.getBid() != null && channelInfo.getBgroup() != null;
                        Reply reply = HelloCommandUtil.invokeHelloCommand(channelInfo, authCommandProcessor, command);
                        if (!hasBidBgroup) {
                            boolean pass = checkConnectLimit(channelInfo);
                            if (!pass) return;
                        }
                        task.replyCompleted(reply);
                        hasCommandsSkip = true;
                        continue;
                    }
                }

                //如果需要密码，但是没有auth，则返回NO_AUTH
                if (authCommandProcessor.isPasswordRequired()) {
                    boolean skipAuth = false;
                    if (redisCommand == RedisCommand.CLUSTER) {
                        byte[][] args = command.getObjects();
                        skipAuth = args.length >= 2 && Utils.bytesToString(args[1]).equalsIgnoreCase(RedisKeyword.PROXY_HEARTBEAT.name());
                    }
                    if (channelInfo.getChannelStats() != ChannelInfo.ChannelStats.AUTH_OK && !skipAuth) {
                        task.replyCompleted(ErrorReply.NO_AUTH);
                        hasCommandsSkip = true;
                        continue;
                    }
                }

                //DB类型的命令，after auth
                if (redisCommand.getCommandType() == RedisCommand.CommandType.DB) {
                    //select命令
                    if (redisCommand == RedisCommand.SELECT) {
                        byte[][] objects = command.getObjects();
                        if (objects.length == 2) {
                            int db = (int) Utils.bytesToNum(command.getObjects()[1]);
                            if (db < 0) {
                                task.replyCompleted(ErrorReply.DB_INDEX_OUT_OF_RANGE);
                            } else {
                                IUpstreamClientTemplate template = factory.tryGet(channelInfo.getBid(), channelInfo.getBgroup());
                                if (template != null && !template.isMultiDBSupport()) {
                                    task.replyCompleted(ErrorReply.DB_INDEX_OUT_OF_RANGE);
                                } else {
                                    //需要把之前db的命令先发出去
                                    if (!tasks.isEmpty() && channelInfo.getDb() != db) {
                                        List<Command> list = new ArrayList<>(tasks.size());
                                        for (CommandTask asyncTask : tasks) {
                                            list.add(asyncTask.getCommand());
                                        }
                                        flush(channelInfo.getBid(), channelInfo.getBgroup(), channelInfo.getDb(), tasks, list);
                                        tasks = new ArrayList<>();
                                    }
                                    channelInfo.setDb(db);
                                    task.replyCompleted(StatusReply.OK);
                                }
                            }
                        } else {
                            task.replyCompleted(ErrorReply.argNumWrong(redisCommand));
                        }
                        hasCommandsSkip = true;
                        continue;
                    }

                    //info命令
                    if (redisCommand == RedisCommand.INFO) {
                        CompletableFuture<Reply> future = ProxyInfoUtils.getInfoReply(command, factory);
                        future.thenAccept(task::replyCompleted);
                        hasCommandsSkip = true;
                        continue;
                    }

                    //client命令，可以用于选择路由
                    if (redisCommand == RedisCommand.CLIENT) {
                        boolean hasBidBgroup = channelInfo.getBid() != null && channelInfo.getBgroup() != null;
                        Reply reply = ClientCommandUtil.invokeClientCommand(channelInfo, command);
                        if (!hasBidBgroup) {
                            boolean pass = checkConnectLimit(channelInfo);
                            if (!pass) return;
                        }
                        task.replyCompleted(reply);
                        hasCommandsSkip = true;
                        continue;
                    }

                    //ASKING命令直接回OK
                    if (redisCommand == RedisCommand.ASKING) {
                        task.replyCompleted(StatusReply.OK);
                        hasCommandsSkip = true;
                        continue;
                    }

                    //CONFIG命令只支持GET
                    if (redisCommand == RedisCommand.CONFIG) {
                        byte[][] objects = command.getObjects();
                        if (objects.length > 1) {
                            String arg = Utils.bytesToString(objects[1]);
                            if (!arg.equalsIgnoreCase(RedisKeyword.GET.name())) {
                                // task.replyCompleted(new ErrorReply("command 'CONFIG' only support GET"));

                                /*
                                * {@link org.springframework.session.data.redis.config.ConfigureNotifyKeyspaceEventsAction#configure} 会执行'SET CONFIG egX'；
                                * {@link org.springframework.session.data.redis.config.annotation.web.http.RedisHttpSessionConfiguration#springSessionRedisMessageListenerContainer} 会创建监听器；
                                * 当两个条件满足时，程序之间会接受到Session的监听信息，此时如果Session包含了非本项目的类，会出现ClassNotFoundException异常
                                *
                                * 处理方案是：
                                * 1.Redis-Server设置CONFIG：notify-keyspace-events ""
                                * 2.Redis-Proxy对所有CONFIG SET返回1，但不做任何处理
                                */
                                task.replyCompleted(StatusReply.OK);
                                hasCommandsSkip = true;
                                continue;
                            }
                        }
                    }

                    if (redisCommand == RedisCommand.CLUSTER) {
                        //cluster mode
                        if (clusterModeProcessor != null) {
                            CompletableFuture<Reply> future = clusterModeProcessor.clusterCommands(command);
                            future.thenAccept(task::replyCompleted);
                        } else {
                            task.replyCompleted(ErrorReply.NOT_SUPPORT);
                        }
                        hasCommandsSkip = true;
                        continue;
                    }
                }

                if (clusterModeProcessor != null) {
                    if (commands.size() == 1) {//pipeline过来的命令就不move了
                        Reply moveReply = clusterModeProcessor.isCommandMove(command);
                        if (moveReply != null) {
                            task.replyCompleted(moveReply);
                            hasCommandsSkip = true;
                            continue;
                        }
                    }
                }

                //订阅命令特殊处理
                if (redisCommand == RedisCommand.SUBSCRIBE || redisCommand == RedisCommand.PSUBSCRIBE) {
                    channelInfo.setInSubscribe(true);
                }

                tasks.add(task);
            }
            if (tasks.isEmpty()) return;
            if (hasCommandsSkip) {
                commands = new ArrayList<>(tasks.size());
                for (CommandTask asyncTask : tasks) {
                    commands.add(asyncTask.getCommand());
                }
            }
            //写入到后端
            flush(channelInfo.getBid(), channelInfo.getBgroup(), channelInfo.getDb(), tasks, commands);
        } catch (Exception e) {
            logger.error("commands transponder error, client connect will be force closed, bid = {}, bgroup = {}, addr = {}",
                    channelInfo.getBid(), channelInfo.getBgroup(), channelInfo.getCtx().channel().remoteAddress(), e);
            channelInfo.getCtx().close();
        }
    }

    private void reply(ChannelInfo channelInfo, CommandTask task, RedisCommand redisCommand, Reply reply, boolean fromPlugin) {
        if (channelInfo.isInSubscribe()) {
            CommandTaskQueue taskQueue = channelInfo.getCommandTaskQueue();
            taskQueue.reply(redisCommand, reply, fromPlugin);
        } else {
            task.replyCompleted(reply, fromPlugin);
        }
    }

    private boolean checkConnectLimit(ChannelInfo channelInfo) {
        try {
            Long bid = channelInfo.getBid();
            String bgroup = channelInfo.getBgroup();
            if (bid != null && bgroup != null) {
                int currentConnect = ChannelMonitor.bidBgroupConnect(bid, bgroup);
                int threshold = ConnectLimiter.connectThreshold(bid, bgroup);
                if (currentConnect >= threshold) {
                    ChannelHandlerContext ctx = channelInfo.getCtx();
                    ctx.writeAndFlush(ErrorReply.TOO_MANY_CONNECTS)
                            .addListener((ChannelFutureListener) future -> ctx.close());
                    logger.warn("too many connects, connect will be force closed, bid = {}, bgroup = {}, current = {}, max = {}, consid = {}, client.addr = {}",
                            bid, bgroup, currentConnect, threshold, channelInfo.getConsid(), channelInfo.getCtx().channel().remoteAddress());
                    return false;
                }
                ChannelMonitor.initBidBgroup(bid, bgroup, channelInfo);
            }
            return true;
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return true;
        }
    }

    private void flush(Long bid, String bgroup, int db, List<CommandTask> tasks, List<Command> commands) {
        try {
            if (!factory.isMultiTenantsSupport() || bid == null || bid <= 0 || bgroup == null) {
                IUpstreamClientTemplate template = factory.getOrInitialize(bid, bgroup);
                flush0(template, bid, bgroup, db, tasks, commands);
                return;
            }
            CompletableFuture<IUpstreamClientTemplate> future = factory.getOrInitializeAsync(bid, bgroup);
            if (future == null) {
                for (CommandTask task : tasks) {
                    task.replyCompleted(ErrorReply.UPSTREAM_NOT_AVAILABLE);
                }
                return;
            }
            future.thenAccept(template -> flush0(template, bid, bgroup, db, tasks, commands));
        } catch (Exception e) {
            ErrorLogCollector.collect(CommandsTransponder.class, "flush commands error", e);
            for (CommandTask task : tasks) {
                task.replyCompleted(ErrorReply.UPSTREAM_NOT_AVAILABLE);
            }
        }
    }

    private void flush0(IUpstreamClientTemplate template, Long bid, String bgroup, int db, List<CommandTask> tasks, List<Command> commands) {
        try {
            if (template == null) {
                for (CommandTask task : tasks) {
                    task.replyCompleted(ErrorReply.UPSTREAM_NOT_AVAILABLE);
                }
            } else {
                List<CompletableFuture<Reply>> futureList;
                try {
                    futureList = template.sendCommand(db, commands);
                } catch (Exception e) {
                    String log = "IUpstreamClientTemplate sendCommand error"
                            + ", bid = " + bid + ", bgroup = " + bgroup + ", ex = " + e;
                    ErrorLogCollector.collect(CommandsTransponder.class, log, e);
                    for (CommandTask task : tasks) {
                        task.replyCompleted(ErrorReply.UPSTREAM_NOT_AVAILABLE);
                    }
                    return;
                }
                for (int i = 0; i < tasks.size(); i++) {
                    CommandTask task = tasks.get(i);
                    CompletableFuture<Reply> completableFuture = futureList.get(i);
                    completableFuture.thenAccept(task::replyCompleted);
                }
            }
        } catch (Exception e) {
            ErrorLogCollector.collect(CommandsTransponder.class, "flush commands error", e);
        }
    }

}
