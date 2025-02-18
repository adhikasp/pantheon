/*
 * Copyright 2019 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package tech.pegasys.pantheon.ethereum.p2p.rlpx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static tech.pegasys.pantheon.ethereum.p2p.peers.PeerTestHelper.createMutableLocalNode;
import static tech.pegasys.pantheon.ethereum.p2p.peers.PeerTestHelper.createPeer;
import static tech.pegasys.pantheon.ethereum.p2p.peers.PeerTestHelper.enode;
import static tech.pegasys.pantheon.ethereum.p2p.peers.PeerTestHelper.enodeBuilder;

import tech.pegasys.pantheon.crypto.SECP256K1.KeyPair;
import tech.pegasys.pantheon.ethereum.p2p.config.RlpxConfiguration;
import tech.pegasys.pantheon.ethereum.p2p.discovery.DiscoveryPeer;
import tech.pegasys.pantheon.ethereum.p2p.peers.DefaultPeer;
import tech.pegasys.pantheon.ethereum.p2p.peers.EnodeURL;
import tech.pegasys.pantheon.ethereum.p2p.peers.MutableLocalNode;
import tech.pegasys.pantheon.ethereum.p2p.peers.Peer;
import tech.pegasys.pantheon.ethereum.p2p.peers.PeerProperties;
import tech.pegasys.pantheon.ethereum.p2p.peers.PeerTestHelper;
import tech.pegasys.pantheon.ethereum.p2p.permissions.PeerPermissions;
import tech.pegasys.pantheon.ethereum.p2p.permissions.PeerPermissions.Action;
import tech.pegasys.pantheon.ethereum.p2p.permissions.PeerPermissionsException;
import tech.pegasys.pantheon.ethereum.p2p.rlpx.connections.MockConnectionInitializer;
import tech.pegasys.pantheon.ethereum.p2p.rlpx.connections.MockPeerConnection;
import tech.pegasys.pantheon.ethereum.p2p.rlpx.connections.PeerConnection;
import tech.pegasys.pantheon.ethereum.p2p.rlpx.connections.PeerConnection.PeerNotConnected;
import tech.pegasys.pantheon.ethereum.p2p.rlpx.connections.PeerConnectionEvents;
import tech.pegasys.pantheon.ethereum.p2p.rlpx.wire.Capability;
import tech.pegasys.pantheon.ethereum.p2p.rlpx.wire.messages.DisconnectMessage.DisconnectReason;
import tech.pegasys.pantheon.ethereum.p2p.rlpx.wire.messages.PingMessage;
import tech.pegasys.pantheon.metrics.MetricsSystem;
import tech.pegasys.pantheon.metrics.noop.NoOpMetricsSystem;
import tech.pegasys.pantheon.util.bytes.BytesValue;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.Before;
import org.junit.Test;

public class RlpxAgentTest {
  private static final KeyPair KEY_PAIR = KeyPair.generate();
  private final RlpxConfiguration config = RlpxConfiguration.create();
  private final TestPeerPermissions peerPermissions = spy(new TestPeerPermissions());
  private final PeerProperties peerProperties = mock(PeerProperties.class);
  private final MutableLocalNode localNode = createMutableLocalNode();
  private final MetricsSystem metrics = new NoOpMetricsSystem();
  private final PeerConnectionEvents peerConnectionEvents = new PeerConnectionEvents(metrics);
  private final MockConnectionInitializer connectionInitializer =
      new MockConnectionInitializer(peerConnectionEvents);
  private RlpxAgent agent = agent();

  @Before
  public void setup() {
    // Set basic defaults
    when(peerProperties.ignoreMaxPeerLimits(any())).thenReturn(false);
    config.setMaxPeers(5);
  }

  @Test
  public void start() {
    // Initial call to start should work
    final CompletableFuture<Integer> future = agent.start();
    assertThat(future).isDone();
    assertThat(future).isNotCompletedExceptionally();

    // Subsequent call to start should fail
    final CompletableFuture<Integer> future2 = agent.start();
    assertThat(future2).isDone();
    assertThat(future2).isCompletedExceptionally();
    assertThatThrownBy(future2::get)
        .hasCauseInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Unable to start an already started");
  }

  @Test
  public void stop() throws ExecutionException, InterruptedException {
    // Cannot stop before starting
    final CompletableFuture<Void> future = agent.stop();
    assertThat(future).isDone();
    assertThat(future).isCompletedExceptionally();
    assertThatThrownBy(future::get)
        .hasCauseInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Illegal attempt to stop");

    // Stop after starting should succeed
    startAgent();
    final MockPeerConnection connection = (MockPeerConnection) agent.connect(createPeer()).get();
    final CompletableFuture<Void> future2 = agent.stop();
    assertThat(future2).isDone();
    assertThat(future2).isNotCompletedExceptionally();
    // Check peer was disconnected
    assertThat(connection.getDisconnectReason()).contains(DisconnectReason.CLIENT_QUITTING);

    // Cannot stop again
    final CompletableFuture<Void> future3 = agent.stop();
    assertThat(future3).isDone();
    assertThat(future3).isCompletedExceptionally();
    assertThatThrownBy(future3::get)
        .hasCauseInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Illegal attempt to stop");
  }

  @Test
  public void connect_succeeds() {
    startAgent();
    final Peer peer = createPeer();
    final CompletableFuture<PeerConnection> connection = agent.connect(peer);

    assertThat(connection).isDone();
    assertThat(connection).isNotCompletedExceptionally();

    assertThat(agent.getPeerConnection(peer)).contains(connection);
  }

  @Test
  public void connect_fails() {
    connectionInitializer.setAutocompleteConnections(false);
    startAgent();
    final Peer peer = createPeer();
    final CompletableFuture<PeerConnection> connection = agent.connect(peer);

    // Fail connection
    connection.completeExceptionally(new RuntimeException("whoopsies"));

    assertPeerConnectionNotTracked(peer);
  }

  @Test
  public void connect_toDiscoveryPeerUpdatesStats() {
    startAgent();
    final DiscoveryPeer peer = DiscoveryPeer.fromEnode(enode());
    assertThat(peer.getLastAttemptedConnection()).isEqualTo(0);
    final CompletableFuture<PeerConnection> connection = agent.connect(peer);

    assertThat(connection).isDone();
    assertThat(connection).isNotCompletedExceptionally();
    assertThat(peer.getLastAttemptedConnection()).isGreaterThan(0);
  }

  @Test
  public void incomingConnect_succeeds() {
    startAgent();
    final Peer peer = createPeer();
    final PeerConnection connection = connection(peer);
    connectionInitializer.simulateIncomingConnection(connection);

    final Optional<CompletableFuture<PeerConnection>> trackedConnection =
        agent.getPeerConnection(peer);
    assertThat(trackedConnection).isNotEmpty();
    assertThat(trackedConnection.get()).isCompletedWithValue(connection);
  }

  @Test
  public void connect_failsIfAgentIsNotReady() {
    // Don't start agent or set localNode
    final Peer peer = createPeer();
    final CompletableFuture<PeerConnection> connection = agent.connect(peer);

    assertThat(connection).isDone();
    assertThat(connection).isCompletedExceptionally();

    assertThatThrownBy(connection::get)
        .hasCauseInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Cannot connect before")
        .hasMessageContaining("has finished starting");
    assertPeerConnectionNotTracked(peer);
  }

  @Test
  public void incomingConnect_failsIfAgentIsNotReady() {
    // Don't start agent or set localNode
    final Peer peer = createPeer();
    final PeerConnection connection = connection(peer);
    connectionInitializer.simulateIncomingConnection(connection);

    assertPeerConnectionNotTracked(peer);
    assertThat(agent.getConnectionCount()).isEqualTo(0);
  }

  @Test
  public void connect_failsIfPeerIsNotListening() {
    startAgent();
    final Peer peer = DefaultPeer.fromEnodeURL(enodeBuilder().disableListening().build());
    final CompletableFuture<PeerConnection> connection = agent.connect(peer);

    assertThat(connection).isDone();
    assertThat(connection).isCompletedExceptionally();

    assertThatThrownBy(connection::get)
        .hasCauseInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Attempt to connect to peer with no listening port");
    assertPeerConnectionNotTracked(peer);
  }

  @Test
  public void connect_doesNotCreateDuplicateConnections() {
    startAgent();
    final Peer peer = createPeer();
    final CompletableFuture<PeerConnection> connection1 = agent.connect(peer);
    final CompletableFuture<PeerConnection> connection2 = agent.connect(peer);

    assertThat(connection2).isDone();
    assertThat(connection2).isNotCompletedExceptionally();
    assertThat(connection2).isEqualTo(connection1);

    assertThat(agent.getPeerConnection(peer)).contains(connection2);
    assertThat(agent.getConnectionCount()).isEqualTo(1);
  }

  @Test
  public void incomingConnection_deduplicatedWhenAlreadyConnected_peerWithHigherValueNodeId() {
    final BytesValue localNodeId = BytesValue.fromHexString("0x01", EnodeURL.NODE_ID_SIZE);
    final BytesValue remoteNodeId = BytesValue.fromHexString("0x02", EnodeURL.NODE_ID_SIZE);

    startAgent(localNodeId);

    final Peer peer = createPeer(remoteNodeId);
    final CompletableFuture<PeerConnection> existingConnection = agent.connect(peer);
    final MockPeerConnection incomingConnection = connection(peer);
    connectionInitializer.simulateIncomingConnection(incomingConnection);

    // Existing connection should be kept
    assertThat(agent.getPeerConnection(peer)).contains(existingConnection);
    assertThat(agent.getConnectionCount()).isEqualTo(1);
    assertThat(incomingConnection.isDisconnected()).isTrue();
    assertThat(incomingConnection.getDisconnectReason())
        .contains(DisconnectReason.ALREADY_CONNECTED);
  }

  @Test
  public void incomingConnection_deduplicatedWhenAlreadyConnected_peerWithLowerValueNodeId()
      throws ExecutionException, InterruptedException {
    final BytesValue localNodeId = BytesValue.fromHexString("0x02", EnodeURL.NODE_ID_SIZE);
    final BytesValue remoteNodeId = BytesValue.fromHexString("0x01", EnodeURL.NODE_ID_SIZE);

    startAgent(localNodeId);

    final Peer peer = createPeer(remoteNodeId);
    final CompletableFuture<PeerConnection> existingConnection = agent.connect(peer);
    final PeerConnection incomingConnection = connection(peer);
    connectionInitializer.simulateIncomingConnection(incomingConnection);

    // New connection should be kept
    assertThat(agent.getPeerConnection(peer).get().get()).isEqualTo(incomingConnection);
    assertThat(agent.getConnectionCount()).isEqualTo(1);
    assertThat(existingConnection.get().isDisconnected()).isTrue();
    assertThat(((MockPeerConnection) existingConnection.get()).getDisconnectReason())
        .contains(DisconnectReason.ALREADY_CONNECTED);
  }

  @Test
  public void connect_failsWhenMaxPeersConnected() {
    // Saturate connections
    startAgentWithMaxPeers(1);
    agent.connect(createPeer());

    final Peer peer = createPeer();
    final CompletableFuture<PeerConnection> connection = agent.connect(peer);

    assertThat(connection).isDone();
    assertThat(connection).isCompletedExceptionally();

    assertThatThrownBy(connection::get)
        .hasCauseInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Max peer peer connections established (1). Cannot connect to peer");
    assertPeerConnectionNotTracked(peer);
    assertThat(agent.getConnectionCount()).isEqualTo(1);
  }

  @Test
  public void incomingConnection_maxPeersExceeded()
      throws ExecutionException, InterruptedException {
    // Saturate connections
    startAgentWithMaxPeers(1);
    final Peer existingPeer = createPeer();
    final PeerConnection existingConnection = agent.connect(existingPeer).get();
    // Sanity check
    assertThat(agent.getConnectionCount()).isEqualTo(1);

    // Simulate incoming connection
    final Peer newPeer = createPeer();
    final MockPeerConnection incomingConnection = connection(newPeer);
    connectionInitializer.simulateIncomingConnection(incomingConnection);

    // Incoming or existing connection should be disconnected
    assertThat(agent.getConnectionCount()).isEqualTo(1);
    if (agent.getPeerConnection(newPeer).isPresent()) {
      assertThat(((MockPeerConnection) existingConnection).getDisconnectReason())
          .contains(DisconnectReason.TOO_MANY_PEERS);
    } else {
      assertThat(incomingConnection.getDisconnectReason())
          .contains(DisconnectReason.TOO_MANY_PEERS);
    }
  }

  @Test
  public void connect_succeedsForExemptPeerWhenMaxPeersConnected()
      throws ExecutionException, InterruptedException {
    // Turn off autocomplete so that each connection is established (completed) after it has been
    // successfully added to the internal connections set. This mimics async production behavior.
    connectionInitializer.setAutocompleteConnections(false);

    // Saturate connections
    startAgentWithMaxPeers(1);
    final CompletableFuture<PeerConnection> existingConnectionFuture = agent.connect(createPeer());
    connectionInitializer.completePendingFutures();
    final MockPeerConnection existingConnection =
        (MockPeerConnection) existingConnectionFuture.get();

    final Peer peer = createPeer();
    when(peerProperties.ignoreMaxPeerLimits(peer)).thenReturn(true);
    final CompletableFuture<PeerConnection> connection = agent.connect(peer);
    connectionInitializer.completePendingFutures();

    assertThat(connection).isDone();
    assertThat(connection).isNotCompletedExceptionally();

    assertThat(agent.getPeerConnection(peer)).contains(connection);
    // Previous, non-exempt connection should be disconnected
    assertThat(existingConnection.isDisconnected()).isTrue();
    assertThat(existingConnection.getDisconnectReason()).contains(DisconnectReason.TOO_MANY_PEERS);
    assertThat(agent.getConnectionCount()).isEqualTo(1);
  }

  @Test
  public void connect_succeedsForExemptPeerWhenMaxExemptPeersConnected() {
    // Turn off autocomplete so that each connection is established (completed) after it has been
    // successfully added to the internal connections set. This mimics async production behavior.
    connectionInitializer.setAutocompleteConnections(false);

    startAgentWithMaxPeers(1);
    final Peer peerA = createPeer();
    final Peer peerB = createPeer();
    when(peerProperties.ignoreMaxPeerLimits(peerA)).thenReturn(true);
    when(peerProperties.ignoreMaxPeerLimits(peerB)).thenReturn(true);

    // Saturate connections
    final CompletableFuture<PeerConnection> existingConnection = agent.connect(peerA);
    connectionInitializer.completePendingFutures();

    // Create new connection
    final CompletableFuture<PeerConnection> connection = agent.connect(peerB);
    connectionInitializer.completePendingFutures();

    assertThat(connection).isDone();
    assertThat(connection).isNotCompletedExceptionally();

    // Both connections should be kept since they are both exempt from max peer limits
    assertThat(agent.getPeerConnection(peerA)).contains(existingConnection);
    assertThat(agent.getPeerConnection(peerB)).contains(connection);
    assertThat(agent.getConnectionCount()).isEqualTo(2);
  }

  @Test
  public void incomingConnection_maxPeersExceeded_incomingConnectionExemptFromLimits()
      throws ExecutionException, InterruptedException {
    final Peer peerA = createPeer();
    final Peer peerB = createPeer();
    when(peerProperties.ignoreMaxPeerLimits(peerB)).thenReturn(true);

    // Saturate connections
    startAgentWithMaxPeers(1);

    // Add existing peer
    final MockPeerConnection existingConnection = (MockPeerConnection) agent.connect(peerA).get();
    assertThat(agent.getConnectionCount()).isEqualTo(1);

    // Simulate incoming connection
    final MockPeerConnection connection = connection(peerB);
    connectionInitializer.simulateIncomingConnection(connection);

    // Existing connection should be disconnected
    assertThat(agent.getConnectionCount()).isEqualTo(1);
    assertPeerConnectionNotTracked(peerA);
    assertThat(agent.getPeerConnection(peerB)).isNotEmpty();
    assertThat(existingConnection.isDisconnected()).isTrue();
    assertThat(existingConnection.getDisconnectReason()).contains(DisconnectReason.TOO_MANY_PEERS);
    assertThat(connection.isDisconnected()).isFalse();
  }

  @Test
  public void incomingConnection_maxPeersExceeded_existingConnectionExemptFromLimits()
      throws ExecutionException, InterruptedException {
    final Peer peerA = createPeer();
    final Peer peerB = createPeer();
    when(peerProperties.ignoreMaxPeerLimits(peerA)).thenReturn(true);

    // Saturate connections
    startAgentWithMaxPeers(1);

    // Add existing peer
    final PeerConnection existingConnection = agent.connect(peerA).get();
    assertThat(agent.getConnectionCount()).isEqualTo(1);

    // Simulate incoming connection
    final MockPeerConnection connection = connection(peerB);
    connectionInitializer.simulateIncomingConnection(connection);

    // Incoming connection should be disconnected
    assertThat(agent.getConnectionCount()).isEqualTo(1);
    assertPeerConnectionNotTracked(peerB);
    assertThat(agent.getPeerConnection(peerA)).isNotEmpty();
    assertThat(connection.isDisconnected()).isTrue();
    assertThat(connection.getDisconnectReason()).contains(DisconnectReason.TOO_MANY_PEERS);
    assertThat(existingConnection.isDisconnected()).isFalse();
  }

  @Test
  public void incomingConnection_maxPeersExceeded_allConnectionsExemptFromLimits()
      throws ExecutionException, InterruptedException {
    final Peer peerA = createPeer();
    final Peer peerB = createPeer();
    when(peerProperties.ignoreMaxPeerLimits(peerA)).thenReturn(true);
    when(peerProperties.ignoreMaxPeerLimits(peerB)).thenReturn(true);

    // Saturate connections
    startAgentWithMaxPeers(1);

    // Add existing peer
    final CompletableFuture<PeerConnection> existingConnection = agent.connect(peerA);
    assertThat(agent.getConnectionCount()).isEqualTo(1);

    // Simulate incoming connection
    final PeerConnection connection = connection(peerB);
    connectionInitializer.simulateIncomingConnection(connection);

    // Neither peer should be disconnected
    assertThat(agent.getConnectionCount()).isEqualTo(2);
    assertThat(agent.getPeerConnection(peerB)).isNotEmpty();
    assertThat(agent.getPeerConnection(peerA)).isNotEmpty();
    assertThat(connection.isDisconnected()).isFalse();
    assertThat(existingConnection.get().isDisconnected()).isFalse();
  }

  @Test
  public void connect_failsIfPeerIsNotPermitted() {
    startAgent();
    final Peer peer = createPeer();
    doReturn(false)
        .when(peerPermissions)
        .isPermitted(
            eq(localNode.getPeer()), eq(peer), eq(Action.RLPX_ALLOW_NEW_OUTBOUND_CONNECTION));
    final CompletableFuture<PeerConnection> connection = agent.connect(peer);

    assertThat(connection).isDone();
    assertThat(connection).isCompletedExceptionally();

    assertThatThrownBy(connection::get).hasCauseInstanceOf(PeerPermissionsException.class);
    assertPeerConnectionNotTracked(peer);
  }

  @Test
  public void incomingConnection_disconnectedIfPeerIsNotPermitted() {
    startAgent();
    final Peer peer = createPeer();
    final MockPeerConnection connection = connection(peer);
    doReturn(false)
        .when(peerPermissions)
        .isPermitted(
            eq(localNode.getPeer()), eq(peer), eq(Action.RLPX_ALLOW_NEW_INBOUND_CONNECTION));
    connectionInitializer.simulateIncomingConnection(connection);

    assertPeerConnectionNotTracked(peer);
    assertThat(connection.getDisconnectReason()).contains(DisconnectReason.UNKNOWN);
  }

  @Test
  public void connect_largeStreamOfPeers() {
    final int maxPeers = 5;
    final Stream<Peer> peerStream = Stream.generate(PeerTestHelper::createPeer).limit(20);

    startAgentWithMaxPeers(maxPeers);
    agent = spy(agent);
    agent.connect(peerStream);

    assertThat(agent.getConnectionCount()).isEqualTo(maxPeers);
    // Check that stream was not fully iterated
    verify(agent, times(maxPeers)).connect(any(Peer.class));
  }

  @Test
  public void disconnect() throws ExecutionException, InterruptedException {
    startAgent();
    final Peer peer = spy(createPeer());
    final CompletableFuture<PeerConnection> future = agent.connect(peer);
    final MockPeerConnection connection = (MockPeerConnection) future.get();

    // Sanity check connection was established
    assertThat(agent.getConnectionCount()).isEqualTo(1);
    assertThat(agent.getPeerConnection(peer)).isNotEmpty();

    // Disconnect
    final DisconnectReason reason = DisconnectReason.REQUESTED;
    agent.disconnect(peer.getId(), reason);

    // Validate peer was disconnected
    assertThat(agent.getConnectionCount()).isEqualTo(0);
    assertThat(connection.isDisconnected()).isTrue();
    assertThat(connection.getDisconnectReason()).contains(reason);
    assertPeerConnectionNotTracked(peer);

    // Additional requests to disconnect should do nothing
    agent.disconnect(peer.getId(), reason);
    agent.disconnect(peer.getId(), reason);
    assertThat(agent.getConnectionCount()).isEqualTo(0);
  }

  @Test
  public void getConnectionCount() throws ExecutionException, InterruptedException {
    startAgent();
    final Peer peerA = createPeer();
    final Peer peerB = createPeer();
    final Peer peerC = createPeer();

    assertThat(agent.getConnectionCount()).isEqualTo(0);
    agent.connect(peerA);
    assertThat(agent.getConnectionCount()).isEqualTo(1);
    agent.connect(peerA);
    assertThat(agent.getConnectionCount()).isEqualTo(1);
    agent.connect(peerB);
    assertThat(agent.getConnectionCount()).isEqualTo(2);
    agent.connect(peerC);
    assertThat(agent.getConnectionCount()).isEqualTo(3);

    // Disconnect should decrement count
    agent.disconnect(peerB.getId(), DisconnectReason.UNKNOWN);
    assertThat(agent.getConnectionCount()).isEqualTo(2);
  }

  @Test
  public void permissionsUpdate_permissionsRestrictedWithNoListOfPeers()
      throws ExecutionException, InterruptedException {
    final Peer permittedPeer = createPeer();
    final Peer nonPermittedPeer = createPeer();
    startAgent();
    final PeerConnection permittedConnection = agent.connect(permittedPeer).get();
    final PeerConnection nonPermittedConnection = agent.connect(nonPermittedPeer).get();

    // Sanity check
    assertThat(agent.getConnectionCount()).isEqualTo(2);

    doReturn(false)
        .when(peerPermissions)
        .isPermitted(
            eq(localNode.getPeer()),
            eq(nonPermittedPeer),
            eq(Action.RLPX_ALLOW_ONGOING_LOCALLY_INITIATED_CONNECTION));
    peerPermissions.testDispatchUpdate(true, Optional.empty());

    assertThat(agent.getConnectionCount()).isEqualTo(1);
    assertThat(agent.getPeerConnection(permittedPeer)).isNotEmpty();
    assertPeerConnectionNotTracked(nonPermittedPeer);
    assertThat(permittedConnection.isDisconnected()).isFalse();
    assertThat(nonPermittedConnection.isDisconnected()).isTrue();
  }

  @Test
  public void permissionsUpdate_permissionsRestrictedWithListOfPeers()
      throws ExecutionException, InterruptedException {
    final Peer permittedPeer = createPeer();
    final Peer nonPermittedPeer = createPeer();
    startAgent();
    final PeerConnection permittedConnection = agent.connect(permittedPeer).get();
    final PeerConnection nonPermittedConnection = agent.connect(nonPermittedPeer).get();

    // Sanity check
    assertThat(agent.getConnectionCount()).isEqualTo(2);

    doReturn(false)
        .when(peerPermissions)
        .isPermitted(
            eq(localNode.getPeer()),
            eq(nonPermittedPeer),
            eq(Action.RLPX_ALLOW_ONGOING_LOCALLY_INITIATED_CONNECTION));
    peerPermissions.testDispatchUpdate(true, Optional.of(Arrays.asList(nonPermittedPeer)));

    assertThat(agent.getConnectionCount()).isEqualTo(1);
    assertThat(agent.getPeerConnection(permittedPeer)).isNotEmpty();
    assertPeerConnectionNotTracked(nonPermittedPeer);
    assertThat(permittedConnection.isDisconnected()).isFalse();
    assertThat(nonPermittedConnection.isDisconnected()).isTrue();
  }

  @Test
  public void permissionsUpdate_permissionsRestrictedForRemotelyInitiatedConnections()
      throws ExecutionException, InterruptedException {
    final Peer locallyConnectedPeer = createPeer();
    final Peer remotelyConnectedPeer = createPeer();
    startAgent();
    final PeerConnection locallyInitiatedConnection = agent.connect(locallyConnectedPeer).get();
    final PeerConnection remotelyInitiatedConnection = connection(remotelyConnectedPeer);
    connectionInitializer.simulateIncomingConnection(remotelyInitiatedConnection);

    // Sanity check
    assertThat(agent.getConnectionCount()).isEqualTo(2);

    doReturn(false)
        .when(peerPermissions)
        .isPermitted(
            eq(localNode.getPeer()),
            any(),
            eq(Action.RLPX_ALLOW_ONGOING_REMOTELY_INITIATED_CONNECTION));
    peerPermissions.testDispatchUpdate(true, Optional.empty());

    assertThat(agent.getConnectionCount()).isEqualTo(1);
    assertThat(agent.getPeerConnection(locallyConnectedPeer)).isNotEmpty();
    assertPeerConnectionNotTracked(remotelyConnectedPeer);
    assertThat(locallyInitiatedConnection.isDisconnected()).isFalse();
    assertThat(remotelyInitiatedConnection.isDisconnected()).isTrue();
  }

  @Test
  public void permissionsUpdate_permissionsRestrictedForLocallyInitiatedConnections()
      throws ExecutionException, InterruptedException {
    final Peer locallyConnectedPeer = createPeer();
    final Peer remotelyConnectedPeer = createPeer();
    startAgent();
    final PeerConnection locallyInitiatedConnection = agent.connect(locallyConnectedPeer).get();
    final PeerConnection remotelyInitiatedConnection = connection(remotelyConnectedPeer);
    connectionInitializer.simulateIncomingConnection(remotelyInitiatedConnection);

    // Sanity check
    assertThat(agent.getConnectionCount()).isEqualTo(2);

    doReturn(false)
        .when(peerPermissions)
        .isPermitted(
            eq(localNode.getPeer()),
            any(),
            eq(Action.RLPX_ALLOW_ONGOING_LOCALLY_INITIATED_CONNECTION));
    peerPermissions.testDispatchUpdate(true, Optional.empty());

    assertThat(agent.getConnectionCount()).isEqualTo(1);
    assertPeerConnectionNotTracked(locallyConnectedPeer);
    assertThat(agent.getPeerConnection(remotelyConnectedPeer)).isNotEmpty();
    assertThat(locallyInitiatedConnection.isDisconnected()).isTrue();
    assertThat(remotelyInitiatedConnection.isDisconnected()).isFalse();
  }

  @Test
  public void permissionsUpdate_permissionsRelaxedWithNoListOfPeers()
      throws ExecutionException, InterruptedException {
    final Peer peerA = createPeer();
    final Peer peerB = createPeer();
    startAgent();
    final PeerConnection connectionA = agent.connect(peerA).get();
    final PeerConnection connectionB = agent.connect(peerB).get();

    // Sanity check
    assertThat(agent.getConnectionCount()).isEqualTo(2);

    peerPermissions.testDispatchUpdate(false, Optional.empty());

    assertThat(agent.getConnectionCount()).isEqualTo(2);
    assertThat(agent.getPeerConnection(peerA)).isNotEmpty();
    assertThat(agent.getPeerConnection(peerB)).isNotEmpty();
    assertThat(connectionA.isDisconnected()).isFalse();
    assertThat(connectionB.isDisconnected()).isFalse();
  }

  @Test
  public void permissionsUpdate_permissionsRelaxedWithListOfPeers()
      throws ExecutionException, InterruptedException {
    final Peer peerA = createPeer();
    final Peer peerB = createPeer();
    startAgent();
    final PeerConnection connectionA = agent.connect(peerA).get();
    final PeerConnection connectionB = agent.connect(peerB).get();

    // Sanity check
    assertThat(agent.getConnectionCount()).isEqualTo(2);

    peerPermissions.testDispatchUpdate(false, Optional.of(Arrays.asList(peerA)));

    assertThat(agent.getConnectionCount()).isEqualTo(2);
    assertThat(agent.getPeerConnection(peerA)).isNotEmpty();
    assertThat(agent.getPeerConnection(peerB)).isNotEmpty();
    assertThat(connectionA.isDisconnected()).isFalse();
    assertThat(connectionB.isDisconnected()).isFalse();
  }

  @Test
  public void subscribeConnect_firesForOutgoingConnection() {
    final Peer peer = createPeer();
    startAgent();

    final AtomicReference<PeerConnection> connection = new AtomicReference<>();
    agent.subscribeConnect(connection::set);

    agent.connect(peer);

    assertThat(connection.get()).isNotNull();
    assertThat(connection.get().getPeer()).isEqualTo(peer);
  }

  @Test
  public void subscribeConnect_firesForIncomingConnection() {
    final Peer peer = createPeer();
    startAgent();

    final AtomicReference<PeerConnection> connection = new AtomicReference<>();
    agent.subscribeConnect(connection::set);

    connectionInitializer.simulateIncomingConnection(connection(peer));

    assertThat(connection.get()).isNotNull();
    assertThat(connection.get().getPeer()).isEqualTo(peer);
  }

  @Test
  public void subscribeDisconnect() {
    final Peer peer = createPeer();
    startAgent();

    final AtomicReference<PeerConnection> connection = new AtomicReference<>();
    agent.subscribeDisconnect((conn, reason, initiatedByPeer) -> connection.set(conn));

    agent.connect(peer);
    agent.disconnect(peer.getId(), DisconnectReason.REQUESTED);

    assertThat(connection.get()).isNotNull();
    assertThat(connection.get().getPeer()).isEqualTo(peer);
  }

  @Test
  public void subscribeMessage() throws ExecutionException, InterruptedException, PeerNotConnected {
    final Peer peer = createPeer();
    final Capability cap = Capability.create("eth", 63);
    startAgent();

    final AtomicBoolean eventFired = new AtomicBoolean(false);
    agent.subscribeMessage(cap, (capability, msg) -> eventFired.set(true));

    final PeerConnection connection = agent.connect(peer).get();
    peerConnectionEvents.dispatchMessage(
        Capability.create("eth", 63), connection, PingMessage.get());

    assertThat(eventFired).isTrue();
  }

  @Test
  public void getPeerConnection_pending() {
    connectionInitializer.setAutocompleteConnections(false);
    startAgent();

    final Peer peer = createPeer();
    final CompletableFuture<PeerConnection> future = agent.connect(peer);
    assertThat(future).isNotDone();

    assertThat(agent.getPeerConnection(peer)).contains(future);
  }

  @Test
  public void getPeerConnection_established() {
    startAgent();

    final Peer peer = createPeer();
    final CompletableFuture<PeerConnection> future = agent.connect(peer);
    assertThat(future).isDone();

    assertThat(agent.getPeerConnection(peer)).contains(future);
  }

  private void assertPeerConnectionNotTracked(final Peer peer) {
    assertThat(agent.getPeerConnection(peer)).isEmpty();
    assertThat(agent.connectionsById.get(peer.getId())).isNull();
  }

  private void startAgent() {
    startAgent(Peer.randomId());
  }

  private void startAgentWithMaxPeers(final int maxPeers) {
    config.setMaxPeers(maxPeers);
    agent = agent();
    startAgent();
  }

  private void startAgent(final BytesValue nodeId) {
    agent.start();
    localNode.setEnode(enodeBuilder().nodeId(nodeId).build());
  }

  private RlpxAgent agent() {
    return RlpxAgent.builder()
        .keyPair(KEY_PAIR)
        .config(config)
        .peerPermissions(peerPermissions)
        .peerProperties(peerProperties)
        .localNode(localNode)
        .metricsSystem(metrics)
        .connectionInitializer(connectionInitializer)
        .connectionEvents(peerConnectionEvents)
        .build();
  }

  private MockPeerConnection connection(final Peer peer) {
    return MockPeerConnection.create(peer, peerConnectionEvents);
  }

  private static class TestPeerPermissions extends PeerPermissions {

    @Override
    public boolean isPermitted(final Peer localNode, final Peer remotePeer, final Action action) {
      return true;
    }

    public void testDispatchUpdate(
        final boolean permissionsRestricted, final Optional<List<Peer>> affectedPeers) {
      this.dispatchUpdate(permissionsRestricted, affectedPeers);
    }
  }
}
