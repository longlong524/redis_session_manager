package com.orangefunction.tomcat.redissessions;

import java.io.IOException;

import javax.servlet.ServletException;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;

/**
 * The redis session RedisSessionHandlerValve, used to decide wether to serialize the session 
 * @author xianglong
 * @created 2015年9月11日 下午3:02:13
 * @version 1.0
 */
public class RedisSessionHandlerValve extends ValveBase {
	private RedisSessionManager manager;

	public void setRedisSessionManager(RedisSessionManager manager) {
		this.manager = manager;
	}

	@Override
	public void invoke(Request request, Response response) throws IOException,
			ServletException {
		try {
			getNext().invoke(request, response);
		} finally {
			manager.afterRequest();
		}
	}
}
