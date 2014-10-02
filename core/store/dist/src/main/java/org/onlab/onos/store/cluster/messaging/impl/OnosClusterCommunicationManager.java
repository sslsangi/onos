package org.onlab.onos.store.cluster.messaging.impl;

import static com.google.common.base.Preconditions.checkArgument;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.Service;
import org.onlab.onos.cluster.ControllerNode;
import org.onlab.onos.cluster.NodeId;
import org.onlab.onos.store.cluster.impl.ClusterNodesDelegate;
import org.onlab.onos.store.cluster.messaging.ClusterCommunicationAdminService;
import org.onlab.onos.store.cluster.messaging.ClusterCommunicationService;
import org.onlab.onos.store.cluster.messaging.ClusterMessage;
import org.onlab.onos.store.cluster.messaging.ClusterMessageHandler;
import org.onlab.onos.store.cluster.messaging.MessageSubject;
import org.onlab.onos.store.messaging.Endpoint;
import org.onlab.onos.store.messaging.Message;
import org.onlab.onos.store.messaging.MessageHandler;
import org.onlab.onos.store.messaging.MessagingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(immediate = true)
@Service
public class OnosClusterCommunicationManager
        implements ClusterCommunicationService, ClusterCommunicationAdminService {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private ControllerNode localNode;
    private ClusterNodesDelegate nodesDelegate;
    private Map<NodeId, ControllerNode> members = new HashMap<>();
    private final Timer timer = new Timer("onos-controller-heatbeats");
    public static final long HEART_BEAT_INTERVAL_MILLIS = 1000L;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    private MessagingService messagingService;

    @Activate
    public void activate() {
        log.info("Started");
    }

    @Deactivate
    public void deactivate() {
        log.info("Stopped");
    }

    @Override
    public boolean broadcast(ClusterMessage message) {
        boolean ok = true;
        for (ControllerNode node : members.values()) {
            if (!node.equals(localNode)) {
                ok = unicast(message, node.id()) && ok;
            }
        }
        return ok;
    }

    @Override
    public boolean multicast(ClusterMessage message, Set<NodeId> nodes) {
        boolean ok = true;
        for (NodeId nodeId : nodes) {
            if (!nodeId.equals(localNode.id())) {
                ok = unicast(message, nodeId) && ok;
            }
        }
        return ok;
    }

    @Override
    public boolean unicast(ClusterMessage message, NodeId toNodeId) {
        ControllerNode node = members.get(toNodeId);
        checkArgument(node != null, "Unknown nodeId: %s", toNodeId);
        Endpoint nodeEp = new Endpoint(node.ip().toString(), node.tcpPort());
        try {
            messagingService.sendAsync(nodeEp, message.subject().value(), message);
            return true;
        } catch (IOException e) {
            log.error("Failed to send cluster message to nodeId: " + toNodeId, e);
        }

        return false;
    }

    @Override
    public void addSubscriber(MessageSubject subject,
            ClusterMessageHandler subscriber) {
        messagingService.registerHandler(subject.value(), new InternalClusterMessageHandler(subscriber));
    }

    @Override
    public void initialize(ControllerNode localNode,
            ClusterNodesDelegate delegate) {
        this.localNode = localNode;
        this.nodesDelegate = delegate;
        this.addSubscriber(new MessageSubject("CLUSTER_MEMBERSHIP_EVENT"), new ClusterMemebershipEventHandler());
        timer.schedule(new KeepAlive(), 0, HEART_BEAT_INTERVAL_MILLIS);
    }

    @Override
    public void addNode(ControllerNode node) {
        members.put(node.id(), node);
    }

    @Override
    public void removeNode(ControllerNode node) {
        broadcast(new ClusterMessage(
                localNode.id(),
                new MessageSubject("CLUSTER_MEMBERSHIP_EVENT"),
                new ClusterMembershipEvent(ClusterMembershipEventType.LEAVING_MEMBER, node)));
        members.remove(node.id());
    }

    // Sends a heart beat to all peers.
    private class KeepAlive extends TimerTask {

        @Override
        public void run() {
            broadcast(new ClusterMessage(
                localNode.id(),
                new MessageSubject("CLUSTER_MEMBERSHIP_EVENT"),
                new ClusterMembershipEvent(ClusterMembershipEventType.HEART_BEAT, localNode)));
        }
    }

    private class ClusterMemebershipEventHandler implements ClusterMessageHandler {

        @Override
        public void handle(ClusterMessage message) {

            ClusterMembershipEvent event = (ClusterMembershipEvent) message.payload();
            ControllerNode node = event.node();
            if (event.type() == ClusterMembershipEventType.HEART_BEAT) {
                log.info("Node {} sent a hearbeat", node.id());
                nodesDelegate.nodeDetected(node.id(), node.ip(), node.tcpPort());
            } else if (event.type() == ClusterMembershipEventType.LEAVING_MEMBER) {
                log.info("Node {} is leaving", node.id());
                nodesDelegate.nodeRemoved(node.id());
            } else if (event.type() == ClusterMembershipEventType.UNREACHABLE_MEMBER) {
                log.info("Node {} is unreachable", node.id());
                nodesDelegate.nodeVanished(node.id());
            }
        }
    }

    private static class InternalClusterMessageHandler implements MessageHandler {

        private final ClusterMessageHandler handler;

        public InternalClusterMessageHandler(ClusterMessageHandler handler) {
            this.handler = handler;
        }

        @Override
        public void handle(Message message) {
            handler.handle((ClusterMessage) message.payload());
        }
    }
}
