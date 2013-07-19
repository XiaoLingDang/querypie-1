package arch;

import ibis.ipl.server.Server;
import ibis.ipl.server.ServerProperties;
import ibis.util.TypedProperties;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import arch.actions.ActionsProvider;
import arch.buckets.Buckets;
import arch.buckets.CachedFilesMerger;
import arch.chains.Chain;
import arch.chains.ChainHandler;
import arch.chains.ChainNotifier;
import arch.chains.ChainResolver;
import arch.data.types.DataProvider;
import arch.data.types.Tuple;
import arch.datalayer.InputLayer;
import arch.datalayer.InputLayerRegistry;
import arch.datalayer.buckets.BucketsLayer;
import arch.dummylayer.DummyLayer;
import arch.net.NetworkLayer;
import arch.storage.Container;
import arch.storage.Factory;
import arch.storage.SubmissionCache;
import arch.storage.container.CheckedConcurrentWritableContainer;
import arch.storage.container.WritableContainer;
import arch.submissions.JobDescriptor;
import arch.submissions.Submission;
import arch.submissions.SubmissionRegistry;
import arch.utils.Configuration;
import arch.utils.Consts;
import arch.utils.Utils;

public class Arch {

	static final Logger log = LoggerFactory.getLogger(Arch.class);

	Context context;

	/*
	 * Start the architecture and return a data-structure that can be used to
	 * insert new chains to be executed.
	 */
	public void startup(Configuration conf) {
		try {
			long time = System.currentTimeMillis();

			/***** CREATE TMP DIR IF NOT PRESENT *****/
			String tmpDir = System.getProperty("java.io.tmpdir");
			File tmpFile = new File(tmpDir);
			if (!tmpFile.exists()) {
				log.info("Create tmp dir ...");
				if (!Utils.createRecursevily(tmpFile)) {
					log.warn("tmp dir not created!");
				}
			}

			/**** START IBIS IF REQUESTED ****/
			if (conf.getBoolean(Consts.START_IBIS, false)) {
				try {
					TypedProperties properties = new TypedProperties();
					properties.putAll(System.getProperties());
					properties.setProperty(ServerProperties.PRINT_EVENTS,
							"true");
					new Server(properties);
				} catch (Exception e) {
					log.error("Error starting ibis", e);
				}
			}

			/***** NET *******/
			log.debug("Starting the Ibis server ...");
			@SuppressWarnings("unchecked")
			Class<WritableContainer<Tuple>> clazz = (Class<WritableContainer<Tuple>>) (Class<?>) WritableContainer.class;
			Factory<WritableContainer<Tuple>> bufferFactory = new Factory<WritableContainer<Tuple>>(
					clazz, true, false, Consts.TUPLES_CONTAINER_BUFFER_SIZE);
			NetworkLayer net = new NetworkLayer(bufferFactory);
			net.startIbis();
			// Get some buffers in the factory ... it is better to have some
			// available, and
			// not having to allocate inside the upcall.
			ArrayList<WritableContainer<Tuple>> l = new ArrayList<WritableContainer<Tuple>>(
					Consts.STARTING_SIZE_FACTORY);
			for (int i = 0; i < Consts.STARTING_SIZE_FACTORY; i++) {
				l.add(bufferFactory.get());
			}
			while (l.size() != 0) {
				bufferFactory.release(l.remove(l.size() - 1));
			}

			boolean serverMode = net.isServer();
			log.debug("...done");

			if (serverMode) {
				log.info("Cluster starting up");
			}

			/**** SHARED DATA STRUCTURES ****/
			Container<Chain> chainsToResolve = new CheckedConcurrentWritableContainer<Chain>(
					Consts.SIZE_BUFFERS_CHAINS_RESOLVE);
			Container<Chain> chainsToProcess = new CheckedConcurrentWritableContainer<Chain>(
					Consts.SIZE_BUFFERS_CHAINS_PROCESS);
			StatisticsCollector stats = new StatisticsCollector(conf, net,
					chainsToProcess);
			CachedFilesMerger merger = new CachedFilesMerger();
			Buckets tuplesContainer = new Buckets(stats, bufferFactory, merger,
					net);
			ActionsProvider ap = new ActionsProvider();
			DataProvider dp = new DataProvider();
			Factory<Tuple> defaultTupleFactory = new Factory<Tuple>(
					Tuple.class, (Object[]) null);
			SubmissionCache cache = new SubmissionCache(net);

			SubmissionRegistry registry = new SubmissionRegistry(net, stats,
					chainsToResolve, tuplesContainer, ap, dp, conf);

			/**** INIT INPUT LAYERS ****/
			InputLayer input = InputLayer.getImplementation(conf);
			InputLayerRegistry inputRegistry = new InputLayerRegistry();
			inputRegistry.add(Consts.DEFAULT_INPUT_LAYER_ID, input);
			inputRegistry.add(Consts.BUCKET_INPUT_LAYER_ID, new BucketsLayer());
			inputRegistry.add(Consts.DUMMY_INPUT_LAYER_ID, new DummyLayer());

			/**** INIT CONTEXT ****/
			context = new Context();
			ChainNotifier notifier = new ChainNotifier(context, dp);
			context.init(inputRegistry, tuplesContainer, registry,
					chainsToResolve, chainsToProcess, notifier, net, stats, ap,
					dp, defaultTupleFactory, cache, conf);

			/**** START RESOLUTION THREADS ****/
			int i = conf.getInt(Consts.N_RES_THREADS, 1);
			for (int j = 0; j < i; ++j) {
				log.debug("Starting ChainResolver " + j + " ...");
				ChainResolver resolver = new ChainResolver(context);
				Thread thread = new Thread(resolver);
				thread.setName("Chain Resolver " + j);
				thread.start();
			}

			/**** START PROCESSING THREADS ****/
			i = conf.getInt(Consts.N_PROC_THREADS, 1);
			for (int j = 0; j < i; ++j) {
				log.debug("Starting Chain Handler " + j + " ...");
				ChainHandler handler = new ChainHandler(context);
				Thread thread = new Thread(handler);
				thread.setName("Chain Handler " + j);
				thread.start();
			}

			/**** START SORTING MERGE THREADS ****/
			i = conf.getInt(Consts.N_MERGE_THREADS, 1);
			for (int j = 0; j < i; ++j) {
				log.debug("Starting Sorting Merge threads " + j + " ...");
				Thread thread = new Thread(merger);
				thread.setName("Merge sort " + j);
				thread.start();
			}

			/**** START COMMUNICATION THREADS ****/
			log.debug("Starting Sending/receiving threads ...");
			net.startupConnections(context);

			/**** START STATISTICS THREAD ****/
			log.debug("Starting statistics thread ...");
			Thread thread = new Thread(stats);
			thread.setName("Statistics Printer");
			thread.start();

			/***** STORAGE *****/
			log.debug("Starting Data Layer ...");
			input.startup(context);

			Runtime.getRuntime().gc();
			if (serverMode) {
				net.waitUntilAllReady();
				log.info("Time to startup the cluster (ms): "
						+ (System.currentTimeMillis() - time));
				log.info("Cluster ready to accept requests");
			} else {
				net.signalReady();
			}

		} catch (Exception e) {
			log.error("Error creation program", e);
		}
	}

	public void shutdown() {
		log.info("Framework is shutting down ...");
		try {
			// Send message to everyone that should stop
			context.getNetworkLayer().signalTermination();
			context.getNetworkLayer().ibis.end();
		} catch (IOException e) {
			log.error("Error in shutting down", e);
			System.exit(1);
		}
		log.info("...done");
	}

	public Submission waitForCompletion(JobDescriptor job) throws Exception {
		Submission sub = context.getSubmissionsRegistry().waitForCompletion(
				context, job);
		if (job.getWaitForStatistics())
			context.getSubmissionsRegistry().getStatistics(job, sub);
		return sub;
	}

	/* Method for tests */
	public static void main(String[] args) throws Exception {
	}

	public boolean isServer() {
		return context.getNetworkLayer().isServer();
	}

}