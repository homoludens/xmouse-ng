package net.droopia.xmouse_ng.connection;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Real Java callers, without Kotlin reflection, Android UI, a resolver, or an SSH implementation. */
public class ConnectionDomainJavaInteropTest {
    @Test
    public void manualFactoriesExposeOrdinaryJavaGetters() {
        Endpoint endpoint = Endpoint.manual("Desktop.Local.", "0022");
        assertEquals(22, Endpoint.parsePort("22"));
        assertEquals(22, endpoint.getPort());
        assertEquals(Endpoint.manual("desktop.local", 22).getIdentity(), endpoint.getIdentity());
        EndpointHost host = ((EndpointLocator.Manual) endpoint.getLocator()).getHost();
        assertEquals("Desktop.Local.", host.getOriginal());
        assertEquals("desktop.local", host.getNormalized());
        assertEquals("desktop.local.", host.getLookupHost());
    }

    @Test
    public void addressOnlyServiceLocatorHasJavaConstructorWithoutOptionalTarget() {
        EndpointLocator.DiscoveredService locator =
                new EndpointLocator.DiscoveredService("Office", "_ssh._tcp.", "local.");
        assertNull(locator.getStableTargetHostname());
        EndpointIdentity.Service identity = (EndpointIdentity.Service) new Endpoint(locator, 2222).getIdentity();
        assertEquals("office", identity.getInstanceName());
        assertEquals("_ssh._tcp", identity.getServiceType());
        assertEquals("local", identity.getDomain());
        assertEquals(2222, identity.getPort());
    }

    @Test
    public void javaCanRenderSealedStatesThroughBaseGettersAndTypedFailures() {
        EndpointIdentity endpoint = Endpoint.manual("[2001:db8::1]", 22).getIdentity();
        ConnectionFailure failure = new ConnectionFailure.Trust(ConnectionFailure.TrustReason.KEY_MISMATCH);
        ConnectionState state = new ConnectionState.Error(endpoint, failure);
        assertEquals(ConnectionState.Phase.ERROR, state.getPhase());
        assertEquals(endpoint, state.getEndpoint());
        assertEquals(ConnectionFailure.Category.TRUST, state.getFailure().getCategory());
        assertFalse(state.isReady());
        assertTrue(new ConnectionState.Connected(endpoint).isReady());
        assertNull(new ConnectionState.Disconnected().getEndpoint());
    }
}
