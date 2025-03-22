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
        // Use the single persistent connection to handle all requests
        while (true) {
            try {
                // Read the first line to determine the request type
                String firstLine = in.readLine();
                if (firstLine == null) {
                    System.out.println("No more data from proxy client. Closing connection.");
                    break;
                }

                // Handle CONNECT requests (for HTTPS)
                if (firstLine.startsWith("CONNECT")) {
                    handleConnectRequest(firstLine);
                    continue;
                }

                // Handle GET requests (for HTTP)
                StringBuilder request = new StringBuilder();
                request.append(firstLine).append("\r\n");
                String inputLine;
                while ((inputLine = in.readLine()) != null && !inputLine.isEmpty()) {
                    request.append(inputLine).append("\r\n");
                }
                System.out.println("Received request from proxy client:\n" + request.toString());

                // Parse the request to extract the target URL
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

                // Fetch the webpage from the target server
                String response = fetchWebpage(targetUrl, request.toString());

                // Send the response back to the proxy client
                out.print(response);
                out.flush();

            } catch (IOException e) {
                System.err.println("Error handling client: " + e.getMessage());
                break;
            }
        }

        // Clean up when the loop exits (connection closed)
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
            // Parse the target URL
            URL url = new URL(targetUrl);
            String host = url.getHost();
            int port = url.getPort() == -1 ? 80 : url.getPort(); // Default to port 80 for HTTP

            // Connect to the target web server
            try (Socket targetSocket = new Socket(host, port);
                 PrintWriter targetOut = new PrintWriter(targetSocket.getOutputStream(), true);
                 BufferedReader targetIn = new BufferedReader(new InputStreamReader(targetSocket.getInputStream()))) {

                // Forward the original request to the target server
                targetOut.print(clientRequest);
                targetOut.flush();

                // Read the response from the target server
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = targetIn.readLine()) != null) {
                    response.append(line).append("\r\n");
                }

                System.out.println("Received response from target server (" + host + "):\n" + response.toString());
                return response.toString();

            } catch (IOException e) {
                System.err.println("Error fetching webpage from " + host + ": " + e.getMessage());
                return "HTTP/1.1 502 Bad Gateway\r\n" +
                        "Content-Type: text/plain\r\n" +
                        "Content-Length: 23\r\n" +
                        "\r\n" +
                        "Failed to fetch webpage\n";
            }

        } catch (MalformedURLException e) {
            System.err.println("Invalid URL: " + targetUrl + " - " + e.getMessage());
            return "HTTP/1.1 400 Bad Request\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "Content-Length: 15\r\n" +
                    "\r\n" +
                    "Invalid URL\n";
        }
    }
}