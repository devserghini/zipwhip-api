package com.zipwhip.api;

import com.zipwhip.api.exception.NotAuthenticatedException;
import com.zipwhip.api.response.ServerResponse;
import com.zipwhip.api.settings.PreferencesSettingsStore;
import com.zipwhip.api.settings.SettingsStore;
import com.zipwhip.api.settings.SettingsVersionStore;
import com.zipwhip.api.settings.VersionStore;
import com.zipwhip.api.signals.SignalProvider;
import com.zipwhip.api.signals.VersionMapEntry;
import com.zipwhip.api.signals.commands.SubscriptionCompleteCommand;
import com.zipwhip.api.signals.sockets.ConnectionHandle;
import com.zipwhip.api.signals.sockets.ConnectionState;
import com.zipwhip.concurrent.*;
import com.zipwhip.events.Observer;
import com.zipwhip.important.ImportantTaskExecutor;
import com.zipwhip.lifecycle.DestroyableBase;
import com.zipwhip.signals.presence.Presence;
import com.zipwhip.util.Asserts;
import com.zipwhip.util.StringUtil;
import org.apache.log4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;

import static com.zipwhip.concurrent.ThreadUtil.ensureLock;

/**
 * A base class for future implementation to extend.
 * <p/>
 * It takes all the non-API specific stuff out of ZipwhipClient implementations.
 * <p/>
 * If some class wants to communicate with Zipwhip, then it needs to extend this
 * class. This class gives functionality that can be used to parse Zipwhip API.
 * This naming convention was copied from Spring (JmsSupport) base class.
 */
public abstract class ClientZipwhipNetworkSupport extends ZipwhipNetworkSupport {

    protected static final Logger LOGGER = Logger.getLogger(ClientZipwhipNetworkSupport.class);

    protected ImportantTaskExecutor importantTaskExecutor;
    protected long signalsConnectTimeoutInSeconds = 30;

    protected SignalProvider signalProvider;
    protected SettingsStore settingsStore = new PreferencesSettingsStore();
    protected VersionStore versionsStore = new SettingsVersionStore(settingsStore);

    // this is so we can block until SubscriptionCompleteCommand comes in.
    // do we have a current SubscriptionCompleteCommand to use.
    protected ObservableFuture connectFuture;

    public ClientZipwhipNetworkSupport(ApiConnection connection, SignalProvider signalProvider) {
        super(connection);

        if (signalProvider != null) {
            setSignalProvider(signalProvider);
            link(signalProvider);
        }

        // Start listening to provider events that interest us
        initSignalProviderEvents();
    }

    public ObservableFuture connect() throws Exception {
        return connect(null);
    }

    public synchronized ObservableFuture connect(final Presence presence) throws Exception {
        synchronized (signalProvider) {
            final ObservableFuture<ConnectionHandle> finalConnectFuture = getUnchangingConnectFuture();
            if (finalConnectFuture != null) {
                LOGGER.debug(String.format("Returning %s since it's still active", connectFuture));
                return finalConnectFuture;
            }

            // throw any necessary sanity checks.
            validateConnectState();

            // put the state into the local store
            setupSettingsStoreForConnection();

            // pull the state out.
            boolean expectingSubscriptionCompleteCommand = Boolean.parseBoolean(settingsStore.get(SettingsStore.Keys.EXPECTS_SUBSCRIPTION_COMPLETE));
            String clientId = settingsStore.get(SettingsStore.Keys.CLIENT_ID);
            String sessionKey = settingsStore.get(SettingsStore.Keys.SESSION_KEY);
            Map<String, Long> versions = versionsStore.get();

            // this future updates itself (clearing out this.connectFuture)
            final NestedObservableFuture<ConnectionHandle> future = new NestedObservableFuture<ConnectionHandle>(this);
            synchronized (future) {
                // setting this will cause the other threads to notice that we're connecting.
                // set it FIRST in case the below line finishes too early! (aka: synchronously!)
                setConnectFuture(future);

                ObservableFuture<ConnectionHandle> requestFuture = importantTaskExecutor.enqueue(
                        null,
                        new ConnectViaSignalProviderTask(clientId, sessionKey, presence, versions, expectingSubscriptionCompleteCommand),
                        getSignalsConnectTimeoutInSeconds());


                requestFuture.addObserver(new ClearConnectFutureOnCompleteObserver(future));

                // run the execution now (have to do this AFTER setting connectFuture in case connection is freakishly fast (ie: sync)).
                future.setNestedFuture(requestFuture);

                return future;
            }
        }
    }

    public synchronized ObservableFuture<ConnectionHandle> disconnect() {
        return disconnect(false);
    }

    public synchronized ObservableFuture<ConnectionHandle> disconnect(final boolean causedByNetwork) {
//        validateConnectState();

        return signalProvider.disconnect(causedByNetwork);
    }

    /**
     * Tells you if this connection is 100% ready to go.
     * <p/>
     * Within the context of ZipwhipClient, we have defined the "connected" state
     * to mean both having a TCP connection AND having a SubscriptionComplete. (ie: connected & authenticated)
     * <p/>
     * Internally we shouldn't use this method because we don't control
     *
     * @return
     */
    public boolean isConnected() {
        ConnectionState connectionState = signalProvider.getConnectionState();

        switch (connectionState) {
            case CONNECTING:
            case CONNECTED:
            case DISCONNECTING:
            case DISCONNECTED:
                return false;
            case AUTHENTICATED:
                // the SignalProvider says that they are AUTHENTICATED. That just means that they have
                // received the {action:CONNECT} command back. We on the other hand need to receive
                // a SubscriptionCompleteCommand. The best way to do that is to check that the current
                // clientId is in our local database as "subscribed"
                synchronized (settingsStore) {
                    String clientId = signalProvider.getClientId();
                    String savedClientId = this.settingsStore.get(SettingsStore.Keys.CLIENT_ID);
                    String lastSubscribedClientId = this.settingsStore.get(SettingsStore.Keys.LAST_SUBSCRIBED_CLIENT_ID);
                    if (StringUtil.equals(clientId, savedClientId)) {
                        // it's current!
                        if (StringUtil.equals(clientId, lastSubscribedClientId)) {
                            // we're up to date!
                            return true;
                        }
                    }

                    return false;
                }
        }

        return false;
    }

    protected void initSignalProviderEvents() {
        signalProvider.getVersionChangedEvent().addObserver(updateVersionsStoreOnVersionChanged);
    }

    private void setupSettingsStoreForConnection() {
        synchronized (settingsStore) {
            /**
             * Validate SESSION KEYS
             */
            boolean cleared = false;
            boolean expectingSubscriptionCompleteCommand = false;

            String existingSessionKey = connection.getSessionKey();
            String existingClientId = signalProvider == null ? null : signalProvider.getClientId();

            String storedSessionKey = settingsStore.get(SettingsStore.Keys.SESSION_KEY);
            String storedClientId = settingsStore.get(SettingsStore.Keys.CLIENT_ID);

            String correctSessionKey = storedSessionKey;
            String correctClientId = existingClientId;

            /**
             * If the sessionKey has changed we need to invalidate the settings data
             */
            if (StringUtil.exists(existingSessionKey) && !StringUtil.equals(existingSessionKey, storedSessionKey)) {
                expectingSubscriptionCompleteCommand = true;
                LOGGER.debug("New or changed sessionKey, resetting session key in settings store");

                cleared = true;
                settingsStore.clear();

                correctSessionKey = existingSessionKey;
            }

            /**
             * If the clientId has changed we need to invalidate the settings data
             */
            if (StringUtil.isNullOrEmpty(storedClientId) || (StringUtil.exists(existingClientId) && !StringUtil.equals(storedClientId, existingClientId))) {
                expectingSubscriptionCompleteCommand = true;
                LOGGER.debug("ClientId has changed, resetting client id in settings store");

                cleared = true;
                settingsStore.clear();

                correctClientId = existingClientId;
            }

            if (cleared) {
                settingsStore.put(SettingsStore.Keys.CLIENT_ID, correctClientId);
                settingsStore.put(SettingsStore.Keys.SESSION_KEY, correctSessionKey);
            }

            // always put this one in
            settingsStore.put(SettingsStore.Keys.EXPECTS_SUBSCRIPTION_COMPLETE, String.valueOf(expectingSubscriptionCompleteCommand));
        }
    }

    private void validateConnectState() throws Exception {
        // if we are already connecting, don't do another connect.
        // we need to determine if we're authenticated enough
        if (!connection.isConnected() || !connection.isAuthenticated()) {
            throw new NotAuthenticatedException("The connection cannot operate at this time");
        }
    }

    private final Observer<VersionMapEntry> updateVersionsStoreOnVersionChanged = new Observer<VersionMapEntry>() {

        @Override
        public void notify(Object sender, VersionMapEntry item) {
            versionsStore.set(item.getKey(), item.getValue());
        }
    };

    private void accessConnectingFuture() {
        ensureLock(signalProvider);
    }

    private void modifyConnectingFuture(ObservableFuture<ConnectionHandle> future) {
        accessConnectingFuture();
        ensureLock(connectFuture);
        ensureLock(future);

        Asserts.assertTrue(connectFuture == null || future == connectFuture, "");
    }

    private void clearConnectingFuture(ObservableFuture<ConnectionHandle> future) {
        modifyConnectingFuture(future);

        connectFuture = null;
    }

    private void setConnectFuture(ObservableFuture<ConnectionHandle> future) {
        accessConnectingFuture();
        modifyConnectingFuture(future);

        if (future == null) {
            throw new RuntimeException("Use clearConnectingFuture() instead");
        }

        if (connectFuture != null) {
            throw new RuntimeException("The connectFuture was not null. You have to clear it first");
        }

        connectFuture = future;
    }

    private ObservableFuture<ConnectionHandle> getUnchangingConnectFuture() {
        accessConnectingFuture();
        return connectFuture;
    }

    public SettingsStore getSettingsStore() {
        return settingsStore;
    }

    public void setSettingsStore(SettingsStore store) {
        this.settingsStore = store;
        this.versionsStore = new SettingsVersionStore(store);
    }

    public SignalProvider getSignalProvider() {
        return signalProvider;
    }

    public void setSignalProvider(SignalProvider signalProvider) {
        this.signalProvider = signalProvider;
    }

    public ImportantTaskExecutor getImportantTaskExecutor() {
        return importantTaskExecutor;
    }

    public void setImportantTaskExecutor(ImportantTaskExecutor importantTaskExecutor) {
        this.importantTaskExecutor = importantTaskExecutor;
    }

    public long getSignalsConnectTimeoutInSeconds() {
        return signalsConnectTimeoutInSeconds;
    }

    public void setSignalsConnectTimeoutInSeconds(long signalsConnectTimeoutInSeconds) {
        this.signalsConnectTimeoutInSeconds = signalsConnectTimeoutInSeconds;
    }

    /**
     * This class lets us 100% guarantee that requests won't overlap/criss-cross. We are able to capture the
     * incoming clientId/sessionKey/connectionHandle and ensure that it doesn't change while we're processing
     * this multi-staged process. We'll do a number of "ConnectionHandle" verification steps to ensure
     * that we're reacting to events that we need.
     * <p/>
     * This Task will execute a signalProvider.connect() and IF NEEDED queue up a /signals/connect request. If
     * we don't expect to receive a SubscriptionCompleteCommand then we'll just quit early.
     * <p/>
     * The caller must decide what to do if this request fails. This class does not do any reconnect or error
     * handling if timeouts occur.
     */
    private class ConnectViaSignalProviderTask extends DestroyableBase implements Callable<ObservableFuture<ConnectionHandle>> {

        final String clientId;
        final String sessionKey;
        final boolean expectingSubscriptionCompleteCommand;
        final Presence presence;
        final Map<String, Long> versions;

        ConnectionHandle signalsConnectionHandle;

        // this is the resultingFuture that we send back to the caller.
        // the caller will "nest" this with the "connectFuture"
        final ObservableFuture<ConnectionHandle> resultFuture;

        private boolean started = false;

        /**
         * These values get populated during the "newClientIdReceived" process.
         */
        private boolean newClientIdReceivedWhileWaitingToConnect = false;
        private String newClientIdWhileWaitingToConnect = null;
        private ConnectionHandle signalsConnectionHandleFromNewClientId;

        private ConnectViaSignalProviderTask(String clientId, String sessionKey, Presence presence, Map<String, Long> versions, boolean expectingSubscriptionCompleteCommand) {
            this.clientId = clientId;
            this.sessionKey = sessionKey;
            this.expectingSubscriptionCompleteCommand = expectingSubscriptionCompleteCommand;
            this.presence = presence;
            this.versions = versions;
            this.resultFuture = new DefaultObservableFuture<ConnectionHandle>(ClientZipwhipNetworkSupport.this) {
                @Override
                public String toString() {
                    return "[ConnectViaSignalProviderTask: " + super.toString();
                }
            };
        }

        @Override
        public synchronized ObservableFuture<ConnectionHandle> call() throws Exception {
            try {
                // do some basic assertions to ensure sanity checks. (did it change while we waited to execute?)
                validateState();
            } catch (Exception e) {
                return new FakeFailingObservableFuture<ConnectionHandle>(this, e);
            }

            // When resultFuture completes, we need to tear down the global observers that we added.
            unbindGlobalEventsOnComplete(resultFuture);

            bindGlobalEvents();

            ObservableFuture<ConnectionHandle> requestFuture;
            try {
                started = true;
                requestFuture = signalProvider.connect(clientId, versions, presence);
            } catch (Exception e) {
                resultFuture.setFailure(e);

                // this future will be linked to the "connectFuture" correctly.
                return resultFuture;
            }

            /**
             * The finish conditions are 2 fold.
             *
             * == Are we expecting a subscriptionCompleteCommand?
             *
             * 1. If so, we finish when we receive it, or time out.
             * 2. If not, we finish when the {action:CONNECT} comes back.
             */
            attachFinishingEvents(requestFuture);

            selfDestructOnComplete();

            return resultFuture;
        }

        private void selfDestructOnComplete() {
            resultFuture.addObserver(new Observer<ObservableFuture<ConnectionHandle>>() {
                @Override
                public void notify(Object sender, ObservableFuture<ConnectionHandle> item) {
                    ConnectViaSignalProviderTask.this.destroy();
                }
            });
        }

        /**
         * The finish conditions are 2 fold.
         * <p/>
         * == Are we expecting a subscriptionCompleteCommand?
         * <p/>
         * 1. If so, we finish when we receive it, or time out.
         * 2. If not, we finish when the {action:CONNECT} comes back.
         */
        private void attachFinishingEvents(ObservableFuture<ConnectionHandle> requestFuture) {
            LOGGER.debug("Expecting subscriptionComplete: " + expectingSubscriptionCompleteCommand);

            requestFuture.addObserver(processSignalProviderConnectComplete);

            if (expectingSubscriptionCompleteCommand) {
                // only run the "reset connectFuture" observable if this initial internet request fails
                // (timeout, socket exception, etc).
                // If requestFuture is successful, we still need to wait for the SubscriptionCompleteCommand
                // to come back.
                requestFuture.addObserver(
                        new OnlyRunIfNotSuccessfulObserverAdapter<ConnectionHandle>(
                                new CopyFutureStatusToNestedFuture<ConnectionHandle>(resultFuture)));

                // if this "requestFuture" succeeds, we need to let the onSubscriptionCompleteCommand finish
                // the "connectFuture". That's because we need to wait for the SubscriptionCompleteCommand.
            } else {
                requestFuture.addObserver(new CopyFutureStatusToNestedFuture<ConnectionHandle>(resultFuture));
            }
        }

        private synchronized void unbindGlobalEventsOnComplete(ObservableFuture<ConnectionHandle> future) {
            future.addObserver(new Observer<ObservableFuture<ConnectionHandle>>() {
                @Override
                public void notify(Object sender, ObservableFuture<ConnectionHandle> item) {
                    if (!started) {
                        LOGGER.error("It seems that our future completed without us starting. Is this possible? Did someone cancel something?");
                        return;
                    }

                    unbindGlobalEvents();
                }
            });
        }

        private synchronized void bindGlobalEvents() {
            // NOTE: We have to protect against it calling too soon.
            // we need to add our observers early (in case connect() is synchronous or too fast!)
            // what if we addObserver here, and then between this line and the next it gets called.
            signalProvider.getConnectionChangedEvent().addObserver(failConnectingFutureIfDisconnectedObserver);

            if (expectingSubscriptionCompleteCommand) {
                signalProvider.getNewClientIdReceivedEvent().addObserver(executeSignalsConnectOnNewClientIdReceived);
            }
        }

        private synchronized void unbindGlobalEvents() {
            signalProvider.getConnectionChangedEvent().removeObserver(failConnectingFutureIfDisconnectedObserver);
            signalProvider.getNewClientIdReceivedEvent().removeObserver(executeSignalsConnectOnNewClientIdReceived);
        }

        private final Observer<ObservableFuture<ConnectionHandle>> processSignalProviderConnectComplete = new Observer<ObservableFuture<ConnectionHandle>>() {

            @Override
            public void notify(Object sender, ObservableFuture<ConnectionHandle> requestFuture) {
                LOGGER.debug("processSignalProviderConnectComplete.notify()");

                synchronized (resultFuture) {
                    if (resultFuture.isCancelled()) {
                        LOGGER.error("processSignalProviderConnectComplete: The resultFuture was cancelled. So we're going to quit.");
                        return;
                    }

                    Asserts.assertTrue(!resultFuture.isDone(), "The resultFuture should not be done!");

                    synchronized (requestFuture) {
                        if (requestFuture.isCancelled()) {
                            LOGGER.error("processSignalProviderConnectComplete: The requestFuture was cancelled.");
                            return;
                        }

                        if (requestFuture.isSuccess()) {
                            LOGGER.debug("processSignalProviderConnectComplete: requestFuture was successful. We now have a connectionHandle.");
                            final ConnectionHandle signalProviderConnectionHandle = ConnectViaSignalProviderTask.this.signalsConnectionHandle = requestFuture.getResult();

                            Asserts.assertTrue(signalProviderConnectionHandle != null, "This can never be null");

                            if (newClientIdReceivedWhileWaitingToConnect) {
                                if (signalsConnectionHandleFromNewClientId != signalProviderConnectionHandle) {
                                    LOGGER.error(String.format("Received a clientId when listening but it was for connection %s instead of %s. Quitting.", signalsConnectionHandleFromNewClientId, signalProviderConnectionHandle));
                                    return;
                                }

                                LOGGER.debug("processSignalProviderConnectComplete: processing the newClientId that we postponed");
                                // we received it already
                                processNewClientId(signalProviderConnectionHandle, newClientIdWhileWaitingToConnect);
                            }
                        }
                    }
                }
            }

            @Override
            public String toString() {
                return "[ConnectViaSignalProvider/ProcessSignalProvider.connect()]";
            }
        };

        private final Observer<String> executeSignalsConnectOnNewClientIdReceived = new Observer<String>() {

            @Override
            public synchronized void notify(Object sender, String newClientId) {
                if (!started || isDestroyed()) {
                    // this fired too fast, it's not for our request!
                    return;
                }

                ConnectionHandle connectionHandle = (ConnectionHandle) sender;

                /**
                 * TODO: write description of this order-of-operations problem
                 */
                if (ConnectViaSignalProviderTask.this.signalsConnectionHandle == null) {
                    ConnectViaSignalProviderTask.this.newClientIdReceivedWhileWaitingToConnect = true;
                    ConnectViaSignalProviderTask.this.signalsConnectionHandleFromNewClientId = connectionHandle;
                    ConnectViaSignalProviderTask.this.newClientIdWhileWaitingToConnect = newClientId;
                    LOGGER.debug("The newClientId was received too early, so we queued it up for processing later.");

                    // we already handled it. Don't handle it twice!
                    signalProvider.getNewClientIdReceivedEvent().removeObserver(this);
                    return;
                } else if (ConnectViaSignalProviderTask.this.signalsConnectionHandle != connectionHandle) {
                    LOGGER.error("Noticed that the connectionHandles didn't match up. Did we get a request we weren't expecting?");
                    return;
                } else {
                    // we already handled it. Don't handle it twice!
                    signalProvider.getNewClientIdReceivedEvent().removeObserver(this);
                }

                processNewClientId(connectionHandle, newClientId);
            }
        };

        private void processNewClientId(ConnectionHandle connectionHandle, String newClientId) {
            // NOTE: when we do a reset, we're going to get a new clientId that is null
//                if (StringUtil.isNullOrEmpty(newClientId)) {
//                    settingsStore.put(SettingsStore.Keys.CLIENT_ID, newClientId);
//                    return;
//                }

            String oldClientId = settingsStore.get(SettingsStore.Keys.CLIENT_ID);
//                final String sessionKey = connection.getSessionKey();
            final ObservableFuture<SubscriptionCompleteCommand> signalsConnectFuture;

            if (StringUtil.exists(oldClientId)) {

                // clientId changed, unsubscribe the old one, and sub the new one
                if (!oldClientId.equals(newClientId)) {
                    settingsStore.clear();

                    settingsStore.put(SettingsStore.Keys.SESSION_KEY, sessionKey);
                    settingsStore.put(SettingsStore.Keys.CLIENT_ID, newClientId);
                    settingsStore.put(SettingsStore.Keys.EXPECTS_SUBSCRIPTION_COMPLETE, "true");

                    executeSignalsDisconnect(sessionKey, oldClientId);

                    signalsConnectFuture = executeSignalsConnect(signalsConnectionHandle, newClientId, sessionKey);
                } else {
                    signalsConnectFuture = null;
                    // just the same clientId
                }
            } else {
                settingsStore.put(SettingsStore.Keys.CLIENT_ID, newClientId);
                settingsStore.put(SettingsStore.Keys.EXPECTS_SUBSCRIPTION_COMPLETE, "true");

                signalsConnectFuture = executeSignalsConnect(connectionHandle, newClientId, sessionKey);
            }

            if (signalsConnectFuture != null) {
                signalsConnectFuture.addObserver(new UpdatelocalStoreWithLastKnownSubscribedClientId(ClientZipwhipNetworkSupport.this, sessionKey, newClientId));

                signalsConnectFuture.addObserver(new TearDownConnectionIfFailureObserver(newClientId, signalProvider));

                // cascade the success/failure/cancellation
                signalsConnectFuture.addObserver(new CascadeStateObserver<ConnectionHandle, SubscriptionCompleteCommand>(resultFuture, signalsConnectionHandle));
            }
        }

        private void executeSignalsDisconnect(String sessionKey, String clientId) {
            // Do a disconnect then connect
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("clientId", clientId);
            params.put("sessions", sessionKey);
            try {
                executeAsync(SIGNALS_DISCONNECT, params);
            } catch (Exception e) {
                LOGGER.warn("Couldn't execute SIGNALS_DISCONNECT. We're going to ignore this problem.", e);
            }
        }

        private final Observer<Boolean> failConnectingFutureIfDisconnectedObserver = new Observer<Boolean>() {

            boolean fired = false;

            @Override
            public synchronized void notify(Object sender, Boolean connected) {
                if (!started || isDestroyed()) {
                    // this fired too fast. It's not for our request! Ignore it.
                    LOGGER.warn(String.format("failConnectingFutureIfDisconnectedObserver skipping call. requestFuture:%s/isDestroyed:%s", started, isDestroyed()));
                    return;
                }

                // if not connected, null out the parent future.
                // NOTE: We need to do this the hard way because we need to ensure the right connection got killed.
                if (!connected) {
                    signalProvider.getConnectionChangedEvent().removeObserver(this);

                    Asserts.assertTrue(!fired, "failConnectingFutureIfDisconnectedObserver fired twice. That's not allowed.");
                    fired = true;

                    resultFuture.setFailure(new Exception("Disconnected"));
                }
            }

            @Override
            public String toString() {
                return "failConnectingFutureIfDisconnectedObserver";
            }
        };

        /**
         * Execute a /signals/connect webcall s
         *
         * @param clientId
         * @param sessionKey
         * @return
         */
        private ObservableFuture<SubscriptionCompleteCommand> executeSignalsConnect(ConnectionHandle connectionHandle, final String clientId, final String sessionKey) {
            /**
             * This is a call that will time out. We had to do the "importantTaskExecutor" in order to allow Android AlarmManager
             * to run the scheduling.
             */
            final ObservableFuture<SubscriptionCompleteCommand> signalsConnectFuture =
                    importantTaskExecutor.enqueue(null, new SignalsConnectTask(connectionHandle, sessionKey, clientId), signalsConnectTimeoutInSeconds);

            return signalsConnectFuture;
        }

        private void validateState() {
            // we are executing either synchronously (we already have the lock) or async in the core executor.
            // either way it's proper to sync on "this"
            synchronized (ClientZipwhipNetworkSupport.this) {
                // this is the proper order of sync [client->provider]
                synchronized (signalProvider) {
                    Asserts.assertTrue(signalProvider.getConnectionState() != ConnectionState.CONNECTED, "Order of operations failure! Already connected!");
                    Asserts.assertTrue(signalProvider.getConnectionState() != ConnectionState.AUTHENTICATED, "Order of operations failure! Already authenticated!");
                }
            }
        }

        @Override
        public String toString() {
            return "ConnectViaSignalProviderTask";
        }

        @Override
        protected void onDestroy() {
            LOGGER.debug(this.getClass().toString() + " destroyed.");
        }
    }

    private static class UpdatelocalStoreWithLastKnownSubscribedClientId implements Observer<ObservableFuture<SubscriptionCompleteCommand>> {

        private final ClientZipwhipNetworkSupport client;
        private final String clientId;
        private final String sessionKey;

        private UpdatelocalStoreWithLastKnownSubscribedClientId(ClientZipwhipNetworkSupport client, String sessionKey, String clientId) {
            this.client = client;
            this.sessionKey = sessionKey;
            this.clientId = clientId;
        }

        @Override
        public void notify(Object sender, ObservableFuture<SubscriptionCompleteCommand> future) {
            synchronized (future) {
                if (future.isCancelled()) {
                    return;
                }

                if (future.isSuccess()) {
                    // the subscriptionId is the sessionKey because we request it as so.
//                    Asserts.assertTrue(StringUtil.equals(future.getResult().getSubscriptionId(), sessionKey), "");
                    // TODO: Signal Server needs to provide subscriptionId in SubscriptionCompleteCommand: http://angela.zipwhip.com/issues/7678
                    if (!StringUtil.equals(future.getResult().getSubscriptionId(), sessionKey)) {
                        LOGGER.error("SubscriptionCompleteCommand did not provide subscriptionId!");
                    }

                    client.getSettingsStore().put(SettingsStore.Keys.EXPECTS_SUBSCRIPTION_COMPLETE, "false");
                    client.getSettingsStore().put(SettingsStore.Keys.LAST_SUBSCRIBED_CLIENT_ID, clientId);
                } else {

                }
            }
        }
    }

    /**
     * This class's job is to do a /signals/connect and wrap the return from the server in one mega Future.
     * <p/>
     * It is NOT allowed to change the state of any parent property.
     */
    private class SignalsConnectTask implements Callable<ObservableFuture<SubscriptionCompleteCommand>> {

        private final String sessionKey;
        private final String clientId;

        /**
         * This is the connectionHandle that we are operating on.
         */
        private final ConnectionHandle connectionHandle;

        private SignalsConnectTask(ConnectionHandle connectionHandle, String sessionKey, String clientId) {
            this.sessionKey = sessionKey;
            this.connectionHandle = connectionHandle;
            this.clientId = clientId;
        }

        @Override
        public synchronized ObservableFuture<SubscriptionCompleteCommand> call() throws Exception {

            // it's important that this future is synchronous (no executor)
            final ObservableFuture<SubscriptionCompleteCommand> resultFuture = new DefaultObservableFuture<SubscriptionCompleteCommand>(this);
            final Observer<Boolean>[] onDisconnectObserver = new Observer[1];

            final Observer<SubscriptionCompleteCommand> onSubscriptionCompleteObserver = new Observer<SubscriptionCompleteCommand>() {
                @Override
                public void notify(Object sender, SubscriptionCompleteCommand item) {
                    synchronized (ClientZipwhipNetworkSupport.this) {
                        synchronized (signalProvider) {
                            synchronized (SignalsConnectTask.this) {
                                signalProvider.getSubscriptionCompleteReceivedEvent().removeObserver(this);
                                signalProvider.getConnectionChangedEvent().removeObserver(onDisconnectObserver[0]);

                                LOGGER.debug("Successing");
                                resultFuture.setSuccess(item);
                            }
                        }
                    }
                }

                @Override
                public String toString() {
                    return "SignalsConnectTask/onSubscriptionCompleteObserver";
                }
            };

            onDisconnectObserver[0] = new Observer<Boolean>() {
                @Override
                public void notify(Object sender, Boolean item) {
                    synchronized (ClientZipwhipNetworkSupport.this) {
                        synchronized (signalProvider) {
                            synchronized (SignalsConnectTask.this) {
                                if (item) {
                                    // we connected?
                                    return;
                                }

                                // on any kind of connection change, we need to just abort
                                signalProvider.getConnectionChangedEvent().removeObserver(onDisconnectObserver[0]);
                                signalProvider.getSubscriptionCompleteReceivedEvent().removeObserver(onSubscriptionCompleteObserver);

                                LOGGER.debug("Failing (disconnected)");
                                resultFuture.setFailure(new Exception("Disconnected while waiting for SubscriptionCompleteCommand to come in! " + item));
                            }
                        }
                    }
                }

                @Override
                public String toString() {
                    return "SignalsConnectTask/onDisconnectObserver";
                }
            };

            signalProvider.getConnectionChangedEvent().addObserver(onDisconnectObserver[0]);
            signalProvider.getSubscriptionCompleteReceivedEvent().addObserver(onSubscriptionCompleteObserver);

            if (resultFuture.isDone()) {
                // wow it finished already?
                return resultFuture;
            }

            ServerResponse response;
            try {
                response = executeSync(ZipwhipNetworkSupport.SIGNALS_CONNECT, getSignalsConnectParams(sessionKey, clientId));
            } catch (Exception e) {
                LOGGER.error("Failed to execute request: ", e);
                resultFuture.setFailure(e);

                return resultFuture;
            }

            if (response == null) {
                LOGGER.error("The response from zipwhip was null!?");
                resultFuture.setFailure(new NullPointerException("The executeSync() response was null"));

                return resultFuture;
            }

            if (!response.isSuccess()) {
                resultFuture.setFailure(new Exception(response.getRaw()));

                return resultFuture;
            }

            resultFuture.addObserver(new Observer<ObservableFuture<SubscriptionCompleteCommand>>() {

                @Override
                public void notify(Object sender, ObservableFuture<SubscriptionCompleteCommand> item) {
                    synchronized (SignalsConnectTask.this) {
                        signalProvider.getSubscriptionCompleteReceivedEvent().removeObserver(onSubscriptionCompleteObserver);
                        signalProvider.getConnectionChangedEvent().removeObserver(onDisconnectObserver[0]);
                    }
                }

                @Override
                public String toString() {
                    return "SignalsConnectTask/CleanUpObserver";
                }
            });

            LOGGER.debug("/signals/connect executed successfully. You should get back a SubscriptionCompleteCommand any time now. (Maybe already?)");

            return resultFuture;
        }

        public ConnectionHandle getConnectionHandle() {
            return connectionHandle;
        }

        @Override
        public String toString() {
            return "SignalsConnectTask(Waiting for SubscriptionCompleteCommand)";
        }
    }

    private Map<String, Object> getSignalsConnectParams(String sessionKey, String clientId) {
        Map<String, Object> params = new HashMap<String, Object>();

        params.put("sessions", sessionKey);
        params.put("clientId", clientId);
        params.put("subscriptionId", sessionKey);

        if (signalProvider.getPresence() != null) {
            params.put("category", signalProvider.getPresence().getCategory());
        }

        return params;
    }

    private static class TearDownConnectionIfFailureObserver implements Observer<ObservableFuture<SubscriptionCompleteCommand>> {

        final String clientId;
        final SignalProvider signalProvider;

        private TearDownConnectionIfFailureObserver(String clientId, SignalProvider signalProvider) {
            this.clientId = clientId;
            this.signalProvider = signalProvider;
        }

        @Override
        public void notify(Object sender, ObservableFuture<SubscriptionCompleteCommand> signalsConnectFuture) {

            SignalsConnectTask task = (SignalsConnectTask) sender;
            ConnectionHandle connectionHandle = task.getConnectionHandle();

            synchronized (signalProvider) {
                String currentClientId = signalProvider.getClientId();
                if (!StringUtil.equals(clientId, currentClientId)) {
                    LOGGER.warn(String.format("The clientId's changed. Not running tearDown. %s->%s", clientId, currentClientId));
                    return;
                }

                if (signalsConnectFuture.getCause() instanceof TimeoutException) {
                    LOGGER.error("Timeout on receiving the SubscriptionCompleteCommand from the server. We're going to tear down the connection and let the ReconnectStrategy take it from there. (If you dont see a disconnect it was because it already reconnected)");

                    // we are in the Timer thread (pub sub if Timer is Intent based).
                    // hashwheel otherwise.

                    // we've decided to clear the clientId when the signals/connect doesn't work
                    // this connection object lets us be certain that the current connection is reconnected.
                    signalProvider.resetDisconnectAndConnect();
                } else if (signalsConnectFuture.isCancelled()) {
                    // potentially we would be in the CALLER thread.
                    LOGGER.warn("Cancelled our /signals/connect web call future?!?");
                } else if (signalsConnectFuture.getCause() != null) {
                    LOGGER.error("Problem executing /signals/connect", signalsConnectFuture.getCause());

                    connectionHandle.disconnect(true);
                } else {
                    // guess it succeeded, do we care?
                    LOGGER.debug("Successfully got a SubscriptionCompleteCommand from the server! " + signalsConnectFuture.getResult());
                }
            }
        }

        @Override
        public String toString() {
            return "TearDownConnectionIfFailureObserver";
        }
    }

    /**
     * This observer fires in the thread that the future completes in. This future can either complete in the
     * Timer thread (HashWheelTimer or pubsub via intent/AlarmManager) OR it can
     */
    private class ClearConnectFutureOnCompleteObserver implements Observer<ObservableFuture<ConnectionHandle>> {

        private final ObservableFuture<ConnectionHandle> myConnectFuture;

        private ClearConnectFutureOnCompleteObserver(ObservableFuture<ConnectionHandle> connectFuture) {
            this.myConnectFuture = connectFuture;
        }

        @Override
        public void notify(Object sender, ObservableFuture<ConnectionHandle> item) {
            // we are not allowed to "synchronize" on the ZipwhipClient because we are in a bad thread. (causes
            // a deadlock). We can rely on the property that only 1 connectFuture is allowed to exist at any
            // given time. It only is allowed to be filled when not null.
            synchronized (signalProvider) {
                final ObservableFuture<ConnectionHandle> finalConnectingFuture = getUnchangingConnectFuture();
                if (finalConnectingFuture == myConnectFuture) {
                    synchronized (myConnectFuture) {
                        clearConnectingFuture(myConnectFuture);
                    }
                }
            }
        }
    }


}
