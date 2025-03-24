package com.server;

import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.net.*;

public class OffshoreServer {
    private ServerSocket serverSocket;
    private int port = 8080;
    private PrintWriter out;
    private BufferedReader in;
    private OutputStream rawOut;
    private InputStream rawIn;
    private Socket clientSocket;

    public OffshoreServer() {
        System.setProperty("http.keepAlive", "true");
        System.setProperty("http.maxConnections", "10");
        try {
            serverSocket = new ServerSocket(port);
            System.out.println("Offshore Proxy Server started on port " + port);


            clientSocket = serverSocket.accept();
            out = new PrintWriter(clientSocket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            rawOut = clientSocket.getOutputStream();
            rawIn = clientSocket.getInputStream();
            System.out.println("Established persistent connection with proxy client");
            startListening();
        } catch (IOException e) {
            System.err.println("Could not listen on port " + port + ": " + e.getMessage());
        }
    }

    private void startListening() {
        while (true) {
            try {
                String firstLine = in.readLine();
                if (firstLine == null) {
                    System.out.println("No more data from proxy client. Closing connection.");
                    break;
                }

                if (firstLine.startsWith("CONNECT")) {
                    handleConnectRequest(firstLine);
                    continue;
                }

                StringBuilder request = new StringBuilder();
                request.append(firstLine).append("\r\n");
                String requestId = null;
                String inputLine;
                while ((inputLine = in.readLine()) != null && !inputLine.isEmpty()) {
                    request.append(inputLine).append("\r\n");
                    if (inputLine.startsWith("X-Request-ID:")) {
                        requestId = inputLine.split(":", 2)[1].trim();
                    }
                }
                System.out.println("Received request from proxy client (ID: " + requestId + "):\n" + request.toString());

                String[] requestLines = request.toString().split("\r\n");
                if (requestLines.length == 0) {
                    System.err.println("Empty request received.");
                    continue;
                }
                String[] requestLineParts = requestLines[0].split(" ");
                if (requestLineParts.length < 2) {
                    System.err.println("Invalid request format: " + requestLines[0]);
                    continue;
                }
                String method = requestLineParts[0];
                String targetUrl = requestLineParts[1];

                String response = fetchWebpage(targetUrl, request.toString());
                if (requestId != null) {
                    response = "X-Request-ID: " + requestId + "\r\n" + response;
                }
                System.out.println("Sending response to proxy client (ID: " + requestId + "):\n" + response);
                out.print(response);
                out.flush();
                if (out.checkError()) {
                    System.err.println("Error sending response to proxy client (ID: " + requestId + ")");
                    break;
                }

            } catch (IOException e) {
                System.err.println("Error handling client: " + e.getMessage());
                break;
            }
        }

        try {
            clientSocket.close();
            serverSocket.close();
        } catch (IOException e) {
            System.err.println("Error closing server: " + e.getMessage());
        }
    }

    private void handleConnectRequest(@NotNull String connectLine) {
        try {
            // Parse the CONNECT request (e.g., "CONNECT www.google.com:443")
            String[] parts = connectLine.split(" ");
            if (parts.length != 2) {
                System.err.println("Invalid CONNECT request: " + connectLine);
                out.println("ERROR");
                out.flush();
                return;
            }
            String[] targetParts = parts[1].split(":");
            if (targetParts.length != 2) {
                System.err.println("Invalid CONNECT target: " + parts[1]);
                out.println("ERROR");
                out.flush();
                return;
            }
            String host = targetParts[0];
            int port = Integer.parseInt(targetParts[1]);

            // Connect to the target server
            Socket targetSocket = new Socket(host, port);
            System.out.println("Connected to target server: " + host + ":" + port);

            // Send OK to the proxy client to indicate successful connection
            out.println("OK");
            out.flush();

            // Start relaying data between the proxy client and the target server
            Thread clientToTarget = new Thread(() -> {
                try {
                    relayData(rawIn, targetSocket.getOutputStream());
                } catch (IOException e) {
                    System.err.println("Error in client-to-target relay: " + e.getMessage());
                }
            });
            Thread targetToClient = new Thread(() -> {
                try {
                    relayData(targetSocket.getInputStream(), rawOut);
                } catch (IOException e) {
                    System.err.println("Error in target-to-client relay: " + e.getMessage());
                }
            });
            clientToTarget.start();
            targetToClient.start();

            // Wait for both threads to finish
            clientToTarget.join();
            targetToClient.join();

            // Close the target socket
            targetSocket.close();

        } catch (IOException | InterruptedException e) {
            System.err.println("Error handling CONNECT request: " + e.getMessage());
            out.println("ERROR");
            out.flush();
        }
    }

    private void relayData(@NotNull InputStream in, OutputStream out) {
        try {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                out.flush();
            }
        } catch (IOException e) {
            System.err.println("Error relaying data: " + e.getMessage());
        }
    }
    private @NotNull String fetchWebpage(String targetUrl, String clientRequest) {
        try {
            URL url = new URL(targetUrl);
            String host = url.getHost();
            int port = url.getPort() == -1 ? 80 : url.getPort();

            Socket targetSocket = null;
            try {
                targetSocket = new Socket();
                targetSocket.connect(new InetSocketAddress(host, port), 5000);
                targetSocket.setSoTimeout(10000);
            } catch (IOException e) {
                System.err.println("Error connecting to target server " + host + ":" + port + ": " + e.getMessage());
                if (targetSocket != null) {
                    try {
                        targetSocket.close();
                    } catch (IOException closeException) {
                        System.err.println("Error closing target socket: " + closeException.getMessage());
                    }
                }
                return "HTTP/1.1 502 Bad Gateway\r\n" +
                        "Content-Type: text/plain\r\n" +
                        "Content-Length: 23\r\n" +
                        "\r\n" +
                        "Failed to fetch webpage";
            }

            try (PrintWriter targetOut = new PrintWriter(targetSocket.getOutputStream(), true);
                 BufferedReader targetIn = new BufferedReader(new InputStreamReader(targetSocket.getInputStream()))) {

                System.out.println("Sending request to target server (" + host + "):\n" + clientRequest);
                targetOut.print(clientRequest);
                targetOut.flush();

                StringBuilder response = new StringBuilder();
                String line;
                try {
                    while ((line = targetIn.readLine()) != null) {
                        response.append(line).append("\r\n");
                    }
                } catch (SocketTimeoutException e) {
                    System.err.println("Timeout while reading response from " + host + ": " + e.getMessage());
                    return "HTTP/1.1 504 Gateway Timeout\r\n" +
                            "Content-Type: text/plain\r\n" +
                            "Content-Length: 24\r\n" +
                            "\r\n" +
                            "Gateway timeout occurred";
                }

                System.out.println("Received response from target server (" + host + "):\n" + response.toString());
                return response.toString();

            } catch (IOException e) {
                System.err.println("Error fetching webpage from " + host + ": " + e.getMessage());
                return "HTTP/1.1 502 Bad Gateway\r\n" +
                        "Content-Type: text/plain\r\n" +
                        "Content-Length: 23\r\n" +
                        "\r\n" +
                        "Failed to fetch webpage";
            } finally {
                try {
                    targetSocket.close();
                } catch (IOException e) {
                    System.err.println("Error closing target socket: " + e.getMessage());
                }
            }

        } catch (MalformedURLException e) {
            System.err.println("Invalid URL: " + targetUrl + " - " + e.getMessage());
            return "HTTP/1.1 400 Bad Request\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "Content-Length: 15\r\n" +
                    "\r\n" +
                    "Invalid URL";
        }
    }
}