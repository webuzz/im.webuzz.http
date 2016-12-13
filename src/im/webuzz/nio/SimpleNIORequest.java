package im.webuzz.nio;

import net.sf.j2s.ajax.HttpRequest;
import net.sf.j2s.ajax.HttpRequest.IXHRReceiving;
import net.sf.j2s.ajax.SimplePipeRequest;
import net.sf.j2s.ajax.SimpleRPCRequest;

public class SimpleNIORequest {

	private static boolean initialized = false;
	
	/**
	 * Initialize with NIO HttpRequest and comet pipe mode.
	 */
	public static void initialize() {
		if (initialized) {
			return;
		}
		SimpleRPCRequest.switchToAJAXMode();
		SimplePipeRequest.switchToContinuumMode(true);
		SimpleRPCRequest.setHttpRequestFactory(new SimplePipeRequest.IHttpPipeRequestFactory() {
			
			@Override
			public HttpRequest createRequest() {
				return new NioHttpRequest(false, true);
			}

			@Override
			public HttpRequest createRequestWithMonitor(final IXHRReceiving monitor) {
				return new NioHttpRequest(false, true) {
					@Override
					protected IXHRReceiving initializeReceivingMonitor() {
						return monitor;
					}
				};
			}
			
		});
		initialized = true;
	}

}
