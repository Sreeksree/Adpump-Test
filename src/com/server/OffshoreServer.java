package com.server;

import java.io.*;
import java.net.*;

public class OffshoreServer {
    private ServerSocket serverSocket;
    private int port = 8080;
    private PrintWriter out;
    private BufferedReader in;
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
            System.out.println("Established persistent connection with proxy client");
            startListening();
        } catch (IOException e) {
            System.err.println("Could not listen on port " + port + ": " + e.getMessage());
        }
    }

    private void startListening() {
        while (true) {
            try {
                String inputLine;
                StringBuilder request = new StringBuilder();
                while ((inputLine = in.readLine()) != null && !inputLine.isEmpty()) {
                    request.append(inputLine).append("\n");
                }
                if (request.length() == 0) {
                    System.out.println("No more data from proxy client. Closing connection.");
                    break;
                }
                System.out.println("Received request: " + request.toString());

                String response = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/plain\r\n" +
                        "Content-Length: 12\r\n" +
                        "Connection: keep-alive\r\n" +
                        "\r\n" +
                        "Request OK\n";
                out.println(response);
                out.flush();

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
}