    import java.io.*;
    import java.net.*;
    import java.util.concurrent.*;
    import java.util.concurrent.atomic.AtomicInteger;

    public class CCS {
        private final int port;

        private final AtomicInteger totalClients = new AtomicInteger(0);
        private final AtomicInteger totalRequests = new AtomicInteger(0);
        private final AtomicInteger invalidRequests = new AtomicInteger(0);
        private final AtomicInteger sumOfResults = new AtomicInteger(0);

        private final ConcurrentHashMap<String, AtomicInteger> operationCounts = new ConcurrentHashMap<>();

        private final ExecutorService threadPool = Executors.newCachedThreadPool();

        public static void main(String[] args) {
            if (args.length != 1) {
                System.err.println("Use: java -jar CCS.jar <port>");
                return;
            }

            try {
                int port = Integer.parseInt(args[0]);

                CCS server = new CCS(port);
                server.start();
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Port must be a valid number.");
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            }
        }

        public CCS(int port) {
            this.port = port;
            operationCounts.put("ADD", new AtomicInteger(0));
            operationCounts.put("SUB", new AtomicInteger(0));
            operationCounts.put("MUL", new AtomicInteger(0));
            operationCounts.put("DIV", new AtomicInteger(0));
        }

        public void start() {
            threadPool.execute(this::startUDPService);   // Start UDP
            threadPool.execute(this::startStatisticsReporter); // Start statistics

            try (ServerSocket serverSocket = new ServerSocket(port)) {
                System.out.println("TCP Server started on port " + port);

                while (true) {
                    Socket clientSocket = serverSocket.accept();
                    threadPool.execute(() -> handleClient(clientSocket));
                }
            } catch (Exception e) {
                System.err.println("Error in CCS: " + e.getMessage());
            } finally {
                shutdown();
            }
        }

        private void startUDPService() {
            try (DatagramSocket udpSocket = new DatagramSocket(port)) {
                System.out.println("UDP Service started on port " + port);
                byte[] buffer = new byte[1024];
                while (true) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    udpSocket.receive(packet);

                    String message = new String(packet.getData(), 0, packet.getLength()).trim();
                    if (message.startsWith("CCS DISCOVER")) {
                        String response = "CCS FOUND";
                        DatagramPacket responsePacket = new DatagramPacket(
                                response.getBytes(), response.length(),
                                packet.getAddress(), packet.getPort()
                        );
                        udpSocket.send(responsePacket);
                        System.out.println("Sent UDP response: CCS FOUND");
                    }
                }
            } catch (Exception e) {
                System.err.println("Error in UDP service: " + e.getMessage());
            }
        }

        private void handleClient(Socket clientSocket) {
            try (
                    BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                    OutputStream writer = clientSocket.getOutputStream()
            ) {
                System.out.println("Client connected: " + clientSocket.getInetAddress());
                totalClients.incrementAndGet();

                String request;
                while ((request = reader.readLine()) != null) {
                    String response = processRequest(request);
                    writer.write((response + "\n").getBytes());
                    writer.flush();
                    System.out.println("Request: " + request + " -> Response: " + response);
                }
            } catch (Exception e) {
                System.err.println("Error handling client: " + e.getMessage());
            } finally {
                try {
                    clientSocket.close();
                } catch (IOException e) {
                    System.err.println("Error closing client socket: " + e.getMessage());
                }
                System.out.println("Client disconnected.");
            }
        }

        private String processRequest(String request) {
            try {
                String[] parts = request.split(" ");
                if (parts.length != 3) {
                    invalidRequests.incrementAndGet();
                    return "ERROR";
                }

                String operation = parts[0];
                int arg1 = Integer.parseInt(parts[1]);
                int arg2 = Integer.parseInt(parts[2]);
                int result;

                switch (operation) {
                    case "ADD":
                        result = arg1 + arg2;
                        operationCounts.get("ADD").incrementAndGet();
                        break;
                    case "SUB":
                        result = arg1 - arg2;
                        operationCounts.get("SUB").incrementAndGet();
                        break;
                    case "MUL":
                        result = arg1 * arg2;
                        operationCounts.get("MUL").incrementAndGet();
                        break;
                    case "DIV":
                        if (arg2 == 0) {
                            invalidRequests.incrementAndGet();
                            return "ERROR";
                        }
                        result = arg1 / arg2;
                        operationCounts.get("DIV").incrementAndGet();
                        break;
                    default:
                        invalidRequests.incrementAndGet();
                        return "ERROR";
                }

                totalRequests.incrementAndGet();
                sumOfResults.addAndGet(result);
                return String.valueOf(result);
            } catch (NumberFormatException e) {
                invalidRequests.incrementAndGet();
                return "ERROR";
            }
        }

        private void startStatisticsReporter() {
            while (true) {
                try {
                    Thread.sleep(10000); // Report every 10 seconds
                    printStatistics();
                } catch (InterruptedException e) {
                    System.err.println("Statistics reporter interrupted.");
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        private void printStatistics() {
            System.out.println("\nStatistics Report:");
            System.out.println("Total Clients: " + totalClients.get());
            System.out.println("Total Requests: " + totalRequests.get());
            System.out.println("Invalid Requests: " + invalidRequests.get());
            System.out.println("Sum of Results: " + sumOfResults.get());
            System.out.println("Operation Counts:");
            operationCounts.forEach((op, count) -> {
                System.out.println(op + ": " + count.get());
            });
            System.out.println();
        }

        private void shutdown() {
            threadPool.shutdown();
            System.out.println("Server shutting down...");
        }
    }

