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
import java.util.concurrent.atomic.AtomicBoolean;

import model.FileDataResponseType;
import model.FileListResponseType;
import model.FileSizeResponseType;
import model.RequestType;
import model.ResponseType;
import model.ResponseType.RESPONSE_TYPES;

public class dummyClient {
    final static int TIMEOUT = 1000;

    private final List<Long> downloadedPackets = new ArrayList<>();
    private final HashMap<Long, byte[]> downloadedData = new HashMap<>();
    private final List<Long> missingPackets = new ArrayList<>();
    private final List<Long> willBeDownloaded = new ArrayList<>();
    private List<Long> pings = new ArrayList<>();
    
    AtomicBoolean stopSignal = new AtomicBoolean(false);


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
            // Using GET_FILE_SIZE request as a small "ping"
            RequestType req = new RequestType(RequestType.REQUEST_TYPES.GET_FILE_SIZE, 1, 0, 0, null);
            byte[] sendData = req.toByteArray();
            DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, IPAddress, port);
            DatagramSocket dsocket = new DatagramSocket();
            dsocket.setSoTimeout(200);
            dsocket.send(sendPacket);
            long start = System.currentTimeMillis();
            byte[] receiveData = new byte[ResponseType.MAX_RESPONSE_SIZE];
            DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
            dsocket.receive(receivePacket);
            long end = System.currentTimeMillis();
            dsocket.close();
            return end - start;
        } catch (Exception e) {
            return 200L;
        }
    }

    public void downloadFile(String ip1, int port1, String ip2, int port2,int file_id, long start, long end, String filePath) throws IOException {
        long downloadStartTime = System.currentTimeMillis();

        // If the last chunk is smaller than 1000 bytes,
        // put that last packet index into missingPackets right away,
        // and adjust end so the main threads only download multiples of 1000.
        long remainder = (end % ResponseType.MAX_DATA_SIZE);
        long lastIndex = (end + (ResponseType.MAX_DATA_SIZE - 1)) / ResponseType.MAX_DATA_SIZE;
        if (remainder != 0) {
            missingPackets.add(lastIndex);
            end -= remainder;
        }

        for (long i = 1; i <= lastIndex; i++) {
            willBeDownloaded.add(i);
        }

        Thread tA = null, tB = null;
        
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    try {
                        DatagramSocket dsocket = new DatagramSocket();
                        long totalDataReceived = 0;

                        int congestionWindow = 4;
                        int slowStartThreshold = 512;
                        int timeout = 300;

                        long entireDownloadStart = System.currentTimeMillis();
                        long startOfRequest = 0L;
                        long endOfRequest = 0L;
                        while (true) {
                            long windowBytes = (long) congestionWindow * ResponseType.MAX_DATA_SIZE;
                            if (stopSignal.get()) {
                                break;
                            }

                            long rtt = ping(ip1, port1);
                            pings.add(rtt);

                            // System.out.println(Thread.currentThread().getName() + " RTT: " + rtt + " ms");

                            synchronized (willBeDownloaded) {
                                if (willBeDownloaded.isEmpty()) {
                                    break;
                                }
                            
                                startOfRequest = willBeDownloaded.get(0) * ResponseType.MAX_DATA_SIZE - (ResponseType.MAX_DATA_SIZE - 1);
                                endOfRequest   = startOfRequest + windowBytes - 1;
                            
                                for (int i = 0; i < congestionWindow; i++) {
                                    if (willBeDownloaded.isEmpty()) {
                                        break;
                                    }
                                    willBeDownloaded.remove(0);
                                }
                            }

                            RequestType req = new RequestType(RequestType.REQUEST_TYPES.GET_FILE_DATA, file_id, startOfRequest, endOfRequest, null);
                            byte[] sendData = req.toByteArray();
                            DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, InetAddress.getByName(ip1), port1);

                            dsocket.setSoTimeout(timeout);
                            dsocket.send(sendPacket);

                            long requestedAt = System.currentTimeMillis();
                            long prevTotalDataReceived = totalDataReceived;
                            boolean increaseCongestionWindow = false;

                            for (int i = 0; i < congestionWindow; i++) {
                                if (stopSignal.get()) {
                                    break;
                                }
                                try {
                                    byte[] receiveData = new byte[ResponseType.MAX_RESPONSE_SIZE];
                                    DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
                                    dsocket.receive(receivePacket);

                                    FileDataResponseType response = new FileDataResponseType(receivePacket.getData());
                                    loggerManager.getInstance(this.getClass()).debug(response.toString() + " " + Thread.currentThread().getName() + "\n\n");

                                    if (response.getResponseType() != RESPONSE_TYPES.GET_FILE_DATA_SUCCESS) {
                                        continue;
                                    }

                                    long receivedEnd   = response.getEnd_byte();
                                    // store if not present
                                    long packetNumber = receivedEnd / ResponseType.MAX_DATA_SIZE;
                                    synchronized (downloadedData) {
                                        if (!downloadedData.containsKey(packetNumber)) {
                                            downloadedData.put(packetNumber, response.getData());
                                            synchronized (downloadedPackets) {
                                                downloadedPackets.add(packetNumber);
                                            }
                                            totalDataReceived += response.getData().length;
                                        }
                                    }
                                    increaseCongestionWindow = true;

                                } catch (IOException e) {
                                    loggerManager.getInstance(this.getClass()).warn(Thread.currentThread().getName() + " Timeout occurred, requesting next packets...\n");
                                    increaseCongestionWindow = false;
                                    break;
                                }
                            }

                            long receivedAt = System.currentTimeMillis();
                            double requestBandwidth = (double) (totalDataReceived - prevTotalDataReceived)* 8 / 1_000_000.0/ ((receivedAt - requestedAt) / 1_000.0);
                            // System.out.println(Thread.currentThread().getName() + " Request bandwidth: " + requestBandwidth + " Mbps");

                            double loss = 0.0;
                            long requestedBytes = (endOfRequest - startOfRequest + 1);
                            if (requestedBytes > 0) {
                                long actuallyReceived = totalDataReceived - prevTotalDataReceived;
                                if (actuallyReceived < 0) {
                                    actuallyReceived = 0;
                                }
                                loss = (double) (requestedBytes - actuallyReceived) / (double) requestedBytes;
                            }

                            // This congestion window logic is not a work of art but it works
                            if (increaseCongestionWindow) {
                                if (congestionWindow * 2 <= slowStartThreshold) {
                                    congestionWindow *= 2;
                                } else {
                                    congestionWindow++;
                                }
                            } else if (loss > 0.25) {
                                congestionWindow = Math.max(4, congestionWindow / 4);
                            } else if (loss > 0.1) {
                                congestionWindow = Math.max(4, congestionWindow / 2);
                            }
                        }

                        dsocket.close();
                        long entireDownloadEnd = System.currentTimeMillis();
                        // System.out.println(Thread.currentThread().getName() + " partial download done in " + (entireDownloadEnd - entireDownloadStart) + " ms.");

                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    stopSignal.set(true);
                }
                
            };

            tA = new Thread(runnable, "DownloadThread-A");
            tB = new Thread(runnable, "DownloadThread-B");

            tA.start();
            tB.start();

            try {
                tA.join();
                tB.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        
        findMissingPackets(lastIndex);
        System.out.println("missing packets (before second pass): " + this.missingPackets);
        int missingSize = missingPackets.size();

        Runnable missingRunnable = new Runnable() {
            @Override
            public void run() {
                try (DatagramSocket dsocket = new DatagramSocket()) {
                    dsocket.setSoTimeout(300);
                    while (true) {
                        long packetNumber;
                        synchronized (missingPackets) {
                            if (missingPackets.isEmpty()) {
                                break;
                            }
                            // pick the first missing packet
                            packetNumber = missingPackets.get(0);
                            missingPackets.remove(0);
                        }

                        long startByte = (packetNumber * ResponseType.MAX_DATA_SIZE) - (ResponseType.MAX_DATA_SIZE - 1);
                        if (startByte < 1) {
                            startByte = 1;
                        }
                        long endByte = packetNumber * ResponseType.MAX_DATA_SIZE;

                        RequestType req = new RequestType(RequestType.REQUEST_TYPES.GET_FILE_DATA,file_id, startByte, endByte, null);
                        byte[] sendData = req.toByteArray();
                        DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, InetAddress.getByName(ip1), port1);

                        dsocket.send(sendPacket);

                        byte[] receiveData = new byte[ResponseType.MAX_RESPONSE_SIZE];
                        DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);

                        try {
                            dsocket.receive(receivePacket);
                            FileDataResponseType response = new FileDataResponseType(receivePacket.getData());
                            loggerManager.getInstance(this.getClass()).debug(response.toString() + " " + Thread.currentThread().getName() + "\n\n");

                            if (response.getResponseType() != RESPONSE_TYPES.GET_FILE_DATA_SUCCESS) {
                                // re-try if not successful
                                synchronized (missingPackets) {
                                    missingPackets.add(packetNumber);
                                }
                                continue;
                            }

                            synchronized (downloadedData) {
                                if (!downloadedData.containsKey(packetNumber)) {
                                    downloadedData.put(packetNumber, response.getData());
                                    synchronized (downloadedPackets) {
                                        downloadedPackets.add(packetNumber);
                                    }
                                }
                            }
                        } catch (Exception e) {
                            synchronized (missingPackets) {
                                missingPackets.add(packetNumber);
                            }
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        };

        Thread tMissingA = new Thread(missingRunnable, "MissingThread-A");
        Thread tMissingB = new Thread(missingRunnable, "MissingThread-B");

        tMissingA.start();
        tMissingB.start();
        try {
            tMissingA.join();
            tMissingB.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        long buildStart = System.currentTimeMillis();
        writeFileFromHashMap(lastIndex, filePath);
        long buildEnd = System.currentTimeMillis();

        long downloadEndTime = System.currentTimeMillis();

        long totalDataReceived = 0;
        synchronized (this.downloadedData) {
            for (byte[] arr : downloadedData.values()) {
                totalDataReceived += (long) arr.length;
            }
        }
        double totalBandwidth = (totalDataReceived * 8 / 1_000_000.0)/ ((downloadEndTime - downloadStartTime) / 1000.0);

        System.out.println("\nFile build time: " + (buildEnd - buildStart) + " ms");
        System.out.printf("Bandwidth: %.2f Mbps \n", totalBandwidth);
        System.out.println("Download time : " + (downloadEndTime - downloadStartTime) + " ms");
        System.out.println("Total data received: " + totalDataReceived + " bytes");
        System.out.println("Retransmitted " + missingSize + " packets");
        System.out.println("Jitter: " + calculateJitter() + " ms");
    }

    public void findMissingPackets(long lastIndex) {
        Set<Long> seen;
        synchronized (downloadedPackets) {
            seen = new HashSet<>(this.downloadedPackets);
        }
        for (long i = 1; i <= lastIndex; i++) {
            if (!seen.contains(i)) {
                synchronized (missingPackets) {
                    missingPackets.add(i);
                }
            }
        }
    }

    private void writeFileFromHashMap(long lastIndex, String outputFilePath) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(outputFilePath)) {
            for (long i = 1; i <= lastIndex; i++) {
                byte[] data;
                synchronized (downloadedData) {
                    data = this.downloadedData.get(i);
                }
                if (data == null) {
                    System.err.println("Missing data for index: " + i);
                    throw new IOException("Data is missing for index " + i);
                }
                fos.write(data);
            }
            System.out.println("File successfully written to " + outputFilePath);
        }
    }

    public  double calculateJitter() {
        long sumOfDifferences = 0;
        for (int i = 1; i < this.pings.size(); i++) {
            long currentPing = this.pings.get(i);
            long previousPing = this.pings.get(i - 1);
            sumOfDifferences += Math.abs(currentPing - previousPing);
        }
        return (double) sumOfDifferences / (this.pings.size() - 1);
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
        if (args.length < 2) {
            throw new IllegalArgumentException("At least 2 ip:port pairs are needed, e.g. 127.0.0.1:9000 127.0.0.1:9001");
        }

        String[] ipAddresses = new String[args.length];
        int[] ports = new int[args.length];
        for (int i = 0; i < args.length; i++) {
            String[] adr = args[i].split(":");
            if (adr.length < 2)
                throw new IllegalArgumentException("ip:port is mandatory");
            String ip;
            int port;
            try {
                ip = adr[0];
                port = Integer.parseInt(adr[1]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("port should be a number");
            }
            ipAddresses[i] = ip;
            ports[i] = port;
        }

        dummyClient inst = new dummyClient();
        Scanner scanner = new Scanner(System.in);

        inst.getFileList(ipAddresses[0], ports[0]);

        System.out.println("Enter a file number: ");
        int fileNumber = scanner.nextInt();
        scanner.close();

        long fileSizeBytes = inst.getFileSize(ipAddresses[0], ports[0], fileNumber);
        System.out.println("File size: " + fileSizeBytes + " bytes");

        // Thread.sleep(1000); // demonstration

        String ip1 = ipAddresses[0];
        int port1 = ports[0];
        String ip2 = ipAddresses[1];
        int port2 = ports[1];

        String filePath = "files/output.txt";
        inst.downloadFile(ip1, port1, ip2, port2, fileNumber, 1, fileSizeBytes, filePath);
        String md5Checksum = getFileMD5(filePath);
        System.out.println("MD5 Checksum of the downloaded file : " + md5Checksum);
    }
}
