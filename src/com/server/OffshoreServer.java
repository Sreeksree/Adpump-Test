package com.server;

import java.io.*;
import java.lang.classfile.attribute.SourceDebugExtensionAttribute;
import java.net.*;

public class OffshoreServer {
    private ServerSocket serverSocket;
    private int port = 8080;
    private PrintWriter out;
    private BufferedReader in;
    private Socket clientSocket;

    public OffshoreServer() {
        try {
            serverSocket = new ServerSocket(port);
            System.out.println("Offshore Proxy Server started on port " + port);
            clientSocket = serverSocket.accept();
            out = new PrintWriter(clientSocket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            System.out.println("Established persistant connection");
            startListening();
        } catch (IOException e) {
            System.err.println("Could not listen on port " + port + ": " + e.getMessage());
        }
    }

    private void startListening() {
        while (true) {
            try (Socket clientSocket = serverSocket.accept();
                 PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);
                 BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()))) {

                String inputLine;
                StringBuilder request = new StringBuilder();
                while ((inputLine = in.readLine()) != null && !inputLine.isEmpty()) {
                    request.append(inputLine).append("\n");
                }
                System.out.println("Received request: " + request.toString());


                String response = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/plain\r\n" +
                        "Content-Length: 12\r\n" +
                        "\r\n" +
                        "Request OK\n";
                out.println(response);
                out.flush();

            } catch (IOException e) {
                System.err.println("Error handling client: " + e.getMessage());
            }
        }
    }
}
