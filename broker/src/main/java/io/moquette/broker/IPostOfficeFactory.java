package io.moquette.broker;

import io.moquette.broker.subscriptions.ISubscriptionsDirectory;
import io.moquette.interception.BrokerInterceptor;

public interface IPostOfficeFactory {

    PostOffice create(
        ISubscriptionsDirectory subscriptions,
        IRetainedRepository retainedRepository,
        SessionRegistry sessions,
        BrokerInterceptor interceptor,
        Authorizator authorizator,
        int sessionQueueSize
    );
}
