package com.client;

import java.io.*;
import java.net.*;

import java.util.concurrent.*;

public class ProxyClient {
    private String serverHost = "127.0.0.1";
    private int  serverPort = 8080;
    private LinkedBlockingQueue<String> requestQueue = new LinkedBlockingQueue<>();
    private int clientPort = 8081;


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