package com.server;

import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.net.*;

public class OffshoreServer {
    private ServerSocket serverSocket;
    private int port = 8080;
    private OutputStream out;
    private InputStream in;
    private Socket clientSocket;
    private final Object lock = new Object(); // For synchronizing access to clientSocket streams

    public OffshoreServer() {
        System.setProperty("http.keepAlive", "true");
        System.setProperty("http.maxConnections", "10");
        try {
            serverSocket = new ServerSocket(port);
            System.out.println("Offshore Proxy Server started on port " + port);

            clientSocket = serverSocket.accept();
            clientSocket.setSoTimeout(30000); // 30-second timeout for socket operations
            out = clientSocket.getOutputStream();
            in = clientSocket.getInputStream();
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
                ByteArrayOutputStream requestBuffer = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int bytesRead;
                boolean firstLineEnded = false;
                String firstLine = null;

                synchronized (lock) {
                    while (!firstLineEnded) {
                        bytesRead = in.read(buffer);
                        if (bytesRead == -1) {
                            System.out.println("No more data from proxy client. Closing connection.");
                            return;
                        }
                        requestBuffer.write(buffer, 0, bytesRead);
                        String currentData = requestBuffer.toString();
                        int endOfLine = currentData.indexOf("\r\n");
                        if (endOfLine != -1) {
                            firstLine = currentData.substring(0, endOfLine);
                            firstLineEnded = true;
                            break;
                        }
                    }
                }

                // Handle CONNECT requests (for HTTPS)
                if (firstLine.startsWith("CONNECT")) {
                    handleConnectRequest(firstLine);
                    continue;
                }

                // Handle GET requests (for HTTP)
                StringBuilder request = new StringBuilder();
                request.append(firstLine).append("\r\n");
                boolean headersEnded = false;

                synchronized (lock) {
                    while (!headersEnded) {
                        bytesRead = in.read(buffer);
                        if (bytesRead == -1) {
                            throw new IOException("Unexpected end of stream while reading headers");
                        }
                        String chunk = new String(buffer, 0, bytesRead);
                        request.append(chunk);
                        requestBuffer.write(buffer, 0, bytesRead);

                        int endOfHeaders = request.toString().indexOf("\r\n\r\n");
                        if (endOfHeaders != -1) {
                            headersEnded = true;
                            break;
                        }
                    }
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
                synchronized (lock) {
                    out.write(response.getBytes());
                    out.flush();
                }

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
                synchronized (lock) {
                    out.write("ERROR\r\n".getBytes());
                    out.flush();
                }
                return;
            }
            String[] targetParts = parts[1].split(":");
            if (targetParts.length != 2) {
                System.err.println("Invalid CONNECT target: " + parts[1]);
                synchronized (lock) {
                    out.write("ERROR\r\n".getBytes());
                    out.flush();
                }
                return;
            }
            String host = targetParts[0];
            int port = Integer.parseInt(targetParts[1]);

            // Connect to the target server
            Socket targetSocket = new Socket(host, port);
            targetSocket.setSoTimeout(30000); // 30-second timeout for target socket
            System.out.println("Connected to target server: " + host + ":" + port);

            // Send OK to the proxy client to indicate successful connection
            synchronized (lock) {
                out.write("OK\r\n".getBytes());
                out.flush();
            }

            // Start relaying data between the proxy client and the target server
            Thread clientToTarget = new Thread(() -> {
                try {
                    synchronized (lock) {
                        relayData(in, targetSocket.getOutputStream());
                    }
                } catch (IOException e) {
                    System.err.println("Error in client-to-target relay: " + e.getMessage());
                }
            });
            Thread targetToClient = new Thread(() -> {
                try {
                    synchronized (lock) {
                        relayData(targetSocket.getInputStream(), out);
                    }
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
            synchronized (lock) {
                try {
                    out.write("ERROR\r\n".getBytes());
                    out.flush();
                } catch (IOException ex) {
                    System.err.println("Error sending ERROR response: " + ex.getMessage());
                }
            }
        }
    }

    private void relayData(@NotNull InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[4096];
        int bytesRead;
        try {
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                out.flush();
            }
        } catch (IOException e) {
            // Only log the error if it's not a normal socket closure
            if (!e.getMessage().contains("Socket closed")) {
                throw e;
            }
        }
    }

    private @NotNull String fetchWebpage(String targetUrl, String clientRequest) {
        try {
            // Parse the target URL
            URL url = new URL(targetUrl);
            String host = url.getHost();
            int port = url.getPort() == -1 ? 80 : url.getPort(); // Default to port 80 for HTTP

            // Connect to the target web server
            try (Socket targetSocket = new Socket(host, port)) {
                targetSocket.setSoTimeout(30000); // 30-second timeout for target socket
                OutputStream targetOut = targetSocket.getOutputStream();
                InputStream targetIn = targetSocket.getInputStream();

                // Forward the original request to the target server
                targetOut.write(clientRequest.getBytes());
                targetOut.flush();

                // Read the response from the target server
                ByteArrayOutputStream responseBuffer = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = targetIn.read(buffer)) != -1) {
                    responseBuffer.write(buffer, 0, bytesRead);
                }

                String response = responseBuffer.toString();
                System.out.println("Received response from target server (" + host + "):\n" + response);
                return response;
            }

        } catch (MalformedURLException e) {
            System.err.println("Invalid URL: " + targetUrl + " - " + e.getMessage());
            return "HTTP/1.1 400 Bad Request\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "Content-Length: 15\r\n" +
                    "\r\n" +
                    "Invalid URL\n";
        } catch (IOException e) {
            System.err.println("Error fetching webpage from target: " + e.getMessage());
            return "HTTP/1.1 502 Bad Gateway\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "Content-Length: 23\r\n" +
                    "\r\n" +
                    "Failed to fetch webpage\n";
        }
    }
}