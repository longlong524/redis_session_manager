package com.orangefunction.tomcat.redissessions;

import java.security.Principal;

import org.apache.catalina.Manager;
import org.apache.catalina.SessionListener;
import org.apache.catalina.session.StandardSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.io.IOException;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

/**
 * The redis session
 * @author xianglong
 * @created 2015年9月11日 下午3:02:13
 * @version 1.0
 */
public class RedisSession extends StandardSession {

	private static final Log log = LogFactory.getLog(RedisSession.class);

	protected static Boolean manualDirtyTrackingSupportEnabled = false;

	public static final String NOT_SERIALIZED = "___NOT_SERIALIZABLE_EXCEPTION___";

	public static void setManualDirtyTrackingSupportEnabled(Boolean enabled) {
		manualDirtyTrackingSupportEnabled = enabled;
	}

	protected static String manualDirtyTrackingAttributeKey = "__changed__";

	public static void setManualDirtyTrackingAttributeKey(String key) {
		manualDirtyTrackingAttributeKey = key;
	}

	/*
	 * protected HashMap<String, Object> changedAttributes;
	 */protected Boolean dirty;

	public RedisSession(Manager manager) {
		super(manager);
		resetDirtyTracking();

	}

	public RedisSession() {
		// TODO Auto-generated constructor stub
		super(null);
		resetDirtyTracking();
	}

	public Boolean isDirty() {
		return dirty /* || !changedAttributes.isEmpty() */;
	}

	/*
	 * public HashMap<String, Object> getChangedAttributes() { return
	 * changedAttributes; }
	 */

	public void resetDirtyTracking() {
		// changedAttributes = new HashMap<>();
		dirty = false;
	}

	@Override
	public void setAttribute(String key, Object value) {
		if (manualDirtyTrackingSupportEnabled
				&& manualDirtyTrackingAttributeKey.equals(key)) {
			dirty = true;
			return;
		}

		Object oldValue = getAttribute(key);
		super.setAttribute(key, value);

		if ((value != null || oldValue != null)
				&& (value == null && oldValue != null || oldValue == null
						&& value != null
						|| !value.getClass().isInstance(oldValue) || !value
							.equals(oldValue))) {
			if (this.manager instanceof RedisSessionManager
					&& ((RedisSessionManager) this.manager).getSaveOnChange()) {
				try {
					((RedisSessionManager) this.manager).save(this, true);
				} catch (IOException ex) {
					log.error("Error saving session on setAttribute (triggered by saveOnChange=true): "
							+ ex.getMessage());
				}
			} else {
				// changedAttributes.put(key, value);
				dirty = true;
			}
		}
	}

	public void setAttribute(String name, Object value, boolean notify) {
		super.setAttribute(name, value, notify);
	}

	@Override
	public void removeAttribute(String name) {
		super.removeAttribute(name);
		if (this.manager instanceof RedisSessionManager
				&& ((RedisSessionManager) this.manager).getSaveOnChange()) {
			try {
				((RedisSessionManager) this.manager).save(this, true);
			} catch (IOException ex) {
				log.error("Error saving session on setAttribute (triggered by saveOnChange=true): "
						+ ex.getMessage());
			}
		} else {
			dirty = true;
		}
	}

	@Override
	public void setId(String id) {
		// Specifically do not call super(): it's implementation does unexpected
		// things
		// like calling manager.remove(session.id) and manager.add(session).

		this.id = id;
	}

	@Override
	public void setPrincipal(Principal principal) {
		dirty = true;
		super.setPrincipal(principal);
	}

	public boolean exclude(String name) {
		return super.exclude(name);
	}

	public void removeAttributeInternal(String name, boolean notify) {
		super.removeAttributeInternal(name, notify);
	}

	public void setLastAccessedTime(long lastAccessedTime) {
		this.lastAccessedTime = lastAccessedTime;
	}

	public void setIsNew(boolean isNew) {
		this.isNew = isNew;
	}

	public void setIsValid(boolean isValid) {
		this.isValid = isValid;
	}

	public void setThisAccessedTime(long thisAccessedTime) {
		this.thisAccessedTime = thisAccessedTime;
	}

	public Map<String, Object> getAttrbutes() {
		return this.attributes;
	}

	public void setAttrbutes(Map<String, Object> attributes) {
		this.attributes = attributes;
	}

	public List<SessionListener> getListeners() {
		return listeners;
	}

	public void setListeners(ArrayList<SessionListener> listeners) {
		this.listeners = listeners;
	}

	public Map<String, Object> getNotes() {
		return notes;
	}

	public void setNotes(Map<String, Object> notes) {
		this.notes = notes;
	}

	@Override
	public void writeObjectData(java.io.ObjectOutputStream out)
			throws IOException {
		super.writeObjectData(out);
		out.writeLong(this.getCreationTime());
	}

	@Override
	public void readObjectData(java.io.ObjectInputStream in)
			throws IOException, ClassNotFoundException {
		super.readObjectData(in);
		this.setCreationTime(in.readLong());
	}

}
