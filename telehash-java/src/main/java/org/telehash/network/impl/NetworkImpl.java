package org.telehash.network.impl;

import org.telehash.core.TelehashException;
import org.telehash.network.InetPath;
import org.telehash.network.Network;
import org.telehash.network.Path;
import org.telehash.network.Reactor;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;

/**
 * This class contains implementations for the network operations needed by
 * Telehash.
 */
public class NetworkImpl implements Network {

    /**
     * Parse a string representing a network address.
     *
     * TODO: we shouldn't need this... why is "see" hard-coded for IP addressing in the protocol?
     *
     * @param addressString
     *            The path string to parse.
     * @return The network path object.
     * @throws TelehashException
     *             If a problem occurred while parsing the path.
     */
    @Override
    public Path parsePath(String addressString, int port) throws TelehashException {
        InetAddress address;
        try {
            address = InetAddress.getByName(addressString);
        } catch (UnknownHostException e) {
            throw new TelehashException("invalid address or unknown host in path");
        }
        return new InetPath(address, port);
    }

    /**
     * Get preferred local path
     * TODO: This will certainly change... we need to support multiple network interfaces!
     */
    @Override
    public Path getPreferredLocalPath() throws TelehashException {
        Enumeration<NetworkInterface> networkInterfaces;
        try {
            networkInterfaces = NetworkInterface.getNetworkInterfaces();
        } catch (SocketException e) {
            throw new TelehashException(e);
        }
        while (networkInterfaces.hasMoreElements()) {
            NetworkInterface networkInterface = networkInterfaces.nextElement();
            Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
            while (inetAddresses.hasMoreElements()) {
                InetAddress inetAddress = inetAddresses.nextElement();
                if (inetAddress.isLoopbackAddress() || inetAddress.isLinkLocalAddress()) {
                    continue;
                }

                // TODO: restrict to ipv4 for now, but must eventually support ipv6.
                // (the whole idea of a "preferred" network interface is temporary, anyway --
                // eventually all non-localhost addresses will be used, both IPv4 and IPv6.
                if (inetAddress.getAddress().length != 4) {
                    continue;
                }

                return new InetPath(inetAddress, 0);
            }
        }
        return null;
    }

    /**
     * Provision a new reactor i/o engine listening on the specified port.
     *
     * @param port The IP port on which to listen.
     * @return The reactor.
     */
    @Override
    public Reactor createReactor(int port) {
        return new ReactorImpl(port);
    }

}
