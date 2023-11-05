package im.webuzz.nio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.sf.j2s.ajax.HttpRequest;

public class NioHttpRequest extends HttpRequest implements INioListener {
	
	private static Object keepAliveMutex = new Object();
	private static Map<String, List<NioHttpRequest>> keepAliveConns = new HashMap<String, List<NioHttpRequest>>();

	private static boolean ignoringLaterUserAgentHeader = false;	

	private HttpRequestItem request;
	private HttpResponseDecoder decoder;
	private NioConnector connector;

	private StringBuffer dataMutex;
	private Queue<Integer> callbacks;
	
	private boolean failed;
	private boolean closed;
	private boolean sent;
	private boolean usingAvailableProxy;
	private boolean supportsKeepAlive;
	
	private int maxRedirects;
	private IRedirectFilter redirectFilter;
	
	private long lastDataReceived;
	
	private String group;
	
	private Map<String, String> responseHeaders = new HashMap<String, String>();
	
	private byte[] contentBytes;
	
	public static boolean isIgnoringLaterUserAgentHeader() {
		return ignoringLaterUserAgentHeader;
	}

	public static void setIgnoringLaterUserAgentHeader(boolean ignoringLaterUserAgentHeader) {
		NioHttpRequest.ignoringLaterUserAgentHeader = ignoringLaterUserAgentHeader;
	}

	public NioHttpRequest() {
		super();
		usingAvailableProxy = true;
		supportsKeepAlive = true;
		maxRedirects = 10; //-1; // Do not support infinite redirect by default!
		lastDataReceived = 0;
		group = "http";
		callbacks = new ConcurrentLinkedQueue<Integer>();
	}

	public NioHttpRequest(boolean usingAvailableProxy) {
		super();
		this.usingAvailableProxy = usingAvailableProxy;
		supportsKeepAlive = true;
		maxRedirects = 10; //-1; // Do not support infinite redirect by default!
		lastDataReceived = 0;
		group = "http";
		callbacks = new ConcurrentLinkedQueue<Integer>();
	}

	public NioHttpRequest(boolean usingAvailableProxy, boolean supportsKeepAlive) {
		super();
		this.usingAvailableProxy = usingAvailableProxy;
		this.supportsKeepAlive = supportsKeepAlive;
		maxRedirects = 10; //-1; // Do not support infinite redirect by default!
		lastDataReceived = 0;
		group = "http";
		callbacks = new ConcurrentLinkedQueue<Integer>();
	}

	public NioHttpRequest(boolean usingAvailableProxy, boolean supportsKeepAlive, String nioGroup) {
		super();
		this.usingAvailableProxy = usingAvailableProxy;
		this.supportsKeepAlive = supportsKeepAlive;
		maxRedirects = 10; //-1; // Do not support infinite redirect by default!
		lastDataReceived = 0;
		group = nioGroup;
		callbacks = new ConcurrentLinkedQueue<Integer>();
	}

	/**
	 * Set maximum redirecting inside requests.
	 * 
	 * @param maxRedirects, < 0 : infinite redirecting; = 0 : no redirecting; > 0 : finite redirecting
	 */
	public void setMaxRedirects(int maxRedirects) {
		this.maxRedirects = maxRedirects;
	}

	/**
	 * User redirect filter to control redirecting.
	 * 
	 * @param filter
	 */
	public void registerOnRedirecting(IRedirectFilter filter) {
		this.redirectFilter = filter;
	}
	
	/**
	 * Return current HTTP requesting URL.
	 * If redirecting is supported, URL may be the target location.
	 * 
	 * @return last location
	 */
	public String getURL() {
		return this.url;
	}
	
	@Override
	protected boolean checkAbort() {
		if (!toAbort) return false;
		if (!failed && !closed) return false;
		return true;
	}
	
	/**
	 * Get all response headers.
	 * @return String the all response header value.
	 */
	public String getAllResponseHeaders() {
		StringBuilder builder = new StringBuilder();
		for (Map.Entry<String, String> entry : responseHeaders.entrySet()) {
			builder.append(entry.getKey());
			builder.append(": ");
			builder.append(entry.getValue());
			builder.append("\r\n");
		}
		return builder.toString();
	}
	/**
	 * Get response header with given key.
	 * @param key String header keyword. For more information please 
	 * read about HTTP protocol.
	 * @return String the response header value.
	 */
	public String getResponseHeader(String key) {
		if (responseHeaders != null) {
			return responseHeaders.get(key);
		}
		return null;
	}

	public void setCometConnection(boolean isCometConnection) {
		this.isCometConnection = isCometConnection;
	}

	@Override
	public void send(String str) {
		content = str;
		sendBytes(str == null ? null : str.getBytes());
	}
	public void sendBytes(byte[] bytes) {
		request = new HttpRequestItem(this.url);
		if (VerifyUtils.isValidIPv4Address(request.host)) {
			String hostStr = headers.get("Host");
			if (hostStr != null && hostStr.length() > 0) {
				request.host = hostStr;
			}
		}
		boolean loopRedirecting = false;
		do {
			loopRedirecting = false;
			failed = false;
			closed = false;
			sent = false;
			lastDataReceived = 0;
			//content = str;
			contentBytes = bytes;
			if (!asynchronous) {
				dataMutex = new StringBuffer();
			}
			
			String key = request.host + ":" + request.port;
			boolean ok2Reuse = false;
			NioHttpRequest r = null;
			if (supportsKeepAlive) {
				synchronized (keepAliveMutex) {
					List<NioHttpRequest> existedConns = keepAliveConns.get(key);
					while (existedConns != null && existedConns.size() > 0) {
						r = existedConns.remove(0);
						if (r != null && r.decoder != null && r.connector != null && !r.connector.isClosed()) {
							if (r.decoder.keepAliveMax > 0) {
								ok2Reuse = true;
								break;
							}
						}
					}
				}
			}
			if (ok2Reuse) {
				decoder = r.decoder;
				decoder.reset();
				decoder.setFullPacket(!isCometConnection);
				decoder.setPlainSimple(isDirectSocket);
				decoder.setKeepHeaders(true);
				connector = r.connector;
				connector.setProcessor(this);
				//System.err.println("Reuse: " + key + " for " + r.url + " to " + this.url);
				//System.err.println("!" + r.connector.domain + ":: " + r.connector.socket);
				connectionFinished(connector, true); // send out data
			} else {
				decoder = new HttpResponseDecoder();
				decoder.setFullPacket(!isCometConnection);
				decoder.setPlainSimple(isDirectSocket);
				decoder.setKeepHeaders(true);
				NioSelectorThread st = NioSelectorThread.getNioSelectorThread(group);
				try {
					connector = new NioConnector(st, request.host, request.port, request.usingSSL, usingAvailableProxy, decoder, this);
				} catch (Throwable e) {
					e.printStackTrace();
					failed = true;
				}
				if (failed || connector == null || connector.isClosed()) {
					if (readyState != 4) {
						readyState = 4;
						if (onreadystatechange != null) {
							onreadystatechange.onLoaded();
							onreadystatechange = null;
						}
					}
					readyState = 0;
					return;
				}
			}
			
			while (!asynchronous) {
				synchronized (dataMutex) {
					if (dataMutex.length() <= 0) {
						try {
							dataMutex.wait(30000);
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					}
				} // end of synchronized(mutex)
				while (!callbacks.isEmpty()) {
					Integer state = callbacks.remove();
					if (state != null) {
						int stateValue = state.intValue();
						if (stateValue == 1) {
							if (onreadystatechange != null) onreadystatechange.onOpen();
						} else if (stateValue == 2) {
							if (onreadystatechange != null) onreadystatechange.onSent();
						} else if (stateValue == 3) {
							if (onreadystatechange != null) onreadystatechange.onReceiving();
						} else if (stateValue == 4) {
							if (onreadystatechange != null) onreadystatechange.onLoaded();
							onreadystatechange = null;
							if (dataMutex.length() <= 0) {
								dataMutex.append('1');
							}
						}
					}
				}
				if (dataMutex.length() >= 1) {
					if (decoder != null && decoder.code != 0) {
						// Already got HTTP header
						if (decoder.code / 300 == 1 && dataMutex.length() > 1) {
							String url = dataMutex.toString();
							request = new HttpRequestItem(url);
							if (key.equalsIgnoreCase(request.host + ":" + request.port)) {
								open("GET", url, false, user, password);
							} else {
								open("GET", url, false);
							}
							dataMutex.delete(0, dataMutex.length());
							//str = null;
							loopRedirecting = true;
						} else  if (lastDataReceived > 0 && !decoder.isFullPacket()) {
							long lastRecieved = lastDataReceived;
							while (true) {
								System.out.println("Wait another 30s...");
								try {
									dataMutex.wait(30000); // wait another 30s
								} catch (InterruptedException e) {
									e.printStackTrace();
								}
								if (dataMutex.length() > 0) {
									break;
								}
								if (lastRecieved == lastDataReceived) {
									// no data for last 30s
									break;
								}
							}
						} // else no data
					}
					break;
				} // end if (dataMutex.length() >= 1)
			} // end of while (!asynchronized)
		} while (loopRedirecting);
	}

	protected byte[] generateHttpRequest(boolean reusing) {
		StringBuilder builder = new StringBuilder();
		builder.append(method);
		builder.append(" ");
		builder.append(request.relativeURL);
		if ("GET".equalsIgnoreCase(method) && content != null && content.length() > 0) {
			if (request.relativeURL.indexOf('?') == -1) {
				builder.append('?');
			}
			builder.append(content);
		}
		builder.append(" HTTP/1.1\r\n");
		
		builder.append("Host: ");
		builder.append(request.host);
		builder.append("\r\n");
		
		boolean uaInHeaders = headers.get("User-Agent") != null;
		if (!uaInHeaders && !(reusing && isIgnoringLaterUserAgentHeader())) {
			builder.append("User-Agent: ");
			builder.append(DEFAULT_USER_AGENT);
			builder.append("\r\n");
		}
		
		builder.append("Accept-Encoding: gzip\r\n");
		
		if ("POST".equalsIgnoreCase(method)) {
			String contentType = headers.get("Content-Type");
			if (contentType == null) {
				builder.append("Content-Type: application/x-www-form-urlencoded\r\n");
			} // else will be set later
		}
		
//		if (content != null && !"GET".equalsIgnoreCase(method)) {
//			builder.append("Content-Length: ");
//			builder.append(content.length());
//			builder.append("\r\n");
//		}
		if (contentBytes != null && !"GET".equalsIgnoreCase(method)) {
			builder.append("Content-Length: ");
			builder.append(contentBytes.length);
			builder.append("\r\n");
		}
		if (supportsKeepAlive) {
			builder.append("Connection: keep-alive\r\n");
		} else {
			builder.append("Connection: close\r\n");
		}
		if (user != null) {
			String auth = user + ":" + (password != null ? password : "");
			String base64Auth = HttpRequest.Base64.byteArrayToBase64(auth.getBytes());
			builder.append("Authorization: Basic ");
			builder.append(base64Auth);
			builder.append("\r\n");
		}
		for (Iterator<String> iter = headers.keySet().iterator(); iter.hasNext();) {
			String key = (String) iter.next();
			if (uaInHeaders && reusing && isIgnoringLaterUserAgentHeader()
					&& "User-Agent".equals(key)) {
				continue;
			}
			if (key.startsWith(":")) {
				continue;
			}
			builder.append(key);
			builder.append(": ");
			builder.append(headers.get(key));
			builder.append("\r\n");
		}
		builder.append("\r\n");
		byte[] headerBytes = builder.toString().getBytes(ISO_8859_1);
		if (contentBytes == null || "GET".equalsIgnoreCase(method)) {
			return headerBytes;
		}
		byte[] requestBytes = new byte[headerBytes.length + contentBytes.length];
		System.arraycopy(headerBytes, 0, requestBytes, 0, headerBytes.length);
		System.arraycopy(contentBytes, 0, requestBytes, headerBytes.length, contentBytes.length);
		return requestBytes;
	}

	@Override
	public void connectionClosedByRemote() {
		//System.err.println(": " + request.host + " / " + request.port + " -> " + this.url);
		
		if (!closed) {
			closed = true;
			if (readyState != 4) {
				readyState = 4;
				if (onreadystatechange != null) {
					if (asynchronous) {
						onreadystatechange.onLoaded();
						onreadystatechange = null;
					} else {
						callbacks.add(readyState);
					}
				}
			}
			closeRequest();
			readyState = 0;
			if (!asynchronous) {
				syncNotify();
			}
		}
	}

	@Override
	public void connectionFailed(NioConnector sessionMetadata) {
		failed = true;
		if (readyState != 4) {
			readyState = 4;
			if (onreadystatechange != null) {
				if (asynchronous) {
					onreadystatechange.onLoaded();
					onreadystatechange = null;
				} else {
					callbacks.add(readyState);
				}
			}
		}
		closeRequest();
		readyState = 0;
		if (!asynchronous) {
			syncNotify();
		}
	}

	@Override
	public void connectionFinished(NioConnector sessionMetadata) {
		connectionFinished(sessionMetadata, false);
	}
	
	private void connectionFinished(NioConnector sessionMetadata, boolean reusing) {
		if (request.usingSSL && !reusing) {
			return; // wait util handshake finished
		}
		sendRequest(sessionMetadata, reusing);
	}

	protected void sendRequest(NioConnector sessionMetadata, boolean reusing) {
		if (sent) {
			System.out.println("!!!!!!!!!!!!!!!!");
			System.out.println("Already sent!!!!");
			new RuntimeException("Request is being sent multiple times!").printStackTrace();
			return;
		}
		sent = true;

		readyState = 1;
		if (onreadystatechange != null) {
			if (asynchronous) {
				onreadystatechange.onOpen();
			} else {
				callbacks.add(readyState);
				syncNotifyOnly();
			}
		}
		byte[] requestData = contentBytes;
		if (!isDirectSocket) {
			requestData = generateHttpRequest(reusing);
		}
		try {
			receiving = initializeReceivingMonitor();
			sessionMetadata.send(requestData);
			readyState = 2;
			if (onreadystatechange != null) {
				if (asynchronous) {
					onreadystatechange.onSent();
				} else {
					callbacks.add(readyState);
					syncNotifyOnly();
				}
			}
		} catch (IOException e) {
			failed = true;
			e.printStackTrace();
			System.out.println(request.usingSSL + " " + request.host + " " + request.relativeURL);
		}
	}

	/*
	 * Return whether action is already performed or not. 
	 */
	private boolean checkRedirecting() {
		if (decoder != null && (decoder.code > 300 && decoder.code < 400) && decoder.location != null) {
			if (redirectFilter != null) {
				boolean ok2Redirect = false;
				try {
					ok2Redirect = redirectFilter.redirecting(decoder.location);
				} catch (Exception e) {
					e.printStackTrace();
				}
				if (!ok2Redirect) {
					finishHttpRequest();
					closed = true;
					return true;
				}
			}
			if (maxRedirects > 0) {
				maxRedirects--; //Do not support infinite redirect by default!
				lastDataReceived = 0;
				
				// cache location before closing request
				String location = decoder.location;
				String urlLC = location.toLowerCase();
				if (!urlLC.startsWith("http://") && !urlLC.startsWith("https://")) {
					location = (request.usingSSL ? "https://" : "http://") + request.host
							+ (request.port <= 0 ? "" : ((request.usingSSL && request.port == 443) || (!request.usingSSL && request.port == 80)) ? "" : (":" + request.port)) + location; 
				}
				String newCookieHeader = getResponseHeader("Set-Cookie");
				String localCookieHeaders = headers == null ? null : headers.get("Cookie");
				
				readyState = 0;
				closeRequest(true);
				
				if (newCookieHeader != null || localCookieHeaders != null) {
					// FIXME: Cookie is incorrect for cross domains
					StringBuilder builder = buildCookie(newCookieHeader, localCookieHeaders);
					if (builder.length() > 0) {
						setRequestHeader("Cookie", builder.toString());
					}
				}
				if (asynchronous) {
					open("GET", location, true);
					send(null);
				} else {
					synchronized (dataMutex) {
						dataMutex.append(location);
						dataMutex.notify();
					}
				}
				return true;
			}
		}
		return false;
	}

	private StringBuilder buildCookie(String newCookieHeader, String localCookieHeaders) {
		StringBuilder builder = new StringBuilder();
		Set<String> existedCookies = new HashSet<String>();
		Set<String> overrideCookies = new HashSet<String>();
		if (localCookieHeaders != null && localCookieHeaders.length() > 0) {
			String[] cookies = localCookieHeaders.split("; ");
			for (String cookie : cookies) {
				String[] kvs = cookie.split("=");
				if (kvs != null && kvs.length == 2) {
					existedCookies.add(kvs[0]);
				}
			}
		}
		if (newCookieHeader != null && newCookieHeader.length() > 0) {
			String[] newCookies = newCookieHeader.split("\r\n");
			for (int i = 0; i < newCookies.length; i++) {
				String cookie = newCookies[i];
				int idx = cookie.indexOf(";");
				if (idx > 0) {
					/*
					String domainKey = "domain=";
					int lastIdx = cookie.lastIndexOf(domainKey);
					if (lastIdx != -1) {
						String domain = cookie.substring(lastIdx + domainKey.length());
					}
					// */
					String cookieKV = cookie.substring(0, idx);
					String[] kvs = cookieKV.split("=");
					if (kvs != null && kvs.length == 2) {
						if (existedCookies.contains(kvs[0])) {
							overrideCookies.add(kvs[0]);
						}
					}
					if (builder.length() > 0) {
						builder.append("; ");
					}
					builder.append(cookieKV);
				}
			}
		}
		if (existedCookies.size() != overrideCookies.size()) {
			if (localCookieHeaders != null && localCookieHeaders.length() > 0) {
				String[] cookies = localCookieHeaders.split("; ");
				for (String cookie : cookies) {
					String[] kvs = cookie.split("=");
					if (kvs != null && kvs.length == 2) {
						if (!overrideCookies.contains(kvs[0])) {
							if (builder.length() > 0) {
								builder.append("; ");
							}
							builder.append(cookie);
						}
					}
				}
			}
		}
		return builder;
	}

	@Override
	public void packetReceived(SocketChannel channel, ByteBuffer pckt) {
		if (status == 0 && decoder != null && decoder.code != 0) {
			status = decoder.code;
		}
		if (pckt == null) {
			responseType = decoder.contentType;

			Map<String, String> httpHeader = decoder.getHeaders();
			if (httpHeader != null) {
				responseHeaders = new HashMap<String, String>(httpHeader);
			}
			if (checkRedirecting()) {
				return;
			}
			finishHttpRequest();
			closed = true;
			return;
		}
		
		if (readyState != 3) {
			readyState = 3;
			if (onreadystatechange != null) {
				if (asynchronous) {
					onreadystatechange.onReceiving();
				} else {
					callbacks.add(readyState);
					syncNotifyOnly();
				}
			}
		}

		byte[] buffer = pckt.array();
		int offset = pckt.arrayOffset();
		int remaining = pckt.remaining();
		
		if (remaining > 0) {
			lastDataReceived = System.currentTimeMillis();
			if (responseBAOS == null) {
				int bufferSize = 10240;
				if (decoder != null) {
					bufferSize = decoder.getContentLength();
					if (bufferSize <= 0) {
						bufferSize = 10240;
					} else if (bufferSize > 512 * 1024) {
						bufferSize = 512 * 1024; // buffer increases by 512k
					}
				}
				responseBAOS = new ByteArrayOutputStream(10240);
			}

			boolean received = false;
			if (receiving != null) {
				received = receiving.receiving(responseBAOS, buffer, offset, remaining);
			}
			if (!received) {
				responseBAOS.write(buffer, offset, remaining);
			}

			if (!decoder.isFullPacket()/* || isCometConnection*/) {
				return;
			}
		}
		
		responseType = decoder.contentType;

		Map<String, String> httpHeader = decoder.getHeaders();
		if (httpHeader != null) {
			responseHeaders = new HashMap<String, String>(httpHeader);
		}
		if (checkRedirecting()) {
			return;
		}
		finishHttpRequest();
		closed = true;
	}

	private void finishHttpRequest() {
		if (readyState != 4) {
			readyState = 4;
			if (onreadystatechange != null) {
				if (asynchronous) {
					onreadystatechange.onLoaded();
					onreadystatechange = null;
				} else {
					callbacks.add(readyState);
				}
			}
		}

		closeRequest();
		readyState = 0;
		if (!asynchronous) {
			syncNotify();
		}
	}

	@Override
	public void sslHandshakeFinished() {
		sendRequest(connector, false);
	}

	@Override
	public void sslHandshakeTimeout() {
		failed = true;
		if (readyState != 4) {
			readyState = 4;
			if (onreadystatechange != null) {
				if (asynchronous) {
					onreadystatechange.onLoaded();
					onreadystatechange = null;
				} else {
					callbacks.add(readyState);
				}
			}
		}
		closeRequest();
		readyState = 0;
		if (!asynchronous) {
			syncNotify();
		}
	}

	void syncNotify() {
		synchronized (dataMutex) {
			if (dataMutex.length() <= 0) {
				dataMutex.append('1');
				dataMutex.notify();
			}
		}
	}
	void syncNotifyOnly() {
		synchronized (dataMutex) {
			dataMutex.notify();
		}
	}

	void closeRequest() {
		closeRequest(false);
	}
	
	void closeRequest(boolean cloneRequest) {
		String key = request.host + ":" + request.port;
		this.responseHeaders = null;
		if (connector != null) {
			if (supportsKeepAlive && !connector.isClosed()
					&& decoder != null && decoder.keepAliveMax > 0) {
				NioHttpRequest r = this;
				if (cloneRequest) {
					r = new NioHttpRequest();
					r.connector = this.connector;
					if (r.connector != null) {
						r.connector.setProcessor(r);
					}
					r.decoder = this.decoder;
					r.request = this.request;
					r.dataMutex = new StringBuffer();
				} else {
					//r.resetData();
				}
				synchronized (keepAliveMutex) {
					List<NioHttpRequest> existedConns = keepAliveConns.get(key);
					if (existedConns != null) {
						existedConns.add(r);
					} else {
						existedConns = new LinkedList<NioHttpRequest>();
						existedConns.add(r);
						keepAliveConns.put(key, existedConns);
					}
				}
				return;
			}
			connector.close();
		}
		if (supportsKeepAlive) {
			synchronized (keepAliveMutex) {
				List<NioHttpRequest> existedConns = keepAliveConns.get(key);
				if (existedConns != null) {
					existedConns.remove(this);
				}
			}
		}
	}
	
	/*private*/ void resetData() {
		responseBytes = null;
		responseBAOS = null;
		responseText = null;
		//headers = null;
		content = null;
		contentBytes = null;
		onreadystatechange = null;
		receiving = null;
		url = null;
		method = null;
		user = null;
		password = null;
	}
}
