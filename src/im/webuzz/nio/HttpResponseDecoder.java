package im.webuzz.nio;


import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import net.sf.j2s.ajax.SimpleSerializable;

// TODO: Support chunked gzip encoding stream. Maybe we need to use jzlib
public class HttpResponseDecoder implements ProtocolDecoder {

	private static final class OpenByteArrayOutputStream extends
			ByteArrayOutputStream {
		public byte[] getByteArray() {
			return buf;
		}
	}

	private static byte[] dummy = new byte[0];
	
	public SocketChannel socket;
	public long keepAliveTimeout; // <= 0: do not keepalive
	public long keepAliveMax; // <= 0: do not keepalive
	public boolean v11; // HTTP/1.1 or HTTP/1.0
	public long created;
	public int code; 
	//public String status;
	public int requestCount;
	public boolean gzipped;
	public boolean chunking;
	public String contentType;
	private int contentLength;
	public String cookies;
	public String location;
	
	public boolean fullRequest = false;
	
	private int dataLength;
	private byte[] pending;
	private boolean firstLine = true;
	private boolean header = true;

	//public boolean closed;
	private boolean gotContentLength;
	private int readContentLength; // already read content length
	private boolean chunkedSizeRead;
	private int chunkedSize;
	private OpenByteArrayOutputStream baos;

	private boolean fullPacket;
	private int dataSent = -1;
	
	private boolean plainSimple;
	
	private boolean keepRawData;
	private ByteArrayOutputStream rawData;
	
	private boolean keepHeaders;
	private Map<String, String> headers;

	public byte[] getRawData() {
		if (rawData == null) {
			return null;
		}
		byte[] byteArray = null;
		synchronized (this) {
			byteArray = rawData.toByteArray();
			rawData.reset();
		}
		if (byteArray.length == 0) {
			return null;
		}
		return byteArray;
	}
	
	public void setKeepRawData(boolean keep) {
		this.keepRawData = keep;
	}

	public Map<String, String> getHeaders() {
		return headers;
	}
	
	public void setKeepHeaders(boolean keep) {
		this.keepHeaders = keep;
	}
	
	public boolean isFullPacket() {
		return fullPacket;
	}
	
	public boolean isPlainSimple() {
		return plainSimple;
	}

	public void setPlainSimple(boolean plainSimple) {
		this.plainSimple = plainSimple;
	}

	public void setFullPacket(boolean fullPacket) {
		this.fullPacket = fullPacket;
	}

	public String getContentType() {
		return contentType;
	}

	public void setContentType(String contentType) {
		this.contentType = contentType;
	}

	public int getContentLength() {
		return contentLength;
	}

	public void setContentLength(int contentLength) {
		this.contentLength = contentLength;
	}

	public void reset(){
		keepAliveMax = 0;
		keepAliveTimeout = 0;
		v11 = false;
		created = 0;
		code = 0;
		//status = null;
		dataLength = 0;
		pending = null;
		firstLine = true;
		header = true;
		contentLength = 0;
		fullRequest = false;
		plainSimple = false;
		dataSent = -1;
		gzipped = false;
		chunking = false;
		contentType = null;
		cookies = null;
		//closed = false;
		gotContentLength = false;
		readContentLength = 0;
		chunkedSizeRead = false;
		chunkedSize = 0;
		baos = null;
		
		location = null;
		
		keepRawData = false;
		if (rawData != null) {
			rawData.reset();
		}
		keepHeaders = false;
		if (headers != null) {
			headers.clear();
		}
	}
	
	public ByteBuffer decode(ByteBuffer bb) {
		if (plainSimple) {
			if (!fullPacket) {
				if (bb == null) {
					if (dataSent > 0) {
						return null;
					}
					return ByteBuffer.wrap(dummy);
				}
				int availableDataLength = bb.remaining();
				byte[] data = new byte[availableDataLength];
				bb.get(data);
				dataSent = 1;
				return ByteBuffer.wrap(data);
			}
			
			// Require full packet for plain simple format
			if (bb == null) {
				if (baos != null) {
					int baosSize = baos.size();
					ByteBuffer response = baosSize == 0 ? ByteBuffer.wrap(dummy) : ungzip(baos.getByteArray(), 0, baosSize);
					baos = null;
					dataSent = 1;
					return response;
				}
				if (dataSent >= 0 && fullPacket) {
					return null; // already sent data packet or dummy packet
				}
			}
			if (baos == null) {
				baos = new OpenByteArrayOutputStream();
			}
			baos.write(bb.array(), bb.arrayOffset(), bb.remaining());
			if (SimpleSerializable.parseInstance(baos.getByteArray()) == null) {
				return null;
			}
			fullPacket = true;
			ByteBuffer response = ByteBuffer.wrap(baos.getByteArray(), 0, baos.size());
			baos = null;
			dataSent = 1;
			return response;
		} // end of plain simple branch
		
		if (bb == null) {
			//closed = true;
			if (fullPacket && baos != null && !gzipped) {
				ByteBuffer response = ByteBuffer.wrap(baos.getByteArray(), 0, baos.size());
				baos = null;
				dataSent = 1;
				return response;
			}
			if (gzipped && baos != null) {
				int baosSize = baos.size();
				ByteBuffer response = baosSize == 0 ? ByteBuffer.wrap(dummy) : ungzip(baos.getByteArray(), 0, baosSize);
				baos = null;
				dataSent = 1;
				return response;
			}
			if (dataSent >= 0 && fullPacket) {
				return null; // already sent data packet or dummy packet
			}
			dataSent = 0;
			return ByteBuffer.wrap(dummy);
		}
		int availableDataLength = bb.remaining();
		byte[] data = new byte[availableDataLength];
		bb.get(data);
		if (keepRawData && data != null) {
			if (rawData == null) {
				synchronized (this) {
					if (rawData == null) {
						rawData = new ByteArrayOutputStream(data.length);
					}
				}
			}
			try {
				synchronized (this) {
					rawData.write(data);
				}
			} catch (IOException e) {
				//e.printStackTrace();
			}
		}
//		int count = 0;
//		while (bb.hasRemaining()) {
//			data[count] = bb.get();
//			count++;
//		}
		int idx = -1;
		int lineBegin = 0;
		int lastSlash = -1;
		int firstColon = -1;
		int firstSpace = -1;
		int secondSpace = -1;
		if (pending != null) {
			availableDataLength += dataLength;
			if (availableDataLength < pending.length) {
				System.arraycopy(data, 0, pending, dataLength, data.length);
				data = pending;
			} else {
				byte[] newData = new byte[availableDataLength];
				System.arraycopy(pending, 0, newData, 0, dataLength);
				System.arraycopy(data, 0, newData, dataLength, data.length);
				data = newData;
			}
		}
		ByteBuffer responseContent = null;
		while (idx < availableDataLength - 1) {
			idx++;
			byte b = data[idx];
			if (firstLine) {
				if (idx > 10240) {
					dataSent = 0;
					return ByteBuffer.wrap(dummy); // url too long
				}
				if (b == '/') {
					lastSlash = idx;
				} else if (b == ' ') {
					if (firstSpace == -1) {
						firstSpace = idx;
					} else if (secondSpace == -1) {
						secondSpace = idx;
					}
				} else if (b == '\n') {
					if (lastSlash == -1 || firstSpace == -1 || secondSpace == -1) {
						dataSent = 0;
						return ByteBuffer.wrap(dummy); // BAD
					}
					v11 = false;
					if (data[lastSlash + 1] == '1' && data[lastSlash + 2] == '.' && data[lastSlash + 3] == '1') {
						v11 = true;
						// HTTP 1.1 supports keep alive by default
						int maxRequests = 300;
						int timeout = 30;
						if (keepAliveTimeout <= 0) {
							keepAliveTimeout = timeout; // seconds
						}
						if (keepAliveMax <= 0) {
							keepAliveMax = maxRequests;
						}
					}
					String returnCodeStr = new String(data, firstSpace + 1, secondSpace - firstSpace - 1);
					try {
						code = Integer.parseInt(returnCodeStr);
					} catch (NumberFormatException e) {
						e.printStackTrace();
					}
					//status = new String(data, secondSpace + 1, idx - secondSpace - 2);
					firstLine = false;
					lineBegin = idx + 1;
					firstSpace = -1;
				}
			} else if (header) { // header
				if (b == ':') {
					if (firstColon == -1) {
						firstColon = idx;
					}
				} else if (b == ' ') {
					if (firstSpace == -1) {
						if (firstColon + 1 == idx) {
							firstSpace = idx;
						}
					} else if (firstSpace + 1 == idx) {
						firstSpace++;
					}
				} else if (b == '\n') {
					if (idx - lineBegin <= 1) {
						lineBegin = idx + 1;
						header = false;
						if (!gotContentLength && !chunking && lineBegin >= availableDataLength) { // 304, ...
							gotContentLength = true;
							contentLength = 0;
							fullRequest = true;
						}
					} else {
//						if (!v11) {
//							continue; // We are not interested in any header value for HTTP/1.0 ?
//						}
						if (firstColon == -1) {
							dataSent = 0;
							return ByteBuffer.wrap(dummy);
						}
						String headerName = new String(data, lineBegin, firstColon - lineBegin);
						int offset = (firstSpace != -1 ? firstSpace : firstColon) + 1;
						int valueLength = idx - 1 - offset;
						if (data[idx - 1] != '\r') { // HTTP line break must be \r\n. Here is tolerance provision: http://www.w3.org/Protocols/rfc2616/rfc2616-sec19.html#sec19.3 
							valueLength++;
						}
						if (keepHeaders && headers == null) {
							headers = new HashMap<String, String>(10);
						}
						//System.out.println(headerName + ":" + new String(data, offset, valueLength));
						if ("Content-Encoding".equalsIgnoreCase(headerName)) {
							String encoding = new String(data, offset, valueLength);
							if (encoding.indexOf("gzip") != -1) {
								gzipped = true;
							}
							if (keepHeaders) headers.put(headerName, encoding);
						} else if ("Transfer-Encoding".equalsIgnoreCase(headerName)) {
							String headerValue = new String(data, offset, valueLength);
							chunking = "chunked".equalsIgnoreCase(headerValue);
							if (keepHeaders) headers.put(headerName, headerValue);
						} else if ("Content-Length".equalsIgnoreCase(headerName)) {
							String headerValue = new String(data, offset, valueLength);
							if (keepHeaders) headers.put(headerName, headerValue);
							try {
								contentLength = Integer.parseInt(headerValue);
							} catch (NumberFormatException e) {
								dataSent = 0;
								return ByteBuffer.wrap(dummy); // Bad
							}
							readContentLength = 0;
							gotContentLength = true;
						} else if ("Content-Type".equalsIgnoreCase(headerName)) {
							contentType = new String(data, offset, valueLength);
							if (keepHeaders) headers.put(headerName, contentType);
						} else if ("Set-Cookie".equalsIgnoreCase(headerName)) {
							String cookie = new String(data, offset, valueLength);
							if (cookies == null) {
								cookies = cookie;
							} else {
								cookies = cookies + "\r\n" + cookie;
							}
							if (keepHeaders) headers.put(headerName, cookies);
						} else if ("Location".equalsIgnoreCase(headerName)) {
							location = new String(data, offset, valueLength);
							if (keepHeaders) headers.put(headerName, location);
						} else {
							int maxRequests = 300;
							int timeout = 30;
							if ("Keep-Alive".equalsIgnoreCase(headerName)) {
								String headerValue = new String(data, offset, valueLength);
								if (keepHeaders) headers.put(headerName, headerValue);
								if ("closed".equalsIgnoreCase(headerValue)) {
									keepAliveMax = 0;
									keepAliveTimeout = 0;
								} else {
									String[] split = headerValue.split(", ");
									if (split.length >= 2) {
										for (int i = 0; i < split.length; i++) {
											String p = split[i];
											int index = p.indexOf('=');
											if (index != -1) {
												String name = p.substring(0, index);
												String value = p.substring(index + 1);
												if ("timeout".equalsIgnoreCase(name)) {
													try {
														keepAliveTimeout = Integer.parseInt(value);
													} catch (NumberFormatException e) {
														keepAliveTimeout = timeout;
													}
													if (keepAliveTimeout > timeout) {
														keepAliveTimeout = timeout;
													}
												} else {
													try {
														keepAliveMax = Integer.parseInt(value);
													} catch (NumberFormatException e) {
														keepAliveMax = maxRequests;
													}
													if (keepAliveMax > maxRequests) {
														keepAliveMax = maxRequests;
													}
												}
											}
										}
									} else {
										keepAliveTimeout = timeout;
										try {
											keepAliveMax = Integer.parseInt(headerValue);
										} catch (NumberFormatException e) {
											keepAliveMax = maxRequests;
										}
										if (keepAliveMax > maxRequests) {
											keepAliveMax = maxRequests;
										}
									}
								}
							} else if ("Connection".equalsIgnoreCase(headerName)) {
								String headerValue = new String(data, offset, valueLength);
								if (keepHeaders) headers.put(headerName, headerValue);
								if ("close".equalsIgnoreCase(headerValue)) {
									keepAliveMax = 0;
									keepAliveTimeout = 0;
								} else if ("keep-alive".equalsIgnoreCase(headerValue)) {
									if (keepAliveTimeout <= 0) {
										keepAliveTimeout = timeout; // seconds
									}
									if (keepAliveMax <= 0) {
										keepAliveMax = maxRequests;
									}
								}
							} else {
								if (keepHeaders) {
									String headerValue = new String(data, offset, valueLength);
									headers.put(headerName, headerValue);
								}
							}
						}
						firstColon = -1;
						firstSpace = -1;
						lineBegin = idx + 1;
					}
				}
			} else { // content
				if (chunking) { // chunk-encoded HTTP connection
					if (!chunkedSizeRead) {
						if (b == '\n') {
							//if (idx - lineBegin - 1 < 0) {
							//	System.out.println("Error");
							//}
							String sizeStr = new String(data, lineBegin, idx - lineBegin - 1);
							int size = 0;
							try {
								size = Integer.parseInt(sizeStr, 16);
							} catch (NumberFormatException e) {
								e.printStackTrace();
							}
							lineBegin = idx + 1;
							chunkedSize = size;
							chunkedSizeRead = true;
						} else {
							continue; // read until got a '\n'
						}
					}
					if (availableDataLength - lineBegin >= chunkedSize + 2) { // \n\n
						//... response chunk is completed.
						if (chunkedSize == 0) {
							fullRequest = true;
							break; // for while loop
						}
						if (baos == null) {
							baos = new OpenByteArrayOutputStream();
						}
						baos.write(data, lineBegin, chunkedSize);
						lineBegin += chunkedSize + 2;
						idx = lineBegin; // leap forward
						chunkedSizeRead = false;
						chunkedSize = 0;
						continue; // read next chunk encoded block of data...
					}
				} else if (!gotContentLength) { // normal HTTP connection without knowing content length
					int leftContentLength = availableDataLength - lineBegin; // should > 0
					if (gzipped || fullPacket) {
						if (baos == null) {
							baos = new OpenByteArrayOutputStream();
						}
						baos.write(data, lineBegin, leftContentLength);
					} else {
						byte[] responseData = new byte[leftContentLength];
						System.arraycopy(data, lineBegin, responseData, 0, leftContentLength);
						responseContent = ByteBuffer.wrap(responseData);
					}
					lineBegin += leftContentLength;
				} else { // normal HTTP connection, knowing content length ahead
					if (contentLength == 0) {
						//dataSent = 0;
						//responseContent = ByteBuffer.wrap(dummy);
						fullRequest = true;
						break; // out of while loop
						// leave at least 1 character! Response for next request?
					}
					int expectedLength = contentLength - (baos == null ? 0 : baos.size());
					int existedLength = availableDataLength - lineBegin;
					int leftContentLength = Math.min(expectedLength, existedLength);
					readContentLength += leftContentLength;
					if (gzipped || fullPacket) {
						byte[] responseData = null;
						if (leftContentLength == contentLength) { // baos == null
							//... response is completed. copy data to responseData
							responseData = new byte[contentLength];
							System.arraycopy(data, lineBegin, responseData, 0, contentLength);
						} else {
							if (baos == null) {
								baos = new OpenByteArrayOutputStream();
							}
							baos.write(data, lineBegin, leftContentLength);
							if (existedLength >= expectedLength) {
								responseData = baos.toByteArray();
								baos = null;
							}
						}
						if (responseData != null) {
							if (gzipped) {
								try {
									responseContent = ungzip(responseData, 0, contentLength);
								} catch (Exception e) {
									e.printStackTrace();
								}
							} else {
								responseContent = ByteBuffer.wrap(responseData);
							}
							fullRequest = true;
						}
					} else {
						byte[] responseData = new byte[leftContentLength];
						System.arraycopy(data, lineBegin, responseData, 0, leftContentLength);
						responseContent = ByteBuffer.wrap(responseData);
						if (existedLength >= expectedLength || readContentLength >= contentLength) {
							fullRequest = true;
						}
					}
					lineBegin += leftContentLength;
				} // end of 3 different connections: chunked, without length and with length
				break; // out of while loop
			} // end of if content part
		} // end of while loop

		int length = availableDataLength - lineBegin;
		if (length > 0) {
			if (pending == null || (lineBegin > 0 && length <= data.length / 2
					&& data.length > 4096)) {
				pending = new byte[length];
			} else {
				pending = data;
			}
			System.arraycopy(data, lineBegin, pending, 0, length);
			dataLength = length;
		} else {
			pending = null;
			dataLength = 0;
		}
		
		if (!firstLine && !header && !chunking
				&& gotContentLength && contentLength == 0) {
			dataSent = 0;
			return ByteBuffer.wrap(dummy); // OK
		}

		if (!firstLine && !header && chunking) {
			if (chunkedSizeRead && chunkedSize == 0) {
				if (baos != null && baos.size() > 0) {
					byte[] responseData = baos.toByteArray();
					baos = null;
					if (gzipped) {
						responseContent = ungzip(responseData, 0, responseData.length);
					} else {
						responseContent = ByteBuffer.wrap(responseData);
					}
				} else {
					return decode(null);
				}
			} else if (gzipped || fullPacket) {
				//int existedLength = (baos != null ? baos.size() : 0);
				//if (gotContentLength && existedLength == contentLength) {
				//}
			} else {
				if (baos != null && baos.size() > 0) {
					responseContent = ByteBuffer.wrap(baos.toByteArray());
					baos = null;
				}
			}
		}
		if (responseContent != null) {
			dataSent = 1;
			return responseContent;
		}
		return null; // Continue reading...
	}

	public static ByteBuffer ungzip(byte[] responseData, int offset, int length) {
		OpenByteArrayOutputStream baos = new OpenByteArrayOutputStream();
		ByteArrayInputStream bais = new ByteArrayInputStream(responseData, offset, length);
		GZIPInputStream gis = null;
		boolean error = false;
		try {
			gis = new GZIPInputStream(bais);
			byte[] buffer = new byte[8096];
			int read = -1;
			while ((read = gis.read(buffer)) > 0) {
				baos.write(buffer, 0, read);
			}
		} catch (Throwable e) {
			e.printStackTrace();
			error = true;
		} finally {
			if (gis != null) {
				try {
					gis.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
		ByteBuffer bb = error ? ByteBuffer.wrap(dummy) : ByteBuffer.wrap(baos.getByteArray(), 0, baos.size());
		try {
			baos.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return bb;
	}

}
