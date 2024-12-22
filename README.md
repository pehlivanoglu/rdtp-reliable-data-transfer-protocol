# Reliable Data Transfer Protocol (RDTP)

This project implements a **Reliable Data Transfer Protocol** (RDTP) over multiple interfaces using UDP. The goal is to transfer files efficiently and reliably under challenging conditions, such as bandwidth throttling, packet loss, and varying connection speeds.

## Features
- Dynamic adaptation to varying connection speeds and packet loss rates.
- Comprehensive statistics and performance reporting:
  - Transfer speed (per connection).
  - Packet loss rate and round-trip time (RTT) metrics.
  - File integrity check using MD5 hash.
- User-interactive file selection and download process.


## Prerequisites
1. **System Requirements**:
   - A Linux-based system is strongly recommended. The `tc` utility must be installed and working.
   - Docker is required to run the provided server (see setup instructions below).
   - Java (JRE or JDK)


## How to Run

1. **Run the Server**:
   - Clone the server repository from [GitHub](https://github.com/pehlivanoglu/rdtp-reliable-data-transfer-protocol).
   - Navigate to the `FileListServer` directory and run the provided `docker-start.sh` script to start the server.

2. **Test the Server**:
   - Ensure the `tc` logs are visible, confirming bandwidth throttling and packet loss are working:
     ```
     setting rate on lo 2 1Mbps
     setting loss on eth0 4 10%
     ```

3. **Run the Client**:
   - Navigate to the `FileListClient`.
   - Run the client via the command line:
     ```bash
     ./run.sh 127.0.0.1:5000 127.0.0.1:5001
     ```

---

## Contributors
- Ahmet Pehlivanoglu
- Mert Tufekci