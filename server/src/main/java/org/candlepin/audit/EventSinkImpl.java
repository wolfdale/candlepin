/**
 * Copyright (c) 2009 - 2012 Red Hat, Inc.
 *
 * This software is licensed to you under the GNU General Public License,
 * version 2 (GPLv2). There is NO WARRANTY for this software, express or
 * implied, including the implied warranties of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
 * along with this software; if not, see
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
 *
 * Red Hat trademarks are not licensed under GPLv2. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package org.candlepin.audit;

import org.candlepin.async.impl.ActiveMQSessionFactory;
import org.candlepin.common.config.Configuration;
import org.candlepin.controller.mode.CandlepinModeManager;
import org.candlepin.controller.mode.CandlepinModeManager.Mode;
import org.candlepin.dto.manifest.v1.SubscriptionDTO;
import org.candlepin.guice.CandlepinRequestScoped;
import org.candlepin.model.Consumer;
import org.candlepin.model.Owner;
import org.candlepin.model.Pool;
import org.candlepin.model.Rules;
import org.candlepin.model.activationkeys.ActivationKey;
import org.candlepin.policy.SystemPurposeComplianceStatus;
import org.candlepin.policy.js.compliance.ComplianceStatus;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.activemq.artemis.api.core.ActiveMQException;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.client.ClientMessage;
import org.apache.activemq.artemis.api.core.client.ClientProducer;
import org.apache.activemq.artemis.api.core.client.ClientSession;
import org.apache.activemq.artemis.api.core.client.ClientRequestor;
import org.apache.activemq.artemis.api.core.management.ManagementHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;
import java.util.List;
import java.util.HashMap;

import javax.inject.Inject;


import javax.jms.MessageConsumer;
import javax.jms.QueueConnection;
import javax.jms.TextMessage;
import javax.management.MBeanServerConnection;
import javax.management.MBeanServerInvocationHandler;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import javax.naming.InitialContext;

import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.management.ActiveMQServerControl;
import org.apache.activemq.artemis.api.core.management.ObjectNameBuilder;
import org.apache.activemq.artemis.api.core.management.QueueControl;



/**
 * EventSink - Queues events to be sent after request/job completes, and handles actual
 * sending of events on successful job or API request, as well as rollback if either fails.
 *
 * An single instance of this object will be created per request/job.
 */
@CandlepinRequestScoped
public class EventSinkImpl implements EventSink {
    private static Logger log = LoggerFactory.getLogger(EventSinkImpl.class);

    public static final String EVENT_TYPE_KEY = "EVENT_TYPE";
    public static final String EVENT_TARGET_KEY = "EVENT_TARGET";

    private EventFactory eventFactory;
    private ObjectMapper mapper;
    private EventFilter eventFilter;
    private CandlepinModeManager modeManager;
    private Configuration config;

    private ActiveMQSessionFactory sessionFactory;
    private EventMessageSender messageSender;

    @Inject
    public EventSinkImpl(EventFilter eventFilter, EventFactory eventFactory,
        ObjectMapper mapper, Configuration config, ActiveMQSessionFactory sessionFactory,
        CandlepinModeManager modeManager) throws ActiveMQException {

        this.eventFactory = eventFactory;
        this.mapper = mapper;
        this.eventFilter = eventFilter;
        this.modeManager = modeManager;
        this.config = config;
        this.sessionFactory = sessionFactory;
    }

    // FIXME This method really does not belong here. It should probably be moved
    //       to its own class.
    @Override
    public List<QueueStatus> getQueueInfo() {
        List<QueueStatus> results = new LinkedList<>();

        try (ClientSession session = this.sessionFactory.getEgressSession(false)) {
            session.start();
            for (String listenerClassName : ActiveMQContextListener.getActiveMQListeners(config)) {
                String queueName = "event." + listenerClassName;
                long msgCount = session.queueQuery(SimpleString.toSimpleString(queueName)).getMessageCount();
                results.add(new QueueStatus(queueName, msgCount));
            }
        }
        catch (Exception e) {
            log.error("Error looking up ActiveMQ queue info: ", e);
        }

        return results;
    }

    /**
     * Adds an event to the queue to be sent on successful completion of the request or job.
     * sendEvents() must be called for these events to actually go out. This happens
     * automatically after each successful REST API request, and KingpingJob. If either
     * is not successful, rollback() must be called.
     *
     * Events are filtered, meaning that some of them might not even get into ActiveMQ.
     * Details about the filtering are documented in EventFilter class
     *
     * ActiveMQ transaction actually manages the queue of events to be sent.
     */
    @Override
    public void queueEvent(Event event) {
        if (eventFilter.shouldFilter(event)) {
            log.debug("Filtering event {}", event);
            return;
        }

        if (this.modeManager.getCurrentMode() != Mode.NORMAL) {
            throw new IllegalStateException("Candlepin is in suspend mode");
        }

        log.debug("Queuing event: {}", event);

        try {
            // Lazily initialize the message sender when the first
            // message gets queued.
            if (messageSender == null) {
                messageSender = new EventMessageSender(this.sessionFactory);
            }

            messageSender.queueMessage(mapper.writeValueAsString(event), event.getType(), event.getTarget());
        }
        catch (Exception e) {
            log.error("Error while trying to send event", e);
        }
    }

    public void doTest() throws Exception {
        if (messageSender == null) {
            messageSender = new EventMessageSender(this.sessionFactory);
        }

        this.messageSender.test();
    }

    /**
     * Dispatch queued events. (if there are any)
     *
     * Typically only called after a successful request or job execution.
     */
    @Override
    public void sendEvents() {
        if (!hasQueuedMessages()) {
            log.debug("No events to send.");
            return;
        }
        messageSender.sendMessages();
    }

    @Override
    public void rollback() {
        if (!hasQueuedMessages()) {
            log.debug("No events to roll back.");
            return;
        }
        messageSender.cancelMessages();
    }

    private boolean hasQueuedMessages() {
        return messageSender != null;
    }

    public void emitConsumerCreated(Consumer newConsumer) {
        Event e = eventFactory.consumerCreated(newConsumer);
        queueEvent(e);
    }

    public void emitOwnerCreated(Owner newOwner) {
        Event e = eventFactory.ownerCreated(newOwner);
        queueEvent(e);
    }

    public void emitOwnerMigrated(Owner owner) {
        Event e = eventFactory.ownerModified(owner);
        queueEvent(e);
    }

    public void emitPoolCreated(Pool newPool) {
        Event e = eventFactory.poolCreated(newPool);
        queueEvent(e);
    }

    public void emitExportCreated(Consumer consumer) {
        Event e = eventFactory.exportCreated(consumer);
        queueEvent(e);
    }

    public void emitImportCreated(Owner owner) {
        Event e = eventFactory.importCreated(owner);
        queueEvent(e);
    }

    public void emitActivationKeyCreated(ActivationKey key) {
        Event e = eventFactory.activationKeyCreated(key);
        queueEvent(e);
    }

    public void emitSubscriptionExpired(SubscriptionDTO subscription) {
        Event e = eventFactory.subscriptionExpired(subscription);
        queueEvent(e);
    }

    @Override
    public void emitRulesModified(Rules oldRules, Rules newRules) {
        queueEvent(eventFactory.rulesUpdated(oldRules, newRules));
    }

    @Override
    public void emitRulesDeleted(Rules rules) {
        queueEvent(eventFactory.rulesDeleted(rules));
    }

    @Override
    public void emitCompliance(Consumer consumer, ComplianceStatus compliance) {
        queueEvent(eventFactory.complianceCreated(consumer, compliance));
    }

    @Override
    public void emitCompliance(Consumer consumer, SystemPurposeComplianceStatus compliance) {
        queueEvent(eventFactory.complianceCreated(consumer, compliance));
    }

    /**
     * An internal class responsible for encapsulating a single session to the
     * event message broker.
     */
    private class EventMessageSender {

        private ActiveMQSessionFactory sessionFactory;
        private ClientSession session;
        private ClientProducer producer;

        public EventMessageSender(ActiveMQSessionFactory sessionFactory) {
            try {
                /*
                 * Uses a transacted ActiveMQ session, events will not be dispatched until
                 * commit() is called on it, and a call to rollback() will revert any queued
                 * messages safely and the session is then ready to start over the next time
                 * the thread is used.
                 */
                session = sessionFactory.getEgressSession(false);

                session.addFailureListener(new org.apache.activemq.artemis.api.core.client.SessionFailureListener() {
                    @Override
                    public void beforeReconnect(ActiveMQException exception) {
                        log.debug("beforeReconnect -- EXCEPTION:", exception);
                    }

                    @Override
                    public void connectionFailed(ActiveMQException exception, boolean failedOver) {
                        log.debug("connectionFailed (2) -- FAILED OVER: {}, EXCEPTION ", failedOver, exception);
                    }

                    @Override
                    public void connectionFailed(ActiveMQException exception, boolean failedOver, String scaleDownTargetNodeID) {
                        log.debug("connectionFailed (3) -- FAILED OVER: {}, NODE ID: {}, FAILURE EXCEPTION OCCURRED:", failedOver, scaleDownTargetNodeID, exception);
                    }
                });


                // session.setSendAcknowledgementHandler(new org.apache.activemq.artemis.api.core.client.SendAcknowledgementHandler() {
                //     @Override
                //     public void sendAcknowledged(org.apache.activemq.artemis.api.core.Message message) {
                //         log.debug("MESSAGE SENT SUCCESSFULLY: {}", message);
                //     }

                //     @Override
                //     public void sendFailed(org.apache.activemq.artemis.api.core.Message message, Exception e) {
                //         log.debug("MESSAGE SEND FAILED: {}", message, e);
                //     }
                // });

                producer = session.createProducer(MessageAddress.DEFAULT_EVENT_MESSAGE_ADDRESS);
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }
            log.debug("Created new message sender.");
        }

        public void test() throws Exception {
//            QueueConnection connection = null;
//            InitialContext initialContext = null;

            String JMX_URL = "service:jmx:rmi:///jndi/rmi://vm:0/jmxrmi";
            String QUEUE_NAME = "event.org.candlepin.audit.AMQPBusPublisher";

            ClientRequestor requestor = null;
            ClientMessage message = null;
            ClientMessage reply = null;

            try {
                log.debug("RUNNING SESSION TEST");
                log.debug("QUERY DETAILS: {}", this.getQueryDeets());
                log.debug("IS BLOCKING: {}, {}, {}", this.session.isBlockOnAcknowledge(), this.producer.isBlockOnDurableSend(), this.producer.isBlockOnNonDurableSend());

                log.debug("SESSION AUTO-COMMIT: {}, AUTO-ACK: {}", this.session.isAutoCommitSends(), this.session.isAutoCommitAcks());


                requestor = new ClientRequestor(this.session, "activemq.management");
                message = session.createMessage(false);
                ManagementHelper.putAttribute(message, "queue." + QUEUE_NAME, "messageCount");

                log.debug("HERE A");
                reply = requestor.request(message);
                log.debug("HERE B");
                int count = (Integer) ManagementHelper.getResult(reply);
                log.debug("There are " + count + " messages in exampleQueue");

                // // Step 1. Create an initial context to perform the JNDI lookup.
                // initialContext = new InitialContext();

                // // Step 2. Perfom a lookup on the queue
                // Queue queue = (Queue) initialContext.lookup("queue/exampleQueue");

                // // Step 3. Perform a lookup on the Connection Factory
                // QueueConnectionFactory cf = (QueueConnectionFactory) initialContext.lookup("ConnectionFactory");

                // // Step 4.Create a JMS Connection
                // connection = cf.createQueueConnection();

                // // Step 5. Create a JMS Session
                // QueueSession session = connection.createQueueSession(false, Session.AUTO_ACKNOWLEDGE);

                // // Step 6. Create a JMS Message Producer
                // MessageProducer producer = session.createProducer(queue);

                // // Step 7. Create a Text Message
                // TextMessage message = session.createTextMessage("This is a text message");
                // System.out.println("Sent message: " + message.getText());

                // // Step 8. Send the Message
                // producer.send(message);

//                // Step 9. Retrieve the ObjectName of the queue. This is used to identify the server resources to manage
//                SimpleString queueName = SimpleString.toSimpleString(QUEUE_NAME);
//                ObjectName on = ObjectNameBuilder.DEFAULT.getQueueObjectName(queueName, queueName, RoutingType.ANYCAST);
//
//                // Step 10. Create JMX Connector to connect to the server's MBeanServer
//                //we dont actually need credentials as the guest login i sused but this is how its done
//                HashMap env = new HashMap();
//                String[] creds = {"guest", "guest"};
//                env.put(JMXConnector.CREDENTIALS, creds);
//
//                JMXConnector connector = JMXConnectorFactory.connect(new JMXServiceURL(JMX_URL), env);
//
//                // Step 11. Retrieve the MBeanServerConnection
//                MBeanServerConnection mbsc = connector.getMBeanServerConnection();
//
//                // Step 12. Create a QueueControl proxy to manage the queue on the server
//                QueueControl queueControl = MBeanServerInvocationHandler.newProxyInstance(mbsc, on, QueueControl.class, false);
//                // Step 13. Display the number of messages in the queue
//                System.out.println(queueControl.getName() + " contains " + queueControl.getMessageCount() + " messages");
//
//                // Step 14. Remove the message sent at step #8
//                System.out.println("message has been removed: " + queueControl.removeMessages(null));
//
//                // Step 15. Display the number of messages in the queue
//                System.out.println(queueControl.getName() + " contains " + queueControl.getMessageCount() + " messages");
//
//                // Step 16. We close the JMX connector
//                connector.close();

                // Step 17. Create a JMS Message Consumer on the queue
                // MessageConsumer messageConsumer = session.createConsumer(queue);

                // Step 18. Start the Connection
                // connection.start();

                // Step 19. Trying to receive a message. Since the only message in the queue was removed by a management
                // operation, there is none to consume.
                // The call will timeout after 5000ms and messageReceived will be null
                // TextMessage messageReceived = (TextMessage) messageConsumer.receive(5000);

                // if (messageReceived != null) {
                //     throw new IllegalStateException("message should be null!");
                // }

                // System.out.println("Received message: " + messageReceived);
            }
            catch (Exception e) {
                log.debug("AN EXCEPTION OCCURRED WHILE TESTING!()*@$)(*!@)($*", e);
            }
            finally {
                if (requestor != null) {
                    requestor.close();
                }

                // Step 20. Be sure to close the resources!
//                if (initialContext != null) {
//                    initialContext.close();
//                }
//
//                if (connection != null) {
//                    connection.close();
//                }
            }
        }

        public void queueMessage(String eventString, Event.Type type, Event.Target target)
            throws ActiveMQException {

            ClientMessage message = session.createMessage(ClientMessage.TEXT_TYPE, true);
            message.getBodyBuffer().writeNullableSimpleString(SimpleString.toSimpleString(eventString));

            // Set the event type and target if provided
            if (type != null) {
                message.putStringProperty(EVENT_TYPE_KEY, type.name());
            }

            if (target != null) {
                message.putStringProperty(EVENT_TARGET_KEY, target.name());
            }

            // NOTE: not actually sent until we commit the session.

            log.debug("ATTEMPTING TO SEND MESSAGE: {}", message);

            log.debug("QUERY DEETS: {}", this.getQueryDeets());
            producer.send(message, new org.apache.activemq.artemis.api.core.client.SendAcknowledgementHandler() {
                @Override
                public void sendAcknowledged(org.apache.activemq.artemis.api.core.Message message) {
                    log.debug("MESSAGE SENT SUCCESSFULLY: {}", message);
                }

                @Override
                public void sendFailed(org.apache.activemq.artemis.api.core.Message message, Exception e) {
                    log.debug("MESSAGE SEND FAILED: {}", message, e);
                }
            });

            log.debug("DONE SENDING");
        }

        public void sendMessages() {
            log.debug("Committing ActiveMQ transaction.");
            try (ClientSession toClose = session) {
                toClose.commit();
            }
            catch (Exception e) {
                // This would be pretty bad, but we always try not to let event errors
                // interfere with the operation of the overall application.
                log.error("Error committing ActiveMQ transaction", e);
            }
        }

        public void cancelMessages() {
            log.warn("Rolling back ActiveMQ transaction.");
            try (ClientSession toClose = session) {
                toClose.rollback();
            }
            catch (ActiveMQException e) {
                log.error("Error rolling back ActiveMQ transaction", e);
            }
        }

        private String getQueryDeets() throws ActiveMQException {
            org.apache.activemq.artemis.api.core.client.ClientSession.QueueQuery query = this.session.queueQuery(SimpleString.toSimpleString(MessageAddress.DEFAULT_EVENT_MESSAGE_ADDRESS));

            return String.format("\n" +
                "getAddress: %s\n" +
                "getAutoDeleteDelay: %s\n" +
                "getAutoDeleteMessageCount: %s\n" +
                "getConsumerCount: %s\n" +
                "getConsumersBeforeDispatch: %s\n" +
                "getDefaultConsumerWindowSize: %s\n" +
                "getDelayBeforeDispatch: %s\n" +
                "getFilterString: %s\n" +
                "getGroupBuckets: %s\n" +
                "getGroupFirstKey: %s\n" +
                "getLastValueKey: %s\n" +
                "getMaxConsumers: %s\n" +
                "getMessageCount: %s\n" +
                "getName: %s\n" +
                "getRingSize: %s\n" +
                "getRoutingType: %s\n" +
                "isAutoCreated: %s\n" +
                "isAutoCreateQueues: %s\n" +
                "isAutoDelete: %s\n" +
                "isDurable: %s\n" +
                "isExclusive: %s\n" +
                "isExists: %s\n" +
                "isGroupRebalance: %s\n" +
                "isLastValue: %s\n" +
                "isNonDestructive: %s\n" +
                "isPurgeOnNoConsumers: %s\n" +
                "isTemporary: %s\n",

                query.getAddress(),
                query.getAutoDeleteDelay() ,
                query.getAutoDeleteMessageCount() ,
                query.getConsumerCount(),
                query.getConsumersBeforeDispatch() ,
                query.getDefaultConsumerWindowSize() ,
                query.getDelayBeforeDispatch() ,
                query.getFilterString(),
                query.getGroupBuckets() ,
                query.getGroupFirstKey() ,
                query.getLastValueKey() ,
                query.getMaxConsumers() ,
                query.getMessageCount(),
                query.getName(),
                query.getRingSize() ,
                query.getRoutingType() ,
                query.isAutoCreated() ,
                query.isAutoCreateQueues(),
                query.isAutoDelete() ,
                query.isDurable(),
                query.isExclusive() ,
                query.isExists(),
                query.isGroupRebalance() ,
                query.isLastValue() ,
                query.isNonDestructive() ,
                query.isPurgeOnNoConsumers() ,
                query.isTemporary());
        }













    }

}
