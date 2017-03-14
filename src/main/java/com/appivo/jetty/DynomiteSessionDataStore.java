package com.appivo.jetty;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.server.session.AbstractSessionDataStore;
import org.eclipse.jetty.server.session.SessionData;

import com.netflix.dyno.jedis.DynoJedisClient;

/**
 * A Jetty 9.4 SessionDataStore implementation backed by Netflix Dynomite
 * 
 * @author Johan Eriksson
 */
public class DynomiteSessionDataStore extends AbstractSessionDataStore {

    final static String PREFIX = "session:";
    final static String ATTR_PREFIX = "-";
    final static String CHARSET = "UTF-8";
    
    final static String META_ID = "id";
    final static String META_CONTEXTPATH = "contextpath";
    final static String META_VHOST = "vhost";
    final static String META_ACCESSED = "accessed";
    final static String META_LASTACCESSED = "lastaccessed";
    final static String META_CREATETIME = "createtime";
    final static String META_LASTNODE = "lastnode";
    final static String META_EXPIRY = "expiry";
    final static String META_MAXINACTIVE = "maxinactive";

    private DynoJedisClient client;

    DynomiteSessionDataStore(DynoJedisClient client) {
	this.client = client;
    }

    @Override
    public boolean exists(String id) throws Exception {
	return client.exists(toKey(id));
    }

    @Override
    public boolean isPassivating() {
	return true;
    }

    @Override
    public boolean delete(String id) throws Exception {
	boolean ok = false;
	byte[] key = toKey(id);
	long cnt = client.del(key);
	if (cnt > 0) {
	    ok = true;
	}
	return ok;
    }

    @Override
    public SessionData load(String id) throws Exception {
	SessionData session = null;
	Map<byte[], byte[]> map = client.hgetAll(toKey(id));
	if (map != null) {
	    final AtomicReference<SessionData> reference = new AtomicReference<SessionData>();
	    final AtomicReference<Exception> exception = new AtomicReference<Exception>();

	    Runnable load = new Runnable() {
		public void run () {
		    try {
			SessionData sd = deserialize(map);
			reference.set(sd);
		    } catch (Exception e) {
			exception.set(e);
		    }
		}
	    };
	    
	    _context.run(load);

	    if (exception.get() != null) {
		throw exception.get();
	    }
	    session = reference.get();
	}
	return session;
    }

    @Override
    public Set<String> doGetExpired(Set<String> candidates) {
	long now = System.currentTimeMillis();
	Set<String> expired = new HashSet<String>();
	for (String candidate : candidates) {
	    try {
		byte[] key = toKey(candidate);
		byte[] exp = toKey(META_EXPIRY);
		List<byte[]> values = client.hmget(key, exp);
		if (values != null && !values.isEmpty()) {
		    long expiry = toLong(values.get(0));
		    if (expiry > 0 && expiry < now) {
			expired.add(candidate);
		    }
		} else {
		    expired.add(candidate);
		}
	    } catch (Exception e) {
		e.printStackTrace();
	    }
	}
	return expired;
    }

    private Map<byte[], byte[]> serialize(SessionData data) throws Exception {
	Map<byte[], byte[]> map = new HashMap<byte[], byte[]>();
	ByteArrayOutputStream baos = new ByteArrayOutputStream();
	ObjectOutputStream oos = new ObjectOutputStream(baos);

	map.put(toBytes(META_ID), toBytes(data.getId()));
	map.put(toBytes(META_CONTEXTPATH), toBytes(data.getContextPath()));
	map.put(toBytes(META_VHOST), toBytes(data.getVhost()));
	map.put(toBytes(META_ACCESSED), toBytes(data.getAccessed()));
	map.put(toBytes(META_ACCESSED), toBytes(data.getAccessed()));
	map.put(toBytes(META_LASTACCESSED), toBytes(data.getLastAccessed()));
	map.put(toBytes(META_CREATETIME), toBytes(data.getCreated()));
	map.put(toBytes(META_EXPIRY), toBytes(data.getExpiry()));
	map.put(toBytes(META_MAXINACTIVE), toBytes(data.getMaxInactiveMs()));
	map.put(toBytes(META_LASTNODE), toBytes(data.getLastNode()));

	for (Map.Entry<String, Object> attr : data.getAllAttributes().entrySet()) {
	    String name = attr.getKey();
	    Object value = attr.getValue();
	    map.put(toBytes(ATTR_PREFIX + name), toBytes(value, baos, oos));
	}

	return map;
    }

    private SessionData deserialize(Map<byte[], byte[]> map) throws Exception {
	String id = null;
	String vhost = null;
	String contextPath = null;
	long created = 0;
	long accessed = 0;
	long lastAccessed = 0;
	long maxInactive = 0;
	long expiry = 0;
	
	Map<String, Object> attrs = new HashMap<String, Object>();
	for (Map.Entry<byte[], byte[]> entry : map.entrySet()) {
	    String key = toString(entry.getKey());
	    if (key.startsWith(ATTR_PREFIX)) {
		key = key.substring(1);
		Object value = toObject(entry.getValue());
		attrs.put(key, value);
	    } else {
		key = key.substring(1);
		byte[] buf = entry.getValue();
		switch (key) {
		case META_ID:
		    id = toString(buf);
		    break;
		case META_VHOST:
		    vhost = toString(buf);
		    break;
		case META_CREATETIME:
		    created = toLong(buf);
		    break;
		case META_ACCESSED:
		    accessed = toLong(buf);
		    break;
		case META_LASTACCESSED:
		    lastAccessed = toLong(buf);
		    break;
		case META_EXPIRY:
		    expiry = toLong(buf);
		    break;
		case META_MAXINACTIVE:
		    maxInactive = toLong(buf);
		    break;
		case META_CONTEXTPATH:
		    contextPath = toString(buf);
		    break;
		}
	    }
	}

	SessionData data = newSessionData(id, created, accessed, lastAccessed, maxInactive);
	data.setVhost(vhost);
	data.setExpiry(expiry);
	data.setContextPath(contextPath);
	data.putAllAttributes(attrs);

	return data;
    }

    @Override
    public void doStore(String id, SessionData data, long lastSaveTime) throws Exception {
	byte[] key = toKey(id);
	Map<byte[], byte[]> map = serialize(data);
	client.hmset(key, map);
    }    

    private byte[] toBytes(Object value, ByteArrayOutputStream baos, ObjectOutputStream oos) throws Exception {
	oos.reset();
	baos.reset();
	oos.writeObject(value);
	oos.flush();
	byte[] data = baos.toByteArray();
	return data;
    }

    private Object toObject(byte[] data) throws Exception {
	ByteArrayInputStream bais = new ByteArrayInputStream(data);
	ObjectInputStream ois = new ObjectInputStream(bais);
	Object value = ois.readObject();
	return value;
    }

    private byte[] toKey(String id) throws Exception {
	String str = PREFIX + id;
	return toBytes(str);
    }

    private String toString(byte[] data) throws Exception {
	return new String(data, CHARSET);
    }

    private byte[] toBytes(String str) throws Exception {
	return str.getBytes(CHARSET);	
    }

    private byte[] toBytes(long value) {
	ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
	buffer.putLong(value);
	return buffer.array();
    }   

    private long toLong(byte[] bytes) {
	ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
	buffer.put(bytes);
	buffer.flip();//need flip 
	return buffer.getLong();
    }
}