package com.client;

import java.io.*;
import java.net.*;

import java.util.concurrent.*;

public class ProxyClient {
    private String serverHost = "127.0.0.1";
    private int  serverPort = 8080;
    private LinkedBlockingQueue<String> requestQueue = new LinkedBlockingQueue<>();
    private int clientPort = 8081;
    private Socket serverSocket ;
    private PrintWriter serverOut;
    private BufferedReader serverIn;

    public ProxyClient() {
        // Establish a single TCP connection to the offshore proxy at startup
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

        }
        catch (IOException e) {
                    System.err.println("Error handling client request: " + e.getMessage());
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

            //This is to split the url from the request like GET http://xxsdfs.com
            String[] requestParts = firstLine.split(" ");
            if (requestParts.length < 2) {
                System.err.println("Invalid request format: " + firstLine);
                return;
            }
            String method = requestParts[0];
            String url = requestParts[1];

            //adding to que
            addRequest(url);

            // Process requests
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
        }
        finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                System.err.println("Error closing client socket: " + e.getMessage());
            }
        }
    }

    // Method to add requests to the queue
    public void addRequest(String requestUrl) {
        try {
            requestQueue.put(requestUrl);
            System.out.println("Added request: " + requestUrl + " to queue.");
        } catch (InterruptedException e) {
            System.err.println("Failed to queue request: " + e.getMessage());
        }
    }

    // To Process requests sequentially
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

    //To Send request to the offshore proxy
    private void sendRequestToProxy(String requestUrl) {
        try (Socket socket = new Socket(serverHost, serverPort);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            // Simple HTTP request
            String request = "GET " + requestUrl + " HTTP/1.1\r\n" +
                    "Host: " + serverHost + "\r\n" +
                    "Connection: close\r\n" +
                    "\r\n";
            out.println(request);

            // To Read and print the response
            String responseLine;
            while ((responseLine = in.readLine()) != null) {
                System.out.println("Response: " + responseLine);
            }
            System.out.println("Completed request for: " + requestUrl);

        } catch (IOException e) {
            System.err.println("Failed to send request to proxy: " + e.getMessage());
        }
    }
}