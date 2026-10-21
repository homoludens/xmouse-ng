package com.stripe1.xmouse.transport;

import com.stripe1.xmouse.secrets.AuthenticationMaterial;
import java.io.InputStream;
import java.io.OutputStream;

/** Blocking resource seam. Implementations and every resource method are used only on the SSH worker. */
public interface SshBackend {
    Session create(SshConnectionRequest request, AuthenticationMaterial authentication) throws Exception;

    interface Session {
        void connect() throws Exception;
        void clearAuthentication() throws Exception;
        Channel openChannel(String type) throws Exception;
        boolean isConnected();
        void close() throws Exception;
    }

    interface Channel {
        void command(String command) throws Exception;
        void output(OutputStream stdout, OutputStream stderr) throws Exception;
        OutputStream input() throws Exception;
        InputStream output() throws Exception;
        void connect(int timeoutMillis) throws Exception;
        boolean isClosed();
        void close() throws Exception;
    }
}
