package org.aio.tcp.samples;

import org.aio.tcp.TcpServer;

public class TcpServerMain {
    public static void main(String [] args) {
        TcpServer tcpServer = TcpServer.newTcpServer();
        tcpServer.start();
    }
}
