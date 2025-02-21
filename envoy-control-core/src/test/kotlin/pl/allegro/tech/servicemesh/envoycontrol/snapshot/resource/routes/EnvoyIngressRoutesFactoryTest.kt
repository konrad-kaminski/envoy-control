package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.routes

import com.google.protobuf.util.Durations
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode
import pl.allegro.tech.servicemesh.envoycontrol.groups.HealthCheck
import pl.allegro.tech.servicemesh.envoycontrol.groups.Incoming
import pl.allegro.tech.servicemesh.envoycontrol.groups.Incoming.TimeoutPolicy
import pl.allegro.tech.servicemesh.envoycontrol.groups.ProxySettings
import pl.allegro.tech.servicemesh.envoycontrol.groups.ServicesGroup
import pl.allegro.tech.servicemesh.envoycontrol.groups.adminPostAuthorizedRoute
import pl.allegro.tech.servicemesh.envoycontrol.groups.adminPostRoute
import pl.allegro.tech.servicemesh.envoycontrol.groups.adminRedirectRoute
import pl.allegro.tech.servicemesh.envoycontrol.groups.adminRoute
import pl.allegro.tech.servicemesh.envoycontrol.groups.ingressRoute
import pl.allegro.tech.servicemesh.envoycontrol.groups.configDumpAuthorizedRoute
import pl.allegro.tech.servicemesh.envoycontrol.groups.configDumpRoute
import pl.allegro.tech.servicemesh.envoycontrol.groups.hasNoRetryPolicy
import pl.allegro.tech.servicemesh.envoycontrol.groups.hasOneDomain
import pl.allegro.tech.servicemesh.envoycontrol.groups.hasOnlyRoutesInOrder
import pl.allegro.tech.servicemesh.envoycontrol.groups.hasRequestHeadersToRemove
import pl.allegro.tech.servicemesh.envoycontrol.groups.hasResponseHeaderToAdd
import pl.allegro.tech.servicemesh.envoycontrol.groups.hasSingleVirtualHostThat
import pl.allegro.tech.servicemesh.envoycontrol.groups.hasStatusVirtualClusters
import pl.allegro.tech.servicemesh.envoycontrol.groups.matchingOnAnyMethod
import pl.allegro.tech.servicemesh.envoycontrol.groups.matchingOnMethod
import pl.allegro.tech.servicemesh.envoycontrol.groups.matchingRetryPolicy
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.EndpointMatch
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.RetryPoliciesProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.RetryPolicyProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SecuredRoute
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import java.time.Duration

internal class EnvoyIngressRoutesFactoryTest {

    private val retryPolicyProps = RetryPoliciesProperties().apply {
        default = RetryPolicyProperties().apply {
            enabled = true
            retryOn = mutableSetOf("connection-failure")
            numRetries = 3
        }
        perHttpMethod = mutableMapOf(
            "GET" to RetryPolicyProperties().apply {
                enabled = true
                retryOn = mutableSetOf("reset", "connection-failure")
                numRetries = 1
                perTryTimeout = Duration.ofSeconds(1)
                hostSelectionRetryMaxAttempts = 3
            },
            "HEAD" to RetryPolicyProperties().apply {
                enabled = true
                retryOn = mutableSetOf("connection-failure")
                numRetries = 6
            },
            "POST" to RetryPolicyProperties().apply {
                enabled = false
                retryOn = mutableSetOf("connection-failure")
                numRetries = 6
            }
        )
    }

    private val adminRoutes = arrayOf(
        configDumpAuthorizedRoute(),
        configDumpRoute(),
        adminPostAuthorizedRoute(),
        adminPostRoute(),
        adminRoute(),
        adminRedirectRoute()
    )

    @Test
    fun `should create route config with health check and response timeout defined`() {
        // given
        val routesFactory = EnvoyIngressRoutesFactory(SnapshotProperties().apply {
            routes.status.enabled = true
            routes.status.endpoints = mutableListOf(EndpointMatch())
            routes.status.createVirtualCluster = true
            localService.retryPolicy = retryPolicyProps
            routes.admin.publicAccessEnabled = true
            routes.admin.token = "test_token"
            routes.admin.securedPaths.add(SecuredRoute().apply {
                pathPrefix = "/config_dump"
                method = "GET"
            })
        })
        val responseTimeout = Durations.fromSeconds(777)
        val idleTimeout = Durations.fromSeconds(61)
        val connectionIdleTimeout = Durations.fromSeconds(120)
        val proxySettingsOneEndpoint = ProxySettings(
            incoming = Incoming(
                healthCheck = HealthCheck(
                    path = "",
                    clusterName = "health_check_cluster"
                ),
                permissionsEnabled = true,
                timeoutPolicy = TimeoutPolicy(idleTimeout, responseTimeout, connectionIdleTimeout)
            )
        )
        val group = ServicesGroup(
            CommunicationMode.XDS,
            "service_1",
            proxySettingsOneEndpoint
        )

        // when
        val routeConfig = routesFactory.createSecuredIngressRouteConfig(
            "service_1",
            proxySettingsOneEndpoint,
            group
        )

        // then
        routeConfig
            .hasSingleVirtualHostThat {
                hasStatusVirtualClusters()
                hasOneDomain("*")
                hasOnlyRoutesInOrder(
                    *adminRoutes,
                    {
                        ingressRoute()
                        matchingOnMethod("GET")
                        matchingRetryPolicy(retryPolicyProps.perHttpMethod["GET"]!!)
                    },
                    {
                        ingressRoute()
                        matchingOnMethod("HEAD")
                        matchingRetryPolicy(retryPolicyProps.perHttpMethod["HEAD"]!!)
                    },
                    {
                        ingressRoute()
                        matchingOnAnyMethod()
                        hasNoRetryPolicy()
                    }
                )
                matchingRetryPolicy(retryPolicyProps.default)
            }
    }

    @Test
    fun `should create route config with headers to remove and add`() {
        // given
        val routesFactory = EnvoyIngressRoutesFactory(SnapshotProperties().apply {
            ingress.headersToRemove = mutableListOf("x-via-vip", "x-special-case-header")
            ingress.addServiceNameHeaderToResponse = true
            ingress.addRequestedAuthorityHeaderToResponse = true
        })
        val proxySettingsOneEndpoint = ProxySettings(
            incoming = Incoming(
                healthCheck = HealthCheck(
                    path = "",
                    clusterName = "health_check_cluster"
                ),
                permissionsEnabled = true
            )
        )
        val group = ServicesGroup(
            CommunicationMode.XDS,
            "service_1",
            proxySettingsOneEndpoint
        )

        // when
        val routeConfig = routesFactory.createSecuredIngressRouteConfig(
            "service_1",
            proxySettingsOneEndpoint,
            group
        )

        // then
        routeConfig
            .hasRequestHeadersToRemove(listOf("x-via-vip", "x-special-case-header"))
            .hasResponseHeaderToAdd("x-service-name", "service_1")
            .hasResponseHeaderToAdd("x-requested-authority", "%REQ(:authority)%")
    }
}
