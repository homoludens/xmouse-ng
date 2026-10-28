package net.droopia.xmouse_ng;

/**
 * Created by bradley on 12/18/15.
 */
public class HostItem {
    private String Alias;
    private String Username;
    private String IP;
    private net.droopia.xmouse_ng.secrets.CredentialHost credentialHost;

    public Integer getDbId() {
        return dbId;
    }

    public void setDbId(Integer dbId) {
        this.dbId = dbId;
    }

    private Integer dbId;

    public String getAlias() {
        return Alias;
    }

    public void setAlias(String alias) {
        Alias = alias;
    }

    public String getUsername() {
        return Username;
    }

    public void setUsername(String username) {
        Username = username;
    }

    public String getIP() {
        return IP;
    }

    public void setIP(String IP) {
        this.IP = IP;
    }

    public net.droopia.xmouse_ng.secrets.CredentialHost getCredentialHost() {
        return credentialHost;
    }

    public void setCredentialHost(net.droopia.xmouse_ng.secrets.CredentialHost host) {
        credentialHost = host;
    }

    public String getPort() {
        return Port;
    }

    public void setPort(String port) {
        Port = port;
    }

    private String Port;
}
