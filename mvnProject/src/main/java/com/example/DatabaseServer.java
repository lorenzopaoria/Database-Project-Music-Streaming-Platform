package com.example;

import com.example.dao.UserDAO;
import com.example.factory.DatabaseFactory;

import java.io.*;
import java.net.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DatabaseServer {
    private static final int PORT = 12345;
    private static final int THREAD_POOL_SIZE = 10;
    private static final Logger LOGGER = Logger.getLogger(DatabaseServer.class.getName());

    public static void main(String[] args) {
        ExecutorService threadPool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            LOGGER.info("Server listening on port " + PORT);

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    LOGGER.info("Connection received from client");

                    threadPool.submit(new ClientHandler(clientSocket));
                } catch (IOException e) {
                    LOGGER.log(Level.SEVERE, "Error accepting client connection", e);
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Server error", e);
        } finally {
            threadPool.shutdown();
        }
    }
}

class ClientHandler implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(ClientHandler.class.getName());
    private final Socket clientSocket;

    public ClientHandler(Socket clientSocket) {
        this.clientSocket = clientSocket;
    }

    @Override
    public void run() {
        Connection conn = null;
        try {
            // Genera un ID utente casuale
            String userId = generateRandomUserId();

            // Create output stream first
            OutputStream os = clientSocket.getOutputStream();
            ObjectOutputStream output = new ObjectOutputStream(os);
            output.flush(); // Flush the stream header

            // Then create input stream
            InputStream is = clientSocket.getInputStream();
            ObjectInputStream input = new ObjectInputStream(is);

            // Establish database connection
            conn = DatabaseFactory.getConnection();

            LOGGER.info("Handling client connection");

            // Authentication
            String email = (String) input.readObject();
            String password = (String) input.readObject();

            UserDAO userDAO = new UserDAO(conn);
            String role = userDAO.authenticate(email, password);

            if (role == null) {
                output.writeObject("Authentication failed!");
                output.flush();
                return;
            }

            // Messaggio di benvenuto con ID utente
            String welcomeMessage = String.format("Authentication successful! Welcome, %s (User ID: %s)", role, userId);
            output.writeObject(welcomeMessage);
            output.flush();

            LOGGER.info(String.format("User %s (%s) authenticated with role %s", email, userId, role));

            // Query processing loop
            while (!Thread.currentThread().isInterrupted()) {
                String query = (String) input.readObject();
                LOGGER.info(String.format("User %s (%s) sent query: %s", email, userId, query));

                if ("exit".equalsIgnoreCase(query)) {
                    output.writeObject("Exiting...");
                    output.flush();
                    break;
                }

                if (AccessControl.isAllowed(role, query)) {
                    try {
                        String result = userDAO.executeQuery(query);
                        output.writeObject(result);
                        output.flush();
                    } catch (SQLException e) {
                        LOGGER.log(Level.SEVERE, "Error executing query", e);
                        output.writeObject("Query execution error: " + e.getMessage());
                        output.flush();
                    }
                } else {
                    output.writeObject("Access denied: insufficient permissions.");
                    output.flush();
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            LOGGER.log(Level.SEVERE, "Client handling error", e);
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Database connection error", e);
        } finally {
            // Close connection explicitly
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    LOGGER.log(Level.SEVERE, "Error closing database connection", e);
                }
            }

            // Close socket
            try {
                if (!clientSocket.isClosed()) {
                    clientSocket.close();
                }
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Error closing client socket", e);
            }
        }
    }

    // Metodo per generare un ID utente casuale
    private String generateRandomUserId() {
        // Usa UUID per generare un ID univoco
        return UUID.randomUUID().toString().substring(0, 8);
    }
}