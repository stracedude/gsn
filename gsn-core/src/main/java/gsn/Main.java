/**
* Global Sensor Networks (GSN) Source Code
* Copyright (c) 2006-2014, Ecole Polytechnique Federale de Lausanne (EPFL)
* 
* This file is part of GSN.
* 
* GSN is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
* 
* GSN is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
* 
* You should have received a copy of the GNU General Public License
* along with GSN.  If not, see <http://www.gnu.org/licenses/>.
* 
* File: src/gsn/Main.java
*
* @author parobert
* @author cl3m
* @author Jerome Rousselot
* @author gsn_devs
* @author Mehdi Riahi
* @author Ali Salehi
* @author Behnaz Bostanipour
* @author Timotee Maret
* @author Julien Eberle
*
*/

package gsn;

import gsn.beans.BeansInitializer;
import gsn.beans.ContainerConfig;
import gsn.beans.StorageConfig;
import gsn.beans.VSensorConfig;
import gsn.config.GsnConf;
import gsn.config.VsConf;
import gsn.data.DataStore;
import gsn.http.delivery.LocalDeliveryWrapper;
import gsn.http.delivery.PushDelivery;
import gsn.monitoring.MemoryMonitor;
import gsn.monitoring.Monitorable;
import gsn.networking.zeromq.ZeroMQDeliverySync;
import gsn.networking.zeromq.ZeroMQDeliveryAsync;
import gsn.networking.zeromq.ZeroMQProxy;
import gsn.storage.SQLValidator;
import gsn.storage.StorageManager;
import gsn.storage.StorageManagerFactory;
import gsn.storage.hibernate.DBConnectionInfo;
import gsn.utils.ValidityTools;
import gsn.vsensor.SQLValidatorIntegration;
import gsn.wrappers.WrappersUtil;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.SplashScreen;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SignatureException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.server.session.HashSessionIdManager;
import org.eclipse.jetty.server.ssl.SslSocketConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.webapp.WebAppContext;

import org.zeromq.ZContext;

import org.eclipse.jetty.server.AbstractConnector;


/**
 * Web Service URLs :
 * Microsoft SensorMap: http://localhost:22001/services/Service?wsdl
 * GSN: http://localhost:22001/services/GSNWebService?wsdl
 */
public final class Main {
	
    public static final int        DEFAULT_MAX_DB_CONNECTIONS       = 8;
	public static final String     DEFAULT_GSN_CONF_FOLDER            = "../conf";
	public static final String     DEFAULT_WEB_APP_FOLDER             = "src/main/webapp";
	public static final String     DEFAULT_VIRTUAL_SENSOR_FOLDER = "../virtual-sensors";
	public static transient Logger logger                           = LoggerFactory.getLogger ( Main.class );

	/**
	 * Mapping between the wrapper name (used in addressing of stream source)
	 * into the class implementing DataSource.
	 */
	private static  Properties                            wrappers ;
	private static final int                              DEFAULT_JETTY_SERVLETS = 100;
	private static Main                                   singleton ;
	private static int                                    gsnControllerPort      = 22232;
	public static String                                  gsnConfFolder          = DEFAULT_GSN_CONF_FOLDER;
	public static String                                  webAppPath             = DEFAULT_WEB_APP_FOLDER;
	public static String                                  virtualSensorDirectory = DEFAULT_VIRTUAL_SENSOR_FOLDER;
	private static ZeroMQProxy                            zmqproxy;
	private static StorageManager                         mainStorage;
    private static StorageManager                         windowStorage;
    private static StorageManager                         validationStorage;
    private static ZContext                                zmqContext             = new ZContext();
    private static HashMap<Integer, StorageManager>       storages                = new HashMap<Integer, StorageManager>();
    private static HashMap<VSensorConfig, StorageManager> storagesConfigs         = new HashMap<VSensorConfig, StorageManager>();
    private GSNController                                 controlSocket;
    private ContainerConfig                               containerConfig;
    private static GsnConf gsnConf;
    private static Map <String,VsConf> vsConf =new HashMap<String,VsConf>();
    private static ArrayList<Monitorable> toMonitor = new ArrayList<Monitorable>();
    
    /*
     *  Retrieving ThreadMXBean instance of JVM
     *  It would be used for monitoring CPU time of each virtual sensor
     */

    private static ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

    private Main() throws Exception {

		ValidityTools.checkAccessibilityOfFiles ( WrappersUtil.DEFAULT_WRAPPER_PROPERTIES_FILE , gsnConfFolder + "/gsn.xml");
		ValidityTools.checkAccessibilityOfDirs ( virtualSensorDirectory );
		//  initializeConfiguration();
		try {
			controlSocket = new GSNController(null, gsnControllerPort);
			containerConfig = loadContainerConfiguration();
			updateSplashIfNeeded(new String[] {"GSN is starting at port:"+containerConfig.getContainerPort(),"All GSN logs are available at: logs/gsn.log"});
			System.out.println("Global Sensor Networks (GSN) is Starting on port "+containerConfig.getContainerPort()+"...");
            System.out.println("The logs of GSN server are available in logs/gsn.log file.");
			System.out.println("To Stop GSN execute the gsn-stop script.");
		} catch ( FileNotFoundException e ) {
			logger.error ( "The the configuration file : conf/gsn.xml doesn't exist.");
			throw new Exception(e);
		}
        int maxDBConnections = System.getProperty("maxDBConnections") == null ? DEFAULT_MAX_DB_CONNECTIONS : Integer.parseInt(System.getProperty("maxDBConnections"));
        int maxSlidingDBConnections = System.getProperty("maxSlidingDBConnections") == null ? DEFAULT_MAX_DB_CONNECTIONS : Integer.parseInt(System.getProperty("maxSlidingDBConnections"));
        int maxServlets = System.getProperty("maxServlets") == null ? DEFAULT_JETTY_SERVLETS : Integer.parseInt(System.getProperty("maxServlets"));

    	
    	DataStore ds = new DataStore(gsnConf);

        mainStorage = StorageManagerFactory.getInstance(containerConfig.getStorage().getJdbcDriver ( ) , containerConfig.getStorage().getJdbcUsername ( ) , containerConfig.getStorage().getJdbcPassword ( ) , containerConfig.getStorage().getJdbcURL ( ) , maxDBConnections);
        
        StorageConfig sc = containerConfig.getSliding() != null ? containerConfig.getSliding().getStorage() : containerConfig.getStorage() ;
        windowStorage = StorageManagerFactory.getInstance(sc.getJdbcDriver ( ) , sc.getJdbcUsername ( ) , sc.getJdbcPassword ( ) , sc.getJdbcURL ( ), maxSlidingDBConnections);
        
        validationStorage = StorageManagerFactory.getInstance("org.h2.Driver", "sa", "", "jdbc:h2:mem:validator", Main.DEFAULT_MAX_DB_CONNECTIONS);

        logger.trace ( "The Container Configuration file loaded successfully." );
        
        toMonitor.add(new MemoryMonitor());

		try {
			logger.debug("Starting the http-server @ port: "+containerConfig.getContainerPort()+" (maxDBConnections: "+maxDBConnections+", maxSlidingDBConnections: " + maxSlidingDBConnections + ", maxServlets:"+maxServlets+")"+" ...");
            Server jettyServer = getJettyServer(getContainerConfig().getContainerPort(), getContainerConfig().getSSLPort(), maxServlets);
			jettyServer.start ( );
			logger.debug("http-server running @ port: "+containerConfig.getContainerPort());
		} catch ( Exception e ) {
			throw new Exception("Start of the HTTP server failed. The HTTP protocol is used for monitoring GSN and some remote communication: "+ e.getMessage(),e);
		}
		
		if (containerConfig.isZMQEnabled()){
			//start the 0MQ proxy
			zmqproxy = new ZeroMQProxy(containerConfig.getZMQProxyPort(),containerConfig.getZMQMetaPort());
		}
		
		VSensorLoader vsloader = VSensorLoader.getInstance ( virtualSensorDirectory );
		File vsDir=new File(virtualSensorDirectory);
		for (File f:vsDir.listFiles()){
			if (f.getName().endsWith(".xml")){
				VsConf vs= VsConf.load(f.getPath());
				vsConf.put(vs.name(), vs);
			}
		}
		controlSocket.setLoader(vsloader);

		vsloader.addVSensorStateChangeListener(new SQLValidatorIntegration(SQLValidator.getInstance()));
		vsloader.addVSensorStateChangeListener(DataDistributer.getInstance(LocalDeliveryWrapper.class));
		vsloader.addVSensorStateChangeListener(DataDistributer.getInstance(PushDelivery.class));
		if (containerConfig.isZMQEnabled())
			vsloader.addVSensorStateChangeListener(DataDistributer.getInstance(ZeroMQDeliverySync.class));
		    vsloader.addVSensorStateChangeListener(DataDistributer.getInstance(ZeroMQDeliveryAsync.class));

		ContainerImpl.getInstance().addVSensorDataListener(DataDistributer.getInstance(LocalDeliveryWrapper.class));
		ContainerImpl.getInstance().addVSensorDataListener(DataDistributer.getInstance(PushDelivery.class));
		ContainerImpl.getInstance().addVSensorDataListener(DataDistributer.getInstance(ZeroMQDeliverySync.class));
		ContainerImpl.getInstance().addVSensorDataListener(DataDistributer.getInstance(ZeroMQDeliveryAsync.class));
		vsloader.startLoading();

	}

	private static void closeSplashIfneeded() {
		if (isHeadless())
			return;
		SplashScreen splash = SplashScreen.getSplashScreen();
		//Check if we have specified any splash screen
		if (splash == null) {
			return;
		}
		if (splash.isVisible())
			splash.close();
	}


	private static void updateSplashIfNeeded(String message[]) {
		boolean headless_check = isHeadless();

		if (!headless_check) {
			SplashScreen splash = SplashScreen.getSplashScreen();
			if (splash == null)
				return;
			if (splash.isVisible()) {
				//Get a graphics overlay for the splash screen
				Graphics2D g = splash.createGraphics();
				//Do some drawing on the graphics object
				//Now update to the splash screen

				g.setComposite(AlphaComposite.Clear);
				g.fillRect(0,0,400,70);
				g.setPaintMode();
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g.setColor(Color.BLACK);
				g.setFont(new Font("Arial",Font.BOLD,11));
				for (int i=0;i<message.length;i++)
					g.drawString(message[i], 13, 16*i+10);
				splash.update();
			}
		}
	}

	private static boolean isHeadless() {
		return GraphicsEnvironment.isHeadless();
	}

	public synchronized static Main getInstance() {
		if (singleton==null)
			try {
				singleton=new Main();
			} catch (Exception e) {
				logger.error(e.getMessage(),e);
				throw new RuntimeException(e);
			}
			return singleton;
	}

	public static void main(String[] args)  {
		if (args.length > 0) {
		    Main.gsnControllerPort = Integer.parseInt(args[0]);
		}
		if (args.length > 1) {
			Main.gsnConfFolder = args[1];
		}
		if (args.length > 2) {
			Main.virtualSensorDirectory = args[2];
		}
		updateSplashIfNeeded(new String[] {"GSN is trying to start.","All GSN logs are available at: logs/gsn.log"});
		try {
			Main.getInstance();
		}catch (Exception e) {
			logger.error(e.getMessage(), e);
			updateSplashIfNeeded(new String[] {"Starting GSN failed! Look at logs/gsn.log for more information."});
			try {
				Thread.sleep(4000);
			} catch (InterruptedException e1) {}
		}
		closeSplashIfneeded();
	}

	public static ContainerConfig loadContainerConfiguration() throws InvalidKeyException, NoSuchAlgorithmException, NoSuchProviderException, KeyStoreException, CertificateException, SecurityException, SignatureException, IOException{
		ValidityTools.checkAccessibilityOfFiles (  WrappersUtil.DEFAULT_WRAPPER_PROPERTIES_FILE , gsnConfFolder + "/gsn.xml");
		ValidityTools.checkAccessibilityOfDirs ( virtualSensorDirectory );
		ContainerConfig toReturn = null;
		try {
			toReturn = loadContainerConfig (gsnConfFolder + "/gsn.xml" );
			logger.info ( "Loading wrappers.properties at : " + WrappersUtil.DEFAULT_WRAPPER_PROPERTIES_FILE);
			wrappers = WrappersUtil.loadWrappers(new HashMap<String, Class<?>>());
			logger.info ( "Wrappers initialization ..." );
		} catch ( FileNotFoundException e ) {
			logger.error ("The the configuration file : " + gsnConfFolder + "/gsn.xml doesn't exist. "+ e.getMessage());
			logger.info ( "Check the path of the configuration file and try again." );
			System.exit ( 1 );
		} catch ( ClassNotFoundException e ) {
			logger.error ( "The file wrapper.properties refers to one or more classes which don't exist in the classpath"+ e.getMessage());
			System.exit ( 1 );
		}
		return toReturn;

	}

	public static ContainerConfig loadContainerConfig (String gsnXMLpath)throws
	    FileNotFoundException, NoSuchAlgorithmException, NoSuchProviderException, IOException, KeyStoreException, CertificateException, SecurityException, SignatureException, InvalidKeyException, ClassNotFoundException {
		if (!new File(gsnXMLpath).isFile()) {
			logger.error("Couldn't find the gsn.xml file @: "+(new File(gsnXMLpath).getAbsolutePath()));
			System.exit(1);
		}		
		GsnConf gsn = GsnConf.load(gsnXMLpath);
		gsnConf = gsn;
		ContainerConfig conf=BeansInitializer.container(gsn);
		Class.forName(conf.getStorage().getJdbcDriver());
		conf.setContainerConfigurationFileName (  gsnXMLpath );
		return conf;
	}

	public static Properties getWrappers()  {
		if (singleton==null )
			return WrappersUtil.loadWrappers(new HashMap<String, Class<?>>());
		return Main.wrappers;
	}
    
	public  static Class < ? > getWrapperClass ( String id ) {
		try {
			String className =  getWrappers().getProperty(id);
			if (className ==null) {
				logger.error("The requested wrapper: "+id+" doesn't exist in the wrappers.properties file.");
				return null;
			}

			return Class.forName(className);
		} catch (ClassNotFoundException e) {
			logger.error(e.getMessage(),e);
		}
		return null;
	}

	/**
	 * Get's the GSN configuration without starting GSN.
	 * @return
	 * @throws Exception
	 */
	public static ContainerConfig getContainerConfig() {
		if (singleton == null)
			try {
				return loadContainerConfig(Main.gsnConfFolder + "/gsn.xml");
			} catch (Exception e) {
				return null;
			}
			else
				return singleton.containerConfig;
	}

	public Server getJettyServer(int port, int sslPort, int maxThreads) throws IOException {
		
        Server server = new Server();
		HandlerCollection handlers = new HandlerCollection();
        server.setThreadPool(new QueuedThreadPool(maxThreads));

        SslSocketConnector sslSocketConnector = null;
        if (sslPort > 0) {
            System.out.println("SSL is Starting on port "+sslPort+"...");
            SslContextFactory sslContextFactory = new SslContextFactory();
            sslContextFactory.addExcludeProtocols("SSLv3");
            sslContextFactory.setKeyStorePath(getContainerConfig().getSSLKeystore());
            sslContextFactory.setKeyStorePassword(getContainerConfig().getSSLKeyStorePassword());
            sslContextFactory.setKeyManagerPassword(getContainerConfig().getSSLKeyPassword());
            sslContextFactory.setTrustStore(getContainerConfig().getSSLKeystore());
            sslContextFactory.setTrustStorePassword(getContainerConfig().getSSLKeyStorePassword());
			sslSocketConnector = new SslSocketConnector(sslContextFactory);
            sslSocketConnector.setPort(getContainerConfig().getSSLPort());
        }
        else if (getContainerConfig().isAcEnabled())
            logger.error("SSL MUST be configured in the gsn.xml file when Access Control is enabled !");
        
        AbstractConnector connector=new SelectChannelConnector ();
        connector.setPort ( port );
        connector.setMaxIdleTime(30000);
        connector.setAcceptors(2);
        connector.setConfidentialPort(sslPort);

		if (sslSocketConnector==null)
			server.setConnectors ( new Connector [ ] { connector } );
		else
			server.setConnectors ( new Connector [ ] { connector,sslSocketConnector } );

		WebAppContext webAppContext = new WebAppContext();
		webAppContext.setContextPath("/");
		webAppContext.setResourceBase(webAppPath);
		
		handlers.addHandler(webAppContext);
		server.setHandler(handlers);

		Properties usernames = new Properties();
		usernames.load(new FileReader(gsnConfFolder + "/realm.properties"));
		if (!usernames.isEmpty()){
			HashLoginService loginService = new HashLoginService();
			loginService.setName("GSNRealm");
			loginService.setConfig(gsnConfFolder + "/realm.properties");
			loginService.setRefreshInterval(10000); //re-reads the file every 10 seconds.

			Constraint constraint = new Constraint();
			constraint.setName("GSN User");
			constraint.setRoles(new String[]{"gsnuser"});
			constraint.setAuthenticate(true);

			ConstraintMapping cm = new ConstraintMapping();
			cm.setConstraint(constraint);
			cm.setPathSpec("/*");
			cm.setMethod("GET");

			ConstraintMapping cm2 = new ConstraintMapping();
			cm2.setConstraint(constraint);
			cm2.setPathSpec("/*");
			cm2.setMethod("POST");

			ConstraintSecurityHandler securityHandler = new ConstraintSecurityHandler();
			securityHandler.setLoginService(loginService);
			securityHandler.setConstraintMappings(new ConstraintMapping[]{cm, cm2});
			securityHandler.setAuthenticator(new BasicAuthenticator());
			webAppContext.setSecurityHandler(securityHandler);
		}

		server.setSendServerVersion(true);
		server.setStopAtShutdown ( true );
		server.setSendServerVersion ( false );
		server.setSessionIdManager(new HashSessionIdManager(new Random()));

		return server;
	}

    public static StorageManager getValidationStorage() {
        return validationStorage;
    }

    public static StorageManager getStorage(VSensorConfig config) {
        StorageManager sm = storagesConfigs.get(config == null ? null : config);
        if  (sm != null)
            return sm;

        DBConnectionInfo dci = null;
        if (config == null || config.getStorage() == null || !config.getStorage().isDefined()) {
            sm = mainStorage;
        } else {
            if (config.getStorage().isIdentifierDefined()) {
                throw new IllegalArgumentException("Identifiers for storage is not supported yet.");
            } else {
                dci = new DBConnectionInfo(config.getStorage().getJdbcDriver(),
                        config.getStorage().getJdbcURL(),
                        config.getStorage().getJdbcUsername(),
                        config.getStorage().getJdbcPassword());
            }
            sm = storages.get(dci.hashCode());
            if (sm == null) {
                sm = StorageManagerFactory.getInstance(config.getStorage().getJdbcDriver(), config.getStorage().getJdbcUsername(), config.getStorage().getJdbcPassword(), config.getStorage().getJdbcURL(), DEFAULT_MAX_DB_CONNECTIONS);
                storages.put(dci.hashCode(), sm);
                storagesConfigs.put(config, sm);
            }
        }
        return sm;

    }

    public static StorageManager getStorage(String vsName) {
        return getStorage(Mappings.getVSensorConfig(vsName));
    }

    public static StorageManager getDefaultStorage() {
        return getStorage((VSensorConfig)null);
    }

    public static StorageManager getWindowStorage() {
        return windowStorage;
    }
    
    public static ZContext getZmqContext(){
    	return zmqContext;
    }
    
    public static ZeroMQProxy getZmqProxy(){
    	return zmqproxy;
    }
    public GsnConf getGsnConf(){
    	return gsnConf;
    }
    public Map<String,VsConf> getVsConf(){
    	return vsConf;
    }
    public ArrayList<Monitorable> getToMonitor(){
    	return toMonitor;
    }
    
    public static ThreadMXBean getThreadMXBean() {
        return threadBean;
    }

}


