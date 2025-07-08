import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import protocols.abd.ABD;
import protocols.agreement.PaxosClassic;
import protocols.agreement.PaxosDistinguishedLearner;
import protocols.app.HashApp;
import protocols.app.HashApp.ReplicationStrategy;
import protocols.statemachine.StateMachine;
import pt.unl.fct.di.novasys.babel.core.Babel;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.security.InvalidParameterException;
import java.util.Enumeration;
import java.util.Properties;


public class Main {

	//Sets the log4j (logging library) configuration file
	static {
		System.setProperty("log4j.configurationFile", "log4j2.xml");
	}


	//Creates the logger object
	private static final Logger logger = LogManager.getLogger(Main.class);

	//Default babel configuration file (can be overridden by the "-config" launch argument)
	private static final String DEFAULT_CONF = "config.properties";
	public static final String DISTINGUISHED_LEARNER = "Distinguished";

	public static void main(String[] args) throws Exception {

		//Get the (singleton) babel instance
		Babel babel = Babel.getInstance();

		//Loads properties from the configuration file, and merges them with
		// properties passed in the launch arguments
		Properties props = Babel.loadConfig(args, DEFAULT_CONF);

		//If you pass an interface name in the properties (either file or arguments), this wil get the
		// IP of that interface and create a property "address=ip" to be used later by the channels.
		addInterfaceIp(props);

		// Application
		HashApp hashApp = new HashApp(props);
		babel.registerProtocol(hashApp);

		// Replication
		StateMachine sm = null;
		ABD abd = null;
		PaxosDistinguishedLearner agreement = null;
		PaxosClassic paxosClassic = null;

		String PAXOS_IMPLEMENTATION = props.getProperty("paxos_strategy", DISTINGUISHED_LEARNER);

		if (hashApp.getReplicationStrategy() == ReplicationStrategy.SMR) {
			System.err.println("Loading SMR/Paxos protocol stack");
			// StateMachine Protocol
			sm = new StateMachine(props);
			babel.registerProtocol(sm);
			// Agreement Protocol
			if (PAXOS_IMPLEMENTATION.equals(DISTINGUISHED_LEARNER)) {
				agreement = new PaxosDistinguishedLearner(props);
				babel.registerProtocol(agreement);
			} else {
				paxosClassic = new PaxosClassic(props);
				babel.registerProtocol(paxosClassic);
			}
		} else {
			System.err.println("Loading ABD protocol stack");
			abd = new ABD(props);
			babel.registerProtocol(abd);
		}

		//Init the protocols. This should be done after creating all protocols,
		// since there can be inter-protocol communications in this step.
		hashApp.init(props);
		if (hashApp.getReplicationStrategy() == ReplicationStrategy.SMR) {
			sm.init(props);

			if (PAXOS_IMPLEMENTATION.equals(DISTINGUISHED_LEARNER))
				agreement.init(props);
			else
				paxosClassic.init(props);
		} else {
			abd.init(props);
		}

		//Start babel and protocol threads
		babel.start();

		Runtime.getRuntime().addShutdownHook(new Thread(() -> logger.info("Goodbye")));

	}

	public static String getIpOfInterface(String interfaceName) throws SocketException {
		if (interfaceName.equalsIgnoreCase("lo"))
			return "127.0.0.1"; //This is an special exception to deal with the loopback.
		NetworkInterface networkInterface = NetworkInterface.getByName(interfaceName);
		System.out.println(networkInterface);
		Enumeration<InetAddress> inetAddress = networkInterface.getInetAddresses();
		InetAddress currentAddress;
		while (inetAddress.hasMoreElements()) {
			currentAddress = inetAddress.nextElement();
			if (currentAddress instanceof Inet4Address && !currentAddress.isLoopbackAddress()) {
				return currentAddress.getHostAddress();
			}
		}
		return null;
	}

	public static void addInterfaceIp(Properties props) throws SocketException, InvalidParameterException {
		String interfaceName;
		if ((interfaceName = props.getProperty("interface")) != null) {
			String ip = getIpOfInterface(interfaceName);
			if (ip != null)
				props.setProperty("address", ip);
			else {
				throw new InvalidParameterException("Property interface is set to " + interfaceName + ", but has no ip");
			}
		}
	}

}
