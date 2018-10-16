package tftp;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import tftp.TFtpPacketV18.OpCode;

public class TFtp {
	private static int timeoutLimit = 5;
	private static int maxTftpData = 512;
	private static String mode = "octet";

	private static int ackCounter = 0;
	private static int dataCounter = 0;
	private static int blockNumber = 0;
	private static DatagramPacket dataPacket;
	private static TFtpPacketV18 TftpPacket;
	private static int serverTID;
	private static int read = 0;
	private static byte[] fileData = new byte[maxTftpData];
	private static byte[] buffer;
	private static boolean sendEmptyMessage;

	public static void main(String[] args) throws UnknownHostException {
		int port;
		String fileName;
		InetAddress server;

		switch (args.length) {
		case 3:
			server = InetAddress.getByName(args[0]);
			port = Integer.valueOf(args[1]);
			fileName = args[2];
			break;
		default:
			System.out.printf("usage: java %s server port filename\n", TFtp.class.getName());
			return;
		}

		Stats stats = new Stats();

		try {
			sendFile(stats, fileName, server, port);
		} catch (SocketException e) {
			System.out.println(e.getMessage());
			System.exit(1);
		} catch (FileNotFoundException e) {
			System.out.println(e.getMessage());
			System.exit(1);

		} catch (IOException e) {
			System.out.println(e.getMessage());
			System.exit(1);
		}
		
		stats.setAcks(ackCounter);
		stats.setData(dataCounter);
		stats.printReport();
	}

	private static void sendFile(Stats stats, String filename, InetAddress server, int port)
			throws FileNotFoundException, IOException {
		try (DatagramSocket socket = new DatagramSocket()) {

			socket.setSoTimeout(stats.getInitTimeout());

			File fileToBeSent = new File(filename);
			long fileSize = fileToBeSent.length();
			stats.setFileSize((int) fileSize);

			/*
			 * checks if the file size is a multiple of the maximum block size. If it is,
			 * checks the flag so that an empty packet is sent after the file transfer is
			 * finished.
			 */
			sendEmptyMessage = fileSize % maxTftpData == 0 ? true : false;

			try (FileInputStream in = new FileInputStream(fileToBeSent)) {

				filename = "/tmp/" + filename;

				try {

					SocketAddress sockaddr = new InetSocketAddress(server, port);
					sendWriteRequest(filename, server, socket, sockaddr);

					buffer = new byte[65536];
					dataPacket = new DatagramPacket(buffer, buffer.length);
					
					socket.receive(dataPacket);
					
					serverTID = dataPacket.getPort();
					TftpPacket = new TFtpPacketV18(dataPacket.getData(), dataPacket.getLength());

					// Updates the port number to the TID chosen by the server.
					sockaddr = new InetSocketAddress(server, serverTID);

					if (TftpPacket.getOpCode().equals(OpCode.OP_ACK)) {	// If server acknowledged the write request
						
						ackCounter++;
						
						while ((read = in.read(fileData)) != -1) {
							blockNumber++;
							timeoutLimit = 5;
							sendDataChunk(fileData, socket, sockaddr, read);
							waitForACK(blockNumber, socket, sockaddr);

						}

						if (sendEmptyMessage) { // send an empty message signaling that the transfer is over. will only execute if file size is multiple of maxTftpData

							blockNumber++;
							timeoutLimit = 5;

							fileData = new byte[0];
							sendDataChunk(fileData, socket, sockaddr, 0);
							waitForACK(blockNumber, socket, sockaddr);

						}

					} else {
						System.out.println("Recieved: " + TftpPacket.getOpCode()
								+ " instead of ACK when requesting to write to server.");
						System.exit(1);
					}

				} catch (SocketTimeoutException e) {
					System.out.println("Server didn't respond to write request.");
					System.exit(1);
				}
			}

		}

	}

	private static void sendWriteRequest(String filename, InetAddress server, DatagramSocket socket,
			SocketAddress sockaddr) throws IOException {
		TFtpPacketV18 writeRequest = new TFtpPacketV18(OpCode.OP_WRQ);
		writeRequest.putBytes(filename.getBytes());
		writeRequest.putByte(0);
		writeRequest.putBytes(mode.getBytes());
		writeRequest.putByte(0);
		DatagramPacket writeRequestPacket = writeRequest.toDatagramPacket(sockaddr);
		socket.send(writeRequestPacket);
	}

	private static void waitForACK(int blockNumber, DatagramSocket socket, SocketAddress sockaddr) throws IOException {

		buffer = new byte[65536];
		dataPacket = new DatagramPacket(buffer, buffer.length);

		while (timeoutLimit > 0) {
			try {
				socket.receive(dataPacket);
				TftpPacket = new TFtpPacketV18(dataPacket.getData(), dataPacket.getLength());

				if (dataPacket.getPort() == serverTID) {
					switch (TftpPacket.getOpCode()) {
					case OP_ACK:
						if (TftpPacket.getBlockNumber() == blockNumber) {
							ackCounter++;
							return; // server received the data block, no need to keep re-sending
						} else {
							throw new SocketTimeoutException("Server didn't recieve last data block.");
						}
					case OP_ERROR:
						System.out.println(TftpPacket.getErrorCode());
						System.exit(1);
						break;
					default:
						System.exit(-1);
						break;
					}
				} else {
					System.out.println("Server TID mismatch.");
					SocketAddress errorSocketAddress = new InetSocketAddress(dataPacket.getAddress(),
							dataPacket.getPort());
					sendErrorMessage("Server TID mismatch.", (int) OpCode.OP_ERROR.toShort(), socket,
							errorSocketAddress);
				}
			} catch (SocketTimeoutException e) {
				timeoutLimit--;
				System.out.println("Received ACK for block number "+ TftpPacket.getBlockNumber()+" instead of " + blockNumber);
				System.out.println("Resending block number "+ blockNumber);
				sendDataChunk(fileData, socket, sockaddr, read);
			}
		}
		if (timeoutLimit == 0) {
			System.out.println("Lost connection to server...");
			System.exit(1);
		}

	}

	private static void sendErrorMessage(String message, int errorCode, DatagramSocket socket, SocketAddress sockaddr)
			throws IOException {
		TFtpPacketV18 tftpDataPacket = new TFtpPacketV18(OpCode.OP_ERROR);
		tftpDataPacket.putShort(errorCode);
		tftpDataPacket.putBytes(message.getBytes());
		tftpDataPacket.putByte(0);
		DatagramPacket dataPacket = tftpDataPacket.toDatagramPacket(sockaddr);
		socket.send(dataPacket);

	}

	private static void sendDataChunk(byte[] fileData, DatagramSocket socket, SocketAddress sockaddr, int read)
			throws IOException {
		dataCounter++;
		TFtpPacketV18 tftpDataPacket = new TFtpPacketV18(OpCode.OP_DATA);
		tftpDataPacket.putShort(blockNumber);
		tftpDataPacket.putBytes(fileData, read);
		DatagramPacket dataPacket = tftpDataPacket.toDatagramPacket(sockaddr);
		socket.send(dataPacket);

	}

}
