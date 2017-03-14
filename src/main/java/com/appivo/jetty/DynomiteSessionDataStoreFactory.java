package com.appivo.jetty;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.eclipse.jetty.server.session.AbstractSessionDataStoreFactory;
import org.eclipse.jetty.server.session.SessionDataStore;
import org.eclipse.jetty.server.session.SessionHandler;

import com.netflix.dyno.connectionpool.Host;
import com.netflix.dyno.connectionpool.HostSupplier;
import com.netflix.dyno.connectionpool.Host.Status;
import com.netflix.dyno.connectionpool.impl.ConnectionPoolConfigurationImpl;
import com.netflix.dyno.jedis.DynoJedisClient;

/**
 * Dynomite-sessions for Jetty 9.4 !
 * 
 * @author Johan Eriksson
 */
public class DynomiteSessionDataStoreFactory extends AbstractSessionDataStoreFactory {

    final static String DEFAULT_APPNAME = "JettySessions";
    
    private int port;
    private List<String> hosts;
    private String app = DEFAULT_APPNAME;
    private String cluster = DEFAULT_APPNAME;
    
    public List<String> getHost() {
        return hosts;
    }

    public void setHost(List<String> hosts) {
        this.hosts = hosts;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    @Override
    public SessionDataStore getSessionDataStore(SessionHandler sessionHandler) throws Exception {
	CustomHostSupplier hostSupplier = new CustomHostSupplier();
	
	ConnectionPoolConfigurationImpl config = new ConnectionPoolConfigurationImpl("AppivoConfig");

	for (String host : hosts) {
	    hostSupplier.addHost(host, port, "");
	}
	
	DynoJedisClient client = new DynoJedisClient.Builder()
	        .withApplicationName(app)
	        .withDynomiteClusterName(cluster)
	        .withHostSupplier(hostSupplier)
	        .withCPConfig(config)
	        .build();
	
	DynomiteSessionDataStore store = new DynomiteSessionDataStore(client);
	
	return store;
    }
    
    final static class CustomHostSupplier implements HostSupplier {
	    final List<Host> hosts = new ArrayList<Host>();

	    public void addHost(String hostname, int port, String rack) {
		Host host = new Host(hostname, port, rack, Status.Up);
		hosts.add(host);
	    }
	    
	    @Override
	    public Collection<Host> getHosts() {
		return hosts;
	    }	
    }
}