package pl.allegro.tech.servicemesh.envoycontrol

import okhttp3.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.Envoy1Ads
import pl.allegro.tech.servicemesh.envoycontrol.config.Envoy2Ads
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer

internal class SourceIpBasedAuthenticationTest : EnvoyControlTestConfiguration() {

    companion object {

        private val properties = mapOf(
            "envoy-control.envoy.snapshot.incoming-permissions.enabled" to true,
            "envoy-control.envoy.snapshot.incoming-permissions.sourceIpAuthentication.enabledForServices" to listOf("echo"),
            "envoy-control.envoy.snapshot.routes.status.create-virtual-cluster" to true,
            "envoy-control.envoy.snapshot.routes.status.path-prefix" to "/status/",
            "envoy-control.envoy.snapshot.routes.status.enabled" to true
        )

        @JvmStatic
        @BeforeAll
        fun setupTest() {
            setup(appFactoryForEc1 = { consulPort ->
                EnvoyControlRunnerTestApp(properties = properties, consulPort = consulPort)
            }, envoy1Config = Envoy1Ads, envoy2Config = Envoy2Ads, envoys = 2)
        }
    }

    @Test
    fun `should allow access to selected clients using source based authentication`() {
        registerService(name = "echo")
        registerEcho2WithEnvoyOnIngress()

        untilAsserted {
            // when
            val validResponse = callEcho2ThroughEnvoy1()
            val invalidResponse = callEcho2ThroughEnvoy2Ingress()

            // then
            assertThat(validResponse).isOk().isFrom(echoContainer2)
            assertThat(invalidResponse).isForbidden()
        }
    }

    private fun registerEcho2WithEnvoyOnIngress() {
        registerService(
                id = "echo2",
                name = "echo2", address = envoyContainer2.ipAddress(),
                port = EnvoyContainer.INGRESS_LISTENER_CONTAINER_PORT
        )
    }

    private fun callEcho2ThroughEnvoy2Ingress(): Response {
        return callService(
                service = "local_service",
                address = envoyContainer2.ingressListenerUrl(),
                pathAndQuery = "/ip_endpoint"
        )
    }

    private fun callEcho2ThroughEnvoy1() = callService(service = "echo2", pathAndQuery = "/ip_endpoint")
}
