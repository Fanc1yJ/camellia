package com.netease.nim.camellia.http.accelerate.proxy.core.transport.tcp.codec;

import com.netease.nim.camellia.codec.Unpack;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Created by caojiajun on 2023/5/8
 */
public class TcpPackDecoder extends ByteToMessageDecoder {

    private static final Logger logger = LoggerFactory.getLogger(TcpPackDecoder.class);

    public static String getName() {
        return "TcpPackDecoder";
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf buf, List<Object> list) {
        if (buf.readableBytes() > 4) {
            int len = buf.getInt(buf.readerIndex());
            if (len > 40 * 1024 * 1024 || len < 0) {
                ctx.channel().close();
                logger.error("incorrect size, channel = {}, size = {}, channel will force close", ctx.channel(), len);
                return;
            }
            if (len > buf.readableBytes()) {
                return;
            }
            ByteBuf byteBuf = buf.readBytes(len);
            byte[] bytes = new byte[byteBuf.readableBytes()];
            byteBuf.readBytes(bytes);
            byteBuf.release();
            Unpack unpack = new Unpack(bytes);
            TcpPack pack = new TcpPack();
            pack.decode(unpack);
            list.add(pack);
        }
    }
}
