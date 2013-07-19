package arch.net;

import ibis.ipl.Ibis;
import ibis.ipl.IbisCapabilities;
import ibis.ipl.IbisCreationFailedException;
import ibis.ipl.IbisFactory;
import ibis.ipl.IbisIdentifier;
import ibis.ipl.PortType;
import ibis.ipl.ReceivePort;
import ibis.ipl.SendPort;
import ibis.ipl.WriteMessage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import arch.Context;
import arch.StatisticsCollector;
import arch.chains.Chain;
import arch.data.types.Tuple;
import arch.storage.Container;
import arch.storage.Factory;
import arch.storage.container.CheckedConcurrentWritableContainer;
import arch.storage.container.WritableContainer;
import arch.utils.Consts;

public class NetworkLayer {

	static final Logger log = LoggerFactory.getLogger(NetworkLayer.class);

	public static final String queryReceiverPort = "query-receiver-port";
	public static final String nameMgmtReceiverPort = "mgmt-receiver-port";
	public static final String nameBcstReceiverPort = "bcst-receiver-port";
	public static final String nameReceiverPort = "receiver-port";

	public static final PortType requestPortType = new PortType(
			PortType.COMMUNICATION_RELIABLE, PortType.SERIALIZATION_DATA,
			PortType.CONNECTION_MANY_TO_ONE, PortType.RECEIVE_AUTO_UPCALLS);

	public static final PortType mgmtRequestPortType = new PortType(
			PortType.COMMUNICATION_RELIABLE, PortType.SERIALIZATION_OBJECT,
			PortType.CONNECTION_MANY_TO_ONE, PortType.RECEIVE_AUTO_UPCALLS);

	public static final PortType broadcastPortType = new PortType(
			PortType.COMMUNICATION_RELIABLE, PortType.SERIALIZATION_OBJECT_SUN,
			PortType.CONNECTION_MANY_TO_MANY, PortType.RECEIVE_AUTO_UPCALLS);

	public static final PortType queryPortType = new PortType(
			PortType.COMMUNICATION_RELIABLE, PortType.SERIALIZATION_DATA,
			PortType.CONNECTION_MANY_TO_ONE, PortType.RECEIVE_EXPLICIT);

	public static final IbisCapabilities ibisCapabilities = new IbisCapabilities(
			IbisCapabilities.ELECTIONS_STRICT,
			IbisCapabilities.MEMBERSHIP_TOTALLY_ORDERED,
			IbisCapabilities.SIGNALS, IbisCapabilities.MALLEABLE);

	public Ibis ibis = null;
	private int partitionId = 0;
	private IbisIdentifier[] assignedPartitions = null;
	private final Map<String, Integer> assignedIds = new HashMap<String, Integer>();
	private final Set<ReceivePort> receivePorts = new HashSet<ReceivePort>();
	private final Map<String, SendPort> senderPorts = new ConcurrentHashMap<String, SendPort>();
	private final Map<String, Long> timers = new ConcurrentHashMap<String, Long>();

	private boolean serverMode = false;
	private IbisIdentifier server = null;
	private StatisticsCollector stats = null;

	ChainSender sender;
	TupleRequester tupleRequester;
	TupleSender tupleSender;
	Receiver receiver;
	Container<Chain> chainsToSend = new CheckedConcurrentWritableContainer<Chain>(
			Consts.SIZE_BUFFERS_CHAIN_SEND);
	Container<ChainTerminator.ChainInfo> chainsTerminated = new CheckedConcurrentWritableContainer<ChainTerminator.ChainInfo>(
			Consts.SIZE_BUFFERS_CHAIN_TERMINATED);
	Factory<ChainTerminator.ChainInfo> chFactory = new Factory<ChainTerminator.ChainInfo>(
			ChainTerminator.ChainInfo.class);

	Factory<WritableContainer<Tuple>> bufferFactory = null;

	private boolean monitorCounters = false;

	private int statsCount;

	SendPort broadcastPort;
	
	private IbisMonitor ibisMonitor;

	public NetworkLayer(Factory<WritableContainer<Tuple>> bufferFactory) {
		this.bufferFactory = bufferFactory;
	}

	/*********** PUBLIC INTERFACE ****************/

	public void sendChain(Chain chain) throws Exception {
		chainsToSend.add(chain);
	}

	public void sendChains(WritableContainer<Chain> chainsToProcess)
			throws Exception {
		chainsToSend.addAll(chainsToProcess);
	}

	public void signalChainTerminated(Chain chain) throws Exception {
		ChainTerminator.ChainInfo ch = chFactory.get();
		ch.nodeId = chain.getSubmissionNode();
		ch.submissionId = chain.getSubmissionId();
		ch.chainId = chain.getChainId();
		ch.parentChainId = chain.getParentChainId();
		ch.nchildrens = chain.getChainChildren();
		ch.repFactor = chain.getReplicatedFactor();
		chainsTerminated.add(ch);
		chFactory.release(ch);
	}

	public void signalReady() throws IOException {
		ibis.registry().signal("ready", server);
	}

	public void signalsBucketToFetch(int idSubmission, int idBucket,
			int remoteNodeId, long bufferKey) {
	    	// called from upcall thread, so no new thread needed.
		tupleRequester.handleNewRequest(idSubmission, idBucket, remoteNodeId,
				bufferKey, 0, 0);
	}

	public void signalsBucketToFetch(int idSubmission, int idBucket,
			int remoteNodeId, long bufferKey, int sequence, int nrequest) {
		tupleRequester.handleNewRequest(idSubmission, idBucket, remoteNodeId,
				bufferKey, sequence, nrequest);
	}

	public void addRequestToSendTuples(long bucketKey, int remoteNodeId,
			int submissionId, int bucketId, long ticket, int sequence,
			int nrequest) {
		tupleSender.handleNewRequest(bucketKey, remoteNodeId, submissionId,
				bucketId, ticket, sequence, nrequest);
	}

	public void waitUntilAllReady() throws InterruptedException {
		int n = getNumberNodes() - 1;
		int currentSignals = 0;
		while (currentSignals < n) {
			String[] signals = ibis.registry().receivedSignals();
			if (signals != null) {
				for (String signal : signals) {
					if (signal.equalsIgnoreCase("ready")) {
						currentSignals++;
					}
				}
			}
			Thread.sleep(100);
		}
	}

	public void removeActiveRequest(long ticket) {
		tupleRequester.removeActiveRequest(ticket);
	}

	public boolean isServer() {
		return serverMode;
	}

	public void startIbis() throws IbisCreationFailedException, IOException {

		if (ibis == null) {
			ibis = IbisFactory.createIbis(ibisCapabilities, null,
					requestPortType, queryPortType, mgmtRequestPortType,
					broadcastPortType);

			int poolSize = Integer.valueOf(System.getProperty("ibis.pool.size",
					"1"));

			assignedPartitions = new IbisIdentifier[poolSize];

			try {
				// First wait for all ibises to join.
				int nJoined = 0;
				do {
					IbisIdentifier[] joinedIbis = ibis.registry()
							.joinedIbises();
					if (joinedIbis.length > 0) {
						System.arraycopy(joinedIbis, 0, assignedPartitions,
								nJoined, joinedIbis.length);
						nJoined += joinedIbis.length;
					}
					try {
						Thread.sleep(10);
					} catch (InterruptedException e) {
						// ignore
					}
				} while (nJoined < poolSize);

				// Sort on IbisIdentifier, which sorts on pool and then
				// location.
				// This at least gives a fixed order which may help in placing
				// caches ...
				Arrays.sort(assignedPartitions);
				for (int i = 0; i < assignedPartitions.length; i++) {
					if (assignedPartitions[i].equals(ibis.identifier())) {
						partitionId = i;
						log.debug("Assigned partition " + i + " to "
								+ ibis.identifier());
					}
					assignedIds.put(assignedPartitions[i].name(), i);
				}
			} catch (Exception e) {
				log.error("Error", e);
			}

			// Put the server on partition 0.
			if (assignedPartitions[0].equals(ibis.identifier())) {
				server = ibis.registry().elect("server");
				serverMode = true;
			} else {
				server = ibis.registry().getElectionResult("server");
			}

			if (log.isDebugEnabled()) {
				log.debug("I AM " + ibis.identifier() + ", partition "
						+ partitionId);
			}
		}
		try {
		    ibisMonitor = IbisMonitor.createMonitor(ibis);
		} catch(Throwable e) {
		    log.info("Could not create IbisMonitor instance", e);
		}
	}

	public void startupConnections(Context context) {
		stats = context.getStatisticsCollector();
		try {

			// Populate the initial buffer
			// ArrayList<WritableContainer<Tuple>> wb = new
			// ArrayList<WritableContainer<Tuple>>();
			// for (int i = 0; i < Consts.STARTING_SIZE_FACTORY; ++i) {
			// wb.add(bufferFactory.get());
			// }
			// for (int i = 0; i < Consts.STARTING_SIZE_FACTORY; ++i) {
			// bufferFactory.release(wb.get(i));
			// }

			sender = new ChainSender(context, chainsToSend);
			Thread thread = new Thread(sender);
			thread.setName("Chain Sender");
			thread.start();

			tupleRequester = new TupleRequester(context);
			tupleSender = new TupleSender(context, bufferFactory);

			/**** START SUBMISSION MANAGEMENT THREAD ****/
			log.debug("Starting Termination chains thread...");
			ChainTerminator terminator = new ChainTerminator(context,
					chainsTerminated);
			thread = new Thread(terminator);
			thread.setName("Chain Terminator");
			thread.start();

			receiver = new Receiver(context, bufferFactory);
			ReceivePort port = ibis.createReceivePort(requestPortType,
					nameReceiverPort, receiver);
			port.enableConnections();
			port.enableMessageUpcalls();
			receivePorts.add(port);

			port = ibis.createReceivePort(mgmtRequestPortType,
					nameMgmtReceiverPort, receiver);
			port.enableConnections();
			port.enableMessageUpcalls();
			receivePorts.add(port);
			log.debug("Mgmt receiver port is created");

			port = ibis.createReceivePort(broadcastPortType,
					nameBcstReceiverPort, receiver);
			port.enableConnections();
			port.enableMessageUpcalls();
			receivePorts.add(port);
			log.debug("Broadcast receiver port is created");

			// Start a broadcast port
			broadcastPort = ibis.createSendPort(broadcastPortType);

			// Connect every node with all the others and put the ports in
			// sendPorts
			for (IbisIdentifier peer : assignedPartitions) {
				String nameSenderPort = nameReceiverPort + peer.name();
				startSenderPort(requestPortType, nameSenderPort, peer,
						nameReceiverPort);

				nameSenderPort = nameMgmtReceiverPort + peer.name();
				startSenderPort(mgmtRequestPortType, nameSenderPort, peer,
						nameMgmtReceiverPort);

				if (!peer.equals(ibis.identifier())) {
					broadcastPort.connect(peer, nameBcstReceiverPort);
				}
			}

		} catch (Exception e) {
			log.error("Error in setting up the connections", e);
		}
	}

	public int getMyPartition() {
		return partitionId;
	}

	public int getNumberNodes() {
		return assignedPartitions.length;
	}

	public void stopIbis() throws IOException {
		for (ReceivePort rp : receivePorts) {
			rp.close();
		}

		ibis.end();
	}

	public WriteMessage getMessageToSend(IbisIdentifier receiver) {
		return getMessageToSend(receiver, nameReceiverPort);
	}

	public WriteMessage getMessageToSend(IbisIdentifier receiver,
			String receiverPort) {

		SendPort port = null;
		try {
			String nameSenderPort = receiverPort + receiver.name();
			if (!senderPorts.containsKey(nameSenderPort)) {
				PortType type = null;
				if (receiverPort.equals(queryReceiverPort)) {
					type = queryPortType;
				} else if (receiverPort.equals(nameMgmtReceiverPort)) {
					type = mgmtRequestPortType;
				} else if (receiverPort.equals(nameBcstReceiverPort)) {
					type = broadcastPortType;
				} else {
					type = requestPortType;
				}
				startSenderPort(type, nameSenderPort, receiver, receiverPort);
			}
			port = senderPorts.get(nameSenderPort);
			WriteMessage w = port.newMessage();
			timers.put(nameSenderPort, System.currentTimeMillis());
			return w;
		} catch (Exception e) {
			log.error("Failed in getting new message to write", e);
			return null;
		}
	}

	public IbisIdentifier getPeerLocation(int index) {
		return assignedPartitions[index];
	}

	public int getPeerId(IbisIdentifier id) {
		return assignedIds.get(id.name());
	}

	public IbisIdentifier[] getPeersLocation(int start, int end) {
		try {
			return Arrays.copyOfRange(assignedPartitions, start, end + 1);
		} catch (Exception e) {
			log.error("Error in doing this: length: "
					+ assignedPartitions.length + " start: " + start + " end: "
					+ end, e);
		}

		return null;
	}

	private SendPort startSenderPort(PortType senderPortType,
			String senderPort, IbisIdentifier receiver, String receiverPort) {

		SendPort port = null;
		try {
			port = ibis.createSendPort(senderPortType, senderPort);
			port.connect(receiver, receiverPort);
			if (port.connectedTo() != null) {
				senderPorts.put(senderPort, port);
				return port;
			} else {
				return null; // Connected to any resource
			}

		} catch (Exception e) {
			log.error("Failed in creating the sender port " + senderPort
					+ "to node " + receiver, e);
		}

		return port;
	}

	public void signalTermination() throws IOException {
		for (IbisIdentifier peer : assignedPartitions) {
			if (!peer.equals(ibis.identifier())) {
				WriteMessage msg = getMessageToSend(peer, nameMgmtReceiverPort);
				msg.writeByte((byte) 3);
				msg.finish();
				if (log.isDebugEnabled()) {
					log.debug("Sent message with id 3 to " + peer);
				}
			}
		}
	}

	public long getCounter(String string) throws IOException {
		return ibis.registry().getSequenceNumber(string);
	}

	public long getSentBytes() {
		try {
			return Long.parseLong(ibis.getManagementProperty("bytesSent"));
		} catch (Exception e) {
			log.error("getSentBytes error", e);
			return 0;
		}
	}

	public long getSentMessages() {
		try {
			return Long.parseLong(ibis
					.getManagementProperty("outgoingMessageCount"));
		} catch (Exception e) {
			log.error("getSentMessages error", e);
			return 0;
		}
	}

	public long getReceivedBytes() {
		try {
			return Long.parseLong(ibis.getManagementProperty("bytesReceived"));
		} catch (Exception e) {
			log.error("getSentBytes error", e);
			return 0;
		}
	}

	public long getReceivedMessages() {
		try {
			return Long.parseLong(ibis
					.getManagementProperty("incomingMessageCount"));
		} catch (Exception e) {
			log.error("getSentMessages error", e);
			return 0;
		}
	}

	public void stopMonitorCounters() {
	    if (ibisMonitor != null) {
		ibisMonitor.setMonitoring(false);
	    }
	}

	public synchronized void startMonitorCounters() {
	    if (ibisMonitor != null) {
		ibisMonitor.setMonitoring(true);
	    }
	}
	
	public void finishMessage(WriteMessage msg, int submissionId)
			throws IOException {
		SendPort p = msg.localPort();
		long bytes = msg.finish();
		long startTime = timers.get(p.name());
		stats.addCounter(0, submissionId, "Time sending",
				System.currentTimeMillis() - startTime);
		stats.addCounter(0, submissionId, "Bytes sent", bytes);
		// stats.addCounter(0, submissionId, "Messages sent", 1);
	}

	public static class CountInfo {
		int count;
		List<Object[]> receivedObjects = new ArrayList<Object[]>();
		boolean success = true;
	}

	int activeBroadcastCount = 0;
	Map<Integer, CountInfo> activeBroadcasts = new HashMap<Integer, CountInfo>();
	int activeRetrievalCount = 0;
	Map<Integer, CountInfo> activeRetrievals = new HashMap<Integer, CountInfo>();
	int activeCodeExecutionsCount = 0;
	Map<Integer, CountInfo> activeCodeExecutions = new HashMap<Integer, CountInfo>();

	public boolean executeRemoteCode(int nodeId, int submissionId,
			String className) {
		try {
			int c;
			CountInfo remaining = new CountInfo();
			remaining.count = assignedPartitions.length - 1;

			synchronized (activeCodeExecutions) {
				c = activeCodeExecutionsCount++;
				activeCodeExecutions.put(c, remaining);
			}

			WriteMessage msg = broadcastPort.newMessage();
			msg.writeByte((byte) 14);
			msg.writeInt(c);
			msg.writeInt(nodeId);
			msg.writeInt(submissionId);
			msg.writeString(className);
			msg.finish();

			synchronized (remaining) {
				while (remaining.count > 0) {
					remaining.wait();
				}
			}

			synchronized (activeCodeExecutions) {
				activeCodeExecutions.remove(c);
			}

			// Return the objects
			return remaining.success;

		} catch (Exception e) {
			log.error("Failed retrieving objects", e);
		}

		return false;
	}

	public List<Object[]> retrieveObjects(int submissionId, Object[] keys) {
		try {
			int c;
			CountInfo remaining = new CountInfo();
			remaining.count = assignedPartitions.length - 1;

			synchronized (activeRetrievals) {
				c = activeRetrievalCount++;
				activeRetrievals.put(c, remaining);
			}

			WriteMessage msg = broadcastPort.newMessage();
			msg.writeByte((byte) 12);
			msg.writeInt(c);
			msg.writeInt(submissionId);
			msg.writeObject(keys);
			msg.finish();

			synchronized (remaining) {
				while (remaining.count > 0) {
					remaining.wait();
				}
			}

			synchronized (activeRetrievals) {
				activeRetrievals.remove(c);
			}

			// Return the objects
			return remaining.receivedObjects;

		} catch (Exception e) {
			log.error("Failed retrieving objects", e);
		}

		return null;
	}

	public void broadcastObjects(int submissionId, Object[] keys,
			Object[] values) {
		try {
			int c;
			CountInfo remaining = new CountInfo();
			remaining.count = assignedPartitions.length - 1;
			synchronized (activeBroadcasts) {
				c = activeBroadcastCount++;
				activeBroadcasts.put(c, remaining);
			}

			WriteMessage msg = broadcastPort.newMessage();
			msg.writeByte((byte) 10);
			msg.writeInt(c);
			msg.writeInt(submissionId);
			msg.writeObject(keys);
			msg.writeObject(values);
			msg.finish();

			synchronized (remaining) {
				while (remaining.count > 0) {
					remaining.wait();
				}
			}
			synchronized (activeBroadcasts) {
				activeBroadcasts.remove(c);
			}
		} catch (Exception e) {
			log.error("Failed broadcasting object", e);
		}
	}
	
	public void broadcastStartMonitoring() throws IOException {
	    if (ibisMonitor != null) {
		WriteMessage msg = broadcastPort.newMessage();
		msg.writeByte((byte) 16);
		msg.finish();
	    }
	}
	
	
	public void broadcastStopMonitoring() throws IOException {
	    if (ibisMonitor != null) {
		WriteMessage msg = broadcastPort.newMessage();
		msg.writeByte((byte) 17);
		msg.finish();
	    }
	}

	public void sendObject(int submissionId, int nodeId, Object key,
			Object value) {
		IbisIdentifier id = getPeerLocation(nodeId);
		WriteMessage msg = getMessageToSend(id, nameMgmtReceiverPort);
		timers.put(msg.localPort().name(), System.currentTimeMillis());
		int c;
		CountInfo remaining = new CountInfo();
		remaining.count = 1;
		synchronized (activeBroadcasts) {
			c = activeBroadcastCount++;
			activeBroadcasts.put(c, remaining);
		}
		try {
			msg.writeByte((byte) 10);
			msg.writeInt(c);
			msg.writeInt(submissionId);
			msg.writeObject(new Object[] { key });
			msg.writeObject(new Object[] { value });
			finishMessage(msg, submissionId);

			synchronized (remaining) {
				while (remaining.count > 0) {
					remaining.wait();
				}
			}
			synchronized (activeBroadcasts) {
				activeBroadcasts.remove(c);
			}
		} catch (Exception e) {
			log.error("Failed sending object", e);
		}

	}
}