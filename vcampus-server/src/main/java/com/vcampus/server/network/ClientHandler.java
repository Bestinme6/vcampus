package com.vcampus.server.network;

import com.vcampus.common.protocol.MessageCodec;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.server.service.RequestRouter;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;

final class ClientHandler implements Runnable {
    private final Socket socket;
    private final RequestRouter router;

    ClientHandler(Socket socket, RequestRouter router) {
        this.socket = socket;
        this.router = router;
    }

    @Override
    public void run() {
        String remoteAddress = socket.getRemoteSocketAddress().toString();
        try (socket;
             DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {
            while (!socket.isClosed()) {
                RequestMessage request;
                try {
                    request = MessageCodec.readRequest(input);
                } catch (EOFException closedByClient) {
                    break;
                }
                ResponseMessage response = router.route(request, remoteAddress);
                MessageCodec.writeResponse(output, response);
            }
        } catch (IOException exception) {
            System.err.printf("Client %s disconnected: %s%n", remoteAddress, exception.getMessage());
        }
    }
}
