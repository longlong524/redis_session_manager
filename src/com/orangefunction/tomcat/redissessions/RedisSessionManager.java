package com.orangefunction.tomcat.redissessions;

import java.io.IOException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.Session;
import org.apache.catalina.Valve;
import org.apache.catalina.session.ManagerBase;
import org.apache.catalina.util.LifecycleSupport;
import org.objenesis.strategy.InstantiatorStrategy;
import org.objenesis.strategy.StdInstantiatorStrategy;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisSentinelPool;
import redis.clients.jedis.Protocol;
import redis.clients.util.Pool;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Kryo.DefaultInstantiatorStrategy;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

/**
 * redis session manager, used to manage session
 * @author xianglong
 * @created 2015年9月11日 下午3:03:55
 * @version 1.0
 */
public class RedisSessionManager extends ManagerBase implements Lifecycle {

	private static final int MAXBUFFER_LENGTH = 1024 * 200;
	private static ThreadLocal<Kryo> kryos = new ThreadLocal<Kryo>() {
		protected Kryo initialValue() {
			try {
				Kryo kryo = new Kryo();
				// configure kryo instance, customize settings
				kryo.register(RedisSession.class, new RedisSessionSerializer());
				kryo.setInstantiatorStrategy(new DefaultInstantiatorStrategy(new StdInstantiatorStrategy()));
				return kryo;
			} catch (Throwable t) {
				t.printStackTrace();
			}
			return null;
		};
	};

	private static ThreadLocal<Output> kryos_buffer = new ThreadLocal<Output>() {
		protected Output initialValue() {
			return new Output(new byte[MAXBUFFER_LENGTH]);
		};
	};

	enum SessionPersistPolicy {
		DEFAULT, SAVE_ON_CHANGE, ALWAYS_SAVE_AFTER_REQUEST;

		static SessionPersistPolicy fromName(String name) {
			for (SessionPersistPolicy policy : SessionPersistPolicy.values()) {
				if (policy.name().equalsIgnoreCase(name)) {
					return policy;
				}
			}
			throw new IllegalArgumentException(
					"Invalid session persist policy [" + name
							+ "]. Must be one of "
							+ Arrays.asList(SessionPersistPolicy.values())
							+ ".");
		}
	}

	protected byte[] NULL_SESSION = "null".getBytes();

	protected String host = "localhost";
	protected int port = 6379;
	protected int database = 0;
	protected String password = null;
	protected int timeout = Protocol.DEFAULT_TIMEOUT;
	protected String sentinelMaster = null;
	Set<String> sentinelSet = null;

	protected Pool<Jedis> connectionPool;
	protected JedisPoolConfig connectionPoolConfig = new JedisPoolConfig();

	protected RedisSessionHandlerValve handlerValve;
	protected ThreadLocal<RedisSession> currentSession = new ThreadLocal<>();
	protected ThreadLocal<String> currentSessionId = new ThreadLocal<>();
	protected ThreadLocal<Boolean> currentSessionIsPersisted = new ThreadLocal<>();

	protected static String name = "RedisSessionManager";

	protected EnumSet<SessionPersistPolicy> sessionPersistPoliciesSet = EnumSet
			.of(SessionPersistPolicy.DEFAULT);

	/**
	 * The lifecycle event support for this component.
	 */
	protected LifecycleSupport lifecycle = new LifecycleSupport(this);

	public String getHost() {
		return host;
	}

	public void setHost(String host) {
		this.host = host;
	}

	public int getPort() {
		return port;
	}

	public void setPort(int port) {
		this.port = port;
	}

	public int getDatabase() {
		return database;
	}

	public void setDatabase(int database) {
		this.database = database;
	}

	public int getTimeout() {
		return timeout;
	}

	public void setTimeout(int timeout) {
		this.timeout = timeout;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public String getSessionPersistPolicies() {
		StringBuilder policies = new StringBuilder();
		for (Iterator<SessionPersistPolicy> iter = this.sessionPersistPoliciesSet
				.iterator(); iter.hasNext();) {
			SessionPersistPolicy policy = iter.next();
			policies.append(policy.name());
			if (iter.hasNext()) {
				policies.append(",");
			}
		}
		return policies.toString();
	}

	public void setSessionPersistPolicies(String sessionPersistPolicies) {
		String[] policyArray = sessionPersistPolicies.split(",");
		EnumSet<SessionPersistPolicy> policySet = EnumSet
				.of(SessionPersistPolicy.DEFAULT);
		for (String policyName : policyArray) {
			SessionPersistPolicy policy = SessionPersistPolicy
					.fromName(policyName);
			policySet.add(policy);
		}
		this.sessionPersistPoliciesSet = policySet;
	}

	public boolean getSaveOnChange() {
		return this.sessionPersistPoliciesSet
				.contains(SessionPersistPolicy.SAVE_ON_CHANGE);
	}

	public boolean getAlwaysSaveAfterRequest() {
		return this.sessionPersistPoliciesSet
				.contains(SessionPersistPolicy.ALWAYS_SAVE_AFTER_REQUEST);
	}

	public String getSentinels() {
		StringBuilder sentinels = new StringBuilder();
		for (Iterator<String> iter = this.sentinelSet.iterator(); iter
				.hasNext();) {
			sentinels.append(iter.next());
			if (iter.hasNext()) {
				sentinels.append(",");
			}
		}
		return sentinels.toString();
	}

	public void setSentinels(String sentinels) {
		if (null == sentinels) {
			sentinels = "";
		}

		String[] sentinelArray = sentinels.split(",");
		this.sentinelSet = new HashSet<String>(Arrays.asList(sentinelArray));
	}

	public Set<String> getSentinelSet() {
		return this.sentinelSet;
	}

	public String getSentinelMaster() {
		return this.sentinelMaster;
	}

	public void setSentinelMaster(String master) {
		this.sentinelMaster = master;
	}

	@Override
	public int getRejectedSessions() {
		// Essentially do nothing.
		return 0;
	}

	public void setRejectedSessions(int i) {
		// Do nothing.
	}

	protected Jedis acquireConnection() {
		Jedis jedis = connectionPool.getResource();

		if (getDatabase() != 0) {
			jedis.select(getDatabase());
		}

		return jedis;
	}

	protected void returnConnection(Jedis jedis, Boolean error) {
		if (error) {
			connectionPool.returnBrokenResource(jedis);
		} else {
			connectionPool.returnResource(jedis);
		}
	}

	protected void returnConnection(Jedis jedis) {
		returnConnection(jedis, false);
	}

	@Override
	public void load() throws ClassNotFoundException, IOException {

	}

	@Override
	public void unload() throws IOException {

	}

	/**
	 * Add a lifecycle event listener to this component.
	 *
	 * @param listener
	 *            The listener to add
	 */
	@Override
	public void addLifecycleListener(LifecycleListener listener) {
		lifecycle.addLifecycleListener(listener);
	}

	/**
	 * Get the lifecycle listeners associated with this lifecycle. If this
	 * Lifecycle has no listeners registered, a zero-length array is returned.
	 */
	@Override
	public LifecycleListener[] findLifecycleListeners() {
		return lifecycle.findLifecycleListeners();
	}

	/**
	 * Remove a lifecycle event listener from this component.
	 *
	 * @param listener
	 *            The listener to remove
	 */
	@Override
	public void removeLifecycleListener(LifecycleListener listener) {
		lifecycle.removeLifecycleListener(listener);
	}

	/**
	 * Start this component and implement the requirements of
	 * {@link org.apache.catalina.util.LifecycleBase#startInternal()}.
	 *
	 * @exception LifecycleException
	 *                if this component detects a fatal error that prevents this
	 *                component from being used
	 */
	@Override
	protected synchronized void startInternal() throws LifecycleException {
		super.startInternal();

		setState(LifecycleState.STARTING);

		Boolean attachedToValve = false;
		for (Valve valve : getContainer().getPipeline().getValves()) {
			if (valve instanceof RedisSessionHandlerValve) {
				this.handlerValve = (RedisSessionHandlerValve) valve;
				this.handlerValve.setRedisSessionManager(this);
				attachedToValve = true;
				break;
			}
		}

		if (!attachedToValve) {
			String error = "Unable to attach to session handling valve; sessions cannot be saved after the request without the valve starting properly.";
			throw new LifecycleException(error);
		}

		initializeDatabaseConnection();

		setDistributable(true);
	}

	/**
	 * Stop this component and implement the requirements of
	 * {@link org.apache.catalina.util.LifecycleBase#stopInternal()}.
	 *
	 * @exception LifecycleException
	 *                if this component detects a fatal error that prevents this
	 *                component from being used
	 */
	@Override
	protected synchronized void stopInternal() throws LifecycleException {

		setState(LifecycleState.STOPPING);

		try {
			connectionPool.destroy();
		} catch (Exception e) {
			// Do nothing.
		}

		// Require a new random number generator if we are restarted
		super.stopInternal();
	}

	@Override
	public Session createSession(String requestedSessionId) {
		RedisSession session = null;
		String jvmRoute = getJvmRoute();

		Boolean error = true;
		Jedis jedis = null;
		try {
			jedis = acquireConnection();

			// Ensure generation of a unique session identifier.
			if (null != requestedSessionId) {
				if (jedis.setnx(requestedSessionId.getBytes(), NULL_SESSION) == 0L) {
					return session;
				}
			} else {
				do {
					requestedSessionId = sessionIdWithJvmRoute(
							generateSessionId(), jvmRoute);
				} while (jedis.setnx(requestedSessionId.getBytes(),
						NULL_SESSION) == 0L); // 1 = key set; 0 = key already
												// existed
			}

			/*
			 * Even though the key is set in Redis, we are not going to flag the
			 * current thread as having had the session persisted since the
			 * session isn't actually serialized to Redis yet. This ensures that
			 * the save(session) at the end of the request will serialize the
			 * session into Redis with 'set' instead of 'setnx'.
			 */

			error = false;

			if (null != requestedSessionId) {
				session = (RedisSession) createEmptySession();
				session.setNew(true);
				session.setValid(true);
				session.setCreationTime(System.currentTimeMillis());
				session.setMaxInactiveInterval(getMaxInactiveInterval());
				session.setId(requestedSessionId);
				session.tellNew();
			}

			currentSession.set(session);
			currentSessionId.set(requestedSessionId);
			currentSessionIsPersisted.set(false);
			// currentSessionSerializationMetadata.set(new
			// SessionSerializationMetadata());

			if (null != session) {
				try {
					error = saveInternal(jedis, session, true);
				} catch (IOException ex) {
					ex.printStackTrace();
					currentSession.set(null);
					currentSessionId.set(null);
					session = null;
				}
			}
		} finally {
			if (jedis != null) {
				returnConnection(jedis, error);
			}
		}

		return session;
	}

	private String sessionIdWithJvmRoute(String sessionId, String jvmRoute) {
		if (jvmRoute != null) {
			String jvmRoutePrefix = '.' + jvmRoute;
			return sessionId.endsWith(jvmRoutePrefix) ? sessionId : sessionId
					+ jvmRoutePrefix;
		}
		return sessionId;
	}

	@Override
	public Session createEmptySession() {
		return new RedisSession(this);
	}

	@Override
	public void add(Session session) {
		try {
			save(session);
		} catch (IOException ex) {
			throw new RuntimeException(
					"Unable to add to session manager store.", ex);
		}
	}

	@Override
	public Session findSession(String id) throws IOException {
		RedisSession session = null;

		if (null == id) {
			currentSessionIsPersisted.set(false);
			currentSession.set(null);
			currentSessionId.set(null);
		} else if (id.equals(currentSessionId.get())) {
			session = currentSession.get();
		} else {
			byte[] data = loadSessionDataFromRedis(id);
			if (data != null) {
				session = sessionFromSerializedData(id, data);
				currentSession.set(session);
				currentSessionIsPersisted.set(true);
				currentSessionId.set(id);
			} else {
				currentSessionIsPersisted.set(false);
				currentSession.set(null);
				currentSessionId.set(null);
			}
		}

		return session;
	}

	public void clear() {
		Jedis jedis = null;
		Boolean error = true;
		try {
			jedis = acquireConnection();
			jedis.flushDB();
			error = false;
		} finally {
			if (jedis != null) {
				returnConnection(jedis, error);
			}
		}
	}

	public int getSize() throws IOException {
		Jedis jedis = null;
		Boolean error = true;
		try {
			jedis = acquireConnection();
			int size = jedis.dbSize().intValue();
			error = false;
			return size;
		} finally {
			if (jedis != null) {
				returnConnection(jedis, error);
			}
		}
	}

	public String[] keys() throws IOException {
		Jedis jedis = null;
		Boolean error = true;
		try {
			jedis = acquireConnection();
			Set<String> keySet = jedis.keys("*");
			error = false;
			return keySet.toArray(new String[keySet.size()]);
		} finally {
			if (jedis != null) {
				returnConnection(jedis, error);
			}
		}
	}

	public byte[] loadSessionDataFromRedis(String id) throws IOException {
		Jedis jedis = null;
		Boolean error = true;

		try {

			jedis = acquireConnection();
			byte[] data = jedis.get(id.getBytes());
			error = false;
			return data;
		} finally {
			if (jedis != null) {
				returnConnection(jedis, error);
			}
		}
	}

	public RedisSession sessionFromSerializedData(String id, byte[] data)
			throws IOException {
		if (Arrays.equals(NULL_SESSION, data)) {
			throw new IOException(
					"Serialized session data was equal to NULL_SESSION");
		}

		RedisSession session = null;

		session = (RedisSession) createEmptySession();

		Kryo k = kryos.get();
		session = k.readObject(new Input(data), RedisSession.class);

		session.setId(id);
		session.setNew(false);
		session.setMaxInactiveInterval(getMaxInactiveInterval());
		session.access();
		session.setValid(true);
		session.resetDirtyTracking();
		session.setManager(this);

		return session;
	}

	public void save(Session session) throws IOException {
		save(session, false);
	}

	public void save(Session session, boolean forceSave) throws IOException {
		Jedis jedis = null;
		Boolean error = true;

		try {
			jedis = acquireConnection();
			error = saveInternal(jedis, session, forceSave);
		} catch (IOException e) {
			throw e;
		} finally {
			if (jedis != null) {
				returnConnection(jedis, error);
			}
		}
	}

	@SuppressWarnings("finally")
	protected boolean saveInternal(Jedis jedis, Session session,
			boolean forceSave) throws IOException {
		Boolean error = true;

		try {

			RedisSession redisSession = (RedisSession) session;

			byte[] binaryId = redisSession.getId().getBytes();

			Boolean isCurrentSessionPersisted;
			// 为何需要判断sessionAttributesHash，不是只需要判断session的dirty等标志就可以了么
			if (forceSave
					|| redisSession.isDirty()
					|| null == (isCurrentSessionPersisted = this.currentSessionIsPersisted
							.get()) || !isCurrentSessionPersisted

			) {
				Kryo k = kryos.get();
				Output out = kryos_buffer.get();
				out.clear();
				k.writeObject(out, redisSession);
				out.flush();
				jedis.set(binaryId,
						Arrays.copyOfRange(out.getBuffer(), 0, out.position()));

				redisSession.resetDirtyTracking();
				currentSessionIsPersisted.set(true);
			}

			jedis.expire(binaryId, getMaxInactiveInterval());

			error = false;

			return error;
		} catch (Exception e) {
			throw e;
		} finally {
			return error;
		}
	}

	@Override
	public void remove(Session session) {
		remove(session, false);
	}

	@Override
	public void remove(Session session, boolean update) {
		Jedis jedis = null;
		Boolean error = true;

		try {
			jedis = acquireConnection();
			jedis.del(session.getId());
			error = false;
		} finally {
			if (jedis != null) {
				returnConnection(jedis, error);
			}
		}
	}

	public void afterRequest() {
		RedisSession redisSession = currentSession.get();
		if (redisSession != null) {
			try {
				if (redisSession.isValid()) {
					save(redisSession, getAlwaysSaveAfterRequest());
				} else {
					remove(redisSession);
				}
			} catch (Exception e) {
				e.printStackTrace();
			} finally {
				currentSession.remove();
				currentSessionId.remove();
				currentSessionIsPersisted.remove();
			}
		}
	}

	@Override
	public void processExpires() {
		// We are going to use Redis's ability to expire keys for session
		// expiration.

		// Do nothing.
	}

	private void initializeDatabaseConnection() throws LifecycleException {
		try {
			if (getSentinelMaster() != null) {
				Set<String> sentinelSet = getSentinelSet();
				if (sentinelSet != null && sentinelSet.size() > 0) {
					connectionPool = new JedisSentinelPool(getSentinelMaster(),
							sentinelSet, this.connectionPoolConfig,
							getTimeout(), getPassword());
				} else {
					throw new LifecycleException(
							"Error configuring Redis Sentinel connection pool: expected both `sentinelMaster` and `sentiels` to be configured");
				}
			} else {
				connectionPool = new JedisPool(this.connectionPoolConfig,
						getHost(), getPort(), getTimeout(), getPassword());
			}
		} catch (Exception e) {
			e.printStackTrace();
			throw new LifecycleException("Error connecting to Redis", e);
		}
	}

	/*
	 * private void initializeSerializer() throws ClassNotFoundException,
	 * IllegalAccessException, InstantiationException {
	 * log.info("Attempting to use serializer :" + serializationStrategyClass);
	 * serializer = (Serializer)
	 * Class.forName(serializationStrategyClass).newInstance();
	 * 
	 * Loader loader = null;
	 * 
	 * if (getContainer() != null) { loader = getContainer().getLoader(); }
	 * 
	 * ClassLoader classLoader = null;
	 * 
	 * if (loader != null) { classLoader = loader.getClassLoader(); }
	 * serializer.setClassLoader(classLoader); }
	 */

	// Connection Pool Config Accessors

	// - from org.apache.commons.pool2.impl.GenericObjectPoolConfig

	public int getConnectionPoolMaxTotal() {
		return this.connectionPoolConfig.getMaxTotal();
	}

	public void setConnectionPoolMaxTotal(int connectionPoolMaxTotal) {
		this.connectionPoolConfig.setMaxTotal(connectionPoolMaxTotal);
	}

	public int getConnectionPoolMaxIdle() {
		return this.connectionPoolConfig.getMaxIdle();
	}

	public void setConnectionPoolMaxIdle(int connectionPoolMaxIdle) {
		this.connectionPoolConfig.setMaxIdle(connectionPoolMaxIdle);
	}

	public int getConnectionPoolMinIdle() {
		return this.connectionPoolConfig.getMinIdle();
	}

	public void setConnectionPoolMinIdle(int connectionPoolMinIdle) {
		this.connectionPoolConfig.setMinIdle(connectionPoolMinIdle);
	}

	// - from org.apache.commons.pool2.impl.BaseObjectPoolConfig

	public boolean getLifo() {
		return this.connectionPoolConfig.getLifo();
	}

	public void setLifo(boolean lifo) {
		this.connectionPoolConfig.setLifo(lifo);
	}

	public long getMaxWaitMillis() {
		return this.connectionPoolConfig.getMaxWaitMillis();
	}

	public void setMaxWaitMillis(long maxWaitMillis) {
		this.connectionPoolConfig.setMaxWaitMillis(maxWaitMillis);
	}

	public long getMinEvictableIdleTimeMillis() {
		return this.connectionPoolConfig.getMinEvictableIdleTimeMillis();
	}

	public void setMinEvictableIdleTimeMillis(long minEvictableIdleTimeMillis) {
		this.connectionPoolConfig
				.setMinEvictableIdleTimeMillis(minEvictableIdleTimeMillis);
	}

	public long getSoftMinEvictableIdleTimeMillis() {
		return this.connectionPoolConfig.getSoftMinEvictableIdleTimeMillis();
	}

	public void setSoftMinEvictableIdleTimeMillis(
			long softMinEvictableIdleTimeMillis) {
		this.connectionPoolConfig
				.setSoftMinEvictableIdleTimeMillis(softMinEvictableIdleTimeMillis);
	}

	public int getNumTestsPerEvictionRun() {
		return this.connectionPoolConfig.getNumTestsPerEvictionRun();
	}

	public void setNumTestsPerEvictionRun(int numTestsPerEvictionRun) {
		this.connectionPoolConfig
				.setNumTestsPerEvictionRun(numTestsPerEvictionRun);
	}

	public boolean getTestOnCreate() {
		return this.connectionPoolConfig.getTestOnCreate();
	}

	public void setTestOnCreate(boolean testOnCreate) {
		this.connectionPoolConfig.setTestOnCreate(testOnCreate);
	}

	public boolean getTestOnBorrow() {
		return this.connectionPoolConfig.getTestOnBorrow();
	}

	public void setTestOnBorrow(boolean testOnBorrow) {
		this.connectionPoolConfig.setTestOnBorrow(testOnBorrow);
	}

	public boolean getTestOnReturn() {
		return this.connectionPoolConfig.getTestOnReturn();
	}

	public void setTestOnReturn(boolean testOnReturn) {
		this.connectionPoolConfig.setTestOnReturn(testOnReturn);
	}

	public boolean getTestWhileIdle() {
		return this.connectionPoolConfig.getTestWhileIdle();
	}

	public void setTestWhileIdle(boolean testWhileIdle) {
		this.connectionPoolConfig.setTestWhileIdle(testWhileIdle);
	}

	public long getTimeBetweenEvictionRunsMillis() {
		return this.connectionPoolConfig.getTimeBetweenEvictionRunsMillis();
	}

	public void setTimeBetweenEvictionRunsMillis(
			long timeBetweenEvictionRunsMillis) {
		this.connectionPoolConfig
				.setTimeBetweenEvictionRunsMillis(timeBetweenEvictionRunsMillis);
	}

	public String getEvictionPolicyClassName() {
		return this.connectionPoolConfig.getEvictionPolicyClassName();
	}

	public void setEvictionPolicyClassName(String evictionPolicyClassName) {
		this.connectionPoolConfig
				.setEvictionPolicyClassName(evictionPolicyClassName);
	}

	public boolean getBlockWhenExhausted() {
		return this.connectionPoolConfig.getBlockWhenExhausted();
	}

	public void setBlockWhenExhausted(boolean blockWhenExhausted) {
		this.connectionPoolConfig.setBlockWhenExhausted(blockWhenExhausted);
	}

	public boolean getJmxEnabled() {
		return this.connectionPoolConfig.getJmxEnabled();
	}

	public void setJmxEnabled(boolean jmxEnabled) {
		this.connectionPoolConfig.setJmxEnabled(jmxEnabled);
	}

	public String getJmxNameBase() {
		return this.connectionPoolConfig.getJmxNameBase();
	}

	public void setJmxNameBase(String jmxNameBase) {
		this.connectionPoolConfig.setJmxNameBase(jmxNameBase);
	}

	public String getJmxNamePrefix() {
		return this.connectionPoolConfig.getJmxNamePrefix();
	}

	public void setJmxNamePrefix(String jmxNamePrefix) {
		this.connectionPoolConfig.setJmxNamePrefix(jmxNamePrefix);
	}
}

class DeserializedSessionContainer {
	public final RedisSession session;

	public DeserializedSessionContainer(RedisSession session) {
		this.session = session;
	}
}
