package pl.allegro.tech.servicemesh.envoycontrol.v2;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import com.google.protobuf.Message;
import io.envoyproxy.controlplane.cache.NodeGroup;
import io.envoyproxy.controlplane.cache.Resources;
import io.envoyproxy.controlplane.cache.Response;
import io.envoyproxy.controlplane.cache.StatusInfo;
import io.envoyproxy.controlplane.cache.Watch;
import io.envoyproxy.controlplane.cache.XdsRequest;
import io.envoyproxy.controlplane.cache.v2.Snapshot;
import io.envoyproxy.envoy.api.v2.Cluster;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.Listener;
import io.envoyproxy.envoy.api.v2.RouteConfiguration;
import io.envoyproxy.envoy.api.v2.auth.Secret;
import io.envoyproxy.envoy.api.v2.core.Node;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static io.envoyproxy.controlplane.cache.Resources.V2.CLUSTER_TYPE_URL;
import static io.envoyproxy.controlplane.cache.Resources.V2.ROUTE_TYPE_URL;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * This class is copy of {@link io.envoyproxy.controlplane.cache.v2.SimpleCacheTest}
 */
public class SimpleCacheTest {

    private static final boolean ADS = ThreadLocalRandom.current().nextBoolean();
    protected static final String CLUSTER_NAME = "cluster0";
    private static final String SECONDARY_CLUSTER_NAME = "cluster1";
    private static final String LISTENER_NAME = "listener0";
    private static final String ROUTE_NAME = "route0";
    private static final String SECRET_NAME = "secret0";

    private static final String VERSION1 = UUID.randomUUID().toString();
    protected static final String VERSION2 = UUID.randomUUID().toString();

    private static final Snapshot SNAPSHOT1 = Snapshot.create(
            ImmutableList.of(Cluster.newBuilder().setName(CLUSTER_NAME).build()),
            ImmutableList.of(ClusterLoadAssignment.getDefaultInstance()),
            ImmutableList.of(Listener.newBuilder().setName(LISTENER_NAME).build()),
            ImmutableList.of(RouteConfiguration.newBuilder().setName(ROUTE_NAME).build()),
            ImmutableList.of(Secret.newBuilder().setName(SECRET_NAME).build()),
            VERSION1);

    private static final Snapshot SNAPSHOT2 = Snapshot.create(
            ImmutableList.of(Cluster.newBuilder().setName(CLUSTER_NAME).build()),
            ImmutableList.of(ClusterLoadAssignment.getDefaultInstance()),
            ImmutableList.of(Listener.newBuilder().setName(LISTENER_NAME).build()),
            ImmutableList.of(RouteConfiguration.newBuilder().setName(ROUTE_NAME).build()),
            ImmutableList.of(Secret.newBuilder().setName(SECRET_NAME).build()),
            VERSION2);

    protected static final Snapshot MULTIPLE_RESOURCES_SNAPSHOT2 = Snapshot.create(
            ImmutableList.of(Cluster.newBuilder().setName(CLUSTER_NAME).build(),
                    Cluster.newBuilder().setName(SECONDARY_CLUSTER_NAME).build()),
            ImmutableList.of(ClusterLoadAssignment.newBuilder().setClusterName(CLUSTER_NAME).build(),
                    ClusterLoadAssignment.newBuilder().setClusterName(SECONDARY_CLUSTER_NAME).build()),
            ImmutableList.of(Listener.newBuilder().setName(LISTENER_NAME).build()),
            ImmutableList.of(RouteConfiguration.newBuilder().setName(ROUTE_NAME).build()),
            ImmutableList.of(Secret.newBuilder().setName(SECRET_NAME).build()),
            VERSION2);

    protected boolean shouldSendMissingEndpoints() {
        return false;
    }

    @Test
    public void invalidNamesListShouldReturnWatcherWithNoResponseInAdsMode() {
        SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup(), shouldSendMissingEndpoints());

        cache.setSnapshot(SingleNodeGroup.GROUP, SNAPSHOT1);

        ResponseTracker responseTracker = new ResponseTracker();

        Watch watch = cache.createWatch(
                true,
                XdsRequest.create(DiscoveryRequest.newBuilder()
                        .setNode(Node.getDefaultInstance())
                        .setTypeUrl(Resources.V2.ENDPOINT_TYPE_URL)
                        .addResourceNames("none")
                        .build()),
                Collections.emptySet(),
                responseTracker,
                false);

        assertThatWatchIsOpenWithNoResponses(new WatchAndTracker(watch, responseTracker));
    }

    @Test
    public void invalidNamesListShouldReturnWatcherWithResponseInXdsMode() {
        SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup(), shouldSendMissingEndpoints());

        cache.setSnapshot(SingleNodeGroup.GROUP, SNAPSHOT1);

        ResponseTracker responseTracker = new ResponseTracker();

        Watch watch = cache.createWatch(
                false,
                XdsRequest.create(DiscoveryRequest.newBuilder()
                        .setNode(Node.getDefaultInstance())
                        .setTypeUrl(Resources.V2.ENDPOINT_TYPE_URL)
                        .addResourceNames("none")
                        .build()),
                Collections.emptySet(),
                responseTracker,
                false);

        assertThat(watch.isCancelled()).isFalse();
        assertThat(responseTracker.responses).isNotEmpty();
    }

    @Test
    public void successfullyWatchAllResourceTypesWithSetBeforeWatch() {
        SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup(), shouldSendMissingEndpoints());

        cache.setSnapshot(SingleNodeGroup.GROUP, SNAPSHOT1);

        for (String typeUrl : Resources.V2.TYPE_URLS) {
            ResponseTracker responseTracker = new ResponseTracker();

            Watch watch = cache.createWatch(
                    ADS,
                    XdsRequest.create(DiscoveryRequest.newBuilder()
                            .setNode(Node.getDefaultInstance())
                            .setTypeUrl(typeUrl)
                            .addAllResourceNames(SNAPSHOT1.resources(typeUrl).keySet())
                            .build()),
                    Collections.emptySet(),
                    responseTracker,
                    false);

            assertThat(watch.request().getTypeUrl()).isEqualTo(typeUrl);
            assertThat(watch.request().getResourceNamesList()).containsExactlyElementsOf(
                    SNAPSHOT1.resources(typeUrl).keySet());

            assertThatWatchReceivesSnapshot(new WatchAndTracker(watch, responseTracker), SNAPSHOT1);
        }
    }

    @Test
    public void shouldSendEdsWhenClusterChangedButEdsVersionDidnt() {
        SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup(), shouldSendMissingEndpoints());

        cache.setSnapshot(SingleNodeGroup.GROUP, SNAPSHOT1);

        ResponseTracker responseTracker = new ResponseTracker();

        Watch watch = cache.createWatch(
                ADS,
                XdsRequest.create(DiscoveryRequest.newBuilder()
                        .setNode(Node.getDefaultInstance())
                        .setVersionInfo(VERSION1)
                        .setTypeUrl(Resources.V2.ENDPOINT_TYPE_URL)
                        .addAllResourceNames(SNAPSHOT1.resources(Resources.V2.ENDPOINT_TYPE_URL).keySet())
                        .build()),
                Sets.newHashSet(""),
                responseTracker,
                true);

        assertThat(watch.request().getTypeUrl()).isEqualTo(Resources.V2.ENDPOINT_TYPE_URL);
        assertThat(watch.request().getResourceNamesList()).containsExactlyElementsOf(
                SNAPSHOT1.resources(Resources.V2.ENDPOINT_TYPE_URL).keySet());

        assertThatWatchReceivesSnapshot(new WatchAndTracker(watch, responseTracker), SNAPSHOT1);
    }

    @Test
    public void successfullyWatchAllResourceTypesWithSetAfterWatch() {
        SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup(), shouldSendMissingEndpoints());

        Map<String, WatchAndTracker> watches = Resources.V2.TYPE_URLS.stream()
                .collect(Collectors.toMap(
                        typeUrl -> typeUrl,
                        typeUrl -> {
                            ResponseTracker responseTracker = new ResponseTracker();

                            Watch watch = cache.createWatch(
                                    ADS,
                                    XdsRequest.create(DiscoveryRequest.newBuilder()
                                            .setNode(Node.getDefaultInstance())
                                            .setTypeUrl(typeUrl)
                                            .addAllResourceNames(SNAPSHOT1.resources(typeUrl).keySet())
                                            .build()),
                                    Collections.emptySet(),
                                    responseTracker,
                                    false);

                            return new WatchAndTracker(watch, responseTracker);
                        }));

        cache.setSnapshot(SingleNodeGroup.GROUP, SNAPSHOT1);

        for (String typeUrl : Resources.V2.TYPE_URLS) {
            assertThatWatchReceivesSnapshot(watches.get(typeUrl), SNAPSHOT1);
        }
    }

    @Test
    public void successfullyWatchAllResourceTypesWithSetBeforeWatchWithRequestVersion() {
        SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup(), shouldSendMissingEndpoints());

        cache.setSnapshot(SingleNodeGroup.GROUP, SNAPSHOT1);

        ResponseOrderTracker responseOrderTracker = new ResponseOrderTracker();

        HashMap<String, WatchAndTracker> watches = new HashMap<>();

        for (int i = 0; i < 2; ++i) {
            watches.putAll(Resources.V2.TYPE_URLS.stream()
                    .collect(Collectors.toMap(
                            typeUrl -> typeUrl,
                            typeUrl -> {
                                ResponseTracker responseTracker = new ResponseTracker();

                                Watch watch = cache.createWatch(
                                        ADS,
                                        XdsRequest.create(DiscoveryRequest.newBuilder()
                                                .setNode(Node.getDefaultInstance())
                                                .setTypeUrl(typeUrl)
                                                .setVersionInfo(SNAPSHOT1.version(typeUrl))
                                                .addAllResourceNames(SNAPSHOT1.resources(typeUrl).keySet())
                                                .build()),
                                        SNAPSHOT2.resources(typeUrl).keySet(),
                                        r -> {
                                            responseTracker.accept(r);
                                            responseOrderTracker.accept(r);
                                        },
                                        false);

                                return new WatchAndTracker(watch, responseTracker);
                            }))
            );
        }

        // The request version matches the current snapshot version, so the watches shouldn't receive any responses.
        for (String typeUrl : Resources.V2.TYPE_URLS) {
            assertThatWatchIsOpenWithNoResponses(watches.get(typeUrl));
        }

        cache.setSnapshot(SingleNodeGroup.GROUP, SNAPSHOT2);

        for (String typeUrl : Resources.V2.TYPE_URLS) {
            assertThatWatchReceivesSnapshot(watches.get(typeUrl), SNAPSHOT2);
        }

        // Verify that CDS and LDS always get triggered before EDS and RDS respectively.
        assertThat(responseOrderTracker.responseTypes).containsExactly(Resources.V2.CLUSTER_TYPE_URL,
                Resources.V2.CLUSTER_TYPE_URL, Resources.V2.ENDPOINT_TYPE_URL, Resources.V2.ENDPOINT_TYPE_URL,
                Resources.V2.LISTENER_TYPE_URL, Resources.V2.LISTENER_TYPE_URL, Resources.V2.ROUTE_TYPE_URL,
                Resources.V2.ROUTE_TYPE_URL, Resources.V2.SECRET_TYPE_URL, Resources.V2.SECRET_TYPE_URL);
    }

    @Test
    public void successfullyWatchAllResourceTypesWithSetBeforeWatchWithSameRequestVersionNewResourceHints() {
        SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup(), shouldSendMissingEndpoints());

        cache.setSnapshot(SingleNodeGroup.GROUP, MULTIPLE_RESOURCES_SNAPSHOT2);

        // Set a watch for the current snapshot with the same version but with resource hints present
        // in the snapshot that the watch creator does not currently know about.
        //
        // Note how we're requesting the resources from MULTIPLE_RESOURCE_SNAPSHOT2 while claiming we
        // only know about the ones from SNAPSHOT2
        Map<String, WatchAndTracker> watches = Resources.V2.TYPE_URLS.stream()
                .collect(Collectors.toMap(
                        typeUrl -> typeUrl,
                        typeUrl -> {
                            ResponseTracker responseTracker = new ResponseTracker();

                            Watch watch = cache.createWatch(
                                    ADS,
                                    XdsRequest.create(DiscoveryRequest.newBuilder()
                                            .setNode(Node.getDefaultInstance())
                                            .setTypeUrl(typeUrl)
                                            .setVersionInfo(MULTIPLE_RESOURCES_SNAPSHOT2.version(typeUrl))
                                            .addAllResourceNames(MULTIPLE_RESOURCES_SNAPSHOT2.resources(typeUrl).keySet())
                                            .build()),
                                    SNAPSHOT2.resources(typeUrl).keySet(),
                                    responseTracker,
                                    false);

                            return new WatchAndTracker(watch, responseTracker);
                        }));

        // The snapshot version matches for all resources, but for eds and cds there are new resources present
        // for the same version, so we expect the watches to trigger.
        assertThatWatchReceivesSnapshot(watches.remove(Resources.V2.CLUSTER_TYPE_URL), MULTIPLE_RESOURCES_SNAPSHOT2);
        assertThatWatchReceivesSnapshot(watches.remove(Resources.V2.ENDPOINT_TYPE_URL), MULTIPLE_RESOURCES_SNAPSHOT2);

        // Remaining watches should not trigger
        for (WatchAndTracker watchAndTracker : watches.values()) {
            assertThatWatchIsOpenWithNoResponses(watchAndTracker);
        }
    }

    @Test
    public void successfullyWatchAllResourceTypesWithSetBeforeWatchWithSameRequestVersionNewResourceHintsNoChange() {
        SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup(), shouldSendMissingEndpoints());

        cache.setSnapshot(SingleNodeGroup.GROUP, SNAPSHOT2);

        // Set a watch for the current snapshot for the same version but with new resource hints not
        // present in the snapshot that the watch creator does not know about.
        //
        // Note that we're requesting the additional resources found in MULTIPLE_RESOURCE_SNAPSHOT2
        // while we only know about the resources found in SNAPSHOT2. Since SNAPSHOT2 is the current
        // snapshot, we have nothing to respond with for the new resources so we should not trigger
        // the watch.
        Map<String, WatchAndTracker> watches = Resources.V2.TYPE_URLS.stream()
                .collect(Collectors.toMap(
                        typeUrl -> typeUrl,
                        typeUrl -> {
                            ResponseTracker responseTracker = new ResponseTracker();

                            Watch watch = cache.createWatch(
                                    ADS,
                                    XdsRequest.create(DiscoveryRequest.newBuilder()
                                            .setNode(Node.getDefaultInstance())
                                            .setTypeUrl(typeUrl)
                                            .setVersionInfo(SNAPSHOT2.version(typeUrl))
                                            .addAllResourceNames(MULTIPLE_RESOURCES_SNAPSHOT2.resources(typeUrl).keySet())
                                            .build()),
                                    SNAPSHOT2.resources(typeUrl).keySet(),
                                    responseTracker,
                                    false);

                            return new WatchAndTracker(watch, responseTracker);
                        }));

        // No watches should trigger since no new information will be returned
        for (WatchAndTracker watchAndTracker : watches.values()) {
            assertThatWatchIsOpenWithNoResponses(watchAndTracker);
        }
    }

    @Test
    public void setSnapshotWithVersionMatchingRequestShouldLeaveWatchOpenWithoutAdditionalResponse() {
        SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup(), shouldSendMissingEndpoints());

        cache.setSnapshot(SingleNodeGroup.GROUP, SNAPSHOT1);

        Map<String, WatchAndTracker> watches = Resources.V2.TYPE_URLS.stream()
                .collect(Collectors.toMap(
                        typeUrl -> typeUrl,
                        typeUrl -> {
                            ResponseTracker responseTracker = new ResponseTracker();

                            Watch watch = cache.createWatch(
                                    ADS,
                                    XdsRequest.create(DiscoveryRequest.newBuilder()
                                            .setNode(Node.getDefaultInstance())
                                            .setTypeUrl(typeUrl)
                                            .setVersionInfo(SNAPSHOT1.version(typeUrl))
                                            .addAllResourceNames(SNAPSHOT1.resources(typeUrl).keySet())
                                            .build()),
                                    SNAPSHOT1.resources(typeUrl).keySet(),
                                    responseTracker,
                                    false);

                            return new WatchAndTracker(watch, responseTracker);
                        }));

        // The request version matches the current snapshot version, so the watches shouldn't receive any responses.
        for (String typeUrl : Resources.V2.TYPE_URLS) {
            assertThatWatchIsOpenWithNoResponses(watches.get(typeUrl));
        }

        cache.setSnapshot(SingleNodeGroup.GROUP, SNAPSHOT1);

        // The request version still matches the current snapshot version, so the watches shouldn't receive any responses.
        for (String typeUrl : Resources.V2.TYPE_URLS) {
            assertThatWatchIsOpenWithNoResponses(watches.get(typeUrl));
        }
    }

    @Test
    public void watchesAreReleasedAfterCancel() {
        SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup(), shouldSendMissingEndpoints());

        Map<String, WatchAndTracker> watches = Resources.V2.TYPE_URLS.stream()
                .collect(Collectors.toMap(
                        typeUrl -> typeUrl,
                        typeUrl -> {
                            ResponseTracker responseTracker = new ResponseTracker();

                            Watch watch = cache.createWatch(
                                    ADS,
                                    XdsRequest.create(DiscoveryRequest.newBuilder()
                                            .setNode(Node.getDefaultInstance())
                                            .setTypeUrl(typeUrl)
                                            .addAllResourceNames(SNAPSHOT1.resources(typeUrl).keySet())
                                            .build()),
                                    Collections.emptySet(),
                                    responseTracker,
                                    false);

                            return new WatchAndTracker(watch, responseTracker);
                        }));

        StatusInfo statusInfo = cache.statusInfo(SingleNodeGroup.GROUP);

        assertThat(statusInfo.numWatches()).isEqualTo(watches.size());

        watches.values().forEach(w -> w.watch.cancel());

        assertThat(statusInfo.numWatches()).isZero();

        watches.values().forEach(w -> assertThat(w.watch.isCancelled()).isTrue());
    }

    @Test
    public void watchIsLeftOpenIfNotRespondedImmediately() {
        SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup(), shouldSendMissingEndpoints());
        cache.setSnapshot(SingleNodeGroup.GROUP, Snapshot.create(
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), VERSION1));

        ResponseTracker responseTracker = new ResponseTracker();
        Watch watch = cache.createWatch(
                true,
                XdsRequest.create(DiscoveryRequest.newBuilder()
                        .setNode(Node.getDefaultInstance())
                        .setTypeUrl(ROUTE_TYPE_URL)
                        .addAllResourceNames(Collections.singleton(ROUTE_NAME))
                        .build()),
                Collections.singleton(ROUTE_NAME),
                responseTracker,
                false);

        assertThatWatchIsOpenWithNoResponses(new WatchAndTracker(watch, responseTracker));
    }

    @Test
    public void getSnapshot() {
        SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup(), shouldSendMissingEndpoints());

        cache.setSnapshot(SingleNodeGroup.GROUP, SNAPSHOT1);

        assertThat(cache.getSnapshot(SingleNodeGroup.GROUP)).isEqualTo(SNAPSHOT1);
    }

    @Test
    public void clearSnapshot() {
        SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup(), shouldSendMissingEndpoints());

        cache.setSnapshot(SingleNodeGroup.GROUP, SNAPSHOT1);

        assertThat(cache.clearSnapshot(SingleNodeGroup.GROUP)).isTrue();

        assertThat(cache.getSnapshot(SingleNodeGroup.GROUP)).isNull();
    }

    @Test
    public void clearSnapshotWithWatches() {
        SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup(), shouldSendMissingEndpoints());

        cache.setSnapshot(SingleNodeGroup.GROUP, SNAPSHOT1);

        // Create a watch with an arbitrary type URL and a versionInfo that matches the saved
        // snapshot, so the watch doesn't immediately close.
        final Watch watch = cache.createWatch(ADS, XdsRequest.create(DiscoveryRequest.newBuilder()
                        .setNode(Node.getDefaultInstance())
                        .setTypeUrl(CLUSTER_TYPE_URL)
                        .setVersionInfo(SNAPSHOT1.version(CLUSTER_TYPE_URL))
                        .build()),
                Collections.emptySet(),
                r -> { },
                false);

        // clearSnapshot should fail and the snapshot should be left untouched
        assertThat(cache.clearSnapshot(SingleNodeGroup.GROUP)).isFalse();
        assertThat(cache.getSnapshot(SingleNodeGroup.GROUP)).isEqualTo(SNAPSHOT1);
        assertThat(cache.statusInfo(SingleNodeGroup.GROUP)).isNotNull();

        watch.cancel();

        // now that the watch is gone we should be able to clear it
        assertThat(cache.clearSnapshot(SingleNodeGroup.GROUP)).isTrue();
        assertThat(cache.getSnapshot(SingleNodeGroup.GROUP)).isNull();
        assertThat(cache.statusInfo(SingleNodeGroup.GROUP)).isNull();
    }

    @Test
    public void groups() {
        SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup(), shouldSendMissingEndpoints());

        assertThat(cache.groups()).isEmpty();

        cache.createWatch(ADS, XdsRequest.create(DiscoveryRequest.newBuilder()
                        .setNode(Node.getDefaultInstance())
                        .setTypeUrl(CLUSTER_TYPE_URL)
                        .build()),
                Collections.emptySet(),
                r -> { },
                false);

        assertThat(cache.groups()).containsExactly(SingleNodeGroup.GROUP);
    }

    private static void assertThatWatchIsOpenWithNoResponses(WatchAndTracker watchAndTracker) {
        assertThat(watchAndTracker.watch.isCancelled()).isFalse();
        assertThat(watchAndTracker.tracker.responses).isEmpty();
    }


    protected static void assertThatWatchReceivesSnapshot(WatchAndTracker watchAndTracker, Snapshot snapshot) {
        assertThat(watchAndTracker.tracker.responses).isNotEmpty();

        Response response = watchAndTracker.tracker.responses.getFirst();

        assertThat(response).isNotNull();
        assertThat(response.version()).isEqualTo(snapshot.version(watchAndTracker.watch.request().getTypeUrl()));
        assertThat(response.resources().toArray(new Message[0]))
            .containsExactlyElementsOf(snapshot.resources(watchAndTracker.watch.request().getTypeUrl()).values());
    }

    protected static class ResponseTracker implements Consumer<Response> {

        private final LinkedList<Response> responses = new LinkedList<>();

        @Override
        public void accept(Response response) {
            responses.add(response);
        }

        public LinkedList<Response> getResponses() {
            return responses;
        }
    }

    private static class ResponseOrderTracker implements Consumer<Response> {

        private final LinkedList<String> responseTypes = new LinkedList<>();

        @Override public void accept(Response response) {
            responseTypes.add(response.request().getTypeUrl());
        }
    }

    protected static class SingleNodeGroup implements NodeGroup<String> {

        protected static final String GROUP = "node";

        @Override
        public String hash(Node node) {
            if (node == null) {
                throw new IllegalArgumentException("node");
            }

            return GROUP;
        }

        @Override
        public String hash(io.envoyproxy.envoy.config.core.v3.Node node) {
            throw new IllegalStateException("should not have received a v3 Node in a v2 Test");
        }
    }

    protected static class WatchAndTracker {

        final Watch watch;
        final ResponseTracker tracker;

        WatchAndTracker(Watch watch, ResponseTracker tracker) {
            this.watch = watch;
            this.tracker = tracker;
        }
    }
}
