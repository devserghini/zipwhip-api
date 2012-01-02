/**
 * 
 */
package com.zipwhip.api.signals.sockets.netty;

import java.net.InetSocketAddress;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;
import org.jboss.netty.channel.AbstractChannel;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.socket.oio.OioClientSocketChannelFactory;

import com.zipwhip.api.signals.PingEvent;
import com.zipwhip.api.signals.SignalConnection;
import com.zipwhip.api.signals.commands.Command;
import com.zipwhip.api.signals.commands.PingPongCommand;
import com.zipwhip.api.signals.commands.SerializingCommand;
import com.zipwhip.api.signals.reconnect.ReconnectStrategy;
import com.zipwhip.events.ObservableHelper;
import com.zipwhip.events.Observer;
import com.zipwhip.lifecycle.CascadingDestroyableBase;
import com.zipwhip.signals.server.protocol.SocketIoProtocol;

/**
 * @author jdinsel
 *
 */
public abstract class SignalConnectionBase extends CascadingDestroyableBase implements SignalConnection {

	protected static final Logger LOGGER = Logger.getLogger(SignalConnectionBase.class);

	public static final int CONNECTION_TIMEOUT_SECONDS = 45;

	private static final int MAX_FRAME_SIZE = 65535;

	private static final int DEFAULT_PING_TIMEOUT = 1000 * 300; // when to ping, inactive seconds
	private static final int DEFAULT_PONG_TIMEOUT = 1000 * 30; // when to disconnect if a ping was not ponged by this time

	private String host = "signals.zipwhip.com";
	private int port = 3000;

	private int pingTimeout = DEFAULT_PING_TIMEOUT;
	private int pongTimeout = DEFAULT_PONG_TIMEOUT;

	private ExecutorService executor;

	private ScheduledFuture<?> pingTimeoutFuture;
	private ScheduledFuture<?> pongTimeoutFuture;
	private ScheduledExecutorService scheduledExecutor = Executors.newScheduledThreadPool(2);

	protected ObservableHelper<PingEvent> pingEvent = new ObservableHelper<PingEvent>();
	protected ObservableHelper<Command> receiveEvent = new ObservableHelper<Command>();
	protected ObservableHelper<Boolean> connectEvent = new ObservableHelper<Boolean>();
	protected ObservableHelper<String> exceptionEvent = new ObservableHelper<String>();
	protected ObservableHelper<Boolean> disconnectEvent = new ObservableHelper<Boolean>();

	protected ReconnectStrategy reconnectStrategy;
	private Runnable onSocketActivity;

	protected Channel channel;
	private ChannelFactory channelFactory = new OioClientSocketChannelFactory(Executors.newSingleThreadExecutor());

	private boolean networkDisconnect;
	private boolean doKeepalives;

	private long messageId = 0l;
	protected boolean connectionSent = false;

	public void init(ReconnectStrategy reconnectStrategy) {

		this.link(pingEvent);
		this.link(receiveEvent);
		this.link(connectEvent);
		this.link(exceptionEvent);
		this.link(disconnectEvent);
		this.link(reconnectStrategy);

		this.reconnectStrategy = reconnectStrategy;
		this.reconnectStrategy.setSignalConnection(this);
		this.doKeepalives = true;
	}

	@Override
	public synchronized Future<Boolean> connect() throws Exception {

		// Enforce a single connection
		if ((channel != null) && channel.isConnected()) {
			throw new Exception("Tried to connect but we already have a channel connected!");
		}

		channel = channelFactory.newChannel(getPipeline());

		if (channel instanceof AbstractChannel) {
			((AbstractChannel) channel).setOnSocketActivity(onSocketActivity);
		}

		final ChannelFuture channelFuture = channel.connect(new InetSocketAddress(host, port));

		FutureTask<Boolean> task = new FutureTask<Boolean>(new Callable<Boolean>() {

			@Override
			public Boolean call() throws Exception {

				channelFuture.await(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);

				boolean socketConnected = !channelFuture.isCancelled() && channelFuture.isSuccess() && channelFuture.getChannel().isConnected();

				networkDisconnect = socketConnected;

				return Boolean.valueOf(socketConnected);
			}
		});

		if (executor == null) {
			executor = Executors.newSingleThreadExecutor();
		}
		executor.execute(task);

		return task;
	}

	@Override
	public synchronized Future<Void> disconnect() {
		return disconnect(false);
	}

	@Override
	public synchronized Future<Void> disconnect(final boolean network) {

		FutureTask<Void> task = new FutureTask<Void>(new Callable<Void>() {
			@Override
			public Void call() throws Exception {

				networkDisconnect = network;

				// If this was not an automatic disconnect stop the retry logic
				if (!networkDisconnect) {
					reconnectStrategy.stop();
				}

				if (channel != null) {
					ChannelFuture closeFuture = channel.close().await();
					LOGGER.debug("Closing channel success was " + closeFuture.isSuccess());
				}

				executor.shutdownNow();
				executor = null;

				if (pingTimeoutFuture != null) {
					pingTimeoutFuture.cancel(true);
				}

				if (pongTimeoutFuture != null) {
					pongTimeoutFuture.cancel(true);
				}

				return null;
			}
		});

		if (executor != null) {
			executor.execute(task);
		}

		return task;
	}

	@Override
	public void keepalive() {

		LOGGER.debug("Keepalive requested!");

		cancelPong();
		schedulePing(true);
	}

	@Override
	public void startKeepalives() {

		LOGGER.debug("Start keepalives requested!");

		doKeepalives = true;
		schedulePing(false);
	}

	@Override
	public void stopKeepalives() {

		LOGGER.debug("Start keepalives requested!");

		doKeepalives = false;
		cancelPing();
	}

	@Override
	public void send(SerializingCommand command) {

		String message = constructMessage(command, null);

		// send this over the wire.
		channel.write(message);
	}

	protected String constructMessage(SerializingCommand command) {
		return constructMessage(command, null);
	}

	protected String constructMessage(SerializingCommand command, String clientId) {
		String message;
		if (connectionSent) {
			message = SocketIoProtocol.jsonMessageResponse(messageId++, command.serialize());
		} else {
			message = SocketIoProtocol.connectMessageResponse(command.serialize(), clientId);
			connectionSent = true;
		}
		return message;
	}

	@Override
	public boolean isConnected() {
		return (channel != null) && channel.isConnected();
	}

	@Override
	public void onMessageReceived(Observer<Command> observer) {
		receiveEvent.addObserver(observer);
	}

	@Override
	public void onConnect(Observer<Boolean> observer) {
		connectEvent.addObserver(observer);
	}

	@Override
	public void onDisconnect(Observer<Boolean> observer) {
		disconnectEvent.addObserver(observer);
	}

	@Override
	public void removeOnConnectObserver(Observer<Boolean> observer) {
		connectEvent.removeObserver(observer);
	}

	@Override
	public void removeOnDisconnectObserver(Observer<Boolean> observer) {
		disconnectEvent.removeObserver(observer);
	}

	@Override
	public void onPingEvent(Observer<PingEvent> observer) {
		pingEvent.addObserver(observer);
	}

	@Override
	public void onExceptionCaught(Observer<String> observer) {
		exceptionEvent.addObserver(observer);
	}

	@Override
	public void setHost(String host) {
		this.host = host;
	}

	@Override
	public void setPort(int port) {
		this.port = port;
	}

	@Override
	public int getPingTimeout() {
		return pingTimeout;
	}

	@Override
	public void setPingTimeout(int pingTimeout) {
		this.pingTimeout = pingTimeout;
	}

	@Override
	public int getPongTimeout() {
		return pongTimeout;
	}

	@Override
	public void setPongTimeout(int pongTimeout) {
		this.pongTimeout = pongTimeout;
	}

	@Override
	public ReconnectStrategy getReconnectStrategy() {
		return reconnectStrategy;
	}

	@Override
	public void setReconnectStrategy(ReconnectStrategy reconnectStrategy) {

		// Stop our old strategy
		if (this.reconnectStrategy != null) {
			this.reconnectStrategy.stop();
			this.reconnectStrategy.destroy();
		}

		this.reconnectStrategy = reconnectStrategy;
		this.reconnectStrategy.setSignalConnection(this);
	}

	public Runnable getOnSocketActivity() {
		return onSocketActivity;
	}

	public void setOnSocketActivity(Runnable onSocketActivity) {
		this.onSocketActivity = onSocketActivity;
	}

	@Override
	protected void onDestroy() {

		if (isConnected()) {
			disconnect();
		}

		if (channelFactory != null) {
			channelFactory.releaseExternalResources();
		}

		if (pingTimeoutFuture != null) {
			pingTimeoutFuture.cancel(true);
		}

		if (pongTimeoutFuture != null) {
			pongTimeoutFuture.cancel(true);
		}

		if (scheduledExecutor != null) {
			scheduledExecutor.shutdownNow();
		}
	}

	private void schedulePing(boolean now) {

		cancelPing();

		LOGGER.debug("Scheduling a PING to start in " + pingTimeout + "ms");
		pingEvent.notifyObservers(this, PingEvent.PING_SCHEDULED);

		pingTimeoutFuture = scheduledExecutor.schedule(new Runnable() {
			@Override
			public void run() {

				LOGGER.debug("Sending a PING");
				pingEvent.notifyObservers(this, PingEvent.PING_SENT);

				send(PingPongCommand.getShortformInstance());

				pongTimeoutFuture = scheduledExecutor.schedule(new Runnable() {
					@Override
					public void run() {

						LOGGER.warn("PONG timeout, disconnecting...");
						pingEvent.notifyObservers(this, PingEvent.PONG_TIMEOUT);

						disconnect(true);
					}
				}, pongTimeout, TimeUnit.MILLISECONDS);

			}
		}, now ? 0 : pingTimeout, TimeUnit.MILLISECONDS);
	}

	private void receivePong(PingPongCommand command) {

		if (command.isRequest()) {

			LOGGER.debug("Received a REVERSE PING");

			PingPongCommand reversePong = PingPongCommand.getNewLongformInstance();
			reversePong.setTimestamp(command.getTimestamp());
			reversePong.setToken(command.getToken());

			LOGGER.debug("Sending a REVERSE PONG");
			send(reversePong);

		} else {

			LOGGER.debug("Received a PONG");
			pingEvent.notifyObservers(this, PingEvent.PONG_RECEIVED);
		}

		cancelPong();

		if (doKeepalives) {
			schedulePing(false);
		}
	}

	private void cancelPing() {

		if ((pingTimeoutFuture != null) && !pingTimeoutFuture.isCancelled()) {

			if (pingTimeoutFuture != null) {

				LOGGER.debug("Resetting scheduled PING");
				pingEvent.notifyObservers(this, PingEvent.PING_CANCELLED);

				pingTimeoutFuture.cancel(false);
			}
		}
	}

	private void cancelPong() {

		if ((pongTimeoutFuture != null) && !pongTimeoutFuture.isCancelled()) {

			LOGGER.debug("Resetting timeout PONG");

			pingEvent.notifyObservers(this, PingEvent.PONG_CANCELLED);
			pongTimeoutFuture.cancel(false);
		}
	}

	protected abstract ChannelPipeline getPipeline();

	public final String getHost() {
		return host;
	}

	public final int getPort() {
		return port;
	}

}

