package com.client;

import java.io.*;
import java.net.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class ProxyClient {
    private String serverHost = "127.0.0.1";
    private int serverPort = 8080;
    private LinkedBlockingQueue<String> requestQueue = new LinkedBlockingQueue<>();
    private Map<String, String> requestIdToUrl = new ConcurrentHashMap<>(); // Map request ID to URL
    private AtomicLong requestIdCounter = new AtomicLong(0); // For generating unique request IDs
    private int clientPort = 8081;
    private Socket serverSocket;
    private PrintWriter serverOut;
    private BufferedReader serverIn;
    private OutputStream serverRawOut;
    private InputStream serverRawIn;

    public ProxyClient() {
        try {
            serverSocket = new Socket(serverHost, serverPort);
            serverOut = new PrintWriter(serverSocket.getOutputStream(), true);
            serverIn = new BufferedReader(new InputStreamReader(serverSocket.getInputStream()));
            serverRawOut = serverSocket.getOutputStream();
            serverRawIn = serverSocket.getInputStream();
            System.out.println("Established persistent TCP connection to offshore proxy at " + serverHost + ":" + serverPort);
        } catch (IOException e) {
            System.err.println("Failed to connect to offshore proxy: " + e.getMessage());
            System.exit(1);
        }
    }

    public void startListening() {
        try (ServerSocket serverSocket = new ServerSocket(clientPort)) {
            System.out.println("Ship Proxy Client is listening on port " + clientPort + " for requests...");

            while (true) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    new Thread(() -> handleBrowserRequest(clientSocket)).start();
                } catch (IOException e) {
                    System.err.println("Error accepting browser request: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Error starting proxy client: " + e.getMessage());
        }
    }

    private void handleBrowserRequest(Socket clientSocket) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);
             InputStream clientRawIn = clientSocket.getInputStream();
             OutputStream clientRawOut = clientSocket.getOutputStream()) {

            String firstLine = in.readLine();
            if (firstLine == null) {
                System.err.println("Empty request received from browser.");
                return;
            }
            System.out.println("Received request from browser: " + firstLine);

            // Split the URL from the request "GET http://example.com"
            String[] requestParts = firstLine.split(" ");
            if (requestParts.length < 2) {
                System.err.println("Invalid request format: " + firstLine);
                return;
            }
            String method = requestParts[0];
            String url = requestParts[1];


//            if (method.equals("CONNECT")) {
//                System.out.println("Received CONNECT request for " + url + ". This proxy only supports HTTP GET requests.");
//                String response = "HTTP/1.1 400 Bad Request\r\n" +
//                        "Content-Type: text/plain\r\n" +
//                        "Content-Length: 29\r\n" +
//                        "\r\n" +
//                        "Proxy only supports HTTP GET\n";
//                out.println(response);
//                out.flush();
//                return;
//            }

            if (method.equals("CONNECT")) {
                handleConnectRequest(url, clientSocket, in, out, clientRawIn, clientRawOut);
                return;
            }

            if (!method.equals("GET")) {
                System.err.println("Unsupported method: " + method);
                String response = "HTTP/1.1 405 Method Not Allowed\r\n" +
                        "Content-Type: text/plain\r\n" +
                        "Content-Length: 23\r\n" +
                        "\r\n" +
                        "Only GET is supported\n";
                out.println(response);
                out.flush();
                return;
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://" + url;
            }
            addRequest(url);
            String response = processRequests();
            if (response != null) {
                out.print(response);
                out.flush();
            } else {
                String errorResponse = "HTTP/1.1 502 Bad Gateway\r\n" +
                        "Content-Type: text/plain\r\n" +
                        "Content-Length: 23\r\n" +
                        "\r\n" +
                        "Failed to fetch webpage\n";
                out.println(errorResponse);
                out.flush();
            }

        } catch (IOException e) {
            System.err.println("Error handling browser request: " + e.getMessage());
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                System.err.println("Error closing client socket: " + e.getMessage());
            }
        }
    }
    private void handleConnectRequest(String target, Socket clientSocket, BufferedReader clientIn, PrintWriter clientOut,
                                      InputStream clientRawIn, OutputStream clientRawOut) {
        try {
            // Parse the target (e.g., "www.google.com:443")
            String[] targetParts = target.split(":");
            if (targetParts.length != 2) {
                System.err.println("Invalid CONNECT target: " + target);
                clientOut.println("HTTP/1.1 400 Bad Request\r\n" +
                        "Content-Type: text/plain\r\n" +
                        "Content-Length: 15\r\n" +
                        "\r\n" +
                        "Invalid target\n");
                clientOut.flush();
                return;
            }
            String host = targetParts[0];
            int port = Integer.parseInt(targetParts[1]);

            // Send a CONNECT request to the offshore proxy over the persistent connection
            serverOut.println("CONNECT " + host + ":" + port);
            serverOut.flush();

            // Read the response from the offshore proxy
            String response = serverIn.readLine();
            if (!response.equals("OK")) {
                System.err.println("Offshore proxy failed to establish connection: " + response);
                clientOut.println("HTTP/1.1 502 Bad Gateway\r\n" +
                        "Content-Type: text/plain\r\n" +
                        "Content-Length: 23\r\n" +
                        "\r\n" +
                        "Failed to fetch webpage\n");
                clientOut.flush();
                return;
            }

            // Send 200 Connection Established to the browser
            clientOut.println("HTTP/1.1 200 Connection Established\r\n" +
                    "Connection: close\r\n" +
                    "\r\n");
            clientOut.flush();

            // Start relaying data between the browser and the offshore proxy
            Thread clientToServer = new Thread(() -> relayData(clientRawIn, serverRawOut));
            Thread serverToClient = new Thread(() -> relayData(serverRawIn, clientRawOut));
            clientToServer.start();
            serverToClient.start();

            // Wait for both threads to finish
            clientToServer.join();
            serverToClient.join();

        } catch (IOException | InterruptedException e) {
            System.err.println("Error handling CONNECT request: " + e.getMessage());
        }
    }

    private void relayData(InputStream in, OutputStream out) {
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
    public void addRequest(String requestUrl) {
        try {
            String requestId = String.valueOf(requestIdCounter.incrementAndGet());
            requestQueue.put(requestId + "|" + requestUrl); // Store request ID with URL
            requestIdToUrl.put(requestId, requestUrl);
            System.out.println("Added request (ID: " + requestId + "): " + requestUrl + " to queue.");
        } catch (InterruptedException e) {
            System.err.println("Failed to queue request: " + e.getMessage());
        }
    }

    public String processRequests() {
        while (!requestQueue.isEmpty()) {
            try {
                String requestEntry = requestQueue.take();
                String[] parts = requestEntry.split("\\|", 2);
                if (parts.length != 2) {
                    System.err.println("Invalid request entry in queue: " + requestEntry);
                    continue;
                }
                String requestId = parts[0];
                String requestUrl = parts[1];
                String response = sendRequestToProxy(requestId, requestUrl);
                requestIdToUrl.remove(requestId); // Clean up after processing
                return response;
            } catch (InterruptedException e) {
                System.err.println("Error processing queue: " + e.getMessage());
            }
        }
        return null;
    }

    private String sendRequestToProxy(String requestId, String requestUrl) {
        try {
            if (serverSocket.isClosed()) {
                System.out.println("Persistent connection closed. Attempting to reconnect...");
                reconnectToServer();
                if (serverSocket.isClosed()) {
                    throw new IOException("Failed to reconnect to offshore proxy.");
                }
            }
            serverSocket.setSoTimeout(15000); // 15-second read timeout

            URL url = new URL(requestUrl);
            String host = url.getHost();

            String request = "GET " + requestUrl + " HTTP/1.1\r\n" +
                    "Host: " + host + "\r\n" +
                    "X-Request-ID: " + requestId + "\r\n" +
                    "Connection: keep-alive\r\n" +
                    "\r\n";
            System.out.println("Sending request to offshore proxy (ID: " + requestId + "):\n" + request);
            serverOut.println(request);
            serverOut.flush();
            if (serverOut.checkError()) {
                System.err.println("Error sending request to offshore proxy (ID: " + requestId + ")");
                reconnectToServer();
                throw new IOException("Failed to send request to offshore proxy.");
            }

            StringBuilder response = new StringBuilder();
            String responseLine;
            String responseRequestId = null;
            System.out.println("Response from offshore proxy for " + requestUrl + " (ID: " + requestId + "):");
            try {
                while ((responseLine = serverIn.readLine()) != null) {
                    response.append(responseLine).append("\r\n");
                    System.out.println(responseLine);
                    if (responseLine.startsWith("X-Request-ID:")) {
                        responseRequestId = responseLine.split(":", 2)[1].trim();
                    }
                    if (responseLine.isEmpty()) {
                        break;
                    }
                }
            } catch (SocketTimeoutException e) {
                System.err.println("Timeout while reading response from offshore proxy for request ID " + requestId + ": " + e.getMessage());
                return "HTTP/1.1 504 Gateway Timeout\r\n" +
                        "Content-Type: text/plain\r\n" +
                        "Content-Length: 24\r\n" +
                        "\r\n" +
                        "Gateway timeout occurred";
            } catch (IOException e) {
                System.err.println("Error reading response from offshore proxy for request ID " + requestId + ": " + e.getMessage());
                reconnectToServer();
                throw e;
            }

            if (responseRequestId == null || !responseRequestId.equals(requestId)) {
                System.err.println("Received response with mismatched or missing request ID. Expected: " + requestId + ", Got: " + responseRequestId);
                return "HTTP/1.1 504 Gateway Timeout\r\n" +
                        "Content-Type: text/plain\r\n" +
                        "Content-Length: 24\r\n" +
                        "\r\n" +
                        "Gateway timeout occurred";
            }

            String responseStr = response.toString();
            int contentLength = -1;
            for (String line : responseStr.split("\r\n")) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.split(":")[1].trim());
                    break;
                }
            }
            if (contentLength > 0) {
                char[] body = new char[contentLength];
                int charsRead = serverIn.read(body, 0, contentLength);
                if (charsRead != contentLength) {
                    System.err.println("Incomplete body read: expected " + contentLength + " chars, got " + charsRead);
                }
                response.append(new String(body, 0, charsRead));
            }
            System.out.println("Full response from offshore proxy (ID: " + requestId + "):\n" + response.toString());
            System.out.println("Completed request for: " + requestUrl);
            return response.toString();

        } catch (MalformedURLException e) {
            System.err.println("Invalid URL format: " + requestUrl + " - " + e.getMessage());
            return "HTTP/1.1 400 Bad Request\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "Content-Length: 15\r\n" +
                    "\r\n" +
                    "Invalid URL";
        } catch (IOException e) {
            System.err.println("Failed to send request to proxy: " + e.getMessage());
            return null;
        }
    }

    private void reconnectToServer() throws IOException {
        if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close();
        }
        serverSocket = new Socket(serverHost, serverPort);
        serverOut = new PrintWriter(serverSocket.getOutputStream(), true);
        serverIn = new BufferedReader(new InputStreamReader(serverSocket.getInputStream()));
        serverRawOut = serverSocket.getOutputStream();
        serverRawIn = serverSocket.getInputStream();
        System.out.println("Re-established persistent TCP connection to offshore proxy at " + serverHost + ":" + serverPort);
    }
}