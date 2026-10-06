package com.gatepulse;

import com.gatepulse.config.BackendDefinition;
import com.gatepulse.config.GatewayConfig;
import com.gatepulse.config.Mode;
import com.gatepulse.dummy.DummyBackendServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Process entry point.
 *
 * <p>Reads configuration from environment variables, starts the embedded dummy backends when
 * {@code MODE=embedded}, starts the gateway, and registers a shutdown hook so that
 * {@code SIGTERM} (what Docker and Render send on stop/redeploy) shuts everything down in order.
 */
public final class GatewayApplication {

    private static final Logger log = LoggerFactory.getLogger(GatewayApplication.class);

    private GatewayApplication() {
    }

    public static void main(String[] args) {
        GatewayConfig config;
        try {
            config = GatewayConfig.fromEnvironment();
        } catch (IllegalArgumentException e) {
            // Fail fast with a readable message instead of a stack trace.
            System.err.println("Invalid configuration: " + e.getMessage());
            System.exit(2);
            return;
        }

        List<DummyBackendServer> embeddedBackends = new ArrayList<>();
        if (config.mode() == Mode.EMBEDDED) {
            for (BackendDefinition def : config.backends()) {
                embeddedBackends.add(new DummyBackendServer(def.id(), def.baseUri().getPort()).start());
            }
        }

        GatewayServer gateway = new GatewayServer(config).start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("event=shutdown_requested");
            gateway.close();                                // 1. stop taking traffic
            embeddedBackends.forEach(DummyBackendServer::close); // 2. then stop the backends
        }, "gateway-shutdown"));
    }
}
