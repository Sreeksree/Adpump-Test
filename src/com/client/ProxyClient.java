package com.client;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;

public class ProxyClient {
    private String serverHost = "127.0.0.1";
    private int serverPort = 8080;
    private LinkedBlockingQueue<String> requestQueue = new LinkedBlockingQueue<>();
    private int clientPort = 8081;
    private Socket serverSocket;
    private PrintWriter serverOut;
    private BufferedReader serverIn;

    public ProxyClient() {
        try {
            serverSocket = new Socket(serverHost, serverPort);
            serverOut = new PrintWriter(serverSocket.getOutputStream(), true);
            serverIn = new BufferedReader(new InputStreamReader(serverSocket.getInputStream()));
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
             PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)) {

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


            if (method.equals("CONNECT")) {
                System.out.println("Received CONNECT request for " + url + ". This proxy only supports HTTP GET requests.");
                String response = "HTTP/1.1 400 Bad Request\r\n" +
                        "Content-Type: text/plain\r\n" +
                        "Content-Length: 29\r\n" +
                        "\r\n" +
                        "Proxy only supports HTTP GET\n";
                out.println(response);
                out.flush();
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
            processRequests();
            String response = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "Content-Length: 13\r\n" +
                    "\r\n" +
                    "Request Queued\n";
            out.println(response);
            out.flush();

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

    public void addRequest(String requestUrl) {
        try {
            requestQueue.put(requestUrl);
            System.out.println("Added request: " + requestUrl + " to queue.");
        } catch (InterruptedException e) {
            System.err.println("Failed to queue request: " + e.getMessage());
        }
    }

    public void processRequests() {
        while (!requestQueue.isEmpty()) {
            try {
                String requestUrl = requestQueue.take();
                sendRequestToProxy(requestUrl);
            } catch (InterruptedException e) {
                System.err.println("Error processing queue: " + e.getMessage());
            }
        }
    }

    private void sendRequestToProxy(String requestUrl) {
        try {
            if (serverSocket.isClosed()) {
                throw new IOException("Persistent connection to offshore proxy is closed.");
            }
            URL url = new URL(requestUrl);
            String host = url.getHost();

            // Send HTTP request over the persistent connection
            String request = "GET " + requestUrl + " HTTP/1.1\r\n" +
                    "Host: " + new URL(requestUrl).getHost() + "\r\n" +
                    "Connection: keep-alive\r\n" +
                    "\r\n";
            serverOut.println(request);
            serverOut.flush();

            String responseLine;
            System.out.println("Response from offshore proxy for " + requestUrl + ":");
            while ((responseLine = serverIn.readLine()) != null) {
                System.out.println(responseLine);
                if (responseLine.isEmpty()) {
                    break;
                }
            }
            System.out.println("Completed request for: " + requestUrl);

        }catch (MalformedURLException e) {
            System.err.println("Invalid URL format: " + requestUrl + " - " + e.getMessage());
        } catch (IOException e) {
            System.err.println("Failed to send request to proxy: " + e.getMessage());
        }
    }
}