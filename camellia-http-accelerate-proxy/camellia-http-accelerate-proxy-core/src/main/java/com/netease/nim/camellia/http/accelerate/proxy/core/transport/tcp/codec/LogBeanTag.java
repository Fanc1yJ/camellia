package com.netease.nim.camellia.http.accelerate.proxy.core.transport.tcp.codec;

import com.netease.nim.camellia.codec.Props;
import com.netease.nim.camellia.http.accelerate.proxy.core.context.ErrorReason;
import com.netease.nim.camellia.http.accelerate.proxy.core.context.LogBean;

/**
 * Created by caojiajun on 2023/7/10
 */
public enum LogBeanTag {

    host(1),
    path(2),
    traceId(3),
    startTime(4),
    transportServerSendTime(5),
    transportServerReceiveTime(6),
    upstreamSendTime(7),
    upstreamReplyTime(8),
    endTime(9),
    transportAddr(10),
    upstreamAddr(11),
    errorReason(12),
    code(13),
    transportClientId(14),
    ;

    private final int value;

    LogBeanTag(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public static LogBeanTag getByValue(int value) {
        for (LogBeanTag tag : LogBeanTag.values()) {
            if (tag.value == value) {
                return tag;
            }
        }
        return null;
    }

    public static Props logBean(LogBean logBean) {
        Props props = new Props();
        if (logBean.getTraceId() != null) {
            props.put(LogBeanTag.traceId.getValue(), logBean.getTraceId());
        }
        if (logBean.getHost() != null) {
            props.put(LogBeanTag.host.getValue(), logBean.getHost());
        }
        if (logBean.getPath() != null) {
            props.put(LogBeanTag.path.getValue(), logBean.getPath());
        }
        if (logBean.getStartTime() != null) {
            props.putLong(LogBeanTag.startTime.getValue(), logBean.getStartTime());
        }
        if (logBean.getTransportServerSendTime() != null) {
            props.putLong(LogBeanTag.transportServerSendTime.getValue(), logBean.getTransportServerSendTime());
        }
        if (logBean.getTransportServerReceiveTime() != null) {
            props.putLong(LogBeanTag.transportServerReceiveTime.getValue(), logBean.getTransportServerReceiveTime());
        }
        if (logBean.getUpstreamSendTime() != null) {
            props.putLong(LogBeanTag.upstreamSendTime.getValue(), logBean.getUpstreamSendTime());
        }
        if (logBean.getUpstreamReplyTime() != null) {
            props.putLong(LogBeanTag.upstreamReplyTime.getValue(), logBean.getUpstreamReplyTime());
        }
        if (logBean.getEndTime() != null) {
            props.putLong(LogBeanTag.endTime.getValue(), logBean.getEndTime());
        }
        if (logBean.getTransportAddr() != null) {
            props.put(LogBeanTag.transportAddr.getValue(), logBean.getTransportAddr());
        }
        if (logBean.getUpstreamAddr() != null) {
            props.put(LogBeanTag.upstreamAddr.getValue(), logBean.getUpstreamAddr());
        }
        if (logBean.getErrorReason() != null) {
            props.putInteger(LogBeanTag.errorReason.getValue(), logBean.getErrorReason().getValue());
        }
        if (logBean.getCode() != null) {
            props.putInteger(LogBeanTag.code.getValue(), logBean.getCode());
        }
        if (logBean.getTransportClientId() != null) {
            props.putLong(LogBeanTag.transportClientId.getValue(), logBean.getTransportClientId());
        }
        return props;
    }

    public static LogBean parseProps(Props props) {
        LogBean logBean = new LogBean();
        if (props.containsKey(LogBeanTag.traceId.getValue())) {
            logBean.setTraceId(props.get(LogBeanTag.traceId.getValue()));
        }
        if (props.containsKey(LogBeanTag.host.getValue())) {
            logBean.setHost(props.get(LogBeanTag.host.getValue()));
        }
        if (props.containsKey(LogBeanTag.path.getValue())) {
            logBean.setPath(props.get(LogBeanTag.path.getValue()));
        }
        if (props.containsKey(LogBeanTag.startTime.getValue())) {
            logBean.setStartTime(props.getLong(LogBeanTag.startTime.getValue()));
        }
        if (props.containsKey(LogBeanTag.transportServerSendTime.getValue())) {
            logBean.setTransportServerSendTime(props.getLong(LogBeanTag.transportServerSendTime.getValue()));
        }
        if (props.containsKey(LogBeanTag.transportServerReceiveTime.getValue())) {
            logBean.setTransportServerReceiveTime(props.getLong(LogBeanTag.transportServerReceiveTime.getValue()));
        }
        if (props.containsKey(LogBeanTag.upstreamSendTime.getValue())) {
            logBean.setUpstreamSendTime(props.getLong(LogBeanTag.upstreamSendTime.getValue()));
        }
        if (props.containsKey(LogBeanTag.upstreamReplyTime.getValue())) {
            logBean.setUpstreamReplyTime(props.getLong(LogBeanTag.upstreamReplyTime.getValue()));
        }
        if (props.containsKey(LogBeanTag.endTime.getValue())) {
            logBean.setEndTime(props.getLong(LogBeanTag.endTime.getValue()));
        }
        if (props.containsKey(LogBeanTag.transportAddr.getValue())) {
            logBean.setTransportAddr(props.get(LogBeanTag.transportAddr.getValue()));
        }
        if (props.containsKey(LogBeanTag.upstreamAddr.getValue())) {
            logBean.setUpstreamAddr(props.get(LogBeanTag.upstreamAddr.getValue()));
        }
        if (props.containsKey(LogBeanTag.errorReason.getValue())) {
            logBean.setErrorReason(ErrorReason.getByValue(props.getInteger(LogBeanTag.errorReason.getValue())));
        }
        if (props.containsKey(LogBeanTag.code.getValue())) {
            logBean.setCode(props.getInteger(LogBeanTag.code.getValue()));
        }
        if (props.containsKey(LogBeanTag.transportClientId.getValue())) {
            logBean.setTransportClientId(props.getLong(LogBeanTag.transportClientId.getValue()));
        }
        return logBean;
    }
}
