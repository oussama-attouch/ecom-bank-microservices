package att.ossama.ledgerservice.dashboard;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Supplies the application's {@link Clock}.
 *
 * <p>The KPI trend windows are relative to "now", so time is injected rather
 * than read statically: a test can pin now and assert the exact boundary between
 * the current and previous windows. The system default zone is deliberate —
 * "today" for a user is the day they are living in, not UTC.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
