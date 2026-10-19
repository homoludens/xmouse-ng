## Purpose

Let users connect to Linux computers by stable hostname or IP address and find advertised local SSH services without relying on fixed DHCP addresses.

## ADDED Requirements

### Requirement: Manual hostnames and IP addresses
Host entry SHALL accept IPv4 literals, IPv6 literals, ordinary DNS names, and `.local` names, with the SSH port entered separately. Saving and editing a host SHALL preserve its user-specified endpoint name rather than replacing it with a resolved IP address. Blank hosts and ports outside 1 through 65535 SHALL be rejected without overwriting valid settings.

#### Scenario: Save and connect to a local hostname
- **WHEN** the user saves `desktop.local` with a valid username and SSH port
- **THEN** the profile SHALL retain `desktop.local`
- **AND** connection attempts SHALL resolve that name on the current local network

#### Scenario: Manual endpoint without discovery
- **WHEN** the user supplies a valid ordinary DNS name, IPv4 literal, or IPv6 literal for a reachable SSH server
- **THEN** the application SHALL allow connection independently of service advertisements

### Requirement: Fresh, bounded address resolution
Each new connection attempt SHALL resolve a saved hostname using the current network, support available IPv4/IPv6 address candidates, and bound resolution and connection waiting. Cancelling an attempt or changing hosts SHALL prevent late results from connecting the wrong endpoint. Multiple address candidates SHALL NOT create duplicate active sessions or bypass host-key checks.

#### Scenario: DHCP address changes
- **WHEN** `desktop.local` changes address between connection attempts
- **THEN** the next attempt SHALL use newly resolved addresses rather than a previously saved DHCP address

#### Scenario: First address unavailable or attempt cancelled
- **WHEN** one resolved address is unreachable or the user cancels while resolution is pending
- **THEN** the application SHALL either try another current candidate within the bounded attempt or complete cancellation
- **AND** a late resolver result SHALL NOT establish an unwanted session

### Requirement: On-demand SSH service discovery
The application SHALL provide an explicit Find computers action that lists locally advertised `_ssh._tcp` services with their display names and advertised ports. Results SHALL be deduplicated by service identity within their network and updated when advertisements disappear. Discovery SHALL stop when the discovery interface is closed or backgrounded, without stopping an established SSH session.

#### Scenario: Discover an advertised SSH service
- **WHEN** a Linux computer advertises `_ssh._tcp` on the same reachable multicast network
- **THEN** Find computers SHALL display that service and its advertised port
- **AND** selecting it SHALL populate a connection candidate without executing remote commands or supplying a fabricated username/password

#### Scenario: Service disappears or discovery is closed
- **WHEN** an advertised service disappears or the user leaves discovery
- **THEN** the result SHALL be removed or discovery SHALL stop as appropriate
- **AND** an existing SSH connection SHALL remain independent of discovery

### Requirement: Discovery does not establish trust or replace manual entry
Users SHALL explicitly choose whether to save a discovered candidate. Discovered endpoints SHALL use the same credential and host-key approval rules as manual endpoints. The application SHALL retain manual hostname/IP entry when discovery is empty or unavailable and explain that running SSH alone does not guarantee a service advertisement.

#### Scenario: SSH server is not advertised
- **WHEN** no advertised SSH services are found
- **THEN** the interface SHALL explain the advertisement/network limitation and offer manual entry
- **AND** it SHALL NOT report that no SSH-capable computers exist

#### Scenario: Select an untrusted discovered computer
- **WHEN** the user selects a discovered service and requests connection
- **THEN** authentication SHALL remain blocked until the host-key trust requirements are satisfied

### Requirement: Actionable network failure categories
Resolution failure, network-permission denial, unreachable/refused SSH endpoints, host-key rejection, and authentication failure SHALL be distinguishable. The application SHALL retain the configured endpoint after failure and allow a deliberate retry.

#### Scenario: Name resolution fails
- **WHEN** the requested `.local` name cannot be resolved on the active network
- **THEN** the application SHALL report a name-resolution failure with same-network/manual-address guidance
- **AND** it SHALL NOT label the failure as an incorrect SSH password
