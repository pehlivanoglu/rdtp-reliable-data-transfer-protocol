package client;

import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import model.FileDataResponseType;
import model.FileListResponseType;
import model.FileSizeResponseType;
import model.RequestType;
import model.ResponseType;
import model.ResponseType.RESPONSE_TYPES;
import client.loggerManager;

public class dummyClient {

    private static final int PACKET_SIZE = 1000; // 1 KB packets
    private static final double GAIN_PROBE_BW = 1.25;
    private static final double GAIN_DRAIN = 0.75;

    private final byte[] fileData;

    public dummyClient(long fileSize) {
        this.fileData = new byte[(int) fileSize];
    }

    private void getFileDataBBR(String ip, int port, int file_id, long start, long end) throws IOException, InterruptedException {
        InetAddress IPAddress = InetAddress.getByName(ip);
        DatagramSocket dsocket = new DatagramSocket();
        dsocket.setSoTimeout(2000);
    
        double bandwidthEstimate = 1_000_000; // Initial bandwidth assumption
        double pacingRate = bandwidthEstimate;
        long rttMin = Long.MAX_VALUE;
        int congestionWindow = 1; // Initial congestion window size
    
        long maxReceivedByte = start - 1;
        Set<Long> missingRanges = new HashSet<>();
        int timeoutCount = 0; 
        final int MAX_TIMEOUTS_BEFORE_REOPEN = 5; // Threshold for socket reopening
    
        while (maxReceivedByte < end || !missingRanges.isEmpty()) {
            if (maxReceivedByte < end) {
                for (long i = maxReceivedByte + 1; i <= end; i += PACKET_SIZE) {
                    missingRanges.add(i);
                }
            }
    
            long sendStartTime = System.currentTimeMillis();
    
            int packetsToRequest = Math.min(congestionWindow, missingRanges.size());
            Set<Long> rangesToRequest = new HashSet<>();
            int count = 0;
    
            for (long packetStart : missingRanges) {
                if (count >= packetsToRequest) break;
    
                long packetEnd = Math.min(packetStart + PACKET_SIZE - 1, end);
                RequestType req = new RequestType(RequestType.REQUEST_TYPES.GET_FILE_DATA, file_id, packetStart, packetEnd, null);
                byte[] sendData = req.toByteArray();
                DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, IPAddress, port);
                dsocket.send(sendPacket);
    
                rangesToRequest.add(packetStart);
                count++;
            }
    
            byte[] receiveData = new byte[ResponseType.MAX_RESPONSE_SIZE];
            DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
    
            try {
                dsocket.receive(receivePacket);
                long receiveTime = System.currentTimeMillis();
    
                FileDataResponseType response = new FileDataResponseType(receivePacket.getData());
                loggerManager.getInstance(this.getClass()).debug(response.toString());
    
                if (response.getResponseType() != RESPONSE_TYPES.GET_FILE_DATA_SUCCESS) {
                    throw new IOException("Error retrieving file data: " + response.getResponseType());
                }
    
                long rttSample = receiveTime - sendStartTime;
                rttMin = Math.min(rttMin, rttSample);
    
                double deliveryRate = PACKET_SIZE / (rttSample / 1000.0); // bytes per second
                bandwidthEstimate = Math.max(bandwidthEstimate, deliveryRate);
    
                pacingRate = bandwidthEstimate * (response.getEnd_byte() == end ? GAIN_DRAIN : GAIN_PROBE_BW);
                congestionWindow = (int) Math.max(1, pacingRate * rttMin / PACKET_SIZE);
    
                long packetStart = response.getStart_byte();
                long packetEnd = response.getEnd_byte();
                System.arraycopy(response.getData(), 0, fileData, (int) (packetStart - 1), (int) (packetEnd - packetStart + 1));
                missingRanges.remove(packetStart);
    
                maxReceivedByte = Math.max(maxReceivedByte, packetEnd);
    
                timeoutCount = 0; // Reset timeout count after successful response
            } catch (IOException e) {
                loggerManager.getInstance(this.getClass()).error("Timeout or packet loss: " + e.getMessage());
                timeoutCount++;
    
                // Reopen the socket if too many timeouts occur
                if (timeoutCount >= MAX_TIMEOUTS_BEFORE_REOPEN) {
                    loggerManager.getInstance(this.getClass()).info("Reopening socket due to repeated timeouts.");
                    dsocket.close();
                    dsocket = new DatagramSocket();
                    dsocket.setSoTimeout(2000);
                    timeoutCount = 0;
                }
            }
    
            long sleepTime = (long) (PACKET_SIZE / pacingRate * 1000); //ms
            if (sleepTime > 0) {
                Thread.sleep(sleepTime);
            }
        }
    
        dsocket.close();
        System.out.println("Download complete.");
    }    

    private void buildFile(String outputFileName) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(outputFileName)) {
            fos.write(fileData);
        }
        System.out.println("File has been saved to: " + outputFileName);
    }

    private void getFileList(String ip, int port) throws IOException {
    InetAddress IPAddress = InetAddress.getByName(ip);
    RequestType req = new RequestType(RequestType.REQUEST_TYPES.GET_FILE_LIST, 0, 0, 0, null);
    byte[] sendData = req.toByteArray();
    DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, IPAddress, port);
    DatagramSocket dsocket = new DatagramSocket();
    dsocket.setSoTimeout(1500);

    while (true) {
        try {
            dsocket.send(sendPacket);
            byte[] receiveData = new byte[ResponseType.MAX_RESPONSE_SIZE];
            DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
            dsocket.receive(receivePacket);
            FileListResponseType response = new FileListResponseType(receivePacket.getData());
            loggerManager.getInstance(this.getClass()).debug(response.toString());
            break;
        } catch (SocketTimeoutException e) {
            loggerManager.getInstance(this.getClass()).warn("Timeout waiting for response, retrying...");
        }
    }

    dsocket.close();
}

    private long getFileSize(String ip, int port, int file_id) throws IOException {
        InetAddress IPAddress = InetAddress.getByName(ip);
        RequestType req = new RequestType(RequestType.REQUEST_TYPES.GET_FILE_SIZE, file_id, 0, 0, null);
        byte[] sendData = req.toByteArray();
        DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, IPAddress, port);
        DatagramSocket dsocket = new DatagramSocket();
        dsocket.send(sendPacket);
        byte[] receiveData = new byte[ResponseType.MAX_RESPONSE_SIZE];
        DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
        dsocket.receive(receivePacket);
        FileSizeResponseType response = new FileSizeResponseType(receivePacket.getData());
        loggerManager.getInstance(this.getClass()).debug(response.toString());
        return response.getFileSize();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException("ip:port is mandatory");
        }

        String[] adr1 = args[0].split(":");
        String ip1 = adr1[0];
        int port1 = Integer.valueOf(adr1[1]);

        dummyClient inst = new dummyClient(0);

        inst.getFileList(ip1, port1);

        System.out.println("Enter a file number: ");
        int fileNumber = new Scanner(System.in).nextInt();

        long fileSizeBytes = inst.getFileSize(ip1, port1, fileNumber);
        System.out.println("File size: " + fileSizeBytes + " bytes");
        
        dummyClient downloadClient = new dummyClient(fileSizeBytes);

        long startTime = System.nanoTime(); // TODO: debug
        downloadClient.getFileDataBBR(ip1, port1, fileNumber, 1, fileSizeBytes);
        long endTime = System.nanoTime(); // TODO: debug
    
        double elapsedTimeInSeconds = (endTime - startTime) / 1_000_000_000.0;
        System.out.println("Elapsed time in seconds: " + elapsedTimeInSeconds);
    
        double bandwidthMbps = (fileSizeBytes * 8) / elapsedTimeInSeconds / 1_000_000;
        System.out.println("Bandwidth: " + bandwidthMbps + " Mbps");

        downloadClient.buildFile("/home/pehlivanoglu/CS468/project2/files/output.txt");
    }
}
