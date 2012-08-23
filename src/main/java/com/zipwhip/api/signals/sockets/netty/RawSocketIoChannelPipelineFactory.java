package com.zipwhip.api.signals.sockets.netty;

import com.zipwhip.api.signals.sockets.netty.pipeline.handler.SocketIdleStateHandler;
import com.zipwhip.api.signals.sockets.netty.pipeline.handler.SocketIoCommandDecoder;
import com.zipwhip.api.signals.sockets.netty.pipeline.handler.SocketIoCommandEncoder;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.handler.codec.frame.DelimiterBasedFrameDecoder;
import org.jboss.netty.handler.codec.frame.Delimiters;
import org.jboss.netty.handler.codec.string.StringDecoder;
import org.jboss.netty.handler.codec.string.StringEncoder;
import org.jboss.netty.handler.timeout.IdleStateHandler;
import org.jboss.netty.util.Timer;

/**
 * Created with IntelliJ IDEA.
 * User: Michael
 * Date: 8/21/12
 * Time: 4:01 PM
 *
 * The pipeline for raw sockets.
 */
public class RawSocketIoChannelPipelineFactory implements ChannelPipelineFactory {

    public static final int DEFAULT_FRAME_SIZE = 8192;
    public static final int DEFAULT_PING_INTERVAL_SECONDS = 300; // when to ping inactive seconds
    public static final int DEFAULT_PONG_TIMEOUT_SECONDS = 30; // when to disconnect if a ping was not ponged by this time

    private final IdleStateHandler idleStateHandler;
    private final StringDecoder stringDecoder = new StringDecoder();
    private final StringEncoder stringEncoder = new StringEncoder();
    private final SocketIoCommandDecoder socketIoCommandDecoder = new SocketIoCommandDecoder();

    public RawSocketIoChannelPipelineFactory(Timer idleChannelTimer) {
        this(idleChannelTimer, DEFAULT_PING_INTERVAL_SECONDS, DEFAULT_PONG_TIMEOUT_SECONDS);
    }

    public RawSocketIoChannelPipelineFactory(Timer idleChannelTimer, int pingIntervalSeconds) {
        this(idleChannelTimer, pingIntervalSeconds, DEFAULT_PONG_TIMEOUT_SECONDS);
    }

    public RawSocketIoChannelPipelineFactory(Timer idleChannelTimer, int pingIntervalSeconds, int pongTimeoutSeconds) {
        idleStateHandler = new SocketIdleStateHandler(idleChannelTimer, pingIntervalSeconds, pongTimeoutSeconds);
    }

    /*
    * (non-Javadoc)
    *
    * @see com.zipwhip.api.signals.sockets.netty.SignalConnectionBase#getPipeline()
    */
    @Override
    public ChannelPipeline getPipeline() {
        return Channels.pipeline(
                new DelimiterBasedFrameDecoder(DEFAULT_FRAME_SIZE, Delimiters.lineDelimiter()),
                stringDecoder,
                socketIoCommandDecoder,
                stringEncoder,
                new SocketIoCommandEncoder(),
                idleStateHandler
        );
    }

}
