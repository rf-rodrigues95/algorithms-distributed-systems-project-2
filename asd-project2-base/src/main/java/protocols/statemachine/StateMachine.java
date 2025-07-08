package protocols.statemachine;

import protocols.agreement.notifications.JoinedNotification;
import protocols.agreement.notifications.MembershipChangedNotification;
import protocols.agreement.notifications.NewLeaderNotification;
import pt.unl.fct.di.novasys.babel.core.GenericProtocol;
import pt.unl.fct.di.novasys.babel.exceptions.HandlerRegistrationException;
import pt.unl.fct.di.novasys.babel.generic.ProtoMessage;
import pt.unl.fct.di.novasys.channel.tcp.TCPChannel;
import pt.unl.fct.di.novasys.channel.tcp.events.*;
import pt.unl.fct.di.novasys.network.data.Host;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import protocols.agreement.PaxosClassic;
import protocols.agreement.PaxosDistinguishedLearner;
import protocols.statemachine.messages.AddReplicaMessage;
import protocols.statemachine.messages.LeaderOrderMessage;
import protocols.statemachine.messages.ReplicaAddedMessage;
import protocols.statemachine.notifications.ChannelReadyNotification;
import protocols.agreement.notifications.DecidedNotification;
import protocols.agreement.requests.AddReplicaRequest;
import protocols.agreement.requests.PrepareRequest;
import protocols.agreement.requests.ProposeRequest;
import protocols.agreement.requests.RemoveReplicaRequest;
import protocols.app.HashApp;
import protocols.app.requests.CurrentStateReply;
import protocols.app.requests.CurrentStateRequest;
import protocols.app.requests.InstallStateRequest;
import protocols.statemachine.notifications.ExecuteNotification;
import protocols.statemachine.requests.OrderRequest;
import protocols.statemachine.timers.AddReplicaTimer;
import protocols.statemachine.timers.ConnectionRetryTimer;
import protocols.statemachine.timers.LeaderCandidateTimer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

public class StateMachine extends GenericProtocol {

    private static final Logger logger = LogManager.getLogger(StateMachine.class);

    private enum State {JOINING, ACTIVE}

    //Protocol information, to register in babel
    public static final String PROTOCOL_NAME = "StateMachine";
    public static final short PROTOCOL_ID = 200;
    public static final String DISTINGUISHED_LEARNER = "Distinguished";

    private final Host self;     //My own address/port
    private final int channelId; //Id of the created channel

    private State state;
    private List<Host> membership;
    private int nextInstance;
    private Host leader;
    private Host previousLeader;

    private final String PAXOS_IMPLEMENTATION;
    private final Short PAXOS_PROTOCOL_ID;
    private final int CONNECTION_RETRIES;

    private Map<UUID, byte[]> pendingOrders;
    private Map<UUID, byte[]> executedOperations;
    private Map<Host, Boolean> pendingAddRemoves;
    private Map<Integer, Pair<Host, Boolean>> executedAddRemoves;

    private Set<Host> replicaIdSet;
    private Host contact;

    private Map<Host, Integer> hostRetries;
    private Map<Long, Host> hostTimers;

    public StateMachine(Properties props) throws IOException, HandlerRegistrationException {
        super(PROTOCOL_NAME, PROTOCOL_ID);
    
        String address = props.getProperty("address");
        String port = props.getProperty("p2p_port");

        PAXOS_IMPLEMENTATION = props.getProperty("paxos_strategy", DISTINGUISHED_LEARNER);
        if (PAXOS_IMPLEMENTATION.equals(DISTINGUISHED_LEARNER))
            PAXOS_PROTOCOL_ID = PaxosDistinguishedLearner.PROTOCOL_ID;
        else    
            PAXOS_PROTOCOL_ID = PaxosClassic.PROTOCOL_ID;

        String retries = props.getProperty("connection_retries", "50").trim();
        CONNECTION_RETRIES = Integer.parseInt(retries);

        logger.info("Listening on {}:{}", address, port);
        this.self = new Host(InetAddress.getByName(address), Integer.parseInt(port));

        Properties channelProps = new Properties();
        channelProps.setProperty(TCPChannel.ADDRESS_KEY, address);
        channelProps.setProperty(TCPChannel.PORT_KEY, port); //The port to bind to
        channelProps.setProperty(TCPChannel.HEARTBEAT_INTERVAL_KEY, "1000");
        channelProps.setProperty(TCPChannel.HEARTBEAT_TOLERANCE_KEY, "3000");
        channelProps.setProperty(TCPChannel.CONNECT_TIMEOUT_KEY, "1000");
        channelId = createChannel(TCPChannel.NAME, channelProps);

        registerMessageSerializer(channelId, LeaderOrderMessage.MSG_ID, LeaderOrderMessage.serializer);
        registerMessageSerializer(channelId, AddReplicaMessage.MSG_ID, AddReplicaMessage.serializer);
        registerMessageSerializer(channelId, ReplicaAddedMessage.MSG_ID, ReplicaAddedMessage.serializer);

        registerMessageHandler(channelId, LeaderOrderMessage.MSG_ID, this::uponLeaderOrderMessage, this::uponLeaderMsgFail);
        registerMessageHandler(channelId, AddReplicaMessage.MSG_ID, this::uponAddReplicaMessage, this::uponMsgFail);
        registerMessageHandler(channelId, ReplicaAddedMessage.MSG_ID, this::uponReplicaAddedMessage, this::uponMsgFail);


        /*-------------------- Register Channel Events ------------------------------- */
        registerChannelEventHandler(channelId, OutConnectionDown.EVENT_ID, this::uponOutConnectionDown);
        registerChannelEventHandler(channelId, OutConnectionFailed.EVENT_ID, this::uponOutConnectionFailed);
        registerChannelEventHandler(channelId, OutConnectionUp.EVENT_ID, this::uponOutConnectionUp);
        registerChannelEventHandler(channelId, InConnectionUp.EVENT_ID, this::uponInConnectionUp);
        registerChannelEventHandler(channelId, InConnectionDown.EVENT_ID, this::uponInConnectionDown);

        /*--------------------- Register Request Handlers ----------------------------- */
        registerRequestHandler(OrderRequest.REQUEST_ID, this::uponOrderRequest);
        registerReplyHandler(CurrentStateReply.REQUEST_ID, this::uponCurrentStateReply);

        /*--------------------- Register Notification Handlers ----------------------------- */
        subscribeNotification(DecidedNotification.NOTIFICATION_ID, this::uponDecidedNotification);
        subscribeNotification(NewLeaderNotification.NOTIFICATION_ID, this::uponNewLeaderNotification);
        subscribeNotification(MembershipChangedNotification.NOTIFICATION_ID, this::uponMembershipChangeNotification);
        
        registerTimerHandler(LeaderCandidateTimer.TIMER_ID, this::uponLeaderCandidateTimer);
        registerTimerHandler(AddReplicaTimer.TIMER_ID, this::uponAddReplicaTimer);
        registerTimerHandler(ConnectionRetryTimer.TIMER_ID, this::uponConnectionRetryTimer);
    }

    @Override
    public void init(Properties props) {
        //Inform the state machine protocol about the channel we created in the constructor
        triggerNotification(new ChannelReadyNotification(channelId, self));

        nextInstance = 1;
        leader = null;

        contact = null;

        pendingOrders = new HashMap<>(); //Pending Orders the Leader has to Propose
        pendingAddRemoves = new HashMap<>(); //Removes that occur on a re-election, like the previous leader
        executedOperations = new HashMap<>();
        executedAddRemoves = new TreeMap<>();
        replicaIdSet = new TreeSet<>();

        hostTimers = new HashMap<>();
        hostRetries = new HashMap<>();

        String host = props.getProperty("initial_membership");
        String[] hosts = host.split(",");
        List<Host> initialMembership = new LinkedList<>();
        for (String s : hosts) {
            String[] hostElements = s.split(":");
            Host h;
            try {
                h = new Host(InetAddress.getByName(hostElements[0]), Integer.parseInt(hostElements[1]));
            } catch (UnknownHostException e) {
                throw new AssertionError("Error parsing initial_membership", e);
            }
            initialMembership.add(h);
        }

        if (initialMembership.contains(self)) {
            state = State.ACTIVE;
            logger.info("Starting in ACTIVE as I am part of initial membership");
            //I'm part of the initial membership, so I'm assuming the system is bootstrapping
            membership = new LinkedList<>(initialMembership);
            membership.forEach(this::openConnection);
            triggerNotification(new JoinedNotification(membership, nextInstance, leader));

            Host firstLeader = initialMembership.get(initialMembership.size() - 1);
            if (firstLeader.equals(self)) {
                logger.info("I am appointed as the Leadah because I am the last member of the initial membership.");
                sendRequest(new PrepareRequest(nextInstance), PAXOS_PROTOCOL_ID);
            }
        } else {
            state = State.JOINING;
            logger.info("Starting in JOINING as I am not part of initial membership");

            membership = new LinkedList<>(initialMembership);
            membership.forEach(this::openConnection);
            membership.add(self);

            contact = initialMembership.get(0);
            openConnection(contact);
            sendMessage(new AddReplicaMessage(self, 0, contact), contact);
            
            setupTimer(new AddReplicaTimer(), 3000);
        }
    }

    /*--------------------------------- Requests ---------------------------------------- */
    private void uponOrderRequest(OrderRequest request, short sourceProto) {
        logger.debug("Received request: " + request.getOpId());

        if (state == State.JOINING) {
            pendingOrders.put(request.getOpId(), request.getOperation());
        } else if (state == State.ACTIVE) {            
            if (leader == null) {
                pendingOrders.put(request.getOpId(), request.getOperation());
            } else if(self.equals(leader)) {                
                sendRequest(new ProposeRequest(nextInstance++, request.getOpId(), request.getOperation()),
                    PAXOS_PROTOCOL_ID); 
            } else {
                pendingOrders.put(request.getOpId(), request.getOperation());
                sendMessage(new LeaderOrderMessage(nextInstance, request.getOpId(), request.getOperation()), leader);
            }
        }
    }

    /*--------------------------------- Notifications ---------------------------------------- */
    private void uponCurrentStateReply(CurrentStateReply reply, short protoID) {
		logger.info("Received Current State Reply: {}", reply.toString());
        if(leader == null) return; //AddReplica timer will retry

        Host newReplica = executedAddRemoves.get(reply.getInstance()).getLeft();
        sendMessage(new ReplicaAddedMessage(reply.getInstance(), reply.getState(), membership, leader), newReplica);
	}


    private void uponDecidedNotification(DecidedNotification notification, short sourceProto) {   
        if(!self.equals(leader)) nextInstance ++;
        
        UUID opId = notification.getOpId();
        pendingOrders.remove(notification.getOpId());
        executedOperations.put(opId, notification.getOperation());
        
        triggerNotification(new ExecuteNotification(notification.getOpId(), notification.getOperation()));        
    }

    private void uponNewLeaderNotification(NewLeaderNotification notification, short sourceProto) {
        logger.debug("Received New Leader Notification: " + notification);
        //replicaInstances --> 
        leader = notification.getLeader();
        if (leader.equals(self)) {
            List<Pair<UUID, byte[]>> prepareOKMsgs = notification.getMessages();  
            prepareOKMsgs.forEach(m -> {
                pendingOrders.remove(m.getLeft());
                sendRequest(new ProposeRequest(nextInstance++, m.getLeft(), m.getRight()), PAXOS_PROTOCOL_ID); 
            });
            pendingAddRemoves.forEach((k, v) -> {
                if(v == true)
                    sendRequest(new AddReplicaRequest(nextInstance++, k), PAXOS_PROTOCOL_ID);
                else 
                    sendRequest(new RemoveReplicaRequest(nextInstance++, k), PAXOS_PROTOCOL_ID);
            });
            pendingOrders.forEach((key, value) -> 
                sendRequest(new ProposeRequest(nextInstance++, key, value), PAXOS_PROTOCOL_ID));

            logger.info("Leadah flushing PREPAREOK - {} // PENDINGADDREMOVES - {} // PENDINGORDERS - {}", 
                prepareOKMsgs, pendingAddRemoves, pendingOrders);
        } else {
            logger.debug("non leader yet -> sending to {}", leader);
            pendingOrders.forEach((key, value) -> 
                sendMessage(new LeaderOrderMessage(0, key, value), leader));
        }
        pendingOrders = new HashMap<>();
        pendingAddRemoves = new HashMap<>();
    }

    private void uponMembershipChangeNotification(MembershipChangedNotification notification, short sourceProto) {
        logger.info("Membership changed notification: " + notification);
        
        executedAddRemoves.put(notification.getInstance(), Pair.of(notification.getReplica(), notification.isAdding()));

        if (notification.isAdding()) {
            openConnection(notification.getReplica());
            membership.add(notification.getReplica());

            if(replicaIdSet.remove(notification.getReplica())) 
                sendRequest(new CurrentStateRequest(notification.getInstance()), HashApp.PROTO_ID);   
        } else {
            closeConnection(notification.getReplica());
            membership.remove(notification.getReplica());
        }

        if(!self.equals(leader))
            nextInstance ++; 
    }

    /*--------------------------------- Messages ---------------------------------------- */
    private void uponLeaderOrderMessage(LeaderOrderMessage msg, Host host, short sourceProto, int channelId) {
        logger.debug("Received Leader Order Message: " + msg.getOpId());
        if (leader == null) {
            logger.info("Leader still waiting majority, pending...");
            pendingOrders.put(msg.getOpId(), msg.getOp());
            return;
        }

        sendRequest(new ProposeRequest(nextInstance++, msg.getOpId(), msg.getOp()), PAXOS_PROTOCOL_ID); 
    }

    private void uponAddReplicaMessage(AddReplicaMessage msg, Host host, short sourceProto, int channelId) {
        logger.info("Received Add Replica Message: " + msg);

        if (leader == null || pendingAddRemoves.containsKey(msg.getNewReplica())) return;

        if (self.equals(msg.getContact())) replicaIdSet.add(msg.getNewReplica());

        executedAddRemoves.forEach((k, v) -> {
            if(v.getLeft().equals(msg.getNewReplica()) && v.getRight()) {
                sendRequest(new CurrentStateRequest(k), HashApp.PROTO_ID);
                return;
            }
        });

        if(!self.equals(leader)) {
            sendMessage(new AddReplicaMessage(msg.getNewReplica(), nextInstance, msg.getContact()), leader);
            return;
        }

        openConnection(msg.getNewReplica());
        sendRequest(new AddReplicaRequest(nextInstance++, msg.getNewReplica()), PAXOS_PROTOCOL_ID);
    }

    private void uponReplicaAddedMessage(ReplicaAddedMessage msg, Host host, short sourceProto, int channelId) {
        logger.info("Replica Added Message: {}", self);
        if(!host.equals(contact)) return;

        leader = msg.getLeader();
        nextInstance = msg.getInstance();
        membership = new LinkedList<>(msg.getMembership());
        membership.forEach(this::openConnection);
        sendRequest(new InstallStateRequest(msg.getState()), HashApp.PROTO_ID);        
        triggerNotification(new JoinedNotification(membership, nextInstance, msg.getLeader()));
        state = State.ACTIVE;

        pendingOrders.forEach((key, value) -> 
            sendMessage(new LeaderOrderMessage(0, key, value), leader));
    }

    private void uponLeaderMsgFail(LeaderOrderMessage msg, Host host, short destProto, Throwable throwable, int channelId) {
        logger.info("Message {} to {} failed, reason: {}", msg, host, throwable);
        if (leader == null)
            pendingOrders.put(msg.getOpId(), msg.getOp());
        else sendMessage(msg, host);
    }

    //same thing for addReplica
    private void uponMsgFail(ProtoMessage msg, Host host, short destProto, Throwable throwable, int channelId) {
        logger.info("Message {} to {} failed, reason: {}", msg, host, throwable);
    }
    
    private void nextLeaderCandidate() {
        int idx = membership.size() -1;
        for(int i = idx; i >= 0; i--) {
            Host h = membership.get(i);
            if (!pendingAddRemoves.containsKey(h) || !h.equals(previousLeader)) {
                if(h.equals(self) && state != State.JOINING) { 
                    previousLeader = h;
                    sendRequest(new PrepareRequest(nextInstance), PAXOS_PROTOCOL_ID);
                } else break;
            }
        }

        setupTimer(new LeaderCandidateTimer(), 1000);
    }

    /* --------------------------------- TCPChannel Events ---------------------------- */
    private void uponOutConnectionUp(OutConnectionUp event, int channelId) {
        logger.info("Connection to {} is up", event.getNode());
    }

    private void uponOutConnectionDown(OutConnectionDown event, int channelId) {
        logger.info("Connection to {} is down, cause {}", event.getNode(), event.getCause());
 
        Host node = event.getNode();
        if(leader == null || node.equals(leader)) 
            pendingAddRemoves.put(node, false); 

        if(node.equals(leader)) { 
            previousLeader = leader;           
            leader = null;
            nextLeaderCandidate();
        }

        closeConnection(node);
        if(self.equals(leader))
            sendRequest(new RemoveReplicaRequest(nextInstance++, node), PAXOS_PROTOCOL_ID);

        if(state == State.JOINING) membership.remove(node);
    }

    private void uponOutConnectionFailed(OutConnectionFailed<ProtoMessage> event, int channelId) {
        logger.debug("Connection to {} failed, cause: {}", event.getNode(), event.getCause());

        Host node = event.getNode();
        Long tid = setupTimer(new ConnectionRetryTimer(), 50);
        hostTimers.put(tid, node);
        hostRetries.computeIfAbsent(node, v -> CONNECTION_RETRIES);
    }

    private void uponInConnectionUp(InConnectionUp event, int channelId) {
        logger.trace("Connection from {} is up", event.getNode());
    }

    private void uponInConnectionDown(InConnectionDown event, int channelId) {
        logger.trace("Connection from {} is down, cause: {}", event.getNode(), event.getCause());
    }

    private void uponLeaderCandidateTimer(LeaderCandidateTimer timer, long timerId) {
		logger.info("Leader Candidate Timer");
        if(leader == null) 
            nextLeaderCandidate();
        else previousLeader = null;
	}

    private void uponAddReplicaTimer(AddReplicaTimer timer, long timerId) {
        if (state == State.ACTIVE) return;
        
        if (membership.size() == 0) System.exit(1);

        logger.info("Add Replica Timer");

        contact = membership.get(0);
        openConnection(contact);
        sendMessage(new AddReplicaMessage(self, 0, contact), contact);

        setupTimer(new AddReplicaTimer(), 3000);
	}

    private void uponConnectionRetryTimer(ConnectionRetryTimer timer, long timerId) {
        Host node = hostTimers.remove(timerId);
        Integer retries = hostRetries.computeIfPresent(node, (key, value) -> value - 1);

        if (retries != null && retries > 0 && membership.contains(node)) {
            openConnection(node);
            return;
        }

        hostRetries.remove(node);
        if(state == State.JOINING) 
            membership.remove(node);
        else if(leader == null) 
            pendingAddRemoves.put(node, false);
        else if(self.equals(leader))
            sendRequest(new RemoveReplicaRequest(nextInstance++, node), PAXOS_PROTOCOL_ID);

	}
}
