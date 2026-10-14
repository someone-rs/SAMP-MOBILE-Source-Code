package com.sampmobile.fork.data;

/** A single favorite server entry. */
public class ServerEntry {
    public String name;
    public String host;
    public int port;

    public ServerEntry(String name, String host, int port) {
        this.name = name == null ? "" : name;
        this.host = host == null ? "" : host;
        this.port = port;
    }

    public String address() {
        return host + ":" + port;
    }

    @Override
    public String toString() {
        return name + " (" + address() + ")";
    }
}
