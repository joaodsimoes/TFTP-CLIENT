package tftp;

public class Stats {

	private static final int INIT_TIMEOUT = 500;

	private static int totalDataBlocks = 0;
	private static int totalAcks = 0;
	private static int totalBytes = 0;
	private static long startTime = 0;


	Stats() {
		startTime = System.currentTimeMillis();
	}

	// any other methods

	
	void printReport() {
		// compute time spent sending data blocks
		int milliSeconds = (int) (System.currentTimeMillis() - startTime);
		float speed = (float) (totalBytes * 8.0 / milliSeconds / 1000); // M bps
		System.out.println("\nTransfer stats:");
		System.out.println("\nFile size:\t\t\t " + totalBytes);
		System.out.printf("End-to-end transfer time:\t %.3f s\n", (float) milliSeconds / 1000);
		System.out.printf("End-to-end transfer rate:\t %.3f M bps\n", speed);
		System.out.println("Number of data messages sent:\t " + totalDataBlocks);
		System.out.println("Number of Acks received:\t " + totalAcks);

	}

	public int getInitTimeout() {
		return INIT_TIMEOUT;
	}
	
	public void setFileSize(int fileSize) {
		totalBytes = fileSize;
	}
	
	public void setAcks(int ackCounter) {
		totalAcks = ackCounter;
	}
	
	public void setData(int dataCounter) {
		totalDataBlocks = dataCounter;
	}
}
