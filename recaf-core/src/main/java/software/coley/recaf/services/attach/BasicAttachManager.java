package software.coley.recaf.services.attach;

import com.sun.tools.attach.AgentInitializationException;
import com.sun.tools.attach.AgentLoadException;
import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import com.sun.tools.attach.spi.AttachProvider;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import software.coley.collections.Unchecked;
import software.coley.collections.observable.ObservableList;
import software.coley.instrument.BuildConfig;
import software.coley.instrument.Client;
import software.coley.instrument.Extractor;
import software.coley.instrument.io.ByteBufferAllocator;
import software.coley.instrument.message.MessageFactory;
import software.coley.instrument.sock.SocketAvailability;
import software.coley.instrument.util.Discovery;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.behavior.PrioritySortable;
import software.coley.recaf.util.DevDetection;
import software.coley.recaf.util.StringUtil;
import software.coley.recaf.util.threading.ThreadUtil;
import software.coley.recaf.workspace.model.resource.AgentServerRemoteVmResource;
import software.coley.recaf.workspace.model.resource.WorkspaceRemoteVmResource;

import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.Locale;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.jar.Manifest;

/**
 * Manager for handling instrumentation of remote JVMs.
 *
 * @author Matt Coley
 */
@ApplicationScoped
public class BasicAttachManager implements AttachManager {
	private static final Logger logger = Logging.get(BasicAttachManager.class);
	private static final long ATTACH_TIMEOUT_MS = 500;
	private static final long currentPid = ProcessHandle.current().pid();
	private static final String JMX_AGENT_ADDRESS = "com.sun.management.jmxremote.localConnectorAddress";
	private static ExtractState extractState = ExtractState.DEFAULT;

	private final DescriptorComparator descriptorComparator = new DescriptorComparator();
	private final Map<VirtualMachineDescriptor, VirtualMachine> virtualMachineMap = new ConcurrentHashMap<>();
	private final Map<VirtualMachineDescriptor, Exception> virtualMachineFailureMap = new ConcurrentHashMap<>();
	private final Map<VirtualMachineDescriptor, Integer> virtualMachinePidMap = new ConcurrentHashMap<>();
	private final Map<VirtualMachineDescriptor, Properties> virtualMachinePropertiesMap = new ConcurrentHashMap<>();
	private final Map<VirtualMachineDescriptor, String> virtualMachineMainClassMap = new ConcurrentHashMap<>();
	private final Map<VirtualMachineDescriptor, JmxBeanServerConnection> virtualMachineJmxConnMap = new ConcurrentHashMap<>();
	private final Map<VirtualMachineDescriptor, JMXConnector> virtualMachineJmxConnectorMap = new ConcurrentHashMap<>();
	private final ObservableList<VirtualMachineDescriptor> virtualMachineDescriptors = new ObservableList<>();
	private final List<PostScanListener> postScanListeners = new CopyOnWriteArrayList<>();
	private final AttachManagerConfig config;
	private ScheduledFuture<?> future;

	@Inject
	public BasicAttachManager(AttachManagerConfig config) {
		this.config = config;
		extractAgent();
	}

	/**
	 * Extracts the agent jar from Recaf to its own file.
	 */
	private void extractAgent() {
		if (extractState == ExtractState.DEFAULT) {
			Path agentPath = getAgentJarPath();
			if (!Files.isRegularFile(agentPath)) {
				// Not extracted already
				try {
					logger.debug("Extracting agent jar to Recaf directory: {}", agentPath.getFileName());
					Files.createDirectories(config.getAgentDirectory());
					Extractor.extractToPath(agentPath);
					future = ThreadUtil.scheduleAtFixedRate(this::passiveScanUpdate, 0, 1, TimeUnit.SECONDS);
					extractState = ExtractState.SUCCESS;
				} catch (IOException ex) {
					logger.error("Failed to extract agent jar to Recaf directory", ex);
					extractState = ExtractState.FAILURE;
				}
			} else {
				// Already extracted before
				extractState = ExtractState.SUCCESS;
				future = ThreadUtil.scheduleAtFixedRate(this::passiveScanUpdate, 0, 1, TimeUnit.SECONDS);
			}
		}
	}

	/**
	 * Cancel passive scan loop when shutting down.
	 */
	@PreDestroy
	private void onShutdown() {
		future.cancel(true);
	}

	/**
	 * Check for new virtual machines in the background.
	 */
	private void passiveScanUpdate() {
		try {
			if (config.getPassiveScanning().getValue())
				scan();
		} catch (Throwable t) {
			logger.error("Unhandled exception in JVM scan", t);
		}
	}

	/**
	 * @param descriptor
	 * 		VM descriptor.
	 *
	 * @return Main class of VM, if possible to resolve.
	 */
	@Nonnull
	private String mapToMainClass(@Nonnull VirtualMachineDescriptor descriptor) {
		return resolveMainClass(descriptor.displayName(), virtualMachinePropertiesMap.get(descriptor));
	}

	/**
	 * @param displayName
	 * 		Display name of the VM, usually its main class or jar followed by its arguments.
	 * @param properties
	 * 		System properties of the VM, used to read the main class from its jar when the display name is a jar.
	 *
	 * @return Main class of the VM, the jar name if the jar cannot be read, or a placeholder if neither is known.
	 */
	@Nonnull
	static String resolveMainClass(@Nullable String displayName, @Nullable Properties properties) {
		String source = displayName;
		if (properties != null && (source == null || source.isBlank() || source.toLowerCase(Locale.ROOT).contains(".jar"))) {
			String jarMainClass = readJarMainClass(properties);
			if (jarMainClass != null)
				source = jarMainClass;
		}
		if (source == null || source.isBlank())
			return "<unknown main-class>";

		// Some display names are '<class> <args...>'
		String trim = source.trim();
		int end = trim.indexOf(' ');
		return end == -1 ? trim : trim.substring(0, end);
		// Alternative idea for later: Use 'sun/launcher/LauncherHelper' as mentioned by xxDark
		//  - reliable source for main-class
	}

	/**
	 * @param properties
	 * 		System properties of a VM launched with {@code -jar}.
	 *
	 * @return {@code Main-Class} of the jar, or {@code null} if the jar cannot be found or read.
	 */
	@Nullable
	private static String readJarMainClass(@Nonnull Properties properties) {
		String command = properties.getProperty("sun.java.command", "");
		int jarIndex = command.toLowerCase(Locale.ROOT).indexOf(".jar");
		if (jarIndex <= 0)
			return null;
		try {
			Path jarPath = Paths.get(command.substring(0, jarIndex + 4));

			// A relative jar path is relative to the remote VM's working directory, not ours.
			if (!jarPath.isAbsolute()) {
				String userDir = properties.getProperty("user.dir");
				if (userDir == null || userDir.isBlank())
					return null;
				jarPath = Paths.get(userDir).resolve(jarPath);
			}
			if (!Files.isRegularFile(jarPath))
				return null;
			try (JarFile jar = new JarFile(jarPath.toFile())) {
				Manifest manifest = jar.getManifest();
				return manifest == null ? null : manifest.getMainAttributes().getValue("Main-Class");
			}
		} catch (InvalidPathException | IOException | SecurityException ex) {
			// Expected for cases like 'com.example.Main foo.jar', where the text up to '.jar' is not a single path.
			return null;
		}
	}

	/**
	 * @param descriptor
	 * 		VM descriptor.
	 *
	 * @return PID of VM process.
	 */
	private int mapToPid(@Nonnull VirtualMachineDescriptor descriptor) {
		String id = descriptor.id();
		if (id.matches("\\d+")) {
			return Integer.parseInt(descriptor.id());
		} else {
			return -1;
		}
	}

	/**
	 * @return {@code true} when the agent jar is extracted and ready to be used.
	 * {@code false} means instrumentation cannot be done since the agent is not available.
	 */
	public static boolean isAgentReady() {
		return extractState == ExtractState.SUCCESS;
	}

	/**
	 * @return Path to agent jar file.
	 */
	@Nonnull
	public Path getAgentJarPath() {
		String jarName = "agent-" + BuildConfig.VERSION + ".jar";
		return config.getAgentDirectory().resolve(jarName);
	}

	@Override
	public boolean canAttach() {
		return isAgentReady();
	}

	@Override
	public void scan() {
		int numDescriptors = virtualMachineDescriptors.size();
		List<VirtualMachineDescriptor> remoteVmList = VirtualMachine.list();
		Set<VirtualMachineDescriptor> toRemove = new HashSet<>(virtualMachineDescriptors);
		Set<VirtualMachineDescriptor> toAdd = ConcurrentHashMap.newKeySet();
		List<CompletableFuture<?>> attachFutures = new ArrayList<>();
		for (VirtualMachineDescriptor descriptor : remoteVmList) {
			// Still active in VM list, keep it.
			toRemove.remove(descriptor);

			// Add if not in the list.
			if (!virtualMachineDescriptors.contains(descriptor)) {
				String label = descriptor.id() + " - " + StringUtil.withEmptyFallback(descriptor.displayName(), "?");
				int pid = mapToPid(descriptor);
				if (pid == currentPid) // skip self
					continue;

				// Each VM is attempted on its own, so one that hangs or fails cannot hold up or cancel the others.
				attachFutures.add(attemptWithTimeout(() -> attachTo(descriptor, label), ATTACH_TIMEOUT_MS,
						late -> detachQuietly(late, label)).thenAccept(attempt -> {
					if (attempt.failure() != null) {
						Throwable failure = attempt.failure();
						virtualMachineFailureMap.put(descriptor, failure instanceof Exception ex ? ex : new Exception(failure));
						logger.debug("Remote JVM descriptor found (attach-failure): {}", label, failure);
					}
					try {
						onAttachAttempted(descriptor, attempt.value(), pid, label, numDescriptors, toAdd);
					} catch (Throwable t) {
						logger.error("Unhandled exception populating remote VM info: {}", label, t);
					}
				}));
			}
		}
		// When all attach attachFutures complete, update the observable list to update the UI
		ThreadUtil.allOf(attachFutures.toArray(new CompletableFuture[0])).thenRun(() -> {
			// Remove entries not visited in this pass
			virtualMachineDescriptors.removeAll(toRemove);

			for (VirtualMachineDescriptor descriptor : toRemove) {
				String label = descriptor.id() + " - " + StringUtil.withEmptyFallback(descriptor.displayName(), "?");
				logger.debug("Remote JVM descriptor removed: " + label);

				// Evict from state caches
				virtualMachineFailureMap.remove(descriptor);
				virtualMachinePidMap.remove(descriptor);
				virtualMachinePropertiesMap.remove(descriptor);
				virtualMachineMainClassMap.remove(descriptor);
				virtualMachineJmxConnMap.remove(descriptor);

				// Safely terminate JMX network connections
				JMXConnector connector = virtualMachineJmxConnectorMap.remove(descriptor);
				if (connector != null) {
					try {
						connector.close();
					} catch (IOException ex) {
						logger.warn("Failed to close JMX connector for removed VM: {}", label, ex);
					}
				}

				// Safely detach from the remote JVM native attachment
				VirtualMachine machine = virtualMachineMap.remove(descriptor);
				if (machine != null) {
					try {
						machine.detach();
					} catch (IOException ex) {
						logger.warn("Failed to detach from remove VM: {}", label, ex);
					}
				}
			}

			// Call listeners
			Unchecked.checkedForEach(postScanListeners, listener -> listener.onScanCompleted(toAdd, toRemove),
					(listener, t) -> logger.error("Exception thrown after scan completion", t));
		});
	}

	/**
	 * Records what could be read from a VM after an attach attempt, and adds the VM to the known list.
	 * VMs that could not be attached to are still listed, so the failure is shown and they are not retried.
	 *
	 * @param descriptor
	 * 		VM descriptor.
	 * @param machine
	 * 		Attached VM, or {@code null} when attaching failed.
	 * @param pid
	 * 		PID of the VM.
	 * @param label
	 * 		Display label for logging.
	 * @param numDescriptors
	 * 		Number of known descriptors when the scan began.
	 * @param toAdd
	 * 		Descriptors added by the current scan.
	 */
	private void onAttachAttempted(@Nonnull VirtualMachineDescriptor descriptor, @Nullable VirtualMachine machine, int pid,
	                               @Nonnull String label, int numDescriptors, @Nonnull Set<VirtualMachineDescriptor> toAdd) {
		// Get information from machine if it is available.
		if (machine != null) {
			virtualMachineMap.put(descriptor, machine);
			logger.debug("Remote JVM descriptor found (attach-success): " + label);

			// Extract additional information
			try {
				Properties systemProperties = machine.getSystemProperties();
				virtualMachinePropertiesMap.put(descriptor, systemProperties);
				virtualMachinePidMap.put(descriptor, pid);
				virtualMachineMainClassMap.put(descriptor, mapToMainClass(descriptor));

				// Enable optional JMX agent
				if (config.getAttachJmxAgent().getValue()) {
					try {
						Properties agentProperties = machine.getAgentProperties();
						String serviceUrl = agentProperties.getProperty(JMX_AGENT_ADDRESS);
						if (serviceUrl == null) {
							serviceUrl = machine.startLocalManagementAgent();
						}

						if (serviceUrl != null) {
							JMXServiceURL url = new JMXServiceURL(serviceUrl);
							@SuppressWarnings("resource") // Do NOT wrap this in a try-with-resource. It will close the connection.
							JMXConnector connector = JMXConnectorFactory.connect(url);
							MBeanServerConnection connection = connector.getMBeanServerConnection();
							virtualMachineJmxConnMap.put(descriptor, new JmxBeanServerConnection(connection));
							virtualMachineJmxConnectorMap.put(descriptor, connector);
						} else {
							logger.warn("Could fetch JMX agent address, skipping connection for: {}", label);
						}
					} catch (Exception ex) {
						logger.error("Failed to attach JMX agent to remote JVM: {}", label, ex);
					}
				}
			} catch (IOException ex) {
				logger.error("Could not read system properties from remote JVM: " + label, ex);
			}
		}

		// Add to list for listener call later.
		toAdd.add(descriptor);

		// Insert descriptor in sorted order.
		int lastComparison = 1;
		synchronized (virtualMachineDescriptors) {
			for (int i = 0; i < numDescriptors; i++) {
				VirtualMachineDescriptor other = virtualMachineDescriptors.get(i);
				int comparison = descriptorComparator.compare(descriptor, other);
				if (comparison < lastComparison) {
					virtualMachineDescriptors.add(i, descriptor);
					return;
				}
			}

			// Greater than all entries, append to end
			virtualMachineDescriptors.add(descriptor);
		}
	}

	/**
	 * @param descriptor
	 * 		VM descriptor.
	 * @param label
	 * 		Display label for logging.
	 *
	 * @return Attached VM, or {@code null} when attaching failed, in which case the failure is recorded.
	 */
	@Nullable
	private VirtualMachine attachTo(@Nonnull VirtualMachineDescriptor descriptor, @Nonnull String label) {
		try {
			AttachProvider provider = descriptor.provider();
			return provider.attachVirtualMachine(descriptor);
		} catch (IOException ex) {
			virtualMachineFailureMap.put(descriptor, ex);
			logger.debug("Remote JVM descriptor found (attach-success, read-failure): {}", label);
		} catch (AttachNotSupportedException ex) {
			virtualMachineFailureMap.put(descriptor, ex);
			logger.debug("Remote JVM descriptor found (attach-failure): {}", label);
		}
		return null;
	}

	private static void detachQuietly(@Nonnull VirtualMachine machine, @Nonnull String label) {
		try {
			machine.detach();
		} catch (IOException ex) {
			logger.debug("Failed to detach from remote JVM: {}", label, ex);
		}
	}

	/**
	 * Runs a task in the background, giving up on it after a timeout. The returned future never completes
	 * exceptionally, so it is safe to combine with others where one failure would otherwise cancel the rest.
	 *
	 * @param task
	 * 		Task to run.
	 * @param timeoutMs
	 * 		Time to wait for the task.
	 * @param abandoned
	 * 		Receives a result that arrives after the timeout, so it can be released. Nothing else will use it.
	 * @param <T>
	 * 		Result type.
	 *
	 * @return Future of the task's result, or of its failure. A timeout is reported as a {@link TimeoutException}.
	 */
	@Nonnull
	static <T> CompletableFuture<Attempt<T>> attemptWithTimeout(@Nonnull Supplier<T> task, long timeoutMs,
	                                                            @Nonnull Consumer<T> abandoned) {
		// Whichever of the task and the timeout claims the slot first decides who owns the result.
		AtomicReference<Object> slot = new AtomicReference<>();
		Object timedOut = new Object();
		CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
			T value = task.get();
			if (value != null && !slot.compareAndSet(null, value)) {
				abandoned.accept(value);
				return null;
			}
			return value;
		}, ThreadUtil.executor());
		return future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS).handle((value, error) -> {
			if (error == null)
				return new Attempt<>(value, null);
			Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
			if (cause instanceof TimeoutException && !slot.compareAndSet(null, timedOut)) {
				@SuppressWarnings("unchecked")
				T lateValue = (T) slot.get();
				return new Attempt<>(lateValue, null);
			}
			return new Attempt<>(null, cause);
		});
	}

	/**
	 * @param value
	 * 		Result of the attempt, or {@code null}.
	 * @param failure
	 * 		Reason the attempt failed, or {@code null}.
	 * @param <T>
	 * 		Result type.
	 */
	record Attempt<T>(@Nullable T value, @Nullable Throwable failure) {}

	@Nonnull
	@Override
	public WorkspaceRemoteVmResource createRemoteResource(VirtualMachineDescriptor item) throws IOException {
		VirtualMachine virtualMachine = virtualMachineMap.get(item);
		try {
			// Will initialize agent server with default arguments
			Properties properties = virtualMachinePropertiesMap.get(item);
			int port = Discovery.extractPort(properties);
			if (port <= 0) {
				// Port not found, server is not running on remote VM.
				// Load the agent to start it.
				try {
					port = SocketAvailability.findAvailable();
					String agentAbsolutePath = StringUtil.pathToAbsoluteString(getAgentJarPath());
					String agentArgs = "port=" + port + ",notrampolines";
					if (DevDetection.isDevEnv())
						agentArgs += ",debug";
					else
						agentArgs += ",namelessThreads";
					virtualMachine.loadAgent(agentAbsolutePath, agentArgs);

					// The agent server will update some properties to indicate its active.
					// We need to update our map so that we can see this indicator so that we can extract
					// the port that the server is running on if we want to reconnect.
					Properties systemProperties = virtualMachine.getSystemProperties();
					virtualMachinePropertiesMap.put(item, systemProperties);
				} catch (AgentLoadException ex) {
					// The agent jar file is written in Java 8. But Recaf uses Java 11+.
					// This is a problem on OUR side because Java 11+ handles agent interactions differently.
					//  - https://stackoverflow.com/a/54454418/
					// Basically in Java 10 they added a prefix string requirement.
					// But the Java 8 VM doesn't have that so our VM will mark it as invalid.
					if (!ex.getMessage().equals("0"))
						// If the result we get back is '0' then that means it was actually a success
						throw ex;
				}
			}

			// Connect with client
			Client client = new Client("localhost", port, ByteBufferAllocator.HEAP, MessageFactory.create());
			return new AgentServerRemoteVmResource(virtualMachine, client);
		} catch (AgentLoadException ex) {
			logger.error("Agent on remote VM '{}' could not be loaded", item, ex);
			throw new IOException("Failed remote load", ex);
		} catch (AgentInitializationException ex) {
			logger.error("Agent on remote VM '{}' crashed on initialization", item, ex);
			throw new IOException("Failed remote initialization", ex);
		} catch (IOException ex) {
			logger.error("IO error when loading agent to remote VM '{}'", item, ex);
			throw ex;
		}
	}

	@Override
	public VirtualMachine getVirtualMachine(@Nonnull VirtualMachineDescriptor descriptor) {
		return virtualMachineMap.get(descriptor);
	}

	@Override
	public Exception getVirtualMachineConnectionFailure(@Nonnull VirtualMachineDescriptor descriptor) {
		return virtualMachineFailureMap.get(descriptor);
	}

	@Override
	public int getVirtualMachinePid(@Nonnull VirtualMachineDescriptor descriptor) {
		return virtualMachinePidMap.getOrDefault(descriptor, -1);
	}

	@Nullable
	@Override
	public Properties getVirtualMachineProperties(@Nonnull VirtualMachineDescriptor descriptor) {
		return virtualMachinePropertiesMap.get(descriptor);
	}

	@Nullable
	@Override
	public String getVirtualMachineMainClass(@Nonnull VirtualMachineDescriptor descriptor) {
		return virtualMachineMainClassMap.get(descriptor);
	}

	@Nullable
	@Override
	public JmxBeanServerConnection getJmxServerConnection(@Nonnull VirtualMachineDescriptor descriptor) {
		return virtualMachineJmxConnMap.get(descriptor);
	}

	@Nonnull
	@Override
	public ObservableList<VirtualMachineDescriptor> getVirtualMachineDescriptors() {
		return virtualMachineDescriptors;
	}

	@Override
	public void addPostScanListener(@Nonnull PostScanListener listener) {
		PrioritySortable.add(postScanListeners, listener);
	}

	@Override
	public void removePostScanListener(@Nonnull PostScanListener listener) {
		postScanListeners.remove(listener);
	}

	@Nonnull
	@Override
	public String getServiceId() {
		return SERVICE_ID;
	}

	@Nonnull
	@Override
	public AttachManagerConfig getServiceConfig() {
		return config;
	}

	/**
	 * Comparator for {@link VirtualMachineDescriptor} using {@link #mapToMainClass(VirtualMachineDescriptor)}.
	 */
	class DescriptorComparator implements Comparator<VirtualMachineDescriptor> {
		@Override
		public int compare(VirtualMachineDescriptor o1, VirtualMachineDescriptor o2) {
			String k1 = virtualMachineMainClassMap.getOrDefault(o1, o1.displayName());
			String k2 = virtualMachineMainClassMap.getOrDefault(o2, o2.displayName());
			return k1.compareTo(k2);
		}
	}

	enum ExtractState {
		DEFAULT,
		SUCCESS,
		FAILURE
	}
}
