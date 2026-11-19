package att.ossama.gatewayservice.systemhealth;

import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * The one {@link WebClient} the health aggregator is allowed to use.
 *
 * <p>The timeouts are the point of this class. A fan-out is only as fast as its
 * worst member: without a response timeout, one service whose management port
 * accepts the connection and then never answers holds the whole aggregation
 * open — so the dashboard stops updating precisely when something is broken,
 * which is when it is being looked at. Two seconds per probe, 8 services in
 * parallel, worst case just over two seconds for the entire page.
 */
@Configuration
public class HealthAggregationConfig {

    /**
     * Not the auto-configured {@code WebClient.Builder} bean, which is shared
     * with {@code KeycloakAdminClient} and everything else: its timeouts are
     * whatever the caller set, and this client needs its own short ones.
     */
    @Bean
    public WebClient healthProbeWebClient(
            WebClient.Builder builder,
            @Value("${gateway.health.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${gateway.health.response-timeout-ms:2000}") int responseTimeoutMs) {

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .responseTimeout(Duration.ofMillis(responseTimeoutMs));

        return builder.clone()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
