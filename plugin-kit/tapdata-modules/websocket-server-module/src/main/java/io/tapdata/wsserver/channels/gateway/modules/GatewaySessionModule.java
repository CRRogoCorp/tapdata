package io.tapdata.wsserver.channels.gateway.modules;

import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;
import io.netty.channel.Channel;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.tapdata.entity.annotations.Bean;
import io.tapdata.entity.annotations.MainMethod;
import io.tapdata.modules.api.net.data.IncomingData;
import io.tapdata.modules.api.net.data.IncomingInvocation;
import io.tapdata.modules.api.net.data.IncomingMessage;
import io.tapdata.wsserver.channels.error.WSErrors;
import io.tapdata.wsserver.channels.gateway.GatewaySessionHandler;
import io.tapdata.wsserver.channels.gateway.GatewaySessionManager;
import io.tapdata.wsserver.channels.gateway.data.UserChannel;
import io.tapdata.wsserver.channels.websocket.event.IncomingDataReceivedEvent;
import io.tapdata.wsserver.channels.websocket.event.IncomingInvocationReceivedEvent;
import io.tapdata.wsserver.channels.websocket.event.IncomingMessageReceivedEvent;
import io.tapdata.wsserver.channels.websocket.event.PingReceivedEvent;
import io.tapdata.wsserver.eventbus.EventBusHolder;

@MainMethod(value = "main", order = 10000)
@Bean
public class GatewaySessionModule {
    public void main() {
        EventBusHolder.getEventBus().register(this);
    }

    public static final String TAG = GatewaySessionModule.class.getSimpleName();

    @Bean
    public GatewaySessionManager gatewaySessionManager;

    @Subscribe
    @AllowConcurrentEvents
    void receivedIncomingData(IncomingDataReceivedEvent incomingDataReceivedEvent) {
        IncomingData incomingData = incomingDataReceivedEvent.getIncomingData();
        if (incomingData == null) {
            incomingDataReceivedEvent.closeChannel(incomingData.getId(), WSErrors.ERROR_ILLEGAL_PARAMETERS);
            return;
        }
        Channel channel = incomingDataReceivedEvent.getCtx().channel();
        Attribute<UserChannel> attribute = channel.attr(AttributeKey.valueOf(GatewayChannelModule.KEY_GATEWAY_USER));
        UserChannel userSession = attribute.get();
        if (userSession == null) {
            incomingDataReceivedEvent.closeChannel(incomingData.getId(), WSErrors.ERROR_USER_SESSION_NOT_EXIST);
            return;
        }

        //TODO 防重复调用， 调用频率限制
        gatewaySessionManager.receiveIncomingData(userSession.getUserId(), incomingData);
    }

    @Subscribe
    @AllowConcurrentEvents
    void receivedIncomingMessage(IncomingMessageReceivedEvent incomingMessageReceivedEvent) {
        IncomingMessage incomingMessage = incomingMessageReceivedEvent.getIncomingMessage();
        if (incomingMessage == null) {
            incomingMessageReceivedEvent.closeChannel(null, WSErrors.ERROR_ILLEGAL_PARAMETERS);
            return;
        }
        Channel channel = incomingMessageReceivedEvent.getCtx().channel();
        Attribute<UserChannel> attribute = channel.attr(AttributeKey.valueOf(GatewayChannelModule.KEY_GATEWAY_USER));
        UserChannel userSession = attribute.get();
        if (userSession == null) {
            incomingMessageReceivedEvent.closeChannel(incomingMessage.getId(), WSErrors.ERROR_USER_SESSION_NOT_EXIST);
            return;
        }

        //TODO 防重复调用， 调用频率限制
        gatewaySessionManager.receiveIncomingMessage(userSession.getUserId(), incomingMessage);
    }

    @Subscribe
    @AllowConcurrentEvents
    void receivedIncomingInvocation(IncomingInvocationReceivedEvent incomingInvocationReceivedEvent) {
        IncomingInvocation incomingInvocation = incomingInvocationReceivedEvent.getIncomingInvocation();
        if (incomingInvocation == null) {
            incomingInvocationReceivedEvent.closeChannel(null, WSErrors.ERROR_ILLEGAL_PARAMETERS);
            return;
        }
        Channel channel = incomingInvocationReceivedEvent.getCtx().channel();
        Attribute<UserChannel> attribute = channel.attr(AttributeKey.valueOf(GatewayChannelModule.KEY_GATEWAY_USER));
        UserChannel userSession = attribute.get();
        if (userSession == null) {
            incomingInvocationReceivedEvent.closeChannel(incomingInvocation.getId(), WSErrors.ERROR_USER_SESSION_NOT_EXIST);
            return;
        }

        //TODO 防重复调用， 调用频率限制
        gatewaySessionManager.receiveIncomingInvocation(userSession.getUserId(), incomingInvocation);
    }

    @Subscribe
    @AllowConcurrentEvents
    void receivedPing(PingReceivedEvent pingReceivedEvent) {
        Channel channel = pingReceivedEvent.getCtx().channel();
        Attribute<UserChannel> attribute = channel.attr(AttributeKey.valueOf(GatewayChannelModule.KEY_GATEWAY_USER));
        UserChannel userChannel = attribute.get();
        if (userChannel == null) {
            pingReceivedEvent.closeChannel(null, WSErrors.ERROR_USER_SESSION_NOT_EXIST);
            return;
        }

        GatewaySessionHandler gatewaySessionHandler = gatewaySessionManager.getUserIdGatewaySessionHandlerMap().get(userChannel.getUserId());
        if (gatewaySessionHandler != null)
            gatewaySessionHandler.touch();
        //TODO 调用频率限制
        pingReceivedEvent.ping(channel);
    }
}
