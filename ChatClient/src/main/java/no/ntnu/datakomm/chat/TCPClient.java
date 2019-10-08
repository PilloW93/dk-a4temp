package no.ntnu.datakomm.chat;

import java.io.*;
import java.net.*;
import java.util.Arrays;
import java.util.EventListener;
import java.util.LinkedList;
import java.util.List;

public class TCPClient {

    private PrintWriter toServer;
    private BufferedReader fromServer;
    private Socket connection;


    // Hint: if you want to store a message for the last error, store it here
    private String lastError = null;

    private final List<ChatListener> listeners = new LinkedList<>();

    /**
     * Connect to a chat server.
     *
     * @param host host name or IP address of the chat server
     * @param port TCP port of the chat server
     * @return True on success, false otherwise
     */
    public boolean connect(String host, int port) {
        try {
            connection = new Socket();
            connection.connect( new InetSocketAddress(host, port));
            fromServer = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            toServer = new PrintWriter(connection.getOutputStream(), true);
            return true;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }

    /**
     * Close the socket. This method must be synchronized, because several
     * threads may try to call it. For example: When "Disconnect" button is
     * pressed in the GUI thread, the connection will get closed. Meanwhile, the
     * background thread trying to read server's response will get error in the
     * input stream and may try to call this method when the socket is already
     * in the process of being closed. with "synchronized" keyword we make sure
     * that no two threads call this method in parallel.
     */
    public synchronized void disconnect() {
        if(connection.isConnected()) {
            try {
                connection.close();
                connection = null;
                fromServer.close();
                fromServer = null;
                toServer.close();
                toServer = null;
                onDisconnect();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * @return true if the connection is active (opened), false if not.
     */
    public boolean isConnectionActive() {
        return connection != null;
    }

    /**
     * Send a command to server.
     *
     * @param cmd A command. It should include the command word and optional attributes, according to the protocol.
     * @return true on success, false otherwise
     */
    private boolean sendCommand(String cmd) {
        if(connection != null) {
            if(connection.isConnected()) {
                toServer.println(cmd);
                return true;
            }
        }
        return false;
    }

    /**
     * Send a public message to all the recipients.
     *
     * @param message Message to send
     * @return true if message sent, false on error
     */
    public boolean sendPublicMessage(String message) {
        if(sendCommand("msg " + message)) {
            return true;
        }
        else {
            return false;
        }
    }

    /**
     * Send a login request to the chat server.
     *
     * @param username Username to use
     */
    public void tryLogin(String username) {
        sendCommand("login " + username);
    }

    /**
     * Send a request for latest user list to the server. To get the new users,
     * clear your current user list and use events in the listener.
     */
    public void refreshUserList() {
        sendCommand("users");
    }

    /**
     * Send a private message to a single recipient.
     *
     * @param recipient username of the chat user who should receive the message
     * @param message   Message to send
     * @return true if message sent, false on error
     */
    public boolean sendPrivateMessage(String recipient, String message) {
        if(sendCommand("privmsg " + recipient + " " + message)) {
            return true;
        }
        else {
            return false;
        }
    }

    /**
     * Send a request for the list of commands that server supports.
     */
    public void askSupportedCommands() {
        sendCommand("help");
    }

    /**
     * Wait for chat server's response
     *
     * @return one line of text (one command) received from the server
     */
    private String waitServerResponse() {
        String responseLine;
        if(isConnectionActive()) {
            try {
                responseLine = fromServer.readLine();
                return responseLine;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        else {
            disconnect();
        }
        return null;
    }

    /**
     * Get the last error message
     *
     * @return Error message or "" if there has been no error
     */
    public String getLastError() {
        if (lastError != null) {
            return lastError;
        }
        else {
            return "";
        }
    }

    /**
     * Start listening for incoming commands from the server in a new CPU thread.
     */
    public void startListenThread() {
        // Call parseIncomingCommands() in the new thread.
        Thread t = new Thread(() -> {
            parseIncomingCommands();
        });
        t.start();
    }

    /**
     * Read incoming messages one by one, generate events for the listeners. A loop that runs until
     * the connection is closed.
     */
    private void parseIncomingCommands() {

        while (isConnectionActive()) {

            String[] tempFeedback = waitServerResponse().split(" ", 2);
            switch(tempFeedback[0]) {

                case "loginok":
                    onLoginResult(true,null);
                    break;

                case "loginerr":
                    onLoginResult(false, "error occurred");
                    break;

                case "users":
                    String tempList = tempFeedback[1];
                    String[] userList = tempList.split(" ");
                    onUsersList(userList);
                    break;

                case "msg":
                    boolean priv = false;
                    String pubFeedback = tempFeedback[1];
                    String[] tempPubInfo = pubFeedback.split(" ", 2);
                    String pubSender = tempPubInfo[0];
                    String pubMsg = tempPubInfo[1];
                    onMsgReceived(priv, pubSender, pubMsg);
                    break;

                case "privmsg":
                    boolean isPrivate = true;
                    String privFeedback = tempFeedback[1];
                    String[] tempInfo = privFeedback.split(" ", 2);
                    String privSender = tempInfo[0];
                    String privMsg = tempInfo[1];
                    onMsgReceived(isPrivate, privSender, privMsg);
                    break;

                case "msgerr":
                    String tempErr = tempFeedback[1];
                    onMsgError(tempErr);
                    break;

                case "cmderr":
                    String tempCmderr = tempFeedback[1];
                    onCmdError(tempCmderr);
                    break;

                case "supported":
                    String feedback = tempFeedback[1];
                    String[] helpCommands = feedback.split(" ");
                    onSupported(helpCommands);
                    break;

                default:
                    System.out.println("Default-case trigger.");
                    break;
            }
        }
    }

    /**
     * Register a new listener for events (login result, incoming message, etc)
     *
     * @param listener
     */
    public void addListener(ChatListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * Unregister an event listener
     *
     * @param listener
     */
    public void removeListener(ChatListener listener) {
        listeners.remove(listener);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////////////////////
    // The following methods are all event-notificators - notify all the listeners about a specific event.
    // By "event" here we mean "information received from the chat server".
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Notify listeners that login operation is complete (either with success or
     * failure)
     *
     * @param success When true, login successful. When false, it failed
     * @param errMsg  Error message if any
     */
    private void onLoginResult(boolean success, String errMsg) {
        for (ChatListener l : listeners) {
            l.onLoginResult(success, errMsg);
        }
    }

    /**
     * Notify listeners that socket was closed by the remote end (server or
     * Internet error)
     */
    private void onDisconnect() {
        for(ChatListener cl : listeners) {
            cl.onDisconnect();
        }
    }

    /**
     * Notify listeners that server sent us a list of currently connected users
     *
     * @param users List with usernames
     */
    private void onUsersList(String[] users) {
        for(ChatListener cl : listeners) {
            cl.onUserList(users);
        }
    }

    /**
     * Notify listeners that a message is received from the server
     *
     * @param priv   When true, this is a private message
     * @param sender Username of the sender
     * @param text   Message text
     */
    private void onMsgReceived(boolean priv, String sender, String text) {
        TextMessage msg = new TextMessage(sender, priv, text);
        for(ChatListener cl : listeners) {
            cl.onMessageReceived(msg);
        }
    }

    /**
     * Notify listeners that our message was not delivered
     *
     * @param errMsg Error description returned by the server
     */
    private void onMsgError(String errMsg) {
        for(ChatListener cl : listeners) {
            cl.onMessageError(errMsg);
        }
    }

    /**
     * Notify listeners that command was not understood by the server.
     *
     * @param errMsg Error message
     */
    private void onCmdError(String errMsg) {
        for(ChatListener cl : listeners) {
            cl.onCommandError(errMsg);
        }
    }

    /**
     * Notify listeners that a help response (supported commands) was received
     * from the server
     *
     * @param commands Commands supported by the server
     */
    private void onSupported(String[] commands) {
        for(ChatListener cl : listeners) {
            cl.onSupportedCommands(commands);
        }
    }
}
