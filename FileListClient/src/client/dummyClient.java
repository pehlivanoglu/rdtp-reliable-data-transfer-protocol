package client;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

import model.FileDataResponseType;
import model.FileListResponseType;
import model.FileSizeResponseType;
import model.RequestType;
import model.ResponseType;
import model.ResponseType.RESPONSE_TYPES;
// import client.loggerManager;

public class dummyClient {
    final static int TIMEOUT = 1000;
    ArrayList<Long> downloadedPackets = new ArrayList<>(); // nth packets -> e.g 3rd is 2001-3000 bytes -> n*1000-999 to n*1000
    HashMap<Long, byte[]> downloadedData = new HashMap<>();
    List<Long> missingPackets = new ArrayList<>();
    
    private void getFileList(String ip, int port) throws IOException {
        InetAddress IPAddress = InetAddress.getByName(ip);
        RequestType req = new RequestType(RequestType.REQUEST_TYPES.GET_FILE_LIST, 0, 0, 0, null);
        byte[] sendData = req.toByteArray();
        DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, IPAddress, port);

        while (true) {
            try {
                DatagramSocket dsocket = new DatagramSocket();
                dsocket.send(sendPacket);

                byte[] receiveData = new byte[ResponseType.MAX_RESPONSE_SIZE];
                DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);

                dsocket.setSoTimeout(TIMEOUT);
                dsocket.receive(receivePacket);

                FileListResponseType response = new FileListResponseType(receivePacket.getData());
                loggerManager.getInstance(this.getClass()).debug(response.toString());

                dsocket.close();
                return;
            } catch (SocketTimeoutException e) {
                loggerManager.getInstance(this.getClass()).warn("Timeout occurred, retrying...");
            }
        }
    }

    private long getFileSize(String ip, int port, int file_id) throws IOException {
        InetAddress IPAddress = InetAddress.getByName(ip);
        RequestType req = new RequestType(RequestType.REQUEST_TYPES.GET_FILE_SIZE, file_id, 0, 0, null);
        byte[] sendData = req.toByteArray();
        DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, IPAddress, port);

        while (true) {
            try {
                DatagramSocket dsocket = new DatagramSocket();
                dsocket.send(sendPacket);

                byte[] receiveData = new byte[ResponseType.MAX_RESPONSE_SIZE];
                DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);

                dsocket.setSoTimeout(TIMEOUT);
                dsocket.receive(receivePacket);

                FileSizeResponseType response = new FileSizeResponseType(receivePacket.getData());
                loggerManager.getInstance(this.getClass()).debug(response.toString());

                dsocket.close();
                return response.getFileSize(); // Exit the method if response is received
            } catch (SocketTimeoutException e) {
                loggerManager.getInstance(this.getClass()).warn("Timeout occurred, retrying... ");
            }
        }
    }

    private long ping(String ip, int port) {
        try {
            InetAddress IPAddress = InetAddress.getByName(ip);
            // Using get file size request as a ping request since it is a small request
            RequestType req=new RequestType(RequestType.REQUEST_TYPES.GET_FILE_SIZE, 1, 0, 0, null);
            byte[] sendData = req.toByteArray();
            DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length,IPAddress, port);    
            DatagramSocket dsocket = new DatagramSocket();
            dsocket.setSoTimeout(200);
            dsocket.send(sendPacket);
            long start = System.currentTimeMillis();
            byte[] receiveData=new byte[ResponseType.MAX_RESPONSE_SIZE];
            DatagramPacket receivePacket=new DatagramPacket(receiveData, receiveData.length);
            dsocket.receive(receivePacket);
            long end = System.currentTimeMillis();
            dsocket.close();
            return end - start;
        } catch (Exception e) {
            return 200L;
        }
        
    }
    
    private void downloadFile(String ip1, String ip2, int port1, int port2, int file_id, long start, long end, String filePath) throws IOException {
        InetAddress IPAddress1 = InetAddress.getByName(ip1);
        InetAddress IPAddress2 = InetAddress.getByName(ip2);
    
        DatagramSocket dsocket = new DatagramSocket();
        
        long rtt1 = 0; // ms
        long rtt2 = 0; // ms
        double bw1 = 0; // Mbps
        double bw2 = 0; // Mbps
        int selectedInterface = 1; // 1: ip1, 2: ip2
    
        int congestionWindow = 4; // Initial CWND, packets
        int slowStartThreshold = 512; // SSTHRESH, packets
        
        boolean increaseCongestionWindow = false;
        int timeout = 300; // ms, must be configured dynamically but works for 1KB packets
    
        long byteCursor = start;
        long lastIndex = (end + 999) / 1000; // Last packet's number
        long totalDataReceived = 0; // bytes
        long totalExcessDataReceived = 0; // bytes
        
        long downloadStart = System.currentTimeMillis();
        int requestCount = 0; // To count the number of requests sent
    
        while (byteCursor < end) {
            // Measure RTTs and Bandwidths
            if (requestCount % 10 == 0) { // Every 10 requests, measure RTT and Bandwidth
                rtt1 = ping(ip1, port1);
                rtt2 = ping(ip2, port2);
                System.out.println("RTT-1: " + rtt1 + " ms");
                System.out.println("RTT-2: " + rtt2 + " ms");
    
                // Compare bandwidths
                if (bw1 < bw2) {
                    selectedInterface = 2;
                } else {
                    selectedInterface = 1;
                }
                System.out.println("Switching to interface: " + (selectedInterface == 1 ? "IP1" : "IP2"));
            }
    
            long endOfRequest = Math.min(byteCursor - 1 + congestionWindow * ResponseType.MAX_DATA_SIZE, end);
            long startOfRequest = byteCursor;
    
            RequestType req = new RequestType(RequestType.REQUEST_TYPES.GET_FILE_DATA, file_id, startOfRequest, endOfRequest, null);
            byte[] sendData = req.toByteArray();
    
            DatagramPacket sendPacket1 = new DatagramPacket(sendData, sendData.length, IPAddress1, port1);
            DatagramPacket sendPacket2 = new DatagramPacket(sendData, sendData.length, IPAddress2, port2);
    
            byte[] receiveData = new byte[ResponseType.MAX_RESPONSE_SIZE];
            
            dsocket.setSoTimeout(timeout);
    
            // Send request via selected interface
            if (selectedInterface == 1) {
                dsocket.send(sendPacket1);
            } else {
                dsocket.send(sendPacket2);
            }
    
            long requestedAt = System.currentTimeMillis();
            long prevTotalDataReceived = totalDataReceived;
    
            for (int i = 0; i < congestionWindow; i++) { 
                try {
                    System.out.println("Congestion Window: " + congestionWindow); // DEBUG
                    
                    DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
                    dsocket.receive(receivePacket);
                    
                    FileDataResponseType response = new FileDataResponseType(receivePacket.getData());
                    loggerManager.getInstance(this.getClass()).debug(response.toString());
    
                    if (response.getResponseType() != RESPONSE_TYPES.GET_FILE_DATA_SUCCESS) {
                        continue;
                    }
    
                    if (response.getEnd_byte() > byteCursor) {
                        byteCursor = Math.max(byteCursor, response.getEnd_byte() + 1);
                    }
    
                    long packetNumber = response.getEnd_byte() / ResponseType.MAX_DATA_SIZE;
                    if (!this.downloadedData.containsKey(packetNumber)) {
                        this.downloadedData.put(response.getEnd_byte() / ResponseType.MAX_DATA_SIZE, response.getData());
                        this.downloadedPackets.add(response.getEnd_byte() / ResponseType.MAX_DATA_SIZE);
                        totalDataReceived += (long) response.getData().length;
                    }
                    else if (response.getEnd_byte() == end) { // Last packet
                        this.downloadedData.put((response.getEnd_byte() / ResponseType.MAX_DATA_SIZE) + 1, response.getData());
                        this.downloadedPackets.add((response.getEnd_byte() / ResponseType.MAX_DATA_SIZE) + 1);
                        totalDataReceived += (long) response.getData().length;
                    }

                    totalExcessDataReceived += (long) response.getData().length;
    
                    increaseCongestionWindow = true;
    
                } catch (IOException e) {
                    loggerManager.getInstance(this.getClass()).warn("Timeout occurred, requesting next packets...");
                    increaseCongestionWindow = false;
                    break;
                }
            }
            
            long receivedAt = System.currentTimeMillis();
    
            // Calculate bandwidth for the current request
            double requestBandwidth = (double) (totalDataReceived - prevTotalDataReceived) * 8 / 1_000_000.0 / ((receivedAt - requestedAt) / 1_000.0); // Mbps
            if (selectedInterface == 1) {
                bw1 = requestBandwidth;
            } else {
                bw2 = requestBandwidth;
            }
    
            System.out.printf("Bandwidth on current interface: %.2f Mbps\n", selectedInterface == 1 ? bw1 : bw2);
    
            // Update congestion window based on success or loss
            double loss = (double) (prevTotalDataReceived - totalDataReceived) / (double) (endOfRequest - startOfRequest);
            if (increaseCongestionWindow) {
                if (congestionWindow * 2 <= slowStartThreshold) {
                    congestionWindow *= 2;
                } else {
                    congestionWindow++;
                }
            } else if (loss > 0.25) {
                congestionWindow = Math.max(4, congestionWindow / 4); // Reduce to 1/4 until a minimum of 4
            } else if (loss > 0.1) {
                congestionWindow = Math.max(4, congestionWindow / 2); // Halve until a minimum of 4
            }
    
            requestCount++;
        }
    
        findMissingPackets(lastIndex); // Find missing packets
        System.out.println("missing packets: " + this.missingPackets);
    
        for (int i = 0; i < this.missingPackets.size(); i++) {
            long packetNumber = this.missingPackets.get(i);
            RequestType req = new RequestType(RequestType.REQUEST_TYPES.GET_FILE_DATA, file_id, packetNumber * ResponseType.MAX_DATA_SIZE - 999, packetNumber * ResponseType.MAX_DATA_SIZE, null);
            byte[] sendData = req.toByteArray();
            
            DatagramPacket sendPacket1 = new DatagramPacket(sendData, sendData.length, IPAddress1, port1);
            DatagramPacket sendPacket2 = new DatagramPacket(sendData, sendData.length, IPAddress2, port2);
            
            byte[] receiveData = new byte[ResponseType.MAX_RESPONSE_SIZE];
            
            if (bw1 >= bw2) {
                dsocket.send(sendPacket1);
                selectedInterface = 1;
            } else {
                dsocket.send(sendPacket2);
                selectedInterface = 2;
            }

            DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
    
            try {
                dsocket.receive(receivePacket);
                FileDataResponseType response = new FileDataResponseType(receivePacket.getData());
                loggerManager.getInstance(this.getClass()).debug(response.toString());
    
                if (response.getResponseType() != RESPONSE_TYPES.GET_FILE_DATA_SUCCESS) {
                    i--; // Retry the same packet
                    continue;
                }
    
                if (!this.downloadedData.containsKey(packetNumber)) {
                    this.downloadedData.put(packetNumber, response.getData());
                    this.downloadedPackets.add(packetNumber);
                    totalDataReceived += (long) response.getData().length;
                }
    
            } catch (Exception e) {
                i--; // Retry the same packet
                continue;
            }
        }
    
        long downloadEnd = System.currentTimeMillis();
    
        // Write the file
        long buildStart = System.currentTimeMillis();
        writeFileFromHashMap(lastIndex, filePath);
        long buildEnd = System.currentTimeMillis();
        System.out.println("File build time: " + (buildEnd - buildStart) + " ms");
    
        // Log the statistics
        double totalBandwidth = (totalDataReceived * 8 / 1_000_000.0) / ((downloadEnd - downloadStart) / 1_000.0); // Mbps
        System.out.println("Downloaded packets: " + this.downloadedPackets.size());
        System.out.printf("Bandwidth: %.2f Mbps \n", totalBandwidth);
        System.out.println("Download time elapsed: " + (downloadEnd - downloadStart) + " ms");
        System.out.println("Total data received: " + totalDataReceived + " bytes");
        System.out.println("Excess data received: " + (totalExcessDataReceived - totalDataReceived) + " bytes");
        System.out.println("Retransmitted " + this.missingPackets.size() + " packets");
    
        dsocket.close();
    }
    

    public void findMissingPackets(long lastIndex) {
        Set<Long> seen = new HashSet<>(this.downloadedPackets);
    
        for (long i = 1; i <= lastIndex; i++) {
            if (!seen.contains(i)) {
                this.missingPackets.add(i);
            }
        }
    }
    
    private void writeFileFromHashMap(long lastIndex, String outputFilePath) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(outputFilePath)) {
            for (long i = 1; i <= lastIndex; i++) {
                if (this.downloadedData.containsKey(i)) {
                    fos.write(this.downloadedData.get(i));
                } else {
                    System.err.println("Missing data for index: " + i);
                    throw new IOException("Data is missing for index " + i);
                }
            }
            System.out.println("\n\nFile successfully written to " + outputFilePath);
        }
    }

    public static String getFileMD5(String filePath) {
        try (FileInputStream fis = new FileInputStream(filePath)) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                md.update(buffer, 0, bytesRead);
            }

            byte[] md5Bytes = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : md5Bytes) {
                sb.append(String.format("%02x", b));
            }

            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not found", e);
        } catch (IOException e) {
            throw new RuntimeException("Error reading the file", e);
        }
    }

    public static void main(String[] args) throws Exception {

        if (args.length < 1) {
            throw new IllegalArgumentException("ip:port is mandatory");
        }

        String[] ipAddresses = new String[args.length];
        int[] ports = new int[args.length];

        for (int i = 0; i < args.length; i++) {
            String[] adr = args[i].split(":");
            String ip;
            int port;

            try {
                ip = adr[0];
                port = Integer.valueOf(adr[1]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("port should be a number");
            }

            ipAddresses[i] = ip;
            ports[i] = port;
        }

        dummyClient inst = new dummyClient();
        Scanner scanner = new Scanner(System.in);

        inst.getFileList(ipAddresses[0], ports[0]);

        System.out.println("Enter a number: ");
        int fileNumber = scanner.nextInt();
        scanner.close();
        System.out.println("File " + fileNumber + " has been selected. Getting the size information…");

        long fileSizeBytes = inst.getFileSize(ipAddresses[0], ports[0], fileNumber);
        System.out.println("File size is " + fileSizeBytes + " bytes");

        Thread.sleep(500); // Wait for 0.5 second before starting the download to show the file size

        String filePath = "files/output.txt";
        inst.downloadFile(ipAddresses[0], ipAddresses[1], ports[0], ports[1], fileNumber, 1, fileSizeBytes, filePath);
        String md5Checksum = getFileMD5(filePath);
        System.out.println("MD5 Checksum of file "+ fileNumber +" : " + md5Checksum);
        
    }
}