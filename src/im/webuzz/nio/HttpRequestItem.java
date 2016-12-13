package im.webuzz.nio;

public class HttpRequestItem {

	public String host;
	
	public int port;
	
	public String relativeURL;
	
	public boolean usingSSL;
	
	public HttpRequestItem(String url) {
		port = 80;
		usingSSL = false;
		relativeURL = "/";
		host = null;
		
		String protocol = null;
		if (url != null && (url.indexOf ("http://") == 0
				|| url.indexOf ("https://") == 0)) {
			int idx1 = url.indexOf ("//") + 2;
			int idx2 = url.indexOf ('/', 9);
			if (idx2 != -1) {
				host = url.substring (idx1, idx2); 
				relativeURL = url.substring(idx2);
			} else {
				host = url.substring (idx1); 
			}
			protocol = "http:"; // http: or https:
			int idx0 = url.indexOf ("://");
			if (idx0 != -1) {
				protocol = url.substring (0, idx0 + 1);
			}
			usingSSL = "https:".equalsIgnoreCase(protocol);
			
			int idx3 = host.indexOf (':'); // there is port number
			if (idx3 != -1) {
				try {
					port = Integer.parseInt (host.substring (idx3 + 1));
				} catch (NumberFormatException e) {
					port = usingSSL ? 443: 80;
				}
				host = host.substring (0, idx3);
			} else {
				port = usingSSL ? 443: 80;
			}
		}

	}
	
}
