/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.network;

import org.jboss.msc.service.ServiceName;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;

/**
 * A client socket binding represents the client end of a socket. It represents binding from a local "host"
 * to a remote "host". In some special cases the remote host can itself be the same local host.
 * Unlike the {@link SocketBinding} which represents a {@link java.net.ServerSocket} that opens a socket for "listening",
 * the {@link ClientSocketBinding} represents a {@link Socket} which "connects" to a remote/local host
 *
 * @author Jaikiran Pai
 */
public class ClientSocketBinding {

    public static final ServiceName CLIENT_SOCKET_BINDING_BASE_SERVICE_NAME = ServiceName.JBOSS.append("client-socket-binding");

    private final String name;
    private final SocketBindingManager socketBindingManager;
    private final boolean fixedSourcePort;
    private final NetworkInterfaceBinding sourceNetworkInterface;
    private final Integer sourcePort;
    private final InetAddress destinationAddress;
    private final int destinationPort;

    /**
     * Creates a client socket binding
     *
     * @param name                   Name of the client socket binding
     * @param socketBindingManager   The socket binding manager
     * @param destinationAddress     The destination address to which this socket will be "connected". Cannot be null.
     * @param destinationPort        The destination port. Cannot be < 0.
     * @param sourceNetworkInterface (Optional) source network interface which will be used as the "source" of the socket binding
     * @param sourcePort             (Optional) source port. Cannot be null or < 0
     * @param fixedSourcePort        True if the <code>sourcePort</code> has to be used as a fixed port number. False if the <code>sourcePort</code>
     *                               will be added to the port offset while determining the absolute source port.
     */
    public ClientSocketBinding(final String name, final SocketBindingManager socketBindingManager,
                               final InetAddress destinationAddress, final int destinationPort,
                               final NetworkInterfaceBinding sourceNetworkInterface, final Integer sourcePort,
                               final boolean fixedSourcePort) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Socket name cannot be null or an empty string");
        }
        if (socketBindingManager == null) {
            throw new IllegalArgumentException("SocketBindingManager cannot be null for client socket binding " + name);
        }
        if (destinationAddress == null) {
            throw new IllegalArgumentException("Destination address cannot be null for client socket binding " + name);
        }
        if (destinationPort < 0) {
            throw new IllegalArgumentException("Destination port cannot be a negative value: " + destinationPort
                    + " for client socket binding " + name);
        }
        this.name = name;
        this.socketBindingManager = socketBindingManager;
        this.destinationAddress = destinationAddress;
        this.destinationPort = destinationPort;
        this.sourceNetworkInterface = sourceNetworkInterface;
        this.sourcePort = sourcePort;
        this.fixedSourcePort = fixedSourcePort;
    }

    /**
     * Creates a {@link Socket} represented by this {@link ClientSocketBinding} and connects to the
     * destination
     *
     * @return
     * @throws IOException
     */
    public Socket connect() throws IOException {
        final Socket socket = this.createSocket();
        final InetAddress destinationAddress = this.getDestinationAddress();
        final int destinationPort = this.getDestinationPort();
        final SocketAddress destination = new InetSocketAddress(destinationAddress, destinationPort);
        socket.connect(destination);

        return socket;
    }

    public InetAddress getDestinationAddress() {
        return this.destinationAddress;
    }

    public int getDestinationPort() {
        return this.destinationPort;
    }

    public boolean isFixedSourcePort() {
        return this.fixedSourcePort;
    }

    /**
     * Returns the source address of this client socket binding. If no explicit source address is specified
     * for this binding, then this method returns the address of the default interface that's configured
     * for the socket binding group
     *
     * @return
     */
    public InetAddress getSourceAddresss() {
        return this.sourceNetworkInterface != null ? this.sourceNetworkInterface.getAddress() : this.socketBindingManager.getDefaultInterfaceAddress();
    }

    /**
     * The source port for this client socket binding. Note that this isn't the "absolute" port if the
     * this client socket binding has a port offset. To get the absolute source port, use the {@link #getAbsoluteSourcePort()}
     * method
     *
     * @return
     */
    public Integer getSourcePort() {
        return this.sourcePort;
    }

    /**
     * The absolute source port for this client socket binding. The absolute source port is the same as {@link #getSourcePort()}
     * if the client socket binding is marked for "fixed source port". Else, it is the sum of {@link #getSourcePort()}
     * and the port offset configured on the {@link SocketBindingManager}
     *
     * @return
     */
    Integer getAbsoluteSourcePort() {
        if (this.sourcePort == null) {
            return null;
        }
        if (this.fixedSourcePort) {
            return this.sourcePort;
        }
        final int portOffset = this.socketBindingManager.getPortOffset();
        return this.sourcePort + portOffset;
    }

    /**
     * Closes the client socket binding connection
     *
     * @throws IOException
     */
    public void close() throws IOException {
        final ManagedBinding binding = this.socketBindingManager.getNamedRegistry().getManagedBinding(this.name);
        if (binding == null) {
            return;
        }
        binding.close();
    }

    /**
     * Returns true if a socket connection has been established by this client socket binding. Else returns false
     *
     * @return
     */
    public boolean isConnected() {
        return this.socketBindingManager.getNamedRegistry().getManagedBinding(this.name) != null;
    }

    // At this point, don't really expose this createSocket() method and let's just expose
    // the connect() method, since the caller can actually misuse the returned Socket
    // to connect any random destination address/port.
    private Socket createSocket() throws IOException {
        final ManagedSocketFactory socketFactory = this.socketBindingManager.getSocketFactory();
        final Socket socket = socketFactory.createSocket(this.name);
        // if the client binding specifies the source to use, then bind this socket to the
        // appropriate source
        final SocketAddress sourceSocketAddress = this.getOptionalSourceSocketAddress();
        if (sourceSocketAddress != null) {
            socket.bind(sourceSocketAddress);
        }
        return socket;
    }

    private SocketAddress getOptionalSourceSocketAddress() {
        final InetAddress sourceAddress = this.getSourceAddresss();
        final Integer absoluteSourcePort = this.getAbsoluteSourcePort();
        if (sourceAddress == null && absoluteSourcePort == null) {
            return null;
        }
        if (sourceAddress == null) {
            return new InetSocketAddress(absoluteSourcePort);
        }
        return new InetSocketAddress(sourceAddress, absoluteSourcePort);
    }

}