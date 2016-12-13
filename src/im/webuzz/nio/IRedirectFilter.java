package im.webuzz.nio;

public interface IRedirectFilter {

	/**
	 * Check redirecting to see if location is permitted.
	 * 
	 * @param location
	 * @return true, it is ok to redirect, else terminate.
	 */
	public boolean redirecting(String location);
	
}
