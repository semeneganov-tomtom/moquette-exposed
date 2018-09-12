package io.moquette.broker;

import io.moquette.broker.Session.SessionStatus;
import io.moquette.spi.impl.subscriptions.ISubscriptionsDirectory;
import io.moquette.spi.impl.subscriptions.Subscription;
import io.netty.handler.codec.mqtt.MqttConnectMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

class SessionRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(SessionRegistry.class);

    private final ConcurrentMap<String, Session> pool = new ConcurrentHashMap<>();
    private final PostOffice postOffice/* = new PostOffice()*/;
    private final ISubscriptionsDirectory subscriptionsDirectory;

    SessionRegistry(ISubscriptionsDirectory subscriptionsDirectory, PostOffice postOffice) {
        this.subscriptionsDirectory = subscriptionsDirectory;
        this.postOffice = postOffice;
    }

    void bindToSession(MQTTConnection mqttConnection, MqttConnectMessage msg) {
        final String clientId = msg.payload().clientIdentifier();
        boolean isSessionAlreadyStored = false;
        if (!pool.containsKey(clientId)) {
            // case 1
            final Session newSession = createNewSession(mqttConnection, msg);

            // publish the session
            final Session previous = pool.putIfAbsent(clientId, newSession);
            final boolean success = previous == null;

            if (success) {
                LOG.trace("case 1, not existing session with CId {}", clientId);
            } else {
                isSessionAlreadyStored = bindToExistingSession(mqttConnection, msg, clientId, newSession);
            }
        } else {
            final Session newSession = createNewSession(mqttConnection, msg);
            isSessionAlreadyStored = bindToExistingSession(mqttConnection, msg, clientId, newSession);
        }
        final boolean msgCleanSessionFlag = msg.variableHeader().isCleanSession();
        boolean isSessionAlreadyPresent = (!msgCleanSessionFlag && isSessionAlreadyStored);
        mqttConnection.sendConnAck(isSessionAlreadyPresent);
    }

    private boolean bindToExistingSession(MQTTConnection mqttConnection, MqttConnectMessage msg, String clientId, Session newSession) {
        boolean isSessionAlreadyStored;
        final boolean newIsClean = msg.variableHeader().isCleanSession();
        final Session oldSession = pool.get(clientId);
        isSessionAlreadyStored = true;
        if (newIsClean && oldSession.disconnected()) {
            // case 2
            postOffice.dropQueuesForClient(clientId);
            unsubscribe(oldSession);

            // publish new session
            boolean result = oldSession.assignState(SessionStatus.DISCONNECTED, SessionStatus.CONNECTING);
            if (!result) {
                throw new SessionCorruptedException("old session was already changed state");
            }
            copySessionConfig(msg, oldSession);
            oldSession.bind(mqttConnection);

            result = oldSession.assignState(SessionStatus.CONNECTING, SessionStatus.CONNECTED);
            if (!result) {
                throw new SessionCorruptedException("old session moved in connected state by other thread");
            }
            final boolean published = pool.replace(clientId, oldSession, oldSession);
            if (!published) {
                throw new SessionCorruptedException("old session was already removed");
            }
            LOG.trace("case 2, oldSession with same CId {} disconnected", clientId);
        } else if (!newIsClean && oldSession.disconnected()) {
            // case 3
            postOffice.sendQueuedMessagesWhileOffline(clientId);
            reactivateSubscriptions(oldSession);

            // mark as connected
            final boolean connecting = oldSession.assignState(SessionStatus.DISCONNECTED, SessionStatus.CONNECTING);
            if (!connecting) {
                throw new SessionCorruptedException("old session moved in connected state by other thread");
            }
            oldSession.bind(mqttConnection);

            final boolean connected = oldSession.assignState(SessionStatus.CONNECTING, SessionStatus.CONNECTED);
            if (!connected) {
                throw new SessionCorruptedException("old session moved in other state state by other thread");
            }

            // publish new session
            final boolean published = pool.replace(clientId, oldSession, oldSession);
            if (!published) {
                throw new SessionCorruptedException("old session was already removed");
            }
            LOG.trace("case 3, oldSession with same CId {} disconnected", clientId);
        } else if (oldSession.connected()) {
            // case 4
            LOG.trace("case 4, oldSession with same CId {} still connected, force to close", clientId);
            oldSession.closeImmediatly();
            remove(clientId);
            // publish new session
            final boolean published = pool.replace(clientId, oldSession, newSession);
            if (!published) {
                throw new SessionCorruptedException("old session was already removed");
            }
        }
        // case not covered new session is clean true/false and old session not in CONNECTED/DISCONNECTED
        return isSessionAlreadyStored;
    }

    private void reactivateSubscriptions(Session session) {
        for (Subscription existingSub : session.getSubscriptions()) {
            // TODO
//            subscriptionsDirectory.reactivate(existingSub.getTopicFilter(), session.getClientID());
        }
    }

    private void unsubscribe(Session session) {
        for (Subscription existingSub : session.getSubscriptions()) {
            subscriptionsDirectory.removeSubscription(existingSub.getTopicFilter(), session.getClientID());
        }
    }

    private Session createNewSession(MQTTConnection mqttConnection, MqttConnectMessage msg) {
        final String clientId = msg.payload().clientIdentifier();

        final boolean clean = msg.variableHeader().isCleanSession();
        final Session.Will will = createWill(msg);

        final Session newSession = new Session(clientId, clean, will);
        newSession.markConnected();
        newSession.bind(mqttConnection);

        return newSession;
    }

    private void copySessionConfig(MqttConnectMessage msg, Session session) {
        final boolean clean = msg.variableHeader().isCleanSession();
        final Session.Will will = createWill(msg);
        session.update(clean, will);
    }

    private Session.Will createWill(MqttConnectMessage msg) {
        final byte[] willPayload = msg.payload().willMessageInBytes();
        final String willTopic = msg.payload().willTopic();
        return new Session.Will(willTopic, willPayload);
    }

    Session retrieve(String clientID) {
        return pool.get(clientID);
    }

    public void remove(String clientID) {
        pool.remove(clientID);
    }

    public void disconnect(String clientID) {
        final Session session = retrieve(clientID);
        if (session == null) {
            LOG.debug("Some other thread already removed the session CId={}", clientID);
            return;
        }
        session.disconnect();
    }
}