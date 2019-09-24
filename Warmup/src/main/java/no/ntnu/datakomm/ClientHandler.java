package no.ntnu.datakomm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class ClientHandler extends Thread {

    private final Socket clientSocket;

    public ClientHandler(Socket socket) {
        this.clientSocket = socket;
    }

    @Override
    public void run() {
        try {
            boolean keepRunning = true;
            while (keepRunning) {
                InputStreamReader reader = new InputStreamReader(clientSocket.getInputStream());
                BufferedReader buffReader = new BufferedReader(reader);

                // Read the actual input from the client and print it out
                String inputFromClient = buffReader.readLine();
                System.out.println("Server received: " + inputFromClient);

                if (inputFromClient.equals("game over")) {
                    keepRunning = false;
                }
                String responseToClient = null;
                try {
                    String [] strArray = inputFromClient.split("\\+");
                    int a = Integer.parseInt(strArray[0]);
                    int b = Integer.parseInt(strArray[1]);
                    int sum = a+b;
                    responseToClient = Integer.toString(sum);
                }
                catch (NumberFormatException nfe) {
                    responseToClient = "ERROR";
                }
                // Send the response to the client
                PrintWriter printWriter = new PrintWriter(clientSocket.getOutputStream(), true);
                System.out.println("Server sent back: " + responseToClient);
                printWriter.println(responseToClient);
            }
            clientSocket.close();
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }


}
